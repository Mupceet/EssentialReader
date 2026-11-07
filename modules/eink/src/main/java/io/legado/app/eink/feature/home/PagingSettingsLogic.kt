package io.legado.app.eink.feature.home

import android.view.KeyEvent

/** 翻页方向（「翻页设置」页的录入方向与预览提示共用）。 */
internal enum class PagingDirection { PREV, NEXT }

/**
 * 录入一个物理键为指定方向的翻页键（纯函数）：
 *  - 非正键码拒录（返回 null）；
 *  - 一键只属一个方向：录入时自动从另一方向键集移除（静默迁移——
 *    保存后方向行摘要随之更新，不另弹提示）。
 *
 * 返回新的键对；调用方负责序列化（formatKeyCodes）与提交时机
 * （GlobalSettings.setPageKeys——弹框形态下保存时一次提交）。BACK 键
 * 在录入处理器更早分支拦截（收起录入弹框），不会到达本函数。
 */
internal fun recordPageKey(
    prevKeyCodes: Set<Int>,
    nextKeyCodes: Set<Int>,
    direction: PagingDirection,
    keyCode: Int,
): Pair<Set<Int>, Set<Int>>? {
    if (keyCode <= 0) return null
    return when (direction) {
        PagingDirection.PREV -> (prevKeyCodes + keyCode) to (nextKeyCodes - keyCode)
        PagingDirection.NEXT -> (prevKeyCodes - keyCode) to (nextKeyCodes + keyCode)
    }
}

/**
 * 录入弹框打开期间的按键处理（纯函数形态，Screen 内接线）：
 *  - BACK 按下：收起弹框并消费（返回键留给导航，不可录为翻页键）；
 *  - 其它键首按：经 [recordPageKey] 录入待定集（非正键码静默忽略）；
 *  - 长按重复/抬起/其它动作：吞掉——弹框打开期间所有按键对整体消费，
 *    防止穿透到翻页判定。
 */
internal fun handleCaptureKey(
    action: Int,
    keyCode: Int,
    repeatCount: Int,
    direction: PagingDirection,
    prevKeyCodes: Set<Int>,
    nextKeyCodes: Set<Int>,
    onRecorded: (prevKeyCodes: Set<Int>, nextKeyCodes: Set<Int>) -> Unit,
    onExitCapture: () -> Unit,
): Boolean = when (action) {
    KeyEvent.ACTION_DOWN -> when {
        keyCode == KeyEvent.KEYCODE_BACK -> {
            onExitCapture()
            true
        }

        repeatCount == 0 -> {
            recordPageKey(prevKeyCodes, nextKeyCodes, direction, keyCode)
                ?.let { (prev, next) -> onRecorded(prev, next) }
            true
        }

        else -> true
    }

    KeyEvent.ACTION_UP -> true

    else -> true
}

/**
 * 键码显示名：常用键映射中文名，冷门键回落「键码」；键码数字恒保留
 * ——完整模式自定义按键配置同口径展示键码，两侧对照可读。
 */
internal fun pageKeyDisplayName(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> "音量上"
    KeyEvent.KEYCODE_VOLUME_DOWN -> "音量下"
    KeyEvent.KEYCODE_DPAD_UP -> "方向上"
    KeyEvent.KEYCODE_DPAD_DOWN -> "方向下"
    KeyEvent.KEYCODE_DPAD_LEFT -> "方向左"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "方向右"
    KeyEvent.KEYCODE_PAGE_UP -> "上翻页"
    KeyEvent.KEYCODE_PAGE_DOWN -> "下翻页"
    KeyEvent.KEYCODE_SPACE -> "空格"
    KeyEvent.KEYCODE_ENTER -> "回车"
    else -> "键码"
} + " $keyCode"
