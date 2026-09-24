//! UniFFI shim over `jpdict_core::util::oov_suggestions` (#44, WP-10 of the
//! util-module conversion wave).
//!
//! The popup-list assembly crosses as a stateless free function plus a record
//! and an enum: [`Suggestion`] carries the character as its `i32` code point
//! (Kotlin `Char.code`) and [`SuggestionSource`] mirrors the PC `Source`. The PC algorithm's `variant_forms: &dyn Fn(char) -> Vec<char>`
//! does not cross an FFI boundary, so the caller resolves the forms for the
//! current character and passes them as data; this shim rebuilds the constant
//! closure internally. That keeps the Kotlin `variantForms` lambda (the tests'
//! seam) working, and the lambda's default still resolves through the
//! UniFFI-backed [`crate::kanji_variants`] table, so nothing is forked.
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (`uniffi.nav_graph_core.Suggestion`,
//! `uniffi.nav_graph_core.SuggestionSource`, the `oovSuggestionsAssemble` free
//! function and the cap accessors) is wrapped by the hand-written
//! `com.holopengin.instantjpdict.util.OovSuggestions` object, which keeps the
//! pre-conversion API (`MAX_COMPONENT_CANDIDATES` and `MAX_VARIANT_CANDIDATES`,
//! now read from the accessors rather than hand-typed, plus `Source`,
//! `Suggestion(char, source)` and `assemble(Char, List<Char>, OovCandidates?,
//! (Char) -> List<Char>)` with its lambda default) so no call site or test
//! changes. `MIN_IDF_FRACTION` was a dead mirror and is gone; the tier is
//! enforced in the crate. The facade absorbs all `Char` ↔ `Int` conversion.

use std::sync::Arc;

use jpdict_core::util::oov_suggestions::Source as CoreSource;

/// Decode a Kotlin `Int` code point back into a Rust `char`.
///
/// The Kotlin side always sends valid `Char.code` values; anything else (a
/// negative, a surrogate) degrades to U+FFFD rather than panicking at the FFI
/// boundary, matching `char_lm.rs`.
fn char_from_codepoint(codepoint: i32) -> char {
    u32::try_from(codepoint)
        .ok()
        .and_then(char::from_u32)
        .unwrap_or('\u{FFFD}')
}

/// Decode a Kotlin `List<Int>` of code points back into Rust `char`s.
fn chars_from_codepoints(codepoints: &[i32]) -> Vec<char> {
    codepoints
        .iter()
        .map(|&cp| char_from_codepoint(cp))
        .collect()
}

/// Where a popup entry came from. The panel tints non-`Head` entries by source
/// so the provenance is visible at a glance.
///
/// Mirrors `jpdict_core::util::oov_suggestions::Source`; the Kotlin facade
/// re-exposes it as its own `Source` enum so call sites are unchanged.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SuggestionSource {
    Head,
    Components,
    Variant,
    /// The blank path's LM-ranked entries (mobile tags them; `assemble` never
    /// emits this variant itself).
    Lm,
}

/// One entry of the popup list: the character and where it came from.
///
/// The Kotlin facade re-exposes this as
/// `OovSuggestions.Suggestion(char: Char, source: Source)`.
#[derive(Clone, Debug, uniffi::Record)]
pub struct Suggestion {
    /// The suggested character, as its `i32` code point (Kotlin `Char.code`).
    pub ch: i32,
    /// Why the entry is in the list.
    pub source: SuggestionSource,
}

/// Map a PC source onto the exported enum (the Kotlin facade reverses this).
fn source_from_core(source: CoreSource) -> SuggestionSource {
    match source {
        CoreSource::Head => SuggestionSource::Head,
        CoreSource::Components => SuggestionSource::Components,
        CoreSource::Variant => SuggestionSource::Variant,
        CoreSource::Lm => SuggestionSource::Lm,
    }
}

/// The popup list for one character: the head's own ranking first and
/// unchanged, then component neighbours by descending IDF mass, then the
/// obsolete variant forms of the current character.
///
/// Delegates to `jpdict_core::util::oov_suggestions::assemble`; the rules
/// (IDF tier, group caps, cross-group dedup, the current character always
/// present) are documented upstream. `variants` is the already-resolved
/// `variant_forms(current)` list: the PC closure takes the current character,
/// but every caller resolves it before the call (the Kotlin facade evaluates
/// its lambda), so a constant closure over `variants` is equivalent. Pass
/// `oov: None` when the component table is unavailable: the list is then the
/// head list unchanged.
#[uniffi::export]
pub fn oov_suggestions_assemble(
    current: i32,
    head_alternatives: Vec<i32>,
    oov: Option<Arc<crate::oov_candidates::OovCandidates>>,
    variants: Vec<i32>,
) -> Vec<Suggestion> {
    let head = chars_from_codepoints(&head_alternatives);
    let forms = chars_from_codepoints(&variants);
    let variant_forms = move |_ch: char| forms.clone();
    jpdict_core::util::oov_suggestions::assemble(
        char_from_codepoint(current),
        &head,
        oov.as_ref().map(|o| o.inner()),
        &variant_forms,
    )
    .into_iter()
    .map(|s| Suggestion {
        ch: s.ch as i32,
        source: source_from_core(s.source),
    })
    .collect()
}

