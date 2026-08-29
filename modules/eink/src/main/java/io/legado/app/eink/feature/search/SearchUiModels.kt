package io.legado.app.eink.feature.search

import io.legado.app.eink.arch.EInkImmutable

/**
 * 搜索结果条目 UiModel（SearchBook → 稳定字段快照）。
 * intro（trimIntro）在桥接层预计算，组合期不再做 html 清洗。
 */
@EInkImmutable
data class SearchBookUiModel(
    val bookUrl: String,
    val name: String,
    val author: String,
    val coverUrl: String?,
    /** 简介（已 trimIntro 清洗）。 */
    val intro: String,
    val latestChapterTitle: String?,
    /** 书源 origin（封面自定义头 + 来源展示）。 */
    val origin: String,
    /** 书源名（originName，空时由展示层回退 origin）。 */
    val originName: String,
)

/** 搜索历史条目 UiModel。 */
@EInkImmutable
data class SearchHistoryUiModel(
    val word: String,
)
