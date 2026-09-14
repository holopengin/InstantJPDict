package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.ComponentTable
import com.holopengin.instantjpdict.util.KanaSizeFix
import com.holopengin.instantjpdict.util.OovCandidates
import com.holopengin.instantjpdict.util.OovSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #44: the two component/kana features are unconditional, asserted where the app actually
 * decides.
 *
 * Both used to sit behind a preference — `oov_suggestions_enabled` read per call through a
 * lambda the host handed the controller, and `kana_size_fix_enabled` read at the top of the
 * page-correction entry point. The rows and the reads are both gone, so the only gates left are
 * load states: whether the 266 KB component table has landed, and whether the native kana model
 * loaded. A stored `false` from an install that predates the removal has nothing left to
 * consult, and that is what these tests hold in place.
 *
 * The component fixture is sized so the arithmetic is real, exactly as in [OovSuggestionsTest]:
 * 50 kanji, `化` carried by two, `中` by twenty, putting a candidate sharing only the rare `化`
 * at ≈ 0.78 — clear of the measured 0.7 tier.
 *
 * [OcrOverlayStateController] is deliberately JVM-constructible (no Android types in its
 * decision path), which is how the wiring is exercised here without a device.
 */
class UnconditionalFeaturesTest {

    private fun fixtureTable(): ComponentTable {
        val sb = StringBuilder()
        sb.append("仲:化 中\n")          // the measured substitution pair
        sb.append("伜:化 九 十\n")       // shares 化, the rare half of 仲
        for (i in 0 until 19) sb.append("%c:中\n".format(0x4E00 + i))
        for (i in 0 until 29) sb.append("%c:水\n".format(0x5E00 + i))
        return ComponentTable.parse(sb.toString())
    }

    private fun controllerFor(text: String): OcrOverlayStateController {
        val c = OcrOverlayStateController()
        c.activeLineResults = mutableListOf(
            LineResult(
                text = text,
                charBoxes = emptyList(),
                alternatives = text.map { mutableListOf(it to 1f) },
            )
        )
        return c
    }

    // ————— the alternatives list —————

    @Test
    fun the_alternatives_list_carries_component_suggestions_once_the_table_lands() {
        val c = controllerFor("仲")
        c.installOovSuggestions(OovCandidates(fixtureTable()))
        val state = c.getAlternativesUiState(0, 0)!!
        assertTrue(
            "no component suggestion reached the panel: ${state.candidates.map { it.char }}",
            state.candidates.any { it.source == OovSuggestions.Source.COMPONENTS },
        )
        assertTrue(
            "the measured component neighbour is missing: ${state.candidates.map { it.char }}",
            state.candidates.any { it.char == '伜' },
        )
    }

    @Test
    fun the_head_list_is_all_that_is_left_before_the_table_lands() {
        // Not a preference: the table is parsed off the main thread, so the panel shows the
        // head's own list until it arrives and nothing more.
        val state = controllerFor("仲").getAlternativesUiState(0, 0)!!
        assertEquals(listOf('仲'), state.candidates.map { it.char })
        assertTrue(state.candidates.all { it.source == OovSuggestions.Source.HEAD })
    }

    // ————— the kana size correction —————

    /**
     * The page correction has no setting left to consult: the call the overlay makes — the
     * policy with its shipped defaults — rewrites a page the model is sure about. There is
     * deliberately no way to express "off".
     */
    @Test
    fun the_kana_policy_corrects_off_the_shipped_defaults_with_no_setting_read() {
        val line = LineResult(
            text = "かっき",
            charBoxes = emptyList(),
            alternatives = "かっき".map { mutableListOf(it to 1f) },
        )
        val out = KanaSizeFix.apply(listOf(line), score = { _, bases -> FloatArray(bases.size) { 10f } })
        assertEquals("the sure position must be flipped", "かつき", out[0].text)
        assertEquals("kana fix: 1 of 1 flipped", KanaSizeFix.lastSummary)
    }

    // ————— no enable toggle left to read —————

    @Test
    fun neither_feature_exposes_an_enable_toggle_a_stored_preference_could_reach() {
        // The names, not a scan of the sources: if a preference gate is reintroduced under the
        // old shape, it has to come back as one of these, and the requirement is that it cannot.
        val banned = setOf("isEnabled", "setEnabled", "getEnabled", "isEnabledOrDefault")
        for (feature in listOf("OovSuggestions" to OovSuggestions, "KanaSizeFix" to KanaSizeFix)) {
            val names = feature.second.javaClass.methods.map { it.name }.toSet()
            assertFalse(
                "${feature.first} grew a preference gate back: ${names intersect banned}",
                names.any { it in banned },
            )
        }
    }
}
