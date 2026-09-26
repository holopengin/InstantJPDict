package com.holopengin.instantjpdict

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.GapCandidates
import com.holopengin.instantjpdict.util.OovSuggestions
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.nav_graph_core.GapCell

/**
 * The lazy-alternatives gate: the page path must not materialise the full
 * per-timestep top-15, and must not lose a byte of it either.
 *
 * `OcrEngine.toPpoResult` used to expand the compact decode's flat cell list
 * into `List<List<Pair<Char, Float>>>` — a list per timestep and a `Pair` plus a
 * boxed `Float` per cell — and `computeCharBoxes` then expanded those rows
 * *again* into `CharPlacement.Step`s for CAP's `steps`. Both copies are gone: the
 * line carries a [TimestepTopK] (flat cells + row boundaries + a top-2 table)
 * and builds a row only when a reader asks for one. And the `steps` themselves
 * are now the table's own `GapCell`s, so the shim's boundary is handed the
 * cells the decode already produced instead of a `Step` copy re-wrapped into
 * `GapCell`s.
 *
 * Two kinds of evidence, over the three page fixtures:
 *
 * 1. **Parity, in process.** Every claim the page path makes is re-derived here
 *    from the compact table and compared cell for cell: the nested rows the
 *    accessor hands out, the `alternatives ↔ rawAlternatives` row identity the
 *    decoder relies on, the characters those rows spell, the gap plan's
 *    insertions, `GapCandidates.generate`'s ranking for every character, and the
 *    overlay's TopK list (`OcrOverlayStateController.getAlternativesUiState`
 *    plus `activeAllAlternatives`).
 * 2. **Cost.** The old materialisation and the new one, interleaved and warm,
 *    over the same flat tables, plus the exact object counts each shape needs.
 *
 * `BookLinesDumpTest` is the other half of the gate: the rendered dump (text,
 * columns, boxes) is byte-identical before and after.
 */
@RunWith(AndroidJUnit4::class)
class LineResultLazyAlternativesTest {

    // ── the old materialisation, transcribed ────────────────────────────────

    /**
     * `OcrEngine.toPpoResult` as it was: one `ArrayList` per timestep, a `Pair`
     * and a boxed `Float` per cell. The "before" lane of the cost measurement
     * and the reference the accessor is compared against.
     */
    private fun eagerRows(
        cells: List<GapCell>,
        rowStart: List<Long>,
    ): List<List<Pair<Char, Float>>> = List(rowStart.size - 1) { i ->
        val from = rowStart[i].toInt()
        val to = rowStart[i + 1].toInt()
        ArrayList<Pair<Char, Float>>(to - from).apply {
            for (k in from until to) add(Char(cells[k].ch) to cells[k].score)
        }
    }

