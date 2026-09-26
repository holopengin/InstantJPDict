package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.util.DeinflectionChain
import com.holopengin.instantjpdict.util.DictionaryRedirects
import com.holopengin.instantjpdict.util.OovCandidates
import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.GapCandidates
import com.holopengin.instantjpdict.util.OovSuggestions
import com.holopengin.instantjpdict.util.PitchAccent
import com.holopengin.instantjpdict.util.BookmarkCandidate
import com.holopengin.instantjpdict.util.Definitions
import com.holopengin.instantjpdict.data.DictionaryEntry
import com.holopengin.instantjpdict.data.toEntity
import com.holopengin.instantjpdict.data.toRow
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import uniffi.nav_graph_core.*

data class LineResult(
    var text: String,
    val charBoxes: List<JpDictRect>,
    val alternatives: List<MutableList<Pair<Char, Float>>>,
    val isVertical: Boolean = false,
    val overrides: MutableMap<Int, Pair<Char, Float>> = mutableMapOf(),
    /**
     * Cached top-15 alternatives for EVERY timestep, for slider re-decode without re-running model.
     *
     * Recognition stores a [TimestepTopK] here rather than the nested rows: it
     * *is* a `List<List<Pair<Char, Float>>>`, so this field, `copy`, `equals`
     * and every reader are unchanged, but the rows are built lazily from the
     * decode's flat cell list the first time something reads one. The page path
     * never does — only the slider re-decode, the blank-candidate list and the
     * rare second gap plan call do.
     */
    val rawAlternatives: List<List<Pair<Char, Float>>> = emptyList(),
    val seqLenTotal: Int = 0,
    val cropW: Int = 0,
    val cropH: Int = 0,
    val cropX: Int = 0,
    val cropY: Int = 0,
    /** CTC timestep column per emitted char (#49) — lets tests recompute
     * char boxes under any BOX_LAYOUT_MODE from one recognition run. */
    val charCols: FloatArray = floatArrayOf(),
    /** #53: this Line's upright-crop placement when it was detected rotated;
     * null on the default axis-aligned path. The char boxes above stay
     * source-space AABBs (of the rotated cells) so every consumer that only
     * knows rects keeps working; [tiltDeg] tells the renderer how far to turn
     * each glyph, and [glyphSizePx] measures text in the upright frame. */
    val quad: JpDictQuad? = null,
) {
    /** #53: clockwise glyph rotation in source pixels; 0 = exactly as today. */
    val tiltDeg: Float get() = quad?.tiltDeg ?: 0f

    /** #53: the source-pixel glyph size the overlay draws this Line at. The
     *  default path is the old inline expression (the largest char box
     *  height); the rotated path measures the upright frame's CROSS axis,
     *  because its char boxes are AABBs of rotated cells and would oversize
     *  the glyphs.
     *
     *  The upright frame's cross axis is `cropH` for a horizontal Line and
     *  `cropW` for a vertical one — the same axis `computeCharBoxes` treats as
     *  the char height/short side. Dividing `cropH` by `seqLenTotal` for the
     *  vertical case (as an earlier revision did) yields the per-timestep step
     *  in model pixels, ~6x smaller than a glyph, and rendered tategaki text
     *  at a fraction of its size. */
    fun glyphSizePx(): Int = if (quad == null) {
        charBoxes.maxOfOrNull { it.height() } ?: 0
    } else if (isVertical) {
        cropW.coerceAtLeast(1)
    } else {
        cropH.coerceAtLeast(1)
    }
}

sealed class DefinitionNode {
    data class Text(val text: String) : DefinitionNode()
    data class Ruby(val term: String, val reading: String, val isMini: Boolean) : DefinitionNode()
    data class Tag(val text: String, val category: String = "general") : DefinitionNode()
    data class Example(
        val japanese: String?,
        val english: String?,
        val content: List<DefinitionNode>?,
        /** #88: an example split into parts (Jitendex's `example-sentence-a`
         *  Japanese and `example-sentence-b` English divisors); each part is
         *  rendered as its own line, so a ruby-laden Japanese sentence is not
         *  joined to its translation by a comma. Empty on the plain-content
         *  shape. */
        val parts: List<List<DefinitionNode>> = emptyList(),
    ) : DefinitionNode()
    data class ListBlock(val items: List<List<DefinitionNode>>, val type: String?) : DefinitionNode()
    data class Table(val rows: List<List<List<DefinitionNode>>>) : DefinitionNode()
    data class Group(val nodes: List<DefinitionNode>, val isInline: Boolean) : DefinitionNode()
    /**
     * #88 follow-up: the source citation that closes a Jitendex entry
     * (`JMdict`, `JMdict | Tatoeba`). Its own node so the renderer can draw it
     * as a small, faint footnote rather than definition-weight text.
     */
    data class Citation(val text: String) : DefinitionNode()
}

data class FormattedSense(
    val index: Int,
    val nodes: List<DefinitionNode>
)

data class FormattedSenseGroup(
    val tags: List<String>,
    val senses: List<FormattedSense>,
    val isForms: Boolean,
    /**
     * #88: structured group-level metadata (Jitendex `part-of-speech-info`,
     * `field-info`, `misc-info`, …), rendered once before [senses]. Empty for
     * JMdict/KANJIDIC, whose group metadata is the string [tags] instead.
     */
    val header: List<DefinitionNode> = emptyList(),
    /**
     * #88: structured non-sense content that trails the senses — the Jitendex
     * forms table and attribution — rendered once after [senses], unnumbered.
     */
    val trailing: List<DefinitionNode> = emptyList()
)

data class FormattedHeadword(
    val kanji: String,
    val onyomi: String?,
    val kunyomi: String?
)

data class FormattedReadingGroup(
    val reading: String,
    val headwords: List<FormattedHeadword>,
    val senseGroups: List<FormattedSenseGroup>,
    val isKanjiEntry: Boolean,
    /** #43: downstep positions for this reading (empty = no pitch data).
     * Rendered as one colored-morae item on the entry's pitch line. */
    val pitchPositions: List<Int> = emptyList(),
    /**
     * False when an earlier reading group already rendered this same glossary.
     * A word's readings are all shown in the headword block, and Jitendex (and
     * JMdict) repeat one glossary across the rows of every reading, so
     * rendering the senses per reading would repeat every sense — and its
     * example box — once per reading.
     */
    val renderSenses: Boolean = true
)

data class FormattedEntry(
    val term: String,
    val readingGroups: List<FormattedReadingGroup>,
    /** Non-null when this entry matched via deinflection (or a JMdict
     * redirect, folded in as a "redirect" chain step, #65); null for direct
     * surface matches (which render exactly as before). */
    val deinflection: DeinflectionChain? = null,
    /** Display name of the dictionary this entry came from (null = unknown,
     * caption omitted). Entries never mix dictionaries. */
    val dictionaryName: String? = null,
    /** #67: what the popup's bookmark toggle saves, or null when this block has
     * no concrete headword to save. Computed here so the view needs no DB read. */
    val bookmark: BookmarkCandidate? = null
)

