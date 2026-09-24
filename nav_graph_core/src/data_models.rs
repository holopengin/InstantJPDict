//! UniFFI records for the shared dictionary row models.
//!
//! The Rust types in [`jpdict_core::data::models`] are the cross-platform
//! vocabulary. These records are the Android boundary representation; the
//! conversions below are deliberately field-for-field so an importer or lookup
//! result can cross UniFFI without changing row data.

/// A single dictionary entry (term or kanji).
#[derive(Clone, Debug, uniffi::Record)]
pub struct DictionaryEntryRow {
    pub id: i64,
    pub kanji: String,
    pub reading: String,
    pub definitions: String,
    pub rules: String,
    pub popularity: i32,
    pub dictionary_id: i64,
    pub onyomi: Option<String>,
    pub kunyomi: Option<String>,
    pub jlpt: Option<String>,
}

/// Metadata for an imported dictionary.
#[derive(Clone, Debug, uniffi::Record)]
pub struct DictionaryMetaRow {
    pub id: i64,
    pub name: String,
    pub priority: i32,
    pub enabled: bool,
    pub built_in: bool,
    pub catalog_id: Option<String>,
}

/// A tag from a dictionary tag bank.
#[derive(Clone, Debug, uniffi::Record)]
pub struct DictionaryTagRow {
    pub id: i64,
    pub name: String,
    pub category: String,
    pub order: i32,
    pub notes: String,
    pub popularity: i32,
    pub dictionary_id: i64,
}

impl From<jpdict_core::data::models::DictionaryEntry> for DictionaryEntryRow {
    fn from(row: jpdict_core::data::models::DictionaryEntry) -> Self {
        Self {
            id: row.id,
            kanji: row.kanji,
            reading: row.reading,
            definitions: row.definitions,
            rules: row.rules,
            popularity: row.popularity,
            dictionary_id: row.dictionary_id,
            onyomi: row.onyomi,
            kunyomi: row.kunyomi,
            jlpt: row.jlpt,
        }
    }
}

impl From<DictionaryEntryRow> for jpdict_core::data::models::DictionaryEntry {
    fn from(row: DictionaryEntryRow) -> Self {
        Self {
            id: row.id,
            kanji: row.kanji,
            reading: row.reading,
            definitions: row.definitions,
            rules: row.rules,
            popularity: row.popularity,
            dictionary_id: row.dictionary_id,
            onyomi: row.onyomi,
            kunyomi: row.kunyomi,
            jlpt: row.jlpt,
        }
    }
}

impl From<jpdict_core::data::models::DictionaryMeta> for DictionaryMetaRow {
    fn from(row: jpdict_core::data::models::DictionaryMeta) -> Self {
        Self {
            id: row.id,
            name: row.name,
            priority: row.priority,
            enabled: row.enabled,
            built_in: row.built_in,
            catalog_id: row.catalog_id,
        }
    }
}

impl From<DictionaryMetaRow> for jpdict_core::data::models::DictionaryMeta {
    fn from(row: DictionaryMetaRow) -> Self {
        Self {
            id: row.id,
            name: row.name,
            priority: row.priority,
            enabled: row.enabled,
            built_in: row.built_in,
            catalog_id: row.catalog_id,
        }
    }
}

impl From<jpdict_core::data::models::DictionaryTag> for DictionaryTagRow {
    fn from(row: jpdict_core::data::models::DictionaryTag) -> Self {
        Self {
            id: row.id,
            name: row.name,
            category: row.category,
            order: row.order,
            notes: row.notes,
            popularity: row.popularity,
            dictionary_id: row.dictionary_id,
        }
    }
}

