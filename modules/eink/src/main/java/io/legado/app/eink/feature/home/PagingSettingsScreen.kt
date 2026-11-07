package io.legado.app.eink.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.app.hubPagingKeyEvent
import io.legado.app.eink.app.hubWheelPagingEvent
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.control.EInkModalPresence
import io.legado.app.eink.designsystem.interaction.EInkPressBlock
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.pager.formatKeyCodes
import io.legado.app.eink.designsystem.pager.parseKeyCodes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.delay

/**
 * 翻页设置页（入口「我的 → 翻页设置」，目的地
 * [io.legado.app.eink.app.EInkScreen.PagingSettings]）：
 * 三项翻页输入的统一设置面——音量键翻页（开关）、鼠标滚轮翻页（开关）、
 * 自定义翻页按键（捕获式录入）。三者在 eink 模式全局生效（阅读页 +
 * 各分页列表，与音量键全局化同套语义）；音量/滚轮与完整模式同键共享
 * 存储，自定义键经宿主 prevKeys/nextKeys 同键（完整模式阅读器同样
 * 生效）。
 *
 * 预览条与生产判定**同源**：本页向 keyEventHub/wheelHub 栈顶注册处理
 * 器，直接复用 hubPagingKeyEvent/hubWheelPagingEvent（事件时实时读设
 * 置），命中即页内反色提示 [HINT_DURATION_MS] 并消费按键（不翻页）
 * ——预览即真实，可验证三项设置组合效果；开关关闭时按键回落系统行为
 * （音量调节照常、滚轮放行页面滚动）。
 *
 * 弹框录入：点方向行（配置行形态，摘要+箭头）打开录入弹框，弹框内按
 * 物理键即录为待定键（一键一方向，录入自动迁出另一方向；非正键码静默
 * 忽略），返回键/取消丢弃待定、保存才经 setPageKeys 两方向一次原子提
 * 交（本地乐观状态保显示，fire-and-forget 同「我的」开关）；清除动作
 * 一键清空本方向待定。返回键不可录为翻页键（导航必需）；滚轮只预览
 * 不可录入。单处理器内分支（弹框录入/预览），避免双处理器栈序竞态。
 *
 * 无 ViewModel（对齐 Mine/FontSettings 先例）：全部同步端口读写 +
 * Compose 本地状态，提示计时 LaunchedEffect。本页无分页列表，不接
 * 翻页手势。
 */
