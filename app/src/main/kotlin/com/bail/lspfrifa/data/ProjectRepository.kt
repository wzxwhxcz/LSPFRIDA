package com.bail.lspfrifa.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户已添加项目数据模型（持久化字段）。
 * icon 不序列化：Drawable 不可 JSON 化，显示时按 packageName 实时解析。
 */
data class AddedProject(
    val packageName: String,
    val appName: String,
    val isEnabled: Boolean = true,
)

/**
 * 已添加项目的持久化仓库（SharedPreferences + JSON，零新增依赖，与 ScriptStore 同构）。
 * 只保存用户主动添加的项目，不绑定系统全部应用列表。
 */
class ProjectRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("lspfrifa_projects", Context.MODE_PRIVATE)

    fun load(): List<AddedProject> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val pkg = obj.optString("packageName")
                if (pkg.isBlank()) return@mapNotNull null
                AddedProject(
                    packageName = pkg,
                    appName = obj.optString("appName", pkg),
                    isEnabled = obj.optBoolean("isEnabled", true),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(projects: List<AddedProject>) {
        val array = JSONArray()
        projects.forEach { p ->
            array.put(
                JSONObject()
                    .put("packageName", p.packageName)
                    .put("appName", p.appName)
                    .put("isEnabled", p.isEnabled)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "added_projects"
    }
}
