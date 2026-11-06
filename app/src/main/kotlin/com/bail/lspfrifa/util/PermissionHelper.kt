package com.bail.lspfrifa.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限自检与引导（2026-11-06 真机反馈）：
 * 应用从不在代码里申请任何运行时权限，manifest 也只声明了 QUERY_ALL_PACKAGES
 * （normal 级，装机即授，不弹框），导致用户在设置里看不到“应用列表”权限被申请过，
 * 且 MIUI 的自启动/电池优化等特殊权限本来就不会自动弹框。
 * 这里做三件事：① 实核权限是否真的生效（不只看声明）；② 在设置页集中展示状态；
 * ③ 点一行就跳到对应的系统设置页（MIUI 上这些权限只能手动开）。
 */
object PermissionHelper {

    /** 应用列表（QUERY_ALL_PACKAGES）是否真的生效：声明已授 + 至少能看到一个第三方应用。 */
    fun queryAllPackagesEffective(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.QUERY_ALL_PACKAGES)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        // 功能探针：权限被 MIUI 等拦截时，getInstalledApplications 只返回自身和少量系统应用，
        // 第三方应用一个都看不到。真机上一定装有第三方应用，见不到即视为未生效。
        return runCatching {
            context.packageManager.getInstalledApplications(0)
                .any { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        }.getOrDefault(false)
    }

    /** 悬浮窗（显示在其他应用上方）。 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 电池优化白名单：忽略后 MIUI 才不会在后台杀掉模块进程（provider 随之不可见）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 跳到本应用的详情页（权限管理 / 自启动都在这里，MIUI 路径各版本略有出入）。 */
    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 跳到悬浮窗授权页。 */
    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 直接弹系统对话框：请求忽略电池优化（用户点“允许”即进白名单）。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
