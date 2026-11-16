# 墨水屏页眉页脚信息槽位遵从完整模式配置 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完整模式配置的页眉/页脚 6 槽信息内容，墨水屏模式遵从渲染（含中槽；视觉型槽位文本化降级）。

**Architecture:** 宿主把槽位配置解析为最终文本（新抽共享解析器 `ReaderTipSlotResolver`），经契约新方法 `ReaderEngine.headerFooterTipTexts()`（默认 null，旧宿主回落）传入模块；模块 VM 存入 `uiState.tipSlots`，渲染层由两槽扩三槽，语义镜像宿主 `ReaderCanvasSurface.drawTipRow`。设计文档：`docs/dev/eink-header-footer-tip-slots.md`。

**Tech Stack:** Kotlin、Jetpack Compose Canvas（模块）、Android Canvas/TextPaint（宿主与模块共用绘制路径）、JUnit4 + Robolectric（宿主）、JUnit4 + returnDefaultValues stub（模块）。

## Global Constraints

- 本仓库在 Windows Git Bash 下，Gradle 命令一律 `./gradlew.bat`；JDK 21。
- 两个 Git 仓库：模块仓库在 `eink-lib/`（分支 `eink/lib`），宿主仓库在根目录（分支 `eink/port/md3/modules`）。先提交模块、后抬宿主指针。
- 只 commit 不 push（推送门：全量验证绿后由用户或后续批次推）。
- 宿主工作区有并行任务未提交文件（`app/src/main/java/io/legado/app/feature/reader/core/layout/ReaderPaginator.kt`、`app/src/test/java/io/legado/app/feature/reader/core/layout/ReaderPageUnderlineTest.kt`）：**严禁 stage 这两个文件**。
- UiState/UI item 数据类标注 `@Stable`；契约新类型为 data class（StateFlow 值去重依赖 equals）。
- 模块测试跑法：`cd eink-lib && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "<类名>"`；宿主：根目录 `./gradlew.bat :app:testAppCoreDebugUnitTest --tests "<类名>"`。
- 所有文本改动后 `git diff --check`。

---

### Task 1: eink-lib 契约——`ReaderTipSlotTexts` + `headerFooterTipTexts()`（默认 null）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt`

**Interfaces:**
- Produces（后续任务依赖的精确签名）:
  - `@Stable data class ReaderTipSlotTexts(headerLeft: String, headerMiddle: String, headerRight: String, footerLeft: String, footerMiddle: String, footerRight: String)`，包 `io.legado.app.eink.contract`
  - `fun headerFooterTipTexts(): ReaderTipSlotTexts?`——`ReaderEngine` 接口方法，**带默认实现返回 null**

- [ ] **Step 1: 在 `ReaderHeaderFooterVisibility` data class（约 39-45 行）之后新增类型**

```kotlin
/**
 * 页眉/页脚信息槽位的最终显示文本（完整模式槽位配置解析产物）。
 *
 * 宿主实现义务：按完整模式同款槽位规则解析（同型多槽只取首个、空文本
 * 槽留空、视觉型类型文本化降级——电量图形给 `NN%`、时间+图形电量给
 * `HH:mm NN%`、箭头给纯章节标题）；空串 = 该槽不绘制。旧宿主不实现
 * [ReaderEngine.headerFooterTipTexts] 时模块回落自有硬装配（时间左/
 * 电量右、章节标题左/页数进度右），行为与历史版本一致。
 */
@Stable
data class ReaderTipSlotTexts(
    /** 页眉左槽文本；空串 = 不绘制。 */
    val headerLeft: String,
    /** 页眉中槽文本；空串 = 不绘制。 */
    val headerMiddle: String,
    /** 页眉右槽文本；空串 = 不绘制。 */
    val headerRight: String,
    /** 页脚左槽文本；空串 = 不绘制。 */
    val footerLeft: String,
    /** 页脚中槽文本；空串 = 不绘制。 */
    val footerMiddle: String,
    /** 页脚右槽文本；空串 = 不绘制。 */
    val footerRight: String,
)
```

- [ ] **Step 2: 在 `ReaderEngine` 接口的 `fun headerFooterVisibility(): ReaderHeaderFooterVisibility`（约 644 行）声明之后新增方法**

```kotlin
    /**
     * 页眉/页脚信息槽位文本（完整模式 6 槽配置 + 当前页上下文的解析产物，
     * 详见 [ReaderTipSlotTexts]）。
     *
     * 默认 null = 旧宿主未实现，模块回落自有硬装配。宿主实现应为纯查询、
     * 不抛异常（电量/时间读不到时给默认值）。
     */
    fun headerFooterTipTexts(): ReaderTipSlotTexts? = null
```

- [ ] **Step 3: 编译模块验证默认实现不破坏既有桩（测试里的 fake engine 无需改）**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit（模块仓库）**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt
git commit -m "feat(eink-contract): ReaderEngine 暴露页眉页脚槽位文本 headerFooterTipTexts（默认 null 旧宿主回落）"
```

---

### Task 2: 宿主——抽取 `ReaderTipSlotResolver` 共享解析器（行为保持重构）

**Files:**
- Create: `app/src/main/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolver.kt`
- Modify: `app/src/main/java/io/legado/app/feature/reader/legacy/LegacyReaderPageDecorationFactory.kt`
- Test: `app/src/test/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolverTest.kt`（纯 JVM）
- Test: `app/src/test/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolverContextTest.kt`（Robolectric）

**Interfaces:**
- Consumes: 既有 `ReaderTipValueContext`、`ReaderTipValueFormatter`、`ReaderTipValueType`（`feature/reader/core/model/ReaderTipValue.kt`）、`ReadTipType` 常量。
- Produces（Task 3 依赖）:
  - `ReaderTipSlotResolver.sixSlotTexts(context: ReaderTipValueContext, header: List<Pair<Int, String>>, footer: List<Pair<Int, String>>): ReaderTipSlotResolver.TipSlotTexts`
  - `ReaderTipSlotResolver.TipSlotTexts(headerLeft/headerMiddle/headerRight/footerLeft/footerMiddle/footerRight: String)`
  - `ReaderTipSlotResolver.buildContext(bookName: String, chapterTitle: String, chapterIndex: Int, pageIndex: Int, chapterPageCount: Int, chapterCount: Int, time: String, batteryPercent: Int): ReaderTipValueContext`
  - `ReaderTipSlotResolver.type(type: Int): ReaderTipValueType`、`textTypeOf(type: Int): ReaderTipValueType`、`textFor(type: Int, context, customTemplate: String): String`、`bandTexts(context, slots: List<Pair<Int, String>>): List<String>`、`readProgress(chapterIndex: Int, pageIndex: Int, pageCount: Int, chapterCount: Int): String`

- [ ] **Step 1: 写失败测试（纯 JVM 部分）**

创建 `app/src/test/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolverTest.kt`：

```kotlin
package io.legado.app.feature.reader.legacy

