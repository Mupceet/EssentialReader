# E-Ink Design System v2.0 迁移审查与任务清单

依据：`modules/eink/docs/eink-design-system-spec-v2.0.md`（下称"规范"）。
审查对象：`modules/eink/src/main/java/io/legado/app/eink/` 下 `component/`（21 文件）、`theme/`（5）、`modifier/`（5）、`refresh/`（2）、`widget/`（2），共 35 个 Kotlin 文件。
审查方法：逐文件通读 + 全模块调用点统计（grep 词边界匹配，排除定义包自身）。判定遵循规范 §55"最终决定必须基于实际调用关系，而不是机械迁移"。

审查日期：2026-08-29。

---

## 1. 总体结论

现状是一个**已经高度 E-Ink 化、但体系未分层**的组件集：

- **优势（与规范一致，无需返工）**：零 Material3 依赖、零 ripple（主题根注入 `NoIndication`）、零动画、按压反色经共享 `rememberImmediatePressState`（120ms 最短保持）+ `eInkActionColors` 统一解析、disabled 用实灰不用 alpha、14sp 字号下限、固定页分页模型（`EInkPagedList` 系）成熟。
- **主要差距**：
  1. **Token 未分层**（规范 §4）：无 `grayscale` 调色板对象、无 `primaryContent/secondaryContent/border/divider/selected` 等语义角色，组件直接用 Material 风格角色名（`primary/onPrimary/surfaceVariant/outline`）。
  2. **死代码占比高**：21 个 component 文件中 9 个零外部调用（Button/Card/TextField/IconButton 全家、PaginatedList 全家、Indicator 两个变体、4 个 Text 快捷变体等）；`modifier/Grayscale.kt` 与整个 `refresh/` 包零调用。
  3. **双分页体系并存**（规范 §55 点名）：`PaginatedList`（HorizontalPager + `animateScrollToPage`，本身违反规范 §26 无滑动动画）与 `EInkPagedList/EInkGridPagedList`（固定页 + `scrollToItem`，规范 §23/§24 的正确方向）。
  4. **refresh 是空壳**：`RefreshMode.PARTIAL/FULL` 属于硬件模式词汇直接暴露给 UI（违反 §43），无 Policy/DeviceProfile/Adapter，且从未被装配。
  5. **顶栏双组件**（§29）：`EInkTopBar` 与 `EInkTopActionBar` 仅差动作区内边距。
  6. **业务组件混在通用层**（§44）：`EInkBookCover` 依赖 `EInkEngineRegistry.coverEngine`（业务端口），不属于 Design System。

调用点统计摘要（排除定义包后的真实使用文件数）：

| 高频 | 中频 | 零调用（死代码） |
|---|---|---|
| EInkText(65)、EInkOperationBarIcon(25)、EInkHorizontalDivider(20)、EInkOperationBar(5 文件)、EInkPageArrows(3)、staticClickable(35)、rememberImmediatePressState(23)、EInkPageSwipe(4 文件) | EInkTopActionBar(3)、EInkTopBar(2)、EInkLoading(5 文件)、EInkSteppedSlider(2)、EInkPagedList 系(3 文件)、EInkSearchBar 两件(2)、eInkActionColors(3)、EInkBookCover(7)、EInkInfoRow(3) | EInkButton 全家、EInkCard 全家、EInkIconButton、EInkBackButton(仅 TopBar 内部用)、EInkTextField 全家、EInkLoadingBox、EInkVerticalDivider、EInkHeadline/Title/BodyText/Label、EInkDot/NumberPageIndicator(仅被死代码 PaginatedList 引用)、PaginatedList 全家、Grayscale.kt、refresh/ 全包、rememberNoIndication、rememberNoRippleInteractionSource(仅被将删的 EInkTextField 引用) |

---

## 2. 逐文件判定

图例：**保留** = 原地不动或仅小改；**合并** = 并入另一组件；**下沉** = 移出 Design System 到 feature/宿主层；**删除** = 移除文件（git 历史可找回）；**重写** = 保留职责但按新架构重建。

### theme/

