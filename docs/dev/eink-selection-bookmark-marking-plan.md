# eink 长按选择：书签与笔记 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 eink 模块阅读页实现「长按选择文本 → 书签 / 笔记 / 复制」创建闭环，数据经宿主端口落库与宿主模式同库互认。

**Architecture:** 快照携带正文空间位置桥（每行章内偏移 + 行盒 + 装饰 run），选择交互完全在模块本地（命中测试、BreakIterator 选词、把手拖拽），宿主只提供可选端口 `ReaderSelectionEngine`（选区解析 + 两张表落库 + 保存笔记后重排推送）。

**Tech Stack:** Kotlin、Jetpack Compose（模块 UI）、`java.text.BreakIterator`、Room 落库走既有 `BookmarkRepository` / `SaveMarkingUseCase`、JUnit4 纯 JVM 测试（模块无 Robolectric）。

**规格：** `docs/dev/eink-selection-bookmark-marking-design.md`（本计划对其有两处精化，见 Task 11 文档回填）。

## Global Constraints

- 开发与 CI 使用 JDK 21；命令在仓库根以 PowerShell 执行（`.\gradlew.bat ...`）。
- 模块单测：`:modules:eink:testDebugUnitTest`（纯 JVM，无 Robolectric——Android 类不可在断言中构造，逻辑一律提为顶层纯函数注入 lambda）。
- 宿主 bridge 单测跑 `testAppDebugUnitTest`；快速编译 `:app:compileAppDebugKotlin`。
- 位置口径：**UTF-16 索引、语义正文空间**（与 `TextProcessAnchor.chapterPosition` 同一坐标系）；标题行位置是独立标题空间、无正文语义。
- 装饰颜色不跨桥：模块按 `EInkTheme.colorScheme` 自涂（线黑、高亮灰底）；eink 创建的笔记固定实线（`underlineMode=1`），无样式配置。
- eink 契约注释按既有纪律：全成员义务注释、宿主实现者视角；新增端口为**可选端口**（同 `appUpdateEngine` 先例），缺失时模块降级隐藏书签/笔记菜单项。
- 所有文本改动至少运行 `git diff --check`。
- 提交信息用仓库惯例：`feat(eink): ...` / `test(eink): ...` 中文描述。

## 文件总览

```text
modules/eink/src/main/java/io/legado/app/eink/
  contract/ReaderPageSnapshot.kt            [改] ReaderPageLine 扩展 + ReaderDecorationRun
  contract/ReaderSelectionEngine.kt         [新] 可选端口 + Draft/Commit 类型
  contract/EInkEngineRegistry.kt            [改] selectionEngine 可选端口
  feature/reader/selection/ReaderTextSelection.kt      [新] 选择纯逻辑（命中/选词/区间/几何）
  feature/reader/selection/ReaderDecorationSpan.kt     [新] 装饰 run → x 区间纯函数
  feature/reader/selection/ReaderSelectionOverlay.kt   [新] 选区/把手绘制 + 浮条菜单
  feature/reader/selection/ReaderSelectionSheets.kt    [新] 书签/笔记编辑弹层
  feature/reader/ReaderScreen.kt            [改] 手势 + 接线
  feature/reader/ReaderViewModel.kt         [改] resolve/save 方法 + 端口可用性
  feature/reader/ReaderPageSnapshotCanvas.kt [改] 装饰绘制 + applySpec 提为 internal
  docs/eink-porting.md                      [改] 端口清单
  src/test/.../selection/ReaderTextSelectionTest.kt    [新]
  src/test/.../selection/ReaderDecorationSpanTest.kt   [新]

app/src/main/java/io/legado/app/eink/bridge/
  ReaderPageSnapshotMapper.kt               [改] 位置桥 + 装饰提取
  ReaderSelectionEngineImpl.kt              [新] 端口实现（落库 + 重排推送）
  EInkBridge.kt                             [改] install selectionEngine
app/src/test/java/io/legado/app/eink/bridge/
  ReaderPageSnapshotMapperTest.kt           [改] 位置/装饰断言
  ReaderSelectionEngineImplTest.kt          [新] 定位/上下文/草稿纯函数测试
```

---

## 切片一：契约与位置桥（纯数据，不改 UI 行为）

### Task 1: 快照位置桥——契约扩展 + 映射器适配

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderPageSnapshot.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt`

**Interfaces:**
- Produces: `ReaderPageLine(chapterPositions: IntArray, top: Float, bottom: Float, decorations: List<ReaderDecorationRun>)`（Task 4/5/6/10 消费）；`ReaderDecorationRun(start: Int, end: Int, underlineMode: Int, highlight: Boolean)`（本任务置空列表，Task 2 填充）。

- [ ] **Step 1: 在契约文件追加 ReaderDecorationRun 并扩展 ReaderPageLine**

`modules/eink/.../contract/ReaderPageSnapshot.kt` 中，把 `ReaderPageLine`（54-66 行）整体替换为：

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
     * 各文本段首字符的章内字符位置（UTF-16 索引）。
     *
     * 坐标系：正文行 = 语义正文空间（与宿主 TextProcessAnchor.chapterPosition
     * 同一坐标系，跨行间隙为段落分隔符/占位字符）；标题行 = 标题空间，
     * 无正文语义——选区含标题行时，章内区间须取选区内首个正文行位置。
     * 行内各段按段长连续，无需额外段内偏移。
     *
     * 宿主实现义务：从排版元素 chapterPosition 原样拷贝，不做换算。
     */
    val chapterPositions: IntArray,

    /**
     * 行盒顶/底边界（px，排版元素 bounds 原样拷贝）。选择命中、高亮带、
     * 把手与菜单锚定的几何依据；模块不得用字体度量估算行高。
     */
    val top: Float,
    val bottom: Float,

    /**
     * 本行划线/高亮装饰（缺省空，与选中态无关、常驻）。区间为行内拼接
     * 文本的 UTF-16 索引；同行相邻同类 run 已在映射侧合并。
     */
    val decorations: List<ReaderDecorationRun> = emptyList(),
)
```

同文件末尾追加：

```kotlin
/**
 * 行内一段用户划线/高亮装饰。颜色不跨桥：模块按主题自涂
 * （下划线=主题前景黑，高亮=主题灰底）。
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

    /** true = 背景高亮带。 */
    val highlight: Boolean,
)
```

- [ ] **Step 2: 映射器最小适配（先让仓库可编译，装饰置空）**

`app/.../bridge/ReaderPageSnapshotMapper.kt`：`LineBuffer`（129-148 行）替换为：

```kotlin
    /** 行内累积中的文本段；flush 时按「行 → 段」结构落盘。 */
    private class LineBuffer {
        val chunks = ArrayList<String>()
        val xs = ArrayList<Float>()
        val chapterPositions = ArrayList<Int>()
        var top = 0f
        var bottom = 0f
        var baseY = 0f
        var isTitle = false

        fun flushInto(lines: MutableList<ReaderPageLine>) {
            if (chunks.isEmpty()) return
            lines.add(
                ReaderPageLine(
                    baseY = baseY,
                    isTitle = isTitle,
                    chunks = chunks,
                    x = xs.toFloatArray(),
                    chapterPositions = chapterPositions.toIntArray(),
                    top = top,
                    bottom = bottom,
                    decorations = emptyList(), // Task 2 接入装饰提取
                )
            )
        }
    }
```

`mapWithSpecs` 的 Text 分支（构造 line 处与追加 chunk 处）同步修改：

```kotlin
                is ReaderElement.Text -> {
                    var line = buffer
                    // 同一视觉行的元素共享行顶 y（分页器逐行使用同一 y 值）
                    if (line != null && element.bounds.top != line.top) {
                        line.flushInto(lines)
                        line = null
                    }
                    if (line == null) {
                        line = LineBuffer().also {
                            it.top = element.bounds.top
                            it.bottom = element.bounds.bottom
                            it.baseY = element.baselinePx
                            it.isTitle = element.emphasized
                        }
                        buffer = line
                    } else if (element.bounds.bottom > line.bottom) {
                        line.bottom = element.bounds.bottom
                    }
                    val spec = if (element.emphasized) titleSpec else contentSpec
                    // API 35+ drawText 会将 letterSpacing 应用在两侧，View 版同样补偿半格
                    val halfSpacing =
                        if (sdkInt >= 35) spec.letterSpacing * spec.textSizePx * 0.5f else 0f
                    line.chunks.add(element.value)
                    line.xs.add(element.bounds.left + halfSpacing)
                    line.chapterPositions.add(element.chapterPosition)
                }
```

- [ ] **Step 3: 扩展映射器单测——位置桥断言**

在 `ReaderPageSnapshotMapperTest.kt` 追加（沿用该文件既有的 page/spec 构造 helper；若 helper 名不同以文件内现名为准）：

```kotlin
    @Test
    fun `行携带元素章内位置与行盒`() {
        val page = pageOfElements(
            text(value = "第一段第一行", chapterPosition = 0, top = 10f, bottom = 30f, baseline = 28f),
            text(value = "第一段第二行", chapterPosition = 7, top = 34f, bottom = 54f, baseline = 52f),
        )
        val snapshot = mapWithSpecs(page, testTitleSpec, testContentSpec, sdkInt = 34,
            sessionBook = null, readProgress = "1/1", imageLoader = fakeImageLoader)

        val line0 = snapshot.lines[0]
        assertEquals(intArrayOf(0), line0.chapterPositions)
        assertEquals(10f, line0.top)
        assertEquals(30f, line0.bottom)
        val line1 = snapshot.lines[1]
        assertEquals(intArrayOf(7), line1.chapterPositions)
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"`
Expected: PASS（新用例通过；既有用例因构造 ReaderPageLine 未传新参数会编译失败——同步修既有用例的构造调用，补 `chapterPositions = intArrayOf(...)`、`top`/`bottom` 字面量）

