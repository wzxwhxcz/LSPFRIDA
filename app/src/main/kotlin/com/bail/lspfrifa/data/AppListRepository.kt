package com.bail.lspfrifa.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val installTime: Long,
    val updateTime: Long
)

class AppListRepository(private val context: Context) {

    suspend fun getInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        // 与 LSPFRIFA 目标复刻版实现一致：getInstalledPackages(0x8200)。
        // 该组合包含禁用组件/禁用至使用等状态，避免高版本系统漏项。
        val flags = 0x8200L
        val packages = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags.toInt())
        }

        packages.mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null

            // 原版将系统应用和“更新后的系统应用”都标为系统应用。
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val name = runCatching { appInfo.loadLabel(pm).toString() }
                .getOrDefault(pkgInfo.packageName)
            val icon = runCatching { appInfo.loadIcon(pm) }.getOrNull()

            InstalledApp(
                name = name,
                packageName = pkgInfo.packageName,
                icon = icon,
                isSystem = isSystem,
                installTime = pkgInfo.firstInstallTime,
                updateTime = pkgInfo.lastUpdateTime
            )
        }.sortedBy { it.name.lowercase() }
    }
}