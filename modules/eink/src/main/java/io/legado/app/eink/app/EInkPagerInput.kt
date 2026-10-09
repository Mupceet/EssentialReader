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
 * [EInkPageSwipe] 直通）+ 音量键/翻页器翻页（[EInkKeyEventHub] 栈顶
 * 注册，事件时实时读 GlobalSettings.volumeKeyPage）。
 *
 * 音量语义与阅读页共用 [hubVolumePagingEvent]（designsystem 纯函数
 * 语义）：开关关闭或页内模态弹框在场放行系统音量调节；首按翻页、
 * 长按重复不翻；翻页到头由各分页器 canPage 守卫（不动作、仍消费）。
 *
 * [enabled] = false 时不注册按键（手势同 EInkPageSwipe 一并停用）：
 * 同屏常驻多个分页区（首页双 Tab）以激活态互斥，避免隐藏区占住
 * 枢纽栈顶吃掉按键；组合互斥的分页区（搜索结果/历史、目录 Tab、
 * 书架列表/网格）保持默认 true 即可。
 */
@Composable
fun Modifier.EInkPagerInput(
    enabled: Boolean = true,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Modifier {
    val currentUp by rememberUpdatedState(onPageUp)
    val currentDown by rememberUpdatedState(onPageDown)
    val hub = EInkEngineRegistry.keyEventHub
    DisposableEffect(hub, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val unregister = hub.register { event ->
            hubVolumePagingEvent(event, onPageUp = currentUp, onPageDown = currentDown)
        }
        onDispose { unregister() }
    }
    return EInkPageSwipe(enabled = enabled, onPageUp = onPageUp, onPageDown = onPageDown)
}
