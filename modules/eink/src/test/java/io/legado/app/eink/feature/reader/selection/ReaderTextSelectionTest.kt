package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假等宽测量：每字符 10px，便于手算。 */
private val measure: (String) -> Float = { it.length * 10f }

private fun line(
    vararg chunks: String,
    positions: IntArray,
    baseY: Float = 50f,
    top: Float = 30f,
    bottom: Float = 70f,
    isTitle: Boolean = false,
    decorations: List<ReaderDecorationRun> = emptyList(),
): ReaderPageLine = ReaderPageLine(
    baseY = baseY, isTitle = isTitle, chunks = chunks.toList(),
    x = FloatArray(chunks.size) { i -> i * 100f },
    chapterPositions = positions, top = top, bottom = bottom,
    decorations = decorations,
)

/** 实线装饰 run（点按命中测试用）。 */
private fun decorationRun(
    start: Int,
    end: Int,
    markingId: String = "m1",
): ReaderDecorationRun = ReaderDecorationRun(
    start = start, end = end, underlineMode = 1, highlight = false, markingId = markingId,
)

private fun snapshot(vararg lines: ReaderPageLine) = ReaderPageSnapshot(
    title = "章", readProgress = "1/1",
    titleSpec = ReaderPaintSpec(20f, 0f, null, null),
    contentSpec = ReaderPaintSpec(20f, 0f, null, null),
    lines = lines.toList(), images = emptyList(),
)

class ReaderTextSelectionTest {

    @Test
    fun `命中测试按行盒定行按前缀宽度定字符`() {
        val snap = snapshot(line("abcdef", positions = intArrayOf(0)))
        // 行内 x=25 = 第 2 字符（span [20,30)）中线 → 命中字符 2
        assertEquals(ReaderTextHit(0, 2), hitTest(snap, x = 25f, y = 50f, measure = measure))
        // 行盒外
        assertNull(hitTest(snap, x = 25f, y = 200f, measure = measure))
    }

    @Test
    fun `命中交换端点后区间规范有序`() {
        val a = ReaderTextHit(0, 5)
        val b = ReaderTextHit(2, 1)
        val (start, end) = normalizeHits(a, b)
        assertEquals(ReaderTextHit(0, 5), start)
        assertEquals(ReaderTextHit(2, 1), end)
    }

    @Test
    fun `区间构建跨行拼接文本并计入正文间隙`() {
        val snap = snapshot(
            line("第一段落", positions = intArrayOf(0)),
            line("续行", positions = intArrayOf(4)),      // 软换行：gap=0
            line("新段落", positions = intArrayOf(7)),      // 段落间隙：gap=1（\n）
        )
        val sel = buildSelection(
            snap, ReaderTextHit(0, 2), ReaderTextHit(2, 1),
        )!!
        assertEquals("段落续行\n新", sel.selectedText)
        assertEquals(2, sel.bodyStart)
        assertEquals(8, sel.bodyEnd)
        assertFalse(sel.includesTitle)
    }

    @Test
    fun `含标题行选区标记 includesTitle 且正文区间取正文行`() {
        val snap = snapshot(
            line("标题", positions = intArrayOf(100), isTitle = true),
            line("正文内容", positions = intArrayOf(0)),
        )
        val sel = buildSelection(
            snap, ReaderTextHit(0, 0), ReaderTextHit(1, 2),
        )!!
        assertTrue(sel.includesTitle)
        assertEquals(0, sel.bodyStart)
        assertEquals(2, sel.bodyEnd)
        assertEquals("标题\n正文", sel.selectedText)
    }

    @Test
    fun `纯标题选区正文区间退化为零`() {
        val snap = snapshot(line("标题", positions = intArrayOf(100), isTitle = true))
        val sel = buildSelection(snap, ReaderTextHit(0, 0), ReaderTextHit(0, 2))!!
        assertTrue(sel.includesTitle)
        assertEquals(0, sel.bodyStart)
        assertEquals(0, sel.bodyEnd)
        assertEquals("标题", sel.selectedText)
    }

    @Test
    fun `选词吸附到词边界`() {
        val snap = snapshot(line("hello world", positions = intArrayOf(0)))
        // 落在 "world" 中间的字符上 → 吸附到词首
        val hit = snapToWord(snap, ReaderTextHit(0, 8))
        assertEquals(ReaderTextHit(0, 6), hit)
    }

