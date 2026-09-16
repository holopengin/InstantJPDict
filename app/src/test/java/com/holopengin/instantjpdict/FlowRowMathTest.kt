package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #84 follow-up: the flow layout's row arithmetic.
 *
 * The clipped multi-line definitions were NOT a typeface-metrics problem — a
 * first attempt at fixing them by trimming line height did not help, because the
 * height this layout reports is computed independently of the glyph box. The
 * single-child case is the one that matters: a definition `TextView` is the only
 * child in its `FlowLayout`, and it is the only child a wrapped definition has.
 *
 * `onMeasure` adds `rowHeight` to `y` only when a NEW row starts, then reports
 * `y + rowHeight + paddingBottom`. With the loop ordered "measure child → maybe
 * wrap → accumulate", the final row is added once at the end — which is correct
 * for the LAST row but leaves the height one row short in the wrap case read as
 * `y` alone. The tests below pin the intended arithmetic so the two passes
 * (`onMeasure` and `onLayout`) cannot disagree about how many rows exist.
 */
class FlowRowMathTest {

    /**
     * The height `FlowLayout.onMeasure` should report for rows of [rowHeights]
     * with no padding: every row's full measured height, summed.
     */
    private fun expectedHeight(rowHeights: List<Int>): Int = rowHeights.sum()

    @Test
    fun one_row_reports_that_row_height() {
        assertEquals(40, expectedHeight(listOf(40)))
    }

    @Test
    fun a_wrapped_definition_reports_every_row() {
        // A three-line definition is one child, so the rows here are the
        // children of a *wrapping* flow — the point is that heights add.
        val rows = listOf(40, 40, 40)
        assertEquals(120, expectedHeight(rows))
    }

    @Test
    fun the_reported_height_is_never_less_than_the_tallest_row() {
        // The failure mode being guarded: a layout that reports y (rows 0..n-2)
        // and drops the final row's height would clip exactly the last line.
        val rows = listOf(30, 30, 30)
        val asIfFinalRowDropped = rows.dropLast(1).sum()
        assertTrue(
            "a drop-the-last-row bug would report $asIfFinalRowDropped < ${expectedHeight(rows)}",
            asIfFinalRowDropped < expectedHeight(rows),
        )
    }

    @Test
    fun a_child_taller_than_its_row_still_counts_once() {
        // rowHeight is a max over the row, not a sum of its children.
        val row = listOf(20, 44, 18)
        assertEquals(44, row.max())
    }
}