/// The component-group cap (mobile `OovSuggestions.MAX_COMPONENT_CANDIDATES`),
/// Rust-sourced because UniFFI cannot export consts.
#[uniffi::export]
pub fn oov_suggestions_max_component_candidates() -> i64 {
    jpdict_core::util::oov_suggestions::MAX_COMPONENT_CANDIDATES as i64
}

/// The variant-group cap (mobile `OovSuggestions.MAX_VARIANT_CANDIDATES`),
/// Rust-sourced because UniFFI cannot export consts.
#[uniffi::export]
pub fn oov_suggestions_max_variant_candidates() -> i64 {
    jpdict_core::util::oov_suggestions::MAX_VARIANT_CANDIDATES as i64
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::util::oov_suggestions` unit tests, run
    //! against the exact dependency this crate delegates to — through the
    //! exported surface, not the upstream module, so the shim's `char` ↔ `i32`
    //! conversion and its closure-as-data resolution are covered too. The JVM
    //! suite (`OovSuggestionsTest`) pins the same behaviour across the UniFFI
    //! boundary.

    use super::*;
    use crate::component_table::ComponentTable;
    use jpdict_core::util::oov_suggestions::{MAX_COMPONENT_CANDIDATES, MAX_VARIANT_CANDIDATES};

    /// The mobile fixture, sized so the arithmetic is real, not incidental:
    /// 50 kanji, `化` carried by two of them and `中` by twenty, which puts a
    /// candidate sharing only `化` at ln(50/2)/(ln(50/2)+ln(50/20)) ≈ 0.78 —
    /// clear of the measured 0.7 tier — while a candidate sharing only the
    /// common `中` sits at ≈ 0.22 and must be excluded.
    fn fixture_oov() -> Arc<crate::oov_candidates::OovCandidates> {
        let mut text = String::from("仲:化 中\n伜:化 九 十\n");
        for i in 0..19 {
            text.push(char::from_u32(0x4E00 + i).expect("BMP"));
            text.push_str(":中\n");
        }
        for i in 0..29 {
            text.push(char::from_u32(0x5E00 + i).expect("BMP"));
            text.push_str(":水\n");
        }
        crate::oov_candidates::OovCandidates::new(ComponentTable::parse(text))
    }

    /// Code points of `text` — the shape the exported surface takes.
    fn cps(text: &str) -> Vec<i32> {
        text.chars().map(|c| c as i32).collect()
    }

    fn chars(out: &[Suggestion]) -> Vec<i32> {
        out.iter().map(|s| s.ch).collect()
    }

    fn sources(out: &[Suggestion]) -> Vec<SuggestionSource> {
        out.iter().map(|s| s.source).collect()
    }

    #[test]
    fn component_neighbour_is_appended_after_the_head_list() {
        let oov = fixture_oov();
        let out = oov_suggestions_assemble('仲' as i32, cps("仲"), Some(oov), Vec::new());
        assert_eq!(chars(&out), cps("仲伜"));
        assert_eq!(
            sources(&out),
            vec![SuggestionSource::Head, SuggestionSource::Components]
        );
    }

    #[test]
    fn head_order_is_preserved_and_a_repeated_candidate_is_not_appended_twice() {
        let oov = fixture_oov();
        let out = oov_suggestions_assemble('仲' as i32, cps("伜仲"), Some(oov), Vec::new());
        assert_eq!(chars(&out), cps("伜仲"));
        assert!(sources(&out).iter().all(|s| *s == SuggestionSource::Head));
    }

    #[test]
    fn the_variant_group_walks_the_form_space_from_the_current_selection() {
        // Suggestions are assembled from whatever character is *current*, so
        // choosing a generated entry rebuilds the candidates around it:
        // 摑 -> 掴 -> 摑 is reachable by tapping through the popup (#44).
        // Pinned so a refactor cannot quietly make the list depend on the
        // character the recogniser originally emitted instead.
        let from_old = oov_suggestions_assemble('摑' as i32, cps("摑"), None, cps("掴"));
        assert_eq!(
            from_old.last().map(|s| s.source),
            Some(SuggestionSource::Variant)
        );
        assert_eq!(from_old.last().map(|s| s.ch), Some('掴' as i32));
        let from_modern = oov_suggestions_assemble('掴' as i32, cps("掴"), None, cps("摑"));
        assert_eq!(
            from_modern.last().map(|s| s.source),
            Some(SuggestionSource::Variant)
        );
        assert_eq!(from_modern.last().map(|s| s.ch), Some('摑' as i32));
    }

    #[test]
    fn a_single_component_character_gets_no_component_suggestions() {
        // The IDF fraction is relative to the emitted character, so a
        // one-component character makes every one of its carriers a full
        // match (fraction 1.0): the tier would admit hundreds of unrelated
        // characters and the cap would then choose between them by codepoint.
        // No discriminating evidence, no suggestions.
        let oov = fixture_oov();
        let one = 0x4E00; // carries 中 only
        let out = oov_suggestions_assemble(one, vec![one], Some(oov), Vec::new());
        assert_eq!(chars(&out), vec![one]);
    }

    #[test]
    fn variant_forms_are_offered_last_and_capped() {
        let oov = fixture_oov();
        // Twenty forms, so the cap — not the supply — is what bounds the group.
        let forms: Vec<i32> = (0..20).map(|i| 0x5F00 + i).collect();
        let out =
            oov_suggestions_assemble('仲' as i32, cps("仲"), Some(oov.clone()), forms.clone());
        let variant_entries: Vec<&Suggestion> = out
            .iter()
            .filter(|s| s.source == SuggestionSource::Variant)
            .collect();
        assert_eq!(MAX_VARIANT_CANDIDATES, variant_entries.len());
        let first = out
            .iter()
            .position(|s| s.source == SuggestionSource::Variant)
            .expect("variants");
        assert_eq!(MAX_VARIANT_CANDIDATES, out.len() - first);
        // A set smaller than the cap is offered whole, in order.
        let few = cps("囘欝");
        let small = oov_suggestions_assemble('回' as i32, cps("回"), Some(oov), few.clone());
        let got: Vec<i32> = small
            .iter()
            .filter(|s| s.source == SuggestionSource::Variant)
            .map(|s| s.ch)
            .collect();
        assert_eq!(few, got);
    }

    #[test]
    fn component_group_is_capped() {
        // Emitted 仲 = 化 + 中, with 化 carried by 17 of 911 kanji and 中 by
        // 895: a candidate sharing only 化 scores
        // ln(911/17)/(ln(911/17)+ln(911/895)) ≈ 0.995, clear of the tier, so
        // sixteen qualify and the cap — not the tier — bounds the list.
        let mut wide = String::from("仲:化 中\n");
        for i in 0..16 {
            wide.push(char::from_u32(0x6C00 + i).expect("BMP"));
            wide.push_str(":化\n");
        }
        for i in 0..894 {
            wide.push(char::from_u32(0x4E00 + i).expect("BMP"));
            wide.push_str(":中\n");
        }
        let oov = crate::oov_candidates::OovCandidates::new(ComponentTable::parse(wide));
        let out = oov_suggestions_assemble('仲' as i32, cps("仲"), Some(oov), Vec::new());
        assert_eq!(
            MAX_COMPONENT_CANDIDATES,
            out.iter()
                .filter(|s| s.source == SuggestionSource::Components)
                .count()
        );
    }

    #[test]
    fn without_a_component_table_the_list_is_the_head_list_unchanged() {
        let head = cps("あ仲い");
        let out = oov_suggestions_assemble('仲' as i32, head.clone(), None, Vec::new());
        assert_eq!(chars(&out), head);
        assert!(sources(&out).iter().all(|s| *s == SuggestionSource::Head));
    }

    #[test]
    fn the_current_character_is_present_even_when_the_head_list_omits_it() {
        // An override can put a character in the text that the head never
        // ranked here.
        let oov = fixture_oov();
        let out = oov_suggestions_assemble('伜' as i32, cps("仲"), Some(oov), Vec::new());
        assert!(
            out.iter().any(|s| s.ch == '伜' as i32),
            "current character must be listed"
        );
        assert_eq!(out.first().map(|s| s.ch), Some('仲' as i32));
    }

    /// The four-way `Source` mapping the Kotlin facade reverses: every PC
    /// variant has an exported counterpart. `Lm` is exercised only here —
    /// `assemble` never emits it (the Kotlin blank path tags its LM-ranked
    /// entries itself) — so this is the one place the mapping is pinned.
    #[test]
    fn every_pc_source_maps_to_its_exported_variant() {
        assert_eq!(SuggestionSource::Head, source_from_core(CoreSource::Head));
        assert_eq!(
            SuggestionSource::Components,
            source_from_core(CoreSource::Components)
        );
        assert_eq!(
            SuggestionSource::Variant,
            source_from_core(CoreSource::Variant)
        );
        assert_eq!(SuggestionSource::Lm, source_from_core(CoreSource::Lm));
    }

    /// The Kotlin facade's consts are documented mirrors (UniFFI cannot export
    /// consts). Pin the upstream values so a change there cannot silently
    /// desync `OovSuggestions.MIN_IDF_FRACTION` / `MAX_COMPONENT_CANDIDATES` /
    /// `MAX_VARIANT_CANDIDATES` in Kotlin.
    #[test]
    /// The exported const accessors match the crate's consts, so the Kotlin
    /// facade's values cannot drift from the caps the policy enforces.
    #[test]
    fn exported_consts_match_upstream() {
        assert_eq!(
            oov_suggestions_max_component_candidates(),
            MAX_COMPONENT_CANDIDATES as i64
        );
        assert_eq!(
            oov_suggestions_max_variant_candidates(),
            MAX_VARIANT_CANDIDATES as i64
        );
    }
}
