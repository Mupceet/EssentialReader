# E-Ink 阅读页渲染叶子反转（页面快照模型）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `:app` 内最后一个 E-Ink 专属绘制文件（ReaderPageCanvas）收编进 `modules/eink`：宿主把引擎排版产物映射为模块自有快照，模块自持画布绘制，删除 `pageRenderer` 槽位。

**Architecture:** 排版引擎（ChapterProvider/TextPage 等）留宿主不动；宿主桥接层新增 TextPage → `EInkPageSnapshot` 映射器（消化上游 TextLine 字段漂移）；模块新增画布组件按快照绘制。设计规格见 `docs/superpowers/specs/2026-08-31-eink-reader-snapshot-render-design.md`（实施前先读）。

**Tech Stack:** Kotlin、Jetpack Compose（Foundation，无 Material3）、android.graphics（Paint/Bitmap）、JUnit4 + Robolectric（`:app` 单测）、JDK 21、Windows Git Bash。

**约定（每个任务都适用）：**

- 工作目录 `D:\Projects\AndroidProjects\legadoMD3-port`，分支 `md3/port/eink`。
- 仓库索引为 LF、工作区惯例 CRLF（`git ls-files --eol` 显示 `i/lf w/crlf`）。工具写出的新文件是 LF，**每次 commit 前对本次新建/修改的文件执行行尾归一**：
  `sed -i 's/\r*$/\r/' <files>`。
- Gradle 用 `./gradlew.bat`（Git Bash 下），开发/CI 用 JDK 21。
- 每个任务收尾跑一次 `git diff --check`（空白错误检查）。
- 提交信息用中文，风格对齐近期提交（`feat(eink): ...` / `refactor(eink): ...`）。

---

## 文件结构总览

**新建：**

| 文件 | 职责 |
|---|---|
| `modules/eink/src/main/java/io/legado/app/eink/engine/EInkPageSnapshot.kt` | 快照契约类型（页面/行/图片槽/画笔规格） |
| `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderPageSnapshotCanvas.kt` | 模块自持绘制画布 |
| `app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt` | 宿主 TextPage → 快照映射器 |
| `app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt` | 映射器单测 |

**修改：**

| 文件 | 变化 |
|---|---|
| `modules/eink/.../engine/GlobalSettings.kt` | +`useAntiAlias` 属性 |
| `modules/eink/.../engine/ReaderEngine.kt` | `currentPage()` 返回快照；删 `EInkPageContent`；KDoc |
| `modules/eink/.../feature/reader/ReaderViewModel.kt` | `page` 字段类型；KDoc |
| `modules/eink/.../feature/reader/ReaderScreen.kt` | 删 `pageRenderer` 两处；内部画布调用 |
| `modules/eink/.../app/EInkApp.kt` | 删 `pageRenderer` 参数与透传 |
| `app/.../eink/bridge/EInkBridge.kt` | GlobalSettingsImpl 实现 `useAntiAlias`；删 `EInkBridge.useAntiAlias` |
| `app/.../eink/bridge/ReaderEngineImpl.kt` | 删 `TextPageContent`；`currentPage()` 走映射器 |
| `app/.../eink/EinkMainActivity.kt` | 删 pageRenderer lambda 与相关 import |

**删除：**

| 文件 | 说明 |
|---|---|
| `app/src/main/java/io/legado/app/eink/reader/ReaderPageCanvas.kt` | 整个 `eink/reader/` 目录消失 |

---

### Task 1: 模块快照契约类型

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/engine/EInkPageSnapshot.kt`

- [ ] **Step 1: 写入快照类型文件**

```kotlin
package io.legado.app.eink.engine

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.runtime.Stable

/**
 * 排版引擎产物的模块侧快照（宿主桥接层映射 TextPage 而来）。
 *
 * 宿主把引擎排版结果映射为本类型（模块自有类型，零宿主类型渗透），
 * 模块画布据此绘制；上游 TextLine 的字段漂移（extraLetterSpacing 等）
 * 由各宿主映射器消化，模块只有一份绘制实现。
 *
 * 坐标均为引擎排版坐标系（px），绘制结果与 View 版 ContentTextView 一致。
 * 实例构建后不可变；每次 pageVersion 变化构建一次（无逐帧开销）。
 */
@Stable
class EInkPageSnapshot(
    /** 页所属章节标题（页眉/状态展示）。 */
    val title: String,
    /** 阅读进度文本。 */
    val readProgress: String,
    /** 标题行画笔规格（TextLine.isTitle 行使用）。 */
    val titleSpec: ReaderPaintSpec,
    /** 正文行画笔规格。 */
    val contentSpec: ReaderPaintSpec,
    /** 文本行（非文本列如评论列不进入）。 */
    val lines: List<EInkSnapshotLine>,
    /** 图片槽位。 */
    val images: List<EInkImageSlot>,
)

