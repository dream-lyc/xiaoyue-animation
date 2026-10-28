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
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ArtworkIntegrationTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var store: LocalStore
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kRlkAAAAASUVORK5CYII=")
    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("child-tv.db"); store = LocalStore(context)
        store.artworkDirectory.deleteRecursively()
        store.put("library", JSONObject().put("id", "ep").put("title", "标题").put("seriesTitle", "动画").put("libraryName", "片库").put("seasonNumber", 1).put("episodeNumber", 1).put("status", "matched").put("lockedFields", JSONArray()).put("tags", JSONObject()))
    }
    @After fun clean() { store.artworkDirectory.deleteRecursively(); store.database.close(); context.deleteDatabase("child-tv.db") }
    @Test fun `decoded PNG cache uses content key and exports actual bytes`() {
        val repo = ArtworkRepository(store); val episode = repo.cacheBytes("ep", png)
        val file = repo.fileFor(episode)
        assertNotNull(file); assertArrayEquals(png, file!!.readBytes()); assertEquals(1, repo.exportFiles().length())
        repo.cacheBytes("ep", png); assertEquals(1, repo.exportFiles().length())
    }
    @Test fun `cover bytes roundtrip through backup into an empty cache`() {
        ArtworkRepository(store).cacheBytes("ep", png)
        val backup = BackupRepository(store).export()
        assertEquals(1, JSONObject(backup.getString("payload")).getJSONArray("covers").length())
        store.artworkDirectory.deleteRecursively()
        BackupRepository(store).restore(backup)
        val restored = ArtworkRepository(store).fileFor(store.get("library", "ep")!!)
        assertNotNull(restored); assertArrayEquals(png, restored!!.readBytes())
    }
    @Test fun `corrupt cover data with valid outer hash cannot replace catalog or invalidate playback`() {
        ArtworkRepository(store).cacheBytes("ep", png)
        val backup = BackupRepository(store).export(); val content = JSONObject(backup.getString("payload")); val cover = content.getJSONArray("covers").getJSONObject(0)
        cover.put("data", "definitely-not-valid-base64")
        val payload = content.toString(); backup.put("payload", payload).put("sha256", BackupIntegrity.digest(payload)); var stopped = false
        assertThrows(Exception::class.java) { BackupRepository(store).restore(backup) { stopped = true } }
        assertFalse(stopped); assertEquals("标题", store.get("library", "ep")!!.getString("title"))
    }
}
