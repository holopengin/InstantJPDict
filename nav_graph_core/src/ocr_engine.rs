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

/// The constants `jpdict_core::char_boxes` reduces a crop with when it measures
/// the snap evidence, published so the Kotlin facade measures with the crate's
/// numbers instead of restating them. A change to a threshold or to the band
/// cannot silently desynchronise the boundary: the record moves with it.
#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct SnapSpec {
    /// Ink is `lum < ink_below` when the background is light.
    pub ink_below: f32,
    /// Ink is `lum > ink_above` when the background is dark.
    pub ink_above: f32,
    /// The border median above `bg_median_above` means a light background.
    pub bg_median_above: f32,
    /// The border sampling stride, in pixels.
    pub border_stride: i32,
    /// The central cross band's lower edge, as a fraction of the cross axis.
    pub band_lo: f32,
    /// …and its upper edge.
    pub band_hi: f32,
    /// The profile box-blur radius (Rust-side; the facade never blurs).
    pub blur_radius: i32,
    /// The largest ink count a count series can carry, so a cross axis thicker
    /// than this keeps taking the pixel path.
    pub max_count: i32,
    /// The exclusive upper bound of the ink channel sums on a light background:
    /// ink is `r + g + b < ink_below_sum`. Luminance is the channel mean of
    /// 8-bit channels and both thresholds are exact multiples of three, so this
    /// integer test is *identical* to the float one over every possible pixel —
    /// the facade counts ink without a float division per pixel.
    pub ink_below_sum: i32,
    /// The inclusive lower bound of the ink channel sums on a dark background:
    /// ink is `r + g + b >= ink_above_sum`.
    pub ink_above_sum: i32,
}

/// [`SnapSpec`], Rust-sourced. Read once per process and cached by the facade.
#[uniffi::export]
pub fn ocr_engine_snap_spec() -> SnapSpec {
    let s = jpdict_core::char_boxes::snap_spec();
    SnapSpec {
        ink_below: s.ink_below,
        ink_above: s.ink_above,
        bg_median_above: s.bg_median_above,
        border_stride: s.border_stride as i32,
        band_lo: s.band_lo,
        band_hi: s.band_hi,
        blur_radius: s.blur_radius,
        max_count: s.max_count as i32,
        ink_below_sum: s.ink_below_sum,
        ink_above_sum: s.ink_above_sum,
    }
}

