//! Adversarial input for every text-taking export in the shim.
//!
//! The two untrusted sources are the OCR path (recogniser output, which can be
//! anything the pixels imply) and the accessibility-tree path (`text` and
//! `contentDescription` off arbitrary apps — huge, malformed, adversarial
//! Unicode, pathological content). Both reach Rust through this crate's UniFFI
//! surface. This module drives every one of those entry points with a hostile
//! corpus and asserts **no export panics**.
//!
//! A panic here is a counterexample. UniFFI 0.28 wraps every export in
//! `panic::catch_unwind` (`uniffi_core`'s `rust_call`), so a caught Rust panic
//! becomes an `InternalException` at the Kotlin boundary rather than a process
//! abort — but it is still a point the resulting text can crash, so the corpus
//! exists to find them. `catch_unwind` is also what lets one counterexample be
//! *reported* instead of aborting the run before the others are tried.
//!
//! Nothing here is a correctness claim beyond "does not panic": return values
//! are not asserted, only that the call returns.
//!
//! The corpus is bounded by construction (a few MiB total), so a **stack
//! overflow** from text-length recursion would still abort the test process
//! rather than fail the test — deliberate evidence that none of these exports
//! recurses with a depth scaling on text length (the deinflection worklist is
//! iterative).

use crate::*;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

/// A deterministic PRNG (xorshift64), so the corpus is reproducible without a
/// dependency.
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
    fn below(&mut self, n: usize) -> usize {
        if n == 0 {
            0
        } else {
            (self.next() % n as u64) as usize
        }
    }
}

/// Text lengths that bracket the access-point boundaries: empty, one, the
/// accessibility reader's `MAX_TEXT` cap (1000), and one past it. 1001 is the
/// longest string the app can reach Rust (the reader drops a node longer than
/// `MAX_TEXT`; an OCR line is bounded by its timestep count), so this is the
/// real upper bound the crash argument has to cover. Bigger inputs live in
/// `text_exports_survive_a_very_long_input`, `#[ignore]`d because a 100k-char
/// string through an unoptimised `japanese_normalize` costs minutes and proves
/// nothing the 1001-char case does not.
const LENGTHS: &[usize] = &[0, 1, 2, 3, 7, 63, 255, 999, 1000, 1001];

/// The adversarial alphabet: ASCII, BMP Japanese, halfwidth kana, combining
/// marks, control characters, bidi controls, astral pairs, and characters the
/// app's own folds and tables treat specially.
fn hostile_units() -> Vec<&'static str> {
    vec![
        "a", "Z", "0", " ", "\n", "\r", "\t", "\u{0}", "\u{7f}", "\u{80}", "\u{85}",
        "あ", "ん", "っ", "ゃ", "ゝ", "ゞ", "ゟ", "ゑ", "ヰ", "ヽ", "ヾ",
        "漢", "々", "〆", "〇", "𠮟", "𩸽", "𠀋",
        "ｶ", "ﾞ", "ﾟ", "｡", "｢", "｣", "､",
        "\u{3099}", "\u{309A}", "\u{0344}", "\u{200D}", "\u{FEFF}", "\u{200B}",
        "\u{202E}", "\u{202D}", "\u{2028}", "\u{2029}",
        "Ⅰ", "Ⅻ", "ⅸ", "℃", "査", "况", "調", "〜", "ー", "～", "‼", "⁉",
        "…", "‥", "︙", "︰", "？", "！", "。", "、", "\u{3000}",
        "🈁", "👍", "🏳️\u{200D}🌈", "\u{1F1EF}\u{1F1F5}",
    ]
}

/// One hostile string of approximately `len` characters.
fn hostile_string(rng: &mut Rng, len: usize) -> String {
    let units = hostile_units();
    let mut s = String::with_capacity(len + 4);
    while s.chars().count() < len {
        s.push_str(units[rng.below(units.len())]);
    }
    s
}

