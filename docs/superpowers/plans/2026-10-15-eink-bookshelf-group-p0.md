# 墨水屏书架分组 P0（双入口拆分）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** eink 书架落地「范围 × 筛选」双入口：精选筛选面板（全部/在读/未读，独立策展）+ 用户组聚合条目（点击进组、返回退出、组内无筛选入口、长按重命名/上移/下移/解散），端口 `BookshelfGroupEngine` 一次性重写到位。

**Architecture:** 宿主侧薄桥封装（谓词常量 + 静态组合查询 + 委派既有领域网关），eink-lib 侧契约重写 + ViewModel 双状态（范围/筛选复用 `BookshelfGroupState`）+ 混合条目渲染。位掩码运算封闭在宿主桥内，契约只见语义化操作。

**Tech Stack:** Kotlin、Room（静态 @Query + 字符串模板常量）、Jetpack Compose（eink-lib 自有 DS 组件）、StateFlow/combine、DataStore 设置网关。

**设计规格：** `docs/superpowers/specs/2026-10-14-eink-bookshelf-group-management-design.md`（已定稿）。本计划实现其 §6 P0，P1（长按书菜单 + 归组勾选面板）另行计划。

## Global Constraints

- 两仓库耦合：宿主分支 `eink/port/md3/modules`（D:\Projects\AndroidProjects\EssentialReader-eink-port-md3-modules），eink-lib 子模块目录 `eink-lib/`、分支 `eink/lib`。**提交顺序：先子模块后宿主**；宿主提交带指针推进。
- 会话开始时 `eink-lib` 已有用户未提交改动（`git status` 显示 M）。**每个提交只 `git add` 本任务明确触碰的文件**，不得 `git add -A`；先 `git -C eink-lib status --short` 查看现状，保留用户已有修改。
- 精选筛选器定稿：全部（`BookGroup.IdAll` = -1）、在读（`IdReading` = -20）、未读（`IdUnread` = -21），此顺序（spec D4）。
- 系统组（groupId < 0）只读：管理方法对其调用抛 `IllegalArgumentException`（spec 规则 2）。
- eink 不读写宿主 `show`/`order` 之外的系统组属性；不自动改用户数据。
- E-Ink 约束：静态绘制、零动画零阴影、无拖拽（排序用上移/下移按钮）。
- 命名空间 `io.legado.app`；eink-lib 模块 Gradle 路径 `:modules:eink`（projectDir = `eink-lib/modules/eink`）。
- JDK 21。快速编译验证：`.\gradlew.bat :app:compileAppDebugKotlin`；eink 模块编译任务名未在 CI 文档列出，首次运行前用 `.\gradlew.bat :modules:eink:tasks --all | grep -i "compile.*Kotlin"` 查证真实任务名（预期 `compileDebugKotlin`）。
- 所有文本改动提交前 `git diff --check`。
- 真机（N10Touch）复核在计划外由用户执行；DB 查询行为按仓库惯例不在 JVM 单测范围（纯函数单测 + 真机验证）。

---

### Task 1: 宿主数据层——谓词常量、组合查询、eink 筛选设置键

**Files:**
- Modify: `app/src/main/java/io/legado/app/data/dao/BookDao.kt`（L33 常量区附近 + `flowUnread` L450 / `flowReading` L598 / 计数查询 L893-894 改引常量 + 文件尾部新增 9 条查询）
- Modify: `app/src/main/java/io/legado/app/constant/PreferKey.kt`（L72 `bookshelfSelectedGroupId` 后加一行）
- Modify: `app/src/main/java/io/legado/app/domain/model/settings/BookshelfSettings.kt`（L53 后加字段）
- Modify: `app/src/main/java/io/legado/app/data/repository/BookshelfSettingsRepository.kt`（读映射 L83-85 重构 + 写映射 L136 后加一行）

**Interfaces:**
- Consumes: 无（先行任务）。
- Produces（后续任务依赖的精确签名）:
  - `BookDao.flowUngroupedBooks(): Flow<List<Book>>` / `flowUngroupedUnreadBooks()` / `flowUngroupedReadingBooks()`
  - `BookDao.flowUserGroupUnreadCount(groupId: Long): Flow<Int>` / `flowUserGroupReadingCount(groupId: Long): Flow<Int>`
  - `BookDao.flowUserGroupUnreadBooks(groupId: Long): Flow<List<Book>>` / `flowUserGroupReadingBooks(groupId: Long): Flow<List<Book>>`
  - `BookDao.flowBookGroupMask(bookUrl: String): Flow<Long?>`
  - `BookshelfSettings.einkShelfFilter: Long`（默认 `BookGroup.IdAll`）

- [ ] **Step 1: BookDao 谓词常量 + 既有查询改引常量**

在 `BookDao.kt` 的 `private const val PUBLIC_BOOK_FILTER =`（L33 起）定义之后追加：

```kotlin
/** 智能分组谓词常量：单组查询（flowUnread/flowReading）、系统组计数与 eink 组合查询共用，防两处漂移。 */
internal const val UNREAD_PREDICATE = "durChapterIndex = 0 AND durChapterPos = 0"
internal const val READING_PREDICATE =
    "totalChapterNum > 0 AND durChapterIndex > 0 AND durChapterIndex < totalChapterNum - 1"
```

三处既有查询改为插值（字符串内容零变化，仅收敛来源）：

```kotlin
@Query("""SELECT * FROM books WHERE $UNREAD_PREDICATE""")
fun flowUnread(): Flow<List<Book>>

@Query("""SELECT * FROM books WHERE $READING_PREDICATE""")
fun flowReading(): Flow<List<Book>>
```

计数查询（`flowSystemGroupCounts` 内）对应两行改为：

```kotlin
UNION ALL SELECT ${BookGroup.IdUnread}, COUNT(*) FROM books WHERE $UNREAD_PREDICATE AND $PUBLIC_BOOK_FILTER
UNION ALL SELECT ${BookGroup.IdReading}, COUNT(*) FROM books WHERE $READING_PREDICATE AND $PUBLIC_BOOK_FILTER
```

- [ ] **Step 2: BookDao 新增 9 条查询**

在 `flowUserGroupBookCount`（L900-909）之后追加：

```kotlin
// ---- eink 书架分组（spec 2026-10-14）：组合查询 ----
// 未归组谓词沿用「网络未分组」的 sum 掩码模式，但外包 ifnull：
// 零用户组时 sum 为 NULL，NULL & x = 0 恒假会误空书架（spec D5 退化基线
// 要求无用户组时未归组 = 全量书单）；不带 NetNone 的「非本地非音频」限制

/** 未归组书籍流（不属于任何用户组；eink 顶层文件夹视图）。 */
@Query(
    """
    SELECT * FROM books
    WHERE ((ifnull((SELECT sum(groupId) FROM book_groups WHERE groupId > 0), 0)) & `group`) = 0
    """
)
fun flowUngroupedBooks(): Flow<List<Book>>

/** 未归组 × 未读（eink 顶层结构保持透镜）。 */
@Query(
    """
    SELECT * FROM books
    WHERE ((ifnull((SELECT sum(groupId) FROM book_groups WHERE groupId > 0), 0)) & `group`) = 0
    AND $UNREAD_PREDICATE
    """
)
fun flowUngroupedUnreadBooks(): Flow<List<Book>>

/** 未归组 × 在读。 */
@Query(
    """
    SELECT * FROM books
    WHERE ((ifnull((SELECT sum(groupId) FROM book_groups WHERE groupId > 0), 0)) & `group`) = 0
    AND $READING_PREDICATE
    """
)
fun flowUngroupedReadingBooks(): Flow<List<Book>>

/** 用户组 × 未读 计数（私有组豁免 + 下架过滤口径与 flowUserGroupBookCount 一致）。 */
@Query(
    """
    SELECT COUNT(*) FROM books
    WHERE (`group` & :groupId) > 0
    AND ((SELECT isPrivate FROM book_groups WHERE groupId = :groupId) = 1 OR $PUBLIC_BOOK_FILTER)
    AND $UNREAD_PREDICATE
    """
)
fun flowUserGroupUnreadCount(groupId: Long): Flow<Int>

/** 用户组 × 在读 计数。 */
@Query(
    """
    SELECT COUNT(*) FROM books
    WHERE (`group` & :groupId) > 0
    AND ((SELECT isPrivate FROM book_groups WHERE groupId = :groupId) = 1 OR $PUBLIC_BOOK_FILTER)
    AND $READING_PREDICATE
    """
)
fun flowUserGroupReadingCount(groupId: Long): Flow<Int>

/** 用户组 × 未读 书籍流（eink 组视图：组位掩码 ∩ 筛选谓词）。 */
@Query(
    """
    SELECT * FROM books
    WHERE (`group` & :groupId) > 0
    AND ((SELECT isPrivate FROM book_groups WHERE groupId = :groupId) = 1 OR $PUBLIC_BOOK_FILTER)
    AND $UNREAD_PREDICATE
    """
)
fun flowUserGroupUnreadBooks(groupId: Long): Flow<List<Book>>

/** 用户组 × 在读 书籍流。 */
@Query(
    """
    SELECT * FROM books
    WHERE (`group` & :groupId) > 0
    AND ((SELECT isPrivate FROM book_groups WHERE groupId = :groupId) = 1 OR $PUBLIC_BOOK_FILTER)
    AND $READING_PREDICATE
    """
)
fun flowUserGroupReadingBooks(groupId: Long): Flow<List<Book>>

/** 书籍分组位掩码流（eink 归组面板回显观察）。 */
@Query("SELECT `group` FROM books WHERE bookUrl = :bookUrl")
fun flowBookGroupMask(bookUrl: String): Flow<Long?>
```

