package io.legado.app.eink.app

import android.app.Application
import android.content.res.Resources
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.contract.AppDownloadState
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.debug.ComponentGalleryRoute
import io.legado.app.eink.debug.ThemeDebugRoute
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.bookdetail.BookDetailRoute
import io.legado.app.eink.feature.changesource.ChangeSourceRoute
import io.legado.app.eink.feature.home.FontScaleSettingsRoute
import io.legado.app.eink.feature.home.HomeRoute
import io.legado.app.eink.feature.home.releaseNoteToPlainText
import io.legado.app.eink.feature.reader.ReaderRoute
import io.legado.app.eink.feature.search.SearchRoute
import io.legado.app.eink.feature.toc.TocRoute

/**
 * E-Ink 应用根 Composable。
 *
 * 1. 通过 when 分支直接替换屏幕内容（无动画过渡，规范 §12, §44）
 * 2. 导航状态由 [EInkNavController] 管理（UDF: state hoisted to controller）
 * 3. 用 [rememberSaveableStateHolder] 按"导航栈条目"保留 rememberSaveable 状态
 *
 * @param initialReaderBookUrl 冷启动直达阅读页的 bookUrl（自动跳转最近阅读，
 * 非 null 时初始栈为 [书架, 阅读页]，阅读页返回即书架）
 */
