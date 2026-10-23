package io.legado.app.eink.feature.reader

import android.app.Application
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.ReaderBookSnapshot
import io.legado.app.eink.contract.ReaderEngine
import io.legado.app.eink.contract.ReaderEngineCallback
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderHeaderFooterVisibility
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPrepareResult
import io.legado.app.eink.contract.ReaderTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * applyStyleChange 写回 base 回归（2026-10 缺陷）：独立字体设置页
 * （FontSettingsScreen）经外部写径直改引擎样式、不经 VM，UiState 缓存不
 * 回流——若 applyStyleChange 以缓存快照为 base，其后任意排版参数调节
 * （切页眉页脚字号等）会把陈旧正文字体整套写回宿主，静默回滚外部修改。
 * 实测路径：当前字体 A → 字体设置页选 B → 切页眉页脚字号 → 正文回退 A、
 * 字体弹框高亮失配（recentFontPaths=B 与宿主 textFont=A 不等）。
 *
 * 端口注入经反射替换注册表 _readerEngine/_globalSettings 两个字段
 * （install 需 8 个必填端口，本测试只读写这两个）；Application 用
 * mockable stub（build.gradle testOptions returnDefaultValues）。
 * Main dispatcher 替换为 StandardTestDispatcher：viewModelScope 创建
 * 需要 Main，排队的防抖重排不推进即不触碰更多引擎面。
 */
class ReaderViewModelStyleBaseTest {

    private val engine = RecordingEngine()
    private val settings = FakeGlobalSettings()

    @Before
    fun setUp() {
        injectEngines(engine, settings)
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // 注册表是进程级单例：恢复未注册态，不泄漏给其他测试类
        injectEngines(null, null)
    }

    @Test
    fun `外部写径换字体后调排版参数不回滚字体`() {
        val vm = ReaderViewModel(Application())
        // 先经 VM 写径选字体 A（字体弹框路径，同步 UiState 缓存）
        vm.setReaderFont(ReaderFontSelection.File("content://fonts/a.ttf"))
        // 独立字体设置页外部写径选字体 B：直改引擎、不经 VM（同 FontSettingsScreen）
        engine.applyStyle(
            engine.currentStyle().withUnifiedFont(ReaderFontSelection.File("content://fonts/b.ttf")),
        )
        // 外部改字体后切页眉页脚字号（实测复现操作）
        vm.setTipSize(20)

        val applied = engine.appliedStyles.last()
        assertEquals(
            "content://fonts/b.ttf",
            (applied.bodyFont as ReaderFontSelection.File).path,
        )
        // 引擎侧字体保持 B（写回后反读不回退，宿主 textFont 不被污染）
        assertEquals(
            "content://fonts/b.ttf",
            (engine.currentStyle().bodyFont as ReaderFontSelection.File).path,
        )
    }

    @Test
    fun `VM 写径选字体与非字体参数调节保持既有行为`() {
        val vm = ReaderViewModel(Application())
        vm.setReaderFont(ReaderFontSelection.File("content://fonts/a.ttf"))
        assertEquals(
            "content://fonts/a.ttf",
            (engine.currentStyle().bodyFont as ReaderFontSelection.File).path,
        )
        // fallback 目录未声明 HEADER/FOOTER_SIZE，clampInt 原值直通
        vm.setTipSize(18)
        assertEquals(18, engine.currentStyle().footerSize)
        assertEquals(18, engine.currentStyle().headerSize)
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

    /** 有状态假引擎：currentStyle 反映最后一次 applyStyle（同步可见，同宿主内存写语义）。 */
    private class RecordingEngine : ReaderEngine {
        var snapshot: ReaderTextStyle = ReaderTextStyle()
            private set
        val appliedStyles = mutableListOf<ReaderTextStyle>()

        override fun applyStyle(style: ReaderTextStyle) {
            appliedStyles += style
            snapshot = style
        }

        override fun currentStyle(): ReaderTextStyle = snapshot

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
        override fun relayout() = Unit
        override val pageTouchSlop: Int get() = 0
        override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
            ReaderHeaderFooterVisibility(headerVisible = false, footerVisible = true)
        override fun formatTimeNow(): String = ""
    }

    /** 最小设置桩：接口抽象成员 + 本用例触碰的 recentFontPathsEncoding（覆盖默认丢弃体）。 */
    private class FakeGlobalSettings : GlobalSettings {
        override var autoRefreshBook = false
        override var defaultToRead = false
        override var volumeKeyPage = false
        override var useDefaultCover = false
        override var pullDownBookmark = false
        override var hideStatusBar = false
        override var showReviewBubbles = true
        override val threadCount = 1
        override val useAntiAlias = false
        override val preDownloadChapterCount = 0
        override val changeSourceCheckAuthor = true
        override var fontScaleSetting: Int? = null
        override var recentFontPathsEncoding = ""
    }
}
