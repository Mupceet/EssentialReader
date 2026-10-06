# 自动翻页非满页按时长比例缩减 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** eink 模块自动翻页时长按当页内容填充比缩放（满页 = 配置时长，章节末页等非满页按比例缩短，1 秒下限）；宿主只新增比例数据通路。

**Architecture:** 契约 `ReaderPageSnapshot` 新增缺省字段 `contentFillRatio`（子模块先行）；宿主映射器按分页几何计算填充；模块倒计时由整秒循环改毫秒循环，目标时长 = 配置时长 × 填充比。规格见 `docs/superpowers/specs/2026-10-06-eink-auto-page-partial-page-duration-design.md`。

**Tech Stack:** Kotlin、Jetpack Compose（模块 UI）、JUnit4（模块纯 JVM 测试）、Robolectric（宿主映射器测试）、Gradle（宿主与 eink-lib 两套独立构建）。

## Global Constraints

- 宿主与子模块是两套独立构建：子模块改动在 `eink-lib/` 目录用其自身 `gradlew.bat` 构建测试；宿主在仓库根目录。
- 推送顺序硬约束：先推 eink-lib 子模块（eink/lib 分支），再推宿主并带上子模块指针（推送本身需用户确认后执行）。
- 范围硬约束（用户已确认）：**不改** `ReaderAutoPagePolicy`、`ReaderCanvasSurface`、配置 UI、滚动模式、漫画阅读器；宿主自身阅读器时长行为不变。
- 契约纪律：模块不自行做几何推断，比例唯一来源是宿主填充的 `contentFillRatio`。
- 常量口径：`MIN_AUTO_INTERVAL_SEC = 1`、`MAX_AUTO_INTERVAL_SEC = 120`、`DEFAULT_AUTO_INTERVAL_SEC = 10`（eink `ReaderViewModel.kt` 尾部既有常量，禁止改动值）。
- 模块单测为纯 JVM JUnit4（模块无 Robolectric 依赖）；宿主映射器测试沿用既有 `@RunWith(RobolectricTestRunner::class)` `@Config(application = Application::class, sdk = [34])`。
- 所有文本改动跑 `git diff --check`；提交信息用中文 conventional commits（`feat(eink):` / `test(eink):` / `docs(eink):`）。
- JDK 21、minSdk 26（现有环境默认满足，无需额外配置）。

---

### Task 1: 契约新增 contentFillRatio 字段（子模块）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/ReaderPageSnapshot.kt:53-54`

**Interfaces:**
- Produces: `ReaderPageSnapshot.contentFillRatio: Float = 1f`（Task 2 模块消费、Task 3 宿主填充；缺省 1.0 保证旧宿主零改动）

- [ ] **Step 1: 在 `bookmarkBadge` 字段后新增字段**

`ReaderPageSnapshot.kt` 构造参数末尾（`val bookmarkBadge: Boolean = false,` 之后、右括号之前）插入：

```kotlin
    /**
     * 本页内容填充比 (0, 1]：本页最低内容盒相对内容区高度的占比，1 = 满页。
     * 宿主映射义务：由分页几何计算——底部剩余不足一行正文行高视为满页
     * （与分页器「下一行放不下即换页」判据同源），无内容元素的空页填 1.0；
     * 旧宿主缺省 1.0 = 满页全时长。模块不自行做几何推断，唯一消费方是
     * 自动翻页单页时长缩放（ReaderViewModel.autoPageDurationMillis）。
     */
    val contentFillRatio: Float = 1f,
```

- [ ] **Step 2: 子模块编译验证**

Run（Git Bash，仓库根目录起）:

