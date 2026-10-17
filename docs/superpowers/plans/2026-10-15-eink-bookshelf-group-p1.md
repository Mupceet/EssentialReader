# 墨水屏书架分组 P1（长按书菜单 + 归组面板 + 排序面板）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地真机反馈三项：长按书籍菜单（详情/加入分组…）+ 归组勾选面板（顶部新建行 + 多组并存勾选）+ 分组排序面板（长按菜单收敛三项，▲▼ 全局相邻换位、退出一次性整表落库）。

**Architecture:** 复用 P0 端口（membership/createGroup 已实现未消费）；两处契约微调（`createGroup` 返回新组 id、`moveGroup`→`applyGroupOrder`）与各自消费者同任务原子落地（吸取 P0 教训，不做垫片往返）。UI 全部沿用既有 eink 组件形态（EInkDialog、ShelfSelectorPanel 分页脚手架、MenuActionRow）。

**Tech Stack:** 同 P0（Kotlin、Compose eink-lib DS、StateFlow、Room）。

**设计规格：** `docs/superpowers/specs/2026-10-14-eink-bookshelf-group-management-design.md`（2026-10-15 P1 激活修订）。
**P0 已落地基线：** 宿主 `8b7a71e7b..57e05e656`、eink-lib `696f86a50..fbfb1534`；本计划在其上继续。

## Global Constraints

（继承 P0 计划全部约束，重申关键项）
- 两仓库提交顺序：先 eink-lib（分支 eink/lib）后宿主；宿主提交带指针；只 add 本任务触碰的文件，严禁 `git add -A`。
- E-Ink 约束：静态绘制、零动画零阴影、无拖拽；弹层禁自由滚动、整页翻页（复用 `ShelfSelectorPanel` 的 chunked+页码+箭头脚手架）。
- 契约破坏性变更与消费者**同一任务**内一起提交（模块与宿主同版本发布）。
- 系统组只读：新 UI 只对用户组暴露操作；IAE → `UserMessage` toast（复用 P0 catch 链模式）。
- `BookshelfItemUiModel` 无构造默认参；Gradle 任务名 `:modules:eink:compileDebugKotlin` / `testDebugUnitTest`（已验证存在）。
- JDK 21；提交前 `git diff --check`；验证命令与 P0 相同。

---

### Task 1: 长按书菜单 + 归组勾选面板 + createGroup 返回 id 与 64 上限（双仓原子）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/BookshelfGroupEngine.kt`（仅 createGroup 一处：返回值 Unit→Long，KDoc 补「满组 IAE」「返回新组 id」）
- Modify: `app/src/main/java/io/legado/app/eink/bridge/BookshelfGroupEngineImpl.kt`（createGroup：+canAddGroup 守卫，+返回新组 id）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookshelfViewModel.kt`（归组状态块 + intents + UiState 三字段）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt`（长按拦截弹菜单 + 归组面板挂载）
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/BookAssignPanel.kt`（菜单 + 归组面板）

**Interfaces:**
- Consumes: P0 端口 `setBookGroupMembership(bookUrl, groupId, member)`、`observeBookGroupMembership(bookUrl): Flow<Set<Long>>`、`createGroup(name)`（本任务改签名 `: Long`）、`observeUserGroups()`；UiState 既有 `userGroups`。
- Produces（Task 2 依赖）: `BookshelfViewModel` intents `openAssign(book: BookshelfItemUiModel)` / `closeAssign()` / `toggleAssignMembership(groupId: Long, member: Boolean)` / `createGroupAndAssign(name: String)`；UiState 字段 `assignBook: BookshelfItemUiModel?`、`assignMembership: Set<Long>`；`MenuActionRow` 组件迁至可复用位置（BookAssignPanel.kt 内 internal，Task 2 引用）。

- [ ] **Step 1: 契约与桥（先子模块后宿主可与后续 UI 同批提交，但先改齐再编译）**

契约 `createGroup` 改为：

```kotlin
    /** 新建用户组并返回新组 id（重名/空名/满 64 组上限抛 IAE）；建组不归书，归组由调用方紧随调用。 */
    suspend fun createGroup(name: String): Long
