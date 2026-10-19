package io.legado.app.eink.bridge

import android.app.Application
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.eink.contract.BookshelfCoverRefUiModel
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfShelfEntryUiModel
import io.legado.app.eink.contract.BookshelfGroupIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * BookshelfGroupEngineImpl 的纯函数面：筛选器精选集装配 + 顶层混合条目装配
 * （门控/置顶/排序）。数据库交互不在单测范围（宿主层既有查询，真机验证）。
 *
 * buildFilterUiModels 经 appCtx 解析系统筛选器默认名，按仓库惯例注入
 * Robolectric 应用上下文，并以 zh-rCN 限定符固定断言语言，不随运行环境漂移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "zh-rCN")
class BookshelfGroupEngineImplTest {

    @Before
    fun setUp() {
        // application = Application::class：不拉真 App.onCreate（Koin/DB/Cronet）
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        // object 的 selectedFilter/selectedGroup 是带初始化器的 val（backing
        // field），clinit 实例化时即解析 bookshelfSettingsGateway 构建 Flow
        // 链——测试必须先启动带该网关定义的 Koin 才能触碰 object；守卫方法
        // 的 require 仍先于任何网关方法调用，断言语义不受影响
        stopKoin()
        startKoin {
            modules(
                module {
                    single<BookshelfSettingsGateway> { TestSettingsGateway() }
                }
            )
        }
    }

    /** 仅满足 clinit 构建 selectedFilter/selectedGroup 冷流链的最小网关。 */
    private class TestSettingsGateway : BookshelfSettingsGateway {
        override val currentSettings: BookshelfSettings
            get() = error("不在本测试范围")
        override val settings: Flow<BookshelfSettings> = emptyFlow()
        override suspend fun update(transform: (BookshelfSettings) -> BookshelfSettings) = Unit
    }

    private fun group(id: Long, name: String = "组$id", order: Int = 0) =
        BookGroup(groupId = id, groupName = name, order = order)

    private fun book(url: String) = Book(bookUrl = url)

    /** BookshelfItemUiModel 无默认参，测试统一经本工厂补全其余字段。 */
    private fun item(url: String) = BookshelfItemUiModel(
        bookUrl = url,
        name = "n",
        author = "a",
        displayAuthor = "a",
        coverUrl = null,
        origin = "o",
        currentChapterTitle = null,
        latestChapterTitle = null,
        unreadCount = 0,
        hasNewChapter = false,
    )

    private val nameOf: (BookGroup) -> String = { it.groupName }

    private val mapper: (Book) -> BookshelfItemUiModel = { item(it.bookUrl) }

    // ---- buildFilterUiModels ----

    @Test
    fun `筛选面板按精选集顺序装配且无筛选常驻`() {
        val counts = mapOf(
            BookGroup.IdAll to 9, BookGroup.IdReading to 2, BookGroup.IdUnread to 0,
        )
        val result = buildFilterUiModels(
            visibleSystemRows = listOf(group(BookGroup.IdAll), group(BookGroup.IdReading)),
            systemCounts = counts, hideEmpty = true,
            noneName = "无筛选", nameOf = { "名$it" },
        )
        assertEquals(
            listOf(
                BookshelfGroupUiModel(BookshelfGroupIds.NONE, "无筛选", 9),
                BookshelfGroupUiModel(BookGroup.IdAll, "名-1", 9),
                BookshelfGroupUiModel(BookGroup.IdReading, "名-20", 2),
            ),
            result,
        )
    }

    @Test
    fun `hideEmpty 关闭时空筛选器保留`() {
        val result = buildFilterUiModels(
            visibleSystemRows = listOf(group(BookGroup.IdUnread)),
            systemCounts = mapOf(BookGroup.IdAll to 5, BookGroup.IdUnread to 0),
            hideEmpty = false, noneName = "无筛选", nameOf = { "名$it" },
        )
        assertEquals(2, result.size)
        assertEquals(0, result[1].bookCount)
    }