/// The mobile `OcrEngine.computeCharBoxes` legacy chain with the snap stage's
/// ink evidence measured by the facade instead of shipped as pixels.
///
/// The evidence is three byte arrays, all bulk-copied by the FFI layer (no
/// per-element boxing, unlike the `List<Int>` pixel list it replaces):
///
/// * `border_rgb` — the stride-`border_stride` border sample, top/bottom row
///   pairs then left/right column pairs, as raw RGB8 triples. Rust takes the
///   median and decides the polarity, so the rule stays in the crate.
/// * `ink_dark` — ink count per reading-axis position inside the central band,
///   counting pixels darker than `ink_below`.
/// * `ink_light` — the same count for pixels lighter than `ink_above`.
///
/// The facade sends both count series because the polarity is Rust's to decide;
/// it selects here. Missing evidence, a count at the `max_count` ceiling (a
/// cross axis too thick to reduce) and a wrong-shaped series all skip the snap
/// stage, exactly as the old missing/short pixel array did.
#[uniffi::export]
#[allow(clippy::too_many_arguments)] // mirrors the PC frame plus the evidence
pub fn ocr_engine_compute_char_boxes_evidence(
    text: String,
    char_cols: Vec<f32>,
    seq_len_total: i64,
    crop_x: i32,
    crop_y: i32,
    crop_w: i32,
    crop_h: i32,
    is_vertical: bool,
    border_rgb: Option<Vec<u8>>,
    ink_dark: Option<Vec<u8>>,
    ink_light: Option<Vec<u8>>,
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
    let evidence = snap_evidence_from_rgb_samples(
        border_rgb.as_deref(),
        ink_dark.as_deref(),
        ink_light.as_deref(),
    );
    let core_boxes = jpdict_core::char_boxes::compute_char_boxes_with_evidence(
        &text,
        &char_cols,
        seq_len_total,
        crop_x,
        crop_y,
        crop_w,
        crop_h,
        is_vertical,
        evidence.as_ref(),
        (pix_w, pix_h),
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

/// The facade's three byte arrays → the crate's [`SnapEvidence`]: the border
/// sample decides the polarity, the polarity picks the count series. `None` when
/// any series is missing or empty, so a partial measurement degrades to the
/// no-snap result instead of snapping on a half profile.
fn snap_evidence_from_rgb_samples(
    border_rgb: Option<&[u8]>,
    ink_dark: Option<&[u8]>,
    ink_light: Option<&[u8]>,
) -> Option<jpdict_core::char_boxes::SnapEvidence> {
    let border = jpdict_core::char_boxes::luminance_from_rgb_samples(border_rgb?);
    let bg_light = jpdict_core::char_boxes::snap_polarity(&border)?;
    let dark = ink_dark?;
    let light = ink_light?;
    if dark.len() != light.len() || dark.is_empty() {
        return None;
    }
    let picked: &[u8] = if bg_light { dark } else { light };
    Some(jpdict_core::char_boxes::SnapEvidence::new(
        bg_light,
        picked.iter().map(|&c| c as f32).collect(),
    ))
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

    // ─── compact snap evidence: pixels vs. the measured record ───────────────

    /// A line crop as the facade sees it: an ARGB `IntArray` plus its dims.
    /// `glyphs` are `(x0, x1)` ink runs, `invert` gives light-on-dark.
    fn argb_line(w: usize, h: usize, glyphs: &[(usize, usize)], weight: usize, invert: bool) -> Vec<i32> {
        let mut px = vec![WHITE; w * h];
        for &(c0, c1) in glyphs {
            for x in c0..(c1 + weight).min(w) {
                for y in 2..h.saturating_sub(2) {
                    px[y * w + x] = INK;
                }
            }
        }
        // Edge ink, so the central band and the border polarity both bite.
        for (i, x) in [0usize, 1, w / 2, w - 2].iter().enumerate() {
            px[if i % 2 == 0 { 0 } else { h - 1 } * w + x] = INK;
        }
        if invert {
            for p in px.iter_mut() {
                *p = !*p;
            }
        }
        px
    }

    /// The three 8-bit channel values of an ARGB int, summed — the numerator
    /// of the reference's channel-mean luminance, and the only per-pixel
    /// arithmetic the facade needs (an integer compare, no float division).
    fn channel_sum(p: i32) -> i32 {
        let p = p as u32;
        ((p >> 16) & 0xFF) as i32 + ((p >> 8) & 0xFF) as i32 + (p & 0xFF) as i32
    }

    /// The facade's measurement, transcribed: the border sample at the spec's
    /// stride, the two count series over the central band (integer compares, the
    /// form the spec's `ink_*_sum` exists for), and (only for the report) the
    /// polarity. Deliberately written from the *spec record* the
    /// facade reads, so the two cannot drift: a wrong constant here is a wrong
    /// constant on both sides, and the parity assert below is what catches it.
    fn measure_snap_evidence(
        argb: &[i32],
        pix_w: usize,
        pix_h: usize,
        vertical: bool,
    ) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
        let spec = ocr_engine_snap_spec();
        // The stride-N border sample, as raw RGB8 triples.
        let mut border: Vec<u8> = Vec::new();
        let mut i = 0usize;
        while i < pix_w {
            for p in [argb[i], argb[(pix_h - 1) * pix_w + i]] {
                let p = p as u32;
                border.push(((p >> 16) & 0xFF) as u8);
                border.push(((p >> 8) & 0xFF) as u8);
                border.push((p & 0xFF) as u8);
            }
            i += spec.border_stride as usize;
        }
        let mut j = 0usize;
        while j < pix_h {
            for p in [argb[j * pix_w], argb[j * pix_w + pix_w - 1]] {
                let p = p as u32;
                border.push(((p >> 16) & 0xFF) as u8);
                border.push(((p >> 8) & 0xFF) as u8);
                border.push((p & 0xFF) as u8);
            }
            j += spec.border_stride as usize;
        }
        // The two count series over the central cross band.
        let cross = if vertical { pix_w } else { pix_h };
        let lo = (cross as f32 * spec.band_lo) as usize;
        let hi = (cross as f32 * spec.band_hi) as usize;
        let read = if vertical { pix_h } else { pix_w };
        let (mut dark, mut light) = (vec![0u8; read], vec![0u8; read]);
        for r in 0..read {
            for c in lo..hi.min(cross) {
                let sum = channel_sum(argb[if vertical { r * pix_w + c } else { c * pix_w + r }]);
                if sum < spec.ink_below_sum {
                    dark[r] += 1;
                }
                if sum >= spec.ink_above_sum {
                    light[r] += 1;
                }
            }
        }
        (border, dark, light)
    }

    const WHITE: i32 = 0xFFFF_FFFFu32 as i32;
    const INK: i32 = 0xFF20_2020u32 as i32;

    /// The evidence entry is a pure re-plumbing of the pixel entry: the same
    /// crop measured on the facade side must give *identical* boxes, on both
    /// orientations, both polarities and across a sweep of crop shapes and
    /// glyph layouts. This is the Rust half of the fixture-parity pin; the JVM
    /// half runs the real facade over the real page crops.
    #[test]
    fn evidence_entry_matches_the_pixel_entry_bit_for_bit() {
        let mut state = 0x5eed_5678u64;
        let mut next = move || -> u32 {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            (state >> 33) as u32
        };
        let mut moved = 0;
        for case in 0..48usize {
            let w = 40 + (next() as usize % 220);
            let h = 16 + (next() as usize % 60);
            let n = 2 + (next() as usize % 9);
            let weight = 1 + (next() as usize % 4);
            let invert = case % 2 == 1;
            let vertical = case % 3 == 0;
            let pitch = (w / n).max(6);
            let glyphs: Vec<(usize, usize)> = (0..n)
                .map(|i| (i * pitch + 2 + (next() as usize % 3), i * pitch + pitch / 2))
                .collect();
            let px = argb_line(w, h, &glyphs, weight, invert);
            let cols: Vec<f32> = (0..n).map(|i| i as f32).collect();
            let text: String = "あいうえおかきくけこさしすせそ".chars().take(n).collect();
            let ink: Vec<f32> = (0..n).map(|i| 8.0 + i as f32).collect();
            let (border, dark, light) = measure_snap_evidence(&px, w, h, vertical);
            let (pw, ph) = (w as i32, h as i32);
            let (cw, ch) = (w as i32, h as i32);
            for &(snap, uniform) in &[(true, true), (true, false), (false, true)] {
                let via_pixels = ocr_engine_compute_char_boxes(
                    text.clone(),
                    cols.clone(),
                    (n + 3) as i64,
                    0,
                    0,
                    cw,
                    ch,
                    vertical,
                    Some(px.clone()),
                    pw,
                    ph,
                    snap,
                    uniform,
                    ink.clone(),
                );
                let via_evidence = ocr_engine_compute_char_boxes_evidence(
                    text.clone(),
                    cols.clone(),
                    (n + 3) as i64,
                    0,
                    0,
                    cw,
                    ch,
                    vertical,
                    Some(border.clone()),
                    Some(dark.clone()),
                    Some(light.clone()),
                    pw,
                    ph,
                    snap,
                    uniform,
                    ink.clone(),
                );
                assert_eq!(
                    to_tuples(&via_pixels),
                    to_tuples(&via_evidence),
                    "case {case} (w={w} h={h} n={n} v={vertical} inv={invert} snap={snap} unif={uniform})"
                );
            }
            let plain = ocr_engine_compute_char_boxes(
                text.clone(),
                cols.clone(),
                (n + 3) as i64,
                0,
                0,
                cw,
                ch,
                vertical,
                None,
                0,
                0,
                true,
                true,
                ink.clone(),
            );
            let inked = ocr_engine_compute_char_boxes_evidence(
                text,
                cols,
                (n + 3) as i64,
                0,
                0,
                cw,
                ch,
                vertical,
                Some(border),
                Some(dark),
                Some(light),
                pw,
                ph,
                true,
                true,
                ink,
            );
            if to_tuples(&plain) != to_tuples(&inked) {
                moved += 1;
            }
        }
        assert!(moved >= 16, "only {moved} cases moved boxes; the sweep is too weak");
    }

    /// The evidence boundary's own guards, through the exported conversion: a
    /// missing series, mismatched series and a saturated count all degrade to
    /// the no-snap (legacy column) result, and the spec record is the crate's.
    #[test]
    fn evidence_boundary_guards_and_spec_cross_intact() {
        let (w, h) = (200usize, 40usize);
        let glyphs = vec![(10usize, 14usize), (60, 64), (110, 114), (160, 164)];
        let px = argb_line(w, h, &glyphs, 8, false);
        let (border, dark, light) = measure_snap_evidence(&px, w, h, false);
        let cols = vec![0.0f32, 1.0, 2.0, 3.0];
        let ink = vec![8.0f32; 4];
        let plain = ocr_engine_compute_char_boxes(
            "あいうえ".to_string(),
            cols.clone(),
            5,
            0,
            0,
            w as i32,
            h as i32,
            false,
            None,
            0,
            0,
            true,
            true,
            ink.clone(),
        );
        let via = |border: Option<Vec<u8>>,
                   dark: Option<Vec<u8>>,
                   light: Option<Vec<u8>>,
                   pw: i32| {
            ocr_engine_compute_char_boxes_evidence(
                "あいうえ".to_string(),
                cols.clone(),
                5,
                0,
                0,
                w as i32,
                h as i32,
                false,
                border,
                dark,
                light,
                pw,
                h as i32,
                true,
                true,
                ink.clone(),
            )
        };
        // The happy path really snaps.
        assert_ne!(
            to_tuples(&via(Some(border.clone()), Some(dark.clone()), Some(light.clone()), w as i32)),
            to_tuples(&plain)
        );
        // Missing evidence of any kind: no snap.
        for (b, d, l) in [
            (None, Some(dark.clone()), Some(light.clone())),
            (Some(border.clone()), None, Some(light.clone())),
            (Some(border.clone()), Some(dark.clone()), None),
        ] {
            assert_eq!(to_tuples(&via(b, d, l, w as i32)), to_tuples(&plain));
        }
        // Mismatched series: no snap (never a half profile).
        let mut short = dark.clone();
        short.pop();
        assert_eq!(
            to_tuples(&via(Some(border.clone()), Some(short), Some(light.clone()), w as i32)),
            to_tuples(&plain)
        );
        // A count at the ceiling means a cross axis too thick to reduce: the
        // crate refuses it, so the entry degrades to the legacy columns.
        let mut sat = dark.clone();
        sat[2] = 255;
        assert_eq!(
            to_tuples(&via(Some(border.clone()), Some(sat), Some(light.clone()), w as i32)),
            to_tuples(&plain)
        );
        // A pixel frame the series does not match: no snap.
        assert_eq!(
            to_tuples(&via(Some(border), Some(dark), Some(light), (w + 1) as i32)),
            to_tuples(&plain)
        );
        // The spec record is the crate's, field for field.
        let s = ocr_engine_snap_spec();
        let core = jpdict_core::char_boxes::snap_spec();
        assert_eq!(s.ink_below, core.ink_below);
        assert_eq!(s.ink_above, core.ink_above);
        assert_eq!(s.bg_median_above, core.bg_median_above);
        assert_eq!(s.border_stride, core.border_stride as i32);
        assert_eq!(s.band_lo, core.band_lo);
        assert_eq!(s.band_hi, core.band_hi);
        assert_eq!(s.blur_radius, core.blur_radius);
        assert_eq!(s.max_count, core.max_count as i32);
        assert_eq!(s.ink_below_sum, core.ink_below_sum);
        assert_eq!(s.ink_above_sum, core.ink_above_sum);
        // The integer test is the crate's float test, for every pixel.
        for sum in 0..=765i32 {
            let lum = sum as f32 / 3.0;
            assert_eq!(jpdict_core::char_boxes::is_ink_sum(sum, true), lum < s.ink_below);
            assert_eq!(jpdict_core::char_boxes::is_ink_sum(sum, false), lum > s.ink_above);
        }
        // The facade's band truncation matches the crate's own band range.
        for cross in [8usize, 17, 40, 60, 121] {
            let lo = (cross as f32 * s.band_lo) as usize;
            let hi = (cross as f32 * s.band_hi) as usize;
            assert_eq!((lo, hi), jpdict_core::char_boxes::snap_band_range(cross));
        }
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
