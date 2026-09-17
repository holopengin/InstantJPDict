package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.DictionaryCatalog
import com.holopengin.instantjpdict.util.InstalledDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #71: the bundled dictionary catalog, asserted against the shipped asset
 * rather than a fixture (the `LicenseIndexTest` precedent).
 *
 * What this can prove: the catalog is self-consistent and the invariants the
 * download path relies on hold — every row is pinned to a dated URL with a size
 * and SHA-256. What it cannot prove: that the upstream URL still serves that
 * byte-identical file, or that an install succeeds on a device.
 */
class DictionaryCatalogTest {

    private val json: String by lazy { TestAssets.assetsFile(DictionaryCatalog.ASSET).readText() }
    private val entries by lazy { DictionaryCatalog.parse(json) }

    @Test
    fun the_catalog_ships_the_dictionaries_the_feature_promises() {
        assertEquals(
            listOf(
                "jmdict-english",
                "jmdict-english-with-examples",
                "kanjidic-english",
            ),
            entries.map { it.id },
        )
        entries.forEach { e ->
            assertTrue("'${e.id}' has an empty name", e.name.isNotBlank())
            assertTrue("'${e.id}' has an empty description", e.description.isNotBlank())
            assertTrue("'${e.id}' has no licence label", e.license.isNotBlank())
            assertTrue("'${e.id}' has no source line", e.source.isNotBlank())
            assertTrue("'${e.id}' has no title family", e.title.isNotBlank())
            assertTrue("'${e.id}' pins no size", e.bytes > 0)
            assertTrue("'${e.id}' pins no sha256", e.sha256.matches(Regex("^[0-9a-f]{64}$")))
        }
        // The two JMdict builds share the upstream title `JMdict` on purpose:
        // they are mutually exclusive variants, and `catalogId` is what tells
        // them apart. Any other shared title family would be an accident.
        val shared = entries.groupBy { it.title }.filterValues { it.size > 1 }
        assertEquals("only the JMdict variants may share a title", setOf("JMdict"), shared.keys)
        assertEquals(
            listOf("jmdict-english", "jmdict-english-with-examples"),
            shared.getValue("JMdict").map { it.id },
        )
        // The bundled Kanjium pitch dictionary is installed at first launch, so
        // it must not be a catalog row (which would imply the user installs it).
        assertTrue(
            "the bundled pitch dictionary must not be listed",
            entries.none { it.id.contains("kanjium") || it.title.contains("Kanjium", ignoreCase = true) },
        )
    }

    @Test
    fun the_two_jmdict_variants_are_mutually_exclusive() {
        // A catalog install names its exact entry, so the other variant stays
        // uninstalled even though both share the title `JMdict`.
        assertEquals(
            setOf("jmdict-english-with-examples"),
            DictionaryCatalog.installedIds(
                entries,
                listOf(InstalledDictionary("JMdict [2026-09-15]", "jmdict-english-with-examples")),
            ),
        )
        assertEquals(
            setOf("jmdict-english"),
            DictionaryCatalog.installedIds(
                entries,
                listOf(InstalledDictionary("JMdict [2026-09-15]", "jmdict-english")),
            ),
        )
        // A title-only install (file picker, or pre-`catalogId`) cannot say
        // which variant it is, so it resolves to the default — the first entry
        // of the family — and never lights up both rows.
        assertEquals(
            setOf("jmdict-english"),
            DictionaryCatalog.installedIds(entries, listOf(InstalledDictionary("JMdict [2026-09-15]"))),
        )
    }

    @Test
    fun a_row_names_the_release_its_url_points_at() {
        entries.forEach { e ->
            val tag = Regex("/releases/download/([^/]+)/").find(e.url)!!.groupValues[1]
            assertTrue(
                "'${e.id}' URL is release '$tag' but its source line says '${e.source}'",
                e.source.contains(tag),
            )
        }
    }

