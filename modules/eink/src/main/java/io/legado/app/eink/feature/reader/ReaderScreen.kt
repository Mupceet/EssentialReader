package io.legado.app.eink.feature.reader

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Paint
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.reader.selection.ReaderSelectionMenu
import io.legado.app.eink.feature.reader.selection.ReaderSelectionMenuAction
import io.legado.app.eink.feature.reader.selection.ReaderSelectionOverlay
import io.legado.app.eink.feature.reader.selection.ReaderSelectionUi
import io.legado.app.eink.feature.reader.selection.buildSelection
import io.legado.app.eink.feature.reader.selection.handleAnchor
import io.legado.app.eink.feature.reader.selection.hitTest
import io.legado.app.eink.feature.reader.selection.moveEndpoint
import io.legado.app.eink.feature.reader.selection.selectionRuns
import io.legado.app.eink.feature.reader.selection.snapToWord
import kotlinx.coroutines.launch

/** 排版设置的居中弹层形态。 */
private enum class ReaderStyleDialog { Fonts, Info, Margin }

/**
 * 阅读 Route — ViewModel 感知层。
 *
 * 全屏说明：窗口在 Activity 层始终 Edge-to-Edge（进出阅读无布局跳动）；
 * 状态栏默认显示，「其它设置 → 隐藏状态栏」开启时收起（转发完整模式
 * 同键设置），页眉接管 时间/电量；菜单展开期恢复状态栏显示（对齐
 * 完整模式 toolBarHide 语义），顶栏/面板避让到其下方。
 *
 * 职责：
 * - 按设置保持屏幕常亮；
 * - 返回键：面板 → 控件 → 退出阅读 的逐级回退；面板/弹框外空白区
 *   点击则一次性收起到干净阅读界面；
 * - 一次性消息 → Toast；
 * - 页内长按选区状态在此持有：翻页/重排（pageVersion 推进）自动清空，
 *   选择浮条「复制」直接落剪贴板；
 * - 面板开关为 UI 局部状态（remember），排版数据来自 [ReaderUiState]。
 */