| 文件 | 判定 | 依据 |
|---|---|---|
| `EInkColors.kt` | **保留（增量改造）** | 16 灰阶 + 4 语义调色板结构良好。Phase 1 增补：`grayscale` 语义 token（规范 §1.3 推荐的 black/gray900…gray50/white 子集即可，不必全 16 级）与 `borderStrong/divider/selected` 等语义角色（§4.1）。现有 Material 风格角色名保留为兼容别名，避免一次性破坏 65+ 调用点。 |
| `EInkTheme.kt` | **保留（增量改造）** | 主题根注入 `NoIndication` + 静态光标（`LocalCursorBlinkEnabled=false`）已是规范级实现。Phase 1 增补 `EInkTheme.grayscale` / `.spacing` / `.shapes` 访问器（§46）。 |
| `EInkTypography.kt` | **保留** | 14sp 硬下限是已决策事项（不向主工程 Miuix 映射对齐），与规范 §5 笔画稳定要求一致。Reading* 角色暂不新增：阅读正文由引擎 Canvas 绘制，不走 Compose Typography，待 Compose 阅读控件出现再补。 |
| `EInkShapes.kt` | **保留** | none/small/medium/large = 0/2/4/8dp，落在规范 §6 推荐区间（Button 2–4、Card/Dialog 2–6）。 |
| `EInkSpacing.kt` | **保留** | 标准 2–48dp 刻度，无问题。 |

### modifier/

| 文件 | 判定 | 依据 |
|---|---|---|
| `StaticClickable.kt` | **保留** | 即规范 §49 的 `einkClickable()`。改名属 API churn，延后到包结构调整时一并做（届时加 `@Deprecated` 别名过渡）。 |
| `ImmediatePress.kt` | **保留** | 规范 §13 按压态的共享实现（120ms 最短保持是全站约定），是 interaction 层的核心资产。 |
| `NoRipple.kt` | **保留（删 1 函数）** | `NoIndication`（主题用）与 `NoRippleInteractionSource`（staticClickable 用）保留；`rememberNoIndication()`（零调用）与 `rememberNoRippleInteractionSource()`（唯一调用方是将删的 EInkTextField）删除。 |
| `EInkPageSwipe.kt` | **保留** | 固定页分页的手势入口（4 个屏幕在用），阈值触发 + 整页跳转、零中间态，符合 §26。后期随 Pager 架构迁入 `pager/` 支撑位。 |
| `Grayscale.kt` | **删除** | 零调用；"整体去色兜底滤镜"与受控灰阶体系（§1.2/§62：灰阶必须来自 Token 语义）方向相悖，规范也未要求该能力。 |

### refresh/

| 文件 | 判定 | 依据 |
|---|---|---|
| `RefreshTypes.kt` | **重写** | `RefreshMode.NONE/PARTIAL/FULL` 是硬件刷新模式词汇，暴露在 UI 可见 API 中违反 §43；且 `RefreshIntent` 数据类与规范 §40 的 `EInkRefreshIntent` 语义枚举（ContentStable/Interactive/PageTurn/Navigation/Overlay/TextInput/FullRedraw/ClearGhosting）冲突。零调用，重写无风险。`DirtyRegion` 保留（归入 adapter 层）。 |
| `EInkRefreshController.kt` | **重写** | 零装配、零调用。按规范 §39/§41 重建：`EInkRefreshIntent`（组件产生）→ `EInkRefreshPolicy`（intent+设备档位→策略）→ `EInkRefreshController`（调度/合批，§67/§68）→ DeviceProfile + Adapter 端口（平台侧实现，本模块只留接口）。`NoOp` 默认 + CompositionLocal 注入模式是好设计，保留。真机 Adapter 在 `:app` 侧实现（BOOX/岩芯等 SDK 不能进本模块）。 |

### component/

