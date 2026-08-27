package io.legado.app.eink.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.theme.EInkTheme

/**
 * A horizontal divider drawn as a flat filled box — no shadow, no gradient.
 *
 * E-Ink note: drawn with a solid color (default the theme outline) so it
 * renders as a crisp single-pixel-ish line rather than an anti-aliased halo.
 *
 * @param modifier Modifier for the divider
 * @param thickness Line thickness
 * @param color Line color (defaults to the theme outline color)
 */
@Composable
fun EInkHorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = DefaultDividerThickness,
    color: Color = EInkTheme.colorScheme.outline
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color = color)
    )
}

/**
 * A vertical divider drawn as a flat filled box.
 *
 * @param modifier Modifier for the divider
 * @param thickness Line thickness
 * @param color Line color (defaults to the theme outline color)
 */
@Composable
fun EInkVerticalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = DefaultDividerThickness,
    color: Color = EInkTheme.colorScheme.outline
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(thickness)
            .background(color = color)
    )
}

/** Default divider thickness (1dp). */
private val DefaultDividerThickness = 1.dp
