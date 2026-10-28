package cn.childtv

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.childtv.core.ParentAction
import cn.childtv.core.PolicyMode
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One isolated end-to-end graph: source -> scan -> manual metadata/tags -> actual playback -> parent -> analytics. */
@RunWith(AndroidJUnit4::class)
class Media3DeviceTest {
    @Test fun actualPlaybackPausesWithoutChargingAndFinishesCurrentEpisodeAtQuota() {
        val diagnosticStart = android.os.SystemClock.elapsedRealtime()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "media3-${UUID.randomUUID()}.db"
        val namespace = "$databaseName.credentials"
        val graph = AppGraph(context, databaseName, namespace)
        val clip = InstrumentationRegistry.getInstrumentation().context.assets.open("playback-fixture.mp4").use { it.readBytes() }
        val source = object : NanoHTTPD(18183) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/api/fs/list" || session.uri == "/api/fs/get") {
                    val raw = mutableMapOf<String, String>(); session.parseBody(raw); val path = JSONObject(raw["postData"].orEmpty()).getString("path")
                    val json = when {
                        session.uri.endsWith("/list") -> JSONObject().put("code", 200).put("data", JSONObject().put("total", 1).put("content", JSONArray().put(if (path == "/") JSONObject().put("name", "Animation").put("is_dir", true).put("size", 0) else JSONObject().put("name", "Fixture.S01E01.mp4").put("is_dir", false).put("size", clip.size))))
                        path.endsWith(".mp4") -> JSONObject().put("code", 200).put("data", JSONObject().put("size", clip.size).put("raw_url", "http://127.0.0.1:18183/fixture.mp4"))
                        else -> JSONObject().put("code", 404)
                    }
                    return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }
                val range = session.headers["range"]?.removePrefix("bytes=")?.split('-')
                val start = range?.getOrNull(0)?.toIntOrNull() ?: 0
                val end = (range?.getOrNull(1)?.toIntOrNull() ?: (clip.size - 1)).coerceAtMost(clip.size - 1)
                if (start !in clip.indices || end < start) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT, "video/mp4", ByteArrayInputStream(clip, start, end - start + 1), (end - start + 1).toLong()).also {
                    it.addHeader("Accept-Ranges", "bytes"); if (range != null) it.addHeader("Content-Range", "bytes $start-$end/${clip.size}")
                }
            }
        }
        val completions = AtomicInteger()
        val player = AtomicReference<Player?>()
        val observedMillis = java.util.concurrent.atomic.AtomicLong()
        val observedOpen = java.util.concurrent.atomic.AtomicLong(-1)
        val observationWall = AtomicReference<java.time.Instant?>()
        val observer = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (isPlaying) observedOpen.compareAndSet(-1, now)
                else { val start = observedOpen.getAndSet(-1); if (start >= 0) observedMillis.addAndGet(now - start) }
            }
        }
        var scenario: ActivityScenario<PlaybackFixtureActivity>? = null
        fun diagnostic(stage: String) {
            var position = -1L; var duration = -1L; var isPlaying = false
            scenario?.onActivity { player.get()?.let { current -> position = current.currentPosition; duration = current.duration; isPlaying = current.isPlaying } }
            val events = graph.store.list("watch")
            val intervals = events.joinToString(",") { "${it.optString("kind")}:${it.optLong("durationMillis")}" }
            val message = "MEDIA3_FIXTURE stage=$stage elapsedMs=${android.os.SystemClock.elapsedRealtime() - diagnosticStart} watchedSeconds=${graph.policy.state().watchedSeconds} observedMillis=${observedMillis.get() + observedOpen.get().let { if (it < 0) 0 else android.os.SystemClock.elapsedRealtime() - it }} eventMillis=${events.sumOf { it.optLong("durationMillis") }} durationMs=$duration positionMs=$position isPlaying=$isPlaying intervals=[$intervals]"
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "\n$message\n") })
        }
        try {
            source.start()
            val created = graph.api.route("POST", "sources", JSONObject().put("name", "Instrumented source").put("type", "openlist").put("endpoint", "http://127.0.0.1:18183").put("roots", JSONArray()), emptyMap()) as JSONObject
            val sourceId = created.getString("id")
            val directories = graph.api.route("GET", "sources/$sourceId/directories", JSONObject(), mapOf("path" to "/")) as JSONObject
            assertEquals("/Animation", directories.getJSONArray("directories").getJSONObject(0).getString("path"))
            graph.api.route("PATCH", "sources/$sourceId", JSONObject().put("roots", JSONArray(listOf("/Animation"))), emptyMap())
            diagnostic("scan-requested")
            await("persisted source scan", 15000) { graph.store.list("scans").singleOrNull()?.optString("status") == "completed" }
            diagnostic("scan-complete")
            val scanned = graph.store.list("library").single(); val id = scanned.getString("id")
            graph.api.route("PATCH", "library/$id", JSONObject().put("title", "设备验收集").put("seriesTitle", "设备验收动画").put("libraryName", "设备验收片库").put("seasonNumber", 1).put("episodeNumber", 1).put("synopsis", "手工确认的合成测试片段").put("lockedFields", JSONArray(listOf("title", "seriesTitle", "seasonNumber", "episodeNumber"))), emptyMap())
            graph.api.route("POST", "tags", JSONObject().put("key", "验收路线").put("values", JSONArray(listOf("设备"))), emptyMap())
            val batch = JSONObject().put("episodeIds", JSONArray(listOf(id))).put("key", "验收路线").put("values", JSONArray(listOf("设备"))).put("operation", "append")
            assertEquals(1, (graph.api.route("POST", "library/tags/preview", batch, emptyMap()) as JSONObject).getInt("affectedCount"))
            graph.api.route("POST", "library/tags", batch.put("confirmed", true), emptyMap())
            graph.policy.changeToday(1, null)
            val episode = graph.sources.resolve(graph.store.get("library", id)!!)
            scenario = ActivityScenario.launch(PlaybackFixtureActivity::class.java)
            diagnostic("activity-launched")
            requireNotNull(scenario).onActivity { activity -> activity.setContent { MaterialTheme { Playback(episode, graph, onExit = {}, onSwitch = {}, onParent = {}, onNext = { completions.incrementAndGet() }) } } }
            fun findPlayer(view: View): PlayerView? {
                if (view is PlayerView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
                return null
            }
            await("Media3 player view") {
                requireNotNull(scenario).onActivity { activity ->
                    val candidate = findPlayer(activity.window.decorView)?.player
                    if (candidate != null && player.compareAndSet(null, candidate)) {
                        observationWall.set(java.time.Instant.now())
                        candidate.addListener(observer)
                        observer.onIsPlayingChanged(candidate.isPlaying)
                    }
                }
                player.get() != null
            }
            fun isPlaying(): Boolean { var value = false; requireNotNull(scenario).onActivity { value = player.get()!!.isPlaying }; return value }
            await("actual clip playing", 15000) { isPlaying() }
            diagnostic("playing-start")
            Thread.sleep(1500)
            diagnostic("before-pause")
            requireNotNull(scenario).onActivity { player.get()!!.pause() }
            await("persisted finishing permission") { graph.policy.state().mode == PolicyMode.FINISHING_CURRENT_EPISODE }
            diagnostic("paused-checkpoint")
            val pausedMillis = graph.store.list("watch").sumOf { it.getLong("durationMillis") }
            assertTrue("Actual play time must be charged", pausedMillis >= 1000)
            Thread.sleep(700)
            assertEquals("Paused time must not be charged", pausedMillis, graph.store.list("watch").sumOf { it.getLong("durationMillis") })
            diagnostic("paused-end")
            requireNotNull(scenario).onActivity { player.get()!!.play() }
            diagnostic("resumed")
            await("current episode completion", 15000) { completions.get() == 1 }
            diagnostic("ended")
            assertEquals(PolicyMode.LOCKED, graph.policy.state().mode)
            assertFalse(graph.policy.canStart(id, 0)); assertFalse(graph.policy.canStart("another-episode", 0))
            val restartedWhileLocked = java.util.concurrent.atomic.AtomicBoolean(false)
            val lockedObserver = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) restartedWhileLocked.set(true) } }
            requireNotNull(scenario).onActivity { player.get()!!.addListener(lockedObserver) }
            try {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                Thread.sleep(400)
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                Thread.sleep(600)
                assertFalse("Media PLAY and confirm must not restart an ended locked episode", restartedWhileLocked.get())
                assertEquals(PolicyMode.LOCKED, graph.policy.state().mode)
            } finally { requireNotNull(scenario).onActivity { player.get()!!.removeListener(lockedObserver) } }
            val events = graph.store.list("watch")
            assertEquals(1000L, events.filter { it.getString("kind") == "normal" }.sumOf { it.getLong("durationMillis") })
            assertTrue(events.filter { it.getString("kind") == "grace" }.sumOf { it.getLong("durationMillis") } >= 4000)
            requireNotNull(scenario).onActivity { observer.onIsPlayingChanged(player.get()!!.isPlaying) }
            val totalEventMillis = events.sumOf { it.getLong("durationMillis") }
            // The independent listener may attach just after playback starts. Compare the same
            // observation window, rather than equating an 8-second media timeline with wall time.
            val observedFrom = requireNotNull(observationWall.get())
            val eventMillisInWindow = events.sumOf { event ->
                val start = java.time.Instant.parse(event.getString("startedAt"))
                val duration = event.getLong("durationMillis")
                duration - java.time.Duration.between(start, observedFrom).toMillis().coerceIn(0, duration)
            }
            assertTrue("Observed playing=${observedMillis.get()}ms, persisted window=${eventMillisInWindow}ms, total=${totalEventMillis}ms", kotlin.math.abs(eventMillisInWindow - observedMillis.get()) <= 500)
            assertEquals("Quota uses actual elapsed playback seconds", totalEventMillis / 1000, graph.policy.state().watchedSeconds)
            val used = graph.policy.state().watchedSeconds
            val challenge = graph.parent.challenge(ParentAction.CHANGE_TODAY)
            val numbers = Regex("\\d+").findAll(challenge.prompt).map { it.value.toInt() }.toList()
            assertEquals("Parent challenge must be a single operation", 2, numbers.size)
            val solution = when { challenge.prompt.contains('×') -> numbers[0] * numbers[1]; challenge.prompt.contains('+') -> numbers[0] + numbers[1]; challenge.prompt.contains('-') -> numbers[0] - numbers[1]; else -> throw AssertionError("Unsupported arithmetic operation") }
            val token = requireNotNull(graph.parent.answer(challenge.id, solution))
            graph.store.transaction { assertTrue(graph.parent.consume(token, ParentAction.CHANGE_TODAY)); graph.policy.changeToday(60, null) }
            assertEquals(used, graph.policy.state().watchedSeconds); assertEquals(PolicyMode.AVAILABLE, graph.policy.state().mode)
            assertFalse(graph.parent.consume(token, ParentAction.CHANGE_TODAY))
            val analytics = graph.api.route("GET", "analytics", JSONObject(), mapOf("dimension" to "tag", "tags" to JSONObject().put("验收路线", JSONArray(listOf("设备"))).toString())) as JSONObject
            assertEquals(used, analytics.getLong("totalSeconds")); assertEquals(1, analytics.getInt("episodeCount")); assertEquals(used, analytics.getJSONArray("rows").getJSONObject(0).getLong("seconds"))
            val detail = graph.api.route("GET", "analytics/day", JSONObject(), mapOf("date" to graph.policy.today().toString())) as JSONObject
            assertEquals(used, detail.getLong("totalSeconds")); assertTrue(detail.getLong("graceSeconds") >= 4)
            val dailyEntries = detail.getJSONArray("entries")
            assertEquals("One playback session must produce one calendar entry", 1, dailyEntries.length())
            val dailyEntry = dailyEntries.getJSONObject(0)
            assertEquals(events.sumOf { it.getLong("durationMillis") }, dailyEntry.getLong("durationMillis"))
            assertEquals("mixed", dailyEntry.getString("kind"))
            assertEquals(listOf("normal", "grace"), dailyEntry.getJSONArray("kinds").strings())
        } finally {
            scenario?.onActivity { player.get()?.removeListener(observer) }; scenario?.close(); Thread.sleep(300); source.stop(); graph.store.database.close(); context.deleteDatabase(databaseName); context.deleteSharedPreferences(namespace); graph.store.artworkDirectory.deleteRecursively()
        }
    }
    private fun await(description: String, timeout: Long = 10000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("Timed out waiting for $description", condition())
    }
}
