# 墨水屏书架分组管理设计

日期：2026-10-14 初稿；2026-10-15 修订（筛选器独立策展、管理就地化、结构保持透镜、组内无筛选入口）
状态：已定稿（2026-10-15 用户确认：精选集顺序按推荐、建组走归组面板轻量行、组内筛选入口消失但作用于内容）
分支预期：`eink/port/md3/modules`（宿主）+ `eink/lib`（eink-lib 子模块）

## 1. 背景与问题

宿主初始化 13 个系统预置分组。eink 模式的分组端口 `BookshelfGroupEngine` 是纯只读，分组管理全部依赖完整模式 UI；且 `show`/`order` 的控制面只在完整模式 `GroupManageSheet`，eink 内无收口手段。用户已明确：13 个筛选器太多。

目标：eink 模式内提供精简的分组闭环——筛选器精选、用户组书架聚合、就地管理，交互适配墨水屏约束（无拖拽、按钮化、少跳转、少入口）。

## 2. 机制事实（分组体系三层模型）

### 2.1 系统组 = 谓词过滤器

13 个组全部为负数 ID，在 `AppDatabase.onOpen` 幂等插入（`app/src/main/java/io/legado/app/data/AppDatabase.kt:343-435`），常量见 `data/entities/BookGroup.kt:30-45`。**无存储成员**，由 `BookDao.flowByGroup` 按组 ID 分发到 SQL 谓词实时计算（`data/dao/BookDao.kt:40-59`，计数口径 L879-897）：

| 组 | ID | 谓词 | 视角 |
|---|---|---|---|
| 在读 | -20 | `0 < durChapterIndex < totalChapterNum-1` | 阅读进度 |
| 未读 | -21 | `durChapterIndex=0 且 durChapterPos=0` | 阅读进度 |
| 已读 | -22 | `durChapterIndex >= totalChapterNum-1` | 进度（= 连载已读 ∪ 完本已读） |
| 连载已读 | -23 | 已读 且 `canUpdate=1` | 进度 × 完结性 |
| 完本已读 | -24 | 已读 且 `canUpdate=0` | 进度 × 完结性 |
| 小说 / 漫画 | -8 / -7 | `type & text` / `type & image` | 内容形态 |
| 更新失败 | -11 | `type & updateError` | 运行状态 |
| 本地 / 音频 | -2 / -3 | `type & local` / `type & audio` | 载体 |
| 网络未分组 | -4 | 非本地非音频 且无用户组位 | 归组状态 |
| 本地未分组 | -5 | 本地 且无用户组位 | 载体 × 归组状态 |
| 全部 | -1 | 全部书（计数与「未分组」去重） | 无 |

推论：系统组不可改名（语义上）、不可解散、不可手动归书；eink 一律采用**默认本地化命名**，不展示宿主库内的自定义名。

### 2.2 用户组 = 位掩码集合

`groupId > 0` 的组，ID 为 2 的幂（上限 63 个，`canAddGroup`），书籍的 `group` 列是位掩码——**一本书可同时属于多个用户组**（完整模式即此语义）。清位 SQL：`update books set `group` = `group` - :group where `group` & :group > 0`（BookDao.kt:861）。

### 2.3 派生层

标签规则（`TagGroupRule` 正则，按 `groupName` 关联组）与 AI 自动分组，落点都是把书写进用户组位掩码。

## 3. 设计决策

