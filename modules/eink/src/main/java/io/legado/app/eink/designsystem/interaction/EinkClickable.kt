package io.legado.app.eink.designsystem.interaction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.Role

/**
 * A [clickable] modifier that produces **no** visual feedback (no ripple, no
 * press-state highlight), suitable for E-Ink displays where any animated
 * overlay tends to trigger an unwanted full-screen refresh.
 *
 * Implementation: combines `indication = null` (no overlay) with a
 * [NoRippleInteractionSource] (so interaction state is never produced), which
 * is the most robust way to guarantee silence across components that read
 * either of those channels.
 *
 * Semantic metadata ([Role], [enabled], [onClickLabel]) is preserved so
 * accessibility tooling still reports the element correctly.
 */

/**
 * Self-managed variant: the modifier remembers its own interaction source.
 *
 * Use this for ordinary click targets.
 *
 * @param enabled Controls the enabled state of the element.
 * @param onClickLabel Optional semantic label for accessibility.
 * @param role Optional accessibility [Role].
 * @param onClick The callback invoked when clicked.
 */
fun Modifier.einkClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { NoRippleInteractionSource }
    clickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/**
 * External-source variant: accepts a caller-supplied [interactionSource].
 *
 * Use this when you need to observe interactions yourself (e.g. to react to
 * press/focus via a `collectIsPressedAsState` elsewhere) while still keeping
 * the click visually silent. Pass a [NoRippleInteractionSource] if you want
 * silence without observation, or a normal `remember { MutableInteractionSource() }`
 * if you intend to read state from it.
 *
 * @param interactionSource The [MutableInteractionSource] to feed into clickable.
 * @param enabled Controls the enabled state of the element.
 * @param onClickLabel Optional semantic label for accessibility.
 * @param role Optional accessibility [Role].
 * @param onClick The callback invoked when clicked.
 */
fun Modifier.einkClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    interactionSource = interactionSource,
    indication = null,
    onClick = onClick,
)