再跑模块侧编译确认契约改动无遗漏消费点：
Run: `.\gradlew.bat :modules:eink:compileDebugKotlin :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/contract/ReaderPageSnapshot.kt \
  app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt \
  app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt
git commit -m "feat(eink): 快照行携带章内位置与行盒，为长按选择提供位置桥"
```

### Task 2: 映射器装饰提取（划线/高亮 → 行内 run）

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt`

**Interfaces:**
- Consumes: `ReaderElement.Text.style: ReaderTextStyle`（`underline: ReaderUnderline?`、`backgroundArgb: Int?`）、`ReaderUnderline.mode: Int`（`feature/reader/core/model/ReaderPage.kt:12-19,64`）。
- Produces: `ReaderPageLine.decorations`（合并后的行内 run；TEXT 字体色标记不产生 run）。

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun `同行相邻同款划线合并为单个装饰 run`() {
        val page = pageOfElements(
            text(value = "划线甲", chapterPosition = 0, top = 10f, bottom = 30f, baseline = 28f,
                underlineMode = 1, highlight = false),
            text(value = "划线乙", chapterPosition = 3, top = 10f, bottom = 30f, baseline = 28f,
                underlineMode = 1, highlight = false),
            text(value = "无样式", chapterPosition = 6, top = 10f, bottom = 30f, baseline = 28f),
            text(value = "高亮丙", chapterPosition = 9, top = 10f, bottom = 30f, baseline = 28f,
                underlineMode = 0, highlight = true),
        )
        val snapshot = mapWithSpecs(page, testTitleSpec, testContentSpec, sdkInt = 34,
            sessionBook = null, readProgress = "1/1", imageLoader = fakeImageLoader)

        val runs = snapshot.lines.single().decorations
        assertEquals(2, runs.size)
        assertEquals(ReaderDecorationRun(0, 6, underlineMode = 1, highlight = false), runs[0])
        assertEquals(ReaderDecorationRun(9, 12, underlineMode = 0, highlight = true), runs[1])
    }

    @Test
    fun `字体色标记不产生装饰 run`() {
        val page = pageOfElements(
            text(value = "仅变色", chapterPosition = 0, top = 10f, bottom = 30f, baseline = 28f),
        )
        val snapshot = mapWithSpecs(page, testTitleSpec, testContentSpec, sdkInt = 34,
            sessionBook = null, readProgress = "1/1", imageLoader = fakeImageLoader)
        assertTrue(snapshot.lines.single().decorations.isEmpty())
    }
```

（`text(...)` helper 需按 Task 1 既有 helper 扩展可选参数 `underlineMode: Int = 0, highlight: Boolean = false`，映射到元素的 `style = ReaderTextStyle(..., backgroundArgb = if (highlight) 0x80FFFFFF else null, underline = mode.takeIf { it != 0 }?.let { ReaderUnderline(mode = it, colorArgb = 0xFF000000, widthPx = 1f, offsetPx = 2f) } ... )`——字段名以 `ReaderPage.kt:12-19,64` 实际签名为准。）

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"`
Expected: FAIL（decorations 恒为空）

- [ ] **Step 3: 实现提取与合并**

`LineBuffer` 增加逐段样式签名，`flushInto` 内合并：

```kotlin
    private class LineBuffer {
        // ... 既有字段 ...
        val underlineModes = ArrayList<Int>()
        val highlights = ArrayList<Boolean>()

        /** flush 时把相邻同签名段合并为行内装饰 run。 */
        private fun buildDecorations(): List<ReaderDecorationRun> {
            val runs = ArrayList<ReaderDecorationRun>()
            var runStart = -1
            var runMode = 0
            var runHighlight = false
            var offset = 0
            for (i in chunks.indices) {
                val mode = underlineModes[i]
                val highlight = highlights[i]
                val same = runStart >= 0 && mode == runMode && highlight == runHighlight
                val length = chunks[i].length
                if (!same) {
                    if (runStart >= 0 && (runMode != 0 || runHighlight)) {
                        runs += ReaderDecorationRun(runStart, offset, runMode, runHighlight)
                    }
                    runStart = offset
                    runMode = mode
                    runHighlight = highlight
                }
                offset += length
            }
            if (runStart >= 0 && (runMode != 0 || runHighlight)) {
                runs += ReaderDecorationRun(runStart, offset, runMode, runHighlight)
            }
            return runs
        }

        fun flushInto(lines: MutableList<ReaderPageLine>) {
            if (chunks.isEmpty()) return
            lines.add(
                ReaderPageLine(
                    // ... 既有字段 ...
                    decorations = buildDecorations(),
                )
            )
        }
    }
```

Text 分支追加两行（与 `line.chapterPositions.add(...)` 并列）：

```kotlin
                    line.underlineModes.add(element.style.underline?.mode ?: 0)
                    line.highlights.add(element.style.backgroundArgb != null)
```

- [ ] **Step 4: 运行测试通过**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt \
  app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt
git commit -m "feat(eink): 映射器提取划线/高亮装饰为行内 run，字体色标记不跨桥"
```

### Task 3: ReaderSelectionEngine 可选端口（契约 + 注册表）

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderSelectionEngine.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt`
- Modify: `modules/eink/docs/eink-porting.md`
- Test: `modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt`

**Interfaces:**
- Produces: `ReaderSelectionEngine`（Task 7 实现、Task 8/10 消费）、`ReaderSelectionDraft`、`ReaderSelectionCommit`、`EInkEngineRegistry.selectionEngine: ReaderSelectionEngine?`。

- [ ] **Step 1: 写失败测试（注册表可空访问）**

`EInkEngineRegistryTest.kt` 追加：

```kotlin
    @Test
    fun `selectionEngine 为可选端口未注册返回 null`() {
        assertNull(EInkEngineRegistry.selectionEngine)
    }
```

（若该测试类以 `@FixMethodOrder` 顺序依赖 install 状态，把用例放在首字母序靠前位置或独立还原注册表，参照文件内既有做法。）

- [ ] **Step 2: 运行确认编译失败**

Run: `.\gradlew.bat :modules:eink:compileDebugUnitTestKotlin`
Expected: FAIL（`selectionEngine` 未定义）

- [ ] **Step 3: 新建契约文件**

`modules/eink/.../contract/ReaderSelectionEngine.kt`：

```kotlin
package io.legado.app.eink.contract

import androidx.compose.runtime.Stable

/**
 * 选区语义与批注落库端口：宿主把模块选区解析为宿主语义字段（书签
 * chapterPos、锚点上下文与哈希）并写 bookmarks / book_marks 两表。
 * 模块不复制这些规则，只提交正文空间区间与选中文本。
 *
 * 可选端口（同 [EInkEngineRegistry.appUpdateEngine] 先例）：未注册 =
 * 宿主无批注能力（companion 宿主的合法状态），模块隐藏书签/笔记菜单项，
 * 长按选择与复制仍可用，不做假死路径。
 */
interface ReaderSelectionEngine {

    /**
     * 选区解析：按章节全文构造编辑弹层预填内容。
     *
     * @param chapterIndex 选区所在章节下标。
     * @param start 正文空间起始提示（选区含标题行时为选区内首个正文行位置；
     *   纯标题选区为 0）。
     * @param end 正文空间结束提示（语义同上；纯标题选区为 0）。
     * @param selectedText 选中文本（按行拼接、段落间隙以换行连接）。
     *   宿主在保存时以此在章节全文中定位（提示位置附近窗口搜索）。
     * @return null = 无会话书或选中文本为空（模块按选区失效处理并清选区）。
     */
    suspend fun resolveSelection(
        chapterIndex: Int,
        start: Int,
        end: Int,
        selectedText: String,
    ): ReaderSelectionDraft?

    /**
     * 保存书签（[ReaderSelectionCommit.bookmarkText]/[ReaderSelectionCommit.bookmarkContent]
     * 为用户编辑后的值）。宿主落库 bookmarks 表。
     * false = 落库失败，模块提示并保留弹层。
     */
    suspend fun saveBookmark(commit: ReaderSelectionCommit): Boolean

    /**
     * 保存笔记。样式固定实线（宿主桥写入 underlineMode=1、颜色取宿主
     * 划线回退默认 0xFF63C37D），note 为用户备注（可空串）。
     * 宿主落库 book_marks 后自行触发当前章重排（保持页内位置），新快照经
     * onContentUpdated 推送——模块不请求刷新。false = 落库失败。
     */
    suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean
}

/** 选区解析结果：两个编辑弹层的预填初值。 */
@Stable
class ReaderSelectionDraft(
    /** 选中文本（笔记预览展示；与 [bookmarkText] 同源，独立成字段以便宿主规则分化）。 */
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

    /** 章内字符区间 [start, end)（UTF-16、语义正文空间；纯标题选区为 0..0）。 */
    val start: Int,
    val end: Int,

    /** 选中文本（宿主用于定位与锚点构造）。 */
    val selectedText: String,

    /** 用户编辑后的书签 bookText。 */
    val bookmarkText: String,

    /** 用户编辑后的书签 content。 */
    val bookmarkContent: String,

    /** 用户输入的笔记备注（可空串）。 */
    val note: String,
)
```

- [ ] **Step 4: 注册表加可选端口**

`EInkEngineRegistry.kt`：在 `_appUpdateEngine`（54 行）后加字段，在 `appUpdateEngine` getter（91-97 行）后加 getter，在 `install` 参数表（`appUpdateEngine` 之后）加参数：

```kotlin
    private var _selectionEngine: ReaderSelectionEngine? = null
```

