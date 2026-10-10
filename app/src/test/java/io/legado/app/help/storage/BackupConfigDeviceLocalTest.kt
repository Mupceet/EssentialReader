package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备相关键的硬过滤语义（备份随包导出、仅跨设备恢复跳过）与集合不重不漏的边界。
 */
class BackupConfigDeviceLocalTest {

    @Test
    fun `device local keys cover mode entry geometry hardware performance and paths`() {
        listOf(
            // 模式与入口
            PreferKey.eInkMode,
            PreferKey.labEInkDisplay,
            // 书架几何
            PreferKey.bookshelfGridCoverWidth,
            PreferKey.bookshelfLayoutModePortrait,
            PreferKey.bookshelfListCoverWidth,
            // 显示与交互硬件
            PreferKey.fontScale,
            PreferKey.volumeKeyPage,
            PreferKey.brightness,
            PreferKey.nightBrightness,
            // 性能与资源（含自 always 迁入的 bitmapCacheSize 与三颗 WakeLock）
            PreferKey.threadCount,
            PreferKey.cacheBookThreadCount,
            PreferKey.bitmapCacheSize,
            PreferKey.webServiceWakeLock,
            PreferKey.readAloudWakeLock,
            PreferKey.audioPlayWakeLock,
            // 本机路径/URI/设备盘点/物理键码
            PreferKey.appFontPath,
            PreferKey.fontFolder,
            PreferKey.importBookPath,
            PreferKey.systemTypefaces,
            PreferKey.prevKeys,
            PreferKey.nextKeys,
        ).forEach { key ->
            assertTrue("缺少设备相关键: $key", key in deviceLocalPreferenceKeys)
        }
    }

    @Test
    fun `device local keys export on backup and skip only on cross device restore`() {
        // 备份侧照常导出——备份是唯一的数据捕获点，重装同机恢复依赖它
        assertTrue(isKeyAllowedByHardPolicy(PreferKey.eInkMode, isBackup = true))
        assertTrue(isKeyAllowedByHardPolicy(PreferKey.brightness, isBackup = true))
        // 同机恢复照常应用
        assertTrue(
            isKeyAllowedByHardPolicy(
                PreferKey.eInkMode, isBackup = false, restoreFromSameDevice = true
            )
        )
        // 跨设备恢复跳过，保留本机值
        assertFalse(
            isKeyAllowedByHardPolicy(
                PreferKey.eInkMode, isBackup = false, restoreFromSameDevice = false
            )
        )
        assertFalse(
            isKeyAllowedByHardPolicy(
                PreferKey.bookshelfGridCoverWidth, isBackup = false, restoreFromSameDevice = false
            )
        )
        // 非设备键不受跨设备判定影响（同机与否都恢复）
        assertTrue(
            isKeyAllowedByHardPolicy(
                PreferKey.themeMode, isBackup = false, restoreFromSameDevice = false
            )
        )
    }

    @Test
    fun `always ignored keys stay blocked in both directions`() {
        listOf(
            PreferKey.defaultCover,
            PreferKey.backupPath,
            PreferKey.defaultBookTreeUri,
            PreferKey.webDavDeviceName,
            PreferKey.launcherIcon,
            LocalPreferencesKeys.PASSWORD.name,
            LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name,
        ).forEach { key ->
            assertFalse(isKeyAllowedByHardPolicy(key, isBackup = true))
            assertFalse(isKeyAllowedByHardPolicy(key, isBackup = false, restoreFromSameDevice = true))
            assertFalse(isKeyAllowedByHardPolicy(key, isBackup = false, restoreFromSameDevice = false))
        }
    }

    @Test
    fun `device local and always ignored sets are disjoint`() {
        // 迁移后的键不得滞留 always 集合，否则备份侧被旧规则挡住、永远无法随包导出
        deviceLocalPreferenceKeys.forEach { key ->
            assertTrue("$key 不应同时出现在两个忽略集合", key !in alwaysIgnoredPreferenceKeys)
        }
    }

    @Test
    fun `origin device marker exports with backup and is never a setting`() {
        // 标记必须能通过备份侧过滤（否则 config.xml 里没有同机判定依据）
        assertTrue(isKeyAllowedByHardPolicy(backupOriginDeviceKey, isBackup = true))
        // 不进任何忽略/设备集合：恢复端在 applyConfigMap 中显式剥离，不作为设置写入
        assertTrue(backupOriginDeviceKey !in alwaysIgnoredPreferenceKeys)
        assertTrue(backupOriginDeviceKey !in deviceLocalPreferenceKeys)
    }
}
