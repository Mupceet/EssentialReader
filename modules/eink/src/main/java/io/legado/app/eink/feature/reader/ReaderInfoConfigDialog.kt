package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkSpacing

/**
 * 信息配置弹层（单页）：标题位置三选；标题字号「随正文一致/自定义」
 * 二选（仅自定义显示拖动条，初值=当前标题字号）；「页眉显示」「页脚
 * 显示」两行按钮依次 显示/隐藏/随状态栏（页脚仅前两枚，第三槽占位
 * 与页眉行同列对齐）；「页眉页脚字号」统一拖动条（页眉/页脚共用同一
 * 字号，一次写两侧）。标签按需占宽保证完整显示（Box 居中 intrinsic，同字体弹层）。
 *  上/下留白、标题行距、页眉页脚分割线不暴露（eink 不支持分割线绘制，
 *  留白/行距不开放调节）。
 *
 * 行级目录守卫：每个设置行按宿主目录声明显隐——参数未声明/不可用
 * 整行隐藏（含二选开关，不留写无效键的死控件）；数值参数声明
 * [ReaderStyleParam.Locked] 时置灰呈现锁定值。
 *
 * 行实现经共享行组件承载（ReaderStyleRows.kt，归一弹框 Task 2 抽取）。
 */
@Composable
internal fun ReaderInfoConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    titleSizeFollowBody: Boolean,
    onSetTitleMode: (Int) -> Unit,
    onSetTitleSizeFollowBody: (Boolean) -> Unit,
    onSetTitleSize: (Int) -> Unit,
    onSetHeaderMode: (Int) -> Unit,
    onSetFooterVisible: (Boolean) -> Unit,
    onSetTipSize: (Int) -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    EInkDialog(
        onDismiss = onClose,
        title = "信息配置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            TitleInfoRows(
                catalog = catalog,
                style = style,
                titleSizeFollowBody = titleSizeFollowBody,
                onSetTitleMode = onSetTitleMode,
                onSetTitleSizeFollowBody = onSetTitleSizeFollowBody,
                onSetTitleSize = onSetTitleSize,
            )
            HeaderModeRow(catalog = catalog, style = style, onSetHeaderMode = onSetHeaderMode)
            FooterModeRow(catalog = catalog, style = style, onSetFooterVisible = onSetFooterVisible)
            TipSizeRow(catalog = catalog, style = style, onSetTipSize = onSetTipSize)
        }
    }
}
