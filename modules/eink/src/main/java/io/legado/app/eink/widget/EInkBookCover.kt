package io.legado.app.eink.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.engine.EInkEngineRegistry
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/** 封面尺寸（与 View 版 item_bookshelf_list.xml 一致：66dp × 90dp）。 */
val EInkCoverWidth = 66.dp
val EInkCoverHeight = 90.dp

/**
 * E-Ink 书籍封面（书架/搜索结果等列表项复用）。
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
    val targetWidthPx = with(density) { width.toPx() }.toInt()
    val targetHeightPx = with(density) { height.toPx() }.toInt()

    val context = LocalContext.current

    // 请求实例按入参 remember：重组间保持同一 ImageRequest，避免请求被
    // 反复重建。crossfade(false) 显式关闭：宿主单例 ImageLoader 全局开
    // crossfade，与墨水屏零动画规范冲突，必须逐请求覆盖
    val model: Any? = remember(context, url, sourceOrigin, targetWidthPx, targetHeightPx) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(false)
            .apply(coverEngine.coverRequestOptions(sourceOrigin, targetWidthPx, targetHeightPx))
            .build()
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
            .background(color = colors.surfaceVariant, shape = EInkShapes.small)
            .border(width = 1.dp, color = colors.outline, shape = EInkShapes.small)
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

/** 信息行图标尺寸（同 View 版 @dimen/desc_icon_size = 18dp）。 */
private val DescIconSize = 18.dp

/**
 * 图标 + 文字信息行：与 View 版书架/搜索列表项一致，用图标区分
 * 作者 / 当前进度章节 / 最新章节等信息行。
 */
@Composable
fun EInkInfoRow(
    iconRes: Int,
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    // ColorFilter.tint 每次调用都新建实例：列表项最多三行信息行，
    // 缓存避免条目重组时的重复分配
    val iconTintColor = EInkTheme.colorScheme.onSurfaceVariant
    val iconTint = remember(iconTintColor) { ColorFilter.tint(iconTintColor) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(DescIconSize),
            colorFilter = iconTint
        )
        Spacer(modifier = Modifier.width(EInkSpacing.xs))
        EInkText(
            text = text,
            style = style,
            color = EInkTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
