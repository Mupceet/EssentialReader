# eink 目录页书签 Tab + 笔记页（跳转+导出）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 目录页底部操作栏双 Tab「目录|书签」、新增独立笔记页（划线+想法混合列表）、点击跳转（对齐宿主校验/重定位/确认管线）、笔记导出 Markdown。

**Architecture:** 新可选端口 `MarksEngine`（列表流 + 跳转解析 + 导出）由宿主 bridge 实现并复用既有 `VerifyBookmarkTargetUseCase`/`RelocateMarkingTargetUseCase`/DAO；`ReaderEngine` 补会话内章+位置跳转原语；`TocEngine.saveReadingProgress` 扩 `chapterPos`；模块侧目录页改双 Tab、新增 `EInkScreen.Note` 路由与页面。

**Tech Stack:** Kotlin、Jetpack Compose（模块自持 design system）、Room 经宿主 DAO/Repository、JUnit4 纯 JVM。

**规格：** `docs/dev/eink-toc-bookmark-note-design.md`（契约语义与 UI 形态以其为准）。

## Global Constraints

- JDK 21。模块单测 `.\gradlew.bat :modules:eink:testDebugUnitTest`（纯 JVM，基线只增不减）；宿主 `.\gradlew.bat testAppDebugUnitTest`（先跑一次记录当前失败基线，零新增）；快速编译 `.\gradlew.bat :app:compileAppDebugKotlin` / `:modules:eink:compileDebugKotlin`。
- **提交纪律：工作区可能有用户未提交文件，所有提交用显式路径**：`git add <files>` 后 `git commit -m "<msg>" -- <files>`，绝不裸 `git commit`。
- 位置口径 UTF-16、语义正文空间（与选择 v2 一致）。
- 列表翻页铁律：`LazyColumn(userScrollEnabled = false)` + `EInkPageSwipe` + `EInkListPagerState` + `EInkPageArrows`，禁止连续滚动。
- 操作条 Tab 素材成对（`_e` 描边 / `_s` 填充，规范 §35/§42）；选中态不使用实心色块。
- 可选端口降级：`marksEngine` 缺失时目录页回到单列表现状（tabs 为空）、笔记入口与 Note 路由不可达，无假死路径。
- 所有文本改动 `git diff --check`；提交信息 `feat(eink)/fix(eink)/docs(eink): ...` 中文。
- 契约文件全成员 KDoc 注释（宿主实现者视角），对齐 `contract/TocEngine.kt` 强度。

## 文件总览

```text
modules/eink/src/main/java/io/legado/app/eink/
  contract/MarksEngine.kt                   [新] 端口 + UiModel + JumpResolution
  contract/EInkEngineRegistry.kt            [改] marksEngine 可选挂载
  contract/ReaderEngine.kt                  [改] jumpToPosition 方法
  contract/TocEngine.kt                     [改] saveReadingProgress 扩 chapterPos
  app/EInkScreen.kt                         [改] Note 路由
  app/EInkApp.kt                            [改] Toc 分支接线 + Note 分支
  feature/toc/TocViewModel.kt               [改] Tab/书签流/跳转分派/确认弹层
  feature/toc/TocScreen.kt                  [改] 双 Tab UI + 书签列表 + 确认弹层
  feature/note/NoteViewModel.kt             [新]
  feature/note/NoteScreen.kt                [新] Route + Screen + 条目 + SAF 导出
  src/main/res/drawable/eink_ic_toc_e.xml          [新] 目录 Tab 描边变体
  src/main/res/drawable/eink_ic_bookmark_e.xml     [新] 书签 Tab 描边
  src/main/res/drawable/eink_ic_bookmark_s.xml     [新] 书签 Tab 填充
  src/main/res/drawable/eink_ic_note_export.xml    [新] 导出按钮
  src/main/res/drawable/eink_ic_note_entry.xml     [新] 笔记入口（目录页顶栏）
app/src/main/java/io/legado/app/
  eink/bridge/MarksEngineImpl.kt            [新] 端口实现（含纯函数）
  eink/bridge/ReaderEngineImpl.kt           [改] jumpToPosition
  eink/bridge/TocEngineImpl.kt              [改] saveReadingProgress 扩参
  eink/bridge/EInkBridge.kt                 [改] install 挂 marksEngine
  help/bookmark/MarkingExporter.kt          [新] Markdown 版式 + uri 写出
  data/dao/BookmarkDao.kt                   [改] getById + flowByBook 排序
modules/eink/docs/eink-porting.md           [改] 端口表补 MarksEngine 行
测试：
  modules/eink .../contract/EInkEngineRegistryTest.kt        [改] marksEngine 挂载
  modules/eink .../feature/toc/TocUiStateTest.kt             [改] Tab/书签状态
  modules/eink .../feature/note/NoteUiStateTest.kt           [新]
  app/src/test .../help/bookmark/MarkingExporterTest.kt      [新]
  app/src/test .../eink/bridge/MarksEngineImplTest.kt        [新] 纯函数锚定
```

---

## 切片 1：契约与宿主桥（模块侧零行为变化，可独立合并）

### Task 1: `MarksEngine` 契约与注册表挂载

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/contract/MarksEngine.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt`

**Interfaces:**
- Produces: `MarksEngine` / `BookmarkUiModel` / `MarkingUiModel` / `JumpResolution`（后续所有任务的消费契约）。

- [ ] **Step 1: 写契约文件**（全成员 KDoc，宿主实现者视角）

```kotlin
package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable
import kotlinx.coroutines.flow.Flow

/**
 * 书签/笔记端口：目录页书签 Tab 与笔记页的数据来源，含跳转目标解析
 * 与笔记导出。
 *
 * 列表按「书名+作者」跨源聚合（换源后仍可见）；跳转解析封装宿主的
 * 校验→本地重定位→确认三分支，模块不复制这些规则。
 *
 * 可选端口（同 [ReaderSelectionEngine] 先例）：注册表缺失本端口时，
 * 目录页不显示书签 Tab、笔记入口与 Note 路由不可达，不做假死路径。
 */
interface MarksEngine {

    /**
     * 订阅书籍的全部页面书签（bookmarks 表，跨源；按 chapterIndex、
     * chapterPos 升序）。宿主按 bookUrl 解析书籍失败返回空流。
     */
    fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>>

    /**
     * 订阅书籍的全部划线/想法（book_marks 表，跨源；按 chapterIndex、
     * createdAt 升序）。宿主按 bookUrl 解析书籍失败返回空流。
     */
    fun observeMarkings(bookUrl: String): Flow<List<MarkingUiModel>>

    /**
     * 解析书签跳转目标：复用宿主校验（源指纹 + 章节标题比对）。
     * Match → [JumpResolution.Located]；不 Match → [JumpResolution.NeedConfirm]
     * （fallback = 存储坐标，对齐宿主「仍跳转」语义）；书签/书籍不存在 →
     * [JumpResolution.Failed]。不执行跳转、不写进度。
     */
    suspend fun resolveBookmarkJump(bookmarkId: Long): JumpResolution

