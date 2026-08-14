package io.legado.app.eink.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/**
 * Static loading indicator for E-Ink displays.
 *
 * E-Ink must NOT use spinners or any animated indicator: every animation frame
 * forces a screen refresh, causing severe ghosting and battery drain. Instead
 * this shows a single centered text label that is drawn once and left alone
 * until the real content replaces it.
 *
 * @param text Label to display (defaults to a Chinese loading string)
 * @param modifier Modifier for the indicator
 * @param color Text color (defaults to the theme onSurfaceVariant)
 */
@Composable
fun EInkLoading(
    text: String = "加载中...",
    modifier: Modifier = Modifier,
    color: Color = EInkTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EInkText(
            text = text,
            style = EInkTheme.typography.titleMedium,
            color = color
        )
    }
}

/**
 * A full-size centered box wrapping an [EInkLoading] indicator.
 *
 * Drop this into any content slot while data is being fetched; it fills the
 * available space and centers a single static "加载中..." label.
 *
 * @param modifier Modifier for the box
 * @param text Label forwarded to [EInkLoading]
 * @param content Optional extra content rendered below the label
 */
@Composable
fun EInkLoadingBox(
    modifier: Modifier = Modifier,
    text: String = "加载中...",
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.s)
        ) {
            EInkLoading(text = text)
            content?.invoke(this@Column)
        }
    }
}
