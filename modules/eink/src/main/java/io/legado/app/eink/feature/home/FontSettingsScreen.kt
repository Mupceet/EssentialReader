package io.legado.app.eink.feature.home

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.app.EInkPagerInput
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.GlobalSettings
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkSteppedSlider
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.pager.awaitPositionReady
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.reader.encodeRecentFontPaths
import io.legado.app.eink.feature.reader.sortFontOptions
import io.legado.app.eink.feature.reader.withUnifiedFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 字体缩放原始设置的最小/最大值（÷10 为倍率：0.8x ~ 1.6x，宿主解析同区间）。 */
internal const val FONT_SCALE_MIN = 8

internal const val FONT_SCALE_MAX = 16

/** 未设置时的锚定档位（1.0x）；宿主对 null 的回落语义见 GlobalSettings 契约。 */
internal const val FONT_SCALE_NEUTRAL = 10

/**
 * 字体设置页（入口：「我的 → 字体设置」与阅读器字体配置弹层「更多字体…」）。
 *
 * 原「字体大小」设置页已并入本页：顶部为示例文字 + 字号拖动条，示例文字
 * 同时承担**字号预览**（随拖动等比缩放，抬手/步进写入全局设置并 recreate
 * 入口 Activity 应用——fontScale 是 attach 期档，导航栈与各条目
 * ViewModelStore 跨 recreate 存活，仍停留在本页）与**字体预览**（跟随
 * 当前选中字体，点选下方列表即时切换）；底部为字体列表（复用全仓全屏
 * 列表分页定式 [rememberEInkListPagerState]：LazyColumn 禁滚 + 首布局
 * 实测页容量 + 整页跳转；上下滑动手势识别为翻页，同 ▲▼）——**翻页只
 * 作用于列表区**，顶部预览区不参与。顶部预览区（示例文字 + 字号拖动条）
 * 仅「我的」入口显示：自阅读页进入时整体隐藏（字号缩放与阅读无关，
 * 阅读正文字号在排版面板；字体预览由列表行承担），界面回到标题 +
 * 列表的纯选字体形态。
 *
 * 两个入口的字体写径与生效流程不同，按 [fromReader] 路由分流（对齐完整
 * 模式的两条字体流程）：
 * - 自阅读页进入（fromReader = true）：**阅读字体**流程——统一字体写径
 *   （正文直选、标题/页眉归位跟随正文，[withUnifiedFont]，与阅读器弹层
 *   setReaderFont 同写径），选中文件字体同时记录最近选择（弹框反显数据
 *   源）；点选应用后即返回阅读页（对齐原浮层点选即关的路径）。生效：
 *   宿主写阅读配置并触发重排。列表反显当前选中（阅读样式快照）。
 * - 「我的」进入（fromReader = false）：**应用界面字体**流程（完整模式
 *   「外观 → 字体」同流程同键）——选中经端口复制入宿主私有目录写
 *   appFontPath，fire-and-forget；生效：eink 界面字体与完整模式 UI 经
 *   设置流订阅实时重渲染（示例文字随之换字体，即点即所见），停留本页。
 *   列表头部插入系统预设项（[GlobalSettings.supportedAppFontPresets]，
 *   宿主声明才渲染：当前宿主仅「系统默认」——选中即清除自定义回落默认，
 *   承接原清除按钮职责）；自定义字体反显/定位经
 *   [GlobalSettings.currentAppFontListPath]（源 path 匹配，完整模式侧
 *   改动后失配为 null——此时 [GlobalSettings.hasCustomAppFont] 仍为
 *   true，不误亮系统默认项）。
 *
 * 共有行为：
 * - 字体行三行文字：字体名（去扩展名，与阅读器字体配置弹层同口径）+
 *   名称下方两行示例（中文 [FontSampleTextCn] / 英文 [FontSampleTextEn]，
 *   分看中外文字形）；各行均以该字体文件渲染（path 经宿主端口
 *   loadFontTypeface 逐项异步加载 Typeface，加载失败回落平台默认字体），
 *   所见即选择后的效果（系统预设项以平台默认字体渲染）；行内左右留
 *   16dp 边距（[EInkSpacing.m]，与目录行、顶栏标题同列）；列表按字体名
 *   升序（sortFontOptions 口径，预设项固定在表头）；选中行用左侧实心
 *   竖条 + 名称加粗（大面积持久反色残影重），按压仍瞬时反色（§35）；
 * - 打开时定位到当前选中字体所在页（jumpToItemAligned；「我的」模式等
 *   字体枚举完成后定位——枚举中列表先以 surface 色遮盖防闪现），定位
 *   完成前同遮盖（同目录页 positioned 先例）；「选择字体文件夹」图标
 *   （空心描边版）随时可换文件夹（SAF），列表即时刷新（文件夹与阅读
 *   字体/完整模式外观字体共用同一偏好）；
 * - 系统栏避让：本页零 inset 代码，由 EInkApp 根层统一避让（目录页同款，
 *   状态栏正常显示）；自阅读页进入时阅读层的沉浸收放随界面切换完成；
 * - 空态：无字体且无预设项时居中提示，翻页箭头置灰。
 */