    /**
     * 解析划线/想法跳转目标：校验不 Match 时先本地重定位（选中文本 +
     * 前后文评分，仅本地已缓存章节，不发起网络）；重定位成功 → 重定位
     * 坐标；失败 → [JumpResolution.NeedConfirm]（fallback = 存储坐标）。
     * 标记不存在（换源清理等）→ [JumpResolution.Failed]。
     */
    suspend fun resolveMarkingJump(markingId: String): JumpResolution

    /**
     * 导出当前书全部划线/想法为 Markdown 写入 SAF uri。
     * @return false = 书籍不存在或无笔记或写失败（模块提示「导出失败」）。
     */
    suspend fun exportMarkingsMarkdown(bookUrl: String, uri: String): Boolean
}

/** 目录页书签 Tab 条目快照（全基元）。 */
@EInkImmutable
data class BookmarkUiModel(
    /** 书签标识（= 宿主 Bookmark.time 主键），跳转解析回传。 */
    val id: Long,
    val chapterIndex: Int,
    val chapterName: String,
    /** 页面文本摘录（快速书签自动记录）。 */
    val bookText: String,
    /** 笔记文本（完整模式编辑过才非空；eink 快速书签恒为空串）。 */
    val content: String,
)

/** 笔记页条目快照（全基元）。 */
@EInkImmutable
data class MarkingUiModel(
    /** 标记标识（= 宿主 BookMarking.id），跳转解析回传。 */
    val id: String,
    val chapterIndex: Int,
    val chapterName: String,
    /** 划线选中原文。 */
    val selectedText: String,
    /** 想法内容（划线为空串）。 */
    val note: String,
    /** true = 想法（宿主从 styleJson 推导 underlineMode == 2）。 */
    val thought: Boolean,
    val createdAt: Long,
)

/** 跳转解析结果三分支。 */
sealed interface JumpResolution {

    /** 目标可靠，直接按坐标跳转。 */
    data class Located(val chapterIndex: Int, val chapterPos: Int) : JumpResolution

    /** 目标存疑：模块弹「仍跳转/取消」确认；[fallback] 为存储坐标，null 时确认后仅提示不跳。 */
    data class NeedConfirm(val message: String, val fallback: Located?) : JumpResolution

    /** 无法解析（记录不存在/数据损坏）：模块 toast [message]，不跳转。 */
    data class Failed(val message: String) : JumpResolution
}

/** 「仍跳转」确认弹层瞬态（目录页/笔记页共用；对齐宿主 PendingBookmarkTarget 语义）。 */
@EInkImmutable
data class PendingJumpConfirm(
    /** 展示给用户的确认文案（宿主拼装，含章节名等上下文）。 */
    val message: String,
    /** 确认后的跳转坐标（null = 仅提示不跳）。 */
    val fallback: JumpResolution.Located?,
)
```

- [ ] **Step 2: 注册表挂可选端口**

`EInkEngineRegistry.kt`：在 `selectionEngine` 属性后追加（KDoc 对齐 selectionEngine 段落，注明缺失降级语义），`install(...)` 追加参数并赋值：

```kotlin
    /**
     * 书签/笔记端口——**可选**端口：未注册 = 宿主无书签/笔记列表能力，
     * 目录页书签 Tab 与笔记页按 [MarksEngine] 接口 KDoc 的降级语义处理，
     * 不参与 install 必填校验。
     */
    val marksEngine: MarksEngine?
        get() = _marksEngine
```

```kotlin
    private var _marksEngine: MarksEngine? = null

    // install 签名追加（置于 selectionEngine 之后）：
    //     marksEngine: MarksEngine? = null,
    // 函数体追加：_marksEngine = marksEngine
```

- [ ] **Step 3: 注册表测试**

`EInkEngineRegistryTest.kt` 复用既有 stub 安装 helper 追加两例：

```kotlin
    @Test
    fun `marksEngine 未注册时为 null 且不参与必填校验`() {
        installDefaults() // 既有 helper：不传 marksEngine
        assertNull(EInkEngineRegistry.marksEngine)
    }

    @Test
    fun `install 传入 marksEngine 后可取回`() {
        installDefaults(marksEngine = fakeMarksEngine)
        assertSame(fakeMarksEngine, EInkEngineRegistry.marksEngine)
    }
```

`fakeMarksEngine` 为测试内最小实现（各方法 `TODO()`/空返回即可，KDoc 已定义语义）。

- [ ] **Step 4: 验证**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest`
Expected: PASS（基线只增不减）

- [ ] **Step 5: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/contract/MarksEngine.kt modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt
git commit -m "feat(eink): 新增 MarksEngine 书签/笔记可选端口与注册表挂载" -- modules/eink/src/main/java/io/legado/app/eink/contract/MarksEngine.kt modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt
```

### Task 2: `ReaderEngine.jumpToPosition` 会话内跳转

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt`（翻页段之后追加）
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt`（`skipToPage` 实现旁追加）

**Interfaces:**
- Consumes: 宿主 `ReadBook.saveReadingAnchorBeforeChapterJump(targetChapterIndex, targetChapterPos)`（ReadBook.kt:717）、`ReadBook.openChapter(index, durChapterPos, upContent, success)`（ReadBook.kt:1139）。
- Produces: `fun jumpToPosition(chapterIndex: Int, chapterPos: Int): Boolean`。

- [ ] **Step 1: 契约方法**

```kotlin
    /**
     * 会话内跳转到指定章节的章内字符位置（书签/笔记跳转用；区别于
     * [loadContent]——本方法保留页内落点，且先记录回跳锚点）。
     * 跳转即移动阅读位置（进度随后续保存时机落库）。
     * @return false = 无会话（调用方走无会话落进度路径）。
     */
    fun jumpToPosition(chapterIndex: Int, chapterPos: Int): Boolean
```

- [ ] **Step 2: 宿主实现**

```kotlin
    override fun jumpToPosition(chapterIndex: Int, chapterPos: Int): Boolean {
        if (ReadBook.book == null) return false
        ReadBook.saveReadingAnchorBeforeChapterJump(chapterIndex, chapterPos)
        ReadBook.openChapter(chapterIndex, chapterPos)
        return true
    }
