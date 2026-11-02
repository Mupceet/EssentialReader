package io.legado.app.eink.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.pager.EInkPageSwipe

/**
 * 分页区域统一输入（app 层组装）：滑动翻页手势（designsystem
 * [EInkPageSwipe] 直通）+ 翻页按键（音量键/自定义键，[EInkKeyEventHub] 栈顶
 * 注册，事件时实时读设置）+ 滚轮翻页（[EInkWheelHub] 栈顶注册，实时读
 * GlobalSettings.mouseWheelPage，200ms 冷却去抖）。
 *
 * 按键语义与阅读页共用 [hubPagingKeyEvent]（designsystem 纯函数
 * 语义）：音量开关关闭或页内模态弹框在场时音量键放行系统（自定义键
 * 不受音量开关控制）；首按翻页、长按重复不翻；翻页到头由各分页器
 * canPage 守卫（不动作、仍消费）。
 *
 * [onSwipeLeft]/[onSwipeRight]（可选）：横向主导滑动切走（如目录页
 * 切 Tab），与竖直翻页共用 [EInkPageSwipe] 的统一轴向仲裁；null 时
 * 手势行为与历史版本一致（仅竖直翻页）。
 *
 * [enabled] = false 时不注册按键与滚轮处理（手势同 EInkPageSwipe 一并停用）：
 * 同屏常驻多个分页区（首页双 Tab）以激活态互斥，避免隐藏区占住
 * 枢纽栈顶吃掉按键；组合互斥的分页区（搜索结果/历史、目录 Tab、
 * 书架列表/网格）保持默认 true 即可。
 */
@Composable
fun Modifier.EInkPagerInput(
    enabled: Boolean = true,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
): Modifier {
    val currentUp by rememberUpdatedState(onPageUp)
    val currentDown by rememberUpdatedState(onPageDown)
    val hub = EInkEngineRegistry.keyEventHub
    val wheelHub = EInkEngineRegistry.wheelHub
    DisposableEffect(hub, wheelHub, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val unregisterKeys = hub.register { event ->
            hubPagingKeyEvent(event, onPageUp = currentUp, onPageDown = currentDown)
        }
        val unregisterWheel = wheelHub.register { axis ->
            hubWheelPagingEvent(axis, onPageUp = currentUp, onPageDown = currentDown)
        }
        onDispose {
            unregisterKeys()
            unregisterWheel()
        }
    }
    return EInkPageSwipe(
        enabled = enabled,
        onPageUp = onPageUp,
        onPageDown = onPageDown,
        onSwipeLeft = onSwipeLeft,
        onSwipeRight = onSwipeRight,
    )
}
