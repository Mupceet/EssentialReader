package io.legado.app.eink.refresh

/**
 * Coarse refresh strategy for an electrophoretic display request.
 *
 * @property NONE    No hardware refresh (the caller handles drawing itself).
 * @property PARTIAL Refresh only the dirty region — fast and low-flicker, but
 *                   can leave ghosting if the previous content was very dark.
 * @property FULL    Full-screen refresh; clears ghosting at the cost of a flash.
 */
enum class RefreshMode {
    NONE,
    PARTIAL,
    FULL
}

/**
 * Scheduling priority for a refresh request. Higher-priority requests preempt
 * deferred/batched work so user-visible interactions stay responsive.
 *
 * @property IMMEDIATE  Bypass all queuing; refresh right now (e.g. a dialog
 *                      appearing).
 * @property NORMAL     Default urgency; refreshed in the next vsync-aligned
 *                      pass.
 * @property DEFERRED   May be delayed until the screen would refresh anyway,
 *                      to coalesce with other work.
 * @property BATCHED    Explicitly grouped with other BATCHED requests and
 *                      flushed together (e.g. a clock tick or status update).
 */
enum class EInkUpdatePriority {
    IMMEDIATE,
    NORMAL,
    DEFERRED,
    BATCHED
}

/**
 * The reason a refresh is being requested. Lets the controller apply policy
 * (for example, forcing a FULL refresh every N page turns to clear ghosting).
 *
 * @property PAGE_CHANGE       Content page flip (reading navigation).
 * @property DIALOG            A dialog/sheet appeared or was dismissed.
 * @property USER_INTERACTION  A tap, selection, or other direct user action.
 * @property DATA_UPDATE       Backing data changed (list reload, etc.).
 * @property BATTERY           Battery indicator update.
 * @property CLOCK             Time/date tick.
 * @property SYSTEM            System-driven event (theme change, config, etc.).
 */
enum class RefreshReason {
    PAGE_CHANGE,
    DIALOG,
    USER_INTERACTION,
    DATA_UPDATE,
    BATTERY,
    CLOCK,
    SYSTEM
}

/**
 * An immutable description of a single refresh request.
 *
 * @param mode     The refresh strategy to apply.
 * @param priority Scheduling priority.
 * @param reason   Why the refresh is being requested (for policy/heuristics).
 */
data class RefreshIntent(
    val mode: RefreshMode,
    val priority: EInkUpdatePriority,
    val reason: RefreshReason
)

/**
 * A rectangular dirty region in absolute window pixel coordinates.
 *
 * Coordinates are inclusive on all sides: the region covers every pixel with
 * `x in [left, right]` and `y in [top, bottom]`. Callers should normalize so
 * that `left <= right` and `top <= bottom`; [normalized] helps enforce that.
 *
 * @property left   Left edge (x), inclusive.
 * @property top    Top edge (y), inclusive.
 * @property right  Right edge (x), inclusive.
 * @property bottom Bottom edge (y), inclusive.
 */
data class DirtyRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    /** Width in pixels; non-negative once normalized. */
    val width: Int get() = right - left + 1

    /** Height in pixels; non-negative once normalized. */
    val height: Int get() = bottom - top + 1

    /**
     * A copy with edges reordered so `left <= right` and `top <= bottom`,
     * which is what most E-Ink drivers expect.
     */
    fun normalized(): DirtyRegion = DirtyRegion(
        left = minOf(left, right),
        right = maxOf(left, right),
        top = minOf(top, bottom),
        bottom = maxOf(top, bottom)
    )

    companion object {
        /** A sentinel "no region" value. Callers may treat this as a no-op. */
        val Empty: DirtyRegion = DirtyRegion(0, 0, -1, -1)
    }
}
