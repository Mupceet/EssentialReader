package io.legado.app.eink.contract

import androidx.compose.runtime.Stable
import io.legado.app.eink.arch.EInkImmutable
import kotlinx.coroutines.flow.Flow

/**
 * 书签/笔记统一端口：阅读内选区批注（笔记状态机 + 页面书签 toggle）与
 * 目录页书签/笔记 Tab 的列表、跳转解析、导出。宿主负责锚点构造（上下文
 * 与哈希）并写 book_marks/bookmarks，模块不复制这些规则。
 *
 * 契约 v2（0.7.1 起，状态机进签名）：笔记（划线/想法）是**同一条记录的
 * 两种状态**——转换只能走 [updateMarkingNote]（按 id，锚点不变），
 * [createMarking] 仅"新选区"入口可达，结构性杜绝宿主把转换实现成重复
 * 添加。类型恒由 note 派生：note 空白 = 划线（实线）；非空白 = 想法
 * （虚线）——契约没有并列的 thought 字段。书签显示字段（章节名 + 页
 * 文本摘录）由 [ReaderPageBookmarkContent] 携带，宿主不自定显示语义。
 *
 * 能力粒度按**特性**不分表面（0.7.1 合并轮，全有全无）：[supportsMarkings]
 * 同时管阅读内保存（长按选择/选区操作条/点按标记）与目录页笔记 Tab/导出；
 * [supportsBookmarks] 同时管阅读内 toggle（下拉书签/顶栏钮/页角标——角标
 * 经页快照 bookmarkBadge 供给、模块无条件绘制，声明 false 时宿主不应
 * 下发）与目录页书签 Tab——不存在"阅读内能画线、目录页却无笔记 Tab"
 * 的分裂配置。两者皆 false 等价于不注册本端口。
 *
 * 可选端口（同 [EInkEngineRegistry.appUpdateEngine] 先例）：注册表缺失本端口
 * 时，模块降级——长按选择整体不启用（松手无动作、无操作条），下拉书签与
 * 顶栏书签钮隐藏，目录页不显示书签/笔记 Tab（只剩目录），不做假死路径。
 *
 * 列表按「书名+作者」跨源聚合（换源后仍可见）；跳转解析封装宿主的
 * 校验→本地重定位→确认三分支。
 */
interface MarksEngine {

    /** 笔记能力：阅读内划线/想法保存 + 目录页笔记 Tab 与导出。 */
    val supportsMarkings: Boolean get() = true

    /** 书签能力：阅读内页面书签 toggle + 目录页书签 Tab。 */
    val supportsBookmarks: Boolean get() = true

    // —— 阅读内：笔记状态机与页面书签 ——

    /**
     * 新建笔记（仅"新选区"入口可达；已有标记上的动作走 [updateMarkingNote]/
     * [deleteMarking]）。[ReaderSelectionCommit.note] 空白 = 划线（实线），
     * 非空白 = 想法（虚线）。样式由宿主桥写入 TextProcessStyle
     * （underlineMode 1/2；颜色固定纯黑，eink 与完整模式显示一致）。
     * 宿主落库后自行触发当前章重排（保持页内位置），新快照经
     * onContentUpdated 携带装饰推送——模块不请求刷新。
     * false = 落库失败（模块提示「保存失败」并恢复现场）。
     */
    suspend fun createMarking(commit: ReaderSelectionCommit): Boolean

    /**
     * 唯一的状态转换路径：按 id 改想法，锚点与选区不变（宿主不做重定位）。
     * note 空白 → 划线（实线）；非空白 → 想法（虚线）。
     * null = 标记不存在（换源清理等，模块按标记失效处理）；false = 更新失败；
     * true = 成功。宿主更新后触发当前章重排（同 createMarking 推送路径）。
     */
    suspend fun updateMarkingNote(markingId: String, note: String): Boolean?

    /**
     * 删除标记。false = 删除失败（模块提示并保留现场）。
     * 宿主删除后触发当前章重排（同 createMarking 推送路径）。
     */
    suspend fun deleteMarking(markingId: String): Boolean

    /**
     * 读取标记详情（点按想法时浮窗展示与写想法预填）。
     * null = 标记不存在（换源清理等，模块按标记失效处理，不弹浮窗）。
     */
    suspend fun findMarking(markingId: String): ReaderMarkingDetail?

    /**
     * 当前页书签 toggle（宿主快速书签语义：同页已有多条时删最近一条）。
     * 显示字段由 [content] 携带（模块从当前页快照组装）：宿主原样落库——
     * 对自家渲染占位符的清理属存储规范化，不构成显示语义；同页判定（页
     * 正文区间）与「删最近一条」按宿主分页事实。自动记录：页位置 +
     * content 引用文本，无编辑层。宿主落库后触发当前章重排（角标随新快照
     * 推送）。null = 无会话书/当前页无法定位；true = 本次添加；false =
     * 本次移除。
     */
    suspend fun togglePageBookmark(content: ReaderPageBookmarkContent): Boolean?

    // —— 目录页：列表 / 跳转 / 导出 ——

    /**
     * 订阅书籍的全部页面书签（bookmarks 表，跨源；按 chapterIndex、
     * chapterPos 升序）。宿主按 bookUrl 解析书籍失败返回空流。
     */
    fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>>

