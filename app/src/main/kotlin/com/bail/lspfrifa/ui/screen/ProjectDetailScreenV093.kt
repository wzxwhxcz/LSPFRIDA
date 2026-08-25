package com.bail.lspfrifa.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ui.component.GlassTopAppBar
import com.bail.lspfrifa.ui.component.UiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.bail.lspfrifa.ui.component.MiuixPageBackground

/**
 * t6：插件视图（信息架构拆分——详情页不再堆编辑器/日志，只保留：应用信息 + 连接状态 +
 * 插件开关卡 + 「脚本编辑」「日志」两个入口行；编辑器/日志主体移至独立 Screen，由 MainActivity
 * nav 接线（onOpenEditor/onOpenLogs 契约见任务 output；默认空实现保证未接线时编译通过）。
 */
@Composable
fun ProjectDetailScreenV093(
    projectName: String,
    packageName: String,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
) {
    // 初始值从 ScriptStore 读（模块冷启动是目标查询数据源），保证详情页与列表/模块一致
    var enabled by remember(packageName) { mutableStateOf(IpcManager.isTargetEnabled(packageName)) }
    var connected by remember { mutableStateOf(false) }
    // t6：作用域申请结果反馈（原写入日志流，日志已迁移至 LogScreen，此处以行内提示展示）
    var scopeHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /** R1.2：向框架申请作用域；回调已投递主线程，结果写入行内提示。 */
    fun requestScopeCall() {
        IpcManager.requestScope(packageName) { ok, msg ->
            scopeHint = if (ok) "[+] 作用域申请成功: $msg" else "[-] 作用域申请失败: $msg"
        }
    }

    // 页面可见期间轮询连接状态：宿主重启后目标进程经 TargetIpcServer 自动重注册，
    // 一次性检查会错过异步恢复，轮询让"连接状态"如实反映恢复结果。
    LaunchedEffect(packageName) {
        while (true) {
            // F3：ping 为跨进程 Binder 调用，放 IO 线程
            connected = withContext(Dispatchers.IO) { IpcManager.isTargetAlive(packageName) }
            delay(3000L)
        }
    }

    // t2：玻璃拟态捕获层——内容容器 layerBackdrop（含页面底色），TopAppBar 以 textureBlur 采样
    val backdrop = rememberLayerBackdrop()

    Scaffold(
        containerColor = MiuixPageBackground(),
        topBar = {
            GlassTopAppBar(backdrop) {
                TopAppBar(
                    title = projectName,
                    largeTitle = projectName,
                    color = Color.Transparent,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { padding ->
        // 捕获层含页面底色（Scaffold containerColor 在 body 之下，未被捕获，需自行补底）
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
            ProjectHeaderCard(projectName = projectName, packageName = packageName)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("连接状态：", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(
                    if (connected) "已连接" else "未连接",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (connected) Color(0xFF35C759) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (enabled) "脚本已启用" else "脚本已停用",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(10.dp))
                // R1.2：向框架动态申请作用域
                Text(
                    "申请作用域",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clickable { requestScopeCall() },
                )
            }
            scopeHint?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(12.dp))
            // 插件开关卡（Miuix SwitchPreference 行内 switch 规格 + icon 圆底：startAction 实核可用）
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                SwitchPreference(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        // 同步写 ScriptStore：模块冷启动 is_target_enabled 依赖此数据；
                        // 停用时同时卸载目标进程内已加载脚本。
                        // F3：stopScript → 远程 unloadScript 为 Binder 调用，放 IO 线程。
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (it) {
                                    IpcManager.enableTarget(packageName)
                                } else {
                                    IpcManager.disableTarget(packageName)
                                    IpcManager.stopScript(packageName)
                                }
                            }
                        }
                    },
                    title = "Frida GumJS",
                    summary = "JavaScript · QuickJS · 动态注入",
                    modifier = Modifier.fillMaxWidth(),
                    startAction = { IconBadge(MiuixIcons.Layers) },
                )
            }
            Spacer(Modifier.height(12.dp))
            // t7：脚本编辑/日志入口行（导航语义，替代 t6 的 M3 分段按钮）：
            // 原设计=SingleChoiceSegmentedButtonRow（互斥**选择**控件）+ 点击即导航——
            // 语义错位（选择态无意义、容器样式暗示"页面内切换"而实际是进独立页面）。
            // 现改为 Miuix 列表条目范式（icon 圆底 + 标题/副标题 + ChevronForward），
            // 与设置页条目同构，明确表达"进入"动作；Sink 按压反馈为 Miuix 原生。
            EntryRowCard(
                icon = MiuixIcons.Edit,
                title = "脚本编辑",
                summary = "JavaScript · TextMate 高亮 · 自动保存",
                onClick = onOpenEditor,
            )
            Spacer(Modifier.height(10.dp))
            EntryRowCard(
                icon = MiuixIcons.ListView,
                title = "日志",
                summary = "运行日志 · 实时流",
                onClick = onOpenLogs,
            )
            Spacer(Modifier.height(14.dp))
        }
        }
    }
}

@Composable
private fun ProjectHeaderCard(projectName: String, packageName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = UiTokens.CardRadius,
        insideMargin = PaddingValues(horizontal = UiTokens.CardMarginH, vertical = UiTokens.CardMarginV),
    ) {
        Text(projectName, fontSize = UiTokens.TitleSize, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Text(
            packageName,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProjectTag("主进程")
            ProjectTag("所有进程")
            ProjectTag("动态作用域")
        }
    }
}

@Composable
private fun ProjectTag(text: String) {
    Card(
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.secondaryContainer,
            contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * t7：功能入口行卡片（导航语义）——与 Miuix 设置条目同构：
 * icon 圆底 + 标题/副标题 + 右侧 ChevronForward，Card onClick + Sink 按压反馈。
 */
@Composable
private fun EntryRowCard(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = UiTokens.CardRadius,
        insideMargin = PaddingValues(horizontal = UiTokens.CardMarginH, vertical = 12.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * t6：条目 icon 圆底容器（surfaceVariant 圆底 + primary 图标，Miuix 语义 token 自适应；
 * 与 SettingsScreenV093 的 IconBadge 同规格——文件私有不允许共享，双方各自声明）。
 */
@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}
