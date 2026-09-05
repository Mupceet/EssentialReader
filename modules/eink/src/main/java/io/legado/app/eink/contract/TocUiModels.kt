package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 目录页书籍信息展示快照（宿主书籍实体 → 展示字段）。
 * 计算类字段（如当前章节下标）在映射时一次取值。
 */
@EInkImmutable
data class TocBookUiModel(
    val bookUrl: String,
    val name: String,
    /** 当前阅读章节下标（目录页进度标记与跳章写回的初始值）。 */
    val currentChapterIndex: Int,
    /** true = 本地导入书（视为全部章节已缓存、不联网拉目录）。 */
    val isLocal: Boolean,
)

/**
 * 目录章节展示快照。
 *
 * [fileName] 是宿主章节缓存体系的文件名标识——目录页用它匹配
 * [TocEngine.cachedChapterFileNames] 的结果渲染缓存标记。
 */
@EInkImmutable
data class ChapterUiModel(
    /** 章节下标（0-based，跳章写回进度时使用）。 */
    val index: Int,
    val title: String,
    /** 章节正文地址（宿主缓存体系的定位键之一）。 */
    val url: String,
    /** true = 卷标题行（展示分组样式，非可读章节）。 */
    val isVolume: Boolean,
    /** 章节缓存文件名（缓存标记匹配键）。 */
    val fileName: String,
)
