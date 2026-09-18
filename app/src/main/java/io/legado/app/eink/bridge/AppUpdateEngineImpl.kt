package io.legado.app.eink.bridge

import android.app.DownloadManager
import android.content.Context
import io.legado.app.eink.contract.AppDownloadProgress
import io.legado.app.eink.contract.AppDownloadState
import io.legado.app.eink.contract.AppUpdateEngine
import io.legado.app.eink.contract.AppUpdateInfo
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.update.AppUpdate
import io.legado.app.model.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import splitties.init.appCtx
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 应用更新端口实现：转发完整模式同一条更新链（AppUpdateGitHub 检查 +
 * DownloadService 系统下载器 + 完成后系统安装器），渠道遵循宿主
 * 「更新至版本」设置——E-Ink 不新增更新语义，两模式同一更新源。
 *
 * 本宿主差异：
 *  - 完整模式无「启动自动检查」设置（仅关于页手动检查）——
 *    [shouldAutoCheckOnStart] 恒 false 保持两模式同口径，「我的」页
 *    手动检查入口不受影响；
 *  - DownloadService 的下载 id 不外泄——应用内进度经系统
 *    DownloadManager 按下载地址匹配行轮询（约 1s）推导，终态驻留。
 */
object AppUpdateEngineImpl : AppUpdateEngine {

    /** 检查挂靠的宿主作用域：不随 E-Ink 入口退出而取消。 */
    private val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun shouldAutoCheckOnStart(): Boolean {
        // 宿主完整模式无启动自动检查能力/设置（关于页手动检查）；
        // E-Ink 与之同口径，不引入完整模式没有的启动网络行为
        return false
    }

    override suspend fun checkUpdate(): AppUpdateInfo? {
        val updater = AppUpdate.gitHubUpdate
            ?: throw NoStackTraceException("更新源不可用")
        return try {
            awaitUpdateInfo(updater, checkScope).toAppUpdateInfo()
        } catch (e: NoStackTraceException) {
            // 宿主检查器以「已是最新版本」异常表达无更新（其余异常照抛）
            if (e.message == "已是最新版本") null else throw e
        }
    }

    override fun startDownload(update: AppUpdateInfo) {
        if (update.downloadUrl.isBlank() || update.fileName.isBlank()) return
        _downloadProgress.value = null
        Download.start(appCtx, update.downloadUrl, update.fileName)
        progressJob?.cancel()
        progressJob = checkScope.launch {
            pollDownloadProgress(update.downloadUrl)
        }
    }

    private val _downloadProgress = MutableStateFlow<AppDownloadProgress?>(null)
    override val downloadProgress: StateFlow<AppDownloadProgress?> =
        _downloadProgress.asStateFlow()

    private var progressJob: Job? = null

    private val downloadManager: DownloadManager
        get() = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * 按地址匹配系统下载行轮询进度：DownloadService 自有通知/安装管线
     * 不外泄 id，此处经 DownloadManager 全量查询按 COLUMN_URI 过滤。
     * 终态（成功/失败）驻留不再回退；行消失（被清理）视为失败。
     */
    private suspend fun pollDownloadProgress(url: String) {
        var sawRow = false
        while (currentCoroutineContext().isActive) {
            val progress = queryRow(url)
            when {
                progress == null && sawRow -> {
                    _downloadProgress.value = AppDownloadProgress(
                        state = AppDownloadState.FAILED,
                        bytesSoFar = _downloadProgress.value?.bytesSoFar ?: 0,
                        totalBytes = _downloadProgress.value?.totalBytes ?: 0,
                    )
                    return
                }

                progress == null ->
                    _downloadProgress.value = AppDownloadProgress(
                        state = AppDownloadState.PENDING,
                        bytesSoFar = 0,
                        totalBytes = 0,
                    )

                progress.state == AppDownloadState.SUCCEEDED ||
                    progress.state == AppDownloadState.FAILED -> {
                    sawRow = true
                    _downloadProgress.value = progress
                    return
                }

                else -> {
                    sawRow = true
                    _downloadProgress.value = progress
                }
            }
            delay(1000)
        }
    }

    private fun queryRow(url: String): AppDownloadProgress? = runCatching {
        downloadManager.query(DownloadManager.Query()).use { cursor ->
            if (!cursor.moveToFirst()) return@runCatching null
            do {
                if (cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_URI)) != url) {
                    continue
                }
                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                val state = when (status) {
                    DownloadManager.STATUS_PAUSED -> AppDownloadState.PAUSED
                    DownloadManager.STATUS_PENDING -> AppDownloadState.PENDING
                    DownloadManager.STATUS_RUNNING -> AppDownloadState.RUNNING
                    DownloadManager.STATUS_SUCCESSFUL -> AppDownloadState.SUCCEEDED
                    DownloadManager.STATUS_FAILED -> AppDownloadState.FAILED
                    else -> AppDownloadState.PENDING
                }
                return@runCatching AppDownloadProgress(
                    state = state,
                    bytesSoFar = cursor.getLong(
                        cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    ),
                    totalBytes = cursor.getLong(
                        cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    ),
                )
            } while (cursor.moveToNext())
            null
        }
    }.getOrNull()

    private fun AppUpdate.UpdateInfo.toAppUpdateInfo() = AppUpdateInfo(
        versionName = tagName,
        note = updateLog,
        downloadUrl = downloadUrl,
        fileName = fileName,
    )
}

/**
 * [AppUpdate.AppUpdateInterface.check] 的回调链 → suspend 桥接（宿主
 * Coroutine 包装无 await）。回调在构造返回后挂接，网络检查毫秒级远大于
 * 分发竞态窗口；continuation 已取消时 resume 为 no-op。
 */
private suspend fun awaitUpdateInfo(
    updater: AppUpdate.AppUpdateInterface,
    scope: CoroutineScope,
): AppUpdate.UpdateInfo = suspendCancellableCoroutine { continuation ->
    try {
        updater.check(scope)
            .onSuccess { if (continuation.isActive) continuation.resume(it) }
            .onError { if (continuation.isActive) continuation.resumeWithException(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        continuation.resumeWithException(e)
    }
}