/** One lookup candidate: a dictionary-form term plus how it was reached.
 *  [requiredTypes] is null for direct surface variants; [chain] is null
 *  for direct matches and set for deinflected ones. */
data class SearchCandidate(
    val term: String,
    val requiredTypes: List<String>?,
    val chain: DeinflectionChain?
)

/** Dictionary entries for one matched term plus its deinflection chain. */
data class TermMatch(
    val term: String,
    val entries: List<DictionaryEntry>,
    val chain: DeinflectionChain? = null
)

data class NeighborChar(
    val text: String,
    val isSelected: Boolean,
    val lineIdx: Int,
    val charIdx: Int
)

data class NeighborLine(
    val chars: List<NeighborChar>,
    val lineIdx: Int
)

data class AlternativeChar(
    val char: Char,
    val isSelected: Boolean,
    /**
     * Where this entry came from (#44): the head's own ranking, a component neighbour of
     * the current character, or one of its variant forms. The panel tints the generated
     * entries so "what the model saw" stays distinguishable from "what the components
     * suggest".
     */
    val source: OovSuggestions.Source = OovSuggestions.Source.HEAD
)

data class AlternativesUiState(
    val candidates: List<AlternativeChar>,
    val showManualInput: Boolean
)

enum class GamepadAction {
    NONE,
    NAVIGATE_LEFT, NAVIGATE_RIGHT, NAVIGATE_UP, NAVIGATE_DOWN,
    CONFIRM, BACK,
    SCROLL_UP, SCROLL_DOWN
}

class OcrOverlayStateController {

    /**
     * Component-derived popup candidates (#44). Null until the service finishes loading the
     * 266 KB component table off the main thread; the panel falls back to the head's own
     * list meanwhile, which is exactly the previous behaviour.
     */
    private var oovCandidates: OovCandidates? = null

    /** Character LM for ranking gap candidates (#44). Null until the service loads it off
     *  the main thread, and null forever if the asset is missing — either way the blank
     *  just offers the placeholder alone. */
    private var charLm: CharLm? = null
    var deinflector: Deinflector? = null
    var dictionaryProvider: DictionaryProvider? = null
    var gson: Gson? = null

    var currentScale = 1f
    var currentTransX = 0f
    var currentTransY = 0f
    var currentWordLength = 0

    var activeLineBoxes: List<LineBox> = emptyList()
    var activeAllChars = mutableListOf<String>()
    var activeAllAlternatives = mutableListOf<List<Pair<Char, Float>>>()
    
    var currentTappedIdx = -1
    var currentTappedLineIdx = -1
    var currentTappedCharIdxInLine = -1
    
    var activeLineResults: MutableList<LineResult?> = mutableListOf()
    var navGraph: NavGraph? = null
    var lastHighlightedCoords = mutableListOf<Pair<Int, Int>>()
    var lastJoystickKeyCode = 0
    var lastLandscapeGravity = JpDictGravity.END
    var lastPortraitGravity = JpDictGravity.BOTTOM
    var lastManualInputCloseTime = 0L
    var lastNeighborHighlightedLine = -1
    var lastNeighborHighlightedChar = -1
    var isControllerNavigation = false
    
    var isDictionaryVisible = false
    var isAlternativesVisible = false

    /**
     * 2026-09-25 overlay-install perf pass: rebuild accounting, so "how often is
     * the page's derived data recomputed" is a number in logcat instead of an
     * inference from the source. [updateGlobalData] is O(page) — it re-derives
     * [activeAllChars] and [activeAllAlternatives] and rebuilds the WHOLE nav
     * graph — so a caller that runs it per installed line pays one prefix rebuild
     * per line, ~21 ms of the ~892 ms page for a 17-line page, and throws all but
     * the last away. `OcrOverlayView.startOcr` therefore calls it once after the
     * install loop and logs the delta; `GlobalDataRebuildTest` pins the
     * once-per-page contract and that the three other call sites still refresh.
     *
     * Cumulative for the life of the object and deliberately NOT reset by
     * [resetState]: the interesting number is a per-page delta, and a counter
     * that a clear could zero is one a log line can misread.
     */
    var globalDataUpdates = 0
        private set

    /** Nav-graph builds, counted apart from [globalDataUpdates] so a direct
     *  [rebuildNavGraph] (there is none today, but it is public) cannot be
     *  mistaken for a global refresh. One per [updateGlobalData]. */
    var navGraphBuilds = 0
        private set

    fun updateGlobalData() {
        globalDataUpdates++
        activeAllChars.clear()
        activeAllAlternatives.clear()
        activeLineResults.forEach { line ->
            line?.let {
                it.text.forEach { char -> activeAllChars.add(char.toString()) }
                it.alternatives.forEach { alts -> activeAllAlternatives.add(alts) }
            }
        }
        rebuildNavGraph()
    }

    fun rebuildNavGraph() {
        navGraphBuilds++
        val boxes = mutableListOf<BoundingBox>()
        for (line in activeLineResults) {
            line?.let {
                for (box in it.charBoxes) {
                    boxes.add(BoundingBox(box.left, box.top, box.width(), box.height()))
                }
            }
        }
        navGraph = if (boxes.size >= 5) buildNavGraph(boxes) else null
    }

    fun getGlobalIdx(lineIdx: Int, charIdxInLine: Int): Int {
        var count = 0
        for (i in 0 until lineIdx) {
            count += activeLineResults[i]?.text?.length ?: 0
        }
        return count + charIdxInLine
    }

    fun getCoordsFromGlobalIdx(globalIdx: Int): Pair<Int, Int>? {
        var count = 0
        for (lineIdx in activeLineResults.indices) {
            val line = activeLineResults[lineIdx] ?: continue
            if (globalIdx < count + line.text.length) {
                return Pair(lineIdx, globalIdx - count)
            }
            count += line.text.length
        }
        return null
    }

    fun resetState() {
        currentScale = 1f
        currentTransX = 0f
        currentTransY = 0f
        currentTappedIdx = -1
        currentTappedLineIdx = -1
        currentTappedCharIdxInLine = -1
        activeLineResults.clear()
        activeLineBoxes = emptyList()
        isControllerNavigation = false
        isDictionaryVisible = false
        isAlternativesVisible = false
        updateGlobalData()
    }