@Composable
fun ReaderRoute(
    bookUrl: String,
    onBack: () -> Unit,
    onOpenToc: (String) -> Unit,
    onChangeSource: (String) -> Unit,
    onOpenDetail: (name: String, author: String, bookUrl: String) -> Unit,
    viewModel: ReaderViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    // 按键转发枢纽：宿主入口 Activity onKeyDown/onKeyUp 经注册表下发
    val keyEventHub = EInkEngineRegistry.keyEventHub
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    // 排版设置弹层（字体配置/信息配置/边距调整）：居中透明卡片，
    // 打开期间面板与操作条隐藏；返回键逐级回退到排版展开态
    var styleDialog by remember { mutableStateOf<ReaderStyleDialog?>(null) }
    // 移出书架二次确认（顶栏切换钮在架态点击只打开确认框）
    var showRemoveConfirm by remember { mutableStateOf(false) }

    // 页内长按选区（Screen 无状态渲染，选区状态在此持有）：选区坐标绑定
    // 单页快照，pageVersion 推进（翻页/重排/批注落库重绘）即自动清空，
    // 同时承载批注保存重绘后的清区时序
    var selection by remember { mutableStateOf<ReaderSelectionUi?>(null) }
    LaunchedEffect(uiState.pageVersion) {
        selection = null
    }

    // 选择浮条动作：复制直接落剪贴板并清选区；书签/笔记弹层与落库在
    // 后续切片接入（浮条键显隐由 selectionEngine 端口可用性决定）
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val onSelectionMenuAction = { action: ReaderSelectionMenuAction, sel: ReaderSelectionUi ->
        when (action) {
            // 书签/笔记弹层在后续切片接管，先显式 no-op
            ReaderSelectionMenuAction.BOOKMARK, ReaderSelectionMenuAction.MARKING -> Unit

            ReaderSelectionMenuAction.COPY -> {
                clipboardScope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("text", sel.selectedText))
                    )
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
                selection = null
            }
        }
    }

    // 字体文件夹选择（SAF）：持久化读权限后交 VM 落库并刷新字体列表
    val fontFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setFontFolder(it.toString())
        }
    }

    // 字体文件列表：字体弹层打开时拉取（SAF 换文件夹后由 VM 刷新）
    val fontOptions by viewModel.fontOptions.collectAsStateWithLifecycle()
    LaunchedEffect(styleDialog) {
        if (styleDialog == ReaderStyleDialog.Fonts) viewModel.loadFontOptions()
    }

    LaunchedEffect(bookUrl) {
        viewModel.attach(bookUrl)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg.format(context), Toast.LENGTH_SHORT).show()
        }
    }

    // 保持屏幕常亮：常亮设置开启或自动翻页运行中时申请；
    // 离开阅读页时清除标记（常亮只作用于阅读页）
    DisposableEffect(uiState.keepScreenOn, uiState.autoPlay) {
        val window = (view.context as? Activity)?.window
        if (uiState.keepScreenOn || uiState.autoPlay) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 隐藏状态栏（转发完整模式同键设置），对齐 View 版语义：沉浸条件为
    // 「开关开启 && 操作条收起」（ReadBookController 的 toolBarHide &&
    // hideStatusBar）——菜单展开期恢复状态栏显示，收起后回到沉浸阅读。
    // 顶部避让由 readerSystemBarInsets 显式置零且只跟随开关本身，正文
    // 排版区域尺寸不随菜单开合变化（规范 §15）；菜单层（顶栏/面板）以
    // 高度快照固定避让（不跟随回归动画，一步落位），顶栏 surface 自
    // 屏幕上缘铺起垫在状态栏图标后方。旧平台（API < 30）legacy 布局
    // 标记 LAYOUT_STABLE 下系统栏插图冻结在「栏可见」尺寸、不随
    // hide() 归零，不能依赖 safeDrawing 自动收缩；滑动可临时浮现
    // （TRANSIENT 浮层）。离开阅读页（含去目录/换源）时恢复显示，其余
    // 界面不受影响。
    val immersiveReading = uiState.hideStatusBar && !uiState.controlsVisible
    DisposableEffect(immersiveReading) {
        val controller = (view.context as? Activity)?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
        if (immersiveReading) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    // 音量键翻页（对齐 View 版 ReadBookController.volumeKeyPage）：
    // 音量+ 上一页、音量- 下一页，仅在首按（repeatCount == 0）翻页，
    // 长按重复不翻（View 版 keyPageDebounce 同样忽略长按）；
    // 开关关闭或离开阅读页时处理器注销/放行，音量键回归系统调节。
    // 设置实时读取（GlobalSettings.volumeKeyPage 经桥接层走宿主快照）
    DisposableEffect(keyEventHub) {
        keyEventHub.handler = { event ->
            if (!EInkEngineRegistry.globalSettings.volumeKeyPage) {
                false
            } else {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            if (event.repeatCount == 0) viewModel.prevPage()
                            true
                        }

                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            if (event.repeatCount == 0) viewModel.nextPage()
                            true
                        }

                        else -> false
                    }
                    // 消费抬起，保证按键对整体被吞掉
                    KeyEvent.ACTION_UP ->
                        event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

                    else -> false
                }
            }
        }
        onDispose { keyEventHub.handler = null }
    }

    // 自动翻页随界面可见性暂停/恢复：退后台（ON_STOP）暂停倒计时，
    // 避免后台继续翻页；回前台（ON_START）重新起算。应用内离开阅读
    // 目的地（目录/换源等）时组合整体卸载（EInkApp when 直换），由
    // onDispose 暂停、返回时经 effect 体恢复
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onReaderHidden()
                Lifecycle.Event.ON_START -> viewModel.onReaderShown()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.onReaderShown()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onReaderHidden()
        }
    }

    // 返回键逐级回退：排版弹层 → 设置面板 → 收起操作条 → 退出阅读
    // （弹层期间排版面板保留，返回即回到排版展开态）
    BackHandler {
        when {
            styleDialog != null -> styleDialog = null
            panel != null -> panel = null
            uiState.controlsVisible -> viewModel.hideControls()
            else -> onBack()
        }
    }

    // 菜单层顶部避让（固定值，不跟随状态栏回归动画）：开关开启时菜单
    // 展开期状态栏恢复显示，避让取进入阅读期捕获的高度快照；开关关闭
    // 时外层 readerSystemBarInsets（safeDrawing）已承担顶部避让，取 0
    val statusBarTop = rememberStatusBarTop()
    val menuTopInset = if (uiState.hideStatusBar) statusBarTop else 0.dp

    // 操作条返回图标：关闭设置面板 → 退出阅读。
    // 排版弹层期间操作条整体隐藏（保证调参实时可见），其首级返回
    // 由系统返回键/点击弹框外区域承担，关闭后回到排版展开态
    val onBarBack = {
        if (panel != null) panel = null else onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderScreen(
            state = uiState,
            statusBarTopInset = menuTopInset,
            // 顶栏在设置面板打开期间隐藏（保持页眉等顶部调参预览不被遮挡）；
            // 底部操作条常驻可见，承载面板期间的返回与选中态；
            // 排版弹层例外：操作条隐藏，保证排版调参实时可见
            topBarVisible = uiState.controlsVisible && panel == null,
            bottomBarVisible = uiState.controlsVisible && styleDialog == null,
            onPrevPage = viewModel::prevPage,
            onNextPage = viewModel::nextPage,
            onCenterTap = {
                // 打开阅读菜单时，若自动翻页正在运行则停止；并自动打开
                // 进度与翻页面板（未开启自动翻页时不自动打开）。
                val openingControls = !uiState.controlsVisible
                viewModel.toggleControls()
                if (openingControls && uiState.autoPlay) {
                    panel = ReaderPanel.PROGRESS
                }
            },
            onContentSized = viewModel::updateViewSize,
            onBack = onBack,
            onBarBack = onBarBack,
            // 换源后路由参数已失效（旧书行连同章节被删、新书换了 bookUrl），
            // 与 onOpenDetail 同一取值：优先会话书的当前 bookUrl。目录据此
            // 直接命中换源时已入库的新目录；二次换源也才能解析到当前书
            onOpenToc = { onOpenToc(uiState.bookUrl.ifEmpty { bookUrl }) },
            onChangeSource = { onChangeSource(uiState.bookUrl.ifEmpty { bookUrl }) },
            onOpenDetail = {
                // 换源后以引擎当前持有的书为准（bookUrl 与路由参数可能不同）
                if (uiState.bookUrl.isNotEmpty()) {
                    onOpenDetail(uiState.bookName, uiState.bookAuthor, uiState.bookUrl)
                }
            },
            onRefresh = viewModel::refreshChapter,
            onOpenCachePanel = { panel = ReaderPanel.CACHE },
            onAddToBookshelf = viewModel::addToBookshelf,
            onRemoveFromBookshelf = { showRemoveConfirm = true },
            selectedPanel = panel,
            onOpenPanel = { target ->
                // 再次点击已打开的面板按钮 = 关闭（取消选中）；
                // 排版弹层打开时点击则先收回弹层、回到面板
                val toggleOff = panel == target && styleDialog == null
                styleDialog = null
                panel = if (toggleOff) null else target
            },
            onRetry = { viewModel.attach(bookUrl) },
            selection = selection,
            selectionEnabled = viewModel.selectionEnabled,
            onSelectionChange = { selection = it },
            onSelectionMenuAction = onSelectionMenuAction,
        )

        // 面板/弹框外空白区一次性收起：直接回到干净阅读界面
        // （× 与系统返回、操作条返回仍为逐级回退）
        val dismissToCleanReading = {
            panel = null
            styleDialog = null
            viewModel.hideControls()
        }
        // 设置面板与阅读内容对齐（Edge-to-Edge 下避免被系统栏遮挡），
        // 底部避开常驻操作条，保持其可见可点；
        // 排版弹层期间面板隐藏（panel 状态保留），关闭弹层后回到展开态
        panel?.takeIf { styleDialog == null }?.let { current ->
            val onClose = { panel = null }
            // 面板与阅读内容对齐（Edge-to-Edge 下避免被系统栏遮挡，
            // 顶部避让与正文同规则——状态栏收起时同样上移），
            // 底部避开常驻操作条，保持其可见可点
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .readerSystemBarInsets(uiState.hideStatusBar)
                    // 菜单展开期状态栏恢复显示（开关开启时上一行顶部已置零）：
                    // 面板同样避让到状态栏下方，固定快照一步落位；开关关闭时
                    // 顶部插图已被上一行消费，此处取 0 不重复避让
                    .padding(top = menuTopInset)
                    .padding(bottom = ReaderBottomBarInset)
            ) {
                when (current) {
                    ReaderPanel.LAYOUT -> ReaderPanelContainer(
                        title = "排版设置",
                        onClose = onClose,
                        onBackdropClick = dismissToCleanReading,
                    ) {
                        ReaderLayoutPanel(
                            catalog = viewModel.styleCatalog,
                            style = uiState.style,
                            onSetTextSize = viewModel::setTextSize,
                            onSetLetterSpacing = viewModel::setLetterSpacing,
                            onSetIndent = viewModel::setIndent,
                            onSetLineSpacing = viewModel::setLineSpacing,
                            onSetParagraphSpacing = viewModel::setParagraphSpacing,
                            onOpenFonts = { styleDialog = ReaderStyleDialog.Fonts },
                            onOpenInfo = { styleDialog = ReaderStyleDialog.Info },
                            onOpenMargins = { styleDialog = ReaderStyleDialog.Margin },
                        )
                    }

                    ReaderPanel.PROGRESS -> ReaderPanelContainer(
                        title = "进度与翻页",
                        onClose = onClose,
                        onBackdropClick = dismissToCleanReading,
                    ) {
                        ReaderProgressPanel(
                            state = uiState,
                            onPrevChapter = viewModel::prevChapter,
                            onNextChapter = viewModel::nextChapter,
                            onSkipToPage = viewModel::skipToPage,
                            onSetAutoInterval = viewModel::setAutoPlayInterval,
                            onToggleAutoPlay = viewModel::toggleAutoPlay,
                        )
                    }

                    ReaderPanel.OTHER -> ReaderPanelContainer(
                        title = "其它设置",
                        onClose = onClose,
                        onBackdropClick = dismissToCleanReading,
                    ) {
                        ReaderOtherPanel(
                            state = uiState,
                            onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                            onToggleHideStatusBar = viewModel::toggleHideStatusBar,
                        )
                    }

                    ReaderPanel.CACHE -> ReaderPanelContainer(
                        title = "缓存",
                        onClose = onClose,
                        onBackdropClick = dismissToCleanReading,
                    ) {
                        ReaderCachePanel(
                            onCache = { count ->
                                viewModel.cacheChapters(count)
                                panel = null
                            }
                        )
                    }
                }
            }
        }

        // 排版弹层：居中透明卡片（面板保留不销毁），关闭后回到排版展开态
        when (styleDialog) {
            ReaderStyleDialog.Margin -> ReaderMarginDialog(
                catalog = viewModel.styleCatalog,
                style = uiState.style,
                onSetPaddingTop = viewModel::setPaddingTop,
                onSetPaddingBottom = viewModel::setPaddingBottom,
                onSetPaddingLeft = viewModel::setPaddingLeft,
                onSetPaddingRight = viewModel::setPaddingRight,
                onSetHeaderPaddingTop = viewModel::setHeaderPaddingTop,
                onSetHeaderPaddingBottom = viewModel::setHeaderPaddingBottom,
                onSetHeaderPaddingLeft = viewModel::setHeaderPaddingLeft,
                onSetHeaderPaddingRight = viewModel::setHeaderPaddingRight,
                onSetFooterPaddingTop = viewModel::setFooterPaddingTop,
                onSetFooterPaddingBottom = viewModel::setFooterPaddingBottom,
                onSetFooterPaddingLeft = viewModel::setFooterPaddingLeft,
                onSetFooterPaddingRight = viewModel::setFooterPaddingRight,
                onClose = { styleDialog = null },
                onBackdropClick = dismissToCleanReading,
            )

            ReaderStyleDialog.Fonts -> ReaderFontConfigDialog(
                catalog = viewModel.styleCatalog,
                style = uiState.style,
                fontOptions = fontOptions,
                onSetFont = viewModel::setReaderFont,
                onSetBodyWeight = viewModel::setBodyWeight,
                onSetTitleWeight = viewModel::setTitleWeight,
                onPickFolder = { fontFolderLauncher.launch(null) },
                onClose = { styleDialog = null },
                onBackdropClick = dismissToCleanReading,
            )
            ReaderStyleDialog.Info -> ReaderInfoConfigDialog(
                catalog = viewModel.styleCatalog,
                style = uiState.style,
                titleSizeFollowBody = uiState.titleSizeFollowBody,
                onSetTitleMode = viewModel::setTitleMode,
                onSetTitleSizeFollowBody = viewModel::setTitleSizeFollowBody,
                onSetTitleSize = viewModel::setTitleSize,
                onSetHeaderMode = viewModel::setHeaderMode,
                onSetFooterVisible = viewModel::setFooterVisible,
                onSetTipSize = viewModel::setTipSize,
                onClose = { styleDialog = null },
                onBackdropClick = dismissToCleanReading,
            )
            null -> Unit
        }

        // 移出书架二次确认：确认后执行移出（后果与详情页一致——下次进
        // 书架时该记录被物理删除、阅读进度丢失）
        if (showRemoveConfirm) {
            EInkDialog(
                onDismiss = { showRemoveConfirm = false },
                title = "移出书架",
                onConfirm = {
                    showRemoveConfirm = false
                    viewModel.removeFromBookshelf()
                },
            ) {
                EInkText(
                    text = "确定要将《${uiState.bookName}》移出书架吗？",
                    style = EInkTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * 无状态阅读 Screen — 纯渲染。
 *
 * 结构：页眉（书名/进度）→ 正文 Canvas（引擎排版区域）→ 页脚（章节/页码）。
 * 操作条覆盖在正文之上，不改变排版区域尺寸（布局稳定，规范 §15）。
 *
 * 操作条可见性：设置面板打开期间底部操作条保持可见（承载分层返回
 * [onBarBack] 与面板选中态 [selectedPanel]），顶栏隐藏以保持顶部调参预览。
 *
 * 系统栏避让由本界面自管（readerSystemBarInsets）：窗口 Edge-to-Edge、
 * 状态栏默认可见，页眉紧贴状态栏下方；「隐藏状态栏」开启时状态栏
 * 收起，状态栏区域转为页眉区域（页眉上移占位），正文始终从页眉之下
 * 开始排版。菜单展开期状态栏恢复显示，正文不移位不重排；顶栏以
 * surface 实底自屏幕上缘铺起（垫在透明状态栏后方），内容按高度快照
 * 固定避让、一步落位。
 *
 * 手势（规范 §16）：
 * - 操作条可见时：点/滑动正文任意处收起操作条；
 * - 操作条隐藏时：点中间 40% 唤出操作条，点其余区域下一页；
 * - 长按正文选词（震动反馈），拖拽延伸选区末端，松手弹出浮条
 *   （书签/笔记/复制；书签/笔记键按 [selectionEnabled] 显隐）；
 *   选区存在期间点按先清选区再按分区执行常规行为、水平滑动不翻页，
 *   把手拖拽调整端点（浮条随锚点重排）；
 * - 水平滑动翻页，判定对齐 View 版：触发距离读引擎 pageTouchSlop（AppConfig.pageTouchSlop 经端口）
 *   （完整版设置"翻页触发距离"，0 = 系统 slop，Compose 版只读不设），
 *   松手前反向回拖取消；无跟手移动，翻页整页立即替换。
 *
 * 选区状态由调用方持有（[selection] / [onSelectionChange]），翻页/重排
 * （pageVersion 推进）清空也由调用方承担；浮条动作经 [onSelectionMenuAction]
 * 上抛（选区随动作语义由调用方决定去留）。
 */
@Composable
internal fun ReaderScreen(
    state: ReaderUiState,
    statusBarTopInset: Dp,
    topBarVisible: Boolean,
    bottomBarVisible: Boolean,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: () -> Unit,
    onContentSized: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onBarBack: () -> Unit,
    onOpenToc: () -> Unit,
    onChangeSource: () -> Unit,
    onOpenDetail: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCachePanel: () -> Unit,
    onAddToBookshelf: () -> Unit,
    onRemoveFromBookshelf: () -> Unit,
    selectedPanel: ReaderPanel?,
    onOpenPanel: (ReaderPanel) -> Unit,
    onRetry: () -> Unit,
    selection: ReaderSelectionUi?,
    selectionEnabled: Boolean,
    onSelectionChange: (ReaderSelectionUi?) -> Unit,
    onSelectionMenuAction: (ReaderSelectionMenuAction, ReaderSelectionUi) -> Unit,
) {
    // 纯净阅读底色/字色随日/夜间主题（决策 B1/B2 修订：仍不读取
    // bgStrEInk / textColorEInk 等用户配色配置，颜色由主题统一下发）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
            .readerSystemBarInsets(state.hideStatusBar)
    ) {
        val density = LocalDensity.current
        // ===== 长按选择手势支撑 =====
        val haptics = LocalHapticFeedback.current
        // pointerInput 闭包跨手势存活：page/selection 经 State 读实时值，
        // 不以其为 pointerInput key——长按选词即改写 selection，以之为 key
        // 会在拖拽中途重启、打断手势（覆盖层同理由，见 ReaderSelectionOverlay）
        val currentPage by rememberUpdatedState(state.page)
        val currentSelection by rememberUpdatedState(selection)
        // controlsVisible/error 同理：长按检测器不以之为 key——键在手势中途
        // 翻转（长按选词即收起/展开操作条）会重启检测器，onDragEnd/onDragCancel
        // 均不执行，selectionMenuVisible 停留 false 而选区仍在（浮条不可达的
        // 僵死选区）；长按守卫改读 State 实时值
        val currentControlsVisible by rememberUpdatedState(state.controlsVisible)
        val currentError by rememberUpdatedState(state.error)
        // 与页画布同规格的测量闭包（applySpec 幂等，重复设置无害）：
        // 长按命中测试与浮条锚点按引擎同款字体度量
        val themeForeground = EInkTheme.colorScheme.onBackground
        val titleSpec = state.page?.titleSpec
        val contentSpec = state.page?.contentSpec
        val measureTitle = remember(titleSpec, themeForeground) {
            val paint = Paint()
            val spec = titleSpec
            { text: String ->
                spec?.let { paint.applySpec(it, themeForeground.toArgb()) }
                paint.measureText(text)
            }
        }
        val measureContent = remember(contentSpec, themeForeground) {
            val paint = Paint()
            val spec = contentSpec
            { text: String ->
                spec?.let { paint.applySpec(it, themeForeground.toArgb()) }
                paint.measureText(text)
            }
        }
        // 松手弹菜单：长按拖拽期间隐藏、松手展示；仅「selection 非空」参与
        // 菜单组合，选区清空（复制/点按/翻页）后残留 true 无副作用，
        // 下次长按选词在 onDragStart 归位 false
        var selectionMenuVisible by remember { mutableStateOf(false) }
        // 画布实测宽：浮条 x 钳制不越界
        var canvasWidthPx by remember { mutableStateOf(0) }
        // 排版画布铺满整个阅读区（页眉/页脚装饰空间含在内，由宿主分页器
        // 预留——与完整模式「画布全屏 + 装饰画在预留区」同构）。页眉/页脚
        // 以宿主预留高度定高叠加在画布上，落在正文避让出的预留区内
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    canvasWidthPx = size.width
                    onContentSized(size.width, size.height)
                }
                .pointerInput(state.controlsVisible, selection != null) {
                        detectTapGestures { offset ->
                            if (state.controlsVisible) {
                                onCenterTap() // 收起操作条
                                return@detectTapGestures
                            }
                            if (selection != null) {
                                // 选区存在：点按先清选区，再按分区执行常规行为
                                onSelectionChange(null)
                            }
                            val width = size.width
                            if (offset.x in width * 0.3f..width * 0.7f) {
                                onCenterTap()
                            } else {
                                onNextPage()
                            }
                        }
                    }
                    .pointerInput(state.controlsVisible, selection != null) {
                        // 水平滑动翻页，判定对齐 View 版：
                        // - 触发距离 = 引擎 pageTouchSlop（px），0 = 系统 touch slop
                        //   （该 slop 已由 detectHorizontalDragGestures 消费）；
                        // - 松手前最后一次增量与滑动方向相反则取消（等价 View 版 isCancel）；
                        // - 选区存在期间水平手势不翻页（端点调整只经把手拖拽）
                        var dragAccum = 0f
                        var lastDelta = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragAccum = 0f
                                lastDelta = 0f
                            },
                            onDragEnd = {
                                if (state.controlsVisible) {
                                    onCenterTap() // 收起操作条，不翻页
                                } else if (selection == null) {
                                    val slop =
                                        EInkEngineRegistry.readerEngine.pageTouchSlop.toFloat()
                                    when {
                                        lastDelta * dragAccum < 0f -> Unit // 回拖取消
                                        dragAccum < -slop -> onNextPage()
                                        dragAccum > slop -> onPrevPage()
                                    }
                                }
                                dragAccum = 0f
                                lastDelta = 0f
                            },
                            onDragCancel = {
                                dragAccum = 0f
                                lastDelta = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount
                            if (dragAmount != 0f) lastDelta = dragAmount
                        }
                    }
                    .pointerInput(state.pageVersion) {
                        // 长按选词 + 拖拽延伸：键不含 selection/controlsVisible——
                        // 长按选词即改写选区状态、控件开合也可发生在手势中途，
                        // 以之为 key 会在拖拽中途重启打断手势（onDragEnd/
                        // onDragCancel 不再执行，选区僵死），实时值经
                        // rememberUpdatedState 读取。仅「本次长按新建选区」的拖拽
                        // 延伸末端（startHit 固定）；已有选区时长按不重建，端点
                        // 调整只经把手拖拽
                        var dragCreatedSelection = false
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragCreatedSelection = false
                                if (!currentControlsVisible && currentError == null &&
                                    currentSelection == null
                                ) {
                                    val page = currentPage
                                    if (page != null) {
                                        val hit = hitTest(
                                            page, offset.x, offset.y, measureContent
                                        )
                                        if (hit != null) {
                                            val word = snapToWord(page, hit)
                                            buildSelection(page, word, word)?.let { created ->
                                                onSelectionChange(created)
                                                selectionMenuVisible = false
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                                dragCreatedSelection = true
                                            }
                                        }
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val page = currentPage
                                val sel = currentSelection
                                if (!dragCreatedSelection || page == null || sel == null) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                val hit = hitTest(
                                    page, change.position.x, change.position.y, measureContent
                                ) ?: return@detectDragGesturesAfterLongPress
                                onSelectionChange(moveEndpoint(page, sel, isStart = false, hit))
                            },
                            onDragEnd = {
                                // 选区保留，松手弹菜单
                                if (dragCreatedSelection) selectionMenuVisible = true
                            },
                            onDragCancel = {
                                if (dragCreatedSelection) selectionMenuVisible = true
                            },
                        )
                    }
            ) {
            ReaderPageSnapshotCanvas(
                page = state.page,
                pageVersion = state.pageVersion,
                modifier = Modifier.fillMaxSize(),
            )
            // 选区覆盖层：高亮带垫在页画布下方（zIndex 由覆盖层自管），
            // 把手/指针独占在正文上方；浮条以 zIndex(2f) 组合在本层之上
            ReaderSelectionOverlay(
                snapshot = state.page,
                selection = selection,
                onSelectionChange = onSelectionChange,
                modifier = Modifier.matchParentSize(),
            )
            if (selection != null && selectionMenuVisible && !state.controlsVisible) {
                val page = state.page
                if (page != null) {
                    val runs = selectionRuns(page, selection, measureTitle, measureContent)
                    val anchors = handleAnchor(runs)
                    if (anchors != null) {
                        ReaderSelectionMenu(
                            anchorLeft = anchors.first.first,
                            anchorTop = anchors.first.second,
                            anchorRight = anchors.second.first,
                            anchorBottom = runs.last().bottom,
                            canvasWidth = canvasWidthPx.toFloat(),
                            showBookmark = selectionEnabled,
                            showMarking = selectionEnabled && !selection.includesTitle,
                            onAction = { action ->
                                onSelectionMenuAction(action, selection)
                            },
                        )
                    }
                }
            }
            if (state.isLoading && state.page == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EInkText(
                        text = "加载中…",
                        color = EInkTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.error != null) {
                ErrorView(message = state.error, onRetry = onRetry, onBack = onBack)
            }
            }
        if (state.headerVisible) {
            val extentPx = EInkEngineRegistry.readerEngine.headerDecorationExtentPx
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(with(density) { extentPx.toDp() })
            ) {
                // 菜单展开期状态栏恢复显示，会覆盖页眉条带（正文排版区域
                // 尺寸恒定不重排，页眉不移位）：文字转透明让出条带，
                // 收起菜单后状态栏再隐藏、时间/电量复现
                ReaderHeader(
                    state = state,
                    contentVisible = !(state.hideStatusBar && state.controlsVisible),
                    extentPx = extentPx,
                )
            }
        }
        if (state.footerVisible) {
            val extentPx = EInkEngineRegistry.readerEngine.footerDecorationExtentPx
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { extentPx.toDp() })
            ) {
                ReaderFooter(state = state, extentPx = extentPx)
            }
        }

        if (topBarVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // surface 从屏幕上缘铺起：垫在（Edge-to-Edge 下透明的）
                    // 状态栏图标后方形成实底条带，同时盖住下方的页眉条带
                    // （状态栏与页眉不再重叠透出）
                    .background(EInkTheme.colorScheme.surface)
                    // 内容让位状态栏：固定快照一步落位（开关关闭时取 0，
                    // 顶部避让由外层 readerSystemBarInsets 承担）
                    .padding(top = statusBarTopInset)
            ) {
                ReaderTopBar(
                    state = state,
                    onOpenDetail = onOpenDetail,
                    onChangeSource = onChangeSource,
                    onRefresh = onRefresh,
                    onOpenCachePanel = onOpenCachePanel,
                    onAddToBookshelf = onAddToBookshelf,
                    onRemoveFromBookshelf = onRemoveFromBookshelf,
                )
            }
        }
        if (bottomBarVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                ReaderBottomBar(
                    state = state,
                    selectedPanel = selectedPanel,
                    onBarBack = onBarBack,
                    onOpenToc = onOpenToc,
                    onOpenPanel = onOpenPanel,
                )
            }
        }
    }
}

