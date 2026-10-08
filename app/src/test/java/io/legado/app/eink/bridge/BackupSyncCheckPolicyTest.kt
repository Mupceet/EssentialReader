package io.legado.app.eink.bridge

import io.legado.app.domain.model.WebDavBackup
import io.legado.app.eink.contract.CloudBackupNewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 云端备份启动检查判定核心：复刻宿主 MainActivity.backupSync 语义
 * （autoCheckNewBackup 门控 + mtime 与 lastBackup 差 > 1 分钟）与
 * 备份文件名解析（backup<yyyy-MM-dd>-<设备名>.zip）。
 */
class BackupSyncCheckPolicyTest {

    private val latest = WebDavBackup(name = "backup2026-09-23-ReaderA.zip", lastModify = 1_000_000_000L)

    @Test
    fun `开关关不提示`() {
        assertNull(BackupSyncCheckPolicy.shouldPrompt(false, latest, lastBackupMark = 0L))
    }

    @Test
    fun `无云端备份不提示`() {
        assertNull(BackupSyncCheckPolicy.shouldPrompt(true, null, lastBackupMark = 0L))
    }

    @Test
    fun `时间差不足一分钟不提示`() {
        assertNull(BackupSyncCheckPolicy.shouldPrompt(true, latest, lastBackupMark = latest.lastModify - 60_000L))
        assertNull(BackupSyncCheckPolicy.shouldPrompt(true, latest, lastBackupMark = latest.lastModify))
        assertNull(
            "本地标记晚于云端也不提示",
            BackupSyncCheckPolicy.shouldPrompt(true, latest, lastBackupMark = latest.lastModify + 5_000L)
        )
    }

    @Test
    fun `时间差超一分钟命中并原样返回`() {
        val hit = BackupSyncCheckPolicy.shouldPrompt(true, latest, lastBackupMark = latest.lastModify - 60_001L)
        assertSame(latest, hit)
    }

    @Test
    fun `文件名解析出设备名与日期`() {
        assertEquals(
            CloudBackupNewer("backup2026-09-23-ReaderA.zip", "ReaderA", "2026-09-23"),
            BackupSyncCheckPolicy.parseSnapshot(latest),
        )
    }

    @Test
    fun `无设备段时设备名为空串`() {
        val backup = WebDavBackup(name = "backup2026-09-23.zip", lastModify = 0L)
        assertEquals(
            CloudBackupNewer("backup2026-09-23.zip", "", "2026-09-23"),
            BackupSyncCheckPolicy.parseSnapshot(backup),
        )
    }

    @Test
    fun `非备份文件名回退原文件名`() {
        val backup = WebDavBackup(name = "manualExport.zip", lastModify = 0L)
        assertEquals(
            CloudBackupNewer("manualExport.zip", "", "manualExport.zip"),
            BackupSyncCheckPolicy.parseSnapshot(backup),
        )
    }
}
