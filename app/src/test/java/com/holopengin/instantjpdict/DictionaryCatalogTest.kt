package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.CatalogSource
import com.holopengin.instantjpdict.util.DictionaryCatalog
import com.holopengin.instantjpdict.util.InstalledDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * #71: the bundled dictionary catalog, asserted against the shipped asset
 * rather than a fixture (the `LicenseIndexTest` precedent).
 *
 * What this can prove: the catalog is self-consistent and the invariants the
 * download path relies on hold — every network row is pinned to a dated URL
 * with a size and SHA-256, and every bundled row's pin matches the asset that
 * is actually in the APK. What it cannot prove: that the upstream URL still
 * serves that byte-identical file, or that an import succeeds on a device.
 */
class DictionaryCatalogTest {

    private val json: String by lazy { TestAssets.assetsFile(DictionaryCatalog.ASSET).readText() }
    private val entries by lazy { DictionaryCatalog.parse(json) }

    private fun entry(id: String) = entries.first { it.id == id }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun the_catalog_ships_the_dictionaries_the_feature_promises() {
        assertEquals(
            listOf(
                "jmdict-english",
                "jmdict-english-with-examples",
                "kanjidic-english",
                "kanjium-pitch",
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
    fun a_network_row_names_the_release_its_url_points_at() {
        entries.filter { it.kind == CatalogSource.YOMITAN_ZIP }.forEach { e ->
            val tag = Regex("/releases/download/([^/]+)/").find(e.url!!)!!.groupValues[1]
            assertTrue(
                "'${e.id}' URL is release '$tag' but its source line says '${e.source}'",
                e.source.contains(tag),
            )
        }
    }

    @Test
    fun every_download_row_is_pinned_to_a_dated_release_never_latest() {
        val network = entries.filter { it.kind == CatalogSource.YOMITAN_ZIP }
        assertTrue("expected the two Yomitan rows, found ${network.size}", network.size >= 2)
        network.forEach { e ->
            val url = e.url
            assertNotNull("'${e.id}' has no URL", url)
            assertTrue("'${e.id}' is not https: $url", url!!.startsWith("https://"))
            // The whole pin is worthless if the URL moves: /releases/latest/ is
            // the trap the issue names, and DictionaryCatalog.parse refuses it.
            assertFalse("'${e.id}' points at a moving target: $url", url.contains("/releases/latest/"))
            assertTrue(
                "'${e.id}' is not pinned to a release tag: $url",
                Regex("/releases/download/[^/]+/").containsMatchIn(url),
            )
            assertTrue("'${e.id}' has no asset path for a network row", e.asset == null)
        }
    }

    @Test
    fun the_bundled_pitch_row_points_at_the_asset_that_ships_and_needs_no_network() {
        val pitch = entry("kanjium-pitch")
        assertEquals(CatalogSource.BUNDLED_ASSET, pitch.kind)
        assertEquals("pitch/kanjium_pitch_accents.zip", pitch.asset)
        assertTrue("a bundled row must carry no URL", pitch.url == null)
        assertFalse("a bundled row must not be downloadable", pitch.downloadable)

        // The pin is checked against the committed asset, so a rebuilt zip and a
        // stale catalog cannot disagree silently.
        val asset = TestAssets.assetsFile(pitch.asset!!)
        assertEquals("'${pitch.asset}' size does not match the catalog pin", pitch.bytes, asset.length())
        assertEquals("'${pitch.asset}' sha256 does not match the catalog pin", pitch.sha256, sha256(asset))
    }

    @Test
    fun a_dictionary_is_installed_when_its_title_family_is_present_but_not_a_longer_name() {
        assertEquals(
            setOf("kanjidic-english"),
            DictionaryCatalog.installedIds(entries, listOf(InstalledDictionary("KANJIDIC [2020-01-01]"))),
        )
        assertEquals(
            setOf("jmdict-english", "kanjium-pitch"),
            DictionaryCatalog.installedIds(
                entries,
                listOf(InstalledDictionary("JMdict [2026-09-15]"), InstalledDictionary("Kanjium Pitch Accents")),
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
        assertEquals("Kanjium Pitch Accents", DictionaryCatalog.baseTitle("Kanjium Pitch Accents"))
        assertEquals("JMdict Forms", DictionaryCatalog.baseTitle("  JMdict Forms  "))
    }

    @Test
    fun parse_refuses_a_row_that_cannot_be_integrity_checked() {
        val validSha = "0".repeat(64)
        val shaOfHelloWorld = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        fun catalog(body: String) = """{"schema": 1, "entries": [$body]}"""
        fun networkRow(sha: String, url: String) = """
            {
              "id": "x", "name": "X", "description": "d", "license": "l", "source": "s",
              "title": "X", "bytes": 10, "sha256": "$sha",
              "kind": "yomitanZip", "url": "$url"
            }
        """.trimIndent()
        // Baseline: this row parses.
        assertNotNull(DictionaryCatalog.parse(catalog(networkRow(validSha, "https://example.com/a.zip"))))
        // A moving URL is refused.
        assertTrue(
            runCatching {
                DictionaryCatalog.parse(catalog(networkRow(validSha, "https://example.com/releases/latest/download/a.zip")))
            }.isFailure,
        )
        // A bad hash is refused.
        assertTrue(
            runCatching { DictionaryCatalog.parse(catalog(networkRow("not-a-hash", "https://example.com/a.zip"))) }.isFailure,
        )
        // A bundled row carrying a URL is refused (it would imply a network path).
        val bundled = """{"id":"p","name":"P","description":"d","license":"l","source":"s",
            "title":"P","bytes":1,"sha256":"$shaOfHelloWorld","kind":"bundledAsset",
            "asset":"pitch/x.zip","url":"https://example.com/x.zip"}"""
        assertTrue(runCatching { DictionaryCatalog.parse(catalog(bundled)) }.isFailure)
        // Duplicate ids are refused, as is an empty catalog.
        val one = """{"id":"x","name":"X","description":"d","license":"l","source":"s",
            "title":"X","bytes":1,"sha256":"$shaOfHelloWorld","kind":"bundledAsset","asset":"a"}"""
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
