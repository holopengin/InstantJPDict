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

    // ── which windows to try ────────────────────────────────────────────────

    private fun candidate(active: Boolean, pkg: String?, area: Long = 1_000) =
        ScreenTextReader.WindowCandidate(active, pkg, area)

    @Test
    fun theForegroundPackageIsTriedFirst() {
        // The recorded foreground is the app the user triggered over, and it beats
        // both the chrome and a larger window — the whole point is that the answer
        // is not inferred from the list.
        assertEquals(
            listOf(1, 2, 0),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(active = false, pkg = ScreenTextReader.SYSTEM_CHROME_PACKAGE, area = 9_000_000),
                    candidate(active = false, pkg = "com.android.chrome", area = 1_000),
                    candidate(active = false, pkg = "com.termux", area = 2_000_000),
                ),
                ownPackage = "com.holopengin.instantjpdict.dev",
                preferredPackage = "com.android.chrome",
            ),
        )
    }

    @Test
    fun systemChromeIsTriedLast() {
        // The device report: with no preference recorded, the largest window was
        // com.android.systemui — its shade window reports the whole screen while
        // drawing a strip — and it answered a Chrome page with one node, so the read
        // stopped before ever reaching the app. Chrome goes last even at nine times
        // the area.
        assertEquals(
            listOf(1, 0),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(active = false, pkg = ScreenTextReader.SYSTEM_CHROME_PACKAGE, area = 9_000_000),
                    candidate(active = false, pkg = "com.android.chrome", area = 1_000),
                ),
                ownPackage = "com.holopengin.instantjpdict",
            ),
        )
    }

    @Test
    fun withoutAPreferenceTheLargestForeignWindowIsFirst() {
        assertEquals(
            listOf(1, 0),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(active = false, pkg = "com.android.chrome", area = 1_000),
                    candidate(active = false, pkg = "com.termux", area = 2_000_000),
                ),
                ownPackage = "com.holopengin.instantjpdict",
            ),
        )
    }

    @Test
    fun ourOwnOverlayIsNeverTried() {
        // Ours is skipped even when it is the active window and the largest: its
        // rects describe this overlay's drawing, not the screenshot's content.
        assertEquals(
            listOf(1),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(active = true, pkg = "com.holopengin.instantjpdict.dev", area = 9_000_000),
                    candidate(active = false, pkg = "com.android.chrome"),
                ),
                ownPackage = "com.holopengin.instantjpdict.dev",
            ),
        )
    }

    @Test
    fun aPreferredWindowThatIsOursIsIgnored() {
        // The preference can be stale or, on a capture of our own app, be us; the
        // skip rules win over the preference.
        assertEquals(
            listOf(1),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(active = false, pkg = "com.holopengin.instantjpdict", area = 9_000_000),
                    candidate(active = false, pkg = "com.android.chrome"),
                ),
                ownPackage = "com.holopengin.instantjpdict",
                preferredPackage = "com.holopengin.instantjpdict",
            ),
        )
    }

    @Test
    fun aWindowWithNoPackageIsNeverTried() {
        assertEquals(
            listOf(2),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(active = true, pkg = null, area = 9_000_000),
                    candidate(active = false, pkg = "", area = 5_000_000),
                    candidate(active = false, pkg = "com.android.chrome"),
                ),
                ownPackage = "com.holopengin.instantjpdict",
            ),
        )
    }

    @Test
    fun withNothingButOursNothingIsTried() {
        // No readable window means the pre-#106 path (every box recognises) rather
        // than reading our own overlay back at ourselves.
        assertEquals(
            emptyList<Int>(),
            ScreenTextReader.windowsToTry(
                listOf(candidate(active = true, pkg = "com.holopengin.instantjpdict")),
                ownPackage = "com.holopengin.instantjpdict",
            ),
        )
        assertEquals(emptyList<Int>(), ScreenTextReader.windowsToTry(emptyList(), ownPackage = "com.holopengin.instantjpdict"))
    }
}
