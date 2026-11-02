# eink 翻页设置页实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「我的」页音量键开关重构为「翻页设置」页（音量键/滚轮/自定义键三项，滚轮与自定义键在 eink 模式全局生效），并从阅读界面「其它设置」面板移除音量键开关。

**Architecture:** 方案甲——自定义键并入按键判定纯函数（`volumePagingKeyEvent` → `pagingKeyEvent`），滚轮新增同构栈式 `EInkWheelHub` + `EInkHostActivity.onGenericMotionEvent` 分发；设置页向两个枢纽栈顶注册处理器复用生产判定做同源预览。规格见 `docs/superpowers/specs/2026-11-02-eink-paging-settings-design.md`。

**Tech Stack:** Kotlin、Jetpack Compose、JUnit4（中文反引号用例名 + Triple 锚定风格）、宿主 Gradle 单构建（子模块模块路径 `:modules:eink`）。

## Global Constraints

- 工作目录：`D:\Projects\AndroidProjects\EssentialReader-eink-paging-settings`（宿主 worktree，分支 `eink/paging-settings`）；子模块 `eink-lib/` 同名分支。所有 gradle 命令在宿主 worktree 根执行（宿主 `settings.gradle` 把 `eink-lib/modules/eink` 挂为 `:modules:eink`，一次构建同时覆盖两侧）。
- 提交纪律：模块内文件改动提交在 `eink-lib/`（cwd 切入）；宿主文件（`app/`）改动提交在 worktree 根；子模块指针最后由 Task 9 统一推进。**本次不 push**（推送前需补跑全量验证集，另定）。
- 契约新成员必须带默认实现（旧宿主诚实降级，对齐 `syncReadingProgress` 先例）；设置写入 fire-and-forget + UI 本地乐观状态；两方向键集经 `setPageKeys` 一次 `copy(...)` 原子提交，不引入 `*SettingsUpdate` 分发类型。
- 代码 namespace `io.legado.app`；测试风格 JUnit4、`org.junit.Assert`、中文反引号方法名；KDoc 中文、说明「为什么」。
- 每个文本改动任务收尾跑 `git diff --check`（cwd 为提交仓库根）。
- 验证命令：模块单测 `./gradlew.bat :modules:eink:testDebugUnitTest --tests "<类名>"`；宿主编译 `./gradlew.bat :app:compileAppDebugKotlin`；JDK 21。
- 提交信息格式：`feat(eink): <中文摘要>` / 子模块内 `feat(eink): ...`（子模块历史即 eink 特性历史，无 scope 区分宿主）。

---

### Task 1: PageKeyCodes 键码串编解码纯函数

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/designsystem/pager/PageKeyCodes.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/designsystem/pager/PageKeyCodesTest.kt`

**Interfaces:**
- Produces: `internal fun parseKeyCodes(encoded: String): Set<Int>`、`internal fun formatKeyCodes(keyCodes: Collection<Int>): String`（Task 3 枢纽包装与 Task 7 设置页共用；`internal` 同模块测试可见）

- [ ] **Step 1: 写失败测试**

创建 `PageKeyCodesTest.kt`：

```kotlin
package io.legado.app.eink.designsystem.pager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自定义翻页键码串编解码锚定用例：空串/空格/非法段/非正键码/重复/
 * 顺序保持（格式化不重排，录入顺序即展示顺序）。
 */
class PageKeyCodesTest {

    @Test
    fun `空串解析为空集`() {
        assertEquals(emptySet<Int>(), parseKeyCodes(""))
    }

    @Test
    fun `常规逗号串解析为键码集`() {
        assertEquals(setOf(92, 93), parseKeyCodes("92,93"))
    }

    @Test
    fun `段内空格容错`() {
        assertEquals(setOf(24, 25), parseKeyCodes(" 24 , 25 "))
    }

    @Test
    fun `非法段丢弃不抛异常`() {
        assertEquals(setOf(92), parseKeyCodes("92,abc,,x1"))
    }

    @Test
    fun `非正键码丢弃`() {
        assertEquals(emptySet<Int>(), parseKeyCodes("0,-1"))
    }

    @Test
    fun `重复键码去重`() {
        assertEquals(setOf(92), parseKeyCodes("92,92"))
    }

    @Test
    fun `格式化保持入参顺序`() {
        assertEquals("93,92", formatKeyCodes(listOf(93, 92)))
    }

    @Test
    fun `空集格式化为空串`() {
        assertEquals("", formatKeyCodes(emptySet()))
    }

