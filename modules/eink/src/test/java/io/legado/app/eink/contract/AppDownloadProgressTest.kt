package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDownloadProgressTest {

    @Test
    fun `percent derives from byte ratio`() {
        val progress = AppDownloadProgress(
            state = AppDownloadState.RUNNING,
            bytesSoFar = 25 * 1024 * 1024,
            totalBytes = 100 * 1024 * 1024,
        )
        assertEquals(25, progress.percent)
    }

    @Test
    fun `unknown total bytes reports negative percent`() {
        val progress = AppDownloadProgress(
            state = AppDownloadState.RUNNING,
            bytesSoFar = 1024,
            totalBytes = -1,
        )
        assertEquals(-1, progress.percent)
    }

    @Test
    fun `percent clamps beyond total into hundred`() {
        val over = AppDownloadProgress(
            state = AppDownloadState.RUNNING,
            bytesSoFar = 120,
            totalBytes = 100,
        )
        assertEquals(100, over.percent)
    }
}
