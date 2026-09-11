package io.legado.app.eink.feature.reader.selection

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.reader.applySpec

/** 把手热区半径（dp，对齐宿主 28f*density）。 */
internal val SelectionHandleTouchRadiusDp = 28.dp

/** 把手竖条宽度（dp，对齐完整模式 SelectionHandleStrokeWidth）。 */
private val SelectionHandleStrokeDp = 2.dp

/** 把手圆点半径（dp，对齐完整模式 SelectionHandleRadius）。 */
private val SelectionHandleRadiusDp = 7.dp

/** 选中带圆角（dp）——小圆角对齐 DS 尺度，墨水屏大圆角易发糊。 */
private val SelectionBandCornerDp = 2.dp

/**
 * 选区覆盖层：灰色选中带（文本下方填充）+ 首末 pin 把手。
 *
 * 分两层绘制——
 * - 选中带（zIndex(-1f)）：垫在页画布**下方**，对选区 runs 逐行铺主题
 *   [io.legado.app.eink.designsystem.theme.EInkColorScheme.selectionContainer]
 *   填充矩形（行盒高 × 行内区间宽，2dp 圆角）——正文笔迹压在带上仍然可读，
 *   观感即「选中的文字铺了灰底」；拖拽调界随 runs 重建逐帧重绘；
 * - 把手 + 指针独占（zIndex(1f)）：压在正文上方。pointerInput 只在按下
 *   即命中把手时消费指针、独占本次拖拽；否则不消费任何事件直接返回，
 *   下层点按/翻页/长按检测器照常工作（空白处点击 = 清选区由 Screen 承担；
 *   操作条以 zIndex(2f) 组合在本层之上，把手热区不吞操作键点击）。
 *
 * 把手几何对齐完整模式 ReaderCanvasSurface 的 pin 手柄：竖条贯穿行盒
 * [top, bottom]（**手柄高度 = 文本行高**），竖条下沿外挂一个圆点；抓取
 * 热区以圆点为心、半径 [SelectionHandleTouchRadiusDp]。
 *
 * 拖拽循环内经 rememberUpdatedState 读实时把手位/选区/页快照/测量闭包：
 * 不以 selection/snapshot 为 pointerInput key——每次端点替换或续选会话翻页
 * （v2 Task 8）都会触发重组，以之为 key 会在拖拽中途重启、打断手势。端点
 * 替换语义幂等（只替换一端、另一端固定），重组滞后不产生累积误差；翻页
 * 推进 pageVersion 后新快照经 State 实时读入，端点命中/吸附即用新页数据
 * （指针按住跨页不中断）。
 *
 * 跨页续选（v2 Task 8，设计 §4）：拖拽循环内每次 move 后判翻页方向
 * （flipDirectionForPointer——起始把手判页顶带、结束把手判页底带；空命中
 * 按越出边归属把手侧，offPageHandleIsStart——会话内反向拖出页缘的双向
 * 翻页依赖此归属），端点进带并持续按住超系统长按时值经 [onFlipRequest]
 * 上抛一次；一次按住只触发一次，拖出触发带重新武装（FlipTrigger）。
 * 抓取瞬间经 [onHandleDragStart] 上抛（Screen 收操作条——调界期间操作条
 * 离场），拖拽松手经 [onHandleRelease] 上抛（Route 侧合成会话最终选区，
 * 不落库：落库由用户在操作条上选「画线/想法」触发）。
 *
 * [handlesEnabled] = false（落库冻结）时把手转只读展示：选区与把手保持
 * 绘制，但不进入抓取、不消费任何事件，按下落回下层检测器（点按照常清
 * 选区）；冻结值同样经 rememberUpdatedState 实时读、不作 pointerInput
 * key——冻结发生在浮条展示期（无在途把手手势），以之为 key 徒增重启面。
 */
