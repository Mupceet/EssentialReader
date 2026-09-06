package io.legado.app.eink.bridge

import io.legado.app.eink.contract.AppUpdateEngine
import io.legado.app.eink.contract.AppUpdateInfo
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.UpToDateException
import io.legado.app.model.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import splitties.init.appCtx
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 应用更新端口实现：转发完整模式同一条更新链（AppUpdateGitHub 双通道
 * 检查 + DownloadService 下载安装），渠道遵循宿主「更新至版本」设置——
 * E-Ink 侧不新增任何更新语义，两个模式看到同一个更新源。
 */
object AppUpdateEngineImpl : AppUpdateEngine {

    /**
     * 检查挂靠的宿主作用域：不随 E-Ink 入口退出而取消（入口重建后
     * 重新装配的是注册表条目，进行中的检查结果由 UI 侧状态承接）。
     */
    private val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override suspend fun checkUpdate(): AppUpdateInfo? {
        val update = AppUpdate.gitHubUpdate
            ?: throw NoStackTraceException("更新源不可用")
        return try {
            update.awaitUpdateInfo(checkScope).toAppUpdateInfo()
        } catch (e: UpToDateException) {
            null
        }
    }

    override fun startDownload(update: AppUpdateInfo) {
        // 防御：正常路径下 checkUpdate 返回的资产直链/文件名必非空
        //（GitHub API 资产字段保证），此处仅阻断理论上的空值透传
        if (update.downloadUrl.isBlank() || update.fileName.isBlank()) return
        Download.start(appCtx, update.downloadUrl, update.fileName)
    }

    private fun AppUpdate.UpdateInfo.toAppUpdateInfo() = AppUpdateInfo(
        versionName = tagName,
        note = updateLog,
        downloadUrl = downloadUrl,
        fileName = fileName
    )
}

/**
 * [AppUpdate.AppUpdateInterface.check] 的回调链 → suspend 桥接。
 *
 * 回调在 Coroutine 构造返回后同步挂接，分发竞态窗口为微秒级而网络
 * 检查至少毫秒级，实际不可触发「太快完成回调不执行」；continuation
 * 已取消时 resume 为 no-op（isActive 双保险）。
 */
private suspend fun AppUpdate.AppUpdateInterface.awaitUpdateInfo(
    scope: CoroutineScope
): AppUpdate.UpdateInfo = suspendCancellableCoroutine { continuation ->
    try {
        check(scope)
            .onSuccess { if (continuation.isActive) continuation.resume(it) }
            .onError { if (continuation.isActive) continuation.resumeWithException(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        continuation.resumeWithException(e)
    }
}
