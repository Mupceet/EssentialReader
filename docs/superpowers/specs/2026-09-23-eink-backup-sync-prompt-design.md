# eink 启动「发现云端新备份」提示设计

- 日期：2026-09-23
- 分支：eink/port/md3/modules
- 状态：设计定稿（其中「恢复反馈形态」等决策为自主推进拍板，见 §1 标注，待用户复核）
- 关联：`docs/superpowers/plans/2026-09-12-eink-progress-sync-migration.md`（进度同步切片把「eink『我的』设置镜像」列为后续设计阶段入口，本设计承接其中的启动检查项）

## 0. 背景与问题

设备间同步有两条独立通道（2026-09-23 调查结论）：

1. **单书阅读进度**（WebDAV `bookProgress/*.json`）：两种模式均已自动同步（eink 侧 `ReaderProgressSyncer`，2026-09-12 切片）。
2. **完整备份 zip**（WebDAV `backup<日期>-<设备名>.zip`，含书源/书架/阅读记录/替换规则/TTS/书签等）：**上行**两模式都有（阅读暂停路径的 `Backup.autoBack`）；**下行**只有完整模式有入口（启动 `MainActivity.backupSync` 提示、设置页手动恢复、Onboarding 恢复），墨水屏模式一个都没有——纯 eink 使用的设备永远拉不下云端书源与阅读记录。

本切片补齐下行三入口中的第一个：**eink 启动时「发现云端新备份」提示**。手动备份/恢复入口与 WebDAV 配置镜像仍属后续切片。

## 1. 决策记录

| 议题 | 决策 | 依据 |
|---|---|---|
| 架构形态 | 新可选端口 `BackupSyncEngine`（宿主裁决 + 模块渲染） | `AppUpdateEngine` 先例：设置开关、比较与标记语义是宿主独占知识；companion 宿主无备份机制时不注册端口 → 零行为诚实降级 |
| 触发时机 | `EInkApp` 根层启动 `LaunchedEffect(Unit)`，每次 Activity 组合检查一次；**不设进程级闸** | 对齐宿主 `MainActivity.backupSync`（onCreate 每次查，无进程闸）；`lastBackup` 标记天然防重复弹；listFiles 无更新检查的限流顾虑 |
| 开关 | 只认宿主「自动检测新备份」`autoCheckNewBackup`（默认开） | 完整模式与 eink 共用同一键；eink 设置镜像不在本切片 |
| 比较与标记语义 | `latest.lastModify - LocalConfig.lastBackup > 1 分钟` 即「新」；**发现即写标记**（返回快照前） | 逐字对齐 `MainActivity.kt:666-667`：用户取消后同一备份不再弹；恢复失败靠对话框内「重试」兜底 |
| 恢复反馈 | 忙态 + 结果提示：确认后对话框转「恢复中…」禁用态，成功 toast「恢复完成」，失败 toast「恢复失败：原因」 | 对齐宿主**手动恢复**路径（`BackupConfigViewModel`：Loading + 成功/失败提示）；宿主启动路径 fire-and-forget 无反馈，但 eink 屏恢复 zip（下载+解压+写库）可达十几秒，无反馈易误判重复操作。【自主拍板，待复核】 |
| 失败重试 | 失败后对话框保持、内容附失败原因、确认钮变「重试」 | 因「发现即标记」，失败后重启才有第二次机会太苛刻；网络抖动常见 |
| 恢复中取消 | 「恢复中…」时确认钮禁用、取消仍可收起对话框；恢复协程不中断，结果照常 toast | 更新弹层同款先例（「收起走取消，宿主下载不中断」） |
| 提示信息量 | 从备份文件名解析设备名与日期一并展示 | 文件名格式 `backupyyyy-MM-dd-<设备名>.zip`；多设备用户需要判断「谁的备份」 |
| 阅读中边界 | 提示弹层渲染在根层、可覆盖阅读页（含冷启动直达阅读场景）；恢复只写 DB，不强制重载当前阅读会话 | 宿主同形（Activity 级 alert 可在阅读路由之上）；书架 `observeShelf()` 为响应式 Flow，恢复后自动重发 |
| DEBUG gate | 无 | 对齐宿主 `backupSync`（无 DEBUG gate） |
| 检查静默口径 | 未配置 WebDav / 网络失败 / 无新备份 → 静默无提示 | 对齐宿主 `catch → return`；自动路径不打扰 |

## 2. 契约：BackupSyncEngine（可选端口）

新增 `modules/eink/src/main/java/io/legado/app/eink/contract/BackupSyncEngine.kt`（对齐 `AppUpdateEngine` 的可选端口模式，README §5 能力裁剪语义）：

```kotlin
interface BackupSyncEngine {
    suspend fun checkNewBackupOnStart(): CloudBackupNewer?
    suspend fun restoreBackup(fileName: String)
}

data class CloudBackupNewer(
    val fileName: String,   // restore 关联键（宿主 WebDAV 文件名）
    val deviceName: String, // 展示用；未命名设备为空串
    val dateText: String,   // 展示用（yyyy-MM-dd）
)
```

- `checkNewBackupOnStart`：**全部判定在实现侧完成**——`autoCheckNewBackup` 开关、`getLatestBackup` 拉取、`LocalConfig.lastBackup` 比较、发现即标记。返回 null = 无新备份/未配置/失败（一律静默）；返回非 null = 已标记、应提示用户。
- `restoreBackup(fileName)`：下载+解压+恢复入 DB（宿主 `WebDavBackupUseCase.restore`，内部 `BackupRestoreLock` 串行）。失败抛异常，message 面向用户，模块直接展示。
- 模块侧零宿主类型渗透：快照全基元。

