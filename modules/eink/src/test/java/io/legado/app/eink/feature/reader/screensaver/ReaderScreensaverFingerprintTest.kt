package io.legado.app.eink.feature.reader.screensaver

import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.contract.ReaderTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreensaverFingerprintTest {

    private val settings = ReaderScreensaverSettings(
        screensaverEnabled = true,
        bookScreenSaverStyle = 5,
        illustrationEnabled = true,
        illustrationValue = "overlay2.png",
    )

    @Test
    fun ignoresPageVersionButReflectsVisibleContentAndSettings() {
        val request = request(text = "same page")
        val same = request(text = "same page")

        assertEquals(
            ReaderScreensaverFingerprint.of(request, settings),
            ReaderScreensaverFingerprint.of(same, settings),
        )
        assertNotEquals(
            ReaderScreensaverFingerprint.of(request, settings),
            ReaderScreensaverFingerprint.of(request(text = "next page"), settings),
        )
        assertNotEquals(
            ReaderScreensaverFingerprint.of(request, settings),
            ReaderScreensaverFingerprint.of(
                request,
                settings.copy(illustrationValue = "custom.png"),
            ),
        )
    }

    @Test
    fun publicationGateSkipsDuplicateUntilForcedOrReset() {
        val gate = ReaderScreensaverPublicationGate()

        assertTrue(gate.shouldPublish("a", force = false))
        gate.markPublished("a")
        assertFalse(gate.shouldPublish("a", force = false))
        assertTrue(gate.shouldPublish("a", force = true))
        assertTrue(gate.shouldPublish("b", force = false))
        gate.reset()
        assertTrue(gate.shouldPublish("a", force = false))
    }

    private fun request(text: String): ReaderScreensaverRequest {
        val spec = ReaderPaintSpec(
            textSizePx = 40f,
            letterSpacing = 0f,
            typeface = null,
            fontVariationSettings = null,
        )
        val page = ReaderPageSnapshot(
            title = "chapter",
            readProgress = "10%",
            titleSpec = spec,
            contentSpec = spec,
            lines = listOf(
                ReaderPageLine(
                    baseY = 80f,
                    isTitle = false,
                    chunks = listOf(text),
                    x = floatArrayOf(20f),
                    chapterPositions = intArrayOf(0),
                    top = 40f,
                    bottom = 100f,
                ),
            ),
            images = emptyList(),
        )
        return ReaderScreensaverRequest(
            page = page,
            chapterTitle = "chapter",
            pageAndTotal = "2/20 10%",
            readProgress = "10%",
            headerVisible = true,
            footerVisible = true,
            headerTime = "09:30",
            batteryPercent = 80,
            autoPlay = false,
            style = ReaderTextStyle(),
            viewportWidthPx = 1860,
            viewportHeightPx = 2480,
            density = 2f,
            colors = ReaderScreensaverColors(
                backgroundArgb = 0xffffffff.toInt(),
                textArgb = 0xff000000.toInt(),
                highlightArgb = 0xffcccccc.toInt(),
                badgeArgb = 0xffaaaaaa.toInt(),
                tipArgb = 0xff222222.toInt(),
            ),
            headerTypeface = null,
            footerTypeface = null,
            imageAntiAlias = true,
            headerExtentPx = 60f,
            footerExtentPx = 90f,
        )
    }
}