/// Every hostile string in the corpus, deterministic across runs.
fn corpus() -> Vec<String> {
    let mut out = Vec::new();
    let mut rng = Rng(0x1234_5678_9abc_def0);
    for &len in LENGTHS {
        for _ in 0..2 {
            out.push(hostile_string(&mut rng, len));
        }
    }
    out.push("\u{0}".repeat(1001));
    out.push("\n".repeat(1001));
    out.push("\u{3099}".repeat(1001)); // combining marks with no base
    out.push("っ".repeat(1001));
    out.push("て".repeat(1001));
    out.push("e\u{301}".repeat(1001));
    out.push("𠮟".repeat(1001));
    out.push("ゝ".repeat(1001));
    out.push("ｶﾞ".repeat(1001));
    out.push("。".repeat(1001));
    out.push("Ⅰ".repeat(1001));
    out.push("\u{1F1EF}".repeat(1001) + &"\u{1F1F5}".repeat(1001));
    out
}

/// Run `f`, recording `label` in `failures` if it panics and swallowing the
/// panic so the rest of the corpus still runs.
fn probe<F: FnOnce()>(failures: &mut Vec<String>, label: String, f: F) {
    if catch_unwind(AssertUnwindSafe(f)).is_err() {
        failures.push(label);
    }
}

/// A scorer that returns `None` — the "model unavailable" path, so the policy
/// leaves the page untouched and the test exercises only the text handling.
fn noop_scorer() -> Arc<dyn kana_size::KanaSizeScorer> {
    struct Noop;
    impl kana_size::KanaSizeScorer for Noop {
        fn score(&self, _windows: Vec<i32>, _bases: Vec<i32>) -> Option<Vec<f32>> {
            None
        }
    }
    Arc::new(Noop)
}

fn place_options() -> char_placement::PlaceOptions {
    char_placement::PlaceOptions {
        ink_max_pull_em: 0.45,
        window_em: 0.6,
        window_stride: 0.4,
        anchor_tol_em: 0.4,
        anchor_tol_stride: 1.2,
        huber_em: 0.35,
        min_mass_frac: 0.12,
        max_spread_em: 0.8,
        conf_floor: 1.0,
        refine_passes: 1,
        final_pass: true,
        extent_pad_px: 1.0,
        extent_floor: 0.5,
        extent_window_em: 0.15,
        extent_grow_frac: 0.3,
        split_floor_frac: 1.0,
        min_half_px: 2.0,
        punct_spread_fallback: true,
        bimodal_retry: true,
        bimodal_valley_em: 0.20,
        bimodal_min_frac: 0.12,
        punct_fallback_window_em: 0.5,
        punct_fallback_max_em: 0.6,
        translate_overlap: true,
        translate_max_em: 0.04,
        translate_passes: 2,
        translate_gate_cut: false,
    }
}

