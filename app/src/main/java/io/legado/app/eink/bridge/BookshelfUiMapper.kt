package io.legado.app.eink.bridge

import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.BookshelfItemUiModel

/**
 * [Book] → [BookshelfItemUiModel]：书架条目渲染字段的唯一抽取点
 * （书架全量流与分组流共用，避免两份映射漂移）。语义见
 * [BookshelfItemUiModel] 的映射纪律 KDoc。
 */
internal fun Book.toBookshelfItemUiModel() = BookshelfItemUiModel(
    bookUrl = bookUrl,
    name = name,
    author = author,
    displayAuthor = getRealAuthor(),
    coverUrl = getDisplayCover(),
    origin = origin,
    currentChapterTitle = durChapterTitle,
    latestChapterTitle = latestChapterTitle,
    unreadCount = getUnreadChapterNum(),
    hasNewChapter = lastCheckCount > 0,
)

/** 排序键投影：设置流任意键变化不触发书架重排，仅排序键变化才重发。 */
internal data class BookshelfSortKey(val sort: Int, val sortOrder: Int)
