package com.holopengin.instantjpdict

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
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
            title = "Debug settings"
            subtitle = "PP-OCR tuning and diagnostics"
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
        behaviourBody.addView(ui.sectionHeader(R.drawable.ic_tune, "Overlay behaviour"))
        behaviourBody.addView(ui.body("Live toggles read at each point of use, so the next lookup picks a change up."))
        // #72: double-tap zoom is opt-in. While it is on, a tap on empty space
        // must wait out the double-tap window before closing.
        behaviourBody.addView(ui.switchRow(
            null,
            "Double-tap to zoom",
            "Makes tap-to-close wait for the double-tap window",
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
            "Vertical text only; fill the blank from the alternatives panel",
            BlankGaps.isEnabled(this)
        ) { checked ->
            BlankGaps.setEnabled(this, checked)
            Log.d(TAG, "blank_gaps_enabled=$checked")
        })
        // #53: rotated-rect detection is opt-in — axis-aligned lines stay the
        // default; the richer geometry is an experiment.
        behaviourBody.addView(ui.switchRow(
            null,
            "Detect rotated lines (experimental)",
            "Fits a minimum-area rectangle and unrotates the crop",
            OcrEngine.isDetRotated(this)
        ) { checked ->
            OcrEngine.setDetRotated(this, checked)
            Log.d(TAG, "det_rotated_enabled=$checked")
        })
        behaviourCard.addView(behaviourBody)
        content.addView(behaviourCard)

        // ————— PP-OCR parameters —————
        val tuningCard = ui.card(bottomMarginDp = 14)
        val tuningContainer = ui.cardBody().apply {
            addView(ui.sectionHeader(R.drawable.ic_settings, "PP-OCR parameters"))
        }
        tuningContainer.addView(ui.body(
            "Tune for tight (but not too tight) crops and no missing っ / punctuation. " +
                "Values are live from SharedPreferences (${OcrEngine.PREFS_NAME}); " +
                "restart the overlay or re-run OCR to apply. Det input is " +
                "${OcrEngine.DET_MODEL_SIZE}×${OcrEngine.DET_MODEL_SIZE} (LONG_SIDE is clamped to it)."
        ))

        // Live summary of the current values.
        val liveSummary = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ui.onSurfaceVariant)
            background = ui.rounded(ui.surfaceHighest, 10)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ui.dp(4) }
        }
        tuningContainer.addView(liveSummary)
        fun refreshLiveSummary() {
            val thresh = prefs.getFloat(OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH)
            val unclip = prefs.getFloat(OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP)
            val longSide = OcrEngine.DEF_DET_LONG_SIDE
            val xOver = prefs.getFloat(OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP)
            val squish = prefs.getFloat(OcrEngine.PREF_REC_SQUISH, OcrEngine.DEF_REC_SQUISH)
            // F5/#86: Locale.ROOT, so the decimal separator in this readout does
            // not follow the phone's locale (lint's DefaultLocale).
            liveSummary.text = "live: detThresh=${String.format(Locale.ROOT, "%.2f", thresh)} " +
                "unclip=${String.format(Locale.ROOT, "%.2f", unclip)} longSide=$longSide " +
                "xOver=${String.format(Locale.ROOT, "%.2f", xOver)} " +
                "squish=${String.format(Locale.ROOT, "%.1f", squish)}"
        }
        refreshLiveSummary()

        // One tunable row: label + live value + Slider + EditText + Apply/Reset.
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
            // step happens on touch-up / Apply: a stepped slider whose range is
            // not exactly divisible in float would refuse to lay out at all.
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
            }
            val edit = EditText(this).apply {
                setText(formatValue(cur))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(ui.onSurface)
                inputType = if (isInt) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                background = ui.rounded(ui.surfaceHighest, 10)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                minimumHeight = ui.dp(48)
                setSelectAllOnFocus(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = ui.dp(8) }
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
                ).apply { marginEnd = ui.dp(8) }
            }
            editRow.addView(btnApply)
            val btnReset = MaterialButton(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "Reset"
                isAllCaps = false
                setTextColor(ui.primary)
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
                    Toast.makeText(this@DebugSettingsActivity, "$label = ${formatValue(v)}", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "tuning $prefKey = $v")
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
                Log.d(TAG, "tuning $prefKey = $v (via EditText)")
            }
            btnReset.setOnClickListener {
                if (isInt) prefs.edit().putInt(prefKey, default.roundToInt()).apply()
                else prefs.edit().putFloat(prefKey, default).apply()
                slider.value = default.coerceIn(min, max)
                edit.setText(formatValue(default))
                tvLive.text = "current ${formatValue(default)} · default ${formatValue(default)}"
                refreshLiveSummary()
                Toast.makeText(this, "$label reset to ${formatValue(default)}", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "tuning $prefKey reset to $default")
            }

            // If the user types, update the preview without committing.
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
    }
}
