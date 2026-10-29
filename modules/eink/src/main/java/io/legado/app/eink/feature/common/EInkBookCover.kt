package io.legado.app.eink.feature.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.widget.EInkAsyncImage
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 封面尺寸（与 View 版 item_bookshelf_list.xml 一致：66dp × 90dp）。 */
val EInkCoverWidth = 66.dp
val EInkCoverHeight = 90.dp

/**
 * 列表模式封面基准高：66×90 在列表模式下观感偏小，2026-09 由用户决策
 * 抬至 120dp（默认字体倍率下的下限）；宽度由调用方按 66:90 比例推导
 * （120 × 66/90 = 88dp）。仅书架列表消费：网格模式只用 66:90 比例、
 * 搜索结果维持 66×90 不变。
 */
val EInkListCoverHeight = 120.dp

/**
 * 封面目标像素尺寸（Dp → px 向下取整）。
 *
 * 单点换算供三处共用：显示（[EInkBookCover]）、预取（[prefetchCovers]）、
 * 同步命中（[EInkAsyncImage][io.legado.app.eink.designsystem.widget.EInkAsyncImage]
 * 的内存缓存键）。像素值必须逐字节一致，预取写入的缓存项才能被显示路径
 * 以同一键同步命中。
 */
internal fun coverTargetSizePx(width: Dp, height: Dp, density: Density): Pair<Int, Int> =
    with(density) { width.toPx().toInt() to height.toPx().toInt() }

/**
 * 构建封面请求：显示与预取共用的唯一构造点。
 *
 * 二者必须产出完全相同的请求（含显式 `memoryCacheKey`），预取写入的内存
 * 缓存项才能被显示路径同步命中。显式键带目标尺寸：Coil 默认键不含尺寸
 * （无 transformations 时），列表 66dp 与网格 ~96dp 两种尺寸会写同一条目
 * 互相顶替（较小位图被 INEXACT 校验拒绝后又重抓）；分尺寸分键后互不干扰。
 *
 * http(s) 封面 data 为 [EInkCoverData]（经端口抓取字节），其余形态
 * （data: 内联/本地路径）原样交给模块图片栈内置 fetcher。目标尺寸由
 * 模块自设（[size]），不再跨桥。
 */
internal fun buildEInkCoverRequest(
    context: Context,
    url: String,
    sourceOrigin: String?,
    widthPx: Int,
    heightPx: Int,
): ImageRequest = ImageRequest.Builder(context)
    .data(coverRequestData(url, sourceOrigin))
    .size(widthPx, heightPx)
    .memoryCacheKey("eink-cover|$url|${widthPx}x$heightPx")
    .build()

/**
 * E-Ink 书籍封面（书架/搜索结果等列表项复用）。
 *
 * Feature 组件（迁移计划 T3.3 下沉）：依赖 [EInkEngineRegistry] 业务
 * 端口（CoverEngine），不属于 Design System（规范 §44），供书架/搜索/
 * 详情等多个 feature 共用，故落在 bookshelf 包。
 *
 * 封面加载统一通过 [EInkAsyncImage] 进入模块自有 ImageLoader
 * （[einkImageLoader]），不在 Compose 侧做 bitmap copy 或额外 LruCache；
 * 内存缓存与请求生命周期由模块图片栈管理，http(s) 封面字节经
 * CoverEngine 端口回到宿主管线（防盗链头/解密/持久缓存在宿主侧），
 * 非 http(s) 封面由内置 fetcher 直接解析。
 *
 * 封面统一 [EInkShapes.medium]（4dp）圆角裁剪，对齐 MD3 主工程
 * CoilBookCover 的 RoundedCornerShape(4.dp)；文字占位封面同理被裁剪，
 * 保持加载前后轮廓一致。阴影不做（E-Ink 零阴影规范，主工程亦为可选）。
 *
 * 无封面地址、用户开启“使用默认封面”、加载中或加载失败时，显示文字占位封面。
 */
