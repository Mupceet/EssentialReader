package io.legado.app.eink.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.ReaderFontOption
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.control.EInkDialog
import io.legado.app.eink.designsystem.theme.EInkSpacing

/**
 * 字体配置弹层（tabs 正文｜标题｜页眉）：字体列表与字重滑条（正文/
 * 标题；宿主无页眉字重键）。字体文件来自宿主字体文件夹，经
 * [onPickFolder] 发起 SAF 选择后刷新列表。
 *
 * 选项构成：正文 = 系统预设 + 字体文件；标题/页眉 = 跟随正文 +
 * 字体文件（宿主 titleFont/headerFont 键仅收文件路径，空串回落
 * 正文/系统默认，系统预设不可表达）。
 */
@Composable
internal fun ReaderFontConfigDialog(
    catalog: ReaderStyleCatalog,
    style: ReaderTextStyle,
    fontOptions: List<ReaderFontOption>,
    onSetBodyFont: (ReaderFontSelection) -> Unit,
    onSetBodyWeight: (Int) -> Unit,
    onSetTitleFont: (ReaderFontSelection) -> Unit,
    onSetTitleWeight: (Int) -> Unit,
    onSetHeaderFont: (ReaderFontSelection) -> Unit,
    onPickFolder: () -> Unit,
    onClose: () -> Unit,
    onBackdropClick: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    EInkDialog(
        onDismiss = onClose,
        title = "字体配置",
        onClose = onClose,
        onBackdropClick = onBackdropClick,
        showActions = false,
    ) {
        PanelTabRow(
            labels = listOf("正文", "标题", "页眉"),
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            when (selectedTab) {
                0 -> {
                    FontRow("系统无衬线", style.bodyFont == ReaderFontSelection.Sans) {
                        onSetBodyFont(ReaderFontSelection.Sans)
                    }
                    FontRow("系统衬线", style.bodyFont == ReaderFontSelection.Serif) {
                        onSetBodyFont(ReaderFontSelection.Serif)
                    }
                    FontRow("系统等宽", style.bodyFont == ReaderFontSelection.Mono) {
                        onSetBodyFont(ReaderFontSelection.Mono)
                    }
                    fontOptions.forEach { option ->
                        FontRow(option.name, style.bodyFont == ReaderFontSelection.File(option.path)) {
                            onSetBodyFont(ReaderFontSelection.File(option.path))
                        }
                    }
                    FolderPickerRow(onPickFolder = onPickFolder)
                    SliderRow(
                        label = "字重",
                        value = style.bodyWeight ?: catalog.defaultInt(Ids.BODY_WEIGHT),
                        valueRange = catalog.intRange(Ids.BODY_WEIGHT),
                        thumbLabel = { it.toString() },
                        tickStep = 100,
                        onSetValue = onSetBodyWeight,
                        markerStep = catalog.defaultStep(Ids.BODY_WEIGHT),
                    )
                }

                1 -> {
                    FontRow("跟随正文", style.titleFont == ReaderFontSelection.FollowBody) {
                        onSetTitleFont(ReaderFontSelection.FollowBody)
                    }
                    fontOptions.forEach { option ->
                        FontRow(option.name, style.titleFont == ReaderFontSelection.File(option.path)) {
                            onSetTitleFont(ReaderFontSelection.File(option.path))
                        }
                    }
                    FolderPickerRow(onPickFolder = onPickFolder)
                    SliderRow(
                        label = "标题字重",
                        value = style.titleWeight ?: catalog.defaultInt(Ids.TITLE_WEIGHT),
                        valueRange = catalog.intRange(Ids.TITLE_WEIGHT),
                        thumbLabel = { it.toString() },
                        tickStep = 100,
                        onSetValue = onSetTitleWeight,
                        markerStep = catalog.defaultStep(Ids.TITLE_WEIGHT),
                    )
                }

                else -> {
                    FontRow("跟随正文", style.headerFont == ReaderFontSelection.FollowBody) {
                        onSetHeaderFont(ReaderFontSelection.FollowBody)
                    }
                    fontOptions.forEach { option ->
                        FontRow(option.name, style.headerFont == ReaderFontSelection.File(option.path)) {
                            onSetHeaderFont(ReaderFontSelection.File(option.path))
                        }
                    }
                    FolderPickerRow(onPickFolder = onPickFolder)
                }
            }
        }
    }
}

/** 字体选项行：整行按钮，选中反白。 */
@Composable
private fun FontRow(label: String, selected: Boolean, onClick: () -> Unit) {
    EInkButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        selected = selected,
        height = 44.dp,
        role = Role.Button,
    )
}

/** 字体文件夹入口行：打开系统文件夹选择器后刷新列表。 */
@Composable
private fun FolderPickerRow(onPickFolder: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EInkSpacing.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkButton(
            text = "选择字体文件夹…",
            onClick = onPickFolder,
            height = 40.dp,
            role = Role.Button,
        )
    }
}
