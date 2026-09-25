//! UniFFI shim over `jpdict_core::util::japanese` (#44, #49, #55, #56, #63) —
//! **WP-11a, the `JapaneseUtil` core, the OCR sizing helpers and the furigana
//! aligner.**
//!
//! The OCR-text normalisation stages (width conversion → lookup-variant fold →
//! combining characters), the half-width/em sizing helpers, the
//! vertical-punctuation substitutions, the KANJIDIC reading split and the
//! dictionary-reading aligner have one source of truth in
//! the PC crate now. This file is only the boundary: module-prefixed free
//! functions plus the [`RubySegment`] record. See `char_lm.rs` for the `char` ↔
//! `i32` convention this follows; nothing here implements a rule — the fold
//! tables, their direction guards and the alignment search live upstream.
//!
//! Boundary losses:
//!
//! * **`char` becomes `i32`.** Invalid code points (negative, surrogate, above
//!   U+10FFFF) degrade to U+FFFD rather than panicking at the FFI boundary, the
//!   rule `char_lm.rs` set. A Kotlin `Char` holding a lone surrogate is
//!   therefore no longer returned unchanged by `verticalPunctuationChar`; real
//!   callers never pass one.
//! * **Strings cross owned, and count code points.** The algorithms index by
//!   Unicode scalar value where the old Kotlin indexed UTF-16 units: identical
//!   for BMP text (everything the recogniser emits), divergent only when a
//!   supplementary-plane character precedes the position being folded or
//!   measured.
//! * **Tables do not cross.** `MEASURED_VARIANT_FOLD` is a private
//!   `lazy_static` upstream with no public accessor, so the shim cannot
//!   re-export it. The Kotlin facade keeps its pre-conversion literal as a
//!   **test-only mirror** that
//!   `JapaneseUtilVariantFoldTest.measured_variant_fold_matches_the_committed_asset`
//!   reads; production code does not read it, and `foldLookupVariants` is
//!   Rust's. A PC-side `pub` accessor would let the shim export the table and
//!   delete the mirror.
//!
//! ## Kotlin facade contract
//!
//! The JapaneseUtil portion of the generated Kotlin surface
//! (`uniffi.nav_graph_core.japaneseNormalize`, `japaneseFoldLookupVariants`,
//! `japaneseKatakanaToHiragana`, `japaneseCollapseEmphatic`,
//! `japaneseVerticalPunctuation`, `japaneseVerticalPunctuationChar`,
//! `japaneseSplitKanaList`, `japaneseAlignFurigana`, record `RubySegment`)
//! is wrapped by the hand-written
//! `com.holopengin.instantjpdict.util.JapaneseUtil` object and
//! `...util.FuriganaAligner` object, which keep the pre-conversion API
//! byte-for-byte (`normalize`, `verticalPunctuation`,
//! `verticalPunctuationChar`, `splitKanaList`, `foldLookupVariants`,
//! `katakanaToHiragana`, `collapseEmphatic`; `FuriganaAligner.align` /
//! `Segment`) so no call site or test changes. The two sizing exports,
//! `japaneseIsHalfWidth` and `japaneseEstimateEm`, are consumed by the
//! hand-written `OcrEngine` facade. All facades absorb their `Char` ↔ `Int`
//! conversion at the boundary.

use jpdict_core::util::japanese as pc;

/// Decode one Kotlin `Int` code point into a Rust `char`.
///
/// The Kotlin side always sends valid `Char.code` values; anything else (a
/// negative, a surrogate, above U+10FFFF) degrades to U+FFFD rather than
/// panicking at the FFI boundary, matching `char_lm.rs`.
fn char_from_codepoint(codepoint: i32) -> char {
    u32::try_from(codepoint)
        .ok()
        .and_then(char::from_u32)
        .unwrap_or('\u{FFFD}')
}

/// One furigana run: `base` surface text with optional `ruby` above it.
///
/// Field-for-field the PC `jpdict_core::util::japanese::RubySegment`; the
/// Kotlin facade maps it onto `FuriganaAligner.Segment`.
#[derive(Clone, Debug, uniffi::Record)]
pub struct RubySegment {
    /// The surface text the run covers (a kanji span, or kana literal).
    pub base: String,
    /// The reading above a kanji span; `None` for an okurigana/kana run.
    pub ruby: Option<String>,
}

