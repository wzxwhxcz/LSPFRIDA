package com.bail.lspfrifa.xposed

/**
 * 第二阶段（zymbiote 式 zygote 注入）的**消费侧**：读取 gate payload 留在进程环境里的标记。
 *
 * 写侧契约见 `docs/Zygote-Injection-Design.md` §3.3（`tools/zygote` 的 gate payload 在 fork 之后、
 * 应用代码之前 setenv）；本对象是 §3.5 的模块侧只读消费方，读四个变量：
 *   - `LSPFRIDA_ZYGOTE_GATE=1`
 *   - `LSPFRIDA_ZYGOTE_GATE_NAME=<socket 名>`
 *   - `LSPFRIDA_ZYGOTE_GATE_PPID=<ppid 十进制>`
 *   - `LSPFRIDA_ZYGOTE_GATE_PROC=<完整进程名，可含 :suffix>`
 *
 * 依赖纪律：只用 java.lang（[System.getenv]）与 Kotlin 标准库，**不引 org.json / Gson**——
 * 本对象会在任何目标进程的最早期回调里运行（classloader 受限、系统进程也可能命中），
 * 多一个依赖就多一个 "NoClassDefFoundError 打断整条注入链" 的面。
 *
 * fail-open：gate 未注入 / 变量缺失 / ppid 非十进制 → `hit=false` + 字段 null，**绝不抛**。
 * gate 缺失时第一阶段行为逐字节不变（本对象不参与任何注入决策）。
 */
internal object ZygoteGate {

    /** 环境变量名（与 tools/zygote 的 gate payload、设计文档 §3.3 逐字一致）。 */
    private const val ENV_GATE = "LSPFRIDA_ZYGOTE_GATE"
    private const val ENV_NAME = "LSPFRIDA_ZYGOTE_GATE_NAME"
    private const val ENV_PPID = "LSPFRIDA_ZYGOTE_GATE_PPID"
    private const val ENV_PROC = "LSPFRIDA_ZYGOTE_GATE_PROC"

    /**
     * 一次探测结果。
     *
     * @property hit `LSPFRIDA_ZYGOTE_GATE` 恰为 `"1"`（gate payload 确实在本进程出生点跑过）
     * @property name gate 的抽象 socket 名（daemon `--socket`），缺失为 null
     * @property ppid gate 看到的父进程 pid（十进制解析），缺失/非数字为 null
     * @property proc gate 眼里的完整进程名（可含 `:suffix`），缺失为 null
     */
    data class Info(
        val hit: Boolean,
        val name: String?,
        val ppid: Int?,
        val proc: String?,
    )

    /**
     * 读一次环境变量快照（默认 [System.getenv]；测试可注入任意查找器）。
     *
     * 不缓存：调用方（LSPFRIFAModule.onModuleLoaded）负责"每进程只探一次"。未命中时
     * 不再读其余三个变量——环境里可能残留用户/调试值，不能当作 gate 证据。
     */
    fun probe(env: (String) -> String? = System::getenv): Info {
        if (env(ENV_GATE) != "1") {
            return Info(hit = false, name = null, ppid = null, proc = null)
        }
        return Info(
            hit = true,
            name = env(ENV_NAME)?.takeIf { it.isNotEmpty() },
            ppid = env(ENV_PPID)?.trim()?.toIntOrNull(),
            proc = env(ENV_PROC)?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * 本进程 `onModuleLoaded` 的一次探测结果（每进程一拍；null = 尚未探测）。
     *
     * 写入方只有 [LSPFRIFAModule.onModuleLoaded]；读取方是 [GumJsBridge.loadScript] 的前导行
     * （惰性读取，避免在 onModuleLoaded 阶段触碰 native 库——见 GumJsBridge 里该字段旁的注释）。
     */
    @Volatile
    private var last: Info? = null

    /** 记录一次探测结果（只写快照、不做任何注入决策）。 */
    fun record(info: Info) {
        last = info
    }

    /** 快照的 JSON（给 `globalThis.__lspZygoteGate` 前导行用）；尚未探测 → null。 */
    fun currentJson(): String? = last?.let { toJson(it) }

    /**
     * [Info] → JSON 字符串（手写转义，无 JSON 库）。
     *
     * 形状固定为 `{"hit":bool,"name":string|null,"ppid":number|null,"proc":string|null}`；
     * 键序固定只为便于人读与测试（JS 侧按字段取值，不依赖键序）。
     * 控制字符一律转义：产物会被**嵌进单行 JS**（`globalThis.__lspZygoteGate = <json>;`），
     * 任何裸换行/引号都会破坏那一行。
     */
    fun toJson(info: Info): String {
        val sb = StringBuilder(96)
        sb.append('{')
        sb.append("\"hit\":").append(if (info.hit) "true" else "false")
        sb.append(",\"name\":")
        appendJsonString(sb, info.name)
        sb.append(",\"ppid\":").append(info.ppid?.toString() ?: "null")
        sb.append(",\"proc\":")
        appendJsonString(sb, info.proc)
        sb.append('}')
        return sb.toString()
    }

    /** 追加一个 JSON 字符串字面量（null → 字面量 `null`）。 */
    private fun appendJsonString(sb: StringBuilder, value: String?) {
        if (value == null) {
            sb.append("null")
            return
        }
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}
