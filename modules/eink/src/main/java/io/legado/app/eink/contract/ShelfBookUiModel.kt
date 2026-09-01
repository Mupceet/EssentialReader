package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书架条目的稳定 UiModel。
 *
 * 宿主 Book 实体是 48 个 `var` 字段的 Room 实体，Compose 推断为不稳定
 * 类型，条目永远无法跳过重组；任何一次 UiState 发射（刷新期间每本书
 * 约 3 次：updatingUrls 加/减 + bookDao.update）都会重组全部可见条目。
 *
 * 本 model 把条目渲染所需的字段一次性抽取为基元类型并标注
 * [@EInkImmutable][io.legado.app.eink.arch.EInkImmutable]：
 *  - `getUnreadChapterNum()` / `getRealAuthor()` / `getDisplayCover()` 等
 *    计算移出组合期，在数据发射时算一次（映射位于宿主 bridge）；
 *  - 条目参数全为稳定类型且相等时可跳过重组（数据未变的条目在刷新
 *    风暴与整页翻页中零重组）。
 */
@EInkImmutable
data class ShelfBookUiModel(
    /** 书籍唯一键：点击回调与刷新中（updating）标记的匹配键。 */
    val bookUrl: String,
    val name: String,
    /** 原始作者（导航到详情页使用，对齐 View 版 book.author）。 */
    val author: String,
    /** 展示作者（已清洗，getRealAuthor）。 */
    val displayAuthor: String,
    /** 封面地址（getDisplayCover：自定义封面优先，可为空走占位封面）。 */
    val coverUrl: String?,
    /** 书源 origin（封面请求自定义头用）。 */
    val origin: String,
    /** 当前进度章节标题。 */
    val durChapterTitle: String?,
    /** 最新章节标题。 */
    val latestChapterTitle: String?,
    /** 未读章节数（预计算，组合期不再算）。 */
    val unreadCount: Int,
    /** 本次刷新发现新章（lastCheckCount > 0，角标高亮）。 */
    val hasNewChapter: Boolean,
)
