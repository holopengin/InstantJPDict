package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.holopengin.instantjpdict.util.LicenseEntry
import com.holopengin.instantjpdict.util.LicenseIndex
import java.io.BufferedInputStream

/**
 * #70: the licences and attribution notices for everything the APK bundles.
 *
 * Reads `assets/licenses/INDEX.txt` and the files it points at — there is no
 * network path, because the app declares no `INTERNET` permission. Layout is a
 * component list on top and the selected component's notice plus full licence text
 * below, each individually scrollable: several components share one licence text
 * (every AndroidX module is Apache-2.0), so showing the text once per selection
 * beats repeating 11 KB of it twenty times down a single page. It draws in the
 * app's "Harbour" Material 3 language ([HarbourUi]).
 *
 * The EDRDG licence requires exactly this shape for a smartphone app — the
 * acknowledgement on a screen reached from a menu, not a line on a launch screen.
 */
object LicenseDialog {

    fun show(context: Context) {
        val ui = HarbourUi.of(context)
        val index = try {
            readAsset(context, LicenseIndex.INDEX_ASSET)
                ?: error("${LicenseIndex.INDEX_ASSET} is missing from the APK")
        } catch (t: Throwable) {
            errorDialog(context, "Could not read ${LicenseIndex.INDEX_ASSET}: ${t.message}")
            return
        }

        val entries = try {
            LicenseIndex.parse(index)
        } catch (t: Throwable) {
            errorDialog(context, t.message ?: "Could not parse ${LicenseIndex.INDEX_ASSET}")
            return
        }
        if (entries.isEmpty()) {
            errorDialog(context, "${LicenseIndex.INDEX_ASSET} lists no components")
            return
        }

        val textCache = HashMap<String, String?>()
        fun read(path: String): String? = textCache.getOrPut(path) { readAsset(context, path) }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
            // See HarbourUi.dialogContentHeightPx: this is what lets the two
            // panes absorb the space and keeps the button bar at the bottom.
            minimumHeight = ui.dialogContentHeightPx()
        }

        root.addView(ui.body(
            "${entries.size} bundled components, models and dictionaries."
        ))

        val detail = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            typeface = Typeface.MONOSPACE
            setTextColor(ui.onSurfaceVariant)
            setTextIsSelectable(true)
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        val detailScroll = NestedScrollView(context).apply {
            clipToPadding = false
            addView(
                detail,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                3f
            )
            visibility = android.view.View.INVISIBLE
        }

        // The component list: one selectable row each, highlighted on selection.
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rows = mutableListOf<TextView>()
        var selected: TextView? = null

        fun select(row: TextView, entry: LicenseEntry) {
            selected?.let {
                it.background = null
                it.setTextColor(ui.onSurface)
            }
            row.background = ui.rounded(ui.secondaryContainer, 12)
            row.setTextColor(ui.onSecondaryContainer)
            selected = row
            detail.text = LicenseIndex.render(entry) { read(it) }
            detailScroll.scrollTo(0, 0)
            detailScroll.visibility = android.view.View.VISIBLE
        }

        entries.forEach { entry ->
            val row = TextView(context).apply {
                text = entry.summary()
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(ui.onSurface)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ui.dp(4) }
                setOnClickListener { select(this, entry) }
            }
            rows += row
            listContainer.addView(row)
        }

        val listScroll = NestedScrollView(context).apply {
            clipToPadding = false
            addView(
                listContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                2f
            )
        }
        root.addView(listScroll)

        root.addView(detailScroll)

        rows.firstOrNull()?.let { select(it, entries.first()) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Licenses & Attribution")
            .setView(root)
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
    }

    private fun errorDialog(context: Context, message: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Licenses & Attribution")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Read one asset as UTF-8, or null if it is not in the APK. */
    private fun readAsset(context: Context, path: String): String? = try {
        BufferedInputStream(context.assets.open(path)).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        null
    }

    /** Kept for callers that want the parsed list without opening a dialog. */
    fun entries(context: Context): List<LicenseEntry> {
        val index = readAsset(context, LicenseIndex.INDEX_ASSET) ?: return emptyList()
        return LicenseIndex.parse(index)
    }
}
