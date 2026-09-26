package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * REC fanout x REC_THREADS cross product on the Pixel 7a.
 *
 * The x86 study (docs/ncnn-perf-x86-2026-09-26.md, finding 5) found rec
 * thread scaling near-linear (0.68x at 4 threads, bit-identical output) while
 * the device reported "more rec threads is worse" (REC_THREADS 2/4 both slower
 * than 1). The two are reconcilable if the device result is a *scheduling*
 * artefact: the app already runs `fanout=4`, i.e. four lines concurrently, so
 * the machine is saturated and per-line threads only add contention — and on a
 * big.LITTLE part four single-threaded lines and two double-threaded lines are
 * the same 4-way parallelism at very different cost (the first spills onto
 * A55s, the second pairs two lines onto one A78).
 *
 * The earlier device sweeps varied one knob at a time and never took the cross
 * product, so that explanation was untested. This does.
 *
 * Method:
 *  - the cross product (fanout, threads) = (1,1) (2,1) (4,1) (1,2) (2,2) (4,2)
 *    (2,4) (8,1), 8 repeats, on `bookpage.png` (18 lines) and
 *    `benchmark/Screenshot_20260905-093821.png` (dense, >= 20 lines);
 *  - a **fresh `OcrEngine` per sample**: `RecNcnn.create(numThreads = …)` bakes
 *    the thread count in at construction, so a reused engine cannot change
 *    threads, and a fresh one also means no cell inherits another cell's warm
 *    weights;
 *  - one warmup pass per engine before the measured pass;
 *  - **interleaved order**: cell `c` runs in time slot `(c - r) mod 8` on
 *    repeat `r`, so over the 8 repeats every cell occupies every slot exactly
 *    once (a Latin square) and thermal drift cannot favour a fixed position;
 *  - every sample's decoded text is compared against the (4,1) shipped default.
 *    On x86 every thread count was bit-identical, so a text difference means
 *    something other than the knob moved and the sample is invalid;
 *  - `det` is computed ONCE per fixture and the same box list is fed to every
 *    cell (so it cannot move at all), plus a per-repeat det probe whose box
 *    list and wall are logged to show det is stable.
 *
 * `recWall` is the `recognizeStreaming` wall — inference and decode, ending when
 * the streaming pass returns. The emit callback is posted to the main looper
 * (`processOneBatch` -> `mainHandler.post`), so those land *after* the return;
 * the drain that collects them is timed separately as `settleMs` and kept out of
 * `recWall` (it is scheduling noise, not compute, and would otherwise add a
 * constant ~1s to every cell).
 *
 * Run:  ./gradlew :app:connectedBenchmarkAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.RecFanoutThreadSweepTest
 */
@RunWith(AndroidJUnit4::class)
class RecFanoutThreadSweepTest {

