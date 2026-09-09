# E-Ink 书架属性配置传递设计（策划快照方案）

- 日期：2026-09-09
- 分支：`md3/port/eink`
- 状态：设计定稿；2026-09-09 第三轮修订（标题最大行数读取生效）
- 判定基准：E-Ink 书架当前形态——扁平全量书架（无分组/文件夹）、网格/列表双布局
  （E-Ink 分页模式，禁自由滚动）、黑白主题自持、禁动画禁阴影
- 关联：`modules/eink/docs/eink-host-config-review.md`（阅读界面配置评审，判定框架来源）、
  `docs/dev/eink-companion-reader-plan.md`（插件计划，双宿主归宿）
- 决策记录（2026-09-09 用户拍板）：
  1. `bookshelfRefreshingLimit` 主动忽略，E-Ink 保持全量刷新；
  2. `bookshelfLayoutGridPortrait`（网格列数）读取生效，列宽按屏宽自适应；
  3. 布局切换入口不开放，列表/网格只读跟随宿主，不反向写。
- 决策修订（2026-09-09 二轮拍板，实施中确认宿主网格策略为「列数主导 +
  封面宽上限，两滑杆无联动，横竖屏分键」后发现列数驱动使封面宽设置失义、
  横竖屏行为分裂）：
  4. 网格改为**列宽主导**：改吃 `bookshelfGridCoverWidth`（封面宽滑杆
     40..150dp），格宽以该值为最小格宽自适应，列数自然推导；列数键
     `bookshelfLayoutGridPortrait` 回归主动忽略；
  5. 书架布局切换入口回归（默认仍随宿主），切换**反向写**宿主
     `bookshelfLayoutModePortrait`（竖屏键；横屏键不动）——覆盖原决策 3。
- 决策修订（2026-09-09 三轮拍板）：
  6. 网格标题最大行数读取生效：`bookshelfTitleMaxLines`；`bookshelfTitleSmallFont`
     与 `bookshelfTitleCenter` 主动忽略——E-Ink 已使用 14sp 最小字号且固定
     居中，支持字体档会放大字体、支持对齐会破坏现有网格观感。maxLines
     钳制 1..5 并参与网格行高与分页重测。
- 决策补充（2026-09-09 三轮拍板确认：书架个性化配置面板）：
  7. 顶栏布局切换按钮改为打开**个性化配置面板**——`EInkDialog` 居中
     卡片、关 scrim 实时预览，样式参考阅读页排版面板（`SliderRow` 档位
     滑条 + `EInkButton(selected)` 按钮行）；可配置：布局、未读角标、
     新章高亮、最新章节、封面宽、书名行数六项；
  8. **写通道收敛**：`setGridLayout` 移除，新增
     `setStyle(style: BookshelfStyle)`——模块传改后完整快照，宿主一次
     原子 update 写六个键（镜像 `ReaderEngine.applyStyle` 形态）；
  9. 分页重测继续走 inputs 键重建并扩展（网格 += 封面宽/书名行数，
     列表 += 最新章节显隐）；`remeasure()` 无生产调用方，随本轮从
     `EInkPageController` 接口移除。

## 1. 文档定位

回答一个问题：**宿主书架设置（`BookshelfSettings` 48 键）中，哪些进入 E-Ink
书架生效、哪些主动忽略、哪些支持在 E-Ink 侧修改？并给出承载通道的契约形态。**

背景：2026-09-06 逐键转发方案被否（宿主书架设置多，逐键走「契约+桥接+消费」
三处维护面失控），要求先做整体方案。本文即该整体方案：一次性给出 48 键的
完整判定与策划快照契约，后续新增生效键时按 §6 的演进规则扩展。

判定级别沿用阅读界面评审：L1 书架核心（直接决定浏览体验）/ L2 书架支撑
（保障刷新与内容可靠性）/ L3 壳层行为（宿主界面形态）。

## 2. 结论摘要

