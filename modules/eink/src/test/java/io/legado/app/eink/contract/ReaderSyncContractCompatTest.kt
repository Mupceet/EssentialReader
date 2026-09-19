package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 契约源兼容性守护：云端进度同步成员（0.4.0 起）全部带默认实现，
 * 只实现既有成员面的宿主引擎无需改动即可编译——AAR 消费方升级零破坏。
 * LegacyHostEngine 刻意不实现 syncCloudProgress / applyCloudProgress，
 * 编译通过即守护成立。
 */
class ReaderSyncContractCompatTest {

    private class LegacyHostEngine : ReaderEngine {
        override fun register(callback: ReaderEngineCallback) = Unit
        override fun unregister(callback: ReaderEngineCallback) = Unit
        override fun isRegistered(callback: ReaderEngineCallback): Boolean = false
        override fun saveReadingProgress() = Unit
        override val sessionBook: ReaderBookSnapshot? get() = null
        override val sessionBookUrl: String? get() = null
        override val chapterSize: Int get() = 0
        override val currentChapterIndex: Int get() = 0
        override val currentPageIndex: Int get() = 0
        override val engineMessage: String? get() = null
        override val hasLaidOutPages: Boolean get() = false
        override val currentChapterPageSize: Int get() = 0
        override fun currentPage(): ReaderPageSnapshot? = null
        override fun loadBook(book: BookHandle) = Unit
        override fun reloadBook(book: BookHandle) = Unit
        override fun setInBookshelf(value: Boolean) = Unit
        override fun clearEngineMessage() = Unit
        override fun loadContent(resetPageOffset: Boolean) = Unit
        override fun loadContent(chapterIndex: Int, resetPageOffset: Boolean) = Unit
        override fun refreshToc() = Unit
        override suspend fun resolveBook(bookUrl: String): ReaderBookSnapshot? = null
        override suspend fun prepareBookData(book: BookHandle): ReaderPrepareResult =
            ReaderPrepareResult.Success
        override fun nextPage(): Boolean = false
        override fun prevPage(): Boolean = false
        override fun skipToPage(pageIndex: Int) = Unit
        override fun nextChapter(): Boolean = false
        override fun prevChapter(): Boolean = false
        override fun jumpToPosition(chapterIndex: Int, chapterPos: Int): Boolean = false
        override val autoReadIntervalSec: Int get() = 10
        override suspend fun setAutoReadIntervalSec(value: Int) = Unit
        override suspend fun refreshCurrentChapter() = Unit
        override fun startCache(count: Int, cacheAll: Boolean): Boolean? = null
        override suspend fun addSessionBookToShelf(): Boolean? = null
        override suspend fun removeSessionBookFromShelf(): Boolean? = null
        override fun updateViewSize(width: Int, height: Int) = Unit
        override fun applyStyle(style: ReaderTextStyle) = Unit
        override fun currentStyle(): ReaderTextStyle = ReaderTextStyle()
        override fun relayout() = Unit
        override val pageTouchSlop: Int get() = 0
        override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
            ReaderHeaderFooterVisibility(headerVisible = false, footerVisible = true)
        override fun formatTimeNow(): String = ""
    }

    private class LegacyCallback : ReaderEngineCallback {
        override fun onRequestShowMenu() = Unit
        override fun onLoadChapterList(book: ReaderBookSnapshot) = Unit
        override fun onContentUpdated(
            relativePosition: Int,
            resetPageOffset: Boolean,
            success: (() -> Unit)?,
        ) = Unit
        override fun onPageChanged() = Unit
        override fun onContentLoadFinish() = Unit
        override fun onLayoutException(e: Throwable) = Unit
        override fun onNotifyBookChanged() = Unit
    }

    @Test
    fun `legacy engine compiles and new sync members default to no-op`() {
        val engine = LegacyHostEngine()
        ReaderSyncTrigger.entries.forEach { engine.syncCloudProgress(it) }
        engine.applyCloudProgress(ReaderCloudProgress(chapterIndex = 1, chapterPos = 0))
    }

    @Test
    fun `legacy callback compiles and onCloudProgressNewer defaults to no-op`() {
        val callback = LegacyCallback()
        callback.onCloudProgressNewer(ReaderCloudProgress(chapterIndex = 2, chapterPos = 5))
    }

    @Test
    fun `cloud progress carries chapter position only`() {
        val progress = ReaderCloudProgress(chapterIndex = 3, chapterPos = 12)
        assertEquals(3, progress.chapterIndex)
        assertEquals(12, progress.chapterPos)
    }
}
