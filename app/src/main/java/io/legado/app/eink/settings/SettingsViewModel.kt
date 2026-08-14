package io.legado.app.eink.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.help.config.ReadBookConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 阅读/全局设置 UiState。
 *
 * 直接映射 [ReadBookConfig] 的排版参数（SharedPreferences-backed）。
 */
data class SettingsUiState(
    val textSize: Int = 20,
    val lineSpacingExtra: Int = 12,
    val letterSpacing: Float = 0.1f,
    val paragraphSpacing: Int = 2,
    val paddingLeft: Int = 16,
    val paddingTop: Int = 16,
    val paddingRight: Int = 16,
    val paddingBottom: Int = 16,
)

/**
 * 设置 ViewModel。
 *
 * 读写 [ReadBookConfig]（全局 SharedPreferences），修改即时持久化。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(readConfig())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun readConfig() = SettingsUiState(
        textSize = ReadBookConfig.textSize,
        lineSpacingExtra = ReadBookConfig.lineSpacingExtra,
        letterSpacing = ReadBookConfig.letterSpacing,
        paragraphSpacing = ReadBookConfig.paragraphSpacing,
        paddingLeft = ReadBookConfig.paddingLeft,
        paddingTop = ReadBookConfig.paddingTop,
        paddingRight = ReadBookConfig.paddingRight,
        paddingBottom = ReadBookConfig.paddingBottom,
    )

    private fun persist() {
        viewModelScope.launch {
            val s = _uiState.value
            ReadBookConfig.textSize = s.textSize
            ReadBookConfig.lineSpacingExtra = s.lineSpacingExtra
            ReadBookConfig.letterSpacing = s.letterSpacing
            ReadBookConfig.paragraphSpacing = s.paragraphSpacing
            ReadBookConfig.paddingLeft = s.paddingLeft
            ReadBookConfig.paddingTop = s.paddingTop
            ReadBookConfig.paddingRight = s.paddingRight
            ReadBookConfig.paddingBottom = s.paddingBottom
        }
    }

    fun setTextSize(value: Int) {
        _uiState.update { it.copy(textSize = value.coerceIn(12, 48)) }
        persist()
    }

    fun setLineSpacing(value: Int) {
        _uiState.update { it.copy(lineSpacingExtra = value.coerceIn(0, 40)) }
        persist()
    }

    fun setLetterSpacing(value: Float) {
        _uiState.update { it.copy(letterSpacing = (value * 10).toInt() / 10f) }
        persist()
    }

    fun setParagraphSpacing(value: Int) {
        _uiState.update { it.copy(paragraphSpacing = value.coerceIn(0, 20)) }
        persist()
    }

    fun setPadding(left: Int? = null, top: Int? = null, right: Int? = null, bottom: Int? = null) {
        _uiState.update {
            it.copy(
                paddingLeft = left?.coerceIn(0, 64) ?: it.paddingLeft,
                paddingTop = top?.coerceIn(0, 64) ?: it.paddingTop,
                paddingRight = right?.coerceIn(0, 64) ?: it.paddingRight,
                paddingBottom = bottom?.coerceIn(0, 64) ?: it.paddingBottom,
            )
        }
        persist()
    }

    /** 恢复默认值 */
    fun resetToDefault() {
        _uiState.value = SettingsUiState()
        persist()
    }
}
