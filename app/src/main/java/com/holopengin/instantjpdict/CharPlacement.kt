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
 *    centre; punctuation and small kana are measured the same way, which puts
 *    their boxes on their real (corner-placed) ink.
 * 5. **Boxes.**  Width = advance class x em, capped at the midpoints between
 *    neighbouring centres so no box ever crosses its neighbour's centre (the
 *    hit-test is first-rect-wins), then clamped into `[0, L]`.
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
        val prof = if (pixels != null) readingProfile(pixels, cropW, cropH, isVertical) else null
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
                    if (classes[i] == OpticalClass.CENTER && spread > options.maxSpreadEm * tmpl.em) continue
                    val pull = (inkC - centers[i])
                        .coerceIn(-options.inkMaxPullEm * tmpl.em, options.inkMaxPullEm * tmpl.em)
                    val w = if (margins[i] < options.confFloor) 0.5f else 1f
                    centers[i] += w * pull
                }
            }
        }

        val boxes = ArrayList<Box>(n)
        for (i in 0 until n) {
            var half = 0.5f * units[i] * tmpl.em
            if (i > 0) {
                val d = centers[i] - centers[i - 1]
                if (d > 0f) half = min(half, 0.49f * d)
            }
            if (i < n - 1) {
                val d = centers[i + 1] - centers[i]
                if (d > 0f) half = min(half, 0.49f * d)
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
