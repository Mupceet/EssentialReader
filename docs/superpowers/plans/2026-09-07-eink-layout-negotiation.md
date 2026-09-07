# eink 排版参数协商目录实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地设计规格 `docs/superpowers/specs/2026-09-07-eink-layout-negotiation-design.md`——排版参数协商目录（ReaderStyleCatalog）、参数扩展（字体/字重/标题/页眉页脚）、extent 缓存键裂缝修复、排版面板三入口 UI。

**Architecture:** 模块契约层新增「参数描述符目录」（宿主逐参数声明可用性/值域/默认值/是否影响分页，旧宿主 null 回落内置 FallbackCatalog）；`ReaderTextStyle` 扩 15 个可空字段实现部分写入（null = 不跨桥写）；页眉页脚几何参数经 extent 影响分页，分页缓存键补全这些键 + VM 按 affectsLayout 全量 diff 路由重排；UI 为「5 滑条 + 一行三入口」面板加三个居中透明弹层。

**Tech Stack:** Kotlin、Jetpack Compose（模块自有 DS，无 Material3）、DataStore/SAF（字体文件夹）、JUnit4。

**环境事实（执行者必读）：**
- Windows + Git Bash；Gradle 命令用 `./gradlew.bat <task> --console=plain`。
- **仓库惯例 CRLF**：ZCode 的 Write/Edit 产物是 LF，每次提交前对本次触及的文本文件执行 `unix2dos <files>`（幂等），否则 `git diff --check` 报混合行尾。
- 提交只 `git add` 本任务列出的文件（工作区有其它未跟踪 docs，勿带入）。
- 模块测试任务 `:modules:eink:testDebugUnitTest`；app 单测 `:app:testAppDebugUnitTest --tests "..."`。
- 既有基线：app 单测有 5 个与本计划无关的既有失败；lint 有 3 个既有基线错误。出现这些不算回归。

---

## 文件结构总览

**新建：**
- `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderStyleCatalog.kt` — 目录类型 + 参数 id + FallbackCatalog
- `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderFontSelection.kt` — 字体取值 + 字体文件选项
- `app/src/main/java/io/legado/app/eink/bridge/HostStyleCatalog.kt` — 宿主目录声明
- `app/src/main/java/io/legado/app/eink/bridge/ReaderStyleMutations.kt` — style→mutations 纯函数 + 字重归一化 + INDENT_CHAR
- `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleRouting.kt` — 变更是否需重排（纯函数）
- `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleCatalogUi.kt` — 目录→UI 值域/默认档辅助
- `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderInfoConfigDialog.kt` — 信息配置弹层
- `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderFontConfigDialog.kt` — 字体配置弹层
- 测试：`modules/eink/src/test/java/io/legado/app/eink/contract/ReaderStyleCatalogTest.kt`、`.../feature/reader/ReaderStyleRoutingTest.kt`、`app/src/test/java/io/legado/app/eink/bridge/HostStyleCatalogTest.kt`、`.../ReaderStyleMutationsTest.kt`、`.../DecorationCacheKeyFragmentTest.kt`

**修改：**
- `modules/eink/.../contract/ReaderTextStyle.kt`（+15 可空字段）
- `modules/eink/.../contract/ReaderEngine.kt`（+3 端口成员；Task 9 移除 setTextBold/textBold）
- `modules/eink/.../feature/reader/ReaderViewModel.kt`（目录消费/diff 路由/新 setters/移除 textBold）
- `modules/eink/.../feature/reader/ReaderMenus.kt`（三入口行/默认标识/组件可见性调整/其它面板去加粗）
- `modules/eink/.../feature/reader/ReaderScreen.kt`（弹层状态枚举/装配三弹层/页眉渲染）
- `app/.../eink/bridge/ReaderEngineImpl.kt`（applyStyle 部分写入/解钉/目录与字体端口/snapshotStyle 扩展）
- `app/.../eink/bridge/ReaderChapterPager.kt`（cacheKey 补全）
- `modules/eink/build.gradle.kts`（版本 0.2.0 → 0.3.0，Task 9）
- `modules/eink/.../contract/README.md`、`EINK-PORTING.md`（Task 14）

---

### Task 1: 契约——目录类型、参数 id 与 FallbackCatalog

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderStyleCatalog.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/contract/ReaderStyleCatalogTest.kt`

- [ ] **Step 1.1: 写失败测试**

```kotlin
package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleCatalogTest {

    private val catalog = FallbackReaderStyleCatalog.create()

    @Test
    fun `回落目录仅含历代17个参数且id唯一`() {
        assertEquals(17, catalog.params.size)
        assertEquals(17, catalog.params.map { it.id }.toSet().size)
    }

    @Test
    fun `回落目录不含协商扩展参数`() {
        val ids = catalog.params.map { it.id }
        assertFalse(ids.contains(ReaderStyleParamIds.BODY_FONT))
        assertFalse(ids.contains(ReaderStyleParamIds.TITLE_SIZE))
        assertFalse(ids.contains(ReaderStyleParamIds.HEADER_SIZE))
        assertFalse(ids.contains(ReaderStyleParamIds.FOOTER_DIVIDER))
    }

    @Test
    fun `回落目录值域沿用模块既有常量`() {
        fun stepped(id: String) = catalog.find(id) as ReaderStyleParam.Stepped
        assertEquals(8f..40f, stepped(ReaderStyleParamIds.BODY_SIZE).let { it.min..it.max })
        assertEquals(0f..0.5f, stepped(ReaderStyleParamIds.BODY_LETTER_SPACING).let { it.min..it.max })
        assertEquals(0..4, stepped(ReaderStyleParamIds.BODY_INDENT).let { it.min.toInt()..it.max.toInt() })
        assertEquals(0..30, stepped(ReaderStyleParamIds.BODY_LINE_SPACING).let { it.min.toInt()..it.max.toInt() })
        assertEquals(0..10, stepped(ReaderStyleParamIds.BODY_PARAGRAPH_SPACING).let { it.min.toInt()..it.max.toInt() })
        assertEquals(20f, stepped(ReaderStyleParamIds.BODY_SIZE).default)
    }

    @Test
    fun `页眉页脚左右边距不影响分页其余全部影响`() {
        fun affects(id: String) = catalog.find(id)!!.affectsLayout
        assertFalse(affects(ReaderStyleParamIds.HEADER_PADDING_LEFT))
        assertFalse(affects(ReaderStyleParamIds.HEADER_PADDING_RIGHT))
        assertFalse(affects(ReaderStyleParamIds.FOOTER_PADDING_LEFT))
        assertFalse(affects(ReaderStyleParamIds.FOOTER_PADDING_RIGHT))
        assertTrue(affects(ReaderStyleParamIds.HEADER_PADDING_TOP))
        assertTrue(affects(ReaderStyleParamIds.HEADER_PADDING_BOTTOM))
        assertTrue(affects(ReaderStyleParamIds.FOOTER_PADDING_TOP))
        assertTrue(affects(ReaderStyleParamIds.BODY_SIZE))
    }

    @Test
    fun `find 按 id 查找`() {
        assertTrue(catalog.find(ReaderStyleParamIds.BODY_SIZE) is ReaderStyleParam.Stepped)
        assertEquals(null, catalog.find("no.such.id"))
    }
}
```

- [ ] **Step 1.2: 运行确认失败**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin --console=plain`
Expected: FAIL（`ReaderStyleCatalog`/`FallbackReaderStyleCatalog` 未定义，测试编译失败即可）

- [ ] **Step 1.3: 实现 ReaderStyleCatalog.kt**

```kotlin
package io.legado.app.eink.contract

/**
 * 排版参数协商目录：宿主逐参数声明「可用性 + 值域 + 默认值 + 是否影响
 * 分页」，模块按 id 手写呈现（标签/步进/分组/控件）。
 *
 * 职责边界：目录只管约束；当前值仍经 ReaderEngine.currentStyle() 读、
 * applyStyle() 写，三口分离。宿主不支持 styleCatalog() 时模块回落
 * [FallbackReaderStyleCatalog]（= 模块历代内置行为）。
 */
sealed interface ReaderStyleParam {
    /** 稳定语义 id（模块命名空间，非宿主配置键名；跨版本不变）。 */
    val id: String

    /** 宿主不支持该参数时模块隐藏对应设置项。 */
    val available: Boolean

    /** 变更后是否必须重新分页（模块据此决定是否走重排路径）。 */
    val affectsLayout: Boolean

    /** 档位滑条参数；量纲随 id 约定（sp/dp/em/倍/行/字/字重）。 */
    data class Stepped(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
        val min: Float,
        val max: Float,
        val default: Float,
    ) : ReaderStyleParam

    /** 选项参数（如标题位置；value 用宿主同构的 Int）。 */
    data class Choice(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
        val options: List<Option>,
        val default: Int,
    ) : ReaderStyleParam {
        data class Option(val value: Int, val label: String)
    }

    /** 开关参数。 */
    data class Toggle(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
        val default: Boolean,
    ) : ReaderStyleParam

    /** 字体选择参数；选项动态来自 ReaderEngine.availableFonts()，不入目录。 */
    data class Font(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
    ) : ReaderStyleParam
}

class ReaderStyleCatalog(val params: List<ReaderStyleParam>) {
    fun find(id: String): ReaderStyleParam? = params.firstOrNull { it.id == id }
}

/** 稳定语义 id：模块命名空间；宿主实现把它映射到自身配置键。 */
object ReaderStyleParamIds {
    const val BODY_SIZE = "body.size"
    const val BODY_LETTER_SPACING = "body.letter-spacing"
    const val BODY_INDENT = "body.indent"
    const val BODY_LINE_SPACING = "body.line-spacing"
    const val BODY_PARAGRAPH_SPACING = "body.paragraph-spacing"
    const val BODY_FONT = "body.font"
    const val BODY_WEIGHT = "body.weight"
    const val BODY_PADDING_TOP = "body.padding-top"
    const val BODY_PADDING_BOTTOM = "body.padding-bottom"
    const val BODY_PADDING_LEFT = "body.padding-left"
    const val BODY_PADDING_RIGHT = "body.padding-right"
    const val TITLE_FONT = "title.font"
    const val TITLE_WEIGHT = "title.weight"
    const val TITLE_MODE = "title.mode"
    const val TITLE_SIZE = "title.size"
    const val TITLE_TOP_SPACING = "title.top-spacing"
    const val TITLE_BOTTOM_SPACING = "title.bottom-spacing"
    const val TITLE_LINE_SPACING = "title.line-spacing"
    const val HEADER_FONT = "header.font"
    const val HEADER_VISIBILITY = "header.visibility"
    const val HEADER_SIZE = "header.size"
    const val HEADER_DIVIDER = "header.divider"
    const val HEADER_PADDING_TOP = "header.padding-top"
    const val HEADER_PADDING_BOTTOM = "header.padding-bottom"
    const val HEADER_PADDING_LEFT = "header.padding-left"
    const val HEADER_PADDING_RIGHT = "header.padding-right"
    const val FOOTER_VISIBILITY = "footer.visibility"
    const val FOOTER_DIVIDER = "footer.divider"
    const val FOOTER_PADDING_TOP = "footer.padding-top"
    const val FOOTER_PADDING_BOTTOM = "footer.padding-bottom"
    const val FOOTER_PADDING_LEFT = "footer.padding-left"
    const val FOOTER_PADDING_RIGHT = "footer.padding-right"
}

/**
 * 内置回落目录：宿主未提供 styleCatalog()（旧宿主）时的模块基线——
 * 仅历代已支持的 17 个参数（正文五标量 + 三组四边距），值域沿用模块
 * 既有常量。新参数（字体/字重/标题/页眉页脚信息）不在其中，即旧宿主
 * 下对应设置项全部隐藏。
 *
 * 页眉/页脚上下边距标 affectsLayout = true：宿主把它们计入分页预留
 * 高度（extent），与左右边距（仅条带内部）不同。
 */
object FallbackReaderStyleCatalog {

    fun create(): ReaderStyleCatalog = ReaderStyleCatalog(
        listOf(
            stepped(ReaderStyleParamIds.BODY_SIZE, 8f, 40f, 20f),
            stepped(ReaderStyleParamIds.BODY_LETTER_SPACING, 0f, 0.5f, 0.1f),
            stepped(ReaderStyleParamIds.BODY_INDENT, 0f, 4f, 2f),
            stepped(ReaderStyleParamIds.BODY_LINE_SPACING, 0f, 30f, 12f),
            stepped(ReaderStyleParamIds.BODY_PARAGRAPH_SPACING, 0f, 10f, 2f),
            stepped(ReaderStyleParamIds.BODY_PADDING_TOP, 0f, 48f, 6f),
            stepped(ReaderStyleParamIds.BODY_PADDING_BOTTOM, 0f, 48f, 6f),
            stepped(ReaderStyleParamIds.BODY_PADDING_LEFT, 0f, 64f, 16f),
            stepped(ReaderStyleParamIds.BODY_PADDING_RIGHT, 0f, 64f, 16f),
            stepped(ReaderStyleParamIds.HEADER_PADDING_TOP, 0f, 48f, 0f),
            stepped(ReaderStyleParamIds.HEADER_PADDING_BOTTOM, 0f, 48f, 0f),
            paintOnly(ReaderStyleParamIds.HEADER_PADDING_LEFT, 0f, 48f, 16f),
            paintOnly(ReaderStyleParamIds.HEADER_PADDING_RIGHT, 0f, 48f, 16f),
            stepped(ReaderStyleParamIds.FOOTER_PADDING_TOP, 0f, 48f, 6f),
            stepped(ReaderStyleParamIds.FOOTER_PADDING_BOTTOM, 0f, 48f, 6f),
            paintOnly(ReaderStyleParamIds.FOOTER_PADDING_LEFT, 0f, 48f, 16f),
            paintOnly(ReaderStyleParamIds.FOOTER_PADDING_RIGHT, 0f, 48f, 16f),
        )
    )

    private fun stepped(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = true, min, max, default)

    private fun paintOnly(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = false, min, max, default)
}
```

