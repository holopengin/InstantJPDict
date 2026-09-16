package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.ImportProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog's row text: the line above each progress bar.
 *
 * These are pinned because they are the only thing telling the user that a long
 * download or import is moving rather than hung — a bar alone says "not done",
 * not "how far". The function is pure so the wording, the percentage rounding
 * and the "unknown total" shape are all assertable off-device; what cannot be
 * asserted here is that the text fits the row on a phone.
 */
class ImportProgressTest {

    // --- size formatting, used by the done/replaced phrases -----------------

    @Test
    fun sizes_read_as_human_units_at_each_boundary() {
        assertEquals("0 B", ImportProgress.formatBytes(0))
        assertEquals("512 B", ImportProgress.formatBytes(512))
        assertEquals("1.0 KB", ImportProgress.formatBytes(1024))
        assertEquals("1.5 KB", ImportProgress.formatBytes(1536))
        assertEquals("15.6 MB", ImportProgress.formatBytes(16_355_328)) // the JMdict zip
        assertEquals("1.0 GB", ImportProgress.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun counts_read_as_grouped_entries() {
        assertEquals("500", ImportProgress.formatCount(500))
        assertEquals("12,345", ImportProgress.formatCount(12_345))
        assertEquals("1,234,567", ImportProgress.formatCount(1_234_567))
    }

    // --- download -----------------------------------------------------------

    @Test
    fun download_names_the_phase_and_both_sizes() {
        val text = ImportProgress.download(
            written = 4_194_304, // 4.0 MB
            total = 16_355_328, // 15.6 MB
            fileName = "JMdict_english.zip",
        )
        assertTrue("names the phase: $text", text.startsWith("Downloading"))
        assertTrue("carries the file name: $text", text.contains("JMdict_english.zip"))
        assertTrue("carries progress over total: $text", text.contains("4.0 MB / 15.6 MB"))
        // 4_194_304 / 16_355_328 = 25.6% and is floored, so the text can never
        // claim a percentage the bar has not reached.
        assertTrue("carries a floored percentage: $text", text.contains("25%"))
        assertFalse("does not round up: $text", text.contains("26%"))
    }

    @Test
    fun download_percentage_is_floored_and_total_less_is_safe() {
        // 50% exactly, not 50.5 rounded up past the bar.
        assertTrue(ImportProgress.download(50, 100, "x.zip").contains("50%"))
        // A server that reports nothing usable must not divide by zero or lie.
        assertTrue(ImportProgress.download(0, 0, "x.zip").contains("0%"))
        assertTrue(ImportProgress.download(10, 0, "x.zip").contains("0%"))
        // Over-delivery (a wrong total) clamps instead of printing 120%.
        assertTrue(ImportProgress.download(120, 100, "x.zip").contains("100%"))
    }

    @Test
    fun download_reaches_one_hundred_percent_at_the_pinned_size() {
        val text = ImportProgress.download(16_355_328, 16_355_328, "JMdict_english.zip")
        assertTrue(text.contains("100%"))
        assertTrue(text.contains("15.6 MB / 15.6 MB"))
    }

    // --- verify -------------------------------------------------------------

    @Test
    fun verify_says_it_is_checking_the_file_it_downloaded() {
        val text = ImportProgress.verify(16_355_328, "JMdict_english.zip")
        assertTrue("names the phase: $text", text.startsWith("Verifying"))
        assertTrue("says what is checked: $text", text.contains("SHA-256"))
        assertTrue("carries the size: $text", text.contains("15.6 MB"))
    }

    // --- import -------------------------------------------------------------

    @Test
    fun import_with_a_known_total_shows_count_and_percentage() {
        val text = ImportProgress.importing(
            processed = 150_000,
            total = 216_000,
            name = "JMdict",
            indeterminate = false,
        )
        assertTrue("names the phase: $text", text.startsWith("Importing"))
        assertTrue("names the dictionary: $text", text.contains("JMdict"))
        assertTrue("carries entries: $text", text.contains("150,000 / 216,000 entries"))
        assertTrue("carries a percentage: $text", text.contains("69%"))
    }

    @Test
    fun import_without_a_known_total_stays_honest_about_it() {
        // The bundled pitch install reports a count but no total; inventing one
        // would make the bar move without meaning.
        val text = ImportProgress.importing(
            processed = 124_000,
            total = null,
            name = "Pitch accents",
            indeterminate = true,
        )
        assertTrue("names the phase: $text", text.startsWith("Importing"))
        assertTrue("names the dictionary: $text", text.contains("Pitch accents"))
        assertTrue("states the count so far: $text", text.contains("124,000"))
        assertFalse("claims no percentage without a total: $text", text.contains("%"))
        assertFalse("claims no total it does not have: $text", text.contains(" / "))
    }

    @Test
    fun import_totals_are_clamped_like_the_bar_is() {
        val text = ImportProgress.importing(
            processed = 250_000,
            total = 216_000,
            name = "JMdict",
            indeterminate = false,
        )
        assertTrue("clamps past the total: $text", text.contains("100%"))
    }

    // --- the finish line ----------------------------------------------------

    @Test
    fun done_reports_what_landed_in_plain_words() {
        val text = ImportProgress.done(216_000, "JMdict", builtIn = false)
        assertTrue("names what happened: $text", text.startsWith("Imported"))
        assertTrue("names the dictionary: $text", text.contains("JMdict"))
        assertTrue("counts the entries: $text", text.contains("216,000 entries"))
        assertFalse("a normal import is not called bundled: $text", text.contains("bundled"))
    }

    @Test
    fun done_marks_a_built_in_dictionary_as_bundled() {
        val text = ImportProgress.done(124_000, "Pitch accents", builtIn = true)
        assertTrue("says it is bundled: $text", text.contains("(bundled)"))
        assertTrue("still counts the entries: $text", text.contains("124,000 entries"))
    }

    // --- failure ------------------------------------------------------------

    @Test
    fun a_key_mismatch_explains_it_in_words_a_user_can_act_on() {
        val text = ImportProgress.failure(
            "Download hash abc123 does not match the pinned def456 — discarded"
        )
        assertTrue("names the phase: $text", text.startsWith("Download failed"))
        assertTrue("keeps the reason: $text", text.contains("does not match"))
        assertTrue("drops the raw hash noise: $text", !text.contains("abc123"))
        assertTrue("ends with the consequence: $text", text.contains("Nothing was imported"))
    }

    @Test
    fun a_size_mismatch_is_reported_as_a_short_or_interrupted_download() {
        val text = ImportProgress.failure("Download is 900 bytes, expected 1024 — discarded")
        assertTrue(text.startsWith("Download failed"))
        assertTrue("reads as truncation, not corruption: $text", text.contains("interrupted"))
    }

    @Test
    fun an_unrecognised_failure_still_gets_a_sentence() {
        val text = ImportProgress.failure("java.io.IOException: unexpected end of stream")
        assertTrue(text.startsWith("Download failed"))
        assertFalse("no raw exception prefix is shown: $text", text.contains("java.io"))
        assertTrue(text.contains("Nothing was imported"))
    }

    @Test
    fun a_failure_with_no_message_at_all_is_still_user_readable() {
        val text = ImportProgress.failure(null)
        assertTrue(text.startsWith("Download failed"))
        assertTrue(text.contains("Nothing was imported"))
    }
}
