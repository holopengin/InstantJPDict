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

    private fun candidate(
        active: Boolean = false,
        pkg: String?,
        area: Long = 1_000,
        ownOverlay: Boolean = false,
    ) = ScreenTextReader.WindowCandidate(active, pkg, area, ownOverlay)

    @Test
    fun withAForegroundPackageOnlyThatPackageIsOffered() {
        // No chrome fallback: a status-bar node drawn as a line over someone else's
        // app was never useful — and it is what answered a Chrome page before this.
        assertEquals(
            listOf(1),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(pkg = ScreenTextReader.SYSTEM_CHROME_PACKAGE, area = 9_000_000),
                    candidate(pkg = "com.android.chrome"),
                ),
                preferredPackage = "com.android.chrome",
            ),
        )
    }

    @Test
    fun withAForegroundPackageThatIsNotPresentNothingIsOffered() {
        // Nothing to read is the pre-#106 path (every box recognises), rather than
        // reading whatever else happens to be on screen.
        assertEquals(
            emptyList<Int>(),
            ScreenTextReader.windowsToTry(
                listOf(candidate(pkg = ScreenTextReader.SYSTEM_CHROME_PACKAGE, area = 9_000_000)),
                preferredPackage = "com.android.chrome",
            ),
        )
    }

    @Test
    fun ourOwnOverlayIsNeverOfferedButOurActivityIs() {
        // The distinction the device needed: excluding our whole package left a
        // capture of our own app with no tree at all, and the status bar answered
        // instead (`foreground=com.holopengin.instantjpdict.dev` beside
        // `pkg=com.android.systemui`, one node, no Japanese).
        assertEquals(
            listOf(1),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(active = true, pkg = "com.holopengin.instantjpdict.dev", area = 9_000_000, ownOverlay = true),
                    candidate(pkg = "com.holopengin.instantjpdict.dev", area = 2_000_000),
                ),
                preferredPackage = "com.holopengin.instantjpdict.dev",
            ),
        )
    }

    @Test
    fun theActiveWindowWinsWithinAPreferredPackage() {
        // Two windows of the same app (a dialog over it) — the one taking input is
        // the one the user is looking at.
        assertEquals(
            listOf(1, 0),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(pkg = "com.android.chrome", area = 9_000_000),
                    candidate(active = true, pkg = "com.android.chrome", area = 1_000),
                ),
                preferredPackage = "com.android.chrome",
            ),
        )
    }

    @Test
    fun withNoPreferenceSystemChromeIsTriedLast() {
        // The fallback, for when no foreground package was recorded: chrome last
        // even at nine times the area, because its shade window reports the whole
        // screen while drawing a strip.
        assertEquals(
            listOf(1, 0),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(pkg = ScreenTextReader.SYSTEM_CHROME_PACKAGE, area = 9_000_000),
                    candidate(pkg = "com.android.chrome"),
                ),
            ),
        )
    }

    @Test
    fun withNoPreferenceTheLargestForeignWindowIsFirst() {
        assertEquals(
            listOf(1, 0),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(pkg = "com.android.chrome"),
                    candidate(pkg = "com.termux", area = 2_000_000),
                ),
            ),
        )
    }

    @Test
    fun aWindowWithNoPackageIsNeverOffered() {
        assertEquals(
            listOf(2),
            ScreenTextReader.windowsToTry(
                listOf(
                    candidate(active = true, pkg = null, area = 9_000_000),
                    candidate(pkg = "", area = 5_000_000),
                    candidate(pkg = "com.android.chrome"),
                ),
            ),
        )
    }

    @Test
    fun withNothingUsableNothingIsOffered() {
        assertEquals(
            emptyList<Int>(),
            ScreenTextReader.windowsToTry(
                listOf(candidate(active = true, pkg = "com.holopengin.instantjpdict", ownOverlay = true)),
            ),
        )
        assertEquals(emptyList<Int>(), ScreenTextReader.windowsToTry(emptyList()))
    }

    // ── containment: one node per pixel ─────────────────────────────────────

    private fun node(text: String, rect: JpDictRect) = ScreenTextNode(text, rect)

    @Test
    fun aContainerThatCarriesItsChildrenIsKeptAndTheyAreDropped() {
        // The reader case (Kindle on the device): the page container's text is its
        // children's run together, so it is the fuller truth. Keeping the children
        // instead would draw the page twice over the same pixels.
        assertEquals(
            listOf(0),
            ScreenTextReader.dropContained(
                listOf(
                    node("これは一行目です。これは二行目です。", JpDictRect(0, 0, 1000, 2000)),
                    node("これは一行目です。", JpDictRect(10, 10, 990, 80)),
                    node("これは二行目です。", JpDictRect(10, 90, 990, 160)),
                ),
            ),
        )
    }

    @Test
    fun anUnrelatedContainerIsDroppedForItsChild() {
        // The card case: the container's own text is a heading while its child carries
        // the body. Neither contains the other, so keeping both would lay two lines
        // over the same pixels — the container is dropped and its text goes to
        // recognition, where an uncovered region belongs.
        assertEquals(
            listOf(1),
            ScreenTextReader.dropContained(
                listOf(
                    node("見出し", JpDictRect(0, 0, 500, 400)),
                    node("本文のテキストです。", JpDictRect(20, 100, 480, 200)),
                ),
            ),
        )
    }

    @Test
    fun aContainerWithOneUnrelatedChildIsDroppedForAllOfThem() {
        // One child it does not carry is enough: the container would overlap that
        // child, so it goes, and every child stays (the ones it did carry too).
        assertEquals(
            listOf(1, 2),
            ScreenTextReader.dropContained(
                listOf(
                    node("見出し 本文のテキスト", JpDictRect(0, 0, 500, 400)),
                    node("本文のテキスト", JpDictRect(20, 100, 480, 200)),
                    node("別の段落", JpDictRect(20, 220, 480, 300)),
                ),
            ),
        )
    }

    @Test
    fun siblingsAreAllKept() {
        assertEquals(
            listOf(0, 1, 2),
            ScreenTextReader.dropContained(
                listOf(
                    node("一行目", JpDictRect(10, 10, 990, 80)),
                    node("二行目", JpDictRect(10, 90, 990, 160)),
                    node("三行目", JpDictRect(10, 170, 990, 240)),
                ),
            ),
        )
    }

    @Test
    fun threeDeepResolvesToTheOutermostThatCarriesThem() {
        // Outermost-first: the grandparent carries both levels, so it survives alone.
        assertEquals(
            listOf(0),
            ScreenTextReader.dropContained(
                listOf(
                    node("あい", JpDictRect(0, 0, 300, 300)),
                    node("あい", JpDictRect(10, 10, 200, 200)),
                    node("あ", JpDictRect(20, 20, 100, 100)),
                ),
            ),
        )
    }

    @Test
    fun equalRectsResolveToOneSurvivor() {
        // Two nodes reporting the same pixels with different text: one line, and the
        // earlier index is the deterministic winner.
        assertEquals(
            listOf(0),
            ScreenTextReader.dropContained(
                listOf(
                    node("同じ場所", JpDictRect(10, 10, 200, 200)),
                    node("別の文字列", JpDictRect(10, 10, 200, 200)),
                ),
            ),
        )
    }

    @Test
    fun twoNearlyCoincidingRectsResolveToOneSurvivor() {
        // Containment proper was not enough on the device: a notification's card and
        // the row drawn inside it overlap almost completely without either enclosing
        // the other, so both answered and their lines were drawn over each other. The
        // rule is now "mostly inside" (half the smaller rect), and as with
        // containment the inner one wins when the outer does not carry its text.
        assertEquals(
            listOf(1),
            ScreenTextReader.dropContained(
                listOf(
                    node("お知らせ", JpDictRect(0, 0, 500, 200)),
                    node("新しいメッセージ", JpDictRect(10, 20, 490, 190)),
                ),
            ),
        )
    }

    @Test
    fun fewerThanTwoNodesAreUntouched() {
        assertEquals(emptyList<Int>(), ScreenTextReader.dropContained(emptyList()))
        assertEquals(
            listOf(0),
            ScreenTextReader.dropContained(listOf(node("一", JpDictRect(0, 0, 10, 10)))),
        )
    }
}
