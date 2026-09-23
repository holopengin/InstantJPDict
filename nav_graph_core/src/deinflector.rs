//! UniFFI shim over `jpdict_core::util::deinflector` (WP-04) — the PC
//! deinflection engine, so the Kotlin fork in `util/Deinflector.kt` is deleted
//! and the algorithm has one source of truth. Nothing here implements an
//! algorithm; the file is record/argument conversion only.
//!
//! Rules cross as JSON **text** (`deinflector_from_json_str`), never a path: an
//! APK asset is not a filesystem path, so the Kotlin facade reads the asset and
//! passes the string. A parse failure comes back as `None`; the facade then
//! falls back to an empty deinflector — the pre-swap swallow-to-empty
//! behaviour. The PC rule struct does not carry the JSON's `rulesIn` field
//! (nothing reads it) and serde ignores unknown fields, so the shipped
//! `deinflect.json`, whose payloads all include `rulesIn`, parses unchanged.

use std::sync::Arc;

/// A deinflection candidate: the base-form term plus the chain that produced
/// it.
#[derive(Clone, Debug, uniffi::Record)]
pub struct DeinflectionResult {
    /// The deinflected term (the identity result carries the input text).
    pub term: String,
    /// Human-readable reason labels, outermost step first (e.g. `["past"]`).
    pub reasons: Vec<String>,
    /// Grammatical types of the last rule applied (e.g. `["v1"]`); empty on
    /// the identity result.
    pub rule_types: Vec<String>,
}

/// Deinflects Japanese text by applying known conjugation rules.
///
/// Wraps `jpdict_core::util::deinflector::Deinflector`. Built empty, or from
/// the host's `deinflect.json` text via [`deinflector_from_json_str`].
#[derive(uniffi::Object)]
pub struct Deinflector {
    inner: jpdict_core::util::deinflector::Deinflector,
}

#[uniffi::export]
impl Deinflector {
    /// An engine with no rules: every [`Deinflector::deinflect`] call returns
    /// just the identity result.
    #[uniffi::constructor]
    pub fn empty() -> Arc<Self> {
        Arc::new(Deinflector {
            inner: jpdict_core::util::deinflector::Deinflector::empty(),
        })
    }

    /// Deinflect `text`, returning all reachable base forms. The first result
    /// is always the input text with no reasons.
    pub fn deinflect(&self, text: String) -> Vec<DeinflectionResult> {
        self.inner
            .deinflect(&text)
            .into_iter()
            .map(|r| DeinflectionResult {
                term: r.term,
                reasons: r.reasons,
                rule_types: r.rule_types,
            })
            .collect()
    }

    /// Number of loaded rules (0 for [`Deinflector::empty`]).
    pub fn rule_count(&self) -> i64 {
        self.inner.rule_count() as i64
    }
}

