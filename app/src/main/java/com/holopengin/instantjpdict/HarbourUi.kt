package com.holopengin.instantjpdict

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * The "Harbour" Material 3 surface primitives, in one place for the app's
 * second-level screens — the debug screen (#14/#86) and the dictionary manager,
 * bookmarks, licences and catalog dialogs.
 *
 * These reproduce the exact values MainActivity draws the home screen with
 * (24 dp tonal cards on `colorSurfaceContainerLow`, the Material 3 type scale,
 * `MaterialButton` roles, ≥56 dp list rows). They live here rather than being
 * re-typed per dialog so those screens cannot silently drift from the home
 * screen's look; every colour is resolved from the theme, so day/night and
 * Material You flow through without a hand-maintained palette.
 *
 * One instance per surface (it caches the resolved theme roles); it is
 * short-lived, so holding the [Context] for the surface's lifetime is fine.
 */
internal class HarbourUi private constructor(private val context: Context) {

    val surfaceLow = color(com.google.android.material.R.attr.colorSurfaceContainerLow)
    val surfaceHigh = color(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    val surfaceHighest = color(com.google.android.material.R.attr.colorSurfaceContainerHighest)
    val onSurface = color(com.google.android.material.R.attr.colorOnSurface)
    val onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    val primary = color(com.google.android.material.R.attr.colorPrimary)
    val onPrimary = color(com.google.android.material.R.attr.colorOnPrimary)
    val primaryContainer = color(com.google.android.material.R.attr.colorPrimaryContainer)
    val onPrimaryContainer = color(com.google.android.material.R.attr.colorOnPrimaryContainer)
    val secondaryContainer = color(com.google.android.material.R.attr.colorSecondaryContainer)
    val onSecondaryContainer = color(com.google.android.material.R.attr.colorOnSecondaryContainer)
    val error = color(com.google.android.material.R.attr.colorError)
    val onError = color(com.google.android.material.R.attr.colorOnError)
    val errorContainer = color(com.google.android.material.R.attr.colorErrorContainer)
    val onErrorContainer = color(com.google.android.material.R.attr.colorOnErrorContainer)
    val outlineVariant = color(com.google.android.material.R.attr.colorOutlineVariant)

    /**
     * The catalog's "Recommended" badge. A positive green has no Material 3
     * role, so it is a named app colour (defined per day and night in the
     * values colour files) rather than a theme attribute.
     */
    val recommended = context.getColor(R.color.md_recommended)
    val onRecommended = context.getColor(R.color.md_on_recommended)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Resolve one colour from the current theme (day/night and Material You aware). */
    fun color(attribute: Int): Int =
        MaterialColors.getColor(context, attribute, android.graphics.Color.GRAY)

    /** A rounded solid-colour background for the few views the theme does not style. */
    fun rounded(fill: Int, radiusDp: Int): MaterialShapeDrawable = MaterialShapeDrawable(
        ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, dp(radiusDp).toFloat())
            .build()
    ).apply { fillColor = ColorStateList.valueOf(fill) }

    /** A fully-rounded pill background, for badges. */
    fun pill(fill: Int): MaterialShapeDrawable = rounded(fill, 100)

    /** Give a view the theme's selectable-item ripple. */
    fun bindRipple(view: View) {
        val out = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            view.setBackgroundResource(out.resourceId)
        }
    }

    /** Give a view the theme's borderless ripple (for icon buttons). */
    fun bindBorderlessRipple(view: View) {
        val out = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)) {
            view.setBackgroundResource(out.resourceId)
        }
    }

    /** A 48 dp-touch-target icon button: a 24 dp glyph with a borderless ripple. */
    fun iconButton(res: Int, contentDescription: String, tint: Int = onSurfaceVariant): ImageButton =
        ImageButton(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            this.contentDescription = contentDescription
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            scaleType = ImageView.ScaleType.CENTER
            bindBorderlessRipple(this)
        }

    /**
     * A tonal card: the grouping unit. Elevation 0 and no stroke, so sections
     * are separated by their tonal step rather than by a border or a shadow.
     */
    fun card(
        fill: Int = surfaceLow,
        radiusDp: Int = 24,
        bottomMarginDp: Int = 0,
    ): MaterialCardView = MaterialCardView(context).apply {
        radius = dp(radiusDp).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(fill)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(bottomMarginDp) }
    }

    /** The vertical body inside a [card], with the standard inner padding. */
    fun cardBody(padH: Int = 20, padV: Int = 16): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    /** A section header row: optional leading icon + a TitleMedium heading. */
    fun sectionHeader(iconRes: Int?, text: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(4))
        if (iconRes != null) addView(icon(iconRes))
        addView(TextView(this@HarbourUi.context).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(onSurface)
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    /** A paragraph of supporting copy. */
    fun body(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(onSurfaceVariant)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    /** A small metadata line. */
    fun label(text: String, color: Int = onSurfaceVariant): TextView = TextView(context).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(color)
    }

    /** A 24 dp themed icon. */
    fun icon(res: Int, tint: Int? = null): ImageView = ImageView(context).apply {
        setImageResource(res)
        if (tint != null) imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun chevron(): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron)
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * A settings-style row: icon, title, optional supporting line and trailing
     * view, ≥56 dp tall, rippled when clickable.
     */
    fun listRow(
        iconRes: Int?,
        title: String,
        supporting: String? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        isClickable = onClick != null
        isFocusable = onClick != null
        onClick?.let { cb -> setOnClickListener { cb() } }
        bindRipple(this)
        if (iconRes != null) addView(icon(iconRes))
        addView(LinearLayout(this@HarbourUi.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@HarbourUi.context).apply {
                text = title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(onSurface)
            })
            if (supporting != null) {
                addView(TextView(this@HarbourUi.context).apply {
                    text = supporting
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(onSurfaceVariant)
                })
            }
        })
        if (trailing != null) addView(trailing)
    }

    /** A [listRow] whose whole row toggles a switch. */
    fun switchRow(
        iconRes: Int?,
        title: String,
        supporting: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout {
        val toggle = MaterialSwitch(context).apply {
            text = null
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        return listRow(iconRes, title, supporting, trailing = toggle) { toggle.toggle() }
    }

    fun filledButton(text: String, iconRes: Int? = null, onClick: () -> Unit): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
            isAllCaps = false
            if (iconRes != null) {
                setIconResource(iconRes)
                iconTint = ColorStateList.valueOf(onPrimary)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

    fun tonalButton(text: String, iconRes: Int? = null, onClick: () -> Unit): MaterialButton =
        filledButton(text, iconRes, onClick).apply {
            backgroundTintList = ColorStateList.valueOf(secondaryContainer)
            setTextColor(onSecondaryContainer)
            iconTint = ColorStateList.valueOf(onSecondaryContainer)
        }

    fun outlinedButton(text: String, iconRes: Int? = null, onClick: () -> Unit): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            if (iconRes != null) setIconResource(iconRes)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

    fun textButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            this.text = text
            isAllCaps = false
            setTextColor(primary)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    /**
     * Give a Material 3 dialog a definite height so a weighted scroll child can
     * resolve, while keeping its width inside the 560 dp dialog cap.
     */
    fun sizeDialogWindow(dialog: android.app.Dialog, widthFraction: Float = 0.94f, heightFraction: Float = 0.82f) {
        val metrics = context.resources.displayMetrics
        dialog.window?.setLayout(
            minOf((metrics.widthPixels * widthFraction).toInt(), dp(560)),
            (metrics.heightPixels * heightFraction).toInt(),
        )
    }

    companion object {
        fun of(context: Context): HarbourUi = HarbourUi(context)
    }
}