/// Fold an OCR line to the form dictionary lookup expects: width/combining
/// normalisation plus the #44 lookup-variant folds (iteration kana, obsolete
/// kana, Roman numerals, the Chinese-only forms the recogniser emits).
///
/// The stage order is part of the contract: width conversion first, then the
/// variant fold, then combining-character normalization. Query-side only.
#[uniffi::export]
pub fn japanese_normalize(text: String) -> String {
    pc::normalize(&text)
}

/// Fold the variant characters of `LOOKUP_VARIANT_MAP` and expand the iteration
/// marks `ゝ`/`ゞ`/`ヽ`/`ヾ`, which repeat the preceding kana of their script.
///
/// Query-side only: callers keep using the raw text for display and for the
/// prefix lengths a match corresponds to, so a fold may change the query's
/// length without affecting what is shown.
#[uniffi::export]
pub fn japanese_fold_lookup_variants(text: String) -> String {
    pc::fold_lookup_variants(&text)
}

/// One pair of the Unihan-derived fold table.
#[derive(Clone, Debug, uniffi::Record)]
pub struct VariantPair {
    /// The variant the fold replaces.
    pub variant: i32,
    /// Its canonical (single-character) target.
    pub canonical: String,
}

/// The Unihan-derived half of the lookup-variant fold, as data.
///
/// The mobile drift guard checks this table against the committed
/// `variants/kanji_variants.txt` asset; it used to carry its own 165-entry copy
/// for that check. UniFFI cannot carry a map, so the pairs cross as a record
/// list (ordered by variant for a stable boundary).
#[uniffi::export]
pub fn japanese_measured_variant_fold() -> Vec<VariantPair> {
    pc::measured_variant_fold()
        .into_iter()
        .map(|(variant, canonical)| VariantPair {
            variant: variant as i32,
            canonical: canonical.to_string(),
        })
        .collect()
}

/// Convert katakana to hiragana, resolving a prolonged sound mark `ー` against
/// the character it follows (`カード` → `かあど`).
#[uniffi::export]
pub fn japanese_katakana_to_hiragana(text: String) -> String {
    pc::katakana_to_hiragana(&text)
}

/// Collapse consecutive emphatic characters (`っ`, `ッ`, `ー`, `～`):
/// `すごーーい` → `すごーい`.
#[uniffi::export]
pub fn japanese_collapse_emphatic(text: String) -> String {
    pc::collapse_emphatic(&text)
}

/// Vertical-line punctuation (#56, #63): PP-OCR emits ASCII `?` where JP text
/// wants fullwidth `？`, and horizontal `…`/`‥` where vertical text wants the
/// vertical presentation forms `︙`/`︰`. Applied at emit time for vertical
/// lines only; lookup-safe, because [`japanese_normalize`] folds them back.
#[uniffi::export]
pub fn japanese_vertical_punctuation(text: String) -> String {
    pc::vertical_punctuation(&text)
}

/// The single-character map behind [`japanese_vertical_punctuation`].
#[uniffi::export]
pub fn japanese_vertical_punctuation_char(c: i32) -> i32 {
    pc::vertical_punctuation_char(char_from_codepoint(c)) as i32
}

/// Batched [`japanese_vertical_punctuation_char`]: one crossing for a whole
/// line's alternatives. Raw-alternative lists hold every CTC timestep × top-K,
/// so per-character calls crossed the boundary thousands of times per line.
#[uniffi::export]
pub fn japanese_vertical_punctuation_chars(chars: Vec<i32>) -> Vec<i32> {
    chars
        .iter()
        .map(|&c| pc::vertical_punctuation_char(char_from_codepoint(c)) as i32)
        .collect()
}

/// Whether `ch` has the half-width advance used by the OCR line-layout helpers.
///
/// Delegates to `jpdict_core::util::japanese::is_half_width`; ASCII and
/// half-width katakana are classified as half-width, while Japanese and
/// full-width characters are not.
#[uniffi::export]
pub fn japanese_is_half_width(ch: i32) -> bool {
    pc::is_half_width(char_from_codepoint(ch))
}