```

- [ ] **Step 3: 验证**：`.\gradlew.bat :app:compileAppDebugKotlin :modules:eink:compileDebugKotlin` → BUILD SUCCESSFUL

- [ ] **Step 4: Commit**：`feat(eink): ReaderEngine 补会话内章+位置跳转原语 jumpToPosition`

### Task 3: `TocEngine.saveReadingProgress` 扩参与 BookmarkDao 补方法

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/TocEngine.kt:76`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/TocEngineImpl.kt:156`
- Modify: `app/src/main/java/io/legado/app/data/dao/BookmarkDao.kt`

**Interfaces:**
- Produces: `suspend fun saveReadingProgress(bookUrl, chapterIndex, chapterTitle, chapterPos: Int = 0)`；`suspend fun getById(time: Long): Bookmark?`；`flowByBook` 排序含 `chapterPos`。

- [ ] **Step 1: 契约扩参**（KDoc 补一句 `chapterPos` 语义：无会话路径的书签/笔记跳转落点，0 = 重置到章首）

- [ ] **Step 2: `TocEngineImpl` 同步改签名**，`book.durChapterPos = 0` → `book.durChapterPos = chapterPos`（其余行不动）

- [ ] **Step 3: `BookmarkDao` 追加**

```kotlin
    @Query("select * from bookmarks where time = :time")
    suspend fun getById(time: Long): Bookmark?
```

`flowByBook` / `getByBook` 的 `order by chapterIndex` → `order by chapterIndex, chapterPos`。

- [ ] **Step 4: 验证**：`.\gradlew.bat :app:compileAppDebugKotlin testAppDebugUnitTest --continue`（宿主既有 `TocEngineImpl`/`BookmarkDao` 相关测试零回退；Room schema 查询变更无需迁移）

- [ ] **Step 5: Commit**：`feat(eink): TocEngine 进度写回扩章内位置，BookmarkDao 补按主键查询与排序`

### Task 4: `MarkingExporter`（宿主导出器）

**Files:**
- Create: `app/src/main/java/io/legado/app/help/bookmark/MarkingExporter.kt`
- Test: `app/src/test/java/io/legado/app/help/bookmark/MarkingExporterTest.kt`

**Interfaces:**
- Consumes: `BookMarking`、`TextProcessAnchor`（`GSON.fromJsonObject` 解析 `anchorJson`）。
- Produces: `fun formatToMarkdown(bookName: String, author: String?, markings: List<BookMarking>): String`。

- [ ] **Step 1: 失败测试**（版式快照，纯 JVM）

```kotlin
package io.legado.app.help.bookmark

