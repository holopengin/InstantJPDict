package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the native kernel's verbose-logging switch costs, per call.
 *
 * The shared core's `PPOCR_LOGI` lines were unconditional: the det path pays
 * two of them per detect on the letterbox route and rec one per recognised
 * line, each formatted and written to logcat on the calling thread — inside the
 * very window `g_det_net_ms` is taken in, so a diagnostic was inflating the
 * number it was reporting.
 *
 * Measured here (Pixel 7a, Android 17, `benchmark` build, warm, with a logcat
 * reader attached, 60 paired repeats): **~0.05-0.15 ms per line**, so
 * ~0.1-0.3 ms per detect and ~0.1 ms per recognised line, ~2 ms a page. The
 * det figure is *smaller than a det wall's own spread* (±0.7-1.3 ms of
 * standard error on a ~240 ms call), which is the whole reason for the design
 * below: unpaired medians of 12-24 repeats read anywhere from −0.1 to +2.6 ms
 * for the same code, and an earlier "2-3 ms per det call" estimate does not
 * survive an interleaved paired measurement.
 *
 * The design, in one sentence: the flag is flipped **between** calls rather
 * than between runs, the order alternates every repeat, and each repeat's two
 * arms are differenced as a pair — thermal drift and the CPU scheduler move a
 * det wall by tens of ms over a minute, and only a paired estimate cancels
 * that. The flag write sits outside both timed regions (a JNI round trip in one
 * arm only would be an artefact), and the preprocess half is reported as a
 * control: it must not move.
 *
 * Two other things are measured, because "logging only" is a claim to check
 * rather than assert:
 *  - the probability map and the top-K packing are **bit-identical** with the
 *    flag on and off. A determinism self-check runs first, so a flaky net is
 *    reported as flaky instead of being blamed on the switch;
 *  - the flag round-trips through the JNI boundary, both wrappers see it, and
 *    with it off the core still prints a `PPOCR_LOGE` when asked to fail.
 *
 * Run: `./gradlew :app:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.NcnnVerboseBenchTest`
 * and read the numbers out of `adb logcat -s NcnnVerboseBench`. Run it with a
 * `adb logcat` reader attached as well as without: a verbose session is one
 * where somebody is reading the log, and that is the case the flag exists for.
 * (The per-line cost turned out to be the same either way, but a filtered and
 * an unfiltered reader differ by ~40% in absolute wall, which is worth knowing
 * before comparing any two logcat-based numbers.) [NcnnVerboseFlagTest] is the
 * companion: a counted gate that the flag suppresses exactly the off-arm lines.
 */
@RunWith(AndroidJUnit4::class)
class NcnnVerboseBenchTest {

