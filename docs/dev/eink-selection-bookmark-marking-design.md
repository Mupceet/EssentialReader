# eink 阅读界面长按选择：划线笔记与页面书签功能设计（v2）

状态：v2 已实施（2026-09-10），待真机验证（v1 创建闭环已于同日实施完成）。
日期：2026-09-09（v1）/ 2026-09-10（v2 交互重设计）。

## 决策记录

- 2026-09-09（用户确认，v1）：范围为**创建闭环**——长按选择系统 + 选择菜单 + 书签/笔记编辑弹层 +
  落库 + 页面划线渲染。列表查看/点击跳转留后续切片。
- 2026-09-09（用户确认，v1）：架构取**方案一**——快照携带章内位置桥，选择交互完全在模块本地，
  宿主仅新增瘦提交端口；否决「选择语义全在宿主」的细粒度几何查询方案。
- 2026-09-09（用户确认，v1）：eink 创建的划线**无样式配置入口**；宿主创建的既有标记按原样式
  如实渲染（数据保真，不是配置项）。
- 2026-09-10（实施回填，v1）：`resolveSelection` 增加 `selectedText` 参数作为宿主保存期
  窗口搜索定位依据；装饰「同行相邻 run 合并」执行侧定为宿主映射器。
- 2026-09-10（用户确认，**v2 交互重设计，取代 v1 的创建闭环交互**）：
  - **选择即划线**：长按选择松手后自动记录为「划线」笔记（实线），不再经菜单显式保存。
  - 浮条三键【复制】【写想法】【删除】：松手后与点按划线时同款；点按**想法**（虚线）为浮窗
    （展示想法内容 + 同款三键，「写想法」即编辑）。
  - 类型语言：**划线 = 实线（underlineMode=1，note 空）；想法 = 虚线（underlineMode=2，
    note 非空）**。
  - **选择过程实时下划线预览**：拖拽调界时当前区间以实线划线样式实时重绘（取代灰色选区带），
    把手保留。
  - **跨页续选双向**：起始把手拖到页顶按住 → 翻上一页；结束把手拖到页底按住 → 翻下一页；
    翻页后选中「翻页边 → 手指位置」的内容，会话内可多次翻页，松手按完整章内区间落一条划线。
  - **书签改页面级交互**：顶栏按钮 + 阅读区下拉手势做**当前页书签 toggle**，自动记录
    （页位置 + 页文本为标题，无编辑弹层），页角标随快照下发。
  - **反馈最小化**：创建/删除/想法保存均无 toast（划线与虚线的呈现本身即反馈）；仅复制保留
    toast（剪贴板不可见需确认）。
  - 「复制」统一复制**划线选中的原文**。
- 2026-09-10（实施回填，v2）：三处实施精化——
  - 点按场景想法弹层的预览与提交携带 `findMarking` 的标记**完整原文**（跨行
    标记按 run 行内截段提交会同锚点不命中、另落重复记录）；松手场景仍为本地
    选区文本。
  - 落库后选区**冻结**：把手停用、浮条动作绑定落库时捕获的原始锚点；调界仅
    发生在落库前（防跨锚点重复标记）。
  - 跨页续选的翻页归属采用**越出边归属**：空命中（拖出文本行盒）按越出边
    归属把手侧——页顶外 = 起始侧（向前）、页底外 = 结束侧（向后）；长按新建
    拖拽期同样判定，单页选区拖出页边按住即进入续选。

## 1. 背景

宿主 MD3 阅读器的笔记为「划线体系」：`book_marks` 表，锚点 `TextProcessAnchor`
（chapterIndex / chapterPosition / selectedText / contextBefore / contextAfter /
normalizedTextHash）+ 样式 `TextProcessStyle`（underlineMode：1 实线 / 2 虚线 / 3 波浪 /
4 双线 / 5 SVG，bg 高亮，textColor 字体色）+ note 备注；保存后宿主重排当前章，标记经
ContentProcessor → 排版元素样式进入页面绘制。书签为 `bookmarks` 表，宿主有「当前页快速
书签」先例（页位置 + 页文本为标题，同页多条时删最近一条）与每页书签角标
（`ReaderPage.decoration.bookmarkBadge`）。

eink 模块（v1 已实施）：快照行携带章内位置桥（chapterPositions/top/bottom/decorations），
模块自持选择交互，经可选端口 `ReaderSelectionEngine` 落库。v2 在此基座上重设计交互：
选择即划线、点按再操作、跨页续选、页面级书签。数据与宿主同库同构，两模式互认。

## 2. 范围

