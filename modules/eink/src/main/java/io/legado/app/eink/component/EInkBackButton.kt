package io.legado.app.eink.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.legado.app.eink.R
import io.legado.app.eink.theme.EInkTheme

/**
 * 返回按钮（统一 arrow_back 图标）。
 *
 * 顶栏与底部操作栏共用，保证各处返回按钮样式一致。
 * 基于 [EInkIconButton]：48dp 触控目标，按压瞬时反色反馈（规范 §35）。
 */
@Composable
fun EInkBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "返回",
) {
    EInkIconButton(
        onClick = onClick,
        painter = painterResource(R.drawable.ic_arrow_back),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = EInkTheme.colorScheme.onSurface
    )
}
