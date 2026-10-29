package io.legado.app.eink.feature.reader.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreensaverCoverQualityTest {

    @Test
    fun samplingNeverDecodesBelowTheLockScreenTarget() {
        assertEquals(1, calculateCoverSampleSize(3000, 4000, 1860, 2480))
        assertEquals(2, calculateCoverSampleSize(4000, 6000, 1860, 2480))
        assertEquals(2, calculateCoverSampleSize(3720, 4960, 1860, 2480))
        assertEquals(1, calculateCoverSampleSize(1000, 1500, 1860, 2480))
    }

    @Test
    fun samplingFollowsTheDeviceTargetSize() {
        // 目标尺寸来自设备真实显示尺寸：同一张源图在更小的面板上
        // 允许采样得更狠，在更大的面板上不允许采样
        assertEquals(2, calculateCoverSampleSize(3000, 4000, 1200, 1600))
        assertEquals(1, calculateCoverSampleSize(3000, 4000, 2000, 3000))
        assertEquals(1, calculateCoverSampleSize(1860, 2480, 1200, 1600))
    }

    @Test
    fun requestsReloadOnlyWhenTheSourceNeedsUpscaling() {
        val targetWidth = 1872
        val targetHeight = 1404
        assertFalse(
            coverNeedsHighResolutionReload(targetWidth, targetHeight, targetWidth, targetHeight),
        )
        assertTrue(
            coverNeedsHighResolutionReload(targetWidth - 1, targetHeight, targetWidth, targetHeight),
        )
        assertTrue(
            coverNeedsHighResolutionReload(targetWidth, targetHeight - 1, targetWidth, targetHeight),
        )
    }
}