/**
 * 阅读界面系统栏避让：左右/底部沿用 safeDrawing；顶部按「隐藏状态栏」
 * 开关显式置零——旧平台（API < 30）legacy 布局标记 LAYOUT_STABLE 下，
 * 系统栏插图冻结在「栏可见」尺寸、不随 insetsController.hide() 归零，
 * safeDrawing 自动收缩不可依赖，置零后状态栏区域转为页眉区域。
 * 对齐完整模式 LAYOUT_FULLSCREEN：隐藏时不避让刘海（墨水屏设备无刘海）。
 */
@Composable
private fun Modifier.readerSystemBarInsets(hideStatusBar: Boolean): Modifier {
    val top = if (hideStatusBar) {
        WindowInsets(0, 0, 0, 0)
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    }
    return windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    ).windowInsetsPadding(top)
}

/**
 * 状态栏高度快照：菜单层固定避让用——状态栏恢复显示的 show()/hide()
 * 动画期间系统栏插图从 0 插值到终值，菜单层若跟随实时插图会被逐步
 * 顶下来，改用快照一步落位。
 *
 * 捕获时机：进入阅读首帧状态栏尚未收起（上一界面必为显示态），
 * 插图即真实高度；此后只增不减，收起/展开不冲掉缓存。旋转后
 * remember 重建、若彼时状态栏已收起，首次展开菜单会随动画补捕一次。
 */
