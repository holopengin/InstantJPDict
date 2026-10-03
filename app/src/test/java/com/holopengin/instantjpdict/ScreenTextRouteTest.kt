package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #106 package 2: the pure half of the Android wiring — turning a routing into the
 * box list the overlay installs, and a node's own string into the `LineResult` it
 * draws.
 *
 * Where package 1 pinned *which* box a node pays for, this pins the three
 * consequences of that decision that are easy to get wrong and impossible to see
 * on a device without a specific page in front of you:
 *
 *  1. **The augmented list's indices are the detector's.** The detected boxes keep
 *     their place, so the border layer, `activeLineBoxes` and the refit path all
 *     read what they read today; only recovered nodes are appended.
 *  2. **A node is one line, not one line per box.** A paragraph the detector split
 *     into three lines is drawn once. Building three results from one node's text
 *     would triple it in the page's char stream, and the neighbour panel, the nav
 *     graph and the cursor's global index would all read that.
 *  3. **The ink predicate can only remove.** #106 makes the recovered-node sample a
 *     filter, never a router; [theInkSampleOnlyDropsRecoveredNodes] is where that
 *     is structural rather than a promise in a comment.
 */
class ScreenTextRouteTest {

    /** The app's own 1.25-aspect orientation rule (#28/#53), inline so this file
     *  does not load the engine — [OcrEngine.isVerticalBox] is `internal` and
     *  UniFFI-backed through [RotatedGeometry]. */
    private fun isVertical(rect: JpDictRect): Boolean = rect.height() >= rect.width() * 1.25f

    /** Nothing is drawn; every recovered node is dropped. */
    private val noInk: (ScreenTextNode) -> Boolean = { false }
    private val allInk: (ScreenTextNode) -> Boolean = { true }

    private fun box(l: Int, t: Int, r: Int, b: Int) = LineBox(JpDictRect(l, t, r, b))
    private fun node(text: String, l: Int, t: Int, r: Int, b: Int) = ScreenTextNode(text, JpDictRect(l, t, r, b))

    private val window = JpDictRect(0, 0, 1080, 1920)

    private fun route(
        boxes: List<LineBox>,
        nodes: List<ScreenTextNode>,
        recognise: Boolean = true,
        inkOk: (ScreenTextNode) -> Boolean = allInk,
    ) = ScreenTextRoute.route(
        boxes = boxes,
        nodes = nodes,
        window = window,
        recogniseBoxes = recognise,
        isVertical = ::isVertical,
        inkOk = inkOk,
    )

    // ── elision: a node whose text cannot be what is drawn ──────────────────

    @Test
    fun anElidedNodeDoesNotAnswerSoItsBoxesRecognise() {
        // A collapsed notification: the node's text is the whole message, the row it
        // is drawn in holds two lines. Answering would squeeze the lot into the row —
        // the maintainer's stacked glyphs — so it does not answer, its box goes to
        // recognition, and the split says so.
        val row = box(0, 0, 1000, 150)
        val plan = route(
            boxes = listOf(row),
            nodes = listOf(node("新しいメッセージがあります。".repeat(40), 0, 0, 1000, 150)),
        )
        assertEquals(listOf(0), plan.recognise)
        assertEquals(0, plan.nodePaid)
        assertEquals(emptyMap<Int, Int>(), plan.nodeAt)
        assertEquals(1, plan.elided)
    }

    @Test
    fun aNodeWhoseTextFitsItsRowAnswersAndIsNotElided() {
        val row = box(0, 0, 1000, 60)
        val plan = route(
            boxes = listOf(row),
            nodes = listOf(node("吾輩は猫である名前はまだ無い", 0, 0, 1000, 60)),
        )
        assertEquals(emptyList<Int>(), plan.recognise)
        assertEquals(mapOf(0 to 0), plan.nodeAt)
        assertEquals(0, plan.elided)
    }

    @Test
    fun aBoxPartlyOverlappingANodeIsStillRecognised() {
        // The invariant: recognition covers every box a node's own line does not. A
        // box whose centre lies outside the node is not covered by that node's text,
        // so it is recognised. Suppressing overlaps (an earlier revision) took OCR
        // away from whole regions whenever a large container answered, and when the
        // tree then refused an elided node the region had no source at all.
        val paid = box(10, 0, 390, 60)
        val overlapping = box(300, 0, 700, 60)
        val plan = route(
            boxes = listOf(paid, overlapping),
            nodes = listOf(node("新しい", 0, 0, 400, 60)),
        )
        assertEquals(listOf(1), plan.recognise)
        assertEquals(mapOf(0 to 0), plan.nodeAt)
        assertEquals(1, plan.nodePaid)
    }