- [ ] **Step 3: 设置键与模型字段**

`PreferKey.kt` L72 后加：

```kotlin
const val einkBookshelfFilterId = "einkBookshelfFilterId"
```

`BookshelfSettings.kt` L53（`saveTabPosition` 行）后加：

```kotlin
/** eink 顶层筛选器（精选系统组 ID 或 IdAll；仅 eink 读写，完整模式不消费）。 */
val einkShelfFilter: Long = BookGroup.IdAll,
```

`BookshelfSettingsRepository.kt` 读映射（L83-85）替换为：

```kotlin
        saveTabPosition = legacySelectedGroupId(),
        einkShelfFilter = compatDsLong(PreferKey.einkBookshelfFilterId)
            ?: legacySelectedGroupId().takeIf {
                it == BookGroup.IdReading || it == BookGroup.IdUnread
            }
            ?: BookGroup.IdAll,
```

文件内（`toBookshelfSettings` 前或后）新增私有扩展：

```kotlin
/** 单值时代遗留：新键缺失时的旧选中组 ID（含 saveTabPosition 原始键兜底）。 */
private fun Preferences.legacySelectedGroupId(): Long =
    compatDsLong(PreferKey.bookshelfSelectedGroupId)
        ?: (rawPrefValue(PreferKey.saveTabPosition) as? Long)
        ?: BookGroup.IdAll
```

写映射（L136 `PreferKey.bookshelfSelectedGroupId to saveTabPosition,` 后）加：

```kotlin
    PreferKey.einkBookshelfFilterId to einkShelfFilter,
```

- [ ] **Step 4: 编译验证**

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL（Room @Query 在编译期校验 SQL 与常量插值）。

- [ ] **Step 5: 提交（宿主，未动 eink-lib）**

```bash
git status --short   # 确认只改了上述 4 个文件 + 已有的 eink-lib/.zcodeignore 用户改动不碰
git add app/src/main/java/io/legado/app/data/dao/BookDao.kt \
        app/src/main/java/io/legado/app/constant/PreferKey.kt \
        app/src/main/java/io/legado/app/domain/model/settings/BookshelfSettings.kt \
        app/src/main/java/io/legado/app/data/repository/BookshelfSettingsRepository.kt
git diff --cached --check
git commit -m "feat(book-dao): eink 书架分组组合查询——未读/在读谓词常量化、未归组三流、用户组×筛选四查询、位掩码观察流；新增 einkShelfFilter 设置键（legacy 单值回退）"
```

---

### Task 2: 端口契约重写 + 宿主桥实现（跨两仓库）

**Files:**
- Rewrite: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfGroupEngine.kt`
- Rewrite: `app/src/main/java/io/legado/app/eink/bridge/BookshelfGroupEngineImpl.kt`
- Rewrite: `app/src/test/java/io/legado/app/eink/bridge/BookshelfGroupEngineImplTest.kt`

**Interfaces:**
- Consumes: Task 1 的全部 DAO 查询与 `BookshelfSettings.einkShelfFilter`；宿主既有 `BookGroupMutationGateway`、`UpdateBooksGroupUseCase`、`BookGroupRepository`、`toBookshelfItemUiModel()`（BookshelfUiMapper.kt）、`sortedForBookshelf`（BookshelfSorter.kt）、`isNotShelf`。
- Produces（Task 3/4 依赖的精确契约）:

```kotlin
object BookshelfGroupIds { const val ALL: Long = -1L }
@EInkImmutable data class BookshelfGroupUiModel(val groupId: Long, val name: String, val bookCount: Int)
@EInkImmutable sealed interface BookshelfShelfEntryUiModel {
    @EInkImmutable data class Book(val book: BookshelfItemUiModel) : BookshelfShelfEntryUiModel
    @EInkImmutable data class Group(val groupId: Long, val name: String, val bookCount: Int, val coverUrl: String?) : BookshelfShelfEntryUiModel
}
interface BookshelfGroupEngine {
    fun observeFilters(): Flow<List<BookshelfGroupUiModel>>
    val selectedFilter: Flow<Long>
    suspend fun setSelectedFilter(filterId: Long)
    val selectedGroup: Flow<Long>
    suspend fun setSelectedGroup(groupId: Long)
    fun observeUserGroups(): Flow<List<BookshelfGroupUiModel>>
    fun observeShelfWithGroups(filterId: Long): Flow<List<BookshelfShelfEntryUiModel>>
    fun observeGroupBooks(groupId: Long, filterId: Long): Flow<List<BookshelfItemUiModel>>
    suspend fun createGroup(name: String)
    suspend fun renameGroup(groupId: Long, name: String)
    suspend fun moveGroup(groupId: Long, delta: Int)
    suspend fun dissolveGroup(groupId: Long)
    suspend fun setBookGroupMembership(bookUrl: String, groupId: Long, member: Boolean)
    fun observeBookGroupMembership(bookUrl: String): Flow<Set<Long>>
}
```

（说明：`observeUserGroups()` 为规格语义规则 4「解散失联回退」所需的用户组全量流，规格方法清单之外新增的只读方法；Task 3 的范围状态机以它为回退参考列表。）

- [ ] **Step 1: 先写纯函数失败测试**

重写 `BookshelfGroupEngineImplTest.kt` 全文（旧 `buildGroupUiModels` 测试随函数删除一并移除）：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfShelfEntryUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BookshelfGroupEngineImpl 的纯函数面：筛选器精选集装配 + 顶层混合条目装配
 * （门控/置顶/排序）。数据库交互不在单测范围（宿主层既有查询，真机验证）。
 */
class BookshelfGroupEngineImplTest {

    private fun group(id: Long, name: String = "组$id", order: Int = 0) =
        BookGroup(groupId = id, groupName = name, order = order)

    private fun book(url: String) = Book(bookUrl = url)

    private val nameOf: (BookGroup) -> String = { it.groupName }

    private val mapper: (Book) -> BookshelfItemUiModel = { BookshelfItemUiModel(bookUrl = it.bookUrl) }

    // ---- buildFilterUiModels ----

    @Test
    fun `筛选面板按精选集顺序装配且全部常驻`() {
        val counts = mapOf(
            BookGroup.IdAll to 9L, BookGroup.IdReading to 2L, BookGroup.IdUnread to 0L,
        )
        val result = buildFilterUiModels(
            filterIds = listOf(BookGroup.IdAll, BookGroup.IdReading, BookGroup.IdUnread),
            systemCounts = counts, hideEmpty = true,
        )
        assertEquals(
            listOf(
                BookshelfGroupUiModel(BookGroup.IdAll, "全部", 9),
                BookshelfGroupUiModel(BookGroup.IdReading, "在读", 2),
            ),
            result,
        )
    }

    @Test
    fun `hideEmpty 关闭时空筛选器保留`() {
        val result = buildFilterUiModels(
            filterIds = listOf(BookGroup.IdAll, BookGroup.IdUnread),
            systemCounts = mapOf(BookGroup.IdAll to 5L, BookGroup.IdUnread to 0L),
            hideEmpty = false,
        )
        assertEquals(2, result.size)
        assertEquals(0, result[1].bookCount)
    }

    // ---- buildShelfWithGroupsEntries ----

    @Test
    fun `无筛选按 hideEmpty 门控且条目置顶`() {
        val groups = listOf(group(0b1, order = 0), group(0b10, order = 1))
        val result = buildShelfWithGroupsEntries(
            userGroups = groups,
            userCounts = mapOf(0b1L to 2, 0b10L to 0),
            ungroupedBooks = listOf(book("u1"), book("u2")),
            filterActive = false, hideEmpty = true,
            nameOf = nameOf, bookMapper = mapper,
        )
        assertEquals(
            listOf(
                BookshelfShelfEntryUiModel.Group(0b1, "组1", 2, null),
                BookshelfShelfEntryUiModel.Book(BookshelfItemUiModel(bookUrl = "u1")),
                BookshelfShelfEntryUiModel.Book(BookshelfItemUiModel(bookUrl = "u2")),
            ),
            result,
        )
    }

    @Test
    fun `筛选激活时空组无条件隐藏非空组保留`() {
        val groups = listOf(group(0b1, order = 0), group(0b10, order = 1))
        val result = buildShelfWithGroupsEntries(
            userGroups = groups,
            userCounts = mapOf(0b1L to 0, 0b10L to 3),
            ungroupedBooks = listOf(book("u1")),
            filterActive = true, hideEmpty = false,
            nameOf = nameOf, bookMapper = mapper,
        )
        assertEquals(
            listOf(
                BookshelfShelfEntryUiModel.Group(0b10, "组2", 3, null),
                BookshelfShelfEntryUiModel.Book(BookshelfItemUiModel(bookUrl = "u1")),
            ),
            result,
        )
    }
}
```