@Composable
fun FontSettingsRoute(
    fromReader: Boolean,
    onBack: () -> Unit,
) {
    val engine = EInkEngineRegistry.readerEngine
    val globalSettings = EInkEngineRegistry.globalSettings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 字体文件列表（宿主字体文件夹枚举，按字体名升序）：进入时拉取，
    // SAF 换文件夹后重拉；fontsLoaded = 枚举完成（空文件夹与加载中区分
    // 定位/空态判定）
    var fontOptions by remember { mutableStateOf<List<ReaderFontOption>>(emptyList()) }
    var fontsLoaded by remember { mutableStateOf(false) }
    // 阅读模式反显：当前选中的文件字体 path（引擎样式快照初值，选择后
    // 按引擎回读乐观更新）
    var selectedReaderPath by remember {
        mutableStateOf((engine.currentStyle().bodyFont as? ReaderFontSelection.File)?.path)
    }
    // 「我的」模式反显：自定义字体对应的列表项源 path + 系统预设选中档
    // （null = 自定义字体在场，预设项不亮）
    var selectedListPath by remember { mutableStateOf(globalSettings.currentAppFontListPath) }
    var selectedPreset by remember {
        mutableStateOf(if (globalSettings.hasCustomAppFont) null else 0)
    }
    // 系统预设项（「我的」模式表头）：宿主声明档位，未知档位忽略
    val presets = if (fromReader) {
        emptyList()
    } else {
        globalSettings.supportedAppFontPresets.filter { it in APP_FONT_PRESET_RANGE }
    }

    // ---- 字号缩放（并入自原「字体大小」设置页）----

    val activity = context as? Activity
    // 拖动只动预览值；写入经 [onScaleChangeFinished]/[onScaleStep]（写后
    // recreate 应用）。remember 键含当前生效值：recreate 后回填新档位
    var pendingScale by remember(globalSettings.fontScaleSetting) {
        mutableStateOf(globalSettings.fontScaleSetting ?: FONT_SCALE_NEUTRAL)
    }
    val onScaleChange: (Int) -> Unit = { pendingScale = it }
    val onScaleChangeFinished: () -> Unit = {
        if (pendingScale != globalSettings.fontScaleSetting) {
            globalSettings.fontScaleSetting = pendingScale
            activity?.recreate()
        }
    }
    val onScaleStep: (Int) -> Unit = { target ->
        if (applyScaleStep(globalSettings, globalSettings.fontScaleSetting, target) {
                pendingScale = it
            }
        ) {
            activity?.recreate()
        }
    }

    // 顶部示例文字的预览字体 = 当前选中字体（阅读模式=引擎样式、我的
    // 模式=反显源 path），点选/预设切换即时跟随；null = 平台默认
    val previewFontFamily = rememberFontFamily(
        if (fromReader) selectedReaderPath else selectedListPath
    )

    LaunchedEffect(Unit) {
        fontOptions = withContext(Dispatchers.IO) {
            sortFontOptions(engine.availableFonts())
        }
        fontsLoaded = true
    }

    // 字体文件夹选择（SAF）：持久化读权限后落库并刷新列表（阅读器弹层同口径）
    val fontFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch(Dispatchers.IO) {
                engine.setFontFolder(it.toString())
                fontOptions = withContext(Dispatchers.IO) {
                    sortFontOptions(engine.availableFonts())
                }
            }
        }
    }

    // 路由分流见本函数 KDoc：阅读字体=统一写径+记录最近+应用后返回；
    // 应用字体=端口 fire-and-forget+停留本页（界面字体实时重渲染即反馈）
    val onSelect: (ReaderFontOption) -> Unit = { option ->
        if (fromReader) {
            globalSettings.recentFontPathsEncoding =
                encodeRecentFontPaths(listOf(option.path))
            engine.applyStyle(
                engine.currentStyle().withUnifiedFont(ReaderFontSelection.File(option.path))
            )
            selectedReaderPath =
                (engine.currentStyle().bodyFont as? ReaderFontSelection.File)?.path
            onBack()
        } else {
            globalSettings.setAppFont(option.path)
            selectedListPath = option.path
            selectedPreset = null
        }
    }

    // 系统预设项选中（0 = 系统默认 = 清除自定义）：fire-and-forget，本页
    // 乐观更新选中态；界面字体随设置流回默认
    val onSelectPreset: (Int) -> Unit = { preset ->
        globalSettings.setAppFontPreset(preset)
        selectedPreset = preset
        selectedListPath = null
    }

    FontSettingsScreen(
        fontOptions = fontOptions,
        fontsLoaded = fontsLoaded,
        presets = presets,
        showPreviewSection = !fromReader,
        selectedFontPath = if (fromReader) selectedReaderPath else selectedListPath,
        selectedPreset = if (fromReader) null else selectedPreset,
        pendingScale = pendingScale,
        previewFontFamily = previewFontFamily,
        onScaleChange = onScaleChange,
        onScaleChangeFinished = onScaleChangeFinished,
        onScaleStep = onScaleStep,
        onSelect = onSelect,
        onSelectPreset = onSelectPreset,
        onPickFolder = { fontFolderLauncher.launch(null) },
        onBack = onBack,
    )
}

