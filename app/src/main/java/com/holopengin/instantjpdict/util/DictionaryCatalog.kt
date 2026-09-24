package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.CatalogEntryRecord
import uniffi.nav_graph_core.catalogBaseTitle
import uniffi.nav_graph_core.catalogInstalledIds
import uniffi.nav_graph_core.catalogParse
import java.util.Locale

/**
 * #71: one row of the bundled dictionary catalog.
 *
 * The catalog is a STATIC asset (`catalog/dictionaries.json`) — no remote
 * catalog service — so this type and [DictionaryCatalog] hold only the reading
 * side and no Android types, which keeps them JVM-unit-testable.  The rows,
 * parser, and installed-state rules are owned by `jpdict_core`; this facade
 * only maps the exported records onto the app's existing data class.
 *
 * A "dictionary" here is one downloadable artifact: a Yomitan-format zip
 * fetched from a pinned upstream URL ([url]), integrity-checked against [bytes]
 * and [sha256], then handed to `DictionaryImporter`. Dictionaries already
 * bundled in the APK (the Kanjium pitch accents, #43) are not listed — they
 * are installed at first launch and need no catalog row.
 */
data class CatalogEntry(
    /** Stable catalog id, e.g. `jmdict-english`. */
    val id: String,
    /** Display name shown as the row's title. */
    val name: String,
    /** One or two sentences for the row. */
    val description: String,
    /** Licence label, e.g. `CC BY-SA 4.0 (EDRDG)`. The full texts live in the
     *  #70 Licenses viewer; the catalog points at it rather than duplicating it. */
    val license: String,
    /** Where the artifact comes from, one line, e.g. `yomidevs/jmdict-yomitan @ 2026-09-15`. */
    val source: String,
    /**
     * The dictionary's stable title family. The imported `DictionaryMeta.name`
     * is matched against this with [DictionaryCatalog.baseTitle], because an
     * upstream zip appends its revision in brackets — `JMdict [2026-09-15]`.
     */
    val title: String,
    /** Exact byte size of the artifact, pinned. */
    val bytes: Long,
    /** Lowercase hex SHA-256 of the artifact, pinned. */
    val sha256: String,
    /**
     * #71 follow-up: an entry we steer users to (Jitendex, KANJIDIC). An
     * uninstalled recommended row shows a red "Recommended" badge; once it is
     * installed that badge gives way to "Installed".
     */
    val recommended: Boolean = false,
    /** Pinned HTTPS source URL for the zip. */
    val url: String,
) {
    /** Human size for the row, e.g. `15.6 MB`. */
    fun sizeLabel(): String = formatBytes(bytes)

    /**
     * The artifact's own file name, taken from the end of [url] with any query
     * string stripped — e.g. `JMdict_english.zip`. The download writes the file
     * under this name, so the progress text names the same thing the user would
     * see in a file manager rather than a second, invented label.
     *
     * Falls back to a name derived from [id] when the URL carries no final
     * segment, which keeps the progress line from rendering an empty name.
     */
    fun fileName(): String {
        val fromUrl = url
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() && it != "/" }
        return fromUrl ?: "$id.zip"
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KB", "MB", "GB")
            var value = bytes.toDouble() / 1024
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            val text = String.format(Locale.ROOT, "%.1f", value)
            return "$text ${units[unit]}"
        }
    }
}

/**
 * #71 follow-up: one installed dictionary as the catalog sees it.
 *
 * [name] is the title the importer wrote (`JMdict [2026-09-15]`); [catalogId]
 * is the catalog entry it came from, when it came through the catalog. An
 * install the catalog did not make — the file picker, or the bundled pitch
 * dictionary — has no id, so [name] is still carried for the title-family
 * fallback in [DictionaryCatalog.installedIds].
 */
data class InstalledDictionary(val name: String, val catalogId: String? = null)

/**
 * The bundled catalog facade.
 *
 * Parsing, validation, title normalization, and installed-state resolution are
 * delegated to `jpdict_core` through the generated UniFFI surface. The
 * `ASSET` path remains available as the parity-test fixture/licence reference;
 * the rows exposed to the app are produced by the core's canonical parser.
 */
object DictionaryCatalog {

    /** The catalog asset, relative to the APK's asset root. */
    const val ASSET = "catalog/dictionaries.json"

    /** The schema version this reader understands. */
    const val SCHEMA = 1

    /**
     * Parse a catalog document with the core's strict parser and map its
     * records onto the app-facing [CatalogEntry] shape.  The test fixture uses
     * this entry point to prove that the shipped asset and the core agree.
     */
    fun parse(json: String): List<CatalogEntry> =
        catalogParse(json).map { it.toCatalogEntry() }

    /**
     * The dictionary's stable title, with any bracketed revision stripped:
     * `JMdict [2026-09-15]` -> `JMdict`. The upstream zip titles carry the
     * release date, so matching the family title is what lets an installed
     * dictionary be recognised across releases.
     */
    fun baseTitle(name: String): String = catalogBaseTitle(name)

    /**
     * The catalog ids that are installed.
     *
     * A dictionary installed through the catalog is matched by its
     * [InstalledDictionary.catalogId]. A title-only install — the file picker,
     * or a dictionary installed before this column existed — cannot say which
     * variant it is, so the core resolves it to the first entry of its title
     * family (the default). Two catalog entries may therefore share a title
     * family (the two JMdict variants do), and the same matching rules run on
     * both desktop and Android.
     *
     * `entries` is retained for source compatibility with the pre-core facade;
     * the shared core owns the canonical list used for the lookup.
     */
    @Suppress("UNUSED_PARAMETER")
    fun installedIds(
        entries: List<CatalogEntry>,
        installed: Collection<InstalledDictionary>,
    ): Set<String> {
        val rows = installed.toList()
        return catalogInstalledIds(
            rows.map { it.name },
            rows.map { it.catalogId },
        ).toSet()
    }
}

private fun CatalogEntryRecord.toCatalogEntry(): CatalogEntry = CatalogEntry(
    id = id,
    name = name,
    description = description,
    license = license,
    source = source,
    title = title,
    bytes = bytes,
    sha256 = sha256,
    recommended = recommended,
    url = url,
)
