package io.legado.app.eink.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 云端备份启动检查状态的 Activity 级持有者（与 [EInkAppUpdateViewModel]
 * 同作用域，在每条目 ViewModelStoreOwner 覆盖之外经 viewModel() 求值）：
 * 启动检查（对齐宿主完整模式 MainActivity.backupSync 链）的确认弹层
 * 须渲染在 EInkApp 根层、覆盖任意屏幕（含直达最近阅读场景的阅读页），
 * 且检查期间 Activity recreate 不丢结果。
 *
 * 静默口径：检查无新备份/失败不提示（宿主 catch→return 同位）。
 * 恢复有结果反馈（对齐宿主手动恢复路径 BackupConfigViewModel）：
 * 成功 toast「恢复完成」，失败弹层保留变「重试」；恢复中收起弹层
 * 不取消恢复，终态经抑制位只走 toast 不回显。
 */
internal class EInkBackupSyncViewModel : ViewModel() {

    /** 确认弹层状态机（null = 无弹层）。 */
    var prompt by mutableStateOf<BackupSyncPromptState?>(null)
        internal set

    /** 一次性结果提示（toast 文案；展示后调 [clearNotice] 复位）。 */
    var oneShotNotice by mutableStateOf<String?>(null)
        internal set

    /** 检查进行中（防 Activity recreate 重跑效应双发检查）。 */
    var checking by mutableStateOf(false)
        internal set

    /** 恢复中收起弹层后置位：终态不再回显弹层（只走 toast）。 */
    private var suppressed = false

    /**
     * 启动检查（EInkApp 根层 LaunchedEffect 触发）：判定与标记全在
     * 端口实现侧，此处只承接结果——命中弹层，其余静默。
     */
    fun checkOnStart(engine: BackupSyncEngine) {
        if (checking || prompt != null) return
        checking = true
        viewModelScope.launch {
            val newer = runCatching { engine.checkNewBackupOnStart() }.getOrNull()
            checking = false
            prompt = newer?.let { BackupSyncPromptState.Newer(it) }
        }
    }

    /**
     * 「恢复」/「重试」：置忙态后转发端口恢复；成功收层 + 提示，
     * 失败保留弹层变可重试（恢复中被收起则只提示，不回显）。
     */
    fun confirmRestore(engine: BackupSyncEngine) {
        val info = when (val state = prompt) {
            is BackupSyncPromptState.Newer -> state.info
            is BackupSyncPromptState.Failed -> state.info
            is BackupSyncPromptState.Restoring -> return
            null -> return
        }
        prompt = BackupSyncPromptState.Restoring(info)
        viewModelScope.launch {
            try {
                engine.restoreBackup(info.fileName)
                prompt = null
                suppressed = false
                oneShotNotice = "恢复完成"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (suppressed) {
                    prompt = null
                    suppressed = false
                    oneShotNotice = "恢复失败：${e.message}"
                } else {
                    prompt = BackupSyncPromptState.Failed(info, e.message ?: "未知错误")
                }
            }
        }
    }

    /**
     * 收起弹层（取消/恢复中收起）。恢复中收起不取消恢复协程
     * （更新弹层「收起走取消、宿主下载不中断」同款）。
     */
    fun dismiss() {
        if (prompt is BackupSyncPromptState.Restoring) suppressed = true
        prompt = null
    }

    /** toast 已展示，复位一次性提示。 */
    fun clearNotice() {
        oneShotNotice = null
    }
}

/** 备份确认弹层状态机：发现新备份 / 恢复中（忙态）/ 恢复失败（可重试）。 */
internal sealed interface BackupSyncPromptState {
    data class Newer(val info: CloudBackupNewer) : BackupSyncPromptState
    data class Restoring(val info: CloudBackupNewer) : BackupSyncPromptState
    data class Failed(val info: CloudBackupNewer, val reason: String) : BackupSyncPromptState
}
