package com.bail.lspfrifa.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.bail.lspfrifa.data.ScriptImport
import com.bail.lspfrifa.data.ScriptLibraryStore
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ipc.ScriptStore
import com.bail.lspfrifa.ui.component.GlassTopAppBar
import com.bail.lspfrifa.ui.component.MiuixCodeEditor
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Redo
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * t7：脚本编辑页 v2 — 全屏工作台（最高审美重构）。
 *
 * 设计（第一性原理）：移动端代码编辑 = 内容优先。所有 chrome 让位给编辑器：
 * - 顶栏：玻璃拟态紧凑工具栏（返回 + 应用名小标题 + 右侧动作图标组 Undo/Redo/▶运行）；
 *   撤销/重做/运行均为高频动作（sora 官方 demo 范式），图标化消灭大按钮；
 * - 编辑器：无卡片包裹、全屏填充（背景=页面背景），行号/等宽/当前行高亮/自动缩进；
 * - 自动保存：编辑防抖 400ms 实时沉淀 ScriptStore（无"保存"按钮——图标集无 Save 语义，
 *   自动保存更符合移动端心智）；顶栏右侧小字"已保存"状态反馈；
 * - 结果反馈：runHint = 顶部浮层胶囊（AnimatedVisibility 进出，非侵入式）；
 * - 符号栏/高亮：保留 sora SymbolInputView + TextMate（高亮失败降级基础配色并在浮层提示）。
 */
