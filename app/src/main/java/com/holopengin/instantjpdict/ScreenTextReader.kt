package com.holopengin.instantjpdict

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * #106: the active accessibility window's **visible, Japanese-bearing leaf text**,
 * read as [ScreenTextNode]s in the screenshot's own pixel coordinates.
 *
 * This is the half of #106 that needs the platform. It filters the tree down to
 * what it may answer with and hands the result to [ScreenTextPlan], which does
 * the routing as pure arithmetic; everything the reader decides here is a
 * question only an `AccessibilityNodeInfo` can answer.
 *
 * ## The four filters
 *
 * 1. **`text`, not `contentDescription`** and not blank. The ticket's rule is the
 *    node's own string — an accessibility *label* is often not the drawn text
 *    (an icon's description, a button's name), and answering with one would put
 *    characters on the screen that are not there.
 * 2. **[isVisibleToUser]** — the cheapest of #106's three guards against a node
 *    that is in the tree but not on it. A `GONE`/`INVISIBLE` view, or one behind
 *    another window, is not drawn and must not be looked up as if it were.
 * 3. **A rect that intersects the screenshot**, clipped to it. #106 asks for
 *    intersection rather than containment (a paragraph scrolled half off the top
 *    is still visible and still answers for what is drawn) and for the clip
 *    (#106's "clip to the node's visible rect"): a node that runs off the edge of
 *    the bitmap must not place character boxes outside it. [window] is the
 *    screenshot's own bounds — screen coordinates *are* bitmap coordinates here,
 *    which is the same identity the detector's boxes already assume.
 * 4. **[carriesJapanese]** — an English page publishes text nodes too, and the
 *    tree must not answer for it.
 *
 * ## Duplicate text: one node per string, the leaf wins
 *
 * A container frequently repeats its child's exact text (a `TextView` whose
 * parent reports the same string, a WebView wrapper around its content node).
 * Both would answer, and the same string would be drawn twice in the overlay, so
 * **the descendant wins and the ancestor is dropped**: the ticket's sub-rule is
 * leaf text nodes only — "a container's rect spans content we did not verify" —
 * and the leaf is the rect the text is actually drawn in, which is also the rect
 * the routing's smallest-area-wins tie-break wants. The *concatenated* case is a
 * different animal and is **not** handled: a container whose text is its
 * children's text run together ("吾輩は猫である。名前はまだ無い。") is not an exact
 * duplicate of either child, so it survives alongside them. What keeps that from
 * misbehaving is downstream, not here — the plan's smallest-area-wins gives each
 * detected box's centre to the most specific rect inside it (the child), and a
 * container that no box covers can only become a line through `recovered`, where
 * the ink sample in [ScreenTextInk] drops it if its rect is blank on screen.
 * That is a v1 mitigation, not a fix: **watch this on the device** in a browser or
 * an e-reader, where a WebView's virtual view hierarchy is the likeliest source.
 *
 * ## Bounded, and it cannot throw out of a capture
 *
 * A window's tree is unbounded in principle (a long article, a chat history), so
 * the walk stops at [MAX_NODES] candidate nodes and [MAX_VISITED] visited nodes
 * and returns what it has; the routing then sees a shorter list and recognises
 * the rest, which is the no-nodes path. Both bounds are bounds, not heuristics —
 * nothing about the answer changes for a tree that fits under them.
 *
 * Every failure mode is contained: a stale node throws from almost any accessor,
 * so each node is read inside its own [Throwable] guard and the walk carries on.
 * The caller is an overlay capture that is already holding a screenshot, and
 * losing the whole capture to one unreadable node would be strictly worse than
 * losing that node.
 *
 * `AccessibilityNodeInfo` objects are **recycled** (minSdk 30 requires it; from
 * API 33 the platform recycles them itself and the call is deprecated). Every node
 * this walk obtains is recycled in a `finally`, and the ones still queued when a
 * bound stops the walk are drained — an accessibility service that walks a tree
 * per capture and keeps the nodes leaks platform objects at capture rate.
 */
object ScreenTextReader {

    private const val TAG = "ScreenTextReader"

