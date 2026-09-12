package io.legado.app.eink.bridge

import android.util.Log
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

/** 同步链路 logcat tag（release 也可观测，真机联调验证手册入口）。 */
private const val LOG_TAG = "EInkProgressSync"

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

    /**
     * 同步链路观测日志：logcat 常开（AppLog 在 release 不进 logcat，仅写
     * 内存环与可选文件，真机联调需 adb 直看），并入 AppLog 保持应用内
     * 一致。tag 固定 [LOG_TAG]，验证手册：adb logcat -s EInkProgressSync。
     */
    private fun log(message: String) {
        Log.i(LOG_TAG, message)
        AppLog.put(message)
    }

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
        if (consumeChapterJumped()) {
            log("进书同步：目录跳章进入，抑制本次")
            return
        }
        val settings = backupSettingsGateway.currentSettings
        if (!settings.syncBookProgress) {
            log("进书同步：同步开关关，跳过")
            return
        }
        if (!ReadBook.inBookshelf) {
            log("进书同步：未加书架，跳过")
            return
        }
        val book = ReadBook.book ?: run {
            log("进书同步：无会话书，跳过")
            return
        }
        val plus = settings.syncBookProgressPlus
        scope.launch {
            val cloud = fetchCloudProgress(book)
            when (ReaderProgressSyncPolicy.relation(cloud, ReadBook.durChapterIndex, ReadBook.durChapterPos)) {
                ProgressRelation.CloudAhead -> {
                    if (!ReaderProgressSyncPolicy.chapterIndexInBounds(
                            cloud!!.durChapterIndex, book.simulatedTotalChapterNum()
                        )
                    ) {
                        log("进书同步：云端章节越界（${cloud.durChapterIndex}/${book.simulatedTotalChapterNum()}），放弃")
                        return@launch
                    }
                    if (plus) {
                        log("进书同步：云端超前（第${cloud.durChapterIndex + 1}章），弹确认框")
                        onCloudProgressNewer?.invoke(
                            ReaderCloudProgress(cloud.durChapterIndex, cloud.durChapterPos)
                        )
                    } else {
                        applyProgress(cloud)
                        log("自动同步阅读进度成功《${book.name}》 ${cloud.durChapterTitle}")
                    }
                }
                // 相等不动（宿主自动路径无 toast）；本地超前仅 Plus 上传
                ProgressRelation.CloudMissing, ProgressRelation.LocalAhead ->
                    if (plus) {
                        log("进书同步：${if (cloud == null) "云端无进度" else "本地超前"}，上传")
                        uploadCurrentProgress()
                    } else {
                        log("进书同步：${if (cloud == null) "云端无进度" else "本地超前"}，不上传（同步增强未开）")
                    }
                ProgressRelation.Equal -> log("进书同步：进度一致，无操作")
            }
        }
    }

    /** Activity 级暂停：宿主 ReadBookViewModel.handleOnPause 同步位。 */
    private fun syncOnPaused() {
        // saveRead 无条件先行（宿主顺序：落库 → 同步）；同步+备份整段 DEBUG 跳过
        ReadBook.saveRead()
        if (BuildConfig.DEBUG) {
            log("暂停同步：DEBUG 构建跳过（宿主同位 gate），本地已落库")
            return
        }
        val plus = backupSettingsGateway.currentSettings.syncBookProgressPlus
        scope.launch {
            val book = ReadBook.book ?: run {
                log("暂停同步：无会话书，跳过")
                return@launch
            }
            if (plus) {
                val cloud = fetchCloudProgress(book)
                when (ReaderProgressSyncPolicy.relation(cloud, ReadBook.durChapterIndex, ReadBook.durChapterPos)) {
                    // 云端超前：留给下次进书确认（宿主 onPause 无回调即放弃）；
                    // 相等不动
                    ProgressRelation.CloudMissing, ProgressRelation.LocalAhead -> {
                        log("暂停同步：本地超前或无云端，上传")
                        uploadCurrentProgress()
                    }
                    ProgressRelation.CloudAhead -> log("暂停同步：云端超前，放弃（留待进书确认）")
                    ProgressRelation.Equal -> log("暂停同步：进度一致，不上传")
                }
            } else {
                log("暂停同步：直接上传当前进度")
                uploadCurrentProgress()
            }
            Backup.autoBack(appCtx)
            log("暂停同步：已触发自动备份")
        }
    }

    /** Activity 级恢复：宿主 handleOnResume 的 webBookProgress 热应用位。 */
    private fun applyPendingWebProgress() {
        val pending = ReadBook.webBookProgress ?: return
        log("恢复同步：应用 Web 暂存进度（第${pending.durChapterIndex + 1}章）")
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
                    ) {
                        log("网络恢复同步：云端章节越界，放弃")
                        return@launch
                    }
                    log("网络恢复同步：云端超前（第${cloud.durChapterIndex + 1}章），弹确认框")
                    onCloudProgressNewer?.invoke(
                        ReaderCloudProgress(cloud.durChapterIndex, cloud.durChapterPos)
                    )
                }
                ProgressRelation.CloudMissing, ProgressRelation.LocalAhead -> {
                    log("网络恢复同步：本地超前或无云端，上传")
                    uploadCurrentProgress()
                }
                ProgressRelation.Equal -> Unit
            }
        }
    }

    /** 周期备份计时到期：宿主 ReadBookViewModel.startBackupJob 到期动作。 */
    private fun backupTimerFired() {
        log("周期备份：5 分钟计时到期，上传并备份")
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
                log("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}")
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
            .onFailure { log("上传进度失败\n${it.localizedMessage}") }
            .getOrNull()
        if (uploadTime != null) {
            book.syncTime = uploadTime
            book.update()
            log("上传进度成功《${book.name}》第${ReadBook.durChapterIndex + 1}章 位置${ReadBook.durChapterPos}")
        } else {
            log("上传进度未生效（未配置 WebDAV/开关关/网络不可用，UseCase 返回 null）")
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
