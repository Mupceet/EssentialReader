package io.legado.app.eink.feature.reader.screensaver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces rapid page/settings events into one publish attempt.
 *
 * Immediate requests cancel the pending debounce window so entering the
 * background or leaving the reader can flush the latest page promptly.
 */
internal class ReaderScreensaverDebouncer(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val onReady: suspend () -> Unit,
) {
    private var pendingJob: Job? = null

    fun request(immediate: Boolean): Job {
        pendingJob?.cancel()
        return scope.launch {
            if (!immediate) delay(debounceMillis)
            onReady()
        }.also { pendingJob = it }
    }

    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
    }
}
