# 墨水屏页眉页脚信息槽位遵从完整模式配置

日期：2026-11-16
状态：设计已认可，待实现
范围：eink-lib 契约与渲染 + 宿主桥（`ReaderEngineImpl`、槽位解析器抽取）

## 1. 背景与目标

完整模式的页眉/页脚内容可配置：页眉/页脚各有 左/中/右 三槽（`ReadBookConfig.tipHeaderLeft/Middle/Right`、`tipFooterLeft/Middle/Right`），每槽取 `ReadTipType` 的 20 种类型（时间、电量文本/图形、页数、进度、章节标题、书名、自定义模板、整书页数、箭头等），在阅读排版面板「自定义信息」配置。

墨水屏模式的页眉页脚**内容项锁定**：页眉恒为 时间(左)+电量%(右)，页脚恒为 章节标题(左)+页数及进度(右)（`ReaderFooter` KDoc 明说「内容项锁定」），6 槽配置被完全忽略；渲染层只有左右两槽（`drawTipRowLeftRight`），无中槽。

目标：放开该限制——用户在完整模式配置槽位内容，墨水屏遵从配置渲染（含中槽）。

## 2. 现状机制事实

### 已遵从的（本设计不动）

- 可见性：`headerMode`（0 随状态栏/1 显示/2 隐藏）、`footerMode`（0 显示/1 隐藏）——桥 `ReaderEngineImpl.headerFooterVisibility()` 与 extent 工厂 `LegacyReaderPageDecorationFactory.header/footerExtentPx()` 已按配置解析（不可见时 extent=0，分页不留带）。
- 字号/字体/上下边距：已跨桥（`ReaderTextStyle` + `headerFooterTypefaces()`）。
- 分隔线：墨水屏有意停绘（2026-11-02 决策，`snapshotStyle` 恒发 false），维持。

### 完整模式槽位解析与绘制（宿主既有实现）

- 解析：`LegacyReaderPageDecorationFactory.tips()/tip()/value()/type()` 把 6 槽配置 + `ReaderTipValueContext`（书名/章节标题/时间/电量/章页序/整书页数/进度）解析为 `List<ReaderPageTip>`（text + alignment START/CENTER/END + visual）。取值规则同型多槽只取首个；空文本过滤。微妙规则集中在 `ReaderTipValueFormatter`（进度 99.9% 封顶、整书页数精确/估算两格式等）。
- 绘制（`ReaderCanvasSurface.drawTipRow`，对照旧 `view_book_page.xml` 三槽约束）：
  - **左槽** weight 占位，在 barrier 处省略——barrier = min(可见中槽起始边, 右槽起始边)，无邻槽时整行宽；
  - **中槽** wrap_content 居中（`width/2 − w/2`），右槽靠右，两者只在**整行宽度**处省略，长文案会压到邻槽上（旧版如此，不额外让位）；
  - 省略号截断 `ellipsizeTipText`（maxLines=1 ellipsize=end 语义，LRU 缓存）。

### 墨水屏渲染链（改造对象）

- VM：`updateTipInfo()` 写 `headerTime/batteryPercent`（页眉硬装配）；页回调写 `chapterTitle/readProgress`，`pageAndTotal` 为计算字段（页脚硬装配）。分钟时钟、翻页/出页/样式事件都走 `updateTipInfo`。
- Screen：`ReaderHeader`（时间左/电量%右）、`ReaderFooter`（章节标题左/pageAndTotal 右）经 `drawTipRowLeftRight` 两槽绘制；行盒几何 `ReaderTipRowGeometry` 与屏保共用。
- 屏保渲染器同构硬编码（不在本刀）。

### 默认配置的关键事实

完整模式默认槽位：`tipHeaderLeft=tipTime`、`tipHeaderMiddle=none`、`tipHeaderRight=tipBattery`、`tipFooterLeft=tipChapterTitle`、`tipFooterMiddle=none`、`tipFooterRight=tipPageAndTotal`。经下述降级规则（tipBattery→`NN%`）后与墨水屏现有硬编码内容**逐槽一致**——即默认配置用户在本设计落地后**零视觉变化**。

## 3. 方案对比

| 方案 | 做法 | 结论 |
| --- | --- | --- |
| A. 宿主解析、文本跨桥（选定） | 宿主把 6 槽配置 + 当前页上下文解析为最终文本（含降级），仅 6 条字符串经契约传入模块 | 取值规则单一实现，两模式天然同构；契约面最小 |
| B. 槽位配置跨桥、模块解析 | 6×(类型+模板) 传模块，模块复刻 20 种格式化 | 否决：重复宿主全部取值规则（进度封顶/整书页数估算/模板），违反模块不自持宿主配置解析的边界，双实现漂移 |
| C. `ReaderPageDecoration` 整体跨桥 | 宿主画布模型直接过桥 | 否决：模块只需文本，拖过整个宿主模型耦合过度 |

## 4. 设计

### 4.1 契约扩展（eink-lib `contract/`）