注：`BookshelfItemUiModel(bookUrl = ...)` 单参构造成立的前提是其余参数有默认值——执行时先查 `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfItemUiModel.kt`；若参数无默认值，测试改为补全全部参数（`name = "n"` 等）。`Book(bookUrl = url)` 同理查 `data/entities/Book.kt`。

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfGroupEngineImplTest"`
Expected: 编译失败（`buildFilterUiModels` / `buildShelfWithGroupsEntries` 未定义、`BookshelfShelfEntryUiModel` 未定义）。

- [ ] **Step 3: 重写契约文件（eink-lib）**

`BookshelfGroupEngine.kt` 全文替换为：

```kotlin
package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable
import kotlinx.coroutines.flow.Flow

/**
 * 「全部」语义值（宿主 `BookGroup.IdAll` = -1）：筛选器与范围（scope）
 * 两个维度共用——不筛选 / 不在组视图。
 */
object BookshelfGroupIds {
    const val ALL: Long = -1L
}

/**
 * 筛选器/用户组展示快照（宿主构造义务：一次映射，模块零计算）。
 */
@EInkImmutable
data class BookshelfGroupUiModel(
    /** 组 id。筛选器为精选系统组负 ID；用户组为位值。模块不解释位运算。 */
    val groupId: Long,
    /** 展示名。筛选器为默认本地化名（不读宿主自定义名）；用户组为组名。 */
    val name: String,
    /** 上下文计数：筛选器为全书架口径；用户组条目为组大小或组内匹配数。 */
    val bookCount: Int,
)

/**
 * 「全部」视图（顶层）混合内容条目：书籍 | 用户组聚合条目（spec D5-D9）。
 */
@EInkImmutable
sealed interface BookshelfShelfEntryUiModel {

    @EInkImmutable
    data class Book(val book: BookshelfItemUiModel) : BookshelfShelfEntryUiModel

    /** 用户组聚合条目：点击进组视图，长按出管理菜单。 */
    @EInkImmutable
    data class Group(
        val groupId: Long,
        val name: String,
        /** 无筛选 = 组大小；筛选激活 = 组内匹配数（门控：0 时条目隐藏）。 */
        val bookCount: Int,
        /** 组封面路径（宿主 BookGroup.cover）；空则 UI 渲染文字磁贴。 */
        val coverUrl: String?,
    ) : BookshelfShelfEntryUiModel
}

/**
 * 书架分组端口（2026-10-14 spec 重写）：筛选（精选系统筛选器，eink 独立
 * 策展，不看宿主 show/order）+ 范围（全部 | 用户组）+ 顶层聚合 + 分组管理。
 *
 * 语义规则：位掩码运算封闭在宿主实现内；管理方法对系统组（groupId < 0）
 * 抛 [IllegalArgumentException]；筛选入口仅在顶层（组视图无 UI），但
 * [observeGroupBooks] 的 filterId 继承当前筛选并作用于组内容。
 */
interface BookshelfGroupEngine {

    /** 精选筛选器流（全部/在读/未读；hideEmpty 过滤、「全部」常驻）。 */
    fun observeFilters(): Flow<List<BookshelfGroupUiModel>>

    /** 当前筛选（持久化键 einkShelfFilter 投影）。 */
    val selectedFilter: Flow<Long>

    /** 持久化筛选；仅接受 [BookshelfGroupIds.ALL] 或精选集成员。 */
    suspend fun setSelectedFilter(filterId: Long)

    /** 当前范围：ALL 或用户组 id（saveTabPosition 投影，负值收敛为 ALL）。 */
    val selectedGroup: Flow<Long>

    /** 持久化范围（组视图进入/退出）；仅接受 ALL 或用户组 id。 */
    suspend fun setSelectedGroup(groupId: Long)

    /** 用户组全量流（不门控；范围状态的失联回退参考列表与菜单边界计算用）。 */
    fun observeUserGroups(): Flow<List<BookshelfGroupUiModel>>

    /** 顶层混合内容（ALL=无筛选：全部非空组条目 + 未归组书；筛选激活：匹配门控）。 */
    fun observeShelfWithGroups(filterId: Long): Flow<List<BookshelfShelfEntryUiModel>>

    /** 组视图内容（filterId 继承当前筛选，ALL = 全组）。 */
    fun observeGroupBooks(groupId: Long, filterId: Long): Flow<List<BookshelfItemUiModel>>

    /** 新建用户组（重名冲突抛 IAE）；P1 归组面板消费。 */
    suspend fun createGroup(name: String)

    /** 重命名用户组（系统组/重名抛 IAE）。 */
    suspend fun renameGroup(groupId: Long, name: String)

    /** 上移/下移（delta = ±1，写宿主 order 整表重排；系统组抛 IAE）。 */
    suspend fun moveGroup(groupId: Long, delta: Int)

    /** 解散用户组（书籍保留仅清位；系统组抛 IAE）。 */
    suspend fun dissolveGroup(groupId: Long)

    /** 归书（member=true 置位 / false 清位；系统组抛 IAE）；P1 消费。 */
    suspend fun setBookGroupMembership(bookUrl: String, groupId: Long, member: Boolean)

    /** 书籍当前归属的用户组 id 集合流（P1 归组面板回显）。 */
    fun observeBookGroupMembership(bookUrl: String): Flow<Set<Long>>
}
```

- [ ] **Step 4: 重写桥实现（宿主）**

`BookshelfGroupEngineImpl.kt` 全文替换为：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.domain.gateway.BookGroupMutationGateway
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.model.BookGroupUpdate
import io.legado.app.domain.model.NewBookGroup
import io.legado.app.domain.usecase.UpdateBooksGroupUseCase
import io.legado.app.eink.contract.BookshelfGroupEngine
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfShelfEntryUiModel
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import splitties.init.appCtx

