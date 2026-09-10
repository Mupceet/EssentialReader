# eink 目录页书签 Tab + 笔记页（跳转与导出）设计

> 配套实施计划：`docs/dev/eink-toc-bookmark-note-plan.md`。
> 前置功能：选择/划线 v2（`eink-selection-bookmark-marking-design.md`，已合入）。

## 决策记录

2026-09-11 与用户定案：

| 决策点 | 结论 |
|---|---|
| 界面形态 | 目录页底部操作栏双 Tab「目录 \| 书签」；笔记为独立页面 `EInkScreen.Note`，形态对齐目录页 |
| 笔记页组织 | 划线+想法混合单列表，条目内线型图例（实线/虚线段）区分，不分子 Tab、不加筛选 |
| 导出 | 仅划线+想法 → 一份 Markdown；入口在笔记页顶栏，SAF 选保存位置；书签不随导 |
| 笔记页入口 | 目录页顶栏（书签 Tab 时 actions 位），不占阅读页底部操作条 |
| 跳转失败策略 | 对齐宿主完整版管线：校验 → 本地重定位 → 失败弹「仍跳转/取消」确认 |
| 列表翻页 | 墨水屏铁律：`userScrollEnabled=false` + `EInkPageSwipe` + `EInkListPagerState` + 翻页箭头 |

非目标（本版不做）：书签/笔记的列表内编辑与删除（删除继续走阅读页角标 toggle 与标记浮条）、JSON 导出、跨书全部笔记管理页、`book_marks` 表补进 Backup 备份（**已确认是真实缺口，单独修**）、书签 Tab 搜索。

## 1. 背景

### 1.1 元数据模型（两表、职责正交）

**`Bookmark`（bookmarks 表）＝ 页面书签**，阅读页下拉手势/顶栏角标写入（选择 v2）：

| 字段 | 语义 |
|---|---|
| `time: Long` | 主键（创建时间戳），列表与跳转的标识 |
| `bookName + bookAuthor` | 跨源关联键（换源后仍归属此书） |
| `bookUrl` | 创建时源指纹，跳转校验用 |
| `chapterIndex / chapterPos` | 章下标 + 章内字符偏移（书签落在页首字符位置，UTF-16 语义正文空间） |
| `chapterName` | 章节标题（条目主信息） |
| `bookText` | 页面文本摘录（快速书签自动填，eink 无编辑层） |
| `content` | 笔记文本。eink 快速书签恒为空串；完整模式 `BookmarkEditSheet` 可编辑——两模式共库，非空时仍需展示 |

**`BookMarking`（book_marks 表）＝ 划线 + 想法**，`anchorJson`（`TextProcessAnchor`：selectedText + 前后文 + 哈希）定位、`styleJson` 即类型（`underlineMode` 1=实线划线、2=虚线想法）、`note` 为想法内容（划线为空）、`chapterName` 冗余存储（注释即注明「目录 Sheet 笔记页展示用」）、`bookName+bookAuthor` 跨源关联。DAO 已预留 `flowByBook`（按章+创建时间排序）。

### 1.2 可复用的宿主资产

- **跳转校验管线**：`VerifyBookmarkTargetUseCase`（Match / SourceChanged / TitleMismatch）+ `RelocateMarkingTargetUseCase`（本地重定位）+ `ReadBookmarkNavigateDelegate` 的三分支语义（含 `PendingBookmarkTarget`「仍跳转」确认）。
- **导出版式先例**：`BookmarkExporter.formatToMarkdown`（`# 书名`、按章分组、`>` 摘录、笔记文本）。
- **会话跳转原语**：`ReadBook.openChapter(index, durChapterPos)` + `saveReadingAnchorBeforeChapterJump`（跳章前记录回跳锚点）。

### 1.3 eink 模块现状与契约缺口

- 目录页 `TocScreen` 为纯章节单列表（固定页分页 + 滑动手柄 + 底部操作栏），`EInkScreen.Toc(bookUrl, fromReader)` 两路径跳章已成熟（fromReader=true 弹回复用阅读页；false `replaceTop` 进阅读页）。
- 契约缺口：①书签/笔记列表读取端口不存在；②`ReaderEngine` 无「章 + 章内位置」跳转（只有 `loadContent(chapterIndex)` 与 `skipToPage`）；③`TocEngine.saveReadingProgress` 无章内位置参数且语义为「重置页内位置」；④无导出端口。

## 2. 范围