impl From<DictionaryTagRow> for jpdict_core::data::models::DictionaryTag {
    fn from(row: DictionaryTagRow) -> Self {
        Self {
            id: row.id,
            name: row.name,
            category: row.category,
            order: row.order,
            notes: row.notes,
            popularity: row.popularity,
            dictionary_id: row.dictionary_id,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_entry_row_round_trip(row: DictionaryEntryRow) {
        let core: jpdict_core::data::models::DictionaryEntry = row.clone().into();
        assert_eq!(core.id, 17);
        assert_eq!(core.kanji, "食");
        assert_eq!(core.reading, "しょく");
        assert_eq!(core.definitions, r#"["eat"]"#);
        assert_eq!(core.rules, "五段・上一");
        assert_eq!(core.popularity, 23);
        assert_eq!(core.dictionary_id, 42);
        assert_eq!(core.onyomi.as_deref(), Some("ショク"));
        assert_eq!(core.kunyomi.as_deref(), Some("たべる"));
        assert_eq!(core.jlpt.as_deref(), Some("N5"));

        let got: DictionaryEntryRow = core.into();
        assert_eq!(got.id, row.id);
        assert_eq!(got.kanji, row.kanji);
        assert_eq!(got.reading, row.reading);
        assert_eq!(got.definitions, row.definitions);
        assert_eq!(got.rules, row.rules);
        assert_eq!(got.popularity, row.popularity);
        assert_eq!(got.dictionary_id, row.dictionary_id);
        assert_eq!(got.onyomi, row.onyomi);
        assert_eq!(got.kunyomi, row.kunyomi);
        assert_eq!(got.jlpt, row.jlpt);
    }

    #[test]
    fn dictionary_entry_row_round_trips_every_field() {
        assert_entry_row_round_trip(DictionaryEntryRow {
            id: 17,
            kanji: "食".to_string(),
            reading: "しょく".to_string(),
            definitions: r#"["eat"]"#.to_string(),
            rules: "五段・上一".to_string(),
            popularity: 23,
            dictionary_id: 42,
            onyomi: Some("ショク".to_string()),
            kunyomi: Some("たべる".to_string()),
            jlpt: Some("N5".to_string()),
        });

        let nullable = DictionaryEntryRow {
            id: 18,
            kanji: "空".to_string(),
            reading: "そら".to_string(),
            definitions: "[]".to_string(),
            rules: String::new(),
            popularity: -1,
            dictionary_id: 43,
            onyomi: None,
            kunyomi: None,
            jlpt: None,
        };
        let core: jpdict_core::data::models::DictionaryEntry = nullable.clone().into();
        assert_eq!(core.onyomi, None);
        assert_eq!(core.kunyomi, None);
        assert_eq!(core.jlpt, None);
        let got: DictionaryEntryRow = core.into();
        assert_eq!(got.onyomi, None);
        assert_eq!(got.kunyomi, None);
        assert_eq!(got.jlpt, None);
    }

    #[test]
    fn dictionary_meta_row_round_trips_every_field() {
        let row = DictionaryMetaRow {
            id: 7,
            name: "JMdict".to_string(),
            priority: 11,
            enabled: true,
            built_in: false,
            catalog_id: Some("jmdict-english".to_string()),
        };

        let core: jpdict_core::data::models::DictionaryMeta = row.clone().into();
        assert_eq!(core.id, 7);
        assert_eq!(core.name, "JMdict");
        assert_eq!(core.priority, 11);
        assert!(core.enabled);
        assert!(!core.built_in);
        assert_eq!(core.catalog_id.as_deref(), Some("jmdict-english"));

        let got: DictionaryMetaRow = core.into();
        assert_eq!(got.id, row.id);
        assert_eq!(got.name, row.name);
        assert_eq!(got.priority, row.priority);
        assert_eq!(got.enabled, row.enabled);
        assert_eq!(got.built_in, row.built_in);
        assert_eq!(got.catalog_id, row.catalog_id);
    }

    #[test]
    fn dictionary_tag_row_round_trips_every_field() {
        let row = DictionaryTagRow {
            id: 31,
            name: "common".to_string(),
            category: "partOfSpeech".to_string(),
            order: 4,
            notes: "keep".to_string(),
            popularity: 12,
            dictionary_id: 5,
        };

        let core: jpdict_core::data::models::DictionaryTag = row.clone().into();
        assert_eq!(core.id, 31);
        assert_eq!(core.name, "common");
        assert_eq!(core.category, "partOfSpeech");
        assert_eq!(core.order, 4);
        assert_eq!(core.notes, "keep");
        assert_eq!(core.popularity, 12);
        assert_eq!(core.dictionary_id, 5);

        let got: DictionaryTagRow = core.into();
        assert_eq!(got.id, row.id);
        assert_eq!(got.name, row.name);
        assert_eq!(got.category, row.category);
        assert_eq!(got.order, row.order);
        assert_eq!(got.notes, row.notes);
        assert_eq!(got.popularity, row.popularity);
        assert_eq!(got.dictionary_id, row.dictionary_id);
    }
}