@Composable
private fun rememberStatusBarTop(): Dp {
    val liveTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var captured by remember { mutableStateOf(0.dp) }
    SideEffect {
        if (liveTop > captured) captured = liveTop
    }
    return captured
}

/**
 * 页眉：时间（左）+ 电量%（右）。可见性与内容按 View 版 ReadTipConfig 默认规则。
 * 字号优先取协商目录的页眉字号配置（[configuredTipTextStyle]），推导仅兜底。
 * 字体按「设置→跟随正文→系统默认」链从端口解析
 * （[io.legado.app.eink.contract.ReaderEngine.headerFooterTypefaces]）。
 *
 * [contentVisible] 为 false 时文字转透明（尺寸不变）：菜单展开期状态栏
 * 恢复显示覆盖页眉条带，让位但保持布局，避免正文重排。
 */
@Composable
private fun ReaderHeader(
    state: ReaderUiState,
    contentVisible: Boolean,
    extentPx: Float,
) {
    val textColor =
        if (contentVisible) EInkTheme.colorScheme.onSurfaceVariant else Color.Transparent
    val tipStyle = configuredTipTextStyle(
        availablePx = extentPx -
            state.style.headerPaddingTop.dpPx() -
            state.style.headerPaddingBottom.dpPx(),
        configuredSizeSp = state.style.headerSize,
    )
    val tipStyleWithFont = EInkEngineRegistry.readerEngine.headerFooterTypefaces().header
        ?.let { tipStyle.copy(fontFamily = FontFamily(it)) }
        ?: tipStyle
    // fillMaxSize：容器已按宿主页眉预留高度定高，行撑满预留区、文字
    // 纵向居中，与完整模式装饰的几何一致
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = state.style.headerPaddingLeft.dp,
                top = state.style.headerPaddingTop.dp,
                end = state.style.headerPaddingRight.dp,
                bottom = state.style.headerPaddingBottom.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = state.headerTime,
            modifier = Modifier.weight(1f),
            style = tipStyleWithFont.copy(color = textColor),
            maxLines = 1,
        )
        BasicText(
            text = "${state.batteryPercent}%",
            style = tipStyleWithFont.copy(color = textColor),
            maxLines = 1,
        )
    }
}

