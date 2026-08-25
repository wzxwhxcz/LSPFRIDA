package com.bail.lspfrifa.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AppsUiState {
    data object Idle : AppsUiState
    data object Loading : AppsUiState
    data class Success(val apps: List<InstalledApp>) : AppsUiState
    data class Error(val message: String) : AppsUiState
}

/**
 * 与 LSPFRIFA 目标复刻版的 ViewModel 状态驱动一致：加载、成功、空结果和错误都有明确状态。
 */
class AppsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppListRepository(application)
    private val _uiState = MutableStateFlow<AppsUiState>(AppsUiState.Idle)
    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()

    fun load(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current is AppsUiState.Loading || current is AppsUiState.Success)) return

        viewModelScope.launch {
            _uiState.value = AppsUiState.Loading
            _uiState.value = try {
                AppsUiState.Success(repository.getInstalledApps())
            } catch (t: Throwable) {
                AppsUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}