    /**
     * #78: the container changed size with the OCR run KEPT, so the boxes have to
     * be moved into the new composite's pixel space here rather than re-derived by
     * a second pass. [ShareImageActivity] handles a quarter turn in place now
     * (`configChanges` + `onConfigurationChanged`) and re-composes the composite at
     * the new container size; this is the other half — the transform, computed from
     * the two fit placements by [ImageShareFit.refit] and pinned by
     * `ImageShareRefitTest`, applied to every coordinate this object holds:
     *
     *  - [activeLineBoxes], the detect boxes, which draw the line borders;
     *  - every [LineResult.charBoxes] entry, which draws the per-character hit
     *    rects, the cursor and the lookup crop, and which [lookup] reads;
     *  - and, through [updateGlobalData], the derived [navGraph], which is built
     *    from the character boxes and would otherwise navigate by the OLD layout.
     *
     * The RUN itself is untouched: the text, the alternatives, the overrides and
     * the activity's own "turns the last pass read" bookkeeping are all kept — that
     * is the point of the change. Nothing here calls the engine.
     *
     * WHAT IS DELIBERATELY DROPPED, and why:
     *  - THE ZOOM/PAN. `currentScale`/`currentTransX`/`currentTransY` are the screen
     *    transform of the OLD composite (`screen = box * scale + trans`, with the
     *    content container's pivot at its origin), so a box number moved into the
     *    new pixel space would be drawn in a different place on screen unless the
     *    zoom were re-expressed for the new mapping too. The identity — scale 1, no
     *    translation — is the one choice that is right BY CONSTRUCTION: with it the
     *    composite is 1:1 with the container, which is exactly what the re-fit
     *    composed, and the tap-to-box inversion in [isNearCharacter] and
     *    [updateGravity] keeps agreeing with what is drawn. It is also what a
     *    re-created activity would have shown, so nothing the user could rely on is
     *    lost that the old behaviour did not lose; a maintained zoom would need its
     *    own anchor choice and is not what this asks for.
     *  - THE SELECTION AND THE OPEN PANEL: the tapped index and the
     *    dictionary/alternatives flags. The panel is positioned from the tapped
     *    box's screen position and the view's gravity rule, and re-deriving that
     *    placement here would be a second implementation of it. The view re-renders
     *    the boxes and the cursor from the kept results afterwards
     *    ([OcrOverlayView.refitContent]), so the run is still visible and still
     *    tappable — a tap simply starts a new lookup.
     */
    fun refitBoxes(refit: ImageShareFit.Refit) {
        // A container that did not change size asks for nothing: the activity's
        // re-fit is only run for a real size change, and this keeps the two in step.
        if (refit.isIdentity) return
        activeLineBoxes = activeLineBoxes.map { it.refitted(refit) }
        activeLineResults = activeLineResults.map { line ->
            line?.copy(
                charBoxes = line.charBoxes.map { it.refitted(refit) },
                // #53: the frame moves with the boxes; a rotated border that
                // kept its old corners would drift off its glyphs.
                quad = line.quad?.refitted(refit),
            )
        }.toMutableList()
        currentScale = 1f
        currentTransX = 0f
        currentTransY = 0f
        currentTappedIdx = -1
        currentTappedLineIdx = -1
        currentTappedCharIdxInLine = -1
        isDictionaryVisible = false
        isAlternativesVisible = false
        lastHighlightedCoords.clear()
        lastNeighborHighlightedLine = -1
        lastNeighborHighlightedChar = -1
        updateGlobalData()
    }

    /** One box through the re-fit transform: [ImageShareFit.Refit] owns the maths
     *  (plain ints, pinned by unit tests); this is only the [JpDictRect] spelling of
     *  it, so the object above stays free of app types and this file free of maths. */
    private fun JpDictRect.refitted(refit: ImageShareFit.Refit): JpDictRect =
        JpDictRect(
            refit.x(left),
            refit.y(top),
            refit.x(right),
            refit.y(bottom),
        )

    /** #53: a rotated Line's frame goes through the same transform, corner by
     *  corner. Under the app's re-fit (uniform scale, no rotation) the frame
     *  stays a rectangle; under a non-uniform one it shears, which the border
     *  renderer draws as-is. */
    private fun JpDictQuad.refitted(refit: ImageShareFit.Refit): JpDictQuad =
        JpDictQuad(
            QuadPoint(refit.offsetX + c0.x * refit.scaleX, refit.offsetY + c0.y * refit.scaleY),
            QuadPoint(refit.offsetX + c1.x * refit.scaleX, refit.offsetY + c1.y * refit.scaleY),
            QuadPoint(refit.offsetX + c2.x * refit.scaleX, refit.offsetY + c2.y * refit.scaleY),
            QuadPoint(refit.offsetX + c3.x * refit.scaleX, refit.offsetY + c3.y * refit.scaleY),
        )

    private fun LineBox.refitted(refit: ImageShareFit.Refit): LineBox =
        LineBox(rect.refitted(refit), quad?.refitted(refit))

    fun updateCharacter(lineIdx: Int, charIdx: Int, newChar: Char) {
        val line = activeLineResults.getOrNull(lineIdx) ?: return
        val charArray = line.text.toCharArray()
        if (charIdx in charArray.indices) {
            charArray[charIdx] = newChar
            line.text = String(charArray)
            
            // Save override (charIdx-based for PP-OCR)
            line.overrides[charIdx] = newChar to 1f

            updateGlobalData()
        }
    }

    fun navigate(keyCode: Int, rootWidth: Double, rootHeight: Double): Boolean {
        if (activeLineResults.isEmpty()) return false
        if (currentTappedLineIdx == -1 || currentTappedCharIdxInLine == -1) return false

        // Use nav graph if available
        val graph = navGraph
        if (graph != null) {
            val dir = when (keyCode) {
                JpDictKeyEvent.KEYCODE_DPAD_UP -> 0
                JpDictKeyEvent.KEYCODE_DPAD_DOWN -> 1
                JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> 2
                JpDictKeyEvent.KEYCODE_DPAD_LEFT -> 3
                else -> return false
            }
            val target = navigate(graph, currentTappedIdx, dir) ?: return false
            val coords = getCoordsFromGlobalIdx(target) ?: return false
            currentTappedLineIdx = coords.first
            currentTappedCharIdxInLine = coords.second
            currentTappedIdx = target
            return true
        }

        // Legacy fallback: same-line left/right
        val line = activeLineResults[currentTappedLineIdx] ?: return false
        when (keyCode) {
            JpDictKeyEvent.KEYCODE_DPAD_LEFT, JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (keyCode == JpDictKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (currentTappedCharIdxInLine + dir in line.charBoxes.indices) {
                    currentTappedCharIdxInLine += dir
                    currentTappedIdx = getGlobalIdx(currentTappedLineIdx, currentTappedCharIdxInLine)
                    return true
                }
            }
        }
        return false
    }

    fun ensureCursorPosition() {
        val currentLine = activeLineResults.getOrNull(currentTappedLineIdx)
        if (currentTappedLineIdx == -1 || currentTappedCharIdxInLine == -1 || 
            currentLine == null || currentTappedCharIdxInLine >= currentLine.charBoxes.size) {
            for (i in activeLineResults.indices) {
                val line = activeLineResults[i]
                if (line != null && line.charBoxes.isNotEmpty()) {
                    currentTappedLineIdx = i
                    currentTappedCharIdxInLine = 0
                    currentTappedIdx = getGlobalIdx(i, 0)
                    break
                }
            }
        }
    }

