package io.legado.app.eink.feature.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面请求路由：http(s) 进端口抓取管线（EInkCoverData 标记），
 * 其余形态（data: 内联/本地路径/content uri）原样交给内置 fetcher——
 * 显示与预取共用同一构造，路由错档会让宿主端口收到无法处理的 data。
 */
class CoverRequestDataTest {

    @Test
    fun `http 与 https 封面包装为端口抓取标记`() {
        val data = coverRequestData("http://example.com/cover.jpg", "src_origin")
        assertTrue(data is EInkCoverData)
        data as EInkCoverData
        assertEquals("http://example.com/cover.jpg", data.url)
        assertEquals("src_origin", data.sourceOrigin)

        assertTrue(coverRequestData("HTTPS://example.com/c.jpg", null) is EInkCoverData)
    }

    @Test
    fun `非 http 封面原样透传`() {
        assertEquals(
            "data:image/png;base64,AAAA",
            coverRequestData("data:image/png;base64,AAAA", null),
        )
        assertEquals(
            "/storage/emulated/0/covers/a.jpg",
            coverRequestData("/storage/emulated/0/covers/a.jpg", null),
        )
        assertEquals(
            "content://media/external/images/1",
            coverRequestData("content://media/external/images/1", null),
        )
    }
}
