package cn.childtv

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class PlaybackLayer {
    HIDDEN,
    HUD,
    EPISODES,
    SETTINGS,
}

/** Full-size video with progressively disclosed text controls; transport never opens a menu. */
@Suppress("UNUSED_PARAMETER")
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun PlaybackControls(
    player: Player,
    title: String,
    status: String,
    failure: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
    onParent: () -> Unit,
    onSource: (Boolean) -> Unit,
    previousTitle: String? = null,
    nextTitle: String? = null,
    onPrevious: (() -> Unit)? = null,
    onNextRequested: (() -> Unit)? = null,
) {
    var layer by remember { mutableStateOf(PlaybackLayer.HIDDEN) }
    var speedExpanded by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    val videoFocus = remember { FocusRequester() }
    val previousFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val speedFocus = remember { FocusRequester() }
    val optionFocus = remember { FocusRequester() }
    val consumedKeys = remember { mutableSetOf<Int>() }
    val previousEnabled = previousTitle != null && onPrevious != null
    val nextEnabled = nextTitle != null && onNextRequested != null

    fun isMenu() = layer == PlaybackLayer.EPISODES || layer == PlaybackLayer.SETTINGS
    fun revealHud() {
        if (!isMenu()) layer = PlaybackLayer.HUD
    }
    fun hide() {
        layer = PlaybackLayer.HIDDEN
        speedExpanded = false
    }
    fun toggle() {
        if (player.playWhenReady) onPause() else onPlay()
        revealHud()
    }
    fun seek(delta: Long) {
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition.coerceAtLeast(0) + delta).coerceIn(0, end))
        revealHud()
    }
    fun down() {
        speedExpanded = false
        layer =
            when (layer) {
                PlaybackLayer.HIDDEN,
                PlaybackLayer.HUD -> PlaybackLayer.EPISODES
                PlaybackLayer.EPISODES,
                PlaybackLayer.SETTINGS -> PlaybackLayer.SETTINGS
            }
    }
    fun up() {
        if (speedExpanded) {
            speedExpanded = false
            return
        }
        layer =
            when (layer) {
                PlaybackLayer.SETTINGS -> PlaybackLayer.EPISODES
                PlaybackLayer.EPISODES -> PlaybackLayer.HUD
                PlaybackLayer.HUD -> PlaybackLayer.HIDDEN
                PlaybackLayer.HIDDEN -> PlaybackLayer.HUD
            }
    }
    fun dismissOrExit() {
        interaction++
        if (speedExpanded) {
            speedExpanded = false
            return
        }
        when (layer) {
            PlaybackLayer.SETTINGS -> layer = PlaybackLayer.EPISODES
            PlaybackLayer.EPISODES -> layer = PlaybackLayer.HUD
            PlaybackLayer.HUD -> hide()
            PlaybackLayer.HIDDEN -> onExit()
        }
    }
    fun handleKey(event: AndroidKeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == AndroidKeyEvent.ACTION_UP) return consumedKeys.remove(code)
        if (event.action != AndroidKeyEvent.ACTION_DOWN) return false
        interaction++
        if (event.repeatCount > 0 && code in consumedKeys) return true
        val handled =
            when (code) {
                AndroidKeyEvent.KEYCODE_BACK -> {
                    dismissOrExit()
                    true
                }
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                AndroidKeyEvent.KEYCODE_HEADSETHOOK -> {
                    toggle()
                    true
                }
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                    onPlay()
                    revealHud()
                    true
                }
                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
                AndroidKeyEvent.KEYCODE_MEDIA_STOP -> {
                    onPause()
                    revealHud()
                    true
                }
                AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seek(10_000)
                    true
                }
                AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seek(-10_000)
                    true
                }
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    down()
                    true
                }
                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                    up()
                    true
                }
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                AndroidKeyEvent.KEYCODE_SPACE ->
                    if (!isMenu()) {
                        toggle()
                        true
                    } else false
                AndroidKeyEvent.KEYCODE_DPAD_LEFT ->
                    if (!isMenu()) {
                        seek(-10_000)
                        true
                    } else false
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (!isMenu()) {
                        seek(10_000)
                        true
                    } else false
                else -> false
            }
        if (handled) consumedKeys += code
        return handled
    }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                    playWhenReady = ready
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    LaunchedEffect(failure) {
        if (failure && layer == PlaybackLayer.HIDDEN) {
            speedExpanded = false
            layer = PlaybackLayer.HUD
        }
    }
    LaunchedEffect(layer, isPlaying, failure, interaction) {
        if (layer != PlaybackLayer.HIDDEN && isPlaying && !failure) {
            delay(8000)
            hide()
        }
    }
    LaunchedEffect(layer, speedExpanded, previousEnabled, nextEnabled) {
        withFrameNanos {}
        when {
            layer == PlaybackLayer.SETTINGS && speedExpanded -> optionFocus.requestFocus()
            layer == PlaybackLayer.SETTINGS -> speedFocus.requestFocus()
            layer == PlaybackLayer.EPISODES && previousEnabled -> previousFocus.requestFocus()
            layer == PlaybackLayer.EPISODES && nextEnabled -> nextFocus.requestFocus()
            else -> videoFocus.requestFocus()
        }
    }
    BackHandler { dismissOrExit() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent {
            handleKey(it.nativeKeyEvent)
        }
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Keep the focusable video target beside the overlay. A focusable ancestor merges
        // descendant semantics, which can hide disabled actions and retain stale layer nodes.
        Box(
            Modifier.matchParentSize()
                .semantics { contentDescription = "播放画面" }
                .focusRequester(videoFocus)
                .focusProperties {
                    canFocus =
                        !isMenu() ||
                            (layer == PlaybackLayer.EPISODES && !previousEnabled && !nextEnabled)
                }
                .focusable()
        )
        key(layer) {
            if (layer != PlaybackLayer.HIDDEN || status.isNotEmpty())
                Column(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                .45f to Color.Black.copy(alpha = .35f),
                                1f to Color.Black.copy(alpha = .9f),
                            )
                        )
                        .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 22.dp)
                        .semantics {
                            when (layer) {
                                PlaybackLayer.HUD -> contentDescription = "播放进度提示"
                                PlaybackLayer.EPISODES -> contentDescription = "剧集切换层"
                                PlaybackLayer.SETTINGS -> contentDescription = "播放设置面板"
                                PlaybackLayer.HIDDEN -> Unit
                            }
                        }
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (layer != PlaybackLayer.HIDDEN) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                title,
                                Modifier.weight(1f),
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!playWhenReady)
                                Text(
                                    "已暂停",
                                    Modifier.padding(horizontal = 18.dp),
                                    color = Color.White.copy(alpha = .7f),
                                    fontSize = 13.sp,
                                )
                            Text(
                                "${time(position)} / ${time(duration)}",
                                color = Color.White.copy(alpha = .75f),
                                fontSize = 13.sp,
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .height(2.dp)
                                .background(Color.White.copy(alpha = .2f))
                        ) {
                            val fraction =
                                if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f)
                                else 0f
                            Box(
                                Modifier.fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .background(Color.White.copy(alpha = .9f))
                            )
                        }
                    }
                    if (status.isNotEmpty())
                        Text(status, color = Color.White.copy(alpha = .85f), fontSize = 14.sp)
                    when (layer) {
                        PlaybackLayer.EPISODES -> {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(48.dp),
                            ) {
                                PlaybackTextAction(
                                    "上一集",
                                    {
                                        interaction++
                                        onPrevious?.invoke()
                                    },
                                    Modifier.weight(1f).focusRequester(previousFocus),
                                    detail = previousTitle ?: "没有上一集",
                                    enabled = previousEnabled,
                                )
                                PlaybackTextAction(
                                    "下一集",
                                    {
                                        interaction++
                                        onNextRequested?.invoke()
                                    },
                                    Modifier.weight(1f).focusRequester(nextFocus),
                                    detail = nextTitle ?: "没有下一集",
                                    enabled = nextEnabled,
                                )
                            }
                            Text(
                                "↑ 返回播放信息     ↓ 播放设置",
                                color = Color.White.copy(alpha = .45f),
                                fontSize = 11.sp,
                            )
                        }
                        PlaybackLayer.SETTINGS -> {
                            if (speedExpanded)
                                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                    listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEachIndexed { index, value
                                        ->
                                        PlaybackTextAction(
                                            "${speedLabel(value)}×",
                                            {
                                                interaction++
                                                player.setPlaybackSpeed(value)
                                                speed = value
                                                speedExpanded = false
                                            },
                                            if (index == 0) Modifier.focusRequester(optionFocus)
                                            else Modifier,
                                        )
                                    }
                                }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlaybackTextAction(
                                    "倍速",
                                    {
                                        interaction++
                                        speedExpanded = !speedExpanded
                                    },
                                    Modifier.focusRequester(speedFocus),
                                    value = "${speedLabel(speed)}×",
                                )
                                PlaybackTextAction(
                                    "同集换源",
                                    {
                                        interaction++
                                        onSource(true)
                                    },
                                )
                                if (failure)
                                    PlaybackTextAction(
                                        "重新解析并重试",
                                        {
                                            interaction++
                                            onSource(false)
                                        },
                                        small = true,
                                    )
                            }
                            Text("↑ 剧集切换", color = Color.White.copy(alpha = .45f), fontSize = 11.sp)
                        }
                        else -> Unit
                    }
                }
        }
    }
}

@Composable
private fun PlaybackTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                label,
                color = Color.White.copy(alpha = if (!enabled) .3f else if (focused) 1f else .72f),
                fontSize = if (small) 14.sp else 19.sp,
                fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            )
            if (value != null) Text(value, color = Color.White.copy(alpha = .55f), fontSize = 14.sp)
        }
        if (detail != null)
            Text(
                detail,
                color =
                    Color.White.copy(alpha = if (!enabled) .25f else if (focused) .95f else .6f),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        Box(
            Modifier.width(36.dp)
                .height(2.dp)
                .background(if (focused) Color.White else Color.Transparent)
        )
    }
}

private fun speedLabel(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

private fun time(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