```text
目录页（改造）                        契约（新增/扩展）                宿主 bridge（新增）
├─ 双 Tab [目录|书签]                 ├─ MarksEngine（可选端口）        ├─ MarksEngineImpl
├─ 书签列表+条目+点击跳转    ────►    ├─ ReaderEngine.jumpToPosition    │   ├─ 列表流（DAO flowByBook）
├─ 顶栏 actions 按 Tab 变化           ├─ TocEngine.saveReadingProgress  │   ├─ resolveJump 三分支
│   （书签 Tab → 笔记入口）           │     扩 chapterPos 参数           │   ├─ Markdown 导出
└─ NeedConfirm 确认弹层               └─ EInkScreen.Note 路由           │   └─ 无会话跳转落进度
                                                                        └─ ReaderEngineImpl.jumpToPosition
笔记页（新增）                                                          TocEngineImpl 扩参
├─ 混合列表（划线+想法）
├─ 点击跳转 + NeedConfirm
└─ 顶栏导出 Markdown（SAF）
```

## 3. 契约变更（AAR minor，破坏面见各节）

### 3.1 `MarksEngine`（新文件 `contract/MarksEngine.kt`，可选端口）

注册进 `EInkEngineRegistry.marksEngine: MarksEngine?`（对齐 `selectionEngine` 先例：可空 + `install` 可选参数）。缺失降级：目录页不显示书签 Tab（回到单列表现状）、笔记入口与笔记页路由不可达，无假死路径。

```kotlin
interface MarksEngine {
    fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>>
    fun observeMarkings(bookUrl: String): Flow<List<MarkingUiModel>>
    suspend fun resolveBookmarkJump(bookmarkId: Long): JumpResolution
    suspend fun resolveMarkingJump(markingId: String): JumpResolution
    suspend fun exportMarkingsMarkdown(bookUrl: String, uri: String): Boolean
}
```

全成员语义（实现注释同强度落盘）：

- `observeBookmarks` / `observeMarkings`：按 bookUrl 解析书籍后以「书名+作者」跨源订阅（bookmarks 按 `chapterIndex, chapterPos` 排序——`BookmarkDao.flowByBook` 现仅 `order by chapterIndex`，需补 `chapterPos` 次序；book_marks 按 `chapterIndex, createdAt`，`BookMarkingDao.flowByBook` 现成）。宿主解析失败返回空流。
- `resolveBookmarkJump`：复用 `VerifyBookmarkTargetUseCase`。Match → `Located(chapterIndex, chapterPos)`；不 Match → `NeedConfirm`（携带校验失败的说明与章下标回退目标，不携带源指纹细节）。书签不存在 → `Failed`。
- `resolveMarkingJump`：校验 → 不 Match 时本地重定位（`RelocateMarkingTargetUseCase`）→ `Located`（重定位后的章+位置）；重定位失败 → `NeedConfirm`（回退章首）；标记不存在（换源清理）→ `Failed`。
- `exportMarkingsMarkdown`：读当前书全部 `book_marks`（跨源），生成 Markdown 写入 SAF uri。无笔记返回 false（模块提示「暂无笔记」路径由空态兜底，正常不触发）。

**UiModel（全基元，`@EInkImmutable`）**：

```kotlin
BookmarkUiModel(
    id: Long,               // = Bookmark.time，跳转回传
    chapterIndex: Int,
    chapterName: String,
    bookText: String,       // 页面摘录
    content: String,        // 笔记（完整模式编辑过才非空）
)
MarkingUiModel(
    id: String,             // = BookMarking.id
    chapterIndex: Int,
    chapterName: String,
    selectedText: String,   // 划线原文
    note: String,           // 想法（划线为空串）
    thought: Boolean,       // 宿主从 styleJson 推导：underlineMode==2 → true
    createdAt: Long,
)
```

`thought` 只表达 eink 两档图例语义；宿主完整模式的其他效果（波浪/背景/字体色）由宿主映射为 `false`，eink 不感知效果枚举。

**`JumpResolution`**：

```kotlin
sealed interface JumpResolution {
    data class Located(val chapterIndex: Int, val chapterPos: Int) : JumpResolution
    data class NeedConfirm(val message: String, val fallback: Located?) : JumpResolution
    data class Failed(val message: String) : JumpResolution
}
```

### 3.2 `ReaderEngine` 会话内跳转（追加方法）

