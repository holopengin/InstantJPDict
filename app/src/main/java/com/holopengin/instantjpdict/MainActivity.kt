package com.holopengin.instantjpdict

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryImporter
import com.holopengin.instantjpdict.util.PitchAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *   - "Get started" — the dictionary catalog as a tonal button, then the
 *     accessibility service as the one filled button on the screen, with its
 *     long denial note behind a disclosure instead of in the flow;
 *   - "Manage dictionaries" and "Bookmarks" — top-level buttons, directly on
 *     the screen rather than nested in a section; installing a local .zip lives
 *     on the dictionary manager's own dialog, not here;
 *   - "Settings" — the overlay font, gamepad, licences and a row into the
 *     debug/tuning screen, which is now an activity of its own rather than a
 *     panel nested in this card.
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
            subtitle = "Offline OCR"
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
            supporting: String? = null,
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
        // The status line carries dictionary-import progress (the bundled pitch
        // install is deliberately silent), and it disappears entirely when there
        // is nothing to say — no reserved empty strip above the fold.
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
        val accessBody = newSection("Get started", R.drawable.ic_checklist)
        bodyText(
            accessBody,
            "Install a dictionary, then turn on the accessibility service and point the " +
                "overlay at any app, and tap a word to look it up."
        )
        // The catalog leads the card: a first-run user needs a dictionary before the
        // service is worth enabling.
        tonalButton(accessBody, "Dictionary catalog", R.drawable.ic_book) {
            DictionaryCatalogDialog.show(this)
        }
        filledButton(accessBody, "Enable accessibility service", R.drawable.ic_accessibility) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // The denial note used to sit in the open, a paragraph of system-settings
        // path between two buttons. It is only relevant when that popup appears, so
        // it is a disclosure now — the capability is intact, the wall of text is not.
        val accessNote = TextView(this).apply {
            text = "If Android shows an \"App was denied access\" popup, go to system settings, " +
                "find 'InstantJPDict' in the app list, open the three-dot menu and choose " +
                "'Allow restricted settings'."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(cOnSurfaceVariant)
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        accessBody.addView(accessNote)
        textButton(accessBody, "\"App was denied access\"?") {
            val showing = accessNote.visibility == View.VISIBLE
            accessNote.visibility = if (showing) View.GONE else View.VISIBLE
            Log.d("MainActivity", "accessibility_note_shown=${!showing}")
        }

        // ————————— 2. Manage dictionaries / Bookmarks —————————
        // The "Dictionaries" card is gone: the catalog moved into Get started and
        // the .zip install moved onto the manager's own dialog, so its two
        // remaining destinations are top-level buttons.
        outlinedButton(content, "Manage dictionaries", R.drawable.ic_book) {
            DictionaryManagerDialog.show(this) { importLauncher.launch(arrayOf("application/zip")) }
        }
        outlinedButton(content, "Bookmarks", R.drawable.ic_bookmark) {
            BookmarkViewerDialog.show(this)
        }

        // ————————— 3. Settings —————————
        val settingsBody = newSection("Settings", R.drawable.ic_settings)
        settingsBody.addView(switchRow(
            R.drawable.ic_text_fields,
            "Serif overlay font",
            checked = OverlayFont.face(this) == OverlayFont.FACE_SERIF
        ) { checked ->
            val face = if (checked) OverlayFont.FACE_SERIF else OverlayFont.FACE_SANS
            OverlayFont.setFace(this, face)
            Log.d("MainActivity", "overlay_font_face=$face")
        })
        // #43: pitch-accent display. A user-facing overlay preference, so it sits
        // here with the serif face rather than on the debug screen. The pitch data
        // is bundled and installed at first launch, so there is nothing to install
        // first; off by default.
        settingsBody.addView(switchRow(
            R.drawable.ic_pitch,
            "Display pitch accent",
            checked = PitchAccent.isEnabled(this)
        ) { checked ->
            PitchAccent.setEnabled(this, checked)
            Log.d("MainActivity", "pitch_accent_enabled=$checked")
        })
        settingsBody.addView(listRow(
            R.drawable.ic_gamepad,
            "Gamepad controls",
            trailing = chevron()
        ) { GamepadSettingsDialog.show(this) })
        settingsBody.addView(listRow(
            R.drawable.ic_info,
            "Licenses & attribution",
            trailing = chevron()
        ) { LicenseDialog.show(this) })

        // The debug/tuning surface is its own screen now: the switch that
        // expanded it inline made the user-facing Settings card read as a
        // wall, and the panel deserved room to be readable. Same controls,
        // one level away — see DebugSettingsActivity.
        settingsBody.addView(listRow(
            R.drawable.ic_tune,
            "Debug settings",
            trailing = chevron()
        ) { startActivity(Intent(this, DebugSettingsActivity::class.java)) })

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
     *
     * Deliberately invisible: it is not a user action, so it writes no status.
     * A failure is logged and pitch accents simply do not appear.
     */
    private fun installBundledPitchDictionary() {
        lifecycleScope.launch {
            try {
                val result = DictionaryImporter(applicationContext)
                    .importBundledAsset(PitchAccent.BUNDLED_ASSET) {}
                result.onFailure { e ->
                    Log.e("MainActivity", "bundled pitch dictionary install failed", e)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "bundled pitch dictionary install failed", e)
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