```bash
cd "eink-lib" && ./gradlew.bat :modules:eink:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交（子模块仓库）**

```bash
cd "eink-lib" && git diff --check && git add modules/eink/src/main/java/io/legado/app/eink/contract/ReaderPageSnapshot.kt && git commit -m "feat(eink): 契约 ReaderPageSnapshot 新增 contentFillRatio——本页内容填充比 (0,1] 缺省 1f 向后兼容，宿主映射义务（底部剩余不足一行正文行高判满、空页填 1.0），唯一消费方为自动翻页单页时长缩放"
```

---

### Task 2: 模块单页时长缩放（纯函数 TDD + 倒计时毫秒化，子模块）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt:599-633`（`startAutoPlayJob`）及文件尾部常量区（约 1366-1370 行附近）
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderUiStateTest.kt`

**Interfaces:**
- Consumes: `ReaderPageSnapshot.contentFillRatio: Float`（Task 1）；既有 `_uiState.value.autoPlayIntervalSec`、`pageTurnAvailable`、`preparePageTurnEffect(forward = true)`、`engine.nextPage()`、`stopAutoPlay()`、`_messages`
- Produces: `internal fun autoPageDurationMillis(intervalSec: Int, contentFillRatio: Float): Long`（ReaderViewModel.kt 顶层函数，与既有 `MIN_AUTO_INTERVAL_SEC` 常量同文件）

- [ ] **Step 1: 在 `ReaderUiStateTest` 追加失败测试**

文件末尾类内（`无可渲染页时翻页不可用` 测试之后）追加三个用例：

```kotlin
    @Test
    fun `单页时长满页为配置时长且非满页按比例缩短`() {
        assertEquals(10_000L, autoPageDurationMillis(10, 1f))
        assertEquals(5_000L, autoPageDurationMillis(10, 0.5f))
        assertEquals(7_500L, autoPageDurationMillis(30, 0.25f))
    }

    @Test
    fun `单页时长一秒下限防墨水屏高频刷`() {
        // 短页比例趋零：钳到 1 秒
        assertEquals(1_000L, autoPageDurationMillis(10, 0.02f))
        // 配置已是 1 秒：任何比例都不低于 1 秒
        assertEquals(1_000L, autoPageDurationMillis(1, 0.5f))
    }

    @Test
    fun `单页时长配置与比例越界均钳制`() {
        // 配置越界钳回 1..120
        assertEquals(1_000L, autoPageDurationMillis(0, 1f))
        assertEquals(60_000L, autoPageDurationMillis(200, 0.5f))
        // 比例越界钳回 0..1
        assertEquals(10_000L, autoPageDurationMillis(10, 1.5f))
        assertEquals(1_000L, autoPageDurationMillis(10, 0f))
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd "eink-lib" && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderUiStateTest"
```

Expected: FAIL（`unresolved reference: autoPageDurationMillis`）

- [ ] **Step 3: 实现 `autoPageDurationMillis`**

`ReaderViewModel.kt` 文件尾部，紧跟 `MAX_AUTO_INTERVAL_SEC` 常量之后追加顶层函数：

```kotlin
/**
 * 自动翻页单页时长（毫秒）：配置时长按当页内容填充比缩放（契约
 * contentFillRatio，满页 = 1 即配置时长），1 秒下限——短页无下限会造成
 * 墨水屏高频整刷；下限即配置区间 [MIN_AUTO_INTERVAL_SEC]。
 */
internal fun autoPageDurationMillis(intervalSec: Int, contentFillRatio: Float): Long {
    val baseMillis = intervalSec.coerceIn(MIN_AUTO_INTERVAL_SEC, MAX_AUTO_INTERVAL_SEC) * 1000L
    val scaled = baseMillis * contentFillRatio.coerceIn(0f, 1f)
    return scaled.toLong().coerceAtLeast(MIN_AUTO_INTERVAL_SEC * 1000L)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
cd "eink-lib" && ./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderUiStateTest"
```

Expected: PASS（含既有用例，共 10 个）

- [ ] **Step 5: 重写 `startAutoPlayJob` 为毫秒倒计时**

整函数替换（原 599-633 行；`toggleAutoPlay`/`pauseAutoPlay`/`stopAutoPlay`/`setAutoPlayInterval` 不动）：

```kotlin
    private fun startAutoPlayJob() {
        if (autoPlayJob?.isActive == true) return
        _uiState.update { it.copy(autoPlayProgress = 0f) }
        autoPlayJob = viewModelScope.launch {
            while (isActive) {
                // 当前页倒计时：目标时长逐拍重读（配置变更立即生效，对齐旧整秒
                // 循环每拍读 interval 的语义），并按当页内容填充比缩放——满页为
                // 配置时长，章节末页等非满页按比例缩短（契约 contentFillRatio）
                var elapsedMillis = 0L
                while (isActive) {
                    val targetMillis = autoPageDurationMillis(
                        intervalSec = _uiState.value.autoPlayIntervalSec,
                        contentFillRatio = _uiState.value.page?.contentFillRatio ?: 1f,
                    )
                    if (elapsedMillis >= targetMillis) break
                    // 页脚进度条保持每秒至多刷新一次（墨水屏友好）；末拍取剩余
                    // 时长，翻页时刻精确落在缩放后时长（含不足 1 秒的短页）
                    val tickMillis = minOf(1_000L, targetMillis - elapsedMillis)
                    delay(tickMillis)
                    elapsedMillis += tickMillis
                    _uiState.update {
                        it.copy(
                            autoPlayProgress =
                                (elapsedMillis.toFloat() / targetMillis).coerceIn(0f, 1f),
                        )
                    }
                }
                if (!isActive) break
                // 刷新/装载窗口无可渲染页：本拍不翻页、清条重新起算——
                // 直连引擎翻章会弃当前重载目标章（判据见 pageTurnAvailable）
                if (!_uiState.value.pageTurnAvailable) {
                    _uiState.update { it.copy(autoPlayProgress = 0f) }
                    continue
                }
                // 直连引擎而非 nextPage()：自动翻页不触发倒计时重置
                //（水波纹效果仍按页触发，与手动翻页一致）
                preparePageTurnEffect(forward = true)
                if (!engine.nextPage()) {
                    stopAutoPlay()
                    _messages.emit(UserMessage.from(R.string.eink_reader_auto_page_end))
                    break
                }
                // 翻页即清条：满条与清空落在同一帧窗口，渲染层最多闪现一帧
                // 即回零——否则 100% 满条要挂到下一个整秒 tick 才消失，
                // 用户看到整行黑条持续一秒
                _uiState.update { it.copy(autoPlayProgress = 0f) }
            }
        }
    }
```

行为保真说明（实施者自查清单，不改代码）：翻到书末 `stopAutoPlay` + 消息不变；`pageTurnAvailable` 为 false 时等满一个目标时长后重试不变；暂停/恢复整段重起不变；每拍重读配置（旧循环语义）保留。

- [ ] **Step 6: 子模块全量单测 + 编译**

Run:

```bash
cd "eink-lib" && ./gradlew.bat :modules:eink:testDebugUnitTest && ./gradlew.bat :modules:eink:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交（子模块仓库）**

```bash
cd "eink-lib" && git diff --check && git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderUiStateTest.kt && git commit -m "feat(eink): 自动翻页单页时长按当页内容填充比缩放——倒计时整秒循环改毫秒循环，目标时长逐拍重读（调参立即生效对齐旧语义），tick 取 min(1s, 剩余) 保持页脚进度条每秒至多刷新一次，翻页时刻精确落点；autoPageDurationMillis 纯函数钳 1 秒下限防墨水屏高频刷，书末停止/pageTurnAvailable 重试/暂停整段重起语义不变"
```

---

### Task 3: 宿主映射器填充 contentFillRatio（TDD，宿主）

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt`（`map()` 约 50-66 行、`mapWithSpecs()` 签名约 69-78 行与构造调用约 184-193 行、object 内新增纯函数）
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt`

**Interfaces:**
- Consumes: `ReaderPage.contentTopPx/contentBottomPx/elements`、`ReaderAndroidPaginationStyle.bodyTextHeightPx/lineSpacingExtra`（既有字段）；`ReaderPageSnapshot.contentFillRatio`（Task 1）
- Produces: `ReaderPageSnapshotMapper.contentFillRatio(page: ReaderPage, bodyLineAdvancePx: Float): Float`（internal 纯函数）；`mapWithSpecs` 新增命名参数 `contentFillRatio: Float = 1f`

- [ ] **Step 1: 在 `ReaderPageSnapshotMapperTest` 追加失败测试**

文件 import 区补一行：

```kotlin
import io.legado.app.feature.reader.platform.ReaderAndroidPaginationStyle
```

文件末尾类内（`画笔规格拷贝测量耦合属性` 测试之后）追加 fixture 与五个用例：

```kotlin
    // ==== 内容填充比（自动翻页单页时长缩放口径）====

    private fun paginationStyle(
        bodyTextHeightPx: Float = 50f,
        lineSpacingExtra: Float = 0f,
    ): ReaderAndroidPaginationStyle {
        val paint = android.text.TextPaint().apply { textSize = 40f }
        val style = ReaderTextStyle(colorArgb = 0, fontSizePx = 40f)
        return ReaderAndroidPaginationStyle(
            bodyPaint = paint,
            titlePaint = paint,
            bodyStyle = style,
            titleStyle = style,
            paddingLeftPx = 0f,
            paddingTopPx = 0f,
            paddingRightPx = 0f,
            paddingBottomPx = 0f,
            bodyTextHeightPx = bodyTextHeightPx,
            titleTextHeightPx = bodyTextHeightPx,
            bodyBaselineOffsetPx = 40f,
            titleBaselineOffsetPx = 40f,
            lineSpacingExtra = lineSpacingExtra,
            titleLineSpacingExtra = 0f,
            paragraphSpacing = 0,
        )
    }

    @Test
    fun `填充比半页内容按内容区高度折算`() {
        val page = readerPage(
            listOf(
                textElement(0f, 0f, "首行", height = 50f),
                textElement(0f, 650f, "末行", height = 50f),
            ),
        )
        // 既有 fixture：contentTop 0、contentBottom 1400，末行 bottom 700 → 0.5
        assertEquals(
            0.5f,
            ReaderPageSnapshotMapper.contentFillRatio(page, bodyLineAdvancePx = 50f),
            0.001f,
        )
    }

    @Test
    fun `填充比底部剩余不足一行判满页`() {
        // 末行 bottom 1380，底部剩余 20 < 行高 50 → 满页（分页器「放不下即换页」同源判据）
        val page = readerPage(listOf(textElement(0f, 1330f, "近满", height = 50f)))
        assertEquals(
            1f,
            ReaderPageSnapshotMapper.contentFillRatio(page, bodyLineAdvancePx = 50f),
            0.001f,
        )
    }

    @Test
    fun `填充比空页与非渲染元素页均判满页`() {
        assertEquals(
            1f,
            ReaderPageSnapshotMapper.contentFillRatio(
                readerPage(emptyList()),
                bodyLineAdvancePx = 50f,
            ),
        )
        // Review/Action 等元素 eink 不渲染，不计入内容盒
        assertEquals(
            1f,
            ReaderPageSnapshotMapper.contentFillRatio(
                readerPage(
                    listOf(
                        ReaderElement.Review(
                            bounds = ReaderRect(0f, 0f, 10f, 10f),
                            count = 3,
                            paragraphIndex = 0,
                        ),
                    ),
                ),
                bodyLineAdvancePx = 50f,
            ),
        )
    }

    @Test
    fun `填充比图片元素计入内容盒且越界钳回一`() {
        val imagePage = readerPage(listOf(imageElement(0f, 100f, 40f, 700f)))
        assertEquals(
            0.5f,
            ReaderPageSnapshotMapper.contentFillRatio(imagePage, bodyLineAdvancePx = 50f),
            0.001f,
        )
        // 元素 bottom 越过 contentBottom（异常布局）：钳回 1
        val overflow = readerPage(listOf(textElement(0f, 1380f, "越界", height = 100f)))
        assertEquals(
            1f,
            ReaderPageSnapshotMapper.contentFillRatio(overflow, bodyLineAdvancePx = 50f),
            0.001f,
        )
    }

    @Test
    fun `快照缺省与显式填充比透传`() {
        // 既有 mapElements 路径（未传比例）缺省 1f = 满页全时长
        assertEquals(1f, mapElements(textElement(0f, 0f, "文")).contentFillRatio, 0.001f)
        val half = ReaderPageSnapshotMapper.mapWithSpecs(
            page = readerPage(listOf(textElement(0f, 0f, "文"))),
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            sdkInt = 30,
            sessionBook = null,
            readProgress = "0.0%",
            contentFillRatio = 0.5f,
            imageLoader = { _, _ -> { _, _ -> null } },
        )
        assertEquals(0.5f, half.contentFillRatio, 0.001f)
    }

    @Test
    fun `map 入口按正文行高加行距计算填充比`() {
        // 内容区高 1400，末行 bottom 700 = 半页；判满容差 = bodyTextHeightPx 50 + lineSpacingExtra 0
        val half = ReaderPageSnapshotMapper.map(
            page = readerPage(listOf(textElement(0f, 650f, "半页", height = 50f))),
            paginationStyle = paginationStyle(bodyTextHeightPx = 50f, lineSpacingExtra = 0f),
            sessionBook = null,
            readProgress = "0.0%",
        )
        assertEquals(0.5f, half.contentFillRatio, 0.001f)

        // 容差口径 = bodyTextHeightPx + lineSpacingExtra：末行 bottom 1380，
        // 剩余 20 < 50 + 8 → 判满页
        val full = ReaderPageSnapshotMapper.map(
            page = readerPage(listOf(textElement(0f, 1330f, "近满", height = 50f))),
            paginationStyle = paginationStyle(bodyTextHeightPx = 50f, lineSpacingExtra = 8f),
            sessionBook = null,
            readProgress = "0.0%",
        )
        assertEquals(1f, full.contentFillRatio, 0.001f)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run（仓库根目录）:

```bash
./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"
```

Expected: FAIL（`unresolved reference: contentFillRatio`）

- [ ] **Step 3: 实现映射器改动**

`ReaderPageSnapshotMapper.kt` 三处修改：

① `map()`（生产入口）改为把比例算好传下去：

```kotlin
    /** 生产入口：规格取分页同源样式画笔，进度文本由分页方按页上下文计算。 */
    fun map(
        page: ReaderPage,
        paginationStyle: ReaderAndroidPaginationStyle,
        sessionBook: Book?,
        readProgress: String,
        bookmarkBadge: Boolean = false,
    ): ReaderPageSnapshot =
        mapWithSpecs(
            page = page,
            titleSpec = paginationStyle.titlePaint.copyPaintSpec(),
            contentSpec = paginationStyle.bodyPaint.copyPaintSpec(),
            sdkInt = Build.VERSION.SDK_INT,
            sessionBook = sessionBook,
            readProgress = readProgress,
            bookmarkBadge = bookmarkBadge,
            contentFillRatio = contentFillRatio(
                page = page,
                bodyLineAdvancePx =
                    paginationStyle.bodyTextHeightPx + paginationStyle.lineSpacingExtra,
            ),
            imageLoader = ::defaultImageLoader,
        )
```

② `mapWithSpecs` 签名在 `bookmarkBadge: Boolean = false,` 之后、`imageLoader` 之前插参：

```kotlin
        contentFillRatio: Float = 1f,
```

构造调用（`return ReaderPageSnapshot(...)`）在 `bookmarkBadge = bookmarkBadge,` 之后补一行：

```kotlin
            contentFillRatio = contentFillRatio,
```

③ object 内（`readProgress` 函数之后）新增纯函数：

```kotlin
    /**
     * 内容填充比 (0, 1]：本页最低渲染内容盒（文本/图片元素）bottom 相对
     * 内容区高度的占比；契约 ReaderPageSnapshot.contentFillRatio 的计算
     * 义务，模块自动翻页单页时长缩放消费。判满容差与分页器「下一行放不下
     * 即换页」同源：底部剩余不足一行正文行高视为满页；无渲染内容元素
     * 或内容区退化（高度 ≤ 0）填 1.0 维持满页时长。
     */
    internal fun contentFillRatio(page: ReaderPage, bodyLineAdvancePx: Float): Float {
        val contentHeight = page.contentBottomPx - page.contentTopPx
        if (contentHeight <= 0f) return 1f
        var maxBottom = Float.NEGATIVE_INFINITY
        for (element in page.elements) {
            val bottom = when (element) {
                is ReaderElement.Text -> element.bounds.bottom
                is ReaderElement.Image -> element.bounds.bottom
                else -> null
            } ?: continue
            if (bottom > maxBottom) maxBottom = bottom
        }
        if (maxBottom == Float.NEGATIVE_INFINITY) return 1f
        if (page.contentBottomPx - maxBottom < bodyLineAdvancePx.coerceAtLeast(1f)) return 1f
        return ((maxBottom - page.contentTopPx) / contentHeight).coerceIn(0f, 1f)
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
./gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderPageSnapshotMapperTest"
```

Expected: PASS（既有用例 + 新增 6 个）

- [ ] **Step 5: 提交（宿主仓库）**

```bash
git diff --check && git add app/src/main/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapper.kt app/src/test/java/io/legado/app/eink/bridge/ReaderPageSnapshotMapperTest.kt && git commit -m "feat(eink): 映射器填充快照 contentFillRatio——文本/图片元素最低内容盒相对内容区高度，判满容差取 bodyTextHeightPx+lineSpacingExtra（与分页器放不下即换页同源），空页/非渲染元素页/越界钳 1f；mapWithSpecs 缺省 1f 既有调用零改动"
```

---

### Task 4: 汇总验证与交付

**Files:** 无新改动（只验证与提交收尾）

- [ ] **Step 1: 子模块全量单测**

```bash
cd "eink-lib" && ./gradlew.bat :modules:eink:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 宿主编译 + 全量单测（可后台长跑）**

```bash
./gradlew.bat :app:compileAppDebugKotlin
./gradlew.bat testAppDebugUnitTest --continue --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL`（若全量单测出现与本次改动无关的既有失败，逐条比对确认非本改动引入并在交付说明中列明）

- [ ] **Step 3: 宿主 bump 子模块指针**

```bash
git diff --check && git add eink-lib && git commit -m "chore(eink): 推进 eink-lib 指针至自动翻页非满页按时长比例缩减——契约 contentFillRatio + 模块倒计时毫秒化（1 秒下限、进度条每秒至多刷新）"
```

- [ ] **Step 4: 检查指针状态**

```bash
git -C eink-lib log --oneline -3 && git status --porcelain
```

Expected: 子模块两个提交（Task 1、Task 2）在 `eink/lib` 分支顶端；宿主工作区干净（`eink-lib` 指针已提交）。

- [ ] **Step 5: 推送（需用户确认后执行，顺序硬约束：先子模块后宿主）**

```bash
cd "eink-lib" && git push origin eink/lib
cd .. && git push origin eink/port/md3/modules
```

说明：宿主 CI 按子模块 ref 检出，先推宿主会挂「not our ref」。

- [ ] **Step 6: 交付说明**

按 AGENTS 交付说明要求输出：修改的职责范围（子模块契约+倒计时、宿主映射器通路）、关键取舍（内容填充比口径与判满容差、1 秒下限、毫秒化 tick 上限 1 秒）、实际验证命令与结果、未验证风险（真机墨水屏翻页节奏与短页进度条观感待复核）。
