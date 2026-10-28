package cn.childtv

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Strongly typed Room tables own catalog identity and viewing counters. */
internal class TypedCatalog(private val database: ChildDatabase) {
    private val dao = database.catalog()
    val collections = setOf("sources", "library", "assets", "episode-assets", "tags", "watch", "progress", "daily-state", "scans")
    fun list(collection: String): List<JSONObject> = when (collection) {
        "sources" -> dao.sources().map { JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("endpoint", it.endpoint).put("credentialConfigured", it.credentialConfigured).put("authMode", it.authMode).put("deleted", it.deleted).put("roots", JSONArray(dao.roots(it.id).map { root -> root.path })) }
        "library" -> dao.episodes().map(::episodeJson)
        "assets" -> dao.assets().map { JSONObject().put("id", it.id).put("sourceId", it.sourceId).put("path", it.path).put("size", it.size).put("stableId", it.stableId).put("available", it.available) }
        "episode-assets" -> dao.mappings().map { JSONObject().put("id", "${it.episodeId}:${it.assetId}").put("episodeId", it.episodeId).put("assetId", it.assetId).put("startMs", it.startMs).also { json -> it.endMs?.let { end -> json.put("endMs", end) } } }
        "tags" -> dao.keys().map { JSONObject().put("id", it.id).put("key", it.name).put("values", JSONArray(dao.values(it.id).map { value -> value.value })) }
        "watch" -> dao.watches().map { JSONObject().put("id", it.id).put("episodeId", it.episodeId).put("libraryId", it.libraryId).put("libraryName", it.libraryName).put("seriesId", it.seriesId).put("seriesTitle", it.seriesTitle).put("title", it.title).put("startedAt", it.startedAt).put("date", it.date).put("durationMillis", it.durationMillis).put("seconds", it.durationMillis / 1000).put("kind", it.kind) }
        "progress" -> dao.progress().map { JSONObject().put("id", it.episodeId).put("positionMs", it.positionMs).put("completed", it.completed) }
        "daily-state" -> dao.daily().map { row -> JSONObject().put("id", row.day).put("totalSeconds", row.totalSeconds).put("watchedSeconds", row.watchedSeconds).put("promptHandled", row.promptHandled).put("mode", row.mode).also { json -> row.overrideSeconds?.let { json.put("overrideSeconds", it) }; row.finishingJson?.let { json.put("finishing", JSONObject(it)) }; row.extraEpisodeId?.let { json.put("extraEpisodeId", it) }; row.extraProgressJson?.let { json.put("extraProgress", JSONObject(it)) } } }
        "scans" -> dao.scans().map { JSONObject().put("id", it.id).put("sourceId", it.sourceId).put("status", it.status).put("stage", it.stage).put("discovered", it.discovered).put("matched", it.matched).put("completed", it.completed).also { json -> it.error?.let { error -> json.put("error", error) }; it.cursorJson?.let { cursor -> json.put("cursor", JSONObject(cursor)) } } }
        else -> error("Unknown typed collection")
    }
    private fun episodeJson(row: EpisodeEntity): JSONObject {
        val season = requireNotNull(dao.season(row.seasonId))
        val series = requireNotNull(dao.series(season.seriesId))
        val library = requireNotNull(dao.library(series.libraryId))
        val keys = dao.keys().associateBy { it.id }
        val tags = JSONObject()
        dao.tags(row.id).groupBy { it.keyId }.forEach { (keyId, rows) -> tags.put(requireNotNull(keys[keyId]).name, JSONArray(rows.map { it.value })) }
        return JSONObject(row.extraJson).put("id", row.id).put("title", row.title).put("synopsis", row.synopsis).put("seriesTitle", series.title).put("seriesId", series.id).put("libraryName", library.name).put("libraryId", library.id).put("seasonNumber", season.number).put("seasonId", season.id).put("episodeNumber", row.number).put("status", row.status).put("available", row.available).put("deleted", row.deleted).put("lockedFields", JSONArray(dao.overrides(row.id).map { it.field })).put("tags", tags).put("candidates", dao.candidates(row.id).map { candidate -> JSONObject().put("id", candidate.candidateId).put("identity", candidate.identity).put("title", candidate.title).put("seriesTitle", candidate.seriesTitle).put("seasonNumber", candidate.seasonNumber).put("episodeNumber", candidate.episodeNumber).put("evidence", JSONArray(candidate.evidenceJson)) }.jsonArray()).also { row.artworkUrl?.let { url -> it.put("artworkUrl", url) }; dao.artwork(row.id).firstOrNull()?.let { art -> art.cacheKey?.let { key -> it.put("artworkCacheKey", key).put("artworkLocalUrl", "/api/v1/artwork/${row.id}") }; it.put("artworkOrigin", art.origin) } }
    }
    fun get(collection: String, id: String): JSONObject? = when (collection) {
        "library" -> dao.episode(id)?.let(::episodeJson)
        "assets" -> dao.asset(id)?.let { JSONObject().put("id", it.id).put("sourceId", it.sourceId).put("path", it.path).put("size", it.size).put("stableId", it.stableId).put("available", it.available) }
        "sources" -> dao.source(id)?.let { JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("endpoint", it.endpoint).put("credentialConfigured", it.credentialConfigured).put("authMode", it.authMode).put("deleted", it.deleted).put("roots", JSONArray(dao.roots(it.id).map { root -> root.path })) }
        "progress" -> dao.progress(id)?.let { JSONObject().put("id", it.episodeId).put("positionMs", it.positionMs).put("completed", it.completed) }
        else -> list(collection).firstOrNull { it.getString("id") == id }
    }
    fun put(collection: String, row: JSONObject) {
        val id = row.getString("id")
        when (collection) {
            "sources" -> {
                dao.put(SourceEntity(id, row.getString("name"), row.getString("type"), row.getString("endpoint"), row.optBoolean("credentialConfigured"), row.optBoolean("deleted"), row.optString("authMode", "legacy")))
                dao.clearRoots(id); row.optJSONArray("roots")?.strings()?.distinct()?.forEach { dao.put(SourceRootEntity(id, it)) }
            }
            "assets" -> {
                require(dao.assets().none { it.sourceId == row.getString("sourceId") && it.path == row.getString("path") && it.id != id }) { "同一源路径已关联其他资源 ID" }
                dao.put(MediaAssetEntity(id, row.getString("sourceId"), row.getString("path"), row.getLong("size"), row.optString("stableId").takeIf { it.isNotBlank() && it != "null" }, row.optBoolean("available", true)))
            }
            "library" -> putEpisode(row)
            "episode-assets" -> dao.put(EpisodeAssetEntity(row.getString("episodeId"), row.getString("assetId"), row.optLong("startMs"), if (row.has("endMs")) row.getLong("endMs") else null))
            "tags" -> {
                val key = TagKeyEntity(id, row.getString("key")); require(dao.keys().none { it.name == key.name && it.id != key.id }) { "标签名称已存在" }; dao.put(key)
                val desired = row.getJSONArray("values").strings().toSet()
                // API updates episode associations first, so FK constraints protect removed values.
                dao.values(id).filter { it.value !in desired }.forEach { old -> database.openHelper.writableDatabase.execSQL("DELETE FROM tag_values WHERE keyId=? AND value=?", arrayOf(id, old.value)) }
                desired.forEach { dao.put(TagValueEntity(id, it)) }
            }
            "watch" -> {
                val episode = episodeJson(requireNotNull(dao.episode(row.getString("episodeId"))))
                val interval = WatchIntervalEntity(id, row.getString("episodeId"), row.optString("libraryId", episode.getString("libraryId")), row.optString("libraryName", episode.getString("libraryName")), row.optString("seriesId", episode.getString("seriesId")), row.optString("seriesTitle", episode.getString("seriesTitle")), row.optString("title", episode.getString("title")), row.getString("startedAt"), row.getString("date"), row.optLong("durationMillis", row.optLong("seconds") * 1000), row.optString("kind", "normal"))
                require(interval.durationMillis > 0)
                val previous = dao.watch(id)
                require(previous == null || previous == interval) { "相同观看事件 ID 的内容冲突" }
                dao.put(interval)
            }
            "progress" -> dao.put(ProgressEntity(id, row.getLong("positionMs"), row.optBoolean("completed")))
            "daily-state" -> dao.put(DailyPolicyEntity(id, row.getLong("totalSeconds"), row.optLong("watchedSeconds"), if (row.has("overrideSeconds")) row.getLong("overrideSeconds") else null, row.optBoolean("promptHandled"), row.optString("mode", "AVAILABLE"), row.optJSONObject("finishing")?.toString(), row.optString("extraEpisodeId").takeIf { it.isNotBlank() }, row.optJSONObject("extraProgress")?.toString()))
            "scans" -> dao.put(ScanJobEntity(id, row.getString("sourceId"), row.optString("status", "running"), row.optString("stage", "discovering"), row.optInt("discovered"), row.optInt("matched"), row.optInt("completed"), row.optString("error").takeIf { it.isNotBlank() }, row.optJSONObject("cursor")?.toString()))
        }
    }
    private fun putEpisode(row: JSONObject) {
        val id = row.getString("id")
        val libraryName = row.getString("libraryName"); val seriesTitle = row.getString("seriesTitle"); val seasonNumber = row.optInt("seasonNumber", 1)
        require(libraryName.isNotBlank() && seriesTitle.isNotBlank() && seasonNumber >= 0 && row.optInt("episodeNumber", 1) > 0)
        val library = dao.libraries().firstOrNull { it.name == libraryName } ?: LibraryEntity(row.optString("libraryId").takeIf { supplied -> supplied.isNotBlank() && dao.libraries().none { it.id == supplied } } ?: UUID.randomUUID().toString(), libraryName).also { dao.put(it) }
        val identity = row.optString("confirmedIdentity").takeIf { it.isNotBlank() }
        val existingSeries = dao.series().firstOrNull { it.libraryId == library.id && if (identity != null) it.identity == identity else it.identity == null && it.title == seriesTitle }
        val series = existingSeries?.also { if (it.title != seriesTitle && identity != null) dao.put(it.copy(title = seriesTitle)) } ?: SeriesEntity(row.optString("seriesId").takeIf { supplied -> supplied.isNotBlank() && dao.series().none { it.id == supplied } } ?: UUID.randomUUID().toString(), library.id, seriesTitle, identity = identity).also { dao.put(it) }
        val season = dao.seasons().firstOrNull { it.seriesId == series.id && it.number == seasonNumber } ?: SeasonEntity(row.optString("seasonId").takeIf { supplied -> supplied.isNotBlank() && dao.seasons().none { it.id == supplied } } ?: UUID.randomUUID().toString(), series.id, seasonNumber).also { dao.put(it) }
        val extra = JSONObject(row.toString()); listOf("id", "libraryName", "libraryId", "seriesTitle", "seriesId", "seasonNumber", "seasonId", "episodeNumber", "title", "synopsis", "artworkUrl", "status", "available", "deleted", "lockedFields", "tags", "candidates", "artworkCacheKey", "artworkLocalUrl", "artworkOrigin").forEach(extra::remove)
        dao.put(EpisodeEntity(id, season.id, row.optInt("episodeNumber", 1), row.getString("title"), row.optString("synopsis"), row.optString("artworkUrl").takeIf { it.isNotBlank() }, row.optString("status", "unmatched"), row.optBoolean("available"), row.optBoolean("deleted"), (row.optJSONArray("lockedFields") ?: JSONArray()).toString(), extra.toString()))
        dao.clearOverrides(id)
        row.optJSONArray("lockedFields")?.strings()?.distinct()?.forEach { field ->
            dao.put(FieldOverrideEntity(id, field, JSONObject().put("value", row.opt(field)).toString()))
        }
        dao.clearCandidates(id)
        row.optJSONArray("candidates")?.let { candidates -> (0 until candidates.length()).forEach { index -> val candidate = candidates.getJSONObject(index)
            dao.put(MetadataMatchEntity(id, candidate.getString("id"), candidate.optString("identity"), candidate.optString("title"), candidate.optInt("seasonNumber", 1), candidate.optInt("episodeNumber", 1), (candidate.optJSONArray("evidence") ?: JSONArray()).toString(), candidate.optString("seriesTitle")))
        } }
        val oldArtwork = dao.artwork(id).firstOrNull()
        dao.clearArtwork(id)
        val uri = row.optString("artworkUrl").takeIf { it.isNotBlank() }
        val suppliedKey = row.optString("artworkCacheKey").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val cacheKey = suppliedKey ?: oldArtwork?.takeIf { it.uri == uri }?.cacheKey
        if (uri != null || cacheKey != null) dao.put(ArtworkEntity("$id:primary", id, uri ?: "local:$cacheKey", row.optString("artworkOrigin").ifBlank { if (uri?.startsWith("file:") == true || uri?.startsWith("content:") == true || uri == null) "local" else "online" }, cacheKey))
        row.optString("assetId").takeIf { it.isNotBlank() }?.let { assetId ->
            if (dao.mappings().none { it.episodeId == id }) dao.put(EpisodeAssetEntity(id, assetId, 0, null))
        }
        dao.clearEpisodeTags(id)
        row.optJSONObject("tags")?.let { tags -> tags.keys().forEach { name ->
            val key = dao.keys().firstOrNull { it.name == name } ?: TagKeyEntity(UUID.randomUUID().toString(), name).also { dao.put(it) }
            tags.getJSONArray(name).strings().distinct().forEach { value -> require(name.isNotBlank() && value.isNotBlank()); dao.put(TagValueEntity(key.id, value)); dao.put(EpisodeTagEntity(id, key.id, value)) }
        } }
    }
    fun delete(collection: String, id: String) {
        when (collection) {
            "tags" -> { dao.clearValues(id); dao.deleteKey(id) }
            "episode-assets" -> { val mapping = list(collection).firstOrNull { it.getString("id") == id } ?: return; dao.deleteMapping(mapping.getString("episodeId"), mapping.getString("assetId")) }
            "sources" -> dao.sources().firstOrNull { it.id == id }?.let { dao.put(it.copy(deleted = true)) }
            "library" -> dao.episodes().firstOrNull { it.id == id }?.let { dao.put(it.copy(deleted = true)) }
            else -> error("Delete requires explicit archive or whole-catalog restore")
        }
    }
    fun clear() {
        listOf("watch_intervals", "progress", "daily_policies", "scan_jobs", "episode_tags", "episode_assets", "field_overrides", "metadata_matches", "artwork", "episodes", "seasons", "series", "libraries", "tag_values", "tag_keys", "media_assets", "source_roots", "sources").forEach { database.openHelper.writableDatabase.execSQL("DELETE FROM $it") }
    }
}