    @Test
    fun `长按选词区间闭合到完整词`() {
        val snap = snapshot(line("hello world", positions = intArrayOf(0)))
        // 裸长按（未拖拽）落在 "world" 中间 → 区间两端闭合到词首/词尾
        val (wordStart, wordEnd) = snapToWordRange(snap, ReaderTextHit(0, 8))
        assertEquals(ReaderTextHit(0, 6), wordStart)
        assertEquals(ReaderTextHit(0, 11), wordEnd)
        val sel = buildSelection(snap, wordStart, wordEnd)!!
        assertEquals("world", sel.selectedText)
        assertEquals(6, sel.bodyStart)
        assertEquals(11, sel.bodyEnd)
    }

    @Test
    fun `中文选词区间非退化`() {
        val snap = snapshot(line("你好世界今天阅读", positions = intArrayOf(0)))
        val (wordStart, wordEnd) = snapToWordRange(snap, ReaderTextHit(0, 3))
        // 分词粒度随 ICU 词典变化，只钉非退化与包含关系：命中字符落在区间内
        assertTrue(wordStart.charIndex <= 3 && 3 < wordEnd.charIndex)
        assertTrue(wordStart.charIndex < wordEnd.charIndex)
        val sel = buildSelection(snap, wordStart, wordEnd)!!
        assertTrue(sel.selectedText.isNotEmpty())
    }

    @Test
    fun `选区几何产出逐行高亮带`() {
        val snap = snapshot(
            line("abcdef", positions = intArrayOf(0), top = 30f, bottom = 70f),
            line("ghijkl", positions = intArrayOf(6), top = 80f, bottom = 120f),
        )
        val sel = buildSelection(snap, ReaderTextHit(0, 3), ReaderTextHit(1, 2))!!
        val runs = selectionRuns(snap, sel, measure, measure)
        assertEquals(2, runs.size)
        // 首行从第 3 字符左缘（x[0]=0 + 30）到行尾（0 + 60）
        assertEquals(30f, runs[0].left)
        assertEquals(60f, runs[0].right)
        assertEquals(30f, runs[0].top)
        assertEquals(70f, runs[0].bottom)
        // 次行整行到第 2 字符右缘（单段行 x[0]=0）
        assertEquals(0f, runs[1].left)
        assertEquals(20f, runs[1].right)
    }

    @Test
    fun `把手锚点取首带左缘与末带右缘且高度取行盒`() {
        val snap = snapshot(
            line("abcdef", positions = intArrayOf(0), top = 30f, bottom = 70f),
            line("ghijkl", positions = intArrayOf(6), top = 80f, bottom = 120f),
        )
        val sel = buildSelection(snap, ReaderTextHit(0, 3), ReaderTextHit(1, 2))!!
        val runs = selectionRuns(snap, sel, measure, measure)
        val (startAnchor, endAnchor) = handleAnchors(runs)!!
        // 竖条贯穿行盒（手柄高度 = 文本行高），x 取首带左缘/末带右缘
        assertEquals(SelectionHandleAnchor(x = 30f, top = 30f, bottom = 70f), startAnchor)
        assertEquals(SelectionHandleAnchor(x = 20f, top = 80f, bottom = 120f), endAnchor)
        assertNull(handleAnchors(emptyList()))
    }

    @Test
    fun `拖动末端把手越过起始端时固定端不跟随`() {
        val snap = snapshot(line("abcdefghij", positions = intArrayOf(0)))
        var sel = buildSelection(snap, ReaderTextHit(0, 2), ReaderTextHit(0, 8))!!
        // 右把手往左拖越过起始端：被拖端换到起始侧，固定端（原起始 2）留在原处
        var hit = ReaderTextHit(0, 1)
        var isStart = false
        sel = moveEndpoint(snap, sel, isStart, hit)
        assertEquals(ReaderTextHit(0, 1), sel.startHit)
        assertEquals(ReaderTextHit(0, 2), sel.endHit)
        assertTrue(draggingEndpointIsStart(sel, hit))
        isStart = draggingEndpointIsStart(sel, hit)
        // 继续往左拖：固定端仍在 2（旧实现会把固定端当成被拖端，塌到上一帧手指位）
        hit = ReaderTextHit(0, 0)
        sel = moveEndpoint(snap, sel, isStart, hit)
        assertEquals(ReaderTextHit(0, 0), sel.startHit)
        assertEquals(ReaderTextHit(0, 2), sel.endHit)
    }