    @Test
    fun anElidedNodeWithNoBoxInsideStillAnswers() {
        // No detected box inside it, so refusing would leave the region with no source
        // — nothing for recognition to read. The node answers, the view draws the
        // visible prefix, and the split still counts it as elided.
        val plan = route(
            boxes = emptyList(),
            nodes = listOf(node("新しいメッセージがあります。".repeat(20), 0, 0, 200, 40)),
        )
        assertEquals(mapOf(0 to 0), plan.nodeAt)
        assertEquals(1, plan.elided)
    }

    // ── no nodes: the pre-#106 pipeline ─────────────────────────────────────

    @Test
    fun withNoNodesEveryBoxRecognisesInOrder() {
        // #106 rule 5 as a property of the object rather than of the caller: an
        // empty node list must produce exactly the list and exactly the order the
        // old code passed to `recognizeStreaming`, so the game's path is the old
        // path and not a re-derived equivalent of it.
        val boxes = listOf(box(10, 0, 100, 30), box(10, 30, 100, 60), box(2000, 100, 2100, 130))
        val plan = route(boxes, emptyList())
        assertEquals(boxes, plan.boxes)
        assertEquals(listOf(0, 1, 2), plan.recognise)
        assertEquals(emptyMap<Int, Int>(), plan.nodeAt)
        assertEquals(3, plan.detected)
        assertEquals(0, plan.nodePaid)
        assertEquals(0, plan.recovered)
    }

    @Test
    fun withNoNodesEvenTheNodesModeRecognisesEverything() {
        // `nodes` mode is a *choice* about how the tree's answer is used; with no
        // tree to answer there is nothing to choose between, and this is the case
        // a game or a photo lands in. (The service skips the walk entirely under
        // `ocr` mode, so that mode never reaches this class at all.)
        val boxes = listOf(box(10, 0, 100, 30), box(10, 30, 100, 60))
        assertEquals(listOf(0, 1), route(boxes, emptyList(), recognise = false).recognise)
    }

    // ── the augmented list ──────────────────────────────────────────────────

    @Test
    fun recoveredNodesAreAppendedAfterEveryDetectedBox() {
        // The index contract the overlay installs through: entry i < detected is
        // the detector's box i, unchanged and with its quad intact, and the
        // recovered nodes follow. `buildBoxLayers`, `activeLineBoxes` and the
        // refit path all index this list.
        val detected = listOf(box(10, 0, 100, 30), box(10, 30, 100, 60))
        val plan = route(detected, listOf(node("余白", 400, 0, 500, 30)))
        assertEquals(2, plan.detected)
        assertEquals(listOf(box(10, 0, 100, 30), box(10, 30, 100, 60)), plan.boxes.take(2))
        // The third entry is the node's own rect, as a plain axis-aligned LineBox.
        assertEquals(LineBox.of(JpDictRect(400, 0, 500, 30)), plan.boxes[2])
        assertFalse(plan.boxes[2].isRotated)
        assertEquals(mapOf(2 to 0), plan.nodeAt)
        assertEquals(1, plan.recovered)
    }

    @Test
    fun aRotatedDetectedBoxKeepsItsQuadThroughTheAugmentation() {
        // #53's geometry is not something #106 may flatten: a node-paid rotated box
        // is still the detector's rotated box, and the border layer draws its quad.
        val quad = JpDictQuad.fromRect(JpDictRect(10, 10, 110, 40))
        val rotated = LineBox(JpDictRect(10, 10, 110, 40), quad)
        val plan = route(listOf(rotated), listOf(node("猫", 0, 0, 200, 60)))
        assertTrue(plan.boxes[0].isRotated)
        assertEquals(quad, plan.boxes[0].quad)
        // …and it is node-paid, so it does not recognise.
        assertEquals(emptyList<Int>(), plan.recognise)
        assertEquals(mapOf(0 to 0), plan.nodeAt)
        assertEquals(1, plan.detected)
    }

    // ── one node, one line ──────────────────────────────────────────────────

