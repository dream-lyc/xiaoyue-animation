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
class StorageIntegrationTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var store: LocalStore
    private lateinit var api: AdminApi
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("child-tv.db"); store = LocalStore(context)
        val vault = CredentialVault(context); api = AdminApi(store, vault, SourceRepository(store, vault))
    }
    @After fun close() { store.database.close(); context.deleteDatabase("child-tv.db") }
    private fun episode() = JSONObject().put("id", "ep").put("title", "集名").put("seriesTitle", "动画").put("libraryName", "片库").put("seasonNumber", 1).put("episodeNumber", 1).put("status", "unmatched").put("available", false).put("lockedFields", JSONArray()).put("tags", JSONObject().put("庆爸路线", JSONArray(listOf("常规一", "常规二"))))
    @Test fun `deleting dictionary removes ghost episode tags`() {
        store.put("library", episode())
        api.route("DELETE", "tags/qingba", JSONObject(), emptyMap())
        assertFalse(store.get("library", "ep")!!.getJSONObject("tags").has("庆爸路线"))
    }
    @Test fun `restore rejects valid hash with corrupt watch before replacing library`() {
        store.put("library", episode())
        val backup = BackupRepository(store).export(); val content = JSONObject(backup.getString("payload"))
        content.put("watch", JSONArray().put(JSONObject().put("id", "w").put("episodeId", "ep").put("date", "invalid").put("startedAt", "invalid").put("seconds", -3).put("kind", "normal")))
        val payload = content.toString(); backup.put("payload", payload).put("sha256", BackupIntegrity.digest(payload))
        assertThrows(Exception::class.java) { BackupRepository(store).restore(backup) }
        assertEquals("集名", store.get("library", "ep")!!.getString("title"))
    }
    @Test fun `backup over one MiB round trips its own payload`() {
        repeat(400) { index -> store.put("library", episode().put("id", "ep$index").put("synopsis", "x".repeat(3000))) }
        val backup = BackupRepository(store).export()
        assertTrue(backup.toString().toByteArray().size > 1_048_576)
        BackupRepository(store).restore(backup)
        assertEquals(400, store.list("library").size)
    }
}
