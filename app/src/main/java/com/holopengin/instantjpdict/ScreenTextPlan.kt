package com.holopengin.instantjpdict

import kotlin.math.roundToInt

/**
 * #106: one accessibility tree's text node, already filtered by the caller to
 * what the tree may answer with — a **visible leaf** carrying its own `text`
 * (not `contentDescription`), **Japanese-bearing**, with the rect it is drawn in.
 *
 * The three filters are the caller's because they are the ones that need the
 * Android API: `isVisibleToUser` and the window rect come off the node, and the
 * Japanese test is the app's own. Keeping them out of this record is what lets
 * the routing below be decided per box on a host test.
 *
 * [rect] is in the screenshot's own pixel coordinates, like the detector's boxes
 * — the two are compared directly, which is the whole point of routing by
 * geometry.
 */
data class ScreenTextNode(
    val text: String,
    val rect: JpDictRect,
)

/**
 * #106: which boxes the tree answers for, which text the detector missed, and
 * which boxes still have to be recognised.
 *
 * Every field is keyed or valued by index into the **arguments as given** — the
 * dropped nodes keep their positions, so a caller holding its own
 * `AccessibilityNodeInfo` list can map an index back without a second lookup
 * table. Insertion order is preserved throughout, so a caller can log or iterate
 * the split in screen order.
 *
 * @property nodePaid box index → node index, for each box the tree pays for. A
 *   box may appear only once, and one node may pay for many boxes (a paragraph
 *   node is usually split into per-line boxes by the detector).
 * @property recovered node indices **no** box's centre falls inside. Detection's
 *   misses are not the floor: this text is in the tree, so it is drawn and looked
 *   up anyway (#106 rule 4). The caller applies its own guard to these — the ink
 *   sample from the screenshot, which needs a `Bitmap` and so is not here.
 * @property recognise box indices with no node paying for them, in box order.
 *   Recognised exactly as they are today. With no nodes at all this is every box
 *   index, which is what makes a game, an emulator, a canvas or a photo
 *   byte-for-byte the pipeline it was (#106 rule 5).
 */
data class ScreenTextRouting(
    val nodePaid: Map<Int, Int>,
    val recovered: List<Int>,
    val recognise: List<Int>,
)

/**
 * #106: the two halves of "let the tree answer where it can", as pure arithmetic
 * over the screenshot's pixel grid.
 *
 * ## What this replaces
 *
 * Detection already runs for every capture; recognition is the expensive half
 * (~530 ms of a ~842 ms page on the Pixel 7a fixture) and it is the half the
 * accessibility tree can answer for. So the tree **routes** rather than
 * replaces: [plan] decides per box whether the detector's box is covered by a
 * node whose text the tree already knows, and [charBoxes] lays that text out
 * itself — the boxes stay ours, measured from the node's own string, so the
 * overlay draws and taps exactly what it does for a recognised line.
 *
 * Nothing is lost in either direction. A box the tree does not cover is
 * recognised as before; a node no box covers is **recovered** rather than
 * dropped, because a detection miss on a native view must not lose text the
 * tree can still read. And where there are no nodes at all — games, emulators,
 * canvases, images, secure windows — every box recognises, so that guarantee is
 * structural rather than a heuristic to get wrong.
 *
 * ## What is deliberately not here
 *
 * No `Bitmap` and no Android import, so the whole module runs in the host tests
 * (`ScreenTextPlanTest`). That leaves three things to the caller, all of them
 * decisions that need pixels or the platform API: reading and filtering the
 * tree ([ScreenTextNode]), the **ink sample** that guards a recovered node
 * (#106 rule 4 — no box vouches for it, so a node the screenshot shows blank is
 * dropped here), and building the `LineResult` itself. The width predicate is a
 * parameter for the same reason: the app's own halfwidth classification is a
 * UniFFI crossing, so the default below is local range checks and the caller may
 * pass the real one.
 */
object ScreenTextPlan {

    // ── widths ─────────────────────────────────────────────────────────────

    /**
     * A fullwidth glyph's advance, in em: kana, kanji and Japanese punctuation,
     * including the ideographic space (#49's "JP and fullwidth latin share one
     * em box").
     */
    const val FULLWIDTH_EXTENT = 1.0f