import io.legado.app.constant.ReadTipType
import io.legado.app.feature.reader.core.model.ReaderTipValueContext
import io.legado.app.feature.reader.core.model.ReaderTipValueType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 槽位文本解析器语义锚定：视觉型降级（电量图形→NN%、时间+图形电量→
 * HH:mm NN%、箭头→纯章节标题）、同型多槽只取首个、tipNone/空文本槽
 * 留空、六槽组装。上下文构建（本地化整书页数文案）在
 * ReaderTipSlotResolverContextTest（Robolectric）。
 */
class ReaderTipSlotResolverTest {

    private val context = ReaderTipValueContext(
        bookName = "书名",
        chapterTitle = "章节",
        time = "10:05",
        batteryPercent = 85,
        chapterIndex = 2,
        chapterCount = 10,
        pageIndex = 3,
        pageCount = 15,
        readProgress = "23.4%",
    )

    @Test
    fun `视觉型电量降级为百分比文本`() {
        listOf(
            ReadTipType.tipBattery,
            ReadTipType.tipBatteryInside,
            ReadTipType.tipBatteryIcon,
            ReadTipType.tipBatteryClassic,
        ).forEach { type ->
            assertEquals(ReaderTipValueType.BATTERY, ReaderTipSlotResolver.textTypeOf(type))
            assertEquals("85%", ReaderTipSlotResolver.textFor(type, context, ""))
        }
    }

    @Test
    fun `时间加图形电量降级为时间加百分比文本`() {
        listOf(ReadTipType.tipTimeBattery, ReadTipType.tipTimeBatteryClassic).forEach { type ->
            assertEquals(ReaderTipValueType.TIME_BATTERY, ReaderTipSlotResolver.textTypeOf(type))
            assertEquals("10:05 85%", ReaderTipSlotResolver.textFor(type, context, ""))
        }
    }

    @Test
    fun `箭头槽位文本即纯章节标题`() {
        listOf(
            ReadTipType.tipChapterTitleArrow,
            ReadTipType.tipChapterTitleArrowClassic,
        ).forEach { type ->
            assertEquals("章节", ReaderTipSlotResolver.textFor(type, context, ""))
        }
    }

    @Test
    fun `文本型直通`() {
        assertEquals("10:05", ReaderTipSlotResolver.textFor(ReadTipType.tipTime, context, ""))
        assertEquals("章节", ReaderTipSlotResolver.textFor(ReadTipType.tipChapterTitle, context, ""))
        assertEquals("书名", ReaderTipSlotResolver.textFor(ReadTipType.tipBookName, context, ""))
        assertEquals("4/15  23.4%", ReaderTipSlotResolver.textFor(ReadTipType.tipPageAndTotal, context, ""))
        assertEquals("3/10", ReaderTipSlotResolver.textFor(ReadTipType.tipTotalProgress1, context, ""))
        // 电量百分比本就是文本型
        assertEquals("85%", ReaderTipSlotResolver.textFor(ReadTipType.tipBatteryPercentage, context, ""))
        assertEquals("10:05 85%", ReaderTipSlotResolver.textFor(ReadTipType.tipTimeBatteryPercentage, context, ""))
    }

    @Test
    fun `自定义模板解析`() {
        assertEquals(
            "书名 章节",
            ReaderTipSlotResolver.textFor(ReadTipType.tipCustom, context, "{BookName} {ChapterTitle}"),
        )
    }

    @Test
    fun `tipNone 与空模板留空`() {
        assertEquals("", ReaderTipSlotResolver.textFor(ReadTipType.tipNone, context, ""))
        assertEquals("", ReaderTipSlotResolver.textFor(ReadTipType.tipCustom, context, ""))
    }

    @Test
    fun `同型多槽只取首个`() {
        val texts = ReaderTipSlotResolver.bandTexts(
            context,
            listOf(
                ReadTipType.tipTime to "",
                ReadTipType.tipTime to "",     // 重复：留空
                ReadTipType.tipBookName to "",
            ),
        )
        assertEquals(listOf("10:05", "", "书名"), texts)
    }

    @Test
    fun `同型不同模板不算重复`() {
        val texts = ReaderTipSlotResolver.bandTexts(
            context,
            listOf(
                ReadTipType.tipCustom to "{BookName}",
                ReadTipType.tipCustom to "{ChapterTitle}",
            ),
        )
        assertEquals(listOf("书名", "章节"), texts)
    }

    @Test
    fun `六槽组装按配置位归位`() {
        val texts = ReaderTipSlotResolver.sixSlotTexts(
            context,
            header = listOf(
                ReadTipType.tipTime to "",
                ReadTipType.tipNone to "",
                ReadTipType.tipBattery to "",
            ),
            footer = listOf(
                ReadTipType.tipChapterTitle to "",
                ReadTipType.tipBookName to "",
                ReadTipType.tipPageAndTotal to "",
            ),
        )
        assertEquals("10:05", texts.headerLeft)
        assertEquals("", texts.headerMiddle)
        assertEquals("85%", texts.headerRight)
        assertEquals("章节", texts.footerLeft)
        assertEquals("书名", texts.footerMiddle)
        assertEquals("4/15  23.4%", texts.footerRight)
    }

    @Test
    fun `进度公式未到末页封顶 99_9`() {
        // 第 3/10 章、第 4/15 页：30% + 10%*4/15 = 32.7%（近似）
        assertEquals("32.7%", ReaderTipSlotResolver.readProgress(2, 3, 15, 10))
        // 末章末页允许 100.0%
        assertEquals("100.0%", ReaderTipSlotResolver.readProgress(9, 14, 15, 10))
        // 未到末章末页但算出 100.0% → 钳 99.9%
        assertEquals("99.9%", ReaderTipSlotResolver.readProgress(9, 13, 15, 10))
        // 章还没排出页且是首页 → 0.0%
        assertEquals("0.0%", ReaderTipSlotResolver.readProgress(0, 0, 0, 10))
        // 章还没排出页、非首页 → 按章计
        assertEquals("40.0%", ReaderTipSlotResolver.readProgress(3, 0, 0, 10))
    }
}
```

- [ ] **Step 2: 运行确认失败（未定义）**

Run: `./gradlew.bat :app:testAppCoreDebugUnitTest --tests "io.legado.app.feature.reader.legacy.ReaderTipSlotResolverTest"`
Expected: 编译 FAIL——`unresolved reference: ReaderTipSlotResolver`

- [ ] **Step 3: 实现 `ReaderTipSlotResolver`**

创建 `app/src/main/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolver.kt`：

```kotlin
package io.legado.app.feature.reader.legacy

