package io.legado.app.eink.designsystem.theme

import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.theme.EInkSpacing.l
import io.legado.app.eink.designsystem.theme.EInkSpacing.m
import io.legado.app.eink.designsystem.theme.EInkSpacing.s
import io.legado.app.eink.designsystem.theme.EInkSpacing.xl
import io.legado.app.eink.designsystem.theme.EInkSpacing.xs
import io.legado.app.eink.designsystem.theme.EInkSpacing.xxl
import io.legado.app.eink.designsystem.theme.EInkSpacing.xxs

/**
 * Standard spacing scale for E-Ink optimized layouts.
 *
 * Extracted from the reference `EInkConstants.Spacing` into a standalone,
 * `@Stable` holder so it can be referenced uniformly (and optionally swapped
 * via a composition local in the future) without a Material dependency.
 *
 * Scale:
 *  - [xxs] 2dp  : fine dividers / hairlines
 *  - [xs]  4dp  : tight inner padding
 *  - [s]   8dp  : small gaps
 *  - [m]   16dp : default content padding
 *  - [l]   24dp : section separation
 *  - [xl]  32dp : large blocks
 *  - [xxl] 48dp : screen-edge / accessibility padding
 */
@Stable
data object EInkSpacing {
    /** 2dp — fine dividers and hairlines. */
    val xxs: Dp = 2.dp

    /** 4dp — tight inner padding. */
    val xs: Dp = 4.dp

    /** 8dp — small gaps between related elements. */
    val s: Dp = 8.dp

    /** 16dp — default content padding. */
    val m: Dp = 16.dp

    /** 24dp — section separation. */
    val l: Dp = 24.dp

    /** 32dp — large block separation. */
    val xl: Dp = 32.dp

    /** 48dp — screen-edge / minimum accessibility touch spacing. */
    val xxl: Dp = 48.dp
}
