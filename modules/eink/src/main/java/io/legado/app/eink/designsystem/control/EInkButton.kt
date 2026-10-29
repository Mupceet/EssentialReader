package io.legado.app.eink.designsystem.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.EInkPressBlock
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * E-Ink 通用文本按钮：唯一的可交互文本元素（EInkText 只负责展示）。
 *
 * 两种形态（DS 规范 §35/§42；2026-10-17 与 [EInkSegmentedButton] 统一）：
 *  - [bordered] = true（默认）：1dp 描边轨道 + medium 圆角，**选中态为轨道内
 *    2dp 内缩的圆角实心块**（onSurface 底反色字）——与多状态分段按钮同款
 *    视觉语言，两者共存不突兀；用于对话框按钮、页签、动作按钮、开/关
 *    状态展示；默认高 36dp（与分段按钮同高，同屏一致）；
 *  - [bordered] = false：无描边矩形按压反色块，如章节步进、± 步进按钮
 *    ——按压背景为直角矩形（可贴容器边缘）。
 *
 * 按压/选中/禁用的状态机与配色由 [EInkPressBlock] 原语承载（本组件只
 * 负责轨道样式与文案）；富内容标题按钮（前缀/尾部槽）不在此——见
 * EInkTopBar 的标题区实现。
 *
 * 状态：按压瞬时反色（整轨）、选中内缩实心块、禁用弱化不可点。
 * 宽度与布局定位（占满/weight/固定尺寸）由调用方经 [modifier] 决定。
 *
 * @param text 按钮文案
 * @param onClick 点击回调（[enabled] 为 false 时不响应）
 * @param modifier 布局定位（如 fillMaxWidth / weight / size）
 * @param enabled 禁用态：弱化且不可点
 * @param selected 选中态：内缩圆角实心块（如运行中/当前页签/开关开启）
 * @param bordered 描边形态；false 为无描边直角按压块
 * @param shape 描边形态的圆角形状（默认 medium；仅 [bordered] = true 生效，
 *   无描边形态恒直角；选中内缩实心块随本形状）——如动作按钮传
 *   [EInkShapes.capsule] 与条目按钮区隔
 * @param height 按钮高度（对话框按钮 44dp、页签 40dp、常规默认 36dp=分段按钮同高）；
 *   null 时不约束高度，由 [modifier] 决定（如 size / fillMaxHeight）
 * @param style 文案字号
 * @param role 无障碍语义角色
 * @param onClickLabel 无障碍点击描述
 * @param contentPadding 文案内边距（在按压背景之内；如章节按钮的水平边距）
 * @param contentAlignment 文案对齐（默认居中）
 */
@Composable
fun EInkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    bordered: Boolean = true,
    shape: Shape = EInkShapes.medium,
    height: Dp? = 36.dp,
    style: TextStyle = EInkTheme.typography.bodyMedium,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.Center,
) {
    val scheme = EInkTheme.colorScheme
    // 描边形态与分段按钮同轨：常态轮廓线（描边恒 outline，实心块独立内层），
    // 禁用弱化；无描边形态直角（按压背景可贴容器边缘）
    val effectiveShape = if (bordered) shape else RectangleShape
    EInkPressBlock(
        onClick = onClick,
        modifier = modifier.then(if (height != null) Modifier.height(height) else Modifier),
        enabled = enabled,
        selected = selected,
        shape = effectiveShape,
        role = role,
        onClickLabel = onClickLabel,
        contentAlignment = contentAlignment,
    ) { colors, pressed ->
        if (bordered) {
            // 轨道描边：内层首子绘制，与原节点级 modifier.border 同界同形；
            // matchParentSize 不参与尺寸决策（fillMaxSize 会顶满传入约束
            // 的 maxWidth，不定宽按钮会被撑到 max——搜索历史 chip 回归）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(
                        width = 1.dp,
                        color = if (!enabled) scheme.disabledContent else scheme.outline,
                        shape = effectiveShape,
                    )
            )
            // 选中内缩实心块（分段按钮同款）：2dp 内缩 + 随按钮形状，
            // onSurface 底；按压反色期间由整轨反色覆盖，不叠加
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(2.dp)
                    .then(
                        if (selected && !pressed && enabled) {
                            Modifier.background(scheme.onSurface, shape)
                        } else {
                            Modifier
                        }
                    ),
            )
        }
        EInkText(
            text = text,
            // 选中且非按压：文案反色（实心块上）；其余随按压/常态色
            color = if (bordered && selected && !pressed && enabled) {
                scheme.background
            } else {
                colors.contentColor
            },
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(contentPadding),
        )
    }
}
