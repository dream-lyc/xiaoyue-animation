package cn.childtv

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.io.File
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackRemoteActionsDeviceTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Test fun secondDownCanApplySpeedWithoutObsoleteControlsOrChangingVideoBounds() = withFixture { fixture ->
        val original = fixture.videoBounds()
        showPanel()
        focus("倍速"); device.pressDPadCenter()
        assertTrue(device.wait(Until.hasObject(By.text("1.5×")), 3000))
        focus("1.5×"); device.pressDPadCenter()
        await("1.5x applied to actual player") { fixture.onMain { kotlin.math.abs(fixture.player.playbackParameters.speed - 1.5f) < .01f } }
        assertFalse("Aspect ratio controls were removed", device.hasObject(By.text("画面比例")))
        assertFalse(device.hasObject(By.text("快进10秒")))
        assertFalse(device.hasObject(By.text("返回片库")))
        assertEquals(original, fixture.videoBounds())
        assertTrue(fixture.onMain { fixture.player.playWhenReady })
    }
    @Test fun hiddenConfirmMediaKeysAndTenSecondSeekingControlTheSamePlayer() = withFixture { fixture ->
        val original = fixture.videoBounds()
        device.pressDPadCenter()
        await("hidden confirm pauses") { fixture.onMain { !fixture.player.playWhenReady } }
        assertTrue(device.wait(Until.hasObject(By.desc("播放进度提示")), 3000))
        device.pressDPadCenter()
        await("HUD confirm resumes without a focusable play button") { fixture.onMain { fixture.player.isPlaying } }
        assertFalse(device.hasObject(By.desc("剧集切换层")))
        key(KeyEvent.KEYCODE_MEDIA_PLAY)
        await("media PLAY resumes") { fixture.onMain { fixture.player.isPlaying } }
        device.pressBack(); assertTrue(device.wait(Until.gone(By.desc("播放进度提示")), 3000))
        key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        await("media PAUSE") { fixture.onMain { !fixture.player.playWhenReady } }
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        await("media toggle resumes") { fixture.onMain { fixture.player.isPlaying } }
        if (device.hasObject(By.desc("播放进度提示"))) device.pressBack()
        fixture.onMain { fixture.player.seekTo(20_000) }
        await("seeked to midpoint") { fixture.onMain { fixture.player.currentPosition in 19_500L..22_000L } }
        val seeks = LinkedBlockingQueue<Pair<Long, Long>>()
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.offer(oldPosition.positionMs to newPosition.positionMs)
            }
        }
        fixture.onMain { fixture.player.addListener(listener) }
        try {
            device.pressDPadLeft()
            val backward = requireNotNull(seeks.poll(5, TimeUnit.SECONDS)) { "Hidden Left did not seek" }
            assertTrue("Left should seek back 10 seconds: $backward", kotlin.math.abs(backward.second - backward.first + 10_000) <= 250)
            assertTrue("Seek feedback panel is visible", device.wait(Until.hasObject(By.desc("播放进度提示")), 3000))
            device.pressBack(); assertTrue(device.wait(Until.gone(By.desc("播放进度提示")), 3000))
            device.pressDPadRight()
            val forward = requireNotNull(seeks.poll(5, TimeUnit.SECONDS)) { "Hidden Right did not seek" }
            assertTrue("Right should seek forward 10 seconds: $forward", kotlin.math.abs(forward.second - forward.first - 10_000) <= 250)
            assertTrue("Forward seek keeps progress feedback reachable", device.wait(Until.hasObject(By.desc("播放进度提示")), 3000))
            assertTrue(fixture.onMain { fixture.player.playWhenReady })
            assertEquals(original, fixture.videoBounds())
        } finally { fixture.onMain { fixture.player.removeListener(listener) } }
    }
    @Test fun settingsHideAfterEightSecondsOfPlaybackButRemainWhilePaused() = withFixture { fixture ->
        showPanel()
        val shownAt = SystemClock.elapsedRealtime()
        assertTrue("Playing overlay must hide automatically", device.wait(Until.gone(By.desc("播放设置面板")), 15000))
        val elapsed = SystemClock.elapsedRealtime() - shownAt
        assertTrue("Auto-hide must not happen substantially before 8 seconds: ${elapsed}ms", elapsed >= 7000)
        device.pressDPadCenter()
        await("confirm pauses") { fixture.onMain { !fixture.player.playWhenReady } }
        assertTrue(device.wait(Until.hasObject(By.desc("播放进度提示")), 3000))
        Thread.sleep(9500)
        assertTrue("Paused progress must remain past the playing timeout", device.hasObject(By.desc("播放进度提示")))
    }
    @Test fun playbackErrorKeepsSettingsAndRetryReachablePastAutoHideTimeout() = withFixture { fixture ->
        evidence("remote-error-before")
        fixture.failPlayback()
        await("actual playback error", 15000) { fixture.onMain { fixture.player.playerError != null } }
        assertTrue(device.wait(Until.hasObject(By.desc("播放进度提示")), 3000))
        evidence("remote-error-after-hud")
        showPanel()
        assertTrue(device.hasObject(By.text("重新解析并重试")))
        Thread.sleep(9500)
        assertTrue("Error settings must remain visible", device.hasObject(By.desc("播放设置面板")))
        focus("重新解析并重试")
    }
    private fun showPanel() {
        device.pressDPadDown()
        assertTrue("First Down must show 剧集切换层", device.wait(Until.hasObject(By.desc("剧集切换层")), 3000))
        device.pressDPadDown()
        assertTrue("Second Down must show 播放设置面板", device.wait(Until.hasObject(By.desc("播放设置面板")), 3000))
    }
    /** Reach buttons only with D-pad navigation; labels alone are not considered proof of reachability. */
    private fun focus(label: String) {
        repeat(5) {
            repeat(10) { if (isFocused(label)) return; device.pressDPadLeft() }
            repeat(16) { if (isFocused(label)) return; device.pressDPadRight() }
            device.pressDPadDown()
        }
        fail("D-pad could not focus '$label'")
    }
    private fun isFocused(label: String): Boolean = try {
        device.findObjects(By.text(label)).any { node ->
            var current: UiObject2? = node; var focused = node.isFocused; var depth = 0
            while (current != null && depth++ < 5) { if (current.isFocused && current.isClickable) focused = true; current = current.parent }
            focused
        }
    } catch (_: RuntimeException) { false }
    private fun key(code: Int) = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
    private fun evidence(name: String) {
        val directory = ApplicationProvider.getApplicationContext<Context>().cacheDir
        device.takeScreenshot(File(directory, "$name.png"))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
    private fun withFixture(test: (PlaybackControlsDeviceTest.Fixture) -> Unit) {
        val fixture = PlaybackControlsDeviceTest.Fixture()
        try { fixture.start(); test(fixture) }
        catch (failure: Throwable) {
            try { evidence("remote-error-failure") } catch (captureFailure: Throwable) { failure.addSuppressed(captureFailure) }
            throw failure
        } finally { fixture.close() }
    }
    private fun await(description: String, timeout: Long = 10000, condition: () -> Boolean) { val deadline = SystemClock.elapsedRealtime() + timeout; while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25); assertTrue("Timed out waiting for $description", condition()) }
}
