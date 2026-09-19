package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书籍详情展示快照。
 *
 * 映射纪律：
 * ```text
 * 宿主书籍实体
 *        │ 映射期一次计算（作者清洗 / 展示封面挑选 / 简介清洗）
 *        ▼
 * BookDetailUiModel（全基元字段；引擎身份走 BookHandle，不进本类型）
 * ```
 *
 * 宿主实现义务：展示类计算在映射时一次完成——[displayAuthor] 与
 * [displayCover] 是宿主规则下的最终值，模块不再加工；[displayIntro]
 * 无简介时传 null。
 */
@EInkImmutable
data class BookDetailUiModel(
    /** 书籍唯一键。 */
    val bookUrl: String,

    /** 书名。 */
    val name: String,

    /** 展示作者（已按书源规则清洗）。 */
    val displayAuthor: String,

    /** 展示封面地址（自定义封面优先；null/空走模块占位封面）。 */
    val displayCover: String?,

    /** 展示简介（映射期已清洗；无简介为 null）。 */
    val displayIntro: String?,

    /** 最新章节标题（无则 null）。 */
    val latestChapterTitle: String?,

    /** 当前阅读章节标题（阅读进度摘要展示）。 */
    val currentChapterTitle: String?,

    /** 书源 origin 标识。 */
    val origin: String,
)