- [ ] **Step 1.4: 跑测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --console=plain`
Expected: PASS（ReaderStyleCatalogTest 5 个用例全绿）

- [ ] **Step 1.5: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/contract/ReaderStyleCatalog.kt modules/eink/src/test/java/io/legado/app/eink/contract/ReaderStyleCatalogTest.kt
git add modules/eink/src/main/java/io/legado/app/eink/contract/ReaderStyleCatalog.kt modules/eink/src/test/java/io/legado/app/eink/contract/ReaderStyleCatalogTest.kt
git commit -m "feat(eink): 排版参数协商目录契约与内置回落目录"
```

---

### Task 2: 契约——字体取值类型与 ReaderTextStyle 扩展字段

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderFontSelection.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderTextStyle.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/contract/ReaderTextStyleDefaultsTest.kt`

- [ ] **Step 2.1: 写失败测试**

```kotlin
package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderTextStyleDefaultsTest {

    @Test
    fun `协商扩展字段默认全部为null即不跨桥写`() {
        val style = ReaderTextStyle()
        assertNull(style.bodyFont)
        assertNull(style.bodyWeight)
        assertNull(style.titleFont)
        assertNull(style.titleWeight)
        assertNull(style.titleMode)
        assertNull(style.titleSize)
        assertNull(style.titleTopSpacing)
        assertNull(style.titleBottomSpacing)
        assertNull(style.titleLineSpacing)
        assertNull(style.headerFont)
        assertNull(style.headerVisible)
        assertNull(style.headerSize)
        assertNull(style.headerDivider)
        assertNull(style.footerVisible)
        assertNull(style.footerDivider)
    }

    @Test
    fun `存量字段默认值不变`() {
        val style = ReaderTextStyle()
        assertEquals(20, style.textSize)
        assertEquals(2, style.indentChars)
        assertEquals(12, style.lineSpacing)
        assertEquals(16, style.paddingLeft)
    }
}
```

- [ ] **Step 2.2: 运行确认失败**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin --console=plain`
Expected: FAIL（新字段未定义）

- [ ] **Step 2.3: 实现 ReaderFontSelection.kt**

```kotlin
package io.legado.app.eink.contract

/**
 * 字体取值：系统预设 / 字体文件 / 跟随正文。
 *
 * FollowBody 仅对标题/页眉合法：宿主实现把它展开为正文当前有效字体
 * （正文为系统预设时无路径可写，宿主回落系统默认字体）。
 */
sealed interface ReaderFontSelection {
    data object Sans : ReaderFontSelection
    data object Serif : ReaderFontSelection
    data object Mono : ReaderFontSelection
    data class File(val path: String) : ReaderFontSelection
    data object FollowBody : ReaderFontSelection
}

/** 可选字体文件（宿主字体文件夹枚举项；path 为文件 uri/路径字符串）。 */
class ReaderFontOption(val name: String, val path: String)
```

- [ ] **Step 2.4: 扩展 ReaderTextStyle**

在 `ReaderTextStyle.kt` 的 `paragraphSpacing` 字段之后、`paddingLeft` 之前插入协商扩展字段块（保持既有字段与注释不动）：

```kotlin
    // ---- 协商扩展参数：null = 不跨桥写（保持宿主值）。宿主目录标记
    // 不可用或旧宿主回落目录中不存在时，UI 隐藏对应设置项。 ----

    /** 正文字体；null = 不管理。 */
    val bodyFont: ReaderFontSelection? = null,

    /** 正文字重（100..900 可变字重）；null = 不管理。 */
    val bodyWeight: Int? = null,

    /** 标题字体；null = 不管理，[ReaderFontSelection.FollowBody] = 跟随正文。 */
    val titleFont: ReaderFontSelection? = null,

    /** 标题字重（100..900）；null = 不管理。 */
    val titleWeight: Int? = null,

    /** 标题位置（0 左 / 1 中 / 2 隐藏，宿主语义）；null = 不管理。 */
    val titleMode: Int? = null,

    /** 标题字号（sp）；null = 不管理（宿主值独立保留，不再钉平跟随正文）。 */
    val titleSize: Int? = null,

    /** 标题上留白（dp）；null = 不管理。 */
    val titleTopSpacing: Int? = null,

    /** 标题下留白（dp）；null = 不管理。 */
    val titleBottomSpacing: Int? = null,

    /** 标题行距（0.1 倍档，12 = 1.2 倍）；null = 不管理。 */
    val titleLineSpacing: Int? = null,

    /** 页眉字体；null = 不管理，FollowBody = 跟随正文。 */
    val headerFont: ReaderFontSelection? = null,

    /** 页眉显隐（写宿主 HeaderMode 1/2）；null = 不管理（宿主默认档
     *  「随状态栏」保留原语义）。 */
    val headerVisible: Boolean? = null,

    /** 页眉字号（sp，经 extent 影响正文分页预留）；null = 不管理。 */
    val headerSize: Int? = null,

    /** 页眉分割线；null = 不管理。 */
    val headerDivider: Boolean? = null,

    /** 页脚显隐（写宿主 FooterMode 0/1）；null = 不管理。 */
    val footerVisible: Boolean? = null,

    /** 页脚分割线；null = 不管理。 */
    val footerDivider: Boolean? = null,
```

同时更新类 KDoc：把「标题字号无独立字段——模块语义为标题跟随正文，宿主实现把标题字号一并写入同值」一句替换为「标题字号为独立可空字段（[titleSize]），null 时不跨桥写；宿主实现不再钉平」。

- [ ] **Step 2.5: 跑测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --console=plain`
Expected: PASS（含 Task 1 用例）

- [ ] **Step 2.6: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/contract/ReaderFontSelection.kt modules/eink/src/main/java/io/legado/app/eink/contract/ReaderTextStyle.kt modules/eink/src/test/java/io/legado/app/eink/contract/ReaderTextStyleDefaultsTest.kt
git add modules/eink/src/main/java/io/legado/app/eink/contract/ReaderFontSelection.kt modules/eink/src/main/java/io/legado/app/eink/contract/ReaderTextStyle.kt modules/eink/src/test/java/io/legado/app/eink/contract/ReaderTextStyleDefaultsTest.kt
git commit -m "feat(eink): ReaderTextStyle 协商扩展字段与字体取值类型"
```

---

### Task 3: 契约——ReaderEngine 端口新增三个成员

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt`（排版小节，`applyStyle` 声明之后）

- [ ] **Step 3.1: 添加端口成员（接口默认实现 = 能力降级）**

在 `fun applyStyle(style: ReaderTextStyle)` 声明之后插入：

```kotlin
    /**
     * 排版参数协商目录：宿主逐参数声明可用性/值域/默认值/是否影响分页。
     * 返回 null = 宿主不支持协商（旧宿主），模块回落内置基线目录。
     */
    fun styleCatalog(): ReaderStyleCatalog? = null

    /**
     * 可枚举的字体文件列表（来自宿主配置的字体文件夹；无文件夹时宿主
     * 可回落其默认字体目录）。IO 操作，调用方自行调度。
     */
    suspend fun availableFonts(): List<ReaderFontOption> = emptyList()

    /** 设置字体文件夹（SAF tree uri 字符串）并持久化。 */
    suspend fun setFontFolder(uri: String) {}
```

并更新 `applyStyle` 的 KDoc：删除「标题字号无独立字段——宿主实现应把标题字号一并写入同值（模块语义：标题跟随正文字号）」段落，替换为「可空扩展字段为 null 时宿主不写对应键（部分写入语义）；FollowBody 字体选择由宿主展开为正文当前有效字体」。

- [ ] **Step 3.2: 编译确认**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin :app:compileAppDebugKotlin --console=plain`
Expected: PASS（默认实现，桥无需改动即编译通过）

- [ ] **Step 3.3: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt
git add modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt
git commit -m "feat(eink): ReaderEngine 端口新增排版目录与字体列表能力"
```

---

### Task 4: 桥——宿主目录 HostStyleCatalog

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/HostStyleCatalog.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/HostStyleCatalogTest.kt`

- [ ] **Step 4.1: 写失败测试**

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStyleCatalogTest {

    private val catalog = HostStyleCatalog.create()

    @Test
    fun `完整目录覆盖全部32个参数且全部可用`() {
        assertEquals(32, catalog.params.size)
        assertEquals(32, catalog.params.map { it.id }.toSet().size)
        assertTrue(catalog.params.all { it.available })
    }

    @Test
    fun `值域与默认值与宿主排版设置同源`() {
        fun stepped(id: String) = catalog.find(id) as ReaderStyleParam.Stepped
        assertEquals(5f..50f, stepped(Ids.BODY_SIZE).let { it.min..it.max })
        assertEquals(-0.5f..0.5f, stepped(Ids.BODY_LETTER_SPACING).let { it.min..it.max })
        assertEquals(0f..20f, stepped(Ids.BODY_LINE_SPACING).let { it.min..it.max })
        assertEquals(8f..60f, stepped(Ids.TITLE_SIZE).let { it.min..it.max })
        assertEquals(20f, stepped(Ids.TITLE_SIZE).default)
        assertEquals(100f..900f, stepped(Ids.BODY_WEIGHT).let { it.min..it.max })
        assertEquals(500f, stepped(Ids.BODY_WEIGHT).default)
        assertEquals(0f..200f, stepped(Ids.TITLE_TOP_SPACING).let { it.min..it.max })
        assertEquals(12f, stepped(Ids.HEADER_SIZE).default)
    }

    @Test
    fun `仅页眉页脚左右边距不影响分页`() {
        val paintOnly = setOf(
            Ids.HEADER_PADDING_LEFT, Ids.HEADER_PADDING_RIGHT,
            Ids.FOOTER_PADDING_LEFT, Ids.FOOTER_PADDING_RIGHT,
        )
        assertFalse(paintOnly.map { catalog.find(it)!!.affectsLayout }.any { it })
        assertTrue(
            catalog.params.filter { it.id !in paintOnly }.all { it.affectsLayout }
        )
    }

    @Test
    fun `标题位置选项与宿主语义同构`() {
        val choice = catalog.find(Ids.TITLE_MODE) as ReaderStyleParam.Choice
        assertEquals(listOf(0, 1, 2), choice.options.map { it.value })
        assertEquals(0, choice.default)
    }
}
```

- [ ] **Step 4.2: 运行确认失败**

Run: `./gradlew.bat :app:compileAppDebugKotlin --console=plain`
Expected: FAIL（HostStyleCatalog 未定义）

- [ ] **Step 4.3: 实现 HostStyleCatalog.kt**

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids

/**
 * 宿主排版参数目录：当前 ReadBookConfig 引擎约束的完整声明，
 * 值域/默认值与宿主排版设置 UI 同源。
 *
 * affectsLayout 判定：页眉/页脚字号/字体/上下边距/分割线/显隐经
 * extent（LegacyReaderPageDecorationFactory 按字体度量推导分页预留
 * 高度）影响正文分页；左右边距仅条带内部布局。
 */
internal object HostStyleCatalog {

    fun create(): ReaderStyleCatalog = ReaderStyleCatalog(
        listOf(
            stepped(Ids.BODY_SIZE, 5f, 50f, 20f),
            stepped(Ids.BODY_LETTER_SPACING, -0.5f, 0.5f, 0.1f),
            stepped(Ids.BODY_INDENT, 0f, 4f, 2f),
            stepped(Ids.BODY_LINE_SPACING, 0f, 20f, 12f),
            stepped(Ids.BODY_PARAGRAPH_SPACING, 0f, 20f, 2f),
            ReaderStyleParam.Font(Ids.BODY_FONT, available = true, affectsLayout = true),
            stepped(Ids.BODY_WEIGHT, 100f, 900f, 500f),
            ReaderStyleParam.Font(Ids.TITLE_FONT, available = true, affectsLayout = true),
            stepped(Ids.TITLE_WEIGHT, 100f, 900f, 500f),
            ReaderStyleParam.Choice(
                id = Ids.TITLE_MODE,
                available = true,
                affectsLayout = true,
                options = listOf(
                    ReaderStyleParam.Choice.Option(0, "居左"),
                    ReaderStyleParam.Choice.Option(1, "居中"),
                    ReaderStyleParam.Choice.Option(2, "隐藏"),
                ),
                default = 0,
            ),
            stepped(Ids.TITLE_SIZE, 8f, 60f, 20f),
            stepped(Ids.TITLE_TOP_SPACING, 0f, 200f, 0f),
            stepped(Ids.TITLE_BOTTOM_SPACING, 0f, 200f, 0f),
            stepped(Ids.TITLE_LINE_SPACING, 0f, 20f, 12f),
            ReaderStyleParam.Font(Ids.HEADER_FONT, available = true, affectsLayout = true),
            toggle(Ids.HEADER_VISIBILITY, default = true),
            stepped(Ids.HEADER_SIZE, 0f, 100f, 12f),
            toggle(Ids.HEADER_DIVIDER, default = false),
            toggle(Ids.FOOTER_VISIBILITY, default = true),
            toggle(Ids.FOOTER_DIVIDER, default = true),
            stepped(Ids.BODY_PADDING_TOP, 0f, 200f, 6f),
            stepped(Ids.BODY_PADDING_BOTTOM, 0f, 200f, 6f),
            stepped(Ids.BODY_PADDING_LEFT, 0f, 200f, 16f),
            stepped(Ids.BODY_PADDING_RIGHT, 0f, 200f, 16f),
            stepped(Ids.HEADER_PADDING_TOP, 0f, 300f, 0f),
            stepped(Ids.HEADER_PADDING_BOTTOM, 0f, 300f, 0f),
            paintOnly(Ids.HEADER_PADDING_LEFT, 0f, 300f, 16f),
            paintOnly(Ids.HEADER_PADDING_RIGHT, 0f, 300f, 16f),
            stepped(Ids.FOOTER_PADDING_TOP, 0f, 300f, 6f),
            stepped(Ids.FOOTER_PADDING_BOTTOM, 0f, 300f, 6f),
            paintOnly(Ids.FOOTER_PADDING_LEFT, 0f, 300f, 16f),
            paintOnly(Ids.FOOTER_PADDING_RIGHT, 0f, 300f, 16f),
        )
    )

    private fun stepped(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = true, min, max, default)

    private fun paintOnly(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = false, min, max, default)

    private fun toggle(id: String, default: Boolean) =
        ReaderStyleParam.Toggle(id, available = true, affectsLayout = true, default)
}
```

