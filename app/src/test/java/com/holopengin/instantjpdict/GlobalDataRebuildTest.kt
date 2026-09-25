package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.nav_graph_core.BoundingBox
import uniffi.nav_graph_core.buildNavGraph

/**
 * WHEN a page's derived data is rebuilt — once per installed page, not once
 * per installed line (2026-09-25 overlay-install perf pass).
 *
 * [OcrOverlayStateController.updateGlobalData] re-derives the page-wide
 * [OcrOverlayStateController.activeAllChars] / [activeAllAlternatives] and
 * rebuilds the WHOLE nav graph, so it is O(page). `addLineToResults` used to call
 * it per line, which made an L-line page pay L prefix rebuilds and throw all but
 * the last away — measured 19.7 ms for 17 prefixes, ~21 ms of a ~892 ms page
 * (`docs/ocr-pipeline-perf-2026-09-25.md`). The install loop now runs it once,
 * after the page is in place.
 *
 * The counters ([OcrOverlayStateController.globalDataUpdates] /
 * [navGraphBuilds]) are the witness, because the derived state is identical
 * either way: a page is only ever read once it is complete. A tap cannot be
 * processed while the install loop holds the main thread, the per-line neighbour
 * chips and the cursor read `activeLineResults` directly, and the status line is
 * written after the update. So these tests pin two separate things:
 *
 *  - the COUNT, which is what the change bought — one update and one nav build
 *    per page, and still exactly one per clear, per re-fit and per override
 *    edit;
 *  - the RESULT, so an update that ran once too early (or not at all) cannot pass
 *    by leaving the derived data stale and looking plausible.
 *
 * Pure JVM: the controller has no Android types in its decision path, and the
 * nav graph is the real Rust core over UniFFI, built from the same two-line
 * fixture `NavGraphCoreTest.navGraph01HorizontalLines` pins neighbour for
 * neighbour — so "navigates" here means the known-good graph, not merely some
 * graph.
 */
class GlobalDataRebuildTest {

    // ── fixture ─────────────────────────────────────────────────────────────
    // Two horizontal lines of six chars in reading order: 24px chars on a 32px
    // advance, 80px line pitch (NavGraphCoreTest's hLines2x6). 12 boxes, over
    // the `boxes.size >= 5` threshold that gates buildNavGraph.

    private val top = "あいうえおか"
    private val bottom = "かきくけこさ"

    private fun line(row: Int, text: String): LineResult = LineResult(
        text = text,
        charBoxes = (0..5).map { c ->
            val x = 100 + c * 32
            val y = 100 + row * 80
            JpDictRect(x, y, x + 24, y + 24)
        },
        alternatives = text.map { mutableListOf(it to 1f) },
    )

    /** A controller mid-install: `lines` empty result slots, the way
     *  `startOcr` builds the list from the detect boxes before the first
     *  `addLineToResults`. Nothing derived yet — the caller decides when. */
    private fun installing(lines: Int): OcrOverlayStateController {
        val c = OcrOverlayStateController()
        c.activeLineResults = MutableList(lines) { null as LineResult? }
        return c
    }

    private fun install(c: OcrOverlayStateController, row: Int, text: String) {
        c.activeLineResults[row] = line(row, text)
    }

    /** A finished two-line page, derived the new way: install, install, derive. */
    private fun installedPage(): OcrOverlayStateController {
        val c = installing(2)
        install(c, 0, top)
        install(c, 1, bottom)
        c.updateGlobalData()
        return c
    }

    private val wholePage = "$top$bottom"

    // ── the once-per-page contract ──────────────────────────────────────────

    @Test
    fun a_page_is_derived_by_one_update_after_its_last_line_not_one_per_line() {
        // The old shape: install, derive, install, derive.
        val perLine = installing(2)
        install(perLine, 0, top)
        perLine.updateGlobalData()
        install(perLine, 1, bottom)
        perLine.updateGlobalData()

        // The new shape: install both, derive once.
        val once = installedPage()

        assertEquals("the O(L) that was measured", 2, perLine.globalDataUpdates)
        assertEquals(2, perLine.navGraphBuilds)
        assertEquals(1, once.globalDataUpdates)
        assertEquals(1, once.navGraphBuilds)

        // …and the page the user is left with is the same one either way.
        assertEquals(perLine.activeAllChars, once.activeAllChars)
        assertEquals(perLine.activeAllAlternatives, once.activeAllAlternatives)
        assertEquals(perLine.navGraph, once.navGraph)
    }

    @Test
    fun the_page_globals_are_the_whole_page_in_reading_order() {
        val c = installedPage()

        // `lookup` reads activeAllChars as ONE flat page-wide string, so a
        // missing, extra or reordered line changes the 20-character window every
        // tap searches. This is the user-visible half of the contract.
        assertEquals(12, c.activeAllChars.size)
        assertEquals(wholePage, c.activeAllChars.joinToString(""))
        assertEquals(12, c.activeAllAlternatives.size)

        // …and the flat index agrees with it, across the line boundary.
        assertEquals(0, c.getGlobalIdx(0, 0))
        assertEquals(5, c.getGlobalIdx(0, 5))
        assertEquals(6, c.getGlobalIdx(1, 0))
        assertEquals(11, c.getGlobalIdx(1, 5))
        assertEquals(1 to 0, c.getCoordsFromGlobalIdx(6))
        assertEquals(1 to 5, c.getCoordsFromGlobalIdx(11))
    }

