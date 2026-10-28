package cn.childtv

import cn.childtv.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Persisted work state is kept before dispatch. Interrupted scans can be retried idempotently. */
class SourceRepository(private val store: LocalStore, private val vault: CredentialVault, private val metadataFactory: (() -> MetadataPipeline)? = null, private val worker: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(), private val frameDecoder: (SourceAdapter, SourceFile, Long) -> ByteArray? = { adapter, file, position -> FrameArtworkExtractor().extract(adapter, file, position) }) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "metadata-retry").apply { isDaemon = true } }
    private var providerKeys: Pair<String?, String?>? = null
    private var cachedPipeline: MetadataPipeline? = null
    @Synchronized private fun pipeline(): MetadataPipeline {
        metadataFactory?.let { return it() }
        val keys = vault.get("openSubtitlesKey") to vault.get("tmdbKey")
        if (cachedPipeline == null || providerKeys != keys) {
            cachedPipeline = MetadataPipeline(keys.first?.let { OpenSubtitlesProvider(it) }, keys.second?.let { TmdbProvider(it) })
            providerKeys = keys
        }
        return requireNotNull(cachedPipeline)
    }
    private val adapterCache = mutableMapOf<String, Pair<String, SourceAdapter>>()
    private fun sourceSecret(source: JSONObject) = if (source.optBoolean("credentialConfigured")) vault.get("source:${source.getString("id")}")?.let(::JSONObject) ?: JSONObject() else JSONObject()
    fun effectiveAuthMode(source: JSONObject): String {
        val mode = source.optString("authMode", "legacy")
        if (mode in setOf("password", "token")) return mode
        return if (sourceSecret(source).optString("username").isNotBlank()) "password" else "token"
    }
    fun describe(source: JSONObject): JSONObject = JSONObject(source.toString()).put("authMode", effectiveAuthMode(source))
    @Synchronized private fun adapter(source: JSONObject): SourceAdapter {
        val secret = sourceSecret(source)
        val mode = source.optString("authMode", "legacy").takeIf { it in setOf("password", "token") } ?: if (secret.optString("username").isNotBlank()) "password" else "token"
        val configuration = listOf(source.getString("type"), source.getString("endpoint"), mode, secret.optString("username"), secret.optString("secret")).joinToString("\u0000")
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(configuration.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val id = source.getString("id")
        adapterCache[id]?.takeIf { it.first == fingerprint }?.let { return it.second }
        val adapter = when (source.getString("type")) {
            "openlist", "alist" -> if (mode == "password") OpenListAdapter(source.getString("endpoint"), username = secret.optString("username"), password = secret.optString("secret"), clientId = "child-tv-$id") else OpenListAdapter(source.getString("endpoint"), secret.optString("secret"), clientId = "child-tv-$id")
            "webdav" -> WebDavAdapter(source.getString("endpoint"), secret.optString("username"), secret.optString("secret"))
            "smb" -> SmbAdapter(source.getString("endpoint"), secret.optString("username"), secret.optString("secret"))
            else -> throw ApiFailure(400, "不支持的源类型")
        }
        adapterCache[id] = fingerprint to adapter
        return adapter
    }
    private fun source(id: String) = store.get("sources", id) ?: throw ApiFailure(404, "资源源不存在")
    fun test(id: String): JSONObject {
        val source = source(id); val adapter = adapter(source)
        val roots = source.optJSONArray("roots")?.strings().orEmpty().distinct()
        if (roots.isEmpty()) check(adapter.testConnection()) { "连接测试失败" }
        else roots.forEach { adapter.listFiles(it) }
        return JSONObject().put("message", "连接成功").put("testedScope", if (roots.isEmpty()) "root" else "selected")
    }
    fun directories(id: String, path: String): JSONObject {
        require(!path.split('/').contains(".."))
        val adapter = adapter(source(id)); val rows = mutableListOf<JSONObject>(); var page: String? = null
        do { val result = adapter.listDirectories(path, page); rows += result.files.filter { it.directory }.map { JSONObject().put("name", it.path.substringAfterLast('/')).put("path", it.path) }; page = result.nextPage } while (page != null)
        return JSONObject().put("path", path).put("directories", rows.jsonArray())
    }
    private fun generation(job: JSONObject): String = job.optJSONObject("cursor")?.optString("generation").orEmpty()
    private fun stamp(job: JSONObject): JSONObject = job.put("cursor", (job.optJSONObject("cursor") ?: JSONObject()).put("generation", UUID.randomUUID().toString()))
    private fun current(id: String, expected: String): Boolean = store.get("scans", id)?.let { it.optString("status") == "running" && generation(it) == expected } == true
    fun enqueue(id: String): JSONObject {
        val source = source(id); require(source.getJSONArray("roots").length() > 0) { "请先选择目录" }
        val job = store.put("scans", stamp(JSONObject().put("sourceId", id).put("status", "running").put("stage", "discovering").put("discovered", 0).put("matched", 0).put("completed", 0)))
        dispatch(job.getString("id")); return job
    }
    fun cancel(id: String): JSONObject = store.transaction { store.put("scans", stamp(store.get("scans", id) ?: throw ApiFailure(404, "任务不存在")).put("status", "cancelled").put("stage", "cancelled")) }
    fun retry(id: String): JSONObject {
        val job = store.transaction {
            val row = store.get("scans", id) ?: throw ApiFailure(404, "任务不存在"); require(row.optString("status") != "running")
            if (row.optString("status") == "waiting" && (row.optJSONObject("cursor")?.optLong("retryAtMillis") ?: 0) > System.currentTimeMillis()) row else {
                if (row.optString("status") == "completed") row.remove("cursor")
                if (row.optString("status") == "failed" && (row.optJSONObject("cursor")?.optInt("metadataAttempt") ?: 0) >= 3) {
                    row.optJSONObject("cursor")?.put("metadataAttempt", 0)?.put("retryAtMillis", 0)
                    synchronized(this) { cachedPipeline = null }
                }
                row.remove("error"); store.put("scans", stamp(row).put("status", "running"))
            }
        }
        if (job.optString("status") == "running") dispatch(id)
        return job
    }
    fun recover() {
        store.list("scans").forEach { old ->
            when (old.optString("status")) {
                "running" -> store.transaction { val row = store.get("scans", old.getString("id")); if (row?.optString("status") == "running") store.put("scans", stamp(row).put("status", "failed").put("error", "上次扫描中断，请继续；断点及已有文件保留")) }
                "waiting" -> scheduleRetry(old.getString("id"), generation(old), old.getJSONObject("cursor").getLong("retryAtMillis"))
            }
        }
    }
    private fun scheduleRetry(id: String, expected: String, deadline: Long) {
        scheduler.schedule({
            val resume = store.transaction {
                val row = store.get("scans", id)
                if (row?.optString("status") == "waiting" && generation(row) == expected) { store.put("scans", stamp(row).put("status", "running")); true } else false
            }
            if (resume && !worker.isShutdown) dispatch(id)
        }, (deadline - System.currentTimeMillis()).coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    private fun dispatch(id: String) { val expected = store.get("scans", id)?.let(::generation) ?: return; worker.execute {
        if (!current(id, expected)) return@execute
        val job = store.get("scans", id) ?: return@execute
        try {
            val source = source(job.getString("sourceId")); val adapter = adapter(source)
            val roots = source.getJSONArray("roots").strings()
            val prior = job.optJSONObject("cursor")?.takeIf { it.optJSONArray("roots")?.strings() == roots }
            val queue = ArrayDeque(prior?.optJSONArray("pendingDirectories")?.strings() ?: roots)
            val completedDirectories = prior?.optJSONArray("completedDirectories")?.strings()?.toMutableSet() ?: mutableSetOf()
            val seenAssets = prior?.optJSONArray("seenAssets")?.strings()?.toMutableSet() ?: mutableSetOf()
            var currentDirectory = prior?.optString("currentDirectory")?.takeIf { it.isNotBlank() }
            var page = prior?.optString("page")?.takeIf { it.isNotBlank() }
            var fileIndex = prior?.optInt("fileIndex") ?: 0
            var metadataAttempt = prior?.optInt("metadataAttempt") ?: 0
            var fileDiscovered = prior?.optBoolean("fileDiscovered") ?: false
            var retryAtMillis = prior?.optLong("retryAtMillis") ?: 0L
            var discovered = if (prior == null) 0 else job.optInt("discovered")
            var matched = if (prior == null) 0 else job.optInt("matched")
            fun persistCursor(stage: String): Boolean = store.transaction {
                val latest = store.get("scans", id)
                if (latest == null || !current(id, expected)) false else {
                    val cursor = JSONObject().put("generation", expected).put("metadataAttempt", metadataAttempt).put("fileDiscovered", fileDiscovered).put("retryAtMillis", retryAtMillis).put("roots", JSONArray(roots)).put("pendingDirectories", JSONArray(queue.toList())).put("completedDirectories", JSONArray(completedDirectories.toList())).put("seenAssets", JSONArray(seenAssets.toList())).put("currentDirectory", currentDirectory).put("page", page).put("fileIndex", fileIndex)
                    store.put("scans", latest.put("cursor", cursor).put("stage", stage).put("discovered", discovered).put("matched", matched).put("completed", discovered)); true
                }
            }
            while (currentDirectory != null || queue.isNotEmpty()) {
                if (!current(id, expected)) return@execute
                if (currentDirectory == null) { currentDirectory = queue.removeFirst(); page = null; fileIndex = 0 }
                val directory = requireNotNull(currentDirectory)
                if (directory in completedDirectories) { currentDirectory = null; continue }
                require(completedDirectories.size + queue.size <= 10000) { "目录数过多，请缩小扫描范围" }
                if (!persistCursor("discovering")) return@execute
                val result = adapter.listFiles(directory, page)
                for (index in fileIndex until result.files.size) {
                    if (!current(id, expected)) return@execute
                    val file = result.files[index]
                    if (file.directory) {
                        if (file.path !in completedDirectories && file.path !in queue) queue.add(file.path)
                    } else if (file.path.substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts")) {
                        val assetId = UUID.nameUUIDFromBytes("${source.getString("id")}:${file.stableId ?: file.path}".toByteArray()).toString()
                        if (assetId !in seenAssets) {
                            val episode = store.transaction {
                                if (!current(id, expected)) null else {
                                    store.put("assets", JSONObject().put("id", assetId).put("stableId", file.stableId).put("sourceId", source.getString("id")).put("path", file.path).put("size", file.size).put("available", true))
                                    val existing = store.list("library").firstOrNull { it.optString("assetId") == assetId && !it.optBoolean("deleted") }
                                    if (existing == null) {
                                        val name = file.path.substringAfterLast('/').substringBeforeLast('.'); val number = FilenameParser.parse(name)
                                        store.put("library", JSONObject().put("assetId", assetId).put("title", name).put("seriesTitle", directory.substringAfterLast('/').ifBlank { source.getString("name") }).put("libraryName", source.getString("name")).put("seasonNumber", number?.season ?: 1).put("episodeNumber", number?.episode ?: discovered + 1).put("synopsis", "").put("status", if (number == null) "unmatched" else "pending").put("available", true).put("lockedFields", JSONArray()).put("tags", JSONObject()))
                                    } else store.put("library", existing.put("available", true))
                                }
                            } ?: return@execute
                            val enriched = enrich(episode, adapter, file, id, expected)
                            if (!fileDiscovered) { discovered++; fileDiscovered = true }
                            if (enriched.has("metadataRetryAt") && metadataAttempt < 2) {
                                metadataAttempt++; retryAtMillis = maxOf(enriched.getLong("metadataRetryAt"), System.currentTimeMillis() + 50)
                                if (!persistCursor("waiting_retry")) return@execute
                                val waiting = store.transaction { val latest = store.get("scans", id); if (current(id, expected) && latest != null) { store.put("scans", latest.put("status", "waiting")); true } else false }
                                if (waiting) scheduleRetry(id, expected, retryAtMillis)
                                return@execute
                            }
                            if (enriched.has("metadataRetryAt")) {
                                metadataAttempt = 3; retryAtMillis = enriched.getLong("metadataRetryAt")
                                if (!persistCursor("retry_exhausted")) return@execute
                                store.transaction { val latest = store.get("scans", id); if (latest != null && current(id, expected)) store.put("scans", latest.put("status", "failed").put("error", "资料服务重试3次仍失败；断点已保存，可稍后继续或手动维护")) }
                                return@execute
                            }
                            if (enriched.optString("status") == "matched") matched++
                            metadataAttempt = 0; fileDiscovered = false; retryAtMillis = 0; seenAssets += assetId
                        }
                    }
                    fileIndex = index + 1
                    if (!persistCursor("matching")) return@execute
                }
                page = result.nextPage; fileIndex = 0
                if (page == null) { completedDirectories += directory; currentDirectory = null }
                if (!persistCursor("discovering")) return@execute
            }
            store.transaction {
                val latest = store.get("scans", id)
                if (latest != null && current(id, expected)) {
                    fun inRoot(path: String, root: String): Boolean {
                        val normalized = root.trimEnd('/').ifEmpty { "/" }
                        return normalized == "/" || path == normalized || path.startsWith("$normalized/")
                    }
                    store.list("assets").filter { it.getString("sourceId") == source.getString("id") && roots.any { root -> inRoot(it.getString("path"), root) } && it.getString("id") !in seenAssets }.forEach { asset -> store.put("assets", asset.put("available", false)) }
                    val assets = store.list("assets").associateBy { it.getString("id") }
                    val mappings = store.list("episode-assets").groupBy { it.getString("episodeId") }
                    store.list("library").forEach { episode ->
                        val ids = mappings[episode.getString("id")]?.map { it.getString("assetId") }.orEmpty().ifEmpty { listOfNotNull(episode.optString("assetId").takeIf { it.isNotBlank() }) }
                        if (ids.any { assets[it]?.optString("sourceId") == source.getString("id") }) store.put("library", episode.put("available", ids.any { assets[it]?.optBoolean("available") == true }))
                    }
                    store.put("scans", latest.put("status", "completed").put("stage", "completed").put("error", if (vault.get("tmdbKey") == null && vault.get("openSubtitlesKey") == null && metadataFactory == null) "自动资料服务未配置，可手动维护未匹配资源" else ""))
                }
            }
        } catch (e: Exception) { store.transaction { val latest = store.get("scans", id); if (latest != null && current(id, expected)) store.put("scans", latest.put("status", "failed").put("error", "扫描失败：${e.javaClass.simpleName}；已有资料保持不变，请检查连接后重试")) } }
    } }
    private fun enrich(episode: JSONObject, adapter: SourceAdapter, file: SourceFile, scanJobId: String? = null, scanGeneration: String? = null): JSONObject {
        if (scanJobId != null && !current(scanJobId, scanGeneration.orEmpty())) return episode
        fun readNfo(path: String): NfoMetadata? = try {
            val stat = adapter.stat(path)
            if (stat.size in 1..1_048_576) NfoParser.parse(String(adapter.openRange(path, 0, stat.size.toInt()), Charsets.UTF_8)) else null
        } catch (_: SourceException) { null } catch (_: IllegalArgumentException) { null } catch (_: org.xml.sax.SAXException) { null }
        val nfo = readNfo(file.path.substringBeforeLast('.') + ".nfo")
        val directory = file.path.substringBeforeLast('/', "")
        val seriesNfo = readNfo("$directory/tvshow.nfo") ?: directory.takeIf { it.isNotEmpty() }?.substringBeforeLast('/', "")?.let { readNfo("$it/tvshow.nfo") }
        val locked = episode.optJSONArray("lockedFields")?.strings().orEmpty().map { if (it == "synopsis") "plot" else it }.toSet()
        val manual = MetadataFields(mapOf("title" to episode.optString("title"), "plot" to episode.optString("synopsis"), "seriesTitle" to episode.optString("seriesTitle"), "seasonNumber" to episode.optInt("seasonNumber", 1).toString(), "episodeNumber" to episode.optInt("episodeNumber", 1).toString()), locked)
        val pipeline = pipeline()
        val result = pipeline.match(adapter, file, nfo = nfo, manual = manual, confirmedIdentity = episode.optString("confirmedIdentity").takeIf { it.isNotBlank() }, confirmedNumber = if (episode.optString("confirmedIdentity").isNotBlank()) EpisodeNumber(episode.optInt("seasonNumber", 1), episode.optInt("episodeNumber", 1)) else null, seriesNfo = seriesNfo)
        val saved = store.transaction {
            val latest = store.get("library", episode.getString("id")) ?: return@transaction episode
            if (scanJobId != null && !current(scanJobId, scanGeneration.orEmpty())) return@transaction latest
            if (latest.optString("confirmedIdentity") != episode.optString("confirmedIdentity")) return@transaction latest
            val locks = latest.optJSONArray("lockedFields")?.strings().orEmpty()
            result.fields.values["title"]?.let { if ("title" !in locks) latest.put("title", it) }
            result.fields.values["plot"]?.let { if ("synopsis" !in locks) latest.put("synopsis", it) }
            result.fields.values["seriesTitle"]?.takeIf { it.isNotBlank() }?.let { if ("seriesTitle" !in locks) latest.put("seriesTitle", it) }
            listOf("seasonNumber", "episodeNumber").forEach { field -> result.fields.values[field]?.toIntOrNull()?.let { if (field !in locks && it >= if (field == "seasonNumber") 0 else 1) latest.put(field, it) } }
            if (result.status == MatchStatus.MATCHED) result.fields.values["identity"]?.let { latest.put("confirmedIdentity", it) }
            if ("artworkUrl" !in locks && result.artwork.isNotEmpty()) { val uri = result.artwork.first().uri; if (latest.optString("artworkUrl") != uri) latest.remove("artworkCacheKey"); latest.put("artworkUrl", uri) }
            if (result.retryAtMillis != null) latest.put("metadataRetryAt", result.retryAtMillis) else latest.remove("metadataRetryAt")
            latest.put("status", result.status.name.lowercase()).put("metadataDiagnostics", JSONArray(result.diagnostics)).put("candidates", result.candidates.map { candidate ->
                val number = candidate.number
                JSONObject().put("id", UUID.nameUUIDFromBytes("${candidate.identity}:$number".toByteArray()).toString()).put("identity", candidate.identity).put("title", candidate.title ?: candidate.seriesTitle ?: latest.optString("title")).put("seriesTitle", candidate.seriesTitle ?: latest.optString("seriesTitle")).put("seasonNumber", number?.season ?: latest.optInt("seasonNumber", 1)).put("episodeNumber", number?.episode ?: latest.optInt("episodeNumber", 1)).put("evidence", JSONArray(listOf(candidate.evidence.name + if (candidate.trueHashMatch) " (true hash match)" else "")))
            }.jsonArray())
            store.put("library", latest)
        }
        if (scanJobId != null && !current(scanJobId, scanGeneration.orEmpty())) return saved
        val artwork = ArtworkRepository(store)
        val locks = saved.optJSONArray("lockedFields")?.strings().orEmpty()
        if ("artworkUrl" !in locks && "artworkCacheKey" !in locks) {
            val candidates = nfo?.artwork.orEmpty().filter { !it.contains("://") && !it.split('/').contains("..") }.map { if (it.startsWith('/')) it else "$directory/$it" } + listOf(file.path.substringBeforeLast('.') + ".png", file.path.substringBeforeLast('.') + ".jpg", "$directory/poster.jpg", "$directory/poster.png", "$directory/folder.jpg", "$directory/folder.png")
            for (path in candidates.distinct()) try {
                val info = adapter.stat(path)
                if (info.size !in 1..4_194_304) continue
                val bytes = ByteArray(info.size.toInt()); var offset = 0
                while (offset < bytes.size) { val count = minOf(1_048_576, bytes.size - offset); val part = adapter.openRange(path, offset.toLong(), count); require(part.size == count); part.copyInto(bytes, offset); offset += count }
                val cached = store.transaction {
                    val latest = store.get("library", saved.getString("id")) ?: return@transaction null
                    if (scanJobId != null && !current(scanJobId, scanGeneration.orEmpty())) return@transaction null
                    val latestLocks = latest.optJSONArray("lockedFields")?.strings().orEmpty()
                    if ("artworkUrl" in latestLocks || "artworkCacheKey" in latestLocks) null else artwork.cacheBytes(latest.getString("id"), bytes, "local")
                }
                if (cached != null) return cached
            } catch (_: SourceException) { } catch (_: IllegalArgumentException) { }
        }
        if (scanJobId != null && !current(scanJobId, scanGeneration.orEmpty())) return saved
        val stillCurrent = { scanJobId == null || current(scanJobId, scanGeneration.orEmpty()) }
        val online = artwork.cacheEpisode(saved, stillCurrent)
        return if (artwork.fileFor(online) == null) cacheFirstFrame(online, stillCurrent) else online
    }
    fun confirmMatch(episodeId: String, candidateId: String): JSONObject {
        val episode = store.get("library", episodeId) ?: throw ApiFailure(404, "剧集不存在")
        val candidates = episode.optJSONArray("candidates") ?: throw ApiFailure(409, "没有待确认候选")
        val candidate = (0 until candidates.length()).map { candidates.getJSONObject(it) }.firstOrNull { it.getString("id") == candidateId } ?: throw ApiFailure(404, "候选不存在")
        episode.put("confirmedIdentity", candidate.getString("identity")).put("seriesTitle", candidate.optString("seriesTitle").takeIf { it.isNotBlank() } ?: episode.getString("seriesTitle")).put("seasonNumber", candidate.getInt("seasonNumber")).put("episodeNumber", candidate.getInt("episodeNumber"))
        episode.put("lockedFields", JSONArray((episode.optJSONArray("lockedFields")?.strings().orEmpty() + listOf("seasonNumber", "episodeNumber")).distinct()))
        store.put("library", episode)
        val (asset, _) = selectedAsset(episode)
        val source = adapter(source(asset.getString("sourceId")))
        return enrich(episode, source, source.stat(asset.getString("path")))
    }
    private fun selectedAsset(episode: JSONObject): Pair<JSONObject, JSONObject?> {
        val mappings = store.list("episode-assets").filter { it.optString("episodeId") == episode.getString("id") }
        val mapping = mappings.firstOrNull { episode.optString("selectedAssetId").isBlank() || it.getString("assetId") == episode.optString("selectedAssetId") }
        val assetId = mapping?.getString("assetId") ?: episode.getString("assetId")
        return (store.get("assets", assetId) ?: throw ApiFailure(404, "播放资源不存在")) to mapping
    }
    /** Uses authenticated source range reads; raw playback headers are never copied to artwork HTTP. */
    fun cacheFirstFrame(episode: JSONObject, stillCurrent: () -> Boolean = { true }): JSONObject {
        val latest = store.get("library", episode.getString("id")) ?: return episode
        return try {
            fun coverAsset(row: JSONObject): Pair<JSONObject, JSONObject?>? {
                val mappings = store.list("episode-assets").filter { it.getString("episodeId") == row.getString("id") }
                val ids = mappings.map { it.getString("assetId") }.ifEmpty { listOfNotNull(row.optString("assetId").takeIf { it.isNotBlank() }) }
                val usable = ids.firstOrNull { store.get("assets", it)?.optBoolean("available", true) == true } ?: return null
                return selectedAsset(JSONObject(row.toString()).put("selectedAssetId", usable))
            }
            val (asset, mapping) = coverAsset(latest) ?: return latest
            val sourceAdapter = adapter(source(asset.getString("sourceId")))
            val file = SourceFile(asset.getString("path"), size = asset.getLong("size"))
            val position = mapping?.optLong("startMs") ?: 0
            val assetId = asset.getString("id")
            val selectionCurrent = {
                stillCurrent() && store.get("library", latest.getString("id"))?.let { current ->
                    val selected = coverAsset(current)
                    selected != null && selected.first.getString("id") == assetId && (selected.second?.optLong("startMs") ?: 0) == position
                } == true
            }
            FrameArtworkExtractor().cacheIfMissing(store, latest, sourceAdapter, file, position, selectionCurrent, frameDecoder)
        } catch (_: Exception) { store.get("library", latest.getString("id")) ?: latest }
    }
    fun refreshPlayback(episode: JSONObject, positionMs: Long, next: Boolean): JSONObject {
        val mappings = store.list("episode-assets").filter { it.getString("episodeId") == episode.getString("id") }
        val selected = episode.optString("selectedAssetId", episode.optString("assetId"))
        val assetIds = mappings.map { it.getString("assetId") }.ifEmpty { listOf(episode.getString("assetId")) }
        if (next && assetIds.size < 2) throw ApiFailure(409, "此集暂时没有其他资源版本")
        val index = assetIds.indexOf(selected).coerceAtLeast(0)
        val candidate = JSONObject(episode.toString()).put("selectedAssetId", if (next) assetIds[(index + 1) % assetIds.size] else selected).put("resumePositionMs", positionMs).put("playbackAttempt", UUID.randomUUID().toString())
        return resolve(candidate)
    }
    fun openSmb(episode: JSONObject): RandomAccessReader {
        val (asset, _) = selectedAsset(episode)
        return (adapter(source(asset.getString("sourceId"))) as? SmbAdapter ?: throw ApiFailure(400, "此资源不是 SMB")).openRead(asset.getString("path"))
    }
    fun resolve(episode: JSONObject): JSONObject {
        val (asset, mapping) = selectedAsset(episode)
        val playback = adapter(source(asset.getString("sourceId"))).resolvePlayback(asset.getString("path"))
        return JSONObject(episode.toString()).put("clipStartMs", mapping?.optLong("startMs") ?: 0).also { if (mapping?.has("endMs") == true) it.put("clipEndMs", mapping.getLong("endMs")) }.put("familyTimezone", store.getSetting("timezone", "Asia/Shanghai")).put("playbackUrl", playback.uri.toString()).put("playbackHeaders", JSONObject(playback.headers))
    }
}