| 文件 | 判定 | 依据 |
|---|---|---|
| `EInkText.kt` | **保留（删 4 函数）** | 核心组件（65 处使用、三路字号下限回退成熟）。`EInkHeadline/EInkTitle/EInkBodyText/EInkLabel` 四个零调用快捷变体删除（§54 禁止轻微变体堆积）。`MIN_FONT_SIZE` 导出保留。 |
| `EInkActionColors.kt` | **保留（Phase 1 升级）** | 全站按压/选中/禁用配色的唯一解析点，规范 §12 统一 InteractionState 的现有雏形。Phase 1 将其升格为 `interaction/` 层入口（可与规范 §12 的 `EInkInteractionState` 数据类合流，但**不强行引入空状态模型**——现有 pressed/enabled/selected 三参解析已覆盖全部真实用法）。 |
| `EInkDivider.kt` | **保留（删 1 函数）** | `EInkHorizontalDivider` 20 处使用。`EInkVerticalDivider` 零调用，删除。Phase 1 后默认色改从新增的 `colorScheme.divider` 语义角色取。 |
| `EInkLoading.kt` | **保留（删 1 函数）** | `EInkLoading` 静态文案（5 文件使用）正是规范 §19 推荐形态。`EInkLoadingBox` 零调用，删除。 |
| `EInkTopBar.kt` | **保留（吸收 TopActionBar）** | 规范 §28 顶栏职责。与 `EInkTopActionBar` 合并为单组件（见下）。内联原 `EInkBackButton` 的返回图标逻辑。 |
| `EInkTopActionBar.kt` | **合并（删除文件）** | 与 TopBar 仅差：动作区无内边距、按钮贴右撑满高度、宽度收敛倍数经 CompositionLocal 降为 1.2。合并方案：`EInkTopBar` 增加动作区样式（如 `actionsEdgeAligned: Boolean` 或统一为贴右模式 + padding 参数），3 个调用方（BookDetailRoute/HomeRoute/TocScreen）随之切换。§29 禁止顶栏变体继续增殖。 |
| `EInkBackButton.kt` | **删除** | 零外部调用（仅 TopBar 内部使用），§54 点名禁止此类"IconButton 轻微变体"。逻辑内联进合并后的 TopBar。 |
| `EInkIconButton.kt` | **删除** | 零外部调用。与高频使用的 `EInkOperationBarIcon`（25 处）职责重复，且它自写反色逻辑、未走 `eInkActionColors`（违反该文件声明的全站约定）。保留 `EInkOperationBarIcon` 为唯一图标按钮。 |
| `EInkOperationBarIcon.kt` | **保留（长期更名）** | 实际的图标按钮主力：走 `eInkActionColors` 共享解析、支持选中素材对/宽度自适应/高度覆写。§54 的"统一为 EInkIconButton 通过 size/variant 组合"作为长期方向：包结构调整时更名并吸收通用尺寸参数，短期不动（25 处调用不值得为改名冒回归风险）。 |
| `EInkOperationBar.kt` | **保留** | 底部操作栏即规范 §2 的 NavigationBar/ActionBar 混合体，5 个屏幕在用；`pageArrows` 槽承载的"翻页状态收敛到叶作用域"是真实的性能取舍，保留。后期迁 `navigation/` 包。 |
| `EInkPageArrows.kt` | **保留** | PageTurn 控件（§26）：胶囊 + 竖线 + 置灰禁用 + 按压反色，8 处使用（多为 OperationBar 内嵌）。后期迁 `navigation/`。 |
| `EInkPagedList.kt` | **保留（后期迁移更名）** | 即规范 §23 的 `EInkPagerState` 列表实现 + `EInkPageController` 抽象（列表/网格无差别调用）。恢复归零、页首对齐、竞态防护等边界处理成熟，**不重写**。后期迁 `pager/` 包并更名 `EInkListPagerState` 一类（加 `@Deprecated` 别名过渡）。 |
| `EInkGridPagedList.kt` | **保留（后期迁移更名）** | 网格版 PagerState，行×列计量模型正确。同上处理。 |
| `PaginatedList.kt` | **删除** | 零调用；与 `EInkPagedList` 构成规范 §55 点名的概念重复；`animateScrollToPage` 违反 §26（无滑动动画）；`HorizontalPager` 离屏页预组合对弱 SoC 不友好。`PageIndicatorStyle` 枚举一并删除。 |
| `EInkPageIndicator.kt` | **重写（收敛）** | 两个指示器均零外部调用（仅被将删的 PaginatedList 引用）。按 §27 重写为单一 `EInkPageIndicator`（"12 / 48" 数字式为默认；点阵式对大页数不友好，删除 `EInkDotPageIndicator`）。P2 优先级——书架/目录页接入时执行。 |
| `EInkSearchBar.kt` | **保留** | 提示条/输入条同壳同位的"同一个框"设计优秀，即规范 §21 的 SearchField 落地（1dp 描边、无动画、聚焦拉起输入法）。HomeRoute/SearchScreen 在用。后期可迁 `control/` 并按需抽 `EInkSearchField`。 |
| `EInkSteppedSlider.kt` | **保留** | 离散档位 + 拇指印值 + 抬手提交（deferred-apply），完全符合 §33；`markerLabel` 静态标识是本仓真实需求（默认档位标注）。 |
| `EInkButton.kt` | **删除（按需重建）** | 全家（Button/OutlinedButton/TextButton）零调用，且自带反色逻辑未走 `eInkActionColors`。**不保留无调用方的抽象**；待出现首个真实按钮需求（如对话框按钮）时按新 Token/Interaction 体系重建，重建时以 `eInkActionColors` 为唯一样式源。 |
| `EInkCard.kt` | **删除（按需重建）** | 全家零调用。规范 §9/§10 的 Surface/Card 体系在 Phase 2 建立时重建（Base/Surface/Variant/Outlined/Emphasized 语义 + divider 优先），同样以真实调用方为前提。 |
| `EInkTextField.kt` | **删除（按需重建）** | 零调用；搜索输入已有专用 `EInkSearchInputBar`。通用表单需求出现时再按 §21 重建（其字号下限回退逻辑届时从 `EInkText` 复用）。删除后 `rememberNoRippleInteractionSource` 一并失去调用方，同步删除。 |

