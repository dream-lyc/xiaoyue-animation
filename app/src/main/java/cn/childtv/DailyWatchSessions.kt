package cn.childtv

import cn.childtv.core.WatchInterval
import cn.childtv.core.WatchKind
import java.time.LocalDate

/** Display-only grouping. Raw checkpoints remain authoritative for accounting and policy. */
internal object DailyWatchSessions {
    private val sessionPattern = Regex("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}):[0-9]+(?::(?:normal|grace))?$")
    private data class Key(val day: LocalDate, val identity: String, val episodeId: String, val libraryId: String, val seriesId: String) {
        fun stableId(): String = "viewing:" + listOf(day.toString(), identity, episodeId, libraryId, seriesId).joinToString("|") { "${it.length}:$it" }
    }
    data class Entry(val id: String, val sessionId: String?, val intervals: List<WatchInterval>) {
        val first: WatchInterval get() = intervals.first()
        val durationMillis: Long get() = intervals.sumOf { it.durationMillis }
        fun millis(kind: WatchKind): Long = intervals.filter { it.kind == kind }.sumOf { it.durationMillis }
        val kinds: List<WatchKind> get() = listOf(WatchKind.NORMAL, WatchKind.GRACE, WatchKind.PARENT_EXTRA).filter { kind -> intervals.any { it.kind == kind } }
    }
    fun group(intervals: List<WatchInterval>): List<Entry> {
        val byId = intervals.associateBy { it.id }
        fun session(event: WatchInterval): String? = sessionPattern.matchEntire(event.id)?.groupValues?.get(1)?.lowercase()
        fun opaqueCheckpoint(event: WatchInterval): String? {
            val suffix = when (event.kind) { WatchKind.NORMAL -> ":normal"; WatchKind.GRACE -> ":grace"; else -> return null }
            if (!event.id.endsWith(suffix)) return null
            val base = event.id.removeSuffix(suffix)
            if (base.isBlank()) return null
            val normal = byId["$base:normal"] ?: return null
            val grace = byId["$base:grace"] ?: return null
            if (normal.kind != WatchKind.NORMAL || grace.kind != WatchKind.GRACE || normal.endUtc != grace.startUtc) return null
            if (normal.day != grace.day || normal.episodeId != grace.episodeId || normal.libraryId != grace.libraryId || normal.seriesId != grace.seriesId) return null
            return base
        }
        return intervals.groupBy { event ->
            val identity = session(event)?.let { "session:$it" } ?: opaqueCheckpoint(event)?.let { "checkpoint:$it" } ?: "event:${event.id}"
            Key(event.day, identity, event.episodeId, event.libraryId, event.seriesId)
        }.map { (key, values) ->
            val ordered = values.sortedWith(compareBy({ it.startUtc }, { it.id }))
            Entry(key.stableId(), session(ordered.first()), ordered)
        }.sortedWith(compareBy({ it.first.startUtc }, { it.id }))
    }
}
