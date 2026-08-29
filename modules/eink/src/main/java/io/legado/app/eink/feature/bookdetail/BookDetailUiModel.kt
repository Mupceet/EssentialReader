package io.legado.app.eink.feature.bookdetail

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书籍详情 UiModel（Book → 展示字段快照）。
 * 展示类计算（getRealAuthor/getDisplayCover/getDisplayIntro）在桥接层
 * 预计算一次；引擎身份（Book 实体）经 [io.legado.app.eink.engine.BookHandle]
 * 由 VM 持有，不进组合层。
 */
@EInkImmutable
data class BookDetailUiModel(
    val bookUrl: String,
    val name: String,
    /** 展示作者（getRealAuthor）。 */
    val displayAuthor: String,
    /** 展示封面地址（getDisplayCover，空走占位封面）。 */
    val displayCover: String?,
    /** 展示简介（getDisplayIntro，无简介时为 null）。 */
    val displayIntro: String?,
    val latestChapterTitle: String?,
    /** 当前阅读章节标题。 */
    val durChapterTitle: String?,
    /** 书源 origin。 */
    val origin: String,
)
