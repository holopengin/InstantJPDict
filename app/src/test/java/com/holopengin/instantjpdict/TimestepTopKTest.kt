package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.toGapPlanLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.nav_graph_core.GapCell

/**
 * [TimestepTopK] against the nested shape it replaces, and the two truncated
 * reads the page path takes from it.
 *
 * The page-path claim this pins: the compact table **is** the nested
 * `List<List<Pair<Char, Float>>>` — same rows, same cells, same order, same
 * `equals`/`hashCode`/`toString` — it just builds a row the first time someone
 * asks for it. And the two eager consumers the page path *does* serve read the
 * same numbers they read before:
 *
 *  - CAP's `steps` from the top-2 truncation ([capStepRows_is_the_top_two_of_each_row],
 *    and [capSteps_place_the_same_boxes_as_the_full_top_k] for the placement
 *    itself);
 *  - the gap walk from the flat `GapCell` rows ([gapCellRows_are_the_flat_cells]).
 */
class TimestepTopKTest {

    /** A decode-shaped flat table: descending rows of `width` cells. */
    private fun table(rows: List<List<Pair<Char, Float>>>): Pair<List<GapCell>, List<Long>> {
        val cells = ArrayList<GapCell>(rows.size * 15)
        val starts = ArrayList<Long>(rows.size + 1)
        rows.forEach { row ->
            starts.add(cells.size.toLong())
            for ((c, s) in row) cells.add(GapCell(c.code, s))
        }
        starts.add(cells.size.toLong())
        return cells to starts
    }

    /** A realistic page-ish table: 15 cells per row, blanks, repeats, 40 chars. */
    private fun pageTable(nRows: Int = 24): List<List<Pair<Char, Float>>> =
        List(nRows) { t ->
            val head = if (t % 7 == 6) '　' else ('あ' + (t % 12))
            List(15) { k -> (if (k == 0) head else ('か' + (t + k) % 80)) to (10f - k - t * 0.01f) }
        }

    private fun nested(topK: TimestepTopK) = topK.map { it }

    // ── the shape: same rows, same cells, built lazily ──────────────────────

    @Test
    fun the_compact_table_is_the_nested_expansion_cell_for_cell() {
        val rows = pageTable()
        val (cells, starts) = table(rows)
        val topK = TimestepTopK.of(cells, starts)

        assertEquals(rows.size, topK.size)
        assertEquals(rows.size, topK.rowCount)
        assertEquals(cells.size, topK.cellCount)
        // Byte-for-byte, and equal as *lists* — not just element-wise by hand.
        assertEquals(rows, nested(topK))
        assertEquals(rows, topK)
        for (i in rows.indices) {
            assertEquals("row $i", rows[i], topK[i])
            assertEquals("row $i hash", rows[i].hashCode(), topK[i].hashCode())
        }
        // A data class field compares and hashes the same either way, so a
        // lazily built LineResult is `equals` to an eagerly built one.
        assertEquals(rows.hashCode(), topK.hashCode())
        assertEquals(rows.toString(), topK.toString())
        assertEquals(topK, rows)
    }

    @Test
    fun rows_are_built_on_first_read_and_reused_after() {
        val rows = pageTable()
        val (cells, starts) = table(rows)
        val topK = TimestepTopK.of(cells, starts)
        // Nothing built yet: the emitted rows are the only ones `toPpoResult`
        // asks for, and the rest stay flat until a rare reader wants them.
        val first = topK[3]
        assertSame("a second read is the same list", first, topK[3])
        assertEquals(rows[3], first)
        // The nested factory that the stitch paths use returns the same object.
        assertSame(topK, TimestepTopK.of(topK))
    }

    @Test
    fun a_line_built_lazily_equals_a_line_built_eagerly_and_survives_copy() {
        val rows = pageTable(8)
        val eager = rows
        val (cells, starts) = table(rows)
        val lazy = TimestepTopK.of(cells, starts)
        val base = LineResult(text = "あいう", charBoxes = emptyList(), alternatives = emptyList())
        val fromEager = base.copy(rawAlternatives = eager)
        val fromLazy = base.copy(rawAlternatives = lazy)
        assertEquals(fromEager, fromLazy)
        assertEquals(fromEager.hashCode(), fromLazy.hashCode())
        // `copy` without touching the field carries the compact table through,
        // still lazily, and the copy still reads as the eager rows.
        val edited = fromLazy.copy(text = "えお")
        assertSame(lazy, edited.rawAlternatives)
        assertEquals(eager, edited.rawAlternatives)
        assertEquals(fromEager, edited.copy(text = "あいう"))
        assertSame(lazy, fromLazy.rawAlternatives)
        assertEquals(eager, fromLazy.rawAlternatives)
    }