- [ ] **Step 4.4: 跑测试确认通过**

Run: `./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.HostStyleCatalogTest" --console=plain`
Expected: PASS

- [ ] **Step 4.5: 提交**

```bash
unix2dos app/src/main/java/io/legado/app/eink/bridge/HostStyleCatalog.kt app/src/test/java/io/legado/app/eink/bridge/HostStyleCatalogTest.kt
git add app/src/main/java/io/legado/app/eink/bridge/HostStyleCatalog.kt app/src/test/java/io/legado/app/eink/bridge/HostStyleCatalogTest.kt
git commit -m "feat(eink): 桥实现宿主排版参数目录"
```

---

### Task 5: 桥——部分写入纯函数 buildStyleMutations

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/ReaderStyleMutations.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt`（仅删除 `private const val INDENT_CHAR`，改用新文件中的 internal 常量）
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderStyleMutationsTest.kt`

- [ ] **Step 5.1: 写失败测试**

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.gateway.ReadStyleIntKey
import io.legado.app.domain.gateway.ReadStyleMutation
import io.legado.app.domain.gateway.ReadStyleStringKey
import io.legado.app.eink.contract.ReaderFontSelection as FontSel
import io.legado.app.eink.contract.ReaderTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleMutationsTest {

    private fun ints(mutations: List<ReadStyleMutation>) =
        mutations.filterIsInstance<ReadStyleMutation.IntValue>()

    private fun strings(mutations: List<ReadStyleMutation>) =
        mutations.filterIsInstance<ReadStyleMutation.StringValue>()

    @Test
    fun `扩展字段全null时只写17个基础键且不写TitleSize`() {
        val mutations = buildStyleMutations(ReaderTextStyle(), currentBodyFontPath = "/f.ttf")
        assertEquals(17, mutations.size)
        assertTrue(strings(mutations).none { it.key == ReadStyleStringKey.TitleFont })
        assertTrue(ints(mutations).none { it.key == ReadStyleIntKey.TitleSize })
        assertTrue(ints(mutations).none { it.key == ReadStyleIntKey.TextBold })
    }

    @Test
    fun `titleSize设置后写TitleSize不再钉平`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(textSize = 24, titleSize = 30),
            currentBodyFontPath = "",
        )
        assertEquals(24, ints(mutations).first { it.key == ReadStyleIntKey.TextSize }.value)
        assertEquals(30, ints(mutations).first { it.key == ReadStyleIntKey.TitleSize }.value)
    }

    @Test
    fun `FollowBody展开为正文文件路径三键同写`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(
                bodyFont = FontSel.File("/fonts/x.ttf"),
                titleFont = FontSel.FollowBody,
                headerFont = FontSel.FollowBody,
            ),
            currentBodyFontPath = "/old.ttf",
        )
        assertEquals("/fonts/x.ttf", strings(mutations).first { it.key == ReadStyleStringKey.TextFont }.value)
        assertEquals("/fonts/x.ttf", strings(mutations).first { it.key == ReadStyleStringKey.TitleFont }.value)
        assertEquals("/fonts/x.ttf", strings(mutations).first { it.key == ReadStyleStringKey.HeaderFont }.value)
    }

    @Test
    fun `正文为系统预设时跟随者写空串`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(
                bodyFont = FontSel.Serif,
                titleFont = FontSel.FollowBody,
                headerFont = FontSel.FollowBody,
            ),
            currentBodyFontPath = "/old.ttf",
        )
        assertEquals("", strings(mutations).first { it.key == ReadStyleStringKey.TextFont }.value)
        assertEquals("", strings(mutations).first { it.key == ReadStyleStringKey.TitleFont }.value)
        assertEquals("", strings(mutations).first { it.key == ReadStyleStringKey.HeaderFont }.value)
    }

    @Test
    fun `bodyFont为null时FollowBody按宿主当前正文路径展开`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(titleFont = FontSel.FollowBody),
            currentBodyFontPath = "/host.ttf",
        )
        assertEquals("/host.ttf", strings(mutations).first { it.key == ReadStyleStringKey.TitleFont }.value)
    }

    @Test
    fun `显隐开关映射宿主模式值`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(headerVisible = false, footerVisible = false),
            currentBodyFontPath = "",
        )
        assertEquals(2, ints(mutations).first { it.key == ReadStyleIntKey.HeaderMode }.value)
        assertEquals(1, ints(mutations).first { it.key == ReadStyleIntKey.FooterMode }.value)
    }

    @Test
    fun `字重写入钳制到100到900`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(bodyWeight = 50, titleWeight = 950),
            currentBodyFontPath = "",
        )
        assertEquals(100, ints(mutations).first { it.key == ReadStyleIntKey.TextBold }.value)
        assertEquals(900, ints(mutations).first { it.key == ReadStyleIntKey.TitleBold }.value)
    }

    @Test
    fun `宿主遗留字重值归一化`() {
        assertEquals(900, normalizeHostWeight(1))
        assertEquals(300, normalizeHostWeight(2))
        assertEquals(500, normalizeHostWeight(500))
        assertEquals(400, normalizeHostWeight(0))
    }

    @Test
    fun `缩进展开为全角空格`() {
        val mutations = buildStyleMutations(
            ReaderTextStyle(indentChars = 3),
            currentBodyFontPath = "",
        )
        assertEquals("　　　", strings(mutations).first { it.key == ReadStyleStringKey.ParagraphIndent }.value)
    }
}
```

- [ ] **Step 5.2: 运行确认失败**

Run: `./gradlew.bat :app:compileAppDebugKotlin --console=plain`
Expected: FAIL（buildStyleMutations 未定义）

- [ ] **Step 5.3: 实现 ReaderStyleMutations.kt**

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.gateway.ReadStyleBooleanKey
import io.legado.app.domain.gateway.ReadStyleFloatKey
import io.legado.app.domain.gateway.ReadStyleIntKey
import io.legado.app.domain.gateway.ReadStyleMutation
import io.legado.app.domain.gateway.ReadStyleStringKey
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderTextStyle

/** 段首缩进展开字符（宿主引擎常量，与完整模式一致）。 */
internal const val INDENT_CHAR = "　"

/**
 * ReaderTextStyle → 宿主配置 mutation 列表（纯函数，applyStyle 消费）。
 *
 * 部分写入：可空扩展字段为 null 时跳过对应键（不触碰宿主值）。
 * 字体跟随：FollowBody 展开为正文当前有效路径——本次快照已带正文字体
 * 用之（[effectiveBodyPath]），否则用宿主当前值；正文为系统预设时无
 * 路径可写，回落空串（宿主语义 = 系统默认字体；标题键空串原生回落
 * 正文字体）。
 */
internal fun buildStyleMutations(
    style: ReaderTextStyle,
    currentBodyFontPath: String,
): List<ReadStyleMutation> = buildList {
    fun int(key: ReadStyleIntKey, value: Int) = add(ReadStyleMutation.IntValue(key, value))
    fun str(key: ReadStyleStringKey, value: String) = add(ReadStyleMutation.StringValue(key, value))

    int(ReadStyleIntKey.TextSize, style.textSize)
    add(ReadStyleMutation.FloatValue(ReadStyleFloatKey.LetterSpacing, style.letterSpacing))
    str(
        ReadStyleStringKey.ParagraphIndent,
        if (style.indentChars <= 0) "" else INDENT_CHAR.repeat(style.indentChars),
    )
    int(ReadStyleIntKey.LineSpacing, style.lineSpacing)
    int(ReadStyleIntKey.ParagraphSpacing, style.paragraphSpacing)
    int(ReadStyleIntKey.PaddingTop, style.paddingTop)
    int(ReadStyleIntKey.PaddingBottom, style.paddingBottom)
    int(ReadStyleIntKey.PaddingLeft, style.paddingLeft)
    int(ReadStyleIntKey.PaddingRight, style.paddingRight)
    int(ReadStyleIntKey.HeaderPaddingTop, style.headerPaddingTop)
    int(ReadStyleIntKey.HeaderPaddingBottom, style.headerPaddingBottom)
    int(ReadStyleIntKey.HeaderPaddingLeft, style.headerPaddingLeft)
    int(ReadStyleIntKey.HeaderPaddingRight, style.headerPaddingRight)
    int(ReadStyleIntKey.FooterPaddingTop, style.footerPaddingTop)
    int(ReadStyleIntKey.FooterPaddingBottom, style.footerPaddingBottom)
    int(ReadStyleIntKey.FooterPaddingLeft, style.footerPaddingLeft)
    int(ReadStyleIntKey.FooterPaddingRight, style.footerPaddingRight)

    style.titleSize?.let { int(ReadStyleIntKey.TitleSize, it) }
    style.titleMode?.let { int(ReadStyleIntKey.TitleMode, it.coerceIn(0, 2)) }
    style.titleTopSpacing?.let { int(ReadStyleIntKey.TitleTopSpacing, it) }
    style.titleBottomSpacing?.let { int(ReadStyleIntKey.TitleBottomSpacing, it) }
    style.titleLineSpacing?.let { int(ReadStyleIntKey.TitleLineSpacingExtra, it) }
    style.bodyWeight?.let { int(ReadStyleIntKey.TextBold, it.coerceIn(100, 900)) }
    style.titleWeight?.let { int(ReadStyleIntKey.TitleBold, it.coerceIn(100, 900)) }

    val effectiveBodyPath = when (val body = style.bodyFont) {
        null -> currentBodyFontPath
        is ReaderFontSelection.File -> body.path
        else -> ""
    }
    style.bodyFont?.let { str(ReadStyleStringKey.TextFont, (it as? ReaderFontSelection.File)?.path ?: "") }
    style.titleFont?.let { str(ReadStyleStringKey.TitleFont, fontPathForHost(it, effectiveBodyPath)) }
    style.headerFont?.let { str(ReadStyleStringKey.HeaderFont, fontPathForHost(it, effectiveBodyPath)) }

    style.headerSize?.let { int(ReadStyleIntKey.HeaderFontSize, it) }
    style.headerDivider?.let {
        add(ReadStyleMutation.BooleanValue(ReadStyleBooleanKey.ShowHeaderLine, it))
    }
    style.footerDivider?.let {
        add(ReadStyleMutation.BooleanValue(ReadStyleBooleanKey.ShowFooterLine, it))
    }
    style.headerVisible?.let { int(ReadStyleIntKey.HeaderMode, if (it) 1 else 2) }
    style.footerVisible?.let { int(ReadStyleIntKey.FooterMode, if (it) 0 else 1) }
}

/** FollowBody 展开为正文有效路径；系统预设 → 空串（宿主 = 系统字体）。 */
private fun fontPathForHost(
    selection: ReaderFontSelection,
    effectiveBodyPath: String,
): String = when (selection) {
    is ReaderFontSelection.File -> selection.path
    ReaderFontSelection.FollowBody -> effectiveBodyPath
    ReaderFontSelection.Sans, ReaderFontSelection.Serif, ReaderFontSelection.Mono -> ""
}

/** 宿主遗留字重值（0 正常 / 1 粗 / 2 细 / 100..900）归一化为 100..900，与引擎 resolveWeight 同口径。 */
internal fun normalizeHostWeight(value: Int): Int = when (value) {
    1 -> 900
    2 -> 300
    in 100..900 -> value
    else -> 400
}
```

