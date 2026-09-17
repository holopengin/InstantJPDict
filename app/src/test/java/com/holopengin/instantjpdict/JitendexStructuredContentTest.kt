package com.holopengin.instantjpdict

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.holopengin.instantjpdict.data.DictionaryEntry
import com.holopengin.instantjpdict.util.Definitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #88: Jitendex renders through the shared structured-content walker. The
 * inputs are real rows from the pinned `jitendex-yomitan.zip` release
 * 2026.08.11.0 (`src/test/resources/jitendex/entries.json`), so the tests pin
 * the shapes Jitendex actually emits: the variant-forms table, the
 * `example-sentence` parts, cross references, antonyms, notes, source-language
 * info, and the `extra-box` block/inline classification.
 *
 * Each test pins both halves the issue names: the parsed node tree
 * (`parseDefinition`) and the plain-text flattening (`Definitions.plain`) that
 * feeds bookmark snapshots and CSV export.
 */
class JitendexStructuredContentTest {

    private val gson = Gson()
    private val controller = OcrOverlayStateController()

    private class Fixture(val term: String, val reading: String, val definitions: Any?) {
        /** The JSON the importer would have stored for this row. */
        val definitionsJson: String get() = Gson().toJson(definitions)
    }

    private val fixtures: List<Fixture> by lazy {
        val root = JsonParser.parseString(TestAssets.resourceText("jitendex/entries.json")).asJsonObject
        root.getAsJsonArray("entries").map { element ->
            val o = element.asJsonObject
            Fixture(
                term = o.get("term").asString,
                reading = o.get("reading").asString,
                definitions = gson.fromJson(o.get("definitions"), Any::class.java),
            )
        }
    }

    private fun fixture(term: String): Fixture =
        fixtures.firstOrNull { it.term == term } ?: error("no fixture for $term")

    private fun parse(term: String) = controller.parseDefinition(fixture(term).definitions)

    /** All descendant nodes, depth-first. */
    private fun flatten(nodes: List<DefinitionNode>): List<DefinitionNode> =
        nodes + nodes.flatMap { flatten(childrenOf(it)) }

    private fun childrenOf(node: DefinitionNode): List<DefinitionNode> = when (node) {
        is DefinitionNode.Text, is DefinitionNode.Ruby, is DefinitionNode.Tag -> emptyList()
        is DefinitionNode.Group -> node.nodes
        is DefinitionNode.ListBlock -> node.items.flatten()
        is DefinitionNode.Example -> (node.content ?: emptyList()) + node.parts.flatten()
        is DefinitionNode.Table -> node.rows.flatten().flatten()
        is DefinitionNode.Citation -> emptyList()
    }

    /** Readable tokens in render order: text runs and ruby headwords. */
    private fun tokens(nodes: List<DefinitionNode>): List<String> =
        flatten(nodes).mapNotNull { node ->
            when (node) {
                is DefinitionNode.Text -> node.text
                is DefinitionNode.Tag -> node.text
                is DefinitionNode.Ruby -> node.term
                else -> null
            }
        }

    private fun cellTexts(nodes: List<DefinitionNode>): List<String> = tokens(nodes)

    /** A token, ignoring the inline `", "` the walker appends to the preceding run. */
    private fun hasToken(nodes: List<DefinitionNode>, text: String): Boolean =
        tokens(nodes).any { it == text || it.startsWith("$text,") }

    // —— the variant-forms table ————————————————————————————————

    @Test
    fun the_forms_table_parses_into_rows_and_cells() {
        val table = parse("支持杭").filterIsInstance<DefinitionNode.Table>().single()
        // Header row (blank corner + the spelling) and one row per reading.
        assertEquals(3, table.rows.size)
        assertEquals(2, table.rows[0].size)
        assertEquals(listOf<String>(), cellTexts(table.rows[0][0]))
        assertEquals(listOf("支持杭"), cellTexts(table.rows[0][1]))
        assertEquals(listOf("しじぐい"), cellTexts(table.rows[1][0]))
        assertEquals(listOf("しじくい"), cellTexts(table.rows[2][0]))
    }

    @Test
    fun the_forms_table_draws_the_validity_marker_upstream_draws() {
        val table = parse("支持杭").filterIsInstance<DefinitionNode.Table>().single()
        // A valid form cell is an empty span in the data; the class is what
        // carries the meaning, so it must become a glyph, not a blank cell.
        assertEquals(listOf("◇"), cellTexts(table.rows[1][1]))
        assertEquals(listOf("◇"), cellTexts(table.rows[2][1]))
    }

    @Test
    fun a_forms_section_can_also_be_a_plain_list() {
        // ダイニングキッチン has no table: its forms are a `ul` of spellings.
        val block = flatten(parse("ダイニングキッチン"))
            .filterIsInstance<DefinitionNode.ListBlock>()
            .single { it.type == null || it.type == "forms" }
        assertEquals(
            listOf("ダイニングキッチン", "ダイニング・キッチン"),
            block.items.map { cellTexts(it) }.map { it.firstOrNull().orEmpty() },
        )
    }