/// Every text-taking export, driven with every corpus string. Any panic is
/// collected and reported together at the end, so one counterexample cannot
/// hide the others.
#[test]
fn text_exports_survive_the_hostile_corpus() {
    let deinflector = crate::deinflector::deinflector_from_json_str(
        r#"{"past":[{"kanaIn":"た","kanaOut":"る","rulesIn":[],"rulesOut":["v1"]}]}"#.to_string(),
    )
    .expect("inline rules parse");
    let kana = crate::kana_orthography::KanaOrthographyTable::empty();
    let variants = crate::kanji_variants::KanjiVariantTable::empty();
    let component = crate::component_table::ComponentTable::parse(String::new());
    let mut failures: Vec<String> = Vec::new();

    for text in corpus() {
        let tag = format!("len={}", text.chars().count());
        probe(&mut failures, format!("japanese_normalize {tag}"), || {
            let _ = japanese::japanese_normalize(text.clone());
        });
        probe(&mut failures, format!("japanese_fold {tag}"), || {
            let _ = japanese::japanese_fold_lookup_variants(text.clone());
        });
        probe(&mut failures, format!("japanese_katakana {tag}"), || {
            let _ = japanese::japanese_katakana_to_hiragana(text.clone());
        });
        probe(&mut failures, format!("japanese_collapse {tag}"), || {
            let _ = japanese::japanese_collapse_emphatic(text.clone());
        });
        probe(&mut failures, format!("japanese_vertical_punct {tag}"), || {
            let _ = japanese::japanese_vertical_punctuation(text.clone());
        });
        probe(&mut failures, format!("japanese_split_kana {tag}"), || {
            let _ = japanese::japanese_split_kana_list(text.clone());
        });
        probe(&mut failures, format!("japanese_align_furigana {tag}"), || {
            let _ = japanese::japanese_align_furigana(text.clone(), text.clone());
            let _ = japanese::japanese_align_furigana(text.clone(), "たべる".to_string());
            let _ = japanese::japanese_align_furigana("食べる".to_string(), text.clone());
        });
        probe(&mut failures, format!("japanese_estimate_em {tag}"), || {
            let _ = japanese::japanese_estimate_em(text.clone(), vec![0.0, 1.0, 2.0]);
            let _ = japanese::japanese_estimate_em(text.clone(), vec![]);
        });

        probe(&mut failures, format!("deinflect {tag}"), || {
            let _ = deinflector.deinflect(text.clone());
        });

        let char_count = text.chars().count() as i64;
        for index in [0i64, 1, char_count - 1, char_count, char_count + 1, -1, i64::MAX, i64::MIN] {
            probe(&mut failures, format!("kana_size_window {tag} index={index}"), || {
                let _ = kana_size::kana_size_window(text.clone(), index);
            });
        }

        probe(&mut failures, format!("lookup_following_text {tag}"), || {
            let _ = lookup_core::lookup_following_text(
                text.chars().map(|c| c.to_string()).collect(),
                0,
            );
            let _ = lookup_core::lookup_following_text(vec![text.clone()], -1);
        });
        probe(&mut failures, format!("lookup_prepare/process {tag}"), || {
            let prepared =
                lookup_core::lookup_prepare_candidates(text.clone(), deinflector.clone());
            let _ = lookup_core::lookup_process_results(vec![], prepared, text.clone());
        });

        probe(&mut failures, format!("gap_context_before {tag}"), || {
            let _ = gap_candidates::gap_context_before(text.clone(), 0);
            let _ = gap_candidates::gap_context_before(text.clone(), char_count);
            let _ = gap_candidates::gap_context_before(text.clone(), i64::MAX);
        });

        probe(&mut failures, format!("table parses {tag}"), || {
            let _ = kana_orthography::KanaOrthographyTable::parse(text.clone());
            let _ = kana_orthography::KanaSoundTable::parse(text.clone());
            let _ = kanji_variants::KanjiVariantTable::parse(text.clone());
            let _ = component_table::ComponentTable::parse(text.clone());
            let _ = kana.modernise(text.clone());
        });
        probe(&mut failures, format!("kana_size_correct_lines {tag}"), || {
            let _ = kana_size::kana_size_correct_lines(vec![text.clone()], noop_scorer(), 0.01);
        });

        probe(&mut failures, format!("definition_format {tag}"), || {
            let _ = definition_format::definition_format_plain(text.clone());
            let _ = definition_format::definition_format_parse(text.clone(), text.clone());
            let _ = definition_format::definition_format_parse_glossary(text.clone());
            let _ = definition_format::definition_format_redirect_targets(text.clone(), 100);
        });
        probe(&mut failures, format!("definition_format_plain_all {tag}"), || {
            let _ = definition_format::definition_format_plain_all(vec![text.clone()]);
        });
        probe(&mut failures, format!("pitch/yomitan/catalog {tag}"), || {
            let _ = pitch::pitch_positions_of(text.clone());
            let _ = pitch::pitch_reading_of(text.clone());
            let _ = pitch::pitch_morae_of(text.clone());
            let _ = yomitan_parse::yomitan_parse_term_bank(text.clone());
            let _ = yomitan_parse::yomitan_parse_kanji_bank(text.clone());
            let _ = yomitan_parse::yomitan_parse_tag_bank(text.clone());
            let _ = yomitan_parse::yomitan_parse_term_meta_bank(text.clone());
            let _ = yomitan_parse::yomitan_parse_index_title(text.clone());
            let _ = yomitan_parse::yomitan_bank_number(text.clone());
            let _ = catalog::catalog_parse(text.clone());
            let _ = catalog::catalog_base_title(text.clone());
            let _ = catalog::catalog_installed_ids(vec![text.clone()], vec![None]);
        });
    }

    // Table accessors with fixed hostile code points (independent of the
    // corpus text, but part of the surface a bad index could reach).
    probe(&mut failures, "table code point accessors".to_string(), || {
        for cp in [-1i32, 0, 0x4E00, 0xD800, 0x10FFFF, i32::MAX] {
            let _ = variants.canonical(cp);
            let _ = variants.canonicals_of(cp);
            let _ = component.kanji_with(vec![cp]);
        }
    });

    assert!(failures.is_empty(), "exports panicked:\n{}", failures.join("\n"));
}

