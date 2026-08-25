package com.bail.lspfrifa.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bail.lspfrifa.ipc.IpcManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「项目」模块状态管理：
 * 维护用户已添加项目列表 addedProjectList，支持增删与启用开关，全部持久化。
 * 不在此处加载系统全部应用（选择页单独异步扫描）。
 */
class ProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

    private val _addedProjects = MutableStateFlow(repository.load())
    val addedProjects: StateFlow<List<AddedProject>> = _addedProjects.asStateFlow()

    /** 添加项目；已存在则跳过。 */
    fun addProject(app: InstalledApp) {
        val current = _addedProjects.value
        if (current.any { it.packageName == app.packageName }) return
        persist(current + AddedProject(
            packageName = app.packageName,
            appName = app.name,
            isEnabled = true,
        ))
    }

    /** 按包名移除项目。 */
    fun removeProject(packageName: String) {
        persist(_addedProjects.value.filterNot { it.packageName == packageName })
    }

    /** 切换启用开关（持久化，并同步写 ScriptStore——模块冷启动 is_target_enabled 的数据源）。 */
    fun setEnabled(packageName: String, enabled: Boolean) {
        val current = _addedProjects.value
        // F3：IpcManager 副作用含 Binder 调用（stopScript → 远程 unloadScript；enable/disable 落盘），
        // 放 IO 线程避免阻塞主线程；完成后回主线程（viewModelScope 默认 Main.immediate）更新 UI 状态。
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (enabled) {
                    IpcManager.enableTarget(packageName)
                } else {
                    IpcManager.disableTarget(packageName)
                    // 停用即卸载目标进程内已加载脚本（目标不在线时为空操作）
                    IpcManager.stopScript(packageName)
                }
            }
            persist(current.map {
                if (it.packageName == packageName) it.copy(isEnabled = enabled) else it
            })
        }
    }

    private fun persist(projects: List<AddedProject>) {
        _addedProjects.value = projects
        // 落盘在 IO 线程，避免阻塞主线程
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.save(projects)
            }
        }
    }
}