import io.legado.app.R
import io.legado.app.constant.AppConst.timeFormat
import io.legado.app.constant.ReadTipType
import io.legado.app.feature.reader.core.model.ReaderTipValueContext
import io.legado.app.feature.reader.core.model.ReaderTipValueFormatter
import io.legado.app.feature.reader.core.model.ReaderTipValueType
import io.legado.app.model.ReadBook
import splitties.init.appCtx
import java.text.DecimalFormat
import java.util.Date

/**
 * 页眉/页脚信息槽位共享解析器：完整模式装饰工厂与 E-Ink 桥共用一份，
 * 取值规则（[ReaderTipValueFormatter]）、同型多槽只取首个、空文本留空
 * 只有一处实现。
 *
 * 文本化降级（E-Ink 路径用，完整模式工厂保留图形槽）：电量图形 4 种 →
 * `NN%`；时间+图形电量 2 种 → `HH:mm NN%`；箭头 2 种的文本本就是纯
 * 章节标题（[ReaderTipValueType.CHAPTER_TITLE_ARROW] 的 format 输出）。
 */
object ReaderTipSlotResolver {

    /** 六槽最终文本（宿主侧内部形态；E-Ink 桥再映射为契约 ReaderTipSlotTexts）。 */
    data class TipSlotTexts(
        val headerLeft: String,
        val headerMiddle: String,
        val headerRight: String,
        val footerLeft: String,
        val footerMiddle: String,
        val footerRight: String,
    )

    /** ReadTipType → 值类型（自 LegacyReaderPageDecorationFactory 平移，行为不变）。 */
    fun type(type: Int): ReaderTipValueType = when (type) {
        ReadTipType.tipNone -> ReaderTipValueType.NONE
        ReadTipType.tipChapterTitle -> ReaderTipValueType.CHAPTER_TITLE
        ReadTipType.tipTime -> ReaderTipValueType.TIME
        ReadTipType.tipBattery,
        ReadTipType.tipBatteryPercentage,
        ReadTipType.tipBatteryInside,
        ReadTipType.tipBatteryIcon,
        ReadTipType.tipBatteryClassic -> ReaderTipValueType.BATTERY
        ReadTipType.tipPage -> ReaderTipValueType.PAGE
        ReadTipType.tipTotalProgress -> ReaderTipValueType.TOTAL_PROGRESS
        ReadTipType.tipPageAndTotal -> ReaderTipValueType.PAGE_AND_TOTAL
        ReadTipType.tipBookName -> ReaderTipValueType.BOOK_NAME
        ReadTipType.tipTimeBattery,
        ReadTipType.tipTimeBatteryPercentage,
        ReadTipType.tipTimeBatteryClassic -> ReaderTipValueType.TIME_BATTERY
        ReadTipType.tipTotalProgress1 -> ReaderTipValueType.CHAPTER_INDEX_AND_TOTAL
        ReadTipType.tipChapterTitleArrow,
        ReadTipType.tipChapterTitleArrowClassic -> ReaderTipValueType.CHAPTER_TITLE_ARROW
        ReadTipType.tipCustom -> ReaderTipValueType.CUSTOM
        ReadTipType.tipWholeBookPage -> ReaderTipValueType.WHOLE_BOOK_PAGE
        ReadTipType.tipWholeBookPageAndProgress -> ReaderTipValueType.WHOLE_BOOK_PAGE_AND_PROGRESS
        else -> ReaderTipValueType.NONE
    }

    /** 文本化取值时的类型映射：图形型降级，文本型直通。 */
    fun textTypeOf(type: Int): ReaderTipValueType = when (type) {
        ReadTipType.tipBattery,
        ReadTipType.tipBatteryInside,
        ReadTipType.tipBatteryIcon,
        ReadTipType.tipBatteryClassic -> ReaderTipValueType.BATTERY
        ReadTipType.tipTimeBattery,
        ReadTipType.tipTimeBatteryClassic -> ReaderTipValueType.TIME_BATTERY
        else -> type(type)
    }

    /** 单槽最终文本；tipNone/空模板留空。 */
    fun textFor(type: Int, context: ReaderTipValueContext, customTemplate: String): String =
        if (type == ReadTipType.tipNone) {
            ""
        } else {
            ReaderTipValueFormatter.format(textTypeOf(type), context, customTemplate)
        }

    /**
     * 一个条带（页眉或页脚）按配置位产出槽位文本：同（类型+模板）多槽只取
     * 首个、其余留空——对照完整模式工厂 tips() 的 seen 去重语义；空串 =
     * 该槽不绘制。
     */
    fun bandTexts(context: ReaderTipValueContext, slots: List<Pair<Int, String>>): List<String> {
        val seen = mutableSetOf<Pair<Int, String>>()
        return slots.map { (type, template) ->
            if (!seen.add(type to template)) "" else textFor(type, context, template)
        }
    }

    /** 六槽组装（header/footer 各按 左/中/右 顺序传入槽位配置）。 */
    fun sixSlotTexts(
        context: ReaderTipValueContext,
        header: List<Pair<Int, String>>,
        footer: List<Pair<Int, String>>,
    ): TipSlotTexts {
        val headerTexts = bandTexts(context, header)
        val footerTexts = bandTexts(context, footer)
        fun at(texts: List<String>, index: Int): String = texts.getOrElse(index) { "" }
        return TipSlotTexts(
            headerLeft = at(headerTexts, 0),
            headerMiddle = at(headerTexts, 1),
            headerRight = at(headerTexts, 2),
            footerLeft = at(footerTexts, 0),
            footerMiddle = at(footerTexts, 1),
            footerRight = at(footerTexts, 2),
        )
    }

    /**
     * 阅读进度，逐条对照旧 `TextPage.readProgress`（自工厂平移，行为不变）：
     * `chapterSize == 0` 或"首页且章还没排出页" → `0.0%`；章还没排出页 → 按章计；
     * 其余按"章 + 页"计，且未真正读完时不允许显示 100%。
     */
    fun readProgress(chapterIndex: Int, pageIndex: Int, pageCount: Int, chapterCount: Int): String {
        if (chapterCount == 0 || (pageCount == 0 && chapterIndex == 0)) return ZERO_PROGRESS
        val formatter = DecimalFormat("0.0%")
        if (pageCount == 0) {
            return formatter.format((chapterIndex + 1.0f) / chapterCount.toDouble())
        }
        var progress = formatter.format(
            chapterIndex * 1.0f / chapterCount +
                    1.0f / chapterCount * (pageIndex + 1) / pageCount.toDouble(),
        )
        if (progress == "100.0%" && (chapterIndex + 1 != chapterCount || pageIndex + 1 != pageCount)) {
            progress = "99.9%"
        }
        return progress
    }

