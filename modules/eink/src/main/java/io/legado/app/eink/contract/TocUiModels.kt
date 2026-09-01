package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/** 目录页书籍信息 UiModel（Book → 展示字段快照）。 */
@EInkImmutable
data class TocBookUiModel(
    val bookUrl: String,
    val name: String,
    /** 当前阅读章节下标（进度标记与跳章写回）。 */
    val durChapterIndex: Int,
    /** 本地书（true 时视为全部章节已缓存、不联网拉目录）。 */
    val isLocal: Boolean,
)

/**
 * 目录章节 UiModel（BookChapter → 展示字段快照）。
 * [fileName] 为章节缓存文件名（缓存标记匹配键）。
 */
@EInkImmutable
data class ChapterUiModel(
    val index: Int,
    val title: String,
    val url: String,
    val isVolume: Boolean,
    val fileName: String,
)