    @Test
    fun `拖动起始把手越过末端时固定端不跟随`() {
        val snap = snapshot(line("abcdefghij", positions = intArrayOf(0)))
        var sel = buildSelection(snap, ReaderTextHit(0, 2), ReaderTextHit(0, 5))!!
        // 左把手往右拖越过末端：固定端（原末端 5）留在原处
        var hit = ReaderTextHit(0, 7)
        sel = moveEndpoint(snap, sel, isStart = true, hit)
        assertEquals(ReaderTextHit(0, 5), sel.startHit)
        assertEquals(ReaderTextHit(0, 7), sel.endHit)
        assertFalse(draggingEndpointIsStart(sel, hit))
        val isStart = draggingEndpointIsStart(sel, hit)
        hit = ReaderTextHit(0, 9)
        sel = moveEndpoint(snap, sel, isStart, hit)
        assertEquals(ReaderTextHit(0, 5), sel.startHit)
        assertEquals(ReaderTextHit(0, 9), sel.endHit)
    }

    @Test
    fun `选区命中标记返回覆盖字符的markingId`() {
        val snap = snapshot(
            line(
                "第一段落", positions = intArrayOf(0),
                decorations = listOf(decorationRun(2, 4, "m1")),
            ),
            line("续行", positions = intArrayOf(4)),
        )
        // 跨行选区与首行装饰相交 → 取该标记 id
        val spanning = buildSelection(snap, ReaderTextHit(0, 2), ReaderTextHit(1, 1))!!
        assertEquals("m1", markingIdForSelection(snap, spanning))
        // 与装饰无交叠（选区在标记之前）→ null
        val before = buildSelection(snap, ReaderTextHit(0, 0), ReaderTextHit(0, 2))!!
        assertNull(markingIdForSelection(snap, before))
        // 空串 markingId（宿主高亮规则）不算用户标记
        val ruleOnly = snapshot(
            line("abcdef", positions = intArrayOf(0), decorations = listOf(decorationRun(0, 6, ""))),
        )
        val inside = buildSelection(ruleOnly, ReaderTextHit(0, 1), ReaderTextHit(0, 4))!!
        assertNull(markingIdForSelection(ruleOnly, inside))
    }

    @Test
    fun `装饰命中返回覆盖字符的run`() {
        val line = line("abcdef", positions = intArrayOf(0), decorations = listOf(decorationRun(2, 5)))
        // 区间 [start, end)：起点与内部命中，恰在右端点不算
        assertEquals("m1", findDecorationAt(line, 2)!!.markingId)
        assertEquals("m1", findDecorationAt(line, 4)!!.markingId)
        assertNull(findDecorationAt(line, 5))
    }

    @Test
    fun `装饰未命中返回null`() {
        // 无装饰行
        val plain = line("abcdef", positions = intArrayOf(0))
        assertNull(findDecorationAt(plain, 3))
        // 有装饰但命中在区间外/间隙
        val line = line("abcdef", positions = intArrayOf(0), decorations = listOf(decorationRun(2, 4)))
        assertNull(findDecorationAt(line, 1))
        assertNull(findDecorationAt(line, 4))
        assertNull(findDecorationAt(line, 10))
    }

    @Test
    fun `多run取包含者且间隙不误取`() {
        val line = line("abcdefghij", positions = intArrayOf(0),
            decorations = listOf(decorationRun(0, 3, "m1"), decorationRun(5, 8, "m2")))
        assertEquals("m1", findDecorationAt(line, 1)!!.markingId)
        assertEquals("m2", findDecorationAt(line, 6)!!.markingId)
        // 相邻 run 之间的间隙不误取
        assertNull(findDecorationAt(line, 4))
    }

    @Test
    fun `命中装饰选区快照按行内区间构造`() {
        val snap = snapshot(line("abcdefghij", positions = intArrayOf(100), top = 30f, bottom = 70f))
        val sel = selectionOfDecoration(snap, 0, decorationRun(2, 6, "m1"))!!
        assertEquals(ReaderTextHit(0, 2), sel.startHit)
        assertEquals(ReaderTextHit(0, 6), sel.endHit)
        assertEquals("cdef", sel.selectedText)
        assertEquals(102, sel.bodyStart)
        assertEquals(106, sel.bodyEnd)
        assertFalse(sel.includesTitle)
        // 防御：行下标越界 / run 区间越界钳制后退化为空
        assertNull(selectionOfDecoration(snap, 5, decorationRun(0, 2)))
        assertNull(selectionOfDecoration(snap, 0, decorationRun(20, 30)))
    }

