package com.holopengin.instantjpdict

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.JapaneseUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.nav_graph_core.BoundingBox
import uniffi.nav_graph_core.CtcDecode
import uniffi.nav_graph_core.GapCell
import uniffi.nav_graph_core.GapPlanLine
import uniffi.nav_graph_core.blankGapMedian
import uniffi.nav_graph_core.blankGapsPlan

/**
 * Decode/post-processing device gate for the book-page fixture.
 *
 * Runs the app's own path (`detect` -> `recognizeStreaming` -> `BlankGaps`) so
 * the numbers are the page's, not a synthetic loop:
 *
 * 1. `BlankGaps.apply` — the plan lane: the detector's own inputs cross once and
 *    the host grows its own lists. The gate checks the insertion count against
 *    the growth of every list the plan flags, and that a second pass is a no-op.
 *    (The byte-equivalence with the retired `blankGapsApply` round trip is pinned
 *    Rust-side by `applying_the_plan_on_the_host_reproduces_apply`; the deleted
 *    crossing cannot be measured here any more.)
 * 2. `CtcDecode.decodeTopKCompact` — the compact result's row/emitted-index
 *    identity, and the vertical fold against the host's own
 *    `JapaneseUtil` map. Cost: the folded decode against the old "decode then
 *    map three lists" lane.
 */
@RunWith(AndroidJUnit4::class)
class DecodePostFfiBenchmarkTest {

    private val topK = 15

    private fun percentile(values: LongArray, p: Int): Double {
        val s = values.sortedArray()
        return s[((s.size - 1) * p / 100.0).toInt()] / 1000.0
    }

    /** Interleaved p50 for a before/after pair on one payload. */
    private fun measurePair(
        samples: Int,
        warmups: Int,
        before: () -> Any,
        after: () -> Any,
    ): Pair<Double, Double> {
        repeat(warmups) { before(); after() }
        val b = LongArray(samples)
        val a = LongArray(samples)
        var sink = 0
        fun timed(out: LongArray, i: Int, block: () -> Any) {
            val t0 = System.nanoTime()
            val r = block()
            out[i] = System.nanoTime() - t0
            sink = sink xor r.hashCode()
        }
        for (i in 0 until samples) {
            if (i and 1 == 0) { timed(b, i, before); timed(a, i, after) } else { timed(a, i, after); timed(b, i, before) }
        }
        check(sink != Int.MIN_VALUE)
        return percentile(b, 50) to percentile(a, 50)
    }

    /**
     * The payload the facade hands `blankGapsPlan`, built here rather than
     * borrowed: `BlankGaps.toGapPlanLine` is `internal`, which the JVM unit-test
     * classpath can see but this instrumentation APK cannot. A mirror, so the
     * benchmark measures the same bytes the production path crosses — check it
     * against `BlankGaps.toGapPlanLine` when that visibility changes.
     */
    private fun toGapPlanLine(l: LineResult, withRaw: Boolean = false): GapPlanLine = GapPlanLine(
        text = l.text,
        isVertical = l.isVertical,
        charBoxes = l.charBoxes.map { BoundingBox(it.left, it.top, it.right - it.left, it.bottom - it.top) },
        charCols = l.charCols.toList(),
        alternativesLen = l.alternatives.size.toLong(),
        rawAlternatives = if (withRaw) {
            l.rawAlternatives.map { alts -> alts.map { (c, s) -> GapCell(c.code, s) } }
        } else {
            emptyList()
        },
        cropW = l.cropW,
        cropH = l.cropH,
        seqLenTotal = l.seqLenTotal,
    )

    private fun collectLines(): List<LineResult> {
        val instr = InstrumentationRegistry.getInstrumentation()
        val bmp = instr.context.assets.open("bookpage.png").use {
            BitmapFactory.decodeStream(it)
        }!!
        val detBoxes = engine.detect(bmp)
        Log.i(TAG, "det ${detBoxes.size} boxes on ${bmp.width}x${bmp.height}")
        val collected = mutableListOf<Pair<Int, LineResult>>()
        runBlocking {
            engine.recognizeStreaming(bmp, detBoxes) { pairs ->
                synchronized(collected) { collected.addAll(pairs) }
            }
            var waited = 0
            var last = -1
            var still = 0
            while (waited < 30_000) {
                delay(200)
                waited += 200
                synchronized(collected) {
                    if (collected.size == last) still += 200 else { still = 0; last = collected.size }
                }
                if (last >= detBoxes.size) break
                if (waited >= 8_000 && still >= 4_000) break
            }
        }
        val lines = collected.sortedBy { it.first }.map { it.second }
        Log.i(TAG, "collected ${lines.size} lines")
        return lines
    }