    /**
     * A halfwidth glyph's advance, in em — ASCII and the halfwidth-kana block
     * (#49's 0.5em box).
     */
    const val HALFWIDTH_EXTENT = 0.5f

    /** Last code point of ASCII, the block the app classifies as halfwidth. */
    const val ASCII_LAST = 0x7F

    /** First code point of the halfwidth forms block (`｡` … `ﾟ`). */
    const val HALFWIDTH_KANA_FIRST = 0xFF61

    /** Last code point of the halfwidth forms block. */
    const val HALFWIDTH_KANA_LAST = 0xFF9F

    /**
     * The default width predicate: ASCII and halfwidth kana are halfwidth,
     * everything else fullwidth.
     *
     * These are the same two classes `OcrEngine.isHalfWidth` draws from, written
     * as range checks so nothing here depends on the UniFFI shim — which is what
     * lets the host tests pin this module. The Android caller passes the app's own
     * classification instead (`char -> !OcrEngine.isHalfWidth(char)`) so the boxes
     * agree with how the overlay will draw each glyph; the two are pinned against
     * each other in [ScreenTextPlanTest].
     */
    fun isFullWidth(ch: Char): Boolean {
        val code = ch.code
        return code > ASCII_LAST && (code < HALFWIDTH_KANA_FIRST || code > HALFWIDTH_KANA_LAST)
    }

    // ── routing ────────────────────────────────────────────────────────────

    /**
     * Route one capture: for every detected [boxes] entry, decide whether a node
     * answers for it; and for every node, decide whether detection found it.
     *
     * The rules, in the order they bite:
     *
     * - **A box is covered when its centre lies inside the node's rect** —
     *   `left <= x < right` and `top <= y < bottom`, `android.graphics.Rect`'s own
     *   half-open convention, so a centre exactly on the near edge is inside and
     *   one on the far edge is outside. Matching on the centre (rather than on
     *   overlap) is #106's sub-rule for a reason: a box straddling a node's edge
     *   is a detection fragment at best, and OCR is the safe answer for it.
     * - **The smallest node wins** a contested centre — the most specific rect,
     *   so the inner span answers for the box its neighbours merely overlap. Two
     *   nodes of equal area are equally specific, and the caller's tree order
     *   decides, never a hash order.
     * - **Only a node that can answer is considered**: it must have text and a
     *   positive-area rect that intersects [window]. The intersection is what #106
     *   asks for rather than containment — a paragraph scrolled half off the top
     *   of the screen is still visible and still answers for what is drawn — and
     *   it is also the cheapest of #106's three guards against a node that is in
     *   the tree but not on the screen (a stale virtual node, a collapsed view).
     * - **Boxes are never filtered.** [window] gates the tree, not the detector:
     *   every box keeps its place in the result, so with no nodes this returns
     *   [recognise] == every box index and the capture is recognised exactly as it
     *   is today.
     *
     * @param boxes the detector's boxes, in bitmap coordinates.
     * @param nodes the visible, Japanese-bearing leaf text nodes, in tree order.
     * @param window the active window's visible rect, in the same coordinates.
     */
    fun plan(boxes: List<JpDictRect>, nodes: List<ScreenTextNode>, window: JpDictRect): ScreenTextRouting {
        val answering = nodes.indices.filter { canAnswer(nodes[it], window) }

        // Box → node, in box order. A node may be written several times: a
        // paragraph is one node and several detected lines.
        val nodePaid = LinkedHashMap<Int, Int>()
        for ((boxIndex, box) in boxes.withIndex()) {
            val owner = mostSpecific(answering, nodes, box) ?: continue
            nodePaid[boxIndex] = owner
        }

        // A node no box's centre fell inside. Reported by index into [nodes], so
        // the caller can apply the ink-sample guard to just these.
        val paidNodes = nodePaid.values.toHashSet()
        val recovered = answering.filter { it !in paidNodes }
        val recognise = boxes.indices.filter { it !in nodePaid }
        return ScreenTextRouting(nodePaid, recovered, recognise)
    }

    /**
     * The node may answer at all: it has a string to draw with, its rect has area,
     * and it intersects the visible window. A node failing any of these is not
     * reported as recovered either — there would be nothing to lay its text out
     * in, and recovering it would put an undrawable line in the overlay.
     */
    private fun canAnswer(node: ScreenTextNode, window: JpDictRect): Boolean =
        node.text.isNotEmpty() && hasArea(node.rect) && intersects(node.rect, window)

