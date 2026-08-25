package com.bail.lspfrifa

import android.app.Application
import com.bail.lspfrifa.data.InjectHintStore
import com.bail.lspfrifa.data.ThemeModeStore
import com.bail.lspfrifa.ipc.LogStore
import com.bail.lspfrifa.ipc.ScriptStore
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模块 App 侧的 libxposed Service 生命周期入口。
 * 只有收到 LSPosed 的 XposedService Binder 才判定为激活。
 */
class LSPFRIFAApplication : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        ThemeModeStore.init(this)
        ScriptStore.init(this)
        InjectHintStore.init(this)
        LogStore.init(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        FrameworkState.onServiceBind(service)
    }

    override fun onServiceDied(service: XposedService) {
        FrameworkState.onServiceDied(service)
    }
}

data class FrameworkStatus(
    val active: Boolean = false,
    val apiVersion: Int = 0,
    val frameworkName: String = "",
    val frameworkVersion: String = ""
)

object FrameworkState {
    private val services = LinkedHashSet<XposedService>()
    private val _status = MutableStateFlow(FrameworkStatus())
    val status = _status.asStateFlow()

    /** 当前可用的 XposedService（多框架取最近绑定的）；无服务返回 null（未激活）。 */
    @Synchronized
    fun current(): XposedService? = services.lastOrNull()

    @Synchronized
    fun onServiceBind(service: XposedService) {
        services.add(service)
        publish(service)
    }

    @Synchronized
    fun onServiceDied(service: XposedService) {
        services.remove(service)
        val latest = services.lastOrNull()
        if (latest == null) {
            _status.value = FrameworkStatus()
        } else {
            publish(latest)
        }
    }

    @Synchronized
    private fun publish(service: XposedService) {
        _status.value = try {
            FrameworkStatus(
                active = true,
                apiVersion = service.apiVersion,
                frameworkName = service.frameworkName.orEmpty(),
                frameworkVersion = service.frameworkVersion.orEmpty()
            )
        } catch (_: Throwable) {
            FrameworkStatus(active = true, apiVersion = XposedService.API_102)
        }
    }
}