    /**
     * #106: the package whose window the last [read] walked, or null.
     *
     * **Log-only.** #106's split line has to name the app it is talking about —
     * "boxes=12 node_paid=0" means something quite different in Chrome and in a
     * game — and the tree is the only place that package is known. Set at the top
     * of every [read] (to null when there is no root, so a failed walk cannot
     * report the previous capture's app), read by the caller when it logs the
     * split. A capture is one overlay at a time and the read and its log are
     * milliseconds apart on the same thread, so the race that would make this
     * wrong costs nothing that a log line is worth.
     */
    @Volatile
    var lastPackage: String? = null
        private set

    /**
     * #106: the most candidate nodes one capture keeps.
     *
     * Bounds the per-character work downstream (`charBoxesAt` is O(characters)
     * per node, and every character is one UniFFI width crossing), not the answer:
     * a page with more visible Japanese nodes than this is past the point where
     * the overlay is usable as a lookup surface anyway.
     */
    const val MAX_NODES = 512

    /**
     * #106: the most nodes the walk **visits**, candidate or not.
     *
     * A WebView's virtual hierarchy is mostly non-text structure, so this is the
     * bound that actually stops a pathological page.
     */
    const val MAX_VISITED = 4096

    /**
     * #106: the longest node text kept, in characters.
     *
     * A whole-page container node is the case this drops: its rect spans
     * everything, so it would answer for boxes anywhere on the screen with one
     * enormous string, and the boxes it does not cover would be recovered as a
     * single line the width of the page. Dropping it sends those boxes to
     * recognition, which is the no-nodes behaviour.
     */
    const val MAX_TEXT = 1000

