package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.data.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #67: bookmark identity, viewer ordering and CSV escaping are pure logic. */
class BookmarkExportTest {

    private fun bookmark(
        kanji: String,
        reading: String,
        dictionary: String,
        definitions: String = "a sense",
        createdAt: Long = 0L,
        id: Long = 0L
    ) = Bookmark(
        id = id,
        kanji = kanji,
        reading = reading,
        dictionaryName = dictionary,
        dictionaryId = 1,
        definitionsText = definitions,
        createdAt = createdAt
    )

    // —— identity ————————————————————————————————————————————————

    @Test
    fun same_headword_and_dictionary_is_the_same_key() {
        val a = BookmarkCandidate("食べる", "たべる", "JMdict", 1, "")
        val b = BookmarkCandidate("食べる", "たべる", "JMdict", 99, "different snapshot")
        assertEquals(a.key, b.key)
    }

    @Test
    fun same_headword_in_two_dictionaries_is_not_the_same_key() {
        val jm = BookmarkCandidate("食べる", "たべる", "JMdict", 1, "")
        val kd = BookmarkCandidate("食べる", "たべる", "KANJIDIC", 2, "")
        assertNotEquals(jm.key, kd.key)
    }

    @Test
    fun key_ignores_the_mutable_dictionary_id() {
        // The id churns on re-import; the key must not.
        val before = BookmarkCandidate("君", "きみ", "JMdict", 1, "")
        val after = BookmarkCandidate("君", "きみ", "JMdict", 42, "")
        assertEquals(before.key, after.key)
    }

    // —— ordering ———————————————————————————————————————————————

    @Test
    fun default_order_is_oldest_first() {
        val rows = listOf(
            bookmark("b", "b", "d", createdAt = 20, id = 2),
            bookmark("a", "a", "d", createdAt = 10, id = 1),
            bookmark("c", "c", "d", createdAt = 30, id = 3)
        )
        assertEquals(listOf("a", "b", "c"), BookmarkSort.apply(rows, newestFirst = false).map { it.kanji })
    }

    @Test
    fun recency_option_is_newest_first() {
        val rows = listOf(
            bookmark("b", "b", "d", createdAt = 20, id = 2),
            bookmark("a", "a", "d", createdAt = 10, id = 1),
            bookmark("c", "c", "d", createdAt = 30, id = 3)
        )
        assertEquals(listOf("c", "b", "a"), BookmarkSort.apply(rows, newestFirst = true).map { it.kanji })
    }

    @Test
    fun timestamp_ties_break_on_id_deterministically() {
        val rows = listOf(
            bookmark("first", "x", "d", createdAt = 5, id = 1),
            bookmark("second", "x", "d", createdAt = 5, id = 2)
        )
        assertEquals(listOf("first", "second"), BookmarkSort.apply(rows, newestFirst = false).map { it.kanji })
        assertEquals(listOf("second", "first"), BookmarkSort.apply(rows, newestFirst = true).map { it.kanji })
    }

    // —— CSV escaping ———————————————————————————————————————————

    @Test
    fun plain_fields_are_not_quoted() {
        assertEquals("hello", BookmarkCsv.escape("hello"))
        assertEquals("食べる", BookmarkCsv.escape("食べる"))
    }

    @Test
    fun commas_force_quotes_and_are_preserved() {
        assertEquals("\"a, b\"", BookmarkCsv.escape("a, b"))
    }

    @Test
    fun quotes_are_doubled_inside_a_quoted_field() {
        assertEquals("\"say \"\"hi\"\"\"", BookmarkCsv.escape("say \"hi\""))
    }

    @Test
    fun newlines_force_quotes_and_are_preserved() {
        assertEquals("\"line1\nline2\"", BookmarkCsv.escape("line1\nline2"))
        assertEquals("\"line1\r\nline2\"", BookmarkCsv.escape("line1\r\nline2"))
    }

    @Test
    fun render_writes_a_header_and_one_row_per_bookmark() {
        val csv = BookmarkCsv.render(listOf(bookmark("食", "しょく", "JMdict", definitions = "eat, food")))
        val lines = csv.trimEnd('\n').split('\n')
        assertEquals("kanji,reading,dictionary,definitions,added_at", lines[0])
        assertEquals("食,しょく,JMdict,\"eat, food\",1970-01-01T00:00:00Z", lines[1])
    }

    @Test
    fun render_keeps_a_multiline_definition_inside_one_quoted_field() {
        val csv = BookmarkCsv.render(
            listOf(bookmark("食", "しょく", "JMdict", definitions = "line one\nline, two \"quoted\""))
        )
        // The embedded newline must not become a second CSV record: the field is
        // quoted, so the raw text round-trips through any RFC 4180 reader.
        assertEquals(
            "kanji,reading,dictionary,definitions,added_at\n" +
                "食,しょく,JMdict,\"line one\nline, two \"\"quoted\"\"\",1970-01-01T00:00:00Z\n",
            csv
        )
    }

    @Test
    fun render_produces_a_utf8_safe_file_name_and_iso_timestamp() {
        assertEquals("instant-jpdict-bookmarks-20260916-123456.csv", BookmarkCsv.fileName(1789562096000L))
        assertEquals("2026-09-16T12:34:56Z", BookmarkCsv.timestamp(1789562096000L))
    }

    // —— definition flattening —————————————————————————————————

    @Test
    fun definitions_flatten_a_list_of_senses_to_lines() {
        assertEquals("to eat\nfood", Definitions.plain("[\"to eat\",\"food\"]"))
    }

    @Test
    fun definitions_flatten_a_bare_string() {
        assertEquals("to eat", Definitions.plain("\"to eat\""))
    }

    @Test
    fun definitions_flatten_nested_arrays_with_commas() {
        assertEquals("to eat, to consume\nfood", Definitions.plain("[[\"to eat\",\"to consume\"],\"food\"]"))
    }

    @Test
    fun definitions_flatten_an_object_via_content() {
        assertEquals("a note", Definitions.plain("{\"tag\":\"span\",\"content\":\"a note\"}"))
    }

    @Test
    fun definitions_fall_back_to_the_raw_blob_when_unparseable() {
        assertEquals("{not json", Definitions.plain("{not json"))
    }

    @Test
    fun definitions_plain_all_joins_entries_and_drops_duplicates() {
        assertEquals("one\ntwo", Definitions.plainAll(listOf("[\"one\"]", "[\"two\",\"one\"]")))
    }
}
