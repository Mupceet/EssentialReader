package io.legado.app.eink.designsystem.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.EInkPressBlock
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * E-Ink 顶栏（Foundation 实现，无 Material3 依赖，规范 §28）。
 *
 * 结构: [返回] 标题(单行省略) …… 动作区，底部一条分隔线。
 * 原E-Ink TopActionBar 已并入（规范 §29 禁止顶栏变体增殖）：带右侧
 * 图标动作的界面传 [actionsFillMax] = true，动作区贴右屏、按钮高度
 * 撑满顶栏、宽度收敛为 [TopBarWidthRatio] 倍高度（方形触摸区）；
 * 仅标题+返回或文本动作的简单顶栏保持默认内边距模式。
 *
 * 标题区两种形态（[TopBarTitleButton] / [TopBarPlainTitle]，本文件私有件）：
 *  - 可点击（[onTitleClick] 非 null，如阅读页书名进详情、首页筛选标题）：
 *    整块无边框按压反色（直角背景贴容器起点，即屏幕左缘/返回键右侧），
 *    [titlePrefix]/[titleTrailing] 内容随块反色；[titleEnabled] = false
 *    时置灰不可点；
 *  - 普通：纯文本展示，无按压态。
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
 * @param titlePrefix 标题前缀文本（次级字号 bodyLarge 18sp、与标题同色；
 *   现消费者：组视图面包屑「书架/」前缀，提示可点击标题退回顶层书架）；
 *   两种标题形态均渲染，可点击形态随按压整块反色
 * @param titleStyle 标题样式；null 时用默认 titleLarge（首页传 headlineMedium 大标题档放大）
 * @param titleTrailing 标题尾部内容槽（现消费者：首页书架的筛选图标/
 *   筛选名，随标题内容整块可点），接收与标题同源的内容色——可点击标题
 *   随按压反色，普通形态为静态 onSurface
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
    titlePrefix: String? = null,
    titleStyle: TextStyle? = null,
    titleTrailing: (@Composable (contentColor: Color) -> Unit)? = null,
    actionsFillMax: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
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
            if (onTitleClick != null) {
                TopBarTitleButton(
                    title = title,
                    onClick = onTitleClick,
                    enabled = titleEnabled,
                    onClickLabel = titleClickLabel,
                    prefix = titlePrefix,
                    style = titleStyle,
                    trailing = titleTrailing,
                    modifier = Modifier.weight(1f),
                )
            } else {
                TopBarPlainTitle(
                    title = title,
                    prefix = titlePrefix,
                    style = titleStyle,
                    trailing = titleTrailing,
                    fillHeight = actionsFillMax,
                    modifier = Modifier.weight(1f),
                )
            }
            if (actionsFillMax) {
                // 顶栏动作按钮方形（宽度收敛 = 高度）：TopBarWidthRatio = 1.0
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
 * 可点击标题（EInkTopBar 私有件）：整块无边框按压反色（直角背景贴容器
 * 起点，即屏幕左缘/返回键右侧）；前缀（面包屑）/标题/尾部槽同块渲染、
 * 内容色随按压一致翻转，前缀与标题零间距密排、尾部槽前留距；
 * 行首 16dp 边距内置在按压区之内。
 */
@Composable
private fun TopBarTitleButton(
    title: String,
    onClick: () -> Unit,
    enabled: Boolean,
    onClickLabel: String?,
    prefix: String?,
    style: TextStyle?,
    trailing: (@Composable (contentColor: Color) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    EInkPressBlock(
        onClick = onClick,
        enabled = enabled,
        onClickLabel = onClickLabel,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.fillMaxHeight(),
    ) { colors, _ ->
        Row(
            modifier = Modifier.padding(start = EInkSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prefix != null) {
                EInkText(
                    text = prefix,
                    style = EInkTheme.typography.bodyLarge,
                    color = colors.contentColor,
                )
            }
            EInkText(
                text = title,
                color = colors.contentColor,
                style = style ?: EInkTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(EInkSpacing.s))
                trailing(colors.contentColor)
            }
        }
    }
}

/**
 * 普通标题（EInkTopBar 私有件）：前缀（次级小字）+ 标题 + 尾部槽，
 * 纯展示无按压态；尾部内容色与标题文字同源（静态 onSurface）。
 */
@Composable
private fun TopBarPlainTitle(
    title: String,
    prefix: String?,
    style: TextStyle?,
    trailing: (@Composable (contentColor: Color) -> Unit)?,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = EInkTheme.colorScheme
    Row(
        modifier = modifier.then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix != null) {
            EInkText(
                text = prefix,
                style = EInkTheme.typography.bodyLarge,
                color = colors.onSurface,
                // 前缀紧贴标题（零间距密排），仅保留行首边距
                modifier = Modifier.padding(start = EInkSpacing.m)
            )
        }
        BasicText(
            text = title,
            style = (style ?: EInkTheme.typography.titleLarge)
                .copy(color = colors.onSurface),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (prefix == null) EInkSpacing.m else 0.dp)
        )
        trailing?.invoke(colors.onSurface)
    }
}

/**
 * 顶栏返回按钮：统一 arrow_back 图标，48dp 触控目标，按压瞬时反色（规范 §13/§35）。
 */
@Composable
private fun TopBarBackButton(onClick: () -> Unit) {
    EInkPressBlock(
        onClick = onClick,
        onClickLabel = "返回",
        modifier = Modifier.size(TouchTarget),
    ) { colors, _ ->
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
