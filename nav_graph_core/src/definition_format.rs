//! UniFFI shim over `jpdict_core::definition_format` (#04 of the
//! sharing follow-ups).
//!
//! The Yomitan/Jitendex definition pipeline — structured-content walker,
//! sense-group splitter, redirect extractor and plain-text walker — has one
//! source of truth in the PC crate now (`definition_format`, ungated). This
//! file is only the boundary: no algorithm, no copied source.
//!
//! ## Why JSON crosses the boundary
//!
//! `DefinitionNode` is recursive (`Group { nodes }`, `Example { content,
//! parts }`, …), which UniFFI records cannot express. The shim therefore
//! returns the formatted result as a JSON string (serde on the core DTOs);
//! the Kotlin facade maps that JSON into its existing `DefinitionNode` model.
//! The mapping is presentation-only — the walker itself runs once, in Rust.
//!
//! Flat outputs are plain records: `definition_format_redirect_targets` →
//! `Vec<String>`, `definition_format_plain` / `definition_format_plain_all`
//! → `String`.
//!
//! ## Boundary conventions
//!
//! * `usize` crosses as `i64`/Kotlin `Long` (copied from `pitch.rs`): a
//!   negative `max_targets` degrades to `0` (no targets) instead of wrapping.
//! * The Kotlin facade serialises its Gson-parsed `Any` back to a JSON string
//!   (`Gson().toJson(data)`) before calling in; the walker input is therefore
//!   always a JSON document. `"null"` (Kotlin `null`) parses to
//!   `Value::Null`, which the walker maps to no nodes — exactly the old
//!   `parseDefinition(null) == emptyList()` behaviour.
//!
//! ## Kotlin facade contract
//!
//! `OcrOverlayStateController.parseDefinition`/`parseGlossary` keep their
//! signatures and map the returned JSON into the existing `DefinitionNode`
//! / `ParsedGlossary` models; `util.DictionaryRedirects.extractTargets` and
//! `util.Definitions.plain`/`plainAll` keep their APIs and delegate. No call
//! site or test changes.

/// Parse a stored definition payload into displayable nodes, returned as
/// JSON (`Vec<DefinitionNodeJson>`).
///
/// `separator` is spliced between adjacent inline siblings (`", "` joins a
/// gloss list, `"\n"` separates a JMdict example's lines, `""`
/// concatenates one sentence's fragments). Delegates to
/// `jpdict_core::definition_format::parse_to_json`.
#[uniffi::export]
pub fn definition_format_parse(glossary_json: String, separator: String) -> String {
    jpdict_core::definition_format::parse_to_json(&glossary_json, &separator)
}

/// Split one database row's glossary for numbering, returned as JSON
/// (`ParsedGlossaryJson`): Jitendex rows come back `structured` with a
/// `groups` entry per `sense-group`; JMdict/KANJIDIC rows come back flat in
/// `plain`. Delegates to
/// `jpdict_core::definition_format::parse_glossary_to_json`.
#[uniffi::export]
pub fn definition_format_parse_glossary(glossary_json: String) -> String {
    jpdict_core::definition_format::parse_glossary_to_json(&glossary_json)
}

/// Headwords a JMdict pointer entry redirects to (empty when the entry
/// carries real definitional content, or the payload is malformed).
/// `max_targets` is Kotlin's `Int` widened to the `i64` the boundary takes;
/// a negative value yields no targets. Delegates to
/// `jpdict_core::definition_format::extract_redirect_targets`.
#[uniffi::export]
pub fn definition_format_redirect_targets(
    definitions_json: String,
    max_targets: i64,
) -> Vec<String> {
    let max = usize::try_from(max_targets).unwrap_or(0);
    jpdict_core::definition_format::extract_redirect_targets(&definitions_json, max)
}