    /**
     * 槽位取值上下文（自工厂 create() 内联块平移，行为不变）：时间空则现取、
     * 电量钳 0..100、章数至少 1、页数未知给 `-`、整书页数文案本地化并带
     * `- / -` 兜底。整书页数状态取 [ReadBook.getWholeBookPageState]（两模式
     * 共用同一份全局分页状态）。
     */
    fun buildContext(
        bookName: String,
        chapterTitle: String,
        chapterIndex: Int,
        pageIndex: Int,
        chapterPageCount: Int,
        chapterCount: Int,
        time: String,
        batteryPercent: Int,
    ): ReaderTipValueContext {
        val wholeBook = ReadBook.getWholeBookPageState(chapterIndex, pageIndex)
        return ReaderTipValueContext(
            bookName = bookName,
            chapterTitle = chapterTitle,
            time = time.ifBlank { timeFormat.format(Date()) },
            batteryPercent = batteryPercent.coerceIn(0, 100),
            chapterIndex = chapterIndex,
            chapterCount = chapterCount.coerceAtLeast(1),
            pageIndex = pageIndex,
            pageCount = chapterPageCount.coerceAtLeast(1),
            pageCountText = if (chapterPageCount <= 0) {
                ReaderTipValueFormatter.UNKNOWN_PAGE_COUNT
            } else {
                chapterPageCount.toString()
            },
            wholeBookPageText = when {
                wholeBook == null -> appCtx.getString(R.string.whole_book_page_unavailable)
                !wholeBook.estimated && wholeBook.allPreviousChaptersExact -> appCtx.getString(
                    R.string.whole_book_page_format,
                    wholeBook.currentPage,
                    wholeBook.totalPages,
                )
                else -> appCtx.getString(
                    R.string.whole_book_page_estimated_format,
                    wholeBook.currentPage.toString(),
                    wholeBook.totalPages.toString(),
                )
            },
            wholeBookPageIndex = wholeBook?.currentPage,
            wholeBookPageCount = wholeBook?.totalPages,
        )
    }

    private const val ZERO_PROGRESS = "0.0%"
}
```

- [ ] **Step 4: 运行纯 JVM 测试确认通过**

Run: `./gradlew.bat :app:testAppCoreDebugUnitTest --tests "io.legado.app.feature.reader.legacy.ReaderTipSlotResolverTest"`
Expected: PASS（9 个用例）

- [ ] **Step 5: 写失败测试（Robolectric 上下文构建部分）**

创建 `app/src/test/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolverContextTest.kt`：

```kotlin
package io.legado.app.feature.reader.legacy

import io.legado.app.R
import io.legado.app.constant.ReadTipType
import io.legado.app.feature.reader.core.model.ReaderTipValueFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import splitties.init.appCtx