    // ── 1. blank gaps: the plan lane ─────────────────────────────────────────

    @Test
    fun gapPlan_inserts_and_is_cheaper() {
        val lines = collectLines()
        assertTrue("no lines recognized", lines.isNotEmpty())
        val verticals = lines.filter { it.isVertical }

        var insertedLines = 0
        var insertedChars = 0
        for (l in lines) {
            // Mirror the facade exactly: a first call without the raw lists, and
            // the rare retry when it says the walk would have been read.
            var plan = blankGapsPlan(toGapPlanLine(l, withRaw = false))
            if (plan.needsRawAlternatives && plan.insertions.isEmpty()) {
                plan = blankGapsPlan(toGapPlanLine(l, withRaw = true))
            }
            val viaPlan = BlankGaps.apply(l)
            val delta = plan.insertions.size
            if (delta > 0) {
                insertedLines++
                insertedChars += delta
                assertEquals(
                    "text grows by one BMP placeholder per insertion",
                    l.text.length + delta,
                    viaPlan.text.length,
                )
                if (plan.growsCharBoxes) {
                    assertEquals("boxes grow by one per insertion", l.charBoxes.size + delta, viaPlan.charBoxes.size)
                }
                if (plan.growsCharCols) {
                    assertEquals("cols grow by one per insertion", l.charCols.size + delta, viaPlan.charCols.size)
                }
                if (plan.growsAlternatives) {
                    assertEquals(
                        "alternatives grow by one per insertion",
                        l.alternatives.size + delta,
                        viaPlan.alternatives.size,
                    )
                }
            }
            // Idempotent: the placeholder is already there, so a second pass is a no-op.
            val again = BlankGaps.apply(viaPlan)
            assertEquals("idempotent text", viaPlan.text, again.text)
            assertEquals("idempotent boxes", viaPlan.charBoxes, again.charBoxes)
        }
        Log.i(
            TAG,
            "STATS lines=${lines.size} vertical=${verticals.size} " +
                "chars=${lines.sumOf { it.text.length }} " +
                "rawCells=${lines.sumOf { l -> l.rawAlternatives.sumOf { it.size } }} " +
                "altCells=${lines.sumOf { l -> l.alternatives.sumOf { it.size } }} " +
                "boxes=${lines.sumOf { it.charBoxes.size }} " +
                "planned=$insertedLines inserted=$insertedChars"
        )
        for ((i, l) in lines.withIndex()) {
            Log.i(TAG, "LINE i=$i v=${l.isVertical} text=${BlankGaps.apply(l).text}")
        }

        // Cost, over the page's vertical lines (the ones the pipeline materialises).
        val subject = verticals.ifEmpty { lines }
        fun p(name: String, before: () -> Any, after: () -> Any) {
            val (o, n) = measurePair(samples = 40, warmups = 10, before = before, after = after)
            Log.i(
                TAG,
                "BENCH item=$name n=${subject.size} lines oldP50Us=$o newP50Us=$n " +
                    "deltaP50Us=${"%.1f".format(n - o)}"
            )
        }
        val text = subject.map { it.text }
        val planArgs = subject.map { toGapPlanLine(it) }
        p("BlankGaps_page", before = { text }, after = {
            subject.map { BlankGaps.apply(it) }
        })
        p("BlankGaps_plan_only", before = { text }, after = {
            planArgs.map { blankGapsPlan(it) }
        })
        p("BlankGaps_kt_prelude_only", before = { text }, after = {
            subject.map { toGapPlanLine(it) }
        })
        // Ladder: where the per-call time goes. Every rung is 13 calls over the
        // same lines, so the fixed cost of one crossing is visible on its own.
        p("ladder_median_1arg", before = { text }, after = {
            subject.map { blankGapMedian(listOf(1f, 2f, 3f)) }
        })
        val emptyRecord = toGapPlanLine(subject.first())
        p("ladder_plan_empty_record", before = { text }, after = {
            subject.map { blankGapsPlan(emptyRecord) }
        })
        p("ladder_plan_real", before = { text }, after = {
            planArgs.map { blankGapsPlan(it) }
        })
    }

    // ── 2. the decode result: compact identity and the fold ──────────────────

    private fun decoder(): CtcDecode {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val vocab = assets.open("PP-OCRv6_small_ncnn/vocab.json").use { stream ->
            val text = stream.reader().readText()
            Gson().fromJson<List<String>>(text, object : TypeToken<List<String>>() {}.type)
        }
        val remap = assets.open("PP-OCRv6_small_ncnn/rec_remap.txt").use { stream ->
            stream.reader().readLines()
                .filter { l -> l.isNotBlank() && !l.startsWith("#") }
                .map { l -> l.trim().toInt() }
        }
        return CtcDecode(vocab, remap)
    }