```

桥实现（替换现有 createGroup 体）：

```kotlin
    override suspend fun createGroup(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名不能为空" }
        requireUserGroupNameFree(trimmed, excludeGroupId = null)
        val canAdd = withContext(Dispatchers.IO) { appDb.bookGroupDao.canAddGroup }
        require(canAdd) { "分组数量已达上限（64）" }
        bookGroupMutationGateway.addGroup(
            NewBookGroup(
                groupName = trimmed, bookSort = -1, enableRefresh = true,
                isPrivate = false, cover = null, pattern = null,
            )
        )
        return withContext(Dispatchers.IO) {
            appDb.bookGroupDao.getByName(trimmed)?.groupId
                ?: error("建组后未找到新组: $trimmed")
        }
    }
```

（`canAddGroup` 为 DAO 既有属性：`select count(*) < 64 from book_groups where groupId >= 0 or groupId == Long.MIN_VALUE`。）

- [ ] **Step 2: VM 归组状态块与 intents**

`BookshelfViewModel.kt`：

UiState 追加三字段（`filterSelectorAvailable` 之后）：

```kotlin
    /** 归组面板目标书（null = 面板关闭）；P1。 */
    val assignBook: BookshelfItemUiModel? = null,
    /** 归组面板当前勾选集（用户组 id；乐观覆盖 + 回流对账）。 */
    val assignMembership: Set<Long> = emptySet(),
