package com.bail.lspfrifa.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bail.lspfrifa.R
import com.bail.lspfrifa.data.AddedProject
import com.bail.lspfrifa.data.ScriptImport
import com.bail.lspfrifa.data.ScriptLibraryStore
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ipc.ScriptStore
import com.bail.lspfrifa.ui.component.MiuixPageBackground
import com.bail.lspfrifa.ui.component.UiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * D6 闭环落地页：外部「分享进入」的脚本要先决定去向。
 *
 * 为什么必须有这一页（第一性原理）：
 * 分享进来的脚本**没有目标上下文** —— 系统只知道"分享一个 js 文件"，
 * 不知道用户想注入哪个 App。所以两种去向必须由用户选：
 *   ① 仅入库（当作素材，之后在编辑器里挑）
 *   ② 入库 + 应用到某个已添加的目标（最常见意图，一步到位）
 *
 * `ACTION_SEND` 的 Uri 授权会在 Intent 之后失效，故内容已在 MainActivity 捕获并解析完毕
 * （见 PendingImport 注释），本页只消费已解析结果，不碰原始 Uri。
 */
@Composable
fun ImportTargetScreen(
    preview: ScriptImport.Result,
    projects: List<AddedProject>,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    /** 入库（总是执行，去重）+ 可选应用到目标。 */
    fun commit(targetPkg: String?) {
        if (busy) return
        busy = true
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                ScriptLibraryStore.add(preview.suggestedName, preview.code, preview.origin).first
            }
            if (targetPkg == null) {
                busy = false
                onDone(context.getString(R.string.import_saved_to_library, preview.suggestedName))
                return@launch
            }
            // 应用到目标：备份旧脚本 → 写新脚本（四态结果如实反馈）
            withContext(Dispatchers.IO) {
                IpcManager.backupScript(targetPkg, IpcManager.loadScript(targetPkg))
            }
            val saved = withContext(Dispatchers.IO) { IpcManager.saveScript(targetPkg, preview.code) }
            busy = false
            val msg = when (saved) {
                ScriptStore.SaveResult.OK -> context.getString(R.string.import_applied, targetPkg, id.takeLast(6))
                ScriptStore.SaveResult.LOCAL_ONLY -> context.getString(R.string.import_local_only)
                ScriptStore.SaveResult.SIZE_EXCEEDED -> context.getString(R.string.import_size_exceeded)
                ScriptStore.SaveResult.REMOTE_REJECTED -> context.getString(R.string.import_remote_rejected)
            }
            hint = msg
            onDone(msg)
        }
    }

    val head = preview.code.lineSequence().take(8).joinToString("\n")

    Scaffold(
        containerColor = MiuixPageBackground(),
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.import_title),
                subtitle = preview.suggestedName,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = UiTokens.PagePadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            // 内容概览
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                Text(
                    stringResource(R.string.import_meta, preview.bytes.toString(), preview.origin),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                preview.warnings.forEach { w ->
                    Spacer(Modifier.height(4.dp))
                    Text("⚠ " + w, fontSize = 11.sp, color = MiuixTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    head,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    maxLines = 8,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.import_apply_to), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))

            if (projects.isEmpty()) {
                Text(
                    stringResource(R.string.import_empty_projects),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                projects.forEach { project ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = UiTokens.CardRadius,
                        onClick = { if (!busy) commit(project.packageName) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(project.appName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    project.packageName,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                stringResource(
                                    if (project.isEnabled) R.string.import_enabled else R.string.import_disabled,
                                ),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { commit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (busy) stringResource(R.string.import_busy) else stringResource(R.string.import_store_only),
                )
            }
            hint?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}