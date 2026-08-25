package com.bail.lspfrifa.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ipc.ScriptStore

/**
 * 跨进程配置共享层。
 * 目标 App 冷启动时通过 contentResolver.call 获取初始脚本、查询目标开关，
 * 并向宿主注册 Binder 通道；宿主 UI 亦可经此持久化脚本与目标包。
 *
 * 鉴权（P0-F1）：本 Provider exported=true 且可被任意第三方 App 跨进程调用，
 * 因此 call() 内按方法族强制校验调用方，禁止把 exported 改为 false（会断掉注入链）：
 *  - 宿主专属方法（save_script / remove_script / enable_target / disable_target）：
 *    仅宿主自身 uid（Binder.getCallingUid() == Process.myUid()）可调用；
 *  - 目标侧方法（is_target_enabled / get_script / register_ipc）：
 *    调用方 uid 必须等于 arg 包名经 PackageManager 解析出的 uid（每个 App 只能读写自己的槽位，
 *    root/system 等非 App uid 天然不匹配而被拒绝）；
 *    register_ipc 额外要求通道 Binder 的接口描述符为 IScriptExecutor AIDL 全限定名。
 *  - Binder.getCallingUid() 仅在 call()（Binder 线程）执行期间有效，不得外传给其他线程。
 */
class ScriptConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val packageName = arg ?: return null
        // 鉴权必须在 call() 的 Binder 线程内完成：getCallingUid() 在此刻有效
        val callingUid = Binder.getCallingUid()
        val isHostCall = callingUid == Process.myUid()

        return when (method) {
            // ===== 宿主专属：仅宿主进程自身可调用（UI 经 IpcManager/ScriptStore 落盘）=====
            "save_script" -> {
                if (!isHostCall) throw SecurityException("host-only method '$method' denied for uid $callingUid")
                val code = extras?.getString("script_content")
                if (!code.isNullOrBlank()) ScriptStore.saveScript(packageName, code)
                Bundle().apply { putBoolean("success", !code.isNullOrBlank()) }
            }
            "remove_script" -> {
                if (!isHostCall) throw SecurityException("host-only method '$method' denied for uid $callingUid")
                ScriptStore.removeScript(packageName)
                Bundle().apply { putBoolean("success", true) }
            }
            "enable_target" -> {
                if (!isHostCall) throw SecurityException("host-only method '$method' denied for uid $callingUid")
                ScriptStore.enableTarget(packageName)
                Bundle().apply { putBoolean("success", true) }
            }
            "disable_target" -> {
                if (!isHostCall) throw SecurityException("host-only method '$method' denied for uid $callingUid")
                ScriptStore.disableTarget(packageName)
                Bundle().apply { putBoolean("success", true) }
            }

            // ===== 目标侧：调用者必须就是 arg 包名对应的 App（uid 一致）=====
            "is_target_enabled" -> {
                requirePackageUidMatches(packageName, callingUid)
                Bundle().apply { putBoolean("enabled", ScriptStore.isTargetEnabled(packageName)) }
            }
            "get_script" -> {
                requirePackageUidMatches(packageName, callingUid)
                Bundle().apply { putString("script_content", ScriptStore.loadScript(packageName)) }
            }
            "register_ipc" -> {
                requirePackageUidMatches(packageName, callingUid)
                val binder: IBinder? = extras?.getBinder(IPC_BINDER_KEY)
                if (binder != null) {
                    val descriptor = runCatching { binder.getInterfaceDescriptor() }
                        .getOrElse { e -> throw SecurityException("register_ipc: binder descriptor unreadable", e) }
                    // 拒绝伪 Binder / 非 IScriptExecutor 接口（防目标传入任意 Binder 触发 registerTarget 副作用）
                    if (descriptor != EXECUTOR_DESCRIPTOR) {
                        throw SecurityException(
                            "register_ipc: unexpected descriptor '$descriptor' (expect '$EXECUTOR_DESCRIPTOR')"
                        )
                    }
                    IpcManager.registerTarget(packageName, binder)
                }
                Bundle().apply { putBoolean("success", binder != null) }
            }

            else -> null
        }
    }

    private companion object {
        /** register_ipc 通道 Binder 的接口描述符（AIDL 全限定名，见 IScriptExecutor.aidl）。 */
        const val EXECUTOR_DESCRIPTOR = "com.bail.lspfrifa.ipc.IScriptExecutor"
        const val IPC_BINDER_KEY = "ipc_binder"
    }

    /**
     * caller-arg 匹配：arg = 包名，PackageManager 解析出的 uid 必须等于调用方 uid。
     * 包不存在（NameNotFoundException）、context 缺失或 uid 不一致（含 root/system 等非 App uid）一律拒绝。
     */
    private fun requirePackageUidMatches(packageName: String, callingUid: Int) {
        val resolvedUid = try {
            @Suppress("DEPRECATION") // 保留 flags=0 语义；compileSdk 37 下 getPackageUid(String,int) 已标记废弃
            context?.packageManager?.getPackageUid(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw SecurityException("unknown package '$packageName' for uid $callingUid", e)
        }
        if (resolvedUid == null || resolvedUid != callingUid) {
            throw SecurityException(
                "uid mismatch for '$packageName': caller=$callingUid resolved=$resolvedUid"
            )
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
