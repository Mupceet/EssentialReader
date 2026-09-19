package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageSnapshot

/**
 * 阅读区点按（非长按/拖拽手势）的分派目标——把 ReaderScreen 点按分支的
 * 判定顺序抽成纯函数，便于单测钉住交互表。
 *
 * 浮层/残留选区在场时点按**只**收它们并吞掉本次点按：不翻页、不唤操作条
 * （对齐完整模式 ReaderCanvasSurface——选区外的按下 `suppressTap = true`）。
 * 浮条是覆盖在正文上的临时工具条，收浮条动作若同时穿透成翻页/菜单，用户
 * 会把「收浮条」误读成误触；且操作条展开期浮条本就不组合（`!controlsVisible`），
 * 穿透会把浮条状态留在背后，菜单收起时浮条自动复现（幽灵浮条）。
 */
enum class ReaderTapDispatch {
    /** 操作条展开：点按任意处收起操作条（原语义）。 */
    COLLAPSE_CONTROLS,

    /**
     * 选区在场（操作条态、或画线/想法落库后直到装饰到位的只读冻结态；会话
     * 中的视觉选区同理）：点按清选区，吞掉本次点按——避免「收残场」被误解
     * 成翻页/唤菜单，下一次点按即正常查标记命中（点划线唤起操作条）。
     */
    DISMISS_SELECTION,

    /** 点按标记浮条在场（无选区）：点浮条外收浮条，吞掉本次点按。 */
    DISMISS_MARKING_BAR,

    /** 无浮层：点按先查标记命中，未命中/标记失效静默回落分区行为。 */
    ZONE_OR_MARKING_HIT,
}

/**
 * 点按分派：操作条展开优先收起；其次浮层/残留选区（选区优先于标记浮条
 * ——两者不会同时在场，选区建立时标记浮条随行关闭）；都没有才走分区行为。
 */
fun readerTapDispatch(
    controlsVisible: Boolean,
    hasSelection: Boolean,
    hasMarkingBar: Boolean,
): ReaderTapDispatch = when {
    controlsVisible -> ReaderTapDispatch.COLLAPSE_CONTROLS
    hasSelection -> ReaderTapDispatch.DISMISS_SELECTION
    hasMarkingBar -> ReaderTapDispatch.DISMISS_MARKING_BAR
    else -> ReaderTapDispatch.ZONE_OR_MARKING_HIT
}

/**
 * 点按命中的可交互图片槽位（带动作脚本的图片，段评气泡是典型用法）。
 *
 * 命中优先级在标记/分区行为之前：行内小图（气泡）的槽位矩形嵌在视觉
 * 行内，文本命中按最近字符归位会把气泡上的点按误读成相邻文字，故图片
 * 矩形优先判定。无动作脚本的图片不参与命中——普通插图点击无行为，回落
 * 分区行为（与完整模式未携带 click 选项的图片点按穿透一致）。
 *
 * 命中后经 [io.legado.app.eink.contract.ReaderEngine.dispatchImageAction]
 * 上抛宿主执行；模块不感知脚本内容。
 */
fun imageActionSlotAt(
    page: ReaderPageSnapshot,
    x: Float,
    y: Float,
): ReaderImageSlot? = page.images.firstOrNull { slot ->
    slot.action != null &&
        x >= slot.x0 && x <= slot.x1 &&
        y >= slot.lineTop && y <= slot.lineBottom
}

/**
 * 想法弹框内容决定落库类型（v2.2 产品规则）：**非空 = 想法（虚线）**，
 * **清空（含仅空白）= 划线（实线）**——想法把内容删空后保存即自动变回划线；
 * 类型语言「划线 = 实线 + note 空 / 想法 = 虚线 + note 非空」由此恒定，
 * 不会出现「没有内容的虚线想法」。
 */
fun markingThoughtFromNote(note: String): Boolean = note.isNotBlank()

/**
 * 页变（`pageVersion` 推进）后「待确认落库」划线预览的续显选区：非空 =
 * 预览续显（以返回值重锚到新页）；null = 清态（预览退场，正式装饰接管）。
 *
 * 为什么需要：松手落划线是「落库 → 宿主重排 → 推带装饰的新快照」的异步
 * 链路，模块在 pageVersion 推进时就清态会让预览先消失、正式划线后到——
 * 真机上表现为「线先消失、再出现」（见 §4 交互优化轮）。此策略把清态
 * 条件改为「正式装饰真的已在页上」：
 *  - 提交区间已有用户标记装饰（[markingRenderedForRange]，按章内区间判交，
 *    不依赖行下标——重排后行下标可能漂移）→ null：正式划线接管；
 *  - 装饰尚未并入但区间仍在本页（宿主重排在途）→ 按区间重锚的选区：
 *    预览与只读把手原地续显，线不闪断；
 *  - 区间不在本页（翻页/跳章）或无待确认提交 → null：清态。
 *
 * 含标题选区不落库（§3.4 门禁）：调用方不登记待确认提交，任何页变都清态。
 */
fun pendingPreviewAfterPageVersion(
    page: ReaderPageSnapshot?,
    committed: ReaderSelectionUi?,
): ReaderSelectionUi? {
    val target = committed ?: return null
    val snapshot = page ?: return null
    if (markingRenderedForRange(snapshot, target.bodyStart, target.bodyEnd)) return null
    if (captureSegment(snapshot, target.bodyStart, target.bodyEnd) == null) return null
    return selectionFromChapterRange(snapshot, target.bodyStart, target.bodyEnd)
}
