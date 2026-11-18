# 排版设置归一弹框（ReaderStyleConfigDialog）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 eink 阅读排版设置的三个互斥弹框（字体/信息/边距）归一为单弹框，支持「按功能 Tab / 按区域分组全景+锚点」双聚合轴，三入口直达功能轴对应 Tab。

**Architecture:** 纯模块侧 UI 容器重构（eink-lib `modules/eink` feature/reader）。新容器 `ReaderStyleConfigDialog` 组合三个既有弹框的内容组件（去 EInkDialog 外壳后的 Content 版本）与新建的区域分组视图；行组件抽到共享文件 `ReaderStyleRows.kt` 供功能轴与区域轴复用；VM/契约/宿主桥零改动。

**Tech Stack:** Jetpack Compose（模块自有 E-Ink 设计系统：EInkDialog/EInkButton/EInkSliderRow/PanelTabRow），JUnit4 纯函数单测。

**Spec:** `docs/superpowers/specs/2026-11-19-reader-style-config-dialog-design.md`（行清单 §4.3 是唯一事实源）

## Global Constraints

- 行集不变：**不新增任何参数设置行**；所有行来自 spec §4.3 现状行清单的迁移/复用
- VM、契约（contract/）、宿主桥（app/eink/bridge）**零改动**
- 模块测试基线 462 个不回归；新增纯函数单测
- 提交在 eink-lib 子模块（分支 eink/lib），宿主仓只推进指针；推送顺序先子模块后宿主（本轮不推送）
- 验证命令（在 `eink-lib/` 下）：`./gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest --console=plain -Pkotlin.daemon.jvmargs=-Xmx4g`
- 命名沿用契约总账规范（无新参数 id）；中文 UI 文案与既有弹框一致（「排版设置」「按功能」「按区域」「正文/标题/页眉/页脚」）
- 文中「锚点字符串」指 grep 可定位的代码片段；执行时行号可能漂移，以锚点为准

---

