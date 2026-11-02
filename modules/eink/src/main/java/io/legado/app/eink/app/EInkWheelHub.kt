package io.legado.app.eink.app

import android.os.SystemClock
import android.view.MotionEvent
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.control.EInkModalPresence
import io.legado.app.eink.designsystem.pager.wheelPagingEvent

/**
 * 滚轮事件转发枢纽（与 [EInkKeyEventHub] 同构的栈式分发）：单 Activity
 * 架构下滚轮（ACTION_SCROLL + AXIS_VSCROLL）先到达入口 Activity，基类
 * [io.legado.app.eink.contract.EInkHostActivity] 的 onGenericMotionEvent
 * 经 [dispatch] 转发；活跃分页区（阅读页、各分页列表的 EInkPagerInput）
 * 在组合期注册处理器（入栈），随组合离开注销（弹栈）。事件只派发给栈顶
 * 处理器，返回 true 表示消费。
 *
 * 去抖为 **[COOLDOWN_MS] 冷却**：首滚即翻页（对齐墨水屏整页刷新的直接
 * 反馈），冷却期内事件消费但忽略——防高分辨率滚轮/触控板连发事件排队
 * 整页刷新洪泛（宿主完整模式为 200ms trailing 去抖，eink 侧取
 * leading + 冷却）。冷却只在处理器接受（返回 true）后起算：翻页关闭
 * 期间的事件不消费、不起冷却。
 */
class EInkWheelHub(
    private val timeSource: () -> Long = SystemClock::elapsedRealtime,
) {

    private val handlers = ArrayDeque<(axisValue: Float) -> Boolean>()

    private var lastAcceptedAt = UNSET

    /** 注册滚轮处理器（入栈）；返回注销函数（弹栈），随组合生命周期恰好调用一次。 */
    fun register(handler: (axisValue: Float) -> Boolean): () -> Unit {
        handlers.addLast(handler)
        return { handlers.remove(handler) }
    }

    /** 在栈处理器数（测试/诊断观测点）。 */
    val handlerCount: Int get() = handlers.size

    /**
     * 转发滚轮事件给栈顶处理器：非竖直滚动或零位移放行（false）；冷却
     * 期内消费但忽略（true）；处理器返回 false（翻页关闭等）原样放行。
     */
    fun dispatch(event: MotionEvent): Boolean =
        dispatchScroll(event.action, event.getAxisValue(MotionEvent.AXIS_VSCROLL))

    /**
     * 滚动判定的可测核（[dispatch] 的纯参量形态，规避单测构造
     * [MotionEvent]）：action 为 MotionEvent.ACTION_* 常量。
     */
    internal fun dispatchScroll(action: Int, axisValue: Float): Boolean {
        if (action != MotionEvent.ACTION_SCROLL || axisValue == 0f) return false
        val handler = handlers.lastOrNull() ?: return false
        val now = timeSource()
        if (lastAcceptedAt != UNSET && now - lastAcceptedAt < COOLDOWN_MS) return true
        if (handler(axisValue)) {
            lastAcceptedAt = now
            return true
        }
        return false
    }

    companion object {
        /** 翻页接受后的冷却窗口（毫秒）。 */
        const val COOLDOWN_MS = 200L

        private const val UNSET = Long.MIN_VALUE
    }
}

/**
 * 滚轮事件 → 翻页判定（阅读页与分页列表共用）：读取开关（宿主快照
 * 桥接，实时生效）与页内模态在场状态后委托 designsystem 纯函数语义
 * （wheelPagingEvent，见其 KDoc）。
 */
internal fun hubWheelPagingEvent(
    axisValue: Float,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean = wheelPagingEvent(
    axisValue = axisValue,
    pagingEnabled = EInkEngineRegistry.globalSettings.mouseWheelPage,
    modalPresent = EInkModalPresence.isPresent,
    onPageUp = onPageUp,
    onPageDown = onPageDown,
)
