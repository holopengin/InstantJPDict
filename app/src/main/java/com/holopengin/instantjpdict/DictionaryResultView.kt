package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.holopengin.instantjpdict.util.BookmarkCandidate
import com.holopengin.instantjpdict.util.DeinflectionChain
import com.holopengin.instantjpdict.util.FuriganaAligner
import com.holopengin.instantjpdict.util.JapaneseUtil
import com.holopengin.instantjpdict.util.PitchAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * #89: the dictionary results panel's construction, single-sourced.
 *
 * The overlay ([OcrOverlayView]) and the manual lookup screen
 * ([ManualLookupActivity]) both render [FormattedEntry] lists through this object,
 * so a fix to a sense, a tag, a table, a citation or the pitch line lands on both
 * without a second edit. The overlay keeps its own concerns — the tap gestures,
 * the highlights, the scroll wiring, the view cache, the crop bitmaps — and
 * supplies them through [Hooks]; nothing OCR- or viewport-shaped is in here.
 *
 * The construction is deliberately callback-free apart from two gesture seams
 * (the entry's long-press copy and its bookmark toggle): the VOICE of the cards
 * (which view, which colour, which size, which arrangement) is the shared part,
 * and the two gestures are wired by whichever host is showing them. A host that
 * passes no hook still gets the exact same views, silently inert.
 */
internal object DictionaryResultView {

    /**
     * Everything an entry card needs from its host that is not pure rendering:
     * copy its dict-form headword on long-press, the scope the bookmark write
     * runs on, and the callback that confirms a bookmark change. All default to
     * inert, so a host that wants read-only cards passes nothing.
     *
     * The bookmark WRITE ([BookmarkStore.toggle]) stays here — it is part of the
     * card's behaviour, not of any one host — and only the feedback differs:
     * the overlay confirms inside its own window (a toast is drawn beneath an
     * accessibility overlay), the manual screen uses a toast.
     */
    class Hooks(
        val onCopyHeadword: (CopyTarget) -> Unit = {},
        val bookmarkScope: CoroutineScope? = null,
        val onBookmarkChanged: (BookmarkCandidate, Boolean) -> Unit = { _, _ -> },
    )

    /** The panel's "No results found" line, with the host's own top clearance. */
    fun buildEmpty(context: Context, topClearancePx: Int): View = TextView(context).apply {
        text = "No results found"
        setTextColor(Color.GRAY)
        gravity = Gravity.CENTER
        textSize = 16f
        OverlayFont.applySystem(context, this)
        setPadding(0, 150 + topClearancePx, 0, 0)
    }

    /**
     * Build one entry's card — headwords, deinflection row, senses, source
     * caption — the way the overlay's popup always built it. The caller stacks
     * these into a scroll content and wires the hooks it cares about.
     */
    fun buildEntry(context: Context, entry: FormattedEntry, hooks: Hooks): View {
        val termSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 40)
        }
        // #67 follow-up: the bookmark star lives in the entry's top-right corner,
        // overlaying the headword block rather than taking a line of its own — the
        // FrameLayout below is what lets it float without adding height to the entry.
        val headwordBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        headwordBlock.addView(buildHeadwordBlock(context, entry))
        // #66 follow-up: long-pressing the entry copies its dict-form headword.
        // Wired on the block, not on a single TextView, so the whole entry is the
        // target — the headword, its reading rows and the pitch line all respond,
        // which is what "long-press the headword in the dictionary" means to a finger.
        LookupCopyTargets.headwordTarget(listOf(entry))?.let { target ->
            headwordBlock.isClickable = true
            headwordBlock.setOnLongClickListener {
                hooks.onCopyHeadword(target)
                true
            }
        }
        createBookmarkCorner(context, entry, hooks)?.let { star ->
            // A FrameLayout is what lets the star float over the headwords: a
            // LinearLayout would give it a row and grow the entry.
            termSection.addView(FrameLayout(context).apply {
                addView(headwordBlock, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                // The star is a sibling of the long-pressable block, laid over it,
                // so the two gestures stay separate: a tap on the star toggles the
                // bookmark, and a tap anywhere else in the entry starts the block's
                // long-press. Making the star a child of the block instead would put
                // both handlers on one view, where Android would deliver the same
                // stream to both.
                addView(star, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ))
            })
        } ?: termSection.addView(headwordBlock)
        // #62: chain row directly below the headwords, above the senses.
        // Direct matches (deinflection == null) render as before.
        entry.deinflection?.let { chain ->
            if (chain.steps.isNotEmpty()) {
                termSection.addView(createDeinflectionRow(context, chain, entry.term))
            }
        }
        entry.readingGroups.forEach { group ->
            // A reading that repeats an already-rendered glossary shows its
            // headword but not a second copy of the senses (and examples).
            if (!group.renderSenses) return@forEach
            renderSensesForReading(context, termSection, group)

            termSection.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 20 }
                setBackgroundColor(Color.DKGRAY)
                alpha = 0.3f
            })
        }
        // Dictionary source, bottom of the entry: entries never mix
        // dictionaries, so one caption per section is exact.
        entry.dictionaryName?.let { name ->
            termSection.addView(TextView(context).apply {
                text = name
                setTextColor(Color.GRAY)
                textSize = 12f
                OverlayFont.applySystem(context, this)
                gravity = Gravity.END
                setPadding(0, 2, 0, 0)
            })
        }
        return termSection
    }

    /**
     * #67 follow-up: the bookmark star for one entry's top-right corner.
     *
     * Replaces a full-width "☆ Bookmark" row. It is a single glyph, padded only
     * enough to stay a comfortable tap target, and it is laid out with a
     * `FrameLayout` rather than a `LinearLayout` by the caller so it floats over
     * the headwords instead of taking a row of its own. Returns null when the
     * entry carries no bookmark identity ([FormattedEntry.bookmark]), which is
     * also what keeps an entry that cannot be saved from showing a dead control.
     */
    private fun createBookmarkCorner(context: Context, entry: FormattedEntry, hooks: Hooks): View? {
        val candidate = entry.bookmark ?: return null
        val key = candidate.key
        val savedColor = Color.rgb(255, 214, 0)
        return TextView(context).apply {
            isClickable = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            textSize = 22f
            setPadding(
                (6 * resources.displayMetrics.density).toInt(),
                (2 * resources.displayMetrics.density).toInt(),
                (2 * resources.displayMetrics.density).toInt(),
                (2 * resources.displayMetrics.density).toInt(),
            )
            // Dictionary-panel element, so the system face like the headwords
            // beside it. As well as consistency this keeps the star's height on
            // the platform metric: the bundled face's 1.448 em line box could
            // exceed a short entry's headword block and grow the entry, which is
            // the one thing this corner placement exists to avoid.
            OverlayFont.applySystem(context, this)
            fun renderState(state: Boolean) {
                text = BookmarkGlyph.of(state)
                setTextColor(if (state) savedColor else Color.LTGRAY)
            }
            renderState(BookmarkStore.isBookmarked(key))
            setOnClickListener {
                val scope = hooks.bookmarkScope ?: return@setOnClickListener
                val appContext = context.applicationContext
                scope.launch {
                    val nowSaved = BookmarkStore.toggle(appContext, candidate)
                    renderState(nowSaved)
                    hooks.onBookmarkChanged(candidate, nowSaved)
                }
            }
        }
    }

    /** #62: compact deinflection chain row, e.g. "食べた → 食べる" + past chip.
     *  Pure view construction (no Android-string resources) so the label
     *  logic stays unit-testable via [DeinflectionChain.label]. */
    private fun createDeinflectionRow(context: Context, chain: DeinflectionChain, term: String): View {
        return FlowLayout(context).apply {
            setPadding(0, 1, 0, 1)
            addView(TextView(context).apply {
                text = "${chain.surface} → $term"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                OverlayFont.applySystem(context, this)
                setPadding(0, 0, 12, 0)
                includeFontPadding = false
            })
            chain.steps.forEach { step -> addView(createTagView(context, step)) }
        }
    }

    /**
     * Headword block for one entry. Kanji (KANJIDIC) entries render their big
     * glyph with 訓/音 rows; ordinary term entries render every headword with
     * its own reading in ONE comma-separated flow, so repeated kanji across
     * reading groups read like the multi-kanji kana-lookup case instead of
     * stacking.
     */
    private fun renderHeadwordSection(context: Context, container: LinearLayout, groups: List<FormattedReadingGroup>) {
        val headwordList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 2)
        }

        val kanjiEntries = groups.filter { it.isKanjiEntry }
        kanjiEntries.forEach { group ->
            group.headwords.forEach { hw ->
                val kanjiHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 1, 0, 1)
                }
                kanjiHeader.addView(TextView(context).apply {
                    text = hw.kanji
                    setTextColor(Color.CYAN)
                    textSize = 48f
                    OverlayFont.applySystem(context, this, bold = true)
                    setPadding(0, 0, 30, 0)
                })
                val readingStack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                // 訓 (kun) above 音 (on); shared row styling.
                hw.kunyomi?.takeIf { it.isNotEmpty() }?.let {
                    readingStack.addView(createKunOnRow(context, "訓", JapaneseUtil.splitKanaList(it).joinToString("、")))
                }
                hw.onyomi?.takeIf { it.isNotEmpty() }?.let {
                    readingStack.addView(createKunOnRow(context, "音", JapaneseUtil.splitKanaList(it).joinToString("、"), topMarginPx = kunOnRowTightenPx))
                }
                kanjiHeader.addView(readingStack)
                headwordList.addView(kanjiHeader)
            }
        }

        // Every (kanji, reading) pair across the remaining reading groups, one
        // flow row, comma separated. Entries are single-dictionary so this is
        // normally all-or-nothing with the kanji branch above.
        val termGroups = groups.filter { !it.isKanjiEntry }
        if (termGroups.isNotEmpty()) {
            val pairs = termGroups.flatMap { g -> g.headwords.map { it.kanji to g.reading } }
            val flow = FlowLayout(context).apply { setPadding(0, 0, 0, 0) }
            // #68: if any headword renders a ruby row, reserve the same ruby
            // space for all of them so baselines align in the shared flow.
            val reserveRubySpace = pairs.any { (kanji, reading) -> kanji != reading }
            pairs.forEachIndexed { i, (kanji, reading) ->
                flow.addView(createRubyView(context, kanji, reading, reserveRubySpace = reserveRubySpace))
                if (i < pairs.size - 1) {
                    flow.addView(TextView(context).apply { text = "、"; setTextColor(Color.GRAY); textSize = 24f; OverlayFont.applySystem(context, this); setPadding(5, 0, 5, 0) })
                }
            }
            headwordList.addView(flow)

            // #43: pitch accents for every reading of this entry, on one
            // comma-separated line. Gated by the MainActivity toggle; with it
            // off, or no reading carrying pitch data, the popup is unchanged.
            if (PitchAccent.isEnabled(context)) {
                val items = termGroups.flatMap { group ->
                    group.pitchPositions.map { PitchAccentLine.Item(group.reading, it) }
                }
                PitchAccentLine.build(context, items, pitchTextSizePx(context))?.let { line ->
                    OverlayFont.applySystem(context, line)
                    headwordList.addView(line, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = pitchRowTopMarginPx })
                }
            }
        }
        container.addView(headwordList)
    }

    /**
     * #67-follow-up: the headwords and their chrome, returned as one view so the
     * caller can float the bookmark star over it in a FrameLayout. Same content
     * [renderHeadwordSection] always built (kanji branch, term flow, pitch line);
     * it is a function returning the container rather than one mutating a
     * caller-supplied parent, because the star needs the container's identity.
     */
    private fun buildHeadwordBlock(context: Context, entry: FormattedEntry): View {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        renderHeadwordSection(context, container, entry.readingGroups)
        return container
    }

    private fun renderSensesForReading(context: Context, container: LinearLayout, group: FormattedReadingGroup) {
        group.senseGroups.forEach { senseGroup ->
            renderSenseGroup(context, container, senseGroup)
        }
    }

    private fun createTagView(context: Context, tag: String, category: String = "general"): View {
        val color = when {
            category == "pos" || tag.startsWith("v") || tag == "adj-i" || tag == "adj-na" -> Color.parseColor("#3a5a7a")
            tag == "n" || tag == "adv" || tag == "pn" -> Color.parseColor("#3a7a5a")
            category == "meta" || tag.startsWith("jlpt") || tag.startsWith("grade") || tag == "★" -> Color.parseColor("#7a3a3a")
            else -> Color.parseColor("#444444")
        }
        return TextView(context).apply {
            text = tag
            setTextColor(Color.WHITE)
            textSize = 10f
            OverlayFont.applySystem(context, this, bold = true)
            setPadding(12, 2, 12, 2)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 6f
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 12, 0)
            }
            includeFontPadding = false
        }
    }

    private fun createRubyView(context: Context, term: String, reading: String, isMini: Boolean = false, reserveRubySpace: Boolean = false): View {
        if (term == reading) {
            if (!reserveRubySpace) return createBaseTextView(context, term, isMini)
            // #68: mixed group — reserve the same ruby row a furigana-bearing
            // sibling has (empty, identical metrics) so baselines align.
            // Widths are unchanged (stack width = base width either way), so
            // FlowLayout line-wrapping is unaffected.
            return createSpacerRubyView(context, term, isMini)
        }
        // Minimal furigana (#55): ruby only over kanji spans, okurigana as
        // plain base text. Falls back to full-reading ruby when unalignable.
        val segments = FuriganaAligner.align(term, reading)
        if (segments == null || segments.none { it.ruby != null }) {
            return createFullRubyView(context, term, reading, isMini)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            var firstRubyIndex = -1
            segments.forEach { seg ->
                if (seg.ruby == null) {
                    addView(createBaseTextView(context, seg.base, isMini))
                } else {
                    if (firstRubyIndex == -1) firstRubyIndex = childCount
                    addView(createRubyStackView(context, seg.base, seg.ruby, isMini))
                }
            }
            // #68: a horizontal LinearLayout reports no baseline (-1) by
            // default, which makes FlowLayout bottom-align it instead of
            // baseline-aligning it with sibling ruby stacks. Point at the
            // first ruby stack so the row shares one baseline. Measurement
            // is untouched, so wrapping is identical.
            if (firstRubyIndex != -1) baselineAlignedChildIndex = firstRubyIndex
        }
    }

    /**
     * DESIGN DEPARTURE 2026-09-21 (maintainer-ordered, mirrored on the PC
     * app): body ruby (`isMini`, i.e. every definition/example run) renders
     * WHITE and regular to match the surrounding body typeface, while
     * full-size headword/term display keeps bold cyan. The furigana row
     * stays light gray either way. Do NOT "fix" this back to bold cyan.
     */
    private fun createBaseTextView(context: Context, term: String, isMini: Boolean): TextView {
        val style = RubyBaseStyle.forMini(isMini)
        return TextView(context).apply {
            text = term
            setTextColor(style.baseColor)
            textSize = if (isMini) 15f else 32f
            OverlayFont.applySystem(context, this, bold = style.bold)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }
    }

    private fun createFullRubyView(context: Context, term: String, reading: String, isMini: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isBaselineAligned = true

            addView(TextView(context).apply {
                text = reading
                setTextColor(Color.LTGRAY)
                textSize = if (isMini) 9f else 13f
                OverlayFont.applySystem(context, this)
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(createBaseTextView(context, term, isMini).apply { gravity = Gravity.CENTER })
            baselineAlignedChildIndex = 1
        }
    }

    /**
     * One 訓 / 音 row — label (12f, GRAY) beside its readings (13f, LTGRAY),
     * used by the kanji (KANJIDIC) entry branch. [topMarginPx] pulls
     * consecutive rows closer (negative tightens).
     */
    private fun createKunOnRow(context: Context, label: String, readings: String, topMarginPx: Int = 0): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = topMarginPx }
            addView(TextView(context).apply {
                text = label
                setTextColor(Color.GRAY)
                textSize = 12f
                OverlayFont.applySystem(context, this)
                setPadding(0, 0, 12, 0)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = readings
                setTextColor(Color.LTGRAY)
                // Same size as the furigana rows (non-mini ruby).
                textSize = 13f
                OverlayFont.applySystem(context, this)
                includeFontPadding = false
                setLineSpacing(0f, kunOnLineSpacingMult)
            })
        }
    }

    /** Vertical gap applied above every 訓/音 row after the first (#69). */
    private const val kunOnRowTightenPx = -4
    /**
     * Wrapped-line spacing inside long readings values (#69): long 訓/音
     * lines must sit no looser than the gap between the rows themselves.
     */
    private const val kunOnLineSpacingMult = 0.85f
    /** Pitch-row typography (#43): matches the furigana reading size. */
    private fun pitchTextSizePx(context: Context): Float =
        13f * context.resources.displayMetrics.scaledDensity
    private const val pitchRowTopMarginPx = 2

    private fun createRubyStackView(context: Context, base: String, ruby: String, isMini: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isBaselineAligned = true

            addView(TextView(context).apply {
                text = ruby
                setTextColor(Color.LTGRAY)
                textSize = if (isMini) 9f else 13f
                OverlayFont.applySystem(context, this)
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(createBaseTextView(context, base, isMini).apply { gravity = Gravity.CENTER })
            baselineAlignedChildIndex = 1
        }
    }

    /** #68: spacer twin of [createRubyStackView] for ruby-less headwords in
     * mixed groups. Identical config with a non-breaking-space (U+00A0) ruby
     * row, so it measures exactly like a real stack (same height in both
     * sizes) while rendering nothing above the base text. Widths match the
     * plain base view, so FlowLayout wrapping is unaffected. */
    private fun createSpacerRubyView(context: Context, term: String, isMini: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isBaselineAligned = true

            addView(TextView(context).apply {
                text = " "
                setTextColor(Color.LTGRAY)
                textSize = if (isMini) 9f else 13f
                OverlayFont.applySystem(context, this)
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(createBaseTextView(context, term, isMini).apply { gravity = Gravity.CENTER })
            baselineAlignedChildIndex = 1
        }
    }

    private fun renderSenseGroup(context: Context, container: LinearLayout, senseGroup: FormattedSenseGroup) {
        if (senseGroup.senses.isEmpty()) return

        if (senseGroup.tags.isNotEmpty()) {
            val header = FlowLayout(context).apply { setPadding(20, 15, 0, 5) }
            senseGroup.tags.forEach { header.addView(createTagView(context, it)) }
            container.addView(header)
        }
        // #88: Jitendex group metadata (part of speech, field, usage notes) is
        // structured content rather than a string tag, and belongs to the group.
        if (senseGroup.header.isNotEmpty()) {
            val header = FlowLayout(context).apply { setPadding(20, 15, 0, 5) }
            renderDefinition(context, header, senseGroup.header)
            container.addView(header)
        }

        if (senseGroup.isForms) {
            val table = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 5, 10, 5)
            }
            senseGroup.senses.forEach { sense ->
                val row = FlowLayout(context).apply { setPadding(0, 5, 0, 5) }
                renderDefinition(context, row, sense.nodes)
                table.addView(row)
            }
            container.addView(table)
        } else {
            senseGroup.senses.forEach { sense ->
                val senseLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(30, 5, 10, 5)
                }
                senseLayout.addView(TextView(context).apply {
                    text = "${sense.index}. "
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    OverlayFont.applySystem(context, this)
                    setPadding(0, 0, 10, 0)
                })

                val contentContainer = FlowLayout(context).apply {
                    setPadding(0, 0, 0, 15)
                }
                renderDefinition(context, contentContainer, sense.nodes)
                senseLayout.addView(contentContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                container.addView(senseLayout)
            }
        }

        // #88: the forms table and attribution trail the senses, unnumbered.
        if (senseGroup.trailing.isNotEmpty()) {
            val trailer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 0, 10, 5)
            }
            renderDefinition(context, trailer, senseGroup.trailing)
            container.addView(trailer)
        }
    }

    private fun renderDefinition(context: Context, container: ViewGroup, nodes: List<DefinitionNode>) {
        var i = 0
        while (i < nodes.size) {
            when (val node = nodes[i]) {
                is DefinitionNode.Text -> {
                    val sb = StringBuilder()
                    var j = i
                    while (j < nodes.size && nodes[j] is DefinitionNode.Text) {
                        sb.append((nodes[j] as DefinitionNode.Text).text)
                        j++
                    }
                    container.addView(TextView(context).apply {
                        text = sb.toString()
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        OverlayFont.applySystem(context, this)
                        includeFontPadding = false
                    })
                    i = j
                }
                is DefinitionNode.Ruby -> {
                    container.addView(createRubyView(context, node.term, node.reading, node.isMini))
                    i++
                }
                is DefinitionNode.Tag -> {
                    container.addView(createTagView(context, node.text, node.category))
                    i++
                }
                is DefinitionNode.Citation -> {
                    // #88 follow-up: the source line reads as a footnote — small
                    // and faint, not definition-weight text.
                    container.addView(TextView(context).apply {
                        text = node.text
                        setTextColor(Color.argb(120, 255, 255, 255))
                        textSize = 11f
                        OverlayFont.applySystem(context, this)
                        includeFontPadding = false
                        setPadding(0, 8, 0, 0)
                    })
                    i++
                }
                is DefinitionNode.Example -> {
                    val box = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(30, 15, 30, 25)
                        background = GradientDrawable().apply {
                            setColor(Color.argb(10, 255, 255, 255))
                            setStroke(3, Color.argb(80, 255, 255, 255))
                            cornerRadius = 12f
                        }
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 15, 0, 15) }
                    }
                    if (node.japanese != null) {
                        box.addView(TextView(context).apply { text = node.japanese; setTextColor(Color.WHITE); textSize = 16f; OverlayFont.applySystem(context, this); setPadding(0, 0, 0, 10) })
                        node.english?.let { en -> box.addView(TextView(context).apply { text = en; setTextColor(Color.LTGRAY); textSize = 14f; OverlayFont.applySystem(context, this) }) }
                    } else if (node.parts.isNotEmpty()) {
                        // #88: Jitendex example — the Japanese sentence (with
                        // ruby) on its own line, the translation on the next.
                        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                        node.parts.forEach { part ->
                            val flow = FlowLayout(context)
                            renderDefinition(context, flow, part)
                            column.addView(flow)
                        }
                        box.addView(column)
                    } else if (node.content != null) {
                        val flow = FlowLayout(context)
                        renderDefinition(context, flow, node.content)
                        box.addView(flow)
                    }
                    container.addView(box)
                    i++
                }
                is DefinitionNode.ListBlock -> {
                    val block = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 10) }
                    }
                    node.items.forEach { itemNodes ->
                        val itemRow = FlowLayout(context).apply { setPadding(0, 0, 0, 5) }
                        renderDefinition(context, itemRow, itemNodes)
                        block.addView(itemRow)
                    }
                    container.addView(block)
                    i++
                }
                is DefinitionNode.Table -> {
                    if (node.rows.isNotEmpty()) container.addView(createDefinitionTable(context, node.rows))
                    i++
                }
                is DefinitionNode.Group -> {
                    val groupContainer = if (node.isInline) FlowLayout(context) else LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    if (groupContainer is FlowLayout) {
                        renderDefinition(context, groupContainer, node.nodes)
                    } else {
                        // If it's a vertical group, each child should still be rendered as a flow if it has inline elements
                        // But for simplicity, let's just use a nested FlowLayout for everything for now
                        val flow = FlowLayout(context)
                        renderDefinition(context, flow, node.nodes)
                        (groupContainer as LinearLayout).addView(flow)
                    }
                    container.addView(groupContainer)
                    i++
                }
            }
        }
    }

    /**
     * #88: render a structured-content table as a real grid — Jitendex's
     * variant-forms table is the reason it exists; the previous renderer
     * skipped tables entirely, which blanked the whole forms block.
     *
     * Rows are laid out as equal-weight columns so a header and its data cells
     * stay aligned without knowing column widths ahead of time. Each cell is
     * its own [FlowLayout], so ruby and the form glyphs (◇ ▽ ★ ✕ 古 旧) keep
     * their inline layout inside the cell.
     *
     * The card carries the example box's palette (a 10-alpha white fill under an
     * 80-alpha white stroke); neighbouring cells share a single rule instead of
     * each drawing its own rounded box, so the grid reads as one table rather
     * than a tray of chips. The outer stroke is the container's, the interior
     * rules are thin [divider] views.
     */
    private fun createDefinitionTable(context: Context, rows: List<List<List<DefinitionNode>>>): View {
        val border = Color.argb(80, 255, 255, 255)
        val table = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(10, 255, 255, 255))
                setStroke(2, border)
                cornerRadius = 12f
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 10, 0, 10) }
        }
        val columns = rows.maxOfOrNull { it.size } ?: 0
        rows.forEachIndexed { rowIndex, cells ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            for (column in 0 until columns) {
                if (column > 0) row.addView(divider(context, border, vertical = true))
                val cell = FlowLayout(context).apply {
                    setPadding(10, 8, 10, 8)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                renderDefinition(context, cell, cells.getOrNull(column).orEmpty())
                row.addView(cell)
            }
            table.addView(row)
            if (rowIndex < rows.lastIndex) table.addView(divider(context, border, vertical = false))
        }
        return table
    }

    /** #88: one grid rule, shared by the two cells it separates. */
    private fun divider(context: Context, color: Int, vertical: Boolean): View = View(context).apply {
        setBackgroundColor(color)
        layoutParams = if (vertical) {
            LinearLayout.LayoutParams(2, ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2)
        }
    }
}