    /**
     * True when the character at [lineIdx]/[charIdx] is the reversible blank placeholder
     * (#44 Feature 2). [lookup] returns null for a placeholder, so the tap path branches on
     * this and opens the alternatives panel instead of doing dictionary work.
     */
    fun isBlankAt(lineIdx: Int, charIdx: Int): Boolean =
        activeLineResults.getOrNull(lineIdx)?.text?.getOrNull(charIdx) == OcrEngine.GAP_CHAR

    /**
     * Anchor the tapped position on a blank without running a lookup. The alternatives state
     * then carries the placeholder (marked selected) plus the manual input entry, which is
     * how the ground-truth character gets typed in; filling it writes an ordinary override,
     * so it reverts like any other correction.
     */
    fun selectBlankPosition(lineIdx: Int, charIdx: Int) {
        currentTappedIdx = getGlobalIdx(lineIdx, charIdx)
        currentTappedLineIdx = lineIdx
        currentTappedCharIdxInLine = charIdx
    }

    suspend fun lookup(lineIdx: Int, charIdx: Int): Result? {
        val deinf = deinflector ?: return null
        val provider = dictionaryProvider ?: return null
        val g = gson ?: return null

        val line = activeLineResults.getOrNull(lineIdx) ?: return null
        // A placeholder has no definition. The tap path routes it to the alternatives panel
        // instead (see [isBlankAt] / [selectBlankPosition]), where the manual IME fills it.
        if (line.text.getOrNull(charIdx) == OcrEngine.GAP_CHAR) return null

        val globalIdx = getGlobalIdx(lineIdx, charIdx)
        currentTappedIdx = globalIdx
        currentTappedLineIdx = lineIdx
        currentTappedCharIdxInLine = charIdx

        val tappedBox = line.charBoxes.getOrNull(charIdx) ?: JpDictRect(0, 0, 0, 0)

        val endIdx = kotlin.math.min(globalIdx + 20, activeAllChars.size)
        val followingText = activeAllChars.subList(globalIdx, endIdx).joinToString("")

        val (allTermsToSearch, candidatesByLength) = prepareSearchCandidates(followingText, deinf)
        val dbResults = provider.findByTexts(allTermsToSearch.toList())
        // dictionaryId → display name for per-entry source captions. Missing
        // map = captions omitted, entries still split per dictionary.
        val dictNames = try { provider.dictionaryNames() } catch (_: Exception) { emptyMap() }
        val (uniqueMatches, maxLen) = processResults(dbResults, candidatesByLength, allTermsToSearch, followingText)

        // ── Redirect pass (#65): JMdict pointer entries (variant spellings)
        // carry only ?query= links and render as dead "⟶, X" text. Resolve
        // them breadth-first (visited set + hop cap, so A→B→A cycles always
        // terminate), then splice each target directly below its source entry
        // (depth-first) so a redirect reads as pointer → target instead of
        // stranding the target at the end of the popup.
        val redirectVia = mutableMapOf<String, String>()
        val resolvedByTerm = mutableMapOf<String, TermMatch>()
        val childrenOf = mutableMapOf<String, MutableList<String>>()
        if (uniqueMatches.isNotEmpty()) {
            val visited = uniqueMatches.map { it.term }.toMutableSet()
            val queue = ArrayDeque<TermMatch>()
            uniqueMatches.forEach { queue.add(it) }
            var hops = 0
            while (queue.isNotEmpty() && hops < 3) {
                repeat(queue.size) {
                    val match = queue.removeFirst()
                    for (entry in match.entries) {
                        for (target in DictionaryRedirects.extractTargets(entry.definitions)) {
                            if (!visited.add(target)) continue
                            val targetResults = provider.findByTexts(listOf(target))
                            if (targetResults.isEmpty()) continue
                            redirectVia[target] = match.term
                            val resolved = TermMatch(target, targetResults.distinctBy { it.id })
                            resolvedByTerm[target] = resolved
                            childrenOf.getOrPut(match.term) { mutableListOf() }.add(target)
                            queue.add(resolved)
                        }
                    }
                }
                hops++
            }
        }
        val resolvedMatches = mutableListOf<TermMatch>()
        fun emit(match: TermMatch) {
            resolvedMatches.add(match)
            childrenOf[match.term]?.forEach { child ->
                resolvedByTerm[child]?.let { emit(it) }
            }
        }
        uniqueMatches.forEach { emit(it) }

        val formatted = formatDictionaryResults(resolvedMatches, g, dictNames).toMutableList()
        for (i in formatted.indices) {
            redirectVia[formatted[i].term]?.let { via ->
                // The redirect hop joins the deinflection chain ("via → term
                // · redirect") instead of a separate caption. Redirect targets
                // never carry a chain of their own (real entries with glosses
                // never redirect), but guard anyway.
                if (formatted[i].deinflection == null) {
                    formatted[i] = formatted[i].copy(
                        deinflection = DeinflectionChain(surface = via, steps = listOf("redirect"))
                    )
                }
            }
        }
        currentWordLength = maxLen

        // ── Second pass: look up each individual kanji in the matched term ──
        val matchedTerm = followingText.take(maxLen)
        val appendKanji = mutableListOf<FormattedEntry>()
        for (ch in matchedTerm) {
            // Only CJK Unified Ideographs (kanji)
            if (ch !in '\u4E00'..'\u9FFF' && ch !in '\u3400'..'\u4DBF') {
                continue
            }
            val kanjiStr = ch.toString()
            // Deduplicate: skip if this kanji already has an entry from the
            // first-pass term lookup or we already appended it above
            if (formatted.any { it.term == kanjiStr } || appendKanji.any { it.term == kanjiStr }) {
                continue
            }
            val kanjiResults = provider.findByTexts(listOf(kanjiStr))
            val kanjiOnly = kanjiResults.filter { it.onyomi != null || it.kunyomi != null }
            if (kanjiOnly.isNotEmpty()) {
                val formattedKanji = formatDictionaryResults(listOf(TermMatch(kanjiStr, kanjiOnly)), g, dictNames)
                appendKanji.addAll(formattedKanji)
            }
        }
        if (appendKanji.isNotEmpty()) {
            formatted.addAll(appendKanji)
        }
        
        return Result(formatted, maxLen, tappedBox, followingText)
    }

    data class Result(val matches: List<FormattedEntry>, val maxLen: Int, val tappedBox: JpDictRect, val cacheKey: String)

