package io.legado.app.eink.feature.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.engine.EInkEngineRegistry
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.designsystem.widget.EInkAsyncImage

/** 封面尺寸（与 View 版 item_bookshelf_list.xml 一致：66dp × 90dp）。 */
val EInkCoverWidth = 66.dp
val EInkCoverHeight = 90.dp

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
 */
internal fun buildEInkCoverRequest(
    context: Context,
    url: String,
    sourceOrigin: String?,
    widthPx: Int,
    heightPx: Int,
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .crossfade(false)
    .memoryCacheKey("eink-cover|$url|${widthPx}x$heightPx")
    .apply(EInkEngineRegistry.coverEngine.coverRequestOptions(sourceOrigin, widthPx, heightPx))
    .build()

/**
 * E-Ink 书籍封面（书架/搜索结果等列表项复用）。
 *
 * Feature 组件（迁移计划 T3.3 下沉）：依赖 [EInkEngineRegistry] 业务
 * 端口（CoverEngine），不属于 Design System（规范 §44），供书架/搜索/
 * 详情等多个 feature 共用，故落在 bookshelf 包。
 *
 * 封面加载统一通过 [EInkAsyncImage] 进入 Coil，不在 Compose 侧做
 * bitmap copy 或额外 LruCache；内存缓存、磁盘缓存与生命周期由宿主
 * 单例 ImageLoader 管理。url 原样作为 data，请求选项（书源 origin 头、
 * 目标尺寸）经 CoverEngine 端口由宿主提供（与 MD3 主工程
 * buildCoverImageRequest 行为对齐）。
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
    val coverEngine = EInkEngineRegistry.coverEngine
    // 单点 clip：加载图与占位共用同一圆角轮廓（draw 阶段裁剪，静态单帧）
    val coverModifier = modifier.clip(EInkShapes.medium)
    if (url.isNullOrBlank() || coverEngine.useDefaultCover) {
        EInkDefaultCover(name = name, author = author, modifier = coverModifier)
        return
    }

    val density = LocalDensity.current
    val (targetWidthPx, targetHeightPx) = coverTargetSizePx(width, height, density)

    val context = LocalContext.current

    // 请求实例按入参 remember：重组间保持同一 ImageRequest，避免请求被
    // 反复重建。crossfade(false) 显式关闭：宿主单例 ImageLoader 全局开
    // crossfade，与墨水屏零动画规范冲突，必须逐请求覆盖
    val model: Any? = remember(context, url, sourceOrigin, targetWidthPx, targetHeightPx) {
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
        loading = placeholderContent,
        failure = placeholderContent,
    )
}

/**
 * 文字占位封面：无封面时保证封面区域正常显示（书名 + 作者）。
 */
@Composable
private fun EInkDefaultCover(
    name: String,
    author: String?,
    modifier: Modifier = Modifier,
) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = modifier
            // 形状必须与外层 clip（EInkBookCover 的 coverModifier，medium）
            // 一致：内层半径小于外层掩模时，1dp 边框的四角弧线落在掩模外
            // 被切掉，视觉上四角缺损
            .background(color = colors.surfaceVariant, shape = EInkShapes.medium)
            .border(width = 1.dp, color = colors.outline, shape = EInkShapes.medium)
            .padding(horizontal = EInkSpacing.xs, vertical = EInkSpacing.xxs),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EInkText(
                text = name,
                style = EInkTheme.typography.labelLarge,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!author.isNullOrBlank()) {
                EInkText(
                    text = author,
                    style = EInkTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
