package io.legado.app.eink.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.eink.BuildConfig
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.designsystem.content.EInkHorizontalDivider
import io.legado.app.eink.designsystem.content.EInkText
import io.legado.app.eink.designsystem.control.EInkButton
import io.legado.app.eink.designsystem.theme.EInkSpacing
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 无状态「我的」页 — 首页第二个 Tab，设置入口。
 *
 * 设置项为「主信息 + 副信息」单行结构：主信息为设置名，副信息为当前
 * 值（如「字体大小 / 当前倍率 1.0x」），整行点击进入对应设置页；
 * 行为开关与宿主完整模式共享同一存储键：自动刷新 / 自动跳转最近阅读
 * 为启动期语义（写入后下次进入生效），音量键翻页、总是使用默认封面
 * 为实时语义（消费方每次读取快照）。
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
    val globalSettings = EInkEngineRegistry.globalSettings
    val fontScale = globalSettings.fontScaleSetting
    var autoRefresh by remember { mutableStateOf(globalSettings.autoRefreshBook) }
    var defaultToRead by remember { mutableStateOf(globalSettings.defaultToRead) }
    var volumeKeyPage by remember { mutableStateOf(globalSettings.volumeKeyPage) }
    Column(modifier = Modifier.fillMaxSize()) {
        MineEntry(
            label = "字体大小",
            sublabel = "当前倍率 ${(fontScale ?: FONT_SCALE_NEUTRAL) / 10f}x",
            onClick = onOpenFontScale
        )
        EInkHorizontalDivider()
        MineToggleRow(
            label = "自动刷新",
            description = "打开软件时自动更新书籍",
            checked = autoRefresh,
            onToggle = {
                val next = !autoRefresh
                globalSettings.autoRefreshBook = next
                autoRefresh = next
            }
        )
        EInkHorizontalDivider()
        MineToggleRow(
            label = "自动跳转最近阅读",
            description = "关闭后默认打开书架",
            checked = defaultToRead,
            onToggle = {
                val next = !defaultToRead
                globalSettings.defaultToRead = next
                defaultToRead = next
            }
        )
        EInkHorizontalDivider()
        MineToggleRow(
            label = "音量键翻页",
            description = "阅读时音量键上下翻页",
            checked = volumeKeyPage,
            onToggle = {
                val next = !volumeKeyPage
                globalSettings.volumeKeyPage = next
                volumeKeyPage = next
            }
        )
        EInkHorizontalDivider()
        MineToggleRow(
            label = "总是使用默认封面",
            description = "总是显示默认封面（不显示网络封面）",
            // 端口 getter 由宿主快照状态背书：此处读取订阅变化，切换后
            // 开关行与书架/详情可见封面立即重组，无需本地乐观状态
            checked = globalSettings.useDefaultCover,
            onToggle = {
                globalSettings.useDefaultCover = !globalSettings.useDefaultCover
            }
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

/**
 * 行为开关行：与 [MineEntry] 同款「主信息 + 副信息」结构（纯展示），
 * 右侧开/关块即开关本体（EInkButton，开启实心，样式对齐阅读页
 * ToggleRow）。
 */
@Composable
private fun MineToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EInkSpacing.m, vertical = EInkSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EInkText(
                text = label,
                style = EInkTheme.typography.bodyLarge
            )
            EInkText(
                text = description,
                style = EInkTheme.typography.bodyMedium,
                color = EInkTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = EInkSpacing.xxs)
            )
        }
        Spacer(modifier = Modifier.padding(start = EInkSpacing.s))
        // 开/关块即开关本体（EInkButton，开启实心），行内文字纯展示
        EInkButton(
            text = if (checked) "开" else "关",
            onClick = onToggle,
            modifier = Modifier.width(64.dp),
            height = 44.dp,
            selected = checked,
            role = Role.Switch
        )
    }
}
