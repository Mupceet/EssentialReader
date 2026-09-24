package io.legado.app.eink.bridge

import io.legado.app.domain.model.ReadingProgress

/**
 * 云端进度同步判定核心（纯函数）。方向语义源自宿主三处比较器共用的
 * 字典序（章节下标优先、章内位置次之），差异仅一处（2026-09-23 定案）：
 * 「云端仅同章内位置超前」单列为 [ProgressRelation.CloudPosAhead]，调用侧
 * 一律忽略——宿主按字典序提示该情形会形成同章微差循环（见枚举 KDoc）。
 * 本地同章位置超前仍算 LocalAhead 走上传，保留章内进度的顺路同步。
 * 静默/确认应用前过章节有效性 gate（弹框上界 = min(模拟阅读上界, 实时
 * 章节行数)，防目录变短/换源/书记录滞后后云端位置越界或应用被吞）。
 */
internal object ReaderProgressSyncPolicy {

    /** 云端进度与本地会话进度的相对关系。 */
    enum class ProgressRelation {
        /** 云端无进度文件（或读取失败视为无）。 */
        CloudMissing,

        /** 云端章节超前。 */
        CloudAhead,

        /**
         * 云端仅同章内位置超前：调用侧一律不提示、不应用、不上传。
         * 章内 pos 跨设备不可严格比较（内容净化/排版差异使同一阅读点
         * 的 pos 值不同），且翻页会把 durChapterPos 回写为页首，恢复后
         * 收敛不回 Equal——按字典序提示就会形成「每次进书都弹、恢复
         * 落在同一视觉页无感」的循环。章节级超前不受影响，仍走确认框。
         */
        CloudPosAhead,

        /** 本地比云端新（含同章位置超前：暂停/进书顺路上传，收敛云端）。 */
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
                cloud.durChapterPos > localChapterPos -> ProgressRelation.CloudPosAhead
            cloud.durChapterIndex == localChapterIndex &&
                cloud.durChapterPos == localChapterPos -> ProgressRelation.Equal
            else -> ProgressRelation.LocalAhead
        }
    }

    fun chapterIndexInBounds(cloudChapterIndex: Int, totalChapters: Int): Boolean =
        cloudChapterIndex >= 0 && cloudChapterIndex < totalChapters

    /**
     * 弹框门槛上界：模拟阅读上界与实时章节行数取小。行数是 setProgress
     * 实际能应用的上界（书记录 totalChapterNum 可能滞后偏高）；只看前者
     * 会弹一个确认后应用必被守卫吞掉的恢复框，形成每次进书都提示的
     * 静默循环。
     */
    fun promptChapterBound(simulatedTotalChapterNum: Int, liveChapterCount: Int): Int =
        minOf(simulatedTotalChapterNum, liveChapterCount)
}
