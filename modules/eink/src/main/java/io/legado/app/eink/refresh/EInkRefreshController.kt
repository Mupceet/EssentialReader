package io.legado.app.eink.refresh

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Abstraction over the platform's E-Ink refresh mechanism.
 *
 * UI code requests refreshes through this interface instead of touching device
 * APIs directly, which keeps the Compose layer portable and testable. Concrete
 * implementations translate each request into the appropriate waveform/mode on
 * the underlying e-paper controller (e.g. Onyx/BOOX SDK, Rockchip EPD sysfs).
 *
 * The two overloads mirror the two refresh granularities:
 *  - [requestRefresh] with just mode + priority refreshes the whole screen.
 *  - [requestRefresh] with a [DirtyRegion] refreshes only that rectangle,
 *    which is cheaper and lower-flicker for small UI changes.
 *
 * Implementations must be safe to call from the main thread; heavy work should
 * be dispatched internally.
 */
interface EInkRefreshController {

    /**
     * Request a full-screen refresh.
     *
     * @param mode     Refresh strategy (PARTIAL, FULL, ...). NONE may be a no-op.
     * @param priority Scheduling priority.
     */
    fun requestRefresh(mode: RefreshMode, priority: EInkUpdatePriority)

    /**
     * Request a refresh of a specific dirty region.
     *
     * @param region   The rectangle to refresh, in absolute window pixel coords.
     * @param mode     Refresh strategy.
     * @param priority Scheduling priority.
     */
    fun requestRefresh(region: DirtyRegion, mode: RefreshMode, priority: EInkUpdatePriority)
}

/**
 * A no-op [EInkRefreshController].
 *
 * Use this on non-E-Ink devices (phones, tablets, LCD readers) or during early
 * development when the platform EPD bridge is not yet wired up. It satisfies the
 * interface without issuing any hardware calls, so UI code can call
 * [requestRefresh] unconditionally.
 */
object NoOpRefreshController : EInkRefreshController {
    override fun requestRefresh(mode: RefreshMode, priority: EInkUpdatePriority) {
        // No-op on non-E-Ink devices.
    }

    override fun requestRefresh(
        region: DirtyRegion,
        mode: RefreshMode,
        priority: EInkUpdatePriority
    ) {
        // No-op on non-E-Ink devices.
    }
}

/**
 * CompositionLocal providing the current [EInkRefreshController].
 *
 * Defaults to [NoOpRefreshController] so that any composable can read and call
 * it without first installing a provider — useful in previews and tests, and
 * safe on non-E-Ink hardware. A real device integration should override this
 * near the top of the composition (for example in the application Activity)
 * with a controller bound to the platform EPD API.
 *
 * Usage:
 * ```
 * val controller = LocalEInkRefreshController.current
 * controller.requestRefresh(RefreshMode.FULL, EInkUpdatePriority.IMMEDIATE)
 * ```
 */
val LocalEInkRefreshController = staticCompositionLocalOf<EInkRefreshController> {
    NoOpRefreshController
}
