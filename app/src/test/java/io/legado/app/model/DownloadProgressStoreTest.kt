package io.legado.app.model

import android.app.DownloadManager
import io.legado.app.model.DownloadProgressStore.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressStoreTest {

    @Test
    fun `system download status maps to model states`() {
        assertEquals(DownloadState.PENDING, downloadStateOf(DownloadManager.STATUS_PENDING))
        assertEquals(DownloadState.RUNNING, downloadStateOf(DownloadManager.STATUS_RUNNING))
        assertEquals(DownloadState.PAUSED, downloadStateOf(DownloadManager.STATUS_PAUSED))
        assertEquals(DownloadState.SUCCEEDED, downloadStateOf(DownloadManager.STATUS_SUCCESSFUL))
        assertEquals(DownloadState.FAILED, downloadStateOf(DownloadManager.STATUS_FAILED))
    }

    @Test
    fun `unknown system status falls back to failed terminal state`() {
        assertEquals(DownloadState.FAILED, downloadStateOf(-1))
        assertEquals(DownloadState.FAILED, downloadStateOf(0))
    }
}
