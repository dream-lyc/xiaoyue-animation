package cn.childtv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.childtv.core.PlayingEpisode
import cn.childtv.core.PolicyMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RepositoryIntegrationTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var store: LocalStore
    private lateinit var api: AdminApi
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("child-tv.db")
        store = LocalStore(context)
        val vault = CredentialVault(context)
        api = AdminApi(store, vault, SourceRepository(store, vault))
        store.put("library", episode("ep"))
    }
    @After fun tearDown() { store.database.close(); context.deleteDatabase("child-tv.db") }
    private fun episode(id: String) = JSONObject().put("id", id).put("title", "家长标题").put("seriesTitle", "动画").put("libraryName", "旧片库").put("seasonNumber", 1).put("episodeNumber", 1).put("synopsis", "人工简介").put("status", "unmatched").put("available", false).put("lockedFields", JSONArray(listOf("title", "synopsis"))).put("tags", JSONObject())
    private fun checkpoint(id: String, millis: Long, day: LocalDate = PolicyRepository(store).today()) = JSONObject().put("id", id).put("episodeId", "ep").put("date", day.toString()).put("startedAt", Instant.now().toString()).put("seconds", millis / 1000).put("durationMillis", millis).put("kind", "normal").put("libraryName", "旧片库").put("seriesTitle", "动画")
    @Test fun `subsecond checkpoints persist across repository restarts and deduplicate retries`() {
        repeat(10) { index -> val event = checkpoint("c$index", 150); PolicyRepository(store).checkpoint(event, null); PolicyRepository(store).checkpoint(event, null) }
        assertEquals(1L, PolicyRepository(store).state().watchedSeconds)
        assertEquals("500", store.getSetting("remainder:${PolicyRepository(store).today()}", "0"))
        assertEquals(1500L, store.list("watch").sumOf { it.getLong("durationMillis") })
        val summary = api.route("GET", "analytics", JSONObject(), emptyMap()) as JSONObject
        assertEquals(1L, summary.getLong("totalSeconds"))
    }
    @Test fun `quota reduction retains active episode progress after database reopen`() {
        val policy = PolicyRepository(store)
        policy.checkpoint(checkpoint("watched", 1_200_000), null)
        policy.changeToday(3600, null)
        assertEquals(2400L, policy.state().totalSeconds - policy.state().watchedSeconds)
        policy.changeToday(600, PlayingEpisode("ep", 120_000, 600_000))
        store.database.close(); store = LocalStore(context)
        val restored = PolicyRepository(store)
        assertEquals(PolicyMode.FINISHING_CURRENT_EPISODE, restored.state().mode)
        assertTrue(restored.canStart("ep", 120_000)); assertFalse(restored.canStart("ep", 119_999)); assertFalse(restored.canStart("other", 120_000))
        restored.finish("ep"); assertFalse(PolicyRepository(store).canStart("ep", 120_000))
    }
    @Test fun `cross midnight checkpoints charge their persisted dates without resetting either day`() {
        val policy = PolicyRepository(store); val today = policy.today(); val yesterday = today.minusDays(1)
        policy.checkpoint(checkpoint("before", 2500, yesterday), null)
        policy.checkpoint(checkpoint("after", 3500, today), null)
        val old = store.get("daily-state", yesterday.toString())!!; val current = store.get("daily-state", today.toString())!!
        assertEquals(2L, old.getLong("watchedSeconds")); assertEquals(3L, current.getLong("watchedSeconds"))
        assertEquals("500", store.getSetting("remainder:$yesterday", "0")); assertEquals("500", store.getSetting("remainder:$today", "0"))
    }
    @Test fun `batch tags roll back when any selected episode is missing`() {
        val body = JSONObject().put("episodeIds", JSONArray(listOf("ep", "missing"))).put("key", "路线").put("values", JSONArray(listOf("一"))).put("operation", "append").put("confirmed", true)
        assertThrows(ApiFailure::class.java) { api.route("POST", "library/tags/apply", body, emptyMap()) }
        assertFalse(store.get("library", "ep")!!.getJSONObject("tags").has("路线"))
    }
    @Test fun `current tags recalculate history while library movement preserves original attribution`() {
        PolicyRepository(store).checkpoint(checkpoint("history", 600_000), null)
        val edit = store.get("library", "ep")!!.put("libraryName", "新片库").put("tags", JSONObject().put("路线", JSONArray(listOf("一", "二"))))
        store.put("library", edit)
        val summary = api.route("GET", "analytics", JSONObject(), mapOf("dimension" to "library")) as JSONObject
        assertEquals("旧片库", summary.getJSONArray("rows").getJSONObject(0).getString("label"))
        assertEquals(1, summary.getInt("watchDays")); assertEquals(1, summary.getInt("episodeCount")); assertEquals(1, summary.getJSONArray("days").length())
        val daily = api.route("GET", "analytics/day", JSONObject(), mapOf("date" to PolicyRepository(store).today().toString())) as JSONObject
        assertEquals("动画", daily.getJSONArray("entries").getJSONObject(0).getString("seriesTitle")); assertEquals(600L, daily.getLong("totalSeconds"))
        val empty = api.route("GET", "analytics/day", JSONObject(), mapOf("date" to PolicyRepository(store).today().plusDays(1).toString())) as JSONObject
        assertEquals(0L, empty.getLong("totalSeconds")); assertEquals(0, empty.getJSONArray("entries").length())
        val union = api.route("GET", "analytics", JSONObject(), mapOf("tags" to "{\"路线\":[\"一\",\"二\"]}", "dimension" to "tag")) as JSONObject
        assertEquals(600L, union.getLong("totalSeconds")); assertEquals(2, union.getJSONArray("rows").length())
        repeat(2) { assertEquals(600L, union.getJSONArray("rows").getJSONObject(it).getLong("seconds")) }
    }
    @Test fun `backup roundtrip preserves milliseconds and duplicate checkpoint protection`() {
        val policy = PolicyRepository(store)
        repeat(3) { policy.checkpoint(checkpoint("tiny$it", 150), null) }
        val backup = BackupRepository(store).export()
        policy.checkpoint(checkpoint("later", 650), null)
        BackupRepository(store).restore(backup)
        val duplicate = checkpoint("tiny0", 150); PolicyRepository(store).checkpoint(duplicate, null)
        assertEquals(450L, store.list("watch").sumOf { it.getLong("durationMillis") })
        assertEquals("450", store.getSetting("remainder:${policy.today()}", "0"))
    }
}
