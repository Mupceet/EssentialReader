package io.legado.app.eink.feature.reader.selection

import androidx.compose.runtime.Stable
import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import java.text.BreakIterator

/** 行内命中：行下标 + 行内拼接文本的字符偏移（UTF-16）。 */
data class ReaderTextHit(val lineIndex: Int, val charIndex: Int)

/**
 * 页内选区（Screen 本地 UI 状态）。正文区间为语义正文空间提示值：
 * 含标题行时取选区内正文行子区间（纯标题退化为 0..0，由宿主窗口搜索兜底）。
 */
@Stable
data class ReaderSelectionUi(
    val startHit: ReaderTextHit,
    val endHit: ReaderTextHit,
    val selectedText: String,
    val bodyStart: Int,
    val bodyEnd: Int,
    val includesTitle: Boolean,
)

/** 行内拼接文本。 */
internal fun lineText(line: ReaderPageLine): String = line.chunks.joinToString("")

/** 行内字符偏移所在段下标；返回 chunk 下标到段内偏移。 */
internal fun locateChunk(line: ReaderPageLine, charIndex: Int): Pair<Int, Int> {
    var remaining = charIndex
    for (i in line.chunks.indices) {
        val length = line.chunks[i].length
        if (remaining <= length) return i to remaining
        remaining -= length
    }
    val last = line.chunks.lastIndex
    return last to line.chunks[last].length
}

/** 段内字符偏移对应的章内位置（段长连续，直接累加）。 */
internal fun chapterPositionOf(line: ReaderPageLine, charIndex: Int): Int {
    val (chunk, offsetInChunk) = locateChunk(line, charIndex)
    return line.chapterPositions[chunk] + offsetInChunk
}

/**
 * 行内装饰命中（v2 点按流）：返回第一个覆盖 [charIndex] 的装饰 run
 * （区间 [start, end)，右端开）。映射侧已按 markingId + 样式分组合并，
 * 同行 run 不重叠；未命中返回 null。
 */
fun findDecorationAt(line: ReaderPageLine, charIndex: Int): ReaderDecorationRun? =
    line.decorations.firstOrNull { charIndex in it.start until it.end }

/**
 * 点按命中装饰的选区快照（v2 点按流）：run 行内区间 → 选区两端与正文区间
 * （run 即行内拼接文本的字符索引，直接换算），selectedText = run 覆盖的
 * 行内文本段。点按场景 saveMarking 与浮条锚定共用此快照——同锚点落库
 * 命中原标记记录（单行标记精确命中；跨行标记以行内片段为锚点，宿主
 * 窗口搜索以提示位回溯）。
 * 行下标越界、run 区间越界钳制后退化为空区间时返回 null（防御宿主映射
 * 脏数据，调用方静默回落分区行为）。
 */
fun selectionOfDecoration(
    page: ReaderPageSnapshot,
    lineIndex: Int,
    run: ReaderDecorationRun,
): ReaderSelectionUi? {
    val line = page.lines.getOrNull(lineIndex) ?: return null
    val text = lineText(line)
    val start = run.start.coerceIn(0, text.length)
    val end = run.end.coerceIn(start, text.length)
    if (start >= end) return null
    return ReaderSelectionUi(
        startHit = ReaderTextHit(lineIndex, start),
        endHit = ReaderTextHit(lineIndex, end),
        selectedText = text.substring(start, end),
        bodyStart = chapterPositionOf(line, start),
        bodyEnd = chapterPositionOf(line, end),
        includesTitle = line.isTitle,
    )
}

