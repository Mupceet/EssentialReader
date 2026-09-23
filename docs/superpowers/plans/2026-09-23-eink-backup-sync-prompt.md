# eink 启动「发现云端新备份」提示 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 墨水屏模式启动时检测 WebDAV 云端较新备份并弹确认层，确认后恢复（补齐书源/阅读记录下行的第一个入口），行为对齐宿主完整模式 `MainActivity.backupSync`。

**Architecture:** 新可选端口 `BackupSyncEngine`（判定/标记/恢复管线全在宿主侧，模块零宿主类型）+ 模块侧 Activity 级 VM 状态机（`EInkBackupSyncViewModel`）+ `EInkApp` 根层 `EInkDialog`；宿主 bridge 侧判定核心抽纯函数 `BackupSyncCheckPolicy`（JVM 可单测）。设计依据：`docs/superpowers/specs/2026-09-23-eink-backup-sync-prompt-design.md`。

**Tech Stack:** Kotlin、Jetpack Compose、`mutableStateOf` 状态机、Koin、JUnit4、kotlinx-coroutines-test（runBlocking + setMain(Unconfined) 形态，对齐 `ChangeSourceViewModelTest`）。

## Global Constraints

- **嵌套仓库提交纪律**：`eink-lib/` 是独立 git 仓库（分支 `eink/lib`）。Task 1-3 的改动在 `eink-lib/` 内提交；Task 4-5 在外层仓库提交；Task 6 最后在外层仓库推进 eink-lib 的 gitlink 指针（先例格式：`feat(eink): 推进 eink-lib 指针至…（<hash>）——<一句话>`，见 6490cd052）。
- **行为基线（宿主 `MainActivity.kt:654-676`）**：`autoCheckNewBackup` 开关门控（默认开）；`latest.lastModify - LocalConfig.lastBackup > 60000ms` 才算新；**发现即标记**（提示前把 `LocalConfig.lastBackup` 置为云端 mtime，用户取消后同一备份不再弹）；检查失败/未配置静默（catch→无操作）；无 DEBUG gate。
- **AAR 源兼容**：契约纯新增类型 + `install` 追加可选参数（默认 null），不改既有成员；不 bump 模块版本号。
- **模块对话框文案硬编码中文**（模块现状，先例：进度同步确认框）。
- 数据边界：宿主实现不直连 `AppWebDav`，走 `WebDavBackupUseCase` / `BackupSettingsGateway` / `LocalConfig`（与 `BackupConfigViewModel`/`MainViewModel` 同链）。
- 每任务独立提交；每次提交前跑 `git diff --check`。
- 验证命令（仓库根执行）：模块 `.\gradlew.bat :modules:eink:testDebugUnitTest`；宿主编译 `.\gradlew.bat :app:compileAppDebugKotlin`；宿主单测 `.\gradlew.bat :app:testAppDebugUnitTest --tests "<FQCN>"`；主验证集见 Task 6。

## 行为基线（宿主对拍来源，`MainActivity.kt:654-676`）

```kotlin
private fun backupSync() {
    if (!backupSettingsGateway.currentSettings.autoCheckNewBackup) return
    lifecycleScope.launch {
        val lastBackupFile = try {
            withContext(IO) { viewModel.getLatestWebDavBackup() }
        } catch (_: Exception) { return@launch } ?: return@launch
        if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
            LocalConfig.lastBackup = lastBackupFile.lastModify
            alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                cancelButton()
                okButton { viewModel.restoreWebDav(lastBackupFile.name) }
            }
        }
    }
}
```

eink 侧差异（设计 §1 已拍板）：确认后恢复有忙态+结果反馈（对齐宿主**手动**恢复路径 `BackupConfigViewModel` 而非启动路径 fire-and-forget）；失败弹层保留可重试；快照展示设备名+日期。

---

### Task 1: （eink-lib）契约 BackupSyncEngine + 注册表扩展 + 契约测试与文档

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/BackupSyncEngine.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/README.md`
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt`

**Interfaces:**
- Consumes: 既有 `EInkEngineRegistry` 装配形态、测试 `stub<T>()` 动态代理桩。
- Produces（后续任务依赖）:
  - `interface BackupSyncEngine { suspend fun checkNewBackupOnStart(): CloudBackupNewer?; suspend fun restoreBackup(fileName: String) }`
  - `data class CloudBackupNewer(val fileName: String, val deviceName: String, val dateText: String)`
  - `EInkEngineRegistry.backupSyncEngine: BackupSyncEngine?`（未注册 null）
  - `EInkEngineRegistry.install(..., backupSyncEngine: BackupSyncEngine? = null)`

- [ ] **Step 1: 写失败测试（先扩注册表测试）**

`EInkEngineRegistryTest.kt` 文件末尾（`e_install 传入 bookshelfGroupEngine 后可取回` 用例之后、`installDefaults` 之前）追加：

