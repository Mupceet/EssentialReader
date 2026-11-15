package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络恢复自愈判定锚定：只自动恢复与网络相关的失败（下载失败页、
 * 协程级网络异常、网络书进书前置失败）；结构性错误不自动重试。
 * 前缀锚定 VM 全角冒号文案（「目录加载失败：」「详情页出错：」）。
 */
class ReaderRecoveryPolicyTest {

    @Test
    fun `正常态不动作与下载失败计数触发重载`() {
        assertEquals(
            ReaderRecoveryAction.None,
            ReaderRecoveryPolicy.evaluate(
                error = null, currentChapterDownloadFailed = false, isLocalBook = false
            )
        )
        // 下载失败时错误文案被当正文排版成页，error 为 null——凭结构化信号判定
        assertEquals(
            ReaderRecoveryAction.RefreshChapter,
            ReaderRecoveryPolicy.evaluate(
                error = null, currentChapterDownloadFailed = true, isLocalBook = false
            )
        )
    }

    @Test
    fun `进书前置失败仅网络书重新进书`() {
        assertEquals(
            ReaderRecoveryAction.Reattach,
            ReaderRecoveryPolicy.evaluate(
                error = "详情页出错：java.net.SocketTimeoutException",
                currentChapterDownloadFailed = false, isLocalBook = false
            )
        )
        assertEquals(
            ReaderRecoveryAction.Reattach,
            ReaderRecoveryPolicy.evaluate(
                error = "目录加载失败：connect timed out",
                currentChapterDownloadFailed = false, isLocalBook = false
            )
        )
        // 本地书目录/详情失败与网络无关
        assertEquals(
            ReaderRecoveryAction.None,
            ReaderRecoveryPolicy.evaluate(
                error = "目录加载失败：EPUB 解析失败",
                currentChapterDownloadFailed = false, isLocalBook = true
            )
        )
    }

    @Test
    fun `加载正文出错可重载`() {
        assertEquals(
            ReaderRecoveryAction.RefreshChapter,
            ReaderRecoveryPolicy.evaluate(
                error = "加载正文出错\njava.net.UnknownHostException",
                currentChapterDownloadFailed = false, isLocalBook = false
            )
        )
    }

    @Test
    fun `结构性错误不动作`() {
        for (error in listOf("书籍不存在", "没有书源", "章节不存在", "排版失败：measure 溢出")) {
            assertEquals(
                ReaderRecoveryAction.None,
                ReaderRecoveryPolicy.evaluate(
                    error = error, currentChapterDownloadFailed = false, isLocalBook = false
                )
            )
        }
    }

    @Test
    fun `Reattach 优先于 RefreshChapter`() {
        assertEquals(
            ReaderRecoveryAction.Reattach,
            ReaderRecoveryPolicy.evaluate(
                error = "目录加载失败：connect timed out",
                currentChapterDownloadFailed = true, isLocalBook = false
            )
        )
    }
}
