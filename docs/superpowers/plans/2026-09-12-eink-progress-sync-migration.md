# E-Ink 阅读页云端进度同步原样迁移 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把宿主「同步阅读进度（syncBookProgress）/ 同步增强（syncBookProgressPlus）」两个设置的自动同步行为原样迁移到 E-Ink 阅读页：进书拉取、Activity 级暂停上传、网络恢复同步、5 分钟周期备份、云端超前确认框。

**Architecture:** 模块侧（`:modules:eink`）只做触发编排——`ReaderEngine` 契约新增带默认实现的 `syncCloudProgress(trigger)` / `applyCloudProgress(progress)` 与回调 `onCloudProgressNewer`（AAR 兼容，旧宿主零改动降级）；VM 持有触发门槛状态机（进书武装/初始装载窗口），Screen 在既有 LifecycleEventObserver 上补 ON_PAUSE/ON_RESUME。宿主桥接侧（`app/eink/bridge`）新增纯函数判定核心 `ReaderProgressSyncPolicy`（可单测）+ 编排器 `ReaderProgressSyncer`，内部走 `ReadingProgressGateway` 的两个 UseCase（不直连 `AppWebDav`），复刻宿主文字阅读器路径（`ReadBookViewModel` / `ReadBookLoadDelegate` / `ReadBook.syncProgress`）的门槛矩阵。

**Tech Stack:** Kotlin、Jetpack Compose、StateFlow、Koin、OkHttp WebDav（经 UseCase）、JUnit4 纯函数单测。

## Global Constraints

- 原样迁移：行为矩阵以宿主**文字阅读器**路径为准；漫画阅读器（`MangaReaderDataRepository`）pause 分支有偏差（非 Plus 不上传），**不照抄**。
- 契约新增成员必须带默认实现（已发布 AAR + 多宿主消费，源/二进制兼容；先例：0.3.0 排版协商四成员）。
- 桥接不直连 `AppWebDav`，经 `GetReadingProgressUseCase` / `UploadReadingProgressUseCase`（数据边界约束）。
- 本切片**不含**：手动同步入口（点按 SYNC_PROGRESS、菜单「拉取/覆盖云端进度」）、eink「我的」设置镜像、阅读时长会话统计、宿主旧账修复。这些属后续设计阶段。
- eink 无 TTS，宿主进书 gate 中的 `isSameBook && BaseReadAloudService.isRun` 分支在 eink 恒假，跳过（有意分歧，非缺失）。
- App 启动批量拉取（`App.kt:241-244` → `downloadAllBookProgress`）是进程级共享，两种模式均已生效，本切片零改动。
- 模块对话框文案沿用模块现状（直接硬编码中文，对齐宿主 `restore_progress`/`found_cloud_progress` 文案）。
- 每个任务独立提交；文本改动跑 `git diff --check`。

## 行为基线（宿主门槛矩阵，迁移的对拍基准）

宿主触发点与语义（详细分析见会话记录，代码位：`ReadBookViewModel.kt:1577-1661`、`ReadBookLoadDelegate.kt:189-197/283-304`、`ReadBook.kt:782-828`、`AppWebDav.kt:288-366`）：

| 触发点 | 前置 gate | 主开关关 | 主开关开·Plus 关 | 主开关开·Plus 开 |
|---|---|---|---|---|
| 进书装载完成 | 非跳章进入（`chapterChanged`）、`inBookshelf` | 无操作 | 拉取：云端超前且章节有效→**静默应用**+AppLog；否则无操作（不上传） | 拉取：云端超前→**回调确认**；云端缺失/本地超前→**上传**；相等→无操作 |
| Activity onPause | `!BuildConfig.DEBUG`；`saveRead()` 无条件先行 | 仅 `Backup.autoBack` | **纯上传**+备份 | 拉取：云端缺失/本地超前→上传；云端超前/相等→无操作；随后备份 |
| Activity onResume | — | 应用 `ReadBook.webBookProgress`（Web 服务暂存热进度）；注册网络监听 | 同左 | 同左 |
| 网络恢复 | Plus 开、网络可用、`!justInitData`（初始装载窗口内静默） | 无操作 | 无操作 | 拉取：云端超前→**回调确认**；云端缺失/本地超前→**上传**；相等→无操作 |
| 翻页活动重置 5 分钟计时到期 | 无 DEBUG gate | 上传（UseCase 内部被主开关拦下→实际仅备份） | **上传**+备份 | 同左 |

比较语义（三处宿主比较器共用）：章节下标优先、章内位置次之的字典序；静默应用前过 `durChapterIndex < simulatedTotalChapterNum()` 有效性 gate。上传成功回写 `book.syncTime` 并落库。

---

### Task 1: 契约扩展——ReaderSyncTrigger / ReaderCloudProgress / syncCloudProgress / onCloudProgressNewer

**Files:**
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/README.md`（§4 默认实现成员清单补两行）
- Modify: `modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`（可选能力段补 0.4.0 条目）
- Test: `modules/eink/src/test/java/io/legado/app/eink/contract/ReaderSyncContractCompatTest.kt`

**Interfaces:**
- Consumes: 现有 `ReaderEngine` / `ReaderEngineCallback`（0.3.x 成员面）。
- Produces（后续任务依赖的精确签名）:
  - `enum class ReaderSyncTrigger { BookEntered, ReaderPaused, ReaderResumed, NetworkAvailable, BackupTimer }`
  - `data class ReaderCloudProgress(val chapterIndex: Int, val chapterPos: Int)`
  - `ReaderEngine.syncCloudProgress(trigger: ReaderSyncTrigger)`（默认 `{}`）
  - `ReaderEngine.applyCloudProgress(progress: ReaderCloudProgress)`（默认 `{}`）
  - `ReaderEngineCallback.onCloudProgressNewer(progress: ReaderCloudProgress)`（默认 `{}`）

- [x] **Step 1: 写源兼容守护测试（先失败）**

创建 `modules/eink/src/test/java/io/legado/app/eink/contract/ReaderSyncContractCompatTest.kt`：

```kotlin
package io.legado.app.eink.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 契约源兼容性守护：云端进度同步成员（0.4.0 起）全部带默认实现，
 * 只实现既有成员面的宿主引擎无需改动即可编译——AAR 消费方升级零破坏。
 * LegacyHostEngine 刻意不实现 syncCloudProgress / applyCloudProgress，
 * 编译通过即守护成立。
 */
