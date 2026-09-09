# eink 阅读界面长按选择：书签与笔记功能设计

状态：已实施（实施精化于 2026-09-10 回填）。
日期：2026-09-09。

## 决策记录

- 2026-09-09（用户确认）：范围为**创建闭环**——长按选择系统 + 选择菜单 + 书签/笔记编辑弹层 +
  落库 + 页面划线渲染。列表查看/点击跳转/点按已标记文字再编辑留后续切片。
- 2026-09-09（用户确认）：菜单项为**书签 / 笔记 / 复制** 三项，菜单结构可扩展。
- 2026-09-09（用户确认）：eink 创建的划线**固定实线，无样式配置入口**（本期与将来都不做
  样式设置）；宿主创建的既有标记按其原样式如实渲染（数据保真，不是配置项）。
- 2026-09-09（用户确认）：架构取**方案一**——快照携带章内位置桥，选择交互完全在模块本地，
  宿主仅新增瘦提交端口；否决「选择语义全在宿主」的细粒度几何查询方案（契约碎、拖拽跨接口、
  companion 插件场景几乎不可用）。
- 2026-09-10（实施回填）：`resolveSelection` 增加 `selectedText` 参数作为宿主保存期
  窗口搜索定位依据，resolve 本身不做定位；装饰「同行相邻 run 合并」执行侧定为宿主
  映射器（映射侧合并、模块直绘）。

## 1. 背景

宿主 MD3 阅读器已有完整选择系统：长按选词 → 把手拖拽 → `TextActionSelectionMenu` 浮层。
两个目标功能的宿主实现：

- **书签**：`bookmarks` 表（chapterIndex / chapterPos=选区 bodyStart / chapterName=窗口标题 /
  bookText=选中文本 / content=备注），编辑弹层 `BookmarkEditSheet` 可编辑 bookText 与 content。
- **笔记**（划线体系）：`book_marks` 表，锚点 `TextProcessAnchor`（chapterIndex /
  chapterPosition / selectedText / contextBefore / contextAfter / normalizedTextHash）+
  样式 `TextProcessStyle`（underlineMode：1 实线 / 2 虚线 / 3 波浪 / 4 双线 / 5 SVG，
  bg 高亮，textColor 字体色）+ note 备注；保存后宿主重排当前章，标记经
  ContentProcessor → 排版元素样式进入页面绘制。

eink 模块阅读页完全自绘，数据源 `ReaderPageSnapshot` 只有行坐标与文本段，无章内字符位置，
无任何选择/书签/笔记能力。本设计补齐这条链，数据与宿主同库同构，两模式互认。

## 2. 范围

目标：

- eink 阅读页长按选择文本（选词、把手拖拽调界、选区高亮与把手绘制）。
- 选择菜单（书签/笔记/复制）与两个编辑弹层，经宿主端口落库。
- 当前章页面上的划线/高亮装饰渲染（含宿主模式创建的既有标记）。

非目标（后续切片）：

- 书签/笔记列表查看与点击跳转。
- 点按已标记文字的再编辑/删除。
- 划线样式配置（明示不做）。
- 下滑快速书签手势、跨页选择、朗读/搜索/词典等其他宿主菜单项。

## 3. 契约扩展（AAR minor）

契约面共三处改动。`ReaderPageLine` 为破坏性构造变更，随 minor 版本升级，
宿主 bridge 同仓同步实现，无跨仓协调。

### 3.1 `ReaderPageLine` 增加位置桥与装饰

```kotlin
/**
 * 单文本行：[chunks] 的第 i 段绘制于横坐标 [x][i]，基线纵坐标 [baseY]。
 * [chunks] 与 [x]、[chapterPositions] 等长。
 */
@Stable
class ReaderPageLine(
    /** 行基线纵坐标（px，引擎排版坐标系）。 */
    val baseY: Float,

    /** true = 标题行，使用 [ReaderPageSnapshot.titleSpec] 的画笔规格。 */
    val isTitle: Boolean,

    /** 行内文本段（按绘制顺序；段间断行由引擎测量决定）。 */
    val chunks: List<String>,

    /** 各文本段起始横坐标（px；与 [chunks] 一一对应）。 */
    val x: FloatArray,

    /**
     * 各文本段首字符的章内字符位置（UTF-16 索引，与宿主
     * TextProcessAnchor.chapterPosition 同一坐标系，标题行亦携带其元素值）。
     * 宿主实现义务：从排版元素 chapterPosition 原样拷贝，不做换算。
     */
    val chapterPositions: IntArray,

    /**
     * 行盒顶/底边界（px，来自排版元素 bounds 的原样拷贝）。选择高亮带、
     * 把手与菜单锚定的几何依据；模块不得用字体度量估算行高。
     */
    val top: Float,
    val bottom: Float,

    /**
     * 本行划线/高亮装饰（缺省空）。宿主实现义务：从排版元素的用户标记
     * 样式提取，区间为行内拼接文本的 UTF-16 索引；与选中态无关，常驻。
     */
    val decorations: List<ReaderDecorationRun> = emptyList(),
)
```