/** 单文本行：chunks[i] 绘制于 x[i]，基线 baseY。 */
@Stable
class EInkSnapshotLine(
    val baseY: Float,
    val isTitle: Boolean,
    val chunks: List<String>,
    val x: FloatArray,
)

/**
 * 图片槽位。
 *
 * [loader] 由宿主闭包提供（含位图解析与异常吞并，失败返回 null）；
 * 铺满/等比居中的矩形数学在模块画布完成（需位图实际尺寸，仅绘制期可得）。
 */
class EInkImageSlot(
    val x0: Float,
    val x1: Float,
    val lineTop: Float,
    val lineBottom: Float,
    val lineHeight: Float,
    /** true = 图片独占整行（铺满行盒）；false = 行内嵌图（等比居中）。 */
    val fullLine: Boolean,
    val loader: (w: Int, h: Int) -> Bitmap?,
)

/**
 * 画笔渲染规格（与引擎 upStyle 实际设置的属性一一对齐）。
 *
 * 字色不进规格：模块按 EInkTheme 主题色自涂。排版配置与完整模式共享，
 * 规格必须全量搬运（含斜体/阴影/可变字重），窄化即隐性渲染退化。
 */
@Stable
class ReaderPaintSpec(
    val textSizePx: Float,
    val letterSpacing: Float,
    val typeface: Typeface?,
    /** 可变字重设置（如 "'wght' 700"）；null = 未设置。 */
    val fontVariationSettings: String?,
    /** 斜体倾斜（引擎斜体 = -0.25f）。 */
    val textSkewX: Float,
    val isLinearText: Boolean,
    val shadow: ReaderShadowSpec?,
)

/** 阴影层参数（引擎画笔 shadowLayer 的快照）。 */
@Stable
class ReaderShadowSpec(
    val radius: Float,
    val dx: Float,
    val dy: Float,
    val color: Int,
)
```

- [ ] **Step 2: 行尾归一并编译模块**

```bash
sed -i 's/\r*$/\r/' modules/eink/src/main/java/io/legado/app/eink/engine/EInkPageSnapshot.kt
./gradlew.bat :modules:eink:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/engine/EInkPageSnapshot.kt
git commit -m "feat(eink): 阅读页快照契约类型——页面/行/图片槽/画笔规格"
```

---

### Task 2: 宿主映射器（TDD）

**Files:**
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt`
- Create: `app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt`

- [ ] **Step 1: 写失败测试**

测试全部用显式构造参数绕开实体的 appCtx 默认值（TextPage 的 text/title 默认值读资源）。
`TextPage` 第 4 参 `textLines` 与 `TextLine` 第 2 参 `textColumns` 是构造参数私有属性，
构造时直接传入自有列表即可填充。

