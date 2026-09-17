package io.legado.app.eink.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.navigation.EInkTopBar
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
 * 骨架参考目录界面（TocScreen）：顶栏（标题 + 关闭）+ 列表 + 底部
 * 操作栏（返回 / 切换字体文件夹 / 翻页胶囊）。
 *
 * - 字体名单行省略号（去扩展名，同一级弹层口径，行内左右留 [EInkSpacing.l]
 *   边距）；选中行按 DS §42 用左侧实心竖条 + 名称加粗（大面积持久反色
 *   残影重），按压仍瞬时反色（§35）；
 * - 打开时定位到当前选中字体所在页（jumpToItemAligned），定位完成前以
 *   surface 色遮盖列表防闪现第一页（同目录页 positioned 先例）；
 * - 底栏「定位到当前」回到选中字体所在页（未选/幽灵选中回第一页，写法同
 *   目录页「回到当前」）；「选择字体文件夹」图标（空心描边版）随时可换
 *   文件夹（SAF），列表即时刷新；
 * - 系统栏避让：顶部用菜单层固定避让快照（[topInset]，宿主
 *   rememberStatusBarTop 口径——不跟随状态栏回归动画插值）；左右/底部
 *   同 readerSystemBarInsets 口径（displayCutout ∪ systemBars）；
 * - 回退：顶栏关闭 / 底栏返回 / 返回键只关本级（一级字体弹层保留）；
 *   全屏本体无背板可点；
 * - 空态：无字体时居中提示，翻页箭头置灰。
 */
@Composable
internal fun ReaderFontPickerOverlay(
    fontOptions: List<ReaderFontOption>,
    selectedPath: String?,
    topInset: Dp,
    onSelect: (ReaderFontOption) -> Unit,
    onPickFolder: () -> Unit,
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
        pager.jumpToItemAligned(selectedFontIndex(fontOptions, selectedPath))
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

    // 定位到当前选中字体（未选/幽灵选中回第一页）；写法同目录页「回到当前」
    // （jumpToItemAligned，不带翻页刷新意图）
    val locateCurrent: () -> Unit = remember(pager, fontOptions, selectedPath, scope) {
        {
            scope.launch {
                pager.jumpToItemAligned(selectedFontIndex(fontOptions, selectedPath))
            }
        }
    }

    BackHandler(onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.surface)
            // 左右/底部避让 displayCutout ∪ systemBars（同 ReaderScreen
            // .readerSystemBarInsets 口径；顶部走快照，不在此处避让）
            .windowInsetsPadding(
                WindowInsets.displayCutout
                    .union(WindowInsets.systemBars)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            // 顶部菜单层固定避让快照：不跟随状态栏回归动画插值逐步顶下
            .padding(top = topInset),
    ) {
        // 顶栏：标题 + 关闭（关闭同返回，只关本级；图标钮写法同目录页顶栏）
        EInkTopBar(
            title = if (fontOptions.isEmpty()) "选择字体" else "选择字体（${fontOptions.size}）",
            actions = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_close),
                    contentDescription = "关闭",
                    onClick = onClose,
                )
            },
        )
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
        // 底部操作栏（同目录页）：返回 / 切换字体文件夹 居左 + 翻页胶囊；
        // 翻页可用状态收敛在箭头槽叶作用域读取（翻页只重组箭头两个图标）
        EInkOperationBar(
            tabs = emptyList(),
            selectedTabIndex = 0,
            onTabSelect = {},
            navigationIcon = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_arrow_back),
                    contentDescription = "返回",
                    onClick = onClose,
                )
            },
            actions = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_toc_locate),
                    contentDescription = "定位到当前字体",
                    onClick = locateCurrent,
                )
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_folder),
                    contentDescription = "选择字体文件夹",
                    onClick = onPickFolder,
                )
            },
            pageArrows = {
                EInkPageArrows(
                    pageUpEnabled = pager.canPageUp(),
                    pageDownEnabled = pager.canPageDown(fontOptions.size),
                    onPageUp = pageUp,
                    onPageDown = pageDown,
                )
            },
        )
    }
}

/** 当前选中文件字体下标（按 path 匹配）；未选/幽灵选中归 0。 */
private fun selectedFontIndex(
    fontOptions: List<ReaderFontOption>,
    selectedPath: String?,
): Int = selectedPath
    ?.let { path -> fontOptions.indexOfFirst { it.path == path } }
    ?.takeIf { it >= 0 } ?: 0

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
            .padding(horizontal = EInkSpacing.l),
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
