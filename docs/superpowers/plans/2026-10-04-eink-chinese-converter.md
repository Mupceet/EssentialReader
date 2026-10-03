# E-Ink「其它设置」简繁转换 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** eink-lib「其它设置」面板新增简繁转换三档设置（关闭/繁体转简体/简体转繁体），与宿主完整模式同键共享（`chineseConverterType`），入口行 + 单选弹窗呈现，点选即应用并触发当前章重排。

**Architecture:** 零新引擎端口——eink-lib `GlobalSettings` 契约加转发键 `chineseConverterType: Int`（默认实现，旧宿主零破坏）+ 能力声明 `supportsChineseConverter`；宿主 `GlobalSettingsImpl` 经 `ReadSettingsGateway` 转发 DataStore 同键；VM 写端口 + UiState 乐观更新 + `scheduleRelayout()` 走既有重排链（宿主 `ContentProcessor` 逐章转换，新 `contentHash` 使分页缓存自然未命中）。弹窗组合于 `ReaderScreen` 根 Box 层（`EInkDialog` 契约）。

**Tech Stack:** Kotlin 2.x、Jetpack Compose（eink-lib 自有 E-Ink designsystem）、JDK 21、Git 双仓（宿主仓 + `eink-lib` 子模块 branch `eink/lib`）。

**Spec:** `docs/superpowers/specs/2026-10-04-eink-chinese-converter-design.md`

## Global Constraints

- 双仓提交顺序：先在 `eink-lib` 子模块（`eink-lib/` 目录，branch `eink/lib`）提交，最后在宿主仓提交指针推进 + 宿主改动（`git -C eink-lib …` 操作子模块）。
- 零新 Gradle 依赖、零新引擎端口、不动 `EinkLegacyPrefsStore`、不进 designsystem 新组件。
- 值域约定：`0 = 关闭 / 1 = 繁体转简体 / 2 = 简体转繁体`（与宿主 `PreferKey.chineseConverterType`、`arrays.xml` chinese_mode 完全一致）。
- 文案唯一源：`chineseConverterTypeLabel(type: Int): String`，未识别值回落「关闭」。
- 提交信息用仓库既有的中文 conventional 风格（`feat(eink): …——细节`）。
- 每次提交前运行 `git diff --check`（两仓各自）。
- 构建命令从宿主仓根执行（Git Bash 形态 `./gradlew.bat`，PowerShell 用 `.\gradlew.bat`）；JDK 21。
- 主验证集（Task 4）：`./gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache`。
- 所有新 KDoc/注释用中文，风格对齐相邻代码（讲约束与语义，不讲实现流水）。

---

### Task 1: eink-lib 契约成员 + 兼容守护测试 + PORTING 手册

**Files:**
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/contract/GlobalSettingsChineseConverterCompatTest.kt`（新建）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/GlobalSettings.kt`（`supportsReviewBubbles` 之后，约 :138-140 之间插入）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`（§3.2 表 `EInkBridge` 行，约 :185）

**Interfaces:**
- Consumes: 无（首个任务）。
- Produces（后续任务依赖的精确签名）:
  - `GlobalSettings.chineseConverterType: Int`（var，默认实现 get()=0 / set 丢弃）
  - `GlobalSettings.supportsChineseConverter: Boolean`（val，默认实现 get()=false）

- [ ] **Step 1: 确认子模块工作态**

Run: `git -C eink-lib status --short --branch`
Expected: `## eink/lib...origin/eink/lib`，无未提交改动（脏则停下报告，不得覆盖他人改动）。

- [ ] **Step 2: 写失败的契约兼容测试**

创建 `GlobalSettingsChineseConverterCompatTest.kt`：