```kotlin
package io.legado.app.eink.bridge

import android.graphics.Bitmap
import io.legado.app.data.entities.Book
import io.legado.app.eink.engine.ReaderPaintSpec
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageSnapshotMapperTest {

    private val contentSpec = ReaderPaintSpec(
        textSizePx = 40f,
        letterSpacing = 0f,
        typeface = null,
        fontVariationSettings = null,
        textSkewX = 0f,
        isLinearText = false,
        shadow = null,
    )
    private val titleSpec = contentSpec.copy(letterSpacing = 0.1f)

    private fun textPage(lines: List<TextLine>, title: String = "章节标题"): TextPage =
        TextPage(
            index = 0,
            text = "正文",
            title = title,
            textLines = ArrayList(lines),
            chapterSize = 1,
            chapterIndex = 0,
            height = 0f,
            leftLineSize = 0,
            renderHeight = 0,
        )

    private fun textLine(
        isTitle: Boolean = false,
        isImage: Boolean = false,
        lineTop: Float = 0f,
        lineBase: Float = 0f,
        lineBottom: Float = 0f,
        columns: List<BaseColumn> = emptyList(),
    ): TextLine = TextLine(
        text = "line",
        textColumns = ArrayList(columns),
        lineTop = lineTop,
        lineBase = lineBase,
        lineBottom = lineBottom,
        isTitle = isTitle,
        isImage = isImage,
    )

    private fun textCol(start: Float, char: String) =
        TextColumn(start = start, end = start + 20f, charData = char)

    private fun mapLines(
        vararg lines: TextLine,
        sdkInt: Int = 30,
    ) = ReaderPageSnapshotMapper.mapWithSpecs(
        page = textPage(lines.toList()),
        titleSpec = titleSpec,
        contentSpec = contentSpec,
        sdkInt = sdkInt,
        imageLoader = { _, _ -> { _, _ -> null } },
    )

    @Test
    fun `文本列按 chunk 与起点 x 映射`() {
        val snapshot = mapLines(
            textLine(lineBase = 50f, columns = listOf(textCol(10f, "你"), textCol(30f, "好")))
        )

        assertEquals(1, snapshot.lines.size)
        val line = snapshot.lines[0]
        assertEquals(50f, line.baseY, 0.001f)
        assertFalse(line.isTitle)
        assertEquals(listOf("你", "好"), line.chunks)
        assertEquals(2, line.x.size)
        assertEquals(10f, line.x[0], 0.001f)
        assertEquals(30f, line.x[1], 0.001f)
    }

    @Test
    fun `API35 以上加字距半格补偿`() {
        // contentSpec.letterSpacing=0、textSizePx=40 → 补偿 0
        val s1 = mapLines(textLine(columns = listOf(textCol(10f, "a"))), sdkInt = 35)
        assertEquals(10f, s1.lines[0].x[0], 0.001f)

        // 标题行：titleSpec.letterSpacing=0.1、textSizePx=40 → 补偿 2.0
        val s2 = mapLines(
            textLine(isTitle = true, columns = listOf(textCol(10f, "a"))),
            sdkInt = 35,
        )
        assertEquals(12f, s2.lines[0].x[0], 0.001f)

        // API35 以下无补偿
        val s3 = mapLines(
            textLine(isTitle = true, columns = listOf(textCol(10f, "a"))),
            sdkInt = 34,
        )
        assertEquals(10f, s3.lines[0].x[0], 0.001f)
    }

    @Test
    fun `标题行携带 isTitle 标记`() {
        val snapshot = mapLines(
            textLine(isTitle = true, columns = listOf(textCol(0f, "题"))),
            textLine(columns = listOf(textCol(0f, "文"))),
        )

        assertTrue(snapshot.lines[0].isTitle)
        assertTrue(!snapshot.lines[1].isTitle)
    }

    @Test
    fun `图片列成为槽位并携带行盒几何`() {
        val loaderCalls = mutableListOf<Pair<Int, Int>>()
        val loader: (Book, String) -> (Int, Int) -> Bitmap? = { _, _ ->
            { w, h ->
                loaderCalls.add(w to h)
                null
            }
        }
        val book = Book()
        val imageColumn = ImageColumn(start = 5f, end = 25f, src = "img.png", book = book)
        val line = textLine(
            isImage = true,
            lineTop = 100f,
            lineBottom = 150f,
            columns = listOf(imageColumn),
        )
        val snapshot = ReaderPageSnapshotMapper.mapWithSpecs(
            page = textPage(listOf(line)),
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            sdkInt = 30,
            imageLoader = loader,
        )

        assertEquals(0, snapshot.lines.size)
        assertEquals(1, snapshot.images.size)
        val slot = snapshot.images[0]
        assertEquals(5f, slot.x0, 0.001f)
        assertEquals(25f, slot.x1, 0.001f)
        assertEquals(100f, slot.lineTop, 0.001f)
        assertEquals(150f, slot.lineBottom, 0.001f)
        assertEquals(50f, slot.lineHeight, 0.001f)
        assertTrue(slot.fullLine)
        // 画布调用 loader 时按列宽与行高取图
        assertNull(slot.loader(20, 50))
        assertEquals(listOf(20 to 50), loaderCalls)
    }

    @Test
    fun `行内嵌图 fullLine 为 false`() {
        val imageColumn = ImageColumn(start = 5f, end = 25f, src = "img.png", book = Book())
        val snapshot = mapLines(
            textLine(isImage = false, lineTop = 0f, lineBottom = 50f, columns = listOf(imageColumn))
        )

        assertTrue(!snapshot.images[0].fullLine)
    }

    @Test
    fun `评论列等非文本列不进入快照`() {
        val snapshot = mapLines(
            textLine(columns = listOf(ReviewColumn(start = 0f, end = 10f)))
        )

        assertEquals(0, snapshot.lines.size)
        assertEquals(0, snapshot.images.size)
    }

    @Test
    fun `快照携带标题与进度文本`() {
        val snapshot = ReaderPageSnapshotMapper.mapWithSpecs(
            page = textPage(emptyList(), title = "第一章"),
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            sdkInt = 30,
            imageLoader = { _, _ -> { _, _ -> null } },
        )

        assertEquals("第一章", snapshot.title)
        assertEquals("", snapshot.readProgress)
    }
}
```

注意：`readProgress` 期望值以实体实际行为准：先在 Step 2 跑一次确认（`TextPage.readProgress`
是计算属性，空页可能返回 "0.0%" 而非 ""，若断言失败以实际值为准修正该断言）。

- [ ] **Step 2: 跑测试确认失败（编译错误：Mapper 不存在）**

```bash
./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"
```

Expected: COMPILATION ERROR（`ReaderPageSnapshotMapper` unresolved）

- [ ] **Step 3: 写映射器实现**