    fun getNeighborUiState(): List<NeighborLine> {
        return activeLineResults.mapIndexed { lIdx, line ->
            if (line == null) NeighborLine(emptyList(), lIdx)
            else NeighborLine(
                line.text.mapIndexed { cIdx, char ->
                    NeighborChar(
                        char.toString(),
                        isSelected = (lIdx == currentTappedLineIdx && cIdx == currentTappedCharIdxInLine),
                        lIdx,
                        cIdx
                    )
                },
                lIdx
            )
        }
    }

    fun getAlternativesUiState(
        lineIdx: Int = currentTappedLineIdx,
        charIdx: Int = currentTappedCharIdxInLine,
    ): AlternativesUiState? {
        // Indices are parameters, not just the controller's own fields: the neighbour panel
        // opens the alternatives for a character it was handed, and without a lookup first
        // those fields are stale — which showed up as a completely blank list (#44).
        val line = activeLineResults.getOrNull(lineIdx) ?: return null
        val candidates = alternativeCharsFor(line, charIdx) ?: return null

        return AlternativesUiState(candidates, showManualInput = true)
    }

    /**
     * The popup list for one character (#44): the head's own top-15, plus — once the
     * component table has loaded — component neighbours and variant forms of that character.
     * Both the panel and keyboard navigation read this, so they can never disagree about what
     * the list contains. Unconditional: the setting this used to consult is gone.
     */
    private fun alternativeCharsFor(line: LineResult, cIdx: Int): List<AlternativeChar>? {
        val current = line.text.getOrNull(cIdx)
        // A blank is not a character to expand by components: its list is the placeholder
        // plus the evidence-ranked candidates, and it is checked *before* the alternatives
        // table because the table does have an entry for the placeholder — an earlier version
        // handled only the case where it did not, so the ranked candidates were unreachable
        // and the list came back as the dotted circle alone (#44).
        if (current == OcrEngine.GAP_CHAR) return gapCandidates(line, cIdx)
        val alts = line.alternatives.getOrNull(cIdx) ?: return null
        val head = alts.take(15).map { it.first }
        val suggestions = if (oovCandidates != null && current != null) {
            OovSuggestions.assemble(current, head, oovCandidates)
        } else {
            head.map { OovSuggestions.Suggestion(it, OovSuggestions.Source.HEAD) }
        }
        return suggestions.map {
            AlternativeChar(it.char, isSelected = it.char == current, source = it.source)
        }
    }

    /**
     * The blank's list: the placeholder itself (so the entry is selectable and carries the
     * manual IME) followed by the LM-ranked kanji the recogniser offered along this line.
     * Unconditional, like the component suggestions it shares its loading state with.
     */
    private fun gapCandidates(line: LineResult, cIdx: Int): List<AlternativeChar> {
        val out = mutableListOf(
            AlternativeChar(OcrEngine.GAP_CHAR, isSelected = true, source = OovSuggestions.Source.HEAD))
        val ranked = GapCandidates.generate(line.text, line.rawAlternatives, cIdx, charLm)
        // Evidence first, then the punctuation and kana a gap most often holds. A blank with
        // nothing to choose from is worse than a guess, so this list is never just the
        // placeholder; even the fallback is ordered by context when the model is loaded.
        val alternatives = ranked.ifEmpty { GapCandidates.fallback(line.text, cIdx, charLm) }
        alternatives.forEach { out.add(AlternativeChar(it, isSelected = false, source = OovSuggestions.Source.LM)) }
        return out
    }

    fun navigateAlternatives(keyCode: Int, isLandscape: Boolean): Char? {
        val diff = when (keyCode) {
            JpDictKeyEvent.KEYCODE_DPAD_DOWN -> if (isLandscape) 1 else 0
            JpDictKeyEvent.KEYCODE_DPAD_UP -> if (isLandscape) -1 else 0
            JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> if (!isLandscape) 1 else 0
            JpDictKeyEvent.KEYCODE_DPAD_LEFT -> if (!isLandscape) -1 else 0
            else -> 0
        }
        if (diff == 0) return null

        val line = activeLineResults.getOrNull(currentTappedLineIdx) ?: return null
        val currentChar = line.text.getOrNull(currentTappedCharIdxInLine) ?: return null
        val candidates = alternativeCharsFor(line, currentTappedCharIdxInLine)?.map { it.char }
            ?: return null
        val currentIndex = candidates.indexOf(currentChar)

        if (currentIndex != -1) {
            val newIndex = (currentIndex + diff).coerceIn(0, candidates.size - 1)
            if (newIndex != currentIndex) {
                return candidates[newIndex]
            }
        }
        return null
    }

    /**
     * Component-derived suggestions (#44). The service loads the 266 KB component table off
     * the main thread and calls this once it lands; until then the panel shows the head's
     * own list, exactly as before. Unconditional since the setting was removed: the table's
     * arrival is the only thing that gates the suggestions.
     */
    fun installOovSuggestions(candidates: OovCandidates) {
        oovCandidates = candidates
    }

    /**
     * The character LM the blank's candidates are ranked with (#44). Loaded by the service
     * off the main thread like the component table; until it lands, and if the asset is
     * missing, a blank offers the placeholder and the manual IME and nothing else.
     */
    fun installCharLm(lm: CharLm?) {
        charLm = lm
    }

    fun getPanelDimensions(rootWidth: Int, rootHeight: Int): Pair<Float, Float> {
        val isLandscape = rootWidth > rootHeight
        val panelWidth = if (isLandscape) (rootWidth * 0.4f) else rootWidth.toFloat()
        val panelHeight = if (isLandscape) rootHeight.toFloat() else (rootHeight * 0.4f)
        return Pair(panelWidth, panelHeight)
    }

    /**
     * Top clearance (px) that keeps the dictionary content clear of the status
     * bar.
     *
     * The overlay windows lay out UNDER the system bars (`FLAG_LAYOUT_NO_LIMITS`
     * in ShareImageActivity, the accessibility overlay in the service), so
     * nothing insets for them automatically. The clearance is owed exactly
     * where the panel's top edge IS the screen's top edge, and [activeGravity]
     * says where the panel is hung:
     *
     *  - `TOP` — a portrait panel hung at the top;
     *  - `START` / `END` — a landscape side panel, which runs the full screen
     *    height and so always has its top edge at the top;
     *  - `BOTTOM` — a portrait panel near the navigation bar, nowhere near the
     *    status bar, so nothing is owed.
     *
     * The test is therefore on BOTTOM *not* being set rather than on equality:
     * a side gravity read back out of a laid-out view arrives as `START|LEFT`
     * / `END|RIGHT`, the round trip through `Gravity`, and must still count.
     * The caller spends it as content padding inside the scroll view (the
     * panel itself must keep its top edge at the screen's), so at rest the
     * first entry sits just below the bar and scrolling takes content up
     * beneath it. [statusBarPx] is the bar's runtime height, resolved by the
     * caller (the overlay already reads it for the #64 strip scrim).
     */
    fun dictionaryTopInset(activeGravity: Int, statusBarPx: Int): Int =
        if (activeGravity and JpDictGravity.BOTTOM == 0) statusBarPx else 0

