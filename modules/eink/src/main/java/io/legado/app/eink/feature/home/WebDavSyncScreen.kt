package io.legado.app.eink.feature.home

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.R
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.WebDavSyncConfig
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 备份与恢复页 Route（入口：「我的 → 备份与恢复」，引擎能力位
 * [io.legado.app.eink.contract.BackupSyncEngine.supportsWebDavManagement]
 * 开启才渲染入口，正常导航必达可用态）。
 *
 * 一次性消息 → Toast（备份/恢复/保存失败）；测试服务的成败细节由宿主
 * 链路 toast（引擎契约口径），本页只做忙态（见 [WebDavSyncViewModel]）。
 *
 * 同步阅读进度开关自「我的」迁入本页（进度同步依赖 WebDAV 配置才
 * 生效）：经 [GlobalSettings] 端口读写宿主同键，fire-and-forget 写路径
 * 配本地乐观状态保显示（MineScreen 原同款）。
 */
@Composable
fun WebDavSyncRoute(onBack: () -> Unit) {
    val engine = EInkEngineRegistry.backupSyncEngine
    val globalSettings = EInkEngineRegistry.globalSettings
    val vm: WebDavSyncViewModel = viewModel()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 同步阅读进度：写路径 fire-and-forget（getter 不保证立即可见新值），
    // 本地乐观状态
    var syncProgress by remember { mutableStateOf(globalSettings.syncReadingProgress) }

    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    WebDavSyncScreen(
        available = engine?.supportsWebDavManagement == true,
        state = uiState,
        syncProgress = syncProgress,
        onSyncProgressToggle = {
            val next = !syncProgress
            globalSettings.syncReadingProgress = next
            syncProgress = next
        },
        onIntent = vm::onIntent,
        onBack = onBack,
    )
}

/**
 * 无状态备份与恢复页外壳 — 居中标题 + WebDAV 五字段配置行（点击弹
 * 编辑框）+ 同步阅读进度开关 + 测试/备份/恢复三动作 + 底部返回操作栏。
 *
 * 配置与完整模式**同一存储**：本页保存即共享，完整模式侧改动经引擎
 * 配置流回流、行值随之刷新。标题不体现 WebDAV，前三行标签带
 * 「WebDAV」前缀锚定语境（服务器地址/账号/密码），文件夹与设备名
 * 承接同组。行展示值为空地址/文件夹回落产品默认（宿主运行时同款
 * 回退）、密码只示有无（明文只在编辑弹框回填，同宿主完整模式
 * WebDavAuth 弹框形态）。
 *
 * 同步阅读进度开关（自「我的」迁入）以 [state.cloudReady] 门控——
 * 未配置账号密码时禁用（进度同步依赖 WebDAV 配置才生效），开关状态
 * 忠实反映宿主同键真值（默认开）。
 *
 * 三个云端动作以 [state.cloudReady]（账号密码齐备）与忙态门控；忙态
 * **不弹模态框**，直接落在对应动作按钮的文字上（测试中/备份中/
 * 获取中/恢复中——「xx中」即表进行过程，不加省略号），期间全部按钮
 * 禁用防并发派发，但用户仍可离开
 * 本页（操作协程随条目 VM 清理而取消）。恢复交互（产品确认）：恢复
 * 最新一份——确认层展示设备/日期/文件名，恢复只写 DB、书架流响应式
 * 刷新，不动导航与当前阅读会话。
 *
 * 表单整体可纵向滚动（常态一屏放得下，滚动仅为小屏溢出兜底；无分页
 * 列表故不接翻页手势/音量键）。
 */
