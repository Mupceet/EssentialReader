package io.legado.app.eink.bridge

import io.legado.app.eink.contract.AppDownloadState
import io.legado.app.model.DownloadProgressStore.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateEngineImplTest {

    @Test
    fun `model download state maps onto contract states`() {
        assertEquals(AppDownloadState.PENDING, DownloadState.PENDING.toAppDownloadState())
        assertEquals(AppDownloadState.RUNNING, DownloadState.RUNNING.toAppDownloadState())
        assertEquals(AppDownloadState.PAUSED, DownloadState.PAUSED.toAppDownloadState())
        assertEquals(AppDownloadState.SUCCEEDED, DownloadState.SUCCEEDED.toAppDownloadState())
        assertEquals(AppDownloadState.FAILED, DownloadState.FAILED.toAppDownloadState())
    }
}
