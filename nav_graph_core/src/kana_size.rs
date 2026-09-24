//! UniFFI shim over `jpdict_core::kana_size` (#44) — **WP-07, the stateless
//! encoder half.**
//!
//! The small/large kana window encoder has one source of truth in the PC crate
//! now. This file is only the boundary: `window`, `big_form_of`, `base_index_of`
//! and `is_small` cross as module-prefixed free functions, and the two tables
//! the Kotlin facade used to hardcode (`BASE_ORDER`, `SMALL_TO_BIG`) cross as
//! owned data. See `char_lm.rs` for the `char` ↔ `i32` convention this follows.
//!
//! What the boundary loses:
//!
//! * **`char` becomes `i32`.** Invalid code points (negative, surrogate, above
//!   U+10FFFF) degrade to U+FFFD, exactly as `char_lm.rs` documents, so they
//!   are never pair members.
//! * **`usize` becomes `i64`.** A negative window index is rejected with a
//!   panic, which UniFFI surfaces to Kotlin as an exception; every in-range
//!   call is unchanged. The index counts Unicode scalar values, not UTF-16
//!   units — that is the PC signature's contract and it matches the artifact
//!   author's Python reference. The old Kotlin encoder indexed UTF-16 units,
//!   so windows for text containing supplementary-plane characters shift by
//!   one per astral character; for BMP text (everything the published vectors
//!   and the corpus cover) the two are identical.
//! * **Consts do not cross.** UniFFI cannot export `WINDOW_BYTES` or
//!   `BOUNDARY`; the Kotlin facade mirrors them as documented `const val`s
//!   (`40`, `"。\n"`). The tests below pin the window length against the
//!   upstream const so the mirror cannot drift silently.
//! * **`SMALL_TO_BIG` becomes `Vec<KanaSizePair>`.** UniFFI cannot carry a
//!   `Map`, so the Kotlin facade rebuilds it from the record list. PC keeps the
//!   table itself private; the accessor derives it from the public
//!   `is_small`/`big_form_of` predicates instead of copying it, so a PC table
//!   change cannot desync this side.
//!
//! Out of scope by design: the ε policy (`correct_lines`, `prob_big`,
//! `Flip`/`Declined`/`Correction`) and the native `KanaSizeNet`. The Kotlin
//! scorer is `KanaSizeNcnn` (JNI), so converting the policy needs a
//! `uniffi::CallbackInterface` and is a separate package; `KanaSizeFix.kt`
//! keeps its Kotlin policy until then.
//!
//! ## Kotlin facade contract
//!
//! `com.holopengin.instantjpdict.util.KanaSizeEncoder` keeps the pre-conversion
//! API exactly (`BASE_ORDER`, `SMALL_TO_BIG`, `bigFormOf`, `baseIndexOf`,
//! `isSmall`, `window`, `WINDOW_BYTES`, `BOUNDARY`), so `KanaSizeNcnn.kt` and
//! `KanaSizeFix.kt` compile unchanged. The facade absorbs every `Char` ↔ `Int`
//! conversion.

use jpdict_core::kana_size as pc;
use std::sync::Arc;

/// One small/big kana pair: the `SMALL_TO_BIG` table as a record list, because
/// UniFFI cannot carry a `Map`. The Kotlin facade rebuilds the map from these.
#[derive(Clone, Debug, uniffi::Record)]
pub struct KanaSizePair {
    /// Small member of the pair, e.g. っ.
    pub small: i32,
    /// Its canonical big form, e.g. つ.
    pub big: i32,
}

/// Decode one Kotlin `Int` code point into a Rust `char`.
///
/// The Kotlin side always sends valid `Char.code` values; anything else (a
/// negative, a surrogate, above U+10FFFF) degrades to U+FFFD rather than
/// panicking at the FFI boundary — the same rule `char_lm.rs` set.
fn char_from_codepoint(cp: i32) -> char {
    u32::try_from(cp)
        .ok()
        .and_then(char::from_u32)
        .unwrap_or('\u{FFFD}')
}

/// Pair index. The BIG form is the base, hiragana then katakana.
#[uniffi::export]
pub fn kana_size_base_order() -> Vec<i32> {
    pc::BASE_ORDER.iter().map(|&c| c as i32).collect()
}

