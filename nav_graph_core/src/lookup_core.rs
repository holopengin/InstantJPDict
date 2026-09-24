//! UniFFI boundary for the shared pure lookup pipeline.
//!
//! Query preparation and row grouping live in `jpdict_core::lookup_core`.
//! This file only converts between its owned Rust records and the UniFFI
//! shapes. The database query, redirect traversal, suspend orchestration, and
//! UI models remain on their respective platforms.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use crate::data_models::DictionaryEntryRow;
use crate::deinflector::Deinflector;

/// One query candidate. `required_types = None` is a direct surface variant;
/// `Some(empty)` is a deinflected candidate that accepts any rule type.
#[derive(Clone, Debug, uniffi::Record)]
pub struct LookupSearchCandidate {
    pub term: String,
    pub required_types: Option<Vec<String>>,
    pub chain: Option<LookupDeinflectionChain>,
}

/// Candidates for one Unicode-character prefix length.
#[derive(Clone, Debug, uniffi::Record)]
pub struct LookupCandidateGroup {
    pub length: i64,
    pub candidates: Vec<LookupSearchCandidate>,
}

/// Prepared terms and candidates returned to the Kotlin facade.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PreparedLookupCandidates {
    pub terms: Vec<String>,
    pub by_length: Vec<LookupCandidateGroup>,
}

/// Deinflection chain carried by a candidate or term match.
#[derive(Clone, Debug, uniffi::Record)]
pub struct LookupDeinflectionChain {
    pub surface: String,
    pub steps: Vec<String>,
}

/// One grouped term match and its database rows.
#[derive(Clone, Debug, uniffi::Record)]
pub struct LookupTermMatch {
    pub term: String,
    pub entries: Vec<DictionaryEntryRow>,
    pub chain: Option<LookupDeinflectionChain>,
}

/// Grouped rows plus the first matching query length.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ProcessedLookupResults {
    pub matches: Vec<LookupTermMatch>,
    pub max_len: i64,
}

/// Dictionary display-name input. A record replaces the `(i64, String)` tuple
/// because tuples are not UniFFI-record compatible.
#[derive(Clone, Debug, uniffi::Record)]
pub struct LookupDictionaryName {
    pub id: i64,
    pub name: String,
}

fn core_chain(
    chain: Option<&jpdict_core::models::DeinflectionChain>,
) -> Option<LookupDeinflectionChain> {
    chain.map(|chain| LookupDeinflectionChain {
        surface: chain.surface.clone(),
        steps: chain.steps.clone(),
    })
}

fn core_chain_from_record(
    chain: Option<LookupDeinflectionChain>,
) -> Option<jpdict_core::models::DeinflectionChain> {
    chain.map(|chain| jpdict_core::models::DeinflectionChain {
        surface: chain.surface,
        steps: chain.steps,
    })
}

fn prepared_to_record(
    prepared: jpdict_core::lookup_core::PreparedCandidates,
) -> PreparedLookupCandidates {
    PreparedLookupCandidates {
        terms: prepared.terms.into_iter().collect(),
        by_length: prepared
            .by_length
            .into_iter()
            .map(|group| LookupCandidateGroup {
                length: group.length as i64,
                candidates: group
                    .candidates
                    .into_iter()
                    .map(|candidate| LookupSearchCandidate {
                        term: candidate.term,
                        required_types: candidate.required_types,
                        chain: core_chain(candidate.chain.as_ref()),
                    })
                    .collect(),
            })
            .collect(),
    }
}

fn prepared_from_record(
    prepared: PreparedLookupCandidates,
) -> jpdict_core::lookup_core::PreparedCandidates {
    jpdict_core::lookup_core::PreparedCandidates {
        terms: prepared.terms.into_iter().collect::<HashSet<_>>(),
        by_length: prepared
            .by_length
            .into_iter()
            .map(|group| jpdict_core::lookup_core::CandidateGroup {
                length: usize::try_from(group.length).unwrap_or(0),
                candidates: group
                    .candidates
                    .into_iter()
                    .map(|candidate| jpdict_core::lookup_core::SearchCandidate {
                        term: candidate.term,
                        required_types: candidate.required_types,
                        chain: core_chain_from_record(candidate.chain),
                    })
                    .collect(),
            })
            .collect(),
    }
}

