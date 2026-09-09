# E-Ink 书架属性配置传递 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 宿主书架设置（`BookshelfSettings` 48 键）经策划快照 `BookshelfStyle` 进入 E-Ink 书架：7 键读取生效（排序、未读角标双键、最新章节行、布局模式、网格列数），其余主动忽略或随功能面缺失不投影。

**Architecture:** 契约新增 `BookshelfStyle`（5 字段）与 `BookshelfEngine.style: Flow<BookshelfStyle>`；宿主 bridge 把 `BookshelfSettingsGateway.settings` 投影为快照流、并在 `observeShelf` 内消化排序（`combine` 设置流 → 排序 → 映射 UiModel）。模块侧 VM 并入 UiState，书架 Screen 消费角标开关/最新章节行/网格列数；格宽由 Route 级单点解析，显示与预取共用同一 Dp 保证封面缓存键一致。

**Tech Stack:** Kotlin、Jetpack Compose、Kotlinx Coroutines Flow、JUnit4、MockK（仅既有桩）、Gradle（`:app`、`:modules:eink`）。

**设计文档:** `docs/superpowers/specs/2026-09-09-eink-bookshelf-settings-passing-design.md`（判定依据与逐键明细，实施时遇到取舍以它为准）。

## Global Constraints

- 开发与 CI 使用 JDK 21；验证命令用 `.\gradlew.bat`（Windows）。
- `modules/eink` 零宿主类型依赖：契约只收基元/模块类型，宿主映射在 `:app` `eink/bridge/` 完成（`BookshelfItemUiModel` KDoc 固化的「宿主构造义务、模块零计算」纪律）。
- 模块书架禁自由滚动：不引入 `verticalScroll`/`userScrollEnabled = true`，分页模型（`EInkGridPagerState` 首次布局实测页项数）不动。
- E-Ink 零动画零阴影：不新增动画、阴影、渐变。
- 布局切换入口不开放、不反向写宿主设置（设计决策 3）；`bookshelfRefreshingLimit` 不消费，刷新保持全量（设计决策 1）。
- 契约数据类字段全成员 KDoc（宿主键对应关系写进注释）；默认值按模块惯例直接落在字段默认参数（对齐 `ReaderTextStyle`）。
- 文本改动至少 `git diff --check`。
- 模块单测无 Robolectric：可测面 = 纯函数与状态流；`AndroidViewModel`/Composable 不直测（由编译 + 纯函数抽取 + 真机手工回归覆盖，见 Task 6）。

---

### Task 1: 契约 `BookshelfStyle` + `BookshelfEngine.style`

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfStyle.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfEngine.kt`（接口加成员）
- Modify: `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/CacheBookPumpTest.kt`（FakeEngine 补实现）
- Test: `modules/eink/src/test/java/io/legado/app/eink/contract/BookshelfStyleTest.kt`

**Interfaces:**
- Consumes: 无（新类型）。
- Produces: `BookshelfStyle(showUnreadBadge: Boolean = true, highlightNewChapter: Boolean = true, showLatestChapter: Boolean = true, isGridLayout: Boolean = true, gridColumns: Int = 3)`（`@EInkImmutable` data class）；`BookshelfEngine.style: Flow<BookshelfStyle>`。后续任务按这些名字消费。

- [ ] **Step 1: 写失败测试（默认值与宿主默认设置对齐）**

创建 `modules/eink/src/test/java/io/legado/app/eink/contract/BookshelfStyleTest.kt`：

```kotlin
package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照默认值必须与宿主 BookshelfSettings 的字段默认值语义一致
 * （showUnread=true、showUnreadNew=true、bookshelfShowLatestChapter=true、
 * bookshelfLayoutModePortrait=1 网格、bookshelfLayoutGridPortrait=3），
 * 同时等于当前 eink 书架的硬编码行为——插件宿主发射默认快照即现状。
 */
class BookshelfStyleTest {

