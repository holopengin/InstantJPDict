package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * #78 — the viewfinder's reticle, drawn over the live preview by
 * [ProtoCameraActivity].
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

    /** Half-gap in device pixels, from the tunable. Four lines sit at ±this. */
    private var gapPx = 0f

    /** Called when the host resumes, so a slider moved in the tuning screen applies
     *  on return without restarting the camera. */
    fun reloadGap() {
        gapPx = gapPxFor(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gapPx = gapPxFor(w, h)
    }

    private fun gapPxFor(w: Int, h: Int): Float {
        val fraction = context.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREF_GAP, DEF_GAP)
        return (minOf(w, h) * fraction).toInt().toFloat()
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
        // a different fraction of each side — a tall rectangle on a portrait screen,
        // which is not what the reticle means. The value comes from the tuning slider
        // (PREF_GAP), re-read on resume, so it can be judged in the hand.
        val gap = gapPx
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
         * enclose is square. Now a tuning slider (MainActivity's tunable row), so the
         * band can be judged in the hand; this is only the default, and a stored
         * slider value (PREF_GAP) wins over it. It also sets the slider's start point
         * and the reset-to-defaults value, because both read this constant through
         * [DEF_GAP] rather than repeating the number.
         *
         * The box it draws is about 2 × this × the view's SHORT side: ~43px on this
         * device's 1080x2400 view (it was ~86px at the 0.04 default this replaced).
         */
        const val CROSSHAIR_GAP_FRACTION = 0.02f

        /** Tuning key + default for the gap slider. */
        const val PREF_GAP = "crosshair_gap"
        const val DEF_GAP = CROSSHAIR_GAP_FRACTION
    }
}
