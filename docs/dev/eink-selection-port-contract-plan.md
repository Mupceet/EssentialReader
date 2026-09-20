# eink 选区批注端口契约重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `ReaderSelectionEngine` 契约重构为状态机形态——`createMarking` / `updateMarkingNote(id, note)` / `togglePageBookmark(content 载荷)`，thought 字段删除改由 note 派生，书签显示字段由模块携带。

**Architecture:** 双仓落地：契约 + 模块 UI/VM 在 eink-lib 子模块仓（`:modules:eink`，`eink/lib` 孤儿分支）原子改完并先行提交；宿主 bridge 实现与测试在主仓跟进；最后主仓推进子模块指针。设计依据：[eink-selection-port-contract-design.md](./eink-selection-port-contract-design.md)（已确认）。

**Tech Stack:** Kotlin、Compose（模块侧 UI 仅小改）、Koin、JUnit4。宿主 JDK 21；命令在 Git Bash 下用 `./gradlew.bat`（等价于文档里的 `.\gradlew.bat`）。

**行为基线（全程不得回退）：** 划线⇄想法转换一条记录、id 不变；删除/失效路径不变；书签落库字段口径不变（chapterName 同源 `page.chapterTitle`、pageText 行间 `\n` 同构）。唯一故意变化：部分重叠选区的「想法」不再另落重叠记录（转按 id 更新被交叠标记）。

---

### Task 1: eink-lib 子模块仓——契约重写与模块适配

签名变更是原子的：契约、VM、Screen、policy、测试必须同任务内改完才能编译。全部改动在 `D:/Projects/AndroidProjects/EssentialReader/eink-lib` 仓内。

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderSelectionEngine.kt`（整文件重写）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`（`saveMarking`/`togglePageBookmark` 区域，约 583-644 行）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`（草稿类、commitLine、两处 THOUGHT、confirm 块、import）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderSelectionInteractionPolicy.kt`（删 `markingThoughtFromNote`）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderSelectionSheets.kt`（KDoc 更新）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderTextSelection.kt`（新增 `toPageBookmarkContent`）
- Modify: `modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/ReaderSelectionInteractionPolicyTest.kt`（删一个测试）
- Create: `modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/ReaderPageBookmarkContentTest.kt`

- [ ] **Step 1.1: 建子模块工作分支**

子模块当前是 detached HEAD（指针 30dfb59c3）。在 `D:/Projects/AndroidProjects/EssentialReader/eink-lib` 下：

```bash
cd /d/Projects/AndroidProjects/EssentialReader/eink-lib
git switch -c eink/lib        # 已存在同名分支则报错，改用: git switch eink/lib 并确认 HEAD == 30dfb59c3
git status --short            # 期望干净
```

- [ ] **Step 1.2: 写失败测试（新载荷拼装）**

创建 `modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/ReaderPageBookmarkContentTest.kt`：

```kotlin
package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.contract.ReaderPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** 页面书签显示载荷拼装（契约 v2：书签显示语义归模块）。 */
class ReaderPageBookmarkContentTest {

    @Test
    fun `章节名取快照标题`() {
        val page = snapshot(title = "第十二章 夜航", line("正文"))
        assertEquals("第十二章 夜航", page.toPageBookmarkContent().chapterName)
    }

    @Test
    fun `页文本按行拼接且行间换行`() {
        val page = snapshot(
            line("标题行", positions = intArrayOf(0), isTitle = true),
            line("第一段", positions = intArrayOf(0)),
            line("第二段", positions = intArrayOf(3)),
        )
        assertEquals("标题行\n第一段\n第二段", page.toPageBookmarkContent().pageText)
    }

    @Test
    fun `行内多段拼接为连续文本`() {
        val page = snapshot(
            line("前段", "后段", positions = intArrayOf(0, 2)),
        )
        assertEquals("前段后段", page.toPageBookmarkContent().pageText)
    }

    @Test
    fun `无正文行的页文本为空串`() {
        val page = snapshot(title = "章")
        assertEquals("", page.toPageBookmarkContent().pageText)
    }

    private fun line(
        vararg chunks: String,
        positions: IntArray = intArrayOf(0),
        isTitle: Boolean = false,
    ): ReaderPageLine = ReaderPageLine(
        baseY = 60f,
        isTitle = isTitle,
        chunks = chunks.toList(),
        x = FloatArray(chunks.size) { it * 100f },
        chapterPositions = positions,
        top = 30f,
        bottom = 70f,
    )

    private fun snapshot(title: String, vararg lines: ReaderPageLine) = ReaderPageSnapshot(
        title = title,
        readProgress = "1/1",
        titleSpec = ReaderPaintSpec(20f, 0f, null, null),
        contentSpec = ReaderPaintSpec(20f, 0f, null, null),
        lines = lines.toList(),
        images = emptyList(),
    )
}
```

- [ ] **Step 1.3: 确认红**

```bash
cd /d/Projects/AndroidProjects/EssentialReader && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.selection.ReaderPageBookmarkContentTest"
```

预期：**编译失败**（`ReaderPageBookmarkContent` / `toPageBookmarkContent` 未解析）。这就是本步的正确结果。

