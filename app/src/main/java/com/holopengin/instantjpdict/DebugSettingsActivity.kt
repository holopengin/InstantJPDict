package com.holopengin.instantjpdict

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.InferLog
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * #14/#86: the debug/tuning screen, moved off the home screen.
 *
 * The home screen used to carry a "Debug settings" switch that expanded this
 * whole panel inline, in the middle of the user-facing Settings card. It is now
 * a button that opens this activity: the same controls, one level away, so the
 * home screen stays a home screen and the panel gets room to be readable.
 *
 * The behaviour is unchanged from the inline panel: the same `SharedPreferences`
 * keys, the same [DebugTuning.rows]/[DebugTuning.features] iteration (so
 * "Reset all tuning to defaults" still cannot miss a control), the same
 * "Copy inference log" clipboard payload. Only the surface moved.
 */
class DebugSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ui = HarbourUi.of(this)
        val prefs = getSharedPreferences(OcrEngine.PREFS_NAME, MODE_PRIVATE)

        val root = CoordinatorLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "Debug Settings"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = NestedScrollView(this).apply {
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

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appBar.setPadding(0, bars.top, 0, 0)
            scroll.setPadding(
                ui.dp(20) + bars.left,
                ui.dp(12),
                ui.dp(20) + bars.right,
                ui.dp(24) + bars.bottom
            )
            insets
        }

        setContentView(root)

        // ————— Overlay behaviour —————
        val behaviourCard = ui.card(bottomMarginDp = 14)
        val behaviourBody = ui.cardBody()
        // #72: double-tap zoom is opt-in. While it is on, a tap on empty space
        // must wait out the double-tap window before closing.
        behaviourBody.addView(ui.switchRow(
            null,
            "Double-tap to zoom",
            "Downside: adds a delay to opening/closing the dictionary popup",
            DoubleTapZoom.isEnabled(this)
        ) { checked ->
            DoubleTapZoom.setEnabled(this, checked)
            Log.d(TAG, "double_tap_zoom_enabled=$checked")
        })
        // #44 Feature 2: clickable blanks where the vertical spacing says a
        // character was dropped. Vertical only.
        behaviourBody.addView(ui.switchRow(
            null,
            "Clickable blanks where a character looks missing",
            "Vertical text only; mostly affects punctuation",
            BlankGaps.isEnabled(this)
        ) { checked ->
            BlankGaps.setEnabled(this, checked)
            Log.d(TAG, "blank_gaps_enabled=$checked")
        })
        // #53: rotated-rect detection is ON by default now; this switch turns it
        // off back to the axis-aligned path. The richer geometry still has the
        // narrower edge-case coverage, hence "experimental".
        behaviourBody.addView(ui.switchRow(
            null,
            "Detect rotated lines (experimental)",
            "Enables handling of tilted text lines. Most useful for the camera",
            OcrEngine.isDetRotated(this)
        ) { checked ->
            OcrEngine.setDetRotated(this, checked)
            Log.d(TAG, "det_rotated_enabled=$checked")
        })
        // #28/#100: the furigana rule is OFF by default — it is flaky on camera
        // photos and a wrong drop costs a whole line. Two switches, because the
        // entry points differ in image quality: screenshots are crisp, camera
        // photos are not.
        behaviourBody.addView(ui.switchRow(
            null,
            "Filter furigana in screenshots",
            "Overlay captures and shared images",
            OcrEngine.isDetFurigana(this, OcrEngine.PREF_DET_FURIGANA_SCREEN)
        ) { checked ->
            OcrEngine.setDetFurigana(this, checked, OcrEngine.PREF_DET_FURIGANA_SCREEN)
            Log.d(TAG, "det_furigana_screen_enabled=$checked")
        })
        behaviourBody.addView(ui.switchRow(
            null,
            "Filter furigana in camera mode",
            "Photos taken with the viewfinder",
            OcrEngine.isDetFurigana(this, OcrEngine.PREF_DET_FURIGANA_CAMERA)
        ) { checked ->
            OcrEngine.setDetFurigana(this, checked, OcrEngine.PREF_DET_FURIGANA_CAMERA)
            Log.d(TAG, "det_furigana_camera_enabled=$checked")
        })
        // research/char-placement: CAP places each char box from the CTC
        // activation runs, a fitted advance-class template, ink refinement and
        // a final boundary pass. ON for the device A/B; off restores the
        // shipped chain, so a line that looks worse can be attributed.
        behaviourBody.addView(ui.switchRow(
            null,
            "CTC-anchored char placement (experimental)",
            "Char boxes from activation runs + ink; off = the shipped chain",
            prefs.getBoolean(OcrEngine.PREF_BOX_PLACEMENT_CAP, OcrEngine.DEF_BOX_PLACEMENT_CAP)
        ) { checked ->
            prefs.edit().putBoolean(OcrEngine.PREF_BOX_PLACEMENT_CAP, checked).apply()
            Log.d(TAG, "box_placement_cap=$checked")
        })
        behaviourCard.addView(behaviourBody)
        content.addView(behaviourCard)

        // ————— OCR parameters —————
        val tuningCard = ui.card(bottomMarginDp = 14)
        val tuningContainer = ui.cardBody().apply {
            addView(ui.sectionHeader(R.drawable.ic_settings, "OCR parameters"))
        }
        tuningContainer.addView(ui.body(
            "Tuning parameters for the OCR pipeline. The defaults here are the best in " +
                "*most* scenarios for Japanese text. If you want better recognition for " +
                "Latin (English) text, try setting the REC_SQUISH_FACTOR higher than 0.5."
        ))

        // One tunable row: label + live value + Slider + Reset.
        fun addTunable(row: TuningRow) {
            val label = row.label
            val prefKey = row.key
            val default = row.default
            val min = row.min
            val max = row.max
            val step = row.step
            val isInt = row.isInt
            // Enough decimals to resolve a single step — CROSSHAIR_GAP steps by
            // 0.005, which "%.2f" collapsed into indistinct values — and never
            // fewer than the two the readouts have always shown.
            val decimals = if (step > 0f) maxOf(2, -floor(log10(step.toDouble())).toInt()) else 2
            fun formatValue(v: Float): String =
                if (isInt) v.roundToInt().toString()
                else String.format(Locale.ROOT, "%.${decimals}f", v)
            // Material's Slider refuses to lay out when the range is not exactly
            // divisible by stepSize in float (0.2..1.0 step 0.1 is not), which is
            // why these rows ran continuous. A row with few enough steps instead
            // drives the slider on an integer index scale (0..steps, step 1),
            // which divides exactly and lets Material draw its tick dots; denser
            // rows stay continuous and snap on touch-up.
            val steps = if (step > 0f) ((max - min) / step).roundToInt() else 0
            val discrete = steps in 1..MAX_TICK_STEPS
            fun valueForIndex(i: Float): Float = (min + i * step).coerceIn(min, max)
            fun indexForValue(v: Float): Float = ((v - min) / step).roundToInt().toFloat()
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
                setPadding(0, ui.dp(10), 0, ui.dp(4))
            }

            container.addView(TextView(this).apply {
                text = label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(ui.onSurface)
            })

            val tvLive = TextView(this).apply {
                text = "current ${formatValue(cur)} · default ${formatValue(default)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(ui.primary)
            }
            container.addView(tvLive)

            val slider = Slider(this).apply {
                if (discrete) {
                    valueFrom = 0f
                    valueTo = steps.toFloat()
                    stepSize = 1f
                    value = indexForValue(cur)
                } else {
                    valueFrom = min
                    valueTo = max
                    stepSize = 0f
                    value = cur
                }
                setLabelFormatter { v -> formatValue(if (discrete) valueForIndex(v) else v) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(slider)

            val btnReset = MaterialButton(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "Reset"
                isAllCaps = false
                setTextColor(ui.primary)
            }
            container.addView(btnReset)

            slider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val shown = if (discrete) valueForIndex(value) else value
                tvLive.text = "current ${formatValue(shown)} · default ${formatValue(default)}"
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) {}
                override fun onStopTrackingTouch(s: Slider) {
                    val v = if (discrete) valueForIndex(s.value) else snap(s.value)
                    if (isInt) prefs.edit().putInt(prefKey, v.roundToInt()).apply()
                    else prefs.edit().putFloat(prefKey, v).apply()
                    // Only the continuous slider can land between its steps.
                    if (!discrete && s.value != v) s.value = v
                    tvLive.text = "current ${formatValue(v)} · default ${formatValue(default)}"
                    Log.d(TAG, "tuning $prefKey = $v")
                }
            })

            btnReset.setOnClickListener {
                if (isInt) prefs.edit().putInt(prefKey, default.roundToInt()).apply()
                else prefs.edit().putFloat(prefKey, default).apply()
                slider.value = if (discrete) indexForValue(default) else default.coerceIn(min, max)
                tvLive.text = "current ${formatValue(default)} · default ${formatValue(default)}"
                Log.d(TAG, "tuning $prefKey reset to $default")
            }

            tuningContainer.addView(container)
        }

        DebugTuning.rows.forEach { addTunable(it) }

        tuningContainer.addView(ui.outlinedButton("Copy inference log") {
            val text = InferLog.dump()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("infer-log", text))
            Toast.makeText(this, "Inference log copied (${text.lines().size} lines)", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "inference log copied")
        })

        tuningContainer.addView(ui.outlinedButton("Reset all tuning to defaults") {
            // G1/#86: one source for the controls and the reset. Every row/feature
            // here comes from [DebugTuning], so there is no second list to keep in
            // step (which is exactly how a new control used to survive a reset).
            val editor = prefs.edit()
            DebugTuning.rows.forEach { row ->
                if (row.isInt) editor.putInt(row.key, row.default.roundToInt())
                else editor.putFloat(row.key, row.default)
            }
            DebugTuning.features.forEach { editor.putBoolean(it.key, it.default) }
            editor.apply()
            Toast.makeText(this, "All tuning reset to defaults", Toast.LENGTH_LONG).show()
            Log.d(TAG, "all tuning reset to defaults")
            // Recreate to refresh the sliders and the feature switches.
            recreate()
        })

        tuningCard.addView(tuningContainer)
        content.addView(tuningCard)
    }

    private companion object {
        const val TAG = "DebugSettings"

        /**
         * A tunable is drawn with discrete ticks only when its range has no more
         * steps than this; denser rows (250 steps for the unclip ratio, 99 for
         * the kana epsilon) stay continuous, because a tick per step would be a
         * comb. The ticked rows are the coarse ones: REC_SQUISH_FACTOR (8),
         * OVERLAY_SCREENSHOT_ALPHA (14) and CROSSHAIR_GAP (19).
         */
        const val MAX_TICK_STEPS = 30
    }
}
