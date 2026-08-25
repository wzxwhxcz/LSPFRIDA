package com.bail.lspfrifa.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bail.lspfrifa.data.AddedProject
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ui.component.AppIconImage
import com.bail.lspfrifa.ui.component.MiuixSwitch
import com.bail.lspfrifa.ui.component.MiuixPageBackground
import com.bail.lspfrifa.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 项目主页面（仅展示用户已添加项目）。
 * 空态 → 提示点击右下角添加；FAB → 导航到选择页（严禁 Dialog）。
 */
@Composable
fun ProjectScreenV2(
    projects: List<AddedProject>,
    onToggle: (String, Boolean) -> Unit,
    onNavigateToSelect: () -> Unit,
    onOpenProject: (AddedProject) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 页面外壳：与其他页（首页/设置/选择项目）保持一致的大标题；
    // 修复此前缺失 Scaffold/TopAppBar 导致内容顶到屏头的问题
    // FAB 抬升：页面外层有 116dp 悬浮底栏（56 胶囊 + 12 偏移 + 48 系统条），
    // 此处用 contentWindowInsets 底部垫高，让 FAB 位于底栏上方而不被其压住。
    val density = LocalDensity.current
    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0, 0, 0, with(density) { 116.dp.roundToPx() }),
        topBar = { TopAppBar(title = "项目", largeTitle = "项目") },
        modifier = modifier,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (projects.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("暂无项目", fontSize = UiTokens.TitleSize, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击右下角 + 按钮添加应用",
                        fontSize = UiTokens.SubtitleSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNavigateToSelect) { Text("添加项目") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(UiTokens.CardSpacing),
                    contentPadding = PaddingValues(start = UiTokens.PagePadding, top = UiTokens.PagePadding, end = UiTokens.PagePadding, bottom = 128.dp),
                ) {
                    items(projects, key = { it.packageName }) { project ->
                        ProjectCardV2(
                            project = project,
                            onToggle = { enabled -> onToggle(project.packageName, enabled) },
                            onClick = { onOpenProject(project) },
                        )
                    }
                }
            }

            // 右下角悬浮 + FAB（官方 FloatingActionButton，导航到「选择项目」全屏页）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) {
                FloatingActionButton(onClick = onNavigateToSelect) {
                    Text("＋", fontSize = 24.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun ProjectCardV2(
    project: AddedProject,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(project.packageName) {
        runCatching { context.packageManager.getApplicationIcon(project.packageName) }.getOrNull()
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = UiTokens.CardRadius,
        insideMargin = PaddingValues(horizontal = UiTokens.CardMarginH, vertical = UiTokens.CardMarginV),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconImage(icon, Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(project.appName, fontSize = UiTokens.TitleSize, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        project.packageName,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // 状态从 ScriptStore 读（与模块 is_target_enabled 同源），保证列表/详情/模块三方一致
            MiuixSwitch(
                checked = IpcManager.isTargetEnabled(project.packageName),
                onCheckedChange = onToggle,
            )
        }
    }
}