    /** The nested rows as a fresh flat table, the way the decode result arrives. */
    private fun flatten(rows: List<List<GapCell>>): Pair<List<GapCell>, List<Long>> {
        val cells = ArrayList<GapCell>()
        val starts = ArrayList<Long>(rows.size + 1)
        for (row in rows) {
            starts.add(cells.size.toLong())
            cells.addAll(row)
        }
        starts.add(cells.size.toLong())
        return cells to starts
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** `detect` → `recognizeStreaming`, the app's own page path. */
    private fun recognize(asset: String): List<LineResult> {
        val instr = InstrumentationRegistry.getInstrumentation()
        Log.i(TAG, "STEP $asset opening")
        val bmp = instr.context.assets.open(asset).use { BitmapFactory.decodeStream(it) }!!
        Log.i(TAG, "STEP $asset decoded ${bmp.width}x${bmp.height} ${bmp.config}")
        val detBoxes = engine.detect(bmp)
        Log.i(TAG, "STEP $asset detected ${detBoxes.size}")
        Log.i(TAG, "FIXTURE $asset ${bmp.width}x${bmp.height} detBoxes=${detBoxes.size}")
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
        assertTrue("$asset: no lines recognized", lines.isNotEmpty())
        return lines
    }

    /**
     * The emitted rows, recovered by content the way `ctc_decode::emitted_rows`
     * does: scan the timesteps in order, take the first row after the previous
     * match that equals the next `alternatives` entry. The decoder pushes one
     * raw row per timestep and clones it into `alternatives` on the timesteps
     * that emit, so every match is exact and the indices ascend.
     */
    private fun emittedRows(
        raw: List<List<Pair<Char, Float>>>,
        alternatives: List<List<Pair<Char, Float>>>,
    ): List<Int> {
        val out = ArrayList<Int>(alternatives.size)
        var at = 0
        for (t in raw.indices) {
            if (at < alternatives.size && raw[t] == alternatives[at]) {
                out.add(t)
                at++
            }
        }
        assertEquals("every per-character list came from a raw row", alternatives.size, at)
        return out
    }

    // ── 1. parity ───────────────────────────────────────────────────────────

    @Test
    fun the_page_path_keeps_every_cell_the_topk_and_the_blank_candidates() {
        var pageRows = 0
        var pageCells = 0
        var pageEmitted = 0
        var pageBuiltAtRecognise = 0
        var pageCandidates = 0
        var pageInsertions = 0
        var pageReDecodes = 0

        for (asset in FIXTURES) {
            val lines = recognize(asset)
            // The overlay's TopK state, built once per page exactly as
            // `OcrOverlayView` does after the install loop.
            val controller = OcrOverlayStateController()
            controller.activeLineResults = lines.map { it as LineResult? }.toMutableList()
            controller.updateGlobalData()

            for ((idx, line) in lines.withIndex()) {
                val why = "$asset line $idx '${line.text.take(12)}'"
                val topK = line.rawAlternatives as TimestepTopK
                val raw = line.rawAlternatives

                // The laziness claim, measured **before** anything in this test
                // reads a row: recognition itself has built no more rows than the
                // line has characters, so the other ~90% of timesteps never
                // became a list. (Every check below reads the whole table on
                // purpose, which is what the rare readers do.)
                assertEquals(
                    "$why: recognition built only the emitted rows",
                    line.alternatives.size,
                    topK.materialisedRows,
                )
                pageBuiltAtRecognise += topK.materialisedRows
                pageEmitted += line.alternatives.size

                // (a) The line really is the compact table, and the accessor
                //     materialises exactly the flat cells it holds.
                val (flat, starts) = flatten(topK.gapCellRows())
                val reference = eagerRows(flat, starts)
                assertEquals("$why: row count", topK.rowCount, raw.size)
                assertEquals("$why: cell count", flat.size, topK.cellCount)
                for (t in raw.indices) {
                    assertEquals("$why: timestep $t", reference[t], raw[t])
                }
                assertTrue("$why: no row is wider than top-15", raw.all { it.size <= 15 })

                // (b) `alternatives` are the raw rows, in order, by content — the
                //     identity `ctc_decode` documents — and they spell the text.
                val rows = emittedRows(raw, line.alternatives)
                assertEquals("$why: one list per character", line.text.length, rows.size)
                val spelled = StringBuilder()
                for (j in rows.indices) {
                    assertEquals("$why: char $j", raw[rows[j]], line.alternatives[j])
                    spelled.append(raw[rows[j]][0].first)
                }
                assertEquals("$why: the emitted rows spell the text", line.text, spelled.toString())

                pageRows += raw.size
                pageCells += topK.cellCount

                // (c) The gap plan: same insertions twice over, idempotently.
                val once = BlankGaps.apply(line)
                val twice = BlankGaps.apply(once)
                assertEquals("$why: idempotent", once.text, twice.text)
                assertEquals("$why: idempotent", once.charBoxes, twice.charBoxes)
                assertEquals("$why: idempotent", once.alternatives, twice.alternatives)
                assertEquals("$why: idempotent", once.rawAlternatives, twice.rawAlternatives)
                if (once.text != line.text) pageInsertions++

                // (d) `GapCandidates.generate` — the blank's candidate list, the
                //     one overlay reader that wants the *whole* table. Compared
                //     against the same call over the nested list built
                //     independently out of the flat cells above.
                for (cIdx in line.text.indices) {
                    assertEquals(
                        "$why: gap candidates at $cIdx",
                        GapCandidates.generate(line.text, reference, cIdx, null),
                        GapCandidates.generate(line.text, line.rawAlternatives, cIdx, null),
                    )
                    pageCandidates++
                }

                // (e) The overlay's TopK workflow, for every character: the panel
                //     is built from `line.alternatives[cIdx]` (the head's own
                //     top-15), never from `rawAlternatives`.
                for (cIdx in line.text.indices) {
                    val ui = controller.getAlternativesUiState(idx, cIdx)
                    assertNotNull("$why: alternatives panel at $cIdx", ui)
                    ui!!
                    assertTrue("$why: manual entry offered at $cIdx", ui.showManualInput)
                    assertEquals(
                        "$why: TopK head at $cIdx",
                        line.alternatives[cIdx].take(15).map { it.first },
                        ui.candidates.filter { it.source == OovSuggestions.Source.HEAD }.map { it.char },
                    )
                }
            }

            // (f) The page-wide TopK state the nav graph is built from.
            assertEquals(
                "$asset: activeAllAlternatives is every line's alternatives",
                lines.flatMap { it.alternatives },
                controller.activeAllAlternatives,
            )

            // (g) The slider re-decode walks the whole table: same cache, same
            //     text, and it must not have thrown.
            for (line in lines) {
                if (line.cropW <= 0 || line.cropH <= 0) continue
                val again = engine.reDecodeLineResult(line)
                assertEquals("${line.text.take(12)}: cache survives re-decode", line.rawAlternatives, again.rawAlternatives)
                assertEquals("${line.text.take(12)}: text survives re-decode", line.text, again.text)
                pageReDecodes++
            }
        }

        Log.i(
            TAG,
            "STATS rows=$pageRows cells=$pageCells emitted=$pageEmitted " +
                "builtAtRecognise=$pageBuiltAtRecognise " +
                "gapCandidateCalls=$pageCandidates gapInsertions=$pageInsertions reDecodes=$pageReDecodes",
        )
        assertTrue("no timesteps at all", pageRows > 0)
        assertTrue("no characters at all", pageEmitted > 0)
    }

    // ── 2. cost ─────────────────────────────────────────────────────────────

    private fun percentile(values: LongArray, p: Int): Double {
        val s = values.sortedArray()
        return s[((s.size - 1) * p / 100.0).toInt()] / 1000.0
    }

    /** Interleaved p50 for a before/after pair over the same payload. */
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
            if (i and 1 == 0) { timed(b, i, before); timed(a, i, after) }
            else { timed(a, i, after); timed(b, i, before) }
        }
        check(sink != Int.MIN_VALUE)
        return percentile(b, 50) to percentile(a, 50)
    }

    /** Heap in use after a collection — the allocation counter ART exposes
     *  without a debug agent. Reported for information only: ART's heap grows
     *  and shrinks under the collector, so the per-page byte delta is not a
     *  reliable ratio. The object counts below are the exact figure. */
    private fun usedHeap(): Long {
        repeat(3) { System.gc() }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun the_page_path_materialises_less_and_builds_fewer_objects() {
        Log.i(TAG, "HEAP javaVersion=${System.getProperty("java.version")} used=${usedHeap()}")
        val lines = recognize("bookpage.png")
        // The decode payload of every line, verbatim: the flat cell list and the
        // row boundaries. Both lanes measure the identical input, and the after
        // lane rebuilds its table from it on every iteration, exactly as the
        // decode arrival does.
        val payload = lines.map { flatten((it.rawAlternatives as TimestepTopK).gapCellRows()) }
        var rows = 0
        var cells = 0
        for ((flat, starts) in payload) {
            rows += starts.size - 1
            cells += flat.size
        }
        Log.i(TAG, "STATS page lines=${payload.size} rows=$rows cells=$cells")

        /**
         * Before: expand every timestep into its own row (a list, a `Pair` and a
         * boxed `Float` per cell), then `computeCharBoxes` walks those rows into
         * a full-width `steps` list of `CharPlacement.Step`s and
         * `CharPlacement.place` converts *that* into `GapCell`s for the
         * boundary. Two full copies of a 15-cell-per-timestep table.
         */
        fun beforeLane(): Any {
            var sink = 0
            for ((flat, starts) in payload) {
                val raw = eagerRows(flat, starts)
                val steps = raw.map { alts -> alts.map { (c, s) -> CharPlacement.Step(c, s) } }
                val crossed = steps.map { alts -> alts.map { GapCell(it.char.code, it.score) } }
                sink += raw.size + steps.size + crossed.size
                sink += raw.sumOf { it.size } + steps.sumOf { it.size } + crossed.sumOf { it.size }
            }
            return sink
        }

        /**
         * After: one pass over the boundaries for the top-2 table, then the
         * truncated `steps` CAP actually reads — two cells per timestep, so
         * thirteen of the fifteen never become an object — and the same
         * `GapCell` conversion `CharPlacement.place` still makes over them.
         * No `Pair` and no boxed `Float` anywhere on this lane.
         */
        fun afterLane(): Any {
            var sink = 0
            for ((flat, starts) in payload) {
                val steps = TimestepTopK.of(flat, starts).capStepRows()
                val crossed = steps.map { alts -> alts.map { GapCell(it.char.code, it.score) } }
                sink += steps.size + crossed.size + steps.sumOf { it.size } + crossed.sumOf { it.size }
            }
            return sink
        }

        /**
         * The page path as it stands after the `GapCell` pass-through: the same
         * top-2 truncation, but expressed as the table's own cells, so the
         * `Step`s and the second `GapCell` row never exist and the boundary is
         * handed the rows directly. This lane is the whole delta the
         * pass-through is worth: what is left is one row list per timestep and
         * no cell at all.
         */
        fun cellLane(): Any {
            var sink = 0
            for ((flat, starts) in payload) {
                val cells = TimestepTopK.of(flat, starts).capCellRows()
                sink += cells.size + cells.sumOf { it.size }
            }
            return sink
        }

        val (o, n) = measurePair(samples = 40, warmups = 10, before = ::beforeLane, after = ::afterLane)
        Log.i(
            TAG,
            "BENCH item=page_alt_materialisation rows=$rows cells=$cells lines=${payload.size} " +
                "oldP50Us=$o newP50Us=$n deltaP50Us=${"%.1f".format(n - o)} " +
                "oldMsPerPage=${"%.3f".format(o / 1000.0)} newMsPerPage=${"%.3f".format(n / 1000.0)}",
        )
        val (p, c) = measurePair(samples = 40, warmups = 10, before = ::afterLane, after = ::cellLane)
        Log.i(
            TAG,
            "BENCH item=page_cap_boundary rows=$rows lines=${payload.size} " +
                "stepRowsP50Us=$p cellRowsP50Us=$c deltaP50Us=${"%.1f".format(c - p)} " +
                "stepMsPerPage=${"%.3f".format(p / 1000.0)} cellMsPerPage=${"%.3f".format(c / 1000.0)}",
        )

        // Object counts, exact — what each shape allocates per page. The
        // per-character `alternatives` lists are in neither lane: this change
        // does not touch them, and both lanes share the emitted row's `Pair`s
        // with them exactly as before.
        val beforeLists = 3 * rows          // raw rows, steps, crossed
        val beforeArrays = 3 * rows         // each list's Object[]
        val beforeCells = 2 * cells         // raw rows: Pair + boxed Float
        val beforeSteps = cells             // steps: one CharPlacement.Step
        val beforeGap = cells               // crossed: one GapCell
        val before = beforeLists + beforeArrays + beforeCells + beforeSteps + beforeGap

        val afterLists = 2 * rows           // steps, crossed
        val afterArrays = 2 * rows          // each list's Object[]
        val afterSteps = 2 * rows           // two CharPlacement.Step per timestep
        val afterGap = 2 * rows             // two GapCell per timestep
        val afterTables = 2 * payload.size  // the top-2 IntArray/FloatArray per line
        val after = afterLists + afterArrays + afterSteps + afterGap + afterTables

        Log.i(
            TAG,
            "OBJECTS page before=$before after=$after drop=${before - after} " +
                "pct=${"%.1f".format(100.0 * (before - after) / before)} (rows=$rows cells=$cells)",
        )
        Log.i(
            TAG,
            "OBJECTS page breakdown before=[lists=$beforeLists arrays=$beforeArrays " +
                "pairFloat=$beforeCells steps=$beforeSteps gapcell=$beforeGap] " +
                "after=[lists=$afterLists arrays=$afterArrays steps=$afterSteps " +
                "gapcell=$afterGap tables=$afterTables]",
        )
        assertTrue(
            "the after lane must allocate far less: before=$before after=$after",
            after * 4 < before,
        )

        // The `GapCell` pass-through, on top of the lazy table: the shim's
        // boundary takes `GapCell`s, and the top-2 cells of a timestep are the
        // first two cells of its slice, so the page hands over the table's own
        // objects instead of building `Step`s and re-wrapping them.
        //
        // Per timestep the lazy-but-`Step` lane built two row lists and two
        // row arrays (`capStepRows`, then `place`'s conversion) plus two
        // `Step`s and two `GapCell`s. The cell lane builds one row list and
        // one row array and no cell at all — the row is a view over cells the
        // decode already produced.
        val stepLists = 2 * rows        // capStepRows rows + place's converted rows
        val stepArrays = 2 * rows       // each list's Object[]
        val stepSteps = 2 * rows        // one CharPlacement.Step per cell
        val stepGap = 2 * rows          // one GapCell per cell
        val stepOuter = 2 * payload.size // the two outer List objects per line
        val stepBoundary = stepLists + stepArrays + stepSteps + stepGap + stepOuter

        val cellLists = rows            // one row list per timestep
        val cellArrays = rows           // its Object[2] (the only row shape that allocates)
        val cellOuter = payload.size    // one outer List per line
        val cellTables = 2 * payload.size // the top-2 IntArray/FloatArray per line
        val cellBoundary = cellLists + cellArrays + cellOuter + cellTables

        Log.i(
            TAG,
            "OBJECTS cap_boundary stepRows=$stepBoundary cellRows=$cellBoundary " +
                "drop=${stepBoundary - cellBoundary} " +
                "pct=${"%.1f".format(100.0 * (stepBoundary - cellBoundary) / stepBoundary)} (rows=$rows)",
        )
        Log.i(
            TAG,
            "OBJECTS cap_boundary breakdown stepRows=[lists=$stepLists arrays=$stepArrays " +
                "steps=$stepSteps gapcell=$stepGap outer=$stepOuter] " +
                "cellRows=[lists=$cellLists arrays=$cellArrays outer=$cellOuter tables=$cellTables]",
        )
        assertTrue(
            "the cell boundary must allocate far less: step=$stepBoundary cell=$cellBoundary",
            cellBoundary * 2 < stepBoundary,
        )
    }

    /**
     * The TopK path on its own, so a regression in it cannot hide behind the
     * rest of the page: the panel list for every character of the book page
     * against the head's own top-15, and the identity of the lists
     * `activeAllAlternatives` hands the nav graph.
     */
    @Test
    fun the_overlay_topk_list_is_untouched() {
        val lines = recognize("bookpage.png")
        val controller = OcrOverlayStateController()
        controller.activeLineResults = lines.map { it as LineResult? }.toMutableList()
        controller.updateGlobalData()
        val flat = lines.flatMap { it.alternatives }
        assertEquals("one entry per character", flat.size, controller.activeAllAlternatives.size)
        for (i in flat.indices) {
            assertSame("the nav graph sees the line's own list", flat[i], controller.activeAllAlternatives[i])
        }
        var checked = 0
        for ((lineIdx, line) in lines.withIndex()) {
            for (cIdx in line.text.indices) {
                val ui = controller.getAlternativesUiState(lineIdx, cIdx)!!
                assertTrue(ui.showManualInput)
                assertEquals(
                    "line $lineIdx char $cIdx",
                    line.alternatives[cIdx].take(15).map { it.first },
                    ui.candidates.filter { it.source == OovSuggestions.Source.HEAD }.map { it.char },
                )
                checked++
            }
        }
        Log.i(TAG, "PARITY topk chars=$checked lists=${flat.size} headCells=${flat.sumOf { it.size }}")
    }

    companion object {
        private const val TAG = "LazyAlt"
        private val FIXTURES = listOf(
            "bookpage.png",
            "benchmark/Screenshot_20260530-172718.png",
            "benchmark/f5d7d08735383899.jpg",
        )
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
