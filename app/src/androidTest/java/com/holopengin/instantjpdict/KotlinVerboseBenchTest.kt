package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.holopengin.instantjpdict.util.InferLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * What the *Kotlin* half of [NcnnVerboseLog] costs, per detect call and per
 * recognised line.
 *
 * The native half has its own measurement ([NcnnVerboseBenchTest], ~0.05-0.15 ms
 * a line). The Kotlin half was never measured and is a different animal: its
 * per-box and per-line sites (`rubyTrim`, `line idx=`, `crop rw=`, the
 * long-line stitch notes) write to an in-memory ring or to logcat from the
 * worker thread, and one of them — the `prob_map:` line — was fed by a
 * 921,600-iteration pass over the probability map that existed for no other
 * reason. Guessing which of those dominates is not good enough, because the
 * answer decides whether the gate was worth adding: a `Log.d` to logcat is a
 * write on the calling thread, an `InferLog.add` is a synchronized deque push
 * that in steady state also evicts a 400-entry ring, and a 0.9 M-iteration
 * float reduction is neither.
 *
 * So the test separates them **by construction**, in two splits, each with an
 * arm that does nothing and every other arm differenced against it *inside the
 * same repeat* (so all arms share one drift):
 *
 *  - [lineSplit] — per line: nothing / one `InferLog.add` / one `Log.d` / both.
 *    The ring is **pre-filled to capacity** first, because that is the state a
 *    page runs in and `InferLog.add` evicts when it is full; a cold ring would
 *    report the cheaper of the two costs.
 *  - [detSplit] — per detect call: nothing / the five `Log.d` lines / the
 *    prob-map reduction / both.
 *
 * On top of that it measures the end-to-end effect the gate has —
 * [engine].detect and a whole [engine].recognizeStreaming page of
 * `bookpage.png`, gate off then on — with [NcnnVerboseBenchTest]'s design,
 * because a det wall and a page wall both move by tens of milliseconds over a
 * minute and only a *paired* difference cancels that: the flag is flipped
 * **between** calls, the order alternates every repeat, and each repeat's two
 * arms are differenced as a pair. The flag write sits outside every timed
 * region. Those two numbers are printed with their standard error and are
 * expected to sit *inside* their own wall's noise — which is why the splits
 * exist.
 *
 * Three things are measured besides the time, because "logging only" is a claim
 * to check rather than assert:
 *
 *  - **How much is suppressed.** The exact number of ring lines a page adds is
 *    read out of [InferLog] itself in each arm, and both ring dumps are
 *    reported whole so a host can diff them.
 *  - **Nothing else moves.** The detect box list and the recognised text of a
 *    whole page are identical with the gate on and off, and the summary lines
 *    survive in both ring dumps.
 *
 * Every number is logged under `KotlinVerboseBench` and also written to
 * `files/kotlin-verbose-bench.txt` in the app's data directory (both paths are
 * printed in a `WROTE` line), because the measurement itself writes thousands
 * of logcat lines and can rotate the main buffer before the first result is out
 * of it:
 *
 * ```
 * ./gradlew :app:connectedBenchmarkAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.KotlinVerboseBenchTest
 * adb logcat -d -s KotlinVerboseBench:I
 * ```
 * Run it with a `adb logcat` reader attached as well as without: a verbose
 * session is one where somebody is reading the log, and that is the case the
 * switch exists for. Both runs are reported.
 *
 * ## What it found (Pixel 7a, Android 17, `benchmark` build, warm)
 *
 * Four runs; the per-site splits are stable and are the numbers to quote, the
 * end-to-end pair is reported to show the resolution limit. Per line, 20 lines
 * an arm, the ring pre-filled to its 400 cap, 60 paired repeats, in µs — **a
 * logcat reader makes almost no difference**, the same result
 * [NcnnVerboseBenchTest] got for the native lines:
 *
 * | arm | quiet run | busier runs |
 * |---|---|---|
 * | one `InferLog.add` | 21 ± 3 | 14 ± 2, 18 ± 3 |
 * | one `Log.d` | 91 ± 3 | 104 ± 9, 114 ± 9 |
 * | both (what a page pays) | 124 ± 4 | 122 ± 4, 137 ± 8 |
 *
 * So `Log.d` is the cost and `InferLog.add` is ~free: **4-8× apart**, and the
 * gate buys back the logcat writes, not the ring pushes. The ring figure is
 * measured with the ring *full*, which is the state a page runs in — a cold
 * ring would have reported less.
 *
 * Per detect call, the same design, 60 paired repeats, in ms:
 *
 * | arm | quiet run | busier runs |
 * |---|---|---|
 * | the four cheap `Log.d` lines | 0.026 ± 0.002 | 0.031-0.035 ± 0.003 |
 * | the prob-map reduction alone | 3.95 ± 0.00 | 4.10, 5.03, 5.28 ± 0.02 |
 * | all five lines, as written | 4.11 ± 0.00 | 4.27, 5.24, 5.53 ± 0.03 |
 *
 * **The reduction is 120-160× the four log writes around it.** The pass over
 * the 802,816-float probability map existed only to format one `prob_map:` line,
 * and it was the most expensive thing `runDetMask` did after the net. That is
 * the finding; the log writes are noise beside it.
 *
 * End to end, gate off then on, paired:
 *
 *  - **detect**: 2.05 ± 0.35 ms in the quiet run (53 of 60 pairs slower), and
 *    0.8 ± 1.2, 1.5 ± 0.9, 3.4 ± 1.7, 6.4 ± 6.9 in busier ones. The direction
 *    is not in doubt — **153 of 210 pairs across five runs were slower with the
 *    gate on** — and ~2 ms is the figure to quote. It is below the 4.1 ms the
 *    isolated split reads, because in the real call the map is still hot from
 *    the net that wrote it.
 *  - **page**: −19 ± 16, −4 ± 15, +16 ± 29, +42 ± 18 ms over four runs of
 *    8-16 pairs. The same code, the same fixture, four answers spread over
 *    60 ms: a 1.2-1.5 s page wall cannot see the ~5 ms the parts predict, and
 *    quoting any one of these runs would be dishonest. The per-site splits plus
 *    the detect figure are the measurement; this row is the noise floor.
 */