```kotlin
    /** 云端备份端口桩（代理生成，只做存取断言，方法不实际调用）。 */
    private val fakeBackupSyncEngine: BackupSyncEngine = stub()

    @Test
    fun `f_backupSyncEngine 未注册时为 null 且不参与必填校验`() {
        installDefaults() // 不传 backupSyncEngine：install 正常完成即证明非必填
        assertNull(EInkEngineRegistry.backupSyncEngine)
    }

    @Test
    fun `f_install 传入 backupSyncEngine 后可取回`() {
        EInkEngineRegistry.install(
            globalSettings = stub(),
            bookshelfEngine = stub(),
            searchEngine = stub(),
            tocEngine = stub(),
            bookDetailEngine = stub(),
            changeSourceEngine = stub(),
            coverEngine = stub(),
            readerEngine = stub(),
            backupSyncEngine = fakeBackupSyncEngine,
        )
        assertSame(fakeBackupSyncEngine, EInkEngineRegistry.backupSyncEngine)
    }
```

同时把类头 KDoc 第 5 行「（更新/书签笔记/分组）未注册为 null」改为「（更新/书签笔记/分组/云端备份）未注册为 null」。

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.EInkEngineRegistryTest"`
Expected: 编译失败，`unresolved reference: BackupSyncEngine`。

- [ ] **Step 3: 新建契约文件**

`BackupSyncEngine.kt` 全文：

```kotlin
package io.legado.app.eink.contract

/**
 * 云端备份端口——**可选**端口之一（其余为 [AppUpdateEngine] /
 * [MarksEngine] / [BookshelfGroupEngine]；可选端口与能力裁剪的完整
 * 语义见本目录 README §5）。
 *
 * ## 职责边界
 *
 * 备份的主体是**宿主应用**：备份通道（WebDAV 配置）、「自动检测新备份」
 * 设置、备份新旧比较、发现即标记、恢复管线（下载+解压+写库，经
 * BackupRestoreLock 串行）全部是宿主独占知识。模块侧只做两件事：
 *
 * ```text
 * E-Ink 启动（EInkApp 根层）
 *    └─ checkNewBackupOnStart()
 *         ├─ null            ─► 静默（无新备份/未配置/失败，不打扰）
 *         └─ CloudBackupNewer ─► E-Ink 确认弹层（根层渲染，任意屏幕之上）
 *              └─「恢复」─► restoreBackup(fileName)
 *                   ├─ 成功   ─► 弹层收起 + toast「恢复完成」
 *                   └─ 抛异常 ─► 弹层保留变「重试」，message 面向用户
 * ```
 *
 * ## 宿主实现义务（本仓参照完整模式 MainActivity.backupSync）
 *
 * - `checkNewBackupOnStart` 判定矩阵：设置关 / 云端无备份 / 拉取失败
 *   → null（静默）；云端最新备份 mtime 与本地 lastBackup 标记差
 *   > 1 分钟 → 命中。**发现即写标记**（返回快照前把 lastBackup 置为
 *   云端 mtime），与宿主一致：用户取消后同一备份不再提示，失败重试
 *   由弹层「重试」承担。
 * - `restoreBackup` 走宿主恢复管线，失败抛异常且 message 面向用户。
 * - 恢复只写 DB：不动模块导航与当前阅读会话（书架流为响应式，
 *   恢复后自动重发）。
 */
interface BackupSyncEngine {

    /**
     * 启动时检查云端是否有比本地新的备份（判定与标记全在实现侧）。
     *
     * @return 有新备份时返回快照（调用方据此弹确认层）；无新备份/
     *   未配置 WebDAV/网络失败一律返回 null（静默，不提示）。
     */
    suspend fun checkNewBackupOnStart(): CloudBackupNewer?

    /**
     * 恢复指定备份（[CloudBackupNewer.fileName] 原样回传）。
     *
     * @throws Exception 恢复失败（网络/解压/写库），message 面向最终
     *   用户，模块直接展示。
     */
    suspend fun restoreBackup(fileName: String)
}

/**
 * 云端较新备份快照：宿主从备份文件名（`backup<yyyy-MM-dd>-<设备名>.zip`）
 * 解析而来。全基元不可变，模块仅展示，恢复经 [BackupSyncEngine.restoreBackup]
 * 回传 [fileName]。
 */
data class CloudBackupNewer(
    /** 备份文件名（restore 关联键）。 */
    val fileName: String,
    /** 备份来源设备名；未命名设备为空串。 */
    val deviceName: String,
    /** 备份日期文本（yyyy-MM-dd；解析失败回退原文件名）。 */
    val dateText: String,
)
```

- [ ] **Step 4: 扩展注册表**

`EInkEngineRegistry.kt` 四处修改：

1) 字段区（`_bookshelfGroupEngine` 声明后）：

```kotlin
    private var _backupSyncEngine: BackupSyncEngine? = null
```

2) 可选端口 getter 区（`bookshelfGroupEngine` val 之后、`keyEventHub` 之前）：

