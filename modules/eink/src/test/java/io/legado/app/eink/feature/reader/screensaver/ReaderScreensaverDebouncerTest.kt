package io.legado.app.eink.feature.reader.screensaver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderScreensaverDebouncerTest {

    @Test
    fun repeatedRequestsCoalesceToLatestDebounceWindow() = runTest {
        var calls = 0
        val debouncer = ReaderScreensaverDebouncer(backgroundScope, 300L) {
            calls++
        }

        debouncer.request(immediate = false)
        advanceTimeBy(200L)
        debouncer.request(immediate = false)
        advanceTimeBy(299L)
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun immediateRequestCancelsPendingDebounceAndRunsOnce() = runTest {
        var calls = 0
        val debouncer = ReaderScreensaverDebouncer(backgroundScope, 300L) {
            calls++
        }

        debouncer.request(immediate = false)
        advanceTimeBy(100L)
        debouncer.request(immediate = true)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, calls)
    }
}