| 分类 | 数量 | 内容 |
|---|---|---|
| 已传递（现状固化） | 1 | `autoRefreshBook`（GlobalSettings 读写，「我的」页开关） |
| 读取生效 | 8 | 排序双键、未读角标双键、最新章节行、布局模式、网格封面宽、网格标题最大行数 |
| 主动忽略 | 27 | 配色/阴影/尺寸/横屏/信息密度/标题字体档与对齐/网格样式等，逐组依据见 §5 |
| 功能面不存在 | 12 | 分组/文件夹体系全部键，eink 无此 UI 面 |

核心决策：

1. **通道 = 策划快照**：契约 `BookshelfStyle`（6 字段），经
   `BookshelfEngine.style: Flow<BookshelfStyle>` 传递；排序在宿主 bridge
   内消化，不进契约（§3）。
2. **网格列宽主导**（决策修订 4）：封面宽设置是格宽的最小值语义，列数
   由模块按可用宽推导；列数键回归忽略。
3. **唯一书架写入口 = 布局切换**（决策修订 5）：切换入口在 eink 首页
   顶栏，默认值随宿主，切换反向写宿主竖屏键；`autoRefreshBook` 维持现状。
4. **生效档位**：快照全部为实时档（Flow 驱动，宿主改设置 E-Ink 立即重组）。

## 3. 通道设计

### 3.1 数据流

```text
宿主（:app eink/bridge/）                          模块（:modules:eink）
─────────────────────────                        ─────────────────────
BookshelfSettingsGateway.settings: Flow<BookshelfSettings>
        │
        ├─ map { it.toBookshelfStyle() }          BookshelfEngine.style
        │  （映射义务见 §4.2；钳制在此层）  ──►     = Flow<BookshelfStyle>
        │                                          │ stateIn / 组合期订阅
        │                                          ▼
        │                                        BookshelfUiState / BookshelfScreen
        │
        └─ combine(bookDao.flowAll(), settings)
               { books, s -> books.按 s 排序 }     BookshelfEngine.observeShelf
               .map { Book → ItemUiModel }   ──►   = Flow<List<BookshelfItemUiModel>>
                                                   （已排序，模块零计算）
```

两条腿独立发射：样式变化只重组渲染参数，排序变化重发书籍列表；
`distinctUntilChanged` 由映射层保证，避免同值重排打断分页状态。

### 3.2 排序在 bridge 消化（不进契约）

排序是数据序而非渲染参数：`cnCompare` 中文比较器是宿主工具，不外泄；
模块继续消费排好序的列表，维持 `BookshelfItemUiModel` KDoc 固化的
「宿主构造义务、模块零计算」纪律。

- `observeShelf` 从 `appDb.bookDao.flowByGroup(IdAll).map { 映射 }` 改为
  combine 设置流后按 `bookshelfSort`/`bookshelfSortOrder` 排序再映射。
- 排序语义与 `BookshelfRepository.sortBooks` 一致（六模式 × 升降序）：
  0 阅读时间（durChapterTime）、1 更新时间（latestChapterTime）、2 书名
  （cnCompare）、3 手动（`order` 字段）、4 max(更新,阅读) 时间、5 作者
  （cnCompare）；`sortOrder == 1` 为降序。
- **手动排序必须显式排序**：`flowAll()` 的 DAO 自然序是
  `durChapterTime desc`，与手动序无关；现状「eink 顺序恰好等于默认排序
  （阅读时间降序）」是巧合，本设计用显式排序取代巧合。
- View 版的 per-group `bookSort` 覆盖不适用：eink 无分组语义，只用全局键。
- `bookshelfRefreshingLimit` 不消费（决策 1）：E-Ink 保持全量刷新。刷新并发
  已由 `threadCount`（GlobalSettings，钳模块内上限）与
  `MAX_REFRESH_CONCURRENCY = 64` 约束，全刷是 E-Ink 的刻意行为。

