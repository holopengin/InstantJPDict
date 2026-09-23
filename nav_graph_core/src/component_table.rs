//! UniFFI shim over `jpdict_core::util::component_table` (#44, WP-02 of the
//! util-module conversion wave).
//!
//! The KRADFILE component table — `呟:亠 口 幺 玄` — crosses as a stateful
//! `uniffi::Object`: the `HashMap` indexes stay private on the Rust side and
//! only owned results cross. Boundary losses: `char` crosses as its `i32` code
//! point (Kotlin `Char.code`), `usize` counts as `i64`/`Long`, and the PC's
//! path-based `load` is not exported at all — APK assets are not filesystem
//! paths, so the Kotlin facade reads `components/krad_components.txt` itself
//! and calls [`ComponentTable::parse`].
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (`uniffi.nav_graph_core.ComponentTable`) is
//! wrapped by the hand-written `com.holopengin.instantjpdict.util.ComponentTable`
//! facade, which keeps the pre-conversion API byte-for-byte (`parse(String)`,
//! `load(Context)`, `entryCount`, `kanjiCount`, `componentsOf(Char)`,
//! `hasComponents(Char)`, `kanjiWith(List<Char>)`, `idfOf(Char)`) so no call
//! site or test changes. The facade absorbs all `Char` ↔ `Int` conversion.

use std::sync::Arc;

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

/// Kanji component table (KRADFILE): decomposition, the inverted index and the
/// IDF weights the OOV candidate policy reads.
///
/// Wraps `jpdict_core::util::component_table::ComponentTable`; the asset format
/// and the parse/IDF rules are documented upstream. The Kotlin host reads the
/// shipped asset bytes and hands them to [`ComponentTable::parse`].
#[derive(uniffi::Object)]
pub struct ComponentTable {
    /// The delegated PC table, shared behind an `Arc` so a later shim in this
    /// crate (WP-09 `oov_candidates`) can hold the same table instead of
    /// cloning the 12k-entry indexes. `pub(crate)` only; it never crosses the
    /// FFI boundary itself.
    pub(crate) inner: Arc<jpdict_core::util::component_table::ComponentTable>,
}

impl ComponentTable {
    /// Share the delegated table (crate-internal; not exported).
    pub(crate) fn inner_arc(&self) -> Arc<jpdict_core::util::component_table::ComponentTable> {
        Arc::clone(&self.inner)
    }
}

#[uniffi::export]
impl ComponentTable {
    /// Parse the table from its text form. Infallible and tolerant upstream: a
    /// malformed line is skipped rather than failing.
    #[uniffi::constructor]
    pub fn parse(text: String) -> Arc<Self> {
        Arc::new(Self {
            inner: Arc::new(jpdict_core::util::component_table::ComponentTable::parse(&text)),
        })
    }

    /// Number of entries parsed — every key in the asset.
    pub fn entry_count(&self) -> i64 {
        self.inner.entry_count() as i64
    }

    /// Number of kanji in the table — the population the IDF is computed over.
    pub fn kanji_count(&self) -> i64 {
        self.inner.kanji_count() as i64
    }

    /// The components of `ch`, in the asset's order, or an empty list for a
    /// character the table has no entry for. Never fails.
    pub fn components_of(&self, ch: i32) -> Vec<i32> {
        codepoints_from_chars(self.inner.components_of(char_from_codepoint(ch)))
    }

    /// Whether the table has an entry for `ch`.
    pub fn has_components(&self, ch: i32) -> bool {
        self.inner.has_components(char_from_codepoint(ch))
    }

    /// Every kanji carrying **all** of `all`, ascending by codepoint. An empty
    /// requirement, or a component no kanji carries, returns nothing.
    pub fn kanji_with(&self, all: Vec<i32>) -> Vec<i32> {
        let all: Vec<char> = all.iter().map(|&cp| char_from_codepoint(cp)).collect();
        codepoints_from_chars(&self.inner.kanji_with(&all))
    }