/**
 * 页脚：顶部自动翻页进度条 + 章节标题（左）/ 页数及进度（右，View 版 pageAndTotal 格式）。
 * 字体按「设置→跟随正文→系统默认」链从端口解析
 * （[io.legado.app.eink.contract.ReaderEngine.headerFooterTypefaces]）。
 */
@Composable
private fun ReaderFooter(state: ReaderUiState, extentPx: Float) {
    // 进度条 2dp 是模块自有装饰，不在宿主预留预算内，需先扣减；
    // 字号与页眉统一——按配置字号直接渲染（非 14/20 推导）
    val tipStyle = configuredTipTextStyle(
        availablePx = extentPx - 2.dpPx() -
            state.style.footerPaddingTop.dpPx() -
            state.style.footerPaddingBottom.dpPx(),
        configuredSizeSp = state.style.footerSize,
    )
    val tipStyleWithFont = EInkEngineRegistry.readerEngine.headerFooterTypefaces().footer
        ?.let { tipStyle.copy(fontFamily = FontFamily(it)) }
        ?: tipStyle
    // fillMaxSize：容器已按宿主页脚预留高度定高，文字行在剩余空间内
    // 纵向居中
    Column(modifier = Modifier.fillMaxSize()) {
        AutoPlayProgressBar(active = state.autoPlay, progress = state.autoPlayProgress)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    start = state.style.footerPaddingLeft.dp,
                    top = state.style.footerPaddingTop.dp,
                    end = state.style.footerPaddingRight.dp,
                    bottom = state.style.footerPaddingBottom.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = state.chapterTitle,
                modifier = Modifier.weight(1f),
                style = tipStyleWithFont.copy(color = EInkTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = state.pageAndTotal,
                style = tipStyleWithFont.copy(color = EInkTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
            )
        }
    }
}