| # | 决策 | 结论 |
|---|---|---|
| D1 | 归组入口 | **长按书籍改为弹上下文菜单**（详情 / 加入分组…）；菜单首项保留详情 |
| D2 | 归组语义 | **仅勾选并存**：勾/取消即加入/移出；不做「设为唯一」；契约只留 `setBookGroupMembership` |
| D3 | 双入口模型 | **筛选与展示彻底分离**：筛选面板 = 系统筛选器**精选集**（eink 独立策展）；展示 = 用户组以聚合条目直接进书架。用户组**不进筛选面板** |
| D4 | 筛选器策展（2026-10-17 菜单重构） | 筛选入口 = **顶栏右侧图标按钮**（无筛选时仅筛选图标；激活时图标+筛选名），点击弹**右锚定下拉菜单**：顶部一行简略筛选描述（「筛选 · 当前名」）、选项列表（选中项 = 左 3dp 竖线 + 加粗 + 末尾 ✓）、底部「管理筛选器」项。菜单数据 = 「无筛选」（NONE=0，恒在首位、计数=全书架口径）+ **show=1 系统筛选器按 `order`**（默认本地化命名、hideEmpty 空计数不展示与宿主一致）。「管理筛选器」面板罗列**全部系统筛选器**（含隐藏），行 = 位次+名称+显隐开关+▲▼，退出一次性整表提交系统行 `order`+`show`（与完整模式同写路径）；「无筛选」为固定默认态不参与管理 |
| D5 | 双模式顶层视图 | **无筛选（默认）= 文件夹视图**：可见（show=1）非空分组条目置顶（按宿主 `order`）+ 未归组书；**任何筛选（含「全部」）= 平铺视图**：全量匹配书单（含已归组书）扁平展示，**无任何分组条目**。组视图只能自文件夹视图进入（筛选激活时无条目可点） |
| D6 | ~~结构保持透镜~~（2026-10-16 废除） | 筛选即平铺透镜：筛选视图 = `flowByGroup(筛选器)` 全量书单；文件夹结构仅存在于无筛选态 |
| D7 | 筛选的作用域 | 筛选**入口只在顶层**；组视图内容恒为全组（无筛选语义，`observeGroupBooks` 不再带 filterId）；筛选与组视图互斥——筛选激活时无条目可进入组 |
| D8 | 隐藏判据与显隐（2026-10-16 修订） | 书籍从顶层隐藏 iff 属于**任一可见（show=1）用户组**（掩码 = `sum(groupId) where groupId>0 and show=1`）——只被隐藏组收纳的书平铺回顶层，杜绝「组隐藏书消失」。用户组显隐由**调整分组面板**管理（写宿主 `show` 位，跨模式一致）；隐藏组条目不进文件夹视图，但仍可排序/恢复显示；解散仍是唯一删除手段 |
| D9 | 聚合条目形态 | 网格：一个条目 = **嵌套封面拼贴磁贴** + 组名；列表：一行 = 拼贴磁贴（与封面等高等宽）+ 组名。拼贴（2026-10-16 修订）：磁贴 2×2 四份——左上/右上/左下显示组内前三本书封面，**右下份递归 2×2** 显示第四至第七本（共 7 封面上限，不再更深），不足留空白占位；封面取组内按 `durChapterTime DESC` 前 7 本（`ifnull(customCoverUrl, coverUrl)` 口径、带 origin 参与加载），**无图封面用书名+作者文字封面**（同普通封面条目，空白象限仅用于书籍数量不足时）；**左下角完整矩形角标**（与磁贴左下角完全重合、无内边距，白天主题白底黑字/夜间随主题反转）显示书籍数量。宿主 `BookGroup.cover` 字段不再被 eink 消费。计数语义不变：无筛选=组大小，筛选=组内匹配数 |
| D10 | 展开与退出 | 点击聚合条目 = 进组视图（复用分页重建、空态文案机制）；**退出组视图 = 系统返回键/手势**（筛选状态保持）；组视图顶栏标题区显示组名，**筛选 chip 隐藏** |
| D11 | 条目管理（就地化，2026-10-16 修订） | **长按聚合条目 → 菜单：重命名分组 / 解散分组 / 调整分组**（此顺序与文案）。「调整分组」打开**全量调整面板**：所有用户组（含隐藏组，隐藏组行名置灰）一屏列出（超屏整页翻页），每行 = 位次序号 + 组名 + 显隐开关 + ▲▼（**不显示书数保持简洁**），▲▼ 与全局相邻行换位，**排序与显隐连续调整、退出时一次性整表提交 `order=index` + `show`**（与完整模式同写路径）；打开时定位到长按所在组的页。**不设独立分组管理屏**——创建入口在归组勾选面板顶部 |
| D12 | 端口形态 | **直接重写 `BookshelfGroupEngine`**：读路径按新语义收敛，同一端口承载筛选、聚合、管理 |
| D13 | 第一期范围 | 不做：系统组显隐/排序/改名（eink 内）、组封面上传管理、组内排序 `bookSort`、私有组 `isPrivate`、标签规则编辑、AI 分组、批量多选归组 |

