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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.theme.EInkColorScheme
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.widget.EInkAsyncImage
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
 * 无封面地址、用户开启“使用默认封面”、加载中或加载失败时，显示文字
 * 占位封面（竖排大字书卷式，见 [EInkDefaultCover]）。
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
 * 文字占位封面（竖排大字书卷式）：无封面时保证封面区域正常显示。
 *
 * 版式（参考用户的默认封面样例）：书名大字裸排贴右缘自上而下，每列
 * 至多 8 字、最多两列（右列先读），无框无印；作者小字竖排居标题块
 * 左侧、垂直锚定封面中线（位置稳定，不随书名长短浮动），尾缀「/ 著」
 * 落款。标签【…】与副标题（…）按解析规则不上封面。
 *
 * 字号规则：书名每列至多 8 字——8 字内单列（短名大字，上限 0.17W），
 * 超出折两列（右列先读、首列填满 8 字其余进次列，至多两列共 16 字，
 * 仍超出截断），字号由最长列推导——两列时恒为 8 字列字号（0.137W），
 * 所有长名书号一致（下限 0.07W）；作者列 0.085W、限高 0.88H，单列时
 * 居书名列左侧（0.55W），双列时随标题块左移，超长截「…」后仍补
 * 「/ 著」。空作者整列不画。书名列贴右缘——封面右上角不摆任何角标
 * （网格未读角标由调用侧置于左上角避让），封面绘制本身不感知角标。
 *
 * 字体与界面排版同源：直绘文本经 [rememberTextMeasurer] 走 Compose 文本
 * 排版，消费 [EInkTheme.typography] 挂载的宿主全局 UI 字体
 * （[io.legado.app.eink.contract.EInkHostActivity.uiFontFamily] 注入，
 * 默认平台字体），与界面 Text 同一套字体解析，天然跟随字体切换；书名
 * Bold、作者 Normal，同为墨色。颜色全部由主题下发（纸面 surface、墨字
 * onSurface、界线 outline），灰阶对比靠“近黑字 + 近白纸”，不用中灰
 * 小字（E-Ink 低对比发虚）。不引入宿主的阴影/描边/自定义字色设置
 * （E-Ink 零阴影，主题单一下发）。
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
                    onDrawBehind(
                        typoCoverDraw(
                            author,
                            parsed,
                            viewWidth,
                            viewHeight,
                            colors,
                            coverFontFamily,
                            textMeasurer,
                        )
                    )
                }
        )
    }
}

/**
 * 书卷式直绘（[EInkDefaultCover] 版式，几何与字号规则见其 KDoc）。
 * Density 接收方供字号 px→sp 换算，与 textMeasurer 密度同源。
 */
private fun Density.typoCoverDraw(
    author: String?,
    parsed: EInkCoverText,
    viewWidth: Float,
    viewHeight: Float,
    colors: EInkColorScheme,
    fontFamily: FontFamily?,
    textMeasurer: TextMeasurer,
): DrawScope.() -> Unit {
    // 书名：竖排，每列至多 8 字——8 字内单列（短名大字），超出折两列
    //（右列先读、首列填满 8 字其余进次列，至多两列共 16 字，仍超出
    // 截断）；字号由最长列推导——两列时恒为 8 字列字号，全部两列书名
    // 字号一致
    val titleText = truncateEInkCoverTitle(parsed.title, 16)
    val twoColumns = titleText.length > 8
    val firstColumn = if (twoColumns) titleText.substring(0, 8) else titleText
    val secondColumn = if (twoColumns) titleText.substring(8) else ""
    val columnLen = maxOf(firstColumn.length, secondColumn.length)
    val titleSize = minOf(
        viewWidth * 0.17f,
        viewHeight * 0.86f / (1f + 1.08f * (columnLen - 1).coerceAtLeast(0)),
    ).coerceAtLeast(viewWidth * 0.07f)
    val titleStyle = TextStyle(
        color = colors.onSurface,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = titleSize.toSp(),
    )
    val firstLayouts = firstColumn.map { char ->
        textMeasurer.measure(char.toString(), titleStyle)
    }
    val secondLayouts = secondColumn.map { char ->
        textMeasurer.measure(char.toString(), titleStyle)
    }
    val titlePitch = titleSize * 1.08f
    val titleTop = viewHeight * 0.07f
    val firstCenterX = viewWidth * 0.75f
    val secondCenterX = firstCenterX - titleSize * 1.35f

    // 作者：小字竖排（Normal 体，与书名同为墨色），垂直锚定封面中线——
    // 位置稳定，不随书名长短浮动；列高预算 0.88H，超长截「…」，尾缀
    // 「/ 著」并在两处接缝各加 0.45 步进的间隙；空作者整列不画。横向
    // 跟随标题块：单列在书名列左侧（0.55W），双列贴第二列左移
    val authorText = author?.trim().orEmpty()
    val authorFontSize = viewWidth * 0.085f
    val authorPitch = authorFontSize * 1.15f
    val maxRunChars = ((viewHeight * 0.88f / authorPitch) - 3).toInt().coerceAtLeast(1)
    val authorRun: List<String> = when {
        authorText.isEmpty() -> emptyList()
        authorText.length > maxRunChars ->
            authorText.take(maxRunChars - 1).map { it.toString() } + "…"
        else -> authorText.map { it.toString() }
    }
    val authorChars = if (authorRun.isEmpty()) {
        emptyList()
    } else {
        authorRun + listOf("/", "著")
    }
    val authorStyle = TextStyle(
        color = colors.onSurface,
        fontFamily = fontFamily,
        fontSize = authorFontSize.toSp(),
    )
    val authorLayouts = authorChars.map { char ->
        textMeasurer.measure(char, authorStyle)
    }
    val authorCenterX = if (twoColumns) {
        secondCenterX - (titleSize + authorFontSize) / 2f - viewWidth * 0.04f
    } else {
        viewWidth * 0.55f
    }
    // 逐字 y 坐标：作者段尾与「/」、「/」与「著」各加 0.45 步进的间隙
    val seamGaps = if (authorLayouts.isEmpty()) 0 else 2
    val authorTotalHeight = (authorLayouts.size.coerceAtLeast(1) - 1) * authorPitch +
            seamGaps * authorPitch * 0.45f + authorFontSize
    var authorY = (viewHeight * 0.5f - authorTotalHeight / 2f)
        .coerceIn(viewHeight * 0.06f, viewHeight * 0.94f - authorTotalHeight)
    val authorYs = FloatArray(authorLayouts.size)
    authorLayouts.forEachIndexed { index, _ ->
        authorYs[index] = authorY
        authorY += authorPitch
        if (authorLayouts.isNotEmpty() &&
            (index == authorRun.size - 1 || index == authorLayouts.size - 2)
        ) {
            authorY += authorPitch * 0.45f
        }
    }

    return {
        firstLayouts.forEachIndexed { index, layout ->
            drawText(
                layout,
                topLeft = Offset(
                    firstCenterX - layout.size.width / 2f,
                    titleTop + index * titlePitch,
                ),
            )
        }
        secondLayouts.forEachIndexed { index, layout ->
            drawText(
                layout,
                topLeft = Offset(
                    secondCenterX - layout.size.width / 2f,
                    titleTop + index * titlePitch,
                ),
            )
        }
        authorLayouts.forEachIndexed { index, layout ->
            drawText(
                layout,
                topLeft = Offset(
                    authorCenterX - layout.size.width / 2f,
                    authorYs[index],
                ),
            )
        }
    }
}
