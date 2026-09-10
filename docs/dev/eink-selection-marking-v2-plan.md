# eink 划线/想法笔记 + 页面书签（v2 交互重设计）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 eink 阅读页的选择交互重设计为「选择即划线」：松手自动落实线划线笔记，浮条三键（复制/写想法/删除），点按标记再操作（想法=虚线浮窗），跨页双向续选；书签改页面级交互（顶栏按钮 + 下拉手势 toggle + 页角标）。

**Architecture:** 契约 v2（装饰 run 携带 markingId、快照携带 bookmarkBadge、端口收敛为 saveMarking(thought)/deleteMarking/findMarking/togglePageBookmark）；模块自持选择状态机重做（实时下划线预览、跨页会话文本累计）；宿主桥补页面书签通道（自有书签状态缓存 + pager 当前页元数据访问点，不依赖宿主 MD3 路径的 `ReaderBookmarkState`/`currentCanvasPage`）。

**Tech Stack:** 同 v1（Kotlin、Compose、BreakIterator、Room 经既有 repository/usecase、JUnit4 纯 JVM）。

**规格：** `docs/dev/eink-selection-bookmark-marking-design.md`（v2 章节为准）。

## Global Constraints

- JDK 21；模块单测 `:modules:eink:testDebugUnitTest`（纯 JVM，当前基线 144 例只增不减）；宿主 `testAppDebugUnitTest`（已知基线 5 失败同集零新增）；快速编译 `:app:compileAppDebugKotlin`。
- **提交纪律：工作区有用户暂存的未提交文件（plan 文档），所有提交必须用显式路径提交**：`git add <files>` 后 `git commit -m "<msg>" -- <files>`，绝不裸 `git commit`。
- 位置口径 UTF-16、语义正文空间；类型语言：划线 = underlineMode 1 + note 空，想法 = underlineMode 2 + note 非空。
- 反馈最小化：创建/删除/想法保存无 toast，仅复制有（「已复制」）；失败路径有「保存失败」。
- 可选端口缺失降级：长按选择整体不启用、下拉书签与顶栏书签钮隐藏，不做假死路径。
- 装饰合并按 markingId + 样式签名分组（不同标记不并入同一 run）；TEXT 字体色仍不渲染。
- 所有文本改动 `git diff --check`；提交信息 `feat(eink)/fix(eink)/refactor(eink): ...` 中文。
- v1 已实施代码是本计划的模式来源：编辑弹层护栏（页变清三态 + saving 防重入 + 解析后失效守卫）在 `ReaderScreen.kt` Route 区，新手势/弹层一律沿用。

## 文件总览

```text
modules/eink/src/main/java/io/legado/app/eink/
  contract/ReaderPageSnapshot.kt            [改] DecorationRun+markingId；snapshot+bookmarkBadge
  contract/ReaderSelectionEngine.kt         [改] 端口 v2（增删方法、Commit 收敛、ReaderMarkingDetail）
  feature/reader/selection/ReaderTextSelection.kt   [改] 跨页会话纯逻辑（页段累计/拼接/范围→行内 run）
  feature/reader/selection/ReaderSelectionOverlay.kt [改] 实时下划线预览 + 标记命中 + 浮条三键/浮窗
  feature/reader/selection/ReaderSelectionSheets.kt  [改] 想法弹层（书签弹层退役）
  feature/reader/ReaderScreen.kt            [改] 状态机重接线 + 下拉手势 + 顶栏书签钮参数
  feature/reader/ReaderViewModel.kt         [改] 端口 v2 方法面
  feature/reader/ReaderMenus.kt             [改] 顶栏书签按钮
  feature/reader/ReaderPageSnapshotCanvas.kt [改] 角标绘制（badge 布尔）
app/src/main/java/io/legado/app/eink/bridge/
  ReaderPageSnapshotMapper.kt               [改] markingId 分组合并 + bookmarkBadge 参数
  ReaderSelectionEngineImpl.kt              [改] 端口 v2 实现
  EInkBookmarkState.kt                      [新] 桥自有书签状态缓存（flowByBook 收集）
  ReaderChapterPager.kt                     [改] currentPageMeta() 访问点 + 角标计算入快照
  ReaderEngineImpl.kt                       [改] loadBook/reloadBook 挂接 EInkBookmarkState.attach
modules/eink/docs/eink-porting.md           [改] 端口 v2 行
测试：
  modules/eink src/test .../selection/ReaderTextSelectionTest.kt   [改] 会话累计/拼接/命中
  app/src/test .../bridge/ReaderPageSnapshotMapperTest.kt          [改] markingId 分组/badge
  app/src/test .../bridge/ReaderSelectionEngineImplTest.kt         [改] thought 样式/delete/find
```

