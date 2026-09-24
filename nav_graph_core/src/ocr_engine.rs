//! UniFFI shim over `jpdict_core::char_boxes` (pipeline-sharing/07, WP-16).
//!
//! The pure, non-native stages of the mobile `OcrEngine` — the legacy
//! CTC-column char-box chain (`computeCharBoxes`), the reading-order sort
//! (`sortDetectedBoxes`) and the cache re-decode walk (`reDecodeLineResult`)
//! — have one source of truth in the PC crate now. This file is only the
//! boundary: argument/record conversion, no algorithm. The ncnn/JNI
//! inference path (`detect`, `recognizeStreaming`, `close`) and every
//! `Bitmap` helper stay Android-native in `OcrEngine.kt`.
//!
//! ## What crosses, and what the boundary loses
//!
//! * **The CAP branch stays on the WP-15 facade.** `computeCharBoxes` tries
//!   CTC-anchored placement first; that already delegates through
//!   `CharPlacement`, so only the legacy fallback chain crosses here (via
//!   `compute_char_boxes_with`). The kill-switch and layout prefs cross as
//!   explicit `cap`/`snap`/`uniform` bools — the Rust side never reads the
//!   Kotlin companion `var`s.
//! * **Ink widths are measured in Kotlin.** The resolve stage needs per-glyph
//!   ink half-widths; the desktop measures them from its bundled font, but
//!   Android measures with its own typeface, so the facade measures with
//!   `Paint` (exactly the old `resolveInkCollisions` block) and passes the
//!   widths in. An empty list reads as all-zero, i.e. the resolve no-op.
//! * **ARGB pixels become RGB8 here.** Kotlin hands over an `IntArray` of
//!   ARGB ints plus dims; the shim extracts RGB bytes for the snap stage.
//!   A short (or missing) array skips snapping, matching the old Kotlin
//!   `pixels.size != pixW * pixH` guard with an exact-size check.
//! * **The sort crosses as a permutation.** The corpus pins reading order
//!   through referential equality, so the shim returns the ordering indices
//!   and the facade applies them to its own `JpDictRect` instances.
//! * **`steps` reuse [`GapCell`].** A raw-alternative entry is a
//!   `(char, score)` cell, exactly the gap pipeline's record; `ch` crosses
//!   as `i32` with the usual U+FFFD degradation.
//! * **`usize` crosses as `i64`/`Long`.** `seq_len_total` follows the crate
//!   convention; negatives degrade to 0.
//!
//! ## Kotlin facade contract
//!
//! `com.holopengin.instantjpdict.OcrEngine` keeps its pre-conversion API
//! (`computeCharBoxes`, `sortDetectedBoxes`, `reDecodeLineResult`) so call
//! sites and the existing tests compile unchanged. The facade absorbs every
//! `Char` ↔ `Int`, `FloatArray` ↔ `List`, rect ↔ `BoundingBox` conversion.

use crate::blank_gaps::GapCell;
use crate::BoundingBox;

/// Decode a Kotlin `Int` code point into a Rust `char` (U+FFFD for invalid),
/// the boundary convention `char_lm.rs` set.
fn char_from_codepoint(cp: i32) -> char {
    u32::try_from(cp)
        .ok()
        .and_then(char::from_u32)
        .unwrap_or('\u{FFFD}')
}

/// ARGB ints → RGB8. `None` unless the array is exactly `w * h` (the mobile
/// snap guard), so a truncated buffer skips the ink pass instead of feeding
/// the profile zeroes.
fn rgb_from_argb_exact(pixels: &[i32], w: u32, h: u32) -> Option<Vec<u8>> {
    let need = (w as usize).saturating_mul(h as usize);
    if pixels.len() != need {
        return None;
    }
    let mut rgb = Vec::with_capacity(need.saturating_mul(3));
    for &p in pixels.iter().take(need) {
        let p = p as u32;
        rgb.push(((p >> 16) & 0xFF) as u8);
        rgb.push(((p >> 8) & 0xFF) as u8);
        rgb.push((p & 0xFF) as u8);
    }
    Some(rgb)
}