/**
 * buildContext 的上下文组装：页数未知给 `-`、整书页数无状态时本地化
 * `- / -` 兜底、时间空则现取、电量钳制。整书页数文案断言用
 * appCtx.getString 同源对照（锁定的是分支行为不是文案翻译）。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderTipSlotResolverContextTest {

    @Test
    fun `章未排出页时页数未知给横杠且进度按章计`() {
        val context = ReaderTipSlotResolver.buildContext(
            bookName = "书名",
            chapterTitle = "章节",
            chapterIndex = 3,
            pageIndex = 0,
            chapterPageCount = 0,
            chapterCount = 10,
            time = "10:05",
            batteryPercent = 85,
        )
        assertEquals(ReaderTipValueFormatter.UNKNOWN_PAGE_COUNT, context.pageCountText)
        assertEquals("40.0%", context.readProgress)
        assertEquals("4/-", ReaderTipSlotResolver.textFor(ReadTipType.tipPage, context, ""))
    }

    @Test
    fun `无整书分页状态时整书页数兜底文案`() {
        val context = ReaderTipSlotResolver.buildContext(
            bookName = "书名",
            chapterTitle = "章节",
            chapterIndex = 0,
            pageIndex = 0,
            chapterPageCount = 5,
            chapterCount = 1,
            time = "10:05",
            batteryPercent = 85,
        )
        assertEquals(
            appCtx.getString(R.string.whole_book_page_unavailable),
            ReaderTipSlotResolver.textFor(ReadTipType.tipWholeBookPage, context, ""),
        )
    }

    @Test
    fun `时间空则现取且电量超界钳制`() {
        val context = ReaderTipSlotResolver.buildContext(
            bookName = "书名",
            chapterTitle = "章节",
            chapterIndex = 0,
            pageIndex = 0,
            chapterPageCount = 5,
            chapterCount = 1,
            time = "",
            batteryPercent = 120,
        )
        org.junit.Assert.assertTrue("时间空应现取系统时间", context.time.isNotEmpty())
        assertEquals(100, context.batteryPercent)
    }
}
```

- [ ] **Step 6: 运行 Robolectric 测试确认通过**

Run: `./gradlew.bat :app:testAppCoreDebugUnitTest --tests "io.legado.app.feature.reader.legacy.ReaderTipSlotResolverContextTest"`
Expected: PASS（3 个用例）

- [ ] **Step 7: 工厂改为共用解析器（行为保持）**

修改 `LegacyReaderPageDecorationFactory.kt`：

1. `create()` 内的 `ReaderTipValueContext(...)` 内联块（`val settings = ...` 之后到 `)` 结束，原约 115-151 行）整体替换为：

```kotlin
        val settings = readSettings.currentSettings
        val context = ReaderTipSlotResolver.buildContext(
            bookName = ReadBook.book?.name.orEmpty(),
            chapterTitle = page.chapterTitle,
            chapterIndex = page.id.chapterIndex,
            pageIndex = page.id.pageIndex,
            chapterPageCount = chapterPageCount,
            chapterCount = ReadBook.chapterSize,
            time = time,
            batteryPercent = batteryPercent,
        )
```

（`settings` 若仅书签角标使用则保留；`dividerColor` 等后续代码不动。）

2. 私有 `type()` 函数体替换为委托：

```kotlin
    private fun type(type: Int): ReaderTipValueType = ReaderTipSlotResolver.type(type)
```

3. 删除私有 `readProgress()` 与 `ZERO_PROGRESS`（已平移至解析器）。
4. 顶部补 import：`io.legado.app.feature.reader.core.model.ReaderTipValueType` 已有则不动；无需新增其它（`ReaderTipSlotResolver` 同包）。

- [ ] **Step 8: 宿主受影响测试回归**

Run: `./gradlew.bat :app:testAppCoreDebugUnitTest --tests "io.legado.app.feature.reader.legacy.*" --tests "io.legado.app.feature.reader.*"`
Expected: PASS（既有 + 新增全绿；工厂无既有专项测试，legacy/feature.reader 相邻套件确认无回归）

- [ ] **Step 9: Commit（宿主仓库）**

```bash
git add app/src/main/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolver.kt \
        app/src/main/java/io/legado/app/feature/reader/legacy/LegacyReaderPageDecorationFactory.kt \
        app/src/test/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolverTest.kt \
        app/src/test/java/io/legado/app/feature/reader/legacy/ReaderTipSlotResolverContextTest.kt
git commit -m "refactor(reader): 抽取 ReaderTipSlotResolver 共享槽位解析器（行为保持，含文本化降级与测试）"
```

---

### Task 3: 宿主——桥实现 `headerFooterTipTexts()`

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt`（`headerFooterVisibility()` 实现之后，约 836 行后）

**Interfaces:**
- Consumes: Task 1 契约类型 `io.legado.app.eink.contract.ReaderTipSlotTexts`；Task 2 的 `ReaderTipSlotResolver.sixSlotTexts/buildContext`；`ReadBookConfig.tipHeader*/customTip*`；`chapterPager.currentChapterPageSize/currentPageSnapshot()`；`ReadBook.durChapterIndex/durPageIndex/chapterSize/book`。
- Produces: `ReaderEngineImpl` 实现的 `override fun headerFooterTipTexts(): ReaderTipSlotTexts`。

- [ ] **Step 1: 实现**

在 `ReaderEngineImpl.kt` 的 `headerFooterVisibility()` 实现后新增（文件顶部补 import：`android.content.Intent`、`android.content.IntentFilter`、`android.os.BatteryManager`、`io.legado.app.eink.contract.ReaderTipSlotTexts`、`io.legado.app.feature.reader.legacy.ReaderTipSlotResolver`、`io.legado.app.constant.AppConst.timeFormat`、`java.util.Date`）：

```kotlin
    override fun headerFooterTipTexts(): ReaderTipSlotTexts {
        // 当前页上下文：快照未就绪（排版窗口）时章标题空、页数 0——槽位
        // 文本按完整模式同一规则取值（页数未知给 `-`、进度按章计）
        val context = ReaderTipSlotResolver.buildContext(
            bookName = ReadBook.book?.name.orEmpty(),
            chapterTitle = chapterPager.currentPageSnapshot()?.title.orEmpty(),
            chapterIndex = ReadBook.durChapterIndex,
            pageIndex = ReadBook.durPageIndex,
            chapterPageCount = chapterPager.currentChapterPageSize,
            chapterCount = ReadBook.chapterSize,
            time = timeFormat.format(Date()),
            batteryPercent = currentBatteryPercent(),
        )
        val texts = ReaderTipSlotResolver.sixSlotTexts(
            context,
            header = listOf(
                ReadBookConfig.tipHeaderLeft to ReadBookConfig.customTipHeaderLeft,
                ReadBookConfig.tipHeaderMiddle to ReadBookConfig.customTipHeaderMiddle,
                ReadBookConfig.tipHeaderRight to ReadBookConfig.customTipHeaderRight,
            ),
            footer = listOf(
                ReadBookConfig.tipFooterLeft to ReadBookConfig.customTipFooterLeft,
                ReadBookConfig.tipFooterMiddle to ReadBookConfig.customTipFooterMiddle,
                ReadBookConfig.tipFooterRight to ReadBookConfig.customTipFooterRight,
            ),
        )
        return ReaderTipSlotTexts(
            headerLeft = texts.headerLeft,
            headerMiddle = texts.headerMiddle,
            headerRight = texts.headerRight,
            footerLeft = texts.footerLeft,
            footerMiddle = texts.footerMiddle,
            footerRight = texts.footerRight,
        )
    }

    /** 电量查询（与模块 updateTipInfo 同一机制：sticky BATTERY_CHANGED 查询注册）。 */
    private fun currentBatteryPercent(): Int {
        val intent = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else 100
    }
```

- [ ] **Step 2: 编译宿主**

Run: `./gradlew.bat :app:compileAppCoreDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit（宿主仓库）**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt
git commit -m "feat(eink-bridge): headerFooterTipTexts 槽位文本实现——完整模式 6 槽解析、视觉型文本化降级"
```

---

### Task 4: eink-lib——VM 拉取槽位文本 + UiState 选择器 + 回落

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderTipSlotsFlowTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ReaderTipSlotTexts`（`io.legado.app.eink.contract`）与接口方法。
- Produces（Task 5 依赖）:
  - `ReaderUiState.tipSlots: ReaderTipSlotTexts?`（默认 null）
  - UiState 计算属性：`headerLeftText/headerMiddleText/headerRightText/footerLeftText/footerMiddleText/footerRightText`（`tipSlots` 非 null 用槽位文本，null 回落 `headerTime`/`"NN%"`/`chapterTitle`/`pageAndTotal`，中槽回落空串）

- [ ] **Step 1: 写失败测试**

创建 `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderTipSlotsFlowTest.kt`：

