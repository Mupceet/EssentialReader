package io.legado.app.eink.feature.home

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.R
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.navigation.EInkOperationBar
import io.legado.app.eink.designsystem.navigation.EInkOperationBarIcon
import io.legado.app.eink.designsystem.control.EInkSteppedSlider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.interaction.eInkActionColors
import io.legado.app.eink.engine.EInkEngineRegistry
import io.legado.app.eink.engine.UiSettings
import io.legado.app.eink.designsystem.interaction.rememberImmediatePressState
import io.legado.app.eink.designsystem.interaction.einkClickable
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/** 字体缩放原始设置的最小/最大值（÷10 为倍率：0.8x ~ 1.6x，宿主解析同区间）。 */
internal const val FONT_SCALE_MIN = 8
internal const val FONT_SCALE_MAX = 16

/** 未设置时的锚定档位（1.0x）；宿主对 null 的回落语义见 UiSettings 契约。 */
internal const val FONT_SCALE_NEUTRAL = 10

/**
 * 字体大小设置页（入口：「我的 → 字体大小」）。
 *
 * 自上而下：居中标题栏（返回在底部操作条）→ 示例文字预览 → 操作滑条
 * （1.0 档位上方有「默认」静态标识，不可点）→ 底部返回操作条（与目录
 * 页同款 EInkOperationBar）。
 *
 * 滑条为「抬手生效」（[EInkSteppedSlider.onValueChangeFinished]）：拖动
 * 仅预览档位，抬手写入宿主 UiSettings 并 recreate 入口 Activity 重应用
 * （fontScale 是 attach 时配置）；recreate 后整页含示例文字按新倍率重排，
 * 即所见即所得。
 */
@Composable
fun FontScaleSettingsRoute(onBack: () -> Unit) {
    val uiSettings = EInkEngineRegistry.uiSettings
    val setting = uiSettings.fontScaleSetting
    var pending by remember(setting) { mutableStateOf(setting ?: FONT_SCALE_NEUTRAL) }
    val activity = LocalContext.current as? Activity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkTheme.colorScheme.background)
    ) {
        // 顶部：居中标题（返回在底部操作条，与目录页的底部返回一致）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.Center
        ) {
            EInkText(text = "字体大小", style = EInkTheme.typography.titleLarge)
        }
        EInkHorizontalDivider()

        // 预览：抬手生效后随新倍率整页重排的示例文字
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m)
        ) {
            EInkText(text = "排版是一本书的呼吸", style = EInkTheme.typography.titleMedium)
            EInkText(
                text = "合适的字号让目光在字里行间从容行走，不必停留，也不必追赶。" +
                    "拖动下方滑条选择倍率，抬手后整个界面即按新倍率重排，" +
                    "直到这一段文字读起来最舒服为止。",
                style = EInkTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = EInkSpacing.s)
            )
        }

        // 操作滑条行：−/＋ 单档步进（点击即应用）+ 滑条抬手生效（拖动预览、
        // 抬手一次应用）；1.0 档位上方「默认」静态标识（不可点）。
        // 行高用 heightIn：滑条带标识时自身需要 48dp + 标识行高度
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.s)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EInkSpacing.xs),
        ) {
            StepGlyphButton("−", onClickLabel = "减小") {
                if (applyStep(uiSettings, setting, pending - 1) { pending = it }) {
                    activity?.recreate()
                }
            }
            EInkSteppedSlider(
                value = pending,
                onValueChange = { pending = it },
                onValueChangeFinished = {
                    if (pending != setting) {
                        uiSettings.fontScaleSetting = pending
                        activity?.recreate()
                    }
                },
                valueRange = FONT_SCALE_MIN..FONT_SCALE_MAX,
                modifier = Modifier.weight(1f),
                thumbLabel = { "${it / 10f}x" },
                tickStep = 2,
                markerStep = FONT_SCALE_NEUTRAL,
                markerLabel = "默认",
            )
            StepGlyphButton("＋", onClickLabel = "增大") {
                if (applyStep(uiSettings, setting, pending + 1) { pending = it }) {
                    activity?.recreate()
                }
            }
        }

        // 底部操作条：返回（与目录页同款）
        EInkOperationBar(
            tabs = emptyList(),
            selectedTabIndex = 0,
            onTabSelect = {},
            navigationIcon = {
                EInkOperationBarIcon(
                    icon = painterResource(R.drawable.eink_ic_arrow_back),
                    contentDescription = "返回",
                    onClick = onBack
                )
            }
        )
    }
}

/** ± 单档步进：越界钳制；与当前生效值相同则不写不刷，返回是否写入（写入方 recreate）。 */
private fun applyStep(
    uiSettings: UiSettings,
    setting: Int?,
    target: Int,
    onPreview: (Int) -> Unit,
): Boolean {
    val next = target.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
    onPreview(next)
    if (next == setting) {
        return false
    }
    uiSettings.fontScaleSetting = next
    return true
}

/** 步进按钮（−/＋）：按压反色（共享配色解析 + 120ms 最短保持，规范 §35）。 */
@Composable
private fun StepGlyphButton(
    glyph: String,
    onClickLabel: String,
    onClick: () -> Unit
) {
    val press = rememberImmediatePressState()
    val colors = eInkActionColors(pressed = press.isPressed)
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(press.modifier)
            .background(colors.containerColor)
            .einkClickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        EInkText(
            text = glyph,
            style = EInkTheme.typography.titleLarge,
            color = colors.contentColor
        )
    }
}