    /**
     * The node that pays for [box]: the smallest node rect containing the box's
     * centre. [candidates] is ascending, so the first of two equal areas wins.
     */
    private fun mostSpecific(
        candidates: List<Int>,
        nodes: List<ScreenTextNode>,
        box: JpDictRect,
    ): Int? {
        var best: Int? = null
        var bestArea = Long.MAX_VALUE
        for (index in candidates) {
            if (!centreInside(nodes[index].rect, box)) continue
            val area = area(nodes[index].rect)
            if (area < bestArea) {
                best = index
                bestArea = area
            }
        }
        return best
    }

    /**
     * True when [box]'s centre lies inside [rect] — half-open on the right and
     * bottom, as `android.graphics.Rect.contains` is, and the rule #106's
     * "match on the box's centre" is stated in. The centre is [box]'s own
     * integer one (`JpDictRect.centerX/Y`), so an odd-sized box is judged by the
     * pixel its rect calls the middle rather than by a float.
     */
    private fun centreInside(rect: JpDictRect, box: JpDictRect): Boolean =
        rect.left <= box.centerX() && box.centerX() < rect.right &&
            rect.top <= box.centerY() && box.centerY() < rect.bottom

    /** Rect overlap. A zero-area rect therefore never overlaps anything. */
    private fun intersects(a: JpDictRect, b: JpDictRect): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    /** Rect area, widened to [Long] so a whole-screen rect cannot overflow. */
    private fun area(rect: JpDictRect): Long = rect.width().toLong() * rect.height().toLong()

    private fun hasArea(rect: JpDictRect): Boolean = rect.width() > 0 && rect.height() > 0

    // ── character geometry ─────────────────────────────────────────────────

    /**
     * The visual rows of a node: the detected boxes that fall inside [nodeRect],
     * clipped to it and ordered top to bottom.
     *
     * This is the paragraph case the detector already gets right for us — one
     * node holding three lines of prose arrives as three per-line boxes, and
     * those boxes are where the text's own line breaks are. Passing the page's
     * whole box list is the intended call; the filter is applied here so the rule
     * lives in one place instead of once per caller.
     *
     * Rows are clipped to [nodeRect] (#106's "clip to the node's visible rect"):
     * a detected box may contain the node's centre while still hanging over its
     * edge, and a character placed on the overhang would be drawn — and tappable
     * — outside the text's own rect. Boxes with no area after the clip are
     * dropped, and a node with no rows yields an empty list, which [charBoxes]
     * reads as "this node is its own single row".
     */
    fun visualRows(nodeRect: JpDictRect, lineBoxes: List<JpDictRect>): List<JpDictRect> =
        lineBoxes.mapNotNull { box ->
            if (!centreInside(nodeRect, box)) return@mapNotNull null
            val clipped = clip(box, nodeRect)
            if (hasArea(clipped)) clipped else null
        }.sortedWith(compareBy({ it.top }, { it.left }))

