package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 点击分区（九宫格简化版）：中心格固定菜单不可改、其余格上一页/下一页
 * 两态、默认值、三等分命中、9 位编码往返与脏数据回落。
 */
class ReaderTapZonesTest {

    @Test
    fun `默认分区为中心菜单其余下一页`() {
        val grid = ReaderTapZoneGrid()
        assertEquals(ReaderTapZoneAction.MENU, grid.middleCenter)
        for ((index, cell) in grid.cells.withIndex()) {
            if (index != ReaderTapZoneGrid.CENTER_INDEX) {
                assertEquals(ReaderTapZoneAction.NEXT_PAGE, cell)
            }
        }
        assertEquals("111101111", grid.encode())
        assertEquals("111101111", ReaderTapZoneGrid.DEFAULT_ENCODING)
    }

    @Test
    fun `中心格下标为四`() {
        assertEquals(4, ReaderTapZoneGrid.CENTER_INDEX)
        assertEquals(ReaderTapZoneAction.MENU, ReaderTapZoneGrid().cells[ReaderTapZoneGrid.CENTER_INDEX])
    }

    @Test
    fun `命中测试按三等分且边界归属右下格`() {
        val grid = ReaderTapZoneGrid()
        val w = 300f
        val h = 300f
        // 左上角 → TL（默认 = 下一页）
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(0f, 0f, w, h))
        // 中心格中心 → MC（= 菜单）
        assertEquals(ReaderTapZoneAction.MENU, grid.actionAt(150f, 150f, w, h))
        // 中列非中心（TC/BC 命中点）：默认 = 下一页（中心格收窄为单格）
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(150f, 50f, w, h))
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(150f, 250f, w, h))
        // 边界 x=100（=w/3）归属中列、x=200（=2w/3）归属右列
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(100f, 50f, w, h))
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(200f, 50f, w, h))
        // 边界 y=100/y=200 同理按行归属（中行非中心列仍为下一页）
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(50f, 100f, w, h))
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(50f, 200f, w, h))
        // 右下角
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(300f, 300f, w, h))
    }

    @Test
    fun `行主序按行按列区分动作`() {
        // 顶行=上一页 / 中行=下一页 / 底行=上一页（中心恒菜单）
        val grid = ReaderTapZoneGrid.decodeOrDefault("222111222")
        assertEquals(ReaderTapZoneAction.PREVIOUS_PAGE, grid.actionAt(50f, 50f, 300f, 300f))
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.actionAt(50f, 150f, 300f, 300f))
        assertEquals(ReaderTapZoneAction.PREVIOUS_PAGE, grid.actionAt(50f, 250f, 300f, 300f))
        assertEquals(ReaderTapZoneAction.MENU, grid.actionAt(150f, 150f, 300f, 300f))
    }

    @Test
    fun `量测未就绪回落该九宫格的中中格`() {
        assertEquals(
            ReaderTapZoneAction.MENU,
            ReaderTapZoneGrid().actionAt(10f, 10f, 0f, 0f),
        )
        assertEquals(
            ReaderTapZoneAction.MENU,
            ReaderTapZoneGrid.decodeOrDefault("222222222").actionAt(10f, 10f, 0f, 0f),
        )
    }

    @Test
    fun `编码解码往返（中心位合法时保值）`() {
        val grid = ReaderTapZoneGrid.decodeOrDefault("012102211")
        assertEquals("012102211", grid.encode())
        assertEquals(ReaderTapZoneAction.MENU, grid.topLeft)
        assertEquals(ReaderTapZoneAction.NEXT_PAGE, grid.topCenter)
        assertEquals(ReaderTapZoneAction.PREVIOUS_PAGE, grid.topRight)
        assertEquals(ReaderTapZoneAction.MENU, grid.middleCenter)
    }

    @Test
    fun `中心位脏值解码时强制归位菜单`() {
        // 中心位非 0：不整串作废（其余格信息仍有价值），只把中心归位
        val grid = ReaderTapZoneGrid.decodeOrDefault("111111111")
        assertEquals(ReaderTapZoneAction.MENU, grid.middleCenter)
        assertEquals("111101111", grid.encode())
    }

    @Test
    fun `脏数据一律回落默认分区`() {
        assertEquals(ReaderTapZoneGrid(), ReaderTapZoneGrid.decodeOrDefault(null))
        assertEquals(ReaderTapZoneGrid(), ReaderTapZoneGrid.decodeOrDefault(""))
        // 长度不符
        assertEquals(ReaderTapZoneGrid(), ReaderTapZoneGrid.decodeOrDefault("11101111"))
        assertEquals(ReaderTapZoneGrid(), ReaderTapZoneGrid.decodeOrDefault("1110111111"))
        // 非法字符（3 起与字母均不属三动作值域）
        assertEquals(ReaderTapZoneGrid(), ReaderTapZoneGrid.decodeOrDefault("113011111"))
        assertEquals(ReaderTapZoneGrid(), ReaderTapZoneGrid.decodeOrDefault("11a011111"))
    }

    @Test
    fun `可切格在上一页下一页间切换且互为对侧`() {
        val grid = ReaderTapZoneGrid()
        // 下标 0（TL，默认下一页）→ 上一页
        assertEquals(
            ReaderTapZoneAction.PREVIOUS_PAGE,
            grid.toggledPageAt(0).topLeft,
        )
        // 再切回 → 下一页（两态往返）
        assertEquals(
            ReaderTapZoneAction.NEXT_PAGE,
            grid.toggledPageAt(0).toggledPageAt(0).topLeft,
        )
        // 下标 8（BR）切换只影响自身，中心不受影响
        val toggled = grid.toggledPageAt(8)
        assertEquals(ReaderTapZoneAction.PREVIOUS_PAGE, toggled.bottomRight)
        assertEquals(ReaderTapZoneAction.MENU, toggled.middleCenter)
    }

    @Test
    fun `中心格固定菜单不可改且越界下标不变`() {
        val grid = ReaderTapZoneGrid()
        // 中心格切换为无操作
        assertEquals(grid, grid.toggledPageAt(ReaderTapZoneGrid.CENTER_INDEX))
        // 越界下标返回原值
        assertEquals(grid, grid.toggledPageAt(-1))
        assertEquals(grid, grid.toggledPageAt(9))
    }
}
