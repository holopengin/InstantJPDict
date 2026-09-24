//! UniFFI shim over `jpdict_core::util::gap_candidates` (#44, WP-08 of the
//! util-module conversion wave; see `char_lm.rs` for the standing boundary
//! conventions).
//!
//! Shape evidence proposes, the text prior disposes: the recogniser's
//! per-timestep top-K crosses as `GapAlternative` records, the line context as
//! code points, and the [`CharLm`] object from WP-01 is borrowed
//! crate-internally so `generate`/`fallback` reorder through the PC model.
//! Nothing here implements a rule; the pool filter, the punctuation-then-kana
//! class order and the back-off cap live upstream.
//!
//! Boundary losses: `char` crosses as its `i32` code point (Kotlin
//! `Char.code`), and an invalid code point — a lone surrogate, a negative —
//! degrades to U+FFFD here, where the old Kotlin carried the raw `Char` into
//! the pool when no model was loaded. `usize` limits cross as `i64` (a
//! negative limit clamps to empty rather than wrapping). The Kotlin `index` is
//! a UTF-16 offset while PC indexes by code point: identical for BMP text
//! (everything the recogniser emits), divergent only when supplementary-plane
//! characters precede the gap. UniFFI cannot export consts, so `MAX` stays a
//! Kotlin `const val` and the two default lists cross through accessor
//! functions instead.
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (`uniffi.nav_graph_core.gapGenerate`,
//! `gapFallback`, `gapContextBefore`, `gapIsOfferable`, `gapPunctDefaults`,
//! `gapKanaDefaults`, record `GapAlternative`) is wrapped by the hand-written
//! `com.holopengin.instantjpdict.util.GapCandidates` object, which keeps the
//! pre-conversion API byte-for-byte (`generate(String, List<List<Pair<Char,
//! Float>>>, Int, CharLm?, Int = MAX)`, `fallback`, `contextBefore`,
//! `isOfferable`, `MAX`, `PUNCT_DEFAULTS`, `KANA_DEFAULTS`) so no call site or
//! test changes. The facade absorbs all `Char` ↔ `Int` conversion.

use std::sync::Arc;

use crate::char_lm::CharLm;

/// Decode a Kotlin `Int` code point back into a Rust `char`.
///
/// The Kotlin side always sends `Char.code` values; anything else (a negative,
/// a surrogate) degrades to U+FFFD rather than panicking at the FFI boundary,
/// matching `char_lm.rs`.
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

/// Render Rust `char`s as the `i32` code points the boundary carries.
fn codepoints_from_chars(chars: &[char]) -> Vec<i32> {
    chars.iter().map(|&c| c as i32).collect()
}

/// Kotlin's `List.take(limit)`; a negative limit clamps to empty rather than
/// wrapping to a huge `usize`.
fn limit_from_kotlin(limit: i64) -> usize {
    usize::try_from(limit).unwrap_or(0)
}

/// Kotlin's `index.coerceIn(0, text.length)`: negatives clamp to the start,
/// anything past the end clamps to the end (PC's `min(len)`).
fn index_from_kotlin(index: i64) -> usize {
    if index < 0 {
        0
    } else {
        usize::try_from(index).unwrap_or(usize::MAX)
    }
}

/// One recogniser alternative: the character and the CTC score that proposed
/// it, field-for-field the `(char, f32)` pair PC's `generate` takes.
///
/// The algorithm reads only `ch` — scores never order the pool — but they
/// cross so the Kotlin facade can keep its `List<Pair<Char, Float>>` API.
#[derive(Clone, Debug, uniffi::Record)]
pub struct GapAlternative {
    /// The proposed character's code point (Kotlin `Char.code`).
    pub ch: i32,
    /// The recogniser's CTC score for the proposal (carried, not consumed).
    pub score: f32,
}

/// Candidates for the blank, best first, from the line's per-timestep top-K.
///
/// Duplicates collapse; the LM reorders the pool by `context` when one is
/// loaded, else the pool keeps its discovery order. Capped at `limit`.
///
/// Delegates to `jpdict_core::util::gap_candidates::generate`; the Kotlin
/// facade wraps this back into `GapCandidates.generate`.
#[uniffi::export]
pub fn gap_generate(
    alternatives: Vec<Vec<GapAlternative>>,
    limit: i64,
    lm: Option<Arc<CharLm>>,
    context: Vec<i32>,
) -> Vec<i32> {
    let alternatives: Vec<Vec<(char, f32)>> = alternatives
        .iter()
        .map(|step| {
            step.iter()
                .map(|alt| (char_from_codepoint(alt.ch), alt.score))
                .collect()
        })
        .collect();
    codepoints_from_chars(&jpdict_core::util::gap_candidates::generate(
        &alternatives,
        limit_from_kotlin(limit),
        lm.as_ref().map(|lm| lm.inner()),
        &chars_from_codepoints(&context),
    ))
}

/// The fallback list, punctuation first, never empty: the model orders
/// *within* a class and the classes stay in this order.
///
/// Delegates to `jpdict_core::util::gap_candidates::fallback`; the Kotlin
/// facade wraps this back into `GapCandidates.fallback`.
#[uniffi::export]
pub fn gap_fallback(limit: i64, lm: Option<Arc<CharLm>>, context: Vec<i32>) -> Vec<i32> {
    codepoints_from_chars(&jpdict_core::util::gap_candidates::fallback(
        limit_from_kotlin(limit),
        lm.as_ref().map(|lm| lm.inner()),
        &chars_from_codepoints(&context),
    ))
}

/// The characters before the gap, the context the back-off chain can use: the
/// model is order 4, so anything longer is ignored and the placeholder itself
/// is dropped rather than read as a real character.
///
/// Delegates to `jpdict_core::util::gap_candidates::context_before`; the
/// Kotlin facade wraps this back into `GapCandidates.contextBefore`.
#[uniffi::export]
pub fn gap_context_before(text: String, index: i64) -> Vec<i32> {
    codepoints_from_chars(&jpdict_core::util::gap_candidates::context_before(
        &text,
        index_from_kotlin(index),
    ))
}

/// Whether a character the recogniser proposed is worth offering. Kanji, kana
/// and punctuation alike: the gap in vertical Japanese text is very often a
/// 読点 or a bracket, so a kanji-only pool comes back empty exactly where the
/// evidence was there.
#[uniffi::export]
pub fn gap_is_offerable(ch: i32) -> bool {
    jpdict_core::util::gap_candidates::is_offerable(char_from_codepoint(ch))
}

/// Punctuation a gap most often holds, in the fixed class order; see
/// [`gap_fallback`]. Rust-sourced because UniFFI cannot export consts.
#[uniffi::export]
pub fn gap_punct_defaults() -> Vec<i32> {
    codepoints_from_chars(&jpdict_core::util::gap_candidates::PUNCT_DEFAULTS)
}

/// The kana a gap most often holds when it is not punctuation, in the fixed
/// class order. Rust-sourced because UniFFI cannot export consts.
#[uniffi::export]
pub fn gap_kana_defaults() -> Vec<i32> {
    codepoints_from_chars(&jpdict_core::util::gap_candidates::KANA_DEFAULTS)
}

/// The candidate cap (mobile `GapCandidates.MAX`), Rust-sourced because UniFFI
/// cannot export consts.
#[uniffi::export]
pub fn gap_candidates_max() -> i64 {
    jpdict_core::util::gap_candidates::MAX as i64
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::util::gap_candidates` unit tests, run
    //! against the exact dependency this crate delegates to — through the
    //! exported surface, not the upstream module, so the shim's record/argument
    //! conversion is covered too. The JVM suite (`GapCandidatesTest`, plus the
    //! `gap-01..04` conformance cases through `ConformanceCorpusTest`) pins the
    //! same behaviour across the UniFFI boundary.

    use super::*;
    use crate::char_lm::char_lm_from_bytes;
    use jpdict_core::util::char_lm::MAX_ORDER;

    /// Mirrors the Kotlin `GapCandidates.MAX` const (UniFFI cannot export
    /// consts, so this is the shim-side pin of the same literal).
    const MAX: i64 = 15;

    /// A toy table packed exactly like `tools/pack_char_lm.py` writes it,
    /// identical to the PC test's table.
    fn toy_lm() -> Arc<CharLm> {
        let entries: &[(&str, u16)] = &[
            ("私", 100),
            ("の", 200),
            ("を", 50),
            ("は", 30),
            ("、", 80),
            ("。", 70),
            ("私の", 40),
            ("私を", 30),
            ("のは", 60),
            ("今日", 90),
            ("日は", 50),
        ];
        let mut records: Vec<([u16; MAX_ORDER], u16)> = entries
            .iter()
            .map(|(ngram, count)| {
                let mut units = [0u16; MAX_ORDER];
                for (i, ch) in ngram.chars().enumerate() {
                    units[i] = ch as u16;
                }
                (units, *count)
            })
            .collect();
        records.sort_by(|a, b| a.0.cmp(&b.0));
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&0x314D_4C43u32.to_le_bytes());
        bytes.extend_from_slice(&(records.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&(MAX_ORDER as u32).to_le_bytes());
        bytes.extend_from_slice(&500u32.to_le_bytes());
        for (units, count) in records {
            for unit in units {
                bytes.extend_from_slice(&unit.to_le_bytes());
            }
            bytes.extend_from_slice(&count.to_le_bytes());
        }
        char_lm_from_bytes(bytes).expect("toy table")
    }

    /// Code points of `text` — the shape the exported surface returns.
    fn cps(text: &str) -> Vec<i32> {
        text.chars().map(|c| c as i32).collect()
    }

    /// Recogniser output in the shape the exported surface takes: one
    /// `Vec<GapAlternative>` per timestep.
    fn alts(steps: &[&[(char, f32)]]) -> Vec<Vec<GapAlternative>> {
        steps
            .iter()
            .map(|step| {
                step.iter()
                    .map(|&(ch, score)| GapAlternative {
                        ch: ch as i32,
                        score,
                    })
                    .collect()
            })
            .collect()
    }

    /// The pool spans every timestep in first-seen order; the CTC blank (the
    /// ideographic space) and the placeholder are dropped, duplicates collapse.
    #[test]
    fn the_pool_comes_from_every_timestep_in_discovery_order() {
        let raw = alts(&[
            &[('、', 0.9), ('の', 0.5)],
            &[('\u{3000}', 0.9), ('の', 0.8), ('私', 0.3)],
            &[('\u{25CC}', 0.1), ('。', 0.2)],
        ]);
        assert_eq!(gap_generate(raw, MAX, None, vec![]), cps("、の私。"));
    }

    /// With a model, the same pool is reordered by the line context: after 私
    /// the model prefers の over を even though を came first from the
    /// recogniser.
    #[test]
    fn the_model_reorders_the_pool_by_context() {
        let lm = toy_lm();
        let raw = alts(&[&[('を', 0.9)], &[('の', 0.8), ('私', 0.1)]]);
        assert_eq!(gap_generate(raw.clone(), MAX, None, vec![]), cps("をの私"));
        assert_eq!(
            gap_generate(raw, MAX, Some(lm), cps("私")),
            cps("のを私"),
            "the context prior leads"
        );
    }

    /// An empty pool is the one case the fallback exists for: punctuation
    /// leads, kana follows, and the model only orders within a class.
    #[test]
    fn an_empty_pool_falls_back_to_punctuation_then_kana() {
        assert!(gap_generate(vec![], MAX, None, vec![]).is_empty());
        let punct = gap_punct_defaults();
        let kana = gap_kana_defaults();
        let fb = gap_fallback(MAX, None, vec![]);
        assert_eq!(fb[0], '、' as i32);
        let kana_at = fb
            .iter()
            .position(|c| kana.contains(c))
            .expect("kana in fallback");
        let last_punct = fb
            .iter()
            .rposition(|c| punct.contains(c))
            .expect("punctuation in fallback");
        assert!(last_punct < kana_at, "punctuation precedes kana: {fb:?}");

        // With a model the classes stay in order; only their members move.
        let lm = toy_lm();
        let fb = gap_fallback(MAX, Some(lm), cps("私"));
        assert!(punct.contains(&fb[0]), "punctuation still leads");
        let last_punct = fb
            .iter()
            .rposition(|c| punct.contains(c))
            .expect("punctuation in fallback");
        let kana_at = fb.iter().position(|c| kana.contains(c)).expect("kana");
        assert!(last_punct < kana_at, "punctuation precedes kana: {fb:?}");
    }

    /// The context is the characters before the gap, capped at the model's
    /// order; a placeholder directly before the gap blocks the back-off
    /// (it is never read as a real character).
    #[test]
    fn the_context_drops_the_placeholder_and_older_characters() {
        assert_eq!(
            gap_context_before("あいうえお".to_string(), 5),
            cps("うえお")
        );
        assert_eq!(gap_context_before("あいうえお".to_string(), 2), cps("あい"));
        assert_eq!(
            gap_context_before("あ\u{25CC}う".to_string(), 2),
            Vec::<i32>::new()
        );
        assert_eq!(gap_context_before(String::new(), 0), Vec::<i32>::new());
    }

    #[test]
    fn offerable_rejects_the_placeholder_the_blank_and_controls() {
        assert!(
            !gap_is_offerable('\u{25CC}' as i32),
            "the placeholder itself"
        );
        assert!(!gap_is_offerable('\u{3000}' as i32), "the CTC blank");
        assert!(!gap_is_offerable('\n' as i32));
        assert!(gap_is_offerable('、' as i32));
        assert!(gap_is_offerable('私' as i32));
        assert!(gap_is_offerable('a' as i32));
    }

    /// The two default lists are Rust-sourced (UniFFI cannot export consts);
    /// the literals pin parity with the pre-conversion Kotlin `val`s.
    #[test]
    fn defaults_are_rust_sourced_and_match_the_kotlin_literals() {
        assert_eq!(gap_punct_defaults(), cps("、。「」…ー"));
        assert_eq!(gap_kana_defaults(), cps("はのをにと"));
    }

    /// The boundary layer the PC tests do not reach: `GapAlternative` records
    /// decode to `(char, f32)` pairs (the score is carried but never orders),
    /// duplicates and empty steps collapse, the limit truncates after
    /// ordering, Kotlin's `coerceIn` index clamp is reproduced, and invalid
    /// code points degrade to U+FFFD as documented.
    #[test]
    fn record_conversion_carries_scores_and_degrades_invalid_code_points() {
        // The same characters with inverted scores give the same pool:
        // discovery order decides, the recogniser's scores never do.
        let high_first = alts(&[&[('思', 0.9), ('阿', 0.1)]]);
        let low_first = alts(&[&[('思', 0.1), ('阿', 0.9)]]);
        assert_eq!(gap_generate(high_first, MAX, None, vec![]), cps("思阿"));
        assert_eq!(gap_generate(low_first, MAX, None, vec![]), cps("思阿"));

        // Empty steps contribute nothing; duplicates collapse first-seen.
        let raw = alts(&[&[], &[('の', 0.5)], &[], &[('の', 0.4), ('は', 0.3)]]);
        assert_eq!(gap_generate(raw, MAX, None, vec![]), cps("のは"));

        // The limit truncates after ordering, and a negative limit clamps to
        // empty rather than wrapping to a huge `usize`.
        let raw = alts(&[&[('、', 0.9), ('の', 0.5), ('私', 0.3)]]);
        assert_eq!(gap_generate(raw.clone(), 2, None, vec![]), cps("、の"));
        assert_eq!(gap_generate(raw, -1, None, vec![]), Vec::<i32>::new());

        // A lone surrogate is not a Rust `char`: it degrades to U+FFFD, which
        // the pool filter keeps (neither blank, control nor placeholder).
        let bad = vec![vec![GapAlternative {
            ch: 0xD800,
            score: 0.5,
        }]];
        assert_eq!(gap_generate(bad, MAX, None, vec![]), cps("\u{FFFD}"));

        // Kotlin's `index.coerceIn(0, text.length)`: negatives are the start of
        // the text, anything past the end is the end.
        assert_eq!(
            gap_context_before("あい".to_string(), -1),
            Vec::<i32>::new()
        );
        assert_eq!(gap_context_before("あい".to_string(), 99), cps("あい"));
    }

    /// The exported const mirror matches the crate's const, so the Kotlin
    /// facade's default cap cannot drift.
    #[test]
    fn exported_max_matches_upstream() {
        assert_eq!(
            gap_candidates_max(),
            jpdict_core::util::gap_candidates::MAX as i64
        );
    }
}