    /**
     * One [JpDictRect] per **visible** character of [text], in reading order —
     * the [LineResult.charBoxes] the overlay draws and hit-tests a node's text
     * with, measured from the node's own string instead of from a recogniser's
     * timesteps.
     *
     * Whitespace has no box (it is not drawn and not tappable); use [charBoxesAt]
     * when the caller needs the boxes keyed by character index. The vertical
     * extent of every box is its row's full height, so the overlay scales each
     * glyph to the line the same way it does for a recognised line.
     *
     * The layout, in order:
     *
     * 1. **Rows.** [visualRows] of [lineBoxes] when the detector found any boxes
     *    in the node, else [nodeRect] as a single row. A node with no room at all
     *    produces nothing.
     * 2. **One scale for the whole string.** Every non-line-break character takes
     *    its class's extent ([FULLWIDTH_EXTENT] or [HALFWIDTH_EXTENT], whitespace
     *    included — a space is a glyph that happens to be blank), and the scale
     *    that makes the whole string exactly fill the rows is
     *    `pxPerEm = Σ row widths / Σ character extents`. That is what "allocate
     *    proportionally to each row's width" means once the string is known: a row
     *    is full at `rowWidth / pxPerEm` em, so a wide row takes more characters
     *    than a narrow one and a line of kanji is not handed the characters a
     *    line of halfwidth latin would take.
     * 3. **Rows fill in reading order**, closing when the next character would
     *    cross the row's width, and closing early at a **line break** in [text]
     *    whatever the arithmetic would have said. A character is never split
     *    across two rows and never dropped or duplicated: whichever row is
     *    current takes it, and the **last** row absorbs whatever is left rather
     *    than discarding it (a node whose text is longer than its geometry gets
     *    narrower characters, not missing ones).
     * 4. **Within a row**, characters take the row's width in proportion to their
     *    own extents, left to right. The edges are rounded to whole pixels, so a
     *    row of five characters over 100 px gives five 20 px boxes.
     *
     * Every box therefore lies inside its row, hence inside [nodeRect], and the
     * boxes come out ordered left to right within a row and top to bottom across
     * rows. Degenerate input — an empty string, a zero-width or zero-height node
     * rect, a text of nothing but line breaks, more rows than characters —
     * produces an empty list rather than throwing.
     *
     * **Horizontal (yoko) text only.** A row is read left to right, which is what
     * the browser and reader text #106 is aimed at draws as; a vertical (tate)
     * node's detected boxes are tall narrow columns and would come out wrong, so
     * the caller decides the orientation and a vertical node does not come here.
     *
     * @param text the node's own string, as the tree reported it.
     * @param nodeRect the rect the text is drawn in.
     * @param lineBoxes the detected boxes to read visual rows from; the page's
     *   whole list is fine.
     * @param isFullWidth the width class of a character; [isFullWidth] by default,
     *   and the caller may pass the app's own classification.
     */
    fun charBoxes(
        text: String,
        nodeRect: JpDictRect,
        lineBoxes: List<JpDictRect> = emptyList(),
        isFullWidth: (Char) -> Boolean = ::isFullWidth,
    ): List<JpDictRect> = charBoxesAt(text, nodeRect, lineBoxes, isFullWidth).filterNotNull()

    /**
     * The same layout as [charBoxes], keyed by character index: one entry per
     * character of [text], `null` where the character is whitespace and so has no
     * box.
     *
     * This is the view the caller wants for `LineResult.charBoxes`, which is
     * **positionally aligned with the line's text** — the overlay reads
     * `charBoxes[i]` for `text[i]` to draw a glyph and to resolve a tap. A node's
     * text can contain spaces and newlines where a recognised line's usually does
     * not, so the boxes alone cannot be handed over without re-deriving where the
     * gaps went; this list cannot be mis-spliced. The caller substitutes the
     * repo's zero-area fallback (`JpDictRect(0, 0, 0, 0)`, as
     * `OcrOverlayStateController` already uses for a missing box) for each `null`:
     * a zero-area box is drawn as nothing and hit-tests as nothing, which is what
     * "whitespace is not tappable" means on the overlay's side.
     */
    fun charBoxesAt(
        text: String,
        nodeRect: JpDictRect,
        lineBoxes: List<JpDictRect> = emptyList(),
        isFullWidth: (Char) -> Boolean = ::isFullWidth,
        advanceOf: ((Char) -> Float)? = null,
    ): List<JpDictRect?> {
        if (text.isEmpty()) return emptyList()
        val rows = visualRows(nodeRect, lineBoxes).ifEmpty { singleRow(nodeRect) }
        if (rows.isEmpty()) return List(text.length) { null }
        if (advanceOf != null) return measuredBoxes(text, rows, advanceOf)

        // Step 2's arithmetic, extracted so the walk below reads as "which row is
        // this character in" and nothing else.
        val extents = FloatArray(text.length) { extentOf(text[it], isFullWidth) }
        val totalExtent = extents.sum()
        if (totalExtent <= 0f) return List(text.length) { null } // nothing but line breaks
        val pxPerEm = rows.sumOf { it.width() } / totalExtent

        // Step 3: hand each character to a row. `rowChars[row]` holds character
        // indices, so the result is index-addressable for charBoxesAt.
        val lastRow = rows.size - 1
        var row = 0
        var rowExtent = 0f
        val rowChars = Array(rows.size) { ArrayList<Int>() }
        for (i in text.indices) {
            val ch = text[i]
            if (isLineBreak(ch)) {
                // A line break closes the row it lands in and starts the next one,
                // whatever the proportional walk would have done.
                if (row < lastRow) {
                    row++
                    rowExtent = 0f
                }
                continue
            }
            val extent = extents[i]
            if (row < lastRow && !fitsInRow(rowExtent, extent, rows[row].width(), pxPerEm)) {
                row++
                rowExtent = 0f
            }
            rowChars[row].add(i)
            rowExtent += extent
        }

        // Step 4: within each row, in proportion to the extents of the characters
        // that row actually got.
        val out = arrayOfNulls<JpDictRect>(text.length)
        for (r in 0..lastRow) {
            val chars = rowChars[r]
            if (chars.isEmpty()) continue
            var rowExtentSoFar = 0f
            var totalForRow = 0f
            for (i in chars) totalForRow += extents[i]
            var prevEdge = rows[r].left
            for (i in chars) {
                rowExtentSoFar += extents[i]
                val edge = (rows[r].left + (rows[r].width() * rowExtentSoFar / totalForRow))
                    .roundToInt().coerceIn(rows[r].left, rows[r].right)
                if (hasInk(text[i])) out[i] = JpDictRect(prevEdge, rows[r].top, edge, rows[r].bottom)
                prevEdge = edge
            }
        }
        return out.toList()
    }

