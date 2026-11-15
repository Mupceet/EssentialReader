# 墨水屏阅读网络恢复自愈 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 墨水屏模式阅读界面在网络恢复时自动重载失败正文/重新进书，并消除无网进书的进度同步白跑（规格见 `docs/dev/eink-reader-network-recovery.md`）。

**Architecture:** eink-lib 模块新增两个纯函数（自愈判定 `ReaderRecoveryPolicy`、VALIDATED 边沿状态机 `NetworkValidationEdge`）+ `ReaderViewModel` 网络回调双路触发与自愈编排；宿主 `ReaderEngine` 端口暴露 `currentChapterDownloadFailed` 结构化失败信号，`ReaderProgressSyncer` 进书同步加无网短路。

**Tech Stack:** Kotlin、Compose AndroidViewModel、`ConnectivityManager.registerDefaultNetworkCallback`、JUnit4。

## Global Constraints

- 本任务横跨**两个 git 仓库**：eink 模块源码在子模块仓库 `eink-lib/`（分支 `eink/lib`），宿主源码在仓库根。提交命令必须 `cd` 到正确仓库，**只 `git add` 计划列出的文件**——宿主工作区有用户未提交的书源管理改动，严禁 `git add -A`/`git add .`。
- 构建用 JDK 21；命令在仓库根执行（GitBash）：`./gradlew.bat <task>`。Kotlin daemon OOM 时加 `-Pkotlin.daemon.jvmargs=-Xmx4g` 重试。
- 错误文案前缀锚定**全角冒号**（VM 产出「详情页出错：」「目录加载失败：」「排版失败：」均为全角）。
- 不重构无关代码；不动「恢复进度」弹窗；不动完整模式（宿主 `ReadBookScreen` 路径）。
- 完成后**不自动推送**：推送顺序先 `eink-lib`（`eink/lib` 分支）后宿主抬指针，由用户决定时机。

---

### Task 1: ReaderRecoveryPolicy 自愈判定纯函数（eink-lib）

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderRecoveryPolicy.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderRecoveryPolicyTest.kt`

**Interfaces:**
- Consumes: `ContentRetryPolicy.shouldRetry(errorMessage: String): Boolean`（既有，同包）
- Produces: `enum ReaderRecoveryAction { None, RefreshChapter, Reattach }`；`ReaderRecoveryPolicy.evaluate(error: String?, currentChapterDownloadFailed: Boolean, isLocalBook: Boolean): ReaderRecoveryAction`（Task 4 使用）

- [ ] **Step 1: 写失败测试**

创建 `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderRecoveryPolicyTest.kt`：

```kotlin
package io.legado.app.eink.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络恢复自愈判定锚定：只自动恢复与网络相关的失败（下载失败页、
 * 协程级网络异常、网络书进书前置失败）；结构性错误不自动重试。
 * 前缀锚定 VM 全角冒号文案（「目录加载失败：」「详情页出错：」）。
 */
class ReaderRecoveryPolicyTest {

    @Test
    fun `正常态不动作与下载失败计数触发重载`() {
        assertEquals(
            ReaderRecoveryAction.None,
            ReaderRecoveryPolicy.evaluate(
                error = null, currentChapterDownloadFailed = false, isLocalBook = false
            )
        )
        // 下载失败时错误文案被当正文排版成页，error 为 null——凭结构化信号判定
        assertEquals(
            ReaderRecoveryAction.RefreshChapter,
            ReaderRecoveryPolicy.evaluate(
                error = null, currentChapterDownloadFailed = true, isLocalBook = false
            )
        )
    }

    @Test
    fun `进书前置失败仅网络书重新进书`() {
        assertEquals(
            ReaderRecoveryAction.Reattach,
            ReaderRecoveryPolicy.evaluate(
                error = "详情页出错：java.net.SocketTimeoutException",
                currentChapterDownloadFailed = false, isLocalBook = false
            )
        )
        assertEquals(
            ReaderRecoveryAction.Reattach,
            ReaderRecoveryPolicy.evaluate(
                error = "目录加载失败：connect timed out",
                currentChapterDownloadFailed = false, isLocalBook = false
            )
        )
        // 本地书目录/详情失败与网络无关
        assertEquals(
            ReaderRecoveryAction.None,
            ReaderRecoveryPolicy.evaluate(
                error = "目录加载失败：EPUB 解析失败",
                currentChapterDownloadFailed = false, isLocalBook = true
            )
        )
    }