    // ---- buildShelfWithGroupsEntries ----

    @Test
    fun `空组条目保留显示且条目置顶`() {
        val groups = listOf(group(0b1, order = 0), group(0b10, order = 1))
        val result = buildShelfWithGroupsEntries(
            userGroups = groups,
            userCounts = mapOf(0b1L to 2, 0b10L to 0),
            groupCovers = emptyMap(),
            ungroupedBooks = listOf(book("u1"), book("u2")),
            nameOf = nameOf, bookMapper = mapper,
        )
        assertEquals(
            listOf(
                BookshelfShelfEntryUiModel.Group(0b1, "组1", 2, emptyList()),
                BookshelfShelfEntryUiModel.Group(0b10, "组2", 0, emptyList()),
                BookshelfShelfEntryUiModel.Book(item("u1")),
                BookshelfShelfEntryUiModel.Book(item("u2")),
            ),
            result,
        )
    }

    @Test
    fun `hideEmpty 关闭时空组条目保留且顺序不变`() {
        val groups = listOf(group(0b1, order = 0), group(0b10, order = 1))
        val result = buildShelfWithGroupsEntries(
            userGroups = groups,
            userCounts = mapOf(0b1L to 0, 0b10L to 3),
            groupCovers = emptyMap(),
            ungroupedBooks = listOf(book("u1")),
            nameOf = nameOf, bookMapper = mapper,
        )
        assertEquals(
            listOf(
                BookshelfShelfEntryUiModel.Group(0b1, "组1", 0, emptyList()),
                BookshelfShelfEntryUiModel.Group(0b10, "组2", 3, emptyList()),
                BookshelfShelfEntryUiModel.Book(item("u1")),
            ),
            result,
        )
    }

    @Test
    fun `封面引用按组装配并截断至七`() {
        val groups = listOf(group(0b1, order = 0))
        val covers = (1..9).map { BookshelfCoverRefUiModel("c$it", "书$it", null, null) }
        val result = buildShelfWithGroupsEntries(
            userGroups = groups, userCounts = mapOf(0b1L to 9),
            groupCovers = mapOf(0b1L to covers),
            ungroupedBooks = emptyList(),
            nameOf = nameOf, bookMapper = mapper,
        )
        val entry = result.single() as BookshelfShelfEntryUiModel.Group
        assertEquals(7, entry.covers.size)
        assertEquals("c1", entry.covers.first().url)
    }

    // ---- 管理/筛选守卫 ----

    @Test
    fun `系统组管理操作与非精选筛选值抛 IAE`() = runTest {
        // 守卫先于 Koin 注入/DB 访问抛出（object 惰性注入不触发），Robolectric
        // 上下文兜底 appCtx 名称解析
        assertIae { BookshelfGroupEngineImpl.setSelectedGroup(BookGroup.IdReading) }
        assertIae { BookshelfGroupEngineImpl.renameGroup(BookGroup.IdAll, "x") }
        assertIae { BookshelfGroupEngineImpl.dissolveGroup(BookGroup.IdAll) }
        assertIae { BookshelfGroupEngineImpl.setBookGroupMembership("url", BookGroup.IdAll, true) }
        assertIae { BookshelfGroupEngineImpl.setSelectedFilter(BookGroup.IdRoot) } // IdRoot 非筛选器
    }

    /** assertThrows 的 Executable 非 inline，suspend 调用经本 suspend 夹具收敛。 */
    private suspend fun assertIae(block: suspend () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("期望 IllegalArgumentException")
    }

    // ---- 纯函数补充夹具 ----

    @Test
    fun `hideEmpty 开启时无筛选常驻且其余筛选器滤空`() {
        val result = buildFilterUiModels(
            visibleSystemRows = listOf(group(BookGroup.IdAll), group(BookGroup.IdUnread)),
            systemCounts = emptyMap(),
            hideEmpty = true, noneName = "无筛选", nameOf = { "名$it" },
        )
        assertEquals(listOf(BookshelfGroupUiModel(BookshelfGroupIds.NONE, "无筛选", 0)), result)
    }

