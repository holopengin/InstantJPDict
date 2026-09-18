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
 * A "dictionary" here is one downloadable artifact: a Yomitan-format zip fetched
 * from a pinned upstream URL ([url]), integrity-checked against [bytes] and
 * [sha256], then handed to `DictionaryImporter`. Dictionaries already bundled in
 * the APK (the Kanjium pitch accents, #43) are not listed — they are installed
 * at first launch and need no catalog row.
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
        val url = required("url")
        require(url.startsWith("https://")) { where("`url` is not https: $url") }
        require(!url.contains("/releases/latest/")) {
            where("`url` points at /releases/latest/, which moves; pin a dated release: $url")
        }
        val recommended = o.get("recommended")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean ?: false
        return CatalogEntry(
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

    /**
     * The catalog ids that are installed.
     *
     * A dictionary installed through the catalog is matched by its
     * [InstalledDictionary.catalogId]. A title-only install — the file picker,
     * or a dictionary installed before this column existed — cannot say which
     * variant it is, so it resolves to the FIRST entry of its title family (the
     * default). Two catalog entries may therefore share a title family (the two
     * JMdict variants do), and only one of them ever reports installed, which is
     * what makes them mutually exclusive in the UI.
     */
    fun installedIds(
        entries: List<CatalogEntry>,
        installed: Collection<InstalledDictionary>,
    ): Set<String> {
        val ids = mutableSetOf<String>()
        installed.forEach { dictionary ->
            val match = dictionary.catalogId?.let { id -> entries.firstOrNull { it.id == id } }
                ?: entries.firstOrNull { it.title == baseTitle(dictionary.name) }
            match?.let { ids += it.id }
        }
        return ids
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}