    @Test
    fun `编解码往返一致`() {
        val encoded = formatKeyCodes(setOf(19, 20))
        assertEquals(setOf(19, 20), parseKeyCodes(encoded))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.designsystem.pager.PageKeyCodesTest"`
Expected: 编译失败 `unresolved reference: parseKeyCodes`

- [ ] **Step 3: 最小实现**

创建 `PageKeyCodes.kt`：

```kotlin
package io.legado.app.eink.designsystem.pager

/**
 * 自定义翻页键码串编解码（纯函数）：存储格式沿用宿主完整模式
 * PreferKey.prevKeyCodes/nextKeyCodes——逗号分隔十进制键码（如
 * "92,93"），默认空串。按键枢纽判定与「翻页设置」页共用同一套解析，
 * 录入、反显、判定三者对同一存储值解释一致。
 *
 * 解析宽松（容错空段/空格/非数字段/重复键码），与宿主完整模式
 * `split(",").contains(keyCode.toString())` 的读取语义兼容：宿主侧
 * 精确匹配不受宽松解析影响，模块侧非法段直接丢弃。
 */
internal fun parseKeyCodes(encoded: String): Set<Int> =
    encoded.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .toSet()

/** 键码集合 → 逗号分隔串（宿主同格式）；保持入参顺序，调用方负责去重。 */
internal fun formatKeyCodes(keyCodes: Collection<Int>): String =
    keyCodes.joinToString(separator = ",")
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.designsystem.pager.PageKeyCodesTest"`
Expected: PASS（9 用例）

- [ ] **Step 5: 提交**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/designsystem/pager/PageKeyCodes.kt modules/eink/src/test/java/io/legado/app/eink/designsystem/pager/PageKeyCodesTest.kt
git commit -m "feat(eink): 自定义翻页键码串编解码纯函数——宿主同格式、宽松解析"
git diff --check HEAD~1 HEAD
```

---

### Task 2: GlobalSettings 契约扩展 + EInkBridge 转发

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/GlobalSettings.kt`（`volumeKeyPage` 成员之后，约 64 行后插入）
- Modify: `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt`（`volumeKeyPage` override 之后，约 376 行后插入）

**Interfaces:**
- Produces: `var mouseWheelPage: Boolean`（默认 false + 写入丢弃）、`val prevKeys: String`（默认 ""）、`val nextKeys: String`（默认 ""）、`fun setPageKeys(prevKeys: String, nextKeys: String)`（默认无操作）——Task 3 枢纽与 Task 7 设置页消费；宿主 `GlobalSettingsImpl` 实现
- Consumes: 宿主域模型 `ReadSettings.mouseWheelPage/prevKeys/nextKeys`（已存在，`ReadSettingsRepository` 同键读写能力现成）

- [ ] **Step 1: 契约新成员（默认实现，旧宿主诚实降级）**

在 `GlobalSettings.kt` 的 `var volumeKeyPage: Boolean` 成员（64 行）之后插入：

```kotlin
    /**
     * 鼠标滚轮翻页（转发宿主阅读设置键 mouseWheelPage，与完整模式共享
     * 同一存储）。
     *
     * 开启后滚轮（AXIS_VSCROLL）在阅读页与书架/目录/搜索/换源/详情/
     * 字体设置等分页列表中翻页（下滚下一页、上滚上一页）；页内模态
     * 弹框打开期间放行系统。生效范围与完整模式不同：完整模式滚轮仅在
     * 阅读页翻页（列表为普通滚动列表），eink 模式列表本身为分页器，
     * 滚轮与音量键全局化同套语义。
     *
     * 可写（「我的 → 翻页设置」开关）：fire-and-forget 写入；实时生效
     * ——滚轮处理器每次事件时读取，无一致性窗口。
     *
     * 默认实现（旧宿主）getter 恒 false、写入丢弃：滚轮不翻页，行为
     * 不回退（诚实降级——不假装生效）。
     */
    var mouseWheelPage: Boolean
        get() = false
        set(value) {}

    /**
     * 自定义上一页键码串（逗号分隔十进制，默认空；转发宿主阅读设置键
     * prevKeys，与完整模式共享同一存储——eink 侧录入的键在完整模式
     * 阅读器同样生效，完整模式侧配置在 eink 模式同样翻页）。
     *
     * 语义对齐完整模式 ReadBookController：自定义键优先于音量键判定，
     * 且**不受** [volumeKeyPage] 开关控制——三项翻页输入（自定义键/
     * 音量键/滚轮）各自独立开关。
     *
     * 读取在按键事件时实时进行（fire-and-forget 档位）；写入经
     * [setPageKeys] 两方向原子提交。
     *
     * 默认实现（旧宿主）恒空串：无自定义键，行为不回退。
     */
    val prevKeys: String get() = ""

    /** 自定义下一页键码串（语义见 [prevKeys]）。默认实现（旧宿主）恒空串。 */
    val nextKeys: String get() = ""

    /**
     * 自定义翻页键对写入（两方向关联字段，一次原子提交）：逗号分隔
     * 键码串，空串 = 清空。fire-and-forget。
     *
     * 默认实现（旧宿主）为无操作：录入不持久化（诚实降级）。
     */
    fun setPageKeys(prevKeys: String, nextKeys: String) {}
```

- [ ] **Step 2: 宿主桥实现**

在 `EInkBridge.kt` 的 `volumeKeyPage` override（约 370-376 行）之后插入：

```kotlin
    override var mouseWheelPage: Boolean
        get() = readSettingsGateway.currentSettings.mouseWheelPage
        set(value) {
            einkSettingsWriteScope.launch {
                readSettingsGateway.update { it.copy(mouseWheelPage = value) }
            }
        }

    /** 自定义翻页键对：两方向一次 copy 原子提交（与音量/滚轮开关独立的第三路翻页输入）。 */
    override val prevKeys: String
        get() = readSettingsGateway.currentSettings.prevKeys

    override val nextKeys: String
        get() = readSettingsGateway.currentSettings.nextKeys

    override fun setPageKeys(prevKeys: String, nextKeys: String) {
        einkSettingsWriteScope.launch {
            readSettingsGateway.update {
                it.copy(prevKeys = prevKeys, nextKeys = nextKeys)
            }
        }
    }
```

- [ ] **Step 3: 编译验证（模块 + 宿主一次覆盖）**

Run: `./gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL（宿主编译含 `:modules:eink`）

- [ ] **Step 4: 提交（两个仓库各一笔）**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/contract/GlobalSettings.kt
git commit -m "feat(eink): GlobalSettings 契约新增滚轮翻页与自定义翻页键——默认实现旧宿主诚实降级"
git diff --check HEAD~1 HEAD
cd ..
git add app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt
git commit -m "feat(eink): EInkBridge 转发滚轮翻页与自定义翻页键——同键共享完整模式存储"
git diff --check HEAD~1 HEAD
```

---

### Task 3: pagingKeyEvent 扩展（音量键纯函数并入自定义键判定）

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/designsystem/pager/PageTurnKeyEvent.kt`
- Delete: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/designsystem/pager/VolumePagingKeyEvent.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/app/EInkKeyEventHub.kt`（包装函数改名并读三设置）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/app/EInkPagerInput.kt`（调用点 + KDoc）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`（import 78 行 + 调用 735 行 + 注释 728-732）
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/designsystem/pager/VolumePagingKeyEventTest.kt` → 改名 `PageTurnKeyEventTest.kt` 并扩展

**Interfaces:**
- Produces: `internal fun pagingKeyEvent(action, keyCode, repeatCount, volumePagingEnabled, prevKeyCodes: Set<Int>, nextKeyCodes: Set<Int>, modalPresent, onPageUp, onPageDown): Boolean`；`internal fun hubPagingKeyEvent(event: KeyEvent, onPageUp, onPageDown): Boolean`（替换 `hubVolumePagingEvent`，Task 5/7 消费）
- Consumes: Task 1 `parseKeyCodes`、Task 2 `GlobalSettings.prevKeys/nextKeys`

- [ ] **Step 1: 测试先行——改名并扩展现有用例**

`git mv` 旧测试文件为 `PageTurnKeyEventTest.kt`，替换全部内容：

```kotlin
package io.legado.app.eink.designsystem.pager

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_MULTIPLE
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 翻页按键事件语义（纯函数）：自定义翻页键 + 音量键共用判定的锚定
 * 用例。返回三元组 = (是否消费, 上翻次数, 下翻次数)；未消费路径次数
 * 非零即为「放行却翻页」的越界副作用。常量取 android.view.KeyEvent
 * （编译期内联值，纯 JVM 可读）。
 */
class PageTurnKeyEventTest {

    private fun event(
        action: Int = ACTION_DOWN,
        keyCode: Int = KEYCODE_VOLUME_DOWN,
        repeatCount: Int = 0,
        volumePagingEnabled: Boolean = true,
        prevKeyCodes: Set<Int> = emptySet(),
        nextKeyCodes: Set<Int> = emptySet(),
        modalPresent: Boolean = false,
    ): Triple<Boolean, Int, Int> {
        var up = 0
        var down = 0
        val consumed = pagingKeyEvent(
            action = action,
            keyCode = keyCode,
            repeatCount = repeatCount,
            volumePagingEnabled = volumePagingEnabled,
            prevKeyCodes = prevKeyCodes,
            nextKeyCodes = nextKeyCodes,
            modalPresent = modalPresent,
            onPageUp = { up += 1 },
            onPageDown = { down += 1 },
        )
        return Triple(consumed, up, down)
    }

    // —— 音量键语义（原 VolumePagingKeyEventTest 全量保留）——

    @Test
    fun `开关关闭放行系统且不翻页`() {
        assertEquals(Triple(false, 0, 0), event(volumePagingEnabled = false))
    }

    @Test
    fun `模态弹框在场放行系统且不翻页`() {
        assertEquals(Triple(false, 0, 0), event(modalPresent = true))
    }

    @Test
    fun `音量上首按翻上一页并消费`() {
        assertEquals(Triple(true, 1, 0), event(keyCode = KEYCODE_VOLUME_UP))
    }

    @Test
    fun `音量下首按翻下一页并消费`() {
        assertEquals(Triple(true, 0, 1), event(keyCode = KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `长按重复不翻但仍消费`() {
        assertEquals(Triple(true, 0, 0), event(keyCode = KEYCODE_VOLUME_UP, repeatCount = 3))
        assertEquals(Triple(true, 0, 0), event(keyCode = KEYCODE_VOLUME_DOWN, repeatCount = 1))
    }

    @Test
    fun `抬起吞掉音量键保证按键对整体被消费`() {
        assertEquals(Triple(true, 0, 0), event(action = ACTION_UP, keyCode = KEYCODE_VOLUME_UP))
        assertEquals(Triple(true, 0, 0), event(action = ACTION_UP, keyCode = KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `非音量键一律放行`() {
        assertEquals(Triple(false, 0, 0), event(keyCode = KEYCODE_A))
        assertEquals(Triple(false, 0, 0), event(action = ACTION_UP, keyCode = KEYCODE_A))
        assertEquals(Triple(false, 0, 0), event(action = ACTION_MULTIPLE))
    }

    // —— 自定义键扩展语义 ——

    @Test
    fun `自定义上一页键命中翻页并消费`() {
        assertEquals(Triple(true, 1, 0), event(keyCode = 92, prevKeyCodes = setOf(92)))
    }

    @Test
    fun `自定义下一页键命中翻页并消费`() {
        assertEquals(Triple(true, 0, 1), event(keyCode = 93, nextKeyCodes = setOf(93)))
    }

    @Test
    fun `自定义键不受音量开关控制`() {
        assertEquals(Triple(true, 1, 0), event(keyCode = 92, volumePagingEnabled = false, prevKeyCodes = setOf(92)))
    }

    @Test
    fun `自定义键优先于音量键`() {
        // 音量+ 被录为自定义下一页键：按自定义方向翻页（对齐宿主完整模式判定优先级）
        assertEquals(Triple(true, 0, 1), event(keyCode = KEYCODE_VOLUME_UP, nextKeyCodes = setOf(KEYCODE_VOLUME_UP)))
    }

    @Test
    fun `自定义键长按重复不翻但仍消费`() {
        assertEquals(Triple(true, 0, 0), event(keyCode = 92, repeatCount = 2, prevKeyCodes = setOf(92)))
    }

    @Test
    fun `自定义键抬起吞掉`() {
        assertEquals(Triple(true, 0, 0), event(action = ACTION_UP, keyCode = 92, prevKeyCodes = setOf(92)))
    }

    @Test
    fun `模态在场自定义键同样放行`() {
        assertEquals(Triple(false, 0, 0), event(keyCode = 92, prevKeyCodes = setOf(92), modalPresent = true))
    }

    @Test
    fun `音量开关关闭时音量键放行但自定义键仍生效`() {
        assertEquals(Triple(false, 0, 0), event(keyCode = KEYCODE_VOLUME_UP, volumePagingEnabled = false))
        assertEquals(Triple(true, 1, 0), event(keyCode = 92, volumePagingEnabled = false, prevKeyCodes = setOf(92)))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.designsystem.pager.PageTurnKeyEventTest"`
Expected: 编译失败 `unresolved reference: pagingKeyEvent`

- [ ] **Step 3: 实现——新文件替换旧文件**

创建 `PageTurnKeyEvent.kt`：

```kotlin
package io.legado.app.eink.designsystem.pager

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP

/**
 * 翻页按键事件语义（纯函数）：阅读页与分页列表的自定义翻页键 + 音量键
 * 判定共用同一套，实参注入开关与模态在场状态——设计系统不持有按键枢纽
 * 与设置端口。
 *
 * 语义（判定优先级对齐宿主完整模式 ReadBookController.onKeyDown）：
 *  - 页内模态弹框在场（[io.legado.app.eink.designsystem.control.EInkModalPresence]）
 *    → 返回 false，按键放行系统；
 *  - 自定义键（[prevKeyCodes]/[nextKeyCodes]）**不受音量开关控制**：
 *    命中即翻页并消费——三项翻页输入（自定义键/音量键/滚轮）各自独立
 *    开关，自定义键优先于音量键判定（音量键被录为自定义键时按自定义
 *    方向生效，与完整模式一致）；
 *  - 音量+ 上一页、音量- 下一页（受 [volumePagingEnabled] 控制），
 *    仅首按（repeatCount == 0）翻页，长按重复不翻（自定义键同款首按
 *    语义）；
 *  - 命中键（自定义或生效中的音量键）抬起吞掉（true），保证按键对
 *    整体被消费；
 *  - 翻页边界不做判定：回调方（阅读页/各分页器）内部 canPage 守卫，
 *    到头不动作、按键仍被消费。
 */
internal fun pagingKeyEvent(
    action: Int,
    keyCode: Int,
    repeatCount: Int,
    volumePagingEnabled: Boolean,
    prevKeyCodes: Set<Int>,
    nextKeyCodes: Set<Int>,
    modalPresent: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean {
    if (modalPresent) return false
    val isPrev = keyCode in prevKeyCodes
    val isNext = keyCode in nextKeyCodes
    if (isPrev || isNext) {
        return when (action) {
            ACTION_DOWN -> {
                if (repeatCount == 0) {
                    if (isPrev) onPageUp() else onPageDown()
                }
                true
            }

            ACTION_UP -> true

            else -> false
        }
    }
    if (!volumePagingEnabled) return false
    return when (action) {
        ACTION_DOWN -> when (keyCode) {
            KEYCODE_VOLUME_UP -> {
                if (repeatCount == 0) onPageUp()
                true
            }

            KEYCODE_VOLUME_DOWN -> {
                if (repeatCount == 0) onPageDown()
                true
            }

            else -> false
        }

        ACTION_UP ->
            keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN

        else -> false
    }
}
```

删除 `VolumePagingKeyEvent.kt`（`git rm`）。

- [ ] **Step 4: hub 包装改名并读三设置**

`EInkKeyEventHub.kt`：import 区把 `volumePagingKeyEvent` 换成 `pagingKeyEvent` 与 `parseKeyCodes`（同包 `io.legado.app.eink.designsystem.pager`），文件尾包装函数整体替换为：

```kotlin
/**
 * 按键事件 → 翻页判定（阅读页与分页列表共用）：读取音量开关与自定义
 * 键集（宿主快照桥接，实时生效）与页内模态在场状态后委托 designsystem
 * 纯函数语义（pagingKeyEvent，见其 KDoc）。自定义键优先于音量键且
 * 不受音量开关控制。
 */
internal fun hubPagingKeyEvent(
    event: KeyEvent,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean = pagingKeyEvent(
    action = event.action,
    keyCode = event.keyCode,
    repeatCount = event.repeatCount,
    volumePagingEnabled = EInkEngineRegistry.globalSettings.volumeKeyPage,
    prevKeyCodes = parseKeyCodes(EInkEngineRegistry.globalSettings.prevKeys),
    nextKeyCodes = parseKeyCodes(EInkEngineRegistry.globalSettings.nextKeys),
    modalPresent = EInkModalPresence.isPresent,
    onPageUp = onPageUp,
    onPageDown = onPageDown,
)
```

类 KDoc 第一段「音量键等系统按键先到达入口 Activity」改为「翻页按键（音量键/自定义键）等系统按键先到达入口 Activity」（措辞最小变更）。

- [ ] **Step 5: 两处调用点改名**

`EInkPagerInput.kt`：`hubVolumePagingEvent(event, onPageUp = currentUp, onPageDown = currentDown)` → `hubPagingKeyEvent(event, onPageUp = currentUp, onPageDown = currentDown)`；KDoc 中「+ 音量键/翻页器翻页（[EInkKeyEventHub] 栈顶注册，事件时实时读 GlobalSettings.volumeKeyPage）」改为「+ 翻页按键（音量键/自定义键，[EInkKeyEventHub] 栈顶注册，事件时实时读设置）」，下一行「音量语义与阅读页共用」改为「按键语义与阅读页共用」。

`ReaderScreen.kt`：78 行 import 改 `io.legado.app.eink.app.hubPagingKeyEvent`；728-732 行注释块「音量键翻页（对齐 View 版 ReadBookController.volumeKeyPage）：语义与分页列表共用 hubVolumePagingEvent」改为「翻页按键（音量键/自定义键，对齐 View 版 ReadBookController 判定优先级）：语义与分页列表共用 hubPagingKeyEvent」；735 行调用改名。

- [ ] **Step 6: 全模块测试确认通过（旧测试文件已删）**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest`
Expected: PASS（含 PageTurnKeyEventTest 16 用例；无 VolumePagingKeyEventTest 残留引用报错）

- [ ] **Step 7: 提交**

```bash
cd eink-lib
git add -A modules/eink/src
git commit -m "feat(eink): 翻页按键判定并入自定义键——pagingKeyEvent 纯函数扩展、枢纽读三设置、八屏与阅读页自动生效"
git diff --check HEAD~1 HEAD
```

---

### Task 4: wheelPagingEvent 纯函数 + EInkWheelHub（200ms 冷却）

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/designsystem/pager/WheelPagingEvent.kt`
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/app/EInkWheelHub.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/designsystem/pager/WheelPagingEventTest.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/app/EInkWheelHubTest.kt`

**Interfaces:**
- Produces: `internal fun wheelPagingEvent(axisValue, pagingEnabled, modalPresent, onPageUp, onPageDown): Boolean`；`class EInkWheelHub(timeSource: () -> Long)`：`register(handler: (Float) -> Boolean): () -> Unit`、`val handlerCount: Int`、`fun dispatch(event: MotionEvent): Boolean`、`internal fun dispatchScroll(action: Int, axisValue: Float): Boolean`、`companion object { const val COOLDOWN_MS = 200L }`；`internal fun hubWheelPagingEvent(axisValue, onPageUp, onPageDown): Boolean`（Task 5/7 消费）
- Consumes: Task 2 `GlobalSettings.mouseWheelPage`

- [ ] **Step 1: 写失败测试（两个测试文件）**

`WheelPagingEventTest.kt`：

```kotlin
package io.legado.app.eink.designsystem.pager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滚轮翻页事件语义（纯函数）锚定用例。返回三元组 = (是否消费, 上翻
 * 次数, 下翻次数)。方向对齐宿主完整模式：下滚（负值）= 下一页。
 */
class WheelPagingEventTest {

    private fun wheel(
        axisValue: Float,
        pagingEnabled: Boolean = true,
        modalPresent: Boolean = false,
    ): Triple<Boolean, Int, Int> {
        var up = 0
        var down = 0
        val consumed = wheelPagingEvent(
            axisValue = axisValue,
            pagingEnabled = pagingEnabled,
            modalPresent = modalPresent,
            onPageUp = { up += 1 },
            onPageDown = { down += 1 },
        )
        return Triple(consumed, up, down)
    }

    @Test
    fun `下滚翻下一页并消费`() {
        assertEquals(Triple(true, 0, 1), wheel(-1f))
    }

    @Test
    fun `上滚翻上一页并消费`() {
        assertEquals(Triple(true, 1, 0), wheel(1f))
    }

    @Test
    fun `零位移不消费`() {
        assertEquals(Triple(false, 0, 0), wheel(0f))
    }

    @Test
    fun `开关关闭放行且不翻页`() {
        assertEquals(Triple(false, 0, 0), wheel(-1f, pagingEnabled = false))
    }

    @Test
    fun `模态在场放行且不翻页`() {
        assertEquals(Triple(false, 0, 0), wheel(-1f, modalPresent = true))
    }
}
```

`EInkWheelHubTest.kt`（测 `dispatchScroll` 可测核，规避单测构造 MotionEvent；MotionEvent 常量为编译期内联值）：

```kotlin
package io.legado.app.eink.app

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滚轮枢纽（栈式分发 + 200ms 冷却）锚定用例：时间源注入假时钟，
 * 冷却只在处理器接受后起算，拒绝（翻页关闭）不消费不起冷却。
 */
class EInkWheelHubTest {

    private var now = 0L

    private fun hub() = EInkWheelHub(timeSource = { now })

    @Test
    fun `非滚动动作与零位移放行`() {
        val hub = hub()
        hub.register { true }
        assertEquals(false, hub.dispatchScroll(MotionEvent.ACTION_HOVER_MOVE, -1f))
        assertEquals(false, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, 0f))
    }

    @Test
    fun `空栈放行`() {
        assertEquals(false, hub().dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
    }

    @Test
    fun `栈顶拒绝时放行且不起冷却`() {
        val hub = hub()
        var handled = 0
        hub.register { handled += 1; false }
        assertEquals(false, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
        now += 10
        assertEquals(
            "处理器拒绝不起冷却，后续事件仍达处理器",
            false,
            hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f),
        )
        assertEquals(2, handled)
    }

    @Test
    fun `接受后冷却期内消费但忽略`() {
        val hub = hub()
        var handled = 0
        hub.register { handled += 1; true }
        assertEquals(true, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
        assertEquals(1, handled)
        now += 100 // 冷却窗口内
        assertEquals("冷却期内消费但忽略", true, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
        assertEquals(1, handled)
        now += 101 // 累计 201ms，出冷却
        assertEquals(true, hub.dispatchScroll(MotionEvent.ACTION_SCROLL, -1f))
        assertEquals(2, handled)
    }

    @Test
    fun `注册入栈注销弹栈`() {
        val hub = hub()
        assertEquals(0, hub.handlerCount)
        val unregister = hub.register { true }
        assertEquals(1, hub.handlerCount)
        unregister()
        assertEquals("注销后栈空（滚轮放行系统）", 0, hub.handlerCount)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.designsystem.pager.WheelPagingEventTest" --tests "io.legado.app.eink.app.EInkWheelHubTest"`
Expected: 编译失败 `unresolved reference: wheelPagingEvent` / `EInkWheelHub`

- [ ] **Step 3: 实现**

`WheelPagingEvent.kt`：

```kotlin
package io.legado.app.eink.designsystem.pager

/**
 * 滚轮翻页事件语义（纯函数）：阅读页与分页列表的鼠标滚轮
 * （AXIS_VSCROLL）翻页共用判定，实参注入开关与模态在场状态——设计
 * 系统不持有滚轮枢纽与设置端口。
 *
 * 语义（方向对齐宿主完整模式 MainActivity.onGenericMotionEvent 的滚轮
 * 分发）：下滚（axisValue < 0）下一页、上滚（axisValue > 0）上一页，
 * 零位移不消费；开关关闭或页内模态弹框在场返回 false 放行系统（页面
 * 正常滚动）。滚轮无按下/抬起对，单事件即完整判定。
 */
internal fun wheelPagingEvent(
    axisValue: Float,
    pagingEnabled: Boolean,
    modalPresent: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean {
    if (!pagingEnabled || modalPresent) return false
    return when {
        axisValue < 0f -> {
            onPageDown()
            true
        }

        axisValue > 0f -> {
            onPageUp()
            true
        }

        else -> false
    }
}
```

`EInkWheelHub.kt`：

```kotlin
package io.legado.app.eink.app

import android.os.SystemClock
import android.view.MotionEvent
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.control.EInkModalPresence
import io.legado.app.eink.designsystem.pager.wheelPagingEvent

/**
 * 滚轮事件转发枢纽（与 [EInkKeyEventHub] 同构的栈式分发）：单 Activity
 * 架构下滚轮（ACTION_SCROLL + AXIS_VSCROLL）先到达入口 Activity，基类
 * [io.legado.app.eink.contract.EInkHostActivity] 的 onGenericMotionEvent
 * 经 [dispatch] 转发；活跃分页区（阅读页、各分页列表的 EInkPagerInput）
 * 在组合期注册处理器（入栈），随组合离开注销（弹栈）。事件只派发给栈顶
 * 处理器，返回 true 表示消费。
 *
 * 去抖为 **[COOLDOWN_MS] 冷却**：首滚即翻页（对齐墨水屏整页刷新的直接
 * 反馈），冷却期内事件消费但忽略——防高分辨率滚轮/触控板连发事件排队
 * 整页刷新洪泛（宿主完整模式为 200ms trailing 去抖，eink 侧取
 * leading + 冷却）。冷却只在处理器接受（返回 true）后起算：翻页关闭
 * 期间的事件不消费、不起冷却。
 */
class EInkWheelHub(
    private val timeSource: () -> Long = SystemClock::elapsedRealtime,
) {

    private val handlers = ArrayDeque<(axisValue: Float) -> Boolean>()

    private var lastAcceptedAt = UNSET

    /** 注册滚轮处理器（入栈）；返回注销函数（弹栈），随组合生命周期恰好调用一次。 */
    fun register(handler: (axisValue: Float) -> Boolean): () -> Unit {
        handlers.addLast(handler)
        return { handlers.remove(handler) }
    }

    /** 在栈处理器数（测试/诊断观测点）。 */
    val handlerCount: Int get() = handlers.size

    /**
     * 转发滚轮事件给栈顶处理器：非竖直滚动或零位移放行（false）；冷却
     * 期内消费但忽略（true）；处理器返回 false（翻页关闭等）原样放行。
     */
    fun dispatch(event: MotionEvent): Boolean =
        dispatchScroll(event.action, event.getAxisValue(MotionEvent.AXIS_VSCROLL))

    /**
     * 滚动判定的可测核（[dispatch] 的纯参量形态，规避单测构造
     * [MotionEvent]）：action 为 MotionEvent.ACTION_* 常量。
     */
    internal fun dispatchScroll(action: Int, axisValue: Float): Boolean {
        if (action != MotionEvent.ACTION_SCROLL || axisValue == 0f) return false
        val handler = handlers.lastOrNull() ?: return false
        val now = timeSource()
        if (lastAcceptedAt != UNSET && now - lastAcceptedAt < COOLDOWN_MS) return true
        if (handler(axisValue)) {
            lastAcceptedAt = now
            return true
        }
        return false
    }

    companion object {
        /** 翻页接受后的冷却窗口（毫秒）。 */
        const val COOLDOWN_MS = 200L

        private const val UNSET = Long.MIN_VALUE
    }
}

/**
 * 滚轮事件 → 翻页判定（阅读页与分页列表共用）：读取开关（宿主快照
 * 桥接，实时生效）与页内模态在场状态后委托 designsystem 纯函数语义
 * （wheelPagingEvent，见其 KDoc）。
 */
internal fun hubWheelPagingEvent(
    axisValue: Float,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
): Boolean = wheelPagingEvent(
    axisValue = axisValue,
    pagingEnabled = EInkEngineRegistry.globalSettings.mouseWheelPage,
    modalPresent = EInkModalPresence.isPresent,
    onPageUp = onPageUp,
    onPageDown = onPageDown,
)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.designsystem.pager.WheelPagingEventTest" --tests "io.legado.app.eink.app.EInkWheelHubTest"`
Expected: PASS（5 + 5 用例）

- [ ] **Step 5: 提交**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/designsystem/pager/WheelPagingEvent.kt modules/eink/src/main/java/io/legado/app/eink/app/EInkWheelHub.kt modules/eink/src/test/java/io/legado/app/eink/designsystem/pager/WheelPagingEventTest.kt modules/eink/src/test/java/io/legado/app/eink/app/EInkWheelHubTest.kt
git commit -m "feat(eink): 滚轮翻页判定与栈式枢纽——200ms 冷却、方向对齐宿主完整模式"
git diff --check HEAD~1 HEAD
```

---

### Task 5: 滚轮接线（注册表 + Activity 分发 + 分页区/阅读页注册）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/EInkHostActivity.kt`（onKeyUp 之后，约 253 行后）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/app/EInkPagerInput.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt`（b 用例扩展）

**Interfaces:**
- Produces: `EInkEngineRegistry.wheelHub: EInkWheelHub`（install 时重置，同 keyEventHub）
- Consumes: Task 4 `EInkWheelHub`/`hubWheelPagingEvent`

- [ ] **Step 1: 注册表扩展**

`EInkEngineRegistry.kt`：
1. import 区加 `import io.legado.app.eink.app.EInkWheelHub`；
2. `_keyEventHub` 声明（62 行）之后加：

```kotlin
    /** 模块自有的滚轮枢纽（非宿主端口）：每次 install 重置，丢弃陈旧 handler。 */
    private var _wheelHub = EInkWheelHub()
```

3. `keyEventHub` getter（144-146 行）之后加：

```kotlin
    /** 模块自有滚轮枢纽（入口基类分发、分页区注册处理器；恒可用）。 */
    val wheelHub: EInkWheelHub
        get() = _wheelHub
```

4. `install()` 尾部 `_keyEventHub = EInkKeyEventHub()` 之后加 `_wheelHub = EInkWheelHub()`；
5. 类 KDoc 第 20 行「keyEventHub 一并重建」改为「keyEventHub/wheelHub 一并重建」，第 37 行「[keyEventHub] 亦随之重置」改为「[keyEventHub]/[wheelHub] 亦随之重置」。

- [ ] **Step 2: Activity 分发**

`EInkHostActivity.kt`：import 区加 `import android.view.MotionEvent`；`onKeyUp`（248-253 行）之后加：

```kotlin
    /**
     * 通用运动事件（滚轮等）优先交给活跃屏幕的处理器
     * （[io.legado.app.eink.app.EInkWheelHub]，如滚轮翻页）；非竖直滚动
     * 或无人消费时交还系统默认行为。
     */
    final override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && EInkEngineRegistry.wheelHub.dispatch(event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }
```

- [ ] **Step 3: EInkPagerInput 注册滚轮（八屏自动获得）**

`EInkPagerInput.kt` 函数体替换为（KDoc 同步补滚轮句）：

```kotlin
    val currentUp by rememberUpdatedState(onPageUp)
    val currentDown by rememberUpdatedState(onPageDown)
    val hub = EInkEngineRegistry.keyEventHub
    val wheelHub = EInkEngineRegistry.wheelHub
    DisposableEffect(hub, wheelHub, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val unregisterKeys = hub.register { event ->
            hubPagingKeyEvent(event, onPageUp = currentUp, onPageDown = currentDown)
        }
        val unregisterWheel = wheelHub.register { axis ->
            hubWheelPagingEvent(axis, onPageUp = currentUp, onPageDown = currentDown)
        }
        onDispose {
            unregisterKeys()
            unregisterWheel()
        }
    }
    return EInkPageSwipe(
        enabled = enabled,
        onPageUp = onPageUp,
        onPageDown = onPageDown,
        onSwipeLeft = onSwipeLeft,
        onSwipeRight = onSwipeRight,
    )
```

KDoc 首段「滑动翻页手势…+ 翻页按键（音量键/自定义键，[EInkKeyEventHub] 栈顶注册，事件时实时读设置）」补一句「+ 滚轮翻页（[EInkWheelHub] 栈顶注册，实时读 GlobalSettings.mouseWheelPage，200ms 冷却去抖）」。

- [ ] **Step 4: 阅读页注册滚轮**

`ReaderScreen.kt`：先 `grep -n "val keyEventHub" modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt` 定位取值处，紧邻加 `val wheelHub = EInkEngineRegistry.wheelHub`；728-742 行既有注册块改为：

```kotlin
    // 翻页按键（音量键/自定义键）与滚轮（对齐 View 版 ReadBookController
    // 判定优先级与滚轮方向）：语义与分页列表共用 hubPagingKeyEvent /
    // hubWheelPagingEvent（首按翻页、长按重复不翻、抬起吞掉；滚轮 200ms
    // 冷却）；页内模态弹框（边距调整/想法等）在场时放行系统。开关关闭
    // 或离开阅读页时处理器注销/放行。
    DisposableEffect(keyEventHub, wheelHub) {
        val unregisterKeys = keyEventHub.register { event ->
            hubPagingKeyEvent(
                event,
                onPageUp = viewModel::prevPage,
                onPageDown = viewModel::nextPage,
            )
        }
        val unregisterWheel = wheelHub.register { axis ->
            hubWheelPagingEvent(
                axis,
                onPageUp = viewModel::prevPage,
                onPageDown = viewModel::nextPage,
            )
        }
        onDispose {
            unregisterKeys()
            unregisterWheel()
        }
    }
```

- [ ] **Step 5: 注册表测试扩展（镜像 keyEventHub 断言）**

`EInkEngineRegistryTest.kt` b 用例 83-95 行改为：

```kotlin
        val hubBefore = EInkEngineRegistry.keyEventHub
        val wheelHubBefore = EInkEngineRegistry.wheelHub
        EInkEngineRegistry.install(
            globalSettings = stub(),
            bookshelfEngine = stub(),
            searchEngine = stub(),
            tocEngine = stub(),
            bookDetailEngine = stub(),
            changeSourceEngine = stub(),
            coverEngine = stub(),
            readerEngine = stub(),
        )
        assertTrue("整体替换：设置端口指向新桩", EInkEngineRegistry.globalSettings !== settings)
        assertTrue("keyEventHub 随 install 重置", EInkEngineRegistry.keyEventHub !== hubBefore)
        assertTrue("wheelHub 随 install 重置", EInkEngineRegistry.wheelHub !== wheelHubBefore)
```

- [ ] **Step 6: 全模块测试 + 宿主编译**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin`
Expected: 全部 PASS / BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
cd eink-lib
git add -A modules/eink/src
git commit -m "feat(eink): 滚轮翻页全局接线——注册表枢纽、入口分发、八屏与阅读页注册"
git diff --check HEAD~1 HEAD
```

---

### Task 6: 翻页设置页纯逻辑（方向枚举 + 录入 + 键名映射）

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/home/PagingSettingsLogic.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/home/PagingSettingsLogicTest.kt`

**Interfaces:**
- Produces: `internal enum class PagingDirection { PREV, NEXT }`；`internal fun recordPageKey(prevKeyCodes, nextKeyCodes, direction, keyCode): Pair<Set<Int>, Set<Int>>?`；`internal fun pageKeyDisplayName(keyCode: Int): String`；`internal fun handleCaptureKey(action, keyCode, repeatCount, direction, prevKeyCodes, nextKeyCodes, onRecorded, onExitCapture): Boolean`（Task 7 消费）

- [ ] **Step 1: 写失败测试**

```kotlin
package io.legado.app.eink.feature.home

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页设置页纯逻辑锚定用例：录入迁移/拒录、BACK 退出录入、录入态
 * 全量消费、键名映射。
 */
class PagingSettingsLogicTest {

    // —— recordPageKey ——

    @Test
    fun `录入上一页键`() {
        assertEquals(
            setOf(92) to emptySet<Int>(),
            recordPageKey(emptySet(), emptySet(), PagingDirection.PREV, 92),
        )
    }

    @Test
    fun `同向重复录入幂等`() {
        assertEquals(
            setOf(92) to emptySet<Int>(),
            recordPageKey(setOf(92), emptySet(), PagingDirection.PREV, 92),
        )
    }

    @Test
    fun `录入时自动从另一方向移除`() {
        // 93 原是上一页键，录为下一页后从上一页键集迁出
        assertEquals(
            emptySet<Int>() to setOf(93),
            recordPageKey(setOf(93), emptySet(), PagingDirection.NEXT, 93),
        )
    }

    @Test
    fun `非正键码拒录`() {
        assertNull(recordPageKey(emptySet(), emptySet(), PagingDirection.PREV, 0))
        assertNull(recordPageKey(emptySet(), emptySet(), PagingDirection.NEXT, -1))
    }

    // —— handleCaptureKey ——

    @Test
    fun `BACK 按下退出录入并消费`() {
        var exited = false
        val consumed = handleCaptureKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BACK,
            repeatCount = 0,
            direction = PagingDirection.PREV,
            prevKeyCodes = emptySet(),
            nextKeyCodes = emptySet(),
            onRecorded = { _, _ -> error("BACK 不应录入") },
            onExitCapture = { exited = true },
        )
        assertTrue(consumed)
        assertTrue(exited)
    }

    @Test
    fun `有效键按下经回调录入并消费`() {
        var recorded: Pair<Set<Int>, Set<Int>>? = null
        val consumed = handleCaptureKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = 92,
            repeatCount = 0,
            direction = PagingDirection.PREV,
            prevKeyCodes = emptySet(),
            nextKeyCodes = setOf(92),
            onRecorded = { prev, next -> recorded = prev to next },
            onExitCapture = { error("有效键不应退出录入") },
        )
        assertTrue(consumed)
        assertEquals(setOf(92) to emptySet<Int>(), recorded)
    }

    @Test
    fun `非正键码按下静默忽略但仍消费`() {
        val consumed = handleCaptureKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = 0,
            repeatCount = 0,
            direction = PagingDirection.PREV,
            prevKeyCodes = emptySet(),
            nextKeyCodes = emptySet(),
            onRecorded = { _, _ -> error("无效键不应录入") },
            onExitCapture = { error("无效键不应退出录入") },
        )
        assertTrue(consumed)
    }

    @Test
    fun `录入态长按重复与抬起全消费`() {
        val exit = { }
        val none: (Set<Int>, Set<Int>) -> Unit = { _, _ -> }
        assertTrue(
            handleCaptureKey(KeyEvent.ACTION_DOWN, 92, 2, PagingDirection.PREV, emptySet(), emptySet(), none, exit),
        )
        assertTrue(
            handleCaptureKey(KeyEvent.ACTION_UP, 92, 0, PagingDirection.PREV, emptySet(), emptySet(), none, exit),
        )
    }

    // —— pageKeyDisplayName ——

    @Test
    fun `常用键名映射带键码`() {
        assertEquals("音量上 24", pageKeyDisplayName(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("上翻页 92", pageKeyDisplayName(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals("方向下 20", pageKeyDisplayName(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `冷门键回落键码`() {
        assertEquals("键码 304", pageKeyDisplayName(304))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.home.PagingSettingsLogicTest"`
Expected: 编译失败 `unresolved reference: recordPageKey`

- [ ] **Step 3: 实现**

```kotlin
package io.legado.app.eink.feature.home

import android.view.KeyEvent

/** 翻页方向（「翻页设置」页的录入方向与预览提示共用）。 */
internal enum class PagingDirection { PREV, NEXT }

/**
 * 录入一个物理键为指定方向的翻页键（纯函数）：
 *  - 非正键码拒录（返回 null）；
 *  - 一键只属一个方向：录入时自动从另一方向键集移除（静默迁移——
 *    键块从另一行消失即可见，不另弹提示）。
 *
 * 返回新的键对；调用方负责序列化（formatKeyCodes）与原子提交
 * （GlobalSettings.setPageKeys）。BACK 键在录入处理器更早分支拦截
 * （退出录入态），不会到达本函数。
 */
internal fun recordPageKey(
    prevKeyCodes: Set<Int>,
    nextKeyCodes: Set<Int>,
    direction: PagingDirection,
    keyCode: Int,
): Pair<Set<Int>, Set<Int>>? {
    if (keyCode <= 0) return null
    return when (direction) {
        PagingDirection.PREV -> (prevKeyCodes + keyCode) to (nextKeyCodes - keyCode)
        PagingDirection.NEXT -> (prevKeyCodes - keyCode) to (nextKeyCodes + keyCode)
    }
}

/**
 * 录入态按键处理（纯函数形态，Screen 内接线）：
 *  - BACK 按下：退出录入态并消费（返回键留给导航，不可录为翻页键）；
 *  - 其它键首按：经 [recordPageKey] 录入（非正键码静默忽略）；
 *  - 长按重复/抬起/其它动作：吞掉——录入期间所有按键对整体消费，
 *    防止穿透到翻页判定。
 */
internal fun handleCaptureKey(
    action: Int,
    keyCode: Int,
    repeatCount: Int,
    direction: PagingDirection,
    prevKeyCodes: Set<Int>,
    nextKeyCodes: Set<Int>,
    onRecorded: (prevKeyCodes: Set<Int>, nextKeyCodes: Set<Int>) -> Unit,
    onExitCapture: () -> Unit,
): Boolean = when (action) {
    KeyEvent.ACTION_DOWN -> when {
        keyCode == KeyEvent.KEYCODE_BACK -> {
            onExitCapture()
            true
        }

        repeatCount == 0 -> {
            recordPageKey(prevKeyCodes, nextKeyCodes, direction, keyCode)
                ?.let { (prev, next) -> onRecorded(prev, next) }
            true
        }

        else -> true
    }

    KeyEvent.ACTION_UP -> true

    else -> true
}

/**
 * 键码显示名：常用键映射中文名，冷门键回落「键码」；键码数字恒保留
 * ——完整模式自定义按键配置同口径展示键码，两侧对照可读。
 */
internal fun pageKeyDisplayName(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> "音量上"
    KeyEvent.KEYCODE_VOLUME_DOWN -> "音量下"
    KeyEvent.KEYCODE_DPAD_UP -> "方向上"
    KeyEvent.KEYCODE_DPAD_DOWN -> "方向下"
    KeyEvent.KEYCODE_DPAD_LEFT -> "方向左"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "方向右"
    KeyEvent.KEYCODE_PAGE_UP -> "上翻页"
    KeyEvent.KEYCODE_PAGE_DOWN -> "下翻页"
    KeyEvent.KEYCODE_SPACE -> "空格"
    KeyEvent.KEYCODE_ENTER -> "回车"
    else -> "键码"
} + " $keyCode"
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.home.PagingSettingsLogicTest"`
Expected: PASS（10 用例）

- [ ] **Step 5: 提交**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/feature/home/PagingSettingsLogic.kt modules/eink/src/test/java/io/legado/app/eink/feature/home/PagingSettingsLogicTest.kt
git commit -m "feat(eink): 翻页设置页纯逻辑——录入迁移与拒录、BACK 退出、键名映射"
git diff --check HEAD~1 HEAD
```

---

### Task 7: 翻页设置页 + 导航接线 + 「我的」入口替换

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/home/PagingSettingsScreen.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/app/EInkScreen.kt`（`WebDavSync` 之后加成员）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/app/EInkApp.kt`（Home 分支回调 + when 新分支）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/home/HomeRoute.kt`（参数 113-114 行区域 + MineScreen 调用 502-512 行区域）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/home/MineScreen.kt`（92 行本地状态删除；169-182 行开关行替换为入口行）

**Interfaces:**
- Produces: `@Composable fun PagingSettingsRoute(onBack: () -> Unit)`；`EInkScreen.PagingSettings`
- Consumes: Task 2 `GlobalSettings`（三成员 + volumeKeyPage）、Task 3 `hubPagingKeyEvent`、Task 4 `EInkWheelHub`/`hubWheelPagingEvent`、Task 6 全部纯逻辑、Task 1 `parseKeyCodes/formatKeyCodes`

- [ ] **Step 1: EInkScreen 新目的地**

`EInkScreen.kt` `WebDavSync` 成员之后加：

```kotlin
    /**
     * 翻页设置（入口：「我的 → 翻页设置」）：三项翻页输入统一设置面
     * ——音量键翻页/鼠标滚轮翻页开关 + 自定义翻页按键捕获式录入；
     * 预览条与生产判定同源（事件时实时读设置），滚轮与自定义键在
     * eink 模式与音量键同套全局语义（阅读页 + 各分页列表）。
     */
    data object PagingSettings : EInkScreen
```

- [ ] **Step 2: 设置页主体**

创建 `PagingSettingsScreen.kt`（完整文件）：

```kotlin
package io.legado.app.eink.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.app.hubPagingKeyEvent
import io.legado.app.eink.app.hubWheelPagingEvent
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.interaction.EInkPressBlock
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.pager.formatKeyCodes
import io.legado.app.eink.designsystem.pager.parseKeyCodes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.delay

/**
 * 翻页设置页（入口「我的 → 翻页设置」，目的地
 * [io.legado.app.eink.app.EInkScreen.PagingSettings]）：
 * 三项翻页输入的统一设置面——音量键翻页（开关）、鼠标滚轮翻页（开关）、
 * 自定义翻页按键（捕获式录入）。三者在 eink 模式全局生效（阅读页 +
 * 各分页列表，与音量键全局化同套语义）；音量/滚轮与完整模式同键共享
 * 存储，自定义键经宿主 prevKeys/nextKeys 同键（完整模式阅读器同样
 * 生效）。
 *
 * 预览条与生产判定**同源**：本页向 keyEventHub/wheelHub 栈顶注册处理
 * 器，直接复用 hubPagingKeyEvent/hubWheelPagingEvent（事件时实时读设
 * 置），命中即页内反色提示 [HINT_DURATION_MS] 并消费按键（不翻页）
 * ——预览即真实，可验证三项设置组合效果；开关关闭时按键回落系统行为
 * （音量调节照常、滚轮放行页面滚动）。
 *
 * 捕获式录入：点方向行进入录入态，按物理键即录为该方向键（一键一方
 * 向，录入自动迁出另一方向；非正键码静默忽略），返回键退出录入且不
 * 可录为翻页键（导航必需）；滚轮只预览不可录入。写入经 setPageKeys
 * 两方向一次原子提交，本地乐观状态保显示（fire-and-forget 同「我的」
 * 开关）。录入态单处理器内分支（录入/预览），避免双处理器栈序竞态。
 *
 * 无 ViewModel（对齐 Mine/FontSettings 先例）：全部同步端口读写 +
 * Compose 本地状态，提示计时 LaunchedEffect。本页无分页列表，不接
 * 翻页手势。
 */
@Composable
fun PagingSettingsRoute(onBack: () -> Unit) {
    val globalSettings = EInkEngineRegistry.globalSettings
    // fire-and-forget 写路径配本地乐观状态（getter 不保证立即可见新值）
    var volumeKeyPage by remember { mutableStateOf(globalSettings.volumeKeyPage) }
    var mouseWheelPage by remember { mutableStateOf(globalSettings.mouseWheelPage) }
    var prevKeys by remember { mutableStateOf(parseKeyCodes(globalSettings.prevKeys)) }
    var nextKeys by remember { mutableStateOf(parseKeyCodes(globalSettings.nextKeys)) }
    // 录入方向（null = 非录入态）；提示方向 + 序号（序号自增驱动计时重启）
    var captureDirection by remember { mutableStateOf<PagingDirection?>(null) }
    var hintDirection by remember { mutableStateOf<PagingDirection?>(null) }
    var hintSequence by remember { mutableStateOf(0) }

    fun flashHint(direction: PagingDirection) {
        hintDirection = direction
        hintSequence++
    }

    fun commitKeys(prev: Set<Int>, next: Set<Int>) {
        prevKeys = prev
        nextKeys = next
        globalSettings.setPageKeys(formatKeyCodes(prev), formatKeyCodes(next))
    }

    // 提示计时：命中反色 1.5s；同方向再命中序号自增刷新计时，异方向
    // 命中立即切换并重计时；无命中无变化
    LaunchedEffect(hintSequence) {
        if (hintDirection != null) {
            delay(HINT_DURATION_MS)
            hintDirection = null
        }
    }

    // 预览 + 录入（单处理器分支）：录入态全量捕键；常态复用生产判定
    // 只提示不翻页（rememberUpdatedState 保证注册 lambda 读到最新态）
    val keyHub = EInkEngineRegistry.keyEventHub
    val currentCapture by rememberUpdatedState(captureDirection)
    val currentPrevKeys by rememberUpdatedState(prevKeys)
    val currentNextKeys by rememberUpdatedState(nextKeys)
    DisposableEffect(keyHub) {
        val unregister = keyHub.register { event ->
            val capturing = currentCapture
            if (capturing != null) {
                handleCaptureKey(
                    action = event.action,
                    keyCode = event.keyCode,
                    repeatCount = event.repeatCount,
                    direction = capturing,
                    prevKeyCodes = currentPrevKeys,
                    nextKeyCodes = currentNextKeys,
                    onRecorded = { prev, next ->
                        commitKeys(prev, next)
                        flashHint(capturing)
                    },
                    onExitCapture = { captureDirection = null },
                )
            } else {
                hubPagingKeyEvent(
                    event,
                    onPageUp = { flashHint(PagingDirection.PREV) },
                    onPageDown = { flashHint(PagingDirection.NEXT) },
                )
            }
        }
        onDispose { unregister() }
    }

    // 滚轮仅预览（不可录入）：与生产同源
    val wheelHub = EInkEngineRegistry.wheelHub
    DisposableEffect(wheelHub) {
        val unregister = wheelHub.register { axis ->
            hubWheelPagingEvent(
                axis,
                onPageUp = { flashHint(PagingDirection.PREV) },
                onPageDown = { flashHint(PagingDirection.NEXT) },
            )
        }
        onDispose { unregister() }
    }

    PagingSettingsScreen(
        volumeKeyPage = volumeKeyPage,
        mouseWheelPage = mouseWheelPage,
        prevKeys = prevKeys,
        nextKeys = nextKeys,
        captureDirection = captureDirection,
        hintDirection = hintDirection,
        onVolumeKeyPageToggle = {
            val next = !volumeKeyPage
            globalSettings.volumeKeyPage = next
            volumeKeyPage = next
        },
        onMouseWheelPageToggle = {
            val next = !mouseWheelPage
            globalSettings.mouseWheelPage = next
            mouseWheelPage = next
        },
        onDirectionRowClick = { direction ->
            captureDirection = if (captureDirection == direction) null else direction
        },
        onKeyRemove = { direction, code ->
            if (direction == PagingDirection.PREV) {
                commitKeys(prevKeys - code, nextKeys)
            } else {
                commitKeys(prevKeys, nextKeys - code)
            }
        },
        onBack = onBack,
    )
}

/** 预览命中提示的持续时长。 */
private const val HINT_DURATION_MS = 1_500L

/**
 * 无状态翻页设置页外壳 — 居中标题 + 两开关行 + 自定义翻页按键分组
 * （两方向行 + 键块）+ 预览条 + 帮助文案 + 底部返回操作栏（备份与
 * 恢复页同款）。内容整体可纵向滚动（常态一屏放得下，滚动仅为小屏
 * 溢出兜底；滚轮开关开启时滚轮被预览消费，页面不依赖滚轮滚动）。
 */
@Composable
private fun PagingSettingsScreen(
    volumeKeyPage: Boolean,
    mouseWheelPage: Boolean,
    prevKeys: Set<Int>,
    nextKeys: Set<Int>,
    captureDirection: PagingDirection?,
    hintDirection: PagingDirection?,
    onVolumeKeyPageToggle: () -> Unit,
    onMouseWheelPageToggle: () -> Unit,
    onDirectionRowClick: (PagingDirection) -> Unit,
    onKeyRemove: (PagingDirection, Int) -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部：居中标题（返回在底部操作栏，备份与恢复页同款）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                EInkText(text = "翻页设置", style = EInkTheme.typography.titleLarge)
            }
            EInkHorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                PagingToggleRow(
                    label = "音量键翻页",
                    description = "关闭时恢复系统音量调节",
                    checked = volumeKeyPage,
                    onToggle = onVolumeKeyPageToggle,
                )
                EInkHorizontalDivider()
                PagingToggleRow(
                    label = "鼠标滚轮翻页",
                    description = "滚轮在阅读页与列表翻页",
                    checked = mouseWheelPage,
                    onToggle = onMouseWheelPageToggle,
                )
                EInkHorizontalDivider()

                EInkText(
                    text = "自定义翻页按键",
                    style = EInkTheme.typography.titleMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        top = EInkSpacing.l,
                        bottom = EInkSpacing.xxs,
                    ),
                )
                PageKeyRow(
                    direction = PagingDirection.PREV,
                    label = "上一页",
                    keyCodes = prevKeys,
                    capturing = captureDirection == PagingDirection.PREV,
                    onRowClick = { onDirectionRowClick(PagingDirection.PREV) },
                    onKeyRemove = { onKeyRemove(PagingDirection.PREV, it) },
                )
                EInkHorizontalDivider()
                PageKeyRow(
                    direction = PagingDirection.NEXT,
                    label = "下一页",
                    keyCodes = nextKeys,
                    capturing = captureDirection == PagingDirection.NEXT,
                    onRowClick = { onDirectionRowClick(PagingDirection.NEXT) },
                    onKeyRemove = { onKeyRemove(PagingDirection.NEXT, it) },
                )
                EInkHorizontalDivider()

                PagePreviewStrip(hintDirection = hintDirection)
                EInkText(
                    text = "点方向行后按物理键录入；返回键用于退出录入，不可录为翻页键。设置对阅读页与各列表全局生效，并与完整模式共享。",
                    style = EInkTheme.typography.bodyMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        bottom = EInkSpacing.m,
                    ),
                )
            }

            // 底部操作栏：仅返回（本页无翻页列表）
            EInkOperationBar(
                tabs = emptyList(),
                selectedTabIndex = 0,
                onTabSelect = {},
                navigationIcon = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_arrow_back),
                        contentDescription = "返回",
                        onClick = onBack,
                    )
                },
            )
        }
    }
}

/**
 * 行为开关行（「我的」页开关行同款结构）：左侧「主信息 + 副信息」纯
 * 展示，右侧开/关块即开关本体（EInkButton，开启实心、Role.Switch）。
 */
@Composable
private fun PagingToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(text = label, style = EInkTheme.typography.titleMedium)
            EInkText(
                text = description,
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = EInkSpacing.xxs),
            )
        }
        Spacer(modifier = Modifier.padding(start = EInkSpacing.s))
        EInkButton(
            text = if (checked) "开" else "关",
            onClick = onToggle,
            modifier = Modifier.width(64.dp),
            selected = checked,
            role = Role.Switch,
        )
    }
}

/**
 * 方向键行：标题 + 录入状态文案，下方键块横排（点键块删除该键）。
 * 整行点击进入/退出录入态（按压瞬时反色 §35）；键块数量少，横向
 * 滚动兜底即可。
 */
@Composable
private fun PageKeyRow(
    direction: PagingDirection,
    label: String,
    keyCodes: Set<Int>,
    capturing: Boolean,
    onRowClick: () -> Unit,
    onKeyRemove: (Int) -> Unit,
) {
    EInkPressBlock(
        onClick = onRowClick,
        modifier = Modifier.fillMaxWidth(),
    ) { colors, _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EInkText(
                    text = label,
                    style = EInkTheme.typography.titleMedium,
                    color = colors.contentColor,
                )
                Spacer(modifier = Modifier.weight(1f))
                EInkText(
                    text = if (capturing) "按物理键录入，再点结束" else "点此录入按键",
                    style = EInkTheme.typography.bodyMedium,
                    color = if (capturing) colors.contentColor else colors.secondaryContentColor,
                )
            }
            if (keyCodes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = EInkSpacing.xs)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    keyCodes.forEach { code ->
                        EInkButton(
                            text = pageKeyDisplayName(code),
                            onClick = { onKeyRemove(code) },
                            height = 32.dp,
                            modifier = Modifier.padding(end = EInkSpacing.s),
                        )
                    }
                }
            } else if (!capturing) {
                EInkText(
                    text = "未设置",
                    style = EInkTheme.typography.bodyMedium,
                    color = colors.secondaryContentColor,
                    modifier = Modifier.padding(top = EInkSpacing.xs),
                )
            }
        }
    }
}

