package io.legado.app.eink.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * Compose host for the shared Android-canvas reader page painter.
 *
 * The same [ReaderPageSnapshotPainter] is used by the iReader lock-screen
 * renderer, so body text, images, highlights, underlines, and the bookmark
 * ribbon cannot drift between the visible page and the generated `.rmb`.
 *
 * [pageVersion] forces a draw when the engine replaces content behind the same
 * snapshot object.
 */
@Composable
internal fun ReaderPageSnapshotCanvas(
    page: ReaderPageSnapshot?,
    pageVersion: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = EInkTheme.colorScheme
    val textColorArgb = scheme.onBackground.toArgb()
    val highlightColorArgb = scheme.secondaryContainer.toArgb()
    val badgeColorArgb = scheme.selectionContainer.toArgb()
    val imageAntiAlias = EInkEngineRegistry.globalSettings.useAntiAlias
    val headerExtentPx = EInkEngineRegistry.readerEngine.headerDecorationExtentPx
    val painter = remember { ReaderPageSnapshotPainter() }

    key(pageVersion) {
        Canvas(modifier = modifier) {
            val snapshot = page ?: return@Canvas
            painter.draw(
                canvas = drawContext.canvas.nativeCanvas,
                snapshot = snapshot,
                style = ReaderPageRenderStyle(
                    widthPx = size.width.toInt(),
                    heightPx = size.height.toInt(),
                    density = density,
                    textColorArgb = textColorArgb,
                    highlightColorArgb = highlightColorArgb,
                    badgeColorArgb = badgeColorArgb,
                    imageAntiAlias = imageAntiAlias,
                    headerExtentPx = headerExtentPx,
                ),
            )
        }
    }
}