/// Estimate the line's em from width-normalized center-to-center pitches.
///
/// Delegates to `jpdict_core::util::japanese::estimate_em`; the result is `0.0`
/// when the text and center list do not contain at least two usable pitches.
#[uniffi::export]
pub fn japanese_estimate_em(text: String, centers: Vec<f32>) -> f32 {
    pc::estimate_em(&text, &centers)
}

/// Split a KANJIDIC kana list ("きみ -ぎみ", "クン キン") into readings: entries
/// are whitespace-separated, a leading ASCII hyphen marks an okurigana-less
/// stem and is stripped, empty entries are dropped.
#[uniffi::export]
pub fn japanese_split_kana_list(raw: String) -> Vec<String> {
    pc::split_kana_list(&raw)
}

/// Align a dictionary reading against its headword so ruby is shown only over
/// kanji spans, with okurigana/kana rendered as plain base text (#55).
///
/// `None` when the reading cannot be unambiguously aligned — callers must fall
/// back to full-reading ruby rendering.
#[uniffi::export]
pub fn japanese_align_furigana(term: String, reading: String) -> Option<Vec<RubySegment>> {
    pc::align_furigana(&term, &reading).map(|segments| {
        segments
            .into_iter()
            .map(|s| RubySegment {
                base: s.base,
                ruby: s.ruby,
            })
            .collect()
    })
}

#[cfg(test)]
mod tests {
    //! Mirror of the JVM suites the swap must keep green
    //! (`JapaneseUtilVariantFoldTest` 20, `JapaneseUtilVerticalPunctuationTest`
    //! 6, `JapaneseUtilVerticalEllipsisTest` 7, `FuriganaAlignerTest` 12,
    //! `UniformEmTest` 6), run through the exported surface — not the upstream
    //! module — so the shim's `char` ↔ `i32` conversion and string handoff are
    //! covered too. The PC module's own tests pin the same rules upstream;
    //! these are the JVM rows, plus two boundary tests the JVM cannot express.

    use super::*;
    use std::collections::{HashMap, HashSet};

    fn norm(text: &str) -> String {
        japanese_normalize(text.to_string())
    }

    fn fold(text: &str) -> String {
        japanese_fold_lookup_variants(text.to_string())
    }

    fn vertical(text: &str) -> String {
        japanese_vertical_punctuation(text.to_string())
    }

    fn vertical_char(c: char) -> char {
        char_from_codepoint(japanese_vertical_punctuation_char(c as i32))
    }

    fn split(raw: &str) -> Vec<String> {
        japanese_split_kana_list(raw.to_string())
    }

    /// `align` as a comparable row list: the record fields the Kotlin facade
    /// consumes, in run order.
    fn segments(term: &str, reading: &str) -> Option<Vec<(String, Option<String>)>> {
        japanese_align_furigana(term.to_string(), reading.to_string())
            .map(|runs| runs.into_iter().map(|s| (s.base, s.ruby)).collect())
    }

    /// Expected rows in the same shape.
    fn want(rows: &[(&str, Option<&str>)]) -> Vec<(String, Option<String>)> {
        rows.iter()
            .map(|&(base, ruby)| (base.to_string(), ruby.map(str::to_string)))
            .collect()
    }

    // ── JapaneseUtilVariantFoldTest ─────────────────────────────────────────

    #[test]
    fn expands_hiragana_iteration_mark() {
        assert_eq!(fold("こゝろ"), "こころ");
        assert_eq!(fold("こゝ"), "ここ");
        assert_eq!(fold("あゝ"), "ああ");
    }

    #[test]
    fn expands_voiced_iteration_mark() {
        assert_eq!(fold("たゞ"), "ただ");
        assert_eq!(fold("かゞ"), "かが");
        assert_eq!(fold("はゞ"), "はば");
        // no voiced form (or already voiced): the mark is still a plain repeat
        assert_eq!(fold("まゞ"), "まま");
        assert_eq!(fold("がゞ"), "がが");
        assert_eq!(fold("ナヾ"), "ナナ");
    }

    #[test]
    fn expands_katakana_iteration_marks() {
        assert_eq!(fold("カヽ"), "カカ");
        assert_eq!(fold("カヾ"), "カガ");
        assert_eq!(fold("ハヾ"), "ハバ");
    }

    #[test]
    fn repeats_run_of_marks() {
        assert_eq!(fold("こゝゝ"), "こここ");
    }

