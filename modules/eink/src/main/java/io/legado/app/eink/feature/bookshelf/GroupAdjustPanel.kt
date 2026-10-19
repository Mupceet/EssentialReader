package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.zIndex
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.control.EInkCloseButton
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 调整分组面板（2026-10-17 改版）：底部弹框（同调整筛选器，共用
 * [BottomAdjustPanel] 骨架）——全量用户组（含隐藏组，行名置灰），
 * 每行 位次 + 组名 + 显隐开关 + ▲▼，退出时一次性整表提交 order+show；
 * 翻页由界面底部操作栏 ▲▼ 驱动（[pageIndex] 由承载层传入）。
 */
@Composable
fun GroupAdjustPanel(
    groups: List<BookshelfGroupUiModel>,
    pageSize: Int,
    pageIndex: Int,
    onPageSizeMeasured: (Int) -> Unit,
    onApplyAdjustments: (orderedGroupIds: List<Long>, shownGroupIds: Set<Long>) -> Unit,
    onHideBlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    BottomAdjustPanel(
        title = "调整分组",
        items = groups,
        pageSize = pageSize,
        pageIndex = pageIndex,
        onPageSizeMeasured = onPageSizeMeasured,
        onApplyAdjustments = onApplyAdjustments,
        onHideBlocked = onHideBlocked,
        onDismiss = onDismiss,
    )
}

/**
 * 底部调整面板高度上限比（2026-10-17 真机反馈二改）：面板不超过内容区高
 * 的 [ADJUST_PANEL_HEIGHT_RATIO]。每页行数不再静态估算（估高偏小曾致首页
 * 末行被裁），改为**实测反推**——标题栏与首行经 onSizeChanged 实测高度，
 * （上限 − 标题栏）÷ 行高 即每页行数；放不下自动分页。
 */
internal const val ADJUST_PANEL_HEIGHT_RATIO = 0.75f

/** 实测前的保守初值（初帧少排几行，测量回报后立即校正）。 */
internal const val ADJUST_PAGE_SIZE_FALLBACK = 8

internal fun adjustPageSizeMeasured(capDp: Dp, headerDp: Dp, rowDp: Dp): Int =
    ((capDp - headerDp) / rowDp).toInt().coerceAtLeast(1)

/**
 * 底部调整面板骨架（调整分组/调整筛选器共用，2026-10-17 真机反馈改版）：
 * 内容区底部锚定、**样式与书架一致**（通宽 surface 底 + 上缘 1dp 分隔线，
 * 无圆角无边框盒）；标题行右置「完成」；行 = 位次 + 名称 + 显隐开关 + ▲▼
 * （隐藏项行名置灰）；翻页复用界面底部操作栏（无自带页脚，多页时仅提示
 * 页码）；退出（完成/面板外点击）时一次性整表提交新序与显隐集。
 * 外层全屏点击层同时阻断对底层书架的输入。
 */