```

状态块（groupState 之后；归组面板的组列表直接复用 `userGroups`，不另起流）：

```kotlin
    /** 归格面板状态：目标书 + 成员集（宿主流 + 乐观覆盖，目标书切换即重订）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val assignState = MutableStateFlow<BookshelfItemUiModel?>(null)
    private val assignOverride = MutableStateFlow<Set<Long>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val assignMembershipFlow = assignState
        .flatMapLatest { book ->
            book?.let { groupEngine?.observeBookGroupMembership(it.bookUrl) } ?: flowOf(emptySet())
        }
        .combine(assignOverride) { saved, override -> override ?: saved }
```

uiState combine 追加两支输入（`assignState` 与 `assignMembershipFlow`），UiState 构造填 `assignBook = assignBookVal, assignMembership = membership`。覆盖清除：collect saved 流与 override 相等时清空（镜像 BookshelfGroupState 的追平清除——在本 VM 内以一个小 combine+collect 实现，或简化为 `assignOverride` 只在 toggle 后到下次回流前生效：toggle 时置覆盖、saved 追平相同值时清空）。

Intents（selectFilter 区块后）：

```kotlin
    /** 打开归组面板（长按书菜单「加入分组」）。 */
    fun openAssign(book: BookshelfItemUiModel) {
        assignState.value = book
        assignOverride.value = null
    }

    fun closeAssign() {
        assignState.value = null
        assignOverride.value = null
    }

    /** 勾/取消勾一个组：乐观置覆盖，宿主位掩码异步落库。 */
    fun toggleAssignMembership(groupId: Long, member: Boolean) {
        val book = assignState.value ?: return
        assignOverride.value = ((assignOverride.value
            ?: uiState.value.assignMembership).let { if (member) it + groupId else it - groupId })
        viewModelScope.launch {
            try {
                groupEngine?.setBookGroupMembership(book.bookUrl, groupId, member)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }

    /** 新建分组并立即勾选当前书（归组面板顶部行）。 */
    fun createGroupAndAssign(name: String) {
        val book = assignState.value ?: return
        viewModelScope.launch {
            try {
                val newId = groupEngine?.createGroup(name) ?: return@launch
                groupEngine?.setBookGroupMembership(book.bookUrl, newId, true)
                assignOverride.value = (assignOverride.value ?: emptySet()) + newId
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _messages.emit(UserMessage.from(R.string.eink_group_name_exists))
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }
```

（字符串资源追加 `eink_group_limit_reached`：`<string name="eink_group_limit_reached">分组数量已达上限</string>`；满组 IAE 与重名 IAE 同为 IllegalArgumentException——按 message 区分太脆，统一映射 name_exists 不准确。**桥层满组改抛 `IllegalStateException`**，VM 对 ISE 单独 catch 映射 `eink_group_limit_reached`；同步调整 Step 1：`require(canAdd) { ... }` 保持 IAE 但……为可区分，满组守卫改用 `check(canAdd) { "分组数量已达上限（64）" }` 抛 ISE。VM catch 链顺序：CE → IAE(name_exists) → ISE(limit) → Exception(failed)。）

- [ ] **Step 3: 菜单与归组面板组件（新建 BookAssignPanel.kt）**

文件内容（`MenuActionRow` 从 ShelfGroupEntryMenu.kt **移动**到本文件并改为 internal 供两处复用；ShelfGroupEntryMenu.kt 同步删除私有副本改引）：

```kotlin
package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 长按书籍的上下文菜单（spec D1，P1）：详情 / 加入分组…。
 * 详情动作由调用方注入（保持既有 BookDetail 导航）。
 */
@Composable
fun BookContextMenu(
    book: BookshelfItemUiModel?,
    onOpenDetail: (BookshelfItemUiModel) -> Unit,
    onOpenAssign: (BookshelfItemUiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    if (book == null) return
    EInkDialog(onDismiss = onDismiss, title = book.name, showActions = false) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MenuActionRow("查看详情") { onOpenDetail(book) }
            MenuActionRow("加入分组…") { onOpenAssign(book) }
        }
    }
}

/**
 * 归组勾选面板（spec D2/4.3，P1）：顶部「新建分组」行 + 全部用户组勾选行。
 * 勾/取消即多组并存写库（乐观覆盖经 VM）；「移动」语义 = 手动两步。
 * 组多时整页翻页（复用筛选面板 chunked 脚手架；页脚仅多页渲染）。
 */
private const val ASSIGN_PAGE_SIZE = 8

@Composable
fun BookAssignPanel(
    book: BookshelfItemUiModel?,
    groups: List<BookshelfGroupUiModel>,
    membership: Set<Long>,
    onToggle: (groupId: Long, member: Boolean) -> Unit,
    onCreateGroup: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (book == null) return
    var newName by remember(book) { mutableStateOf("") }
    var pageInput by remember(book) { mutableIntStateOf(0) }
    val pages = groups.chunked(ASSIGN_PAGE_SIZE)
    val pageCount = pages.size.coerceAtLeast(1)
    val pageIndex = pageInput.coerceIn(0, pageCount - 1)
    val page = pages.getOrNull(pageIndex).orEmpty()

    EInkDialog(
        onDismiss = onDismiss,
        title = "加入分组",
        showActions = false,
        onClose = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 顶部新建行：输入 + 「新建并加入」按钮（空名禁用）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
            ) {
                BasicTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    textStyle = EInkTheme.typography.bodyMedium.copy(
                        color = EInkTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextActionButton(
                    text = "新建并加入",
                    enabled = newName.isNotBlank(),
                    onClick = {
                        onCreateGroup(newName)
                        newName = ""
                    },
                )
            }
            EInkHorizontalDivider()
            // 勾选行（当前页）
            page.forEach { group ->
                val checked = membership.contains(group.groupId)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .einkClickable(
                            role = Role.CheckBox,
                            onClickLabel = group.name,
                        ) {
                            onToggle(group.groupId, !checked)
                        }
                        .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBoxSquare(checked = checked)
                    EInkText(
                        text = group.name,
                        style = EInkTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = EInkSpacing.s),
                    )
                    EInkText(
                        text = "${group.bookCount} 本",
                        style = EInkTheme.typography.bodySmall,
                        color = EInkTheme.colorScheme.outline,
                    )
                }
            }
            // 页脚：多页才渲染（同筛选面板脚手架）
            if (pageCount > 1) {
                EInkHorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EInkText(
                        text = "${pageIndex + 1}/$pageCount 页",
                        style = EInkTheme.typography.bodySmall,
                        color = EInkTheme.colorScheme.outline,
                    )
                    io.legado.app.eink.designsystem.navigation.EInkPageArrows(
                        pageUpEnabled = pageIndex > 0,
                        pageDownEnabled = pageIndex < pageCount - 1,
                        onPageUp = { pageInput = pageIndex - 1 },
                        onPageDown = { pageInput = pageIndex + 1 },
                    )
                }
            }
        }
    }
}