/**
 * 书架分组端口实现（2026-10-14 spec）：精选筛选器 + 顶层聚合装配 + 管理
 * 委派既有领域网关。位掩码运算全部封闭在本层，不进契约。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object BookshelfGroupEngineImpl : BookshelfGroupEngine, KoinComponent {

    /** 精选筛选器（spec D4 定稿：全部、在读、未读，此顺序）。 */
    private val EINK_FILTER_IDS = listOf(BookGroup.IdAll, BookGroup.IdReading, BookGroup.IdUnread)

    private val bookGroupRepository: BookGroupRepository by inject()
    private val bookGroupMutationGateway: BookGroupMutationGateway by inject()
    private val updateBooksGroupUseCase: UpdateBooksGroupUseCase by inject()
    private val bookshelfSettingsGateway: BookshelfSettingsGateway by inject()

    private val nameOf: (BookGroup) -> String = { group ->
        group.getManageName(appCtx).let { it.groupName.ifBlank { it.suffix ?: "" } }
    }

    // —— 筛选 ——

    override fun observeFilters(): Flow<List<BookshelfGroupUiModel>> =
        combine(
            appDb.bookDao.flowSystemGroupCounts(),
            bookshelfSettingsGateway.settings
                .map { it.hideEmptyGroups }
                .distinctUntilChanged(),
        ) { counts, hideEmpty ->
            buildFilterUiModels(EINK_FILTER_IDS, counts.associate { it.groupId to it.count }, hideEmpty)
        }

    override val selectedFilter: Flow<Long> =
        bookshelfSettingsGateway.settings
            .map { it.einkShelfFilter }
            .distinctUntilChanged()

    override suspend fun setSelectedFilter(filterId: Long) {
        require(
            filterId == BookshelfGroupIds.ALL || EINK_FILTER_IDS.contains(filterId)
        ) { "非精选筛选器: $filterId" }
        bookshelfSettingsGateway.update { it.copy(einkShelfFilter = filterId) }
    }

    // —— 范围 ——

    override val selectedGroup: Flow<Long> =
        bookshelfSettingsGateway.settings
            .map { if (it.saveTabPosition > 0) it.saveTabPosition else BookshelfGroupIds.ALL }
            .distinctUntilChanged()

    override suspend fun setSelectedGroup(groupId: Long) {
        require(groupId == BookshelfGroupIds.ALL || groupId > 0) { "范围仅接受全部或用户组: $groupId" }
        bookshelfSettingsGateway.update { it.copy(saveTabPosition = groupId) }
    }

    override fun observeUserGroups(): Flow<List<BookshelfGroupUiModel>> =
        bookGroupRepository.flowAll().flatMapLatest { groups ->
            val userGroups = groups.filter { it.groupId > 0 }
            if (userGroups.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    userGroups.map { group ->
                        appDb.bookDao.flowUserGroupBookCount(group.groupId)
                            .map { group.groupId to it }
                    }
                ) { pairs -> pairs.toMap() }.map { counts ->
                    userGroups.map { group ->
                        BookshelfGroupUiModel(
                            groupId = group.groupId,
                            name = nameOf(group),
                            bookCount = counts[group.groupId] ?: 0,
                        )
                    }
                }
            }
        }

    // —— 顶层聚合 ——

    override fun observeShelfWithGroups(filterId: Long): Flow<List<BookshelfShelfEntryUiModel>> =
        bookGroupRepository.flowAll().flatMapLatest { groups ->
            val userGroups = groups.filter { it.groupId > 0 }.sortedBy { it.order }
            val countsFlow = if (userGroups.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    userGroups.map { group ->
                        userGroupCountFlow(filterId, group.groupId).map { group.groupId to it }
                    }
                ) { pairs -> pairs.toMap() }
            }
            combine(
                countsFlow,
                ungroupedBooksFlow(filterId),
                bookshelfSettingsGateway.settings
                    .map { BookshelfSortKey(it.bookshelfSort, it.bookshelfSortOrder) }
                    .distinctUntilChanged(),
                bookshelfSettingsGateway.settings
                    .map { it.hideEmptyGroups }
                    .distinctUntilChanged(),
            ) { counts, books, sortKey, hideEmpty ->
                val ungrouped = books
                    .filterNot { it.isNotShelf }
                    .sortedForBookshelf(sortKey.sort, sortKey.sortOrder)
                buildShelfWithGroupsEntries(
                    userGroups, counts, ungrouped,
                    filterActive = filterId != BookshelfGroupIds.ALL,
                    hideEmpty = hideEmpty, nameOf = nameOf,
                    bookMapper = { it.toBookshelfItemUiModel() },
                )
            }
        }.distinctUntilChanged()

    // —— 组视图 ——

    override fun observeGroupBooks(groupId: Long, filterId: Long): Flow<List<BookshelfItemUiModel>> =
        combine(
            when (filterId) {
                BookshelfGroupIds.ALL -> appDb.bookDao.flowUserGroupBooks(groupId)
                BookGroup.IdReading -> appDb.bookDao.flowUserGroupReadingBooks(groupId)
                BookGroup.IdUnread -> appDb.bookDao.flowUserGroupUnreadBooks(groupId)
                else -> error("非精选筛选器: $filterId")
            }.map { books -> books.filterNot { it.isNotShelf } },
            bookGroupRepository.flowAll().map { groups ->
                groups.firstOrNull { it.groupId == groupId }
            },
            bookshelfSettingsGateway.settings
                .map { BookshelfSortKey(it.bookshelfSort, it.bookshelfSortOrder) }
                .distinctUntilChanged(),
        ) { books, group, sortKey ->
            val sort = group?.getRealBookSort(sortKey.sort) ?: sortKey.sort
            books.sortedForBookshelf(sort, sortKey.sortOrder).map { it.toBookshelfItemUiModel() }
        }

    // —— 管理（仅用户组）——

    override suspend fun createGroup(name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名不能为空" }
        requireUserGroupNameFree(trimmed, excludeGroupId = null)
        bookGroupMutationGateway.addGroup(
            NewBookGroup(
                groupName = trimmed, bookSort = -1, enableRefresh = true,
                isPrivate = false, cover = null, pattern = null,
            )
        )
    }

    override suspend fun renameGroup(groupId: Long, name: String) {
        require(groupId > 0) { "系统筛选器不支持重命名" }
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名不能为空" }
        requireUserGroupNameFree(trimmed, excludeGroupId = groupId)
        val current = withContext(Dispatchers.IO) { appDb.bookGroupDao.getByID(groupId) }
            ?: error("分组不存在: $groupId")
        bookGroupMutationGateway.saveGroup(
            bookGroup = current.toUpdate().copy(groupName = trimmed),
            ruleToSave = null, ruleIdToDelete = null,
        )
    }

    override suspend fun moveGroup(groupId: Long, delta: Int) {
        require(groupId > 0) { "系统筛选器不支持排序" }
        require(delta == 1 || delta == -1) { "delta 仅接受 ±1: $delta" }
        val groups = withContext(Dispatchers.IO) { appDb.bookGroupDao.all }
        val index = groups.indexOfFirst { it.groupId == groupId }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, groups.lastIndex)
        if (target == index) return
        val reordered = groups.toMutableList().apply { add(target, removeAt(index)) }
            .mapIndexed { i, group -> group.copy(order = i) }
        bookGroupRepository.update(*reordered.toTypedArray())
    }

    override suspend fun dissolveGroup(groupId: Long) {
        require(groupId > 0) { "系统筛选器不支持解散" }
        bookGroupMutationGateway.deleteGroup(groupId)
    }

    override suspend fun setBookGroupMembership(bookUrl: String, groupId: Long, member: Boolean) {
        require(groupId > 0) { "系统筛选器不支持归书" }
        updateBooksGroupUseCase.updateGroups(setOf(bookUrl)) { old ->
            if (member) old or groupId else old and groupId.inv()
        }
    }

    override fun observeBookGroupMembership(bookUrl: String): Flow<Set<Long>> =
        combine(
            appDb.bookDao.flowBookGroupMask(bookUrl),
            bookGroupRepository.flowAll().map { groups -> groups.filter { it.groupId > 0 } },
        ) { mask, groups ->
            mask?.let { m -> groups.filter { m and it.groupId > 0 }.map { it.groupId } }?.toSet()
                ?: emptySet()
        }

    // —— 私有装配 ——

    private fun userGroupCountFlow(filterId: Long, groupId: Long): Flow<Int> = when (filterId) {
        BookshelfGroupIds.ALL -> appDb.bookDao.flowUserGroupBookCount(groupId)
        BookGroup.IdReading -> appDb.bookDao.flowUserGroupReadingCount(groupId)
        BookGroup.IdUnread -> appDb.bookDao.flowUserGroupUnreadCount(groupId)
        else -> error("非精选筛选器: $filterId")
    }

    private fun ungroupedBooksFlow(filterId: Long): Flow<List<Book>> = when (filterId) {
        BookshelfGroupIds.ALL -> appDb.bookDao.flowUngroupedBooks()
        BookGroup.IdReading -> appDb.bookDao.flowUngroupedReadingBooks()
        BookGroup.IdUnread -> appDb.bookDao.flowUngroupedUnreadBooks()
        else -> error("非精选筛选器: $filterId")
    }

    private suspend fun requireUserGroupNameFree(name: String, excludeGroupId: Long?) {
        val dup = withContext(Dispatchers.IO) { appDb.bookGroupDao.getByName(name) }
        require(dup == null || dup.groupId !in 1L..Long.MAX_VALUE || dup.groupId == excludeGroupId) {
            "已有同名分组: $name"
        }
    }

    private fun BookGroup.toUpdate() = BookGroupUpdate(
        groupId = groupId, groupName = groupName, cover = cover, order = order,
        enableRefresh = enableRefresh, show = show, bookSort = bookSort, isPrivate = isPrivate,
    )
}