/**
 * 无状态字体设置页外壳 — 居中标题栏 + 示例文字与字号拖动条 + 字体列表
 * （系统预设表头 + 字体文件，分页）+ 底部操作栏。
 *
 * **翻页只作用于列表区**：上下滑动手势挂在列表上，顶部预览区/拖动条
 * 不参与（顶部预览区仅「我的」入口显示，见 [showPreviewSection]）。分页与初始定位为界面本地状态（数据由 Route 注入）：等字体
 * 枚举完成（[fontsLoaded]）与首布局就绪后，跳到选中项所在页——预设项
 * 在表头（下标 < [presets].size），字体文件紧随其后；未选中/幽灵选中
 * 归 0（「我的」模式即系统默认项、阅读模式即首行）。枚举中列表以
 * background 色遮盖（防闪现第一页再跳）。
 */
@Composable
private fun FontSettingsScreen(
    fontOptions: List<ReaderFontOption>,
    fontsLoaded: Boolean,
    presets: List<Int>,
    showPreviewSection: Boolean,
    selectedFontPath: String?,
    selectedPreset: Int?,
    pendingScale: Int,
    previewFontFamily: FontFamily?,
    onScaleChange: (Int) -> Unit,
    onScaleChangeFinished: () -> Unit,
    onScaleStep: (Int) -> Unit,
    onSelect: (ReaderFontOption) -> Unit,
    onSelectPreset: (Int) -> Unit,
    onPickFolder: () -> Unit,
    onBack: () -> Unit,
) {
    val pager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val refresh = LocalEInkRefreshController.current
    val totalRows = presets.size + fontOptions.size

    // 初始定位：等字体枚举完成 + 首布局后，跳到选中项所在页——等测量落定
    // 或单页可容纳（单页无需跳转，jumpToItemAligned no-op；只等测量落定
    // 会把恰好一页的列表卡在遮盖常驻）。未选中/幽灵选中归 0。定位只执行
    // 一次；数据原地变化（文件夹重扫）后拉回页首（防御）。选择不进本
    // 效应键，点选只更新行内选中态
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(fontsLoaded, fontOptions) {
        if (!fontsLoaded) return@LaunchedEffect
        if (fontOptions.isEmpty() && presets.isEmpty()) {
            positioned = true
            return@LaunchedEffect
        }
        if (!positioned) {
            pager.awaitPositionReady()
            pager.jumpToItemAligned(positionTargetIndex(presets, fontOptions, selectedFontPath))
            positioned = true
        } else {
            pager.realignToPageStart(totalRows)
        }
    }

    // 翻页动作 remember 稳定实例 + 翻页刷新意图（同目录页口径）
    val pageUp: () -> Unit = remember(pager, refresh, scope) {
        {
            scope.launch { pager.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageDown: () -> Unit = remember(pager, totalRows, refresh, scope) {
        {
            scope.launch { pager.pageDown(totalRows) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background),
    ) {
        // 顶部：居中标题（返回在底部操作条）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            EInkText(text = "字体设置", style = EInkTheme.typography.titleLarge)
        }
        EInkHorizontalDivider()

        // 顶部预览区（仅「我的」入口显示，showPreviewSection）：示例文字
        // 双预览——字号随拖动档位等比缩放（fontSize 与 lineHeight 同乘，
        // min 14sp 下限与全局一致，recreate 后即所见），字体跟随当前选中
        // （点选列表即时切换）。自阅读页进入时整体隐藏（字号缩放与阅读
        // 无关、阅读正文字号在排版面板，字体预览由列表行承担），列表占满
        // 剩余空间
        if (showPreviewSection) {
            val previewScale = pendingScale / 10f
            val heading = EInkTheme.typography.titleMedium
            val body = EInkTheme.typography.bodyLarge
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
            ) {
                EInkText(
                    text = "排版是一本书的呼吸",
                    fontFamily = previewFontFamily,
                    style = heading.copy(
                        fontSize = heading.fontSize * previewScale,
                        lineHeight = heading.lineHeight * previewScale,
                    ),
                )
                EInkText(
                    text = "合适的字号与字体，让目光在字里行间从容行走，不必停留，" +
                            "也不必追赶。在下方进行调整直到这一段文字读起来最舒服为止。",
                    fontFamily = previewFontFamily,
                    style = body.copy(
                        fontSize = body.fontSize * previewScale,
                        lineHeight = body.lineHeight * previewScale,
                    ),
                    modifier = Modifier.padding(top = EInkSpacing.s),
                )
            }

            // 字号操作滑条行：−/＋ 单档步进（点击即应用）+ 滑条抬手生效
            // （拖动仅预览档位）；1.0 档位上方「默认」静态标识（不可点）。
            // 行高用 heightIn：滑条带标识时自身需要 48dp + 标识行高度
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
            ) {
                StepGlyphButton("−", onClickLabel = "减小") { onScaleStep(pendingScale - 1) }
                EInkSteppedSlider(
                    value = pendingScale,
                    onValueChange = onScaleChange,
                    onValueChangeFinished = onScaleChangeFinished,
                    valueRange = FONT_SCALE_MIN..FONT_SCALE_MAX,
                    modifier = Modifier.weight(1f),
                    thumbLabel = { "${it / 10f}x" },
                    markerStep = FONT_SCALE_NEUTRAL,
                    markerLabel = "默认",
                )
                StepGlyphButton("＋", onClickLabel = "增大") { onScaleStep(pendingScale + 1) }
            }
            EInkHorizontalDivider()
        }

        // 列表区 / 空态（翻页只作用于本区）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (fontsLoaded && fontOptions.isEmpty() && presets.isEmpty()) {
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
                        .EInkPagerInput(
                            onPageUp = pageUp,
                            onPageDown = pageDown,
                        ),
                ) {
                    // 表头系统预设项（「我的」模式；阅读模式 presets 为空）：
                    // 平台默认字体渲染，选中即应用（0 = 清除自定义回落默认）
                    presets.forEach { preset ->
                        item(key = "preset-$preset") {
                            FontListRow(
                                label = appFontPresetLabel(preset),
                                fontFamily = null,
                                selected = selectedPreset == preset,
                                onClick = { onSelectPreset(preset) },
                            )
                        }
                    }
                    items(fontOptions, key = { it.path }) { option ->
                        val fontFamily = rememberFontFamily(option.path)
                        FontListRow(
                            label = option.name.substringBeforeLast("."),
                            fontFamily = fontFamily,
                            selected = option.path == selectedFontPath,
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
                            .background(EInkTheme.colorScheme.background),
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
                    onClick = onBack,
                )
            },
            actions = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_folder),
                    contentDescription = "选择字体文件夹",
                    onClick = onPickFolder,
                )
            },
            pageArrows = {
                EInkPageArrows(
                    pageUpEnabled = pager.canPageUp(),
                    pageDownEnabled = pager.canPageDown(totalRows),
                    onPageUp = pageUp,
                    onPageDown = pageDown,
                )
            },
        )
    }
}

