package io.legado.app.eink.engine

import android.view.KeyEvent

/**
 * Activity 按键转发枢纽（宿主入口 Activity 持有，经引擎注册表下发）。
 *
 * 单 Activity 架构下，音量键等系统按键先到达入口 Activity；活跃屏幕
 * （如阅读页）在组合期经 [handler] 注册处理器，随屏幕离开组合注销。
 * 处理器返回 true 表示消费（Activity 不再下传），返回 false 或未注册
 * 时按键交还系统默认处理（如音量调节）。
 */
class EInkKeyEventHub {

    /** 当前活跃屏幕的按键处理器；随屏幕组合生命周期注册/注销。 */
    var handler: ((KeyEvent) -> Boolean)? = null

    /** 转发按键事件；无人处理时返回 false（放行系统默认行为）。 */
    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}