### 3.3 契约类型定义

`modules/eink` `contract/BookshelfStyle.kt`（新增）：

```kotlin
/**
 * 书架显示样式快照：宿主书架设置中对 E-Ink 生效的策划子集投影。
 *
 * 映射纪律（宿主构造义务）：
 *  - 字段按模块语义命名，不照搬宿主键名；宿主键到字段的对应关系见各成员
 *    KDoc，宿主实现不得扩大或收窄语义；
 *  - [gridCoverWidth] 的非法值钳制在本层完成，模块收到的值恒可用；
 *  - 快照为实时档：宿主设置变化后经 Flow 发射新值，模块组合期订阅，
 *    立即重组（与 [GlobalSettings.useDefaultCover] 的快照状态档同语义）。
 *
 * 双宿主归宿：嵌入式宿主投影 `BookshelfSettingsGateway`；插件宿主无宿主
 * 书架设置可投影，发射模块默认值的静态快照即合法实现（不是功能缺失，
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
    val showUnreadBadge: Boolean,

    /**
     * 本次目录刷新发现新章时角标是否反色高亮（宿主 `showUnreadNew`）。
     *
     * 模块侧组合规则与 View 版一致：高亮 = [showUnreadBadge] && 本字段 &&
     * hasNewChapter；未读角标整体隐藏时高亮随之无载体。
     */
    val highlightNewChapter: Boolean,

    /**
     * 列表条目是否显示最新章节行（宿主 `bookshelfShowLatestChapter`）。
     *
     * false 时列表条目隐藏该行，剩余信息行按既有 SpaceBetween 结构重排；
     * 网格条目本无该行，不受影响。
     */
    val showLatestChapter: Boolean,

    /**
     * 书架默认布局：true = 网格，false = 列表（宿主 `bookshelfLayoutModePortrait`，
     * 0 = 列表、非 0 = 网格）。
     *
     * 默认值随宿主（实时档）；E-Ink 个性化配置面板可切换，经
     * [BookshelfEngine.setStyle] 反向写宿主竖屏键（横屏变体
     * 不投影，E-Ink 按竖屏形态设计）。
     */
    val isGridLayout: Boolean,

    /**
     * 网格封面宽（dp）：格宽的最小值语义（宿主 `bookshelfGridCoverWidth`，
     * 宿主滑杆范围 40..150，默认 120）。
     *
     * 网格列数由模块按可用宽推导，使每格不小于该值、富余均摊（列数随
     * 屏宽/旋转自适应，封面保持 66:90 比例随格宽伸缩）。宿主映射义务：
     * 值 <= 0 时回落 120，不做其他钳制。仅 [isGridLayout] = true 时消费。
     */
    val gridCoverWidth: Int = 120,

    /** 网格标题最大行数（宿主 `bookshelfTitleMaxLines`，钳制 1..5）。 */
    val titleMaxLines: Int = 2,
)
```

### 3.4 端口签名

`BookshelfEngine` 新增成员：

```kotlin
/**
 * 书架显示样式快照流（策划子集投影，实时档；成员语义见
 * [BookshelfStyle]）。模块在 VM 层 stateIn 后并入 UiState。
 */
val style: Flow<BookshelfStyle>

/**
 * 提交书架显示样式（个性化配置面板的唯一写通道，决策补充 8）。
 *
 * 模块传改后完整快照，宿主经设置网关一次原子 update 写六个键
 * （showUnread / showUnreadNew / bookshelfShowLatestChapter /
 * bookshelfLayoutModePortrait 竖屏键 / bookshelfGridCoverWidth /
 * bookshelfTitleMaxLines；横屏键不动）。返回即落库完成，写入成功后
 * [style] 重发新快照。模块 UI 乐观更新，不等待本方法返回。
 */
suspend fun setStyle(style: BookshelfStyle)
```

