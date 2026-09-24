//! UniFFI shim over `jpdict_core::yomitan_parse`.
//!
//! The Android importer still owns ZIP traversal and Room writes.  This module
//! only crosses the format boundary: a bank document goes in as JSON text and
//! the shared row records come back.  The dictionary id is intentionally not
//! an argument here; the caller stamps the id it allocated on the returned
//! Room entities, just as the old Android readers did.
//!
//! A malformed top-level bank is represented as an empty list at this
//! boundary.  The desktop importer still receives the core parser's `Result`
//! and can report a failed import; the Android path historically skipped
//! unusable bank values while continuing its ZIP walk.

use crate::data_models::{DictionaryEntryRow, DictionaryTagRow};

fn entry_rows<E>(
    result: Result<Vec<jpdict_core::data::models::DictionaryEntry>, E>,
) -> Vec<DictionaryEntryRow> {
    result
        .ok()
        .unwrap_or_default()
        .into_iter()
        .map(DictionaryEntryRow::from)
        .collect()
}

fn tag_rows<E>(
    result: Result<Vec<jpdict_core::data::models::DictionaryTag>, E>,
) -> Vec<DictionaryTagRow> {
    result
        .ok()
        .unwrap_or_default()
        .into_iter()
        .map(DictionaryTagRow::from)
        .collect()
}

/// Parse a Yomitan term bank.  The returned rows use dictionary id `0`; the
/// Android importer replaces it with the id created for the current ZIP.
#[uniffi::export]
pub fn yomitan_parse_term_bank(json: String) -> Vec<DictionaryEntryRow> {
    entry_rows(jpdict_core::yomitan_parse::parse_term_bank(&json, 0))
}

/// Parse a Yomitan kanji bank.  The returned rows use dictionary id `0`; the
/// Android importer replaces it with the id created for the current ZIP.
#[uniffi::export]
pub fn yomitan_parse_kanji_bank(json: String) -> Vec<DictionaryEntryRow> {
    entry_rows(jpdict_core::yomitan_parse::parse_kanji_bank(&json, 0))
}

/// Parse a Yomitan tag bank.  The returned rows use dictionary id `0`; the
/// Android importer replaces it with the id created for the current ZIP.
#[uniffi::export]
pub fn yomitan_parse_tag_bank(json: String) -> Vec<DictionaryTagRow> {
    tag_rows(jpdict_core::yomitan_parse::parse_tag_bank(&json, 0))
}

/// Parse the pitch-bearing rows of a Yomitan term-meta bank.  The returned
/// rows use dictionary id `0`; the Android importer replaces it with the id
/// created for the current ZIP.
#[uniffi::export]
pub fn yomitan_parse_term_meta_bank(json: String) -> Vec<DictionaryEntryRow> {
    entry_rows(jpdict_core::yomitan_parse::parse_term_meta_bank(&json, 0))
}

/// Read the declared title from an `index.json` document.  `None` means the
/// document is malformed or has no string `title` field.
#[uniffi::export]
pub fn yomitan_parse_index_title(json: String) -> Option<String> {
    jpdict_core::yomitan_parse::parse_index_title(&json)
}

/// Numeric suffix of a Yomitan bank filename, using the shared parser's
/// classification rules.  Kotlin receives `null` for an invalid suffix.
#[uniffi::export]
pub fn yomitan_bank_number(filename: String) -> Option<i64> {
    jpdict_core::yomitan_parse::bank_number(&filename).and_then(|number| i64::try_from(number).ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn term_and_kanji_rows_cross_as_shared_records() {
        let term = yomitan_parse_term_bank(
            r#"[["支持杭","しじぐい","", "v1", 7, ["bearing pile"], 3, "common"]]"#.to_string(),
        );
        assert_eq!(term.len(), 1);
        assert_eq!(term[0].kanji, "支持杭");
        assert_eq!(term[0].reading, "しじぐい");
        assert_eq!(term[0].rules, " | v1 | common");
        assert_eq!(term[0].dictionary_id, 0);

        let kanji = yomitan_parse_kanji_bank(
            r#"[["漢",["カン"],["た."],"8",["kanji"],{"jlpt":"N5"}]]"#.to_string(),
        );
        assert_eq!(kanji.len(), 1);
        assert_eq!(kanji[0].kanji, "漢");
        assert_eq!(kanji[0].onyomi.as_deref(), Some("カン"));
        assert_eq!(kanji[0].kunyomi.as_deref(), Some("た."));
        assert_eq!(kanji[0].jlpt.as_deref(), Some("N5"));
    }

    #[test]
    fn tag_and_term_meta_rows_cross_as_shared_records() {
        let tags = yomitan_parse_tag_bank(r#"[["common","partOfSpeech",4,"keep",12]]"#.to_string());
        assert_eq!(tags.len(), 1);
        assert_eq!(tags[0].name, "common");
        assert_eq!(tags[0].category, "partOfSpeech");
        assert_eq!(tags[0].order, 4);
        assert_eq!(tags[0].notes, "keep");
        assert_eq!(tags[0].popularity, 12);

        let meta = yomitan_parse_term_meta_bank(
            r#"[["分","pitch",{"reading":"ぶん","pitches":[{"position":1}]}],
                ["分","freq",{"value":100}]]"#
                .to_string(),
        );
        assert_eq!(meta.len(), 1);
        assert_eq!(meta[0].kanji, "分");
        assert_eq!(meta[0].reading, "ぶん");
        assert_eq!(
            meta[0].definitions,
            r#"{"reading":"ぶん","pitches":[{"position":1}]}"#
        );
    }

    #[test]
    fn malformed_documents_are_empty_and_index_title_is_best_effort() {
        assert!(yomitan_parse_term_bank("not json".to_string()).is_empty());
        assert!(yomitan_parse_kanji_bank("not json".to_string()).is_empty());
        assert!(yomitan_parse_tag_bank("not json".to_string()).is_empty());
        assert!(yomitan_parse_term_meta_bank("not json".to_string()).is_empty());
        assert_eq!(
            yomitan_parse_index_title(r#"{"title":"Jitendex.org"}"#.to_string()),
            Some("Jitendex.org".to_string())
        );
        assert_eq!(
            yomitan_parse_index_title(r#"{"title":7}"#.to_string()),
            None
        );
    }

    #[test]
    fn bank_number_crosses_as_nullable_i64() {
        assert_eq!(
            yomitan_bank_number("term_bank_12.json".to_string()),
            Some(12)
        );
        assert_eq!(
            yomitan_bank_number("term_meta_bank_3.json".to_string()),
            Some(3)
        );
        assert_eq!(yomitan_bank_number("term_bank_bad.json".to_string()), None);
        assert_eq!(yomitan_bank_number("term_bank_1.txt".to_string()), None);
    }
}
