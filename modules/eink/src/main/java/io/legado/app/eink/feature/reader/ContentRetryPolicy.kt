package io.legado.app.eink.feature.reader

/**
 * 正文加载失败自动重试判定（纯函数）。
 *
 * 宿主 ReadBook.loadContent 的正文下载失败经 upMsg 上报
 * 「加载正文出错\n…」；该类错误多为源端限流/瞬时故障（换源前的全源
 * 搜索是请求突发，首章请求常撞限流窗口），单发重试即可恢复——与用户
 * 手动刷新同一条链。结构性错误（书籍不存在/没有书源/章节不存在）重试
 * 无意义，不自动重试。
 */
internal object ContentRetryPolicy {

    /** 重试退避：给源端限流窗口留出间隔（量级对齐预下载重试的错峰节奏）。 */
    const val RETRY_DELAY_MS = 2_500L

    private const val RETRYABLE_PREFIX = "加载正文出错"

    fun shouldRetry(errorMessage: String): Boolean = errorMessage.startsWith(RETRYABLE_PREFIX)
}