目标：

- 选择即划线：松手自动落实线划线 + 浮条三键（复制/写想法/删除）；拖拽期实时下划线预览。
- 跨页续选（双向：页顶按住向前、页底按住向后，会话内多次翻页）。
- 点按已有标记：划线 → 三键浮条；想法 → 浮窗（想法内容 + 三键）；写想法弹层（新建/编辑，
  转虚线）。
- 书签页面级：顶栏按钮 + 下拉手势 toggle、页角标。
- 保留：页面装饰渲染（实/虚/波/双原生，SVG 降级实线，TEXT 不渲染）。

非目标（后续切片）：

- 书签/笔记列表查看与点击跳转。
- 划线样式配置（明示不做，见决策记录）。
- 向前翻页手势（阅读页翻页仍 next-only；跨页续选的「向前」指选区扩展方向）。
- 朗读/搜索/词典等其他宿主能力。

## 3. 契约变更（AAR 破坏性变更，minor）

`ReaderPageLine`（v1 已实施）不变。v2 变更四处：

### 3.1 `ReaderDecorationRun` 增加 markingId

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
     * （1 实线 / 2 虚线 / 3 波浪 / 4 双线 / 5 SVG 花色；0 = 无下划线）。
     * 模块渲染：1/2/3/4 原生绘制，5 及未知值降级为实线（明示不支持花色）。
     */
    val underlineMode: Int,

    /** true = 背景高亮带（TextProcessStyle.bgColor 非空的标记）。 */
    val highlight: Boolean,

    /**
     * 标记身份（宿主 book_marks.id，含宿主高亮规则等非用户标记来源的
     * 合成 id）。点按命中 → 端口操作（findMarking/deleteMarking）的定位键。
     * 宿主实现义务：装饰合并按 markingId + 样式签名分组，不同标记不并入
     * 同一 run。
     */
    val markingId: String,
)
```

### 3.2 `ReaderPageSnapshot` 增加 bookmarkBadge

```kotlin
    /**
     * 当前页是否带有书签角标（宿主 page.decoration.bookmarkBadge 原样
     * 拷贝）。模块页角绘制角标，顶栏书签按钮的选中态据此推导——模块不
     * 自持「当前页是否有书签」状态。
     */
    val bookmarkBadge: Boolean = false,
```

### 3.3 端口 `ReaderSelectionEngine`（v2 形态）

```kotlin
/**
 * 选区语义与批注落库端口：宿主负责锚点构造（上下文与哈希）并写
 * book_marks，提供页面级书签 toggle。模块不复制这些规则。
 *
 * 可选端口（同 appUpdateEngine 先例）：注册表缺失本端口时，模块降级——
 * 长按选择整体不启用（松手无动作、无浮条，选择交互无可用出路），
 * 下拉书签与顶栏书签钮隐藏，不做假死路径。
 */
interface ReaderSelectionEngine {

    /**
     * 保存标记（同锚点 upsert，创建/写想法/编辑想法复用）。
     * [ReaderSelectionCommit.thought] = false → 划线（实线，note 空串）；
     * true → 想法（虚线 + note）。虚线颜色取宿主默认，样式由宿主桥写入
     * TextProcessStyle（underlineMode 1/2）。
     * 宿主落库后自行触发当前章重排（保持页内位置），新快照经
     * onContentUpdated 携带装饰推送——模块不请求刷新。
     * false = 落库失败（模块提示「保存失败」并恢复现场）。
     */
    suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean

    /**
     * 删除标记。false = 删除失败（模块提示并保留现场）。
     * 宿主删除后触发当前章重排（同 saveMarking 推送路径）。
     */
    suspend fun deleteMarking(markingId: String): Boolean

    /**
     * 读取标记详情（点按想法时浮窗展示与写想法预填）。
     * null = 标记不存在（换源清理等，模块按标记失效处理，不弹浮窗）。
     */
    suspend fun findMarking(markingId: String): ReaderMarkingDetail?

    /**
     * 当前页书签 toggle（宿主快速书签语义：同页已有多条时删最近一条）。
     * 自动记录：页位置 + 页文本为标题，无编辑层。
     * 宿主落库后触发当前章重排（角标随新快照推送）。
     * null = 无会话书；true = 本次添加；false = 本次移除。
     */
    suspend fun togglePageBookmark(): Boolean?
}

/** 标记详情（点按浮窗与写想法预填）。 */
@Stable
class ReaderMarkingDetail(
    /** 划线选中的原文。 */
    val selectedText: String,

    /** 想法内容（划线为空串）。 */
    val note: String,

    /** true = 想法（虚线）；false = 划线（实线）。 */
    val thought: Boolean,
)

