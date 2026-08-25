package com.bail.lspfrifa.ui.screen

import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ipc.LogStore
import com.bail.lspfrifa.ui.component.GlassTopAppBar
import com.bail.lspfrifa.ui.component.MiuixPageBackground
import com.bail.lspfrifa.ui.component.UiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 日志列表展示上限（历史回放 + 实时共用，从 ProjectDetailScreenV093 迁移）。 */
private const val MAX_LOG_LINES = 500

/** 实时标志窗口：最近收到上行日志的毫秒数内视为"有实时流"。 */
private const val LIVE_WINDOW_MS = 3000L

// ==================== t3 定稿：日志页（现代日志查看器范式） ====================

/**
 * 用户强制（t3 纠正）：绿黑终端配色彻底废弃——#171717/#74E391 从 LogScreenV1.kt 全部移除
 * （无"授权保留"理解）。日志视觉 = Miuix 语义 token 纯体系：消息主文本 onSurface、
 * 时间戳/次要 onSurfaceVariantSummary、背景 MiuixPageBackground()（无独立深色卡，
 * 深色模式自动由 token 变暗）；级别色 = token 映射 + 8dp 色点行首徽标。
 */

/** 时间戳列固定宽度（弱化：11sp 等宽 11 字符 ≈ 73dp，取 76dp）。 */
private val TimestampColumnWidth = 76.dp

/** LogStore 行前缀（LogStore.kt L96 实核：`[${timeFormatter}] $message`，yyyy-MM-dd HH:mm:ss.SSS）。 */
private val LogLinePrefix = Regex("""^\[(\d{4}-\d{2}-\d{2} (\d{2}:\d{2}:\d{2}\.\d{3}))\] (.*)""")

/** 实时行时间戳兜底格式（与 LogStore.append 完全一致：yyyy-MM-dd HH:mm:ss.SSS）。 */
private val RowTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** 展示行模型：级别（色点徽标）+ 时间戳（可空=行无前缀，如实时行）+ 消息（prettyMessage 结果）。 */
private class LogLine(
    val level: LogLevel,
    val timestamp: String?,
    val message: String,
)

/** 日志级别（AS Logcat 惯例 V/D/I/W/E/F）。 */
private enum class LogLevel { Verbose, Debug, Info, Warn, Error, Fatal }

/**
 * 级别 → Miuix token（t4 定稿 map；⚠ 实核调整：Miuix Colors **无** `onSurfaceVariant`/`tertiary`
 * 属性（Colors.kt 构造函数实核，仅 tertiaryContainer 族 / onSurfaceVariantSummary），故：
 * V 用 onSurfaceVariantSummary（语义等价：variant 上摘要色）、W 用 tertiaryContainer（第三色族唯一存在 token））。
 */
@Composable
private fun logLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.Verbose -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    LogLevel.Debug -> MiuixTheme.colorScheme.secondary
    LogLevel.Info -> MiuixTheme.colorScheme.primary
    LogLevel.Warn -> MiuixTheme.colorScheme.tertiaryContainer
    LogLevel.Error -> MiuixTheme.colorScheme.error
    LogLevel.Fatal -> MiuixTheme.colorScheme.error
}

/**
 * t6：独立日志页（信息架构拆分——从 ProjectDetailScreenV093 的 AnalysisPanel + 日志
 * DisposableEffect 迁移）。t3 定稿：功能（历史回放/实时监听/prettyMessage）不变；
 * 视觉：紧凑玻璃顶栏（SmallTopAppBar：返回+"日志"标题+应用名副标+清除 action）、
 * 单行小字指标 + 「● 实时」标识、扫读式列表（8dp 级别色点 + 时间戳弱化列 + 消息列，
 * 多行 payload 续行缩进 12dp 继承级别色）、追尾自动滚动 + 用户上滚暂停 + 「回到底部」按钮、
 * 真清除（显示层 + 持久文件同步删除——重进不回潮）、空态两态（等待连接/已清除）。
 */
