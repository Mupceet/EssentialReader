package io.legado.app.feature.reader.core.gesture

import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderPageWindow
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.selection.ReaderSelection
import io.legado.app.feature.reader.core.selection.mergeSelectionBounds

data class ReaderPagePlacement(
    val page: ReaderPage,
    val offsetY: Float,
) {
    fun containsViewportPoint(x: Float, y: Float): Boolean =
        x in 0f..page.widthPx.toFloat() && y in offsetY..(offsetY + page.scrollExtentPx)

    fun localY(viewportY: Float): Float = viewportY - offsetY
}

data class ReaderSelectionVisualBounds(
    val page: ReaderPage,
    val bounds: ReaderRect,
)

/** Single coordinate source for hit testing and selection across the three-page scroll stack. */
class ReaderPageViewportLayout private constructor(
    val placements: List<ReaderPagePlacement>,
) {
    fun pageAt(x: Float, y: Float): ReaderPagePlacement? =
        placements.firstOrNull { it.containsViewportPoint(x, y) }

    fun selectionBounds(selection: ReaderSelection): List<ReaderSelectionVisualBounds> =
        placements.flatMap { placement ->
            selection.bounds(placement.page).mergeSelectionBounds().map { bounds ->
                ReaderSelectionVisualBounds(
                    page = placement.page,
                    bounds = bounds.offsetY(placement.offsetY),
                )
            }
        }

    companion object {
        fun paged(window: ReaderPageWindow): ReaderPageViewportLayout =
            ReaderPageViewportLayout(
                listOfNotNull(window.current?.let { ReaderPagePlacement(it, 0f) }),
            )

        fun scroll(window: ReaderPageWindow, scrollOffsetPx: Float): ReaderPageViewportLayout {
            val current = window.current ?: return ReaderPageViewportLayout(emptyList())
            return ReaderPageViewportLayout(
                listOfNotNull(
                    window.previous?.let {
                        ReaderPagePlacement(it, scrollOffsetPx - it.scrollExtentPx)
                    },
                    ReaderPagePlacement(current, scrollOffsetPx),
                    window.next?.let {
                        ReaderPagePlacement(it, scrollOffsetPx + current.scrollExtentPx)
                    },
                    window.next?.let { next ->
                        window.nextPlus?.let { following ->
                            ReaderPagePlacement(
                                following,
                                scrollOffsetPx + current.scrollExtentPx + next.scrollExtentPx,
                            )
                        }
                    },
                ),
            )
        }
    }
}
