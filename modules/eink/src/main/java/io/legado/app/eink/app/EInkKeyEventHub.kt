package io.legado.app.eink.app

import android.view.KeyEvent
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.control.EInkModalPresence
import io.legado.app.eink.designsystem.pager.pagingKeyEvent
import io.legado.app.eink.designsystem.pager.parseKeyCodes

/**
 * 系统按键转发枢纽（模块自有，经
 * [io.legado.app.eink.contract.EInkEngineRegistry] 持有与重置）。
 *
 * 单 Activity 架构下，翻页按键（音量键/自定义键）等系统按键先到达入口 Activity；基类
 * [io.legado.app.eink.contract.EInkHostActivity] 的 onKeyDown/onKeyUp 统一经 [dispatch] 转发。
 * 活跃界面元素（阅读页、分页列表的 EInkPagerInput）在组合期经 [register]
 * 注册处理器（入栈），随组合离开注销（弹栈）。
 *
 * 事件只派发给栈顶处理器：后组合者自然屏蔽先组合者（弹框内的分页区
 * 盖过弹框外的）。栈顶返回 true 表示消费（Activity 不再下传系统），
 * 返回 false 或栈空时按键交还系统默认处理（如音量调节）。
 *
 * 同屏常驻多个分页区（首页双 Tab 不销毁组合只翻放置态）必须以
 * enabled 互斥注册——组合顺序不定，隐藏区若占住栈顶会吃掉按键。
 */
class EInkKeyEventHub {

    private val handlers = ArrayDeque<(KeyEvent) -> Boolean>()

    /** 注册按键处理器（入栈）；返回注销函数（弹栈），随组合生命周期恰好调用一次。 */
    fun register(handler: (KeyEvent) -> Boolean): () -> Unit {
        handlers.addLast(handler)
        return { handlers.remove(handler) }
    }

    /** 在栈处理器数（测试/诊断观测点）。 */
    val handlerCount: Int get() = handlers.size

    /** 转发按键事件给栈顶处理器；无人处理时返回 false（放行系统默认行为）。 */
    fun dispatch(event: KeyEvent): Boolean = handlers.lastOrNull()?.invoke(event) == true
}

/**
 * 按键事件 → 翻页判定（阅读页与分页列表共用）：读取音量开关与自定义
 * 键集（宿主快照桥接，实时生效）与页内模态在场状态后委托 designsystem
 * 纯函数语义（pagingKeyEvent，见其 KDoc）。自定义键优先于音量键且
 * 不受音量开关控制。
 */
internal fun hubPagingKeyEvent(
    event: KeyEvent,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean = pagingKeyEvent(
    action = event.action,
    keyCode = event.keyCode,
    repeatCount = event.repeatCount,
    volumePagingEnabled = EInkEngineRegistry.globalSettings.volumeKeyPage,
    prevKeyCodes = parseKeyCodes(EInkEngineRegistry.globalSettings.prevKeys),
    nextKeyCodes = parseKeyCodes(EInkEngineRegistry.globalSettings.nextKeys),
    modalPresent = EInkModalPresence.isPresent,
    onPageUp = onPageUp,
    onPageDown = onPageDown,
)
