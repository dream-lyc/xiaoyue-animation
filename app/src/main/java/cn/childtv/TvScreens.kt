package cn.childtv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val TvBackground = Color(0xFF102129)
internal val TvPanel = Color(0xFF1A3039)
internal val TvPanelLight = Color(0xFF233C45)
internal val TvMint = Color(0xFFCBEEA7)
internal val TvText = Color(0xFFF4F8F4)
internal val TvMuted = Color(0xFFA8BEC2)
internal val TvWarm = Color(0xFFE5C886)

/** D-pad accessible control with explicit focus contrast at television distance. */
@Composable
internal fun TvAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val background =
        when {
            focused -> TvMint
            primary -> Color(0xFF37584A)
            selected -> TvPanelLight
            else -> TvPanel
        }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color =
                if (focused) TvBackground
                else if (!enabled) TvMuted.copy(alpha = .5f)
                else if (primary || selected) TvMint else TvText,
            fontSize = 16.sp,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TvPill(text: String, warm: Boolean = false) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (warm) Color(0xFF4D4931) else Color(0xFF263F37))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (warm) TvWarm else TvMint,
        fontSize = 13.sp,
    )
}

@Composable
internal fun TvEmpty(
    title: String,
    detail: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("♧", color = TvMint, fontSize = 42.sp)
        Text(title, fontSize = 25.sp, color = TvText)
        Text(detail, color = TvMuted, fontSize = 16.sp)
        if (action != null) TvAction(action, onAction, primary = true)
    }
}

@Composable
internal fun TvSection(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TvText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.width(15.dp))
            Text(subtitle, color = TvMuted, fontSize = 14.sp)
        }
    }
}

internal val LocalTvArtwork = staticCompositionLocalOf<ArtworkRepository?> { null }

@Composable
private fun Artwork(
    episode: JSONObject?,
    modifier: Modifier = Modifier,
    seriesEpisodes: List<JSONObject>? = null,
) {
    val repository = LocalTvArtwork.current
    var bitmap by
        remember(episode?.optString("id")) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(
        episode?.optString("id"),
        episode?.optString("artworkCacheKey"),
        episode?.optString("artworkUrl"),
        seriesEpisodes?.map {
            it.optString("id") +
                it.optString("artworkCacheKey") +
                it.optString("artworkUrl") +
                it.optBoolean("available")
        },
    ) {
        bitmap =
            withContext(Dispatchers.IO) {
                if (episode == null || repository == null) null
                else {
                    val cached =
                        if (seriesEpisodes == null) repository.cacheEpisode(episode)
                        else repository.cacheSeries(seriesEpisodes) ?: episode
                    repository.fileFor(cached)?.let { file ->
                        val bounds =
                            android.graphics.BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
                        else {
                            var sample = 1
                            while (
                                bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 720
                            ) sample *= 2
                            android.graphics.BitmapFactory.decodeFile(
                                file.absolutePath,
                                android.graphics.BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                },
                            )
                        }
                    }
                }
            }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF31574B), Color(0xFF1F454F), Color(0xFF536348))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null)
            Image(
                image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        else
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✧", fontSize = 47.sp, color = TvMint.copy(alpha = .55f))
                Text(
                    if (episode == null) "每个故事，都值得陪伴" else episode.optString("seriesTitle"),
                    color = TvText.copy(alpha = .75f),
                    fontSize = 16.sp,
                    maxLines = 1,
                )
            }
    }
}