## 4. 交互设计

### 4.1 筛选面板（系统筛选器精选集，仅顶层）

- 顶栏分组 chip → 面板，形态不变（`ShelfSelectorPanel` 平铺 chip、点选即切、点外收起）；chip 显示当前筛选器名（默认「全部」）；
- 内容：只列精选系统筛选器（默认命名 + 全书架口径计数），不列用户组、不受宿主 `show`/`order` 影响；hideEmpty 过滤、「全部」常驻；
- 选择筛选器即刻作用于顶层视图（D6 门控规则）；**组视图内 chip 与面板不可用**（隐藏）。

### 4.2 书架视图（顶层门控 + 组视图）

| 范围 | 筛选 | 内容 | 筛选入口 |
|---|---|---|---|
| 全部 | 无 | 全部非空组条目（组大小计数）+ 未归组书籍 | 有（chip） |
| 全部 | 激活 | 匹配非空组条目（组内匹配数计数）+ 匹配的未归组书籍 | 有（chip） |
| 组内 | 继承（不可改） | 组内匹配书籍（无筛选 = 全组） | **无** |

- 网格条目 = 组封面（`BookGroup.cover` 已有值时）或文字磁贴 + 组名 + 计数；列表条目 = 组名 + 计数一行；
- 点击条目 = 进组视图；**退出 = 系统返回键/手势**（回到顶层，筛选状态保持）；组视图顶栏标题区显示组名；
- **长按条目 → 菜单：重命名（输入对话框）/ 上移 / 下移 / 解散（确认对话框，文案「书籍保留，仅移出该分组」）**。

### 4.3 长按书籍与归组面板（P1，2026-10-15 激活）

- 长按书籍 → 菜单（详情 / 加入分组…）；菜单首项保留详情（原 P0 行为为直达详情）；
- 「加入分组…」→ 组勾选面板：**顶部「新建分组」行**（输入名称即建组并勾选当前书，eink 内唯一创建入口；桥层建组前校验 64 组上限，满组 IAE→toast）+ 每个用户组一行一个勾选框，实时回显位掩码归属；勾/取消即写库（乐观更新 + 回流对账）；
- 不提供批量多选。

## 5. 接口契约

### 5.1 分层

1. **宿主领域层（已存在，不动）**：`BookGroupMutationGateway`（addGroup/saveGroup/deleteGroup）+ `UpdateBooksGroupUseCase`（位掩码 transform 封闭在内）。eink 写路径必须委派这两个件，不走新 SQL。

2. **eink 端口（重写 `eink/contract/BookshelfGroupEngine.kt`）**：

```kotlin
// 顶层（全部范围）混合内容：书籍条目 | 用户组聚合条目
@EInkImmutable sealed interface BookshelfShelfEntryUiModel {
    @EInkImmutable data class Book(val book: BookshelfItemUiModel) : BookshelfShelfEntryUiModel
    @EInkImmutable data class Group(
        val groupId: Long,
        val name: String,
        val bookCount: Int,         // 上下文计数：无筛选=组大小，筛选=组内匹配数
        val coverUrl: String?,      // 组封面字段；空则 UI 渲染文字磁贴
    ) : BookshelfShelfEntryUiModel
}

interface BookshelfGroupEngine {
    // —— 筛选（精选系统筛选器；eink 独立策展，不看宿主 show/order）——
    fun observeFilters(): Flow<List<BookshelfGroupUiModel>>
    val selectedFilter: Flow<Long>                     // ALL | 系统筛选器 ID
    suspend fun setSelectedFilter(filterId: Long)

    // —— 范围（全部 | 用户组）——
    val selectedGroup: Flow<Long>                      // ALL | 用户组 ID
    suspend fun setSelectedGroup(groupId: Long)

    // —— 内容 ——
    fun observeShelfWithGroups(): Flow<List<BookshelfShelfEntryUiModel>>   // 无筛选文件夹视图
    fun observeFilteredShelf(filterId: Long): Flow<List<BookshelfShelfEntryUiModel>> // 筛选平铺视图
    fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>>  // 组视图（全组）

    // —— 分组管理（仅用户组；对系统组调用返回显式错误）——
    suspend fun createGroup(name: String)
    suspend fun renameGroup(groupId: Long, name: String)
    suspend fun applyGroupAdjustments(orderedGroupIds: List<Long>, shownGroupIds: Set<Long>) // 调整面板退出时整表提交
    suspend fun dissolveGroup(groupId: Long)
    suspend fun setBookGroupMembership(bookUrl: String, groupId: Long, member: Boolean)
    fun observeBookGroupMembership(bookUrl: String): Flow<Set<Long>>  // 勾选面板回显；仅用户组位
}
```

