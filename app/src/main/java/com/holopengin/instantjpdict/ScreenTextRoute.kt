package com.holopengin.instantjpdict

/**
 * #106 package 2: the pure half of the Android wiring — turning a [ScreenTextPlan]
 * routing into the box list the overlay installs, and a node's own string into the
 * [LineResult] the overlay draws.
 *
 * No `Bitmap` and no `AccessibilityNodeInfo`, so all of it is pinned by host tests
 * ([ScreenTextRouteTest]) the way package 1's module is. What stays on the Android
 * side is exactly three things, and they are the three that need pixels or the
 * platform: reading and filtering the tree ([ScreenTextReader]), the **ink
 * sample** that guards a recovered node ([ScreenTextInk]), and running the OCR
 * pass over whatever [route] says still recognises.
 */
object ScreenTextRoute {

    /**
     * #106: one capture's box list, and where each of its entries comes from.
     *
     * @property boxes the **augmented** list the overlay draws and installs: every
     *   detected [LineBox], in detection order and unchanged, followed by one
     *   [LineBox] per recovered node at that node's rect. The detected boxes keep
     *   their index and their geometry — a node-paid box is still the detector's
     *   box, it is simply not *recognised* — so the border layer and the refit
     *   path see exactly what they saw before #106.
     * @property recognise the indices of [boxes] that go through recognition, in
     *   the order they are handed to the recogniser. Empty when recognition is
     *   off (the `nodes` mode) and equal to `boxes.indices` when the tree had
     *   nothing to say, which is what makes a game or a photo the pipeline it was.
     * @property nodeAt the indices of [boxes] that carry a **node's** text
     *   instead of a recognition, mapped to that node's index **in the caller's
     *   `nodes` list** — not in the vertical-filtered one the routing worked on.
     *   One entry per node, not per box: a paragraph node pays for several
     *   detected lines and is drawn once, laid over all of them.
     */
    data class Routing(
        val boxes: List<LineBox>,
        val recognise: List<Int>,
        val nodeAt: Map<Int, Int>,
        /** How many entries of [boxes] are the detector's own. */
        val detected: Int,
        /** How many of those [detected] boxes a node paid for. */
        val nodePaid: Int,
        /** How many nodes no box covered and the ink sample kept. */
        val recovered: Int,
        /**
         * #106: how many nodes were refused because their text cannot fit the rect
         * they are drawn in — an elided notification, a scrolled page. Log-only.
         */
        val elided: Int,
    )

