package com.holopengin.instantjpdict

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.slider.Slider
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryImporter
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.KanaSizeFix
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.PitchAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ui-deepseek design 1 — "Harbour".
 *
 * The home screen as a Material 3 surface: one scrolling column of tonal cards
 * under a lifted app bar, with the camera — the reason the app exists — pinned to
 * the bottom-right as an extended FAB that never scrolls away.
 *
 * The old screen was a single undifferentiated button column: every entry point
 * the same full-width button, the debug tuning inline, the camera a plain button
 * below the list. Nothing said which thing a first-run user had to do, and the
 * accessibility note was a paragraph wedged between two buttons.
 *
 * The redesign keeps every entry point and regroups them by what the user is
 * trying to do:
 *   - "Get started" — the accessibility service, as the one filled button on the
 *     screen, with its long denial note behind a disclosure instead of in the
 *     flow;
 *   - "Dictionaries" — the catalog and the import/manage/bookmark verbs, one
 *     obvious primary (tonal) action and the rest as list rows;
 *   - "Settings" — the overlay font, gamepad, licences and the debug switch,
 *     with the PP-OCR tuning block nested inside as a tonal panel rather than as
 *     a peer of the camera button.
 *
 * All of the behaviour lives exactly where it lived before: the same
 * SharedPreferences keys, the same [DictionaryCatalogDialog] / [LicenseDialog] /
 * [GamepadSettingsDialog] / [BookmarkViewerDialog] entry points, the same
 * [importLauncher], the same [openCamera] target, and the same shortcut routing in
 * [openCameraFrom]. What changed is the shape of the surface, not the machine
 * underneath it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var statusCard: MaterialCardView

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importDictionary(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(OcrEngine.PREFS_NAME, MODE_PRIVATE)

        // The window is edge-to-edge — targetSdk 35 forces it on Android 15+, and
        // the share and camera activities already draw full-bleed. The insets are
        // spent at the bottom of this method, on the exact views that have to clear
        // the bars: the app bar (top), the scrolling column (sides + bottom, so the
        // last card can scroll clear of the FAB) and the FAB itself (bottom).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // ————— theme colours, read once —————
        // Resolved from the theme rather than hardcoded, so the day/night pair and
        // the Material You override both flow into every hand-built view.
        val cSurfaceLow = color(com.google.android.material.R.attr.colorSurfaceContainerLow)
        val cSurfaceHigh = color(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val cSurfaceHighest = color(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        val cSecondaryContainer = color(com.google.android.material.R.attr.colorSecondaryContainer)
        val cOnSecondaryContainer = color(com.google.android.material.R.attr.colorOnSecondaryContainer)
        val cOnSurface = color(com.google.android.material.R.attr.colorOnSurface)
        val cOnSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val cPrimary = color(com.google.android.material.R.attr.colorPrimary)
        val cOnPrimary = color(com.google.android.material.R.attr.colorOnPrimary)
        val cOutlineVariant = color(com.google.android.material.R.attr.colorOutlineVariant)

        // ————— root: CoordinatorLayout, so the FAB can float —————
        val root = CoordinatorLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ————— app bar —————
        val toolbar = MaterialToolbar(this).apply {
            title = "Instant JP Dict"
            subtitle = "On-device Japanese OCR"
            // The title block is the app's identity, not a navigation affordance;
            // the two primary actions live in the body and the FAB.
            isTitleCentered = false
        }
        val appBar = AppBarLayout(this).apply {
            setLiftOnScroll(true)
            elevation = 0f
            addView(
                toolbar,
                AppBarLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(appBar)

        // ————— the scrolling column —————
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = NestedScrollView(this).apply {
            // The content may draw into the bottom padding, which is what lets the
            // final card scroll clear of the pinned FAB instead of hiding under it.
            clipToPadding = false
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { behavior = AppBarLayout.ScrollingViewBehavior() }
        }
        root.addView(scroll)

        // ————— section helper —————
        // Each section is a low-elevation tonal card: the tonal step is the
        // grouping, so whitespace between sections is the only separator needed.
        fun newSection(titleText: String, iconRes: Int? = null): LinearLayout {
            val card = MaterialCardView(this).apply {
                radius = dp(24).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(cSurfaceLow)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(20))
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(4))
            }
            if (iconRes != null) {
                header.addView(ImageView(this).apply {
                    setImageResource(iconRes)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }
            header.addView(TextView(this).apply {
                text = titleText
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                setTextColor(cOnSurface)
                setTypeface(typeface, Typeface.BOLD)
            })
            body.addView(header)
            card.addView(body)
            content.addView(card)
            return body
        }

        fun bodyText(parent: LinearLayout, text: String) {
            parent.addView(TextView(this).apply {
                this.text = text
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(cOnSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            })
        }

        // ————— a settings-style row: icon, title, optional supporting line, trailing —————
        fun listRow(
            iconRes: Int,
            titleText: String,
            supporting: String? = null,
            trailing: View? = null,
            onClick: (() -> Unit)? = null
        ): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = onClick != null
            isFocusable = onClick != null
            onClick?.let { cb -> setOnClickListener { cb() } }
            // Ripple from the theme rather than a hand-rolled selector.
            TypedValue().let { tv ->
                if (this@MainActivity.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, tv, true
                    )
                ) {
                    setBackgroundResource(tv.resourceId)
                }
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = titleText
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    setTextColor(cOnSurface)
                })
                if (supporting != null) {
                    addView(TextView(this@MainActivity).apply {
                        text = supporting
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        setTextColor(cOnSurfaceVariant)
                    })
                }
            })
            if (trailing != null) addView(trailing)
        }

        fun chevron(): ImageView = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun switchRow(
            iconRes: Int,
            titleText: String,
            supporting: String,
            checked: Boolean,
            onChanged: (Boolean) -> Unit
        ): LinearLayout {
            val toggle = MaterialSwitch(this).apply {
                isChecked = checked
                contentDescription = titleText
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            }
            // The whole row is the target (≥56 dp tall); the switch is kept
            // clickable too so a screen reader exposes it as a real switch.
            return listRow(iconRes, titleText, supporting, trailing = toggle) { toggle.toggle() }
        }

        fun filledButton(
            parent: LinearLayout,
            text: String,
            iconRes: Int? = null,
            onClick: () -> Unit
        ): MaterialButton = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            this.text = text
            isAllCaps = false
            if (iconRes != null) {
                setIconResource(iconRes)
                iconTint = ColorStateList.valueOf(cOnPrimary)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            parent.addView(this)
        }

        fun tonalButton(
            parent: LinearLayout,
            text: String,
            iconRes: Int? = null,
            onClick: () -> Unit
        ): MaterialButton = filledButton(parent, text, iconRes, onClick).apply {
            backgroundTintList = ColorStateList.valueOf(cSecondaryContainer)
            setTextColor(cOnSecondaryContainer)
            iconTint = ColorStateList.valueOf(cOnSecondaryContainer)
        }

        fun outlinedButton(
            parent: LinearLayout,
            text: String,
            iconRes: Int? = null,
            onClick: () -> Unit
        ): MaterialButton = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            isAllCaps = false
            if (iconRes != null) setIconResource(iconRes)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            parent.addView(this)
        }

        fun textButton(
            parent: LinearLayout,
            text: String,
            onClick: () -> Unit
        ): MaterialButton = MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            this.text = text
            isAllCaps = false
            setTextColor(cPrimary)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            parent.addView(this)
        }

        // ————— status banner —————
        // The status line still carries import and pitch-install progress, but it
        // now has a surface of its own and disappears entirely when there is
        // nothing to say — no reserved empty strip above the fold.
        tvStatus = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(cOnSecondaryContainer)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(cSecondaryContainer)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_info)
                    imageTintList = ColorStateList.valueOf(cOnSecondaryContainer)
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(tvStatus)
            })
        }
        content.addView(statusCard)

        // ————————— 1. Get started —————————
        val accessBody = newSection("Get started", R.drawable.ic_accessibility)
        bodyText(
            accessBody,
            "Turn on the accessibility service, then point the overlay at any app and tap a word to look it up."
        )
        filledButton(accessBody, "Enable accessibility service", R.drawable.ic_accessibility) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // The denial note used to sit in the open, a paragraph of system-settings
        // path between two buttons. It is only relevant when that popup appears, so
        // it is a disclosure now — the capability is intact, the wall of text is not.
        val accessNote = TextView(this).apply {
            text = "If Android shows an \"App access was denied\" popup, go to system settings, " +
                "find 'InstantJPDict' in the app list, open the three-dot menu and choose " +
                "'Allow restricted settings'."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(cOnSurfaceVariant)
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        accessBody.addView(accessNote)
        textButton(accessBody, "Access was denied?") {
            val showing = accessNote.visibility == View.VISIBLE
            accessNote.visibility = if (showing) View.GONE else View.VISIBLE
            Log.d("MainActivity", "accessibility_note_shown=${!showing}")
        }

        // ————————— 2. Dictionaries —————————
        val dictBody = newSection("Dictionaries", R.drawable.ic_book)
        bodyText(
            dictBody,
            "Install a Yomitan-format dictionary, or grab one from the built-in catalog."
        )
        tonalButton(dictBody, "Dictionary catalog", R.drawable.ic_book) {
            DictionaryCatalogDialog.show(this)
        }
        dictBody.addView(listRow(
            R.drawable.ic_upload,
            "Import Yomitan dictionary",
            "From a .zip on this device",
            chevron()
        ) { importLauncher.launch(arrayOf("application/zip")) })
        dictBody.addView(listRow(
            R.drawable.ic_download,
            "Download dictionaries",
            "Opens the upstream JMdict page",
            chevron()
        ) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yomidevs/jmdict-yomitan"))) })
        dictBody.addView(listRow(
            R.drawable.ic_book,
            "Manage dictionaries",
            "Rename, reorder or remove installed dictionaries",
            chevron()
        ) { DictionaryManagerDialog.show(this) })
        dictBody.addView(listRow(
            R.drawable.ic_bookmark,
            "Bookmarks",
            "Headwords you saved from the lookup popup",
            chevron()
        ) { BookmarkViewerDialog.show(this) })

        // ————————— 3. Settings —————————
        val settingsBody = newSection("Settings", R.drawable.ic_settings)
        settingsBody.addView(switchRow(
            R.drawable.ic_text_fields,
            "Serif overlay font",
            "Use a mincho face in the OCR overlay",
            OverlayFont.face(this) == OverlayFont.FACE_SERIF
        ) { checked ->
            val face = if (checked) OverlayFont.FACE_SERIF else OverlayFont.FACE_SANS
            OverlayFont.setFace(this, face)
            Log.d("MainActivity", "overlay_font_face=$face")
        })
        settingsBody.addView(listRow(
            R.drawable.ic_gamepad,
            "Gamepad & hardware keys",
            "Map buttons and volume keys to overlay actions",
            chevron()
        ) { GamepadSettingsDialog.show(this) })
        settingsBody.addView(listRow(
            R.drawable.ic_info,
            "Licenses & attribution",
            "Everything bundled in the APK, offline",
            chevron()
        ) { LicenseDialog.show(this) })

        // ————— debug switch + its tuning panel —————
        val debugPrefsKey = "debug_settings_enabled"
        val tuningContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val tuningCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = cOutlineVariant
            setCardBackgroundColor(cSurfaceHigh)
            visibility = if (prefs.getBoolean(debugPrefsKey, false)) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(tuningContainer)
        }
        settingsBody.addView(switchRow(
            R.drawable.ic_tune,
            "Debug settings",
            "Show PP-OCR tuning and diagnostics",
            prefs.getBoolean(debugPrefsKey, false)
        ) { checked ->
            prefs.edit().putBoolean(debugPrefsKey, checked).apply()
            tuningCard.visibility = if (checked) View.VISIBLE else View.GONE
            Log.d("MainActivity", "debug_settings_enabled=$checked")
        })
        settingsBody.addView(tuningCard)

        // Sub-header helper for the panel's two halves.
        fun panelHeader(text: String) {
            tuningContainer.addView(TextView(this).apply {
                this.text = text
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setTextColor(cOnSurface)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(4))
            })
        }

        panelHeader("Overlay behaviour")
        // The three feature switches the main screen used to carry, moved in here so the
        // screen a user actually reads is not a wall of checkboxes. Each is a plain boolean
        // preference — not a float tunable — read once to seed the switch and written back on
        // change, and each is read again at its own point of use; where the row is drawn
        // changes nothing about that.
        //
        // #43: pitch-accent display. Off by default; needs a pitch dictionary installed
        // or the rows simply never appear. Read at popup build time, so the next lookup
        // picks it up.
        fun featureSwitch(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
            tuningContainer.addView(MaterialSwitch(this).apply {
                text = label
                isChecked = checked
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(cOnSurface)
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            })
        }
        featureSwitch("Show pitch accent in dictionary popup", PitchAccent.isEnabled(this)) { checked ->
            PitchAccent.setEnabled(this, checked)
            Log.d("MainActivity", "pitch_accent_enabled=$checked")
        }
        // #72: double-tap zoom is opt-in. While it is on, a tap on empty space
        // must wait out the double-tap window before closing, so the default
        // trades zoom for an instant close.
        featureSwitch("Double-tap to zoom (makes tap-to-close wait)", DoubleTapZoom.isEnabled(this)) { checked ->
            DoubleTapZoom.setEnabled(this, checked)
            Log.d("MainActivity", "double_tap_zoom_enabled=$checked")
        }
        // #44 Feature 2: clickable blanks where the vertical spacing says a character was
        // dropped. Vertical only (the horizontal trigger measured 13% false), and the blank
        // is filled through the alternatives panel's manual IME entry.
        featureSwitch("Clickable blanks where a character looks missing (vertical text)", BlankGaps.isEnabled(this)) { checked ->
            BlankGaps.setEnabled(this, checked)
            Log.d("MainActivity", "blank_gaps_enabled=$checked")
        }
        // #53: rotated-rect detection is opt-in, per the maintainer's scope note:
        // axis-aligned lines are the simplest and most stable case and must stay
        // the default; the richer geometry earns its place as an experiment.
        featureSwitch("Detect rotated lines (experimental)", OcrEngine.isDetRotated(this)) { checked ->
            OcrEngine.setDetRotated(this, checked)
            Log.d("MainActivity", "det_rotated_enabled=$checked")
        }

        panelHeader("PP-OCR parameters")
        tuningContainer.addView(TextView(this).apply {
            text = "Tune for tight (but not too tight) crops and no missing っ / punctuation. " +
                "Values are live from SharedPreferences (${OcrEngine.PREFS_NAME}); restart the overlay " +
                "or re-run OCR to apply. Det input is ${OcrEngine.DET_MODEL_SIZE}×${OcrEngine.DET_MODEL_SIZE} " +
                "(LONG_SIDE is clamped to it)."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(cOnSurfaceVariant)
            setPadding(0, 0, 0, dp(8))
        })

        // live summary line that shows current values
        val liveSummary = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(cOnSurfaceVariant)
            background = roundedBackground(cSurfaceHighest, dp(10))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        tuningContainer.addView(liveSummary)
        fun refreshLiveSummary() {
            val thresh = prefs.getFloat(OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH)
            val unclip = prefs.getFloat(OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP)
            val longSide = OcrEngine.DEF_DET_LONG_SIDE
            val xOver = prefs.getFloat(OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP)
            val squish = prefs.getFloat(OcrEngine.PREF_REC_SQUISH, OcrEngine.DEF_REC_SQUISH)
            // F5/#86: Locale.ROOT, so the decimal separator in this debug readout
            // does not follow the phone's locale (lint's DefaultLocale).
            val line = "live: detThresh=${String.format(Locale.ROOT, "%.2f", thresh)} " +
                "unclip=${String.format(Locale.ROOT, "%.2f", unclip)} longSide=$longSide " +
                "xOver=${String.format(Locale.ROOT, "%.2f", xOver)} " +
                "squish=${String.format(Locale.ROOT, "%.1f", squish)}"
            liveSummary.text = line
        }
        refreshLiveSummary()

        // helper to add one tunable row: label + live value + Slider + EditText + Apply/Reset
        fun addTunable(row: TuningRow) {
            val label = row.label
            val prefKey = row.key
            val default = row.default
            val min = row.min
            val max = row.max
            val step = row.step
            val isInt = row.isInt
            fun formatValue(v: Float): String =
                if (isInt) v.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", v)
            // The Slider runs continuous (stepSize 0) and the snap to the row's
            // step happens on touch-up / Apply: a stepped slider whose range is not
            // exactly divisible in float would refuse to lay out at all.
            fun snap(v: Float): Float =
                if (step <= 0f) v else (min + ((v - min) / step).roundToInt() * step).coerceIn(min, max)

            val curRaw: Float = if (isInt) {
                prefs.getInt(prefKey, default.roundToInt()).toFloat()
            } else {
                prefs.getFloat(prefKey, default)
            }
            val cur = curRaw.coerceIn(min, max)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(4))
            }

            container.addView(TextView(this).apply {
                text = label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(cOnSurface)
            })

            val tvLive = TextView(this).apply {
                text = "current ${formatValue(cur)} · default ${formatValue(default)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(cPrimary)
            }
            container.addView(tvLive)

            val slider = Slider(this).apply {
                valueFrom = min
                valueTo = max
                stepSize = 0f
                value = cur
                setLabelFormatter { formatValue(it) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(slider)

            val editRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
            }
            val edit = EditText(this).apply {
                setText(formatValue(cur))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(cOnSurface)
                inputType = if (isInt) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                background = roundedBackground(cSurfaceHighest, dp(10))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minimumHeight = dp(48)
                setSelectAllOnFocus(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(8) }
            }
            editRow.addView(edit)
            val btnApply = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "Apply"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            editRow.addView(btnApply)
            val btnReset = MaterialButton(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "Reset"
                isAllCaps = false
                setTextColor(cPrimary)
            }
            editRow.addView(btnReset)
            container.addView(editRow)

            var fromSlider = false
            slider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                fromSlider = true
                tvLive.text = "current ${formatValue(value)} · default ${formatValue(default)} (dragging)"
                edit.setText(formatValue(value))
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) {}
                override fun onStopTrackingTouch(s: Slider) {
                    val v = snap(s.value)
                    if (isInt) prefs.edit().putInt(prefKey, v.roundToInt()).apply()
                    else prefs.edit().putFloat(prefKey, v).apply()
                    if (s.value != v) s.value = v
                    tvLive.text = "current ${formatValue(v)} · default ${formatValue(default)}"
                    refreshLiveSummary()
                    Toast.makeText(this@MainActivity, "$label = ${formatValue(v)}", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "tuning $prefKey = $v")
                    fromSlider = false
                }
            })

            btnApply.setOnClickListener {
                val parsed = edit.text.toString().trim().toFloatOrNull()
                if (parsed == null) {
                    Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (parsed < min - 1e-6 || parsed > max + 1e-6) {
                    Toast.makeText(this, "Out of range [$min, $max]", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val v = if (isInt) parsed.roundToInt().toFloat() else parsed
                if (isInt) prefs.edit().putInt(prefKey, v.roundToInt()).apply()
                else prefs.edit().putFloat(prefKey, v).apply()
                slider.value = v.coerceIn(min, max)
                tvLive.text = "current ${formatValue(v)} · default ${formatValue(default)}"
                refreshLiveSummary()
                Toast.makeText(this, "$label = ${formatValue(v)}", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "tuning $prefKey = $v (via EditText)")
            }
            btnReset.setOnClickListener {
                if (isInt) prefs.edit().putInt(prefKey, default.roundToInt()).apply()
                else prefs.edit().putFloat(prefKey, default).apply()
                slider.value = default.coerceIn(min, max)
                edit.setText(formatValue(default))
                tvLive.text = "current ${formatValue(default)} · default ${formatValue(default)}"
                refreshLiveSummary()
                Toast.makeText(this, "$label reset to ${formatValue(default)}", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "tuning $prefKey reset to $default")
            }

            // live: if user types, update the preview (don't commit)
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (fromSlider) return
                    val t = s?.toString()?.trim() ?: return
                    val pv = t.toFloatOrNull() ?: return
                    if (pv in min..max) {
                        tvLive.text = "current ${formatValue(pv)} · default ${formatValue(default)} (typed, press Apply)"
                    }
                }
            })

            tuningContainer.addView(container)
        }

        DebugTuning.rows.forEach { addTunable(it) }

        outlinedButton(tuningContainer, "Copy inference log") {
            val text = InferLog.dump()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("infer-log", text))
            Toast.makeText(this, "Inference log copied (${text.lines().size} lines)", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "inference log copied")
        }

        outlinedButton(tuningContainer, "Reset all tuning to defaults") {
            // G1/#86: one source for the controls and the reset. Every row/feature the
            // debug screen built comes from [DebugTuning], so there is no list here to
            // keep in step (which is exactly how a new control used to survive a reset).
            val editor = prefs.edit()
            DebugTuning.rows.forEach { row ->
                if (row.isInt) editor.putInt(row.key, row.default.roundToInt())
                else editor.putFloat(row.key, row.default)
            }
            DebugTuning.features.forEach { editor.putBoolean(it.key, it.default) }
            editor.apply()
            Toast.makeText(this, "All tuning reset to defaults — reopen screen to refresh", Toast.LENGTH_LONG).show()
            Log.d("MainActivity", "all tuning reset to defaults")
            // Recreate to refresh the sliders and the feature switches.
            recreate()
        }

        // ————————— the pinned camera affordance —————————
        // The camera is the app's purpose, so it is not a row in a list: it is an
        // extended FAB, a sibling of the ScrollView in the CoordinatorLayout, so it
        // stays put while the column above scrolls and the bars keep it clear of the
        // navigation bar.
        val cameraFab = ExtendedFloatingActionButton(this).apply {
            text = "Scan with camera"
            setIconResource(R.drawable.ic_camera)
            contentDescription = "Scan with camera"
            setOnClickListener { openCamera() }
        }
        val fabLp = CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(20)
            marginEnd = dp(20)
        }
        root.addView(cameraFab, fabLp)

        // ————— insets, spent once —————
        val basePadH = dp(20)
        val basePadTop = dp(6)
        val basePadBottom = dp(24)
        val fabBaseBottom = dp(20)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The app bar's own background covers the status bar; only its content
            // is pushed below it.
            appBar.setPadding(0, bars.top, 0, 0)
            scroll.setPadding(
                basePadH + bars.left,
                basePadTop,
                basePadH + bars.right,
                basePadBottom + bars.bottom
            )
            val lp = cameraFab.layoutParams as CoordinatorLayout.LayoutParams
            lp.bottomMargin = fabBaseBottom + bars.bottom
            lp.marginEnd = basePadH + bars.right
            cameraFab.layoutParams = lp
            insets
        }

        setContentView(root)
        ensureBundledPitchDictionary()

        // #82: the app shortcut (res/xml/shortcuts.xml). Cold launches — the app
        // was not running — arrive here with the shortcut's action, and this is
        // where it becomes the viewfinder. The dictionary screen this activity
        // just built is an artefact of the routing, not somewhere the user asked
        // to be, so the router removes itself: with nothing of ours beneath the
        // camera, Back returns to whatever was on screen before (home, another
        // app, or the task below) instead of dropping the user into the
        // dictionary. Warm re-entry (onNewIntent) keeps this activity, because
        // there the dictionary IS the screen the user came from.
        if (openCameraFrom(intent)) finish()
    }

    /** Resolve one colour from the current theme (day/night and Material You aware). */
    private fun color(attr: Int): Int =
        MaterialColors.getColor(this, attr, android.graphics.Color.GRAY)

    /** A rounded solid-colour background, for the handful of views the theme does not style. */
    private fun roundedBackground(fill: Int, radiusPx: Int): MaterialShapeDrawable =
        MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, radiusPx.toFloat())
                .build()
        ).apply { fillColor = ColorStateList.valueOf(fill) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * The one place the status text is written: keeps the banner's visibility in
     * step with its content, so "no status" is no banner rather than an empty strip.
     */
    private fun setStatus(text: CharSequence) {
        tvStatus.text = text
        statusCard.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * #82: the second way into this activity, for the case this activity is
     * ALREADY the top of the task — manifest `singleTop` names this activity, so
     * the platform reuses the instance and delivers the shortcut here instead of
     * stacking a second dictionary screen under the camera.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // No finish() here: this instance was already the top of the task, so
        // it is the previous activity Back should return to.
        openCameraFrom(intent)
    }

    /**
     * #82: the router between the shortcut's action and the viewfinder. The
     * static declaration can carry an action and nothing else, so the rule is
     * equality with [CameraShortcut.ACTION_OPEN_CAMERA] — see [CameraShortcut] for
     * why the shortcut reaches the camera through this activity rather than
     * naming the viewfinder directly — and any other launch of this activity
     * (the ordinary MAIN tap) is untouched.
     *
     * The action is CLEARED from the intent it arrived on. The client record
     * keeps this same instance and re-delivers it when the platform re-creates
     * the activity (a rotation, a UI-mode change, an explicit recreate()), so
     * without the clear the next onCreate would read the shortcut again and open
     * a second viewfinder over the one the user is looking at. Clearing it on the
     * framework's own instance — not on a copy — is what makes the request
     * one-shot.
     *
     * A passive restore after process death is deliberately NOT guarded: the
     * system server's copy of the intent still carries the action, but a
     * `savedInstanceState` test cannot tell that restore apart from a cold
     * shortcut launch into a dead-but-tasked app, and refusing the camera on a
     * real activation would be the worse failure.
     */
    private fun openCameraFrom(intent: Intent): Boolean {
        if (!CameraShortcut.opensCamera(intent.action)) return false
        intent.action = null
        Log.d("MainActivity", "camera shortcut: opening the viewfinder")
        openCamera()
        return true
    }

    /** The one way into the viewfinder: the pinned control and the shortcut both land here.
     *
     * A9/#86: launched with CLEAR_TOP|SINGLE_TOP. The gamepad shortcut can fire while a
     * viewfinder is already on top; `singleTop` on MainActivity does not reuse an
     * instance that is below the camera, so the platform would push a second
     * MainActivity whose onCreate pushes a second viewfinder — leaving the first
     * beneath it, and Back landing on the previous camera rather than the dictionary.
     * CLEAR_TOP|SINGLE_TOP makes the duplicate launch collapse onto the existing
     * viewfinder: the new MainActivity is cleared, nothing is stacked. */
    private fun openCamera() {
        startActivity(
            Intent(this, ProtoCameraActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
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
                        setStatus("Installing pitch dictionary: $progress entries...")
                    }
                }
                withContext(Dispatchers.Main) {
                    // A3/#86: the failure message is the fold's result, so it must be
                    // assigned — as a bare expression the status line kept showing
                    // "Installing pitch dictionary: N entries…" after a failed import.
                    setStatus(result.fold(
                        onSuccess = { count ->
                            if (PitchAccent.isEnabled(this@MainActivity)) {
                                "Pitch dictionary installed: $count entries"
                            } else {
                                "Pitch dictionary installed: $count entries " +
                                    "(turn on pitch accent in Debug settings)"
                            }
                        },
                        onFailure = { e -> "Pitch dictionary error: ${e.message}" },
                    ))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("Error installing pitch dictionary: ${e.message}")
                }
            }
        }
    }

    private fun importDictionary(uri: Uri) {
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(nameIndex) else "Imported Dictionary"
        } ?: "Imported Dictionary"

        setStatus("Importing...")
        lifecycleScope.launch {
            try {
                val importer = DictionaryImporter(applicationContext)
                val result = importer.importZip(uri, name) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        setStatus("Importing: $progress entries...")
                    }
                }
                withContext(Dispatchers.Main) {
                    setStatus(result.fold(
                        onSuccess = { count -> "Imported $count entries" },
                        onFailure = { e -> "Error: ${e.message}" }
                    ))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("Error initializing importer: ${e.message}")
                }
            }
        }
    }
}
