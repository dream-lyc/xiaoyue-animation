package cn.childtv

import android.content.Context
import android.graphics.Rect
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
import cn.childtv.core.PolicyMode
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real BUFFERING/READY changes without replacing the playback composable or player. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackLayoutDeviceTest {
    @Test
    fun repeatedBufferingKeepsTheVideoViewportAndPlayerStable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "playback-layout-${UUID.randomUUID()}.db"
        val namespace = "$databaseName.credentials"
        val graph = AppGraph(context, databaseName, namespace)
        val clip =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("playback-fixture.mp4")
                .use { it.readBytes() }
        val gate = AtomicReference<CountDownLatch?>(null)
        val pendingGates = mutableListOf<CountDownLatch>()
        val source =
            object : NanoHTTPD(0) {
                override fun serve(session: IHTTPSession): Response {
                    val range = session.headers["range"]?.removePrefix("bytes=")?.split('-')
                    val start = range?.getOrNull(0)?.toIntOrNull() ?: 0
                    val end =
                        (range?.getOrNull(1)?.toIntOrNull() ?: (clip.size - 1)).coerceAtMost(
                            clip.size - 1
                        )
                    if (start !in clip.indices || end < start)
                        return newFixedLengthResponse(
                            Response.Status.RANGE_NOT_SATISFIABLE,
                            "text/plain",
                            "",
                        )
                    val requestGate = gate.get()
                    val input =
                        object :
                            FilterInputStream(ByteArrayInputStream(clip, start, end - start + 1)) {
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                requestGate?.await(15, TimeUnit.SECONDS)
                                return super.read(bytes, offset, length)
                            }

                            override fun read(): Int {
                                requestGate?.await(15, TimeUnit.SECONDS)
                                return super.read()
                            }
                        }
                    return newFixedLengthResponse(
                            if (range == null) Response.Status.OK
                            else Response.Status.PARTIAL_CONTENT,
                            "video/mp4",
                            input,
                            (end - start + 1).toLong(),
                        )
                        .also {
                            it.addHeader("Accept-Ranges", "bytes")
                            if (range != null)
                                it.addHeader("Content-Range", "bytes $start-$end/${clip.size}")
                        }
                }
            }
        var scenario: ActivityScenario<PlaybackFixtureActivity>? = null
        try {
            source.start()
            val row =
                graph.store.put(
                    "library",
                    JSONObject()
                        .put("title", "缓冲布局验收")
                        .put("seriesTitle", "合成设备片段")
                        .put("libraryName", "独立测试片库")
                        .put("seasonNumber", 1)
                        .put("episodeNumber", 1)
                        .put("synopsis", "")
                        .put("available", true)
                        .put("status", "matched"),
                )
            val id = row.getString("id")
            graph.policy.changeToday(3600, null)
            val url = "http://127.0.0.1:${source.listeningPort}/fixture.mp4"
            val episode = JSONObject(row.toString()).put("playbackUrl", url)
            scenario = ActivityScenario.launch(PlaybackFixtureActivity::class.java)
            requireNotNull(scenario).onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Playback(episode, graph, onExit = {}, onSwitch = {}, onParent = {})
                    }
                }
            }
            val playerView = AtomicReference<PlayerView?>()
            await("PlayerView attached") {
                requireNotNull(scenario).onActivity {
                    playerView.set(findPlayer(it.window.decorView))
                }
                playerView.get()?.player != null
            }
            val view = requireNotNull(playerView.get())
            val player = AtomicReference<Player>()
            val surface = AtomicReference<View?>()
            requireNotNull(scenario).onActivity {
                player.set(view.player)
                surface.set(view.videoSurfaceView)
            }
            await("first actual playback") { onMain(scenario!!) { player.get().isPlaying } }
            requireNotNull(scenario).onActivity { player.get().pause() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val initial = onMain(scenario!!) { bounds(view) }
            assertTrue(initial.width() > 0 && initial.height() > 0)
            repeat(3) {
                val waiting = CountDownLatch(1)
                pendingGates += waiting
                gate.set(waiting)
                requireNotNull(scenario).onActivity {
                    player.get().stop()
                    player.get().seekTo(0)
                    player.get().prepare()
                    player.get().play()
                }
                await("actual buffering state") {
                    onMain(scenario!!) { player.get().playbackState == Player.STATE_BUFFERING }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                requireNotNull(scenario).onActivity {
                    assertSame(
                        "Status changes must retain the Android video view",
                        view,
                        findPlayer(it.window.decorView),
                    )
                    assertSame("Buffering must not recreate ExoPlayer", player.get(), view.player)
                    assertSame(
                        "Buffering must retain the rendering surface",
                        surface.get(),
                        view.videoSurfaceView,
                    )
                    assertEquals(
                        "Buffering status must overlay the video instead of shrinking its viewport",
                        initial,
                        bounds(view),
                    )
                }
                waiting.countDown()
                gate.set(null)
                await("playback after buffering") { onMain(scenario!!) { player.get().isPlaying } }
                requireNotNull(scenario).onActivity { player.get().pause() }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(
                    "Hiding buffering status must preserve the video viewport",
                    initial,
                    onMain(scenario!!) { bounds(view) },
                )
            }
            assertEquals(PolicyMode.AVAILABLE, graph.policy.state().mode)
        } finally {
            pendingGates.forEach { it.countDown() }
            scenario?.close()
            Thread.sleep(300)
            source.stop()
            graph.store.database.close()
            context.deleteDatabase(databaseName)
            context.deleteSharedPreferences(namespace)
            graph.store.artworkDirectory.deleteRecursively()
        }
    }

    private fun bounds(view: View): Rect =
        Rect().also { assertTrue("Video must remain visible", view.getGlobalVisibleRect(it)) }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let {
                return it
            }
        return null
    }

    private fun <T> onMain(scenario: ActivityScenario<PlaybackFixtureActivity>, read: () -> T): T {
        val value = AtomicReference<T>()
        scenario.onActivity { value.set(read()) }
        return value.get()
    }

    private fun await(description: String, timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("Timed out waiting for $description", condition())
    }
}