- [ ] **Step 1.4: 重写契约文件**

`modules/eink/src/main/java/io/legado/app/eink/contract/ReaderSelectionEngine.kt` 整文件替换为：

```kotlin
package io.legado.app.eink.contract

import androidx.compose.runtime.Stable

/**
 * 选区语义与批注落库端口：宿主负责锚点构造（上下文与哈希）并写
 * book_marks，提供页面级书签 toggle。模块不复制这些规则。
 *
 * 契约 v2（0.7.0 起，状态机进签名）：笔记（划线/想法）是**同一条记录的
 * 两种状态**——转换只能走 [updateMarkingNote]（按 id，锚点不变），
 * [createMarking] 仅"新选区"入口可达，结构性杜绝宿主把转换实现成重复
 * 添加。类型恒由 note 派生：note 空白 = 划线（实线）；非空白 = 想法
 * （虚线）——契约不再有并列的 thought 字段。书签显示字段（章节名 + 页
 * 文本摘录）由 [ReaderPageBookmarkContent] 携带，宿主不自定显示语义。
 *
 * 可选端口（同 [EInkEngineRegistry.appUpdateEngine] 先例）：注册表缺失本端口
 * 时，模块降级——长按选择整体不启用（松手无动作、无操作条，选择交互无
 * 可用出路），下拉书签与顶栏书签钮隐藏，不做假死路径。
 *
 * 能力粒度（0.6.0 起）：注册 = 至少支持一种能力，宿主按实际支持覆写
 * 能力声明——仅支持划线/想法的宿主 [supportsPageBookmark] = false（下拉
 * 书签开关、顶栏书签钮、页角标、下拉手势隐藏），仅支持页面书签的宿主
 * [supportsMarkings] = false（长按选择不启用，操作条只余复制）。默认
 * 均为 true（两能力齐备，旧宿主零改动）。
 */
interface ReaderSelectionEngine {

    /**
     * 划线/想法能力：长按选择、选区操作条标记动作与点按标记浮条。
     *
     * 与 [MarksEngine.supportsMarkings] 同名不同义：此处是**阅读内保存**
     * 能力，彼处是**目录页列表/导出**能力；宿主通常两者一致声明，
     * 不一致时两处入口各自独立显隐（互不推导）。
     */
    val supportsMarkings: Boolean get() = true

    /** 页面书签能力：快速书签 toggle、下拉书签、顶栏书签钮、页角标。 */
    val supportsPageBookmark: Boolean get() = true

    /**
     * 新建笔记（仅"新选区"入口可达；已有标记上的动作走 [updateMarkingNote]/
     * [deleteMarking]）。[ReaderSelectionCommit.note] 空白 = 划线（实线），
     * 非空白 = 想法（虚线）。样式由宿主桥写入 TextProcessStyle
     * （underlineMode 1/2；颜色固定纯黑，eink 与完整模式显示一致）。
     * 宿主落库后自行触发当前章重排（保持页内位置），新快照经
     * onContentUpdated 携带装饰推送——模块不请求刷新。
     * false = 落库失败（模块提示「保存失败」并恢复现场）。
     */
    suspend fun createMarking(commit: ReaderSelectionCommit): Boolean

    /**
     * 唯一的状态转换路径：按 id 改想法，锚点与选区不变（宿主不做重定位）。
     * note 空白 → 划线（实线）；非空白 → 想法（虚线）。
     * null = 标记不存在（换源清理等，模块按标记失效处理）；false = 更新失败；
     * true = 成功。宿主更新后触发当前章重排（同 createMarking 推送路径）。
     */
    suspend fun updateMarkingNote(markingId: String, note: String): Boolean?

    /**
     * 删除标记。false = 删除失败（模块提示并保留现场）。
     * 宿主删除后触发当前章重排（同 createMarking 推送路径）。
     */
    suspend fun deleteMarking(markingId: String): Boolean

    /**
     * 读取标记详情（点按想法时浮窗展示与写想法预填）。
     * null = 标记不存在（换源清理等，模块按标记失效处理，不弹浮窗）。
     */
    suspend fun findMarking(markingId: String): ReaderMarkingDetail?

    /**
     * 当前页书签 toggle（宿主快速书签语义：同页已有多条时删最近一条）。
     * 显示字段由 [content] 携带（模块从当前页快照组装）：宿主原样落库——
     * 对自家渲染占位符的清理属存储规范化，不构成显示语义；同页判定（页
     * 正文区间）与「删最近一条」按宿主分页事实。自动记录：页位置 +
     * content 引用文本，无编辑层。宿主落库后触发当前章重排（角标随新快照
     * 推送）。null = 无会话书/当前页无法定位；true = 本次添加；false =
     * 本次移除。
     */
    suspend fun togglePageBookmark(content: ReaderPageBookmarkContent): Boolean?
}

/** 笔记提交载荷（仅新建路径；已有标记的更新走 updateMarkingNote，不重提交选区）。 */
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

    /** 初始想法内容：空白 = 划线（实线）；非空白 = 想法（虚线）。 */
    val note: String,
)

/** 标记详情（点按浮窗与写想法预填；类型由 note 派生，不再有 thought 字段）。 */
@Stable
class ReaderMarkingDetail(
    /** 划线选中的原文。 */
    val selectedText: String,

    /** 想法内容（空白 = 划线/实线；非空白 = 想法/虚线）。 */
    val note: String,
)

/** 页面书签显示载荷（模块从当前页快照组装，宿主原样落库）。 */
@Stable
class ReaderPageBookmarkContent(
    /** 章节标题（= 页快照 title，与宿主 page.chapterTitle 同源）。 */
    val chapterName: String,

    /** 页文本摘录（快照行内 chunks 连接、行间 \n，与宿主 page.text 同构；
     *  图片页不含 \uFFFC 占位——模块无此字符语义）。 */
    val pageText: String,
)
```