@Composable
fun EInkBookCover(
    url: String?,
    name: String,
    author: String? = null,
    modifier: Modifier = Modifier,
    width: Dp = EInkCoverWidth,
    height: Dp = EInkCoverHeight,
    sourceOrigin: String? = null,
) {
    // 单点 clip：加载图与占位共用同一圆角轮廓（draw 阶段裁剪，静态单帧）
    val coverModifier = modifier.clip(EInkShapes.medium)
    if (url.isNullOrBlank() || EInkEngineRegistry.globalSettings.useDefaultCover) {
        EInkDefaultCover(name = name, author = author, modifier = coverModifier)
        return
    }

    val density = LocalDensity.current
    val (targetWidthPx, targetHeightPx) = coverTargetSizePx(width, height, density)

    val context = LocalContext.current

    // 请求实例按入参 remember：重组间保持同一 ImageRequest，避免请求被
    // 反复重建。过渡动画零配置——模块自有 loader 即 Coil 默认（无 crossfade）
    val model: Any = remember(context, url, sourceOrigin, targetWidthPx, targetHeightPx) {
        buildEInkCoverRequest(context, url, sourceOrigin, targetWidthPx, targetHeightPx)
    }

    // 占位 lambda 稳定实例（键随 name/author 变化）：每次重组新建会让
    // EInkAsyncImage 的占位内容反复换实例。占位内容用 fillMaxSize（单例
    // Modifier）填满 EInkAsyncImage 自身边界，与外层传入的尺寸修饰效果一致
    val placeholderContent: @Composable () -> Unit = remember(name, author) {
        { EInkDefaultCover(name = name, author = author, modifier = Modifier.fillMaxSize()) }
    }

    EInkAsyncImage(
        model = model,
        contentDescription = name,
        modifier = coverModifier,
        imageLoader = einkImageLoader(context),
        loading = placeholderContent,
        failure = placeholderContent,
    )
}

/**
 * 文字占位封面（方案 A「题签」式）：无封面时保证封面区域正常显示。
 *
 * 版式取自中式线装书题签：封面整体为纸面（surface），左上角一枚竖排
 * 题签只承载书名文字——书名逐字下排、列满左移换列（最多两列，超长
 * 截断补「…」），【…】标签以小字落在书名列左侧（引题位）；右下角
 * 落款行——作者横排右对齐、末尾钤书名首字藏书印
 * （onSurface 印底、surface 印文），仿落款钤印收笔。左上题签、右下
 * 落款对角占位；文字量被题签与截断天然封顶，不会重现旧版“逐字换列
 * 塞满整框”。
 *
 * 题签高 0.72H、上边距 0.07H、左缘对齐 0.085W 边距并向右扩展，宽度
 * 由实际列数决定；每列 7 字（题签内容高 / 1.12 倍书名字号，66:90 恒定
 * 宽高比下与封面尺寸无关），列表 88×120dp 与网格 120×163dp 同为两列
 * 14 字上限，搜索 66×90dp 同比例缩放。字号全部取封面宽比例（书名
 * 0.11W、标签 0.06W、作者 0.09W、印文 0.10W）；竖排字距 1.12 倍字号、
 * 列距 1.55 倍字号（CJK 字面 ≈0.86 字号，视觉留隙与效果图一致）。
 *
 * 字体与界面排版同源：直绘文本经 [rememberTextMeasurer] 走 Compose 文本
 * 排版，消费 [EInkTheme.typography] 挂载的宿主全局 UI 字体
 * （[io.legado.app.eink.contract.EInkHostActivity.uiFontFamily] 注入，
 * 默认平台字体），与界面 Text 同一套字体解析，天然跟随字体切换；字重
 * 按排版系统角色——书名/印文 Bold（title 角色）、标签/作者 Normal
 * （body 角色）。颜色全部由主题下发（纸面 surface、墨字 onSurface、
 * 辅助 onSurfaceVariant、界线 outline），灰阶对比靠“近黑字 + 近白纸 +
 * 发丝界线”，不用中灰小字（E-Ink 低对比发虚）。不引入宿主的阴影/
 * 描边/自定义字色设置（E-Ink 零阴影，主题单一下发）。
 *
 * 文本解析与截断在 [parseEInkCoverText] / [truncateEInkCoverTitle]
 * （纯函数，单测锚定 AO3 真实书目）；本函数只按上述比例绘制。
 *
 * 形状与外层 clip（EInkBookCover 的 coverModifier，medium）一致：内层
 * 半径小于外层掩模时，1dp 边框的四角弧线会被掩模切掉，视觉上四角缺损。
 */
