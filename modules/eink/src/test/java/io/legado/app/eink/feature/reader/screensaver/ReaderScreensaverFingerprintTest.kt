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
        activeLockScreenTypeValue = IREADER_LOCK_SCREEN_TYPE_READING_PROGRESS,
    )

    @Test
    fun ignoresPageVersionButReflectsVisibleContentAndSettings() {
        val request = request(text = "same page")
        val same = request(text = "same page")

        assertEquals(
            fingerprint(request, settings),
            fingerprint(same, settings),
        )
        assertNotEquals(
            fingerprint(request, settings),
            fingerprint(request(text = "next page"), settings),
        )
        assertNotEquals(
            fingerprint(request, settings),
            fingerprint(
                request,
                settings.copy(illustrationValue = "custom.png"),
            ),
        )
        assertNotEquals(
            fingerprint(request, settings),
            fingerprint(request.copyWithBookName("another book"), settings),
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

    @Test
    fun coverModeIgnoresPagesButReflectsCoverAndBookIdentity() {
        val coverSettings = settings.copy(bookScreenSaverStyle = 2)
        val request = request(text = "same page")

        assertEquals(
            fingerprint(request, coverSettings, coverDigest = "cover-a"),
            fingerprint(
                request(text = "different page"),
                coverSettings,
                coverDigest = "cover-a",
            ),
        )
        assertNotEquals(
            fingerprint(request, coverSettings, coverDigest = "cover-a"),
            fingerprint(request, coverSettings, coverDigest = "cover-b"),
        )
        assertNotEquals(
            fingerprint(request, coverSettings, coverDigest = "cover-a"),
            fingerprint(
                request.copyWithBookAuthor("another author"),
                coverSettings,
                coverDigest = "cover-a",
            ),
        )
        assertNotEquals(
            fingerprint(request, coverSettings, coverDigest = "cover-a"),
            fingerprint(
                request.copyWithBookUrl("another url"),
                coverSettings,
                coverDigest = "cover-a",
            ),
        )
    }

    @Test
    fun reflectsTheWallpaperTargetSizeInBothModes() {
        val request = request(text = "same page")
        val coverSettings = settings.copy(bookScreenSaverStyle = 2)

        // 阅读页模式：目标画布尺寸变化（换设备/旋转）必须重发
        assertNotEquals(
            fingerprint(request, settings, targetWidthPx = 1860, targetHeightPx = 2480),
            fingerprint(request, settings, targetWidthPx = 1200, targetHeightPx = 1600),
        )
        assertEquals(
            fingerprint(request, settings, targetWidthPx = 1860, targetHeightPx = 2480),
            fingerprint(request, settings, targetWidthPx = 1860, targetHeightPx = 2480),
        )
        // 封面模式同样随目标尺寸缩放，尺寸变化必须重发
        assertNotEquals(
            fingerprint(
                request,
                coverSettings,
                coverDigest = "cover-a",
                targetWidthPx = 1860,
                targetHeightPx = 2480,
            ),
            fingerprint(
                request,
                coverSettings,
                coverDigest = "cover-a",
                targetWidthPx = 1200,
                targetHeightPx = 1600,
            ),
        )
    }

    private fun fingerprint(
        request: ReaderScreensaverRequest,
        settings: ReaderScreensaverSettings,
        targetWidthPx: Int = 1860,
        targetHeightPx: Int = 2480,
        coverDigest: String? = null,
    ): String = ReaderScreensaverFingerprint.of(
        request = request,
        settings = settings,
        targetWidthPx = targetWidthPx,
        targetHeightPx = targetHeightPx,
        coverDigest = coverDigest,
    )

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
            bookName = "book",
            bookAuthor = "author",
            bookUrl = "book-url",
            pageAndTotal = "2/20 10%",
            readProgress = "10%",
            headerVisible = true,
            footerVisible = true,
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
            systemBarTopInsetPx = 50f,
            systemBarBottomInsetPx = 30f,
        )
    }

    private fun ReaderScreensaverRequest.copyWithBookName(name: String): ReaderScreensaverRequest =
        ReaderScreensaverRequest(
            page = page,
            chapterTitle = chapterTitle,
            bookName = name,
            bookAuthor = bookAuthor,
            bookUrl = bookUrl,
            pageAndTotal = pageAndTotal,
            readProgress = readProgress,
            headerVisible = headerVisible,
            footerVisible = footerVisible,
            autoPlay = autoPlay,
            style = style,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            density = density,
            colors = colors,
            headerTypeface = headerTypeface,
            footerTypeface = footerTypeface,
            imageAntiAlias = imageAntiAlias,
            headerExtentPx = headerExtentPx,
            footerExtentPx = footerExtentPx,
            systemBarTopInsetPx = systemBarTopInsetPx,
            systemBarBottomInsetPx = systemBarBottomInsetPx,
        )

    private fun ReaderScreensaverRequest.copyWithBookAuthor(
        author: String,
    ): ReaderScreensaverRequest =
        ReaderScreensaverRequest(
            page = page,
            chapterTitle = chapterTitle,
            bookName = bookName,
            bookAuthor = author,
            bookUrl = bookUrl,
            pageAndTotal = pageAndTotal,
            readProgress = readProgress,
            headerVisible = headerVisible,
            footerVisible = footerVisible,
            autoPlay = autoPlay,
            style = style,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            density = density,
            colors = colors,
            headerTypeface = headerTypeface,
            footerTypeface = footerTypeface,
            imageAntiAlias = imageAntiAlias,
            headerExtentPx = headerExtentPx,
            footerExtentPx = footerExtentPx,
            systemBarTopInsetPx = systemBarTopInsetPx,
            systemBarBottomInsetPx = systemBarBottomInsetPx,
        )

    private fun ReaderScreensaverRequest.copyWithBookUrl(
        url: String,
    ): ReaderScreensaverRequest =
        ReaderScreensaverRequest(
            page = page,
            chapterTitle = chapterTitle,
            bookName = bookName,
            bookAuthor = bookAuthor,
            bookUrl = url,
            pageAndTotal = pageAndTotal,
            readProgress = readProgress,
            headerVisible = headerVisible,
            footerVisible = footerVisible,
            autoPlay = autoPlay,
            style = style,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            density = density,
            colors = colors,
            headerTypeface = headerTypeface,
            footerTypeface = footerTypeface,
            imageAntiAlias = imageAntiAlias,
            headerExtentPx = headerExtentPx,
            footerExtentPx = footerExtentPx,
            systemBarTopInsetPx = systemBarTopInsetPx,
            systemBarBottomInsetPx = systemBarBottomInsetPx,
        )
}
