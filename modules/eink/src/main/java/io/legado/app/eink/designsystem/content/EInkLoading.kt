package io.legado.app.eink.designsystem.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * Static loading indicator for E-Ink displays.
 *
 * E-Ink must NOT use spinners or any animated indicator: every animation frame
 * forces a screen refresh, causing severe ghosting and battery drain. Instead
 * this shows a single centered text label that is drawn once and left alone
 * until the real content replaces it.
 *
 * @param text Label to display (defaults to a Chinese loading string)
 * @param modifier Modifier for the indicator
 * @param color Text color (defaults to the theme onSurfaceVariant)
 */
@Composable
fun EInkLoading(
    text: String = "加载中...",
    modifier: Modifier = Modifier,
    color: Color = EInkTheme.colorScheme.onSurfaceVariant,
    textStyle: TextStyle = EInkTheme.typography.titleMedium
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EInkText(
            text = text,
            style = textStyle,
            color = color
        )
    }
}
