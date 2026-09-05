package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书架条目展示快照。
 *
 * 映射纪律（宿主构造义务）：
 * ```text
 * 宿主书籍实体（Room 等不稳定类型）
 *        │ 数据发射时一次映射（作者清洗/封面挑选/未读数预计算）
 *        ▼
 * BookshelfItemUiModel（全基元字段 + @EInkImmutable）
 *        │ 组合期
 *        ▼
 * 模块渲染（零计算；数据未变条目在刷新风暴与翻页中零重组）
 * ```
 */
@EInkImmutable
data class BookshelfItemUiModel(
    /** 书籍唯一键（点击回调与「更新中」标记的匹配键）。 */
    val bookUrl: String,

    /** 书名（条目标题展示）。 */
    val name: String,

    /** 原始作者（导航到详情页使用）。 */
    val author: String,

    /** 展示作者（已按书源规则清洗）。 */
    val displayAuthor: String,

    /** 展示封面地址（自定义封面优先；null/空走模块占位封面）。 */
    val coverUrl: String?,

    /** 书源 origin 标识（封面请求的源信息）。 */
    val origin: String,

    /** 当前阅读章节标题（进度摘要展示）。 */
    val currentChapterTitle: String?,

    /** 最新章节标题（有新章时高亮展示）。 */
    val latestChapterTitle: String?,

    /** 未读章节数（预计算）。 */
    val unreadCount: Int,

    /** 本次目录刷新发现新章（角标高亮）。 */
    val hasNewChapter: Boolean,
)
