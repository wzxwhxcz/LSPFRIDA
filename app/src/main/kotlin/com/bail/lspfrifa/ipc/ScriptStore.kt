package com.bail.lspfrifa.ipc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bail.lspfrifa.FrameworkState

/**
 * 宿主侧脚本与目标包配置的持久化存储。
 *
 * ## 通道（D4，2026-08-26 三查后冻结）
 * libxposed 的远程文件 API 是**只读**的（listRemoteFiles / openRemoteFile；宿主侧 XposedService
 * 亦无写入接口）→ **remote prefs 是唯一能下发到目标进程的通道**。
 * 本地 SharedPreferences 仅作兜底与迁移存量，目标进程读不到。
 *
 * ## 分组（D15，2026-08-26 新增）
 * RemotePreferences 每次读写都搬运**整个 group** 的 map（实核：读 requestRemotePreferences
 * 返回整组 Bundle、写 updateRemotePreferences 打包全量 mPut/mDelete），而单次 Binder 事务约 1MB
 * —— 脚本正文若与 enabled/hint 同组，会随目标数量线性撑爆。因此：
 *
 *     "lspfrifa_config"         只放小数据：enabled / hint_inject / scriptgroup.<pkg> 指针
 *     "lspfrifa_s_<pkgSafe>"    每目标一个：code（脚本正文）/ modules
 *
 * ## 写入语义
 * apply() 的失败只打日志（Failed to commit changes to framework），调用方无感；
 * 故脚本写路径改用 commit() 取得同步布尔结果，失败即上报 UI（[SaveResult]）。
 *
 * 由 LSPFRIFAApplication.onCreate 调用 [init]；跨进程只经 ScriptConfigProvider 访问。
 */
object ScriptStore {

    private const val TAG = "LSPFRIFA-ScriptStore"

    private const val PREFS_SCRIPTS = "lspfrifa_scripts"
    private const val PREFS_TARGETS = "lspfrifa_targets"
    private const val PREFS_META = "lspfrifa_meta"

    /** 小数据组名（与模块侧 LSPFRIFAModule 常量严格一致，不得改）。 */
    const val REMOTE_GROUP = "lspfrifa_config"

    private const val KEY_ENABLED = "enabled"

    /** 旧键（升级前数据）：仅读不写，首次保存后自然迁移。 */
    private fun legacyScriptKey(pkg: String) = "script." + pkg

    /** 指针键：pkg -> 专属 group 名。 */
    private fun scriptGroupPointerKey(pkg: String) = "scriptgroup." + pkg

    // 专属 group 内的键
    private const val KEY_CODE = "code"
    private const val KEY_MODULES = "modules"

    /** 脚本正文上限（D5：group 级约束；实测回填前取保守值，见设计文档 §4#5）。 */
    const val MAX_SCRIPT_BYTES = 400 * 1024

    // A2（2026-10-07）：此处原有 `REMOTE_WRITE_BUDGET = 700 * 1024`，已删除。
    // 原因：saveScript 先查 MAX_SCRIPT_BYTES（400KB）再查它（700KB），
    //   400 < 700 ⇒ 该检查**永远不可达**，是死代码（全项目仅 2 处引用：定义 + 该检查）。
    // 远端写入失败的真实兜底是下方的 commit() 返回值判定（codeOk），不依赖此预算。

    /** 保存结果：供导入/编辑器 UI 给出准确反馈。 */
    enum class SaveResult {
        /** 本地 + 远程均已写入。 */
        OK,
        /** 仅本地写入（框架未激活/未连接），目标进程暂不可见。 */
        LOCAL_ONLY,
        /** 超限，未写入任何通道。 */
        SIZE_EXCEEDED,
        /** 远程写入被框架拒绝（Binder 事务失败）。 */
        REMOTE_REJECTED,
    }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---- 通道获取 ----