- [ ] **Step 1.5: 新增载荷拼装扩展**

`ReaderTextSelection.kt`：在 `internal fun lineText(...)`（第 27 行）之后新增：

```kotlin
/**
 * 页面书签显示载荷（契约 v2）：快照行文本按与宿主 page.text 同构的口径
 * 拼装（行内 chunks 连接、行间 \n）；chapterName 取快照 title（与宿主
 * page.chapterTitle 同源）。书签显示语义归模块，宿主只做存储规范化。
 */
internal fun ReaderPageSnapshot.toPageBookmarkContent(): ReaderPageBookmarkContent =
    ReaderPageBookmarkContent(
        chapterName = title,
        pageText = lines.joinToString("\n") { lineText(it) },
    )
```

并在该文件 import 区加入 `io.legado.app.eink.contract.ReaderPageBookmarkContent`（其余 contract 导入已有）。

- [ ] **Step 1.6: 改 ReaderViewModel**

替换 `saveMarking` / `togglePageBookmark`（约 585-644 行区域；`deleteMarking`/`findMarking` 不动，只改各自 KDoc 里对 saveMarking 的引用字样）为：

```kotlin
/**
 * 新建笔记（契约 v2：仅新选区入口——操作条「画线」与新选区「想法」确认）：
 * note 空白 = 划线（宿主写 underlineMode=1 实线，固定纯黑），非空白 = 想法
 * （underlineMode=2 虚线）。宿主落 book_marks 后自行触发当前章重排（保持
 * 页内位置）并经 onContentUpdated 推送带装饰的新快照——模块不请求刷新。
 * false = 端口未注册（降级宿主）或落库失败。
 */
suspend fun createMarking(sel: ReaderSelectionUi, note: String): Boolean {
    val port = EInkEngineRegistry.selectionEngine ?: return false
    return port.createMarking(
        ReaderSelectionCommit(
            chapterIndex = engine.currentChapterIndex,
            start = sel.bodyStart,
            end = sel.bodyEnd,
            selectedText = sel.selectedText,
            note = note,
        ),
    )
}

/**
 * 状态转换唯一路径（契约 v2）：按 id 改想法，锚点不变——note 空白 → 划线
 * （实线），非空白 → 想法（虚线）。null = 标记不存在（换源清理等）；false =
 * 更新失败。端口未注册理论不可达（弹层仅在选择能力在位时打开），防御性按
 * 失败处理。
 */
suspend fun updateMarkingNote(markingId: String, note: String): Boolean? {
    val port = EInkEngineRegistry.selectionEngine ?: return false
    return port.updateMarkingNote(markingId, note)
}
```

`togglePageBookmark` 替换为：

```kotlin
/**
 * 当前页书签 toggle（v2 Task 9，设计 §4）：显示载荷（章节名 + 页文本摘录）
 * 由模块从当前页快照组装（契约 v2——书签显示语义归模块），宿主只负责同页
 * 判定、存储与重排。三态：null = 端口未注册（降级宿主）/无会话书/无当前页
 * 快照；true = 本次添加；false = 本次移除。仅 null 由界面提示「操作失败」，
 * true/false 均静默成功（角标变化即反馈）。
 */
suspend fun togglePageBookmark(): Boolean? {
    val port = EInkEngineRegistry.selectionEngine ?: return null
    val page = _uiState.value.page ?: return null
    return port.togglePageBookmark(page.toPageBookmarkContent())
}
```

import 区：新增 `io.legado.app.eink.feature.reader.selection.toPageBookmarkContent`。

- [ ] **Step 1.7: 改 ReaderScreen**

五处改动：

(a) `ReaderThoughtDraft`（约 141-154 行）替换为：

```kotlin
/**
 * 想法弹框草稿（v2.2 统一，契约 v2 加 id 分派）：两个入口（选区操作条 /
 * 点按标记操作条）共用**同一个弹框**。[markingId] null = 新建（确认走
 * createMarking）；非空 = 更新已有标记的想法（确认走 updateMarkingNote，
 * 锚点不变）。[note] 预填——已有想法带出宿主记录的笔记内容，划线/新选区
 * 为空串；[selection] 是弹层预览选区（点按入口用完整原文覆写行内截段；
 * 仅新建路径作为提交选区）。
 *
 * 保存时按内容判类型：note 非空 = 想法（虚线），清空 = 划线（实线）。
 */
@Stable
private data class ReaderThoughtDraft(
    val markingId: String?,
    val note: String,
    val selection: ReaderSelectionUi,
)
```

