# 墨水屏阅读网络恢复自愈设计

> 状态：设计定稿（2026-11-15），实施批次一为 eink 模式；完整模式同构问题留待后续批次。
> 范围：墨水屏模式阅读界面（`EInkMainActivity` + eink-lib `feature/reader` + 宿主 `eink/bridge`）。

## 1. 问题

墨水屏设备灭屏后系统通常断开网络。回到前台后存在一个「网络重连窗口」，造成两类体验断裂：

1. **进度同步弹窗与正文可用性脱钩**：进书进度同步（`BookEntered`）与章节内容加载是两条独立链路，
   正文下载失败时错误文案（如「获取正文失败\n…」）会被当作正文走完装载回调，进度同步照样发出；
   WebDAV 与书源连接状况可以不一致，于是出现「恢复进度」弹窗在场、正文却是错误页。
2. **网络恢复后无补偿**：网络恢复回调目前只驱动进度同步
   （`ReaderViewModel.registerNetworkWatcher` → `syncCloudProgress(NetworkAvailable)`），
   失败正文只能靠用户手动刷新；点「恢复」跳转后停在无内容页是典型路径。

附带浪费：无网络时进书，WebDAV 拉取失败被当成「云端无进度」（`CloudMissing`），
还会顺路尝试上传同样失败——每次进书白跑两个网络请求。

## 2. 现状机制锚点

| 事实 | 位置 |
|---|---|
| eink 错误态载体 `ReaderUiState.error`，由 `engineMessage`（= `ReadBook.msg`）或进书前置失败设置 | `eink-lib/.../feature/reader/ReaderViewModel.kt` `onContentUpdated` |
| 网络下载失败文案被当正文排版成页（**不是** error 态），同时 `downloadFailChapters[index]++` | `app/.../model/CacheBookModel.kt` `download().onError` |
| `downloadFailChapters` 换书清空、下载成功移除——干净的「最近一次下载尝试失败且未成功」结构化信号 | `app/.../model/ReadBook.kt:209` |
| 现有自动重试仅排版类错误单发一次（2.5s 退避、同书同章一次），只匹配「加载正文出错」前缀 | `eink-lib/.../feature/reader/ContentRetryPolicy.kt` |
| 网络监听 `onAvailable`（不校验 VALIDATED），仅触发进度同步，受 `ReaderSyncGate` 初始装载窗口抑制 | `ReaderViewModel.kt` `registerNetworkWatcher` |
| `NetworkUtils.isAvailable()` 只查 transport capability，不查 `NET_CAPABILITY_VALIDATED` | `app/.../utils/NetworkUtils.kt:32` |
| 进书同步无网络前置检查 | `app/.../eink/bridge/ReaderProgressSyncer.kt` `syncOnEntered` |

## 3. 方案选择

- **方案 A（采纳）：网络恢复事件驱动的阅读自愈**。把网络恢复从只驱动进度同步升级为
  「进度同步 + 内容自愈」，外加进书同步无网短路。改动集中在 eink VM、bridge 与端口，
  复用现有 `refreshChapter()` / `attach()` 链路。
- 方案 B（未采纳，本批）：`ReadBook` 回调契约新增 `onChapterLoadFailed` 结构化事件。
  信号最准、完整模式可复用，但动核心回调契约、影响两套渲染管线，违背 eink 优先最小切片。
  完整模式批次可重新评估。
- 方案 C（否决）：只修弹窗时序、不做自动重载——不解决「跳转后无内容要手动刷」核心痛点。

## 4. 设计

### 4.1 网络恢复的可靠判定

`registerNetworkWatcher` 升级双路触发：

- 保留 `onAvailable`（兼容不上报 VALIDATED 的网络环境，行为不差于现状）；
- 新增 `onCapabilitiesChanged` 中 `NET_CAPABILITY_VALIDATED` **边沿**触发
  （未验证→已验证才动作），覆盖「Wi-Fi 重连窗口内 capability 已上报但实际不通」的场景；
- `onLost` 重置边沿状态。

不做事件层合并窗口（固定窗口会把晚到的 VALIDATED 补试挡掉）：单次连接周期内
`onAvailable` 与 VALIDATED 边沿至多各触发一次，双触发防重由动作层承担——
进度同步本身幂等（拉取+比较，与现状网络抖动下的重复触发同语义），
自愈动作防重见 4.3。

### 4.2 自愈判定（纯函数）

新增 `ReaderRecoveryPolicy`（eink 模块，纯函数、可单测），输入
`(error: String?, currentChapterDownloadFailed: Boolean, isLocalBook: Boolean)`，
输出 `None / RefreshChapter / Reattach`：

