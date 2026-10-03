package io.legado.app.eink.feature.bookshelf

import android.content.Context
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfStyle
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTypography
import io.legado.app.eink.feature.common.EInkCoverHeight
import io.legado.app.eink.feature.common.EInkCoverWidth
import io.legado.app.eink.feature.common.coverTargetSizePx
import io.legado.app.eink.feature.common.prefetchCovers
import kotlinx.coroutines.flow.first

/**
 * 首屏封面预热：进入 E-Ink 模式（入口 Activity onCreate、组合开始前）后台把
 * 书架第一页封面提前解码进模块 ImageLoader 内存缓存。
 *
 * 现有 [prefetchCovers]（HomeRoute）在「当前页落定后」预取下一页，翻页零
 * 占位；但冷启动第一页没有更早的落定时刻可借——条目首帧组合即发起异步
 * 请求，先画文字占位、到位后重绘，墨水屏上就是两次全页绘制。本预热与
 * 组合启动并行抢跑：[EInkAsyncImage] 组合期的同步命中快路径直接绘制缓存
 * 位图（零请求、零占位帧）。磁盘缓存命中的小尺寸封面解码快，能抢在条目
 * 组合前完成多少就收益多少；网络封面与首帧组合赛跑，输了也只是回到现状
 * （异步到位重绘），不会更差。上限对齐完整模式 BookshelfCoverPreloader 的
 * 首屏量级：超出第一页的部分与翻页预取同键，Coil 在途去重，无额外浪费。
 *
 * 尺寸预测契约：预热必须与显示路径产出同一 [coverTargetSizePx] 像素值
 * （缓存键含尺寸，见 buildEInkCoverRequest KDoc），三处同源：
 *  - density/fontScale 取入口包装后的 Activity resources（与 LocalDensity
 *    同源，sp→dp 非线性换算一致，见 [bookshelfListRowHeight] KDoc）；
 *  - 列表行高参数与 HomeRoute 逐项相同——[EInkTypography] 行高钉住、
 *    不随宿主字体族联动，组合外引用合法；
 *  - 网格可用宽用 `screenWidthDp` 预测：HomeRoute 的 BoxWithConstraints
 *    宽 = 屏宽 − 水平 safeDrawing 内边距，无水平刘海/系统栏的墨水屏设备
 *    两者相等。带水平刘海的设备预测偏大 → 键失配 → 该项预热不被同步命中
 *    （显示退回异步路径），仅浪费不致错。
 */
internal suspend fun prewarmFirstScreenCovers(context: Context) {
    // 「使用默认封面」模式下不显示网络封面，预热纯浪费（同 prefetchCovers 门控）
    if (EInkEngineRegistry.globalSettings.useDefaultCover) return

    val engine = EInkEngineRegistry.bookshelfEngine
    val groupEngine = EInkEngineRegistry.bookshelfGroupEngine
    val style = engine.style.first()

    val density = Density(
        density = context.resources.displayMetrics.density,
        fontScale = context.resources.configuration.fontScale,
    )
    val screenWidthDp = context.resources.configuration.screenWidthDp
    if (screenWidthDp <= 0) return

    val (widthPx, heightPx) = prewarmCoverTargetSize(style, density, screenWidthDp) ?: return
    val limit = if (style.isGridLayout) PREWARM_GRID_BOOKS else PREWARM_LIST_BOOKS

    // 书集与 BookshelfViewModel.booksFlow 同口径：选中「全部」走 observeShelf，
    // 其余走分组端口（未注册分组端口的旧宿主诚实退化为全部书架）
    val groupId = groupEngine?.selectedGroup?.first() ?: BookshelfGroupIds.ALL
    val books = if (groupId == BookshelfGroupIds.ALL) {
        engine.observeShelf().first()
    } else {
        groupEngine?.observeGroupBooks(groupId)?.first() ?: engine.observeShelf().first()
    }
    if (books.isEmpty()) return

    prefetchCovers(
        context = context,
        items = books.take(limit),
        widthPx = widthPx,
        heightPx = heightPx,
        coverUrl = { it.coverUrl },
        sourceOrigin = { it.origin },
    )
}

/**
 * 预热目标尺寸解析（纯函数，供 parity 测试钉住）：与 HomeRoute 显示路径
 * 同链同参——网格走 [adaptiveGridColumns] → [bookshelfGridCellWidth] →
 * 66:90 等比封面；列表走 [bookshelfListRowHeight]（[EInkTypography] 钉住
 * 行高 + [EInkSpacing] 间距）→ 宽 66:90 等比伸缩，最终经 [coverTargetSizePx]
 * 换算像素（缓存键逐字节一致的前提）。
 *
 * @return 宽×高像素；[screenWidthDp] 非法（<= 0）时 null（调用方放弃预热）。
 */
internal fun prewarmCoverTargetSize(
    style: BookshelfStyle,
    density: Density,
    screenWidthDp: Int,
): Pair<Int, Int>? {
    if (screenWidthDp <= 0) return null
    return if (style.isGridLayout) {
        val maxWidth = screenWidthDp.dp
        val columns = adaptiveGridColumns(maxWidth, style.gridCoverWidth.dp)
        val cellWidth = bookshelfGridCellWidth(maxWidth, columns)
        coverTargetSizePx(
            cellWidth,
            cellWidth * (EInkCoverHeight / EInkCoverWidth),
            density,
        )
    } else {
        val rowHeight = bookshelfListRowHeight(
            density = density,
            titleLineHeight = EInkTypography.titleMedium.lineHeight,
            authorLineHeight = EInkTypography.bodyMedium.lineHeight,
            chapterLineHeight = EInkTypography.bodyMedium.lineHeight,
            showLatestChapter = style.showLatestChapter,
            rowSpacing = EInkSpacing.xs,
            verticalPadding = EInkSpacing.xxs,
        )
        coverTargetSizePx(
            rowHeight * (EInkCoverWidth / EInkCoverHeight),
            rowHeight,
            density,
        )
    }
}

/** 网格预热上限：3 列 × 高屏 5 行的余量，再多的书不在第一页。 */
private const val PREWARM_GRID_BOOKS = 24

/** 列表预热上限：列表模式一屏只可见几本，按翻页预取同量级收敛。 */
private const val PREWARM_LIST_BOOKS = 12