/**
 * 预览条：双格等宽方向块，命中方向反色 [HINT_DURATION_MS]（同向刷新
 * 计时、异向切换重计时，时序在 Route 的序号驱动 LaunchedEffect）；
 * 不命中无变化。按钮 onClick 为空操作（纯展示，命中态即提示本体）。
 */
@Composable
private fun PagePreviewStrip(hintDirection: PagingDirection?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EInkSpacing.m,
                end = EInkSpacing.m,
                top = EInkSpacing.l,
            ),
    ) {
        EInkText(text = "预览", style = EInkTheme.typography.titleMedium)
        EInkText(
            text = "按下翻页键或滚动滚轮，命中方向短暂反色",
            style = EInkTheme.typography.bodyMedium,
            color = EInkTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                top = EInkSpacing.xxs,
                bottom = EInkSpacing.s,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            EInkButton(
                text = "上一页",
                onClick = {},
                selected = hintDirection == PagingDirection.PREV,
                modifier = Modifier.weight(1f),
                height = 44.dp,
            )
            Spacer(modifier = Modifier.width(EInkSpacing.m))
            EInkButton(
                text = "下一页",
                onClick = {},
                selected = hintDirection == PagingDirection.NEXT,
                modifier = Modifier.weight(1f),
                height = 44.dp,
            )
        }
    }
}
```

- [ ] **Step 3: EInkApp 分支 + Home 接线**

`EInkApp.kt`：Home 分支 `onOpenWebDavSync` 之后（213 行后）加：

```kotlin
                                onOpenPagingSettings = {
                                    controller.navigate(EInkScreen.PagingSettings)
                                },