fn processed_to_record(
    processed: jpdict_core::lookup_core::ProcessedResults,
) -> ProcessedLookupResults {
    ProcessedLookupResults {
        matches: processed
            .matches
            .into_iter()
            .map(|term_match| LookupTermMatch {
                term: term_match.term,
                entries: term_match
                    .entries
                    .into_iter()
                    .map(DictionaryEntryRow::from)
                    .collect(),
                chain: core_chain(term_match.chain.as_ref()),
            })
            .collect(),
        max_len: processed.max_len as i64,
    }
}

fn matches_from_record(matches: Vec<LookupTermMatch>) -> Vec<jpdict_core::models::TermMatch> {
    matches
        .into_iter()
        .map(|term_match| jpdict_core::models::TermMatch {
            term: term_match.term,
            entries: term_match
                .entries
                .into_iter()
                .map(jpdict_core::data::models::DictionaryEntry::from)
                .collect(),
            chain: core_chain_from_record(term_match.chain),
        })
        .collect()
}

/// Return the next 20 characters from `global_idx`, omitting U+3000 cells.
/// An out-of-range host index degrades to an empty string instead of crossing
/// a Rust panic.
#[uniffi::export]
pub fn lookup_following_text(active_chars: Vec<String>, global_idx: i64) -> String {
    let Ok(global_idx) = usize::try_from(global_idx) else {
        return String::new();
    };
    if global_idx >= active_chars.len() {
        return String::new();
    }
    jpdict_core::lookup_core::following_text(&active_chars, global_idx)
}

/// Prepare lookup candidates from the host's exported deinflector object.
///
/// The Kotlin facade passes its `Deinflector` handle straight through, so
/// deinflection happens in-process rather than crossing back per prefix.
#[uniffi::export]
pub fn lookup_prepare_candidates(
    text: String,
    deinflector: Arc<Deinflector>,
) -> PreparedLookupCandidates {
    prepared_to_record(jpdict_core::lookup_core::prepare_search_candidates(
        &text, deinflector.core_inner(),
    ))
}

/// Group database rows under prepared candidates.
#[uniffi::export]
pub fn lookup_process_results(
    rows: Vec<DictionaryEntryRow>,
    prepared: PreparedLookupCandidates,
    following_text: String,
) -> ProcessedLookupResults {
    let rows = rows
        .into_iter()
        .map(jpdict_core::data::models::DictionaryEntry::from)
        .collect::<Vec<_>>();
    let processed = jpdict_core::lookup_core::process_results(
        &rows,
        &prepared_from_record(prepared),
        &following_text,
    );
    processed_to_record(processed)
}

/// Format grouped rows as the compact JSON projection described by
/// `jpdict_core::lookup_core::format_dictionary_results_json`.
#[uniffi::export]
pub fn lookup_format_results(
    matches: Vec<LookupTermMatch>,
    dict_names: Vec<LookupDictionaryName>,
) -> String {
    let matches = matches_from_record(matches);
    let dict_names = dict_names
        .into_iter()
        .map(|entry| (entry.id, entry.name))
        .collect::<HashMap<_, _>>();
    jpdict_core::lookup_core::format_dictionary_results_json(&matches, &dict_names)
}

#[cfg(test)]
mod tests {
    use super::*;

