//! UniFFI shim over `jpdict_core::util::japanese`'s two pre-reform kana tables
//! — #75 orthography (`KanaOrthography`) and #81 sound changes
//! (`KanaSoundChanges`), WP-11b of the util-module conversion wave.
//!
//! Both tables are parsed data upstream (`KanaOrthographyTable` /
//! `KanaSoundTable`); this file exports each as a UniFFI `Object` with the
//! three constructors the Kotlin facades need (`parse` / `builtin` / `empty`)
//! and the lookups they call. Nothing algorithmic lives here: the parse rules,
//! the context rules and the modernise passes are upstream, so the Kotlin fork
//! is deleted and the tables have one source of truth.
//!
//! Boundary conventions follow `char_lm.rs` (WP-01): `char` crosses as `i32`
//! (`Char.code` / `Char(code)`), `usize` as `i64`. `builtin()` is the committed
//! table PC inlines as consts — byte-identical to the Android assets, so the
//! Kotlin `install(context)` no longer reads them — while `parse()` keeps the
//! asset text parseable on the host, which is what the Kotlin tests install.
//! Both objects are `Send + Sync` plain data, so `uniffi::Object` is legal.
//!
//! ## Kotlin facade contract
//!
//! `com.holopengin.instantjpdict.util.KanaOrthography` and
//! `com.holopengin.instantjpdict.util.KanaSoundChanges` keep their
//! pre-conversion API byte-for-byte (`ASSET_PATH`, `install`, `parse`,
//! `entryCount`, `canonical` / `modernise`, and the nested `Table` with
//! `EMPTY`, `size`, `ha` / `au` / `eu`, `toString`). The facades wrap the
//! generated `uniffi.nav_graph_core.KanaOrthographyTable` /
//! `uniffi.nav_graph_core.KanaSoundTable` handles and absorb every
//! `Char` <-> `Int` conversion.

use std::sync::Arc;

/// Decode one Kotlin `Char.code` into a Rust `char`; invalid code points
/// (negative, surrogates) degrade to U+FFFD, as in `char_lm.rs`.
fn char_from_codepoint(codepoint: i32) -> char {
    u32::try_from(codepoint)
        .ok()
        .and_then(char::from_u32)
        .unwrap_or('\u{FFFD}')
}

/// Pre-reform kana orthography (#75): `variant<TAB>canonical` pairs.
///
/// Wraps `jpdict_core::util::japanese::KanaOrthographyTable`; the asset
/// format, the `variant -> modern` direction and the context rules are
/// documented upstream. [`KanaOrthographyTable::builtin`] is the committed
/// table (the shipped `variants/kana_variants.txt` rows) and is what the
/// Kotlin facade's `install(context)` installs; [`KanaOrthographyTable::parse`]
/// parses the same text so a host can install a table from any source.
#[derive(uniffi::Object)]
pub struct KanaOrthographyTable {
    inner: jpdict_core::util::japanese::KanaOrthographyTable,
}

#[uniffi::export]
impl KanaOrthographyTable {
    /// Parse the committed asset's text: one `variant<TAB>canonical` pair per
    /// line, `#` comments and blank lines ignored, malformed lines skipped,
    /// and the first mapping for a variant wins. An empty input parses to an
    /// empty table (every lookup the identity), never an error.
    #[uniffi::constructor]
    pub fn parse(text: String) -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::japanese::KanaOrthographyTable::parse(&text),
        })
    }

    /// The builtin table: the committed pairs PC inlines as consts, which are
    /// the shipped `variants/kana_variants.txt` rows.
    #[uniffi::constructor]
    pub fn builtin() -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::japanese::KanaOrthographyTable::builtin(),
        })
    }

    /// An empty table: every lookup is the identity function. The Kotlin
    /// facade's `Table.EMPTY` wraps this.
    #[uniffi::constructor]
    pub fn empty() -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::japanese::KanaOrthographyTable::empty(),
        })
    }

    /// Number of distinct variants in this table.
    pub fn entry_count(&self) -> i64 {
        self.inner.entry_count() as i64
    }

    /// The modern form of `ch`, or `ch` itself when the table has no entry.
    pub fn canonical(&self, ch: i32) -> i32 {
        self.inner.canonical(char_from_codepoint(ch)) as i32
    }

    /// Rewrite a lookup query from pre-reform orthography onto the modern form
    /// the dictionary keys on. Never fails; the identity when nothing in the
    /// query is a variant.
    pub fn modernise(&self, query: String) -> String {
        self.inner.modernise(&query)
    }
}

