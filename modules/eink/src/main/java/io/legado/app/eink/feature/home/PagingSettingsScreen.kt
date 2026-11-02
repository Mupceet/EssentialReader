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
 * 捕获式录入：点方向行进入录入态，按物理键即录为该方向键（一键一方
 * 向，录入自动迁出另一方向；非正键码静默忽略），返回键退出录入且不
 * 可录为翻页键（导航必需）；滚轮只预览不可录入。写入经 setPageKeys
 * 两方向一次原子提交，本地乐观状态保显示（fire-and-forget 同「我的」
 * 开关）。录入态单处理器内分支（录入/预览），避免双处理器栈序竞态。
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
    // 录入方向（null = 非录入态）；提示方向 + 序号（序号自增驱动计时重启）
    var captureDirection by remember { mutableStateOf<PagingDirection?>(null) }
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

    // 预览 + 录入（单处理器分支）：录入态全量捕键；常态复用生产判定
    // 只提示不翻页（rememberUpdatedState 保证注册 lambda 读到最新态）
    val keyHub = EInkEngineRegistry.keyEventHub
    val currentCapture by rememberUpdatedState(captureDirection)
    val currentPrevKeys by rememberUpdatedState(prevKeys)
    val currentNextKeys by rememberUpdatedState(nextKeys)
    DisposableEffect(keyHub) {
        val unregister = keyHub.register { event ->
            val capturing = currentCapture
            if (capturing != null) {
                handleCaptureKey(
                    action = event.action,
                    keyCode = event.keyCode,
                    repeatCount = event.repeatCount,
                    direction = capturing,
                    prevKeyCodes = currentPrevKeys,
                    nextKeyCodes = currentNextKeys,
                    onRecorded = { prev, next ->
                        commitKeys(prev, next)
                        flashHint(capturing)
                    },
                    onExitCapture = { captureDirection = null },
                )
            } else {
                hubPagingKeyEvent(
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
        captureDirection = captureDirection,
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
        onDirectionRowClick = { direction ->
            captureDirection = if (captureDirection == direction) null else direction
        },
        onKeyRemove = { direction, code ->
            if (direction == PagingDirection.PREV) {
                commitKeys(prevKeys - code, nextKeys)
            } else {
                commitKeys(prevKeys, nextKeys - code)
            }
        },
        onBack = onBack,
    )
}

/** 预览命中提示的持续时长。 */
private const val HINT_DURATION_MS = 1_500L

/**
 * 无状态翻页设置页外壳 — 居中标题 + 两开关行 + 自定义翻页按键分组
 * （两方向行 + 键块）+ 预览条 + 帮助文案 + 底部返回操作栏（备份与
 * 恢复页同款）。内容整体可纵向滚动（常态一屏放得下，滚动仅为小屏
 * 溢出兜底；滚轮开关开启时滚轮被预览消费，页面不依赖滚轮滚动）。
 */
@Composable
private fun PagingSettingsScreen(
    volumeKeyPage: Boolean,
    mouseWheelPage: Boolean,
    prevKeys: Set<Int>,
    nextKeys: Set<Int>,
    captureDirection: PagingDirection?,
    hintDirection: PagingDirection?,
    onVolumeKeyPageToggle: () -> Unit,
    onMouseWheelPageToggle: () -> Unit,
    onDirectionRowClick: (PagingDirection) -> Unit,
    onKeyRemove: (PagingDirection, Int) -> Unit,
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
                    description = "关闭时恢复系统音量调节",
                    checked = volumeKeyPage,
                    onToggle = onVolumeKeyPageToggle,
                )
                EInkHorizontalDivider()
                PagingToggleRow(
                    label = "鼠标滚轮翻页",
                    description = "滚轮在阅读页与列表翻页",
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
                        top = EInkSpacing.l,
                        bottom = EInkSpacing.xxs,
                    ),
                )
                PageKeyRow(
                    direction = PagingDirection.PREV,
                    label = "上一页",
                    keyCodes = prevKeys,
                    capturing = captureDirection == PagingDirection.PREV,
                    onRowClick = { onDirectionRowClick(PagingDirection.PREV) },
                    onKeyRemove = { onKeyRemove(PagingDirection.PREV, it) },
                )
                EInkHorizontalDivider()
                PageKeyRow(
                    direction = PagingDirection.NEXT,
                    label = "下一页",
                    keyCodes = nextKeys,
                    capturing = captureDirection == PagingDirection.NEXT,
                    onRowClick = { onDirectionRowClick(PagingDirection.NEXT) },
                    onKeyRemove = { onKeyRemove(PagingDirection.NEXT, it) },
                )
                EInkHorizontalDivider()

                PagePreviewStrip(hintDirection = hintDirection)
                EInkText(
                    text = "点方向行后按物理键录入；返回键用于退出录入，不可录为翻页键。设置对阅读页与各列表全局生效，并与完整模式共享。",
                    style = EInkTheme.typography.bodyMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = EInkSpacing.m,
                        end = EInkSpacing.m,
                        bottom = EInkSpacing.m,
                    ),
                )
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
 * 行为开关行（「我的」页开关行同款结构）：左侧「主信息 + 副信息」纯
 * 展示，右侧开/关块即开关本体（EInkButton，开启实心、Role.Switch）。
 */