@Composable
private fun WebDavSyncScreen(
    available: Boolean,
    state: WebDavSyncViewModel.UiState,
    syncProgress: Boolean,
    onSyncProgressToggle: () -> Unit,
    onIntent: (WebDavSyncIntent) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<WebDavField?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部：居中标题（返回在底部操作栏，字体设置页同款）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                EInkText(text = "备份与恢复", style = EInkTheme.typography.titleLarge)
            }
            EInkHorizontalDivider()

            if (!available) {
                // 兜底：入口本应由能力位门控，直达（如调试）时给明确态
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    EInkText(
                        text = "此设备不支持同步管理",
                        style = EInkTheme.typography.bodyMedium,
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    WebDavField.entries.forEach { field ->
                        WebDavConfigRow(
                            label = field.label,
                            value = state.config.displayValue(field),
                            onClick = { editing = field },
                        )
                        EInkHorizontalDivider()
                    }

                    // 同步阅读进度（自「我的」迁入）：依赖 WebDAV 账号密码
                    // 配置才生效，未配置时禁用（开关忠实反映宿主同键真值）
                    WebDavToggleRow(
                        label = "同步阅读进度",
                        description = "进入/退出阅读时与云端同步",
                        checked = syncProgress,
                        enabled = state.cloudReady,
                        onToggle = onSyncProgressToggle,
                    )
                    EInkHorizontalDivider()

                    if (!state.cloudReady) {
                        EInkText(
                            text = "配置账号与密码后可测试、备份与恢复",
                            style = EInkTheme.typography.bodyMedium,
                            color = EInkTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = EInkSpacing.m,
                                vertical = EInkSpacing.s,
                            ),
                        )
                    }

                    // 三动作等分一行：胶囊形（两端半圆角）与上方配置条目的
                    // medium 圆角按钮区隔风格；忙态不弹模态框，直接落在对应
                    // 动作按钮的文字上（进行中动作改文案，全部按钮同步禁用
                    // 防并发派发）；顶部间距加大（l）与表单区分层
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = EInkSpacing.m,
                                end = EInkSpacing.m,
                                top = EInkSpacing.l,
                                bottom = EInkSpacing.m,
                            ),
                    ) {
                        val enabled = state.busy == null && state.cloudReady
                        EInkButton(
                            text = when (state.busy) {
                                WebDavSyncViewModel.Busy.Testing -> "测试中"
                                else -> "测试服务"
                            },
                            onClick = { onIntent(WebDavSyncIntent.TestService) },
                            enabled = enabled,
                            shape = EInkShapes.capsule,
                            modifier = Modifier.weight(1f),
                            height = 44.dp,
                        )
                        Spacer(modifier = Modifier.width(EInkSpacing.m))
                        EInkButton(
                            text = when (state.busy) {
                                WebDavSyncViewModel.Busy.BackingUp -> "备份中"
                                else -> "备份"
                            },
                            onClick = { onIntent(WebDavSyncIntent.BackupNow) },
                            enabled = enabled,
                            shape = EInkShapes.capsule,
                            modifier = Modifier.weight(1f),
                            height = 44.dp,
                        )
                        Spacer(modifier = Modifier.width(EInkSpacing.m))
                        EInkButton(
                            text = when (state.busy) {
                                WebDavSyncViewModel.Busy.LoadingLatest -> "获取中"
                                WebDavSyncViewModel.Busy.Restoring -> "恢复中"
                                else -> "恢复"
                            },
                            onClick = { onIntent(WebDavSyncIntent.RequestRestore) },
                            enabled = enabled,
                            shape = EInkShapes.capsule,
                            modifier = Modifier.weight(1f),
                            height = 44.dp,
                        )
                    }
                }
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

        // ---- 弹框组（Screen 根 Box 直下，覆盖层语义）----

        editing?.let { field ->
            WebDavFieldEditDialog(
                title = field.label,
                initialValue = state.config.editValue(field),
                isPassword = field == WebDavField.PASSWORD,
                onConfirm = { value ->
                    editing = null
                    onIntent(
                        WebDavSyncIntent.SaveConfig(state.config.withField(field, value))
                    )
                },
                onDismiss = { editing = null },
            )
        }

        // 恢复确认层（恢复最新一份；确认后收层，恢复进行中忙态落在
        // 「恢复」按钮文字上，不另弹框）
        state.restoreConfirm?.let { info ->
            EInkDialog(
                onDismiss = { onIntent(WebDavSyncIntent.DismissRestore) },
                title = "恢复云端备份",
                confirmText = "恢复",
                onConfirm = { onIntent(WebDavSyncIntent.ConfirmRestore) },
            ) {
                EInkText(
                    text = buildString {
                        append("设备：${info.deviceName.ifBlank { "未命名" }}  日期：${info.dateText}\n")
                        append("文件：${info.fileName}\n\n")
                        append("恢复将把云端备份合并到本机。")
                    },
                    style = EInkTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * 可编辑配置字段（枚举序即页面行序）。标题「备份与恢复」不体现
 * WebDAV，前三行标签带前缀锚定语境（同组承接的文件夹/设备名不再
 * 重复前缀，避免五行全前缀的视觉噪音）。
 */
private enum class WebDavField(val label: String) {
    SERVER_URL("WebDAV 服务器地址"),
    ACCOUNT("WebDAV 账号"),
    PASSWORD("WebDAV 密码"),
    DIR("同步文件夹"),
    DEVICE_NAME("设备名称"),
}

/** 行展示值：空地址/文件夹回落产品默认（宿主运行时同款），密码只示有无。 */
private fun WebDavSyncConfig.displayValue(field: WebDavField): String = when (field) {
    WebDavField.SERVER_URL -> serverUrl.ifBlank { WebDavSyncConfig.DEFAULT_SERVER_URL }
    WebDavField.ACCOUNT -> account.ifBlank { "未设置" }
    WebDavField.PASSWORD -> if (password.isEmpty()) "未设置" else "已设置"
    WebDavField.DIR -> dir.ifBlank { WebDavSyncConfig.DEFAULT_DIR }
    WebDavField.DEVICE_NAME -> deviceName.ifBlank { "未命名" }
}

/** 编辑弹框初值：空地址/文件夹预填默认（保存后显式落默认值），密码明文回填。 */
private fun WebDavSyncConfig.editValue(field: WebDavField): String = when (field) {
    WebDavField.SERVER_URL -> serverUrl.ifBlank { WebDavSyncConfig.DEFAULT_SERVER_URL }
    WebDavField.ACCOUNT -> account
    WebDavField.PASSWORD -> password
    WebDavField.DIR -> dir.ifBlank { WebDavSyncConfig.DEFAULT_DIR }
    WebDavField.DEVICE_NAME -> deviceName
}

/** 单字段替换（其余字段原样带回，VM 保存前整体归一化）。 */
private fun WebDavSyncConfig.withField(field: WebDavField, value: String): WebDavSyncConfig =
    when (field) {
        WebDavField.SERVER_URL -> copy(serverUrl = value)
        WebDavField.ACCOUNT -> copy(account = value)
        WebDavField.PASSWORD -> copy(password = value)
        WebDavField.DIR -> copy(dir = value)
        WebDavField.DEVICE_NAME -> copy(deviceName = value)
    }

/**
 * 配置行：与「我的」页设置行同款「主信息 + 副信息」结构（副信息为当前
 * 值），尾部右箭头为可编辑标识，整行点击弹编辑框；按压瞬时反色（§35）。
 */
@Composable
private fun WebDavConfigRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(
                text = label,
                style = EInkTheme.typography.titleMedium,
                color = colors.contentColor,
            )
            EInkText(
                text = value,
                style = EInkTheme.typography.bodyMedium,
                color = colors.secondaryContentColor,
                modifier = Modifier.padding(top = EInkSpacing.xxs),
            )
        }
        Spacer(modifier = Modifier.padding(start = EInkSpacing.s))
        Image(
            painter = painterResource(R.drawable.eink_ic_keyboard_arrow_right),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(colors.secondaryContentColor),
        )
    }
}

