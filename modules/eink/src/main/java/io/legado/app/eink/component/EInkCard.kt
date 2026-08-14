package io.legado.app.eink.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.legado.app.eink.modifier.staticClickable
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme

/**
 * E-Ink optimized card with zero elevation and border-based visual separation.
 *
 * Hierarchy is conveyed through borders and container color rather than shadows
 * (shadows render as fuzzy gray halos on electrophoretic displays and ghost).
 * Pass [onClick] to make the card clickable; the click uses [staticClickable]
 * so there is no ripple-induced refresh.
 *
 * @param modifier Modifier for the card
 * @param onClick Optional click handler (makes the card clickable)
 * @param enabled Whether the card is enabled (only relevant when [onClick] is set)
 * @param colors Card colors
 * @param border Border for the card
 * @param shape Shape of the card
 * @param contentPadding Padding around the card content
 * @param content The content of the card
 */
@Composable
fun EInkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: EInkCardColors = EInkCardDefaults.colors(),
    border: BorderStroke = EInkCardDefaults.border(),
    shape: Shape = EInkShapes.medium,
    contentPadding: PaddingValues = EInkCardDefaults.contentPadding(),
    content: @Composable ColumnScope.() -> Unit
) {
    val backgroundColor = if (enabled) colors.containerColor else colors.disabledContainerColor
    val borderBrush: Brush = if (enabled) border.brush else EInkCardDefaults.disabledBorder().brush

    val cardModifier = modifier
        .background(color = backgroundColor, shape = shape)
        .border(border = BorderStroke(border.width, borderBrush), shape = shape)
        .then(
            if (onClick != null) {
                Modifier.staticClickable(enabled = enabled, onClick = onClick)
            } else {
                Modifier
            }
        )
        .padding(contentPadding)

    Column(
        modifier = cardModifier,
        content = content
    )
}

/**
 * Elevated card variant. On E-Ink "elevation" is expressed as a stronger border
 * plus a distinct container color rather than a drop shadow.
 */
@Composable
fun EInkElevatedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: EInkCardColors = EInkCardDefaults.elevatedColors(),
    border: BorderStroke = EInkCardDefaults.elevatedBorder(),
    shape: Shape = EInkShapes.medium,
    contentPadding: PaddingValues = EInkCardDefaults.contentPadding(),
    content: @Composable ColumnScope.() -> Unit
) {
    EInkCard(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        border = border,
        shape = shape,
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * Outlined card variant with transparent background and a thin outline border.
 */
@Composable
fun EInkOutlinedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: EInkCardColors = EInkCardDefaults.outlinedColors(),
    border: BorderStroke = EInkCardDefaults.outlinedBorder(),
    shape: Shape = EInkShapes.medium,
    contentPadding: PaddingValues = EInkCardDefaults.contentPadding(),
    content: @Composable ColumnScope.() -> Unit
) {
    EInkCard(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        border = border,
        shape = shape,
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * Card colors for E-Ink themes.
 */
@Stable
data class EInkCardColors(
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color
)

/**
 * Default values and factory helpers for E-Ink cards.
 */
object EInkCardDefaults {

    @Composable
    fun colors(): EInkCardColors {
        val colors = eInkColorScheme()
        return EInkCardColors(
            containerColor = colors.surface,
            contentColor = colors.onSurface,
            disabledContainerColor = colors.surfaceVariant,
            disabledContentColor = colors.onSurfaceVariant
        )
    }

    @Composable
    fun elevatedColors(): EInkCardColors {
        val colors = eInkColorScheme()
        return EInkCardColors(
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            disabledContainerColor = colors.surfaceVariant,
            disabledContentColor = colors.onSurfaceVariant
        )
    }

    @Composable
    fun outlinedColors(): EInkCardColors {
        val colors = eInkColorScheme()
        return EInkCardColors(
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.onSurfaceVariant
        )
    }

    @Composable
    fun border() = BorderStroke(
        width = ThinBorder,
        color = eInkColorScheme().outline
    )

    @Composable
    fun elevatedBorder() = BorderStroke(
        width = MediumBorder,
        color = eInkColorScheme().primary
    )

    @Composable
    fun outlinedBorder() = BorderStroke(
        width = ThinBorder,
        color = eInkColorScheme().outline
    )

    @Composable
    fun disabledBorder() = BorderStroke(
        width = ThinBorder,
        color = eInkColorScheme().onSurfaceVariant
    )

    fun contentPadding() = PaddingValues(EInkSpacing.m)
}

/** Thin border width (1dp). */
private val ThinBorder = 1.dp

/** Medium border width (2dp), used by the elevated card variant. */
private val MediumBorder = 2.dp
