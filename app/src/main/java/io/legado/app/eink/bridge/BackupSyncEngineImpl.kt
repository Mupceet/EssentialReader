package io.legado.app.eink.bridge

import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.usecase.WebDavBackupUseCase
import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import io.legado.app.help.config.LocalConfig

/**
 * 云端备份端口实现：转发宿主完整模式同一条链（MainActivity.backupSync
 * 判定 + WebDavBackupUseCase 恢复），E-Ink 侧不新增备份语义。开关
 * （autoCheckNewBackup）、比较与标记（LocalConfig.lastBackup，与完整
 * 模式共享同一闸，同进程先发现者标记、后者静默）、恢复管线
 * （BackupRestoreLock 串行）全部沿用宿主。
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
}