/**
 * 行为开关行（「我的」页开关行同款结构）：左侧「主信息 + 副信息」纯
 * 展示，右侧开/关块即开关本体（EInkButton，开启实心、Role.Switch）。
 */
@Composable
private fun WebDavToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(
                text = label,
                style = EInkTheme.typography.titleMedium,
            )
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
            enabled = enabled,
            modifier = Modifier.width(64.dp),
            selected = checked,
            role = Role.Switch,
        )
    }
}

/**
 * 单字段编辑弹框（分组名编辑弹框同范式）：TextFieldValue 初始选区置
 * 行尾 + 打开即聚焦；空值可确认（清空账号/密码 = 回到未配置态，地址/
 * 文件夹空值由保存归一化回落默认）。密码字段掩码显示 + 行尾「显示/
 * 隐藏」切换（宿主完整模式 WebDavAuth 弹框同形态）。
 */
@Composable
private fun WebDavFieldEditDialog(
    title: String,
    initialValue: String,
    isPassword: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var textValue by remember(initialValue) {
        mutableStateOf(
            TextFieldValue(
                text = initialValue,
                selection = TextRange(initialValue.length),
            )
        )
    }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(initialValue) { focusRequester.requestFocus() }
    EInkDialog(
        onDismiss = onDismiss,
        title = title,
        onConfirm = { onConfirm(textValue.text) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = textValue,
                onValueChange = { textValue = it },
                singleLine = true,
                cursorBrush = SolidColor(EInkTheme.colorScheme.onSurface),
                textStyle = EInkTheme.typography.bodyLarge.copy(
                    color = EInkTheme.colorScheme.onSurface
                ),
                visualTransformation = if (isPassword && !passwordVisible) {
                    PasswordVisualTransformation(mask = '•')
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = if (isPassword) {
                    KeyboardOptions(keyboardType = KeyboardType.Password)
                } else {
                    KeyboardOptions.Default
                },
                // 文本域自带焦点节点：仅挂 focusRequester（勿加 focusable，
                // 会另立焦点节点抢走 requestFocus，文本域仍无光标）
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            if (isPassword) {
                EInkButton(
                    text = if (passwordVisible) "隐藏" else "显示",
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier
                        .padding(start = EInkSpacing.s)
                        .width(56.dp),
                    height = 32.dp,
                )
            }
        }
    }
}
