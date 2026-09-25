package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Recognition-preparation harness: where the per-line bitmap chain spends its
 * time, and what each collapse of that chain costs in pixels.
 *
 * The chain is **copied verbatim** from `OcrEngine.processOneBatch` +
 * `recognizePpocrBatch` + `inferResizedRec` (the det-side harness,
 * `DetLetterboxParityTest`, uses the same pattern for the same reason: the
 * reference has to be measurable and comparable inside one build). Every line's
 * derived geometry (`targetW`, `sq`, `seq`) is logged next to the engine's own
 * `InferLog` line, so a divergence would be visible.
 *
 * The five tests, in the order the questions were asked:
 *
 *  * [stageSplitAndBitIdentity] — the root-cause split (which stage costs what,
 *    per page), then the same loop timed three ways: the pre-change chain, the
 *    shipped collapse (fused crop+rotate + evidence read off the page) and the
 *    rejected one-draw. Then the one-draw's per-line pixel identity against
 *    every paint/destination/geometry variant worth trying.
 *  * [netConsequenceOfPixelDifferences] — whether those pixel differences reach
 *    the rec net, behind a determinism control on the net first, because "the
 *    top-K changed" means nothing without it.
 *  * [fusedCropAndRotateIsBitExact] — the collapse that **is** admissible:
 *    crop and 90° turn in one `createBitmap(page, rect, m, filter)`, asserted
 *    bit-exact on every portrait line of every fixture.
 *  * [evidenceReadFromPageIsBitExact] — the other admissible one: the char-box
 *    evidence read off the page rather than off a crop, asserted identical.
 *  * [diagnoseMapping] — the diffing experiment behind the conclusions: where
 *    the one draw departs from `createScaledBitmap`, and that dither, the
 *    destination's alpha flag, fresh-vs-pooled and the draw form are not why.
 *
 * Fixtures: `bookpage.png` (1080x2400, 18 boxes, 12 portrait-aspect lines) plus
 * the two bench images (37 and 72 boxes).
 *
 * Run:
 * `./gradlew :app:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.RecDrawParityTest`
 */
@RunWith(AndroidJUnit4::class)
class RecDrawParityTest {

    // ─────────── fixtures ───────────

    private val instr by lazy { InstrumentationRegistry.getInstrumentation() }
    private val ctx: Context by lazy { instr.targetContext }