### 3.2 `ReaderDecorationRun`

```kotlin
/**
 * 行内一段用户标记装饰。颜色不跨桥：模块按主题自涂（线=黑，高亮=灰底）。
 */
@Stable
class ReaderDecorationRun(
    /** 行内字符区间 [start, end)，按行内拼接文本的 UTF-16 索引。 */
    val start: Int,
    val end: Int,

    /**
     * 宿主 TextProcessStyle.underlineMode 原样透传
     * （1 实线 / 2 虚线 / 3 波浪 / 4 双线 / 5 SVG 花色）。
     * 模块渲染：1/2/3/4 原生绘制，5 降级为实线（明示不支持花色）。
     */
    val underlineMode: Int,

    /** true = 背景高亮带（TextProcessStyle.bgColor 非空的标记）。 */
    val highlight: Boolean,
)
```

诚实降级声明（写入契约注释与本文档）：**字体色效果（MarkingEffect.TEXT）在 eink
页面不渲染**——模块正文一律主题色，字体色不可表达；该类标记在 eink 页面不可见，
宿主模式可见。不伪造近似效果。

### 3.3 新增可选端口 `ReaderSelectionEngine`

```kotlin
/**
 * 选区语义与批注落库端口：宿主负责把模块选区解析为宿主语义字段
 * （书签 bodyStart、锚点上下文与哈希）并写入 bookmarks / book_marks。
 * 模块不复制这些规则。
 *
 * 可选端口（同 appUpdateEngine 先例）：注册表缺失本端口时，模块隐藏
 * 书签/笔记菜单项，仅保留长按选择与复制，不做假死路径。
 */
interface ReaderSelectionEngine {

    /**
     * 选区解析：按章节全文构造编辑弹层预填内容（书签标题/内容按宿主
     * BookmarkEditSheet 预填规则；选中文本供笔记预览）。
     * [selectedText]：选中文本，按行拼接、段落间隙以换行连接；宿主保存时
     * 以其在章节全文窗口搜索定位，resolve 本身不做定位（含标题选区在正文
     * 空间无文本，属合法输入）。
     * 返回 null = 无会话书或选中文本为空，模块清选区。
     */
    suspend fun resolveSelection(
        chapterIndex: Int,
        start: Int,
        end: Int,
        selectedText: String,
    ): ReaderSelectionDraft?

    /**
     * 保存书签（字段为用户编辑后的值）。宿主落库 bookmarks 表。
     * false = 落库失败，模块提示并保留弹层。
     */
    suspend fun saveBookmark(commit: ReaderSelectionCommit): Boolean

    /**
     * 保存笔记（固定实线样式由宿主桥写入 TextProcessStyle：
     * underlineMode=1、颜色取宿主笔记默认值；note 为用户备注，可空）。
     * 宿主落库 book_marks 后自行触发当前章重排（保持页内位置），
     * 新快照经 onContentUpdated 携带装饰推送——模块不请求刷新。
     * false = 落库失败。
     */
    suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean
}

/** 选区解析结果：两个编辑弹层的预填初值。 */
@Stable
class ReaderSelectionDraft(
    /** 选中文本（笔记预览展示；与 bookmarkText 同源，独立成字段以便宿主规则分化）。 */
    val selectedText: String,

    /** 书签 bookText 预填（宿主规则：选中文本）。 */
    val bookmarkText: String,

    /** 书签 content 预填（宿主规则：空）。 */
    val bookmarkContent: String,
)

/** 菜单动作提交载荷：选区坐标 + 用户编辑后的各字段。 */
@Stable
class ReaderSelectionCommit(
    /** 选区所在章节下标。 */
    val chapterIndex: Int,

    /** 章内字符区间 [start, end)（UTF-16 索引）。 */
    val start: Int,
    val end: Int,

    /** 选中文本（宿主用于校验/锚点构造）。 */
    val selectedText: String,

    /** 用户编辑后的书签 bookText。 */
    val bookmarkText: String,

    /** 用户编辑后的书签 content。 */
    val bookmarkContent: String,

    /** 用户输入的笔记备注（可空串）。 */
    val note: String,
)
```