### widget/

| 文件 | 判定 | 依据 |
|---|---|---|
| `EInkAsyncImage.kt` | **保留** | 全站唯一图片入口（Coil3 薄封装，占位立即可见、无子组合），规范无对应条款但属 Foundation 必需件。 |
| `EInkBookCover.kt` | **下沉（拆分）** | `EInkBookCover` 依赖 `EInkEngineRegistry.coverEngine`（§44：业务模型不得进 Design System），与 `EInkInfoRow` 拆文件：封面下沉到 feature 共享位（建议 `eink/bookshelf/` 或新建 `eink/ui/`，书架/搜索/换源共用）；`EInkInfoRow`（纯图标+文字行，3 个 feature 在用）留在 Design System，后期可并入 `EInkListItem`。 |

---

## 3. 迁移任务清单（Agent 可执行）

执行纪律（每个任务通用）：
- 一个任务 = 一个可独立回滚的垂直切片；不做任务外的顺手重构。
- 每个任务完成后运行：`.\gradlew.bat :modules:eink:compileDebugKotlin :app:compileAppDebugKotlin`；涉及行为变化的加 `.\gradlew.bat :app:assembleAppDebug`。文本改动运行 `git diff --check`。
- 涉及删除的：先全仓 grep 确认零调用再删（本清单的调用统计截至 2026-08-29，执行时须复核）。
- 禁止在 P0/P1 未完成前新增业务组件（规范 §87）。

### Phase 0 — 清理死代码（无行为变化，可独立提交）

- [x] **T0.1 删除 `component/PaginatedList.kt`**
  动作：整文件删除（`PaginatedList`/`SimplePaginatedList`/`PaginatedGrid`/`PageIndicatorStyle`/`rememberEInkPagerState`）。前置复核：`grep -rn "PaginatedList\|SimplePaginatedList\|PaginatedGrid\|PageIndicatorStyle" modules/eink/src app/src`（排除该文件自身）应为零。
  验收：编译通过；无残留 import。

- [x] **T0.2 删除 `component/EInkPageIndicator.kt`**
  动作：整文件删除（两个指示器仅被 T0.1 的死代码引用）。Phase 3 按规范重建。
  验收：编译通过。

- [x] **T0.3 删除 `component/EInkButton.kt`、`component/EInkCard.kt`、`component/EInkTextField.kt`**
  动作：三文件删除；同步删除 `modifier/NoRipple.kt` 中失去全部调用方的 `rememberNoRippleInteractionSource()`。
  验收：编译通过；`rememberNoRippleInteractionSource` 全仓零引用。