```kotlin
    /**
     * 云端备份端口——**可选**端口：未注册 = 宿主无云端备份能力
     * （companion 宿主的合法状态），启动「发现新备份」检查静默跳过，
     * 不参与 install 必填校验。
     */
    val backupSyncEngine: BackupSyncEngine?
        get() = _backupSyncEngine
```

3) `install` 参数（`bookshelfGroupEngine` 参数后）与赋值（`_bookshelfGroupEngine = bookshelfGroupEngine` 后）：

```kotlin
        backupSyncEngine: BackupSyncEngine? = null,
```

```kotlin
        _backupSyncEngine = backupSyncEngine
```

并在 `install` KDoc 的 `@param bookshelfGroupEngine` 之后补：

```kotlin
     * @param backupSyncEngine 云端备份端口实现（可选，默认 null：
     *   宿主无备份能力时不传，启动「发现云端新备份」检查静默跳过）。
```

4) 文件头 KDoc 装配图行（`install(8 个必填端口实现 + 可选 appUpdateEngine / marksEngine / bookshelfGroupEngine)`）改为在末尾追加 ` / backupSyncEngine`。

- [ ] **Step 5: 跑测试确认通过**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.EInkEngineRegistryTest"`
Expected: PASS（既有 + 新增 2 用例）。

- [ ] **Step 6: 契约文档**

`README.md` §3 端口契约总表：`BookshelfGroupEngine` 行后追加一行（表格列对齐随既有行宽即可）：

```markdown
| `BackupSyncEngine` | 云端备份启动检查与恢复（可选） | 判定/标记/恢复管线全在宿主侧（backupSync 同语义）；未注册 = 启动检查静默跳过，无 UI 入口 |
```

并把表后一句「上表后三行为可选端口」改为「上表后四行为可选端口」。

`EINK-PORTING.md` §1：搜索「可选端口 3 个」改「可选端口 4 个」；「`*EngineImpl（11 个）`」改「`*EngineImpl（12 个）`」，其后括号「（3 个可选端口随宿主能力取舍）」改「（4 个可选端口随宿主能力取舍）」。若 §0/§2 存在逐个列出可选端口的清单（appUpdateEngine/marksEngine/bookshelfGroupEngine 并列处），同步补 `backupSyncEngine`。

- [ ] **Step 7: Commit（eink-lib 仓库内）**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/contract/BackupSyncEngine.kt \
        modules/eink/src/main/java/io/legado/app/eink/contract/EInkEngineRegistry.kt \
        modules/eink/src/main/java/io/legado/app/eink/contract/README.md \
        modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md \
        modules/eink/src/test/java/io/legado/app/eink/contract/EInkEngineRegistryTest.kt
git diff --check --cached
git commit -m "feat(eink): 契约新增云端备份可选端口 BackupSyncEngine（注册表/文档/装配测试）"
```

---

### Task 2: （eink-lib）EInkBackupSyncViewModel 状态机 + 测试

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/app/EInkBackupSyncViewModel.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/app/EInkBackupSyncViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `BackupSyncEngine` / `CloudBackupNewer`。
- Produces:
  - `internal class EInkBackupSyncViewModel : ViewModel`，公开 `prompt: BackupSyncPromptState?`、`oneShotNotice: String?`、`checkOnStart(engine)`、`confirmRestore(engine)`、`dismiss()`、`clearNotice()`。
  - `internal sealed interface BackupSyncPromptState { Newer(info); Restoring(info); Failed(info, reason) }`（与 VM 同包 `io.legado.app.eink.app`，Task 3 直接引用免 import）。

- [ ] **Step 1: 写失败测试**

`EInkBackupSyncViewModelTest.kt` 全文（形态对齐 `ChangeSourceViewModelTest`：runBlocking + setMain(Unconfined)；VM 经方法参数拿端口，无需反射替换注册表）：

```kotlin
package io.legado.app.eink.app