    const INLINE_RULES: &str = r#"{
      "past": [{"kanaIn": "た", "kanaOut": "る", "rulesIn": [], "rulesOut": ["v1"]}],
      "causative": [{"kanaIn": "させる", "kanaOut": "る", "rulesIn": [], "rulesOut": ["v1"]}]
    }"#;

    fn fixture_deinflector() -> Arc<Deinflector> {
        crate::deinflector::deinflector_from_json_str(INLINE_RULES.to_string())
            .expect("inline deinflection rules parse")
    }

    fn row(id: i64, kanji: &str, reading: &str, dictionary_id: i64) -> DictionaryEntryRow {
        DictionaryEntryRow {
            id,
            kanji: kanji.to_string(),
            reading: reading.to_string(),
            definitions: r#"["to eat"]"#.to_string(),
            rules: "v1".to_string(),
            popularity: 0,
            dictionary_id,
            onyomi: None,
            kunyomi: None,
            jlpt: None,
        }
    }

    /// Mirror of the PC `lookup_core` worker-thread regression, through every
    /// exported conversion.
    #[test]
    fn mirror_lookup_stages_run_cross_threaded_and_match_inline() {
        let text = "食べる".to_string();
        let rows = vec![row(1, "食べる", "たべる", 1)];
        let names = vec![LookupDictionaryName {
            id: 1,
            name: "Test".to_string(),
        }];
        let deinflector = fixture_deinflector();

        let prepared = lookup_prepare_candidates(text.clone(), deinflector.clone());
        let inline = lookup_process_results(rows.clone(), prepared.clone(), text.clone());

        let worker = std::thread::spawn(move || {
            let prepared = lookup_prepare_candidates(text, deinflector);
            let processed = lookup_process_results(rows, prepared, "食べる".to_string());
            (
                lookup_format_results(processed.matches, names),
                processed.max_len,
            )
        });
        let (worker_json, worker_max) = worker.join().expect("worker");
        let inline_json = lookup_format_results(inline.matches.clone(), fixture_names());
        assert_eq!(inline_json, worker_json);
        assert_eq!(inline.max_len, worker_max);
        assert!(inline.max_len >= 1, "the fixture term must match");
    }

    fn fixture_names() -> Vec<LookupDictionaryName> {
        vec![LookupDictionaryName {
            id: 1,
            name: "Test".to_string(),
        }]
    }

    #[test]
    fn candidates_keep_direct_and_deinflected_chains_through_the_boundary() {
        let direct_prepared =
            lookup_prepare_candidates("たべた".to_string(), fixture_deinflector());
        assert!(direct_prepared.terms.iter().any(|term| term == "たべる"));

        let prepared =
            lookup_prepare_candidates("たべた".to_string(), fixture_deinflector());
        let terms = prepared.terms.iter().cloned().collect::<HashSet<_>>();
        let group = prepared
            .by_length
            .iter()
            .find(|group| group.length == 3)
            .expect("three-character group");
        let direct = group
            .candidates
            .iter()
            .find(|candidate| candidate.term == "たべた")
            .expect("direct candidate");
        assert!(direct.required_types.is_none());
        assert!(direct.chain.is_none());

        let deinflected = group
            .candidates
            .iter()
            .find(|candidate| candidate.term == "たべる")
            .expect("deinflected candidate");
        assert_eq!(
            deinflected.required_types.as_deref(),
            Some(&["v1".to_string()][..])
        );
        let chain = deinflected.chain.as_ref().expect("deinflection chain");
        assert_eq!(chain.surface, "たべた");
        assert_eq!(chain.steps, vec!["past"]);

        let processed = lookup_process_results(
            vec![row(1, "たべた", "たべた", 1), row(2, "たべる", "たべる", 1)],
            prepared,
            "たべた".to_string(),
        );
        assert!(!terms.is_empty());
        assert_eq!(processed.max_len, 3);
        let direct = processed
            .matches
            .iter()
            .find(|term| term.term == "たべた")
            .expect("direct match");
        assert!(direct.chain.is_none());
        let deinflected = processed
            .matches
            .iter()
            .find(|term| term.term == "たべる")
            .expect("deinflected match");
        assert_eq!(deinflected.chain.as_ref().unwrap().steps, vec!["past"]);
    }

    #[test]
    fn following_text_conversion_strips_placeholders_and_guards_indices() {
        let active = ["食", "\u{3000}", "べ", "る"]
            .iter()
            .map(|character| character.to_string())
            .collect::<Vec<_>>();
        assert_eq!(lookup_following_text(active.clone(), 0), "食べる");
        assert_eq!(lookup_following_text(active, -1), "");
        assert_eq!(lookup_following_text(vec!["食".to_string()], 1), "");
    }

    #[test]
    fn format_boundary_splits_dictionaries_and_preserves_chain() {
        let matches = vec![LookupTermMatch {
            term: "食べる".to_string(),
            entries: vec![row(1, "食べる", "たべる", 1), row(2, "食べる", "たべる", 2)],
            chain: Some(LookupDeinflectionChain {
                surface: "食べた".to_string(),
                steps: vec!["past".to_string()],
            }),
        }];
        let names = vec![
            LookupDictionaryName {
                id: 1,
                name: "JMdict".to_string(),
            },
            LookupDictionaryName {
                id: 2,
                name: "KANJIDIC".to_string(),
            },
        ];
        let json = lookup_format_results(matches, names);
        assert!(json.contains(r#""dictionary_name":"JMdict""#));
        assert!(json.contains(r#""dictionary_name":"KANJIDIC""#));
        assert!(json.contains(r#""surface":"食べた""#));
    }
}