/// Parse deinflection rules from JSON text. `None` when the text is not a
/// valid rule file; the Kotlin facade then keeps its empty fallback.
///
/// A free function, not a constructor: uniffi_bindgen requires constructor
/// return types to be `Self`/`Arc<Self>` (no `Option`) and rejects associated
/// functions, the same constraint `char_lm_from_bytes` documents.
#[uniffi::export]
pub fn deinflector_from_json_str(text: String) -> Option<Arc<Deinflector>> {
    jpdict_core::util::deinflector::Deinflector::from_json_str(&text)
        .ok()
        .map(|inner| Arc::new(Deinflector { inner }))
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::util::deinflector` unit tests, run
    //! against the exact dependency this crate delegates to — through the
    //! exported surface, so the shim's parse and record conversion are covered
    //! too. The JVM suite (`DeinflectionChainTest` plus the `deinflection-01..04`
    //! conformance cases) pins the same behaviour across the UniFFI boundary.

    use super::*;

    /// The Kotlin `DeinflectionChainTest` fixture: group-keyed JSON whose
    /// payloads carry `rulesIn`, which the PC rule struct does not deserialize
    /// (serde ignores unknown fields). This is the parse split's host-side
    /// shape — a `String`, not a file path.
    const INLINE_RULES: &str = r#"{
      "past": [{"kanaIn": "た", "kanaOut": "る", "rulesIn": [], "rulesOut": ["v1"]}],
      "causative": [{"kanaIn": "させる", "kanaOut": "る", "rulesIn": [], "rulesOut": ["v1"]}]
    }"#;

    /// The shipped rules text, read from the Android repo the way the Kotlin
    /// facade reads the asset (`../app/src/main/assets/deinflect.json`, byte
    /// identical with the PC `accessibility_daemon/assets/deinflect.json`).
    /// Skipped when the asset is absent (e.g. a checkout without it).
    fn shipped_rules() -> Option<Arc<Deinflector>> {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../app/src/main/assets/deinflect.json");
        match std::fs::read_to_string(&path) {
            Ok(text) => Some(deinflector_from_json_str(text).expect("shipped rules parse")),
            Err(_) => {
                eprintln!("skipping: {} not present", path.display());
                None
            }
        }
    }

    fn find(results: Vec<DeinflectionResult>, term: &str) -> DeinflectionResult {
        results
            .into_iter()
            .find(|r| r.term == term)
            .unwrap_or_else(|| panic!("no deinflection candidate {term}"))
    }

    #[test]
    fn empty_has_no_rules_and_deinflects_to_the_identity_only() {
        let deinflector = Deinflector::empty();
        assert_eq!(deinflector.rule_count(), 0);
        let results = deinflector.deinflect("食べた".to_string());
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].term, "食べた");
        assert!(results[0].reasons.is_empty());
        assert!(results[0].rule_types.is_empty());
    }

    /// The parse split, host side: a plain JSON string (Kotlin's inline test
    /// fixture) parses, including payloads carrying `rulesIn`, and the group
    /// key becomes the reason — the two chain assertions from
    /// `DeinflectionChainTest` (`reasons_carryRuleNames`,
    /// `reasons_accumulateOutermostFirst_multiStep`).
    #[test]
    fn from_json_str_parses_the_kotlin_fixture_and_chains_reasons() {
        let deinflector =
            deinflector_from_json_str(INLINE_RULES.to_string()).expect("inline rules parse");
        assert_eq!(deinflector.rule_count(), 2);

        let hit = find(deinflector.deinflect("たべた".to_string()), "たべる");
        assert_eq!(hit.reasons, vec!["past"]);
        assert_eq!(hit.rule_types, vec!["v1"]);

        let hit = find(deinflector.deinflect("たべさせた".to_string()), "たべる");
        assert_eq!(hit.reasons, vec!["past", "causative"]);
    }

    /// Mobile parity (`DeinflectionChainTest.reasons_carryRuleNames`): reasons
    /// are readable group labels, not kana fragments.
    #[test]
    fn reasons_carry_rule_names() {
        let Some(deinflector) = shipped_rules() else {
            return;
        };
        let hit = find(deinflector.deinflect("食べた".to_string()), "食べる");
        assert_eq!(hit.reasons, vec!["past"]);
    }

    /// Mobile parity (`DeinflectionChainTest.identityResult_hasNoReasons`):
    /// the identity result carries no reasons, so direct matches get no chain.
    #[test]
    fn identity_result_has_no_reasons() {
        let Some(deinflector) = shipped_rules() else {
            return;
        };
        let identity = find(deinflector.deinflect("食べた".to_string()), "食べた");
        assert!(identity.reasons.is_empty());
    }

    /// The parse split (`from_json_str` extracted from `from_json_file`) as the
    /// shim can observe it: the shim exports no file loader — Kotlin reads the
    /// asset and passes the text — so the file half is represented by the
    /// shipped asset on disk. Every shipped rule survives the object-keyed
    /// parse (569 rules in 36 groups, per the `yomichan-deinflect.txt` notice)
    /// and group keys from two different groups both become reasons.
    #[test]
    fn shipped_asset_text_loads_every_rule() {
        let Some(deinflector) = shipped_rules() else {
            return;
        };
        assert_eq!(deinflector.rule_count(), 569);
        let hit = find(deinflector.deinflect("食べさせた".to_string()), "食べる");
        assert_eq!(hit.reasons, vec!["past", "causative"]);
        assert_eq!(hit.rule_types, vec!["v1"]);
    }

    /// The facade's `?: Deinflector.empty()` fallback: malformed text is
    /// `None`, while both JSON shapes the loader accepts parse — `{}` is an
    /// empty rule set, and a bare array (which the old Gson loader could not
    /// read at all) carries no group keys, so no reason accumulates.
    #[test]
    fn malformed_text_is_none_and_both_json_shapes_parse() {
        assert!(deinflector_from_json_str(String::new()).is_none());
        assert!(deinflector_from_json_str("not json".to_string()).is_none());
        assert!(deinflector_from_json_str("{".to_string()).is_none());

        let object = deinflector_from_json_str("{}".to_string()).expect("empty object");
        assert_eq!(object.rule_count(), 0);

        let array = deinflector_from_json_str(
            r#"[{"kanaIn": "た", "kanaOut": "る", "rulesIn": [], "rulesOut": ["v1"]}]"#
                .to_string(),
        )
        .expect("array form");
        assert_eq!(array.rule_count(), 1);
        let hit = find(array.deinflect("たべた".to_string()), "たべる");
        assert_eq!(hit.rule_types, vec!["v1"]);
        assert!(hit.reasons.is_empty(), "bare arrays have no group keys");
    }
}