    @Test
    fun `加载正文出错可重载`() {
        assertEquals(
            ReaderRecoveryAction.RefreshChapter,
            ReaderRecoveryPolicy.evaluate(
                error = "加载正文出错\njava.net.UnknownHostException",
                currentChapterDownloadFailed = false, isLocalBook = false
            )
        )
    }

    @Test
    fun `结构性错误不动作`() {
        for (error in listOf("书籍不存在", "没有书源", "章节不存在", "排版失败：measure 溢出")) {
            assertEquals(
                ReaderRecoveryAction.None,
                ReaderRecoveryPolicy.evaluate(
                    error = error, currentChapterDownloadFailed = false, isLocalBook = false
                )
            )
        }
    }

    @Test
    fun `Reattach 优先于 RefreshChapter`() {
        assertEquals(
            ReaderRecoveryAction.Reattach,
            ReaderRecoveryPolicy.evaluate(
                error = "目录加载失败：connect timed out",
                currentChapterDownloadFailed = true, isLocalBook = false
            )
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderRecoveryPolicyTest"
```

Expected: 编译失败 `unresolved reference: ReaderRecoveryPolicy`。

- [ ] **Step 3: 最小实现**

创建 `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderRecoveryPolicy.kt`：

```kotlin
package io.legado.app.eink.feature.reader

/**
 * 网络恢复自愈判定（纯函数）。
 *
 * 墨水屏设备灭屏断网后回前台存在网络重连窗口：失败正文/进书前置
 * 停在错误态无人补偿，用户需手动刷新。网络恢复事件触发时以本判定
 * 决定补偿动作；结构性错误（书籍不存在/没有书源/章节不存在/排版
 * 失败/本地书无内容）重试无意义，不自动恢复。
 *
 * 「获取正文失败」类下载失败文案会被宿主当正文排版成页（非 error
 * 态），凭 [currentChapterDownloadFailed] 结构化信号识别；进书前置
 * 失败（详情页出错/目录加载失败）仅网络书与网络相关，本地书归 None。
 */
internal enum class ReaderRecoveryAction {
    /** 无动作（正常态/结构性错误）。 */
    None,

    /** 重载当前章（[ReaderViewModel.refreshChapter] 同款路径）。 */
    RefreshChapter,

    /** 重新进书（ErrorView 重试按钮同款 [ReaderViewModel.attach] 路径）。 */
    Reattach,
}

internal object ReaderRecoveryPolicy {

    /** 进书前置失败前缀（VM 全角冒号文案，见 attach 的 prepareBookData 分支）。 */
    private val REATTACH_PREFIXES = listOf("详情页出错：", "目录加载失败：")

    fun evaluate(
        error: String?,
        currentChapterDownloadFailed: Boolean,
        isLocalBook: Boolean,
    ): ReaderRecoveryAction = when {
        error != null && !isLocalBook &&
            REATTACH_PREFIXES.any { error.startsWith(it) } -> ReaderRecoveryAction.Reattach

        error != null && ContentRetryPolicy.shouldRetry(error) -> ReaderRecoveryAction.RefreshChapter

        currentChapterDownloadFailed -> ReaderRecoveryAction.RefreshChapter

        else -> ReaderRecoveryAction.None
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.ReaderRecoveryPolicyTest"
```

Expected: `BUILD SUCCESSFUL`，5 个测试全绿。

- [ ] **Step 5: 提交（eink-lib 仓库）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules/eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderRecoveryPolicy.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/reader/ReaderRecoveryPolicyTest.kt
git commit -m "feat(eink-reader): 网络恢复自愈判定纯函数 ReaderRecoveryPolicy"
```

---

### Task 2: NetworkValidationEdge 边沿状态机（eink-lib）

**Files:**
- Create: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/NetworkValidationEdge.kt`
- Test: `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/NetworkValidationEdgeTest.kt`

**Interfaces:**
- Produces: `NetworkValidationEdge.onCapabilities(isValidated: Boolean): Boolean`（边沿即 true）、`onLost()`（Task 4 使用）

- [ ] **Step 1: 写失败测试**

创建 `eink-lib/modules/eink/src/test/java/io/legado/app/eink/feature/reader/NetworkValidationEdgeTest.kt`：

```kotlin
package io.legado.app.eink.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NET_CAPABILITY_VALIDATED 边沿状态机锚定：单次连接周期内
 * 未验证→已验证只放行一次；onLost 重置后允许下一次连接周期的边沿。
 */
class NetworkValidationEdgeTest {

    @Test
    fun `未验证到已验证触发一次`() {
        val edge = NetworkValidationEdge()
        assertTrue(edge.onCapabilities(true))
        assertFalse(edge.onCapabilities(true)) // 已验证态重复上报不再触发
    }

    @Test
    fun `保持未验证不触发`() {
        val edge = NetworkValidationEdge()
        assertFalse(edge.onCapabilities(false))
        assertFalse(edge.onCapabilities(false))
    }

    @Test
    fun `掉回未验证后再验证再次触发`() {
        val edge = NetworkValidationEdge()
        assertTrue(edge.onCapabilities(true))
        assertFalse(edge.onCapabilities(false)) // 掉回未验证：不触发，只降状态
        assertTrue(edge.onCapabilities(true))
    }

    @Test
    fun `onLost 重置后重新验证触发`() {
        val edge = NetworkValidationEdge()
        assertTrue(edge.onCapabilities(true))
        edge.onLost()
        assertTrue(edge.onCapabilities(true))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.NetworkValidationEdgeTest"
```

Expected: 编译失败 `unresolved reference: NetworkValidationEdge`。

- [ ] **Step 3: 最小实现**

创建 `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/NetworkValidationEdge.kt`：

```kotlin
package io.legado.app.eink.feature.reader

/**
 * NET_CAPABILITY_VALIDATED 边沿状态机（纯状态类，可单测）。
 *
 * 墨水屏 Wi-Fi 重连存在「capability 已上报但探测未过」的窗口，
 * onAvailable 早于实际可通；本类把 onCapabilitiesChanged 的
 * VALIDATED 上报收敛为未验证→已验证的单次边沿，作为网络真正恢复
 * 的补试时机。onLost 重置——不重置会吞掉下一次连接周期的边沿。
 *
 * 初始即已验证（重注册 callback 的首次上报）算一次边沿：触发动作
 * 幂等（进度同步为拉取+比较，自愈有目标防抖），可接受。
 */
internal class NetworkValidationEdge {

    private var validated = false

    /** 处理一次 VALIDATED 上报；返回 true 表示发生未验证→已验证边沿。 */
    fun onCapabilities(isValidated: Boolean): Boolean {
        val edge = isValidated && !validated
        validated = isValidated
        return edge
    }

    /** 网络丢失：重置边沿状态，允许下一次连接周期重新触发。 */
    fun onLost() {
        validated = false
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew.bat :modules:eink:testDebugUnitTest --tests "io.legado.app.eink.feature.reader.NetworkValidationEdgeTest"
```

Expected: `BUILD SUCCESSFUL`，4 个测试全绿。

- [ ] **Step 5: 提交（eink-lib 仓库）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules/eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/NetworkValidationEdge.kt \
        modules/eink/src/test/java/io/legado/app/eink/feature/reader/NetworkValidationEdgeTest.kt
git commit -m "feat(eink-reader): NET_CAPABILITY_VALIDATED 边沿状态机"
```

---

### Task 3: 端口暴露下载失败信号 + 宿主实现（跨两仓库）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt`（`hasLaidOutPages` 属性之后插入）
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt`（「会话只读状态」区 `hasLaidOutPages` 实现之后插入）

**Interfaces:**
- Produces: `ReaderEngine.currentChapterDownloadFailed: Boolean`（默认 false；Task 4 使用）

- [ ] **Step 1: 端口加默认属性（eink-lib 仓库）**

在 `ReaderEngine.kt` 的 `hasLaidOutPages`（约 L311）之后插入：

```kotlin
    /**
     * 当前章最近一次正文下载尝试失败且尚未成功（宿主 downloadFailChapters
     * 信号）。「获取正文失败」类下载失败文案会被当正文排版成页（非
     * error 态），模块侧凭本信号识别失败页。默认 false：旧宿主无此
     * 信号，诚实降级为网络恢复不自愈。
     */
    val currentChapterDownloadFailed: Boolean get() = false
```

- [ ] **Step 2: 宿主实现（宿主仓库）**

在 `ReaderEngineImpl.kt` 的 `hasLaidOutPages` 实现之后插入：

```kotlin
    override val currentChapterDownloadFailed: Boolean
        get() = ReadBook.downloadFailChapters.containsKey(ReadBook.durChapterIndex)
```

- [ ] **Step 3: 编译验证**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
./gradlew.bat :app:compileAppCoreDebugKotlin
```

Expected: `BUILD SUCCESSFUL`（该任务连带编译 `:modules:eink`）。

- [ ] **Step 4: 提交（先 eink-lib 后宿主，两个仓库各一笔）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules/eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/contract/ReaderEngine.kt
git commit -m "feat(eink-contract): ReaderEngine 暴露当前章下载失败信号（默认 false 旧宿主降级）"

cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
git add app/src/main/java/io/legado/app/eink/bridge/ReaderEngineImpl.kt
git commit -m "feat(eink): bridge 实现当前章下载失败信号（downloadFailChapters）"
```

---

### Task 4: ReaderViewModel 双路触发与自愈编排（eink-lib）

**Files:**
- Modify: `eink-lib/modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt`

**Interfaces:**
- Consumes: `ReaderRecoveryPolicy.evaluate`（Task 1）、`NetworkValidationEdge`（Task 2）、`ReaderEngine.currentChapterDownloadFailed`（Task 3）、既有 `refreshChapter()`/`attach(bookUrl)`/`syncGate.allowNetworkSync()`

- [ ] **Step 1: 加 import 与字段**

`ReaderViewModel.kt` import 区加（`android.net.Network` 已有）：

```kotlin
import android.net.NetworkCapabilities
```

字段区（`private var networkWatcher: ConnectivityManager.NetworkCallback? = null` 之后）加：

```kotlin
    /** VALIDATED 边沿状态机（onCapabilitiesChanged 收敛为单次边沿）。 */
    private val networkValidationEdge = NetworkValidationEdge()

    /** 网络自愈防抖：目标键（bookUrl to chapterIndex，Reattach 用 -1）。 */
    private var lastRecoveryKey: Pair<String, Int>? = null
    private var lastRecoveryAt = 0L
```

- [ ] **Step 2: 改造 registerNetworkWatcher 并新增编排函数**

将 `registerNetworkWatcher()` 中 `val watcher = object : ConnectivityManager.NetworkCallback() { ... }` 整体替换为：

```kotlin
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkRecovered()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                // Wi-Fi 重连窗口内 onAvailable 早于实际可通（capability 已
                // 上报但探测未过）——VALIDATED 边沿是「真正能上网」的补试
                // 时机；单次连接周期至多各触发一次，防重由动作层承担
                if (networkValidationEdge.onCapabilities(
                        networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        )
                    )
                ) {
                    onNetworkRecovered()
                }
            }

            override fun onLost(network: Network) {
                networkValidationEdge.onLost()
            }
        }
```

在 `unregisterNetworkWatcher()` 之后新增：

```kotlin
    /**
     * 网络恢复事件（onAvailable / VALIDATED 边沿双路）：进度同步沿用
     * 既有 gate；内容自愈按失败态判定动作（[ReaderRecoveryPolicy]），
     * 双路触发的防重——同步本身幂等（拉取+比较），自愈受
     * [RECOVERY_MIN_INTERVAL_MS] 同目标防抖约束。
     */
    private fun onNetworkRecovered() {
        if (syncGate.allowNetworkSync()) {
            engine.syncCloudProgress(ReaderSyncTrigger.NetworkAvailable)
        }
        attemptNetworkRecovery()
    }

    /**
     * 网络恢复内容自愈：仅失败态动作（判定独立于 [ReaderSyncGate]——
     * gate 只约束进度同步语义，自愈由失败态自身门控）；同一目标
     * 15s 内不重复，失败后等下一次网络恢复事件，不设总次数上限。
     */
    private fun attemptNetworkRecovery() {
        val state = _uiState.value
        val action = ReaderRecoveryPolicy.evaluate(
            error = state.error,
            currentChapterDownloadFailed = engine.currentChapterDownloadFailed,
            isLocalBook = state.isLocalBook,
        )
        if (action == ReaderRecoveryAction.None) return
        val bookUrl = loadedBookUrl ?: state.bookUrl
        if (bookUrl.isEmpty()) return
        val chapterIndex =
            if (action == ReaderRecoveryAction.Reattach) -1 else engine.currentChapterIndex
        val key = bookUrl to chapterIndex
        val now = SystemClock.elapsedRealtime()
        if (key == lastRecoveryKey && now - lastRecoveryAt < RECOVERY_MIN_INTERVAL_MS) return
        lastRecoveryKey = key
        lastRecoveryAt = now
        when (action) {
            ReaderRecoveryAction.RefreshChapter -> refreshChapter()
            ReaderRecoveryAction.Reattach -> attach(bookUrl)
            ReaderRecoveryAction.None -> Unit
        }
    }
```

文件底部常量区（`PROGRESS_BACKUP_INTERVAL_MS` 附近）加：

```kotlin
/** 网络自愈同目标最小间隔（毫秒）：Wi-Fi 抖动时约束墨水屏刷新成本。 */
internal const val RECOVERY_MIN_INTERVAL_MS = 15_000L
```

- [ ] **Step 3: 编译 + 模块全量测试**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
./gradlew.bat :modules:eink:compileDebugKotlin :modules:eink:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，既有 reader 测试全绿。

- [ ] **Step 4: 提交（eink-lib 仓库）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules/eink-lib
git add modules/eink/src/main/java/io/legado/app/eink/feature/reader/ReaderViewModel.kt
git commit -m "feat(eink-reader): 网络恢复双路触发与失败态自愈编排（15s 同目标防抖）"
```

---

### Task 5: 进书同步无网短路（宿主）

**Files:**
- Modify: `app/src/main/java/io/legado/app/eink/bridge/ReaderProgressSyncer.kt`（`syncOnEntered()` 内）

**Interfaces:**
- Consumes: 既有 `NetworkUtils.isAvailable()`（文件已 import）

- [ ] **Step 1: 加无网短路**

在 `syncOnEntered()` 的 `if (!ReadBook.inBookshelf) { ... return }` 块之后、`val book = ReadBook.book ?: run { ... }` 之前插入：

```kotlin
        if (!NetworkUtils.isAvailable()) {
            log("进书同步：网络不可用，跳过（待网络恢复补拉）")
            return
        }
```

位置要点：必须在函数开头 `consumeChapterJumped()` **之后**——跳章抑制标记是一次性消费语义，无网短路若发生在消费前会把标记残留到下一次进书（`ReaderProgressSyncer` 是引擎单例，跨 ViewModel 存活）。

- [ ] **Step 2: 编译验证**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
./gradlew.bat :app:compileAppCoreDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交（宿主仓库）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
git add app/src/main/java/io/legado/app/eink/bridge/ReaderProgressSyncer.kt
git commit -m "feat(eink): 进书进度同步无网短路，待网络恢复路径补拉"
```

说明：本函数依赖静态 `NetworkUtils` 与网关，不单测；短路行为经 logcat（tag `EInkProgressSync`）真机验证（Task 6 清单第 3 项顺带覆盖）。

---

### Task 6: 全量验证与文档提交（宿主）

**Files:**
- Commit: `docs/dev/eink-reader-network-recovery.md`、`docs/superpowers/plans/2026-11-15-eink-reader-network-recovery.md`

- [ ] **Step 1: 模块测试全量**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
./gradlew.bat :modules:eink:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 宿主主验证集（AGENTS.md 当前口径）**

```bash
./gradlew.bat testAppCoreDebugUnitTest lintAppCoreDebug verifyConfigArchitecture --continue --no-configuration-cache
```

Expected: 全部通过。

- [ ] **Step 3: 文本改动检查（两仓库）**

```bash
git diff --check
git -C eink-lib diff --check
```

Expected: 无输出。

- [ ] **Step 4: 提交文档（宿主仓库）**

```bash
cd /d/Projects/AndroidProjects/EssentialReader-eink-port-md3-modules
git add docs/dev/eink-reader-network-recovery.md docs/superpowers/plans/2026-11-15-eink-reader-network-recovery.md
git commit -m "docs(eink): 墨水屏阅读网络恢复自愈设计与实施计划"
```

- [ ] **Step 5: 真机验证清单（装机后人工执行，结果回填交付说明）**

1. 灭屏断网 → 回前台进书 → 错误页 → 网络恢复 → 自动重载出正文；
2. 进书弹「恢复进度」→ 恢复跳转到未缓存章 → 下载失败页 → 网络恢复 → 自动重载；
3. 灭屏断网期间点开书 → 目录加载失败页 → 网络恢复 → 自动重新进书（`adb logcat -s EInkProgressSync` 同时可见「网络不可用，跳过」→「网络恢复同步」）；
4. 飞行模式快速开关循环（Wi-Fi 抖动）→ 错误页不被连环刷新（15s 间隔生效）；
5. 结构性错误（无书源书）网络恢复后**不**自动重试。

---

## 推送提醒（不自动执行）

先推 `eink-lib`（`eink/lib` 分支，4 笔提交），后推宿主并确认子模块指针（3 笔提交 + docs）。顺序反了 CI 检出会挂（not our ref）。