/** 勾选方框：选中 = onSurface 实心 + 中心留白方点；未选 = 1dp outline 描边。零动画。 */
@Composable
private fun CheckBoxSquare(checked: Boolean) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = Modifier
            .size(20.dp)
            .then(
                if (checked) {
                    Modifier.background(colors.onSurface, EInkShapes.extraSmall)
                } else {
                    Modifier.border(1.dp, colors.outline, EInkShapes.extraSmall)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(colors.background, EInkShapes.extraSmall)
            )
        }
    }
}

/** 菜单/动作文本行（长按菜单、排序面板共用）。 */
@Composable
internal fun MenuActionRow(text: String, enabled: Boolean = true, onClick: () -> Unit) {
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

/** 小号文字动作按钮（新建行内）。 */
@Composable
private fun TextActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    EInkText(
        text = text,
        style = EInkTheme.typography.bodyMedium,
        color = if (enabled) EInkTheme.colorScheme.onSurface else EInkTheme.colorScheme.outline,
        modifier = Modifier
            .einkClickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.xxs),
    )
}
```

（import 补齐：`Box`、`size`、`mutableIntStateOf`、`EInkPageArrows` 顶层 import 而非内联限定。`EInkShapes.extraSmall` 以实际常量名为准，若无则用 `EInkShapes.small`。）

- [ ] **Step 4: HomeRoute 接线**

状态区追加：

```kotlin
    // 长按书菜单 / 归组面板（UI 局部状态，同面板纪律）
    var menuBook by remember { mutableStateOf<BookshelfItemUiModel?>(null) }
    var assignBook by remember { mutableStateOf<BookshelfItemUiModel?>(null) }
```

`BookshelfScreen(...)` 的 `onBookLongClick = onBookLongClick` 替换为 `onBookLongClick = { book -> menuBook = book }`（拦截原直达详情导航）。

文件尾部（ShelfGroupEntryDialogs 之后）挂载：

```kotlin
    BookContextMenu(
        book = menuBook,
        onOpenDetail = { book ->
            menuBook = null
            onBookLongClick(book)   // 既有 BookDetail 导航管线
        },
        onOpenAssign = { book ->
            menuBook = null
            viewModel.openAssign(book)
        },
        onDismiss = { menuBook = null },
    )
    if (uiState.assignBook != null) {
        BookAssignPanel(
            book = uiState.assignBook,
            groups = uiState.userGroups,
            membership = uiState.assignMembership,
            onToggle = viewModel::toggleAssignMembership,
            onCreateGroup = viewModel::createGroupAndAssign,
            onDismiss = viewModel::closeAssign,
        )
    }
```

- [ ] **Step 5: ShelfGroupEntryMenu 同步（MenuActionRow 迁移）**

删除其私有 `MenuActionRow`，改引 `BookAssignPanel.kt` 的 internal 版本（同包直引）。菜单动作不变（Task 2 再收敛三项）。

- [ ] **Step 6: 编译 + 测试 + 提交**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest`（宿主再跑 `--tests "io.legado.app.eink.bridge.*"` 确认契约改动无破坏——桥测试不触 createGroup 落库路径，纯 JVM 应仍绿）。
Expected: BUILD SUCCESSFUL。

提交（先子模块后宿主）：
- eink-lib：契约 createGroup 返回 Long + VM/菜单/面板 + HomeRoute + strings —— `feat(bookshelf): P1 长按书菜单(详情/加入分组)与归组勾选面板——多组并存勾选乐观更新、顶部新建并加入行、createGroup 返回新组 id`
- 宿主：桥 createGroup 守卫与返回 id + 指针 —— `feat(eink-bridge): createGroup 补 64 组上限守卫(canAddGroup→ISE)并返回新组 id；eink-lib 指针随 P1 推进`

---

### Task 2: 排序面板 + 端口 moveGroup→applyGroupOrder（双仓原子）

**Files:**
- Modify: `eink-lib/.../contract/BookshelfGroupEngine.kt`（删 moveGroup、加 applyGroupOrder）
- Modify: `app/.../eink/bridge/BookshelfGroupEngineImpl.kt`（删 moveGroup 实现、加 applyGroupOrder 织入重排）
- Modify: `eink-lib/.../feature/bookshelf/BookshelfViewModel.kt`（删 moveGroup intent、加 applyGroupOrder）
- Modify: `eink-lib/.../feature/bookshelf/ShelfGroupEntryMenu.kt`（菜单收敛三项：重命名/排序/解散；删 canMoveUp/canMoveDown/onMove，加 onOpenSort）
- Create: `eink-lib/.../feature/bookshelf/GroupSortPanel.kt`
- Modify: `eink-lib/.../feature/home/HomeRoute.kt`（排序面板挂载 + 菜单回调改接）
- Test: `app/src/test/java/io/legado/app/eink/bridge/BookshelfGroupEngineImplTest.kt`（删 moveGroup IAE 断言、加 applyGroupOrder 参数校验纯函数断言——若校验抽为纯函数）

