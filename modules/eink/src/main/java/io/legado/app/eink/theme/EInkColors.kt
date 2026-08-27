package io.legado.app.eink.theme

import androidx.compose.ui.graphics.Color

/**
 * Common contract implemented by every semantic palette nested in [EInkColors].
 *
 * Lets [EInkTheme] treat the light/dark x high-contrast/grayscale palettes
 * uniformly when building an [EInkColorScheme], without reflection or
 * per-palette helper functions. Package-private — it is an implementation
 * detail of the theme machinery, not part of the public palette API.
 */
internal interface EInkPalette {
    val primary: Color
    val onPrimary: Color
    val primaryContainer: Color
    val onPrimaryContainer: Color
    val secondary: Color
    val onSecondary: Color
    val secondaryContainer: Color
    val onSecondaryContainer: Color
    val background: Color
    val onBackground: Color
    val surface: Color
    val onSurface: Color
    val surfaceVariant: Color
    val onSurfaceVariant: Color
    val outline: Color
    val error: Color
    val onError: Color

    /**
     * Content color for disabled controls ("gray out").
     *
     * A dedicated mid-gray role: in the high-contrast palettes
     * [onSurfaceVariant] is pure black/white, which cannot express
     * "disabled" — disabled state must read as a real gray level,
     * never as alpha blending (ghosting on E-Ink).
     */
    val disabledContent: Color
}

/**
 * E-Ink optimized color palette following the 16-level grayscale guideline.
 *
 * Colors are designed for maximum contrast and readability on electrophoretic
 * displays. The palette exposes pure black/white plus 14 intermediate gray
 * levels, then groups them into four semantic schemes (light/dark x
 * high-contrast/grayscale) that [EInkTheme] can hand to a Compose tree.
 *
 * No Material3 dependency: this file only depends on `androidx.compose.ui.graphics`.
 */
object EInkColors {

    // Pure black and white for maximum contrast
    val PureBlack = Color(0xFF000000)
    val PureWhite = Color(0xFFFFFFFF)

    // 16-level grayscale palette (excluding pure black and white).
    // Naming follows standard camelCase: Gray01 .. Gray14.
    val Gray01 = Color(0xFF111111) // Darkest gray
    val Gray02 = Color(0xFF222222)
    val Gray03 = Color(0xFF333333)
    val Gray04 = Color(0xFF444444)
    val Gray05 = Color(0xFF555555)
    val Gray06 = Color(0xFF666666)
    val Gray07 = Color(0xFF777777)
    val Gray08 = Color(0xFF888888) // Middle gray
    val Gray09 = Color(0xFF999999)
    val Gray10 = Color(0xFFAAAAAA)
    val Gray11 = Color(0xFFBBBBBB)
    val Gray12 = Color(0xFFCCCCCC)
    val Gray13 = Color(0xFFDDDDDD)
    val Gray14 = Color(0xFFEEEEEE) // Lightest gray

    /**
     * High contrast color scheme for maximum readability.
     * Uses only pure black and white.
     */
    object HighContrast : EInkPalette {
        override val primary = PureBlack
        override val onPrimary = PureWhite
        override val primaryContainer = PureWhite
        override val onPrimaryContainer = PureBlack
        override val secondary = PureBlack
        override val onSecondary = PureWhite
        override val secondaryContainer = PureWhite
        override val onSecondaryContainer = PureBlack
        override val background = PureWhite
        override val onBackground = PureBlack
        override val surface = PureWhite
        override val onSurface = PureBlack
        override val surfaceVariant = PureWhite
        override val onSurfaceVariant = PureBlack
        override val outline = PureBlack
        override val error = PureBlack
        override val onError = PureWhite
        override val disabledContent = Gray09
    }

    /**
     * Grayscale color scheme with subtle hierarchy.
     * Uses the full 16-level grayscale palette for visual separation.
     */
    object Grayscale : EInkPalette {
        override val primary = PureBlack
        override val onPrimary = PureWhite
        override val primaryContainer = Gray13 // Light gray for containers
        override val onPrimaryContainer = PureBlack
        override val secondary = Gray05 // Dark gray for secondary elements
        override val onSecondary = PureWhite
        override val secondaryContainer = Gray12
        override val onSecondaryContainer = PureBlack
        override val background = PureWhite
        override val onBackground = PureBlack
        override val surface = PureWhite
        override val onSurface = PureBlack
        override val surfaceVariant = Gray14 // Very light gray for subtle differentiation
        override val onSurfaceVariant = PureBlack
        override val outline = Gray03 // Dark gray for borders
        override val error = PureBlack
        override val onError = PureWhite
        override val disabledContent = Gray09
    }

    /**
     * Dark high-contrast scheme: inverted pure black/white.
     */
    object DarkHighContrast : EInkPalette {
        override val primary = PureWhite
        override val onPrimary = PureBlack
        override val primaryContainer = PureBlack
        override val onPrimaryContainer = PureWhite
        override val secondary = PureWhite
        override val onSecondary = PureBlack
        override val secondaryContainer = PureBlack
        override val onSecondaryContainer = PureWhite
        override val background = PureBlack
        override val onBackground = PureWhite
        override val surface = PureBlack
        override val onSurface = PureWhite
        override val surfaceVariant = PureBlack
        override val onSurfaceVariant = PureWhite
        override val outline = PureWhite
        override val error = PureWhite
        override val onError = PureBlack
        override val disabledContent = Gray10
    }

    /**
     * Dark grayscale scheme using the gray palette for hierarchy on black.
     */
    object DarkGrayscale : EInkPalette {
        override val primary = PureWhite
        override val onPrimary = PureBlack
        override val primaryContainer = Gray03 // Dark gray for containers
        override val onPrimaryContainer = PureWhite
        override val secondary = Gray10 // Light gray for secondary elements
        override val onSecondary = PureBlack
        override val secondaryContainer = Gray04
        override val onSecondaryContainer = PureWhite
        override val background = PureBlack
        override val onBackground = PureWhite
        override val surface = Gray01 // Near black for surfaces
        override val onSurface = PureWhite
        override val surfaceVariant = Gray02 // Very dark gray for subtle differentiation
        override val onSurfaceVariant = PureWhite
        override val outline = Gray12 // Light gray for borders
        override val error = PureWhite
        override val onError = PureBlack
        override val disabledContent = Gray10
    }
}