    /**
     * 订阅书籍的全部划线/想法（book_marks 表，跨源；按 chapterIndex、
     * **章内正文本位置**升序，同位置按 createdAt）。宿主按 bookUrl 解析书籍
     * 失败返回空流。
     *
     * 用正文本位置而非创建时间：笔记卡按「读到的先后」排列才与正文顺序一致
     * （补记的划线若按创建时间会排在章末，与阅读顺序割裂）。位置取自锚点
     * [TextProcessAnchor.chapterPosition]。
     */
    fun observeMarkings(bookUrl: String): Flow<List<MarkingUiModel>>

    /**
     * 解析书签跳转目标：复用宿主校验（源指纹 + 章节标题比对）。
     * Match → [JumpResolution.Located]；不 Match → [JumpResolution.NeedConfirm]
     * （fallback = 存储坐标，对齐宿主「仍跳转」语义）；书签/书籍不存在 →
     * [JumpResolution.Failed]。不执行跳转、不写进度。
     */
    suspend fun resolveBookmarkJump(bookmarkId: Long): JumpResolution

    /**
     * 解析划线/想法跳转目标：校验不 Match 时先本地重定位（选中文本 +
     * 前后文评分，仅本地已缓存章节，不发起网络）；重定位成功 → 重定位
     * 坐标；失败 → [JumpResolution.NeedConfirm]（fallback = 存储坐标）。
     * 标记不存在（换源清理等）→ [JumpResolution.Failed]。
     */
    suspend fun resolveMarkingJump(markingId: String): JumpResolution

    /**
     * 导出当前书全部划线/想法为 Markdown 写入 SAF uri。
     * @return false = 书籍不存在或无笔记或写失败（模块提示「导出失败」）。
     */
    suspend fun exportMarkingsMarkdown(bookUrl: String, uri: String): Boolean
}

/** 目录页书签 Tab 条目快照（全基元）。 */
@EInkImmutable
data class BookmarkUiModel(
    /** 书签标识（= 宿主 Bookmark.time 主键），跳转解析回传。 */
    val id: Long,
    /** 章节下标（0-based）。 */
    val chapterIndex: Int,
    /** 章节标题（条目次级信息）。 */
    val chapterName: String,
    /** 页面文本摘录（快速书签自动记录）。 */
    val bookText: String,
    /** 笔记文本（完整模式编辑过才非空；eink 快速书签恒为空串）。 */
    val content: String,
)

/** 笔记页条目快照（全基元）。 */
@EInkImmutable
data class MarkingUiModel(
    /** 标记标识（= 宿主 BookMarking.id），跳转解析回传。 */
    val id: String,
    /** 章节下标（0-based）。 */
    val chapterIndex: Int,
    /** 章节标题（条目次级信息）。 */
    val chapterName: String,
    /**
     * 章内正文本位置（锚点 `TextProcessAnchor.chapterPosition`）：
     * 笔记卡排序依据（宿主按此升序下发，模块按原序渲染分组）。
     */
    val chapterPos: Int = 0,
    /** 划线选中原文。 */
    val selectedText: String,
    /** 想法内容（划线为空串）。 */
    val note: String,
    /** true = 想法（宿主从 styleJson 推导 underlineMode == 2）。 */
    val thought: Boolean,
    /** 创建时间戳（毫秒）。 */
    val createdAt: Long,
)

/** 跳转解析结果三分支。 */
sealed interface JumpResolution {

    /** 目标可靠，直接按坐标跳转。 */
    data class Located(val chapterIndex: Int, val chapterPos: Int) : JumpResolution

    /** 目标存疑：模块弹「仍跳转/取消」确认；[fallback] 为存储坐标，null 时确认后仅关闭弹层不跳转。 */
    data class NeedConfirm(val message: String, val fallback: Located?) : JumpResolution

    /** 无法解析（记录不存在/数据损坏）：模块 toast [message]，不跳转。 */
    data class Failed(val message: String) : JumpResolution
}

/** 笔记提交载荷（仅新建路径；已有标记的更新走 updateMarkingNote，不重提交选区）。 */
@Stable
class ReaderSelectionCommit(
    /** 选区所在章节下标。 */
    val chapterIndex: Int,

    /** 章内字符区间 [start, end)（UTF-16、语义正文空间）。 */
    val start: Int,
    val end: Int,

    /** 选中文本（按行拼接、段落间隙以换行连接；跨页选区为跨页累计拼接）。
     *  宿主保存时以其在章节全文窗口搜索定位。 */
    val selectedText: String,

    /** 初始想法内容：空白 = 划线（实线）；非空白 = 想法（虚线）。 */
    val note: String,
)

/** 标记详情（点按浮窗与写想法预填；类型由 note 派生，不再有 thought 字段）。 */
@Stable
class ReaderMarkingDetail(
    /** 划线选中的原文。 */
    val selectedText: String,

    /** 想法内容（空白 = 划线/实线；非空白 = 想法/虚线）。 */
    val note: String,
)

/** 页面书签显示载荷（模块从当前页快照组装，宿主原样落库）。 */
@Stable
class ReaderPageBookmarkContent(
    /** 章节标题（= 页快照 title，与宿主 page.chapterTitle 同源）。 */
    val chapterName: String,

    /** 页文本摘录（行内 chunks 连接；行间按 ReaderPageLine.paragraphBreaksAfter
     *  插段落换行——同段折行无换行、段末一个、空行累加，与宿主 page.text
     *  段落边界口径同构；图片页不含 \uFFFC 占位——模块无此字符语义）。 */
    val pageText: String,
)