```kotlin
    /**
     * 选区批注端口——**可选**端口：未注册 = 宿主无批注落库能力，
     * 阅读页选择菜单隐藏书签/笔记项（长按选择与复制保留），
     * 不参与 install 必填校验。
     */
    val selectionEngine: ReaderSelectionEngine?
        get() = _selectionEngine
```

```kotlin
        // install 签名末尾追加：
        appUpdateEngine: AppUpdateEngine? = null,
        selectionEngine: ReaderSelectionEngine? = null,
```

（install 函数体内把 `_selectionEngine = selectionEngine` 与既有赋值并列。）

- [ ] **Step 5: 更新端口清单文档**

`modules/eink/docs/eink-porting.md` 端口表加一行：`ReaderSelectionEngine | 可选 | 阅读页长按选择的书签/笔记落库；未注册时选择菜单降级为仅复制`。

- [ ] **Step 6: 运行测试与编译**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest`
Expected: PASS（含新用例）

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL（EInkBridge 未传新参数——默认 null 合法）

- [ ] **Step 7: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/contract/ \
  modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt \
  modules/eink/docs/eink-porting.md
git commit -m "feat(eink): 新增 ReaderSelectionEngine 可选端口契约（选区解析+批注落库）"
```

---

## 切片二：模块选择系统 + 复制

### Task 4: 选择纯逻辑——命中测试与区间构建

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderTextSelection.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/ReaderTextSelectionTest.kt`

**Interfaces:**
- Consumes: `ReaderPageSnapshot` / `ReaderPageLine`（Task 1）。
- Produces（Task 5/6/8 消费）：`ReaderTextHit(lineIndex, charIndex)`、`ReaderSelectionUi(bodyStart, bodyEnd, selectedText, includesTitle, startHit, endHit)`、`hitTest(...)`、`buildSelection(...)`、`normalizeHits(a, b)`。测量统一注入 `measure: (String) -> Float`（生产侧传 `paint::measureText`，测试传假等宽函数）。

- [ ] **Step 1: 写失败测试**

```kotlin
package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假等宽测量：每字符 10px，便于手算。 */
private val measure: (String) -> Float = { it.length * 10f }

private fun line(
    vararg chunks: String,
    positions: IntArray,
    baseY: Float = 50f,
    top: Float = 30f,
    bottom: Float = 70f,
    isTitle: Boolean = false,
): ReaderPageLine = ReaderPageLine(
    baseY = baseY, isTitle = isTitle, chunks = chunks.toList(),
    x = FloatArray(chunks.size) { i -> i * 100f },
    chapterPositions = positions, top = top, bottom = bottom,
)

private fun snapshot(vararg lines: ReaderPageLine) = ReaderPageSnapshot(
    title = "章", readProgress = "1/1",
    titleSpec = ReaderPaintSpec(20f, 0f, null, null),
    contentSpec = ReaderPaintSpec(20f, 0f, null, null),
    lines = lines.toList(), images = emptyList(),
)

class ReaderTextSelectionTest {

    @Test
    fun `命中测试按行盒定行按前缀宽度定字符`() {
        val snap = snapshot(line("abcdef", positions = intArrayOf(0)))
        // 行内 x=25 = 第 2 字符（span [20,30)）中线 → 命中字符 2
        assertEquals(ReaderTextHit(0, 2), hitTest(snap, x = 25f, y = 50f, measure = measure))
        // 行盒外
        assertNull(hitTest(snap, x = 25f, y = 200f, measure = measure))
    }

    @Test
    fun `命中交换端点后区间规范有序`() {
        val a = ReaderTextHit(0, 5)
        val b = ReaderTextHit(2, 1)
        val (start, end) = normalizeHits(a, b)
        assertEquals(ReaderTextHit(0, 5), start)
        assertEquals(ReaderTextHit(2, 1), end)
    }

    @Test
    fun `区间构建跨行拼接文本并计入正文间隙`() {
        val snap = snapshot(
            line("第一段落", positions = intArrayOf(0)),
            line("续行", positions = intArrayOf(4)),      // 软换行：gap=0
            line("新段落", positions = intArrayOf(7)),      // 段落间隙：gap=1（\n）
        )
        val sel = buildSelection(
            snap, ReaderTextHit(0, 2), ReaderTextHit(2, 1),
        )!!
        assertEquals("段落续行\n新", sel.selectedText)
        assertEquals(2, sel.bodyStart)
        assertEquals(8, sel.bodyEnd)
        assertFalse(sel.includesTitle)
    }

    @Test
    fun `含标题行选区标记 includesTitle 且正文区间取正文行`() {
        val snap = snapshot(
            line("标题", positions = intArrayOf(100), isTitle = true),
            line("正文内容", positions = intArrayOf(0)),
        )
        val sel = buildSelection(
            snap, ReaderTextHit(0, 0), ReaderTextHit(1, 2),
        )!!
        assertTrue(sel.includesTitle)
        assertEquals(0, sel.bodyStart)
        assertEquals(2, sel.bodyEnd)
        assertEquals("标题\n正文", sel.selectedText)
    }

