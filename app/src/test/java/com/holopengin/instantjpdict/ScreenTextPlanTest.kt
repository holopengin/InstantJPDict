package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #106 package 1: the pure routing and character geometry the Android wiring
 * will consume — which boxes the accessibility tree pays for, which nodes
 * detection missed and are recovered anyway, which boxes still recognise, and
 * the per-character boxes a node's own string is drawn with.
 *
 * These are the rules #106 could not decide on the device: centre-on-edge is a
 * pixel-exact boundary, smallest-node-wins is a tie-break, and the row/character
 * allocation is arithmetic that would otherwise be a comment. Everything here is
 * pure (`JpDictRect` is integers and nothing else), so the whole module is pinned
 * in full rather than sampled; the parts that need a `Bitmap` — the screenshot,
 * the ink sample behind a recovered node — are the Android package's.
 */
class ScreenTextPlanTest {

    // ── fixtures ────────────────────────────────────────────────────────────

    /** The active window's visible rect, in the bitmap's own coordinates. */
    private val window = JpDictRect(0, 0, 1080, 1920)

    private fun box(l: Int, t: Int, r: Int, b: Int) = JpDictRect(l, t, r, b)

    private fun node(text: String, l: Int, t: Int, r: Int, b: Int) = ScreenTextNode(text, box(l, t, r, b))

    /** [charBoxesAt] re-indexed by character: what each text character got, so a
     *  test can pin "every character accounted for exactly once" by name. */
    private fun placed(text: String, boxes: List<JpDictRect?>): List<Char?> =
        boxes.mapIndexed { i, b -> if (b == null) null else text[i] }

    // ── routing: which node pays for a box ─────────────────────────────────

