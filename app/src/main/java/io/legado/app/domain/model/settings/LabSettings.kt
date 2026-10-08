package io.legado.app.domain.model.settings

data class LabSettings(
    val enabled: Boolean = false,
    val eInkDisplay: Boolean = false,
    val eyeProtection: Boolean = false,
    /** 墨水屏模式当前状态：「我的」页顶栏开关；显隐门控是 [eInkDisplay] */
    val eInkMode: Boolean = false,
)