不新增独立引擎：样式与书架数据同生命周期，挂在 `BookshelfEngine` 是最窄归属。
`GlobalSettings` 不新增任何键（收录规则不破坏：个性化面板是书架面控件而非
设置页条目，读写同归书架端口）。

## 4. 读取生效组消费语义（8 键）

| 宿主键 | 契约/消化位置 | 模块消费规则 | 生效档 |
|---|---|---|---|
| `bookshelfSort` | bridge 排序 | 无模块消费（数据已排序） | 实时（列表流重发） |
| `bookshelfSortOrder` | bridge 排序 | 同上 | 实时 |
| `showUnread` | `BookshelfStyle.showUnreadBadge` | 网格/列表角标渲染开关 | 实时 |
| `showUnreadNew` | `BookshelfStyle.highlightNewChapter` | 角标高亮组合条件之一 | 实时 |
| `bookshelfShowLatestChapter` | `BookshelfStyle.showLatestChapter` | 列表条目最新章节行显隐 | 实时 |
| `bookshelfLayoutModePortrait` | `BookshelfStyle.isGridLayout` | 书架默认布局；布局切换反向写本键（§6） | 实时 |
| `bookshelfGridCoverWidth` | `BookshelfStyle.gridCoverWidth` | 网格最小格宽，列数自适应推导 | 实时 |
| `bookshelfTitleMaxLines` | `BookshelfStyle.titleMaxLines` | 网格标题最大行数（1..5），改变网格条目高度 | 实时，需重测分页 |

实施要点：

1. **个性化配置面板**（决策补充 7/8）：eink 首页顶栏书架 Tab 按钮
   （图标 `eink_ic_interface_setting`，与阅读页「排版」同款语义）点击
   打开面板——`EInkDialog` 居中卡片、关 scrim 实时预览。面板行：
   布局（网格/列表双枚 `EInkButton(selected)`）、未读角标 / 新章高亮 /
   最新章节（三行双枚按钮）、封面宽（`SliderRow` 40..150 步进 5）、
   书名行数（`SliderRow` 1..5 步进 1）。每档变化即经
   `engine.setStyle(style.copy(...))` 落库（档位滑条离散步进，非连续
   拖动）；VM 乐观层 `_styleOverride` 先行，落库完成后清除。
2. **网格列宽主导渲染**：列数由纯函数按可用宽推导
   `adaptiveGridColumns(availableWidth, minCellWidth)`——扣除左右内容边距
   （16dp×2）后，`floor((有效宽 + 列距) / (minCellWidth + 列距))`、至少
   1 列（列距 16dp 同 `GridCells` 约束解）；渲染用 `GridCells.Fixed(推导
   列数)`，格宽用既有 `bookshelfGridCellWidth` 均分公式单点解析——显示
   与预取的缓存键一致性链条不变。默认 120dp 封面宽在 360dp 手机推导
   2 列、617dp 七英寸墨水屏推导 4 列。
3. **封面尺寸同源**：显示与预取（HomeRoute 下一页封面预热）必须用同一
   格宽推导公式（缓存键一致性，既有约束）。
4. **分页几何重测**：沿用 inputs 键重建机制并扩展（决策补充 9，
   `remeasure()` 接口随本轮移除）——网格分页状态以
   （orientation, gridCoverWidth, titleMaxLines）为键、列表分页状态以
   （orientation, showLatestChapter）为键：任一输入变化即重建分页状态、
   页首回第一页并按新几何重测（`EInkMainActivity` 旋转不重建，此路径
   为必经；页首回第一页符合墨水屏整页阅读直觉）。
5. **网格标题最大行数**：标题固定用 `bodySmall`（14sp/16sp 紧凑档）并
   居中；`titleMaxLines` 经 `bookshelfGridTitleHeight` 计算最小高度并
   限制 `maxLines`，变化时重建网格分页状态。`bookshelfTitleSmallFont` 与
   `bookshelfTitleCenter` 主动忽略，不进入契约。

