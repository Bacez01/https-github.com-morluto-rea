package com.miniprojector

import kotlin.math.max
import kotlin.math.min

/** Scale, mirror and offset applied to the projected picture. */
data class StageTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * Pure maths for the projection screen, kept apart from Android so it can be unit tested.
 *
 * A shoebox projector's lens turns the picture upside down. Its left and right stay
 * correct on the wall, because the screen faces the wall while the audience faces it
 * too. That is why the app flips vertically by default and leaves the horizontal flip off.
 */
object ProjectionMath {
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 4f
    const val MAX_KEYSTONE = 30f

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun clampKeystone(degrees: Float): Float = degrees.coerceIn(-MAX_KEYSTONE, MAX_KEYSTONE)

    /** Mirrors and zooms the stage. The offset stays in screen pixels, so dragging follows the finger. */
    fun stageTransform(
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        zoom: Float,
        panX: Float,
        panY: Float,
    ): StageTransform {
        val z = clampZoom(zoom)
        return StageTransform(
            scaleX = if (flipHorizontal) -z else z,
            scaleY = if (flipVertical) -z else z,
            translationX = panX,
            translationY = panY,
        )
    }

    /**
     * A TextureView stretches the video over its whole area. Returns the extra
     * scale (about the view centre) that brings the video back to its own
     * proportions, fitted inside the view.
     */
    fun videoFitScale(videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1f to 1f
        val fit = min(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        return (videoWidth * fit / viewWidth) to (videoHeight * fit / viewHeight)
    }

    /**
     * The largest power of two that shrinks the image to about the screen size.
     * Decoding a 48 MP photo at full size would run out of memory.
     */
    fun sampleSize(imageWidth: Int, imageHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (imageWidth <= 0 || imageHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        val longImage = max(imageWidth, imageHeight)
        val shortImage = min(imageWidth, imageHeight)
        val longTarget = max(targetWidth, targetHeight)
        val shortTarget = min(targetWidth, targetHeight)
        var sample = 1
        while (longImage / (sample * 2) >= longTarget && shortImage / (sample * 2) >= shortTarget) {
            sample *= 2
        }
        return sample
    }
}