```kotlin
/** 跳转并装载指定章节到章内字符位置（书签/笔记跳转用）。
 *  宿主实现 = ReadBook.openChapter(chapterIndex, chapterPos)，
 *  含跳章前回跳锚点记录；无会话返回 false。 */
fun jumpToPosition(chapterIndex: Int, chapterPos: Int): Boolean
```

### 3.3 `TocEngine.saveReadingProgress` 扩参

```kotlin
suspend fun saveReadingProgress(
    bookUrl: String,
    chapterIndex: Int,
    chapterTitle: String,
    chapterPos: Int = 0,     // 新增：章内落点（无会话路径的书签/笔记跳转）
)
```

默认参数兼容存量调用点；宿主 `TocEngineImpl` 同步改签名（override 消默认值）。**对 AAR 旧宿主为破坏性变更 → minor 版本升**。

### 3.4 `EInkScreen.Note`（新路由）

```kotlin
/** 笔记页（划线+想法混合列表）。fromReader 语义同 Toc：
 *  true = 经目录页自阅读页进入，跳转后 pop 回阅读页；
 *  false = 预留（详情等入口），跳转后 replaceTop 进阅读页。 */
data class Note(val bookUrl: String, val fromReader: Boolean = false) : EInkScreen
```

## 4. 目录页双 Tab

- **Tab 位**：底部操作栏 tabs 槽（对齐首页「书架|我的」模式）。需要素材：书签 Tab 成对图标（outline/filled `eink_ic_bookmark*`）+ 目录 Tab filled 变体（现有 `eink_ic_toc` 补填充版）。
- **顶栏 actions 按 Tab 变化**：目录 Tab → 正/倒序（现状不动）；书签 Tab → 「笔记」入口按钮（`EInkOperationBarIcon`，跳 `EInkScreen.Note(bookUrl, fromReader=当前 Toc 的 fromReader)`）。
- **底部 actions**：目录 Tab → 回到当前 / 去底部（现状）；书签 Tab → 回到当前（定位当前章第一条书签，无书签时无动作）/ 去底部（维持一致性）。
- **滑动手柄**：仅目录 Tab 显示。
- **书签条目**（按压瞬时反色 §35，点击跳转）：章节名 `labelMedium` 次级色一行 → `bookText` 摘录 `bodyLarge` 最多 2 行省略 → `content` 非空时主色跟排最多 2 行。当前章条目复用「左侧实心标记 + 章节名加粗」语言（§42 additive inking）。
- **空态**：「暂无书签（阅读页下拉或角标添加）」。
- **书签 Tab 的 pager**：复用 `EInkListPagerState`；书签量级小，初始定位第一页即可（不定位当前章，避免少书签时来回跳）。

## 5. 笔记页

- **结构**：顶栏（书名 + 「导出」图标按钮）→ 混合列表（固定页分页）→ 底部操作栏（返回 / 回到当前（当前章第一条笔记）/ 翻页胶囊），无滑动手柄。
- **条目**：行首线型图例（16dp 实线段 / 虚线段，`onSurface` 灰度，线型本身即类型标识）→ 章节名 `labelMedium` 次级色 → `selectedText` `bodyLarge` 最多 3 行省略 → `note` 非空时跟排（`onSurface` 主色 + 「想法」引导词）最多 3 行。当前章条目同样左侧实心标记语言。
- **空态**：「暂无划线或想法」。
- **导出**：顶栏「导出」→ `CreateDocument` launcher（`text/markdown`，建议名 `书名-笔记.md`）→ `exportMarkingsMarkdown(bookUrl, uri)` → 成功 toast「已导出」、失败 toast「导出失败」。空列表时导出按钮置灰。

## 6. 跳转管线

```text
目录页书签条目点击 / 笔记页条目点击
  └─ marksEngine.resolveBookmarkJump(id) / resolveMarkingJump(id)
       ├─ Failed(msg) ─► toast(msg)，停留当前页
       ├─ NeedConfirm(msg, fallback) ─► EInkDialog「仍跳转 / 取消」
       │     ├─ 仍跳转 ─► fallback 非 null 按其执行，null 仅 toast 不跳
       │     └─ 取消 ─► 关闭弹层
       └─ Located(chapterIndex, chapterPos)
            ├─ 有活跃会话（readerEngine.sessionBookUrl == bookUrl）
            │     └─ readerEngine.jumpToPosition(…) ─► 导航回阅读页
            │          ├─ 目录页 fromReader=true / 笔记页：pop 弹出目录/笔记层
            │          └─ 阅读页经 onContentUpdated 推送刷新（不写阅读时间）
            └─ 无会话（详情等路径进入）
                  └─ tocEngine.saveReadingProgress(bookUrl, index, title, pos)
                       └─ 导航 replaceTop(Reader(bookUrl))，装载时落位
```