```kotlin
package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 契约源兼容性守护：简繁转换成员（转发键 + 能力声明）全部带默认实现，
 * 只实现既有成员面的宿主设置无需改动即可编译——AAR 消费方升级零破坏
 * （ReaderSyncContractCompatTest 同款守护）。LegacyGlobalSettings 刻意
 * 不实现 chineseConverterType / supportsChineseConverter，编译通过即
 * 守护成立；行为断言钉死降级语义：恒 0 档（关闭）、能力 false、写入
 * 丢弃不抛。
 */
class GlobalSettingsChineseConverterCompatTest {

    private class LegacyGlobalSettings : GlobalSettings {
        override val threadCount: Int get() = 1
        override var autoRefreshBook: Boolean
            get() = false
            set(value) {}
        override var defaultToRead: Boolean
            get() = false
            set(value) {}
        override var volumeKeyPage: Boolean
            get() = false
            set(value) {}
        override var useDefaultCover: Boolean
            get() = false
            set(value) {}
        override var pullDownBookmark: Boolean
            get() = false
            set(value) {}
        override var hideStatusBar: Boolean
            get() = false
            set(value) {}
        override var showReviewBubbles: Boolean
            get() = true
            set(value) {}
        override val useAntiAlias: Boolean get() = false
        override val preDownloadChapterCount: Int get() = 0
        override val changeSourceCheckAuthor: Boolean get() = true
        override var fontScaleSetting: Int?
            get() = null
            set(value) {}
    }

    @Test
    fun `legacy settings 编译通过且新成员默认关闭降级`() {
        val settings = LegacyGlobalSettings()
        assertEquals("旧宿主恒 0 档（关闭）", 0, settings.chineseConverterType)
        assertFalse("旧宿主能力 false（入口行不渲染）", settings.supportsChineseConverter)
        settings.chineseConverterType = 2
        assertEquals("写入丢弃不抛不落", 0, settings.chineseConverterType)
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.GlobalSettingsChineseConverterCompatTest"`
Expected: FAIL，编译错误 `Unresolved reference: chineseConverterType`（新成员尚未存在于接口）。

- [ ] **Step 4: 实现契约成员**

`GlobalSettings.kt` 在 `supportsReviewBubbles`（:137-138）之后、`readerTapZonesEncoding` KDoc 之前插入：

```kotlin
    /**
     * 简繁转换档位（转发宿主阅读设置键 chineseConverterType，与完整模式
     * 排版设置「简繁转换」下拉共享同一存储；默认 0 关闭）。
     *
     * 值域：0 = 关闭 / 1 = 繁体转简体 / 2 = 简体转繁体。转换在宿主内容
     * 处理层逐章生效（正文与章标题实时转换，不改章节缓存文件），与朗读/
     * 导书/全文搜索同管线。**切换需重排**——调用方写后显式触发重排（设值
     * 后 scheduleRelayout，新 contentHash 使分页缓存自然未命中）；若宿主
     * 写入为纯异步可见（写后读 getter 拿到旧值），排版最迟随下一次翻页
     * 的内容刷新对齐。
     *
     * 可写（阅读界面「其它设置」面板单选弹窗）：fire-and-forget 写入。
     *
     * 默认实现（旧宿主）getter 恒返回 0、写入丢弃：入口行经
     * [supportsChineseConverter] 门控不渲染，行为不回退（正文按原文
     * 呈现），设置不可持久化。
     */
    var chineseConverterType: Int
        get() = 0
        set(value) {}

    /** 简繁转换能力声明：false = 宿主不支持，模块隐藏「简繁转换」入口行（不留死开关）。 */
    val supportsChineseConverter: Boolean get() = false
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.GlobalSettingsChineseConverterCompatTest"`
Expected: PASS（1 test）。

- [ ] **Step 6: 更新 PORTING 手册差异表**

`EINK-PORTING.md` §3.2 表 `EInkBridge` 行（约 :185），将：

```text
| EInkBridge | 全部设置经网关（OtherSettings/ReadSettings/DownloadCache/Cover/ChangeSource Gateway + Koin）；fontScaleSetting 仍走同步快照 |
```

