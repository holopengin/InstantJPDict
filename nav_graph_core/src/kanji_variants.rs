//! UniFFI shim over `jpdict_core::util::kanji_variants` (#44, WP-03 of the
//! util-module conversion wave).
//!
//! The vendored Unihan `kSemanticVariant`/`kZVariant` pairs plus the JMdict
//! old-orthography half cross as a stateful `uniffi::Object`: the three
//! `HashMap` indexes stay private on the Rust side and only owned results
//! cross. Boundary losses: `char` crosses as its `i32` code point (Kotlin
//! `Char.code`), `usize` counts as `i64`/`Long`, and the PC's path-based `load`
//! is not exported at all — APK assets are not filesystem paths, so the Kotlin
//! facade reads `variants/kanji_variants.txt` itself and calls
//! [`KanjiVariantTable::parse`].
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (`uniffi.nav_graph_core.KanjiVariantTable`) is
//! wrapped by the hand-written `com.holopengin.instantjpdict.util.KanjiVariants`
//! singleton, whose nested `Table` keeps the pre-conversion API byte-for-byte
//! (`Table.EMPTY`, `Table.parse(String)`, `size`, `canonical(Char)`,
//! `canonicalsOf(Char)`, `obsoleteFormsOf(Char)`, plus the object-level
//! `install(Context)`, `install(Table)`, `parse`, `entryCount`, `canonical`,
//! `canonicalsOf`, `obsoleteFormsOf`) so no call site or test changes. The
//! facade absorbs all `Char` ↔ `Int` conversion.
//!
//! ## Reuse
//!
//! [`KanjiVariantTable::inner`] is `pub(crate)` so a later shim in this crate
//! (WP-10 `oov_suggestions`) can borrow the table and build its `variant_forms`
//! closure internally, per the wave's "closures never cross" rule.

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

/// Kanji variant table (#44): the vendored Unihan pairs plus the JMdict
/// old-orthography half, indexed in both directions.
///
/// Wraps `jpdict_core::util::kanji_variants::KanjiVariantTable`; the asset
/// format, the `variant -> canonical` direction rule and the first-canonical
/// rule are documented upstream. Built by [`KanjiVariantTable::parse`] from the
/// host's `variants/kanji_variants.txt` text (APK assets are not filesystem
/// paths, so the host reads the file and passes the text), or empty by
/// [`KanjiVariantTable::empty`].
#[derive(uniffi::Object)]
pub struct KanjiVariantTable {
    /// The delegated PC table. `pub(crate)` only so a later shim in this crate
    /// (WP-10 `oov_suggestions`) can borrow it; it never crosses the FFI
    /// boundary itself.
    pub(crate) inner: jpdict_core::util::kanji_variants::KanjiVariantTable,
}