import io.legado.app.data.entities.BookMarking
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkingExporterTest {

    private fun marking(
        chapterIndex: Int,
        chapterName: String,
        selectedText: String,
        note: String = "",
        createdAt: Long = 0,
    ) = BookMarking(
        id = "id-$chapterIndex-$createdAt",
        bookUrl = "https://a",
        bookName = "书",
        bookAuthor = "作者",
        chapterIndex = chapterIndex,
        anchorJson = GSON.toJson(
            io.legado.app.domain.model.TextProcessAnchor(
                chapterIndex = chapterIndex,
                selectedText = selectedText,
                normalizedTextHash = "h",
            )
        ),
        styleJson = null,
        note = note,
        chapterName = chapterName,
        createdAt = createdAt,
    )

    @Test
    fun `按章分组，划线摘录与想法行`() {
        val md = MarkingExporter.formatToMarkdown(
            "书", "作者",
            listOf(
                marking(1, "第二章", "第二句", createdAt = 2),
                marking(1, "第二章", "第一句", note = "有感", createdAt = 1),
                marking(0, "第一章", "开头"),
            )
        )
        assertEquals(
            """
            # 书

            作者：作者

            ## 第一章

            > 开头

            ## 第二章

            > 第一句

            想法：有感

            > 第二句
            """.trimIndent() + "\n",
            md
        )
    }

    @Test
    fun `空章节名回退序号章题，anchorJson 损坏条目跳过`() {
        val bad = marking(3, "", "x").copy(anchorJson = "{bad")
        val md = MarkingExporter.formatToMarkdown("书", "", listOf(marking(2, "", "文本"), bad))
        assertEquals("# 书\n\n## 第 3 章\n\n> 文本\n", md)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**：`.\gradlew.bat testAppDebugUnitTest --tests "*MarkingExporterTest*"` → 编译失败（类不存在）

- [ ] **Step 3: 实现**

```kotlin
package io.legado.app.help.bookmark

import android.net.Uri
import io.legado.app.data.entities.BookMarking
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.appCtx
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 划线/想法笔记 Markdown 导出（版式对齐 [BookmarkExporter]：# 书名 / 按章分组 / > 摘录）。 */
object MarkingExporter {

    fun formatToMarkdown(bookName: String, author: String?, markings: List<BookMarking>): String {
        val sb = StringBuilder()
        sb.append("# ").append(bookName).append('\n')
        author?.takeIf { it.isNotBlank() }?.let { sb.append("\n作者：").append(it).append('\n') }
        markings
            .sortedWith(compareBy({ it.chapterIndex ?: Int.MAX_VALUE }, { it.createdAt }))
            .mapNotNull { m ->
                val text = GSON.fromJsonObject<TextProcessAnchor>(m.anchorJson).getOrNull()
                    ?.selectedText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                m to text
            }
            .groupBy { (m, _) ->
                m.chapterName.ifBlank { "第 ${(m.chapterIndex ?: 0) + 1} 章" }
            }
            .forEach { (chapterTitle, items) ->
                sb.append("\n## ").append(chapterTitle).append('\n')
                items.forEach { (m, text) ->
                    sb.append('\n')
                    text.split('\n').forEach { line -> sb.append("> ").append(line).append('\n') }
                    if (m.note.isNotBlank()) sb.append("\n想法：").append(m.note).append('\n')
                }
            }
        return sb.toString()
    }

    suspend fun exportToUri(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**：`feat(eink): 新增划线/想法 Markdown 导出器 MarkingExporter`

### Task 5: `MarksEngineImpl` 宿主实现与挂载

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/MarksEngineImpl.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt:48`（install 追加 `marksEngine = MarksEngineImpl`）
- Test: `app/src/test/java/io/legado/app/eink/bridge/MarksEngineImplTest.kt`

**Interfaces:**
- Consumes: Task 1 契约；`BookmarkDao.getById`（Task 3）；`BookMarkingDao.flowByBook/getById/getByBook`；`BookRepository.getBook(name, author)/getBook(bookUrl)/getChapterTitle/getChapters`；`VerifyBookmarkTargetUseCase`；`RelocateMarkingTargetUseCase.locate(anchor, candidates)`；`BookHelp.getContent(book, chapter)`；`ContentProcessor.get(book).getContent(book, chapter, rawContent, includeTitle = false)`；`MarkingExporter`。
- Produces: 端口实现（模块侧按契约消费）。

- [ ] **Step 1: 纯函数失败测试**

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.model.TextProcessStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MarksEngineImpl.kt 顶层纯函数行为锚定（thought 推导 / 确认文案）。 */
class MarksEngineImplTest {

    @Test
    fun `underlineMode 2 为想法，实线与空样式为划线`() {
        assertTrue(markingThought("""{"underlineMode":2}"""))
        assertFalse(markingThought("""{"underlineMode":1}"""))
        assertFalse(markingThought(null))
        assertFalse(markingThought("{bad"))
    }

    @Test
    fun `确认文案含章节名`() {
        val msg = confirmJumpMessage("第二章 坠落")
        assertTrue(msg.contains("第二章 坠落"))
    }
}
```

`markingThought` 内部用 `GSON.fromJsonObject<TextProcessStyle>(...)`（该解析在纯 JVM 可用，对齐 `ReaderSelectionEngineImplTest` 环境）。

- [ ] **Step 2: 跑测试确认失败**（函数未定义）

- [ ] **Step 3: 实现**（顶层纯函数 + KoinComponent 实现类）

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.appCtx
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookRepository
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.domain.usecase.BookmarkTargetVerdict
import io.legado.app.domain.usecase.RelocateMarkingTargetUseCase
import io.legado.app.domain.usecase.VerifyBookmarkTargetUseCase
import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.contract.MarksEngine
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.bookmark.MarkingExporter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** styleJson → eink 两档图例：虚线（underlineMode 2）= 想法；其余（实线/背景/字体色/空）= 划线。 */
internal fun markingThought(styleJson: String?): Boolean =
    GSON.fromJsonObject<TextProcessStyle>(styleJson).getOrNull()?.underlineMode == 2

/** 「仍跳转」确认文案（模块弹层直接展示）。 */
internal fun confirmJumpMessage(chapterName: String): String =
    "书签创建后目录可能已变化（$chapterName），仍要跳转吗？"

/** eink 书签/笔记端口实现：列表流 + 跳目标解析 + Markdown 导出。 */
object MarksEngineImpl : MarksEngine, KoinComponent {

    private val bookRepository: BookRepository by inject()
    private val verifyUseCase = VerifyBookmarkTargetUseCase()
    private val relocateUseCase = RelocateMarkingTargetUseCase()

    override fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>> =
        bookFlow(bookUrl) { book ->
            appDb.bookmarkDao.flowByBook(book.name, book.author)
                .map { list -> list.map { it.toUiModel() } }
        }

    override fun observeMarkings(bookUrl: String): Flow<List<MarkingUiModel>> =
        bookFlow(bookUrl) { book ->
            appDb.bookMarkingDao.flowByBook(book.name, book.author)
                .map { list -> list.map { it.toUiModel() } }
        }

    /** 解析书 → 无书发空列表，有书接 DAO 流；书籍记录变化（换源替换）时自动重解析。 */
    private fun <T> bookFlow(
        bookUrl: String,
        source: suspend (Book) -> Flow<List<T>>,
    ): Flow<List<T>> = flow {
        val book = bookRepository.getBook(bookUrl) ?: run {
            emit(emptyList<T>()); return@flow
        }
        emitAll(source(book))
    }

    private fun Bookmark.toUiModel() = BookmarkUiModel(
        id = time, chapterIndex = chapterIndex, chapterName = chapterName,
        bookText = bookText, content = content,
    )

    private fun BookMarking.toUiModel() = MarkingUiModel(
        id = id,
        chapterIndex = chapterIndex ?: 0,
        chapterName = chapterName,
        selectedText = GSON.fromJsonObject<TextProcessAnchor>(anchorJson).getOrNull()
            ?.selectedText ?: "",
        note = note,
        thought = markingThought(styleJson),
        createdAt = createdAt,
    )

    override suspend fun resolveBookmarkJump(bookmarkId: Long): JumpResolution {
        val bookmark = appDb.bookmarkDao.getById(bookmarkId)
            ?: return JumpResolution.Failed("书签不存在")
        val book = bookRepository.getBook(bookmark.bookName, bookmark.bookAuthor)
            ?: return JumpResolution.Failed("书籍不存在")
        val targetTitle = bookRepository.getChapterTitle(book.name, book.author, bookmark.chapterIndex)
        return when (verifyUseCase.verify(book.bookUrl, targetTitle, bookmark.bookUrl, bookmark.chapterName)) {
            BookmarkTargetVerdict.Match ->
                JumpResolution.Located(bookmark.chapterIndex, bookmark.chapterPos)
            else -> JumpResolution.NeedConfirm(
                message = confirmJumpMessage(bookmark.chapterName),
                fallback = JumpResolution.Located(bookmark.chapterIndex, bookmark.chapterPos),
            )
        }
    }

    override suspend fun resolveMarkingJump(markingId: String): JumpResolution {
        val marking = appDb.bookMarkingDao.getById(markingId)
            ?: return JumpResolution.Failed("标记不存在")
        val anchor = GSON.fromJsonObject<TextProcessAnchor>(marking.anchorJson).getOrNull()
            ?: return JumpResolution.Failed("标记数据异常")
        val chapterIndex = marking.chapterIndex ?: anchor.chapterIndex
        val stored = JumpResolution.Located(chapterIndex, anchor.chapterPosition ?: 0)
        val book = bookRepository.getBook(marking.bookName, marking.bookAuthor)
            ?: return JumpResolution.Failed("书籍不存在")
        val targetTitle = bookRepository.getChapterTitle(book.name, book.author, chapterIndex)
        return when (verifyUseCase.verify(book.bookUrl, targetTitle, marking.bookUrl, marking.chapterName)) {
            BookmarkTargetVerdict.Match -> stored
            else -> relocateMarking(book, marking.chapterName, anchor)
                ?.let { JumpResolution.Located(it.chapterIndex, it.chapterPosition) }
                ?: JumpResolution.NeedConfirm(confirmJumpMessage(marking.chapterName), stored)
        }
    }

    /** 本地重定位（对齐 ReadBookmarkNavigateDelegate.relocateMarking：仅本地已缓存章节，不发起网络）。 */
    private suspend fun relocateMarking(
        book: Book,
        chapterName: String,
        anchor: TextProcessAnchor,
    ): RelocateMarkingTargetUseCase.Target? {
        val processor = ContentProcessor.get(book)
        val candidates = bookRepository.getChapters(book.bookUrl)
            .asSequence()
            .filter {
                it.index == anchor.chapterIndex ||
                        (chapterName.isNotBlank() && it.title == chapterName)
            }
            .distinctBy { it.index }
            .mapNotNull { chapter ->
                val rawContent = BookHelp.getContent(book, chapter) ?: return@mapNotNull null
                RelocateMarkingTargetUseCase.Candidate(
                    chapterIndex = chapter.index,
                    content = processor.getContent(book, chapter, rawContent, includeTitle = false)
                        .toString(),
                )
            }
            .toList()
        return relocateUseCase.locate(anchor, candidates)
    }

    override suspend fun exportMarkingsMarkdown(bookUrl: String, uri: String): Boolean {
        val book = bookRepository.getBook(bookUrl) ?: return false
        val markings = appDb.bookMarkingDao.getByBook(book.name, book.author, null)
        if (markings.isEmpty()) return false
        val content = MarkingExporter.formatToMarkdown(book.name, book.author, markings)
        return MarkingExporter.exportToUri(android.net.Uri.parse(uri), content)
    }
}
```

注：`appDb` 为宿主顶层函数（`io.legado.app.data.appDb`）；若 Koin 已有 `AppDatabase` 绑定可改 `inject`，两者等价，跟随 `ReaderSelectionEngineImpl` 邻近风格。`bookFlow` 的 `transform` import 未用到则删除（编译器警告清零）。

- [ ] **Step 4: `EInkBridge.install` 追加** `marksEngine = MarksEngineImpl,`（selectionEngine 行后）

- [ ] **Step 5: 验证**：`.\gradlew.bat :app:compileAppDebugKotlin testAppDebugUnitTest --continue`（新增测试 PASS、宿主基线零新增）

- [ ] **Step 6: Commit**：`feat(eink): 宿主实现 MarksEngine 端口（列表流/跳转解析/导出）并挂载安装`

---

## 切片 2：目录页双 Tab

### Task 6: Tab 与功能图标素材

**Files:**
- Create: `modules/eink/src/main/res/drawable/eink_ic_toc_e.xml`、`eink_ic_bookmark_e.xml`、`eink_ic_bookmark_s.xml`、`eink_ic_note_export.xml`、`eink_ic_note_entry.xml`

- [ ] **Step 1: 新增素材**（格式对齐 `eink_ic_toc.xml`：24dp viewport、`#FF000000` fill；`_e` 描边形态 / `_s` 填充形态，素材必须成对）

```xml
<!-- eink_ic_toc_e.xml：目录 Tab 描边变体（现有 eink_ic_toc 作 _s 填充态复用） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24.0" android:viewportHeight="24.0">
    <path android:fillColor="#FF000000"
        android:pathData="M3,6h18v2H3zM3,11h18v2H3zM3,16h18v2H3z" />
</vector>
```

```xml
<!-- eink_ic_bookmark_e.xml：书签描边 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24.0" android:viewportHeight="24.0">
    <path android:fillColor="#FF000000"
        android:pathData="M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z" />
</vector>
```

```xml
<!-- eink_ic_bookmark_s.xml：书签填充 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24.0" android:viewportHeight="24.0">
    <path android:fillColor="#FF000000"
        android:pathData="M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z" />
</vector>
```

```xml
<!-- eink_ic_note_export.xml：导出（笔记页顶栏） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24.0" android:viewportHeight="24.0">
    <path android:fillColor="#FF000000"
        android:pathData="M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z" />
</vector>
```

```xml
<!-- eink_ic_note_entry.xml：笔记入口（目录页顶栏，书签 Tab 时） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24.0" android:viewportHeight="24.0">
    <path android:fillColor="#FF000000"
        android:pathData="M14,17H3v-2h11v2zM21,13H3v-2h18v2zM21,9H3V7h18v2z" />
</vector>
```

- [ ] **Step 2: 验证**：`.\gradlew.bat :modules:eink:compileDebugKotlin`（资源引用在后续任务，编译先过）

- [ ] **Step 3: Commit**：`feat(eink): 目录/书签 Tab 成对素材与笔记入口、导出图标`

### Task 7: TocViewModel 双 Tab 状态与跳转分派

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/toc/TocViewModel.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/toc/TocUiStateTest.kt`

**Interfaces:**
- Consumes: Task 1 契约（`EInkEngineRegistry.marksEngine`）。
- Produces: `TocTab` 枚举、`TocUiState` 新字段（`selectedTab/bookmarks/marksAvailable/pendingJump`）、VM 方法 `selectTab/onBookmarkClick/confirmPendingJump/dismissPendingJump`、`jumpTarget: SharedFlow<JumpResolution.Located>`、`messages: SharedFlow<String>`。

- [ ] **Step 1: UiState 失败测试**（`TocUiStateTest.kt` 追加）

```kotlin
    @Test
    fun `书签状态默认隐藏不可用且无确认`() {
        val s = TocUiState()
        assertEquals(TocTab.Chapters, s.selectedTab)
        assertTrue(s.bookmarks.isEmpty())
        assertFalse(s.marksAvailable)
        assertNull(s.pendingJump)
    }

    @Test
    fun `确认弹层状态可置入`() {
        val confirm = PendingJumpConfirm("msg", JumpResolution.Located(1, 2))
        val s = TocUiState(pendingJump = confirm)
        assertEquals(confirm, s.pendingJump)
    }
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现 UiState 与 VM**

`TocViewModel.kt` 追加：

```kotlin
/** 目录页底部操作栏 Tab（marksEngine 缺失时无 Tab，单列表现状）。 */
enum class TocTab { Chapters, Bookmarks }
```

`TocUiState` 追加字段（KDoc 一行一个）：

```kotlin
    /** 底部操作栏当前 Tab。 */
    val selectedTab: TocTab = TocTab.Chapters,
    /** 书签 Tab 列表（marksEngine 可用时由 observeBookmarks 维护）。 */
    val bookmarks: List<BookmarkUiModel> = emptyList(),
    /** marksEngine 是否注册（false = 不渲染书签 Tab）。 */
    val marksAvailable: Boolean = false,
    /** 跳转确认弹层（null = 无）。 */
    val pendingJump: PendingJumpConfirm? = null,
```

VM 追加（`loadBook` 成功分支里启流）：

```kotlin
    private val marksEngine get() = EInkEngineRegistry.marksEngine

    private val _jumpTarget = MutableSharedFlow<JumpResolution.Located>(extraBufferCapacity = 16)
    /** 已解析成功的跳转目标（Route 层执行引擎跳转 + 导航）。 */
    val jumpTarget: SharedFlow<JumpResolution.Located> = _jumpTarget.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** 用户可见提示（Failed 文案等，Route 层 toast）。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // loadBook 的书解析成功后：
    //   _uiState.update { it.copy(marksAvailable = marksEngine != null) }
    //   marksEngine?.let { engine ->
    //       viewModelScope.launch {
    //           engine.observeBookmarks(book.bookUrl).collect { list ->
    //               _uiState.update { it.copy(bookmarks = list) }
    //           }
    //       }
    //   }

    fun selectTab(tab: TocTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun onBookmarkClick(id: Long) {
        val engine = marksEngine ?: return
        viewModelScope.launch {
            when (val r = engine.resolveBookmarkJump(id)) {
                is JumpResolution.Located -> _jumpTarget.tryEmit(r)
                is JumpResolution.NeedConfirm -> _uiState.update {
                    it.copy(pendingJump = PendingJumpConfirm(r.message, r.fallback))
                }
                is JumpResolution.Failed -> _messages.tryEmit(r.message)
            }
        }
    }

    fun confirmPendingJump() {
        val pending = _uiState.value.pendingJump ?: return
        _uiState.update { it.copy(pendingJump = null) }
        pending.fallback?.let { _jumpTarget.tryEmit(it) }
    }

    fun dismissPendingJump() {
        _uiState.update { it.copy(pendingJump = null) }
    }
```

- [ ] **Step 4: 跑模块测试确认通过**：`.\gradlew.bat :modules:eink:testDebugUnitTest`

- [ ] **Step 5: Commit**：`feat(eink): 目录页 ViewModel 双 Tab 状态与书签跳转分派`

### Task 8: TocScreen 双 Tab UI 与导航接线

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/toc/TocScreen.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/app/EInkScreen.kt`、`EInkApp.kt`

**Interfaces:**
- Consumes: Task 7 的 VM 状态面；Task 6 素材；`EInkOperationBar(tabs, selectedTabIndex, onTabSelect, navigationIcon, actions, pageArrows)`；`EInkDialog(onDismiss, title, confirmText, cancelText, onConfirm, content)`。
- Produces: `TocRoute` 新参数 `onOpenNote: () -> Unit`、`onJumpToLocation: (JumpResolution.Located) -> Unit`；`EInkScreen.Note` 路由。

- [ ] **Step 1: `EInkScreen.Note` 路由**

```kotlin
    /**
     * 笔记页（划线+想法混合列表）。
     *
     * @param fromReader 是否自阅读页链路进入（经目录页）：跳转后 pop 回阅读页；
     *   false = 预留（详情等入口），跳转后 replaceTop 进阅读页
     */
    data class Note(val bookUrl: String, val fromReader: Boolean = false) : EInkScreen
```

- [ ] **Step 2: TocScreen 双 Tab 结构改造**

Route 层（`TocRoute`）新增参数与收集：

```kotlin
@Composable
fun TocRoute(
    bookUrl: String,
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit = {},
    onOpenNote: () -> Unit = {},
    onJumpToLocation: (JumpResolution.Located) -> Unit = {},
    viewModel: TocViewModel = viewModel(),
) {
    // ... 现状 ...
    // 跳转目标收集（LaunchedEffect(viewModel, onJumpToLocation)）：
    LaunchedEffect(viewModel, onJumpToLocation) {
        viewModel.jumpTarget.collect { onJumpToLocation(it) }
    }
    // 消息收集：LocalEInkToast（或对齐模块现有 toast 通道；无则经 UserMessage 体系）
```

`TocScreen` 无状态签名追加 `onTabSelect: (TocTab) -> Unit`、`onBookmarkClick: (Long) -> Unit`、`onOpenNote: () -> Unit`、`onConfirmJump: () -> Unit`、`onDismissJump: () -> Unit`，结构改为：

```kotlin
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏 actions 按 Tab 变化：目录 Tab → 正/倒序（现状）；书签 Tab → 笔记入口
        EInkTopBar(
            title = state.book?.name ?: if (state.selectedTab == TocTab.Bookmarks) "书签" else "目录",
            actionsFillMax = true,
            actions = {
                if (state.selectedTab == TocTab.Bookmarks) {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_note_entry),
                        contentDescription = "笔记",
                        onClick = onOpenNote,
                    )
                } else {
                    // 现状正/倒序按钮原样保留
                }
            }
        )
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
                state.error != null -> CenterMessage(state.error)
                state.selectedTab == TocTab.Bookmarks -> BookmarkListPane(state, ...) // 见下
                state.isEmpty -> CenterMessage("无章节")
                else -> /* 现状章节列表 + 滑动手柄 + positioned 遮盖 */
            }
        }
        EInkOperationBar(
            tabs = if (state.marksAvailable) listOf(
                EInkOperationTab(
                    icon = painterResource(R.drawable.eink_ic_toc_e),
                    selectedIcon = painterResource(R.drawable.eink_ic_toc),
                    contentDescription = "目录",
                ),
                EInkOperationTab(
                    icon = painterResource(R.drawable.eink_ic_bookmark_e),
                    selectedIcon = painterResource(R.drawable.eink_ic_bookmark_s),
                    contentDescription = "书签",
                ),
            ) else emptyList(),
            selectedTabIndex = if (state.selectedTab == TocTab.Bookmarks) 1 else 0,
            onTabSelect = { onTabSelect(if (it == 1) TocTab.Bookmarks else TocTab.Chapters) },
            navigationIcon = { /* 现状返回 */ },
            actions = { /* 目录 Tab：回到当前/去底部（现状）；书签 Tab：回到当前（书签首条）+ 去底部 */ },
            pageArrows = pageArrows,
        )
    }
    // 跳转确认弹层（Screen 根级，组合在 Column 外的 Box）
    state.pendingJump?.let { pending ->
        EInkDialog(
            onDismiss = onDismissJump,
            title = "跳转确认",
            confirmText = "仍跳转",
            cancelText = "取消",
            onConfirm = onConfirmJump,
        ) {
            EInkText(text = pending.message, style = EInkTheme.typography.bodyMedium)
        }
    }