@Composable
internal fun ReaderSelectionOverlay(
    snapshot: ReaderPageSnapshot?,
    selection: ReaderSelectionUi?,
    handlesEnabled: Boolean,
    onSelectionChange: (ReaderSelectionUi?) -> Unit,
    onFlipRequest: (Int) -> Unit,
    onHandleDragStart: () -> Unit,
    onHandleRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null || selection == null) return
    val themeForeground = EInkTheme.colorScheme.onBackground
    val selectionBand = EInkTheme.colorScheme.selectionContainer
    val density = LocalDensity.current
    val handleStrokePx = with(density) { SelectionHandleStrokeDp.toPx() }
    val handleRadiusPx = with(density) { SelectionHandleRadiusDp.toPx() }
    val bandCornerPx = with(density) { SelectionBandCornerDp.toPx() }
    val touchRadiusPx = with(density) { SelectionHandleTouchRadiusDp.toPx() }

    // 与页画布同规格的测量闭包（applySpec 幂等，重复设置无害）
    val measureTitle = remember(snapshot.titleSpec, themeForeground) {
        val paint = Paint()
        val spec = snapshot.titleSpec
        { text: String ->
            paint.applySpec(spec, themeForeground.toArgb())
            paint.measureText(text)
        }
    }
    val measureContent = remember(snapshot.contentSpec, themeForeground) {
        val paint = Paint()
        val spec = snapshot.contentSpec
        { text: String ->
            paint.applySpec(spec, themeForeground.toArgb())
            paint.measureText(text)
        }
    }
    val runs = remember(selection, snapshot) {
        selectionRuns(snapshot, selection, measureTitle, measureContent)
    }
    val anchors = remember(runs) { handleAnchors(runs) }
    // 拖拽循环内读实时值：按下时刻的把手位/选区/页快照/测量闭包不冻结
    // （见类 KDoc——续选会话翻页推进 pageVersion 后新快照实时生效）
    val currentAnchors by rememberUpdatedState(anchors)
    val currentSelection by rememberUpdatedState(selection)
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentMeasureContent by rememberUpdatedState(measureContent)
    val currentHandlesEnabled by rememberUpdatedState(handlesEnabled)

    // 灰色选中带：垫在页画布下方（正文笔迹压在带上，见类 KDoc）
    Canvas(modifier = modifier.zIndex(-1f)) {
        for (run in runs) {
            drawRoundRect(
                color = selectionBand,
                topLeft = Offset(run.left, run.top),
                size = Size(
                    (run.right - run.left).coerceAtLeast(0f),
                    (run.bottom - run.top).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius(bandCornerPx),
            )
        }
    }
    // 把手 + 指针独占：按下即命中把手才消费指针，独占本次拖拽。
    // 键为 Unit：全部实时值经 rememberUpdatedState 读取（续选会话翻页
    // 推进 pageVersion 不重启、不打断在途拖拽，见类 KDoc）
    Canvas(
        modifier = modifier
            .zIndex(1f)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 落库冻结：把手停用（只读展示），不进入抓取、不消费事件，
                    // 按下落回下层检测器（点按照常清选区，见类 KDoc）
                    if (!currentHandlesEnabled) return@awaitEachGesture
                    val grab = grabHandle(currentAnchors, down.position, touchRadiusPx)
                        ?: return@awaitEachGesture
                    // 抓取即上抛：Screen 收操作条（调界期间操作条离场，
                    // 松手后由 onHandleRelease 路径恢复）
                    onHandleDragStart()
                    // 页顶/页底按住翻页触发状态机：每次按住一个，入带计时
                    // 超长按时值上抛一次，出带重新武装
                    val flipTrigger = FlipTrigger(viewConfiguration.longPressTimeoutMillis)
                    // 被拖端点侧别：抓取时按命中把手定，之后每帧按归一化结果
                    // 刷新（端点可越过对方，越过即换侧——见 draggingEndpointIsStart）
                    var draggingStart = grab
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // 把手上的裸点按就地消费，不外漏成「清选区」tap；
                            // 拖拽松手上抛提交链路（Route 侧会话合成，见类 KDoc）
                            change.consume()
                            onHandleRelease()
                            break
                        }
                        change.consume()
                        val sel = currentSelection
                        val snapshotNow = currentSnapshot
                        val hit = snapshotNow?.let {
                            hitTest(it, change.position.x, change.position.y, currentMeasureContent)
                        }
                        // sel 非空由本组合入口早退保证（selection == null 不组合），
                        // 无需判空；hit/snapshot 真可空（拖出文本区/页快照换页瞬间）
                        if (snapshotNow != null && hit != null) {
                            val moved = moveEndpoint(snapshotNow, sel, draggingStart, hit)
                            onSelectionChange(moved)
                            draggingStart = draggingEndpointIsStart(moved, hit)
                        }
                        // 页顶/页底按住翻页（v2 Task 8）：非空命中按抓取把手侧
                        // 判触发带（现状不变）；空命中（拖出文本行盒）按越出边
                        // 归属把手侧（offPageHandleIsStart——页顶外 = 起始侧、
                        // 页底外 = 结束侧，行盒之间空档不判触发）：会话内反向
                        // 拖出页缘（前向翻页后拖出页顶 / 向后翻页后拖出页底）
                        // 的双向翻页依赖此归属。计时满上抛一次，出带重新武装
                        // （FlipTrigger）
                        if (snapshotNow != null) {
                            val handleIsStart = when {
                                hit != null -> grab
                                else ->
                                    offPageHandleIsStart(snapshotNow, change.position.y)
                                        ?: grab
                            }
                            val direction = flipDirectionForPointer(
                                snapshotNow, hit, change.position.y, handleIsStart
                            )
                            flipTrigger.onDirection(
                                direction, SystemClock.elapsedRealtime()
                            )?.let(onFlipRequest)
                        }
                    }
                }
            },
    ) {
        if (anchors != null) {
            val (startAnchor, endAnchor) = anchors
            drawPinHandle(startAnchor, handleStrokePx, handleRadiusPx, themeForeground)
            drawPinHandle(endAnchor, handleStrokePx, handleRadiusPx, themeForeground)
        }
    }
}