同时删除 `ReaderEngineImpl.kt:54` 的 `private const val INDENT_CHAR = "　"`（新文件已提供同包 internal 常量，原文件其余引用不用改）。

- [ ] **Step 5.4: 跑测试确认通过**

Run: `./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderStyleMutationsTest" --console=plain`
Expected: PASS（9 个用例）

- [ ] **Step 5.5: 提交**

```bash
unix2dos app/src/main/java/io/legado/app/eink/bridge/ReaderStyleMutations.kt app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt app/src/test/java/io/legado/app/eink/bridge/ReaderStyleMutationsTest.kt
git add app/src/main/java/io/legado/app/eink/bridge/ReaderStyleMutations.kt app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt app/src/test/java/io/legado/app/eink/bridge/ReaderStyleMutationsTest.kt
git commit -m "feat(eink): 桥排版写入改部分写入纯函数与字体跟随展开"
```

---

### Task 6: 桥——ReaderEngineImpl 接线（applyStyle 重写 / 解钉 / 目录与字体端口 / snapshotStyle 扩展）

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt`

无独立单测（逻辑已由 Task 5 纯函数覆盖；编译 + 既有测试回归验证）。

- [ ] **Step 6.1: 重写 applyStyle（替换 `ReaderEngineImpl.kt:377-423` 的整个方法体）**

```kotlin
    override fun applyStyle(style: ReaderTextStyle) {
        val mutations = buildStyleMutations(
            style = style,
            currentBodyFontPath = ReadBookConfig.durConfig.textFont,
        )
        mutations.forEach(readStyleGateway::updateCurrentStyle)
        // 正文系统预设写入全局 systemTypefaces（ReadSettings，非样式键）；
        // 同值重复写无害，DataStore 异步落盘
        when (style.bodyFont) {
            ReaderFontSelection.Sans -> styleScope.launch { readSettingsRepository.setSystemTypefaces(0) }
            ReaderFontSelection.Serif -> styleScope.launch { readSettingsRepository.setSystemTypefaces(1) }
            ReaderFontSelection.Mono -> styleScope.launch { readSettingsRepository.setSystemTypefaces(2) }
            else -> Unit
        }
        readStyleGateway.save()
        chapterPager.onStyleChanged()
    }
```

类体内加一个 fire-and-forget 作用域（与回调适配器同区域）：

```kotlin
    /** 排版副作用作用域：applyStyle 内的异步全局设置写入（systemTypefaces）。 */
    private val styleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

补充 import（文件头部）：

```kotlin
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.help.loadFontFiles
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
```

- [ ] **Step 6.2: 实现三个新端口成员（追加到 `footerDecorationExtentPx` 之后）**

```kotlin
    override fun styleCatalog(): ReaderStyleCatalog = HostStyleCatalog.create()

    override suspend fun availableFonts(): List<ReaderFontOption> {
        val folder = readSettingsRepository.currentSettings.fontFolder
            .takeIf { it.isNotEmpty() }
            ?.toUri()
        return loadFontFiles(appCtx, folder).map { ReaderFontOption(name = it.name, path = it.uri.toString()) }
    }

    override suspend fun setFontFolder(uri: String) {
        readSettingsRepository.setFontFolder(uri)
    }
```

- [ ] **Step 6.3: 扩展 snapshotStyle（替换文件底部 `private fun ReadBookConfig.snapshotStyle()` 整个函数）**

```kotlin
/** 从阅读配置读取排版参数快照。显隐读宿主默认档时保持 null（不管理）。 */
private fun ReadBookConfig.snapshotStyle(): ReaderTextStyle = ReaderTextStyle(
    textSize = textSize,
    letterSpacing = letterSpacing,
    indentChars = paragraphIndent.count { it == INDENT_CHAR[0] },
    lineSpacing = lineSpacingExtra,
    paragraphSpacing = paragraphSpacing,
    paddingLeft = paddingLeft,
    paddingTop = paddingTop,
    paddingRight = paddingRight,
    paddingBottom = paddingBottom,
    headerPaddingLeft = durConfig.headerPaddingLeft,
    headerPaddingTop = durConfig.headerPaddingTop,
    headerPaddingRight = durConfig.headerPaddingRight,
    headerPaddingBottom = durConfig.headerPaddingBottom,
    footerPaddingLeft = durConfig.footerPaddingLeft,
    footerPaddingTop = durConfig.footerPaddingTop,
    footerPaddingRight = durConfig.footerPaddingRight,
    footerPaddingBottom = durConfig.footerPaddingBottom,
    bodyWeight = normalizeHostWeight(durConfig.textBold),
    titleWeight = normalizeHostWeight(durConfig.titleBold),
    titleMode = titleMode,
    titleSize = durConfig.titleSize,
    titleTopSpacing = durConfig.titleTopSpacing,
    titleBottomSpacing = durConfig.titleBottomSpacing,
    titleLineSpacing = durConfig.titleLineSpacingExtra,
    bodyFont = bodyFontSelection(durConfig.textFont),
    titleFont = durConfig.titleFont.takeIf { it.isNotBlank() }
        ?.let(ReaderFontSelection::File) ?: ReaderFontSelection.FollowBody,
    headerFont = durConfig.headerFont.takeIf { it.isNotBlank() }
        ?.let(ReaderFontSelection::File) ?: ReaderFontSelection.FollowBody,
    headerSize = durConfig.headerFontSize,
    headerDivider = durConfig.showHeaderLine,
    footerDivider = durConfig.showFooterLine,
    headerVisible = when (durConfig.headerMode) {
        1 -> true
        2 -> false
        else -> null
    },
    footerVisible = when (durConfig.footerMode) {
        1 -> false
        else -> null
    },
)

/** 正文正文字体取值：有文件用文件，否则按全局 systemTypefaces 映射系统预设。 */
private fun bodyFontSelection(path: String): ReaderFontSelection = if (path.isNotBlank()) {
    ReaderFontSelection.File(path)
} else {
    when (readSettingsRepository.currentSettings.systemTypefaces) {
        1 -> ReaderFontSelection.Serif
        2 -> ReaderFontSelection.Mono
        else -> ReaderFontSelection.Sans
    }
}
```

注意：`snapshotStyle` 是 `ReaderEngineImpl.kt` 文件级私有扩展函数，`bodyFontSelection` 需要访问 `readSettingsRepository`，故把它写成 `ReaderEngineImpl` 的私有成员函数，`snapshotStyle` 内调用 `this.bodyFontSelection(...)`（snapshotStyle 无接收者冲突，直接调用即可）。

- [ ] **Step 6.4: 编译 + 既有测试回归**

Run: `./gradlew.bat :app:compileAppDebugKotlin :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.*" --console=plain`
Expected: PASS

- [ ] **Step 6.5: 提交**

```bash
unix2dos app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt
git add app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt
git commit -m "feat(eink): 桥接线目录/字体端口，applyStyle 部分写入与标题解钉"
```

---

### Task 7: 桥——分页缓存键补全 extent 影响项（裂缝修复）

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderChapterPager.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/DecorationCacheKeyFragmentTest.kt`

- [ ] **Step 7.1: 写失败测试**

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.help.config.ReadBookConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DecorationCacheKeyFragmentTest {

    private val base = ReadBookConfig.Config()

    @Test
    fun `页眉字号变化改变键片段`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerFontSize = 14)),
        )
    }

    @Test
    fun `页眉上下边距变化改变键片段`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerPaddingTop = 4)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(footerPaddingBottom = 8)),
        )
    }

    @Test
    fun `分割线与显隐变化改变键片段`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(showHeaderLine = true)),
        )
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerMode = 1)),
        )
    }

    @Test
    fun `左右边距同样进键但值独立`() {
        assertNotEquals(
            decorationCacheKeyFragment(base),
            decorationCacheKeyFragment(base.copy(headerPaddingLeft = 20)),
        )
    }

    @Test
    fun `同值键片段稳定`() {
        assertEquals(decorationCacheKeyFragment(base), decorationCacheKeyFragment(base.copy()))
    }
}
```

- [ ] **Step 7.2: 运行确认失败**

Run: `./gradlew.bat :app:compileAppDebugKotlin --console=plain`
Expected: FAIL（decorationCacheKeyFragment 未定义）

- [ ] **Step 7.3: 实现**

在 `ReaderChapterPager.kt` 文件末尾（class 外）添加：

```kotlin
/**
 * 页眉/页脚装饰的缓存键片段：这些参数经 extent（按字号/字体度量/
 * 上下边距/分割线推导分页预留高度）影响正文分页，任一变化必须触发
 * 重排。修复缺陷：改页眉上下边距/字号后条带高度变化但缓存键不含
 * 这些键，旧页坐标继续使用导致错位。
 */
internal fun decorationCacheKeyFragment(config: ReadBookConfig.Config): String = buildString {
    append(config.headerFontSize).append(',')
    append(config.footerFontSize).append(',')
    append(config.headerFont).append(',')
    append(config.footerFont).append(',')
    append(config.applyHeaderStyle).append(',')
    append(config.headerMode).append(',')
    append(config.footerMode).append(',')
    append(config.showHeaderLine).append(',')
    append(config.showFooterLine).append(',')
    append(config.headerPaddingTop).append(',').append(config.headerPaddingBottom)
        .append(',').append(config.headerPaddingLeft).append(',').append(config.headerPaddingRight)
        .append(',')
    append(config.footerPaddingTop).append(',').append(config.footerPaddingBottom)
        .append(',').append(config.footerPaddingLeft).append(',').append(config.footerPaddingRight)
}
```

在 `cacheKey(...)` 方法内、`append(ReadBookConfig.durConfig.highlightRules.hashCode())` 之前插入一行：

```kotlin
            append(decorationCacheKeyFragment(ReadBookConfig.durConfig)).append('|')
```

- [ ] **Step 7.4: 跑测试确认通过**

Run: `./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.DecorationCacheKeyFragmentTest" --console=plain`
Expected: PASS

- [ ] **Step 7.5: 提交**

```bash
unix2dos app/src/main/java/io/legado/app/eink/bridge/ReaderChapterPager.kt app/src/test/java/io/legado/app/eink/bridge/DecorationCacheKeyFragmentTest.kt
git add app/src/main/java/io/legado/app/eink/bridge/ReaderChapterPager.kt app/src/test/java/io/legado/app/eink/bridge/DecorationCacheKeyFragmentTest.kt
git commit -m "fix(eink): 分页缓存键补全页眉页脚 extent 影响项"
```

---

### Task 8: 模块——变更路由纯函数与目录 UI 辅助

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleRouting.kt`
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleCatalogUi.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderStyleRoutingTest.kt`

- [ ] **Step 8.1: 写失败测试**

```kotlin
package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.FallbackReaderStyleCatalog
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleRoutingTest {

    private val catalog = FallbackReaderStyleCatalog.create()

    @Test
    fun `无变更不重排`() {
        val style = ReaderTextStyle()
        assertFalse(styleChangeNeedsRelayout(catalog, style, style))
    }

    @Test
    fun `页眉左右边距变更不重排`() {
        val old = ReaderTextStyle()
        val new = old.copy(headerPaddingLeft = 20)
        assertFalse(styleChangeNeedsRelayout(catalog, old, new))
    }

    @Test
    fun `页眉上下边距变更需重排`() {
        val old = ReaderTextStyle()
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(headerPaddingTop = 4)))
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(footerPaddingBottom = 8)))
    }

    @Test
    fun `正文字号与边距变更需重排`() {
        val old = ReaderTextStyle()
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(textSize = 22)))
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(paddingLeft = 20)))
    }

    @Test
    fun `目录缺失的参数默认需要重排`() {
        val old = ReaderTextStyle()
        // 标题字号不在回落目录——保守按需重排处理
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(titleSize = 30)))
        assertTrue(styleChangeNeedsRelayout(catalog, old, old.copy(bodyFont = ReaderFontSelection.Serif)))
    }
}
```

- [ ] **Step 8.2: 运行确认失败**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin --console=plain`
Expected: FAIL

- [ ] **Step 8.3: 实现 ReaderStyleRouting.kt**

```kotlin
package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle

/**
 * 排版参数变更 → 是否需要重新分页：对比新旧快照，任一「已变化且目录
 * 标记 affectsLayout」的参数即重排。目录中不存在的参数保守视为需要
 * 重排。字段→id 映射与目录同源，新增参数两处同步。
 */
