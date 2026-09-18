package io.legado.app.eink.bridge

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.eink.contract.ReaderCloudProgress
import io.legado.app.eink.contract.ReaderSyncTrigger
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.model.ReadBook
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx

/** 同步链路 logcat tag（release 也可观测，真机联调：adb logcat -s EInkProgressSync）。 */
private const val LOG_TAG = "EInkProgressSync"

/**
 * 云端进度同步编排（E-Ink「一键全开」档），门槛矩阵见模块契约
 * ReaderSyncTrigger KDoc。与宿主完整模式的等价性：
 *  - 拉取/上传直接走 [AppWebDav]（本宿主无进度网关；AppWebDav 内部自带
 *    授权/主开关/网络三重守卫，失败静默）；
 *  - ReaderPaused 的「同步 + 自动备份」整段 DEBUG 构建跳过（宿主
 *    ReadBookActivity.onPause 同位 gate），saveRead 无条件先行；
 *  - 进书同步受目录跳章标记抑制（宿主 chapterChanged 同位语义，本层
 *    自持标志：jumpToPosition / 目录页落进度时置位）；
 *  - 云端超前的应用路径先过章节有效性 gate（防换源/目录变短后越界）。
 *
 * 「一键全开」：E-Ink 不区分宿主「同步增强」子键——主开关
 * （GlobalSettings.syncReadingProgress → AppConfig.syncBookProgress）开即
 * 恒走完整双向行为，消除宿主基础档「暂停盲上传」把云端更超前进度覆盖回
 * 旧值的回退隐患。
 */
internal object ReaderProgressSyncer {

    /** 云端超前需确认时通知模块（ReaderEngineImpl 接线到当前注册回调）。 */
    @Volatile
    var onCloudProgressNewer: ((ReaderCloudProgress) -> Unit)? = null

    /** 目录跳章进入标记（宿主 chapterChanged 同位）；进书同步消费即清零。 */
    @Volatile
    private var chapterJumpedSinceEntry = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun log(message: String) {
        Log.i(LOG_TAG, message)
        AppLog.put(message)
    }

    /** 目录跳章发生（jumpToPosition / 目录页落进度跳转）时置位。 */
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

    /**
     * 以本设备进度覆盖云端（弹框「以本设备为准」；宿主菜单「覆盖云端进度」
     * /ReadBook.uploadProgress 同义）：用户显式动作，无条件上传、不做超前
     * 比较，亦无 DEBUG gate——上传成功后云端与本设备一致，进书冲突提示
     * 随之消除。
     */
    fun coverCloudProgress() {
        scope.launch {
            log("覆盖云端：以本设备进度上传（用户确认）")
            uploadCurrentProgress()
        }
    }

    // ---- 触发点实现 ----

    /** 进书装载完成：宿主 ReadBookViewModel.initBook 同步位（一键全开档）。 */
    private fun syncOnEntered() {
        val jumped = chapterJumpedSinceEntry
        chapterJumpedSinceEntry = false
        if (jumped) {
            log("进书同步：目录跳章进入，抑制本次")
            return
        }
        if (!AppConfig.syncBookProgress) {
            log("进书同步：同步开关关，跳过")
            return
        }
        if (!ReadBook.inBookshelf) {
            log("进书同步：未加书架，跳过")
            return
        }
        if (ReadBook.book == null) {
            log("进书同步：无会话书，跳过")
            return
        }
        scope.launch {
            val book = ReadBook.book ?: return@launch
            val cloud = fetchCloudProgress(book)
            val cloudMissing = cloud == null
            when (relation(cloud)) {
                ProgressRelation.CLOUD_AHEAD -> {
                    if (!chapterIndexInBounds(cloud!!.durChapterIndex)) {
                        log("进书同步：云端章节越界（${cloud.durChapterIndex}），放弃")
                        return@launch
                    }
                    log("进书同步：云端超前（第${cloud.durChapterIndex + 1}章），弹确认框")
                    onCloudProgressNewer?.invoke(
                        ReaderCloudProgress(cloud.durChapterIndex, cloud.durChapterPos)
                    )
                }
                // 云端无进度或本地超前 → 上传；相等不动（宿主自动路径无 toast）
                ProgressRelation.CLOUD_MISSING, ProgressRelation.LOCAL_AHEAD -> {
                    log("进书同步：${if (cloudMissing) "云端无进度" else "本地超前"}，上传")
                    uploadCurrentProgress()
                }
                ProgressRelation.EQUAL -> log("进书同步：进度一致，无操作")
            }
        }
    }