```kotlin
package io.legado.app.eink.bridge

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import io.legado.app.data.entities.Book
import io.legado.app.eink.engine.EInkImageSlot
import io.legado.app.eink.engine.EInkPageSnapshot
import io.legado.app.eink.engine.EInkSnapshotLine
import io.legado.app.eink.engine.ReaderPaintSpec
import io.legado.app.eink.engine.ReaderShadowSpec
import io.legado.app.model.ImageProvider
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * TextPage → 模块快照映射器（宿主唯一新增渲染职责）。
 *
 * 引擎排版完成后把页面映射为 [EInkPageSnapshot]：上游 TextLine 的字段
 * 漂移（extraLetterSpacing/wordSpacing/isHtml 等）由本映射器消化，模块
 * 画布只有一份绘制实现。API35+ 的逐字字距半格补偿（View 版画布行为）
 * 在映射期算进 x 坐标，模块不再感知。
 *
 * 画笔规格从 [ChapterProvider] 共享画笔全量拷贝 —— 排版配置与完整模式
 * 共享，斜体/阴影/可变字重等完整模式设置在 E-Ink 必须同样可见。
 *
 * 在引擎回调线程调用（onUpContent 内），产物不可变、跨线程安全。
 */
internal object ReaderPageSnapshotMapper {

    /** 生产入口：规格取自引擎当前共享画笔，补偿按真实 SDK 版本。 */
    fun map(page: TextPage): EInkPageSnapshot = mapWithSpecs(
        page = page,
        titleSpec = ChapterProvider.titlePaint.copyPaintSpec(),
        contentSpec = ChapterProvider.contentPaint.copyPaintSpec(),
        sdkInt = Build.VERSION.SDK_INT,
        imageLoader = ::defaultImageLoader,
    )

    /** 纯函数核心（单测直接喂规格与 SDK 版本）。 */
    internal fun mapWithSpecs(
        page: TextPage,
        titleSpec: ReaderPaintSpec,
        contentSpec: ReaderPaintSpec,
        sdkInt: Int,
        imageLoader: (Book, String) -> (Int, Int) -> Bitmap?,
    ): EInkPageSnapshot {
        val lines = ArrayList<EInkSnapshotLine>(page.lines.size)
        val images = ArrayList<EInkImageSlot>()
        for (line in page.lines) {
            val spec = if (line.isTitle) titleSpec else contentSpec
            // API 35+ drawText 会将 letterSpacing 应用在两侧，View 版同样补偿半格
            val halfSpacing =
                if (sdkInt >= 35) spec.letterSpacing * spec.textSizePx * 0.5f else 0f
            val chunks = ArrayList<String>()
            val xs = ArrayList<Float>()
            for (column in line.columns) {
                when (column) {
                    is TextColumn -> {
                        chunks.add(column.charData)
                        xs.add(column.start + halfSpacing)
                    }

                    is ImageColumn -> images.add(
                        EInkImageSlot(
                            x0 = column.start,
                            x1 = column.end,
                            lineTop = line.lineTop,
                            lineBottom = line.lineBottom,
                            lineHeight = line.height,
                            fullLine = line.isImage,
                            loader = imageLoader(column.book, column.src),
                        )
                    )

                    else -> Unit // 评论列/HTML 列等：E-Ink 不渲染（同 View 画布 else 分支）
                }
            }
            if (chunks.isNotEmpty()) {
                lines.add(
                    EInkSnapshotLine(
                        baseY = line.lineBase,
                        isTitle = line.isTitle,
                        chunks = chunks,
                        x = xs.toFloatArray(),
                    )
                )
            }
        }
        return EInkPageSnapshot(
            title = page.title,
            readProgress = page.readProgress,
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            lines = lines,
            images = images,
        )
    }

    /**
     * 位图解析闭包：捕获创建时那一列的书（换书瞬间旧页不误取新书目录，
     * 与 View 版 ImageColumn 一致）；异常吞并返回 null，由画布跳过槽位。
     */
    private fun defaultImageLoader(book: Book, src: String): (Int, Int) -> Bitmap? =
        { w, h -> runCatching { ImageProvider.getImage(book, src, w, h) }.getOrNull() }

    /**
     * 引擎画笔 → 渲染规格（全量拷贝 upStyle 设置的属性；color 除外，
     * 由模块主题自涂）。shadowLayerRadius > 0 视为设置了阴影。
     */
    internal fun Paint.copyPaintSpec(): ReaderPaintSpec = ReaderPaintSpec(
        textSizePx = textSize,
        letterSpacing = letterSpacing,
        typeface = typeface,
        fontVariationSettings = fontVariationSettings,
        textSkewX = textSkewX,
        isLinearText = isLinearText,
        shadow = shadowLayerRadius.takeIf { it > 0f }?.let {
            ReaderShadowSpec(it, shadowLayerDx, shadowLayerDy, shadowLayerColor)
        },
    )
}
```

- [ ] **Step 4: 追加画笔规格拷贝的 Robolectric 测试**

追加到 `ReaderPageSnapshotMapperTest.kt`（`copyPaintSpec` 在 Step 3 实现中已是
internal，同模块测试可直接调用；经 Robolectric 测 `Paint` 属性拷贝路径，
`@Config(sdk = [34])` 保证 API26+ 属性可用）：