改为：

```text
| EInkBridge | 全部设置经网关（OtherSettings/ReadSettings/DownloadCache/Cover/ChangeSource Gateway + Koin；chineseConverterType 转发 ReadSettingsGateway 与完整模式同键，supportsChineseConverter 声明 true）；fontScaleSetting 仍走同步快照 |
```

- [ ] **Step 7: 全量模块测试**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（既有测试全绿；新接口成员带默认实现，AAR 兼容守护见 Step 2 注释）。

- [ ] **Step 8: 提交（子模块）**

```bash
git -C eink-lib diff --check
git -C eink-lib add modules/eink/src/main/java/io/legado/app/eink/contract/GlobalSettings.kt \
  modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md \
  modules/eink/src/test/java/io/legado/app/eink/contract/GlobalSettingsChineseConverterCompatTest.kt
git -C eink-lib commit -m "feat(eink): GlobalSettings 契约新增简繁转换转发键——chineseConverterType（0/1/2，带默认实现旧宿主零破坏）与 supportsChineseConverter 能力门控；PORTING 手册差异表同步，契约兼容守护测试钉死降级语义"
```

---

### Task 2: eink-lib UiState 字段 + VM 设值函数 + 档位文案映射

**Files:**
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderChineseConverterTest.kt`（新建）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`（UiState 字段 :94 后、init 装载 :187 后、VM 函数 :1021 后）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt`（`pageTurnRippleModeLabel` 之后约 :697）

**Interfaces:**
- Consumes: `GlobalSettings.chineseConverterType: Int`（Task 1）。
- Produces:
  - `ReaderUiState.chineseConverterType: Int = 0`
  - `ReaderViewModel.setChineseConverterType(type: Int): Unit`
  - `chineseConverterTypeLabel(type: Int): String`（internal 顶层函数，包 `io.legado.app.eink.feature.reader`，同包 Composable 与测试可见）

- [ ] **Step 1: 写失败的文案映射测试**

创建 `ReaderChineseConverterTest.kt`：

```kotlin
package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 简繁转换档位文案映射：入口行行尾值与单选弹窗选项共用的唯一文案源
 * （0 关闭 / 1 繁体转简体 / 2 简体转繁体），未识别值回落「关闭」。
 */
class ReaderChineseConverterTest {

    @Test
    fun `档位文案映射`() {
        assertEquals("关闭", chineseConverterTypeLabel(0))
        assertEquals("繁体转简体", chineseConverterTypeLabel(1))
        assertEquals("简体转繁体", chineseConverterTypeLabel(2))
    }