    // ==================== 跨页续选会话（v2 Task 8） ====================

    /** 三行页：软换行（gap=0）+ 段落间隙（gap=1），正文区间 [0, 10)。 */
    private fun threeLinePage() = snapshot(
        line("第一段落", positions = intArrayOf(0), top = 30f, bottom = 70f),
        line("续行", positions = intArrayOf(4), top = 80f, bottom = 120f),
        line("新段落", positions = intArrayOf(7), top = 130f, bottom = 170f),
    )

    @Test
    fun `captureSegment 整页区间拼接含段落间隙换行`() {
        val seg = captureSegment(threeLinePage(), 0, 100)!!
        assertEquals("第一段落续行\n新段落", seg.text)
        assertEquals(0, seg.startPos)
        assertEquals(10, seg.endPos)
    }

    @Test
    fun `captureSegment 部分区间在页内裁剪`() {
        // 与 buildSelection 的「段落续行\n新」同一区间同一拼接口径
        val seg = captureSegment(threeLinePage(), 2, 8)!!
        assertEquals("段落续行\n新", seg.text)
        assertEquals(2, seg.startPos)
        assertEquals(8, seg.endPos)
    }

    @Test
    fun `captureSegment 空交集与零长区间返回null`() {
        assertNull(captureSegment(threeLinePage(), 100, 200))
        assertNull(captureSegment(threeLinePage(), 5, 5))
        // 零长度行（防御脏数据）不参与拼接
        assertNull(captureSegment(snapshot(line("", positions = intArrayOf(0))), 0, 10))
    }

    @Test
    fun `captureSegment 跳过标题行只取正文空间`() {
        val snap = snapshot(
            line("标题", positions = intArrayOf(100), isTitle = true, top = 30f, bottom = 70f),
            line("正文内容", positions = intArrayOf(0), top = 80f, bottom = 120f),
        )
        val seg = captureSegment(snap, 0, 100)!!
        assertEquals("正文内容", seg.text)
        assertEquals(0, seg.startPos)
        assertEquals(4, seg.endPos)
    }

    @Test
    fun `joinSegments 有序拼接gap为零直连`() {
        assertEquals(
            "abcdef",
            joinSegments(listOf(ReaderSelectionSegment("abc", 0, 3), ReaderSelectionSegment("def", 3, 6))),
        )
        assertEquals("", joinSegments(emptyList()))
        assertEquals("abc", joinSegments(listOf(ReaderSelectionSegment("abc", 0, 3))))
    }

    @Test
    fun `joinSegments 相邻gap大于零补换行`() {
        assertEquals(
            "abc\ndef",
            joinSegments(listOf(ReaderSelectionSegment("abc", 0, 3), ReaderSelectionSegment("def", 5, 8))),
        )
    }

    @Test
    fun `joinSegments 乱序输入按startPos排序`() {
        assertEquals(
            "第一段落续行\n新段落",
            joinSegments(
                listOf(
                    ReaderSelectionSegment("新段落", 7, 10),
                    ReaderSelectionSegment("第一段落续行", 0, 6),
                )
            ),
        )
    }

    @Test
    fun `flipDirection 起始把手页顶触发上翻`() {
        val snap = threeLinePage()
        assertEquals(-1, flipDirection(ReaderTextHit(0, 3), snap, handleIsStart = true))
    }

    @Test
    fun `flipDirection 起始把手非触发带返回null`() {
        val snap = threeLinePage()
        assertNull(flipDirection(ReaderTextHit(1, 0), snap, handleIsStart = true))
        assertNull(flipDirection(ReaderTextHit(2, 0), snap, handleIsStart = true))
    }

    @Test
    fun `flipDirection 结束把手页底触发下翻`() {
        val snap = threeLinePage()
        assertEquals(1, flipDirection(ReaderTextHit(2, 3), snap, handleIsStart = false))
    }

    @Test
    fun `flipDirection 结束把手在首行返回null`() {
        val snap = threeLinePage()
        assertNull(flipDirection(ReaderTextHit(0, 0), snap, handleIsStart = false))
        assertNull(flipDirection(ReaderTextHit(1, 0), snap, handleIsStart = false))
    }

