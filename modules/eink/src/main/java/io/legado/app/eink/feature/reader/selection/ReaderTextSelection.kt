package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import java.text.BreakIterator

/** 行内命中：行下标 + 行内拼接文本的字符偏移（UTF-16）。 */
data class ReaderTextHit(val lineIndex: Int, val charIndex: Int)

/**
 * 页内选区（Screen 本地 UI 状态）。正文区间为语义正文空间提示值：
 * 含标题行时取选区内正文行子区间（纯标题退化为 0..0，由宿主窗口搜索兜底）。
 */
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
 * 吸附失败（迭代器异常等）返回原命中。
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

/** 逐行选区高亮带（行盒为高、字符前缀宽为横向）。 */
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
