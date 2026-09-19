package io.legado.app.eink.feature.reader.selection

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.eink.R
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlin.math.roundToInt

/**
 * 选区/标记操作条动作（v2.1 点击式交互）：
 * - [COPY] 复制：选文落剪贴板；
 * - [LINE] 画线：落一条黑色实线（saveMarking thought=false）；
 * - [THOUGHT] 想法：开想法弹层（新区间 = 新建；已有想法 = 预填编辑）；
 * - [DELETE] 删除：deleteMarking（仅已有标记的区间/点按场景可达）。
 */
enum class ReaderMarkingAction { COPY, LINE, THOUGHT, DELETE }

/**
 * 选区动作集分派（纯函数，便于单测钉住交互表）：
 * - 新区间（选区上没有用户标记）→ 复制 / 想法 / 画线；
 * - 已有标记（划线或想法）→ 复制 / 想法 / 删除（想法进入编辑、删除按 id
 *   即时可用）——与点按已有标记同一语义，用户不会在两个入口看到两套动作。
 *
 * **顺序固定为「复制、想法、画线（删除）」**：前两槽在任何状态下都不换位，
 * 只有第三槽在「画线/删除」之间替换——键位不随选区是否落在标记上而互调，
 * 用户凭位置就能点到想点的键（真机反馈「按钮位置稳定一点」）。
 * - 不可标记（含标题选区 §3.4 门控 / 降级宿主端口缺失）→ 只留复制，
 *   不留点了没反应的死键。
 */
fun selectionActions(
    hasMarking: Boolean,
    canMark: Boolean = true,
): List<ReaderMarkingAction> = when {
    // 含标题选区不落划线/想法（§3.4 静默门控）：只留复制，不留死键
    !canMark -> listOf(ReaderMarkingAction.COPY)
    hasMarking -> listOf(
        ReaderMarkingAction.COPY,
        ReaderMarkingAction.THOUGHT,
        ReaderMarkingAction.DELETE,
    )
    else -> listOf(
        ReaderMarkingAction.COPY,
        ReaderMarkingAction.THOUGHT,
        ReaderMarkingAction.LINE,
    )
}

private val ReaderMarkingAction.iconRes: Int
    get() = when (this) {
        ReaderMarkingAction.COPY -> R.drawable.eink_ic_selection_copy
        ReaderMarkingAction.LINE -> R.drawable.eink_ic_selection_line
        ReaderMarkingAction.THOUGHT -> R.drawable.eink_ic_selection_thought
        ReaderMarkingAction.DELETE -> R.drawable.eink_ic_selection_delete
    }

private val ReaderMarkingAction.label: String
    get() = when (this) {
        ReaderMarkingAction.COPY -> "复制"
        ReaderMarkingAction.LINE -> "画线"
        ReaderMarkingAction.THOUGHT -> "想法"
        ReaderMarkingAction.DELETE -> "删除"
    }

/** 按钮宽度（dp）：三键等宽，横向撑不满时也不压缩触控目标（高度另定）。 */
private val ActionItemWidth = 80.dp

/** 按钮高度（dp）：图标在上 + 文案在下，容得下 24dp 图标与一行小字。 */
private val ActionItemHeight = 72.dp

/** 操作条到选区的间距（dp）。 */
private val ActionBarGap = 8.dp

/** 操作条内边距（dp）：卡片内缩，按钮不贴边。 */
private val ActionBarPadding = 4.dp

/**
 * 选区操作条（v2.1）：**大圆角矩形**卡片（[EInkShapes.large] 8dp + 1dp
 * 描边 + surface 实底），内含 1..3 个「上图标下文字」按钮。锚在选区上方
 * （放不下取下方），x 跟随选区中心并钳制在画布内。
 *
 * 形态取舍：卡片不透明（正文不透过按键，与正文视觉打架）；按钮常态透明 +
 * onSurface 内容、按压瞬时反色（[eInkActionColors]，规范 §35 唯一按压语言），
 * 零动画直切。整卡消费点击：点卡片内空白不穿透成「收操作条」。
 *
 * 置于 zIndex(2f)：压过把手独占层（zIndex(1f)），下方放置时按钮落在把手
 * 28dp 热区内也不被其 pointerInput 吞掉。
 *
 * @param canvasWidth 画布实测宽（调用方以 onSizeChanged 传入），x 钳制边界
 */
@Composable
internal fun ReaderSelectionActionBar(
    anchorLeft: Float,
    anchorTop: Float,
    anchorRight: Float,
    anchorBottom: Float,
    canvasWidth: Float,
    actions: List<ReaderMarkingAction>,
    onAction: (ReaderMarkingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val density = LocalDensity.current
    val gapPx = with(density) { ActionBarGap.toPx() }
    val barWidthPx = with(density) {
        (ActionItemWidth * actions.size + ActionBarPadding * 2).toPx()
    }
    val barHeightPx = with(density) {
        (ActionItemHeight + ActionBarPadding * 2).toPx()
    }
    // 垂直侧别在一次浮条展示内**粘住**：首选上方（放不下才到下方），定了之后
    // 选区微调不再让浮条在上/下之间反复翻面（真机反馈「位置稳定一点」）；
    // 粘住的那一侧真的放不下（选区被拖到页顶）时仍回落到另一侧。
    // 浮条随调界离场（拖把手期间不组合），下次展示重新判定。
    var stickyAbove by remember { mutableStateOf<Boolean?>(null) }
    val aboveFits = anchorTop - barHeightPx - gapPx >= 0f
    val useAbove = (stickyAbove ?: aboveFits) && aboveFits
    if (stickyAbove == null) {
        SideEffect { stickyAbove = useAbove }
    }
    // x 跟随选区中心并钳制在画布内（画布极窄时上限取 0）
    val x = ((anchorLeft + anchorRight) / 2f - barWidthPx / 2f)
        .coerceIn(0f, (canvasWidth - barWidthPx).coerceAtLeast(0f))
    val y = if (useAbove) {
        (anchorTop - barHeightPx - gapPx).roundToInt()
    } else {
        (anchorBottom + gapPx).roundToInt()
    }
    Row(
        modifier = modifier
            .zIndex(2f)
            .offset { IntOffset(x.roundToInt(), y) }
            .background(EInkTheme.colorScheme.surface, shape = EInkShapes.large)
            .border(1.dp, EInkTheme.colorScheme.outline, EInkShapes.large)
            // 消费卡片内空白点击，避免透传成下层「点浮条外收选区」
            .einkClickable(onClick = {})
            .padding(ActionBarPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            ReaderActionButton(
                action = action,
                onClick = { onAction(action) },
            )
        }
    }
}

/** 操作条按钮：图标在上、文案在下；按压反色（含 120ms 最短保持）。 */
@Composable
private fun ReaderActionButton(
    action: ReaderMarkingAction,
    onClick: () -> Unit,
) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Column(
        modifier = Modifier
            .width(ActionItemWidth)
            .height(ActionItemHeight)
            .then(press.modifier)
            .background(colors.containerColor, shape = EInkShapes.small)
            .einkClickable(
                role = Role.Button,
                onClickLabel = action.label,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(action.iconRes),
            contentDescription = action.label,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(colors.contentColor),
        )
        EInkText(
            text = action.label,
            style = EInkTheme.typography.labelMedium,
            color = colors.contentColor,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