import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 云端备份启动检查 VM 状态机回归：检查静默口径、确认恢复忙态、
 * 失败可重试、恢复中收起的抑制位（终态不回显弹层只走 toast）。
 *
 * VM 只经方法参数消费端口（不触注册表），fake 引擎用
 * CompletableDeferred 手动放行即可驱动全状态转移；viewModelScope 的
 * Main 依赖由 setMain(Unconfined) 满足（ChangeSourceViewModelTest 同款）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EInkBackupSyncViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `检查命中弹层无新备份静默`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        val viewModel = EInkBackupSyncViewModel()

        viewModel.checkOnStart(engine)
        assertNull("检查挂起中无弹层", viewModel.prompt)
        engine.checkResult.complete(null)
        withTimeout(5_000) { while (viewModel.checking) kotlinx.coroutines.delay(10) }
        assertNull("无新备份静默", viewModel.prompt)

        viewModel.checkOnStart(engine)
        engine.checkResult.complete(info)
        withTimeout(5_000) { kotlinx.coroutines.test.runCurrent() ; while (!viewModel.checking) kotlinx.coroutines.delay(10) }
        assertEquals(BackupSyncPromptState.Newer(info), viewModel.prompt)
    }

    @Test
    fun `确认恢复成功收层并提示完成`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        engine.checkResult.complete(info)
        val viewModel = EInkBackupSyncViewModel()
        viewModel.checkOnStart(engine)
        withTimeout(5_000) { kotlinx.coroutines.test.runCurrent() }

        viewModel.confirmRestore(engine)
        assertEquals(BackupSyncPromptState.Restoring(info), viewModel.prompt)
        engine.restoreResult.complete(Result.success(Unit))
        withTimeout(5_000) { while (viewModel.oneShotNotice == null) kotlinx.coroutines.delay(10) }
        assertNull("成功后弹层收起", viewModel.prompt)
        assertEquals("恢复完成", viewModel.oneShotNotice)
        assertEquals(info.fileName, engine.restoreCalledWith)
    }

    @Test
    fun `恢复失败保留弹层可重试`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        engine.checkResult.complete(info)
        val viewModel = EInkBackupSyncViewModel()
        viewModel.checkOnStart(engine)
        withTimeout(5_000) { kotlinx.coroutines.test.runCurrent() }

        viewModel.confirmRestore(engine)
        engine.restoreResult.complete(Result.failure(IllegalStateException("网络中断")))
        withTimeout(5_000) { while (viewModel.prompt !is BackupSyncPromptState.Failed) kotlinx.coroutines.delay(10) }
        val failed = viewModel.prompt as BackupSyncPromptState.Failed
        assertEquals("网络中断", failed.reason)

        // 重试成功
        engine.restoreResult = CompletableDeferred()
        viewModel.confirmRestore(engine)
        engine.restoreResult.complete(Result.success(Unit))
        withTimeout(5_000) { while (viewModel.oneShotNotice == null) kotlinx.coroutines.delay(10) }
        assertNull(viewModel.prompt)
        assertEquals("恢复完成", viewModel.oneShotNotice)
    }

    @Test
    fun `恢复中收起后失败不回显弹层只提示`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        engine.checkResult.complete(info)
        val viewModel = EInkBackupSyncViewModel()
        viewModel.checkOnStart(engine)
        withTimeout(5_000) { kotlinx.coroutines.test.runCurrent() }

        viewModel.confirmRestore(engine)
        viewModel.dismiss() // 恢复中收起：协程不取消
        assertNull(viewModel.prompt)
        engine.restoreResult.complete(Result.failure(IllegalStateException("超时")))
        withTimeout(5_000) { while (viewModel.oneShotNotice == null) kotlinx.coroutines.delay(10) }
        assertNull("已收起不回显弹层", viewModel.prompt)
        assertEquals("恢复失败：超时", viewModel.oneShotNotice)
    }

    private val info = CloudBackupNewer(
        fileName = "backup2026-09-23-ReaderA.zip",
        deviceName = "ReaderA",
        dateText = "2026-09-23",
    )

    private class FakeBackupSyncEngine : BackupSyncEngine {
        val checkResult = CompletableDeferred<CloudBackupNewer?>()
        var restoreResult = CompletableDeferred<Result<Unit>>()
        var restoreCalledWith: String? = null

        override suspend fun checkNewBackupOnStart(): CloudBackupNewer? = checkResult.await()

        override suspend fun restoreBackup(fileName: String) {
            restoreCalledWith = fileName
            restoreResult.await().getOrThrow()
        }
    }
}
```

注意：首个用例为驱动两段检查，VM 需暴露 `checking` 供测试轮询——见 Step 3 的 `var checking`（`internal set`）。若 `kotlinx.coroutines.test.runCurrent()` 在 runBlocking 内不可用（它是 TestScope 扩展），把两处 `kotlinx.coroutines.test.runCurrent()` 删除、直接用后面的 while 轮询等待即可（Unconfined Main 下 deferred 完成即内联恢复，轮询是兜底）。以编译器为准修正后提交。

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.app.EInkBackupSyncViewModelTest"`
Expected: 编译失败，`unresolved reference: EInkBackupSyncViewModel`。

- [ ] **Step 3: 实现 VM**

`EInkBackupSyncViewModel.kt` 全文：