@Composable
fun LogScreenV1(
    projectName: String,
    packageName: String,
    onBack: () -> Unit,
) {
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    // 实时流心跳：最近一次上行日志时间（实时标识；无实时流时不显示）
    var lastLiveAt by remember { mutableStateOf(0L) }
    var liveNow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            liveNow = System.currentTimeMillis() - lastLiveAt < LIVE_WINDOW_MS
            delay(1000L)
        }
    }
    // 清除状态（空态据此显示"已清除"；持久文件删除见 onClear → LogStore.clearAsync）
    var cleared by remember { mutableStateOf(false) }

    DisposableEffect(packageName) {
        var unsubscribe: (() -> Unit)? = null
        // disposed 防护（P2）：历史读取（阻塞 IO，不可取消）期间页面可能已销毁或键已变更，
        // 协程仍在 withContext(Main) 后继续执行；一旦已 dispose 就不再注册监听，杜绝 edge 泄漏。
        var disposed = false
        // 先读该包历史日志（后台 IO，最近 MAX_LOG_LINES 行），再订阅实时流，保证按时间序展示
        scope.launch(Dispatchers.IO) {
            val history = LogStore.readHistory(packageName, MAX_LOG_LINES)
            withContext(Dispatchers.Main) {
                if (disposed) return@withContext
                logs.addAll(history)
                while (logs.size > MAX_LOG_LINES) logs.removeAt(0)
                unsubscribe = IpcManager.addLogListener { target, message ->
                    if (target == packageName) {
                        lastLiveAt = System.currentTimeMillis()
                        // t9-2：实时行无时间戳前缀（logReceiverStub 直发原始消息）→ 本地兜底补齐，
                        // 否则实时到达的日志显示缺时间戳（重进读历史才有）
                        val stamped = if (LogLinePrefix.containsMatchIn(message)) message
                        else "[${RowTimeFormatter.format(LocalDateTime.now())}] $message"
                        logs.add(stamped)
                        if (logs.size > MAX_LOG_LINES) logs.removeAt(0)
                    }
                }
            }
        }
        onDispose {
            disposed = true
            unsubscribe?.invoke()
        }
    }

    // t9：3s 轮询兜底——实时订阅可能因时序/后台漏收；以 LogStore 队列为准增量补齐
    // （尾部相同=无新行；尾部不同=按上次尾部定位后追加；cleared 后暂停自动重载）
    LaunchedEffect(packageName) {
        while (true) {
            delay(3000)
            if (cleared) continue
            val h = withContext(Dispatchers.IO) { LogStore.readHistory(packageName, MAX_LOG_LINES) }
            if (h.size > logs.size && h.isNotEmpty() && logs.isNotEmpty() && h[h.size - 1] != logs[logs.size - 1]) {
                val start = h.indexOf(logs[logs.size - 1])
                if (start >= 0) logs.addAll(h.subList(start + 1, h.size))
            }
        }
    }

    // ---- t3：追尾自动滚动 + 用户上滚暂停 + 回底恢复 ----
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var pinnedToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { inProgress ->
                if (!inProgress) {
                    val info = listState.layoutInfo
                    val total = info.totalItemsCount
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    pinnedToBottom = total == 0 || lastVisible >= total - 2
                }
            }
    }
    LaunchedEffect(dragged) {
        if (dragged) pinnedToBottom = false
    }
    LaunchedEffect(logs.size) {
        if (pinnedToBottom && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }
    // 回到底部：恢复追尾 + 滚到末项
    val jumpToBottom: () -> Unit = {
        pinnedToBottom = true
        scope.launch { listState.animateScrollToItem((logs.size - 1).coerceAtLeast(0)) }
    }

    // t3 定稿：清除=真清除（显示层 + LogStore 持久文件同步删除——修复"清除后重进又出现"：
    // 旧实现只清 logs+cleared 标志，文件保留，重进 DisposableEffect readHistory 又灌回）
    // （captain 修复保留：显式 () -> Unit——避免 lambda 末表达式被推断为 Job 导致 IconButton onClick 编译失败）
    val onClear: () -> Unit = {
        logs.clear()
        cleared = true
        // 页面作用域无关执行（LogStore 内 App 级 IO scope）：清除后立即返回/销毁也不取消真清除
        LogStore.clearAsync(packageName)
    }

    // t10：导出全部日志（分享/存文件——系统文本选择跨行是 CMP 已知缺陷，导出为完整文本兜底）
    val context = LocalContext.current
    val exportLogs: () -> Unit = {
        val text = logs.joinToString("\n")
        if (text.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "LSPFRIFA 日志 - $projectName")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "导出日志")) }
        }
    }


    // t2：玻璃拟态捕获层
    val backdrop = rememberLayerBackdrop()

    Scaffold(
        containerColor = MiuixPageBackground(),
        topBar = {
            GlassTopAppBar(backdrop) {
                // t3 紧凑顶栏：Miuix SmallTopAppBar（0.9.4-rc01 实核：title+subtitle+actions；
                // 返回 + 标题"日志" + 应用名副标 + 清除 action）
                SmallTopAppBar(
                    title = "日志",
                    subtitle = projectName,
                    color = Color.Transparent,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // t5：live 标识移入顶栏 actions 区（Delete 左）——与 chrome 对齐；
                        // 视觉=软底胶囊（surfaceVariant）+ 6dp primary 圆点 + 11sp 小字（非整块深色）
                        if (liveNow) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.primary),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "实时",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                        // t10：导出全部日志（系统分享面板：可存文件/发送）
                        IconButton(onClick = exportLogs) {
                            Icon(
                                imageVector = MiuixIcons.Share,
                                contentDescription = "导出日志",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "清除日志（显示层+文件同步删除）",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixPageBackground())
                .layerBackdrop(backdrop),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = UiTokens.PagePadding),
            ) {
                // 指标行：t5 仅非空显示（空态 0 条不出"日志 0 条"）；纯文字无色块
                if (logs.isNotEmpty()) {
                    Text(
                        "日志 ${logs.size} 条",
                        fontSize = UiTokens.CaptionSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // 日志列表直排页面背景（无独立深色卡；深色模式 token 自动变暗）
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        if (logs.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (cleared) "已清除" else "等待目标进程连接…",
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        fontSize = UiTokens.SubtitleSize,
                                    )
                                }
                            }
                        }
                        items(logs) { raw ->
                            val line = remember(raw) { toLogLine(raw) }
                            LogLineRow(line)
                        }
                    }
                    // 「回到底部」（即用户已离底暂停追尾时弹出；点击恢复追尾）
                    if (!pinnedToBottom && logs.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 4.dp, bottom = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MiuixTheme.colorScheme.secondaryContainer)
                                .clickable(onClick = jumpToBottom)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "↓ 回到底部",
                                fontSize = UiTokens.CaptionSize,
                                color = MiuixTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单行日志：行首 8dp 级别色点徽标（非整行着色，保留可读性）→ 时间戳弱化列（11sp 次要色，
 * 仅历史行）→ 消息列（等宽 12sp；首行 onSurface，Fatal=error+加粗；多行 payload 续行
 * 缩进 12dp 并继承级别色）。SelectionContainer 保留长按复制。
 */
@Composable
private fun LogLineRow(line: LogLine) {
    val context = LocalContext.current
    val levelColor = logLevelColor(line.level)
    SelectionContainer {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(vertical = 1.dp),
        ) {
            // 8dp 级别色点徽标（行首）
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(levelColor),
            )
            Spacer(Modifier.width(6.dp))
            if (line.timestamp != null) {
                Text(
                    text = line.timestamp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(TimestampColumnWidth),
                )
            }
            val parts = line.message.split('\n')
            val msgText = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = if (line.level == LogLevel.Fatal) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.onSurface,
                    ),
                ) { append(parts.first()) }
                parts.drop(1).forEach { cont ->
                    // 真实换行 + 8 空格缩进（等宽 12sp 下 ≈ 原 12dp 续行缩进视觉）
                    append("\n        ")
                    withStyle(SpanStyle(color = levelColor)) { append(cont) }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = msgText,
                    fontWeight = if (line.level == LogLevel.Fatal) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    softWrap = true,
                )
            }
            // t9：整条复制（长按选择跨多 Text 体验差/只能选到单行——直接复制完整条目文本）
            IconButton(
                onClick = {
                    val full = buildString {
                        line.timestamp?.let { append(it).append(' ') }
                        append(line.message)
                    }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("LSPFRIFA 日志", full))
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = "复制整条",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 原始行 → 展示行：
 * 1. 剥离 LogStore 时间戳前缀（"[yyyy-MM-dd HH:mm:ss.SSS] "，仅显示 HH:mm:ss.SSS——日期段在
 *    单日视图中无信息量）；实时监听行无前缀（IpcManager logReceiverStub 直发原始 message，
 *    LogStore.append 才加前缀）→ 时间戳为空，不伪造（协议无时间戳字段=缺口，见报告）；
 * 2. 级别：Frida 协议 JSON 的 level 字段映射（info/warning/error/…）；非 JSON 行 → Verbose（中性）；
 * 3. prettyMessage 解析 payload（保持）。
 */
private fun toLogLine(raw: String): LogLine {
    val match = LogLinePrefix.matchEntire(raw)
    return if (match != null) {
        val payload = match.groupValues[3]
        LogLine(parseLevel(payload), match.groupValues[2], prettyMessage(payload))
    } else {
        LogLine(parseLevel(raw), null, prettyMessage(raw))
    }
}

/** Frida 协议 level 字段 → AS Logcat 惯例级别（V/D/I/W/E/F）。 */
private fun parseLevel(raw: String): LogLevel {
    return try {
        when (org.json.JSONObject(raw).optString("level").lowercase().ifBlank { "info" }) {
            "verbose", "trace" -> LogLevel.Verbose
            "debug" -> LogLevel.Debug
            "info", "log" -> LogLevel.Info
            "warning", "warn" -> LogLevel.Warn
            "error" -> LogLevel.Error
            "fatal", "assert" -> LogLevel.Fatal
            else -> LogLevel.Info
        }
    } catch (_: Throwable) {
        LogLevel.Verbose // 非 JSON 行（HookRouter 纯文本/协议外上行）→ 中性
    }
}

/**
 * 显示层美化：GumJS 上行原始消息是 Frida 协议 JSON（{"type":"log","level":"info","payload":...}），
 * 显示时解析出 payload 纯文本；非 JSON / 历史行（带时间戳前缀）/ HookRouter 纯文本行原样显示。
 */
private fun prettyMessage(raw: String): String {
    return try {
        val obj = org.json.JSONObject(raw)
        if (obj.optString("type") == "log") obj.optString("payload", raw) else raw
    } catch (_: Throwable) {
        raw
    }
}