@Composable
internal fun BottomAdjustPanel(
    title: String,
    items: List<BookshelfGroupUiModel>,
    pageSize: Int,
    pageIndex: Int,
    onPageSizeMeasured: (Int) -> Unit,
    onApplyAdjustments: (orderedIds: List<Long>, shownIds: Set<Long>) -> Unit,
    onHideBlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    var orderedIds by remember(items.map { it.groupId }) {
        mutableStateOf(items.map { it.groupId })
    }
    var shownIds by remember(items.map { it.groupId }) {
        mutableStateOf(items.filter { it.shown }.map { it.groupId }.toSet())
    }
    val byId = remember(items) { items.associateBy { it.groupId } }
    val pageCount = (orderedIds.size + pageSize - 1) / pageSize
    val safeIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val page = orderedIds.drop(safeIndex * pageSize).take(pageSize)
    val orderChanged = orderedIds != items.map { it.groupId }
    val shownChanged = shownIds != items.filter { it.shown }.map { it.groupId }.toSet()

    fun applyAndDismiss() {
        if (orderChanged || shownChanged) onApplyAdjustments(orderedIds, shownIds)
        onDismiss()
    }

    // 返回键与点面板外同义（提交并退出）：首页是导航栈根，不拦截返回会
    // 直接透传系统退出应用，静默丢弃未提交的重排/显隐
    androidx.activity.compose.BackHandler(onBack = ::applyAndDismiss)

    // 模态在场登记（与 EInkDialog 弹框同一登记点）：面板打开期间音量键
    // 翻页等外围按键输入放行系统，不在面板背后穿透翻动书架
    androidx.compose.runtime.DisposableEffect(Unit) {
        io.legado.app.eink.designsystem.control.EInkModalPresence.acquire()
        onDispose { io.legado.app.eink.designsystem.control.EInkModalPresence.release() }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(2f)
            .einkClickable(role = Role.Button, onClickLabel = "关闭") { applyAndDismiss() },
    ) {
        // 实测反推每页行数：上限 − 标题栏（首行同测）。**实测落定前面板
        // 移出视口**（offset 到屏外——仍布局可测、不绘制不上墨），落定后
        // 归位一次性可见：避免回退行数先排一帧、实测行数再重排一帧的闪跳
        val density = LocalDensity.current
        val capDp = maxHeight * ADJUST_PANEL_HEIGHT_RATIO
        var headerDpVal by remember { mutableStateOf<Dp?>(null) }
        var rowDpVal by remember { mutableStateOf<Dp?>(null) }
        var settled by remember { mutableStateOf(false) }
        val measuredPageSize = if (headerDpVal != null && rowDpVal != null) {
            adjustPageSizeMeasured(capDp, headerDpVal!!, rowDpVal!!)
        } else {
            null
        }
        androidx.compose.runtime.SideEffect {
            measuredPageSize?.let {
                if (it != pageSize) onPageSizeMeasured(it)
                settled = true
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 实测落定前移出视口（布局可测、屏外不绘制）
                .offset(y = if (settled) 0.dp else 10000.dp)
                .background(EInkTheme.colorScheme.surface)
                // 高度上限：内容区高的 75%（与 pageSize 推导同口径；超出即
                // 由分页承接，面板不无限撑高）
                .heightIn(max = maxHeight * ADJUST_PANEL_HEIGHT_RATIO)
                .einkClickable(onClick = {}),  // 消费面板内空白点击，不透传收起
        ) {
            // 上缘分隔线（书架样式：直线分界，无圆角边框盒）
            EInkHorizontalDivider()
            // 标题栏与阅读界面常态面板（ReaderPanelContainer）完全同规格：
            // titleMedium + start/end=m、vertical=s（上下对称 8）+ 36dp 关闭钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        with(density) { headerDpVal = size.height.toDp() }
                    }
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EInkText(
                    text = title,
                    style = EInkTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                EInkCloseButton(onClose = { applyAndDismiss() })
            }
            EInkHorizontalDivider()
            page.forEachIndexed { offset, id ->
                val index = safeIndex * pageSize + offset
                val item = byId[id]
                val shown = id in shownIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            // 首行实测行高（含内边距与最高子元素——分段按钮/
                            // 箭头随字体缩放的实际高度）
                            if (offset == 0) Modifier.onSizeChanged { size ->
                                with(density) { rowDpVal = size.height.toDp() }
                            } else Modifier
                        )
                        .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EInkText(
                        text = "${index + 1}",
                        style = EInkTheme.typography.bodyMedium,
                        color = EInkTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = EInkSpacing.s),
                    )
                    EInkText(
                        text = item?.name ?: "",
                        style = EInkTheme.typography.bodyLarge,
                        color = if (shown) EInkTheme.colorScheme.onSurface
                        else EInkTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    // 多状态分段按钮（显示 | 隐藏）：轨道相连两端圆角、
                    // 选中态圆角矩形实心高亮（替代原单文本钮，状态更明确）
                    io.legado.app.eink.designsystem.control.EInkSegmentedButton(
                        options = listOf("显示", "隐藏"),
                        selectedIndex = if (shown) 0 else 1,
                        onSelect = { target ->
                            // 守卫：至少保留一项显示——全部隐藏后再无入口进入
                            // 调整（条目全消失/筛选菜单仅剩无筛选）
                            val wouldHide = target == 1 && shown
                            if (wouldHide && shownIds.size <= 1) {
                                onHideBlocked()
                            } else if ((target == 0) != shown) {
                                shownIds = if (target == 0) shownIds + id else shownIds - id
                            }
                        },
                        modifier = Modifier
                            .width(116.dp)
                            .padding(end = EInkSpacing.s),
                    )
                    TextArrowButton("▲", enabled = index > 0) {
                        swapAdjust(orderedIds, index, -1)?.let { orderedIds = it }
                    }
                    TextArrowButton("▼", enabled = index < orderedIds.lastIndex) {
                        swapAdjust(orderedIds, index, +1)?.let { orderedIds = it }
                    }
                }
            }
        }
    }
}

/** 全局相邻换位；跨页边界后条目自然流入邻页（整表重绘、页索引夹取）。 */
internal fun swapAdjust(ids: List<Long>, index: Int, delta: Int): List<Long>? {
    val target = index + delta
    if (target < 0 || target >= ids.size) return null
    return ids.toMutableList().apply { add(target, removeAt(index)) }
}

/** 排序箭头文本按钮（底部调整面板共用）。 */
@Composable
internal fun TextArrowButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    EInkText(
        text = symbol,
        style = EInkTheme.typography.titleMedium,
        color = if (enabled) EInkTheme.colorScheme.onSurface else EInkTheme.colorScheme.outline,
        modifier = Modifier
            .einkClickable(enabled = enabled, role = Role.Button, onClickLabel = symbol, onClick = onClick)
            .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.xxs),
    )
}
