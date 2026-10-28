package cn.childtv

import android.os.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cn.childtv.core.*
import org.json.JSONObject
import java.time.*
import java.util.UUID
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable fun Playback(episode: JSONObject, graph: AppGraph, onExit: () -> Unit, onSwitch: (JSONObject) -> Unit, onParent: (PlayingEpisode?) -> Unit, onNext: (() -> Unit)? = null, previousTitle: String? = null, nextTitle: String? = null, onPrevious: (() -> Unit)? = null, onNextRequested: (() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val player = remember {
        val headers = episode.optJSONObject("playbackHeaders")?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } } ?: emptyMap()
        val dataSource: androidx.media3.datasource.DataSource.Factory = if (episode.optString("playbackUrl").startsWith("smb:")) androidx.media3.datasource.DataSource.Factory { SmbDataSource(graph.sources, episode) } else androidx.media3.datasource.DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSource)).build()
    }
    var status by remember { mutableStateOf("正在连接资源…") }
    var failure by remember { mutableStateOf(false) }
    val id = episode.getString("id")
    val timezone = episode.optString("familyTimezone", "Asia/Shanghai")
    val writer = remember { Executors.newSingleThreadExecutor() }
    val sessionEpoch = remember { graph.playbackEpoch.get() }
    val active = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val checkpointNow = remember { java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null) }
    DisposableEffect(player) {
        val clock = PlaybackClock(UUID.randomUUID().toString(), id, episode.optString("libraryId", episode.optString("libraryName")), episode.optString("seriesId", episode.optString("seriesTitle")), ZoneId.of(timezone))
        val handler = Handler(Looper.getMainLooper())
        var kind = WatchKind.NORMAL
        val stopForRestore: () -> Unit = { player.pause(); status = "片库已恢复，当前播放已停止"; onExit() }
        graph.stopPlayback = stopForRestore
        fun checkpoint() {
            val position = player.currentPosition.coerceAtLeast(0)
            val duration = player.duration
            val completed = player.playbackState == Player.STATE_ENDED
            val current = if (duration > position && duration > 0) PlayingEpisode(id, position, duration) else null
            val intervals = clock.update(SystemClock.elapsedRealtime(), Instant.now(), player.isPlaying, kind)
            writer.execute { graph.store.transaction {
                if (graph.playbackEpoch.get() != sessionEpoch) return@transaction
                intervals.forEach { event ->
                    graph.policy.checkpoint(JSONObject().put("id", event.id).put("episodeId", id).put("libraryId", event.libraryId).put("libraryName", episode.optString("libraryName")).put("seriesId", event.seriesId).put("seriesTitle", episode.optString("seriesTitle")).put("title", episode.optString("title")).put("startedAt", event.startUtc.toString()).put("date", event.day.toString()).put("seconds", event.durationMillis / 1000).put("durationMillis", event.durationMillis).put("kind", event.kind.name.lowercase()), current)
                }
                graph.store.put("progress", JSONObject().put("id", id).put("positionMs", position).put("completed", completed))
                val state = graph.policy.state()
                handler.post { if (!active.get() || graph.playbackEpoch.get() != sessionEpoch) return@post; if (state.mode == PolicyMode.LOCKED) { player.pause(); status = "今天的额度已用完，请休息或进入家长设置" }; kind = if (state.extraEpisodeId == id) WatchKind.PARENT_EXTRA else if (state.finishing?.episodeId == id) WatchKind.GRACE else WatchKind.NORMAL; if (kind == WatchKind.GRACE) status = "今天的额度已用完，看完这一集就休息" }
            } }
        }
        checkpointNow.set { checkpoint() }
        val ticker = object : Runnable { override fun run() { checkpoint(); handler.postDelayed(this, 5000) } }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { checkpoint(); if (isPlaying && !failure) status = "" }
            override fun onPlaybackStateChanged(state: Int) {
                checkpoint()
                if (state == Player.STATE_BUFFERING) status = "正在缓冲，等待时间不计入观看"
                if (state == Player.STATE_ENDED) { writer.execute { graph.store.transaction { if (graph.playbackEpoch.get() == sessionEpoch) { graph.policy.finish(id); handler.post { if (active.get() && graph.playbackEpoch.get() == sessionEpoch) onNext?.invoke() } } } }; status = "本集播放完成，请返回片库" }
            }
            override fun onPlayerError(error: PlaybackException) { checkpoint(); failure = true; status = "播放失败（${error.errorCodeName}）。可重试；解码不支持时请选择同集其他资源。" }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && kind in setOf(WatchKind.GRACE, WatchKind.PARENT_EXTRA) && newPosition.positionMs < oldPosition.positionMs) { player.seekTo(oldPosition.positionMs); status = "收尾期间只允许向前播放"; return }
                if (reason == Player.DISCONTINUITY_REASON_SEEK) writer.execute {
                    if (!graph.policy.canStart(id, newPosition.positionMs)) handler.post { player.seekTo(oldPosition.positionMs.coerceAtLeast(0)); status = "收尾期间只允许向前播放" }
                }
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { player.pause(); checkpoint() } }
        player.addListener(listener); lifecycle.lifecycle.addObserver(observer)
        writer.execute {
            val savedProgress = graph.store.get("progress", id)
            val position = if (episode.has("resumePositionMs")) episode.getLong("resumePositionMs") else if (savedProgress?.optBoolean("completed") == true) 0 else savedProgress?.optLong("positionMs") ?: 0
            val initialState = graph.policy.state()
            val allowed = graph.policy.canStart(id, position)
            handler.post { if (!active.get() || graph.playbackEpoch.get() != sessionEpoch) return@post; if (!allowed) { status = "当前剧集已不符合今日观看策略，请返回片库"; return@post }; kind = if (initialState.extraEpisodeId == id) WatchKind.PARENT_EXTRA else if (initialState.finishing?.episodeId == id) WatchKind.GRACE else WatchKind.NORMAL; val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(episode.optLong("clipStartMs")); if (episode.has("clipEndMs")) clipping.setEndPositionMs(episode.getLong("clipEndMs")); player.setMediaItem(MediaItem.Builder().setUri(episode.getString("playbackUrl")).setClippingConfiguration(clipping.build()).build()); player.seekTo(position); player.prepare(); player.playWhenReady = true; handler.post(ticker) }
        }
        onDispose { checkpointNow.set(null); active.set(false); if (graph.stopPlayback === stopForRestore) graph.stopPlayback = null; handler.removeCallbacks(ticker); player.pause(); checkpoint(); player.removeListener(listener); lifecycle.lifecycle.removeObserver(observer); player.release(); writer.shutdown() }
    }
    val resumeGeneration = remember { java.util.concurrent.atomic.AtomicLong(0) }
    val navigationPending = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun pausePlayback() { resumeGeneration.incrementAndGet(); player.pause() }
    fun requestPlayback() {
        if (navigationPending.get()) return
        val request = resumeGeneration.incrementAndGet()
        val position = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0)
        writer.execute {
            val allowed = graph.store.transaction {
                graph.playbackEpoch.get() == sessionEpoch && graph.policy.canStart(id, position)
            }
            Handler(Looper.getMainLooper()).post {
                if (!active.get() || graph.playbackEpoch.get() != sessionEpoch || resumeGeneration.get() != request) return@post
                if (allowed && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(position)
                    player.play()
                } else status = "当前剧集已不符合今日观看策略，请返回片库"
            }
        }
    }
    fun requestEpisode(callback: (() -> Unit)?) {
        if (callback == null || !navigationPending.compareAndSet(false, true)) return
        pausePlayback()
        // Commit the final partial interval before MainActivity checks the next episode's quota.
        checkpointNow.get()?.invoke()
        writer.execute {
            Handler(Looper.getMainLooper()).post {
                if (active.get() && graph.playbackEpoch.get() == sessionEpoch) callback()
            }
        }
    }
    PlaybackControls(
        player = player,
        title = episode.optString("title", "正在观看"),
        status = status,
        failure = failure,
        previousTitle = previousTitle,
        nextTitle = nextTitle,
        onPrevious = onPrevious?.let { { requestEpisode(it) } },
        onNextRequested = onNextRequested?.let { { requestEpisode(it) } },
        onPlay = { requestPlayback() },
        onPause = { pausePlayback() },
        onExit = { pausePlayback(); onExit() },
        onParent = { pausePlayback(); onParent(if (player.duration > player.currentPosition) PlayingEpisode(id, player.currentPosition, player.duration) else null) },
        onSource = source@ { next ->
            if (navigationPending.get()) return@source
            pausePlayback()
            val position = player.currentPosition
            writer.execute {
                try {
                    val resolved = graph.sources.refreshPlayback(episode, position, next)
                    Handler(Looper.getMainLooper()).post { if (active.get() && graph.playbackEpoch.get() == sessionEpoch && !navigationPending.get()) onSwitch(resolved) }
                } catch (_: Exception) {
                    Handler(Looper.getMainLooper()).post { status = if (next) "此集没有可用的其他资源版本，请在电脑端检查映射" else "重新解析地址失败，请检查资源源连接" }
                }
            }
        },
    )
}
