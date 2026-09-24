//! UniFFI shim over `jpdict_core::data::catalog` (#71).
//!
//! The pinned catalog, its strict parser, and installed-state matching live in
//! the PC core.  This file only turns the core records and primitive values
//! into the shapes the Kotlin facade already exposes; the Android facade keeps
//! its public API while the catalog asset remains a licence/reference fixture.

/// A validation failure crossing the Kotlin boundary.
///
/// UniFFI exports Rust errors as typed exceptions rather than as a bare
/// `String`, so the facade receives an ordinary failed call that its existing
/// `runCatching`/error handling can handle.
#[derive(Debug, uniffi::Error)]
pub enum CatalogParseError {
    Invalid { reason: String },
}

impl std::fmt::Display for CatalogParseError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Invalid { reason } => formatter.write_str(reason),
        }
    }
}

impl std::error::Error for CatalogParseError {}

/// One catalog row at the UniFFI boundary.
///
/// `bytes` crosses as `i64` because the Android `CatalogEntry` API uses a
/// Kotlin `Long`; the core keeps the pin as `u64` for download accounting.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CatalogEntryRecord {
    pub id: String,
    pub name: String,
    pub description: String,
    pub url: String,
    pub bytes: i64,
    pub sha256: String,
    pub title: String,
    pub recommended: bool,
    pub license: String,
    pub source: String,
}

fn to_record(entry: &jpdict_core::data::catalog::CatalogEntry) -> CatalogEntryRecord {
    CatalogEntryRecord {
        id: entry.id.clone(),
        name: entry.name.clone(),
        description: entry.description.clone(),
        url: entry.url.clone(),
        bytes: i64::try_from(entry.bytes)
            .expect("catalog byte pin must fit the Android Long boundary"),
        sha256: entry.sha256.clone(),
        title: entry.title.clone(),
        recommended: entry.recommended,
        license: entry.license.clone(),
        source: entry.source.clone(),
    }
}

fn to_records(entries: Vec<jpdict_core::data::catalog::CatalogEntry>) -> Vec<CatalogEntryRecord> {
    entries.iter().map(to_record).collect()
}

/// Return the catalog embedded in `jpdict_core`.
///
/// The Android asset is retained as a fixture/licence reference, but the
/// runtime rows come from this shared source.
#[uniffi::export]
pub fn catalog_entries() -> Vec<CatalogEntryRecord> {
    jpdict_core::data::catalog::entries()
        .iter()
        .map(to_record)
        .collect()
}

/// Parse and validate a catalog document with the core's strict rules.
///
/// This is used by the Kotlin facade's asset-parity API and by host tests; the
/// ordinary app path obtains the same embedded rows through
/// [`catalog_entries`].
#[uniffi::export]
pub fn catalog_parse(json: String) -> Result<Vec<CatalogEntryRecord>, CatalogParseError> {
    jpdict_core::data::catalog::parse_catalog(&json)
        .map(to_records)
        .map_err(|reason| CatalogParseError::Invalid { reason })
}

/// Resolve installed names and optional catalog ids to catalog ids.
///
/// The optional-id list is parallel to `installed_names`, matching the
/// `InstalledDictionary(name, catalogId)` values collected by Android.
#[uniffi::export]
pub fn catalog_installed_ids(
    installed_names: Vec<String>,
    installed_catalog_ids: Vec<Option<String>>,
) -> Vec<String> {
    jpdict_core::data::catalog::installed_ids(&installed_names, &installed_catalog_ids)
}

/// Strip a bracketed revision from an imported dictionary title, matching the
/// Android `DictionaryCatalog.baseTitle` contract.
#[uniffi::export]
pub fn catalog_base_title(name: String) -> String {
    jpdict_core::data::catalog::base_title(&name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalog_entries_match_the_canonical_core_asset() {
        let got = catalog_entries();
        assert_eq!(got.len(), 4);
        assert_eq!(
            got.iter()
                .map(|entry| entry.id.as_str())
                .collect::<Vec<_>>(),
            vec![
                "jitendex",
                "kanjidic-english",
                "jmdict-english",
                "jmdict-english-with-examples",
            ],
        );
        assert_eq!(got[0].title, "Jitendex.org");
        assert!(got[0].recommended);
        assert!(!got[2].recommended);
        assert_eq!(got[2].bytes, 15_594_803);
    }

    #[test]
    fn parse_uses_core_validation_and_maps_every_field() {
        let json = include_str!("../../app/src/main/assets/catalog/dictionaries.json");
        let parsed = catalog_parse(json.to_string()).expect("canonical asset parses");
        assert_eq!(parsed, catalog_entries());

        let bad = r#"{"schema":1,"entries":[]}"#;
        assert!(catalog_parse(bad.to_string()).is_err());
    }

    #[test]
    fn installed_ids_preserve_exact_id_and_first_title_family_semantics() {
        let names = vec![
            "JMdict [2026-09-15]".to_string(),
            "KANJIDIC [2026-258]".to_string(),
        ];
        let ids = vec![Some("jmdict-english-with-examples".to_string()), None];
        assert_eq!(
            catalog_installed_ids(names, ids),
            vec!["jmdict-english-with-examples", "kanjidic-english"],
        );
    }

    #[test]
    fn base_title_matches_the_android_normalization() {
        assert_eq!(
            catalog_base_title("  JMdict [2026-09-15]  ".to_string()),
            "JMdict"
        );
        assert_eq!(
            catalog_base_title("JMdict Forms".to_string()),
            "JMdict Forms"
        );
    }
}