---

### Task 1: 端口 v2 增量（只加不删，保持全仓可编译）

**Files:**
- Modify: `modules/eink/.../contract/ReaderSelectionEngine.kt`
- Create: `modules/eink/.../contract` 内 `ReaderMarkingDetail`（同文件追加）
- Modify: `modules/eink/docs/eink-porting.md`
- Test: `modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt`（无需新用例，既有可空 getter 用例覆盖）

**Interfaces:**
- Produces（后续任务消费）：`ReaderSelectionCommit(chapterIndex, start, end, selectedText, note, thought)`——本任务先**追加** `note`/`thought` 字段（旧 bookmarkText/bookmarkContent 保留），`deleteMarking(markingId): Boolean`、`findMarking(markingId): ReaderMarkingDetail?`、`togglePageBookmark(): Boolean?`、`ReaderMarkingDetail(selectedText, note, thought)`。契约注释按设计文档 §3.3 全文（KDoc 义务逐成员）。

**Steps:**
- [ ] 按 `docs/dev/eink-selection-bookmark-marking-design.md` §3.3 的类型草图追加：Commit 加 `note`/`thought`（带默认值保持源兼容：`note: String = ""`、`thought: Boolean = false`）；接口加 3 个方法（KDoc 全文照规格）；`ReaderMarkingDetail` 新数据类。
- [ ] `eink-porting.md` 端口行更新为 v2 方法清单（注明 v2 过渡期同时保留 v1 方法，Task 5 收敛移除）。
- [ ] 验证：`./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin`（144/144，编译绿——新方法无实现者会在桥对象编译期报错吗？不会：接口新方法使 `ReaderSelectionEngineImpl` 抽象未实现 → **必须同步在桥对象加 `NotImplementedError("Task 3")` 占位实现三个新方法**，加 KDoc「Task 3 实现」）。
- [ ] Commit: `feat(eink): 端口 v2 增量——deleteMarking/findMarking/togglePageBookmark 与 Commit.thought`

### Task 2: 映射器 markingId 分组合并 + bookmarkBadge 参数

**Files:**
- Modify: `modules/eink/.../contract/ReaderPageSnapshot.kt`（DecorationRun 加 `markingId: String`——**破坏性**；snapshot 加 `bookmarkBadge: Boolean = false`）
- Modify: `app/.../bridge/ReaderPageSnapshotMapper.kt`（合并键改 markingId+样式；`mapWithSpecs` 加 `bookmarkBadge: Boolean = false` 参数传入 snapshot；`map` 生产入口加同名参数默认 false）
- Modify: `app/.../bridge/ReaderChapterPager.kt`（`currentPageSnapshot()` 调 `map(...)` 处暂传 `bookmarkBadge = false`，Task 8 接真值）
- Test: `app/src/test/.../bridge/ReaderPageSnapshotMapperTest.kt`

**Interfaces:**
- Produces: `ReaderDecorationRun(start, end, underlineMode, highlight, markingId)`；快照 `bookmarkBadge`。

**Steps:**
- [ ] RED：改既有装饰测试断言为含 markingId 的构造 + 新用例「不同 markingId 相邻同款不合并」（两条 run）、「相同 markingId 相邻同款合并」（一条）。确认编译失败（构造缺参）。
- [ ] GREEN：`LineBuffer` 的 `underlineModes`/`highlights` 旁加 `markingIds: ArrayList<String>`（Text 分支 `element.markingId.orEmpty()`——注意元素字段是 `String?`，null 归一为空串）；`buildDecorations` 合并条件追加 `markingId` 相等，run 携带该 id。`mapWithSpecs`/`map` 加 `bookmarkBadge: Boolean = false` 并传入 `ReaderPageSnapshot(..., bookmarkBadge = bookmarkBadge)`。
- [ ] 同步修既有全部构造点（模块测试 helper `line()`/Task1 适配过的 mapper 测试）补 `markingId`。
- [ ] `./gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest" :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin` 全绿（模块既有测试若构造 DecorationRun 需同步补参）。
- [ ] Commit: `feat(eink): 装饰 run 携带 markingId 分组合并，快照携带书签角标布尔`

### Task 3: 桥端口实现——saveMarking(thought)/deleteMarking/findMarking