    #[test]
    fn leaves_mark_that_cannot_be_repeated() {
        // line-initial, after a kanji, after punctuation
        assert_eq!(fold("ゝあ"), "ゝあ");
        assert_eq!(fold("日ゝ"), "日ゝ");
        assert_eq!(fold("、ゝ"), "、ゝ");
        assert_eq!(fold("。ヾ"), "。ヾ");
        // iteration marks do not cross scripts
        assert_eq!(fold("カゝ"), "カゝ");
        assert_eq!(fold("あヽ"), "あヽ");
    }

    #[test]
    fn folds_obsolete_kana_the_head_can_emit() {
        assert_eq!(fold("こゑ"), "こえ");
        assert_eq!(fold("ヰロ"), "イロ");
    }

    #[test]
    fn folds_roman_numerals() {
        assert_eq!(fold("Ⅶ"), "VII");
        assert_eq!(fold("Ⅷ"), "VIII");
        assert_eq!(fold("第Ⅻ章"), "第XII章");
        assert_eq!(fold("ⅸ"), "ix");
    }

    #[test]
    fn folds_compatibility_and_chinese_only_forms() {
        assert_eq!(fold("20℃"), "20°C");
        assert_eq!(fold("状况"), "状況");
        // 查 folds (emittable) while 调 does not (we pruned it) — same word, and
        // only the emittable half of the pair is worth an entry
        assert_eq!(fold("调查④"), "调査④");
    }

    #[test]
    fn does_not_fold_characters_we_pruned_ourselves() {
        // 调 has no Unihan Japanese reading, so prune_ctc_head cut it: the head
        // cannot emit it, and an entry would be dead code. Emittability is
        // checked against rec_remap.txt, never vocab.json.
        assert_eq!(fold("调"), "调");
    }

    #[test]
    fn leaves_kanji_iteration_mark_alone() {
        // dictionary headwords contain 々 (日々), so expanding would lose matches
        assert_eq!(fold("日々"), "日々");
        assert_eq!(norm("日々"), "日々");
    }

    #[test]
    fn folds_measured_unihan_variants() {
        // the pair the direction rule was validated on, and the corpus's most
        // frequent variant forms
        assert_eq!(fold("囘"), "回");
        assert_eq!(fold("欝"), "鬱");
        // frequency guard: 壜 is the *commoner* side, so folding it would
        // rewrite a resolvable query into a dead one
        assert_eq!(fold("壜"), "壜");
        assert_eq!(fold("劒"), "劍");
        assert_eq!(fold("慙"), "慚");
        // and inside a word, which is how the fold is actually reached
        assert_eq!(fold("囘想"), "回想");
        assert_eq!(fold("欝々"), "鬱々");
        assert_eq!(fold("慙愧"), "慚愧");
        assert_eq!(fold("迯げる"), "逃げる");
        assert_eq!(fold("噐械"), "器械");
        assert_eq!(fold("迯"), "逃");
    }

    #[test]
    fn unihan_fold_picks_the_corpus_dominant_canonical() {
        // several variants have multiple canonical candidates in Unihan; the
        // fold takes the form that dominates the corpus, not an arbitrary first
        // (葢 -> 蓋; 悋 -> 吝; 冫 -> 氷)
        assert_eq!(fold("葢"), "蓋");
        assert_eq!(fold("悋"), "吝");
        assert_eq!(fold("冫"), "氷");
        assert_eq!(fold("秇"), "藝");
        assert_eq!(fold("﨑"), "崎");
    }

    #[test]
    fn unihan_fold_does_not_run_backwards() {
        // the canonical side is what the dictionary already keys on: folding it
        // would move the query to a form the recogniser cannot emit
        assert_eq!(fold("回"), "回");
        assert_eq!(fold("鬱"), "鬱");
        assert_eq!(fold("蓋"), "蓋");
        assert_eq!(norm("回想"), "回想");
    }

    #[test]
    fn unihan_fold_is_idempotent_and_leaves_unknown_characters_alone() {
        let plain = "日本語のテキストです。";
        assert_eq!(fold(plain), plain);
        for s in ["囘想", "欝々", "迯げる", "噐械", "壜", "﨑", "囘囘回"] {
            let once = norm(s);
            assert_eq!(norm(&once), once, "not idempotent for {s:?}");
        }
    }