    /** The host's re-expansion of the compact result, exactly as `OcrEngine.toPpoResult`. */
    private fun rows(r: uniffi.nav_graph_core.CompactCtcDecodeResult): List<List<Pair<Char, Float>>> =
        List(r.rawRows.size - 1) { i ->
            val from = r.rawRows[i].toInt()
            val to = r.rawRows[i + 1].toInt()
            ArrayList<Pair<Char, Float>>(to - from).apply {
                for (k in from until to) add(Char(r.rawAlternatives[k].ch) to r.rawAlternatives[k].score)
            }
        }

    @Test
    fun compactDecode_row_identity_and_fold_cost() {
        val lines = collectLines()
        val decoder = decoder()
        try {
            var cells = 0
            var altCells = 0
            var costSum = 0.0
            for (l in lines) {
                val seq = l.seqLenTotal
                if (seq <= 0) continue
                val list = syntheticPacked(seq, decoder).asList()

                val compact = decoder.decodeTopKCompact(list, seq.toLong(), false)
                val vertical = decoder.decodeTopKCompact(list, seq.toLong(), true)

                // Identity 1: the row boundaries partition the flat cells.
                val raw = rows(compact)
                assertEquals("rawRows starts at 0", 0L, compact.rawRows.first())
                assertEquals(
                    "rawRows ends at the cell count",
                    compact.rawAlternatives.size.toLong(),
                    compact.rawRows.last(),
                )
                assertEquals("one boundary per row plus one", raw.size + 1, compact.rawRows.size)

                // Identity 2: every emitted alternative is the raw row at its timestep,
                // which is what the host indexes back through.
                val alts = compact.altRows.map { raw[it.toInt()] }
                assertTrue("altRows ascending", compact.altRows.zipWithNext().all { (a, b) -> a < b })
                assertTrue("altRows in range", compact.altRows.all { it in raw.indices })
                assertEquals(
                    "one emitted row per decoded char",
                    compact.text.codePointCount(0, compact.text.length),
                    alts.size,
                )

                // The vertical fold is the host's own map, 1:1, on text and both lists.
                assertEquals(JapaneseUtil.verticalPunctuation(compact.text), vertical.text)
                assertEquals(compact.charCols, vertical.charCols)
                assertEquals(compact.seqLenTotal, vertical.seqLenTotal)
                assertEquals(compact.altRows, vertical.altRows)
                val handRaw = raw.map { row ->
                    row.map { (c, s) -> JapaneseUtil.verticalPunctuationChar(c) to s }
                }
                assertEquals(handRaw, rows(vertical))

                cells += compact.rawAlternatives.size
                altCells += compact.altRows.size

                // Cost: the folded decode against the old "decode then map three
                // lists" lane. The list maps stay in both lanes so this measures
                // the fold, not the host's array building.
                val (folded, mapped) = measurePair(
                    samples = 60,
                    warmups = 20,
                    before = {
                        val r = decoder.decodeTopKCompact(list, seq.toLong(), true)
                        Triple(r.text, r.altRows, r.rawAlternatives.size)
                    },
                    after = {
                        val r = decoder.decodeTopKCompact(list, seq.toLong(), false)
                        Triple(
                            JapaneseUtil.verticalPunctuation(r.text),
                            r.altRows,
                            r.rawAlternatives.size,
                        )
                    },
                )
                costSum += folded - mapped
            }
            Log.i(
                TAG,
                "BENCH item=ctc_decodeTopKCompact_page n=${lines.size} rawCells=$cells altCells=$altCells " +
                    "foldSavingUs=${"%.1f".format(costSum)}"
            )
        } finally {
            decoder.close()
        }
    }

    private fun syntheticPacked(seq: Int, decoder: CtcDecode): FloatArray {
        // Realistic top-K table: descending distinct scores, class ids spread
        // over the head, so the decode emits and collapses like a real line.
        val classes = 13_353
        val out = FloatArray(seq * topK * 2)
        for (t in 0 until seq) {
            // One winner class per four timesteps: the first emits, the other
            // three are CTC repeats that collapse. A blank every eighth step.
            val step = t / 4
            val winner = when {
                t % 8 == 7 -> 0
                else -> 1 + (step * 7) % (classes - 1)
            }
            for (k in 0 until topK) {
                val c = (winner + k * 131) % classes
                out[(t * topK + k) * 2] = c.toFloat()
                out[(t * topK + k) * 2 + 1] = (2_000_000 - t * 100 - k).toFloat()
            }
        }
        return out
    }

    companion object {
        private const val TAG = "FfiPostBench"
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
