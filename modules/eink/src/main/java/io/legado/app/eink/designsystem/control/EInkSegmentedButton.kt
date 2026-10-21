package io.legado.app.eink.designsystem.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 多状态分段按钮（2026-10-17 沉淀；消费者：底部调整面板显隐开关、
 * 阅读设置面板水波纹档位等多档互斥项）。
 *
 * 形态：相连多段——**轨道整体仅左右两端圆角**，段间结合处无分割线、
 * 无圆角（通高矩形）；**选中段为正常圆角矩形实心高亮**（四角圆角、
 * 2dp 内缩，onSurface 底 + background 反色字）；未选中段透明底 onSurface 字。
 * 轨道宽度两模式（[distributeEvenly]）：定宽均分（消费方传定宽
 * modifier，段 weight 均分、高亮盒铺满整段）；内容自适应（各段按
 * 文案收紧、轨道随内容收缩——段与高亮盒均不得用 weight/
 * fillMaxWidth，二者会把不定宽段撑满可用最大宽，挤掉兄弟段与
 * 同行标签）。
 * E-Ink 约束：静态单帧、零动画零阴影。
 */
@Composable
fun EInkSegmentedButton(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // true=各段均分给定宽度（定宽消费方，如书架 ChoiceRow）；
    // false=各段按文案收紧、轨道随内容收缩（不定宽消费方，如阅读
    // 面板水波纹档位行——weight 会强制各段吃满余宽挤没同行标签）
    distributeEvenly: Boolean = true,
) {
    val colors = EInkTheme.colorScheme
    Row(
        modifier = modifier
            .height(36.dp)
            .border(1.dp, colors.outline, EInkShapes.medium),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = (if (distributeEvenly) Modifier.weight(1f) else Modifier)
                    .fillMaxHeight()
                    .einkClickable(
                        enabled = true,
                        role = Role.Button,
                        onClickLabel = option,
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                // 选中高亮：正常圆角矩形实心块。上下 2dp、仅最外端 2dp 内缩，
                // 段间接缝处贴合无缝（不留缝隙浪费空间）
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(
                            // 定宽均分：高亮盒铺满整段；自适应：随文案收紧
                            // （fillMaxWidth 会把不定宽段撑满可用宽，挤掉
                            // 兄弟段与同行标签）
                            if (distributeEvenly) Modifier.fillMaxWidth() else Modifier
                        )
                        .padding(
                            start = if (index == 0) 2.dp else 0.dp,
                            end = if (index == options.lastIndex) 2.dp else 0.dp,
                            top = 2.dp,
                            bottom = 2.dp,
                        )
                        .then(
                            if (selected) {
                                Modifier.background(colors.onSurface, EInkShapes.medium)
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    EInkText(
                        text = option,
                        style = EInkTheme.typography.bodyMedium,
                        color = if (selected) colors.background else colors.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        // 定宽均分：未选中态收窄边距（宽度紧张时保证四字完整
                        // 显示）；自适应：统一 s 边距，切换选中不改段宽
                        modifier = Modifier.padding(
                            horizontal = if (distributeEvenly && !selected) {
                                EInkSpacing.xxs
                            } else {
                                EInkSpacing.s
                            }
                        ),
                    )
                }
            }
        }
    }
}