注册进 `EInkEngineRegistry` 为可选端口。

### 3.4 位置口径

- 一律 **UTF-16 索引**，与宿主选区偏移同一空间，不做码点换算。
- 标题行亦携带其元素的 chapterPosition；选区含标题行时**笔记不可用**（菜单隐藏该项，
  宿主同款规则），书签允许——bodyStart 等宿主语义字段由桥实现负责从选区区间映射，
  模块不解释标题/正文位置空间差异（实现期核对项见 §9）。
- 选区仅限当前页内文本（eink 为整页翻页模型，无连续滚动，跨页选择无意义）。

### 3.5 数据流

```text
长按/拖拽（模块本地：命中 → 选词 → 把手）→ 松手弹菜单
  ├─ 复制 ──► 模块本地剪贴板 + tip，清选区
  ├─ 书签 ──► resolveSelection ──► 编辑弹层（预填可改）──► saveBookmark
  │                                              └─► 成功 tip「已添加书签」，清选区
  └─ 笔记 ──► resolveSelection ──► 编辑弹层（备注）──► saveMarking
         └─► 宿主落库 + 当前章重排 ─► onContentUpdated（保持页内位置）
                ─► 新快照携带 decorations ─► 模块整页重绘，清选区收尾
```

## 4. 模块选择交互

选择状态完全在模块本地（无跨桥往返），宿主无感知：

```text
阅读态 ──长按(系统 longPressTimeout，正文行上)──► 选词态
        命中测试：y → 行盒 top/bottom 定行；x → 行内逐字符宽度测最近字符
        选词：BreakIterator(章节 locale) 吸附词边界；触觉反馈一次
选词态 ──拖把手──► 调界态：moveEndpoint 吸附字符边界；拖拽期局部快速刷新
       ──松手──► 菜单态：浮条锚选区上方/下方（放不下取另一侧）
菜单态 ──点菜单项──► 执行；──点选区外──► 清选区
选词态 ──点选区内非把手──► 清选区（防误触无路可退）
任意含选区态 ──翻页/跳章/自动翻页──► 清选区（无跨页残留）
```

- 把手样式与宿主一致（竖线+圆点），命中热区 28dp；把手命中优先于翻页手势，
  选区存在期间水平拖动手势不触发翻页（拖拽语义优先）；物理键/自动翻页仍会清选区。
- 菜单为设计系统 Action Bar 风格浮条：无阴影、按压反色、零动画直切，横排三键。
  两项显隐规则：
  - 选区含标题行 → 隐藏笔记键（书签/复制保留）；
  - `ReaderSelectionEngine` 端口缺失 → 隐藏书签/笔记键（仅复制）。
- 复制：`ClipboardManager` 本地写入 + eink 风格 tip「已复制」→ 清选区。
- 中间区域点按呼出菜单的原有行为保持：仅在无选区时生效。

## 5. 编辑弹层与提交

弹层复用模块现有面板/EInkDialog 体系（设计系统 §20/§21：标准 TextField、
软键盘避让、零动画）。

**书签弹层**（字段与宿主 BookmarkEditSheet 对齐）：

- 标题输入框（预填 `draft.bookmarkText`，可改 → `commit.bookmarkText`）。
- 内容输入框（预填 `draft.bookmarkContent` 即空，可改 → `commit.bookmarkContent`）。
- 保存/取消。成功 → tip「已添加书签」→ 关弹层清选区；失败 → tip「保存失败」，
  弹层不关，可重试或取消。

**笔记弹层**：

- 顶部只读预览选中文本（`draft.selectedText`）。
- 备注输入框（可空，空=纯划线 → `commit.note`）。
- 样式不暴露选择：固定实线落库（见 §3.3 端口注释）。
- 保存成功：弹层立即关闭并 tip「已添加笔记」；**选区保持显示**，等
  `onContentUpdated` 新快照整页重绘（带装饰）后随重绘一并清除——重排期间选区不闪断，
  避免「点了保存屏上什么都没发生」的 eink 停滞感。宿主 `onLayoutException` 时
  tip 提示并清选区——标记已落库，不丢数据。

## 6. 页面装饰渲染

模块画布随行绘制 `decorations`：

- 下划线：基线下方行盒高度的 12%，模式 1 实线 / 2 虚线 / 3 波浪 / 4 双线原生绘制，
  颜色走模块主题（黑）；模式 5 SVG 降级实线；TEXT 字体色效果不进装饰数据（§3.2）。