```kotlin
package io.legado.app.eink.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 云端备份启动检查状态的 Activity 级持有者（与 [EInkAppUpdateViewModel]
 * 同作用域，在每条目 ViewModelStoreOwner 覆盖之外经 viewModel() 求值）：
 * 启动检查（对齐宿主完整模式 MainActivity.backupSync 链）的确认弹层
 * 须渲染在 EInkApp 根层、覆盖任意屏幕（含直达最近阅读场景的阅读页），
 * 且检查期间 Activity recreate 不丢结果。
 *
 * 静默口径：检查无新备份/失败不提示（宿主 catch→return 同位）。
 * 恢复有结果反馈（对齐宿主手动恢复路径 BackupConfigViewModel）：
 * 成功 toast「恢复完成」，失败弹层保留变「重试」；恢复中收起弹层
 * 不取消恢复，终态经抑制位只走 toast 不回显。
 */
internal class EInkBackupSyncViewModel : ViewModel() {

    /** 确认弹层状态机（null = 无弹层）。 */
    var prompt by mutableStateOf<BackupSyncPromptState?>(null)
        internal set

    /** 一次性结果提示（toast 文案；展示后调 [clearNotice] 复位）。 */
    var oneShotNotice by mutableStateOf<String?>(null)
        internal set

    /** 检查进行中（防 Activity recreate 重跑效应双发检查）。 */
    var checking by mutableStateOf(false)
        internal set

    /** 恢复中收起弹层后置位：终态不再回显弹层（只走 toast）。 */
    private var suppressed = false

    /**
     * 启动检查（EInkApp 根层 LaunchedEffect 触发）：判定与标记全在
     * 端口实现侧，此处只承接结果——命中弹层，其余静默。
     */
    fun checkOnStart(engine: BackupSyncEngine) {
        if (checking || prompt != null) return
        checking = true
        viewModelScope.launch {
            val newer = runCatching { engine.checkNewBackupOnStart() }.getOrNull()
            checking = false
            prompt = newer?.let { BackupSyncPromptState.Newer(it) }
        }
    }

    /**
     * 「恢复」/「重试」：置忙态后转发端口恢复；成功收层 + 提示，
     * 失败保留弹层变可重试（恢复中被收起则只提示，不回显）。
     */
    fun confirmRestore(engine: BackupSyncEngine) {
        val info = when (val state = prompt) {
            is BackupSyncPromptState.Newer -> state.info
            is BackupSyncPromptState.Failed -> state.info
            is BackupSyncPromptState.Restoring -> return
            null -> return
        }
        prompt = BackupSyncPromptState.Restoring(info)
        viewModelScope.launch {
            try {
                engine.restoreBackup(info.fileName)
                prompt = null
                suppressed = false
                oneShotNotice = "恢复完成"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (suppressed) {
                    prompt = null
                    suppressed = false
                    oneShotNotice = "恢复失败：${e.message}"
                } else {
                    prompt = BackupSyncPromptState.Failed(info, e.message ?: "未知错误")
                }
            }
        }
    }

    /**
     * 收起弹层（取消/恢复中收起）。恢复中收起不取消恢复协程
     * （更新弹层「收起走取消、宿主下载不中断」同款）。
     */
    fun dismiss() {
        if (prompt is BackupSyncPromptState.Restoring) suppressed = true
        prompt = null
    }

    /** toast 已展示，复位一次性提示。 */
    fun clearNotice() {
        oneShotNotice = null
    }
}

/** 备份确认弹层状态机：发现新备份 / 恢复中（忙态）/ 恢复失败（可重试）。 */
internal sealed interface BackupSyncPromptState {
    data class Newer(val info: CloudBackupNewer) : BackupSyncPromptState
    data class Restoring(val info: CloudBackupNewer) : BackupSyncPromptState
    data class Failed(val info: CloudBackupNewer, val reason: String) : BackupSyncPromptState
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.app.EInkBackupSyncViewModelTest"`
Expected: PASS（4 用例）。

- [ ] **Step 5: Commit（eink-lib 仓库内）**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/app/EInkBackupSyncViewModel.kt \
        modules/eink/src/test/java/io/legado/app/eink/app/EInkBackupSyncViewModelTest.kt
git diff --check --cached
git commit -m "feat(eink): 云端备份启动检查 VM 状态机（静默检查/忙态恢复/失败重试/抑制位）"
```

---

### Task 3: （eink-lib）EInkApp 根层接线：启动检查 + 确认弹层 + 结果 toast

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/app/EInkApp.kt`

**Interfaces:**
- Consumes: Task 1 的 `EInkEngineRegistry.backupSyncEngine`；Task 2 的 `EInkBackupSyncViewModel` / `BackupSyncPromptState`（同包免 import）；既有 `EInkDialog` / `EInkText` / `EInkTheme` / `viewModel()` / `LaunchedEffect` / `LocalContext`。
- Produces: 根层弹层与 toast 的最终 UI 行为（无新公开成员）。

- [ ] **Step 1: 启动检查与 toast 接线**

`EInkApp.kt`：import 区补 `import android.widget.Toast`。在更新检查 `LaunchedEffect(Unit)` 块（`EInkEngineRegistry.appUpdateEngine?.let(updateViewModel::autoCheckOnStart)`）之后追加：

```kotlin
    // 云端备份启动检查状态的 Activity 级 VM（与 updateViewModel 同作用域）：
    // 宿主完整模式 MainActivity.backupSync 的 eink 同链——判定/标记全在
    // 宿主端口侧，未注册端口（companion 宿主）静默跳过
    val backupSyncViewModel: EInkBackupSyncViewModel = viewModel()

    LaunchedEffect(Unit) {
        EInkEngineRegistry.backupSyncEngine?.let(backupSyncViewModel::checkOnStart)
    }

    // 恢复结果一次性 toast：VM 只持状态，展示归组合层
    val toastContext = LocalContext.current
    LaunchedEffect(backupSyncViewModel.oneShotNotice) {
        val notice = backupSyncViewModel.oneShotNotice ?: return@LaunchedEffect
        Toast.makeText(toastContext, notice, Toast.LENGTH_SHORT).show()
        backupSyncViewModel.clearNotice()
    }
```