/// Flatten one stored `definitions` blob to plain text (one line per
/// top-level sense). A malformed blob falls back to the raw string. Delegates
/// to `jpdict_core::definition_format::plain_text`.
#[uniffi::export]
pub fn definition_format_plain(definitions_json: String) -> String {
    jpdict_core::definition_format::plain_text(&definitions_json)
}

/// Flatten several definition blobs as one blob of lines (blank lines
/// dropped, first occurrence kept). Delegates to
/// `jpdict_core::definition_format::plain_all_text`.
#[uniffi::export]
pub fn definition_format_plain_all(definitions_json_rows: Vec<String>) -> String {
    jpdict_core::definition_format::plain_all_text(&definitions_json_rows)
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::definition_format` contract, run through
    //! the exported surface (not the upstream module), so the shim's argument
    //! conversion is covered too. The redirect shapes are verbatim from
    //! Android's `DictionaryRedirectsTest`; the plain-text cases from
    //! `BookmarkExportTest`; the walker smoke cases pin the JSON shape the
    //! Kotlin mapper consumes.

    use super::*;

    // Verbatim shapes from Android's `DictionaryRedirectsTest` (#65).
    const PURE_SINGLE: &str = r#"[{"content": {"content": ["⟶", {"content": "あかん", "href": "?query=あかん&wildcards=off", "lang": "ja", "tag": "a"}], "style": {"fontSize": "130%"}, "tag": "span"}, "type": "structured-content"}]"#;
    const PURE_DUAL: &str = r#"[{"content": {"content": ["⟶", {"content": "阿呆陀羅", "href": "?query=阿呆陀羅&wildcards=off", "lang": "ja", "tag": "a"}, "（", {"content": "あほんだら", "href": "?query=あほんだら&wildcards=off", "lang": "ja", "tag": "a"}, "）"], "style": {"fontSize": "130%"}, "tag": "span"}, "type": "structured-content"}]"#;
    const GLOSS_WITH_REFS: &str = r#"[{"content": [{"content": {"content": "repetition mark in katakana", "tag": "li"}, "data": {"content": "glossary"}, "lang": "en", "style": {"listStyleType": "circle"}, "tag": "ul"}, {"content": {"content": ["see: ", {"content": "一の字点", "href": "?query=一の字点&wildcards=off", "lang": "ja", "tag": "a"}, {"content": " kana iteration mark", "data": {"content": "refGlosses"}, "style": {"fontSize": "65%", "verticalAlign": "middle"}, "tag": "span"}], "tag": "li"}, "data": {"content": "references"}, "lang": "en", "style": {"listStyleType": "'➡️ '"}, "tag": "ul"}], "type": "structured-content"}]"#;

    /// The walker output is compact serde JSON with `kind` first (declaration
    /// order); tests match on those substrings rather than parsing, so the
    /// shim needs no serde dependency of its own.
    fn has_kind(json: &str, kind: &str) -> bool {
        json.contains(&format!("\"kind\":\"{kind}\""))
    }

    #[test]
    fn pure_single_redirect_resolves_through_shim() {
        assert_eq!(
            definition_format_redirect_targets(PURE_SINGLE.to_string(), 3),
            vec!["あかん"]
        );
    }

    #[test]
    fn pure_dual_redirect_resolves_both_through_shim() {
        assert_eq!(
            definition_format_redirect_targets(PURE_DUAL.to_string(), 3),
            vec!["阿呆陀羅", "あほんだら"]
        );
    }

    #[test]
    fn gloss_with_see_also_does_not_redirect_through_shim() {
        assert!(definition_format_redirect_targets(GLOSS_WITH_REFS.to_string(), 3).is_empty());
    }

    #[test]
    fn plain_gloss_and_malformed_redirects_yield_nothing_through_shim() {
        assert!(definition_format_redirect_targets("\"ただの定義\"".to_string(), 3).is_empty());
        for json in ["not json{[", "", "[]"] {
            assert!(definition_format_redirect_targets(json.to_string(), 3).is_empty());
        }
    }

    #[test]
    fn redirect_target_cap_and_negative_cap_apply_through_shim() {
        assert_eq!(
            definition_format_redirect_targets(PURE_DUAL.to_string(), 1).len(),
            1
        );
        assert!(definition_format_redirect_targets(PURE_SINGLE.to_string(), -1).is_empty());
    }

    #[test]
    fn plain_text_mirrors_the_bookmark_contract_through_shim() {
        assert_eq!(
            definition_format_plain("[\"to eat\",\"food\"]".to_string()),
            "to eat\nfood"
        );
        assert_eq!(
            definition_format_plain("[[\"to eat\",\"to consume\"],\"food\"]".to_string()),
            "to eat, to consume\nfood"
        );
        assert_eq!(
            definition_format_plain("{\"tag\":\"span\",\"content\":\"a note\"}".to_string()),
            "a note"
        );
        assert_eq!(
            definition_format_plain("{not json".to_string()),
            "{not json"
        );
        assert_eq!(
            definition_format_plain(
                "{\"tag\":\"td\",\"data\":{\"class\":\"form-valid\"},\"content\":{\"tag\":\"span\",\"title\":\"valid form/reading combination\",\"data\":{\"class\":\"form-valid\"}}}".to_string()
            ),
            ""
        );
    }

    #[test]
    fn plain_all_joins_entries_and_drops_duplicates_through_shim() {
        assert_eq!(
            definition_format_plain_all(vec!["[\"one\"]".to_string(), "[\"two\",\"one\"]".to_string()]),
            "one\ntwo"
        );
    }

    #[test]
    fn parse_returns_typed_nodes_as_json_through_shim() {
        let out = definition_format_parse(
            "[\"kept\", {\"tag\":\"ruby\",\"content\":[\"湾\",{\"tag\":\"rt\",\"content\":\"わん\"}]}]".to_string(),
            ", ".to_string(),
        );
        assert!(has_kind(&out, "text"), "text node: {out}");
        assert!(has_kind(&out, "ruby"), "ruby node: {out}");
        assert!(out.contains("\"text\":\"kept\""), "text payload: {out}");
        assert!(out.contains("\"term\":\"湾\""), "ruby term: {out}");
        assert!(out.contains("\"reading\":\"わん\""), "ruby reading: {out}");
    }

    #[test]
    fn parse_citation_table_and_group_shapes_through_shim() {
        let out = definition_format_parse(
            "{\"tag\":\"div\",\"data\":{\"content\":\"attribution\"},\"content\":[{\"tag\":\"a\",\"content\":\"JMdict\"}]}".to_string(),
            ", ".to_string(),
        );
        assert!(has_kind(&out, "citation"), "citation node: {out}");
        assert!(out.contains("\"text\":\"JMdict\""), "citation payload: {out}");

        let out = definition_format_parse("null".to_string(), ", ".to_string());
        assert_eq!(out, "[]");
    }

    #[test]
    fn parse_glossary_splits_a_sense_group_through_shim() {
        let group = "{\"tag\":\"div\",\"data\":{\"content\":\"sense-group\"},\"content\":[\
            {\"tag\":\"span\",\"data\":{\"content\":\"part-of-speech\"},\"content\":\"noun\"},\
            {\"tag\":\"div\",\"data\":{\"content\":\"sense\"},\"content\":[\"a gloss\"]}]}";
        let out = definition_format_parse_glossary(format!("[{group}]"));
        assert!(out.contains("\"structured\":true"), "structured: {out}");
        assert!(out.contains("\"senses\":[[{"), "one sense: {out}");
        assert!(out.contains("\"header\":[{"), "shared header: {out}");

        let out = definition_format_parse_glossary("[\"to eat\"]".to_string());
        assert!(out.contains("\"structured\":false"), "flat: {out}");
        assert!(out.contains("\"text\":\"to eat\""), "plain payload: {out}");
    }
}