### Task 1: 轴模型与分组可见性纯函数（TDD）

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleConfigDialog.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderStyleConfigModelTest.kt`

**Interfaces:**
- Consumes: `io.legado.app.eink.contract.ReaderStyleCatalog`、`ReaderStyleParamIds`（既有）
- Produces: `StyleAxis` / `StyleFunctionTab` / `StyleRegionGroup` 枚举；`regionGroupVisible(catalog: ReaderStyleCatalog, group: StyleRegionGroup): Boolean`；`StyleRegionGroup.displayLabel: String`（后续任务的容器与视图直接使用）

- [ ] **Step 1: 写失败测试**

```kotlin
package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.FallbackReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleConfigModelTest {

    @Test
    fun `回落目录下仅正文组可见`() {
        // Fallback 目录只含正文五标量+三组边距：仅 BODY 组可见（标题/页眉/页脚无行）
        val catalog = FallbackReaderStyleCatalog.create()
        assertTrue(regionGroupVisible(catalog, StyleRegionGroup.BODY))
        assertFalse(regionGroupVisible(catalog, StyleRegionGroup.TITLE))
        assertFalse(regionGroupVisible(catalog, StyleRegionGroup.HEADER))
        assertFalse(regionGroupVisible(catalog, StyleRegionGroup.FOOTER))
    }

    @Test
    fun `全量目录下四组均可见`() {
        val catalog = ReaderStyleCatalog(
            listOf(
                ReaderStyleParam.Stepped(Ids.BODY_SIZE, true, true, 5f, 50f, 20f),
                ReaderStyleParam.Stepped(Ids.BODY_LETTER_SPACING, true, true, -0.5f, 0.5f, 0.1f),
                ReaderStyleParam.Stepped(Ids.BODY_INDENT, true, true, 0f, 4f, 2f),
                ReaderStyleParam.Stepped(Ids.BODY_LINE_SPACING, true, true, 0f, 20f, 12f),
                ReaderStyleParam.Stepped(Ids.BODY_PARAGRAPH_SPACING, true, true, 0f, 20f, 2f),
                ReaderStyleParam.Choice(Ids.TITLE_MODE, true, true, emptyList(), 0),
                ReaderStyleParam.Stepped(Ids.TITLE_SIZE, true, true, 8f, 60f, 20f),
                ReaderStyleParam.Choice(Ids.HEADER_MODE, true, true, emptyList(), 0),
                ReaderStyleParam.Stepped(Ids.HEADER_SIZE, true, true, 0f, 100f, 12f),
                ReaderStyleParam.Toggle(Ids.FOOTER_VISIBILITY, true, true, true),
                ReaderStyleParam.Stepped(Ids.FOOTER_SIZE, true, true, 0f, 100f, 12f),
                ReaderStyleParam.Stepped(Ids.BODY_PADDING_TOP, true, true, 0f, 200f, 6f),
                ReaderStyleParam.Stepped(Ids.HEADER_PADDING_TOP, true, true, 0f, 300f, 0f),
                ReaderStyleParam.Stepped(Ids.FOOTER_PADDING_TOP, true, true, 0f, 300f, 6f),
            )
        )
        assertTrue(StyleRegionGroup.entries.all { regionGroupVisible(catalog, it) })
    }

    @Test
    fun `目录缺正文全部行则正文组隐藏`() {
        val catalog = ReaderStyleCatalog(emptyList())
        assertFalse(StyleRegionGroup.entries.any { regionGroupVisible(catalog, it) })
    }

    @Test
    fun `分组标签与轴枚举稳定`() {
        assertEquals(listOf("正文", "标题", "页眉", "页脚"), StyleRegionGroup.entries.map { it.displayLabel })
        assertEquals(listOf("字体", "信息", "边距"), StyleFunctionTab.entries.map { it.displayLabel })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:compileDebugKotlin --console=plain -Pkotlin.daemon.jvmargs=-Xmx4g`
Expected: FAIL（`Unresolved reference: regionGroupVisible / StyleRegionGroup / ...`）

- [ ] **Step 3: 最小实现**

创建 `ReaderStyleConfigDialog.kt`，本任务只放模型（容器后续任务补齐）：

```kotlin
package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids

/** 归一弹框聚合轴：按功能（Tab 切换）/ 按区域（分组全景 + 锚点直达）。 */
internal enum class StyleAxis { FUNCTION, REGION }

/** 功能轴 Tab（三入口直达目标）。 */
internal enum class StyleFunctionTab(val displayLabel: String) {
    FONTS("字体"),
    INFO("信息"),
    MARGIN("边距"),
}

/** 区域轴分组（spec §4.3 行清单归组）。 */
internal enum class StyleRegionGroup(val displayLabel: String) {
    BODY("正文"),
    TITLE("标题"),
    HEADER("页眉"),
    FOOTER("页脚"),
}

/**
 * 区域分组可见性：组内任一参数行可用即可见（锚点与组同步隐藏，spec §4.3）。
 * 行→参数 id 对应 spec §4.3 现状行清单。
 */
internal fun regionGroupVisible(catalog: ReaderStyleCatalog, group: StyleRegionGroup): Boolean {
    val ids = when (group) {
        // 五滑条 + 正文四边距
        StyleRegionGroup.BODY -> listOf(
            Ids.BODY_SIZE, Ids.BODY_LETTER_SPACING, Ids.BODY_INDENT,
            Ids.BODY_LINE_SPACING, Ids.BODY_PARAGRAPH_SPACING,
            Ids.BODY_PADDING_TOP, Ids.BODY_PADDING_BOTTOM,
            Ids.BODY_PADDING_LEFT, Ids.BODY_PADDING_RIGHT,
        )
        // 标题位置 + 标题字号
        StyleRegionGroup.TITLE -> listOf(Ids.TITLE_MODE, Ids.TITLE_SIZE)
        // 页眉显示 + 页眉页脚字号 + 页眉四边距
        StyleRegionGroup.HEADER -> listOf(
            Ids.HEADER_MODE, Ids.HEADER_SIZE,
            Ids.HEADER_PADDING_TOP, Ids.HEADER_PADDING_BOTTOM,
            Ids.HEADER_PADDING_LEFT, Ids.HEADER_PADDING_RIGHT,
        )
        // 页脚显示 + 页眉页脚字号 + 页脚四边距
        StyleRegionGroup.FOOTER -> listOf(
            Ids.FOOTER_VISIBILITY, Ids.FOOTER_SIZE,
            Ids.FOOTER_PADDING_TOP, Ids.FOOTER_PADDING_BOTTOM,
            Ids.FOOTER_PADDING_LEFT, Ids.FOOTER_PADDING_RIGHT,
        )
    }
    return ids.any { catalog.available(it) }
}
```

注意：`catalog.available(id)` 是模块内既有扩展（`ReaderViewModel` 同款用法，若它定义在别的文件为 private，则在本文件新增同实现：`internal fun ReaderStyleCatalog.available(id: String): Boolean = (find(id)?.available ?: false)`——先 grep `fun ReaderStyleCatalog.available` 确认，已有 internal/public 定义则直接用，勿重复定义）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderStyleConfigModelTest" --console=plain -Pkotlin.daemon.jvmargs=-Xmx4g`
Expected: PASS（4 tests）

- [ ] **Step 5: 提交**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleConfigDialog.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderStyleConfigModelTest.kt
git commit -m "feat(reader): 归一弹框轴模型与区域分组可见性纯函数（Task 1）"
```

---

### Task 2: 共享行组件抽取（ReaderStyleRows.kt）

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleRows.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt`（ReaderLayoutPanel 五滑条改用共享组件；`MarginRows` 迁出）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderInfoConfigDialog.kt`（行实现迁出为共享组件，弹框改为组合共享行）

**Interfaces:**
- Consumes: `EInkSliderRow`/`EInkButton`/`EInkText`（designsystem.control）、`PanelTabRow`（ReaderMenus.kt internal）、`ReaderStyleCatalog`/`ReaderTextStyle`/`ReaderStyleParamIds`
- Produces（后续任务直接使用，全部 internal）:
  - `BodyBasicRows(catalog, style, onSetTextSize: (Int)->Unit, onSetLetterSpacing: (Int)->Unit, onSetIndent: (Int)->Unit, onSetLineSpacing: (Int)->Unit, onSetParagraphSpacing: (Int)->Unit)`
  - `TitleInfoRows(catalog, style, titleSizeFollowBody: Boolean, onSetTitleMode: (Int)->Unit, onSetTitleSizeFollowBody: (Boolean)->Unit, onSetTitleSize: (Int)->Unit)`
  - `HeaderModeRow(catalog, style, onSetHeaderMode: (Int)->Unit)`
  - `FooterModeRow(catalog, style, onSetFooterVisible: (Boolean)->Unit)`
  - `TipSizeRow(catalog, style, onSetTipSize: (Int)->Unit)`
  - `BodyMarginRows(catalog, style, onSetTop/Bottom/Left/Right: (Int)->Unit)`、`HeaderMarginRows(...)`、`FooterMarginRows(...)`（各 4 setter，命名 onSetTop 等）
  - `MarginRows(topDp, bottomDp, leftDp, rightDp, maxVertical: Int, maxHorizontal: Int, onSetTop/Bottom/Left/Right)`（自 ReaderMenus.kt 迁入，private → internal，实现原样）
  - `LabeledSettingRow(label: String, content: @Composable ColumnScope.() -> Unit)`、`ChoiceButtons(options: List<String>, values: List<Int>, selected: Int, onSelect: (Int)->Unit)`（自 ReaderInfoConfigDialog.kt 迁入，private → internal，实现原样——含 `LockedTipValueRow` 若被引用一并迁入）
  - `GroupHeading(text: String)`（新：区域分组小节标题）

- [ ] **Step 1: 创建 ReaderStyleRows.kt——迁移既有实现**

新建文件，把以下实现**原样迁入**（剪切粘贴，仅可见性 private→internal）：
1. `ReaderMenus.kt` 中锚点 `/** 单个区域的边距 4 行档位滑条：上边距、下边距、左边距、右边距。 */` 起的 `MarginRows` 整个函数；
2. `ReaderInfoConfigDialog.kt` 中锚点 `/** 标签在左（按需占宽保证完整显示、垂直居中对齐首行控件），内容在右。 */` 起的 `LabeledSettingRow`，以及其下 `ChoiceButtons`、`LockedTipValueRow`（同文件私有组件，被上述行引用的全部带走）；
3. `ReaderInfoConfigDialog.kt` 的 import 中被迁走组件独占的 import 一并迁移；两文件删除不再使用的 import（以编译通过为准）。

文件头 KDoc：

```kotlin
package io.legado.app.eink.feature.reader

// imports 按迁入实现所需补齐（EInkSliderRow/EInkButton/EInkText/EInkTheme/
// EInkSpacing/Modifier/Row/Column/Arrangement/Alignment/Box/Scope/Unit/roundToInt…）

/**
 * 排版设置共享行组件（归一弹框 Task 2 抽取）：
 * 功能轴弹框与区域轴分组视图复用同一份行实现与回调——同一参数在两轴
 * 同值同控件（spec §4.3「行组件两处复用，回调一致」）。
 * 行集 = spec §4.3 现状行清单，不新增参数行。
 */
```

- [ ] **Step 2: 组合级行组（新代码，迁入素材改写）**

`BodyBasicRows`——把 `ReaderMenus.kt` 中 `ReaderLayoutPanel` 函数体内锚点 `EInkSliderRow(label = "字号"` 到锚点 `EInkSliderRow(label = "段距"` 块结束的五段滑条**原样**搬入下列函数体：

```kotlin
@Composable
internal fun BodyBasicRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTextSize: (Int) -> Unit,
    onSetLetterSpacing: (Int) -> Unit,
    onSetIndent: (Int) -> Unit,
    onSetLineSpacing: (Int) -> Unit,
    onSetParagraphSpacing: (Int) -> Unit,
) {
    // 五段 EInkSliderRow 原样迁入（字号/字距/缩进/行距/段距，
    // 值域分别取 catalog.intRange(Ids.BODY_SIZE) 等现有写法不动）
}
```

（执行提示：迁入后把函数体里对参数的引用改为本函数形参名——原代码引用 `style.textSize`/`onSetTextSize` 等同名形参，无需改名。）

`TitleInfoRows` / `HeaderModeRow` / `FooterModeRow` / `TipSizeRow`——把 `ReaderInfoConfigDialog.kt` 的 EInkDialog 内容 Column 里对应 `if (catalog.available(...))` 块与 `when (catalog.find(...))` 块**原样搬入**：

```kotlin
@Composable
internal fun TitleInfoRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    titleSizeFollowBody: Boolean,
    onSetTitleMode: (Int) -> Unit,
    onSetTitleSizeFollowBody: (Boolean) -> Unit,
    onSetTitleSize: (Int) -> Unit,
) {
    // 迁入「标题位置」if 块（LabeledSettingRow + ChoiceButtons）与
    // 「标题字号」when 块（Locked 置灰 / 随正文一致·自定义 + 滑条）——原样
}

@Composable
internal fun HeaderModeRow(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetHeaderMode: (Int) -> Unit,
) {
    // 迁入「页眉显示」if 块——原样
}

@Composable
internal fun FooterModeRow(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetFooterVisible: (Boolean) -> Unit,
) {
    // 迁入「页脚显示」if 块（显示/隐藏 + 第三槽占位）——原样
}

@Composable
internal fun TipSizeRow(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTipSize: (Int) -> Unit,
) {
    // 迁入「页眉页脚字号」when 块（Locked 置灰 / 滑条 / else 隐藏）——原样
}
```

`BodyMarginRows` / `HeaderMarginRows` / `FooterMarginRows`——按 `ReaderMarginDialog` when 分支的实参改写为薄包装：

```kotlin
@Composable
internal fun BodyMarginRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTop: (Int) -> Unit,
    onSetBottom: (Int) -> Unit,
    onSetLeft: (Int) -> Unit,
    onSetRight: (Int) -> Unit,
) = MarginRows(
    topDp = style.paddingTop,
    bottomDp = style.paddingBottom,
    leftDp = style.paddingLeft,
    rightDp = style.paddingRight,
    maxVertical = catalog.intRange(Ids.BODY_PADDING_TOP).last,
    maxHorizontal = catalog.intRange(Ids.BODY_PADDING_LEFT).last,
    onSetTop = onSetTop,
    onSetBottom = onSetBottom,
    onSetLeft = onSetLeft,
    onSetRight = onSetRight,
)

// Header/Footer 两个包装完整实现：

@Composable
internal fun HeaderMarginRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTop: (Int) -> Unit,
    onSetBottom: (Int) -> Unit,
    onSetLeft: (Int) -> Unit,
    onSetRight: (Int) -> Unit,
) = MarginRows(
    topDp = style.headerPaddingTop,
    bottomDp = style.headerPaddingBottom,
    leftDp = style.headerPaddingLeft,
    rightDp = style.headerPaddingRight,
    maxVertical = catalog.intRange(Ids.HEADER_PADDING_TOP).last,
    maxHorizontal = catalog.intRange(Ids.HEADER_PADDING_LEFT).last,
    onSetTop = onSetTop,
    onSetBottom = onSetBottom,
    onSetLeft = onSetLeft,
    onSetRight = onSetRight,
)