@Composable
internal fun TvHome(
    series: List<TvSeries>,
    resume: JSONObject?,
    onPlay: (JSONObject, List<JSONObject>) -> Unit,
    onSeries: (String) -> Unit,
    onLibrary: () -> Unit,
    onSetup: () -> Unit,
    focusSeriesKey: String? = null,
) {
    if (series.isEmpty()) {
        TvEmpty("你好，小小观众", "片库还是空的，请让家长连接家庭资源。", "打开家长设置", onSetup)
        return
    }
    val hero = resume ?: series.first().episodes.first()
    val rowState = rememberLazyListState()
    val focus = remember(series.map { it.key }) { series.associate { it.key to FocusRequester() } }
    LaunchedEffect(focusSeriesKey) {
        val index = series.indexOfFirst { it.key == focusSeriesKey }
        if (index >= 0) {
            rowState.scrollToItem(index)
            delay(100)
            focus[focusSeriesKey.orEmpty()]?.requestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(19.dp)) {
        Row(
            Modifier.fillMaxWidth().height(182.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                TvPill(if (resume != null) "继续上次的冒险" else "一起发现新的故事")
                Text(
                    hero.optString("seriesTitle"),
                    color = TvText,
                    fontSize = 31.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "第 ${hero.optInt("seasonNumber",1)} 季 · 第 ${hero.optInt("episodeNumber",1)} 集 ${hero.optString("title")}",
                    fontSize = 16.sp,
                    color = TvMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    TvAction(
                        if (resume != null) "▶ 继续观看" else "▶ 开始观看",
                        {
                            onPlay(
                                hero,
                                series
                                    .firstOrNull {
                                        it.episodes.any { e ->
                                            e.optString("id") == hero.optString("id")
                                        }
                                    }
                                    ?.episodes ?: listOf(hero),
                            )
                        },
                        primary = true,
                    )
                    TvAction(
                        "选集",
                        {
                            series
                                .firstOrNull {
                                    it.episodes.any { e ->
                                        e.optString("id") == hero.optString("id")
                                    }
                                }
                                ?.let { onSeries(it.key) }
                        },
                    )
                }
            }
            Artwork(hero, Modifier.weight(1.25f).fillMaxHeight())
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("今天想看什么？", color = TvText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            TvAction("全部动画 →", onLibrary)
        }
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            contentPadding = PaddingValues(4.dp),
        ) {
            items(series, key = { it.key }) { s ->
                SeriesCard(
                    s,
                    { onSeries(s.key) },
                    Modifier.width(208.dp).focusRequester(focus.getValue(s.key)),
                )
            }
        }
    }
}

@Composable
private fun SeriesCard(series: TvSeries, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) TvMint else Color.Transparent,
                RoundedCornerShape(13.dp),
            )
            .clip(RoundedCornerShape(13.dp))
            .background(if (focused) TvPanelLight else TvBackground)
            .clickable(onClick = onClick)
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(
            series.episodes.firstOrNull(),
            Modifier.fillMaxWidth().height(95.dp),
            series.episodes,
        )
        Text(
            series.title,
            color = if (focused) TvMint else TvText,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${series.seasons.size} 季 · ${series.episodes.size} 集",
            color = TvMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
internal fun TvLibrary(
    series: List<TvSeries>,
    selectedLibrary: String?,
    onLibrary: (String?) -> Unit,
    onSeries: (String) -> Unit,
    focusSeriesKey: String? = null,
) {
    val visible = series.filter { selectedLibrary == null || it.library == selectedLibrary }
    val state = rememberLazyGridState()
    val focus =
        remember(visible.map { it.key }) { visible.associate { it.key to FocusRequester() } }
    LaunchedEffect(focusSeriesKey, selectedLibrary) {
        val index = visible.indexOfFirst { it.key == focusSeriesKey }
        if (index >= 0) {
            state.scrollToItem(index)
            delay(100)
            focus[focusSeriesKey.orEmpty()]?.requestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvSection("挑选一部喜欢的动画", "${series.size} 部动画")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TvAction("全部片库", { onLibrary(null) }, selected = selectedLibrary == null) }
            items(series.map { it.library }.distinct()) { name ->
                TvAction(name, { onLibrary(name) }, selected = selectedLibrary == name)
            }
        }
        if (visible.isEmpty()) TvEmpty("这里还没有动画", "从电脑管理端添加资源并整理片库。")
        else
            LazyVerticalGrid(
                state = state,
                columns = GridCells.Adaptive(205.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                items(visible, key = { it.key }) { item ->
                    SeriesCard(
                        item,
                        { onSeries(item.key) },
                        Modifier.focusRequester(focus.getValue(item.key)),
                    )
                }
            }
    }
}

@Composable
internal fun TvSeriesDetail(
    series: TvSeries,
    season: Int,
    onSeason: (Int) -> Unit,
    progress: Map<String, Long>,
    onPlay: (JSONObject, List<JSONObject>) -> Unit,
    onBack: () -> Unit,
    focusEpisodeId: String? = null,
) {
    val episodes = series.episodes.filter { it.optInt("seasonNumber", 1) == season }
    val state = rememberLazyGridState()
    val focus =
        remember(episodes.map { it.getString("id") }) {
            episodes.associate { it.getString("id") to FocusRequester() }
        }
    LaunchedEffect(series.key, season, focusEpisodeId) {
        val index = episodes.indexOfFirst { it.getString("id") == focusEpisodeId }.coerceAtLeast(0)
        if (episodes.isNotEmpty()) {
            state.scrollToItem(index)
            delay(100)
            focus[episodes[index].getString("id")]?.requestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Artwork(
                series.episodes.firstOrNull(),
                Modifier.width(220.dp).height(125.dp),
                series.episodes,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(series.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TvText)
                Text(
                    "${series.library} · 共 ${series.episodes.size} 集",
                    color = TvMuted,
                    fontSize = 16.sp,
                )
                val description = series.episodes.firstOrNull()?.optString("synopsis").orEmpty()
                if (description.isNotBlank())
                    Text(
                        description,
                        color = TvMuted,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            TvAction("‹ 返回", onBack)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(series.seasons) { number ->
                TvAction("第 $number 季", { onSeason(number) }, selected = season == number)
            }
        }
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(205.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(4.dp),
        ) {
            items(episodes, key = { it.getString("id") }) { episode ->
                TvEpisodeCard(
                    episode,
                    progress[episode.getString("id")] ?: 0,
                    { onPlay(episode, episodes) },
                    Modifier.focusRequester(focus.getValue(episode.getString("id"))),
                )
            }
        }
    }
}

@Composable
private fun TvEpisodeCard(
    episode: JSONObject,
    progress: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(11.dp))
            .background(if (focused) TvMint else TvPanel)
            .border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(episode, Modifier.fillMaxWidth().height(95.dp))
        Text(
            "第 ${episode.optInt("episodeNumber",1)} 集",
            fontSize = 13.sp,
            color = if (focused) Color(0xFF466348) else TvMuted,
        )
        Text(
            episode.optString("title"),
            fontSize = 19.sp,
            color = if (focused) TvBackground else TvText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (!episode.optBoolean("available")) "资源暂不可用"
            else if (progress > 0) "已看 ${TvPresentation.duration(progress/1000)}" else "等待一个新故事",
            fontSize = 12.sp,
            color = if (focused) Color(0xFF466348) else TvMuted,
        )
    }
}

@Composable
internal fun TvTags(
    dictionary: List<JSONObject>,
    selection: Map<String, Set<String>>,
    onSelection: (String, String) -> Unit,
    episodes: List<JSONObject>,
    progress: Map<String, Long>,
    onPlay: (JSONObject, List<JSONObject>) -> Unit,
    focusEpisodeId: String? = null,
) {
    val state = rememberLazyGridState()
    val focus =
        remember(episodes.map { it.getString("id") }) {
            episodes.associate { it.getString("id") to FocusRequester() }
        }
    LaunchedEffect(focusEpisodeId) {
        val index = episodes.indexOfFirst { it.getString("id") == focusEpisodeId }
        if (index >= 0) {
            state.scrollToItem(index)
            delay(100)
            focus[focusEpisodeId.orEmpty()]?.requestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        TvSection("按自己的路线看", "同一标签多选 OR · 不同标签 AND")
        if (dictionary.isEmpty()) TvEmpty("还没有标签", "请从电脑管理端创建标签，为现有剧集打标。")
        else {
            LazyColumn(
                Modifier.heightIn(max = 132.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(dictionary, key = { it.getString("id") }) { tag ->
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            Text(
                                tag.getString("key"),
                                Modifier.width(95.dp),
                                fontSize = 16.sp,
                                color = TvMuted,
                            )
                        }
                        items(tag.getJSONArray("values").strings()) { v ->
                            TvAction(
                                v,
                                { onSelection(tag.getString("key"), v) },
                                selected = selection[tag.getString("key")]?.contains(v) == true,
                            )
                        }
                    }
                }
            }
            Text("共 ${episodes.size} 集 · 已按动画 / 季 / 集排序", fontSize = 14.sp, color = TvMuted)
            if (episodes.isEmpty()) TvEmpty("没有符合这条路线的剧集", "换一个标签组合再试试。")
            else
                LazyVerticalGrid(
                    state = state,
                    columns = GridCells.Adaptive(205.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    items(episodes, key = { it.getString("id") }) { e ->
                        TvEpisodeCard(
                            e,
                            progress[e.getString("id")] ?: 0,
                            { onPlay(e, episodes) },
                            Modifier.focusRequester(focus.getValue(e.getString("id"))),
                        )
                    }
                }
        }
    }
}

@Composable
internal fun TvHistory(
    watch: List<JSONObject>,
    library: List<JSONObject>,
    onPlay: (JSONObject, List<JSONObject>) -> Unit,
) {
    val entries =
        watch
            .groupBy { it.optString("episodeId") to it.optString("date") }
            .values
            .sortedByDescending { rows -> rows.maxOf { it.optString("startedAt") } }
    Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
        TvSection("看过的故事", "重复观看也会记录实际用时")
        if (entries.isEmpty()) TvEmpty("还没有观看记录", "开始第一个故事后，这里会留下足迹。")
        else
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                items(
                    entries,
                    key = { it.first().optString("episodeId") + it.first().optString("date") },
                ) { events ->
                    val row = events.last()
                    val episode =
                        library.firstOrNull { it.getString("id") == row.optString("episodeId") }
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TvPanel)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                episode?.optString("title") ?: row.optString("title", "已归档剧集"),
                                fontSize = 20.sp,
                                color = TvText,
                            )
                            Text(
                                "${row.optString("date")} · ${row.optString("seriesTitle")} · ${TvPresentation.duration(events.sumOf{it.optLong("durationMillis",it.optLong("seconds")*1000)}/1000)}",
                                fontSize = 14.sp,
                                color = TvMuted,
                            )
                        }
                        if (episode != null) TvAction("继续观看", { onPlay(episode, listOf(episode)) })
                    }
                }
            }
    }
}

