package cn.childtv

import cn.childtv.core.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class PolicyRepository(private val store: LocalStore) {
    private fun engine(): ViewingPolicyEngine {
        val states = store.list("daily-state").associate { row ->
            val finishing = row.optJSONObject("finishing")?.let { PlayingEpisode(it.getString("episodeId"), it.getLong("positionMs"), it.getLong("endMs")) }
            val extraProgress = row.optJSONObject("extraProgress")?.let { PlayingEpisode(it.getString("episodeId"), it.getLong("positionMs"), it.getLong("endMs")) }
            LocalDate.parse(row.getString("id")) to DailyState(row.getLong("totalSeconds"), row.optLong("watchedSeconds"), if (row.has("overrideSeconds")) row.getLong("overrideSeconds") else null, row.optBoolean("promptHandled"), PolicyMode.valueOf(row.optString("mode", "AVAILABLE")), finishing, row.optString("extraEpisodeId").takeIf { it.isNotBlank() }, extraProgress)
        }
        return ViewingPolicyEngine(store.getSetting("defaultMinutes", "45").toLong() * 60, states)
    }
    private fun save(engine: ViewingPolicyEngine) {
        engine.snapshot().forEach { (day, state) ->
            store.put("daily-state", JSONObject().put("id", day.toString()).put("totalSeconds", state.totalSeconds).put("watchedSeconds", state.watchedSeconds).put("promptHandled", state.promptHandled).put("mode", state.mode.name).also { row ->
                state.extraProgress?.let { row.put("extraProgress", JSONObject().put("episodeId", it.episodeId).put("positionMs", it.positionMs).put("endMs", it.endMs)) }; state.overrideSeconds?.let { row.put("overrideSeconds", it) }; state.extraEpisodeId?.let { row.put("extraEpisodeId", it) }; state.finishing?.let { row.put("finishing", JSONObject().put("episodeId", it.episodeId).put("positionMs", it.positionMs).put("endMs", it.endMs)) }
            })
        }
    }
    fun today(): LocalDate = LocalDate.now(ZoneId.of(store.getSetting("timezone", "Asia/Shanghai")))
    fun state() = store.transaction { engine().state(today()) }
    fun canStart(id: String, positionMs: Long) = store.transaction { engine().canStart(today(), id, positionMs) }
    fun promptHandled() = store.transaction { val engine = engine(); engine.markPromptHandled(today()); save(engine) }
    fun needsPrompt() = store.transaction { engine().needsPrompt(today()) }
    fun changeToday(seconds: Long?, current: PlayingEpisode?) = store.transaction { val engine = engine(); engine.setTodayTotal(today(), seconds, current); save(engine) }
    fun allowExtra(id: String, positionMs: Long) = store.transaction { val engine = engine(); engine.allowExtra(today(), PlayingEpisode(id, positionMs, Long.MAX_VALUE)); save(engine) }
    fun finish(id: String) = store.transaction { val engine = engine(); engine.finish(today(), id); save(engine) }
    fun checkpoint(event: JSONObject, current: PlayingEpisode?) = store.transaction {
        val eventId = event.getString("id")
        if (store.get("checkpoints", eventId) == null) {
            val day = event.getString("date")
            val duration = event.optLong("durationMillis", event.getLong("seconds") * 1000)
            val remainder = store.getSetting("remainder:$day", "0").toLong()
            val engine = engine()
            val prior = engine.state(LocalDate.parse(day))
            val plan = WatchAccounting.split(prior.watchedSeconds, remainder, prior.totalSeconds, duration)
            val normal = plan.normalMillis
            fun append(suffix: String, offset: Long, millis: Long, kind: String) {
                if (millis <= 0) return
                store.put("watch", JSONObject(event.toString()).put("id", eventId + suffix).put("startedAt", java.time.Instant.parse(event.getString("startedAt")).plusMillis(offset).toString()).put("durationMillis", millis).put("seconds", millis / 1000).put("kind", kind))
            }
            if (prior.extraEpisodeId == event.getString("episodeId")) append("", 0, duration, "parent_extra")
            else { append(":normal", 0, normal, "normal"); append(":grace", normal, duration - normal, "grace") }
            engine.record(LocalDate.parse(day), plan.chargedSeconds, current)
            store.setSetting("remainder:$day", plan.remainderMillis.toString())
            current?.let { if (engine.state(today()).finishing != null || engine.state(today()).extraEpisodeId != null) engine.advance(today(), it.episodeId, it.positionMs) }
            store.put("checkpoints", JSONObject().put("id", eventId)); save(engine)
        }
    }
}