/// Historical kana sound changes (#81): `variant<TAB>modern<TAB>row` pairs
/// with rows `ha` (ハ行転呼), `au` (アウ->オウ) and `eu` (エウ->ヨウ).
///
/// Wraps `jpdict_core::util::japanese::KanaSoundTable`; the asset format, the
/// grammatical ending conditions and the vowel-change order are documented
/// upstream. [`KanaSoundTable::builtin`] is the committed table (the shipped
/// `variants/kana_sound_changes.txt` rows) and is what the Kotlin facade's
/// `install(context)` installs; [`KanaSoundTable::parse`] parses the same text
/// so a host can install a table from any source.
#[derive(uniffi::Object)]
pub struct KanaSoundTable {
    inner: jpdict_core::util::japanese::KanaSoundTable,
}

#[uniffi::export]
impl KanaSoundTable {
    /// Parse the committed asset's text: one `variant<TAB>modern<TAB>row`
    /// line per pair, `#` comments and blank lines ignored, malformed lines
    /// skipped, unknown rows ignored, and the first mapping for a variant
    /// wins. An empty input parses to an empty table (every lookup the
    /// identity), never an error.
    #[uniffi::constructor]
    pub fn parse(text: String) -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::japanese::KanaSoundTable::parse(&text),
        })
    }

    /// The builtin table: the committed rows PC inlines as consts, which are
    /// the shipped `variants/kana_sound_changes.txt` rows.
    #[uniffi::constructor]
    pub fn builtin() -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::japanese::KanaSoundTable::builtin(),
        })
    }

    /// An empty table: every lookup misses and `modernise` is the identity.
    /// The Kotlin facade's `Table.EMPTY` wraps this.
    #[uniffi::constructor]
    pub fn empty() -> Arc<Self> {
        Arc::new(Self {
            inner: jpdict_core::util::japanese::KanaSoundTable::empty(),
        })
    }

    /// Number of distinct pairs in this table.
    pub fn entry_count(&self) -> i64 {
        self.inner.entry_count() as i64
    }

    /// The ハ行転呼 modern form of `ch`, when the table carries the pair.
    pub fn ha(&self, ch: i32) -> Option<i32> {
        self.inner.ha(char_from_codepoint(ch)).map(|c| c as i32)
    }

    /// The アウ->オウ modern form of `ch`, when the table carries the pair.
    pub fn au(&self, ch: i32) -> Option<i32> {
        self.inner.au(char_from_codepoint(ch)).map(|c| c as i32)
    }

    /// The エウ->ヨウ modern form of `ch` (1–2 characters), when the table
    /// carries the pair.
    pub fn eu(&self, ch: i32) -> Option<String> {
        self.inner.eu(char_from_codepoint(ch))
    }

    /// Rewrite an already-modernised lookup query (see
    /// [`KanaOrthographyTable::modernise`]) through the ハ行転呼 pass, then the
    /// vowel-change pass. Never fails; the identity when nothing in the query
    /// is a rule's input.
    pub fn modernise(&self, query: String) -> String {
        self.inner.modernise(&query)
    }
}

#[cfg(test)]
mod tests {
    //! Mirror of the Kotlin `KanaOrthographyTest` (15) and
    //! `KanaSoundChangesTest` (12) assertions, run through the exported shim
    //! surface (the constructors and methods above, not the upstream module),
    //! so the argument conversion is covered too. The JVM suite pins the same
    //! behaviour across the real UniFFI boundary; the asset-based tests here
    //! read the Android repo's committed assets and skip with a note when a
    //! checkout lacks them. `KanaSoundChangesTest`'s last test asserts the
    //! call-site contract (`OcrOverlayStateController` keeps the raw, #75 and
    //! sound-changed forms in its search set); here that reduces to the three
    //! composed strings it pins.