@RunWith(AndroidJUnit4::class)
class KotlinVerboseBenchTest {

    private val results = StringBuilder()

    private fun say(line: String) {
        results.append(line).append('\n')
        Log.i(TAG, line)
    }

    // ── statistics, as in NcnnVerboseBenchTest ──────────────────────────────

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.isEmpty()) 0.0 else s[s.size / 2]
    }

    private fun mean(v: List<Double>): Double = if (v.isEmpty()) 0.0 else v.sum() / v.size

    private fun se(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = mean(v)
        val ss = v.sumOf { (it - m) * (it - m) }
        return Math.sqrt(ss / (v.size - 1) / v.size)
    }

    /** Paired differences: repeat *i* off against the same repeat *i* on. */
    private fun paired(off: List<Double>, on: List<Double>): List<Double> =
        off.indices.map { on[it] - off[it] }

    private fun stat(name: String, off: List<Double>, on: List<Double>): String {
        val d = paired(off, on)
        return "$name off=${fmt(median(off))}ms on=${fmt(median(on))}ms | " +
            "paired med=${fmt(median(d))}ms mean=${fmt(mean(d))}±${fmt(se(d))}ms " +
            "up=${d.count { it > 0 }}/${d.size}"
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    // ── the interleaved A/B: the flag write is outside every timed region ───

    /**
     * Interleaved, order-alternating A/B. [setFlag] runs before the clock
     * starts and after it stops, so a write in one arm only is never part of
     * the measurement.
     */
    private fun ab(
        repeats: Int,
        warmup: Int,
        setFlag: (Boolean) -> Unit,
        call: () -> Any,
    ): Pair<List<Double>, List<Double>> {
        repeat(warmup) {
            setFlag(false); call()
            setFlag(true); call()
        }
        val off = ArrayList<Double>(repeats)
        val on = ArrayList<Double>(repeats)
        for (i in 0 until repeats) {
            for (arm in if (i % 2 == 0) listOf(false, true) else listOf(true, false)) {
                setFlag(arm)
                val t0 = System.nanoTime()
                val r = call()
                val ms = (System.nanoTime() - t0) / 1e6
                sink = sink xor r.hashCode()
                if (arm) on.add(ms) else off.add(ms)
            }
        }
        return off to on
    }

    /**
     * [arms] are run in alternating order, each differenced against the arm
     * named [base], within the same repeat. The shared estimator: whatever
     * drift a repeat saw, every arm of that repeat saw it.
     */
    private fun splitArms(arms: List<String>, base: String, warmup: Int, repeats: Int, block: (String) -> Unit): String {
        fun runArms() {
            arms.forEach { a -> repeat(warmup) { block(a) } }
        }
        runArms()
        val samples = Array(arms.size) { ArrayList<Double>(repeats) }
        for (r in 0 until repeats) {
            val order = if (r % 2 == 0) arms else arms.reversed()
            val perRepeat = arrayOfNulls<Double>(arms.size)
            for (a in order) {
                val t0 = System.nanoTime()
                block(a)
                perRepeat[arms.indexOf(a)] = (System.nanoTime() - t0) / 1e6
            }
            for (a in arms.indices) samples[a].add(perRepeat[a]!!)
        }
        val baseSamples = samples[arms.indexOf(base)]
        return arms.filter { it != base }.joinToString(" ") { a ->
            val d = paired(baseSamples, samples[arms.indexOf(a)])
            "$a=${fmt(mean(d))}ms±${fmt(se(d))} (med ${fmt(median(d))})"
        }
    }

    // ── split 1: per line ───────────────────────────────────────────────────

    /** The `crop rw=` ring line, verbatim from `OcrEngine.recognizePpocrBatch`
     *  (`REC_STRIDE` is 8; the values only set the line's length). */
    private fun ringLine(i: Int) {
        val targetW = 288
        InferLog.add("crop rw=${300 + i} rh=48 targetW=$targetW sq=$targetW seq=${targetW / 8}")
    }

    /** The `line idx=` logcat line, verbatim from `OcrEngine.emitLine`. */
    private fun logLine(i: Int) {
        Log.d(ENGINE_TAG, "line idx=$i len=${(i % 40) + 2} vert=${i % 2 == 0}")
    }

    private fun lineSplit(): String {
        // Steady state: the ring is at capacity before every arm, so each
        // `add` also evicts — that copy is part of what a page pays.
        fun fill() {
            InferLog.clear()
            repeat(RING_CAP + 20) { InferLog.add("prefill $it") }
        }
        fun block(arm: String) {
            fill()
            for (i in 0 until LINES_PER_ARM) {
                when (arm) {
                    "off" -> Unit
                    "ring" -> ringLine(i)
                    "logcat" -> logLine(i)
                    "both" -> { ringLine(i); logLine(i) }
                }
            }
        }
        val per = splitArms(listOf("off", "ring", "logcat", "both"), "off", 3, SPLIT_REPEATS, ::block)
        return "$LINES_PER_ARM lines/arm, ring pre-filled to $RING_CAP: $per"
    }

    // ── split 2: per detect call ────────────────────────────────────────────

    /** A prob map of the real shape and value range: 896² floats, `mean=0.0464`,
     *  `min=2.2e-5`, `max=1.0` on `bookpage.png`. */
    private val probMap: FloatArray by lazy {
        FloatArray(DET_MAP * DET_MAP) { i ->
            when {
                i % 977 == 0 -> 1.0f
                i % 37 == 0 -> 0.5f
                else -> 2.169609E-5f + (i % 251) * 1.8e-4f
            }
        }
    }

    /** The reduction `runDetMask` ran only to be able to print `prob_map:`. */
    private fun probScan(): Triple<Float, Float, Float> {
        var pMin = Float.MAX_VALUE
        var pMax = Float.MIN_VALUE
        var pSum = 0f
        var pCount = 0
        for (i in probMap.indices) {
            val v = probMap[i]
            pMin = minOf(pMin, v)
            pMax = maxOf(pMax, v)
            pSum += v
            pCount++
        }
        return Triple(pMin, pMax, pSum / pCount)
    }

    /** The four cheap `Log.d` lines a detect call writes when the gate is on. */
    private fun detLogLines() {
        Log.d(ENGINE_TAG, "detect: native letterbox content=896x1080 pad=(0,0) model=896")
        Log.d(ENGINE_TAG, "detect tunables thresh=0.25 unclip=0.7 longSide=896 xOverlap=0.4 modelSize=896 furigana=false")
        Log.d(ENGINE_TAG, "detect: output size=802816 expected=802816 out=896x896")
        Log.d(ENGINE_TAG, "detect: raw 21 boxes")
    }

    /** All five: the four above plus the one the reduction feeds. */
    private fun detLogLinesWithScan() {
        detLogLines()
        val (mn, mx, mean) = probScan()
        Log.d(ENGINE_TAG, "prob_map: min=$mn max=$mx mean=$mean")
    }

    private fun detSplit(): String {
        fun block(arm: String) {
            when (arm) {
                "off" -> Unit
                "logs" -> detLogLines()
                "probscan" -> probScan()
                "both" -> detLogLinesWithScan()
            }
        }
        val per = splitArms(listOf("off", "logs", "probscan", "both"), "off", 20, DET_SPLIT_REPEATS, ::block)
        return "${probMap.size} floats: $per"
    }

    // ── the page, as the app runs it ────────────────────────────────────────

    /** Ring lines currently in [InferLog], the exact suppressed-line count. */
    private fun ringLines(): Int = InferLog.dump().trim().lines().size - 1

    private fun recognizePage(bmp: Bitmap): List<LineResult> {
        val detBoxes = engine.detect(bmp)
        val collected = mutableListOf<Pair<Int, LineResult>>()
        runBlocking {
            engine.recognizeStreaming(bmp, detBoxes) { pairs ->
                synchronized(collected) { collected.addAll(pairs) }
            }
            var waited = 0
            var last = -1
            var still = 0
            while (waited < 40_000) {
                delay(200)
                waited += 200
                synchronized(collected) {
                    if (collected.size == last) still += 200 else { still = 0; last = collected.size }
                }
                if (last >= detBoxes.size) break
                if (waited >= 8_000 && still >= 4_000) break
            }
        }
        return collected.sortedBy { it.first }.map { it.second }
    }

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

    @Test
    fun theKotlinGateCostsMsPerDetectAndPerLine() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val page = loadBitmap("bookpage.png")
        // Only the Kotlin sites move in this test: the native flag is held off
        // in both arms, so every millisecond below is a `Log.d` or an
        // `InferLog.add` written by `OcrEngine`.
        DetNcnn.setVerboseLogging(false)
        val stored = NcnnVerboseLog.isEnabled(ctx)
        assertEquals("create() must apply the stored pref to native", stored, DetNcnn.isVerboseLogging())
        assertFalse("the shipped default is off, so a release run pays nothing", NcnnVerboseLog.DEF_VERBOSE)
        say(
            "FIXTURE bookpage.png ${page.width}x${page.height} storedPref=$stored " +
                "detRepeats=$DET_REPEATS pageRepeats=$PAGE_REPEATS splitRepeats=$SPLIT_REPEATS " +
                "detSplitRepeats=$DET_SPLIT_REPEATS linesPerArm=$LINES_PER_ARM warmup=$WARMUP ringCap=$RING_CAP",
        )

        // ── 1. the two splits, before anything else fills the ring ──
        say("SPLIT perLine $LINES_PER_ARM-lines: ${lineSplit()}")
        say("SPLIT perDetect ${detSplit()}")
        InferLog.clear()

        // ── 2. det end to end, gate off then on ──
        val (detOff, detOn) = ab(DET_REPEATS, WARMUP, ::setGate) {
            val boxes = engine.detect(page)
            if (boxes.isEmpty()) error("no boxes")
            boxes
        }
        InferLog.clear()
        val boxesOff = engine.detect(page)
        val ringOffDet = ringLines()
        InferLog.clear()
        setGate(true)
        val boxesOn = engine.detect(page)
        val ringOnDet = ringLines()
        setGate(false)
        val detParity = boxesOff == boxesOn
        say(stat("DET", detOff, detOn) + " | boxes=${boxesOff.size} identical=$detParity")
        assertTrue("the gate must not move a single box", detParity)
        assertEquals("a detect says the same thing in both arms", ringOffDet, ringOnDet)
        assertEquals("a detect adds only its two summary lines", 2, ringOffDet)

        // ── 3. a whole page, gate off then on ──
        setGate(false); recognizePage(page)
        setGate(true); recognizePage(page)
        val (pageOff, pageOn) = ab(PAGE_REPEATS, 0, ::setGate) {
            val lines = recognizePage(page)
            if (lines.isEmpty()) error("no lines")
            lines.joinToString(" ") { it.text }
        }
        setGate(false)
        InferLog.clear()
        val textOff = recognizePage(page).joinToString(" ") { it.text }
        val linesOff = ringLines()
        val offDump = InferLog.dump()
        InferLog.clear()
        setGate(true)
        val textOn = recognizePage(page).joinToString(" ") { it.text }
        val linesOn = ringLines()
        val onDump = InferLog.dump()
        setGate(false)
        InferLog.clear()

        val ringDelta = linesOn - linesOff
        say(stat("PAGE", pageOff, pageOn))
        say(
            "SUPPRESS ringLines/page off=$linesOff on=$linesOn gated=$ringDelta " +
                "textIdentical=${textOff == textOn}",
        )
        say("RING off:\n$offDump")
        say("RING on:\n$onDump")
        assertEquals("the gate must not change a character of the page", textOff, textOn)
        // The summary contract, stated as a test: every summary line is in both
        // dumps, as many times as in the other, and the gated per-line/per-box
        // lines are in the on dump only. (The *order* and the millisecond
        // figures of the summaries legitimately differ run to run — lines
        // complete in completion order and the timings are measurements.)
        for (keep in SUMMARIES) {
            assertEquals("summary '$keep' count must not depend on the gate", countPrefix(offDump, keep), countPrefix(onDump, keep))
            if (!keep.startsWith("rec ")) {
                assertTrue("summary '$keep' must survive with the gate off", offDump.contains(keep))
            }
        }
        for (gated in GATED) {
            assertEquals("'$gated' must appear in the on dump only", 0, countPrefix(offDump, gated))
            assertTrue("'$gated' must appear with the gate on", countPrefix(onDump, gated) > 0)
        }

        // ── 4. the per-unit figures, straight out of the paired deltas ──
        val lineCount = boxesOff.size
        val detDelta = mean(paired(detOff, detOn))
        val pageDelta = mean(paired(pageOff, pageOn))
        say(
            "PERUNIT detDelta=${fmt(detDelta)}ms/detect lineCount=$lineCount " +
                "pageDelta=${fmt(pageDelta)}ms/page perLineMs=${fmt(pageDelta / max(1, lineCount))} " +
                "gatedRingLines=$ringDelta",
        )
        writeOut(ctx)
    }

    /** How many lines of [dump] start with [prefix]. */
    private fun countPrefix(dump: String, prefix: String): Int =
        dump.lines().count { it.startsWith(prefix) }

    private fun setGate(on: Boolean) = NcnnVerboseLog.setKotlinEnabled(on)

    /**
     * The results, to a file as well as to logcat: the measurement writes
     * thousands of logcat lines and can rotate the main buffer before the first
     * result is out of it. Internal storage is the primary copy — it always
     * exists and the benchmark build is debuggable, so
     * `adb exec-out run-as <pkg> cat files/kotlin-verbose-bench.txt` reads it;
     * external storage is a second copy for a host that prefers it. Both paths
     * are printed.
     */
    private fun writeOut(ctx: android.content.Context) {
        ctx.filesDir.mkdirs()
        val internal = File(ctx.filesDir, RESULT_FILE)
        internal.writeText(results.toString())
        Log.i(TAG, "WROTE ${internal.absolutePath} (${internal.length()} bytes)")
        ctx.getExternalFilesDir(null)?.let { dir ->
            val external = File(dir, RESULT_FILE)
            runCatching { external.writeText(results.toString()) }
            Log.i(TAG, "WROTE ${external.absolutePath} (${external.length()} bytes)")
        }
    }

    companion object {
        private const val TAG = "KotlinVerboseBench"

        /** `OcrEngine`'s own log tag, so the split's `Log.d` lines are the ones
         *  a host counts on the page. */
        private const val ENGINE_TAG = "PPOCREngine"
        const val RESULT_FILE = "kotlin-verbose-bench.txt"

        /** `InferLog`'s capacity, restated so the split can pre-fill the ring
         *  without reaching into the ring's internals. */
        const val RING_CAP = 400

        /** The prob map's side: `DET_MODEL_SIZE` for this fixture. */
        const val DET_MAP = 896

        /** The ring lines a page must still say with the gate off. `rec …` is
         *  `RecNcnn`'s per-line timing, which this switch does not own. */
        val SUMMARIES = listOf(
            "detect out=", "detect final=", "stream start", "batch ", "stream done", "rec topk ", "rec infer ",
        )

        /** The ring lines the gate owns: per line and per box. */
        val GATED = listOf("line idx=", "crop rw=")

        private var sink = 0

        /** The det wall is ~230 ms and the effect is 1-2 ms, so the end-to-end
         *  pair count is a noise budget: 60 pairs put the standard error around
         *  0.6 ms, which is what it takes to tell a 1.5 ms effect from nothing.
         *  The splits are what resolve the effect per site. */
        const val DET_REPEATS = 60
        const val PAGE_REPEATS = 16
        const val WARMUP = 2

        /** The split arms are milliseconds each and their effect is tens of
         *  microseconds, so they get many more repeats. */
        const val SPLIT_REPEATS = 60
        const val DET_SPLIT_REPEATS = 60
        const val LINES_PER_ARM = 20

        private lateinit var engine: OcrEngine

        @JvmStatic
        @BeforeClass
        fun setupEngine() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            engine = OcrEngine(appContext)
            assertTrue("OcrEngine failed to load", engine.isReady())
        }
    }
}
