# E-Ink 书架分组 Shelf Selector 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 E-Ink 书架实现 Shelf Selector（书架选择器）：顶栏分组 chip + 竖向展开选择面板 + 分组排序，数据经新增可选端口 `BookshelfGroupEngine` 与宿主交互。

**Architecture:** 三层垂直切片——①模块契约（可选端口 + 注册表槽位）→ ②宿主 bridge 实现（薄转发既有 Room 查询）→ ③模块 VM 状态接线 + UI 三态组件。选中分组与宿主 `saveTabPosition` 共享记忆；排序写入 `BookGroup.order`。

**Tech Stack:** Kotlin、Jetpack Compose（E-Ink 设计系统，零动画）、Room Flow、Koin、StateFlow/flatMapLatest、JUnit4 + kotlinx-coroutines-test。

**Spec:** `docs/superpowers/specs/2026-09-16-eink-bookshelf-groups-design.md`（行为语义以 spec 为准，本计划是实现步骤）。

## Global Constraints

- 分组管理（建/删组、书归组、AI 分组）不在范围——只做浏览、切换、排序。
- E-Ink 交互铁律：列表/面板禁自由滚动，整页翻页（`userScrollEnabled = false` + pager）；零动画零阴影。
- 未注册 `BookshelfGroupEngine` 时选择器整体不渲染、VM 恒走 `observeShelf()`（诚实退化，不伪造空分组）。
- 位掩码（`Book.group` 位运算）不得泄漏进模块契约；模块只见 `groupId`/`name`/`bookCount`。
- AAR 兼容：新增成员一律带默认值/可选参数，不破坏既有宿主调用。
- 测试纪律：模块单测 VM 相关状态类用 `runTest` + `backgroundScope`（见 `BookshelfStyleStateTest` 先例）；eink 模块 VM 若硬编码 IO 调度用 `runBlocking`（本计划不直测 AndroidViewModel，逻辑抽纯类）。
- 构建命令在仓库根（Git Bash）执行：`./gradlew.bat <task>`。
- 每个任务收尾 `git diff --check`；提交信息用仓库中文惯例（`feat(eink): …`）。

---

### Task 1: 契约文件与注册表可选槽位（模块）

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfGroupEngine.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt`

**Interfaces:**
- Consumes: 无（首个任务）。
- Produces: `BookshelfGroupUiModel(groupId: Long, name: String, bookCount: Int)`、`BookshelfGroupIds.ALL: Long = -1L`、`interface BookshelfGroupEngine`（成员：`observeGroups(): Flow<List<BookshelfGroupUiModel>>`、`observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>>`、`selectedGroup: Flow<Long>`、`setSelectedGroup(groupId: Long)`、`moveGroup(groupId: Long, up: Boolean)`）、`EInkEngineRegistry.bookshelfGroupEngine: BookshelfGroupEngine?`、`install(..., bookshelfGroupEngine: BookshelfGroupEngine? = null)`。

- [ ] **Step 1: 写注册表失败测试**

在 `EInkEngineRegistryTest.kt` 中追加两个用例（`e_` 前缀保证在 `d_按键枢纽` 之后执行，见该文件 `@FixMethodOrder` 说明）：

```kotlin
    /** 分组端口桩（代理生成，只做存取断言，方法不实际调用）。 */
    private val fakeGroupEngine: BookshelfGroupEngine = stub()

    @Test
    fun `e_bookshelfGroupEngine 未注册时为 null 且不参与必填校验`() {
        installDefaults() // 不传 bookshelfGroupEngine：install 正常完成即证明非必填
        assertNull(EInkEngineRegistry.bookshelfGroupEngine)
    }

    @Test
    fun `e_install 传入 bookshelfGroupEngine 后可取回`() {
        EInkEngineRegistry.install(
            globalSettings = stub(),
            bookshelfEngine = stub(),
            searchEngine = stub(),
            tocEngine = stub(),
            bookDetailEngine = stub(),
            changeSourceEngine = stub(),
            coverEngine = stub(),
            readerEngine = stub(),
            bookshelfGroupEngine = fakeGroupEngine,
        )
        assertSame(fakeGroupEngine, EInkEngineRegistry.bookshelfGroupEngine)
    }
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.EInkEngineRegistryTest"`
Expected: 编译错误（`BookshelfGroupEngine` 未定义）。

- [ ] **Step 3: 写契约文件**

创建 `modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfGroupEngine.kt`：

```kotlin
package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable
import kotlinx.coroutines.flow.Flow

/**
 * 「全部」虚拟分组的 groupId（宿主 `BookGroup.IdAll` = -1）。
 *
 * 模块以本常量判断「不分组过滤」：选中值为 [ALL] 时书架走
 * [BookshelfEngine.observeShelf] 现状平铺路径；其余值走
 * [BookshelfGroupEngine.observeGroupBooks]。
 */
object BookshelfGroupIds {
    const val ALL: Long = -1L
}

/**
 * 书架分组展示快照（宿主构造义务：一次映射，模块零计算，
 * 同 [BookshelfItemUiModel] 纪律）。
 */
@EInkImmutable
data class BookshelfGroupUiModel(
    /**
     * 分组 id。虚拟组沿用宿主表行语义（全部 -1、未分组 -100、本地 -2 等，
     * 建库时种入 book_groups 表）；用户组为位值。模块不解释位运算。
     */
    val groupId: Long,

    /** 展示名（宿主经 getManageName 解析，虚拟组空名回落本地化后缀）。 */
    val name: String,

    /** 组内书数。宿主已按 hideEmptyGroups 语义处理：可见组才出现在列表。 */
    val bookCount: Int,
)

/**
 * 书架分组端口——**可选**端口（同 [MarksEngine] / [ReaderSelectionEngine]）：
 * 未注册 = 宿主无分组浏览能力，书架选择器整体不渲染（书架维持全量平铺），
 * 不参与 install 必填校验。
 *
 * 职责边界：分组浏览/切换/排序由本端口承载；分组管理（建组、删组、
 * 书归组、AI 分组、标签规则）不在端口面内，仍归完整模式。
 *
 * 宿主数据交互（位掩码模型，模块不感知）：
 * ```text
 * observeGroups()  ──► book_groups 表 show>0 行（按 order 排）
 *                      + BookDao 计数查询（系统组/用户组）
 *                      + hideEmptyGroups 过滤（「全部」永不隐藏）
 * observeGroupBooks(groupId) ──► BookDao.flowByGroup(groupId)
 *                      + 组 bookSort>=0 时覆盖全局排序（sortBooks）
 * selectedGroup / setSelectedGroup ──► 宿主书架设置 saveTabPosition
 * moveGroup(groupId, up) ──► 与相邻行交换显示序列，重赋唯一 order 落库
 * ```
 */
interface BookshelfGroupEngine {

    /**
     * 分组选择模型流（实时档）：`book_groups` 中 show>0 的行按 `order`
     * 排序，附组内书数。`hideEmptyGroups` 开启时计数为 0 的组被过滤，
     * 「全部」（[BookshelfGroupIds.ALL]）恒在列表内。
     */
    fun observeGroups(): Flow<List<BookshelfGroupUiModel>>

    /**
     * 组内书籍流：映射语义与 [BookshelfEngine.observeShelf] 完全一致
     * （作者清洗/封面挑选/未读数预计算一次完成）。`groupId` 为
     * [BookshelfGroupIds.ALL] 时不分发（调用方应走 observeShelf）；
     * 其余值含虚拟组（未分组 -100 等）与用户组。组 `bookSort >= 0`
     * 时覆盖全局排序。
     */
    fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>>

    /**
     * 当前选中分组（宿主 `saveTabPosition` 投影，实时档）：与完整模式
     * 共享「记住当前分组」，跨模式一致。初始值「全部」。
     */
    val selectedGroup: Flow<Long>

    /**
     * 持久化选中分组（切组时调用）：宿主经设置网关一次原子 update 写
     * `saveTabPosition`。模块侧应乐观更新 UI，不等待落库。
     */
    suspend fun setSelectedGroup(groupId: Long)

    /**
     * 排序模式 ▲▼ 的唯一写通道：把 [groupId] 与显示序列中的相邻行
     * （[up] = true 上邻，false 下邻）交换位置。每次点击即写，不攒批；
     * 首行上移/末行下移为 no-op。结果经 [observeGroups] 重发，
     * 模块乐观重排后由真值流收敛。
     */
    suspend fun moveGroup(groupId: Long, up: Boolean)
}
```

