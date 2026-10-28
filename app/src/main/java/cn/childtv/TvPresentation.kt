package cn.childtv

import java.time.YearMonth
import org.json.JSONObject

/** A display group; logical episode IDs remain authoritative for playback and history. */
data class TvSeries(
    val key: String,
    val title: String,
    val library: String,
    val episodes: List<JSONObject>,
    val seasons: List<Int>,
)

/** Pure presentation rules shared by the TV grids, labels and filters. */
object TvPresentation {
    fun series(rows: List<JSONObject>): List<TvSeries> =
        rows
            .groupBy { row ->
                row.optString("libraryId").ifBlank { "name:${row.optString("libraryName")}" } to
                    row.optString("seriesId").ifBlank {
                        "name:${row.optString("seriesTitle", "未命名动画")}"
                    }
            }
            .map { (group, episodes) ->
                val ordered =
                    episodes
                        .distinctBy { it.getString("id") }
                        .sortedWith(
                            compareBy(
                                { it.optInt("seasonNumber", 1) },
                                { it.optInt("episodeNumber", 1) },
                                { it.getString("id") },
                            )
                        )
                TvSeries(
                    "${group.first.length}:${group.first}${group.second}",
                    ordered.first().optString("seriesTitle", "未命名动画"),
                    ordered.first().optString("libraryName"),
                    ordered,
                    ordered.map { it.optInt("seasonNumber", 1) }.distinct(),
                )
            }
            .sortedWith(compareBy({ it.library }, { it.title }))

    fun calendarDuration(entry: JSONObject): String {
        val millis =
            if (entry.has("durationMillis")) entry.optLong("durationMillis")
            else entry.optLong("seconds") * 1000
        return if (millis in 1..999) "不到1秒" else duration(millis / 1000)
    }

    fun calendarKind(entry: JSONObject): String {
        val kinds =
            (entry.optJSONArray("kinds")?.strings().orEmpty().ifEmpty {
                    listOf(entry.optString("kind"))
                })
                .map { if (it == "parent-extra") "parent_extra" else it }
                .toSet()
        val labels =
            listOf("normal" to "正常观看", "grace" to "本集收尾", "parent_extra" to "家长额外一集")
                .filter { it.first in kinds }
                .map { it.second }
        return if (labels.isNotEmpty()) labels.joinToString(" · ")
        else if (entry.optString("kind") == "mixed") "混合观看" else "观看记录"
    }

    fun calendar(month: YearMonth): List<Int?> =
        List(month.atDay(1).dayOfWeek.value - 1) { null } + (1..month.lengthOfMonth()).toList()

    fun duration(seconds: Long): String {
        val value = seconds.coerceAtLeast(0)
        if (value == 0L) return "0 分钟"
        return listOf(value / 3600 to "小时", value % 3600 / 60 to "分", value % 60 to "秒")
            .filter { it.first > 0 }
            .joinToString(" ") { "${it.first} ${it.second}" }
    }

    fun quota(minutes: Int, watchedSeconds: Long): String {
        val remaining = (minutes * 60L - watchedSeconds).coerceAtLeast(0)
        return if (remaining == 0L) "今日额度已用完" else "今日还可看 ${(remaining + 59) / 60} 分钟"
    }

    fun filter(rows: List<JSONObject>, selection: Map<String, Set<String>>): List<JSONObject> =
        rows
            .filter { row ->
                selection.all { (key, values) ->
                    values.isEmpty() ||
                        values.any { value ->
                            row.optJSONObject("tags")
                                ?.optJSONArray(key)
                                ?.strings()
                                ?.contains(value) == true
                        }
                }
            }
            .distinctBy { it.getString("id") }
}