/// The mobile `OcrEngine.computeCharBoxes` legacy chain (horizontal columns +
/// ink resolve + uniform sizing, vertical columns + punctuation rules).
///
/// Delegates to `jpdict_core::char_boxes::compute_char_boxes_with`: `snap`
/// and `uniform` are the `BOX_LAYOUT_MODE == BOX_SNAP` / `BOX_UNIFORM_SIZE`
/// flags, `ink_half_widths` the facade's `Paint`-measured half-widths
/// (horizontal only; empty reads as all-zero). Returns crop-space boxes as
/// `(x, y, w, h)`; the facade maps them to `JpDictRect`.
#[uniffi::export]
#[allow(clippy::too_many_arguments)] // mirrors the PC frame plus the pixel dims
pub fn ocr_engine_compute_char_boxes(
    text: String,
    char_cols: Vec<f32>,
    seq_len_total: i64,
    crop_x: i32,
    crop_y: i32,
    crop_w: i32,
    crop_h: i32,
    is_vertical: bool,
    pixels: Option<Vec<i32>>,
    pix_w: i32,
    pix_h: i32,
    snap: bool,
    uniform: bool,
    ink_half_widths: Vec<f32>,
) -> Vec<BoundingBox> {
    let seq_len_total = usize::try_from(seq_len_total).unwrap_or(0);
    let (crop_w, crop_h) = (crop_w.max(0) as u32, crop_h.max(0) as u32);
    let (pix_w, pix_h) = (
        u32::try_from(pix_w).unwrap_or(0),
        u32::try_from(pix_h).unwrap_or(0),
    );
    let rgb = pixels
        .as_deref()
        .and_then(|p| rgb_from_argb_exact(p, pix_w, pix_h));
    let core_boxes = jpdict_core::char_boxes::compute_char_boxes_with(
        &text,
        &char_cols,
        seq_len_total,
        crop_x,
        crop_y,
        crop_w,
        crop_h,
        is_vertical,
        rgb.as_ref().map(|b| (b.as_slice(), pix_w, pix_h)),
        snap,
        uniform,
        &ink_half_widths,
    );
    core_boxes
        .into_iter()
        .map(|b| BoundingBox {
            x: b.x,
            y: b.y,
            w: b.w,
            h: b.h,
        })
        .collect()
}

/// Mobile vertical closing punctuation (`OcrEngine.computeCharBoxes`): marks
/// that shrink onto the next box's start. Exported so the JVM suite can pin
/// the set; the box path itself applies it internally.
#[uniffi::export]
pub fn ocr_engine_is_vertical_closing_punct(ch: i32) -> bool {
    jpdict_core::char_boxes::is_vertical_closing_punct(char_from_codepoint(ch))
}

/// Mobile vertical opening punctuation: marks that shrink onto the previous
/// box's end. See [`ocr_engine_is_vertical_closing_punct`].
#[uniffi::export]
pub fn ocr_engine_is_vertical_opening_punct(ch: i32) -> bool {
    jpdict_core::char_boxes::is_vertical_opening_punct(char_from_codepoint(ch))
}

/// Reading-order permutation for axis-aligned boxes (mobile
/// `OcrEngine.sortDetectedBoxes`): the indices of `boxes` in reading order.
/// The facade applies it to its own rects so their identities survive (the
/// shared corpus pins order through referential equality).
#[uniffi::export]
pub fn ocr_engine_sort_order(boxes: Vec<BoundingBox>) -> Vec<i64> {
    let core_boxes: Vec<jpdict_core::models::BoundingBox> = boxes
        .iter()
        .map(|b| jpdict_core::models::BoundingBox::new(b.x, b.y, b.w, b.h, 1.0))
        .collect();
    jpdict_core::char_boxes::sort_order(&core_boxes)
        .into_iter()
        .map(|i| i as i64)
        .collect()
}

/// Model-free re-decode output: text, fractional CTC columns and
/// per-emitted-character alternatives, ready to rebuild a `LineResult`.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ReDecodedLine {
    pub text: String,
    pub char_cols: Vec<f32>,
    pub alternatives: Vec<Vec<GapCell>>,
}