- [x] **T0.4 删除零调用的小件**
  动作：`EInkBackButton.kt`（先将内联逻辑并入 T2.2 的 TopBar 合并——若 T2.2 未执行，则保留本文件至该任务一起做）；`EInkIconButton.kt`；`EInkText.kt` 内 `EInkHeadline/EInkTitle/EInkBodyText/EInkLabel`；`EInkDivider.kt` 内 `EInkVerticalDivider`；`EInkLoading.kt` 内 `EInkLoadingBox`；`modifier/NoRipple.kt` 内 `rememberNoIndication()`；`modifier/Grayscale.kt` 整文件。
  验收：编译通过；本清单表格中标注"零调用"的符号全仓 grep 均无残留。

### Phase 1 — Foundation：Token 分层（规范 §4/§46，P0）

- [x] **T1.1 `EInkColors.kt` 增补 grayscale 语义 token**
  动作：新增 `@Stable data object EInkGrayscale`（或等价结构），按规范 §1.3 提供 `black/gray900/gray700/gray500/gray400/gray300/gray200/gray100/gray50/white`，值从现有 16 级调色板取最近值映射（不新造色值）。现有 4 个语义 palette 改为**引用**这些 token 组合（单一事实源），不复制字面量。
  验收：编译通过；`EInkColors.GrayXX` 字面量仅出现在 grayscale token 定义处。

- [x] **T1.2 `EInkColorScheme` 增补语义角色**
  动作：在 `EInkColorScheme` 增加 `secondaryContent`（映射现 `onSurfaceVariant`）、`borderStrong`（现 `outline` 的 2dp 语义色）、`divider`（规范 §11：gray200/gray300 实灰）、`selected/selectedContent`（复用 `primary/onPrimary`）。旧角色名全部保留（65+ 调用点不迁移）。`EInkPalette` 接口同步扩展，4 个 palette 提供值。
  验收：编译通过；新增角色在 4 个 palette 下语义一致（HighContrast 下 divider 仍为实黑/白，不得退化成浅灰）。

- [x] **T1.3 `EInkTheme` 暴露全量 Token 访问器**
  动作：增加 `EInkTheme.grayscale`（staticCompositionLocal，默认即 T1.1 对象）、`EInkTheme.spacing`、`EInkTheme.shapes`（后两者现为直接对象引用，经 Theme 暴露以满足 §46"组件从 Theme 获取参数"，实现上可先直通现有单例）。
  验收：编译通过；规范 §46 检查项"Theme 提供 colorScheme/grayscale/typography/shapes/spacing"全部可访问。

- [x] **T1.4 Divider 默认色切换语义角色**
  动作：`EInkHorizontalDivider` 默认 `color` 改为 `EInkTheme.colorScheme.divider`。这是行为微调（灰阶从 outline 深灰变为 divider 浅灰），真机观察分隔线灰度是否合适；不合适则把 Grayscale palette 的 divider 角色调深（改 palette 一处）。
  验收：编译通过；书架/目录页分隔线真机或预览目视确认。

### Phase 2 — Interaction 统一（规范 §12/§13/§49，P0）

- [x] **T2.1 `EInkActionColors` 升格 interaction 层**
  动作：`EInkActionColors.kt` 迁至 `interaction/` 包（保留原包 `@Deprecated` typealias/函数一个过渡版本可不加——调用方仅 3 屏 + 组件，直接改 import 更干净）；`selected` 分支的容器色从 `primary` 改为 T1.2 的 `selected` 角色（值相同，仅语义化）。不引入规范 §12 的完整 `EInkInteractionState` 数据类——现有三参解析已覆盖全部真实用法，避免空模型（§53）。
  验收：编译通过；ReaderMenus/FontScaleSettings/TocScreen 三处调用行为不变。

- [x] **T2.2 顶栏合并：`EInkTopActionBar` 并入 `EInkTopBar`**
  动作：`EInkTopBar` 增加动作区模式参数（建议 `actionsFillMax: Boolean = false`：true 时动作区贴右、无水平内边距、按钮高度撑满，内部提供 `LocalOperationBarWidthRatio provides TopBarWidthRatio`）；`EInkBackButton` 逻辑内联（painterResource arrow_back + 48dp 目标）；删除 `EInkTopActionBar.kt` 与 `EInkBackButton.kt`；切换 3 个调用方（BookDetailRoute/HomeRoute/TocScreen）与 2 个旧 TopBar 调用方（ChangeSourceScreen/ThemeDebugScreen）。
  验收：编译通过；5 个界面顶栏视觉与合并前一致（首页/目录/详情的贴右动作区 + 换源/调试的常规顶栏），真机或截屏对比。