    private fun scriptsPrefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_SCRIPTS, Context.MODE_PRIVATE)

    private fun targetsPrefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_TARGETS, Context.MODE_PRIVATE)

    private fun metaPrefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_META, Context.MODE_PRIVATE)

    /** 取指定 group 的远程配置；框架不可用时返回 null。 */
    private fun remotePrefs(group: String): SharedPreferences? = try {
        FrameworkState.current()?.getRemotePreferences(group)
    } catch (_: Throwable) {
        null
    }

    /** D15：包名 -> 专属 group 名（包名字符集本就安全，无需 hash）。 */
    fun scriptGroupOf(packageName: String): String =
        "lspfrifa_s_" + packageName.replace('.', '_').replace('-', '_')

    /** 是否已连接框架（决定远程写入是否可用）。 */
    fun isRemoteAvailable(): Boolean = remotePrefs(REMOTE_GROUP) != null

    // ---- 脚本 ----

    /**
     * 保存脚本（D15：正文进专属 group，指针进小数组）。
     *
     * 同步顺序：本地镜像 -> 专属 group 正文 -> config 组指针。
     * 远程任一步失败即返回 [SaveResult.REMOTE_REJECTED]（本地已落，UI 可提示等待框架恢复）。
     * 未激活时返回 [SaveResult.LOCAL_ONLY]。
     */
    fun saveScript(packageName: String, code: String): SaveResult {
        val bytes = code.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_SCRIPT_BYTES) {
            Log.e(TAG, "脚本超限拒绝: pkg=" + packageName + " bytes=" + bytes + " limit=" + MAX_SCRIPT_BYTES)
            return SaveResult.SIZE_EXCEEDED
        }
        // 本地镜像（永远写；目标进程读不到，仅作迁移与兜底）
        scriptsPrefs()?.edit()?.putString(packageName, code)?.apply()

        val configPrefs = remotePrefs(REMOTE_GROUP)
        if (configPrefs == null) {
            Log.w(TAG, "框架未连接，仅本地保存: pkg=" + packageName + " bytes=" + bytes)
            return SaveResult.LOCAL_ONLY
        }

        val group = scriptGroupOf(packageName)
        val groupPrefs = remotePrefs(group)
        if (groupPrefs == null) {
            Log.e(TAG, "专属 group 不可用: group=" + group)
            return SaveResult.REMOTE_REJECTED
        }

        // A2（2026-10-07）：此处原有 `if (bytes > REMOTE_WRITE_BUDGET) { ... }` 检查，已删除。
        // 原因：上方第 114 行已按 MAX_SCRIPT_BYTES(400KB) 拦截，而原预算为 700KB ⇒ 永远不可达。
        // 远端写入失败的真实兜底是下方的 commit() 返回值判定。

        // commit() 同步返回框架是否接受（apply() 失败无感，不用）
        val codeOk = runCatching { groupPrefs.edit().putString(KEY_CODE, code).commit() }
            .getOrDefault(false)
        if (!codeOk) {
            Log.e(TAG, "远程正文写入被拒绝: group=" + group + " len=" + code.length)
            return SaveResult.REMOTE_REJECTED
        }

        val pointerOk = runCatching {
            configPrefs.edit().putString(scriptGroupPointerKey(packageName), group).commit()
        }.getOrDefault(false)
        if (!pointerOk) {
            Log.e(TAG, "远程指针写入被拒绝: pkg=" + packageName + " group=" + group)
            return SaveResult.REMOTE_REJECTED
        }

        Log.i(TAG, "脚本已保存: pkg=" + packageName + " bytes=" + bytes + " group=" + group)
        return SaveResult.OK
    }

    /**
     * 读取脚本。顺序（D15 兼容链）：
     * 1. config 组指针 -> 专属 group code（新格式）
     * 2. config 组旧键 script.<pkg>（升级前数据）
     * 3. 本地镜像（框架未连接时兜底；目标进程读不到，仅 UI 展示）
     */
    fun loadScript(packageName: String): String? {
        val configPrefs = remotePrefs(REMOTE_GROUP)
        val pointer = configPrefs?.getString(scriptGroupPointerKey(packageName), null)
        if (!pointer.isNullOrBlank()) {
            val remote = remotePrefs(pointer)?.getString(KEY_CODE, null)
            if (!remote.isNullOrBlank()) return remote
        }
        val legacy = configPrefs?.getString(legacyScriptKey(packageName), null)
        if (!legacy.isNullOrBlank()) return legacy
        return scriptsPrefs()?.getString(packageName, null)
    }

    /** 移除脚本：专属 group 正文 + config 组指针/旧键 + 本地镜像 + 熔断计数。 */
    fun removeScript(packageName: String) {
        scriptsPrefs()?.edit()?.remove(packageName)?.apply()
        val group = scriptGroupOf(packageName)
        remotePrefs(group)?.edit()?.remove(KEY_CODE)?.remove(KEY_MODULES)?.apply()
        remotePrefs(REMOTE_GROUP)?.edit()
            ?.remove(scriptGroupPointerKey(packageName))
            ?.remove(legacyScriptKey(packageName))
            ?.apply()
        clearFailCount(packageName)
    }

    // ---- 导入备份 / 撤销（D7；只存本地，不占 Binder） ----

    private fun backupKey(packageName: String, ts: Long) = "bak." + packageName + "." + ts

    private fun latestBackupKey(packageName: String) = "bakLatest." + packageName

    /** 导入前备份当前脚本；返回备份键（供撤销）。code 为空不备份。 */
    fun backupScript(packageName: String, code: String?): String? {
        if (code.isNullOrBlank()) return null
        val ts = System.currentTimeMillis()
        val key = backupKey(packageName, ts)
        metaPrefs()?.edit()?.putString(key, code)?.apply()
        metaPrefs()?.edit()?.putLong(latestBackupKey(packageName), ts)?.apply()
        return key
    }

    /** 最近一次备份的 (键, 内容)；无备份返回 null。 */
    fun latestBackup(packageName: String): Pair<String, String>? {
        val ts = metaPrefs()?.getLong(latestBackupKey(packageName), -1L) ?: -1L
        if (ts <= 0) return null
        val code = metaPrefs()?.getString(backupKey(packageName, ts), null) ?: return null
        return backupKey(packageName, ts) to code
    }

    /** 撤销最近一次导入：回滚脚本并清除该备份键。返回是否成功。 */
    fun undoImport(packageName: String): Boolean {
        val pair = latestBackup(packageName) ?: return false
        val key = pair.first
        val code = pair.second
        if (saveScript(packageName, code) == SaveResult.SIZE_EXCEEDED) return false
        metaPrefs()?.edit()?.remove(key)?.remove(latestBackupKey(packageName))?.apply()
        Log.i(TAG, "已撤销导入: pkg=" + packageName)
        return true
    }

    // ---- 模块集（D8/D9：正文在专属 group，模块 id 集合同组） ----

    fun setModules(packageName: String, moduleIds: Set<String>) {
        remotePrefs(scriptGroupOf(packageName))?.edit()?.putStringSet(KEY_MODULES, moduleIds)?.apply()
    }

    fun enabledModules(packageName: String): Set<String> =
        remotePrefs(scriptGroupOf(packageName))?.getStringSet(KEY_MODULES, emptySet()) ?: emptySet()

    // ---- 熔断（D12）：计数 + 闸 ----
    //
    // 设计要点（第一性原理）：坏脚本会让目标每次启动都受影响，而目标进程**无法自写状态**
    // （remote prefs 在被 hook 的 app 里只读）。故：
    //   · 计数由宿主维护（它通过日志通道观察失败）；
    //   · 闸（circuitOpen）写进 remote prefs —— 这是目标进程唯一能读到的持久状态；
    //   · register_ipc 会拉起宿主进程，因此宿主通常在场，观察链成立。

    private fun failCountKey(packageName: String) = "failCount." + packageName

    private fun circuitKey(packageName: String) = "circuitOpen." + packageName

    /** 连续失败阈值：达此值自动断闸（D12 = 3）。 */
    const val CIRCUIT_THRESHOLD = 3

    /** 合闸（允许再次尝试注入）：同时清失败计数。 */
    fun closeCircuit(packageName: String) {
        clearFailCount(packageName)
        remotePrefs(REMOTE_GROUP)?.edit()?.remove(circuitKey(packageName))?.apply()
        metaPrefs()?.edit()?.remove(circuitKey(packageName))?.apply()
        Log.i(TAG, "熔断已合闸: pkg=" + packageName)
    }

    /** 断闸（停止对该目标的注入尝试）。写 remote prefs 以便目标进程下次启动读到。 */
    fun openCircuit(packageName: String) {
        remotePrefs(REMOTE_GROUP)?.edit()?.putBoolean(circuitKey(packageName), true)?.apply()
        metaPrefs()?.edit()?.putBoolean(circuitKey(packageName), true)?.apply()
        Log.w(TAG, "熔断已断闸: pkg=" + packageName)
    }

    /** 闸是否断着（remote 优先：那是目标进程的真实依据；本地兜底供未激活时 UI 显示）。 */
    fun isCircuitOpen(packageName: String): Boolean {
        val remote = remotePrefs(REMOTE_GROUP)?.getBoolean(circuitKey(packageName), false)
        if (remote != null && remote) return true
        return metaPrefs()?.getBoolean(circuitKey(packageName), false) == true
    }

    fun failCount(packageName: String): Int = metaPrefs()?.getInt(failCountKey(packageName), 0) ?: 0

    /** 累加失败次数，返回新值。 */
    fun bumpFailCount(packageName: String): Int {
        val next = failCount(packageName) + 1
        metaPrefs()?.edit()?.putInt(failCountKey(packageName), next)?.apply()
        return next
    }

    fun clearFailCount(packageName: String) {
        metaPrefs()?.edit()?.remove(failCountKey(packageName))?.apply()
    }

    // ---- 启用开关（只写小数据；脚本已移出，本组永不携带正文） ----

    fun enableTarget(packageName: String) {
        val set = readEnabledLocal().toMutableSet()
        set.add(packageName)
        writeEnabled(set)
    }

    fun disableTarget(packageName: String) {
        val set = readEnabledLocal().toMutableSet()
        set.remove(packageName)
        writeEnabled(set)
    }

    private fun readEnabledLocal(): Set<String> =
        targetsPrefs()?.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()

    private fun writeEnabled(set: Set<String>) {
        targetsPrefs()?.edit()?.putStringSet(KEY_ENABLED, set)?.apply()
        remotePrefs(REMOTE_GROUP)?.edit()?.putStringSet(KEY_ENABLED, set)?.apply()
    }

    fun isTargetEnabled(packageName: String): Boolean {
        val remoteSet = remotePrefs(REMOTE_GROUP)?.getStringSet(KEY_ENABLED, null)
        if (remoteSet != null) return remoteSet.contains(packageName)
        return readEnabledLocal().contains(packageName)
    }

    fun enabledTargets(): Set<String> {
        val remoteSet = remotePrefs(REMOTE_GROUP)?.getStringSet(KEY_ENABLED, null)
        return remoteSet ?: readEnabledLocal()
    }
}