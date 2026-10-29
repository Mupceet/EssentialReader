package io.legado.app.eink.bridge

import android.app.Application
import io.legado.app.data.dao.HighlightRuleDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.repository.HighlightRuleRepository
import io.legado.app.data.repository.ReadStyleConfigStore
import io.legado.app.data.repository.ReadStyleRepository
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import io.legado.app.feature.reader.core.source.ReaderChapterSource
import io.legado.app.feature.reader.platform.ReaderAndroidPaginationStyle
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.reader.ReaderChapterInput
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 分页缓存键的**高亮规则活哈希**锚定。
 *
 * 旧键取 `ReadBookConfig.durConfig.highlightRules`（排版配置内嵌副本，仅
 * 导出时刷新）——编辑规则后哈希不变，旧页缓存被复用、样式不更新。现键
 * 吃 DB `loadEnabled` 的实时结果，本测试锁定「规则变化 ⇒ 键变化」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReaderPaginationCacheKeyTest {

    /** 最小 DAO 桩：仅满足构造链，键计算不触库。 */
    private object NoopHighlightRuleDao : HighlightRuleDao {
        override fun getAll(): List<HighlightRule> = emptyList()
        override fun getEnabled(): List<HighlightRule> = emptyList()
        override fun insertAll(rules: List<HighlightRule>) = Unit
        override fun update(rule: HighlightRule) = Unit
        override fun delete(rule: HighlightRule) = Unit
        override fun deleteAll() = Unit
        override fun deleteGlobal() = Unit
        override fun count(): Int = 0
    }

    private class StubReadSettingsGateway : ReadSettingsGateway {
        override val currentSettings: ReadSettings = ReadSettings()
        override val settings = MutableStateFlow(ReadSettings())
        override suspend fun update(transform: (ReadSettings) -> ReadSettings) = Unit
    }

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.injectAsAppCtx()
        // 键计算读 ReadBookConfig 全局（段落缩进/两端对齐/标题模式/装饰 extent），
        // 用最小依赖装配（真实 store + 桩 DAO/gateway，配置文件缺省走默认）
        ReadBookConfig.initialize(
            configStore = ReadStyleConfigStore(
                ReadStyleRepository(HighlightRuleRepository(NoopHighlightRuleDao, app)),
            ),
            readSettingsGateway = StubReadSettingsGateway(),
        )
    }

    private fun input() = ReaderChapterInput(
        book = Book(bookUrl = "book://1"),
        bookSource = null,
        chapter = BookChapter(url = "chapter://1", bookUrl = "book://1", index = 3),
        displayTitle = "第三章",
        content = BookContent(
            sameTitleRemoved = false,
            textList = listOf("正文内容"),
            effectiveReplaceRules = null,
        ),
        source = ReaderChapterSource(
            chapterIndex = 3,
            title = "第三章",
            blocks = emptyList(),
            characterCount = 4,
        ),
        contentHash = 1,
        contentProcessesHash = 2,
        sourceHash = 3,
        bookSourceHash = 4,
        pageEstimateGeneration = 5,
    )

    private val style: ReaderAndroidPaginationStyle by lazy {
        val paint = android.text.TextPaint().apply { textSize = 40f }
        val textStyle = ReaderTextStyle(colorArgb = 0, fontSizePx = 40f)
        ReaderAndroidPaginationStyle(
            bodyPaint = paint,
            titlePaint = paint,
            bodyStyle = textStyle,
            titleStyle = textStyle,
            paddingLeftPx = 0,
            paddingTopPx = 0,
            paddingRightPx = 0,
            paddingBottomPx = 0,
            bodyTextHeightPx = 50f,
            titleTextHeightPx = 50f,
            bodyBaselineOffsetPx = 40f,
            titleBaselineOffsetPx = 40f,
            lineSpacingExtra = 0f,
            titleLineSpacingExtra = 0f,
            paragraphSpacing = 0,
        )
    }

    @Test
    fun `规则变化反映到缓存键`() {
        val emptyRules = readerPaginationCacheKey(input(), style, 1000, 1400, emptyList(), 40f, 40f)
        val withRule = readerPaginationCacheKey(
            input(), style, 1000, 1400,
            listOf(HighlightRule(id = "a", pattern = "“[^”]+”")), 40f, 40f,
        )
        val withOtherRule = readerPaginationCacheKey(
            input(), style, 1000, 1400,
            listOf(HighlightRule(id = "b", pattern = "《[^》]+》")), 40f, 40f,
        )
        // 同输入同规则可复现（缓存命中前提）
        assertEquals(
            emptyRules,
            readerPaginationCacheKey(input(), style, 1000, 1400, emptyList(), 40f, 40f),
        )
        assertNotEquals(emptyRules, withRule)
        assertNotEquals(withRule, withOtherRule)
    }

    @Test
    fun `规则启停变化同样反映到缓存键`() {
        val enabled = readerPaginationCacheKey(
            input(), style, 1000, 1400,
            listOf(HighlightRule(id = "a", pattern = "x", enabled = true)), 40f, 40f,
        )
        val disabled = readerPaginationCacheKey(
            input(), style, 1000, 1400,
            listOf(HighlightRule(id = "a", pattern = "x", enabled = false)), 40f, 40f,
        )
        assertNotEquals(enabled, disabled)
    }

    @Test
    fun `排版内容边距变化反映到缓存键`() {
        // 系统栏避让（同源宿主策略）变化 ⇒ 正文区域变化 ⇒ 必须重排
        val noAvoidance = readerPaginationCacheKey(
            input(), style, 1000, 1400, emptyList(), 40f, 40f,
            io.legado.app.eink.contract.ReaderEngine.ReaderContentPadding(),
        )
        val statusBarAvoided = readerPaginationCacheKey(
            input(), style, 1000, 1400, emptyList(), 40f, 40f,
            io.legado.app.eink.contract.ReaderEngine.ReaderContentPadding(top = 80),
        )
        val navBarAvoided = readerPaginationCacheKey(
            input(), style, 1000, 1400, emptyList(), 40f, 40f,
            io.legado.app.eink.contract.ReaderEngine.ReaderContentPadding(bottom = 48),
        )
        // 边距归零可复现（切换两套阅读界面同避让值 ⇒ 缓存键一致）
        assertEquals(
            noAvoidance,
            readerPaginationCacheKey(
                input(), style, 1000, 1400, emptyList(), 40f, 40f,
                io.legado.app.eink.contract.ReaderEngine.ReaderContentPadding(),
            ),
        )
        assertNotEquals(noAvoidance, statusBarAvoided)
        assertNotEquals(noAvoidance, navBarAvoided)
        assertNotEquals(statusBarAvoided, navBarAvoided)
    }
}
