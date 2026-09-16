package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryDownloader
import com.holopengin.instantjpdict.data.DictionaryImporter
import com.holopengin.instantjpdict.util.CatalogEntry
import com.holopengin.instantjpdict.util.CatalogSource
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
 * upstream URL and integrity-checked before import, or an asset already in the
 * APK (the pitch dictionary, which needs no network). A network row shows
 * download progress and can be cancelled; a bundled row installs immediately.
 *
 * Three things this deliberately reuses rather than reimplements:
 *  - `DictionaryImporter.importZip` for the actual insert, so the catalog and
 *    the file picker write rows through one path;
 *  - `DictionaryMeta` for installed state, so the row says "Installed" from the
 *    same truth the manager uses; and
 *  - the #70 [LicenseDialog] for licence text, reached from the Licences button
 *    instead of the catalog carrying its own copy.
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
        // #71 follow-up: colours are resolved per day/night. The detail text used
        // to be a hardcoded #444444, which is dark-on-dark against the DayNight
        // theme's night dialog surface and was reported as unreadable.
        val palette = CatalogPalette.of(context)
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
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), 0)
        }

        root.addView(TextView(context).apply {
            text = "Popular Yomitan dictionaries, downloaded and imported for you. " +
                "Downloads are checked against a pinned size and SHA-256 before anything " +
                "is added. Downloading needs a connection; everything else in the app " +
                "stays offline."
            textSize = 12f
            setTextColor(palette.neutral)
            setPadding(0, 0, 0, dp(context, 8))
        })

        // Shown only after a download could not reach the network. The app holds
        // no permission to read connectivity state, so this is discovered from
        // the failure rather than asked ahead of time.
        val statusBanner = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.warn)
            setPadding(0, 0, 0, dp(context, 8))
            visibility = View.GONE
        }
        root.addView(statusBanner)

        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rowsScroll = ScrollView(context).apply {
            addView(rowsContainer)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
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
                    if (entry.kind == CatalogSource.BUNDLED_ASSET) {
                        val asset = entry.asset!!
                        // The bundled install reads the APK and inserts rows; it is
                        // short and repairs itself, so it is left to finish. Its
                        // callback fires only after a batch of rows is committed,
                        // so the count shown cannot run ahead of what is in the DB.
                        val result = withContext(NonCancellable) {
                            importer.importBundledAsset(asset, catalogId = entry.id) { n ->
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
                        result.getOrThrow()
                    } else {
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
                    }
                    ui { statusBanner.visibility = View.GONE }
                    refreshInstalledState()
                } catch (e: CancellationException) {
                    ui { refreshRows() }
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Catalog import failed for ${entry.id}", e)
                    ui {
                        if (e.isConnectionFailure()) {
                            statusBanner.text =
                                "Download unavailable — no connection. Connect and tap Retry."
                            statusBanner.visibility = View.VISIBLE
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

        entries.forEach { entry ->
            val row = CatalogRowView(
                context = context,
                entry = entry,
                palette = palette,
                onImport = { r -> startImport(entry, r) },
                onCancel = { jobs[entry.id]?.cancel() },
            )
            rows += row
            rowsContainer.addView(row.root)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Dictionary Catalog")
            .setView(root)
            .setNeutralButton("Licences", null)
            .setNegativeButton("Close", null)
            .create()

        // The neutral button opens the #70 licence surface, where the CC BY-SA 4.0
        // and EDRDG texts these dictionaries are under are shipped; set after
        // show() so the dialog is not dismissed by the click.
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                LicenseDialog.show(context)
            }
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

    /** One catalog row: the card's views and the states they can show. */
    private class CatalogRowView(
        context: Context,
        val entry: CatalogEntry,
        private val palette: CatalogPalette,
        private val onImport: (CatalogRowView) -> Unit,
        private val onCancel: () -> Unit,
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            setBackgroundColor(Color.argb(10, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(context, 10) }
        }

        private val stateView = TextView(context).apply {
            textSize = 12f
            setPadding(0, dp(context, 2), 0, dp(context, 2))
        }

        /**
         * #71 follow-up: the line above the bar, split in two.
         *
         * [stateView] carries the phase in words ("Downloading JMdict_english.zip
         * — 4.0 MB / 15.6 MB"); this carries the percentage alone, right-aligned
         * so it is a fixed column the eye can find. A determinate bar alone
         * shows "not done" but not "how far" — this is the number that says it.
         * The percentage is throttled to whole percent so a transfer that
         * repaints on every 64 KB chunk does not also rebuild the string on
         * every chunk.
         */
        private val percentView = TextView(context).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(context, 8), dp(context, 2), 0, dp(context, 2))
            visibility = View.GONE
        }

        private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            visibility = View.GONE
            max = entry.bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private val importButton = Button(context).apply {
            text = "Import"
            setOnClickListener { onImport(this@CatalogRowView) }
        }

        private val cancelButton = Button(context).apply {
            text = "Cancel"
            visibility = View.GONE
            setOnClickListener { onCancel() }
        }

        init {
            root.addView(TextView(context).apply {
                text = entry.name
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            })
            root.addView(TextView(context).apply {
                text = entry.description
                textSize = 12f
                setTextColor(palette.neutral)
                setPadding(0, dp(context, 2), 0, dp(context, 4))
            })
            root.addView(TextView(context).apply {
                text = "${entry.sizeLabel()} · ${entry.license} · ${entry.source}"
                textSize = 11f
                setTextColor(palette.neutral)
            })
            root.addView(LinearLayout(context).apply {
                // #71 follow-up: the phase text and its percentage share one
                // line, above the bar, so "what is happening" and "how far"
                // read together and the bar below is unambiguously the thing
                // the number belongs to.
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(stateView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(percentView)
            })
            root.addView(progress)
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(context, 6), 0, 0)
                addView(importButton)
                addView(cancelButton.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = dp(context, 8) }
                })
            })
        }

        /** Back to the resting state: installed, or ready to import. */
        fun bindState(installed: Boolean) {
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            percentView.visibility = View.GONE
            cancelButton.visibility = View.GONE
            importButton.isEnabled = true
            when {
                installed -> {
                    stateView.text = if (entry.kind == CatalogSource.BUNDLED_ASSET) {
                        "Installed (bundled)"
                    } else {
                        "Installed"
                    }
                    stateView.setTextColor(palette.ok)
                    importButton.text = "Re-import"
                }
                else -> {
                    stateView.text = ""
                    importButton.text = "Import"
                }
            }
        }

        /**
         * Determinate download progress: the phase in words on the left, the
         * percentage on the right, the bar below.
         *
         * The percentage is recomputed from the whole-percent value, so the text
         * changes when the number does rather than on every 64 KB chunk the
         * download reports.
         */
        fun showDownloadProgress(written: Long, total: Long, fileName: String) {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = false
            progress.max = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress.progress = written.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            stateView.text = ImportProgress.download(written, total, fileName)
            stateView.setTextColor(palette.neutral)
            percentView.visibility = View.VISIBLE
            percentView.text = "${ImportProgress.percentOf(written, total)}%"
            percentView.setTextColor(palette.neutral)
            importButton.isEnabled = false
            cancelButton.visibility = View.VISIBLE
        }

        /** An import/verify step whose total is unknown; indeterminate bar. */
        fun showBusy(label: String, cancellable: Boolean) {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            percentView.visibility = View.GONE
            stateView.text = label
            stateView.setTextColor(palette.neutral)
            importButton.isEnabled = false
            cancelButton.visibility = if (cancellable) View.VISIBLE else View.GONE
        }

        fun showFailure(message: String) {
            progress.visibility = View.GONE
            percentView.visibility = View.GONE
            cancelButton.visibility = View.GONE
            stateView.text = message
            stateView.setTextColor(palette.err)
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
        AlertDialog.Builder(context)
            .setTitle("Dictionary Catalog")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Density-independent pixels; one definition for the dialog and its rows. */
    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