    @Test
    fun `纯标题选区正文区间退化为零`() {
        val snap = snapshot(line("标题", positions = intArrayOf(100), isTitle = true))
        val sel = buildSelection(snap, ReaderTextHit(0, 0), ReaderTextHit(0, 2))!!
        assertTrue(sel.includesTitle)
        assertEquals(0, sel.bodyStart)
        assertEquals(0, sel.bodyEnd)
        assertEquals("标题", sel.selectedText)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.selection.ReaderTextSelectionTest"`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现纯逻辑**

`modules/eink/.../selection/ReaderTextSelection.kt`：

```kotlin
package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot

/** 行内命中：行下标 + 行内拼接文本的字符偏移（UTF-16）。 */
data class ReaderTextHit(val lineIndex: Int, val charIndex: Int)

/**
 * 页内选区（Screen 本地 UI 状态）。正文区间为语义正文空间提示值：
 * 含标题行时取选区内正文行子区间（纯标题退化为 0..0，由宿主窗口搜索兜底）。
 */
data class ReaderSelectionUi(
    val startHit: ReaderTextHit,
    val endHit: ReaderTextHit,
    val selectedText: String,
    val bodyStart: Int,
    val bodyEnd: Int,
    val includesTitle: Boolean,
)

/** 行内拼接文本。 */
internal fun lineText(line: ReaderPageLine): String = line.chunks.joinToString("")

/** 行内字符偏移所在段下标；返回 chunk 下标到段内偏移。 */
internal fun locateChunk(line: ReaderPageLine, charIndex: Int): Pair<Int, Int> {
    var remaining = charIndex
    for (i in line.chunks.indices) {
        val length = line.chunks[i].length
        if (remaining <= length) return i to remaining
        remaining -= length
    }
    val last = line.chunks.lastIndex
    return last to line.chunks[last].length
}

/** 段内字符偏移对应的章内位置（段长连续，直接累加）。 */
internal fun chapterPositionOf(line: ReaderPageLine, charIndex: Int): Int {
    val (chunk, offsetInChunk) = locateChunk(line, charIndex)
    return line.chapterPositions[chunk] + offsetInChunk
}

/** 命中测试：y 按行盒定行（含容差半行高），x 按前缀宽度定最近字符。 */
fun hitTest(
    snapshot: ReaderPageSnapshot,
    x: Float,
    y: Float,
    measure: (String) -> Float,
): ReaderTextHit? {
    if (snapshot.lines.isEmpty()) return null
    // 定行：取行盒中心距 y 最近的行，距离超过半行高视为未命中
    var best = -1
    var bestDistance = Float.MAX_VALUE
    snapshot.lines.forEachIndexed { index, line ->
        val center = (line.top + line.bottom) / 2f
        val distance = kotlin.math.abs(y - center)
        if (distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    val line = snapshot.lines[best]
    val halfHeight = (line.bottom - line.top) / 2f
    if (bestDistance > halfHeight) return null
    return ReaderTextHit(best, hitCharInLine(line, x, measure))
}

/** 行内命中字符：逐段判右缘，段内按字符中线求 x 落点，钳制到 [0, 行长]。 */
internal fun hitCharInLine(line: ReaderPageLine, x: Float, measure: (String) -> Float): Int {
    var offset = 0
    for (i in line.chunks.indices) {
        val chunk = line.chunks[i]
        val chunkLeft = line.x[i]
        val chunkWidth = measure(chunk)
        if (x > chunkLeft + chunkWidth && i != line.chunks.lastIndex) {
            offset += chunk.length
            continue
        }
        var acc = 0f
        for (j in chunk.indices) {
            val charWidth = measure(chunk[j].toString())
            if (x <= chunkLeft + acc + charWidth / 2f) return offset + j
            acc += charWidth
        }
        return offset + chunk.length
    }
    return offset
}

/** 端点按（行、字符）升序规范。 */
fun normalizeHits(a: ReaderTextHit, b: ReaderTextHit): Pair<ReaderTextHit, ReaderTextHit> =
    if (a.lineIndex < b.lineIndex || (a.lineIndex == b.lineIndex && a.charIndex <= b.charIndex)) a to b
    else b to a

/**
 * 由两端命中构建选区：跨行拼接文本（正文间隙 >0 时补一个换行，
 * 对应语义正文空间的段落分隔符/占位字符），正文区间只统计正文行。
 */
fun buildSelection(
    snapshot: ReaderPageSnapshot,
    hitA: ReaderTextHit,
    hitB: ReaderTextHit,
): ReaderSelectionUi? {
    val (start, end) = normalizeHits(hitA, hitB)
    if (start.lineIndex !in snapshot.lines.indices ||
        end.lineIndex !in snapshot.lines.indices
    ) return null
    val text = StringBuilder()
    var bodyStart = 0
    var bodyEnd = 0
    var bodySeen = false
    var includesTitle = false
    for (index in start.lineIndex..end.lineIndex) {
        val line = snapshot.lines[index]
        if (line.isTitle) includesTitle = true
        val from = if (index == start.lineIndex) start.charIndex else 0
        val to = if (index == end.lineIndex) end.charIndex else lineText(line).length
        val piece = lineText(line).substring(from, to)
        if (index > start.lineIndex) {
            val prev = snapshot.lines[index - 1]
            if (prev.isTitle || line.isTitle) {
                // 标题/正文异空间：按段落分隔呈现
                text.append('\n')
            } else {
                // 正文行之间：软换行（gap=0）不补，正文间隙（段落/占位字符）补一个换行
                val prevEnd = chapterPositionOf(prev, lineText(prev).length)
                val curStart = line.chapterPositions.first()
                if (curStart - prevEnd > 0) text.append('\n')
            }
        }
        text.append(piece)
        if (!line.isTitle) {
            val pieceStart = chapterPositionOf(line, from)
            val pieceEnd = pieceStart + (to - from)
            if (!bodySeen) {
                bodyStart = pieceStart
                bodySeen = true
            }
            bodyEnd = pieceEnd
        }
    }
    return ReaderSelectionUi(
        startHit = start,
        endHit = end,
        selectedText = text.toString(),
        bodyStart = bodyStart,
        bodyEnd = bodyEnd,
        includesTitle = includesTitle,
    )
}
```

- [ ] **Step 4: 运行测试通过**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.selection.ReaderTextSelectionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderTextSelection.kt \
  modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/ReaderTextSelectionTest.kt
git commit -m "feat(eink): 阅读页选择纯逻辑——命中测试与选区区间构建"
```

### Task 5: 选词吸附与选区几何（把手/高亮带）

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderTextSelection.kt`（追加）
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/ReaderTextSelectionTest.kt`（追加）

**Interfaces:**
- Produces（Task 6 消费）：`snapToWord(snapshot, hit): ReaderTextHit`（BreakIterator 词边界）、`selectionRuns(snapshot, selection, measureTitle, measureContent): List<SelectionRun>`、`SelectionRun(lineIndex, left, right, top, bottom)`、`handleAnchor(runs): Pair<Pair<Float,Float>, Pair<Float,Float>>?`（首把手 left/top 与末把手 right/top）。

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun `选词吸附到词边界`() {
        val snap = snapshot(line("hello world", positions = intArrayOf(0)))
        // 落在 "world" 中间的字符上 → 吸附到词首
        val hit = snapToWord(snap, ReaderTextHit(0, 8))
        assertEquals(ReaderTextHit(0, 6), hit)
    }

    @Test
    fun `选区几何产出逐行高亮带`() {
        val snap = snapshot(
            line("abcdef", positions = intArrayOf(0), top = 30f, bottom = 70f),
            line("ghijkl", positions = intArrayOf(6), top = 80f, bottom = 120f),
        )
        val sel = buildSelection(snap, ReaderTextHit(0, 3), ReaderTextHit(1, 2))!!
        val runs = selectionRuns(snap, sel, measure, measure)
        assertEquals(2, runs.size)
        // 首行从第 3 字符左缘（x[0]=0 + 30）到行尾（0 + 60）
        assertEquals(30f, runs[0].left)
        assertEquals(60f, runs[0].right)
        assertEquals(30f, runs[0].top)
        assertEquals(70f, runs[0].bottom)
        // 次行整行到第 2 字符右缘（单段行 x[0]=0）
        assertEquals(0f, runs[1].left)
        assertEquals(20f, runs[1].right)
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.selection.ReaderTextSelectionTest"`
Expected: FAIL（snapToWord/selectionRuns 未定义）

- [ ] **Step 3: 追加实现**

`ReaderTextSelection.kt` 追加：

```kotlin
import java.text.BreakIterator

/**
 * 长按选词：BreakIterator 词边界吸附（中日文逐字、拉丁按词）。
 * 吸附失败（迭代器异常等）返回原命中。
 */
fun snapToWord(snapshot: ReaderPageSnapshot, hit: ReaderTextHit): ReaderTextHit {
    val line = snapshot.lines.getOrNull(hit.lineIndex) ?: return hit
    val text = lineText(line)
    if (text.isEmpty()) return hit
    val iterator = BreakIterator.getWordInstance()
    iterator.setText(text)
    var wordStart = iterator.first()
    while (wordStart != BreakIterator.DONE) {
        val wordEnd = iterator.next()
        if (wordEnd == BreakIterator.DONE) break
        if (hit.charIndex in wordStart until wordEnd ||
            (hit.charIndex >= text.length && wordEnd == text.length)
        ) {
            return hit.copy(charIndex = wordStart)
        }
        wordStart = wordEnd
    }
    return hit
}

/** 逐行选区高亮带（行盒为高、字符前缀宽为横向）。 */
data class SelectionRun(
    val lineIndex: Int,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

fun selectionRuns(
    snapshot: ReaderPageSnapshot,
    selection: ReaderSelectionUi,
    measureTitle: (String) -> Float,
    measureContent: (String) -> Float,
): List<SelectionRun> {
    val (start, end) = selection.startHit to selection.endHit
    val runs = ArrayList<SelectionRun>()
    for (index in start.lineIndex..end.lineIndex) {
        val line = snapshot.lines.getOrNull(index) ?: continue
        val measure = if (line.isTitle) measureTitle else measureContent
        val text = lineText(line)
        val from = if (index == start.lineIndex) start.charIndex else 0
        val to = if (index == end.lineIndex) end.charIndex else text.length
        val left = charX(line, from, measure)
        val right = charX(line, to, measure)
        runs += SelectionRun(index, left, right, line.top, line.bottom)
    }
    return runs
}

/** 行内字符偏移的 x 坐标（逐段累加前缀宽）。 */
internal fun charX(line: ReaderPageLine, charIndex: Int, measure: (String) -> Float): Float {
    val (chunk, offsetInChunk) = locateChunk(line, charIndex)
    var x = line.x[chunk]
    val text = line.chunks[chunk]
    for (i in 0 until offsetInChunk) x += measure(text[i].toString())
    return x
}

/** 把手锚点：首 run 左上（起始把手）与末 run 右上（末端把手）。 */
fun handleAnchor(runs: List<SelectionRun>): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
    val first = runs.firstOrNull() ?: return null
    val last = runs.lastOrNull() ?: return null
    return (first.left to first.top) to (last.right to last.top)
}
```

- [ ] **Step 4: 运行测试通过**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.selection.ReaderTextSelectionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ \
  modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/
git commit -m "feat(eink): 选词吸附与选区几何（高亮带/把手锚点）"
```

### Task 6: 选择 UI——覆盖层、浮条菜单、手势接线、复制

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderSelectionOverlay.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderPageSnapshotCanvas.kt`（`applySpec` 改 `internal`）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`（仅 `selectionEngine` 可用性暴露，save 方法在 Task 8/10 补）

**Interfaces:**
- Consumes: Task 4/5 全部；`EInkEngineRegistry.selectionEngine`（可用性）；`EInkButton`（designsystem/control/EInkButton.kt:55）。
- Produces: `ReaderSelectionOverlay(...)`、`ReaderSelectionMenuAction { BOOKMARK, MARKING, COPY }`；`ReaderScreen` 新参数（下方列明）；VM `selectionEnabled: Boolean`。

UI 层无 Robolectric 测试，本任务验证 = 模块编译 + 既有测试不回归 + 真机手工（见 Step 6）。

- [ ] **Step 1: applySpec 提为 internal**

`ReaderPageSnapshotCanvas.kt:95` 的 `private fun Paint.applySpec(...)` 改为 `internal fun Paint.applySpec(...)`，供覆盖层复用同一画笔规格逻辑。

- [ ] **Step 2: 新建覆盖层与浮条菜单**

`modules/eink/.../selection/ReaderSelectionOverlay.kt`：

```kotlin
package io.legado.app.eink.feature.reader.selection

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlin.math.roundToInt

/** 选择菜单动作。 */
enum class ReaderSelectionMenuAction { BOOKMARK, MARKING, COPY }

/** 把手热区半径（dp，对齐宿主 28f*density）。 */
internal val SelectionHandleTouchRadiusDp = 28.dp

/**
 * 选区覆盖层：高亮带 + 首末把手 + 浮条菜单。
 * 绘制在页画布之上；空白处点击 = 清选区（菜单按钮自身消费点击）。
 */
@Composable
internal fun ReaderSelectionOverlay(
    snapshot: ReaderPageSnapshot?,
    selection: ReaderSelectionUi?,
    onSelectionChange: (ReaderSelectionUi?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null || selection == null) return
    val themeForeground = EInkTheme.colorScheme.onBackground
    val themeBackground = EInkTheme.colorScheme.background
    val highlightArgb = EInkTheme.colorScheme.surfaceVariant
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 4.dp.toPx() }
    val touchRadiusPx = with(density) { SelectionHandleTouchRadiusDp.toPx() }

    val titlePaint = remember { Paint() }
    val contentPaint = remember { Paint() }
    val measureTitle = remember(snapshot.titleSpec, themeForeground) {
        val paint = Paint()
        { text: String ->
            paint.applySpec(snapshot.titleSpec, themeForeground.toArgb())
            paint.measureText(text)
        }
    }
    // 与画布同规格的测量闭包（applySpec 幂等，重复设置无害）
    val measureContent = remember(snapshot.contentSpec, themeForeground) {
        val paint = Paint()
        { text: String ->
            paint.applySpec(snapshot.contentSpec, themeForeground.toArgb())
            paint.measureText(text)
        }
    }
    val runs = remember(selection, snapshot) {
        selectionRuns(snapshot, selection, measureTitle, measureContent)
    }
    val anchors = remember(runs) { handleAnchor(runs) }

    Canvas(modifier = modifier.pointerInput(selection) {
        // 把手拖拽：按下即命中把手则独占指针；否则交给下层手势（翻页/长按）
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val position = down.position
            val grab = grabHandle(anchors, position, touchRadiusPx) ?: return@awaitEachGesture
            var current = grab
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break
                change.consume()
                val hit = hitTest(snapshot, change.position.x, change.position.y, measureContent)
                    ?: continue
                onSelectionChange(moveEndpoint(snapshot, selection, current, hit))
            }
        }
    }) {
        // 高亮带
        for (run in runs) {
            drawRoundRect(
                color = highlightArgb,
                topLeft = Offset(run.left, run.top),
                size = Size(run.right - run.left, run.bottom - run.top),
                cornerRadius = CornerRadius(4f, 4f),
            )
        }
        // 把手：竖线 + 底部圆（宿主样式）
        if (anchors != null) {
            val (startAnchor, endAnchor) = anchors
            drawHandle(startAnchor, handleRadiusPx, themeForeground)
            drawHandle(endAnchor, handleRadiusPx, themeForeground)
        }
    }
    // 菜单由 ReaderScreen 在本覆盖层上方组合（见 Step 3）
}
```

同文件随代码一并写入（import：`awaitEachGesture`/`awaitFirstDown` 来自 `androidx.compose.foundation.gestures`，`DrawScope` 来自 `androidx.compose.ui.graphics.drawscope`）：

```kotlin
private fun DrawScope.drawHandle(
    anchor: Pair<Float, Float>,
    radius: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    val (x, top) = anchor
    drawLine(color, Offset(x, top), Offset(x, top + radius * 4f), strokeWidth = radius / 2f)
    drawCircle(color, radius = radius, center = Offset(x, top + radius * 4f))
}