    @Test
    fun a_line_with_no_rows_reports_none() {
        assertEquals(emptyList<List<Pair<Char, Float>>>(), TimestepTopK.EMPTY)
        assertEquals(0, TimestepTopK.EMPTY.size)
        assertEquals(0, TimestepTopK.EMPTY.cellCount)
        assertSame(TimestepTopK.EMPTY, TimestepTopK.of(emptyList()))
        val line = LineResult(text = "", charBoxes = emptyList(), alternatives = emptyList())
        assertTrue(line.rawAlternatives.isEmpty())
        assertSame(TimestepTopK.EMPTY, line.rawTopK)
    }

    @Test
    fun the_nested_factory_re_slices_a_hand_built_line() {
        val rows = listOf(
            listOf('あ' to 3f, 'い' to 2f),
            listOf('う' to 1f),
            emptyList(),
            listOf('か' to 9f, 'き' to 8f, 'く' to 7f),
        )
        val topK = TimestepTopK.of(rows)
        assertEquals(rows, topK)
        assertEquals(6, topK.cellCount)
        // Round trip: the compact factory and the nested one agree.
        assertEquals(topK, TimestepTopK.of(topK.gapCellRows().map { r -> r.map { Char(it.ch) to it.score } }))
    }

    // ── reader 1: CAP's steps, truncated to the top two ──────────────────────

    @Test
    fun capStepRows_are_the_top_two_of_each_row() {
        val rows = List(12) { t ->
            List(15) { k -> (if (k == 0) 'あ' + t else 'か' + (t + k)) to (5f - k * 0.25f - t) }
        }
        val topK = TimestepTopK.of(table(rows).first, table(rows).second)
        val steps = topK.capStepRows()
        assertEquals(rows.size, steps.size)
        for (t in rows.indices) {
            val want = rows[t].take(2).map { (c, s) -> CharPlacement.Step(c, s) }
            assertEquals("timestep $t", want, steps[t])
        }
    }

    /**
     * The margin rule that makes truncation safe: a row with fewer than two
     * cells keeps reporting *no* second score, exactly as
     * `runs_from_steps` computes it (`0.0` for a one-cell row). Inventing a
     * second cell here would change the top1−top2 margin CAP fits its layout
     * template against.
     */
    @Test
    fun a_short_row_keeps_reporting_no_second_score() {
        val rows = listOf(
            listOf('あ' to 4f, 'い' to 3f, 'う' to 2f),
            listOf('え' to 4f),
            emptyList(),
        )
        val topK = TimestepTopK.of(rows)
        assertEquals('あ', topK.topChar(0))
        assertEquals(4.0, topK.topScore(0).toDouble(), 0.0)
        assertEquals(3.0, topK.secondScore(0).toDouble(), 0.0)
        assertEquals(2, topK.capStepRows()[0].size)

        assertEquals('え', topK.topChar(1))
        assertEquals(4.0, topK.topScore(1).toDouble(), 0.0)
        assertEquals("a one-cell row has no runner-up", 0.0, topK.secondScore(1).toDouble(), 0.0)
        assertEquals(1, topK.capStepRows()[1].size)

        assertEquals(TimestepTopK.NO_CELL, topK.topChar(2))
        assertEquals(0.0, topK.topScore(2).toDouble(), 0.0)
        assertEquals(0.0, topK.secondScore(2).toDouble(), 0.0)
        assertEquals(0, topK.capStepRows()[2].size)
    }

