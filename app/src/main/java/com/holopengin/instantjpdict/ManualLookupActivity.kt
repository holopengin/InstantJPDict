package com.holopengin.instantjpdict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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
import com.google.android.material.textfield.TextInputEditText
import com.holopengin.instantjpdict.util.BookmarkCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Three ways in beyond the launcher (the home-screen button and the static
 * shortcut both land here through [MainActivity]):
 *  - `ACTION_SEND` + `text/plain` — a shared selection prefills the box and is
 *    looked up on create;
 *  - `ACTION_PROCESS_TEXT` (`text/plain`) — the selection toolbar's entry. The
 *    text is read from `EXTRA_PROCESS_TEXT`; `EXTRA_PROCESS_TEXT_READONLY` is
 *    respected by never returning a replacement, because this is a lookup, not a
 *    text transformer;
 *  - an ordinary explicit launch from the home screen, with an empty box.
 */
class ManualLookupActivity : AppCompatActivity() {

    /** The wiring host's scope, mirroring [ShareImageActivity.overlayScope]: the
     *  optional #44 table loads and the bookmark writes run here, cancelled with
     *  the activity. */
    private val lookupScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val controller = OcrOverlayStateController()

    private lateinit var input: TextInputEditText
    private lateinit var resultsContent: LinearLayout
    private var lookupJob: Job? = null

    /** True when this launch is a text-selection lookup that must not replace text. */
    private var readOnlyProcessText = false

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

        OverlayEnvironment.prepare(this, controller, lookupScope)

        // A selection-menu lookup never returns a result: it is read-only, and the
        // activity finishes as soon as it has shown what it found. `ACTION_SEND`
        // and a plain launch stay on screen. `EXTRA_PROCESS_TEXT_READONLY` is read
        // for the log only — we never set a replacement result either way, so the
        // flag cannot change what we return; it records what the sender asked for.
        readOnlyProcessText = intent?.action == Intent.ACTION_PROCESS_TEXT
        if (readOnlyProcessText) {
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
            // A selection-menu launch finishes on Back; a home-screen launch is a
            // normal task entry and Back returns to the home screen beneath it.
            setNavigationOnClickListener {
                if (readOnlyProcessText) finish() else onBackPressedDispatcher.onBackPressed()
            }
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "Back"
        }

        input = TextInputEditText(this).apply {
            hint = getString(R.string.manual_lookup_hint)
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
                    // Empty/whitespace clears: the results of the previous lookup
                    // would otherwise sit under an empty box claiming to belong to
                    // it. Clearing is not a lookup, so no "No results found" — that
                    // message is for a search that found nothing.
                    if (s.isNullOrBlank()) clearResults()
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
            orientation = LinearLayout.VERTICAL
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldCard)
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
     * Run the shared chain off the main thread, like the overlay's lookup. A new
     * submit cancels the one in flight, so a fast typist never sees an older
     * lookup's cards replace a newer one's.
     */
    private fun runLookup(text: String) {
        lookupJob?.cancel()
        lookupJob = lookupScope.launch {
            val entries = withContext(Dispatchers.IO) { controller.lookupText(text) }
            // A read-only selection lookup is a glance: show what was found, then
            // finish once it has been shown, never returning a replacement.
            if (readOnlyProcessText) {
                showResults(entries)
                Log.d(TAG, "process-text lookup done: ${entries.size} entries, finishing")
                finish()
            } else {
                showResults(entries)
            }
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
        readOnlyProcessText = readIncomingText() != null
        input.text = null
        applyIncomingText()
    }

    private companion object {
        const val TAG = "ManualLookupActivity"
    }
}
