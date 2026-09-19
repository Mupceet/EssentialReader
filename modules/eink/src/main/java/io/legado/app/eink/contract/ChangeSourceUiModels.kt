package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 换源页当前书籍展示快照。
 *
 * 映射纪律：
 * ```text
 * 宿主书籍实体（阅读会话书优先，否则存储读取）
 *        │ currentReadingBook 时一次映射
 *        ▼
 * ChangeSourceBookUiModel（全基元字段；origin 用于标记「当前源」）
 * ```
 */
@EInkImmutable
data class ChangeSourceBookUiModel(
    /** 书籍唯一键。 */
    val bookUrl: String,

    /** 书名（换源搜索的匹配词）。 */
    val name: String,

    /** 作者（换源搜索的匹配词）。 */
    val author: String,

    /** 当前书源 origin 标识（结果列表中标记「当前源」）。 */
    val origin: String,
)

/**
 * 换源搜索结果展示快照：展示字段 + 引擎身份句柄（应用换源时经
 * [ChangeSourceEngine.changeBookSource] 回传）。
 *
 * 映射纪律：
 * ```text
 * 宿主搜索结果实体（单源一批）
 *        │ searchSourceBook 返回时逐条映射（含 authorRegex 清洗）
 *        ▼
 * ChangeSourceResultUiModel（模块按 deduplicationKey 合并去重）
 * ```
 */
@EInkImmutable
data class ChangeSourceResultUiModel(
    /** 搜索结果的引擎身份（应用换源时回传宿主解包）。 */
    val handle: SearchResultHandle,

    /** 该源上的书籍唯一键。 */
    val bookUrl: String,

    /** 书名。 */
    val name: String,

    /** 作者（已按书源规则清洗）。 */
    val author: String,

    /** 书源 origin 标识。 */
    val origin: String,

    /** 书源显示名。 */
    val originName: String,

    /** 最新章节标题（搜索结果自带，无则 null）。 */
    val latestChapter: String?,

    /**
     * 去重键：宿主侧「同书同源」的判定值——模块用它合并跨源重复
     * 结果，并作为结果列表项的组合 key（必须同书内唯一且稳定）。
     */
    val deduplicationKey: String,
)