    fun updateGravity(rootWidth: Int, rootHeight: Int, tappedBox: JpDictRect) {
        val isLandscape = rootWidth > rootHeight

        // Calculate screen center of the character, taking into account current scale and translation
        val screenCenterX = tappedBox.centerX() * currentScale + currentTransX
        val screenCenterY = tappedBox.centerY() * currentScale + currentTransY

        if (isLandscape) {
            lastLandscapeGravity = if (screenCenterX < rootWidth / 2f) {
                JpDictGravity.END
            } else {
                JpDictGravity.START
            }
        } else {
            lastPortraitGravity = if (screenCenterY < rootHeight / 2f) {
                JpDictGravity.BOTTOM
            } else {
                JpDictGravity.TOP
            }
        }
    }

    fun centerOnCharacter(lineIdx: Int, charIdx: Int, rootWidth: Int, rootHeight: Int): Boolean {
        val line = activeLineResults.getOrNull(lineIdx) ?: return false
        val charBox = line.charBoxes.getOrNull(charIdx) ?: return false
        
        var changed = false
        if (isControllerNavigation) {
            isControllerNavigation = false
            
            val visibleCenterX: Float
            val visibleCenterY: Float
            
            if (!isDictionaryVisible) {
                val left = charBox.left * currentScale + currentTransX
                val right = charBox.right * currentScale + currentTransX
                val top = charBox.top * currentScale + currentTransY
                val bottom = charBox.bottom * currentScale + currentTransY
                
                var nudgeX = 0f
                if (left < 0) nudgeX = -left
                else if (right > rootWidth) nudgeX = rootWidth.toFloat() - right
                
                var nudgeY = 0f
                if (top < 0) nudgeY = -top
                else if (bottom > rootHeight) nudgeY = rootHeight.toFloat() - bottom
                
                if (nudgeX != 0f || nudgeY != 0f) {
                    currentTransX += nudgeX
                    currentTransY += nudgeY
                    changed = true
                }
            } else {
                val (panelWidth, panelHeight) = getPanelDimensions(rootWidth, rootHeight)
                val isLandscape = rootWidth > rootHeight
                if (isLandscape) {
                    val isEnd = lastLandscapeGravity == JpDictGravity.END
                    visibleCenterX = if (isEnd) (rootWidth - panelWidth) / 2f else panelWidth + (rootWidth - panelWidth) / 2f
                    visibleCenterY = rootHeight / 2f
                } else {
                    val isBottom = lastPortraitGravity == JpDictGravity.BOTTOM
                    visibleCenterX = rootWidth / 2f
                    visibleCenterY = if (isBottom) (rootHeight - panelHeight) / 2f else panelHeight + (rootHeight - panelHeight) / 2f
                }
                
                currentTransX = visibleCenterX - charBox.centerX() * currentScale
                currentTransY = visibleCenterY - charBox.centerY() * currentScale
                changed = true
            }
        }
        return changed
    }

    fun isNearCharacter(screenX: Float, screenY: Float, marginDp: Float, density: Float): Boolean {
        val s = currentScale
        val ix = (screenX - currentTransX) / s
        val iy = (screenY - currentTransY) / s
        val m = (marginDp * density) / s
        
        return activeLineResults.filterNotNull().any { line ->
            line.charBoxes.any { b ->
                ix >= b.left - m && ix <= b.right + m && iy >= b.top - m && iy <= b.bottom + m
            }
        } || activeLineBoxes.any { b ->
            ix >= b.rect.left - m && ix <= b.rect.right + m && iy >= b.rect.top - m && iy <= b.rect.bottom + m
        }
    }

    fun prepareSearchCandidates(
        followingText: String,
        deinflector: Deinflector
    ): Pair<Set<String>, List<Pair<Int, List<SearchCandidate>>>> {
        // Candidate preparation runs in jpdict_core; the facade passes its
        // UniFFI-backed handle so Rust deinflects in-process (no callback
        // round-trip per prefix).
        val prepared = lookupPrepareCandidates(followingText, deinflector.inner)
        val candidatesByLength = prepared.byLength.map { group ->
            group.length.toInt() to group.candidates.map { candidate ->
                SearchCandidate(
                    term = candidate.term,
                    requiredTypes = candidate.requiredTypes,
                    chain = candidate.chain?.let {
                        DeinflectionChain(it.surface, it.steps)
                    },
                )
            }
        }
        return prepared.terms.toSet() to candidatesByLength
    }

    fun processResults(
        dbResults: List<DictionaryEntry>,
        candidatesByLength: List<Pair<Int, List<SearchCandidate>>>,
        allTermsToSearch: Set<String>,
        followingText: String
    ): Pair<List<TermMatch>, Int> {
        val prepared = PreparedLookupCandidates(
            terms = allTermsToSearch.toList(),
            byLength = candidatesByLength.map { (length, candidates) ->
                LookupCandidateGroup(
                    length = length.toLong(),
                    candidates = candidates.map { candidate ->
                        LookupSearchCandidate(
                            term = candidate.term,
                            requiredTypes = candidate.requiredTypes,
                            chain = candidate.chain?.let {
                                LookupDeinflectionChain(it.surface, it.steps)
                            },
                        )
                    },
                )
            },
        )
        val processed = lookupProcessResults(
            rows = dbResults.map { it.toRow() },
            prepared = prepared,
            followingText = followingText,
        )
        val matches = processed.matches.map { match ->
            TermMatch(
                term = match.term,
                entries = match.entries.map { it.toEntity() },
                chain = match.chain?.let { DeinflectionChain(it.surface, it.steps) },
            )
        }
        return matches to processed.maxLen.toInt()
    }

    fun resolveGamepadAction(keyCode: Int, layoutSwap: Boolean): GamepadAction {
        val mappedEnter = if (layoutSwap) JpDictKeyEvent.KEYCODE_BUTTON_B else JpDictKeyEvent.KEYCODE_BUTTON_A
        val mappedBack = if (layoutSwap) JpDictKeyEvent.KEYCODE_BUTTON_A else JpDictKeyEvent.KEYCODE_BUTTON_B

        return when (keyCode) {
            JpDictKeyEvent.KEYCODE_DPAD_LEFT -> GamepadAction.NAVIGATE_LEFT
            JpDictKeyEvent.KEYCODE_DPAD_RIGHT -> GamepadAction.NAVIGATE_RIGHT
            JpDictKeyEvent.KEYCODE_DPAD_UP -> GamepadAction.NAVIGATE_UP
            JpDictKeyEvent.KEYCODE_DPAD_DOWN -> GamepadAction.NAVIGATE_DOWN
            JpDictKeyEvent.KEYCODE_ENTER, JpDictKeyEvent.KEYCODE_DPAD_CENTER, mappedEnter -> GamepadAction.CONFIRM
            JpDictKeyEvent.KEYCODE_BACK, JpDictKeyEvent.KEYCODE_ESCAPE, mappedBack -> GamepadAction.BACK
            JpDictKeyEvent.KEYCODE_BUTTON_L1, JpDictKeyEvent.KEYCODE_BUTTON_L2 -> GamepadAction.SCROLL_UP
            JpDictKeyEvent.KEYCODE_BUTTON_R1, JpDictKeyEvent.KEYCODE_BUTTON_R2 -> GamepadAction.SCROLL_DOWN
            else -> GamepadAction.NONE
        }
    }