    use super::*;

    const ORTHOGRAPHY_ASSET: &str = "../app/src/main/assets/variants/kana_variants.txt";
    const SOUND_ASSET: &str = "../app/src/main/assets/variants/kana_sound_changes.txt";

    /// The Android asset, read from the repo this crate lives in. Absent in a
    /// checkout without assets: the caller skips with a note.
    fn asset(rel: &str) -> Option<String> {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(rel);
        match std::fs::read_to_string(&path) {
            Ok(text) => Some(text),
            Err(_) => {
                eprintln!("skipping: {} not present", path.display());
                None
            }
        }
    }

    /// The shipped composition: the #75 variant fold first, then the #81 sound
    /// changes.
    fn normalise(query: &str) -> String {
        let folded = KanaOrthographyTable::builtin().modernise(query.to_string());
        KanaSoundTable::builtin().modernise(folded)
    }

    fn ortho_modernise(query: &str) -> String {
        KanaOrthographyTable::builtin().modernise(query.to_string())
    }

    // ── KanaOrthographyTest ─────────────────────────────────────────────────

    /// `parses_the_committed_asset` + `installs_and_normalises_with_the_asset`.
    #[test]
    fn orthography_parses_the_committed_asset() {
        let Some(text) = asset(ORTHOGRAPHY_ASSET) else {
            return;
        };
        let lines = text
            .lines()
            .filter(|line| {
                let line = line.trim();
                !line.is_empty() && !line.starts_with('#')
            })
            .count();
        assert_eq!(16, lines);
        assert_eq!(16, KanaOrthographyTable::parse(text).entry_count());
        assert_eq!(16, KanaOrthographyTable::builtin().entry_count());
    }

    /// `pins_every_shipped_pair`, against both the builtin table and the
    /// parsed asset.
    #[test]
    fn orthography_pins_every_shipped_pair() {
        const PAIRS: &[(char, char)] = &[
            // wagyou ワ行
            ('ゐ', 'い'), ('ゑ', 'え'), ('ヰ', 'イ'), ('ヱ', 'エ'),
            // dakugyou だ行
            ('ぢ', 'じ'), ('づ', 'ず'), ('ヂ', 'ジ'), ('ヅ', 'ズ'),
            // sokuon 促音
            ('つ', 'っ'), ('ツ', 'ッ'),
            // yoon 拗音
            ('や', 'ゃ'), ('ゆ', 'ゅ'), ('よ', 'ょ'), ('ヤ', 'ャ'), ('ユ', 'ュ'), ('ヨ', 'ョ'),
        ];
        let mut tables = vec![KanaOrthographyTable::builtin()];
        if let Some(text) = asset(ORTHOGRAPHY_ASSET) {
            tables.push(KanaOrthographyTable::parse(text));
        }
        for table in &tables {
            for (variant, canonical) in PAIRS {
                assert_eq!(*canonical as i32, table.canonical(*variant as i32), "{variant}");
            }
            // Excluded as ordinary modern usage, so they fold to themselves:
            // the accusative particle, and the 小書き row (あ->ぁ … わ->ゎ),
            // whose variant side is a standard modern kana.
            assert_eq!('を' as i32, table.canonical('を' as i32));
            assert_eq!('ヲ' as i32, table.canonical('ヲ' as i32));
            for ordinary in "あいうえおわアイウエオワ".chars() {
                assert_eq!(ordinary as i32, table.canonical(ordinary as i32));
            }
        }
    }

    /// `normalises_the_headline_legacy_forms`.
    #[test]
    fn orthography_normalises_the_headline_legacy_forms() {
        // いつしよ -> いっしょ needs つ->っ AND よ->ょ.
        assert_eq!("いっしょ", ortho_modernise("いつしよ"));
        // しゆつぱつ -> しゅっぱつ needs つ->っ AND ゆ->ゅ.
        assert_eq!("しゅっぱつ", ortho_modernise("しゆつぱつ"));
        assert_eq!("ちょっと", ortho_modernise("ちよつと"));
        // きやう -> きょう is only half reached: や->ゃ is this table's 拗音
        // row, the アウ->オウ shift is #81.
        assert_eq!("きゃう", ortho_modernise("きやう"));
    }

