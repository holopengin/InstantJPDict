package com.holopengin.instantjpdict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.holopengin.instantjpdict.util.BookmarkCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #89: the manual lookup screen — a search box and the same dictionary cards the
 * OCR overlay's popup shows.
 *
 * The screen exists because OCR is the app's only door otherwise: if the
 * recogniser misreads, or the text is in another app or the clipboard, there is
 * no way to look it up. It shares BOTH halves of the overlay's machine:
 *  - the lookup chain, through [OcrOverlayStateController.lookupText] — the same
 *    `prepareSearchCandidates` → `findByTexts` → #65 redirect BFS → `processResults`
 *    → `formatDictionaryResults` chain a tap runs, so the two paths cannot
 *    disagree about what a word resolves to;
 *  - the renderer, through [DictionaryResultView] — the same headwords, senses,
 *    tags, examples, tables, pitch line and citations, so a rendering fix lands
 *    on both without a second edit.
 *
 * Wiring is [OverlayEnvironment.prepare], exactly as the service and the share
 * activity call it, so the deinflector, the provider and the gson cannot drift
 * between hosts.
 *
 * Read-only by scope: the screen shows results and nothing else — no history, no
 * favourites, no tap-to-jump. The only two gestures a card carries are the ones
 * the renderer already had (long-press to copy the headword, the bookmark star),
 * so they behave as they do in the overlay.
 *
 * Lookups are live: every change to the box schedules one (debounced), a new
 * change supersedes the one in flight, and the chain itself runs off the main
 * thread — so the box never waits on a search, and Enter and the button are
 * conveniences rather than the only trigger.
 *
 * #108 adds the direction switch above the results: Japanese to English (the
 * original path) and English gloss to Japanese. Romaji is not a third
 * direction: it is an input spelling for Japanese, so the Japanese mode
 * converts ASCII to kana and falls back to the katakana spelling when the
 * hiragana form finds nothing. The direction is explicit and persisted because
 * Japanese and English are different indexes.
 *
 * Three ways in beyond the launcher (the home-screen button and the static
 * shortcut both land here through [MainActivity]):
 *  - `ACTION_SEND` + `text/plain` — a shared selection prefills the box and is
 *    looked up on create;
 *  - `ACTION_PROCESS_TEXT` (`text/plain`) — the selection toolbar's entry. The
 *    text is read from `EXTRA_PROCESS_TEXT` and the screen stays up with the
 *    results, exactly like a share. We never set a replacement result, because
 *    this is a lookup, not a text transformer — Back returns to the sender. It
 *    used to finish on the lookup's own frame, which meant the cards were built
 *    and then the window closed before they could be drawn;
 *  - an ordinary explicit launch from the home screen, with an empty box.
 */
class ManualLookupActivity : AppCompatActivity() {

    /** The wiring host's scope, mirroring [ShareImageActivity.overlayScope]: the
     *  optional #44 table loads and the bookmark writes run here, cancelled with
     *  the activity. */
    private val lookupScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val controller = OcrOverlayStateController()

    private lateinit var input: TextInputEditText
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var resultsContent: LinearLayout
    private var mode: ManualLookupMode = ManualLookupMode.DEFAULT
    private var lookupJob: Job? = null

    /**
     * EN→JP scans the dictionary, so repeating a query (mode switch, a
     * backspace and retype, a selection returning to the same text) should not
     * repeat the scan. The activity is short-lived, so a small cache is enough.
     */
    private val definitionCache = LruCache<String, List<FormattedEntry>>(16)

    /**
     * #89: the bookmark star writes the same store the overlay uses; the manual
     * screen has no in-window confirmation channel, so it uses the platform toast
     * (a normal foreground app's toast IS visible, unlike the overlay's).
     */
    private val resultHooks = DictionaryResultView.Hooks(
        onCopyHeadword = { target ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(target.clipLabel, target.value))
            Toast.makeText(this, LookupCopyTargets.copiedConfirmation(target.value), Toast.LENGTH_SHORT).show()
        },
        bookmarkScope = lookupScope,
        onBookmarkChanged = { candidate: BookmarkCandidate, nowSaved: Boolean ->
            Toast.makeText(
                this,
                if (nowSaved) "Bookmarked ${candidate.kanji}" else "Removed ${candidate.kanji}",
                Toast.LENGTH_SHORT,
            ).show()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        mode = ManualLookupMode.read(this)

        OverlayEnvironment.prepare(this, controller, lookupScope)

        // A selection-menu lookup never returns a result: we do not set one, and
        // Back leaves the sender with RESULT_CANCELED. It stays on screen like a
        // share so the cards can actually be read; `EXTRA_PROCESS_TEXT_READONLY`
        // only says the sender's text cannot be replaced, not that this screen
        // should close itself.
        if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            Log.d(TAG, "process-text lookup, readOnly=${isProcessTextReadOnly()}")
        }