    private fun loadBitmap(path: String): Bitmap {
        for ((p, assets) in listOf(path to instr.context.assets, path to ctx.assets)) {
            try {
                assets.open(p).use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    if (bmp != null) return bmp
                }
            } catch (_: Exception) { }
        }
        error("fixture not found: $path")
    }

    private class Fixture(val name: String, val bmp: Bitmap) {
        override fun toString() = "$name (${bmp.width}x${bmp.height})"
    }

    private fun fixtures(): List<Fixture> = listOf(
        Fixture("bookpage.png", loadBitmap("bookpage.png")),
        Fixture("Screenshot_20260530-172718.png", loadBitmap("benchmark/Screenshot_20260530-172718.png")),
        Fixture("f5d7d08735383899.jpg", loadBitmap("benchmark/f5d7d08735383899.jpg")),
    )

    // ─────────── the copied chain ───────────

    /** One line's recognition source plus the clamped crop rect the current
     *  `processOneBatch` would have cut. A #53 rotated line's source is the
     *  unrotate warp (built eagerly here only because the legacy chain wants
     *  a bitmap). */
    private class Src(
        val page: Bitmap,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        /** Non-null for a rotated Line: the upright local frame. */
        val frame: Bitmap? = null,
    )

    private fun sourceOf(bmp: Bitmap, box: LineBox): Src? {
        val quad = box.quad
        if (quad != null) {
            val w = max(quad.localWidth.roundToInt(), 4)
            val h = max(quad.localHeight.roundToInt(), 4)
            return Src(bmp, 0, 0, w, h, warpRotatedCrop(bmp, quad))
        }
        val rect = box.rect
        val cx = max(rect.left, 0)
        val cy = max(rect.top, 0)
        val cw = minOf(bmp.width - cx, rect.width()).coerceAtLeast(1)
        val ch = minOf(bmp.height - cy, rect.height()).coerceAtLeast(1)
        if (cw < 4 || ch < 4) return null
        return Src(bmp, cx, cy, cw, ch)
    }

    /** `OcrEngine.warpRotatedCrop`, copied. */
    private fun warpRotatedCrop(src: Bitmap, quad: JpDictQuad): Bitmap? {
        val w = max(quad.localWidth.roundToInt(), 4)
        val h = max(quad.localHeight.roundToInt(), 4)
        val matrix = Matrix()
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        if (!matrix.setPolyToPoly(quad.corners(), 0, dst, 0, 4)) return null
        return try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            out
        } catch (_: Exception) {
            null
        }
    }

    /** Everything `recognizePpocrBatch` derives from a crop's size, verbatim. */
    private class Plan(
        val rotate: Boolean,
        val rw: Int,
        val rh: Int,
        val targetW: Int,
        val sq: Int,
        val modelW: Int,
        val longLine: Boolean,
    ) {
        val isStitch: Boolean get() = longLine
    }

    private val squish: Float
        get() = ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(OcrEngine.PREF_REC_SQUISH, OcrEngine.DEF_REC_SQUISH).coerceIn(0.2f, 1.0f)

    private fun planFor(cw: Int, ch: Int): Plan {
        val rotate = ch >= cw * 3 / 2
        val rw = if (rotate) ch else cw
        val rh = if (rotate) cw else ch
        val isLongHoriz = rw >= rh * 3 / 2 && (rw.toFloat() * REC_H / rh.toFloat() > LONG_LINE_GATE)
        val isLongVert = rh >= rw * 3 / 2 && (rh.toFloat() * REC_H / rw.toFloat() > LONG_LINE_GATE)
        val targetW = maxOf(4, minOf(LONG_LINE_GATE, (rw.toFloat() * REC_H / rh.toFloat()).roundToInt()))
        val squished = maxOf(8, (targetW * squish).roundToInt())
        val sq = if (squished / REC_STRIDE < 32) targetW else squished
        return Plan(rotate, rw, rh, targetW, sq, ((sq + 7) / 8) * 8, isLongHoriz || isLongVert)
    }

    /** `OcrEngine.buildRecInput`, copied, with the live `GRAY_LUT`. */
    private fun buildRecInput(pixels: IntArray, contentW: Int, targetH: Int, modelW: Int, lut: FloatArray): FloatArray {
        val inputFloats = FloatArray(1 * 3 * targetH * modelW)
        for (c in 0 until 3) {
            val cOff = c * targetH * modelW
            for (y in 0 until targetH) {
                for (x in 0 until contentW) {
                    val px = pixels[y * contentW + x]
                    val gray = lut[px shr 16 and 0xFF] + lut[256 + (px shr 8 and 0xFF)] + lut[512 + (px and 0xFF)]
                    inputFloats[cOff + y * modelW + x] = gray / 127.5f - 1f
                }
            }
        }
        return inputFloats
    }

    /** The live `GRAY_LUT`, by reflection; a literal copy only as a fallback. */
    private fun liveGrayLut(): FloatArray {
        try {
            val f = OcrEngine::class.java.getDeclaredField("GRAY_LUT").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return f.get(OcrEngine) as FloatArray
        } catch (e: Exception) {
            Log.w(TAG, "could not reflect GRAY_LUT ($e); using a local copy")
            return FloatArray(768) { i ->
                val v = (i % 256).toFloat()
                when (i / 256) { 0 -> v * 0.299f; 1 -> v * 0.587f; else -> v * 0.114f }
            }
        }
    }

    // ─────────── the two transforms under test ───────────

    private val LEGACY_STAGES = arrayOf("cropCreate", "portraitRotate", "recResize", "recGetPixels", "buildRecInput", "directBufCopy", "recycleAll")
    private val COLLAPSED_STAGES = arrayOf("oneDraw", "recGetPixels", "buildRecInput", "directBufCopy")

    private class ChainOut(val pixels: IntArray, val floats: FloatArray, val stages: DoubleArray)

    /**
     * The current chain, staged: `createBitmap(page, rect)` → optional
     * `postRotate(270)` re-create → `createScaledBitmap(.., sq, 48, true)` →
     * `getPixels` → `buildRecInput`, with every intermediate recycled the way
     * the engine recycles it.
     *
     * [fusedRotate] is the shipped variant: for a portrait crop the crop and the
     * 270° turn are one `createBitmap(page, rect, m, filter)` call (bit-exact,
     * `fusedCropAndRotateIsBitExact`), so the crop allocation and its recycle
     * disappear. A horizontal crop is unchanged either way.
     */
    private fun legacyChain(src: Src, plan: Plan, lut: FloatArray, bb: ByteBuffer, fusedRotate: Boolean = false): ChainOut {
        val s = DoubleArray(LEGACY_STAGES.size)
        val page = src.frame ?: src.page
        val fusing = fusedRotate && plan.rotate && src.frame == null
        var t0 = System.nanoTime()
        val crop: Bitmap? = when {
            src.frame != null -> src.frame
            fusing -> null // never materialised; the fused call reads the page
            else -> Bitmap.createBitmap(src.page, src.x, src.y, src.w, src.h)
        }
        s[S_CROP] = (System.nanoTime() - t0) / 1e6

        val rotated: Bitmap
        if (plan.rotate) {
            t0 = System.nanoTime()
            rotated = if (fusing) {
                Bitmap.createBitmap(page, src.x, src.y, src.w, src.h, Matrix().apply { postRotate(270f) }, true)
            } else {
                val c = checkNotNull(crop)
                Bitmap.createBitmap(c, 0, 0, c.width, c.height, Matrix().apply { postRotate(270f) }, true)
            }
            s[S_ROT] = (System.nanoTime() - t0) / 1e6
        } else {
            rotated = checkNotNull(crop)
        }

        t0 = System.nanoTime()
        val resized = Bitmap.createScaledBitmap(rotated, plan.sq, REC_H, true)
        s[S_RESIZE] = (System.nanoTime() - t0) / 1e6

        t0 = System.nanoTime()
        val pixels = IntArray(plan.sq * REC_H)
        resized.getPixels(pixels, 0, plan.sq, 0, 0, plan.sq, REC_H)
        s[S_PIXELS] = (System.nanoTime() - t0) / 1e6

        t0 = System.nanoTime()
        val floats = buildRecInput(pixels, plan.sq, REC_H, plan.modelW, lut)
        s[S_BUILD] = (System.nanoTime() - t0) / 1e6

        t0 = System.nanoTime()
        bb.clear()
        bb.asFloatBuffer().put(floats)
        bb.position(0)
        s[S_BUF] = (System.nanoTime() - t0) / 1e6

        t0 = System.nanoTime()
        resized.recycle()
        if (rotated !== crop) rotated.recycle()
        if (crop != null && crop !== page) crop.recycle()
        s[S_RECYCLE] = (System.nanoTime() - t0) / 1e6
        return ChainOut(pixels, floats, s)
    }

    /** Pooled `sq × 48` destination + its Canvas, so the collapsed path
     *  allocates nothing per line (the legacy path allocates three). */
    private val collapsedBufs = HashMap<Int, Bitmap>()

    /**
     * The matrix that maps the **page** (not the crop) into the `sq × 48` net
     * input, with the 270° portrait rotation folded in.
     *
     * The legacy chain is `createBitmap(page, rect)` → optional
     * `postRotate(270°)` re-create → `createScaledBitmap(.., sq, 48, true)`,
     * and `createScaledBitmap` builds its matrix as
     * `setScale(sq / rw, 48 / rh)` on the *rotated* frame. Composing that with
     * the rotation in page coordinates (Android's matrix order:
     * `x' = MSCALE_X·x + MSKEW_X·y + MTRANS_X`):
     *
     *  * horizontal: `dst(x',y') = page(x - srcX, y - srcY)` scaled by (a, b)
     *  * portrait: the rotation maps crop `(x,y) → (y, w-x)`, so
     *    `dst(x',y') = (a·y_crop, b·(w - x_crop))`
     */
    private fun collapsedMatrix(src: Src, plan: Plan, outW: Int, outH: Int): Matrix {
        val a = outW.toFloat() / plan.rw.toFloat()   // x' per rotated-x pixel
        val b = outH.toFloat() / plan.rh.toFloat()   // y' per rotated-y pixel
        val m = Matrix()
        if (!plan.rotate) {
            m.setScale(a, b)
            m.postTranslate(-a * src.x.toFloat(), -b * src.y.toFloat())
        } else {
            m.setValues(
                floatArrayOf(
                    0f, a, -a * src.y.toFloat(),
                    -b, 0f, b * (src.x.toFloat() + src.w.toFloat()),
                    0f, 0f, 1f,
                )
            )
        }
        return m
    }

    private fun collapsedDraw(
        src: Src,
        plan: Plan,
        lut: FloatArray,
        bb: ByteBuffer,
        paint: Paint,
        useMatrix: Boolean,
        clearFirst: Boolean,
        opaqueDest: Boolean = true,
        freshDest: Boolean = false,
    ): ChainOut {
        val s = DoubleArray(COLLAPSED_STAGES.size)
        val outW = plan.sq
        val (bmp, canvas) = recBuffer(outW, opaqueDest, freshDest)
        var t0 = System.nanoTime()
        if (clearFirst) canvas.drawColor(OPAQUE_WHITE)
        if (useMatrix) {
            canvas.drawBitmap(src.page, collapsedMatrix(src, plan, outW, REC_H), paint)
        } else {
            canvas.drawBitmap(
                src.page,
                Rect(src.x, src.y, src.x + src.w, src.y + src.h),
                RectF(0f, 0f, outW.toFloat(), REC_H.toFloat()),
                paint,
            )
        }
        s[0] = (System.nanoTime() - t0) / 1e6

        t0 = System.nanoTime()
        val pixels = IntArray(outW * REC_H)
        bmp.getPixels(pixels, 0, outW, 0, 0, outW, REC_H)
        s[1] = (System.nanoTime() - t0) / 1e6

        t0 = System.nanoTime()
        val floats = buildRecInput(pixels, outW, REC_H, plan.modelW, lut)
        s[2] = (System.nanoTime() - t0) / 1e6

        t0 = System.nanoTime()
        bb.clear()
        bb.asFloatBuffer().put(floats)
        bb.position(0)
        s[3] = (System.nanoTime() - t0) / 1e6
        if (freshDest) bmp.recycle()
        return ChainOut(pixels, floats, s)
    }

    /**
     * The char-box evidence read. The legacy chain has a live crop to read
     * from; the collapsed chain reads the same pixels straight out of the page.
     */
    private fun evidenceMsOf(src: Src, fromCrop: Boolean, scratch: IntArray?): Pair<Double, IntArray?> {
        var t0 = System.nanoTime()
        val px = try {
            if (fromCrop) {
                val page = src.frame ?: src.page
                val crop = src.frame ?: Bitmap.createBitmap(src.page, src.x, src.y, src.w, src.h)
                val arr = IntArray(crop.width * crop.height)
                crop.getPixels(arr, 0, crop.width, 0, 0, crop.width, crop.height)
                if (crop !== page) crop.recycle()
                arr
            } else {
                val arr = IntArray(src.w * src.h)
                if (src.frame != null) src.frame.getPixels(arr, 0, src.w, 0, 0, src.w, src.h)
                else src.page.getPixels(arr, 0, src.w, src.x, src.y, src.w, src.h)
                arr
            }
        } catch (_: Exception) {
            null
        }
        return (System.nanoTime() - t0) / 1e6 to px
    }

    // ─────────── 1. the split, and bit identity of the collapse ───────────

    @Test
    fun stageSplitAndBitIdentity() {
        val eng = OcrEngine(ctx)
        assertTrue("engine ready", eng.isReady())
        val lut = liveGrayLut()
        val bb = ByteBuffer.allocateDirect(3 * REC_H * 2048 * 4).order(ByteOrder.nativeOrder())
        val paintFD = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
        val paintF = Paint(Paint.FILTER_BITMAP_FLAG)
        val paintNoDither = paint(false)

        try {
            for (fx in fixtures()) {
                val boxes = eng.detectLines(fx.bmp)
                Log.i(TAG, "FIXTURE ${fx.name} ${fx.bmp.width}x${fx.bmp.height} boxes=${boxes.size} rotated=${boxes.count { it.isRotated }}")
                val lines = boxes.mapNotNull { b -> sourceOf(fx.bmp, b)?.let { Pair(b, it) } }
                val plans = lines.map { planFor(it.second.w, it.second.h) }
                lines.forEachIndexed { i, (b, s) ->
                    val p = plans[i]
                    Log.i(
                        TAG,
                        "GEOM ${fx.name} i=$i rect=${b.rect.left},${b.rect.top},${b.rect.width()}x${b.rect.height()} " +
                            "rot=${b.isRotated} crop=${s.w}x${s.h}@${s.x},${s.y} rotate=${p.rotate} " +
                            "rw=${p.rw} rh=${p.rh} targetW=${p.targetW} sq=${p.sq} seq=${p.sq / REC_STRIDE} " +
                            "stitch=${p.isStitch} frame=${s.frame != null}",
                    )
                }
                val single = lines.indices.filter { !plans[it].isStitch && !lines[it].first.isRotated }
                Log.i(TAG, "GEOM ${fx.name} singlePass=${single.size}/${lines.size} portrait=${single.count { plans[it].rotate }}")

                // ── warm: pooled buffers, first-touch pages, allocator ──
                repeat(2) {
                    single.forEach { i ->
                        val s = lines[i].second
                        val p = plans[i]
                        legacyChain(s, p, lut, bb)
                        collapsedDraw(s, p, lut, bb, paintFD, useMatrix = false, clearFirst = true)
                        collapsedDraw(s, p, lut, bb, paintNoDither, useMatrix = true, clearFirst = true)
                    }
                }

                // ── per-stage split, interleaved, min over repeats (CPU work) ──
                val legacyBest = DoubleArray(LEGACY_STAGES.size) { Double.MAX_VALUE }
                val collapsedBest = DoubleArray(COLLAPSED_STAGES.size) { Double.MAX_VALUE }
                var evidenceCropBest = Double.MAX_VALUE
                var evidencePageBest = Double.MAX_VALUE
                var evidenceSame = 0
                repeat(TIMING_RUNS) { round ->
                    val perLegacy = DoubleArray(LEGACY_STAGES.size)
                    val perCollapsed = DoubleArray(COLLAPSED_STAGES.size)
                    for (i in single) {
                        val s = lines[i].second
                        val p = plans[i]
                        val o = legacyChain(s, p, lut, bb)
                        for (k in perLegacy.indices) perLegacy[k] += o.stages[k]
                        val c = collapsedDraw(s, p, lut, bb, paintFD, useMatrix = false, clearFirst = false)
                        for (k in perCollapsed.indices) perCollapsed[k] += c.stages[k]
                    }
                    var ec = 0.0
                    var ep = 0.0
                    var same = 0
                    for (i in single) {
                        val s = lines[i].second
                        val (mCrop, pxCrop) = evidenceMsOf(s, fromCrop = true, scratch = null)
                        val (mPage, pxPage) = evidenceMsOf(s, fromCrop = false, scratch = null)
                        ec += mCrop
                        ep += mPage
                        if (pxCrop != null && pxPage != null && pxCrop.contentEquals(pxPage)) same++
                    }
                    evidenceCropBest = minOf(evidenceCropBest, ec)
                    evidencePageBest = minOf(evidencePageBest, ep)
                    evidenceSame = same
                    for (k in perLegacy.indices) legacyBest[k] = minOf(legacyBest[k], perLegacy[k])
                    for (k in perCollapsed.indices) collapsedBest[k] = minOf(collapsedBest[k], perCollapsed[k])
                    Log.i(
                        TAG,
                        "RAW ${fx.name} round=$round legacy=${perLegacy.map { "%.2f".format(it) }} " +
                            "collapsed=${perCollapsed.map { "%.2f".format(it) }} " +
                            "evCrop=${"%.2f".format(ec)} evPage=${"%.2f".format(ep)}",
                    )
                }
                var legacyTot = 0.0
                LEGACY_STAGES.forEachIndexed { k, n ->
                    legacyTot += legacyBest[k]
                    Log.i(TAG, "SPLIT ${fx.name} OLD $n min=${"%.2f".format(legacyBest[k])}ms")
                }
                Log.i(TAG, "SPLIT ${fx.name} OLD evidenceFromCrop min=${"%.2f".format(evidenceCropBest)}ms evidenceFromPage min=${"%.2f".format(evidencePageBest)}ms identicalLines=$evidenceSame/$single.size")
                Log.i(TAG, "SPLIT ${fx.name} OLD TOTAL(with evidence) min=${"%.2f".format(legacyTot + evidenceCropBest)}ms perLine=${"%.2f".format((legacyTot + evidenceCropBest) / max(1, single.size))}ms")
                var collapsedTot = 0.0
                COLLAPSED_STAGES.forEachIndexed { k, n ->
                    collapsedTot += collapsedBest[k]
                    Log.i(TAG, "SPLIT ${fx.name} NEW $n min=${"%.2f".format(collapsedBest[k])}ms")
                }
                Log.i(TAG, "SPLIT ${fx.name} NEW TOTAL(with evidence from page) min=${"%.2f".format(collapsedTot + evidencePageBest)}ms perLine=${"%.2f".format((collapsedTot + evidencePageBest) / max(1, single.size))}ms")
                Log.i(TAG, "SPLIT ${fx.name} SAVING min=${"%.2f".format(legacyTot + evidenceCropBest - collapsedTot - evidencePageBest)}ms speedup=${"%.2f".format((legacyTot + evidenceCropBest) / max(1e-9, collapsedTot + evidencePageBest))}x")

                // ── what the shipped changes are worth: the pre-change chain
                // (crop → rotate → resize), the shipped default (fused
                // crop+rotate for portrait, same resize) and the one draw, all
                // timed in the same interleaved loop ──
                val oldBest = DoubleArray(LEGACY_STAGES.size) { Double.MAX_VALUE }
                val shipBest = DoubleArray(LEGACY_STAGES.size) { Double.MAX_VALUE }
                repeat(TIMING_RUNS) {
                    val perOld = DoubleArray(LEGACY_STAGES.size)
                    val perShip = DoubleArray(LEGACY_STAGES.size)
                    for (i in single) {
                        val s = lines[i].second
                        val p = plans[i]
                        val o = legacyChain(s, p, lut, bb, fusedRotate = false)
                        for (k in perOld.indices) perOld[k] += o.stages[k]
                        val n = legacyChain(s, p, lut, bb, fusedRotate = true)
                        for (k in perShip.indices) perShip[k] += n.stages[k]
                    }
                    for (k in perOld.indices) {
                        oldBest[k] = minOf(oldBest[k], perOld[k])
                        shipBest[k] = minOf(shipBest[k], perShip[k])
                    }
                }
                var oldTot = 0.0
                var shipTot = 0.0
                LEGACY_STAGES.forEachIndexed { k, n ->
                    oldTot += oldBest[k]
                    shipTot += shipBest[k]
                    Log.i(
                        TAG,
                        "SHIP ${fx.name} $n pre=${"%.2f".format(oldBest[k])}ms shipped=${"%.2f".format(shipBest[k])}ms",
                    )
                }
                Log.i(
                    TAG,
                    "SHIP ${fx.name} TOTAL pre=${"%.2f".format(oldTot + evidenceCropBest)}ms " +
                        "shipped=${"%.2f".format(shipTot + evidencePageBest)}ms " +
                        "saved=${"%.2f".format(oldTot + evidenceCropBest - shipTot - evidencePageBest)}ms",
                )

                // Axes measured rather than assumed: the draw form (src/dst-rect
                // overload vs explicit matrix), the paint's dither flag, and the
                // destination's alpha flag — the decompiled
                // `Bitmap.createBitmap(src, …, m, filter)` allocates its
                // destination with `hasAlpha = source.hasAlpha()` and then draws
                // through `Canvas.drawBitmap(src, srcRect, dstRect, paint)`, so
                // "one draw" has to reproduce THAT destination, not a default one.
                class Cand(val tag: String, val useMatrix: Boolean, val dither: Boolean, val opaque: Boolean, val fresh: Boolean, val noFilter: Boolean = false)
                val cands = listOf(
                    Cand("rect,alpha", false, false, false, false),
                    Cand("rect,opaque", false, false, true, false),
                    Cand("rect,opaque,fresh", false, false, true, true),
                    Cand("rect,alpha,fresh", false, false, false, true),
                    Cand("rect,opaque,dither", false, true, true, false),
                    Cand("matrix,opaque", true, false, true, false),
                    Cand("matrix,opaque,fresh", true, false, true, true),
                    Cand("rect,opaque,noFilter", false, false, true, false, noFilter = true),
                )
                val exact = HashMap<String, Int>()
                val hist = HashMap<String, IntArray>()
                for (i in single) {
                    val s = lines[i].second
                    val p = plans[i]
                    val legacy = legacyChain(s, p, lut, bb)
                    for (cand in cands) {
                        val c = collapsedDraw(
                            s, p, lut, bb,
                            paint(filter = !cand.noFilter, dither = cand.dither),
                            useMatrix = cand.useMatrix,
                            clearFirst = true,
                            opaqueDest = cand.opaque,
                            freshDest = cand.fresh,
                        )
                        val d = diffI(legacy.pixels, c.pixels)
                        if (d == 0) exact[cand.tag] = (exact[cand.tag] ?: 0) + 1
                        else {
                            val h = hist.getOrPut(cand.tag) { IntArray(5) }
                            val (n, mx) = grayHist(legacy.pixels, c.pixels)
                            h[minOf(4, n)]++
                            Log.i(TAG, "BIT ${fx.name} i=$i portrait=${p.rotate} ${cand.tag} diffPix=$d/${p.sq * REC_H} maxChannel=$mx")
                        }
                    }
                }
                for (cand in cands) {
                    val n = exact[cand.tag] ?: 0
                    Log.i(TAG, "BIT ${fx.name} ${cand.tag} exact=$n/${single.size} diffBucketHist=${hist[cand.tag]?.toList()}")
                }
            }
        } finally {
            eng.close()
        }
    }

    /** Net-level consequence for any line whose pixels are not bit-equal: the
     *  collapsed draw is only admissible if the rec net's own output — the
     *  packed top-15 the CTC decode reads — is identical anyway. */
    @Test
    fun netConsequenceOfPixelDifferences() {
        val eng = OcrEngine(ctx)
        val lut = liveGrayLut()
        val bb = ByteBuffer.allocateDirect(3 * REC_H * 2048 * 4).order(ByteOrder.nativeOrder())
        val candidate = paint(filter = true)
        var rec: RecNcnn? = null
        try {
            for (fx in fixtures()) {
                val lines = eng.detectLines(fx.bmp).mapNotNull { b -> sourceOf(fx.bmp, b)?.let { Pair(b, it) } }
                var tested = 0
                var diffLines = 0
                var sameTopK = 0
                var maxLogitDiff = 0f
                var argmaxFlipsTotal = 0
                var worstPix = 0
                for ((i, line) in lines.withIndex()) {
                    val s = line.second
                    val p = planFor(s.w, s.h)
                    if (p.isStitch || lines[i].first.isRotated) continue
                    tested++
                    val legacy = legacyChain(s, p, lut, bb)
                    // Control: the same input twice. Without this, "top-K
                    // identical" is not a claim about the transform at all.
                    val r = rec ?: RecNcnn.create(ctx).also { rec = it }
                    if (r == null) { Log.w(TAG, "NET no RecNcnn"); return }
                    val a0 = r.inferTopK(legacy.floats, p.modelW, REC_H)
                    val a1 = r.inferTopK(legacy.floats, p.modelW, REC_H)
                    if (a0 == null || a1 == null || !a0.contentEquals(a1)) {
                        Log.e(TAG, "NET CONTROL ${fx.name} i=$i: the rec net is NOT deterministic — every net-level number below is void")
                        return
                    }
                    // The rect form can only express a horizontal crop; a
                    // portrait line needs the matrix form (the 270° rotation).
                    val cand = collapsedDraw(s, p, lut, bb, candidate, useMatrix = p.rotate, clearFirst = true)
                    val d = diffI(legacy.pixels, cand.pixels)
                    if (d == 0) { sameTopK++; continue }
                    diffLines++
                    val (n, mx) = grayHist(legacy.pixels, cand.pixels)
                    if (mx > worstPix) worstPix = mx
                    val a = r.inferTopK(legacy.floats, p.modelW, REC_H)
                    val b = r.inferTopK(cand.floats, p.modelW, REC_H)
                    val same = a != null && b != null && a.contentEquals(b)
                    if (same) sameTopK++
                    // An argmax flip (entry 0 of a timestep) is what changes the
                    // decoded text; a deeper reorder only reorders alternatives.
                    var argmaxFlips = 0
                    var rowMoves = 0
                    if (a != null && b != null && a.size == b.size) {
                        for (k in a.indices step 2) {
                            if (a[k] != b[k]) argmaxFlips++
                        }
                        for (k in a.indices) if (a[k] != b[k]) rowMoves++
                    }
                    argmaxFlipsTotal += argmaxFlips
                    var maxAbs = 0f
                    if (a != null && b != null && a.size == b.size) {
                        for (k in a.indices) maxAbs = max(maxAbs, abs(a[k] - b[k]))
                        if (maxAbs > maxLogitDiff) maxLogitDiff = maxAbs
                    }
                    Log.i(
                        TAG,
                        "NET ${fx.name} i=$i portrait=${p.rotate} crop=${s.w}x${s.h} sq=${p.sq} " +
                            "diffPix=$d/${p.sq * REC_H} maxGrayDiff=$mx topKIdentical=$same " +
                            "argmaxFlips=$argmaxFlips changedFloats=$rowMoves/${a?.size} maxAbsDiff=$maxAbs",
                    )
                }
                Log.i(TAG, "NET ${fx.name} SUMMARY lines=$tested pixelIdentical=${tested - diffLines} topKIdentical=$sameTopK/$tested worstGrayDiff=$worstPix argmaxFlips=$argmaxFlipsTotal maxAbsDiff=$maxLogitDiff")
            }
        } finally {
            rec?.close()
            eng.close()
        }
    }

    /**
     * Can the *crop* be folded into the rotate without moving the sampling grid?
     *
     * `rotate(createBitmap(page, rect))` and
     * `createBitmap(page, rect, postRotate(270), true)` are the same primitive
     * with a zero vs a non-zero src rect, and a 90° turn carries no scale — so
     * unlike the fused *scale* (which mixes the rect's origin into a non-unit
     * inverse, [diagnoseMapping]) this has a real chance of being bit-exact. If
     * it is, portrait lines drop one allocation and one recycle per line and
     * the pixels never move. Every portrait line of every fixture is compared,
     * not a sample.
     */
    @Test
    fun fusedCropAndRotateIsBitExact() {
        val eng = OcrEngine(ctx)
        try {
            for (fx in fixtures()) {
                var tested = 0
                var exact = 0
                var worst = 0
                for ((b, s) in eng.detectLines(fx.bmp).mapNotNull { b -> sourceOf(fx.bmp, b)?.let { b to it } }) {
                    if (!planFor(s.w, s.h).rotate) continue
                    tested++
                    // A rotated Line's source IS the frame, so the fused call
                    // has to read the frame (x=y=0), not the page's corner.
                    val srcBmp = s.frame ?: s.page
                    val crop = s.frame ?: Bitmap.createBitmap(s.page, s.x, s.y, s.w, s.h)
                    val legacy = Bitmap.createBitmap(
                        crop, 0, 0, crop.width, crop.height,
                        Matrix().apply { postRotate(270f) }, true,
                    )
                    val fused = Bitmap.createBitmap(
                        srcBmp, s.x, s.y, s.w, s.h,
                        Matrix().apply { postRotate(270f) }, true,
                    )
                    val n = legacy.width * legacy.height
                    val a = IntArray(n)
                    val c = IntArray(n)
                    legacy.getPixels(a, 0, legacy.width, 0, 0, legacy.width, legacy.height)
                    fused.getPixels(c, 0, fused.width, 0, 0, fused.width, fused.height)
                    val (d, mx) = grayHist(a, c)
                    if (d == 0 && fused.width == legacy.width && fused.height == legacy.height) {
                        exact++
                    } else {
                        Log.i(TAG, "ROT ${fx.name} box=${b.rect} crop=${s.w}x${s.h} fusedSize=${fused.width}x${fused.height} legacySize=${legacy.width}x${legacy.height} diffPix=$d/$n maxGrayDiff=$mx")
                    }
                    if (mx > worst) worst = mx
                    legacy.recycle()
                    fused.recycle()
                    if (crop !== s.frame) crop.recycle()
                }
                Log.i(TAG, "ROT ${fx.name} fusedCropAndRotate exact=$exact/$tested worstGrayDiff=$worst")
            }
        } finally {
            eng.close()
        }
    }

    /**
     * The char-box evidence read: `page.getPixels(rect)` instead of
     * `crop.getPixels(whole)`. Same pixels by construction — a 1:1
     * `drawBitmap(page, rect)` is what the crop is — and every line is
     * compared, because the evidence feeds CAP ink refinement and char-box
     * snapping, so a silent difference here would move boxes.
     */
    @Test
    fun evidenceReadFromPageIsBitExact() {
        val eng = OcrEngine(ctx)
        try {
            for (fx in fixtures()) {
                var tested = 0
                var exact = 0
                for ((_, s) in eng.detectLines(fx.bmp).mapNotNull { b -> sourceOf(fx.bmp, b)?.let { b to it } }) {
                    tested++
                    val (mCrop, a) = evidenceMsOf(s, fromCrop = true, scratch = null)
                    val (mPage, b) = evidenceMsOf(s, fromCrop = false, scratch = null)
                    if (a != null && b != null && a.contentEquals(b)) exact++
                    else Log.e(TAG, "EVIDENCE ${fx.name} ${s.w}x${s.h}@${s.x},${s.y} differs (crop=${a?.size} page=${b?.size})")
                }
                Log.i(TAG, "EVIDENCE ${fx.name} pageReadEqualsCropRead $exact/$tested")
                assertEquals("evidence read from the page must equal the crop's own pixels", tested, exact)
            }
        } finally {
            eng.close()
        }
    }

    private fun diffI(a: IntArray, b: IntArray): Int {
        if (a.size != b.size) return -1
        var n = 0
        for (i in a.indices) if (a[i] != b[i]) n++
        return n
    }

    /**
     * Where does the single draw diverge from crop-then-scale? Prints, for one
     * horizontal line:
     *  1. `1:1 draw` — the page rect drawn into a `cw × ch` buffer vs the crop
     *     itself. Equal ⇒ one draw at scale 1 reproduces `createBitmap` exactly.
     *  2. rows of the legacy `createScaledBitmap` output vs each candidate, so a
     *     shift, a flip or a filter difference is visible rather than inferred.
     *  3. the same for a *crop-then-draw* collapse (draw the page rect straight
     *     into the net input with the legacy's own scale matrix, so only the
     *     crop step is removed) — the closest possible collapse.
     */
    @Test
    fun diagnoseMapping() {
        val eng = OcrEngine(ctx)
        val lut = liveGrayLut()
        val bb = ByteBuffer.allocateDirect(3 * REC_H * 2048 * 4).order(ByteOrder.nativeOrder())
        val paintFD = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
        try {
            val fx = fixtures().first()
            val lines = eng.detectLines(fx.bmp).mapNotNull { b -> sourceOf(fx.bmp, b)?.let { Pair(b, it) } }
            for (lineIdx in listOf(0, 1)) {
                val (box, s) = lines[lineIdx]
                val p = planFor(s.w, s.h)
                Log.i(TAG, "DIAG line=$lineIdx rect=${box.rect} crop=${s.w}x${s.h}@${s.x},${s.y} portrait=${p.rotate} sq=${p.sq} modelW=${p.modelW} pageHasAlpha=${fx.bmp.hasAlpha()} cfg=${fx.bmp.config}")
                val legacy = legacyChain(s, p, lut, bb)
                val rectC = collapsedDraw(s, p, lut, bb, paintFD, useMatrix = false, clearFirst = true)
                val matC = collapsedDraw(s, p, lut, bb, paintFD, useMatrix = true, clearFirst = true)
                Log.i(TAG, "DIAG line=$lineIdx rectDiff=${diffI(legacy.pixels, rectC.pixels)} matrixDiff=${diffI(legacy.pixels, matC.pixels)}")

                // 1:1 draw of the page rect vs the crop
                val one2one = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888)
                Canvas(one2one).drawBitmap(s.page, Rect(s.x, s.y, s.x + s.w, s.y + s.h), RectF(0f, 0f, s.w.toFloat(), s.h.toFloat()), paintFD)
                val crop = Bitmap.createBitmap(s.page, s.x, s.y, s.w, s.h)
                val a1 = IntArray(s.w * s.h)
                val b1 = IntArray(s.w * s.h)
                one2one.getPixels(a1, 0, s.w, 0, 0, s.w, s.h)
                crop.getPixels(b1, 0, s.w, 0, 0, s.w, s.h)
                Log.i(TAG, "DIAG line=$lineIdx 1to1 drawVsCrop diff=${diffI(a1, b1)} (cfg1=${one2one.config} cfgCrop=${crop.config} alpha1=${one2one.hasAlpha()} alphaCrop=${crop.hasAlpha()})")
                crop.recycle()
                one2one.recycle()

                // crop-then-single-draw: the legacy's own scale matrix, source = page rect
                val crop2 = Bitmap.createBitmap(s.page, s.x, s.y, s.w, s.h)
                val cropDraw = Bitmap.createBitmap(p.sq, REC_H, Bitmap.Config.ARGB_8888, false)
                Canvas(cropDraw).drawBitmap(
                    crop2,
                    Matrix().apply { setScale(p.sq.toFloat() / s.w.toFloat(), REC_H.toFloat() / s.h.toFloat()) },
                    paint(filter = true),
                )
                val a2 = IntArray(p.sq * REC_H)
                cropDraw.getPixels(a2, 0, p.sq, 0, 0, p.sq, REC_H)
                Log.i(TAG, "DIAG line=$lineIdx cropThenDraw(noDither) diff=${diffI(legacy.pixels, a2)}")
                cropDraw.recycle()
                crop2.recycle()

                // One native call that crops AND scales: the very routine
                // `createScaledBitmap` itself calls (decompiled:
                // `createBitmap(src, 0,0,w,h, setScale(..), filter)`), but with
                // a non-zero src rect. The internal draw is the same
                // `drawBitmap(source, srcRect, dstRect, paint)`; only the src
                // rect's origin differs, and that origin is an INTEGER.
                val m = Matrix()
                val a = p.sq.toFloat() / s.w.toFloat()
                val b = REC_H.toFloat() / s.h.toFloat()
                if (p.rotate) {
                    m.setValues(
                        floatArrayOf(
                            0f, a, 0f,
                            -b, 0f, 0f,
                            0f, 0f, 1f,
                        )
                    )
                    // Legacy rotate-then-scale: dst(x',y') = (a·y, b·(w-x)) in
                    // crop-local coordinates, so the matrix is translate-free
                    // and the src rect offset is folded by createBitmap itself.
                } else {
                    m.setScale(a, b)
                }
                val fused = try {
                    Bitmap.createBitmap(s.page, s.x, s.y, s.w, s.h, m, true)
                } catch (e: Exception) {
                    Log.w(TAG, "DIAG line=$lineIdx fused threw $e")
                    null
                }
                if (fused != null) {
                    val af = IntArray(fused.width * fused.height)
                    fused.getPixels(af, 0, fused.width, 0, 0, fused.width, fused.height)
                    Log.i(TAG, "DIAG line=$lineIdx fused createBitmap(page,rect,m) size=${fused.width}x${fused.height} (want ${p.sq}x$REC_H) diff=${diffI(legacy.pixels, af)}")
                    fused.recycle()
                }

                // Same, but with the src rect at the origin and a translate in
                // the matrix (the alternative composition of the same mapping).
                val m2 = Matrix()
                if (p.rotate) {
                    m2.setValues(
                        floatArrayOf(
                            0f, a, 0f,
                            -b, 0f, b * s.w.toFloat(),
                            0f, 0f, 1f,
                        )
                    )
                } else {
                    m2.setScale(a, b)
                    m2.postTranslate(-a * s.x.toFloat(), -b * s.y.toFloat())
                }
                val fused2 = try {
                    Bitmap.createBitmap(s.page, s.x, s.y, s.w, s.h, m2, true)
                } catch (e: Exception) {
                    Log.w(TAG, "DIAG line=$lineIdx fused2 threw $e")
                    null
                }
                if (fused2 != null) {
                    val af2 = IntArray(fused2.width * fused2.height)
                    fused2.getPixels(af2, 0, fused2.width, 0, 0, fused2.width, fused2.height)
                    Log.i(TAG, "DIAG line=$lineIdx fused2 createBitmap(page,rect,m+translate) size=${fused2.width}x${fused2.height} diff=${diffI(legacy.pixels, af2)}")
                    fused2.recycle()
                }
                // And a 1:1 fused call, to prove the src-rect path is exact when
                // the matrix is the identity (the pure crop removal).
                val fusedId = Bitmap.createBitmap(s.page, s.x, s.y, s.w, s.h, null, true)
                val afId = IntArray(fusedId.width * fusedId.height)
                fusedId.getPixels(afId, 0, s.w, 0, 0, s.w, s.h)
                val cropRef = Bitmap.createBitmap(s.page, s.x, s.y, s.w, s.h)
                val afCrop = IntArray(s.w * s.h)
                cropRef.getPixels(afCrop, 0, s.w, 0, 0, s.w, s.h)
                Log.i(TAG, "DIAG line=$lineIdx fusedIdentity diff=${diffI(afCrop, afId)}")
                fusedId.recycle()
                cropRef.recycle()

                // crop then createScaledBitmap of a *fresh alloc* (the legacy
                // again) — proves the comparison is stable across runs
                val again = legacyChain(s, p, lut, bb)
                Log.i(TAG, "DIAG line=$lineIdx legacyVsLegacy diff=${diffI(legacy.pixels, again.pixels)}")

                for (row in listOf(0, REC_H / 2, REC_H - 1)) {
                    fun row(px: IntArray, w: Int) = (0 until 10).joinToString(" ") { Integer.toHexString(px[row * w + it]) }
                    Log.i(TAG, "DIAG line=$lineIdx row$row legacy=${row(legacy.pixels, p.sq)}")
                    Log.i(TAG, "DIAG line=$lineIdx row$row rect   =${row(rectC.pixels, p.sq)}")
                    Log.i(TAG, "DIAG line=$lineIdx row$row matrix =${row(matC.pixels, p.sq)}")
                }
                // How big is the difference in practice? A histogram of |Δgray|.
                val hist = IntArray(9)
                for (i in legacy.pixels.indices) {
                    val dl = (legacy.pixels[i] shr 16 and 0xFF) - (rectC.pixels[i] shr 16 and 0xFF)
                    val dg = (legacy.pixels[i] shr 8 and 0xFF) - (rectC.pixels[i] shr 8 and 0xFF)
                    val db = (legacy.pixels[i] and 0xFF) - (rectC.pixels[i] and 0xFF)
                    val d = maxOf(kotlin.math.abs(dl), kotlin.math.abs(dg), kotlin.math.abs(db))
                    hist[minOf(8, d)]++
                }
                Log.i(TAG, "DIAG line=$lineIdx |Δgray| histogram (rect variant) =${hist.toList()}")
            }
        } finally {
            eng.close()
        }
    }

    /** (differing pixels, max per-channel delta) on the gray of each pixel. */
    private fun grayHist(a: IntArray, b: IntArray): Pair<Int, Int> {
        var n = 0
        var mx = 0
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            n++
            for (shift in intArrayOf(16, 8, 0)) {
                mx = max(mx, abs((a[i] shr shift and 0xFF) - (b[i] shr shift and 0xFF)))
            }
        }
        return n to mx
    }

    private fun paint(filter: Boolean, dither: Boolean = false): Paint =
        Paint((if (filter) Paint.FILTER_BITMAP_FLAG else 0) or (if (dither) Paint.DITHER_FLAG else 0))

    /** Pooled `sq × 48` destination. [opaque] mirrors what
     *  `Bitmap.createBitmap(src, …, m, filter)` builds for the resize: its
     *  decompiled `createBitmap(DisplayMetrics, w, h, config, hasAlpha, cs)` is
     *  called with `hasAlpha = source.hasAlpha()`, and an opaque page gives an
     *  *opaque* destination — not the premultiplied-alpha one a plain
     *  `createBitmap(w, h, ARGB_8888)` allocates. */
    private fun recBuffer(w: Int, opaque: Boolean, fresh: Boolean): Triple<Bitmap, Canvas, String> {
        if (fresh) {
            val bmp = Bitmap.createBitmap(w, REC_H, Bitmap.Config.ARGB_8888, opaque)
            return Triple(bmp, Canvas(bmp), "fresh")
        }
        val key = w * 2 + if (opaque) 1 else 0
        val bmp = collapsedBufs.getOrPut(key) {
            Bitmap.createBitmap(w, REC_H, Bitmap.Config.ARGB_8888, opaque)
        }
        return Triple(bmp, Canvas(bmp), "pooled")
    }

    companion object {
        private const val TAG = "RecDrawParity"
        private const val TIMING_RUNS = 5
        private const val REC_H = 48
        private const val REC_STRIDE = 8
        private const val LONG_LINE_GATE = 2000
        private const val OPAQUE_WHITE = -0x1000000
        private const val S_CROP = 0
        private const val S_ROT = 1
        private const val S_RESIZE = 2
        private const val S_PIXELS = 3
        private const val S_BUILD = 4
        private const val S_BUF = 5
        private const val S_RECYCLE = 6

        @JvmStatic
        @BeforeClass
        fun ensureNcnn() {
            RecNcnn.ensureLoaded()
        }
    }
}