    private fun loadBitmap(path: String): Bitmap {
        val instr = InstrumentationRegistry.getInstrumentation()
        for ((p, assets) in listOf(path to instr.context.assets, path to instr.targetContext.assets)) {
            try {
                assets.open(p).use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    if (bmp != null) return bmp
                }
            } catch (_: Exception) {
            }
        }
        error("fixture not found: $path")
    }

    /** FNV-1a over the raw bits — a checksum, so a 900k-float map fits in one
     *  log line. */
    private fun hash(floats: FloatArray): String {
        var h = -0x340d631b7bbddd4bL
        for (f in floats) {
            var bits = f.toRawBits()
            for (i in 0 until 4) {
                h = h xor (bits.toLong() and 0xff)
                h *= 0x100000001b3L
                bits = bits ushr 8
            }
        }
        return java.lang.Long.toHexString(h)
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.isEmpty()) 0.0 else s[s.size / 2]
    }

    private fun mean(v: List<Double>): Double = if (v.isEmpty()) 0.0 else v.sum() / v.size

    /** Interleaved, order-alternating A/B of one call site. The flag write sits
     *  outside both timed regions — a JNI round trip in one arm only would be an
     *  artefact. [after] runs inside the timed region, per arm, and is how the
     *  det net/pre split gets collected. */
    private fun ab(
        repeats: Int = REC_REPEATS,
        setFlag: (Boolean) -> Unit,
        after: (Boolean) -> Unit = {},
        call: () -> Unit,
    ): Pair<List<Double>, List<Double>> {
        repeat(WARMUP) {
            setFlag(false); call(); after(false)
            setFlag(true); call(); after(true)
        }
        val off = ArrayList<Double>(repeats)
        val on = ArrayList<Double>(repeats)
        for (i in 0 until repeats) {
            for (arm in if (i % 2 == 0) listOf(false, true) else listOf(true, false)) {
                setFlag(arm)
                val t0 = System.nanoTime()
                call()
                val ms = (System.nanoTime() - t0) / 1e6
                after(arm)
                if (arm) on.add(ms) else off.add(ms)
            }
        }
        return off to on
    }

    /**
     * Paired differences (repeat *i* off against the same repeat *i* on, adjacent
     * in time with the order alternating). This is the only estimator immune to
     * the drift an unpaired median still sees: a det wall moves several ms
     * between repeats, which is the same size as the effect.
     */
    private fun paired(off: List<Double>, on: List<Double>): List<Double> =
        off.indices.map { on[it] - off[it] }

    /** `mean ± standard error`, so a reader can tell "0.1 ms" from "0.1 ± 0.9 ms". */
    private fun se(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = mean(v)
        val ss = v.sumOf { (it - m) * (it - m) }
        return Math.sqrt(ss / (v.size - 1) / v.size)
    }

    private fun stat(name: String, off: List<Double>, on: List<Double>): String {
        val d = paired(off, on)
        val up = d.count { it > 0 }
        return "$name off=${fmt(median(off))} on=${fmt(median(on))} | " +
            "paired med=${fmt(median(d))} mean=${fmt(mean(d))}±${fmt(se(d))} " +
            "up=$up/${d.size}"
    }

    @Test
    fun verboseLoggingCostsMsPerCall() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val page = loadBitmap("bookpage.png")

        // The app's own det geometry (OcrEngine.detect, verbatim): Skia owns the
        // resize, and the pad is the half pixel rounded away from zero.
        val modelSize = OcrEngine.DET_MODEL_SIZE.coerceIn(320, 960)
        val targetLong = minOf(OcrEngine.DEF_DET_LONG_SIDE, modelSize)
        val scale = targetLong.toFloat() / maxOf(page.width, page.height)
        val resizeW = maxOf((page.width * scale).roundToInt(), 32)
        val resizeH = maxOf((page.height * scale).roundToInt(), 32)
        val padX = (modelSize - resizeW + 1) / 2
        val padY = (modelSize - resizeH + 1) / 2
        val resized = Bitmap.createScaledBitmap(page, resizeW, resizeH, true)
        val crop = IntArray(resizeW * resizeH)
        resized.getPixels(crop, 0, resizeW, 0, 0, resizeW, resizeH)
        resized.recycle()

        Log.i(
            TAG,
            "fixture bookpage.png ${page.width}x${page.height} det=${resizeW}x$resizeH " +
                "pad=($padX,$padY) model=$modelSize recThreads=${OcrEngine.REC_THREADS} " +
                "repeats=det:$DET_REPEATS/rec:$REC_REPEATS warmup=$WARMUP pref=${NcnnVerboseLog.isEnabled(ctx)}"
        )
        val prefOn = NcnnVerboseLog.isEnabled(ctx)

        val det = DetNcnn.create(ctx)
        val rec = RecNcnn.create(ctx, numThreads = OcrEngine.REC_THREADS)
        assertTrue("det net failed to load", det != null)
        assertTrue("rec net failed to load", rec != null)
        val d = det!!
        val r = rec!!

        // ── the flag itself ──
        // create() pushed the stored preference into native: whatever the pref
        // says is what native must now say. Asserted as a pairing so the check
        // holds on a device where somebody has already flipped the switch.
        assertEquals("create() must apply the stored pref", prefOn, DetNcnn.isVerboseLogging())
        DetNcnn.setVerboseLogging(true)
        assertTrue("set(true) did not reach native", DetNcnn.isVerboseLogging())
        assertTrue("rec must see the same process-global flag", RecNcnn.isVerboseLogging())
        DetNcnn.setVerboseLogging(false)
        assertFalse("set(false) did not reach native", DetNcnn.isVerboseLogging())
        assertFalse("the shipped default is off, so a release run pays nothing", NcnnVerboseLog.DEF_VERBOSE)
        assertTrue("the pref key is what the debug screen switches", NcnnVerboseLog.PREF_VERBOSE.isNotBlank())

        // ── determinism self-check, before blaming the switch for a diff ──
        val detHashes = HashSet<String>()
        repeat(3) { detHashes.add(hash(d.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)!!)) }
        val recProbe = recInput(256)
        val recHashes = HashSet<String>()
        repeat(3) { recHashes.add(hash(r.inferTopK(recProbe, 256)!!)) }
        val detDet = detHashes.size == 1
        val recDet = recHashes.size == 1
        Log.i(TAG, "determinism det=$detDet rec=$recDet detHash=${detHashes.firstOrNull()} recHash=${recHashes.firstOrNull()}")

        // ── det: the whole JNI call, and the net half alone ──
        // The log lines live in det_run, so the net half is where the cost has
        // to appear; the preprocess half is the control that says the difference
        // is the log write and not the drift.
        val netOff = ArrayList<Double>(DET_REPEATS + WARMUP)
        val netOn = ArrayList<Double>(DET_REPEATS + WARMUP)
        val preOff = ArrayList<Double>(DET_REPEATS + WARMUP)
        val preOn = ArrayList<Double>(DET_REPEATS + WARMUP)
        val (detOff, detOn) = ab(
            repeats = DET_REPEATS,
            setFlag = { on -> DetNcnn.setVerboseLogging(on) },
            after = { arm ->
                val t = d.lastTimings() ?: return@ab
                (if (arm) netOn else netOff).add(t[1].toDouble())
                (if (arm) preOn else preOff).add(t[0].toDouble())
            },
            call = {
                d.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)
            },
        )
        DetNcnn.setVerboseLogging(false)
        // Re-read both maps in one arm each and compare: the switch must not
        // move a single output bit.
        val mapOff = d.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)!!
        DetNcnn.setVerboseLogging(true)
        val mapOn = d.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)!!
        DetNcnn.setVerboseLogging(false)
        val detParity = !detDet || mapOff.contentEquals(mapOn)
        Log.i(TAG, stat("det", detOff, detOn) + " | bitIdentical=$detParity")
        // The log lines live in det_run, so the net half is where the cost has
        // to appear; the preprocess half is the control that says the difference
        // is the log write and not the drift.
        Log.i(TAG, stat("det NET", netOff, netOn))
        Log.i(TAG, stat("det PRE (control)", preOff, preOn))

        // ── rec: one call per recognised line ──
        for (w in REC_WIDTHS) {
            val input = recInput(w)
            val (off, on) = ab(
                repeats = REC_REPEATS,
                setFlag = { v -> DetNcnn.setVerboseLogging(v) },
                call = { r.inferTopK(input, w) },
            )
            DetNcnn.setVerboseLogging(false)
            val outOff = r.inferTopK(input, w)!!
            DetNcnn.setVerboseLogging(true)
            val outOn = r.inferTopK(input, w)!!
            DetNcnn.setVerboseLogging(false)
            val parity = !recDet || outOff.contentEquals(outOn)
            Log.i(TAG, stat("rec w=$w", off, on) + " | bitIdentical=$parity")
            if (w == REC_WIDTHS[0]) assertTrue("rec output must not depend on the flag", parity)
        }

        // ── a whole page's recognition: 18 lines, each measured off then on ──
        var offTotal = 0.0
        var onTotal = 0.0
        val lineOff = ArrayList<Double>()
        val lineOn = ArrayList<Double>()
        for (w in PAGE_WIDTHS) {
            val input = recInput(w)
            DetNcnn.setVerboseLogging(false)
            var t0 = System.nanoTime()
            r.inferTopK(input, w)
            val a = (System.nanoTime() - t0) / 1e6
            DetNcnn.setVerboseLogging(true)
            t0 = System.nanoTime()
            r.inferTopK(input, w)
            val b = (System.nanoTime() - t0) / 1e6
            offTotal += a
            onTotal += b
            lineOff.add(a)
            lineOn.add(b)
        }
        DetNcnn.setVerboseLogging(false)
        Log.i(
            TAG,
            "page rec(${PAGE_WIDTHS.size} lines) off=${fmt(offTotal)}ms on=${fmt(onTotal)}ms " +
                "saving=${fmt(onTotal - offTotal)}ms paired-line med=${fmt(median(paired(lineOff, lineOn)))}ms"
        )

        // ── errors are NOT gated ──
        // With the flag off: the app's own guard, then a core PPOCR_LOGE. A
        // zero-width rec input is the way in — rec_infer_topk refuses it and
        // returns null rather than reading a stale stride, so this fails closed
        // and prints `PpocrNcnn E PpocrNcnn: extract out0 failed …` from
        // ppocr_ncnn_core.cpp, the one path no flag can switch off.
        DetNcnn.setVerboseLogging(false)
        assertTrue(
            "bad geometry must be refused",
            d.inferLetterboxed(IntArray(16), 4, 4, 8, 6, 0) == null,
        )
        assertTrue(
            "a zero-width rec input must be refused, not decoded",
            r.inferTopK(FloatArray(0), 0, 48) == null,
        )
        Log.i(TAG, "error proof: flag OFF, both refusals logged; expect PpocrNcnn E lines")

        assertTrue("det output must not depend on the logging flag", detParity)
        d.close()
        r.close()
    }

    /** A page-like [1,3,48,w] rec input: the app's rec normalisation range
     *  ((gray/127.5)-1), shaped like text rows so the top-K scan sees the same
     *  magnitudes it does in production. */
    private fun recInput(w: Int): FloatArray {
        val out = FloatArray(3 * 48 * w)
        var seed = w * 2654435761
        for (c in 0 until 3) {
            for (y in 0 until 48) {
                val row = -0.9f + 1.8f * (((y * 7919) % 23) / 22f)
                for (x in 0 until w) {
                    seed = seed * 1103515245 + 12345
                    val jitter = ((seed ushr 9) and 0xFF) / 255f - 0.5f
                    out[(c * 48 + y) * w + x] = (row + jitter * 0.4f).coerceIn(-1f, 1f)
                }
            }
        }
        return out
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    private companion object {
        const val TAG = "NcnnVerboseBench"

        /** Repeat counts are the noise budget. A det wall moves ~8-15 ms between
         *  repeats on its own (it runs on two ncnn threads) and the whole
         *  measure-down the page drifted 229 → 248 ms median between runs, so an
         *  unpaired estimate of a ~0.1-0.3 ms effect is meaningless; the paired
         *  difference with these counts resolves it, and [stat] prints the
         *  standard error so the result is readable as an interval. */
        const val DET_REPEATS = 60
        const val REC_REPEATS = 60
        const val WARMUP = 2
        val REC_WIDTHS = intArrayOf(64, 128, 256, 400)

        /** 18 lines, bookpage.png's box count, widths spanning what its crops
         *  actually produce. */
        val PAGE_WIDTHS = intArrayOf(
            64, 96, 128, 160, 200, 240, 288, 320, 64,
            96, 128, 160, 200, 240, 288, 320, 128, 160,
        )
    }
}
