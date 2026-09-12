package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假等宽测量：每字符 10px，便于手算。 */
private val measure: (String) -> Float = { it.length * 10f }

private fun line(
    vararg chunks: String,
    positions: IntArray,
    top: Float = 30f,
    bottom: Float = 70f,
    isTitle: Boolean = false,
    decorations: List<ReaderDecorationRun> = emptyList(),
): ReaderPageLine = ReaderPageLine(
    baseY = top + (bottom - top) * 0.8f, isTitle = isTitle, chunks = chunks.toList(),
    x = FloatArray(chunks.size) { i -> i * 100f },
    chapterPositions = positions, top = top, bottom = bottom,
    decorations = decorations,
)

private fun snapshot(
    vararg lines: ReaderPageLine,
    images: List<ReaderImageSlot> = emptyList(),
) = ReaderPageSnapshot(
    title = "章", readProgress = "1/1",
    titleSpec = ReaderPaintSpec(20f, 0f, null, null),
    contentSpec = ReaderPaintSpec(20f, 0f, null, null),
    lines = lines.toList(), images = images,
)

private fun imageSlot(
    x0: Float,
    x1: Float,
    lineTop: Float,
    lineBottom: Float,
    action: String? = null,
    source: String = "img.png",
) = ReaderImageSlot(
    x0 = x0, x1 = x1, lineTop = lineTop, lineBottom = lineBottom,
    lineHeight = lineBottom - lineTop, fullLine = true,
    loader = { _, _ -> null },
    source = source, action = action,
)

private fun decorationRun(start: Int, end: Int, markingId: String = "m1") =
    ReaderDecorationRun(
        start = start, end = end, underlineMode = 1, highlight = false, markingId = markingId,
    )

/**
 * 点按分派表：操作条展开优先收起；浮层/残留选区在场只收它们（吞掉点按，
 * 不穿透成翻页/菜单）；都没有才走分区行为（含标记命中查询）。
 */
class ReaderSelectionInteractionPolicyTest {

    @Test
    fun `操作条展开时点按只收起操作条`() {
        assertEquals(
            ReaderTapDispatch.COLLAPSE_CONTROLS,
            readerTapDispatch(controlsVisible = true, hasSelection = false, hasMarkingBar = false),
        )
        // 操作条展开期浮条不组合，仍归收起
        assertEquals(
            ReaderTapDispatch.COLLAPSE_CONTROLS,
            readerTapDispatch(controlsVisible = true, hasSelection = true, hasMarkingBar = true),
        )
    }

    @Test
    fun `选区浮条在场时点按只收浮条不穿分区行为`() {
        assertEquals(
            ReaderTapDispatch.DISMISS_SELECTION,
            readerTapDispatch(controlsVisible = false, hasSelection = true, hasMarkingBar = false),
        )
        // 选区优先于标记浮条（两者不会同时在场，防御顺序固定）
        assertEquals(
            ReaderTapDispatch.DISMISS_SELECTION,
            readerTapDispatch(controlsVisible = false, hasSelection = true, hasMarkingBar = true),
        )
    }

    @Test
    fun `点按标记浮条在场时点按只收浮条`() {
        assertEquals(
            ReaderTapDispatch.DISMISS_MARKING_BAR,
            readerTapDispatch(controlsVisible = false, hasSelection = false, hasMarkingBar = true),
        )
    }

    @Test
    fun `无浮层时点按回落分区行为与标记命中`() {
        assertEquals(
            ReaderTapDispatch.ZONE_OR_MARKING_HIT,
            readerTapDispatch(controlsVisible = false, hasSelection = false, hasMarkingBar = false),
        )
    }

    // ==================== 落划线预览的退场门控 ====================

    @Test
    fun `选区动作集按是否已有标记分派`() {
        // 新区间：复制 / 想法 / 画线（前两槽恒定，第三槽换「画线」）
        assertEquals(
            listOf(ReaderMarkingAction.COPY, ReaderMarkingAction.THOUGHT, ReaderMarkingAction.LINE),
            selectionActions(hasMarking = false),
        )
        // 已有标记（划线或想法）：复制 / 想法 / 删除（第三槽换「删除」）
        assertEquals(
            listOf(
                ReaderMarkingAction.COPY,
                ReaderMarkingAction.THOUGHT,
                ReaderMarkingAction.DELETE,
            ),
            selectionActions(hasMarking = true),
        )
        // 不可标记（含标题选区 §3.4 / 降级宿主）：只留复制，不留死键
        assertEquals(
            listOf(ReaderMarkingAction.COPY),
            selectionActions(hasMarking = false, canMark = false),
        )
        assertEquals(
            listOf(ReaderMarkingAction.COPY),
            selectionActions(hasMarking = true, canMark = false),
        )
    }

    @Test
    fun `想法弹框内容清空即变划线`() {
        assertFalse(markingThoughtFromNote(""))
        // 仅空白视为清空（宿主落库时 note 会被置空）
        assertFalse(markingThoughtFromNote("   "))
        assertFalse(markingThoughtFromNote("\n"))
        assertTrue(markingThoughtFromNote("记一笔"))
    }

    /** 三行正文页：章内区间 [0, 10)（第一段落 [0,4)、续行 [4,6)、新段落 [7,10)）。 */
    private fun threeLinePage(
        topShift: Float = 0f,
        decorations: List<ReaderDecorationRun> = emptyList(),
    ) = snapshot(
        line("第一段落", positions = intArrayOf(0), top = 30f + topShift, bottom = 70f + topShift,
            decorations = decorations),
        line("续行", positions = intArrayOf(4), top = 80f + topShift, bottom = 120f + topShift),
        line("新段落", positions = intArrayOf(7), top = 130f + topShift, bottom = 170f + topShift),
    )

