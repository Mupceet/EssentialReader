package io.legado.app.eink.bridge

import io.legado.app.domain.model.settings.BackupSettings
import io.legado.app.eink.contract.WebDavSyncConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BackupSyncEngineImpl WebDAV 管理映射锚定：宿主设置模型与模块配置
 * 快照的五字段一一对应（读写两侧共用同一字段序，防字段错位回归）。
 */
class BackupSyncEngineImplTest {

    @Test
    fun `settings 五字段一一投影到模块配置快照`() {
        val settings = BackupSettings(
            webDavUrl = "https://dav.example.com/dav/",
            webDavAccount = "user@example.com",
            webDavPassword = "secret",
            webDavDir = "books",
            webDavDeviceName = "墨水屏",
        )
        assertEquals(
            WebDavSyncConfig(
                serverUrl = "https://dav.example.com/dav/",
                account = "user@example.com",
                password = "secret",
                dir = "books",
                deviceName = "墨水屏",
            ),
            settings.toWebDavSyncConfig()
        )
    }

    @Test
    fun `默认设置投影保持宿主默认值`() {
        // 未配置初态：地址空串（模块侧展示回落默认）、文件夹宿主默认 legado
        assertEquals(
            WebDavSyncConfig(
                serverUrl = "",
                account = "",
                password = "",
                dir = "legado",
                deviceName = "",
            ),
            BackupSettings().toWebDavSyncConfig()
        )
    }
}