/** 命中测试：y 按行盒定行（含容差半行高），x 按前缀宽度定最近字符。 */
fun hitTest(
    snapshot: ReaderPageSnapshot,
    x: Float,
    y: Float,
    measure: (String) -> Float,
): ReaderTextHit? {
    if (snapshot.lines.isEmpty()) return null
    // 定行：取行盒中心距 y 最近的行，距离超过半行高视为未命中
    var best = -1
    var bestDistance = Float.MAX_VALUE
    snapshot.lines.forEachIndexed { index, line ->
        val center = (line.top + line.bottom) / 2f
        val distance = kotlin.math.abs(y - center)
        if (distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    val line = snapshot.lines[best]
    val halfHeight = (line.bottom - line.top) / 2f
    if (bestDistance > halfHeight) return null
    return ReaderTextHit(best, hitCharInLine(line, x, measure))
}

/** 行内命中字符：逐段判右缘，段内按字符中线求 x 落点，钳制到 [0, 行长]。 */
internal fun hitCharInLine(line: ReaderPageLine, x: Float, measure: (String) -> Float): Int {
    var offset = 0
    for (i in line.chunks.indices) {
        val chunk = line.chunks[i]
        val chunkLeft = line.x[i]
        val chunkWidth = measure(chunk)
        if (x > chunkLeft + chunkWidth && i != line.chunks.lastIndex) {
            offset += chunk.length
            continue
        }
        var acc = 0f
        for (j in chunk.indices) {
            val charWidth = measure(chunk[j].toString())
            if (x <= chunkLeft + acc + charWidth / 2f) return offset + j
            acc += charWidth
        }
        return offset + chunk.length
    }
    return offset
}

/** 端点按（行、字符）升序规范。 */
fun normalizeHits(a: ReaderTextHit, b: ReaderTextHit): Pair<ReaderTextHit, ReaderTextHit> =
    if (a.lineIndex < b.lineIndex || (a.lineIndex == b.lineIndex && a.charIndex <= b.charIndex)) a to b
    else b to a

/**
 * 由两端命中构建选区：跨行拼接文本（正文间隙 >0 时补一个换行，
 * 对应语义正文空间的段落分隔符/占位字符），正文区间只统计正文行。
 */
fun buildSelection(
    snapshot: ReaderPageSnapshot,
    hitA: ReaderTextHit,
    hitB: ReaderTextHit,
): ReaderSelectionUi? {
    val (start, end) = normalizeHits(hitA, hitB)
    if (start.lineIndex !in snapshot.lines.indices ||
        end.lineIndex !in snapshot.lines.indices
    ) return null
    val text = StringBuilder()
    var bodyStart = 0
    var bodyEnd = 0
    var bodySeen = false
    var includesTitle = false
    for (index in start.lineIndex..end.lineIndex) {
        val line = snapshot.lines[index]
        if (line.isTitle) includesTitle = true
        val from = if (index == start.lineIndex) start.charIndex else 0
        val to = if (index == end.lineIndex) end.charIndex else lineText(line).length
        val piece = lineText(line).substring(from, to)
        if (index > start.lineIndex) {
            val prev = snapshot.lines[index - 1]
            if (prev.isTitle || line.isTitle) {
                // 标题/正文异空间：按段落分隔呈现
                text.append('\n')
            } else {
                // 正文行之间：软换行（gap=0）不补，正文间隙（段落/占位字符）补一个换行
                val prevEnd = chapterPositionOf(prev, lineText(prev).length)
                val curStart = line.chapterPositions.first()
                if (curStart - prevEnd > 0) text.append('\n')
            }
        }
        text.append(piece)
        if (!line.isTitle) {
            val pieceStart = chapterPositionOf(line, from)
            val pieceEnd = pieceStart + (to - from)
            if (!bodySeen) {
                bodyStart = pieceStart
                bodySeen = true
            }
            bodyEnd = pieceEnd
        }
    }
    return ReaderSelectionUi(
        startHit = start,
        endHit = end,
        selectedText = text.toString(),
        bodyStart = bodyStart,
        bodyEnd = bodyEnd,
        includesTitle = includesTitle,
    )
}

/**
 * 长按选词：BreakIterator 词边界吸附（中日文逐字、拉丁按词）。
 * 词边界退化、空文本或行不存在时返回原命中。
 */
fun snapToWord(snapshot: ReaderPageSnapshot, hit: ReaderTextHit): ReaderTextHit {
    val line = snapshot.lines.getOrNull(hit.lineIndex) ?: return hit
    val text = lineText(line)
    if (text.isEmpty()) return hit
    val iterator = BreakIterator.getWordInstance()
    iterator.setText(text)
    var wordStart = iterator.first()
    while (wordStart != BreakIterator.DONE) {
        val wordEnd = iterator.next()
        if (wordEnd == BreakIterator.DONE) break
        if (hit.charIndex in wordStart until wordEnd ||
            (hit.charIndex >= text.length && wordEnd == text.length)
        ) {
            return hit.copy(charIndex = wordStart)
        }
        wordStart = wordEnd
    }
    return hit
}

/**
 * 长按选词的区间版本：把命中字符闭合成完整词区间（BreakIterator 词边界），
 * 返回（词首命中, 词尾命中），供 [buildSelection] 构建非零长度选区——
 * 裸长按（未拖拽）即选中一个词，复制/书签/笔记不再得到空文本。
 * 命中在行尾（charIndex == 行长）时取最后一段；空文本或行不存在时
 * 返回（原命中, 原命中）；词边界退化（空分段）时钳制出长度 1 的区间，
 * 避免零长度选区。
 */
fun snapToWordRange(
    snapshot: ReaderPageSnapshot,
    hit: ReaderTextHit,
): Pair<ReaderTextHit, ReaderTextHit> {
    val line = snapshot.lines.getOrNull(hit.lineIndex) ?: return hit to hit
    val text = lineText(line)
    if (text.isEmpty()) return hit to hit
    val iterator = BreakIterator.getWordInstance()
    iterator.setText(text)
    var wordStart = iterator.first()
    while (wordStart != BreakIterator.DONE) {
        val wordEnd = iterator.next()
        if (wordEnd == BreakIterator.DONE) break
        if (hit.charIndex in wordStart until wordEnd ||
            (hit.charIndex >= text.length && wordEnd == text.length)
        ) {
            // 退化分段防御：end = wordStart + 1 钳制到行长，保证区间非空
            val safeEnd = if (wordEnd <= wordStart) {
                (wordStart + 1).coerceAtMost(text.length)
            } else {
                wordEnd
            }
            return hit.copy(charIndex = wordStart) to hit.copy(charIndex = safeEnd)
        }
        wordStart = wordEnd
    }
    return hit to hit
}

/** 逐行选区 run（行盒为高、字符前缀宽为横向；预览下划线/浮条锚定/命中均以此为准）。 */
data class SelectionRun(
    val lineIndex: Int,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

fun selectionRuns(
    snapshot: ReaderPageSnapshot,
    selection: ReaderSelectionUi,
    measureTitle: (String) -> Float,
    measureContent: (String) -> Float,
): List<SelectionRun> {
    val (start, end) = selection.startHit to selection.endHit
    val runs = ArrayList<SelectionRun>()
    for (index in start.lineIndex..end.lineIndex) {
        val line = snapshot.lines.getOrNull(index) ?: continue
        val measure = if (line.isTitle) measureTitle else measureContent
        val text = lineText(line)
        val from = if (index == start.lineIndex) start.charIndex else 0
        val to = if (index == end.lineIndex) end.charIndex else text.length
        val left = charX(line, from, measure)
        val right = charX(line, to, measure)
        runs += SelectionRun(index, left, right, line.top, line.bottom)
    }
    return runs
}

/** 行内字符偏移的 x 坐标（逐段累加前缀宽）。 */
internal fun charX(line: ReaderPageLine, charIndex: Int, measure: (String) -> Float): Float {
    val (chunk, offsetInChunk) = locateChunk(line, charIndex)
    var x = line.x[chunk]
    val text = line.chunks[chunk]
    for (i in 0 until offsetInChunk) x += measure(text[i].toString())
    return x
}

/** 把手锚点：首 run 左上（起始把手）与末 run 右上（末端把手）。 */
fun handleAnchor(runs: List<SelectionRun>): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
    val first = runs.firstOrNull() ?: return null
    val last = runs.lastOrNull() ?: return null
    return (first.left to first.top) to (last.right to last.top)
}

// ==================== 跨页续选会话（v2 Task 8） ====================

/**
 * 跨页会话中一页被选中的文本段（章内端点用于跨段拼接的 gap 判定）。
 * 端点为语义正文空间 UTF-16 索引，[text] 段内拼接口径与 [buildSelection]
 * 一致（正文行间 gap>0 补换行）。
 */
data class ReaderSelectionSegment(val text: String, val startPos: Int, val endPos: Int)

/**
 * 当前页内 [rangeStart, rangeEnd) ∩ 页正文范围的文本段（端点经
 * [chapterPositionOf] 换算）。标题行在标题空间、无正文语义，不参与拼接；
 * 正文行物理不相邻（中间隔标题/空行）或章内 gap>0（段落分隔符/占位字符）
 * 时补一个换行，与 [buildSelection] 的 gap 规则同源。交集为空（含零长
 * 区间、零长度行）返回 null。
 */
fun captureSegment(
    snapshot: ReaderPageSnapshot,
    rangeStart: Int,
    rangeEnd: Int,
): ReaderSelectionSegment? {
    if (rangeStart >= rangeEnd) return null
    val text = StringBuilder()
    var startPos = 0
    var endPos = 0
    var seen = false
    var prevLineIndex = -1
    var prevLineEnd = 0
    snapshot.lines.forEachIndexed { index, line ->
        if (line.isTitle) return@forEachIndexed
        val lineLength = lineText(line).length
        if (lineLength == 0) return@forEachIndexed
        val lineStart = line.chapterPositions.first()
        val lineEnd = lineStart + lineLength
        val from = maxOf(rangeStart, lineStart)
        val to = minOf(rangeEnd, lineEnd)
        if (from >= to) return@forEachIndexed
        if (seen) {
            // 物理不相邻（隔标题/空行）按段落分隔；相邻正文行按章内 gap 判
            val physicallyAdjacent = index == prevLineIndex + 1
            if (!physicallyAdjacent || from - prevLineEnd > 0) text.append('\n')
        } else {
            startPos = from
        }
        text.append(lineText(line).substring(from - lineStart, to - lineStart))
        endPos = to
        prevLineIndex = index
        prevLineEnd = lineEnd
        seen = true
    }
    if (!seen) return null
    return ReaderSelectionSegment(text.toString(), startPos, endPos)
}

/**
 * 页段按 [ReaderSelectionSegment.startPos] 排序拼接；相邻 gap>0 补一个
 * 换行（与 [buildSelection] 的 gap 规则同源），gap<=0 直连。空列表返回
 * 空串。
 */
fun joinSegments(segments: List<ReaderSelectionSegment>): String {
    if (segments.isEmpty()) return ""
    val text = StringBuilder()
    var prevEnd = 0
    segments.sortedBy { it.startPos }.forEachIndexed { index, segment ->
        if (index > 0 && segment.startPos - prevEnd > 0) text.append('\n')
        text.append(segment.text)
        prevEnd = segment.endPos
    }
    return text.toString()
}

/**
 * 会话段合并：保留与 [incoming] 不相交的既有段，重叠段（同页重捕——
 * 会话内翻回已累计页后区间只会扩大）由更完整的新段覆盖。
 */
fun mergeSegment(
    segments: List<ReaderSelectionSegment>,
    incoming: ReaderSelectionSegment,
): List<ReaderSelectionSegment> =
    segments.filter { it.endPos <= incoming.startPos || it.startPos >= incoming.endPos } + incoming

/**
 * 把手端点处于页顶/页底触发带（首行/末行行盒区间，一行高）内的翻页方向：
 * 起始把手只判页顶（-1 上一页），结束把手只判页底（+1 下一页），
 * 非触发带或不匹配的把手侧返回 null。
 */
fun flipDirection(
    hit: ReaderTextHit,
    snapshot: ReaderPageSnapshot,
    handleIsStart: Boolean,
): Int? = when {
    handleIsStart && hit.lineIndex == 0 -> -1
    !handleIsStart && hit.lineIndex == snapshot.lines.lastIndex -> 1
    else -> null
}

/**
 * 把手拖拽的翻页方向判定（指针级）：命中结果进 [flipDirection] 判触发带；
 * 命中为空（拖出文本行盒）时按越出方向兜底——起始把手越过页顶、结束把手
 * 越过页底仍视为按住触发带（手指拖出页缘不解除翻页意图），文本行盒之间
 * 的空档不判触发。页面无文本行返回 null。
 */
fun flipDirectionForPointer(
    snapshot: ReaderPageSnapshot,
    hit: ReaderTextHit?,
    y: Float,
    handleIsStart: Boolean,
): Int? {
    hit?.let { return flipDirection(it, snapshot, handleIsStart) }
    val first = snapshot.lines.firstOrNull() ?: return null
    val last = snapshot.lines.lastOrNull() ?: return null
    return when {
        handleIsStart && y <= first.top -> -1
        !handleIsStart && y >= last.bottom -> 1
        else -> null
    }
}

/**
 * 空命中（拖出文本行盒）的把手侧归属：按越出边判定——页顶外 = 起始侧
 * （页顶触发带 → -1），页底外 = 结束侧（页底触发带 → +1），文本行盒之间
 * 的空档返回 null（调用方维持最近归属，不判触发）。会话双向翻页（设计
 * §4）依赖此归属：前向翻页（结束把手拖拽）后反向拖出页顶、向后翻页
 * （起始把手拖拽）后正向拖出页底，空命中均按越出边归属对侧把手，向后/
 * 向前翻可达；非空命中的归属仍按把手侧（见 [flipDirection]）。页面无
 * 文本行返回 null。
 */
fun offPageHandleIsStart(snapshot: ReaderPageSnapshot, y: Float): Boolean? {
    val first = snapshot.lines.firstOrNull() ?: return null
    val last = snapshot.lines.lastOrNull() ?: return null
    return when {
        y <= first.top -> true
        y >= last.bottom -> false
        else -> null
    }
}

/**
 * 翻页时刻离页段的捕获区间（提交范围 = 文本）：被拖侧端用会话边界（手指
 * 到达处），对侧端扩到离开页的正文边——被选部分直到页边整段捕获，与页变
 * 效应膨胀到翻页边的会话边界严格一致，不产生未被 selectedText 覆盖的落库
 * 区间。direction < 0 向后翻（起始把手上翻）取 [离开页正文起始, rangeEnd]；
 * direction > 0 向前翻（结束把手下翻）取 [rangeStart, 离开页正文末尾]。
 * 标题行在标题空间、无正文语义，不参与正文边（同 captureSegment 口径）；
 * 页面无正文行返回 null。
 */
fun flipCaptureRange(
    snapshot: ReaderPageSnapshot,
    direction: Int,
    rangeStart: Int,
    rangeEnd: Int,
): Pair<Int, Int>? {
    val first = snapshot.lines.firstOrNull { !it.isTitle && lineText(it).isNotEmpty() }
        ?: return null
    val last = snapshot.lines.lastOrNull { !it.isTitle && lineText(it).isNotEmpty() }
        ?: return null
    return if (direction < 0) {
        first.chapterPositions.first() to rangeEnd
    } else {
        rangeStart to (last.chapterPositions.first() + lineText(last).length)
    }
}

/**
 * 会话翻页边吸附命中：起始把手（向后翻）吸附新页末正文行末字符，结束
 * 把手（向前翻）吸附新页首正文行首字符；端点章内位置由调用方经
 * [chapterPositionOf] 换算。页面无正文行返回 null。
 */
fun flipEdgeHit(snapshot: ReaderPageSnapshot, handleIsStart: Boolean): ReaderTextHit? {
    val line = if (handleIsStart) {
        snapshot.lines.lastOrNull { !it.isTitle }
    } else {
        snapshot.lines.firstOrNull { !it.isTitle }
    } ?: return null
    val index = snapshot.lines.indexOf(line)
    val charIndex = if (handleIsStart) lineText(line).length else 0
    return ReaderTextHit(index, charIndex)
}

/**
 * 会话视觉选区：以章内区间 [rangeStart, rangeEnd) ∩ 页正文范围裁剪出
 * startHit/endHit，交 [buildSelection] 构造（文本拼接与位置口径天然一致）。
 * 范围与页正文完全无交集时返回覆盖全页的呈现（会话视觉不消失——翻页刷新
 * 窗口/整页越界的过渡态）；页面无文本行返回 null。
 */
fun selectionFromChapterRange(
    snapshot: ReaderPageSnapshot,
    rangeStart: Int,
    rangeEnd: Int,
): ReaderSelectionUi? {
    if (snapshot.lines.isEmpty()) return null
    var startHit: ReaderTextHit? = null
    var endHit: ReaderTextHit? = null
    snapshot.lines.forEachIndexed { index, line ->
        if (line.isTitle) return@forEachIndexed
        val lineLength = lineText(line).length
        if (lineLength == 0) return@forEachIndexed
        val lineStart = line.chapterPositions.first()
        val from = maxOf(rangeStart, lineStart)
        val to = minOf(rangeEnd, lineStart + lineLength)
        if (from >= to) return@forEachIndexed
        if (startHit == null) startHit = ReaderTextHit(index, from - lineStart)
        endHit = ReaderTextHit(index, to - lineStart)
    }
    val clippedStart = startHit
    val clippedEnd = endHit
    if (clippedStart != null && clippedEnd != null) {
        return buildSelection(snapshot, clippedStart, clippedEnd)
    }
    // 覆盖全页兜底：首行首字符 → 末行末字符（含标题行）。
    // 防御性兜底，现调用方不会到达（均已前置 captureSegment != null 守卫）
    val last = snapshot.lines.last()
    return buildSelection(snapshot, ReaderTextHit(0, 0), ReaderTextHit(snapshot.lines.lastIndex, lineText(last).length))
}

/**
 * 翻页触发状态机（一次按住内）：端点进入触发带起计时，持续按住超过
 * [timeoutMillis] 上抛方向一次；触发后 disarm（带内不重复触发），拖出
 * 触发带重新武装并清计时。时间由调用方注入（如 SystemClock.elapsedRealtime
 * 的单调毫秒），保持纯逻辑可单测。
 */
class FlipTrigger(private val timeoutMillis: Long) {
    private var armed = true
    private var bandEnterTimeMillis = 0L

    /**
     * 每次 move 上报方向判定：null = 端点在触发带外（重新武装并清计时）；
     * 非 null 且返回值非 null = 应触发翻页（方向与入参一致）。
     */
    fun onDirection(direction: Int?, nowMillis: Long): Int? {
        if (direction == null) {
            armed = true
            bandEnterTimeMillis = 0L
            return null
        }
        if (!armed) return null
        if (bandEnterTimeMillis == 0L) {
            bandEnterTimeMillis = nowMillis
            return null
        }
        return if (nowMillis - bandEnterTimeMillis >= timeoutMillis) {
            armed = false
            bandEnterTimeMillis = 0L
            direction
        } else {
            null
        }
    }
}
