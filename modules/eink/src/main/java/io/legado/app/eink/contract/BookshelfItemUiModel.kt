package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书架条目展示快照。
 *
 * 宿主实现义务：把书籍实体一次性抽取为基元类型字段——展示类计算
 * （作者清洗、展示封面挑选、未读数）在数据发射时算一次，模块组合期
 * 不再做任何计算（条目在刷新风暴与整页翻页中零重组的前提）。
 */
@EInkImmutable
data class BookshelfItemUiModel(
    /** 书籍唯一键（点击回调与「更新中」标记的匹配键）。 */
    val bookUrl: String,
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
    val latestChapterTitle: String?,
    /** 未读章节数（预计算）。 */
    val unreadCount: Int,
    /** 本次目录刷新发现新章（角标高亮）。 */
    val hasNewChapter: Boolean,
)