- [ ] **Step 4: 注册表加可选槽位**

修改 `EInkEngineRegistry.kt`：

1. 字段区（`private var _marksEngine` 之后）：

```kotlin
    private var _bookshelfGroupEngine: BookshelfGroupEngine? = null
```

2. 访问器区（`marksEngine` 访问器之后）：

```kotlin
    /**
     * 书架分组端口——**可选**端口：未注册 = 宿主无分组浏览能力，
     * 书架选择器不渲染（书架维持全量平铺），不参与 install 必填校验。
     */
    val bookshelfGroupEngine: BookshelfGroupEngine?
        get() = _bookshelfGroupEngine
```

3. `install(...)` 参数列表末尾追加（`marksEngine: MarksEngine? = null` 之后）：

```kotlin
        bookshelfGroupEngine: BookshelfGroupEngine? = null,
```

4. `install` 函数体赋值区追加：

```kotlin
        _bookshelfGroupEngine = bookshelfGroupEngine
```

5. `install` 的 KDoc `@param` 列表末尾追加：

```kotlin
     * @param bookshelfGroupEngine 书架分组端口实现（可选，默认 null：
     *   宿主无分组浏览能力时不传，书架选择器不渲染）。
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.EInkEngineRegistryTest"`
Expected: PASS（含新增两用例）。

- [ ] **Step 6: 提交**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfGroupEngine.kt \
        modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt \
        modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt
git commit -m "feat(eink): 书架分组可选端口契约与注册表槽位"
git diff --check
```

---

### Task 2: 宿主 bridge 实现（BookshelfGroupEngineImpl）

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/BookshelfGroupEngineImpl.kt`
- Create: `app/src/main/java/io/legado/app/eink/bridge/BookshelfUiMapper.kt`（从 `BookshelfEngineImpl` 抽出共享映射）
- Modify: `app/src/main/java/io/legado/app/eink/bridge/BookshelfEngineImpl.kt`（改用共享映射）
- Modify: `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt:59-76`（install 装配）
- Test: `app/src/test/java/io/legado/app/eink/bridge/BookshelfGroupEngineImplTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `BookshelfGroupEngine` / `BookshelfGroupUiModel` / `BookshelfGroupIds`；既有 `BookGroupRepository`（flowShow/update）、`BookshelfRepository.sortBooks`（不直接用，见下）、`BookshelfSettingsGateway`（settings/update）、`appDb.bookDao.flowByGroup`、`sortedForBookshelf`（BookshelfSorter.kt）、`BookGroup.getRealBookSort/getManageName`。
- Produces: `internal fun Book.toBookshelfItemUiModel(): BookshelfItemUiModel`（BookshelfUiMapper.kt，两 bridge 共用）；`internal fun buildGroupUiModels(groups, systemCounts, userCounts, hideEmpty, nameOf): List<BookshelfGroupUiModel>`；`internal fun reorderedGroupsForMove(groups, groupId, up): List<BookGroup>?`；`internal data class BookshelfSortKey(sort, sortOrder)`；`EInkBridge.install()` 装配 `bookshelfGroupEngine = BookshelfGroupEngineImpl`。

- [ ] **Step 1: 写纯函数失败测试**

创建 `app/src/test/java/io/legado/app/eink/bridge/BookshelfGroupEngineImplTest.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.data.entities.BookGroup
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BookshelfGroupEngineImpl 的纯函数面：分组快照组装（hideEmpty 过滤 +
 * 名称解析注入）与排序重排（邻位交换 + order 整体重赋）。
 * 数据库交互不在单测范围（宿主层既有查询，真机验证）。
 */
class BookshelfGroupEngineImplTest {

    private fun group(id: Long, name: String = "组$id", order: Int = 0) =
        BookGroup(groupId = id, groupName = name, order = order)

    private val nameOf: (BookGroup) -> String = { it.groupName }

    // ---- buildGroupUiModels ----

    @Test
    fun `hideEmpty 关闭时全部组可见`() {
        val groups = listOf(
            group(BookGroup.IdAll), group(-100L), group(0b1, order = 1), group(0b10, order = 2)
        )
        val result = buildGroupUiModels(
            groups, systemCounts = mapOf(-100L to 0), userCounts = emptyMap(),
            hideEmpty = false, nameOf = nameOf,
        )
        assertEquals(4, result.size)
        assertEquals(BookshelfGroupUiModel(0b1, "组1", 0), result[2])
    }

    @Test
    fun `hideEmpty 开启时空组被过滤但全部保留`() {
        val groups = listOf(
            group(BookGroup.IdAll), group(-100L), group(0b1, order = 1), group(0b10, order = 2)
        )
        val result = buildGroupUiModels(
            groups,
            systemCounts = mapOf(BookGroup.IdAll to 5, -100L to 3),
            userCounts = mapOf(0b1L to 0, 0b10L to 7),
            hideEmpty = true,
            nameOf = nameOf,
        )
        // 全部(-1) 保留（计数 5）；未分组(-100) 计数 3 保留；组1 空被滤；组2 保留
        assertEquals(listOf(-1L, -100L, 0b10L), result.map { it.groupId })
    }