    /// Mirrors mobile `measured_variant_fold_matches_the_committed_asset`.
    ///
    /// The upstream table is private (a `lazy_static` with no `pub`
    /// accessor), so the shim cannot re-export it and this test recovers it
    /// from the exported fold instead: scanning the BMP for characters whose
    /// single-character fold changes yields exactly the measured half (the
    /// curated multi-character rows — Roman numerals, ℃ — are dropped by the
    /// single-character filter, and the four curated single-character rows are
    /// excluded by name). Every recovered pair must then exist in
    /// `variants/kanji_variants.txt` in the same direction, and no canonical
    /// may itself be a fold key.
    #[test]
    fn measured_variant_fold_matches_the_committed_asset() {
        let path = std::path::Path::new(concat!(env!("CARGO_MANIFEST_DIR")))
            .join("../app/src/main/assets/variants/kanji_variants.txt");
        let Ok(text) = std::fs::read_to_string(&path) else {
            eprintln!("skipping: {} not present", path.display());
            return;
        };
        let mut asset: HashMap<char, HashSet<char>> = HashMap::new();
        for line in text.lines() {
            if line.trim().is_empty() || line.starts_with('#') {
                continue;
            }
            let parts: Vec<&str> = line.split('\t').collect();
            if parts.len() != 2 || parts[0].chars().count() != 1 || parts[1].chars().count() != 1 {
                continue;
            }
            asset
                .entry(parts[0].chars().next().unwrap())
                .or_default()
                .insert(parts[1].chars().next().unwrap());
        }
        assert_eq!(600, asset.len());

        // The curated entries of `LOOKUP_VARIANT_MAP` whose target is a single
        // character: the six Roman numerals that fold to one ASCII letter and
        // the four kana/Chinese folds. The measured half never reuses one as a
        // key.
        let curated_single: HashSet<char> = "ⅠⅤⅩⅰⅴⅹゑヰ况查".chars().collect();
        let mut measured: Vec<(char, char)> = Vec::new();
        for cp in 0..=0xFFFFu32 {
            let Some(variant) = char::from_u32(cp) else {
                continue;
            };
            let folded = japanese_fold_lookup_variants(variant.to_string());
            let mut target = folded.chars();
            let (Some(canonical), None) = (target.next(), target.next()) else {
                continue;
            };
            if canonical == variant || curated_single.contains(&variant) {
                continue;
            }
            measured.push((variant, canonical));
        }
        // A supplementary-plane row would be invisible to this BMP scan; the
        // count assertion then reports that the scan (or the exclusion list)
        // needs extending rather than passing silently.
        assert_eq!(165, measured.len());
        let keys: HashSet<char> = measured.iter().map(|&(variant, _)| variant).collect();
        for (variant, target) in &measured {
            let candidates = asset
                .get(variant)
                .unwrap_or_else(|| panic!("'{variant}' is not in kanji_variants.txt"));
            assert!(
                candidates.contains(target),
                "asset has '{variant}' -> {candidates:?}, not '{target}'"
            );
            assert!(
                !keys.contains(target),
                "canonical '{target}' is itself a fold key — fold would chain"
            );
        }
    }

    #[test]
    fn folds_the_jmdict_half_and_leaves_modern_forms_alone() {
        // Old orthography the head can emit — both forms are in the vocab,
        // which is exactly why the original direction rule dropped these pairs.
        // The queried old form resolves to the headword a dictionary indexes.
        assert_eq!(fold("摑"), "掴");
        assert_eq!(fold("國"), "国");
        assert_eq!(fold("會"), "会");
        assert_eq!(fold("燈"), "灯");
        // The modern side is a key for nothing, so it must resolve to itself:
        // folding it would rewrite the form the dictionaries actually index.
        assert_eq!(fold("掴"), "掴");
        assert_eq!(fold("国"), "国");
        // A chain (冩 -> 寫 -> 写) reaches the terminal form in one pass.
        assert_eq!(fold("冩"), "写");
        // 坂 is NOT folded to 阪: the corpus prefers 坂 (大阪), so that pair
        // failed the direction guard and stays out of the fold.
        assert_eq!(fold("坂"), "坂");
    }

