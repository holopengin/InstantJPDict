package com.holopengin.instantjpdict

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * Gamepad and hardware-key mapping, drawn in the app's "Harbour" Material 3
 * language ([HarbourUi]) like the other second-level dialogs.
 *
 * Preferences live in the `gamepad_prefs` SharedPreferences the overlay reads;
 * every control writes through immediately, so there is no apply step.
 */
object GamepadSettingsDialog {

    fun show(context: Context) {
        val ui = HarbourUi.of(context)
        val prefs = context.getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
        }
        root.addView(ui.body(
            "Map a gamepad or the keyboard to the overlay's actions. Changes apply immediately."
        ))

        val card = ui.card(bottomMarginDp = 8)
        val body = ui.cardBody()
        body.addView(ui.sectionHeader(R.drawable.ic_gamepad, "Controls"))

        /**
         * A [HarbourUi.listRow]-shaped row whose supporting line is returned, so
         * a value that changes with the switch (the button layout) can be
         * updated in place. Kept here rather than in the shared kit because only
         * this dialog needs a live supporting line.
         */
        fun switchRow(
            iconRes: Int,
            title: String,
            supporting: String,
            checked: Boolean,
            onChanged: (Boolean) -> Unit,
        ): Pair<LinearLayout, TextView> {
            val supportingView = ui.label(supporting)
            val toggle = MaterialSwitch(context).apply {
                isChecked = checked
                contentDescription = title
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(56)
                setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { toggle.toggle() }
                ui.bindRipple(this)
                addView(ui.icon(iconRes))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = title
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                        setTextColor(ui.onSurface)
                    })
                    addView(supportingView)
                })
                addView(toggle)
            }
            return row to supportingView
        }

        // Button layout swap. The supporting line is assigned after the row is
        // built; the switch can only fire later, from a user tap.
        var layoutSupporting: TextView? = null
        val layoutPair = switchRow(
            R.drawable.ic_gamepad,
            "Button layout",
            layoutLabel(prefs.getBoolean("layout_swap", false)),
            prefs.getBoolean("layout_swap", false),
        ) { checked ->
            prefs.edit().putBoolean("layout_swap", checked).apply()
            layoutSupporting?.text = layoutLabel(checked)
        }
        layoutSupporting = layoutPair.second
        body.addView(layoutPair.first)

        // Global shortcut.
        val shortcutPair = switchRow(
            R.drawable.ic_gamepad,
            "Global shortcut (L1 + R1)",
            "Opens the overlay from any app",
            prefs.getBoolean("global_shortcut_enabled", true),
        ) { checked ->
            prefs.edit().putBoolean("global_shortcut_enabled", checked).apply()
        }
        body.addView(shortcutPair.first)

        // Key repeat delay: 100..1000 ms.
        val delayValue = prefs.getInt("repeat_delay", 500)
        val delayLabel = ui.label("Key repeat delay: ${delayValue}ms", ui.primary)
        body.addView(delayLabel.apply { setPadding(0, ui.dp(12), 0, 0) })
        body.addView(Slider(context).apply {
            valueFrom = 100f
            valueTo = 1000f
            stepSize = 50f
            value = delayValue.coerceIn(100, 1000).toFloat()
            setLabelFormatter { "${it.toInt()}ms" }
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val v = value.toInt()
                prefs.edit().putInt("repeat_delay", v).apply()
                delayLabel.text = "Key repeat delay: ${v}ms"
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })

        // Key repeat rate: 1..60 repeats per second.
        val rateValue = prefs.getInt("repeat_rate", 20)
        val rateLabel = ui.label("Key repeat rate: $rateValue repeats/s", ui.primary)
        body.addView(rateLabel.apply { setPadding(0, ui.dp(16), 0, 0) })
        body.addView(Slider(context).apply {
            valueFrom = 1f
            valueTo = 60f
            stepSize = 1f
            value = rateValue.coerceIn(1, 60).toFloat()
            setLabelFormatter { "${it.toInt()} repeats/s" }
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val v = value.toInt()
                prefs.edit().putInt("repeat_rate", v).apply()
                rateLabel.text = "Key repeat rate: $v repeats/s"
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })

        card.addView(body)
        root.addView(card)

        MaterialAlertDialogBuilder(context)
            .setTitle("Gamepad Controls")
            .setView(root)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun layoutLabel(swap: Boolean): String =
        "Layout: ${if (swap) "B/A (Nintendo)" else "A/B (Xbox)"}"
}
