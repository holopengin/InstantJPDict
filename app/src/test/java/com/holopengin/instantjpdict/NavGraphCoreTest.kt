package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.nav_graph_core.BoundingBox
import uniffi.nav_graph_core.buildNavGraph
import uniffi.nav_graph_core.navigate

/**
 * Android mirror of the PC `jpdict_core::nav_graph` regression tests
 * (`accessibility_daemon/core/src/nav_graph.rs`, tests `nav_graph_01..08`).
 *
 * `nav_graph_core` no longer carries its own algorithm: it delegates to
 * `jpdict_core::nav_graph` (pipeline-sharing/04), so these tests call the real
 * Rust core through the UniFFI boundary (host-built `libnav_graph_core.so`,
 * wired via `jna.library.path` in `buildNavGraphCoreHost`) and pin the exact
 * PC neighbour indices. Test numbering matches the PC side so each case can
 * be promoted to a `nav-graph-0X-...` conformance case later.
 *
 * Per-test harness accounting:
 * - 01, 02, 03, 03b, 04, 05, 06b, 07, 08: exact-index through the FFI call.
 * - PC `initial_edges` assertions have no mobile counterpart: the UniFFI
 *   `NavGraph` record exposes only `edges`/`n` (shape frozen so the Kotlin
 *   call sites compile unchanged). Phase-1 behaviour is still pinned
 *   indirectly — the final edge tables below are only produced when Phase 1
 *   feeds the documented locals (e.g. 03b's ring, 06b's line walk) — and
 *   directly by the Rust-side mirror in `nav_graph_core/src/lib.rs`, which
 *   asserts `initial_edges` against `jpdict_core` without the record in the
 *   way. No silent skips: every PC assertion is either here or there.
 * - 06a (`RotatedBox::is_vertical`) cannot reach Rust at all: no verticality
 *   predicate is exported over UniFFI. It is covered through the platform
 *   counterpart that feeds the same sort (`RotatedGeometry.isVertical`, same
 *   1.25x rule as PC `VERTICAL_MIN_ASPECT`), pinning the shared threshold.
 */
class NavGraphCoreTest {

    companion object {
        const val N = 0
        const val S = 1
        const val E = 2
        const val W = 3
    }

    private fun bb(x: Int, y: Int, w: Int, h: Int) = BoundingBox(x, y, w, h)

    private fun nav(boxes: List<BoundingBox>, idx: Int, dir: Int) =
        navigate(buildNavGraph(boxes), idx, dir)

    /** Edges as rows of [north, south, east, west], like PC `g.edges`. */
    private fun rows(boxes: List<BoundingBox>): List<List<Int>> {
        val g = buildNavGraph(boxes)
        return (0 until g.n).map { i -> (0..3).map { d -> g.edges[i * 4 + d] } }
    }

    /** Two horizontal lines of six chars in reading order: 24px chars on a
     * 32px advance, 80px line pitch. */
    private fun hLines2x6(): List<BoundingBox> {
        val out = mutableListOf<BoundingBox>()
        for (r in 0..1) for (c in 0..5) out.add(bb(100 + c * 32, 100 + r * 80, 24, 24))
        return out
    }

    /** Two vertical columns of six chars in reading order: right column first. */
    private fun vCols2x6(): List<BoundingBox> {
        val out = mutableListOf<BoundingBox>()
        for (x in listOf(380, 300)) for (r in 0..5) out.add(bb(x, 100 + r * 32, 24, 24))
        return out
    }

    @Test
    fun navGraph01HorizontalLines() {
        val boxes = hLines2x6()
        val expect = listOf(
            listOf(7, 6, 1, 5),
            listOf(6, 7, 2, 0),
            listOf(9, 8, 3, 1),
            listOf(8, 9, 4, 2),
            listOf(9, 10, 5, 3),
            listOf(10, 11, 0, 4),
            listOf(0, 1, 7, 11),
            listOf(1, 0, 8, 6),
            listOf(2, 3, 9, 7),
            listOf(3, 2, 10, 8),
            listOf(4, 3, 11, 9),
            listOf(5, 4, 6, 10),
        )
        assertEquals(expect, rows(boxes))
        assertEquals(1, nav(boxes, 0, E))
        assertEquals(5, nav(boxes, 4, E))
        assertEquals(0, nav(boxes, 5, E))
        assertEquals(11, nav(boxes, 6, W))
        assertEquals(6, nav(boxes, 7, W))
        assertEquals(6, nav(boxes, 0, S))
        assertEquals(0, nav(boxes, 6, N))
        assertEquals(9, nav(boxes, 3, S))
        assertEquals(3, nav(boxes, 9, N))
        assertEquals(6, nav(boxes, 1, N))
        assertEquals(1, nav(boxes, 6, S))
        assertEquals(9, nav(boxes, 2, N))
        assertEquals(9, nav(boxes, 4, N))
    }

