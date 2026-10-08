package io.legado.app.eink.bridge

import io.legado.app.eink.contract.PageTurnRippleMode

/**
 * 掌阅固件 EPDCDevice 水波纹 effect 编码（纯函数，对拍
 * qianshang-legado-eink 的 IReaderPageH / KOReader 方向映射）。
 *
 * effect = 方向码 or 速度位：方向码按 `rotation and 3` 查表
 * （1=右 2=左 3=下 4=上，波纹扫入方向随翻页方向与屏幕旋转变化）；
 * 速度位（对拍 qianshang SPEED_BITS）慢速=128 / 标准=64 / 快速=0。
 * 编码无官方文档，是社区经验值，勿"顺手修正"。
 */
internal object PageTurnEffectCodes {

    /** PAGE_H 波形（水波纹全刷）的 setForceNextPostMode 模式码。 */
    const val FORCE_NEXT_PAGE_H = 0x01000063

    /** 横波方向：前进（下一页，波纹自右向左扫入），下标 = rotation and 3。 */
    private val NEXT_BY_ROTATION = intArrayOf(1, 4, 2, 3)

    /** 横波方向：后退（上一页，波纹自左向右扫入），下标 = rotation and 3。 */
    private val PREV_BY_ROTATION = intArrayOf(2, 3, 1, 4)

    /** 速度位：波形时长/残影表现（对拍 qianshang slow=128 / medium=64 / fast=0）。 */
    private val SPEED_BITS = mapOf(
        PageTurnRippleMode.SLOW to 128,
        PageTurnRippleMode.STANDARD to 64,
        PageTurnRippleMode.FAST to 0,
    )

    fun effectCode(forward: Boolean, rotation: Int, mode: PageTurnRippleMode): Int {
        val table = if (forward) NEXT_BY_ROTATION else PREV_BY_ROTATION
        return table[rotation and 3] or (SPEED_BITS[mode] ?: 0)
    }
}