@Composable
internal fun FooterMarginRows(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTop: (Int) -> Unit,
    onSetBottom: (Int) -> Unit,
    onSetLeft: (Int) -> Unit,
    onSetRight: (Int) -> Unit,
) = MarginRows(
    topDp = style.footerPaddingTop,
    bottomDp = style.footerPaddingBottom,
    leftDp = style.footerPaddingLeft,
    rightDp = style.footerPaddingRight,
    maxVertical = catalog.intRange(Ids.FOOTER_PADDING_TOP).last,
    maxHorizontal = catalog.intRange(Ids.FOOTER_PADDING_LEFT).last,
    onSetTop = onSetTop,
    onSetBottom = onSetBottom,
    onSetLeft = onSetLeft,
    onSetRight = onSetRight,
)
```

（Header/Footer 两个包装必须完整写出，不得省略——实现同 BodyMarginRows 逐字段替换。）

`GroupHeading`（新）：

```kotlin
/** 区域分组小节标题：流内常驻（非吸顶），与锚点行呼应。 */
@Composable
internal fun GroupHeading(text: String) {
    EInkText(
        text = text,
        style = EInkTheme.typography.titleSmallEmphasized,
        modifier = Modifier.padding(top = EInkSpacing.s, bottom = EInkSpacing.xs),
    )
}
```

（若 designsystem 无 `titleSmallEmphasized` 样式名，grep `EInkTheme.typography.` 取现有近似档（如 `titleSmall`），以存在的为准。）

- [ ] **Step 3: 改用方**

1. `ReaderMenus.kt` `ReaderLayoutPanel`：函数体五段滑条替换为 `BodyBasicRows(catalog = catalog, style = style, onSetTextSize = onSetTextSize, onSetLetterSpacing = onSetLetterSpacing, onSetIndent = onSetIndent, onSetLineSpacing = onSetLineSpacing, onSetParagraphSpacing = onSetParagraphSpacing)`；删除迁走的 `MarginRows`。
2. `ReaderInfoConfigDialog.kt` `ReaderInfoConfigDialog`：EInkDialog 内容 Column 改为顺序组合四段共享行：

```kotlin
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            TitleInfoRows(
                catalog = catalog,
                style = style,
                titleSizeFollowBody = titleSizeFollowBody,
                onSetTitleMode = onSetTitleMode,
                onSetTitleSizeFollowBody = onSetTitleSizeFollowBody,
                onSetTitleSize = onSetTitleSize,
            )
            HeaderModeRow(catalog = catalog, style = style, onSetHeaderMode = onSetHeaderMode)
            FooterModeRow(catalog = catalog, style = style, onSetFooterVisible = onSetFooterVisible)
            TipSizeRow(catalog = catalog, style = style, onSetTipSize = onSetTipSize)
        }