- [ ] **Step 2: 根层确认弹层**

在更新弹层 `if (availableUpdate != null && appUpdateEngine != null) { ... }` 块之后追加（同层平级）：

```kotlin
    // 云端新备份确认弹层：根层渲染、覆盖任意屏幕（宿主 backupSync 的
    // Activity 级 alert 同形态）；恢复只写 DB，书架流响应式自动刷新，
    // 不动导航与当前阅读会话
    val backupSyncEngine = EInkEngineRegistry.backupSyncEngine
    val backupPrompt = backupSyncViewModel.prompt
    if (backupPrompt != null && backupSyncEngine != null) {
        val busy = backupPrompt is BackupSyncPromptState.Restoring
        val failed = backupPrompt as? BackupSyncPromptState.Failed
        EInkDialog(
            onDismiss = { backupSyncViewModel.dismiss() },
            title = "发现云端新备份",
            confirmText = when {
                busy -> "恢复中…"
                failed != null -> "重试"
                else -> "恢复"
            },
            // 恢复中无确认动作：禁用态承担「忙」提示，收起走「取消」
            onConfirm = if (busy) null else {
                { backupSyncViewModel.confirmRestore(backupSyncEngine) }
            },
        ) {
            val info = when (backupPrompt) {
                is BackupSyncPromptState.Newer -> backupPrompt.info
                is BackupSyncPromptState.Restoring -> backupPrompt.info
                is BackupSyncPromptState.Failed -> backupPrompt.info
            }
            EInkText(
                text = buildString {
                    append("云端备份比本地新，是否恢复？\n")
                    append("设备：${info.deviceName.ifBlank { "未命名" }}  日期：${info.dateText}")
                    if (failed != null) append("\n恢复失败：${failed.reason}")
                },
                style = EInkTheme.typography.bodyMedium,
            )
        }
    }
```

- [ ] **Step 3: 编译 + 模块全量测试**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部 PASS（含 Task 1/2 新增用例）。

- [ ] **Step 4: Commit（eink-lib 仓库内）**

```bash
cd eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/app/EInkApp.kt
git diff --check --cached
git commit -m "feat(eink): 根层接入云端新备份启动检查与恢复确认弹层（含结果 toast）"
```

---

### Task 4: （外层仓库）判定纯函数 BackupSyncCheckPolicy + 测试

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/BackupSyncCheckPolicy.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/BackupSyncCheckPolicyTest.kt`

**Interfaces:**
- Consumes: `io.legado.app.domain.model.WebDavBackup`（`name: String, lastModify: Long`）；Task 1 的 `CloudBackupNewer`。
- Produces:
  - `BackupSyncCheckPolicy.shouldPrompt(autoCheckEnabled: Boolean, latest: WebDavBackup?, lastBackupMark: Long): WebDavBackup?`
  - `BackupSyncCheckPolicy.parseSnapshot(backup: WebDavBackup): CloudBackupNewer`

- [ ] **Step 1: 写失败测试**

`BackupSyncCheckPolicyTest.kt` 全文：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.model.WebDavBackup
import io.legado.app.eink.contract.CloudBackupNewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 云端备份启动检查判定核心：复刻宿主 MainActivity.backupSync 语义
 * （autoCheckNewBackup 门控 + mtime 与 lastBackup 差 > 1 分钟）与
 * 备份文件名解析（backup<yyyy-MM-dd>-<设备名>.zip）。
 */
class BackupSyncCheckPolicyTest {

    private val latest = WebDavBackup(name = "backup2026-09-23-ReaderA.zip", lastModify = 1_000_000_000L)

    @Test
    fun `开关关不提示`() {
        assertNull(BackupSyncCheckPolicy.shouldPrompt(false, latest, lastBackupMark = 0L))
    }

    @Test
    fun `无云端备份不提示`() {
        assertNull(BackupSyncCheckPolicy.shouldPrompt(true, null, lastBackupMark = 0L))
    }

    @Test
    fun `时间差不足一分钟不提示`() {
        assertNull(BackupSyncCheckPolicy.shouldPrompt(true, latest, lastBackupMark = latest.lastModify - 60_000L))
        assertNull(BackupSyncCheckPolicy.shouldPrompt(true, latest, lastBackupMark = latest.lastModify))
        assertNull("本地标记晚于云端也不提示", BackupSyncCheckPolicy.shouldPrompt(true, latest, lastBackupMark = latest.lastModify + 5_000L))
    }

    @Test
    fun `时间差超一分钟命中并原样返回`() {
        val hit = BackupSyncCheckPolicy.shouldPrompt(true, latest, lastBackupMark = latest.lastModify - 60_001L)
        assertSame(latest, hit)
    }

    @Test
    fun `文件名解析出设备名与日期`() {
        assertEquals(
            CloudBackupNewer("backup2026-09-23-ReaderA.zip", "ReaderA", "2026-09-23"),
            BackupSyncCheckPolicy.parseSnapshot(latest),
        )
    }

    @Test
    fun `无设备段时设备名为空串`() {
        val backup = WebDavBackup(name = "backup2026-09-23.zip", lastModify = 0L)
        assertEquals(
            CloudBackupNewer("backup2026-09-23.zip", "", "2026-09-23"),
            BackupSyncCheckPolicy.parseSnapshot(backup),
        )
    }

    @Test
    fun `非备份文件名回退原文件名`() {
        val backup = WebDavBackup(name = "manualExport.zip", lastModify = 0L)
        assertEquals(
            CloudBackupNewer("manualExport.zip", "", "manualExport.zip"),
            BackupSyncCheckPolicy.parseSnapshot(backup),
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BackupSyncCheckPolicyTest"`
Expected: 编译失败，`unresolved reference: BackupSyncCheckPolicy`。

