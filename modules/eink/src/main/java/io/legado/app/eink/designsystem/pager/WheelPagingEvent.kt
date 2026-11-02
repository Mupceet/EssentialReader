package io.legado.app.eink.designsystem.pager

/**
 * 滚轮翻页事件语义（纯函数）：阅读页与分页列表的鼠标滚轮
 * （AXIS_VSCROLL）翻页共用判定，实参注入开关与模态在场状态——设计
 * 系统不持有滚轮枢纽与设置端口。
 *
 * 语义（方向对齐宿主完整模式 MainActivity.onGenericMotionEvent 的滚轮
 * 分发）：下滚（axisValue < 0）下一页、上滚（axisValue > 0）上一页，
 * 零位移不消费；开关关闭或页内模态弹框在场返回 false 放行系统（页面
 * 正常滚动）。滚轮无按下/抬起对，单事件即完整判定。
 */
internal fun wheelPagingEvent(
    axisValue: Float,
    pagingEnabled: Boolean,
    modalPresent: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean {
    if (!pagingEnabled || modalPresent) return false
    return when {
        axisValue < 0f -> {
            onPageDown()
            true
        }

        axisValue > 0f -> {
            onPageUp()
            true
        }

        else -> false
    }
}
