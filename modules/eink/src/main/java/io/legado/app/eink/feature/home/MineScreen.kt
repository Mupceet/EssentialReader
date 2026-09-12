package io.legado.app.eink.feature.home

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.BuildConfig
import io.legado.app.eink.contract.AppUpdateInfo
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.pager.EInkListPagerState
import io.legado.app.eink.designsystem.pager.EInkPageSwipe
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 无状态「我的」页 — 首页第二个 Tab，设置入口。
 *
 * 设置项为「主信息 + 副信息」单行结构：主信息为设置名，副信息为当前
 * 值（如「字体大小 / 当前倍率 1.0x」），整行点击进入对应设置页；
 * 行为开关与宿主完整模式共享同一存储键：自动刷新 / 自动跳转最近阅读
 * 为启动期语义（写入后下次进入生效），音量键翻页、总是使用默认封面
 * 为实时语义（消费方每次读取快照）。
 * 「检查更新」行只在宿主注册了应用更新端口时渲染（companion 宿主可能
 * 没有 app 级更新机制，入口随之消失）；检查行为经 [UpdateCheckState]
 * 状态机承接：点击 → 端口检查 → 无更新 toast / 有更新弹 E-Ink 纯文本
 * 更新弹层。
 * 「完整模式」承载进入完整模式的入口（导入导出等管理功能在完整模式
 * 中完成）。
 *
 * 条目列表为 E-Ink 分页模式（对齐书架）：LazyColumn 禁用户滚动 +
 * [EInkPageSwipe] 手势整页翻页 + 底部操作栏箭头（经 [pager] 由
 * HomeRoute 分派），首屏实测一页项数后整页跳转，不做连续滚动。
 * 开关行的乐观状态 remember 在本函数体（LazyColumn 外）——条目翻页
 * 移出视口销毁 item 组合也不丢开关显示（写路径本身 fire-and-forget，
 * 若依赖 getter 重读会闪回旧值）。
 */
