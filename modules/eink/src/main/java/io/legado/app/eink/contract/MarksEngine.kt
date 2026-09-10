package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable
import kotlinx.coroutines.flow.Flow

/**
 * 书签/笔记端口：目录页书签 Tab 与笔记页的数据来源，含跳转目标解析
 * 与笔记导出。
 *
 * 列表按「书名+作者」跨源聚合（换源后仍可见）；跳转解析封装宿主的
 * 校验→本地重定位→确认三分支，模块不复制这些规则。
 *
 * 可选端口（同 [ReaderSelectionEngine] 先例）：注册表缺失本端口时，
 * 目录页不显示书签 Tab、笔记入口与 Note 路由不可达，不做假死路径。
 */
interface MarksEngine {

    /**
     * 订阅书籍的全部页面书签（bookmarks 表，跨源；按 chapterIndex、
     * chapterPos 升序）。宿主按 bookUrl 解析书籍失败返回空流。
     */
    fun observeBookmarks(bookUrl: String): Flow<List<BookmarkUiModel>>

    /**
     * 订阅书籍的全部划线/想法（book_marks 表，跨源；按 chapterIndex、
     * createdAt 升序）。宿主按 bookUrl 解析书籍失败返回空流。
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
    val chapterIndex: Int,
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
    val chapterIndex: Int,
    val chapterName: String,
    /** 划线选中原文。 */
    val selectedText: String,
    /** 想法内容（划线为空串）。 */
    val note: String,
    /** true = 想法（宿主从 styleJson 推导 underlineMode == 2）。 */
    val thought: Boolean,
    val createdAt: Long,
)

/** 跳转解析结果三分支。 */
sealed interface JumpResolution {

    /** 目标可靠，直接按坐标跳转。 */
    data class Located(val chapterIndex: Int, val chapterPos: Int) : JumpResolution

    /** 目标存疑：模块弹「仍跳转/取消」确认；[fallback] 为存储坐标，null 时确认后仅提示不跳。 */
    data class NeedConfirm(val message: String, val fallback: Located?) : JumpResolution

    /** 无法解析（记录不存在/数据损坏）：模块 toast [message]，不跳转。 */
    data class Failed(val message: String) : JumpResolution
}

/** 「仍跳转」确认弹层瞬态（目录页/笔记页共用；对齐宿主 PendingBookmarkTarget 语义）。 */
@EInkImmutable
data class PendingJumpConfirm(
    /** 展示给用户的确认文案（宿主拼装，含章节名等上下文）。 */
    val message: String,
    /** 确认后的跳转坐标（null = 仅提示不跳）。 */
    val fallback: JumpResolution.Located?,
)