    /// `ln(kanji_count / kanji_count_containing(component))` — nats, not bits.
    /// An unknown component returns `0.0`.
    pub fn idf_of(&self, component: i32) -> f32 {
        self.inner.idf_of(char_from_codepoint(component))
    }
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::util::component_table` unit tests, run
    //! against the exact dependency this crate delegates to — through the
    //! exported surface, not the upstream module, so the shim's `char` ↔ `i32`
    //! conversion is covered too. The JVM suite (`ComponentTableTest`) pins the
    //! same behaviour across the UniFFI boundary.

    use super::*;

    /// Code points of `text` — the shape the exported surface takes.
    fn cps(text: &str) -> Vec<i32> {
        text.chars().map(|c| c as i32).collect()
    }

    /// The real asset, read from the Android repo (mirrors mobile
    /// `ComponentTableTest`). `None` on a checkout without it.
    fn asset_text() -> Option<String> {
        let path = std::path::Path::new(concat!(env!("CARGO_MANIFEST_DIR")))
            .join("../app/src/main/assets/components/krad_components.txt");
        std::fs::read_to_string(&path).ok()
    }

    #[test]
    fn components_of_fukan_are_exactly_the_measured_set() {
        let Some(text) = asset_text() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        let table = ComponentTable::parse(text);
        let mut got = table.components_of('呟' as i32);
        got.sort();
        assert_eq!(got, cps("亠口幺玄"));
        assert_eq!(table.components_of('呟' as i32).len(), 4);
    }

    #[test]
    fn kanji_with_uses_the_inverted_index_and_requires_every_component() {
        let Some(text) = asset_text() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        let table = ComponentTable::parse(text);
        let both = table.kanji_with(cps("口亠"));
        assert!(both.contains(&('呟' as i32)), "呟 carries both 口 and 亠");
        assert!(
            (2..table.kanji_count()).contains(&(both.len() as i64)),
            "the pair is selective, not vacuous: {}",
            both.len()
        );
        for k in &both {
            let comps = table.components_of(*k);
            assert!(
                comps.contains(&('口' as i32)) && comps.contains(&('亠' as i32)),
                "{} returned for 口+亠 but does not carry both",
                char::from_u32(*k as u32).unwrap_or('\u{FFFD}')
            );
        }
        let mut sorted = both.clone();
        sorted.sort();
        assert_eq!(both, sorted, "codepoint order, deterministic");
        assert_eq!(both, table.kanji_with(cps("口亠口")), "repeats change nothing");
        assert!(table.kanji_with(vec![]).is_empty(), "no evidence -> nothing");
        assert!(
            table.kanji_with(vec!['口' as i32, 0xE000]).is_empty(),
            "unknown component -> nothing"
        );
    }

    #[test]
    fn idf_weighs_a_rare_component_above_a_common_one() {
        let Some(text) = asset_text() else {
            eprintln!("skipping: components asset not present");
            return;
        };
        let table = ComponentTable::parse(text);
        assert!(table.idf_of('車' as i32) > table.idf_of('一' as i32));
        assert!(table.idf_of('一' as i32) > 0.0);
        assert_eq!(table.idf_of(0xE000), 0.0, "unknown: no weight, never NaN");
    }

    #[test]
    fn unknown_characters_are_empty_rather_than_exceptional() {
        let table = ComponentTable::parse("一:一\n".to_string());
        assert!(!table.has_components(0xE000));
        assert!(table.components_of(0xE000).is_empty());
        assert!(table.components_of('あ' as i32).is_empty());
    }

    #[test]
    fn parse_dedupes_repeated_components_and_keeps_the_first_entry() {
        let parsed = ComponentTable::parse(
            [
                "一:一 一", // repeated component: deduped
                "丁:一 亅",
                "not a table line",
                " :口", // non-kanji key: kept in the table, excluded from the index
                "ニ:一 二", // katakana key: same
                "一:口 亅", // duplicate key: the generator's setdefault keeps the first
            ]
            .join("\n"),
        );
        assert_eq!(parsed.components_of('一' as i32), cps("一"));
        assert_eq!(parsed.components_of('丁' as i32), cps("一亅"));
        assert_eq!(parsed.components_of(' ' as i32), cps("口"));
        assert_eq!(parsed.entry_count(), 4);
        assert_eq!(parsed.kanji_count(), 2);
        assert_eq!(parsed.kanji_with(cps("一")), cps("一丁"));
        assert!(parsed.kanji_with(cps("口")).is_empty());
        // ln(2 kanji / 1 carrier) for 亅; ln(2/2) = 0 for the ubiquitous 一.
        assert!((parsed.idf_of('亅' as i32) - 0.6931472).abs() < 1e-5);
        assert!((parsed.idf_of('一' as i32) - 0.0).abs() < 1e-6);
    }

    #[test]
    fn the_bundled_asset_still_has_12156_entries() {
        let path = std::path::Path::new(concat!(env!("CARGO_MANIFEST_DIR")))
            .join("../app/src/main/assets/components/krad_components.txt");
        let Ok(text) = std::fs::read_to_string(&path) else {
            eprintln!("skipping: {} not present", path.display());
            return;
        };
        let lines: Vec<&str> = text.lines().collect();
        // PROVENANCE.txt pins the entry count; a truncated or regenerated
        // asset must fail loudly here, not silently degrade every candidate
        // set downstream.
        assert_eq!(12156, lines.len(), "entry count in components/krad_components.txt");
        for (i, line) in lines.iter().enumerate() {
            let mut chars = line.chars();
            let second = chars.nth(1);
            assert!(
                line.len() >= 3 && second == Some(':') && !chars.as_str().starts_with(' '),
                "line {} is not 'kanji:components': {}",
                i + 1,
                line.chars().take(40).collect::<String>()
            );
        }
        // Every line must survive parsing — a line the parser skips would
        // shrink the table below the line count above.
        let table = ComponentTable::parse(text.clone());
        assert_eq!(12156, table.entry_count());
        assert_eq!(12156, table.kanji_count());
        assert_eq!("一:一", lines[0], "first line of the codepoint-sorted asset");
    }

    /// PC `load_rejects_a_missing_or_empty_asset`, as far as it survives the
    /// boundary.
    ///
    /// The path-based `load` is deliberately not exported: APK assets are not
    /// filesystem paths, so the Kotlin facade owns "the asset cannot be read"
    /// (it throws exactly as it did before) and this shim only exposes the
    /// infallible `parse`. What can be pinned here is the empty half of the PC
    /// test: empty input parses to an empty table — never a panic, never all
    /// 12,156 kanji — so an empty asset degrades callers the same way the old
    /// Kotlin did.
    #[test]
    fn empty_text_parses_to_an_empty_table() {
        for text in ["", "\n", " \n"] {
            let table = ComponentTable::parse(text.to_string());
            assert_eq!(table.entry_count(), 0, "input {text:?}");
            assert_eq!(table.kanji_count(), 0, "input {text:?}");
            assert!(!table.has_components('一' as i32), "input {text:?}");
            assert!(table.components_of('一' as i32).is_empty(), "input {text:?}");
            assert!(table.kanji_with(cps("一")).is_empty(), "input {text:?}");
            assert_eq!(table.idf_of('一' as i32), 0.0, "input {text:?}");
        }
    }
}
