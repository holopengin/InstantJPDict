package com.holopengin.instantjpdict.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * #71: one row of the bundled dictionary catalog.
 *
 * The catalog is a STATIC asset (`catalog/dictionaries.json`) — no remote
 * catalog service — so this type and [DictionaryCatalog] hold only the reading
 * side and no Android types, which keeps them JVM-unit-testable.
 *
 * A "dictionary" here is one importable artifact. Two shapes exist:
 *
 *  - [CatalogSource.YOMITAN_ZIP] — a Yomitan-format zip fetched from a pinned
 *    upstream URL ([url]), integrity-checked against [bytes] and [sha256], then
 *    handed to `DictionaryImporter`;
 *  - [CatalogSource.BUNDLED_ASSET] — an asset already in the APK ([asset]),
 *    installed with no network. This is the pitch-accent dictionary (#43).
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
    val kind: CatalogSource,
    /** HTTPS source URL for [CatalogSource.YOMITAN_ZIP], else null. */
    val url: String? = null,
    /** APK asset path for [CatalogSource.BUNDLED_ASSET], else null. */
    val asset: String? = null,
) {
    /** True when importing this row downloads from the network. */
    val downloadable: Boolean get() = kind == CatalogSource.YOMITAN_ZIP

    /** Human size for the row, e.g. `15.6 MB`. */
    fun sizeLabel(): String = formatBytes(bytes)

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

/** Which importer a catalog row feeds. */
enum class CatalogSource { YOMITAN_ZIP, BUNDLED_ASSET }

/**
 * The bundled catalog: parsing, validation and installed-state resolution.
 *
 * [parse] is strict on purpose. These URLs are pinned to a dated release and a
 * SHA-256; an entry that is missing a pin, or that points at `/releases/latest/`
 * (a moving target that would silently defeat the hash), is a bug in the asset,
 * not something to paper over at runtime.
 */
object DictionaryCatalog {

    /** The catalog asset, relative to the APK's asset root. */
    const val ASSET = "catalog/dictionaries.json"

    /** The schema version this reader understands. */
    const val SCHEMA = 1

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun parse(json: String): List<CatalogEntry> {
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "$ASSET: top level must be an object" }
        val obj = root.asJsonObject
        val schema = obj.int("schema") ?: error("$ASSET: missing `schema`")
        require(schema == SCHEMA) { "$ASSET: schema $schema is not the supported $SCHEMA" }
        val array = obj.get("entries")
        require(array != null && array.isJsonArray) { "$ASSET: missing `entries` array" }
        val entries = array.asJsonArray.mapIndexed { i, element ->
            require(element.isJsonObject) { "$ASSET: entry $i is not an object" }
            entry(element.asJsonObject, i)
        }
        require(entries.isNotEmpty()) { "$ASSET: lists no dictionaries" }
        val dupes = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "$ASSET: duplicate ids: ${dupes.joinToString()}" }
        return entries
    }

    private fun entry(o: JsonObject, index: Int): CatalogEntry {
        fun where(field: String) = "$ASSET: entry $index (`${o.string("id") ?: "?"}`) $field"
        fun required(field: String): String =
            o.string(field) ?: error(where("is missing `$field`"))

        val id = required("id")
        val name = required("name")
        val description = required("description")
        val license = required("license")
        val source = required("source")
        val title = required("title")
        val bytes = o.long("bytes") ?: error(where("is missing `bytes`"))
        require(bytes > 0) { where("must have a positive `bytes`") }
        val sha256 = required("sha256")
        require(SHA256.matches(sha256)) { where("`sha256` is not 64 lowercase hex chars: $sha256") }
        val kind = when (val raw = required("kind")) {
            "yomitanZip" -> CatalogSource.YOMITAN_ZIP
            "bundledAsset" -> CatalogSource.BUNDLED_ASSET
            else -> error(where("has unknown `kind` '$raw'"))
        }
        val url = o.string("url")
        val asset = o.string("asset")
        when (kind) {
            CatalogSource.YOMITAN_ZIP -> {
                require(!url.isNullOrBlank()) { where("has kind yomitanZip but no `url`") }
                require(url.startsWith("https://")) { where("`url` is not https: $url") }
                require(!url.contains("/releases/latest/")) {
                    where("`url` points at /releases/latest/, which moves; pin a dated release: $url")
                }
                require(asset.isNullOrBlank()) { where("has kind yomitanZip and an `asset`") }
            }
            CatalogSource.BUNDLED_ASSET -> {
                require(!asset.isNullOrBlank()) { where("has kind bundledAsset but no `asset`") }
                require(url.isNullOrBlank()) { where("has kind bundledAsset and a `url`; a bundled row needs no network") }
            }
        }
        return CatalogEntry(
            id = id,
            name = name,
            description = description,
            license = license,
            source = source,
            title = title,
            bytes = bytes,
            sha256 = sha256,
            kind = kind,
            url = url,
            asset = asset,
        )
    }

    /**
     * The dictionary's stable title, with any bracketed revision stripped:
     * `JMdict [2026-09-15]` -> `JMdict`. The upstream zip titles carry the
     * release date, so matching the family title is what lets an installed
     * dictionary be recognised across releases.
     */
    fun baseTitle(name: String): String {
        val trimmed = name.trim()
        val bracket = trimmed.indexOf(" [")
        return if (bracket > 0) trimmed.substring(0, bracket).trim() else trimmed
    }

    /** True when a dictionary of [entry]'s title family is already installed. */
    fun isInstalled(entry: CatalogEntry, installedNames: Collection<String>): Boolean =
        installedNames.any { baseTitle(it) == entry.title }

    /** The ids of [entries] whose title family is present in [installedNames]. */
    fun installedIds(entries: List<CatalogEntry>, installedNames: Collection<String>): Set<String> =
        entries.filter { isInstalled(it, installedNames) }.map { it.id }.toSet()

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}
