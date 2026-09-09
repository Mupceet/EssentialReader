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
    titleMaxLines = bookshelfTitleMaxLines.coerceIn(1, 5),
)

/** 布局切换的反向写投影：1 = 网格、0 = 列表（只动竖屏键）。 */
internal fun BookshelfSettings.withGridLayout(grid: Boolean): BookshelfSettings =
    copy(bookshelfLayoutModePortrait = if (grid) 1 else 0)
