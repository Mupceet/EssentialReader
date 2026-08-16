package io.legado.app.eink.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.theme.EInkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 统一的上/下翻页箭头组件。
 *
 * 视觉样式：上下箭头共同包在一个胶囊（长圆）边框内，中间用竖线分隔；
 * 竖线贯通胶囊上下边。单个箭头为宽 96dp、高 48dp 的大触控目标，
 * 不可用时置灰，按下瞬时反色。
 */
@Composable
fun EInkPageArrows(
    pageUpEnabled: Boolean,
    pageDownEnabled: Boolean,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = EInkTheme.colorScheme
    Row(
        modifier = modifier
            .height(ArrowTouchTarget)
            .clip(CircleShape)
            .background(Color.Transparent)
            .border(width = CapsuleBorder, color = scheme.outline, shape = CircleShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PageArrowIcon(
            iconRes = R.drawable.ic_keyboard_arrow_up,
            enabled = pageUpEnabled,
            contentDescription = "上一页",
            onClick = onPageUp
        )
        Box(
            modifier = Modifier
                .width(DividerWidth)
                .height(ArrowTouchTarget)
                .background(scheme.outline)
        )
        PageArrowIcon(
            iconRes = R.drawable.ic_keyboard_arrow_down,
            enabled = pageDownEnabled,
            contentDescription = "下一页",
            onClick = onPageDown
        )
    }
}

@Composable
private fun PageArrowIcon(
    iconRes: Int,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val scope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }
    var pressToken by remember { mutableStateOf(0) }
    fun showPressed() {
        pressToken++
        isPressed = true
    }
    fun hidePressedAfterMinDuration() {
        val token = pressToken
        scope.launch {
            delay(MinPressedDurationMillis)
            if (pressToken == token) {
                isPressed = false
            }
        }
    }
    val color = when {
        !enabled -> scheme.disabledContent
        isPressed -> scheme.surface
        else -> scheme.onSurface
    }
    Box(
        modifier = Modifier
            .width(ArrowButtonWidth)
            .height(ArrowTouchTarget)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (enabled) {
                    this.onClick(label = contentDescription) {
                        onClick()
                        true
                    }
                }
            }
            .background(if (enabled && isPressed) scheme.onSurface else Color.Transparent)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            showPressed()
                            try {
                                tryAwaitRelease()
                            } finally {
                                hidePressedAfterMinDuration()
                            }
                        },
                        onTap = { onClick() }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(ArrowIconSize),
            colorFilter = ColorFilter.tint(color)
        )
    }
}

/** 箭头按钮高度 / 胶囊高度。 */
private val ArrowTouchTarget = 40.dp

/** 单个箭头按钮宽度。 */
private val ArrowButtonWidth = 60.dp

/** 图标显示尺寸。 */
private val ArrowIconSize = 24.dp

/** 胶囊边框宽度。 */
private val CapsuleBorder = 1.dp

/** 上下箭头之间的竖向分隔线宽度。 */
private val DividerWidth = 1.dp

/** Minimum highlight duration so quick taps still show inversion. */
private const val MinPressedDurationMillis = 120L
