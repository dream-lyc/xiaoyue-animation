package cn.childtv

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ApiFailure(val status: Int, override val message: String) : RuntimeException(message)
class AdminApi(val store: LocalStore, private val vault: CredentialVault, private val sources: SourceRepository, private val beforeRestore: () -> Unit = {}) {
    fun route(method: String, path: String, body: JSONObject, query: Map<String, String>): Any {
        val parts = path.trim('/').split('/')
        val group = parts.firstOrNull().orEmpty()
        val id = parts.getOrNull(1)
        val action = parts.getOrNull(2)
        return when (group) {
            "sources" -> when {
                method == "GET" && id == null -> store.list("sources").map(sources::describe).jsonArray()
                method == "POST" && id == null -> {
                    val type = body.getString("type"); require(type in listOf("openlist", "alist", "webdav", "smb"))
                    val endpoint = java.net.URI(body.getString("endpoint")); require(endpoint.host != null && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null && endpoint.scheme in if (type == "smb") setOf("smb") else setOf("http", "https")) { "请分别填写地址与凭据，地址不得包含用户名密码" }
                    require(body.getString("name").isNotBlank())
                    require((body.optJSONArray("roots")?.strings() ?: emptyList()).all { !it.replace('\\', '/').split('/').contains("..") })
                    val source = JSONObject().put("id", UUID.randomUUID().toString()).put("name", body.getString("name")).put("type", type).put("endpoint", body.getString("endpoint")).put("roots", body.optJSONArray("roots") ?: JSONArray()).put("credentialConfigured", body.optString("secret").isNotEmpty() || body.optString("username").isNotEmpty())
                    configureSourceCredentials(source, body, true)
                    store.put("sources", source).also { if (it.getJSONArray("roots").length() > 0) sources.enqueue(it.getString("id")) }.let(sources::describe)
                }
                method == "PATCH" && id != null -> {
                    val source = existing("sources", id)
                    if (body.has("roots")) { require(body.getJSONArray("roots").strings().all { !it.replace('\\', '/').split('/').contains("..") }); source.put("roots", body.getJSONArray("roots")) }
                    configureSourceCredentials(source, body, false)
                    store.put("sources", source).also { if (body.has("roots") && it.getJSONArray("roots").length() > 0) sources.enqueue(id) }.let(sources::describe)
                }
                action == "directories" && method == "GET" -> sources.directories(requireNotNull(id), query["path"] ?: "/")
                action == "scan" && method == "POST" -> sources.enqueue(requireNotNull(id))
                action == "test" && method == "POST" -> sources.test(requireNotNull(id))
                else -> throw ApiFailure(404, "未找到接口")
            }
            "library" -> when {
                id == "tags" -> tagsBatch(method, action, body)
                id == "mapping" -> mapping(method, action, body)
                action == "assets" && id != null -> assets(method, id, body)
                action == "candidates" && id != null -> existing("library", id).optJSONArray("candidates") ?: JSONArray()
                action == "match" && id != null -> { require(method == "POST"); sources.confirmMatch(id, body.getString("candidateId")) }
                method == "GET" -> store.list("library").map { row -> if (row.optBoolean("available")) row.put("artworkLocalUrl", "/api/v1/artwork/${row.getString("id")}") else row }.jsonArray()
                method == "POST" || method == "PATCH" -> {
                    val row = if (id == null) JSONObject().put("id", UUID.randomUUID().toString()).put("status", "unmatched").put("available", false).put("tags", JSONObject()) else existing("library", id)
                    listOf("title", "seriesTitle", "libraryName").forEach { require(body.optString(it).isNotBlank()) { "名称不能为空" } }
                    if (body.has("artworkUrl") && body.optString("artworkUrl") != row.optString("artworkUrl")) row.remove("artworkCacheKey")
                    listOf("title", "seriesTitle", "libraryName", "seasonNumber", "episodeNumber", "synopsis", "artworkUrl", "lockedFields").forEach { if (body.has(it)) row.put(it, body.get(it)) }
                    require(row.optInt("seasonNumber", 1) >= 0 && row.optInt("episodeNumber", 1) > 0)
                    ArtworkRepository(store).cacheEpisode(store.put("library", row))
                }
                else -> throw ApiFailure(404, "未找到接口")
            }
            "tags" -> if (method == "GET") store.list("tags").jsonArray() else store.transaction {
                val old = id?.let { existing("tags", it) }
                val oldKey = old?.getString("key")
                if (method == "DELETE") {
                    requireNotNull(id); store.list("library").forEach { row -> val tags = row.optJSONObject("tags") ?: JSONObject(); tags.remove(oldKey); store.put("library", row.put("tags", tags)) }; store.delete("tags", id); JSONObject()
                } else {
                    require(method in listOf("POST", "PATCH")); val key = body.getString("key"); val values = body.getJSONArray("values").strings().toSet(); require(key.isNotBlank() && values.all { it.isNotBlank() }); require(store.list("tags").none { it.getString("id") != id && it.getString("key") == key })
                    val affectedRows = if (oldKey != null) store.list("library") else emptyList()
                    if (oldKey != null && oldKey != key) store.put("tags", JSONObject().put("id", id).put("key", key).put("values", old!!.getJSONArray("values")))
                    if (oldKey != null) affectedRows.forEach { row ->
                        val tags = row.optJSONObject("tags") ?: JSONObject(); val kept = tags.optJSONArray(oldKey)?.strings().orEmpty().toSet().intersect(values); tags.remove(oldKey); if (kept.isNotEmpty()) tags.put(key, JSONArray(kept.toList())); store.put("library", row.put("tags", tags))
                    }
                    store.put("tags", JSONObject().put("key", key).put("values", JSONArray(values.toList())).also { if (id != null) it.put("id", id) })
                }
            }
            "scans" -> when { method == "GET" -> store.list("scans").jsonArray(); action == "cancel" -> sources.cancel(requireNotNull(id)); action == "retry" -> sources.retry(requireNotNull(id)); else -> throw ApiFailure(404, "任务不存在") }
            "policy" -> {
                if (method == "PUT") { val minutes = body.getInt("defaultMinutes"); require(minutes in 0..1440); store.setSetting("defaultMinutes", minutes.toString()) }
                policy()
            }
            "provider" -> {
                if (method == "PUT") listOf("tmdbKey", "openSubtitlesKey").forEach { if (body.optString(it).isNotBlank()) vault.put(it, body.getString(it)) }
                JSONObject().put("tmdbConfigured", vault.get("tmdbKey") != null).put("openSubtitlesConfigured", vault.get("openSubtitlesKey") != null).put("status", if (vault.get("tmdbKey") == null && vault.get("openSubtitlesKey") == null) "自动资料服务未配置" else "已配置；扫描时自动读取 NFO、指纹与候选资料")
            }
            "analytics" -> analytics(id, query)
            "backup" -> { require(method == "GET"); BackupRepository(store).export() }
            "restore" -> { require(method == "POST" && body.optBoolean("confirmed")); BackupRepository(store).restore(body.getJSONObject("backup"), beforeRestore) }
            else -> throw ApiFailure(404, "未找到接口")
        }
    }
    private fun configureSourceCredentials(source: JSONObject, body: JSONObject, creating: Boolean) {
        val tokenSource = source.getString("type") in setOf("openlist", "alist")
        val previous = if (creating) null else sources.effectiveAuthMode(source)
        val mode = if (tokenSource) body.optString("authMode").takeIf { it.isNotBlank() } ?: previous ?: if (body.optString("username").isNotBlank()) "password" else "token" else "password"
        require(mode in setOf("password", "token")) { "请选择账号密码或访问令牌登录方式" }
        require(creating || previous == mode || body.has("secret")) { "切换登录方式时需要重新填写对应凭据" }
        if (body.has("secret") || creating) {
            val username = if (tokenSource && mode == "token") "" else body.optString("username")
            if (tokenSource && mode == "password") require(username.isNotBlank() && body.optString("secret").isNotBlank()) { "账号密码登录需要填写用户名和密码；启用双重验证时请改用访问令牌" }
            vault.put("source:${source.getString("id")}", JSONObject().put("username", username).put("secret", body.optString("secret")).toString())
            source.put("credentialConfigured", username.isNotBlank() || body.optString("secret").isNotBlank())
        }
        source.put("authMode", mode)
    }
    private fun assets(method: String, episodeId: String, body: JSONObject): JSONArray = store.transaction {
        val episode = existing("library", episodeId)
        if (method == "PUT") {
            val rows = body.getJSONArray("assets")
            val mappings = (0 until rows.length()).map { index ->
                val item = rows.getJSONObject(index); val assetId = item.getString("assetId"); existing("assets", assetId)
                val start = item.optLong("startMs"); val end = if (item.has("endMs")) item.getLong("endMs") else null
                require(start >= 0 && (end == null || end > start))
                JSONObject().put("id", "$episodeId:$assetId").put("episodeId", episodeId).put("assetId", assetId).put("startMs", start).also { if (end != null) it.put("endMs", end) }
            }
            require(mappings.map { it.getString("assetId") }.distinct().size == mappings.size)
            store.list("episode-assets").filter { it.getString("episodeId") == episodeId }.forEach { store.delete("episode-assets", it.getString("id")) }
            mappings.forEach { store.put("episode-assets", it) }
            if (mappings.isEmpty()) { episode.remove("assetId"); episode.put("available", false) } else { episode.put("assetId", mappings.first().getString("assetId")).put("available", true) }
            store.put("library", episode)
        } else require(method == "GET")
        val selected = store.list("episode-assets").filter { it.getString("episodeId") == episodeId }.associateBy { it.getString("assetId") }
        store.list("assets").map { asset ->
            val assetId = asset.getString("id"); val mapping = selected[assetId]
            JSONObject().put("id", assetId).put("name", asset.getString("path").substringAfterLast('/')).put("sourceId", asset.getString("sourceId")).put("path", asset.getString("path")).put("selected", mapping != null || (selected.isEmpty() && episode.optString("assetId") == assetId)).put("startMs", mapping?.optLong("startMs") ?: 0).also { if (mapping?.has("endMs") == true) it.put("endMs", mapping.getLong("endMs")) }
        }.jsonArray()
    }
    private fun existing(collection: String, id: String) = store.get(collection, id) ?: throw ApiFailure(404, "记录不存在")
    private fun tagsBatch(method: String, action: String?, body: JSONObject): JSONObject {
        require(method == "POST")
        val ids = body.getJSONArray("episodeIds").strings().distinct().toSet()
        val key = body.getString("key"); val values = body.getJSONArray("values").strings().toSet()
        require(key.isNotBlank() && values.all { it.isNotBlank() })
        val operation = cn.childtv.core.TagOperation.valueOf(body.getString("operation").uppercase())
        return store.transaction {
            val rows = ids.map { existing("library", it) }
            if (action == "preview") JSONObject().put("affectedCount", rows.size)
            else {
                require(body.optBoolean("confirmed"))
                val initial = rows.associate { row -> row.getString("id") to (row.optJSONObject("tags")?.let { tags -> tags.keys().asSequence().associateWith { tags.getJSONArray(it).strings().toSet() } } ?: emptyMap()) }
                val catalog = cn.childtv.core.TagCatalog(initial); catalog.apply(ids, key, values, operation)
                rows.forEach { row -> store.put("library", row.put("tags", JSONObject(catalog.snapshot()[row.getString("id")].orEmpty().mapValues { JSONArray(it.value.toList()) }))) }
                JSONObject().put("affectedCount", rows.size)
            }
        }
    }

