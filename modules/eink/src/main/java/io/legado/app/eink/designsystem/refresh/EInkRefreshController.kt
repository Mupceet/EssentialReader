package io.legado.app.eink.designsystem.refresh

import androidx.compose.runtime.staticCompositionLocalOf
import io.legado.app.eink.designsystem.refresh.NoOpRefreshController.requestRefresh

/**
 * 刷新调度入口（规范 §67 Refresh-aware Rendering）。
 *
 * 组件只上报 [EInkRefreshIntent]；是否刷新、何时刷新、刷新区域由
 * Controller 统一决定（合批见规范 §68）。平台实现（BOOX/岩芯等）
 * 属 `:app` 宿主侧，本模块只定义端口：
 *
 * ```text
 * Component -> EInkRefreshIntent -> [EInkRefreshPolicy] -> EInkRefreshTier
 *           -> DeviceRefreshAdapter（平台侧） -> E-Ink HW
 * ```
 *
 * 实现必须主线程安全；重活内部派发。
 */
interface EInkRefreshController {

    /** 请求整屏范围的语义刷新。 */
    fun requestRefresh(intent: EInkRefreshIntent)

    /**
     * 请求仅刷新 [region] 脏区（规范 §68 合批预留：多个连续状态变化
     * 应尽可能合并为一次刷新事务）。设备不支持局部刷新时等效整屏。
     */
    fun requestRefresh(intent: EInkRefreshIntent, region: DirtyRegion)
}

/**
 * A no-op [EInkRefreshController].
 *
 * Use this on non-E-Ink devices (phones, tablets, LCD readers) or while the
 * platform EPD bridge is not yet wired up. It satisfies the interface without
 * issuing any hardware calls, so UI code can call [requestRefresh]
 * unconditionally.
 */
object NoOpRefreshController : EInkRefreshController {
    override fun requestRefresh(intent: EInkRefreshIntent) {
        // No-op on non-E-Ink devices.
    }

    override fun requestRefresh(intent: EInkRefreshIntent, region: DirtyRegion) {
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
 */
val LocalEInkRefreshController = staticCompositionLocalOf<EInkRefreshController> {
    NoOpRefreshController
}

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