    @Test
    fun every_row_is_pinned_to_a_dated_release_never_latest() {
        assertTrue("expected at least the two Yomitan rows, found ${entries.size}", entries.size >= 2)
        entries.forEach { e ->
            assertTrue("'${e.id}' is not https: ${e.url}", e.url.startsWith("https://"))
            // The whole pin is worthless if the URL moves: /releases/latest/ is
            // the trap the issue names, and DictionaryCatalog.parse refuses it.
            assertFalse("'${e.id}' points at a moving target: ${e.url}", e.url.contains("/releases/latest/"))
            assertTrue(
                "'${e.id}' is not pinned to a release tag: ${e.url}",
                Regex("/releases/download/[^/]+/").containsMatchIn(e.url),
            )
        }
    }

    @Test
    fun a_dictionary_is_installed_when_its_title_family_is_present_but_not_a_longer_name() {
        assertEquals(
            setOf("kanjidic-english"),
            DictionaryCatalog.installedIds(entries, listOf(InstalledDictionary("KANJIDIC [2020-01-01]"))),
        )
        assertEquals(
            setOf("jmdict-english", "kanjidic-english"),
            DictionaryCatalog.installedIds(
                entries,
                listOf(InstalledDictionary("JMdict [2026-09-15]"), InstalledDictionary("KANJIDIC [2020-01-01]")),
            ),
        )
        // `JMdict Forms` is a longer name, not the `JMdict` family, so it must
        // not be mistaken for the dictionary itself.
        assertEquals(
            emptySet<String>(),
            DictionaryCatalog.installedIds(entries, listOf(InstalledDictionary("JMdict Forms"))),
        )
    }

    @Test
    fun base_title_strips_the_bracketed_revision() {
        assertEquals("JMdict", DictionaryCatalog.baseTitle("JMdict [2026-09-15]"))
        assertEquals("KANJIDIC", DictionaryCatalog.baseTitle("KANJIDIC [2026-258]"))
        assertEquals("JMdict Forms", DictionaryCatalog.baseTitle("  JMdict Forms  "))
    }

    @Test
    fun parse_refuses_a_row_that_cannot_be_integrity_checked() {
        val validSha = "0".repeat(64)
        val shaOfHelloWorld = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        fun catalog(body: String) = """{"schema": 1, "entries": [$body]}"""
        fun row(sha: String, url: String) = """
            {
              "id": "x", "name": "X", "description": "d", "license": "l", "source": "s",
              "title": "X", "bytes": 10, "sha256": "$sha", "url": "$url"
            }
        """.trimIndent()
        // Baseline: this row parses.
        assertNotNull(DictionaryCatalog.parse(catalog(row(validSha, "https://example.com/a.zip"))))
        // A moving URL is refused.
        assertTrue(
            runCatching {
                DictionaryCatalog.parse(catalog(row(validSha, "https://example.com/releases/latest/download/a.zip")))
            }.isFailure,
        )
        // A non-HTTPS URL is refused.
        assertTrue(
            runCatching { DictionaryCatalog.parse(catalog(row(validSha, "http://example.com/a.zip"))) }.isFailure,
        )
        // A bad hash is refused.
        assertTrue(
            runCatching { DictionaryCatalog.parse(catalog(row("not-a-hash", "https://example.com/a.zip"))) }.isFailure,
        )
        // Duplicate ids are refused, as is an empty catalog.
        val one = """{"id":"x","name":"X","description":"d","license":"l","source":"s",
            "title":"X","bytes":1,"sha256":"$shaOfHelloWorld","url":"https://example.com/a.zip"}"""
        assertTrue(runCatching { DictionaryCatalog.parse(catalog("$one,$one")) }.isFailure)
        assertTrue(runCatching { DictionaryCatalog.parse("""{"schema":1,"entries":[]}""") }.isFailure)
    }

    @Test
    fun size_labels_read_as_sizes() {
        assertEquals("512 B", com.holopengin.instantjpdict.util.CatalogEntry.formatBytes(512))
        assertEquals("1.0 KB", com.holopengin.instantjpdict.util.CatalogEntry.formatBytes(1024))
        assertEquals("14.9 MB", com.holopengin.instantjpdict.util.CatalogEntry.formatBytes(15594803))
    }
}