    @Test
    fun a_page_with_a_hole_derives_only_the_lines_that_were_installed() {
        // The install loop breaks on `closed`, so a hole in the results list is
        // a real state, not a fiction. The derive is driven by the results, so
        // the hole is skipped and nothing after it shifts.
        val c = installing(2)
        install(c, 0, top)
        c.updateGlobalData()

        assertEquals(1, c.globalDataUpdates)
        assertEquals(top, c.activeAllChars.joinToString(""))
        // 6 boxes is still over the threshold, and the graph is the first LINE's
        // — not a graph of the empty slots around it.
        assertNotNull(c.navGraph)
        assertEquals(6, c.navGraph!!.n)
    }

    @Test
    fun the_page_still_navigates_across_lines() {
        // The graph is the interaction contract: gamepad navigation is its only
        // reader, and a stale or absent one silently degrades to same-line
        // left/right. This walks the controller's real entry point, from the
        // cursor placement the pass ends with, and expects the neighbour indices
        // NavGraphCoreTest pins for this exact box layout.
        val c = installedPage()
        c.ensureCursorPosition()
        assertEquals(0 to 0, c.currentTappedLineIdx to c.currentTappedCharIdxInLine)
        assertEquals(0, c.currentTappedIdx)

        assertTrue("south onto the next line", c.navigate(JpDictKeyEvent.KEYCODE_DPAD_DOWN, 1080.0, 2400.0))
        assertEquals(6, c.currentTappedIdx)
        assertEquals(1 to 0, c.currentTappedLineIdx to c.currentTappedCharIdxInLine)

        assertTrue("north back", c.navigate(JpDictKeyEvent.KEYCODE_DPAD_UP, 1080.0, 2400.0))
        assertEquals(0, c.currentTappedIdx)
        assertEquals(0 to 0, c.currentTappedLineIdx to c.currentTappedCharIdxInLine)

        assertTrue("east along the first line", c.navigate(JpDictKeyEvent.KEYCODE_DPAD_RIGHT, 1080.0, 2400.0))
        assertEquals(1, c.currentTappedIdx)
        assertEquals(0 to 1, c.currentTappedLineIdx to c.currentTappedCharIdxInLine)
    }

    // ── the other three call sites still refresh ────────────────────────────

    @Test
    fun clearing_the_run_empties_the_globals_and_drops_the_graph() {
        val c = installedPage()
        val updates = c.globalDataUpdates

        c.resetState()

        assertEquals(updates + 1, c.globalDataUpdates)
        assertTrue(c.activeAllChars.isEmpty())
        assertTrue(c.activeAllAlternatives.isEmpty())
        assertNull("a cleared run must not leave a graph to navigate a dead page by", c.navGraph)
    }

    @Test
    fun an_override_edit_refreshes_the_globals_it_changed() {
        val c = installedPage()
        val updates = c.globalDataUpdates

        c.updateCharacter(0, 4, 'A')

        assertEquals(updates + 1, c.globalDataUpdates)
        assertEquals(updates + 1, c.navGraphBuilds)
        assertEquals("あいうえAか$bottom", c.activeAllChars.joinToString(""))
        // The correction moves no box, so the graph's answers are unchanged — but
        // it must still have been rebuilt, which is what the counter is for.
        c.currentTappedIdx = 4
        c.currentTappedLineIdx = 0
        c.currentTappedCharIdxInLine = 4
        assertTrue(c.navigate(JpDictKeyEvent.KEYCODE_DPAD_RIGHT, 1080.0, 2400.0))
        assertEquals(5, c.currentTappedIdx)
    }

    @Test
    fun a_container_refit_rebuilds_the_graph_from_the_moved_boxes() {
        val c = installedPage()
        val updates = c.globalDataUpdates

        c.refitBoxes(ImageShareFit.Refit(scaleX = 2f, scaleY = 2f, offsetX = 10f, offsetY = 20f))

        assertEquals(updates + 1, c.globalDataUpdates)
        assertEquals(updates + 1, c.navGraphBuilds)
        // The boxes really moved — (100,100,24,24) at 2x + (10,20) …
        assertEquals(JpDictRect(210, 220, 258, 268), c.activeLineResults[0]!!.charBoxes[0])
        // … the page is still whole …
        assertEquals(wholePage, c.activeAllChars.joinToString(""))
        // … and the graph is the one the MOVED boxes describe, which is the whole
        // reason this path rebuilds it: a graph over the old layout would navigate
        // a page that is no longer there. Checked against the builder itself, so
        // it says "fed the moved boxes", not "did not change".
        val moved = c.activeLineResults.flatMap { line ->
            line!!.charBoxes.map { BoundingBox(it.left, it.top, it.width(), it.height()) }
        }
        assertEquals(buildNavGraph(moved), c.navGraph)
        // … and a moved page still navigates across its lines.
        c.ensureCursorPosition()
        assertTrue(c.navigate(JpDictKeyEvent.KEYCODE_DPAD_DOWN, 1080.0, 2400.0))
        assertEquals(6, c.currentTappedIdx)
    }

    @Test
    fun a_same_size_container_refreshes_nothing_because_nothing_moved() {
        val c = installedPage()
        val graph = c.navGraph
        val updates = c.globalDataUpdates

        c.refitBoxes(ImageShareFit.Refit(1f, 1f, 0f, 0f))

        assertEquals(updates, c.globalDataUpdates)
        assertEquals(graph, c.navGraph)
        assertEquals(wholePage, c.activeAllChars.joinToString(""))
    }
}