    @Test
    fun oneNodePayingForThreeBoxesBecomesOneLine() {
        // The paragraph case #106 is about. Three detected lines, one node, one
        // `LineResult` — installed at the node's FIRST box, which is its position
        // in reading order. The other two boxes keep their borders and carry no
        // result, so the paragraph is drawn once rather than three times over.
        val boxes = listOf(box(0, 0, 300, 30), box(0, 30, 300, 60), box(0, 60, 300, 90))
        val plan = route(boxes, listOf(node("三行", 0, 0, 320, 90)))
        assertEquals(mapOf(0 to 0), plan.nodeAt)
        assertEquals(3, plan.nodePaid)
        assertEquals(emptyList<Int>(), plan.recognise)
        assertEquals(3, plan.boxes.size)
    }

    @Test
    fun twoNodesAndTheBoxBetweenThemStayDistinct() {
        // A page with two one-line nodes and an image caption detection found and
        // no node covers: both nodes are their own line and the middle box still
        // recognises. The mixed case the ticket says resolves on its own.
        val boxes = listOf(box(10, 0, 100, 30), box(10, 40, 100, 70), box(10, 80, 100, 110))
        val plan = route(boxes, listOf(node("見出し", 0, 0, 200, 30), node("脚注", 0, 80, 200, 110)))
        assertEquals(mapOf(0 to 0, 2 to 1), plan.nodeAt)
        assertEquals(listOf(1), plan.recognise)
        assertEquals(2, plan.nodePaid)
        assertEquals(0, plan.recovered)
    }

    // ── the ink sample is a filter, never a router ──────────────────────────

    @Test
    fun theInkSampleOnlyDropsRecoveredNodes() {
        // Structural, not a promise: `inkOk` returning false for everything removes
        // the recovered node and touches nothing else. Every box a node paid for is
        // still answered by that node, and every uncovered box still recognises —
        // the sample cannot make recognition run somewhere it would not otherwise,
        // which is #106's rule for it.
        val boxes = listOf(box(10, 0, 100, 30), box(400, 0, 500, 30))
        val plan = route(
            boxes,
            // The second node sits clear of both boxes' centres, so it is the
            // RECOVERED case the sample exists for.
            listOf(node("本文", 0, 0, 200, 40), node("余白", 700, 0, 800, 40)),
            inkOk = noInk,
        )
        // The recovered node is gone…
        assertEquals(0, plan.recovered)
        assertEquals(mapOf(0 to 0), plan.nodeAt)
        // …the augmented list is back to the detected boxes…
        assertEquals(boxes, plan.boxes)
        // …and the uncovered box recognises exactly as it would have with no
        // nodes at all.
        assertEquals(listOf(1), plan.recognise)
    }

    @Test
    fun theInkSampleNeverRemovesANodeABoxAlreadyPaysFor() {
        // A box vouches for its node by existing: the detector found ink there.
        // #106 rule 4 scopes the sample to `recovered` for that reason, and this
        // is where the scoping is checked.
        val boxes = listOf(box(10, 10, 90, 30))
        val plan = route(boxes, listOf(node("本文", 0, 0, 200, 40)), inkOk = noInk)
        assertEquals(mapOf(0 to 0), plan.nodeAt)
        assertEquals(emptyList<Int>(), plan.recognise)
        assertEquals(1, plan.nodePaid)
    }

    @Test
    fun aRecoveredNodeKeepsItsOwnTextWhenAVerticalNodeIsFilteredOutBeforeIt() {
        // The index-space trap, pinned. [ScreenTextRoute.answeringIndices] removes
        // the tate node and the routing then works on the filtered sequence, so
        // any index travelling back to the caller's node list has to be
        // translated. Reading the caller's list directly would put the recovered
        // node's box at the *dropped* node's rect and name the wrong node in
        // `nodeAt`, which no single-node fixture would ever surface.
        val boxes = listOf(box(0, 0, 40, 300), box(700, 0, 800, 30))
        val nodes = listOf(
            node("縦書き", 0, 0, 40, 300),      // dropped: a tall narrow block
            node("余白", 400, 600, 500, 640),   // no box centre here: recovered
        )
        val plan = route(boxes, nodes)
        // `nodeAt` names node **1** — the caller's index, past the filter.
        assertEquals(mapOf(2 to 1), plan.nodeAt)
        assertEquals(1, plan.recovered)
        // The appended box is the RECOVERED node's rect — not the vertical node's,
        // which is the box a stale index would have produced.
        assertEquals(LineBox.of(JpDictRect(400, 600, 500, 640)), plan.boxes[2])
        assertEquals(listOf(0, 1), plan.recognise)
    }