    @Test
    fun navGraph02VerticalColumns() {
        val boxes = vCols2x6()
        val expect = listOf(
            listOf(5, 1, 7, 6),
            listOf(0, 2, 6, 7),
            listOf(1, 3, 9, 8),
            listOf(2, 4, 8, 9),
            listOf(3, 5, 9, 10),
            listOf(4, 0, 10, 11),
            listOf(11, 7, 0, 1),
            listOf(6, 8, 1, 0),
            listOf(7, 9, 2, 3),
            listOf(8, 10, 3, 2),
            listOf(9, 11, 4, 3),
            listOf(10, 6, 5, 4),
        )
        assertEquals(expect, rows(boxes))
        assertEquals(1, nav(boxes, 0, S))
        assertEquals(5, nav(boxes, 4, S))
        assertEquals(0, nav(boxes, 5, S))
        assertEquals(4, nav(boxes, 5, N))
        assertEquals(5, nav(boxes, 0, N))
        assertEquals(6, nav(boxes, 0, W))
        assertEquals(9, nav(boxes, 3, W))
        assertEquals(0, nav(boxes, 6, E))
        assertEquals(7, nav(boxes, 1, W))
    }

    @Test
    fun navGraph03SingleLineHasNoVerticalNeighbours() {
        val boxes = (0..5).map { c -> bb(100 + c * 32, 100, 24, 24) }
        val expect = listOf(
            listOf(6, 6, 1, 5),
            listOf(6, 6, 2, 0),
            listOf(6, 6, 3, 1),
            listOf(6, 6, 4, 2),
            listOf(6, 6, 5, 3),
            listOf(6, 6, 0, 4),
        )
        assertEquals(expect, rows(boxes))
        for (i in 0..5) {
            assertNull("node $i has no north", nav(boxes, i, N))
            assertNull("node $i has no south", nav(boxes, i, S))
        }
        assertEquals(1, nav(boxes, 0, E))
        assertEquals(0, nav(boxes, 5, E))
        assertEquals(5, nav(boxes, 0, W))
    }

    @Test
    fun navGraph03bLongRowLinksPhase1Local() {
        val boxes = (0..24).map { c -> bb(100 + c * 32, 100, 24, 24) }
        val g = buildNavGraph(boxes)
        assertEquals(25, g.n)
        // Final graph: a clean east/west ring, no vertical neighbours.
        // (PC pins `initial_edges` too; see the class doc — the record has no
        // such field, and the Rust-side mirror asserts it directly.)
        for (i in 0..24) {
            assertEquals("node $i east", (i + 1) % 25, g.edges[i * 4 + E])
            assertEquals("node $i west", (i + 24) % 25, g.edges[i * 4 + W])
            assertEquals("node $i has no north", 25, g.edges[i * 4 + N])
            assertEquals("node $i has no south", 25, g.edges[i * 4 + S])
        }
        assertEquals(0, nav(boxes, 24, E))
        assertEquals(24, nav(boxes, 0, W))
        assertNull(nav(boxes, 12, N))
    }

    @Test
    fun navGraph04SmallInputsTakeTheFallbackRing() {
        // Empty: no nodes, no edges, no navigation.
        val empty = buildNavGraph(emptyList())
        assertEquals(0, empty.n)
        assertEquals(emptyList<Int>(), empty.edges)
        assertNull(navigate(empty, 0, N))
        // Single char: the ring is all self-loops, so navigation stays put.
        val one = buildNavGraph(listOf(bb(100, 100, 24, 24)))
        assertEquals(listOf(0, 0, 0, 0), one.edges)
        for (dir in listOf(N, S, E, W)) assertEquals(0, navigate(one, 0, dir))
        // Two nodes: [(i+1)%2, (i+2)%2, (i+3)%2, (i+4)%2].
        val two = buildNavGraph((0..1).map { c -> bb(100 + c * 32, 100, 24, 24) })
        assertEquals(listOf(1, 0, 1, 0, 0, 1, 0, 1), two.edges)
        assertEquals(1, navigate(two, 0, E))
        assertEquals(1, navigate(two, 1, W))
        // Four nodes: the full ring.
        val four = buildNavGraph((0..3).map { c -> bb(100 + c * 32, 100, 24, 24) })
        assertEquals(
            listOf(1, 2, 3, 0, 2, 3, 0, 1, 3, 0, 1, 2, 0, 1, 2, 3),
            four.edges,
        )
        assertEquals(0, navigate(four, 3, N))
    }

