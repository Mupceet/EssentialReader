package io.legado.app.eink.contract

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.runtime.Stable

/**
 * 排版引擎产物的页快照：模块阅读页画布的唯一绘制依据。
 *
 * 渲染数据流：
 * ```text
 * 宿主排版引擎（页对象 / 文本行 / 列 / 图片）
 *        │ 宿主映射器（渲染侧唯一职责：字段漂移在此消化）
 *        ▼
 * ReaderPageSnapshot（坐标原样拷贝 + 画笔规格 + 图片槽位）
 *        │ 模块画布（ReaderPageSnapshotCanvas）
 *        ▼
 * 按 titleSpec/contentSpec 的画笔绘制行块；图片槽位按 loader 取位图
 * ```
 *
 * 宿主实现义务：把引擎排版结果（页对象）映射为本类型——这是宿主在
 * 渲染侧的唯一职责（模块自持画布，不注入宿主 View）。映射要点：
 *  - 坐标一律为引擎排版坐标系（px），原样拷贝不得换算；
 *  - 上游排版结构的字段漂移在映射器内消化，模块只有一份绘制实现；
 *  - 实例构建后引用不可变：映射方交出所有权后不得再修改内部数组
 *    与列表；每次排版版本变化构建一次（无逐帧开销）。
 */
@Stable
class ReaderPageSnapshot(
    /** 页所属章节标题（页眉/状态展示）。 */
    val title: String,

    /** 阅读进度文本（如「3/12」，页脚展示）。 */
    val readProgress: String,

    /** 标题行画笔规格（[ReaderPageLine.isTitle] 为 true 的行使用）。 */
    val titleSpec: ReaderPaintSpec,

    /** 正文行画笔规格（其余文本行使用）。 */
    val contentSpec: ReaderPaintSpec,

    /** 文本行（非文本列如评论列不进入快照）。 */
    val lines: List<ReaderPageLine>,

    /** 图片槽位（本页全部插图，按行盒区域定位）。 */
    val images: List<ReaderImageSlot>,

    /**
     * 当前页是否带有书签角标（宿主 page.decoration.bookmarkBadge 原样
     * 拷贝）。模块页角绘制角标，顶栏书签按钮的选中态据此推导——模块不
     * 自持「当前页是否有书签」状态。
     */
    val bookmarkBadge: Boolean = false,
)

/**
 * 单文本行：[chunks] 的第 i 段绘制于横坐标 [x][i]，基线纵坐标 [baseY]。
 * [chunks] 与 [x]、[chapterPositions] 等长。
 */
@Stable
class ReaderPageLine(
    /** 行基线纵坐标（px，引擎排版坐标系）。 */
    val baseY: Float,

    /** true = 标题行，使用 [ReaderPageSnapshot.titleSpec] 的画笔规格。 */
    val isTitle: Boolean,

    /** 行内文本段（按绘制顺序；段间断行由引擎测量决定）。 */
    val chunks: List<String>,

    /** 各文本段起始横坐标（px；与 [chunks] 一一对应）。 */
    val x: FloatArray,

    /**
     * 各文本段首字符的章内字符位置（UTF-16 索引）。
     *
     * 坐标系：正文行 = 语义正文空间（与宿主 TextProcessAnchor.chapterPosition
     * 同一坐标系，跨行间隙为段落分隔符/占位字符）；标题行 = 标题空间，
     * 无正文语义——选区含标题行时，章内区间须取选区内首个正文行位置。
     * 行内各段按段长连续，无需额外段内偏移。
     *
     * 宿主实现义务：从排版元素 chapterPosition 原样拷贝，不做换算。
     */
    val chapterPositions: IntArray,

    /**
     * 行盒顶/底边界（px，排版元素 bounds 原样拷贝）。选择命中、高亮带、
     * 把手与菜单锚定的几何依据；模块不得用字体度量估算行高。
     */
    val top: Float,
    val bottom: Float,

    /**
     * 本行划线/高亮装饰（缺省空，与选中态无关、常驻）。区间为行内拼接
     * 文本的 UTF-16 索引；同行相邻同 markingId 且同款样式的 run 已在映射侧
     * 合并（不同标记不并入同一 run）。
     */
    val decorations: List<ReaderDecorationRun> = emptyList(),
)

/**
 * 图片槽位：页内一张图占用的行盒区域。
 *
 * [loader] 由宿主闭包提供（含位图解析与异常吞并，失败返回 null）；
 * 铺满/等比居中的矩形数学在模块画布完成（需位图实际尺寸，仅绘制期
 * 可得）。
 */
class ReaderImageSlot(
    /** 行盒左边界（px）。 */
    val x0: Float,

    /** 行盒右边界（px）。 */
    val x1: Float,

    /** 行盒顶边界（px）。 */
    val lineTop: Float,

    /** 行盒底边界（px）。 */
    val lineBottom: Float,

    /** 行高（px，铺满/等比缩放计算用）。 */
    val lineHeight: Float,

    /** true = 图片独占整行（铺满行盒）；false = 行内嵌图（等比居中）。 */
    val fullLine: Boolean,

    /** 按目标尺寸解码位图；无法解码返回 null（模块渲染占位）。 */
    val loader: (width: Int, height: Int) -> Bitmap?,
)

/**
 * 画笔渲染规格：**仅测量耦合参数**——页快照中的列坐标是引擎按这些
 * 参数测量的，模块画布必须按同值绘制才不错位。
 *
 * 刻意排除项（既定产品取舍）：字色不进规格（模块按主题色自涂）；
 * 阴影/斜体等纯视觉效果不跨桥（E-Ink 阅读不渲染这些效果，宿主配置
 * 了也不生效）。
 */
@Stable
class ReaderPaintSpec(
    /** 正文字号（px——测量耦合：字形宽度与行盒由它决定）。 */
    val textSizePx: Float,

    /** 字距（px，引擎测量值原样透传）。 */
    val letterSpacing: Float,

    /** 字体（null = 引擎默认字体）；字形宽度测量耦合。 */
    val typeface: Typeface?,

    /** 可变字重设置（如 "'wght' 700"）；null = 未设置。加粗影响字形宽度，测量耦合。 */
    val fontVariationSettings: String?,
)

/**
 * 行内一段用户标记装饰。颜色不跨桥：模块按主题自涂（线=黑，高亮=灰底）。
 */
@Stable
class ReaderDecorationRun(
    /** 行内字符区间 [start, end)，按行内拼接文本的 UTF-16 索引。 */
    val start: Int,
    val end: Int,

    /**
     * 宿主 TextProcessStyle.underlineMode 原样透传
     * （1 实线 / 2 虚线 / 3 波浪 / 4 双线 / 5 SVG 花色；0 = 无下划线）。
     * 模块渲染：1/2/3/4 原生绘制，5 及未知值降级为实线（明示不支持花色）。
     */
    val underlineMode: Int,

    /** true = 背景高亮带（TextProcessStyle.bgColor 非空的标记）。 */
    val highlight: Boolean,

    /**
     * 标记身份（宿主 book_marks.id，含宿主高亮规则等非用户标记来源的
     * 合成 id）。点按命中 → 端口操作（findMarking/deleteMarking）的定位键。
     * 宿主实现义务：装饰合并按 markingId + 样式签名分组，不同标记不并入
     * 同一 run。
     */
    val markingId: String,
)