```kotlin
    // ==== 画笔规格拷贝（Robolectric：需要 android.graphics 原生行为）====

    @Test
    fun `画笔规格全量拷贝引擎画笔属性`() {
        val paint = android.graphics.TextPaint().apply {
            textSize = 42f
            letterSpacing = 0.08f
            textSkewX = -0.25f
            isLinearText = true
            typeface = android.graphics.Typeface.MONOSPACE
            setShadowLayer(4f, 1f, 2f, 0xFF00FF00.toInt())
        }
        val spec = ReaderPageSnapshotMapper.copyPaintSpec(paint)

        assertEquals(42f, spec.textSizePx, 0.001f)
        assertEquals(0.08f, spec.letterSpacing, 0.0001f)
        assertEquals(android.graphics.Typeface.MONOSPACE, spec.typeface)
        assertEquals(-0.25f, spec.textSkewX, 0.0001f)
        assertTrue(spec.isLinearText)
        val shadow = requireNotNull(spec.shadow)
        assertEquals(4f, shadow.radius, 0.001f)
        assertEquals(1f, shadow.dx, 0.001f)
        assertEquals(2f, shadow.dy, 0.001f)
        assertEquals(0xFF00FF00.toInt(), shadow.color)
    }

    @Test
    fun `无阴影时规格 shadow 为 null`() {
        val paint = android.graphics.TextPaint() // 从未 setShadowLayer
        val spec = ReaderPageSnapshotMapper.copyPaintSpec(paint)

        assertNull(spec.shadow)
    }
```

为此把实现中的 `toPaintSpec` 从 private 改为 **internal**（测试同模块可访问），
并把测试类注解补齐：

```kotlin
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPageSnapshotMapperTest {
```

注意：纯 JVM 的前 7 个测试在 Robolectric runner 下照常运行（Robolectric 兼容普通
JUnit 测试）。若 `fontVariationSettings` 断言在本机 Robolectric shadow 下不保真，
**不加**该属性的断言（拷贝代码已存在，属 shadow 能力限制），其余断言保持。

- [ ] **Step 5: 跑测试确认通过**

```bash
./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"
```

Expected: BUILD SUCCESSFUL，全部测试 PASS（若有 `readProgress` 断言失败，按实体
实际行为修正后重跑）

- [ ] **Step 6: 行尾归一并提交**

```bash
sed -i 's/\r*$/\r/' app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt \
  app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt
git diff --check
git add app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt \
  app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt
git commit -m "feat(eink): 宿主 TextPage→快照映射器——字距补偿/图片槽位/画笔规格全量拷贝"
```

---

### Task 3: GlobalSettings 增加 useAntiAlias 端口

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/engine/GlobalSettings.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt`

- [ ] **Step 1: 端口接口加属性**

在 `GlobalSettings.kt` 接口内、`preDownloadNum` 声明之前加入：

```kotlin
    /**
     * 图片绘制抗锯齿（OtherSettings.antiAlias，与完整模式共享同一开关）。
     * 阅读页图片画笔取用；引擎文字画笔恒抗锯齿，不受本项影响。
     */
    val useAntiAlias: Boolean
```

- [ ] **Step 2: 宿主实现**

在 `EInkBridge.kt` 的 `GlobalSettingsImpl` 内（`changeSourceCheckAuthor` override
之后）加入：

```kotlin
    override val useAntiAlias: Boolean
        get() = otherSettingsGateway.currentSettings.antiAlias
```

同时**暂不**删除 `EInkBridge.useAntiAlias`（Task 5 才删，其消费方 MainActivity
lambda 仍在用）。

- [ ] **Step 3: 编译并提交**

```bash
./gradlew.bat :app:compileAppDebugKotlin
sed -i 's/\r*$/\r/' modules/eink/src/main/java/io/legado/app/eink/engine/GlobalSettings.kt \
  app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt
git diff --check
git add modules/eink/src/main/java/io/legado/app/eink/engine/GlobalSettings.kt \
  app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt
git commit -m "feat(eink): GlobalSettings 增加图片抗锯齿端口 useAntiAlias"
```

Expected: BUILD SUCCESSFUL

---

### Task 4: 模块自持画布

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderPageSnapshotCanvas.kt`

- [ ] **Step 1: 写画布组件**

