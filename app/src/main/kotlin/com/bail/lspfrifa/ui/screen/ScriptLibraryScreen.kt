package com.bail.lspfrifa.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * D8/D9：脚本库管理页（导入的脚本在此被真正管理起来）。
 *
 * 为什么必须有（第一性原理）：没有它，导入只能进不能出 ——
 * 用户导入 10 个脚本后无从知道有哪些、也无从删除或复用，库是死的。
 *
 * 本期只做「查看 / 应用 / 删除」（最小闭环）；重命名与模块化拼装留待后续
 * （后者依赖多模块工作流，已明确暂缓）。
 */
@Composable
fun ScriptLibraryScreen(
    targetPackage: String?,
    targetName: String?,
    onBack: () -> Unit,
) {
    var entries by remember { mutableStateOf(ScriptLibraryStore.list()) }
    var pendingDelete by remember { mutableStateOf<ScriptLibraryStore.Entry?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun reload() {
        entries = ScriptLibraryStore.list()
    }

    /** 把库内脚本应用到当前目标（若从详情页进入则 targetPackage 非空）。 */
    fun applyToTarget(entry: ScriptLibraryStore.Entry) {
        val pkg = targetPackage
        if (pkg.isNullOrBlank()) {
            hint = context.getString(R.string.lib_need_project)
            return
        }
        scope.launch {
            val code = withContext(Dispatchers.IO) { ScriptLibraryStore.code(entry.id) }
            if (code.isNullOrBlank()) {
                hint = context.getString(R.string.lib_content_missing)
                return@launch
            }
            withContext(Dispatchers.IO) { IpcManager.backupScript(pkg, IpcManager.loadScript(pkg)) }
            val saved = withContext(Dispatchers.IO) { IpcManager.saveScript(pkg, code) }
            hint = when (saved) {
                ScriptStore.SaveResult.OK -> context.getString(R.string.lib_applied, targetName ?: pkg)
                ScriptStore.SaveResult.LOCAL_ONLY -> context.getString(R.string.lib_local_only)
                ScriptStore.SaveResult.SIZE_EXCEEDED -> context.getString(R.string.lib_size_exceeded)
                ScriptStore.SaveResult.REMOTE_REJECTED -> context.getString(R.string.lib_remote_rejected)
            }
        }
    }

    Scaffold(
        containerColor = MiuixPageBackground(),
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.lib_title),
                subtitle = if (entries.isEmpty()) stringResource(R.string.lib_subtitle_empty)
                else stringResource(R.string.lib_subtitle_count, entries.size),
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
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = UiTokens.PagePadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            hint?.let {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Spacer(Modifier.height(10.dp))
            }

            if (entries.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    Text(stringResource(R.string.lib_empty_title), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.lib_empty_hint),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                entries.forEach { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = UiTokens.CardRadius,
                        insideMargin = PaddingValues(
                            horizontal = UiTokens.CardMarginH,
                            vertical = 12.dp,
                        ),
                        onClick = { applyToTarget(entry) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    entry.origin,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "#" + entry.sha256.take(8),
                                    fontSize = 10.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { pendingDelete = entry }) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { entry ->
        val deletedMsg = stringResource(R.string.lib_deleted, entry.name)
        WindowDialog(
            show = true,
            title = stringResource(R.string.lib_delete_title),
            summary = stringResource(R.string.lib_delete_summary, entry.name),
            onDismissRequest = { pendingDelete = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { pendingDelete = null }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            ScriptLibraryStore.remove(entry.id)
                            pendingDelete = null
                            reload()
                            hint = deletedMsg
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.common_delete)) }
                }
            }
        }
    }
}
