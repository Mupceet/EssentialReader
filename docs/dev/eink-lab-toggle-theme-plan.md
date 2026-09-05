# 实验室墨水屏开关默认切换电子书主题：实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实验室「墨水屏显示」开关打开时，先把宿主主题无条件切为「电子书」（`"4"`），再翻开关进入 E-Ink 界面；退回完整模式后主题保持「电子书」。

**Architecture:** `LabConfigViewModel` 在 `SetEInkDisplay(true)` 分支组合写 `ThemeSettingsGateway` 与 `LabSettingsGateway`（同一协程、主题先行的非原子双写）；顺带修主题选择器对当前选中项的可见性、更新开关文案。规格见 `docs/dev/eink-lab-toggle-theme-design.md`。

**Tech Stack:** Kotlin、Jetpack Compose、Robolectric 单测、Koin（`viewModelOf` 自动解析新构造参数，无需改 `appModule.kt`）。

## Global Constraints

- 代码 namespace `io.legado.app`；JDK 21；Git Bash 下验证命令用 `./gradlew.bat <task>` 形式。
- 主题值 `"4"` 沿用仓库裸值惯用法（同 `AppConfig.isEInkMode`、`ThemeConfigScreen` 过滤），不新造常量。
- ViewModel 只经 Gateway 写设置，不直连 DataStore/DAO；主题先写、开关后写，顺序不可颠倒（中断最坏态须为「主题已切、开关未开」）。
- Android string 资源里的英文双引号必须转义为 `\"`，否则资源编译失败。
- **不自动 git commit**：全部任务完成后等用户显式说「提交」。

## File Structure

- Modify: `app/src/main/java/io/legado/app/ui/config/labConfig/LabConfigViewModel.kt` — 注入 `ThemeSettingsGateway`，intent 分支补主题写入。
- Create: `app/src/test/java/io/legado/app/ui/config/labConfig/LabConfigViewModelTest.kt` — 仿 `OtherConfigViewModelTest` 的 Robolectric + fake gateway 模式。
- Modify: `app/src/main/java/io/legado/app/ui/config/themeConfig/ThemeConfigScreen.kt` — 两处主题过滤补「当前选中即 "4" 也可见」。
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`、`app/src/main/res/values/strings.xml` — 各改 `lab_eink_display_summary`、`lab_eink_display_hint` 两条文案。

---

### Task 1: LabConfigViewModel 打开开关时写入电子书主题（TDD）

**Files:**
- Create: `app/src/test/java/io/legado/app/ui/config/labConfig/LabConfigViewModelTest.kt`
- Modify: `app/src/main/java/io/legado/app/ui/config/labConfig/LabConfigViewModel.kt`

**Interfaces:**
- Consumes: `LabSettingsGateway`（`io.legado.app.domain.gateway`，已有）、`ThemeSettingsGateway`（同包，已有，Koin `single` 绑定于 `appModule.kt:362`）、`LabConfigIntent.SetEInkDisplay(value: Boolean)`、`LabSettings(enabled, eInkDisplay, eyeProtection)`、`ThemeSettings(appTheme: String = "0", ...)`（可无参构造）。
- Produces: 无下游依赖。

- [x] **Step 1: 写失败测试**

创建 `app/src/test/java/io/legado/app/ui/config/labConfig/LabConfigViewModelTest.kt`：

```kotlin
package io.legado.app.ui.config.labConfig

