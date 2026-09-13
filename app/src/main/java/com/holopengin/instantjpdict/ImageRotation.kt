package com.holopengin.instantjpdict

/**
 * #57 rotation: the pure geometry behind the share activity's rotate buttons,
 * kept free of Android types so the JVM unit tests can pin it — the same shape
 * as [ImageShareFit] and [ExifOrientation], which the activity already leans on
 * because view construction itself cannot be unit-tested.
 *
 * The one thing that has to be right: a quarter turn SWAPS the image's width
 * and height. The overlay surface composes the image at the view's own size so
 * that recognition box coordinates are 1:1 with what is on screen; if the fit
 * is recomputed from the pre-rotation dimensions, every box and every
 * per-character hit rect is left computed for the old orientation — drawn off
 * the glyphs and tapping the wrong character, silently. [fitRotated] is the
 * recomputation, and it is the reason this object exists.
 */
object ImageRotation {

    /** Quarter turns clockwise after a press: cumulative, and wrapped to 0..3
     *  so four presses return to the original orientation. */
    fun turn(currentTurns: Int, clockwise: Boolean): Int =
        (normalise(currentTurns) + if (clockwise) 1 else 3) % 4

    /** Clockwise degrees for a `Bitmap` matrix carrying [turns] quarter turns.
     *  Same convention the EXIF correction uses for its ROTATE_90 tag. */
    fun degrees(turns: Int): Float = 90f * normalise(turns)

    /** Width/height of the image after [turns]: a quarter turn swaps them. */
    fun rotatedSize(width: Int, height: Int, turns: Int): Pair<Int, Int> =
        if (normalise(turns) % 2 == 0) width to height else height to width

    /**
     * Fit-centre placement of an image of [srcW] x [srcH] turned by [turns]
     * onto a [targetW] x [targetH] canvas — derived from the ROTATED size, so
     * OCR running on the composite produces boxes in the same pixel space the
     * glyphs are drawn in.
     */
    fun fitRotated(
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int,
        turns: Int,
    ): ImageShareFit.Placement {
        val (w, h) = rotatedSize(srcW, srcH, turns)
        return ImageShareFit.fitCenter(w, h, targetW, targetH)
    }

    private fun normalise(turns: Int): Int = ((turns % 4) + 4) % 4
}
