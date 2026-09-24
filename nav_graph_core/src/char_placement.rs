//! UniFFI shim over `jpdict_core::char_placement` (#44) — **WP-15.**
//!
//! The CTC-anchored placement algorithm (CAP) has one source of truth in the
//! PC crate now: `place_with` and the `Options` defaults live in
//! `jpdict_core::char_placement`, the float64 port of the normative Python
//! reference. This file is only the boundary — no algorithm, no copied source.
//!
//! ## What crosses, and what the boundary loses
//!
//! * **f32 in, f64 out.** The mobile port is `Float`; the PC port is `f64`.
//!   The shim widens every `f32` argument (including the option knobs) and
//!   narrows the returned boxes back to `f32`, so the Kotlin facade keeps its
//!   shapes. The widening error is ~1e-7 px, far inside the 1.5 px the JVM
//!   parity test allows.
//! * **ARGB pixels become luminance here.** Kotlin hands over an `IntArray`
//!   of ARGB ints; the shim extracts the RGB bytes and calls
//!   `jpdict_core::char_placement::luminance_from_rgb`, the reference's
//!   channel-mean path. A short array (or `None`) skips the ink pass, matching
//!   the old Kotlin `pixels.size >= crop_w * crop_h` guard.
//! * **`steps` reuse [`crate::blank_gaps::GapCell`].** A step is a
//!   `(char, score)` cell, exactly the gap pipeline's record; the shim maps
//!   `ch` through the usual `i32` → `char` conversion (invalid code points
//!   degrade to U+FFFD) and widens `score` to `f64`.
//! * **`BLANK` is an accessor.** UniFFI cannot export consts, so the Kotlin
//!   `CharPlacement.BLANK` reads `char_placement_blank()` instead of
//!   hardcoding U+3000.
//! * **Three PC-internal `Options` are not on the wire.** The mobile data
//!   class has 27 knobs; the PC struct has 30. `ridge_ls`, `profile_band` and
//!   `profile_smooth` are filled from `Options::default()` — the shim never
//!   exposes them. Two of them are dead in the PC port anyway (`ridge_ls` and
//!   `profile_band` are declared and defaulted but never read; the fit ridge
//!   and the 0.35 cross band are hardcoded, and the mobile port hardcodes the
//!   same values). `profile_smooth = 2` matches the mobile ±2 box blur.
//!
//! ## Kotlin facade contract
//!
//! `com.holopengin.instantjpdict.CharPlacement` keeps its pre-conversion API
//! (`place`, `Step`, `Box`, `Options`, `OpticalClass`, `BLANK`) so
//! `OcrEngine.computeCharBoxes` and `CharPlacementTest` compile unchanged. The
//! facade absorbs every `Char`/`Int`/`UInt`/`Long`/`List` conversion.

use crate::blank_gaps::GapCell;

/// One output box in crop pixels; the cross axis is the whole crop. The PC
/// port returns `[f64; 4]`; the shim narrows to `f32` for the mobile shapes.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PlaceBox {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

/// The mobile `CharPlacement.Options` knobs (27 fields), in declaration order.
///
/// The PC struct has three more (`ridge_ls`, `profile_band`, `profile_smooth`)
/// that the mobile port never exposed; [`to_core_options`] starts from
/// `Options::default()` so those keep the shipped values.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PlaceOptions {
    pub ink_max_pull_em: f32,
    pub window_em: f32,
    pub window_stride: f32,
    pub anchor_tol_em: f32,
    pub anchor_tol_stride: f32,
    pub huber_em: f32,
    pub min_mass_frac: f32,
    pub max_spread_em: f32,
    pub conf_floor: f32,
    pub refine_passes: i32,
    pub final_pass: bool,
    pub extent_pad_px: f32,
    pub extent_floor: f32,
    pub extent_window_em: f32,
    pub extent_grow_frac: f32,
    pub split_floor_frac: f32,
    pub min_half_px: f32,
    pub punct_spread_fallback: bool,
    pub bimodal_retry: bool,
    pub bimodal_valley_em: f32,
    pub bimodal_min_frac: f32,
    pub punct_fallback_window_em: f32,
    pub punct_fallback_max_em: f32,
    pub translate_overlap: bool,
    pub translate_max_em: f32,
    pub translate_passes: i32,
    pub translate_gate_cut: bool,
}