### Phase 3 — Content/Navigation 补齐（规范 §27/§60，P1–P2）

- [x] **T3.1 重建 `EInkPageIndicator`（数字式）**
  动作：新建单一 `EInkPageIndicator(currentPage, pageCount)`，"第 X / Y 页"或"X / Y"数字式（§27 推荐），静态无动画。接入点：书架网格页（HomeRoute 的 pageArrows 槽附近或顶栏），与 `EInkPageController` 的 pageStart/pageItemCount 换算页码。
  验收：编译通过；书架翻页时指示同步且单次刷新。
  注意：仅当产品确认需要页码显示时执行；无确认则保持删除状态。

- [x] **T3.2 `EInkListItem` 评估（不预先实现）**
  动作：规范 §60 的 ListItem（leading/headline/supporting/trailing/selected）。先盘点现有 5 个屏幕的行组件重复度；若 ≥2 处可归一才实现（§53），否则记录"暂缓"结论关闭本任务。
  验收：产出评估结论（实现 or 暂缓 + 理由），不强行落地。

- [x] **T3.3 `EInkInfoRow` 与 `EInkBookCover` 拆分下沉**
  动作：`widget/EInkBookCover.kt` 拆两文件：`EInkInfoRow` 留 Design System（迁 `component/` 或未来 `content/`）；`EInkBookCover` + `EInkCoverWidth/Height` 下沉到 feature 共享位（建议 `io.legado.app.eink.bookshelf`，因书架/搜索/换源共用；import 更新约 3 文件）。
  验收：编译通过；Design System 包（component/theme/modifier/interaction）内无 `engine/` 依赖（`grep -rn "EInkEngineRegistry" component/ theme/ modifier/` 为零）。

### Phase 4 — Refresh 架构重建（规范 §39–§43/§67–§69，P0 中"abstraction"项但实现排期可后置）

- [x] **T4.1 重写 `refresh/` 为 Intent→Policy→Controller 链**
  动作：删除现有两文件内容，重建：
  - `EInkRefreshIntent` 枚举（§40 八值：ContentStable/Interactive/PageTurn/Navigation/Overlay/TextInput/FullRedraw/ClearGhosting）；
  - `EInkDeviceProfile` 数据类（§42：grayscaleLevels/supportsPartialRefresh/…/keyboardInput，P3 再扩展）；
  - `EInkRefreshPolicy`：`(intent, profile) -> RefreshDecision`，决策用抽象档位（Interactive/Stable/PageTurn/FullRedraw，§43），**不含任何波形名**；
  - `EInkRefreshController` 接口 + `NoOp` 实现 + `LocalEInkRefreshController`（保留现有注入模式）；`DirtyRegion` 移入 controller 文件（区域合批 §68 预留）。
  验收：编译通过；`grep -rn "PARTIAL\|FULL\|A2\|DU\|GC16\|GL16" modules/eink/src/main/java/io/legado/app/eink/refresh/` 无波形/模式词汇泄漏。

- [x] **T4.2 翻页接 RefreshIntent（首个真实消费者）**
  动作：书架/目录翻页路径（`EInkPageController.pageDown/pageUp` 的调用侧，即各屏 Route）在翻页成功后发 `EInkRefreshIntent.PageTurn`（经 `LocalEInkRefreshController.current`，NoOp 下为空操作）。设备 Adapter 未接入前此改动零可见行为，但建立"组件产生意图"的接线范式。
  验收：编译通过；真机行为不变（NoOp）。
  注意：本任务可与 Phase 5 的设备研究并行；若设备调研（BOX/岩芯 SDK 能力）未启动，可推迟到有真实 Adapter 计划时执行，避免空转接线。

### Phase 5 — 包结构与命名对齐（规范 §3，最后执行）

