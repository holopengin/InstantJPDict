package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.roundToInt

/**
 * Single View per Line that draws all glyphs directly on Canvas — replaces 3× Views per char
 * (a FrameLayout + a centred text view + a View, 5850 Views for 65×30) to reduce UI jank.
 * Handles yoko (horizontal) and tate (vertical) with true ink center over true bbox center.
 */
class LineOverlayView(
    context: Context,
    private var line: LineResult,
    private var fixedSize: Int,
    private var lineLeft: Int,
    private var lineTop: Int,
    private val onCharClick: (charIdx: Int) -> Unit
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7777")
        // #84: the setting's face (default sans), read once per line view. The
        // highlighted glyphs keep the same face and are fake-bolded in onDraw —
        // the bundled faces ship Regular only, and a BOLD request against a
        // single-font family selects the same outlines.
        typeface = OverlayFont.typeface(context)
        textSize = fixedSize * 0.90f
        isAntiAlias = true
    }
    private val bounds = Rect()
    private val refBounds = Rect()
    private val hitRects = mutableListOf<android.graphics.Rect>()
    var highlightedIndices: Set<Int> = emptySet()
        private set
    // Ink margin (#49): halfwidth ink legally overflows its 0.5em advance box
    // (by design — sizing comes from line height), and edge chars would clip
    // against the view bounds. The view is padded by [margin] on all sides;
    // addLineToResults sizes/positions the LayoutParams with the same margin
    // via [marginFor], so draw coords and hit rects just shift by [margin].
    private var margin = marginFor(fixedSize)

    /**
     * Per-character halfwidth classification, computed when the line is
     * installed and read on every redraw.
     *
     * [OcrEngine.isHalfWidth] is a UniFFI crossing — measured 4.3 ms for the 183
     * characters of one page's overlay, ~23 us a call — and [onDraw] used to ask
     * it for EVERY character on EVERY pass. A highlight, a cursor move, a pan or
     * a zoom re-draws the same line and paid the same 4.3 ms again, for a
     * classification that cannot change: the only writer of a live line's text
     * is the override path ([OcrOverlayView.replaceCharacter]), which calls
     * [updateLine] on this view immediately afterwards, so install is the one
     * point where the answer can be new.
     *
     * The cost is therefore paid once per install instead of once per glyph per
     * redraw: the draw right after an install costs what it always did, and every
     * later one is free.
     *
     * Indexed by character position in [LineResult.text] — the same characters,
     * in the same order, the draw loop walks. It is sized to the TEXT, not to the
     * boxes, because the boxes are the loop's bound: a CharBox with no
     * corresponding text (a length mismatch, which the loop already tolerates
     * with `getOrNull`) falls back to the live call rather than to a wrong
     * default, so no mismatch can change what is drawn.
     */
    private var halfWidth: BooleanArray = BooleanArray(0)

    companion object {
        /** View padding per side, in units of fixedSize (#49). 0.30 covers
         * the worst proportional-latin overflow (~0.17em/side for W/% at
         * 0.90 textSize, plus bold-highlight headroom). */
        const val INK_MARGIN_RATIO = 0.30f
        /** Halfwidth glyph trim (#49): shared line-height textSize renders
         * ASCII ~10% too large next to kanji (eyeball-calibrated on the
         * platform face). #84: the bundled faces keep it. Noto Sans JP is
         * metric-identical to the platform's Japanese sans (capHeight
         * 733/1000, 'W' 0.878em — both matching Noto Sans CJK JP), so the
         * default path is unchanged; Noto Serif JP is a serif design whose
         * Latin differs more (capHeight 729/1000, 'W' 1.053em, '1' 0.471em), so
         * its exact ASCII sizing on-device is the maintainer's eye, not a
         * number re-invented here. Applied as a center-scale so centering is
         * untouched — and it also shrinks edge overflow. */
        const val ASCII_GLYPH_SCALE = 0.9f

        /** Lower bound for the box-fit shrink (see onDraw): never draw a
         *  glyph below 85% of the line's paint size, so a too-narrow box
         *  cannot make one character visibly smaller than its neighbours. */
        const val BOX_FIT_SCALE_FLOOR = 0.85f
        fun marginFor(fixedSize: Int): Int =
            (fixedSize * INK_MARGIN_RATIO).roundToInt().coerceAtLeast(1)

        /** The per-character halfwidth cache [halfWidth] is built from — one
         *  [OcrEngine.isHalfWidth] crossing per character, positionally aligned
         *  to [text] and nothing else. Kept as a named function (rather than
         *  inlined at the two install sites) so the alignment the renderer relies
         *  on is a thing a host test can pin: it is a View field, this is not. */
        internal fun halfWidthOf(text: String): BooleanArray =
            BooleanArray(text.length) { OcrEngine.isHalfWidth(text[it]) }
    }

    init {
        // Vertical substitution lives ONLY here, never in backend text (#47):
        // per-char Minikin vert subs (ja locale). vrt2 probed on-device as a
        // no-op over vert (identical bounds on all probe chars) — vert only.
        if (line.isVertical) {
            paint.textLocale = java.util.Locale.JAPANESE
            paint.fontFeatureSettings = "'vert' 1"
        }
        updateHalfWidth()
        updateHitRects()
    }

    fun updateLine(newLine: LineResult, newFixedSize: Int, newLineLeft: Int = lineLeft, newLineTop: Int = lineTop) {
        line = newLine
        fixedSize = newFixedSize
        lineLeft = newLineLeft
        lineTop = newLineTop
        margin = marginFor(fixedSize)
        paint.textSize = fixedSize * 0.90f
        if (line.isVertical) {
            paint.textLocale = java.util.Locale.JAPANESE
            paint.fontFeatureSettings = "'vert' 1"
        } else {
            paint.textLocale = java.util.Locale.ROOT
            paint.fontFeatureSettings = null
        }
        // The text may have been corrected in place (the override path edits
        // LineResult.text and then re-installs the line here), so the cache is
        // rebuilt with the line, never carried across it.
        updateHalfWidth()
        updateHitRects()
        invalidate()
    }

    fun setHighlighted(indices: Set<Int>) {
        highlightedIndices = indices
        invalidate()
    }

    /**
     * [halfWidth] for the line's current text — one FFI call per character, on
     * the install path only (see [halfWidth] for why that is the only point at
     * which the answer can be new).
     */
    private fun updateHalfWidth() {
        halfWidth = halfWidthOf(line.text)
    }

    private fun updateHitRects() {
        hitRects.clear()
        for (box in line.charBoxes) {
            hitRects.add(android.graphics.Rect(box.left - lineLeft + margin, box.top - lineTop + margin, box.right - lineLeft + margin, box.bottom - lineTop + margin))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // View is sized to line bounds plus ink margin on all sides (parent
        // positions at lineLeft/top minus margin — see addLineToResults).
        val lineW = (line.charBoxes.maxOfOrNull { it.right } ?: 0) - (line.charBoxes.minOfOrNull { it.left } ?: 0)
        val lineH = (line.charBoxes.maxOfOrNull { it.bottom } ?: 0) - (line.charBoxes.minOfOrNull { it.top } ?: 0)
        val w = if (lineW > 0) lineW + 2 * margin else MeasureSpec.getSize(widthMeasureSpec)
        val h = if (lineH > 0) lineH + 2 * margin else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (line.charBoxes.isEmpty() || line.text.isEmpty()) return

        val refChar = "あ"
        paint.getTextBounds(refChar, 0, 1, refBounds)

        // #53: a rotated Line's glyphs turn about their own (AABB) box centre.
        // Tilt 0 takes every branch exactly as before.
        val tilt = line.tiltDeg

        for (i in line.charBoxes.indices) {
            val box = line.charBoxes[i]
            val charStr = line.text.getOrNull(i)?.toString() ?: continue
            if (charStr.isEmpty()) continue

            val boxW = box.width().coerceAtLeast(1)
            val boxH = box.height().coerceAtLeast(1)
            val viewCenterX = (box.centerX() - lineLeft + margin).toFloat()
            val viewCenterY = (box.centerY() - lineTop + margin).toFloat()

            // Measure glyph at current paint size
            paint.getTextBounds(charStr, 0, charStr.length, bounds)
            val glyphW = bounds.width().toFloat()
            val glyphH = bounds.height().toFloat()
            if (glyphW <= 0 || glyphH <= 0) continue

            // True centers
            val glyphCenterX: Float
            val glyphCenterY: Float
            if (line.isVertical) {
                glyphCenterX = (refBounds.left + refBounds.right) / 2f
                glyphCenterY = (bounds.top + bounds.bottom) / 2f
            } else {
                glyphCenterX = (bounds.left + bounds.right) / 2f
                glyphCenterY = (refBounds.top + refBounds.bottom) / 2f
            }

            var x = viewCenterX - glyphCenterX
            var y = viewCenterY - glyphCenterY

            // Scale about center if needed (thin boxes)
            val maxW = boxW * 0.92f
            val maxH = boxH * 0.92f
            var scale = 1f
            // Halfwidth ASCII sizing is driven
            // by line height (#49): the 0.5em box is an advance for
            // positioning/hit-testing, so fit against the full-em width and
            // let ink overflow symmetric bearings instead of shrinking to the
            // advance box (which halved cap height vs neighboring CJK).
            // Read from the cache built at install (2026-09-25 overlay-install
            // perf pass). A CharBox past the end of the text (the length
            // mismatch getOrNull above tolerates) falls back to the live
            // classification, so it draws exactly as before.
            val isHalf = if (i < halfWidth.size) halfWidth[i] else OcrEngine.isHalfWidth(charStr[0])
            if (line.isVertical) {
                val hLimit = if (isHalf) maxH * 2f else maxH
                if (glyphH > hLimit) scale = hLimit / glyphH.coerceAtLeast(1f)
            } else {
                val wLimit = if (isHalf) maxW * 2f else maxW
                if (glyphW > wLimit) scale = wLimit / glyphW.coerceAtLeast(1f)
            }
            // Never draw a glyph dramatically smaller than its neighbours: a
            // genuinely jammed source pair (boxes floored at 0.49 of a pitch
            // measured at 0.77em, i.e. 0.75em boxes) used to render ~30% small
            // ("まで renders small").  0.85 halves that size gap in the worst
            // case while keeping the drawn ink's overlap with the neighbour
            // to a few px (the source's own gap there is ~19px).  The clean
            // fix for those pairs is placement — full boxes via the sweep —
            // which trades position accuracy for size instead.
            scale = scale.coerceAtLeast(BOX_FIT_SCALE_FLOOR)

            val isHighlighted = highlightedIndices.contains(i)
            paint.color = if (isHighlighted) Color.YELLOW else Color.parseColor("#FF7777")
            // #84: emphasis without a second font file — same face, synthetic bold.
            paint.isFakeBoldText = isHighlighted

            // Halfwidth trim (#49): shared line-height textSize overshoots
            // ASCII ~10% next to kanji — scale about the box center (centering
            // untouched) on top of any box-fit shrink.
            val drawScale = scale * (if (isHalf) ASCII_GLYPH_SCALE else 1f)
            if (tilt != 0f) {
                canvas.save()
                canvas.rotate(tilt, viewCenterX, viewCenterY)
            }
            if (drawScale < 0.99f) {
                canvas.save()
                canvas.translate(viewCenterX, viewCenterY)
                canvas.scale(drawScale, drawScale)
                canvas.translate(-viewCenterX, -viewCenterY)
                canvas.drawText(charStr, x, y, paint)
                canvas.restore()
            } else {
                canvas.drawText(charStr, x, y, paint)
            }
            if (tilt != 0f) canvas.restore()
        }
    }

    private var downHitIdx: Int = -1
    private val tap = TapDisambiguator(0f)

    private fun slopPx(): Float {
        var slop = tap.slopPx
        if (slop <= 0f) {
            slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            tap.slopPx = slop
        }
        return slop
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                slopPx()
                val x = event.x.toInt()
                val y = event.y.toInt()
                for (i in hitRects.indices) {
                    if (hitRects[i].contains(x, y)) {
                        downHitIdx = i
                        tap.onDown(event.x, event.y)
                        // Deliberately NOT calling
                        // requestDisallowInterceptTouchEvent(true) here (#61):
                        // claiming the stream on DOWN starves the root
                        // layout's pan/pinch handling for touches that start
                        // on a character. The tap is only claimed on UP if
                        // the finger stayed within touch slop; otherwise the
                        // parent intercepts (child gets CANCEL) and gestures
                        // proceed. Tap fires synchronously on UP — no added
                        // latency vs before.
                        return true
                    }
                }
                downHitIdx = -1
                tap.cancel()
                return false
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger = pinch: abandon the tap candidate and let
                // the parent's ScaleGestureDetector own the stream.
                downHitIdx = -1
                tap.cancel()
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (downHitIdx != -1) {
                    if (tap.shouldCancelOnMove(event.x, event.y)) {
                        // Drag: abandon the tap, explicitly allow the parent
                        // to intercept so pan takes over.
                        downHitIdx = -1
                        tap.cancel()
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                    // Within slop: keep consuming so the stream stays alive,
                    // while still allowing the parent to intercept.
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (downHitIdx != -1) {
                    val idx = downHitIdx
                    downHitIdx = -1
                    val stillTap = idx in hitRects.indices &&
                        tap.isTapAtUp(event.x, event.y) &&
                        hitRects[idx].contains(event.x.toInt(), event.y.toInt())
                    tap.cancel()
                    if (stillTap) {
                        onCharClick(idx)
                    }
                    return true
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                downHitIdx = -1
                tap.cancel()
            }
        }
        return super.onTouchEvent(event)
    }
}