**Files:**
- Modify: `app/.../bridge/ReaderSelectionEngineImpl.kt`
- Test: `app/src/test/.../bridge/ReaderSelectionEngineImplTest.kt`

**Interfaces:**
- Consumes: `BookMarkingRepository.getById(id)/delete(id)`（现成，BookMarkingRepository.kt:28-42）；`SaveMarkingUseCase.save`（同锚点 upsert 原地更新）；既有 `locateInContent/extractContext/semanticContent/displayTitle`。
- Produces: 端口三方法生产实现（togglePageBookmark 仍 NotImplementedError("Task 8")）。

**Steps:**
- [ ] RED：测试 `einkMarkingStyle(thought)`——`false` → underlineMode=1、`true` → underlineMode=2（虚线），颜色均 `EINK_MARKING_COLOR`。确认失败。
- [ ] GREEN：
  - `einkMarkingStyle(thought: Boolean)` 替换原单参形态（调用点同步）。
  - `saveMarking`：`val style = einkMarkingStyle(commit.thought)`；thought=true 时 note 允许非空、false 时 note 置空串落库（划线无备注语义在桥内强制）；其余锚点构造与 relayout 推送同 v1（CancellationException 透传 + AppLog 保留）。
  - `deleteMarking`：`ReadBook.book ?: return false`；`runCatching { bookMarkingRepository.delete(markingId); ReaderEngineImpl.relayout(); true }.getOrElse { AppLog...; false }`（CancellationException 透传同款）。
  - `findMarking`：`bookMarkingRepository.getById(markingId) ?: return null`；`ReaderMarkingDetail(selectedText = mark.anchor()?.selectedText.orEmpty(), note = mark.note, thought = mark.style()?.underlineMode == 2)`——`mark.anchor()`/`mark.style()` 是实体上的 JSON 解析扩展（v1 已用 `anchor()`，确认 style() 存在否则局部解析 `GSON.fromJsonObject<TextProcessStyle>(mark.styleJson)`）。
- [ ] `./gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderSelectionEngineImplTest" :app:compileAppDebugKotlin` 绿。
- [ ] Commit: `feat(eink): 桥实现想法/划线双样式保存与按 id 删除读取`

### Task 4: 模块选择重构——松手即存 + 浮条三键（复制/写想法/删除）

**Files:**
- Modify: `modules/eink/.../selection/ReaderSelectionOverlay.kt`（浮条三键换文案与动作；写想法回调）
- Modify: `modules/eink/.../selection/ReaderSelectionSheets.kt`（`ReaderThoughtDialog` 替换书签弹层——选文预览 + 想法输入 + 保存/取消；`ReaderBookmarkEditDialog` 本任务先保留不删，Task 5 退役）
- Modify: `modules/eink/.../ReaderScreen.kt`（Route：菜单动作处理重写；书签/笔记旧链路下线）
- Modify: `modules/eink/.../ReaderViewModel.kt`（`saveMarking(sel, note, thought)` 替换 saveBookmark/旧 saveMarking；`deleteMarking(markingId)`；`resolveSelection`/`saveBookmark` 删除推迟到 Task 5——本任务先停用调用）
- Modify: `modules/eink/.../contract/ReaderSelectionEngine.kt` 无（Task 1 已加）

**Interfaces:**
- Consumes: Task 1 端口、Task 3 桥实现；v1 弹层护栏模式（页变清态、saving 防重入）。
- Produces: VM `suspend fun saveMarking(sel: ReaderSelectionUi, note: String, thought: Boolean): Boolean`、`suspend fun deleteMarking(markingId: String): Boolean`；`ReaderSelectionMenuAction { COPY, THOUGHT, DELETE }`（枚举重命名）；浮条 KDoc 更新。

