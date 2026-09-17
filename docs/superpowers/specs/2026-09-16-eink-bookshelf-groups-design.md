# E-Ink 书架分组 Shelf Selector 设计

- 日期：2026-09-16
- 分支：`md3/port/eink`
- 状态：设计定稿，待实施
- 范围：E-Ink 模式书架的分组浏览（查看/切换/排序）；分组管理（建组、删组、书归组）
  仍在完整模式完成，不在本设计范围
- 关联：`modules/eink/contract/EINK-PORTING.md`（端口契约纪律）、
  `docs/superpowers/specs/2026-09-09-eink-bookshelf-settings-passing-design.md`
  （书架设置投影先例）、`modules/eink/src/main/java/io/legado/app/eink/feature/bookshelf/`
  （现状书架实现）

## 1. 目标与决策记录（2026-09-16 用户拍板）

1. **只做展示 + 分组排序**：eink 侧浏览分组、切换分组、调整分组顺序；建组/删除/
   书归组/AI 分组/标签规则均不做（完整模式职责）。
2. **呈现形态 = Shelf Selector（书架选择器），唯一形态**：放弃分组筛选条与文件夹
   卡两种早先候选（用户反馈"卡片不方便"，方案重提为选择器）；书架本体始终是
   平铺书列表（网格/列表 + 整页翻页），分组经顶栏选择器切换。
3. **【已作废，见 §9 修订】排序按钮 = 分组顺序调整**：选择器面板内「排序」进入排序模式，行尾 ▲▼
   移动分组位置，写入 `BookGroup.order`（宿主表）。此条扩展了决策 1 的写入面：
   eink 写分组顺序，其余管理仍只读。
4. **契约面 = 新可选端口**：新增 `BookshelfGroupEngine` 可选端口
   （同 `marksEngine` / `selectionEngine` 先例），未注册 = 无分组能力，
   选择器整体不渲染（诚实退化，不伪造空分组）。

## 2. 宿主数据层事实（调研结论，全部现成）

- 分组为**位掩码模型**：`book_groups` 表（`BookGroup` 实体，`groupId` 为 2 的幂，
  上限 64 个用户组位），书挂组经 `Book.group: Long` 位运算
  （`` `group` & :group > 0 ``）。虚拟分组（全部 -1、未分组 -100、本地 -2、
  有声 -3 等）是建库时种入的表行（`AppDatabase.kt` 迁移 insert），语义同普通行。
- 查询面（`BookDao`）：`flowByGroup(IdAll)`（全量，eink 现状使用）、
  `flowBookShelfByUserGroup(groupId)`（用户组过滤，含私有组豁免——**实现时需核对
  豁免行为与完整模式一致**）、`flowRoot()`（未分组）、
  `flowSystemGroupCounts()` / `flowUserGroupBookCount(groupId)`（空分组计数）。
- 组装先例（宿主 `BookshelfViewModel`）：`groupsFlow = bookGroupRepository.flowShow()`
  （`show > 0`，按 `order` 排）；`hideEmptyGroups` 开启时按计数过滤
  （`hiddenGroupIdsFlow`，「全部」永不隐藏）；组内书籍排序经
  `BookshelfRepository.sortBooks(list, group, sort, sortOrder)`——
  `group.bookSort >= 0` 时覆盖全局排序。
- 排序写入先例：宿主 `GroupManageSheet` 拖拽排序（`GroupViewModel.upGroup` /
  `BookGroupRepository.update`）。

## 3. 契约（`modules/eink/contract/BookshelfGroupEngine.kt`，新文件）【moveGroup 已随 §9 修订移除，现行契约四成员】

```kotlin
/** 分组快照（宿主构造义务：一次映射，模块零计算，同 BookshelfItemUiModel 纪律） */
@EInkImmutable
data class BookshelfGroupUiModel(
    val groupId: Long,   // 虚拟组沿用宿主语义：-1 全部、-100 未分组等（表行）
    val name: String,
    val bookCount: Int,  // 宿主已按 hideEmptyGroups 语义处理后的可见组
)

/** 书架分组端口——可选端口（同 marksEngine / selectionEngine） */
interface BookshelfGroupEngine {
    /** 分组选择模型（实时档）：flowShow 语义 + 组内计数，按 order 排；「全部」恒可见。 */
    fun observeGroups(): Flow<List<BookshelfGroupUiModel>>

    /** 组内书籍流：映射语义同 observeShelf；组 bookSort >= 0 时覆盖全局排序。 */
    fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>>

    /** 排序模式 ▲▼ 的唯一写通道：与显示序列中相邻行交换位置；每次点击即写。 */
    suspend fun moveGroup(groupId: Long, up: Boolean)
}
```

