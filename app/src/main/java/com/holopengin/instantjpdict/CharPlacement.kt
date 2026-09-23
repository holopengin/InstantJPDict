package com.holopengin.instantjpdict

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Research sketch: the proposed per-character box placement algorithm.
 *
 * This is a **pure-function port of the Python prototype** developed and
 * evaluated on the synthetic corpus in `tools/char_placement/` (see
 * `docs/char-placement-findings.md`).  It is deliberately not wired into
 * `OcrEngine.computeCharBoxes` yet — that is the adoption step described in
 * the findings doc — but it compiles, has no Android dependencies beyond the
 * `IntArray` pixel convention, and is pinned by
 * `app/src/test/.../CharPlacementTest.kt` against fixture output from the
 * Python reference.
 *
 * Algorithm (one pass, deterministic):
 *
 * 1. **CTC activations.**  Walk the per-timestep top-K stream with the same
 *    greedy rules the decoder uses (blank resets, repeats collapse) to attach
 *    an activation run `[runStart, runEnd]` to every decoded character.
 *    A run's centre is a far better anchor than `charCols`, which records the
 *    *first* timestep of the run plus a one-sided peak interpolation.
 * 2. **Layout template.**  Fit `centre_i = X0 + em * (P_i + u_i / 2)` by
 *    Huber-reweighted least squares over characters whose ink is centred in
 *    the em box (`opticalClass == CENTER`), where `u_i` is the advance class
 *    (1 em fullwidth, 0.5 em ASCII/halfwidth katakana) and `P_i` the
 *    cumulative advance before it.  Letter-spacing is fitted on the residuals
 *    only when mixed advance classes make the index column non-collinear.
 * 3. **Anchors.**  The template centre when the CTC run centre agrees within
 *    `max(anchorTolEm * em, anchorTolStride * stride)` (the timestep
 *    quantisation bound); the CTC centre otherwise.  This keeps the good
 *    absolute position when the fit is trustworthy and falls back to the only
 *    valid evidence when the timestep grid is too coarse for the linear model
 *    (tall crops, halfwidth runs).
 * 4. **Ink refinement.**  Build the reading-axis ink profile over the dominant
 *    cross-band (dodges ruby/neighbour lines), then move each centre to the
 *    mid-quartile of the profile inside its Voronoi window (clipped to
 *    +/- `windowEm * em` around the anchor).  Quartiles are used instead of a
 *    centroid so a neighbour's stroke leaking into the window cannot drag the
 *    centre.  A contaminated window is retried around the CTC run centre
 *    (which sits on the glyph), taken only when it measures a compact blob
 *    near the anchor: smeared punctuation/small-kana windows (spread gate)
 *    and `CENTER` windows split by a real ink valley (bimodal gate -- で's
 *    dakuten crossing the Voronoi bound put の 9.5px high on device; with the
 *    retry, -1.5px).
 * 5. **Boxes.**  Width = advance class x em, grown (only when the centre sits
 *    on its own ink, and only within that character's Voronoi window) to cover
 *    a measured ink span up to `extentGrowFrac` past the cell.
 * 6. **Final pass: boundaries, not midpoints.**  Adjacent boxes share a
 *    boundary placed in the empty ink space between the two glyphs.  A pair
 *    whose rooms do not fit first translates apart into its flanking pairs'
 *    positive slack (cap `translateMaxEm`), so translation — not a split —
 *    resolves the collision wherever the empty space allows; only the residue
 *    is split, at its emptiest point, with each box kept at least
 *    `splitFloorFrac` of the old midpoint cap.  Every box is
 *    therefore at least as wide as the midpoint cap allowed (the pass is
 *    monotone), and no box crosses its neighbour's centre.
 */
internal object CharPlacement {

    /** Blank class as the decoder hands it over (`decodeChar(0)`). */
    const val BLANK: Char = '\u3000'

    /** One timestep's top-1..K: the decoded character and its raw logit. */
    data class Step(val char: Char, val score: Float)

    /** Advance-class/optical-class knobs; defaults are the evaluated ones. */
    data class Options(
        val inkMaxPullEm: Float = 0.45f,
        val windowEm: Float = 0.6f,
        val windowStride: Float = 0.4f,
        val anchorTolEm: Float = 0.4f,
        val anchorTolStride: Float = 1.2f,
        val huberEm: Float = 0.35f,
        val minMassFrac: Float = 0.12f,
        val maxSpreadEm: Float = 0.8f,
        val confFloor: Float = 1.0f,
        val refinePasses: Int = 1,
        // Final pass (see the class doc): boundaries instead of midpoints.
        val finalPass: Boolean = true,
        val extentPadPx: Float = 1f,
        val extentFloor: Float = 0.5f,
        val extentWindowEm: Float = 0.15f,
        val extentGrowFrac: Float = 0.3f,
        val splitFloorFrac: Float = 1f,
        val minHalfPx: Float = 2f,
        // Punctuation/small-kana window fallback (see the ink pass).
        val punctSpreadFallback: Boolean = true,
        // Neighbour-blob retry for CENTER-class glyphs (the で/の dakuten case):
        // a window split by a real ink valley has its mid-quartile straddling
        // the valley, which is how a neighbour's stroke biases the pull.  See
        // [windowIsBimodal].
        val bimodalRetry: Boolean = true,
        val bimodalValleyEm: Float = 0.20f,
        val bimodalMinFrac: Float = 0.12f,
        val punctFallbackWindowEm: Float = 0.5f,
        val punctFallbackMaxEm: Float = 0.6f,
        // Translate-before-split: push overlapping pairs apart into their
        // neighbours' slack before falling back to a split.  translateMaxEm
        // is the measured peak (0.04em): small outward moves debias centres
        // the ink refinement pulled inward by the crowding while resolving
        // the fit; bigger caps buy a little cover but push centre error back
        // above the no-translate baseline.
        val translateOverlap: Boolean = true,
        val translateMaxEm: Float = 0.04f,
        val translatePasses: Int = 2,
        val translateGateCut: Boolean = false,
    )

    /** Output box in crop pixels; the cross axis is the whole crop. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Optical class: only [CENTER] glyphs anchor the layout fit. */
    enum class OpticalClass { CENTER, SMALL, PUNCT }

    private const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ"
    private const val PUNCT = "、。，．,.)）〕》」』】〙〗〟’”］(（〔《「『【〘〖〝‘“［" +
        "：；！？…‥︙︰・ー～「」『』（）[]{}〈〉《》【】゛゜ゝゞヽヾ々〆〇"

    internal fun isHalfWidth(ch: Char): Boolean {
        val cp = ch.code
        return cp <= 0x7E || cp in 0xFF61..0xFFDC
    }

    internal fun advanceUnits(ch: Char): Float = if (isHalfWidth(ch)) 0.5f else 1.0f

    internal fun opticalClass(ch: Char): OpticalClass = when {
        ch in SMALL_KANA -> OpticalClass.SMALL
        ch in PUNCT || ch.isWhitespace() -> OpticalClass.PUNCT
        else -> OpticalClass.CENTER
    }

    /** One decoded character's CTC support. */
    data class CtcChar(
        val char: Char,
        val runStart: Int,
        val runEnd: Int,
        val conf: Float,
        val margin: Float,
    )

    /**
     * Attach a run to each decoded char by replaying the greedy CTC walk over
     * the per-timestep top-K stream.  Returns a list index-aligned with
     * [text]; a char whose run cannot be found falls back to [charCols].
     */
    internal fun runsFromSteps(
        text: String,
        steps: List<List<Step>>?,
        charCols: FloatArray,
    ): List<CtcChar> {
        val out = ArrayList<CtcChar>(text.length)
        if (steps != null && steps.isNotEmpty()) {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var prev: Char = '\u0000'
            for ((t, alts) in steps.withIndex()) {
                val top = alts.firstOrNull() ?: continue
                if (top.char == BLANK) {
                    prev = '\u0000'
                    continue
                }
                if (prev == top.char) {
                    if (ends.isNotEmpty()) ends[ends.size - 1] = t
                    continue
                }
                if (out.size < text.length && text[out.size] == top.char) {
                    out.add(CtcChar(top.char, t, t, 0f, 0f))
                    starts.add(t)
                    ends.add(t)
                    prev = top.char
                } else if (ends.isNotEmpty()) {
                    ends[ends.size - 1] = t
                    prev = top.char
                }
            }
            for (i in out.indices) {
                var scoreSum = 0f
                var marginSum = 0f
                var count = 0
                for (t in starts[i]..ends[i]) {
                    val alts = steps.getOrNull(t) ?: continue
                    if (alts.isEmpty()) continue
                    scoreSum += alts[0].score
                    marginSum += alts[0].score - (alts.getOrNull(1)?.score ?: 0f)
                    count++
                }
                // The run may have been extended after the CtcChar was built;
                // write the final span back or every run stays 1 timestep.
                out[i] = out[i].copy(
                    runStart = starts[i],
                    runEnd = ends[i],
                    conf = if (count > 0) scoreSum / count else 0f,
                    margin = if (count > 0) marginSum / count else 0f,
                )
            }
        }
        if (out.size != text.length) {
            return List(text.length) { i ->
                val t = charCols.getOrElse(i) { i.toFloat() }
                CtcChar(text[i], t.toInt(), t.toInt(), 1f, 1f)
            }
        }
        return out
    }

    /** Mid-quartile centre, total mass and IQR spread of a profile slice. */
    private fun midQuartile(prof: FloatArray, lo: Float, hi: Float): Triple<Float, Float, Float> {
        val a = max(0, floor(lo).toInt())
        val b = min(prof.size, ceil(hi).toInt())
        if (b <= a) return Triple(0f, (lo + hi) / 2f, 0f)
        var mass = 0f
        for (i in a until b) mass += prof[i]
        if (mass <= 0f) return Triple(0f, (lo + hi) / 2f, 0f)
        val q1 = mass * 0.25f
        val q3 = mass * 0.75f
        var cum = 0f
        var p1 = a.toFloat()
        var p3 = a.toFloat()
        for (i in a until b) {
            cum += prof[i]
            if (p1 <= a && cum >= q1) p1 = i.toFloat()
            if (cum >= q3) { p3 = i.toFloat(); break }
        }
        return Triple(mass, (p1 + p3) / 2f, p3 - p1)
    }

    /**
     * Trimmed mass span of the profile around [centre], clipped to the limits:
     * how far a glyph's own ink extends.  A quantile span, not a floor walk --
     * the profile is smoothed, so a narrow inter-glyph gap can stay above any
     * low floor and a contiguous run would walk into the neighbour's strokes
     * (measured: that over-estimated 61% of extents and produced phantom
     * splits).  The 5% trim drops anti-aliasing tails.  Returns (centre,
     * centre) when the window holds no ink; the caller then keeps the advance
     * cell.
     */
    private fun inkSpan(
        prof: FloatArray,
        centre: Float,
        loLim: Float,
        hiLim: Float,
    ): Pair<Float, Float> {
        val a = floor(loLim.coerceIn(0f, prof.size.toFloat())).toInt()
        val b = ceil(hiLim.coerceIn(0f, prof.size.toFloat())).toInt()
        if (b - a <= 0) return centre to centre
        var mass = 0f
        for (i in a until b) mass += prof[i]
        if (mass <= 0f) return centre to centre
        val q1 = mass * 0.05f
        val q3 = mass * 0.95f
        var cum = 0f
        var lo = centre
        var hi = centre
        var haveLo = false
        for (i in a until b) {
            cum += prof[i]
            if (!haveLo && cum >= q1) { lo = i.toFloat(); haveLo = true }
            if (cum >= q3) { hi = i.toFloat(); break }
        }
        if (hi < lo) hi = lo
        return lo to hi
    }

    /**
     * Midpoint of the emptiest run inside [lo, hi], else the mass minimum
     * (ties toward the interval centre).  Only whole pixels inside [lo, hi]
     * are candidates, so the result never leaves the interval -- callers rely
     * on it for their no-regression floor.
     */
    private fun emptiestPoint(prof: FloatArray, lo: Float, hi: Float, floor: Float): Float {
        val a = ceil(lo.coerceIn(0f, prof.size.toFloat())).toInt()
        // b clamped to prof.size: Python slices prof[a:b], which reads to the
        // array end when b overshoots; `a until b` would index past it. Keeps
        // (b - a) == Python's seg.size for the cost terms below.
        val b = (floor(hi.coerceIn(0f, prof.size.toFloat())).toInt() + 1).coerceAtMost(prof.size)
        if (b <= a) return 0.5f * (lo + hi)
        var peak = 0f
        for (i in a until b) if (prof[i] > peak) peak = prof[i]
        val thr = max(floor, 0.10f * peak)
        var bestLen = 0
        var bestEnd = 0
        var cur = 0
        for (i in a until b) {
            cur = if (prof[i] <= thr) cur + 1 else 0
            if (cur > bestLen) { bestLen = cur; bestEnd = i }
        }
        if (bestLen >= 1) return bestEnd.toFloat() - (bestLen - 1) / 2f
        var best = a
        var bestCost = Float.MAX_VALUE
        for (i in a until b) {
            val cost = prof[i] + 1e-3f * abs((i - a) - (b - a) / 2f)
            if (cost < bestCost) { bestCost = cost; best = i }
        }
        return best.toFloat()
    }

    /**
     * True when a window holds two ink blobs separated by a real valley: the
     * mid-quartile then straddles the valley and lands between the glyphs
     * instead of on either.  This is how a neighbour's stroke biases the pull
     * — で's dakuten crosses the Voronoi bound into の's window and landed の
     * ~0.12em high on device.  Fires only when the valley is long enough
     * (bimodalValleyEm) and the off-anchor side holds a real share of the
     * window's mass (bimodalMinFrac).
     */
    private fun windowIsBimodal(
        prof: FloatArray,
        lo: Float,
        hi: Float,
        em: Float,
        options: Options,
    ): Boolean {
        val a = ceil(lo.coerceIn(0f, prof.size.toFloat())).toInt()
        // b clamped to prof.size — the LAST glyph's window clips to hi == L ==
        // prof.size (its Voronoi bound is the line end), so floor(hi) + 1 walks
        // one past the profile. Python's prof[a:b] slice silently caps there;
        // indexing threw IndexOutOfBoundsException, which the rec callback
        // swallowed, and the device rendered whole detected lines blank.
        val b = (floor(hi.coerceIn(0f, prof.size.toFloat())).toInt() + 1).coerceAtMost(prof.size)
        if (b - a <= 0) return false
        var total = 0f
        var peak = 0f
        for (i in a until b) {
            total += prof[i]
            if (prof[i] > peak) peak = prof[i]
        }
        if (total <= 0f) return false
        val thr = max(options.extentFloor, 0.15f * peak)
        val valleyLen = max(2, (options.bimodalValleyEm * em).toInt())
        var run = 0
        var best = 0
        var start = 0
        var bestStart = 0
        for (k in a until b) {
            if (prof[k] <= thr) {
                if (run == 0) start = k
                run++
                if (run > best) {
                    best = run
                    bestStart = start
                }
            } else {
                run = 0
            }
        }
        if (best < valleyLen) return false
        var left = 0f
        var right = 0f
        for (i in a until b) {
            when {
                i < bestStart -> left += prof[i]
                i >= bestStart + best -> right += prof[i]
            }
        }
        return min(left, right) / total >= options.bimodalMinFrac
    }

    /**
     * Push overlapping pairs apart into their neighbours' slack, in place —
     * the translate-before-split step of the final pass.
     *
     * For a pair whose required half-widths do not fit between the centres
     * (`need[i] + need[i+1] > d`), translating the two glyphs outward by the
     * deficit hands both their full room, so the boundary pass can place the
     * divider in genuinely empty space instead of splitting the contested
     * span.  Only *positive* slack of the flanking pairs may be consumed
     * (capped at [ProposedOptions.translateMaxEm]), so a move can shrink a
     * neighbour pair's interval but never make it infeasible; whatever
     * deficit remains is left for the split path.  Slack is recomputed after
     * every move, so chains resolve honestly left-to-right.
     */
    private fun translateApart(
        centers: FloatArray,
        need: FloatArray,
        inkHalf: FloatArray,
        desired: FloatArray,
        em: Float,
        options: Options,
    ) {
        val n = centers.size
        if (n < 2 || !options.translateOverlap) return
        val cap = options.translateMaxEm * em
        fun slacks(): FloatArray {
            val out = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                out[i] = (centers[i + 1] - centers[i]) - need[i] - need[i + 1]
            }
            return out
        }
        fun wouldSplitCut(i: Int): Boolean {
            for (j in i..(i + 1)) {
                if (j >= n) continue
                var h = desired[j]
                if (j > 0) h = min(h, 0.49f * (centers[j] - centers[j - 1]))
                if (j < n - 1) h = min(h, 0.49f * (centers[j + 1] - centers[j]))
                if (inkHalf[j] > h) return true
            }
            return false
        }
        repeat(max(1, options.translatePasses)) {
            var slack = slacks()
            var moved = false
            for (i in 0 until n - 1) {
                val d = centers[i + 1] - centers[i]
                val deficit = need[i] + need[i + 1] - d
                if (deficit <= 0f) continue
                if (options.translateGateCut && !wouldSplitCut(i)) continue
                val roomL = if (i > 0) min(max(slack[i - 1], 0f), cap) else 0f
                val roomR = if (i + 1 < n - 1) min(max(slack[i + 1], 0f), cap) else 0f
                val move = min(deficit, roomL + roomR)
                if (move <= 0f) continue
                val dl = move * (roomL / (roomL + roomR))
                centers[i] -= dl
                centers[i + 1] += move - dl
                slack = slacks()
                moved = true
            }
            if (!moved) return
        }
    }

    private fun weightedMedian(xs: List<Float>, ws: List<Float>): Float {
        if (xs.isEmpty()) return 0f
        val order = xs.indices.sortedBy { xs[it] }
        var total = 0f
        for (w in ws) total += w
        if (total <= 0f) return xs.sorted()[xs.size / 2]
        var cum = 0f
        for (idx in order) {
            cum += ws[idx]
            if (cum >= total / 2f) return xs[idx]
        }
        return xs[order.last()]
    }

    /** Fitted template: cell origins from (em, x0, letterSpacing). */
    private class Template(val em: Float, val x0: Float, val ls: Float)

    private fun fitTemplate(
        centers: FloatArray,
        units: FloatArray,
        classes: List<OpticalClass>,
        confs: FloatArray,
        cross: Float,
        opts: Options,
    ): Template {
        val n = centers.size
        if (n < 2) return Template(cross, centers.firstOrNull() ?: 0f, 0f)
        val pitches = ArrayList<Float>()
        val pws = ArrayList<Float>()
        for (i in 0 until n - 1) {
            val gap = centers[i + 1] - centers[i]
            val u = 0.5f * (units[i] + units[i + 1])
            if (gap > 0f && u > 0f) {
                pitches.add(gap / u)
                pws.add(max(0.2f, 0.5f * (confs[i] + confs[i + 1]) / 8f))
            }
        }
        var pitch = if (pitches.isNotEmpty()) weightedMedian(pitches, pws) else 0f
        if (pitch <= 0f) {
            val span = centers[n - 1] - centers[0]
            var unitsTotal = 0.5f * (units[0] + units[n - 1])
            for (i in 1 until n) unitsTotal += units[i]
            pitch = if (span > 0f && unitsTotal > 0f) span / unitsTotal else cross
        }

        val idx = (0 until n).filter { classes[it] == OpticalClass.CENTER }
            .ifEmpty { (0 until n).toList() }
        // Prefix sums of the advance before each char.
        val prefix = FloatArray(n)
        var acc = 0f
        for (i in 0 until n) {
            prefix[i] = acc
            acc += units[i]
        }
        val rows = idx.map { i -> floatArrayOf(prefix[i] + 0.5f * units[i], 1f) }
        val ys = idx.map { centers[it] }.toFloatArray()
        val scale0 = sqrtMeanSquare(rows.map { it[0] })
        val scale1 = sqrtMeanSquare(rows.map { it[1] })
        var em = pitch
        var x0 = ys.firstOrNull() ?: 0f
        var weights = FloatArray(idx.size) { i ->
            min(1f, max(0.15f, confs[idx[i]] / 4f))
        }
        repeat(3) {
            var a00 = 0f; var a01 = 0f; var a11 = 0f
            var b0 = 0f; var b1 = 0f
            for ((k, i) in idx.withIndex()) {
                val r0 = rows[k][0] / scale0
                val r1 = rows[k][1] / scale1
                val w = weights[k]
                a00 += w * r0 * r0
                a01 += w * r0 * r1
                a11 += w * r1 * r1
                b0 += w * r0 * ys[k]
                b1 += w * r1 * ys[k]
            }
            val det = a00 * a11 - a01 * a01
            if (abs(det) < 1e-9f) return Template(pitch, ys.firstOrNull() ?: 0f, 0f)
            em = (b0 * a11 - b1 * a01) / det / scale0
            x0 = (a00 * b1 - a01 * b0) / det / scale1
            if (!em.isFinite() || em <= 0f) return Template(pitch, ys.firstOrNull() ?: 0f, 0f)
            // Huber reweight.
            val huber = opts.huberEm * pitch
            weights = FloatArray(idx.size) { k ->
                val pred = em * rows[k][0] + x0
                val r = abs(ys[k] - pred)
                max(0.05f, min(1f, huber / max(r, 1e-6f)))
            }
        }
        em = em.coerceIn(0.6f * pitch, 1.7f * pitch).coerceIn(0.15f * cross, 3f * cross)

        // Letter-spacing: only identifiable with mixed advance classes.
        var ls = 0f
        val mixed = idx.map { units[it] }.toSet().size > 1
        if (mixed && idx.size >= 4) {
            val xs = idx.map { it.toFloat() }
            val mean = xs.average().toFloat()
            val preds = idx.map { i -> em * (prefix[i] + 0.5f * units[i]) + x0 }
            var num = 0f
            var den = 0f
            for ((k, i) in idx.withIndex()) {
                val xc = xs[k] - mean
                num += weights[k] * (ys[k] - preds[k]) * xc
                den += weights[k] * xc * xc
            }
            val ridge = (0.35f * pitch / (0.1f * pitch)) * (0.35f * pitch / (0.1f * pitch))
            val slope = if (den > 1e-9f) num / den * den / (den + ridge) else 0f
            if (abs(slope) > 0.02f * pitch) ls = slope
        }
        return Template(em, x0, ls)
    }

    private fun sqrtMeanSquare(xs: List<Float>): Float {
        var s = 0f
        for (x in xs) s += x * x
        val ms = if (xs.isEmpty()) 0f else s / xs.size
        return if (ms <= 0f) 1f else kotlin.math.sqrt(ms)
    }

    /** Place boxes; mirror of `place.py::proposed_char_boxes`. */
    fun place(
        text: String,
        charCols: FloatArray,
        seqLenTotal: Int,
        cropW: Int,
        cropH: Int,
        isVertical: Boolean,
        pixels: IntArray? = null,
        steps: List<List<Step>>? = null,
        options: Options = Options(),
    ): List<Box> {
        val n = text.length
        if (n == 0 || seqLenTotal <= 0) return emptyList()
        val L = if (isVertical) cropH.toFloat() else cropW.toFloat()
        val cross = if (isVertical) cropW.toFloat() else cropH.toFloat()
        val pxPerT = L / seqLenTotal

        val chars = runsFromSteps(text, steps, charCols)
        val units = FloatArray(n) { advanceUnits(text[it]) }
        val classes = List(n) { opticalClass(text[it]) }
        val ctcCenters = FloatArray(n) {
            (chars[it].runStart + chars[it].runEnd) / 2f * pxPerT + 0.5f * pxPerT
        }
        val confs = FloatArray(n) { chars[it].conf }
        val margins = FloatArray(n) { chars[it].margin }

        val tmpl = fitTemplate(ctcCenters, units, classes, confs, cross, options)
        val prefix = FloatArray(n)
        var acc = 0f
        for (i in 0 until n) { prefix[i] = acc; acc += units[i] }
        val origins = FloatArray(n) { tmpl.x0 + tmpl.em * prefix[it] + tmpl.ls * it }
        val template = FloatArray(n) { origins[it] + 0.5f * units[it] * tmpl.em }
        val anchors = FloatArray(n) { i ->
            val tol = max(options.anchorTolEm * tmpl.em, options.anchorTolStride * pxPerT)
            if (abs(ctcCenters[i] - template[i]) <= tol) template[i] else ctcCenters[i]
        }

        val centers = anchors.copyOf()
        // Ink pass needs a sane crop; anything degenerate keeps the template
        // (the caller's legacy fallback would do worse than the fitted cells).
        val prof = if (pixels != null && pixels.size >= cropW * cropH && cropW >= 8 && cropH >= 8) {
            readingProfile(pixels, cropW, cropH, isVertical)
        } else {
            null
        }
        if (prof != null) {
            val win = max(options.windowEm * tmpl.em, options.windowStride * pxPerT)
            val masses = FloatArray(n)
            for (i in 0 until n) {
                val lo0 = if (i == 0) 0f else 0.5f * (centers[i - 1] + centers[i])
                val hi0 = if (i == n - 1) L else 0.5f * (centers[i] + centers[i + 1])
                val lo = max(lo0, origins[i] - options.windowEm * tmpl.em)
                val hi = min(hi0, origins[i] + units[i] * tmpl.em + options.windowEm * tmpl.em)
                val (m, _, _) = midQuartile(prof.profile, max(lo, 0f), min(hi, L))
                masses[i] = m
            }
            val pos = masses.filter { it > 0f }.sorted()
            val medMass = if (pos.isEmpty()) 1f else pos[pos.size / 2]
            repeat(options.refinePasses + 1) {
                for (i in 0 until n) {
                    val lo0 = if (i == 0) 0f else 0.5f * (centers[i - 1] + centers[i])
                    val hi0 = if (i == n - 1) L else 0.5f * (centers[i] + centers[i + 1])
                    val lo = max(max(lo0, anchors[i] - win), 0f)
                    val hi = min(min(hi0, anchors[i] + win), L)
                    if (hi <= lo) continue
                    val (mass, inkC, spread) = midQuartile(prof.profile, lo, hi)
                    if (mass < max(6f, options.minMassFrac * medMass)) continue
                    val smeared = spread > options.maxSpreadEm * tmpl.em
                    var ink = inkC
                    var retry = false
                    if (smeared) {
                        if (classes[i] != OpticalClass.CENTER) {
                            // Punctuation/small kana: a neighbour's stroke can
                            // dominate their small ink window; retry around the
                            // CTC run centre (which sits on the glyph), or keep
                            // the primary measurement when the retry is off.
                            retry = options.punctSpreadFallback
                        } else {
                            continue  // smeared centre-class window: old behaviour
                        }
                    } else if (
                        classes[i] == OpticalClass.CENTER && options.bimodalRetry &&
                        windowIsBimodal(prof.profile, lo, hi, tmpl.em, options)
                    ) {
                        // Two blobs with a real valley between them: the
                        // mid-quartile straddles the valley and lands between
                        // the glyphs.  Retry around the CTC run centre, taking
                        // it only when it measures a compact blob near the
                        // anchor.
                        retry = true
                    }
                    if (retry) {
                        val winFb = max(
                            options.punctFallbackWindowEm * tmpl.em,
                            options.windowStride * pxPerT,
                        )
                        val lo2 = max(lo0, ctcCenters[i] - winFb)
                        val hi2 = min(hi0, ctcCenters[i] + winFb)
                        if (hi2 > lo2) {
                            val (mass2, inkC2, spread2) =
                                midQuartile(prof.profile, lo2, hi2)
                            if (mass2 >= max(6f, options.minMassFrac * medMass) &&
                                spread2 <= options.maxSpreadEm * tmpl.em &&
                                abs(inkC2 - anchors[i]) <=
                                options.punctFallbackMaxEm * tmpl.em
                            ) {
                                ink = inkC2
                            }
                        }
                    }
                    val pull = (ink - centers[i])
                        .coerceIn(-options.inkMaxPullEm * tmpl.em, options.inkMaxPullEm * tmpl.em)
                    val w = if (margins[i] < options.confFloor) 0.5f else 1f
                    centers[i] += w * pull
                }
            }
        }

        // Final pass: boundaries, not midpoints.  Measured ink extents decide
        // how much room each glyph wants (trusted only when the centre sits on
        // its own ink); a shared boundary per adjacent pair then goes into the
        // empty ink space between the two glyphs, or splits the contested span
        // when both cannot fit.  Every box stays at least as wide as the old
        // midpoint cap allowed.
        val desired = FloatArray(n) { 0.5f * units[it] * tmpl.em }
        val need = desired.copyOf()
        // Raw measured ink half-extents (unclamped, unpadded): the translate
        // gate needs to know what a split would actually cut.
        val inkHalf = need.copyOf()
        if (options.finalPass && prof != null) {
            for (i in 0 until n) {
                val ci = Math.round(centers[i]).coerceIn(0, prof.profile.size - 1)
                if (prof.profile[ci] <= options.extentFloor) continue
                val pad = 0.5f * units[i] * tmpl.em + options.extentWindowEm * tmpl.em
                var loLim = centers[i] - pad
                var hiLim = centers[i] + pad
                if (i > 0) loLim = max(loLim, 0.5f * (centers[i] + centers[i - 1]))
                if (i < n - 1) hiLim = min(hiLim, 0.5f * (centers[i] + centers[i + 1]))
                val (spanLo, spanHi) = inkSpan(prof.profile, centers[i], loLim, hiLim)
                inkHalf[i] = max(centers[i] - spanLo, spanHi - centers[i])
                val halfNeed =
                    max(centers[i] - spanLo, spanHi - centers[i]) + options.extentPadPx
                need[i] = halfNeed.coerceIn(
                    desired[i], desired[i] * (1f + options.extentGrowFrac)
                )
            }
        }
        if (options.finalPass) {
            // Translate first: an overlapping pair pushes apart into its
            // neighbours' positive slack, so the boundary pass below gives
            // both glyphs their full room; only the residue is split.
            translateApart(centers, need, inkHalf, desired, tmpl.em, options)
        }
        val boundaries = FloatArray(max(n - 1, 0))
        if (!options.finalPass) {
            for (i in boundaries.indices) boundaries[i] = 0.5f * (centers[i] + centers[i + 1])
        } else {
            // hOld: what the midpoint cap allowed -- the no-regression floor.
            val hOld = desired.copyOf()
            for (i in 0 until n) {
                if (i > 0) {
                    val d = centers[i] - centers[i - 1]
                    if (d > 0f) hOld[i] = min(hOld[i], 0.49f * d)
                }
                if (i < n - 1) {
                    val d = centers[i + 1] - centers[i]
                    if (d > 0f) hOld[i] = min(hOld[i], 0.49f * d)
                }
            }
            val req = FloatArray(n) { max(need[it], hOld[it]) }
            for (i in boundaries.indices) {
                val d = centers[i + 1] - centers[i]
                val mid = 0.5f * (centers[i] + centers[i + 1])
                if (d <= 0f || prof == null) { boundaries[i] = mid; continue }
                val lo = centers[i] + req[i]
                val hi = centers[i + 1] - req[i + 1]
                if (hi >= lo) {
                    boundaries[i] = emptiestPoint(prof.profile, lo, hi, options.extentFloor)
                    continue
                }
                val lo2 = centers[i] + max(options.splitFloorFrac * hOld[i], options.minHalfPx)
                val hi2 = centers[i + 1] - max(
                    options.splitFloorFrac * hOld[i + 1], options.minHalfPx
                )
                boundaries[i] = if (hi2 > lo2) {
                    emptiestPoint(prof.profile, lo2, hi2, options.extentFloor)
                } else {
                    mid
                }
            }
        }

        val boxes = ArrayList<Box>(n)
        for (i in 0 until n) {
            var half = need[i]
            if (!options.finalPass) {
                if (i > 0) {
                    val d = centers[i] - centers[i - 1]
                    if (d > 0f) half = min(half, 0.49f * d)
                }
                if (i < n - 1) {
                    val d = centers[i + 1] - centers[i]
                    if (d > 0f) half = min(half, 0.49f * d)
                }
            } else {
                if (i > 0) half = min(half, centers[i] - boundaries[i - 1])
                if (i < n - 1) half = min(half, boundaries[i] - centers[i])
            }
            half = max(half, 1f)
            var a = (centers[i] - half).coerceIn(0f, L)
            var b = (centers[i] + half).coerceIn(0f, L)
            if (b - a < 1f) {
                b = min(L, a + 1f)
                a = max(0f, b - 1f)
            }
            boxes.add(
                if (isVertical) Box(0f, a, cross, b)
                else Box(a, 0f, b, cross)
            )
        }
        return boxes
    }

    private class Profile(val profile: FloatArray, val bgLight: Boolean)

    /** Reading-axis ink profile over the dominant cross band. */
    private fun readingProfile(
        pixels: IntArray,
        cropW: Int,
        cropH: Int,
        isVertical: Boolean,
    ): Profile {
        val lum = FloatArray(cropW * cropH)
        for (i in pixels.indices) {
            val p = pixels[i]
            lum[i] = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3f
        }
        val border = ArrayList<Float>()
        var x = 0
        while (x < cropW) {
            border.add(lum[x]); border.add(lum[(cropH - 1) * cropW + x]); x += 7
        }
        var y = 0
        while (y < cropH) {
            border.add(lum[y * cropW]); border.add(lum[y * cropW + cropW - 1]); y += 7
        }
        val sorted = border.sorted()
        val bgLight = sorted[sorted.size / 2] > 128f
        fun isInk(v: Float) = if (bgLight) v < 110f else v > 145f

        // Dominant cross band: run(s) of ink mass around the cross median.
        val crossLen = if (isVertical) cropW else cropH
        val crossProf = FloatArray(crossLen)
        if (isVertical) {
            for (xx in 0 until cropW) {
                var c = 0f
                for (yy in 0 until cropH) if (isInk(lum[yy * cropW + xx])) c += 1f
                crossProf[xx] = c
            }
        } else {
            for (yy in 0 until cropH) {
                var c = 0f
                for (xx in 0 until cropW) if (isInk(lum[yy * cropW + xx])) c += 1f
                crossProf[yy] = c
            }
        }
        val crossMax = crossProf.max()
        var loF = 0f
        var hiF = 1f
        if (crossMax > 0f) {
            val thr = max(1f, 0.15f * crossMax)
            val runs = ArrayList<IntArray>()
            var start = -1
            for (i in 0 until crossLen) {
                if (crossProf[i] >= thr && start < 0) start = i
                else if (crossProf[i] < thr && start >= 0) { runs.add(intArrayOf(start, i - 1)); start = -1 }
            }
            if (start >= 0) runs.add(intArrayOf(start, crossLen - 1))
            if (runs.isNotEmpty()) {
                var cum = 0f
                val total = crossProf.sum()
                var medianPos = crossLen - 1
                for (i in 0 until crossLen) {
                    cum += crossProf[i]
                    if (cum >= total / 2f) { medianPos = i; break }
                }
                var ci = runs.indexOfFirst { medianPos in it[0]..it[1] }.let { if (it < 0) 0 else it }
                var lo = runs[ci][0]
                var hi = runs[ci][1]
                val need = 0.35f * crossLen
                while (hi - lo + 1 < need && runs.size > 1) {
                    val left = if (ci > 0) runs[ci - 1] else null
                    val right = if (ci < runs.size - 1) runs[ci + 1] else null
                    if (left == null && right == null) break
                    val gl = if (left != null) lo - left[1] else Int.MAX_VALUE
                    val gr = if (right != null) right[0] - hi else Int.MAX_VALUE
                    if (gl <= gr && left != null) { lo = left[0]; ci -= 1 }
                    else if (right != null) { hi = right[1]; ci += 1 }
                    else break
                }
                loF = lo.toFloat() / crossLen
                hiF = (hi + 1).toFloat() / crossLen
            }
        }
        val lo = (crossLen * loF).toInt()
        val hi = max((crossLen * hiF).toInt(), lo + 1)
        val profLen = if (isVertical) cropH else cropW
        val raw = FloatArray(profLen)
        if (isVertical) {
            for (yy in 0 until cropH) {
                var c = 0f
                for (xx in lo until min(hi, cropW)) if (isInk(lum[yy * cropW + xx])) c += 1f
                raw[yy] = c
            }
        } else {
            for (xx in 0 until cropW) {
                var c = 0f
                for (yy in lo until min(hi, cropH)) if (isInk(lum[yy * cropW + xx])) c += 1f
                raw[xx] = c
            }
        }
        val prof = FloatArray(profLen)
        for (i in 0 until profLen) {
            var s = 0f
            var cnt = 0
            for (k in -2..2) {
                val j = (i + k).coerceIn(0, profLen - 1)
                s += raw[j]; cnt++
            }
            prof[i] = s / cnt
        }
        return Profile(prof, bgLight)
    }
}