    @Test
    fun `flipDirectionForPointer 越出页缘按方向兜底`() {
        val snap = threeLinePage()
        // 命中为空但指针越过页顶：起始把手判上翻，结束把手不判
        assertEquals(-1, flipDirectionForPointer(snap, null, y = 10f, handleIsStart = true))
        assertNull(flipDirectionForPointer(snap, null, y = 10f, handleIsStart = false))
        // 越过页底：结束把手判下翻
        assertEquals(1, flipDirectionForPointer(snap, null, y = 300f, handleIsStart = false))
        assertNull(flipDirectionForPointer(snap, null, y = 300f, handleIsStart = true))
        // 行盒之间空档不判触发
        assertNull(flipDirectionForPointer(snap, null, y = 75f, handleIsStart = true))
        // 有命中时与 flipDirection 同判
        assertEquals(-1, flipDirectionForPointer(snap, ReaderTextHit(0, 3), 40f, handleIsStart = true))
        assertNull(flipDirectionForPointer(snap, ReaderTextHit(1, 0), 100f, handleIsStart = true))
    }

    @Test
    fun `selectionFromChapterRange 页内裁剪构造选区`() {
        val snap = threeLinePage()
        val sel = selectionFromChapterRange(snap, 2, 8)!!
        assertEquals(ReaderTextHit(0, 2), sel.startHit)
        assertEquals(ReaderTextHit(2, 1), sel.endHit)
        assertEquals("段落续行\n新", sel.selectedText)
        assertEquals(2, sel.bodyStart)
        assertEquals(8, sel.bodyEnd)
        assertFalse(sel.includesTitle)
    }

    @Test
    fun `selectionFromChapterRange 范围不在本页返回覆盖全页`() {
        val snap = snapshot(
            line("标题", positions = intArrayOf(100), isTitle = true, top = 30f, bottom = 70f),
            line("正文", positions = intArrayOf(0), top = 80f, bottom = 120f),
        )
        val sel = selectionFromChapterRange(snap, 500, 600)!!
        // 覆盖全页：首行首字符到末行末字符
        assertEquals(ReaderTextHit(0, 0), sel.startHit)
        assertEquals(ReaderTextHit(1, 2), sel.endHit)
        assertEquals("标题\n正文", sel.selectedText)
    }

    @Test
    fun `selectionFromChapterRange 空页返回null`() {
        assertNull(selectionFromChapterRange(snapshot(), 0, 10))
    }

    @Test
    fun `flipEdgeHit 向后翻吸附末正文行末字符`() {
        val snap = threeLinePage()
        assertEquals(ReaderTextHit(2, 3), flipEdgeHit(snap, handleIsStart = true))
    }

    @Test
    fun `flipEdgeHit 向前翻吸附首正文行首字符`() {
        val snap = threeLinePage()
        assertEquals(ReaderTextHit(0, 0), flipEdgeHit(snap, handleIsStart = false))
    }

    @Test
    fun `flipEdgeHit 无正文行返回null`() {
        val snap = snapshot(line("标题", positions = intArrayOf(0), isTitle = true))
        assertNull(flipEdgeHit(snap, handleIsStart = true))
        assertNull(flipEdgeHit(snap, handleIsStart = false))
    }

    @Test
    fun `mergeSegment 保留不相交段并以新段覆盖同页重捕`() {
        val first = ReaderSelectionSegment("aaa", 0, 3)
        val second = ReaderSelectionSegment("bbb", 20, 23)
        // 不相交段保留
        val base = mergeSegment(emptyList(), first)
        assertEquals(listOf(first), base)
        assertEquals(listOf(first, second), mergeSegment(base, second))
        // 同页重捕（向前多翻一次又翻回后区间被扩大）：旧段与新段重叠 → 新段覆盖
        val recapture = ReaderSelectionSegment("cccc", 10, 23)
        assertEquals(listOf(first, recapture), mergeSegment(listOf(first, second), recapture))
        // 相邻不重叠（end == start）都保留（列表序不保证，拼接侧 joinSegments 按 startPos 排序）
        val adjacent = ReaderSelectionSegment("ddd", 3, 6)
        assertEquals(
            setOf(first, adjacent, second),
            mergeSegment(listOf(first, second), adjacent).toSet(),
        )
    }

    // ==================== 修复轮（三编排缺陷） ====================

