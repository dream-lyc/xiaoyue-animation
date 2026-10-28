package cn.childtv

import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant

class BackupRepository(private val store: LocalStore) {
    private val collections = listOf("sources", "library", "assets", "tags", "watch", "progress", "daily-state", "episode-assets", "checkpoints")
    fun export(): JSONObject = exportInternal(false)
    private fun exportInternal(allowMissingArtwork: Boolean): JSONObject = store.transaction {
        val content = JSONObject()
        collections.forEach { name -> content.put(name, store.list(name).map { row ->
            JSONObject(row.toString()).also {
                if (name == "sources") it.put("credentialConfigured", false)
                if (name == "daily-state") { it.remove("extraEpisodeId"); it.remove("extraProgress") }
            }
        }.jsonArray()) }
        val artwork = ArtworkRepository(store)
        val covers = if (!allowMissingArtwork) artwork.exportFiles() else JSONArray().also { result ->
            val seen = mutableSetOf<String>(); var encodedTotal = 0L
            store.list("library").forEach { row ->
                val cacheKey = row.optString("artworkCacheKey")
                if (cacheKey.isNotBlank() && seen.add(cacheKey)) try {
                    val file = artwork.fileFor(row) ?: throw IllegalArgumentException("缓存缺失")
                    encodedTotal += ((file.length() + 2) / 3) * 4
                    require(encodedTotal <= 32L * 1024 * 1024) { "恢复前封面快照超过容量" }
                    val item = JSONObject().put("cacheKey", cacheKey).put("sha256", cacheKey).put("mediaType", artwork.mediaType(file)).put("data", java.util.Base64.getEncoder().encodeToString(file.readBytes()))
                    artwork.validateFiles(JSONArray().put(item)); result.put(item)
                } catch (_: Exception) { /* The snapshot retains metadata and marks the missing image explicitly. */ }
            }
        }
        content.put("covers", covers)
        if (allowMissingArtwork) {
            val available = (0 until covers.length()).map { covers.getJSONObject(it).getString("cacheKey") }.toSet()
            val rows = content.getJSONArray("library")
            (0 until rows.length()).forEach { index -> val row = rows.getJSONObject(index); if (row.optString("artworkCacheKey").isNotBlank() && row.optString("artworkCacheKey") !in available) { row.remove("artworkCacheKey"); row.put("artworkCacheMissing", true) } }
        }
        content.put("watchRemainders", JSONObject().also { counters ->
            store.list("settings").filter { it.getString("id").startsWith("remainder:") }.forEach { counters.put(it.getString("id").removePrefix("remainder:"), it.getString("value").toLong()) }
        })
        content.put("defaultMinutes", store.getSetting("defaultMinutes", "45")).put("timezone", store.getSetting("timezone", "Asia/Shanghai"))
        val payload = content.toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024) { "备份超过当前 32 MiB 容量，请保留现有数据并联系维护者导出数据库" }
        JSONObject().put("version", 1).put("createdAt", Instant.now().toString()).put("payload", payload).put("sha256", BackupIntegrity.digest(payload))
    }
    private fun validate(content: JSONObject) {
        fun rows(name: String): List<JSONObject> { val array = if (!content.has(name) && name == "checkpoints") org.json.JSONArray() else content.getJSONArray(name); return (0 until array.length()).map { array.getJSONObject(it) }.also { values -> require(values.map { it.getString("id") }.distinct().size == values.size) { "备份含重复 ID" } } }
        if (content.has("watchRemainders")) {
            val counters = content.getJSONObject("watchRemainders")
            counters.keys().forEach { day ->
                java.time.LocalDate.parse(day)
                val value = counters.get(day); require(value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() in 0..999)
            }
        }
        rows("checkpoints").forEach { require(it.getString("id").isNotBlank()) }
        val sourceIds = rows("sources").map { it.getString("id") }.toSet()
        val episodeIds = rows("library").map { it.getString("id") }.toSet()
        val assetIds = rows("assets").map { it.getString("id") }.toSet()
        fun tags(value: JSONObject) { value.keys().forEach { key -> require(key.isNotBlank()); require(value.getJSONArray(key).strings().all { it.isNotBlank() }) } }
        rows("sources").forEach { row ->
            require(row.getString("type") in listOf("openlist", "alist", "webdav", "smb")); require(row.getString("name").isNotBlank()); val uri = java.net.URI(row.getString("endpoint")); require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.scheme in if (row.getString("type") == "smb") setOf("smb") else setOf("http", "https")); require(row.getJSONArray("roots").strings().all { !it.split('/').contains("..") })
        }
        rows("assets").forEach { require(it.getString("sourceId") in sourceIds && it.getLong("size") >= 0); require(!it.getString("path").split('/').contains("..")) }
        rows("library").forEach { row ->
            listOf("title", "seriesTitle", "libraryName").forEach { require(row.getString(it).isNotBlank()) }; require(row.getInt("seasonNumber") >= 0 && row.getInt("episodeNumber") > 0); require(row.getString("status") in listOf("matched", "pending", "unmatched")); row.getJSONArray("lockedFields").strings(); tags(row.getJSONObject("tags")); if (row.has("assetId")) require(row.getString("assetId") in assetIds)
        }
        rows("tags").forEach { require(it.getString("key").isNotBlank()); require(it.getJSONArray("values").strings().all { value -> value.isNotBlank() }) }
        rows("episode-assets").forEach { require(it.getString("episodeId") in episodeIds && it.getString("assetId") in assetIds); require(it.optLong("startMs") >= 0); if (it.has("endMs")) require(it.getLong("endMs") > it.optLong("startMs")) }
        rows("progress").forEach { require(it.getString("id") in episodeIds && it.getLong("positionMs") >= 0) }
        rows("watch").forEach { row ->
            require(row.getString("episodeId") in episodeIds); java.time.LocalDate.parse(row.getString("date")); java.time.Instant.parse(row.getString("startedAt")); require(row.optLong("durationMillis", row.getLong("seconds") * 1000) > 0); require(row.getString("kind") in listOf("normal", "grace", "parent_extra"))
        }
        rows("daily-state").forEach { row ->
            java.time.LocalDate.parse(row.getString("id")); require(row.getLong("totalSeconds") >= 0 && row.getLong("watchedSeconds") >= 0); cn.childtv.core.PolicyMode.valueOf(row.getString("mode")); if (row.has("overrideSeconds")) require(row.getLong("overrideSeconds") >= 0)
            row.optJSONObject("finishing")?.let { require(it.getString("episodeId") in episodeIds); require(it.getLong("positionMs") >= 0 && it.getLong("endMs") > it.getLong("positionMs")) }
        }
    }

    fun restore(backup: JSONObject, beforeCommit: () -> Unit = {}): JSONObject {
        require(backup.getInt("version") == 1) { "备份版本不兼容" }
        val payload = backup.getString("payload")
        require(payload.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024 && BackupIntegrity.valid(payload, backup.getString("sha256"))) { "备份完整性校验失败" }
        val content = JSONObject(payload)
        val minutes = content.getString("defaultMinutes").toInt(); require(minutes in 0..1440)
        java.time.ZoneId.of(content.getString("timezone"))
        val allowedFields = collections.toSet() + setOf("defaultMinutes", "timezone", "watchRemainders", "covers")
        require(content.keys().asSequence().all { it in allowedFields })
        collections.forEach { name -> val array = if (!content.has(name) && name == "checkpoints") org.json.JSONArray() else content.getJSONArray(name); require(array.length() <= 1_000_000); (0 until array.length()).forEach { index -> require(array.getJSONObject(index).getString("id").isNotBlank()) } }
        validate(content)
        val covers = if (content.has("covers")) content.getJSONArray("covers") else JSONArray()
        val artwork = ArtworkRepository(store); artwork.validateFiles(covers)
        val keys = (0 until covers.length()).map { covers.getJSONObject(it).getString("cacheKey") }.toSet()
        val episodes = content.getJSONArray("library")
        (0 until episodes.length()).forEach { index -> episodes.getJSONObject(index).optString("artworkCacheKey").takeIf { it.isNotBlank() }?.let { require(it in keys) { "备份缺少引用的封面文件" } } }
        val restored = collections.associateWith { name ->
            val array = content.optJSONArray(name) ?: org.json.JSONArray()
            (0 until array.length()).map { index -> JSONObject(array.getJSONObject(index).toString()).also { row ->
                if (name == "sources") row.put("credentialConfigured", false)
                if (name == "daily-state") { row.remove("extraEpisodeId"); row.remove("extraProgress") }
            } }.toMutableList()
        }.toMutableMap()
        if (!content.has("checkpoints")) restored["checkpoints"] = restored.getValue("watch").map { event -> JSONObject().put("id", event.getString("id").removeSuffix(":normal").removeSuffix(":grace")) }.distinctBy { it.getString("id") }.toMutableList()
        val remainders = content.optJSONObject("watchRemainders") ?: JSONObject().also { counters -> restored.getValue("watch").groupBy { it.getString("date") }.forEach { (day, events) -> counters.put(day, events.sumOf { it.optLong("durationMillis", it.optLong("seconds") * 1000) } % 1000) } }
        artwork.prepareRestore(covers)
        store.transaction {
            beforeCommit()
            store.put("restore-snapshots", JSONObject().put("backup", exportInternal(true)).put("createdAt", Instant.now().toString()))
            store.replaceAll(restored)
            store.list("settings").filter { it.getString("id").startsWith("remainder:") }.forEach { store.delete("settings", it.getString("id")) }
            remainders.keys().forEach { day -> store.setSetting("remainder:$day", remainders.getLong(day).toString()) }
            store.setSetting("defaultMinutes", minutes.toString()); store.setSetting("timezone", content.getString("timezone"))
        }
        return JSONObject().put("message", "已恢复片库、历史和策略；请重新输入资源凭据")
    }
}
