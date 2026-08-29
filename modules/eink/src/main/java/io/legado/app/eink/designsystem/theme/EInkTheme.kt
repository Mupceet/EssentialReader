package io.legado.app.eink.designsystem.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.text.TextStyle
import io.legado.app.eink.designsystem.interaction.NoIndication

/**
 * E-Ink color scheme variants.
 *
 * - [HighContrast]: pure black/white only, maximum readability.
 * - [Grayscale]: uses the full 16-level gray palette for subtle hierarchy.
 */
enum class EInkColorVariant {
    HighContrast,
    Grayscale,
}

/**
 * E-Ink color scheme data class that holds the semantic theme colors.
 *
 * Intentionally mirrors the Material3 color-role names so it is a drop-in
 * mental model, but it carries **no** Material3 dependency.
 */
@Stable
data class EInkColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val error: Color,
    val onError: Color,

    /** Content color for disabled controls — a real gray level, never alpha. */
    val disabledContent: Color,

    /** 次级内容色（元信息、辅助图标），语义上独立于 onSurfaceVariant（规范 §4.1）。 */
    val secondaryContent: Color,

    /** 强边界色：2dp 重要交互边界 / 焦点外框（规范 §7）。 */
    val borderStrong: Color,

    /** 分隔线色：1dp 低成本结构线，实灰非 alpha（规范 §11）。 */
    val divider: Color,

    /** 持久选中容器色（小面积控件：Tab/开关/复选，规范 §14）。 */
    val selected: Color,

    /** 持久选中容器上的内容色。 */
    val selectedContent: Color,
)

/**
 * E-Ink typography system wrapping the 15 standard text styles.
 *
 * Exposed as an immutable data class (rather than the [EInkTypography] singleton
 * directly) so it can be overridden through [LocalEInkTypography] and compared
 * stably across recompositions.
 */
@Stable
data class EInkTypographySystem(
    val displayLarge: TextStyle,
    val displayMedium: TextStyle,
    val displaySmall: TextStyle,
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
)

/**
 * Resolves the active [EInkColorScheme] for the requested [variant] / [darkTheme].
 *
 * This replaces the previous ~90-line `createColorScheme` helper: the four
 * palette objects are turned into a single [EInkColorScheme] via the small
 * [toColorScheme] extension below, with no per-field boilerplate per branch.
 */
private fun resolveColorScheme(
    variant: EInkColorVariant,
    darkTheme: Boolean,
): EInkColorScheme {
    val palette = when (variant) {
        EInkColorVariant.HighContrast ->
            if (darkTheme) EInkColors.DarkHighContrast else EInkColors.HighContrast
        EInkColorVariant.Grayscale ->
            if (darkTheme) EInkColors.DarkGrayscale else EInkColors.Grayscale
    }
    return palette.toColorScheme()
}

/**
 * Maps any of the four nested palette objects in [EInkColors] to an
 * [EInkColorScheme]. Each palette object exposes the same set of `val`
 * properties, so a single shared `EInkPalette` supertype lets us do this
 * without reflection or per-object helpers.
 */
private fun EInkPalette.toColorScheme(): EInkColorScheme = EInkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    outline = outline,
    error = error,
    onError = onError,
    disabledContent = disabledContent,
    secondaryContent = secondaryContent,
    borderStrong = borderStrong,
    divider = divider,
    selected = selected,
    selectedContent = selectedContent,
)

/**
 * E-Ink theme accessor object (mirrors `MaterialTheme.colorScheme` usage).
 *
 * Read the active scheme / typography / content color via
 * [colorScheme], [typography] and [contentColor]; the theme itself is applied
 * by the [EInkTheme] composable function.
 */
object EInkTheme {

    /** The active [EInkColorScheme]. */
    val colorScheme: EInkColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalEInkColorScheme.current

    /** The active [EInkTypographySystem]. */
    val typography: EInkTypographySystem
        @Composable
        @ReadOnlyComposable
        get() = LocalEInkTypography.current

    /** The current E-Ink content color for the subtree. */
    val contentColor: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalEInkContentColor.current

    /**
     * 受控灰阶 Token（规范 §46：Theme 提供全量设计参数）。
     *
     * 灰阶是全局语义空间（与色彩变体无关），直通 [EInkGrayscale] 单例；
     * 后续如需按变体裁剪灰阶级数，改为 CompositionLocal 提供即可。
     */
    val grayscale: EInkGrayscale
        get() = EInkGrayscale

