package io.legado.app.eink.feature.reader

/**
 * 云端进度同步的模块侧触发门槛（复刻宿主 justInitData / 装载完成同步位）。
 *
 * - [onFreshEntryStarted]：新阅读会话（VM 首次装载一本书）开始——武装
 *   进书同步并进入初始装载窗口；
 * - [consumeEntrySync]：进书内容就绪时消费（一次性），返回是否应触发
 *   BookEntered 同步；
 * - [allowNetworkSync]：网络恢复同步仅在初始装载窗口结束后放行（避免
 *   与进书同步竞态，宿主 `!justInitData` 同位）；
 * - [onPaused]：Activity 级暂停关闭初始装载窗口（宿主 handleOnPause
 *   末尾清零 justInitData 同位）。
 */
internal class ReaderSyncGate {

    private var entrySyncArmed = false
    private var withinInitialLoad = false

    /** 仅在新会话装载开始时调用（`loadedBookUrl == null` 判定由 VM 负责）。 */
    fun onFreshEntryStarted() {
        entrySyncArmed = true
        withinInitialLoad = true
    }

    /** 进书内容就绪：一次性消费武装标记。 */
    fun consumeEntrySync(): Boolean {
        if (!entrySyncArmed) return false
        entrySyncArmed = false
        return true
    }

    /** 网络恢复同步是否放行。 */
    fun allowNetworkSync(): Boolean = !withinInitialLoad

    /** Activity 级暂停：关闭初始装载窗口。 */
    fun onPaused() {
        withinInitialLoad = false
    }
}