@Composable
fun ScriptEditorScreenV1(
    projectName: String,
    packageName: String,
    onBack: () -> Unit,
) {
    var enabled by remember(packageName) { mutableStateOf(IpcManager.isTargetEnabled(packageName)) }
    // t7：初始值单独持有——onDispose 冲刷时以「是否有编辑」为判据，避免未编辑返回时
    // 把默认模板误写覆盖（仅写入用户真实改动）。
    val initial = remember(packageName) {
        IpcManager.loadScript(packageName) ?: """
            // 可视化验证 v2
            LSP.hook("android.app.Activity", "onResume", "demo");
            console.log("[*] hook ready");
            """.trimIndent()
    }
    var script by remember(packageName) { mutableStateOf(initial) }
    var runHint by remember { mutableStateOf<String?>(null) }
    var savedAt by remember { mutableStateOf(false) }
    var tmError by remember { mutableStateOf<String?>(null) }
    var editorRef by remember { mutableStateOf<io.github.rosemoe.sora.widget.CodeEditor?>(null) }
    val scope = rememberCoroutineScope()

    // t7（P0）：退出冲刷——自动保存是 400ms 防抖，最后 ≤400ms 的编辑会随页面销毁的
    // 协程取消而静默丢失；onDispose 同步 flush 兜底（saveScript 为同步 SP 写入，安全）。
    // 只写有改动的脚本（initial 判据），未编辑返回不产生写入。
    DisposableEffect(Unit) {
        onDispose {
            if (script != initial) IpcManager.saveScript(packageName, script)
        }
    }

    // 自动保存（防抖 400ms；编辑即沉淀，冷启动注入依赖 ScriptStore）
    fun scheduleSave() {
        savedAt = false
        scope.launch {
            delay(400)
            withContext(Dispatchers.IO) { IpcManager.saveScript(packageName, script) }
            savedAt = true
        }
    }

    // ---- D6/D7：导入（文件 / 剪贴板 / 撤销） ----
    val ctx = LocalContext.current
    var importPreview by remember { mutableStateOf<ScriptImport.Result?>(null) }
    var showImportSource by remember { mutableStateOf(false) }

    /** 导入内容 → 先展示预览与来源，用户确认后才应用（导入即任意代码执行）。 */
    fun stageImport(outcome: ScriptImport.Outcome) {
        when (outcome) {
            is ScriptImport.Outcome.Ok -> importPreview = outcome.result
            is ScriptImport.Outcome.Failed -> runHint = when (val f = outcome.reason) {
                ScriptImport.Failure.Empty -> "导入失败：内容为空"
                ScriptImport.Failure.Binary -> "导入失败：疑似二进制文件"
                ScriptImport.Failure.TooLarge -> "导入失败：超过 " + (ScriptStore.MAX_SCRIPT_BYTES / 1024) + "KB"
                is ScriptImport.Failure.ReadError -> "导入失败：" + f.message
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) stageImport(ScriptImport.readUri(ctx, uri))
    }

    fun importFromClipboard() {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        stageImport(ScriptImport.readText(text, "clipboard.js", ScriptImport.Origin.CLIP))
    }

    /** 确认导入：备份当前 → 入库（去重）→ 覆盖应用。 */
    fun applyImport(preview: ScriptImport.Result) {
        scope.launch {
            val backup = withContext(Dispatchers.IO) { IpcManager.backupScript(packageName, script) }
            val id = withContext(Dispatchers.IO) {
                ScriptLibraryStore.add(preview.suggestedName, preview.code, preview.origin).first
            }
            script = preview.code
            val saved = withContext(Dispatchers.IO) { IpcManager.saveScript(packageName, script) }
            importPreview = null
            runHint = when (saved) {
                ScriptStore.SaveResult.OK ->
                    "已导入并应用（库 " + id.takeLast(6) + (if (backup != null) "，可撤销" else "") + "）"
                ScriptStore.SaveResult.LOCAL_ONLY -> "已导入（仅本地）：框架未连接，激活后自动下发"
                ScriptStore.SaveResult.SIZE_EXCEEDED -> "已入库，但超出下发上限 400KB"
                ScriptStore.SaveResult.REMOTE_REJECTED -> "已入库，但框架拒绝写入（容量已满）"
            }
        }
    }

    fun undoImport() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { IpcManager.undoImport(packageName) }
            if (ok) {
                script = withContext(Dispatchers.IO) { IpcManager.loadScript(packageName) } ?: script
                runHint = "已撤销导入，恢复上一版"
            } else {
                runHint = "没有可撤销的导入"
            }
        }
    }

    fun pushScript() {
        scope.launch {
            val alive = withContext(Dispatchers.IO) { IpcManager.isTargetAlive(packageName) }
            if (!alive) {
                withContext(Dispatchers.IO) { IpcManager.saveScript(packageName, script) }
                runHint = "目标进程未连接，脚本已保存等待注入"
                return@launch
            }
            val result = runCatching {
                withContext(Dispatchers.IO) { IpcManager.pushScript(packageName, script) }
            }.getOrNull()
            runHint = when (result) {
                ScriptStore.SaveResult.OK -> "脚本热更新成功"
                ScriptStore.SaveResult.LOCAL_ONLY -> "仅存本地：框架未连接，激活后自动下发"
                ScriptStore.SaveResult.SIZE_EXCEEDED -> "脚本超限（上限 400KB），已拒绝"
                ScriptStore.SaveResult.REMOTE_REJECTED -> "框架拒绝写入（容量已满），请缩小脚本"
                null -> "脚本加载失败，请检查日志"
            }
        }
    }

    val backdrop = rememberLayerBackdrop()

    Scaffold(
        containerColor = MiuixPageBackground(),
        topBar = {
            // 紧凑玻璃工具栏（无大标题——内容优先）
            GlassTopAppBar(backdrop) {
                // t8：紧凑顶栏（SmallTopAppBar 52dp——原 TopAppBar 大标题模式高度过大，
                // 用户两轮截图均反馈"顶栏太大"；与日志页同规格）
                SmallTopAppBar(
                    title = projectName,
                    subtitle = "",
                    color = Color.Transparent,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // D6：导入入口。图标先用已验证存在的 MiuixIcons.Edit
                        // （导入专用图标未经三查，待 UI 二轮替换；禁用未核对图标是本项目纪律）
                        ToolbarIcon(MiuixIcons.Edit, "导入脚本") { showImportSource = true }
                        ToolbarIcon(MiuixIcons.Undo, "撤销") { editorRef?.undo() }
                        ToolbarIcon(MiuixIcons.Redo, "重做") { editorRef?.redo() }
                        Spacer(Modifier.width(4.dp))
                        // 运行：主操作 = primary 色强调
                        IconButton(
                            onClick = ::pushScript,
                            enabled = enabled,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Play,
                                contentDescription = "运行脚本",
                                tint = if (enabled) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(22.dp),
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
            // 编辑器全屏工作区（无卡片：内容优先）
            MiuixCodeEditor(
                script = script,
                onScriptChange = {
                    script = it
                    scheduleSave()
                },
                onTmError = { msg -> tmError = msg },
                onEditorReady = { editorRef = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = UiTokens.PagePadding),
            )
            // 顶部浮层反馈胶囊（runHint / 保存状态 / 高亮降级）
            // t5：top 改用 Scaffold padding.calculateTopPadding()（= 顶栏高度，含状态栏 inset，
            // 参考设备 ≈80dp；比硬编码 56dp 自适应状态栏差异，浮层不再被顶栏遮挡）
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = padding.calculateTopPadding(), start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(
                    visible = runHint != null,
                    enter = fadeIn() + slideInVertically { -it / 2 },
                    exit = fadeOut() + slideOutVertically { -it / 2 },
                ) {
                    HintCapsule(runHint ?: "", onDismiss = { runHint = null }, tone = HintTone.Info)
                }
                AnimatedVisibility(visible = tmError != null) {
                    HintCapsule(
                        tmError?.let { "语法高亮降级: $it" } ?: "",
                        onDismiss = { tmError = null },
                        tone = HintTone.Warn,
                    )
                }
                AnimatedVisibility(visible = savedAt && runHint == null) {
                    Text(
                        "已保存",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    // ---- D6/D7：导入相关弹窗（挂函数顶层，不与任何 lambda 嵌套） ----
    if (showImportSource) {
        WindowDialog(
            show = true,
            title = "导入脚本",
            summary = "选择来源；导入后先预览，确认才应用",
            onDismissRequest = { showImportSource = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        showImportSource = false
                        filePicker.launch(
                            arrayOf("text/*", "application/javascript", "application/json", "*/*")
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从文件导入") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        showImportSource = false
                        importFromClipboard()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从剪贴板导入") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        showImportSource = false
                        undoImport()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("撤销上次导入") }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            onConfirm = { applyImport(preview) },
            onDismiss = { importPreview = null },
        )
    }
}

private enum class HintTone { Info, Warn }

@Composable
private fun Modifier.tokenBg(tone: HintTone): Modifier = when (tone) {
    // t5：Warn 柔化——surfaceContainerHigh 底 + error 字（不用整片 errorContainer 粉红，不刺眼）
    HintTone.Info -> background(MiuixTheme.colorScheme.surfaceContainerHigh)
    HintTone.Warn -> background(MiuixTheme.colorScheme.surfaceContainerHigh)
}

@Composable
private fun HintCapsule(text: String, onDismiss: () -> Unit, tone: HintTone) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .tokenBg(tone)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (tone == HintTone.Warn) MiuixTheme.colorScheme.error
            else MiuixTheme.colorScheme.onSurfaceContainerHigh,
            // t7：Warn（异常消息可能带长 URL 等）限 2 行省略——错误卡不再高占屏
            maxLines = if (tone == HintTone.Warn) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // t7：Warn 同样可关闭（异常提示一次性消隐，无需等下次成功覆盖）
        Spacer(Modifier.width(8.dp))
        Text(
            "✕",
            fontSize = 12.sp,
            color = if (tone == HintTone.Warn) MiuixTheme.colorScheme.error
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .padding(start = 4.dp)
                .clickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun ToolbarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
    }
}

/** D7：导入预览弹窗（内容预览 + 来源 + 警告 + 确认/取消）。 */
@Composable
private fun ImportPreviewDialog(
    preview: ScriptImport.Result,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val head = preview.code.lineSequence().take(12).joinToString("\n")
    WindowDialog(
        show = true,
        title = "导入脚本",
        summary = preview.suggestedName + "  ·  " + preview.bytes + " 字节",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "来源：" + preview.origin,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            preview.warnings.forEach { w ->
                Text("⚠ " + w, fontSize = 11.sp, color = MiuixTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                head,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 12,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "将覆盖当前脚本（旧版已自动备份，可撤销）",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("导入并应用") }
            }
        }
    }
}