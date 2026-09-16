package com.holopengin.instantjpdict

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
 * A dialog, not a new activity. The two existing second-level screens
 * (`DictionaryManagerDialog`, `LicenseDialog`) are dialogs opened from the same
 * MainActivity button column, and the viewer is the same size of surface: a
 * short list with a control row. A dialog needs no manifest entry and no second
 * `AppCompatActivity` lifecycle; the trade is that it competes with the host
 * window for height, which a bookmark list of tens of rows does not.
 *
 * Sort default is insertion order (oldest first); the recency option is
 * newest-first. Export writes an RFC 4180 CSV to the app cache and hands it to
 * the system share sheet through the FileProvider the camera already declares —
 * one provider, not a second authority.
 */
object BookmarkViewerDialog {

    fun show(context: Context) {
        val db = AppDatabase.getDatabase(context)
        val owner = context as? LifecycleOwner ?: return

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        val scroll = ScrollView(context).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val countLabel = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 4))
        }

        var newestFirst = false
        var rows: List<Bookmark> = emptyList()

        fun renderRows(onRemoved: () -> Unit) {
            listContainer.removeAllViews()
            BookmarkSort.apply(rows, newestFirst).forEach { bookmark ->
                listContainer.addView(row(context, bookmark) {
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
                BookmarkStore.refresh(context)
                countLabel.text = when (loaded.size) {
                    0 -> "No bookmarks yet. Save a word from the dictionary popup."
                    1 -> "1 bookmarked headword"
                    else -> "${loaded.size} bookmarked headwords"
                }
                renderRows { reload() }
            }
        }

        val sortButton = Button(context).apply {
            text = sortLabel(newestFirst)
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                newestFirst = !newestFirst
                text = sortLabel(newestFirst)
                renderRows { reload() }
            }
        }
        val exportButton = Button(context).apply {
            text = "Export CSV"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // Sorted the same way the list is shown: what is exported is what the
            // user is looking at.
            setOnClickListener { exportCsv(context, BookmarkSort.apply(rows, newestFirst)) }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), 0)
            addView(countLabel)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(sortButton)
                addView(exportButton)
            })
            addView(scroll)
        }

        AlertDialog.Builder(context)
            .setTitle("Bookmarks")
            .setView(root)
            .setPositiveButton("Close", null)
            .show()

        reload()
    }

    private fun sortLabel(newestFirst: Boolean) =
        if (newestFirst) "Sort: newest first" else "Sort: oldest first"

    private fun row(
        context: Context,
        bookmark: Bookmark,
        onRemove: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))

        val headword = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = bookmark.kanji
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, dp(context, 8), 0)
            })
            addView(TextView(context).apply {
                text = bookmark.reading
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "✕"
                textSize = 18f
                setPadding(dp(context, 12), 0, dp(context, 4), 0)
                isClickable = true
                setOnClickListener { onRemove() }
            })
        }
        addView(headword)

        if (bookmark.definitionsText.isNotBlank()) {
            addView(TextView(context).apply {
                text = bookmark.definitionsText
                textSize = 13f
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        addView(TextView(context).apply {
            text = bookmark.dictionaryName
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(context, 2), 0, 0)
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
                context.startActivity(Intent.createChooser(send, "Export bookmarks"))
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private const val EXPORT_DIR = "bookmarks"

    /** Matches the manifest's `${applicationId}.protofileprovider`. */
    private const val FILE_PROVIDER_SUFFIX = ".protofileprovider"

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
