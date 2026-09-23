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