    /**
     * #106: the boxes a node's text is drawn with when the caller can **measure the
     * font** — which the overlay can, because it draws those glyphs itself.
     *
     * The extent model above is a guess: every character is a full or half em, and
     * one global scale stretches the string to fill the rows' total width. On the
     * device that showed up as the maintainer reported: character and inter-word
     * spacing "weird and inconsistent" in node-backed lines. Two reasons, both
     * fixed by measuring instead:
     *
     *  - the scale is shared by the whole node, so any mismatch between how much
     *    text there is and how wide the detected rows are (a trailing space in the
     *    box, an ink-hugging box narrower than the advances, two rows of different
     *    heights) rescales *every* glyph — and differently for every node;
     *  - fullwidth/halfwidth is not what a proportional face does, and `LineOverlayView`
     *    then fits each glyph into the box it was handed, so a wrong box makes a
     *    wrong size next to its neighbour.
     *
     * With real advances each character takes exactly the width the face gives it at
     * the size that line is drawn at, so spacing is the font's own and is identical
     * from line to line; a row that runs out wraps to the next, and a row that the
     * text does not fill stays unfilled instead of stretching.
     */
    private fun measuredBoxes(
        text: String,
        rows: List<JpDictRect>,
        advanceOf: (Char) -> Float,
    ): List<JpDictRect?> {
        val out = arrayOfNulls<JpDictRect>(text.length)
        val lastRow = rows.size - 1
        var row = 0
        var x = rows[0].left.toFloat()
        for (i in text.indices) {
            val ch = text[i]
            if (isLineBreak(ch)) {
                if (row < lastRow) row++
                x = rows[row].left.toFloat()
                continue
            }
            val advance = advanceOf(ch)
            // Wrap by the same rule the extent model uses: never split a character,
            // and an empty row always takes the next one (even one wider than the row).
            if (row < lastRow && x > rows[row].left && x + advance > rows[row].right) {
                row++
                x = rows[row].left.toFloat()
            }
            if (hasInk(ch)) {
                out[i] = JpDictRect(
                    x.roundToInt(),
                    rows[row].top,
                    (x + advance).roundToInt().coerceIn(rows[row].left, rows[row].right),
                    rows[row].bottom,
                )
            }
            x += advance
        }
        return out.toList()
    }

    /**
     * The node's own rect as the only row, or no rows at all when it has no area —
     * a node with nowhere to put a character must produce no boxes, not a
     * division by zero.
     */
    private fun singleRow(nodeRect: JpDictRect): List<JpDictRect> =
        if (hasArea(nodeRect)) listOf(nodeRect) else emptyList()

    /**
     * A character's advance: [FULLWIDTH_EXTENT] or [HALFWIDTH_EXTENT] by class, and
     * **0 for a line break**, which is not a glyph — it consumes no width, it
     * consumes a row.
     */
    private fun extentOf(ch: Char, isFullWidth: (Char) -> Boolean): Float = when {
        isLineBreak(ch) -> 0f
        isFullWidth(ch) -> FULLWIDTH_EXTENT
        else -> HALFWIDTH_EXTENT
    }