/// Decode one Kotlin `Int` code point into a Rust `char` (U+FFFD for invalid),
/// the boundary convention `char_lm.rs` set.
fn char_from_codepoint(cp: i32) -> char {
    u32::try_from(cp)
        .ok()
        .and_then(char::from_u32)
        .unwrap_or('\u{FFFD}')
}

/// ARGB ints → RGB8 → `luminance_from_rgb`. `None` when the array is shorter
/// than `crop_w * crop_h` (the mobile ink-pass guard), so a truncated buffer
/// skips the ink pass instead of feeding the profile zeroes.
fn luminance_from_argb(pixels: &[i32], crop_w: u32, crop_h: u32) -> Option<Vec<f32>> {
    let need = (crop_w as usize).saturating_mul(crop_h as usize);
    if pixels.len() < need {
        return None;
    }
    let mut rgb = Vec::with_capacity(need * 3);
    for &p in pixels.iter().take(need) {
        let p = p as u32;
        rgb.push(((p >> 16) & 0xFF) as u8);
        rgb.push(((p >> 8) & 0xFF) as u8);
        rgb.push((p & 0xFF) as u8);
    }
    Some(jpdict_core::char_placement::luminance_from_rgb(
        &rgb, crop_w, crop_h,
    ))
}

/// The mobile `Options` → the PC `Options`, keeping the three PC-internal
/// knobs at their shipped defaults.
fn to_core_options(o: &PlaceOptions) -> jpdict_core::char_placement::Options {
    let mut core = jpdict_core::char_placement::Options::default();
    core.ink_max_pull_em = o.ink_max_pull_em as f64;
    core.window_em = o.window_em as f64;
    core.window_stride = o.window_stride as f64;
    core.anchor_tol_em = o.anchor_tol_em as f64;
    core.anchor_tol_stride = o.anchor_tol_stride as f64;
    core.huber_em = o.huber_em as f64;
    core.min_mass_frac = o.min_mass_frac as f64;
    core.max_spread_em = o.max_spread_em as f64;
    core.conf_floor = o.conf_floor as f64;
    core.refine_passes = o.refine_passes;
    core.final_pass = o.final_pass;
    core.extent_pad_px = o.extent_pad_px as f64;
    core.extent_floor = o.extent_floor as f64;
    core.extent_window_em = o.extent_window_em as f64;
    core.extent_grow_frac = o.extent_grow_frac as f64;
    core.split_floor_frac = o.split_floor_frac as f64;
    core.min_half_px = o.min_half_px as f64;
    core.punct_spread_fallback = o.punct_spread_fallback;
    core.bimodal_retry = o.bimodal_retry;
    core.bimodal_valley_em = o.bimodal_valley_em as f64;
    core.bimodal_min_frac = o.bimodal_min_frac as f64;
    core.punct_fallback_window_em = o.punct_fallback_window_em as f64;
    core.punct_fallback_max_em = o.punct_fallback_max_em as f64;
    core.translate_overlap = o.translate_overlap;
    core.translate_max_em = o.translate_max_em as f64;
    core.translate_passes = o.translate_passes;
    core.translate_gate_cut = o.translate_gate_cut;
    core
}

/// Place one line's characters (mobile `CharPlacement.place`).
///
/// Delegates to `jpdict_core::char_placement::place_with`; see its docs for
/// the input contract. Returns one box per character of `text`; each box spans
/// the whole cross axis, and degenerate inputs return an empty list rather
/// than panicking (the PC guard rails).
#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn char_placement_place(
    text: String,
    char_cols: Vec<f32>,
    seq_len_total: i64,
    crop_w: u32,
    crop_h: u32,
    vertical: bool,
    pixels: Option<Vec<i32>>,
    steps: Option<Vec<Vec<GapCell>>>,
    options: PlaceOptions,
) -> Vec<PlaceBox> {
    let seq_len_total = usize::try_from(seq_len_total).unwrap_or(0);
    let lum = pixels
        .as_deref()
        .and_then(|p| luminance_from_argb(p, crop_w, crop_h));
    let core_steps: Option<Vec<Vec<(char, f64)>>> = steps.as_ref().map(|per_step| {
        per_step
            .iter()
            .map(|alts| {
                alts.iter()
                    .map(|c| (char_from_codepoint(c.ch), c.score as f64))
                    .collect()
            })
            .collect()
    });
    let core_opts = to_core_options(&options);
    jpdict_core::char_placement::place_with(
        &text,
        &char_cols,
        seq_len_total,
        crop_w,
        crop_h,
        vertical,
        lum.as_deref().map(|l| (l, crop_w, crop_h)),
        core_steps.as_deref(),
        &core_opts,
    )
    .into_iter()
    .map(|b| PlaceBox {
        left: b[0] as f32,
        top: b[1] as f32,
        right: b[2] as f32,
        bottom: b[3] as f32,
    })
    .collect()
}

