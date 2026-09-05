package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/** 换源页当前书籍展示快照。 */
@EInkImmutable
data class ChangeSourceBookUiModel(
    val bookUrl: String,
    val name: String,
    val author: String,
    /** 当前书源 origin 标识（结果列表中标记「当前源」）。 */
    val origin: String,
)

/**
 * 换源搜索结果展示快照：展示字段 + 引擎身份句柄（应用换源时经
 * [ChangeSourceEngine.changeBookSource] 回传）。
 */
@EInkImmutable
data class ChangeSourceResultUiModel(
    val handle: SearchResultHandle,
    val bookUrl: String,
    val name: String,
    val author: String,
    val origin: String,
    val originName: String,
    /** 最新章节标题（搜索结果自带，无则 null）。 */
    val latestChapter: String?,
    /**
     * 去重键：宿主侧「同书同源」的判定值——模块用它合并跨源重复
     * 结果，并作为结果列表项的组合 key（必须同书内唯一且稳定）。
     */
    val deduplicationKey: String,
)
