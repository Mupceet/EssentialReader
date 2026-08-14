package io.legado.app.eink.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography

/**
 * Absolute minimum font size enforced across all E-Ink text.
 *
 * On electrophoretic displays anything smaller than ~14sp loses too much
 * sharpness to be readable, so this floor is applied regardless of caller input.
 */
val MIN_FONT_SIZE: TextUnit = 14.sp

/**
 * E-Ink optimized text component built on [BasicText] (not Material Text).
 *
 * Enforces a minimum font size via a three-way fallback so callers can pass any
 * combination of an explicit [fontSize], a base [style], or neither, and the
 * rendered size is always >= [MIN_FONT_SIZE]:
 *
 *  1. explicit [fontSize] param, when specified — clamped to the floor
 *  2. otherwise [style.fontSize], when specified — clamped to the floor
 *  3. otherwise [MIN_FONT_SIZE] (the floor itself)
 *
 * [TextUnit.Unspecified] is treated correctly: it means "not provided by the
 * caller", so the next source in the chain is consulted instead of being
 * compared directly (which would wrongly clamp the sentinel value).
 *
 * @param text The text to display
 * @param modifier Modifier for the text
 * @param color Text color (defaults to onSurface from theme)
 * @param fontSize Explicit font size; clamped to [MIN_FONT_SIZE] if smaller
 * @param fontWeight Font weight override
 * @param textAlign Text alignment override
 * @param textDecoration Text decoration override (underline, strikethrough)
 * @param overflow How to handle text overflow
 * @param softWrap Whether to allow soft line breaks
 * @param maxLines Maximum number of lines
 * @param style Base text style (its fontSize is consulted when [fontSize] is unspecified)
 */
@Composable
fun EInkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = eInkColorScheme().onSurface,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = eInkTypography().bodyMedium
) {
    // Three-way minimum font size fallback with correct TextUnit.Unspecified handling.
    val enforcedFontSize: TextUnit = when {
        // 1. Explicit param wins when specified.
        fontSize != TextUnit.Unspecified && fontSize < MIN_FONT_SIZE -> MIN_FONT_SIZE
        fontSize != TextUnit.Unspecified -> fontSize
        // 2. Fall back to the style's size when the param was unspecified.
        style.fontSize != TextUnit.Unspecified && style.fontSize < MIN_FONT_SIZE -> MIN_FONT_SIZE
        style.fontSize != TextUnit.Unspecified -> style.fontSize
        // 3. Both unspecified — use the floor as a safe default.
        else -> MIN_FONT_SIZE
    }

    val finalStyle = style.copy(
        color = color,
        fontSize = enforcedFontSize,
        fontWeight = fontWeight ?: style.fontWeight,
        textAlign = textAlign ?: style.textAlign,
        textDecoration = textDecoration ?: style.textDecoration
    )

    BasicText(
        text = text,
        modifier = modifier,
        style = finalStyle,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines
    )
}

/**
 * Headline text mapped to the headlineMedium typography role.
 */
@Composable
fun EInkHeadline(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = eInkColorScheme().onSurface
) {
    EInkText(
        text = text,
        modifier = modifier,
        color = color,
        style = eInkTypography().headlineMedium
    )
}

/**
 * Title text mapped to the titleLarge typography role.
 */
@Composable
fun EInkTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = eInkColorScheme().onSurface
) {
    EInkText(
        text = text,
        modifier = modifier,
        color = color,
        style = eInkTypography().titleLarge
    )
}

/**
 * Body text mapped to the bodyLarge typography role.
 */
@Composable
fun EInkBodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = eInkColorScheme().onSurface
) {
    EInkText(
        text = text,
        modifier = modifier,
        color = color,
        style = eInkTypography().bodyLarge
    )
}

/**
 * Label text mapped to the labelLarge typography role.
 */
@Composable
fun EInkLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = eInkColorScheme().onSurface
) {
    EInkText(
        text = text,
        modifier = modifier,
        color = color,
        style = eInkTypography().labelLarge
    )
}