/** dp 转像素（字体缩放不参与——排版装饰几何锚定像素）。 */
@Composable
private fun Int.dpPx(): Float = with(LocalDensity.current) { dp.toPx() }

/**
 * 页眉/页脚文字样式：排版装饰语义——**行高从容器可用高度推导**（宿主
 * 预留 − 配置边距 − 模块进度条），字号按 14/20 的字面/行高比缩放。
 * 两个不变量：
 *  - 恰好放得下：宿主预留的文字预算是按宿主字体度量算的，与本模块
 *    字体无关；行高锚定可用高度保证任何配置（页脚字号/边距/字体缩放）
 *    下都不超出，文字底部不再被裁；
 *  - 像素锚定不随字体缩放：用 Dp.toSp() 换算，应用内字体缩放不放大
 *    页眉/页脚（同正文；正文排版坐标是引擎测量像素）。宿主预留随其
 *    页眉/页脚字号设置变化时，模块文字同步伸缩。
 * 刻意不走 EInkText：其 14sp 下限按 sp 语义钳制，与像素锚定冲突。
 */
@Composable
private fun tipTextStyle(availablePx: Float): TextStyle {
    val density = LocalDensity.current
    val linePx = availablePx.takeIf { it > 0f } ?: with(density) { 23.dp.toPx() }
    return EInkTheme.typography.bodyMedium.copy(
        fontSize = with(density) { (linePx * 14f / 20f).toDp().toSp() },
        lineHeight = with(density) { linePx.toDp().toSp() },
    )
}