@Composable
fun PagingSettingsRoute(onBack: () -> Unit) {
    val globalSettings = EInkEngineRegistry.globalSettings
    // fire-and-forget 写路径配本地乐观状态（getter 不保证立即可见新值）
    var volumeKeyPage by remember { mutableStateOf(globalSettings.volumeKeyPage) }
    var mouseWheelPage by remember { mutableStateOf(globalSettings.mouseWheelPage) }
    var prevKeys by remember { mutableStateOf(parseKeyCodes(globalSettings.prevKeys)) }
    var nextKeys by remember { mutableStateOf(parseKeyCodes(globalSettings.nextKeys)) }
    // 录入弹框方向（null = 关闭）；弹框内待定键对——打开时以当前键对
    // 为初值，保存才落盘，取消/返回原样丢弃
    var dialogDirection by remember { mutableStateOf<PagingDirection?>(null) }
    var dialogPrevKeys by remember { mutableStateOf(setOf<Int>()) }
    var dialogNextKeys by remember { mutableStateOf(setOf<Int>()) }

    fun openKeyDialog(direction: PagingDirection) {
        dialogPrevKeys = prevKeys
        dialogNextKeys = nextKeys
        dialogDirection = direction
    }

    var hintDirection by remember { mutableStateOf<PagingDirection?>(null) }
    var hintSequence by remember { mutableStateOf(0) }

    fun flashHint(direction: PagingDirection) {
        hintDirection = direction
        hintSequence++
    }

    fun commitKeys(prev: Set<Int>, next: Set<Int>) {
        prevKeys = prev
        nextKeys = next
        globalSettings.setPageKeys(formatKeyCodes(prev), formatKeyCodes(next))
    }

    // 提示计时：命中反色 1.5s；同方向再命中序号自增刷新计时，异方向
    // 命中立即切换并重计时；无命中无变化
    LaunchedEffect(hintSequence) {
        if (hintDirection != null) {
            delay(HINT_DURATION_MS)
            hintDirection = null
        }
    }

    // 预览 + 录入（单处理器分支）：弹框打开时全量捕键录入待定集；常态
    // 复用生产判定只提示不翻页（rememberUpdatedState 保证注册 lambda
    // 读到最新态）
    val keyHub = EInkEngineRegistry.keyEventHub
    val currentDialogDirection by rememberUpdatedState(dialogDirection)
    val currentDialogPrevKeys by rememberUpdatedState(dialogPrevKeys)
    val currentDialogNextKeys by rememberUpdatedState(dialogNextKeys)
    DisposableEffect(keyHub) {
        val unregister = keyHub.register { event ->
            val dialogDir = currentDialogDirection
            when {
                // 录入弹框在场：本页自有弹框，物理键全部捕获录入待定集
                // （一键一方向、迁移即时反映在待定集）；BACK 经
                // handleCaptureKey 收起弹框（丢弃待定）——弹框的模态
                // 登记对本分支不适用，须先于模态检查判定
                dialogDir != null -> handleCaptureKey(
                    action = event.action,
                    keyCode = event.keyCode,
                    repeatCount = event.repeatCount,
                    direction = dialogDir,
                    prevKeyCodes = currentDialogPrevKeys,
                    nextKeyCodes = currentDialogNextKeys,
                    onRecorded = { prev, next ->
                        dialogPrevKeys = prev
                        dialogNextKeys = next
                    },
                    onExitCapture = { dialogDirection = null },
                )
                // 其它模态弹框在场时不录键也不预览，放行系统/Compose
                // （与预览分支 hubPagingKeyEvent 的 modalPresent 同语义）
                EInkModalPresence.isPresent -> false
                else -> hubPagingKeyEvent(
                    event,
                    onPageUp = { flashHint(PagingDirection.PREV) },
                    onPageDown = { flashHint(PagingDirection.NEXT) },
                )
            }
        }
        onDispose { unregister() }
    }

    // 滚轮仅预览（不可录入）：与生产同源
    val wheelHub = EInkEngineRegistry.wheelHub
    DisposableEffect(wheelHub) {
        val unregister = wheelHub.register { axis ->
            hubWheelPagingEvent(
                axis,
                onPageUp = { flashHint(PagingDirection.PREV) },
                onPageDown = { flashHint(PagingDirection.NEXT) },
            )
        }
        onDispose { unregister() }
    }

    PagingSettingsScreen(
        volumeKeyPage = volumeKeyPage,
        mouseWheelPage = mouseWheelPage,
        prevKeys = prevKeys,
        nextKeys = nextKeys,
        hintDirection = hintDirection,
        onVolumeKeyPageToggle = {
            val next = !volumeKeyPage
            globalSettings.volumeKeyPage = next
            volumeKeyPage = next
        },
        onMouseWheelPageToggle = {
            val next = !mouseWheelPage
            globalSettings.mouseWheelPage = next
            mouseWheelPage = next
        },
        onOpenKeyDialog = { openKeyDialog(it) },
        onBack = onBack,
    )

    // 录入弹框（Screen 根 Box 直下覆盖层语义；EInkDialog 自带返回收起
    // 与模态登记，与 hub 分支配合见上）
    dialogDirection?.let { direction ->
        KeyCaptureDialog(
            direction = direction,
            prevKeys = dialogPrevKeys,
            nextKeys = dialogNextKeys,
            onKeyRemove = { code ->
                if (direction == PagingDirection.PREV) {
                    dialogPrevKeys = dialogPrevKeys - code
                } else {
                    dialogNextKeys = dialogNextKeys - code
                }
            },
            onClear = {
                if (direction == PagingDirection.PREV) {
                    dialogPrevKeys = emptySet()
                } else {
                    dialogNextKeys = emptySet()
                }
            },
            onConfirm = {
                commitKeys(dialogPrevKeys, dialogNextKeys)
                dialogDirection = null
            },
            onDismiss = { dialogDirection = null },
        )
    }
}

