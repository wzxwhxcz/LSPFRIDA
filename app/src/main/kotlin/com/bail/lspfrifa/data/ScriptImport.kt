package com.bail.lspfrifa.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.bail.lspfrifa.ipc.ScriptStore

/**
 * D6/D7：脚本导入（文件 / 剪贴板 / 分享）。
 *
 * 设计要点（第一性原理）：
 * 1. 零新增权限：SAF(OpenDocument) / 剪贴板 / ACTION_SEND 均无需 INTERNET 等权限；
 *    刻意不做 URL 下载（护宿主攻击面，见设计文档 D6）。
 * 2. 校验前置：真实语法校验只能靠注入回执（宿主不加载 gumjs，见 F4），
 *    此处只做成本极低的硬拦截：二进制 / 空文件 / 超限 / 括号粗配。
 * 3. 导入即任意代码执行：故调用方必须先展示预览与来源，用户确认后才应用（D7）。
 */
object ScriptImport {

    private const val TAG = "LSPFRIFA-Import"

    /** 小于此值不可能是有效脚本。 */
    private const val MIN_BYTES = 1

    /**
     * 可导入的字节硬上限。
     *
     * A2（2026-10-07）：**直接对齐下发上限**（`ScriptStore.MAX_SCRIPT_BYTES`）。
     * 原为独立的 1MB —— 造成"导入成功但下发失败"的中间带：用户在导入阶段收到成功，
     * 到"应用/运行"时才看到 400KB 超限提示。改为单一真相源后两者不再可能漂移。
     *
     * 注：`const val` 引用另一 `const val` 是编译期内联，无运行时依赖。
     */
    private const val HARD_LIMIT_BYTES = ScriptStore.MAX_SCRIPT_BYTES

    /** 来源标记（与 ScriptLibraryStore 的 origin 字段约定一致）。 */
    object Origin {
        const val CLIP = "clip"
        const val MANUAL = "manual"
        fun file(uri: Uri) = "file:" + uri.toString()
        fun share(pkg: String?) = "share:" + (pkg ?: "unknown")
    }

    /** 导入结果：内容 + 建议名 + 警告（警告不阻断，只提示）。 */
    data class Result(
        val code: String,
        val suggestedName: String,
        val origin: String,
        val warnings: List<String>,
        val bytes: Int,
    )

    sealed class Failure {
        data object Empty : Failure()
        data object Binary : Failure()
        data object TooLarge : Failure()
        data class ReadError(val message: String) : Failure()
    }

    /** 导入结果类型（避免异常控制流）。 */
    sealed class Outcome {
        data class Ok(val result: Result) : Outcome()
        data class Failed(val reason: Failure) : Outcome()
    }

    /** 从 SAF Uri 读取并校验。 */
    fun readUri(context: Context, uri: Uri, fromPackage: String? = null): Outcome {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Outcome.Failed(Failure.ReadError("无法打开输入流"))
            val name = displayName(context, uri) ?: "imported.js"
            val origin = if (fromPackage != null) Origin.share(fromPackage) else Origin.file(uri)
            decode(bytes, name, origin)
        } catch (t: Throwable) {
            Log.w(TAG, "读取失败: " + t.message)
            Outcome.Failed(Failure.ReadError(t.message ?: "读取异常"))
        }
    }

    /** 从文本（剪贴板 / 分享 EXTRA_TEXT）导入。 */
    fun readText(text: String?, suggestedName: String, origin: String): Outcome {
        if (text == null) return Outcome.Failed(Failure.Empty)
        return decode(text.toByteArray(Charsets.UTF_8), suggestedName, origin)
    }

    // ---- 内部 ----

    private fun decode(bytes: ByteArray, name: String, origin: String): Outcome {
        if (bytes.size < MIN_BYTES) return Outcome.Failed(Failure.Empty)
        if (bytes.size > HARD_LIMIT_BYTES) return Outcome.Failed(Failure.TooLarge)

        // 二进制嗅探：前 4KB 出现 NUL 一律拒（JS 源码不可能含裸 NUL）
        val probe = minOf(bytes.size, 4096)
        for (i in 0 until probe) {
            if (bytes[i] == 0.toByte()) return Outcome.Failed(Failure.Binary)
        }

        var text = String(bytes, Charsets.UTF_8)
        val warnings = mutableListOf<String>()

        if (text.startsWith(BOM)) {
            text = text.substring(1)
            warnings.add("已去除 UTF-8 BOM")
        }
        val bad = text.count { it == REPLACEMENT }
        if (bad > 0) warnings.add("检测到 " + bad + " 个无法解码字符，可能不是 UTF-8")

        val brace = balance(text)
        if (brace != 0) warnings.add("花括号不配平（差 " + brace + "），文件可能被截断")

        if (text.contains("while (true)") || text.contains("while(true)")) {
            warnings.add("包含 while(true) 循环：模块死循环将僵死整个 JS 线程（无抢占）")
        }

        return Outcome.Ok(
            Result(
                code = text,
                suggestedName = name,
                origin = origin,
                warnings = warnings,
                bytes = bytes.size,
            )
        )
    }

    /**
     * 花括号差值。排除字符串、模板串与注释 —— 本项目在 cpp 括号校验上踩过
     * 字面量假阳性（'{' 被计入），此处一次性处理干净。
     */
    private fun balance(src: String): Int {
        var depth = 0
        var i = 0
        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else NUL_CHAR
            if (lineComment) {
                if (c == NEWLINE) lineComment = false
            } else if (blockComment) {
                if (c == STAR && next == SLASH) {
                    blockComment = false
                    i++
                }
            } else if (quote != null) {
                if (c == BACKSLASH) i++ else if (c == quote) quote = null
            } else if (c == SLASH && next == SLASH) {
                lineComment = true
                i++
            } else if (c == SLASH && next == STAR) {
                blockComment = true
                i++
            } else if (c == DQUOTE || c == SQUOTE || c == BACKTICK) {
                quote = c
            } else if (c == LBRACE) {
                depth++
            } else if (c == RBRACE) {
                depth--
            }
            i++
        }
        return depth
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }

    // ---- 字符常量（集中声明，避免散落的转义噪声） ----
    private val BOM = "\uFEFF"
    private val REPLACEMENT = '\uFFFD'
    private val NUL_CHAR = '\u0000'
    private val NEWLINE = '\n'
    private val STAR = '*'
    private val SLASH = '/'
    private val BACKSLASH = '\\'
    private val DQUOTE = '"'
    private val SQUOTE = '\''
    private val BACKTICK = '`'
    private val LBRACE = '{'
    private val RBRACE = '}'
}