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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BackupIntegrationTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var store: LocalStore
    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("child-tv.db"); store = LocalStore(context)
        store.put("library", JSONObject().put("id", "ep").put("title", "标题").put("seriesTitle", "动画").put("libraryName", "片库").put("seasonNumber", 1).put("episodeNumber", 1).put("status", "matched").put("lockedFields", JSONArray(listOf("title"))).put("tags", JSONObject()))
    }
    @After fun clean() { store.database.close(); context.deleteDatabase("child-tv.db") }
    private fun event() = JSONObject().put("id", "idempotent").put("episodeId", "ep").put("date", PolicyRepository(store).today().toString()).put("startedAt", Instant.now().toString()).put("seconds", 0).put("durationMillis", 450).put("kind", "normal")
    private fun altered(backup: JSONObject, change: (JSONObject) -> Unit): JSONObject {
        val content = JSONObject(backup.getString("payload")); change(content); val payload = content.toString()
        return JSONObject(backup.toString()).put("payload", payload).put("sha256", BackupIntegrity.digest(payload))
    }
    @Test fun `fresh install roundtrip restores millisecond remainder and event idempotency`() {
        val checkpoint = event(); PolicyRepository(store).checkpoint(checkpoint, null)
        val backup = BackupRepository(store).export(); val identity = store.get("library", "ep")!!.getString("seriesId")
        store.database.close(); context.deleteDatabase("child-tv.db"); store = LocalStore(context)
        BackupRepository(store).restore(backup)
        PolicyRepository(store).checkpoint(checkpoint, null)
        assertEquals(450L, store.list("watch").sumOf { it.getLong("durationMillis") }); assertEquals("450", store.getSetting("remainder:${PolicyRepository(store).today()}", "0")); assertEquals(identity, store.get("library", "ep")!!.getString("seriesId"))
    }
    @Test fun `older compatible backup reconstructs checkpoints and fractional remainder from watch events`() {
        val checkpoint = event(); PolicyRepository(store).checkpoint(checkpoint, null)
        val backup = altered(BackupRepository(store).export()) { it.remove("checkpoints"); it.remove("watchRemainders") }
        store.database.close(); context.deleteDatabase("child-tv.db"); store = LocalStore(context)
        BackupRepository(store).restore(backup); PolicyRepository(store).checkpoint(checkpoint, null)
        assertEquals("450", store.getSetting("remainder:${PolicyRepository(store).today()}", "0")); assertEquals(1, store.list("watch").size)
    }
    @Test fun `invalid backup never interrupts playback or replaces current catalog`() {
        val backup = altered(BackupRepository(store).export()) { it.put("watchRemainders", "not an object") }
        var callback = false
        assertThrows(Exception::class.java) { BackupRepository(store).restore(backup) { callback = true } }
        assertFalse(callback); assertEquals("标题", store.get("library", "ep")!!.getString("title")); assertTrue(store.list("restore-snapshots").isEmpty())
    }
    @Test fun `valid restore calls epoch barrier once and retains a pre restore snapshot`() {
        val backup = BackupRepository(store).export(); store.put("library", store.get("library", "ep")!!.put("title", "稍后修正"))
        var callbacks = 0; BackupRepository(store).restore(backup) { callbacks++ }
        assertEquals(1, callbacks); assertEquals("标题", store.get("library", "ep")!!.getString("title"))
        val previous = JSONObject(store.list("restore-snapshots").single().getJSONObject("backup").getString("payload"))
        assertEquals("稍后修正", previous.getJSONArray("library").getJSONObject(0).getString("title"))
    }
}