    /** The only hard line break this treats as one. A `\r` is whitespace like any
     *  other — extent, no box, no break — so a `\r\n` pair costs one row. */
    private fun isLineBreak(ch: Char): Boolean = ch == '\n'

    /**
     * True for a character that is drawn and can be tapped. `Char.isWhitespace`
     * covers the ASCII breaks, the ideographic space and every other Unicode
     * space character, so a Japanese paragraph's ideographic spaces are skipped
     * with its ASCII ones.
     */
    private fun hasInk(ch: Char): Boolean = !ch.isWhitespace()

    /**
     * Whether a character of [charExtent] em still fits in a row [rowWidthPx]
     * wide at [pxPerEm], once the row has taken [rowExtent] em.
     *
     * A character is never split, so the test is `>`: a row that has taken
     * **nothing** yet always takes the character, even one wider than the row —
     * the alternative is a character with no box at all, which is worse than one
     * too wide for its row.
     */
    private fun fitsInRow(rowExtent: Float, charExtent: Float, rowWidthPx: Int, pxPerEm: Float): Boolean =
        (rowExtent + charExtent) * pxPerEm <= rowWidthPx || rowExtent <= 0f

    /** [box] as far as it lies inside [bounds] — the row-clipping rule. */
    private fun clip(box: JpDictRect, bounds: JpDictRect): JpDictRect = JpDictRect(
        box.left.coerceAtLeast(bounds.left),
        box.top.coerceAtLeast(bounds.top),
        box.right.coerceAtMost(bounds.right),
        box.bottom.coerceAtMost(bounds.bottom),
    )

    /**
     * #106: [text] with unpaired surrogates replaced by U+FFFD.
     *
     * An app's own text can be malformed UTF-16: a lone surrogate left by a broken
     * emoji, a truncated pair in a scrolled virtual node. That is legal in a Java
     * `String` and *impossible* to encode as UTF-8, so handing it to the engine
     * throws `MalformedInputException: Input length = 1` out of the UniFFI string
     * converter — a crash on the next dictionary lookup, reported from a capture
     * whose screen-reader text contained one. The OCR path cannot produce this (its
     * characters come from a fixed table); the tree path is the one that forwards
     * whatever the app published, so it is sanitised here, at the boundary.
     *
     * Substitution is one-for-one, so every index still means what it meant: the
     * character boxes and the text stay aligned, and a highlight or an override
     * cannot land on the wrong glyph. Valid pairs are untouched.
     */
    fun wellFormed(text: String): String {
        var i = 0
        var out: StringBuilder? = null
        while (i < text.length) {
            val c = text[i]
            val pair = Character.isHighSurrogate(c) &&
                i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
            val lone = (Character.isHighSurrogate(c) && !pair) || Character.isLowSurrogate(c)
            if (lone) {
                if (out == null) out = StringBuilder(text.length).append(text, 0, i)
                out.append('\uFFFD')
                i++
            } else {
                out?.append(c)
                if (pair) {
                    out?.append(text[i + 1])
                    i += 2
                } else {
                    i++
                }
            }
        }
        return out?.toString() ?: text
    }

    /**
     * #106: the widest a fullwidth glyph may be assumed to be, as a fraction of its
     * row's height, before a node's text is judged **not drawn**.
     *
     * The overlay draws a node's text at `0.90 × the row height`, and the detector's
     * boxes hug the ink, so a line of Japanese has roughly `rowWidth / (0.9 ×
     * rowHeight)` characters in it: this is the estimate of *how much text the rect
     * can be showing*, and the whole elision test rests on it.
     *
     * It was 0.55 at first, on the reasoning that a wrong *drop* costs only a slower
     * capture — and the device showed the other edge: a collapsed notification's
     * text is often only twice what it draws, so a bound with 1.6x of slack let
     * almost every elided node through (`elided=2 … 6` against dozens of affected
     * nodes) and their strings were still laid over all of their lines. At 0.85 the
     * tolerance is ~1.2x, which drops the elided ones and still clears honest dense
     * text.
     */
    const val MIN_EM_RATIO = 0.85f

    /**
     * #106: slack on [fits]'s capacity, so a line that exactly fills its row is not
     * read as not-fitting by rounding.
     */
    const val FIT_SLACK = 1.05f