```kotlin
package io.legado.app.eink.feature.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.engine.EInkEngineRegistry
import io.legado.app.eink.engine.EInkImageSlot
import io.legado.app.eink.engine.EInkPageSnapshot
import io.legado.app.eink.engine.ReaderPaintSpec

/**
 * 阅读页绘制层（模块自持）。
 *
 * 绘制宿主映射来的 [EInkPageSnapshot]：行 chunk 按预计算 x 坐标画字，
 * 图片槽位按铺满/等比居中画位图。排版本身由引擎（宿主 ChapterProvider）
 * 完成，这里不做二次排版 —— 结果与 View 版 ContentTextView 一致。
 *
 * 字色随日/夜间主题每次绘制前钉上（首帧即正确，主题切换重组自动重绘）；
 * 画笔渲染规格（字号/字距/字体/斜体/阴影等）取自快照规格 —— 引擎配置与
 * 完整模式共享，完整模式设置的斜体/阴影在 E-Ink 同样可见。
 *
 * [pageVersion] 用于强制重绘（引擎可能原地更新同一排版实例后仅推版本号）。
 *
 * 文字画笔恒抗锯齿（对齐引擎 upStyle 硬编码 isAntiAlias = true）；图片
 * 画笔抗锯齿取全局设置 useAntiAlias。
 */
@Composable
internal fun ReaderPageSnapshotCanvas(
    page: EInkPageSnapshot?,
    pageVersion: Int,
    modifier: Modifier = Modifier,
) {
    val themeTextColorArgb = EInkTheme.colorScheme.onBackground.toArgb()
    val imageAntiAlias = EInkEngineRegistry.globalSettings.useAntiAlias
    val titlePaint = remember { Paint() }
    val contentPaint = remember { Paint() }
    val imagePaint = remember(imageAntiAlias) { Paint().apply { isAntiAlias = imageAntiAlias } }
    // 引擎可能原地更新同一排版实例，用版本号强制重建绘制块
    key(pageVersion) {
        Canvas(modifier = modifier) {
            val snapshot = page ?: return@Canvas
            val nativeCanvas = drawContext.canvas.nativeCanvas
            titlePaint.applySpec(snapshot.titleSpec, themeTextColorArgb)
            contentPaint.applySpec(snapshot.contentSpec, themeTextColorArgb)
            for (line in snapshot.lines) {
                val paint = if (line.isTitle) titlePaint else contentPaint
                for ((index, chunk) in line.chunks.withIndex()) {
                    nativeCanvas.drawText(chunk, line.x[index], line.baseY, paint)
                }
            }
            for (slot in snapshot.images) {
                drawImageSlot(nativeCanvas, slot, imagePaint)
            }
        }
    }
}

/**
 * 绘制图片槽位：按列宽×行高向宿主闭包取图，铺满（fullLine）或以宽度为
 * 基准等比居中（与 View 版 ImageColumn 一致）；取图失败/尺寸异常跳过。
 */
private fun drawImageSlot(canvas: Canvas, slot: EInkImageSlot, paint: Paint) {
    val width = (slot.x1 - slot.x0).toInt()
    val height = slot.lineHeight.toInt()
    if (width <= 0 || height <= 0) return
    val bitmap = slot.loader(width, height) ?: return
    if (bitmap.width <= 0 || bitmap.height <= 0) return
    val rectF = if (slot.fullLine) {
        RectF(slot.x0, slot.lineTop, slot.x1, slot.lineBottom)
    } else {
        val h = (slot.x1 - slot.x0) / bitmap.width * bitmap.height
        val div = (slot.lineHeight - h) / 2f
        RectF(slot.x0, slot.lineTop + div, slot.x1, slot.lineBottom - div)
    }
    canvas.drawBitmap(bitmap, null, rectF, paint)
}

/**
 * 把快照规格应用到画笔。API35+ 的逐字半格补偿已在映射期算进 x，画笔
 * 字距保持引擎原值（单字符 drawText 的字形行为与 View 版一致）。
 */
private fun Paint.applySpec(spec: ReaderPaintSpec, colorArgb: Int) {
    color = colorArgb
    textSize = spec.textSizePx
    letterSpacing = spec.letterSpacing
    typeface = spec.typeface ?: Typeface.DEFAULT
    textSkewX = spec.textSkewX
    isLinearText = spec.isLinearText
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // 空字符串 = 清除可变字重设置（宿主 null 对应引擎未设置）
        fontVariationSettings = spec.fontVariationSettings ?: ""
    }
    val shadow = spec.shadow
    if (shadow != null) {
        setShadowLayer(shadow.radius, shadow.dx, shadow.dy, shadow.color)
    } else {
        clearShadowLayer()
    }
}
```

- [ ] **Step 2: 行尾归一并编译模块**

```bash
sed -i 's/\r*$/\r/' modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderPageSnapshotCanvas.kt
./gradlew.bat :modules:eink:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderPageSnapshotCanvas.kt
git commit -m "feat(eink): 模块自持阅读画布——快照绘制与画笔规格应用"
```

---

### Task 5: 契约翻转（跨模块原子切片 + 删除物）

