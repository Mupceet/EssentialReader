package io.legado.app.eink.feature.reader.screensaver

import android.graphics.Typeface
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.feature.reader.ReaderUiState
import java.security.MessageDigest

internal data class ReaderScreensaverColors(
    val backgroundArgb: Int,
    val textArgb: Int,
    val highlightArgb: Int,
    val badgeArgb: Int,
    val tipArgb: Int,
)

/**
 * Immutable snapshot of everything needed to render one lock-screen image.
 *
 * The reader viewport can exclude system bars while the `.rmb` target is a
 * fixed full-screen bitmap, so both source dimensions and density are kept.
 */
internal data class ReaderScreensaverRequest(
    val page: ReaderPageSnapshot,
    val chapterTitle: String,
    val bookName: String,
    val pageAndTotal: String,
    val readProgress: String,
    val headerVisible: Boolean,
    val footerVisible: Boolean,
    val autoPlay: Boolean,
    val style: ReaderTextStyle,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val density: Float,
    val colors: ReaderScreensaverColors,
    val headerTypeface: Typeface?,
    val footerTypeface: Typeface?,
    val imageAntiAlias: Boolean,
    val headerExtentPx: Float,
    val footerExtentPx: Float,
    val systemBarTopInsetPx: Float,
    val systemBarBottomInsetPx: Float,
)

internal fun ReaderUiState.toScreensaverRequest(
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    density: Float,
    colors: ReaderScreensaverColors,
    headerTypeface: Typeface?,
    footerTypeface: Typeface?,
    imageAntiAlias: Boolean,
    headerExtentPx: Float,
    footerExtentPx: Float,
    systemBarTopInsetPx: Float,
    systemBarBottomInsetPx: Float,
): ReaderScreensaverRequest? {
    val currentPage = page ?: return null
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return null
    return ReaderScreensaverRequest(
        page = currentPage,
        chapterTitle = chapterTitle,
        bookName = bookName.ifBlank { chapterTitle },
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
        systemBarTopInsetPx = systemBarTopInsetPx.coerceAtLeast(0f),
        systemBarBottomInsetPx = systemBarBottomInsetPx.coerceAtLeast(0f),
    )
}

/**
 * Content fingerprint used to avoid rewriting identical wallpapers.
 *
 * `pageVersion` is intentionally excluded: a reflow/reload that produces the
 * same visual snapshot should not trigger another 9.2 MB write.
 */
internal object ReaderScreensaverFingerprint {

    fun of(
        request: ReaderScreensaverRequest,
        settings: ReaderScreensaverSettings,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val page = request.page

        digest.text(page.title)
        digest.text(page.readProgress)
        digest.paintSpec(page.titleSpec)
        digest.paintSpec(page.contentSpec)
        digest.int(page.lines.size)
        for (line in page.lines) {
            digest.int(if (line.isTitle) 1 else 0)
            digest.float(line.baseY)
            digest.float(line.top)
            digest.float(line.bottom)
            digest.int(line.paragraphBreaksAfter)
            digest.int(line.chunks.size)
            for (index in line.chunks.indices) {
                digest.text(line.chunks[index])
                digest.float(line.x[index])
                digest.int(line.chapterPositions[index])
            }
            digest.int(line.decorations.size)
            for (run in line.decorations) {
                digest.int(run.start)
                digest.int(run.end)
                digest.int(run.underlineMode)
                digest.int(if (run.highlight) 1 else 0)
                digest.text(run.markingId)
                run.underline?.let { geometry ->
                    digest.float(geometry.widthPx)
                    digest.float(geometry.offsetPx)
                    digest.float(geometry.dashOnPx)
                    digest.float(geometry.dashOffPx)
                    digest.float(geometry.waveAmplitudePx)
                    digest.float(geometry.waveLengthPx)
                    digest.float(geometry.doubleLineGapPx)
                }
            }
        }
        digest.int(page.images.size)
        for (image in page.images) {
            digest.float(image.x0)
            digest.float(image.x1)
            digest.float(image.lineTop)
            digest.float(image.lineBottom)
            digest.float(image.lineHeight)
            digest.int(if (image.fullLine) 1 else 0)
            digest.text(image.source)
            digest.text(image.action ?: "")
        }
        digest.int(if (page.bookmarkBadge) 1 else 0)
        digest.float(page.contentFillRatio)

        digest.text(request.chapterTitle)
        digest.text(request.bookName)
        digest.text(request.pageAndTotal)
        digest.text(request.readProgress)
        digest.int(if (request.headerVisible) 1 else 0)
        digest.int(if (request.footerVisible) 1 else 0)
        digest.int(if (request.autoPlay) 1 else 0)
        digest.text(request.style.toString())
        digest.int(request.viewportWidthPx)
        digest.int(request.viewportHeightPx)
        digest.float(request.density)
        digest.int(request.colors.backgroundArgb)
        digest.int(request.colors.textArgb)
        digest.int(request.colors.highlightArgb)
        digest.int(request.colors.badgeArgb)
        digest.int(request.colors.tipArgb)
        digest.int(System.identityHashCode(request.headerTypeface))
        digest.int(System.identityHashCode(request.footerTypeface))
        digest.int(if (request.imageAntiAlias) 1 else 0)
        digest.float(request.headerExtentPx)
        digest.float(request.footerExtentPx)
        digest.float(request.systemBarTopInsetPx)
        digest.float(request.systemBarBottomInsetPx)

        digest.int(if (settings.screensaverEnabled) 1 else 0)
        digest.int(settings.bookScreenSaverStyle)
        digest.int(if (settings.illustrationEnabled) 1 else 0)
        digest.text(settings.illustrationValue ?: "")

        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun MessageDigest.paintSpec(spec: io.legado.app.eink.contract.ReaderPaintSpec) {
        float(spec.textSizePx)
        float(spec.letterSpacing)
        int(System.identityHashCode(spec.typeface))
        text(spec.fontVariationSettings ?: "")
    }

    private fun MessageDigest.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        int(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.int(value: Int) {
        update((value ushr 24 and 0xff).toByte())
        update((value ushr 16 and 0xff).toByte())
        update((value ushr 8 and 0xff).toByte())
        update((value and 0xff).toByte())
    }

    private fun MessageDigest.float(value: Float) {
        int(value.toRawBits())
    }
}

/** Last successfully published fingerprint for the current reader surface. */
internal class ReaderScreensaverPublicationGate {
    private var lastPublishedFingerprint: String? = null

    fun shouldPublish(fingerprint: String, force: Boolean): Boolean =
        force || fingerprint != lastPublishedFingerprint

    fun markPublished(fingerprint: String) {
        lastPublishedFingerprint = fingerprint
    }

    fun reset() {
        lastPublishedFingerprint = null
    }
}