class ReaderSyncContractCompatTest {

    private class LegacyHostEngine : ReaderEngine {
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
        override fun currentPage(): ReaderPageSnapshot? = null
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
        override fun updateViewSize(width: Int, height: Int) = Unit
        override fun applyStyle(style: ReaderTextStyle) = Unit
        override fun currentStyle(): ReaderTextStyle = ReaderTextStyle()
        override fun relayout() = Unit
        override val pageTouchSlop: Int get() = 0
        override fun headerFooterVisibility(): ReaderHeaderFooterVisibility =
            ReaderHeaderFooterVisibility(headerVisible = false, footerVisible = true)
        override fun formatTimeNow(): String = ""
    }

    private class LegacyCallback : ReaderEngineCallback {
        override fun onRequestShowMenu() = Unit
        override fun onLoadChapterList(book: ReaderBookSnapshot) = Unit
        override fun onContentUpdated(
            relativePosition: Int,
            resetPageOffset: Boolean,
            success: (() -> Unit)?,
        ) = Unit
        override fun onPageChanged() = Unit
        override fun onContentLoadFinish() = Unit
        override fun onLayoutException(e: Throwable) = Unit
        override fun onNotifyBookChanged() = Unit
    }

    @Test
    fun `legacy engine compiles and new sync members default to no-op`() {
        val engine = LegacyHostEngine()
        ReaderSyncTrigger.entries.forEach { engine.syncCloudProgress(it) }
        engine.applyCloudProgress(ReaderCloudProgress(chapterIndex = 1, chapterPos = 0))
    }

    @Test
    fun `legacy callback compiles and onCloudProgressNewer defaults to no-op`() {
        val callback = LegacyCallback()
        callback.onCloudProgressNewer(ReaderCloudProgress(chapterIndex = 2, chapterPos = 5))
    }

