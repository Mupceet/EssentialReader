package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书籍详情展示快照。
 *
 * 映射纪律：
 * ```text
 * 宿主书籍实体
 *        │ 映射期一次计算（作者清洗 / 展示封面挑选 / 简介清洗）
 *        ▼
 * BookDetailUiModel（全基元字段；引擎身份走 BookHandle，不进本类型）
 * ```
 *
 * 宿主实现义务：展示类计算在映射时一次完成——[displayAuthor] 与
 * [coverUrl] 是宿主规则下的最终值，模块不再加工；[intro]
 * 无简介时传 null；[displaySource] 同为映射期终值（本地书传 null）。
 */
@EInkImmutable
data class BookDetailUiModel(
    /** 书籍唯一键。 */
    val bookUrl: String,

    /** 书名。 */
    val name: String,

    /** 展示作者（已按书源规则清洗）。 */
    val displayAuthor: String,

    /** 展示封面地址（自定义封面优先，映射期终值；null/空走模块占位封面；0.8.0 起对齐全契约 coverUrl 命名）。 */
    val coverUrl: String?,

    /** 展示简介（映射期已清洗，无简介为 null；0.8.0 起对齐全契约 intro 命名）。 */
    val intro: String?,

    /**
     * 展示书源名（originName 优先、回落书源表名与 origin；本地书/解析不出为 null）。
     * 详情页作者行之下居中展示（带下划线，点击进入换源）；null 时该行不出现。
     */
    val displaySource: String?,

    /** 最新章节标题（无则 null）。 */
    val latestChapterTitle: String?,

    /** 当前阅读章节标题（阅读进度摘要展示）。 */
    val currentChapterTitle: String?,

    /** 书源 origin 标识。 */
    val origin: String,
)

/**
 * 书籍详情的解析结果（[BookDetailEngine.findBook] 返回；0.8.0 起以
 * 具名类型取代裸 `Pair<BookHandle, …>`）。
 */
@EInkImmutable
class BookDetailResolved(
    /** 书籍的引擎句柄（预取/加架/移架操作回传；可能被预取更新）。 */
    val handle: BookHandle,

    /** 展示模型。 */
    val model: BookDetailUiModel,
)
