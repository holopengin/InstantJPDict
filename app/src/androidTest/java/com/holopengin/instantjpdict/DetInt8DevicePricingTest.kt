package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Device pricing of the x86 study's det-int8 variants (finding 1 of
 * `docs/ncnn-perf-x86-2026-09-26.md`), on the actual Pixel 7a.
 *
 * The study measured, on x86, that a whole-graph int8 det is **-7.2%** against
 * the *fp32* the host actually runs (the fp16 flags are bit-identical no-ops
 * there, finding 3), and predicted **<= 7%, most likely 0-3%** on Arm, on the
 * argument that det is bandwidth-bound rather than MAC-bound and the Arm
 * baseline is already fp16 — so int8-over-fp16 has at most half the traffic
 * headroom int8-over-fp32 had. This prices it on the device the app ships on,
 * against the shipped fp16 model, and reports the quality gate with it: a
 * detector that is 5% faster and moves the boxes is not a result.
 *
 * Two phases, in this order (`@FixMethodOrder`):
 *
 *  * `a_detWall` — the price. Four arms: the shipped fp16 detector against
 *    `full`, `2x2` and `depthwise` int8, **all under the shipped options**,
 *    because that is the configuration they would ship with. **12 cells per
 *    arm**, and a cell is `baseline, variant, baseline` — the number reported
 *    is the variant's net time over the mean of the two baselines bracketing
 *    it. A **fresh `DetNcnn` per sample** (ncnn builds the pipeline and packs
 *    the weights per Net at create, so a reused handle carries the previous
 *    arm's warm cache), one warmup call per handle, median of three measured
 *    calls per sample. The local bracket is the whole design: this device
 *    drifts multiplicatively and hard under the load, and a one-sample-per-arm
 *    Latin square leaves a ~4 s bracket, which at the +50% baseline ramp this
 *    workload produces is four times the effect being measured. `baseSelfRatio`
 *    reports the baseline's own drift across a cell, i.e. the noise floor of
 *    the ratio. Primary number is `net_ms` — the native extract half, from the
 *    same `lastTimings()` slot the shipped tuning used — so it is comparable to
 *    the x86 `min_ms` column; the Kotlin wall is reported beside it.
 *    `maskIoU@0.3` is computed the way the study's column was, so the quality
 *    numbers are comparable across the two hosts too.
 *  * `b_quality` — the gate, through the real engine. For each model an
 *    `OcrEngine` is built and its detector swapped
 *    ([OcrEngine.replaceDetForTest]) so contour walk, unclip, furigana filter,
 *    x-overlap merge and the CTC decode all run on the variant's output: box
 *    count, greedy-matched mean/min IoU against the fp16 baseline boxes, and
 *    the decoded line count and strings. The documented gate is **mean IoU >=
 *    0.95**; it is reported, not asserted, because the arms are expected to
 *    fail it and the run's value is the measurement.
 *
 * ## The fp16 option question is settled, and the seam that priced it is gone
 *
 * This test used to carry a second phase that forced the three `use_fp16_*`
 * flags off through a process-global override in `det_create`, because
 * "does the int8 model need the shipped options changed to load" was not a
 * question the shipped build could answer. It was answered: **it does not, and
 * the shipped configuration is required rather than incidental.** Measured on
 * the same bracket as everything else, fp16 off costs **+27.6%** for `full` and
 * **+91.0%** for `2x2` against the fp16 baseline, with no quality gain at all
 * (mask IoU 0.797 vs 0.798 and 0.925 vs 0.933).
 *
 * The reason is the layer-class split, and it is the most transferable thing the
 * experiment produced: `full` is int8 in 83 of 217 layers, `2x2` in 2 of 217.
 * The fp16 flags decide what the *other* 134 (or 215) layers do — with them on
 * they get fp16 storage and A78's fp16 FMA; off, they drop to fp32 storage *and*
 * fp32 FMA. `2x2` is the clean demonstration: int8-ing two layers while turning
 * fp16 off makes it 91% slower than the model it replaced, because 215 layers
 * went from fp16 to fp32.
 *
 * So there is no option configuration in which det int8 is both fast and
 * correct, the override had no future value, and keeping it would have held
 * `ppocr_ncnn_core.{h,cpp}` byte-identical with the PC mirror
 * (`InstantJPDictDecky/accessibility_daemon/native/ppocr_ncnn/`) for a dead
 * knob. The native seam was removed with the measurement; the two Kotlin seams
 * that are worth keeping — [DetNcnn.createFromPaths] and
 * [OcrEngine.replaceDetForTest] — stayed, because they make any *future* det
 * model A/B (int8, Winograd, a different input size) a one-test change.
 *
 * Re-deriving the fp16-off numbers needs the override back, which is the
 * intended cost: it is a model/config experiment that has already been paid for.
 * The load verdict for every arm is still logged, because a variant that will
 * not load at all is a different failure from one that loads and is slow.
 *
 * **The models are not in the repo.** `det_int8/{full,2x2,depthwise}/det.*` are
 * staged under `app/src/androidTest/assets/` by whoever runs this, from
 * `tools/ncnn-exp/`'s `det_int8_variants.sh` output; every arm whose asset is
 * absent is *skipped with a log line*, so a clean checkout runs this as a no-op
 * instead of failing. Nothing here is committed.
 *
 * Run: `./gradlew :app:connectedBenchmarkAndroidTest \
 *          -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.DetInt8DevicePricingTest`
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class DetInt8DevicePricingTest {

    // ─────────── arms ───────────

    /**
     * One priced configuration. Every arm runs under the shipped `det_create`
     * options — there is no option axis, because the fp16-off end of it is a
     * measured dead end (see the class KDoc) and keeping the override alive to
     * re-measure it was not worth holding the PC mirror of the core open for.
     *
     * @param assetDir `det_int8/<assetDir>/` under the androidTest assets, or
     *   null for the shipped `PP-OCRv6_small_ncnn/det.*`.
     */
    private class Arm(
        val name: String,
        val assetDir: String?,
        val x86Ms: Double?,
        val x86MaskIou: Double?,
    ) {
        override fun toString() = name
    }

    companion object {
        private const val TAG = "DetInt8Price"

        /** 18 lines, portrait, and the fixture every other det test uses. */
        private const val FIXTURE = "bookpage.png"

        /**
         * 12 paired cells per variant arm in [a_detWall], >= the 8 the study's
         * device protocol asks for. A *cell* is `baseline, variant, baseline`:
         * the ratio is the variant against the mean of the two baselines
         * bracketing it, so the bracket is ~1 s wide instead of the ~4 s a
         * one-sample-per-arm-per-repeat Latin square leaves. That matters
         * because the device drifts hard under this load: a 6-arm x 10-repeat
         * square took the shipped fp16 detector from 207 ms to 310 ms (+50%)
         * across the run, which is 4x the whole effect being measured, and the
         * raw medians of that run were garbage while the paired ratios were
         * stable to ~2%.
         */
        private const val REPEATS = 12

        /** Seconds of settle between cells, to let the big.LITTLE cool. */
        private const val CELL_SETTLE_MS = 1200L

        /** One warmup (pipeline build + weight packing) then this many timed. */
        private const val CALLS_PER_SAMPLE = 3

        /** The binarisation the study's `mask IoU @0.3` column used. */
        private const val MASK_THRESH = 0.3f

        /**
         * The priceable arms, under the configuration they would ship with.
         *
         * These four are what `a_detWall` and `b_quality` run. The fp16-off
         * variants of `full` and `2x2` were a fifth and sixth arm and are gone
         * with the override that produced them; the numbers they gave are in
         * the class KDoc, because the conclusion they support — that the
         * shipped fp16 configuration is required, not incidental — is the
         * reason the option axis does not exist here any more.
         */
        private val ARMS = listOf(
            // x86 columns from results/det_int8_variants.tsv, for reference only.
            Arm("base-fp16", null, 255.4, 1.000),
            Arm("int8-full", "full", 238.0, 0.289),
            Arm("int8-2x2", "2x2", 246.9, 0.893),
            Arm("int8-depthwise", "depthwise", 265.0, 0.829),
        )

        private fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return Double.NaN
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }

        private fun pct(sorted: List<Double>, p: Double): Double {
            if (sorted.isEmpty()) return Double.NaN
            val i = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[i]
        }

        /** Sample standard deviation; the spread of a set of cell ratios. */
        private fun sd(xs: List<Double>): Double {
            if (xs.size < 2) return 0.0
            val m = xs.average()
            return kotlin.math.sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        }

        /** Least-squares slope of ms per sample step: the thermal trend of one
         *  arm across the whole run (0 = the arm never drifted). */
        private fun slopePerSample(xs: List<Double>): Double {
            val n = xs.size
            if (n < 2) return 0.0
            val mx = (n - 1) / 2.0
            val my = xs.average()
            var num = 0.0
            var den = 0.0
            for (i in 0 until n) {
                num += (i - mx) * (xs[i] - my)
                den += (i - mx) * (i - mx)
            }
            return if (den == 0.0) 0.0 else num / den
        }
    }

    // ─────────── fixture, geometry, model paths ───────────

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val appContext: Context get() = instr.targetContext

    private lateinit var bmp: Bitmap
    private var resizeW = 0
    private var resizeH = 0
    private var modelSize = 0
    private var padX = 0
    private var padY = 0
    private var crop = IntArray(0)
    private var modelSizeSqr = 0

    /** The shipped det, materialised where `DetNcnn.create` would put it. */
    private lateinit var shippedParam: File
    private lateinit var shippedBin: File

    private val report = mutableListOf<String>()

    private fun loadBitmap(name: String): Bitmap {
        for (base in listOf(instr.context.assets, appContext.assets)) {
            for (path in listOf(name, name.substringAfterLast('/'))) {
                try {
                    base.open(path).use { ins ->
                        val b = BitmapFactory.decodeStream(ins)
                        if (b != null) return b
                    }
                } catch (_: Exception) { }
            }
        }
        error("fixture not found: $name")
    }

    /**
     * The exact input `OcrEngine.runDetMask` builds, once: Skia resize to
     * `min(detLongSide, modelSize)` on the long side, then the resized crop's
     * pixels plus the `(modelSize - content + 1) / 2` pad Skia's half-pixel-away-
     * from-zero translate actually produces (see `DetLetterboxParityTest`). One
     * tensor for every arm, so no arm can win on preprocessing.
     */
    private fun prepareFixture() {
        bmp = loadBitmap(FIXTURE)
        modelSize = OcrEngine.DET_MODEL_SIZE.coerceIn(320, 960)
        val targetLong = minOf(OcrEngine.DEF_DET_LONG_SIDE, modelSize)
        val origW = bmp.width.toFloat()
        val origH = bmp.height.toFloat()
        val scale = targetLong.toFloat() / maxOf(origW, origH)
        resizeW = maxOf((origW * scale).roundToInt(), 32)
        resizeH = maxOf((origH * scale).roundToInt(), 32)
        padX = (modelSize - resizeW + 1) / 2
        padY = (modelSize - resizeH + 1) / 2
        modelSizeSqr = modelSize * modelSize
        val resized = Bitmap.createScaledBitmap(bmp, resizeW, resizeH, true)
        crop = IntArray(resizeW * resizeH)
        resized.getPixels(crop, 0, resizeW, 0, 0, resizeW, resizeH)
        resized.recycle()

        // The shipped detector, through the app's own materialiser, so the
        // baseline arm loads byte-identical bytes to production and nothing has
        // to be staged for it.
        shippedParam = materialiseModelAsset(appContext, "PP-OCRv6_small_ncnn/det.param", "det.param")!!
        shippedBin = materialiseModelAsset(appContext, "PP-OCRv6_small_ncnn/det.bin", "det.bin")!!
        Log.i(
            TAG,
            "PREP ${bmp.width}x${bmp.height} model=$modelSize content=${resizeW}x$resizeH " +
                "pad=($padX,$padY) shippedParam=${shippedParam.length()}B shippedBin=${shippedBin.length()}B"
        )
    }

    /** The staged variant models, copied out of the test APK's assets (the
     *  loader takes filesystem paths, not asset names). Missing ⇒ null, which
     *  is how this test skips on a clean checkout. */
    private fun stagedModel(arm: Arm): Pair<File, File>? {
        val dir = arm.assetDir ?: return shippedParam to shippedBin
        val src = "det_int8/$dir"
        val out = File(appContext.cacheDir, "det_int8/$dir").apply { mkdirs() }
        val param = File(out, "det.param")
        val bin = File(out, "det.bin")
        if (!param.isFile || !bin.isFile) {
            for ((name, target) in listOf("det.param" to param, "det.bin" to bin)) {
                try {
                    instr.context.assets.open("$src/$name").use { ins ->
                        target.outputStream().use { ins.copyTo(it) }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "SKIP arm=${arm.name} variant asset $src/$name absent ($e)")
                    return null
                }
            }
        }
        return param to bin
    }

    /**
     * The arm's detector under the shipped `det_create` options — the only
     * configuration this test knows how to ask for, and the one the finding
     * says is required.
     */
    private fun openDet(arm: Arm): DetNcnn? {
        val (param, bin) = stagedModel(arm) ?: return null
        val det = DetNcnn.createFromPaths(param.absolutePath, bin.absolutePath)
        if (det == null) {
            Log.e(TAG, "LOADFAIL arm=${arm.name} param=${param.length()}B bin=${bin.length()}B")
        }
        return det
    }

    // ─────────── phase A: the price ───────────

    private var seq = 0

    private class Sample(
        val arm: String,
        val netMs: Double,
        val wallMs: Double,
        val loadMs: Double,
    )

    /** One `baseline, variant, baseline` cell. [ratio] is the variant's net time
     *  over the mean of the two bracketing baselines, so it is a *local*
     *  A/B — [beforeOverAfter] is the baseline's own drift across the ~1 s the
     *  cell takes, which is the noise floor of the ratio. */
    private class Cell(
        val arm: String,
        val cell: Int,
        val ratio: Double,
        val beforeMs: Double,
        val midMs: Double,
        val afterMs: Double,
        val beforeOverAfter: Double,
        val loadMs: Double,
    )

    /** One arm, one sample: fresh handle, one warmup, [CALLS_PER_SAMPLE] timed
     *  calls, sample = median of their net halves. The fresh handle is not
     *  optional: ncnn builds the pipeline and packs the weights per Net at
     *  create, so a reused handle carries the previous arm's warm cache. */
    private fun sample(arm: Arm): Sample? {
        val t0 = System.nanoTime()
        val det = openDet(arm) ?: return null
        val loadMs = (System.nanoTime() - t0) / 1e6
        try {
            val w = det.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)
            if (w == null || w.size != modelSizeSqr) {
                Log.e(TAG, "BADPROB arm=${arm.name} size=${w?.size} want=$modelSizeSqr")
                return null
            }
            val nets = ArrayList<Double>(CALLS_PER_SAMPLE)
            val walls = ArrayList<Double>(CALLS_PER_SAMPLE)
            repeat(CALLS_PER_SAMPLE) {
                val t1 = System.nanoTime()
                val p = det.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)
                walls.add((System.nanoTime() - t1) / 1e6)
                if (p == null) return null
                nets.add(det.lastTimings()!![1].toDouble())
            }
            return Sample(arm.name, median(nets), median(walls), loadMs)
        } finally {
            det.close()
        }
    }

    /** The arms that load, and the ones that do not (a load failure is a
     *  finding, not a crash, and must not abort the sweep). */
    private fun loadable(arms: List<Arm>): Pair<List<Arm>, List<Arm>> {
        val ok = mutableListOf<Arm>()
        val bad = mutableListOf<Arm>()
        for (arm in arms) {
            val probe = openDet(arm)
            if (probe == null) bad.add(arm) else { ok.add(arm); probe.close() }
        }
        return ok to bad
    }

    /** Interleaved, locally-bracketed price of [variants] against [base]. */
    private fun priceArms(label: String, base: Arm, variants: List<Arm>, cells: Int) {
        val (arms, skipped) = loadable(variants)
        Log.i(
            TAG,
            "START $label base=${base.name} arms=${arms.map { it.name }} " +
                "skipped=${skipped.map { it.name }} cells=$cells callsPerSample=$CALLS_PER_SAMPLE " +
                "settleMs=$CELL_SETTLE_MS options=shipped-fp16"
        )
        report.add("START $label arms=${arms.map { it.name }} SKIPPED ${skipped.map { it.name }} cells=$cells")
        for (a in skipped) report.add("LOADFAIL $label ${a.name}")

        val results = mutableListOf<Cell>()
        for (arm in arms) {
            for (c in 0 until cells) {
                Thread.sleep(CELL_SETTLE_MS)
                val before = sample(base) ?: continue
                val mid = sample(arm) ?: continue
                val after = sample(base) ?: continue
                val ref = (before.netMs + after.netMs) / 2.0
                val cell = Cell(
                    arm.name, c, mid.netMs / ref, before.netMs, mid.netMs, after.netMs,
                    before.netMs / after.netMs, mid.loadMs,
                )
                results.add(cell)
                Log.i(
                    TAG,
                    "CELL $label arm=${arm.name} c=$c seq=${seq++} ratio=${"%.4f".format(cell.ratio)} " +
                        "base=${"%.1f".format(before.netMs)}/${"%.1f".format(after.netMs)} " +
                        "arm=${"%.1f".format(mid.netMs)}ms drift=${"%.4f".format(cell.beforeOverAfter)} " +
                        "load=${"%.1f".format(mid.loadMs)}ms"
                )
            }
        }

        // The per-cell table. [ratio] < 1 means the arm was faster.
        for (arm in arms) {
            val cs = results.filter { it.arm == arm.name }
            if (cs.isEmpty()) continue
            val rs = cs.map { it.ratio }.sorted()
            val med = median(rs)
            val boas = cs.map { it.beforeOverAfter }
            val line = "ROW $label ${arm.name} ratioMed=${"%.4f".format(med)} " +
                "vsBase=${"%+.1f".format(100.0 * (med - 1.0))}% " +
                "min=${"%.4f".format(rs.first())} p10=${"%.4f".format(pct(rs, 0.1))} " +
                "p90=${"%.4f".format(pct(rs, 0.9))} max=${"%.4f".format(rs.last())} " +
                "iqr=${"%.4f".format(pct(rs, 0.9) - pct(rs, 0.1))} " +
                "sd=${"%.4f".format(sd(cs.map { it.ratio }))} " +
                "n=${cs.size} baseNetMed=${"%.1f".format(median(cs.map { it.beforeMs }.plus(cs.map { it.afterMs })))}ms " +
                "armNetMed=${"%.1f".format(median(cs.map { it.midMs }))}ms " +
                "baseSelfRatio=${"%.4f".format(median(boas))} " +
                "x86ms=${arm.x86Ms ?: "n/a"} x86MaskIou=${arm.x86MaskIou ?: "n/a"} " +
                "ratios=${cs.map { "%.3f".format(it.ratio) }}"
            Log.i(TAG, line)
            report.add(line)
        }
        val allDrift = results.map { it.beforeOverAfter }
        if (allDrift.isNotEmpty()) {
            val line = "DRIFT $label baselineSelfRatioMed=${"%.4f".format(median(allDrift))} " +
                "p10=${"%.4f".format(pct(allDrift.sorted(), 0.1))} " +
                "p90=${"%.4f".format(pct(allDrift.sorted(), 0.9))} " +
                "n=${allDrift.size} (1.0 = no drift across a cell)"
            Log.i(TAG, line)
            report.add(line)
        }
    }

    @Test
    fun a_detWall() {
        prepareFixture()
        val base = ARMS.first()

        // The reference prob maps, for the mask-IoU column (a whole-map property,
        // so one call per arm is enough; not a timing number).
        val probs = HashMap<String, FloatArray>()
        for (arm in ARMS) {
            val det = openDet(arm) ?: continue
            try {
                probs[arm.name] = det.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)!!
            } finally {
                det.close()
            }
        }
        val baseProb = probs[base.name]
        for (arm in ARMS) {
            val p = probs[arm.name] ?: continue
            val miou = if (baseProb == null) Double.NaN else maskIou(baseProb, p)
            val maxAbs = if (baseProb == null) Double.NaN else maxAbsDiff(baseProb, p)
            val line = "PROB arm=${arm.name} maskIoU@${MASK_THRESH}=${"%.4f".format(miou)} " +
                "maxAbs=${"%.4f".format(maxAbs)} x86MaskIou=${arm.x86MaskIou ?: "n/a"} " +
                "x86ms=${arm.x86Ms ?: "n/a"}"
            Log.i(TAG, line)
            report.add(line)
        }

        priceArms("fp16on", base, ARMS.drop(1), REPEATS)
        val bundle = android.os.Bundle().apply { putString("detInt8Price", report.joinToString("\n")) }
        instr.sendStatus(0, bundle)
    }

    // ─────────── phase B: the quality gate, through the engine ───────────

    @Test
    fun b_quality() {
        prepareFixture()

        /** Box count, greedy-matched IoU against the baseline, and the decoded
         *  lines — all from the real pipeline, so this is what a user would get. */
        class Outcome(val boxes: List<JpDictRect>, val lines: Int, val texts: List<String>)

        fun runArm(arm: Arm): Outcome? {
            val det = openDet(arm) ?: return null
            val eng = OcrEngine(appContext)
            try {
                assertTrue("engine ready for ${arm.name}", eng.isReady())
                eng.replaceDetForTest(det)
                // Warm, then measure, so the box list is the steady state.
                eng.detect(bmp)
                val boxes = eng.detect(bmp)
                val collected = mutableListOf<Pair<Int, LineResult>>()
                runBlocking {
                    eng.recognizeStreaming(bmp, boxes) { pairs -> synchronized(collected) { collected.addAll(pairs) } }
                    var waited = 0
                    var last = -1
                    var still = 0
                    while (waited < 120_000) {
                        val n = synchronized(collected) { collected.size }
                        if (n >= boxes.size) break
                        delay(200)
                        waited += 200
                        if (n == last) still += 200 else { still = 0; last = n }
                        if (waited >= 10_000 && still >= 4_000) break
                    }
                }
                val texts = synchronized(collected) {
                    collected.sortedBy { it.first }.map { it.second.text }
                }
                return Outcome(boxes, texts.size, texts)
            } finally {
                eng.close()
            }
        }

        val base = ARMS.first()
        val baseOut = runArm(base)
        if (baseOut == null) {
            Log.e(TAG, "baseline arm produced nothing; cannot gate")
            return
        }
        val baseLine = "GATE base-fp16 boxes=${baseOut.boxes.size} lines=${baseOut.lines}"
        Log.i(TAG, baseLine)
        report.add(baseLine)
        for (t in baseOut.texts) Log.i(TAG, "GATE-TEXT base-fp16 $t")

        for (arm in ARMS.drop(1)) {
            if (stagedModel(arm) == null) {
                Log.i(TAG, "GATE-SKIP ${arm.name} (variant model absent)")
                report.add("GATE-SKIP ${arm.name}")
                continue
            }
            val out = runArm(arm)
            if (out == null) {
                Log.e(TAG, "GATE-FAIL ${arm.name} did not load or run")
                report.add("GATE-FAIL ${arm.name}")
                continue
            }
            val ious = matchIous(baseOut.boxes, out.boxes)
            val mean = if (ious.isEmpty()) 0.0 else ious.average()
            val mn = if (ious.isEmpty()) 0.0 else ious.min()
            val exact = ious.count { it >= 0.9999 }
            // Text is compared positionally on the boxes both arms produced, so
            // a variant that finds a different number of lines cannot hide a
            // decode difference behind a shifted index.
            val common = minOf(baseOut.texts.size, out.texts.size)
            var same = 0
            var firstBad = -1
            for (k in 0 until common) {
                if (baseOut.texts[k] == out.texts[k]) same++ else if (firstBad < 0) firstBad = k
            }
            val line = "GATE ${arm.name} boxes=${baseOut.boxes.size}->${out.boxes.size} " +
                "meanIoU=${"%.4f".format(mean)} minIoU=${"%.4f".format(mn)} " +
                "exact=$exact/${ious.size} pass=${mean >= 0.95} " +
                "lines=${baseOut.lines}->${out.lines} textSame=$same/$common firstBad=$firstBad"
            Log.i(TAG, line)
            report.add(line)
            for ((k, t) in out.texts.withIndex()) {
                val ref = baseOut.texts.getOrElse(k) { "<none>" }
                if (t != ref) Log.i(TAG, "GATE-TEXT ${arm.name} idx=$k got='$t' base='$ref'")
            }
        }

        val bundle = android.os.Bundle().apply { putString("detInt8Price", report.joinToString("\n")) }
        instr.sendStatus(0, bundle)
    }

    // ─────────── shared helpers ───────────

    /** Intersection-over-union of the two probability maps binarised at
     *  [MASK_THRESH] — the study's `mask IoU @0.3` column, same definition, so
     *  the two hosts' quality numbers are comparable. */
    private fun maskIou(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var inter = 0
        var union = 0
        for (i in a.indices) {
            val ab = a[i] >= MASK_THRESH
            val bb = b[i] >= MASK_THRESH
            if (ab && bb) inter++
            if (ab || bb) union++
        }
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return Double.NaN
        var m = 0.0
        for (i in a.indices) {
            val d = abs(a[i] - b[i]).toDouble()
            if (d > m) m = d
        }
        return m
    }

    private fun iou(a: JpDictRect, b: JpDictRect): Float {
        val ix = max(0, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u > 0) inter.toFloat() / u else 0f
    }

    /** Greedy best-match by area, like `tools/det_box_iou.py`'s `match_iou` and
     *  `DetLetterboxParityTest.matchIous` — one IoU per baseline box, so the mean
     *  is over the boxes that *were* found and a lost box shows up as a low
     *  match rather than being silently dropped. */
    private fun matchIous(base: List<JpDictRect>, cand: List<JpDictRect>): List<Float> {
        val rem = cand.toMutableList()
        val out = ArrayList<Float>()
        for (box in base.sortedByDescending { it.width() * it.height() }) {
            var best = -1f
            var at = -1
            for (i in rem.indices) {
                val v = iou(box, rem[i])
                if (v > best) { best = v; at = i }
            }
            if (at >= 0) { out.add(best); rem.removeAt(at) }
        }
        return out
    }
}