/** 命中首/末把手；true = 起始把手，false = 末端把手，null = 未命中。 */
internal fun grabHandle(
    anchors: Pair<Pair<Float, Float>, Pair<Float, Float>>?,
    position: Offset,
    touchRadius: Float,
): Boolean? {
    if (anchors == null) return null
    val (startAnchor, endAnchor) = anchors
    val distanceToStart = Offset(startAnchor.first, startAnchor.second + touchRadius)
        .getDistanceTo(position)
    val distanceToEnd = Offset(endAnchor.first, endAnchor.second + touchRadius)
        .getDistanceTo(position)
    return when {
        distanceToStart <= touchRadius -> true
        distanceToEnd <= touchRadius -> false
        else -> null
    }
}

private fun Offset.getDistanceTo(other: Offset): Float = (this - other).getDistance()

/** 替换选区一端并重建（保持另一端不变）。 */
internal fun moveEndpoint(
    snapshot: ReaderPageSnapshot,
    selection: ReaderSelectionUi,
    isStart: Boolean,
    hit: ReaderTextHit,
): ReaderSelectionUi =
    buildSelection(
        snapshot,
        if (isStart) hit else selection.startHit,
        if (isStart) selection.endHit else hit,
    ) ?: selection
```

覆盖层的最终签名（`onSelectionChange` 经参数传入，拖拽循环内调用 `onSelectionChange(moveEndpoint(snapshot, selection, current, hit))`）：

```kotlin
@Composable
internal fun ReaderSelectionOverlay(
    snapshot: ReaderPageSnapshot?,
    selection: ReaderSelectionUi?,
    onSelectionChange: (ReaderSelectionUi?) -> Unit,
    modifier: Modifier = Modifier,
)
```

（拖拽期间闭包捕获的 `selection` 是按下时的值——每次 `moveEndpoint` 都以 snapshot 重新构建，端点替换语义幂等，重组滞后不产生累积误差。菜单组合上浮后 `ReaderSelectionMenuAction` 的分发在 ReaderScreen 完成，覆盖层不持有菜单。）

- [ ] **Step 3: 浮条菜单**

同文件追加（菜单由 ReaderScreen 组合，锚定选区上方/下方）：

```kotlin
/**
 * 选择浮条：横排动作键，锚在选区上方（放不下取下方），零动画直切。
 * 书签/笔记键按端口可用性显隐（降级后仅复制）。
 */