**Interfaces:**
- Consumes: Task 1 的 `MenuActionRow`；P0 的重命名/解散管线不变。
- Produces: `GroupSortPanel(groups, initialFocusGroupId, onApplyOrder: (List<Long>) -> Unit, onDismiss)`；VM intent `applyGroupOrder(orderedGroupIds: List<Long>)`；桥层织入重排纯函数 `buildGroupOrderUpdate(allGroups: List<BookGroup>, orderedUserIds: List<Long>): List<BookGroup>`（internal，可纯 JVM 测）。

- [ ] **Step 1: 纯函数测试先行（织入重排）**

`BookshelfGroupEngineImplTest.kt` 追加：

```kotlin
    // ---- buildGroupOrderUpdate（applyGroupOrder 的织入重排） ----

    @Test
    fun `织入重排保持系统行位置且用户行按提交序连续编号`() {
        val all = listOf(
            group(-30L, "在读", order = 0), group(-1L, "全部", order = 1),
            group(0b1, "组1", order = 2), group(0b10, "组2", order = 3), group(0b100, "组3", order = 4),
        )
        val result = buildGroupOrderUpdate(all, listOf(0b100L, 0b1L, 0b10L))
        assertEquals(
            listOf(-30L, -1L, 0b100L, 0b1L, 0b10L),
            result.map { it.groupId },
        )
        assertEquals(List(5) { it }, result.map { it.order })  // order = 全表 index
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfGroupEngineImplTest"`
Expected: 编译失败（buildGroupOrderUpdate 未定义）。

- [ ] **Step 3: 契约 + 桥 + VM**

契约：删 `moveGroup`，加：

```kotlin
    /** 排序面板退出时一次性整表提交用户组新序（与完整模式拖拽同写路径）；提交集合与当前用户组集合不一致时 IAE。 */
    suspend fun applyGroupOrder(orderedGroupIds: List<Long>)
```

桥（替换 moveGroup 实现；`require` 拆两段，集合校验逻辑入纯函数便于测试）：

```kotlin
    override suspend fun applyGroupOrder(orderedGroupIds: List<Long>) {
        val groups = withContext(Dispatchers.IO) { appDb.bookGroupDao.all }
        require(validateGroupOrderSubmission(groups, orderedGroupIds)) { "分组列表已变化，请重试" }
        bookGroupRepository.update(*buildGroupOrderUpdate(groups, orderedGroupIds).toTypedArray())
    }
```

纯函数（Impl 文件尾部，与既有纯函数同区）：

```kotlin
/** 提交集合与当前用户组集合一致（同元素集；size 隐含）才允许落库。 */
internal fun validateGroupOrderSubmission(
    allGroups: List<BookGroup>,
    orderedUserIds: List<Long>,
): Boolean {
    val current = allGroups.filter { it.groupId > 0 }.map { it.groupId }
    return orderedUserIds.size == current.size && orderedUserIds.toSet() == current.toSet()
}

/**
 * 织入重排：系统行保持原位原序，用户行按提交序依次占据用户槽位；
* 全表 order = index（与完整模式 GroupManageSheet 拖拽落序同构）。
 */
internal fun buildGroupOrderUpdate(
    allGroups: List<BookGroup>,
    orderedUserIds: List<Long>,
): List<BookGroup> {
    val byId = allGroups.associateBy { it.groupId }
    val queue = ArrayDeque(orderedUserIds)
    return allGroups.map { group ->
        if (group.groupId > 0) byId.getValue(queue.removeFirst()) else group
    }.mapIndexed { index, group -> group.copy(order = index) }
}
```

测试文件同步：删除原 moveGroup 的 IAE 断言（`moveGroup(IdAll, 1)` 那条），新增 applyGroupOrder 相关守卫走 DB 路径不纯测（validate 纯函数可用一行夹具断言 false 分支：元素缺失时 false）。