/** 精选筛选器装配（纯函数，注入便于 JVM 测试）：默认命名 + hideEmpty 过滤 + 「全部」常驻。 */
internal fun buildFilterUiModels(
    filterIds: List<Long>,
    systemCounts: Map<Long, Long>,
    hideEmpty: Boolean,
): List<BookshelfGroupUiModel> = filterIds.mapNotNull { id ->
    val count = (systemCounts[id] ?: 0L).toInt()
    if (hideEmpty && id != BookGroup.IdAll && count == 0) return@mapNotNull null
    BookshelfGroupUiModel(
        groupId = id,
        name = when (id) {
            BookGroup.IdReading -> appCtx.getString(R.string.is_reading)
            BookGroup.IdUnread -> appCtx.getString(R.string.is_unread)
            else -> appCtx.getString(R.string.all)
        },
        bookCount = count,
    )
}

/**
 * 顶层混合条目装配（纯函数，注入名称解析与书籍映射便于 JVM 测试）：
 * 条目置顶（按入参序）+ 未归组书跟随；筛选激活时空组无条件门控，
 * 无筛选时按 hideEmpty 门控（spec D6/D8）。
 */
internal fun buildShelfWithGroupsEntries(
    userGroups: List<BookGroup>,
    userCounts: Map<Long, Int>,
    ungroupedBooks: List<Book>,
    filterActive: Boolean,
    hideEmpty: Boolean,
    nameOf: (BookGroup) -> String,
    bookMapper: (Book) -> BookshelfItemUiModel,
): List<BookshelfShelfEntryUiModel> {
    val entries = userGroups.mapNotNull { group ->
        val count = userCounts[group.groupId] ?: 0
        val gated = if (filterActive) count == 0 else hideEmpty && count == 0
        if (gated) return@mapNotNull null
        BookshelfShelfEntryUiModel.Group(
            groupId = group.groupId,
            name = nameOf(group),
            bookCount = count,
            coverUrl = group.cover,
        )
    }
    return entries + ungroupedBooks.map { BookshelfShelfEntryUiModel.Book(bookMapper(it)) }
}
```

注意：`flowSystemGroupCounts` 返回 `List<GroupBookCount>`（`groupId: Long, count: Long` 字段名以实际定义为准——若为 `count: Int` 则 `associate { it.groupId to it.count }` 后 `buildFilterUiModels` 的 `Map<Long, Long>` 改 `Map<Long, Int>`，同步修改纯函数签名与测试）。

- [ ] **Step 5: 跑测试通过**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfGroupEngineImplTest"`
Expected: PASS（3 个测试）。

- [ ] **Step 6: 编译 eink 模块确认契约自洽**

Run: `.\gradlew.bat :modules:eink:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（若任务名不存在，先 `.\gradlew.bat :modules:eink:tasks --all | grep -i "compile.*kotlin"` 查证真实名）。

- [ ] **Step 7: 提交（先子模块后宿主）**

```bash
git -C eink-lib status --short   # 只 add 契约文件；用户已有未提交改动不得卷入
git -C eink-lib add modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfGroupEngine.kt
git -C eink-lib diff --cached --check
git -C eink-lib commit -m "feat(contract)!: 书架分组端口重写——筛选(精选系统组)/范围/顶层混合条目/分组管理四域 + BookshelfShelfEntryUiModel 密封模型"

git add app/src/main/java/io/legado/app/eink/bridge/BookshelfGroupEngineImpl.kt \
        app/src/test/java/io/legado/app/eink/bridge/BookshelfGroupEngineImplTest.kt \
        eink-lib
git diff --cached --check
git commit -m "feat(eink-bridge): 分组桥重写实现新端口——精选筛选器装配、顶层结构保持透镜（门控+上下文计数）、组视图×筛选、管理委派 BookGroupMutationGateway/UpdateBooksGroupUseCase；eink-lib 指针随契约提交推进"
```

---

### Task 3: eink ViewModel + 首页双轴改造 + 聚合条目两形态渲染

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupState.kt`（仅 KDoc 措辞：通用化为「筛选/范围共用」）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/ShelfSelectorPanel.kt`（语义改筛选面板：参数与 KDoc 更名）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt`（混合条目渲染 + Group 两形态条目）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt`（chip=筛选、组视图标题、BackHandler、分页键、空态、预取）
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupStateTest.kt`（追加筛选状态用例）

**Interfaces:**
- Consumes: Task 2 契约全量；既有 `BookshelfGroupState`（复用两份实例：scope 与 filter）。
- Produces（Task 4 依赖）:
  - `BookshelfUiState.entries: List<BookshelfShelfEntryUiModel>`、`userGroups: List<BookshelfGroupUiModel>`（菜单边界用）、`scopeGroupName: String?`、`selectedScopeId: Long`、`selectedFilterId: Long`
  - VM intents：`selectFilter(filterId: Long)`、`enterGroup(groupId: Long)`、`exitGroup()`
  - `BookshelfScreen` 新增参数：`onGroupClick: (BookshelfShelfEntryUiModel.Group) -> Unit`、`onGroupLongClick: (BookshelfShelfEntryUiModel.Group) -> Unit`

- [ ] **Step 1: 状态机补充测试（先写失败）**

`BookshelfGroupStateTest.kt` 追加（类通用化后筛选维度的用法等价验证）：

```kotlin
@Test
fun `筛选维度复用同一状态机`() = runTest {
    val saved = MutableStateFlow(BookshelfGroupIds.ALL)
    val filters = MutableStateFlow(
        listOf(
            BookshelfGroupUiModel(BookshelfGroupIds.ALL, "全部", 9),
            BookshelfGroupUiModel(-20L, "在读", 2),
        )
    )
    val state = BookshelfGroupState(saved, filters, backgroundScope)
    val values = mutableListOf<Long>()
    backgroundScope.launch { state.selected.toList(values) }

    state.submit(-20L)
    runCurrent()
    saved.value = -20L
    runCurrent()
    assertEquals(-20L, values.last())

    // 筛选器被移出精选集（策展调整）→ 回退全部
    filters.value = listOf(BookshelfGroupUiModel(BookshelfGroupIds.ALL, "全部", 9))
    runCurrent()
    assertEquals(BookshelfGroupIds.ALL, values.last())
}
```

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.bookshelf.BookshelfGroupStateTest"`（任务名按 Step Task2-6 方式查证）
Expected: PASS（既有类未改行为，此步是回归锚；若 FAIL 先修状态类再继续）。

- [ ] **Step 2: BookshelfViewModel 双状态改造**

`BookshelfViewModel.kt` 关键改动（保持刷新/样式/消息管线不动）：