```

书签列表 pane（同款固定页分页，无滑动手柄，`userScrollEnabled = false` + `EInkPageSwipe`；分页状态与目录列表各自独立 `rememberEInkListPagerState`）：

```kotlin
@Composable
private fun BookmarkListPane(
    state: TocUiState,
    listState: LazyListState,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onBookmarkClick: (Long) -> Unit,
) {
    if (state.bookmarks.isEmpty()) {
        CenterMessage("暂无书签\n阅读页下拉或点角标添加")
        return
    }
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        overscrollEffect = null,
        modifier = Modifier.fillMaxSize().EInkPageSwipe(onPageUp = onPageUp, onPageDown = onPageDown),
    ) {
        itemsIndexed(state.bookmarks, key = { _, b -> b.id }) { _, bookmark ->
            BookmarkItem(
                bookmark = bookmark,
                isCurrent = bookmark.chapterIndex == state.currentChapterIndex,
                onClick = { onBookmarkClick(bookmark.id) },
            )
        }
    }
}

@Composable
private fun BookmarkItem(
    bookmark: BookmarkUiModel,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s)) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(width = CurrentMarkWidth, height = CurrentMarkHeight)
                        .background(if (press.isPressed) scheme.surface else scheme.onSurface)
                )
            }
            EInkText(
                text = bookmark.chapterName,
                style = EInkTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
            )
        }
        if (bookmark.bookText.isNotBlank()) {
            EInkText(
                text = bookmark.bookText,
                style = EInkTheme.typography.bodyLarge,
                color = if (press.isPressed) colors.contentColor else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (bookmark.content.isNotBlank()) {
            EInkText(
                text = bookmark.content,
                style = EInkTheme.typography.bodyMedium,
                color = if (press.isPressed) colors.contentColor else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

Route 层翻页与 Tab 编排要点：两个 pager（目录 `pagerChapters` 现名 `pager` 保持、书签 `pagerBookmarks`）；`pageArrows` 槽按 `selectedTab` 读对应 pager 的 `canPageUp/canPageDown`；书签 Tab 的「回到当前」= `pagerBookmarks.jumpToItemAligned(当前章首条书签下标)`（`state.bookmarks.indexOfFirst { it.chapterIndex >= state.currentChapterIndex }.coerceAtLeast(0)`，无书签时无动作）。

- [ ] **Step 3: 跳转编排（引擎动作在 Route 内完成，导航动作由 EInkApp 注入）**

`TocRoute` 内收集跳转目标并执行引擎侧动作（有会话即时跳；无会话落进度）：

```kotlin
    // TocRoute 内：
    LaunchedEffect(viewModel, onJumpToLocation) {
        viewModel.jumpTarget.collect { target ->
            val reader = EInkEngineRegistry.readerEngine
            if (reader.sessionBookUrl == bookUrl) {
                reader.jumpToPosition(target.chapterIndex, target.chapterPos)
            } else {
                // 无会话（详情等路径进入）：落进度（含章内位置），导航后阅读页装载时落位
                val chapterTitle = viewModel.uiState.value.chapters
                    .getOrNull(target.chapterIndex)?.title ?: ""
                EInkEngineRegistry.tocEngine.saveReadingProgress(
                    bookUrl, target.chapterIndex, chapterTitle, target.chapterPos,
                )
            }
            onJumpToLocation(target)
        }
    }
```

`EInkApp` 的 `TocRoute(...)` 追加两个回调（引擎动作已由 Route 完成，回调只管导航）：

```kotlin
                                onOpenNote = {
                                    controller.navigate(EInkScreen.Note(screen.bookUrl, screen.fromReader))
                                },
                                onJumpToLocation = {
                                    if (screen.fromReader) {
                                        controller.pop()
                                    } else {
                                        controller.replaceTop(EInkScreen.Reader(screen.bookUrl))
                                    }
                                },
```

- [ ] **Step 4: `EInkApp` Note 分支**（本任务先接占位路由，页面在 Task 10 交付；为保编译可先渲染 `CenterMessage("笔记页施工中")` 级别的最小 NoteRoute，Task 10 替换实现）

- [ ] **Step 5: 验证**：`.\gradlew.bat :modules:eink:testDebugUnitTest :app:assembleAppDebug` → PASS / BUILD SUCCESSFUL

- [ ] **Step 6: Commit**：`feat(eink): 目录页双 Tab（目录|书签）与书签跳转、笔记入口接线`

---

## 切片 3：笔记页与导出

### Task 9: NoteViewModel

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/note/NoteViewModel.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/note/NoteUiStateTest.kt`

**Interfaces:**
- Consumes: `MarksEngine`（Task 1）、`TocEngine.resolveBook`（书名与当前章下标）。
- Produces: `NoteUiState` + `NoteViewModel(loadBook/onMarkingClick/confirmPendingJump/dismissPendingJump/exportMarkdown(uri))`、`jumpTarget/messages: SharedFlow`。

- [ ] **Step 1: UiState 失败测试**

```kotlin
package io.legado.app.eink.feature.note

import io.legado.app.eink.contract.JumpResolution
import org.junit.Assert.*
import org.junit.Test

class NoteUiStateTest {

    @Test
    fun `默认态加载中且无确认`() {
        val s = NoteUiState()
        assertTrue(s.isLoading)
        assertTrue(s.markings.isEmpty())
        assertNull(s.pendingJump)
        assertFalse(s.canExport)
    }

    @Test
    fun `有笔记即可导出`() {
        val s = NoteUiState(
            isLoading = false,
            markings = listOf(MarkingUiModel("a", 0, "第一章", "文", "", false, 1L)),
        )
        assertTrue(s.canExport)
    }
}
```

（`MarkingUiModel` 从 `io.legado.app.eink.contract` import。）

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

```kotlin
package io.legado.app.eink.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.arch.EInkImmutable
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.contract.MarkingUiModel
import io.legado.app.eink.contract.PendingJumpConfirm
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@EInkImmutable
data class NoteUiState(
    /** 书名（顶栏标题）。 */
    val bookName: String = "",
    /** 混合列表（划线+想法，按章+创建时间升序）。 */
    val markings: List<MarkingUiModel> = emptyList(),
    /** 当前阅读章节下标（「回到当前」与当前章标记）。 */
    val currentChapterIndex: Int = 0,
    val isLoading: Boolean = true,
    val exporting: Boolean = false,
    /** 跳转确认弹层（null = 无）。 */
    val pendingJump: PendingJumpConfirm? = null,
) {
    /** 有笔记且未在导出中。 */
    val canExport: Boolean get() = markings.isNotEmpty() && !exporting
}

class NoteViewModel : ViewModel() {

    private val marksEngine get() = EInkEngineRegistry.marksEngine
    private val tocEngine get() = EInkEngineRegistry.tocEngine

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    private val _jumpTarget = MutableSharedFlow<JumpResolution.Located>(extraBufferCapacity = 16)
    val jumpTarget: SharedFlow<JumpResolution.Located> = _jumpTarget.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun loadBook(bookUrl: String) {
        val engine = marksEngine ?: run {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        viewModelScope.launch {
            val book = tocEngine.resolveBook(bookUrl)
            if (book == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    bookName = book.name,
                    currentChapterIndex = book.currentChapterIndex,
                    isLoading = false,
                )
            }
            engine.observeMarkings(bookUrl).collect { list ->
                _uiState.update { it.copy(markings = list) }
            }
        }
    }

    fun onMarkingClick(id: String) {
        val engine = marksEngine ?: return
        viewModelScope.launch {
            when (val r = engine.resolveMarkingJump(id)) {
                is JumpResolution.Located -> _jumpTarget.tryEmit(r)
                is JumpResolution.NeedConfirm -> _uiState.update {
                    it.copy(pendingJump = PendingJumpConfirm(r.message, r.fallback))
                }
                is JumpResolution.Failed -> _messages.tryEmit(r.message)
            }
        }
    }

    fun confirmPendingJump() {
        val pending = _uiState.value.pendingJump ?: return
        _uiState.update { it.copy(pendingJump = null) }
        pending.fallback?.let { _jumpTarget.tryEmit(it) }
    }

    fun dismissPendingJump() {
        _uiState.update { it.copy(pendingJump = null) }
    }

    /** 导出 Markdown 到 SAF uri（结果经 messages 反馈，exporting 期间置灰按钮）。 */
    fun exportMarkdown(bookUrl: String, uri: String) {
        val engine = marksEngine ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(exporting = true) }
            val ok = engine.exportMarkingsMarkdown(bookUrl, uri)
            _uiState.update { it.copy(exporting = false) }
            _messages.tryEmit(if (ok) "已导出" else "导出失败")
        }
    }
}
```

（`PendingJumpConfirm` 已定义在 `contract/MarksEngine.kt`（Task 1），此处直接 import。）

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**：`feat(eink): 笔记页 ViewModel（混合列表/跳转分派/导出意图）`

### Task 10: NoteScreen UI 与 SAF 导出

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/note/NoteScreen.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/app/EInkApp.kt`（替换 Task 8 占位）

**Interfaces:**
- Consumes: Task 9 VM；`rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"))`（先例 `ReaderScreen.kt:537`）；素材 `eink_ic_note_export`。

- [ ] **Step 1: Route + Screen**

```kotlin
@Composable
fun NoteRoute(
    bookUrl: String,
    onBack: () -> Unit,
    onJumpToLocation: (JumpResolution.Located) -> Unit = {},
    viewModel: NoteViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val refresh = LocalEInkRefreshController.current

    LaunchedEffect(bookUrl) { viewModel.loadBook(bookUrl) }

    // SAF 导出：bookName 变化时重建建议文件名
    var pendingExport by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null && pendingExport) {
            pendingExport = false
            viewModel.exportMarkdown(bookUrl, uri.toString())
        }
    }

    // 跳转/消息收集（对齐 Task 8 TocRoute 模式；无会话分支经 tocEngine 落进度）
    LaunchedEffect(viewModel, onJumpToLocation) { viewModel.jumpTarget.collect { /* 同 TocRoute */ } }
    LaunchedEffect(viewModel) { viewModel.messages.collect { /* toast 通道 */ } }

    // 翻页动作（对齐 TocRoute：remember 稳定实例 + PageTurn 上报）
    val displayCount = uiState.markings.size

    NoteScreen(
        state = uiState,
        pager = pager,
        onBack = onBack,
        onBackToCurrent = {
            val index = uiState.markings
                .indexOfFirst { it.chapterIndex >= uiState.currentChapterIndex }
                .coerceAtLeast(0)
            scope.launch { pager.jumpToItemAligned(index) }
        },
        onPageUp = { scope.launch { pager.pageUp() }; refresh.requestRefresh(EInkRefreshIntent.PageTurn) },
        onPageDown = {
            scope.launch { pager.pageDown(displayCount) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        },
        onMarkingClick = viewModel::onMarkingClick,
        onConfirmJump = viewModel::confirmPendingJump,
        onDismissJump = viewModel::dismissPendingJump,
        onExport = {
            pendingExport = true
            exportLauncher.launch("${uiState.bookName}-笔记.md")
        },
    )
}
```

Screen 结构（顶栏导出按钮 + 混合列表 + 底部操作栏，条目样式规格 §5）：

```kotlin
@Composable
internal fun NoteScreen(/* 参数 = Route 透传 */) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            EInkTopBar(
                title = state.bookName.ifBlank { "笔记" },
                actionsFillMax = true,
                actions = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_note_export),
                        contentDescription = "导出",
                        onClick = { if (state.canExport) onExport() },
                    )
                },
            )
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> EInkLoading(modifier = Modifier.fillMaxSize())
                    state.markings.isEmpty() -> CenterMessage("暂无划线或想法")
                    else -> MarkingList(/* LazyColumn userScrollEnabled=false + EInkPageSwipe + itemsIndexed(markings, key=id) */)
                }
            }
            EInkOperationBar(
                tabs = emptyList(),
                selectedTabIndex = 0,
                onTabSelect = {},
                navigationIcon = { /* 返回 onBack */ },
                actions = { /* 回到当前（当前章首条笔记，无笔记无动作） */ },
                pageArrows = { /* EInkPageArrows(pager.canPageUp(), pager.canPageDown(displayCount)) */ },
            )
        }
        state.pendingJump?.let { pending ->
            EInkDialog(
                onDismiss = onDismissJump,
                title = "跳转确认",
                confirmText = "仍跳转",
                cancelText = "取消",
                onConfirm = onConfirmJump,
            ) {
                EInkText(text = pending.message, style = EInkTheme.typography.bodyMedium)
            }
        }
    }
}
```

条目（`MarkingItem`）：`Row(线型图例 + 章节名)` → `selectedText` bodyLarge maxLines=3 → `note` 非空时「想法：」前缀 bodyMedium maxLines=3。图例：

```kotlin
@Composable
private fun MarkingLegend(thought: Boolean, pressed: Boolean, color: Color) {
    // 实线段 / 虚线段（16dp 宽、2dp 高；墨水屏灰度，不做彩色）
    if (thought) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.width(16.dp)) {
            repeat(3) { Box(Modifier.width(3.dp).height(2.dp).background(color)) }
        }
    } else {
        Box(Modifier.width(16.dp).height(2.dp).background(color))
    }
}
```

- [ ] **Step 2: `EInkApp` Note 分支替换占位**

```kotlin
                        is EInkScreen.Note -> {
                            NoteRoute(
                                bookUrl = screen.bookUrl,
                                onBack = { controller.pop() },
                                onJumpToLocation = {
                                    // 引擎跳转/落进度已在 Route 内完成，这里只导航：
                                    // fromReader → pop 两次回阅读页；否则 replaceTop(Reader)
                                },
                            )
                        }
```

（`pop 两次`：连续 `controller.pop(); controller.pop()`——pop 同帧幂等性实施时以 `EInkNavControllerTest` 增一例锚定；`fromReader=false` 时 `replaceTop` 前先 pop 掉 Note 再 replaceTop Toc 之上，按栈实际行为调整并记录。）

- [ ] **Step 3: 验证**：`.\gradlew.bat :modules:eink:testDebugUnitTest :app:assembleAppDebug`

- [ ] **Step 4: Commit**：`feat(eink): 笔记页（混合列表/跳转确认/Markdown 导出）`

### Task 11: 文档与收尾验证

**Files:**
- Modify: `modules/eink/docs/eink-porting.md`（端口表补 `MarksEngine` 行：可选、方法面、降级语义）

- [ ] **Step 1: 端口表补行**（对齐 `selectionEngine` 行格式）

- [ ] **Step 2: 全量验证**

```powershell
.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache
.\gradlew.bat :modules:eink:testDebugUnitTest
git diff --check
```

Expected: 宿主测试基线零新增失败；lint/架构门禁 PASS；BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**：`docs(eink): eink-porting 端口表补 MarksEngine 行`

## 真机验证清单（交付后逐项勾选）

- [ ] 双 Tab 切换整页刷新残影可接受；marksEngine 缺失构建下单列表现状不回退
- [ ] 书签条目点击：当前章内跳转落页首；跨章跳转落点正确；阅读时间不被刷新
- [ ] 笔记条目点击：划线/想法落点均在选区所在页；阅读页重排后页码正确
- [ ] 换源书：书签/笔记跳转触发「仍跳转」确认；确认后按存储坐标跳；取消停留
- [ ] 详情 → 目录 → 书签跳转（无会话路径）：落进度后进阅读页位置正确
- [ ] 笔记导出：SAF 建档 `书名-笔记.md`，PC 打开分组/想法行正确；空列表导出按钮置灰
- [ ] 笔记页返回回目录页；跳转直达阅读页（pop 两次）
- [ ] 书签/笔记列表翻页不溢屏（变高条目实测；溢页则启用回退方案：pager 按视口像素翻页变体）
