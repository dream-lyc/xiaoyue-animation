package cn.childtv

import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Images are bounded and addressed by their content; video and credentials never enter this cache. */
internal class ArtworkWorkState {
    val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    data class Failure(val signature: String, val retryAt: Long)
    val failures = java.util.concurrent.ConcurrentHashMap<String, Failure>()
}
class ArtworkRepository(private val store: LocalStore, private val frameFallback: ((JSONObject, () -> Boolean) -> JSONObject)? = null, private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val maxBytes = 4 * 1024 * 1024
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private fun key(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 255) }
    private fun type(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
        bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        else -> throw IllegalArgumentException("封面格式不支持")
    }
    private fun validateImage(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "单张封面不能超过 4 MiB" }; type(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000) { "封面尺寸超过支持范围" }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("封面图片损坏")
        decoded.recycle()
    }
    private fun persist(cacheKey: String, bytes: ByteArray) {
        require(cacheKey.matches(Regex("[0-9a-f]{64}")))
        check(store.artworkDirectory.isDirectory || store.artworkDirectory.mkdirs()) { "无法创建封面目录" }
        val target = File(store.artworkDirectory, cacheKey)
        if (target.isFile && key(target.readBytes()) == cacheKey) return
        val temporary = File.createTempFile("cover-", ".tmp", store.artworkDirectory)
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(target)) { "无法提交封面缓存" }
        } finally { temporary.delete() }
    }
    fun cacheBytes(episodeId: String, bytes: ByteArray, origin: String = "local"): JSONObject {
        require(origin in setOf("local", "online", "video_frame")); validateImage(bytes)
        val cacheKey = key(bytes); persist(cacheKey, bytes)
        return store.transaction {
            val row = store.get("library", episodeId) ?: throw ApiFailure(404, "剧集不存在")
            store.put("library", row.put("artworkCacheKey", cacheKey).put("artworkOrigin", origin))
            requireNotNull(store.get("library", episodeId))
        }
    }
    internal fun signature(row: JSONObject): String = listOf(store.catalogEpoch.get().toString(), row.optString("artworkUrl"), row.optString("artworkCacheKey"), row.optJSONArray("lockedFields")?.toString().orEmpty(), row.optString("assetId"), row.optBoolean("available").toString()).joinToString("\u0000")
    fun cacheEpisode(episode: JSONObject, stillCurrent: () -> Boolean = { true }): JSONObject {
        val id = episode.getString("id")
        return synchronized(store.artworkWork.locks.computeIfAbsent(id) { Any() }) {
            val original = store.get("library", id) ?: return@synchronized episode
            val fingerprint = signature(original)
            val cached = fileFor(original)
            val url = original.optString("artworkUrl")
            val retry = store.artworkWork.failures[id]
            if (cached != null && (original.optString("artworkOrigin") != "video_frame" || url.isBlank())) return@synchronized original
            if (retry != null && retry.signature == fingerprint && nowMillis() < retry.retryAt) return@synchronized original
            val current = { stillCurrent() && store.get("library", id)?.let { signature(it) == fingerprint } == true }
            var result = original
            var onlineFailed = false
            if (url.isNotBlank()) {
                result = try {
                    val uri = URI(url); require(uri.scheme in setOf("http", "https") && uri.userInfo == null)
                    val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        check(response.isSuccessful); val body = requireNotNull(response.body); require(body.contentLength() <= maxBytes)
                        body.byteStream().use { input ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= maxBytes); output.write(buffer, 0, count) }
                            output.toByteArray()
                        }
                    }
                    store.transaction { if (!current()) store.get("library", id) ?: original else cacheBytes(id, bytes, "online") }
                } catch (_: Exception) { onlineFailed = true; store.get("library", id) ?: original }
            }
            if (fileFor(result) == null && current()) result = try { frameFallback?.invoke(result, current) ?: result } catch (_: Exception) { store.get("library", id) ?: result }
            if (onlineFailed || fileFor(result) == null) store.artworkWork.failures[id] = ArtworkWorkState.Failure(signature(result), nowMillis() + 30_000)
            else store.artworkWork.failures.remove(id)
            result
        }
    }
    /** A series borrows a representative episode image; it never replaces another episode's artwork. */
    fun cacheSeries(episodes: List<JSONObject>): JSONObject? {
        val rows = episodes.map { store.get("library", it.getString("id")) ?: it }.sortedWith(compareBy({ it.optInt("seasonNumber", 1) }, { it.optInt("episodeNumber", 1) }, { it.getString("id") }))
        rows.firstOrNull { fileFor(it) != null && it.optString("artworkOrigin") != "video_frame" }?.let { return it }
        val manual = rows.filter { it.optString("artworkUrl").isNotBlank() && "artworkUrl" in it.optJSONArray("lockedFields")?.strings().orEmpty() }
        manual.forEach { row -> cacheEpisode(row).let { if (fileFor(it) != null) return it } }
        rows.firstOrNull { fileFor(it) != null }?.let { return it }
        rows.filter { it.optBoolean("available") || it.optString("artworkUrl").isNotBlank() }.forEach { row -> cacheEpisode(row).let { if (fileFor(it) != null) return it } }
        return rows.firstOrNull()
    }
    fun fileFor(episode: JSONObject): File? {
        val cacheKey = episode.optString("artworkCacheKey")
        if (!cacheKey.matches(Regex("[0-9a-f]{64}"))) return null
        return File(store.artworkDirectory, cacheKey).takeIf { file ->
            if (!file.isFile || file.length() !in 1..maxBytes.toLong()) false else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000
            }
        }
    }
    fun mediaType(file: File): String = file.inputStream().use { input -> val head = ByteArray(12); val length = input.read(head); type(head.copyOf(length.coerceAtLeast(0))) }
    fun exportFiles(): JSONArray {
        val files = store.list("library").mapNotNull { row -> row.optString("artworkCacheKey").takeIf { it.isNotBlank() }?.let { it to row } }.distinctBy { it.first }
        var exportedBytes = 0L
        return files.map { (cacheKey, episode) ->
            val bytes = (fileFor(episode) ?: throw IllegalArgumentException("封面缓存缺失，请重新获取封面后备份")).readBytes()
            exportedBytes += ((bytes.size.toLong() + 2) / 3) * 4
            require(exportedBytes <= 32L * 1024 * 1024) { "封面总量超过 32 MiB 备份容量" }
            require(key(bytes) == cacheKey) { "封面完整性校验失败" }; validateImage(bytes)
            JSONObject().put("cacheKey", cacheKey).put("sha256", cacheKey).put("mediaType", type(bytes)).put("data", Base64.getEncoder().encodeToString(bytes))
        }.jsonArray()
    }
    fun validateFiles(files: JSONArray) {
        require(files.length() <= 10000)
        val seen = mutableSetOf<String>(); var totalEncoded = 0L
        (0 until files.length()).forEach { index ->
            val item = files.getJSONObject(index); val cacheKey = item.getString("cacheKey"); require(seen.add(cacheKey))
            val encoded = item.getString("data"); totalEncoded += encoded.length; require(totalEncoded <= 32L * 1024 * 1024); require(encoded.length <= ((maxBytes + 2) / 3) * 4)
            val bytes = Base64.getDecoder().decode(encoded)
            require(cacheKey == key(bytes) && item.getString("sha256") == cacheKey) { "封面完整性校验失败" }
            validateImage(bytes); require(item.optString("mediaType", type(bytes)) == type(bytes))
        }
    }
    fun prepareRestore(files: JSONArray) {
        validateFiles(files)
        (0 until files.length()).forEach { index -> val item = files.getJSONObject(index); persist(item.getString("cacheKey"), Base64.getDecoder().decode(item.getString("data"))) }
    }
}