    @Test
    fun `flipCaptureRange 向前翻取会话起点到离页正文末尾`() {
        // threeLinePage 正文 [0,10)；会话区间 [2,8)（手指在 8），向前翻（结束
        // 把手下翻）离页段整段到页边——被选部分直到页边，边界与文本一致
        assertEquals(
            2 to 10,
            flipCaptureRange(threeLinePage(), direction = 1, rangeStart = 2, rangeEnd = 8),
        )
    }

    @Test
    fun `flipCaptureRange 向后翻取离页正文起始到会话终点`() {
        assertEquals(
            0 to 8,
            flipCaptureRange(threeLinePage(), direction = -1, rangeStart = 2, rangeEnd = 8),
        )
    }

    @Test
    fun `flipCaptureRange 正文边跳过标题行`() {
        val snap = snapshot(
            line("标题", positions = intArrayOf(100), isTitle = true, top = 30f, bottom = 70f),
            line("正文", positions = intArrayOf(0), top = 80f, bottom = 120f),
            line("尾行", positions = intArrayOf(2), top = 130f, bottom = 170f),
        )
        // 正文范围 [0,4)：向前翻取 [rangeStart, 4)，向后翻取 [0, rangeEnd)
        assertEquals(3 to 4, flipCaptureRange(snap, direction = 1, rangeStart = 3, rangeEnd = 4))
        assertEquals(0 to 3, flipCaptureRange(snap, direction = -1, rangeStart = 0, rangeEnd = 3))
    }

    @Test
    fun `flipCaptureRange 无正文行返回null`() {
        assertNull(
            flipCaptureRange(
                snapshot(line("标题", positions = intArrayOf(0), isTitle = true)),
                direction = 1, rangeStart = 0, rangeEnd = 5,
            )
        )
    }

    @Test
    fun `offPageHandleIsStart 页顶外起始侧页底外结束侧空档null`() {
        val snap = threeLinePage() // 行盒 [30, 170]
        assertTrue(offPageHandleIsStart(snap, y = 10f)!!)    // 页顶外 = 起始侧（-1 可达）
        assertFalse(offPageHandleIsStart(snap, y = 300f)!!)  // 页底外 = 结束侧（+1 可达）
        // 行盒之间空档：返回 null，调用方维持最近归属（不判触发）
        assertNull(offPageHandleIsStart(snap, y = 75f))
        assertNull(offPageHandleIsStart(snapshot(), y = 10f))
    }

    @Test
    fun `前向会话反向拖出页缘空命中按越出边归属双向翻页可达`() {
        val snap = threeLinePage()
        // 前向翻页（结束把手拖拽）后反向拖出页顶：空命中按越出边归属起始侧 → -1
        assertEquals(
            -1,
            flipDirectionForPointer(
                snap, hit = null, y = 10f,
                handleIsStart = offPageHandleIsStart(snap, y = 10f)!!,
            ),
        )
        // 对称：向后翻页（起始把手拖拽）后正向拖出页底：归属结束侧 → +1
        assertEquals(
            1,
            flipDirectionForPointer(
                snap, hit = null, y = 300f,
                handleIsStart = offPageHandleIsStart(snap, y = 300f)!!,
            ),
        )
    }

    @Test
    fun `FlipTrigger 入带计时超时触发一次出带重新武装`() {
        val trigger = FlipTrigger(timeoutMillis = 500)
        // 入带首事件：只起表不触发
        assertNull(trigger.onDirection(1, nowMillis = 1000))
        // 带内未满时值：不触发
        assertNull(trigger.onDirection(1, nowMillis = 1200))
        // 满 500ms：触发一次
        assertEquals(1, trigger.onDirection(1, nowMillis = 1500))
        // 带内继续按住：已 disarm 不再触发
        assertNull(trigger.onDirection(1, nowMillis = 1600))
        // 出带：重新武装并清计时
        assertNull(trigger.onDirection(null, nowMillis = 1700))
        // 再入带：重新起表，未满不触发
        assertNull(trigger.onDirection(-1, nowMillis = 1800))
        assertEquals(-1, trigger.onDirection(-1, nowMillis = 2300))
        // 出带再瞬间回带又出带：计时被打断不误触发
        assertNull(trigger.onDirection(null, nowMillis = 2400))
        assertNull(trigger.onDirection(1, nowMillis = 2500))
        assertNull(trigger.onDirection(null, nowMillis = 2600))
        assertNull(trigger.onDirection(1, nowMillis = 3000))
        assertNull(trigger.onDirection(1, nowMillis = 3100))
        assertEquals(1, trigger.onDirection(1, nowMillis = 3500))
    }
}