/// Mobile `OcrEngine.reDecodeLineResult`'s walk, without the `LineResult`
/// plumbing: greedy CTC over the cached raw alternatives (entry 0 the
/// argmax, U+3000 the blank). `None` when nothing is cached. The facade
/// recomputes the char boxes from the returned columns through
/// `computeCharBoxes` and maps them through the line's quad itself.
#[uniffi::export]
pub fn ocr_engine_re_decode(
    raw: Vec<Vec<GapCell>>,
    is_vertical: bool,
) -> Option<ReDecodedLine> {
    let core_raw: Vec<Vec<(char, f32)>> = raw
        .iter()
        .map(|alts| {
            alts.iter()
                .map(|c| (char_from_codepoint(c.ch), c.score))
                .collect()
        })
        .collect();
    jpdict_core::char_boxes::re_decode_raw_alternatives(&core_raw, is_vertical).map(|re| {
        ReDecodedLine {
            text: re.text,
            char_cols: re.char_cols,
            alternatives: re
                .alternatives
                .into_iter()
                .map(|alts| {
                    alts.into_iter()
                        .map(|(c, s)| GapCell {
                            ch: c as i32,
                            score: s,
                        })
                        .collect()
                })
                .collect(),
        }
    })
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::char_boxes` tests, run through the
    //! exported surface (not the upstream module), so the shim's argument
    //! conversion is covered too. The JVM `ConformanceCorpusTest` pins
    //! reading order through the UniFFI boundary at integration.

    use super::*;

    /// Box frame for the column tests: 200px wide, `seqLenTotal` 4.
    fn h_frame() -> (i32, i32, i32, i32) {
        (10, 20, 200, 40)
    }

    fn to_tuples(boxes: &[BoundingBox]) -> Vec<(i32, i32, i32, i32)> {
        boxes.iter().map(|b| (b.x, b.y, b.w, b.h)).collect()
    }

    /// The PC `compute_char_boxes_horizontal_uniform_em` pin, through the
    /// boundary: centres on the CTC columns, uniform em widths, full height.
    #[test]
    fn horizontal_columns_and_uniform_widths_cross_intact() {
        let (x, y, w, h) = h_frame();
        let boxes = ocr_engine_compute_char_boxes(
            "あいうえ".to_string(),
            vec![0.0, 1.0, 2.0, 3.0],
            4,
            x,
            y,
            w,
            h,
            false,
            None,
            0,
            0,
            true,
            true,
            vec![],
        );
        assert_eq!(boxes.len(), 4);
        for b in &boxes {
            assert_eq!((b.y, b.h), (y, h));
        }
        let centres: Vec<f32> = boxes
            .iter()
            .map(|b| b.x as f32 + b.w as f32 / 2.0)
            .collect();
        assert_eq!(centres, vec![35.0, 85.0, 135.0, 185.0]);
        for b in &boxes {
            assert!((b.w as f32 - 50.0).abs() <= 1.0, "w={}", b.w);
        }
    }

    /// The PC vertical-punctuation pin: the closing mark shrinks onto the
    /// next box and expands back to the plain-cell height.
    #[test]
    fn vertical_punctuation_rules_cross_intact() {
        let boxes = ocr_engine_compute_char_boxes(
            "あ。いあ".to_string(),
            vec![0.0, 1.0, 2.0, 3.0],
            4,
            0,
            0,
            40,
            100,
            true,
            None,
            0,
            0,
            true,
            true,
            vec![],
        );
        assert_eq!(boxes.len(), 4);
        let cp = &boxes[1];
        assert!(cp.y + cp.h <= boxes[2].y + 1, "closing mark {cp:?}");
        assert!(
            (cp.h - boxes[2].h).abs() <= 2,
            "closing h={} vs plain h={}",
            cp.h,
            boxes[2].h
        );
    }

    /// The flags cross: `uniform = false` keeps legacy widths, and `snap`
    /// with a short pixel array degrades to the no-pixels result.
    #[test]
    fn flags_and_pixel_guard_cross() {
        let (x, y, w, h) = h_frame();
        let base = || {
            ocr_engine_compute_char_boxes(
                "あいうえ".to_string(),
                vec![0.0, 1.0, 2.0, 3.0],
                4,
                x,
                y,
                w,
                h,
                false,
                None,
                0,
                0,
                true,
                false,
                vec![],
            )
        };
        let boxes = base();
        assert!(boxes.iter().all(|b| b.w == 40), "{boxes:?}");
        // A truncated ARGB array skips the snap stage: same boxes as None.
        let short = ocr_engine_compute_char_boxes(
            "あいうえ".to_string(),
            vec![0.0, 1.0, 2.0, 3.0],
            4,
            x,
            y,
            w,
            h,
            false,
            Some(vec![0i32; 10]),
            w,
            h,
            true,
            false,
            vec![],
        );
        assert_eq!(to_tuples(&short), to_tuples(&boxes));
        // Degenerate inputs come back empty, never panic.
        let empty = ocr_engine_compute_char_boxes(
            String::new(),
            Vec::new(),
            0,
            x,
            y,
            w,
            h,
            false,
            None,
            0,
            0,
            true,
            true,
            vec![],
        );
        assert!(empty.is_empty());
    }

    /// The punctuation sets cross the `char` ↔ `i32` boundary.
    #[test]
    fn punctuation_predicates_cross() {
        for ch in "。.、「」".chars() {
            assert!(
                ocr_engine_is_vertical_closing_punct(ch as i32)
                    || ocr_engine_is_vertical_opening_punct(ch as i32),
                "{ch:?} must be a vertical mark"
            );
        }
        assert!(ocr_engine_is_vertical_closing_punct('。' as i32));
        assert!(!ocr_engine_is_vertical_closing_punct('あ' as i32));
        assert!(ocr_engine_is_vertical_opening_punct('「' as i32));
        assert!(!ocr_engine_is_vertical_opening_punct('あ' as i32));
        // Invalid code points degrade to U+FFFD (not a mark), no panic.
        assert!(!ocr_engine_is_vertical_closing_punct(-1));
    }

    /// The permutation matches the PC `sort_order` pin, and indices stay in
    /// range so the facade's identity-preserving apply cannot panic.
    #[test]
    fn sort_order_crosses_as_indices() {
        let boxes = vec![
            BoundingBox { x: 132, y: 100, w: 24, h: 24 },
            BoundingBox { x: 100, y: 100, w: 24, h: 24 },
            BoundingBox { x: 100, y: 180, w: 24, h: 24 },
            BoundingBox { x: 380, y: 100, w: 24, h: 40 },
            BoundingBox { x: 380, y: 150, w: 24, h: 40 },
            BoundingBox { x: 300, y: 110, w: 24, h: 40 },
            BoundingBox { x: 300, y: 160, w: 24, h: 40 },
        ];
        assert_eq!(ocr_engine_sort_order(boxes), vec![1, 0, 2, 3, 4, 5, 6]);
        assert!(ocr_engine_sort_order(vec![]).is_empty());
    }

    fn step(entries: &[(i32, f32)]) -> Vec<GapCell> {
        entries
            .iter()
            .map(|&(ch, score)| GapCell { ch, score })
            .collect()
    }

    /// The re-decode walk crosses: blank resets, space never collapses,
    /// repeats collapse, columns interpolate, and the empty cache is `None`.
    #[test]
    fn re_decode_walk_crosses() {
        let raw = vec![
            step(&[('あ' as i32, 0.90), ('\u{3000}' as i32, 0.10)]),
            step(&[('あ' as i32, 0.80)]),
            step(&[('\u{3000}' as i32, 0.90)]),
            step(&[('あ' as i32, 0.70)]),
            step(&[(' ' as i32, 0.60)]),
            step(&[(' ' as i32, 0.50)]),
            step(&[('い' as i32, 0.90)]),
        ];
        let re = ocr_engine_re_decode(raw, false).expect("walk decodes");
        assert_eq!(re.text, "ああ  い");
        assert_eq!(re.char_cols, vec![0.0, 3.0, 4.0, 5.0, 6.0]);
        assert_eq!(re.alternatives.len(), 5);
        assert!(ocr_engine_re_decode(vec![], false).is_none());
    }

    /// Vertical re-decode normalises punctuation through the boundary.
    #[test]
    fn re_decode_vertical_normalisation_crosses() {
        let raw = vec![
            step(&[('?' as i32, 0.9)]),
            step(&[('…' as i32, 0.8)]),
            step(&[('あ' as i32, 0.9)]),
        ];
        let re = ocr_engine_re_decode(raw, true).expect("walk decodes");
        assert_eq!(re.text, "？︙あ");
        assert_eq!(re.alternatives[0][0].ch, '？' as i32);
    }

    /// The ARGB → RGB conversion and its exact-size guard, direct on the
    /// private converter.
    #[test]
    fn argb_pixels_convert_and_short_arrays_skip() {
        let px = vec![
            0xFFFF_FFFFu32 as i32,
            0xFF20_2020u32 as i32,
            0xFF00_0000u32 as i32,
            0xFFFF_0000u32 as i32,
        ];
        let rgb = rgb_from_argb_exact(&px, 4, 1).expect("exact-size array");
        assert_eq!(
            rgb,
            vec![255, 255, 255, 32, 32, 32, 0, 0, 0, 255, 0, 0]
        );
        assert!(rgb_from_argb_exact(&px, 5, 1).is_none());
    }
}