@Composable
private fun PagingToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(text = label, style = EInkTheme.typography.titleMedium)
            EInkText(
                text = description,
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = EInkSpacing.xxs),
            )
        }
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
 * 方向键行：标题 + 录入状态文案，下方键块横排（点键块删除该键）。
 * 整行点击进入/退出录入态（按压瞬时反色 §35）；键块数量少，横向
 * 滚动兜底即可。
 */
@Composable
private fun PageKeyRow(
    direction: PagingDirection,
    label: String,
    keyCodes: Set<Int>,
    capturing: Boolean,
    onRowClick: () -> Unit,
    onKeyRemove: (Int) -> Unit,
) {
    EInkPressBlock(
        onClick = onRowClick,
        modifier = Modifier.fillMaxWidth(),
    ) { colors, _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EInkText(
                    text = label,
                    style = EInkTheme.typography.titleMedium,
                    color = colors.contentColor,
                )
                Spacer(modifier = Modifier.weight(1f))
                EInkText(
                    text = if (capturing) "按物理键录入，再点结束" else "点此录入按键",
                    style = EInkTheme.typography.bodyMedium,
                    color = if (capturing) colors.contentColor else colors.secondaryContentColor,
                )
            }
            if (keyCodes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = EInkSpacing.xs)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    keyCodes.forEach { code ->
                        EInkButton(
                            text = pageKeyDisplayName(code),
                            onClick = { onKeyRemove(code) },
                            height = 32.dp,
                            modifier = Modifier.padding(end = EInkSpacing.s),
                        )
                    }
                }
            } else if (!capturing) {
                EInkText(
                    text = "未设置",
                    style = EInkTheme.typography.bodyMedium,
                    color = colors.secondaryContentColor,
                    modifier = Modifier.padding(top = EInkSpacing.xs),
                )
            }
        }
    }
}

/**
 * 预览条：双格等宽方向块，命中方向反色 [HINT_DURATION_MS]（同向刷新
 * 计时、异向切换重计时，时序在 Route 的序号驱动 LaunchedEffect）；
 * 不命中无变化。按钮 onClick 为空操作（纯展示，命中态即提示本体）。
 */
@Composable
private fun PagePreviewStrip(hintDirection: PagingDirection?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EInkSpacing.m,
                end = EInkSpacing.m,
                top = EInkSpacing.l,
            ),
    ) {
        EInkText(text = "预览", style = EInkTheme.typography.titleMedium)
        EInkText(
            text = "按下翻页键或滚动滚轮，命中方向短暂反色",
            style = EInkTheme.typography.bodyMedium,
            color = EInkTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                top = EInkSpacing.xxs,
                bottom = EInkSpacing.s,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
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
}
