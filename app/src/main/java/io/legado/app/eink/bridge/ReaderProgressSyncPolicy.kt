package io.legado.app.eink.bridge

import io.legado.app.domain.model.ReadingProgress

/**
 * 云端进度同步判定核心（纯函数）。复刻宿主三处比较器共用的字典序语义：
 * 章节下标优先、章内位置次之；静默/确认应用前过章节有效性 gate
 * （宿主 `durChapterIndex < simulatedTotalChapterNum()`，防目录变短/换源
 * 后云端位置越界）。
 */
internal object ReaderProgressSyncPolicy {

    /** 云端进度与本地会话进度的相对关系。 */
    enum class ProgressRelation {
        /** 云端无进度文件（或读取失败视为无）。 */
        CloudMissing,

        /** 云端比本地新。 */
        CloudAhead,

        /** 本地比云端新。 */
        LocalAhead,

        /** 完全一致。 */
        Equal,
    }

    fun relation(
        cloud: ReadingProgress?,
        localChapterIndex: Int,
        localChapterPos: Int,
    ): ProgressRelation {
        cloud ?: return ProgressRelation.CloudMissing
        return when {
            cloud.durChapterIndex > localChapterIndex -> ProgressRelation.CloudAhead
            cloud.durChapterIndex == localChapterIndex &&
                cloud.durChapterPos > localChapterPos -> ProgressRelation.CloudAhead
            cloud.durChapterIndex == localChapterIndex &&
                cloud.durChapterPos == localChapterPos -> ProgressRelation.Equal
            else -> ProgressRelation.LocalAhead
        }
    }

    fun chapterIndexInBounds(cloudChapterIndex: Int, totalChapters: Int): Boolean =
        cloudChapterIndex >= 0 && cloudChapterIndex < totalChapters
}
