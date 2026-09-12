package io.legado.app.eink.feature.reader.selection

import androidx.compose.runtime.Stable

/**
 * 跨页续选会话（v2 Task 8，设计 §3.4/§4）：**章内位置为真值**的两端点模型，
 * 对齐完整模式 `ReaderSelection` 的 anchor/focus 口径。
 *
 * 为什么不再用「页内命中 + 区间极值单向累计」：模块的选区视觉原本以页内命中
 * （行下标 + 行内字符）为真值，翻页后行下标全部失效，只能靠 min/max 累计防
 * 回退——但累计只允许变大，于是跨页后选区**收不回来**：翻页瞬间手指还停在
 * 页底（触发翻页的位置），下一次 move 把被拖端点映射到新页末行（选区铺满整
 * 页），用户把手指往上抬想收到目标行时 max 仍保留整页边界，表现为「一跨页
 * 就飞、且选不对」（真机反馈）。本类只看章内位置：拖动就是给被拖端点**赋值**，
 * 可增可减；页内视觉每帧由 `[startPos, endPos)` ∩ 当前页推导，跨页不再需要
 * 任何累计补丁；被拖侧别也由 [draggingEnd] 记着，不再靠比较命中反推。
 *
 * - [startPos]/[endPos]：两端点的章内位置（右端开区间边界）。被拖端点越过
 *   对端时自动交换两端并在 [draggingEnd] 上跟着翻面（端点越界合法，选区只
 *   是换个方向），之后继续拖动仍作用在手指那一端；
 * - [draggingEnd]：手指拖着的是 [endPos] 侧（true）还是 [startPos] 侧；
 * - [includesTitle]：建立会话时选区是否含标题——§3.4 含标题不落划线的门禁
 *   依据（会话期间 capture 只收正文，标题不可达）；
 * - [segments]：翻页时刻捕获的各页文本段，仅供跨页 `selectedText` 拼接
 *   （与真值区间同源），不参与端点计算。
 */
@Stable
data class ReaderSelectionSession(
    val startPos: Int,
    val endPos: Int,
    val draggingEnd: Boolean,
    val includesTitle: Boolean,
    val segments: List<ReaderSelectionSegment> = emptyList(),
) {
    init {
        require(startPos <= endPos) { "session range must be normalized: $startPos..$endPos" }
    }

    /**
     * 把被拖端点移到 [position]：**直接赋值**（可增可减，跨页后能收回来）。
     *
     * 越过对端时交换两端并翻转 [draggingEnd]——侧别跟着手指走，不会出现
     * 「手指继续往同一方向拖，另一端的边界反而在动」的错乱。
     */
    fun moveDragged(position: Int): ReaderSelectionSession {
        val newStart = if (draggingEnd) startPos else position
        val newEnd = if (draggingEnd) position else endPos
        return if (newStart <= newEnd) {
            copy(startPos = newStart, endPos = newEnd)
        } else {
            copy(startPos = newEnd, endPos = newStart, draggingEnd = !draggingEnd)
        }
    }

    /** 并入一次翻页时刻的页段捕获结果（文本累计，不动端点）。 */
    fun withSegments(segments: List<ReaderSelectionSegment>): ReaderSelectionSession =
        copy(segments = segments)

    /**
     * 翻页时按**本次翻页方向**重置被拖端（只换侧，不动两端点的值）。
     *
     * 翻下一页（[forward] = true）说明手指在页底拖结束端；翻上一页说明手指在
     * 页顶拖起始端。会话内双向翻页（先下翻、再往回拖出页顶，或反之）必须每翻
     * 一次写一次——少写这一笔会让翻页边吸附落到**另一端**：反向翻页时把结束端
     * 吸到上一页首行，整段区间塌回上一页，表现为「向前翻页只选中翻页后的内容」
     * （真机反馈，重构时丢过这条）。
     */
    fun withFlipDirection(forward: Boolean): ReaderSelectionSession =
        copy(draggingEnd = forward)

    companion object {
        /**
         * 由页内选区建立会话：[draggingEnd] 按本次翻页方向定——向下翻（+1）是
         * 结束把手被拖到页底，向上翻（-1）是起始把手被拖到页顶。含标题的选区
         * 正文区间可能退化为零宽（[ReaderSelectionUi] 口径），照样如实记录。
         */
        fun from(selection: ReaderSelectionUi, draggingEnd: Boolean): ReaderSelectionSession =
            ReaderSelectionSession(
                startPos = minOf(selection.bodyStart, selection.bodyEnd),
                endPos = maxOf(selection.bodyStart, selection.bodyEnd),
                draggingEnd = draggingEnd,
                includesTitle = selection.includesTitle,
            )
    }
}