/**
 * 页眉/页脚文字样式（配置字号渲染）：协商目录字号可见后按配置字号
 * 渲染（配置值按 dp 像素锚定，不随应用内字体缩放），行高锚定条带
 * 可用高度、行内垂直居中——页眉与页脚用同一配置字号（eink 统一写入
 * 两侧），视觉上严格同字号。宿主 extent 本就按同字号的字体度量预留
 * （padding+fontLine+divider），配置字号按构造放得下；模块渲染字体与
 * 宿主字体度量不同时可能轻微越界，但 lineHeight 不裁字形、居中对称，
 * 观感安全。字号缺失或非正（旧宿主桥/极端配置）时回落 [tipTextStyle]
 * 推导。
 */
@Composable
private fun configuredTipTextStyle(availablePx: Float, configuredSizeSp: Int?): TextStyle {
    val density = LocalDensity.current
    if (configuredSizeSp != null && configuredSizeSp > 0) {
        val linePx = availablePx.takeIf { it > 0f } ?: with(density) { 23.dp.toPx() }
        return EInkTheme.typography.bodyMedium.copy(
            fontSize = configuredTipFontSizeSp(configuredSizeSp, density),
            lineHeight = with(density) { linePx.toDp().toSp() },
        )
    }
    return tipTextStyle(availablePx)
}