/// Small form -> big form, as pairs.
///
/// The small→big pair table, in PC table order. Direct re-export of
/// `jpdict_core::kana_size::SMALL_TO_BIG_PAIRS` (UniFFI cannot carry a map,
/// hence the record list).
#[uniffi::export]
pub fn kana_size_small_to_big() -> Vec<KanaSizePair> {
    pc::SMALL_TO_BIG_PAIRS
        .iter()
        .map(|&(small, big)| KanaSizePair {
            small: small as i32,
            big: big as i32,
        })
        .collect()
}

/// The canonical big form for either member of a pair, or `None` if `ch` is not
/// part of one.
#[uniffi::export]
pub fn kana_size_big_form_of(ch: i32) -> Option<i32> {
    pc::big_form_of(char_from_codepoint(ch)).map(|c| c as i32)
}

/// Pair index for the position holding `ch`, or `None` when `ch` is not part of
/// a size pair. Both っ and つ map to the same index: the pair is the class, the
/// size is the decision.
#[uniffi::export]
pub fn kana_size_base_index_of(ch: i32) -> Option<i64> {
    pc::base_index_of(char_from_codepoint(ch)).map(|i| i as i64)
}

/// Whether `ch` is the small member of a pair, i.e. what the model corrects.
#[uniffi::export]
pub fn kana_size_is_small(ch: i32) -> bool {
    pc::is_small(char_from_codepoint(ch))
}

/// The 40-byte window length, Rust-sourced because UniFFI cannot export consts
/// (the Kotlin facade's `WINDOW_BYTES` and `KanaSizeNcnn.WINDOW_BYTES` read it).
#[uniffi::export]
pub fn kana_size_window_bytes() -> i64 {
    pc::WINDOW_BYTES as i64
}

/// The measured ε default, Rust-sourced because UniFFI cannot export consts
/// (the Kotlin facade's `EPSILON`/`DEF_EPSILON` read it).
#[uniffi::export]
pub fn kana_size_epsilon() -> f32 {
    pc::EPSILON
}

/// The 40-byte window for the position at `index` in `text`, with the character
/// at `index` excluded. Context stops at a boundary character (。 or newline)
/// and runs off the ends as zero padding; see `jpdict_core::kana_size::window`
/// for the layout. The Kotlin facade mirrors the 40-byte length as
/// `KanaSizeEncoder.WINDOW_BYTES` because UniFFI cannot export consts.
#[uniffi::export]
pub fn kana_size_window(text: String, index: i64) -> Vec<i32> {
    let index = usize::try_from(index).expect("kana_size_window: index must be non-negative");
    pc::window(&text, index).to_vec()
}

// ---------------------------------------------------------------------------
// The ε policy (WP-14): the scorer is injected through a foreign callback
// ---------------------------------------------------------------------------

/// The scorer the host injects into [`kana_size_correct_lines`].
///
/// `windows` is `n * 40` window bytes and `bases` the `n` pair indices, in text
/// order; the implementation returns `n` logits, or `None` to leave the page
/// untouched (a wrong-length result does the same). Android implements this
/// over the JNI `KanaSizeNcnn`; the JVM tests implement it as a lambda.
#[uniffi::export(with_foreign)]
pub trait KanaSizeScorer: Send + Sync {
    fn score(&self, windows: Vec<i32>, bases: Vec<i32>) -> Option<Vec<f32>>;
}

/// One position the policy flipped.
#[derive(Clone, Debug, uniffi::Record)]
pub struct KanaSizeFlip {
    /// Line index in the corrected page.
    pub line: i32,
    /// Character index in the line's text.
    pub index: i32,
    pub from: i32,
    pub to: i32,
    pub p_big: f32,
}

/// One position left in the middle band, lowest confidence first.
#[derive(Clone, Debug, uniffi::Record)]
pub struct KanaSizeDeclined {
    pub line: i32,
    pub index: i32,
    pub ch: i32,
    /// Distance to the threshold that would have fired: `1 - p` for a small
    /// member, `p` for a big one.
    pub confidence: f32,
}

