package io.legado.app.eink.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文失败自动重试判定锚定：只重试宿主 ReadBook 上报的「加载正文出错」
 * 类瞬时失败；结构性错误（重试不可能成功）不得触发自动重试。
 */
class ContentRetryPolicyTest {

    @Test
    fun `正文加载类瞬时失败可重试`() {
        assertTrue(ContentRetryPolicy.shouldRetry("加载正文出错\njava.net.SocketTimeoutException"))
        assertTrue(ContentRetryPolicy.shouldRetry("加载正文出错\n获取正文失败"))
    }

    @Test
    fun `结构性错误不重试`() {
        assertFalse(ContentRetryPolicy.shouldRetry("书籍不存在"))
        assertFalse(ContentRetryPolicy.shouldRetry("没有书源"))
        assertFalse(ContentRetryPolicy.shouldRetry("章节不存在"))
        assertFalse(ContentRetryPolicy.shouldRetry("目录加载失败: 连接超时"))
        assertFalse(ContentRetryPolicy.shouldRetry(""))
    }
}