```

- [ ] **Step 4: 编译 + 全量回归**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest --console=plain -Pkotlin.daemon.jvmargs=-Xmx4g`
Expected: BUILD SUCCESSFUL（462+4 tests, 0 failures）

- [ ] **Step 5: 提交**

```bash
cd eink-lib
git add -A modules/eink/src
git commit -m "refactor(reader): 排版设置共享行组件抽取——两轴复用同一行实现（Task 2）"
```

---

### Task 3: 三个内容组件（去壳 Content 版本 + 边距分组列表化）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderFontConfigDialog.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderInfoConfigDialog.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt`

**Interfaces:**
- Consumes: Task 2 的共享行组件
- Produces（Task 4 容器使用，全部 internal；旧 dialog 函数保留不动直到 Task 5 删除）:
  - `ReaderFontConfigContent(catalog, style, fontOptions: List<ReaderFontOption>, recentFontPaths: List<String>, chineseConverterType: Int, onSetFont: (ReaderFontSelection)->Unit, onOpenFontSettings: ()->Unit, onSetBodyWeight: (Int)->Unit, onSetTitleWeight: (Int)->Unit, onSetChineseConverterType: (Int)->Unit, onPickFolder: ()->Unit)`
  - `ReaderInfoConfigContent(catalog, style, titleSizeFollowBody: Boolean, onSetTitleMode: (Int)->Unit, onSetTitleSizeFollowBody: (Boolean)->Unit, onSetTitleSize: (Int)->Unit, onSetHeaderMode: (Int)->Unit, onSetFooterVisible: (Boolean)->Unit, onSetTipSize: (Int)->Unit)`
  - `ReaderMarginConfigContent(catalog, style, 12 个边距 setter：onSetPaddingTop/Bottom/Left/Right、onSetHeaderPaddingTop/Bottom/Left/Right、onSetFooterPaddingTop/Bottom/Left/Right)`

