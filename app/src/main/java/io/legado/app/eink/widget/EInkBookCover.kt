package io.legado.app.eink.widget

import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.RequestBuilder
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isDataUrl
import java.io.File

/** 封面尺寸（与 View 版 item_bookshelf_list.xml 一致：66dp × 90dp）。 */
internal val EInkCoverWidth = 66.dp
internal val EInkCoverHeight = 90.dp

/**
 * E-Ink 书籍封面（书架/搜索结果等列表项复用）。
 *
 * 封面加载统一通过 [EInkAsyncImage] 进入 Glide Compose，不在 Compose 侧做
 * bitmap copy 或额外 LruCache；内存缓存、磁盘缓存与生命周期由 Glide 管理。
 *
 * 无封面地址、用户开启“使用默认封面”、加载中或加载失败时，显示文字占位封面。
 */
@Composable
internal fun EInkBookCover(
    url: String?,
    name: String,
    author: String? = null,
    modifier: Modifier = Modifier,
    width: Dp = EInkCoverWidth,
    height: Dp = EInkCoverHeight,
    sourceOrigin: String? = null,
) {
    if (url.isNullOrBlank() || AppConfig.useDefaultCover) {
        EInkDefaultCover(name = name, author = author, modifier = modifier)
        return
    }

    val density = LocalDensity.current
    val targetWidthPx = with(density) { width.toPx() }.toInt()
    val targetHeightPx = with(density) { height.toPx() }.toInt()

    // 与 View 版 ImageLoader.load 保持一致的 path -> model 判断，
    // 避免 http/content/file/data 不同格式被 Glide 默认 StringLoader 误解。
    val model: Any? = remember(url) {
        when {
            url.isDataUrl() -> url
            url.isAbsUrl() -> url
            url.isContentScheme() -> Uri.parse(url)
            else -> kotlin.runCatching { File(url) }.getOrElse { url }
        }
    }

    // 与 View 版 CoverImageView.load 对齐：透传书源 origin 以支持需要
    // 自定义请求头（Referer/User-Agent）的封面；书架列表仅 Wi-Fi 加载固定为 false。
    val requestBuilderTransform: (RequestBuilder<Drawable>) -> RequestBuilder<Drawable> =
        remember(model, sourceOrigin, targetWidthPx, targetHeightPx) {
            { request ->
                var builder = request.set(OkHttpModelLoader.loadOnlyWifiOption, false)
                if (sourceOrigin != null) {
                    builder = builder.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
                }
                builder.override(targetWidthPx, targetHeightPx)
            }
        }

    EInkAsyncImage(
        model = model,
        contentDescription = name,
        modifier = modifier,
        loading = {
            EInkDefaultCover(name = name, author = author, modifier = modifier)
        },
        failure = {
            EInkDefaultCover(name = name, author = author, modifier = modifier)
        },
        requestBuilderTransform = requestBuilderTransform,
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
internal fun EInkInfoRow(
    iconRes: Int,
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(DescIconSize),
            colorFilter = ColorFilter.tint(EInkTheme.colorScheme.onSurfaceVariant)
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