    @Test
    fun `cloud progress carries chapter position only`() {
        val progress = ReaderCloudProgress(chapterIndex = 3, chapterPos = 12)
        assertEquals(3, progress.chapterIndex)
        assertEquals(12, progress.chapterPos)
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.ReaderSyncContractCompatTest"`
Expected: 编译失败，`unresolved reference: ReaderSyncTrigger`（类型尚未定义）。

- [x] **Step 3: 扩展契约**

`ReaderEngine.kt`——在 `ReaderEngineCallback` 接口的 `onNotifyBookChanged()` 之后追加默认成员：

```kotlin
    /**
     * 云端进度比本地新（章节下标或章内位置超前），等待用户确认恢复。
     * 确认动作经 [ReaderEngine.applyCloudProgress] 回传；用户放弃则不调，
     * 本地进度保持。默认实现 = 丢弃（宿主不支持同步确认时云端进度不应用，
     * 属诚实降级：不弹框、不覆盖本地）。
     */
    fun onCloudProgressNewer(progress: ReaderCloudProgress) {}
```

在文件顶部（`ReaderPrepareResult` 之后）新增两个跨桥类型：

```kotlin
/**
 * 云端进度同步触发时机。同一同步核心按触发点套不同门槛，矩阵复刻宿主
 * 「同步阅读进度 / 同步增强」两设置的行为（`ReadBookViewModel` /
 * `ReadBookLoadDelegate` 文字阅读器路径为基准）：
 *
 * | 触发点            | 主开关关   | 主开关开·Plus 关                | 主开关开·Plus 开                       |
 * |------------------|-----------|--------------------------------|---------------------------------------|
 * | BookEntered      | 无操作     | 静默拉取：云端超前且章节有效→应用  | 云端超前→确认回调；缺失/本地超前→上传；相等→无 |
 * | ReaderPaused     | 仅自动备份 | 上传 + 自动备份                 | 双向（云端超前则放弃）+ 自动备份          |
 * | ReaderResumed    | 应用 Web 服务暂存进度（无开关 gate）           | 同左                                   |
 * | NetworkAvailable | 无操作     | 无操作                          | 双向（云端超前→确认回调；否则上传）        |
 * | BackupTimer      | 仅自动备份（上传被主开关拦下）                  | 上传 + 自动备份                         |
 *
 * 模块按生命周期节点触发；宿主在引擎侧执行全部判定与网络 IO。
 */
enum class ReaderSyncTrigger {
    /** 进书装载完成（宿主 loadDataCompleted 尾部同步位）。 */
    BookEntered,

    /** 阅读页 Activity 级暂停：息屏 / 退后台 / 离开任务。 */
    ReaderPaused,

    /** 阅读页 Activity 级恢复：先应用 Web 服务暂存的热进度。 */
    ReaderResumed,

    /** 网络恢复（宿主 onNetworkChanged 位；模块侧已滤初始装载窗口）。 */
    NetworkAvailable,

    /** 周期进度备份计时到期（宿主 5 分钟自动任务；由阅读活动重置）。 */
    BackupTimer,
}

/**
 * 云端进度恢复所需的最小快照。宿主判定「云端比本地新」后经
 * [ReaderEngineCallback.onCloudProgressNewer] 通知模块；用户确认后模块经
 * [ReaderEngine.applyCloudProgress] 回传同值应用。
 */
data class ReaderCloudProgress(
    /** 目标章节下标（0-based）。 */
    val chapterIndex: Int,
    /** 章内字符位置。 */
    val chapterPos: Int,
)
```

`ReaderEngine` 接口内（`saveReadingProgress()` 之后）新增 section：

```kotlin
    // ---- 云端进度同步（可选能力，默认实现 = 不支持）----

    /**
     * 云端进度同步入口。模块在阅读页生命周期节点按 [ReaderSyncTrigger]
     * 触发；宿主按自身「同步阅读进度 / 同步增强」设置与
     * [ReaderSyncTrigger] KDoc 的门槛矩阵执行：拉取后引擎侧应用时界面经
     * 既有的内容更新回调刷新；云端超前需确认时经
     * [ReaderEngineCallback.onCloudProgressNewer] 通知模块。
     *
     * 非_suspend、主线程调用，宿主内部自行转异步；同步行为对调用方静默
     * （失败仅入宿主日志，无 toast——与宿主自动路径一致）。
     * 默认实现 = 宿主不支持（无同步行为；本地进度落库不受影响）。
     */
    fun syncCloudProgress(trigger: ReaderSyncTrigger) {}

    /**
     * 用户确认恢复云端进度（[ReaderEngineCallback.onCloudProgressNewer]
     * 的确认动作）：宿主把会话进度应用到目标章节位置并触发内容重载。
     * 默认无实现。
     */
    fun applyCloudProgress(progress: ReaderCloudProgress) {}
```

同步更新接口头部的流程图注释（「离开阅读」行之后加一行）：

```text
 * 云端进度同步 ─► syncCloudProgress(trigger) ─► 云端超前 ─► onCloudProgressNewer
 *                                          └─► 用户确认 ─► applyCloudProgress
```

- [x] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.contract.ReaderSyncContractCompatTest"`
Expected: PASS（3 用例）。

- [x] **Step 5: 同步契约文档**

`contract/README.md` §4 中 `ReaderStyleCatalog.kt` 条目后（「`ReaderEngine` 四个关联端口成员」清单之后）追加：

```markdown
- **云端进度同步（0.4.0 起）** — `ReaderEngine` 新增两个带默认实现的成员
  + 回调一个默认成员：`syncCloudProgress(trigger)`（触发矩阵见
  `ReaderSyncTrigger` KDoc）、`applyCloudProgress(progress)`、
  `ReaderEngineCallback.onCloudProgressNewer(progress)`。旧宿主零改动即
  降级（无同步行为，本地进度不受影响）；要启用须复刻宿主
  syncBookProgress/syncBookProgressPlus 的门槛矩阵并走宿主进度网关。
```

`contract/EINK-PORTING.md` 在「排版协商与字体端口（0.3.0 起）」条目后追加同义短条目（0.4.0 起，云端进度同步三成员，旧宿主零改动降级）。

- [x] **Step 6: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt \
        modules/eink/src/main/java/io/legado/app/eink/contract/README.md \
        modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md \
        modules/eink/src/test/java/io/legado/app/eink/contract/ReaderSyncContractCompatTest.kt
git commit -m "feat(eink): 契约新增云端进度同步成员（默认实现，AAR 兼容）"
```

---

### Task 2: 模块侧编排——ReaderSyncGate + VM 触发点 + Screen 生命周期与确认框

**Files:**
- Create: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderSyncGate.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`
- Modify: `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt`
- Test: `modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderSyncGateTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ReaderSyncTrigger` / `ReaderCloudProgress` / `engine.syncCloudProgress` / `engine.applyCloudProgress`；VM 现有 `attach()` / `onContentLoadFinish()` / `onCleared()` / `upContent()`；Screen 现有 LifecycleEventObserver（`ReaderScreen.kt:663-678`）与 `EInkDialog` 用法（`showRemoveConfirm` 块）。
- Produces: VM 公开方法 `onActivityResumed()` / `onActivityPaused()` / `confirmCloudProgress()` / `dismissCloudProgress()`；`ReaderUiState.cloudProgressPrompt`。

- [x] **Step 1: 写 ReaderSyncGate 失败测试**

创建 `modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderSyncGateTest.kt`：

```kotlin
package io.legado.app.eink.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端同步触发门槛状态机（复刻宿主 justInitData / 装载完成同步位语义）。
 */
class ReaderSyncGateTest {

    @Test
    fun `fresh entry arms entry sync and opens initial window`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        assertTrue(gate.consumeEntrySync())
    }

    @Test
    fun `entry sync is one-shot`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        assertTrue(gate.consumeEntrySync())
        assertFalse(gate.consumeEntrySync())
    }

    @Test
    fun `network sync is blocked during initial window`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        gate.consumeEntrySync()
        assertFalse(gate.allowNetworkSync())
    }

    @Test
    fun `pause closes initial window and releases network sync`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        gate.consumeEntrySync()
        gate.onPaused()
        assertTrue(gate.allowNetworkSync())
    }

    @Test
    fun `network sync is allowed when never armed`() {
        val gate = ReaderSyncGate()
        assertTrue(gate.allowNetworkSync())
    }

    @Test
    fun `re-arm only on fresh entry`() {
        val gate = ReaderSyncGate()
        gate.onFreshEntryStarted()
        gate.consumeEntrySync()
        gate.onPaused()
        // 重挂载（返回自目录/换源）不重新武装——宿主 InitData 仅每 VM 一次
        assertFalse(gate.consumeEntrySync())
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderSyncGateTest"`
Expected: 编译失败，`unresolved reference: ReaderSyncGate`。

- [x] **Step 3: 实现 ReaderSyncGate**

创建 `modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderSyncGate.kt`：

```kotlin
package io.legado.app.eink.feature.reader

/**
 * 云端进度同步的模块侧触发门槛（复刻宿主 justInitData / 装载完成同步位）。
 *
 * - [onFreshEntryStarted]：新阅读会话（VM 首次装载一本书）开始——武装
 *   进书同步并进入初始装载窗口；
 * - [consumeEntrySync]：进书内容就绪时消费（一次性），返回是否应触发
 *   BookEntered 同步；
 * - [allowNetworkSync]：网络恢复同步仅在初始装载窗口结束后放行（避免
 *   与进书同步竞态，宿主 `!justInitData` 同位）；
 * - [onPaused]：Activity 级暂停关闭初始装载窗口（宿主 handleOnPause
 *   末尾清零 justInitData 同位）。
 */
internal class ReaderSyncGate {

    private var entrySyncArmed = false
    private var withinInitialLoad = false

    /** 仅在新会话装载开始时调用（`loadedBookUrl == null` 判定由 VM 负责）。 */
    fun onFreshEntryStarted() {
        entrySyncArmed = true
        withinInitialLoad = true
    }

    /** 进书内容就绪：一次性消费武装标记。 */
    fun consumeEntrySync(): Boolean {
        if (!entrySyncArmed) return false
        entrySyncArmed = false
        return true
    }

    /** 网络恢复同步是否放行。 */
    fun allowNetworkSync(): Boolean = !withinInitialLoad

    /** Activity 级暂停：关闭初始装载窗口。 */
    fun onPaused() {
        withinInitialLoad = false
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderSyncGateTest"`
Expected: PASS（6 用例）。

- [x] **Step 5: VM 接线**

`ReaderViewModel.kt`：

1）imports 补：

```kotlin
import android.net.ConnectivityManager
import android.net.Network
import io.legado.app.eink.contract.ReaderCloudProgress
import io.legado.app.eink.contract.ReaderSyncTrigger
```

2）`ReaderUiState` 字段区（`batteryPercent` 之后）补：

```kotlin
    /** 云端进度恢复确认（非 null = 显示确认框；宿主 ConfirmRestoreProgress 同义）。 */
    val cloudProgressPrompt: ReaderCloudProgress? = null,
```

3）类成员区（`relayoutJob` 之后）补：

```kotlin
    /** 云端同步触发门槛（进书武装 + 初始装载窗口，宿主 justInitData 同位）。 */
    private val syncGate = ReaderSyncGate()
    private var progressBackupJob: Job? = null
    private var networkWatcher: ConnectivityManager.NetworkCallback? = null
```

4）`attach()` 内 `hideControls()` 之后、`attachJob = ...` 之前补：

```kotlin
        // 新会话首次装载：武装进书同步并进入初始装载窗口（网络恢复同步在
        // 窗口内静默，避免与进书同步竞态——宿主 justInitData 同位）。重挂载
        // （目录/换源返回）不重新武装：宿主 InitData 仅每 VM 一次
        if (loadedBookUrl == null) syncGate.onFreshEntryStarted()
```

5）`attach()` 热缓存分支（`engine.refreshToc(); return@launch` 之前，即 `if (engine.hasLaidOutPages) { ... }` 块内 `upContent()` 之后）补：

```kotlin
                    if (syncGate.consumeEntrySync()) {
                        engine.syncCloudProgress(ReaderSyncTrigger.BookEntered)
                    }
```

6）`onContentLoadFinish()`（现 `upContent()` 单行实现）改为：

```kotlin
    override fun onContentLoadFinish() {
        upContent()
        // 新会话首次内容就绪：触发进书同步（一次性；宿主 loadDataCompleted 尾部位）
        if (syncGate.consumeEntrySync()) {
            engine.syncCloudProgress(ReaderSyncTrigger.BookEntered)
        }
    }
```

7）回调区（`onNotifyBookChanged()` 之后）补：

```kotlin
    override fun onCloudProgressNewer(progress: ReaderCloudProgress) {
        _uiState.update { it.copy(cloudProgressPrompt = progress) }
    }
```

8）`onReaderShown()` 之后新增生命周期与同步方法组：

```kotlin
    // ==================== 云端进度同步（宿主同步阅读进度/同步增强迁移位）====================

    /** Activity 级恢复（ON_RESUME）：引擎应用 Web 暂存进度；注册网络监听。 */
    fun onActivityResumed() {
        engine.syncCloudProgress(ReaderSyncTrigger.ReaderResumed)
        registerNetworkWatcher()
    }

    /** Activity 级暂停（ON_PAUSE）：取消周期备份、同步/上传进度、关初始窗口、停网络监听。 */
    fun onActivityPaused() {
        progressBackupJob?.cancel()
        engine.syncCloudProgress(ReaderSyncTrigger.ReaderPaused)
        syncGate.onPaused()
        unregisterNetworkWatcher()
    }

    /** 用户确认恢复云端进度。 */
    fun confirmCloudProgress() {
        _uiState.value.cloudProgressPrompt?.let { prompt ->
            _uiState.update { it.copy(cloudProgressPrompt = null) }
            engine.applyCloudProgress(prompt)
        }
    }

    /** 用户放弃恢复云端进度（保留本地进度）。 */
    fun dismissCloudProgress() {
        _uiState.update { it.copy(cloudProgressPrompt = null) }
    }

    /** 周期进度备份计时（宿主 startBackupJob 同位：阅读活动重置 5 分钟计时）。 */
    private fun restartProgressBackupTimer() {
        progressBackupJob?.cancel()
        progressBackupJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PROGRESS_BACKUP_INTERVAL_MS)
            engine.syncCloudProgress(ReaderSyncTrigger.BackupTimer)
        }
    }

    /** 网络恢复监听（宿主 NetworkChangedListener 同位；minSdk 26 恒走 NetworkCallback）。 */
    private fun registerNetworkWatcher() {
        if (networkWatcher != null) return
        val cm = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java) ?: return
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (syncGate.allowNetworkSync()) {
                    engine.syncCloudProgress(ReaderSyncTrigger.NetworkAvailable)
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(watcher) }
            .onFailure { networkWatcher = null; return }
        networkWatcher = watcher
    }

    private fun unregisterNetworkWatcher() {
        val watcher = networkWatcher ?: return
        networkWatcher = null
        runCatching {
            getApplication<Application>()
                .getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(watcher)
        }
    }
```

9）`upContent()` 方法体末尾（`ReaderSessionCache.start(it)` 之后、`success?.invoke()` 之前）补：

```kotlin
            restartProgressBackupTimer()
```

10）`onCleared()` 内 `stopAutoPlay()` 之后补：

```kotlin
        progressBackupJob?.cancel()
        unregisterNetworkWatcher()
```

11）文件底部常量区（`RELAYOUT_DEBOUNCE_MS` 附近）补：

```kotlin
/** 周期进度备份间隔（毫秒），宿主 startBackupJob 同值。 */
internal const val PROGRESS_BACKUP_INTERVAL_MS = 5 * 60 * 1000L
```

- [x] **Step 6: Screen 接线**

`ReaderScreen.kt`：

1）既有生命周期 observer（`Lifecycle.Event.ON_STOP -> viewModel.onReaderHidden()` 处）扩为：

```kotlin
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onReaderHidden()
                Lifecycle.Event.ON_START -> viewModel.onReaderShown()
                Lifecycle.Event.ON_PAUSE -> viewModel.onActivityPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onActivityResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.onReaderShown()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.onActivityResumed()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onReaderHidden()
        }
```

注意（2026-09-12 勘误，真机反馈修复）：初版此处写「`onDispose` 不触发 `onActivityPaused()`——应用内导航宿主同形不发生上传」，**判断有误**。宿主 `MainNavGraph` 在阅读路由 `onDispose` 里显式调用 `pauseReader()`（`MainNavGraph.kt:765`），而 NavDisplay 在应用内导航（去目录/换源）时同样卸载阅读路由——即宿主每次阅读组合卸载都会补发一次 pause → 上传进度 + 自动备份。修正：eink onDispose 调 `viewModel.onReaderDisposed()`（VM 内 `activityResumedMark` 防与真实 ON_PAUSE 双触发，宿主 readerResumeState 同位），并在 `attach()` 重挂载时补恢复位（ReaderResumed + 重挂网络监听，宿主重组 initData 回调 resumeReader 同形）。表现为：退出阅读/去目录/换源均触发一次上传，与宿主一致。

2）`showRemoveConfirm` 对话框块之后追加：

```kotlin
        uiState.cloudProgressPrompt?.let {
            EInkDialog(
                onDismiss = { viewModel.dismissCloudProgress() },
                title = "恢复进度",
                onConfirm = { viewModel.confirmCloudProgress() },
            ) {
                EInkText(
                    text = "发现云端进度，是否恢复？",
                    style = EInkTheme.typography.bodyMedium
                )
            }
        }
```

- [x] **Step 7: 编译 + 模块测试**

Run: `.\gradlew.bat :modules:eink:testDebugUnitTest`
Expected: 全部 PASS（含既有用例与新增 ReaderSyncGateTest / 兼容测试）。

- [x] **Step 8: Commit**

```bash
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderSyncGate.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt \
        modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderScreen.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderSyncGateTest.kt
git commit -m "feat(eink): 阅读页接入云端进度同步触发点（生命周期/网络/周期备份/确认框）"
```

---

### Task 3: 桥接判定纯函数 ReaderProgressSyncPolicy + 测试

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/ReaderProgressSyncPolicy.kt`
- Test: `app/src/test/java/io/legado/app/eink/bridge/ReaderProgressSyncPolicyTest.kt`

**Interfaces:**
- Consumes: `io.legado.app.domain.model.ReadingProgress`（字段：name/author/durChapterIndex/durChapterPos/durChapterTime/durChapterTitle）。
- Produces:
  - `ReaderProgressSyncPolicy.ProgressRelation { CloudMissing, CloudAhead, LocalAhead, Equal }`
  - `ReaderProgressSyncPolicy.relation(cloud: ReadingProgress?, localChapterIndex: Int, localChapterPos: Int): ProgressRelation`
  - `ReaderProgressSyncPolicy.chapterIndexInBounds(cloudChapterIndex: Int, totalChapters: Int): Boolean`

- [x] **Step 1: 写失败测试**

创建 `app/src/test/java/io/legado/app/eink/bridge/ReaderProgressSyncPolicyTest.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.model.ReadingProgress
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.CloudAhead
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.CloudMissing
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.Equal
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.LocalAhead
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端进度比较核心：复刻宿主三处比较器（ReadBook.syncProgress /
 * ReadBookLoadDelegate.syncBookProgress / downloadAllBookProgress）共用的
 * 字典序语义与章节有效性 gate。
 */
class ReaderProgressSyncPolicyTest {

    private fun cloud(index: Int, pos: Int) =
        ReadingProgress("", "", index, pos, 0L, null)

    @Test
    fun `null cloud is CloudMissing`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(null, localChapterIndex = 3, localChapterPos = 5)
                == CloudMissing
        )
    }

