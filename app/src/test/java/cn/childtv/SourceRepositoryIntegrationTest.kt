package cn.childtv

import cn.childtv.core.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SourceRepositoryIntegrationTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var store: LocalStore
    private lateinit var repository: SourceRepository
    private lateinit var server: LocalHttpFixture
    private lateinit var scanWorker: ExecutorService
    @Volatile private var pauseListing = false
    private val listEntered = CountDownLatch(1)
    private val listRelease = CountDownLatch(1)
    @Volatile private var present = true
    @Volatile private var unavailable = false
    @Volatile private var playbackVersion = 1
    @Volatile private var alwaysThrottleMetadata = false
    @Volatile private var throttleMetadata = false
    private val metadataCalls = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var localPoster = false
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kRlkAAAAASUVORK5CYII=")
    @Volatile private var paginated = false
    @Volatile private var failSecondPage = false
    private val pageRequests = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    @Volatile private var filenames = listOf("Peppa S01E01.mp4")
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("child-tv.db"); store = LocalStore(context)
        scanWorker = Executors.newSingleThreadExecutor()
        repository = SourceRepository(store, CredentialVault(context), worker = scanWorker)
        server = LocalHttpFixture { _, path, body, headers ->
            if (path.endsWith("/list")) pageRequests.computeIfAbsent(body.optInt("page", 1)) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            if (pauseListing && path.endsWith("/list")) { listEntered.countDown(); check(listRelease.await(10, TimeUnit.SECONDS)) }
            val payload = when {
                path == "/video" -> {
                    val bounds = headers.getValue("range").removePrefix("bytes=").split('-').map(String::toLong)
                    FixtureResponse(206, ByteArray((bounds[1] - bounds[0] + 1).toInt()), mapOf("Content-Range" to "bytes ${bounds[0]}-${bounds[1]}/200000"))
                }
                path == "/local-poster" -> FixtureResponse(206, png, mapOf("Content-Range" to "bytes 0-${png.size - 1}/${png.size}"))
                path == "/metadata/subtitles" && throttleMetadata && (metadataCalls.incrementAndGet() == 1 || alwaysThrottleMetadata) -> FixtureResponse(429, "{}".toByteArray(), mapOf("Retry-After" to "1"))
                path == "/metadata/subtitles" -> JSONObject("""{"data":[{"attributes":{"moviehash_match":true,"feature_details":{"feature_id":12,"parent_tmdb_id":44,"parent_title":"小猪佩奇","title":"泥坑","season_number":1,"episode_number":1}}}]}""")
                path == "/tmdb/tv/44" -> JSONObject().put("name", "小猪佩奇")
                path.endsWith("/images") -> JSONObject().put("stills", JSONArray())
                path.startsWith("/tmdb/") -> JSONObject().put("name", "泥坑").put("overview", "跳进泥坑")
                path.endsWith("/list") && paginated && body.optInt("page", 1) == 2 && failSecondPage -> JSONObject().put("code", 503)
                path.endsWith("/list") && paginated -> JSONObject().put("code", 200).put("data", JSONObject().put("has_more", body.optInt("page", 1) == 1).put("content", JSONArray().put(JSONObject().put("name", "Peppa S01E0${body.optInt("page", 1)}.mp4").put("size", 200000).put("is_dir", false))))
                unavailable -> JSONObject().put("code", 503).put("message", "offline")
                path.endsWith("/list") -> JSONObject().put("code", 200).put("data", JSONObject().put("total", if (present) filenames.size else 0).put("content", JSONArray().also { if (present) filenames.forEach { name -> it.put(JSONObject().put("name", name).put("size", 200000).put("is_dir", false)) } }))
                body.optString("path").endsWith("poster.png") && localPoster -> JSONObject().put("code", 200).put("data", JSONObject().put("size", png.size).put("raw_url", "http://127.0.0.1:${server.port}/local-poster"))
                body.optString("path").endsWith(".jpg") || body.optString("path").endsWith(".png") -> JSONObject().put("code", 404)
                body.optString("path").endsWith(".nfo") -> JSONObject().put("code", 404)
                else -> JSONObject().put("code", 200).put("data", JSONObject().put("name", "Peppa S01E01.mp4").put("size", 200000).put("raw_url", "http://127.0.0.1:${server.port}/video?version=$playbackVersion"))
            }
            payload
        }
        store.put("sources", JSONObject().put("id", "source").put("name", "NAS").put("type", "openlist").put("endpoint", "http://127.0.0.1:${server.port}").put("roots", JSONArray(listOf("/cartoon"))).put("credentialConfigured", false))
    }
    @After fun tearDown() { listRelease.countDown(); scanWorker.shutdownNow(); scanWorker.awaitTermination(5, TimeUnit.SECONDS); server.close(); store.database.close(); context.deleteDatabase("child-tv.db") }
    private fun scan(): JSONObject = awaitJob(repository.enqueue("source").getString("id"))
    private fun awaitJob(id: String): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val job = store.get("scans", id)!!
            if (job.getString("status") !in listOf("running", "waiting")) return job
            Thread.sleep(20)
        }
        error("扫描未在15秒内完成")
    }
    @Test fun `metadata retry exhaustion remains a resumable failed job rather than a false success`() {
        throttleMetadata = true; alwaysThrottleMetadata = true
        val base = "http://127.0.0.1:${server.port}"
        repository = SourceRepository(store, CredentialVault(context), metadataFactory = { MetadataPipeline(OpenSubtitlesProvider("key", endpoint = "$base/metadata"), TmdbProvider("token", endpoint = "$base/tmdb")) }, worker = scanWorker)
        val id = repository.enqueue("source").getString("id")
        assertEquals("failed", awaitJob(id).getString("status")); assertEquals(3, metadataCalls.get()); assertEquals(3, store.get("scans", id)!!.getJSONObject("cursor").getInt("metadataAttempt"))
        alwaysThrottleMetadata = false; repository.retry(id)
        assertEquals("completed", awaitJob(id).getString("status")); assertEquals(4, metadataCalls.get()); assertEquals(1, store.get("scans", id)!!.getInt("discovered"))
    }
    @Test fun `429 metadata waits persistently then succeeds automatically without user retry`() {
        throttleMetadata = true
        val base = "http://127.0.0.1:${server.port}"
        val pipeline = MetadataPipeline(OpenSubtitlesProvider("key", endpoint = "$base/metadata"), TmdbProvider("token", endpoint = "$base/tmdb"))
        repository = SourceRepository(store, CredentialVault(context), metadataFactory = { pipeline }, worker = scanWorker)
        val job = repository.enqueue("source"); val id = job.getString("id")
        val waitingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (store.get("scans", id)!!.getString("status") == "running" && System.nanoTime() < waitingDeadline) Thread.sleep(10)
        assertEquals("waiting", store.get("scans", id)!!.getString("status")); assertTrue(store.get("scans", id)!!.getJSONObject("cursor").getLong("retryAtMillis") > System.currentTimeMillis())
        assertEquals("completed", awaitJob(id).getString("status")); assertEquals(2, metadataCalls.get()); assertEquals("matched", store.list("library").single().getString("status"))
    }
    @Test fun `cancel then immediate retry cannot let an old HTTP attempt consume the new generation`() {
        pauseListing = true; val id = repository.enqueue("source").getString("id"); assertTrue(listEntered.await(5, TimeUnit.SECONDS))
        val previous = store.get("scans", id)!!.getJSONObject("cursor").optString("generation")
        repository.cancel(id); repository.retry(id); listRelease.countDown()
        assertEquals("completed", awaitJob(id).getString("status")); assertEquals(2, pageRequests.getValue(1).get()); assertNotEquals(previous, store.get("scans", id)!!.getJSONObject("cursor").optString("generation")); assertEquals(1, store.list("assets").size)
    }
    @Test fun `local poster PNG is actually range read decoded cached and included in backup`() {
        localPoster = true; assertEquals("completed", scan().getString("status"))
        val episode = store.list("library").single(); val file = ArtworkRepository(store).fileFor(episode)
        assertNotNull(file); assertArrayEquals(png, file!!.readBytes()); assertEquals("local", episode.getString("artworkOrigin")); assertEquals(1, JSONObject(BackupRepository(store).export().getString("payload")).getJSONArray("covers").length())
    }
    @Test fun `restored anonymous source does not read retained old encrypted credentials`() {
        assertEquals("completed", scan().getString("status")); val backup = BackupRepository(store).export()
        context.getSharedPreferences("encrypted-credentials", Context.MODE_PRIVATE).edit().putString("source:source", "this-is-not-valid-encrypted-credentials").commit()
        try { BackupRepository(store).restore(backup); assertFalse(store.get("sources", "source")!!.getBoolean("credentialConfigured")); assertEquals("连接成功", repository.test("source").getString("message")) }
        finally { context.getSharedPreferences("encrypted-credentials", Context.MODE_PRIVATE).edit().remove("source:source").commit() }
    }
    @Test fun `retry resumes persisted page cursor without rediscovering completed page`() {
        paginated = true; failSecondPage = true
        val jobId = repository.enqueue("source").getString("id")
        assertEquals("failed", awaitJob(jobId).getString("status"))
        assertEquals("2", store.get("scans", jobId)!!.getJSONObject("cursor").getString("page"))
        failSecondPage = false; repository.retry(jobId)
        assertEquals("completed", awaitJob(jobId).getString("status")); assertEquals(1, pageRequests.getValue(1).get()); assertEquals(2, pageRequests.getValue(2).get()); assertEquals(2, store.list("assets").size)
    }
    @Test fun `in flight scan cannot resurrect catalog rows after atomic restore replaces its job`() {
        pauseListing = true
        repository.enqueue("source")
        assertTrue(listEntered.await(5, TimeUnit.SECONDS))
        val source = store.get("sources", "source")!!
        store.replaceAll(mapOf("sources" to listOf(source)))
        listRelease.countDown(); scanWorker.shutdown(); assertTrue(scanWorker.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(store.list("assets").isEmpty()); assertTrue(store.list("library").isEmpty()); assertTrue(store.list("scans").isEmpty())
    }
    @Test fun `strong automatic match persists real animation hierarchy identity and readable candidate`() {
        val base = "http://127.0.0.1:${server.port}"
        repository = SourceRepository(store, CredentialVault(context), metadataFactory = { MetadataPipeline(OpenSubtitlesProvider("key", endpoint = "$base/metadata"), TmdbProvider("token", endpoint = "$base/tmdb")) }, worker = scanWorker)
        assertEquals("completed", scan().getString("status"))
        val episode = store.list("library").single()
        assertEquals("matched", episode.getString("status")); assertEquals("tmdb:44", episode.getString("confirmedIdentity"))
        assertEquals("小猪佩奇", episode.getString("seriesTitle")); assertEquals("泥坑", episode.getString("title")); assertEquals(1, episode.getInt("seasonNumber")); assertEquals(1, episode.getInt("episodeNumber"))
        assertEquals("泥坑", episode.getJSONArray("candidates").getJSONObject(0).getString("title"))
    }
    @Test fun `unknown filename and two apparent versions never abort scan or merge without strong evidence`() {
        filenames = listOf("unknown.mp4", "Peppa S01E01.mp4", "Peppa English S01E01.mp4")
        assertEquals("completed", scan().getString("status")); assertEquals(3, store.list("assets").size); assertEquals(3, store.list("library").size)
        assertTrue(store.list("library").all { it.getString("status") != "matched" })
    }
    @Test fun `real HTTP scan is idempotent and preserves manually locked metadata`() {
        assertEquals("completed", scan().getString("status")); val original = store.list("library").single()
        store.put("library", original.put("title", "家长修正").put("synopsis", "人工简介").put("lockedFields", JSONArray(listOf("title", "synopsis"))))
        assertEquals("completed", scan().getString("status"))
        val restored = store.list("library").single(); assertEquals(original.getString("id"), restored.getString("id")); assertEquals("家长修正", restored.getString("title")); assertEquals("人工简介", restored.getString("synopsis")); assertEquals(1, store.list("assets").size)
    }
    @Test fun `playback addresses resolve on demand and multiple versions preserve logical episode and clip bounds`() {
        assertEquals("completed", scan().getString("status"))
        val episode = store.list("library").single(); val id = episode.getString("id"); val originalAsset = episode.getString("assetId")
        store.put("assets", JSONObject().put("id", "alternate").put("sourceId", "source").put("path", "/cartoon/alternate.mp4").put("size", 200000).put("available", true))
        store.put("episode-assets", JSONObject().put("id", "$id:alternate").put("episodeId", id).put("assetId", "alternate").put("startMs", 1000).put("endMs", 2000))
        val selected = JSONObject(episode.toString()).put("selectedAssetId", "alternate")
        val first = repository.resolve(selected)
        assertEquals(1000L, first.getLong("clipStartMs")); assertEquals(2000L, first.getLong("clipEndMs")); assertTrue(first.getString("playbackUrl").endsWith("version=1"))
        playbackVersion = 2
        val refreshed = repository.refreshPlayback(selected, 1500, false)
        assertTrue(refreshed.getString("playbackUrl").endsWith("version=2")); assertEquals(id, refreshed.getString("id")); assertEquals(1500L, refreshed.getLong("resumePositionMs"))
        assertFalse(store.get("library", id)!!.has("playbackUrl")); assertEquals(originalAsset, store.get("library", id)!!.getString("assetId")); assertEquals(1, store.list("library").size)
    }
    @Test fun `candidate confirmation persists external identity across subsequent scans`() {
        assertEquals("completed", scan().getString("status"))
        val episode = store.list("library").single()
        episode.put("candidates", JSONArray().put(JSONObject().put("id", "candidate").put("identity", "tmdb:44").put("seasonNumber", 2).put("episodeNumber", 3)))
        store.put("library", episode)
        repository.confirmMatch(episode.getString("id"), "candidate")
        assertEquals("tmdb:44", store.get("library", episode.getString("id"))!!.optString("confirmedIdentity"))
        assertEquals(2, store.get("library", episode.getString("id"))!!.getInt("seasonNumber")); assertEquals(3, store.get("library", episode.getString("id"))!!.getInt("episodeNumber"))
        assertEquals("completed", scan().getString("status"))
        assertEquals("matched", store.get("library", episode.getString("id"))!!.getString("status"))
    }
    @Test fun `only successful full scan marks missing files unavailable while preserving history`() {
        assertEquals("completed", scan().getString("status")); val original = store.list("library").single();val assetId = original.getString("assetId")
        unavailable = true; assertEquals("failed", scan().getString("status")); assertTrue(store.get("assets", assetId)!!.getBoolean("available"))
        unavailable = false; present = false; assertEquals("completed", scan().getString("status"))
        assertFalse(store.get("assets", assetId)!!.getBoolean("available")); assertNotNull(store.get("library", original.getString("id")))
    }
}