@Composable
fun EInkApp(
    initialReaderBookUrl: String? = null,
    // 宿主注入的引擎能力出口："退出到完整模式"
    onExitToFullMode: () -> Unit,
    controller: EInkNavController = EInkNavController.remember(
        initialStack(initialReaderBookUrl)
    ),
) {
    // 更新检查状态的 Activity 级 VM：求值处位于每条目 store owner 覆盖
    // 之外（同 controller），跨屏幕切换与 Activity recreate 存活
    val updateViewModel: EInkAppUpdateViewModel = viewModel()

    // 启动自动检查（对齐宿主完整模式启动链）：设置开关与进程级一次性
    // 闸由端口实现侧裁决；Activity recreate 重跑本效应时闸已消耗，
    // 直接跳过，进行中的检查由 VM 状态承接
    LaunchedEffect(Unit) {
        EInkEngineRegistry.appUpdateEngine?.let(updateViewModel::autoCheckOnStart)
    }

    // 云端备份启动检查状态的 Activity 级 VM（与 updateViewModel 同作用域）：
    // 宿主完整模式 MainActivity.backupSync 的 eink 同链——判定/标记全在
    // 宿主端口侧，未注册端口（companion 宿主）静默跳过
    val backupSyncViewModel: EInkBackupSyncViewModel = viewModel()

    LaunchedEffect(Unit) {
        EInkEngineRegistry.backupSyncEngine?.let(backupSyncViewModel::checkOnStart)
    }

    // 恢复结果一次性 toast：VM 只持状态，展示归组合层
    val toastContext = LocalContext.current
    LaunchedEffect(backupSyncViewModel.oneShotNotice) {
        val notice = backupSyncViewModel.oneShotNotice ?: return@LaunchedEffect
        Toast.makeText(toastContext, notice, Toast.LENGTH_SHORT).show()
        backupSyncViewModel.clearNotice()
    }

    // 单 Activity 架构：系统返回键优先 pop 导航栈，根页面时交还系统（退出应用）
    BackHandler(enabled = controller.canPop) {
        controller.pop()
    }

    // 非阅读界面顶部避让快照（只增不减，见 rememberSafeDrawingTopMax）：
    // 状态栏在非阅读界面恒显示，栏高是常量，返回书架时一步落位不跳动
    val safeDrawingTopMax = rememberSafeDrawingTopMax()

    // when 分支切换会整体卸载离屏内容，rememberSaveable 状态随之丢失；
    // 用 SaveableStateHolder 按"导航栈条目"（entryId）保留：
    // pop 返回复用同一 entryId（保留状态），重新 push 则为新 entryId（即首次进入）。
    val stateHolder = rememberSaveableStateHolder()
    val screen = controller.screen
    val entryId = controller.currentEntryId

    // 按当前栈条目提供 ViewModelStoreOwner：ViewModel 作用域随条目隔离，
    // 退出界面再进入时 ViewModel 全新（如搜索历史/结果不再残留）。
    // 实现 HasDefaultViewModelProviderFactory 以便 AndroidViewModel(application)
    // 等构造方式仍可正常创建。
    val app = LocalContext.current.applicationContext as Application
    val viewModelStoreOwner = remember(controller, app) {
        object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore
                get() = controller.currentViewModelStore

            override val defaultViewModelProviderFactory: ViewModelProvider.Factory
                get() = ViewModelProvider.AndroidViewModelFactory.getInstance(app)

            override val defaultViewModelCreationExtras: CreationExtras
                get() = MutableCreationExtras().apply {
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] = app
                }
        }
    }

    stateHolder.SaveableStateProvider(key = entryId) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            if (screen is EInkScreen.Reader) {
                // 阅读界面自管系统栏避让与状态栏显隐（「隐藏状态栏」开关
                // 恒定语义，见 ReaderScreen 沉浸效应），不经本层 safeDrawing
                // 避让——页眉区域顶到屏幕上缘
                ReaderRoute(
                    bookUrl = screen.bookUrl,
                    onBack = { controller.pop() },
                    onOpenToc = { bookUrl ->
                        controller.navigate(EInkScreen.Toc(bookUrl, fromReader = true))
                    },
                    onChangeSource = { bookUrl ->
                        controller.navigate(EInkScreen.ChangeSource(bookUrl))
                    },
                    onOpenDetail = { name, author, bookUrl ->
                        controller.navigate(
                            EInkScreen.BookDetail(name, author, bookUrl, fromReader = true)
                        )
                    },
                )
            } else {
                // 其余界面统一避让系统栏（Edge-to-Edge 下系统栏透明覆盖在
                // 背景上）。顶部取状态栏快照：从阅读页（隐藏状态栏）返回时
                // 系统栏 show() 动画期间活值从 0 逐帧回升，跟随活值会让书架
                // 整体跳动一次，快照一步落位；左右/底部（刘海/导航栏/IME）
                // 不经历阅读页 show/hide，仍跟随活值
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = safeDrawingTopMax)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                            )
                        )
                ) {
                    when (screen) {
                        is EInkScreen.Home -> {
                            HomeRoute(
                                updateViewModel = updateViewModel,
                                onBookClick = { bookUrl ->
                                    // 点击即预取（对齐完整模式 MainNavigator 的
                                    // prefetchForOpen 时机）：会话装载与当前章内容
                                    // 读取和导航切换并行，阅读页进入时直接消费
                                    EInkEngineRegistry.readerEngine.prefetchOpen(bookUrl)
                                    // 书架点击直接进入阅读
                                    controller.navigate(EInkScreen.Reader(bookUrl))
                                },
                                onBookLongClick = { book ->
                                    // 长按进详情页（对齐 View 版书架交互）
                                    controller.navigate(
                                        EInkScreen.BookDetail(book.name, book.author, book.bookUrl)
                                    )
                                },
                                onSearch = { controller.navigate(EInkScreen.Search) },
                                // 完整模式（View UI）退出由宿主实现：恢复原主题并跳转
                                // 完整模式首页（导入导出等管理功能在完整模式中完成）
                                onOpenFullMode = onExitToFullMode,
                                onOpenFontScale = {
                                    controller.navigate(EInkScreen.FontScaleSettings)
                                },
                                onOpenThemeDebug = {
                                    controller.navigate(EInkScreen.ThemeDebug)
                                },
                                onOpenComponentGallery = {
                                    controller.navigate(EInkScreen.ComponentGallery)
                                },
                            )
                        }

                        is EInkScreen.Search -> {
                            SearchRoute(
                                onBack = { controller.pop() },
                                onBookClick = { book ->
                                    controller.navigate(
                                        EInkScreen.BookDetail(book.name, book.author, book.bookUrl)
                                    )
                                }
                            )
                        }

                        is EInkScreen.BookDetail -> {
                            BookDetailRoute(
                                name = screen.name,
                                author = screen.author,
                                bookUrl = screen.bookUrl,
                                onBack = { controller.pop() },
                                onOpenToc = { bookUrl ->
                                    controller.navigate(EInkScreen.Toc(bookUrl, fromReader = false))
                                },
                                onRead = { bookUrl ->
                                    if (screen.fromReader && bookUrl == screen.bookUrl) {
                                        // 自阅读页进入且书未重定向：仅弹出详情，
                                        // 复用下方既有阅读页，详情不留在返回栈
                                        controller.pop()
                                    } else {
                                        // 书架/搜索等路径：新进阅读页，详情保留在栈中
                                        controller.navigate(EInkScreen.Reader(bookUrl))
                                    }
                                },
                                // 换源成功后详情经 bookChanged 事件跟随刷新
                                onChangeSource = { bookUrl ->
                                    controller.navigate(EInkScreen.ChangeSource(bookUrl))
                                },
                            )
                        }

                        is EInkScreen.Toc -> {
                            TocRoute(
                                bookUrl = screen.bookUrl,
                                onBack = { controller.pop() },
                                onOpenReader = { bookUrl ->
                                    if (screen.fromReader) {
                                        // 复用下方既有阅读页：弹出目录即可，
                                        // 阅读页重新挂载时按新保存的进度跳章
                                        controller.pop()
                                    } else {
                                        // 详情页等路径：目录出栈、阅读页入栈，返回回到详情页
                                        controller.replaceTop(EInkScreen.Reader(bookUrl))
                                    }
                                },
                                // 书签跳转：引擎动作已由 TocRoute 完成，此处只做导航
                                //（书签 / 划线 / 想法同一条链路；fromReader 同
                                // onOpenReader 的复用语义）
                                onJumpToLocation = {
                                    if (screen.fromReader) {
                                        controller.pop()
                                    } else {
                                        controller.replaceTop(EInkScreen.Reader(screen.bookUrl))
                                    }
                                }
                            )
                        }

                        is EInkScreen.ChangeSource -> {
                            ChangeSourceRoute(
                                bookUrl = screen.bookUrl,
                                onBack = { controller.pop() },
                            )
                        }

                        is EInkScreen.ThemeDebug -> {
                            ThemeDebugRoute(onBack = { controller.pop() })
                        }

                        is EInkScreen.ComponentGallery -> {
                            ComponentGalleryRoute(onBack = { controller.pop() })
                        }

                        is EInkScreen.FontScaleSettings -> {
                            FontScaleSettingsRoute(onBack = { controller.pop() })
                        }

                        is EInkScreen.Reader -> Unit // 上方已处理
                    }
                }
            }
        }
    }

    // 更新弹层：根层渲染、覆盖任意屏幕（对齐宿主 Activity 级 UpdateDialog
    // 形态——直达最近阅读场景检查完成时阅读页之上也能弹）；状态由
    // Activity 级 VM 持有，「我的」页手动检查与启动自动检查共用。
    // 「立即更新」后弹框不切换形态，标题下方内嵌 4dp 细进度条；状态
    // 由确认钮承担（下载中禁用/失败变重试）——部分系统不给通知栏
    // 权限，通知进度不可见
    val appUpdateEngine = EInkEngineRegistry.appUpdateEngine
    val availableUpdate = updateViewModel.updateCheck as? UpdateCheckState.Available
    if (availableUpdate != null && appUpdateEngine != null) {
        val downloadingUpdate = updateViewModel.download as? UpdateDownloadState.Downloading
        val failed = downloadingUpdate?.progress?.state == AppDownloadState.FAILED
        val busy = downloadingUpdate != null && !failed
        EInkDialog(
            onDismiss = { updateViewModel.dismissUpdate() },
            title = "发现新版本 ${availableUpdate.info.versionName}",
            confirmText = when {
                failed -> "重试"
                busy -> "下载中"
                else -> "立即更新"
            },
            // 下载进行中无确认动作：禁用态承担「忙」提示，收起走「取消」
            onConfirm = if (busy) null else {
                { updateViewModel.startUpdateDownload(appUpdateEngine, availableUpdate.info) }
            },
            // 槽位常驻（未下载时为空轨道，兼作标题下分隔线，面板级
            // 插槽通到面板左右边缘）：下载开始仅小牌出现并沿线移动，
            // 正文不因进度行下跳
            belowTitle = {
                EInkAppUpdateProgressRow(
                    percent = downloadingUpdate?.progress?.percent ?: -1
                )
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

    // 云端新备份确认弹层：根层渲染、覆盖任意屏幕（宿主 backupSync 的
    // Activity 级 alert 同形态）；恢复只写 DB，书架流响应式自动刷新，
    // 不动导航与当前阅读会话
    val backupSyncEngine = EInkEngineRegistry.backupSyncEngine
    val backupPrompt = backupSyncViewModel.prompt
    if (backupPrompt != null && backupSyncEngine != null) {
        val busy = backupPrompt is BackupSyncPromptState.Restoring
        val failed = backupPrompt as? BackupSyncPromptState.Failed
        EInkDialog(
            onDismiss = { backupSyncViewModel.dismiss() },
            title = "发现云端新备份",
            confirmText = when {
                busy -> "恢复中…"
                failed != null -> "重试"
                else -> "恢复"
            },
            // 恢复中无确认动作：禁用态承担「忙」提示，收起走「取消」
            onConfirm = if (busy) null else {
                { backupSyncViewModel.confirmRestore(backupSyncEngine) }
            },
        ) {
            val info = when (backupPrompt) {
                is BackupSyncPromptState.Newer -> backupPrompt.info
                is BackupSyncPromptState.Restoring -> backupPrompt.info
                is BackupSyncPromptState.Failed -> backupPrompt.info
            }
            EInkText(
                text = buildString {
                    append("云端备份比本地新，是否恢复？\n")
                    append("设备：${info.deviceName.ifBlank { "未命名" }}  日期：${info.dateText}")
                    if (failed != null) append("\n恢复失败：${failed.reason}")
                },
                style = EInkTheme.typography.bodyMedium,
            )
        }
    }
}

/** 冷启动初始栈：默认仅书架；[自动跳转最近阅读]时书架之上叠阅读页。 */
internal fun initialStack(initialReaderBookUrl: String?): List<EInkScreen> =
    if (initialReaderBookUrl == null) {
        listOf(EInkScreen.Home)
    } else {
        listOf(EInkScreen.Home, EInkScreen.Reader(initialReaderBookUrl))
    }

/**
 * safeDrawing 顶部高度快照（只增不减）：非阅读界面顶部避让用。两个跳动
 * 源都由「跟随活值/从零起步」引发，一并消掉：
 * - 冷启动：第一轮组合早于插图分发到 Compose，快照从 0 起步会让书架先
 *   顶格、次帧随插图到位整体下移——初值改为同步种子，首帧即终值；
 * - 阅读返回：隐藏状态栏的阅读页退出时系统栏 show() 动画期活值从 0
 *   逐帧回升——只增不减的快照保持满栏高，一步落位。
 *
 * 种子按可用性顺位取值、不做跨源取大（真实插图在场即以其为准，跨源
 * 取大反而会把备忘陈旧值钉成永久偏大的避让）：根视图现实插图（≥23，
 * statusBars∪cutout 顶；阅读期栏隐藏时值偏小属预期）→ 框架
 * status_bar_height 内部 dimen（仅 API<23 无插图派发时，唯一同步来源，
 * 该分支恰是前插图文件系统栏高语义成立的范围，lint 有据抑制）→ 进程级
 * 备忘（[SafeDrawingTopMemo]，前两者皆不可用时的地板）。活值跟踪取
 * safeDrawing 顶，兼顾刘海高于状态栏的机型。快照挂 EInkApp 根跨屏幕
 * 切换存活：阅读期活值回落不冲掉快照。
 */
@Composable
private fun rememberSafeDrawingTopMax(): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    val seedDp = remember(view) {
        val insetsTopPx = ViewCompat.getRootWindowInsets(view)?.let { compat ->
            maxOf(
                compat.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                compat.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
            )
        }
        when {
            insetsTopPx != null -> with(density) { insetsTopPx.toDp().value }
            else -> {
                val legacyPx = legacyStatusBarHeightPx()
                if (legacyPx > 0) with(density) { legacyPx.toDp().value }
                else SafeDrawingTopMemo.topDp
            }
        }
    }
    val liveTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    var captured by remember { mutableStateOf(seedDp.dp) }
    SideEffect {
        if (liveTop > captured) captured = liveTop
        SafeDrawingTopMemo.topDp = maxOf(SafeDrawingTopMemo.topDp, liveTop.value)
    }
    return captured
}

/**
 * API < 23 无插图派发时的唯一同步栏高来源（框架内部 dimen，仅在该分支
 * 使用；现代 API 的取值风险由「不走上此分支」规避）。lint 两项抑制在此
 * 收敛，调用面保持干净。
 */
@Suppress("DiscouragedApi", "InternalInsetResource")
private fun legacyStatusBarHeightPx(): Int {
    val resources = Resources.getSystem()
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}

/** 进程级备忘：本进程见过的 safeDrawing 顶最大值（dp 值），种子地板。 */
private object SafeDrawingTopMemo {
    @Volatile var topDp: Float = 0f
}