    @Test
    fun `未识别值回落关闭`() {
        assertEquals("关闭", chineseConverterTypeLabel(-1))
        assertEquals("关闭", chineseConverterTypeLabel(3))
        assertEquals("关闭", chineseConverterTypeLabel(Int.MAX_VALUE))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderChineseConverterTest"`
Expected: FAIL，编译错误 `Unresolved reference: chineseConverterTypeLabel`。

- [ ] **Step 3: 实现文案映射**

`ReaderMenus.kt` 在 `pageTurnRippleModeLabel`（:690-697）之后插入：

```kotlin
/** 简繁转换档位界面文案（0/1/2 → 关闭/繁体转简体/简体转繁体；入口行与单选弹窗共用，未识别值回落关闭）。 */
internal fun chineseConverterTypeLabel(type: Int): String = when (type) {
    1 -> "繁体转简体"
    2 -> "简体转繁体"
    else -> "关闭"
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderChineseConverterTest"`
Expected: PASS（2 tests）。

- [ ] **Step 5: UiState 字段 + init 装载**

`ReaderViewModel.kt` UiState 在 `showReviewBubbles` 字段（:93-94）之后插入：

```kotlin
    /** 简繁转换档位（转发完整模式同键阅读设置；0 关闭/1 繁转简/2 简转繁，切换触发重排）。 */
    val chineseConverterType: Int = 0,
```

init 装载块（:179-200）在 `showReviewBubbles = …`（:187）之后插入：

```kotlin
                chineseConverterType = EInkEngineRegistry.globalSettings.chineseConverterType,
```

- [ ] **Step 6: VM 设值函数**

`ReaderViewModel.kt` 在 `toggleShowReviewBubbles`（:1012-1021）之后插入：

```kotlin
    /**
     * 简繁转换档位（转发完整模式同键设置）：写入 + 乐观更新当前档，再
     * 触发重排——宿主内容处理层按新档对正文与章标题逐章转换，新
     * contentHash 使分页缓存自然未命中重排。同值以 UiState 快照为准
     * （乐观源）直写跳过，免无谓重排。
     */
    fun setChineseConverterType(type: Int) {
        if (type == _uiState.value.chineseConverterType) return
        EInkEngineRegistry.globalSettings.chineseConverterType = type
        _uiState.update { it.copy(chineseConverterType = type) }
        scheduleRelayout()
    }
```

- [ ] **Step 7: 全量模块测试**

Run: `./gradlew.bat :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: 提交（子模块）**

```bash
git -C eink-lib diff --check
git -C eink-lib add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt \
  modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt \
  modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderChineseConverterTest.kt
git -C eink-lib commit -m "feat(eink): 阅读 VM 接简繁转换——UiState 装载/设值（同值短路，写后 scheduleRelayout 走既有重排链）+ 档位文案映射（未识别值回落关闭）"
```

---

### Task 3: eink-lib 面板入口行 + 单选弹窗 + Screen 接线

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt`（`OptionRow` :891-918、`ReaderOtherPanel` :653-688、新弹窗组合件）
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`（状态声明 :204 后、面板接线 :926-934、弹窗组合 :1025-1033 后）

**Interfaces:**
- Consumes: `ReaderUiState.chineseConverterType`、`setChineseConverterType`、`chineseConverterTypeLabel`（Task 2）、`GlobalSettings.supportsChineseConverter`（Task 1）。
- Produces:
  - `ReaderOtherPanel` 新参数 `onOpenChineseConverter: () -> Unit`（插在 `onToggleShowReviewBubbles` 与 `onOpenTapZones` 之间）
  - `OptionRow` 新可选参数 `value: String? = null`（private，无外部影响）
  - `ReaderChineseConverterDialog(current: Int, onSelect: (Int) -> Unit, onClose: () -> Unit, onBackdropClick: () -> Unit)`（internal 组合件，ReaderScreen 消费）

- [ ] **Step 1: OptionRow 扩展行尾值**

`ReaderMenus.kt` `OptionRow`（:892）签名与内容改为：

```kotlin
@Composable
private fun OptionRow(label: String, value: String? = null, onClick: () -> Unit) {
```

行内 `Spacer` 与箭头 `Image` 之间插入（箭头 tint 同源次级色）：

```kotlin
        if (value != null) {
            EInkText(
                text = value,
                style = EInkTheme.typography.bodyMedium,
                color = colors.secondaryContentColor,
                modifier = Modifier.padding(end = EInkSpacing.s),
            )
        }
```

（既有 `ReaderCachePanel` 四处调用与「点击区域设置」行不传 value，行为不变。）

- [ ] **Step 2: 面板签名 + 入口行**

`ReaderOtherPanel`（:653-662）签名在 `onToggleShowReviewBubbles` 后插入参数：

```kotlin
    onOpenChineseConverter: () -> Unit,
```

行区在「显示段评气泡」`if` 块（:680-682）之后、「下拉添加书签」之前插入（界面显示组末位；能力门控同段评）：

```kotlin
    // 简繁转换：宿主声明不支持时隐藏入口行（不留死开关）；点行开单选弹窗
    // （组合于 Screen 根层，EInkDialog 组合契约），点选即应用并触发重排
    if (io.legado.app.eink.contract.EInkEngineRegistry.globalSettings.supportsChineseConverter) {
        OptionRow(
            label = "简繁转换",
            value = chineseConverterTypeLabel(state.chineseConverterType),
            onClick = onOpenChineseConverter,
        )
    }
```

- [ ] **Step 3: 单选弹窗组合件**

`ReaderMenus.kt` 在 `CycleValueRow`（:704-726）之后、「点击区域蒙层」分节注释之前插入：

```kotlin
/**
 * 简繁转换单选弹框：满宽选项行（[EInkButton]，当前档实心反白——字体
 * 配置弹窗选项行同款形态），点选即应用并关闭——与完整模式下拉「选中
 * 即应用」语义一致，单选无确认回路。组合契约见 [EInkDialog]：必须组合
 * 在全屏容器（Screen 根 Box）子级。× / 系统返回经 [onClose] 回到其它
 * 面板展开态；点击弹框外空白区域经 [onBackdropClick] 一次性收起到
 * 干净阅读界面。
 */
@Composable
internal fun ReaderChineseConverterDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    EInkDialog(
        onDismiss = onClose,
        title = "简繁转换",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            listOf(0, 1, 2).forEach { type ->
                EInkButton(
                    text = chineseConverterTypeLabel(type),
                    onClick = {
                        onSelect(type)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    selected = type == current,
                )
            }
        }
    }
}
```

- [ ] **Step 4: Screen 状态声明**

`ReaderScreen.kt` 在 `tapZoneEditor` 声明（:204）之后插入：

```kotlin
    // 简繁转换单选弹窗（其它面板入口）：组合于根 Box（EInkDialog 契约，
    // 满宽覆盖不被面板卡片裁剪）；点选即应用并关闭，关闭后回到其它面板
    // 展开态（styleDialog 同款逐级回退）
    var chineseConverterDialog by remember { mutableStateOf(false) }
```

- [ ] **Step 5: 面板接线 + 弹窗组合**

`ReaderScreen.kt` `ReaderOtherPanel(...)` 调用（:926-934）在 `onToggleShowReviewBubbles = …` 行后插入：

```kotlin
                            onOpenChineseConverter = { chineseConverterDialog = true },
```

弹窗组合：`tapZoneEditor` if 块（:1025-1033）之后、「加入书架」提示注释之前插入：

```kotlin
        if (chineseConverterDialog) {
            ReaderChineseConverterDialog(
                current = uiState.chineseConverterType,
                onSelect = viewModel::setChineseConverterType,
                onClose = { chineseConverterDialog = false },
                onBackdropClick = dismissToCleanReading,
            )
        }
```

- [ ] **Step 6: 编译 + 全量模块测试**

Run: `./gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（无新单测——UI 组合与接线，覆盖口径见 spec §8）。

- [ ] **Step 7: 提交（子模块）**

```bash
git -C eink-lib diff --check
git -C eink-lib add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderMenus.kt \
  modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt
git -C eink-lib commit -m "feat(eink): 其它设置面板简繁转换入口行（OptionRow 扩展行尾值）+ 单选弹窗——满宽 EInkButton 反白选项行点选即生效，组合于 Screen 根层（EInkDialog 契约，tapZoneEditor 先例），能力门控复用 supportsChineseConverter"
```

---

### Task 4: 宿主桥覆写 + 子模块指针推进 + 主验证集

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt`（GlobalSettingsImpl KDoc :121-123、覆写 :249-255 后）
- Commit: 宿主仓（含 `eink-lib` 指针推进）

**Interfaces:**
- Consumes: `GlobalSettings.chineseConverterType` / `supportsChineseConverter`（Task 1）；`ReadSettingsGateway.currentSettings.chineseConverterType: Int`（宿主 `domain/model/settings/ReadSettings.kt:117` 既有字段）+ `suspend update`。
- Produces: 运行时能力（宿主声明简繁转换可用，eink 面板入口行渲染、设值经 DataStore 与完整模式同键）。

- [ ] **Step 1: GlobalSettingsImpl KDoc 键清单更新**

`EInkBridge.kt` GlobalSettingsImpl KDoc（:121-123）将：

```text
 * autoRefreshBook/defaultToRead（「我的」页可写）经
 * OtherSettingsGateway、volumeKeyPage、hideStatusBar 与
 * showReviewBubbles（后三者为阅读界面其它设置开关、转发宿主阅读设置
 * 键，与完整模式共享同一存储）经 ReadSettingsGateway、
```

改为：

```text
 * autoRefreshBook/defaultToRead（「我的」页可写）经
 * OtherSettingsGateway、volumeKeyPage、hideStatusBar、showReviewBubbles
 * 与 chineseConverterType（后四者为阅读界面其它设置开关、转发宿主阅读
 * 设置键，与完整模式共享同一存储；chineseConverterType 由宿主内容处理
 * 管线逐章消费）经 ReadSettingsGateway、
```

- [ ] **Step 2: 覆写转发**

`EInkBridge.kt` 在 `showReviewBubbles` 覆写（:249-255）之后插入：

```kotlin
    override var chineseConverterType: Int
        get() = readSettingsGateway.currentSettings.chineseConverterType
        set(value) {
            einkSettingsWriteScope.launch {
                readSettingsGateway.update { it.copy(chineseConverterType = value) }
            }
        }

    override val supportsChineseConverter: Boolean get() = true
```

- [ ] **Step 3: 快速编译确认**

Run: `./gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 主验证集**

Run: `./gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache`
Expected: BUILD SUCCESSFUL 全绿（无新依赖，verifyConfigArchitecture 不受影响——桥接层经 Gateway，无 UI 直连）。

- [ ] **Step 5: 提交（宿主仓，含指针推进）**

```bash
git diff --check
git add app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt eink-lib
git commit -m "feat(eink): 推进 eink-lib 指针至简繁转换——宿主桥 GlobalSettingsImpl 转发 chineseConverterType（ReadSettingsGateway 与完整模式同键共享，pending-overlay 写后重排即时可见）+ 能力声明 true。eink 侧「其它设置」面板入口行+单选弹窗三档，点选即应用并走既有重排链（ContentProcessor 逐章转换，新 contentHash 分页缓存自然失效）。契约带默认实现旧宿主零破坏，兼容守护测试钉死降级语义"
```

- [ ] **Step 6: 交付核对**

确认两仓 `git log --oneline -1`：宿主仓（feat 指针推进）与 `git -C eink-lib log --oneline -3`（Task 1-3 三笔）都在。真机复核项见 spec §7/§8（转换效果、重排后位置保持、与完整模式互改一致性、e-ink 弹窗刷新表现）——**不在本计划内**，交付说明中列为未验证风险。

---

## Self-Review 记录

- **Spec 覆盖**：spec §4（呈现）→ Task 3；§5（契约+宿主）→ Task 1/4；§6（状态与生效链）→ Task 2/3；§7（已知边界）→ 无需任务（行为语义已由链路保证）；§8（测试）→ Task 1/2/4。无缺口。
- **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码；命令含预期结果。
- **类型一致性**：`chineseConverterType: Int`（契约/UiState/Dialog current/onSelect 一致）；`chineseConverterTypeLabel(type: Int): String` 定义与调用一致；`onOpenChineseConverter: () -> Unit` 面板参数与 Screen 接线一致；`ReaderChineseConverterDialog(current, onSelect, onClose, onBackdropClick)` 定义与调用一致。
- **既有调用不破坏**：`OptionRow` 新参数带默认值（`ReaderCachePanel` 四处 + 点击区域行不变）；`GlobalSettings` 新成员带默认实现（旧宿主零破坏，Task 1 测试守护）。