    @Test
    fun `默认值与宿主书架设置默认值对齐`() {
        val style = BookshelfStyle()
        assertTrue(style.showUnreadBadge)
        assertTrue(style.highlightNewChapter)
        assertTrue(style.showLatestChapter)
        assertTrue(style.isGridLayout)
        assertEquals(3, style.gridColumns)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.BookshelfStyleTest"`
Expected: 编译失败 `Unresolved reference: BookshelfStyle`

- [ ] **Step 3: 创建契约类型**

创建 `modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfStyle.kt`：

```kotlin
package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书架显示样式快照：宿主书架设置中对 E-Ink 生效的策划子集投影。
 *
 * 判定全集（48 键逐键依据）见
 * `docs/superpowers/specs/2026-09-09-eink-bookshelf-settings-passing-design.md`；
 * 未投影的宿主键为主动忽略或功能面缺失，不进本快照。
 *
 * 映射纪律（宿主构造义务）：
 *  - 字段按模块语义命名，不照搬宿主键名；宿主键到字段的对应关系见各成员
 *    KDoc，宿主实现不得扩大或收窄语义；
 *  - [gridColumns] 的非法值钳制在本层完成，模块收到的值恒可用；
 *  - 快照为实时档：宿主设置变化后经 Flow 发射新值，模块组合期订阅，
 *    立即重组（与 [GlobalSettings.useDefaultCover] 的快照状态档同语义）。
 *
 * 双宿主归宿：嵌入式宿主投影 `BookshelfSettingsGateway`；插件宿主无宿主
 * 书架设置可投影，发射本默认值的静态快照即合法实现（不是功能缺失，
 * 插件形态本就不承接宿主设置，见插件计划 §6.4 配置分叉）。
 */
@EInkImmutable
data class BookshelfStyle(
    /**
     * 是否显示未读章节数角标（宿主 `showUnread`）。
     *
     * false 时网格与列表条目均不渲染未读角标；刷新中的「…」角标是刷新态
     * 表达，不受本字段影响。
     */
    val showUnreadBadge: Boolean = true,

    /**
     * 本次目录刷新发现新章时角标是否反色高亮（宿主 `showUnreadNew`）。
     *
     * 模块侧组合规则与 View 版一致：高亮 = [showUnreadBadge] && 本字段 &&
     * hasNewChapter；未读角标整体隐藏时高亮随之无载体。
     */
    val highlightNewChapter: Boolean = true,

    /**
     * 列表条目是否显示最新章节行（宿主 `bookshelfShowLatestChapter`）。
     *
     * false 时列表条目隐藏该行，剩余信息行按既有 SpaceBetween 结构重排；
     * 网格条目本无该行，不受影响。
     */
    val showLatestChapter: Boolean = true,

    /**
     * 书架默认布局：true = 网格，false = 列表（宿主 `bookshelfLayoutModePortrait`，
     * 0 = 列表、非 0 = 网格）。
     *
     * 只读投影：E-Ink 不开放布局切换入口，也不反向写宿主设置（横屏变体
     * 不投影，E-Ink 按竖屏形态设计）。
     */
    val isGridLayout: Boolean = true,

    /**
     * 网格列数（宿主 `bookshelfLayoutGridPortrait`，默认 3）。
     *
     * 网格以 Fixed 列数渲染，格宽按可用宽度均分自适应（封面保持 66:90
     * 比例随格宽伸缩）。宿主映射义务：值 <= 0 时回落 3，不做其他钳制。
     * 仅 [isGridLayout] = true 时消费。
     */
    val gridColumns: Int = 3,
)
```

- [ ] **Step 4: `BookshelfEngine` 加成员**

在 `modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfEngine.kt` 的
`interface BookshelfEngine` 内（`observeShelf()` 之前）加入：

```kotlin
    /**
     * 书架显示样式快照流（宿主书架设置的策划子集投影，实时档；成员语义
     * 见 [BookshelfStyle]）。模块在 VM 层并入 UiState，与 [observeShelf]
     * 的书籍流独立发射：样式变化只重组渲染参数，排序变化才重发列表。
     */
    val style: Flow<BookshelfStyle>
```

- [ ] **Step 5: 补齐测试桩**

`CacheBookPumpTest.kt` 的 `private class FakeEngine : BookshelfEngine` 内加（并补
import `io.legado.app.eink.contract.BookshelfStyle`；`EInkEngineRegistryTest` 用
mockk `stub<BookshelfEngine>()`，自动覆盖新成员，无需改）：

```kotlin
    override val style: Flow<BookshelfStyle> = emptyFlow()
```

- [ ] **Step 6: 运行确认通过**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（含既有全部模块单测）

- [ ] **Step 7: 提交**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfStyle.kt modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfEngine.kt modules/eink/src/test/java/io/legado/app/eink/contract/BookshelfStyleTest.kt modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/CacheBookPumpTest.kt
git commit -m "feat(eink): 书架样式快照契约 BookshelfStyle 与引擎 style 流"
```

---

### Task 2: 宿主投影映射 `toBookshelfStyle`

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/BookshelfStyleMapper.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/BookshelfEngineImpl.kt`（注入 Gateway + 实现 `style`）
- Test: `app/src/test/java/io/legado/app/eink/bridge/BookshelfStyleMapperTest.kt`

**Interfaces:**
- Consumes: `BookshelfStyle`（Task 1）；`BookshelfSettingsGateway.settings: Flow<BookshelfSettings>`（宿主既有接口，`domain/gateway/BookshelfSettingsGateway.kt`）；`BookshelfSettings` 48 键字段名。
- Produces: `internal fun BookshelfSettings.toBookshelfStyle(): BookshelfStyle`（Task 2 自身消费）；`BookshelfEngineImpl.style: Flow<BookshelfStyle>`（模块经端口消费）。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/io/legado/app/eink/bridge/BookshelfStyleMapperTest.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.eink.contract.BookshelfStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BookshelfSettings → BookshelfStyle 策划投影逐字段验证（设计 §4/§5）：
 * 投影键的对应关系、布局模式 0/非 0 语义、gridColumns 非法值钳制。
 */
class BookshelfStyleMapperTest {

    @Test
    fun `各字段按宿主键投影`() {
        val style = BookshelfSettings(
            showUnread = true,
            showUnreadNew = false,
            bookshelfShowLatestChapter = false,
            bookshelfLayoutModePortrait = 0,
            bookshelfLayoutGridPortrait = 5,
        ).toBookshelfStyle()

        assertTrue(style.showUnreadBadge)
        assertFalse(style.highlightNewChapter)
        assertFalse(style.showLatestChapter)
        assertFalse(style.isGridLayout)
        assertEquals(5, style.gridColumns)
    }

    @Test
    fun `布局模式非 0 为网格`() {
        val style = BookshelfSettings(bookshelfLayoutModePortrait = 1).toBookshelfStyle()
        assertTrue(style.isGridLayout)
    }

    @Test
    fun `网格列数非正回落 3`() {
        assertEquals(3, BookshelfSettings(bookshelfLayoutGridPortrait = 0).toBookshelfStyle().gridColumns)
        assertEquals(3, BookshelfSettings(bookshelfLayoutGridPortrait = -2).toBookshelfStyle().gridColumns)
    }

    @Test
    fun `默认设置投影等于契约默认快照`() {
        val style = BookshelfSettings().toBookshelfStyle()
        assertEquals(BookshelfStyle(), style)
    }
}
```

注意：`BookshelfStyle` import 为 `io.legado.app.eink.contract.BookshelfStyle`（测试文件头部补）。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfStyleMapperTest"`
Expected: 编译失败 `Unresolved reference: toBookshelfStyle`

- [ ] **Step 3: 实现映射函数**

创建 `app/src/main/java/io/legado/app/eink/bridge/BookshelfStyleMapper.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.eink.contract.BookshelfStyle

/**
 * BookshelfSettings → BookshelfStyle 策划投影（唯一映射点，设计 §4）。
 *
 * 读取生效 7 键中的 5 个显示键在此投影（排序双键在 [BookshelfSorter]
 * 于 observeShelf 内消化，不进契约）。其余宿主键全部为主动忽略或功能面
 * 缺失（设计 §5 逐键依据）：契约不扩字段即不生效，后续新键按设计 §6
 * 演进规则走「契约字段 + 本函数映射 + 模块消费」三处一次提交。
 */
internal fun BookshelfSettings.toBookshelfStyle(): BookshelfStyle = BookshelfStyle(
    showUnreadBadge = showUnread,
    highlightNewChapter = showUnreadNew,
    showLatestChapter = bookshelfShowLatestChapter,
    isGridLayout = bookshelfLayoutModePortrait != 0,
    gridColumns = if (bookshelfLayoutGridPortrait <= 0) 3 else bookshelfLayoutGridPortrait,
)
```

- [ ] **Step 4: `BookshelfEngineImpl` 接线**

修改 `app/src/main/java/io/legado/app/eink/bridge/BookshelfEngineImpl.kt`：

4a. 注入 Gateway（`downloadCacheSettingsGateway` 声明之后）：

```kotlin
    private val bookshelfSettingsGateway: BookshelfSettingsGateway by inject()
```

4b. 实现 `style`（`observeShelf()` 之后；`updatableBooks` 与展示序无关，不加排序）：

```kotlin
    override val style: Flow<BookshelfStyle> =
        bookshelfSettingsGateway.settings
            .map { it.toBookshelfStyle() }
            .distinctUntilChanged()
```

4c. 补 import：`io.legado.app.domain.gateway.BookshelfSettingsGateway`、
`io.legado.app.eink.contract.BookshelfStyle`、`kotlinx.coroutines.flow.distinctUntilChanged`
（`map` 已有）。

- [ ] **Step 5: 运行确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.*"`
Expected: PASS（该包既有测试一并跑通）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/BookshelfStyleMapper.kt app/src/main/java/io/legado/app/eink/bridge/BookshelfEngineImpl.kt app/src/test/java/io/legado/app/eink/bridge/BookshelfStyleMapperTest.kt
git commit -m "feat(eink): 宿主书架设置策划投影 toBookshelfStyle 与 style 流实现"
```

---

### Task 3: 宿主排序消化（observeShelf）

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/BookshelfSorter.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/BookshelfEngineImpl.kt:57-59`（`observeShelf` 改 combine 排序）
- Test: `app/src/test/java/io/legado/app/eink/bridge/BookshelfSorterTest.kt`

**Interfaces:**
- Consumes: `BookshelfSettingsGateway.settings`（Task 2 已注入）；`String.cnCompare`（`io.legado.app.utils.StringExtensions.kt:98`）；`Book` 实体字段 `name/author/durChapterTime: Long/latestChapterTime: Long/order: Int`。
- Produces: `internal fun List<Book>.sortedForBookshelf(sort: Int, sortOrder: Int): List<Book>`。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/io/legado/app/eink/bridge/BookshelfSorterTest.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.data.entities.Book
import io.legado.app.utils.cnCompare
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 六模式 × 升降序对拍 View 版 BookshelfRepository.sortBooks 语义
 * （设计 §3.2）：手动排序显式按 order（不依赖 DAO 自然序）、
 * 未知 sort 回落阅读时间、升降序翻转。
 */
class BookshelfSorterTest {

    private fun book(
        name: String = "",
        author: String = "",
        dur: Long = 0,
        latest: Long = 0,
        order: Int = 0,
    ) = Book(
        name = name,
        author = author,
        durChapterTime = dur,
        latestChapterTime = latest,
        order = order,
    )

    private fun urls(books: List<Book>) = books.map { it.name }

    @Test
    fun `模式 0 与未知值按阅读时间排序`() {
        val books = listOf(book(name = "a", dur = 1), book(name = "b", dur = 3), book(name = "c", dur = 2))
        assertEquals(listOf("a", "c", "b"), urls(books.sortedForBookshelf(0, 0)))
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(0, 1)))
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(9, 1)))
    }

    @Test
    fun `模式 1 按更新时间排序`() {
        val books = listOf(book(name = "a", latest = 1), book(name = "b", latest = 3), book(name = "c", latest = 2))
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(1, 1)))
    }

    @Test
    fun `模式 2 按书名 cnCompare 排序`() {
        val books = listOf(book(name = "B"), book(name = "A"), book(name = "C"))
        assertEquals(listOf("A", "B", "C"), urls(books.sortedForBookshelf(2, 0)))
        assertEquals(listOf("C", "B", "A"), urls(books.sortedForBookshelf(2, 1)))
    }

    @Test
    fun `模式 3 手动排序显式按 order`() {
        // DAO flowAll 自然序是 durChapterTime desc；dur 与 order 交叉
        // 证明排序跟随 order 而非自然序
        val books = listOf(
            book(name = "a", dur = 30, order = 2),
            book(name = "b", dur = 20, order = 0),
            book(name = "c", dur = 10, order = 1),
        )
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(3, 0)))
        assertEquals(listOf("a", "c", "b"), urls(books.sortedForBookshelf(3, 1)))
    }

    @Test
    fun `模式 4 按更新与阅读时间的较大值排序`() {
        val books = listOf(
            book(name = "a", dur = 5, latest = 1),
            book(name = "b", dur = 1, latest = 9),
            book(name = "c", dur = 3, latest = 3),
        )
        assertEquals(listOf("b", "c", "a"), urls(books.sortedForBookshelf(4, 1)))
    }

    @Test
    fun `模式 5 按作者 cnCompare 排序`() {
        val books = listOf(book(name = "1", author = "B"), book(name = "2", author = "A"))
        assertEquals(listOf("2", "1"), urls(books.sortedForBookshelf(5, 0)))
    }

    @Test
    fun `书名比较器与 cnCompare 同源`() {
        // 中文书名在前（collator 语义由宿主 cnCompare 定义，此处锁定装配一致）
        val books = listOf(book(name = "英文"), book(name = "啊"))
        val expected = books.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
        assertEquals(expected, books.sortedForBookshelf(2, 0))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfSorterTest"`
Expected: 编译失败 `Unresolved reference: sortedForBookshelf`

- [ ] **Step 3: 实现排序函数**

创建 `app/src/main/java/io/legado/app/eink/bridge/BookshelfSorter.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.data.entities.Book
import io.legado.app.utils.cnCompare

/**
 * 书架排序（bridge 内消化，排序键不进契约；设计 §3.2）：语义与 View 版
 * `BookshelfRepository.sortBooks` 一致——0 阅读时间、1 更新时间、2 书名、
 * 3 手动（order 字段）、4 max(更新, 阅读) 时间、5 作者，`sortOrder == 1`
 * 为降序，未知 sort 走 else（阅读时间）。
 *
 * 端口只投影「全部书架」，View 版 per-group bookSort 覆盖不适用。
 * 手动排序必须显式排序：DAO `flowAll()` 自然序是 `durChapterTime desc`，
 * 与手动序无关，不得依赖自然序巧合。cnCompare 为宿主工具，故排序收敛
 * 在 bridge 而非模块（模块零计算纪律）。
 */
internal fun List<Book>.sortedForBookshelf(sort: Int, sortOrder: Int): List<Book> {
    val descending = sortOrder == 1
    return when (sort) {
        1 -> if (descending) sortedByDescending { it.latestChapterTime }
        else sortedBy { it.latestChapterTime }

        2 -> if (descending) sortedWith { a, b -> b.name.cnCompare(a.name) }
        else sortedWith { a, b -> a.name.cnCompare(b.name) }

        3 -> if (descending) sortedByDescending { it.order }
        else sortedBy { it.order }

        4 -> if (descending) sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
        else sortedBy { maxOf(it.latestChapterTime, it.durChapterTime) }

        5 -> if (descending) sortedWith { a, b -> b.author.cnCompare(a.author) }
        else sortedWith { a, b -> a.author.cnCompare(b.author) }

        else -> if (descending) sortedByDescending { it.durChapterTime }
        else sortedBy { it.durChapterTime }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfSorterTest"`
Expected: PASS

- [ ] **Step 5: `observeShelf` 接入排序**

修改 `BookshelfEngineImpl.kt` 的 `observeShelf()`（现为
`appDb.bookDao.flowByGroup(BookGroup.IdAll).map { books -> books.map { it.toBookshelfItemUiModel() } }`）：

```kotlin
    override fun observeShelf(): Flow<List<BookshelfItemUiModel>> =
        combine(
            appDb.bookDao.flowByGroup(BookGroup.IdAll),
            bookshelfSettingsGateway.settings
                .map { BookshelfSortKey(it.bookshelfSort, it.bookshelfSortOrder) }
                .distinctUntilChanged(),
        ) { books, sortKey ->
            books.sortedForBookshelf(sortKey.sort, sortKey.sortOrder)
        }.map { books -> books.map { it.toBookshelfItemUiModel() } }
```

同文件私有数据类（`BookshelfEngineImpl` object 体内末尾）：

```kotlin
    /** 排序键投影：设置流任意键变化不触发书架重排，仅排序键变化才重发。 */
    private data class BookshelfSortKey(val sort: Int, val sortOrder: Int)
```

补 import：`kotlinx.coroutines.flow.combine`。

- [ ] **Step 6: 编译与测试**

Run: `.\gradlew.bat :app:compileAppDebugKotlin :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.*"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/BookshelfSorter.kt app/src/main/java/io/legado/app/eink/bridge/BookshelfEngineImpl.kt app/src/test/java/io/legado/app/eink/bridge/BookshelfSorterTest.kt
git commit -m "feat(eink): 书架流消化宿主排序键，六模式升降序对拍 View 版"
```

---

### Task 4: 模块消费——角标/最新章节行 + UiState 接 style

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt`（角标纯函数 + 两类条目消费 + 最新章节行开关）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt`（UiState 加 style、combine 5 流、布局初始化读快照）
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfBadgeRuleTest.kt`

**Interfaces:**
- Consumes: `BookshelfStyle`（Task 1）；`BookshelfEngine.style`（Task 2 起宿主提供）。
- Produces: `internal fun shelfBadgeText(isUpdating: Boolean, showUnreadBadge: Boolean, unreadCount: Int): String?`；`internal fun shelfBadgeHighlight(isUpdating: Boolean, showUnreadBadge: Boolean, highlightNewChapter: Boolean, hasNewChapter: Boolean): Boolean`；`BookshelfUiState.style: BookshelfStyle`（Task 5 消费）。

- [ ] **Step 1: 写失败测试（角标组合规则）**

创建 `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfBadgeRuleTest.kt`：

```kotlin
package io.legado.app.eink.feature.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角标组合规则（语义对齐 View 版 BookItem：unreadText 受 showUnread 门控、
 * showUpdateBadge = showUnread && showUnreadNew && isNew）。
 */
class BookshelfBadgeRuleTest {

    @Test
    fun `刷新中显示省略号且优先于未读数`() {
        assertEquals("…", shelfBadgeText(isUpdating = true, showUnreadBadge = true, unreadCount = 5))
        assertEquals("…", shelfBadgeText(isUpdating = true, showUnreadBadge = false, unreadCount = 5))
    }

    @Test
    fun `未读角标受宿主开关门控`() {
        assertEquals("5", shelfBadgeText(false, true, 5))
        assertNull(shelfBadgeText(false, false, 5))
    }

    @Test
    fun `未读为 0 不显示角标`() {
        assertNull(shelfBadgeText(false, true, 0))
    }

    @Test
    fun `高亮需角标可见且开关开启且发现新章`() {
        assertTrue(shelfBadgeHighlight(false, true, true, true))
        assertFalse(shelfBadgeHighlight(false, true, false, true))
        assertFalse(shelfBadgeHighlight(false, false, true, true))
        assertFalse(shelfBadgeHighlight(false, true, true, false))
        assertFalse(shelfBadgeHighlight(true, true, true, true))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.bookshelf.BookshelfBadgeRuleTest"`
Expected: 编译失败 `Unresolved reference: shelfBadgeText`

- [ ] **Step 3: 实现纯函数并接入两类条目**

3a. `BookshelfScreen.kt` 文件级（`ShelfBadge` 之前）加：

```kotlin
/**
 * 书架角标文本（网格与列表共用）：刷新中显示省略号（优先，E-Ink 禁止
 * 加载动画的刷新态表达）；否则未读角标受宿主开关 [showUnreadBadge]
 * 门控、未读数大于 0 才显示。
 */
internal fun shelfBadgeText(
    isUpdating: Boolean,
    showUnreadBadge: Boolean,
    unreadCount: Int,
): String? = when {
    isUpdating -> "…"
    showUnreadBadge && unreadCount > 0 -> unreadCount.toString()
    else -> null
}

/**
 * 角标反色高亮：本次刷新发现新章（语义对齐 View 版
 * showUpdateBadge = showUnread && showUnreadNew && isNew）。刷新中不高亮，
 * 角标整体隐藏（未读开关关闭）时高亮无载体。
 */
internal fun shelfBadgeHighlight(
    isUpdating: Boolean,
    showUnreadBadge: Boolean,
    highlightNewChapter: Boolean,
    hasNewChapter: Boolean,
): Boolean = !isUpdating && showUnreadBadge && highlightNewChapter && hasNewChapter
```

3b. `BookGridItem` 加 `style: BookshelfStyle` 参数（`book` 之后）；角标块替换为：

```kotlin
            val badgeText = shelfBadgeText(isUpdating, style.showUnreadBadge, unreadCount)
            if (badgeText != null) {
                ShelfBadge(
                    text = badgeText,
                    highlight = shelfBadgeHighlight(
                        isUpdating,
                        style.showUnreadBadge,
                        style.highlightNewChapter,
                        book.hasNewChapter
                    ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(EInkSpacing.xxs)
                )
            }
```

3c. `BookListItem` 加 `style: BookshelfStyle` 参数（`book` 之后）；标题行角标
`when { ... }` 块替换为：

```kotlin
                shelfBadgeText(isUpdating, style.showUnreadBadge, unreadCount)?.let { text ->
                    ShelfBadge(
                        text = text,
                        highlight = shelfBadgeHighlight(
                            isUpdating,
                            style.showUnreadBadge,
                            style.highlightNewChapter,
                            book.hasNewChapter
                        ),
                        modifier = Modifier.padding(start = EInkSpacing.xs)
                    )
                }
```

3d. `BookListItem` 最新章节行（现为无条件 `book.latestChapterTitle?.let { ... }`）
加开关门控：

```kotlin
            // 最新章节（同 View 版 iv_last / ic_book_last；宿主开关门控）
            if (style.showLatestChapter) {
                book.latestChapterTitle?.let { title ->
                    EInkInfoRow(
                        iconRes = R.drawable.eink_ic_book_last,
                        text = title,
                        style = EInkTheme.typography.labelMedium
                    )
                }
            }
```

3e. `BookshelfScreen` / `BookGrid` / `BookList` 逐层透传（新签名，参数顺序
沿用既有惯例插在 `updatingBookUrls` 之后）：

```kotlin
private fun BookList(
    books: List<BookshelfItemUiModel>,
    updatingBookUrls: Set<String>,
    style: BookshelfStyle,
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookshelfItemUiModel) -> Unit,
    listState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit
)

private fun BookGrid(
    books: List<BookshelfItemUiModel>,
    updatingBookUrls: Set<String>,
    style: BookshelfStyle,
    onBookClick: (String) -> Unit,
    onBookLongClick: (BookshelfItemUiModel) -> Unit,
    gridState: LazyGridState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit
)
```

`BookshelfScreen` 的两个分支调用点（`BookGrid(...)` / `BookList(...)`）补
`style = state.style`。

- [ ] **Step 4: UiState 与 ViewModel 接线**

`BookshelfViewModel.kt`：

4a. `BookshelfUiState` 末尾加字段：

```kotlin
    /** 书架显示样式快照（宿主设置投影，实时档）。 */
    val style: BookshelfStyle = BookshelfStyle(),
```

4b. `uiState` 的 `combine` 由 4 流改 5 流（`engine.style` 插在 `observeShelf()` 之后）：

```kotlin
    val uiState: StateFlow<BookshelfUiState> =
        combine(
            engine.observeShelf(),
            engine.style,
            _isRefreshing,
            _updatingUrls,
            _isGridLayout
        ) { books, style, refreshing, updatingUrls, isGridLayout ->
            BookshelfUiState(
                books = books,
                isLoading = false,
                isRefreshing = refreshing,
                updatingBookUrls = updatingUrls,
                isGridLayout = isGridLayout,
                style = style,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfUiState())
```

4c. `init` 中（`deleteBooksNotInBookshelf` 启动之后）加布局初始化：

```kotlin
        // 布局默认值读宿主快照（首次发射，处于加载态期间，无可见切换）。
        // toggleGridLayout 仍为内存态覆盖，入口未开放、不接共享存储
        viewModelScope.launch {
            _isGridLayout.value = engine.style.first().isGridLayout
        }
```

补 import：`io.legado.app.eink.contract.BookshelfStyle`、`kotlinx.coroutines.flow.first`。

- [ ] **Step 5: 运行确认通过 + 编译**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL（`:app` 编译需宿主已实现 `style`——Task 2 完成）

- [ ] **Step 6: 提交**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfBadgeRuleTest.kt
git commit -m "feat(eink): 书架消费样式快照——未读角标/最新章节行/布局默认值"
```

---

### Task 5: 网格列数宿主驱动 + 格宽自适应 + 预取同源

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt`（格宽纯函数、Fixed 列数、条目封面尺寸、删 96dp 门槛常量）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt`（BoxWithConstraints 单点解析格宽、预取改用同源 Dp、清理失效 import）
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfGridCellWidthTest.kt`

**Interfaces:**
- Consumes: `BookshelfUiState.style`（Task 4）、`gridColumns`（Task 1）、`coverTargetSizePx(width: Dp, height: Dp, density: Density): Pair<Int, Int>`（`feature/common/EInkBookCover.kt` 既有）。
- Produces: `internal fun bookshelfGridCellWidth(availableWidth: Dp, columns: Int): Dp`；`BookshelfScreen(state, gridCellWidth: Dp, ...)` 新签名（HomeRoute 是唯一调用方）。

- [ ] **Step 1: 写失败测试**

创建 `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfGridCellWidthTest.kt`：

```kotlin
package io.legado.app.eink.feature.bookshelf

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格宽公式：可用宽扣除左右内容边距（16dp × 2）与列间距（16dp × 列数-1）
 * 后均分。360dp 屏 3 列 = (360 - 32 - 32) / 3 ≈ 98.67dp。
 */
class BookshelfGridCellWidthTest {

    @Test
    fun `360dp 三列格宽约 98_67dp`() {
        assertEquals(98.67f, bookshelfGridCellWidth(360.dp, 3).value, 0.01f)
    }

    @Test
    fun `列数小于 1 钳制为单列`() {
        assertEquals(328f, bookshelfGridCellWidth(360.dp, 0).value, 0.01f)
        assertEquals(328f, bookshelfGridCellWidth(360.dp, -1).value, 0.01f)
    }

    @Test
    fun `列数越多格宽越窄`() {
        val widths = (2..6).map { bookshelfGridCellWidth(600.dp, it).value }
        assertEquals(widths, widths.sortedDescending())
        assertTrue(widths.zipWithNext().all { (a, b) -> a > b })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.bookshelf.BookshelfGridCellWidthTest"`
Expected: 编译失败 `Unresolved reference: bookshelfGridCellWidth`

- [ ] **Step 3: 实现格宽函数并改造网格渲染**

3a. `BookshelfScreen.kt`：删除 `internal val EInkBookshelfGridMinCellWidth` 与
`internal val EInkGridCoverHeight` 两个常量及其 KDoc（96dp 门槛与 Adaptive
推导整体退役），原位替换为：

```kotlin
/**
 * 网格格宽：可用宽扣除左右内容边距（各 [EInkSpacing.m]）与列间距
 * （[EInkSpacing.m] ×（列数 − 1））后均分，列数来自宿主样式快照
 * （BookshelfStyle.gridColumns）。与 LazyVerticalGrid 的
 * GridCells.Fixed 同一约束解（同 contentPadding/Arrangement），显示与
 * 预取必须共用本函数（封面缓存键逐字节一致，见 coverTargetSizePx KDoc）。
 */
internal fun bookshelfGridCellWidth(availableWidth: Dp, columns: Int): Dp {
    val columns = columns.coerceAtLeast(1)
    return (availableWidth - EInkSpacing.m * 2 - EInkSpacing.m * (columns - 1)) / columns
}
```

补 import：`androidx.compose.ui.unit.Dp`（`dp` 已有）。

3b. `BookshelfScreen` 签名加 `gridCellWidth: Dp`（`state` 之后），
KDoc 中「列数按屏宽自适应（[EInkBookshelfGridMinCellWidth]）」改为
「列数来自样式快照 `state.style.gridColumns`（GridCells.Fixed，格宽
[bookshelfGridCellWidth] 均分）」；透传给 `BookGrid`。

3c. `BookGrid`：加 `style: BookshelfStyle` 与 `gridCellWidth: Dp` 参数；
`columns = GridCells.Adaptive(EInkBookshelfGridMinCellWidth)` 改为：

```kotlin
        columns = GridCells.Fixed(style.gridColumns.coerceAtLeast(1)),
```

`BookGridItem(...)` 调用补 `style = style, gridCellWidth = gridCellWidth`。

3d. `BookGridItem`：加 `style` 已在 Task 4 传入，此处再加
`gridCellWidth: Dp` 参数；封面尺寸改为动态推导（KDoc 中「解码尺寸用
门槛宽推导」一段同步改写为格宽公式来源）：

```kotlin
    val gridCoverHeight = gridCellWidth * (EInkCoverHeight / EInkCoverWidth)
```

```kotlin
            EInkBookCover(
                url = book.coverUrl,
                name = book.name,
                author = book.displayAuthor,
                sourceOrigin = book.origin,
                modifier = Modifier.fillMaxSize(),
                width = gridCellWidth,
                height = gridCoverHeight
            )
```

（`EInkCoverWidth`/`EInkCoverHeight` import 已有。）

3e. `BookshelfScreen` 中 `state.isGridLayout -> BookGrid(...)` 分支补传
`style = state.style, gridCellWidth = gridCellWidth`。

- [ ] **Step 4: HomeRoute 单点解析与预取同源**

修改 `HomeRoute.kt`：

4a. 删 import：`io.legado.app.eink.feature.bookshelf.EInkBookshelfGridMinCellWidth`、
`io.legado.app.eink.feature.bookshelf.EInkGridCoverHeight`；加 import：
`androidx.compose.foundation.layout.BoxWithConstraints`、
`io.legado.app.eink.feature.bookshelf.bookshelfGridCellWidth`。

4b. `HomeScreen(...)` 调用整体包进 BoxWithConstraints，格宽单点解析
（Route 级一次测量，非逐项；HomeRoute 函数体的 `HomeScreen(...)` 表达式改为）：

```kotlin
    // 格宽单点解析：显示（BookshelfScreen）与预取共用同一 Dp，封面缓存
    // 键逐字节一致（bookshelfGridCellWidth KDoc）。Route 级一次
    // BoxWithConstraints，不做逐项测量
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridCellWidth = bookshelfGridCellWidth(maxWidth, uiState.style.gridColumns)

        // 封面预取：当前页落定后预热下一页封面进内存缓存。加格宽键：
        // 列数/屏宽变化时按新尺寸重新预热
        LaunchedEffect(uiState.books, uiState.isGridLayout, gridCellWidth) {
            val activePager = if (uiState.isGridLayout) gridPager else listPager
            // 与显示严格同源：网格用 bookshelfGridCellWidth 的同一 Dp 值
            //（coverTargetSizePx 单点换算），预取键与显示键逐字节一致
            val (coverWidthPx, coverHeightPx) = if (uiState.isGridLayout) {
                coverTargetSizePx(
                    gridCellWidth,
                    gridCellWidth * (EInkCoverHeight / EInkCoverWidth),
                    prefetchDensity
                )
            } else {
                coverTargetSizePx(EInkCoverWidth, EInkCoverHeight, prefetchDensity)
            }
            snapshotFlow { activePager.pageStart to activePager.pageItemCount }
                .collect { page ->
                    val start = page.first
                    val pageSize = page.second
                    if (pageSize <= 0) return@collect
                    prefetchCovers(
                        context = prefetchContext,
                        items = uiState.books.drop(start + pageSize).take(pageSize),
                        widthPx = coverWidthPx,
                        heightPx = coverHeightPx,
                        coverUrl = { it.coverUrl },
                        sourceOrigin = { it.origin },
                    )
                }
        }

        HomeScreen(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            headerTitle = HomeTabLabels[selectedTab],
            showRefresh = selectedTab == HomeTabs.BOOKSHELF,
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            onSearchClick = onSearch,
            pageArrows = pageArrows,
            bookshelf = {
                BookshelfScreen(
                    state = uiState,
                    gridCellWidth = gridCellWidth,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookLongClick,
                    listState = listPager.listState,
                    gridState = gridPager.gridState,
                    onPageUp = pageUp,
                    onPageDown = pageDown
                )
            },
            mine = {
                MineScreen(
                    pager = minePager,
                    onPageUp = minePageUp,
                    onPageDown = minePageDown,
                    onOpenFontScale = onOpenFontScale,
                    onOpenFullMode = onOpenFullMode,
                    onOpenThemeDebug = onOpenThemeDebug,
                    onOpenComponentGallery = onOpenComponentGallery
                )
            }
        )
    }
```

同时删除原独立成块的预取 `LaunchedEffect(uiState.books, uiState.isGridLayout) { ... }`
（内容已并入上方；`prefetchContext`/`prefetchDensity` 两个 val 声明保留在
Route 作用域原位置）。`gridCellWidth` 的读取使 HomeRoute 整体随列数变化
重组一次，与既有 `uiState.books` 键同级，可接受。

- [ ] **Step 5: 全仓引用核查**

Run: `grep -rn "EInkBookshelfGridMinCellWidth\|EInkGridCoverHeight" modules/ app/`
Expected: 无输出（两个常量及其引用全部清除）

- [ ] **Step 6: 运行确认通过**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfGridCellWidthTest.kt
git commit -m "feat(eink): 书架网格列数随宿主设置，格宽自适应与预取同源"
```

---

### Task 6: 全量验证与交付

**Files:**
- Modify: 无新增（验证 + 交付说明）

**Interfaces:**
- Consumes: Task 1–5 全部产物。
- Produces: 验证记录与手工回归清单。

- [ ] **Step 1: 文本门禁**

Run: `git diff --check`
Expected: 无输出

- [ ] **Step 2: 主验证集**

Run: `.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache`
Expected: BUILD SUCCESSFUL（`verifyConfigArchitecture` 基线棘轮不得放宽；bridge 改动减少 UI→DAO 直连不涉及， Gateway 注入方向正确）

- [ ] **Step 3: 模块全量单测**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 真机手工回归清单（交付说明中如实标注验证状态）**

1. 完整模式书架排序切「按更新时间/书名/手动」→ E-Ink 书架顺序跟随（重点验证手动排序与 DAO 自然序差异）；
2. 完整模式书架设置改网格列数（如 5）→ E-Ink 网格 5 列、格宽自适应、翻页整行对齐；
3. 完整模式关闭「显示未读」→ E-Ink 网格与列表角标消失、刷新中省略号仍在；
4. 完整模式关闭「显示最新章节」→ E-Ink 列表条目三行重排、无残留空行；
5. 完整模式布局切列表 → E-Ink 进入默认列表；E-Ink 内无任何书架设置入口（只读验证）；
6. 封面预取命中：网格翻页无占位帧（缓存键一致性）。

Run: 手工（真机）。未执行则列入交付说明「未验证风险」。

- [ ] **Step 5: 交付说明**

按 AGENTS.md「交付说明」要求输出：修改职责范围、关键设计取舍（排序 bridge 消化、格宽单点解析、96dp 门槛退役）、实际运行的验证与结果、未验证风险（真机清单若未跑）。