## 5. 主动忽略组（27 键）与功能面缺失组（12 键）

逐键明细（依据列引用阅读界面评审的固化决策）：

| 组 | 键 | 依据 |
|---|---|---|
| 刷新编排（1） | `bookshelfRefreshingLimit` | 决策 1：E-Ink 保持全刷；并发已有 threadCount 上限约束 |
| 配色（2） | `bookshelfCardColor`、`bookshelfCardColorDark` | 配色体系刻意不共享，E-InkTheme 黑白自持（评审 §5.3） |
| 阴影（1） | `bookshelfCoverShadow` | E-Ink 禁阴影（灰阶不可控，设计系统约束） |
| 壳层载体不存在（4） | `showBookshelfFastScroller`、`shouldShowExpandButton`、`showWaitUpCount`、`bookshelfSearchActionDirectToSearch` | 分页模式禁自由滚动 / eink 底部操作栏自成体系 / 刷新态用角标省略号表达 / eink 搜索框直达搜索页 |
| 信息密度（6） | `showBookIntro`、`bookshelfShowIntro`、`bookshelfIntroMaxLines`、`bookshelfListIntroBelowContent`、`bookshelfShowTag`、`showLastUpdateTime` | 列表条目按封面等高设计（四行 SpaceBetween），几何不容纳简介/标签/时间；诚实窄化非遗漏 |
| 尺寸（1） | `bookshelfListCoverWidth` | 列表封面固定 66:90；密度自持，与阅读页「页眉页脚字号不开放设置」同逻辑。网格封面宽 `bookshelfGridCoverWidth` 已移入生效组（决策修订 4） |
| 布局细化（5） | `bookshelfLayoutListPortrait`、`bookshelfLayoutGridPortrait`（列数，列宽主导下列数由推导产生，决策修订 4）、`bookshelfLayoutCompact`、`bookshelfListCoverCenter`、`bookshelfShowDivider` | 列表列数/紧凑/封面对齐/分隔线是 View 卡片形态细节，eink 行结构自洽 |
| 横屏（3） | `bookshelfLayoutModeLandscape`、`bookshelfLayoutGridLandscape`、`bookshelfLayoutListLandscape` | E-Ink 按竖屏形态设计 |
| 标题字体档/对齐（2） | `bookshelfTitleSmallFont`、`bookshelfTitleCenter` | E-Ink 已用 14sp 最小字号且网格标题固定居中，支持会放大字体或破坏现有网格观感，主动忽略 |
| 网格样式（1） | `bookshelfGridLayout` | 叠加样式需渐变遮罩 + 白字，灰阶不可控 |
| 类型角标（1） | `showTip` | 漫画/有声非 eink 目标场景，小字角标灰阶可读性差 |
| 功能面不存在（12） | `bookGroupStyle`、`hideEmptyGroups`、`showBookCount`、`saveTabPosition`、folder 布局 6 键、`bookshelfGroupListStyle`、`bookshelfGroupCoverCount` | eink 无分组/文件夹 UI；属功能缺口非配置缺口。未来 eink 建分组时本组重新评估，届时仍走快照扩展（§6） |

合计 1 + 8 + 27 + 12 = 48 键全覆盖。

## 6. 修改面与演进规则

**修改面**：书架写入口共两处——

1. `autoRefreshBook`（GlobalSettings，「我的」页开关，fire-and-forget、
   下次启动生效），维持现状；
2. **个性化配置面板**（决策补充 7/8）：面板六项经
   `BookshelfEngine.setStyle` 反向写宿主六键（含竖屏布局键）。只写
   竖屏键：横屏键属宿主手机形态，E-Ink 不消费也不改写。完整模式与
   E-Ink 的书架显示设置由此双向同步（一套配置）。

**快照演进规则**（后续「宿主新设置要对 eink 生效」类需求的唯一路径）：