    /// `normalises_wagyou_and_dakugyou_in_context`.
    #[test]
    fn orthography_normalises_wagyou_and_dakugyou_in_context() {
        assert_eq!("いる", ortho_modernise("ゐる"));
        assert_eq!("植えた", ortho_modernise("植ゑた"));
        assert_eq!("あずま", ortho_modernise("あづま"));
        assert_eq!("おのずから", ortho_modernise("おのづから"));
        assert_eq!("みず", ortho_modernise("みづ"));
        assert_eq!("はなじ", ortho_modernise("はなぢ"));
        assert_eq!("ウイスキー", ortho_modernise("ウヰスキー"));
        assert_eq!("エビス", ortho_modernise("ヱビス"));
    }

    /// `leaves_rendaku_alone`.
    #[test]
    fn orthography_leaves_rendaku_alone() {
        // 連濁: つづく/ちぢむ are the modern forms themselves.
        assert_eq!("つづく", ortho_modernise("つづく"));
        assert_eq!("ちぢむ", ortho_modernise("ちぢむ"));
        assert_eq!("ちぢれる", ortho_modernise("ちぢれる"));
        assert_eq!("つづける", ortho_modernise("つづける"));
        // katakana counterpart of the same guard
        assert_eq!("ツヅク", ortho_modernise("ツヅク"));
    }

    /// `leaves_the_accusative_particle_alone`.
    #[test]
    fn orthography_leaves_the_accusative_particle_alone() {
        assert_eq!("本を読む", ortho_modernise("本を読む"));
        assert_eq!("ヲタク", ortho_modernise("ヲタク"));
    }

    /// `the_excluded_rows_fold_to_themselves`.
    #[test]
    fn orthography_the_excluded_rows_fold_to_themselves() {
        assert_eq!("ああ", ortho_modernise("ああ"));
        assert_eq!("そうそう", ortho_modernise("そうそう"));
        assert_eq!("かわ", ortho_modernise("かわ"));
        assert_eq!("コーヒー", ortho_modernise("コーヒー"));
    }

    /// `does_not_fold_tsu_before_the_k_row`.
    #[test]
    fn orthography_does_not_fold_tsu_before_the_k_row() {
        // 促音 fires only before た行/さ行/ぱ行.
        assert_eq!("つかう", ortho_modernise("つかう"));
        assert_eq!("つくえ", ortho_modernise("つくえ"));
        // ...while the sokuon positions still normalise
        assert_eq!("あった", ortho_modernise("あつた"));
        assert_eq!("いって", ortho_modernise("いつて"));
        assert_eq!("いっぱい", ortho_modernise("いつぱい"));
    }

    /// `is_the_identity_on_modern_text`.
    #[test]
    fn orthography_is_the_identity_on_modern_text() {
        for modern in [
            "", "あ", "ABC", "日本語", "つくえ", "にっぽん", "がっこう", "コーヒー", "12月",
            "、。",
        ] {
            assert_eq!(modern, ortho_modernise(modern));
        }
    }

    /// `is_deterministic_and_answers_from_the_query_alone`.
    #[test]
    fn orthography_is_deterministic_and_answers_from_the_query_alone() {
        let q = "いつしよ";
        assert_eq!(ortho_modernise(q), ortho_modernise(q));
        assert_eq!("いっしょ", ortho_modernise(q));
        assert_eq!("行っていっしょ", ortho_modernise("行つていつしよ"));
        assert_eq!("。いっしょ、", ortho_modernise("。いつしよ、"));
        assert_eq!("いる", ortho_modernise("ゐる"));
    }

    /// `unknown_characters_fold_to_themselves`.
    #[test]
    fn orthography_unknown_characters_fold_to_themselves() {
        let table = KanaOrthographyTable::builtin();
        assert_eq!('漢' as i32, table.canonical('漢' as i32));
        assert_eq!('ん' as i32, table.canonical('ん' as i32));
        assert_eq!('A' as i32, table.canonical('A' as i32));
        assert_eq!('い' as i32, table.canonical('い' as i32));
    }