    /**
     * #106: what share of [box]'s area lies inside [rect], 0..1.
     *
     * The routing uses it to keep recognition off a box that an answering node
     * already covers: two boxes over one line (a rotated and an axis-aligned
     * detection, a node whose rect covers the line but not its centre) used to give
     * the line both a tree line and an OCR line, drawn on top of each other.
     */
    fun overlapShare(box: JpDictRect, rect: JpDictRect): Float {
        val w = minOf(box.right, rect.right) - maxOf(box.left, rect.left)
        val h = minOf(box.bottom, rect.bottom) - maxOf(box.top, rect.top)
        if (w <= 0 || h <= 0) return 0f
        val boxArea = box.width().toLong() * box.height()
        if (boxArea <= 0) return 0f
        return (w.toLong() * h).toFloat() / boxArea
    }

    /**
     * #106: whether [text] can plausibly be the text **drawn** in [nodeRect].
     *
     * An app may elide: a collapsed notification's node carries the whole
     * notification while the card draws its first two lines and an ellipsis, and a
     * scrolled page's node can carry more text than its rect shows. Laying that
     * string out over the rect squeezes hundreds of glyphs into two lines — the
     * maintainer's "many lines on top of each other" in the notification shade — and
     * every lookup then answers with characters that are not on screen.
     *
     * So a node whose text cannot fit does not answer at all: its boxes go to
     * recognition, which reads exactly the characters that *are* drawn (ellipsis and
     * all, since OCR only ever sees pixels). That is the safe direction — a wrong
     * answer is worse than a slower one — and it is why the bound is loose.
     *
     * Rows are [visualRows] (the detected boxes inside the node) and the text is
     * walked through them in order, breaking where it has newlines.
     *
     * **With no detected box inside, the node's own rect is the one row to measure
     * against.** It used to return "fits" in that case, on the reasoning that there
     * was no evidence either way — and the maintainer found the hole it left: a
     * small box whose node carries an entire notification's detail has no detected
     * box inside it (there is nothing small enough to detect there), so it was kept
     * and its hundreds of characters were squashed into the box. The rect is
     * evidence: a box that size cannot be showing that much text.
     */
    fun visiblePrefix(
        text: String,
        nodeRect: JpDictRect,
        lineBoxes: List<JpDictRect>,
        isFullWidth: (Char) -> Boolean = ::isFullWidth,
    ): String {
        // The node's own rect as a single row when the detector saw nothing inside it.
        val rows = visualRows(nodeRect, lineBoxes).ifEmpty { listOf(nodeRect) }
        var row = 0
        var used = 0f
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '\n') {
                row++
                used = 0f
                if (row >= rows.size) {
                    // More visual lines than the node has rows: only a trailing
                    // newline may run past the last one.
                    return if (text.substring(i + 1).isBlank()) text else text.substring(0, i)
                }
                continue
            }
            val extent = if (isFullWidth(ch)) 1f else 0.5f
            val capacity = rows[row].width().toFloat() /
                (MIN_EM_RATIO * rows[row].height().coerceAtLeast(1))
            if (used + extent > capacity * FIT_SLACK) return text.substring(0, i)
            used += extent
        }
        return text
    }

    /**
     * #106: whether [text] can plausibly be the text **drawn** in [nodeRect] — that
     * is, whether [visiblePrefix] returns all of it.
     *
     * An app may elide: a collapsed notification's node carries the whole
     * notification while the card draws its first two lines and an ellipsis, and a
     * scrolled page's node can carry more text than its rect shows. Laying that
     * string out over the rect squeezes hundreds of glyphs into two lines — the
     * maintainer's "many lines on top of each other" in the notification shade.
     *
     * A node that fails this is refused **when the detector has a box inside it** to
     * hand the region to: recognition reads exactly the pixels that are drawn,
     * ellipsis and all. When the detector found no box inside it, refusing would
     * leave that region with no source at all, so such a node answers with
     * [visiblePrefix] — for an ellipsis the visible part *is* the prefix of the
     * string, and showing that beats showing nothing.
     */
    fun fits(
        text: String,
        nodeRect: JpDictRect,
        lineBoxes: List<JpDictRect>,
        isFullWidth: (Char) -> Boolean = ::isFullWidth,
    ): Boolean = visiblePrefix(text, nodeRect, lineBoxes, isFullWidth) == text
}