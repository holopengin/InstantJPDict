package com.holopengin.instantjpdict

import kotlin.math.roundToInt

/**
 * #78: the geometry behind the viewfinder's FILL_CENTER handoff, kept free of
 * Android types so the JVM unit tests can pin it — [ProtoCameraActivity] is view
 * construction and a preview surface, and cannot be.
 *
 * The question it answers: the preview view is filled edge to edge by a frame
 * that is a different shape, so which centred slice of the frame is actually on
 * screen? [cover] returns that slice, in the FRAME's own (EXIF-upright) pixel
 * space, and the handoff crops the file to it so the recogniser reads what the
 * user saw ([ProtoCameraActivity.cropThenHandOff]).
 *
 * It is aspect-driven and nothing else: no orientation, no display rotation, no
 * insets. That is what makes it correct after a quarter turn as well as before
 * one — in a landscape hold the view is the wider shape, so the answer swaps
 * from "the sides fall off" to "the top and bottom fall off" without the code
 * having to know which hold it is in.
 *
 * The aspect it is given is the LIVE preview view's (`view.width /
 * view.height`, read on the UI thread in the capture callback), never a latched
 * one or the display's: this activity declares `configChanges`, so it is never
 * re-created on a turn and the view is re-laid out in place — the view is the
 * only thing that knows what shape the preview is actually filling.
 */
object PreviewCrop {

    /** A rect in the frame's own pixel space. */
    data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The centred region of a [frameW] x [frameH] frame that fills an
     * aspect-[viewAspect] view edge to edge — the FILL_CENTER slice.
     *
     * A degenerate frame or aspect returns the whole frame rather than an empty
     * rect: the caller can then notice it got everything back and hand over the
     * untouched capture.
     */
    fun cover(frameW: Int, frameH: Int, viewAspect: Float): Region {
        if (frameW <= 0 || frameH <= 0 || viewAspect <= 0f) {
            return Region(0, 0, frameW.coerceAtLeast(0), frameH.coerceAtLeast(0))
        }
        val frameAspect = frameW.toFloat() / frameH.toFloat()
        return if (viewAspect >= frameAspect) {
            // The view is the wider shape: the frame covers it from edge to edge and
            // the TOP AND BOTTOM fall off.
            val height = (frameW / viewAspect).roundToInt().coerceIn(1, frameH)
            Region(0, (frameH - height) / 2, frameW, height)
        } else {
            // The view is the taller shape: full height, the SIDES fall off.
            val width = (frameH * viewAspect).roundToInt().coerceIn(1, frameW)
            Region((frameW - width) / 2, 0, width, frameH)
        }
    }
}
