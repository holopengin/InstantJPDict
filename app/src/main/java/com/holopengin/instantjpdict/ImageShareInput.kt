package com.holopengin.instantjpdict

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * #57: the pure math behind ingesting a shared image, kept free of Android
 * types so the JVM unit tests can pin it. The activity that hosts it is view
 * construction and cannot be unit-tested; this is the part that can.
 */
object ImageShareFit {

    /** Fit-centre placement, in the target bitmap's pixel space. */
    data class Placement(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * Largest power-of-two [android.graphics.BitmapFactory.Options.inSampleSize]
     * that keeps the decoded image's longest side under roughly twice
     * [maxSide]. The overlay renders at the view's own size, so detail finer
     * than that never reaches the screen; bound the decode instead of the
     * display.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= maxSide) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /**
     * Scale [srcW] x [srcH] to fit inside [targetW] x [targetH] preserving
     * aspect ratio, and centre it. The overlay's box coordinates are bitmap
     * pixels, so the source has to be laid out onto the exact canvas the view
     * will display; letterboxing is what keeps that mapping 1:1.
     */
    fun fitCenter(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Placement {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) {
            return Placement(0, 0, targetW.coerceAtLeast(0), targetH.coerceAtLeast(0))
        }
        val scale = min(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val width = (srcW * scale).toInt().coerceAtLeast(1)
        val height = (srcH * scale).toInt().coerceAtLeast(1)
        return Placement((targetW - width) / 2, (targetH - height) / 2, width, height)
    }

    /**
     * #78: the mapping from one composite's pixel space into the next's, for the
     * case where the CONTAINER changed size and the OCR run did not.
     *
     * The problem it solves. The overlay's box coordinates are composite pixels,
     * and the composite is composed at the container's own size so that mapping
     * is 1:1 ([ShareImageActivity.composeForScreen], [OcrOverlayView]). A quarter
     * turn of the phone with the OCR view open changes the container's size — and
     * since #78 that turn no longer re-creates the activity, so the run is KEPT
     * and its boxes must be carried into the new pixel space by hand. Carried by
     * anything other than the transform the image itself was placed with, every
     * box sits off the glyph it belongs to, silently.
     *
     * The transform, exactly. A [Placement] says where the picture was drawn and at
     * what size, so the source-space point a composite pixel `x` refers to is
     * `(x - before.left) / before.width` of the way across the picture, and the new
     * composite pixel is `after.left + that * after.width`. Rearranged, that is
     * one scale and one offset per axis:
     *
     *     scale  = after.width / before.width
     *     offset = after.left - before.left * scale
     *
     * per axis independently, so the mapping is exactly the placement arithmetic
     * including its integer truncation (the two axes land on the same scale to
     * within a pixel of rounding, since both are `min()` of the same two ratios).
     * It is UNIFORM SCALE PLUS TRANSLATION and never a rotation: the picture is a
     * photo and a turn of the phone is not a turn of the photo, so the rows and
     * columns of the image keep their direction and a landscape box stays
     * landscape ([Refit.isIdentity] is what a same-size container asks for).
     *
     * The degenerate case — a container that was never laid out, or one caught
     * mid-turn at a zero extent (a config change is exactly when that can happen):
     * an axis is transformed only when BOTH placements describe a panel of pixels on
     * it. With either side degenerate there is no correspondence to derive, so that
     * axis is the IDENTITY — no scale and no offset — and nothing on it moves.
     *
     * Gating the OFFSET as well as the scale is the part that is easy to get wrong:
     * a scale of 1 forced by a division guard, while the offset is still taken from
     * `after.left`, silently translates every box by the new container's letterbox
     * for a placement that never existed. There is nothing to be right about on that
     * axis, so the transform must not claim to move anything.
     */
    fun refit(before: Placement, after: Placement): Refit {
        val knownX = before.width > 0 && after.width > 0
        val knownY = before.height > 0 && after.height > 0
        val scaleX = if (knownX) after.width.toFloat() / before.width else 1f
        val scaleY = if (knownY) after.height.toFloat() / before.height else 1f
        return Refit(
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = if (knownX) after.left - before.left * scaleX else 0f,
            offsetY = if (knownY) after.top - before.top * scaleY else 0f,
        )
    }

    /**
     * The transform [refit] answers: a point in the previous composite's pixels in,
     * the same point in the new one out. Rounds to whole pixels, because every
     * caller is placing a view or a hit rect and nothing downstream takes a float.
     */
    data class Refit(
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float,
        val offsetY: Float,
    ) {
        fun x(value: Int): Int = (offsetX + value * scaleX).roundToInt()
        fun y(value: Int): Int = (offsetY + value * scaleY).roundToInt()

        /** Nothing to do: the container did not change size, so neither do the boxes. */
        val isIdentity: Boolean
            get() = scaleX == 1f && scaleY == 1f && offsetX == 0f && offsetY == 0f
    }
}

/**
 * #57: EXIF orientation -> the correction to apply after decoding. A camera-app
 * JPEG is routinely stored sideways with the orientation in its EXIF header;
 * the accessibility service never needed this because its input is already an
 * upright screenshot. Pure ints in, a plain description out, so the eight cases
 * are pinned by unit tests rather than by holding a phone the right way up.
 */
object ExifOrientation {

    // Values as defined by the EXIF/TIFF orientation tag.
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    /** Clockwise rotation first, then the mirror. */
    data class Correction(
        val rotationDegrees: Int,
        val flipHorizontal: Boolean,
        val flipVertical: Boolean,
    ) {
        val isIdentity: Boolean
            get() = rotationDegrees == 0 && !flipHorizontal && !flipVertical
    }

    /** Unknown or absent tags decode as already upright. */
    fun correction(orientation: Int): Correction = when (orientation) {
        FLIP_HORIZONTAL -> Correction(0, flipHorizontal = true, flipVertical = false)
        ROTATE_180 -> Correction(180, flipHorizontal = false, flipVertical = false)
        FLIP_VERTICAL -> Correction(0, flipHorizontal = false, flipVertical = true)
        TRANSPOSE -> Correction(90, flipHorizontal = true, flipVertical = false)
        ROTATE_90 -> Correction(90, flipHorizontal = false, flipVertical = false)
        TRANSVERSE -> Correction(-90, flipHorizontal = true, flipVertical = false)
        ROTATE_270 -> Correction(-90, flipHorizontal = false, flipVertical = false)
        else -> Correction(0, flipHorizontal = false, flipVertical = false)
    }
}