```

`is EInkScreen.WebDavSync ->` 分支（300-304 行）之后加：

```kotlin
                        is EInkScreen.PagingSettings -> {
                            PagingSettingsRoute(
                                onBack = { controller.pop() },
                            )
                        }
```

- [ ] **Step 4: HomeRoute + MineScreen 替换**

`HomeRoute.kt`：参数区 `onOpenWebDavSync: () -> Unit = {},`（114 行）之后加 `onOpenPagingSettings: () -> Unit = {},`；MineScreen 调用 `onOpenWebDavSync = onOpenWebDavSync,`（508 行）之后加 `onOpenPagingSettings = onOpenPagingSettings,`。MineScreen 函数签名补同款参数（`onOpenWebDavSync` 旁）。

`MineScreen.kt`：
1. 删除 92 行 `var volumeKeyPage by remember { mutableStateOf(globalSettings.volumeKeyPage) }`；
2. 169-182 行 `MineToggleRow(音量键翻页…)` 整块（含 168 行前置分隔线之后的内容，保留分隔线本身）替换为：

```kotlin
        item {
            MineEntry(
                label = "翻页设置",
                sublabel = "音量键 / 滚轮 / 自定义按键",
                onClick = onOpenPagingSettings
            )
        }
```

（该 item 前后各有一条 `EInkHorizontalDivider`，结构与其他入口行一致，无需增删分隔线。）

- [ ] **Step 5: 编译 + 全模块测试**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin`
Expected: 全部 PASS / BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd eink-lib
git add -A modules/eink/src
git commit -m "feat(eink): 翻页设置页——三项翻页输入统一设置、捕获式录入、同源预览条；我的页音量键开关改为入口行"
git diff --check HEAD~1 HEAD
```

---

### Task 8: 阅读界面音量键开关移除

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt`（831 行参数、844-845 行行与注释）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`（1042 行传参）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`（89 行 UiState 字段、221 行 init 快照、1146-1154 行 toggle 函数与 KDoc）