internal fun styleChangeNeedsRelayout(
    catalog: ReaderStyleCatalog,
    old: ReaderTextStyle,
    new: ReaderTextStyle,
): Boolean {
    val diffs = listOf(
        Ids.BODY_SIZE to (old.textSize != new.textSize),
        Ids.BODY_LETTER_SPACING to (old.letterSpacing != new.letterSpacing),
        Ids.BODY_INDENT to (old.indentChars != new.indentChars),
        Ids.BODY_LINE_SPACING to (old.lineSpacing != new.lineSpacing),
        Ids.BODY_PARAGRAPH_SPACING to (old.paragraphSpacing != new.paragraphSpacing),
        Ids.BODY_FONT to (old.bodyFont != new.bodyFont),
        Ids.BODY_WEIGHT to (old.bodyWeight != new.bodyWeight),
        Ids.BODY_PADDING_TOP to (old.paddingTop != new.paddingTop),
        Ids.BODY_PADDING_BOTTOM to (old.paddingBottom != new.paddingBottom),
        Ids.BODY_PADDING_LEFT to (old.paddingLeft != new.paddingLeft),
        Ids.BODY_PADDING_RIGHT to (old.paddingRight != new.paddingRight),
        Ids.TITLE_FONT to (old.titleFont != new.titleFont),
        Ids.TITLE_WEIGHT to (old.titleWeight != new.titleWeight),
        Ids.TITLE_MODE to (old.titleMode != new.titleMode),
        Ids.TITLE_SIZE to (old.titleSize != new.titleSize),
        Ids.TITLE_TOP_SPACING to (old.titleTopSpacing != new.titleTopSpacing),
        Ids.TITLE_BOTTOM_SPACING to (old.titleBottomSpacing != new.titleBottomSpacing),
        Ids.TITLE_LINE_SPACING to (old.titleLineSpacing != new.titleLineSpacing),
        Ids.HEADER_FONT to (old.headerFont != new.headerFont),
        Ids.HEADER_VISIBILITY to (old.headerVisible != new.headerVisible),
        Ids.HEADER_SIZE to (old.headerSize != new.headerSize),
        Ids.HEADER_DIVIDER to (old.headerDivider != new.headerDivider),
        Ids.HEADER_PADDING_TOP to (old.headerPaddingTop != new.headerPaddingTop),
        Ids.HEADER_PADDING_BOTTOM to (old.headerPaddingBottom != new.headerPaddingBottom),
        Ids.HEADER_PADDING_LEFT to (old.headerPaddingLeft != new.headerPaddingLeft),
        Ids.HEADER_PADDING_RIGHT to (old.headerPaddingRight != new.headerPaddingRight),
        Ids.FOOTER_VISIBILITY to (old.footerVisible != new.footerVisible),
        Ids.FOOTER_DIVIDER to (old.footerDivider != new.footerDivider),
        Ids.FOOTER_PADDING_TOP to (old.footerPaddingTop != new.footerPaddingTop),
        Ids.FOOTER_PADDING_BOTTOM to (old.footerPaddingBottom != new.footerPaddingBottom),
        Ids.FOOTER_PADDING_LEFT to (old.footerPaddingLeft != new.footerPaddingLeft),
        Ids.FOOTER_PADDING_RIGHT to (old.footerPaddingRight != new.footerPaddingRight),
    )
    return diffs.any { (id, differs) -> differs && (catalog.find(id)?.affectsLayout ?: true) }
}
```

- [ ] **Step 8.4: 实现 ReaderStyleCatalogUi.kt**

```kotlin
package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import kotlin.math.roundToInt

/** 目录 → UI 呈现辅助：值域/默认档/可用性（步进粒度与标签由调用方定）。 */

internal fun ReaderStyleCatalog.available(id: String): Boolean =
    find(id)?.available ?: false

private fun ReaderStyleCatalog.stepped(id: String): ReaderStyleParam.Stepped? =
    find(id) as? ReaderStyleParam.Stepped

/** 整型值域（参数缺失时 0..0，仅作展示兜底，不用于钳制）。 */
internal fun ReaderStyleCatalog.intRange(id: String): IntRange {
    val p = stepped(id) ?: return 0..0
    return p.min.roundToInt()..p.max.roundToInt()
}

/** 整型钳制（参数缺失时不钳制，原值透传）。 */
internal fun ReaderStyleCatalog.clampInt(id: String, value: Int): Int {
    val p = stepped(id) ?: return value
    return value.coerceIn(p.min.roundToInt(), p.max.roundToInt())
}

/** 整型默认值。 */
internal fun ReaderStyleCatalog.defaultInt(id: String): Int =
    stepped(id)?.default?.roundToInt() ?: 0

/** 「默认」标识档位（在值域内才显示）。 */
internal fun ReaderStyleCatalog.defaultStep(id: String): Int? {
    val p = stepped(id) ?: return null
    val d = p.default.roundToInt()
    return d.takeIf { it in p.min.roundToInt()..p.max.roundToInt() }
}

/** 浮点参数按步进映射为整型档位域（如字距 -0.5..0.5、步进 0.05 → -10..10）。 */
internal fun ReaderStyleCatalog.floatStepIndexRange(id: String, step: Float): IntRange {
    val p = stepped(id) ?: return 0..0
    return (p.min / step).roundToInt()..(p.max / step).roundToInt()
}

/** 浮点参数默认档位。 */
internal fun ReaderStyleCatalog.floatDefaultStep(id: String, step: Float): Int? {
    val p = stepped(id) ?: return null
    val d = (p.default / step).roundToInt()
    return d.takeIf { it in floatStepIndexRange(id, step) }
}
```

- [ ] **Step 8.5: 跑测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --console=plain`
Expected: PASS

- [ ] **Step 8.6: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleRouting.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleCatalogUi.kt modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderStyleRoutingTest.kt
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleRouting.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderStyleCatalogUi.kt modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderStyleRoutingTest.kt
git commit -m "feat(eink): 排版参数目录 UI 辅助与变更路由判定"
```

---

### Task 9: 模块——VM 改造 + 移除 textBold 端口（跨层一笔）

本任务跨模块契约、VM、UI 面板与桥四处，必须一次提交保证编译。

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt`（仅 ReaderOtherPanel 去掉加粗行）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`（仅去掉 onToggleTextBold 实参）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt`（删 setTextBold/textBold 成员）
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt`（删对应 override）
- Modify: `modules/eink/build.gradle.kts`（0.2.0 → 0.3.0）

- [ ] **Step 9.1: VM——目录持有与统一路由**

在 `ReaderViewModel` 类体（`private val engine get() = ...` 附近）加：

```kotlin
    /** 排版参数目录（宿主协商；旧宿主回落内置基线）。 */
    val styleCatalog: ReaderStyleCatalog by lazy {
        engine.styleCatalog() ?: FallbackReaderStyleCatalog.create()
    }
```

补充 import：

```kotlin
import io.legado.app.eink.contract.FallbackReaderStyleCatalog
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
```

（`kotlinx.coroutines.flow.*` 已有部分 import，按编译器提示合并。）

- [ ] **Step 9.2: VM——替换 applyLayoutStyle/applyStyleOnly 为 diff 路由**

删除 `applyLayoutStyle` 与 `applyStyleOnly` 两个私有方法，新增：

```kotlin
    /**
     * 应用排版参数变更：写回配置并读回快照，按目录 diff 判定是否需要
     * 重新分页（affectsLayout 参数变更 → 200ms 防抖重排；仅纯绘制参数
     * 变更即时生效不重排）。
     */
    private fun applyStyleChange(change: (ReaderTextStyle) -> ReaderTextStyle) {
        val old = _uiState.value.style
        val new = change(old)
        engine.applyStyle(new)
        _uiState.update { it.copy(style = engine.currentStyle()) }
        if (styleChangeNeedsRelayout(styleCatalog, old, new)) {
            scheduleRelayout()
        }
    }
```

- [ ] **Step 9.3: VM——重写既有 setters（全部走 applyStyleChange + 目录钳制）**

整块替换「排版参数」区段的 setters（保持注释风格）：

```kotlin
    fun setTextSize(value: Int) = applyStyleChange {
        it.copy(textSize = styleCatalog.clampInt(Ids.BODY_SIZE, value))
    }

    /** 字距按 0.05 步进索引设置（目录值域映射，避免浮点累加漂移）。 */
    fun setLetterSpacing(step: Int) = applyStyleChange {
        val range = styleCatalog.floatStepIndexRange(Ids.BODY_LETTER_SPACING, LETTER_SPACING_STEP)
        val safe = step.coerceIn(range.first, range.last)
        it.copy(letterSpacing = safe * LETTER_SPACING_STEP)
    }

    fun setLineSpacing(value: Int) = applyStyleChange {
        it.copy(lineSpacing = styleCatalog.clampInt(Ids.BODY_LINE_SPACING, value))
    }

    fun setParagraphSpacing(value: Int) = applyStyleChange {
        it.copy(paragraphSpacing = styleCatalog.clampInt(Ids.BODY_PARAGRAPH_SPACING, value))
    }

    fun setIndent(value: Int) = applyStyleChange {
        it.copy(indentChars = styleCatalog.clampInt(Ids.BODY_INDENT, value))
    }

    fun setPaddingLeft(value: Int) = applyStyleChange {
        it.copy(paddingLeft = styleCatalog.clampInt(Ids.BODY_PADDING_LEFT, value))
    }

    fun setPaddingTop(value: Int) = applyStyleChange {
        it.copy(paddingTop = styleCatalog.clampInt(Ids.BODY_PADDING_TOP, value))
    }

    fun setPaddingRight(value: Int) = applyStyleChange {
        it.copy(paddingRight = styleCatalog.clampInt(Ids.BODY_PADDING_RIGHT, value))
    }

    fun setPaddingBottom(value: Int) = applyStyleChange {
        it.copy(paddingBottom = styleCatalog.clampInt(Ids.BODY_PADDING_BOTTOM, value))
    }

    // ---- 页眉/页脚边距：上下边距经 extent 影响分页（diff 路由自动判定
    // 走重排），左右边距仅条带内部即时生效。 ----

    fun setHeaderPaddingLeft(value: Int) = applyStyleChange {
        it.copy(headerPaddingLeft = styleCatalog.clampInt(Ids.HEADER_PADDING_LEFT, value))
    }

    fun setHeaderPaddingTop(value: Int) = applyStyleChange {
        it.copy(headerPaddingTop = styleCatalog.clampInt(Ids.HEADER_PADDING_TOP, value))
    }

    fun setHeaderPaddingRight(value: Int) = applyStyleChange {
        it.copy(headerPaddingRight = styleCatalog.clampInt(Ids.HEADER_PADDING_RIGHT, value))
    }

    fun setHeaderPaddingBottom(value: Int) = applyStyleChange {
        it.copy(headerPaddingBottom = styleCatalog.clampInt(Ids.HEADER_PADDING_BOTTOM, value))
    }

    fun setFooterPaddingLeft(value: Int) = applyStyleChange {
        it.copy(footerPaddingLeft = styleCatalog.clampInt(Ids.FOOTER_PADDING_LEFT, value))
    }

    fun setFooterPaddingTop(value: Int) = applyStyleChange {
        it.copy(footerPaddingTop = styleCatalog.clampInt(Ids.FOOTER_PADDING_TOP, value))
    }

    fun setFooterPaddingRight(value: Int) = applyStyleChange {
        it.copy(footerPaddingRight = styleCatalog.clampInt(Ids.FOOTER_PADDING_RIGHT, value))
    }

    fun setFooterPaddingBottom(value: Int) = applyStyleChange {
        it.copy(footerPaddingBottom = styleCatalog.clampInt(Ids.FOOTER_PADDING_BOTTOM, value))
    }
