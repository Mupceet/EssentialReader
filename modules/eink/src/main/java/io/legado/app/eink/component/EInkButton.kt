package io.legado.app.eink.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme

/**
 * E-Ink optimized button component with zero elevation and high contrast styling.
 *
 * Uses instant color feedback instead of ripple animations so the electrophoretic
 * display only needs to update once per state change. The background is a flat
 * [Box] with an optional border — no shadow, no elevation overlay.
 *
 * Touch targets follow guideline #6: edge buttons use a larger 48dp minimum
 * (easy to reach, tolerant of imprecise taps) while central buttons use 36dp.
 *
 * @param onClick Callback invoked when button is clicked
 * @param modifier Modifier for the button
 * @param enabled Whether the button is enabled
 * @param colors Button colors (defaults to primary colors from theme)
 * @param border Optional border for the button (null for filled/text variants)
 * @param shape Shape of the button
 * @param contentPadding Padding around the button content
 * @param isEdgeButton Whether this button sits in an edge area (uses larger min size)
 * @param content The content of the button (typically text or icon)
 */
@Composable
fun EInkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: EInkButtonColors = EInkButtonDefaults.primaryColors(),
    border: BorderStroke? = null,
    shape: Shape = EInkShapes.small,
    contentPadding: PaddingValues = EInkButtonDefaults.contentPadding(),
    isEdgeButton: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val minSize = if (isEdgeButton) EdgeButtonMinSize else CentralButtonMinSize

    val backgroundColor = if (enabled) colors.containerColor else colors.disabledContainerColor
    val contentColor = if (enabled) colors.contentColor else colors.disabledContentColor

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minSize, minHeight = minSize)
            .background(color = backgroundColor, shape = shape)
            .then(
                if (border != null) Modifier.border(border, shape) else Modifier
            )
            .staticClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * Outlined button variant: transparent background with a visible border.
 * Useful for secondary actions where the filled primary button would dominate.
 */
@Composable
fun EInkOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: EInkButtonColors = EInkButtonDefaults.outlinedColors(),
    border: BorderStroke = EInkButtonDefaults.outlinedBorder(),
    shape: Shape = EInkShapes.small,
    contentPadding: PaddingValues = EInkButtonDefaults.contentPadding(),
    isEdgeButton: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    EInkButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        border = border,
        shape = shape,
        contentPadding = contentPadding,
        isEdgeButton = isEdgeButton,
        content = content
    )
}

/**
 * Text button variant: transparent background, no border.
 * Lowest-emphasis action (e.g. "Cancel" in a dialog).
 */
@Composable
fun EInkTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: EInkButtonColors = EInkButtonDefaults.textColors(),
    shape: Shape = EInkShapes.small,
    contentPadding: PaddingValues = EInkButtonDefaults.textContentPadding(),
    isEdgeButton: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    EInkButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        border = null,
        shape = shape,
        contentPadding = contentPadding,
        isEdgeButton = isEdgeButton,
        content = content
    )
}

/**
 * Button colors for E-Ink themes. Each color pair has an enabled and disabled variant
 * so disabled state is communicated by contrast change rather than opacity
 * (opacity blending produces intermediate grays that ghost on E-Ink).
 */
@Stable
data class EInkButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color
)

/**
 * Default values and factory helpers for E-Ink buttons.
 */
object EInkButtonDefaults {

    /** Minimum touch target for buttons in the central area (guideline #6). */
    val centralButtonMinSize: androidx.compose.ui.unit.Dp = CentralButtonMinSize

    /** Minimum touch target for buttons in edge areas (guideline #6). */
    val edgeButtonMinSize: androidx.compose.ui.unit.Dp = EdgeButtonMinSize

    @Composable
    fun primaryColors(): EInkButtonColors {
        val colors = eInkColorScheme()
        return EInkButtonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.surfaceVariant,
            disabledContentColor = colors.onSurfaceVariant
        )
    }

    @Composable
    fun outlinedColors(): EInkButtonColors {
        val colors = eInkColorScheme()
        return EInkButtonColors(
            containerColor = Color.Transparent,
            contentColor = colors.primary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.onSurfaceVariant
        )
    }

    @Composable
    fun textColors(): EInkButtonColors {
        val colors = eInkColorScheme()
        return EInkButtonColors(
            containerColor = Color.Transparent,
            contentColor = colors.primary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.onSurfaceVariant
        )
    }

    fun contentPadding() = PaddingValues(
        horizontal = EInkSpacing.m,
        vertical = EInkSpacing.s
    )

    fun textContentPadding() = PaddingValues(
        horizontal = EInkSpacing.s,
        vertical = EInkSpacing.xs
    )

    @Composable
    fun outlinedBorder() = BorderStroke(
        width = ThinBorder,
        color = eInkColorScheme().outline
    )
}

/** Thin border width used by outlined variants. */
private val ThinBorder = 1.dp

/** Central-area minimum touch target (36dp). */
private val CentralButtonMinSize = 36.dp

/** Edge-area minimum touch target (48dp). */
private val EdgeButtonMinSize = 48.dp
