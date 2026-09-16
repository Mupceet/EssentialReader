package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.launch

/**
 * 排序模式 ▲▼ 的乐观重排（纯函数）：与相邻行交换，边界/未知 id 原样返回。
 * 真值收敛由宿主 observeGroups 重发完成（LaunchedEffect 清除乐观层）。
 */
internal fun moveGroupInList(
    groups: List<BookshelfGroupUiModel>,
    groupId: Long,
    up: Boolean,
): List<BookshelfGroupUiModel> {
    val index = groups.indexOfFirst { it.groupId == groupId }
    if (index < 0) return groups
    val target = index + if (up) -1 else 1
    if (target !in groups.indices) return groups
    return groups.toMutableList().apply { add(target, removeAt(index)) }
}

/**
 * 书架选择器收起态 chip：跟在顶栏「书架」标题后，显示当前分组名。
 *
 * 样式为描边加重（加粗边框 + 标题级字重，用户 2026-09-16 定案偏好，
 * 不用反色实心）：2dp 描边区别于常规 1dp 元素，▾/▴ 指示展开态。
 * E-Ink 约束：静态绘制，零动画零阴影。
 */
@Composable
fun ShelfGroupChip(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = EInkTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(start = EInkSpacing.s)
            .border(width = 2.dp, color = colors.onSurface, shape = EInkShapes.medium)
            .einkClickable(
                role = Role.Button,
                onClickLabel = "选择分组",
                onClick = onClick,
            )
            .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = if (expanded) "$text ▴" else "$text ▾",
            style = EInkTheme.typography.titleSmall,
            // 顶栏高度固定 56dp：长组名单行截断省略，不换行撑高
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 书架选择器面板（顶栏下方锚定浮层，三态一个组件）：
 *
 * 1. 选择态：行 = 分组名 + 书数，当前组 ✓ 加粗；点行即切组并收起；
 * 2. 排序态（面板头「排序」进入）：行尾 ▲▼ 上/下移分组，每击即调
 *    [onMoveGroup]（宿主 moveGroup 每击落库），「完成」退出（无提交语义）；
 * 3. 分组行列表整页翻页（EInkListPagerState，禁自由滚动——弹层列表
 *    同书架铁律），组数不満一页时翻页箭头置灰。
 *
 * 浮层锚定内容区顶部（挂在 HomeScreen 内容 Box 内，位于顶栏之下、
 * 底部操作栏之上），面板外点击收起；书列表不重排、书架分页状态不动。
 * 排序乐观层：▲▼ 点击先本地重排，宿主 observeGroups 重发即清除
 * （失败时下次分组表变化收敛，spec §6）。
 */
@Composable
fun ShelfSelectorPanel(
    groups: List<BookshelfGroupUiModel>,
    selectedGroupId: Long,
    onSelectGroup: (Long) -> Unit,
    onMoveGroup: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var sortMode by rememberSaveable { mutableStateOf(false) }
    var sortOverride by remember { mutableStateOf<List<BookshelfGroupUiModel>?>(null) }

    // 真值流重发（含自身落库成功/失败/无关计数变化）→ 丢弃乐观层
    LaunchedEffect(groups) { sortOverride = null }

    val display = sortOverride ?: groups
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 不变量：content Box 内浮层的 zIndex 必须高于 HomePane 可见态
            // 的 1f，否则被书架 Pane 盖住绘制且点击穿透到书籍条目
            .zIndex(2f)
    ) {
        // 透明点击层：点面板外空白收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .einkClickable(role = Role.Button, onClickLabel = "收起面板", onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.6f)
                .background(EInkTheme.colorScheme.surface)
                // 消费面板内空白点击，避免透传到关闭层
                .einkClickable(onClick = {}),
        ) {
            // 面板头：标题 + 排序/完成
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EInkText(
                    text = if (sortMode) "调整分组顺序" else "选择分组",
                    style = EInkTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                EInkButton(
                    text = if (sortMode) "完成" else "排序",
                    onClick = {
                        sortOverride = null
                        sortMode = !sortMode
                    },
                    bordered = true,
                )
            }
            EInkHorizontalDivider()
            // 分组行列表（整页翻页，禁自由滚动）
            val pager = rememberEInkListPagerState(display.size)
            LazyColumn(
                state = pager.listState,
                userScrollEnabled = false,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(display) { group ->
                    val index = display.indexOfFirst { it.groupId == group.groupId }
                    if (sortMode) {
                        SortRow(
                            group = group,
                            canUp = index > 0,
                            canDown = index < display.lastIndex,
                            onMove = { up ->
                                sortOverride = moveGroupInList(display, group.groupId, up)
                                onMoveGroup(group.groupId, up)
                            },
                        )
                    } else {
                        SelectRow(
                            group = group,
                            selected = group.groupId == selectedGroupId,
                            onClick = {
                                onSelectGroup(group.groupId)
                                onDismiss()
                            },
                        )
                    }
                }
            }
            // 面板内翻页箭头（组数不満一页时两端置灰）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = EInkSpacing.xxs),
            ) {
                EInkPageArrows(
                    pageUpEnabled = pager.canPageUp(),
                    pageDownEnabled = pager.canPageDown(display.size),
                    onPageUp = { scope.launch { pager.pageUp() } },
                    onPageDown = { scope.launch { pager.pageDown(display.size) } },
                )
            }
        }
    }
}

/** 选择行：✓ 当前组（加粗）+ 分组名 + 书数。 */
@Composable
private fun SelectRow(
    group: BookshelfGroupUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .einkClickable(role = Role.Button, onClickLabel = group.name, onClick = onClick)
            .padding(horizontal = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            EInkText(text = "✓", style = EInkTheme.typography.titleSmall)
            Spacer(modifier = Modifier.padding(start = EInkSpacing.xxs))
        }
        EInkText(
            text = group.name,
            style = if (selected) EInkTheme.typography.titleSmall
            else EInkTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        EInkText(
            text = "${group.bookCount} 本",
            style = EInkTheme.typography.bodyMedium,
        )
    }
}

/** 排序行：分组名 + 书数 + 行尾 ▲▼（边界置灰）。 */
@Composable
private fun SortRow(
    group: BookshelfGroupUiModel,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (up: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = group.name,
            style = EInkTheme.typography.bodyLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        EInkText(text = "${group.bookCount} 本", style = EInkTheme.typography.bodyMedium)
        MoveArrow(text = "▲", enabled = canUp, onClickLabel = "上移${group.name}") { onMove(true) }
        MoveArrow(text = "▼", enabled = canDown, onClickLabel = "下移${group.name}") { onMove(false) }
    }
}

/**
 * 排序箭头：48dp 触控目标，禁用态中灰。禁用态完全退出点击与语义树
 * （与 EInkPageArrows 用 enabled 参数的约定并存，此处选择不挂点击修饰符）。
 */
@Composable
private fun MoveArrow(
    text: String,
    enabled: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = Modifier
            .height(48.dp)
            .padding(horizontal = EInkSpacing.xs)
            .then(
                if (enabled) {
                    Modifier.einkClickable(
                        role = Role.Button,
                        onClickLabel = onClickLabel,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = text,
            style = EInkTheme.typography.titleSmall,
            color = if (enabled) colors.onSurface else colors.outline,
        )
    }
}
