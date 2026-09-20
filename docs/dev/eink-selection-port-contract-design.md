# eink 选区批注端口契约重构设计（ReaderSelectionEngine 契约 v2）

状态：设计已确认（2026-09-20），待实施。
日期：2026-09-20。
关系：修订 [eink-selection-bookmark-marking-design.md](./eink-selection-bookmark-marking-design.md) 定下的
`ReaderSelectionEngine` 端口契约；**交互语义基本不变**（v2.2 点击式选择、划线/想法类型
语言、页面书签 toggle 均维持原设计），唯一故意的边界行为变化见「行为变化清单」，
本设计其余只重构契约的表达方式。

## 决策记录

- 2026-09-20（用户确认）：契约重构取**方案 A——状态机进签名**。转换语义从 KDoc 升级为
  方法结构：`createMarking` / `updateMarkingNote(id, note)` / `togglePageBookmark(content 载荷)`。
  否决 **B**（签名不动，只补 KDoc + 模块侧端口契约测试）：签名继续不表达转换关系，防不住
  宿主按字面实现成重复插入；否决 **C**（进一步拆成书签/笔记两个注册端口）：能力位
  `supportsMarkings` / `supportsPageBookmark` 已表达该粒度，拆端口在两个移植目标上重复这套
  机制，迁移成本不成比例。
- 2026-09-20（用户确认）：`thought` 字段从契约删除，笔记类型恒由 `note.isNotBlank()` 派生
  （模块 UI 现状 `markingThoughtFromNote` 即此口径）。消除"note + thought 两字段一份不变量、
  一致性靠宿主自觉"的契约漏洞。
- 2026-09-20（用户确认）：页面书签 toggle 增加载荷 `ReaderPageBookmarkContent(chapterName,
  pageText)`——引用内容（书签显示文本）由模块携带，宿主不再自定；同页判定与"删最近一条"
  仍属宿主分页事实，不进载荷。
- 2026-09-20（实施期修正，质量审查发现）：初稿"pageText 行间 `\n` 与宿主 `page.text`
  同构"的事实前提**有误**——宿主只在**段落边界**插 `\n`（`ReaderPaginator`：
  `appendSeparator = hasFollowingBlock`；同段折行不插），空行分隔为 `\n\n`（Spacer
  块，有 `ReaderPaginatorTest` 固化）。修正：`ReaderPageLine` 新增
  `paragraphBreaksAfter: Int`（本行之后的段落边界数，0 = 同段折行续行；1 = 段落
  结束；空行/占位块累加），宿主映射器从排版块结构填充，模块按
  `"\n".repeat(breaksAfter)` 拼装——同构口径由此成立，且模块仍自有拼装实现。

## 背景与问题

现契约（eink-lib `contract/ReaderSelectionEngine.kt`，0.6.0 形态）三个结构性缺陷：

1. **状态转换隐形，宿主可实现成重复添加。** 划线 ⇄ 想法是同一条 `book_marks` 记录的两种
   状态（实线 note 空 / 虚线 note 非空），但契约把它们建模为 `saveMarking(commit)` 的反复
   调用，"同锚点 upsert 原地更新"只活在 KDoc 与宿主 `SaveMarkingUseCase.save` 实现里，
   签名零表达。换宿主按字面实现成 insert，每次写想法就多一条记录。
2. **更新走重新锚点定位，失败模式多余。** 每次写/清空想法都携完整选区重新定位锚点。宿主
   `locateSelectionInContent` 的归一化兜底（修复真机"笔记 Tab 里看得见、点进去改想法保存
   失败"）正是"更新被建模为重新提交"的补丁；模块侧 `ReaderMarkingBar.commitSelection` 的
   "原文覆写行内截段，防跨行 upsert 不命中"是同一根因的另一层补丁。而所有转换入口手里
   都有 `markingId`（装饰 run / 操作条 target），契约却不用。
3. **`togglePageBookmark()` 零载荷，书签显示跨宿主漂移。** 书签记录的 `chapterName`/
   `bookText` 全由宿主实现随手决定（`ReaderSelectionEngineImpl` 用 displayTitle + 页文本 +
   自家占位符清理），而书签 Tab 渲染的"页面文本摘录"是 eink 模块的产品语义。与
   `saveMarking` 满载荷提交形成不对称。

## 契约设计

