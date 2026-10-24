package io.legado.app.eink.feature.reader.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreensaverCapabilityTest {

    private val activeSettings = ReaderScreensaverSettings(
        screensaverEnabled = true,
        bookScreenSaverStyle = ReaderScreensaverSettings.BOOK_READING_STYLE,
        illustrationEnabled = false,
        illustrationValue = null,
        activeLockScreenTypeValue = IREADER_LOCK_SCREEN_TYPE_READING_PROGRESS,
    )

    @Test
    fun requiresIReaderBrandAndSystemUiPackage() {
        assertTrue(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "Ocean",
                    systemUiInstalled = true,
                    settings = activeSettings,
                ),
            ),
        )
        assertFalse(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "Other",
                    brand = "Ocean",
                    systemUiInstalled = true,
                    settings = activeSettings,
                ),
            ),
        )
        assertFalse(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "Ocean",
                    systemUiInstalled = false,
                    settings = activeSettings,
                ),
            ),
        )
    }

    @Test
    fun requiresScreensaverEnabledAndSupportedBookStyle() {
        assertTrue(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "iReader",
                    systemUiInstalled = true,
                    settings = activeSettings.copy(
                        bookScreenSaverStyle = ReaderScreensaverSettings.BOOK_COVER_STYLE,
                        activeLockScreenTypeValue = IREADER_LOCK_SCREEN_TYPE_BOOK,
                    ),
                ),
            ),
        )
        assertFalse(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "iReader",
                    systemUiInstalled = true,
                    settings = activeSettings.copy(screensaverEnabled = false),
                ),
            ),
        )
        assertFalse(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "iReader",
                    systemUiInstalled = true,
                    settings = activeSettings.copy(bookScreenSaverStyle = 4),
                ),
            ),
        )
    }

    @Test
    fun mapsBookStylesToTheirFirmwareProtocols() {
        assertEquals(
            ReaderScreensaverMode.COVER,
            ReaderScreensaverMode.fromStyle(ReaderScreensaverSettings.BOOK_COVER_STYLE),
        )
        assertEquals(
            ReaderScreensaverMode.READING_PROGRESS,
            ReaderScreensaverMode.fromStyle(ReaderScreensaverSettings.BOOK_READING_STYLE),
        )
        assertNull(ReaderScreensaverMode.fromStyle(4))
    }

    @Test
    fun requiresActiveLockScreenTypeToMatchMode() {
        // 用户选了实时屏保(DYNAMIC=25)等非图书类型时必须完全静默：
        // SystemUI 收到刷新广播会无条件切到广播声明的显示模型，任何
        // 写入/广播都会劫持用户所选屏保
        assertFalse(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "iReader",
                    systemUiInstalled = true,
                    settings = activeSettings.copy(
                        activeLockScreenTypeValue = IREADER_LOCK_SCREEN_TYPE_DYNAMIC,
                    ),
                ),
            ),
        )
        // 未设置(null)：固件把活动类型回落到 COMPAT_*/SYSTEM 兜底链（非
        // 图书类），官方阅读应用同语义——不发布
        assertFalse(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "iReader",
                    systemUiInstalled = true,
                    settings = activeSettings.copy(activeLockScreenTypeValue = null),
                ),
            ),
        )
        // 活动类型是另一图书样式（选封面但活动的是阅读进度）同样不发布
        assertFalse(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "iReader",
                    systemUiInstalled = true,
                    settings = activeSettings.copy(
                        bookScreenSaverStyle = ReaderScreensaverSettings.BOOK_COVER_STYLE,
                    ),
                ),
            ),
        )
        // 活动类型与所选样式一致 → 允许发布
        assertTrue(
            resolveReaderScreensaverCapability(
                ReaderScreensaverCapabilityInput(
                    manufacturer = "iReader",
                    brand = "iReader",
                    systemUiInstalled = true,
                    settings = activeSettings,
                ),
            ),
        )
    }

    @Test
    fun firmwareTypeValuesMatchWallpaperLockScreenTypeContract() {
        assertEquals(2, IREADER_LOCK_SCREEN_TYPE_BOOK)
        assertEquals(16, IREADER_LOCK_SCREEN_TYPE_READING_PROGRESS)
        assertEquals(25, IREADER_LOCK_SCREEN_TYPE_DYNAMIC)
        assertEquals(
            IREADER_LOCK_SCREEN_TYPE_BOOK,
            ReaderScreensaverMode.COVER.typeValue,
        )
        assertEquals(
            IREADER_LOCK_SCREEN_TYPE_READING_PROGRESS,
            ReaderScreensaverMode.READING_PROGRESS.typeValue,
        )
    }
}