@Composable
internal fun MineScreen(
    pager: EInkListPagerState,
    onPageUp: () -> Unit = {},
    onPageDown: () -> Unit = {},
    onOpenFontScale: () -> Unit = {},
    onOpenFullMode: () -> Unit = {},
    onOpenThemeDebug: () -> Unit = {},
    onOpenComponentGallery: () -> Unit = {},
) {
    val globalSettings = EInkEngineRegistry.globalSettings
    val appUpdateEngine = EInkEngineRegistry.appUpdateEngine
    val context = LocalContext.current
    val fontScale = globalSettings.fontScaleSetting
    var autoRefresh by remember { mutableStateOf(globalSettings.autoRefreshBook) }
    var defaultToRead by remember { mutableStateOf(globalSettings.defaultToRead) }
    var volumeKeyPage by remember { mutableStateOf(globalSettings.volumeKeyPage) }
    // 写路径 fire-and-forget（getter 不保证立即可见新值），本地乐观状态
    var syncProgress by remember { mutableStateOf(globalSettings.syncReadingProgress) }
    val currentVersionName = remember(context) { context.readAppVersionName() }
    var updateCheck by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    val scope = rememberCoroutineScope()

    // 根 Box 承载更新弹层的全屏覆盖（弹层契约：须组合在全屏容器子级，
    // 不得进滚动/受限容器——若直接放在条目列表内只覆盖剩余高度）
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .EInkPageSwipe(
                    onPageUp = onPageUp,
                    onPageDown = onPageDown
                ),
            state = pager.listState,
            userScrollEnabled = false,
            overscrollEffect = null
        ) {
            item {
                MineEntry(
                    label = "字体大小",
                    sublabel = "当前倍率 ${(fontScale ?: FONT_SCALE_NEUTRAL) / 10f}x",
                    onClick = onOpenFontScale
                )
            }
            item { EInkHorizontalDivider() }
            item {
                MineToggleRow(
                    label = "自动刷新",
                    description = "打开软件时自动更新书籍",
                    checked = autoRefresh,
                    onToggle = {
                        val next = !autoRefresh
                        globalSettings.autoRefreshBook = next
                        autoRefresh = next
                    }
                )
            }
            item { EInkHorizontalDivider() }
            item {
                MineToggleRow(
                    label = "自动跳转最近阅读",
                    description = "关闭后默认打开书架",
                    checked = defaultToRead,
                    onToggle = {
                        val next = !defaultToRead
                        globalSettings.defaultToRead = next
                        defaultToRead = next
                    }
                )
            }
            item { EInkHorizontalDivider() }
            item {
                MineToggleRow(
                    label = "音量键翻页",
                    description = "阅读时音量键上下翻页",
                    checked = volumeKeyPage,
                    onToggle = {
                        val next = !volumeKeyPage
                        globalSettings.volumeKeyPage = next
                        volumeKeyPage = next
                    }
                )
            }
            item { EInkHorizontalDivider() }
            item {
                MineToggleRow(
                    label = "总是使用默认封面",
                    description = "总是显示默认封面（不显示网络封面）",
                    // 端口 getter 由宿主快照状态背书：此处读取订阅变化，切换后
                    // 开关行与书架/详情可见封面立即重组，无需本地乐观状态
                    checked = globalSettings.useDefaultCover,
                    onToggle = {
                        globalSettings.useDefaultCover = !globalSettings.useDefaultCover
                    }
                )
            }
            item { EInkHorizontalDivider() }
            item {
                MineToggleRow(
                    label = "同步阅读进度",
                    description = "进入/退出阅读时与云端同步",
                    checked = syncProgress,
                    onToggle = {
                        val next = !syncProgress
                        globalSettings.syncReadingProgress = next
                        syncProgress = next
                    }
                )
            }
            if (appUpdateEngine != null) {
                item { EInkHorizontalDivider() }
                item {
                    MineEntry(
                        label = "检查更新",
                        sublabel = when (updateCheck) {
                            UpdateCheckState.Checking -> "检查中…"
                            else -> "当前版本 $currentVersionName"
                        },
                        onClick = {
                            // 检查中防重复点击（行不置灰，副信息提示状态）
                            if (updateCheck is UpdateCheckState.Checking) return@MineEntry
                            updateCheck = UpdateCheckState.Checking
                            scope.launch {
                                try {
                                    val info = appUpdateEngine.checkUpdate()
                                    if (info == null) {
                                        updateCheck = UpdateCheckState.Idle
                                        Toast.makeText(
                                            context, "已是最新版本", Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        updateCheck = UpdateCheckState.Available(info)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    updateCheck = UpdateCheckState.Idle
                                    Toast.makeText(
                                        context, e.message ?: "检查更新失败", Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
            item { EInkHorizontalDivider() }
            item { MineEntry(label = "完整模式", onClick = onOpenFullMode) }
            if (BuildConfig.DEBUG) {
                // 调试入口仅 debug 变体展示（编译期常量，release 中整个分支被移除）
                item { EInkHorizontalDivider() }
                item { MineEntry(label = "排版样式调试", onClick = onOpenThemeDebug) }
                item { EInkHorizontalDivider() }
                item { MineEntry(label = "组件预览（Design System）", onClick = onOpenComponentGallery) }
            }
        }
        val availableUpdate = updateCheck as? UpdateCheckState.Available
        if (availableUpdate != null && appUpdateEngine != null) {
            EInkDialog(
                onDismiss = { updateCheck = UpdateCheckState.Idle },
                title = "发现新版本 ${availableUpdate.info.versionName}",
                confirmText = "立即更新",
                onConfirm = {
                    appUpdateEngine.startDownload(availableUpdate.info)
                    Toast.makeText(
                        context,
                        "已开始下载 ${availableUpdate.info.fileName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateCheck = UpdateCheckState.Idle
                },
                content = {
                    // 长说明限制高度内滚动，面板不随说明无限增高
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        EInkText(
                            text = releaseNoteToPlainText(availableUpdate.info.note),
                            style = EInkTheme.typography.bodyMedium,
                        )
                    }
                }
            )
        }
    }
}

/** 更新入口的状态机（「我的」页内聚，无跨屏消费方）。 */
private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val info: AppUpdateInfo) : UpdateCheckState
}

/** 宿主 app（嵌入本模块的应用）的 versionName——更新主体即宿主应用。 */
@Suppress("DEPRECATION")
private fun Context.readAppVersionName(): String =
    packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

/**
 * 设置项行：左侧主信息（设置名）+ 副信息（当前值，弱化色小字），右侧
 * ">" 跳转标识，整行点击进入设置页。
 */
@Composable
private fun MineEntry(
    label: String,
    onClick: () -> Unit,
    sublabel: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(text = label, style = EInkTheme.typography.titleMedium)
            if (sublabel != null) {
                EInkText(
                    text = sublabel,
                    style = EInkTheme.typography.bodyMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = EInkSpacing.xxs)
                )
            }
        }
        Spacer(modifier = Modifier.padding(start = EInkSpacing.s))
        EInkText(
            text = ">",
            style = EInkTheme.typography.titleLarge,
            color = EInkTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 行为开关行：与 [MineEntry] 同款「主信息 + 副信息」结构（纯展示），
 * 右侧开/关块即开关本体（EInkButton，开启实心，样式对齐阅读页
 * ToggleRow）。
 */
@Composable
private fun MineToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit
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
                style = EInkTheme.typography.titleMedium
            )
            EInkText(
                text = description,
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = EInkSpacing.xxs)
            )
        }
        Spacer(modifier = Modifier.padding(start = EInkSpacing.s))
        // 开/关块即开关本体（EInkButton，开启实心），行内文字纯展示
        EInkButton(
            text = if (checked) "开" else "关",
            onClick = onToggle,
            modifier = Modifier.width(64.dp),
            height = 44.dp,
            selected = checked,
            role = Role.Switch
        )
    }
}
