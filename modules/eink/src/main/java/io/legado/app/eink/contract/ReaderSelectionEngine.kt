package io.legado.app.eink.contract

import androidx.compose.runtime.Stable

/**
 * 选区语义与批注落库端口：宿主把模块选区解析为宿主语义字段（书签
 * chapterPos、锚点上下文与哈希）并写 bookmarks / book_marks 两表。
 * 模块不复制这些规则，只提交正文空间区间与选中文本。
 *
 * 可选端口（同 [EInkEngineRegistry.appUpdateEngine] 先例）：未注册 =
 * 宿主无批注能力（companion 宿主的合法状态），模块隐藏书签/笔记菜单项，
 * 长按选择与复制仍可用，不做假死路径。
 */
interface ReaderSelectionEngine {

    /**
     * 选区解析：按章节全文构造编辑弹层预填内容。
     *
     * @param chapterIndex 选区所在章节下标。
     * @param start 正文空间起始提示（选区含标题行时为选区内首个正文行位置；
     *   纯标题选区为 0）。
     * @param end 正文空间结束提示（语义同上；纯标题选区为 0）。
     * @param selectedText 选中文本（按行拼接、段落间隙以换行连接）。
     *   宿主在保存时以此在章节全文中定位（提示位置附近窗口搜索）。
     * @return null = 无会话书或选中文本为空（模块按选区失效处理并清选区）。
     */
    suspend fun resolveSelection(
        chapterIndex: Int,
        start: Int,
        end: Int,
        selectedText: String,
    ): ReaderSelectionDraft?

    /**
     * 保存书签（[ReaderSelectionCommit.bookmarkText]/[ReaderSelectionCommit.bookmarkContent]
     * 为用户编辑后的值）。宿主落库 bookmarks 表。
     * false = 落库失败，模块提示并保留弹层。
     */
    suspend fun saveBookmark(commit: ReaderSelectionCommit): Boolean

    /**
     * 保存笔记。样式固定实线（宿主桥写入 underlineMode=1、颜色取宿主
     * 划线回退默认 0xFF63C37D），note 为用户备注（可空串）。
     * 宿主落库 book_marks 后自行触发当前章重排（保持页内位置），新快照经
     * onContentUpdated 推送——模块不请求刷新。false = 落库失败。
     */
    suspend fun saveMarking(commit: ReaderSelectionCommit): Boolean

    /**
     * 删除标记。false = 删除失败（模块提示并保留现场）。
     * 宿主删除后触发当前章重排（同 saveMarking 推送路径）。
     */
    suspend fun deleteMarking(markingId: String): Boolean

    /**
     * 读取标记详情（点按想法时浮窗展示与写想法预填）。
     * null = 标记不存在（换源清理等，模块按标记失效处理，不弹浮窗）。
     */
    suspend fun findMarking(markingId: String): ReaderMarkingDetail?

    /**
     * 当前页书签 toggle（宿主快速书签语义：同页已有多条时删最近一条）。
     * 自动记录：页位置 + 页文本为标题，无编辑层。
     * 宿主落库后触发当前章重排（角标随新快照推送）。
     * null = 无会话书；true = 本次添加；false = 本次移除。
     */
    suspend fun togglePageBookmark(): Boolean?
}

/** 选区解析结果：两个编辑弹层的预填初值。 */
@Stable
class ReaderSelectionDraft(
    /** 选中文本（笔记预览展示；与 [bookmarkText] 同源，独立成字段以便宿主规则分化）。 */
    val selectedText: String,

    /** 书签 bookText 预填（宿主规则：选中文本）。 */
    val bookmarkText: String,

    /** 书签 content 预填（宿主规则：空）。 */
    val bookmarkContent: String,
)

/** 菜单动作提交载荷：选区坐标 + 用户编辑后的各字段。 */
@Stable
class ReaderSelectionCommit(
    /** 选区所在章节下标。 */
    val chapterIndex: Int,

    /** 章内字符区间 [start, end)（UTF-16、语义正文空间；纯标题选区为 0..0）。 */
    val start: Int,
    val end: Int,

    /** 选中文本（宿主用于定位与锚点构造）。 */
    val selectedText: String,

    /** 用户编辑后的书签 bookText。 */
    val bookmarkText: String,

    /** 用户编辑后的书签 content。 */
    val bookmarkContent: String,

    /** 想法内容（划线为空串）。 */
    val note: String = "",

    /** true = 想法（虚线样式）；false = 划线（实线样式）。 */
    val thought: Boolean = false,
)

/** 标记详情（点按浮窗与写想法预填）。 */
@Stable
class ReaderMarkingDetail(
    /** 划线选中的原文。 */
    val selectedText: String,

    /** 想法内容（划线为空串）。 */
    val note: String,

    /** true = 想法（虚线）；false = 划线（实线）。 */
    val thought: Boolean,
)