- 新增 `@Stable data class ReaderTipSlotTexts(headerLeft/headerMiddle/headerRight, footerLeft/footerMiddle/footerRight: String)`；空串 = 该槽不绘制（解析侧已过滤空文本，模块不再自行判断类型）。声明为 data class 以提供值等价——UiState 直持该类型时 StateFlow 去重依赖 equals。
- `ReaderEngine` 新增 `fun headerFooterTipTexts(): ReaderTipSlotTexts?`，**默认返回 null**——旧宿主降级，同 `ReaderRuleLine` 缺省空、`ReaderEngine 暴露下载失败信号默认 false` 的既有兼容纪律。

### 4.2 宿主槽位解析器抽取与桥实现

- 从 `LegacyReaderPageDecorationFactory` 抽出纯函数解析器（`tips()/tip()/value()/type()` 及所需常量→如 `ReaderTipSlotResolver`），完整模式工厂与新桥实现共用一份；行为不变（重构前后完整模式既有测试/快照保持通过）。
- `ReaderEngineImpl.headerFooterTipTexts()`：以 eink 会话当前页为上下文（书名/章节标题/章页索引与计数/整书页数/时间/电量），构建 `ReaderTipValueContext` 后走同一解析器，产出 6 槽文本。电量值读取与 `updateTipInfo` 现行方式同源（`registerReceiver(null, BATTERY_CHANGED)`）。
- **降级规则**（解析时完成，模块零图标代码）：
  - 电量图形 4 种（`tipBattery/tipBatteryInside/tipBatteryIcon/tipBatteryClassic`）→ `NN%`；
  - 时间+图形电量 2 种（`tipTimeBattery/tipTimeBatteryClassic`，及 `tipTimeBatteryPercentage` 文本型本就直通）→ `HH:mm NN%`（复用 `ReaderTipValueFormatter.TIME_BATTERY` 文本路径）；
  - 箭头 2 种（`tipChapterTitleArrow/tipChapterTitleArrowClassic`）→ 纯章节标题文本。
- 同型多槽只取首个、空文本过滤——与完整模式同语义。

### 4.3 模块 VM（`ReaderViewModel`）

- `updateTipInfo()` 调 `engine.headerFooterTipTexts()`：
  - 非 null → `ReaderTipSlotTexts` 整体写入 uiState（单可空字段 `tipSlots`，`@Stable` + data class 值等价支撑 StateFlow 去重）；页眉页脚相关旧字段保留用于 null 回落与屏保；
  - null（旧宿主）→ 维持现有硬编码装配，行为与今日一致。
- 刷新时机不变：分钟时钟、翻页/出页/样式事件均已驱动 `updateTipInfo`，页数/进度/整书页数类槽位随页刷新；无时间槽时时钟仍走字（StateFlow 值去重零代价）。

### 4.4 模块渲染（`ReaderScreen.ReaderHeader/ReaderFooter`）

- 取值：`uiState.tipSlots` 非 null 用其槽位文本；null（旧宿主回落）沿用旧字段硬装配，走同一三槽函数（左/右槽 = 旧字段，中槽空）——默认配置下两路径渲染逐槽一致。
- `drawTipRowLeftRight` 扩为三槽 `drawTipRow(left, middle, right)`，语义镜像 §2 完整模式绘制：左槽在 barrier（= min(可见中槽起始边, 右槽起始边)，无则整行宽）处省略；中槽居中、右槽靠右，整行宽处省略；省略沿用模块现行 `TextUtils.ellipsize(TruncateAt.END)`（与宿主 `ellipsizeTipText` 同语义，现有两槽行为不变）。
- 行盒几何、基线、字号钳制（`ReaderTipRowGeometry`/`clampTipFontSizeToAvailablePx`）不动。
- 屏保渲染器不动（仍是时间/电量/标题/页数硬编码，后续刀对齐）。

### 4.5 不在范围

- 屏保页眉页脚内容对齐；
- 宿主 tip 颜色（`resolvedTipHeader/FooterColor`）灰度跨桥——维持模块主题色；
- 分隔线绘制（维持停绘决策）；
- eink 信息配置弹窗新增槽位配置 UI——配置入口只有完整模式排版面板；
- 电池图形/箭头的真实绘制。

## 5. 行为变化与兼容

- **默认配置用户：零变化**（§2 默认槽位经降级与现硬编码逐槽一致）。
- **自定义槽位用户**：eink 首次真实遵从配置（含中槽）；视觉型槽位以文本降级呈现。
- **旧宿主（模块库单独更新）**：`headerFooterTipTexts()` 默认 null → 回落现有硬编码。
- eink 信息配置弹窗的「页眉显示/页脚显示」两行继续控制可见性，与槽位内容正交。

## 6. 测试策略

- 宿主：解析器抽取为纯函数后，20 类型→文本映射、视觉型降级、同型去重、空过滤逐条锚定；完整模式工厂行为回归（既有测试不动照跑）。
- 模块：VM——引擎文本流灌 uiState、null 回落、翻页/时钟刷新路径；渲染——三槽几何（中槽居中、左槽 barrier 省略、右槽整行宽省略、无邻槽退化两槽与现状一致）。
- 真机：默认配置视觉零变化抽查 + 自定义三槽配置目验（真机复核清单随交付列明）。
