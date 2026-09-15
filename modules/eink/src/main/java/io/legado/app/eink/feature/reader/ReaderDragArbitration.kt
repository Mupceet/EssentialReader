package io.legado.app.eink.feature.reader

import kotlin.math.abs
import kotlin.math.hypot

/** 阅读区拖动手势的所有权：待定 / 竖直下拉书签 / 水平翻页。 */
enum class ReaderDragOwner { PENDING, PULL, HORIZONTAL }

/**
 * 一次触摸手势内的仲裁进度。[pullLockedOut] 置位后本次手势不再回到书签：
 * 翻页可能已开始响应后续位移，对角方向回摆不得重新认领下拉。
 */
data class ReaderDragArbitrationState(
    val owner: ReaderDragOwner = ReaderDragOwner.PENDING,
    val pullLockedOut: Boolean = false,
)

/**
 * 阅读区拖动手势统一仲裁（横滑翻页 × 竖直下拉书签），输入为自按下起的
 * 累计位移。判据对齐完整模式 ReaderCanvasSurface / PullBookmarkGesture：
 * 书签只在向下且强竖直优势（|Σy| > |Σx|×1.5）时认领；翻页认领保持独立
 * 横向检测器的时机（|Σx| ≥ 触摸 slop），无竖直竞争者；认领后主导性翻转
 * 即交接翻页并锁存。
 */
object ReaderDragArbitration {

    /** 书签认领的竖直优势比：累计 |Σy| 须超过 |Σx| 的该倍数。 */
    const val PULL_DOMINANCE_RATIO = 1.5f

    /** 下拉书签触发距离（dp）：累计 Σy 达到即 toggle，对齐完整模式 80dp。 */
    const val PULL_TRIGGER_DISTANCE_DP = 80

    /** 仲裁门槛按累计位移的欧氏距离衡量，任一轴向过 slop 蕴含其中。 */
    fun isPastSlop(totalX: Float, totalY: Float, touchSlop: Float): Boolean =
        hypot(totalX, totalY) >= touchSlop

    fun arbitrate(
        state: ReaderDragArbitrationState,
        totalX: Float,
        totalY: Float,
        touchSlop: Float,
        bookmarkReady: Boolean,
    ): ReaderDragArbitrationState {
        if (state.owner == ReaderDragOwner.HORIZONTAL) return state
        if (!isPastSlop(totalX, totalY, touchSlop)) return state
        val pullCandidate = !state.pullLockedOut && bookmarkReady &&
            totalY > 0f && abs(totalY) > abs(totalX) * PULL_DOMINANCE_RATIO
        if (pullCandidate) return state.copy(owner = ReaderDragOwner.PULL)
        // 过 slop 后的样本不再候选（首样本即非候选，或认领后主导性翻转）：
        // 锁存书签，横移已过 slop 则当场交接翻页，否则回到待定等横移发展。
        return ReaderDragArbitrationState(
            owner = if (abs(totalX) >= touchSlop) {
                ReaderDragOwner.HORIZONTAL
            } else {
                ReaderDragOwner.PENDING
            },
            pullLockedOut = true,
        )
    }
}
