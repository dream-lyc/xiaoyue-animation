package cn.childtv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.childtv.core.ParentAction
import cn.childtv.core.ParentChallenge
import cn.childtv.core.PlayingEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ParentSettings(
    graph: AppGraph,
    policy: JSONObject?,
    current: PlayingEpisode?,
    currentEpisode: JSONObject?,
    challenge: ParentChallenge?,
    action: ParentAction,
    grant: String?,
    answer: String,
    onAnswer: (String) -> Unit,
    minutes: String,
    onMinutes: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    extraEpisode: JSONObject?,
    onChallenge: (ParentAction) -> Unit,
    onVerify: () -> Unit,
    onCancel: () -> Unit,
    onSave: (Boolean) -> Unit,
    onExtra: () -> Unit,
    onEnable: () -> Unit,
    onStop: () -> Unit,
    address: String,
) {
    val used = policy?.optLong("watchedSeconds") ?: 0
    val context = LocalContext.current.applicationContext
    var serviceState by remember { mutableStateOf("stopped") }
    var serviceError by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (isActive) {
            val status =
                withContext(Dispatchers.IO) {
                    val preferences =
                        context.getSharedPreferences(
                            "service-status",
                            android.content.Context.MODE_PRIVATE,
                        )
                    (preferences.getString("state", "stopped") ?: "stopped") to
                        (preferences.getString("error", "") ?: "")
                }
            serviceState = status.first
            serviceError = status.second
            delay(1000)
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "今天的安排，\n由家长来决定。",
                fontSize = 34.sp,
                lineHeight = 47.sp,
                fontWeight = FontWeight.Bold,
                color = TvText,
            )
            Text("今日设置只影响今天，实际已观看时长始终保留。", fontSize = 17.sp, color = TvMuted)
            TvPill("今日总额度 ${policy?.optInt("todayMinutes")?:45} 分钟")
            TvPill("今天已看 ${TvPresentation.duration(used)}")
            Text(
                "默认每天 ${policy?.optInt("defaultMinutes")?:45} 分钟 · ${policy?.optString("timezone")?:"Asia/Shanghai"}",
                fontSize = 13.sp,
                color = TvMuted,
            )
            if (current != null)
                Text("当前集已暂停，进入设置的时间不计入观看。调低额度后仍可保留本集收尾。", fontSize = 14.sp, color = TvWarm)
            TvAction(if (currentEpisode != null) "取消，返回当前集" else "取消，返回", onCancel)
        }
        Column(
            Modifier.weight(1.1f)
                .fillMaxHeight()
                .background(TvPanel, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(23.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                challenge != null -> {
                    Text(
                        "请交给家长完成",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TvText,
                    )
                    Text("验证通过后进入设置，不会直接增加额度。", fontSize = 14.sp, color = TvMuted)
                    Text(
                        challenge.prompt,
                        Modifier.fillMaxWidth()
                            .background(TvPanelLight, RoundedCornerShape(10.dp))
                            .padding(16.dp),
                        fontSize = 27.sp,
                        color = TvMint,
                    )
                    OutlinedTextField(
                        answer,
                        onAnswer,
                        label = { Text("输入答案") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TvNumberPad(answer, onAnswer)
                    TvAction("验证并继续", onVerify, Modifier.fillMaxWidth(), primary = true)
                    Text("连续答错 3 次需等待 30 秒；每次授权只用于指定操作。", fontSize = 12.sp, color = TvMuted)
                }
                grant != null && action == ParentAction.CHANGE_TODAY -> {
                    Text(
                        "只为今天安排",
                        fontSize = 25.sp,
                        color = TvText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("设置今天可观看的总额度", color = TvMuted, fontSize = 15.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TvAction(
                            "− 5 分钟",
                            {
                                onMinutes(
                                    ((minutes.toIntOrNull() ?: 0) - 5).coerceAtLeast(0).toString()
                                )
                            },
                        )
                        Text("$minutes 分钟", color = TvMint, fontSize = 28.sp)
                        TvAction(
                            "＋ 5 分钟",
                            {
                                onMinutes(
                                    ((minutes.toIntOrNull() ?: 0) + 5).coerceAtMost(1440).toString()
                                )
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 45, 60, 90).forEach { n ->
                            TvAction(
                                "$n 分",
                                { onMinutes(n.toString()) },
                                Modifier.weight(1f),
                                selected = minutes == n.toString(),
                            )
                        }
                    }
                    Text(
                        "保存后今天还可看 ${TvPresentation.duration(((minutes.toLongOrNull()?:0)*60-used).coerceAtLeast(0))}",
                        color = TvMint,
                        fontSize = 16.sp,
                    )
                    TvAction("保存今日设置", { onSave(false) }, Modifier.fillMaxWidth(), primary = true)
                    TvAction(
                        "恢复每日默认 ${policy?.optInt("defaultMinutes")?:45} 分钟",
                        { onSave(true) },
                        Modifier.fillMaxWidth(),
                    )
                    Text(
                        "授权 2 分钟过期。保存后自动锁定；下次修改需要重新验证。到时看完本集后停止，不自动播放下一集。",
                        fontSize = 13.sp,
                        color = TvMuted,
                    )
                }
                grant != null && action == ParentAction.EXTRA_EPISODE -> {
                    Text("再看这一集", fontSize = 25.sp, color = TvText)
                    Text(
                        extraEpisode?.optString("title") ?: "未选择剧集",
                        color = TvMint,
                        fontSize = 25.sp,
                    )
                    Text("只允许这一个逻辑集，观看时间仍计入今天的实际用时。结束后重新按额度判断。", color = TvMuted, fontSize = 16.sp)
                    TvAction("确认允许并播放", onExtra, Modifier.fillMaxWidth(), primary = true)
                }
                else -> {
                    Text("家长管理", fontSize = 24.sp, color = TvText)
                    TvAction(
                        "验证后修改今日总额度",
                        { onChallenge(ParentAction.CHANGE_TODAY) },
                        Modifier.fillMaxWidth(),
                        primary = true,
                    )
                    Text("电脑管理", fontSize = 22.sp, color = TvText)
                    TvPill(
                        "服务状态：" +
                            when (serviceState) {
                                "starting" -> "正在启动"
                                "running" -> "正在运行"
                                "failed" -> "启动失败"
                                else -> "已停止"
                            },
                        warm = serviceState == "failed",
                    )
                    if (serviceError.isNotBlank())
                        Text(serviceError, color = TvWarm, fontSize = 14.sp)

                    Text(
                        "管理密码与答题授权是独立机制。电脑与电视需处于同一可信局域网。HTTP 传输未加密，电视休眠或应用关闭后可能无法访问。",
                        color = TvMuted,
                        fontSize = 14.sp,
                    )
                    if (grant == null || action != ParentAction.MANAGE_ADMIN)
                        TvAction(
                            "验证管理服务操作",
                            { onChallenge(ParentAction.MANAGE_ADMIN) },
                            Modifier.fillMaxWidth(),
                        )
                    else TvPill("管理操作已授权 · 2 分钟内有效")
                    OutlinedTextField(
                        password,
                        onPassword,
                        label = { Text("独立管理员密码（至少 12 个字符）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TvAction(
                        "保存密码并启用管理",
                        onEnable,
                        Modifier.fillMaxWidth(),
                        enabled = grant != null && action == ParentAction.MANAGE_ADMIN,
                    )
                    TvAction(
                        "停止电脑管理服务",
                        onStop,
                        Modifier.fillMaxWidth(),
                        enabled = grant != null && action == ParentAction.MANAGE_ADMIN,
                    )
                    Text(address, color = TvMint, fontSize = 21.sp)
                }
            }
        }
    }
}
