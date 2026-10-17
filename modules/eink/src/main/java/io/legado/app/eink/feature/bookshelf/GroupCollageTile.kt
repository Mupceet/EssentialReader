package io.legado.app.eink.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.BookshelfCoverRefUiModel
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.theme.EInkShapes
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.common.EInkBookCover

/**
 * 聚合条目嵌套封面拼贴磁贴（spec D9 2026-10-16 修订）：
 * 外层 2×2 = 前三本封面 + 右下递归 2×2（第四至七本），空白象限仅用于
 * 书籍数量不足；无图封面用书名+作者文字封面（同普通封面条目）。
 * 左下角完整矩形计数标（与磁贴左下角完全重合，白天主题白底黑字）。
 * E-Ink：静态单帧、零动画；象限尺寸显式计算（封面缓存键带尺寸）。
 */
private val COLLAGE_GAP = 2.dp

@Composable
fun GroupCollageTile(
    covers: List<BookshelfCoverRefUiModel>,
    bookCount: Int,
    tileWidth: Dp,
    tileHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = modifier
            .size(tileWidth, tileHeight)
            .border(1.dp, colors.outline, EInkShapes.medium),
    ) {
        // 空组占位：竖排「未添加书籍」（逐字纵向）
        if (covers.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                "未添加书籍".forEach { ch ->
                    EInkText(
                        text = ch.toString(),
                        style = EInkTheme.typography.bodyMedium,
                        color = colors.outline,
                    )
                }
            }
            Box(modifier = Modifier.align(Alignment.BottomStart)) {
                CountBadge(bookCount)
            }
            return@Box
        }
        val quadrantWidth = (tileWidth - COLLAGE_GAP * 2) / 2
        val quadrantHeight = (tileHeight - COLLAGE_GAP * 2) / 2
        Column(modifier = Modifier.fillMaxSize().padding(COLLAGE_GAP)) {
            Row(modifier = Modifier.weight(1f)) {
                CollageQuadrant(covers.getOrNull(0), quadrantWidth, quadrantHeight, Modifier.weight(1f))
                Box(Modifier.size(COLLAGE_GAP))
                CollageQuadrant(covers.getOrNull(1), quadrantWidth, quadrantHeight, Modifier.weight(1f))
            }
            Box(Modifier.size(COLLAGE_GAP * 2, COLLAGE_GAP))
            Row(modifier = Modifier.weight(1f)) {
                CollageQuadrant(covers.getOrNull(2), quadrantWidth, quadrantHeight, Modifier.weight(1f))
                Box(Modifier.size(COLLAGE_GAP))
                // 右下递归 2×2：第四至七本
                Box(modifier = Modifier.weight(1f)) {
                    val innerW = (quadrantWidth - COLLAGE_GAP * 2) / 2
                    val innerH = (quadrantHeight - COLLAGE_GAP * 2) / 2
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f)) {
                            CollageQuadrant(covers.getOrNull(3), innerW, innerH, Modifier.weight(1f))
                            Box(Modifier.size(COLLAGE_GAP))
                            CollageQuadrant(covers.getOrNull(4), innerW, innerH, Modifier.weight(1f))
                        }
                        Box(Modifier.size(COLLAGE_GAP * 2, COLLAGE_GAP))
                        Row(Modifier.weight(1f)) {
                            CollageQuadrant(covers.getOrNull(5), innerW, innerH, Modifier.weight(1f))
                            Box(Modifier.size(COLLAGE_GAP))
                            CollageQuadrant(covers.getOrNull(6), innerW, innerH, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Box(modifier = Modifier.align(Alignment.BottomStart)) {
            CountBadge(bookCount)
        }
    }
}

/** 左下角计数标：带 1dp 边框的圆角矩形，叠在磁贴左下角上（BottomStart
 *  零内边距）；白天主题白底黑字，夜间随主题反转。空组占位复用。 */
@Composable
private fun CountBadge(bookCount: Int) {
    val colors = EInkTheme.colorScheme
    Box(
        modifier = Modifier
            .background(colors.background, EInkShapes.medium)
            .border(1.dp, colors.outline, EInkShapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        EInkText(
            text = "$bookCount 本",
            style = EInkTheme.typography.labelSmall,
            color = colors.onSurface,
            modifier = Modifier.padding(horizontal = EInkSpacing.xs, vertical = 0.dp),
        )
    }
}

/** 单象限：渲染封面（无图 url 由 EInkBookCover 文字封面兜底——书名+作者），
 *  象限为 null（书籍数量不足）时留白不组合。尺寸显式传参与缓存键一致。 */
@Composable
private fun CollageQuadrant(
    cover: BookshelfCoverRefUiModel?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (cover != null) {
            EInkBookCover(
                url = cover.url,
                name = cover.name,
                author = cover.author,
                modifier = Modifier.fillMaxSize(),
                width = width,
                height = height,
                sourceOrigin = cover.origin,
            )
        }
    }
}
