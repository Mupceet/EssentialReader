package io.legado.app.eink.feature.reader

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Paint
import android.os.SystemClock
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
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
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.reader.selection.FlipTrigger
import io.legado.app.eink.feature.reader.selection.ReaderMarkingAction
import io.legado.app.eink.feature.reader.selection.ReaderSelectionActionBar
import io.legado.app.eink.feature.reader.selection.ReaderSelectionOverlay
import io.legado.app.eink.feature.reader.selection.ReaderSelectionSegment
import io.legado.app.eink.feature.reader.selection.ReaderSelectionSession
import io.legado.app.eink.feature.reader.selection.ReaderTextHit
import io.legado.app.eink.feature.reader.selection.ReaderSelectionUi
import io.legado.app.eink.feature.reader.selection.ReaderTapDispatch
import io.legado.app.eink.feature.reader.selection.ReaderThoughtDialog
import io.legado.app.eink.feature.reader.selection.buildSelection
import io.legado.app.eink.feature.reader.selection.captureSegment
import io.legado.app.eink.feature.reader.selection.chapterPositionOf
import io.legado.app.eink.feature.reader.selection.draggingEndpointIsStart
import io.legado.app.eink.feature.reader.selection.findDecorationAt
import io.legado.app.eink.feature.reader.selection.flipCaptureRange
import io.legado.app.eink.feature.reader.selection.flipDirectionForDrag
import io.legado.app.eink.feature.reader.selection.flipEdgeHit
import io.legado.app.eink.feature.reader.selection.handleAnchors
import io.legado.app.eink.feature.reader.selection.hitTest
import io.legado.app.eink.feature.reader.selection.joinSegments
import io.legado.app.eink.feature.reader.selection.markingIdForSelection
import io.legado.app.eink.feature.reader.selection.markingThoughtFromNote
import io.legado.app.eink.feature.reader.selection.mergeSegment
import io.legado.app.eink.feature.reader.selection.moveEndpoint
import io.legado.app.eink.feature.reader.selection.offPageHandleIsStart
import io.legado.app.eink.feature.reader.selection.pendingPreviewAfterPageVersion
import io.legado.app.eink.feature.reader.selection.readerTapDispatch
import io.legado.app.eink.feature.reader.selection.selectionFromChapterRange
import io.legado.app.eink.feature.reader.selection.selectionActions
import io.legado.app.eink.feature.reader.selection.selectionOfDecoration
import io.legado.app.eink.feature.reader.selection.selectionRuns
import io.legado.app.eink.feature.reader.selection.snapToWordRange
import kotlinx.coroutines.launch

/** 排版设置的居中弹层形态。 */
private enum class ReaderStyleDialog { Fonts, Info, Margin }

/**
 * 点按标记浮条状态（v2 Task 6）：命中 markingId + run 派生选区——浮条
 * 锚定（selectionRuns 行内区间 → x 复用）与 COPY/DELETE 动作、定位键共用
 * [selection]；[commitSelection] 为「写想法」提交专用（原文覆写版，见
 * [onMarkingTap]），防止跨行标记按行内截段落库时 upsert 不命中原记录。
 */
@Stable
private data class ReaderMarkingBar(
    val markingId: String,
    val selection: ReaderSelectionUi,
    val commitSelection: ReaderSelectionUi,
)

/**
 * 想法弹框草稿（v2.2 统一）：两个入口（选区操作条 / 点按标记操作条）共用
 * **同一个弹框**，唯一差别是 [note] 预填——已有想法带出宿主记录的笔记内容，
 * 划线/新选区为空串；[selection] 是提交选区（点按入口用完整原文覆写行内
 * 截段，见 [onMarkingTap]，防跨行 upsert 不命中）。
 *
 * 保存时按内容判类型：note 非空 = 想法（虚线），清空 = 划线（实线）——
 * 与「划线 = 实线 + note 空；想法 = 虚线 + note 非空」的类型语言一致。
 */
