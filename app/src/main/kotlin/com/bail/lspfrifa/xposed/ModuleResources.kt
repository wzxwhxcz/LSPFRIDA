package com.bail.lspfrifa.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.util.Log

/**
 * 模块自身的字符串资源（i18n）。
 *
 * 模块代码跑在目标 App 进程里：那里的 Context 属于目标包，`context.getString(R.string.…)`
 * 会拿模块的 R 常量去目标包的资源表解析 —— id 不对应，直接 Resources.NotFoundException。
 * 故用框架给的模块 ApplicationInfo 单独取一份模块 Resources。
 */
object ModuleResources {
    private const val TAG = "LSPFRIFA-ModuleRes"

    @Volatile
    private var res: Resources? = null

    fun init(context: Context, moduleInfo: ApplicationInfo?) {
        if (res != null || moduleInfo == null) return
        res = runCatching { context.packageManager.getResourcesForApplication(moduleInfo) }
            .onFailure { Log.w(TAG, "module resources unavailable: " + it.message) }
            .getOrNull()
    }

    /** 取模块字符串；资源不可用（极早期/框架异常）返回 null，调用方跳过展示。 */
    fun get(id: Int, vararg args: Any): String? = res?.getString(id, *args)
}