#[uniffi::export]
impl KanjiVariantTable {
    /// An empty table: every lookup is the identity function. The Kotlin
    /// facade's `Table.EMPTY` wraps this.
    #[uniffi::constructor]
    pub fn empty() -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::kanji_variants::KanjiVariantTable::empty(),
        })
    }

    /// Parse the committed asset's text: one `variant<TAB>canonical` pair per
    /// line, `#` comments and blank lines ignored, malformed lines skipped.
    /// Infallible and tolerant upstream: an empty input parses to an empty
    /// table (every lookup the identity), never an error.
    #[uniffi::constructor]
    pub fn parse(text: String) -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::kanji_variants::KanjiVariantTable::parse(&text),
        })
    }

    /// Number of distinct variants parsed; 0 when nothing is loaded.
    pub fn entry_count(&self) -> i64 {
        self.inner.entry_count() as i64
    }

    /// The canonical form of `ch`, or `ch` itself when the table has no entry
    /// for it. Never fails, including on an empty table.
    pub fn canonical(&self, ch: i32) -> i32 {
        self.inner.canonical(char_from_codepoint(ch)) as i32
    }

    /// Every canonical candidate the table lists for `ch`, sorted; empty when
    /// there are none.
    pub fn canonicals_of(&self, ch: i32) -> Vec<i32> {
        codepoints_from_chars(self.inner.canonicals_of(char_from_codepoint(ch)))
    }

    /// The variant forms that fold to `ch`, sorted; empty when there are none.
    pub fn obsolete_forms_of(&self, ch: i32) -> Vec<i32> {
        codepoints_from_chars(&self.inner.obsolete_forms_of(char_from_codepoint(ch)))
    }
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::util::kanji_variants` unit tests, run
    //! against the exact dependency this crate delegates to — through the
    //! exported surface, not the upstream module, so the shim's `char` ↔ `i32`
    //! conversion is covered too. The JVM suite (`KanjiVariantsTest`) pins the
    //! same behaviour across the UniFFI boundary.

    use super::*;

    /// Code points of `text` — the shape the exported surface returns.
    fn cps(text: &str) -> Vec<i32> {
        text.chars().map(|c| c as i32).collect()
    }

    /// The real asset, read from the Android repo (mirrors mobile
    /// `KanjiVariantsTest`). `None` on a checkout without it.
    fn asset_text() -> Option<String> {
        let path = std::path::Path::new(concat!(env!("CARGO_MANIFEST_DIR")))
            .join("../app/src/main/assets/variants/kanji_variants.txt");
        std::fs::read_to_string(&path).ok()
    }

    /// The committed asset parsed through the exported surface; `None` when
    /// the asset is absent (each asset test then skips loudly).
    fn table() -> Option<Arc<KanjiVariantTable>> {
        Some(KanjiVariantTable::parse(asset_text()?))
    }

    #[test]
    fn parses_the_committed_asset() {
        let path = std::path::Path::new(concat!(env!("CARGO_MANIFEST_DIR")))
            .join("../app/src/main/assets/variants/kanji_variants.txt");
        let Ok(text) = std::fs::read_to_string(&path) else {
            eprintln!("skipping: {} not present", path.display());
            return;
        };
        assert_eq!(669, text.lines().filter(|l| !l.trim().is_empty()).count());
        // 600 distinct variants: Unihan gives 58 of them more than one
        // canonical candidate, and the table keeps every pair.
        assert_eq!(600, KanjiVariantTable::parse(text).entry_count());
    }

    #[test]
    fn the_jmdict_half_reaches_old_orthography_the_vocab_rule_could_not() {
        let Some(t) = table() else {
            eprintln!("skipping: variants asset not present");
            return;
        };
        assert_eq!('掴' as i32, t.canonical('摑' as i32));
        assert_eq!('国' as i32, t.canonical('國' as i32));
        assert_eq!('会' as i32, t.canonical('會' as i32));
        assert_eq!('灯' as i32, t.canonical('燈' as i32));
        assert_eq!('当' as i32, t.canonical('當' as i32));
        // Traps the intersection exists to exclude: Unihan links each of
        // these, but they are different words in Japanese.
        assert_eq!('誌' as i32, t.canonical('誌' as i32));
        assert_eq!('製' as i32, t.canonical('製' as i32));
        assert_eq!('長' as i32, t.canonical('長' as i32));
        assert_eq!('階' as i32, t.canonical('階' as i32));
    }

    #[test]
    fn chains_resolve_to_the_form_a_dictionary_indexes() {
        let Some(t) = table() else {
            eprintln!("skipping: variants asset not present");
            return;
        };
        // 冩 -> 寫 -> 写: both hops now reach 写.
        assert_eq!('写' as i32, t.canonical('冩' as i32));
        assert_eq!('写' as i32, t.canonical('寫' as i32));
        // A genuine cycle (干 <-> 乾) is kept as-is rather than guessed at.
        assert_eq!('干' as i32, t.canonical('乾' as i32));
        assert_eq!('乾' as i32, t.canonical('干' as i32));
    }

    #[test]
    fn folds_variant_onto_the_dictionary_form() {
        let Some(t) = table() else {
            eprintln!("skipping: variants asset not present");
            return;
        };
        assert_eq!('回' as i32, t.canonical('囘' as i32));
        assert_eq!('鬱' as i32, t.canonical('欝' as i32));
        assert_eq!('罈' as i32, t.canonical('壜' as i32));
        assert_eq!('逃' as i32, t.canonical('迯' as i32));
        assert_eq!('器' as i32, t.canonical('噐' as i32));
        assert_eq!('慚' as i32, t.canonical('慙' as i32));
    }

    #[test]
    fn a_variant_with_several_canonical_candidates_keeps_them_all() {
        let Some(t) = table() else {
            eprintln!("skipping: variants asset not present");
            return;
        };
        assert_eq!(cps("盖蓋"), t.canonicals_of('葢' as i32));
        assert_eq!('盖' as i32, t.canonical('葢' as i32));
        assert_eq!(cps("冰氷"), t.canonicals_of('冫' as i32));
        assert_eq!('冰' as i32, t.canonical('冫' as i32));
        assert_eq!(cps("回"), t.canonicals_of('囘' as i32));
        assert!(t.canonicals_of('あ' as i32).is_empty());
    }

    #[test]
    fn unknown_character_returns_itself() {
        let Some(t) = table() else {
            eprintln!("skipping: variants asset not present");
            return;
        };
        assert_eq!('漢' as i32, t.canonical('漢' as i32));
        assert_eq!('あ' as i32, t.canonical('あ' as i32));
        assert_eq!('A' as i32, t.canonical('A' as i32));
        assert_eq!('回' as i32, t.canonical('回' as i32));
    }

    #[test]
    fn obsolete_forms_are_the_reverse_direction() {
        let Some(t) = table() else {
            eprintln!("skipping: variants asset not present");
            return;
        };
        assert!(t.obsolete_forms_of('回' as i32).contains(&('囘' as i32)));
        assert!(t.obsolete_forms_of('鬱' as i32).contains(&('欝' as i32)));
        assert_eq!(cps("墰壜"), t.obsolete_forms_of('罈' as i32));
        let forms = t.obsolete_forms_of('回' as i32);
        let mut sorted = forms.clone();
        sorted.sort();
        assert_eq!(forms, sorted, "sorted");
        assert!(t.obsolete_forms_of('あ' as i32).is_empty());
        assert!(t.obsolete_forms_of('漢' as i32).is_empty());
    }

    #[test]
    fn empty_table_is_the_identity() {
        let t = KanjiVariantTable::parse(String::new());
        assert_eq!(0, t.entry_count());
        assert_eq!('囘' as i32, t.canonical('囘' as i32));
        assert!(t.obsolete_forms_of('回' as i32).is_empty());
        assert!(t.canonicals_of('囘' as i32).is_empty());
        assert_eq!(0, KanjiVariantTable::empty().entry_count());
    }

    #[test]
    fn parse_skips_malformed_lines_and_keeps_the_first_canonical() {
        let t = KanjiVariantTable::parse(
            "# a comment\n你\t你\n囘\t回\n囘\t囬\n囘\t回\textra\n囘回\n回\t回\n".to_string(),
        );
        // Only the well-formed single-character pairs survive (你 is
        // supplementary-plane, so it is skipped); the duplicate variant keeps
        // the first line.
        assert_eq!(1, t.entry_count());
        assert_eq!('回' as i32, t.canonical('囘' as i32));
    }

    /// PC `load_rejects_a_missing_or_empty_asset`, as far as it survives the
    /// boundary.
    ///
    /// The path-based `load` is deliberately not exported: APK assets are not
    /// filesystem paths, so the Kotlin facade owns "the asset cannot be read"
    /// (it throws exactly as it did before) and this shim only exposes the
    /// infallible `parse`. What can be pinned here is the empty half of the PC
    /// test: empty input parses to an empty table — never a panic, never a
    /// non-identity fold — so an empty asset degrades callers the same way the
    /// old Kotlin did.
    #[test]
    fn empty_text_parses_to_an_empty_table() {
        for text in ["", "\n", " \n"] {
            let t = KanjiVariantTable::parse(text.to_string());
            assert_eq!(0, t.entry_count(), "input {text:?}");
            assert_eq!('囘' as i32, t.canonical('囘' as i32), "input {text:?}");
            assert!(
                t.obsolete_forms_of('回' as i32).is_empty(),
                "input {text:?}"
            );
            assert!(t.canonicals_of('囘' as i32).is_empty(), "input {text:?}");
        }
    }
}