```

- [ ] **Step 9.4: VM——新增协商扩展 setters（追加到上块之后）**

```kotlin
    // ---- 协商扩展参数（目录可用性由 UI 入口判定，VM 只管写） ----

    fun setBodyFont(selection: ReaderFontSelection) = applyStyleChange {
        it.copy(bodyFont = selection)
    }

    fun setBodyWeight(value: Int) = applyStyleChange {
        it.copy(bodyWeight = styleCatalog.clampInt(Ids.BODY_WEIGHT, value))
    }

    fun setTitleFont(selection: ReaderFontSelection) = applyStyleChange {
        it.copy(titleFont = selection)
    }

    fun setTitleWeight(value: Int) = applyStyleChange {
        it.copy(titleWeight = styleCatalog.clampInt(Ids.TITLE_WEIGHT, value))
    }

    fun setTitleMode(value: Int) = applyStyleChange {
        it.copy(titleMode = value.coerceIn(0, 2))
    }

    fun setTitleSize(value: Int) = applyStyleChange {
        it.copy(titleSize = styleCatalog.clampInt(Ids.TITLE_SIZE, value))
    }

    fun setTitleTopSpacing(value: Int) = applyStyleChange {
        it.copy(titleTopSpacing = styleCatalog.clampInt(Ids.TITLE_TOP_SPACING, value))
    }

    fun setTitleBottomSpacing(value: Int) = applyStyleChange {
        it.copy(titleBottomSpacing = styleCatalog.clampInt(Ids.TITLE_BOTTOM_SPACING, value))
    }

    fun setTitleLineSpacing(value: Int) = applyStyleChange {
        it.copy(titleLineSpacing = styleCatalog.clampInt(Ids.TITLE_LINE_SPACING, value))
    }

    fun setHeaderFont(selection: ReaderFontSelection) = applyStyleChange {
        it.copy(headerFont = selection)
    }

    fun setHeaderSize(value: Int) = applyStyleChange {
        it.copy(headerSize = styleCatalog.clampInt(Ids.HEADER_SIZE, value))
    }

    fun setHeaderDivider(value: Boolean) = applyStyleChange {
        it.copy(headerDivider = value)
    }

    fun setFooterDivider(value: Boolean) = applyStyleChange {
        it.copy(footerDivider = value)
    }

    /** 页眉显隐：写宿主 HeaderMode 1/2；随后重算条带可见性即时生效。 */
    fun setHeaderVisible(value: Boolean) {
        applyStyleChange { it.copy(headerVisible = value) }
        updateTipInfo()
    }

    /** 页脚显隐：写宿主 FooterMode 0/1；随后重算条带可见性即时生效。 */
    fun setFooterVisible(value: Boolean) {
        applyStyleChange { it.copy(footerVisible = value) }
        updateTipInfo()
    }

    // ---- 字体列表（字体配置弹层数据源） ----

    private val _fontOptions = MutableStateFlow<List<ReaderFontOption>>(emptyList())

    /** 可选字体文件（宿主字体文件夹枚举）。 */
    val fontOptions = _fontOptions.asStateFlow()

    /** 拉取字体文件列表（打开字体配置弹层时调用）。 */
    fun loadFontOptions() {
        viewModelScope.launch(Dispatchers.IO) {
            _fontOptions.value = engine.availableFonts()
        }
    }

    /** 设置字体文件夹（SAF tree uri）并刷新字体列表。 */
    fun setFontFolder(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            engine.setFontFolder(uri)
            _fontOptions.value = engine.availableFonts()
        }
    }
```

- [ ] **Step 9.5: VM/契约/桥/面板——移除 textBold 端口与「正文加粗」开关**

1. 删除 VM 的 `toggleTextBold()` 方法；删除 `applyStyleOnly` 内对 `textBold` 的更新（该方法已整体删除）；`ReaderUiState` 删除 `val textBold: Boolean = false` 字段。
2. `ReaderEngine.kt` 删除两个成员及其 KDoc：
   ```kotlin
   fun setTextBold(enabled: Boolean)
   val textBold: Boolean
   ```
3. `ReaderEngineImpl.kt` 删除 `override fun setTextBold(...)` 与 `override val textBold ...` 两个成员。
4. `ReaderMenus.kt` 的 `ReaderOtherPanel` 去掉 `onToggleTextBold` 参数与「正文加粗」行：
   ```kotlin
   @Composable
   internal fun ReaderOtherPanel(
       state: ReaderUiState,
       onToggleKeepScreenOn: () -> Unit,
       onToggleHideStatusBar: () -> Unit,
   ) {
       ToggleRow(label = "保持屏幕常亮", checked = state.keepScreenOn, onToggle = onToggleKeepScreenOn)
       ToggleRow(label = "隐藏状态栏", checked = state.hideStatusBar, onToggle = onToggleHideStatusBar)
   }
   ```
5. `ReaderScreen.kt` 的 `ReaderOtherPanel(...)` 调用去掉 `onToggleTextBold = viewModel::toggleTextBold` 一行。
6. `modules/eink/build.gradle.kts`：`grep -n "0.2.0" modules/eink/build.gradle.kts` 找到版本行，把 `0.2.0` 改为 `0.3.0`（契约破坏性变更）。

- [ ] **Step 9.6: 清理失效常量并确认无残留引用**

```bash
grep -rn "MIN_TEXT_SIZE\|MAX_TEXT_SIZE\|MAX_LETTER_SPACING\|MAX_LINE_SPACING\|MAX_PARAGRAPH_SPACING\|MAX_PADDING_HORIZONTAL\|MAX_PADDING_VERTICAL\|MIN_INDENT_CHARS\|MAX_INDENT_CHARS\|LETTER_SPACING_STEPS\|applyStyleOnly\|applyLayoutStyle\|toggleTextBold" modules/eink/src/main app/src/main --include=*.kt
```

Expected: 仅 `ReaderMenus.kt` 中面板滑条对旧常量的引用（Task 10 处理）。VM 文件底部的 `MIN_TEXT_SIZE/MAX_TEXT_SIZE/MAX_LETTER_SPACING/MAX_LINE_SPACING/MAX_PARAGRAPH_SPACING/MAX_PADDING_HORIZONTAL/MAX_PADDING_VERTICAL/MIN_INDENT_CHARS/MAX_INDENT_CHARS/LETTER_SPACING_STEPS` 常量声明此时删除会在 Task 10 前破坏 ReaderMenus 编译——**本任务保留常量声明不删**，Task 10 改完面板后一并删除。

- [ ] **Step 9.7: 编译 + 测试**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin --console=plain`
Expected: PASS（模块测试全绿；两端编译通过）

- [ ] **Step 9.8: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt modules/eink/build.gradle.kts
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt modules/eink/build.gradle.kts
git commit -m "refactor(eink): VM 排版调参走目录 diff 路由，新增扩展 setter 并移除加粗端口"
```

---

### Task 10: 模块——排版面板三入口行、默认标识与弹层状态机（先接边距）

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`（删失效常量）

- [ ] **Step 10.1: ReaderMenus——组件可见性调整**

1. `SliderRow` 改 `internal` 并加标识参数：
   ```kotlin
   @Composable
   internal fun SliderRow(
       label: String?,
       value: Int,
       valueRange: IntRange,
       thumbLabel: (Int) -> String,
       tickStep: Int,
       onSetValue: (Int) -> Unit,
       markerStep: Int? = null,
   ) {
   ```
   函数体内 `EInkSteppedSlider(...)` 调用追加实参：
   ```kotlin
           markerStep = markerStep,
           markerLabel = markerStep?.let { "默认" },
   ```
2. `ToggleRow`、`PanelTabRow` 改 `internal`（新弹层复用）。
3. `SliderLabelWidth` 常量改 `internal val`（新弹层的 ChoiceRow 复用）。

- [ ] **Step 10.2: ReaderMenus——三入口行与面板改造**

新增组件（放在 `ReaderLayoutPanel` 之前）：

```kotlin
/** 三入口行：一行多枚等宽文本按钮（字体配置/信息配置/边距调整）。 */
@Composable
private fun StyleEntryRow(entries: List<Pair<String, () -> Unit>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EInkSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        entries.forEach { (label, onClick) ->
            EInkButton(
                text = label,
                onClick = onClick,
                modifier = Modifier.weight(1f),
                height = 44.dp,
                role = Role.Button,
            )
        }
    }
}
```

替换整个 `ReaderLayoutPanel`：

```kotlin
/**
 * 排版面板：5 行档位滑条（字号/字距/缩进/行距/段距，值域与「默认」
 * 标识来自协商目录）+ 一行入口按钮（字体配置/信息配置/边距调整，
 * 按目录可用性显隐）。三个弹层均为居中透明卡片，实时预览不被遮挡。
 */
@Composable
internal fun ReaderLayoutPanel(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    onSetTextSize: (Int) -> Unit,
    onSetLetterSpacing: (Int) -> Unit,
    onSetIndent: (Int) -> Unit,
    onSetLineSpacing: (Int) -> Unit,
    onSetParagraphSpacing: (Int) -> Unit,
    onOpenFonts: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenMargins: () -> Unit,
) {
    SliderRow(
        label = "字号",
        value = style.textSize,
        valueRange = catalog.intRange(Ids.BODY_SIZE),
        thumbLabel = { "${it}sp" },
        tickStep = 4,
        onSetValue = onSetTextSize,
        markerStep = catalog.defaultStep(Ids.BODY_SIZE),
    )
    val lsRange = catalog.floatStepIndexRange(Ids.BODY_LETTER_SPACING, LETTER_SPACING_STEP)
    SliderRow(
        label = "字距",
        value = (style.letterSpacing / LETTER_SPACING_STEP).roundToInt()
            .coerceIn(lsRange.first, lsRange.last),
        valueRange = lsRange,
        thumbLabel = { "%.2f".format(it * LETTER_SPACING_STEP) },
        tickStep = 2,
        onSetValue = onSetLetterSpacing,
        markerStep = catalog.floatDefaultStep(Ids.BODY_LETTER_SPACING, LETTER_SPACING_STEP),
    )
    SliderRow(
        label = "缩进",
        value = style.indentChars,
        valueRange = catalog.intRange(Ids.BODY_INDENT),
        thumbLabel = { "${it}字" },
        tickStep = 1,
        onSetValue = onSetIndent,
        markerStep = catalog.defaultStep(Ids.BODY_INDENT),
    )
    SliderRow(
        label = "行距",
        value = style.lineSpacing,
        valueRange = catalog.intRange(Ids.BODY_LINE_SPACING),
        thumbLabel = { "%.1f倍".format(it / 10f) },
        tickStep = 2,
        onSetValue = onSetLineSpacing,
        markerStep = catalog.defaultStep(Ids.BODY_LINE_SPACING),
    )
    SliderRow(
        label = "段距",
        value = style.paragraphSpacing,
        valueRange = catalog.intRange(Ids.BODY_PARAGRAPH_SPACING),
        thumbLabel = { "%.1f行".format(it / 10f) },
        tickStep = 2,
        onSetValue = onSetParagraphSpacing,
        markerStep = catalog.defaultStep(Ids.BODY_PARAGRAPH_SPACING),
    )
    val entries = buildList {
        val fontReady = catalog.available(Ids.BODY_FONT) || catalog.available(Ids.TITLE_FONT) ||
            catalog.available(Ids.HEADER_FONT)
        val infoReady = catalog.available(Ids.TITLE_MODE) || catalog.available(Ids.HEADER_SIZE) ||
            catalog.available(Ids.HEADER_VISIBILITY)
        if (fontReady) add("字体配置" to onOpenFonts)
        if (infoReady) add("信息配置" to onOpenInfo)
        add("边距调整" to onOpenMargins)
    }
    StyleEntryRow(entries)
}
```

补充 ReaderMenus import：

```kotlin
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import kotlin.math.roundToInt
```

- [ ] **Step 10.3: ReaderMenus——边距弹框值域目录化**

`ReaderMarginDialog` 加 `catalog: ReaderStyleCatalog` 首参；三处 `MarginRows` 调用的 `maxVertical/maxHorizontal` 改为：

```kotlin
            0 -> MarginRows(
                topDp = style.paddingTop,
                bottomDp = style.paddingBottom,
                leftDp = style.paddingLeft,
                rightDp = style.paddingRight,
                maxVertical = catalog.intRange(Ids.BODY_PADDING_TOP).last,
                maxHorizontal = catalog.intRange(Ids.BODY_PADDING_LEFT).last,
                onSetTop = onSetPaddingTop,
                onSetBottom = onSetPaddingBottom,
                onSetLeft = onSetPaddingLeft,
                onSetRight = onSetPaddingRight,
            )

            1 -> MarginRows(
                topDp = style.headerPaddingTop,
                bottomDp = style.headerPaddingBottom,
                leftDp = style.headerPaddingLeft,
                rightDp = style.headerPaddingRight,
                maxVertical = catalog.intRange(Ids.HEADER_PADDING_TOP).last,
                maxHorizontal = catalog.intRange(Ids.HEADER_PADDING_LEFT).last,
                onSetTop = onSetHeaderPaddingTop,
                onSetBottom = onSetHeaderPaddingBottom,
                onSetLeft = onSetHeaderPaddingLeft,
                onSetRight = onSetHeaderPaddingRight,
            )

            else -> MarginRows(
                topDp = style.footerPaddingTop,
                bottomDp = style.footerPaddingBottom,
                leftDp = style.footerPaddingLeft,
                rightDp = style.footerPaddingRight,
                maxVertical = catalog.intRange(Ids.FOOTER_PADDING_TOP).last,
                maxHorizontal = catalog.intRange(Ids.FOOTER_PADDING_LEFT).last,
                onSetTop = onSetFooterPaddingTop,
                onSetBottom = onSetFooterPaddingBottom,
                onSetLeft = onSetFooterPaddingLeft,
                onSetRight = onSetFooterPaddingRight,
            )
```

- [ ] **Step 10.4: ReaderScreen——弹层状态机（三态枚举，本任务先接边距）**