    #[test]
    fn normalize_applies_the_fold() {
        assert_eq!(norm("こゝろ"), "こころ");
        assert_eq!(norm("たゞ"), "ただ");
        assert_eq!(norm("Ⅶ"), "VII");
        assert_eq!(norm("状况"), "状況");
    }

    #[test]
    fn halfwidth_kana_is_widened_before_folding() {
        // order matters: convert_width runs first, so the mark sees fullwidth カ
        assert_eq!(norm("ｶヽ"), "カカ");
    }

    #[test]
    fn fold_is_idempotent_and_noop_on_plain_text() {
        let plain = "日本語のテキストです。";
        assert_eq!(fold(plain), plain);
        assert_eq!(norm(plain), plain);
        for s in ["こゝろ", "たゞ", "Ⅶ", "状况", "カヾ"] {
            let once = norm(s);
            assert_eq!(norm(&once), once);
        }
    }

    #[test]
    fn existing_normalize_behaviour_is_unchanged() {
        // fullwidth ASCII folds to ASCII, ideographic space to space, vertical
        // presentation forms back to their horizontal forms
        assert_eq!(norm("？"), "?");
        assert_eq!(norm("Ａ１"), "A1");
        assert_eq!(norm("あ︙"), "あ…");
        assert_eq!(norm("あ　い"), "あ い");
    }

    /// The composed stage order the fold rows above depend on — width
    /// conversion → lookup-variant fold → combining-character normalization.
    /// Not a JVM row (the PC port added it), but this is where the order is
    /// observable through the shim.
    #[test]
    fn normalize_stages_run_in_pipeline_order() {
        // Width conversion first: the mark sees the widened カ, not ｶ.
        assert_eq!(norm("ｶﾞヾ"), "ガガ");
        // Fold before combining normalization: the mark sees the combining
        // dakuten, so it is left alone rather than voicing the repeat. Were
        // combining normalized first, this would read がが.
        assert_eq!(norm("か\u{3099}ゞ"), "がゞ");
        // Combining normalization runs last: the decomposed pair composes.
        assert_eq!(norm("は\u{309A}"), "ぱ");
    }

    // ── JapaneseUtilVerticalPunctuationTest ────────────────────────────────

    #[test]
    fn replaces_ascii_question() {
        assert_eq!(vertical("か?"), "か？");
    }

    #[test]
    fn replaces_all_occurrences_leaves_rest() {
        assert_eq!(vertical("?なに?これ"), "？なに？これ");
        assert_eq!(vertical("あ！?"), "あ！？");
    }

    #[test]
    fn idempotent_and_noop_without_ascii() {
        assert_eq!(vertical("か？"), "か？");
        assert_eq!(vertical(""), "");
    }

    #[test]
    fn char_mapping() {
        assert_eq!(vertical_char('?'), '？');
        assert_eq!(vertical_char('？'), '？');
        assert_eq!(vertical_char('あ'), 'あ');
        assert_eq!(vertical_char('!'), '!');
    }

    #[test]
    fn split_kana_list() {
        assert_eq!(split("きみ -ぎみ"), vec!["きみ", "ぎみ"]);
        assert_eq!(split("クン キン"), vec!["クン", "キン"]);
        assert_eq!(split("クン"), vec!["クン"]);
        assert_eq!(split(""), Vec::<String>::new());
        assert_eq!(split("   "), Vec::<String>::new());
    }

    // ── OcrEngine half-width and uniform-em tests ─────────────────────────

    /// Mirrors mobile `OcrEngine.isHalfWidth` through the exported surface.
    #[test]
    fn halfwidth_classification_matches_mobile() {
        assert!(japanese_is_half_width('A' as i32));
        assert!(japanese_is_half_width('~' as i32));
        assert!(japanese_is_half_width('\u{FF76}' as i32)); // ｶ halfwidth katakana
        assert!(!japanese_is_half_width('あ' as i32));
        assert!(!japanese_is_half_width('漢' as i32));
        assert!(!japanese_is_half_width('。' as i32));
    }

