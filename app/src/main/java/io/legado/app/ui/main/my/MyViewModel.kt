package io.legado.app.ui.main.my

import android.app.Application
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.EventBus
import io.legado.app.domain.gateway.LabSettingsGateway
import io.legado.app.service.WebService
import io.legado.app.utils.eventBus.FlowEventBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Stable
data class MyUiState(
    val isWebServiceRun: Boolean = false,
    val webServiceAddress: String = "",
    val showEInkModeEntry: Boolean = false,
    val isEInkModeOn: Boolean = false,
)

sealed class PrefClickEvent {
    data class OpenUrl(val url: String) : PrefClickEvent()
    data class CopyUrl(val url: String) : PrefClickEvent()
    data class StartActivity(val destination: Class<*>, val configTag: String? = null) : PrefClickEvent()
    object OpenReadRecord : PrefClickEvent()
    object OpenBookCacheManage : PrefClickEvent()
    object OpenBookSourceManage : PrefClickEvent()
    object OpenHighlightTagRule : PrefClickEvent()
    object OpenAbout : PrefClickEvent()
    object ToggleWebService : PrefClickEvent()
    object ExitApp : PrefClickEvent()
}

sealed interface MyIntent {
    data object ToggleWebService : MyIntent
    data class SetEInkMode(val enabled: Boolean) : MyIntent

    /** 本地网络权限授予后由界面触发，避免再次进入申请分支。 */
    data object StartWebService : MyIntent
}

sealed interface MyEffect {
    data object EnterEInkMode : MyEffect

    /** Android 17 起 Web 服务需要先获得本地网络权限才能被其他设备访问。 */
    data object RequestLocalNetworkPermission : MyEffect
}

class MyViewModel(
    application: Application,
    private val labSettingsGateway: LabSettingsGateway,
) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(
        MyUiState(
            isWebServiceRun = WebService.isRun,
            webServiceAddress = WebService.hostAddress,
            showEInkModeEntry = labSettingsGateway.currentSettings.run {
                enabled && eInkDisplay
            },
            isEInkModeOn = labSettingsGateway.currentSettings.eInkMode,
        )
    )
    val uiState: StateFlow<MyUiState> = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<MyEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            FlowEventBus.with<String>(EventBus.WEB_SERVICE)
                .collect { address ->
                    _uiState.update { state ->
                        state.copy(
                            isWebServiceRun = address.isNotEmpty(),
                            webServiceAddress = address
                        )
                    }
                }
        }
        // 「墨水屏模式」开关：条目显隐跟实验室两个开关联动——「启用实验室」
        // 或「墨水屏显示」任一关闭即隐藏（总开关关闭时实验室页也藏起显示组）；
        // 开关状态是独立的 eInkMode 偏好——打开即进入 E-Ink，退出模式自动
        // 关闭（条目不随之消失），冷启动按 eInkMode 分流
        viewModelScope.launch {
            labSettingsGateway.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        showEInkModeEntry = settings.enabled && settings.eInkDisplay,
                        isEInkModeOn = settings.eInkMode,
                    )
                }
            }
        }
    }

    fun onIntent(intent: MyIntent) {
        when (intent) {
            MyIntent.ToggleWebService -> {
                if (_uiState.value.isWebServiceRun) {
                    WebService.stop(context)
                    _uiState.update { it.copy(isWebServiceRun = false, webServiceAddress = "") }
                } else if (WebService.hasLocalNetworkPermission(context)) {
                    WebService.start(context)
                } else {
                    _effects.tryEmit(MyEffect.RequestLocalNetworkPermission)
                }
            }
            is MyIntent.SetEInkMode -> viewModelScope.launch {
                // 偏好先落盘再发进入效果：中断最坏态是「开关已开、未跳转」，
                // 用户冷启动或再拨一次开关即可进入
                labSettingsGateway.update { it.copy(eInkMode = intent.enabled) }
                if (intent.enabled) _effects.tryEmit(MyEffect.EnterEInkMode)
            }

            MyIntent.StartWebService -> WebService.start(context)
        }
    }

}