契约变化横跨两个模块，中间态无法各自编译——本任务作为**单次原子提交**完成全部翻转。

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/engine/ReaderEngine.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/app/EInkApp.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt`
- Modify: `app/src/main/java/io/legado/app/eink/EinkMainActivity.kt`
- Delete: `app/src/main/java/io/legado/app/eink/reader/ReaderPageCanvas.kt`（整个 `reader/` 目录）

- [ ] **Step 1: ReaderEngine.kt —— 删句柄接口，改返回类型**

1. 删除 `interface EInkPageContent { ... }` 整块（`ReaderEngine.kt:13-19`）。
2. `currentPage()` 签名与 KDoc 改为：

```kotlin
    /** 当前页（durPageIndex 对应页；未就绪返回 null）。 */
    fun currentPage(): EInkPageSnapshot?
```

3. 类级 KDoc 中「排版产物经 [EInkPageContent] 不透明句柄进入模块状态」改为
   「排版产物经宿主映射为 [EInkPageSnapshot] 快照进入模块状态」。

- [ ] **Step 2: ReaderViewModel.kt —— page 字段类型与 KDoc**

1. import `EInkPageContent` 改为 `io.legado.app.eink.engine.EInkPageSnapshot`。
2. `ReaderUiState.page` 字段改为：

```kotlin
    val page: EInkPageSnapshot? = null,
```

3. `ReaderUiState` 类级 KDoc 中「[page] 为引擎排版产物的不透明句柄……由宿主注入的
   画布槽位（ReaderPageCanvas）还原绘制」改为「[page] 为引擎排版产物的模块快照
   （宿主映射 TextPage 而来），由模块画布 [ReaderPageSnapshotCanvas] 绘制」。
4. `ReaderViewModel` 类级 KDoc 中「宿主 ChapterProvider 排版产物经 EInkPageContent
   句柄进入状态，绘制由宿主画布槽位完成」改为「宿主 ChapterProvider 排版产物经
   映射器转为 EInkPageSnapshot 快照进入状态，绘制由模块画布完成」。

- [ ] **Step 3: ReaderScreen.kt —— 删两处 pageRenderer，接内部画布**

1. 删 `import io.legado.app.eink.engine.EInkPageContent`。
2. `ReaderRoute`（约 :56-64）：删参数

```kotlin
    pageRenderer: @Composable (page: EInkPageContent?, pageVersion: Int, modifier: Modifier) -> Unit,
```

3. `ReaderRoute` 内调用 `ReaderScreen(...)`（约 :144-147）：删去
   `pageRenderer = pageRenderer,` 及其上一行注释「// 绘制叶子（引擎排版产物的画布）由宿主注入」。
4. `ReaderScreen`（约 :278-280）：删同名参数（与第 2 步相同的整行）。
5. 正文区调用（约 :370-374）替换为：

```kotlin
                ReaderPageSnapshotCanvas(
                    page = state.page,
                    pageVersion = state.pageVersion,
                    modifier = Modifier.fillMaxSize(),
                )
```

- [ ] **Step 4: EInkApp.kt —— 删参数与透传**

1. 删 `import io.legado.app.eink.engine.EInkPageContent`（:25）。
2. 删参数（:47）：

```kotlin
    pageRenderer: @Composable (page: EInkPageContent?, pageVersion: Int, modifier: Modifier) -> Unit,
```

3. `ReaderRoute(...)` 调用（:89-94）：删去 `pageRenderer = pageRenderer,` 与其上
   两行注释「// 绘制叶子留在宿主 app……经槽位注入」。
4. 参数 KDoc（:45-46）「宿主注入的引擎能力出口：绘制叶子（引擎画布）与"退出到完整
   模式"」改为「宿主注入的引擎能力出口："退出到完整模式"」。

- [ ] **Step 5: ReaderEngineImpl.kt —— 删句柄包装，走映射器**

1. 删 `internal class TextPageContent`（:43-47）。
2. import 删 `io.legado.app.eink.engine.EInkPageContent` 与
   `io.legado.app.ui.book.read.page.entities.TextPage`（仅 TextPageContent 在用），
   加 `io.legado.app.eink.engine.EInkPageSnapshot`。
3. `currentPage()`（:139-142）改为：

```kotlin
    override fun currentPage(): EInkPageSnapshot? {
        val chapter = ReadBook.curTextChapter ?: return null
        return chapter.getPage(ReadBook.durPageIndex)?.let(ReaderPageSnapshotMapper::map)
    }
```

4. 类级 KDoc 首段「纯转发（含宿主双轨回调 → 模块回调的适配与样式快照映射）」后
   补一句：「排版产物经 [ReaderPageSnapshotMapper] 映射为模块快照。」

- [ ] **Step 6: EInkBridge.kt —— 删旧取值口**

删除 `EInkBridge` object 内的以下整块（其消费方已在 Step 7 移除）：

```kotlin
    private val otherSettingsGateway: OtherSettingsGateway by inject()

    /**
     * 图片绘制抗锯齿开关（OtherSettings）。Compose 文件按本仓架构护栏
     * 禁止导入兼容 Config，画布经此处取值。
     */
    val useAntiAlias: Boolean
        get() = otherSettingsGateway.currentSettings.antiAlias