宿主桥 `BookshelfGroupEngineImpl` 的装配与写路径映射：

| 端口方法 | 委派目标 / 装配 | 备注 |
|---|---|---|
| observeFilters | 精选集固定 ID 列表 + `flowSystemGroupCounts` + 默认命名 | 精选集定义在桥层常量（成员顺序后续可调）；hideEmpty 过滤、「全部」常驻 |
| observeShelfWithGroups(filterId) | 条目：全部用户组按 `order`，筛选激活时按组内匹配数门控（0 → 隐藏）；书籍：未归组 ∩ 筛选 | 组内匹配数与未归组∩筛选查询走动态 SQL（`SimpleSQLiteQuery`，谓词常量从 BookDao 抽取共享）；未归组谓词 `(sum(groupId) where groupId>0) & `group` = 0`；isNotShelf 过滤、排序口径同现 `observeShelf`；无筛选路径与既有 `flowUserGroupBookCount` 一致 |
| observeGroupBooks(groupId, filterId) | filterId=ALL 走既有 `flowUserGroupBooks`；否则动态查询 `(`group` & :gid) > 0 AND <谓词>` | 结果等价于 `flowUserGroupBooks(gid)` ∩ `flowByGroup(filterId)`（测试兜底） |
| applyGroupAdjustments | `upGroup` 整表 order+show 重排 | 与完整模式同写路径；桥层校验提交集合与当前用户组集合一致、显隐集 ⊆ 提交集（IAE）；面板本地连续调整、退出一次性提交 |
| createGroup | `addGroup(NewBookGroup(...))` | 桥层先查重（`getByName` 命中且 `groupId > 0` 视为冲突，系统组库内中文名不算）**并校验 64 组上限**（`canAddGroup`，满组 IAE）；归组面板路径建组后立即归书（一个事务或紧邻两步） |
| renameGroup | `saveGroup(BookGroupUpdate)` | 仅改 `groupName`，ruleToSave/ruleIdToDelete 传 null |
| dissolveGroup | `deleteGroup` | 事务内已含：删同名标签规则 → 清书位 → 删组行 |
| setBookGroupMembership | `UpdateBooksGroupUseCase.updateGroups` | transform：`old or bit` / `old and bit.inv()` |

「全部+无筛选」分支从 `BookshelfEngine.observeShelf` 迁移到 `observeShelfWithGroups(ALL)`；若 `observeShelf` 此后无其他调用方则收敛移除。混合列表条目即 item，走既有分页与 `EInkPagerInput`。

3. **eink ViewModel（UDF）**：书架 UiState 持有筛选/范围两个状态 + 内容流按「范围×筛选」切换（组视图 chip 隐藏由 UiState 派生）；「范围+筛选」组合作为分页几何键组成部分（切换即重建分页回第一页）；条目长按菜单与解散确认走 Intent/Effect；归组面板状态（组列表 + 当前勾选集 + 新建行）独立 `@Stable` UiState + 单一 `onIntent`。

4. **持久化**：`selectedFilter` 与 `selectedGroup` 两个独立持久化键（设置 Gateway）；从现单值 `saveTabPosition` 迁移——旧值为系统 ID → 归 `selectedFilter`，用户组 ID → 归 `selectedGroup`，ALL → 双 ALL。

### 5.2 语义规则

