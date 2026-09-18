package com.holopengin.instantjpdict

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryDownloader
import com.holopengin.instantjpdict.data.DictionaryImporter
import com.holopengin.instantjpdict.util.CatalogEntry
import com.holopengin.instantjpdict.util.DictionaryCatalog
import com.holopengin.instantjpdict.util.ImportProgress
import com.holopengin.instantjpdict.util.InstalledDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * #71: the in-app dictionary catalog — browse the bundled static list and
 * import a dictionary in one tap.
 *
 * Each row is one [CatalogEntry]: a Yomitan zip fetched from a pinned, dated
 * upstream URL and integrity-checked before import. A row shows download
 * progress and can be cancelled. Bundled dictionaries (the Kanjium pitch
 * accents) are not listed — they install themselves at first launch.
 *
 * The surface is the app's "Harbour" Material 3 language (main-screen redesign):
 * one tonal card per dictionary on `colorSurfaceContainerLow`, the Material 3
 * type scale instead of hand-set sizes, `MaterialButton` roles for the actions,
 * an M3 progress indicator, and every colour read from the theme — so day/night
 * and Material You both flow through without a hand-maintained palette. That
 * retires the old `CatalogPalette`, which existed only because the dialog was
 * not themed.
 *
 * Two things this deliberately reuses rather than reimplements:
 *  - `DictionaryImporter.importZip` for the actual insert, so the catalog and
 *    the file picker write rows through one path; and
 *  - `DictionaryMeta` for installed state, so the row says "Installed" from the
 *    same truth the manager uses.
 *
 * Rows name each dictionary's licence, but the full texts live in the main
 * screen's Licenses & attribution surface — the catalog carries no copy of its
 * own and no second way in.
 *
 * Offline is handled on the response, not asked about first: reading
 * connectivity costs `ACCESS_NETWORK_STATE`, and #71's acceptance criterion is
 * that `INTERNET` is the ONLY permission added. So a download that cannot reach
 * the network reports "Download unavailable" from the failure itself, and the
 * row offers Retry. See docs/dictionary-catalog.md.
 */
object DictionaryCatalogDialog {

    private const val TAG = "DictionaryCatalog"

    fun show(context: Context) {
        val ui = HarbourUi.of(context)
        val entries = try {
            DictionaryCatalog.parse(
                readAsset(context, DictionaryCatalog.ASSET)
                    ?: error("${DictionaryCatalog.ASSET} is missing from the APK")
            )
        } catch (t: Throwable) {
            errorDialog(context, "Could not read the dictionary catalog: ${t.message}")
            return
        }

        val owner = context as? LifecycleOwner
        val scope = owner?.lifecycleScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val main = Handler(Looper.getMainLooper())
        fun ui(block: () -> Unit) {
            main.post(block)
        }

        val downloader = DictionaryDownloader(context)
        val importer = DictionaryImporter(context)
        val dao = AppDatabase.getDatabase(context).dictionaryDao()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
        }

        root.addView(TextView(context).apply {
            text = "Single-click download and install for popular Yomitan format dictionaries."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(ui.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ui.dp(12) }
        })