        val ui = HarbourUi.of(this)
        setContentView(buildRoot(ui))
        applyIncomingText()
    }

    override fun onDestroy() {
        lookupJob?.cancel()
        lookupScope.cancel()
        super.onDestroy()
    }

    // ---- incoming text ----

    /**
     * The text this launch carries, or null for an ordinary launch.
     *
     * `ACTION_PROCESS_TEXT` is the selection toolbar's shape and carries
     * `EXTRA_PROCESS_TEXT`; `ACTION_SEND` is a share and carries `EXTRA_TEXT`.
     * Both are `text/plain` (the manifest filters say so), so either is a plain
     * string to look up.
     */
    private fun readIncomingText(): String? {
        return when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                @Suppress("DEPRECATION")
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            else -> null
        }
    }

    /** True when the selection toolbar marked this lookup read-only. */
    private fun isProcessTextReadOnly(): Boolean =
        intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) == true

    private fun applyIncomingText() {
        val text = readIncomingText()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText(text)
        input.setSelection(text.length)
        runLookup(text)
    }

    // ---- the screen ----

    private fun buildRoot(ui: HarbourUi): View {
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.manual_lookup_title)
            // Back closes the screen for every launch shape. A selection/share
            // launch returns to the app that sent the text; a home-screen or
            // shortcut launch returns to the screen beneath it.
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "Back"
        }

        input = TextInputEditText(this).apply {
            hint = getString(mode.hintRes)
            // Single-line + the search IME action: the soft keyboard's key becomes
            // "Search", which is the submit the spec asks for. A multi-line field
            // would show Enter instead and never surface the search action.
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submit()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    if (text.isBlank()) {
                        // Empty/whitespace clears: the results of the previous
                        // lookup would otherwise sit under an empty box claiming
                        // to belong to it. Clearing is not a lookup, so no "No
                        // results found" — that message is for a search that
                        // found nothing. A pending live lookup is cancelled with
                        // the results: there is nothing left to search for.
                        lookupJob?.cancel()
                        clearResults()
                    } else {
                        // Live lookup — no Enter and no button needed. Debounced,
                        // and each run supersedes the one in flight (runLookup
                        // cancels it), so a burst of typing is one search after
                        // the pause rather than one per key.
                        runLookup(text, LIVE_LOOKUP_DEBOUNCE_MS)
                    }
                }
            })
        }

        val fieldCard = ui.card(fill = ui.surfaceHigh, radiusDp = 16)
        fieldCard.addView(ui.cardBody(padH = 12, padV = 12).apply {
            addView(LinearLayout(this@ManualLookupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(input)
                addView(ui.iconButton(R.drawable.ic_search, "Look up").apply {
                    setOnClickListener { submit() }
                })
            })
        })

        resultsContent = LinearLayout(this).apply {
            id = R.id.manual_lookup_results
            orientation = LinearLayout.VERTICAL
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldCard)
            addView(buildModeToggle(ui))
            addView(resultsContent)
        }

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(0, bars.top, 0, 0)
            scroll.setPadding(ui.dp(20) + bars.left, ui.dp(6), ui.dp(20) + bars.right, ui.dp(24) + bars.bottom)
            insets
        }
        return root
    }

    /**
     * #108: the explicit direction switch. Three modes, one checked at a time,
     * persisted immediately. Changing it re-runs the current box text so the
     * result on screen always belongs to the selected mode.
     */
    private fun buildModeToggle(ui: HarbourUi): MaterialButtonToggleGroup {
        modeGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = ui.dp(8)
                bottomMargin = ui.dp(8)
            }
        }

        ManualLookupMode.entries.forEach { candidate ->
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = getString(candidate.labelRes)
                isAllCaps = false
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                isCheckable = true
                id = View.generateViewId()
                tag = candidate
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            modeGroup.addView(button)
            if (candidate == mode) modeGroup.check(button.id)
        }

        modeGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selected = group.findViewById<MaterialButton>(checkedId)?.tag as? ManualLookupMode
                ?: return@addOnButtonCheckedListener
            if (selected == mode) return@addOnButtonCheckedListener
            mode = selected
            ManualLookupMode.write(this, selected)
            updateHint()
            val text = input.text?.toString().orEmpty()
            if (text.isBlank()) clearResults() else runLookup(text)
        }
        return modeGroup
    }

    private fun updateHint() {
        input.hint = getString(mode.hintRes)
    }

    // ---- lookup ----

    private fun submit() {
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) {
            clearResults()
            return
        }
        // Dismiss the IME so the results are not half-covered while they appear.
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        runLookup(text)
    }

    /**
     * Run the shared chain off the main thread, like the overlay's lookup.
     *
     * A new run cancels the one in flight, so a fast typist never sees an older
     * lookup's cards replace a newer one's — and the [debounceMs] wait is part of
     * that same cancellable job, so a keystroke can never queue work behind
     * another search. The box itself is never blocked by any of this:
     * [OcrOverlayStateController.lookupText] runs on [Dispatchers.IO], so typing
     * stays on the main thread's frame budget whatever a query costs.
     *
     * The result is checked against the box before it is shown: a job that was
     * already past its cancellation check when the text changed (the IO result
     * landing in the same frame as a keystroke) must not paint results for a
     * string the user has moved on from.
     */
    private fun runLookup(text: String, debounceMs: Long = 0) {
        lookupJob?.cancel()
        // The mode is read once, on the main thread, so a switch in the middle
        // of a search cannot send the result to the wrong panel. The listener
        // starts a fresh run when the mode changes, and this check drops any
        // result that was already past cancellation.
        val activeMode = mode
        lookupJob = lookupScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val started = System.currentTimeMillis()
            val entries = withContext(Dispatchers.IO) {
                when (activeMode) {
                    ManualLookupMode.JP_TO_EN -> controller.lookupJapaneseText(text)
                    ManualLookupMode.EN_TO_JP -> {
                        val key = DefinitionSearch.normalizeQuery(text)
                        val cached = key?.let { definitionCache.get(it) }
                        if (cached != null) {
                            cached
                        } else {
                            val fresh = controller.lookupDefinitionText(text)
                            if (key != null) definitionCache.put(key, fresh)
                            fresh
                        }
                    }
                }
            }
            Log.d(
                TAG,
                "lookup $activeMode took ${System.currentTimeMillis() - started}ms, " +
                    "${entries.size} entries, ${text.length} chars",
            )
            if (activeMode != mode) return@launch
            if (input.text?.toString() != text) return@launch
            // One exit path for every launch shape. A process-text lookup must
            // not finish here: `finish()` on the same main-thread turn as
            // `showResults` tears the window down before a frame is drawn, so the
            // screen closed with the results never shown. Leaving it up also
            // matches a share: Back returns to the sender without a replacement.
            showResults(entries)
        }
    }

    /** Empty the results area — for a box that was cleared, not a failed search. */
    private fun clearResults() {
        resultsContent.removeAllViews()
    }

    private fun showResults(entries: List<FormattedEntry>) {
        resultsContent.removeAllViews()
        if (entries.isEmpty()) {
            // A search genuinely found nothing: say so through the shared component,
            // so this message matches the overlay's.
            resultsContent.addView(DictionaryResultView.buildEmpty(this, 0))
            return
        }
        entries.forEach { entry ->
            resultsContent.addView(DictionaryResultView.buildEntry(this, entry, resultHooks))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second share/selection lands on this instance when it is already on
        // top; re-read and look up rather than leaving the previous result up.
        input.text = null
        applyIncomingText()
    }

    private companion object {
        const val TAG = "ManualLookupActivity"

        /**
         * How long the box must be quiet before a keystroke's lookup runs. Live
         * enough to feel immediate, long enough that a burst of typing is one
         * search rather than one per key — and every run is cancellable, so the
         * wait never queues work.
         */
        const val LIVE_LOOKUP_DEBOUNCE_MS = 200L
    }
}