`BookshelfUiState` 替换为：

```kotlin
data class BookshelfUiState(
    /** 顶层混合条目（组条目置顶 + 书籍）；组视图为纯书籍条目。 */
    val entries: List<BookshelfShelfEntryUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val updatingBookUrls: Set<String> = emptySet(),
    val isGridLayout: Boolean = true,
    val style: BookshelfStyle = BookshelfStyle(),
    /** 精选筛选器列表（筛选面板数据源）。 */
    val filters: List<BookshelfGroupUiModel> = emptyList(),
    /** 当前筛选（ALL = 无筛选）。 */
    val selectedFilterId: Long = BookshelfGroupIds.ALL,
    /** 当前范围（ALL = 顶层；用户组 id = 组视图）。 */
    val selectedScopeId: Long = BookshelfGroupIds.ALL,
    /** 组视图时顶栏标题显示的组名；顶层为 null。 */
    val scopeGroupName: String? = null,
    /** 用户组全量（长按菜单边界/范围回退参考；不做空组门控）。 */
    val userGroups: List<BookshelfGroupUiModel> = emptyList(),
    /** 筛选端口已注册（chip 可渲染）；false 时书架维持现状平铺。 */
    val filterSelectorAvailable: Boolean = false,
) {
    val isEmpty: Boolean get() = entries.isEmpty() && !isLoading
}
```

分组状态区替换为（`groupEngine` getter 不变）：

```kotlin
/** 筛选状态宿主（精选筛选器列表 + 选中乐观覆盖，复用 [BookshelfGroupState]）。 */
internal val filterState = BookshelfGroupState(
    savedSelected = groupEngine?.selectedFilter
        ?: flowOf(BookshelfGroupIds.ALL),
    groupsFlow = groupEngine?.observeFilters()
        ?: flowOf(emptyList()),
    scope = viewModelScope,
)

/** 范围状态宿主（用户组列表 + 选中乐观覆盖 + 失联回退全部）。 */
internal val groupState = BookshelfGroupState(
    savedSelected = groupEngine?.selectedGroup
        ?: flowOf(BookshelfGroupIds.ALL),
    groupsFlow = groupEngine?.observeUserGroups()
        ?: flowOf(emptyList()),
    scope = viewModelScope,
)
```

`booksFlow` 替换为 `entriesFlow`：

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
private val entriesFlow = combine(groupState.selected, filterState.selected) { scope, filter ->
    scope to filter
}.distinctUntilChanged().flatMapLatest { (scope, filter) ->
    when {
        scope != BookshelfGroupIds.ALL ->
            (groupEngine?.observeGroupBooks(scope, filter) ?: engine.observeShelf())
                .map { list -> list.map { BookshelfShelfEntryUiModel.Book(it) } }
        groupEngine != null -> groupEngine.observeShelfWithGroups(filter)
        else -> engine.observeShelf()
            .map { list -> list.map { BookshelfShelfEntryUiModel.Book(it) } }
    }
}
```

`uiState` 的 combine 结构：外层第一支的 `booksFlow` 改 `entriesFlow`；第三支 `combine(groupState.groups, groupState.selected) { groups, selected -> groups to selected }` 改为四元：

```kotlin
combine(
    groupState.groups, groupState.selected,
    filterState.groups, filterState.selected,
) { userGroups, scope, filters, filter ->
    ShelfSelection(userGroups, scope, filters, filter)
}
```

（文件内加私有 `private data class ShelfSelection(val userGroups: List<BookshelfGroupUiModel>, val scope: Long, val filters: List<BookshelfGroupUiModel>, val filter: Long)`。）

UiState 构造替换：

```kotlin
BookshelfUiState(
    entries = frameEntries,
    isLoading = false,
    isRefreshing = refreshing,
    updatingBookUrls = updatingUrls,
    isGridLayout = style.isGridLayout,
    style = style,
    filters = selection.filters,
    selectedFilterId = selection.filter,
    selectedScopeId = selection.scope,
    scopeGroupName = selection.userGroups
        .firstOrNull { it.groupId == selection.scope }?.name,
    userGroups = selection.userGroups,
    filterSelectorAvailable = groupEngine != null,
)
```

`selectGroup` 替换为三个 intent：

```kotlin
/** 切换筛选（仅顶层入口可达；乐观置覆盖，einkShelfFilter 异步落库）。 */
fun selectFilter(filterId: Long) {
    filterState.submit(filterId)
    viewModelScope.launch { groupEngine?.setSelectedFilter(filterId) }
}

/** 进入组视图（聚合条目点击）。 */
fun enterGroup(groupId: Long) {
    groupState.submit(groupId)
    viewModelScope.launch { groupEngine?.setSelectedGroup(groupId) }
}

/** 退出组视图（系统返回；筛选状态保持）。 */
fun exitGroup() {
    enterGroup(BookshelfGroupIds.ALL)
}
```

`refresh` 内 `scopeGroupId` 取值行改为（语义：透镜不改变刷新范围）：

```kotlin
val scopeGroupId =
    if (trigger == "manual") groupState.selected.first() else BookshelfGroupIds.ALL