    fun isHandledKey(keyCode: Int): Boolean {
        // Simple check without considering layoutSwap as most keys are shared
        return resolveGamepadAction(keyCode, false) != GamepadAction.NONE || 
               resolveGamepadAction(keyCode, true) != GamepadAction.NONE
    }

    fun getRepeatInterval(rate: Int): Long = (1000L / rate.toLong()).coerceAtLeast(16L)

    fun updateHighlightCoords(lineIdx: Int, charIdx: Int, wordLength: Int) {
        lastHighlightedCoords.clear()
        lastHighlightedCoords.add(Pair(lineIdx, charIdx))
        for (i in 1 until wordLength) {
            val targetGlobalIdx = getGlobalIdx(lineIdx, charIdx) + i
            getCoordsFromGlobalIdx(targetGlobalIdx)?.let { lastHighlightedCoords.add(it) }
        }
    }

    fun formatDictionaryResults(
        matches: List<TermMatch>,
        gson: Gson,
        dictNames: Map<Int, String> = emptyMap()
    ): List<FormattedEntry> {
        // One entry per (term, dictionary): JMdict and KANJIDIC rows must
        // never merge into a single block. findByTexts returns priority
        // order, so groupBy preserves dictionary ranking.
        return matches.flatMap { (term, entries, chain) ->
            // #43: pitch rows (from any imported pitch dictionary — ours is a
            // built-in) are data, not entries, so they are split out and keyed
            // by reading rather than rendered. Reading-keyed means a kana
            // form's pitch still lands on the matching group.
            val (pitchEntries, termEntries) = entries.partition {
                PitchAccent.positionsOf(it.definitions) != null
            }
            val pitchByReading = pitchEntries
                .groupBy { PitchAccent.readingOf(it.definitions) ?: it.reading }
                .mapValues { (_, rows) ->
                    rows.flatMap { PitchAccent.positionsOf(it.definitions).orEmpty() }
                        .distinct()
                        .sorted()
                }
            if (termEntries.isEmpty()) return@flatMap emptyList()

            // One entry per (term, dictionary): JMdict and KANJIDIC rows must
            // never merge into a single block. findByTexts returns priority
            // order, so groupBy preserves dictionary ranking.
            termEntries.groupBy { it.dictionaryId }.map { (dictId, dictEntries) ->
            // Glossaries already rendered for an earlier reading of this word.
            val seenGlossaries = mutableSetOf<String>()
            val readingGroups = dictEntries.groupBy { it.reading }.map { (reading, readingEntries) ->
                val isKanjiEntry = readingEntries.firstOrNull()?.let { it.onyomi != null || it.kunyomi != null } ?: false
                val kanjiVariants = readingEntries.map { it.kanji }.distinct()
                
                val headwords = kanjiVariants.map { kanji ->
                    val entry = readingEntries.find { it.kanji == kanji } ?: readingEntries.first()
                    FormattedHeadword(kanji, entry.onyomi, entry.kunyomi)
                }

                val senseGroups = mutableListOf<FormattedSenseGroup>()
                var globalSenseNum = 1
                val groupSeenTags = mutableSetOf<String>()
                var currentGroupTags: List<String>? = null
                var currentGroupSenses = mutableListOf<FormattedSense>()

                fun flushGroup() {
                    val tagsToRender = currentGroupTags ?: return
                    if (currentGroupSenses.isEmpty()) return
                    val isForms = tagsToRender.any { it.equals("Forms", ignoreCase = true) || it.equals("Other forms", ignoreCase = true) }
                    senseGroups.add(FormattedSenseGroup(tagsToRender.filter { groupSeenTags.add(it) }, currentGroupSenses, isForms))
                    currentGroupSenses = mutableListOf()
                }

                for (e in readingEntries) {
                    val definitionsJson = try { gson.fromJson(e.definitions, Any::class.java) } catch (ex: Exception) { e.definitions }
                    val definitionsList = definitionsJson as? List<*> ?: listOf(definitionsJson)

                    val metaTags = mutableListOf<String>()
                    val senseTagsMap = mutableMapOf<Int, MutableList<String>>()
                    e.jlpt?.takeIf { it.isNotEmpty() }?.let { metaTags.add("jlpt: N$it") }
                    "grade:(\\s+)".toRegex().find(e.rules)?.groupValues?.get(1)?.let { metaTags.add("grade: $it") }

                    val segments = e.rules.split(" | ")
                    fun parseToMaps(s: String?) {
                        var currentSense: Int? = null
                        s?.split(" ")?.filter { it.isNotEmpty() }?.forEach { tag ->
                            val n = tag.toIntOrNull()
                            if (n != null) currentSense = n
                            else if (!tag.startsWith("grade:")) {
                                currentSense?.let { senseTagsMap.getOrPut(it) { mutableListOf() }.add(tag) } ?: metaTags.add(tag)
                            }
                        }
                    }
                    parseToMaps(segments.getOrNull(0))
                    parseToMaps(segments.getOrNull(2))

                    val tags = (metaTags + (senseTagsMap[1] ?: emptyList())).distinct()
                    val parsed = parseGlossary(definitionsList)

                    if (parsed.structured) {
                        // #88: Jitendex packs every sense into one row's glossary.
                        // Each structured sense group is its own visual group with
                        // its shared metadata as a header; the senses are numbered
                        // individually (a group can hold more than one), and the
                        // forms/attribution trailing blocks render after the last
                        // one, unnumbered.
                        flushGroup()
                        currentGroupTags = null
                        val last = parsed.groups.lastIndex
                        parsed.groups.forEachIndexed { i, group ->
                            senseGroups.add(FormattedSenseGroup(
                                tags = emptyList(),
                                senses = group.senses.map { FormattedSense(globalSenseNum++, it) },
                                isForms = false,
                                header = group.header,
                                trailing = if (i == last) parsed.trailing + group.trailing else group.trailing,
                            ))
                        }
                    } else if (currentGroupTags == null || tags == currentGroupTags) {
                        currentGroupTags = tags
                        currentGroupSenses.add(FormattedSense(globalSenseNum++, parsed.plain))
                    } else {
                        flushGroup()
                        currentGroupTags = tags
                        currentGroupSenses.add(FormattedSense(globalSenseNum++, parsed.plain))
                    }
                }
                flushGroup()

                FormattedReadingGroup(
                    reading,
                    headwords,
                    senseGroups,
                    isKanjiEntry,
                    pitchPositions = pitchByReading[reading].orEmpty(),
                    // A reading whose row repeats a glossary already rendered
                    // keeps its headword but not a second copy of the senses.
                    renderSenses = readingEntries.firstOrNull()?.definitions
                        ?.let { seenGlossaries.add(it) } ?: true,
                )
            }
            FormattedEntry(
                term,
                readingGroups,
                deinflection = chain,
                dictionaryName = dictNames[dictId],
                bookmark = bookmarkCandidate(dictId, readingGroups, dictEntries, dictNames)
            )
            }
        }
    }

