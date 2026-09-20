package io.legado.app.eink.contract

import androidx.compose.runtime.Stable

/**
 * 选区语义与批注落库端口：宿主负责锚点构造（上下文与哈希）并写
 * book_marks，提供页面级书签 toggle。模块不复制这些规则。
 *
 * 契约 v2（0.7.0 起，状态机进签名）：笔记（划线/想法）是**同一条记录的
 * 两种状态**——转换只能走 [updateMarkingNote]（按 id，锚点不变），
 * [createMarking] 仅"新选区"入口可达，结构性杜绝宿主把转换实现成重复
 * 添加。类型恒由 note 派生：note 空白 = 划线（实线）；非空白 = 想法
 * （虚线）——契约不再有并列的 thought 字段。书签显示字段（章节名 + 页
 * 文本摘录）由 [ReaderPageBookmarkContent] 携带，宿主不自定显示语义。
 *
 * 可选端口（同 [EInkEngineRegistry.appUpdateEngine] 先例）：注册表缺失本端口
 * 时，模块降级——长按选择整体不启用（松手无动作、无操作条，选择交互无
 * 可用出路），下拉书签与顶栏书签钮隐藏，不做假死路径。
 *
 * 能力粒度（0.6.0 起）：注册 = 至少支持一种能力，宿主按实际支持覆写
 * 能力声明——仅支持划线/想法的宿主 [supportsPageBookmark] = false（下拉
 * 书签开关、顶栏书签钮、页角标、下拉手势隐藏），仅支持页面书签的宿主
 * [supportsMarkings] = false（长按选择不启用，操作条只余复制）。默认
 * 均为 true（两能力齐备，旧宿主零改动）。
 */
interface ReaderSelectionEngine {

    /**
     * 划线/想法能力：长按选择、选区操作条标记动作与点按标记浮条。
     *
     * 与 [MarksEngine.supportsMarkings] 同名不同义：此处是**阅读内保存**
     * 能力，彼处是**目录页列表/导出**能力；宿主通常两者一致声明，
     * 不一致时两处入口各自独立显隐（互不推导）。
     */
    val supportsMarkings: Boolean get() = true

    /** 页面书签能力：快速书签 toggle、下拉书签、顶栏书签钮、页角标。 */
    val supportsPageBookmark: Boolean get() = true

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

    /** 页文本摘录（快照行内 chunks 连接、行间 \n，与宿主 page.text 同构；
     *  图片页不含 \uFFFC 占位——模块无此字符语义）。 */
    val pageText: String,
)