- [ ] **Step 3: 实现**

`BackupSyncCheckPolicy.kt` 全文：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.model.WebDavBackup
import io.legado.app.eink.contract.CloudBackupNewer

/**
 * 云端备份启动检查判定核心（纯函数）。复刻宿主 MainActivity.backupSync
 * 语义：「自动检测新备份」设置门控；云端最新备份 mtime 与本地 lastBackup
 * 标记差 > 1 分钟才算「新」（宿主 android.text.format.DateUtils.
 * MINUTE_IN_MILLIS = 60000ms 同值，框架类型不进纯函数故以字面常量表达）。
 * 「发现即标记」的写入由调用方（BackupSyncEngineImpl）承接。
 */
internal object BackupSyncCheckPolicy {

    /** 新备份判定阈值（ms），宿主 backupSync 同值。 */
    private const val NEWER_THRESHOLD_MS = 60_000L

    /** 宿主备份文件名格式：backup<yyyy-MM-dd>-<设备名>.zip（设备段可缺省）。 */
    private val backupFileNameRegex = Regex("""^backup(\d{4}-\d{2}-\d{2})(?:-(.*))?\.zip$""")

    /**
     * 是否应提示恢复。
     *
     * @param autoCheckEnabled 宿主「自动检测新备份」设置（默认开）。
     * @param latest 云端最新备份；null（无备份/拉取失败）恒不提示。
     * @param lastBackupMark 本地 lastBackup 标记（上次备份/恢复/发现时间戳）。
     * @return 命中时原样返回 [latest]，否则 null。
     */
    fun shouldPrompt(
        autoCheckEnabled: Boolean,
        latest: WebDavBackup?,
        lastBackupMark: Long,
    ): WebDavBackup? {
        if (!autoCheckEnabled) return null
        latest ?: return null
        return if (latest.lastModify - lastBackupMark > NEWER_THRESHOLD_MS) latest else null
    }