| 当前状态 | 动作 | 场景 |
|---|---|---|
| `error` 以「详情页出错：」或「目录加载失败：」开头，且非本地书 | Reattach（重新 `attach`，同 ErrorView 重试按钮路径） | 灭屏断网期间点开书，目录/详情拉不下来 |
| `error` 以「加载正文出错」开头（现有 2.5s 单发重试已失败） | RefreshChapter | 协程级网络异常 |
| `currentChapterDownloadFailed == true` | RefreshChapter | 「获取正文失败」错误文案页（网络书下载失败）——跳转/翻章后 |
| 书籍不存在 / 没有书源 / 章节不存在 / 排版失败 / 本地书无内容 / 本地书目录失败 | None | 结构性错误，自动重试无意义 |

优先级 Reattach > RefreshChapter > None。「无内容」文案页属本地书（不走
`downloadFailChapters` 计数），归 None；网络书无源文案是「没有书源」，同为结构性 None。

### 4.3 自愈动作与防抖

- `RefreshChapter` → `refreshChapter()`（清页、清错误、进加载态，`refreshCurrentChapter` 原位换回）；
  `Reattach` → `attach(bookUrl)`（`attachJob` 活跃时天然幂等）。
- 防抖：网络恢复事件去抖（4.1）之外，同一自愈目标（`bookUrl to chapterIndex`；Reattach 为
  `bookUrl`）在最小间隔 15s 内不重复；失败后**不设总次数上限**，等下一次网络恢复事件
  ——网络真正稳定后一次即成功，Wi-Fi 抖动时 15s 间隔约束墨水屏刷新成本。
- 防抖拒绝不丢弃：晚到的 VALIDATED 边沿补试改为窗口到期执行一次（最早在途的补试
  生效，后续拒绝不叠加）——消解「onAvailable 先到、失败占住窗口，VALIDATED 数秒后
  被 15s 防抖吞掉」这一重连常态路径（最终审查 Important 修订，2026-11-15）。
- 恢复动作统一经 viewModelScope 派发主线程执行，网络回调线程只做判定：
  attach 前置块会改 attachJob/autoPlayJob，回调线程直调存在竞态。
- 与现有 `scheduleContentAutoRetry`（`autoRetriedFailure` 名额）两套独立，互不消耗。
- 自愈判定独立于 `ReaderSyncGate`（gate 只约束进度同步的触发语义——进书武装与
  初始装载窗口；自愈由失败态自身门控，装载窗口内通常无失败态，天然不触发）。

### 4.4 端口与桥接

`ReaderEngine` 端口新增只读属性：

```kotlin
val currentChapterDownloadFailed: Boolean get() = false  // 默认实现，旧宿主诚实降级为不自愈
```

`ReaderEngineImpl` 实现：
`ReadBook.downloadFailChapters.containsKey(ReadBook.durChapterIndex)`。

查询式只读信号，不改回调契约；换书/下载成功的清理复用宿主现有行为，无新增维护点。

### 4.5 进书同步无网短路

`ReaderProgressSyncer.syncOnEntered` 在发起拉取前加 `NetworkUtils.isAvailable()` 前置，
无网记日志跳过。云端进度判定由网络恢复路径补齐（`syncOnNetworkAvailable` 已有 isAvailable
检查）——弹窗照常出现，但只会出现在网络真正可拉到云端的时刻，弹窗逻辑与文案本身不动。

## 5. 本轮不做

- 不改「恢复进度」弹窗触发逻辑与文案（无网短路后弹窗与网络可用性对齐；跳转后失败由自愈闭环）。
- 不动完整模式（宿主 `ReadBookScreen` 路径）同构问题——后续批次，届时可评估方案 B 事件化。
- 不动书架封面、发现页等其他网络失败面；不引入全局网络状态流/WorkManager。

## 6. 测试与验证

- 新增 `ReaderRecoveryPolicyTest`（eink 模块纯函数全分支）；VALIDATED 边沿状态机抽纯函数同测。
- 真机验证路径：
  1. 灭屏断网 → 回前台进书 → 错误页 → 网络恢复 → 自动重载出正文；
  2. 进书弹「恢复进度」→ 恢复跳转到未缓存章 → 下载失败页 → 网络恢复 → 自动重载；
  3. 灭屏断网期间点开书 → 目录加载失败页 → 网络恢复 → 自动重新进书；
  4. 飞行模式开关循环（Wi-Fi 抖动）→ 错误页不被连环刷新（15s 间隔生效）；
  5. `adb logcat -s EInkProgressSync` 观察同步链路日志。

## 7. 交付物

eink-lib 子模块（`eink-lib/modules/eink`）：

- `feature/reader/ReaderViewModel.kt`：networkWatcher 双路触发 + 自愈编排 + 防抖；
- `feature/reader/ReaderRecoveryPolicy.kt`（新增）；
- `src/test/.../ReaderRecoveryPolicyTest.kt`（新增）。

宿主 `app`：

- `eink/contract/ReaderEngine.kt`：`+currentChapterDownloadFailed`（默认 false）；
- `eink/bridge/ReaderEngineImpl.kt`：实现；
- `eink/bridge/ReaderProgressSyncer.kt`：`syncOnEntered` 无网短路。

推送顺序：先子模块（`eink/lib` 分支）后宿主抬指针。