@Stable
private data class ReaderThoughtDraft(
    val note: String,
    val selection: ReaderSelectionUi,
)

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
 * - 页内长按选区状态在此持有：翻页/重排（pageVersion 推进）自动清空。
 *   v2.1 点击式（设计 §3.5/§4）：长按选词 → 灰底选中带 + pin 把手；松手
 *   **不落库**，弹选区操作条（复制/想法/画线，选区落在已有标记上时第三键
 *   换删除）；画线/想法由用户明确选择后才落库，落库期间选中带只读续显到
 *   装饰到位（避免「线先消失、再出现」）。v2.2 点按已有标记（设计 §4
 *   「点已有标记」）：hitTest 命中装饰 run → findMarking → **划线与想法
 *   一律先弹同一操作条**，想法键再开编辑更新弹框（预填 note + 复制/删除）
 *   ——两个入口（选区落在标记上 / 点按标记）的弹框形态与内容完全一致；
 *   标记失效静默回落分区行为；
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
    // 跨页续选会话（v2 Task 8，设计 §3.4/§4）：null = 无会话（单页直选
    // 行为不变）。首次翻页请求时以当前选区极值建会话；松手提交或清区置
    // null。会话期间 selection 语义变为「会话区间 ∩ 当前页」的页内视觉，
    // 累计真值在会话状态里
    var selectionSession by remember { mutableStateOf<ReaderSelectionSession?>(null) }
    // 落库冻结：用户在操作条上选「画线/想法」后发起落库即置 true——把手停用
    // （调界停用），选中带只读续显到正式装饰真的出现在页上（见
    // pendingPreviewAfterPageVersion），避免「线先消失、再出现」的闪断。
    // 页变清态与选区清空（点外）一并复位，下一轮选区从可调界态开始
    var selectionFrozen by remember { mutableStateOf(false) }
    // 落库待确认的提交选区：非空 = 一次画线/想法仍在「等装饰」窗口内——
    // 宿主重排推进 pageVersion 时不清选中带，直到新快照带上该标记
    // （markingRenderedForRange）；装饰呈现/落库失败/区间离页/点按清区即复位
    var committedSelection by remember { mutableStateOf<ReaderSelectionUi?>(null) }
    // v2 想法弹层（v2.2 统一）：thoughtDraft 非空即弹层打开——两个入口
    // （选区操作条「想法」/ 点按标记操作条「想法」）共用同一弹框，唯一差别
    // 是预填的笔记内容；确认提交用其 selection，预览取其 selectedText，
    // 不经端口解析（设计 §5）
    var thoughtDraft by remember { mutableStateOf<ReaderThoughtDraft?>(null) }
    // v2 点按标记浮条（Task 6，设计 §4「点已有标记」）：非空 = 点按命中已有
    // 划线（thought=false），浮条锚定命中 run 几何（无选区/把手/下划线预览），
    // markingId 供删除键即时可用（松手场景无 id 的时序此处不存在）
    var markingBar by remember { mutableStateOf<ReaderMarkingBar?>(null) }
    // pageVersion 推进 = 选区所在内容已被替换：选区、冻结态、想法弹层与
    // 点按浮条/浮窗一并清空，浮层随内容变化关闭，不残留幽灵浮层（在途
    // 提交按各自现读现判护栏落失效/失败分支）。松手落划线后的宿主重排
    // （saveMarking → relayout）受下方待确认门控：仅当新快照真的带上了
    // 该标记（正式装饰在位）预览才退场，否则按提交区间重锚续显。
    // 续选会话挂起（v2 Task 8，设计 §4）：会话进行中（session != null）
    // 翻页推进 pageVersion 不清选区，而是把**被拖端点**吸附到新页翻页边
    // （向后翻 = 起始把手吸附新页末正文行末字符、向前翻 = 结束把手吸附新页
    // 首正文行首字符）。注意这是**赋值**不是取极值：手指随后往回收，区间
    // 跟着收——旧实现的 min/max 单向累计正是「一跨页就飞、且收不回来」的
    // 根因。会话结束置 null 后本效应恢复清态语义。uiState.page 与
    // pageVersion 同批更新，效应执行时已持新快照。跳章/自动翻页等非续选
    // 页变在会话中同样走挂起分支，不做额外处理（风险已记录设计 §9）
    LaunchedEffect(uiState.pageVersion) {
        val sessionNow = selectionSession
        if (sessionNow != null) {
            uiState.page?.let { page ->
                // 吸附边按**本次翻页方向**定（与来页的接缝那一侧）：下翻吸新页首行
                // 首字符、上翻吸新页末行末字符。注意与被拖端无关——反向续拖时手指
                // 拖的还是原来那一端，接缝边才是选区该接着长的方向
                val edgeHit = flipEdgeHit(page, handleIsStart = !sessionNow.lastFlipForward)
                val edgePos = edgeHit?.let { hit ->
                    page.lines.getOrNull(hit.lineIndex)
                        ?.let { line -> chapterPositionOf(line, hit.charIndex) }
                }
                val moved = if (edgePos != null) sessionNow.moveDragged(edgePos) else sessionNow
                selectionSession = moved
                selection = if (captureSegment(page, moved.startPos, moved.endPos) != null) {
                    selectionFromChapterRange(page, moved.startPos, moved.endPos) ?: selection
                } else {
                    // 翻页边零宽锚点（会话区间与页正文暂无交集的过渡态）：
                    // 把手吸附新页翻页边，后续 move 自然展开「翻页边 → 手指
                    // 位置」。不走 selectionFromChapterRange 的覆盖全页兜底
                    edgeHit?.let { buildSelection(page, it, it) } ?: selection
                }
            }
            return@LaunchedEffect
        }
        // 待确认落库的划线预览：装饰已在新页呈现才退场；宿主重排链路可能先推
        // 一帧还没并入标记的页（内容缓存/异步重排时序），此时按提交区间重锚
        // 续显预览——预览与正式装饰同形，交接处不产生视觉空档（见策略 KDoc）
        pendingPreviewAfterPageVersion(uiState.page, committedSelection)?.let { kept ->
            selection = kept
            return@LaunchedEffect
        }
        selection = null
        selectionFrozen = false
        committedSelection = null
        thoughtDraft = null
        markingBar = null
    }
    val scope = rememberCoroutineScope()

    // 剪贴板（点按浮条「复制」/想法浮窗「复制」共用）
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()

    // 选区状态清场（复制/删除/落库收尾/点选区外共用）
    fun clearSelectionState() {
        selection = null
        selectionFrozen = false
        committedSelection = null
        selectionSession = null
    }

    // 复制（选区操作条与点按标记操作条共用）
    fun copyText(text: String) {
        clipboardScope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text)))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    // v2.1 点击式交互（设计 §4）：松手只弹操作条，落库/删除/复制都由用户在
    // 操作条上明确选择后执行——不再「松手即存」。画线/想法落库发起即冻结
    // 把手并登记待确认提交 committedSelection：选中带只读续显，直到新快照
    // 真的带上该装饰（页变效应按 pendingPreviewAfterPageVersion 判在位），
    // 期间不闪断；落库失败 toast「保存失败」并清态（选中带不能留着冒充已
    // 落库）。两类不请求落库：端口未注册（降级宿主整体不启用）、含标题选区
    // （§3.4 静默忽略，操作条只留复制）。
    fun commitLine(target: ReaderSelectionUi) {
        selectionFrozen = true
        committedSelection = target
        scope.launch {
            if (!viewModel.saveMarking(target, note = "", thought = false)) {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                clearSelectionState()
            }
        }
    }

    // 选区操作条动作分派：动作集由 selectionActions 决定（新区间 复制/画线/
    // 想法；已有标记 复制/想法/删除），这里按动作执行副作用。点击「想法」在
    // 已有想法时进入编辑（预填 findMarking 的 note），已有划线时转为想法；
    // 提交文本一律用标记完整原文，防跨行标记按行内截段落库 upsert 不命中。
    val onSelectionAction: (ReaderMarkingAction) -> Unit = { action ->
        val target = selection
        if (target == null) {
            // 现读现判：动作发起时选区已被并发清空（点选区外/页变）则静默丢弃
        } else {
            val markingId = uiState.page?.let { markingIdForSelection(it, target) }
            when (action) {
                ReaderMarkingAction.COPY -> {
                    copyText(target.selectedText)
                    clearSelectionState()
                }

                ReaderMarkingAction.LINE -> {
                    // 已有标记的区间不出现该键（动作集保证），防御性忽略
                    if (markingId == null) commitLine(target)
                }

                ReaderMarkingAction.THOUGHT -> {
                    if (markingId == null) {
                        thoughtDraft = ReaderThoughtDraft(note = "", selection = target)
                    } else {
                        scope.launch {
                            when (val detail = viewModel.findMarking(markingId)) {
                                // 标记失效（换源清理/无会话）：清态，不弹层
                                null -> clearSelectionState()
                                // 已有想法带出笔记内容、已有划线为空——弹框同一份，
                                // 提交选区用标记完整原文覆写行内截段（见 ReaderThoughtDraft）
                                else -> thoughtDraft = ReaderThoughtDraft(
                                    note = detail.note,
                                    selection = target.copy(selectedText = detail.selectedText),
                                )
                            }
                        }
                    }
                }

                ReaderMarkingAction.DELETE -> scope.launch {
                    when {
                        markingId == null -> clearSelectionState()
                        viewModel.deleteMarking(markingId) -> clearSelectionState()
                        else -> Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 松手/抬手统一入口（v2.1）：只合成跨页会话的最终选区并结束会话，**不落库**
    // ——落库由用户在操作条上选「画线/想法」触发。合成 = joinSegments（已捕获
    // 页段 + 末页 capture，按 startPos 排序、gap>0 补换行）+ 会话区间极值。
    // includesTitle 如实传递会话建立时记录值（操作条据此只留复制键）。
    val finalizeSelection: () -> Unit = {
        val sessionNow = selectionSession
        val page = uiState.page
        val current = selection
        when {
            sessionNow != null && page != null && current != null -> {
                val lastSegment = captureSegment(page, sessionNow.startPos, sessionNow.endPos)
                // 末页 capture 经重叠覆盖合并：会话内翻回已累计页再松手时，
                // 该页已有翻页时刻捕获，直接追加会被 joinSegments 重复拼接
                val all = lastSegment?.let { mergeSegment(sessionNow.segments, it) }
                    ?: sessionNow.segments
                val final = if (all.isEmpty()) {
                    current
                } else {
                    current.copy(
                        selectedText = joinSegments(all),
                        bodyStart = sessionNow.startPos,
                        bodyEnd = sessionNow.endPos,
                        includesTitle = sessionNow.includesTitle,
                    )
                }
                selectionSession = null
                // 翻页零宽过渡期的空选区静默丢弃（无文本可复制/可标记）
                selection = final.takeIf { it.selectedText.isNotBlank() }
            }

            else -> selectionSession = null
        }
    }

    // 翻页请求（v2 Task 8，设计 §4 页顶/页底按住翻页）：方向 -1 上一页 /
    // +1 下一页。翻页成功才建立/推进会话——无页可翻维持现状（失败的翻页
    // 不得把当前选区会话化，普通选区原样保留）。成功时先按 flipCaptureRange
    // 捕获离页整段（被拖侧端 = 会话边界、对侧端 = 离开页正文边——被选部分
    // 直到页边，提交范围与 selectedText 严格一致）经 mergeSegment 入会话，
    // 再翻页；翻页推进 pageVersion 后由上方页变效应吸附翻页边（会话挂起使
    // 选区不被清）。会话不存在时以当前选区极值建会话（首个翻页请求即会话
    // 起点），includesTitle 按当时选区如实带入（capture 只收正文，会话期间
    // 标题不可达，见 commitSelection 的标题门禁）
    val onFlipRequest: (Int) -> Unit = { direction ->
        val page = uiState.page
        val visual = selection
        if (page != null && visual != null) {
            val moved = if (direction < 0) viewModel.prevPage() else viewModel.nextPage()
            if (moved) {
                val base = selectionSession
                    ?: ReaderSelectionSession.from(visual, draggingEnd = direction > 0)
                // 只记录翻页方向（决定页变后被拖端吸附哪条边），不改被拖端——
                // 手指拖的始终是同一端，翻页改侧会让固定端漂到远端（见 withFlip）
                val session = base.withFlip(forward = direction > 0)
                val range = flipCaptureRange(page, direction, session.startPos, session.endPos)
                val segment = range?.let { captureSegment(page, it.first, it.second) }
                selectionSession = session.withSegments(
                    segment?.let { mergeSegment(session.segments, it) } ?: session.segments,
                )
            }
        }
    }

    // 点按标记操作条动作（v2 Task 6，设计 §4「点已有标记」）：与选区操作条
    // 同组件同语义（复制/想法/删除）。复制 = 命中 run 文本落剪贴板 + toast +
    // 收操作条；想法 = 想法弹层（确认后 saveMarking(thought=true) 同锚点
    // upsert，划线转想法，虚线随宿主重排新快照呈现）；删除 = deleteMarking
    // 即时可用（markingId 现成），成功收操作条、失败 toast 保留现场
    val onMarkingAction: (ReaderMarkingAction) -> Unit = { action ->
        // 现读操作条状态：动作发起时已被页变/新点按清空则静默丢弃
        markingBar?.let { bar ->
            when (action) {
                ReaderMarkingAction.COPY -> {
                    copyText(bar.selection.selectedText)
                    markingBar = null
                }

                // 想法：一律开同一弹框，只有预填内容不同（已有想法带出笔记，
                // 划线为空 = 划线转想法）。与选区操作条完全一致
                ReaderMarkingAction.THOUGHT -> scope.launch {
                    // 现读现判：动作发起后操作条已被并发清空则静默丢弃
                    val target = markingBar
                    if (target == null) return@launch
                    val detail = viewModel.findMarking(target.markingId)
                    when {
                        // 标记失效（换源清理）：收条即可，不弹层
                        detail == null -> markingBar = null
                        else -> thoughtDraft = ReaderThoughtDraft(
                            note = detail.note,
                            selection = target.commitSelection,
                        )
                    }
                }

                ReaderMarkingAction.DELETE -> {
                    scope.launch {
                        // 现读现判：删除发起时浮条已被并发清空则落失效分支
                        val target = markingBar
                        when {
                            target == null -> Unit
                            viewModel.deleteMarking(target.markingId) -> markingBar = null
                            else -> Toast.makeText(
                                context, "删除失败", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                // 已有标记的区间不出现该键（动作集保证），防御性忽略
                ReaderMarkingAction.LINE -> Unit
            }
        }
    }

    // 点按命中标记（v2 Task 6，v2.2 修订）：Screen 完成几何命中（hitTest +
    // 装饰 run + markingId 非空过滤）后上抛，**划线与想法一律先弹操作条**
    // （复制/想法/删除）——想法不再直接弹编辑框：用户点「想法」才出现编辑
    // 更新弹框，与「选区已落在标记上 → 操作条 → 想法」的入口完全一致。
    // 标记失效（换源清理/无会话/端口缺失）→ 静默回落分区行为（[onMiss]）。
    // 发起时刻页版本作护栏：findMarking 在途期间页已翻/重排则 run 行内几何
    // 过期，静默丢弃（浮条锚定旧页坐标会锚错位置）
    val onMarkingTap: (ReaderSelectionUi, String, () -> Unit) -> Unit = { tapped, markingId, onMiss ->
        val versionAtTap = uiState.pageVersion
        scope.launch {
            val detail = viewModel.findMarking(markingId)
            if (uiState.pageVersion != versionAtTap) return@launch
            if (detail == null) {
                // 标记失效：不弹操作条，回落分区行为（可能翻页/唤菜单）
                markingBar = null
                onMiss()
                return@launch
            }
            markingBar = ReaderMarkingBar(
                markingId = markingId,
                selection = tapped,
                // 提交选区以标记完整原文（findMarking detail，可跨行）覆写 run
                // 行内截段：宿主 saveMarking 以 selectedText 在章节全文定位并按
                // （chapterPosition, selectedText）同锚点 upsert，按截段提交会另
                // 锚一条新记录；COPY/锚定仍用行内截段，start/end/bodyStart/bodyEnd
                // 只是宿主窗口搜索的提示位（点按位置在标记内 → 命中原完整区间）
                commitSelection = tapped.copy(selectedText = detail.selectedText),
            )
        }
    }

    // 页面书签 toggle（v2 Task 9，设计 §4）：顶栏书签钮与阅读区竖直下拉
    // 共用；端口在位才可达（降级宿主两者皆不渲染/不响应，见 ReaderScreen）。
    // 三态：null = 端口缺失/无页/无会话书 → toast「操作失败」；true = 本次
    // 添加、false = 本次移除，均静默——角标变化随宿主重排的新快照即反馈
    val onPageBookmarkToggle: () -> Unit = {
        scope.launch {
            if (viewModel.togglePageBookmark() == null) {
                Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show()
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
            // 离开阅读页的三个出口（目录 / 换源 / 详情）先收起操作条再导航：
            // 回来时本就是干净阅读状态，不会在回程第一帧才收起而闪一下
            //（真机反馈"回阅读界面时才开始消失"）
            onOpenToc = {
                viewModel.hideControls()
                onOpenToc(uiState.bookUrl.ifEmpty { bookUrl })
            },
            onChangeSource = {
                viewModel.hideControls()
                onChangeSource(uiState.bookUrl.ifEmpty { bookUrl })
            },
            onOpenDetail = {
                // 换源后以引擎当前持有的书为准（bookUrl 与路由参数可能不同）
                if (uiState.bookUrl.isNotEmpty()) {
                    viewModel.hideControls()
                    onOpenDetail(uiState.bookName, uiState.bookAuthor, uiState.bookUrl)
                }
            },
            onRefresh = viewModel::refreshChapter,
            onOpenCachePanel = { panel = ReaderPanel.CACHE },
            onAddToBookshelf = viewModel::addToBookshelf,
            onRemoveFromBookshelf = { showRemoveConfirm = true },
            onTogglePageBookmark = onPageBookmarkToggle,
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
            session = selectionSession,
            selectionEnabled = viewModel.selectionEnabled,
            // 落库冻结后把手停用（调界停用），选中带只读保留到重排的新快照
            handlesEnabled = !selectionFrozen,
            // 选区是否已落在用户标记上（动作集分派：已有标记 → 复制/想法/删除）
            selectionHasMarking = uiState.page?.let { page ->
                selection?.let { markingIdForSelection(page, it) != null }
            } == true,
            // 选区操作条可见性：选区在场 + 未落库冻结（动作已完成）+ 无弹层
            // + 非续选会话（拖拽中）——拖手柄/长按拖拽期由 Screen 另行隐藏
            selectionBarVisible = selection != null && !selectionFrozen &&
                committedSelection == null && selectionSession == null &&
                thoughtDraft == null,
            // 点按标记浮条（Task 6）：无选区时锚定命中 run 几何展示三键
            markingSelection = markingBar?.selection,
            // 操作条可见性：想法弹层打开期间收条（取消后回来，两入口一致）
            markingBarVisible = markingBar != null && thoughtDraft == null,
            onSelectionChange = { sel ->
                when {
                    // 会话进行中：视觉由 onDraggedHit 按章内区间推导，页内合成
                    // 结果不参与（会话真值是章内两端点，不再是页内命中）
                    selectionSession != null && sel != null -> Unit
                    sel != null -> {
                        selection = sel
                        // 新选区建立（长按选词）：点按标记浮条随行关闭，
                        // 避免选区预览与标记浮条双浮层叠加
                        markingBar = null
                    }
                    else -> {
                        // 清区路径（点外/点按浮条外）随行复位冻结与待确认提交，
                        // 下一轮选区从可调界态开始；会话防御性终止（挂起恢复）
                        selection = null
                        selectionFrozen = false
                        committedSelection = null
                        selectionSession = null
                    }
                }
            },
            // 拖拽期手指的原始命中（v2 Task 8 会话真值）：被拖端点 = 命中所在
            // 行的章内位置，直接赋值给会话（可增可减）——跨页后手指往回收，
            // 区间跟着收；旧实现按 min/max 累计，正是「一跨页就飞、收不回来」
            // 的根因。命中行失效/落在标题行（标题空间无正文语义）时忽略本次
            // 移动，维持原视觉
            onDraggedHit = { hit ->
                val sessionNow = selectionSession
                val page = uiState.page
                val line = page?.lines?.getOrNull(hit.lineIndex)
                if (sessionNow != null && page != null && line != null && !line.isTitle) {
                    val movedSession = sessionNow.moveDragged(
                        chapterPositionOf(line, hit.charIndex),
                    )
                    selectionSession = movedSession
                    selection = if (
                        captureSegment(page, movedSession.startPos, movedSession.endPos) != null
                    ) {
                        selectionFromChapterRange(
                            page,
                            movedSession.startPos,
                            movedSession.endPos,
                        ) ?: selection
                    } else {
                        // 会话区间与页正文暂无交集（翻页刷新窗口的过渡态）：
                        // 维持原视觉（覆盖全页兜底会污染下一次端点归属）
                        selection
                    }
                }
            },
            // 松手/抬手统一入口（v2.1）：只合成会话选区，不落库
            onSelectionFinalized = finalizeSelection,
            // 选区操作条动作（复制/画线/想法/删除）——动作集由 selectionActions
            // 按「是否已有标记 / 是否可标记」分派，见 Screen
            onSelectionAction = onSelectionAction,
            // 跨页续选会话（v2 Task 8）：页顶/页底按住翻页请求
            onFlipRequest = onFlipRequest,
            onMarkingTap = onMarkingTap,
            // 点按标记操作条外：只收操作条（吞掉本次点按，见 ReaderTapDispatch）
            onMarkingBarDismiss = { markingBar = null },
            onMarkingAction = onMarkingAction,
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

        // 想法弹层（v2.2 统一，设计 §5）：选区操作条「想法」与点按标记操作条
        // 「想法」共用**同一个弹框**——顶部大弹框 + 自动拉起输入法（见
        // ReaderThoughtDialog），唯一差别是预填的笔记内容（已有想法带出内容，
        // 划线/新选区为空）。确认经端口落库，**类型按内容判定**：note 非空 =
        // 想法（虚线），清空 = 划线（实线）——想法清空内容即自动变回划线。
        // 护栏沿用 v1：
        // - pageVersion 推进（落库重排/翻页/点按重绘）连同清空
        //   thoughtDraft，内容变化即关弹层，不残留幽灵弹层；
        // - 确认时 thoughtDraft 现读现判，落库前弹层选区已被并发清空
        //   则落「选区已失效」分支；
        // - saving 防重入：慢速墨水屏确认回显延迟期间的双击只落库一次。
        // 成功关弹层并登记待确认提交（与画线同一收尾）：选中带只读续显到新
        // 快照真的带上装饰（pageVersion 效应按 pendingPreviewAfterPageVersion
        // 判在位），再清态；失败 toast「保存失败」弹层保留可重试。
        var saving by remember { mutableStateOf(false) }
        thoughtDraft?.let { draft ->
            ReaderThoughtDialog(
                thoughtText = draft.note,
                selectedText = draft.selection.selectedText,
                // 顶部大弹框避让状态栏（快照，不随状态栏回归动画抖动）
                topInset = statusBarTop,
                onDismiss = { thoughtDraft = null },
                onConfirm = confirm@{ note ->
                    if (saving) return@confirm
                    saving = true
                    scope.launch {
                        try {
                            // 现读现判：确认发起后选区快照已被页变/点按清空
                            // 则落失效分支（thoughtDraft 非空时即弹层快照）
                            val target = thoughtDraft
                            when {
                                target == null -> Toast.makeText(
                                    context, "选区已失效", Toast.LENGTH_SHORT
                                ).show()

                                // 内容清空即划线（实线）：类型由内容决定
                                viewModel.saveMarking(
                                    target.selection,
                                    note,
                                    thought = markingThoughtFromNote(note),
                                ) -> {
                                    thoughtDraft = null
                                    // 操作条不回来：动作已完成（装饰随重排呈现）
                                    markingBar = null
                                    selectionFrozen = true
                                    committedSelection = target.selection
                                }

                                else -> Toast.makeText(
                                    context, "保存失败", Toast.LENGTH_SHORT
                                ).show()
                            }
                        } finally {
                            saving = false
                        }
                    }
                },
            )
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
 * - 操作条隐藏时：点中间 40% 唤出操作条，点其余区域下一页；无选区点按
 *   命中已有标记（行内装饰 run 且 markingId 非空——空串为宿主高亮规则
 *   等非用户标记来源，视为未命中）改为上抛 [onMarkingTap]（划线/想法 →
 *   同一操作条，想法键再开编辑更新弹框；标记失效静默回落分区行为；
 *   [selectionEnabled] = false
 *   降级宿主不启用）；浮层在场时点按只收它们并吞掉本次点按（选区经
 *   [onSelectionChange] 置空清区、点按标记操作条经 [onMarkingBarDismiss] 收取），
 *   不翻页、不唤操作条，也不查标记（见 readerTapDispatch）；
 * - 长按正文选词（震动反馈；[selectionEnabled] = false 端口缺失时整体
 *   不启用——契约 §3.3 降级语义，不选词不触觉），拖拽延伸选区末端：
 *   拖动期正文铺灰底选中带 + 两侧 pin 把手（高度 = 行高），长按手势内松手
 *   （含拖拽延伸）经 [onSelectionFinalized] 只合成选区、**不落库**，改由
 *   选区操作条（[selectionBarVisible]：新区间 复制/画线/想法；选区已落在
 *   用户标记上 复制/想法/删除）执行副作用——落库/删除都发生在用户明确选择
 *   之后，键位不存在落库时序导致的禁用态。选区存在期间点按只清选区、水平
 *   滑动不翻页；把手拖拽调整端点（抓取即经 [onHandleDragStart] 收操作条、
 *   松手回来），端点可越过对方（区间归一、把手换边）；跨页续选
 *   （v2 Task 8，设计
 *   §3.4/§4）：端点拖入页顶/页底触发带（首/末行行盒）按住超长按时值经
 *   [onFlipRequest] 上抛翻页方向，会话内累计选区、松手合成完整章内区间
 *   （会话合成在 Route 侧）；[markingSelection] 非空时（无选区）点按标记
 *   操作条以命中 run 几何锚定展示三键，动作经 [onMarkingAction] 上抛
 *   （删除即时可用），点操作条外经 [onMarkingBarDismiss] 收取；
 * - 水平滑动翻页，判定对齐 View 版：触发距离读引擎 pageTouchSlop（AppConfig.pageTouchSlop 经端口）
 *   （完整版设置"翻页触发距离"，0 = 系统 slop，Compose 版只读不设），
 *   松手前反向回拖取消；无跟手移动，翻页整页立即替换。
 *
 * 选区状态由调用方持有（[selection] / [onSelectionChange]），翻页/重排
 * （pageVersion 推进）清空也由调用方承担；松手/抬手合成经
 * [onSelectionFinalized] 上抛，选区操作条动作经 [onSelectionAction] 上抛，
 * 点按标记操作条动作经 [onMarkingAction] 上抛。
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
    onTogglePageBookmark: () -> Unit,
    selectedPanel: ReaderPanel?,
    onOpenPanel: (ReaderPanel) -> Unit,
    onRetry: () -> Unit,
    selection: ReaderSelectionUi?,
    session: ReaderSelectionSession?,
    selectionEnabled: Boolean,
    handlesEnabled: Boolean,
    selectionHasMarking: Boolean,
    selectionBarVisible: Boolean,
    markingSelection: ReaderSelectionUi?,
    markingBarVisible: Boolean,
    onSelectionChange: (ReaderSelectionUi?) -> Unit,
    onDraggedHit: (ReaderTextHit) -> Unit,
    onSelectionFinalized: () -> Unit,
    onSelectionAction: (ReaderMarkingAction) -> Unit,
    onFlipRequest: (Int) -> Unit,
    onMarkingTap: (selection: ReaderSelectionUi, markingId: String, onMiss: () -> Unit) -> Unit,
    onMarkingBarDismiss: () -> Unit,
    onMarkingAction: (ReaderMarkingAction) -> Unit,
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
        // 续选会话同样是跨手势存活的实时值：不包 State 会在手势开始时被捕获成
        // null 并一直用，翻页判据退化成"只看把手侧"——真机表现为"手柄能往回翻，
        // 同一个手势里（不抬手）翻不回去"
        val currentSession by rememberUpdatedState(session)
        // controlsVisible/error 同理：长按检测器不以之为 key——键在手势中途
        // 翻转会重启检测器，onDragEnd/onDragCancel 均不执行，松手落划线丢失
        // 而选区留在屏上（僵死选区）；长按守卫改读 State 实时值
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
        // 测量闭包经 State 实时读：长按检测器不再以 pageVersion 为 key
        // （续选会话翻页推进 pageVersion，重启会打断在途拖拽），翻页后
        // 端点吸附/命中须用新快照的同规格测量
        val currentMeasureContent by rememberUpdatedState(measureContent)
        // 选中手势进行中（长按拖拽延伸 / 把手调界）：操作条离场，抬手回来
        // （v2.1 §4「修改时弹框隐藏，抬起回到操作条」）
        var selectionDragActive by remember { mutableStateOf(false) }
        // 画布实测宽：操作条 x 钳制不越界
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
                .pointerInput(
                    state.controlsVisible,
                    selection != null,
                    markingSelection != null,
                ) {
                        detectTapGestures { offset ->
                            when (
                                readerTapDispatch(
                                    controlsVisible = state.controlsVisible,
                                    hasSelection = selection != null,
                                    hasMarkingBar = markingSelection != null,
                                )
                            ) {
                                // 收起操作条
                                ReaderTapDispatch.COLLAPSE_CONTROLS -> {
                                    onCenterTap()
                                    return@detectTapGestures
                                }
                                // 浮条态（选区浮条）：点浮条外只收浮条清选区，
                                // 吞掉本次点按——不翻页、不唤菜单（对齐完整模式
                                // ReaderCanvasSurface：选区外的按下 suppressTap）。
                                // 穿透会把「收浮条」误读成翻页/开菜单，且操作条
                                // 展开期浮条本就不组合，收起后浮条状态复现成
                                // 幽灵浮条（见 ReaderTapDispatch KDoc）
                                ReaderTapDispatch.DISMISS_SELECTION -> {
                                    onSelectionChange(null)
                                    return@detectTapGestures
                                }
                                // 浮条态（点按标记浮条）：同上只收浮条，不露出
                                // 下层分区行为
                                ReaderTapDispatch.DISMISS_MARKING_BAR -> {
                                    onMarkingBarDismiss()
                                    return@detectTapGestures
                                }
                                ReaderTapDispatch.ZONE_OR_MARKING_HIT -> Unit
                            }
                            // 分区行为：中央呼菜单/侧边翻页——标记未命中与
                            // 标记失效静默回落时共用
                            val zoneBehavior = {
                                val width = size.width
                                if (offset.x in width * 0.3f..width * 0.7f) {
                                    onCenterTap()
                                } else {
                                    onNextPage()
                                }
                            }
                            // 无选区点按（v2 Task 6，设计 §4「点已有标记」）：
                            // 命中已有标记（装饰 run 覆盖命中字符且 markingId
                            // 非空——空串为宿主高亮规则等非用户标记来源，视为
                            // 未命中）→ 上抛查详情分浮条/浮窗；未命中或降级
                            // 宿主（选择交互整体不启用）→ 原分区行为
                            val page = currentPage
                            if (selectionEnabled && page != null) {
                                val hit = hitTest(page, offset.x, offset.y, measureContent)
                                if (hit != null) {
                                    val run = page.lines.getOrNull(hit.lineIndex)
                                        ?.let { line -> findDecorationAt(line, hit.charIndex) }
                                    if (run != null && run.markingId.isNotEmpty()) {
                                        selectionOfDecoration(page, hit.lineIndex, run)
                                            ?.let { marking ->
                                                onMarkingTap(marking, run.markingId, zoneBehavior)
                                                return@detectTapGestures
                                            }
                                    }
                                }
                            }
                            zoneBehavior()
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
                    .pointerInput(selectionEnabled) {
                        // 竖直下拉书签（v2 Task 9，设计 §4「阅读区竖直下拉」）：
                        // 累计下拉 ≥ 80dp 且无选区、操作条收起、无排版错误、
                        // 批注端口在位时 toggle 当前页书签一次；一次手势至多
                        // 触发一次，结束/取消复位。与既有手势共存：横/竖触摸
                        // slop 判定互斥（横向翻页检测器赢走的拖动会取消本检测
                        // 器，反之亦然）；长按选择在位移超 slop 时即被取消，把
                        // 手拖拽属选区场景（选区非空恒不触发）。端口缺失（降级
                        // 宿主）恒不触发（no-op）。检测器不以 selection/controls
                        // 为 key（拖拽中途翻转状态不重启手势），实时值经
                        // rememberUpdatedState 读取
                        val triggerThreshold = 80.dp.toPx()
                        var dragAccum = 0f
                        var triggered = false
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragAccum = 0f
                                triggered = false
                            },
                            onDragEnd = {
                                dragAccum = 0f
                                triggered = false
                            },
                            onDragCancel = {
                                dragAccum = 0f
                                triggered = false
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            if (triggered) return@detectVerticalDragGestures
                            dragAccum += dragAmount
                            if (dragAccum >= triggerThreshold && selectionEnabled &&
                                !currentControlsVisible && currentError == null &&
                                currentSelection == null
                            ) {
                                triggered = true
                                onTogglePageBookmark()
                            }
                        }
                    }
                    .pointerInput(selectionEnabled) {
                        // 长按选词 + 拖拽延伸：键不含 selection/controlsVisible/
                        // pageVersion——长按选词即改写选区状态、控件开合也可发生在
                        // 手势中途、续选会话翻页推进 pageVersion（v2 Task 8），以之
                        // 为 key 会在拖拽中途重启打断手势（onDragEnd/onDragCancel
                        // 不再执行，选区僵死），实时值经 rememberUpdatedState 读取。
                        // 仅「本次长按新建选区」的拖拽延伸末端（startHit 固定）；
                        // 已有选区时长按不重建，端点调整只经把手拖拽
                        var dragCreatedSelection = false
                        // 长按拖拽被拖端点侧别：默认拖末端，越过起点后换侧
                        // （固定端不跟随，见 draggingEndpointIsStart）
                        var dragMovesStart = false
                        // 页顶/页底按住翻页触发状态机（每次按住一个，v2 Task 8）
                        var flipTrigger: FlipTrigger? = null
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragCreatedSelection = false
                                dragMovesStart = false
                                flipTrigger = null
                                selectionDragActive = true
                                // 端口缺失：选择交互整体不启用（契约 §3.3 降级
                                // 语义）——不选词、不触觉，长按手势整体旁路；
                                // 点按标记浮条/浮窗同随端口缺失不可达（无端口
                                // 根本没有选区，松手冻结语义随门控消解）
                                if (!selectionEnabled) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                if (!currentControlsVisible && currentError == null &&
                                    currentSelection == null
                                ) {
                                    val page = currentPage
                                    if (page != null) {
                                        val hit = hitTest(
                                            page, offset.x, offset.y, currentMeasureContent
                                        )
                                        if (hit != null) {
                                            // 词区间两端闭合：裸长按即选中完整词，
                                            // 不产生零长度选区（复制为空/批注失效）
                                            val (wordStart, wordEnd) = snapToWordRange(
                                                page, hit
                                            )
                                            buildSelection(page, wordStart, wordEnd)?.let { created ->
                                                onSelectionChange(created)
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
                                    page, change.position.x, change.position.y,
                                    currentMeasureContent,
                                )
                                if (hit != null) {
                                    // 续选会话进行中：被拖端点直接按手指命中赋值给
                                    // 会话（与把手拖拽同一条通路，见 onDraggedHit
                                    // KDoc）——长按拖拽路径漏喂会话时，翻页后继续
                                    // 拖会静默无效：光标停在翻页边、端点不前进，
                                    // 最终只标到离页那一段（真机反馈）
                                    onDraggedHit(hit)
                                    // 会话中不再做页内合成：真值是章内两端点，页内结果
                                    // 本来就被忽略；更要紧的是**不能再拿旧页命中与当前页
                                    // 拼选区**——翻页刷新窗口里旧命中行内下标可能超出
                                    // 当前行长度（真机崩溃：buildSelection substring 越界）
                                    if (currentSession == null) {
                                        // 长按拖拽延伸默认拖末端；端点越过起点后换侧
                                        // （见 draggingEndpointIsStart——固定端不跟随）
                                        val moved = moveEndpoint(page, sel, dragMovesStart, hit)
                                        onSelectionChange(moved)
                                        dragMovesStart = draggingEndpointIsStart(moved, hit)
                                    }
                                }
                                // 页顶/页底按住翻页（v2 Task 8，长按拖拽路径）：
                                // 被拖端点的把手侧按命中与选区起点比较——命中在
                                // 起点之前或恰在起点（拖拽已越过/贴住起点向上）
                                // 判起始把手页顶带，否则结束把手判页底带；命中为
                                // 空（拖出文本行盒）时按越出边归属
                                // （offPageHandleIsStart：页顶外 = 起始侧、
                                // 页底外 = 结束侧，行盒之间空档不判触发）——会话
                                // 内反向拖出页缘（前向翻页后拖出页顶 / 向后翻页
                                // 后拖出页底）的双向翻页依赖此归属。端点进带并
                                // 持续按住超长按时值上抛一次，出带重新武装
                                // （FlipTrigger）
                                val draggingStart = when {
                                    hit != null ->
                                        hit.lineIndex < sel.startHit.lineIndex ||
                                            (
                                                hit.lineIndex == sel.startHit.lineIndex &&
                                                    hit.charIndex <= sel.startHit.charIndex
                                                )

                                    else -> offPageHandleIsStart(page, change.position.y) ?: false
                                }
                                // 会话中按"该方向还能不能更长"判翻页
                                // （[flipDirectionForDrag]）——下翻后结束端贴在新页
                                // 首行，只看把手侧永远翻不回上一页
                                val direction = flipDirectionForDrag(
                                    page = page,
                                    session = currentSession,
                                    hit = hit,
                                    y = change.position.y,
                                    fallbackDraggingStart = draggingStart,
                                )
                                val trigger = flipTrigger ?: FlipTrigger(
                                    viewConfiguration.longPressTimeoutMillis
                                ).also { flipTrigger = it }
                                trigger.onDirection(
                                    direction, SystemClock.elapsedRealtime()
                                )?.let(onFlipRequest)
                            },
                            onDragEnd = {
                                // 松手（v2.1）：只合成选区并弹选区操作条，
                                // 不落库——副作用由用户在操作条上选择动作触发
                                selectionDragActive = false
                                if (dragCreatedSelection) onSelectionFinalized()
                            },
                            onDragCancel = {
                                // 手势取消视同松手：合成选区 + 回操作条，不落库
                                selectionDragActive = false
                                if (dragCreatedSelection) onSelectionFinalized()
                            },
                        )
                    }
            ) {
            ReaderPageSnapshotCanvas(
                page = state.page,
                pageVersion = state.pageVersion,
                modifier = Modifier.fillMaxSize(),
            )
            // 选区覆盖层：灰色选中带垫在页画布下方（zIndex 由覆盖层自管），
            // 把手/指针独占在正文上方；操作条以 zIndex(2f) 组合在本层之上
            ReaderSelectionOverlay(
                snapshot = state.page,
                selection = selection,
                session = session,
                handlesEnabled = handlesEnabled,
                onSelectionChange = onSelectionChange,
                onDraggedHit = onDraggedHit,
                onFlipRequest = onFlipRequest,
                // 抓取把手 = 进入调界：操作条离场（手势中不给点击目标）
                onHandleDragStart = { selectionDragActive = true },
                onHandleRelease = {
                    // 抬手：合成会话选区 + 操作条回来（不落库）
                    selectionDragActive = false
                    onSelectionFinalized()
                },
                modifier = Modifier.matchParentSize(),
            )
            // 选区操作条（v2.1）：松手/抬手后显示，拖拽调界期间隐藏；动作集
            // 由选区是否落在用户标记上决定（新区间 复制/画线/想法；已有标记
            // 复制/想法/删除），含标题选区只留复制
            if (
                selectionBarVisible && selection != null && !selectionDragActive &&
                !state.controlsVisible
            ) {
                state.page?.let { page ->
                    AnchoredSelectionActionBar(
                        page = page,
                        selection = selection,
                        measureTitle = measureTitle,
                        measureContent = measureContent,
                        canvasWidthPx = canvasWidthPx,
                        actions = selectionActions(
                            hasMarking = selectionHasMarking,
                            canMark = selectionEnabled && !selection.includesTitle,
                        ),
                        onAction = onSelectionAction,
                    )
                }
            }
            // 点按标记操作条（v2 Task 6，设计 §4「点已有标记」）：无选区时以
            // 命中 run 几何锚定展示三键（与选区操作条同组件同语义）。选区
            // 存在（长按/落库待确认冻结）随行关闭（Route onSelectionChange /
            // 页变清态），操作条展开期隐藏
            if (
                selection == null && markingBarVisible && markingSelection != null &&
                !state.controlsVisible
            ) {
                state.page?.let { page ->
                    AnchoredSelectionActionBar(
                        page = page,
                        selection = markingSelection,
                        measureTitle = measureTitle,
                        measureContent = measureContent,
                        canvasWidthPx = canvasWidthPx,
                        actions = selectionActions(
                            hasMarking = true,
                            canMark = selectionEnabled,
                        ),
                        onAction = onMarkingAction,
                    )
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
                    // 页面书签钮（v2 Task 9，设计 §4）：选中态 = 当前页快照
                    // bookmarkBadge；批注端口缺失（selectionEnabled = false）
                    // 时整颗不渲染（契约 §3.3 降级语义）
                    bookmarkEnabled = selectionEnabled,
                    bookmarkBadge = state.page?.bookmarkBadge == true,
                    onOpenDetail = onOpenDetail,
                    onChangeSource = onChangeSource,
                    onRefresh = onRefresh,
                    onOpenCachePanel = onOpenCachePanel,
                    onAddToBookshelf = onAddToBookshelf,
                    onRemoveFromBookshelf = onRemoveFromBookshelf,
                    onToggleBookmark = onTogglePageBookmark,
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
 * 操作条锚定组合：选区/run 几何（selectionRuns 行内区间 → x + 行盒）→
 * 选区上方（放不下取下方）的 [ReaderSelectionActionBar]。选区操作条
 * （选区驱动）与点按标记操作条（[markingSelection] = run 派生选区）共用
 * 同一锚定机制——run 行内区间即字符索引，派生选区天然落在命中行盒上。
 * 锚定几何缺失（页快照与选区行下标错位、页翻动在途）时不展示，不做错位
 * 操作条。
 */
@Composable
private fun AnchoredSelectionActionBar(
    page: ReaderPageSnapshot,
    selection: ReaderSelectionUi,
    measureTitle: (String) -> Float,
    measureContent: (String) -> Float,
    canvasWidthPx: Int,
    actions: List<ReaderMarkingAction>,
    onAction: (ReaderMarkingAction) -> Unit,
) {
    val runs = selectionRuns(page, selection, measureTitle, measureContent)
    val anchors = handleAnchors(runs) ?: return
    ReaderSelectionActionBar(
        anchorLeft = anchors.first.x,
        anchorTop = anchors.first.top,
        anchorRight = anchors.second.x,
        anchorBottom = runs.last().bottom,
        canvasWidth = canvasWidthPx.toFloat(),
        actions = actions,
        onAction = onAction,
    )
}

/**
 * 阅读界面系统栏避让：左右/底部取 displayCutout ∪ systemBars；顶部按「隐藏状态栏」
 * 开关显式置零——旧平台（API < 30）legacy 布局标记 LAYOUT_STABLE 下，
 * 系统栏插图冻结在「栏可见」尺寸、不随 insetsController.hide() 归零，
 * safeDrawing 自动收缩不可依赖，置零后状态栏区域转为页眉区域。
 * 对齐完整模式 LAYOUT_FULLSCREEN：隐藏时不避让刘海（墨水屏设备无刘海）。
 *
 * 左右/底部不用 safeDrawing：safeDrawing 含 ime，若进避让，输入法弹出会压缩
 * 阅读视口 → 宿主按新视口全量重排（updateViewport → requestPagination）→
 * 正文跳页且弹框被页变效应关闭。阅读区与面板均无内嵌输入场景，键盘一律以
 * 覆盖层出现，输入型弹框的避让由 EInkDialog 的 imePadding 承担。
 */
@Composable
private fun Modifier.readerSystemBarInsets(hideStatusBar: Boolean): Modifier {
    val top = if (hideStatusBar) {
        WindowInsets(0, 0, 0, 0)
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    }
    return windowInsetsPadding(
        WindowInsets.displayCutout
            .union(WindowInsets.systemBars)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
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