    /// Mirrors mobile `UniformEmTest`: true em 20px in every estimable case.
    #[test]
    fn estimate_em_normalizes_halfwidth_advances() {
        let assert_em = |got: f32| assert!((got - 20.0).abs() < 1e-3, "em={got}");
        assert_em(japanese_estimate_em(
            "AB日本CD".to_string(),
            vec![5.0, 15.0, 30.0, 50.0, 65.0, 75.0],
        ));
        assert_em(japanese_estimate_em(
            "日本語".to_string(),
            vec![10.0, 30.0, 50.0],
        ));
        assert_em(japanese_estimate_em(
            "ABCD".to_string(),
            vec![5.0, 15.0, 25.0, 35.0],
        ));
        assert_em(japanese_estimate_em("ＡＢ".to_string(), vec![10.0, 30.0]));
        assert_em(japanese_estimate_em("ｱｲ".to_string(), vec![5.0, 15.0]));
        assert_eq!(japanese_estimate_em("あ".to_string(), vec![10.0]), 0.0);
        assert_eq!(japanese_estimate_em(String::new(), Vec::new()), 0.0);
        assert_eq!(japanese_estimate_em("あい".to_string(), vec![10.0]), 0.0);
    }

    #[test]
    fn lookup_safe_normalize_folds_back() {
        // Dictionary lookup runs normalize(), which folds ？ back to ? —
        // so the substitution must not change lookup results.
        assert_eq!(norm("か?"), norm(&vertical("か?")));
    }

    // ── JapaneseUtilVerticalEllipsisTest ───────────────────────────────────

    #[test]
    fn replaces_horizontal_ellipsis() {
        assert_eq!(vertical("あ…"), "あ︙");
    }

    #[test]
    fn replaces_two_dot_leader() {
        assert_eq!(vertical("あ‥"), "あ︰");
    }

    #[test]
    fn replaces_all_occurrences_leaves_rest_ellipsis() {
        assert_eq!(vertical("…なに…これ"), "︙なに︙これ");
        assert_eq!(vertical("あ…?"), "あ︙？");
    }

    #[test]
    fn ascii_periods_untouched() {
        // Runs of ASCII periods are deliberately NOT folded: an N:1 fold
        // would break char-box/alternative alignment.
        assert_eq!(vertical("あ..."), "あ...");
        assert_eq!(vertical("あ."), "あ.");
    }

    #[test]
    fn idempotent_and_noop() {
        assert_eq!(vertical("あ︙"), "あ︙");
        assert_eq!(vertical("あ︰"), "あ︰");
        assert!(!vertical("あ…").contains('…'));
        assert_eq!(vertical(""), "");
    }

    #[test]
    fn char_mapping_ellipsis() {
        assert_eq!(vertical_char('…'), '︙');
        assert_eq!(vertical_char('︙'), '︙');
        assert_eq!(vertical_char('‥'), '︰');
        assert_eq!(vertical_char('︰'), '︰');
        assert_eq!(vertical_char('.'), '.');
        assert_eq!(vertical_char('あ'), 'あ');
    }

    #[test]
    fn lookup_safe_normalize_folds_back_ellipsis() {
        // Dictionary lookup runs normalize(), which must fold the vertical
        // forms back — the substitution must not change lookup results.
        assert_eq!(norm("あ…"), norm(&vertical("あ…")));
        assert_eq!(norm("あ‥"), norm(&vertical("あ‥")));
        assert_eq!(norm("あ︙"), "あ…");
        assert_eq!(norm("あ︰"), "あ‥");
    }

    // ── FuriganaAlignerTest ────────────────────────────────────────────────

    #[test]
    fn okurigana_trailing() {
        assert_eq!(
            segments("食べる", "たべる"),
            Some(want(&[("食", Some("た")), ("べる", None)]))
        );
    }

    #[test]
    fn okurigana_split_stem() {
        assert_eq!(
            segments("大きい", "おおきい"),
            Some(want(&[("大", Some("おお")), ("きい", None)]))
        );
    }

    #[test]
    fn infixed_kana() {
        assert_eq!(
            segments("申し込む", "もうしこむ"),
            Some(want(&[
                ("申", Some("もう")),
                ("し", None),
                ("込", Some("こ")),
                ("む", None),
            ]))
        );
    }

    #[test]
    fn leading_kana() {
        assert_eq!(
            segments("お母さん", "おかあさん"),
            Some(want(&[("お", None), ("母", Some("かあ")), ("さん", None),]))
        );
    }

    #[test]
    fn all_kanji_whole_ruby() {
        assert_eq!(
            segments("今日", "きょう"),
            Some(want(&[("今日", Some("きょう"))]))
        );
    }