    @Test
    fun `装饰在位判据按章内区间相交且忽略非用户标记`() {
        val page = threeLinePage(decorations = listOf(decorationRun(2, 4)))
        // 第一段落装饰 [2,4) 与章内 [2,6) 相交
        assertTrue(markingRenderedForRange(page, bodyStart = 2, bodyEnd = 6))
        // 区间在装饰之前 / 之后均不相交
        assertFalse(markingRenderedForRange(page, bodyStart = 0, bodyEnd = 2))
        assertFalse(markingRenderedForRange(page, bodyStart = 4, bodyEnd = 6))
        // 空串 markingId = 宿主高亮规则等非用户来源，不算「已渲染用户标记」
        val ruleOnly = threeLinePage(decorations = listOf(decorationRun(0, 4, "")))
        assertFalse(markingRenderedForRange(ruleOnly, bodyStart = 0, bodyEnd = 4))
        // 标题行无正文语义：其装饰不参与
        val titled = snapshot(
            line(
                "标题", positions = intArrayOf(100), isTitle = true,
                decorations = listOf(decorationRun(0, 2, "mTitle")),
            ),
        )
        assertFalse(markingRenderedForRange(titled, bodyStart = 100, bodyEnd = 102))
        // 零宽区间不判
        assertFalse(markingRenderedForRange(page, bodyStart = 2, bodyEnd = 2))
    }

    @Test
    fun `装饰未到但区间仍在本页时预览按区间重锚续显`() {
        val committed = buildSelection(
            threeLinePage(), ReaderTextHit(0, 2), ReaderTextHit(1, 2),
        )!!
        assertEquals("段落续行", committed.selectedText)
        // 宿主重排后的新页：正文相同、行盒下移、装饰还没并进来
        val relayouted = threeLinePage(topShift = 10f)
        val kept = pendingPreviewAfterPageVersion(relayouted, committed)
        assertNotNull(kept)
        assertEquals(ReaderTextHit(0, 2), kept!!.startHit)
        assertEquals(ReaderTextHit(1, 2), kept.endHit)
        // 几何取新快照（预览与正式划线同形，交接不跳位）
        val runs = selectionRuns(relayouted, kept, measure, measure)
        assertEquals(40f, runs[0].top)
        assertEquals(20f, runs[0].left)
        assertEquals(40f, runs[0].right)
    }

    @Test
    fun `装饰已在页上或区间离页或提交缺失时预览退场`() {
        val committed = buildSelection(
            threeLinePage(), ReaderTextHit(0, 2), ReaderTextHit(1, 2),
        )!!
        // 新快照已带该标记装饰：正式划线接管，预览退场
        val decorated = threeLinePage(decorations = listOf(decorationRun(2, 4)))
        assertNull(pendingPreviewAfterPageVersion(decorated, committed))
        // 翻到下一页（区间不在本页）：清态
        val otherPage = snapshot(
            line("下一页正文", positions = intArrayOf(500)),
        )
        assertNull(pendingPreviewAfterPageVersion(otherPage, committed))
        // 无待确认提交 / 无页：清态（常规页变语义）
        assertNull(pendingPreviewAfterPageVersion(decorated, committed = null))
        assertNull(pendingPreviewAfterPageVersion(page = null, committed = committed))
    }

    @Test
    fun `点按命中带动作脚本的图片槽位`() {
        // 段评气泡：行内小图矩形嵌在文本行盒内，点按落在槽位矩形即命中
        val page = snapshot(
            line("正文文字", positions = intArrayOf(0, 1, 2, 3)),
            images = listOf(
                imageSlot(
                    x0 = 400f, x1 = 440f, lineTop = 30f, lineBottom = 70f,
                    action = "java.showBrowser('u')",
                    source = "bubble.png,{\"click\":\"java.showBrowser('u')\"}",
                ),
            ),
        )

        val slot = imageActionSlotAt(page, 420f, 50f)
        assertNotNull(slot)
        assertEquals("java.showBrowser('u')", slot!!.action)
        assertEquals("bubble.png,{\"click\":\"java.showBrowser('u')\"}", slot.source)
        // 边界含端点（矩形闭区间）
        assertNotNull(imageActionSlotAt(page, 400f, 30f))
        assertNotNull(imageActionSlotAt(page, 440f, 70f))
    }

    @Test
    fun `无动作脚本的图片与槽位外的点按不命中`() {
        val page = snapshot(
            line("正文文字", positions = intArrayOf(0, 1, 2, 3)),
            images = listOf(
                imageSlot(x0 = 400f, x1 = 440f, lineTop = 30f, lineBottom = 70f),
                imageSlot(x0 = 500f, x1 = 540f, lineTop = 30f, lineBottom = 70f, action = "js"),
            ),
        )

        // 普通插图（无 action）不参与命中——回落分区行为
        assertNull(imageActionSlotAt(page, 420f, 50f))
        // 槽位矩形外（相邻文字区/下一行）
        assertNull(imageActionSlotAt(page, 510f, 100f))
        assertNull(imageActionSlotAt(page, 200f, 50f))
        // 带动作脚本的槽位照常命中
        assertEquals("js", imageActionSlotAt(page, 520f, 50f)?.action)
    }
}
