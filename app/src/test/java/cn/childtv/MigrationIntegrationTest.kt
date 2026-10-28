package cn.childtv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MigrationIntegrationTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    @Before fun prepare() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("child-tv.db") }
    @After fun clean() { context.deleteDatabase("child-tv.db") }
    private fun oldDatabase(rows: Map<String, List<JSONObject>>) {
        val schema = JSONObject(File("schemas/cn.childtv.ChildDatabase/1.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase("child-tv.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS documents (collection TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(collection,id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,?)", arrayOf(schema.getString("identityHash")))
            rows.forEach { (collection, values) -> values.forEach { row -> db.execSQL("INSERT INTO documents (collection,id,json) VALUES(?,?,?)", arrayOf(collection, row.getString("id"), row.toString())) } }
            db.version = 1
        }
    }
    @Test fun `v1 documents migrate stable IDs locked fields tags and actual time into typed tables`() {
        val episode = JSONObject().put("id", "ep").put("title", "人工标题").put("seriesTitle", "动画").put("libraryName", "片库").put("seasonNumber", 1).put("episodeNumber", 1).put("status", "matched").put("lockedFields", JSONArray(listOf("title"))).put("tags", JSONObject().put("语言", JSONArray(listOf("英语"))))
        val watch = JSONObject().put("id", "watch").put("episodeId", "ep").put("libraryName", "片库").put("seriesTitle", "动画").put("startedAt", "2026-10-27T00:00:00Z").put("date", "2026-10-27").put("durationMillis", 1150).put("kind", "normal")
        oldDatabase(mapOf("library" to listOf(episode), "watch" to listOf(watch)))
        val store = LocalStore(context)
        try {
            assertEquals("人工标题", store.get("library", "ep")!!.getString("title"))
            assertEquals("title", store.get("library", "ep")!!.getJSONArray("lockedFields").getString(0))
            assertEquals("英语", store.get("library", "ep")!!.getJSONObject("tags").getJSONArray("语言").getString(0))
            assertEquals(1150L, store.get("watch", "watch")!!.getLong("durationMillis"))
            assertTrue(store.database.documents().list("library").isEmpty())
        } finally { store.database.close() }
    }
    @Test fun `failed legacy data migration preserves original document rather than replacing database`() {
        val invalid = JSONObject().put("id", "broken").put("title", "保留原文")
        oldDatabase(mapOf("library" to listOf(invalid)))
        assertThrows(Exception::class.java) { LocalStore(context) }
        context.openOrCreateDatabase("child-tv.db", Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT json FROM documents WHERE collection='library' AND id='broken'", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("保留原文", JSONObject(cursor.getString(0)).getString("title")) }
        }
    }
}
