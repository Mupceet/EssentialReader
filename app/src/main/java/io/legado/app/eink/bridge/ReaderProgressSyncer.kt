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
import io.legado.app.help.book.simulatedTotalChapterNum
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
                // 相等不动（宿主自动路径无 toast）；本地超前仅 Plus 上传
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
