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

    /** 文本行（纯装饰性非文本元素不进入快照；图片经 [images] 槽位）。 */
    val lines: List<ReaderPageLine>,

    /** 图片槽位（本页全部插图，按行盒区域定位）。 */
    val images: List<ReaderImageSlot>,

    /**
     * 当前页是否带有书签角标（宿主 page.decoration.bookmarkBadge 原样
     * 拷贝）。模块页角绘制角标，顶栏书签按钮的选中态据此推导——模块不
     * 自持「当前页是否有书签」状态。
     */
    val bookmarkBadge: Boolean = false,

    /**
     * 本页内容填充比 (0, 1]：本页最低内容盒相对内容区高度的占比，1 = 满页。
     * 宿主映射义务：由分页几何计算——底部剩余不足一行正文行高视为满页
     * （与分页器「下一行放不下即换页」判据同源），无内容元素的空页填 1.0；
     * 旧宿主缺省 1.0 = 满页全时长。模块不自行做几何推断，唯一消费方是
     * 自动翻页单页时长缩放（ReaderViewModel.autoPageDurationMillis）。
     */
    val contentFillRatio: Float = 1f,
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
     * 本行之后的段落边界数（契约 v2 书签载荷拼装依据，与宿主 page.text
     * 段落边界口径同构）：0 = 下一行是同段折行续行；1 = 段落在本行结束；
     * 空行/占位块每个累加 1（如空行分隔 = 2）。末行的值不参与拼装。
     * 宿主映射器实现义务：从排版块结构填充；缺省 0（旧宿主摘录退化为
     * 无段落分隔）。
     */
    val paragraphBreaksAfter: Int = 0,

    /**
     * 本行划线/高亮装饰（缺省空，与选中态无关、常驻）。区间为行内拼接
     * 文本的 UTF-16 索引；同行相邻同 markingId 且同款样式的 run 已在映射侧
     * 合并（不同标记不并入同一 run）。
     */
    val decorations: List<ReaderDecorationRun> = emptyList(),

    /**
     * 行内逐段画笔覆盖（与 [chunks] 平行；元素为 null 或列表短于 chunks
     * = 该段沿用行规格 [ReaderPageSnapshot.titleSpec]/[contentSpec]）。
     *
     * 来源是宿主高亮规则的字体覆盖（字号差/字重/自定义字体/字色）——
     * 引擎按这些参数**测量段宽**，模块若按行规格绘制，字形画不满或溢出
     * 引擎量出的槽位（段间出现多余空隙）。覆盖项由宿主映射器计算，模块
     * 只按值建画笔，不做字体加载与差值推断。
     */
    val chunkOverrides: List<ReaderChunkPaintOverride?> = emptyList(),
)

/**
 * 图片槽位：页内一张图占用的行盒区域。
 *
 * [loader] 由宿主闭包提供（含位图解析与异常吞并，失败返回 null）；
 * 铺满/等比居中的矩形数学在模块画布完成（需位图实际尺寸，仅绘制期
 * 可得）。
 *
 * [source]/[action] 为交互元数据（与几何测量无关）：书源可在正文图片
 * 地址尾部携带 `,{"click":"…JS…"}` 选项——段评气泡即「行内小图 +
 * click 脚本」的用法。点按命中带 [action] 的槽位时，模块经
 * [ReaderEngine.dispatchImageAction] 上抛宿主执行（脚本求值与弹层
 * 都是宿主能力）；无 [action] 的图片不参与点按命中。
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

    /**
     * 图片来源原样字符串（宿主排版元素 source 透传，含地址尾部可能的
     * `,{...}` 选项——与完整模式点击分派注入 JS 的 result 绑定同值）。
     */
    val source: String = "",

    /**
     * 点击动作脚本（宿主图片选项 `click` 键透传；null/空 = 无点击语义，
     * 槽位不参与点按命中）。
     */
    val action: String? = null,
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
 * 行内一段的画笔覆盖：全部字段 nullable，null = 沿用行规格对应项
 * （[ReaderPaintSpec] 的 textSizePx/typeface/fontVariationSettings 与模块
 * 主题文字色）。缺省实例（全 null）等价于无覆盖。
 *
 * 取值均为**引擎测量同源**参数——宿主映射器用引擎侧同一字体工厂解析
 * typeface（含字重映射）、同一字号值；模块按值绘制才能与段宽测量逐像素
 * 对齐。字色 [colorArgb] 是宿主灰度换算后的绝对灰（保留 alpha）——彩色
 * 规则色在墨水屏上的等价表达，宿主完成换算、模块不做色彩推断；null =
 * 模块主题文字色（既有口径）。
 */
