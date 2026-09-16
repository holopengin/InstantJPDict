package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 */
object DictionaryCatalogDialog {

    private const val TAG = "DictionaryCatalog"

    private val OK = Color.rgb(0x1B, 0x7F, 0x3B)
    private val WARN = Color.rgb(0xB4, 0x54, 0x09)
    private val ERR = Color.rgb(0xB0, 0x1C, 0x1C)
    private val IDLE = Color.rgb(0x44, 0x44, 0x44)

    fun show(context: Context) {
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
        var offline = !isOnline(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), 0)
        }

        root.addView(TextView(context).apply {
            text = "Popular Yomitan dictionaries, downloaded and imported for you. " +
                "Downloads are checked against a pinned size and SHA-256 before anything " +
                "is added. Everything else in the app stays offline."
            textSize = 12f
            setTextColor(IDLE)
            setPadding(0, 0, 0, dp(context, 8))
        })

        val offlineBanner = TextView(context).apply {
            text = "Download unavailable — this device is offline. " +
                "Bundled dictionaries can still be installed; connect and reopen the catalog to download."
            textSize = 12f
            setTextColor(WARN)
            setPadding(0, 0, 0, dp(context, 8))
            visibility = if (offline) View.VISIBLE else View.GONE
        }
        root.addView(offlineBanner)

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
            rows.forEach { it.bindInstalled(it.entry.id in installedIds, offline) }
        }

        suspend fun refreshInstalledState() {
            val names = withContext(Dispatchers.IO) { dao.getAllDictionaries().map { it.name } }
            installedIds = DictionaryCatalog.installedIds(entries, names)
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
                        // short and repairs itself, so it is left to finish.
                        val result = withContext(NonCancellable) {
                            importer.importBundledAsset(asset) { n ->
                                ui { row.showBusy("Installing: $n entries…", cancellable = false) }
                            }
                        }
                        result.getOrThrow()
                    } else {
                        val file: File = downloader.download(entry) { written ->
                            ui { row.showDownloadProgress(written) }
                        }
                        try {
                            ui { row.showBusy("Verified — importing…", cancellable = false) }
                            // Once the file is verified the download is done and
                            // Cancel is gone: an import that has started must not
                            // be interrupted, because a half-written dictionary
                            // has no completion marker and would look installed.
                            val result = withContext(NonCancellable) {
                                importer.importZip(Uri.fromFile(file), entry.name) { n ->
                                    ui { row.showBusy("Importing: $n entries…", cancellable = false) }
                                }
                            }
                            result.getOrThrow()
                        } finally {
                            file.delete()
                        }
                    }
                    offline = !isOnline(context)
                    ui { offlineBanner.visibility = if (offline) View.VISIBLE else View.GONE }
                    refreshInstalledState()
                } catch (e: CancellationException) {
                    ui { refreshRows() }
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Catalog import failed for ${entry.id}", e)
                    offline = !isOnline(context)
                    val message = if (offline) {
                        "Download unavailable — offline"
                    } else {
                        "Import failed: ${e.message ?: "unknown error"}"
                    }
                    ui {
                        offlineBanner.visibility = if (offline) View.VISIBLE else View.GONE
                        row.showFailure(message, offline)
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

    /** The columns a card can be in, bound by [CatalogRowView]. */
    private class CatalogRowView(
        context: Context,
        val entry: CatalogEntry,
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
                setTextColor(IDLE)
                setPadding(0, dp(context, 2), 0, dp(context, 4))
            })
            root.addView(TextView(context).apply {
                text = "${entry.sizeLabel()} · ${entry.license} · ${entry.source}"
                textSize = 11f
                setTextColor(IDLE)
            })
            root.addView(stateView)
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
        fun bindInstalled(installed: Boolean, offline: Boolean) {
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            cancelButton.visibility = View.GONE
            val unavailable = offline && entry.downloadable
            importButton.isEnabled = !unavailable
            when {
                unavailable -> {
                    stateView.text = "Download unavailable — offline"
                    stateView.setTextColor(WARN)
                    importButton.text = "Import"
                }
                installed -> {
                    stateView.text = if (entry.kind == CatalogSource.BUNDLED_ASSET) {
                        "Installed (bundled)"
                    } else {
                        "Installed"
                    }
                    stateView.setTextColor(OK)
                    importButton.text = "Re-import"
                }
                else -> {
                    stateView.text = ""
                    importButton.text = "Import"
                }
            }
        }

        /** Determinate download progress; Cancel is offered. */
        fun showDownloadProgress(written: Long) {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = false
            progress.max = entry.bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress.progress = written.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            stateView.text = "Downloading ${CatalogEntry.formatBytes(written)} / ${entry.sizeLabel()}…"
            stateView.setTextColor(IDLE)
            importButton.isEnabled = false
            cancelButton.visibility = View.VISIBLE
        }

        /** An import/verify step whose total is unknown; indeterminate bar. */
        fun showBusy(label: String, cancellable: Boolean) {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            stateView.text = label
            stateView.setTextColor(IDLE)
            importButton.isEnabled = false
            cancelButton.visibility = if (cancellable) View.VISIBLE else View.GONE
        }

        fun showFailure(message: String, offline: Boolean) {
            progress.visibility = View.GONE
            cancelButton.visibility = View.GONE
            stateView.text = message
            stateView.setTextColor(ERR)
            importButton.text = "Retry"
            importButton.isEnabled = !(offline && entry.downloadable)
        }

        private companion object {
            fun dp(context: Context, value: Int): Int =
                (value * context.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * True when the device has a validated internet connection. VALIDATED, not
     * just CONNECTED, so a captive portal is reported as offline instead of
     * letting a download through that would then fail confusingly.
     */
    private fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
