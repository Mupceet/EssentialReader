package io.legado.app.eink.designsystem.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * A horizontal divider drawn as a flat filled box — no shadow, no gradient.
 *
 * E-Ink note: drawn with a solid color so it renders as a crisp
 * single-pixel-ish line rather than an anti-aliased halo. Default color is
 * the theme `divider` role (a real gray level per spec §11, never alpha).
 *
 * @param modifier Modifier for the divider
 * @param thickness Line thickness
 * @param color Line color (defaults to the theme divider color)
 */
@Composable
fun EInkHorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = DefaultDividerThickness,
    color: Color = EInkTheme.colorScheme.divider
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color = color)
    )
}

/** Default divider thickness (1dp). */
private val DefaultDividerThickness = 1.dp
