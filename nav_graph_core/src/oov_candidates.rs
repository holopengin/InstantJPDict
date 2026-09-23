//! UniFFI shim over `jpdict_core::util::oov_candidates` (#44, WP-09 of the
//! util-module conversion wave).
//!
//! The component-level OOV candidate policy crosses as a stateful
//! `uniffi::Object` that **shares** the WP-02 [`ComponentTable`] instead of
//! cloning its 12k-entry indexes: the Kotlin host already holds the table as
//! its own UniFFI object, so [`OovCandidates::new`] wraps the same `Arc` (the
//! PC `from_arc` constructor added for exactly this package). Boundary losses:
//! `char` crosses as its `i32` code point (Kotlin `Char.code`), `&[char]` as
//! `Vec<i32>`, and the [`Candidate`] record carries the character as
//! `char: i32` — the Kotlin facade converts back to `Char`. Nothing here
//! implements an algorithm: the two measured rules (the intersected IDF
//! fraction and the majority vote) live upstream, and ranking stays with the
//! caller.
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (`uniffi.nav_graph_core.OovCandidates` and its
//! `uniffi.nav_graph_core.Candidate` record) is wrapped by the hand-written
//! `com.holopengin.instantjpdict.util.OovCandidates` facade, which keeps the
//! pre-conversion API byte-for-byte (`OovCandidates(ComponentTable)`,
//! `Candidate(char, idfFraction, sharesAllComponents)`, `neighboursOf(Char)`,
//! `hasDiscriminatingComponents(Char)`, `majorityComponents(List<Char>,
//! needFraction = 0.5)`) so no call site or test changes. The facade absorbs
//! all `Char` ↔ `Int` conversion.
//!
//! ## Reuse
//!
//! [`OovCandidates::inner`] is `pub(crate)` so a later shim in this crate
//! (WP-10 `oov_suggestions`) can borrow the policy and hand it to `assemble`.

use std::sync::Arc;

use crate::component_table::ComponentTable;

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

/// Render Rust `char`s as the `i32` code points the boundary carries.
fn codepoints_from_chars(chars: &[char]) -> Vec<i32> {
    chars.iter().map(|&c| c as i32).collect()
}

/// A character that could stand where the emitted character was, with the
/// strength of the visual relation to it.
///
/// The Kotlin facade re-exposes this as
/// `OovCandidates.Candidate(char: Char, idfFraction: Float, sharesAllComponents: Boolean)`.
#[derive(Clone, Debug, uniffi::Record)]
pub struct Candidate {
    /// The candidate character, as its `i32` code point (Kotlin `Char.code`).
    pub char: i32,
    /// Share of the emitted character's component information (IDF mass) that
    /// this candidate also carries, in `[0, 1]`. Intersected, never the
    /// candidate's whole mass.
    pub idf_fraction: f32,
    /// The candidate carries **every** component of the emitted character —
    /// the near-identity relation.
    pub shares_all_components: bool,
}

/// Component-level candidate policy for out-of-vocabulary characters (#44):
/// the substitution neighbours of a character the head emitted, and the
/// majority components of a top-K the head offered at a deleted one.
///
/// Wraps `jpdict_core::util::oov_candidates::OovCandidates`, which holds the
/// shared KRADFILE table; the measured rules (intersected IDF fraction,
/// majority vote with strongest-component fallback) are documented upstream.
#[derive(uniffi::Object)]
pub struct OovCandidates {
    /// The delegated PC policy. `pub(crate)` only so a later shim in this crate
    /// (WP-10 `oov_suggestions`) can borrow it; it never crosses the FFI
    /// boundary itself.
    pub(crate) inner: jpdict_core::util::oov_candidates::OovCandidates,
}

impl OovCandidates {
    /// Borrow the delegated policy (crate-internal; not exported).
    pub(crate) fn inner(&self) -> &jpdict_core::util::oov_candidates::OovCandidates {
        &self.inner
    }
}

#[uniffi::export]
impl OovCandidates {
    /// Wrap the already-parsed component table. The table is **shared**, not
    /// cloned: the Kotlin host owns it as a `ComponentTable` object of its own,
    /// and one parse of the 12k-entry indexes serves every consumer.
    #[uniffi::constructor]
    pub fn new(table: Arc<ComponentTable>) -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::oov_candidates::OovCandidates::from_arc(table.inner_arc()),
        })
    }

    /// Candidate characters for a character the head **emitted** (the
    /// substitution mode), ordered by [`Candidate::idf_fraction`] descending,
    /// ties broken by codepoint. An unknown character, or one whose components
    /// carry no IDF mass, yields an empty list rather than an error.
    pub fn neighbours_of(&self, emitted: i32) -> Vec<Candidate> {
        self.inner
            .neighbours_of(char_from_codepoint(emitted))
            .into_iter()
            .map(|c| Candidate {
                char: c.ch as i32,
                idf_fraction: c.idf_fraction,
                shares_all_components: c.shares_all_components,
            })
            .collect()
    }

    /// Whether component evidence can discriminate at all for this character:
    /// two or more components. A single-component character makes every one of
    /// its carriers a full match, which is noise presented as evidence.
    pub fn has_discriminating_components(&self, emitted: i32) -> bool {
        self.inner.has_discriminating_components(char_from_codepoint(emitted))
    }

    /// The components a top-K of characters **agree on**, by majority vote: a
    /// component carried by at least `need_fraction` of `top_k` (at least one
    /// character). Ordered strongest first — by how many of the top-K carry it,
    /// then by codepoint. An empty `top_k`, or one whose characters are all
    /// unknown, returns an empty list.
    pub fn majority_components(&self, top_k: Vec<i32>, need_fraction: f64) -> Vec<i32> {
        let top_k: Vec<char> = top_k.iter().map(|&cp| char_from_codepoint(cp)).collect();
        codepoints_from_chars(&self.inner.majority_components(&top_k, need_fraction))
    }
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::util::oov_candidates` unit tests, run
    //! against the exact dependency this crate delegates to — through the
    //! exported surface, not the upstream module, so the shim's `char` ↔ `i32`
    //! conversion and its shared-table constructor are covered too. The JVM
    //! suite (`OovCandidatesTest`) pins the same behaviour across the UniFFI
    //! boundary.

    use super::*;

    /// Code points of `text` — the shape the exported surface takes.
    fn cps(text: &str) -> Vec<i32> {
        text.chars().map(|c| c as i32).collect()
    }

    /// The real asset's text, read from the Android repo (mirrors mobile
    /// `OovCandidatesTest`). `None` on a checkout without it.
    fn asset_text() -> Option<String> {
        let path = std::path::Path::new(concat!(env!("CARGO_MANIFEST_DIR")))
            .join("../app/src/main/assets/components/krad_components.txt");
        std::fs::read_to_string(&path).ok()
    }

    /// The asset parsed through the exported `ComponentTable` surface, with the
    /// exported policy built on top of it. `None` when the asset is absent.
    fn real() -> Option<(Arc<ComponentTable>, Arc<OovCandidates>)> {
        let table = ComponentTable::parse(asset_text()?);
        let candidates = OovCandidates::new(Arc::clone(&table));
        Some((table, candidates))
    }

    /// The measured top-5 the recogniser emitted at the deleted `呟`.
    fn measured_top5() -> Vec<i32> {
        cps("咳咬啦哮眩")
    }

    /// The binding-host path: the shim constructor hands the caller's
    /// already-parsed table to the PC `from_arc` instead of cloning it, and
    /// answers exactly like a table of its own.
    #[test]
    fn constructor_shares_the_table_and_answers_identically() {
        let Some(text) = asset_text() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        let table = ComponentTable::parse(text.clone());
        let shared = OovCandidates::new(Arc::clone(&table));
        assert!(
            std::ptr::eq(shared.inner().table(), table.inner_arc().as_ref()),
            "shares, does not clone"
        );
        let owned = OovCandidates::new(ComponentTable::parse(text));
        assert_eq!(
            shared.majority_components(measured_top5(), 0.5),
            owned.majority_components(measured_top5(), 0.5)
        );
        assert_eq!(
            shared.neighbours_of('曇' as i32).len(),
            owned.neighbours_of('曇' as i32).len()
        );
    }

    #[test]
    fn majority_components_of_the_measured_top5_matches_fukan() {
        let Some((table, candidates)) = real() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        // 口 is carried by 4 of 5, 亠 by 3 — both >= ceil(5/2) — and both are
        // components of the dropped 呟 (亠 口 幺 玄).
        assert_eq!(candidates.majority_components(measured_top5(), 0.5), cps("口亠"));
        // The strict intersection really is empty: this is what the vote replaces.
        let mut strict: Option<Vec<i32>> = None;
        for ch in measured_top5() {
            let set = table.components_of(ch);
            strict = Some(match strict {
                None => set,
                Some(acc) => acc.into_iter().filter(|c| set.contains(c)).collect(),
            });
        }
        assert_eq!(strict.unwrap_or_default(), Vec::<i32>::new());
    }

    #[test]
    fn majority_components_falls_back_to_the_strongest_and_handles_empty_input() {
        let Some((_, candidates)) = real() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        // need_fraction 1.0 demands unanimity, which this top-K does not
        // have; the fallback is the single strongest component (口, 4 of 5).
        assert_eq!(candidates.majority_components(measured_top5(), 1.0), cps("口"));
        assert!(candidates.majority_components(vec![], 0.5).is_empty());
        assert!(candidates.majority_components(vec![0xE000, 0xE001], 0.5).is_empty());
    }

    #[test]
    fn neighbours_of_emitted_char_find_the_measured_substitution() {
        let Some((_, candidates)) = real() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        // 曇 (二 厶 日 雨) -> 壜 (二 厶 土 日 雨): the head's component-space
        // neighbour from the Aozora bench, carrying all four of 曇's
        // components — the near-identity relation, with full IDF fraction.
        let neighbours = candidates.neighbours_of('曇' as i32);
        let ban = neighbours
            .iter()
            .find(|c| c.char == '壜' as i32)
            .expect("壜 is a neighbour");
        assert!(ban.shares_all_components);
        assert!((ban.idf_fraction - 1.0).abs() < 1e-6);

        // And the relation is discriminating: 但 (一 化 日) shares only the common 日.
        let ta = neighbours
            .iter()
            .find(|c| c.char == '但' as i32)
            .expect("但 is a neighbour");
        assert!(!ta.shares_all_components);
        assert!((ta.idf_fraction - 0.1798134).abs() < 1e-4, "但 = {}", ta.idf_fraction);

        // Exactly the two kanji carrying every component of 曇, ordered by
        // codepoint when the evidence ties: 壜 < 罎.
        let full: Vec<i32> = neighbours
            .iter()
            .filter(|c| c.shares_all_components)
            .map(|c| c.char)
            .collect();
        assert_eq!(full, cps("壜罎"));
        assert_eq!(neighbours.first().map(|c| c.char), Some('壜' as i32));
    }

    #[test]
    fn idf_fraction_intersects_the_emitted_characters_components() {
        let Some((_, candidates)) = real() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        // 呟 = 亠 口 幺 玄 (13.064 nats); 咳 = ノ 丶 亠 人 口 shares 亠 + 口
        // only: (4.2185 / 13.0642) = 0.32291. The rejected variant divided by
        // *咳's* whole mass instead, giving 0.35765.
        let neighbours = candidates.neighbours_of('呟' as i32);
        let kai = neighbours
            .iter()
            .find(|c| c.char == '咳' as i32)
            .expect("咳 is a neighbour");
        assert!((kai.idf_fraction - 0.3229068).abs() < 1e-4, "咳 = {}", kai.idf_fraction);
        assert!(!kai.shares_all_components, "呟's 幺 and 玄 are missing");
        assert!(
            neighbours.iter().all(|c| c.char != '呟' as i32),
            "never its own candidate"
        );
    }

    #[test]
    fn neighbours_are_sorted_by_evidence_and_never_contain_the_emitted_character() {
        let Some((table, candidates)) = real() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        let neighbours = candidates.neighbours_of('曇' as i32);
        assert!(neighbours.len() > 1000, "the pool is the wide 'shares any component' rule");
        for pair in neighbours.windows(2) {
            assert!(pair[0].idf_fraction >= pair[1].idf_fraction);
        }
        let emitted = table.components_of('曇' as i32);
        for c in &neighbours {
            assert_ne!(c.char, '曇' as i32);
            assert!(c.idf_fraction > 0.0 && c.idf_fraction <= 1.0);
            assert!(
                table.components_of(c.char).iter().any(|x| emitted.contains(x)),
                "{} shares nothing with 曇",
                char_from_codepoint(c.char)
            );
        }
        // The discriminator the caller gates on before walking the pool: 曇 has
        // four components, the single-component 一 does not, and an unknown
        // character has none at all.
        assert!(candidates.has_discriminating_components('曇' as i32));
        assert!(!candidates.has_discriminating_components('一' as i32));
        assert!(!candidates.has_discriminating_components('あ' as i32));
    }

    #[test]
    fn unknown_characters_yield_no_candidates_and_no_majority() {
        let Some((_, candidates)) = real() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        assert!(candidates.neighbours_of(0xE000).is_empty());
        assert!(candidates.neighbours_of('あ' as i32).is_empty());
        assert!(candidates.majority_components(cps("あ"), 0.5).is_empty());
    }
}
