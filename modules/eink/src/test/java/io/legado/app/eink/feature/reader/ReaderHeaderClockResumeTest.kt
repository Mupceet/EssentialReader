package io.legado.app.eink.feature.reader

import android.app.Application
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.ReaderBookSnapshot
import io.legado.app.eink.contract.ReaderEngine
import io.legado.app.eink.contract.ReaderEngineCallback
import io.legado.app.eink.contract.ReaderHeaderFooterVisibility
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPrepareResult
import io.legado.app.eink.contract.ReaderTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 页眉时钟「启动即对表」回归（2026-11 缺陷）：时钟在 ON_PAUSE 取消、
 * ON_RESUME 重启，但重启后首个刷新被 delay 推迟到下一个分钟边界——锁屏
 * 回前台（或从目录/换源返回阅读）后页眉时间/电量停留在离开前的旧值，
 * 最长一分钟不更新；翻页路径即时调 [ReaderViewModel.updateTipInfo]，观感
 * 即「不刷新，翻页才更新」。宿主同位是 ReadBookController.onResume 显式
 * 派发 UpTime 效果刷首值（TIME_TICK 广播注册不保证立即首送），本用例锁定
 * 该语义：时钟任务启动必须先立即对表一次，再进入分钟对齐循环。
 *
 * 引擎注入经反射替换注册表 _readerEngine 字段（同 ReaderViewModelStyleBaseTest）；
 * Application 用 mockable stub（build.gradle testOptions returnDefaultValues，
 * registerReceiver/getSystemService 返回 null → 电量默认 100、网络监听跳过）。
 * Main dispatcher 替换为 StandardTestDispatcher：时钟任务的 delay 走虚拟
 * 时间，只 runCurrent 不推进虚拟时间即可在「任务启动」与「分钟循环推进」
 * 之间划出确定性断点。
 */
class ReaderHeaderClockResumeTest {

    private val engine = HeaderClockEngine()

    /** 虚拟时间调度器：只 runCurrent 不 advance，即可停在「任务启动」断点。 */
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        injectEngines(engine, HeaderClockSettings())
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // 注册表是进程级单例：恢复未注册态，不泄漏给其他测试类
        injectEngines(null, null)
    }

    @Test
    fun `时钟启动立即对表再进分钟循环`() {
        val vm = ReaderViewModel(Application())
        engine.now = "10:00"
        vm.onActivityResumed()
        // 只跑当前已排队任务、不推进虚拟时间：修复前首个刷新被 delay 推到
        // 下一分钟边界，headerTime 停留初始空串
        testDispatcher.scheduler.runCurrent()
        assertEquals("10:00", vm.uiState.value.headerTime)
    }

    @Test
    fun `锁屏窗口后回前台页眉立即跟上时间`() {
        val vm = ReaderViewModel(Application())
        engine.now = "10:00"
        vm.onActivityResumed()
        testDispatcher.scheduler.runCurrent()
        // 锁屏：ON_PAUSE 停时钟；期间真实时间走到 10:07
        vm.onActivityPaused()
        testDispatcher.scheduler.runCurrent()
        engine.now = "10:07"
        // 回前台：ON_RESUME 重启时钟
        vm.onActivityResumed()
        testDispatcher.scheduler.runCurrent()
        // 修复前：重启的时钟等下一分钟边界，页眉停留 10:00（实测缺陷路径）
        assertEquals("10:07", vm.uiState.value.headerTime)
    }

    private fun injectEngines(engine: ReaderEngine?, settings: GlobalSettings?) {
        // Kotlin object 成员是 INSTANCE 的实例字段
        EInkEngineRegistry::class.java.getDeclaredField("_readerEngine").apply {
            isAccessible = true
        }.set(EInkEngineRegistry, engine)
        EInkEngineRegistry::class.java.getDeclaredField("_globalSettings").apply {
            isAccessible = true
        }.set(EInkEngineRegistry, settings)
    }

    /** 最小设置桩：仅抽象成员 + 本用例无关的默认直通（同 ReaderViewModelStyleBaseTest）。 */
    private class HeaderClockSettings : GlobalSettings {
        override var autoRefreshBook = false
        override var defaultToRead = false
        override var volumeKeyPage = false
        override var useDefaultCover = false
        override var pullDownBookmark = false
        override var readerSourceInfoBar = false
        override var hideStatusBar = false
        override var showReviewBubbles = true
        override val threadCount = 1
        override val useAntiAlias = false
        override val preDownloadChapterCount = 0
        override val changeSourceCheckAuthor = true
        override var fontScaleSetting: Int? = null
        override var recentFontPathsEncoding = ""
    }

    /**
     * 可控时钟假引擎：formatTimeNow 返回测试直改的 [now]（替代真实系统钟），
     * 页眉可见性置 true（updateTipInfo 写 headerTime 不受可见性影响，
     * 置 true 对齐实测显示路径）。
     */
    private class HeaderClockEngine : ReaderEngine {
        /** 测试控制的「当前时刻」显示值。 */
        var now: String = ""

        override fun applyStyle(style: ReaderTextStyle) = Unit
        override fun currentStyle(): ReaderTextStyle = ReaderTextStyle()
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
        override fun updateViewSize(
            width: Int,
            height: Int,
            contentPadding: io.legado.app.eink.contract.ReaderEngine.ReaderContentPadding,
        ) = Unit
        override fun relayout() = Unit
        override val pageTouchSlop: Int get() = 0
        override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
            ReaderHeaderFooterVisibility(headerVisible = true, footerVisible = true)
        override fun formatTimeNow(): String = now
    }
}
