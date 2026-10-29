package io.legado.app.eink.feature.reader.screensaver

internal data class ReaderScreensaverBands(
    /** 正文带顶（窗口坐标）：顶部内容边距 + 页眉预留。 */
    val bodyTopPx: Float,
    /** 正文带底 = 页脚带顶（窗口坐标）。 */
    val bodyBottomPx: Float,
)

/**
 * 窗口坐标下的屏保分带：阅读画布为全屏窗口（系统栏 overlay 其上、
 * 避让已并入排版 contentPadding），快照行坐标即窗口坐标——正文带 =
 * [systemBarTopInsetPx + headerExtentPx, 底部内容边距之上]；页眉隐时
 * headerExtent 恒为 0（extent 随可见性塌缩），带顶自然上移；页脚同理。
 */
internal fun resolveReaderScreensaverBands(
    sourceHeightPx: Int,
    systemBarTopInsetPx: Float,
    systemBarBottomInsetPx: Float,
    headerExtentPx: Float,
    footerExtentPx: Float,
    footerVisible: Boolean,
): ReaderScreensaverBands {
    val footerTop = sourceHeightPx.toFloat() - systemBarBottomInsetPx -
        (if (footerVisible && footerExtentPx > 0f) footerExtentPx else 0f)
    return ReaderScreensaverBands(
        bodyTopPx = systemBarTopInsetPx.coerceAtLeast(0f) +
            headerExtentPx.coerceAtLeast(0f),
        bodyBottomPx = footerTop,
    )
}
