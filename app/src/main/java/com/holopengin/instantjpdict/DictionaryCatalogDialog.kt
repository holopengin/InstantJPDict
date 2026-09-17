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
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
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
import kotlin.math.min

/**
 * #71: the in-app dictionary catalog — browse the bundled static list and
 * import a dictionary in one tap.
 *
 * Each row is one [CatalogEntry]: a Yomitan zip fetched from a pinned, dated
 * upstream URL and integrity-checked before import, or an asset already in the
 * APK (the pitch dictionary, which needs no network). A network row shows
 * download progress and can be cancelled; a bundled row installs immediately.
 *
 * The surface is the app's "Harbour" Material 3 language (main-screen redesign):
 * one tonal card per dictionary on `colorSurfaceContainerLow`, the Material 3
 * type scale instead of hand-set sizes, `MaterialButton` roles for the actions,
 * an M3 progress indicator, and every colour read from the theme — so day/night
 * and Material You both flow through without a hand-maintained palette. That
 * retires the old `CatalogPalette`, which existed only because the dialog was
 * not themed.
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

    /** Material 3 dialogs cap at 560 dp; the window is sized inside that. */
    private const val WINDOW_MAX_WIDTH_DP = 560

    fun show(context: Context) {
        val colors = colorsFor(context)
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
            setPadding(dp(context, 24), 0, dp(context, 24), 0)
        }

        root.addView(TextView(context).apply {
            text = "Popular Yomitan dictionaries, downloaded and imported for you. " +
                "Each download is checked against a pinned size and SHA-256 before a " +
                "single row is added. Downloading needs a connection; everything else " +
                "in the app stays offline."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(colors.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        })

        // Shown only after a download could not reach the network. The app holds
        // no permission to read connectivity state, so this is discovered from
        // the failure rather than asked ahead of time.
        val bannerText = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(colors.onErrorContainer)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val banner = MaterialCardView(context).apply {
            radius = dp(context, 16).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(colors.errorContainer)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_info)
                    imageTintList = ColorStateList.valueOf(colors.onErrorContainer)
                    layoutParams = LinearLayout.LayoutParams(dp(context, 20), dp(context, 20))
                        .apply { marginEnd = dp(context, 12) }
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
            setPadding(0, dp(context, 2), 0, dp(context, 2))
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

        entries.forEach { entry ->
            val row = CatalogRowView(
                context = context,
                entry = entry,
                colors = colors,
                onImport = { r -> startImport(entry, r) },
                onCancel = { jobs[entry.id]?.cancel() },
            )
            rows += row
            rowsContainer.addView(row.root)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Dictionary catalog")
            .setView(root)
            .setNeutralButton("Licences", null)
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener {
            // The neutral button opens the #70 licence surface, where the
            // CC BY-SA 4.0 and EDRDG texts these dictionaries are under are
            // shipped; set here so the click does not dismiss the dialog.
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                LicenseDialog.show(context)
            }
            // A definite window height is what gives the list its weight and lets
            // it scroll, while the intro, banner and buttons stay put. Width stays
            // inside Material 3's 560 dp dialog cap.
            val metrics = context.resources.displayMetrics
            dialog.window?.setLayout(
                min((metrics.widthPixels * 0.94f).toInt(), dp(context, WINDOW_MAX_WIDTH_DP)),
                (metrics.heightPixels * 0.82f).toInt(),
            )
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

    /** The theme roles the catalog draws with, resolved once per dialog. */
    private data class CatalogColors(
        val surfaceLow: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val primary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val error: Int,
        val errorContainer: Int,
        val onErrorContainer: Int,
    )

    /** One catalog row: a tonal card and the states it can show. */
    private class CatalogRowView(
        private val context: Context,
        val entry: CatalogEntry,
        private val colors: CatalogColors,
        private val onImport: (CatalogRowView) -> Unit,
        private val onCancel: () -> Unit,
    ) {
        /** A filled pill, for the "Installed" badge. */
        private fun pill(fill: Int): MaterialShapeDrawable = MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(context, 100).toFloat())
                .build()
        ).apply { fillColor = ColorStateList.valueOf(fill) }

        private val installedChip = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setTextColor(colors.onPrimaryContainer)
            background = pill(colors.primaryContainer)
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4))
            visibility = View.GONE
        }

        private val stateView = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(colors.onSurfaceVariant)
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
            setTextColor(colors.onSurfaceVariant)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        private val progress = LinearProgressIndicator(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            trackCornerRadius = dp(context, 4)
            visibility = View.GONE
        }

        private val progressBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(context, 10), 0, 0)
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
            text = "Import"
            isAllCaps = false
            setOnClickListener { onImport(this@CatalogRowView) }
        }

        private val cancelButton = MaterialButton(
            context, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(colors.primary)
            visibility = View.GONE
            setOnClickListener { onCancel() }
        }

        val root = MaterialCardView(context).apply {
            radius = dp(context, 24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(colors.surfaceLow)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        }

        init {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        setImageResource(
                            if (entry.kind == CatalogSource.BUNDLED_ASSET) R.drawable.ic_book
                            else R.drawable.ic_download
                        )
                        layoutParams = LinearLayout.LayoutParams(dp(context, 24), dp(context, 24))
                            .apply { marginEnd = dp(context, 12) }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(TextView(context).apply {
                        text = entry.name
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                        setTextColor(colors.onSurface)
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(installedChip)
                })
                addView(TextView(context).apply {
                    text = entry.description
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(colors.onSurfaceVariant)
                    setPadding(0, dp(context, 6), 0, 0)
                })
                addView(TextView(context).apply {
                    text = "${entry.sizeLabel()} · ${entry.license} · ${entry.source}"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(colors.onSurfaceVariant)
                    setPadding(0, dp(context, 4), 0, 0)
                })
                addView(progressBlock)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(context, 10), 0, 0)
                    addView(importButton)
                    addView(cancelButton.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { leftMargin = dp(context, 8) }
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
                installedChip.text =
                    if (entry.kind == CatalogSource.BUNDLED_ASSET) "Bundled" else "Installed"
                installedChip.visibility = View.VISIBLE
                importButton.text = "Re-import"
            } else {
                installedChip.visibility = View.GONE
                importButton.text = "Import"
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
            stateView.setTextColor(colors.onSurfaceVariant)
            percentView.visibility = View.VISIBLE
            percentView.text = "${ImportProgress.percentOf(written, total)}%"
            percentView.setTextColor(colors.onSurfaceVariant)
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
            stateView.setTextColor(colors.onSurfaceVariant)
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
            stateView.setTextColor(colors.error)
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
            .setTitle("Dictionary catalog")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun colorsFor(context: Context): CatalogColors = CatalogColors(
        surfaceLow = attr(context, com.google.android.material.R.attr.colorSurfaceContainerLow),
        onSurface = attr(context, com.google.android.material.R.attr.colorOnSurface),
        onSurfaceVariant = attr(context, com.google.android.material.R.attr.colorOnSurfaceVariant),
        primary = attr(context, com.google.android.material.R.attr.colorPrimary),
        primaryContainer = attr(context, com.google.android.material.R.attr.colorPrimaryContainer),
        onPrimaryContainer = attr(context, com.google.android.material.R.attr.colorOnPrimaryContainer),
        error = attr(context, com.google.android.material.R.attr.colorError),
        errorContainer = attr(context, com.google.android.material.R.attr.colorErrorContainer),
        onErrorContainer = attr(context, com.google.android.material.R.attr.colorOnErrorContainer),
    )

    /** Resolve one colour from the current theme (day/night and Material You aware). */
    private fun attr(context: Context, attribute: Int): Int =
        MaterialColors.getColor(context, attribute, android.graphics.Color.GRAY)

    /** Density-independent pixels; one definition for the dialog and its rows. */
    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