/**
 * pin 把手（完整模式同款）：竖条贯穿行盒（手柄高度 = 文本行高），行盒下沿
 * 外挂一个描边圆点；圆点圆心即抓取热区中心（见 [grabHandle]）。
 */
private fun DrawScope.drawPinHandle(
    anchor: SelectionHandleAnchor,
    strokeWidth: Float,
    radius: Float,
    color: Color,
) {
    drawLine(
        color = color,
        start = Offset(anchor.x, anchor.top),
        end = Offset(anchor.x, anchor.bottom),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    // 竖条止于行盒下沿，圆的顶端与竖条末端相切（完整模式同几何）
    drawCircle(
        color = color,
        radius = radius,
        center = Offset(anchor.x, anchor.bottom + radius),
        style = Stroke(width = strokeWidth),
    )
}

/** 命中首/末把手；true = 起始把手，false = 末端把手，null = 未命中。 */
internal fun grabHandle(
    anchors: Pair<SelectionHandleAnchor, SelectionHandleAnchor>?,
    position: Offset,
    touchRadius: Float,
): Boolean? {
    if (anchors == null) return null
    val (startAnchor, endAnchor) = anchors
    val distanceToStart = Offset(startAnchor.x, startAnchor.bottom).getDistanceTo(position)
    val distanceToEnd = Offset(endAnchor.x, endAnchor.bottom).getDistanceTo(position)
    return when {
        distanceToStart <= touchRadius -> true
        distanceToEnd <= touchRadius -> false
        else -> null
    }
}

private fun Offset.getDistanceTo(other: Offset): Float = (this - other).getDistance()

/** 替换选区一端并重建（保持另一端不变）。 */
internal fun moveEndpoint(
    snapshot: ReaderPageSnapshot,
    selection: ReaderSelectionUi,
    isStart: Boolean,
    hit: ReaderTextHit,
): ReaderSelectionUi =
    buildSelection(
        snapshot,
        if (isStart) hit else selection.startHit,
        if (isStart) selection.endHit else hit,
    ) ?: selection

/**
 * 拖动端在新选区里的侧别：**拖动端 = 与本次命中位置一致的那一端**。
 *
 * 端点允许越过对方（区间按 [normalizeHits] 归一），越过之后被拖端在归一化
 * 区间里换到了另一侧——后续 move 必须跟着换侧，否则会把**固定端当成被拖端**
 * 继续替换：手指继续往左拖，右边缘反而跟着手指跑，选区越拖越乱（真机反馈
 * 「把手拖回另一端之前就错乱」）。拖动循环每帧用本函数刷新侧别。
 */
internal fun draggingEndpointIsStart(
    selection: ReaderSelectionUi,
    hit: ReaderTextHit,
): Boolean = selection.startHit == hit