- **`moveGroup` 语义**：每次箭头点击立即写库（不攒批），宿主实现可用邻位交换
  `order` 或整体重赋序，须保证 `order` 唯一有序；结果经 `observeGroups` 重发，
  模块侧乐观重排。「完成」按钮仅退出排序模式，无提交语义。排序范围为选择器
  列表全部行（含虚拟组行），与宿主 `GroupManageSheet` 拖拽范围同语义。
- **位掩码不出契约**：模块只见 `groupId` 与名称/计数，组位运算、私有组豁免、
  空分组隐藏全部在宿主实现内消化。
- **注册**：`EInkEngineRegistry` 增 `bookshelfGroupEngine` 可选槽位 +
  `install(..., bookshelfGroupEngine = null)` 默认参数；未注册时模块隐藏选择器，
  书架维持现状平铺（顶栏「书架 ⚙ ↻」不变），不是功能缺失。
- **AAR**：新增契约文件 + install 可选参数，既有宿主调用不破坏 → minor 升版
  （沿 0.4.x 分栈惯例）。

## 4. 宿主实现（`app/.../eink/bridge/BookshelfGroupEngineImpl.kt`，新文件）

`internal object` + KoinComponent（同 `BookshelfEngineImpl` 形态），在
`EInkBridge.install()` 注册：

- `observeGroups()`：`bookGroupRepository.flowShow()` 结合
  `flowSystemGroupCounts()` + `flowUserGroupBookCount()` 计数；`hideEmptyGroups`
  开启时过滤计数为 0 的组（「全部」不过滤，镜像宿主 `computeHiddenGroupIds`）；
  按 `order` 排序输出 `BookshelfGroupUiModel`。
- `observeGroupBooks(groupId)`：`IdAll → flowByGroup(IdAll)`、
  `IdRoot → flowRoot()`、用户组 → `flowBookShelfByUserGroup(groupId)`；
  经 `bookshelfRepository.sortBooks(books, group, bookshelfSort, sortOrder)`
  排序后映射为 `BookshelfItemUiModel`（复用 `BookshelfEngineImpl` 的既有映射）。
- `moveGroup(groupId, up)`：读 `flowShow` 序列，定位目标行与邻位行，交换/重赋
  `order` 后 `bookGroupRepository.update`（事务）。

## 5. 模块侧结构与状态（`modules/eink/feature/bookshelf/`）

**不新建 VM**，扩展现有 `BookshelfViewModel`：

```text
selectedGroupId: StateFlow<Long>   // 初始读宿主 saveTabPosition；切组时一次 update 写回
booksFlow = selectedGroupId.flatMapLatest {
    IdAll → engine.observeShelf()               // 现状路径不动
    else  → groupEngine.observeGroupBooks(it)   // 组内路径（含 IdRoot）
}
uiState = combine(booksFlow, styleState, _isRefreshing, _updatingUrls, groupsFlow)
```

- **状态记忆**：`selectedGroupId` 与宿主 `BookshelfSettings.saveTabPosition` 共享
  ——切组写回一次，冷启动读取；与完整模式「记住当前分组」跨模式一致。
- **新组件 `ShelfSelectorPanel.kt`**（feature/bookshelf 包内，三态一个文件）：
  1. **收起态**：chip 跟在 `HomeRoute` 顶栏「书架」标题后（`当前组名 ▾`；
     「全部」时显示 `全部 ▾`）。样式沿用用户在早先筛选条候选上的点选偏好
     （描边加重：加粗边框 + 加粗文字，不用反色实心），实施时以真机观感为准。
  2. **展开态**：锚定顶栏下方的浮层，行 = 分组名 + 书数，当前组 ✓；面板外点击
     收起；**书列表不重排、分页状态不动**。面板内分组列表**整页翻页**
     （`EInkListPagerState`，组多时翻页；E-Ink 禁连续滚动原则对弹层列表同样适用）。
  3. **排序态**：面板头部「排序」进入，行尾 ▲▼（顶/底行箭头置灰），每击一次
     即调 `moveGroup` 并乐观重排；「完成」退出排序模式（无提交语义）。
