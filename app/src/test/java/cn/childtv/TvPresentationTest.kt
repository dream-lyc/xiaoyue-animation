package cn.childtv

import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvPresentationTest {
    private fun episode(
        id: String,
        title: String,
        series: String,
        season: Int,
        number: Int,
        library: String = "动画",
    ) =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("seriesTitle", series)
            .put("libraryName", library)
            .put("seasonNumber", season)
            .put("episodeNumber", number)

    @Test
    fun `series cards keep separate libraries and sort seasons and episodes`() {
        val rows =
            listOf(
                episode("b", "第二集", "花园", 2, 2),
                episode("a", "第一集", "花园", 1, 1),
                episode("c", "英文版", "花园", 1, 1, "英语"),
            )
        val series = TvPresentation.series(rows)
        assertEquals(2, series.size)
        val garden = series.first { it.library == "动画" }
        assertEquals(listOf("a", "b"), garden.episodes.map { it.getString("id") })
        assertEquals(listOf(1, 2), garden.seasons)
    }

    @Test
    fun `calendar uses monday first and actual empty day remains empty`() {
        val cells = TvPresentation.calendar(YearMonth.of(2026, 2))
        assertNull(cells[0])
        assertEquals(1, cells[6])
        assertEquals(28, cells.filterNotNull().size)
    }

    @Test
    fun `duration shows actual seconds and quota never displays negative remainder`() {
        assertEquals("1 分 30 秒", TvPresentation.duration(90))
        assertEquals("今日额度已用完", TvPresentation.quota(45, 3000))
        assertEquals("今日还可看 40 分钟", TvPresentation.quota(60, 1200))
    }

    @Test
    fun `tag filter supports multiple values and does not duplicate logical episodes`() {
        val a =
            episode("a", "A", "花园", 1, 1)
                .put("tags", JSONObject("{\"路线\":[\"一\",\"二\"],\"语言\":[\"英语\"]}"))
        val b =
            episode("b", "B", "花园", 1, 2)
                .put("tags", JSONObject("{\"路线\":[\"二\"],\"语言\":[\"国语\"]}"))
        assertEquals(
            listOf("a"),
            TvPresentation.filter(listOf(a, b), mapOf("路线" to setOf("一", "二"), "语言" to setOf("英语")))
                .map { it.getString("id") },
        )
    }

    @Test
    fun `same display names keep separate stable series identities`() {
        val first =
            episode("a", "A", "同名动画", 1, 1)
                .put("libraryId", "library-1")
                .put("seriesId", "series-1")
        val second =
            episode("b", "B", "同名动画", 1, 1)
                .put("libraryId", "library-1")
                .put("seriesId", "series-2")
        val groups = TvPresentation.series(listOf(first, second))
        assertEquals(2, groups.size)
        val stableKey = groups.first { it.episodes.first().getString("id") == "a" }.key
        first.put("seriesTitle", "家长修正片名").put("libraryName", "新片库名")
        assertEquals(stableKey, TvPresentation.series(listOf(first)).first().key)
    }

    @Test
    fun `calendar sessions preserve positive sub second duration`() {
        assertEquals(
            "不到1秒",
            TvPresentation.calendarDuration(
                JSONObject().put("seconds", 0).put("durationMillis", 250)
            ),
        )
        assertEquals(
            "0 分钟",
            TvPresentation.calendarDuration(JSONObject().put("seconds", 0).put("durationMillis", 0)),
        )
        assertEquals(
            "2 秒",
            TvPresentation.calendarDuration(
                JSONObject().put("seconds", 2).put("durationMillis", 2500)
            ),
        )
        assertEquals("1 分 30 秒", TvPresentation.calendarDuration(JSONObject().put("seconds", 90)))
    }

    @Test
    fun `mixed calendar session lists all actual viewing categories`() {
        assertEquals(
            "正常观看 · 本集收尾 · 家长额外一集",
            TvPresentation.calendarKind(
                JSONObject()
                    .put("kind", "mixed")
                    .put("kinds", JSONArray(listOf("normal", "grace", "parent_extra")))
            ),
        )
        assertEquals(
            "本集收尾 · 家长额外一集",
            TvPresentation.calendarKind(
                JSONObject()
                    .put("kind", "mixed")
                    .put("kinds", JSONArray(listOf("grace", "parent_extra")))
            ),
        )
        assertEquals("混合观看", TvPresentation.calendarKind(JSONObject().put("kind", "mixed")))
        assertEquals(
            "家长额外一集",
            TvPresentation.calendarKind(JSONObject().put("kind", "parent_extra")),
        )
    }
}