/** 标记提交载荷。 */
@Stable
class ReaderSelectionCommit(
    /** 选区所在章节下标。 */
    val chapterIndex: Int,

    /** 章内字符区间 [start, end)（UTF-16、语义正文空间）。 */
    val start: Int,
    val end: Int,

    /** 选中文本（按行拼接、段落间隙以换行连接；跨页选区为跨页累计拼接）。
     *  宿主保存时以其在章节全文窗口搜索定位。 */
    val selectedText: String,

    /** 想法内容（划线为空串）。 */
    val note: String,

    /** true = 想法（虚线样式）；false = 划线（实线样式）。 */
    val thought: Boolean,
)
```

移除（v1 → v2）：`resolveSelection`（松手即存 + 点按走 findMarking 后无消费方）、
`saveBookmark`（书签改页面级 toggle）、`ReaderSelectionDraft`、commit 的
`bookmarkText`/`bookmarkContent` 字段。注册仍为可选端口。

### 3.4 位置口径

- 一律 **UTF-16 索引**，语义正文空间；标题行独立空间、无正文语义。
- 选区含标题行：不落划线（静默忽略，宿主同款约束）。
- **选区允许跨页**（v2）：章内区间跨页累计；跨页选中文本由模块按页段累计拼接
  （页边界若在段内则无分隔符，段落间隙以换行连接），宿主窗口搜索定位兜底。

### 3.5 数据流（v2）

```text
长按/拖拽（模块本地：命中 → 选词 → 把手，实时实线预览；页顶/页底按住翻页续选）
  └─松手──► saveMarking(thought=false) ─► 宿主落库+重排 ─► 新快照呈现划线
         └─浮条【复制】【写想法】【删除】
             ├─复制──► 剪贴板 + toast「已复制」，收浮条清选区
             ├─写想法──► 想法弹层（选文预览+输入）──► saveMarking(thought=true, note)
             │                                          └─► 重排后虚线呈现
             └─删除──► deleteMarking ─► 重排后消失
                      （仅点按浮条；松手浮条删除键置灰——无 markingId）
点按已有标记 ─► 命中 decoration.run（markingId 非空方为用户标记，空串为
                宿主高亮规则等非用户来源视为未命中）─► markingId
  ├─划线──► 浮条三键（同上）
  └─想法──► findMarking ─► 浮窗（想法内容 + 三键）；写想法 = 编辑
下拉/顶栏书签 ──► togglePageBookmark ─► 宿主落库+重排 ─► 角标随新快照刷新
```

## 4. 模块交互（v2 状态机）

选择状态完全在模块本地；宿主仅经端口收到落库/删除请求：

```text
阅读态 ──长按(系统 longPressTimeout，正文行上)──► 选词态
        命中测试：y → 行盒 top/bottom 定行；x → 行内逐字符宽度测最近字符
        选词：BreakIterator 词区间吸附（snapToWordRange）；触觉反馈一次
选词态/调界态 ──拖把手──► 调界态：实时实线划线预览随字符数变化重绘 + 把手
  调界中 ──起始把手至页顶按住──► 翻上一页，选区续接「本页底部 → 手指位置」
        ──结束把手至页底按住──► 翻下一页，选区续接「本页顶部 → 手指位置」
       （续选会话：pageVersion 清态效应对选区挂起；选中文本按页段累计；
        会话内可多次双向翻页；翻页刷新期间端点吸附翻页边后跟随手指；
        触发带归属含越出边兜底，见下）
       ──松手──► saveMarking(thought=false) 落划线 + 浮条三键
              + 选区冻结（落库发起即冻结，见下）
浮条态（已冻结）──复制──► 剪贴板 + toast「已复制」，收浮条清选区并解冻
       ──写想法──► 想法弹层 ──保存──► saveMarking(thought=true)
       ──删除──► deleteMarking（无 toast；仅点按场景——松手场景无
                 markingId，删除键置灰不可达）
       ──点浮条外──► 收浮条清选区（已落的划线保留，删除走点按）
阅读态 ──点已有标记（markingId 非空方为用户标记）──► 划线 → 浮条三键；
        想法 → findMarking → 浮窗（内容+三键，写想法 = 编辑）
