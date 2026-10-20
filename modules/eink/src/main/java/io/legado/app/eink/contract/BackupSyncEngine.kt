package io.legado.app.eink.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 云端备份端口——**可选**端口之一（其余为 [AppUpdateEngine] /
 * [MarksEngine] / [BookshelfGroupEngine]；可选端口与能力裁剪的完整
 * 语义见本目录 README §5）。
 *
 * ## 职责边界
 *
 * 备份的主体是**宿主应用**：备份通道（WebDAV 配置）、「自动检测新备份」
 * 设置、备份新旧比较、发现即标记、恢复管线（下载+解压+写库，经
 * BackupRestoreLock 串行）全部是宿主独占知识。模块侧原只做两件事：
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
 * 在此之上，宿主可额外开放 [supportsWebDavManagement] 能力位：模块内
 * 直接配置 WebDAV（服务器地址/账号/密码/文件夹/设备名，与完整模式
 * **同一存储**——模块写即共享，备份/进度同步/启动检查全链即时生效）
 * 并手动触发测试/备份/恢复，免除进完整模式配置的依赖。配置的备份/恢复
 * 忽略清单不在模块配置面内：沿用宿主完整模式的既有设置（实现侧的
 * 备份/恢复管线内部消费，模块无感知）。能力位关闭时全部管理成员为
 * 惰性默认实现，模块入口随之不渲染。
 *
 * ## 宿主实现义务（本仓参照完整模式 MainActivity.backupSync / BackupConfig）
 *
 * - `checkNewBackupOnStart` 判定矩阵：设置关 / 云端无备份 / 拉取失败
 *   → null（静默）；云端最新备份 mtime 与本地 lastBackup 标记差
 *   > 1 分钟 → 命中。**发现即写标记**（返回快照前把 lastBackup 置为
 *   云端 mtime），与宿主一致：用户取消后同一备份不再提示，失败重试
 *   由弹层「重试」承担。
 * - `restoreBackup` 走宿主恢复管线，失败抛异常且 message 面向用户。
 * - 恢复只写 DB：不动模块导航与当前阅读会话（书架流为响应式，
 *   恢复后自动重发）。
 * - `testWebDav` 复用宿主「测试同步」链路；实现侧负责结果反馈（本仓
 *   实现沿用完整模式 toast，失败含原因——模块侧只做忙态，不重复提示）。
 * - `backupNow` 复用宿主备份管线（仅云端模式），失败抛异常；无网络时
 *   上传静默跳过的宿主既有行为保持一致，不在端口侧改写。
 * - `latestBackup` 仅作展示（恢复确认弹框数据），**不写 lastBackup
 *   标记**——与 `checkNewBackupOnStart` 的「发现即标记」刻意区分。
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

    /**
     * 是否支持模块内 WebDAV 配置与手动测试/备份/恢复（能力位）。
     *
     * 关闭（默认）时下方管理成员均为惰性默认实现，模块「备份与恢复」
     * 设置入口不渲染——宿主未跟进实现时入口静默消失，不伪造能力。
     */
    val supportsWebDavManagement: Boolean get() = false

    /**
     * 当前 WebDAV 配置流（与完整模式同一存储的五个字段子集投影）。
     *
     * 仅 [supportsWebDavManagement] 为 true 时有值；默认空流。配置由
     * 宿主或完整模式侧改动时同样经本流重发，模块展示随之刷新。
     */
    val webDavConfig: Flow<WebDavSyncConfig> get() = emptyFlow()

    /**
     * 整体保存五字段（一次原子提交；suspend 返回即已落盘，调用方可
     * 安全随后触发 [testWebDav]）。
     *
     * 模块侧保存前自行归一化（空服务器地址→[WebDavSyncConfig.DEFAULT_SERVER_URL]、
     * 空文件夹→[WebDavSyncConfig.DEFAULT_DIR]、trim）；密码为明文往返
     * （同宿主完整模式设置页，无新增暴露面）。
     */
    suspend fun saveWebDavConfig(config: WebDavSyncConfig) {}

    /**
     * 测试 WebDAV 服务（账号密码连通性校验 + 目录初始化）。
     *
     * @return 服务是否可用。失败细节由实现侧反馈（本仓实现为宿主链路
     *   内置 toast）；返回 false 时模块不重复提示。
     */
    suspend fun testWebDav(): Boolean = false

    /**
     * 立即备份到云端（宿主备份管线，忽略清单沿用宿主设置）。
     *
     * @throws Exception 备份失败，message 面向最终用户，模块直接展示。
     */
    suspend fun backupNow() {}

    /**
     * 云端最新备份快照（恢复确认弹框展示用；仅此用途，不写任何标记）。
     *
     * @return 无备份/未配置/拉取失败返回 null。
     */
    suspend fun latestBackup(): CloudBackupNewer? = null
}

/**
 * 模块内展示与保存的 WebDAV 配置（宿主完整模式同键存储的五字段子集，
 * 全基元不可变）。[serverUrl]/[dir] 为空串时宿主侧有运行时回退
 * （地址→[DEFAULT_SERVER_URL]、文件夹→不拼路径段），模块保存前应先
 * 归一化，空串仅为迁移前的未配置初态。
 *
 * @param serverUrl 服务器地址。
 * @param account 账号。
 * @param password 密码（明文，同宿主存储形态）。
 * @param dir 同步文件夹名称。
 * @param deviceName 同步设备名称；空串 = 未命名设备（备份文件名省略设备段）。
 */
data class WebDavSyncConfig(
    val serverUrl: String,
    val account: String,
    val password: String,
    val dir: String,
    val deviceName: String,
) {
    companion object {
        /** 产品默认服务器地址（坚果云；与宿主运行时回退常量同值）。 */
        const val DEFAULT_SERVER_URL = "https://dav.jianguoyun.com/dav/"

        /** 产品默认同步文件夹名称（与宿主设置模型默认值同值）。 */
        const val DEFAULT_DIR = "legado"
    }
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
