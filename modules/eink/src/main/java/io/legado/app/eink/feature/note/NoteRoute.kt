package io.legado.app.eink.feature.note

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.JumpResolution
import io.legado.app.eink.designsystem.navigation.EInkPageArrows
import io.legado.app.eink.designsystem.pager.rememberEInkListPagerState
import io.legado.app.eink.designsystem.refresh.EInkRefreshIntent
import io.legado.app.eink.designsystem.refresh.LocalEInkRefreshController
import kotlinx.coroutines.launch

/**
 * 笔记页 Route — ViewModel 感知层。
 *
 * 划线/想法混合列表为固定页分页（翻页按钮与上下滑动手势一致）；
 * 条目点击经 MarksEngine 解析跳转目标（直接/需确认/失败三分支），
 * 由本层执行引擎动作（有会话即时跳章 / 无会话落进度）后交
 * [onJumpToLocation] 做纯导航；导出经 SAF `CreateDocument` 取 uri
 * 交 ViewModel 写入。
 *
 * @param bookUrl 书籍唯一键
 * @param onJumpToLocation 引擎动作完成后的纯导航回调（宿主按进入链路
 *   pop 回阅读页或 replaceTop 进阅读页）
 */
@Composable
fun NoteRoute(
    bookUrl: String,
    onBack: () -> Unit,
    onJumpToLocation: (JumpResolution.Located) -> Unit = {},
    viewModel: NoteViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pager = rememberEInkListPagerState()
    val scope = rememberCoroutineScope()
    val displayCount = uiState.markings.size

    // 加载书籍与划线/想法列表
    LaunchedEffect(bookUrl) {
        viewModel.loadBook(bookUrl)
    }

    // 一次性消息 → Toast（组合期订阅：SharedFlow 无 replay，点击回调内
    // 订阅会丢事件；对齐 TocRoute/BookDetailRoute 先例）
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 跳转目标：有会话即时跳章；无会话（详情等路径进入）落进度含章内位置，
    // 导航后阅读页装载时落位；引擎动作完成后 [onJumpToLocation] 只做导航。
    // 笔记页无章节列表，落进度的章节标题传空串（该链路目录页在下层，
    // 宿主按 bookUrl 解析书籍）
    LaunchedEffect(viewModel, bookUrl, onJumpToLocation) {
        viewModel.jumpTarget.collect { target ->
            val reader = EInkEngineRegistry.readerEngine
            if (reader.sessionBookUrl == bookUrl) {
                reader.jumpToPosition(target.chapterIndex, target.chapterPos)
            } else {
                EInkEngineRegistry.tocEngine.saveReadingProgress(
                    bookUrl, target.chapterIndex, "", target.chapterPos,
                )
            }
            onJumpToLocation(target)
        }
    }

    // SAF 导出：pendingExport 标记"本次 launcher 结果由导出点击发起"，
    // 防 launcher 复用/Activity 重建结果重投递误触发；取消（uri == null）
    // 同样复位，不吞掉下一次真实导出
    var pendingExport by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null && pendingExport) {
            viewModel.exportMarkdown(bookUrl, uri.toString())
        }
        pendingExport = false
    }

    // 翻页动作 remember 稳定实例：下传后接收方（列表 / EInkPageSwipe）
    // 不因 lambda 逐次更换而被迫重组；翻页后上报 PageTurn 意图（规范 §26/§40）
    val refresh = LocalEInkRefreshController.current
    val pageUp: () -> Unit = remember(pager, refresh, scope) {
        {
            scope.launch { pager.pageUp() }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }
    val pageDown: () -> Unit = remember(pager, displayCount, refresh, scope) {
        {
            scope.launch { pager.pageDown(displayCount) }
            refresh.requestRefresh(EInkRefreshIntent.PageTurn)
        }
    }

    // 翻页箭头槽：canPageUp/canPageDown 读取分页状态（pageStart 为
    // mutableStateOf），收敛到槽内读取，翻页只重组箭头两个图标
    val pageArrows: @Composable () -> Unit = {
        EInkPageArrows(
            pageUpEnabled = pager.canPageUp(),
            pageDownEnabled = pager.canPageDown(displayCount),
            onPageUp = pageUp,
            onPageDown = pageDown,
        )
    }

    NoteScreen(
        state = uiState,
        listState = pager.listState,
        pageArrows = pageArrows,
        onBack = onBack,
        // 回到当前 = 定位到当前章（含）之后首条笔记（对齐 TocRoute 书签 Tab
        // 语义）；空列表无动作
        onBackToCurrent = {
            scope.launch {
                if (uiState.markings.isEmpty()) return@launch
                val index = uiState.markings
                    .indexOfFirst { it.chapterIndex >= uiState.currentChapterIndex }
                    .coerceAtLeast(0)
                pager.jumpToItemAligned(index)
            }
        },
        onPageUp = pageUp,
        onPageDown = pageDown,
        onMarkingClick = viewModel::onMarkingClick,
        onConfirmJump = viewModel::confirmPendingJump,
        onDismissJump = viewModel::dismissPendingJump,
        onExport = {
            pendingExport = true
            exportLauncher.launch("${uiState.bookName}-笔记.md")
        },
    )
}