1. 删除 `var showMarginDialog by remember { mutableStateOf(false) }`，替换为：

   ```kotlin
   // 排版设置弹层（字体配置/信息配置/边距调整）：居中透明卡片，
   // 打开期间面板与操作条隐藏；返回键逐级回退到排版展开态
   var styleDialog by remember { mutableStateOf<ReaderStyleDialog?>(null) }
   ```

   并在文件级（ReaderRoute 之外）新增：

   ```kotlin
   /** 排版设置的居中弹层形态。 */
   private enum class ReaderStyleDialog { Fonts, Info, Margin }
   ```

2. 全文把 `showMarginDialog` 引用替换为 `styleDialog != null`（`BackHandler` / `bottomBarVisible` / 面板挂载守卫 `panel?.takeIf { styleDialog == null }` / `onOpenPanel` 内 `showMarginDialog = false` → `styleDialog = null`），`BackHandler` 改为：

   ```kotlin
   BackHandler {
       when {
           styleDialog != null -> styleDialog = null
           panel != null -> panel = null
           uiState.controlsVisible -> viewModel.hideControls()
           else -> onBack()
       }
   }
   ```

3. `onOpenPanel` 内的 toggleOff 判定同步改为 `panel == target && styleDialog == null`。
4. `dismissToCleanReading` 增加 `styleDialog = null`。
5. `ReaderLayoutPanel` 调用改为：

   ```kotlin
                        ReaderLayoutPanel(
                            catalog = viewModel.styleCatalog,
                            style = uiState.style,
                            onSetTextSize = viewModel::setTextSize,
                            onSetLetterSpacing = viewModel::setLetterSpacing,
                            onSetIndent = viewModel::setIndent,
                            onSetLineSpacing = viewModel::setLineSpacing,
                            onSetParagraphSpacing = viewModel::setParagraphSpacing,
                            onOpenFonts = { styleDialog = ReaderStyleDialog.Fonts },
                            onOpenInfo = { styleDialog = ReaderStyleDialog.Info },
                            onOpenMargins = { styleDialog = ReaderStyleDialog.Margin },
                        )
   ```

6. 边距弹框挂载块替换为：

   ```kotlin
        // 排版弹层：居中透明卡片（面板保留不销毁），关闭后回到排版展开态
        when (styleDialog) {
            ReaderStyleDialog.Margin -> ReaderMarginDialog(
                catalog = viewModel.styleCatalog,
                style = uiState.style,
                onSetPaddingTop = viewModel::setPaddingTop,
                onSetPaddingBottom = viewModel::setPaddingBottom,
                onSetPaddingLeft = viewModel::setPaddingLeft,
                onSetPaddingRight = viewModel::setPaddingRight,
                onSetHeaderPaddingTop = viewModel::setHeaderPaddingTop,
                onSetHeaderPaddingBottom = viewModel::setHeaderPaddingBottom,
                onSetHeaderPaddingLeft = viewModel::setHeaderPaddingLeft,
                onSetHeaderPaddingRight = viewModel::setHeaderPaddingRight,
                onSetFooterPaddingTop = viewModel::setFooterPaddingTop,
                onSetFooterPaddingBottom = viewModel::setFooterPaddingBottom,
                onSetFooterPaddingLeft = viewModel::setFooterPaddingLeft,
                onSetFooterPaddingRight = viewModel::setFooterPaddingRight,
                onClose = { styleDialog = null },
                onBackdropClick = dismissToCleanReading,
            )

            ReaderStyleDialog.Fonts -> Unit          // Task 12 接入
            ReaderStyleDialog.Info -> Unit           // Task 11 接入
            null -> Unit
        }
   ```


- [ ] **Step 10.5: 删除 VM 失效常量**

删除 `ReaderViewModel.kt` 底部：`MIN_INDENT_CHARS/MAX_INDENT_CHARS/MIN_TEXT_SIZE/MAX_TEXT_SIZE/LETTER_SPACING_STEPS/MAX_LETTER_SPACING/MAX_LINE_SPACING/MAX_PARAGRAPH_SPACING/MAX_PADDING_HORIZONTAL/MAX_PADDING_VERTICAL` 十个常量（`LETTER_SPACING_STEP`、`RELAYOUT_DEBOUNCE_MS`、`CACHE_ALL`、`VIEW_SIZE_TIMEOUT_MS`、auto interval 常量保留）。若 `MarginTickStep` 仍在 ReaderMenus 使用则保留。

- [ ] **Step 10.6: 编译 + 模块测试**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin --console=plain`
Expected: PASS

- [ ] **Step 10.7: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt
git commit -m "feat(eink): 排版面板三入口行与滑条默认标识"
```

---

### Task 11: 模块——信息配置弹层

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderInfoConfigDialog.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`

- [ ] **Step 11.1: 实现 ReaderInfoConfigDialog.kt**

```kotlin
package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.theme.EInkSpacing

/**
 * 信息配置弹层（tabs 标题｜页眉｜页脚）：标题的版面信息（位置/字号/
 * 留白/行距）与页眉页脚几何（显隐/字号/分割线）。居中透明卡片，
 * 页面上下边缘不被遮挡，实时预览。参数可用性由排版面板入口守卫，
 * 本弹层内不再逐项判。
 */
@Composable
internal fun ReaderInfoConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    headerVisible: Boolean,
    footerVisible: Boolean,
    onSetTitleMode: (Int) -> Unit,
    onSetTitleSize: (Int) -> Unit,
    onSetTitleTopSpacing: (Int) -> Unit,
    onSetTitleBottomSpacing: (Int) -> Unit,
    onSetTitleLineSpacing: (Int) -> Unit,
    onSetHeaderVisible: (Boolean) -> Unit,
    onSetHeaderSize: (Int) -> Unit,
    onSetHeaderDivider: (Boolean) -> Unit,
    onSetFooterVisible: (Boolean) -> Unit,
    onSetFooterDivider: (Boolean) -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    EInkDialog(
        onDismiss = onClose,
        title = "信息配置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        PanelTabRow(
            labels = listOf("标题", "页眉", "页脚"),
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = 320.dp),
        ) {
            when (selectedTab) {
                0 -> {
                    ChoiceRow(
                        label = "标题位置",
                        options = listOf("居左", "居中", "隐藏"),
                        selected = style.titleMode ?: catalog.defaultInt(Ids.TITLE_MODE),
                        onSelect = onSetTitleMode,
                    )
                    SliderRow(
                        label = "标题字号",
                        value = style.titleSize ?: catalog.defaultInt(Ids.TITLE_SIZE),
                        valueRange = catalog.intRange(Ids.TITLE_SIZE),
                        thumbLabel = { "${it}sp" },
                        tickStep = 6,
                        onSetValue = onSetTitleSize,
                        markerStep = catalog.defaultStep(Ids.TITLE_SIZE),
                    )
                    SliderRow(
                        label = "上留白",
                        value = style.titleTopSpacing ?: catalog.defaultInt(Ids.TITLE_TOP_SPACING),
                        valueRange = catalog.intRange(Ids.TITLE_TOP_SPACING),
                        thumbLabel = { "${it}dp" },
                        tickStep = 10,
                        onSetValue = onSetTitleTopSpacing,
                        markerStep = catalog.defaultStep(Ids.TITLE_TOP_SPACING),
                    )
                    SliderRow(
                        label = "下留白",
                        value = style.titleBottomSpacing ?: catalog.defaultInt(Ids.TITLE_BOTTOM_SPACING),
                        valueRange = catalog.intRange(Ids.TITLE_BOTTOM_SPACING),
                        thumbLabel = { "${it}dp" },
                        tickStep = 10,
                        onSetValue = onSetTitleBottomSpacing,
                        markerStep = catalog.defaultStep(Ids.TITLE_BOTTOM_SPACING),
                    )
                    SliderRow(
                        label = "标题行距",
                        value = style.titleLineSpacing ?: catalog.defaultInt(Ids.TITLE_LINE_SPACING),
                        valueRange = catalog.intRange(Ids.TITLE_LINE_SPACING),
                        thumbLabel = { "%.1f倍".format(it / 10f) },
                        tickStep = 2,
                        onSetValue = onSetTitleLineSpacing,
                        markerStep = catalog.defaultStep(Ids.TITLE_LINE_SPACING),
                    )
                }

                1 -> {
                    ToggleRow(label = "显示页眉", checked = headerVisible, onToggle = onSetHeaderVisible)
                    SliderRow(
                        label = "页眉字号",
                        value = style.headerSize ?: catalog.defaultInt(Ids.HEADER_SIZE),
                        valueRange = catalog.intRange(Ids.HEADER_SIZE),
                        thumbLabel = { "${it}sp" },
                        tickStep = 6,
                        onSetValue = onSetHeaderSize,
                        markerStep = catalog.defaultStep(Ids.HEADER_SIZE),
                    )
                    ToggleRow(
                        label = "页眉分割线",
                        checked = style.headerDivider ?: false,
                        onToggle = onSetHeaderDivider,
                    )
                }

                else -> {
                    ToggleRow(label = "显示页脚", checked = footerVisible, onToggle = onSetFooterVisible)
                    ToggleRow(
                        label = "页脚分割线",
                        checked = style.footerDivider ?: true,
                        onToggle = onSetFooterDivider,
                    )
                }
            }
        }
    }
}

/** 选项行：标签在左，右侧等宽分段按钮（选中反白）。 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
    ) {
        EInkText(
            text = label,
            modifier = Modifier.width(SliderLabelWidth),
            style = EInkTheme.typography.bodyMedium,
        )
        options.forEachIndexed { index, option ->
            EInkButton(
                text = option,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                selected = index == selected,
                height = 40.dp,
                role = Role.Tab,
            )
        }
    }
}
```

（`EInkSpacing` 的实际包名以 `ReaderMenus.kt` 现 import 为准——若为 `...designsystem.theme.EInkSpacing` 之外的路径，抄 ReaderMenus 的 import。）

- [ ] **Step 11.2: ReaderScreen 挂载**

`when (styleDialog)` 的 `ReaderStyleDialog.Info -> Unit` 替换为：

```kotlin
            ReaderStyleDialog.Info -> ReaderInfoConfigDialog(
                catalog = viewModel.styleCatalog,
                style = uiState.style,
                headerVisible = uiState.headerVisible,
                footerVisible = uiState.footerVisible,
                onSetTitleMode = viewModel::setTitleMode,
                onSetTitleSize = viewModel::setTitleSize,
                onSetTitleTopSpacing = viewModel::setTitleTopSpacing,
                onSetTitleBottomSpacing = viewModel::setTitleBottomSpacing,
                onSetTitleLineSpacing = viewModel::setTitleLineSpacing,
                onSetHeaderVisible = viewModel::setHeaderVisible,
                onSetHeaderSize = viewModel::setHeaderSize,
                onSetHeaderDivider = viewModel::setHeaderDivider,
                onSetFooterVisible = viewModel::setFooterVisible,
                onSetFooterDivider = viewModel::setFooterDivider,
                onClose = { styleDialog = null },
                onBackdropClick = dismissToCleanReading,
            )
```

- [ ] **Step 11.3: 编译**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin :app:compileAppDebugKotlin --console=plain`
Expected: PASS

- [ ] **Step 11.4: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderInfoConfigDialog.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderInfoConfigDialog.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt
git commit -m "feat(eink): 信息配置弹层（标题/页眉/页脚）"
```

---

### Task 12: 模块——字体配置弹层与字体文件夹选择

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderFontConfigDialog.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`

- [ ] **Step 12.1: 实现 ReaderFontConfigDialog.kt**

```kotlin
package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog

/**
 * 字体配置弹层（tabs 正文｜标题｜页眉）：字体列表（系统预设 + 字体
 * 文件，标题/页眉多一项「跟随正文」）与字重滑条（正文/标题；宿主无
 * 页眉字重键）。字体文件来自宿主字体文件夹，经 [onPickFolder] 发起
 * SAF 选择后刷新列表。
 */