注册位：`EInkEngineRegistry.install(...)` 追加 `backupSyncEngine: BackupSyncEngine? = null`（可选参数默认 null，源兼容）；宿主 `EInkBridge.install()` 注册实现。模块版本 0.8.0 起（新能力端口，minor 递增；旧宿主不注册即无行为）。

## 3. 宿主实现：BackupSyncEngineImpl + BackupSyncCheckPolicy

`app/eink/bridge/BackupSyncEngineImpl.kt`（object，对齐 `AppUpdateEngineImpl` 形态）：

- 判定核心抽纯函数 `BackupSyncCheckPolicy`（对齐 `ReaderProgressSyncPolicy` 先例，可 JVM 单测）：
  - `shouldPrompt(autoCheckEnabled: Boolean, latest: WebDavBackup?, lastBackupMark: Long): WebDavBackup?`——开关关/无备份/时间差 ≤ 1 分钟 → null；超前 → 原样返回。
  - `parseSnapshot(backup: WebDavBackup): CloudBackupNewer`——文件名解析设备名/日期（`backupyyyy-MM-dd-device.zip`，无设备段时 deviceName 空串、日期段不合法时回退原文）。
- 实现：`WebDavBackupUseCase.getLatestBackup()`（runCatching → null 静默）→ Policy 判定 → 命中即 `LocalConfig.lastBackup = latest.lastModify` → 返回快照；`restoreBackup` 直接转发 `WebDavBackupUseCase.restore(fileName)`。

## 4. 模块侧：EInkBackupSyncViewModel + 根层弹层

`modules/eink/.../app/EInkBackupSyncViewModel.kt`（Activity 级 VM，对齐 `EInkAppUpdateViewModel`：`viewModel()` 求值于条目 store owner 覆盖之外，`mutableStateOf` 状态机，`viewModelScope` 发起）：

```kotlin
sealed interface BackupSyncPromptState {
    data object Idle                                    // 静默（初始/无新备份/检查失败）
    data class Newer(val info: CloudBackupNewer) : BackupSyncPromptState          // → 对话框
    data class Restoring(val info: CloudBackupNewer) : BackupSyncPromptState      // 确认已点：忙态
    data class Failed(val info: CloudBackupNewer, val reason: String) : BackupSyncPromptState // 可重试
}
```

- `checkOnStart(engine)`：`engine.checkNewBackupOnStart()` → null 置 Idle，非 null 置 Newer；异常静默置 Idle。
- `confirmRestore(engine)`：置 Restoring，调 `restoreBackup`；成功置 Idle + Toast「恢复完成」；失败置 Failed（对话框保留）。
- `retry` = `confirmRestore` 复用；`dismiss()` 置 Idle（Restoring 中 dismiss 仅收起对话框，协程照跑——更新弹层先例）。**抑制位**：Restoring 中被 dismiss 后置 `suppressed`，恢复协程终态不再回显对话框（成功/失败均只走 toast 置 Idle），防「已取消的对话框失败后复活」。

`EInkApp.kt` 接线（更新弹层旁）：

```kotlin
val backupSyncViewModel: EInkBackupSyncViewModel = viewModel()
LaunchedEffect(Unit) { EInkEngineRegistry.backupSyncEngine?.let(backupSyncViewModel::checkOnStart) }
// 根层：
EInkDialog(
    onDismiss = viewModel::dismiss,
    title = "发现云端新备份",
    confirmText = when (state) { Restoring -> "恢复中…"; Failed -> "重试"; else -> "恢复" },
    onConfirm = if (state is Restoring) null else { viewModel::confirmRestore },
) { EInkText("设备「${deviceName}」于 ${dateText} 的备份比本地新，是否恢复？") }
```

文案对齐宿主「WebDav 书源比本地新,是否恢复」语义；沿用模块现状硬编码中文。

## 5. 已知边界与不做的事

- **阅读会话中恢复**：当前 `ReadBook` 会话是内存对象，恢复只写 DB；继续读旧章、暂停落库/进度同步语义自愈（宿主完整模式同边界，不额外处理）。
- **恢复中途取消**：恢复挂 `viewModelScope`，Activity 销毁（退 eink 模式/系统回收）会取消协程，理论上存在半恢复状态；宿主手动恢复路径（`BackupConfigViewModel` 同挂 viewModelScope）同暴露，不单独加固。
- **两模式并发**：同进程完整模式 `backupSync` 与本检查共享 `LocalConfig.lastBackup` 标记，先发现者标记、后者静默，无双弹。
- 不做：eink「我的」设置镜像（含本开关的 eink 侧开关）、手动备份/恢复列表入口、WebDAV 账号配置入口、恢复进度条（百分比粒度对 eink 屏无意义，忙态足够）。

## 6. 测试策略

- **bridge 纯函数**（`:app` JVM 单测，`BackupSyncCheckPolicyTest`）：开关关 → null；无备份 → null；时间差 ≤1 分钟 → null；超前 → 命中；文件名解析（有/无设备段、异常日期段回退）。
- **模块状态机**（`:modules:eink` JVM 单测，对齐 `ReaderSyncGateTest` 先例）：检查 null/异常 → Idle；命中 → Newer；确认 → Restoring → 成功 Idle / 失败 Failed；Failed 重试复用；dismiss 复位。VM 依赖 engine 参数注入，fake 即可单测。
- **契约注册装配**：扩展既有 `EInkEngineRegistryTest`（`backupSyncEngine` 注册/未注册两态）。
- 主验证集：`testAppDebugUnitTest`、`:modules:eink:testDebugUnitTest`、`compileAppDebugKotlin`、`verifyConfigArchitecture`。
