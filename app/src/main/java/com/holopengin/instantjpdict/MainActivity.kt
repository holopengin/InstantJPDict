package com.holopengin.instantjpdict

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryImporter
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.KanaSizeFix
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.PitchAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importDictionary(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(OcrEngine.PREFS_NAME, MODE_PRIVATE)

        // The window is edge-to-edge — targetSdk 35 forces it on Android 15+, and
        // the share and camera activities already draw full-bleed — so every edge
        // of the screen is ours to pay for. Ask for it explicitly rather than
        // inheriting it, so the insets the listener below spends are the bars on
        // every API level and not only where the platform hands us a cut window.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Root is a column, not a scrolling page: the list takes the space above,
        // and the camera control is its last child, so it is pinned to the bottom
        // and cannot scroll away. The ScrollView is given "all that is left"
        // (0dp + weight 1) instead of the full height, so no row of the list can
        // ever end up underneath the pinned control.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // The system bars, spent once, on the root: the bars as padding plus the
        // 32px the root used to carry as margins. Paid on the root rather than on
        // the list because the pinned camera control has to clear the bars too.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(32 + bars.left, 32 + bars.top, 32 + bars.right, 32 + bars.bottom)
            insets
        }

        // ScrollView wrapper so tuning controls don't overflow
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scrollView.addView(layout)
        root.addView(scrollView)

        // #78: the camera viewfinder, and the only way into it. Capture goes into
        // ShareImageActivity through the same ACTION_SEND + EXTRA_STREAM entry the
        // system share sheet uses, so there is one OCR surface, not two. PINNED to
        // the bottom of the screen: a sibling of the ScrollView, so it stays put
        // while the list above scrolls, and the bars keep it clear of the navigation
        // bar.
        root.addView(Button(this).apply {
            text = "Camera"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ProtoCameraActivity::class.java))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        })

        val title = TextView(this).apply {
            text = "Instant JP Dict"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        // The status line, still here for import and pitch-install progress, but no
        // longer seeded with anything: the "DB contains N entries in M dictionaries"
        // readout that used to fill it on launch is gone (it was a debug readout on a
        // user-facing screen, and it cost two database queries on every open). Empty
        // until there is something real to say.
        tvStatus = TextView(this).apply {
            text = ""
            setPadding(0, 0, 0, 32)
        }
        layout.addView(tvStatus)

        addButton(layout, "Enable Accessibility Service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val accessibilityHelp = TextView(this).apply {
            text = "Note: If android displays an \"App access was denied\" popup when attempting to enable it the Accessibility Service, you may need to go to your system settings, find 'InstantJPDict' in the app list, and tap the three-dot menu to select 'Allow restricted settings'."
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(accessibilityHelp)

        addButton(layout, "Download Dictionaries") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yomidevs/jmdict-yomitan")))
        }

        addButton(layout, "Import Yomitan Dictionary (.zip)") {
            importLauncher.launch(arrayOf("application/zip"))
        }

        addButton(layout, "Manage Dictionaries") {
            DictionaryManagerDialog.show(this)
        }

        addButton(layout, "Gamepad Controls") {
            GamepadSettingsDialog.show(this)
        }

        // #70: licences and attribution for everything the APK bundles — the app
        // itself, every dependency, the native libraries, the models and the
        // derived dictionary data. Read from assets/licenses/INDEX.txt, so it works
        // with no network (the app declares no INTERNET permission). Also the
        // separate acknowledgements screen the EDRDG licence asks smartphone apps
        // for, since KRADFILE/JMdict-derived data ships in here.
        addButton(layout, "Licenses") {
            LicenseDialog.show(this)
        }

        // #44 Feature 3: the kana size correction runs unconditionally now — no settings row
        // and no preference read (see KanaSizeFix). The small/large form of っ/つ, ゃ/や, ゅ/ゆ,
        // ょ/よ is decided by a byte-CNN that reads five characters of context on each side,
        // applied only where the orthography allows it — pre-reform text keeps its large つ.
        //
        // Feature 3 also used to carry a "Check kana size model" button: one tap on the
        // device ran the model author's ten published vectors through this phone's own
        // encoder + JNI path and copied the verdict, proving the asset bytes, the
        // marshalling and the ARM float behaviour without adb. The BUTTON is gone — this
        // is a user-facing screen — but the diagnostic it drove is not: it stays in
        // [KanaSizeNcnn.probeWithTrace], which is where the check belongs and where a
        // future debug entry point can call it again.

        // ————— PP-OCR parameter tuning — debug controls — #14 —————
        // Hidden behind Debug settings checkbox — keeps main screen clean
        val debugPrefsKey = "debug_settings_enabled"
        val tuningContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.getBoolean(debugPrefsKey, false)) LinearLayout.VISIBLE else LinearLayout.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val debugToggle = CheckBox(this).apply {
            text = "Debug Settings"
            isChecked = prefs.getBoolean(debugPrefsKey, false)
            textSize = 14f
            setPadding(0, 24, 0, 8)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(debugPrefsKey, checked).apply()
                tuningContainer.visibility = if (checked) LinearLayout.VISIBLE else LinearLayout.GONE
                Log.d("MainActivity", "debug_settings_enabled=$checked")
            }
        }
        layout.addView(debugToggle)
        layout.addView(tuningContainer)

        // The three feature switches the main screen used to carry, moved in here so the
        // screen a user actually reads is not a wall of checkboxes. Each is a plain boolean
        // preference — not a float tunable — read once to seed the box and written back on
        // change, and each is read again at its own point of use; where the row is drawn
        // changes nothing about that. They sit ABOVE the tuning header so the tunables
        // below stay the one block the reset-to-defaults button owns.
        //
        // #43: pitch-accent display. Off by default; needs a pitch dictionary installed
        // or the rows simply never appear. Read at popup build time, so the next lookup
        // picks it up.
        tuningContainer.addView(CheckBox(this).apply {
            text = "Show pitch accent in dictionary popup"
            isChecked = PitchAccent.isEnabled(this@MainActivity)
            textSize = 14f
            setPadding(0, 20, 0, 8)
            setOnCheckedChangeListener { _, checked ->
                PitchAccent.setEnabled(this@MainActivity, checked)
                Log.d("MainActivity", "pitch_accent_enabled=$checked")
            }
        })

        // #72: double-tap zoom is opt-in. While it is on, a tap on empty space
        // must wait out the double-tap window before closing, so the default
        // trades zoom for an instant close.
        tuningContainer.addView(CheckBox(this).apply {
            text = "Double-tap to zoom (makes tap-to-close wait)"
            isChecked = DoubleTapZoom.isEnabled(this@MainActivity)
            textSize = 14f
            setPadding(0, 20, 0, 8)
            setOnCheckedChangeListener { _, checked ->
                DoubleTapZoom.setEnabled(this@MainActivity, checked)
                Log.d("MainActivity", "double_tap_zoom_enabled=$checked")
            }
        })

        // #44 Feature 2: clickable blanks where the vertical spacing says a character was
        // dropped. Vertical only (the horizontal trigger measured 13% false), and the blank
        // is filled through the alternatives panel's manual IME entry.
        tuningContainer.addView(CheckBox(this).apply {
            text = "Clickable blanks where a character looks missing (vertical text)"
            isChecked = BlankGaps.isEnabled(this@MainActivity)
            textSize = 14f
            setPadding(0, 20, 0, 8)
            setOnCheckedChangeListener { _, checked ->
                BlankGaps.setEnabled(this@MainActivity, checked)
                Log.d("MainActivity", "blank_gaps_enabled=$checked")
            }
        })

        val tuningHeader = TextView(this).apply {
            text = "PP-OCR Tuning (Debug)"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }
        tuningContainer.addView(tuningHeader)
        val tuningHelp = TextView(this).apply {
            text = "Tune for tight (but not too tight) crops and no missing っ / punctuation. Values are live from SharedPreferences (${OcrEngine.PREFS_NAME}); restart overlay or re-run OCR to apply. LONG_SIDE fixed at 960 (model input)."
            textSize = 11f
            setPadding(0, 0, 0, 12)
        }
        tuningContainer.addView(tuningHelp)

        // live summary line that shows current values
        val liveSummary = TextView(this).apply {
            textSize = 11f
            setPadding(0, 0, 0, 12)
            setBackgroundColor(android.graphics.Color.argb(20, 0, 0, 0))
        }
        tuningContainer.addView(liveSummary)
        fun refreshLiveSummary() {
            val thresh = prefs.getFloat(OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH)
            val unclip = prefs.getFloat(OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP)
            val longSide = OcrEngine.DEF_DET_LONG_SIDE
            val xOver = prefs.getFloat(OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP)
            val squish = prefs.getFloat(OcrEngine.PREF_REC_SQUISH, OcrEngine.DEF_REC_SQUISH)
            val line = "live: detThresh=${String.format("%.2f", thresh)} unclip=${String.format("%.2f", unclip)} longSide=$longSide xOver=${String.format("%.2f", xOver)} squish=${String.format("%.1f", squish)}"
            liveSummary.text = line
        }
        refreshLiveSummary()

        // helper to add one tunable row: label + live value + SeekBar + EditText + Apply
        fun addTunable(
            label: String,
            prefKey: String,
            default: Float,
            min: Float,
            max: Float,
            step: Float,
            isInt: Boolean
        ) {
            val steps = ((max - min) / step).roundToInt().coerceAtLeast(1)
            fun valueForProgress(p: Int): Float = min + p * step
            fun progressForValue(v: Float): Int = ((v - min) / step).roundToInt().coerceIn(0, steps)
            fun formatValue(v: Float): String = if (isInt) v.roundToInt().toString() else String.format("%.2f", v)

            val curRaw: Float = if (isInt) {
                prefs.getInt(prefKey, default.roundToInt()).toFloat()
            } else {
                prefs.getFloat(prefKey, default)
            }
            val cur = curRaw.coerceIn(min, max)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                setBackgroundColor(android.graphics.Color.argb(8, 0, 0, 0))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
            }

            val tvLabel = TextView(this).apply {
                text = "$label  (default ${formatValue(default)})"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            container.addView(tvLabel)

            val tvLive = TextView(this).apply {
                text = "current: ${formatValue(cur)}"
                textSize = 12f
                setTextColor(android.graphics.Color.rgb(0, 100, 0))
            }
            container.addView(tvLive)

            val seek = SeekBar(this).apply {
                this.max = steps
                progress = progressForValue(cur)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(seek)

            val editRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 0)
            }
            val edit = EditText(this).apply {
                setText(formatValue(cur))
                textSize = 12f
                inputType = if (isInt) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
                setPadding(12, 8, 12, 8)
                setBackgroundColor(android.graphics.Color.argb(30, 0, 0, 0))
            }
            editRow.addView(edit)
            val btnApply = Button(this).apply {
                text = "Apply"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                minWidth = 0
                minimumWidth = 0
                setPadding(16, 0, 16, 0)
            }
            editRow.addView(btnApply)
            val btnReset = Button(this).apply {
                text = "Reset"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 8 }
                minWidth = 0
                minimumWidth = 0
                setPadding(16, 0, 16, 0)
            }
            editRow.addView(btnReset)
            container.addView(editRow)

            // SeekBar listener: live update tvLive + edit, commit on stop
            var fromSeek = false
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    fromSeek = true
                    val v = valueForProgress(p)
                    tvLive.text = "current: ${formatValue(v)} (dragging)"
                    edit.setText(formatValue(v))
                    // don't commit yet; live TextView shows dragging value, summary updates on stop
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = valueForProgress(seek.progress)
                    if (isInt) prefs.edit().putInt(prefKey, v.roundToInt()).apply()
                    else prefs.edit().putFloat(prefKey, v).apply()
                    tvLive.text = "current: ${formatValue(v)}"
                    refreshLiveSummary()
                    Toast.makeText(this@MainActivity, "$label = ${formatValue(v)}", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "tuning $prefKey = $v")
                    fromSeek = false
                }
            })

            // Edit Apply
            btnApply.setOnClickListener {
                val raw = edit.text.toString().trim()
                val parsed = raw.toFloatOrNull()
                if (parsed == null) {
                    Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (parsed < min - 1e-6 || parsed > max + 1e-6) {
                    Toast.makeText(this, "Out of range [$min, $max]", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // snap to step for non-int? keep as-is but store
                val v = if (isInt) parsed.roundToInt().toFloat() else parsed
                if (isInt) prefs.edit().putInt(prefKey, v.roundToInt()).apply()
                else prefs.edit().putFloat(prefKey, v).apply()
                seek.progress = progressForValue(v)
                tvLive.text = "current: ${formatValue(v)}"
                refreshLiveSummary()
                Toast.makeText(this, "$label = ${formatValue(v)}", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "tuning $prefKey = $v (via EditText)")
            }
            btnReset.setOnClickListener {
                if (isInt) prefs.edit().putInt(prefKey, default.roundToInt()).apply()
                else prefs.edit().putFloat(prefKey, default).apply()
                seek.progress = progressForValue(default)
                edit.setText(formatValue(default))
                tvLive.text = "current: ${formatValue(default)}"
                refreshLiveSummary()
                Toast.makeText(this, "$label reset to ${formatValue(default)}", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "tuning $prefKey reset to $default")
            }

            // live: if user types, update tvLive preview (don't commit)
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (fromSeek) return
                    val t = s?.toString()?.trim() ?: return
                    val pv = t.toFloatOrNull() ?: return
                    if (pv in min..max) {
                        tvLive.text = "current: ${formatValue(pv)} (typed, press Apply)"
                    }
                }
            })

            tuningContainer.addView(container)
        }

        addTunable("PPOCR_DET_THRESH", OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH, 0.05f, 0.60f, 0.01f, false)
        addTunable("PPOCR_DET_UNCLIP_RATIO", OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP, 0.5f, 3.0f, 0.01f, false)
        addTunable("X_OVERLAP_THRESHOLD", OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP, 0.0f, 1.0f, 0.01f, false)
        addTunable("REC_SQUISH_FACTOR", OcrEngine.PREF_REC_SQUISH, OcrEngine.DEF_REC_SQUISH, 0.2f, 1.0f, 0.1f, false)
        addTunable("OVERLAY_SCREENSHOT_ALPHA", OverlayBackdrop.PREF_SCREENSHOT_ALPHA, OverlayBackdrop.DEF_SCREENSHOT_ALPHA, 0.3f, 1.0f, 0.05f, false)
        // Certainty required before the kana size model may rewrite a character. The measured
        // tradeoff over 7,620 confusable bench positions: 0.01 -> 12 fixed / 4 broken,
        // 0.03 -> 22/12, 0.10 -> 29/24.
        addTunable("KANA_SIZE_EPSILON", KanaSizeFix.PREF_EPSILON, KanaSizeFix.DEF_EPSILON, 0.005f, 0.50f, 0.005f, false)
        addTunable("CROSSHAIR_GAP", ProtoCrosshairView.PREF_GAP, ProtoCrosshairView.DEF_GAP, 0.005f, 0.10f, 0.005f, false)

        addButton(tuningContainer, "Copy inference log") {
            val text = InferLog.dump()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("infer-log", text))
            Toast.makeText(this, "Inference log copied (${text.lines().size} lines)", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "inference log copied")
        }

        addButton(tuningContainer, "Reset all tuning to defaults") {
            prefs.edit()
                .putFloat(OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH)
                .putFloat(OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP)
                .putFloat(OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP)
                .putFloat(OcrEngine.PREF_REC_SQUISH, OcrEngine.DEF_REC_SQUISH)
                .putFloat(OverlayBackdrop.PREF_SCREENSHOT_ALPHA, OverlayBackdrop.DEF_SCREENSHOT_ALPHA)
                // The kana-size ε tunable belongs here too: a control the reset does not know
                // about is a bug, so the reset lists every addTunable above.
                .putFloat(KanaSizeFix.PREF_EPSILON, KanaSizeFix.DEF_EPSILON)
                .putFloat(ProtoCrosshairView.PREF_GAP, ProtoCrosshairView.DEF_GAP)
                // The three feature switches moved into this block with the tunables, so the
                // reset owns them now as well: a control the reset does not know about is a
                // bug here. They are booleans with their own defaults, not addTunable rows.
                .putBoolean(PitchAccent.PREF_PITCH_ENABLED, PitchAccent.DEF_PITCH_ENABLED)
                .putBoolean(DoubleTapZoom.PREF_ENABLED, DoubleTapZoom.DEF_ENABLED)
                .putBoolean(BlankGaps.PREF_ENABLED, BlankGaps.DEF_ENABLED)
                .apply()
            Toast.makeText(this, "All tuning reset to defaults — reopen screen to refresh", Toast.LENGTH_LONG).show()
            Log.d("MainActivity", "all tuning reset to defaults")
            // Recreate to refresh SeekBars and the feature checkboxes
            recreate()
        }

        setContentView(root)
        ensureBundledPitchDictionary()
    }

    private fun addButton(parent: android.view.ViewGroup, text: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        parent.addView(button)
    }

    /**
     * #43: first-launch install of the pitch dictionary vendored in the APK.
     *
     * The dictionary is built-in (see [DictionaryMeta.builtIn]), so there is no
     * button to find and nothing for the user to manage — the feature is simply
     * ready the first time they look something up. Presence is the truth rather
     * than a remembered flag, which also means a wiped database heals itself.
     */
    private fun ensureBundledPitchDictionary() {
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext)
                    .dictionaryDao()
                    .findBuiltInDictionary() != null
            }
            if (!installed) installBundledPitchDictionary()
        }
    }

    /**
     * #43: install the pitch dictionary vendored in the APK assets. No network
     * and no file picker — and because [DictionaryImporter.importBundledAsset]
     * replaces any copy with the same title, running it twice is harmless.
     *
     * There is no button for this: the install completes the flag it is checked
     * by, so a wiped database, a corrupted row or an import killed part-way all
     * repair themselves on the next launch.
     */
    private fun installBundledPitchDictionary() {
        lifecycleScope.launch {
            try {
                val importer = DictionaryImporter(applicationContext)
                val result = importer.importBundledAsset(PitchAccent.BUNDLED_ASSET) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        tvStatus.text = "Installing pitch dictionary: $progress entries..."
                    }
                }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { count ->
                            tvStatus.text = if (PitchAccent.isEnabled(this@MainActivity)) {
                                "Pitch dictionary installed: $count entries"
                            } else {
                                "Pitch dictionary installed: $count entries " +
                                    "(tick the box above to show it)"
                            }
                        },
                        onFailure = { e -> "Pitch dictionary error: ${e.message}" },
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error installing pitch dictionary: ${e.message}"
                }
            }
        }
    }

    private fun importDictionary(uri: Uri) {
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(nameIndex) else "Imported Dictionary"
        } ?: "Imported Dictionary"

        tvStatus.text = "Importing..."
        lifecycleScope.launch {
            try {
                val importer = DictionaryImporter(applicationContext)
                val result = importer.importZip(uri, name) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        tvStatus.text = "Importing: $progress entries..."
                    }
                }
                withContext(Dispatchers.Main) {
                    tvStatus.text = result.fold(
                        onSuccess = { count -> "Imported $count entries" },
                        onFailure = { e -> "Error: ${e.message}" }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error initializing importer: ${e.message}"
                }
            }
        }
    }
}