- [ ] **Step 1: FontConfigContent**

`ReaderFontConfigDialog.kt` 中：将 `internal fun ReaderFontConfigDialog(...)` 的**函数体中 `EInkDialog(...) { ... }` 花括号内的内容块**提为新函数 `ReaderFontConfigContent`（同文件、紧邻旧函数之后新增；参数=旧参数去掉 `onClose`/`onBackdropClick`）。旧 `ReaderFontConfigDialog` 暂时改为壳：

```kotlin
@Composable
internal fun ReaderFontConfigDialog(
    /* 原参数列表不动 */
) {
    EInkDialog(
        onDismiss = onClose,
        title = "字体配置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        ReaderFontConfigContent(
            catalog = catalog,
            style = style,
            fontOptions = fontOptions,
            recentFontPaths = recentFontPaths,
            chineseConverterType = chineseConverterType,
            onSetFont = onSetFont,
            onOpenFontSettings = onOpenFontSettings,
            onSetBodyWeight = onSetBodyWeight,
            onSetTitleWeight = onSetTitleWeight,
            onSetChineseConverterType = onSetChineseConverterType,
            onPickFolder = onPickFolder,
        )
    }
}
```

注意：内容块内的 `Column(verticalArrangement = ...)` 顶层容器原样保留（其外层不再有滚动——滚动由 Task 4 容器统一提供；若内容块自带 `verticalScroll` 则移除该 modifier，避免嵌套滚动编译警告/异常）。

- [ ] **Step 2: InfoConfigContent**

同法处理 `ReaderInfoConfigDialog.kt`：新函数 `ReaderInfoConfigContent` = Task 2 Step 3 的 Column 块（含 verticalScroll+heightIn——信息内容自带滚动，Task 4 容器不再包一层）；旧 dialog 改 EInkDialog 壳组合 Content。

- [ ] **Step 3: MarginConfigContent（三子 Tab → 三组分组列表）**

`ReaderMenus.kt`：删除 `ReaderMarginDialog` 内的 `var selectedTab ...` 与 `PanelTabRow(...)` 与 `when (selectedTab)` 三分支结构，新增 `ReaderMarginConfigContent`：

```kotlin
/** 边距内容：三组分组列表（正文/页眉/页脚），消除子 Tab 双层嵌套（spec §4.3）。 */
@Composable
internal fun ReaderMarginConfigContent(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetPaddingTop: (Int) -> Unit,
    onSetPaddingBottom: (Int) -> Unit,
    onSetPaddingLeft: (Int) -> Unit,
    onSetPaddingRight: (Int) -> Unit,
    onSetHeaderPaddingTop: (Int) -> Unit,
    onSetHeaderPaddingBottom: (Int) -> Unit,
    onSetHeaderPaddingLeft: (Int) -> Unit,
    onSetHeaderPaddingRight: (Int) -> Unit,
    onSetFooterPaddingTop: (Int) -> Unit,
    onSetFooterPaddingBottom: (Int) -> Unit,
    onSetFooterPaddingLeft: (Int) -> Unit,
    onSetFooterPaddingRight: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
    ) {
        GroupHeading("正文")
        BodyMarginRows(catalog, style, onSetPaddingTop, onSetPaddingBottom, onSetPaddingLeft, onSetPaddingRight)
        GroupHeading("页眉")
        HeaderMarginRows(catalog, style, onSetHeaderPaddingTop, onSetHeaderPaddingBottom, onSetHeaderPaddingLeft, onSetHeaderPaddingRight)
        GroupHeading("页脚")
        FooterMarginRows(catalog, style, onSetFooterPaddingTop, onSetFooterPaddingBottom, onSetFooterPaddingLeft, onSetFooterPaddingRight)
    }
}
```

（Margin 包装的四个 setter 形参名在 Task 2 定为 onSetTop/onSetBottom/onSetLeft/onSetRight——此处按位置传参即可。）旧 `ReaderMarginDialog` 改为 EInkDialog 壳组合 `ReaderMarginConfigContent`。

- [ ] **Step 4: 编译 + 回归 + 提交**

Run: 同 Task 2 Step 4。Expected: BUILD SUCCESSFUL
```bash
cd eink-lib && git add -A modules/eink/src && git commit -m "refactor(reader): 三弹框内容组件化+边距三子Tab改分组列表（Task 3）"
```

---

