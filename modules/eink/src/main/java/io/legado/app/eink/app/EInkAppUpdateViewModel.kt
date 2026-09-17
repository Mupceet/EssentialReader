package io.legado.app.eink.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.contract.AppUpdateEngine
import io.legado.app.eink.contract.AppUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 应用更新检查状态的 Activity 级持有者（与 [EInkNavViewModel] 同作用域，
 * 在每条目 ViewModelStoreOwner 覆盖之外经 viewModel() 求值取得）。
 *
 * 状态从「我的」页内聚 remember 提升到 Activity 级：启动自动检查
 * （对齐宿主完整模式 MainActivity 启动链）的弹层须渲染在 EInkApp
 * 根层、覆盖任意屏幕（含直达最近阅读场景的阅读页），且检查期间
 * Activity recreate（字体缩放等设置改变入口重建）不丢结果。
 *
 * 写入方两路：本 VM 的启动自动检查（静默口径——无更新/失败不提示，
 * 对齐宿主 checkUpdateOnStart 只挂 onSuccess）；「我的」页手动检查
 * （页面协程 + toast 反馈，见 MineScreen——页面卸载取消协程时由其
 * finally 复位 Checking，防手动入口卡「检查中」）。
 */
internal class EInkAppUpdateViewModel : ViewModel() {

    /** 更新检查状态机（启动自动检查与「我的」页手动检查共用）。 */
    var updateCheck by mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle)
        internal set

    /**
     * 启动自动检查：经端口询问宿主门控（「其他设置」启动检查开关 +
     * 进程级一次性闸，与完整模式共享同一套），通过后在 viewModelScope
     * 发起静默检查——无更新与失败均不提示，仅发现新版本置
     * [UpdateCheckState.Available] 由根层弹层承接。
     */
    fun autoCheckOnStart(engine: AppUpdateEngine) {
        if (!engine.shouldAutoCheckOnStart()) return
        if (updateCheck is UpdateCheckState.Checking) return
        updateCheck = UpdateCheckState.Checking
        viewModelScope.launch {
            try {
                val info = engine.checkUpdate()
                updateCheck = info?.let { UpdateCheckState.Available(it) }
                    ?: UpdateCheckState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 自动路径静默（对齐宿主）：检查失败不弹层不提示
                updateCheck = UpdateCheckState.Idle
            }
        }
    }
}

/** 更新检查状态机：Idle / Checking / 发现新版本（Available）。 */
internal sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val info: AppUpdateInfo) : UpdateCheckState
}