    @Test
    fun navGraph05MixedOrientationPage() {
        val boxes = mutableListOf<BoundingBox>()
        for (c in 0..2) boxes.add(bb(100 + c * 32, 100, 24, 24))
        for (r in 0..2) boxes.add(bb(400, 100 + r * 32, 24, 24))
        val expect = listOf(
            listOf(4, 6, 1, 3),
            listOf(5, 6, 2, 0),
            listOf(5, 6, 3, 1),
            listOf(5, 4, 0, 2),
            listOf(3, 5, 0, 2),
            listOf(4, 3, 1, 2),
        )
        assertEquals(expect, rows(boxes))
        assertEquals(3, nav(boxes, 2, E))
        assertEquals(2, nav(boxes, 3, W))
        assertEquals(4, nav(boxes, 3, S))
        assertEquals(4, nav(boxes, 5, N))
        assertEquals(5, nav(boxes, 4, S))
        assertNull(nav(boxes, 0, S))
        assertNull(nav(boxes, 2, S))
        assertEquals(0, nav(boxes, 4, E))
    }

    @Test
    fun navGraph06aNearSquareCountsAsHorizontal() {
        // PC `nav_graph_06a` pins `RotatedBox::is_vertical`; no verticality
        // predicate is exported over UniFFI, so this pins the platform
        // counterpart that feeds the same sort (`OcrEngine.sortDetectedBoxes`
        // splits on the same 1.25x rule): the shared threshold constant plus
        // the boundary behaviour through `RotatedGeometry.isVertical`.
        assertEquals(1.25f, RotatedGeometry.VERTICAL_MIN_ASPECT)
        fun quad(w: Float, h: Float) = JpDictQuad(
            QuadPoint(0f, 0f), QuadPoint(w, 0f), QuadPoint(w, h), QuadPoint(0f, h),
        )
        assertEquals(false, RotatedGeometry.isVertical(quad(40f, 40f)))
        assertEquals(false, RotatedGeometry.isVertical(quad(40f, 44f)))
        assertEquals(true, RotatedGeometry.isVertical(quad(32f, 40f)))
        assertEquals(false, RotatedGeometry.isVertical(quad(33f, 40f)))
        assertEquals(true, RotatedGeometry.isVertical(quad(30f, 200f)))
        assertEquals(false, RotatedGeometry.isVertical(quad(200f, 30f)))
    }

    @Test
    fun navGraph06bNearSquareBoxNavigatesWithItsLine() {
        val boxes = (0..5).map { c ->
            if (c == 2) bb(100 + c * 32 - 8, 92, 40, 40) else bb(100 + c * 32, 100, 24, 24)
        }
        val expect = listOf(
            listOf(6, 6, 1, 5),
            listOf(6, 6, 2, 0),
            listOf(6, 6, 3, 1),
            listOf(6, 6, 4, 2),
            listOf(6, 6, 5, 3),
            listOf(6, 6, 0, 4),
        )
        assertEquals(expect, rows(boxes))
        assertEquals(2, nav(boxes, 1, E))
        assertEquals(2, nav(boxes, 3, W))
        assertEquals(3, nav(boxes, 2, E))
        assertEquals(1, nav(boxes, 2, W))
    }

    @Test
    fun navGraph07CorpusMixedGeometryInReadingOrder() {
        val boxes = listOf(
            bb(10, 10, 200, 30),
            bb(10, 100, 200, 30),
            bb(10, 200, 40, 40),
            bb(300, 10, 30, 200),
            bb(100, 10, 30, 200),
        )
        val expect = listOf(
            listOf(4, 1, 3, 5),
            listOf(4, 2, 3, 5),
            listOf(1, 4, 3, 5),
            listOf(1, 0, 5, 4),
            listOf(0, 1, 3, 5),
        )
        assertEquals(expect, rows(boxes))
        assertEquals(1, nav(boxes, 0, S))
        assertEquals(2, nav(boxes, 1, S))
        assertEquals(1, nav(boxes, 2, N))
        assertEquals(3, nav(boxes, 0, E))
        assertEquals(3, nav(boxes, 4, E))
        assertEquals(4, nav(boxes, 3, W))
        assertNull(nav(boxes, 0, W))
        assertNull(navigate(buildNavGraph(boxes), 3, E))
    }

    @Test
    fun navGraph08NavigateBounds() {
        val boxes = (0..5).map { c -> bb(100 + c * 32, 100, 24, 24) }
        val g = buildNavGraph(boxes)
        assertNull(navigate(g, 0, N))
        assertNull(navigate(g, 6, N))
        assertNull(navigate(g, 99, E))
        assertNull(navigate(g, 0, 4))
        assertNull(navigate(g, 0, 99))
        val empty = buildNavGraph(emptyList())
        assertNull(navigate(empty, 0, N))
    }
}