    /**
     * 备份文件名 → 弹层快照：日期段不合法或整体不匹配时 dateText 回退
     * 原文件名，设备段缺失为空串（弹层展示「未命名」）。
     */
    fun parseSnapshot(backup: WebDavBackup): CloudBackupNewer {
        val match = backupFileNameRegex.matchEntire(backup.name)
        return CloudBackupNewer(
            fileName = backup.name,
            deviceName = match?.groupValues?.getOrNull(2).orEmpty(),
            dateText = match?.groupValues?.getOrNull(1).takeUnless { it.isNullOrBlank() }
                ?: backup.name,
        )
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.BackupSyncCheckPolicyTest"`
Expected: PASS（7 用例）。

- [ ] **Step 5: Commit（外层仓库）**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/BackupSyncCheckPolicy.kt \
        app/src/test/java/io/legado/app/eink/bridge/BackupSyncCheckPolicyTest.kt
git diff --check --cached
git commit -m "feat(eink): 桥接新增云端备份启动检查判定纯函数（宿主 backupSync 同语义）"
```

---

### Task 5: （外层仓库）BackupSyncEngineImpl + EInkBridge 注册

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/BackupSyncEngineImpl.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt:93`（install 参数清单）

**Interfaces:**
- Consumes: Task 1 的 `BackupSyncEngine` / `CloudBackupNewer`；Task 4 的 `BackupSyncCheckPolicy`；`WebDavBackupUseCase`（`getLatestBackup(): WebDavBackup?` / `restore(name)`）；`BackupSettingsGateway.currentSettings.autoCheckNewBackup`；`LocalConfig.lastBackup`。
- Produces: `object BackupSyncEngineImpl : BackupSyncEngine`（经 `EInkBridge.install` 注册）。

- [ ] **Step 1: 实现端口**

`BackupSyncEngineImpl.kt` 全文：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.usecase.WebDavBackupUseCase
import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import io.legado.app.help.config.LocalConfig

/**
 * 云端备份端口实现：转发宿主完整模式同一条链（MainActivity.backupSync
 * 判定 + WebDavBackupUseCase 恢复），E-Ink 侧不新增备份语义。开关
 * （autoCheckNewBackup）、比较与标记（LocalConfig.lastBackup，与完整
 * 模式共享同一闸，同进程先发现者标记、后者静默）、恢复管线
 * （BackupRestoreLock 串行）全部沿用宿主。
 */
object BackupSyncEngineImpl : BackupSyncEngine {

    private val webDavBackupUseCase: WebDavBackupUseCase by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    private val backupSettingsGateway: BackupSettingsGateway by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    override suspend fun checkNewBackupOnStart(): CloudBackupNewer? {
        // 拉取失败（未配置 WebDAV/网络不可用）静默，对齐宿主 catch→return
        val latest = runCatching { webDavBackupUseCase.getLatestBackup() }
            .getOrNull() ?: return null
        val hit = BackupSyncCheckPolicy.shouldPrompt(
            autoCheckEnabled = backupSettingsGateway.currentSettings.autoCheckNewBackup,
            latest = latest,
            lastBackupMark = LocalConfig.lastBackup,
        ) ?: return null
        // 发现即标记（宿主同位）：用户取消后同一备份不再提示，失败重试由弹层承担
        LocalConfig.lastBackup = latest.lastModify
        return BackupSyncCheckPolicy.parseSnapshot(hit)
    }

    override suspend fun restoreBackup(fileName: String) {
        webDavBackupUseCase.restore(fileName)
    }
}
```

- [ ] **Step 2: 注册进 EInkBridge**

`EInkBridge.kt` 的 `install()` 调用处（`bookshelfGroupEngine = BookshelfGroupEngineImpl,` 之后）补一行：

```kotlin
            backupSyncEngine = BackupSyncEngineImpl,
```

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit（外层仓库）**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/BackupSyncEngineImpl.kt \
        app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt
git diff --check --cached
git commit -m "feat(eink): 桥接实现云端备份端口并注册（转发宿主 backupSync 判定与恢复链）"
```

---

### Task 6: （外层仓库）主验证集 + 推进 eink-lib 指针

**Files:** 无新文件（验证与指针提交）。

- [ ] **Step 1: 主验证集**

```powershell
.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache
```

Expected: 全部通过（含新增 3 个测试文件）。

- [ ] **Step 2: 文本改动检查（两仓库）**

```bash
git -C eink-lib diff --check; git diff --check
```

Expected: 无输出。

- [ ] **Step 3: 推进 eink-lib 指针**

```bash
git add eink-lib
git commit -m "feat(eink): 推进 eink-lib 指针至云端备份提示轮（$(git -C eink-lib rev-parse --short HEAD)）——eink 启动检测云端较新备份，确认后恢复并反馈结果"
```

（提交信息中 hash 由命令内联填充；先例格式见 6490cd052。）

- [ ] **Step 4: 真机验证清单（交付后用户执行）**

1. **提示触发**：设备 A 完整模式触发备份上传（或手动备份）→ 设备 B 进入墨水屏模式 → 应弹「发现云端新备份」（含设备名/日期）。
2. **恢复反馈**：确认后弹层变「恢复中…」，完成后 toast「恢复完成」且书架自动刷新出 A 的书源/书籍。
3. **失败重试**：断网时确认 → 弹层保留「恢复失败：原因」，联网点「重试」可成功。
4. **取消不再弹**：取消后再次进入墨水屏模式 → 同一备份不再提示（发现即标记）。
5. **开关联动**：完整模式关闭「自动检测新备份」后，墨水屏启动不再检查。
6. **直达阅读**：开「自动跳转最近阅读」冷启动 → 弹层覆盖在阅读页之上，恢复后书架返回时已是新数据。

- [ ] **Step 5: 交付说明**

交付说明须包含：修改职责范围、与宿主 backupSync 的等价性取舍（恢复反馈对齐宿主手动路径而非启动路径 fire-and-forget；失败弹层保留可重试；快照展示设备名/日期——三项均为设计 §1 拍板）、已跑验证、未验证风险（真机清单 6 项）。

---

## Self-Review 记录

- **覆盖核对**：设计 §1 决策表逐项有实现位——可选端口（T1）、触发时机/静默口径（T1 契约 + T3 接线）、开关/比较/标记（T4 Policy + T5 Impl）、忙态+结果提示/失败重试/抑制位（T2 VM）、根层弹层覆盖任意屏幕（T3）、文案（T3 硬编码中文）、AAR 源兼容（T1 纯新增）。设计 §5 边界无需代码（对齐宿主不处理）。
- **占位符扫描**：无 TBD/TODO；Task 2 Step 1 的 runCurrent 注记给出确定修正路径（删除即兜底），非悬空占位。
- **类型一致性**：`CloudBackupNewer(fileName, deviceName, dateText)` 在 T1/T2/T3/T4/T5 一致；`BackupSyncPromptState.Newer/Restoring/Failed` 在 T2/T3 一致；`shouldPrompt(autoCheckEnabled, latest, lastBackupMark)` 在 T4/T5 一致。
