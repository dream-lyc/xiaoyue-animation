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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackControlsDeviceTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Test fun videoStartsFullscreenWithNoPermanentMenuButtons() = withFixture { fixture ->
        screenshot("controls-hidden.png")
        assertEquals("Video must fill the activity content", fixture.contentBounds(), fixture.videoBounds())
        assertFalse("Settings start hidden", device.hasObject(By.desc("播放设置面板")))
        assertFalse("Return button is not permanently beside the video", device.hasObject(By.text("返回片库")))
        assertFalse("Parent settings are not permanently beside the video", device.hasObject(By.text("家长设置")))
    }
    @Test fun downShowsAnOverlayWithoutPausingOrRecreatingVideoAndBackClosesItFirst() = withFixture { fixture ->
        val initial = fixture.videoBounds(); val view = fixture.playerView; val player = fixture.player; val surface = fixture.onMain { view.videoSurfaceView }
        device.pressDPadDown()
        assertTrue("First Down exposes only adjacent episodes", device.wait(Until.hasObject(By.desc("剧集切换层")), 3000))
        assertFalse("Speed belongs to the second Down", device.hasObject(By.text("倍速")))
        assertFalse("Source belongs to the second Down", device.hasObject(By.text("同集换源")))
        listOf("播放", "暂停", "快退10秒", "快进10秒", "画面比例", "家长设置", "返回片库").forEach { assertFalse("Removed control: $it", device.hasObject(By.text(it))) }
        assertTrue(device.hasObject(By.desc("上一集")))
        assertTrue(device.hasObject(By.desc("下一集")))
        screenshot("controls-down-overlay.png")
        device.pressDPadDown()
        assertTrue("Second Down exposes settings", device.wait(Until.hasObject(By.desc("播放设置面板")), 3000))
        screenshot("controls-settings.png")
        assertTrue(device.hasObject(By.text("倍速")))
        assertTrue(device.hasObject(By.text("同集换源")))
        assertTrue("Opening settings must keep actual playback running", fixture.onMain { player.isPlaying })
        assertEquals(initial, fixture.videoBounds())
        fixture.onMain { assertSame(view, findPlayer(fixture.activity.window.decorView)); assertSame(player, view.player); assertSame(surface, view.videoSurfaceView) }
        device.pressBack()
        assertTrue("First Back hides settings", device.wait(Until.gone(By.desc("播放设置面板")), 3000))
        assertEquals("Closing the overlay must not exit", 0, fixture.exits.get())
        assertEquals(initial, fixture.videoBounds())
        assertTrue("Back returns to adjacent episodes", device.hasObject(By.desc("剧集切换层")))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.desc("播放进度提示")), 3000))
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.desc("播放进度提示")), 3000))
        assertEquals(0, fixture.exits.get())
        device.pressBack(); await("Back from hidden playback exits") { fixture.exits.get() == 1 }
    }
    @Test fun adjacentEpisodeCallbacksPauseAndCommitBeforeNavigation() {
        for (next in listOf(false, true)) {
            val fixture = Fixture()
            try {
                fixture.start(queueIndex = 1)
                Thread.sleep(1100)
                device.pressDPadDown()
                assertTrue(device.wait(Until.hasObject(By.desc("剧集切换层")), 3000))
                assertTrue(device.hasObject(By.text("前一个故事")))
                assertTrue(device.hasObject(By.text("后一个故事")))
                if (next) device.pressDPadRight()
                device.pressDPadCenter()
                val requested = if (next) fixture.nextRequests else fixture.previousRequests
                val other = if (next) fixture.previousRequests else fixture.nextRequests
                await("manual ${if (next) "next" else "previous"} callback") { requested.get() == 1 }
                assertEquals(0, other.get())
                assertEquals("Manual navigation must not call automatic completion", 0, fixture.automaticCompletions.get())
                assertTrue("Switch callback sees paused player", fixture.pausedAtNavigation.get() == true)
                assertTrue("Final progress is durable before callback", fixture.committedPosition.get() > 0)
                assertTrue("Partial viewing interval is durable before callback", fixture.committedWatchMillis.get() > 0)
                device.pressDPadCenter()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                Thread.sleep(600)
                assertEquals("Pending navigation accepts only one callback", 1, requested.get())
                assertEquals(0, other.get())
                assertFalse("Media PLAY cannot resume while navigation is pending", fixture.onMain { fixture.player.playWhenReady })
                assertFalse(fixture.onMain { fixture.player.isPlaying })
            } finally { fixture.close() }
        }
    }
    @Test fun playlistBoundariesKeepMissingNeighboursDisabled() {
        for (index in listOf(0, 2)) {
            val fixture = Fixture()
            try {
                fixture.start(queueIndex = index)
                device.pressDPadDown()
                assertTrue(device.wait(Until.hasObject(By.desc("剧集切换层")), 3000))
                val label = if (index == 0) "上一集" else "下一集"
                val candidate = requireNotNull(device.findObject(By.desc(label)))
                android.util.Log.i("PlaybackBoundary", "index=$index matches=${device.findObjects(By.desc(label)).size}")
                var node: androidx.test.uiautomator.UiObject2? = candidate
                repeat(4) { depth ->
                    node?.let { current ->
                        android.util.Log.i("PlaybackBoundary", "depth=$depth class=${current.className} desc=${current.contentDescription} text=${current.text} enabled=${current.isEnabled} clickable=${current.isClickable} bounds=${current.visibleBounds}")
                        node = current.parent
                    }
                }
                // Compose may expose a non-clickable content-description child separately.
                // The actionable ancestor owns enabled state and D-pad activation.
                var action: androidx.test.uiautomator.UiObject2? = candidate
                while (action != null && !action.isClickable) action = action.parent
                assertFalse("Missing neighbour action must be disabled", requireNotNull(action) { "No action for $label" }.isEnabled)
                assertTrue(device.hasObject(By.text(if (index == 0) "没有上一集" else "没有下一集")))
                assertEquals(0, fixture.automaticCompletions.get())
            } catch (failure: Throwable) {
                try {
                    screenshot("controls-boundary-$index-failure.png")
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    device.dumpWindowHierarchy(File(context.cacheDir, "controls-boundary-$index-failure.xml"))
                } catch (captureFailure: Throwable) { failure.addSuppressed(captureFailure) }
                throw failure
            } finally { fixture.close() }
        }
    }
    private fun screenshot(name: String) { val context = ApplicationProvider.getApplicationContext<Context>(); device.takeScreenshot(File(context.cacheDir, name)) }
    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { fixture.start(); test(fixture) } finally { fixture.close() }
    }
    internal class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val databaseName = "controls-${UUID.randomUUID()}.db"
        private val namespace = "$databaseName.credentials"
        private val graph = AppGraph(context, databaseName, namespace)
        private var scenario: ActivityScenario<PlaybackFixtureActivity>? = null
        lateinit var activity: PlaybackFixtureActivity
        lateinit var playerView: PlayerView
        lateinit var player: Player
        val exits = AtomicInteger()
        val previousRequests = AtomicInteger()
        val nextRequests = AtomicInteger()
        val automaticCompletions = AtomicInteger()
        val pausedAtNavigation = AtomicReference<Boolean>()
        val committedPosition = AtomicLong()
        val committedWatchMillis = AtomicLong()
        @Volatile private var failRequests = false
        private val clip = InstrumentationRegistry.getInstrumentation().context.assets.open("controls-fixture.mp4").use { it.readBytes() }
        private val source = object : NanoHTTPD(0) {
            override fun serve(session: IHTTPSession): Response {
                if (failRequests) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "fixture unavailable")
                val range = session.headers["range"]?.removePrefix("bytes=")?.split('-')
                val start = range?.getOrNull(0)?.toIntOrNull() ?: 0
                val end = (range?.getOrNull(1)?.toIntOrNull() ?: (clip.size - 1)).coerceAtMost(clip.size - 1)
                if (start !in clip.indices || end < start) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT, "video/mp4", ByteArrayInputStream(clip, start, end - start + 1), (end - start + 1).toLong()).also { it.addHeader("Accept-Ranges", "bytes"); if (range != null) it.addHeader("Content-Range", "bytes $start-$end/${clip.size}") }
            }
        }
        fun start(queueIndex: Int? = null) {
            source.start()
            val row = graph.store.put("library", JSONObject().put("title", "遥控叠层验收").put("seriesTitle", "合成测试视频").put("libraryName", "隔离测试").put("seasonNumber", 1).put("episodeNumber", 1).put("available", true).put("status", "matched"))
            graph.policy.changeToday(3600, null)
            val episode = JSONObject(row.toString()).put("playbackUrl", "http://127.0.0.1:${source.listeningPort}/clip.mp4")
            scenario = ActivityScenario.launch(PlaybackFixtureActivity::class.java)
            fun navigated(counter: AtomicInteger) {
                pausedAtNavigation.set(!player.isPlaying && !player.playWhenReady)
                val read = Thread {
                    committedPosition.set(graph.store.get("progress", row.getString("id"))?.optLong("positionMs") ?: 0)
                    committedWatchMillis.set(graph.store.list("watch").sumOf { it.optLong("durationMillis") })
                }
                read.start(); read.join()
                counter.incrementAndGet()
            }
            requireNotNull(scenario).onActivity { host -> activity = host; host.setContent { MaterialTheme {
                Playback(episode, graph, onExit = { exits.incrementAndGet() }, onSwitch = {}, onParent = {},
                    onNext = { automaticCompletions.incrementAndGet() },
                    previousTitle = if (queueIndex != null && queueIndex > 0) "前一个故事" else null,
                    nextTitle = if (queueIndex != null && queueIndex < 2) "后一个故事" else null,
                    onPrevious = if (queueIndex != null && queueIndex > 0) ({ navigated(previousRequests) }) else null,
                    onNextRequested = if (queueIndex != null && queueIndex < 2) ({ navigated(nextRequests) }) else null)
            } } }
            await("PlayerView attached") {
                onMain { findPlayer(activity.window.decorView)?.let { view -> playerView = view; view.player?.let { current -> player = current; current.repeatMode = Player.REPEAT_MODE_ONE } } }
                this::player.isInitialized
            }
            await("actual looping playback", 15000) { onMain { player.isPlaying } }
        }
        fun failPlayback() {
            failRequests = true
            onMain { player.stop(); player.setMediaItem(androidx.media3.common.MediaItem.fromUri("http://127.0.0.1:${source.listeningPort}/missing-${UUID.randomUUID()}.mp4")); player.prepare(); player.play() }
        }
        fun videoBounds() = onMain { Rect().also { assertTrue(playerView.getGlobalVisibleRect(it)) } }
        fun contentBounds() = onMain { Rect().also { assertTrue(activity.findViewById<View>(android.R.id.content).getGlobalVisibleRect(it)) } }
        fun <T> onMain(block: () -> T): T { val value = AtomicReference<T>(); requireNotNull(scenario).onActivity { value.set(block()) }; return value.get() }
        override fun close() { scenario?.close(); Thread.sleep(300); source.stop(); graph.store.database.close(); context.deleteDatabase(databaseName); context.deleteSharedPreferences(namespace); graph.store.artworkDirectory.deleteRecursively() }
    }
    companion object {
        private fun findPlayer(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
            return null
        }
        private fun await(description: String, timeout: Long = 10000, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeout
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertTrue("Timed out waiting for $description", condition())
        }
    }
}
