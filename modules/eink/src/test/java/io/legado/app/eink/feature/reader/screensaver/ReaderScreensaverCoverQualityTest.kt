package io.legado.app.eink.feature.reader.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreensaverCoverQualityTest {

    @Test
    fun samplingNeverDecodesBelowTheLockScreenTarget() {
        assertEquals(1, calculateCoverSampleSize(3000, 4000))
        assertEquals(2, calculateCoverSampleSize(4000, 6000))
        assertEquals(2, calculateCoverSampleSize(3720, 4960))
        assertEquals(1, calculateCoverSampleSize(1000, 1500))
    }

    @Test
    fun requestsReloadOnlyWhenTheSourceNeedsUpscaling() {
        assertFalse(
            coverNeedsHighResolutionReload(
                width = ReaderScreensaverRenderer.TARGET_WIDTH,
                height = ReaderScreensaverRenderer.TARGET_HEIGHT,
            ),
        )
        assertTrue(
            coverNeedsHighResolutionReload(
                width = ReaderScreensaverRenderer.TARGET_WIDTH - 1,
                height = ReaderScreensaverRenderer.TARGET_HEIGHT,
            ),
        )
        assertTrue(
            coverNeedsHighResolutionReload(
                width = ReaderScreensaverRenderer.TARGET_WIDTH,
                height = ReaderScreensaverRenderer.TARGET_HEIGHT - 1,
            ),
        )
    }
}