VM：删 `moveGroup` intent，加：

```kotlin
    /** 排序面板退出：一次性提交用户组新序（面板本地已连续换位，此处整表落库）。 */
    fun applyGroupOrder(orderedGroupIds: List<Long>) {
        viewModelScope.launch {
            try {
                groupEngine?.applyGroupOrder(orderedGroupIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(UserMessage.from(R.string.eink_group_operation_failed))
            }
        }
    }
```

- [ ] **Step 4: GroupSortPanel（新建）**

```kotlin
package io.legado.app.eink.feature.bookshelf

/**
 * 分组排序面板（spec D11 修订，P1）：全量用户组一屏，每行 位次+组名+计数+▲▼；
 * ▲▼ 与全局相邻行换位（跨页边界自然流动、整表静态重绘）；打开定位到
 * [initialFocusGroupId] 所在页；退出时若顺序有变则一次性回调提交。
 * 本地状态随面板组合存活，关闭即弃（翻页/换位零落库，退出一次写）。
 */
private const val SORT_PAGE_SIZE = 8

@Composable
fun GroupSortPanel(
    groups: List<BookshelfGroupUiModel>,
    initialFocusGroupId: Long?,
    onApplyOrder: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var orderedIds by remember(groups) {  // 组集合外部变化（解散/新建）时重置
        mutableStateOf(groups.map { it.groupId })
    }
    var pageInput by remember {
        mutableIntStateOf(
            (groups.indexOfFirst { it.groupId == initialFocusGroupId }
                .takeIf { it >= 0 } ?: 0) / SORT_PAGE_SIZE
        )
    }
    val byId = remember(groups) { groups.associateBy { it.groupId } }
    val pageCount = (orderedIds.size + SORT_PAGE_SIZE - 1) / SORT_PAGE_SIZE
    val pageIndex = pageInput.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val page = orderedIds.drop(pageIndex * SORT_PAGE_SIZE).take(SORT_PAGE_SIZE)

    EInkDialog(
        onDismiss = {
            if (orderedIds != groups.map { it.groupId }) onApplyOrder(orderedIds)
            onDismiss()
        },
        title = "分组排序",
        showActions = false,
        onClose = {
            if (orderedIds != groups.map { it.groupId }) onApplyOrder(orderedIds)
            onDismiss()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            page.forEachIndexed { offset, id ->
                val index = pageIndex * SORT_PAGE_SIZE + offset
                val group = byId[id]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EInkText(
                        text = "${index + 1}",
                        style = EInkTheme.typography.bodySmall,
                        color = EInkTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = EInkSpacing.s),
                    )
                    EInkText(
                        text = group?.name ?: "",
                        style = EInkTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    EInkText(
                        text = "${group?.bookCount ?: 0} 本",
                        style = EInkTheme.typography.bodySmall,
                        color = EInkTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = EInkSpacing.s),
                    )
                    TextArrowButton("▲", enabled = index > 0) { swap(orderedIds, index, -1)?.let { orderedIds = it } }
                    TextArrowButton("▼", enabled = index < orderedIds.lastIndex) { swap(orderedIds, index, +1)?.let { orderedIds = it } }
                }
            }
            if (pageCount > 1) {
                EInkHorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EInkText(
                        text = "${pageIndex + 1}/$pageCount 页",
                        style = EInkTheme.typography.bodySmall,
                        color = EInkTheme.colorScheme.outline,
                    )
                    EInkPageArrows(
                        pageUpEnabled = pageIndex > 0,
                        pageDownEnabled = pageIndex < pageCount - 1,
                        onPageUp = { pageInput = pageIndex - 1 },
                        onPageDown = { pageInput = pageIndex + 1 },
                    )
                }
            }
        }
    }
}

/** 全局相邻换位；跨页边界后条目自然流入邻页（整表重绘、页索引夹取）。 */
private fun swap(ids: List<Long>, index: Int, delta: Int): List<Long>? {
    val target = index + delta
    if (target < 0 || target >= ids.size) return null
    return ids.toMutableList().apply { add(target, removeAt(index)) }
}

@Composable
private fun TextArrowButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    EInkText(
        text = symbol,
        style = EInkTheme.typography.titleMedium,
        color = if (enabled) EInkTheme.colorScheme.onSurface else EInkTheme.colorScheme.outline,
        modifier = Modifier
            .einkClickable(enabled = enabled, role = Role.Button, onClickLabel = symbol, onClick = onClick)
            .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.xxs),
    )
}
```

