package io.legado.app.eink.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import io.legado.app.eink.designsystem.content.EInkText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.eink.BuildConfig
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.engine.EInkEngineRegistry
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 无状态「我的」页 — 首页第二个 Tab，设置入口。
 *
 * 设置项为「主信息 + 副信息」单行结构：主信息为设置名，副信息为当前
 * 值（如「字体大小 / 当前倍率 1.0x」），整行点击进入对应设置页。
 * 「完整模式」承载进入完整模式的入口（导入导出等管理功能在完整模式
 * 中完成）。无列表翻页（操作栏箭头置灰）。
 */
@Composable
internal fun MineScreen(
    onOpenFontScale: () -> Unit = {},
    onOpenFullMode: () -> Unit = {},
    onOpenThemeDebug: () -> Unit = {},
    onOpenComponentGallery: () -> Unit = {},
) {
    val fontScale = EInkEngineRegistry.uiSettings.fontScaleSetting
    Column(modifier = Modifier.fillMaxSize()) {
        MineEntry(
            label = "字体大小",
            sublabel = "当前倍率 ${(fontScale ?: FONT_SCALE_NEUTRAL) / 10f}x",
            onClick = onOpenFontScale
        )
        EInkHorizontalDivider()
        MineEntry(label = "完整模式", onClick = onOpenFullMode)
        EInkHorizontalDivider()
        if (BuildConfig.DEBUG) {
            // 调试入口仅 debug 变体展示（编译期常量，release 中整个分支被移除）
            MineEntry(label = "排版样式调试", onClick = onOpenThemeDebug)
            EInkHorizontalDivider()
            MineEntry(label = "组件预览（Design System）", onClick = onOpenComponentGallery)
            EInkHorizontalDivider()
        }
    }
}

/**
 * 设置项行：左侧主信息（设置名）+ 副信息（当前值，弱化色小字），右侧
 * ">" 跳转标识，整行点击进入设置页。
 */
@Composable
private fun MineEntry(
    label: String,
    onClick: () -> Unit,
    sublabel: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(text = label, style = EInkTheme.typography.bodyLarge)
            if (sublabel != null) {
                EInkText(
                    text = sublabel,
                    style = EInkTheme.typography.bodyMedium,
                    color = EInkTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = EInkSpacing.xxs)
                )
            }
        }
        Spacer(modifier = Modifier.padding(start = EInkSpacing.s))
        EInkText(
            text = ">",
            style = EInkTheme.typography.titleMedium,
            color = EInkTheme.colorScheme.onSurfaceVariant
        )
    }
}