    private fun mapping(method: String, action: String?, body: JSONObject): JSONObject = store.transaction {
        require(method == "POST")
        val ids = body.getJSONArray("episodeIds").strings().distinct(); val start = body.getInt("startEpisode"); require(start > 0)
        require(body.getInt("seasonNumber") >= 0 && body.getString("seriesTitle").isNotBlank())
        val rows = ids.mapIndexed { index, id -> existing("library", id).put("seriesTitle", body.getString("seriesTitle")).put("seasonNumber", body.getInt("seasonNumber")).put("episodeNumber", start + index) }
        if (action != "preview") {
            require(body.optBoolean("confirmed"))
            rows.forEach { row -> store.put("library", row.put("lockedFields", JSONArray((row.optJSONArray("lockedFields")?.strings().orEmpty() + listOf("seriesTitle", "seasonNumber", "episodeNumber")).distinct()))) }
        }
        JSONObject().put("rows", rows.jsonArray())
    }
    fun policy(): JSONObject {
        val state = PolicyRepository(store).state()
        val default = store.getSetting("defaultMinutes", "45").toInt()
        return JSONObject().put("defaultMinutes", default).put("todayMinutes", state.totalSeconds / 60).put("todayOverride", state.overrideSeconds != null).put("watchedSeconds", state.watchedSeconds).put("state", state.mode.name.lowercase()).put("timezone", store.getSetting("timezone", "Asia/Shanghai"))
    }
    private fun analytics(id: String?, query: Map<String, String>): JSONObject {
        val episodes = store.list("library").associateBy { it.getString("id") }
        val selection = query["tags"]?.let(::JSONObject)?.let { obj -> obj.keys().asSequence().associateWith { obj.getJSONArray(it).strings().toSet() } } ?: emptyMap()
        val tagCatalog = cn.childtv.core.TagCatalog(episodes.mapValues { (_, row) -> row.optJSONObject("tags")?.let { obj -> obj.keys().asSequence().associateWith { obj.getJSONArray(it).strings().toSet() } } ?: emptyMap() })
        val watchRows = store.list("watch")
        val watchById = watchRows.associateBy { it.getString("id") }
        val events = watchRows.mapNotNull { row ->
            val duration = row.optLong("durationMillis", row.optLong("seconds") * 1000)
            if (duration <= 0) null else {
                val start = java.time.Instant.parse(row.getString("startedAt"))
                cn.childtv.core.WatchInterval(row.getString("id"), row.getString("episodeId"), row.optString("libraryId", row.optString("libraryName")), row.optString("seriesId", row.optString("seriesTitle")), start, start.plusMillis(duration), LocalDate.parse(row.getString("date")), store.getSetting("timezone", "Asia/Shanghai"), duration, cn.childtv.core.WatchKind.valueOf(row.optString("kind", "normal").uppercase()))
            }
        }
        val from = query["from"]?.let(LocalDate::parse)
        val to = query["to"]?.let(LocalDate::parse)
        require(from == null || to == null || from <= to)
        val engine = cn.childtv.core.ViewingAnalytics(events)
        if (id == "day") {
            val day = LocalDate.parse(query["date"] ?: throw IllegalArgumentException("缺少日期"))
            val rows = engine.details(day)
            val entries = DailyWatchSessions.group(rows).map { entry ->
                val first = entry.first
                val kinds = entry.kinds.map { it.name.lowercase() }
                JSONObject().put("id", entry.id).put("episodeId", first.episodeId).put("libraryId", first.libraryId).put("seriesId", first.seriesId)
                    .put("title", episodes[first.episodeId]?.optString("title") ?: "已归档剧集")
                    .put("seriesTitle", watchById[first.id]?.optString("seriesTitle") ?: episodes[first.episodeId]?.optString("seriesTitle") ?: "已归档动画")
                    .put("startedAt", first.startUtc.toString()).put("endedAt", entry.intervals.maxOf { it.endUtc }.toString())
                    .put("durationMillis", entry.durationMillis).put("seconds", entry.durationMillis / 1000)
                    .put("kind", if (kinds.size == 1) kinds.single() else "mixed").put("kinds", JSONArray(kinds))
                    .put("normalMillis", entry.millis(cn.childtv.core.WatchKind.NORMAL))
                    .put("graceMillis", entry.millis(cn.childtv.core.WatchKind.GRACE))
                    .put("parentExtraMillis", entry.millis(cn.childtv.core.WatchKind.PARENT_EXTRA))
                    .also { row -> entry.sessionId?.let { row.put("sessionId", it) } }
            }
            return JSONObject().put("date", day.toString()).put("totalSeconds", rows.sumOf { it.durationMillis } / 1000).put("graceSeconds", rows.filter { it.kind == cn.childtv.core.WatchKind.GRACE }.sumOf { it.durationMillis } / 1000).put("entries", entries.jsonArray())
        }
        val summary = engine.summary(from, to, tags = tagCatalog, selection = selection)
        val selected = events.filter { (from == null || it.day >= from) && (to == null || it.day <= to) && tagCatalog.filter(setOf(it.episodeId), selection).isNotEmpty() }
        val tagLabels = linkedMapOf<String, String>()
        val grouped: Map<String, List<cn.childtv.core.WatchInterval>> = when (query["dimension"]) {
            "library" -> selected.groupBy { it.libraryId }
            "tag" -> {
                val groups = linkedMapOf<String, MutableList<cn.childtv.core.WatchInterval>>()
                selected.forEach { event -> episodes[event.episodeId]?.optJSONObject("tags")?.let { tags -> tags.keys().forEach { key -> tags.getJSONArray(key).strings().forEach { value -> val stableTag = JSONArray(listOf(key, value)).toString(); tagLabels[stableTag] = "$key=$value"; groups.getOrPut(stableTag) { mutableListOf() }.add(event) } } } }
                groups
            }
            else -> selected.groupBy { it.seriesId }
        }
        val labels = if (query["dimension"] == "library") watchRows.associate { it.optString("libraryId", it.optString("libraryName")) to it.optString("libraryName") } else watchRows.associate { it.optString("seriesId", it.optString("seriesTitle")) to it.optString("seriesTitle") }
        return JSONObject().put("totalSeconds", summary.durationMillis / 1000).put("episodeCount", summary.episodeCount).put("watchDays", summary.watchingDays).put("rows", grouped.map { (label, rows) -> JSONObject().put("id", "${query["dimension"] ?: "series"}:$label").put("label", if (query["dimension"] == "tag") tagLabels[label] ?: label else labels[label] ?: label).put("seconds", rows.distinctBy { it.id }.sumOf { it.durationMillis } / 1000).put("episodeCount", rows.map { it.episodeId }.distinct().size) }.jsonArray()).put("days", summary.daily.map { (day, millis) -> JSONObject().put("date", day.toString()).put("seconds", millis / 1000).put("graceSeconds", selected.filter { it.day == day && it.kind == cn.childtv.core.WatchKind.GRACE }.sumOf { it.durationMillis } / 1000) }.jsonArray())
    }
}