**Steps:**
- [ ] VM：`saveMarking` 组装 `ReaderSelectionCommit(chapterIndex = engine.currentChapterIndex, start = sel.bodyStart, end = sel.bodyEnd, selectedText = sel.selectedText, note = note, thought = thought)` → port.saveMarking；`deleteMarking` 直传 port。
- [ ] Route 松手路径重做：长按松手（v1 的 onDragEnd）不再只弹浮条——先 `scope.launch { ok = viewModel.saveMarking(selection, "", thought = false) }`，浮条照常展示（落库异步进行，浮条动作不依赖落库结果）；落库失败 toast「保存失败」。
- [ ] 浮条动作：
  - COPY 同 v1（复制选文 + toast + 清）。
  - THOUGHT → 打开 `ReaderThoughtDialog(draft = ReaderThoughtDraft(selectedText = sel.selectedText, note = ""))`（预填空），确认 → `scope.launch { saving 防重入; viewModel.saveMarking(sel, note, thought = true) }`；成功关弹层（选区清理由页变效应承担——落库后宿主重排推进 pageVersion）；失败 toast「保存失败」弹层保留。
  - DELETE → `scope.launch { viewModel.deleteMarking(...) }`——**问题：松手场景 markingId 未知的时序**：落库是异步的，删除可能先于落库完成。**裁定：松手后浮条的 DELETE 在落库完成前禁用（局部 `saving` 态复用），落库成功后启用**；点按场景（Task 6）markingId 现成无此时序。
  - 点浮条外：收浮条清选区，划线保留。
- [ ] 删除/写想法成功均无 toast；失败「保存失败」。
- [ ] 门禁：`./gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin`（144/144）；`git diff --check`。
- [ ] Commit: `feat(eink): 松手自动落划线与浮条三键（复制/写想法/删除）`

### Task 5: 契约收敛——退役 resolveSelection/saveBookmark 与书签弹层

**Files:**
- Modify: `modules/eink/.../contract/ReaderSelectionEngine.kt`（删 `resolveSelection`/`saveBookmark`/`ReaderSelectionDraft`；Commit 删 `bookmarkText`/`bookmarkContent`——破坏性）
- Modify: `modules/eink/.../selection/ReaderSelectionSheets.kt`（删 `ReaderBookmarkEditDialog`）
- Modify: `modules/eink/.../ReaderScreen.kt`（删书签弹层组合与 pendingSelection 里的书签分支残留）
- Modify: `modules/eink/.../ReaderViewModel.kt`（删 `resolveSelection`/`saveBookmark`）
- Modify: `app/.../bridge/ReaderSelectionEngineImpl.kt`（删两方法实现）
- Modify: `modules/eink/docs/eink-porting.md`（端口行收敛为 v2 终态）
- Test: 受影响测试同步

**Steps:**
- [ ] 删除 + 全仓 grep `resolveSelection\|saveBookmark\|ReaderSelectionDraft\|bookmarkText` 清残留。
- [ ] 门禁：模块单测 + `:app:compileAppDebugKotlin` + `git diff --check`。
- [ ] Commit: `refactor(eink): 契约收敛——退役选择书签链路（页面级书签接管）`

### Task 6: 点按标记——命中 markingId → 浮条/浮窗 + 删除/编辑

**Files:**
- Modify: `modules/eink/.../selection/ReaderTextSelection.kt`（追加纯函数 `findDecorationAt(line: ReaderPageLine, charIndex: Int): ReaderDecorationRun?`——行内 decorations 命中包含 charIndex 的 run）
- Modify: `modules/eink/.../selection/ReaderSelectionOverlay.kt`（浮条支持「标记态」——锚定命中 run 几何而非选区；THOUGHT 预填 note；DELETE 带 markingId 即时可用）
- Modify: `modules/eink/.../ReaderScreen.kt`（tap 分支升级：无选区时点按先查命中 decoration——命中 →VM `findMarking(markingId)` → 按 thought 分浮条/浮窗；未命中 → 原有分区行为）
- Modify: `modules/eink/.../ReaderViewModel.kt`（`suspend fun findMarking(markingId): ReaderMarkingDetail?`）
- Test: `modules/eink/src/test/.../selection/ReaderTextSelectionTest.kt`（`findDecorationAt` 命中/未命中/边界用例，TDD）

**Steps:**
- [ ] RED：`findDecorationAt` 三用例（命中区间内、区间外 null、多 run 取包含者）。
- [ ] 点按流：tap（无选区）→ `hitTest` → `findDecorationAt` → 命中则 `markingId` → `viewModel.findMarking`：null → 静默（标记失效）；thought=false → 浮条三键（锚 run 几何，DELETE 即时可用）；true → 浮窗（`ReaderThoughtDialog` 变体：想法内容只读展示区 + 同样输入可改 + 删除钮置入弹层底部？**裁定：浮窗 = EInkDialog 展示想法内容 + 【写想法】【删除】【复制】三键**（showActions=false + 自定义按钮行），复制/删除/编辑均即时可用）。
- [ ] 写想法（编辑模式）预填 note，保存 = `saveMarking(sel-from-marking, note, thought = true)`——点按场景的 `ReaderSelectionUi` 由命中 run 的行内区间经 `buildSelection` 构造（bodyStart/bodyEnd 取 run 对应章内区间，`chapterPositionOf` 换算）。
- [ ] 门禁 + Commit: `feat(eink): 点按标记浮条/浮窗——按 markingId 删除与写想法`

