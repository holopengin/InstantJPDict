package com.holopengin.instantjpdict

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.JapaneseUtil
import com.holopengin.instantjpdict.util.toGapLine
import com.holopengin.instantjpdict.util.toLineResult
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
import uniffi.nav_graph_core.blankGapsApply
import uniffi.nav_graph_core.blankGapMedian
import uniffi.nav_graph_core.blankGapsPlan

/**
 * Decode/post-processing FFI before/after gate for the book-page fixture.
 *
 * Both lanes run on the app's own path (`detect` → `recognizeStreaming` →
 * `BlankGaps`) so the numbers are the page's, not a synthetic loop, and both
 * compare the *old* crossing against the *new* one in the same process, on the
 * same payloads, alternating sample order:
 *
 * 1. `BlankGaps.apply` — the whole `LineResult` across and back
 *    (`blankGapsApply` + the record conversions) against `blankGapPlan` + the
 *    host growing its own lists. Parity: both lanes must produce the identical
 *    `LineResult`, and the rendered line text is logged for the cross-build diff.
 * 2. `CtcDecode.decodeTopK` — the nested result materialised against the compact
 *    one re-expanded the way `OcrEngine.toPpoResult` does it. Parity: the
 *    re-expansion is the nested result, and the vertical fold is the host's own
 *    `JapaneseUtil` map.
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

    /** The old lane: the whole line across, and the whole line back. */
    private fun oldApply(l: LineResult): LineResult = blankGapsApply(l.toGapLine()).toLineResult(l)

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

    // ── 1. blank gaps: the plan against the whole-line round trip ───────────

    @Test
    fun gapPlan_matches_blankGapsApply_and_is_cheaper() {
        val lines = collectLines()
        assertTrue("no lines recognized", lines.isNotEmpty())
        val verticals = lines.filter { it.isVertical }
        val firstPlan = verticals.map {
            blankGapsPlan(toGapPlanLine(it))
        }
        Log.i(
            TAG,
            "STATS lines=${lines.size} vertical=${verticals.size} " +
                "chars=${lines.sumOf { it.text.length }} " +
                "rawCells=${lines.sumOf { l -> l.rawAlternatives.sumOf { it.size } }} " +
                "altCells=${lines.sumOf { l -> l.alternatives.sumOf { it.size } }} " +
                "boxes=${lines.sumOf { it.charBoxes.size }} " +
                "needsRaw=${firstPlan.count { it.needsRawAlternatives }} " +
                "planned=${firstPlan.count { it.insertions.isNotEmpty() }} " +
                "oldLaneCells=${lines.sumOf { l -> l.rawAlternatives.sumOf { it.size } + l.alternatives.sumOf { it.size } }}"
        )

        // Parity: the plan lane's line is the round-trip lane's line, field for field.
        //
        // One documented exception, and it is a *loss the plan lane removes*: the
        // round trip carries every alternative through `GapCell.ch: Int`, and a
        // lone surrogate (the first UTF-16 unit of a supplementary-plane
        // vocabulary entry, which the boundary deliberately keeps) is not a Rust
        // `char`, so it comes back as U+FFFD. The plan lane never carries the
        // alternatives, so it keeps the cell the decode produced. The gate
        // therefore asserts the diff is *exactly* that: the old lane's cell is
        // U+FFFD and the new lane's is the source's lone surrogate.
        var inserted = 0
        var surrogateCells = 0
        for (l in lines) {
            val viaPlan = BlankGaps.apply(l)
            val viaLine = oldApply(l)
            assertEquals("text for '${l.text}'", viaLine.text, viaPlan.text)
            assertEquals("boxes", viaLine.charBoxes, viaPlan.charBoxes)
            assertTrue("cols", viaLine.charCols.contentEquals(viaPlan.charCols))
            assertEquals("overrides", viaLine.overrides, viaPlan.overrides)
            assertEquals("vertical", viaLine.isVertical, viaPlan.isVertical)
            assertEquals("raw", viaLine.rawAlternatives, viaPlan.rawAlternatives)
            assertEquals("alternatives size", viaLine.alternatives.size, viaPlan.alternatives.size)
            for (i in viaPlan.alternatives.indices) {
                val a = viaLine.alternatives[i]
                val b = viaPlan.alternatives[i]
                val src = if (l.alternatives.size == viaPlan.alternatives.size) l.alternatives[i] else null
                assertEquals("alternatives row $i size", a.size, b.size)
                for (j in a.indices) {
                    if (a[j] == b[j]) continue
                    assertEquals("row $i cell $j char", '�', a[j].first)
                    val keep = src?.get(j)
                    assertEquals("row $i cell $j kept", keep, b[j])
                    assertTrue(
                        "row $i cell $j is a lone surrogate: U+${keep?.first?.code?.toString(16)}",
                        keep != null && keep.first.code in 0xD800..0xDFFF,
                    )
                    assertEquals("row $i cell $j score", a[j].second, b[j].second, 0f)
                    surrogateCells++
                }
            }
            if (viaPlan.text != l.text) inserted++
        }
        Log.i(
            TAG,
            "PARITY gapPlan == blankGapsApply on ${lines.size} lines ($inserted with an insertion, " +
                "$surrogateCells lone-surrogate cells the round trip would have flattened to U+FFFD)"
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
        p("BlankGaps_page", before = { subject.map { oldApply(it) } }, after = { subject.map { BlankGaps.apply(it) } })
        p("BlankGaps_plan_only", before = { text }, after = {
            planArgs.map { blankGapsPlan(it) }
        })
        p("BlankGaps_kt_prelude_only", before = { text }, after = {
            subject.map { toGapPlanLine(it) }
        })
        // Ladder: where the per-call time goes. Every rung is 13 calls over the
        // same lines, so the fixed cost of one crossing is visible on its own.
        val blank = subject.map {
            it.copy(text = "", charBoxes = emptyList(), alternatives = emptyList(),
                charCols = floatArrayOf(), rawAlternatives = emptyList()).toGapLine()
        }
        val realArgs = subject.map { it.toGapLine() }
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
        p("ladder_apply_empty_line", before = { text }, after = { blank.map { blankGapsApply(it) } })
        p("ladder_apply_real_line", before = { text }, after = { realArgs.map { blankGapsApply(it) } })
        p("ladder_lower_real_line_only", before = { text }, after = { subject.map { it.toGapLine() } })
    }

    // ── 2. the decode result: compact against nested ─────────────────────────

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
    fun compactDecode_matches_the_nested_result_and_is_cheaper() {
        val lines = collectLines()
        val decoder = decoder()
        try {
            var oldSum = 0.0
            var newSum = 0.0
            var cells = 0
            var altCells = 0
            for (l in lines) {
                val seq = l.seqLenTotal
                if (seq <= 0) continue
                val packed = syntheticPacked(seq, decoder)
                val list = packed.asList()

                val nested = decoder.decodeTopK(list, seq.toLong())
                val compact = decoder.decodeTopKCompact(list, seq.toLong(), false)
                val vertical = decoder.decodeTopKCompact(list, seq.toLong(), true)

                // Parity 1: the compact re-expansion is the nested result.
                assertEquals(nested.text, compact.text)
                assertEquals(nested.charCols, compact.charCols)
                assertEquals(nested.seqLenTotal, compact.seqLenTotal)
                val raw = rows(compact)
                val alts = compact.altRows.map { raw[it.toInt()] }
                for ((a, b) in nested.rawAlternatives.zip(raw)) {
                    assertEquals(a.map { Char(it.ch) to it.score }, b)
                }
                for ((a, b) in nested.alternatives.zip(alts)) {
                    assertEquals(a.map { Char(it.ch) to it.score }, b)
                }
                assertEquals(nested.alternatives.size, alts.size)
                assertEquals(compact.altRows.size, nested.alternatives.size)

                // Parity 2: the vertical fold is the host's own map.
                val handText = JapaneseUtil.verticalPunctuation(nested.text)
                assertEquals(handText, vertical.text)
                val handRaw = nested.rawAlternatives.map { row ->
                    row.map { JapaneseUtil.verticalPunctuationChar(Char(it.ch)) to it.score }
                }
                assertEquals(handRaw, rows(vertical))
                val handAlts = nested.alternatives.map { row ->
                    row.map { JapaneseUtil.verticalPunctuationChar(Char(it.ch)) to it.score }
                }
                assertEquals(handAlts, vertical.altRows.map { rows(vertical)[it.toInt()] })
                assertEquals(vertical.charCols, nested.charCols)
                assertEquals(vertical.seqLenTotal, nested.seqLenTotal)

                cells += nested.rawAlternatives.size * topK
                altCells += nested.alternatives.size * topK

                val (o, n) = measurePair(
                    samples = 60,
                    warmups = 20,
                    before = {
                        val r = decoder.decodeTopK(list, seq.toLong())
                        r.alternatives.map { row -> row.map { Char(it.ch) to it.score } } +
                            r.rawAlternatives.map { row -> row.map { Char(it.ch) to it.score } }
                    },
                    after = {
                        val r = decoder.decodeTopKCompact(list, seq.toLong(), false)
                        val rws = rows(r)
                        r.altRows.map { rws[it.toInt()] }
                    },
                )
                oldSum += o
                newSum += n
            }
            Log.i(
                TAG,
                "BENCH item=ctc_decodeTopK_page_median_sum n=$cells rawCells=$cells altCells=$altCells " +
                    "oldP50Us=$oldSum newP50Us=$newSum deltaP50Us=${"%.1f".format(newSum - oldSum)}"
            )

            // Lane 3: the vertical punctuation fold. Before: three more exports
            // per vertical line (the text, the per-character alternatives, the
            // per-timestep ones) plus the Kotlin remap. After: one flag on the
            // decode that is already running.
            var punctOld = 0.0
            var punctNew = 0.0
            var punctChars = 0
            for (l in lines) {
                val seq = l.seqLenTotal
                if (seq <= 0 || !l.isVertical) continue
                val list = syntheticPacked(seq, decoder).asList()
                val (o, n) = measurePair(
                    samples = 60,
                    warmups = 20,
                    before = {
                        // Exactly what the emit path does today: the batched
                        // per-list map, three crossings for a vertical line.
                        val r = decoder.decodeTopK(list, seq.toLong())
                        val text = japaneseVerticalPunctuation(r.text)
                        val alts = JapaneseUtil.verticalPunctuationAlternatives(
                            r.alternatives.map { row -> row.map { Char(it.ch) to it.score } }
                        ).map { it.toMutableList() }
                        val raw = JapaneseUtil.verticalPunctuationAlternatives(
                            r.rawAlternatives.map { row -> row.map { Char(it.ch) to it.score } }
                        )
                        Triple(text, alts, raw)
                    },
                    after = {
                        val r = decoder.decodeTopKCompact(list, seq.toLong(), true)
                        Triple(r.text, r.altRows, r.rawAlternatives)
                    },
                )
                punctOld += o
                punctNew += n
                punctChars += seq
            }
            Log.i(
                TAG,
                "BENCH item=verticalPunctuation_page n=$punctChars chars " +
                    "oldP50Us=$punctOld newP50Us=$punctNew deltaP50Us=${"%.1f".format(punctNew - punctOld)}"
            )
        } finally {
            decoder.close()
        }
    }

    private fun japaneseVerticalPunctuation(t: String): String =
        JapaneseUtil.verticalPunctuation(t)

    private fun verticalPunctuationChar(c: Char): Char =
        JapaneseUtil.verticalPunctuationChar(c)

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