```

`GlobalSettingsImpl` 自有的 `otherSettingsGateway` 注入与 `OtherSettingsGateway`
import 保留（GlobalSettingsImpl 仍在用）；`EInkBridge` 顶层的这个注入字段只服务
useAntiAlias，一并删除。

- [ ] **Step 7: EinkMainActivity.kt —— 瘦身为纯入口**

1. 删 import（:30、:33）：

```kotlin
import io.legado.app.eink.bridge.TextPageContent
import io.legado.app.eink.reader.ReaderPageCanvas
```

2. `EInkApp(...)` 调用（:172-194）删去 pageRenderer 尾参（:184-193）：

```kotlin
            // 宿主引擎能力出口 2：阅读绘制叶子——直接操作引擎
            // ChapterProvider 画笔与 TextPage 坐标，与 View 版渲染零分岔
            pageRenderer = { page, version, modifier ->
                ReaderPageCanvas(
                    page = (page as? TextPageContent)?.textPage,
                    pageVersion = version,
                    antiAlias = EInkBridge.useAntiAlias,
                    modifier = modifier,
                )
            },
```

（`EInkBridge` import 保留——onCreate 的 `EInkBridge.install(this)` 仍在用。）

- [ ] **Step 8: 删除宿主画布文件并双模块编译**

```bash
git rm -r app/src/main/java/io/legado/app/eink/reader
./gradlew.bat :app:compileAppDebugKotlin :modules:eink:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL（若有遗漏的 `EInkPageContent`/`pageRenderer` 引用，编译
错误会指出，按同样方式清除）

- [ ] **Step 9: 全局确认无残留引用**

```bash
grep -rn "EInkPageContent\|pageRenderer\|ReaderPageCanvas\|TextPageContent" \
  modules/eink/src app/src/main/java/io/legado/app/eink --include="*.kt"
```

Expected: 无输出。

- [ ] **Step 10: 行尾归一、diff 检查、原子提交**

```bash
sed -i 's/\r*$/\r/' \
  modules/eink/src/main/java/io/legado/app/eink/engine/ReaderEngine.kt \
  modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt \
  modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt \
  modules/eink/src/main/java/io/legado/app/eink/app/EInkApp.kt \
  app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt \
  app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt \
  app/src/main/java/io/legado/app/eink/EinkMainActivity.kt
git diff --check
git add -A app/src/main/java/io/legado/app/eink modules/eink/src/main/java/io/legado/app/eink
git commit -m "refactor(eink): 渲染叶子反转——快照契约翻转并删除宿主画布与槽位"
```

---

### Task 6: 汇总验证

- [ ] **Step 1: 单测与快速编译**

```bash
./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"
./gradlew.bat :app:compileAppDebugKotlin
```

Expected: 全部 PASS / BUILD SUCCESSFUL

- [ ] **Step 2: 打包验证（模块依赖/资源合并变化）**

```bash
./gradlew.bat :app:assembleAppDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 差异面复核（验收标准 §9.2）**

```bash
git diff --stat f2a0f5b7b..HEAD -- app/src/main/java/io/legado/app/eink modules/eink
grep -rn "io.legado.app.\(model\|data\|help\|ui\|domain\|constant\)" \
  modules/eink/src/main/java/io/legado/app/eink --include="*.kt"
```

Expected: `:app` eink 包内无 Compose 绘制文件；模块内零宿主类型引用（第二条 grep
无输出）。

- [ ] **Step 4: 真机验证（Clear7，serial 14be82fd）**

```bash
./gradlew.bat installAppDebug
adb -s 14be82fd shell monkey -p io.legato.kazusa.debug 1
```

人工核对清单（对照设计规格 §7）：

1. 打开实验室「墨水屏显示」→ 书架 → 打开一本书进入阅读页；
2. 排版调参即时重排：字号 ±、字距档位、行距、段距、缩进、四边距——每项观察正文
   重排与页眉页脚边距即时生效；
3. 粗体开关切换（可变字重路径）；
4. **画笔规格保真**：先在完整模式阅读设置中开启斜体（或阴影类显示效果），再切回
   E-Ink 阅读页确认效果仍然可见；
5. 含图章节翻页，图片铺满/行内嵌图两种版式都出现时观察比例正确；
6. 「我的」页或系统层切换深色模式，回阅读页确认首帧字色正确（无黑字黑底残留）；
7. 连续翻页 10 次感受翻页延迟与迁移前相当（Clear7 基线 0.5s 量级，无肉眼可感劣化）。

注：Clear7 为 API 33，API35 字距补偿分支真机不可达（单测已覆盖）；若手头有 API35+
设备可加验字距档位非 0 时的列对齐。

- [ ] **Step 5: 收尾核对**

```bash
git status
git log --oneline 00b08d058..HEAD
```

Expected: 工作区干净；本计划共产出 5 个实现提交（Task 1-5）。