@Composable
internal fun ReaderFontConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    fontOptions: List<ReaderFontOption>,
    onSetBodyFont: (ReaderFontSelection) -> Unit,
    onSetBodyWeight: (Int) -> Unit,
    onSetTitleFont: (ReaderFontSelection) -> Unit,
    onSetTitleWeight: (Int) -> Unit,
    onSetHeaderFont: (ReaderFontSelection) -> Unit,
    onPickFolder: () -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    EInkDialog(
        onDismiss = onClose,
        title = "字体配置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        PanelTabRow(
            labels = listOf("正文", "标题", "页眉"),
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = 360.dp),
        ) {
            when (selectedTab) {
                0 -> {
                    FontRow(
                        label = "系统无衬线",
                        selected = style.bodyFont == ReaderFontSelection.Sans,
                        onClick = { onSetBodyFont(ReaderFontSelection.Sans) },
                    )
                    FontRow(
                        label = "系统衬线",
                        selected = style.bodyFont == ReaderFontSelection.Serif,
                        onClick = { onSetBodyFont(ReaderFontSelection.Serif) },
                    )
                    FontRow(
                        label = "系统等宽",
                        selected = style.bodyFont == ReaderFontSelection.Mono,
                        onClick = { onSetBodyFont(ReaderFontSelection.Mono) },
                    )
                    fontOptions.forEach { option ->
                        FontRow(
                            label = option.name,
                            selected = style.bodyFont == ReaderFontSelection.File(option.path),
                            onClick = { onSetBodyFont(ReaderFontSelection.File(option.path)) },
                        )
                    }
                    FolderPickerRow(onPickFolder = onPickFolder)
                    SliderRow(
                        label = "字重",
                        value = style.bodyWeight ?: catalog.defaultInt(Ids.BODY_WEIGHT),
                        valueRange = catalog.intRange(Ids.BODY_WEIGHT),
                        thumbLabel = { it.toString() },
                        tickStep = 100,
                        onSetValue = onSetBodyWeight,
                        markerStep = catalog.defaultStep(Ids.BODY_WEIGHT),
                    )
                }

                1 -> {
                    FontRow(
                        label = "跟随正文",
                        selected = style.titleFont == ReaderFontSelection.FollowBody,
                        onClick = { onSetTitleFont(ReaderFontSelection.FollowBody) },
                    )
                    FontRow(
                        label = "系统无衬线",
                        selected = style.titleFont == ReaderFontSelection.Sans,
                        onClick = { onSetTitleFont(ReaderFontSelection.Sans) },
                    )
                    FontRow(
                        label = "系统衬线",
                        selected = style.titleFont == ReaderFontSelection.Serif,
                        onClick = { onSetTitleFont(ReaderFontSelection.Serif) },
                    )
                    FontRow(
                        label = "系统等宽",
                        selected = style.titleFont == ReaderFontSelection.Mono,
                        onClick = { onSetTitleFont(ReaderFontSelection.Mono) },
                    )
                    fontOptions.forEach { option ->
                        FontRow(
                            label = option.name,
                            selected = style.titleFont == ReaderFontSelection.File(option.path),
                            onClick = { onSetTitleFont(ReaderFontSelection.File(option.path)) },
                        )
                    }
                    FolderPickerRow(onPickFolder = onPickFolder)
                    SliderRow(
                        label = "标题字重",
                        value = style.titleWeight ?: catalog.defaultInt(Ids.TITLE_WEIGHT),
                        valueRange = catalog.intRange(Ids.TITLE_WEIGHT),
                        thumbLabel = { it.toString() },
                        tickStep = 100,
                        onSetValue = onSetTitleWeight,
                        markerStep = catalog.defaultStep(Ids.TITLE_WEIGHT),
                    )
                }

                else -> {
                    FontRow(
                        label = "跟随正文",
                        selected = style.headerFont == ReaderFontSelection.FollowBody,
                        onClick = { onSetHeaderFont(ReaderFontSelection.FollowBody) },
                    )
                    FontRow(
                        label = "系统无衬线",
                        selected = style.headerFont == ReaderFontSelection.Sans,
                        onClick = { onSetHeaderFont(ReaderFontSelection.Sans) },
                    )
                    FontRow(
                        label = "系统衬线",
                        selected = style.headerFont == ReaderFontSelection.Serif,
                        onClick = { onSetHeaderFont(ReaderFontSelection.Serif) },
                    )
                    FontRow(
                        label = "系统等宽",
                        selected = style.headerFont == ReaderFontSelection.Mono,
                        onClick = { onSetHeaderFont(ReaderFontSelection.Mono) },
                    )
                    fontOptions.forEach { option ->
                        FontRow(
                            label = option.name,
                            selected = style.headerFont == ReaderFontSelection.File(option.path),
                            onClick = { onSetHeaderFont(ReaderFontSelection.File(option.path)) },
                        )
                    }
                    FolderPickerRow(onPickFolder = onPickFolder)
                }
            }
        }
    }
}

/** 字体选项行：整行按钮，选中反白。 */
@Composable
private fun FontRow(label: String, selected: Boolean, onClick: () -> Unit) {
    EInkButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        selected = selected,
        height = 44.dp,
        role = Role.Button,
    )
}

/** 字体文件夹入口行：打开系统文件夹选择器后刷新列表。 */
@Composable
private fun FolderPickerRow(onPickFolder: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EInkSpacing.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkButton(
            text = "选择字体文件夹…",
            onClick = onPickFolder,
            height = 40.dp,
            role = Role.Button,
        )
    }
}
```

（`EInkSpacing` import 同 Task 11 的说明。）

- [ ] **Step 12.2: ReaderScreen——SAF 启动器与字体弹层挂载**

1. `ReaderRoute` 顶部（`showRemoveConfirm` 声明附近）加：

   ```kotlin
   // 字体文件夹选择（SAF）：持久化读权限后交 VM 落库并刷新字体列表
   val fontFolderLauncher = rememberLauncherForActivityResult(
       ActivityResultContracts.OpenDocumentTree()
   ) { uri ->
       uri?.let {
           runCatching {
               context.contentResolver.takePersistableUriPermission(
                   it, Intent.FLAG_GRANT_READ_URI_PERMISSION
               )
           }
           viewModel.setFontFolder(it.toString())
       }
   }
   ```

   补充 import：

   ```kotlin
   import android.content.Intent
   import androidx.activity.compose.rememberLauncherForActivityResult
   import androidx.activity.result.contract.ActivityResultContracts
   ```

2. `when (styleDialog)` 的 `ReaderStyleDialog.Fonts -> Unit` 替换为：

   ```kotlin
            ReaderStyleDialog.Fonts -> ReaderFontConfigDialog(
                catalog = viewModel.styleCatalog,
                style = uiState.style,
                fontOptions = fontOptions,
                onSetBodyFont = viewModel::setBodyFont,
                onSetBodyWeight = viewModel::setBodyWeight,
                onSetTitleFont = viewModel::setTitleFont,
                onSetTitleWeight = viewModel::setTitleWeight,
                onSetHeaderFont = viewModel::setHeaderFont,
                onPickFolder = { fontFolderLauncher.launch(null) },
                onClose = {
                    styleDialog = null
                    // 关弹层时重拉，下次打开反映最新选择
                },
                onBackdropClick = dismissToCleanReading,
            )
   ```

3. `fontOptions` 收集与打开时加载（`uiState` 收集之后）：

   ```kotlin
   val fontOptions by viewModel.fontOptions.collectAsStateWithLifecycle()
   LaunchedEffect(styleDialog) {
       if (styleDialog == ReaderStyleDialog.Fonts) viewModel.loadFontOptions()
   }
   ```

- [ ] **Step 12.3: 编译**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin :app:compileAppDebugKotlin --console=plain`
Expected: PASS

- [ ] **Step 12.4: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderFontConfigDialog.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderFontConfigDialog.kt modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt
git commit -m "feat(eink): 字体配置弹层与字体文件夹选择"
```

---

### Task 13: 模块——页眉按配置字号渲染

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`

- [ ] **Step 13.1: 新增 headerTipTextStyle 并改造 ReaderHeader**

在 `tipTextStyle` 之后新增：

```kotlin
/**
 * 页眉文字样式：宿主字号可见（协商目录 header.size）后按配置字号渲染
 * （像素锚定，不随应用内字体缩放），行高仍锚定条带可用高度（任何配置
 * 不超出、文字底部不裁）。配置字号的行高需求（按 20/14 字面/行高比
 * 估计，与推导口径互逆）放不进可用高度时，回落 [tipTextStyle] 推导
 * （旧宿主/极端配置兜底）。
 */
@Composable
private fun headerTipTextStyle(availablePx: Float, configuredSizeSp: Int?): TextStyle {
    val density = LocalDensity.current
    val linePx = availablePx.takeIf { it > 0f } ?: with(density) { 23.dp.toPx() }
    val base = EInkTheme.typography.bodyMedium
    if (configuredSizeSp != null) {
        val sizePx = with(density) { configuredSizeSp.sp.toPx() }
        if (sizePx * 20f / 14f <= linePx) {
            return base.copy(
                fontSize = with(density) { sizePx.toDp().toSp() },
                lineHeight = with(density) { linePx.toDp().toSp() },
            )
        }
    }
    return base.copy(
        fontSize = with(density) { (linePx * 14f / 20f).toDp().toSp() },
        lineHeight = with(density) { linePx.toDp().toSp() },
    )
}
```

`ReaderHeader` 内的 `tipStyle` 计算替换为：

```kotlin
    val tipStyle = headerTipTextStyle(
        availablePx = extentPx -
            state.style.headerPaddingTop.dpPx() -
            state.style.headerPaddingBottom.dpPx(),
        configuredSizeSp = state.style.headerSize,
    )
```

`ReaderFooter` 不动（继续 `tipTextStyle` 推导——页脚有效字号可能来自完整模式独立配置，从 extent 推导在两种状态下都正确）。补充 `androidx.compose.ui.unit.sp` import（若未有）。

- [ ] **Step 13.2: 编译**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin --console=plain`
Expected: PASS

- [ ] **Step 13.3: 提交**

```bash
unix2dos modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt
git commit -m "feat(eink): 页眉按配置字号渲染，行高推导降为兜底"
```

---

### Task 14: 收尾——契约文档、全量验证与真机清单

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/README.md`（端口清单补 styleCatalog/availableFonts/setFontFolder；记录 setTextBold 移除）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`（宿主移植面新增三个端口成员说明，注明 0.3.0 契约版本）

- [ ] **Step 14.1: 更新契约文档**

在 `README.md` 的 ReaderEngine 端口清单补三行（保持既有格式）：

```markdown
- `styleCatalog(): ReaderStyleCatalog?` — 排版参数协商目录（null = 旧宿主，模块回落内置基线）
- `availableFonts(): List<ReaderFontOption>` — 字体文件夹枚举（suspend，IO）
- `setFontFolder(uri: String)` — 持久化字体文件夹（suspend）
```

并删除 setTextBold/textBold 两行（若有列出）；注明 0.3.0 起移除。

`EINK-PORTING.md` 的移植面章节补一段：宿主实现 ReaderEngine 时三个新成员的最低要求（styleCatalog 返回 null 即可让模块回落；实现字体能力需返回目录 + 字体枚举 + 文件夹持久化）。

- [ ] **Step 14.2: 全量验证**

```bash
./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.*" lintAppDebug verifyConfigArchitecture --continue --no-configuration-cache --console=plain
```

Expected: 新增测试全绿；既有 5 个 app 单测失败与 3 个 lint 基线错误不变（非本计划回归）；verifyConfigArchitecture 通过。

- [ ] **Step 14.3: 行尾检查 + 提交**

```bash
git diff --check
unix2dos modules/eink/src/main/java/io/legado/app/eink/contract/README.md modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md
git add modules/eink/src/main/java/io/legado/app/eink/contract/README.md modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md
git commit -m "docs(eink): 契约文档补排版目录与字体端口（0.3.0）"
```

- [ ] **Step 14.4: 真机验证清单（Clear7，serial 14be82fd，装 io.legato.kazusa.debug）**

执行者无法完成真机项，输出清单交用户：

1. 排版面板 6 行显示，滑条带「默认」标识；字号拉到 5/50 两端不越界。
2. 信息配置→标题字号 12→28：标题实时变大、每页行数减少、阅读位置保留。
3. 标题位置 居中/隐藏 立即生效。
4. 页眉字号 12→16：页眉条带变高、正文重排一次（不再错位——裂缝修复验证）；页眉上下边距 ±4dp 同样触发重排；左右边距即时生效不重排。
5. 页眉分割线开关出现/消失 0.5dp 分割线且重排。
6. 字体配置→正文选衬线：全文字形变化并重排；标题/页眉「跟随正文」随动。
7. 「选择字体文件夹…」SAF 选含 ttf 的目录后列表出现文件；选文件生效。
8. 字重 100..900 拖动实时变粗细。
9. 「其它」面板只剩两行（常亮/隐藏状态栏）。
10. 完整模式排版设置改字号后回 eink 阅读页，参数一致（共享配置语义未破坏）。

---

## 自审记录（Self-Review）

**Spec 覆盖：** §3 契约（Task 1-3）、§4 参数清单（Task 4/8/9/10/11/12）、§5.1 部分写入（Task 5/6）、§5.2 裂缝修复（Task 7+8 双侧）、§5.3 解钉（Task 5/6）、§5.4 渲染（Task 13）、§5.5 跟随语义（Task 5）、§6 UI（Task 10-12）、§7 兼容（FallbackCatalog + 显隐 null 语义）、§8 测试（各 Task 内联）、§9 非目标（未引入）。无缺口。

**类型一致性：** `buildStyleMutations(style, currentBodyFontPath)` 在 Task 5 定义、Task 6 消费签名一致；`applyStyleChange` 仅 Task 9 内使用；`styleChangeNeedsRelayout(catalog, old, new)` Task 8 定义、Task 9 消费一致；`decorationCacheKeyFragment(config)` Task 7 内定义与消费一致；ReaderStyleDialog 三态在 Task 10 定义、11/12 消费。

**已知执行注意：**
- `EInkSpacing` 的 import 路径以 ReaderMenus.kt 现有 import 为准。
- Task 9 是跨层提交，Step 9.6 的 grep 检查必须在提交前执行。