### Task 7: 实时下划线预览

**Files:**
- Modify: `modules/eink/.../selection/ReaderSelectionOverlay.kt`（选区带绘制替换为实线下划线预览：对选区 runs 逐行画基线下 12% 行盒高的实线（主题 onBackground、1.5f·density），把手叠加；复用与画布一致的偏移公式）
- Modify: `modules/eink/.../ReaderScreen.kt`（无结构变化——浮条锚定改用 runs 的下划线几何）

**Steps:**
- [ ] 灰色 `drawRoundRect` 高亮带删除，替换 `drawLine`（复用画布同款 y = `line.baseY + (line.bottom - line.top) * 0.12f`）。
- [ ] 门禁 + Commit: `feat(eink): 选择预览改实时下划线（所见即所得）`

### Task 8: 跨页续选双向会话

**Files:**
- Modify: `modules/eink/.../selection/ReaderTextSelection.kt`（会话纯逻辑，TDD）：
  - `data class ReaderSelectionSession(chapterIndex, bodyStart, bodyEnd, segments: List<PageSegment>, activeHandle)`；`PageSegment(text, startPos, endPos)`。
  - `fun captureSegment(snapshot, rangeStart, rangeEnd): PageSegment?`（当前页内 [range] ∩ 页范围的文本与章内端点；用 chapterPositionOf）。
  - `fun joinSegments(segments: List<PageSegment>): String`（按 startPos 排序，相邻 gap>0 补 `\n`，同 `buildSelection` 的 gap 规则）。
  - `fun flipDirection(handle, edgeY, pageTop, pageBottom, triggerBandPx): Int?`（+1 下翻 / -1 上翻 / null——端点在触发带内才判）。
- Modify: `modules/eink/.../selection/ReaderSelectionOverlay.kt`（把手拖拽循环内：端点进入页顶/页底触发带且按住超 longPressTimeout → 回调 `onFlipRequest(direction, capture)`——一次按住只触发一次，翻页后端点吸附翻页边）
- Modify: `modules/eink/.../ReaderScreen.kt`：
  - Route 的 selection 状态升级为可容纳会话：`selection: ReaderSelectionUi` 增加 `sessionActive: Boolean` 字段（或伴生 session state）；**页变清态效应挂起**：`LaunchedEffect(pageVersion)` 中 `if (selection?.sessionActive != true) 清态`。
  - 翻页执行：`onFlipRequest` → `viewModel.nextPage()/prevPage()`（返回 Boolean；false = 无页可翻，忽略）→ 翻页后（pageVersion 推进）端点吸附新页翻页边（起始把手翻上页吸附本页底部、结束把手翻下页吸附本页顶部），继续拖拽。
  - 松手提交：segments + 当前页段 capture → `joinSegments` 为 selectedText、bodyStart/bodyEnd 极值 → `saveMarking(thought=false)`。
- Test: `ReaderTextSelectionTest.kt`（captureSegment 前向/后向、joinSegments 多段+gap、flipDirection 判定，TDD）

**Steps:**
- [ ] RED：会话纯函数全用例。
- [ ] GREEN + 手势接线（触发带 = 一行高，长按时值用 `viewConfiguration.longPressTimeoutMillis`）。
- [ ] 门禁 + Commit: `feat(eink): 跨页续选双向会话——页顶/页底按住翻页累计选区`

### Task 9: 书签页面级——状态缓存、端口实现、角标、顶栏钮、下拉手势

**Files:**
- Create: `app/.../bridge/EInkBookmarkState.kt`
- Modify: `app/.../bridge/ReaderSelectionEngineImpl.kt`（togglePageBookmark 实现）
- Modify: `app/.../bridge/ReaderChapterPager.kt`（`currentPageMeta()` + `currentPageSnapshot()` 的 bookmarkBadge 真值）
- Modify: `app/.../bridge/ReaderEngineImpl.kt`（loadBook/reloadBook 处 `EInkBookmarkState.attach(ReadBook.book)`）
- Modify: `modules/eink/.../ReaderPageSnapshotCanvas.kt`（bookmarkBadge=true 时页角画角标——右上一枚实心小三角/书签折角，主题 primary，尺寸 ~16dp，位置对齐 contentTop）
- Modify: `modules/eink/.../ReaderMenus.kt`（顶栏书签切换钮：selected = 当前快照 bookmarkBadge）
- Modify: `modules/eink/.../ReaderScreen.kt`（下拉手势 + 顶栏钮回调 → VM `togglePageBookmark(): Boolean?`，null/失败静默或「操作失败」toast——失败才提示）
- Modify: `modules/eink/.../ReaderViewModel.kt`（`suspend fun togglePageBookmark(): Boolean?`）
- Test: `app/src/test/.../bridge/ReaderSelectionEngineImplTest.kt`（meta 构造纯函数若有）+ 模块侧角标绘制无测试（UI）

