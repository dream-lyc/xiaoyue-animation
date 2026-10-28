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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TypedStorageIntegrationTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var store: LocalStore
    @Before fun prepare() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("child-tv.db"); store = LocalStore(context) }
    @After fun clean() { store.database.close(); context.deleteDatabase("child-tv.db") }
    private fun episode(id: String, number: Int) = JSONObject().put("id", id).put("title", "第${number}集").put("libraryName", "原片库").put("seriesTitle", "动画").put("seasonNumber", 1).put("episodeNumber", number).put("status", "matched").put("tags", JSONObject()).put("lockedFields", JSONArray())
    @Test fun `same display title with distinct confirmed identities remains distinct stable series`() {
        store.put("library", episode("a", 1).put("confirmedIdentity", "tmdb:1"))
        store.put("library", episode("b", 2).put("confirmedIdentity", "tmdb:2"))
        assertNotEquals(store.get("library", "a")!!.getString("seriesId"), store.get("library", "b")!!.getString("seriesId"))
    }
    @Test fun `metadata candidate readable series title survives typed persistence`() {
        val candidate = JSONObject().put("id", "candidate").put("identity", "tmdb:1").put("title", "泥坑").put("seriesTitle", "小猪佩奇").put("seasonNumber", 1).put("episodeNumber", 1).put("evidence", JSONArray(listOf("NFO_ID")))
        store.put("library", episode("a", 1).put("candidates", JSONArray().put(candidate)))
        assertEquals("小猪佩奇", store.get("library", "a")!!.getJSONArray("candidates").getJSONObject(0).getString("seriesTitle"))
    }
    @Test fun `moving an episode never renames the old library or moves its sibling`() {
        store.put("library", episode("a", 1)); store.put("library", episode("b", 2))
        val originalLibrary = store.get("library", "a")!!.getString("libraryId")
        store.put("library", store.get("library", "a")!!.put("libraryName", "新片库"))
        assertEquals("原片库", store.get("library", "b")!!.getString("libraryName")); assertEquals(originalLibrary, store.get("library", "b")!!.getString("libraryId"))
        assertNotEquals(originalLibrary, store.get("library", "a")!!.getString("libraryId"))
    }
    @Test fun `foreign key and unique source path constraints reject corrupt writes atomically`() {
        store.put("library", episode("a", 1))
        assertThrows(Exception::class.java) { store.put("assets", JSONObject().put("id", "missing-source").put("sourceId", "none").put("path", "/x.mp4").put("size", 1)) }
        assertTrue(store.list("assets").isEmpty())
        store.put("sources", JSONObject().put("id", "s").put("name", "NAS").put("type", "openlist").put("endpoint", "http://example.com").put("roots", JSONArray()))
        val asset = JSONObject().put("id", "asset").put("sourceId", "s").put("path", "/x.mp4").put("size", 1)
        store.put("assets", asset)
        assertThrows(Exception::class.java) { store.put("assets", JSONObject(asset.toString()).put("id", "duplicate")) }
        assertEquals(listOf("asset"), store.list("assets").map { it.getString("id") })
        val indexes = mutableSetOf<String>()
        store.database.openHelper.readableDatabase.query("PRAGMA index_list(watch_intervals)").use { cursor -> while (cursor.moveToNext()) indexes += cursor.getString(cursor.getColumnIndexOrThrow("name")) }
        assertTrue(indexes.any { it.contains("date") }); assertTrue(indexes.any { it.contains("episodeId") })
    }
    @Test fun `soft deleting a logical episode retains progress and historical foreign keys`() {
        store.put("library", episode("a", 1))
        store.put("progress", JSONObject().put("id", "a").put("positionMs", 150))
        store.put("watch", JSONObject().put("id", "w").put("episodeId", "a").put("startedAt", "2026-10-27T00:00:00Z").put("date", "2026-10-27").put("durationMillis", 150).put("kind", "normal"))
        store.delete("library", "a")
        assertTrue(store.get("library", "a")!!.getBoolean("deleted")); assertEquals(150L, store.get("watch", "w")!!.getLong("durationMillis")); assertEquals(150L, store.get("progress", "a")!!.getLong("positionMs"))
    }
}
