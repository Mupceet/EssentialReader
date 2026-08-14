package io.legado.app.eink.modifier

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Grayscale conversion modifiers for E-Ink.
 *
 * Enforces E-Ink guideline #1 (no color) as a safety net for any content that
 * might bypass the theme system — e.g. dynamically loaded images, web views,
 * or third-party composables.
 *
 * ## Implementation note
 *
 * The reference implementation used `drawIntoCanvas { canvas.saveLayer(...) }`
 * inside a `DrawModifier`, which allocated a fresh [androidx.compose.ui.graphics.Rect]
 * and [androidx.compose.ui.graphics.Paint] on **every frame**. This version
 * instead uses [Modifier.graphicsLayer] with its `colorFilter` slot. The color
 * matrix/filter are constructed and cached once at class-init, and
 * `graphicsLayer` applies the filter through the composited layer with no
 * per-frame allocation — substantially cheaper on low-power E-Ink hardware.
 */

/**
 * Cached saturation-zero color matrix and the corresponding [ColorFilter].
 *
 * `setToSaturation(0f)` produces a matrix that maps every pixel to its
 * luminance (per the platform's luminance weights), i.e. full desaturation.
 */
private val saturationZeroMatrix: ColorMatrix = ColorMatrix().apply { setToSaturation(0f) }
private val saturationZeroFilter: ColorFilter = ColorFilter.colorMatrix(saturationZeroMatrix)

/**
 * Cached ITU-R BT.709 luminance matrix and the corresponding [ColorFilter].
 *
 * Weights: R = 0.2126, G = 0.7152, B = 0.0722. Alpha is passed through
 * unchanged. Applied identically to each channel so the result is the
 * perceived-luminance gray value of every input pixel.
 */
private val luminanceMatrix: ColorMatrix = ColorMatrix(
    floatArrayOf(
        0.2126f, 0.7152f, 0.0722f, 0f, 0f, // Red channel
        0.2126f, 0.7152f, 0.0722f, 0f, 0f, // Green channel
        0.2126f, 0.7152f, 0.0722f, 0f, 0f, // Blue channel
        0f, 0f, 0f, 1f, 0f,                // Alpha channel (unchanged)
    ),
)
private val luminanceFilter: ColorFilter = ColorFilter.colorMatrix(luminanceMatrix)

/**
 * Desaturates the wrapped content to pure grayscale (color saturation set to 0).
 *
 * Uses the platform's built-in desaturation matrix via [ColorMatrix.setToSaturation].
 * Apply at the root of a subtree to guarantee all descendants render in gray.
 *
 * Example:
 * ```
 * Box(Modifier.grayscale()) { /* any colored content */ }
 * ```
 */
fun Modifier.grayscale(): Modifier = this.graphicsLayer { colorFilter = saturationZeroFilter }

/**
 * Desaturates the wrapped content using explicit ITU-R BT.709 luminance weights.
 *
 * Produces a perceptually-weighted grayscale that may look more natural for
 * photographic content than the plain [grayscale] variant. Same zero-allocation
 * `graphicsLayer` implementation, just a different cached matrix.
 */
fun Modifier.luminanceGrayscale(): Modifier = this.graphicsLayer { colorFilter = luminanceFilter }