    /**
     * The visible, Japanese-bearing text nodes of [root]'s subtree, in tree order,
     * with their rects clipped to [window]. Empty for a null root.
     *
     * Never throws: see the class doc.
     */
    fun read(root: AccessibilityNodeInfo?, window: JpDictRect): List<ScreenTextNode> {
        lastPackage = try {
            root?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
        if (root == null) return emptyList()

        // Parallel lists, so the ancestor test below is index arithmetic rather
        // than a second structure to keep in step.
        val texts = ArrayList<String>()
        val rects = ArrayList<JpDictRect>()
        /** For each candidate, the index of its nearest candidate ancestor, or -1. */
        val parents = ArrayList<Int>()

        val pending = ArrayDeque<Pending>()
        pending.addLast(Pending(root, -1))
        var visited = 0
        while (pending.isNotEmpty() && texts.size < MAX_NODES && visited < MAX_VISITED) {
            val frame = pending.removeLast()
            // What a child of this node inherits: this node's own index when it
            // became a candidate, its parent's otherwise (non-candidates are
            // transparent to the duplicate test, as they are to the tree).
            var self = frame.parentCandidate
            try {
                visited++
                val candidate = candidateOf(frame.node, window)
                if (candidate != null) {
                    self = texts.size
                    texts.add(candidate.text)
                    rects.add(candidate.rect)
                    parents.add(frame.parentCandidate)
                }
                val childCount = frame.node.childCount
                for (i in 0 until childCount) {
                    val child = frame.node.getChild(i) ?: continue
                    pending.addLast(Pending(child, self))
                }
            } catch (t: Throwable) {
                // A node goes stale under us (the app behind it went away, the
                // window closed) and every accessor on it can throw. One dead node
                // must not cost the capture its overlay.
                Log.w(TAG, "node unreadable; continuing the walk without it", t)
            } finally {
                recycle(frame.node)
            }
        }
        // Whatever a bound left queued was obtained by getChild and is ours.
        while (pending.isNotEmpty()) recycle(pending.removeLast().node)

        val keep = dropRepeatedAncestors(texts, parents)
        return keep.map { ScreenTextNode(texts[it], rects[it]) }
    }

    /**
     * #106: the indices of the nodes no descendant repeats verbatim — the pure
     * half of the duplicate rule, split out so it is pinned by host tests rather
     * than by a page that happens to have a container echoing its child.
     *
     * [parents] is the nearest-candidate-ancestor index of each entry (-1 at a
     * root), as the walk builds it. Walking **up** from each entry marks every
     * ancestor whose text it repeats, so the innermost node carrying a string
     * survives — see the class doc for why the leaf, and what is deliberately left
     * to the routing and the ink sample.
     *
     * Siblings that share a string are both kept: two list rows reading "詳細"
     * are two drawn instances of that word, not one node seen twice, and merging
     * them would put one lookup target where the screen has two.
     */
    internal fun dropRepeatedAncestors(texts: List<String>, parents: List<Int>): List<Int> {
        val repeated = BooleanArray(texts.size)
        for (i in texts.indices) {
            var p = parents[i]
            while (p >= 0) {
                if (texts[p] == texts[i]) {
                    repeated[p] = true
                    break
                }
                p = parents[p]
            }
        }
        return (0 until texts.size).filter { !repeated[it] }
    }

    /**
     * #106: does this text carry Japanese?
     *
     * Two of the app's own character classes, both named constants of
     * [ScreenTextPlan] and both the classes `OcrEngine.isHalfWidth` draws from:
     *
     *  - **fullwidth** ([ScreenTextPlan.isFullWidth]) — kana, kanji, Japanese
     *    punctuation, the ideographic space, and every other non-ASCII character.
     *  - **halfwidth kana** (`HALFWIDTH_KANA_FIRST..HALFWIDTH_KANA_LAST`) — the
     *    `ｶﾀｶﾅ` block. It is Japanese, and the app classifies it *halfwidth*
     *    because that is its advance, so a fullwidth-only test would drop a node
     *    whose text really is Japanese. Rare in modern text and cheap to include,
     *    so it is.
     *
     * Local range checks, not `OcrEngine.isHalfWidth` itself: that is a UniFFI
     * crossing at ~23 us a call, which over a browser page's characters is tens
     * of milliseconds spent on a yes/no answer. The classes are the same ones, and
     * `ScreenTextPlanTest` pins them against the shim.
     *
     * What this admits beyond Japanese, stated plainly because it is a superset:
     * fullwidth latin, Hangul, Cyrillic, emoji — anything non-ASCII. What it
     * excludes is what #106's rule is about: **ASCII**, so an English page, an
     * English URL bar, an English button label never reach the routing. The
     * residue is a node whose characters are still exactly the characters on
     * screen; only the dictionary finds nothing in them, which is the same
     * outcome as recognition misreading them.
     */
    fun carriesJapanese(text: String): Boolean {
        for (i in text.indices) {
            val code = text[i].code
            if (ScreenTextPlan.isFullWidth(text[i])) return true
            if (code in ScreenTextPlan.HALFWIDTH_KANA_FIRST..ScreenTextPlan.HALFWIDTH_KANA_LAST) return true
        }
        return false
    }

    /** One queued node and the candidate it inherits from. */
    private class Pending(
        val node: AccessibilityNodeInfo,
        val parentCandidate: Int,
    )

    /**
     * This node's answer, or null when it must not answer: see the class doc's
     * four filters.
     */
    private fun candidateOf(node: AccessibilityNodeInfo, window: JpDictRect): ScreenTextNode? {
        val text = node.text?.toString() ?: return null
        if (text.isBlank() || text.length > MAX_TEXT) return null
        if (!carriesJapanese(text)) return null
        // A method, not a property: `AccessibilityNodeInfo.isVisibleToUser()` has
        // no `get`/`is` bean convention Kotlin can synthesise from.
        if (!node.isVisibleToUser()) return null
        // `getBoundsInScreen` fills the rect and returns void from API 34 (it used
        // to return whether the node had a bounds at all), so the emptiness of the
        // rect is the test — which the clip below makes anyway.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val rect = clip(bounds.toJpDictRect(), window)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return ScreenTextNode(text, rect)
    }

    /** [rect] as far as it lies inside [window] — #106's "clip to the visible rect". */
    private fun clip(rect: JpDictRect, window: JpDictRect): JpDictRect = JpDictRect(
        maxOf(rect.left, window.left),
        maxOf(rect.top, window.top),
        minOf(rect.right, window.right),
        minOf(rect.bottom, window.bottom),
    )

    /**
     * minSdk 30 requires a walked node to be recycled; from API 33 the platform
     * recycles them itself and the call is deprecated, so it is skipped there
     * rather than suppressed away everywhere.
     */
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        try {
            node.recycle()
        } catch (_: Throwable) {
        }
    }
}
