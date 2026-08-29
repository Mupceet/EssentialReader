package io.legado.app.eink.designsystem.content

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/** 信息行图标尺寸（同 View 版 @dimen/desc_icon_size = 18dp）。 */
private val DescIconSize = 18.dp

/**
 * 图标 + 文字信息行：书架/搜索/详情列表项共用的元信息行，
 * 用图标区分作者 / 当前进度章节 / 最新章节等信息。
 *
 * 纯通用组件（无业务依赖，规范 §44 归 Design System content 层）；
 * 后续如出现 ≥2 处可归一的行结构再演进为 EInkListItem（§60）。
 */
@Composable
fun EInkInfoRow(
    iconRes: Int,
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    /** 图标与文字颜色（默认次级色；按压反色行内传瞬时反色内容色）。 */
    contentColor: Color = EInkTheme.colorScheme.onSurfaceVariant,
) {
    // ColorFilter.tint 每次调用都新建实例：列表项最多三行信息行，
    // 缓存避免条目重组时的重复分配
    val iconTint = remember(contentColor) { ColorFilter.tint(contentColor) }
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
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