- [x] **T5.1 目录归位（一次性、纯移动 + import 更新 + `@Deprecated` 旧路别名）**
  动作：按规范 §3 目标结构移动（保持 `io.legado.app.eink` 根）：
  - `interaction/`：EInkActionColors、ImmediatePress、StaticClickable（更名 `einkClickable` 保留旧名 deprecated 别名）、NoRipple（收敛为 `EinkNoIndication.kt` 一类的单文件）；
  - `navigation/`：EInkTopBar（合并后）、EInkOperationBar、EInkOperationBarIcon、EInkPageArrows；
  - `pager/`：EInkPageController + EInkPagedListState（更名 `EInkListPagerState`）+ EInkGridPagedListState（更名 `EInkGridPagerState`）+ EInkPageSwipe；
  - `content/`：EInkText、EInkDivider、EInkLoading、EInkInfoRow；
  - `control/`：EInkSteppedSlider、EInkSearchBar；
  - `theme/`、`refresh/` 不动。
  每个包一个独立提交；更名组件保留旧 `@Deprecated` typealias 一到两个迭代周期。
  验收：每步编译通过；`component/` 包最终清空移除；全仓无对旧包路径的 import。

### 明确不做（本次审查结论）

- **不实现** 规范 §12 完整 `EInkInteractionState` 数据类（现有三参解析覆盖全部真实用法，空模型违反 §53 与仓库"无调用方抽象"纪律）。
- **不新增** Reading* Typography 角色（阅读正文走引擎 Canvas，非 Compose 排版）。
- **不重建** EInkButton/EInkCard/EInkTextField，直到出现首个真实调用方（对话框/表单需求）。
- **不接入** 真机 Refresh Adapter（BOOX/岩芯 SDK 属平台侧，归 `:app` 宿主，另行立项）。
- **不做** Theme Variants（§47 LargeText 已由宿主 fontScale 机制承担；HighContrast/Grayscale 双 palette 已存在）。

---

## 4. 验证与风险

- 已验证：全部 35 文件通读；调用点统计基于词边界 grep 全模块（含 `:app` 侧 EinkMainActivity/ReaderPageCanvas 对 eink theme 的引用）。
- 未验证：T1.4 divider 灰度、T2.2 顶栏合并后的视觉一致性需真机/预览确认；灰阶抗锯齿相关既有待验证项（4dp 圆角灰阶表现）不受本清单影响。
- 风险提示：Phase 0 删除项的"零调用"结论依赖当前代码形态，执行时必须重新 grep 复核；`:app` 侧若新增了对将删组件的引用会阻塞对应任务。

---

## 5. 执行记录（2026-08-29，全阶段一次性落地）

Phase 0–5 全部执行完毕，每个阶段后 `:modules:eink:compileDebugKotlin :app:compileAppDebugKotlin` 通过；最终验证集结果（2026-08-29）：`testAppDebugUnitTest`、`verifyConfigArchitecture`、`assembleAppDebug` 通过；`lintAppDebug` 失败于 **3 个既有错误**（与本迁移无关，迁移自身错误已清零）：`BookInfoScreen.kt:269/270`（已提交代码）、`FontScaleSettingsScreen.kt:65`（此前会话未提交工作）。三者均按"不动无关代码"纪律保留，待单独处理。落地内容与计划的偏差：

- **新增交付：组件预览调试界面**（本节为用户新增要求，非原计划条目）。
  `debug/ComponentGalleryScreen.kt` + `EInkScreen.ComponentGallery` 路由 + 「我的」页 debug 入口（"组件预览（Design System）"，BuildConfig.DEBUG 门控）。
  覆盖：灰阶 Token / 语义色角色 / 形状 / 按压-选中-禁用交互 / 文本-分隔线-信息行-加载 / 离散滑条（预览+抬手提交）/ 搜索条（提示+输入）/ 顶栏两种动作模式 / 翻页箭头（含置灰）/ 页码指示器 / 底部操作栏（Tab 选中素材对）/ 固定页分页联动（EInkPageSwipe + PageTurn 意图上报）/ 封面占位。产品界面之外即可微调并目视验证全部组件（规范 §72）。