@Stable
class ReaderChunkPaintOverride(
    /** 覆盖字号（px，绝对值非增量）。null = 行规格字号。 */
    val textSizePx: Float? = null,

    /** 覆盖字体（null = 行规格字体）。字形宽度测量耦合。 */
    val typeface: Typeface? = null,

    /** 覆盖可变字重设置（如 "'wght' 700"）。null = 行规格设置。 */
    val fontVariationSettings: String? = null,

    /** 覆盖字色（宿主灰度换算后的 ARGB 灰）。null = 模块主题文字色。 */
    val colorArgb: Int? = null,

    /**
     * 合成斜体倾斜（宿主引擎 `textSkewX` 同值，如 -0.25f）。null = 不倾斜。
     *
     * 例外说明：行规格层面斜体仍不跨桥（基线样式无斜体），但高亮规则的
     * isItalic 需要在墨水屏上表达——skew 只剪切成字形轮廓、不改步进宽
     * （引擎测量与模块测量都不受影响），故作为覆盖项无对齐风险地跨桥。
     */
    val skewX: Float? = null,
)

/**
 * 行内一段用户标记装饰。颜色**灰度跨桥**：宿主把规则/标记的彩色换算为
 * 灰（亮度公式、保留 alpha）后随 run 透传，模块按值绘制；null = 模块按
 * 主题自涂（线=主题文字色，高亮=主题高亮色，兼容旧宿主）。
 */
@Stable
class ReaderDecorationRun(
    /** 行内字符区间 [start, end)，按行内拼接文本的 UTF-16 索引。 */
    val start: Int,
    val end: Int,

    /**
     * 宿主 TextProcessStyle.underlineMode 原样透传
     * （1 实线 / 2 虚线 / 3 波浪 / 4 双线 / 5 SVG 花色 / 6 删除线 /
     * 7 荧光半高带；0 = 无下划线）。
     * 模块渲染：1/2/3/4/6/7 按语义绘制（6 = 行盒 52% 高度横线压过字形，
     * 7 = 行盒下半高亮带、色取 [underlineColorArgb]），5 及未知值降级为
     * 实线（明示不支持花色）。
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

    /**
     * 下划线绘制几何（px，宿主 `ReaderUnderline` 同源透传；null = 宿主未提供，
     * 模块按内置默认绘制）。
     *
     * 为什么透传而不是模块自算：完整模式的下划线 y = **行盒下沿 + offsetPx**，
     * 线宽 / 虚线节距 / 波浪幅度波长 / 双线间距都来自宿主样式；模块若自拟
     * 公式（早期为「基线下方 12% 行盒高」）会与宿主有明显落差——真机反馈
     * 「eink 画的线离文本底部太近」。透传后两种模式逐像素对齐，宿主改样式
     * 常量或高亮规则自带线宽也能如实呈现。
     */
    val underline: ReaderUnderlineGeometry? = null,

    /** 高亮带颜色（宿主灰度换算后；null = 模块主题高亮色）。 */
    val highlightColorArgb: Int? = null,

    /** 下划线/删除线/荧光带颜色（宿主灰度换算后；null = 模块主题文字色）。 */
    val underlineColorArgb: Int? = null,
)

/**
 * 下划线几何（px）：与宿主 `ReaderUnderline` 一一对应，模块不再自拟参数。
 *
 * 坐标口径与宿主画布一致——同一页快照里元素 bounds / 行盒都是同一像素空间，
 * 因此 `y = line.bottom + offsetPx` 在两种模式下落在同一位置。
 */
data class ReaderUnderlineGeometry(
    /** 线宽（宿主 TextProcessStyle.underlineWidth）。 */
    val widthPx: Float,
    /** 行盒下沿之下的偏移（宿主 TextProcessStyle.underlineOffset；可负）。 */
    val offsetPx: Float,
    /** 虚线墨长。 */
    val dashOnPx: Float,
    /** 虚线空长。 */
    val dashOffPx: Float,
    /** 波浪幅度（±）。 */
    val waveAmplitudePx: Float,
    /** 波浪半波长（宿主 createWavePath 的 quad 段长）。 */
    val waveLengthPx: Float,
    /** 双线第二根相对第一根的额外间距（宿主再叠加一个线宽）。 */
    val doubleLineGapPx: Float,
)
