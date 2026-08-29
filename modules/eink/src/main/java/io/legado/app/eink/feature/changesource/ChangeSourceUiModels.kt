package io.legado.app.eink.feature.changesource

import io.legado.app.eink.arch.EInkImmutable
import io.legado.app.eink.engine.SearchResultHandle
import io.legado.app.eink.engine.SearchResultRef

/** 换源页当前书籍 UiModel。 */
@EInkImmutable
data class ChangeSourceBookUiModel(
    val bookUrl: String,
    val name: String,
    val author: String,
    /** 当前书源 origin（结果列表中标记“当前源”）。 */
    val origin: String,
)

/**
 * 换源搜索结果 UiModel：展示字段 + 引擎身份句柄（应用换源时回传）。
 * [primary] 为去重键（primaryStr）。
 */
@EInkImmutable
data class ChangeSourceResultUiModel(
    override val handle: SearchResultHandle,
    val bookUrl: String,
    val name: String,
    val author: String,
    val origin: String,
    val originName: String,
    /** 最新章节标题（搜索结果自带，无则为 null）。 */
    val latestChapter: String?,
    /** 去重键（primaryStr，展示“来源：”列与 LazyColumn key 共用）。 */
    val primary: String,
) : SearchResultRef
