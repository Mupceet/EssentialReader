package io.legado.app.eink.contract

/**
 * 阅读页点击区域动作（九宫格简化版，仅三动作）。
 *
 * 数值语义与完整模式点击区域配置的 legacy 值对齐（菜单 0 / 下一页 1 /
 * 上一页 2）；E-Ink 侧不收纳其余 12 种动作，且九宫格内动作位置受固定
 * 约束（见 [ReaderTapZoneGrid]）：中心格恒为菜单，其余格仅上一页/
 * 下一页两态。
 */
enum class ReaderTapZoneAction(val encoded: Int) {
    MENU(0),
    NEXT_PAGE(1),
    PREVIOUS_PAGE(2);
}

/**
 * 阅读页点击分区（3×3 九宫格，完整模式「点击区域设置」的 E-Ink 简化版）。
 *
 * - 几何：与完整模式 ReaderTapActionGrid 同款三等分（actionAt），
 *   行主序 TL/TC/TR、ML/MC/MR、BL/BC/BR；
 * - 固定约束：中心格（[CENTER_INDEX]）恒为菜单、蒙层不可改（编辑经
 *   [toggledPageAt] 对中心格返回原值；解码经 fromCells 强制归位），
 *   其余 8 格仅 上一页/下一页 两态；
 * - 默认值 = 中心格唤菜单、其余格下一页（对齐本设置引入前「中央唤
 *   菜单、其余下一页」的行为；中央区随之从 40% 宽带收窄为九宫格
 *   中心格——中列上下两格由唤菜单变为翻页，属规格固定约束的预期差异）；
 * - 持久化：[encode] 产出 9 位数字串（行主序，每格 [ReaderTapZoneAction.encoded]，
 *   中心位恒 0），宿主按自有偏好整键存取；[decodeOrDefault] 对空串/
 *   长度不符/非法字符一律回落默认分区（脏存储不炸分发）。
 */
data class ReaderTapZoneGrid(
    val topLeft: ReaderTapZoneAction = ReaderTapZoneAction.NEXT_PAGE,
    val topCenter: ReaderTapZoneAction = ReaderTapZoneAction.NEXT_PAGE,
    val topRight: ReaderTapZoneAction = ReaderTapZoneAction.NEXT_PAGE,
    val middleLeft: ReaderTapZoneAction = ReaderTapZoneAction.NEXT_PAGE,
    val middleCenter: ReaderTapZoneAction = ReaderTapZoneAction.MENU,
    val middleRight: ReaderTapZoneAction = ReaderTapZoneAction.NEXT_PAGE,
    val bottomLeft: ReaderTapZoneAction = ReaderTapZoneAction.NEXT_PAGE,
    val bottomCenter: ReaderTapZoneAction = ReaderTapZoneAction.NEXT_PAGE,
    val bottomRight: ReaderTapZoneAction = ReaderTapZoneAction.NEXT_PAGE,
) {
    /** 行主序九格（蒙层渲染与 [toggledPageAt] 的下标基准）。 */
    val cells: List<ReaderTapZoneAction>
        get() = listOf(
            topLeft, topCenter, topRight,
            middleLeft, middleCenter, middleRight,
            bottomLeft, bottomCenter, bottomRight,
        )

    /**
     * 命中测试（三等分，边界归属右/下格）：点按坐标 → 所在格动作。
     * 宽高非正时回落本九宫格的中中格（量测未就绪的安全占位）。
     */
    fun actionAt(x: Float, y: Float, width: Float, height: Float): ReaderTapZoneAction {
        if (width <= 0f || height <= 0f) return middleCenter
        val column = when {
            x < width / 3f -> 0
            x < width * 2f / 3f -> 1
            else -> 2
        }
        val row = when {
            y < height / 3f -> 0
            y < height * 2f / 3f -> 1
            else -> 2
        }
        return cells[row * 3 + column]
    }

    /**
     * 蒙层点击指定格（[cells] 下标）在 上一页/下一页 间切换；
     * 中心格固定菜单不可改、下标越界均返回原值。
     */
    fun toggledPageAt(index: Int): ReaderTapZoneGrid {
        if (index == CENTER_INDEX) return this
        val current = cells.getOrNull(index) ?: return this
        val next = if (current == ReaderTapZoneAction.PREVIOUS_PAGE) {
            ReaderTapZoneAction.NEXT_PAGE
        } else {
            ReaderTapZoneAction.PREVIOUS_PAGE
        }
        return fromCells(cells.toMutableList().also { it[index] = next })
    }

    /** 9 位编码（行主序，每格一位数字；中心位恒 0）。 */
    fun encode(): String = cells.joinToString("") { it.encoded.toString() }

    companion object {
        /** 中心格（中中）在 [cells] 行主序中的下标；固定菜单不可改。 */
        const val CENTER_INDEX = 4

        /** [decodeOrDefault] 对非法输入的回落值（= 默认分区编码）。 */
        const val DEFAULT_ENCODING = "111101111"

        /**
         * 解码 9 位编码串；null/空/长度不符/非法字符一律回落默认分区
         * （脏存储不进入分发，宁可回到既有固定行为）。中心位强制归位
         * 菜单（脏值不破坏固定约束）。
         */
        fun decodeOrDefault(value: String?): ReaderTapZoneGrid {
            if (value == null || value.length != 9) return ReaderTapZoneGrid()
            val actions = value.mapNotNull { ch ->
                ReaderTapZoneAction.entries.firstOrNull { it.encoded == ch - '0' }
            }
            if (actions.size != 9) return ReaderTapZoneGrid()
            return fromCells(actions)
        }

        private fun fromCells(cells: List<ReaderTapZoneAction>) = ReaderTapZoneGrid(
            topLeft = cells[0],
            topCenter = cells[1],
            topRight = cells[2],
            middleLeft = cells[3],
            // 中心格固定菜单：解码归位，编辑路径（toggledPageAt）也不可改
            middleCenter = ReaderTapZoneAction.MENU,
            middleRight = cells[5],
            bottomLeft = cells[6],
            bottomCenter = cells[7],
            bottomRight = cells[8],
        )
    }
}
