package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 搜索结果条目展示快照。
 *
 * 宿主实现义务：[intro] 在映射时完成 HTML/空白清洗（组合期不再处理）；
 * [kind] 保留逗号分隔的分类标签原文（模块排序的「标签命中」依据）；
 * [originName] 为空串时展示层回退显示 [origin]。
 */
@EInkImmutable
data class SearchBookUiModel(
    val bookUrl: String,
    val name: String,
    val author: String,
    /** 分类标签（逗号分隔原文），搜索结果排序的标签命中桶依据。 */
    val kind: String? = null,
    /** 命中书源数量，同排序桶内按此降序。 */
    val originsCount: Int = 1,
    val coverUrl: String?,
    /** 简介（映射期已清洗）。 */
    val intro: String,
    val latestChapterTitle: String?,
    /** 书源 origin 标识（封面请求的源信息 + 来源展示回退值）。 */
    val origin: String,
    /** 书源显示名。 */
    val originName: String,
)

/** 搜索历史条目展示快照。 */
@EInkImmutable
data class SearchHistoryUiModel(
    val word: String,
)