(b) `commitLine`（约 315-324 行）中 `viewModel.saveMarking(target, note = "", thought = false)` 改为 `viewModel.createMarking(target, note = "")`。

(c) `onSelectionAction` 的 THOUGHT 分支（约 347-363 行）改为：

```kotlin
ReaderMarkingAction.THOUGHT -> {
    if (markingId == null) {
        thoughtDraft = ReaderThoughtDraft(markingId = null, note = "", selection = target)
    } else {
        scope.launch {
            when (val detail = viewModel.findMarking(markingId)) {
                // 标记失效（换源清理/无会话）：清态，不弹层
                null -> clearSelectionState()
                // 已有想法带出笔记内容、已有划线为空——弹框同一份，按 id 更新
                else -> thoughtDraft = ReaderThoughtDraft(
                    markingId = markingId,
                    note = detail.note,
                    selection = target.copy(selectedText = detail.selectedText),
                )
            }
        }
    }
}
```

(d) `onMarkingAction` 的 THOUGHT 分支（约 464-468 行）改为：

```kotlin
else -> thoughtDraft = ReaderThoughtDraft(
    markingId = target.markingId,
    note = detail.note,
    selection = target.commitSelection,
)
```

(e) 想法弹层 confirm 分派（约 1083-1105 行的 `when` 块）替换为：

```kotlin
val target = thoughtDraft
when {
    target == null -> Toast.makeText(
        context, "选区已失效", Toast.LENGTH_SHORT
    ).show()

    // 新建：内容判类型（空 = 划线实线 / 非空 = 想法虚线）
    target.markingId == null -> when {
        viewModel.createMarking(target.selection, note) -> {
            thoughtDraft = null
            // 操作条不回来：动作已完成（装饰随重排呈现）
            markingBar = null
            selectionFrozen = true
            committedSelection = target.selection
        }

        else -> Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
    }

    // 更新：按 id 状态转换，锚点不变；null = 标记已失效（清态收层）
    else -> when (viewModel.updateMarkingNote(target.markingId, note)) {
        true -> {
            thoughtDraft = null
            markingBar = null
            selectionFrozen = true
            committedSelection = target.selection
        }

        false -> Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()

        null -> {
            thoughtDraft = null
            markingBar = null
            clearSelectionState()
            Toast.makeText(context, "标记已失效", Toast.LENGTH_SHORT).show()
        }
    }
}
```

附带清理：
- 删 import `io.legado.app.eink.feature.reader.selection.markingThoughtFromNote`（约 112 行）。
- `ReaderMarkingBar` KDoc（约 128-139 行）中「`[commitSelection]` 为『写想法』提交专用（原文覆写版……）防止跨行标记按行内截段落库时 upsert 不命中原记录」改为「`[commitSelection]` 为想法弹层预览专用（完整原文覆写行内截段；契约 v2 起更新按 id 提交，不再作为落库选区）」。
- 上方约 442-443 行、328-329 行注释里「saveMarking(thought=true) 同锚点 upsert，划线转想法」「提交文本一律用标记完整原文，防跨行标记按行内截段落库 upsert 不命中」同步改为「按 id updateMarkingNote（锚点不变）」的说法；预览文本仍取完整原文。

- [ ] **Step 1.8: 清理 policy 与 sheets**

`ReaderSelectionInteractionPolicy.kt`：删除 `markingThoughtFromNote` 及其 KDoc（约 70-76 行整块）。
`ReaderSelectionInteractionPolicyTest.kt`：删除测试 `` `想法弹框内容清空即变划线` ``（约 138-145 行）。
`ReaderSelectionSheets.kt`：KDoc 第 42-43 行「确认 = 调用方按**内容**判类型落库：note 非空 = 想法（虚线），清空 = 划线（实线，想法清空内容即自动变回划线）；同锚点 upsert 为原地更新。」改为「确认 = 调用方按**内容**判类型落库：note 非空 = 想法（虚线），清空 = 划线（实线，想法清空内容即自动变回划线）；新建/更新由调用方按弹层来源分派（createMarking / updateMarkingNote）。」

- [ ] **Step 1.9: 跑模块测试（绿）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader && ./gradlew.bat :modules:eink:testDebugUnitTest
```

预期：全部 PASS（含新增 4 个载荷测试）。`:app:compileAppDebugKotlin` 此时会失败是**预期内**的（宿主 bridge 还是旧签名），Task 2 修复。

- [ ] **Step 1.10: 残留检查**

```bash
grep -rn "saveMarking\|markingThoughtFromNote" /d/Projects/AndroidProjects/EssentialReader/eink-lib/modules/eink/src --include="*.kt"
grep -rn "\.thought" /d/Projects/AndroidProjects/EssentialReader/eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader --include="*.kt"
```

预期：第一条零命中；第二条零命中（TocScreen 的 `marking.thought` 在 feature/toc，不在 reader，且是 `MarksEngine.MarkingUiModel` 字段，保留）。

- [ ] **Step 1.11: 提交（eink-lib 仓）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader/eink-lib
git add modules/eink/src
git commit -m "refactor(eink)!: ReaderSelectionEngine 契约 v2——createMarking/updateMarkingNote 状态机进签名，thought 由 note 派生，书签显示载荷由模块携带"
git diff --check
```