    // ── nodes mode ──────────────────────────────────────────────────────────

    @Test
    fun nodesModeDropsEveryUncoveredBoxAndKeepsEveryNode() {
        // The escape hatch for an app whose recogniser cannot cope: nothing
        // recognises, and a node still answers for the boxes it covers. The
        // uncovered box is *not* shown — that is the only mode in which a box is
        // dropped, and only by choice.
        val boxes = listOf(box(10, 0, 100, 30), box(400, 0, 500, 30))
        val plan = route(
            boxes,
            listOf(node("本文", 0, 0, 200, 40), node("余白", 700, 0, 800, 40)),
            recognise = false,
        )
        assertEquals(emptyList<Int>(), plan.recognise)
        assertEquals(mapOf(0 to 0, 2 to 1), plan.nodeAt)
        assertEquals(1, plan.recovered)
    }

    // ── vertical (tate) nodes do not answer ─────────────────────────────────

    @Test
    fun aVerticalNodeIsNotAnsweredForAndItsBoxesStillRecognise() {
        // `charBoxesAt` reads a row left to right, which is not what a tate column
        // draws as. Laid out as rows, every character would get a box as wide as
        // the column and be stacked side by side across it — the wrong character in
        // the wrong place. So the node does not answer and its boxes are the
        // pipeline this ticket did not change.
        val boxes = listOf(box(0, 0, 40, 300))
        val plan = route(boxes, listOf(node("縦書き", 0, 0, 40, 300)))
        assertEquals(emptyMap<Int, Int>(), plan.nodeAt)
        assertEquals(listOf(0), plan.recognise)
        assertEquals(boxes, plan.boxes)
    }

    @Test
    fun aYokoNodeIsNotFilteredOutByItsOwnShape() {
        // The guard is one-directional: a wide node, a square one and a two-line
        // paragraph are all horizontal and all answer. Only a tall narrow block is
        // vertical, and a false negative there costs recognition rather than
        // correctness — which is the safe direction for a rule this coarse.
        val nodes = listOf(
            node("見出し", 0, 0, 400, 40),
            node("猫", 0, 0, 40, 40),
            node("段落", 0, 0, 300, 90),
        )
        assertEquals(listOf(0, 1, 2), ScreenTextRoute.answeringIndices(nodes, emptyList(), ::isVertical))
    }

    @Test
    fun aNodeWhoseBoxesAreAllVerticalColumnsDoesNotAnswer() {
        // Same rule from the other side: the node's own rect may be wide (a column
        // block, a sidebar), but every detected box inside it is a tall narrow
        // column, so laying them out as rows is wrong whatever the node looks like.
        val nodes = listOf(node("縦書き", 0, 0, 400, 300), node("横書き", 0, 0, 200, 40))
        val columns = listOf(JpDictRect(10, 0, 40, 300), JpDictRect(60, 0, 90, 300))
        assertEquals(
            listOf(1),
            ScreenTextRoute.answeringIndices(nodes, columns, ::isVertical),
        )
    }

    // ── char boxes: positional alignment, and where a space's box goes ──────

    @Test
    fun charBoxesHasOneEntryPerCharacterIncludingWhitespace() {
        // The alignment the overlay depends on: `charBoxes[i]` is `text[i]` for the
        // neighbour chips, the nav graph, the cursor and every tap. Dropping the
        // spaces would shift everything after the first one.
        val text = "日 本"
        val boxes = ScreenTextRoute.charBoxes(text, JpDictRect(0, 0, 300, 40))
        assertEquals(text.length, boxes.size)
        // The space sits in the middle third and is not tappable: it is the gap
        // between the two characters' boxes, collapsed to no width.
        assertEquals(0, boxes[1].width())
        assertEquals(boxes[0].right, boxes[1].left)
    }

