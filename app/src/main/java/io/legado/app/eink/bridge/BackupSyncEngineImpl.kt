package io.legado.app.eink.bridge

import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.model.settings.BackupSettings
import io.legado.app.domain.usecase.WebDavBackupUseCase
import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import io.legado.app.eink.contract.WebDavSyncConfig
import io.legado.app.help.config.LocalConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 云端备份端口实现：转发宿主完整模式同一条链（MainActivity.backupSync
 * 判定 + WebDavBackupUseCase 恢复），E-Ink 侧不新增备份语义。开关
 * （autoCheckNewBackup）、比较与标记（LocalConfig.lastBackup，与完整
 * 模式共享同一闸，同进程先发现者标记、后者静默）、恢复管线
 * （BackupRestoreLock 串行）全部沿用宿主。
 *
 * WebDAV 管理（supportsWebDavManagement）：配置读写经
 * [BackupSettingsGateway]（与完整模式同一存储，App 启动订阅流变化
 * 自动重建 AppWebDav 运行时授权，模块保存后零额外接线）；测试/备份/
 * 恢复转发 [WebDavBackupUseCase]（宿主备份设置页同链，忽略清单由
 * Backup/Restore 管线内部消费、沿用完整模式设置，模块无感知）。
 * [latestBackup] 仅作展示快照，不写 lastBackup 标记（与
 * checkNewBackupOnStart 的「发现即标记」刻意区分）。
 */
object BackupSyncEngineImpl : BackupSyncEngine {

    private val webDavBackupUseCase: WebDavBackupUseCase by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    private val backupSettingsGateway: BackupSettingsGateway by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    override suspend fun checkNewBackupOnStart(): CloudBackupNewer? {
        // 拉取失败（未配置 WebDAV/网络不可用）静默，对齐宿主 catch→return
        val latest = runCatching { webDavBackupUseCase.getLatestBackup() }
            .getOrNull() ?: return null
        val hit = BackupSyncCheckPolicy.shouldPrompt(
            autoCheckEnabled = backupSettingsGateway.currentSettings.autoCheckNewBackup,
            latest = latest,
            lastBackupMark = LocalConfig.lastBackup,
        ) ?: return null
        // 发现即标记（宿主同位）：用户取消后同一备份不再提示，失败重试由弹层承担
        LocalConfig.lastBackup = latest.lastModify
        return BackupSyncCheckPolicy.parseSnapshot(hit)
    }

    override suspend fun restoreBackup(fileName: String) {
        webDavBackupUseCase.restore(fileName)
    }

    override val supportsWebDavManagement: Boolean = true

    override val webDavConfig: Flow<WebDavSyncConfig> by lazy {
        // 五字段子集投影 + 去重：完整模式侧改动无关字段（如进度同步开关）
        // 不重发模块配置。lazy 收集期才解析 Koin 依赖（VM init 时机，
        // 远晚于 attachBaseContext 的端口装配）
        backupSettingsGateway.settings
            .map { it.toWebDavSyncConfig() }
            .distinctUntilChanged()
    }

    override suspend fun saveWebDavConfig(config: WebDavSyncConfig) {
        backupSettingsGateway.update { current ->
            current.copy(
                webDavUrl = config.serverUrl,
                webDavAccount = config.account,
                webDavPassword = config.password,
                webDavDir = config.dir,
                webDavDeviceName = config.deviceName,
            )
        }
    }

    override suspend fun testWebDav(): Boolean {
        // 成败细节由宿主链路 toast（AppWebDav.testWebDav 内置）
        return webDavBackupUseCase.test()
    }

    override suspend fun backupNow() {
        webDavBackupUseCase.backup()
    }

    override suspend fun latestBackup(): CloudBackupNewer? {
        return runCatching { webDavBackupUseCase.getLatestBackup() }
            .getOrNull()
            ?.let(BackupSyncCheckPolicy::parseSnapshot)
    }
}

/** 宿主设置模型 → 模块配置快照（五字段子集，字段一一对应）。 */
internal fun BackupSettings.toWebDavSyncConfig() = WebDavSyncConfig(
    serverUrl = webDavUrl,
    account = webDavAccount,
    password = webDavPassword,
    dir = webDavDir,
    deviceName = webDavDeviceName,
)
