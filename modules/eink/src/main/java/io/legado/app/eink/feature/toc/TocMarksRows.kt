package io.legado.app.eink.feature.toc

import io.legado.app.eink.contract.BookmarkUiModel
import io.legado.app.eink.contract.MarkingUiModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书签/笔记 Tab 的扁平行：章节聚合头 + 卡片。LazyColumn 直接按它渲染，
 * 变高分页（[io.legado.app.eink.designsystem.pager.EInkFlowPagerState]）
 * 也按它下标翻页——两处共用同一份行模型，避免下标口径不一致。
 */
sealed interface TocMarkRow {

    val chapterIndex: Int

    /** 章节聚合头：章名 + 条目数（当前阅读章高亮，见 Screen）。 */
    data class ChapterHeader(
        override val chapterIndex: Int,
        val chapterName: String,
        val itemCount: Int,
    ) : TocMarkRow

    /** 书签卡行。 */
    data class Bookmark(val bookmark: BookmarkUiModel) : TocMarkRow {
        override val chapterIndex: Int get() = bookmark.chapterIndex
    }

    /** 笔记卡行（划线／想法）。 */
    data class Marking(val marking: MarkingUiModel) : TocMarkRow {
        override val chapterIndex: Int get() = marking.chapterIndex
    }
}

/**
 * 书签按章节聚合为扁平行：每章一个 [TocMarkRow.ChapterHeader]，其下依次
 * 是本章书签卡。输入已由宿主按（章、章内位置）升序，分组保持该顺序。
 */
fun bookmarkRows(bookmarks: List<BookmarkUiModel>): List<TocMarkRow> =
    groupRows(bookmarks, { it.chapterIndex }, { it.chapterName }) { TocMarkRow.Bookmark(it) }

/**
 * 划线/想法按章节聚合为扁平行（同上；宿主按（章、**章内正文本位置**）升序——
 * 卡片顺序与正文阅读顺序一致，补记的划线不会因创建时间晚而排到章末）。
 */
fun markingRows(markings: List<MarkingUiModel>): List<TocMarkRow> =
    groupRows(markings, { it.chapterIndex }, { it.chapterName }) { TocMarkRow.Marking(it) }

/**
 * 章节聚合（保持输入顺序的稳定分组）：连续同章条目归为一组，组内顺序
 * 不变；空章名回落到「第 N 章」占位，避免出现无标题的章节头。
 */
private inline fun <T> groupRows(
    items: List<T>,
    chapterIndexOf: (T) -> Int,
    chapterNameOf: (T) -> String,
    toRow: (T) -> TocMarkRow,
): List<TocMarkRow> {
    if (items.isEmpty()) return emptyList()
    val rows = ArrayList<TocMarkRow>(items.size + 8)
    var index = 0
    while (index < items.size) {
        val chapterIndex = chapterIndexOf(items[index])
        var end = index
        while (end < items.size && chapterIndexOf(items[end]) == chapterIndex) end++
        val group = items.subList(index, end)
        rows += TocMarkRow.ChapterHeader(
            chapterIndex = chapterIndex,
            chapterName = chapterNameOf(group.first()).ifBlank { "第 ${chapterIndex + 1} 章" },
            itemCount = group.size,
        )
        group.forEach { rows += toRow(it) }
        index = end
    }
    return rows
}

/** 当前阅读章对应的章节头行下标（「回到当前」滚动目标）；无该章返回 null。 */
fun currentChapterRowIndex(rows: List<TocMarkRow>, currentChapterIndex: Int): Int? =
    rows.indexOfFirst {
        it is TocMarkRow.ChapterHeader && it.chapterIndex == currentChapterIndex
    }.takeIf { it >= 0 }

/** 可显示时间戳的下界（2000-01-01）：更早的值视为脏数据不显示。 */
private const val MIN_VALID_TIME_MILLIS = 946_684_800_000L

/** 容差：允许略超当前时间（设备时钟回拨/时区边界）。 */
private const val TIME_FUTURE_TOLERANCE_MILLIS = 86_400_000L

private val markTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * 标记时间显示（卡片第一行的基础信息）：毫秒时间戳 → `yyyy-MM-dd HH:mm`。
 *
 * 书签主键（宿主 `Bookmark.time`）与标记 `createdAt` 都是毫秒时间戳；
 * 历史数据/异常值（早于 2000 或明显在未来）返回 null —— 卡片第一行只留图标，
 * 不显示伪造时间。用 [SimpleDateFormat] 而非 java.time：模块 minSdk 21，
 * 不引入 core library desugaring 依赖。
 */
fun formatMarkTime(
    epochMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    if (epochMillis < MIN_VALID_TIME_MILLIS) return null
    if (epochMillis > nowMillis + TIME_FUTURE_TOLERANCE_MILLIS) return null
    return synchronized(markTimeFormatter) { markTimeFormatter.format(Date(epochMillis)) }
}
