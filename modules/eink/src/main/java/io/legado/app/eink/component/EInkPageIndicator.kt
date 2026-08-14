package io.legado.app.eink.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.eInkColorScheme
import io.legado.app.eink.theme.eInkTypography

/**
 * Dot-style page indicator (static, no animation).
 *
 * The current page is shown as a larger filled dot and the rest as smaller
 * outline-colored dots. There is deliberately no transition animation: each
 * state is drawn as-is so the E-Ink controller can refresh once.
 *
 * @param currentPage Zero-based index of the current page
 * @param pageCount Total number of pages
 * @param modifier Modifier for the indicator row
 */
@Composable
fun EInkDotPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return

    val colors = eInkColorScheme()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage
            Box(
                modifier = Modifier
                    .size(if (isSelected) SelectedDotSize else UnselectedDotSize)
                    .background(
                        color = if (isSelected) colors.primary else colors.outline,
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * Number-style page indicator rendered as "第 X / Y 页" text (static).
 *
 * @param currentPage One-based current page number for display
 * @param pageCount Total number of pages
 * @param modifier Modifier for the indicator text
 */
@Composable
fun EInkNumberPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return

    EInkText(
        text = "第 $currentPage / $pageCount 页",
        style = eInkTypography().labelLarge,
        color = eInkColorScheme().onSurface,
        modifier = modifier.padding(EInkSpacing.s)
    )
}

/** Diameter of the dot marking the selected page. */
private val SelectedDotSize = 12.dp

/** Diameter of the dots marking unselected pages. */
private val UnselectedDotSize = 8.dp