    /// `empty_table_is_the_identity`.
    #[test]
    fn orthography_empty_table_is_the_identity() {
        let table = KanaOrthographyTable::empty();
        assert_eq!(0, table.entry_count());
        assert_eq!('ゐ' as i32, table.canonical('ゐ' as i32));
        assert_eq!("ゐる", table.modernise("ゐる".to_string()));
    }

    /// `parse_skips_malformed_lines_and_keeps_the_first_canonical`.
    #[test]
    fn orthography_parse_skips_malformed_lines_and_keeps_the_first_canonical() {
        let table = KanaOrthographyTable::parse(
            "# a comment\nゐ\tい\nゐ\tゑ\nゐい\nい\tい\nゐ\tい\textra\n".to_string(),
        );
        assert_eq!(1, table.entry_count());
        assert_eq!('い' as i32, table.canonical('ゐ' as i32));
    }

    /// `the_table_is_kana_only_single_characters`.
    #[test]
    fn orthography_the_table_is_kana_only_single_characters() {
        let Some(text) = asset(ORTHOGRAPHY_ASSET) else {
            return;
        };
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let parts: Vec<&str> = line.split('\t').collect();
            assert_eq!(2, parts.len(), "2 columns: {line}");
            assert!(
                parts[0].chars().count() == 1 && parts[1].chars().count() == 1,
                "single char: {line}"
            );
            let cp = parts[0].chars().next().unwrap() as u32;
            assert!(
                (0x3041..=0x3096).contains(&cp) || (0x30A1..=0x30F6).contains(&cp),
                "kana row member: {line}"
            );
        }
    }

    // ── KanaSoundChangesTest ────────────────────────────────────────────────

    /// `parses_the_committed_asset`.
    #[test]
    fn sound_parses_the_committed_asset() {
        let Some(text) = asset(SOUND_ASSET) else {
            return;
        };
        let lines = text
            .lines()
            .filter(|line| {
                let line = line.trim();
                !line.is_empty() && !line.starts_with('#')
            })
            .count();
        assert_eq!(31, lines);
        assert_eq!(31, KanaSoundTable::parse(text).entry_count());
        assert_eq!(31, KanaSoundTable::builtin().entry_count());
    }

    /// `pins_every_shipped_pair`, against both the builtin table and the
    /// parsed asset.
    #[test]
    fn sound_pins_every_shipped_pair() {
        const SHIPPED_HA: &[(char, char)] =
            &[('ふ', 'う'), ('ひ', 'い'), ('へ', 'え'), ('は', 'わ')];
        const SHIPPED_AU: &[(char, char)] = &[
            ('か', 'こ'), ('が', 'ご'), ('さ', 'そ'), ('ざ', 'ぞ'), ('た', 'と'), ('だ', 'ど'),
            ('な', 'の'), ('は', 'ほ'), ('ば', 'ぼ'), ('ぱ', 'ぽ'), ('ゃ', 'ょ'), ('や', 'よ'),
            ('ら', 'ろ'), ('わ', 'お'),
        ];
        const SHIPPED_EU: &[(char, &str)] = &[
            ('え', "よ"), ('け', "きょ"), ('げ', "ぎょ"), ('せ', "しょ"), ('ぜ', "じょ"),
            ('て', "ちょ"), ('で', "じょ"), ('ね', "にょ"), ('へ', "ひょ"), ('べ', "びょ"),
            ('ぺ', "ぴょ"), ('め', "みょ"), ('れ', "りょ"),
        ];
        let mut tables = vec![KanaSoundTable::builtin()];
        if let Some(text) = asset(SOUND_ASSET) {
            tables.push(KanaSoundTable::parse(text));
        }
        for table in &tables {
            for (variant, modern) in SHIPPED_HA {
                assert_eq!(Some(*modern as i32), table.ha(*variant as i32), "ha {variant}");
            }
            for (variant, modern) in SHIPPED_AU {
                assert_eq!(Some(*modern as i32), table.au(*variant as i32), "au {variant}");
            }
            for (variant, modern) in SHIPPED_EU {
                assert_eq!(
                    Some((*modern).to_string()),
                    table.eu(*variant as i32),
                    "eu {variant}"
                );
            }
        }
    }

    /// `pins_the_withheld_rows`.
    #[test]
    fn sound_pins_the_withheld_rows() {
        // あ/ま in the アウ row, ほ in the ハ行 row, and #75's を.
        let mut tables = vec![KanaSoundTable::builtin()];
        if let Some(text) = asset(SOUND_ASSET) {
            tables.push(KanaSoundTable::parse(text));
        }
        for table in &tables {
            assert_eq!(None, table.au('あ' as i32));
            assert_eq!(None, table.au('ま' as i32));
            assert_eq!(None, table.ha('ほ' as i32));
            assert_eq!(None, table.au('を' as i32));
            assert_eq!(None, table.ha('を' as i32));
        }
        // ...so they fold to themselves even when the context would fire.
        assert_eq!("あう", normalise("あう"));
        assert_eq!("まう", normalise("まう"));
        assert_eq!("おほ", normalise("おほ"));
        assert_eq!("本を読む", normalise("本を読む"));
    }

    /// `normalises_the_headline_legacy_forms`.
    #[test]
    fn sound_normalises_the_headline_legacy_forms() {
        // The pairs the issue names, through the shipped composition.
        assert_eq!("よう", normalise("やう"));
        assert_eq!("きょう", normalise("きやう"));
        assert_eq!("きょう", normalise("けふ"));
        assert_eq!("ちょう", normalise("てふ"));
        assert_eq!("思う", normalise("思ふ"));
        assert_eq!("言う", normalise("言ふ"));
        // The neighbouring sound changes the same rows carry.
        assert_eq!("しょう", normalise("しやう"));
        assert_eq!("でしょう", normalise("でせう"));
        assert_eq!("だろう", normalise("だらう"));
        assert_eq!("ありがとう", normalise("ありがたう"));
        assert_eq!("とうとし", normalise("たふとし"));
        assert_eq!("というのは", normalise("といふのは"));
    }

    /// `applies_the_ha_row_only_at_the_grammatical_ending`.
    #[test]
    fn sound_applies_the_ha_row_only_at_the_grammatical_ending() {
        // ハ行四段 終止/連体形: word-final or before a particle.
        assert_eq!("思う", normalise("思ふ"));
        assert_eq!("思うが", normalise("思ふが"));
        assert_eq!("思う人", normalise("思ふ人"));
        assert_eq!("云う", normalise("云ふ"));
        // ...and nowhere else.
        assert_eq!("ふね", normalise("ふね"));
        assert_eq!("ふとん", normalise("ふとん"));
        assert_eq!("吹く", normalise("吹く"));
        assert_eq!("ふうふ", normalise("ふうふ"));
        assert_eq!("ふ", normalise("ふ"));
        // 連用形 ひ -> い before て/つ/な.
        assert_eq!("思いつ", normalise("思ひつ"));
        assert_eq!("戦いながら", normalise("戦ひながら"));
        // ...while a word-initial ひ and the common ひる/ひま are untouched.
        assert_eq!("ひる", normalise("ひる"));
        assert_eq!("ひま", normalise("ひま"));
        assert_eq!("ひが", normalise("ひが"));
        // 連用/仮定/已然 へ -> え.
        assert_eq!("数えて", normalise("数へて"));
        assert_eq!("言えば", normalise("言へば"));
        assert_eq!("添えた", normalise("添へた"));
        assert_eq!("へや", normalise("へや"));
        // 未然形 は -> わ before ず/ぬ/む/ば; the particle は never folds.
        assert_eq!("言わず", normalise("言はず"));
        assert_eq!("思わぬ", normalise("思はぬ"));
        assert_eq!("思わば", normalise("思はば"));
        assert_eq!("本は", normalise("本は"));
        assert_eq!("はな", normalise("はな"));
        assert_eq!("はず", normalise("はず"));
    }

    /// `applies_the_vowel_rows_only_before_u`.
    #[test]
    fn sound_applies_the_vowel_rows_only_before_u() {
        // アウ -> オウ needs the う.
        assert_eq!("かき", normalise("かき"));
        assert_eq!("こう", normalise("かう"));
        assert_eq!("こうして", normalise("かうして"));
        assert_eq!("よう", normalise("やう"));
        assert_eq!("ようだ", normalise("やうだ"));
        assert_eq!("おはよう", normalise("おはやう"));
        // エウ -> ヨウ.
        assert_eq!("きょう", normalise("けう"));
        assert_eq!("ちょう", normalise("てう"));
        assert_eq!("しょう", normalise("せう"));
        // A kanji stem keeps a modern 五段 verb ending out of the rule's reach.
        assert_eq!("買う", normalise("買う"));
        assert_eq!("会う", normalise("会う"));
    }

    /// `is_the_identity_on_modern_text`.
    #[test]
    fn sound_is_the_identity_on_modern_text() {
        for modern in [
            "", "あ", "ABC", "日本語", "つくえ", "にっぽん", "がっこう", "コーヒー", "12月",
            "、。", "つづく", "ちぢむ", "ふね", "コーヒーを飲む", "つくえの上", "クラウン",
        ] {
            assert_eq!(modern, normalise(modern));
        }
    }

    /// `is_deterministic_and_answers_from_the_query_alone`.
    #[test]
    fn sound_is_deterministic_and_answers_from_the_query_alone() {
        let q = "けふ";
        assert_eq!(normalise(q), normalise(q));
        assert_eq!("きょう", normalise(q));
        assert_eq!("きょうは晴れ", normalise("けふは晴れ"));
        assert_eq!("。きょう、", normalise("。けふ、"));
        assert_eq!("きょう", normalise("けふ"));
    }

    /// `empty_table_is_the_identity`.
    #[test]
    fn sound_empty_table_is_the_identity() {
        let table = KanaSoundTable::empty();
        assert_eq!(0, table.entry_count());
        assert_eq!("けふ", table.modernise("けふ".to_string()));
    }

    /// `parse_skips_malformed_lines_and_keeps_the_first_mapping`.
    #[test]
    fn sound_parse_skips_malformed_lines_and_keeps_the_first_mapping() {
        let table = KanaSoundTable::parse(
            "# a comment\n\
             ふ\tう\tha\n\
             ふ\tひ\tha\n\
             ふう\n\
             う\tう\tha\n\
             ふ\tう\tha\textra\n\
             け\tきょ\teu\n\
             け\tきょ\tunknown-row\n"
                .to_string(),
        );
        assert_eq!(2, table.entry_count());
        assert_eq!(Some('う' as i32), table.ha('ふ' as i32));
        assert_eq!(Some("きょ".to_string()), table.eu('け' as i32));
    }

    /// `the_table_is_kana_only_and_rows_are_known`.
    #[test]
    fn sound_the_table_is_kana_only_and_rows_are_known() {
        let Some(text) = asset(SOUND_ASSET) else {
            return;
        };
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let parts: Vec<&str> = line.split('\t').collect();
            assert_eq!(3, parts.len(), "3 columns: {line}");
            assert_eq!(1, parts[0].chars().count(), "single-char variant: {line}");
            let cp = parts[0].chars().next().unwrap() as u32;
            assert!(
                (0x3041..=0x3096).contains(&cp) || (0x30A1..=0x30F6).contains(&cp),
                "kana variant: {line}"
            );
            assert!(["ha", "au", "eu"].contains(&parts[2]), "known row: {line}");
            let modern: Vec<char> = parts[1].chars().collect();
            assert!(
                (1..=2).contains(&modern.len())
                    && modern.iter().all(|c| {
                        let cp = *c as u32;
                        (0x3041..=0x3096).contains(&cp) || (0x30A1..=0x30F6).contains(&cp)
                    }),
                "modern is 1-2 kana: {line}"
            );
        }
    }

    /// `the_lookup_adds_the_normalised_form_beside_the_raw_one` — the call
    /// site keeps the raw, #75 and sound-changed forms in its search set, so
    /// here the three composed strings it pins must be produced.
    #[test]
    fn sound_the_lookup_adds_the_normalised_form_beside_the_raw_one() {
        assert_eq!("きゃう", ortho_modernise("きやう"));
        assert_eq!("きょう", normalise("きやう"));
        assert_eq!("きょうは", normalise("けふは"));
    }
}