@Composable
internal fun TvCalendar(
    month: YearMonth,
    selected: LocalDate,
    days: List<JSONObject>,
    detail: JSONObject?,
    onMonth: (YearMonth) -> Unit,
    onDate: (LocalDate) -> Unit,
    timezone: String = "Asia/Shanghai",
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(
            Modifier.weight(1.35f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(TvPanel)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvAction("‹ 上月", { onMonth(month.minusMonths(1)) })
                Text(
                    "${month.year} 年 ${month.monthValue} 月",
                    fontSize = 21.sp,
                    color = TvText,
                    fontWeight = FontWeight.SemiBold,
                )
                TvAction("下月 ›", { onMonth(month.plusMonths(1)) })
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(
                        it,
                        Modifier.weight(1f),
                        color = TvMuted,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            val cells = TvPresentation.calendar(month)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (week + List(7 - week.size) { null }).forEach { day ->
                            if (day == null) Spacer(Modifier.weight(1f).fillMaxHeight())
                            else {
                                val date = month.atDay(day)
                                val seconds =
                                    days
                                        .firstOrNull { it.optString("date") == date.toString() }
                                        ?.optLong("seconds") ?: 0
                                var focused by remember(date) { mutableStateOf(false) }
                                Column(
                                    Modifier.weight(1f)
                                        .fillMaxHeight()
                                        .onFocusChanged { focused = it.isFocused }
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(
                                            if (focused || date == selected) TvMint
                                            else if (seconds > 0) Color(0xFF2C4B42)
                                            else TvPanelLight
                                        )
                                        .border(
                                            if (focused) 3.dp else 0.dp,
                                            Color.White,
                                            RoundedCornerShape(7.dp),
                                        )
                                        .clickable { onDate(date) }
                                        .padding(7.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        day.toString(),
                                        fontSize = 16.sp,
                                        color =
                                            if (focused || date == selected) TvBackground
                                            else TvText,
                                    )
                                    Text(
                                        if (seconds > 0) "${seconds/60} 分" else "—",
                                        fontSize = 10.sp,
                                        color =
                                            if (focused || date == selected) Color(0xFF45634B)
                                            else TvMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(TvPanel)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${selected.monthValue} 月 ${selected.dayOfMonth} 日 · 周${listOf("一","二","三","四","五","六","日")[selected.dayOfWeek.value-1]}",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = TvText,
            )
            Text(
                "共 ${TvPresentation.duration(detail?.optLong("totalSeconds")?:0)}",
                fontSize = 16.sp,
                color = TvMuted,
            )
            val grace = detail?.optLong("graceSeconds") ?: 0
            if (grace > 0) TvPill("其中本集收尾 ${TvPresentation.duration(grace)}", warm = true)
            val entries =
                detail
                    ?.optJSONArray("entries")
                    ?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                    .orEmpty()
            if (detail == null) Text("正在读取当日记录…", color = TvMuted, fontSize = 16.sp)
            else if (entries.isEmpty()) TvEmpty("这一天没有观看记录", "实际观看 0 分钟")
            else
                LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(entries, key = { it.getString("id") }) { e ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(TvPanelLight)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                e.optString("title"),
                                color = TvText,
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${e.optString("startedAt").let{runCatching{java.time.Instant.parse(it).atZone(java.time.ZoneId.of(timezone)).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}.getOrDefault("")}} · ${e.optString("seriesTitle")} · ${TvPresentation.calendarDuration(e)}",
                                color = TvMuted,
                                fontSize = 13.sp,
                            )
                            Text(TvPresentation.calendarKind(e), color = TvMint, fontSize = 11.sp)
                        }
                    }
                }
        }
    }
}

@Composable
internal fun TvStatistics(
    data: JSONObject?,
    dimension: String,
    onDimension: (String) -> Unit,
    period: Int,
    onPeriod: (Int) -> Unit,
) {
    val days = data?.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val rows = data?.optJSONArray("rows")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(dimension, period) { listState.scrollToItem(0) }
    val grouping = when (dimension) { "library" -> "片库观看明细"; "tag" -> "当前标签观看明细"; else -> "动画观看明细" }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf(7 to "最近 7 天", 30 to "最近 30 天", 0 to "全部历史").forEach { (n, label) ->
                TvAction(label, { onPeriod(n) }, selected = period == n)
            }
            Spacer(Modifier.weight(1f))
            listOf("series" to "按动画", "library" to "按片库", "tag" to "按当前标签").forEach { (key, label) ->
                TvAction(label, { onDimension(key) }, selected = dimension == key)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("实际观看 ${TvPresentation.duration(data?.optLong("totalSeconds") ?: 0)}", color = TvMint, fontSize = 21.sp)
            Text("${data?.optInt("episodeCount") ?: 0} 集 · ${data?.optInt("watchDays") ?: 0} 天", color = TvMuted, fontSize = 16.sp)
            Text("合计已去重，切换维度不改变合计", color = TvMuted, fontSize = 12.sp)
        }
        if (days.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("还没有观看统计", color = TvText, fontSize = 22.sp)
                    Text("开始播放后，这里会显示真实的观看时长。", color = TvMuted, fontSize = 14.sp)
                }
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(grouping, color = TvText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    if (rows.isEmpty()) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (dimension == "tag") "还没有可统计的当前标签" else "此维度暂无分组", color = TvText, fontSize = 18.sp)
                            Text(if (dimension == "tag") "为已看剧集添加标签后，历史观看会自动归入当前标签。" else "已有观看合计保留在上方。", color = TvMuted, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(rows, key = { it.optString("id").ifBlank { it.optString("label") } }) { row ->
                                var focused by remember { mutableStateOf(false) }
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                        .onFocusChanged { focused = it.isFocused }
                                        .semantics(mergeDescendants = true) { contentDescription = "${row.optString("label")}，${TvPresentation.duration(row.optLong("seconds"))}，${row.optInt("episodeCount")}集" }
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(if (focused) TvPanelLight else TvPanel)
                                        .border(if (focused) 2.dp else 0.dp, if (focused) TvMint else Color.Transparent, RoundedCornerShape(7.dp))
                                        .focusable()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(row.optString("label"), Modifier.weight(1f), fontSize = 17.sp, color = TvText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${TvPresentation.duration(row.optLong("seconds"))} · ${row.optInt("episodeCount")} 集", fontSize = 16.sp, color = TvMint)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("每天看了多久", fontSize = 16.sp, color = TvMuted)
                    val max = days.maxOf { it.optLong("seconds") }.coerceAtLeast(1)
                    LazyRow(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Bottom) {
                        items(days, key = { it.optString("date") }) { day ->
                            var focused by remember { mutableStateOf(false) }
                            Column(Modifier.width(45.dp).onFocusChanged { focused = it.isFocused }.semantics(mergeDescendants = true) { contentDescription = "${day.optString("date")}，实际观看${TvPresentation.duration(day.optLong("seconds"))}" }.border(if (focused) 2.dp else 0.dp, if (focused) TvMint else Color.Transparent, RoundedCornerShape(4.dp)).focusable().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${day.optLong("seconds") / 60}分", fontSize = 10.sp, color = TvMuted, maxLines = 1)
                                Box(Modifier.width(32.dp).height((day.optLong("seconds").toFloat() / max * 64).coerceAtLeast(2f).dp).clip(RoundedCornerShape(3.dp)).background(TvMint.copy(alpha = .65f)))
                                Text(day.optString("date").takeLast(5), fontSize = 10.sp, color = TvMuted)
                            }
                        }
                    }
                }
            }
        }
        Text(if (dimension == "tag") "按当前标签追溯历史；同一段观看可归入多个标签，各行不能直接相加。" else "上方选择统计维度，方向键上下浏览明细。", color = TvMuted, fontSize = 12.sp)
    }
}

@Composable
internal fun TvNumberPad(value: String, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("清除", "0", "删除"),
            )
            .forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { digit ->
                        TvAction(
                            digit,
                            {
                                onValue(
                                    when (digit) {
                                        "清除" -> ""
                                        "删除" -> value.dropLast(1)
                                        else -> (value + digit).take(8)
                                    }
                                )
                            },
                            Modifier.weight(1f),
                        )
                    }
                }
            }
    }
}