/// Result of one correction pass.
#[derive(Clone, Debug, uniffi::Record)]
pub struct KanaSizeCorrection {
    /// Corrected text per line, index-aligned with the input.
    pub texts: Vec<String>,
    pub flips: Vec<KanaSizeFlip>,
    /// Declined positions, lowest confidence first.
    pub declined: Vec<KanaSizeDeclined>,
    /// `L<line>@<index> <char> p=<p>`, lowest confidence first, first five
    /// (mobile `KanaSizeFix.lastDeclined`), formatted upstream so both sides
    /// agree on the exact string.
    pub declined_summary: String,
}

/// Mobile `KanaSizeFix.apply`: the ε policy over a page, with the host's
/// scorer. Only the text is needed from each line — the policy never reads
/// geometry, and the caller keeps its own overrides.
#[uniffi::export]
pub fn kana_size_correct_lines(
    texts: Vec<String>,
    scorer: Arc<dyn KanaSizeScorer>,
    epsilon: f32,
) -> KanaSizeCorrection {
    let lines: Vec<jpdict_core::models::LineResult> = texts
        .iter()
        .map(|text| jpdict_core::models::LineResult {
            text: text.clone(),
            char_boxes: Vec::new(),
            alternatives: Vec::new(),
            raw_alternatives: Vec::new(),
            sample_txt: None,
            is_vertical: false,
            chunk_boxes: Vec::new(),
        })
        .collect();
    let correction =
        pc::correct_lines(&lines, |wins, bases| scorer.score(wins.to_vec(), bases.to_vec()), epsilon);
    KanaSizeCorrection {
        texts: correction.lines.iter().map(|l| l.text.clone()).collect(),
        flips: correction
            .flips
            .iter()
            .map(|f| KanaSizeFlip {
                line: f.line as i32,
                index: f.index as i32,
                from: f.from as i32,
                to: f.to as i32,
                p_big: f.p_big,
            })
            .collect(),
        declined: correction
            .declined
            .iter()
            .map(|d| KanaSizeDeclined {
                line: d.line as i32,
                index: d.index as i32,
                ch: d.ch as i32,
                confidence: d.confidence,
            })
            .collect(),
        declined_summary: correction.declined_summary(),
    }
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::kana_size` encoder tests, run through the
    //! exported surface (not the upstream module), so the shim's argument
    //! conversion is covered too. The PC module's ε-policy tests
    //! (`correct_lines`) and the `native` self-check are out of WP-07's scope
    //! and stay on the PC side; the JVM `KanaSizeEncoderTest` pins the same ten
    //! published vectors across the UniFFI boundary.

    use super::*;
    use std::collections::HashSet;

    /// The ten published validation vectors, verbatim from the artifact: the
    /// same bytes its own `validate_port.py` checks and the Kotlin
    /// `KanaSizeEncoderTest` pins. A wrong cell order, padding convention,
    /// missing context clip, or the target leaking into its own window would
    /// all produce plausible-looking windows that silently degrade every
    /// prediction. Four carry a boundary (。 or newline) within reach of the
    /// target, which is what pins the line-domain clip.
    struct Vector {
        text: &'static str,
        index: usize,
        win: [i32; 40],
    }

    const VECTORS: [Vector; 10] = [
        Vector {
            text: "かれはいっとう。",
            index: 4,
            win: [
                0, 0, 0, 0, 227, 129, 139, 0, 227, 130, 140, 0, 227, 129, 175, 0, 227, 129, 132, 0,
                227, 129, 168, 0, 227, 129, 134, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ],
        },
        Vector {
            text: "きょうはいいてんきですね、まつ。",
            index: 14,
            win: [
                227, 129, 167, 0, 227, 129, 153, 0, 227, 129, 173, 0, 227, 128, 129, 0, 227, 129,
                190, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ],
        },
        Vector {
            text: "みんなでサッカーをするつもりです。",
            index: 5,
            win: [
                227, 129, 191, 0, 227, 130, 147, 0, 227, 129, 170, 0, 227, 129, 167, 0, 227, 130,
                181, 0, 227, 130, 171, 0, 227, 131, 188, 0, 227, 130, 146, 0, 227, 129, 153, 0,
                227, 130, 139, 0,
            ],
        },
        Vector {
            text: "シーツをあらう。",
            index: 2,
            win: [
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 227, 130, 183, 0, 227, 131, 188, 0, 227, 130,
                146, 0, 227, 129, 130, 0, 227, 130, 137, 0, 227, 129, 134, 0, 0, 0, 0, 0,
            ],
        },
        Vector {
            text: "きょうのてんきはいいですね。",
            index: 1,
            win: [
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 227, 129, 141, 0, 227, 129, 134,
                0, 227, 129, 174, 0, 227, 129, 166, 0, 227, 130, 147, 0, 227, 129, 141, 0,
            ],
        },
        Vector {
            text: "キャンプにいく。",
            index: 1,
            win: [
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 227, 130, 173, 0, 227, 131, 179,
                0, 227, 131, 151, 0, 227, 129, 171, 0, 227, 129, 132, 0, 227, 129, 143, 0,
            ],
        },
        Vector {
            text: "昌仙も、おもわず床几を立って、\n「あッ」\n　と、櫓",
            index: 12,
            win: [
                227, 129, 154, 0, 229, 186, 138, 0, 229, 135, 160, 0, 227, 130, 146, 0, 231, 171,
                139, 0, 227, 129, 166, 0, 227, 128, 129, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ],
        },
        Vector {
            text: "なろうかと……」\n　おえつは、片手に、腕白を抱きな",
            index: 12,
            win: [
                0, 0, 0, 0, 0, 0, 0, 0, 227, 128, 128, 0, 227, 129, 138, 0, 227, 129, 136, 0, 227,
                129, 175, 0, 227, 128, 129, 0, 231, 137, 135, 0, 230, 137, 139, 0, 227, 129, 171,
                0,
            ],
        },
        Vector {
            text: "は、変化多き世の中にもちょっと例の少ない並ならぬ三",
            index: 12,
            win: [
                227, 129, 174, 0, 228, 184, 173, 0, 227, 129, 171, 0, 227, 130, 130, 0, 227, 129,
                161, 0, 227, 129, 163, 0, 227, 129, 168, 0, 228, 190, 139, 0, 227, 129, 174, 0,
                229, 176, 145, 0,
            ],
        },
        Vector {
            text: "。\n　そして、ザッザ、ザッザと、草の波を分けて、押",
            index: 12,
            win: [
                227, 130, 182, 0, 227, 131, 131, 0, 227, 130, 182, 0, 227, 128, 129, 0, 227, 130,
                182, 0, 227, 130, 182, 0, 227, 129, 168, 0, 227, 128, 129, 0, 232, 141, 137, 0,
                227, 129, 174, 0,
            ],
        },
    ];

    /// The exported window, with the `i64` index conversion the facade applies.
    fn win(text: &str, index: usize) -> Vec<i32> {
        kana_size_window(text.to_string(), index as i64)
    }

    /// The length the Kotlin facade mirrors as a `const val` (UniFFI cannot
    /// export consts), pinned to the upstream const.
    const WINDOW_LEN: usize = jpdict_core::kana_size::WINDOW_BYTES;

    #[test]
    fn every_published_vector_encodes_byte_for_byte() {
        for v in &VECTORS {
            assert_eq!(
                win(v.text, v.index),
                v.win.to_vec(),
                "window mismatch for {}@{}",
                v.text,
                v.index
            );
        }
    }

    #[test]
    fn base_index_follows_the_models_table() {
        let order = kana_size_base_order();
        assert_eq!(order.len(), 20);
        assert_eq!(order.iter().collect::<HashSet<_>>().len(), 20);
        assert_eq!(kana_size_base_index_of('あ' as i32), Some(0));
        assert_eq!(kana_size_base_index_of('つ' as i32), Some(5));
        assert_eq!(kana_size_base_index_of('よ' as i32), Some(8));
        assert_eq!(kana_size_base_index_of('ア' as i32), Some(10));
        assert_eq!(kana_size_base_index_of('ツ' as i32), Some(15));
        assert_eq!(kana_size_base_index_of('か' as i32), None);
    }

    #[test]
    fn both_members_of_a_pair_share_one_index() {
        assert_eq!(
            kana_size_base_index_of('つ' as i32),
            kana_size_base_index_of('っ' as i32)
        );
        assert_eq!(
            kana_size_base_index_of('よ' as i32),
            kana_size_base_index_of('ょ' as i32)
        );
        assert_eq!(
            kana_size_base_index_of('ツ' as i32),
            kana_size_base_index_of('ッ' as i32)
        );
        assert!(kana_size_is_small('っ' as i32));
        assert!(!kana_size_is_small('つ' as i32));
    }

    #[test]
    fn the_target_character_is_not_in_its_own_window() {
        // Same neighbours, different target: the windows must be identical, or
        // the model would be shown the OCR's answer at the one position it is
        // supposed to be deciding.
        assert_eq!(win("かれはっとう。", 3), win("かれはつとう。", 3));
    }

    #[test]
    fn context_stops_at_a_full_stop_or_a_newline() {
        // The boundary character terminates the walk and is not itself part of
        // the window, so neither the 。 nor anything before it is visible to a
        // position after it.
        let stop = win("あ。っあ", 2);
        assert_eq!(stop[..20], [0i32; 20], "left of the 。 must be empty");
        assert_eq!(
            stop[20..],
            [227, 129, 130, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            "right context is the あ, then nothing"
        );

        // A newline clips the same way, and a character beyond it stays hidden
        // even though the window has room for it: without the clip this cell
        // would hold the あ.
        let nl = win("あ\nいっ", 3);
        assert_eq!(
            nl[..20],
            [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 227, 129, 132, 0]
        );
        assert_eq!(nl[20..], [0i32; 20]);
    }

    #[test]
    fn context_runs_off_the_lines_ends_as_zeros() {
        let w = win("あい", 0);
        assert_eq!(w.len(), WINDOW_LEN);
        assert_eq!(w[..20], [0i32; 20]);
        assert_eq!(w[20..24], [227, 129, 132, 0]);
        assert_eq!(w[24..], [0i32; 16]);
    }

    /// Both tables cross as owned data and must describe exactly the model's
    /// pairs: every base form has one small member, and the small member maps
    /// back through the exported `big_form_of`.
    #[test]
    fn pair_tables_are_exact() {
        let order: Vec<i32> = "あいうえおつやゆよわアイウエオツヤユヨワ"
            .chars()
            .map(|c| c as i32)
            .collect();
        assert_eq!(kana_size_base_order(), order);

        let mut got: Vec<(i32, i32)> = kana_size_small_to_big()
            .iter()
            .map(|p| (p.big, p.small))
            .collect();
        got.sort_unstable();
        let expected: Vec<(i32, i32)> = "あいうえおつやゆよわアイウエオツヤユヨワ"
            .chars()
            .map(|c| c as i32)
            .zip("ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ".chars().map(|c| c as i32))
            .collect();
        assert_eq!(got, expected, "small -> big pairs");

        for p in kana_size_small_to_big() {
            assert!(kana_size_is_small(p.small));
            assert_eq!(kana_size_big_form_of(p.small), Some(p.big));
            assert_eq!(
                kana_size_base_index_of(p.small),
                kana_size_base_index_of(p.big),
                "pair {} shares one index",
                char::from_u32(p.small as u32).unwrap()
            );
        }
    }

    /// Invalid code points degrade to U+FFFD at the boundary, so they are not
    /// pair members rather than a panic.
    #[test]
    fn invalid_code_points_are_not_pair_members() {
        for cp in [-1, 0xD800, 0x11_0000, 'a' as i32] {
            assert_eq!(kana_size_big_form_of(cp), None, "big_form_of({cp})");
            assert_eq!(kana_size_base_index_of(cp), None, "base_index_of({cp})");
            assert!(!kana_size_is_small(cp), "is_small({cp})");
        }
    }

    // ── The ε policy through the callback interface (WP-14) ────────────────

    /// A scorer that always answers with the same logits (the shim-side twin of
    /// the JVM tests' injected lambda).
    struct FixedLogits(Vec<f32>);
    impl KanaSizeScorer for FixedLogits {
        fn score(&self, _windows: Vec<i32>, _bases: Vec<i32>) -> Option<Vec<f32>> {
            Some(self.0.clone())
        }
    }

    /// A scorer that fails (mobile `KanaSizeFix`'s null result).
    struct NoLogits;
    impl KanaSizeScorer for NoLogits {
        fn score(&self, _windows: Vec<i32>, _bases: Vec<i32>) -> Option<Vec<f32>> {
            None
        }
    }

    fn texts(lines: &[&str]) -> Vec<String> {
        lines.iter().map(|s| s.to_string()).collect()
    }

    /// The measured policy: a confident small member flips to big and a
    /// confident big member to small (mirrors `kana-02-policy-flips` and
    /// `KanaSizeFixTest`).
    #[test]
    fn policy_flips_small_and_big_and_carries_p() {
        let got = kana_size_correct_lines(
            texts(&["かっき", "かつき"]),
            Arc::new(FixedLogits(vec![8.0, -8.0])),
            0.01,
        );
        assert_eq!(got.texts, texts(&["かつき", "かっき"]));
        assert_eq!(got.flips.len(), 2);
        assert_eq!((got.flips[0].line, got.flips[0].index), (0, 1));
        assert_eq!(
            (got.flips[0].from, got.flips[0].to),
            ('っ' as i32, 'つ' as i32)
        );
        assert!(got.flips[0].p_big > 0.99);
        assert_eq!((got.flips[1].line, got.flips[1].index), (1, 1));
        assert_eq!(
            (got.flips[1].from, got.flips[1].to),
            ('つ' as i32, 'っ' as i32)
        );
        assert!(got.flips[1].p_big < 0.01);
        assert!(got.declined.is_empty());
        assert_eq!(got.declined_summary, "");
    }

    /// The middle band is left alone and reported with its distance to the
    /// threshold, lowest confidence first, in the mobile summary format.
    #[test]
    fn middle_band_is_declined_with_the_mobile_summary_format() {
        let got = kana_size_correct_lines(
            texts(&["かつき"]),
            Arc::new(FixedLogits(vec![0.0])),
            0.01,
        );
        assert_eq!(got.texts, texts(&["かつき"]), "no flip");
        assert!(got.flips.is_empty());
        assert_eq!(got.declined.len(), 1);
        let d = &got.declined[0];
        assert_eq!((d.line, d.index, d.ch), (0, 1, 'つ' as i32));
        assert!((d.confidence - 0.5).abs() < 1e-6);
        assert_eq!(got.declined_summary, "L0@1 つ p=0.500");
    }

    /// A missing or wrong-length scorer result leaves the page untouched
    /// (mobile `KanaSizeFix.apply`).
    #[test]
    fn failed_or_short_scorer_leaves_the_page_untouched() {
        let input = texts(&["かっき"]);
        let failed = kana_size_correct_lines(input.clone(), Arc::new(NoLogits), 0.01);
        assert_eq!(failed.texts, input);
        assert!(failed.flips.is_empty() && failed.declined.is_empty());
        let short = kana_size_correct_lines(input.clone(), Arc::new(FixedLogits(vec![8.0, -8.0])), 0.01);
        assert_eq!(short.texts, input);
        assert!(short.flips.is_empty() && short.declined.is_empty());
    }

    /// Positions that are not size-pair members are never scored; an empty page
    /// short-circuits before the scorer is consulted.
    #[test]
    fn non_pair_texts_are_never_scored() {
        let got = kana_size_correct_lines(texts(&["かきくけこ"]), Arc::new(FixedLogits(vec![])), 0.01);
        assert_eq!(got.texts, texts(&["かきくけこ"]));
        assert!(got.flips.is_empty() && got.declined.is_empty());
        let empty = kana_size_correct_lines(Vec::new(), Arc::new(FixedLogits(vec![])), 0.01);
        assert!(empty.texts.is_empty());
    }

    /// The exported const accessors match the crate's consts, so the Kotlin
    /// facade's values cannot drift from the encoder and the measured ε.
    #[test]
    fn exported_consts_match_upstream() {
        assert_eq!(kana_size_window_bytes(), pc::WINDOW_BYTES as i64);
        assert_eq!(kana_size_epsilon(), pc::EPSILON);
    }
}
