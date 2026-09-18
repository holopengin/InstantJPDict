package com.holopengin.instantjpdict

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.Bookmark
import com.holopengin.instantjpdict.util.BookmarkCsv
import com.holopengin.instantjpdict.util.BookmarkSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * #67: the bookmark viewer.
 *
 * A dialog, not a new activity. The other second-level surfaces
 * ([DictionaryManagerDialog], [LicenseDialog]) are dialogs opened from the same
 * MainActivity card, and the viewer is the same size of surface: a short list
 * with a control row. It draws in the app's "Harbour" Material 3 language
 * ([HarbourUi]).
 *
 * Sort default is insertion order (oldest first); the recency option is
 * newest-first. Export writes an RFC 4180 CSV to the app cache and hands it to
 * the system share sheet through the FileProvider the camera already declares —
 * one provider, not a second authority.
 */
object BookmarkViewerDialog {

    fun show(context: Context) {
        val ui = HarbourUi.of(context)
        val db = AppDatabase.getDatabase(context)
        val owner = context as? LifecycleOwner ?: return

        val countLabel = ui.label("")
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = NestedScrollView(context).apply {
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
                1f
            )
        }

        var newestFirst = false
        var rows: List<Bookmark> = emptyList()

        fun renderRows(onRemoved: () -> Unit) {
            listContainer.removeAllViews()
            BookmarkSort.apply(rows, newestFirst).forEach { bookmark ->
                listContainer.addView(row(context, ui, bookmark) {
                    owner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            db.bookmarkDao().deleteByKey(
                                bookmark.kanji, bookmark.reading, bookmark.dictionaryName
                            )
                        }
                        Toast.makeText(context, "Removed ${bookmark.kanji}", Toast.LENGTH_SHORT).show()
                        onRemoved()
                    }
                })
            }
        }

        fun reload() {
            owner.lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) { db.bookmarkDao().getAll() }
                rows = loaded
                BookmarkStore.replace(loaded)
                countLabel.text = when (loaded.size) {
                    0 -> "No bookmarks yet. Save a word from the dictionary."
                    1 -> "1 bookmarked word"
                    else -> "${loaded.size} bookmarked words"
                }
                renderRows { reload() }
            }
        }

        lateinit var sortButton: MaterialButton
        sortButton = ui.outlinedButton(sortLabel(newestFirst)) {
            newestFirst = !newestFirst
            sortButton.text = sortLabel(newestFirst)
            renderRows { reload() }
        }
        val exportButton = ui.tonalButton("Export CSV") {
            // Sorted the same way the list is shown: what is exported is what the
            // user is looking at.
            exportCsv(context, BookmarkSort.apply(rows, newestFirst))
        }
        // The control row shares width evenly; keep the accent on Export.
        sortButton.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = ui.dp(8) }
        exportButton.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
            // See HarbourUi.dialogContentHeightPx: this is what lets the list
            // absorb the space and keeps the button bar at the window's bottom.
            minimumHeight = ui.dialogContentHeightPx()
            addView(countLabel.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ui.dp(10) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, ui.dp(10))
                addView(sortButton)
                addView(exportButton)
            })
            addView(scroll)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Bookmarks")
            .setView(root)
            .setPositiveButton("Close", null)
            .create()
        dialog.show()

        reload()
    }

    private fun sortLabel(newestFirst: Boolean) =
        if (newestFirst) "Sort: newest" else "Sort: oldest"

    private fun row(
        context: Context,
        ui: HarbourUi,
        bookmark: Bookmark,
        onRemove: () -> Unit,
    ): View = ui.card(radiusDp = 20, bottomMarginDp = 10).apply {
        addView(ui.cardBody(padH = 16, padV = 12).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = bookmark.kanji
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    setTextColor(ui.onSurface)
                    setPadding(0, 0, ui.dp(8), 0)
                })
                addView(TextView(context).apply {
                    text = bookmark.reading
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(ui.onSurfaceVariant)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(ui.iconButton(R.drawable.ic_delete, "Remove ${bookmark.kanji}").apply {
                    setOnClickListener { onRemove() }
                })
            })
            if (bookmark.definitionsText.isNotBlank()) {
                addView(TextView(context).apply {
                    text = bookmark.definitionsText
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(ui.onSurfaceVariant)
                    setPadding(0, ui.dp(4), 0, 0)
                })
            }
            addView(ui.label(bookmark.dictionaryName).apply {
                setPadding(0, ui.dp(4), 0, 0)
            })
        })
    }

    /** Write the CSV to the FileProvider-shared cache dir and offer the share sheet. */
    private fun exportCsv(context: Context, rows: List<Bookmark>) {
        val owner = context as? LifecycleOwner ?: return
        val csv = BookmarkCsv.render(rows)
        owner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
                    File(dir, BookmarkCsv.fileName(System.currentTimeMillis()))
                        .apply { writeText(csv, Charsets.UTF_8) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Export Bookmarks"))
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private const val EXPORT_DIR = "bookmarks"

    /** Matches the manifest's `${applicationId}.protofileprovider`. */
    private const val FILE_PROVIDER_SUFFIX = ".protofileprovider"
}