/// The character-box paths the OCR and tree routes both reach: a text length
/// that disagrees with the geometry array length, in both directions, at both
/// ends. `charPlacementPlace*` and `ocrEngineComputeCharBoxes*` take `text` and
/// `char_cols`/`char_boxes` as independent lists, so a mismatch is the shape an
/// adversarial caller (or a bug upstream) can produce.
#[test]
fn geometry_exports_survive_text_length_mismatch() {
    let opts = place_options();
    let mut failures: Vec<String> = Vec::new();
    for text in ["", "あ", "あい", "あいうえおかきくけこ", &"あ".repeat(500)] {
        let n = text.chars().count();
        for cols_len in [0usize, 1, n.saturating_sub(1), n, n + 1, n + 50] {
            let cols: Vec<f32> = (0..cols_len).map(|i| i as f32 * 10.0).collect();
            for seq in [0i64, 1, n as i64, i64::MAX] {
                let opts = opts.clone();
                probe(&mut failures, format!("place n={n} cols={cols_len} seq={seq}"), || {
                    let _ = char_placement::char_placement_place(
                        text.to_string(), cols.clone(), seq, 64, 48, false, None, None, opts,
                    );
                });
                probe(&mut failures, format!("boxes n={n} cols={cols_len} seq={seq}"), || {
                    let _ = ocr_engine::ocr_engine_compute_char_boxes(
                        text.to_string(), cols.clone(), seq, 0, 0, 64, 48, false, None, 64, 48,
                        true, true, vec![],
                    );
                });
            }
        }
    }
    assert!(failures.is_empty(), "geometry exports panicked:\n{}", failures.join("\n"));
}

/// The oversized-input case, run by hand (`cargo test -- --ignored`).
///
/// A 70000-character string is 4× past anything the app can hand Rust (the
/// accessibility reader caps a node at 1000 characters; an OCR line is bounded
/// by its timestep count), and in a debug build a single `japanese_normalize`
/// over it costs tens of milliseconds — so this is not part of the default
/// suite. It exists to show the boundary functions stay linear and panic-free
/// at a size far past the real cap, and to exercise the lengths around a
/// 16-bit and 32-bit index.
#[test]
#[ignore = "slow in debug; run with --ignored to prove the oversized case"]
fn text_exports_survive_a_very_long_input() {
    let deinflector = crate::deinflector::deinflector_from_json_str(
        r#"{"past":[{"kanaIn":"た","kanaOut":"る","rulesIn":[],"rulesOut":["v1"]}]}"#.to_string(),
    )
    .expect("inline rules parse");
    let mut failures: Vec<String> = Vec::new();
    let mut rng = Rng(0x0fed_cba9_8765_4321);
    for len in [65535usize, 70000, 200_000] {
        // Alternate between the random hostile string and a uniform one that
        // keeps the fold table busy.
        let text = if len % 2 == 0 {
            hostile_string(&mut rng, len)
        } else {
            "ゝ".repeat(len)
        };
        probe(&mut failures, format!("normalize len={len}"), || {
            let _ = japanese::japanese_normalize(text.clone());
        });
        probe(&mut failures, format!("fold len={len}"), || {
            let _ = japanese::japanese_fold_lookup_variants(text.clone());
        });
        probe(&mut failures, format!("deinflect len={len}"), || {
            let _ = deinflector.deinflect(text.clone());
        });
        probe(&mut failures, format!("align len={len}"), || {
            let _ = japanese::japanese_align_furigana(text.clone(), text.clone());
        });
        probe(&mut failures, format!("window len={len}"), || {
            let count = text.chars().count() as i64;
            for index in [-1i64, 0, count - 1, count, count + 1, i64::MAX] {
                let _ = kana_size::kana_size_window(text.clone(), index);
            }
        });
    }
    assert!(failures.is_empty(), "exports panicked:\n{}", failures.join("\n"));
}