import android.app.Application
import android.os.Looper
import io.legado.app.domain.gateway.LabSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.settings.LabSettings
import io.legado.app.domain.model.settings.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LabConfigViewModelTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    @Test
    fun enableEInkDisplay_appliesElinkThemeAndFlipsFlag() = runBlocking {
        val labGateway = FakeLabSettingsGateway()
        val themeGateway = FakeThemeSettingsGateway()
        val viewModel = createViewModel(labGateway, themeGateway)

        viewModel.onIntent(LabConfigIntent.SetEInkDisplay(true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, viewModel.uiState.value.settings.eInkDisplay)
        assertEquals("4", themeGateway.currentSettings.appTheme)
        assertEquals(1, themeGateway.updateCount)
    }

    @Test
    fun disableEInkDisplay_flipsFlagWithoutTouchingTheme() = runBlocking {
        val labGateway = FakeLabSettingsGateway(
            LabSettings(enabled = true, eInkDisplay = true)
        )
        val themeGateway = FakeThemeSettingsGateway()
        val viewModel = createViewModel(labGateway, themeGateway)

        viewModel.onIntent(LabConfigIntent.SetEInkDisplay(false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(false, viewModel.uiState.value.settings.eInkDisplay)
        assertEquals(0, themeGateway.updateCount)
    }

    @Test
    fun setEnabled_doesNotTouchTheme() = runBlocking {
        val themeGateway = FakeThemeSettingsGateway()
        val viewModel = createViewModel(themeGateway = themeGateway)

        viewModel.onIntent(LabConfigIntent.SetEnabled(true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, viewModel.uiState.value.settings.enabled)
        assertEquals(0, themeGateway.updateCount)
    }

    private fun createViewModel(
        labGateway: LabSettingsGateway = FakeLabSettingsGateway(),
        themeGateway: ThemeSettingsGateway = FakeThemeSettingsGateway(),
    ) = LabConfigViewModel(
        settingsGateway = labGateway,
        themeSettingsGateway = themeGateway,
    )

    private class FakeLabSettingsGateway(
        initial: LabSettings = LabSettings(),
    ) : LabSettingsGateway {
        private val state = MutableStateFlow(initial)

        override val currentSettings: LabSettings
            get() = state.value
        override val settings: Flow<LabSettings> = state

        override suspend fun update(transform: (LabSettings) -> LabSettings) {
            state.value = transform(state.value)
        }
    }

    private class FakeThemeSettingsGateway : ThemeSettingsGateway {
        private val state = MutableStateFlow(ThemeSettings())
        var updateCount = 0
            private set

        override val currentSettings: ThemeSettings
            get() = state.value
        override val settings: Flow<ThemeSettings> = state

        override suspend fun update(transform: (ThemeSettings) -> ThemeSettings) {
            state.value = transform(state.value)
            updateCount++
        }
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat :app:compileAppDebugUnitTestKotlin`
Expected: 编译失败，报 `LabConfigViewModel` 构造函数没有 `themeSettingsGateway` 参数（no value passed for parameter / unresolved reference 类错误）。这是预期的红灯。

- [x] **Step 3: 最小实现**

`LabConfigViewModel.kt` 全量替换为：

```kotlin
package io.legado.app.ui.config.labConfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.domain.gateway.LabSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.ui.book.read.pageestimate.LocalPageEstimateMetrics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LabConfigViewModel(
    private val settingsGateway: LabSettingsGateway,
    private val themeSettingsGateway: ThemeSettingsGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        LabConfigUiState(
            settings = settingsGateway.currentSettings,
            pageEstimateDiagnosticCount = LocalPageEstimateMetrics.size(),
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<LabConfigEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsGateway.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun onIntent(intent: LabConfigIntent) {
        if (intent is LabConfigIntent.ExportPageEstimateDiagnostics) {
            _effects.tryEmit(
                LabConfigEffect.SharePageEstimateDiagnostics(LocalPageEstimateMetrics.export())
            )
            return
        }
        viewModelScope.launch {
            // 主题先落盘再翻开关：中断最坏态是「主题已切、开关未开」（无害），顺序不可颠倒
            if (intent is LabConfigIntent.SetEInkDisplay && intent.value) {
                themeSettingsGateway.update { it.copy(appTheme = "4") }
            }
            settingsGateway.update { settings ->
                when (intent) {
                    is LabConfigIntent.SetEnabled -> settings.copy(enabled = intent.value)
                    is LabConfigIntent.SetEInkDisplay -> settings.copy(eInkDisplay = intent.value)
                    LabConfigIntent.ExportPageEstimateDiagnostics -> settings
                }
            }
        }
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `./gradlew.bat :app:testAppDebugUnitTest --tests "*LabConfigViewModelTest*"`
Expected: PASS，3 个测试全绿。

---

### Task 2: 主题选择器对当前选中「电子书」保持可见

**Files:**
- Modify: `app/src/main/java/io/legado/app/ui/config/themeConfig/ThemeConfigScreen.kt:209-211` 与 `:234-236`

**Interfaces:**
- Consumes: `state.showEInkTheme: Boolean`、`theme.appTheme: String`（均已在作用域内，两处过滤点下方分别有 `selectedValue = theme.appTheme` / `selectedTheme = theme.appTheme` 可证）。
- Produces: 无下游依赖。

- [x] **Step 1: 改 Miuix 下拉处过滤（约 :209-211）**

```kotlin
// 旧
val visibleThemes = themes.filter { (_, value) ->
    value != "4" || state.showEInkTheme
}
// 新
val visibleThemes = themes.filter { (_, value) ->
    value != "4" || state.showEInkTheme || theme.appTheme == "4"
}
```

- [x] **Step 2: 改主题色选择器处过滤（约 :234-236）**

与 Step 1 相同的旧代码片段出现第二处，做相同替换（两处旧文本相同，用唯一上下文或 replace_all 区分）。

- [x] **Step 3: 编译验证**

Run: `./gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL。

---

### Task 3: 开关文案更新与全量验证

**Files:**
- Modify: `app/src/main/res/values-zh-rCN/strings.xml:2347-2348`
- Modify: `app/src/main/res/values/strings.xml:2371-2372`

**Interfaces:**
- Consumes: 字符串键 `lab_eink_display_summary`、`lab_eink_display_hint`（`LabConfigScreen.kt:110、115` 引用，键名不变）。
- Produces: 无下游依赖。

- [x] **Step 1: 改中文文案（values-zh-rCN/strings.xml）**

```xml
<!-- 旧 -->
<string name="lab_eink_display_summary">在平板界面显示墨水屏选项</string>
<string name="lab_eink_display_hint">可在 主题 → 显示 中调整墨水屏设置</string>
<!-- 新 -->
<string name="lab_eink_display_summary">开启后整体切换到墨水屏界面，并默认切换为「电子书」主题</string>
<string name="lab_eink_display_hint">已切换为「电子书」主题，退出墨水屏后完整模式将继续使用该主题</string>
```

- [x] **Step 2: 改英文文案（values/strings.xml，注意引号转义）**

```xml
<!-- 旧 -->
<string name="lab_eink_display_summary">Show E-Ink display option in the tablet interface</string>
<string name="lab_eink_display_hint">You can adjust E-Ink display settings in Theme → Display options</string>
<!-- 新 -->
<string name="lab_eink_display_summary">Switch to the E-Ink interface and apply the \"E-Book\" theme when enabled</string>
<string name="lab_eink_display_hint">\"E-Book\" theme applied; it stays active after returning to full mode</string>
```

- [x] **Step 3: 资源编译验证（覆盖引号转义）**

Run: `./gradlew.bat :app:assembleAppDebug`
Expected: BUILD SUCCESSFUL。

- [x] **Step 4: 回归单测 + 文本检查**

Run: `./gradlew.bat :app:testAppDebugUnitTest --tests "*LabConfigViewModelTest*"` 然后 `git diff --check`
Expected: 测试 PASS；`git diff --check` 无输出（无尾随空白/冲突标记）。

---

## 完成定义

- 三个任务全绿；未做任何 git 提交（等用户显式「提交」）。
- 交付说明列出：改动文件、验证命令与结果、未验证项（真机进出的主题表现、选择器可见性需真机/预览手动确认）。
