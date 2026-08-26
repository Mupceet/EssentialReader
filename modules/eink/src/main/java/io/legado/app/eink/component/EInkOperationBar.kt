package io.legado.app.eink.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.theme.EInkSpacing

/**
 * 底部通用操作栏（参考微信读书墨水屏版）。
 *
 * 结构：图标按钮（[EInkOperationBarIcon] 默认尺寸：高度撑满操作栏、
 * 宽度自适应 —— min(屏幕宽/5, 1.7 倍高度)）居左连续排列
 * （不与屏幕留边距、按钮彼此紧邻），
 * 中间留白，右侧固定上/下翻页胶囊 [EInkPageArrows]（竖线分隔，
 * 距屏幕右侧留 [EInkSpacing.m] 边距）。
 *
 * 选中态：Tab 选中不使用实心色块，按钮保持白底，仅图标切换为
 * 填充变体素材（见 [EInkOperationTab] 的素材成对要求）。
 *
 * E-Ink 约束：
 *  - 不可翻页（列表已到顶/到底、或当前内容不可翻页）时箭头置灰
 *    （[io.legado.app.eink.theme.EInkColorScheme.disabledContent] 中灰，
 *    非 alpha 混合，避免残影）；
 *  - 零动画：Tab 切换与翻页均为状态直接替换。
 *
 * 该操作栏在各界面普遍存在；无 Tab 的界面传 [tabs] 为空列表即可，
 * 此时左侧仅保留 [navigationIcon]，右侧翻页按钮。
 *
 * @param tabs Tab 列表（图标素材对 + 无障碍文案，从左到右）
 * @param selectedTabIndex 当前选中的 Tab 下标
 * @param onTabSelect 点击 Tab 回调，参数为下标
 * @param navigationIcon 最左侧导航槽（如返回按钮），非首页常用；与 Tab 可并存
 * @param actions 左侧动作槽（如 目录/阅读），排在导航与 Tab 之后、留白之前，
 * 与左侧按钮连续排列
 * @param pageUpEnabled 上翻（向列表上方翻一页）是否可用
 * @param pageDownEnabled 下翻是否可用
 * @param onPageUp 点击上翻箭头
 * @param onPageDown 点击下翻箭头
 * @param pageArrows 翻页箭头自定义槽：非空时替代内置 [EInkPageArrows]。
 * 供承载层把翻页可用状态的读取收敛到箭头叶作用域（如书架固定页分页的
 * canPageUp/canPageDown 读 mutableStateOf，在 Route 层读取会让整个首页
 * 随每次翻页重组），槽内自行组合 [EInkPageArrows]
 */
@Composable
fun EInkOperationBar(
    tabs: List<EInkOperationTab>,
    selectedTabIndex: Int,
    onTabSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    pageUpEnabled: Boolean = false,
    pageDownEnabled: Boolean = false,
    onPageUp: () -> Unit = {},
    onPageDown: () -> Unit = {},
    pageArrows: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        EInkHorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .padding(end = EInkSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon?.invoke()
            tabs.forEachIndexed { index, tab ->
                EInkOperationBarIcon(
                    icon = tab.icon,
                    selectedIcon = tab.selectedIcon,
                    contentDescription = tab.contentDescription,
                    selected = index == selectedTabIndex,
                    role = Role.Tab,
                    onClick = { onTabSelect(index) },
                )
            }
            actions?.invoke()
            Spacer(modifier = Modifier.weight(1f))
            pageArrows?.invoke() ?: EInkPageArrows(
                pageUpEnabled = pageUpEnabled,
                pageDownEnabled = pageDownEnabled,
                onPageUp = onPageUp,
                onPageDown = onPageDown
            )
        }
    }
}

/**
 * 操作栏 Tab：图标素材对 + 无障碍文案。
 *
 * 素材成对要求（规范 §35/§42 操作条图标按钮选中层）：操作条图标
 * 必须提供两个变体 —— [icon] 线性/描边（未选中）+ [selectedIcon]
 * 填充（选中），沿用 View 版底栏 `_e` / `_s` 资源命名约定。
 * 选中态不使用实心色块，按钮保持白底，仅图标切换为填充变体；
 * 后续新增素材同样必须成对提供。
 */
class EInkOperationTab(
    val icon: Painter,
    val selectedIcon: Painter,
    val contentDescription: String,
)

/** 操作栏高度（触控目标 ≥48dp + 上下留白），图标按钮高度撑满该值。 */
private val BarHeight = 56.dp
