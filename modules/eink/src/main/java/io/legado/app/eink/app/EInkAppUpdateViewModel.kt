package io.legado.app.eink.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.contract.AppDownloadProgress
import io.legado.app.eink.contract.AppDownloadState
import io.legado.app.eink.contract.AppUpdateEngine
import io.legado.app.eink.contract.AppUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /** 更新下载状态机（「立即更新」后弹层内嵌的进度行）。 */
    var download by mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle)
        internal set

    private var downloadJob: Job? = null

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

    /**
     * 「立即更新」：转发宿主下载管线，弹层保持更新提示形态不动，
     * 标题下方内嵌进度行（部分系统不给通知栏权限，通知进度不可见，
     * 应用内进度是唯一可见反馈）。
     */
    fun startUpdateDownload(engine: AppUpdateEngine, info: AppUpdateInfo) {
        downloadJob?.cancel()
        download = UpdateDownloadState.Downloading(info, progress = null)
        engine.startDownload(info)
        downloadJob = viewModelScope.launch {
            engine.downloadProgress.collect { progress ->
                onProgressEmitted(progress)
            }
        }
    }

    /** 收起更新弹层：检查与下载观测一并复位，宿主下载不中断。 */
    fun dismissUpdate() {
        updateCheck = UpdateCheckState.Idle
        dismissDownload()
    }

    /** 停止进度观测（弹层收起或下载完成时内部调用）。 */
    private fun dismissDownload() {
        downloadJob?.cancel()
        downloadJob = null
        download = UpdateDownloadState.Idle
    }

    private fun onProgressEmitted(progress: AppDownloadProgress?) {
        val current = download as? UpdateDownloadState.Downloading ?: return
        if (progress == null) return
        // eink 屏按百分比/状态粒度落状态，避免 1Hz 轮询整行重刷；
        // 总长未知（percent=-1）时百分比不可用，退回逐次刷新字节文本
        val old = current.progress
        if (old != null && progress.percent >= 0 &&
            progress.percent == old.percent && progress.state == old.state
        ) {
            return
        }
        if (progress.state == AppDownloadState.SUCCEEDED) {
            // 宿主下载完成即调起系统安装器，更新弹层同步收起
            updateCheck = UpdateCheckState.Idle
            dismissDownload()
            return
        }
        download = current.copy(progress = progress)
    }
}

/** 更新检查状态机：Idle / Checking / 发现新版本（Available）。 */
internal sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val info: AppUpdateInfo) : UpdateCheckState
}

/** 更新下载状态机：Idle / 下载中（含最近一次进度快照）。 */
internal sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val info: AppUpdateInfo,
        val progress: AppDownloadProgress?,
    ) : UpdateDownloadState
}
