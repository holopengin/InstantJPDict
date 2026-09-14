package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * PROTOTYPE (#78, throwaway — not for main).
 *
 * The maintainer's reticle: TWO 1px horizontal lines and TWO 1px vertical lines,
 * each running the ENTIRE width/height of the camera view, straddling the centre
 * at [CROSSHAIR_GAP_FRACTION] either side. Nothing else — no brackets, no tick
 * marks, no centre dot — so the thing being judged is the alignment affordance
 * itself and not the decoration around it.
 *
 * Two lines per axis rather than one, because a single line cannot show whether
 * a long line is PARALLEL to it: a pair straddles the text and reads as a gauge.
 * Both axes get a pair because the app reads both orientations — a horizontal
 * pair brackets a horizontal line, a vertical pair brackets a vertical column,
 * and the gap is narrow (a text line's height) since the lines already span the
 * whole view, which is what makes a long line's angle visible along its length.
 *
 * Deliberately drawn in raw device pixels (`strokeWidth = 1f`, no density
 * scaling, anti-aliasing off) so "1px" means one physical pixel, which is what
 * the spec asked for. A dp-scaled "1px" line would be 2.75 px on this phone and
 * would answer a different question.
 *
 * The colour is the one judgement call here: #00FFFF is the app's own accent
 * (the OCR box borders use it), so the reticle looks like part of the product
 * rather than a debug overlay. It is a single constant — [CROSSHAIR_COLOR] — so
 * the in-hand verdict "too faint on paper" is a one-line change.
 */
class ProtoCrosshairView(context: Context) : View(context) {

    private val linePaint = Paint().apply {
        color = CROSSHAIR_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1f
        // Off on purpose: an anti-aliased 1px line is a 1px line smeared over
        // two columns of half-intensity pixels, which is not what "1px" means.
        isAntiAlias = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // width/2 and height/2 land on an exact pixel on an even-sized view
        // (1080 x 2400 here), so each line occupies exactly one pixel row/column.
        // The offsets are taken in whole pixels for the same reason.
        val cx = width / 2f
        val cy = height / 2f
        // ONE half-gap for both axes, measured from the SHORT side, so the box the four
        // lines enclose is square. Scaling each axis by its own dimension makes the gap
        // 5% of 1080 across but 5% of 2400 down — a tall rectangle on a portrait screen,
        // which is not what the reticle means.
        val gap = (minOf(width, height) * CROSSHAIR_GAP_FRACTION).toInt().toFloat()
        canvas.drawLine(0f, cy - gap, width.toFloat(), cy - gap, linePaint)
        canvas.drawLine(0f, cy + gap, width.toFloat(), cy + gap, linePaint)
        canvas.drawLine(cx - gap, 0f, cx - gap, height.toFloat(), linePaint)
        canvas.drawLine(cx + gap, 0f, cx + gap, height.toFloat(), linePaint)
    }

    companion object {
        /** Opaque cyan — the app's accent. One constant to nudge on device. */
        val CROSSHAIR_COLOR: Int = Color.parseColor("#00FFFF")

        /**
         * Half-distance between the two lines of a pair, as a fraction of the view's
         * SHORT side — the same distance on both axes, so the box the four lines
         * enclose is square. A text line at the captured scale is roughly 50-100 px
         * tall, so 0.04 is a band that brackets it: wide enough to see the line's
         * angle, narrow enough to see it is being straddled. One constant to nudge.
         */
        const val CROSSHAIR_GAP_FRACTION = 0.04f
    }
}