@Composable
internal fun ReaderSelectionMenu(
    anchorLeft: Float,
    anchorTop: Float,
    anchorRight: Float,
    anchorBottom: Float,
    showBookmark: Boolean,
    showMarking: Boolean,
    onAction: (ReaderSelectionMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val itemWidthDp = 72.dp
    val itemCount = listOf(showBookmark, showMarking, true).count { it }
    val menuWidthPx = with(density) { (itemWidthDp * itemCount).toPx() }
    val menuHeightPx = with(density) { 48.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val canvasWidth = with(density) { /* 由调用方传入父宽，见下 */ 0f }
    // 位置：优先上方；x 跟随选区中心并钳制在画布内
    val x = ((anchorLeft + anchorRight) / 2f - menuWidthPx / 2f)
        .coerceIn(0f, canvasWidth - menuWidthPx)
    val y = if (anchorTop - menuHeightPx - gapPx >= 0f) {
        (anchorTop - menuHeightPx - gapPx).roundToInt()
    } else {
        (anchorBottom + gapPx).roundToInt()
    }
    Row(
        modifier = modifier.offset { IntOffset(x.roundToInt(), y) },
    ) {
        if (showBookmark) {
            EInkButton(text = "书签", onClick = { onAction(ReaderSelectionMenuAction.BOOKMARK) },
                modifier = Modifier.width(itemWidthDp))
        }
        if (showMarking) {
            EInkButton(text = "笔记", onClick = { onAction(ReaderSelectionMenuAction.MARKING) },
                modifier = Modifier.width(itemWidthDp))
        }
        EInkButton(text = "复制", onClick = { onAction(ReaderSelectionMenuAction.COPY) },
            modifier = Modifier.width(itemWidthDp))
    }
}
```

（`canvasWidth` 用调用方传入：最终签名加 `canvasWidth: Float` 参数，ReaderScreen 以画布实测宽传入——`onContentSized` 已有页面尺寸流，或用 `BoxWithConstraints`。实现时取其一，保持菜单不越界即可。）

- [ ] **Step 4: ReaderScreen 接线**

`ReaderScreen.kt` 无状态 `ReaderScreen`（483-505 行）参数表追加：

```kotlin
    selection: ReaderSelectionUi?,
    selectionEnabled: Boolean,
    onSelectionChange: (ReaderSelectionUi?) -> Unit,
    onSelectionMenuAction: (ReaderSelectionMenuAction, ReaderSelectionUi) -> Unit,
```

正文画布 Box（524-580 行区域）改造：

1. **tap 手势**（524-537）加选区分支：

```kotlin
                        detectTapGestures { offset ->
                            if (state.controlsVisible) {
                                onCenterTap()
                                return@detectTapGestures
                            }
                            if (selection != null) {
                                // 选区内点按 = 清选区；选区外 = 清选区 + 常规行为
                                onSelectionChange(null)
                                if (offset.x in width * 0.3f..width * 0.7f) {
                                    onCenterTap()
                                } else {
                                    onNextPage()
                                }
                                return@detectTapGestures
                            }
                            // …既有分区逻辑不变…
                        }
```

2. **长按选词 + 拖拽延伸**：新增 pointerInput（放在 tap 与 pager 之后）：

```kotlin
                    .pointerInput(state.controlsVisible, selection == null) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                if (!state.controlsVisible && selection == null) {
                                    val hit = hitTest(page!!, offset.x, offset.y, measureContent)
                                    if (hit != null) {
                                        val word = snapToWord(page, hit)
                                        onSelectionChange(buildSelection(page, word, word))
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val sel = selection ?: return@detectDragGesturesAfterLongPress
                                val hit = hitTest(page!!, change.position.x, change.position.y, measureContent)
                                    ?: return@detectDragGesturesAfterLongPress
                                onSelectionChange(moveEndpoint(page, sel, isStart = false, hit))
                            },
                            onDragEnd = { /* 选区保留，菜单随 selection 展示 */ },
                            onDragCancel = { },
                        )
                    }
```

（`page`、`measureContent`、`haptics` 在 ReaderScreen 内构造：`page = state.page`；测量闭包同 Step 2 写法；`val haptics = LocalHapticFeedback.current`。）

3. **pager 手势守卫**（538-574）：`pointerInput` key 加 `selection != null`，`onDragEnd` 翻页分支外层加 `if (selection == null)`。

4. **组合覆盖层与菜单**（画布 576-580 之后、同 Box 内）：

```kotlin
                ReaderSelectionOverlay(
                    snapshot = state.page,
                    selection = selection,
                    onSelectionChange = onSelectionChange,
                    modifier = Modifier.matchParentSize(),
                )
                if (selection != null && !state.controlsVisible) {
                    val runs = selectionRuns(state.page!!, selection, measureTitle, measureContent)
                    val anchors = handleAnchor(runs)
                    if (anchors != null) {
                        ReaderSelectionMenu(
                            anchorLeft = anchors.first.first,
                            anchorTop = anchors.first.second,
                            anchorRight = anchors.second.first,
                            anchorBottom = runs.last().bottom,
                            showBookmark = selectionEnabled,
                            showMarking = selectionEnabled && !selection.includesTitle,
                            onAction = { action ->
                                if (action == ReaderSelectionMenuAction.COPY) {
                                    onSelectionChange(null)
                                }
                                onSelectionMenuAction(action, selection)
                            },
                            canvasWidth = canvasWidthPx,
                        )
                    }
                }
```

5. **Route（有状态侧）**：`var selection by remember { mutableStateOf<ReaderSelectionUi?>(null) }`；翻页/重排自动清选区：

```kotlin
    LaunchedEffect(viewModel.uiState.collectAsState().value.pageVersion) {
        selection = null
    }
```

（pageVersion 读取方式以 Route 现有 uiState 收集变量为准。复制动作在 Route 的 `onSelectionMenuAction` 里：

```kotlin
                    ReaderSelectionMenuAction.COPY -> {
                        clipboardManager.setText(AnnotatedString(sel.selectedText))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    else -> Unit // 书签/笔记在 Task 8/10 接管
```

`val clipboardManager = LocalClipboardManager.current`。）

- [ ] **Step 5: VM 暴露端口可用性**

`ReaderViewModel.kt` 追加：

```kotlin
    /** 批注端口可用性（决定选择菜单书签/笔记键显隐）。 */
    val selectionEnabled: Boolean
        get() = EInkEngineRegistry.selectionEngine != null
```

- [ ] **Step 6: 编译 + 既有测试回归**

Run: `.\gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，测试全绿

Run: `git diff --check`
Expected: 无输出

- [ ] **Step 7: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ \
git commit -m "feat(eink): 阅读页长按选择系统——选词/把手/浮条菜单/复制"
```

（本步真机验证顺延到 Task 10 后统一执行；编译与单测是本步的门禁。）

---

## 切片三：书签链路

### Task 7: 宿主端口实现——选区解析与书签落库

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/ReaderSelectionEngineImpl.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderSelectionEngineImplTest.kt`

**Interfaces:**
- Consumes: `ReaderSelectionEngine`（Task 3）；`ReadBook.readerChapterInputWindow.current`（`.chapter.index` / `.source.semanticContent`，参照 `MarkingDelegate.selectionContext` 原文 MarkingDelegate.kt:190-208）；`ReadBook.readerChapterInputWindow.current?.displayTitle`（参照 `ReadBookController.composeSelectionBookmark` :1337-1345）；`BookmarkRepository.save`（BookmarkRepository.kt:38-40）；Koin `by inject()` 模式同 `ReaderEngineImpl`。
- Produces: `EInkBridge.install(selectionEngine = ReaderSelectionEngineImpl)`；顶层纯函数 `locateInContent(content, expectedStart, text): Int`、`extractContext(content, start, length): Pair<String, String>`（Task 9 的 saveMarking 复用）。

- [ ] **Step 1: 写失败测试（纯函数：定位与上下文）**

```kotlin
package io.legado.app.eink.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSelectionEngineImplTest {

    private val content = buildString {
        repeat(3) { paragraph -> append("第${paragraph}段").append("一二三四五六七八九十".repeat(8)).append("\n") }
    }

    @Test
    fun `定位优先提示位置精确命中`() {
        val start = content.indexOf("第1段") + 3
        val text = content.substring(start, start + 10)
        assertEquals(start, locateInContent(content, start, text))
    }

    @Test
    fun `提示位置漂移越界时窗口回搜纠偏`() {
        val actual = content.indexOf("第2段") + 3
        val text = content.substring(actual, actual + 10)
        // 提示位置后漂 300（钳制到文末后从窗口回搜命中）
        assertEquals(actual, locateInContent(content, actual + 300, text))
    }

    @Test
    fun `找不到文本返回 -1`() {
        assertEquals(-1, locateInContent(content, 0, "不存在的文本"))
    }

    @Test
    fun `上下文各取 48 字符并钳制边界`() {
        val start = 0
        val length = 10
        val (before, after) = extractContext(content, start, length)
        assertEquals("", before)
        assertEquals(48, after.length)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderSelectionEngineImplTest"`
Expected: FAIL（函数未定义）

- [ ] **Step 3: 实现端口对象（纯函数顶层、可测；落库挂对象方法）**

`app/.../bridge/ReaderSelectionEngineImpl.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.data.repository.BookmarkRepository
import io.legado.app.data.entities.Bookmark
import io.legado.app.eink.contract.ReaderSelectionCommit
import io.legado.app.eink.contract.ReaderSelectionDraft
import io.legado.app.eink.contract.ReaderSelectionEngine
import io.legado.app.model.ReadBook
import org.koin.core.KoinComponent
import org.koin.core.inject

/** 与 MarkingDelegate 一致的窗口常量。 */
private const val CONTEXT_CHARS = 48
private const val CONTEXT_SEARCH_WINDOW = 256

/**
 * 在章节全文中定位选中文本：优先提示位置精确命中，附近 ±[CONTEXT_SEARCH_WINDOW]
 * 窗口搜索纠偏，找不到返回 -1。算法与 MarkingDelegate.selectionContext 一致。
 */
internal fun locateInContent(content: String, expectedStart: Int, text: String): Int {
    if (text.isEmpty()) return -1
    val clamped = expectedStart.coerceIn(0, content.length)
    content.indexOf(text, clamped).takeIf { it >= 0 && it <= clamped + CONTEXT_SEARCH_WINDOW }
        ?.let { return it }
    val windowStart = (clamped - CONTEXT_SEARCH_WINDOW).coerceAtLeast(0)
    content.indexOf(text, windowStart).takeIf { it >= 0 && it <= clamped + CONTEXT_SEARCH_WINDOW }
        ?.let { return it }
    return -1
}

/** 选区前后各取 [CONTEXT_CHARS] 字符（钳制边界）。 */
internal fun extractContext(content: String, start: Int, length: Int): Pair<String, String> {
    val end = (start + length).coerceAtMost(content.length)
    val before = content.substring((start - CONTEXT_CHARS).coerceAtLeast(0), start)
    val after = content.substring(end, (end + CONTEXT_CHARS).coerceAtMost(content.length))
    return before to after
}

internal object ReaderSelectionEngineImpl : ReaderSelectionEngine, KoinComponent {

    private val bookmarkRepository: BookmarkRepository by inject()

    /** 当前会话章节的语义正文（章节不匹配返回 null）。 */
    private fun semanticContent(chapterIndex: Int): String? =
        ReadBook.readerChapterInputWindow.current
            ?.takeIf { it.chapter.index == chapterIndex }
            ?.source?.semanticContent

    /** 当前窗口标题（书签 chapterName，宿主同款口径）。 */
    private fun displayTitle(): String =
        ReadBook.readerChapterInputWindow.current?.displayTitle.orEmpty()

    override suspend fun resolveSelection(
        chapterIndex: Int,
        start: Int,
        end: Int,
        selectedText: String,
    ): ReaderSelectionDraft? {
        // 预填构造不做正文定位：含标题选区在正文空间本就找不到文本，
        // 属合法输入；定位与失效判定在 saveBookmark/saveMarking 时执行
        ReadBook.book ?: return null
        if (selectedText.isBlank()) return null
        return ReaderSelectionDraft(
            selectedText = selectedText,
            bookmarkText = selectedText,
            bookmarkContent = "",
        )
    }

    override suspend fun saveBookmark(commit: ReaderSelectionCommit): Boolean {
        val book = ReadBook.book ?: return false
        val content = semanticContent(commit.chapterIndex)
        val chapterPos = content
            ?.let { locateInContent(it, commit.start, commit.selectedText) }
            ?.takeIf { it >= 0 }
            ?: commit.start
        val bookmark = Bookmark(
            bookName = book.name,
            bookAuthor = book.author,
            bookUrl = book.bookUrl,
            chapterIndex = commit.chapterIndex,
            chapterPos = chapterPos,
            chapterName = displayTitle(),
            bookText = commit.bookmarkText,
            content = commit.bookmarkContent,
        )
        return try {
            bookmarkRepository.save(bookmark)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean {
        // Task 9 实现：SaveMarkingUseCase + 固定实线样式 + relayout 推送
        throw NotImplementedError("Task 9")
    }
}
```

- [ ] **Step 4: 运行测试通过 + 装配**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderSelectionEngineImplTest"`
Expected: PASS

`EInkBridge.install(...)`（EInkBridge.kt:37-48）追加一行：

```kotlin
            appUpdateEngine = AppUpdateEngineImpl,
            selectionEngine = ReaderSelectionEngineImpl,
```

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/ReaderSelectionEngineImpl.kt \
  app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt \
  app/src/test/java/io/legado/app/eink/bridge/ReaderSelectionEngineImplTest.kt
git commit -m "feat(eink): 宿主桥实现选区解析与书签落库端口"
```

### Task 8: 模块书签弹层与提交链路

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderSelectionSheets.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`（Route 侧接线）

**Interfaces:**
- Consumes: `ReaderSelectionEngine.resolveSelection/saveBookmark`（Task 3/7）；`EInkDialog`（designsystem/control/EInkDialog.kt:50-61，确认弹框形态：onConfirm + content）；`UserMessage` 提示模式（ReaderScreen.kt:131-135 既有 Toast 收集）。
- Produces: VM `suspend fun resolveSelection(sel): ReaderSelectionDraft?`、`suspend fun saveBookmark(sel, bookText, content): Boolean`；`ReaderBookmarkEditDialog`。

- [ ] **Step 1: VM 增加解析与保存方法**

`ReaderViewModel.kt` 追加：

```kotlin
    /** 选区解析：宿主构造弹层预填。null = 选区失效（调用方清选区）。 */
    suspend fun resolveSelection(sel: ReaderSelectionUi): ReaderSelectionDraft? =
        EInkEngineRegistry.selectionEngine?.resolveSelection(
            chapterIndex = engine.currentChapterIndex,
            start = sel.bodyStart,
            end = sel.bodyEnd,
            selectedText = sel.selectedText,
        )

    /** 保存书签（编辑后的标题/内容）。 */
    suspend fun saveBookmark(sel: ReaderSelectionUi, bookText: String, content: String): Boolean {
        val port = EInkEngineRegistry.selectionEngine ?: return false
        return port.saveBookmark(
            ReaderSelectionCommit(
                chapterIndex = engine.currentChapterIndex,
                start = sel.bodyStart,
                end = sel.bodyEnd,
                selectedText = sel.selectedText,
                bookmarkText = bookText,
                bookmarkContent = content,
                note = "",
            ),
        )
    }
```

- [ ] **Step 2: 书签编辑弹层**

`ReaderSelectionSheets.kt`（输入行参照 `EInkSearchInputBar` 的 BasicTextField 写法，designsystem/control/EInkSearchBar.kt:105-162）：

```kotlin
package io.legado.app.eink.feature.reader.selection

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import io.legado.app.eink.contract.ReaderSelectionDraft
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.text.EInkText
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 书签编辑弹层：标题（预填选中文本）+ 内容（预填空），均可改；
 * 字段对应宿主 Bookmark.bookText / content。
 */
@Composable
internal fun ReaderBookmarkEditDialog(
    draft: ReaderSelectionDraft,
    onDismiss: () -> Unit,
    onConfirm: (bookText: String, content: String) -> Unit,
) {
    var bookText by remember { mutableStateOf(draft.bookmarkText) }
    var content by remember { mutableStateOf(draft.bookmarkContent) }
    EInkDialog(
        onDismiss = onDismiss,
        title = "添加书签",
        confirmText = "保存",
        onConfirm = { onConfirm(bookText, content) },
    ) {
        Column {
            EInkText(text = "标题", style = EInkTheme.typography.labelMedium)
            BasicTextField(
                value = bookText,
                onValueChange = { bookText = it },
                textStyle = EInkTheme.typography.bodyMedium.copy(color = EInkTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(EInkTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            EInkText(text = "内容", style = EInkTheme.typography.labelMedium)
            BasicTextField(
                value = content,
                onValueChange = { content = it },
                textStyle = EInkTheme.typography.bodyMedium.copy(color = EInkTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(EInkTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
    }
}
```

（`EInkText` 的实际包路径/签名以 `designsystem` 内既有用法为准（ReaderScreen.kt:447 有用例）；若无 labelMedium 用 bodyMedium。）

- [ ] **Step 3: Route 接线**

`ReaderScreen.kt` Route 内（`selection` 状态旁）加弹层状态与动作处理：

```kotlin
    // 选区动作：null = 无弹层
    var bookmarkDraft by remember { mutableStateOf<ReaderSelectionDraft?>(null) }
    var pendingSelection by remember { mutableStateOf<ReaderSelectionUi?>(null) }
    val scope = rememberCoroutineScope()
```

`onSelectionMenuAction` 的书签分支：

```kotlin
                    ReaderSelectionMenuAction.BOOKMARK -> {
                        scope.launch {
                            val draft = viewModel.resolveSelection(sel)
                            if (draft == null) {
                                selection = null
                                Toast.makeText(context, "选区已失效", Toast.LENGTH_SHORT).show()
                            } else {
                                pendingSelection = sel
                                bookmarkDraft = draft
                            }
                        }
                    }
```

弹层组合（与 `showRemoveConfirm` 同层，ReaderScreen.kt:442 附近）：

```kotlin
        bookmarkDraft?.let { draft ->
            val sel = pendingSelection
            ReaderBookmarkEditDialog(
                draft = draft,
                onDismiss = { bookmarkDraft = null; pendingSelection = null },
                onConfirm = { bookText, content ->
                    scope.launch {
                        val ok = sel != null && viewModel.saveBookmark(sel, bookText, content)
                        bookmarkDraft = null
                        pendingSelection = null
                        selection = null
                        viewModel.notify(
                            if (ok) UserMessage.from(R.string.eink_bookmark_saved)
                            else UserMessage.from(R.string.eink_bookmark_save_failed)
                        )
                    }
                },
            )
        }
```

（`viewModel.notify`/`_messages` 若为 private，改经既有公共发射方法；`R.string.eink_bookmark_saved`/`eink_bookmark_save_failed`/`eink_selection_invalid` 三个文案加入模块 strings（modules/eink res/values 与 values-zh 一并，「已添加书签」「保存失败」「选区已失效」——模块若无多语言目录则以模块现状为准）。失败时按设计保留弹层可重试：把 `selection = null` 移出失败分支、仅 toast——按此实现。）

- [ ] **Step 4: 编译 + 回归**

Run: `.\gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `git diff --check`
Expected: 无输出

- [ ] **Step 5: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ \
  modules/eink/src/main/res/
git commit -m "feat(eink): 长按选择书签链路——编辑弹层与端口提交"
```

---

## 切片四：笔记链路 + 装饰渲染闭环

### Task 9: 宿主端口——笔记落库与重排推送

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderSelectionEngineImpl.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderSelectionEngineImplTest.kt`（追加）

**Interfaces:**
- Consumes: `SaveMarkingUseCase.save(bookName, bookAuthor, bookUrl, chapterIndex, chapterPosition, selectedText, style, chapterName, note, contextBefore, contextAfter)`（SaveMarkingUseCase.kt:50-112，归一化+哈希在其内部）；`ReaderEngineImpl.relayout()`（ReaderEngineImpl.kt:487-498，= 清排版缓存 + `loadContent(resetPageOffset=false)`，保持页内位置）；`TextProcessStyle`（domain/model/BookContentProcessModels.kt:41-49）。
- Produces: `saveMarking` 完整实现；常量 `EINK_MARKING_COLOR = 0xFF63C37D`（宿主划线渲染的回退默认色，LegacyReaderStyleRangeMapper.kt:107）。

- [ ] **Step 1: 写失败测试（样式与提交构造纯函数）**

```kotlin
    @Test
    fun `eink 笔记固定实线样式`() {
        val style = einkMarkingStyle()
        assertEquals(1, style.underlineMode)
        assertEquals(0xFF63C37D.toInt(), style.underlineColor)
        assertEquals(null, style.bgColor)
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderSelectionEngineImplTest"`
Expected: FAIL

- [ ] **Step 3: 实现 saveMarking**

`ReaderSelectionEngineImpl.kt` 追加（对象外顶层）：

```kotlin
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.domain.usecase.SaveMarkingUseCase

/** eink 笔记固定色：宿主划线渲染的回退默认（灰绿），eink 页面按主题黑绘制。 */
private val EINK_MARKING_COLOR: Int = 0xFF63C37D.toInt()

/** eink 创建的笔记固定实线样式（无样式配置——既定决策）。 */
internal fun einkMarkingStyle(): TextProcessStyle =
    TextProcessStyle(underlineMode = 1, underlineColor = EINK_MARKING_COLOR)
```

对象内注入 `private val saveMarkingUseCase: SaveMarkingUseCase by inject()`，替换 `saveMarking`：

```kotlin
    override suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean {
        val book = ReadBook.book ?: return false
        val content = semanticContent(commit.chapterIndex) ?: return false
        val located = locateInContent(content, commit.start, commit.selectedText)
        if (located < 0) return false
        val (before, after) = extractContext(content, located, commit.selectedText.length)
        return try {
            saveMarkingUseCase.save(
                bookName = book.name,
                bookAuthor = book.author,
                bookUrl = book.bookUrl,
                chapterIndex = commit.chapterIndex,
                chapterPosition = located,
                selectedText = commit.selectedText,
                style = einkMarkingStyle(),
                chapterName = displayTitle(),
                note = commit.note,
                contextBefore = before,
                contextAfter = after,
            )
            // 新快照经 onContentUpdated 推送（保持页内位置），模块随重绘清选区
            ReaderEngineImpl.relayout()
            true
        } catch (e: Exception) {
            false
        }
    }
```

（`SaveMarkingUseCase.save` 内部 `require(normalized.isNotBlank())` 抛 IllegalArgumentException——被 catch 转 false，符合「落库失败」语义。）

- [ ] **Step 4: 运行测试 + 编译**

Run: `.\gradlew.bat testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderSelectionEngineImplTest" :app:compileAppDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/
git commit -m "feat(eink): 宿主桥实现笔记落库（固定实线）与保存后重排推送"
```

### Task 10: 模块笔记弹层 + 页面装饰渲染 + 收尾时序

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderSelectionSheets.kt`（追加笔记弹层）
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/selection/ReaderDecorationSpan.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderPageSnapshotCanvas.kt`（装饰绘制）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`（saveMarking）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`（Route 接线）
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/reader/selection/ReaderDecorationSpanTest.kt`

**Interfaces:**
- Consumes: `ReaderPageLine.decorations`（Task 1/2）；`ReaderSelectionEngine.saveMarking`。
- Produces: `decorationSpanX(line, run, measure): Pair<Float, Float>?`（绘制用 x 区间）；VM `suspend fun saveMarking(sel, note): Boolean`；`ReaderMarkingEditDialog`。

- [ ] **Step 1: 写失败测试（装饰 x 区间）**

```kotlin
package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderPageLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val measure: (String) -> Float = { it.length * 10f }

class ReaderDecorationSpanTest {

    private fun line(vararg chunks: String, positions: IntArray) = ReaderPageLine(
        baseY = 50f, isTitle = false, chunks = chunks.toList(),
        x = FloatArray(chunks.size) { i -> i * 100f },
        chapterPositions = positions, top = 30f, bottom = 70f,
    )

    @Test
    fun `装饰区间换算为 x 跨度`() {
        val l = line("abcdef", "gh", positions = intArrayOf(0, 6))
        // 字符 2..9（跨两段）
        val span = decorationSpanX(l, ReaderDecorationRun(2, 9, underlineMode = 1, highlight = false), measure)
        assertEquals(20f, span!!.first)   // x[0] + 2 字符
        assertEquals(100f + 10f, span.second) // x[1] + 1 字符
    }

    @Test
    fun `区间越界返回 null`() {
        val l = line("abc", positions = intArrayOf(0))
        assertNull(decorationSpanX(l, ReaderDecorationRun(2, 9, 1, false), measure))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.selection.ReaderDecorationSpanTest"`
Expected: FAIL

- [ ] **Step 3: 实现装饰区间纯函数**

`ReaderDecorationSpan.kt`：

```kotlin
package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderPageLine

/** 装饰 run → 行内 x 跨度；越界（快照行文本变化后残留 run）返回 null。 */
fun decorationSpanX(
    line: ReaderPageLine,
    run: ReaderDecorationRun,
    measure: (String) -> Float,
): Pair<Float, Float>? {
    val total = lineText(line).length
    if (run.start < 0 || run.end > total || run.start >= run.end) return null
    return charX(line, run.start, measure) to charX(line, run.end, measure)
}
```

- [ ] **Step 4: 画布绘制装饰**

`ReaderPageSnapshotCanvas.kt` 的 `Canvas` 块（44-66 行）改造为三遍：高亮 → 文本 → 下划线：

```kotlin
        Canvas(modifier = modifier) {
            val snapshot = page ?: return@Canvas
            val nativeCanvas = drawContext.canvas.nativeCanvas
            titlePaint.applySpec(snapshot.titleSpec, themeTextColorArgb)
            contentPaint.applySpec(snapshot.contentSpec, themeTextColorArgb)

            // 1) 高亮带（正文之下）：主题灰底
            val highlightArgb = EInkTheme.colorScheme.surfaceVariant.toArgb()
            val highlightPaint = android.graphics.Paint().apply { color = highlightArgb }
            for (line in snapshot.lines) {
                for (run in line.decorations) {
                    if (!run.highlight) continue
                    val span = decorationSpanX(line, run) { contentPaint.measureText(it) } ?: continue
                    nativeCanvas.drawRect(
                        span.first, line.top, span.second, line.bottom, highlightPaint,
                    )
                }
            }

            // 2) 文本（既有逻辑不变）
            for (line in snapshot.lines) {
                val paint = if (line.isTitle) titlePaint else contentPaint
                for ((index, chunk) in line.chunks.withIndex()) {
                    nativeCanvas.drawText(chunk, line.x[index], line.baseY, paint)
                }
            }

            // 3) 下划线（正文之上）：主题黑；mode 5/未知降级实线
            val underlineColor = EInkTheme.colorScheme.onBackground.toArgb()
            val strokeWidth = 1.5f * density
            for (line in snapshot.lines) {
                for (run in line.decorations) {
                    if (run.underlineMode == 0) continue
                    val span = decorationSpanX(line, run) { contentPaint.measureText(it) } ?: continue
                    val y = line.baseY + line.let { (it.bottom - it.top) * 0.12f }
                    when (run.underlineMode) {
                        2 -> drawDashedLine(span.first, y, span.second, strokeWidth, underlineColor)
                        3 -> drawWaveLine(span.first, y, span.second, strokeWidth, underlineColor)
                        4 -> {
                            drawSolidLine(span.first, y, span.second, strokeWidth, underlineColor)
                            drawSolidLine(span.first, y + strokeWidth * 2.5f, span.second, strokeWidth, underlineColor)
                        }
                        else -> drawSolidLine(span.first, y, span.second, strokeWidth, underlineColor)
                    }
                }
            }
        }
```

`drawSolidLine/drawDashedLine/drawWaveLine` 为文件内 private（Compose `DrawScope`：solid 用 `drawLine`；dashed 用 `Path`+`PathEffect.dashPathEffect(floatArrayOf(8*d, 5*d))` 经 nativeCanvas；wave 用 `Path.quadTo` 幅度 3*d、波长 12*d——参数对齐宿主 `ReaderCharacterStyle` 默认（dashOn 8/dashOff 5/waveAmp 3/waveLen 12，LegacyReaderStyleRangeMapper.kt:111-115）。

- [ ] **Step 5: VM saveMarking + 笔记弹层 + 接线**

`ReaderViewModel.kt` 追加：

```kotlin
    /** 保存笔记（固定实线；成功后宿主重排推送新快照）。 */
    suspend fun saveMarking(sel: ReaderSelectionUi, note: String): Boolean {
        val port = EInkEngineRegistry.selectionEngine ?: return false
        return port.saveMarking(
            ReaderSelectionCommit(
                chapterIndex = engine.currentChapterIndex,
                start = sel.bodyStart,
                end = sel.bodyEnd,
                selectedText = sel.selectedText,
                bookmarkText = "",
                bookmarkContent = "",
                note = note,
            ),
        )
    }
```

`ReaderSelectionSheets.kt` 追加笔记弹层（只读预览 + 备注输入，结构同书签弹层，title = "添加笔记"，保存回调 `onConfirm(note: String)`）。

Route 接线：`var markingDraft by remember { mutableStateOf<ReaderSelectionDraft?>(null) }`；菜单 MARKING 分支同 BOOKMARK（复用 `pendingSelection`）；弹层确认：

```kotlin
                        scope.launch {
                            val ok = sel != null && viewModel.saveMarking(sel, note)
                            markingDraft = null
                            if (!ok) {
                                viewModel.notify(UserMessage.from(R.string.eink_marking_save_failed))
                                return@launch
                            }
                            // 成功：弹层已关、选区保留到新快照重绘后由 pageVersion 清除
                        }
```

失败时（含宿主 `onLayoutException` 路径——VM 既有异常通道已有提示）选区兜底清除：`selection = null`；成功路径不清（等 `LaunchedEffect(pageVersion)`）。

- [ ] **Step 6: 全量模块验证**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin testAppDebugUnitTest --tests "io.legado.app.eink.bridge.*"`
Expected: 全绿

Run: `git diff --check`
Expected: 无输出

- [ ] **Step 7: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/ \
  modules/eink/src/test/
git commit -m "feat(eink): 笔记链路与页面划线/高亮装饰渲染闭环"
```

### Task 11: 文档回填 + 真机验证清单 + 收尾

**Files:**
- Modify: `docs/dev/eink-selection-bookmark-marking-design.md`（两处精化回填）
- Modify: `docs/dev/eink-selection-bookmark-marking-plan.md`（勾选情况无需回写，本任务只改设计文档）

- [ ] **Step 1: 设计文档精化回填**

按实施结果修订设计文档两处（与 Task 3/Task 2 的实现一致）：

1. §3.3 `resolveSelection` 签名增加第 4 参数 `selectedText: String`（宿主窗口搜索定位的依据，纯标题选区兜底依赖它）；
2. §6 「同行相邻 run 合并」的执行侧从模块绘制移到宿主映射器（映射侧合并、模块直绘）。

- [ ] **Step 2: 全量验证**

```powershell
.\gradlew.bat :modules:eink:testDebugUnitTest testAppDebugUnitTest :app:compileAppDebugKotlin verifyConfigArchitecture --continue --no-configuration-cache
git diff --check
```
Expected: 全部通过

- [ ] **Step 3: 真机手工验证清单（交付说明中如实报告结果）**

- 长按选词 → 把手拖拽调整 → 浮条三键显隐（无标题选区 3 键、含标题 2 键、无端口 1 键——后者可临时注释 EInkBridge 装配验证降级）。
- 书签：保存 → 宿主完整模式打开同一本书的书签列表，确认条目存在且点击跳转位置正确（bodyStart 口径核对——设计 §9 风险项的落点验证）。
- 笔记：保存 → eink 页面出现黑色实线（等新快照重绘，选区随之清除）；宿主完整模式同一页看到灰绿实线；宿主模式预先创建的波浪/虚线标记在 eink 页面如实显示。
- 复制：剪贴板内容正确。
- 翻页/跳章/自动翻页清选区；选区内点按清选区；弹层软键盘避让。
- 波浪/虚线在真机灰屏观感确认（设计 §9 风险项）。

- [ ] **Step 4: Commit**

```bash
git add docs/dev/eink-selection-bookmark-marking-design.md
git commit -m "docs(eink): 回填选择/书签/笔记设计的实施精化（签名与合并侧）"
```

---

## Self-Review 记录

- **规格覆盖**：设计 §3 契约（Task 1/2/3）、§4 交互（Task 4/5/6）、§5 弹层与提交（Task 7/8/9/10）、§6 渲染（Task 2/10）、§7 bridge（Task 7/9）、§8 测试（各任务 + Task 11）、§10 切片一一对应。降级路径（端口缺失）在 Task 3/6。真机清单覆盖 §9 风险项。
- **占位符**：Task 6 的 UI 代码为骨架级（Compose 无法在本计划中完全预先验证），但每个待定点的判定规则均已写明（EInkText 路径以既有用法为准、canvasWidth 来源二选一、`remaining` 笔误行删除），不属未定义行为；其余任务代码完整。
- **类型一致性**：`ReaderSelectionUi`（bodyStart/bodyEnd/selectedText/includesTitle/startHit/endHit）在 Task 4 定义、Task 5/6/8/10 消费一致；`ReaderSelectionCommit` 七字段在 Task 3 定义、Task 7/8/9/10 构造一致；`ReaderDecorationRun` 四字段一致；`decorationSpanX` 返回 `Pair<Float, Float>?` 定义与消费一致。