/** 预览命中提示的持续时长。 */
private const val HINT_DURATION_MS = 1_500L

/**
 * 无状态翻页设置页外壳 — 居中标题 + 两开关行（单行，开关名自明）+
 * 自定义翻页按键分组（录入说明一行 + 左右并排双方向卡，点开录入弹框）
 * + 预览分组（与自定义分组同款「分组标题 + 说明行」风格，顶部加大
 * 间距与设置区隔离，方向块左右分布等宽）+ 底部返回操作栏（备份与
 * 恢复页同款）。内容整体可纵向滚动（常态一屏放得下，滚动仅为小屏
 * 溢出兜底；滚轮开关开启时滚轮被预览消费，页面不依赖滚轮滚动）。
 */
@Composable
private fun PagingSettingsScreen(
    volumeKeyPage: Boolean,
    mouseWheelPage: Boolean,
    prevKeys: Set<Int>,
    nextKeys: Set<Int>,
    hintDirection: PagingDirection?,
    onVolumeKeyPageToggle: () -> Unit,
    onMouseWheelPageToggle: () -> Unit,
    onOpenKeyDialog: (PagingDirection) -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部：居中标题（返回在底部操作栏，备份与恢复页同款）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                EInkText(text = "翻页设置", style = EInkTheme.typography.titleLarge)
            }
            EInkHorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                PagingToggleRow(
                    label = "音量键翻页",
                    checked = volumeKeyPage,
                    onToggle = onVolumeKeyPageToggle,
                )
                EInkHorizontalDivider()
                PagingToggleRow(
                    label = "鼠标滚轮翻页",
                    checked = mouseWheelPage,
                    onToggle = onMouseWheelPageToggle,
                )
                EInkHorizontalDivider()

                EInkText(
                    text = "自定义翻页按键",
                    style = EInkTheme.typography.titleMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        top = EInkSpacing.m,
                        bottom = EInkSpacing.xxs,
                    ),
                )
                // 录入说明置于页面（弹框内不再重复），与分组标题同段
                EInkText(
                    text = "点击录入或修改，按下按键自动添加",
                    style = EInkTheme.typography.bodyMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        top = EInkSpacing.xxs,
                        bottom = EInkSpacing.s,
                    ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EInkSpacing.m),
                ) {
                    PageKeyCard(
                        label = "上一页",
                        keyCodes = prevKeys,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenKeyDialog(PagingDirection.PREV) },
                    )
                    Spacer(modifier = Modifier.width(EInkSpacing.m))
                    PageKeyCard(
                        label = "下一页",
                        keyCodes = nextKeys,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenKeyDialog(PagingDirection.NEXT) },
                    )
                }
                EInkHorizontalDivider()

                // 预览分组
                EInkText(
                    text = "预览",
                    style = EInkTheme.typography.titleMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        top = EInkSpacing.m,
                        bottom = EInkSpacing.xxs,
                    ),
                )
                EInkText(
                    text = "按下翻页键或滚动滚轮，命中的方向将会高亮",
                    style = EInkTheme.typography.bodyMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        top = EInkSpacing.xxs,
                        bottom = EInkSpacing.s,
                    ),
                )
                PagePreviewPanel(hintDirection = hintDirection)
            }

            // 底部操作栏：仅返回（本页无翻页列表）
            EInkOperationBar(
                tabs = emptyList(),
                selectedTabIndex = 0,
                onTabSelect = {},
                navigationIcon = {
                    EInkOperationBarIcon(
                        icon = painterResource(R.drawable.eink_ic_arrow_back),
                        contentDescription = "返回",
                        onClick = onBack,
                    )
                },
            )
        }
    }
}

/**
 * 行为开关行（单行：开关名自明，无需副文案）：左侧开关名纯展示，
 * 右侧开/关块即开关本体（EInkButton，开启实心、Role.Switch）。
 */