```kotlin
interface ReaderSelectionEngine {
    val supportsMarkings: Boolean get() = true      // 语义不变
    val supportsPageBookmark: Boolean get() = true  // 语义不变

    // —— 笔记：同一条记录的两种状态（划线 ⇄ 想法），转换只能走 update ——

    /** 新建笔记（仅"新选区"入口可达）。note 空白 = 划线（实线）；非空 = 想法（虚线）。 */
    suspend fun createMarking(commit: ReaderSelectionCommit): Boolean

    /** 唯一状态转换路径：按 id 改想法，锚点与选区不变。
     *  note 空白 → 划线（实线）；非空 → 想法（虚线）。
     *  null = 标记不存在（换源清理等）；false = 更新失败；true = 成功。 */
    suspend fun updateMarkingNote(markingId: String, note: String): Boolean?

    suspend fun deleteMarking(markingId: String): Boolean            // 语义不变
    suspend fun findMarking(markingId: String): ReaderMarkingDetail? // 语义不变

    // —— 页面书签：引用内容由模块携带 ——

    /** 同页判定（页正文区间）与"删最近一条"仍是宿主分页事实。
     *  三态返回语义不变：null = 无会话书/无法定位；true = 添加；false = 移除。 */
    suspend fun togglePageBookmark(content: ReaderPageBookmarkContent): Boolean?
}
```

载荷变化：

- `ReaderSelectionCommit(chapterIndex, start, end, selectedText, note)`——**删除 `thought`**；
  `note` 为初始想法（可空串）。仅新建路径使用。
- `ReaderMarkingDetail(selectedText, note)`——**删除 `thought`**（模块零消费，弹层只读
  `note`；TocScreen 的 `thought` 来自 `MarksEngine.MarkingUiModel`，另一端口，不动）。
- 新增 `ReaderPageBookmarkContent(chapterName, pageText)`：
  - `chapterName` = 当前页快照 `title`（与宿主 `page.chapterTitle` 同源，已核实
    `ReaderPageSnapshotMapper`：`title = page.chapterTitle`）。
  - `pageText` = 模块从快照行拼装：行内 `chunks` 连接，行间按上一行的
    `ReaderPageLine.paragraphBreaksAfter` 插 `"\n".repeat(n)`（0 = 同段折行无
    换行；1 = 段落结束；空行/占位块累加），与宿主 `page.text` 的段落边界口径
    同构（`ReaderPaginator` 按 `appendSeparator = hasFollowingBlock` 插 `\n`）。
    图片页模块拼装不含 `\uFFFC` 占位（模块无此字符语义），属可接受差异。
  - 宿主存储时可对自家渲染产物做清理（现有 `[袮꧁]` 占位符剥离 + trim），属存储规范化，
    不构成显示语义。

三条不变量由签名承载：

1. **转换按 id**——`createMarking` 只在新选区入口可达，重复添加结构性不可能。
2. **类型单事实源**——划线/想法恒等于 `note.isNotBlank()`，契约不再有两个字段一份不变量。
3. **书签显示归模块**——`chapterName`/`pageText` 模块提供，宿主原样（除自家渲染产物清理）
   落库。

## 模块侧改动（eink-lib）

- `ReaderViewModel`：`saveMarking(sel, note, thought)` 拆为
  `createMarking(sel, note)` 与 `updateMarkingNote(markingId, note)`。
- `ReaderThoughtDraft` 增加 `markingId: String?`（null = 新建；非空 = 更新）：
  - 选区操作条新选区「想法」→ `null`；
  - 点按标记操作条 / 选区落在标记上的「想法」→ `target.markingId`；
  - 弹层确认分派：`null` → `createMarking(draft.selection, note)`；非空 →
    `updateMarkingNote(id, note)`。
- LINE 动作 → `createMarking(target, note = "")`（原 `saveMarking(thought = false)`）。
- 更新失败分支：`false` → toast「保存失败」弹层保留可重试（现状）；`null` → toast
  「标记已失效」并收弹层（与 `findMarking` 失效的静默处理不同——用户已输入文本，静默
  丢弃不友善）。
- `ReaderMarkingBar.commitSelection` 的"原文覆写防 upsert 不命中"职责消失，退化为想法
  弹层的预览原文（跨行标记预览完整原文仍有意义；字段保留，注释改写）。
- `togglePageBookmark` 载荷组装归 VM（`uiState` 持有当前页快照）：`chapterName` 取快照
  `title`，`pageText` 按上文口径拼装；Screen 的两个 toggle 入口（顶栏按钮、下拉手势）
  零改动。
- `markingThoughtFromNote` 删除：唯一调用点是 `saveMarking` 的 thought 序列化，随字段
  消失；类型语言退化为"note 内容"，不再有独立函数。

## 宿主侧改动（app `eink/bridge/ReaderSelectionEngineImpl.kt`）

- `createMarking`：原 `saveMarking` 逻辑不变（等待正文 → 定位 → 锚点构造 → 落库 →
  relayout），`note` 原样存储（纯空白可归一为空串，属存储规范化），样式
  `einkMarkingStyle(note.isNotBlank())`。宿主
  `SaveMarkingUseCase` 的同锚点 upsert 保留为创建路径安全网（契约不要求、也不禁止）。
