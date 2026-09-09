package io.legado.app.eink.bridge

import io.legado.app.data.entities.Book
import io.legado.app.utils.cnCompare

/**
 * 书架排序（bridge 内消化，排序键不进契约；设计 §3.2）：语义与 View 版
 * `BookshelfRepository.sortBooks` 一致——0 阅读时间、1 更新时间、2 书名、
 * 3 手动（order 字段）、4 max(更新, 阅读) 时间、5 作者，`sortOrder == 1`
 * 为降序，未知 sort 走 else（阅读时间）。
 *
 * 端口只投影「全部书架」，View 版 per-group bookSort 覆盖不适用。
 * 手动排序必须显式排序：DAO `flowAll()` 自然序是 `durChapterTime desc`，
 * 与手动序无关，不得依赖自然序巧合。cnCompare 为宿主工具，故排序收敛
 * 在 bridge 而非模块（模块零计算纪律）。
 */
internal fun List<Book>.sortedForBookshelf(sort: Int, sortOrder: Int): List<Book> {
    val descending = sortOrder == 1
    return when (sort) {
        1 -> if (descending) sortedByDescending { it.latestChapterTime }
        else sortedBy { it.latestChapterTime }

        2 -> if (descending) sortedWith { a, b -> b.name.cnCompare(a.name) }
        else sortedWith { a, b -> a.name.cnCompare(b.name) }

        3 -> if (descending) sortedByDescending { it.order }
        else sortedBy { it.order }

        4 -> if (descending) sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
        else sortedBy { maxOf(it.latestChapterTime, it.durChapterTime) }

        5 -> if (descending) sortedWith { a, b -> b.author.cnCompare(a.author) }
        else sortedWith { a, b -> a.author.cnCompare(b.author) }

        else -> if (descending) sortedByDescending { it.durChapterTime }
        else sortedBy { it.durChapterTime }
    }
}