    companion object {
        private const val TAG = "RecFanoutSweep"

        /** (fanout, recThreads). (4,1) is the shipped default. */
        private val CELLS = listOf(
            1 to 1, 2 to 1, 4 to 1,
            1 to 2, 2 to 2, 4 to 2,
            2 to 4, 8 to 1,
        )
        private const val REPEATS = 8
        private const val WARMUP_PASSES = 1

        private class Fixture(val label: String, val asset: String, val minLines: Int)

        private val FIXTURES = listOf(
            Fixture("bookpage", "bookpage.png", 18),
            Fixture("quest", "benchmark/Screenshot_20260905-093821.png", 20),
        )

        private fun loadAssetBitmap(name: String): Bitmap {
            val instr = InstrumentationRegistry.getInstrumentation()
            val bases = listOf(instr.context.assets, instr.targetContext.assets)
            val paths = listOf(name, name.substringAfterLast('/'))
            for (base in bases) for (path in paths) {
                try {
                    base.open(path).use { ins ->
                        val bmp = BitmapFactory.decodeStream(ins)
                        if (bmp != null) {
                            Log.i(TAG, "loaded $path ${bmp.width}x${bmp.height}")
                            return bmp
                        }
                    }
                } catch (_: Exception) { }
            }
            error("fixture not found: $name (tried ${bases.size * paths.size} paths)")
        }

        private fun boxKey(boxes: List<JpDictRect>): List<String> =
            boxes.map { "${it.left},${it.top},${it.right},${it.bottom}" }

        private fun pct(sorted: List<Long>, p: Double): Long {
            if (sorted.isEmpty()) return 0
            val i = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[i]
        }

        private fun median(xs: List<Long>): Long {
            if (xs.isEmpty()) return 0
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        /** Least-squares slope of ms per sample step — the thermal trend of one
         *  cell across the whole run (0 = the cell never drifted). */
        private fun slopePerSample(xs: List<Long>): Double {
            val n = xs.size
            if (n < 2) return 0.0
            val mx = (n - 1) / 2.0
            val my = xs.sum() / n.toDouble()
            var num = 0.0; var den = 0.0
            for (i in 0 until n) {
                num += (i - mx) * (xs[i] - my); den += (i - mx) * (i - mx)
            }
            return if (den == 0.0) 0.0 else num / den
        }
    }

    private class PassResult(val recWallMs: Long, val settleMs: Long, val texts: List<String>)

    private class Sample(
        val fanout: Int,
        val threads: Int,
        val repeat: Int,
        val slot: Int,
        val seq: Int,
        val loadMs: Long,
        val recWallMs: Long,
        val settleMs: Long,
        val lines: Int,
        val texts: List<String>,
    )

    /** One full streaming pass. [recWallMs] ends when `recognizeStreaming`
     *  returns; the main-looper emit drain is timed apart. */
    private fun runPass(eng: OcrEngine, bmp: Bitmap, boxes: List<JpDictRect>): PassResult {
        val collected = mutableListOf<Pair<Int, LineResult>>()
        val t0 = System.nanoTime()
        runBlocking {
            eng.recognizeStreaming(bmp, boxes) { pairs ->
                synchronized(collected) { collected.addAll(pairs) }
            }
        }
        val recWallMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        runBlocking {
            var waited = 0; var last = -1; var still = 0
            while (waited < 60000) {
                val n = synchronized(collected) { collected.size }
                if (n >= boxes.size) break
                kotlinx.coroutines.delay(50); waited += 50
                if (n == last) still += 50 else { still = 0; last = n }
                // Empties never arrive by design, so count alone cannot end the
                // drain; 500ms of stillness can.
                if (still >= 500 && waited > 1000) break
            }
        }
        val settleMs = (System.nanoTime() - t1) / 1_000_000
        val texts = synchronized(collected) {
            collected.sortedBy { it.first }.map { it.second.text }
        }
        return PassResult(recWallMs, settleMs, texts)
    }

    @Test
    fun fanoutThreadsCrossProduct() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val report = mutableListOf<String>()
        val originalFanout = OcrEngine.REC_FANOUT
        val originalThreads = OcrEngine.REC_THREADS
        val originalBatch = OcrEngine.REC_BATCH_SIZE
        Log.i(TAG, "START cells=${CELLS.size} repeats=$REPEATS batch=$originalBatch " +
            "defaults f=$originalFanout t=$originalThreads")
        try {
            for (fx in FIXTURES) {
                val bmp = loadAssetBitmap(fx.asset)
                val detEng = OcrEngine(appContext)
                try {
                    assertTrue("det engine ready", detEng.isReady())
                    // det is computed once and the SAME list feeds every cell, so
                    // it cannot move between arms; the per-repeat probe below is
                    // only evidence that it did not drift on its own.
                    val boxes = detEng.detect(bmp)
                    assertTrue(
                        "${fx.label}: expected >= ${fx.minLines} lines, got ${boxes.size}",
                        boxes.size >= fx.minLines,
                    )
                    val baseKey = boxKey(boxes)
                    Log.i(TAG, "FIXTURE ${fx.label} boxes=${boxes.size} detHeldFixed=true")

                    val samples = mutableListOf<Sample>()
                    val detWalls = mutableListOf<Long>()
                    var seq = 0
                    for (r in 0 until REPEATS) {
                        val tDet = System.nanoTime()
                        val probe = detEng.detect(bmp)
                        detWalls.add((System.nanoTime() - tDet) / 1_000_000)
                        assertEquals(
                            "${fx.label} repeat $r: det boxes drifted",
                            baseKey, boxKey(probe),
                        )
                        // Latin square: on repeat r the cell in time slot i is
                        // CELLS[(i + r) mod n], so each cell visits each slot once.
                        for (i in CELLS.indices) {
                            val slot = (i + r) % CELLS.size
                            val (fanout, threads) = CELLS[slot]
                            OcrEngine.REC_FANOUT = fanout
                            OcrEngine.REC_THREADS = threads
                            val tLoad = System.nanoTime()
                            val eng = OcrEngine(appContext)
                            val loadMs = (System.nanoTime() - tLoad) / 1_000_000
                            var pass: PassResult? = null
                            try {
                                assertTrue("engine ready f=$fanout t=$threads", eng.isReady())
                                // Warmup: weights are packed per Net at create, so
                                // the first pass is not the steady state.
                                repeat(WARMUP_PASSES) { runPass(eng, bmp, boxes) }
                                pass = runPass(eng, bmp, boxes)
                            } finally {
                                eng.close()
                            }
                            val p = pass!!
                            samples.add(
                                Sample(fanout, threads, r, slot, seq++, loadMs,
                                    p.recWallMs, p.settleMs, p.texts.size, p.texts)
                            )
                            Log.i(
                                TAG,
                                "SAMPLE ${fx.label} f=$fanout t=$threads r=$r slot=$slot seq=${seq - 1} " +
                                    "load=${loadMs}ms rec=${p.recWallMs}ms settle=${p.settleMs}ms " +
                                    "lines=${p.texts.size}"
                            )
                        }
                    }

                    // ── text gate: every sample must equal the (4,1) baseline ──
                    val baseline = samples.first { it.fanout == 4 && it.threads == 1 }
                    var textOk = true
                    for (s in samples) {
                        if (s.texts != baseline.texts) {
                            textOk = false
                            val n = minOf(s.texts.size, baseline.texts.size)
                            var firstBad = -1
                            for (k in 0 until n) if (s.texts[k] != baseline.texts[k]) { firstBad = k; break }
                            Log.e(
                                TAG,
                                "TEXTMISMATCH ${fx.label} f=${s.fanout} t=${s.threads} r=${s.repeat} " +
                                    "seq=${s.seq} lines=${s.texts.size} vs ${baseline.texts.size} " +
                                    "firstBad=$firstBad base='${baseline.texts.getOrElse(firstBad) { "" }}' " +
                                    "got='${s.texts.getOrElse(firstBad) { "" }}'"
                            )
                        }
                    }
                    assertTrue(
                        "${fx.label}: a cell decoded different text than (4,1) — " +
                            "the thread/fanout knob changed the output, so the arm is invalid",
                        textOk,
                    )
                    // The baseline must also be self-consistent across its own repeats.
                    for (s in samples.filter { it.fanout == 4 && it.threads == 1 }) {
                        assertEquals("${fx.label} (4,1) repeat ${s.repeat} text drifted", baseline.texts, s.texts)
                    }

                    // ── det stability (reported, not a gate on the arms) ──
                    val detSorted = detWalls.sorted()
                    Log.i(
                        TAG,
                        "DET ${fx.label} boxes=${boxes.size} med=${median(detWalls)}ms " +
                            "min=${detSorted.first()} max=${detSorted.last()} samples=$detWalls"
                    )

                    // ── per-cell table ──
                    val baseMed = median(samples.filter { it.fanout == 4 && it.threads == 1 }.map { it.recWallMs })
                    val table = mutableListOf<String>()
                    table.add("FIXTURE ${fx.label} lines=${baseline.texts.size} boxes=${boxes.size} " +
                        "repeats=$REPEATS baselineMed4_1=${baseMed}ms")
                    for (cell in CELLS) {
                        val cs = samples.filter { it.fanout == cell.first && it.threads == cell.second }
                            .sortedBy { it.seq }
                        val walls = cs.map { it.recWallMs }
                        val sorted = walls.sorted()
                        val med = median(walls)
                        val perLine = med.toDouble() / cs[0].lines
                        val ratio = baseMed.toDouble() / med
                        val slope = slopePerSample(walls)
                        table.add(
                            "ROW ${fx.label} f=${cell.first} t=${cell.second} med=${med}ms " +
                                "min=${sorted.first()} p10=${pct(sorted, 0.1)} p90=${pct(sorted, 0.9)} " +
                                "max=${sorted.last()} spread=${sorted.last() - sorted.first()}ms " +
                                "perLine=${"%.1f".format(perLine)}ms vsBase=${"%.3f".format(ratio)}x " +
                                "lines=${cs[0].lines} slope=${"%.2f".format(slope)}ms/sample " +
                                "text=OK walls=$walls"
                        )
                    }
                    // Drift: per-repeat median over all cells. A flat sequence means
                    // the interleave did its job and no cell is being throttled.
                    val roundMed = (0 until REPEATS).map { r ->
                        median(samples.filter { it.repeat == r }.map { it.recWallMs })
                    }
                    val poolSlope = slopePerSample(roundMed)
                    table.add("DRIFT ${fx.label} roundMedians=$roundMed slope=${"%.2f".format(poolSlope)}ms/repeat " +
                        "firstVsLast=${roundMed.last() - roundMed[0]}ms")
                    table.forEach { Log.i(TAG, it); report.add(it) }

                    val winner = CELLS.minByOrNull { cell ->
                        median(samples.filter { it.fanout == cell.first && it.threads == cell.second }
                            .map { it.recWallMs })
                    }!!
                    val winMed = median(
                        samples.filter { it.fanout == winner.first && it.threads == winner.second }
                            .map { it.recWallMs }
                    )
                    val twoTwo = median(
                        samples.filter { it.fanout == 2 && it.threads == 2 }.map { it.recWallMs }
                    )
                    Log.i(
                        TAG,
                        "VERDICT ${fx.label} baseline4_1=${baseMed}ms twoTwo=${twoTwo}ms " +
                            "predicted_2_2_beats_4_1=${twoTwo < baseMed} " +
                            "winner=f${winner.first}/t${winner.second} at ${winMed}ms " +
                            "saving=${baseMed - winMed}ms (${"%.1f".format(100.0 * (baseMed - winMed) / baseMed)}%)"
                    )
                } finally {
                    detEng.close()
                    bmp.recycle()
                }
            }
            val bundle = android.os.Bundle().apply {
                putString("fanoutSweep", report.joinToString("\n"))
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
        } finally {
            OcrEngine.REC_FANOUT = originalFanout
            OcrEngine.REC_THREADS = originalThreads
            OcrEngine.REC_BATCH_SIZE = originalBatch
        }
    }
}
