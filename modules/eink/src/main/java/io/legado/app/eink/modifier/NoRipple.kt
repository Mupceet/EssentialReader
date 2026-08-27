package io.legado.app.eink.modifier

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The three pillars of "no ripple" on E-Ink.
 *
 * E-Ink displays should not show animated ripples or press-state highlights,
 * because every animated layer tends to trigger a full-screen refresh that
 * flickers. This file provides three cooperating mechanisms:
 *
 *  1. [NoRippleInteractionSource] / [rememberNoRippleInteractionSource] — an
 *     [MutableInteractionSource] that swallows every emitted interaction, so
 *     components that read interaction state never see a press/drag and thus
 *     never draw a ripple.
 *  2. [NoIndication] — an [Indication] (via [IndicationNodeFactory]) whose draw
 *     node simply calls `drawContent()` with no overlay. This is what [EInkTheme]
 *     installs as `LocalIndication` so that any component consulting the ambient
 *     indication renders nothing.
 *
 * @see io.legado.app.eink.theme.EInkTheme
 */

/**
 * A no-operation [MutableInteractionSource] that never emits interactions.
 *
 * Use it for components that don't respect the ambient `LocalIndication`
 * (e.g. anything that takes its own `interactionSource` parameter) — passing
 * this in guarantees no press/focus/etc. state is ever produced, so the
 * component draws no ripple/highlight.
 *
 * Implemented as a singleton `object` because there is no state to carry.
 */
object NoRippleInteractionSource : MutableInteractionSource {
    override val interactions: Flow<Interaction> = emptyFlow()

    override suspend fun emit(interaction: Interaction) {
        // No-op: swallow all interactions.
    }

    override fun tryEmit(interaction: Interaction): Boolean = true
}

/**
 * Returns a remembered [MutableInteractionSource] that suppresses interactions.
 *
 * Semantically equivalent to using [NoRippleInteractionSource], but yields a
 * fresh remembered instance per call site — useful when an API requires a
 * distinct instance (some libraries key internal state off instance identity)
 * or when you want to later swap in a real source without changing call sites.
 */
@Composable
fun rememberNoRippleInteractionSource(): MutableInteractionSource =
    remember {
        object : MutableInteractionSource {
            override val interactions: Flow<Interaction> = emptyFlow()

            override suspend fun emit(interaction: Interaction) {
                // No-op: swallow all interactions.
            }

            override fun tryEmit(interaction: Interaction): Boolean = true
        }
    }

/**
 * An [Indication] that draws no visual feedback whatsoever.
 *
 * Uses [IndicationNodeFactory] (the modern Modifier.Node API) to create a
 * [DrawModifierNode] that simply forwards to `drawContent()` with zero overlay.
 *
 * Installed as `LocalIndication` by [EInkTheme] to globally disable ripples.
 */
object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()

    override fun equals(other: Any?): Boolean = this === other || other is NoIndication
    override fun hashCode(): Int = NoIndication::class.hashCode()
}

/**
 * A [Modifier.Node] that implements [DrawModifierNode] by simply drawing the content.
 */
private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

/**
 * Returns a remembered [NoIndication].
 *
 * Provided for symmetry with [rememberNoRippleInteractionSource] and for
 * callers that want an explicit `Indication` value to pass into components
 * that require one.
 */
@Composable
fun rememberNoIndication(): Indication = remember { NoIndication }