/// The decoder's blank (`jpdict_core::char_placement::BLANK`, U+3000), so the
/// Kotlin `CharPlacement.BLANK` is Rust-sourced. UniFFI cannot export consts.
#[uniffi::export]
pub fn char_placement_blank() -> i32 {
    jpdict_core::char_placement::BLANK as i32
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::char_placement` tests, run through the
    //! exported surface (not the upstream module), so the shim's argument
    //! conversion is covered too. The JVM `CharPlacementTest` pins the full
    //! Python-parity fixture at integration; the synthetic properties here
    //! mirror the same contract at the boundary.

    use super::*;

    /// The mobile `Options` defaults, spelled out (UniFFI records have no
    /// defaults, so the Kotlin facade builds this record every call).
    fn default_options() -> PlaceOptions {
        PlaceOptions {
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

    const WHITE: i32 = 0xFFFF_FFFFu32 as i32;
    const INK: i32 = 0xFF20_2020u32 as i32;

    /// A white ARGB crop with vertical ink blobs spanning `[x0, x1)` over the
    /// middle rows (the mobile `CharPlacementEdgeCaseTest` fixture shape).
    fn argb_crop(crop_w: usize, crop_h: usize, blobs: &[(usize, usize)]) -> Vec<i32> {
        let mut px = vec![WHITE; crop_w * crop_h];
        for &(x0, x1) in blobs {
            for y in 4..crop_h.saturating_sub(4) {
                for x in x0..x1 {
                    px[y * crop_w + x] = INK;
                }
            }
        }
        px
    }

    /// The device regression (`CharPlacementEdgeCaseTest`): the last glyph's
    /// Voronoi window clips to the line end and the bimodal walk must not read
    /// past the profile. The shim path also exercises ARGB → luminance.
    #[test]
    fn last_glyph_window_reaching_the_line_end_does_not_walk_past_the_profile() {
        let (crop_w, crop_h) = (80usize, 40usize);
        let pixels = argb_crop(crop_w, crop_h, &[(24, 36), (64, 76)]);
        let boxes = char_placement_place(
            "あい".to_string(),
            vec![1.0, 3.0],
            4,
            crop_w as u32,
            crop_h as u32,
            false,
            Some(pixels),
            None,
            default_options(),
        );
        assert_eq!(boxes.len(), 2, "both glyphs get a box");
        let last = &boxes[1];
        assert!(
            last.left <= 70.0 && last.right >= 70.0,
            "last box must cover its ink centre (x=70): {last:?}"
        );
        // The contract: full cross axis, box order along the reading axis.
        for b in &boxes {
            assert_eq!(b.top, 0.0, "horizontal boxes start at the top edge");
            assert_eq!(b.bottom, crop_h as f32, "…and span the whole cross axis");
        }
    }

    /// Guard rails (PC spec §1.3.3): degenerate inputs fall back or return
    /// empty, never panic — through the exported conversion layer.
    #[test]
    fn guard_rails_degrade_instead_of_panicking() {
        let empty = char_placement_place(
            String::new(),
            Vec::new(),
            0,
            100,
            50,
            false,
            None,
            None,
            default_options(),
        );
        assert!(empty.is_empty());
        let zero_seq = char_placement_place(
            "あ".to_string(),
            vec![0.0],
            0,
            100,
            50,
            false,
            None,
            None,
            default_options(),
        );
        assert!(zero_seq.is_empty());

        // Text longer than the top-K run count falls back to char_cols.
        let steps = vec![vec![GapCell {
            ch: 'あ' as i32,
            score: 2.0,
        }]];
        let boxes = char_placement_place(
            "あいう".to_string(),
            vec![0.0, 1.0, 2.0],
            4,
            100,
            50,
            false,
            None,
            Some(steps),
            default_options(),
        );
        assert_eq!(boxes.len(), 3, "the char_cols fallback still places every char");

        // Short pixel array: no ink pass, boxes still come out.
        let short = vec![0i32; 10];
        let boxes = char_placement_place(
            "あい".to_string(),
            vec![0.0, 2.0],
            4,
            100,
            50,
            false,
            Some(short),
            None,
            default_options(),
        );
        assert_eq!(boxes.len(), 2, "a short pixel array skips the ink pass");

        // Crop < 8 skips the ink pass.
        let tiny = vec![0i32; 4 * 4];
        let boxes = char_placement_place(
            "あい".to_string(),
            vec![0.0, 1.0],
            2,
            4,
            4,
            false,
            Some(tiny),
            None,
            default_options(),
        );
        assert_eq!(boxes.len(), 2, "a sub-8px crop skips the ink pass");
    }

    /// Boxes span the whole cross axis on both orientations, and the count
    /// follows `text`.
    #[test]
    fn boxes_span_the_cross_axis_in_both_orientations() {
        let pixels = vec![WHITE; 40 * 120];
        let h = char_placement_place(
            "あい".to_string(),
            vec![0.0, 1.0],
            2,
            40,
            120,
            false,
            Some(pixels.clone()),
            None,
            default_options(),
        );
        assert!(h.iter().all(|b| b.top == 0.0 && b.bottom == 120.0));
        let v = char_placement_place(
            "あい".to_string(),
            vec![0.0, 1.0],
            2,
            40,
            120,
            true,
            Some(pixels),
            None,
            default_options(),
        );
        assert!(v.iter().all(|b| b.left == 0.0 && b.right == 40.0));
    }

    /// The renderer/tap-target contract (mobile
    /// `CharPlacementTest.boxesContainTheirCharactersInkCentre`): every box
    /// contains the reading-axis ink centre of the character it was placed for.
    /// Evenly spaced glyphs and matching `char_cols` make the true centres
    /// known, so this pins the whole pipeline through the boundary.
    #[test]
    fn boxes_contain_their_glyphs_ink_centre() {
        let (crop_w, crop_h) = (100usize, 40usize);
        let centres: Vec<usize> = (0..5).map(|i| 10 + i * 20).collect();
        let blobs: Vec<(usize, usize)> = centres.iter().map(|&c| (c - 6, c + 6)).collect();
        let pixels = argb_crop(crop_w, crop_h, &blobs);
        let boxes = char_placement_place(
            "あいうえお".to_string(),
            vec![0.0, 1.0, 2.0, 3.0, 4.0],
            5,
            crop_w as u32,
            crop_h as u32,
            false,
            Some(pixels),
            None,
            default_options(),
        );
        assert_eq!(boxes.len(), 5);
        for (i, b) in boxes.iter().enumerate() {
            let centre = centres[i] as f32;
            assert!(
                b.left - 0.5 <= centre && centre <= b.right + 0.5,
                "glyph {i} ink centre {centre} outside box [{}, {}]",
                b.left,
                b.right
            );
        }
    }

    /// The ARGB → RGB → luminance conversion, and the short-array guard.
    #[test]
    fn argb_pixels_convert_to_luminance_and_short_arrays_skip() {
        let px = vec![
            0xFFFF_FFFFu32 as i32, // white
            0xFF20_2020u32 as i32, // 0x20 channel mean
            0xFF00_0000u32 as i32, // black
            0xFFFF_0000u32 as i32, // red → (255+0+0)/3
        ];
        let lum = luminance_from_argb(&px, 4, 1).expect("exact-size array");
        assert_eq!(lum, vec![255.0, 32.0, 0.0, 85.0]);
        assert!(
            luminance_from_argb(&px, 5, 1).is_none(),
            "a short array skips the ink pass"
        );
    }

    /// The `steps` boundary: `Char.code` ints decode to chars and the f32
    /// scores widen; invalid code points degrade instead of panicking.
    #[test]
    fn steps_boundary_converts_chars_and_scores() {
        let steps = vec![
            vec![
                GapCell {
                    ch: 'あ' as i32,
                    score: 1.0,
                },
                GapCell {
                    ch: 'い' as i32,
                    score: 0.5,
                },
            ],
            vec![GapCell {
                ch: 'い' as i32,
                score: 2.0,
            }],
        ];
        let boxes = char_placement_place(
            "あい".to_string(),
            vec![0.0, 1.0],
            2,
            100,
            50,
            false,
            None,
            Some(steps),
            default_options(),
        );
        assert_eq!(boxes.len(), 2);

        let bad = vec![
            vec![GapCell {
                ch: -1,
                score: 1.0,
            }],
            vec![GapCell {
                ch: 'い' as i32,
                score: 1.0,
            }],
        ];
        let boxes = char_placement_place(
            "あい".to_string(),
            vec![0.0, 1.0],
            2,
            100,
            50,
            false,
            None,
            Some(bad),
            default_options(),
        );
        assert_eq!(boxes.len(), 2, "an invalid code point degrades to U+FFFD");
    }

    /// Every one of the 27 mobile knobs lands on its PC field, and the three
    /// PC-internal extras stay at their shipped defaults. Direct on the
    /// private converter, so a swapped or dropped field cannot hide behind an
    /// unaffected fixture.
    #[test]
    fn options_boundary_maps_every_field_and_keeps_pc_internals() {
        let o = PlaceOptions {
            ink_max_pull_em: 1.5,
            window_em: 2.5,
            window_stride: 3.5,
            anchor_tol_em: 4.5,
            anchor_tol_stride: 5.5,
            huber_em: 6.5,
            min_mass_frac: 7.5,
            max_spread_em: 8.5,
            conf_floor: 9.5,
            refine_passes: 3,
            final_pass: false,
            extent_pad_px: 10.5,
            extent_floor: 11.5,
            extent_window_em: 12.5,
            extent_grow_frac: 13.5,
            split_floor_frac: 14.5,
            min_half_px: 15.5,
            punct_spread_fallback: false,
            bimodal_retry: false,
            bimodal_valley_em: 16.5,
            bimodal_min_frac: 17.5,
            punct_fallback_window_em: 18.5,
            punct_fallback_max_em: 19.5,
            translate_overlap: false,
            translate_max_em: 20.5,
            translate_passes: 4,
            translate_gate_cut: true,
        };
        let c = to_core_options(&o);
        assert_eq!(c.ink_max_pull_em, 1.5);
        assert_eq!(c.window_em, 2.5);
        assert_eq!(c.window_stride, 3.5);
        assert_eq!(c.anchor_tol_em, 4.5);
        assert_eq!(c.anchor_tol_stride, 5.5);
        assert_eq!(c.huber_em, 6.5);
        assert_eq!(c.min_mass_frac, 7.5);
        assert_eq!(c.max_spread_em, 8.5);
        assert_eq!(c.conf_floor, 9.5);
        assert_eq!(c.refine_passes, 3);
        assert!(!c.final_pass);
        assert_eq!(c.extent_pad_px, 10.5);
        assert_eq!(c.extent_floor, 11.5);
        assert_eq!(c.extent_window_em, 12.5);
        assert_eq!(c.extent_grow_frac, 13.5);
        assert_eq!(c.split_floor_frac, 14.5);
        assert_eq!(c.min_half_px, 15.5);
        assert!(!c.punct_spread_fallback);
        assert!(!c.bimodal_retry);
        assert_eq!(c.bimodal_valley_em, 16.5);
        assert_eq!(c.bimodal_min_frac, 17.5);
        assert_eq!(c.punct_fallback_window_em, 18.5);
        assert_eq!(c.punct_fallback_max_em, 19.5);
        assert!(!c.translate_overlap);
        assert_eq!(c.translate_max_em, 20.5);
        assert_eq!(c.translate_passes, 4);
        assert!(c.translate_gate_cut);

        let d = jpdict_core::char_placement::Options::default();
        assert_eq!(c.ridge_ls, d.ridge_ls, "PC-internal ridge_ls stays default");
        assert_eq!(c.profile_band, d.profile_band, "PC-internal profile_band stays default");
        assert_eq!(c.profile_smooth, d.profile_smooth, "PC-internal profile_smooth stays default");
    }

    /// The exported blank accessor matches upstream, so the Kotlin mirror
    /// cannot drift from the decoder's blank.
    #[test]
    fn exported_blank_matches_upstream() {
        assert_eq!(
            char_placement_blank(),
            jpdict_core::char_placement::BLANK as i32
        );
        assert_eq!(char_placement_blank(), '\u{3000}' as i32);
    }
}
