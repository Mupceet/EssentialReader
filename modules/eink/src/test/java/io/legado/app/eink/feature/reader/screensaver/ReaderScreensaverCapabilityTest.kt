package io.legado.app.eink.feature.reader.screensaver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreensaverCapabilityTest {

    private val activeSettings = ReaderScreensaverSettings(
        screensaverEnabled = true,
        bookScreenSaverStyle = ReaderScreensaverSettings.BOOK_READING_STYLE,
        illustrationEnabled = false,
        illustrationValue = null,
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
    fun requiresScreensaverEnabledAndBookStyleFive() {
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
}