    /** Activity 级暂停：宿主 ReadBookActivity.onPause 同步位（先比较后上传）。 */
    private fun syncOnPaused() {
        // saveRead 无条件先行（宿主顺序：落库 → 同步）；同步+备份整段 DEBUG 跳过
        ReadBook.saveRead()
        if (BuildConfig.DEBUG) {
            log("暂停同步：DEBUG 构建跳过（宿主同位 gate），本地已落库")
            return
        }
        if (!ReadBook.inBookshelf) return
        scope.launch {
            val book = ReadBook.book ?: return@launch
            val cloud = fetchCloudProgress(book)
            when (relation(cloud)) {
                // 云端超前：留给下次进书确认（宿主 onPause 无回调即放弃）
                ProgressRelation.CLOUD_MISSING, ProgressRelation.LOCAL_AHEAD -> {
                    log("暂停同步：本地超前或无云端，上传")
                    uploadCurrentProgress()
                }
                ProgressRelation.CLOUD_AHEAD ->
                    log("暂停同步：云端超前，放弃（留待进书确认）")
                ProgressRelation.EQUAL -> log("暂停同步：进度一致，不上传")
            }
            Backup.autoBack(appCtx)
            log("暂停同步：已触发自动备份")
        }
    }

    /** Activity 级恢复：宿主 onPause→onResume 的 webBookProgress 热应用位。 */
    private fun applyPendingWebProgress() {
        val pending = ReadBook.webBookProgress ?: return
        log("恢复同步：应用 Web 暂存进度（第${pending.durChapterIndex + 1}章）")
        scope.launch {
            ReadBook.webBookProgress = null
            ReadBook.setProgress(pending)
        }
    }

    /** 网络恢复：宿主 ReadBookActivity.onResume 位（一键全开：主开关开即同步）。 */
    private fun syncOnNetworkAvailable() {
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        if (ReadBook.book == null) return
        if (!ReadBook.inBookshelf) return
        scope.launch {
            val book = ReadBook.book ?: return@launch
            val cloud = fetchCloudProgress(book)
            when (relation(cloud)) {
                ProgressRelation.CLOUD_AHEAD -> {
                    if (!chapterIndexInBounds(cloud!!.durChapterIndex)) {
                        log("网络恢复同步：云端章节越界，放弃")
                        return@launch
                    }
                    log("网络恢复同步：云端超前（第${cloud.durChapterIndex + 1}章），弹确认框")
                    onCloudProgressNewer?.invoke(
                        ReaderCloudProgress(cloud.durChapterIndex, cloud.durChapterPos)
                    )
                }
                ProgressRelation.CLOUD_MISSING, ProgressRelation.LOCAL_AHEAD -> {
                    log("网络恢复同步：本地超前或无云端，上传")
                    uploadCurrentProgress()
                }
                ProgressRelation.EQUAL -> Unit
            }
        }
    }

    /** 周期备份计时到期：宿主 5 分钟自动任务。 */
    private fun backupTimerFired() {
        log("周期备份：计时到期，上传并备份")
        scope.launch {
            ReadBook.saveRead()
            uploadCurrentProgress()
            Backup.autoBack(appCtx)
        }
    }

    // ---- 判定核心（复刻宿主字典序比较：章节下标优先、章内位置次之） ----

    private enum class ProgressRelation {
        CLOUD_MISSING, CLOUD_AHEAD, LOCAL_AHEAD, EQUAL,
    }

    private fun relation(cloud: BookProgress?): ProgressRelation {
        cloud ?: return ProgressRelation.CLOUD_MISSING
        return when {
            cloud.durChapterIndex > ReadBook.durChapterIndex -> ProgressRelation.CLOUD_AHEAD
            cloud.durChapterIndex == ReadBook.durChapterIndex &&
                cloud.durChapterPos > ReadBook.durChapterPos -> ProgressRelation.CLOUD_AHEAD
            cloud.durChapterIndex == ReadBook.durChapterIndex &&
                cloud.durChapterPos == ReadBook.durChapterPos -> ProgressRelation.EQUAL
            else -> ProgressRelation.LOCAL_AHEAD
        }
    }

    private fun chapterIndexInBounds(cloudChapterIndex: Int): Boolean =
        cloudChapterIndex >= 0 && cloudChapterIndex < ReadBook.chapterSize

    private suspend fun fetchCloudProgress(book: Book): BookProgress? {
        return runCatching { AppWebDav.getBookProgress(book) }
            .onFailure { log("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}") }
            .getOrNull()
    }

    private suspend fun uploadCurrentProgress() {
        val book = ReadBook.book ?: return
        try {
            AppWebDav.uploadBookProgress(BookProgress(book)) {
                book.syncTime = System.currentTimeMillis()
            }
            book.update()
            log("上传进度成功《${book.name}》第${ReadBook.durChapterIndex + 1}章 位置${ReadBook.durChapterPos}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("上传进度未生效（未配置 WebDAV/开关关/网络不可用）\n${e.localizedMessage}")
        }
    }
}