        // Shown only after a download could not reach the network. The app holds
        // no permission to read connectivity state, so this is discovered from
        // the failure rather than asked ahead of time.
        val bannerText = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ui.onErrorContainer)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val banner = MaterialCardView(context).apply {
            radius = ui.dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ui.errorContainer)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ui.dp(12) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_info)
                    imageTintList = ColorStateList.valueOf(ui.onErrorContainer)
                    layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20))
                        .apply { marginEnd = ui.dp(12) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(bannerText)
            })
        }
        root.addView(banner)

        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rowsScroll = NestedScrollView(context).apply {
            clipToPadding = false
            addView(
                rowsContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            setPadding(0, ui.dp(2), 0, ui.dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(rowsScroll)

        val jobs = mutableMapOf<String, Job>()
        var installedIds: Set<String> = emptySet()
        val rows = mutableListOf<CatalogRowView>()

        fun refreshRows() {
            rows.forEach { it.bindState(it.entry.id in installedIds) }
        }

        suspend fun refreshInstalledState() {
            // #71 follow-up: carry `catalogId` so the two JMdict variants, whose
            // upstream titles are identical, resolve to exactly one installed
            // row rather than both.
            val installed = withContext(Dispatchers.IO) {
                dao.getAllDictionaries().map { InstalledDictionary(it.name, it.catalogId) }
            }
            installedIds = DictionaryCatalog.installedIds(entries, installed)
            ui { refreshRows() }
        }

        fun startImport(entry: CatalogEntry, row: CatalogRowView) {
            if (jobs[entry.id]?.isActive == true) return
            row.showBusy("Preparing…", cancellable = true)
            jobs[entry.id] = scope.launch {
                try {
                    val result = downloader.download(entry) { written ->
                        ui { row.showDownloadProgress(written, entry.bytes, entry.fileName()) }
                    }
                    // The file is held here so the finally below can delete
                    // it on every path that does not reach the import.
                    val file = result
                    try {
                        ui {
                            row.showBusy(
                                ImportProgress.verify(entry.bytes, entry.fileName()),
                                cancellable = false,
                            )
                        }
                        // Once the file is verified the download is done and
                        // Cancel is gone: an import that has started must not
                        // be interrupted, because a half-written dictionary
                        // has no completion marker and would look installed.
                        val imported = withContext(NonCancellable) {
                            importer.importZip(Uri.fromFile(file), entry.name, catalogId = entry.id) { n ->
                                ui {
                                    row.showBusy(
                                        ImportProgress.importing(
                                            processed = n,
                                            total = null,
                                            name = entry.name,
                                            indeterminate = true,
                                        ),
                                        cancellable = false,
                                    )
                                }
                            }
                        }
                        imported.getOrThrow()
                    } finally {
                        file.delete()
                    }
                    ui { banner.visibility = View.GONE }
                    refreshInstalledState()
                } catch (e: CancellationException) {
                    ui { refreshRows() }
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Catalog import failed for ${entry.id}", e)
                    ui {
                        if (e.isConnectionFailure()) {
                            bannerText.text =
                                "Download unavailable — no connection. Connect and tap Retry."
                            banner.visibility = View.VISIBLE
                            row.showFailure("Download unavailable — no connection")
                        } else {
                            row.showFailure(ImportProgress.failure(e.message))
                        }
                    }
                } finally {
                    jobs.remove(entry.id)
                }
            }
        }

        /**
         * Delete the installed dictionary this row represents. Removal is
         * destructive, so it is confirmed first; the row then refreshes from the
         * same installed-state read the manager uses.
         */
        fun startRemove(entry: CatalogEntry, row: CatalogRowView) {
            if (jobs[entry.id]?.isActive == true) return
            MaterialAlertDialogBuilder(context)
                .setTitle("Remove Dictionary")
                .setMessage("Delete '${entry.name}'? All of its entries and tags will be removed.")
                .setPositiveButton("Remove") { _, _ ->
                    row.showBusy("Removing…", cancellable = false)
                    jobs[entry.id] = scope.launch {
                        try {
                            val removed = withContext(Dispatchers.IO) {
                                val metas = dao.getAllDictionaries()
                                // A catalog install carries its id; a file-picker
                                // install is matched by its title family instead.
                                val meta = metas.firstOrNull { it.catalogId == entry.id }
                                    ?: metas.firstOrNull {
                                        it.catalogId == null &&
                                            DictionaryCatalog.baseTitle(it.name) == entry.title
                                    }
                                if (meta == null) {
                                    false
                                } else {
                                    dao.deleteEntriesForDictionary(meta.id)
                                    dao.deleteTagsForDictionary(meta.id)
                                    dao.deleteDictionary(meta.id)
                                    true
                                }
                            }
                            ui { banner.visibility = View.GONE }
                            if (removed) refreshInstalledState() else ui { refreshRows() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Catalog remove failed for ${entry.id}", e)
                            ui { row.showFailure("Could not remove the dictionary.") }
                        } finally {
                            jobs.remove(entry.id)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        entries.forEach { entry ->
            val row = CatalogRowView(
                context = context,
                entry = entry,
                ui = ui,
                onImport = { r -> startImport(entry, r) },
                onRemove = { r -> startRemove(entry, r) },
                onCancel = { jobs[entry.id]?.cancel() },
            )
            rows += row
            rowsContainer.addView(row.root)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Dictionary Catalog")
            .setView(root)
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener {
            // A definite window height is what gives the list its weight and lets
            // it scroll, while the intro, banner and buttons stay put.
            ui.sizeDialogWindow(dialog)
        }
        dialog.setOnDismissListener {
            jobs.values.forEach { it.cancel() }
            if (owner == null) scope.cancel()
        }

        scope.launch { refreshInstalledState() }
        dialog.show()
    }

    /**
     * True for the exceptions a download raises when it cannot reach the host —
     * no DNS, refused connection, no route, timeout. Used to tell "you have no
     * connection" apart from "the server answered with something unexpected"
     * without asking the platform for connectivity state.
     */
    private fun Throwable.isConnectionFailure(): Boolean =
        this is UnknownHostException || this is ConnectException ||
            this is SocketTimeoutException || this is SocketException

    /** One catalog row: a tonal card and the states it can show. */
    private class CatalogRowView(
        private val context: Context,
        val entry: CatalogEntry,
        private val ui: HarbourUi,
        private val onImport: (CatalogRowView) -> Unit,
        private val onRemove: (CatalogRowView) -> Unit,
        private val onCancel: () -> Unit,
    ) {
        private val installedChip = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setTextColor(ui.onPrimaryContainer)
            background = ui.pill(ui.primaryContainer)
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
            visibility = View.GONE
        }

        /**
         * #71 follow-up: the green nudge on an uninstalled recommended entry. It
         * shares the installed chip's slot, so only one of the two is ever
         * visible; an installed row shows "Installed" instead.
         */
        private val recommendedChip = TextView(context).apply {
            text = "Recommended"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setTextColor(ui.onRecommended)
            background = ui.pill(ui.recommended)
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
            visibility = View.GONE
        }

        private val stateView = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ui.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        /**
         * The line above the bar, split in two.
         *
         * [stateView] carries the phase in words ("Downloading JMdict_english.zip
         * — 4.0 MB / 15.6 MB"); this carries the percentage alone, right-aligned
         * so it is a fixed column the eye can find. A determinate bar alone
         * shows "not done" but not "how far" — this is the number that says it.
         */
        private val percentView = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ui.onSurfaceVariant)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        private val progress = LinearProgressIndicator(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            trackCornerRadius = ui.dp(4)
            visibility = View.GONE
        }

        private val progressBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, ui.dp(10), 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(stateView)
                addView(percentView)
            })
            addView(progress)
        }

        private val importButton = MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = "Install"
            isAllCaps = false
            setOnClickListener { onImport(this@CatalogRowView) }
        }

        // The filled style's own tint and text colour, captured so an installed
        // row can turn the same button red for Remove and restore it after.
        private val importButtonTint = importButton.backgroundTintList
        private val importButtonTextColor = importButton.currentTextColor

        private val cancelButton = MaterialButton(
            context, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(ui.primary)
            visibility = View.GONE
            setOnClickListener { onCancel() }
        }

        val root = MaterialCardView(context).apply {
            radius = ui.dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ui.surfaceLow)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ui.dp(12) }
        }

        init {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_download)
                        layoutParams = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24))
                            .apply { marginEnd = ui.dp(12) }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(TextView(context).apply {
                        text = entry.name
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                        setTextColor(ui.onSurface)
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(installedChip)
                    addView(recommendedChip)
                })
                addView(TextView(context).apply {
                    text = entry.description
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(ui.onSurfaceVariant)
                    setPadding(0, ui.dp(6), 0, 0)
                })
                addView(TextView(context).apply {
                    text = "${entry.sizeLabel()} · ${entry.license} · ${entry.source}"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(ui.onSurfaceVariant)
                    setPadding(0, ui.dp(4), 0, 0)
                })
                addView(progressBlock)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ui.dp(10), 0, 0)
                    addView(importButton)
                    addView(cancelButton.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { leftMargin = ui.dp(8) }
                    })
                })
            })
        }

        /** Back to the resting state: installed, or ready to import. */
        fun bindState(installed: Boolean) {
            progressBlock.visibility = View.GONE
            progress.isIndeterminate = false
            percentView.visibility = View.GONE
            cancelButton.visibility = View.GONE
            importButton.isEnabled = true
            if (installed) {
                installedChip.text = "Installed"
                installedChip.visibility = View.VISIBLE
                recommendedChip.visibility = View.GONE
                // An installed row's action is removal, not re-install; the red
                // filled button signals the destructive turn.
                importButton.text = "Remove"
                importButton.backgroundTintList = ColorStateList.valueOf(ui.error)
                importButton.setTextColor(ui.onError)
                importButton.setOnClickListener { onRemove(this@CatalogRowView) }
            } else {
                installedChip.visibility = View.GONE
                recommendedChip.visibility = if (entry.recommended) View.VISIBLE else View.GONE
                importButton.text = "Install"
                importButton.backgroundTintList = importButtonTint
                importButton.setTextColor(importButtonTextColor)
                importButton.setOnClickListener { onImport(this@CatalogRowView) }
            }
            stateView.text = ""
        }

        /**
         * Determinate download progress: the phase in words on the left, the
         * percentage on the right, the bar below.
         */
        fun showDownloadProgress(written: Long, total: Long, fileName: String) {
            progressBlock.visibility = View.VISIBLE
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = false
            progress.max = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress.setProgressCompat(
                written.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                true,
            )
            stateView.text = ImportProgress.download(written, total, fileName)
            stateView.setTextColor(ui.onSurfaceVariant)
            percentView.visibility = View.VISIBLE
            percentView.text = "${ImportProgress.percentOf(written, total)}%"
            percentView.setTextColor(ui.onSurfaceVariant)
            importButton.isEnabled = false
            cancelButton.visibility = View.VISIBLE
        }

        /** An import/verify step whose total is unknown; indeterminate bar. */
        fun showBusy(label: String, cancellable: Boolean) {
            progressBlock.visibility = View.VISIBLE
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            percentView.visibility = View.GONE
            stateView.text = label
            stateView.setTextColor(ui.onSurfaceVariant)
            importButton.isEnabled = false
            cancelButton.visibility = if (cancellable) View.VISIBLE else View.GONE
        }

        fun showFailure(message: String) {
            // The state line carries the failure text, so the block stays visible
            // while only the bar itself is hidden.
            progressBlock.visibility = View.VISIBLE
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            percentView.visibility = View.GONE
            cancelButton.visibility = View.GONE
            stateView.text = message
            stateView.setTextColor(ui.error)
            importButton.text = "Retry"
            importButton.isEnabled = true
        }
    }

    private fun readAsset(context: Context, path: String): String? = try {
        BufferedInputStream(context.assets.open(path)).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        null
    }

    private fun errorDialog(context: Context, message: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Dictionary Catalog")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }
}
