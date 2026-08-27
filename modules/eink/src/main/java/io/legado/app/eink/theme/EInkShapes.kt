package io.legado.app.eink.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Corner-radius scale for E-Ink optimized UI.
 *
 * E-Ink displays render crisp edges well and large radii can look "soft" or
 * muddy after partial refreshes, so the scale favors small radii. Values
 * follow the reference `EInkConstants.CornerRadius` but expose concrete
 * [Shape]s ready to drop into composables.
 *
 *  - [none]   0dp : sharp corners (default for most surfaces)
 *  - [small]  2dp : subtle rounding for chips/small buttons
 *  - [medium] 4dp : cards, dialogs
 *  - [large]  8dp : emphasized containers
 */
@Stable
data object EInkShapes {
    /** 0dp — sharp corners. */
    val none: Shape = RoundedCornerShape(0.dp)

    /** 2dp — subtle rounding. */
    val small: Shape = RoundedCornerShape(2.dp)

    /** 4dp — standard cards / dialogs. */
    val medium: Shape = RoundedCornerShape(4.dp)

    /** 8dp — emphasized containers. */
    val large: Shape = RoundedCornerShape(8.dp)
}