---

### Task 1b: eink-lib 子模块仓——书签载荷段落边界修正（Task 1 质量审查 Critical 修复）

质量审查发现：初版 `toPageBookmarkContent` 逐视觉行 `joinToString("\n")` 与宿主
`page.text` 不同构——宿主只在段落边界插 `\n`（同段折行不插；空行为 `\n\n`，
`ReaderPaginatorTest:72/133` 固化）。修正方案 a：契约补段落边界信息（设计文档
决策记录「实施期修正」条目）。

**Files（均在 eink-lib 仓）:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderPageSnapshot.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderSelectionEngine.kt`（仅 KDoc）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderTextSelection.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/ReaderPageBookmarkContentTest.kt`

- [ ] **Step 1b.1: 先补失败测试**

`ReaderPageBookmarkContentTest.kt`：`line(...)` helper 加 `breaks: Int = 0` 参数并传入
`paragraphBreaksAfter = breaks`；新增两个用例：

```kotlin
@Test
fun `同段折行不插换行`() {
    val page = snapshot(
        line("第一段前半", positions = intArrayOf(0), breaks = 0),
        line("后半", positions = intArrayOf(5), breaks = 1),
    )
    assertEquals("第一段前后半", page.toPageBookmarkContent().pageText)
}

@Test
fun `空行分隔累积双换行`() {
    val page = snapshot(
        line("甲段", positions = intArrayOf(0), breaks = 2),
        line("乙段", positions = intArrayOf(4), breaks = 1),
    )
    assertEquals("甲段\n\n乙段", page.toPageBookmarkContent().pageText)
}
```

跑 `./gradlew.bat :modules:eink:testDebugUnitTest --tests "...ReaderPageBookmarkContentTest"`
确认编译失败（`paragraphBreaksAfter` 未解析）。

- [ ] **Step 1b.2: 契约加字段**

`ReaderPageSnapshot.kt` 的 `ReaderPageLine`，在 `decorations` 之前（`bottom` 之后）加：

```kotlin
    /**
     * 本行之后的段落边界数（契约 v2 书签载荷拼装依据，与宿主 page.text
     * 段落边界口径同构）：0 = 下一行是同段折行续行；1 = 段落在本行结束；
     * 空行/占位块每个累加 1（如空行分隔 = 2）。末行的值不参与拼装。
     * 宿主映射器实现义务：从排版块结构填充；缺省 0（旧宿主摘录退化为
     * 无段落分隔）。
     */
    val paragraphBreaksAfter: Int = 0,
```

- [ ] **Step 1b.3: 拼装改用边界**

`ReaderTextSelection.kt` 的 `toPageBookmarkContent` 替换为：

```kotlin
/**
 * 页面书签显示载荷（契约 v2）：快照行文本按与宿主 page.text 同构的段落
 * 边界口径拼装（行内 chunks 连接；行间按上一行 paragraphBreaksAfter 插
 * "\n".repeat(n)——同段折行 0、段末 1、空行累加；末行不计）。chapterName
 * 取快照 title（与宿主 page.chapterTitle 同源）。书签显示语义归模块，
 * 宿主只做存储规范化。
 */
internal fun ReaderPageSnapshot.toPageBookmarkContent(): ReaderPageBookmarkContent =
    ReaderPageBookmarkContent(
        chapterName = title,
        pageText = buildString {
            lines.forEachIndexed { index, line ->
                append(lineText(line))
                if (index < lines.lastIndex) append("\n".repeat(line.paragraphBreaksAfter))
            }
        },
    )
```

`ReaderSelectionEngine.kt` 中 `ReaderPageBookmarkContent.pageText` 的 KDoc 改为：

```kotlin
    /** 页文本摘录（行内 chunks 连接；行间按 ReaderPageLine.paragraphBreaksAfter
     *  插段落换行——同段折行无换行、段末一个、空行累加，与宿主 page.text
     *  段落边界口径同构；图片页不含 \uFFFC 占位——模块无此字符语义）。 */
```

- [ ] **Step 1b.4: 跑测试与残留检查（绿）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader && ./gradlew.bat :modules:eink:testDebugUnitTest
```

预期全绿（含新增 2 用例）。`git -C eink-lib diff --check` 干净。

- [ ] **Step 1b.5: 提交（eink-lib 仓）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader/eink-lib
git add modules/eink/src
git commit -m "fix(eink): 书签载荷按段落边界拼装——ReaderPageLine 增 paragraphBreaksAfter，对齐宿主 page.text 口径"
```

---

### Task 2: 主仓——宿主 bridge 实现

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderSelectionEngineImpl.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt`（填 paragraphBreaksAfter）
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderSelectionEngineImplTest.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt`（同构对照门禁）

- [ ] **Step 2.1: 写失败测试**

`ReaderSelectionEngineImplTest.kt` 追加（import 区补 `io.legado.app.data.entities.BookMarking`、`io.legado.app.domain.model.TextProcessStyle`、`io.legado.app.utils.GSON`、`io.legado.app.utils.fromJsonObject`）：

