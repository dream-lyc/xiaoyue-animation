package cn.childtv

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/API data and D-pad actions, constrained to the TV content area's normal height. */
@RunWith(AndroidJUnit4::class)
class StatisticsDeviceTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun remoteDimensionsShowTheirDifferentGroupingRowsWithinTvContentArea() = fixture(tags = true) { data ->
        assertTrue("Animation rows must be visible below the summary", device.wait(Until.hasObject(By.descStartsWith("海底故事，")), 3000))
        assertTrue("The first grouping row must be fully readable", requireNotNull(device.findObject(By.descStartsWith("海底故事，"))).visibleBounds.height() >= 30)
        assertTrue(device.hasObject(By.descStartsWith("森林冒险，")))
        screenshot("statistics-series.png")
        focus("按片库"); device.pressDPadCenter()
        assertTrue("Library dimension must display its own group", device.wait(Until.hasObject(By.descStartsWith("儿童片库，")), 3000))
        assertFalse(device.hasObject(By.descStartsWith("海底故事，")))
        screenshot("statistics-library.png")
        focus("按当前标签"); device.pressDPadCenter()
        assertTrue("Tag dimension must display current tag groups", device.wait(Until.hasObject(By.descStartsWith("主题=海洋，")), 3000))
        assertTrue(device.hasObject(By.descStartsWith("主题=自然，")))
        assertFalse(device.hasObject(By.descStartsWith("儿童片库，")))
        screenshot("statistics-tags.png")
        // Summary and daily trend intentionally do not change when only the grouping changes.
        assertEquals(listOf(180L, 180L, 180L), listOf("series", "library", "tag").map { data.getValue(it).getLong("totalSeconds") })
    }

    @Test fun tagDimensionWithViewingButNoTagsExplainsItsEmptyGrouping() = fixture(tags = false) {
        focus("按当前标签"); device.pressDPadCenter()
        assertTrue("An empty current-tag grouping needs a specific explanation", device.wait(Until.hasObject(By.text("还没有可统计的当前标签")), 3000))
        assertFalse("Existing watch history is not empty", device.hasObject(By.text("还没有观看统计")))
        screenshot("statistics-no-tags.png")
    }

    @Test fun groupingListCanBeScrolledWithDirectionalKeysToItsLastRow() = fixture(tags = true, count = 12) { data ->
        val rows = data.getValue("series").getJSONArray("rows")
        val lastLabel = rows.getJSONObject(rows.length() - 1).getString("label")
        focus("最近 7 天")
        repeat(18) {
            val last = device.findObject(By.descStartsWith("$lastLabel，"))
            if (last != null && last.visibleBounds.height() >= 30) { screenshot("statistics-last-row.png"); return@fixture }
            device.pressDPadDown()
        }
        assertTrue("D-pad must reach the final grouping row in a long history", visibleAfterSettling(By.descStartsWith("$lastLabel，"), 30))
        screenshot("statistics-last-row.png")
    }

    @Test fun dailyTrendCanBeScrolledRightToTheLastDateWithDirectionalKeys() = fixture(tags = true, historyDays = 20) { data ->
        val lastDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString().takeLast(5)
        val days = data.getValue("series").getJSONArray("days")
        val lastDay = days.getJSONObject(days.length() - 1)
        val lastDescription = "${lastDay.getString("date")}，实际观看${TvPresentation.duration(lastDay.getLong("seconds"))}"
        assertTrue(lastDescription.startsWith(lastDay.getString("date")))
        assertTrue(lastDay.getString("date").endsWith(lastDate))
        assertFalse("The end date initially lies beyond the narrow trend viewport", device.hasObject(By.desc(lastDescription)))
        focus("按当前标签")
        device.pressDPadDown()
        repeat(24) {
            val last = device.findObject(By.desc(lastDescription))
            if (last != null && last.visibleBounds.height() >= 20) { screenshot("statistics-last-date.png"); return@fixture }
            device.pressDPadRight()
        }
        assertTrue("D-pad must reach the last date of a long daily trend", visibleAfterSettling(By.desc(lastDescription), 20))
        screenshot("statistics-last-date.png")
    }

    private fun fixture(tags: Boolean, count: Int = 2, historyDays: Int = 1, test: (Map<String, JSONObject>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "statistics-${UUID.randomUUID()}.db"
        val namespace = "$name.credentials"
        val graph = AppGraph(context, name, namespace)
        var scenario: ActivityScenario<PlaybackFixtureActivity>? = null
        try {
            (0 until count).map { index -> when (index) { 0 -> "海底故事" to "海洋"; 1 -> "森林冒险" to "自然"; else -> "故事${index + 1}" to "标签${index + 1}" } }.forEachIndexed { index, (series, tag) ->
                val saved = graph.store.put("library", JSONObject().put("title", "$series 第一集").put("seriesTitle", series).put("libraryName", "儿童片库").put("seasonNumber", 1).put("episodeNumber", 1).put("tags", if (tags) JSONObject().put("主题", JSONArray(listOf(tag))) else JSONObject()))
                val row = requireNotNull(graph.store.get("library", saved.getString("id")))
                repeat(historyDays) { offset ->
                    graph.store.put("watch", JSONObject().put("id", "statistics-event-$index-$offset").put("episodeId", row.getString("id")).put("libraryId", row.getString("libraryId")).put("libraryName", row.getString("libraryName")).put("seriesId", row.getString("seriesId")).put("seriesTitle", series).put("startedAt", Instant.now().minusSeconds(300 + offset * 86400L).toString()).put("date", graph.policy.today().minusDays(offset.toLong()).toString()).put("durationMillis", (index + 1) * 60_000L).put("seconds", (index + 1) * 60).put("kind", "normal"))
                }
            }
            val data = listOf("series", "library", "tag").associateWith { dimension ->
                graph.api.route("GET", "analytics", JSONObject(), mapOf("dimension" to dimension)) as JSONObject
            }
            assertEquals(listOf(count, 1, if (tags) count else 0), listOf("series", "library", "tag").map { data.getValue(it).getJSONArray("rows").length() })
            scenario = ActivityScenario.launch(PlaybackFixtureActivity::class.java)
            scenario.onActivity { host -> host.setContent { MaterialTheme {
                var dimension by remember { mutableStateOf("series") }
                var period by remember { mutableIntStateOf(7) }
                var current by remember { mutableStateOf(data.getValue("series")) }
                val scope = rememberCoroutineScope()
                // MainActivity reserves the remaining height for its logo, navigation and footer.
                Box(Modifier.fillMaxSize().background(TvBackground).padding(horizontal = 28.dp, vertical = 22.dp)) {
                    Box(Modifier.fillMaxWidth().height(369.dp)) {
                        TvStatistics(current, dimension, { selected ->
                            dimension = selected
                            scope.launch { current = withContext(Dispatchers.IO) { graph.api.route("GET", "analytics", JSONObject(), mapOf("dimension" to selected)) as JSONObject } }
                        }, period, { period = it })
                    }
                }
            } } }
            test(data)
        } catch (failure: Throwable) {
            try { screenshot("statistics-failure.png"); device.dumpWindowHierarchy(File(context.cacheDir, "statistics-failure.xml")) } catch (capture: Throwable) { failure.addSuppressed(capture) }
            throw failure
        } finally {
            scenario?.close(); graph.store.database.close(); context.deleteDatabase(name); context.deleteSharedPreferences(namespace); graph.store.artworkDirectory.deleteRecursively()
        }
    }
    /** Key injection can finish before the Lazy list's scroll/semantics animation settles. */
    private fun visibleAfterSettling(selector: androidx.test.uiautomator.BySelector, minimumHeight: Int): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + 3000
        do {
            val node = device.findObject(selector)
            if (node != null && node.visibleBounds.height() >= minimumHeight) return true
            Thread.sleep(50)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        return false
    }
    private fun screenshot(name: String) { device.takeScreenshot(File(ApplicationProvider.getApplicationContext<Context>().cacheDir, name)) }
    private fun focus(label: String) {
        repeat(5) {
            repeat(9) { if (focused(label)) return; device.pressDPadLeft() }
            repeat(9) { if (focused(label)) return; device.pressDPadRight() }
            device.pressDPadDown()
        }
        fail("D-pad could not focus $label")
    }
    private fun focused(label: String): Boolean = try {
        device.findObjects(By.text(label)).any { child ->
            var node: UiObject2? = child
            var found = false
            repeat(5) { node?.let { current -> if (current.isFocused && current.isClickable) found = true; node = current.parent } }
            found
        }
    } catch (_: RuntimeException) { false }
}
