package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #106 package 2: the two decisions of [ScreenTextReader] that are not about the
 * `AccessibilityNodeInfo` API — what counts as Japanese-bearing text, and which
 * node survives when a container repeats its child's string.
 *
 * The walk itself needs a live accessibility tree and is verified on a device;
 * everything it *decides* on the way is here, pinned on the host.
 */
class ScreenTextReaderTest {

    // ── the Japanese predicate ──────────────────────────────────────────────

    @Test
    fun anAsciiOnlyStringDoesNotCarryJapanese() {
        // The case #106's rule exists for: an English page, an English URL bar, an
        // English button label all publish text nodes, and the tree must not
        // answer for them.
        for (text in listOf("", " ", "Hello, world", "Read more", "https://example.com/a/b", "12345")) {
            assertFalse("\"$text\"", ScreenTextReader.carriesJapanese(text))
        }
    }

    @Test
    fun kanaKanjiAndJapanesePunctuationCarryJapanese() {
        for (text in listOf("猫", "吾輩は猫である", "こんにちは", "ｶﾀｶﾅ", "。", "「」", "・", "　")) {
            assertTrue("\"$text\"", ScreenTextReader.carriesJapanese(text))
        }
    }

    @Test
    fun oneJapaneseCharacterInAMostlyAsciiStringIsEnough() {
        // A browser page is ASCII chrome around Japanese content, and the decision
        // is per string, not per line-of-sight: a node whose text is "Read 詳細"
        // is a node about the Japanese word.
        assertTrue(ScreenTextReader.carriesJapanese("Read 詳細"))
        assertTrue(ScreenTextReader.carriesJapanese("第1章"))
        // …and the halfwidth-kana block, which is Japanese in ASCII clothing.
        assertTrue(ScreenTextReader.carriesJapanese("ﾊﾝｶｸ"))
    }

    @Test
    fun thePredicateIsTheAppsOwnCharacterClasses() {
        // Not a private range check written twice: the app's own two classes —
        // fullwidth, and the halfwidth-kana block — so a change to what the
        // overlay draws as halfwidth cannot silently disagree with what the reader
        // calls Japanese. `ScreenTextPlanTest` pins those classes against
        // `OcrEngine.isHalfWidth`.
        for (ch in "A7 あい漢　。") {
            assertEquals(
                "U+%04X".format(ch.code),
                ScreenTextPlan.isFullWidth(ch),
                ScreenTextReader.carriesJapanese(ch.toString()),
            )
        }
        // Halfwidth kana is Japanese and the app classifies it halfwidth for its
        // advance, so it is admitted on the second class rather than the first.
        for (ch in "ｶﾀｶﾅ") {
            assertFalse("U+%04X is halfwidth".format(ch.code), ScreenTextPlan.isFullWidth(ch))
            assertTrue("U+%04X is halfwidth kana".format(ch.code), ScreenTextReader.carriesJapanese(ch.toString()))
        }
    }

    // ── the duplicate rule ──────────────────────────────────────────────────

    /**
     * [ScreenTextReader.dropRepeatedAncestors] takes the walk's two parallel
     * lists: each candidate's text, and the index of its nearest candidate
     * ancestor (-1 at a root). `-1` for a non-candidate parent is what the walk
     * records — non-candidates are transparent to this rule.
     */
    private fun survivors(vararg entries: Pair<String, Int>): List<String> {
        val texts = entries.map { it.first }
        val keep = ScreenTextReader.dropRepeatedAncestors(texts, entries.map { it.second })
        return keep.map { texts[it] }
    }

    @Test
    fun aContainerThatRepeatsItsChildsTextLosesToTheChild() {
        // The case the rule is for: a `TextView` whose parent reports the same
        // string, or a WebView content node wrapped in a node that repeats it.
        // Both would answer, and the overlay would draw the same string twice.
        // The leaf wins — a container's rect spans content we did not verify, and
        // the leaf is the rect the text is actually drawn in.
        assertEquals(listOf("吾輩"), survivors("吾輩" to -1, "吾輩" to 0))
    }

    @Test
    fun onlyTheRepeatingAncestorIsDroppedNotTheWholeSubtree() {
        // A container that echoes one child VERBATIM and holds another: the
        // container goes, and the *other* child stays. Dropping a subtree would
        // lose text the tree can read, which is the one thing #106 must not do.
        // The echo is exact — a concatenated container is a different case, and
        // [aConcatenatedContainerIsNotTreatedAsADuplicate] is the one that stays.
        assertEquals(
            listOf("猫", "名前はまだ無い。"),
            survivors("猫" to -1, "猫" to 0, "名前はまだ無い。" to 0),
        )
    }

    @Test
    fun theSameStringAtTwoPlacesInTheTreeIsKeptTwice() {
        // Two list rows reading "詳細" are two drawn instances of that word, not
        // one node seen twice. Merging them would put a single lookup target where
        // the screen has two, and would shift the neighbour panel's line indices.
        assertEquals(listOf("詳細", "詳細"), survivors("詳細" to -1, "詳細" to -1))
    }