```kotlin
@Test
fun `状态转换保留身份与锚点并按内容翻样式`() {
    val anchorJson = """{"chapterIndex":3,"chapterPosition":12,"selectedText":"原文","normalizedTextHash":"h"}"""
    val mark = BookMarking(
        id = "m-1", bookUrl = "u", bookName = "n", bookAuthor = "a",
        chapterIndex = 3, anchorJson = anchorJson,
        styleJson = """{"underlineMode":1}""", note = "",
        chapterName = "c", enabled = true, createdAt = 100L, updatedAt = 100L,
    )
    // 写想法 → 虚线；id/锚点/createdAt 原地不动（转换不是重建）
    val thought = mark.withEinkNote("记一笔", now = 200L)
    assertEquals("m-1", thought.id)
    assertEquals(anchorJson, thought.anchorJson)
    assertEquals(100L, thought.createdAt)
    assertEquals("记一笔", thought.note)
    assertEquals(2, GSON.fromJsonObject<TextProcessStyle>(thought.styleJson).getOrNull()!!.underlineMode)
    // 清空想法（含仅空白）→ 划线，note 归一空串
    val line = thought.withEinkNote("   ", now = 300L)
    assertEquals("", line.note)
    assertEquals(1, GSON.fromJsonObject<TextProcessStyle>(line.styleJson).getOrNull()!!.underlineMode)
}

@Test
fun `书签显示文本剥离渲染占位符并 trim`() {
    assertEquals("正文摘录", bookmarkDisplayText("正文摘录 袮꧁  "))
    assertEquals("", bookmarkDisplayText(" 袮 ꧁ "))
}
```

跑红：

```bash
cd /d/Projects/AndroidProjects/EssentialReader && ./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderSelectionEngineImplTest"
```

预期：**编译失败**（`withEinkNote` / `bookmarkDisplayText` 未解析）。

- [ ] **Step 2.2: 实现宿主侧**

`ReaderSelectionEngineImpl.kt` 改动：

(a) 把 object 内的 `private val BOOK_TEXT_MARKS = Regex("[袮꧁]")` 移到文件顶层（object 外），并新增两个顶层函数：

```kotlin
/** 与宿主 ReadBookmarkDelegate/ReadBookController.addBookmark 一致：剔除正文里的排版占位符。 */
private val BOOK_TEXT_MARKS = Regex("[袮꧁]")

/**
 * 笔记状态转换（契约 v2 updateMarkingNote 的纯函数核）：按 note 派生类型并
 * 重写 styleJson——空白 = 划线（实线，note 归一空串）；非空白 = 想法（虚线）。
 * id、锚点、章节、createdAt 一律不动（原地更新，不是重建）。
 */
internal fun BookMarking.withEinkNote(note: String, now: Long): BookMarking {
    val hasThought = note.isNotBlank()
    return copy(
        note = if (hasThought) note else "",
        styleJson = GSON.toJson(einkMarkingStyle(hasThought)),
        updatedAt = now,
    )
}

/** 书签显示文本（契约 v2）：模块载荷落库前的存储规范化——剥离自家渲染占位符并 trim。 */
internal fun bookmarkDisplayText(pageText: String): String = pageText.replace(BOOK_TEXT_MARKS, "").trim()
```

(b) `saveMarking` 整体改名重写为 `createMarking`（逻辑不变，仅 note/style 推导）：

```kotlin
override suspend fun createMarking(commit: ReaderSelectionCommit): Boolean {
    val book = ReadBook.book ?: run {
        AppLog.put("eink createMarking: 无会话书")
        return false
    }
    val content = awaitSemanticContent(commit.chapterIndex) ?: run {
        AppLog.put("eink createMarking: 章节内容未就绪 chapter=${commit.chapterIndex}")
        return false
    }
    val located = locateSelectionInContent(content, commit.start, commit.selectedText)
    if (located < 0) {
        AppLog.put(
            "eink createMarking: 选区定位失败 chapter=${commit.chapterIndex} " +
                "start=${commit.start} len=${commit.selectedText.length} " +
                "text=${commit.selectedText.take(24)}"
        )
        return false
    }
    val (before, after) = extractContext(content, located, commit.selectedText.length)
    val hasThought = commit.note.isNotBlank()
    return try {
        saveMarkingUseCase.save(
            bookName = book.name,
            bookAuthor = book.author,
            bookUrl = book.bookUrl,
            chapterIndex = commit.chapterIndex,
            chapterPosition = located,
            selectedText = commit.selectedText,
            style = einkMarkingStyle(hasThought),
            chapterName = displayTitle(),
            note = if (hasThought) commit.note else "",
            contextBefore = before,
            contextAfter = after,
        )
        // 新快照经 onContentUpdated 推送（保持页内位置），模块随重绘清选区
        ReaderEngineImpl.relayout()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.put("eink createMarking failed: ${e.message}", e)
        false
    }
}
```

(c) 新增 `updateMarkingNote`（放在 createMarking 之后）：