    // —— examples ————————————————————————————————————————————————

    @Test
    fun a_jitendex_example_splits_into_japanese_and_english_parts() {
        val example = flatten(parse("湾内")).filterIsInstance<DefinitionNode.Example>().single()
        assertEquals(2, example.parts.size)
        // Japanese part keeps its ruby.
        assertTrue(
            example.parts[0].filterIsInstance<DefinitionNode.Ruby>().map { it.term }.contains("湾"),
        )
        assertTrue(cellTexts(example.parts[0]).joinToString("").contains("えられた。"))
        // English part is a plain sentence.
        assertTrue(
            cellTexts(example.parts[1]).joinToString(" ").contains("privilege of fishing in this bay"),
        )
    }

    // —— cross references / antonyms ——————————————————————————

    @Test
    fun cross_references_render_as_text_without_comma_noise() {
        assertTrue(tokens(parse("湾内")).contains("See also"))
        assertTrue(tokens(parse("湾内")).contains("beyond the bay"))
        // The link target 湾外 is two ruby kanji; they are one word, never
        // "湾, 外". The old walker spliced a separator between every inline
        // pair, including ruby runs.
        assertFalse(tokens(parse("湾内")).contains(", "))
    }

    @Test
    fun antonyms_render_as_text() {
        val nodes = parse("ディフェンシブ")
        assertTrue(hasToken(nodes, "Antonym"))
        assertTrue(hasToken(nodes, "オフェンシブ"))
        assertTrue(hasToken(nodes, "offensive"))
    }

    // —— notes and source-language info ———————————————————————

    @Test
    fun a_sense_note_is_a_block_so_it_is_not_comma_joined_to_its_neighbour() {
        val tokens = tokens(parse("あかんべえ"))
        assertTrue(tokens.contains("from 赤目"))
        val noteIndex = tokens.indexOf("from 赤目")
        // The next thing after the note is the cross reference, not ", ".
        assertEquals("See also", tokens[noteIndex + 1])
    }

    @Test
    fun source_language_info_renders_its_label_body_and_tags() {
        val nodes = parse("ダイニングキッチン")
        assertTrue(hasToken(nodes, "Language of Origin"))
        assertTrue(hasToken(nodes, "English: \"dining kitchen\""))
        assertTrue(hasToken(nodes, "wasei"))
    }

    @Test
    fun a_definition_note_renders_its_label_and_body() {
        val nodes = parse("袋小路文")
        assertTrue(hasToken(nodes, "Literally"))
        assertTrue(hasToken(nodes, "cul-de-sac sentence"))
    }

    @Test
    fun the_source_line_is_a_citation_node_with_its_labels_joined() {
        // 湾内 names both sources; the labels join in document order.
        assertEquals(
            "JMdict | Tatoeba",
            flatten(parse("湾内")).filterIsInstance<DefinitionNode.Citation>().single().text,
        )
        // A JMdict-only entry still carries one.
        assertEquals(
            "JMdict",
            flatten(parse("支持杭")).filterIsInstance<DefinitionNode.Citation>().single().text,
        )
    }

    @Test
    fun the_sense_groups_list_keeps_its_senses() {
        val block = flatten(parse("あかんべえ")).filterIsInstance<DefinitionNode.ListBlock>().first()
        assertEquals("sense-groups", block.type)
        assertEquals(3, block.items.size)
        val nodes = parse("あかんべえ")
        assertTrue(hasToken(nodes, "no way!"))
        assertTrue(hasToken(nodes, "get lost!"))
        assertTrue(hasToken(nodes, "あっかんべー"))
    }

    // —— plain text ————————————————————————————————————————————

    @Test
    fun plain_text_is_pinned_for_each_fixture() {
        val expected = mapOf(
            "支持杭" to "noun, architecture, bearing pile, forms, 支持杭, しじぐい, しじくい, JMdict",
            "ダイニングキッチン" to
                "noun, eat-in kitchen, combination kitchen-dining room, Language of Origin, " +
                "English: \"dining kitchen\", wasei, forms, ダイニングキッチン, ダイニング・キッチン, JMdict",
            "湾内" to
                "noun, inside the bay, 我々, われわれ, はこの, 湾, わん, 内, ない, で, 漁, ぎょ, 獲, かく, " +
                "する, 特, とっ, 権, けん, を, 与, あた, えられた。, " +
                "We were granted the privilege of fishing in this bay., " +
                "See also, 湾, わん, 外, がい, beyond the bay, JMdict,  | , Tatoeba",
            "あかんべえ" to
                "noun, pulling down one's lower eyelid and sticking out one's tongue (as a taunt or " +
                "gesture of contempt or rejection), Note, from 赤目, See also, 赤, あか, 目, べ, " +
                "facial gesture of pulling one's eyelid down and sticking out one's tongue, " +
                "interjection, no way!, no!, get lost!, forms, あかんべえ, あかんべ, あっかんべー, JMdict",
            "袋小路文" to "noun, easily misunderstood sentence, garden-path sentence, Literally, cul-de-sac sentence, JMdict",
            "ディフェンシブ" to "na-adj, defensive, Antonym, オフェンシブ, offensive, JMdict",
        )
        expected.forEach { (term, text) ->
            assertEquals(term, text, Definitions.plain(fixture(term).definitionsJson))
        }
    }

