package io.legado.app.eink.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.theme.EInkSpacing
import io.legado.app.eink.theme.EInkTheme

/**
 * 顶栏（操作条语言统一版）：标题居左占剩余宽度（左侧留屏幕边距），
 * 动作图标按钮（[EInkOperationBarIcon] 默认尺寸：高度撑满 56dp 顶栏、
 * 宽度自适应但收敛上限降为 [TopBarWidthRatio] 倍高度（1.2，约 67dp，
 * 较底部操作栏的 1.7 倍更窄）、28dp 图标）居右连续排列、贴右屏；
 * 底部一条分隔线。
 *
 * 与 [EInkTopBar] 的区别：动作区无内边距、按钮贴右屏撑满高度。
 * 带右侧动作的界面（首页/目录/详情页）用本组件；仅标题+返回的简单
 * 顶栏仍可用 [EInkTopBar]；阅读页标题可点击进详情，自管顶栏。
 *
 * @param title 标题文本（单行省略；传空串则仅占位，动作仍贴右）
 * @param titleStyle 标题样式
 * @param actions 右侧动作区（各按钮直接使用 EInkOperationBarIcon）
 */
@Composable
fun EInkTopActionBar(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = EInkTheme.typography.titleLarge,
    actions: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .background(EInkTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = EInkSpacing.m)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                EInkText(
                    text = title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 顶栏动作按钮比底部操作栏窄：收敛宽度降为 TopBarWidthRatio 倍高度
            CompositionLocalProvider(LocalOperationBarWidthRatio provides TopBarWidthRatio) {
                actions()
            }
        }
        EInkHorizontalDivider()
    }
}

/** 顶栏高度（与底部通用操作栏一致）。 */
private val BarHeight = 56.dp
