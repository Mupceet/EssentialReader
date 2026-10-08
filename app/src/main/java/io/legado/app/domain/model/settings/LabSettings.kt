package io.legado.app.domain.model.settings

data class LabSettings(
    /** 「启用实验室」「墨水屏显示」默认开：与仓库 toLabSettings 读取默认一致，新装机即见墨水屏模式入口 */
    val enabled: Boolean = true,
    val eInkDisplay: Boolean = true,
    val eyeProtection: Boolean = false,
    /** 墨水屏模式当前状态：「我的」页顶栏开关；显隐门控是 [eInkDisplay] */
    val eInkMode: Boolean = false,
)