```

（`updatableBooks` 已支持 ALL | 组 id，无需改。）

- [ ] **Step 3: ShelfSelectorPanel 语义更名**

参数与 KDoc 更名（实现体不变）：

```kotlin
fun ShelfSelectorPanel(
    filters: List<BookshelfGroupUiModel>,
    selectedFilterId: Long,
    onSelectFilter: (Long) -> Unit,
    onDismiss: () -> Unit,
)
```

函数体内 `groups`→`filters`、`selectedGroupId`→`selectedFilterId`、`onSelectGroup`→`onSelectFilter` 同步更名；KDoc 首行改为「书架筛选面板（顶栏下方锚定浮层）：精选系统筛选器流式 chip 平铺。」；`GroupChip` 更名 `FilterChip`。

- [ ] **Step 4: BookshelfScreen 混合条目渲染**

`BookshelfScreen` 签名新增（放在 `onBookLongClick` 之后）：

```kotlin
onGroupClick: (BookshelfShelfEntryUiModel.Group) -> Unit = {},
onGroupLongClick: (BookshelfShelfEntryUiModel.Group) -> Unit = {},
```

`BookGrid`/`BookList` 的 `books: List<BookshelfItemUiModel>` 参数改 `entries: List<BookshelfShelfEntryUiModel>`；items 块改为：

```kotlin
items(entries) { entry ->
    when (entry) {
        is BookshelfShelfEntryUiModel.Book -> BookGridItem(
            book = entry.book, /* 其余参数同现状 */
        )
        is BookshelfShelfEntryUiModel.Group -> GroupGridItem(
            group = entry, gridCellWidth = gridCellWidth,
            onGroupClick = onGroupClick, onGroupLongClick = onGroupLongClick,
        )
    }
}
```

（`BookList` 同构，用 `GroupListItem`。）

新增两个私有条目（文件尾部 `EmptyBookshelf` 前插入）：

```kotlin
/** 用户组网格条目：封面（有则铺满磁贴）或文字磁贴 + 组名 + 计数（spec D9）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupGridItem(
    group: BookshelfShelfEntryUiModel.Group,
    gridCellWidth: Dp,
    onGroupClick: (BookshelfShelfEntryUiModel.Group) -> Unit,
    onGroupLongClick: (BookshelfShelfEntryUiModel.Group) -> Unit,
) {
    val colors = EInkTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onGroupClick(group) },
                onLongClick = { onGroupLongClick(group) },
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(EInkCoverWidth / EInkCoverHeight)
                .border(1.dp, colors.outline, EInkShapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            if (group.coverUrl != null) {
                EInkBookCover(
                    url = group.coverUrl,
                    name = group.name,
                    author = "",
                    sourceOrigin = null,
                    modifier = Modifier.fillMaxSize(),
                    width = gridCellWidth,
                    height = gridCellWidth * (EInkCoverHeight / EInkCoverWidth),
                )
            } else {
                GroupTileText(group)
            }
        }
        EInkText(
            text = group.name,
            style = EInkTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = EInkSpacing.xs),
        )
        EInkText(
            text = "${group.bookCount} 本",
            style = EInkTheme.typography.bodySmall,
            color = colors.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 用户组列表条目：磁贴（与封面等高等宽）+ 组名 + 计数行，整行等高不破坏分页。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupListItem(
    group: BookshelfShelfEntryUiModel.Group,
    listCoverWidth: Dp,
    listCoverHeight: Dp,
    onGroupClick: (BookshelfShelfEntryUiModel.Group) -> Unit,
    onGroupLongClick: (BookshelfShelfEntryUiModel.Group) -> Unit,
) {
    val colors = EInkTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onGroupClick(group) },
                onLongClick = { onGroupLongClick(group) },
            )
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
    ) {
        Box(
            modifier = Modifier
                .width(listCoverWidth)
                .height(listCoverHeight)
                .border(1.dp, colors.outline, EInkShapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            GroupTileText(group)
        }
        Spacer(modifier = Modifier.width(EInkSpacing.m))
        Column(
            modifier = Modifier
                .weight(1f)
                .height(listCoverHeight)
                .padding(vertical = EInkSpacing.xxs),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs, Alignment.CenterVertically),
        ) {
            EInkText(
                text = group.name,
                style = EInkTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EInkText(
                text = "分组 · ${group.bookCount} 本",
                style = EInkTheme.typography.bodyMedium,
                color = colors.outline,
            )
        }
    }
}

/** 磁贴内文字：组名 + 计数（无封面时）。 */
@Composable
private fun GroupTileText(group: BookshelfShelfEntryUiModel.Group) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        EInkText(
            text = group.name,
            style = EInkTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        EInkText(
            text = "${group.bookCount} 本",
            style = EInkTheme.typography.bodySmall,
            color = EInkTheme.colorScheme.outline,
        )
    }
}
```

新增 import：`BookshelfShelfEntryUiModel`、`androidx.compose.foundation.border`（已有）、`EInkShapes`（已有）。`EInkBookCover` 的 `sourceOrigin` 参数类型若非可空 `String?`，传 `""`。

- [ ] **Step 5: HomeRoute 接线**

新增 import：`androidx.activity.compose.BackHandler`、`BookshelfShelfEntryUiModel`。

(a) BackHandler（`showGroupSelector` 声明之后加；EInkDialog 后组合会覆盖它，弹框期间返回先关弹框）：

```kotlin
// 组视图退出：系统返回（筛选状态保持；spec D10）。EInkDialog 系列在组合树
// 更晚注册 BackHandler，弹框打开期间由弹框优先接管
BackHandler(enabled = uiState.selectedScopeId != BookshelfGroupIds.ALL) {
    viewModel.exitGroup()
}
```

(b) 分页几何键（组 id 键替换为 范围+筛选 复合键）：

```kotlin
val listPager = rememberEInkListPagerState(
    orientation, listCoverHeight,
    uiState.selectedScopeId to uiState.selectedFilterId,
)
val gridPager = rememberEInkGridPagerState(
    orientation,
    uiState.style.gridCoverWidth,
    uiState.style.titleMaxLines,
    uiState.selectedScopeId to uiState.selectedFilterId,
)
```

(c) `totalBooks` 与 realign/预取条目改混合列表：

```kotlin
val totalBooks = uiState.entries.size
```

realign（`LaunchedEffect(uiState.books, ...)` 改 `uiState.entries`）；预取块内：

```kotlin
val pageItems = uiState.entries
    .drop(start + pageSize).take(pageSize)
    .filterIsInstance<BookshelfShelfEntryUiModel.Book>()
    .map { it.book }
prefetchCovers(
    context = prefetchContext,
    items = pageItems,
    widthPx = coverWidthPx,
    heightPx = coverHeightPx,
    coverUrl = { it.coverUrl },
    sourceOrigin = { it.origin },
)
```

(d) 标题、chip、面板（`HomeScreen(...)` 调用处）：

```kotlin
headerTitle = if (selectedTab == HomeTabs.BOOKSHELF) {
    uiState.scopeGroupName ?: HomeTabLabels[selectedTab]
} else {
    HomeTabLabels[selectedTab]
},
titleTrailing = if (
    selectedTab == HomeTabs.BOOKSHELF &&
    uiState.filterSelectorAvailable &&
    uiState.selectedScopeId == BookshelfGroupIds.ALL   // 组视图无筛选入口（spec D7）
) {
    {
        val filterName = uiState.filters
            .firstOrNull { it.groupId == uiState.selectedFilterId }?.name ?: "全部"
        ShelfGroupChip(
            text = filterName,
            expanded = showGroupSelector,
            onClick = { showGroupSelector = !showGroupSelector },
        )
    }
} else {
    null
},
contentOverlay = {
    if (showGroupSelector) {
        ShelfSelectorPanel(
            filters = uiState.filters,
            selectedFilterId = uiState.selectedFilterId,
            onSelectFilter = viewModel::selectFilter,
            onDismiss = { showGroupSelector = false },
        )
    }
},
```

(e) `BookshelfScreen` 调用处：`state = uiState` 不变，追加：

```kotlin
onGroupClick = { group -> viewModel.enterGroup(group.groupId) },
onGroupLongClick = { },   // Task 4 接入管理菜单
emptyMessage = when {
    uiState.selectedScopeId != BookshelfGroupIds.ALL -> "此分组暂无书籍"
    uiState.selectedFilterId != BookshelfGroupIds.ALL -> "没有符合筛选的书籍"
    else -> "书架为空"
},
```

（`EInkApp.kt` 的 `onBookClick`/`onBookLongClick` 导航管线不动。）

- [ ] **Step 6: 编译 + 测试 + 提交（子模块）**

Run: `.\gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.bookshelf.*"`（任务名按前述方式查证）
Expected: BUILD SUCCESSFUL。

```bash
git -C eink-lib status --short
git -C eink-lib add modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupState.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/ShelfSelectorPanel.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupStateTest.kt
git -C eink-lib diff --cached --check
git -C eink-lib commit -m "feat(bookshelf): 双入口拆分——筛选面板精选集化、范围×筛选双状态机、顶层混合条目(组磁贴两形态)渲染、组视图标题+返回退出、分页复合几何键"
```

（宿主指针留待 Task 5 统一推进。）

---

### Task 4: 条目长按菜单——重命名 / 上移 / 下移 / 解散

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/ShelfGroupEntryMenu.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt`（菜单挂载 + onGroupLongClick 接线）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt`（管理 intents + 消息）
- Modify: eink 模块字符串资源（先用 `grep -rn "eink_added_to_bookshelf" eink-lib/modules/eink/src/main/res/` 定位 strings.xml 实际路径，追加条目）

**Interfaces:**
- Consumes: Task 2 的 `renameGroup/moveGroup/dissolveGroup`；Task 3 的 `userGroups`、`onGroupLongClick`、`_messages: MutableSharedFlow<UserMessage>`。
- Produces: `ShelfGroupEntryDialogs(...)` 组合函数（HomeRoute 挂载）；VM intents `renameGroup(groupId: Long, name: String)`、`moveGroup(groupId: Long, delta: Int)`、`dissolveGroup(groupId: Long)`。

- [ ] **Step 1: 字符串资源**

在定位到的 strings.xml 追加：

```xml
<string name="eink_group_name_exists">已有同名分组</string>
<string name="eink_group_operation_failed">分组操作失败，请重试</string>
```

- [ ] **Step 2: VM 管理 intents**

`BookshelfViewModel.kt` 追加（import `io.legado.app.eink.R`、`kotlin.coroutines.cancellation.CancellationException` 视需要）：

```kotlin
/** 长按条目管理：重命名（IAE=重名/空名，toast 反馈；Room 回流驱动 UI 更新）。 */
fun renameGroup(groupId: Long, name: String) {
    viewModelScope.launch {
        try {
            groupEngine?.renameGroup(groupId, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            _messages.emit(UserMessage.from(R.string.eink_group_name_exists))
        } catch (e: Exception) {
            _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
        }
    }
}

fun moveGroup(groupId: Long, delta: Int) {
    viewModelScope.launch {
        try {
            groupEngine?.moveGroup(groupId, delta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
        }
    }
}

fun dissolveGroup(groupId: Long) {
    viewModelScope.launch {
        try {
            groupEngine?.dissolveGroup(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
        }
    }
}
```

