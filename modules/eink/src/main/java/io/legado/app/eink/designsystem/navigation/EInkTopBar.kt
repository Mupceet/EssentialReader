package io.legado.app.eink.designsystem.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * E-Ink 顶栏（Foundation 实现，无 Material3 依赖，规范 §28）。
 *
 * 结构: [返回] 标题(单行省略) …… 动作区，底部一条分隔线。
 * 原E-Ink TopActionBar 已并入（规范 §29 禁止顶栏变体增殖）：带右侧
 * 图标动作的界面传 [actionsFillMax] = true，动作区贴右屏、按钮高度
 * 撑满顶栏、宽度收敛上限降为 [TopBarWidthRatio] 倍高度；仅标题+返回
 * 或文本动作的简单顶栏保持默认内边距模式。
 *
 * 标题可点击（[onTitleClick]，如阅读页书名进详情）：标题区整体作为
 * 无边框按钮（EInkButton 直角按压块）撑满顶栏高度，按压瞬时反色且
 * 背景贴容器边缘（屏幕左缘 / 返回键右侧），[titleEnabled] = false 时
 * 置灰不可点。
 *
 * E-Ink 约束:
 *  - 零涟漪（全局 NoIndication）、零阴影，层次仅靠分隔线；
 *  - 返回键使用统一的 arrow_back 图标，触控目标 48dp；
 *  - 高度与底部操作栏一致（56dp），上下形成稳定的对称骨架；
 *  - 不透明 surface 背景：支持作为覆盖层浮在内容之上（如阅读页），
 *    普通页面下与页面背景同色、渲染无差异。
 *
 * @param title 标题文本（单行省略）
 * @param onBack 返回回调；为 null 时不显示返回按钮（根页面用）
 * @param onTitleClick 标题点击回调；非 null 时标题整体可点击（按压反色、贴边背景）
 * @param titleEnabled 标题可点击性；false 时中灰置灰（仅 [onTitleClick] 非 null 时有意义）
 * @param titleClickLabel 标题点击的无障碍语义标签（如"书籍详情"）
 * @param titleStyle 标题样式；null 时用默认 titleLarge（首页等传 titleLarge 放大）
 * @param actionsFillMax true 时动作区贴右撑满高度（图标动作模式），
 *   动作内直接使用 [EInkOperationBarIcon]；false 时动作区随顶栏内边距（文本动作模式）
 * @param actions 右侧动作区内容
 */
@Composable
fun EInkTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    titleEnabled: Boolean = true,
    titleClickLabel: String? = null,
    titleStyle: TextStyle? = null,
    actionsFillMax: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val colors = EInkTheme.colorScheme
    val titleClickable = onTitleClick != null

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                // 不透明 surface 背景：顶栏可能作为覆盖层浮在内容之上
                // （阅读页书名栏 overlay 正文），必须遮住其后内容
                .background(EInkTheme.colorScheme.surface)
                .then(if (actionsFillMax) Modifier else Modifier.padding(horizontal = EInkSpacing.m)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TopBarBackButton(onClick = onBack)
            }
            if (titleClickable) {
                // 可点击标题：无边框按钮形态（直角按压背景贴容器起点，
                // 即屏幕左缘/返回键右侧），边距内置在按钮内
                EInkButton(
                    text = title,
                    onClick = onTitleClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    enabled = titleEnabled,
                    bordered = false,
                    height = null,
                    style = titleStyle ?: EInkTheme.typography.titleLarge,
                    onClickLabel = titleClickLabel,
                    contentPadding = PaddingValues(start = EInkSpacing.m),
                    contentAlignment = Alignment.CenterStart,
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (actionsFillMax) Modifier.fillMaxHeight() else Modifier),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(
                        text = title,
                        style = (titleStyle ?: EInkTheme.typography.titleLarge)
                            .copy(color = colors.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = EInkSpacing.m)
                    )
                }
            }
            if (actionsFillMax) {
                // 顶栏动作按钮比底部操作栏窄：收敛宽度降为 TopBarWidthRatio 倍高度
                CompositionLocalProvider(LocalOperationBarWidthRatio provides TopBarWidthRatio) {
                    actions()
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                }
            }
        }
        EInkHorizontalDivider()
    }
}

/**
 * 顶栏返回按钮（原 EInkBackButton 内联，规范 §54 禁止独立轻微变体）：
 * 统一 arrow_back 图标，48dp 触控目标，按压瞬时反色（规范 §13/§35）。
 */
@Composable
private fun TopBarBackButton(onClick: () -> Unit) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Box(
        modifier = Modifier
            .size(TouchTarget)
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(
                role = Role.Button,
                onClickLabel = "返回",
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.eink_ic_arrow_back),
            contentDescription = "返回",
            modifier = Modifier.size(IconSize),
            colorFilter = ColorFilter.tint(colors.contentColor)
        )
    }
}

/** 返回按钮触控目标（边缘区规范 48dp）。 */
private val TouchTarget = 48.dp

/** 返回按钮图标尺寸。 */
private val IconSize = 24.dp

/** 顶栏高度（与底部通用操作栏一致）。 */
private val BarHeight = 56.dp