    /**
     * #106: route one capture.
     *
     * **An empty node list recognises every box, in every mode.** That is #106
     * rule 5, and it is the property the whole ticket's safety argument rests on:
     * a game, an emulator, a canvas or a photo publishes no text nodes, so every
     * box takes the recognition path and the capture is the pipeline this ticket
     * did not change. It holds under `nodes` mode too — and that is the deliberate
     * reading. "Nodes only" describes what happens where the tree *has* something
     * to say; a screen where it says nothing is not the case the override was set
     * for, and an empty overlay there would read as a broken app rather than as a
     * working mode. `ocr` mode never reaches this at all — the service skips the
     * walk, so the list is empty and this is the same path.
     *
     * [plan] decides; this decides what to do about its answer:
     *
     * - **Recovered nodes are kept only if [inkOk] says the rect is drawn on.**
     *   This is the one place the recovered rule and the ink sample meet, and the
     *   sample can only ever remove a line from the result — it cannot add a box
     *   to [recognise] and it cannot re-route a box a node already paid for.
     * - **[recogniseBoxes] false** is the `nodes` mode: nothing recognises, so a
     *   detected box no node covers is simply not shown. It is the only mode that
     *   drops boxes, and only by choice.
     * - **A node gets one line.** The plan may map several boxes to one node (a
     *   paragraph split into per-line boxes by the detector); the line is installed
     *   at the **first** of them, which is the lowest box index and therefore the
     *   node's position in reading order. The boxes after it stay in [boxes] — the
     *   detector did find lines there, and the border layer draws them — but carry
     *   no result of their own, so the paragraph is drawn once rather than once per
     *   line. Building one [LineResult] per box instead would put the same text in
     *   the page's char stream two or three times over, which the neighbour panel,
     *   the nav graph and the cursor's global index would all read.
     */
    fun route(
        boxes: List<LineBox>,
        nodes: List<ScreenTextNode>,
        window: JpDictRect,
        recogniseBoxes: Boolean,
        isVertical: (JpDictRect) -> Boolean,
        inkOk: (ScreenTextNode) -> Boolean,
        isFullWidth: (Char) -> Boolean = ScreenTextPlan::isFullWidth,
    ): Routing {
        val detectedRects = boxes.map { it.rect }
        val kept = answeringIndices(nodes, detectedRects, isVertical, isFullWidth)
        // #106: log-only — how many nodes were refused because their text cannot fit
        // the rect they are drawn in (an elided notification, a scrolled page). The
        // number that says whether the tree path answered for the page or handed it
        // to recognition.
        val elided = nodes.count {
            !ScreenTextPlan.fits(it.text, it.rect, detectedRects, isFullWidth)
        }
        // Every index below addresses `answering`, and [Routing.nodeAt] is
        // translated back to the CALLER's node list on the way out. The filter
        // renumbers, so carrying a filtered index across would put one node's text
        // under another's rect — the two never agree once anything is filtered.
        val answering = kept.map { nodes[it] }
        val plan = ScreenTextPlan.plan(detectedRects, answering, window)
        val recovered = plan.recovered.filter { inkOk(answering[it]) }

        val augmented = ArrayList<LineBox>(boxes.size + recovered.size)
        augmented.addAll(boxes)
        for (index in recovered) augmented.add(LineBox.of(answering[index].rect))

        val nodeAt = LinkedHashMap<Int, Int>()
        // Box order, and keyed on the NODE rather than the box: a paragraph pays
        // for several boxes and must be installed at one of them, not at each.
        // `putIfAbsent` would not do it — it tests the key, and the keys differ.
        for ((boxIndex, nodeIndex) in plan.nodePaid) {
            if (nodeIndex !in nodeAt.values) nodeAt[boxIndex] = kept[nodeIndex]
        }
        val firstRecovered = boxes.size
        for (k in recovered.indices) nodeAt[firstRecovered + k] = kept[recovered[k]]

        // #106: a box that overlaps an answering node AT ALL does not run
        // recognition. The centre test alone let a box whose centre happens to fall
        // outside the node — a merged or rotated detection spanning the node and its
        // neighbours, a node whose rect sits inside a bigger detected box — be
        // recognised while the node's own line was drawn across the same pixels, so
        // one line showed tree text and OCR text at once. The tree is exact and the
        // detection there is a second opinion about the same ink, so the tree wins
        // that region: the box is suppressed and the node's line covers it.
        val answeringRects = answering.map { it.rect }
        val suppressed = if (answeringRects.isEmpty()) emptyList() else {
            plan.recognise.filter { boxIndex ->
                answeringRects.any { ScreenTextPlan.overlapShare(boxes[boxIndex].rect, it) > 0f }
            }
        }

        return Routing(
            boxes = augmented,
            // Empty node list ⇒ everything recognises, whatever the mode asked for:
            // see the KDoc above. Otherwise the mode decides.
            recognise = if (answering.isEmpty() || recogniseBoxes) {
                plan.recognise.filter { it !in suppressed }
            } else {
                emptyList()
            },
            nodeAt = nodeAt,
            detected = boxes.size,
            nodePaid = plan.nodePaid.size + suppressed.size,
            recovered = recovered.size,
            elided = elided,
        )
    }

    /**
     * #106: the indices of the nodes whose text [ScreenTextPlan.charBoxesAt] can
     * actually lay out — [nodes] minus the **vertical (tate)** ones, as indices
     * into [nodes].
     *
     * Indices and not nodes, because that is the only form the caller can use
     * safely: the plan and the augmented list both address a *filtered* sequence,
     * so any index that has to travel back to the caller's own node list has to be
     * in the caller's space. Returning nodes here and indexing them downstream is
     * the one way to get that wrong.
     *
     * The layout reads a row left to right, which is what the browser and reader
     * text #106 is aimed at draws as. A tate node's detected boxes are tall narrow
     * *columns*; laid out as if they were rows, its characters would each get a box
     * as wide as the column and be stacked side by side across it — every
     * character in the wrong place. So a vertical node does not answer, and its
     * boxes recognise exactly as they did before #106.
     *
     * [isVertical] is the app's own orientation rule (`OcrEngine.isVerticalBox`,
     * the 1.25-aspect test #28/#53 share with the rotated fit), passed in rather
     * than called so this module stays free of the engine and runs in the host
     * tests.
     *
     * Applied to a **node's** rect, not to a character's: for a tate node the whole
     * block is a tall narrow column, and for a yoko one it is wide or roughly
     * square. A single character in a narrow column is genuinely ambiguous — the
     * rule calls it vertical, and OCR then answers for it, which is the safe
     * direction: a wrong answer would put characters where none are, while the
     * fallback is the pipeline this ticket did not change.
     */
    fun answeringIndices(
        nodes: List<ScreenTextNode>,
        boxes: List<JpDictRect>,
        isVertical: (JpDictRect) -> Boolean,
        isFullWidth: (Char) -> Boolean = ScreenTextPlan::isFullWidth,
    ): List<Int> {
        if (nodes.isEmpty()) return emptyList()
        return nodes.indices.filter { i ->
            val node = nodes[i]
            !isVertical(node.rect) &&
                ScreenTextPlan.visualRows(node.rect, boxes).none { isVertical(it) } &&
                // #106: and an elided node does not answer either — its text is not
                // what is drawn. See [ScreenTextPlan.fits].
                ScreenTextPlan.fits(node.text, node.rect, boxes, isFullWidth)
        }
    }

