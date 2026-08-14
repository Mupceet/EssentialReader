package io.legado.app.eink.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.eink.theme.EInkTheme.typography

/**
 * "←" 文本字形返回按钮（48dp 触控目标，零涟漪零依赖图标库）。
 *
 * 顶栏与底部操作栏共用，保证两处返回按钮样式一致。
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
        BasicText(
            text = BackGlyph,
            style = typography.titleMedium.copy(color = EInkTheme.colorScheme.onSurface)
        )
    }
}

private val BackTouchTarget = 48.dp

private const val BackGlyph = "←"
