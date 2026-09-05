package io.legado.app.ui.config.labConfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.domain.gateway.LabSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.ui.book.read.pageestimate.LocalPageEstimateMetrics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LabConfigViewModel(
    private val settingsGateway: LabSettingsGateway,
    private val themeSettingsGateway: ThemeSettingsGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        LabConfigUiState(
            settings = settingsGateway.currentSettings,
            pageEstimateDiagnosticCount = LocalPageEstimateMetrics.size(),
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<LabConfigEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsGateway.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun onIntent(intent: LabConfigIntent) {
        if (intent is LabConfigIntent.ExportPageEstimateDiagnostics) {
            _effects.tryEmit(
                LabConfigEffect.SharePageEstimateDiagnostics(LocalPageEstimateMetrics.export())
            )
            return
        }
        viewModelScope.launch {
            // 主题先落盘再翻开关：中断最坏态是「主题已切、开关未开」（无害），顺序不可颠倒
            if (intent is LabConfigIntent.SetEInkDisplay && intent.value) {
                themeSettingsGateway.update { it.copy(appTheme = "4") }
            }
            settingsGateway.update { settings ->
                when (intent) {
                    is LabConfigIntent.SetEnabled -> settings.copy(enabled = intent.value)
                    is LabConfigIntent.SetEInkDisplay -> settings.copy(eInkDisplay = intent.value)
                    LabConfigIntent.ExportPageEstimateDiagnostics -> settings
                }
            }
        }
    }
}
