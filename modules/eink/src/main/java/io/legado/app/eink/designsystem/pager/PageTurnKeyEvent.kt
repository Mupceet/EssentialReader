package io.legado.app.eink.designsystem.pager

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP

/**
 * 翻页按键事件语义（纯函数）：阅读页与分页列表的自定义翻页键 + 音量键
 * 判定共用同一套，实参注入开关与模态在场状态——设计系统不持有按键枢纽
 * 与设置端口。
 *
 * 语义（判定优先级对齐宿主完整模式 ReadBookController.onKeyDown）：
 *  - 页内模态弹框在场（[io.legado.app.eink.designsystem.control.EInkModalPresence]）
 *    → 返回 false，按键放行系统；
 *  - 自定义键（[prevKeyCodes]/[nextKeyCodes]）**不受音量开关控制**：
 *    命中即翻页并消费——三项翻页输入（自定义键/音量键/滚轮）各自独立
 *    开关，自定义键优先于音量键判定（音量键被录为自定义键时按自定义
 *    方向生效，与完整模式一致）；
 *  - 音量+ 上一页、音量- 下一页（受 [volumePagingEnabled] 控制），
 *    仅首按（repeatCount == 0）翻页，长按重复不翻（自定义键同款首按
 *    语义）；
 *  - 命中键（自定义或生效中的音量键）抬起吞掉（true），保证按键对
 *    整体被消费；
 *  - 翻页边界不做判定：回调方（阅读页/各分页器）内部 canPage 守卫，
 *    到头不动作、按键仍被消费。
 */
internal fun pagingKeyEvent(
    action: Int,
    keyCode: Int,
    repeatCount: Int,
    volumePagingEnabled: Boolean,
    prevKeyCodes: Set<Int>,
    nextKeyCodes: Set<Int>,
    modalPresent: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean {
    if (modalPresent) return false
    val isPrev = keyCode in prevKeyCodes
    val isNext = keyCode in nextKeyCodes
    if (isPrev || isNext) {
        return when (action) {
            ACTION_DOWN -> {
                if (repeatCount == 0) {
                    if (isPrev) onPageUp() else onPageDown()
                }
                true
            }

            ACTION_UP -> true

            else -> false
        }
    }
    if (!volumePagingEnabled) return false
    return when (action) {
        ACTION_DOWN -> when (keyCode) {
            KEYCODE_VOLUME_UP -> {
                if (repeatCount == 0) onPageUp()
                true
            }

            KEYCODE_VOLUME_DOWN -> {
                if (repeatCount == 0) onPageDown()
                true
            }

            else -> false
        }

        ACTION_UP ->
            keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN

        else -> false
    }
}
