package io.legado.app.eink.contract

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.runtime.Stable

/**
 * 排版引擎产物的页快照：模块阅读页画布的唯一绘制依据。
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
    /** 阅读进度文本（如「3/12」）。 */
    val readProgress: String,
    /** 标题行画笔规格（[ReaderPageLine.isTitle] 为 true 的行使用）。 */
    val titleSpec: ReaderPaintSpec,
    /** 正文行画笔规格。 */
    val contentSpec: ReaderPaintSpec,
    /** 文本行（非文本列如评论列不进入）。 */
    val lines: List<ReaderPageLine>,
    /** 图片槽位。 */
    val images: List<ReaderImageSlot>,
)

/**
 * 单文本行：[chunks] 的第 i 段绘制于横坐标 [x][i]，基线纵坐标 [baseY]。
 * [chunks] 与 [x] 等长。
 */
@Stable
class ReaderPageLine(
    val baseY: Float,
    /** true = 标题行，使用 [ReaderPageSnapshot.titleSpec] 的画笔规格。 */
    val isTitle: Boolean,
    val chunks: List<String>,
    val x: FloatArray,
)

/**
 * 图片槽位：页内一张图占用的行盒区域。
 *
 * [loader] 由宿主闭包提供（含位图解析与异常吞并，失败返回 null）；
 * 铺满/等比居中的矩形数学在模块画布完成（需位图实际尺寸，仅绘制期
 * 可得）。
 */
class ReaderImageSlot(
    val x0: Float,
    val x1: Float,
    val lineTop: Float,
    val lineBottom: Float,
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
    val textSizePx: Float,
    val letterSpacing: Float,
    val typeface: Typeface?,
    /** 可变字重设置（如 "'wght' 700"）；null = 未设置。加粗影响字形宽度，测量耦合。 */
    val fontVariationSettings: String?,
)