    /**
     * #106: the char boxes a node's own string is drawn and tapped with —
     * `LineResult.charBoxes` for a node-backed line, positionally aligned with the
     * text, with **every whitespace character given a box**.
     *
     * [ScreenTextPlan.charBoxesAt] leaves whitespace `null` (it is not drawn and
     * not tappable), and `LineResult.charBoxes` has no room for a gap: the overlay
     * reads `charBoxes[i]` for `text[i]` — the neighbour chips, the nav graph, the
     * cursor and every tap — so a list that skipped the spaces would misalign
     * everything after the first one. The gap has to be filled, and *where* it is
     * filled matters:
     *
     *  - **Not** `JpDictRect(0, 0, 0, 0)`, which is the repo's fallback for a
     *    *missing* box. A zero-area rect is drawn as nothing and hit-tests as
     *    nothing, which is right, but it sits at the origin — and the page's nav
     *    graph is built from every char box, so each space would add a navigation
     *    node pinned to the top-left corner of the screenshot. Cursor navigation
     *    would then have several hundred indistinguishable positions to choose from.
     *  - Instead the gap collapses to a **zero-width box on the row it is in**, at
     *    the edge of the character beside it. It is still not drawn and still not
     *    tappable (`Rect.contains` is false for an empty rect), it keeps the nav
     *    graph's node where the space actually is, and it leaves the line's own
     *    bounds — which `addLineToResults` derives from the min/max of these boxes
     *    — unchanged.
     *
     * The recognised path never has to make this choice: the recogniser emits a
     * timestep for a space like any other character and [computeCharBoxes] gives it
     * a real box. This is the tree path's equivalent of that, and it is the only
     * place the two disagree about whitespace.
     */
    fun charBoxes(
        text: String,
        nodeRect: JpDictRect,
        boxes: List<JpDictRect> = emptyList(),
        isFullWidth: (Char) -> Boolean = ScreenTextPlan::isFullWidth,
    ): List<JpDictRect> {
        val at = ScreenTextPlan.charBoxesAt(text, nodeRect, boxes, isFullWidth)
        val out = arrayOfNulls<JpDictRect>(at.size)
        for (i in at.indices) out[i] = at[i]
        // Forward: a gap takes the trailing edge of the character before it.
        var anchor: JpDictRect? = null
        for (i in at.indices) {
            val box = at[i]
            if (box != null) {
                anchor = box
                continue
            }
            if (anchor != null) out[i] = JpDictRect(anchor.right, anchor.top, anchor.right, anchor.bottom)
        }
        // Backward: the gaps the forward pass could not place — leading ones,
        // before any character exists — take the leading edge of the character
        // after them. Skipping the ones already placed is the whole point: a
        // second pass would move every interior gap to the *next* character's
        // left edge instead of the previous one's right edge.
        anchor = null
        for (i in at.indices.reversed()) {
            val box = at[i]
            if (box != null) {
                anchor = box
                continue
            }
            if (anchor != null && out[i] == null) {
                out[i] = JpDictRect(anchor.left, anchor.top, anchor.left, anchor.bottom)
            }
        }
        // All whitespace (the reader filters it out, the plan cannot produce it):
        // every gap sits at the node's own left edge.
        for (i in out.indices) {
            if (out[i] == null) out[i] = JpDictRect(nodeRect.left, nodeRect.top, nodeRect.left, nodeRect.bottom)
        }
        return out.map { it!! }
    }
}
