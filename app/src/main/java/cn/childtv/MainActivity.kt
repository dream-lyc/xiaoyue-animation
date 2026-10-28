package cn.childtv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.childtv.core.*
import java.net.NetworkInterface
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject

private data class TvSnapshot(
    val library: List<JSONObject> = emptyList(),
    val watch: List<JSONObject> = emptyList(),
    val tags: List<JSONObject> = emptyList(),
    val progress: Map<String, Long> = emptyMap(),
    val policy: JSONObject? = null,
    val statistics: JSONObject? = null,
    val calendar: JSONObject? = null,
    val day: JSONObject? = null,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = TvMint,
                        background = TvBackground,
                        surface = TvPanel,
                        onBackground = TvText,
                        onSurface = TvText,
                        onPrimary = TvBackground,
                    )
            ) {
                Surface(Modifier.fillMaxSize(), color = TvBackground, contentColor = TvText) {
                    ChildTv()
                }
            }
        }
    }

    @Composable
    private fun ChildTv() {
        val scope = rememberCoroutineScope()
        var graph by remember { mutableStateOf<AppGraph?>(null) }
        var initialError by remember { mutableStateOf("") }
        var startup by remember { mutableIntStateOf(0) }
        var snapshot by remember { mutableStateOf(TvSnapshot()) }
        var page by rememberSaveable { mutableStateOf("首页") }
        var selectedLibrary by rememberSaveable { mutableStateOf<String?>(null) }
        var selectedSeriesKey by rememberSaveable { mutableStateOf<String?>(null) }
        var lastEpisodeFocus by rememberSaveable { mutableStateOf<String?>(null) }
        var lastSeriesFocus by rememberSaveable { mutableStateOf<String?>(null) }
        var season by rememberSaveable { mutableIntStateOf(1) }
        var selectedDate by remember { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Shanghai"))) }
        var calendarMonth by remember { mutableStateOf(YearMonth.from(selectedDate)) }
        var tagSelection by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
        var statsDimension by remember { mutableStateOf("series") }
        var statsPeriod by remember { mutableIntStateOf(7) }
        var message by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf<JSONObject?>(null) }
        var playQueue by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var queueIndex by remember { mutableIntStateOf(0) }
        var lockedEpisode by remember { mutableStateOf<JSONObject?>(null) }
        var parentCurrent by remember { mutableStateOf<PlayingEpisode?>(null) }
        var parentEpisode by remember { mutableStateOf<JSONObject?>(null) }
        var extraEpisode by remember { mutableStateOf<JSONObject?>(null) }
        var challengeAction by remember { mutableStateOf(ParentAction.CHANGE_TODAY) }
        var challenge by remember { mutableStateOf<ParentChallenge?>(null) }
        var answer by remember { mutableStateOf("") }
        var grant by remember { mutableStateOf<String?>(null) }
        var minutes by remember { mutableStateOf("45") }
        var password by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf(false) }
        var fromPrompt by remember { mutableStateOf(false) }
        val firstFocus = remember { FocusRequester() }
        suspend fun refresh() {
            val g = graph ?: return
            val loaded =
                withContext(Dispatchers.IO) {
                    val today = g.policy.today()
                    val query = mutableMapOf("dimension" to statsDimension)
                    if (statsPeriod > 0) {
                        query["from"] = today.minusDays(statsPeriod.toLong() - 1).toString()
                        query["to"] = today.toString()
                    }
                    TvSnapshot(
                        g.store.list("library"),
                        g.store.list("watch"),
                        g.store.list("tags"),
                        g.store.list("progress").associate {
                            it.getString("id") to it.optLong("positionMs")
                        },
                        g.api.policy(),
                        g.api.route("GET", "analytics", JSONObject(), query) as JSONObject,
                        g.api.route(
                            "GET",
                            "analytics",
                            JSONObject(),
                            mapOf(
                                "from" to calendarMonth.atDay(1).toString(),
                                "to" to calendarMonth.atEndOfMonth().toString(),
                            ),
                        ) as JSONObject,
                        g.api.route(
                            "GET",
                            "analytics/day",
                            JSONObject(),
                            mapOf("date" to selectedDate.toString()),
                        ) as JSONObject,
                    )
                }
            snapshot = loaded
            val needsPrompt = withContext(Dispatchers.IO) { g.policy.needsPrompt() }
            if (
                page == "首页" && playing == null && !fromPrompt && challenge == null && grant == null
            )
                prompt = needsPrompt
        }
        fun reload() {
            scope.launch {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = "本地数据读取失败，已有数据已保留，请重试。"
                }
            }
        }
        fun beginChallenge(action: ParentAction) {
            val g = graph ?: return
            try {
                g.parent.cancel(grant)
                grant = null
                challengeAction = action
                challenge = g.parent.challenge(action)
                answer = ""
                message = ""
            } catch (e: IllegalStateException) {
                message = e.message ?: "请稍后再次验证"
            }
        }
        fun cancelParent() {
            graph?.parent?.cancel(grant)
            grant = null
            challenge = null
            answer = ""
            password = ""
            if (fromPrompt) {
                fromPrompt = false
                prompt = true
                page = "首页"
            } else if (parentEpisode != null) {
                playing = parentEpisode
                parentEpisode = null
                parentCurrent = null
            } else page = "首页"
        }
        fun play(episode: JSONObject, queue: List<JSONObject>, automatic: Boolean = false) {
            val g = graph ?: return
            lastEpisodeFocus = episode.getString("id")
            scope.launch {
                busy = true
                message = ""
                try {
                    val current =
                        withContext(Dispatchers.IO) {
                            g.store.get("library", episode.getString("id"))
                        }
                    if (current == null || !current.optBoolean("available")) {
                        message = "此集资源暂不可用。请从电脑管理端检查资源或关联其他版本。"
                        playing = null
                        return@launch
                    }
                    val id = current.getString("id")
                    val allowed =
                        withContext(Dispatchers.IO) {
                            val progress = g.store.get("progress", id)
                            val pos =
                                if (progress?.optBoolean("completed") == true) 0
                                else progress?.optLong("positionMs") ?: 0
                            g.policy.canStart(id, pos)
                        }
                    if (!allowed) {
                        playing = null
                        lockedEpisode = current
                        page = "休息"
                        refresh()
                        return@launch
                    }
                    val resolved = withContext(Dispatchers.IO) { g.sources.resolve(current) }
                    playQueue = queue
                    queueIndex = queue.indexOfFirst { it.getString("id") == id }.coerceAtLeast(0)
                    playing = resolved
                    lockedEpisode = null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    playing = null
                    message = if (automatic) "下一集资源解析失败，请检查连接后重试。" else "获取播放资源失败，请检查连接后重试。"
                } finally {
                    busy = false
                }
            }
        }
        LaunchedEffect(startup) {
            initialError = ""
            try {
                val g = withContext(Dispatchers.IO) { AppGraph.get(this@MainActivity) }
                graph = g
                val state =
                    withContext(Dispatchers.IO) { g.policy.needsPrompt() to g.policy.today() }
                prompt = state.first
                selectedDate = state.second
                calendarMonth = YearMonth.from(state.second)
                refresh()
                if (
                    withContext(Dispatchers.IO) {
                        g.store.getSetting("adminEnabled", "false") == "true"
                    }
                )
                    startForegroundService(Intent(this@MainActivity, AdminService::class.java))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                initialError = "本地数据加载或升级失败。原有数据已保留，应用不会自动重建空片库。请重试或联系维护者检查诊断。"
            }
        }
        LaunchedEffect(graph, page, selectedDate, calendarMonth, statsDimension, statsPeriod) {
            if (graph != null)
                while (isActive) {
                    try {
                        refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        message = "数据刷新失败，请检查电视存储后重试。"
                    }
                    delay(5000)
                }
        }
        LaunchedEffect(graph, prompt) {
            if (graph != null && !prompt) runCatching { firstFocus.requestFocus() }
        }
        BackHandler(
            enabled =
                playing != null ||
                    page != "首页" ||
                    selectedSeriesKey != null ||
                    challenge != null ||
                    grant != null
        ) {
            when {
                playing != null -> {
                    playing = null
                    reload()
                }
                page == "家长设置" &&
                    (challenge != null || grant != null || parentEpisode != null || fromPrompt) ->
                    cancelParent()
                selectedSeriesKey != null -> selectedSeriesKey = null
                else -> {
                    page = "首页"
                    lockedEpisode = null
                }
            }
        }
        if (initialError.isNotBlank()) {
            Column(
                Modifier.fillMaxSize().padding(40.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                TvEmpty("本地数据暂时无法打开", initialError, "重试加载", { startup++ })
                TvAction("退出应用", { finish() })
            }
            return
        }
        val g = graph
        if (g == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在打开家庭影院…", fontSize = 25.sp, color = TvMint)
            }
            return
        }
        val episode = playing
        if (episode != null) {
            key(
                episode.getString("id"),
                episode.optString("selectedAssetId"),
                episode.optString("playbackAttempt"),
            ) {
                Playback(
                    episode,
                    g,
                    onSwitch = { playing = it },
                    onExit = {
                        playing = null
                        reload()
                    },
                    onParent = { current ->
                        parentCurrent = current
                        parentEpisode = playing
                        playing = null
                        page = "家长设置"
                        grant = null
                        challenge = null
                        reload()
                    },
                    previousTitle = playQueue.getOrNull(queueIndex - 1)?.optString("title"),
                    nextTitle = playQueue.getOrNull(queueIndex + 1)?.optString("title"),
                    onPrevious = playQueue.getOrNull(queueIndex - 1)?.let { previous ->
                        { play(previous, playQueue) }
                    },
                    onNextRequested = playQueue.getOrNull(queueIndex + 1)?.let { next ->
                        { play(next, playQueue) }
                    },
                    onNext = {
                        val next = playQueue.getOrNull(queueIndex + 1)
                        if (next != null) play(next, playQueue, true)
                        else {
                            playing = null
                            message = "这一组故事看完啦。"
                            reload()
                        }
                    },
                )
            }
            return
        }
        val series = TvPresentation.series(snapshot.library)
        val activeSeries = series.firstOrNull { it.key == selectedSeriesKey }
        CompositionLocalProvider(LocalTvArtwork provides g.artwork) {
            Column(
                Modifier.fillMaxSize()
                    .background(TvBackground)
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("♧  小约动画", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TvText)
                    Spacer(Modifier.weight(1f))
                    TvPill(
                        snapshot.policy?.let {
                            TvPresentation.quota(
                                it.optInt("todayMinutes"),
                                it.optLong("watchedSeconds"),
                            )
                        } ?: "正在读取今日安排"
                    )
                    TvAction(
                        "家长设置",
                        {
                            parentCurrent = null
                            parentEpisode = null
                            page = "家长设置"
                            selectedSeriesKey = null
                            message = ""
                        },
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(listOf("首页", "动画片库", "按标签看", "最近看过", "观看日历", "观看统计")) { name ->
                        TvAction(
                            name,
                            {
                                g.parent.cancel(grant)
                                grant = null
                                challenge = null
                                password = ""
                                parentCurrent = null
                                parentEpisode = null
                                page = if (fromPrompt) "首页" else name
                                if (fromPrompt) {
                                    fromPrompt = false
                                    prompt = true
                                }
                                selectedSeriesKey = null
                                message = ""
                            },
                            modifier =
                                if (name == "首页") Modifier.focusRequester(firstFocus) else Modifier,
                            selected = page == name,
                        )
                    }
                }
                if (message.isNotBlank())
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF453C2B), RoundedCornerShape(8.dp))
                            .padding(11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            color = TvWarm,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TvAction("重试刷新", { reload() })
                    }
                if (busy) Text("正在连接故事…", fontSize = 16.sp, color = TvMint)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        page == "家长设置" ->
                            ParentSettings(
                                g,
                                snapshot.policy,
                                parentCurrent,
                                parentEpisode,
                                challenge,
                                challengeAction,
                                grant,
                                answer,
                                { answer = it },
                                minutes,
                                { minutes = it },
                                password,
                                { password = it },
                                extraEpisode,
                                onChallenge = { beginChallenge(it) },
                                onVerify = {
                                    val result =
                                        answer.toIntOrNull()?.let {
                                            g.parent.answer(challenge?.id ?: "", it)
                                        }
                                    grant = result
                                    challenge = null
                                    answer = ""
                                    if (result == null) message = "答案错误或挑战过期，请重新验证。"
                                    else
                                        minutes =
                                            (snapshot.policy?.optInt("todayMinutes") ?: 45)
                                                .toString()
                                },
                                onCancel = { cancelParent() },
                                onSave = { restore ->
                                    scope.launch {
                                        val value = if (restore) null else minutes.toLongOrNull()
                                        if (!restore && (value == null || value !in 0..1440)) {
                                            message = "请输入 0—1440 分钟"
                                            return@launch
                                        }
                                        try {
                                            withContext(Dispatchers.IO) {
                                                g.store.transaction {
                                                    require(
                                                        g.parent.consume(
                                                            grant ?: "",
                                                            ParentAction.CHANGE_TODAY,
                                                        )
                                                    )
                                                    g.policy.changeToday(
                                                        value?.times(60),
                                                        parentCurrent,
                                                    )
                                                    g.policy.promptHandled()
                                                }
                                            }
                                            grant = null
                                            fromPrompt = false
                                            message = "今日总额度已保存，实际已看时长保持不变。"
                                            refresh()
                                        } catch (_: Exception) {
                                            grant = null
                                            message = "授权已过期，请重新验证。"
                                        }
                                    }
                                },
                                onExtra = {
                                    val extra = extraEpisode
                                    if (extra != null)
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    g.store.transaction {
                                                        require(
                                                            g.parent.consume(
                                                                grant ?: "",
                                                                ParentAction.EXTRA_EPISODE,
                                                            )
                                                        )
                                                        val progress =
                                                            g.store.get(
                                                                "progress",
                                                                extra.getString("id"),
                                                            )
                                                        val position =
                                                            if (
                                                                progress?.optBoolean("completed") ==
                                                                    true
                                                            )
                                                                0
                                                            else
                                                                progress?.optLong("positionMs") ?: 0
                                                        g.policy.allowExtra(
                                                            extra.getString("id"),
                                                            position,
                                                        )
                                                    }
                                                }
                                                grant = null
                                                extraEpisode = null
                                                play(extra, listOf(extra))
                                            } catch (_: Exception) {
                                                grant = null
                                                message = "额外一集授权失败，请重新验证。"
                                            }
                                        }
                                },
                                onEnable = {
                                    scope.launch {
                                        try {
                                            require(password.length >= 12)
                                            withContext(Dispatchers.IO) {
                                                g.store.transaction {
                                                    require(
                                                        g.parent.consume(
                                                            grant ?: "",
                                                            ParentAction.MANAGE_ADMIN,
                                                        )
                                                    )
                                                    g.security.revokeAll()
                                                    g.store.setSetting(
                                                        "adminPassword",
                                                        g.security.hashPassword(password),
                                                    )
                                                    g.store.setSetting("adminEnabled", "true")
                                                }
                                            }
                                            grant = null
                                            password = ""
                                            startForegroundService(
                                                Intent(this@MainActivity, AdminService::class.java)
                                            )
                                            message = "正在启动管理服务，请确认服务状态后再从电脑连接。"
                                        } catch (_: Exception) {
                                            grant = null
                                            message = "启用失败，请检查密码长度并重新验证管理权限。"
                                        }
                                    }
                                },
                                onStop = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                g.store.transaction {
                                                    require(
                                                        g.parent.consume(
                                                            grant ?: "",
                                                            ParentAction.MANAGE_ADMIN,
                                                        )
                                                    )
                                                    g.security.revokeAll()
                                                    g.store.setSetting("adminEnabled", "false")
                                                }
                                            }
                                            grant = null
                                            stopService(
                                                Intent(this@MainActivity, AdminService::class.java)
                                            )
                                            message = "电脑管理服务已停止"
                                        } catch (_: Exception) {
                                            grant = null
                                            message = "请先完成管理服务验证。"
                                        }
                                    }
                                },
                                address = address(),
                            )
                        page == "休息" ->
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                TvEmpty(
                                    "今天的故事，先到这里",
                                    "今天已看 ${TvPresentation.duration(snapshot.policy?.optLong("watchedSeconds")?:0)}。休息一下，明天继续探索。",
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TvAction("返回首页", { page = "首页" }, primary = true)
                                    TvAction(
                                        "家长修改今日安排",
                                        {
                                            page = "家长设置"
                                            beginChallenge(ParentAction.CHANGE_TODAY)
                                        },
                                    )
                                    if (lockedEpisode != null)
                                        TvAction(
                                            "家长允许再看这一集",
                                            {
                                                extraEpisode = lockedEpisode
                                                page = "家长设置"
                                                beginChallenge(ParentAction.EXTRA_EPISODE)
                                            },
                                        )
                                }
                            }
                        activeSeries != null ->
                            TvSeriesDetail(
                                activeSeries,
                                if (season in activeSeries.seasons) season
                                else activeSeries.seasons.first(),
                                { season = it },
                                snapshot.progress,
                                { e, q -> play(e, q) },
                                { selectedSeriesKey = null },
                                focusEpisodeId = lastEpisodeFocus,
                            )
                        page == "首页" ->
                            TvHome(
                                series,
                                snapshot.watch
                                    .sortedByDescending { it.optString("startedAt") }
                                    .firstNotNullOfOrNull { watch ->
                                        snapshot.library.firstOrNull {
                                            it.getString("id") == watch.optString("episodeId") &&
                                                (snapshot.progress[it.getString("id")] ?: 0) > 0
                                        }
                                    },
                                { e, q -> play(e, q) },
                                {
                                    lastSeriesFocus = it
                                    selectedSeriesKey = it
                                    season =
                                        series.first { it.key == selectedSeriesKey }.seasons.first()
                                },
                                { page = "动画片库" },
                                { page = "家长设置" },
                                focusSeriesKey = lastSeriesFocus,
                            )
                        page == "动画片库" ->
                            TvLibrary(
                                series,
                                selectedLibrary,
                                { selectedLibrary = it },
                                {
                                    lastSeriesFocus = it
                                    selectedSeriesKey = it
                                    season = series.first { s -> s.key == it }.seasons.first()
                                },
                                focusSeriesKey = lastSeriesFocus,
                            )
                        page == "按标签看" ->
                            TvTags(
                                snapshot.tags,
                                tagSelection,
                                { k, v ->
                                    val values = tagSelection[k].orEmpty()
                                    tagSelection =
                                        tagSelection +
                                            (k to if (v in values) values - v else values + v)
                                },
                                TvPresentation.filter(series.flatMap { it.episodes }, tagSelection),
                                snapshot.progress,
                                { e, q -> play(e, q) },
                                focusEpisodeId = lastEpisodeFocus,
                            )
                        page == "最近看过" ->
                            TvHistory(snapshot.watch, snapshot.library, { e, q -> play(e, q) })
                        page == "观看日历" ->
                            TvCalendar(
                                calendarMonth,
                                selectedDate,
                                snapshot.calendar
                                    ?.optJSONArray("days")
                                    ?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                                    .orEmpty(),
                                snapshot.day?.takeIf {
                                    it.optString("date") == selectedDate.toString()
                                },
                                {
                                    calendarMonth = it
                                    selectedDate = it.atDay(1)
                                },
                                { selectedDate = it },
                            )
                        page == "观看统计" ->
                            TvStatistics(
                                snapshot.statistics,
                                statsDimension,
                                { statsDimension = it },
                                statsPeriod,
                                { statsPeriod = it },
                            )
                    }
                }
                Text("⊙  方向键选择  ·  确认键进入  ·  返回键返回  ·  数据保存在本电视", color = TvMuted, fontSize = 11.sp)
            }
            if (prompt)
                AlertDialog(
                    onDismissRequest = {
                        scope.launch {
                            withContext(Dispatchers.IO) { g.policy.promptHandled() }
                            prompt = false
                        }
                    },
                    containerColor = TvPanel,
                    title = { Text("今天也一起快乐观看", color = TvText, fontSize = 25.sp) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "每日默认 ${snapshot.policy?.optInt("defaultMinutes")?:45} 分钟",
                                color = TvMint,
                                fontSize = 29.sp,
                            )
                            Text(
                                "到时可以看完当前这一集，然后休息。你可以直接开始，也可以请家长设置今天的总额度。",
                                color = TvMuted,
                                fontSize = 17.sp,
                            )
                        }
                    },
                    confirmButton = {
                        TvAction(
                            "跳过设置，开始吧",
                            {
                                scope.launch {
                                    withContext(Dispatchers.IO) { g.policy.promptHandled() }
                                    prompt = false
                                    reload()
                                }
                            },
                            primary = true,
                        )
                    },
                    dismissButton = {
                        TvAction(
                            "家长设置今天",
                            {
                                prompt = false
                                fromPrompt = true
                                page = "家长设置"
                                beginChallenge(ParentAction.CHANGE_TODAY)
                            },
                        )
                    },
                )
        }
    }

    private fun address(): String =
        runCatching {
                NetworkInterface.getNetworkInterfaces()
                    .toList()
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.hostAddress
                    ?.let { "http://$it:8080" }
            }
            .getOrNull() ?: "尚未连接局域网"
}