### Task 4: 归一容器 ReaderStyleConfigDialog + 区域视图

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleConfigDialog.kt`（Task 1 文件追加容器）

**Interfaces:**
- Consumes: Task 1 模型、Task 2 共享行、Task 3 三个 Content
- Produces: `ReaderStyleConfigDialog(initialTab: StyleFunctionTab, catalog: ReaderStyleCatalog, style: ReaderTextStyle, titleSizeFollowBody: Boolean, fontOptions: List<ReaderFontOption>, recentFontPaths: List<String>, chineseConverterType: Int, actions: ReaderStyleActions, onOpenFontSettings: () -> Unit, onPickFolder: () -> Unit, onClose: () -> Unit, onBackdropClick: () -> Unit)`；`ReaderStyleActions` 值类（Task 5 接线使用）

- [ ] **Step 1: ReaderStyleActions 值类**

在 `ReaderStyleConfigDialog.kt` 追加（纯值对象，聚合 24 个 setter，调用点一次构造）：

```kotlin
/** 归一弹框全部参数写回调（功能轴三内容 + 区域视图共用；spec §4.2 VM 零新增状态）。 */
internal class ReaderStyleActions(
    val setTextSize: (Int) -> Unit,
    val setLetterSpacing: (Int) -> Unit,
    val setIndent: (Int) -> Unit,
    val setLineSpacing: (Int) -> Unit,
    val setParagraphSpacing: (Int) -> Unit,
    val setReaderFont: (ReaderFontSelection) -> Unit,
    val setBodyWeight: (Int) -> Unit,
    val setTitleWeight: (Int) -> Unit,
    val setChineseConverterType: (Int) -> Unit,
    val setTitleMode: (Int) -> Unit,
    val setTitleSizeFollowBody: (Boolean) -> Unit,
    val setTitleSize: (Int) -> Unit,
    val setHeaderMode: (Int) -> Unit,
    val setFooterVisible: (Boolean) -> Unit,
    val setTipSize: (Int) -> Unit,
    val setPaddingTop: (Int) -> Unit,
    val setPaddingBottom: (Int) -> Unit,
    val setPaddingLeft: (Int) -> Unit,
    val setPaddingRight: (Int) -> Unit,
    val setHeaderPaddingTop: (Int) -> Unit,
    val setHeaderPaddingBottom: (Int) -> Unit,
    val setHeaderPaddingLeft: (Int) -> Unit,
    val setHeaderPaddingRight: (Int) -> Unit,
    val setFooterPaddingTop: (Int) -> Unit,
    val setFooterPaddingBottom: (Int) -> Unit,
    val setFooterPaddingLeft: (Int) -> Unit,
    val setFooterPaddingRight: (Int) -> Unit,
)
```

- [ ] **Step 2: 容器主体**

```kotlin
/**
 * 排版设置归一弹框（spec §4）：居中透明卡片，顶部聚合切换 [按功能|按区域]；
 * 功能轴 = Tab 行（字体/信息/边距，三入口直达）；区域轴 = 锚点行 + 四分组
 * 全景滚动（锚点无动画直达）。切轴/切 Tab 不关闭弹框。
 */
@Composable
internal fun ReaderStyleConfigDialog(
    initialTab: StyleFunctionTab,
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    titleSizeFollowBody: Boolean,
    fontOptions: List<ReaderFontOption>,
    recentFontPaths: List<String>,
    chineseConverterType: Int,
    actions: ReaderStyleActions,
    onOpenFontSettings: () -> Unit,
    onPickFolder: () -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    var axis by rememberSaveable { mutableStateOf(StyleAxis.FUNCTION) }
    var functionTab by rememberSaveable { mutableStateOf(initialTab) }
    EInkDialog(
        onDismiss = onClose,
        title = "排版设置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        Column {
            PanelTabRow(
                labels = listOf("按功能", "按区域"),
                selected = if (axis == StyleAxis.FUNCTION) 0 else 1,
                onSelect = { axis = if (it == 0) StyleAxis.FUNCTION else StyleAxis.REGION },
            )
            when (axis) {
                StyleAxis.FUNCTION -> {
                    PanelTabRow(
                        labels = StyleFunctionTab.entries.map { it.displayLabel },
                        selected = functionTab.ordinal,
                        onSelect = { functionTab = StyleFunctionTab.entries[it] },
                    )
                    when (functionTab) {
                        StyleFunctionTab.FONTS -> ReaderFontConfigContent(
                            catalog = catalog,
                            style = style,
                            fontOptions = fontOptions,
                            recentFontPaths = recentFontPaths,
                            chineseConverterType = chineseConverterType,
                            onSetFont = actions.setReaderFont,
                            onOpenFontSettings = onOpenFontSettings,
                            onSetBodyWeight = actions.setBodyWeight,
                            onSetTitleWeight = actions.setTitleWeight,
                            onSetChineseConverterType = actions.setChineseConverterType,
                            onPickFolder = onPickFolder,
                        )
                        StyleFunctionTab.INFO -> ReaderInfoConfigContent(
                            catalog = catalog,
                            style = style,
                            titleSizeFollowBody = titleSizeFollowBody,
                            onSetTitleMode = actions.setTitleMode,
                            onSetTitleSizeFollowBody = actions.setTitleSizeFollowBody,
                            onSetTitleSize = actions.setTitleSize,
                            onSetHeaderMode = actions.setHeaderMode,
                            onSetFooterVisible = actions.setFooterVisible,
                            onSetTipSize = actions.setTipSize,
                        )
                        StyleFunctionTab.MARGIN -> ReaderMarginConfigContent(
                            catalog = catalog,
                            style = style,
                            onSetPaddingTop = actions.setPaddingTop,
                            onSetPaddingBottom = actions.setPaddingBottom,
                            onSetPaddingLeft = actions.setPaddingLeft,
                            onSetPaddingRight = actions.setPaddingRight,
                            onSetHeaderPaddingTop = actions.setHeaderPaddingTop,
                            onSetHeaderPaddingBottom = actions.setHeaderPaddingBottom,
                            onSetHeaderPaddingLeft = actions.setHeaderPaddingLeft,
                            onSetHeaderPaddingRight = actions.setHeaderPaddingRight,
                            onSetFooterPaddingTop = actions.setFooterPaddingTop,
                            onSetFooterPaddingBottom = actions.setFooterPaddingBottom,
                            onSetFooterPaddingLeft = actions.setFooterPaddingLeft,
                            onSetFooterPaddingRight = actions.setFooterPaddingRight,
                        )
                    }
                }
                StyleAxis.REGION -> ReaderStyleRegionView(
                    catalog = catalog,
                    style = style,
                    titleSizeFollowBody = titleSizeFollowBody,
                    actions = actions,
                )
            }
        }
    }
}
```

说明：三个 Content 中信息/边距自带 `verticalScroll + heightIn(360.dp)`（Task 3）；字体内容无内部滚动，容器不额外包裹——与 spec「内容区内部滚动」的既有弹框口径一致（原字体弹框即固定面板无滚动）。若字体内容超屏（真机验证项），后续在字体分支外包同款滚动 Column。

- [ ] **Step 3: 区域视图（锚点直达）**

```kotlin
/**
 * 区域轴视图：锚点行（组可见性过滤后）+ 四分组全景滚动（spec §4.4：
 * 无动画直达、组标题流内非吸顶、组与锚点同步隐藏、切入回顶部）。
 */
