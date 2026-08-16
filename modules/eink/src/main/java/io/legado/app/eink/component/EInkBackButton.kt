package io.legado.app.eink.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.theme.EInkTheme

/**
 * 返回按钮（48dp 触控目标，零涟漪，使用统一 arrow_back 图标）。
 *
 * 顶栏与底部操作栏共用，保证各处返回按钮样式一致。
 */
@Composable
fun EInkBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "返回",
) {
    Box(
        modifier = modifier
            .size(BackTouchTarget)
            .clickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = contentDescription,
            modifier = Modifier.size(IconSize),
            colorFilter = ColorFilter.tint(EInkTheme.colorScheme.onSurface)
        )
    }
}

private val BackTouchTarget = 48.dp
private val IconSize = 24.dp