```kotlin
/**
 * 状态转换唯一路径（契约 v2）：按 id 改 note + 样式，锚点不动、**不做选区
 * 重定位**（编辑已存标记不再可能因定位失败而保存失败）。null = 无会话书/
 * 标记不存在；false = 更新失败。成功后 relayout，新快照带新样式装饰推送。
 */
override suspend fun updateMarkingNote(markingId: String, note: String): Boolean? = try {
    ReadBook.book ?: return null
    val mark = bookMarkingGateway.getById(markingId) ?: return null
    bookMarkingGateway.upsert(mark.withEinkNote(note, System.currentTimeMillis()))
    ReaderEngineImpl.relayout()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppLog.put("eink updateMarkingNote failed: ${e.message}", e)
    false
}
```

(d) `findMarking` 返回值去掉 `thought`：

```kotlin
return ReaderMarkingDetail(
    selectedText = anchor?.selectedText.orEmpty(),
    note = mark.note,
)
```

(e) `togglePageBookmark` 签名加载荷、落库字段改用载荷（页区间查询与最近删除逻辑不动）：

```kotlin
override suspend fun togglePageBookmark(content: ReaderPageBookmarkContent): Boolean? = try {
    toggleMutex.withLock {
        val book = ReadBook.book ?: return@withLock null
        val meta = ReaderEngineImpl.currentPageMeta() ?: return@withLock null
        val existing = bookmarkRepository.getByChapterRange(
            bookName = book.name,
            bookAuthor = book.author,
            chapterIndex = meta.chapterIndex,
            startPos = meta.bodyStart,
            endPos = meta.bodyEnd,
        )
        if (existing.isEmpty()) {
            bookmarkRepository.save(
                Bookmark(
                    bookName = book.name,
                    bookAuthor = book.author,
                    bookUrl = book.bookUrl,
                    chapterIndex = meta.chapterIndex,
                    chapterName = content.chapterName,
                    chapterPos = ReadBook.durChapterPos,
                    bookText = bookmarkDisplayText(content.pageText),
                    content = "",
                )
            )
            // 角标随新快照刷新（保持页内位置）
            ReaderEngineImpl.relayout()
            true
        } else {
            // 只删离当前阅读位置最近的一条：同一页可能有多条书签，不应整页误删
            val nearest = existing.minByOrNull { abs(it.chapterPos - ReadBook.durChapterPos) }
                ?: return@withLock null
            bookmarkRepository.delete(nearest)
            ReaderEngineImpl.relayout()
            false
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppLog.put("eink togglePageBookmark failed: ${e.message}", e)
    null
}
```

KDoc：方法上方注释把「页文本为标题」的来源改为「显示字段取模块载荷 [content]（存储规范化：占位符剥离 + trim）；同页判定与最近删除按宿主分页事实」。import 区补 `io.legado.app.eink.contract.ReaderPageBookmarkContent`。

(f) 快照映射器填段落边界（Task 1b 契约字段的宿主义务）：

`ReaderPageSnapshotMapper.kt`：为每个映射出的 `ReaderPageLine` 填 `paragraphBreaksAfter`——
从排版块结构推导：同一文本块的折行续行间 0；块末行为 1；其后每隔一个空行/占位块
（不产生 ReaderPageLine 的 Spacer 等）累加 1（空行分隔 = 2）。末行填实际边界数即可
（模块拼装不消费末行值，但填真值保持快照语义完整）。先读
`ReaderPaginator`（`pageTexts` 的 `append('\n')` 处，约 548/559/921/966 行）与
`ReaderPaginatorTest`（:72 `甲\n\n乙`、:133 三行同段无 `\n`）确认块结构与分隔口径，
再实现推导。

- [ ] **Step 2.2b: 同构对照门禁（先红后绿）**

`ReaderPageSnapshotMapperTest.kt` 追加：用 `ReaderPaginatorTest` 同款 fixture（含同段
折行 + 空行分隔 + 标题行）排出一页，`ReaderPageSnapshotMapper.map` 得快照后断言
模块拼装摘录与宿主 `page.text` 相等（fixture 选不含占位符与图片的文本，即逐字相等；
`toPageBookmarkContent` 为模块 internal 扩展，宿主测试不可见——改为对快照行手动按
`paragraphBreaksAfter` 拼出期望串再与 `page.text` 比对，等价锁定 mapper 填值与宿主
口径一致）。fixture 需覆盖：同段折行（无 `\n`）、相邻段落（单 `\n`）、空行分隔
（`\n\n`）、标题行。

- [ ] **Step 2.3: 跑宿主测试与编译（绿）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader && ./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderSelectionEngineImplTest" --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"
./gradlew.bat :app:compileAppDebugKotlin
```

预期：测试全 PASS（ReaderSelectionEngineImplTest 原 12 + 新增 2；ReaderPageSnapshotMapperTest 全部含同构门禁）；编译通过（含 `:modules:eink`）。

- [ ] **Step 2.4: 提交（主仓，不含子模块指针）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader
git add app/src/main/java/io/legado/app/eink/bridge/ReaderSelectionEngineImpl.kt app/src/test/java/io/legado/app/eink/bridge/ReaderSelectionEngineImplTest.kt
git commit -m "refactor(eink): 宿主适配 ReaderSelectionEngine 契约 v2——createMarking/updateMarkingNote(按id不重定位)/书签显示载荷落库"
```

