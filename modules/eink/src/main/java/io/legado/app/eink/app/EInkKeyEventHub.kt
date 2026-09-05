package io.legado.app.eink.app

import android.view.KeyEvent

/**
 * 系统按键转发枢纽（模块自有，经
 * [io.legado.app.eink.contract.EInkEngineRegistry] 持有与重置）。
 *
 * 单 Activity 架构下，音量键等系统按键先到达入口 Activity；基类
 * [io.legado.app.eink.contract.EInkHostActivity] 的 onKeyDown/onKeyUp 统一经 [dispatch] 转发。
 * 活跃屏幕（如阅读页）在组合期经 [handler] 注册处理器，随屏幕离开
 * 组合注销。
 *
 * 处理器返回 true 表示消费（Activity 不再下传系统），返回 false 或
 * 未注册时按键交还系统默认处理（如音量调节）。
 */
class EInkKeyEventHub {

    /** 当前活跃屏幕的按键处理器；随屏幕组合生命周期注册/注销。 */
    var handler: ((KeyEvent) -> Boolean)? = null

    /** 转发按键事件；无人处理时返回 false（放行系统默认行为）。 */
    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}