    @Test
    fun `名称解析经注入的 nameOf`() {
        val result = buildGroupUiModels(
            listOf(group(-1L)), systemCounts = mapOf(-1L to 2), userCounts = emptyMap(),
            hideEmpty = false, nameOf = { "解析名" },
        )
        assertEquals("解析名", result.single().name)
    }

    // ---- reorderedGroupsForMove ----

    @Test
    fun `上移与相邻行交换且整体重赋唯一 order`() {
        val groups = listOf(group(1L), group(2L), group(3L)).mapIndexed { i, g -> g.copy(order = i) }
        val moved = reorderedGroupsForMove(groups, 2L, up = true)!!
        assertEquals(listOf(2L, 1L, 3L), moved.map { it.groupId })
        assertEquals(listOf(0, 1, 2), moved.map { it.order })
    }

    @Test
    fun `首行上移与末行下移为 no-op 返回 null`() {
        val groups = listOf(group(1L), group(2L)).mapIndexed { i, g -> g.copy(order = i) }
        assertNull(reorderedGroupsForMove(groups, 1L, up = true))
        assertNull(reorderedGroupsForMove(groups, 2L, up = false))
    }

    @Test
    fun `未知 groupId 返回 null`() {
        assertNull(reorderedGroupsForMove(listOf(group(1L)), 99L, up = true))
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfGroupEngineImplTest"`
Expected: 编译错误（`buildGroupUiModels` / `reorderedGroupsForMove` 未定义）。

- [ ] **Step 3: 抽出共享 UiModel 映射**

创建 `app/src/main/java/io/legado/app/eink/bridge/BookshelfUiMapper.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.help.book.getRealAuthor
import io.legado.app.help.book.getUnreadChapterNum

/**
 * [Book] → [BookshelfItemUiModel]：书架条目渲染字段的唯一抽取点
 * （书架全量流与分组流共用，避免两份映射漂移）。语义见
 * [BookshelfItemUiModel] 的映射纪律 KDoc。
 */
internal fun Book.toBookshelfItemUiModel() = BookshelfItemUiModel(
    bookUrl = bookUrl,
    name = name,
    author = author,
    displayAuthor = getRealAuthor(),
    coverUrl = getDisplayCover(),
    origin = origin,
    currentChapterTitle = durChapterTitle,
    latestChapterTitle = latestChapterTitle,
    unreadCount = getUnreadChapterNum(),
    hasNewChapter = lastCheckCount > 0,
)

/** 排序键投影：设置流任意键变化不触发书架重排，仅排序键变化才重发。 */
internal data class BookshelfSortKey(val sort: Int, val sortOrder: Int)
```

注：`getDisplayCover()` 与 `getUnreadChapterNum()` 的 import 位置以 `BookshelfEngineImpl` 现有 import 为准（`io.legado.app.help.book.*` 扩展）。`BookshelfSortKey` 从 `BookshelfEngineImpl` 移入本文件后，`BookshelfEngineImpl` 中的同名 private data class 删除、改为使用共享定义。

修改 `BookshelfEngineImpl.kt`：

1. 删除 private 扩展 `Book.toBookshelfItemUiModel()`（L49-60）与 private `data class BookshelfSortKey`（L161-162），改用共享定义（同包 internal，无需 import）。
2. 其余逻辑不动（编译通过即行为不变——映射体逐行相同）。

- [ ] **Step 4: 写 BookshelfGroupEngineImpl**

创建 `app/src/main/java/io/legado/app/eink/bridge/BookshelfGroupEngineImpl.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.data.repository.BookshelfRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.eink.contract.BookshelfGroupEngine
import io.legado.app.eink.contract.BookshelfGroupUiModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import splitties.init.appCtx

/**
 * 书架分组端口实现：薄转发既有 Room 查询 + 快照映射 + 排序落库。
 * 位掩码运算全部封闭在本层（flowByGroup 查询内），不进契约。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object BookshelfGroupEngineImpl : BookshelfGroupEngine, KoinComponent {

    private val bookGroupRepository: BookGroupRepository by inject()
    private val bookshelfSettingsGateway: BookshelfSettingsGateway by inject()

    /**
     * 展示名解析：虚拟组表内 groupName 可能为空，回落宿主本地化后缀
     * （与完整模式 GroupManageSheet 的 getManageName 消费方式一致）。
     */
    private val nameOf: (BookGroup) -> String = { group ->
        group.getManageName(appCtx).let { it.groupName.ifBlank { it.suffix ?: "" } }
    }

    override fun observeGroups(): Flow<List<BookshelfGroupUiModel>> =
        bookGroupRepository.flowShow().flatMapLatest { groups ->
            val userGroups = groups.filter { it.groupId > 0 }
            val userCountsFlow = if (userGroups.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    userGroups.map { group ->
                        appDb.bookDao.flowUserGroupBookCount(group.groupId)
                            .map { group.groupId to it }
                    }
                ) { pairs -> pairs.toMap() }
            }
            combine(
                appDb.bookDao.flowSystemGroupCounts(),
                userCountsFlow,
                bookshelfSettingsGateway.settings
                    .map { it.hideEmptyGroups }
                    .distinctUntilChanged(),
            ) { systemCounts, userCounts, hideEmpty ->
                buildGroupUiModels(
                    groups,
                    systemCounts.associate { it.groupId to it.count },
                    userCounts,
                    hideEmpty,
                    nameOf,
                )
            }
        }.distinctUntilChanged()

    override fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>> =
        combine(
            appDb.bookDao.flowByGroup(groupId),
            bookGroupRepository.flowShow().map { groups ->
                groups.firstOrNull { it.groupId == groupId }
            },
            bookshelfSettingsGateway.settings
                .map { BookshelfSortKey(it.bookshelfSort, it.bookshelfSortOrder) }
                .distinctUntilChanged(),
        ) { books, group, sortKey ->
            // 组 bookSort >= 0 覆盖全局排序（语义与完整模式 sortBooks 一致）
            val sort = group?.getRealBookSort(sortKey.sort) ?: sortKey.sort
            books.sortedForBookshelf(sort, sortKey.sortOrder)
        }.map { books -> books.map { it.toBookshelfItemUiModel() } }

    override val selectedGroup: Flow<Long> =
        bookshelfSettingsGateway.settings
            .map { it.saveTabPosition }
            .distinctUntilChanged()

    override suspend fun setSelectedGroup(groupId: Long) {
        bookshelfSettingsGateway.update { it.copy(saveTabPosition = groupId) }
    }

    override suspend fun moveGroup(groupId: Long, up: Boolean) {
        val groups = bookGroupRepository.flowShow().first()
        val reordered = reorderedGroupsForMove(groups, groupId, up) ?: return
        val changed = reordered.filter { new ->
            groups.firstOrNull { it.groupId == new.groupId }?.order != new.order
        }
        try {
            appDb.runInTransaction {
                changed.forEach { appDb.bookGroupDao.update(it) }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 排序落库失败不抛给模块：真值流不重发，模块乐观层由下一次
            // 分组表变化收敛；日志指名定位
            AppLog.put("分组排序失败 groupId=$groupId\n${e.localizedMessage}", e)
        }
    }
}

/**
 * 分组快照组装（纯函数，注入名称解析便于 JVM 单测）：
 * `hideEmpty` 开启时计数为 0 的组被过滤，「全部」永不隐藏
 * （镜像宿主 BookshelfViewModel.computeHiddenGroupIds 语义）。
 */
internal fun buildGroupUiModels(
    groups: List<BookGroup>,
    systemCounts: Map<Long, Int>,
    userCounts: Map<Long, Int>,
    hideEmpty: Boolean,
    nameOf: (BookGroup) -> String,
): List<BookshelfGroupUiModel> = groups.mapNotNull { group ->
    val count =
        if (group.groupId > 0) userCounts[group.groupId]
        else systemCounts[group.groupId]
    if (hideEmpty && group.groupId != BookGroup.IdAll && (count ?: 0) == 0) {
        return@mapNotNull null
    }
    BookshelfGroupUiModel(
        groupId = group.groupId,
        name = nameOf(group),
        bookCount = count ?: 0,
    )
}

/**
 * 排序重排（纯函数）：目标行与相邻行交换后整体重赋 `order = index`，
 * 保证 order 唯一有序（宿主可容忍历史碰撞值）。不可移动返回 null。
 */
internal fun reorderedGroupsForMove(
    groups: List<BookGroup>,
    groupId: Long,
    up: Boolean,
): List<BookGroup>? {
    val index = groups.indexOfFirst { it.groupId == groupId }
    if (index < 0) return null
    val target = index + if (up) -1 else 1
    if (target !in groups.indices) return null
    return groups.toMutableList()
        .apply { add(target, removeAt(index)) }
        .mapIndexed { i, g -> g.copy(order = i) }
}
```

注：`KoinComponent` 需 `import org.koin.core.component.KoinComponent` 与 `import org.koin.core.component.inject`（对照 `BookshelfEngineImpl` import 区补齐）；`sortedForBookshelf` 为同包 internal（BookshelfSorter.kt），无需 import。

- [ ] **Step 5: EInkBridge 装配**

修改 `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt` 的 `install()`（L59-72），在 `marksEngine = MarksEngineImpl,` 之后追加一行：

```kotlin
            bookshelfGroupEngine = BookshelfGroupEngineImpl,
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfGroupEngineImplTest" --tests "io.legado.app.eink.bridge.BookshelfStyleMapperTest"`
Expected: PASS（新用例 + 既有映射测试不回归）。

Run: `./gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/BookshelfGroupEngineImpl.kt \
        app/src/main/java/io/legado/app/eink/bridge/BookshelfUiMapper.kt \
        app/src/main/java/io/legado/app/eink/bridge/BookshelfEngineImpl.kt \
        app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt \
        app/src/test/java/io/legado/app/eink/bridge/BookshelfGroupEngineImplTest.kt
git commit -m "feat(eink): 宿主书架分组端口实现与装配"
git diff --check
```

---

### Task 3: 模块选中分组状态（BookshelfGroupState）

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupState.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupStateTest.kt`

**Interfaces:**
- Consumes: Task 1 契约（`BookshelfGroupUiModel` / `BookshelfGroupIds.ALL`）。
- Produces: `internal class BookshelfGroupState(savedSelected: Flow<Long>, groupsFlow: Flow<List<BookshelfGroupUiModel>>, scope: CoroutineScope)`，成员 `val groups: Flow<List<BookshelfGroupUiModel>>`、`val selected: Flow<Long>`、`fun submit(groupId: Long)`。

- [ ] **Step 1: 写失败测试**

创建 `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupStateTest.kt`（结构对照 `BookshelfStyleStateTest`）：

```kotlin
package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书架分组状态宿主：选中覆盖优先/追平清除（镜像 BookshelfStyleState
 * 语义）+ 选中组被隐藏后回退「全部」。收集经 backgroundScope + runCurrent
 * 推进虚拟时钟。
 */
class BookshelfGroupStateTest {

    private fun group(id: Long, count: Int = 1) =
        BookshelfGroupUiModel(groupId = id, name = "组$id", bookCount = count)

    @Test
    fun `提交后覆盖优先于快照`() = runTest {
        val saved = MutableStateFlow(BookshelfGroupIds.ALL)
        val state = BookshelfGroupState(saved, MutableStateFlow(listOf(group(1L))), backgroundScope)
        val values = mutableListOf<Long>()
        backgroundScope.launch { state.selected.toList(values) }

        state.submit(1L)
        runCurrent()
        assertEquals(1L, values.last())
    }

    @Test
    fun `快照追平后清除覆盖且后续外部变化生效`() = runTest {
        val saved = MutableStateFlow(BookshelfGroupIds.ALL)
        val state = BookshelfGroupState(saved, MutableStateFlow(listOf(group(1L))), backgroundScope)
        val values = mutableListOf<Long>()
        backgroundScope.launch { state.selected.toList(values) }

        state.submit(1L)
        runCurrent()
        saved.value = 1L // 落库追平，覆盖清除
        runCurrent()
        saved.value = BookshelfGroupIds.ALL // 外部（完整模式）再改
        runCurrent()
        assertEquals(BookshelfGroupIds.ALL, values.last())
    }

    @Test
    fun `选中组被隐藏后回退全部`() = runTest {
        val saved = MutableStateFlow(1L)
        val groups = MutableStateFlow(listOf(group(1L)))
        val state = BookshelfGroupState(saved, groups, backgroundScope)
        val values = mutableListOf<Long>()
        backgroundScope.launch { state.selected.toList(values) }

        runCurrent()
        assertEquals(1L, values.last())

        groups.value = emptyList() // 组 1 被删/隐藏
        runCurrent()
        assertEquals(BookshelfGroupIds.ALL, values.last())
    }

    @Test
    fun `分组未加载空列表不回退`() = runTest {
        val saved = MutableStateFlow(1L)
        val groups = MutableStateFlow(emptyList<BookshelfGroupUiModel>())
        val state = BookshelfGroupState(saved, groups, backgroundScope)
        val values = mutableListOf<Long>()
        backgroundScope.launch { state.selected.toList(values) }

        runCurrent()
        assertEquals(1L, values.last())
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.bookshelf.BookshelfGroupStateTest"`
Expected: 编译错误（`BookshelfGroupState` 未定义）。

- [ ] **Step 3: 写实现**

创建 `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupState.kt`：

```kotlin
package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

/**
 * 书架分组状态宿主：分组列表流 + 选中分组（宿主快照 + 面板乐观覆盖），
 * 合并为对外唯一选中流。结构与语义镜像 [BookshelfStyleState]。
 *
 * - [submit] 同步置覆盖：切组渲染即时生效，不等待宿主落库
 *  （saveTabPosition 经 DataStore 原子提交）；
 * - 覆盖在宿主快照追平后清除：落库完成与流发射之间的空窗不回跳旧值；
 * - 选中组不在已加载列表中（被隐藏/删除，含加载后清空）时回退「全部」：
 *   避免 chip 与列表失联导致空书架假象；列表未加载（尚未出现过非空
 *   列表，空列表视为未加载）时保持快照值不误回退。
 *
 * 快照流与分组流均为纯输入，本类不修改它们（纯函数式合并 + 一处
 * 覆盖清除副作用，可在纯 JVM 测试中验证——AndroidViewModel 不可直测）。
 */
internal class BookshelfGroupState(
    savedSelected: Flow<Long>,
    groupsFlow: Flow<List<BookshelfGroupUiModel>>,
    scope: CoroutineScope,
) {
    private val _override = MutableStateFlow<Long?>(null)

    /** 分组列表直通（宿主一次映射快照，模块零计算）。 */
    val groups: Flow<List<BookshelfGroupUiModel>> = groupsFlow

    /**
     * 分组列表的加载投影：首个非空列表出现前为 null（未加载），此后
     * 直通当前列表（含空列表 = 全部隐藏）。scan 状态随收集器独立，
     * 不跨收集器共享。
     */
    private val loadedGroups: Flow<List<BookshelfGroupUiModel>?> =
        groupsFlow.scan(null as List<BookshelfGroupUiModel>?) { loaded, groups ->
            if (loaded != null || groups.isNotEmpty()) groups else loaded
        }

    /** 合并后的对外选中流：覆盖优先，追平回快照，失联回退「全部」。 */
    val selected: Flow<Long> =
        combine(savedSelected, _override, loadedGroups) { saved, override, groups ->
            val effective = override ?: saved
            if (groups != null && groups.none { it.groupId == effective }) {
                BookshelfGroupIds.ALL
            } else {
                effective
            }
        }

    init {
        scope.launch {
            savedSelected.collect { saved ->
                if (_override.value == saved) _override.value = null
            }
        }
    }

    /** 提交切组：同步置覆盖，宿主落库由调用方另行发起。 */
    fun submit(groupId: Long) {
        _override.value = groupId
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.bookshelf.BookshelfGroupStateTest"`
Expected: PASS（4 用例）。

- [ ] **Step 5: 提交**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupState.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/BookshelfGroupStateTest.kt
git commit -m "feat(eink): 书架分组选中状态宿主（乐观覆盖+失联回退）"
git diff --check
```

---

### Task 4: 书架 VM 接线（分组流并入 UiState）

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt`

**Interfaces:**
- Consumes: Task 1 契约端口与常量、Task 3 `BookshelfGroupState`。
- Produces: `BookshelfUiState` 新字段 `groups: List<BookshelfGroupUiModel> = emptyList()`、`selectedGroupId: Long = BookshelfGroupIds.ALL`、`groupSelectorAvailable: Boolean = false`；VM 新公开成员 `groupState: BookshelfGroupState`、`fun selectGroup(groupId: Long)`、`fun moveGroup(groupId: Long, up: Boolean)`。

- [ ] **Step 1: UiState 扩展字段**

`BookshelfUiState`（同文件 L38-52）在 `style` 字段后追加：

```kotlin
    /** 分组选择模型（未注册分组端口时恒空，选择器不渲染）。 */
    val groups: List<BookshelfGroupUiModel> = emptyList(),

    /** 当前选中分组（[BookshelfGroupIds.ALL] = 全部平铺）。 */
    val selectedGroupId: Long = BookshelfGroupIds.ALL,

    /** 分组端口已注册（选择器可渲染）；false 时书架维持现状平铺。 */
    val groupSelectorAvailable: Boolean = false,
```

- [ ] **Step 2: VM 接分组状态与组内书流**

`BookshelfViewModel` 中：

1. 顶部新增字段（`styleState` 之后）：

```kotlin
    // 分组端口可选：未注册（旧宿主）时组流退化为空、选中恒「全部」，
    // 书架维持现状平铺（诚实退化，不伪造空分组）
    private val groupEngine get() = EInkEngineRegistry.bookshelfGroupEngine

    /** 分组状态宿主（列表 + 选中乐观覆盖，见 [BookshelfGroupState]）。 */
    val groupState = BookshelfGroupState(
        savedSelected = groupEngine?.selectedGroup
            ?: kotlinx.coroutines.flow.flowOf(BookshelfGroupIds.ALL),
        groupsFlow = groupEngine?.observeGroups()
            ?: kotlinx.coroutines.flow.flowOf(emptyList()),
        scope = viewModelScope,
    )
```

（import 收敛：文件头部补 `import io.legado.app.eink.contract.BookshelfGroupIds`、`import io.legado.app.eink.contract.BookshelfGroupUiModel`、`import io.legado.app.eink.contract.BookshelfGroupEngine` 不需要——经 registry 类型推断；`flowOf` 已可用则去全限定。）

2. 现有 `uiState`（L90-105）替换为（文件头部补 import：`kotlinx.coroutines.ExperimentalCoroutinesApi`、`kotlinx.coroutines.flow.distinctUntilChanged`、`kotlinx.coroutines.flow.flatMapLatest`、`kotlinx.coroutines.flow.flowOf`）：

```kotlin
    // 组内书流：选中「全部」走现状 observeShelf 路径（零行为变化）；
    // 其余值（含未分组 -100、用户组）走分组端口。distinctUntilChanged
    // 防止选中流重发同值导致 Room 重订阅
    @OptIn(ExperimentalCoroutinesApi::class)
    private val booksFlow = groupState.selected
        .distinctUntilChanged()
        .flatMapLatest { groupId ->
            if (groupId == BookshelfGroupIds.ALL) {
                engine.observeShelf()
            } else {
                groupEngine?.observeGroupBooks(groupId) ?: engine.observeShelf()
            }
        }

    val uiState: StateFlow<BookshelfUiState> =
        combine(
            combine(booksFlow, _isRefreshing, _updatingUrls) { books, refreshing, updating ->
                Triple(books, refreshing, updating)
            },
            styleState.style,
            combine(groupState.groups, groupState.selected) { groups, selected ->
                groups to selected
            },
        ) { frame, style, selection ->
            val (books, refreshing, updatingUrls) = frame
            val (groups, selectedGroupId) = selection
            BookshelfUiState(
                books = books,
                isLoading = false,
                isRefreshing = refreshing,
                updatingBookUrls = updatingUrls,
                isGridLayout = style.isGridLayout,
                style = style,
                groups = groups,
                selectedGroupId = selectedGroupId,
                groupSelectorAvailable = groupEngine != null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfUiState())
```

3. 新公开动作（`updateStyle` 之后）：

```kotlin
    /** 切换分组：乐观置选中，宿主 saveTabPosition 异步落库。 */
    fun selectGroup(groupId: Long) {
        groupState.submit(groupId)
        viewModelScope.launch { groupEngine?.setSelectedGroup(groupId) }
    }

    /** 排序模式 ▲▼：转发宿主 moveGroup（每击即写），乐观重排在面板层。 */
    fun moveGroup(groupId: Long, up: Boolean) {
        viewModelScope.launch { groupEngine?.moveGroup(groupId, up) }
    }
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

Run: `./gradlew.bat :modules:eink:testDebugUnitTest`
Expected: PASS（Task 1/3 用例不回归）。

- [ ] **Step 4: 提交**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt
git commit -m "feat(eink): 书架 VM 并入分组流（选中切组+组内书流+退化恒平铺）"
git diff --check
```

---

### Task 5: Shelf Selector UI（chip + 展开面板 + 排序模式）

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/ShelfSelectorPanel.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/designsystem/navigation/EInkTopBar.kt`（标题尾槽）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt`（空态文案参数）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt`（装配 chip/面板/分页键）
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/ShelfSelectorMoveTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `BookshelfUiState.groups/selectedGroupId/groupSelectorAvailable`、`viewModel.selectGroup/moveGroup`；设计系统 `EInkTopBar`、`EInkButton`、`EInkText`、`EInkHorizontalDivider`、`EInkPageArrows`、`rememberEInkListPagerState`、`einkClickable`。
- Produces: `EInkTopBar` 新参数 `titleTrailing: (@Composable () -> Unit)? = null`；`BookshelfScreen` 新参数 `emptyMessage: String = "书架为空"`；`internal fun moveGroupInList(groups, groupId, up): List<BookshelfGroupUiModel>`；`@Composable fun ShelfGroupChip(text, expanded, onClick)`；`@Composable fun ShelfSelectorPanel(groups, selectedGroupId, onSelectGroup, onMoveGroup, onDismiss)`。

- [ ] **Step 1: 写排序重排纯函数失败测试**

创建 `modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/ShelfSelectorMoveTest.kt`：

```kotlin
package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfGroupUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** 排序模式 ▲▼ 的面板乐观重排（真值收敛由宿主流负责）。 */
class ShelfSelectorMoveTest {

    private fun group(id: Long) = BookshelfGroupUiModel(id, "组$id", 1)

    @Test
    fun `上移与相邻行交换`() {
        val list = listOf(group(1L), group(2L), group(3L))
        assertEquals(
            listOf(2L, 1L, 3L),
            moveGroupInList(list, 2L, up = true).map { it.groupId },
        )
    }

    @Test
    fun `下移与相邻行交换`() {
        val list = listOf(group(1L), group(2L), group(3L))
        assertEquals(
            listOf(1L, 3L, 2L),
            moveGroupInList(list, 3L, up = false).map { it.groupId },
        )
    }

    @Test
    fun `边界与未知 id 原样返回`() {
        val list = listOf(group(1L), group(2L))
        assertEquals(list, moveGroupInList(list, 1L, up = true))
        assertEquals(list, moveGroupInList(list, 2L, up = false))
        assertEquals(list, moveGroupInList(list, 99L, up = true))
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.bookshelf.ShelfSelectorMoveTest"`
Expected: 编译错误（`moveGroupInList` 未定义）。

- [ ] **Step 3: 写 ShelfSelectorPanel 组件**

创建 `modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/ShelfSelectorPanel.kt`：

```kotlin
package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.navigation.EInkHorizontalDivider
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.interaction.einkClickable
import kotlinx.coroutines.launch

/**
 * 排序模式 ▲▼ 的乐观重排（纯函数）：与相邻行交换，边界/未知 id 原样返回。
 * 真值收敛由宿主 observeGroups 重发完成（LaunchedEffect 清除乐观层）。
 */
internal fun moveGroupInList(
    groups: List<BookshelfGroupUiModel>,
    groupId: Long,
    up: Boolean,
): List<BookshelfGroupUiModel> {
    val index = groups.indexOfFirst { it.groupId == groupId }
    if (index < 0) return groups
    val target = index + if (up) -1 else 1
    if (target !in groups.indices) return groups
    return groups.toMutableList().apply { add(target, removeAt(index)) }
}

/**
 * 书架选择器收起态 chip：跟在顶栏「书架」标题后，显示当前分组名。
 *
 * 样式为描边加重（加粗边框 + 标题级字重，用户 2026-09-16 定案偏好，
 * 不用反色实心）：2dp 描边区别于常规 1dp 元素，▾/▴ 指示展开态。
 * E-Ink 约束：静态绘制，零动画零阴影。
 */
@Composable
fun ShelfGroupChip(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = EInkTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(start = EInkSpacing.s)
            .border(width = 2.dp, color = colors.onSurface, shape = EInkShapes.medium)
            .einkClickable(
                role = Role.Button,
                onClickLabel = "选择分组",
                onClick = onClick,
            )
            .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = if (expanded) "$text ▴" else "$text ▾",
            style = EInkTheme.typography.titleSmall,
        )
    }
}

/**
 * 书架选择器面板（顶栏下方锚定浮层，三态一个组件）：
 *
 * 1. 选择态：行 = 分组名 + 书数，当前组 ✓ 加粗；点行即切组并收起；
 * 2. 排序态（面板头「排序」进入）：行尾 ▲▼ 上/下移分组，每击即调
 *    [onMoveGroup]（宿主 moveGroup 每击落库），「完成」退出（无提交语义）；
 * 3. 分组行列表整页翻页（EInkListPagerState，禁自由滚动——弹层列表
 *    同书架铁律），组数不満一页时翻页箭头置灰。
 *
 * 浮层锚定内容区顶部（挂在 HomeScreen 内容 Box 内，位于顶栏之下、
 * 底部操作栏之上），面板外点击收起；书列表不重排、书架分页状态不动。
 * 排序乐观层：▲▼ 点击先本地重排，宿主 observeGroups 重发即清除
 * （失败时下次分组表变化收敛，spec §6）。
 */
@Composable
fun ShelfSelectorPanel(
    groups: List<BookshelfGroupUiModel>,
    selectedGroupId: Long,
    onSelectGroup: (Long) -> Unit,
    onMoveGroup: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var sortMode by rememberSaveable { mutableStateOf(false) }
    var sortOverride by remember { mutableStateOf<List<BookshelfGroupUiModel>?>(null) }

    // 真值流重发（含自身落库成功/失败/无关计数变化）→ 丢弃乐观层
    LaunchedEffect(groups) { sortOverride = null }

    val display = sortOverride ?: groups
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 透明点击层：点面板外空白收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .einkClickable(role = Role.Button, onClickLabel = "收起面板", onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.6f)
                .background(EInkTheme.colorScheme.surface)
                // 消费面板内空白点击，避免透传到关闭层
                .einkClickable(onClick = {}),
        ) {
            // 面板头：标题 + 排序/完成
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EInkText(
                    text = if (sortMode) "调整分组顺序" else "选择分组",
                    style = EInkTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                EInkButton(
                    text = if (sortMode) "完成" else "排序",
                    onClick = {
                        sortOverride = null
                        sortMode = !sortMode
                    },
                    bordered = true,
                )
            }
            EInkHorizontalDivider()
            // 分组行列表（整页翻页，禁自由滚动）
            val pager = rememberEInkListPagerState(display.size)
            LazyColumn(
                state = pager.listState,
                userScrollEnabled = false,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(display) { group ->
                    val index = display.indexOfFirst { it.groupId == group.groupId }
                    if (sortMode) {
                        SortRow(
                            group = group,
                            canUp = index > 0,
                            canDown = index < display.lastIndex,
                            onMove = { up ->
                                sortOverride = moveGroupInList(display, group.groupId, up)
                                onMoveGroup(group.groupId, up)
                            },
                        )
                    } else {
                        SelectRow(
                            group = group,
                            selected = group.groupId == selectedGroupId,
                            onClick = {
                                onSelectGroup(group.groupId)
                                onDismiss()
                            },
                        )
                    }
                }
            }
            // 面板内翻页箭头（组数不満一页时两端置灰）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = EInkSpacing.xxs),
            ) {
                EInkPageArrows(
                    pageUpEnabled = pager.canPageUp(),
                    pageDownEnabled = pager.canPageDown(display.size),
                    onPageUp = { scope.launch { pager.pageUp() } },
                    onPageDown = { scope.launch { pager.pageDown(display.size) } },
                )
            }
        }
    }
}

/** 选择行：✓ 当前组（加粗）+ 分组名 + 书数。 */
@Composable
private fun SelectRow(
    group: BookshelfGroupUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .einkClickable(role = Role.Button, onClickLabel = group.name, onClick = onClick)
            .padding(horizontal = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            EInkText(text = "✓", style = EInkTheme.typography.titleSmall)
            Spacer(modifier = Modifier.padding(start = EInkSpacing.xxs))
        }
        EInkText(
            text = group.name,
            style = if (selected) EInkTheme.typography.titleSmall
            else EInkTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        EInkText(
            text = "${group.bookCount} 本",
            style = EInkTheme.typography.bodyMedium,
        )
    }
}

/** 排序行：分组名 + 书数 + 行尾 ▲▼（边界置灰）。 */
@Composable
private fun SortRow(
    group: BookshelfGroupUiModel,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (up: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = group.name,
            style = EInkTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        EInkText(text = "${group.bookCount} 本", style = EInkTheme.typography.bodyMedium)
        MoveArrow(text = "▲", enabled = canUp, onClickLabel = "上移${group.name}") { onMove(true) }
        MoveArrow(text = "▼", enabled = canDown, onClickLabel = "下移${group.name}") { onMove(false) }
    }
}

/** 排序箭头：48dp 触控目标，禁用态中灰不可点（einkClickable 无 enabled 参数，禁用时干脆不挂点击）。 */
@Composable
private fun MoveArrow(
    text: String,
    enabled: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = Modifier
            .height(48.dp)
            .padding(horizontal = EInkSpacing.xs)
            .then(
                if (enabled) {
                    Modifier.einkClickable(
                        role = Role.Button,
                        onClickLabel = onClickLabel,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = text,
            style = EInkTheme.typography.titleSmall,
            color = if (enabled) colors.onSurface else colors.outline,
        )
    }
}
```

（import 与 API 以仓库实际为准修正：`border` 用 `androidx.compose.foundation.border` import；`EInkButton`/`EInkPageArrows`/`einkClickable`/`EInkHorizontalDivider` 的实际包路径对照仓库既有用法；`EInkText` 的 `color`/`maxLines` 参数签名以组件定义为准。）

- [ ] **Step 4: 运行排序纯函数测试**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.bookshelf.ShelfSelectorMoveTest"`
Expected: PASS。

- [ ] **Step 5: EInkTopBar 加标题尾槽**

修改 `modules/eink/src/main/java/io/legado/app/eink/designsystem/navigation/EInkTopBar.kt`：

1. 参数列表（`titleStyle` 之后）追加：

```kotlin
    titleTrailing: (@Composable () -> Unit)? = null,
```

KDoc `@param` 区追加：

```kotlin
 * @param titleTrailing 标题尾部内容槽（如首页书架的分组 chip）；仅普通
 *   标题分支渲染（可点击标题分支暂无消费方，需要时再扩展）
```

2. 非点击标题分支（`Box(weight(1f)) { BasicText(...) }`）改为：

```kotlin
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (actionsFillMax) Modifier.fillMaxHeight() else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = title,
                        style = (titleStyle ?: EInkTheme.typography.titleLarge)
                            .copy(color = colors.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = EInkSpacing.m),
                    )
                    titleTrailing?.invoke()
                }
```

（`Box` 换 `Row`：trailing 内容在标题文本右侧、动作区之前；标题仍占满剩余宽。）

- [ ] **Step 6: BookshelfScreen 空态文案参数**

`BookshelfScreen.kt`：

1. `BookshelfScreen` 参数加 `emptyMessage: String = "书架为空"`，`EmptyBookshelf` 调用改为 `EmptyBookshelf(modifier = Modifier.fillMaxSize(), message = emptyMessage)`。
2. `EmptyBookshelf` 改为：

```kotlin
@Composable
private fun EmptyBookshelf(modifier: Modifier = Modifier, message: String) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        EInkText(message, style = EInkTheme.typography.bodyLarge)
    }
}
```

- [ ] **Step 7: HomeRoute 装配**

修改 `HomeRoute.kt`：

1. 面板状态（`showStylePanel` 旁）：

```kotlin
    // 书架分组选择器显隐（UI 局部状态，同 showStylePanel 纪律：
    // 不随导航栈保存，切 Tab/离开首页即收起）
    var showGroupSelector by remember { mutableStateOf(false) }
```

2. Tab 切换收起（`onSelectTab` 内）：

```kotlin
            onSelectTab = { target ->
                if (selectedTab != target) {
                    showStylePanel = false
                    showGroupSelector = false
                }
                selectedTab = target
            },
```

3. 分页几何键加选中分组（切组后分页状态重建、回第一页）：

```kotlin
    val listPager = rememberEInkListPagerState(orientation, listCoverHeight, uiState.selectedGroupId)
    val gridPager = rememberEInkGridPagerState(
        orientation,
        uiState.style.gridCoverWidth,
        uiState.style.titleMaxLines,
        uiState.selectedGroupId,
    )
```

4. `HomeScreen(...)` 调用新增（`headerTitle` 相关参数旁）：

```kotlin
            titleTrailing = if (selectedTab == HomeTabs.BOOKSHELF && uiState.groupSelectorAvailable) {
                {
                    val groupName = uiState.groups
                        .firstOrNull { it.groupId == uiState.selectedGroupId }?.name ?: "全部"
                    ShelfGroupChip(
                        text = groupName,
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
                        groups = uiState.groups,
                        selectedGroupId = uiState.selectedGroupId,
                        onSelectGroup = viewModel::selectGroup,
                        onMoveGroup = viewModel::moveGroup,
                        onDismiss = { showGroupSelector = false },
                    )
                }
            },
```

5. `BookshelfScreen(...)` 调用新增空态文案：

```kotlin
                    emptyMessage = if (uiState.selectedGroupId == BookshelfGroupIds.ALL) "书架为空"
                    else "此分组暂无书籍",
```

（import：`io.legado.app.eink.contract.BookshelfGroupIds`、`io.legado.app.eink.feature.bookshelf.ShelfGroupChip`、`ShelfSelectorPanel`——同包 feature.bookshelf 无需后两者 import。）

6. `HomeScreen` 无状态外壳签名追加（`pageArrows` 旁）：

```kotlin
    titleTrailing: (@Composable () -> Unit)? = null,
    contentOverlay: @Composable () -> Unit = {},
```

`EInkTopBar(...)` 调用处传 `titleTrailing = titleTrailing`；内容 `Box(modifier = Modifier.weight(1f))` 尾部（两个 `HomePane` 之后）加 `contentOverlay()`。

- [ ] **Step 8: 编译与全模块测试**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

Run: `./gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL。

Run: `./gradlew.bat :modules:eink:testDebugUnitTest`
Expected: PASS（含 Task 1/3/5 全部用例）。

- [ ] **Step 9: 提交**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/ShelfSelectorPanel.kt \
        modules/eink/src/main/java/io/legado/app/eink/designsystem/navigation/EInkTopBar.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfScreen.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/bookshelf/ShelfSelectorMoveTest.kt
git commit -m "feat(eink): 书架选择器 UI（顶栏 chip+竖向展开面板+分组排序）"
git diff --check
```

---

### Task 6: 契约文档、AAR 核查与全量验证

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`（或该目录 README.md 中端口清单——以实际承载端口文档的文件为准，两者都有则同步）

**Interfaces:**
- Consumes: Task 1-5 全部产出。
- Produces: 文档更新；验证结论（命令输出记录在交付说明）。

- [ ] **Step 1: 契约文档补端口条目**

在端口清单的可选端口区（`marksEngine`/`selectionEngine` 条目旁，格式对照既有条目）追加：

```markdown
### BookshelfGroupEngine（可选：书架分组浏览）

未注册 = 宿主无分组浏览能力，书架选择器不渲染（书架维持全量平铺），
不参与 install 必填校验。

- `observeGroups(): Flow<List<BookshelfGroupUiModel>>` — 分组选择模型
  （show>0 行按 order 排 + 组内计数；hideEmptyGroups 过滤，「全部」恒在）。
- `observeGroupBooks(groupId): Flow<List<BookshelfItemUiModel>>` — 组内书籍流
  （映射语义同 observeShelf；组 bookSort>=0 覆盖全局排序）。
- `selectedGroup: Flow<Long>` / `setSelectedGroup(groupId)` — 选中分组与宿主
  `saveTabPosition` 共享记忆（跨模式一致）。
- `moveGroup(groupId, up)` — 排序 ▲▼ 唯一写通道：与相邻行交换，每次点击
  即写 `book_groups.order`，结果经 observeGroups 重发。

宿主义务：位掩码运算封闭在实现内；`BookshelfGroupIds.ALL = -1` 为
「全部」约定值（宿主 `BookGroup.IdAll`）。
```

- [ ] **Step 2: AAR 版本核查**

Run: `grep -rn "version" modules/eink/build.gradle.kts | head -5`（或库版本所在配置）
判定：当前 0.4.0 尚未 publish（见记忆 eink-aar-state）→ 本次契约面随 0.4.0 一并发布，**不另升版**；若发现 0.4.0 已发布，则升 minor（0.5.0）并在交付说明记录。把结论写进交付说明。

- [ ] **Step 3: 全量验证**

Run: `./gradlew.bat :app:compileAppDebugKotlin :modules:eink:testDebugUnitTest :app:testAppDebugUnitTest lintAppDebug verifyConfigArchitecture --continue --no-configuration-cache`
Expected: BUILD SUCCESSFUL；`testAppDebugUnitTest` 无新增失败（分支既有 5 个失败见记忆 md3-port-branch-known-failures，勿误归因）。

Run: `git diff --check`
Expected: 无输出。

- [ ] **Step 4: 提交**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md \
        modules/eink/src/main/java/io/legado/app/eink/contract/README.md
git commit -m "docs(eink): 书架分组端口契约文档"
git diff --check
```

（只 add 实际修改的文件；未动 README 则不加。）

- [ ] **Step 5: 交付说明记录真机清单**

不建真机步骤为任务（本环境无设备），但交付说明必须列出 spec §7 真机清单待办：
选择器开合（浮层不重排书列表、分页状态不动）；排序落库后完整模式 GroupManageSheet 顺序一致（双向对拍）；空分组显隐随宿主 hideEmptyGroups；≥64 组面板整页翻页；组内进阅读返回后停留原组原页。

---

## Self-Review 记录

- **Spec 覆盖**：spec §3 契约（Task 1，含 selectedGroup/setSelectedGroup 为 spec §5「saveTabPosition 共享记忆」的契约落点）、§4 宿主实现（Task 2）、§5 模块结构与三态（Task 3/4/5，含分页几何键、空态文案、刷新不随组收窄——updatableBooks 未动即满足）、§6 退化边界（Task 4 恒平铺 + Task 5 不渲染；并发删组由 LaunchedEffect 清乐观层覆盖；moveGroup 失败不抛 Task 2）、§7 测试（各任务单测 + Task 6 全量验证与真机清单）、§8 不做（无对应任务，正确）。
- **占位符**：无 TBD/TODO；两处「以仓库实际为准修正 import/API」为实施期对齐指示，代码主体完整。
- **类型一致性**：`BookshelfGroupEngine` 五成员在 Task 1 定义、Task 2 实现签名一致；`BookshelfGroupState(savedSelected, groupsFlow, scope)` Task 3 定义与 Task 4 构造一致；`moveGroupInList` Task 5 定义与测试一致；`BookshelfGroupIds.ALL` 常量贯穿。