**Interfaces:**
- 移除：`ReaderOtherPanel.onToggleVolumeKeyPage` 参数、`ReaderUiState.volumeKeyPage` 字段、`ReaderViewModel.toggleVolumeKeyPage()`——纯移除，无新增接口

- [ ] **Step 1: 四点移除**

1. `ReaderMenus.kt` 831 行删除 `    onToggleVolumeKeyPage: () -> Unit,`；844-845 行删除注释行 `// 音量键翻页纯按键语义（阅读页按键处理器实时读端口值），切换不触发重排` 与 `ToggleRow(label = "音量键翻页", checked = state.volumeKeyPage, onToggle = onToggleVolumeKeyPage)`；
2. `ReaderScreen.kt` 1042 行删除 `                            onToggleVolumeKeyPage = viewModel::toggleVolumeKeyPage,`；
3. `ReaderViewModel.kt` 89 行删除 `    val volumeKeyPage: Boolean = false,`；221 行删除 `                volumeKeyPage = EInkEngineRegistry.globalSettings.volumeKeyPage,`；1146-1154 行删除 toggleVolumeKeyPage 函数及其 KDoc；
4. 验证无残留引用：

```bash
grep -rn "volumeKeyPage" eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/
```

Expected: 仅剩 ReaderScreen 728-732 行注释里的语义描述（若有「音量键」字样属正常——翻页按键注释保留），`toggleVolumeKeyPage`、`state.volumeKeyPage`、`onToggleVolumeKeyPage` 零命中。若注释行提及「音量键翻页开关」等已失效描述，同步清理措辞。