- **T3.1 执行口径**：`EInkPageIndicator`（数字式）已重建并在 Gallery 中与 Pager 联动演示；**未**接入产品界面（原任务的产品确认门槛维持——书架/目录是否显示页码待产品决定，接入点已在 Gallery 中验证可行）。
- **T3.2 结论：暂缓**。盘点书架/目录/搜索/换源四类行组件：各自内嵌封面槽、选中标记、进度徽标等 feature 专属结构，仅目录与换源两处可共享骨架且已由各自屏内组合覆盖，未达 §53"≥2 处统一场景"门槛。`EInkInfoRow`（3 个 feature 在用）保留为通用件，待第 2 个可归一行结构出现再演进 `EInkListItem`。
- **T5.1 偏差：更名未留 deprecated 别名**。`staticClickable→einkClickable`、`EInkPagedListState→EInkListPagerState`、`EInkGridPagedListState→EInkGridPagerState` 及全部 import 在同一次改动内完成迁移（编译验证），不产生立即死亡的兼容包装——优于原计划的"别名保留一到两个迭代周期"（规范 §81 的 wrapper 本就要求最终删除，内部模块无外部消费者时直接更名更干净）。目录归位未按"每包一提交"拆分（本次为一次性落地，用户已确认允许过程中产品界面效果不佳、最终统一审核）。
- **T1.2 补充**：`eInkActionColors` 常态次级内容与选中分支已改用 `secondaryContent`/`selected`/`selectedContent` 语义角色（值不变）。
- **T2.2 API 形态**：合并后 `EInkTopBar` 以 `actionsFillMax: Boolean` 切换两种动作模式；返回按钮内联为私有 `TopBarBackButton`（48dp 触控目标 + 共享按压反色）。

落地后的包结构（`io.legado.app.eink` 下）：`content/ control/ interaction/ navigation/ pager/ refresh/ theme/ widget/(EInkAsyncImage) bookshelf/(EInkBookCover) engine/ ...`；`component/`、`modifier/` 已删除。

### 补充：业务/组件系统包级分离（2026-08-29 第二批）

上述结构落地后业务屏仍与 DS 包平铺在 `io.legado.app.eink` 根下，且 `navigation/` 混装业务路由（EInkNavController/EInkScreen）与 DS 顶栏组件。按仓库 feature-first 分阶段纪律（先包级分离、稳定后再提 Gradle 模块）完成二次重组：

- **DS 不动**（规范 §3 根布局）：`content/ control/ interaction/ navigation/ pager/ refresh/ theme/ widget/`。
- **业务下沉**：`home/ bookshelf/ bookdetail/ search/ toc/ changesource/ reader/` → `io.legado.app.eink.feature.<name>`；跨 feature 共享的 `EInkBookCover`（依赖 CoverEngine 业务端口）移至 `feature/common/`，避免 feature 互相依赖实现。
- **宿主聚合**：`EInkApp + EInkScreen + EInkNavController(EInkNavViewModel)` → `io.legado.app.eink.app`（组合根，唯一允许依赖全部 feature 的位置）；`:app` 侧 EinkMainActivity 增加 EInkApp import，bridge 六个引擎实现改引 `feature.*` UiModel。
- **根级 infra 保留**：`arch/ engine/ settings/ util/ debug/`（跨 feature 端口与调试工具，非 DS 非单 feature）。
- **DS 纯度门禁**（新增，已验证通过）：DS 八包内 `grep "import io.legado.app.eink.(engine|feature|settings|debug|arch|app)"` 零命中。

### 补充：DS 八包收拢 designsystem/ 父目录（2026-08-29 第三批）

按用户澄清，DS（EInkText/Theme 等）与业务（HomeScreen 等）须各自位于独立目录，对等可见。DS 八包整体迁入 `io.legado.app.eink.designsystem` 父包（`designsystem/{content,control,interaction,navigation,pager,refresh,theme,widget}`），与 `feature/`、`app/` 形成三域分立；`arch/ engine/ settings/ util/ debug/` 仍为根级 infra。此调整有意偏离规范 §3 的字面根布局（规范允许迁移期保留/逐步调整），包语义不变、仅加一层命名空间。

最终目录形态（`io.legado.app.eink` 下）：

```text
designsystem/   # 组件系统（DS 八包，无业务依赖）
feature/        # 业务（home/bookshelf/bookdetail/search/toc/changesource/reader + common）
app/            # 宿主组合根（EInkApp + 路由）
arch/ engine/ settings/ util/ debug/   # 根级 infra 与调试
```

**下一步（未执行，需独立立项）**：将 DS 八包提取为 `:modules:eink-designsystem` Gradle 模块，把"DS 不依赖业务"从 grep 门禁升级为编译期边界；按 AGENTS KMP 纪律属独立边界变化，须单独切片并把真实 Gradle 任务纳入 CI。