    /** 间距刻度，直通 [EInkSpacing] 单例（规范 §46）。 */
    val spacing: EInkSpacing
        get() = EInkSpacing

    /** 形状刻度，直通 [EInkShapes] 单例（规范 §46）。 */
    val shapes: EInkShapes
        get() = EInkShapes
}

/**
 * Main E-Ink theme composable.
 *
 * What it does:
 *  1. Resolves the color scheme from [colorVariant] / [darkTheme].
 *  2. Publishes the scheme, typography and the "on-surface" content color via
 *     the three `Local*` composition locals so descendants can read them
 *     through [EInkTheme.colorScheme], [EInkTheme.typography] and
 *     [EInkTheme.contentColor].
 *  3. **Globally disables ripple/indication** by providing
 *     `LocalIndication provides NoIndication` at the root. This is the key
 *     mechanism that prevents animated ripples (which cause full-screen
 *     refreshes on E-Ink) for any component that consults [LocalIndication].
 *
 * @param colorVariant The color scheme variant to use (HighContrast or Grayscale)
 * @param darkTheme Whether to use dark theme colors
 * @param content The content to theme
 */
@Composable
fun EInkTheme(
    colorVariant: EInkColorVariant = EInkColorVariant.HighContrast,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Remembered: scheme resolution allocates a new instance per call, and
    // EInkTheme wrapping content would otherwise churn instances on every
    // recomposition of the theme root.
    val colorScheme = remember(colorVariant, darkTheme) {
        resolveColorScheme(colorVariant, darkTheme)
    }
    val typography = DefaultTypography

    CompositionLocalProvider(
        LocalEInkColorScheme provides colorScheme,
        LocalEInkTypography provides typography,
        // The content color that descendants read via EInkTheme.contentColor;
        // defaults to onBackground so it is meaningful without manual wiring.
        LocalEInkContentColor provides colorScheme.onBackground,
        // Globally disable ripple indication at the theme root.
        LocalIndication provides NoIndication,
        // Static (non-blinking) text cursor: blinking is motion and causes
        // needless E-Ink refreshes, so the caret stays visible at full alpha.
        LocalCursorBlinkEnabled provides false,
    ) {
        content()
    }
}

/**
 * The single shared default typography system, derived from [EInkTypography].
 *
 * Constructed once and reused across recompositions (the underlying
 * [EInkTypography] singleton is immutable). This replaces the previous
 * per-call `createTypographySystem()` factory.
 */
private val DefaultTypography: EInkTypographySystem = with(EInkTypography) {
    EInkTypographySystem(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = displaySmall,
        headlineLarge = headlineLarge,
        headlineMedium = headlineMedium,
        headlineSmall = headlineSmall,
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        titleSmall = titleSmall,
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall,
    )
}

// ---------------------------------------------------------------------
// Composition locals & accessors
// ---------------------------------------------------------------------

/**
 * Holds the active [EInkColorScheme]. Defaults to an error so misuse fails loudly.
 *
 * Static local: the scheme instance never changes within a themed session, so
 * reads don't need per-scope subscription tracking (cheaper for list items);
 * a rare theme switch replaces the whole content composition, which is fine.
 */
val LocalEInkColorScheme = staticCompositionLocalOf<EInkColorScheme> {
    error("No EInkColorScheme provided. Wrap your content in EInkTheme { ... }.")
}

/**
 * Holds the active [EInkTypographySystem].
 *
 * Static local for the same reason as [LocalEInkColorScheme]: the value is a
 * singleton that never changes within a session.
 */
val LocalEInkTypography = staticCompositionLocalOf<EInkTypographySystem> {
    error("No EInkTypography provided. Wrap your content in EInkTheme { ... }.")
}

/**
 * Holds the "current content color" for the subtree, analogous to
 * Material's `LocalContentColor`. [EInkTheme] seeds it with the scheme's
 * `onBackground`; descendants may override it (e.g. inside a primary container)
 * via `CompositionLocalProvider(LocalEInkContentColor provides ...)` so nested
 * text/icons inherit the correct on-* color.
 */
val LocalEInkContentColor = staticCompositionLocalOf { Color.Unspecified }