    @Test
    fun `无筛选且 hideEmpty 关闭时空组条目保留`() {
        val result = buildShelfWithGroupsEntries(
            userGroups = listOf(group(0b1)),
            userCounts = mapOf(0b1L to 0),
            groupCovers = emptyMap(),
            ungroupedBooks = emptyList(),
            nameOf = nameOf, bookMapper = mapper,
        )
        assertEquals(listOf(BookshelfShelfEntryUiModel.Group(0b1, "组1", 0, emptyList())), result)
    }

    // ---- buildGroupAdjustmentUpdate（applyGroupAdjustments 的织入重排+显隐） ----

    @Test
    fun `织入重排保持系统行位置且用户行按提交序连续编号并落显隐`() {
        val all = listOf(
            group(-30L, "在读", order = 0), group(-1L, "全部", order = 1),
            group(0b1, "组1", order = 2), group(0b10, "组2", order = 3), group(0b100, "组3", order = 4),
        )
        val result = weaveClassAdjustmentUpdate(all, listOf(0b100L, 0b1L, 0b10L), setOf(0b1L)) { it.groupId > 0 }
        assertEquals(
            listOf(-30L, -1L, 0b100L, 0b1L, 0b10L),
            result.map { it.groupId },
        )
        assertEquals(List(5) { it }, result.map { it.order })  // order = 全表 index
        // 用户行 show 取显隐集（组3/组2 隐藏、组1 显示），系统行 show 不动（默认 true）
        assertEquals(
            listOf(true, true, false, true, false),
            result.map { it.show },
        )
    }

    @Test
    fun `筛选器织入重排用户行原位且系统行落显隐`() {
        val all = listOf(
            group(-1L, "全部", order = 0), group(0b1, "组1", order = 1), group(-20L, "在读", order = 2),
        )
        val result = weaveClassAdjustmentUpdate(all, listOf(-20L, -1L), setOf(-1L)) {
            it.groupId < 0 && it.groupId != -100L
        }
        assertEquals(listOf(-20L, 0b1L, -1L), result.map { it.groupId })  // 用户行原位
        assertEquals(listOf(false, true, true), result.map { it.show })   // 在读隐藏、用户行 show 不动
    }

    // ---- validateClassSubmission（applyGroupAdjustments 的集合一致性校验） ----

    @Test
    fun `提交集合与当前用户组不一致时校验拒绝`() {
        val all = listOf(group(-1L, "全部"), group(0b1), group(0b10))
        assertFalse(validateClassSubmission(all, listOf(0b1L)) { it.groupId > 0 })          // 元素缺失
        assertFalse(validateClassSubmission(all, listOf(0b1L, 0b100L)) { it.groupId > 0 }) // 含未知组 id
        assertTrue(validateClassSubmission(all, listOf(0b10L, 0b1L)) { it.groupId > 0 })    // 同元素集不同序
    }

    // ---- folderVisibleGroups / rangeVisibleGroups（私密组过滤） ----

    @Test
    fun `文件夹可见组过滤隐藏组与私密组`() {
        val groups = listOf(
            group(0b1),
            group(0b10).copy(show = false),
            group(0b100).copy(isPrivate = true),
            group(0b1000).copy(show = false, isPrivate = true),
            group(BookGroup.IdAll),
            group(BookGroup.IdRoot),
        )
        assertEquals(listOf(0b1L), folderVisibleGroups(groups).map { it.groupId })
    }

    @Test
    fun `范围可见组过滤私密组但保留隐藏组`() {
        val groups = listOf(
            group(0b1),
            group(0b10).copy(show = false),
            group(0b100).copy(isPrivate = true),
            group(BookGroup.IdReading),
        )
        assertEquals(listOf(0b1L, 0b10L), rangeVisibleGroups(groups).map { it.groupId })
    }
}