    @Test
    fun aConcatenatedContainerIsNotTreatedAsADuplicate() {
        // The documented v1 risk, pinned so it stays a *known* risk rather than a
        // surprise: a container whose text is its children's text run together is
        // not an exact duplicate of either, so it survives alongside them. What
        // keeps that from misbehaving is downstream — the routing's
        // smallest-area-wins gives each detected box to the most specific rect
        // inside it, and a container no box covers can only become a line through
        // `recovered`, where the ink sample drops it if the rect is blank.
        assertEquals(
            listOf("吾輩は猫である。名前はまだ無い。", "猫", "名前はまだ無い。"),
            survivors(
                "吾輩は猫である。名前はまだ無い。" to -1,
                "猫" to 0,
                "名前はまだ無い。" to 0,
            ),
        )
    }

    @Test
    fun aGrandparentRepeatingTheSameStringGoesToo() {
        // Three levels, all carrying it: only the innermost survives, and the
        // intermediate container goes even though nothing names it directly —
        // the walk-up does not stop at the first ancestor.
        assertEquals(
            listOf("猫"),
            survivors("猫" to -1, "猫" to 0, "猫" to 1),
        )
    }

    @Test
    fun anAncestorWithADifferentStringSurvivesAlongside() {
        // A container with its own text (a heading, a button label) is not a
        // duplicate of anything and must answer for itself.
        assertEquals(
            listOf("吾輩は猫である", "名前はまだ無い。"),
            survivors("吾輩は猫である" to -1, "名前はまだ無い。" to 0),
        )
    }

    @Test
    fun aNonCandidateParentIsTransparentToTheRule() {
        // The walk records -1 for a parent that was not itself a candidate (no
        // text, blank, invisible), and the rule reaches *through* it: a candidate
        // under a rejected container is still compared against the container above
        // that one.
        assertEquals(
            listOf("猫"),
            survivors("猫" to -1, "猫" to 0),
        )
    }

    @Test
    fun nothingInGivesNothingOut() {
        assertEquals(emptyList<String>(), survivors())
    }

    // ── which window answers ────────────────────────────────────────────────

    @Test
    fun theActiveWindowIsReadWhenItIsNotOurs() {
        assertEquals(
            1,
            ScreenTextReader.windowToRead(
                listOf(
                    ScreenTextReader.WindowCandidate(isActive = false, packageName = "com.android.systemui"),
                    ScreenTextReader.WindowCandidate(isActive = true, packageName = "com.android.chrome"),
                ),
                ownPackage = "com.holopengin.instantjpdict.dev",
            ),
        )
    }

    @Test
    fun ourOwnActiveOverlayIsSkippedForTheWindowUnderIt() {
        // The read runs once our overlay is up, and that window takes input focus
        // (taps, the manual entry's IME), so it can be the active one. It is our
        // own drawing rather than the screenshot's content, and the boxes have to
        // line up with what was captured — the window under it.
        assertEquals(
            1,
            ScreenTextReader.windowToRead(
                listOf(
                    ScreenTextReader.WindowCandidate(isActive = true, packageName = "com.holopengin.instantjpdict.dev"),
                    ScreenTextReader.WindowCandidate(isActive = false, packageName = "com.android.chrome"),
                ),
                ownPackage = "com.holopengin.instantjpdict.dev",
            ),
        )
    }

    @Test
    fun aWindowWithNoPackageIsNotRead() {
        assertEquals(
            2,
            ScreenTextReader.windowToRead(
                listOf(
                    ScreenTextReader.WindowCandidate(isActive = true, packageName = null),
                    ScreenTextReader.WindowCandidate(isActive = false, packageName = ""),
                    ScreenTextReader.WindowCandidate(isActive = false, packageName = "com.android.chrome"),
                ),
                ownPackage = "com.holopengin.instantjpdict",
            ),
        )
    }

    @Test
    fun withoutAnActiveWindowTheFirstUsableOneIsRead() {
        assertEquals(
            1,
            ScreenTextReader.windowToRead(
                listOf(
                    ScreenTextReader.WindowCandidate(isActive = false, packageName = "com.holopengin.instantjpdict"),
                    ScreenTextReader.WindowCandidate(isActive = false, packageName = "com.android.chrome"),
                    ScreenTextReader.WindowCandidate(isActive = false, packageName = "com.android.systemui"),
                ),
                ownPackage = "com.holopengin.instantjpdict",
            ),
        )
    }

    @Test
    fun withNothingButOursNothingIsRead() {
        // No readable window means the pre-#106 path (every box recognises) rather
        // than reading our own overlay back at ourselves.
        assertEquals(
            null,
            ScreenTextReader.windowToRead(
                listOf(ScreenTextReader.WindowCandidate(isActive = true, packageName = "com.holopengin.instantjpdict")),
                ownPackage = "com.holopengin.instantjpdict",
            ),
        )
        assertEquals(null, ScreenTextReader.windowToRead(emptyList(), ownPackage = "com.holopengin.instantjpdict"))
    }
}
