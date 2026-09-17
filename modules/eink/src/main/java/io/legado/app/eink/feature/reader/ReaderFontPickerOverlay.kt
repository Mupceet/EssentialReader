package io.legado.app.eink.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkCloseButton
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 全屏字体选择浮层（字体配置弹层的二级）：单列整行显示文件夹字体，
 * 复用全仓全屏列表分页定式（[rememberEInkListPagerState]：LazyColumn
 * 禁滚 + 首布局实测页容量 + 整页跳转；上下滑动手势识别为翻页，同 ▲▼）。
 *
 * - 字体名单行省略号（去扩展名，同一级弹层口径）；选中行按 DS §42 用
 *   左侧实心竖条 + 名称加粗（大面积持久反色残影重），按压仍瞬时反色（§35）；
 * - 页脚（页码 + 箭头）仅字体数超过一页容量时渲染；打开时定位到当前
 *   选中字体所在页（jumpToItemAligned），定位完成前以 surface 色遮盖
 *   列表防闪现第一页（同目录页 positioned 先例）；
 * - 回退：× / 返回键只关本级（一级字体弹层保留）；全屏本体无背板可点；
 * - 空态：无字体时居中提示，无页脚。
 */
@Composable
internal fun ReaderFontPickerOverlay(
    fontOptions: List<ReaderFontOption>,
    selectedPath: String?,
    onSelect: (ReaderFontOption) -> Unit,
    onClose: () -> Unit,
) {
    val pager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val refresh = LocalEInkRefreshController.current

    // 初始定位：等首布局测出页容量后跳到选中字体所在页；未选中/幽灵选中归 0。
    // 浮层每次打开重新组合（remember 随卸载复位），定位只在打开时执行一次
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(fontOptions, selectedPath) {
        if (fontOptions.isEmpty()) {
            positioned = true
            return@LaunchedEffect
        }
        if (!positioned) {
            snapshotFlow { pager.pageItemCount }.first { it > 0 }
        }
        val selected = selectedPath
            ?.let { path -> fontOptions.indexOfFirst { it.path == path } }
            ?.takeIf { it >= 0 } ?: 0
        pager.jumpToItemAligned(selected)
        positioned = true
    }
    // 数据原地变化（文件夹重扫）后把实际位置拉回页首（防御）
    LaunchedEffect(fontOptions.size) {
        pager.realignToPageStart(fontOptions.size)
    }

    // 翻页动作 remember 稳定实例 + 翻页刷新意图（同目录页口径）
    val pageUp: () -> Unit = remember(pager, refresh, scope) {
        {
            scope.launch { pager.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageDown: () -> Unit = remember(pager, fontOptions.size, refresh, scope) {
        {
            scope.launch { pager.pageDown(fontOptions.size) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }

    BackHandler(onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.surface),
    ) {
        // 标题行（同 EInkDialog 面板形态：标题 + × + 通幅分隔线）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = EInkSpacing.m,
                    end = EInkSpacing.m,
                    top = EInkSpacing.m,
                    bottom = EInkSpacing.s,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EInkText(
                text = if (fontOptions.isEmpty()) "选择字体" else "选择字体（${fontOptions.size}）",
                style = EInkTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EInkCloseButton(onClose = onClose)
        }
        EInkHorizontalDivider()
        // 列表区 / 空态
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (fontOptions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EInkText(
                        text = "文件夹内暂无 .ttf/.otf 字体",
                        style = EInkTheme.typography.bodyMedium,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 不支持自由滚动：上下滑动手势识别为整页翻页，与 ▲▼ 同一动作
                LazyColumn(
                    state = pager.listState,
                    userScrollEnabled = false,
                    overscrollEffect = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .EInkPageSwipe(
                            onPageUp = pageUp,
                            onPageDown = pageDown,
                        ),
                ) {
                    items(fontOptions, key = { it.path }) { option ->
                        FontPickerRow(
                            label = option.name.substringBeforeLast("."),
                            selected = option.path == selectedPath,
                            onClick = { onSelect(option) },
                        )
                    }
                }
                // 初始定位未完成时遮盖列表：LazyListState 初始在第 0 项，直接
                // 显示会先闪现第一页再跳转；列表保持参与布局（驱动页容量测量）
                if (!positioned) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(EInkTheme.colorScheme.surface),
                    )
                }
            }
        }
        // 页脚：页码 + 翻页箭头，仅字体数超过一页容量时渲染（单页不渲染不占位）
        val pageSize = pager.pageItemCount
        if (fontOptions.isNotEmpty() && pageSize > 0 && fontOptions.size > pageSize) {
            val pageCount = (fontOptions.size + pageSize - 1) / pageSize
            val pageNumber = (pager.pageStart / pageSize).coerceIn(0, pageCount - 1) + 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EInkText(
                    text = "$pageNumber/$pageCount 页",
                    style = EInkTheme.typography.bodySmall,
                    color = EInkTheme.colorScheme.outline,
                )
                EInkPageArrows(
                    pageUpEnabled = pager.canPageUp(),
                    pageDownEnabled = pager.canPageDown(fontOptions.size),
                    onPageUp = pageUp,
                    onPageDown = pageDown,
                )
            }
        }
        // 底部收边
        EInkHorizontalDivider()
    }
}

/** 选中标记尺寸：左侧实心竖条（§42 additive inking：加黑比去黑可靠）。 */
private val FontRowMarkWidth = 4.dp
private val FontRowMarkHeight = 16.dp

/**
 * 字体行：定高 44dp（触控目标；等高行是分页不变量），单行省略号。
 * 选中 = 左侧实心竖条 + 名称加粗（§42 不用整行持久反色）；按压瞬时
 * 反色（§35）。写法同目录页 ChapterItem（TocScreen.kt）。
 */
@Composable
private fun FontPickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EInkSpacing.s),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(width = FontRowMarkWidth, height = FontRowMarkHeight)
                    .background(if (press.isPressed) scheme.surface else scheme.onSurface),
            )
        }
        EInkText(
            text = label,
            style = EInkTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else null,
            color = when {
                press.isPressed -> colors.contentColor
                selected -> scheme.onSurface
                else -> scheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