- [ ] **Step 3: 菜单组件（新建文件）**

`ShelfGroupEntryMenu.kt` 全文：

```kotlin
package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.legado.app.eink.contract.BookshelfShelfEntryUiModel
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 用户组聚合条目的管理对话框组（spec D11）：长按弹出动作菜单
 * （重命名/上移/下移/解散），重命名与解散各带二级弹框。
 * 三态互斥由调用方（HomeRoute）以局部状态驱动，同一时刻至多一个非空。
 */
@Composable
fun ShelfGroupEntryDialogs(
    menuGroup: BookshelfShelfEntryUiModel.Group?,
    renamingGroup: BookshelfShelfEntryUiModel.Group?,
    dissolvingGroup: BookshelfShelfEntryUiModel.Group?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: (groupId: Long, name: String) -> Unit,
    onMove: (groupId: Long, delta: Int) -> Unit,
    onDissolve: (groupId: Long) -> Unit,
    onDismiss: () -> Unit,
    onSwitchToRename: (BookshelfShelfEntryUiModel.Group) -> Unit,
    onSwitchToDissolve: (BookshelfShelfEntryUiModel.Group) -> Unit,
) {
    if (menuGroup != null) {
        EInkDialog(
            onDismiss = onDismiss,
            title = menuGroup.name,
            showActions = false,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MenuActionRow("重命名", enabled = true) { onSwitchToRename(menuGroup) }
                MenuActionRow("上移", enabled = canMoveUp) { onMove(menuGroup.groupId, -1) }
                MenuActionRow("下移", enabled = canMoveDown) { onMove(menuGroup.groupId, 1) }
                MenuActionRow("解散分组", enabled = true) { onSwitchToDissolve(menuGroup) }
            }
        }
    }
    if (renamingGroup != null) {
        var text by remember(renamingGroup) { mutableStateOf(renamingGroup.name) }
        val blank = text.isBlank()
        EInkDialog(
            onDismiss = onDismiss,
            title = "重命名分组",
            onConfirm = if (blank) null else ({ onRename(renamingGroup.groupId, text) }),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = EInkTheme.typography.bodyLarge.copy(color = EInkTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    if (dissolvingGroup != null) {
        EInkDialog(
            onDismiss = onDismiss,
            title = "解散分组",
            belowTitle = {
                EInkText(
                    text = "书籍保留，仅移出该分组。",
                    style = EInkTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
                )
            },
            confirmText = "解散",
            onConfirm = { onDissolve(dissolvingGroup.groupId) },
        ) {}
    }
}

@Composable
private fun MenuActionRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    EInkText(
        text = text,
        style = EInkTheme.typography.bodyLarge,
        color = if (enabled) EInkTheme.colorScheme.onSurface else EInkTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .einkClickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
    )
}
```

- [ ] **Step 4: HomeRoute 挂载**

`HomeRoute.kt` 状态区（`showGroupSelector` 后）加：

```kotlin
// 条目长按菜单三态（UI 局部状态，同面板纪律：不随导航栈保存）
var menuGroup by remember { mutableStateOf<BookshelfShelfEntryUiModel.Group?>(null) }
var renamingGroup by remember { mutableStateOf<BookshelfShelfEntryUiModel.Group?>(null) }
var dissolvingGroup by remember { mutableStateOf<BookshelfShelfEntryUiModel.Group?>(null) }
```

`BookshelfScreen` 的 `onGroupLongClick = { }` 替换为：

```kotlin
onGroupLongClick = { group -> menuGroup = group },
```

文件尾部（`BookshelfStylePanel` 之后）挂载：

```kotlin
val menuIndex = uiState.userGroups.indexOfFirst { it.groupId == menuGroup?.groupId }
ShelfGroupEntryDialogs(
    menuGroup = menuGroup,
    renamingGroup = renamingGroup,
    dissolvingGroup = dissolvingGroup,
    canMoveUp = menuIndex > 0,
    canMoveDown = menuIndex >= 0 && menuIndex < uiState.userGroups.lastIndex,
    onRename = { groupId, name ->
        viewModel.renameGroup(groupId, name)
        renamingGroup = null
    },
    onMove = { groupId, delta ->
        viewModel.moveGroup(groupId, delta)
        menuGroup = null
    },
    onDissolve = { groupId ->
        viewModel.dissolveGroup(groupId)
        dissolvingGroup = null
    },
    onDismiss = {
        menuGroup = null; renamingGroup = null; dissolvingGroup = null
    },
    onSwitchToRename = { group ->
        menuGroup = null; renamingGroup = group
    },
    onSwitchToDissolve = { group ->
        menuGroup = null; dissolvingGroup = group
    },
)
```

import：`io.legado.app.eink.feature.bookshelf.ShelfGroupEntryDialogs`。

- [ ] **Step 5: 编译 + 提交（子模块）**

Run: `.\gradlew.bat :modules:eink:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

```bash
git -C eink-lib add modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/ShelfGroupEntryMenu.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt \
        <Step1 定位到的 strings.xml 路径>
git -C eink-lib diff --cached --check
git -C eink-lib commit -m "feat(bookshelf): 聚合条目长按管理菜单——重命名(输入弹框)/上移/下移/解散(确认弹框)，错误经 UserMessage toast"
```

---

### Task 5: 全量验证与指针收口

**Files:**
- Modify: 宿主仓库 `eink-lib` 指针（Task 3/4 的子模块提交）

**Interfaces:**
- Consumes: Task 1-4 全部。
- Produces: 可发布的双仓库一致状态。

- [ ] **Step 1: 主验证集**

Run: `.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache`
Expected: BUILD SUCCESSFUL（含既有 eink 桥/状态测试回归）。

- [ ] **Step 2: 宿主指针推进**

```bash
git -C eink-lib log --oneline -4          # 确认 Task 2/3/4 三个提交在 eink/lib 分支
git -C eink-lib status --short            # 用户改动仍在工作区、未卷入
git add eink-lib
git diff --cached --check
git commit -m "chore(eink): 推进 eink-lib 指针——书架分组 P0 双入口拆分（Task3/4 子模块提交）"
```

- [ ] **Step 3: 交付说明清单（写入 PR/提交描述，不新建文档）**

必须列明（AGENTS 交付说明）：行为基线（书架默认视图零变化——无用户组时混合列表退化为未归组书单 = 现全量书单）、依赖边界净变化（eink 契约重写为破坏性变更，宿主桥同版本发布）、已验证项（编译 + 单测清单）、未验证项（真机：残影下排序反馈、长按与翻页手势冲突、组磁贴视觉、分页落定、返回退出与 predictive back 兼容、筛选切换整屏刷新代价；DB 组合查询等价性——`flowUserGroupReadingBooks(g)` ≡ `flowUserGroupBooks(g)` ∩ `flowByGroup(IdReading)` 待真机抽查）。

---

## Self-Review 记录

- **Spec 覆盖**：D1/D2（长按书菜单/归组面板）属 P1，不在本计划；D3-D11 P0 项均有对应 Task（D4→Task2 observeFilters/buildFilterUiModels、D5/D6→Task1+2 门控查询与装配、D7→Task3 chip 隐藏与 filterId 继承、D8→Task1 未归组谓词、D9→Task3 两形态条目、D10→Task3 BackHandler、D11→Task4、D12 端口一次性重写→Task2 含 P1 方法）。
- **类型一致性**：`BookshelfShelfEntryUiModel` 三处定义一致（Task2 契约/测试、Task3/4 消费）；`moveGroup(delta: Int)` 与 `onMove(groupId, delta)` 对齐；`observeUserGroups` 为规格清单外新增，已在 Interfaces 说明块记录理由。
- **已知不确定点**（执行时按步骤内注释核实）：`GroupBookCount.count` 字段类型（Long/Int）、`BookshelfItemUiModel`/`Book` 构造默认参、eink 模块 Gradle 任务名、strings.xml 路径、`EInkBookCover.sourceOrigin` 可空性。均已内嵌核查命令，不构成占位。