/** ± 单档步进：越界钳制；与当前生效值相同则不写不刷，返回是否写入（写入方 recreate）。 */
private fun applyScaleStep(
    globalSettings: GlobalSettings,
    setting: Int?,
    target: Int,
    onPreview: (Int) -> Unit,
): Boolean {
    val next = target.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
    onPreview(next)
    if (next == setting) {
        return false
    }
    globalSettings.fontScaleSetting = next
    return true
}

/** 步进按钮（−/＋）：按压反色（共享配色解析 + 120ms 最短保持，规范 §35）。 */
@Composable
private fun StepGlyphButton(
    glyph: String,
    onClickLabel: String,
    onClick: () -> Unit
) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        EInkText(
            text = glyph,
            style = EInkTheme.typography.titleLarge,
            color = colors.contentColor
        )
    }
}

/** 系统预设档位号与标签的有效范围（0 默认 / 1 衬线 / 2 等宽，与阅读字体
 *  预设编号同口径；标签收口在模块侧）。 */
private val APP_FONT_PRESET_RANGE = 0..2

private fun appFontPresetLabel(preset: Int): String = when (preset) {
    0 -> "系统默认"
    1 -> "系统衬线"
    2 -> "系统等宽"
    else -> "系统预设 $preset"
}

/** 初始定位目标下标：选中字体项（含预设表头偏移）；未选中/幽灵选中归 0
 *  （「我的」模式即系统默认项）。 */