    @Test
    fun plain_text_carries_no_attribute_noise() {
        val plain = Definitions.plain(fixture("支持杭").definitionsJson)
        listOf("span", "form-valid", "valid form/reading combination", "data", "href", "style")
            .forEach { noise -> assertFalse("plain text leaked '$noise': $plain", plain.contains(noise)) }
    }

    // —— fail open ————————————————————————————————————————————

    @Test
    fun an_unknown_tag_renders_its_content_rather_than_dropping_it() {
        val nodes = controller.parseDefinition(
            gson.fromJson("""{"tag":"mark","content":"kept"}""", Any::class.java),
        )
        assertEquals(listOf(DefinitionNode.Text("kept")), nodes)
    }

    @Test
    fun an_unknown_node_inside_a_sense_never_blanks_the_sense() {
        val json = """
            [{"type":"structured-content","content":[
              {"tag":"div","data":{"content":"sense"},"content":[
                {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"a gloss"}},
                {"tag":"future-widget","content":"future text"}
              ]}
            ]}]
        """.trimIndent()
        val nodes = controller.parseDefinition(gson.fromJson(json, Any::class.java))
        assertTrue(hasToken(nodes, "a gloss"))
        assertTrue(hasToken(nodes, "future text"))
    }

    @Test
    fun a_malformed_ruby_still_renders_its_content() {
        val nodes = controller.parseDefinition(
            gson.fromJson("""{"tag":"ruby","content":"just text"}""", Any::class.java),
        )
        assertEquals(listOf(DefinitionNode.Text("just text")), nodes)
    }

    @Test
    fun an_unshaped_table_still_renders_its_content() {
        val nodes = controller.parseDefinition(
            gson.fromJson("""{"tag":"table","content":"not rows"}""", Any::class.java),
        )
        assertEquals(listOf(DefinitionNode.Text("not rows")), nodes)
    }

    // —— sense numbering ————————————————————————————————————————

    /** Format one real Jitendex row and return its sense groups. */
    private fun formatted(term: String) = controller.formatDictionaryResults(
        listOf(
            TermMatch(
                term,
                listOf(
                    DictionaryEntry(
                        kanji = term,
                        reading = fixture(term).reading,
                        definitions = fixture(term).definitionsJson,
                        rules = "",
                        popularity = 0,
                        dictionaryId = 1,
                    )
                )
            )
        ),
        gson,
        mapOf(1 to "Jitendex"),
    ).single().readingGroups.single().senseGroups

    @Test
    fun senses_are_numbered_across_a_jitendex_entry_not_all_under_1() {
        // あかんべえ is two sense-groups of one sense each.
        assertEquals(
            listOf(1, 2),
            formatted("あかんべえ").flatMap { it.senses }.map { it.index },
        )
        // いじらしい is ONE sense-group holding two senses: the case that
        // collapses to a single "1." when senses are counted per row.
        assertEquals(
            listOf(1, 2),
            formatted("いじらしい").single().senses.map { it.index },
        )
    }

    @Test
    fun jitendex_group_metadata_is_a_header_and_forms_trail_the_senses() {
        val group = formatted("支持杭").single()
        // POS/field info is structured content, rendered once as the header.
        assertTrue(group.header.isNotEmpty())
        assertTrue(hasToken(group.header, "noun"))
        // The forms table and attribution are trailing, not numbered senses.
        assertEquals(1, group.senses.size)
        assertTrue(group.trailing.isNotEmpty())
        assertTrue(flatten(group.trailing).filterIsInstance<DefinitionNode.Table>().isNotEmpty())
    }

    @Test
    fun a_plain_row_still_yields_a_single_sense() {
        val out = controller.formatDictionaryResults(
            listOf(
                TermMatch(
                    "他",
                    listOf(
                        DictionaryEntry(
                            kanji = "他",
                            reading = "た",
                            definitions = "\"other\"",
                            rules = "",
                            popularity = 0,
                            dictionaryId = 1,
                        )
                    )
                )
            ),
            gson,
            mapOf(1 to "Jitendex"),
        )
        val senses = out.single().readingGroups.single().senseGroups.flatMap { it.senses }
        assertEquals(listOf(1), senses.map { it.index })
        assertTrue(senses.single().nodes.isNotEmpty())
    }
}