（import 补齐：`getValue/setValue/mutableStateOf/remember`、`Arrangement`、`Alignment`、`EInkHorizontalDivider`、`EInkPageArrows` 等。）

- [ ] **Step 5: ShelfGroupEntryMenu 收敛三项 + HomeRoute 接线**

`ShelfGroupEntryDialogs` 签名变化：删 `canMoveUp/canMoveDown/onMove`，加 `onOpenSort: (BookshelfShelfEntryUiModel.Group) -> Unit`；菜单行为：

```kotlin
MenuActionRow("重命名") { onSwitchToRename(menuGroup) }
MenuActionRow("排序") { onOpenSort(menuGroup) }
MenuActionRow("解散分组") { onSwitchToDissolve(menuGroup) }
```

HomeRoute：删 `menuIndex/canMoveUp/canMoveDown/onMove` 相关行；新增状态 `var sortPanel by remember { mutableStateOf<BookshelfShelfEntryUiModel.Group?>(null) }`；`ShelfGroupEntryDialogs(... onOpenSort = { group -> menuGroup = null; sortPanel = group })`；挂载：

```kotlin
    if (sortPanel != null && uiState.userGroups.isNotEmpty()) {
        GroupSortPanel(
            groups = uiState.userGroups,
            initialFocusGroupId = sortPanel?.groupId,
            onApplyOrder = viewModel::applyGroupOrder,
            onDismiss = { sortPanel = null },
        )
    }
```

- [ ] **Step 6: 测试 + 编译 + 提交**

Run: `./gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BookshelfGroupEngineImplTest" :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest`
Expected: 全绿。

提交：
- eink-lib：`feat(bookshelf): P1 分组排序面板——长按菜单收敛三项(重命名/排序/解散)、全量列表▲▼全局相邻换位、整页翻页、退出一次性整表提交；端口 moveGroup→applyGroupOrder`
- 宿主：`feat(eink-bridge): applyGroupOrder 织入重排——系统行原位、用户行按提交序、全表 order=index（与完整模式同写路径）+ 集合一致性校验；eink-lib 指针推进`

---

### Task 3: 全量验证 + 指针收口 + APK 安装

**Files:**
- Modify: 宿主 eink-lib 指针（Task 1/2 若未逐任务推）

- [ ] **Step 1: 主验证集**

Run: `./gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 指针收口（若 Task 1/2 已逐任务推则跳过）**

```bash
git -C eink-lib status --short   # 干净
git add eink-lib && git commit -m "chore(eink): 推进 eink-lib 指针——书架分组 P1（长按书菜单/归组面板/排序面板）"
```

- [ ] **Step 3: 安装到设备（3B1F4WE9B8L7Y00L，同签名直装）**

```bash
export PATH="$PATH:/c/Users/laiguizhong/AppData/Local/Android/Sdk/platform-tools"
adb install -r app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk
adb shell am start -n io.legato.kazusa.eink.debug/io.legado.app.ui.main.MainActivity
```

Expected: Success + 应用前台启动。

- [ ] **Step 4: 交付说明**（写报告：行为基线、已验证、真机待验——长按手势、归组勾选手感、排序面板跨页换位、新建行输入、上限 toast）

---

## Self-Review 记录

- Spec 覆盖：D1（长按菜单）→Task1、D2（勾选并存）→Task1、D11 修订（排序面板/三项菜单）→Task2、顶部新建行→Task1、64 上限→Task1 桥、createGroup 返回 id→Task1。
- 类型一致性：`applyGroupOrder(List<Long>)` 契约/桥/VM/面板回调四处一致；`MenuActionRow` 单一定义在 BookAssignPanel.kt（internal），ShelfGroupEntryMenu/GroupSortPanel 复用。
- 已知不确定点（步骤内注明）：`EInkShapes.extraSmall` 常量名、`EInkPageArrows` import 路径、ISE/IAE 区分方案（Step 2 已定：满组 check→ISE，VM 单独映射）。
