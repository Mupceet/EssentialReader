package io.legado.app.eink.contract

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.runtime.Stable

/**
 * 排版引擎产物的模块侧快照（宿主桥接层映射 TextPage 而来）。
 *
 * 宿主把引擎排版结果映射为本类型（模块自有类型，零宿主类型渗透），
 * 模块画布据此绘制；上游 TextLine 的字段漂移（extraLetterSpacing 等）
 * 由各宿主映射器消化，模块只有一份绘制实现。
 *
 * 坐标均为引擎排版坐标系（px），绘制结果与 View 版 ContentTextView 一致。
 * 实例构建后引用不可变；映射方交出所有权后不得再修改内部数组与列表。每次 pageVersion 变化构建一次（无逐帧开销）。
 */
@Stable
class ReaderPageSnapshot(
    /** 页所属章节标题（页眉/状态展示）。 */
    val title: String,
    /** 阅读进度文本。 */
    val readProgress: String,
    /** 标题行画笔规格（TextLine.isTitle 行使用）。 */
    val titleSpec: ReaderPaintSpec,
    /** 正文行画笔规格。 */
    val contentSpec: ReaderPaintSpec,
    /** 文本行（非文本列如评论列不进入）。 */
    val lines: List<ReaderPageLine>,
    /** 图片槽位。 */
    val images: List<ReaderImageSlot>,
)

/** 单文本行：chunks[i] 绘制于 x[i]，基线 baseY；x 与 chunks 等长。 */
@Stable
class ReaderPageLine(
    val baseY: Float,
    /** true = 使用 [ReaderPageSnapshot.titleSpec] 的画笔规格。 */
    val isTitle: Boolean,
    val chunks: List<String>,
    val x: FloatArray,
)

/**
 * 图片槽位。
 *
 * [loader] 由宿主闭包提供（含位图解析与异常吞并，失败返回 null）；
 * 铺满/等比居中的矩形数学在模块画布完成（需位图实际尺寸，仅绘制期可得）。
 */
class ReaderImageSlot(
    val x0: Float,
    val x1: Float,
    val lineTop: Float,
    val lineBottom: Float,
    val lineHeight: Float,
    /** true = 图片独占整行（铺满行盒）；false = 行内嵌图（等比居中）。 */
    val fullLine: Boolean,
    val loader: (w: Int, h: Int) -> Bitmap?,
)

/**
 * 画笔渲染规格（仅测量耦合参数，与引擎 upStyle 写入画笔的对应属性一致）。
 *
 * 只携带影响字形宽度/行盒对齐的参数——快照列坐标是引擎按这些参数测量的，
 * 模块必须按同值绘制才不错位。字色不进规格：模块按 EInkTheme 主题色自涂；
 * 纯视觉效果（阴影/斜体/linearText 渲染开关）不跨桥——E-Ink 阅读不渲染
 * 这些效果，宿主配置了也不生效（既定产品取舍）。
 */
@Stable
class ReaderPaintSpec(
    val textSizePx: Float,
    val letterSpacing: Float,
    val typeface: Typeface?,
    /** 可变字重设置（如 "'wght' 700"）；null = 未设置。加粗影响字形宽度，测量耦合。 */
    val fontVariationSettings: String?,
)