@Composable
private fun ReaderStyleRegionView(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    titleSizeFollowBody: Boolean,
    actions: ReaderStyleActions,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val groupOffsets = remember { mutableStateMapOf<StyleRegionGroup, Int>() }
    val visibleGroups = StyleRegionGroup.entries.filter { regionGroupVisible(catalog, it) }
    Column {
        PanelTabRow(
            labels = visibleGroups.map { it.displayLabel },
            // 锚点无持久选中态（定位按钮，非当前页标记）
            selected = -1,
            onSelect = { index ->
                val target = visibleGroups.getOrNull(index) ?: return@PanelTabRow
                scope.launch { scrollState.scrollTo(groupOffsets[target] ?: 0) }
            },
        )
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            visibleGroups.forEach { group ->
                Column(
                    modifier = Modifier.onGloballyPositioned {
                        groupOffsets[group] = it.positionInParent().y.roundToInt()
                    },
                ) {
                    GroupHeading(group.displayLabel)
                    when (group) {
                        StyleRegionGroup.BODY -> {
                            BodyBasicRows(
                                catalog = catalog, style = style,
                                onSetTextSize = actions.setTextSize,
                                onSetLetterSpacing = actions.setLetterSpacing,
                                onSetIndent = actions.setIndent,
                                onSetLineSpacing = actions.setLineSpacing,
                                onSetParagraphSpacing = actions.setParagraphSpacing,
                            )
                            BodyMarginRows(
                                catalog, style,
                                actions.setPaddingTop, actions.setPaddingBottom,
                                actions.setPaddingLeft, actions.setPaddingRight,
                            )
                        }
                        StyleRegionGroup.TITLE -> TitleInfoRows(
                            catalog = catalog,
                            style = style,
                            titleSizeFollowBody = titleSizeFollowBody,
                            onSetTitleMode = actions.setTitleMode,
                            onSetTitleSizeFollowBody = actions.setTitleSizeFollowBody,
                            onSetTitleSize = actions.setTitleSize,
                        )
                        StyleRegionGroup.HEADER -> {
                            HeaderModeRow(catalog, style, actions.setHeaderMode)
                            // 页眉页脚字号：同一行组件页眉/页脚组双呈现（spec §4.3 注）
                            TipSizeRow(catalog, style, actions.setTipSize)
                            HeaderMarginRows(
                                catalog, style,
                                actions.setHeaderPaddingTop, actions.setHeaderPaddingBottom,
                                actions.setHeaderPaddingLeft, actions.setHeaderPaddingRight,
                            )
                        }
                        StyleRegionGroup.FOOTER -> {
                            FooterModeRow(catalog, style, actions.setFooterVisible)
                            TipSizeRow(catalog, style, actions.setTipSize)
                            FooterMarginRows(
                                catalog, style,
                                actions.setFooterPaddingTop, actions.setFooterPaddingBottom,
                                actions.setFooterPaddingLeft, actions.setFooterPaddingRight,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

注意：`PanelTabRow` 的 `selected = -1` 需确认其实现容忍 -1（无高亮）——`EInkButton(selected = index == selected)` 对 -1 天然全不选中，成立。`positionInParent()` 需 `import androidx.compose.ui.layout.positionInParent`。

- [ ] **Step 4: 编译 + 测试 + 提交**

Run: 同 Task 2 Step 4。Expected: BUILD SUCCESSFUL（容器暂无调用方，编译通过即可）
```bash
cd eink-lib && git add -A modules/eink/src && git commit -m "feat(reader): 归一容器+区域分组视图（锚点直达）（Task 4）"
```

---

### Task 5: ReaderScreen 接线切换 + 删除旧三弹框

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderFontConfigDialog.kt`（删旧壳函数）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderInfoConfigDialog.kt`（删旧壳函数）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt`（删旧壳函数）

**Interfaces:**
- Consumes: Task 4 的 `ReaderStyleConfigDialog`/`ReaderStyleActions`/`StyleFunctionTab`
- Produces: 无（终端消费方）

- [ ] **Step 1: 状态与引用切换**

`ReaderScreen.kt`：
1. 删除锚点 `private enum class ReaderStyleDialog { Fonts, Info, Margin }`；
2. 锚点 `var styleDialog by remember { mutableStateOf<ReaderStyleDialog?>(null) }` → `var styleTab by rememberSaveable { mutableStateOf<StyleFunctionTab?>(null) }`；
3. 全文 `styleDialog` 引用改 `styleTab`（含 `styleDialog != null -> styleDialog = null`、`bottomBarVisible = ... && styleDialog == null`、`panel?.takeIf { styleDialog == null }` 等约 5 处，逐处 grep `styleDialog` 清零）；
4. 字体列表预取 `LaunchedEffect(styleDialog) { if (styleDialog == ReaderStyleDialog.Fonts)` → `LaunchedEffect(styleTab) { if (styleTab == StyleFunctionTab.FONTS)`；
5. 三入口：`onOpenFonts = { styleTab = StyleFunctionTab.FONTS }`、`onOpenInfo = { styleTab = StyleFunctionTab.INFO }`、`onOpenMargins = { styleTab = StyleFunctionTab.MARGIN }`。

- [ ] **Step 2: 渲染块替换**

锚点 `// 排版弹层：居中透明卡片` 起的 `when (styleTab) { ... }` 三分支整块替换为：

```kotlin
        // 排版弹层：归一容器（三入口直达功能轴 Tab，弹框内切轴/切 Tab 不关框）
        styleTab?.let { tab ->
            ReaderStyleConfigDialog(
                initialTab = tab,
                catalog = viewModel.styleCatalog,
                style = uiState.style,
                titleSizeFollowBody = uiState.titleSizeFollowBody,
                fontOptions = fontOptions,
                recentFontPaths = recentFontPaths,
                chineseConverterType = uiState.chineseConverterType,
                actions = ReaderStyleActions(
                    setTextSize = viewModel::setTextSize,
                    setLetterSpacing = viewModel::setLetterSpacing,
                    setIndent = viewModel::setIndent,
                    setLineSpacing = viewModel::setLineSpacing,
                    setParagraphSpacing = viewModel::setParagraphSpacing,
                    setReaderFont = viewModel::setReaderFont,
                    setBodyWeight = viewModel::setBodyWeight,
                    setTitleWeight = viewModel::setTitleWeight,
                    setChineseConverterType = viewModel::setChineseConverterType,
                    setTitleMode = viewModel::setTitleMode,
                    setTitleSizeFollowBody = viewModel::setTitleSizeFollowBody,
                    setTitleSize = viewModel::setTitleSize,
                    setHeaderMode = viewModel::setHeaderMode,
                    setFooterVisible = viewModel::setFooterVisible,
                    setTipSize = viewModel::setTipSize,
                    setPaddingTop = viewModel::setPaddingTop,
                    setPaddingBottom = viewModel::setPaddingBottom,
                    setPaddingLeft = viewModel::setPaddingLeft,
                    setPaddingRight = viewModel::setPaddingRight,
                    setHeaderPaddingTop = viewModel::setHeaderPaddingTop,
                    setHeaderPaddingBottom = viewModel::setHeaderPaddingBottom,
                    setHeaderPaddingLeft = viewModel::setHeaderPaddingLeft,
                    setHeaderPaddingRight = viewModel::setHeaderPaddingRight,
                    setFooterPaddingTop = viewModel::setFooterPaddingTop,
                    setFooterPaddingBottom = viewModel::setFooterPaddingBottom,
                    setFooterPaddingLeft = viewModel::setFooterPaddingLeft,
                    setFooterPaddingRight = viewModel::setFooterPaddingRight,
                ),
                onOpenFontSettings = onOpenFontSettings,
                onPickFolder = {
                    val hostHandled = (context as? EInkHostActivity)?.onPickFontFolder { uriString ->
                        if (uriString != null) viewModel.setFontFolder(uriString)
                    } ?: false
                    if (!hostHandled) fontFolderLauncher.launch(null)
                },
                onClose = { styleTab = null },
                onBackdropClick = dismissToCleanReading,
            )
        }
```

（onPickFolder 闭包原样迁自旧 Fonts 分支，含注释。）

- [ ] **Step 3: 删除旧壳函数**

三个文件各删旧 dialog 壳函数（`ReaderFontConfigDialog`/`ReaderInfoConfigDialog`/`ReaderMarginDialog`——Task 3 已退化为 EInkDialog+Content 壳），及仅被其引用的 import。

- [ ] **Step 4: 残留清零 + 编译 + 回归**

Run: `grep -rn "ReaderStyleDialog\|ReaderFontConfigDialog(\|ReaderInfoConfigDialog(\|ReaderMarginDialog(" modules/eink/src`（在 eink-lib 下）Expected: 零命中
Run: 同 Task 2 Step 4。Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
cd eink-lib && git add -A modules/eink/src && git commit -m "feat(reader): 排版设置归一弹框接线，三入口直达+删除旧三弹框（Task 5）"
```

---

### Task 6: 全量验证与指针推进

**Files:**
- 无新改动（验证 + 宿主指针）

- [ ] **Step 1: 全量验证**

Run: `cd eink-lib && ./gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest --console=plain -Pkotlin.daemon.jvmargs=-Xmx4g`
Expected: BUILD SUCCESSFUL，测试总数 = 462 + Task 1 新增 4 = 466，0 failures
Run: `git -C eink-lib diff --check` Expected: 无输出

- [ ] **Step 2: 宿主指针推进**

```bash
cd <宿主仓根>
git add eink-lib
git commit -m "chore(eink-lib): 指针推进——排版设置归一弹框（双聚合轴）"
```

（宿主编译无需重跑：模块 API 无变化——`grep` 确认 contract/ 无 diff 即可。）

- [ ] **Step 3: 真机验收清单（交付说明引用，spec §6）**

三入口直达、切轴切 Tab 不关框、区域锚点直达分组、双轴同参数同值、实时预览、✕/外点/返回键关闭、旋转后弹框状态保持、字体 Tab 内容不超屏（若超屏记录为后续项）。
