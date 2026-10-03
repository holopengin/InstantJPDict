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
 * ## The filters
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
 * 4. **A length cap** ([MAX_TEXT]) — see the note there.
 *
 * There is deliberately **no language filter**. It used to require Japanese and
 * that read, on device, as "the tree path barely finds anything": a page whose
 * Japanese sits among English answered with a handful of nodes. The tree is the
 * authority on what is drawn, so an English node is kept too — it looks up to
 * nothing, which is the same outcome as a misread, and the routing (a leaf's own
 * rect, smallest wins) is what keeps a node from claiming boxes that are not its
 * own. [carriesJapanese] stays as the log's Japanese/other split.
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
     * #106: log-only walk counters, all reset at the top of every [read].
     *
     * They exist because "the tree path only picks up a few texts" is a report
     * that needs a number to act on: [lastTextNodes] is how much text the window
     * published at all, [lastDroppedLong] how much of it the length cap removed
     * (a novel page is commonly ONE node longer than [MAX_TEXT]), and
     * [lastJapanese] how many of the nodes that answered actually carry Japanese —
     * the split that says whether a low count is the app's text or the cap.
     */
    @Volatile
    var lastTextNodes: Int = 0
        private set

    /** #106: the nodes that answered and carry Japanese; log-only. */
    @Volatile
    var lastJapanese: Int = 0
        private set

    /** #106: text-bearing nodes the length cap [MAX_TEXT] removed; log-only. */
    @Volatile
    var lastDroppedLong: Int = 0
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
     * One entry of the service's window list, as [windowsToTry] needs it.
     *
     * [area] is the window's own bounds area, read from the window
     * (`getBoundsInScreen`) rather than from its root, so ordering costs no walk.
     */
    data class WindowCandidate(val isActive: Boolean, val packageName: String?, val area: Long)

    /**
     * #106: the package of the system's own chrome. Its windows are windows like
     * any other, and one of them — the shade — reports the whole screen while it
     * draws a strip; on the maintainer's device it answered before the app did.
     */
    const val SYSTEM_CHROME_PACKAGE = "com.android.systemui"

    /**
     * #106: the windows to try for this capture, best first.
     *
     * The accessibility window list cannot be trusted to name the answer, and #106
     * learned that twice on the device. First by `isActive`: by the time this read
     * runs, this app's own overlay window is up and can be the active one (it takes
     * input focus for taps and the manual entry's IME), so `isActive` pointed at us.
     * Then by area: with us excluded, the largest window was **com.android.systemui**
     * — its shade window reports the whole screen while drawing a strip — and the
     * read stopped there, on one five-character node, without ever looking at the
     * app behind it (`screen text: pkg=com.android.systemui … nodes=1`).
     *
     * So the answer is not inferred from the list at all: [preferredPackage] is the
     * package that was in the foreground when the capture was asked for, recorded
     * before this app's overlay window existed. That is the app the user is looking
     * at, and it is tried first. The rest of the order is the fallback for when
     * nothing recorded it (no window content, a race): system chrome last, then the
     * remaining windows largest-first, since the app under the overlay covers the
     * screen while chrome is a strip. The caller walks the order and keeps the first
     * window that actually carries text, so a wrong guess costs one bounded walk.
     *
     * Ours is skipped outright: its rects describe this overlay's drawing, not the
     * screenshot's content, and the boxes have to line up with what was captured. A
     * window with no package to name is skipped with it. When nothing usable is
     * left, the capture recognises everything, which is the pre-#106 path.
     *
     * Pure, so the order is pinned by tests rather than by a device.
     */
    fun windowsToTry(
        candidates: List<WindowCandidate>,
        ownPackage: String,
        preferredPackage: String? = null,
    ): List<Int> {
        val usable = candidates.withIndex().filter {
            val pkg = it.value.packageName
            !pkg.isNullOrEmpty() && pkg != ownPackage
        }
        return usable
            .sortedWith(
                compareBy<IndexedValue<WindowCandidate>> {
                    when (it.value.packageName) {
                        preferredPackage -> 0
                        SYSTEM_CHROME_PACKAGE -> 2
                        else -> 1
                    }
                }.thenByDescending { it.value.area },
            )
            .map { it.index }
    }

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
        lastTextNodes = 0
        lastJapanese = 0
        lastDroppedLong = 0
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
        // #106: and the CONTAINMENT rule, which is what stops a page drawing twice.
        // A reader as common as Kindle publishes a container whose text is its
        // children's text run together; the exact-duplicate rule above does not see
        // it (the strings differ), so both the container and every child became a
        // line and the overlay drew them on top of each other — the maintainer's
        // "many lines on top of each other", with `nodes=5 … node_paid=7` in the
        // split line.
        val candidates = keep.map { ScreenTextNode(texts[it], rects[it]) }
        val survivors = dropContained(candidates).map { keep[it] }
        var japanese = 0
        val out = survivors.map {
            if (carriesJapanese(texts[it])) japanese++
            ScreenTextNode(texts[it], rects[it])
        }
        lastJapanese = japanese
        return out
    }

    /**
     * #106: which nodes survive when one node's rect contains another's — the pure
     * half of the containment rule, host-tested like [dropRepeatedAncestors].
     *
     * A node is drawn as one line laid out over its whole rect, so two answering
     * nodes whose rects overlap are two sets of glyphs on the same pixels. The two
     * ways that arises are told apart by the text, not the geometry:
     *
     * - **The outer node's text carries the inner one's** (a paragraph with a link
     *   inside it, a reader's page container over its per-line children): the outer
     *   node is the fuller truth and is kept, and the inner nodes it carries are
     *   dropped. Its own layout covers their pixels, in order, once.
     * - **It does not** (a card whose own text is a heading while its children carry
     *   the body, a list container whose text is one row): the outer node is
     *   dropped and the inner ones are kept. The alternative — keeping both — is
     *   the overlap; the cost is the outer node's own text, which no longer answers
     *   and goes to recognition like any other uncovered region.
     *
     * Worked outermost-first, so a chain resolves bottom-up: a grandparent whose
     * text carries its whole subtree keeps it whole, and one whose text does not is
     * dropped in favour of its children. Equal rects resolve by input order (one
     * survives; two lines in the same pixels is what this exists to prevent).
     *
     * Returns indices into [nodes], ascending.
     */
    internal fun dropContained(nodes: List<ScreenTextNode>): List<Int> {
        if (nodes.size < 2) return nodes.indices.toList()
        // Outermost first; equal rects resolve to the eariler index (see the KDoc).
        val order = nodes.indices.sortedWith(
            compareByDescending<Int> { nodes[it].rect.width().toLong() * nodes[it].rect.height() }
                .thenByDescending { it },
        )
        val dropped = BooleanArray(nodes.size)

        // Phase 1: an outer node that holds an inner node it does NOT carry would be
        // drawn over that inner node's pixels, so the OUTER one goes. This is the
        // card-with-a-heading case; its own text then goes to recognition with any
        // other uncovered region.
        for (p in order.indices) {
            val outer = order[p]
            val rect = nodes[outer].rect
            val unrelated = (p + 1 until order.size).any { q ->
                val inner = order[q]
                contains(rect, nodes[inner].rect) && !nodes[outer].text.contains(nodes[inner].text)
            }
            if (unrelated) dropped[outer] = true
        }

        // Phase 2: an inner node carried by a surviving outer one is dropped — the
        // outer node lays that whole text out once, in order, over all of it. The
        // reader's page container keeps its page; its per-line children go.
        for (p in order.indices) {
            val outer = order[p]
            if (dropped[outer]) continue
            val rect = nodes[outer].rect
            for (q in p + 1 until order.size) {
                val inner = order[q]
                if (dropped[inner]) continue
                if (contains(rect, nodes[inner].rect) && nodes[outer].text.contains(nodes[inner].text)) {
                    dropped[inner] = true
                }
            }
        }
        return nodes.indices.filter { !dropped[it] }
    }

    /** Whether [outer] encloses [inner] (equal rects count as enclosing). */
    private fun contains(outer: JpDictRect, inner: JpDictRect): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

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
        if (text.isBlank()) return null
        lastTextNodes++
        if (text.length > MAX_TEXT) {
            lastDroppedLong++
            return null
        }
        // #106: NO language filter. It used to require Japanese, and on device that
        // read as "the tree path barely finds anything" — a page whose Japanese sits
        // among English (a bilingual article, an English UI around Japanese content,
        // a reader's chrome) answered with a handful of nodes. The maintainer's call
        // was to include the rest, and the argument for it is that the tree is the
        // authority on what is drawn: an English node is still exactly the text on
        // screen, it just looks up to nothing, the same as a misread would. What
        // keeps that from claiming Japanese boxes is the routing, not this filter —
        // a node pays only for a box whose centre is inside its own (leaf) rect, and
        // the smallest such node wins.
        //
        // `carriesJapanese` survives below as a *log* distinction (a page's
        // Japanese/other split is the first thing a "too few nodes" report wants),
        // not as a gate.
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
