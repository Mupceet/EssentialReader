package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 搜索结果条目展示快照。
 *
 * 映射纪律：
 * ```text
 * 宿主搜索结果实体（多源聚合）
 *        │ 到达批次时一次映射（简介 HTML 清洗 / 命中源计数）
 *        ▼
 * SearchBookUiModel（全基元字段）
 *        │ 组合期
 *        ▼
 * 模块渲染与排序（精确命中 → 标签命中 → 包含命中 → 其他；
 *                同桶按 originsCount 降序——排序在模块 VM，不进本类型）
 * ```
 *
 * 宿主实现义务：[intro] 在映射时完成 HTML/空白清洗（组合期不再处理）；
 * [kind] 保留逗号分隔的分类标签原文（模块排序的「标签命中」依据）；
 * [originName] 为空串时展示层回退显示 [origin]。
 */
@EInkImmutable
data class SearchBookUiModel(
    /** 书籍唯一键（点击进详情/阅读的导航键）。 */
    val bookUrl: String,

    /** 书名。 */
    val name: String,

    /** 作者（原始值，展示与匹配用）。 */
    val author: String,

    /** 分类标签（逗号分隔原文），搜索结果排序的标签命中桶依据。 */
    val kind: String? = null,

    /** 命中书源数量，同排序桶内按此降序。 */
    val originsCount: Int = 1,

    /** 封面地址（null/空走模块占位封面）。 */
    val coverUrl: String?,

    /** 简介（映射期已清洗）。 */
    val intro: String,

    /** 最新章节标题（无则 null）。 */
    val latestChapterTitle: String?,

    /** 书源 origin 标识（封面请求的源信息 + 来源展示回退值）。 */
    val origin: String,

    /** 书源显示名。 */
    val originName: String,
)

/** 搜索历史条目展示快照。 */
@EInkImmutable
data class SearchHistoryUiModel(
    /** 历史搜索词（点击即以该词发起搜索）。 */
    val word: String,
)