- **分页联动**：切组后书架分页状态重建、回第一页——分页几何键扩展
  `selectedGroupId`（列表与网格 pager 同）；`LaunchedEffect` realign 语义不变。
- **刷新**：范围仍为全部可更新书（`updatableBooks()` 不随分组收窄），
  选择器开合状态不影响刷新入口可用性（对齐宿主行为）。

## 6. 退化与边界

- 未注册 `bookshelfGroupEngine`：chip/面板不渲染，`booksFlow` 恒走 `observeShelf()`，
  顶栏与现状完全一致；不渲染空分组、不写 `saveTabPosition`。
- 只有一个可见组（仅「全部」）时：chip 仍渲染（可展开看到「全部」），不特判隐藏
  ——与宿主选择条行为一致，避免边界态闪烁。
- 排序模式中分组被完整模式并发修改（删组）：`observeGroups` 重发后排序态按新序列
  继续操作；被删组所在行自然消失，无需专门处理。
- `moveGroup` 失败（IO 异常）：宿主实现不抛出，记 `AppLog`；模块乐观重排在
  `observeGroups` 重发后回滚为库内真实序。

## 7. 测试与验证

- **模块单测**（`modules/eink/src/test/.../bookshelf/`，沿 eink 单测纪律
  ——VM 调度坑见 `runBlocking` 惯例）：
  - 组切换：`selectedGroupId` 变化触发 `flatMapLatest` 换流；`IdAll` 走
    `observeShelf`、用户组走 `observeGroupBooks`；
  - 切组写回 `saveTabPosition` 一次；
  - 排序：`moveGroup` 调用与乐观重排、失败回滚（`observeGroups` 重发覆盖）；
  - 未注册端口：VM 不构造组流、UiState 无分组字段污染。
- **宿主单测**（`:app`）：`BookshelfGroupEngineImpl` 查询分发
  （IdAll/IdRoot/用户组）、`hideEmptyGroups` 过滤（「全部」不滤）、
  `moveGroup` 落库（order 唯一有序、首/末行边界）。
- **真机清单**：选择器开合（浮层不重排书列表、分页状态不动）；排序落库后完整
  模式 `GroupManageSheet` 顺序一致（双向对拍：完整模式拖拽 → eink 面板顺序同步）；
  空分组显隐随宿主 `hideEmptyGroups`；≥64 组上面板整页翻页；从组内进阅读返回
  后停留原组原页。
- **构建验证**：`:app:compileAppDebugKotlin` + 模块测试 +
  `testAppDebugUnitTest lintAppDebug verifyConfigArchitecture`（受影响子集）。

## 8. 明确不做

- 分组管理（建/删/改组、书归组、AI 分组、标签规则）——完整模式职责。
- 文件夹卡形态、分组筛选条形态（两轮候选均被否）。
- 组内书籍排序入口（宿主 `bookshelfSort` 继续全局生效；组 `bookSort` 覆盖逻辑
  在宿主 `sortBooks` 内，模块不感知）。
- eink 侧分组搜索/过滤联动（搜索页仍全局搜书）。

## 9. 决策修订（2026-09-17 真机反馈）

1. **排序移除（决策 3 作废）**：真机验证后用户拍板 eink 侧彻底移除排序入口
   （面板排序态、契约 `moveGroup`、宿主实现与纯函数、VM 转发全部删除；
   0.4.0 未发布，不留无调用方死代码）。分组顺序调整归完整模式
   `GroupManageSheet`；端口职责收窄为分组浏览/切换 + 选中记忆
   （只读 + `setSelectedGroup` 写）。
2. **面板 = 流式 chip 平铺**：原单列行 + 上下翻页的呈现被否（真机反馈
   「上下翻页选择不方便也不美观」），面板改为 FlowRow chip 平铺：
   面板头整行删除，打开即见分组；每个分组一个 chip（文案 `组名 ·N`，
   N=书数，含「全部」），**当前选中组 chip 反色实心**（onSurface 底 +
   background 字，无描边），其余 1dp 描边常规字重；整页兜底 16 chip/页，
   翻页 = 整页替换（零动画），页脚 `x/y 页` 小字 + EInkPageArrows
   （仅一页时两箭头置灰仍渲染）。