    @Test
    fun aBoxWhoseCentreIsInsideTheNodeIsPaidFor() {
        // The whole rule in one line: detection arbitrates, the node answers.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(110, 110, 190, 130)),
            nodes = listOf(node("吾輩", 100, 100, 200, 140)),
            window = window,
        )
        assertEquals(mapOf(0 to 0), routing.nodePaid)
        assertEquals(emptyList<Int>(), routing.recognise)
        assertEquals(emptyList<Int>(), routing.recovered)
    }

    @Test
    fun centreOnTheNodeLeftEdgeIsCovered() {
        // `left <= x < right`: a centre exactly on the near edge is inside, the
        // same half-open convention `android.graphics.Rect.contains` uses. The
        // box here is half outside the node and still node-paid.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(50, 110, 150, 130)),
            nodes = listOf(node("猫", 100, 100, 200, 140)),
            window = window,
        )
        assertEquals(mapOf(0 to 0), routing.nodePaid)
    }

    @Test
    fun centreOnTheNodeRightEdgeIsNotCovered() {
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(150, 110, 250, 130)),
            nodes = listOf(node("猫", 100, 100, 200, 140)),
            window = window,
        )
        assertEquals(emptyMap<Int, Int>(), routing.nodePaid)
        assertEquals(listOf(0), routing.recognise)
        assertEquals(listOf(0), routing.recovered)
    }

    @Test
    fun centreOnTheNodeTopEdgeIsCoveredAndOnTheBottomEdgeIsNot() {
        // The vertical pair of the same boundary, so neither edge is decided by
        // accident: top is inclusive, bottom is exclusive.
        val onTop = ScreenTextPlan.plan(
            boxes = listOf(box(110, 60, 190, 140)),
            nodes = listOf(node("猫", 100, 100, 200, 140)),
            window = window,
        )
        assertEquals(mapOf(0 to 0), onTop.nodePaid)
        val onBottom = ScreenTextPlan.plan(
            boxes = listOf(box(110, 120, 190, 200)),
            nodes = listOf(node("猫", 100, 100, 200, 140)),
            window = window,
        )
        assertEquals(listOf(0), onBottom.recognise)
    }

    @Test
    fun aBoxStraddlingTheNodeEdgeGoesToRecognition() {
        // #106's sub-rule: match on the centre, so a box that only overlaps the
        // node's edge is OCR'd rather than answered for.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(20, 110, 120, 130)),
            nodes = listOf(node("猫", 100, 100, 200, 140)),
            window = window,
        )
        assertEquals(emptyMap<Int, Int>(), routing.nodePaid)
        assertEquals(listOf(0), routing.recognise)
    }

    @Test
    fun theSmallestNodeWinsWhenTwoNodesCoverTheSameCentre() {
        // A paragraph node and the smaller span inside it both contain the
        // centre; the more specific rect answers, so the inner text (not the
        // whole paragraph's) is what the box draws.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(120, 10, 180, 30)),
            nodes = listOf(node("outer", 0, 0, 300, 40), node("inner", 100, 0, 200, 40)),
            window = window,
        )
        assertEquals(mapOf(0 to 1), routing.nodePaid)
    }

    @Test
    fun anOuterNodeStillPaysForABoxTheInnerOneMisses() {
        // Smallest-wins is a tie-break, not an exclusive filter: the outer node
        // is paid for the box its centre is in, and is recovered neither.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(10, 10, 80, 30), box(120, 10, 180, 30)),
            nodes = listOf(node("outer", 0, 0, 300, 40), node("inner", 100, 0, 200, 40)),
            window = window,
        )
        assertEquals(mapOf(0 to 0, 1 to 1), routing.nodePaid)
        assertEquals(emptyList<Int>(), routing.recognise)
        assertEquals(emptyList<Int>(), routing.recovered)
    }

    @Test
    fun equalAreaNodesTieToTheCallersFirstNode() {
        // Determinism, not a rule: two nodes of the same area are equally
        // specific, so the caller's tree order decides — never a hash order.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(110, 10, 190, 30)),
            nodes = listOf(node("first", 100, 0, 200, 40), node("second", 100, 0, 200, 40)),
            window = window,
        )
        assertEquals(mapOf(0 to 0), routing.nodePaid)
    }

    @Test
    fun oneNodeCanPayForSeveralBoxesOfAParagraph() {
        // The paragraph case the ticket is about: a detector splits one node
        // into per-line boxes, and every one of them is the same node's text.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(10, 0, 300, 30), box(10, 30, 200, 60), box(10, 60, 250, 90)),
            nodes = listOf(node("三行", 0, 0, 320, 90)),
            window = window,
        )
        assertEquals(mapOf(0 to 0, 1 to 0, 2 to 0), routing.nodePaid)
        assertEquals(emptyList<Int>(), routing.recognise)
        assertEquals(emptyList<Int>(), routing.recovered)
    }

    // ── routing: recovered vs recognised ───────────────────────────────────

    @Test
    fun aNodeWithNoBoxOverItIsRecoveredWhileItsNeighbourIsRecognised() {
        // #106 rule 4 against rule 5, on one screen: box 0 is node-paid, box 1
        // is OCR (no node covers it), and node 2 — which no box vouches for — is
        // recovered rather than lost.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(10, 10, 90, 30), box(150, 10, 170, 30), box(210, 10, 290, 30)),
            nodes = listOf(
                node("paid", 0, 0, 100, 40),
                node("over", 200, 0, 300, 40),
                node("missed", 400, 0, 500, 40),
            ),
            window = window,
        )
        assertEquals(mapOf(0 to 0, 2 to 1), routing.nodePaid)
        assertEquals(listOf(1), routing.recognise)
        assertEquals(listOf(2), routing.recovered)
    }

    @Test
    fun anEmptyNodeListPlansEveryBoxForRecognition() {
        // #106 rule 5, structurally: a game, an emulator, a canvas or a photo has
        // no text nodes, so every box takes the recognition path and the pipeline
        // is what it is today. Nothing about this result may depend on having
        // nodes.
        val boxes = listOf(box(10, 0, 100, 30), box(10, 30, 100, 60), box(2000, 100, 2100, 130))
        val routing = ScreenTextPlan.plan(boxes, emptyList(), window)
        assertEquals(emptyMap<Int, Int>(), routing.nodePaid)
        assertEquals(emptyList<Int>(), routing.recovered)
        assertEquals(boxes.indices.toList(), routing.recognise)
    }

    @Test
    fun aBoxOutsideTheWindowIsNeverDroppedFromRecognition() {
        // The window gates the *tree*, not the detector: a box the window does
        // not show still recognises, exactly as it did before #106. Dropping it
        // here would be the one behaviour change an all-OCR screen could see.
        val routing = ScreenTextPlan.plan(listOf(box(2000, 2000, 2100, 2030)), emptyList(), window)
        assertEquals(listOf(0), routing.recognise)
    }

    @Test
    fun aNodeOutsideTheWindowIsNeitherPaidForNorRecovered() {
        // #106 rule 2/4's visible-window guard: a node the screenshot cannot
        // show cannot answer for a box, and cannot be recovered either.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(2000, 2000, 2100, 2030)),
            nodes = listOf(node("offscreen", 2000, 2000, 2100, 2040)),
            window = window,
        )
        assertEquals(emptyMap<Int, Int>(), routing.nodePaid)
        assertEquals(listOf(0), routing.recognise)
        assertEquals(emptyList<Int>(), routing.recovered)
    }

    @Test
    fun aNodeStraddlingTheWindowEdgeStaysIn() {
        // Intersection, not containment: a paragraph scrolled half off the top
        // of the screen is still visible, and still answers for what is drawn.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(10, 10, 90, 30)),
            nodes = listOf(node("clipped", 0, -400, 300, 40)),
            window = window,
        )
        assertEquals(mapOf(0 to 0), routing.nodePaid)
    }

    @Test
    fun aDegenerateNodeRectIsIgnored() {
        // Zero-area nodes (a stale virtual node, a collapsed view) can neither
        // contain a centre nor hold a character box, so they are not reported as
        // recovered — the caller would have nothing to draw for them.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(10, 10, 90, 30)),
            nodes = listOf(node("collapsed", 50, 10, 50, 30), node("flat", 10, 20, 90, 20)),
            window = window,
        )
        assertEquals(emptyMap<Int, Int>(), routing.nodePaid)
        assertEquals(listOf(0), routing.recognise)
        assertEquals(emptyList<Int>(), routing.recovered)
    }

    @Test
    fun aNodeWithNoTextCannotPayForABox() {
        // Belt and braces on the caller's filter: a node with no string has
        // nothing to answer with, so it must not silence the box.
        val routing = ScreenTextPlan.plan(
            boxes = listOf(box(10, 10, 90, 30)),
            nodes = listOf(node("", 0, 0, 100, 40)),
            window = window,
        )
        assertEquals(emptyMap<Int, Int>(), routing.nodePaid)
        assertEquals(listOf(0), routing.recognise)
        assertEquals(emptyList<Int>(), routing.recovered)
    }

    // ── character geometry: visual rows ────────────────────────────────────

    @Test
    fun visualRowsAreTheBoxesInsideTheNodeSortedTopToBottom() {
        // A paragraph node is commonly split into per-line boxes by the
        // detector; those are the rows, in reading order, whatever order they
        // arrive in. The box off to the right is not in the node.
        val rows = ScreenTextPlan.visualRows(
            nodeRect = box(0, 0, 300, 90),
            lineBoxes = listOf(box(0, 60, 300, 90), box(400, 50, 500, 80), box(0, 0, 300, 30), box(0, 30, 300, 60)),
        )
        assertEquals(listOf(box(0, 0, 300, 30), box(0, 30, 300, 60), box(0, 60, 300, 90)), rows)
    }

    @Test
    fun aNodeWithNoBoxesInsideItIsLaidOutAsOneRow() {
        // No detected box inside the node (a recovered node, or one the detector
        // split nothing out of) means the node's own rect is the row — which is
        // why `visualRows` reports none and `charBoxes` falls back rather than
        // `visualRows` inventing a rect the caller never passed.
        assertEquals(emptyList<JpDictRect>(), ScreenTextPlan.visualRows(box(0, 0, 300, 40), emptyList()))
        assertEquals(
            listOf(box(0, 0, 150, 40), box(150, 0, 300, 40)),
            ScreenTextPlan.charBoxes("あい", box(0, 0, 300, 40), emptyList()),
        )
    }

    // ── character geometry: the allocation ─────────────────────────────────

    @Test
    fun aParagraphSplitAcrossThreeDetectedRowsAccountsForEveryCharacterOnce() {
        // Nine fullwidth characters over three 300 px rows: the em that makes the
        // string fill the rows is 900/9 = 100 px, so each row takes three
        // characters (the third fits exactly, 300 <= 300), and no character is
        // dropped, duplicated or reordered.
        val text = "あいうえおかきくけ"
        val rows = listOf(box(0, 0, 300, 30), box(0, 30, 300, 60), box(0, 60, 300, 90))
        val byCharIndex = ScreenTextPlan.charBoxesAt(text, box(0, 0, 300, 90), rows)
        assertEquals(text.map { it }, placed(text, byCharIndex))
        assertEquals(
            listOf(
                box(0, 0, 100, 30), box(100, 0, 200, 30), box(200, 0, 300, 30),
                box(0, 30, 100, 60), box(100, 30, 200, 60), box(200, 30, 300, 60),
                box(0, 60, 100, 90), box(100, 60, 200, 90), box(200, 60, 300, 90),
            ),
            ScreenTextPlan.charBoxes(text, box(0, 0, 300, 90), rows),
        )
    }

    @Test
    fun aNewlineForcesARowBreakTheProportionalSplitWouldNotMake() {
        // "あ\n\n\nい" is two characters, so a purely proportional split would
        // give row 0 and row 1. The explicit breaks put the second character on
        // the third row and leave the middle one empty — the text's own line
        // structure wins over the arithmetic.
        val text = "あ\n\n\nい"
        val rows = listOf(box(0, 0, 300, 30), box(0, 30, 300, 60), box(0, 60, 300, 90))
        assertEquals(listOf(box(0, 0, 300, 30), box(0, 60, 300, 90)), ScreenTextPlan.charBoxes(text, box(0, 0, 300, 90), rows))
        assertEquals(listOf('あ', null, null, null, 'い'), placed(text, ScreenTextPlan.charBoxesAt(text, box(0, 0, 300, 90), rows)))
    }

    @Test
    fun aMixedFullwidthHalfwidthRowHalvesTheAsciiAdvance() {
        // Two kanji and one ASCII letter in a 300 px row: 1.0 + 1.0 + 0.5 em, so
        // the latin glyph gets 60 px where the kanji get 120.
        val text = "日本A"
        assertEquals(
            listOf(box(0, 0, 120, 40), box(120, 0, 240, 40), box(240, 0, 300, 40)),
            ScreenTextPlan.charBoxes(text, box(0, 0, 300, 40)),
        )
    }

    @Test
    fun whitespaceOccupiesExtentButGetsNoBox() {
        // An ideographic space is a fullwidth glyph of zero ink: it holds its
        // 1.0 em (the kanji around it stay 100 px) and is not tappable, which is
        // what a null in [charBoxesAt] says.
        val text = "日　本"
        val at = ScreenTextPlan.charBoxesAt(text, box(0, 0, 300, 40))
        assertEquals(listOf(box(0, 0, 100, 40), null, box(200, 0, 300, 40)), at)
        assertEquals(listOf(box(0, 0, 100, 40), box(200, 0, 300, 40)), ScreenTextPlan.charBoxes(text, box(0, 0, 300, 40)))
    }

    @Test
    fun theCallersWidthPredicateReplacesTheDefaultOne() {
        // The default is local range checks so this module runs in the host
        // tests; the Android caller passes the app's own `isHalfWidth`. Both
        // halves of the parameter are pinned here, so a change to the default
        // cannot silently stop the argument being honoured.
        val text = "日 本"
        assertEquals(
            listOf(box(0, 0, 120, 40), box(180, 0, 300, 40)),
            ScreenTextPlan.charBoxes(text, box(0, 0, 300, 40)),
        )
        assertEquals(
            listOf(box(0, 0, 100, 40), box(200, 0, 300, 40)),
            ScreenTextPlan.charBoxes(text, box(0, 0, 300, 40), isFullWidth = { true }),
        )
    }

    @Test
    fun moreCharactersThanRowsKeepTheLastRowInsideTheRect() {
        // The string is longer than the geometry can place at the nominal scale.
        // The last row absorbs the remainder and normalises it to its own width,
        // so the boxes get narrower instead of leaving the node's rect.
        val text = "あいうえお"
        val boxes = ScreenTextPlan.charBoxes(text, box(0, 0, 100, 30))
        assertEquals(
            listOf(box(0, 0, 20, 30), box(20, 0, 40, 30), box(40, 0, 60, 30), box(60, 0, 80, 30), box(80, 0, 100, 30)),
            boxes,
        )
    }

    @Test
    fun moreRowsThanCharactersLeavesTheTrailingRowsEmpty() {
        val text = "あい"
        val rows = listOf(box(0, 0, 300, 30), box(0, 30, 300, 60), box(0, 60, 300, 90), box(0, 90, 300, 120))
        assertEquals(
            listOf(box(0, 0, 300, 30), box(0, 30, 300, 60)),
            ScreenTextPlan.charBoxes(text, box(0, 0, 300, 120), rows),
        )
    }

    @Test
    fun everyBoxStaysInsideTheNodeRectEvenWhenARowStraddlesItsEdge() {
        // The row here is a detected box that overhangs the node's rect on BOTH
        // sides while its centre is inside, so it is clipped to (0,10,100,30)
        // before any character is placed — #106's "clip to the node's visible
        // rect". Without the clip the first character would start at -40 px.
        val text = "あい"
        val rows = ScreenTextPlan.charBoxes(text, box(0, 0, 100, 40), listOf(box(-40, 10, 140, 30)))
        assertEquals(listOf(box(0, 10, 50, 30), box(50, 10, 100, 30)), rows)
        for (b in rows) {
            assertTrue("left", b.left >= 0)
            assertTrue("top", b.top >= 0)
            assertTrue("right", b.right <= 100)
            assertTrue("bottom", b.bottom <= 40)
        }
    }

    @Test
    fun boxesAreOrderedTopToBottomAcrossRowsAndLeftToRightWithinThem() {
        val text = "あいうえおかきくけ"
        val rows = ScreenTextPlan.charBoxes(text, box(0, 0, 300, 90))
        assertEquals(9, rows.size)
        for (i in 1 until rows.size) {
            assertTrue("ordering at $i: ${rows[i - 1]} then ${rows[i]}", rows[i - 1].left <= rows[i].left)
        }
    }

    // ── character geometry: degenerate input ───────────────────────────────

    @Test
    fun anEmptyStringProducesNothing() {
        assertEquals(emptyList<JpDictRect>(), ScreenTextPlan.charBoxes("", box(0, 0, 300, 40)))
        assertEquals(emptyList<JpDictRect?>(), ScreenTextPlan.charBoxesAt("", box(0, 0, 300, 40)))
    }

    @Test
    fun aDegenerateNodeRectProducesNothing() {
        // No room, no boxes, no throw: a zero-width or zero-height node rect
        // cannot hold a character, and the caller's next line must still run.
        assertEquals(emptyList<JpDictRect>(), ScreenTextPlan.charBoxes("あ", box(10, 10, 10, 40)))
        assertEquals(emptyList<JpDictRect>(), ScreenTextPlan.charBoxes("あ", box(10, 10, 100, 10)))
        assertEquals(listOf<JpDictRect?>(null), ScreenTextPlan.charBoxesAt("あ", box(10, 10, 10, 40)))
    }

    @Test
    fun aStringOfOnlyLineBreaksProducesNothing() {
        assertEquals(emptyList<JpDictRect>(), ScreenTextPlan.charBoxes("\n\n\n", box(0, 0, 300, 90)))
    }

    @Test
    fun whitespaceOnlyTextOccupiesTheRowWithoutProducingBoxes() {
        // Extent without ink: the row is filled (so the string does fill the
        // node) but there is nothing to draw or tap.
        val at = ScreenTextPlan.charBoxesAt("   ", box(0, 0, 300, 40))
        assertEquals(listOf(null, null, null), at)
        assertEquals(emptyList<JpDictRect>(), ScreenTextPlan.charBoxes("   ", box(0, 0, 300, 40)))
    }

    @Test
    fun aSingleCharacterTakesTheWholeRow() {
        assertEquals(listOf(box(0, 0, 300, 40)), ScreenTextPlan.charBoxes("猫", box(0, 0, 300, 40)))
    }

    @Test
    fun charBoxesAtIsTheCharBoxesListWithGapsKept() {
        // The two views of one layout: the boxes in reading order, and the same
        // boxes keyed by character index so a caller can splice them into
        // `LineResult.charBoxes` without guessing where the whitespace went.
        val text = "日 本"
        val byCharIndex = ScreenTextPlan.charBoxesAt(text, box(0, 0, 300, 40))
        assertEquals(ScreenTextPlan.charBoxes(text, box(0, 0, 300, 40)), byCharIndex.filterNotNull())
        assertNull(byCharIndex[1])
    }

    // ── the default fullwidth predicate ────────────────────────────────────

    @Test
    fun theDefaultWidthsAreTheAppsOwnClasses() {
        // ASCII and the halfwidth-kana block are halfwidth; kana, kanji and
        // Japanese punctuation (including the ideographic space) are fullwidth.
        // The two extents are the numbers the whole layout divides by, so they
        // are pinned here rather than only being implied by a box list.
        assertEquals(1.0f, ScreenTextPlan.FULLWIDTH_EXTENT, 0f)
        assertEquals(0.5f, ScreenTextPlan.HALFWIDTH_EXTENT, 0f)
        for (fullwidth in listOf('あ', '漢', '。', '　', 'ヴ')) {
            assertTrue("$fullwidth should be fullwidth", ScreenTextPlan.isFullWidth(fullwidth))
        }
        for (halfwidth in listOf('A', '7', 'ｶ', ' ', '\n')) {
            assertTrue("$halfwidth should be halfwidth", !ScreenTextPlan.isFullWidth(halfwidth))
        }
    }

    // ── does the text fit the rect it is drawn in? (#106 elision) ────────────

    @Test
    fun aLineOfTextThatMatchesItsRowFits() {
        // 20 fullwidth glyphs in a 1000x60 row: what a normal line of Japanese
        // looks like, and the case that must never be refused.
        val row = box(0, 0, 1000, 60)
        assertTrue(ScreenTextPlan.fits("吾輩は猫である名前はまだ無い所で", row, listOf(row)))
    }

    @Test
    fun anElidedNotificationDoesNotFit() {
        // The device case: a collapsed card draws two lines while its node carries
        // the whole notification. Laying it out would squeeze hundreds of glyphs
        // into those two rows, which is what the maintainer saw stacked.
        val row1 = box(0, 0, 1000, 70)
        val row2 = box(0, 80, 1000, 150)
        val notification = "新しいメッセージがあります。" .repeat(40)
        assertFalse(ScreenTextPlan.fits(notification, box(0, 0, 1000, 150), listOf(row1, row2)))
    }

    @Test
    fun aHalfwidthLineHoldsTwiceTheCharacters() {
        // Extent is per character class, not per character: 30 ASCII glyphs are 15
        // em, so they fit a row that 30 fullwidth ones would not.
        val row = box(0, 0, 1000, 60)
        assertTrue(ScreenTextPlan.fits("a".repeat(30), row, listOf(row)))
        assertFalse(ScreenTextPlan.fits("あ".repeat(60), row, listOf(row)))
    }

    @Test
    fun moreLinesThanRowsDoesNotFit() {
        val row = box(0, 0, 1000, 60)
        assertFalse(ScreenTextPlan.fits("あ\nい\nう", row, listOf(row)))
    }

    @Test
    fun aTrailingNewlineStillFits() {
        val row = box(0, 0, 1000, 60)
        assertTrue(ScreenTextPlan.fits("あい\n", row, listOf(row)))
    }

    // ── malformed UTF-16 from an app's own text (#106) ──────────────────────

    @Test
    fun aLoneSurrogateBecomesAReplacementCharacter() {
        // The crash in the ticket: a screen-reader node whose text carried one of
        // these threw out of the UniFFI string converter on the next lookup.
        val bad = "a\uD83Db"           // high surrogate, no pair
        val low = "a\uDE00b"           // low surrogate, no pair
        assertEquals("a\uFFFDb", ScreenTextPlan.wellFormed(bad))
        assertEquals("a\uFFFDb", ScreenTextPlan.wellFormed(low))
        assertEquals("\uFFFD", ScreenTextPlan.wellFormed("\uD83D"))
    }

    @Test
    fun aWellFormedStringComesBackUntouched() {
        for (text in listOf("", "abc", "吾輩は猫である", "😀 絵文字 \uD83D\uDE00")) {
            assertEquals(text, ScreenTextPlan.wellFormed(text))
            assertEquals("same length for $text", text.length, ScreenTextPlan.wellFormed(text).length)
        }
    }

    @Test
    fun theResultIsAlwaysEncodableAsUtf8() {
        // What the crash actually was: the engine encodes the string to UTF-8.
        val encoder = java.nio.charset.Charset.forName("UTF-8").newEncoder()
        for (text in listOf("a\uD83Db", "\uDE00", "あ\uD83D", "ok", "😀")) {
            assertTrue(
                "should encode: $text",
                encoder.canEncode(ScreenTextPlan.wellFormed(text)),
            )
        }
    }

    @Test
    fun aSliceThatCutsAPairInHalfIsStillEncodable() {
        // The robot-emoji crash: the lookup slices the line's characters, so a tap
        // inside 🤖 (a valid pair) can produce a string that starts with its lower
        // half. Sanitising at the shim boundary is what makes that encodable; the
        // half becomes U+FFFD and the lookup simply finds nothing.
        val robot = "\uD83E\uDD16"
        val encoder = java.nio.charset.Charset.forName("UTF-8").newEncoder()
        val fromLowHalf = robot.substring(1) + "の"
        assertEquals("\uFFFDの", ScreenTextPlan.wellFormed(fromLowHalf))
        assertTrue(encoder.canEncode(ScreenTextPlan.wellFormed(fromLowHalf)))
        val toHighHalf = "猫" + robot.substring(0, 1)
        assertTrue(encoder.canEncode(ScreenTextPlan.wellFormed(toHighHalf)))
    }

    @Test
    fun substitutionIsOneForOneSoBoxesStayAligned() {
        val mixed = "猫\uD83D犬"
        assertEquals(mixed.length, ScreenTextPlan.wellFormed(mixed).length)
    }

    // ── measured advances (#106 spacing) ────────────────────────────────────

    @Test
    fun aLongLineIsShrunkIntoItsRowRatherThanBunchedAtTheEnd() {
        // The maintainer's "characters bunch up and overlap the end of the line":
        // ten 40px advances in a 100px row. The width bound shrinks the size to 0.25,
        // so every character fits and none shares pixels with its neighbour.
        val row = box(0, 0, 100, 60)
        val at = ScreenTextPlan.charBoxesAt("あ".repeat(10), row, listOf(row), advanceOf = { 40f })
        val boxes = at.filterNotNull()
        assertEquals(10, boxes.size)
        for (i in 1 until boxes.size) {
            assertTrue(
                "box $i starts at ${boxes[i].left}, the one before ends at ${boxes[i - 1].right}",
                boxes[i].left >= boxes[i - 1].right,
            )
        }
        assertTrue("all ten should be inside the row", boxes.last().right <= row.right)
    }

    @Test
    fun aMeasuredLineIsScaledToTheRowWidth() {
        // The maintainer: "font size should scale up to fit the real width of the
        // line; right now the font is too small, so the string is too short". A box
        // whose height is the ink's height under-estimates the font, and the box's
        // width is the drawn line's width — so the measured advances are scaled to
        // it. Two 40px advances in an 80px row are already right (scale 1)…
        val row = box(0, 0, 80, 60)
        val at = ScreenTextPlan.charBoxesAt("あい", row, listOf(row), advanceOf = { 40f })
        assertEquals(0, at[0]!!.left)
        assertEquals(40, at[0]!!.right)
        assertEquals(80, at[1]!!.right)
        assertEquals(1f, ScreenTextPlan.measuredScale("あい", listOf(row)) { 40f })
    }

    @Test
    fun aMeasuredLineGrowsOnlyToTheInkRatio() {
        // The same text in a 160px row could fill it by width, but growth stops at the
        // ink ratio: the row is the app's ink (0.88em), so the size that drew it is
        // 1.26x our base and no more. A short line stays short, in its own size.
        val row = box(0, 0, 160, 60)
        val at = ScreenTextPlan.charBoxesAt("あい", row, listOf(row), advanceOf = { 40f })
        assertEquals(50, at[0]!!.right)
        assertEquals(101, at[1]!!.right)
        assertEquals(1.26f, ScreenTextPlan.measuredScale("あい", listOf(row)) { 40f })
    }

    @Test
    fun aShortLabelIsNeverBlownUpAndALongOneIsShrunkToFit() {
        // The two directions the width bound may act in: it never grows a short label
        // past the ink ratio, and it shrinks a string that would not fit at all.
        val row = box(0, 0, 1000, 60)
        assertEquals(1.26f, ScreenTextPlan.measuredScale("あい", listOf(row)) { 40f })
        assertEquals(0.25f, ScreenTextPlan.measuredScale("あ".repeat(100), listOf(row)) { 40f })
    }

    @Test
    fun measuredAdvancesWrapWhenTheRowsRunOut() {
        // Four 40px advances in two 100px rows: the fit is 1.25x, so two characters
        // per row and the third wraps.
        val node = box(0, 0, 100, 120)
        val row1 = box(0, 0, 100, 60)
        val row2 = box(0, 60, 100, 120)
        val at = ScreenTextPlan.charBoxesAt("ああああ", node, listOf(row1, row2), advanceOf = { 40f })
        assertEquals(0, at[2]!!.left)
        assertEquals(60, at[2]!!.top)
    }

    @Test
    fun nothingMeasurableLeavesTheScaleAlone() {
        assertEquals(1f, ScreenTextPlan.measuredScale("", listOf(box(0, 0, 10, 10))) { 40f })
        assertEquals(1f, ScreenTextPlan.measuredScale("あ", emptyList()) { 40f })
    }

    @Test
    fun aPrefixIsWhatFitsInTheBox() {
        // The visible-prefix rule: a 200x40 box holds about six fullwidth glyphs, so
        // the drawn text is the leading part of the string — which is what an ellipsis
        // shows, and what keeps the region from being empty when nothing was detected
        // inside it.
        val text = "詳細".repeat(100)
        val prefix = ScreenTextPlan.visiblePrefix(text, box(0, 0, 200, 40), emptyList())
        assertTrue("prefix was ${prefix.length} chars", prefix.length in 4..8)
        assertTrue(text.startsWith(prefix))
    }

    @Test
    fun aFittingStringIsItsOwnPrefix() {
        val row = box(0, 0, 1000, 60)
        assertEquals("吾輩は猫である名前はまだ無い", ScreenTextPlan.visiblePrefix("吾輩は猫である名前はまだ無い", row, listOf(row)))
    }

    @Test
    fun aSmallBoxClaimingATonOfTextDoesNotFit() {
        // The maintainer's case: a node whose rect is a tiny box while its text is an
        // entire notification's detail. With no detected box inside it, the box
        // itself is the evidence — a rect that size cannot be showing that much.
        assertFalse(ScreenTextPlan.fits("詳細".repeat(100), box(0, 0, 200, 40), emptyList()))
    }

    @Test
    fun aSmallBoxWithTheTextItCanHoldStillFits() {
        // The same box, text it can actually show: recovered as #106 rule 4 intends.
        assertTrue(ScreenTextPlan.fits("詳細", box(0, 0, 200, 40), emptyList()))
    }

    @Test
    fun aTallBoxWithNothingDetectedInItIsMeasuredAsOneRow() {
        // The conservative side of the same rule, pinned so it is a choice and not a
        // surprise: with no detected box inside a tall rect, the rect reads as one
        // row and long text fails. That node goes to OCR rather than the tree — the
        // safe direction, since the detector found nothing there to answer for.
        assertFalse(ScreenTextPlan.fits("あ".repeat(500), box(0, 0, 1000, 2000), emptyList()))
    }
}