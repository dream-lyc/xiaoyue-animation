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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ArtworkFallbackIntegrationTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var store: LocalStore
    private lateinit var name: String
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kRlkAAAAASUVORK5CYII=")
    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext(); name = "artwork-fallback-${UUID.randomUUID()}.db"; store = LocalStore(context, name)
        store.put("sources", JSONObject().put("id", "source").put("name", "源").put("type", "openlist").put("endpoint", "http://unused.invalid").put("credentialConfigured", false).put("roots", JSONArray()))
    }
    @After fun cleanup() { store.database.close(); context.deleteDatabase(name); store.artworkDirectory.deleteRecursively() }
    private fun episode(id: String = "ep", number: Int = 1, available: Boolean = true): JSONObject {
        store.put("assets", JSONObject().put("id", "asset-$id").put("sourceId", "source").put("path", "/$id.mp4").put("size", 200000).put("available", available))
        store.put("library", JSONObject().put("id", id).put("assetId", "asset-$id").put("title", id).put("seriesTitle", "动画").put("libraryName", "片库").put("seasonNumber", 1).put("episodeNumber", number).put("status", "unmatched").put("available", available).put("lockedFields", JSONArray()).put("tags", JSONObject()))
        return store.get("library", id)!!
    }
    @Test fun `old unmatched library gets first frame on demand exactly once and respects clip start`() {
        val row = episode(); store.put("episode-assets", JSONObject().put("id", "ep:asset-ep").put("episodeId", "ep").put("assetId", "asset-ep").put("startMs", 2500).put("endMs", 5000))
        var reads = 0; var position = -1L
        val sources = SourceRepository(store, CredentialVault(context), frameDecoder = { _, _, start -> reads++; position = start; png })
        val artwork = ArtworkRepository(store, sources::cacheFirstFrame)
        repeat(3) { assertNotNull(artwork.fileFor(artwork.cacheEpisode(row))) }
        assertEquals(1, reads); assertEquals(2500L, position); assertEquals("video_frame", store.get("library", "ep")!!.getString("artworkOrigin"))
    }
    @Test fun `series uses first available episode without copying its cover into unavailable episode`() {
        val unavailable = episode("one", 1, false); val available = episode("two", 2, true)
        val decoded = mutableListOf<String>()
        val sources = SourceRepository(store, CredentialVault(context), frameDecoder = { _, file, position -> decoded += file.path; assertEquals(0L, position); png })
        val result = ArtworkRepository(store, sources::cacheFirstFrame).cacheSeries(listOf(unavailable, available))!!
        assertEquals("two", result.getString("id")); assertEquals(listOf("/two.mp4"), decoded)
        assertFalse(store.get("library", "one")!!.has("artworkCacheKey"))
    }
    @Test fun `valid manual cover wins over video extraction`() {
        val row = episode(); row.put("artworkUrl", "http://unused.invalid/manual.png").put("lockedFields", JSONArray(listOf("artworkUrl"))); store.put("library", row)
        val manual = ArtworkRepository(store).cacheBytes("ep", png, "local"); val calls = AtomicInteger()
        val artwork = ArtworkRepository(store, { current, _ -> calls.incrementAndGet(); current })
        assertEquals(manual.getString("artworkCacheKey"), artwork.cacheEpisode(row).getString("artworkCacheKey"))
        assertEquals(0, calls.get()); assertEquals("local", store.get("library", "ep")!!.getString("artworkOrigin"))
    }
    @Test fun `failed online cover falls back to frame and negative retries are bounded`() {
        val row = episode(); val requests = AtomicInteger(); val frames = AtomicInteger()
        LocalHttpFixture { _, _, _, headers -> assertFalse(headers.containsKey("authorization")); requests.incrementAndGet(); FixtureResponse(503, ByteArray(0)) }.use { fixture ->
            row.put("artworkUrl", "http://127.0.0.1:${fixture.port}/missing.png"); store.put("library", row)
            var now = 0L
            val artwork = ArtworkRepository(store, { current, _ -> frames.incrementAndGet(); ArtworkRepository(store).cacheBytes(current.getString("id"), png, "video_frame") }, { now })
            assertNotNull(artwork.fileFor(artwork.cacheEpisode(row))); artwork.cacheEpisode(row)
            assertEquals(1, requests.get()); assertEquals(1, frames.get())
            now = 31000; artwork.cacheEpisode(row)
            assertEquals(2, requests.get()); assertEquals(1, frames.get())
        }
    }
    @Test fun `missing frame backoff avoids repeated source reads until cooldown`() {
        val row = episode(); var now = 0L; var calls = 0
        val artwork = ArtworkRepository(store, { current, _ -> calls++; current }, { now })
        artwork.cacheEpisode(row); artwork.cacheEpisode(row); assertEquals(1, calls)
        now = 31000; artwork.cacheEpisode(row); assertEquals(2, calls)
    }
    @Test fun `manual cover set during extraction cannot be overwritten by stale frame`() {
        val row = episode(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
        val sources = SourceRepository(store, CredentialVault(context), frameDecoder = { _, _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); png })
        val artwork = ArtworkRepository(store, sources::cacheFirstFrame)
        try {
            val pending = executor.submit<JSONObject> { artwork.cacheEpisode(row) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ArtworkRepository(store).cacheBytes("ep", png, "local"); release.countDown(); pending.get(5, TimeUnit.SECONDS)
            assertEquals("local", store.get("library", "ep")!!.getString("artworkOrigin"))
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
