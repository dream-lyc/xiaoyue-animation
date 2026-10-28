package cn.childtv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DailyViewingEntriesTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var store: LocalStore
    private lateinit var api: AdminApi
    private lateinit var name: String
    private val firstSession = "11111111-1111-4111-8111-111111111111"
    private val secondSession = "22222222-2222-4222-8222-222222222222"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); name = "daily-entries-${UUID.randomUUID()}.db"; store = LocalStore(context, name)
        val vault = CredentialVault(context, "$name.credentials"); api = AdminApi(store, vault, SourceRepository(store, vault))
        listOf("ep", "other").forEach { id -> store.put("library", JSONObject().put("id", id).put("title", "剧集$id").put("seriesTitle", "动画").put("libraryName", "片库").put("seasonNumber", 1).put("episodeNumber", if (id == "ep") 1 else 2).put("status", "matched").put("lockedFields", JSONArray()).put("tags", JSONObject())) }
    }
    @After fun cleanup() { store.database.close(); context.deleteDatabase(name); context.deleteSharedPreferences("$name.credentials") }
    private fun event(id: String, at: String, millis: Long, kind: String = "normal", episode: String = "ep", day: String = "2026-10-27", library: String = "historical-library", utcDate: String = day) {
        store.put("watch", JSONObject().put("id", id).put("episodeId", episode).put("libraryId", library).put("libraryName", "观看时片库").put("seriesId", "historical-series").put("seriesTitle", "观看时动画").put("title", "观看时集名").put("startedAt", "${utcDate}T${at}Z").put("date", day).put("durationMillis", millis).put("seconds", millis / 1000).put("kind", kind))
    }
    private fun day(date: String = "2026-10-27") = api.route("GET", "analytics/day", JSONObject(), mapOf("date" to date)) as JSONObject
    @Test fun `one session combines checkpoints pause gaps and mixed kinds without changing raw totals`() {
        event("$firstSession:0:normal", "10:00:00.000", 600)
        event("$firstSession:1:normal", "10:00:01.000", 400)
        event("$firstSession:2:normal", "10:05:00.000", 1500)
        event("$firstSession:3:grace", "10:05:02.000", 1600, "grace")
        event("$firstSession:4", "10:05:04.000", 900, "parent_extra")
        val raw = store.list("watch").map { it.toString() }; val policy = api.route("GET", "policy", JSONObject(), emptyMap()).toString()
        val result = day(); val entries = result.getJSONArray("entries"); assertEquals(1, entries.length())
        val row = entries.getJSONObject(0)
        assertEquals(firstSession, row.getString("sessionId")); assertEquals("ep", row.getString("episodeId"))
        assertEquals(5000L, row.getLong("durationMillis")); assertEquals(5L, row.getLong("seconds")); assertEquals("mixed", row.getString("kind"))
        assertEquals(listOf("normal", "grace", "parent_extra"), row.getJSONArray("kinds").strings())
        assertEquals(2500L, row.getLong("normalMillis")); assertEquals(1600L, row.getLong("graceMillis")); assertEquals(900L, row.getLong("parentExtraMillis"))
        assertEquals("2026-10-27T10:00:00Z", row.getString("startedAt")); assertEquals("2026-10-27T10:05:04.900Z", row.getString("endedAt"))
        assertEquals("观看时动画", row.getString("seriesTitle")); assertEquals(5L, result.getLong("totalSeconds")); assertEquals(1L, result.getLong("graceSeconds"))
        assertEquals(raw, store.list("watch").map { it.toString() }); assertEquals(policy, api.route("GET", "policy", JSONObject(), emptyMap()).toString())
    }
    @Test fun `replaying same episode with a new session remains a separate entry`() {
        event("$firstSession:0:normal", "10:00:00.000", 1000); event("$secondSession:0:normal", "10:00:01.000", 1000)
        val entries = day().getJSONArray("entries"); assertEquals(2, entries.length())
        assertNotEquals(entries.getJSONObject(0).getString("id"), entries.getJSONObject(1).getString("id")); assertEquals(secondSession, entries.getJSONObject(1).getString("sessionId"))
    }
    @Test fun `milliseconds sum before rounding and grouping id stays stable as checkpoints arrive`() {
        event("$firstSession:0:normal", "10:00:00.000", 150)
        val initialId = day().getJSONArray("entries").getJSONObject(0).getString("id")
        event("$firstSession:1:normal", "10:00:01.000", 350); event("$firstSession:2:normal", "10:00:02.000", 650)
        val result = day(); val entries = result.getJSONArray("entries"); assertEquals(1, entries.length())
        assertEquals(initialId, entries.getJSONObject(0).getString("id")); assertEquals(1150L, entries.getJSONObject(0).getLong("durationMillis")); assertEquals(1L, entries.getJSONObject(0).getLong("seconds")); assertEquals(1L, result.getLong("totalSeconds"))
    }
    @Test fun `same session is split by day episode and historical library attribution`() {
        event("$firstSession:0:normal", "15:59:59.000", 1000)
        event("$firstSession:1:normal", "15:59:59.000", 500, episode = "other")
        event("$firstSession:2:normal", "15:59:59.500", 500, library = "another-library")
        event("$firstSession:3:normal", "16:00:00.000", 1000, day = "2026-10-28", utcDate = "2026-10-27")
        assertEquals(3, day().getJSONArray("entries").length()); assertEquals(1, day("2026-10-28").getJSONArray("entries").length())
        assertEquals(1000L, day("2026-10-28").getJSONArray("entries").getJSONObject(0).getLong("durationMillis"))
    }
    @Test fun `opaque legacy events stay separate except proven contiguous checkpoint halves`() {
        event("legacy-a", "10:00:00.000", 500); event("legacy-b", "10:00:00.500", 500)
        event("checkpoint:normal", "10:01:00.000", 500); event("checkpoint:grace", "10:01:00.500", 600, "grace")
        val entries = day().getJSONArray("entries"); assertEquals(3, entries.length())
        val combined = entries.getJSONObject(2); assertEquals(1100L, combined.getLong("durationMillis")); assertEquals("mixed", combined.getString("kind")); assertFalse(combined.has("sessionId"))
    }
    @Test fun `legacy suffixes alone cannot join separate noncontiguous watches`() {
        event("old:normal", "10:00:00.000", 500); event("old:grace", "10:05:00.000", 500, "grace")
        val entries = day().getJSONArray("entries"); assertEquals(2, entries.length()); assertEquals(500L, entries.getJSONObject(0).getLong("durationMillis"))
    }
}