- [ ] **Step 2: 编译 + 全模块测试**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin`
Expected: 全部 PASS / BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
cd eink-lib
git add -A modules/eink/src
git commit -m "feat(eink): 阅读界面其它设置面板移除音量键翻页开关——全局设置收口翻页设置页"
git diff --check HEAD~1 HEAD
```

---

### Task 9: 集成验证、真机手验、宿主指针推进

**Files:**
- Modify: 宿主 `eink-lib` 子模块指针（Task 3-8 的 eink-lib 提交链）

- [ ] **Step 1: 全量构建验证**

```bash
./gradlew.bat :modules:eink:testDebugUnitTest :app:compileAppDebugKotlin assembleAppDebug
```

Expected: 全部 PASS / BUILD SUCCESSFUL（AGENTS 主验证集 testAppDebugUnitTest lintAppDebug verifyConfigArchitecture 留待推送前统一补跑——本次按快速迭代纪律）

- [ ] **Step 2: 真机装机手验（按用户既定无线装机方式）**

清单（对照规格「测试」节）：
1. 我的 → 翻页设置：两开关反显正确（音量键默认开、滚轮默认开）；
2. 捕获录入：点「上一页」行 → 按键（如方向上）→ 键块出现「方向上 19」、预览条闪上一页；同键再录「下一页」→ 从上一页迁出；
3. BACK 录入态退出录入（不退出页面）、非录入态返回上一页；
4. 预览：音量键开→音量键命中提示并消费（无系统音量面板）；关→按音量键无提示、系统音量调节照常；滚轮同理；
5. 八屏生效：书架（列表/网格）/搜索（结果/历史）/目录（章节/书签）/详情/换源/我的/字体设置——自定义键与滚轮翻页、边界到头不动作仍消费；
6. 阅读页：自定义键/滚轮/音量键翻页；「其它设置」面板无音量键开关行、其余行完好；
7. 完整模式回验：切完整模式阅读页，eink 侧录入的自定义键同样翻页（同键共享）。

- [ ] **Step 3: 宿主指针推进 + 终检**

```bash
cd eink-lib && git log --oneline -1   # 记下 eink-lib 末提交 sha
cd ..
git add eink-lib
git commit -m "feat(eink): eink-lib 推进 <sha>——翻页设置页：三项翻页输入统一设置、滚轮与自定义键全局生效、阅读面板开关移除"
git diff --check HEAD~1 HEAD
git log --oneline -3                  # 确认三笔：设计文档 / 桥接 / 指针
```

**不 push**：推送前需补跑 AGENTS 主验证集（testAppDebugUnitTest lintAppDebug verifyConfigArchitecture）并按「先子模块后宿主」顺序推送，另行执行。
