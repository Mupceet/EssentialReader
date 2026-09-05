package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书籍详情展示快照。
 *
 * 宿主实现义务：展示类计算（作者清洗、展示封面挑选、简介清洗）在
 * 映射时一次完成——[displayAuthor] 与 [displayCover] 是宿主规则下的
 * 最终值，模块不再加工；[displayIntro] 无简介时传 null。
 */
@EInkImmutable
data class BookDetailUiModel(
    val bookUrl: String,
    val name: String,
    /** 展示作者（已按书源规则清洗）。 */
    val displayAuthor: String,
    /** 展示封面地址（自定义封面优先；null/空走模块占位封面）。 */
    val displayCover: String?,
    /** 展示简介（映射期已清洗；无简介为 null）。 */
    val displayIntro: String?,
    val latestChapterTitle: String?,
    /** 当前阅读章节标题（阅读进度摘要展示）。 */
    val currentChapterTitle: String?,
    /** 书源 origin 标识。 */
    val origin: String,
)