    @Test
    fun `cloud chapter ahead is CloudAhead`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(4, 0), 3, 100) == CloudAhead
        )
    }

    @Test
    fun `same chapter pos ahead is CloudAhead`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 6), 3, 5) == CloudAhead
        )
    }

    @Test
    fun `exact match is Equal`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 5), 3, 5) == Equal
        )
    }

    @Test
    fun `local chapter ahead is LocalAhead even with smaller pos`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 100), 4, 0) == LocalAhead
        )
    }

    @Test
    fun `same chapter local pos ahead is LocalAhead`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 4), 3, 5) == LocalAhead
        )
    }

    @Test
    fun `bounds gate matches host simulated chapter num semantics`() {
        assertTrue(ReaderProgressSyncPolicy.chapterIndexInBounds(0, 10))
        assertTrue(ReaderProgressSyncPolicy.chapterIndexInBounds(9, 10))
        assertFalse(ReaderProgressSyncPolicy.chapterIndexInBounds(10, 10))
        assertFalse(ReaderProgressSyncPolicy.chapterIndexInBounds(-1, 10))
        assertFalse(ReaderProgressSyncPolicy.chapterIndexInBounds(0, 0))
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderProgressSyncPolicyTest"`
Expected: 编译失败，`unresolved reference: ReaderProgressSyncPolicy`。

- [x] **Step 3: 实现**

创建 `app/src/main/java/io/legado/app/eink/bridge/ReaderProgressSyncPolicy.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.domain.model.ReadingProgress

/**
 * 云端进度同步判定核心（纯函数）。复刻宿主三处比较器共用的字典序语义：
 * 章节下标优先、章内位置次之；静默/确认应用前过章节有效性 gate
 * （宿主 `durChapterIndex < simulatedTotalChapterNum()`，防目录变短/换源
 * 后云端位置越界）。
 */
internal object ReaderProgressSyncPolicy {

    /** 云端进度与本地会话进度的相对关系。 */
    enum class ProgressRelation {
        /** 云端无进度文件（或读取失败视为无）。 */
        CloudMissing,

        /** 云端比本地新。 */
        CloudAhead,

        /** 本地比云端新。 */
        LocalAhead,

        /** 完全一致。 */
        Equal,
    }

    fun relation(
        cloud: ReadingProgress?,
        localChapterIndex: Int,
        localChapterPos: Int,
    ): ProgressRelation {
        cloud ?: return ProgressRelation.CloudMissing
        return when {
            cloud.durChapterIndex > localChapterIndex -> ProgressRelation.CloudAhead
            cloud.durChapterIndex == localChapterIndex &&
                cloud.durChapterPos > localChapterPos -> ProgressRelation.CloudAhead
            cloud.durChapterIndex == localChapterIndex &&
                cloud.durChapterPos == localChapterPos -> ProgressRelation.Equal
            else -> ProgressRelation.LocalAhead
        }
    }

    fun chapterIndexInBounds(cloudChapterIndex: Int, totalChapters: Int): Boolean =
        cloudChapterIndex >= 0 && cloudChapterIndex < totalChapters
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.eink.bridge.ReaderProgressSyncPolicyTest"`
Expected: PASS（7 用例）。

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/ReaderProgressSyncPolicy.kt \
        app/src/test/java/io/legado/app/eink/bridge/ReaderProgressSyncPolicyTest.kt
git commit -m "feat(eink): 桥接新增云端进度比较纯函数（宿主三比较器共用语义）"
```

---

### Task 4: 桥接编排 ReaderProgressSyncer + ReaderEngineImpl / TocEngineImpl 接线

**Files:**
- Create: `app/src/main/java/io/legado/app/eink/bridge/ReaderProgressSyncer.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt`
- Modify: `app/src/main/java/io/legado/app/eink/bridge/TocEngineImpl.kt`（`saveReadingProgress` 置跳章标记）

**Interfaces:**
- Consumes: Task 1 契约成员；Task 3 `ReaderProgressSyncPolicy`；`GetReadingProgressUseCase` / `UploadReadingProgressUseCase` / `BackupSettingsGateway`（Koin 已绑定，`appModule.kt:472`）；`ReadBook.saveRead/setProgress/webBookProgress/inBookshelf/durChapterIndex/durChapterPos`；`Backup.autoBack(context)`；`Book.update()`（`io.legado.app.help.book.update`）。
- Produces: `ReaderProgressSyncer`（`sync(trigger)` / `applyCloudProgress(progress)` / `markChapterJumped()` / `resetChapterJumped()` / `var onCloudProgressNewer`）；`ReaderEngineImpl.markProgressJumpedForTocJump()`（internal，供 TocEngineImpl）。

- [x] **Step 1: 实现 ReaderProgressSyncer**

创建 `app/src/main/java/io/legado/app/eink/bridge/ReaderProgressSyncer.kt`：

```kotlin
package io.legado.app.eink.bridge

import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.model.ReadingProgress
import io.legado.app.domain.usecase.GetReadingProgressUseCase
import io.legado.app.domain.usecase.UploadReadingProgressUseCase
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation
import io.legado.app.eink.contract.ReaderCloudProgress
import io.legado.app.eink.contract.ReaderSyncTrigger
import io.legado.app.help.book.update
import io.legado.app.help.storage.Backup
import io.legado.app.model.ReadBook
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 云端进度同步编排（宿主 ReadBook.syncProgress / ReadBookLoadDelegate.
 * syncBookProgress / ReadBookViewModel.handleOnPause 的 eink 同构实现，
 * 门槛矩阵见 ReaderSyncTrigger KDoc）。
 *
 * 与宿主的等价性要点：
 * - 拉取/上传走 ReadingProgressGateway 的两个 UseCase（不直连 AppWebDav）；
 * - ReaderPaused 的「同步+自动备份」整段 DEBUG 构建跳过（宿主 handleOnPause
 *   同位），但 saveRead 无条件先行；BackupTimer 无 DEBUG gate（宿主
 *   startBackupJob 同位）；
 * - 进书同步受「目录跳章进入」标记抑制（宿主 chapterChanged 同位）；
 * - 云端超前的应用路径先过章节有效性 gate；
 * - 上传成功回写 book.syncTime 并落库（宿主 LoadDelegate.uploadBookProgress
 *   同构）。
 */
internal class ReaderProgressSyncer(
    private val getReadingProgress: GetReadingProgressUseCase,
    private val uploadReadingProgress: UploadReadingProgressUseCase,
    private val backupSettingsGateway: BackupSettingsGateway,
) {

    /** 云端超前需确认时通知模块（ReaderEngineImpl 接线到当前注册回调）。 */
    @Volatile
    var onCloudProgressNewer: ((ReaderCloudProgress) -> Unit)? = null

    /** 目录跳章进入标记（宿主 chapterChanged 同位）；进书同步消费即清零。 */
    @Volatile
    private var chapterJumpedSinceEntry = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 目录跳章发生（jumpToPosition / TocEngine 落进度跳转）时置位。 */
    fun markChapterJumped() {
        chapterJumpedSinceEntry = true
    }

    /** 阅读会话注销时清零（跨会话残留防误抑制下一次进书同步）。 */
    fun resetChapterJumped() {
        chapterJumpedSinceEntry = false
    }

    fun sync(trigger: ReaderSyncTrigger) {
        when (trigger) {
            ReaderSyncTrigger.BookEntered -> syncOnEntered()
            ReaderSyncTrigger.ReaderPaused -> syncOnPaused()
            ReaderSyncTrigger.ReaderResumed -> applyPendingWebProgress()
            ReaderSyncTrigger.NetworkAvailable -> syncOnNetworkAvailable()
            ReaderSyncTrigger.BackupTimer -> backupTimerFired()
        }
    }

    fun applyCloudProgress(progress: ReaderCloudProgress) {
        scope.launch {
            val book = ReadBook.book ?: return@launch
            ReadBook.setProgress(
                BookProgress(
                    name = book.name,
                    author = book.author,
                    durChapterIndex = progress.chapterIndex,
                    durChapterPos = progress.chapterPos,
                    durChapterTime = System.currentTimeMillis(),
                    durChapterTitle = book.durChapterTitle,
                )
            )
        }
    }

    // ---- 触发点实现 ----

    /** 进书装载完成：宿主 ReadBookLoadDelegate.loadDataCompleted 尾部同步位。 */
    private fun syncOnEntered() {
        if (consumeChapterJumped()) return
        val settings = backupSettingsGateway.currentSettings
        if (!settings.syncBookProgress) return
        if (!ReadBook.inBookshelf) return
        val book = ReadBook.book ?: return
        val plus = settings.syncBookProgressPlus
        scope.launch {
            val cloud = fetchCloudProgress(book)
            when (ReaderProgressSyncPolicy.relation(cloud, ReadBook.durChapterIndex, ReadBook.durChapterPos)) {
                ProgressRelation.CloudAhead -> {
                    if (!ReaderProgressSyncPolicy.chapterIndexInBounds(
                            cloud!!.durChapterIndex, book.simulatedTotalChapterNum()
                        )
                    ) return@launch
                    if (plus) {
                        onCloudProgressNewer?.invoke(
                            ReaderCloudProgress(cloud.durChapterIndex, cloud.durChapterPos)
                        )
                    } else {
                        applyProgress(cloud)
                        AppLog.put("自动同步阅读进度成功《${book.name}》 ${cloud.durChapterTitle}")
                    }
                }
                // 非语义分支：相等不动（宿主自动路径无 toast）；本地超前仅 Plus 上传
                ProgressRelation.CloudMissing, ProgressRelation.LocalAhead ->
                    if (plus) uploadCurrentProgress()
                ProgressRelation.Equal -> Unit
            }
        }
    }

    /** Activity 级暂停：宿主 ReadBookViewModel.handleOnPause 同步位。 */
    private fun syncOnPaused() {
        // saveRead 无条件先行（宿主顺序：落库 → 同步）；同步+备份整段 DEBUG 跳过
        ReadBook.saveRead()
        if (BuildConfig.DEBUG) return
        val plus = backupSettingsGateway.currentSettings.syncBookProgressPlus
        scope.launch {
            val book = ReadBook.book ?: return@launch
            if (plus) {
                val cloud = fetchCloudProgress(book)
                when (ReaderProgressSyncPolicy.relation(cloud, ReadBook.durChapterIndex, ReadBook.durChapterPos)) {
                    // 云端超前：留给下次进书确认（宿主 onPause 无回调即放弃）；
                    // 相等不动
                    ProgressRelation.CloudMissing, ProgressRelation.LocalAhead ->
                        uploadCurrentProgress()
                    ProgressRelation.CloudAhead, ProgressRelation.Equal -> Unit
                }
            } else {
                uploadCurrentProgress()
            }
            Backup.autoBack(appCtx)
        }
    }

    /** Activity 级恢复：宿主 handleOnResume 的 webBookProgress 热应用位。 */
    private fun applyPendingWebProgress() {
        val pending = ReadBook.webBookProgress ?: return
        scope.launch {
            ReadBook.webBookProgress = null
            ReadBook.setProgress(pending)
        }
    }

    /** 网络恢复：宿主 ReadBookViewModel.onNetworkChanged 位（Plus 专属）。 */
    private fun syncOnNetworkAvailable() {
        if (!backupSettingsGateway.currentSettings.syncBookProgressPlus) return
        if (!NetworkUtils.isAvailable()) return
        val book = ReadBook.book ?: return
        scope.launch {
            val cloud = fetchCloudProgress(book)
            when (ReaderProgressSyncPolicy.relation(cloud, ReadBook.durChapterIndex, ReadBook.durChapterPos)) {
                ProgressRelation.CloudAhead -> {
                    if (!ReaderProgressSyncPolicy.chapterIndexInBounds(
                            cloud!!.durChapterIndex, book.simulatedTotalChapterNum()
                        )
                    ) return@launch
                    onCloudProgressNewer?.invoke(
                        ReaderCloudProgress(cloud.durChapterIndex, cloud.durChapterPos)
                    )
                }
                ProgressRelation.CloudMissing, ProgressRelation.LocalAhead ->
                    uploadCurrentProgress()
                ProgressRelation.Equal -> Unit
            }
        }
    }

    /** 周期备份计时到期：宿主 ReadBookViewModel.startBackupJob 到期动作。 */
    private fun backupTimerFired() {
        scope.launch {
            ReadBook.saveRead()
            uploadCurrentProgress()
            Backup.autoBack(appCtx)
        }
    }

    // ---- 内部工具 ----

    private fun consumeChapterJumped(): Boolean {
        val jumped = chapterJumpedSinceEntry
        chapterJumpedSinceEntry = false
        return jumped
    }

    private suspend fun fetchCloudProgress(book: Book): ReadingProgress? {
        return runCatching { getReadingProgress.execute(book.name, book.author) }
            .onFailure {
                AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
            }
            .getOrNull()
    }

    private suspend fun uploadCurrentProgress() {
        val book = ReadBook.book ?: return
        val uploadTime = runCatching {
            uploadReadingProgress.execute(
                ReadingProgress(
                    name = book.name,
                    author = book.author,
                    durChapterIndex = ReadBook.durChapterIndex,
                    durChapterPos = ReadBook.durChapterPos,
                    durChapterTime = System.currentTimeMillis(),
                    durChapterTitle = book.durChapterTitle,
                )
            )
        }
            .onFailure { AppLog.put("上传进度失败\n${it.localizedMessage}", it) }
            .getOrNull()
        if (uploadTime != null) {
            book.syncTime = uploadTime
            book.update()
        }
    }

    private fun applyProgress(cloud: ReadingProgress) {
        val book = ReadBook.book ?: return
        ReadBook.setProgress(
            BookProgress(
                name = book.name,
                author = book.author,
                durChapterIndex = cloud.durChapterIndex,
                durChapterPos = cloud.durChapterPos,
                durChapterTime = cloud.durChapterTime,
                durChapterTitle = cloud.durChapterTitle,
            )
        )
    }
}
```

**实现者注意**：`ReadBook` 的包是 `io.legado.app.model.ReadBook`（import 已按此写）；`Book.update()` 扩展来自 `io.legado.app.help.book.BookExtensions`（import `io.legado.app.help.book.update`），若编译报 unresolved 则改为 `appDb.bookDao.update(book)`（`TocEngineImpl` 先例，import `io.legado.app.data.appDb`）。以编译器为准修正后再提交。

- [x] **Step 2: ReaderEngineImpl 接线**

`ReaderEngineImpl.kt`：

1）`styleScope` 声明附近补依赖与实例：

```kotlin
    private val getReadingProgressUseCase: GetReadingProgressUseCase by inject()
    private val uploadReadingProgressUseCase: UploadReadingProgressUseCase by inject()
    private val backupSettingsGateway: BackupSettingsGateway by inject()

    private val progressSyncer by lazy {
        ReaderProgressSyncer(
            getReadingProgress = getReadingProgressUseCase,
            uploadReadingProgress = uploadReadingProgressUseCase,
            backupSettingsGateway = backupSettingsGateway,
        )
    }

    /** 目录页无会话跳章落进度（TocEngineImpl 转发）：抑制下一次进书同步。 */
    internal fun markProgressJumpedForTocJump() {
        progressSyncer.markChapterJumped()
    }
```

2）实现契约新成员（`saveReadingProgress()` 之后）：

```kotlin
    override fun syncCloudProgress(trigger: ReaderSyncTrigger) {
        // 提示回调绑定到当前注册的模块回调（迟绑定：注册可能晚于触发）
        progressSyncer.onCloudProgressNewer = { progress ->
            cachedAdapter?.callback?.onCloudProgressNewer(progress)
        }
        progressSyncer.sync(trigger)
    }

    override fun applyCloudProgress(progress: ReaderCloudProgress) {
        progressSyncer.applyCloudProgress(progress)
    }
```

3）`jumpToPosition(...)` 实现内（`ReadBook.openChapter` 之前）补：

```kotlin
        progressSyncer.markChapterJumped()
```

4）`unregister(callback)` 末尾（`chapterPager.cancelPending()` 之后）补：

```kotlin
        progressSyncer.resetChapterJumped()
```

5）`EngineCallBackAdapter.sureNewProgress` 空实现改为转发（修复「退化不诚实」隐账）：

```kotlin
        override fun sureNewProgress(progress: io.legado.app.data.entities.BookProgress) {
            // 宿主 ReadBook.CallBack 通道的云端超前通知：与同步编排同路转发
            callback.onCloudProgressNewer(
                ReaderCloudProgress(progress.durChapterIndex, progress.durChapterPos)
            )
        }
```

- [x] **Step 3: TocEngineImpl 置跳章标记**

`TocEngineImpl.kt` 的 `saveReadingProgress(...)`（`appDb.bookDao.update(book)` 之后）补：

```kotlin
        ReaderEngineImpl.markProgressJumpedForTocJump()
```

（无会话路径的目录跳章：落库进度后新开阅读页，下一次进书同步被抑制——宿主 TOC 进入带 `chapterChanged` 同位。）

- [x] **Step 4: 编译验证**

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: BUILD SUCCESSFUL（按 Step 1 注意项校对 import）。

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/eink/bridge/ReaderProgressSyncer.kt \
        app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt \
        app/src/main/java/io/legado/app/eink/bridge/TocEngineImpl.kt
git commit -m "feat(eink): 桥接实现云端进度同步编排（宿主门槛矩阵同构）"
```

---

### Task 5: 集成验证

**Files:** 无新文件（验证任务）。

**Interfaces:**
- Consumes: Task 1-4 全部产出。

- [x] **Step 1: 主验证集**

```powershell
.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache
```

Expected: 全部通过（含新增 3 个测试类 16 个用例）。

- [x] **Step 2: 模块测试单列确认**

```powershell
.\gradlew.bat :modules:eink:testDebugUnitTest
```

Expected: PASS。

- [x] **Step 3: 文本改动检查**

```bash
git diff --check
```

Expected: 无输出。

- [ ] **Step 4: 真机验证清单（交付后用户执行）**

1. **双向覆盖**：完整模式读到第 5 章 → 退到书架息屏（触发上传）→ eink 模式打开同书（WebDAV 已配置、两开关开）→ 应静默落在第 5 章（Plus 开则云端超前时不弹框的静默路径走 Plus 分支：本地与云端一致；反向：eink 读到第 8 章后息屏 → 完整模式打开 → 应到第 8 章）。
2. **云端超前确认框**：设备 A eink 读到第 10 章并息屏上传；设备 B（或改本地 DB 使本地落后）eink 打开同书（Plus 开）→ 应弹「恢复进度」框 → 确认后跳云端位置；取消则保留本地。
3. **目录跳章不回退**：eink 目录里跳到第 3 章 → 不发生进书同步把进度拉回云端旧位置。
4. **网络恢复同步**：飞行模式进书（Plus 开）→ 联网 → 云端超前时应弹确认框。
5. **DEBUG 跳过**：debug 构建息屏不应触发上传/备份（Release 验证项 1/2 已覆盖）。
6. **5 分钟周期备份**：连续翻页 5 分钟以上 → WebDAV `bookProgress/` 文件更新时间前进。

- [x] **Step 5: 交付说明与后续设计入口**

交付说明须包含：修改职责范围、与宿主矩阵的等价性取舍（TTS gate 跳过、onDispose 补发 pause 同步【勘误修正，见 Task 2 Step 6】、BackupTimer 前置 saveRead）、已跑验证、未验证风险（真机清单 6 项）。后续设计阶段入口：手动同步入口、eink「我的」设置镜像（跨模式同键）、宿主「pause 后 VM 销毁丢上传」旧账、暂停触发密度节流（墨水屏息屏常态）。

---

## Self-Review 记录

- **覆盖核对**：基线矩阵 5 个触发点 × 3 档开关均有实现位（Task 4 各 trigger 方法）；确认框闭环（回调→状态→确认→applyCloudProgress→setProgress）齐全；跳章抑制两路（jumpToPosition / TocEngine）齐全；`justInitData` 对应（ReaderSyncGate）；DEBUG gate 两处差异（Paused 有 / Timer 无）与宿主一致。
- **占位符扫描**：无 TBD/TODO；Task 4 的 import 校对注记给出确定正确值与兜底路径，非悬空占位。
- **类型一致性**：`ReaderSyncTrigger` 枚举值在 Task 1/2/4 使用一致；`ReaderCloudProgress(chapterIndex, chapterPos)` 构造一致；`ReaderProgressSyncer` 方法名（sync/applyCloudProgress/markChapterJumped/resetChapterJumped/onCloudProgressNewer）在 Task 4 内自洽。