```kotlin
package io.legado.app.eink.feature.reader

import android.app.Application
import io.legado.app.eink.contract.BookHandle
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.ReaderBookSnapshot
import io.legado.app.eink.contract.ReaderEngine
import io.legado.app.eink.contract.ReaderEngineCallback
import io.legado.app.eink.contract.ReaderHeaderFooterVisibility
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPrepareResult
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.contract.ReaderTipSlotTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 槽位文本流灌回归：引擎 headerFooterTipTexts 非 null 时 uiState.tipSlots
 * 如实承载（完整模式槽位配置遵从）；旧宿主默认 null 时回落字段照旧
 * （headerTime 仍由时钟对表路径写入，渲染层回落硬装配）。
 *
 * 端口注入经反射替换注册表 _readerEngine/_globalSettings（同
 * ReaderHeaderClockResumeTest）；Main 换 StandardTestDispatcher，
 * runCurrent 推进 onActivityResumed 时钟启动的立即 updateTipInfo。
 */
class ReaderTipSlotsFlowTest {

    private val engine = TipSlotsEngine()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        injectEngines(engine, TipSlotsSettings())
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        injectEngines(null, null)
    }

    @Test
    fun `引擎槽位文本写入 uiState`() {
        engine.tipSlotTexts = ReaderTipSlotTexts(
            headerLeft = "书名", headerMiddle = "", headerRight = "10:05",
            footerLeft = "章节", footerMiddle = "60%", footerRight = "4/15",
        )
        val vm = ReaderViewModel(Application())
        vm.onActivityResumed()
        testDispatcher.scheduler.runCurrent()
        val slots = vm.uiState.value.tipSlots
        assertEquals("书名", slots?.headerLeft)
        assertEquals("", slots?.headerMiddle)
        assertEquals("10:05", slots?.headerRight)
        assertEquals("章节", slots?.footerLeft)
        assertEquals("60%", slots?.footerMiddle)
        assertEquals("4/15", slots?.footerRight)
    }

    @Test
    fun `旧宿主 null 回落且时间字段照旧`() {
        engine.tipSlotTexts = null
        val vm = ReaderViewModel(Application())
        vm.onActivityResumed()
        testDispatcher.scheduler.runCurrent()
        assertNull(vm.uiState.value.tipSlots)
        assertEquals("10:00", vm.uiState.value.headerTime)
    }

    @Test
    fun `uiState 槽位选择器回落映射`() {
        // 直接构造 UiState 锁定选择器：tipSlots 优先、null 回落硬装配
        val withSlots = ReaderUiState(
            tipSlots = ReaderTipSlotTexts(
                headerLeft = "L", headerMiddle = "M", headerRight = "R",
                footerLeft = "FL", footerMiddle = "FM", footerRight = "FR",
            ),
        )
        assertEquals("L", withSlots.headerLeftText)
        assertEquals("M", withSlots.headerMiddleText)
        assertEquals("R", withSlots.headerRightText)
        assertEquals("FL", withSlots.footerLeftText)
        assertEquals("FM", withSlots.footerMiddleText)
        assertEquals("FR", withSlots.footerRightText)

        val legacy = ReaderUiState(
            headerTime = "10:00",
            batteryPercent = 85,
            chapterTitle = "章节",
            readProgress = "12.3%",
        )
        // 无页数信息时 pageIndicator 为空、pageAndTotal 仅进度
        assertEquals("10:00", legacy.headerLeftText)
        assertEquals("", legacy.headerMiddleText)
        assertEquals("85%", legacy.headerRightText)
        assertEquals("章节", legacy.footerLeftText)
        assertEquals("", legacy.footerMiddleText)
        assertEquals("12.3%", legacy.footerRightText)
    }

    private fun injectEngines(engine: ReaderEngine?, settings: GlobalSettings?) {
        EInkEngineRegistry::class.java.getDeclaredField("_readerEngine").apply {
            isAccessible = true
        }.set(EInkEngineRegistry, engine)
        EInkEngineRegistry::class.java.getDeclaredField("_globalSettings").apply {
            isAccessible = true
        }.set(EInkEngineRegistry, settings)
    }

    private class TipSlotsSettings : GlobalSettings {
        override var autoRefreshBook = false
        override var defaultToRead = false
        override var volumeKeyPage = false
        override var useDefaultCover = false
        override var pullDownBookmark = false
        override var readerSourceInfoBar = false
        override var hideStatusBar = false
        override var showReviewBubbles = true
        override val threadCount = 1
        override val useAntiAlias = false
        override val preDownloadChapterCount = 0
        override val changeSourceCheckAuthor = true
        override var fontScaleSetting: Int? = null
        override var recentFontPathsEncoding = ""
    }

    /** 槽位文本可控的最小引擎桩（成员清单同 ReaderHeaderClockResumeTest.HeaderClockEngine）。 */
    private class TipSlotsEngine : ReaderEngine {
        var tipSlotTexts: ReaderTipSlotTexts? = null

        override fun applyStyle(style: ReaderTextStyle) = Unit
        override fun currentStyle(): ReaderTextStyle = ReaderTextStyle()
        override fun register(callback: ReaderEngineCallback) = Unit
        override fun unregister(callback: ReaderEngineCallback) = Unit
        override fun isRegistered(callback: ReaderEngineCallback): Boolean = false
        override fun saveReadingProgress() = Unit
        override val sessionBook: ReaderBookSnapshot? get() = null
        override val sessionBookUrl: String? get() = null
        override val chapterSize: Int get() = 0
        override val currentChapterIndex: Int get() = 0
        override val currentPageIndex: Int get() = 0
        override val engineMessage: String? get() = null
        override val hasLaidOutPages: Boolean get() = false
        override val currentChapterPageSize: Int get() = 0
        override val currentPage(): ReaderPageSnapshot? = null
        override fun loadBook(book: BookHandle) = Unit
        override fun reloadBook(book: BookHandle) = Unit
        override fun setInBookshelf(value: Boolean) = Unit
        override fun clearEngineMessage() = Unit
        override fun loadContent(resetPageOffset: Boolean) = Unit
        override fun loadContent(chapterIndex: Int, resetPageOffset: Boolean) = Unit
        override fun refreshToc() = Unit
        override suspend fun resolveBook(bookUrl: String): ReaderBookSnapshot? = null
        override suspend fun prepareBookData(book: BookHandle): ReaderPrepareResult =
            ReaderPrepareResult.Success
        override fun nextPage(): Boolean = false
        override fun prevPage(): Boolean = false
        override fun skipToPage(pageIndex: Int) = Unit
        override fun nextChapter(): Boolean = false
        override fun prevChapter(): Boolean = false
        override fun jumpToPosition(chapterIndex: Int, chapterPos: Int): Boolean = false
        override val autoReadIntervalSec: Int get() = 10
        override suspend fun setAutoReadIntervalSec(value: Int) = Unit
        override suspend fun refreshCurrentChapter() = Unit
        override fun startCache(count: Int, cacheAll: Boolean): Boolean? = null
        override suspend fun addSessionBookToShelf(): Boolean? = null
        override suspend fun removeSessionBookFromShelf(): Boolean? = null
        override fun updateViewSize(
            width: Int,
            height: Int,
            contentPadding: io.legado.app.eink.contract.ReaderEngine.ReaderContentPadding,
        ) = Unit
        override fun relayout() = Unit
        override val pageTouchSlop: Int get() = 0
        override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
            ReaderHeaderFooterVisibility(headerVisible = true, footerVisible = true)
        override fun formatTimeNow(): String = "10:00"
        override fun headerFooterTipTexts(): ReaderTipSlotTexts? = tipSlotTexts
    }
}
```

