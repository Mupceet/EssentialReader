package io.legado.app.eink.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.legado.app.eink.modifier.rememberNoRippleInteractionSource
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography

/**
 * E-Ink optimized text field built on [BasicTextField] (not Material OutlinedTextField).
 *
 * Visual feedback is conveyed through instant border-color changes rather than
 * animated indicators, following the priority chain:
 * isError -> focused -> !enabled -> unfocused. The default interaction source is
 * a no-ripple source so focusing the field never triggers a ripple refresh.
 *
 * Minimum font size is enforced with the same three-way fallback as [EInkText]:
 * explicit param -> style -> [MIN_FONT_SIZE].
 *
 * @param value The current text value
 * @param onValueChange Callback invoked when text changes
 * @param modifier Modifier for the text field
 * @param enabled Whether the text field is enabled
 * @param readOnly Whether the text field is read-only
 * @param textStyle Style for the input text
 * @param label Optional label above the text field
 * @param placeholder Optional placeholder text
 * @param leadingIcon Optional leading icon
 * @param trailingIcon Optional trailing icon
 * @param supportingText Optional supporting text below the field
 * @param isError Whether the field is in error state
 * @param visualTransformation Visual transformation for the text
 * @param keyboardOptions Keyboard options
 * @param keyboardActions Keyboard actions
 * @param singleLine Whether this is a single line text field
 * @param maxLines Maximum number of lines
 * @param minLines Minimum number of lines
 * @param interactionSource Interaction source for the field (no-ripple by default)
 * @param shape Shape of the text field
 * @param colors Colors for the text field
 */
@Composable
fun EInkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = eInkTypography().bodyLarge,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = rememberNoRippleInteractionSource(),
    shape: Shape = EInkShapes.small,
    colors: EInkTextFieldColors = EInkTextFieldDefaults.colors()
) {
    val isFocused by interactionSource.collectIsFocusedAsState()

    // State-driven border: error takes priority, then focus, then enabled, then default.
    val borderColor = when {
        isError -> colors.errorBorderColor
        isFocused -> colors.focusedBorderColor
        !enabled -> colors.disabledBorderColor
        else -> colors.unfocusedBorderColor
    }

    val backgroundColor = if (enabled) colors.containerColor else colors.disabledContainerColor

    val textColor = when {
        isError -> colors.errorTextColor
        !enabled -> colors.disabledTextColor
        else -> colors.textColor
    }

    // Minimum font size enforced via the shared three-way fallback.
    val enforcedTextStyle = textStyle.copy(
        color = textColor,
        fontSize = resolveMinFontSize(textStyle.fontSize)
    )

    Column(modifier = modifier) {
        // Label
        if (label != null) {
            EInkText(
                text = label,
                style = eInkTypography().labelLarge,
                color = if (isError) colors.errorLabelColor else colors.labelColor,
                modifier = Modifier.padding(bottom = EInkSpacing.xs)
            )
        }

        // Text field container
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = enforcedTextStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(textColor),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .background(backgroundColor, shape)
                        .border(BorderStroke(ThinBorder, borderColor), shape)
                        .padding(EInkSpacing.m),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Leading icon
                    leadingIcon?.let { icon ->
                        icon()
                        Spacer(modifier = Modifier.width(EInkSpacing.s))
                    }

                    // Text field content
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            EInkText(
                                text = placeholder,
                                style = enforcedTextStyle,
                                color = colors.placeholderColor
                            )
                        }
                        innerTextField()
                    }

                    // Trailing icon
                    trailingIcon?.let { icon ->
                        Spacer(modifier = Modifier.width(EInkSpacing.s))
                        icon()
                    }
                }
            }
        )

        // Supporting text
        if (supportingText != null) {
            EInkText(
                text = supportingText,
                style = eInkTypography().bodySmall,
                color = if (isError) colors.errorSupportingTextColor else colors.supportingTextColor,
                modifier = Modifier.padding(
                    top = EInkSpacing.xs,
                    start = EInkSpacing.m
                )
            )
        }
    }
}

/**
 * Outlined text field variant: transparent container with the same border logic.
 */
@Composable
fun EInkOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = eInkTypography().bodyLarge,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = rememberNoRippleInteractionSource(),
    shape: Shape = EInkShapes.small,
    colors: EInkTextFieldColors = EInkTextFieldDefaults.outlinedColors()
) {
    EInkTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors
    )
}

/**
 * Colors for E-Ink text fields.
 */
@Stable
data class EInkTextFieldColors(
    val textColor: Color,
    val disabledTextColor: Color,
    val containerColor: Color,
    val disabledContainerColor: Color,
    val placeholderColor: Color,
    val labelColor: Color,
    val errorLabelColor: Color,
    val supportingTextColor: Color,
    val errorSupportingTextColor: Color,
    val focusedBorderColor: Color,
    val unfocusedBorderColor: Color,
    val disabledBorderColor: Color,
    val errorBorderColor: Color,
    val errorTextColor: Color
)

/**
 * Default values and factory helpers for E-Ink text fields.
 */
object EInkTextFieldDefaults {

    @Composable
    fun colors(): EInkTextFieldColors {
        val colors = eInkColorScheme()
        return EInkTextFieldColors(
            textColor = colors.onSurface,
            disabledTextColor = colors.onSurfaceVariant,
            containerColor = colors.surfaceVariant,
            disabledContainerColor = colors.surfaceVariant,
            placeholderColor = colors.onSurfaceVariant,
            labelColor = colors.onSurface,
            errorLabelColor = colors.error,
            supportingTextColor = colors.onSurfaceVariant,
            errorSupportingTextColor = colors.error,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline,
            disabledBorderColor = colors.onSurfaceVariant,
            errorBorderColor = colors.error,
            errorTextColor = colors.error
        )
    }

    @Composable
    fun outlinedColors(): EInkTextFieldColors {
        val colors = eInkColorScheme()
        return EInkTextFieldColors(
            textColor = colors.onSurface,
            disabledTextColor = colors.onSurfaceVariant,
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            placeholderColor = colors.onSurfaceVariant,
            labelColor = colors.onSurface,
            errorLabelColor = colors.error,
            supportingTextColor = colors.onSurfaceVariant,
            errorSupportingTextColor = colors.error,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline,
            disabledBorderColor = colors.onSurfaceVariant,
            errorBorderColor = colors.error,
            errorTextColor = colors.error
        )
    }
}

/** Thin border width used by the text field container. */
private val ThinBorder = 1.dp

/**
 * Resolves the effective font size from a [styleFontSize] using the same
 * three-way fallback as [EInkText] but where the explicit-param branch has
 * already been handled by the caller: when the supplied size is specified it is
 * clamped to [MIN_FONT_SIZE]; otherwise the floor itself is used (the caller's
 * style default is responsible for providing a readable size).
 */
private fun resolveMinFontSize(styleFontSize: TextUnit): TextUnit = when {
    styleFontSize != TextUnit.Unspecified && styleFontSize < MIN_FONT_SIZE -> MIN_FONT_SIZE
    styleFontSize != TextUnit.Unspecified -> styleFontSize
    else -> MIN_FONT_SIZE
}