- 高亮带：主题灰底色；同行相邻 run 合并在宿主映射器执行（提取装饰时相邻同款
  合并为单 run），模块直绘不合并，避免逐 run 碎块灰带。
- 字符区间 → x 坐标换算用画布自有 `Paint.measureText`（与正文绘制同一把尺）。
- 刷新成本：装饰只随 `onContentUpdated` 新快照出现，归入正常整页刷新，零额外开销；
  与拖拽期局部快速刷新互不干扰。

## 7. 宿主 bridge 实现

- **映射器扩展**（`ReaderPageSnapshotMapper`）：行盒 top/bottom 与每段 chapterPositions
  从排版元素原样拷贝；装饰 run 从元素用户标记样式提取（含同 marking 跨行折叠为多行 run）。
- **`resolveSelection`**：按章节全文构造 `ReaderSelectionDraft`（书签预填复用
  composeSelectionBookmark 规则）；同时校验选区越界（返回 null 路径）。
- **锚点构造**：与 `SaveMarkingUseCase` 输入同构（前后 48 字上下文、normalizedTextHash），
  复用 `SaveMarkingUseCase.save` 落库（同锚点原地更新语义由其承担）。
- **`saveBookmark`**：组装 `Bookmark`（bookName/author/bookUrl 取会话书）落库 bookmarks。
- **`saveMarking` 成功后**：触发当前章重排（保持页内位置），经既有回调推送新快照。

## 8. 测试与验证

| 层 | 内容 |
|---|---|
| 模块单测 | 命中测试（y→行、x→字符）纯函数；BreakIterator 选词吸附；行内区间→x 换算 |
| 宿主 bridge 单测 | 映射器 chapterPositions 与 chunks/x 对齐、装饰 run 提取与同行相邻合并、跨行折叠；resolveSelection 预填与选中文本为空 null；锚点构造与 SaveMarkingUseCase 输入同构；saveMarking 后重排触发 |
| 降级测试 | 注册表无 ReaderSelectionEngine → 菜单仅复制，无假死路径 |
| 真机手工 | 全链路（长按→拖拽→书签/笔记/复制）；同库互认；清选区路径；弹层软键盘避让——逐项清单见 §9 真机验证清单 |

模块单测沿用既有 seam 惯例（ViewModel 抽象调度，避免 runTest 收尾无限 drain）。
验证命令：`:modules:eink` 单测任务、`:app:compileAppDebugKotlin`、宿主 bridge 所在测试集，
具体任务名在实施计划中按当时构建脚本确认后列出。

## 9. 风险与实现期核对项

- **标题/正文位置空间口径**：标题元素的 chapterPosition 与书签 bodyStart 的映射关系
  在桥实现期对照宿主 composeSelectionBookmark 精确核对；映射规则全部消化在桥内。
- **eink 真机软键盘**：弹层输入与避让需真机验证（eink 设备输入法表现差异大）。
- **波浪/虚线的低刷新观感**：装饰线型在真机灰屏上的实际效果待手工确认。
- **AAR 版本分栈**：契约 minor 升级后，宿主与模块版本配套关系按发布纪律更新。

### 真机验证清单（2026-09-10 实施后汇总）

- 选区浮条三键显隐：无标题 3 键 / 含标题 2 键 / 无端口 1 键（可临时注释 EInkBridge
  装配验证降级）。
- 空白区长按拖拽为死手势（框架固有），确认无翻页误触。
- 自动翻页推进终止拖拽并清选区。
- secondaryContainer 高亮带与四种下划线（实/虚/波/双）灰屏观感。
- 保存笔记 → 重排 → 选区随新快照清除的感知时序；selectionMenuVisible 在重排窗口的重现。
- onLayoutException 后 ErrorView 覆盖与已落库笔记的恢复路径。
- 书签跨模式互认：eink 加书签 → 宿主完整模式书签列表可见且跳转位置正确（bodyStart
  口径核对）。
- 笔记跨模式互认：eink 划线 → 宿主可见；宿主建的波浪/虚线 → eink 如实显示。
- 弹层软键盘避让与真机输入法表现。

## 10. 实施切片（详见实施计划）

1. 契约类型 + 宿主映射器扩展（位置桥 + 装饰桥，带测试，纯数据不改 UI）。
2. 模块选择系统 + 复制（无端口依赖，独立可验）。
3. 书签链路（端口实现 + 书签弹层）。
4. 笔记链路（弹层 + 落库 + 装饰渲染闭环）。

每片独立可回滚，交付时按 AGENTS.md 门禁说明验证与未验证风险。
