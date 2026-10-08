package io.legado.app.eink.bridge

import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.eink.contract.BookshelfStyle

/**
 * BookshelfSettings → BookshelfStyle 策划投影（唯一映射点，设计 §4）。
 *
 * 读取生效 8 键中的 6 个显示键在此投影（排序双键在 [BookshelfSorter]
 * 于 observeShelf 内消化，不进契约）。其余宿主键全部为主动忽略或功能面
 * 缺失（设计 §5 逐键依据）：契约不扩字段即不生效，后续新键按设计 §6
 * 演进规则走「契约字段 + 本函数映射 + 模块消费」三处一次提交。
 */
internal fun BookshelfSettings.toBookshelfStyle(): BookshelfStyle = BookshelfStyle(
    showUnreadBadge = showUnread,
    highlightNewChapter = showUnreadNew,
    showLatestChapter = bookshelfShowLatestChapter,
    isGridLayout = bookshelfLayoutModePortrait != 0,
    gridCoverWidth = if (bookshelfGridCoverWidth <= 0) 120 else bookshelfGridCoverWidth,
    titleMaxLines = if (bookshelfTitleMaxLines in 1..5) bookshelfTitleMaxLines else 2,
)

/**
 * 样式快照反向写投影（setStyle 唯一消费点）：六键一次原子 copy，
 * 非本通道键（排序/横屏等）由调用方 update 语义保持原值。
 */
internal fun BookshelfSettings.withStyleProjection(style: BookshelfStyle): BookshelfSettings = copy(
    showUnread = style.showUnreadBadge,
    showUnreadNew = style.highlightNewChapter,
    bookshelfShowLatestChapter = style.showLatestChapter,
    bookshelfLayoutModePortrait = if (style.isGridLayout) 1 else 0,
    bookshelfGridCoverWidth = style.gridCoverWidth,
    bookshelfTitleMaxLines = style.titleMaxLines,
)
