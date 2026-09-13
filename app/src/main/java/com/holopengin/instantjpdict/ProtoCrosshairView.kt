package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * PROTOTYPE (#78, throwaway — not for main).
 *
 * The maintainer's reticle: ONE 1px horizontal line and ONE 1px vertical line,
 * each running the ENTIRE width/height of the camera view, crossing at the
 * centre. Nothing else — no brackets, no tick marks, no centre dot — so the
 * thing being judged is the alignment affordance itself and not the decoration
 * around it.
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
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(0f, cy, width.toFloat(), cy, linePaint)
        canvas.drawLine(cx, 0f, cx, height.toFloat(), linePaint)
    }

    companion object {
        /** Opaque cyan — the app's accent. One constant to nudge on device. */
        val CROSSHAIR_COLOR: Int = Color.parseColor("#00FFFF")
    }
}
