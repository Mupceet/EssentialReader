package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三槽 barrier 语义（镜像宿主 ReaderCanvasSurface.drawTipRow 的旧版三槽
 * 约束）：左槽可用右界 = min(可见中槽起始边, 右槽起始边)；槽不可见
 * （width null）不参与；无邻槽 = 行右缘。
 */
class ReaderTipRowBarrierTest {

    private val left = 100f
    private val right = 1000f

    @Test
    fun `无邻槽时 barrier 为行右缘`() {
        assertEquals(right, tipRowBarrierX(left, right, null, null), 0f)
    }

    @Test
    fun `仅右槽时 barrier 为右槽起始边`() {
        assertEquals(700f, tipRowBarrierX(left, right, null, 300f), 0f)
    }

    @Test
    fun `仅中槽时 barrier 为中槽起始边`() {
        // 中槽宽 200：中心 550，起始边 450
        assertEquals(450f, tipRowBarrierX(left, right, 200f, null), 0f)
    }

    @Test
    fun `中右都在时取更小者`() {
        // 右槽起始边 700；中槽宽 600：中心 550、起始边 250 → min = 250
        assertEquals(250f, tipRowBarrierX(left, right, 600f, 300f), 0f)
    }

    @Test
    fun `零宽中槽仍参与 barrier`() {
        // 与宿主一致：槽存在即参与（全宽测量）——中槽零宽起始边 550，
        // 右槽 300 宽起始边 700，min = 550；空文本槽由调用方不传入
        assertEquals(550f, tipRowBarrierX(left, right, 0f, 300f), 0f)
    }
}