注意：若 `ReaderUiState` 构造需要其余必填参数导致编译错，按编译器提示补默认值参数（本计划外字段均带默认值，`pageAndTotal` 是计算属性非构造参数）。

- [ ] **Step 2: 运行确认失败**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderTipSlotsFlowTest"`
Expected: 编译 FAIL——`tipSlots`/`headerLeftText` 等未定义

- [ ] **Step 3: 实现 UiState 字段与选择器、updateTipInfo 拉取**

修改 `ReaderViewModel.kt`：

1. `ReaderUiState` 在 `batteryPercent: Int = 100,`（约 117 行）之后新增：

```kotlin
    /** 页眉/页脚槽位文本（引擎按完整模式槽位配置解析）；null = 旧宿主，
     *  渲染回落自有硬装配（时间左/电量右、章节标题左/页数进度右）。 */
    val tipSlots: ReaderTipSlotTexts? = null,
```

2. `ReaderUiState` 的 `pageAndTotal` 计算属性之后（约 143 行 `}` 前）新增选择器：

```kotlin
    /** 页眉槽位文本：引擎解析可用时遵从完整模式配置，否则回落硬装配。 */
    val headerLeftText: String get() = tipSlots?.headerLeft ?: headerTime
    val headerMiddleText: String get() = tipSlots?.headerMiddle ?: ""
    val headerRightText: String get() = tipSlots?.headerRight ?: "${batteryPercent}%"

    /** 页脚槽位文本：同上；中槽无回落来源恒空。 */
    val footerLeftText: String get() = tipSlots?.footerLeft ?: chapterTitle
    val footerMiddleText: String get() = tipSlots?.footerMiddle ?: ""
    val footerRightText: String get() = tipSlots?.footerRight ?: pageAndTotal
```

3. `updateTipInfo()`（约 471-486 行）在 `val tip = engine.headerFooterVisibility()` 后加一行拉取、并写入 copy：

```kotlin
        val tip = engine.headerFooterVisibility()
        val tipSlots = engine.headerFooterTipTexts()
```

`_uiState.update` 的 `it.copy(...)` 增加一项 `tipSlots = tipSlots,`（放在 `batteryPercent = battery,` 之后）。

4. `ReaderUiState` 注释行「按 View 版 ReadTipConfig 规则渲染，不开放设置」（约 113 行）改为「内容遵从完整模式槽位配置（tipSlots），旧宿主回落硬装配」。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderTipSlotsFlowTest"`
Expected: PASS（3 个用例）

- [ ] **Step 5: 模块全量回归（本任务动了 VM）**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（含既有 ReaderHeaderClockResumeTest 等）

- [ ] **Step 6: Commit（模块仓库）**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderTipSlotsFlowTest.kt
git commit -m "feat(eink-reader): VM 拉取页眉页脚槽位文本入 uiState——完整模式配置遵从、旧宿主 null 回落"
```

---

### Task 5: eink-lib——三槽渲染（barrier 语义镜像宿主）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderTipRowDraw.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`（`ReaderHeader` 约 2100-2154、`ReaderFooter` 约 2165-2233）
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderTipRowBarrierTest.kt`

**Interfaces:**
- Consumes: Task 4 的 UiState 选择器 `headerLeftText` 等 6 个。
- Produces:
  - `internal fun tipRowBarrierX(rowLeftPx: Float, rowRightPx: Float, middleWidthPx: Float?, rightWidthPx: Float?): Float`
  - `internal fun drawTipRow(canvas: Canvas, paint: TextPaint, rowLeftPx: Float, rowRightPx: Float, baselineY: Float, leftText: String, middleText: String, rightText: String)`
  - `drawTipRowLeftRight` 保留（屏保继续用）。

- [ ] **Step 1: 写失败测试（barrier 纯函数）**

创建 `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderTipRowBarrierTest.kt`：

```kotlin
package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三槽 barrier 语义（镜像宿主 ReaderCanvasSurface.drawTipRow 的旧版三槽
 * 约束）：左槽可用右界 = min(可见中槽起始边, 右槽起始边)；槽不可见
 * （width null）不参与；无邻槽 = 行右缘。
 */
class ReaderTipRowBarrierTest {

    private val left = 100f
    private val right = 1000f

    @Test
    fun `无邻槽时 barrier 为行右缘`() {
        assertEquals(right, tipRowBarrierX(left, right, null, null), 0f)
    }

    @Test
    fun `仅右槽时 barrier 为右槽起始边`() {
        assertEquals(700f, tipRowBarrierX(left, right, null, 300f), 0f)
    }

    @Test
    fun `仅中槽时 barrier 为中槽起始边`() {
        // 中槽宽 200：中心 550，起始边 450
        assertEquals(450f, tipRowBarrierX(left, right, 200f, null), 0f)
    }

    @Test
    fun `中右都在时取更小者`() {
        // 右槽起始边 700；中槽宽 600：中心 550、起始边 250 → min = 250
        assertEquals(250f, tipRowBarrierX(left, right, 600f, 300f), 0f)
    }