    /**
     * #67: the headword the popup's bookmark toggle saves — the first headword
     * pair the block renders, plus a snapshot of this block's senses. Null when
     * the dictionary has no display name (the popup cannot label it, and that
     * name is the bookmark's identity).
     */
    private fun bookmarkCandidate(
        dictId: Int,
        readingGroups: List<FormattedReadingGroup>,
        dictEntries: List<DictionaryEntry>,
        dictNames: Map<Int, String>
    ): BookmarkCandidate? {
        val name = dictNames[dictId] ?: return null
        val headword = readingGroups.firstNotNullOfOrNull { group ->
            group.headwords.firstOrNull()?.let { hw -> hw.kanji to group.reading }
        } ?: return null
        return BookmarkCandidate(
            kanji = headword.first,
            reading = headword.second,
            dictionaryName = name,
            definitionsText = Definitions.plainAll(dictEntries.map { it.definitions })
        )
    }

    /**
     * JSON codec for the definition-format shim calls below. Separate from the
     * nullable service-supplied [gson]: unit tests construct this controller
     * with no arguments and call [parseDefinition] directly.
     */
    private val parseCodec = Gson()

    /**
     * #88: turn Yomitan structured content into [DefinitionNode]s — via the
     * single-sourced walker in `jpdict_core::definition_format` (package 04).
     * The Gson-parsed [data] is serialised back to JSON for the shim, which
     * returns the walked nodes as JSON; [nodeFromJson] maps that JSON into
     * the existing model. The mapping is presentation-only — no walker lives
     * on this side.
     *
     * [separator] is spliced between adjacent inline siblings: `", "` joins a
     * gloss list, `"\n"` separates a JMdict example's Japanese/English lines,
     * and `""` concatenates the fragments of one sentence. The walker fails
     * open, as before.
     */
    internal fun parseDefinition(data: Any?, separator: String = ", "): List<DefinitionNode> {
        val out = definitionFormatParse(parseCodec.toJson(data), separator)
        return (JsonParser.parseString(out) as? JsonArray)
            ?.map { nodeFromJson(it) }.orEmpty()
    }

    /**
     * Map one `DefinitionNodeJson` object (see
     * `jpdict_core::definition_format::DefinitionNodeJson`; `kind` selects the
     * variant) into the existing [DefinitionNode] model. Reads the Gson tree
     * directly — no reflective DTO, so nothing needs an R8 keep rule.
     */
    private fun nodeFromJson(el: JsonElement): DefinitionNode {
        val o = el.asJsonObject
        fun s(name: String): String? =
            (o.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString
        fun nodes(name: String): List<DefinitionNode> =
            (o.get(name) as? JsonArray)?.map { nodeFromJson(it) }.orEmpty()
        fun nested(name: String): List<List<DefinitionNode>> =
            (o.get(name) as? JsonArray)
                ?.map { inner -> (inner as JsonArray).map { nodeFromJson(it) } }
                .orEmpty()
        return when ((o.get("kind") as? JsonPrimitive)?.asString) {
            "ruby" -> DefinitionNode.Ruby(s("term").orEmpty(), s("reading").orEmpty(), isMini = true)
            "tag" -> DefinitionNode.Tag(s("text").orEmpty())
            "citation" -> DefinitionNode.Citation(s("text").orEmpty())
            "example" -> DefinitionNode.Example(
                s("japanese"),
                s("english"),
                (o.get("content") as? JsonArray)?.map { nodeFromJson(it) },
                nested("parts"),
            )
            "list" -> DefinitionNode.ListBlock(nested("items"), s("list_type"))
            "table" -> DefinitionNode.Table(
                (o.get("rows") as? JsonArray)
                    ?.map { row -> (row as JsonArray).map { cell -> (cell as JsonArray).map { nodeFromJson(it) } } }
                    .orEmpty(),
            )
            "group" -> DefinitionNode.Group(
                nodes("nodes"),
                (o.get("is_inline") as? JsonPrimitive)?.asBoolean == true,
            )
            else -> DefinitionNode.Text(s("text").orEmpty())
        }
    }

    /** #88: one Jitendex `sense-group` split for numbering. */
    private class ParsedSenseGroup(
        val header: List<DefinitionNode>,
        val senses: List<List<DefinitionNode>>,
        val trailing: List<DefinitionNode>,
    )

    /**
     * #88: a row's glossary, split for numbering — via
     * `jpdict_core::definition_format` (package 04), mapped back into the
     * model above.
     *
     * JMdict/KANJIDIC rows carry one sense per row, so [ParsedGlossary.structured]
     * is false and [ParsedGlossary.plain] is the whole row's node list.
     * Jitendex rows carry every sense in one glossary, so [ParsedGlossary.structured]
     * is true and [ParsedGlossary.groups] holds each `sense-group`;
     * [ParsedGlossary.trailing] holds the non-sense blocks (the forms table,
     * attribution) that must render unnumbered.
     */
    private class ParsedGlossary(
        val structured: Boolean,
        val groups: List<ParsedSenseGroup>,
        val plain: List<DefinitionNode>,
        val trailing: List<DefinitionNode>,
    )

    private fun parseGlossary(data: Any?): ParsedGlossary {
        val root =
            JsonParser.parseString(definitionFormatParseGlossary(parseCodec.toJson(data))).asJsonObject
        fun nodeList(el: JsonElement?): List<DefinitionNode> =
            (el as? JsonArray)?.map { nodeFromJson(it) }.orEmpty()
        val groups = (root.get("groups") as? JsonArray)?.map { g ->
            val go = g.asJsonObject
            ParsedSenseGroup(
                nodeList(go.get("header")),
                ((go.get("senses") as? JsonArray)?.map { s -> nodeList(s) }).orEmpty(),
                nodeList(go.get("trailing")),
            )
        }.orEmpty()
        return ParsedGlossary(
            structured = (root.get("structured") as? JsonPrimitive)?.asBoolean == true,
            groups = groups,
            plain = nodeList(root.get("plain")),
            trailing = nodeList(root.get("trailing")),
        )
    }
}