    @Test
    fun aSpaceGetsAZeroWidthBoxWhereItIsAndNotAtTheOrigin() {
        // Why not `JpDictRect(0, 0, 0, 0)`, the repo's fallback for a *missing*
        // box: it is drawn as nothing and hit-tests as nothing either way, but it
        // sits at the top-left of the screenshot, and the page's nav graph is built
        // from every char box — so each space would add a navigation node pinned
        // to the corner, and cursor navigation would have hundreds of identical
        // positions to choose from. The gap is collapsed onto the character beside
        // it instead: same row, same edge, zero width, so it is neither drawn nor
        // tappable and the line's own bounds are unchanged.
        val text = "日本"
        val boxes = ScreenTextRoute.charBoxes("日 本", JpDictRect(100, 200, 400, 240))
        assertEquals(text.length, 2)
        assertEquals(3, boxes.size)
        val space = boxes[1]
        assertEquals(0, space.width())
        assertEquals(space.top, boxes[0].top)
        assertEquals(space.bottom, boxes[0].bottom)
        // Inside the line: the line's bounds are derived from the min/max of these.
        assertTrue(space.left >= 100 && space.right <= 400)
        assertTrue(space.top >= 200 && space.bottom <= 240)
    }

    @Test
    fun leadingAndTrailingSpacesCollapseOntoTheirNeighbour() {
        // The two ends have no "character beside them" on one side, so each takes
        // the edge of the one that does exist — a leading space at the first
        // character's left edge, a trailing one at the last character's right edge.
        val leading = ScreenTextRoute.charBoxes(" 猫", JpDictRect(0, 0, 200, 40))
        assertEquals(2, leading.size)
        assertEquals(0, leading[0].width())
        assertEquals(leading[1].left, leading[0].left)

        val trailing = ScreenTextRoute.charBoxes("猫 ", JpDictRect(0, 0, 200, 40))
        assertEquals(2, trailing.size)
        assertEquals(0, trailing[1].width())
        assertEquals(trailing[0].right, trailing[1].left)
    }

    @Test
    fun charBoxesLaysAParagraphOverTheDetectedRowsInsideIt() {
        // The node's own string spread over the detected boxes that fall inside
        // it — the paragraph case, where the layout comes from the detector's rows
        // and the characters from the tree.
        val text = "あいうえお"
        val rows = listOf(JpDictRect(0, 0, 300, 30), JpDictRect(0, 30, 300, 60))
        val boxes = ScreenTextRoute.charBoxes(text, JpDictRect(0, 0, 300, 60), rows)
        assertEquals(5, boxes.size)
        // Five fullwidth characters over 600 px of rows is 120 px/em, so the first
        // 300 px row takes two of them and the second takes three — the rows are
        // the detector's, and the characters are laid out over them in order.
        assertEquals(0, boxes[0].top)
        assertEquals(30, boxes[1].bottom)
        assertEquals(30, boxes[2].top)
        assertEquals(60, boxes[4].bottom)
        // Each row is filled across its own width, so the second row starts at the
        // row's left edge rather than where the first one ended.
        assertEquals(listOf(
            JpDictRect(0, 0, 150, 30), JpDictRect(150, 0, 300, 30),
            JpDictRect(0, 30, 100, 60), JpDictRect(100, 30, 200, 60), JpDictRect(200, 30, 300, 60),
        ), boxes)
    }

    @Test
    fun everyCharBoxStaysInsideTheNodesOwnRect() {
        // #106's "clip to the node's visible rect", end to end through this
        // module: a detected row that overhangs the node on both sides is clipped
        // before any character is placed, so a node-backed line cannot draw or
        // offer a tap outside the text it actually occupies.
        val boxes = ScreenTextRoute.charBoxes("あい", JpDictRect(100, 100, 300, 140), listOf(JpDictRect(60, 110, 340, 130)))
        assertEquals(2, boxes.size)
        for (b in boxes) {
            assertTrue("left", b.left >= 100)
            assertTrue("top", b.top >= 100)
            assertTrue("right", b.right <= 300)
            assertTrue("bottom", b.bottom <= 140)
        }
    }

    @Test
    fun charBoxesOnTextWithNothingToPlaceStillReturnsOneEntryPerCharacter() {
        // Degenerate input the caller can hit (a node rect with no area): the list
        // must stay aligned with the text, and every entry must be a box that is
        // drawn as nothing and hit-tests as nothing rather than a crash.
        val boxes = ScreenTextRoute.charBoxes("あい", JpDictRect(10, 10, 10, 40))
        assertEquals(2, boxes.size)
        assertEquals(0, boxes[0].width())
        assertEquals(0, boxes[1].width())
    }
}
