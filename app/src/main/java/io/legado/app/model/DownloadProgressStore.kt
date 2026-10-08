package io.legado.app.model

import android.app.DownloadManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 下载进度状态持有者（进程级）：[io.legado.app.service.DownloadService]
 * 轮询系统下载器时发布逐项进度，消费方按下载地址经 [progressOf]
 * 观测自己发起的那条（应用内进度 UI；通知栏进度在无通知权限的
 * 系统上不可见）。
 *
 * 形态对齐 [io.legado.app.domain.usecase.AiTaskManager]：私有
 * StateFlow 快照表 + 派生访问器，不暴露可变流。进度是状态而非
 * 事件——晚订阅者必须立即拿到当前值，故用 StateFlow 而非
 * SharedFlow；生产者 Service 会 stopSelf，状态不能挂在其实例上。
 *
 * 终态语义：SUCCEEDED/FAILED 发布后停留不回退；条目被移除（取消/
 * 失败重下清理）时由发布方同步调用 [remove] 下线，防止进程内残留
 * 陈旧行。
 */
object DownloadProgressStore {

    /** 单条下载进度快照；键为 [url]。 */
    data class DownloadProgress(
        val url: String,
        val fileName: String,
        val state: DownloadState,
        val bytesSoFar: Long,
        /** 总字节数；<= 0 表示总长未知。 */
        val totalBytes: Long,
    )

    /** 系统下载器状态的模型层投影（不携带 DownloadManager 常量）。 */
    enum class DownloadState {
        PENDING,
        RUNNING,
        PAUSED,
        SUCCEEDED,
        FAILED,
    }

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    /** 按下载地址观测单条进度；未开始/已移除时发射 null。 */
    fun progressOf(url: String): Flow<DownloadProgress?> = _progress.map { it[url] }

    /** 服务轮询发布：整体重建快照表。仅 [io.legado.app.service.DownloadService] 调用。 */
    internal fun publish(rows: Collection<DownloadProgress>) {
        _progress.value = rows.associateBy { it.url }
    }

    /** 条目移除（取消下载/失败重下清理）时同步下线。 */
    internal fun remove(url: String) {
        _progress.update { it - url }
    }
}

/** 系统下载器状态码 → [DownloadProgressStore.DownloadState]；未知状态按 FAILED 落终态。 */
internal fun downloadStateOf(status: Int): DownloadProgressStore.DownloadState =
    when (status) {
        DownloadManager.STATUS_PENDING -> DownloadProgressStore.DownloadState.PENDING
        DownloadManager.STATUS_RUNNING -> DownloadProgressStore.DownloadState.RUNNING
        DownloadManager.STATUS_PAUSED -> DownloadProgressStore.DownloadState.PAUSED
        DownloadManager.STATUS_SUCCESSFUL -> DownloadProgressStore.DownloadState.SUCCEEDED
        DownloadManager.STATUS_FAILED -> DownloadProgressStore.DownloadState.FAILED
        else -> DownloadProgressStore.DownloadState.FAILED
    }