/**
 * 配置字号换算为像素锚定的 Compose 字号：配置值按 dp 解释，再换算回
 * 当前 density 下的 sp。这样实际绘制像素不随应用内 fontScale 放大，
 * 与宿主按像素预留的页眉/页脚条带一致。
 */
internal fun configuredTipFontSizeSp(configuredSizeSp: Int, density: Density): TextUnit =
    with(density) { configuredSizeSp.dp.toSp() }

/**
 * 自动翻页进度条：2dp 高度常驻占位——开关自动翻页不改变页脚高度，
 * 正文排版区域尺寸恒定，避免布局跳动/重排；未运行时不绘制（与背景
 * 融合不可见），运行时从左到右按时间进度以主色（日间纯黑）填充，
 * 未填充段保持背景色，进度随填充长度可感知。
 */
@Composable
private fun AutoPlayProgressBar(active: Boolean, progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(EInkTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EInkText(
            text = "加载失败",
            style = EInkTheme.typography.titleMedium,
        )
        EInkText(
            text = message,
            color = EInkTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(modifier = Modifier.padding(top = 16.dp)) {
            Box(
                modifier = Modifier
                    .einkClickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                EInkText(text = "重试", color = EInkTheme.colorScheme.onSurface)
            }
            Box(
                modifier = Modifier
                    .einkClickable(role = Role.Button, onClick = onBack)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                EInkText(text = "返回", color = EInkTheme.colorScheme.onSurface)
            }
        }
    }
}