1. 判定是否属于 eink 形态消费力度（§5 依据清单为准绳）；
2. 属于 → `BookshelfStyle` 加字段（全成员 KDoc，含宿主键对应关系）+
   bridge 映射 + 模块消费，三处一次提交；
3. 不属于 → 记入 §5 明细表依据列，不动契约。

`BookshelfStyle` 由模块定义、宿主构造：加字段即破坏全部宿主构造点，
嵌入式与插件宿主的映射必须随同一演进同步更新（对应「端口新增必须双宿主
同步实现」的既有检查清单纪律），不存在仅模块侧加默认值的静默兼容。

## 7. 边界与已知限制

1. **旋转等几何变化需重测分页**：`EInkGridPagerState.pageItemCount` 首次
   布局实测后固定（含 rememberSaveable 恢复）。`EInkMainActivity` 声明了
   `configChanges=orientation|screenSize`，旋转不重建组合——列数（格宽
   推导输入变化）与行高随之改变，实测页项数失效。处理：inputs 键重建
   （决策补充 9；`remeasure()` 接口已随本轮移除），键见 §4 要点 4。
   E-Ink 专用阅读器（竖屏锁定形态）不触发此路径。
2. **快照实时档的真实变化源**：宿主改设置、E-Ink 内布局切换反向写，均经
   快照流实时反映。
3. **排序重发与分页状态**：排序变化导致列表重排时，沿用既有
   `realignToPageStart` 数据变化对齐路径；按下标锚定（列表不用 key）的
   现状不变。
4. **插件宿主义务**：`style` 发射模块默认值静态快照、`setStyle`
   写自有 DataStore 或空实现（明示），均属合法实现（§3.3 KDoc 已注明）；
   插件形态的配置分叉见插件计划 §6.4，首次使用默认排版即出厂值，需在
   P4 验收时向用户明示。
5. **默认列数观感变化**：列宽主导后默认 120dp 封面宽在手机竖屏推导
   2 列（旧 Adaptive 96dp 为 3 列、宿主列数键默认 3）——封面更大、单屏
   书目更少，属既定语义（用户可在宿主封面宽滑杆 40..150 调节），真机
   回归时不得误判为回归。
6. **标题最大行数作用面**：`titleMaxLines` 作用于网格标题；E-Ink 列表标题
   保持单行，不改列表行结构。`titleMaxLines` 改变网格条目高度，必须重建
   网格分页状态；`bookshelfTitleSmallFont` 与 `bookshelfTitleCenter` 主动忽略。

## 8. 验证策略

| 层 | 验证 | 方式 |
|---|---|---|
| 宿主映射 | `toBookshelfStyle` 全字段映射 + gridCoverWidth/titleMaxLines 钳制；`withStyleProjection` 六键反向写投影 | JVM 纯函数单测 |
| 宿主排序 | 六模式 × 升降序与 `sortBooks` 语义对拍；手动排序不依赖 DAO 自然序 | JVM 纯函数单测（比较器镜像） |
| 模块推导 | `adaptiveGridColumns` 列数推导（含边界：可用宽不足单格、极宽屏）；`bookshelfGridCellWidth` 均分 | JVM 纯函数单测 |
| 模块消费 | style 并入 UiState（含乐观层）；角标组合规则（badge × highlight）；最新章节行显隐；面板六项提交；标题行数推导 `bookshelfGridTitleHeight` | 既有 JVM 测试基建 |
| 分页重测 | inputs 键（orientation/封面宽/书名行数/最新章节显隐）变化后分页状态重建 | designsystem 既有纯 JVM 分页测试基建 |
| 端到端 | 宿主改封面宽/排序 → eink 跟随；面板六项改动 → 完整模式书架同步变化；旋转后翻页整行对齐 | 真机手工回归（列入交付说明的未验证风险，若当轮未做） |

文本与门禁：`git diff --check`；契约/bridge 改动跑
`:app:compileAppDebugKotlin` 与 `testAppDebugUnitTest`；模块侧跑其真实
单测任务。
