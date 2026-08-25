package com.bail.lspfrifa.ipc

import android.content.Context
import android.content.SharedPreferences
import com.bail.lspfrifa.FrameworkState
import io.github.libxposed.service.XposedService

/**
 * 宿主侧脚本与目标包配置的持久化存储（R1.1：双通道）。
 *
 * 通道设计（重构目标态=框架远程 prefs 为主，本地为兜底）：
 * - 远程通道：框架库 `XposedService.getRemotePreferences(REMOTE_GROUP)`（框架数据库，宿主可写、
 *   模块（目标进程）经 XposedInterface.getRemotePreferences 只读——不受宿主进程存活影响）；
 * - 本地通道：SharedPreferences 常驻（未激活/框架不可用时兜底，也是升级迁移前的存量数据源）。
 * - 写路径：双写（远程可用则写远程；本地始终写，保证两通道一致/可回退）；
 * - 读路径：远程优先（远程有值即用，含"空集=全部未启用"语义），远程无值回退本地。
 * - 键约定（与模块侧 LSPFRIFAModule 严格一致）：
 *     GROUP="lspfrifa_config"；"enabled"=StringSet(包名)；"script.<pkg>"=String(脚本内容)。
 *
 * 由 LSPFRIFAApplication.onCreate 调用 [init]；跨进程只经 ScriptConfigProvider 访问。
 */
object ScriptStore {

    private const val PREFS_SCRIPTS = "lspfrifa_scripts"
    private const val PREFS_TARGETS = "lspfrifa_targets"

    /** 远程配置组名（与 LSPFRIFAModule.RemoteConfig/GROUP 常量一致）。 */
    const val REMOTE_GROUP = "lspfrifa_config"
    private const val KEY_ENABLED = "enabled"
    private fun scriptKey(pkg: String) = "script.$pkg"

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

    private fun remotePrefs(): SharedPreferences? = try {
        FrameworkState.current()?.getRemotePreferences(REMOTE_GROUP)
    } catch (_: Throwable) {
        null
    }

    // ---- 脚本 ----

    fun saveScript(packageName: String, code: String) {
        scriptsPrefs()?.edit()?.putString(packageName, code)?.apply()
        remotePrefs()?.edit()?.putString(scriptKey(packageName), code)?.apply()
    }

    fun loadScript(packageName: String): String? =
        remotePrefs()?.getString(scriptKey(packageName), null)
            ?: scriptsPrefs()?.getString(packageName, null)

    fun removeScript(packageName: String) {
        scriptsPrefs()?.edit()?.remove(packageName)?.apply()
        remotePrefs()?.edit()?.remove(scriptKey(packageName))?.apply()
    }

    // ---- 启用开关 ----

    fun enableTarget(packageName: String) {
        val set = targetsPrefs()?.getStringSet(KEY_ENABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(packageName)
        targetsPrefs()?.edit()?.putStringSet(KEY_ENABLED, set)?.apply()
        remotePrefs()?.edit()?.putStringSet(KEY_ENABLED, set)?.apply()
    }

    fun disableTarget(packageName: String) {
        val set = targetsPrefs()?.getStringSet(KEY_ENABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(packageName)
        targetsPrefs()?.edit()?.putStringSet(KEY_ENABLED, set)?.apply()
        remotePrefs()?.edit()?.putStringSet(KEY_ENABLED, set)?.apply()
    }

    fun isTargetEnabled(packageName: String): Boolean {
        val remoteSet = remotePrefs()?.getStringSet(KEY_ENABLED, null)
        if (remoteSet != null) return remoteSet.contains(packageName)
        return targetsPrefs()?.getStringSet(KEY_ENABLED, emptySet())?.contains(packageName) == true
    }

    fun enabledTargets(): Set<String> {
        val remoteSet = remotePrefs()?.getStringSet(KEY_ENABLED, null)
        if (remoteSet != null) return remoteSet
        return targetsPrefs()?.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()
    }
}
