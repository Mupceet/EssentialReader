package io.legado.app.eink.designsystem.theme

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

/**
 * 受控灰阶 Token（规范 §1.3 Controlled Grayscale）。
 *
 * 语义空间按规范 §1.3 的十级命名定义；色值不新造，全部取自
 * [EInkColors] 的 16 级调色板（取最近值；gray100/gray50 为保持两级
 * 区分分别映射 Gray13/Gray14）。具体设备不要求实际显示全部灰阶——
 * 这里定义的是语义空间，语义色角色（[EInkColorScheme]）从本 Token
 * 取值组合，业务代码不得绕过 Theme 直接使用 GrayXX 或 alpha 混灰。
 */
@Stable
data object EInkGrayscale {

    /** 最深：主内容、强调边界。 */
    val black: Color = EInkColors.PureBlack

    /** 深灰（≈ 规范 #1A1A1A，取最近级 Gray02）。 */
    val gray900: Color = EInkColors.Gray02

    /** 结构边界（≈ 规范 #333333）。 */
    val gray700: Color = EInkColors.Gray03

    /** 次级内容（≈ 规范 #666666）。 */
    val gray500: Color = EInkColors.Gray06

    /** 中灰（≈ 规范 #808080，取最近级 Gray08）。 */
    val gray400: Color = EInkColors.Gray08

    /** 分隔线、辅助（≈ 规范 #999999）。 */
    val gray300: Color = EInkColors.Gray09

    /** 禁用内容（浅，≈ 规范 #CCCCCC）。 */
    val gray200: Color = EInkColors.Gray12

    /** 浅表面（≈ 规范 #E6E6E6）。 */
    val gray100: Color = EInkColors.Gray13

    /** 最浅灰表面（≈ 规范 #F5F5F5）。 */
    val gray50: Color = EInkColors.Gray14

    /** 最浅：底色。 */
    val white: Color = EInkColors.PureWhite
}

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

    /** 次级内容色（元信息、辅助图标）：语义化包装，当前与 onSurfaceVariant 同值。 */
    val secondaryContent: Color

    /** 强边界色（2dp 重要交互边界，规范 §7 borderStrong）。 */
    val borderStrong: Color

    /** 分隔线色（1dp 低成本结构线，规范 §11：优先 gray200/gray300 实灰）。 */
    val divider: Color

    /** 持久选中容器色（小面积控件：Tab/开关/复选）。 */
    val selected: Color

    /** 持久选中容器上的内容色。 */
    val selectedContent: Color
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
        override val disabledContent = EInkGrayscale.gray300
        override val secondaryContent = PureBlack
        override val borderStrong = PureBlack

        // 分隔线不参与最大对比（规范 §11：1dp 结构线优先实灰），
        // 与 Grayscale 浅色板同值——纯黑分隔线在页面上过于沉重
        override val divider = EInkGrayscale.gray300
        override val selected = primary
        override val selectedContent = onPrimary
    }

    /**
     * Grayscale color scheme with subtle hierarchy.
     * Uses the full 16-level grayscale palette for visual separation.
     */
    object Grayscale : EInkPalette {
        override val primary = PureBlack
        override val onPrimary = PureWhite
        override val primaryContainer = EInkGrayscale.gray100 // Light gray for containers
        override val onPrimaryContainer = PureBlack
        override val secondary = Gray05 // Dark gray for secondary elements
        override val onSecondary = PureWhite
        override val secondaryContainer = Gray12
        override val onSecondaryContainer = PureBlack
        override val background = PureWhite
        override val onBackground = PureBlack
        override val surface = PureWhite
        override val onSurface = PureBlack
        override val surfaceVariant =
            EInkGrayscale.gray50 // Very light gray for subtle differentiation
        override val onSurfaceVariant = PureBlack
        override val outline = EInkGrayscale.gray700 // Dark gray for borders
        override val error = PureBlack
        override val onError = PureWhite
        override val disabledContent = EInkGrayscale.gray300
        override val secondaryContent = PureBlack
        override val borderStrong = PureBlack
        override val divider = EInkGrayscale.gray300
        override val selected = primary
        override val selectedContent = onPrimary
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
        override val secondaryContent = PureWhite
        override val borderStrong = PureWhite

        // 深色高分隔线对齐 DarkGrayscale（实灰，不做最大对比）
        override val divider = EInkGrayscale.gray500
        override val selected = primary
        override val selectedContent = onPrimary
    }

    /**
     * Dark grayscale scheme using the gray palette for hierarchy on black.
     */
    object DarkGrayscale : EInkPalette {
        override val primary = PureWhite
        override val onPrimary = PureBlack
        override val primaryContainer = EInkGrayscale.gray700 // Dark gray for containers
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
        override val outline = EInkGrayscale.gray200 // Light gray for borders
        override val error = PureWhite
        override val onError = PureBlack
        override val disabledContent = Gray10
        override val secondaryContent = PureWhite
        override val borderStrong = PureWhite
        override val divider = EInkGrayscale.gray500
        override val selected = primary
        override val selectedContent = onPrimary
    }
}