    #[test]
    fn kana_only_plain() {
        assert_eq!(
            segments("たべる", "たべる"),
            Some(want(&[("たべる", None)]))
        );
    }

    /// Mirrors mobile `katakanaReading_normalized`.
    ///
    /// The upstream `katakana_to_hiragana` u8-truncation bug this row found was
    /// fixed on the PC side (commit `8b1b957`), so it asserts the real contract.
    #[test]
    fn katakana_reading_normalized() {
        assert_eq!(
            japanese_katakana_to_hiragana("タベル".to_string()),
            "たべる"
        );
        assert_eq!(
            segments("食べる", "タベル"),
            Some(want(&[("食", Some("タ")), ("べる", None)]))
        );
    }

    #[test]
    fn iteration_mark_stays_in_kanji_run() {
        assert_eq!(
            segments("人々", "ひとびと"),
            Some(want(&[("人々", Some("ひとびと"))]))
        );
    }

    #[test]
    fn reading_too_short_returns_none() {
        assert!(segments("食べる", "たべ").is_none());
    }

    #[test]
    fn reading_too_long_returns_none() {
        assert!(segments("食べる", "たべるる").is_none());
    }

    #[test]
    fn anchor_missing_returns_none() {
        assert!(segments("食べる", "たばさ").is_none());
    }

    #[test]
    fn empty_returns_none() {
        assert!(segments("", "たべる").is_none());
        assert!(segments("食べる", "").is_none());
    }

    // ── Boundary (not expressible in the JVM suites) ───────────────────────

    /// The batched map is exactly the per-character map (same order, same
    /// degradation), so the recognition path can normalise a whole line in one
    /// crossing without changing the result.
    #[test]
    fn vertical_punctuation_batch_matches_the_single_char_map() {
        let chars: Vec<i32> = "あ?…‥A。︙︰？、".chars().map(|c| c as i32).collect();
        let batched = japanese_vertical_punctuation_chars(chars.clone());
        let per_char: Vec<i32> = chars
            .iter()
            .map(|&c| japanese_vertical_punctuation_char(c))
            .collect();
        assert_eq!(batched, per_char);
        assert!(japanese_vertical_punctuation_chars(Vec::new()).is_empty());
        // Invalid code points degrade per entry, not for the whole batch.
        assert_eq!(
            char_from_codepoint(japanese_vertical_punctuation_chars(vec![-1, '?' as i32])[0]),
            '\u{FFFD}'
        );
    }

    /// The exported measured-fold table is the exact data the fold reads: 165
    /// single-character pairs, in stable variant order. The mobile drift guard
    /// compares this against the committed asset instead of carrying its own
    /// copy.
    #[test]
    fn measured_variant_fold_matches_the_rust_table() {
        let pairs = japanese_measured_variant_fold();
        assert_eq!(pairs.len(), 165);
        let map: HashMap<i32, String> = pairs
            .iter()
            .map(|p| (p.variant, p.canonical.clone()))
            .collect();
        for (variant, canonical) in pc::measured_variant_fold().iter() {
            assert_eq!(
                map.get(&(*variant as i32)).map(String::as_str),
                Some(*canonical),
                "{variant}"
            );
        }
        // Stable order and single-character targets.
        let mut variants: Vec<i32> = pairs.iter().map(|p| p.variant).collect();
        let sorted = variants.clone();
        variants.sort_unstable();
        assert_eq!(variants, sorted);
        assert!(pairs.iter().all(|p| p.canonical.chars().count() == 1));
        assert_eq!(map.get(&('囘' as i32)).map(String::as_str), Some("回"));
    }

    /// Invalid code points degrade to U+FFFD at the boundary rather than
    /// panicking, and a supplementary-plane character crosses as one scalar.
    #[test]
    fn invalid_code_points_degrade_to_the_replacement_character() {        for cp in [-1, 0xD800, 0x11_0000] {
            assert_eq!(
                char_from_codepoint(japanese_vertical_punctuation_char(cp)),
                '\u{FFFD}',
                "vertical_punctuation_char({cp})"
            );
        }
        // A supplementary-plane character is one scalar here; the Kotlin facade
        // never sends one, because its `Char` API cannot express it.
        assert_eq!(fold("𠮟"), "𠮟");
    }
}
