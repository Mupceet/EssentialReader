package io.legado.app.eink.contract

import androidx.compose.runtime.Stable

/**
 * 选区语义与批注落库端口：宿主负责锚点构造（上下文与哈希）并写
 * book_marks，提供页面级书签 toggle。模块不复制这些规则。
 *
 * 可选端口（同 [EInkEngineRegistry.appUpdateEngine] 先例）：注册表缺失本端口
 * 时，模块降级——长按选择整体不启用（松手无动作、无操作条，选择交互无
 * 可用出路），下拉书签与顶栏书签钮隐藏，不做假死路径。
 */
interface ReaderSelectionEngine {

    /**
     * 保存标记（同锚点 upsert，创建/写想法/编辑想法复用）。
     * [ReaderSelectionCommit.thought] = false → 划线（实线，note 空串）；
     * true → 想法（虚线 + note）。虚线颜色取宿主默认，样式由宿主桥写入
     * TextProcessStyle（underlineMode 1/2；颜色固定纯黑，eink 与完整模式
     * 显示一致）。
     * 宿主落库后自行触发当前章重排（保持页内位置），新快照经
     * onContentUpdated 携带装饰推送——模块不请求刷新。
     * false = 落库失败（模块提示「保存失败」并恢复现场）。
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

/** 标记提交载荷。 */
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

    /** 想法内容（划线为空串）。 */
    val note: String,

    /** true = 想法（虚线样式）；false = 划线（实线样式）。 */
    val thought: Boolean,
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