@Composable
private fun PagingToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkText(
            text = label,
            style = EInkTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.padding(start = EInkSpacing.s))
        EInkButton(
            text = if (checked) "开" else "关",
            onClick = onToggle,
            modifier = Modifier.width(64.dp),
            selected = checked,
            role = Role.Switch,
        )
    }
}

/**
 * 方向卡（左右并排双卡，各占一半）：方向名居中 + 已录键名摘要（空 =
 * 未设置）；点卡打开录入弹框——录入/移除/清除全部在弹框内完成，卡
 * 本身无编辑态，内容只在保存后变化，无行内状态切换的布局跳动。
 */
@Composable
private fun PageKeyCard(
    label: String,
    keyCodes: Set<Int>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    EInkPressBlock(
        onClick = onClick,
        modifier = modifier,
    ) { colors, _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.s, vertical = EInkSpacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EInkText(
                text = label,
                style = EInkTheme.typography.titleMedium,
                color = colors.contentColor,
            )
            EInkText(
                text = if (keyCodes.isEmpty()) {
                    "未设置"
                } else {
                    keyCodes.joinToString(separator = "、") { pageKeyDisplayName(it) }
                },
                style = EInkTheme.typography.bodyMedium,
                color = colors.secondaryContentColor,
                modifier = Modifier.padding(top = EInkSpacing.xxs),
            )
        }
    }
}

/**
 * 方向按键录入弹框：按物理键自动录入待定集（一键一方向、自动迁出
 * 另一方向），点键块移除单个键，清除一键清空本方向待定；保存落盘
 * （两方向一次原子提交），取消/返回丢弃待定。录入说明在页面分组
 * 标题下（不占弹框）。BACK 收起由 EInkDialog 自带，物理键捕获经
 * Route 的枢纽录入分支（见 [PagingSettingsRoute]）。
 */
@Composable
private fun KeyCaptureDialog(
    direction: PagingDirection,
    prevKeys: Set<Int>,
    nextKeys: Set<Int>,
    onKeyRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keys = if (direction == PagingDirection.PREV) prevKeys else nextKeys
    EInkDialog(
        onDismiss = onDismiss,
        title = if (direction == PagingDirection.PREV) "上一页按键" else "下一页按键",
        confirmText = "保存",
        onConfirm = onConfirm,
        neutralText = "清除",
        onNeutral = onClear,
    ) {
        // 空/非空两态同容器同最小高（键块行高），首个键录入或清空时
        // 弹框高度不变；内容边距放大缓解拥挤
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.l)
                .heightIn(min = 32.dp),
        ) {
            if (keys.isEmpty()) {
                EInkText(
                    text = "未录入按键",
                    style = EInkTheme.typography.bodyMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterStart)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    keys.forEach { code ->
                        EInkButton(
                            text = pageKeyDisplayName(code),
                            onClick = { onKeyRemove(code) },
                            height = 32.dp,
                            modifier = Modifier.padding(end = EInkSpacing.s),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 预览内容（分组标题与说明行在页面层，与「自定义翻页按键」节同款
 * 风格；顶部加大间距与设置区隔离）：方向块左右分布、等宽；命中方向
 * 反色 [HINT_DURATION_MS]（同向刷新计时、异向切换重计时，时序在
 * Route 的序号驱动 LaunchedEffect）；不命中无变化。按钮 onClick 为
 * 空操作（纯展示，命中态即提示本体）。
 */
@Composable
private fun PagePreviewPanel(hintDirection: PagingDirection?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EInkSpacing.m,
                end = EInkSpacing.m,
                bottom = EInkSpacing.m,
            ),
    ) {
        EInkButton(
            text = "上一页",
            onClick = {},
            selected = hintDirection == PagingDirection.PREV,
            modifier = Modifier.weight(1f),
            height = 44.dp,
        )
        Spacer(modifier = Modifier.width(EInkSpacing.m))
        EInkButton(
            text = "下一页",
            onClick = {},
            selected = hintDirection == PagingDirection.NEXT,
            modifier = Modifier.weight(1f),
            height = 44.dp,
        )
    }
}
