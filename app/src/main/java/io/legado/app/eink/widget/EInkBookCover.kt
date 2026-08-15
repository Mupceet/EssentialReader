package io.legado.app.eink.widget

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.eink.component.EInkText
import io.legado.app.eink.theme.EInkShapes
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader

/** 封面尺寸（与 View 版 item_bookshelf_list.xml 一致：66dp × 90dp）。 */
internal val EInkCoverWidth = 66.dp
internal val EInkCoverHeight = 90.dp

/**
 * E-Ink 书籍封面（书架/搜索结果等列表项复用）。
 *
 * 复用应用内 Glide（[ImageLoader.loadBitmap]）加载封面图，按 [EInkCoverWidth] ×
 * [EInkCoverHeight] 裁剪显示；**封面始终有正常显示**：
 *  - 无封面地址、用户开启"使用默认封面"或加载失败时，显示文字占位封面
 *    （书名 + 作者，居中、带边框），不会出现空白/破图；
 *  - 加载成功则显示封面位图（[ContentScale.Crop]）。
 *
 * 占位封面用纯 Compose 绘制而非位图，E-Ink 下文字更清晰、无残影风险。
 */
@Composable
internal fun EInkBookCover(
    url: String?,
    name: String,
    author: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val useDefaultCover = AppConfig.useDefaultCover
    val density = LocalDensity.current
    val targetWidthPx = with(density) { EInkCoverWidth.toPx() }.toInt()
    val targetHeightPx = with(density) { EInkCoverHeight.toPx() }.toInt()

    val coverBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        url,
        useDefaultCover
    ) {
        value = null
        // 无封面地址或用户选择"使用默认封面"：直接展示文字占位封面。
        if (url.isNullOrBlank() || useDefaultCover) return@produceState

        val target = object : CustomTarget<Bitmap>(targetWidthPx, targetHeightPx) {
            override fun onResourceReady(
                resource: Bitmap,
                transition: Transition<in Bitmap>?,
            ) {
                value = resource.asImageBitmap()
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                value = null
            }
        }
        val request = ImageLoader.loadBitmap(context, url)
            .override(targetWidthPx, targetHeightPx)
            .into(target)
        awaitDispose { Glide.with(context).clear(target) }
    }

    val bitmap = coverBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = name,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        EInkDefaultCover(
            name = name,
            author = author,
            modifier = modifier
        )
    }
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
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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