---

### Task 3: eink-lib 仓——版本列车注释与端口总表

**Files:**
- Modify: `modules/eink/build.gradle.kts`（0.7.0 版本注释块）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/README.md`（端口总表第 81 行）

- [ ] **Step 3.1: 0.7.0 版本注释追加本轮条目**

`build.gradle.kts` 的 `// 0.7.0 = 契约清理轮（待发布）：` 条目列表末尾（`compileSdk 35＝依赖集 AAR 元数据地板` 段之后、孪生坐标注释之前）追加：

```kotlin
//         ReaderSelectionEngine 契约 v2（状态机进签名）：
//         saveMarking 拆为 createMarking（仅新选区）+ updateMarkingNote
//         (id, note)（唯一状态转换路径，锚点不变，结构性杜绝跨宿主把
//         划线⇄想法转换实现成重复添加）；thought 字段删除（类型恒由
//         note 派生）；togglePageBookmark 增 ReaderPageBookmarkContent
//         载荷（书签显示字段由模块携带，宿主不自定显示语义）；
```

注意：`version = "0.6.1"` **不翻**——0.7.0 车列车齐发时统一翻。

- [ ] **Step 3.2: 端口总表条目补状态机语义**

`contract/README.md` 第 81 行替换为：

```markdown
| `ReaderSelectionEngine` | 阅读内选区批注与页面书签（可选） | 注册即默认两能力齐备；`supportsMarkings`/`supportsPageBookmark` = false 时对应入口隐藏；契约 v2：笔记转换按 id 走 `updateMarkingNote`（不可重复添加），书签显示载荷由模块携带 |
```

- [ ] **Step 3.3: 提交（eink-lib 仓）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader/eink-lib
git add modules/eink/build.gradle.kts modules/eink/src/main/java/io/legado/app/eink/contract/README.md
git commit -m "docs(eink): 0.7.0 版本注释与端口总表补 ReaderSelectionEngine 契约 v2 条目"
git diff --check
```

---

### Task 4: 主仓——决策回填、指针推进与全量验证

**Files:**
- Modify: `docs/dev/eink-selection-bookmark-marking-design.md`（决策记录追加）
- Modify: git 子模块指针 `eink-lib`

- [ ] **Step 4.1: 旧设计文档决策记录追加**

`docs/dev/eink-selection-bookmark-marking-design.md` 的「决策记录」小节末尾（紧邻的下一个 `##` 标题之前）追加最后一条：

```markdown
- 2026-09-20（用户确认，**端口契约 v2**，交互不变）：`ReaderSelectionEngine`
  契约重构——saveMarking 拆 createMarking / updateMarkingNote(id, note)（转换按
  id、锚点不变，不再重定位）；thought 字段删除，类型恒由 note 派生；
  togglePageBookmark 增显示载荷（章节名 + 页文本由模块携带）。唯一边界行为
  变化：部分重叠选区的「想法」不再另落重叠记录，转为按 id 更新被交叠标记。
  详见 [eink-selection-port-contract-design.md](./eink-selection-port-contract-design.md)。
```

- [ ] **Step 4.2: 主仓提交（文档 + 指针）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader
git add docs/dev/eink-selection-bookmark-marking-design.md eink-lib
git commit -m "build(eink): 推进 eink-lib 指针至契约 v2 并回填旧设计决策记录"
```

- [ ] **Step 4.3: 全量验证**

```bash
cd /d/Projects/AndroidProjects/EssentialReader
./gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture --continue --no-configuration-cache
git diff --check
cd eink-lib && git diff --check && git status --short   # 期望干净
```

预期：全绿；两仓工作区干净（`.zcode/` 为主仓既存未跟踪目录，不计）。

- [ ] **Step 4.4: 交付说明必列的未验证项**

- 真机上 eink 创建的书签 `bookText` 与完整模式创建的逐字一致性（占位符清理口径）——人工比对一次。
- 真机回归：划线 → 写想法 → 清空想法 → 划线 全程一条记录、实/虚线随重排正确切换；点按标记浮条三键行为不变。

---

## 计划自审记录

- **Spec 覆盖**：契约形状（Task 1.4）、三不变量（1.4/1.6/1.7/2.2）、部分重叠行为变化（1.7(c) 按 id 更新即实现）、宿主测试三件（2.1）、双仓提交与指针（1.11/2.4/3.3/4.2）、文档同步（3.1/3.2/4.1）、版本列车不翻号（3.1）、行为基线验证（4.3/4.4）——设计文档各节均有对应任务。
- **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码。
- **类型一致性**：`createMarking(ReaderSelectionCommit): Boolean`、`updateMarkingNote(String, String): Boolean?`、`togglePageBookmark(ReaderPageBookmarkContent): Boolean?`、`withEinkNote(String, Long): BookMarking`、`bookmarkDisplayText(String): String`、`toPageBookmarkContent(): ReaderPageBookmarkContent` 各任务间签名一致。
