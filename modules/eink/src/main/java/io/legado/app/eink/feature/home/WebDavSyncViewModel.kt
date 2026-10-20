package io.legado.app.eink.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.WebDavSyncConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 备份与恢复页 VM — WebDAV 配置展示/保存 + 测试/备份/恢复的手动闭环。
 *
 * 配置读写全经 [BackupSyncEngine]（与完整模式同一存储），本 VM 不持
 * 配置真值：展示值来自 [BackupSyncEngine.webDavConfig] 流（宿主或完整
 * 模式侧改动同样回流），保存经 [WebDavSyncIntent.SaveConfig] 归一化后
 * 整体落盘（suspend 落定再返回，无读写竞态）。
 *
 * 操作反馈口径（引擎契约 KDoc 同款）：
 * - 测试：成败细节由宿主链路 toast，模块只做忙态，不产生消息；
 * - 备份/恢复：宿主链路无提示，模块以一次性消息 toast 完成/失败
 *   （对齐启动检查恢复路径「恢复完成」文案）；
 * - 恢复交互（产品确认）：恢复最新一份——先 [BackupSyncEngine.latestBackup]
 *   弹确认层（设备/日期），确认后 [BackupSyncEngine.restoreBackup]；
 *   失败不回显确认层（重点重走），仅 toast。
 *
 * 单忙态互斥：同一时刻至多一个云端操作进行中（忙态反馈直接落在对应
 * 动作按钮的文字上，不弹模态框——操作期间用户仍可离开本页，协程随
 * 条目 ViewModelStore 清理而取消），进行中重复派发直接忽略。云端操作
 * 协程走 IO（宿主备份含 zip 打包）。
 */
internal class WebDavSyncViewModel : ViewModel() {

    private val engine: BackupSyncEngine? = EInkEngineRegistry.backupSyncEngine

    /** 进行中操作（互斥单忙态；忙态文案映射见 Screen 动作按钮）。 */
    enum class Busy {
        Testing,
        LoadingLatest,
        BackingUp,
        Restoring,
    }

    /** 页面状态：配置（引擎流投影）+ 忙态 + 恢复确认弹框数据。 */
    data class UiState(
        val config: WebDavSyncConfig = WebDavSyncConfig("", "", "", "", ""),
        val busy: Busy? = null,
        val restoreConfirm: CloudBackupNewer? = null,
    ) {
        /** 账号密码齐备才可发起云端动作（宿主 isConfigured 同口径）。 */
        val cloudReady: Boolean
            get() = config.account.isNotBlank() && config.password.isNotEmpty()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 一次性结果提示（toast 文案；测试结果不经此流，见类 KDoc）。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        engine?.let { eng ->
            viewModelScope.launch {
                eng.webDavConfig.collect { config ->
                    _uiState.update { it.copy(config = config) }
                }
            }
        }
    }

    fun onIntent(intent: WebDavSyncIntent) {
        when (intent) {
            is WebDavSyncIntent.SaveConfig -> saveConfig(intent.config)
            WebDavSyncIntent.TestService -> testService()
            WebDavSyncIntent.BackupNow -> backupNow()
            WebDavSyncIntent.RequestRestore -> requestRestore()
            WebDavSyncIntent.ConfirmRestore -> confirmRestore()
            WebDavSyncIntent.DismissRestore ->
                _uiState.update { it.copy(restoreConfirm = null) }
        }
    }

    private fun saveConfig(raw: WebDavSyncConfig) {
        val eng = engine ?: return
        val normalized = normalizeWebDavConfig(raw)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { eng.saveWebDavConfig(normalized) }
                .onFailure { _messages.tryEmit("保存失败：${it.message ?: "未知错误"}") }
        }
    }

    private fun testService() {
        val eng = engine ?: return
        if (_uiState.value.busy != null) return
        setBusy(Busy.Testing)
        viewModelScope.launch(Dispatchers.IO) {
            // 成败细节由宿主链路 toast（引擎契约口径），模块不重复提示
            runCatching { eng.testWebDav() }
            setBusy(null)
        }
    }

    private fun backupNow() {
        val eng = engine ?: return
        if (_uiState.value.busy != null) return
        setBusy(Busy.BackingUp)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                eng.backupNow()
                _messages.tryEmit("备份完成")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("备份失败：${e.message ?: "未知错误"}")
            } finally {
                setBusy(null)
            }
        }
    }

    private fun requestRestore() {
        val eng = engine ?: return
        if (_uiState.value.busy != null) return
        setBusy(Busy.LoadingLatest)
        viewModelScope.launch(Dispatchers.IO) {
            // 拉取失败与无备份同为 null → 同一句提示（不区分打扰用户）
            val latest = runCatching { eng.latestBackup() }.getOrNull()
            setBusy(null)
            if (latest == null) {
                _messages.tryEmit("云端没有可用备份")
            } else {
                _uiState.update { it.copy(restoreConfirm = latest) }
            }
        }
    }

    private fun confirmRestore() {
        val eng = engine ?: return
        val info = _uiState.value.restoreConfirm ?: return
        if (_uiState.value.busy != null) return
        _uiState.update { it.copy(restoreConfirm = null) }
        setBusy(Busy.Restoring)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                eng.restoreBackup(info.fileName)
                _messages.tryEmit("恢复完成")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("恢复失败：${e.message ?: "未知错误"}")
            } finally {
                setBusy(null)
            }
        }
    }

    private fun setBusy(busy: Busy?) {
        _uiState.update { it.copy(busy = busy) }
    }
}

/** 页面动作。 */
internal sealed interface WebDavSyncIntent {
    /** 保存配置（VM 内先归一化）。 */
    data class SaveConfig(val config: WebDavSyncConfig) : WebDavSyncIntent

    data object TestService : WebDavSyncIntent

    data object BackupNow : WebDavSyncIntent

    /** 请求恢复：拉取云端最新备份，命中弹确认层。 */
    data object RequestRestore : WebDavSyncIntent

    /** 确认恢复确认层当前指向的备份。 */
    data object ConfirmRestore : WebDavSyncIntent

    data object DismissRestore : WebDavSyncIntent
}

/**
 * 保存归一化：地址/文件夹 trim 后空值回落产品默认（对齐宿主运行时回退
 * 常量）；账号/设备名 trim；密码原样保留（宿主存储与校验均不 trim，
 * 可能含首尾空格的合法密码）。
 */
internal fun normalizeWebDavConfig(config: WebDavSyncConfig): WebDavSyncConfig =
    WebDavSyncConfig(
        serverUrl = config.serverUrl.trim().ifEmpty { WebDavSyncConfig.DEFAULT_SERVER_URL },
        account = config.account.trim(),
        password = config.password,
        dir = config.dir.trim().ifEmpty { WebDavSyncConfig.DEFAULT_DIR },
        deviceName = config.deviceName.trim(),
    )
