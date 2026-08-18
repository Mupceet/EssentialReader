package io.legado.app.eink.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/**
 * E-Ink 顶栏（Foundation 实现，无 Material3 依赖）。
 *
 * 结构: [返回] 标题(单行省略) …… 动作区，底部一条分隔线。
 *
 * E-Ink 约束:
 *  - 零涟漪（全局 NoIndication）、零阴影，层次仅靠分隔线；
 *  - 返回键使用统一的 arrow_back 图标，触控目标 48dp；
 *  - 高度与底部操作栏一致（56dp），上下形成稳定的对称骨架。
 *
 * @param title 标题文本
 * @param onBack 返回回调；为 null 时不显示返回按钮（根页面用）
 * @param titleStyle 标题样式；null 时用默认 titleMedium（首页等传 titleLarge 放大）
 * @param actions 右侧动作区内容（文本按钮等）
 */
@Composable
fun EInkTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    titleStyle: TextStyle? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = EInkTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .padding(horizontal = EInkSpacing.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                EInkBackButton(onClick = onBack)
            }
            BasicText(
                text = title,
                style = (titleStyle ?: EInkTheme.typography.titleMedium)
                    .copy(color = colors.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack != null) EInkSpacing.m else 0.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        EInkHorizontalDivider()
    }
}

/** 顶栏高度（与底部操作栏一致）。 */
private val BarHeight = 56.dp
