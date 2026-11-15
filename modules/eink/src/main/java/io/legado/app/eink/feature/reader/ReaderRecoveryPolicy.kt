package io.legado.app.eink.feature.reader

/**
 * 网络恢复自愈判定（纯函数）。
 *
 * 墨水屏设备灭屏断网后回前台存在网络重连窗口：失败正文/进书前置
 * 停在错误态无人补偿，用户需手动刷新。网络恢复事件触发时以本判定
 * 决定补偿动作；结构性错误（书籍不存在/没有书源/章节不存在/排版
 * 失败/本地书无内容）重试无意义，不自动恢复。
 *
 * 「获取正文失败」类下载失败文案会被宿主当正文排版成页（非 error
 * 态），凭 [currentChapterDownloadFailed] 结构化信号识别；进书前置
 * 失败（详情页出错/目录加载失败）仅网络书与网络相关，本地书归 None。
 */
internal enum class ReaderRecoveryAction {
    /** 无动作（正常态/结构性错误）。 */
    None,

    /** 重载当前章（[ReaderViewModel.refreshChapter] 同款路径）。 */
    RefreshChapter,

    /** 重新进书（ErrorView 重试按钮同款 [ReaderViewModel.attach] 路径）。 */
    Reattach,
}

internal object ReaderRecoveryPolicy {

    /** 进书前置失败前缀（VM 全角冒号文案，见 attach 的 prepareBookData 分支）。 */
    private val REATTACH_PREFIXES = listOf("详情页出错：", "目录加载失败：")

    fun evaluate(
        error: String?,
        currentChapterDownloadFailed: Boolean,
        isLocalBook: Boolean,
    ): ReaderRecoveryAction = when {
        error != null && !isLocalBook &&
            REATTACH_PREFIXES.any { error.startsWith(it) } -> ReaderRecoveryAction.Reattach

        error != null && ContentRetryPolicy.shouldRetry(error) -> ReaderRecoveryAction.RefreshChapter

        currentChapterDownloadFailed -> ReaderRecoveryAction.RefreshChapter

        else -> ReaderRecoveryAction.None
    }
}