阅读态 ──阅读区竖直下拉（无选区、非把手）──► togglePageBookmark
顶栏书签钮 ──点击──► 同一 toggle；选中态 = 当前页快照 bookmarkBadge
任意含选区态 ──跳章/自动翻页（非续选会话）──► 清选区（随行解冻）
```

- 把手样式与宿主一致（竖线+圆点），命中热区 28dp；把手命中优先于翻页手势；
  选区存在期间水平拖动手势不触发翻页。
- 页顶/页底触发翻页的判定：被拖端点拖入页顶/页底触发带（首/末行行盒，各约
  一行高）并保持按住超过系统长按时值 → 翻页；一次按住只触发一次，翻页后需
  重新拖出再进触发带。归属规则（实施精化）：非空命中按把手侧判带（起始把手
  只判页顶、结束把手只判页底）；空命中（拖出文本行盒）按**越出边归属**——
  页顶外 = 起始侧、页底外 = 结束侧（会话内反向拖出页缘的双向翻页依赖此
  归属），行盒间空档不判触发、维持最近归属。长按新建拖拽期同样判定：
  单页选区拖出页边按住同样进入续选会话。
- 落库冻结（实施精化）：松手提交发起即冻结选区——把手停用（只读展示），
  浮条动作绑定落库时捕获的原始锚点快照（写想法同锚点 upsert 命中已落记录，
  不产生第二条标记）；调界仅发生在落库前。落库失败同样保持冻结（再调整会
  漂移锚点，冻结是安全侧），选区清空（复制/点外/页变）后解冻。
- 浮条/浮窗为设计系统 Action Bar 风格（实心反白键、无阴影、零动画直切）。
  删除键按时序可用：松手场景置灰（saveMarking 无 markingId 回传），点按
  标记场景即时可用。
- 下拉手势：竖直位移超阈值（约 80dp）且 dominant 方向为下 → toggle；与横向
  翻页、把手拖拽、长按选择互斥（无选区时才生效）。

## 5. 想法弹层

EInkDialog（设计系统 §20/§21，IME 避让由弹框自带 imePadding 承担）：

- 顶部只读预览选中文本（超长截断展示，落库不受影响）：松手场景取本地选区
  文本；点按场景取 `findMarking` 的标记**完整原文**（可跨行），不经快照
  命中 run 的行内截段——跨行标记按截段提交会同锚点不命中、另落重复记录
  （v2 Task 6 修复轮裁定）。预览不经端口二次解析。
- 想法输入框：新建为空；编辑预填 `findMarking` 的 note。
- 保存/取消。保存 = `saveMarking(thought=true, note)`；成功无 toast（虚线呈现即反馈），
  失败 tip「保存失败」弹层保留可重试。编辑（点按想法浮窗）模式底部另加
  「复制」「删除」文字钮（复制完整原文、删除即时可用）——保存即「写想法」
  编辑，提交选区以完整原文覆写 selectedText、同锚点 upsert。

## 6. 页面装饰渲染

- 下划线：基线下方行盒高度的 12%，模式 1 实线 / 2 虚线 / 3 波浪 / 4 双线原生绘制，
  颜色走模块主题（黑）；模式 5 SVG 降级实线；TEXT 字体色效果不进装饰数据。
- 高亮带：主题灰底色；合并（markingId + 样式分组）在宿主映射器执行，模块直绘。
- **实时下划线预览**：调界态的选区预览直接复用装饰绘制路径——以当前区间构造临时实线
  run，随拖拽逐帧重绘（模块本地，零宿主开销）；预览层垫在页画布下方（正文
  笔迹覆盖线体，观感与正式划线一致），把手叠加其上。灰色选区带取消。
- 刷新成本：装饰与预览均走模块画布本地重绘；落库后的确认刷新随宿主重排整页一次。

## 7. 宿主 bridge 实现

- **映射器**：装饰提取按 markingId + 样式签名分组合并（不同标记不并入同一 run）；
  拷贝 `page.decoration.bookmarkBadge` 进快照。
- **saveMarking**：锚点构造同 v1（48 字上下文 + 哈希 + 窗口搜索定位；先提示位
  精确、后窗口回搜，定位不到视为选区失效从严返回 false），
  `thought` → underlineMode 1/2；复用 `SaveMarkingUseCase.save`（同锚点原地更新承担
  写想法转换/编辑）；成功后 `ReaderEngineImpl.relayout()` 推送。
- **deleteMarking**：按 id 删 `book_marks`（gateway/DAO delete）+ relayout。
- **findMarking**：按 id 读 gateway → `ReaderMarkingDetail`（selectedText/note/thought
  由 anchor.selectedText、note、style.underlineMode 推导）。
- **togglePageBookmark**：宿主快速书签语义（当前页无则加——页位置 + 页文本为
  标题、剔除排版占位符；有则删离当前阅读位置最近的一条）+ relayout（角标
  刷新）；toggle 全程互斥锁（先查再写非原子，防快速连点重复插入）。

## 8. 测试与验证

| 层 | 内容 |
|---|---|
| 模块单测 | 跨页会话的页段累计与拼接（前/后向、多次翻页、段内/段落边界 gap 规则、重叠覆盖合并）；页顶/页底触发带判定（含越出边归属与 FlipTrigger 一次武装）；点按命中 run → markingId；想法/划线的浮条状态推导 |
| 宿主 bridge 单测 | 映射器按 markingId+样式分组合并、bookmarkBadge 拷贝；定位窗口搜索与上下文钳制、thought→样式 1/2 纯函数锚定；deleteMarking/findMarking/togglePageBookmark 为 gateway 薄委托（编译 + 真机覆盖） |
| 降级测试 | 注册表无端口 → 松手不落划线、点按无动作、下拉与顶栏书签钮隐藏，无假死路径 |
| 真机手工 | 逐项清单见 §9 |

模块单测沿用既有 seam 惯例；验证命令 `:modules:eink:testDebugUnitTest`、
`:app:compileAppDebugKotlin`、`testAppDebugUnitTest`。

## 9. 风险与真机验证清单（v2 口径）

结构性风险（设计层已建模，真机复核）：

- **续选会话与 pageVersion 清态的挂起**：翻页不清选区的窗口期内，宿主推来的非续选
  重排（如追更）不得撕裂会话。
- **AAR 版本分栈**：破坏性契约变更随 minor 升级，宿主与模块版本配套按发布纪律更新。

真机手工清单（v2 全量）：

1. **松手即划线全链路**：裸长按（不拖拽，选中一词即落划线）、长按拖拽延伸松手、
   落库后把手冻结不可再调界、浮条写想法不产生第二条标记（原始锚点 upsert）。
2. **浮条三键**：复制（toast「已复制」+ 清区）、写想法（弹层 → 保存 → 划线转
   虚线）、删除（点按场景即时可用、松手场景置灰）。
3. **点按已有标记**：划线 → 浮条三键；想法 → 浮窗（note 预填 + 复制/删除，
   保存即编辑）；标记失效（换源清理）静默回落分区行为。
4. **跨页双向续选**：多页累计、跨段落/跨标题边界拼接、前向翻页后反向再翻
   （越出边归属）、松手按完整章内区间落一条划线且宿主定位命中。
5. **触发带**：页顶/页底按住触发翻页的端点吸附与跟手性；正常拖拽调界、行盒
   间空档、空白区长按拖拽死手势不得误触发。
6. **实时下划线预览观感**：拖拽期逐帧重绘在真机灰屏上的刷新波形与残影。
7. **页面书签**：顶栏钮选中态（随快照 bookmarkBadge）、下拉 toggle、页角标随
   重排刷新、同页多条时删最近一条；跨模式互认（eink 书签/划线/想法 ↔ 宿主
   显示，宿主建的波浪/双线在 eink 如实显示）。
8. **想法弹层软键盘**：IME 避让不压缩阅读视口，正文不重排不跳页。
9. **降级宿主**：注释 `EInkBridge` 的 selectionEngine 装配 → 长按选择整体不启用
   （不选词、松手无动作）、下拉与顶栏书签钮不渲染，无假死路径。
10. **含标题选区**：静默不落划线（含标题的跨页会话同样不落），浮条只留复制键。
11. **onLayoutException 恢复路径**。

## 10. 实施切片（详见实施计划）

1. 契约 v2（DecorationRun.markingId、snapshot.bookmarkBadge、Commit 收敛 + thought、
   ReaderMarkingDetail、端口方法增删）+ 注册表。
2. 宿主映射器（markingId 分组合并 + bookmarkBadge 拷贝）+ 测试。
3. 端口实现：saveMarking(thought)/deleteMarking/findMarking + 测试。
4. 书签链路：togglePageBookmark 实现 + 页角标绘制 + 顶栏按钮 + 下拉手势。
5. 模块选择重构：实时下划线预览 + 松手自动落划线 + 浮条三键（复制/写想法/删除）。
6. 点按标记：命中 → markingId → 浮条/浮窗 + 想法弹层 + 删除。
7. 跨页续选双向会话。
8. 退役清理（resolveSelection/saveBookmark/书签弹层）+ 全量验证 + 文档回填。

每片独立可回滚，交付时按 AGENTS.md 门禁说明验证与未验证风险。
