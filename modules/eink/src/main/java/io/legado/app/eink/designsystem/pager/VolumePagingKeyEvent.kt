package io.legado.app.eink.designsystem.pager

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP

/**
 * 音量键翻页事件语义（纯函数）：阅读页与分页列表的音量键/翻页器接入
 * 共用同一套判定，实参注入开关与模态在场状态——设计系统不持有按键
 * 枢纽与设置端口。
 *
 * 语义（对齐宿主 ReadBookController.volumeKeyPage）：
 *  - 开关关闭或页内模态弹框在场（[io.legado.app.eink.designsystem.control.EInkModalPresence]）
 *    → 返回 false，按键放行系统（音量调节照常）；
 *  - 音量+ 上一页、音量- 下一页，仅首按（repeatCount == 0）翻页，
 *    长按重复不翻（宿主 keyPageDebounce 同样忽略长按）；
 *  - 音量键抬起吞掉（true），保证按键对整体被消费；
 *  - 翻页边界不做判定：回调方（阅读页/各分页器）内部 canPage 守卫，
 *    到头不动作、按键仍被消费（与阅读页既有口径一致——开关开启即
 *    音量键不再是音量键，边界处不弹系统音量面板）。
 */
internal fun volumePagingKeyEvent(
    action: Int,
    keyCode: Int,
    repeatCount: Int,
    pagingEnabled: Boolean,
    modalPresent: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean {
    if (!pagingEnabled || modalPresent) return false
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