1. 位掩码运算封闭在宿主桥实现内，eink 契约只见语义化操作，组 ID 对 eink 除 `ALL=-1` 外不透明；
2. 系统组能力受限是机制事实：对系统组调 rename/move/dissolve/归书返回显式错误，不静默；eink 不写系统组的任何属性（`show`/`order`/名）；
3. 解散的事务语义归宿主网关，eink 只发意图收结果；
4. 乐观更新 + Flow 回流对账；组视图中的组被解散时范围回退「全部」（复用 `BookshelfGroupState` 失联回退模式；筛选状态保持不变）；
5. 建/改名重名冲突走 Effect 反馈；
6. 筛选状态唯一且持久化，作用域覆盖顶层与组内容，但**编辑入口仅在顶层**；进组不改筛选，退组不清筛选；条目排序写宿主 `order`，筛选器策展为桥层常量。

## 6. 分期落地

- **P0 双入口拆分**：精选筛选面板 + 顶层结构保持透镜（条目门控）+ 书架聚合条目（含点击进组、返回退出、组内无筛选入口、长按重命名/上移/下移/解散）。存量用户组立即获得展示与管理；
- **P1 长按书籍菜单 + 归组勾选面板 + 排序面板**（2026-10-15 激活）：长按书菜单（详情/加入分组）、归组勾选面板（顶部新建行 + 多组勾选）、长按组菜单收敛三项（重命名/排序/解散）+ 全量排序面板（▲▼ 全局相邻换位、退出一次性整表提交；端口 `moveGroup` 移除、新增 `applyGroupOrder`）。

每期独立可发布；端口一次性重写到位。

## 7. 测试与验证

- 桥层纯函数 JVM 测（沿用 `buildGroupUiModels` / `BookshelfGroupEngineImplTest` 形态）：精选集装配、混合列表装配（条目置顶、D8 隐藏判据、筛选门控与上下文计数、hideEmpty）；
- 查询等价测试：`observeGroupBooks(gid, f)` = `flowUserGroupBooks(gid)` ∩ `flowByGroup(f)`；筛选门控计数 = 交集计数（防动态 SQL 谓词漂移）；
- 状态机测（筛选/范围互不干扰、组内不可改筛选、解散回退范围不回退筛选、乐观更新，沿用 `BookshelfGroupStateTest` 形态）；
- 契约非法操作用例（系统组 rename/move/dissolve/归书报错）；
- 真机复核项：残影下的上移/下移反馈、长按条目菜单与翻页器/双 Tab 手势冲突、组条目磁贴视觉一致性、筛选切换时条目门控的增减反馈、返回手势退出组视图与 predictive back 兼容、组视图 chip 隐藏后的顶栏布局。

## 8. 范围外（明确不做，本期）

系统组显隐/排序/改名（eink 内，完整模式保留）、组封面上传管理（渲染既有 `cover` 字段）、组内排序 `bookSort`、私有组 `isPrivate`、标签规则编辑、AI 分组、批量多选归组、独立分组管理屏、组内筛选 UI。

## 9. 风险与未验证项

- **筛选门控的动态查询成本**：筛选激活时每组一条匹配计数查询（combine 模式与现 `flowUserGroupBookCount` 同形，用户组 ≤ 63，可接受）；墨水屏上筛选切换的整屏刷新代价需真机感受；
- **严格文件夹语义的可达性**（D5/D8）：活跃阅读的已归组书不在顶层平铺，入口 = 组条目 / 筛选门控后的条目 / 搜索；真机验证后再定是否放宽；
- **长按书菜单是既有交互变更**（原直达详情一步变两步）：菜单首项=详情缓解，真机复核；
- **混合列表改造面**：`BookshelfScreen` 网格/列表渲染、空态文案（顶层无筛选/顶层筛选/组内 三种空态）、`BookshelfItemUiModel` 其他消费方需排查；
- **返回手势依赖**：退出组视图走系统返回，需验证 eink 双 Tab 常驻结构与 predictive back 的兼容；
- **改名与同名标签规则关联断裂**：宿主既有行为，eink 沿用不修；
- **order 并发窗口**：eink 与完整模式同时重排存在丢更新可能，乐观对账缓解；
- **端口重写为破坏性变更**：宿主桥与 eink-lib 同版本发布（子模块指针纪律覆盖）；
- **不自动改用户数据**：宿主 `show` 位不被 eink 读写；存量库的系统组涌出问题由精选集直接绕开。