    /**
     * The audit this workstream rests on, as an executable claim: CAP's placed
     * boxes with the truncated top-2 `steps` are the boxes with the full
     * top-K, over a sweep of crop shapes, glyph layouts and column counts.
     *
     * `jpdict_core::char_placement::runs_from_steps` is the only reader of
     * `steps` (the shim's `place_dispatch` merely converts it, and the PC
     * `place_core` calls nothing else on it): it walks on `alts[0].0` and reads
     * `alts[0].1` / `alts[1].1`. This is the end-to-end statement of that.
     */
    @Test
    fun capSteps_place_the_same_boxes_as_the_full_top_k() {
        var withInk = 0
        var total = 0
        for (case in 0 until 64) {
            val vertical = case % 3 == 0
            val read = 40 + case % 120
            val cross = 16 + (case * 7) % 48
            val dims: Pair<Int, Int> = if (vertical) Pair(cross, read) else Pair(read, cross)
            val w = dims.first
            val h = dims.second
            val n = 2 + case % 9
            val pitch = (read / n).coerceAtLeast(6)
            val text = buildString { for (i in 0 until n) append("あいうえおかきくけこ"[i]) }
            val cols = FloatArray(n) { it.toFloat() }

            // A crop with a glyph-sized ink blob per character, so the ink pass
            // actually moves boxes (a template-only run would pass trivially).
            val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
            val thick = (cross * 2 / 3).coerceAtLeast(3)
            for (i in 0 until n) {
                val c0 = i * pitch + 1
                val c1 = (c0 + pitch / 2).coerceAtMost(read)
                for (r in c0 until c1) {
                    for (c in 0 until thick) {
                        val idx = if (vertical) r * w + c else c * w + r
                        px[idx] = 0xFF202020.toInt()
                    }
                }
            }
            val steps = List(n + 2) { t ->
                val ch = if (t % 3 == 2) '　' else "あいうえおかきくけこ"[t % n]
                List(15) { k -> (if (k == 0) ch else 'か' + (t + k) % 80) to (4f - k * 0.2f) }
            }
            val topK = TimestepTopK.of(steps)
            val full = steps.map { alts -> alts.map { (c, s) -> CharPlacement.Step(c, s) } }
            val truncated = topK.capStepRows()

            val viaFull = CharPlacement.place(text, cols, n + 2, w, h, vertical, px, full)
            val viaTop2 = CharPlacement.place(text, cols, n + 2, w, h, vertical, px, truncated)
            assertEquals(
                "case $case w=$w h=$h n=$n vertical=$vertical",
                viaFull,
                viaTop2,
            )
            val template = CharPlacement.place(text, cols, n + 2, w, h, vertical, px, null)
            if (template != viaFull) withInk++
            total++
        }
        assertTrue("only $withInk of $total cases moved boxes; the sweep is too weak", withInk >= 16)
    }

    // ── reader 2: the gap boundary's flat cells ──────────────────────────────

    @Test
    fun gapCellRows_are_the_flat_cells() {
        val rows = pageTable(6)
        val topK = TimestepTopK.of(rows)
        val cells = topK.gapCellRows()
        assertEquals(rows.size, cells.size)
        for (t in rows.indices) {
            assertEquals(
                "timestep $t",
                rows[t].map { GapCell(it.first.code, it.second) },
                cells[t],
            )
        }
    }

    // ── the two rare readers keep the whole table ───────────────────────────

    @Test
    fun the_blank_plan_line_still_crosses_every_cell_on_the_second_call() {
        val rows = pageTable(5)
        val line = LineResult(
            text = "あいうえお",
            charBoxes = List(5) { JpDictRect(it, 0, it + 1, 1) },
            alternatives = emptyList(),
            isVertical = true,
            rawAlternatives = TimestepTopK.of(rows),
        )
        val crossed = line.toGapPlanLine(includeRawAlternatives = true).rawAlternatives
        assertEquals(rows, crossed.map { r -> r.map { Char(it.ch) to it.score } })
        // The first call — the one every recognised line makes — crosses none.
        assertTrue(line.toGapPlanLine(includeRawAlternatives = false).rawAlternatives.isEmpty())
        // A horizontal line is returned untouched, table and all.
        val horizontal = line.copy(isVertical = false)
        assertSame(horizontal, BlankGaps.apply(horizontal))
    }

    @Test
    fun a_line_holding_nested_rows_still_reports_its_whole_table() {
        val rows = pageTable(4)
        val line = LineResult(
            text = "あいう",
            charBoxes = emptyList(),
            alternatives = emptyList(),
            rawAlternatives = rows,
        )
        assertNotEquals(TimestepTopK.EMPTY, line.rawTopK)
        assertEquals(rows, line.rawTopK)
    }
}