- `updateMarkingNote`（新增）：`getById` 不存在 → `null`；存在 → 只改 `note` 与
  `styleJson`（underlineMode 按 note 派生：1 实线 / 2 虚线），保留 id 落库 → relayout →
  `true`。**不走选区定位**；`locateSelectionInContent` 归一化兜底只剩 create 路径需要。
- `togglePageBookmark(content)`：`chapterName`/`bookText` 改用载荷（`bookText` 落库前仍过
  `[袮꧁]` 清理 + trim），页区间查询与最近删除逻辑不动。
- 快照映射器为 `ReaderPageLine` 填充 `paragraphBreaksAfter`（新增契约字段，见决策记录
  实施期修正）：从排版块结构推导（同块折行 0、块末 1、空行/Spacer 块逐个累加）；
  末行值不参与拼装（对齐宿主 `hasFollowingBlock` 尾行为）。
- 测试（`ReaderSelectionEngineImplTest`）更新并新增：
  - 划线 → 写想法 → 清空 全程一条记录且 id 不变（状态机回归基线）；
  - `updateMarkingNote` 不存在 → `null`；
  - 书签落库字段来自载荷；
  - 同构对照门禁：同一排版 fixture 下模块拼装 `pageText` 与宿主 `page.text`
    断言相等（占位符/`\uFFFC` 按既定差异声明），防拼装口径再次静默漂移。

## 迁移与兼容

- 破坏性契约变更：并入 eink-lib 已预留的 0.7.0「契约清理轮」发布列车（当前
  0.6.1；在 `modules/eink/build.gradle.kts` 的 0.7.0 版本注释追加本轮条目，列车
  齐发时统一翻版本号，不在本轮单独翻）。
- 双仓落地：契约 + 模块 UI/VM 改动在 eink-lib 子模块仓（`eink/lib` 孤儿分支，
  当前 detached HEAD，需先建工作分支）；宿主 bridge 改动 + 子模块指针推进在主仓。
  两仓各自提交，主仓提交含指针推进。
- 两个移植目标分支（legadoM-Ink / legado-with-MD3）本就待重移植，随重移植直接适配新
  契约，不写旧签名兼容层。
- 文档同步：`contract/README.md` 端口总表的 `ReaderSelectionEngine` 条目补状态机
  语义；`docs/eink-porting.md` 已不存在（移植手册集中于模块内
  `contract/EINK-PORTING.md`，其 §1 端口清单不涉方法签名，无需改动）；
  [eink-selection-bookmark-marking-design.md](./eink-selection-bookmark-marking-design.md)
  决策记录追加一条指向本设计。

## 行为基线与验证

行为变化清单（唯一故意变化，其余行为不得回退）：

- **部分重叠选区的「想法」**：现状为"选区与已有标记任意相交 → 操作条切到标记语境
  （LINE 隐藏、DELETE 出现），但「想法」按新选区锚点 upsert 不命中 → 另落一条重叠
  记录"——恰是本次重构要消灭的重复添加类别。改后：交叠语境的「想法」= 按交叠标记的
  id 转 `updateMarkingNote`，不再产生重叠新记录；交叠多条时取首条命中（与现有 DELETE
  口径一致）。要基于部分重叠文本新建笔记，先删原标记或调整选区避开。

基线（重构门禁，行为不得回退）：

- 划线 ⇄ 想法转换全程一条记录、id 不变（现由宿主锚点 upsert 保证；改后由 update-by-id
  保证，宿主测试固化）。
- 笔记列表（`MarksEngine`）、装饰渲染（实线/虚线、纯黑）、删除与失效路径行为不变。
- 书签落库字段与现行为一致：`chapterName` 同源 `page.chapterTitle`；`pageText` 与宿主
  `page.text` 段落边界口径同构（同段折行无换行、段末一个换行、空行双换行；图片
  `\uFFFC` 差异已知可接受）。
- 编辑已存想法不再可能因选区定位失败而「保存失败」（原真机 bug 路径随更新重定位一并
  消除）。

验证命令（按风险取最小充分集）：

```powershell
.\gradlew.bat :app:compileAppDebugUnitTest
.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderSelectionEngineImplTest"
.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture --continue --no-configuration-cache
git diff --check   # 两仓各自
```

未验证项（实现切片标注）：真机上 eink 创建的书签 `bookText` 与完整模式创建的逐字一致性
（占位符清理口径）需人工比对一次。

## 范围外

- `MarksEngine` / `MarkingUiModel.thought`（列表端口的派生字段）不动。
- 端口不拆分、不改名；`EInkEngineRegistry` 注册结构不动。
- 选区交互、想法弹层形态、书签手势等 UI 行为不变。
- 锚点存储模型（`TextProcessAnchor`、`SaveMarkingUseCase` 的 upsert 键）不动。