**Interfaces:**
- `EInkBookmarkState`（internal object，自有 `CoroutineScope(SupervisorJob() + IO)`）：`fun attach(book: Book?)`（book 变化时重启 `bookmarkRepository.flowByBook(name, author)` 收集，写 `positionsByChapter: Map<Int, List<Int>>`，模式照抄宿主 `ReaderBookmarkState.kt:18-47`）；`fun hasBookmarkInRange(chapterIndex, startPos, endPos): Boolean`。
- `ReaderChapterPager.currentPageMeta(): PageMeta?`：复用 `currentPageSnapshot` 的 locate 逻辑（L159-184 同款守卫），返回 `(chapterIndex, chapterTitle, text, bodyStart, bodyEnd)`——body 范围从页元素 chapterPositions 取 min/max（Text/Spacer 均有 chapterPosition）；badge：`currentPageSnapshot()` 映射后 `bookmarkBadge = EInkBookmarkState.hasBookmarkInRange(chapterIndex, bodyStart, bodyEnd)`。

**Steps:**
- [ ] `EInkBookmarkState`（镜像宿主 ReaderBookmarkState 结构，flowByBook 收集 + 整体替换快照）。
- [ ] `togglePageBookmark` 桥实现（镜像宿主 `ReadBookmarkDelegate.toggleForCurrentPage` L98-138 语义，页来源换 `chapterPager.currentPageMeta()`；`BOOK_TEXT_MARKS = Regex("[袮꧁]")` 同款剔除；mutex 串行；成功后 relayout 推角标）。
- [ ] pager meta/badge + EngineImpl attach。
- [ ] 模块：角标绘制 + 顶栏钮（模块 res 无书签图标则用 `EInkButton(text = "书签", selected = badge, height = 40.dp)` 文本钮——实现时查 `modules/eink/src/main/res/drawable` 决定）+ 下拉手势（`detectVerticalDragGestures` 新 pointerInput，累计下拉 ≥ 80dp 且无选区、controlsVisible=false → toggle，一次手势一次）。
- [ ] 门禁 + Commit: `feat(eink): 页面级书签——顶栏/下拉 toggle、桥自有书签状态与页角标`

### Task 10: 全量验证 + 文档回填 + 真机清单

**Files:**
- Modify: `docs/dev/eink-selection-bookmark-marking-design.md`（实施精化回填 + 真机清单更新）

**Steps:**
- [ ] 回填实施期偏差（如有）与 §9 真机清单：跨页双向续选（多页/跨段/跨标题）、触发带误触发、实时下划线观感、下拉冲突、角标刷新时序、降级（注释 EInkBridge 的 selectionEngine 装配验证长按不启用）。
- [ ] `./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin testAppDebugUnitTest verifyConfigArchitecture --continue --no-configuration-cache`（基线比对：模块只增、宿主 5 失败同集）。
- [ ] `git diff --check`；Commit 文档: `docs(eink): v2 交互重设计实施回填`

## Self-Review 记录

- **覆盖**：设计 §3（Task 1/2/5）、§4/§5（Task 4/6/7/8）、§6（Task 7）、§7（Task 2/3/9）、§8（各任务）、书签（Task 9）、降级（Task 9 装配注释验证归入 Task 10 清单）。
- **编译连续性**：Task 1 只加不删（桥占位实现）；破坏性收敛集中在 Task 5（同任务内修全仓）；Task 2 的 DecorationRun 破坏性变更同任务内修全部构造点。
- **时序裁定**：松手浮条 DELETE 在落库完成前禁用（Task 4）；点按场景 markingId 现成无此时序（Task 6）。
- **风险前置**：跨页会话与 pageVersion 清态的挂起（Task 8 `sessionActive`）；宿主异步重排撕裂会话已在设计 §9 列为真机风险。