    @Test
    fun `零宽中槽仍参与 barrier`() {
        // 与宿主一致：槽存在即参与（全宽测量）——中槽零宽起始边 550，
        // 右槽 300 宽起始边 700，min = 550；空文本槽由调用方不传入
        assertEquals(550f, tipRowBarrierX(left, right, 0f, 300f), 0f)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderTipRowBarrierTest"`
Expected: 编译 FAIL——`tipRowBarrierX` 未定义

- [ ] **Step 3: 实现 barrier 与三槽绘制**

在 `ReaderTipRowDraw.kt` 的 `drawTipRowLeftRight` 之后新增（import 已有 `TextUtils`/`Paint`/`Canvas`/`TextPaint`）：

```kotlin
/**
 * 三槽 barrier：左槽可用右界 = min(可见中槽起始边, 右槽起始边)，槽宽为
 * null = 该槽不参与；无邻槽 = 行右缘。镜像宿主 ReaderCanvasSurface.
 * drawTipRow 对照旧 view_book_page.xml 的三槽约束（左槽 weight + barrier
 * 省略；中/右 wrap_content 不受邻槽约束）。宽度一律传**未省略全宽**
 * （宿主同款：右槽过长时左槽可用宽直接压到 0 不画）。
 */
internal fun tipRowBarrierX(
    rowLeftPx: Float,
    rowRightPx: Float,
    middleWidthPx: Float?,
    rightWidthPx: Float?,
): Float {
    val rowWidth = rowRightPx - rowLeftPx
    return listOfNotNull(
        middleWidthPx?.let { rowLeftPx + (rowWidth - it) / 2f },
        rightWidthPx?.let { rowRightPx - it },
    ).minOrNull() ?: rowRightPx
}

/**
 * 三槽绘制（左 START / 中 CENTER / 右 END）：中槽居中、右槽右对齐，两者
 * 只在整行宽度处省略（长文案会压到邻槽上，宿主同款不额外让位）；左槽
 * 在 barrier 处省略。空串槽不绘制。
 */
internal fun drawTipRow(
    canvas: Canvas,
    paint: TextPaint,
    rowLeftPx: Float,
    rowRightPx: Float,
    baselineY: Float,
    leftText: String,
    middleText: String,
    rightText: String,
) {
    val rowWidth = (rowRightPx - rowLeftPx).coerceAtLeast(0f)
    // barrier 用未省略全宽（镜像宿主 tipWidth 语义）
    val barrier = tipRowBarrierX(
        rowLeftPx = rowLeftPx,
        rowRightPx = rowRightPx,
        middleWidthPx = middleText.takeIf { it.isNotEmpty() }?.let { paint.measureText(it) },
        rightWidthPx = rightText.takeIf { it.isNotEmpty() }?.let { paint.measureText(it) },
    )
    if (middleText.isNotEmpty()) {
        val text = TextUtils.ellipsize(middleText, paint, rowWidth, TextUtils.TruncateAt.END)
        if (text.isNotEmpty()) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(text, 0, text.length, rowLeftPx + rowWidth / 2f, baselineY, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }
    if (rightText.isNotEmpty()) {
        val text = TextUtils.ellipsize(rightText, paint, rowWidth, TextUtils.TruncateAt.END)
        if (text.isNotEmpty()) {
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(text, 0, text.length, rowRightPx, baselineY, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }
    if (leftText.isNotEmpty()) {
        val available = (barrier - rowLeftPx).coerceAtLeast(0f)
        if (available > 0f) {
            val text = TextUtils.ellipsize(leftText, paint, available, TextUtils.TruncateAt.END)
            if (text.isNotEmpty()) {
                canvas.drawText(text, 0, text.length, rowLeftPx, baselineY, paint)
            }
        }
    }
}
```

- [ ] **Step 4: 运行 barrier 测试确认通过**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderTipRowBarrierTest"`
Expected: PASS（5 个用例）

- [ ] **Step 5: ReaderHeader/ReaderFooter 接线三槽**

修改 `ReaderScreen.kt`：

1. `ReaderHeader` 内 `drawTipRowLeftRight(...)` 调用（约 2128-2136 行）替换为：

```kotlin
        drawTipRow(
            canvas = native,
            paint = paint,
            rowLeftPx = rowLeftPx,
            rowRightPx = rowRightPx,
            baselineY = baseline,
            leftText = state.headerLeftText,
            middleText = state.headerMiddleText,
            rightText = state.headerRightText,
        )
```

2. `ReaderFooter` 内 `drawTipRowLeftRight(...)` 调用（约 2223-2231 行）替换为：

```kotlin
        drawTipRow(
            canvas = native,
            paint = paint,
            rowLeftPx = rowLeftPx,
            rowRightPx = rowRightPx,
            baselineY = baseline,
            leftText = state.footerLeftText,
            middleText = state.footerMiddleText,
            rightText = state.footerRightText,
        )
```

3. KDoc 更新：`ReaderHeader` KDoc（约 2085-2098 行）与 `ReaderFooter` KDoc（约 2156-2163 行）删去「内容项锁定/时间(左)+电量%(右)/章节标题(左)/页数及进度(右)」表述，改为：

```
 * 内容遵从完整模式槽位配置（uiState.tipSlots，旧宿主回落：时间左/电量
 * 右、章节标题左/页数及进度右）；中槽居中，左槽在 barrier 处省略、中/右
 * 槽只在整行宽度处省略（镜像宿主三槽约束，见 drawTipRow）。
```

- [ ] **Step 6: 模块全量测试**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit（模块仓库）**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderTipRowDraw.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderTipRowBarrierTest.kt
git commit -m "feat(eink-reader): 页眉页脚三槽渲染——内容遵从完整模式槽位配置，barrier 语义镜像宿主"
```

---

### Task 6: 指针抬升 + 验证集 + 设计文档状态

**Files:**
- Modify: `docs/dev/eink-header-footer-tip-slots.md`（状态行）
- Modify: 宿主 `eink-lib` 子模块指针

**Interfaces:**
- Consumes: Task 1-5 全部提交。

- [ ] **Step 1: 更新设计文档状态行**

`docs/dev/eink-header-footer-tip-slots.md` 第 3 行 `状态：设计已认可，待实现` 改为 `状态：已实现（模块 <Task5 哈希> + 宿主 <Task3 哈希>，真机复核待做）`（哈希用实际提交号）。

- [ ] **Step 2: 抬指针提交（宿主仓库，只 stage eink-lib 与文档）**

```bash
git add eink-lib docs/dev/eink-header-footer-tip-slots.md
git commit -m "chore(eink): 抬 eink-lib 指针至 eink/lib <Task5 哈希>（页眉页脚槽位遵从完整模式配置——宿主解析文本跨桥 + 模块三槽渲染）"
```

- [ ] **Step 3: 宿主主验证集**

Run: `./gradlew.bat :app:testAppCoreDebugUnitTest lintAppCoreDebug verifyConfigArchitecture assembleAppCoreDebug --continue --no-configuration-cache`
Expected: BUILD SUCCESSFUL（宿主动了工厂/桥，跑全量集）

- [ ] **Step 4: 空白检查**

Run: `git diff --check`（两仓库，若有输出按提示修正）

- [ ] **Step 5: 交付说明（不 push）**

列明：修改职责范围、关键取舍（方案 A/降级规则/默认配置零变化）、实际运行验证与结果、未验证风险（真机三槽目验、屏保内容仍硬编码待后续刀、视觉型槽位文本化降级非图形）。

---

## Self-Review 记录

- 规格覆盖：设计 §4.1 契约（Task 1）、§4.2 解析器抽取+桥（Task 2/3）、§4.3 VM（Task 4）、§4.4 三槽渲染（Task 5）、§5 兼容（默认 null 回落，Task 1/4）、§6 测试（Task 2/4/5）均有对应任务；§4.5 不在范围项无任务（正确）。
- 类型一致性：`ReaderTipSlotTexts` 字段名在 Task 1/3/4 一致；`TipSlotTexts`（宿主内部）字段与之一致；`tipRowBarrierX`/`drawTipRow` 签名在 Task 5 内一致。
- 占位符扫描：Task 2 Step 5 第三用例的写法歧义已内联修正说明，其余无 TBD。