private fun positionTargetIndex(
    presets: List<Int>,
    fontOptions: List<ReaderFontOption>,
    selectedPath: String?,
): Int {
    val fontIndex = selectedPath
        ?.let { path -> fontOptions.indexOfFirst { it.path == path } }
        ?.takeIf { it >= 0 } ?: return 0
    return presets.size + fontIndex
}

/** 选中标记尺寸：左侧实心竖条（§42 additive inking：加黑比去黑可靠）。
 *  高度与行内文字块三行同高（26+4+16+4+16=66dp，bodyLarge 名称 + xs 间距
 *  + 两行 bodySmall 示例），窄条贯穿文字块，三行行内选中态依旧可辨。 */
private val FontRowMarkWidth = 4.dp
private val FontRowMarkHeight = 66.dp

/** 字体行高：名称 + 中英示例两行（等高行是分页不变量，触控目标）。 */
private val FontRowHeight = 88.dp

/** 示例文字（中文行）：自然中文短句（口径同「我的」字体大小页），
 *  覆盖汉字与常用标点字形；按最小屏宽一行放得下取 19 字，超宽由
 *  单行省略号兜底。 */
private const val FontSampleTextCn = "合适的字体，让目光在字里行间从容行走。"

/** 示例文字（英文行）：自然英文短句，与中文行同为预览小写/大写/标点
 *  字形；36 字符按最小屏宽一行放得下取值，超宽单行省略号兜底。 */
private const val FontSampleTextEn = "Good type makes reading a quiet joy."

/**
 * 字体文件 path → FontFamily（每行/预览一份）：经宿主端口 loadFontTypeface
 * 在 IO 上下文异步加载（宿主侧进程级缓存，同路径不重复解码），加载失败
 * 为 null → 不指定 fontFamily 回落平台默认字体。path 不变不重新加载；
 * null（无自定义/系统默认）直接为 null。
 */
@Composable
private fun rememberFontFamily(path: String?): FontFamily? {
    if (path == null) return null
    val typeface by produceState<Typeface?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { EInkEngineRegistry.readerEngine.loadFontTypeface(path) }
                .getOrNull()
        }
    }
    return remember(typeface) { typeface?.let(::FontFamily) }
}

/**
 * 列表行（预设项与字体文件行共用）：定高 88dp（名称 + 中英示例两行；
 * 等高行是分页不变量）。字体文件行以该字体的 Typeface 渲染
 * （[rememberFontFamily]），系统预设项 fontFamily 为 null（平台默认）。
 * 选中 = 左侧实心竖条 + 名称加粗（§42 不用整行持久反色）；按压瞬时
 * 反色（§35）。写法同目录页 ChapterItem（TocScreen.kt）。
 */
@Composable
private fun FontListRow(
    label: String,
    fontFamily: FontFamily?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = EInkTheme.colorScheme
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FontRowHeight)
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            // 与目录界面行同口径 16dp（亦与顶栏标题左边距同列对齐）
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
        Column(verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs)) {
            EInkText(
                text = label,
                fontFamily = fontFamily,
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
            // 示例两行（中/英）用 tertiaryContent 与名称行拉开一档实灰
            // （灰阶做减法，不用字重/字号层级）；按压跟随整行反色
            EInkText(
                text = FontSampleTextCn,
                fontFamily = fontFamily,
                style = EInkTheme.typography.bodySmall,
                color = if (press.isPressed) colors.contentColor else scheme.tertiaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EInkText(
                text = FontSampleTextEn,
                fontFamily = fontFamily,
                style = EInkTheme.typography.bodySmall,
                color = if (press.isPressed) colors.contentColor else scheme.tertiaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