@Composable
private fun EInkDefaultCover(
    name: String,
    author: String?,
    modifier: Modifier = Modifier,
) {
    val colors = EInkTheme.colorScheme
    // 与界面排版同源的字体族：排版系统全部样式挂载宿主 uiFontFamily
    //（默认平台字体），封面直绘消费同一族，保证界面字体统一
    val coverFontFamily = EInkTheme.typography.bodyMedium.fontFamily
    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier = modifier
            .background(color = colors.surface, shape = EInkShapes.medium)
            .border(width = 1.dp, color = colors.outline, shape = EInkShapes.medium)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val viewWidth = size.width
                    val viewHeight = size.height
                    if (viewWidth <= 0f || viewHeight <= 0f) {
                        return@drawWithCache onDrawBehind { }
                    }

                    val parsed = parseEInkCoverText(name)

                    // 题签字号与字距/列距：全部随封面宽等比缩放；字号经
                    // 本作用域 Density 转 sp，与 textMeasurer 密度同源
                    val titleTextSize = viewWidth * 0.11f
                    val tagTextSize = viewWidth * 0.06f
                    val authorTextSize = viewWidth * 0.09f
                    val sealTextSize = viewWidth * 0.10f
                    val titleStyle = TextStyle(
                        color = colors.onSurface,
                        fontFamily = coverFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = titleTextSize.toSp(),
                    )
                    val tagStyle = TextStyle(
                        color = colors.onSurfaceVariant,
                        fontFamily = coverFontFamily,
                        fontSize = tagTextSize.toSp(),
                    )
                    val authorStyle = TextStyle(
                        color = colors.onSurfaceVariant,
                        fontFamily = coverFontFamily,
                        fontSize = authorTextSize.toSp(),
                    )
                    val sealStyle = TextStyle(
                        color = colors.surface,
                        fontFamily = coverFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = sealTextSize.toSp(),
                    )
                    val titlePitchY = titleTextSize * 1.12f
                    val tagPitchY = tagTextSize * 1.12f
                    val titleColPitch = titleTextSize * 1.55f
                    val tagColPitch = tagTextSize * 1.6f

                    // 题签几何：高 0.72H、上边距 0.07H、左缘对齐 0.085W
                    // 边距，内边距 0.026W/0.035H
                    val slipTop = viewHeight * 0.07f
                    val slipHeight = viewHeight * 0.72f
                    val slipPadX = viewWidth * 0.026f
                    val slipPadY = viewHeight * 0.035f
                    val contentTop = slipTop + slipPadY
                    val contentHeight = slipHeight - slipPadY * 2f
                    val charsPerColumn = (contentHeight / titlePitchY).toInt().coerceAtLeast(1)

                    // 书名最多两列，超长截断补「…」；标签单列小字殿后
                    val titleText = truncateEInkCoverTitle(parsed.title, 2 * charsPerColumn)
                    val titleColumns = ceil(titleText.length / charsPerColumn.toFloat())
                        .toInt().coerceIn(1, 2)
                    val tagText = parsed.tag?.let { tag ->
                        truncateEInkCoverTitle(
                            tag,
                            (contentHeight / tagPitchY).toInt().coerceAtLeast(1),
                        )
                    }

                    // 题签贴左上角，宽度由实际列数决定（上限 0.7W 兜底）
                    // 并向右扩展；书名列自最右向左起排，标签列紧贴书名
                    // 列左侧（引题位）
                    val contentWidth = titleColumns * titleColPitch +
                            (if (tagText != null) tagColPitch else 0f)
                    val slipWidth = (contentWidth + slipPadX * 2f)
                        .coerceAtMost(viewWidth * 0.7f)
                    val slipLeft = viewWidth * 0.085f
                    val contentRight = slipLeft + slipWidth - slipPadX
                    val titleBaseX = contentRight - titleColPitch / 2f

                    // 逐字测量（书名/标签竖排逐字定位，印文居中定位）
                    val titleLayouts = titleText.map { char ->
                        textMeasurer.measure(char.toString(), titleStyle)
                    }
                    val tagLayouts = tagText?.map { char ->
                        textMeasurer.measure(char.toString(), tagStyle)
                    }
                    val sealLayout = textMeasurer.measure(
                        parsed.title.firstOrNull()?.toString() ?: "书",
                        sealStyle,
                    )

                    // 藏书印：右下角落款行末尾（贴 0.085W 右边距线），
                    // 书名首字；印底 onSurface、印文 surface；下边距
                    // 0.05H——题签加高后与落款行保持间距
                    val sealSize = viewWidth * 0.17f
                    val sealLeft = viewWidth - viewWidth * 0.085f - sealSize
                    val sealTop = viewHeight - viewHeight * 0.05f - sealSize
                    val sealCenterX = sealLeft + sealSize / 2f
                    val sealCenterY = sealTop + sealSize / 2f

                    // 作者：横排落款与印章同行，右对齐印章左侧、随印贴
                    // 0.085W 右边距线，超长向左扩展省略号收尾
                    val authorText = author?.trim().orEmpty()
                    val authorRight = sealLeft - viewWidth * 0.03f
                    val authorLayout = authorText.takeIf { it.isNotEmpty() }?.let {
                        textMeasurer.measure(
                            it,
                            authorStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = Constraints(
                                maxWidth = (authorRight - slipLeft).roundToInt(),
                            ),
                        )
                    }

                    onDrawBehind {
                        // 题签只描界线不铺底：纸面本就同色，发丝线即题签轮廓
                        drawRect(
                            color = colors.outline,
                            topLeft = Offset(slipLeft, slipTop),
                            size = Size(slipWidth, slipHeight),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                        titleLayouts.forEachIndexed { index, layout ->
                            drawText(
                                layout,
                                topLeft = Offset(
                                    titleBaseX - (index / charsPerColumn) * titleColPitch -
                                            layout.size.width / 2f,
                                    contentTop + (index % charsPerColumn) * titlePitchY,
                                ),
                            )
                        }
                        tagLayouts?.forEachIndexed { index, layout ->
                            drawText(
                                layout,
                                topLeft = Offset(
                                    contentRight - titleColumns * titleColPitch -
                                            tagColPitch / 2f - layout.size.width / 2f,
                                    contentTop + index * tagPitchY,
                                ),
                            )
                        }
                        drawRoundRect(
                            color = colors.onSurface,
                            topLeft = Offset(sealLeft, sealTop),
                            size = Size(sealSize, sealSize),
                            cornerRadius = CornerRadius(viewWidth * 0.017f),
                        )
                        drawText(
                            sealLayout,
                            topLeft = Offset(
                                sealCenterX - sealLayout.size.width / 2f,
                                sealCenterY - sealLayout.size.height / 2f,
                            ),
                        )
                        authorLayout?.let { layout ->
                            drawText(
                                layout,
                                topLeft = Offset(
                                    authorRight - layout.size.width,
                                    sealCenterY - layout.size.height / 2f,
                                ),
                            )
                        }
                    }
                }
        )
    }
}