- 跳转即移动阅读位置（对齐宿主 `navigateToBookmark` 语义，`jumpToPosition` 内含回跳锚点记录）；**不新增「返回跳转来源」按钮**，回退靠系统返回。
- `NeedConfirm` 弹层复用 `EInkDialog`，文案含章节名等上下文（宿主拼接）。

## 7. 导出 Markdown 版式

```markdown
# {书名}

作者：{作者}

## {章节名}

> {划线原文}

想法：{note}          ← 仅想法条目
```

按 `chapterIndex` 分组、组内按 `createdAt` 升序；宿主新写 `MarkingExporter`（或 `BookmarkExporter` 同目录平级 object），格式化纯函数 + uri 写文件两层，便于 JVM 单测。

## 8. 宿主 bridge 实现（`app/.../eink/bridge/`）

- `MarksEngineImpl`（新）：DAO 流映射 UiModel；resolve 两方法组装既有 UseCase（校验/重定位），把 `ReadBookmarkNavigateDelegate` 的交互分支改为同步返回 `JumpResolution`（UI 确认动作上移到模块弹层，宿主不再持有确认回调）；导出走 `MarkingExporter`。
- `ReaderEngineImpl`（改）：追加 `jumpToPosition` → `ReadBook.openChapter(chapterIndex, chapterPos)`（锚点语义随 openChapter 路径自带），无会话返回 false。
- `TocEngineImpl`（改）：`saveReadingProgress` 扩参，`chapterPos` 写入 Book 进度（对齐现有落库路径）。
- `EInkEngineRegistry` 安装点（App host DI）：`marksEngine` 可选注入。

## 9. 测试与验证

- **宿主单测**（`testAppDebugUnitTest`）：
  - `MarksEngineImplTest`：resolveBookmarkJump（Match / SourceChanged / 不存在）、resolveMarkingJump（Match / 重定位成功 / 重定位失败 NeedConfirm / 不存在）、列表流映射（thought 推导、跨源聚合）。
  - `MarkingExporterTest`：Markdown 版式快照（分组、想法行、空笔记）。
- **模块单测**（`:modules:eink:testDebugUnitTest`，基线只增不减）：
  - `TocViewModelTest` 扩：Tab 状态、书签流映射、跳转意图分派（三分支）、笔记入口导航回调。
  - `NoteViewModelTest` 新：列表流、导出意图（uri 回传成功/失败）、NeedConfirm 弹层状态机。
- **真机清单**（第 10 节合并列出）。

## 10. 风险与真机验证清单

| 风险 | 对策 |
|---|---|
| 变高条目整页翻页（书签/笔记条目 2–3 档高度，`EInkListPagerState` 按第一页实测项数移动） | maxLines 收紧让高度趋同；真机若溢页，再给 pager 加按视口像素翻页的变体（回退方案，不预实现） |
| Tab 图标素材成对要求（outline/filled） | 新增 `eink_ic_bookmark` 成对 + `eink_ic_toc` filled 变体，风格对齐现有素材 |
| pop 两次回阅读页的栈操作 | 实施时确认 `EInkNavController` 是否需要 `popTo`/连续 pop 辅助 |
| 换源书的笔记锚点失效 | NeedConfirm 管线兜底（对齐宿主） |

真机：①双 Tab 切换残影与整页刷新；②当前章内书签跳转（`skipToPage` 等价落点）；③跨章书签/笔记跳转落点精度（页首位置）；④换源书触发确认弹层、「仍跳转」落章首；⑤详情 → 目录 → 书签跳转（无会话路径落位）；⑥导出文件 PC 可读、内容分组正确；⑦空列表空态与置灰导出；⑧笔记页返回回目录页、跳转直达阅读页。

## 11. 实施切片

1. **契约与宿主**：`MarksEngine` 契约 + `ReaderEngine.jumpToPosition` + `TocEngine` 扩参 + 三个 bridge 实现 + 宿主单测（可独立合并，模块侧无消费者时零行为变化）。
2. **目录页书签 Tab**：TocViewModel/UI 双 Tab + 跳转编排 + NeedConfirm 弹层 + 素材 + 模块单测。
3. **笔记页 + 导出**：Note 路由/页/VM + SAF 导出 + 模块单测。

切片 1 合入后 AAR minor 升版（`TocEngine` 签名变更）。
