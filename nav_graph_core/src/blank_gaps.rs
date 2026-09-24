//! UniFFI shim over `jpdict_core::blank_gaps` (#44 Feature 2) — **WP-13.**
//!
//! The mobile blank-gap pipeline has one source of truth in the PC crate now:
//! `GapDetector` (the spacing-ratio detector, per-orientation thresholds, the
//! three geometry sources and the CTC timestep walk) and the materialiser
//! (`with_gap_char_at` / `apply_blank_gaps`) both live in
//! `jpdict_core::blank_gaps`. This file is only the boundary.
//!
//! ## What crosses
//!
//! The detector reads a `LineResult` and the materialiser returns a new one, so
//! the mobile `LineResult` subset they touch crosses as [`GapLine`]: text,
//! orientation, the three geometry sources (`char_boxes`, `char_cols`,
//! `raw_alternatives`), the crop geometry, the override map and the alternatives
//! lists. Fields the pipeline never reads (the quad, chunk boxes, the sample
//! path) stay on the Kotlin side and are copied back by the facade.
//!
//! ## Boundary losses
//!
//! * `char` crosses as `i32`, `usize` as `i64`, and the override `Map` as a
//!   record list — the usual conventions (`char_lm.rs`).
//! * Identity is a facade concern: mobile returns the *same* `LineResult` when
//!   nothing changed (`BlankGaps.apply` on a horizontal or already-gapped line,
//!   `withGapCharAt` out of range), so the Kotlin facade short-circuits those
//!   before calling here.
//! * Consts cannot cross; the defaults (`DEFAULT_VERTICAL_RATIO`,
//!   `DEFAULT_HORIZONTAL_RATIO`, `DEFAULT_TIMESTEP_STRIDE_PX`,
//!   `MIN_EMITTED_CHARS`, `TIMESTEP_BLANK_CHAR`) are exported as accessors so
//!   the Kotlin companion reads the real values.

use std::collections::BTreeMap;

use crate::BoundingBox;

/// One `(char, score)` cell of an alternatives list.
#[derive(Clone, Debug, uniffi::Record)]
pub struct GapCell {
    pub ch: i32,
    pub score: f32,
}

/// One manual override on a line: `index -> (char, score)`.
#[derive(Clone, Debug, uniffi::Record)]
pub struct GapOverride {
    pub index: i32,
    pub ch: i32,
    pub score: f32,
}

/// The mobile `LineResult` subset the gap pipeline reads and writes.
#[derive(Clone, Debug, uniffi::Record)]
pub struct GapLine {
    pub text: String,
    pub is_vertical: bool,
    /// Raw/unclipped character boxes, `x`/`y`/`w`/`h`.
    pub char_boxes: Vec<BoundingBox>,
    /// Per-emitted-character alternatives (grown by the materialiser).
    pub alternatives: Vec<Vec<GapCell>>,
    /// Per-timestep top-K, the detector's last geometry resort.
    pub raw_alternatives: Vec<Vec<GapCell>>,
    /// CTC timestep column per emitted character, the second geometry source.
    pub char_cols: Vec<f32>,
    pub overrides: Vec<GapOverride>,
    pub crop_w: i32,
    pub crop_h: i32,
    pub crop_x: i32,
    pub crop_y: i32,
    pub seq_len_total: i32,
}

/// One detected gap: the character index the placeholder belongs at, this
/// pair's spacing over the line's median spacing, and the spacing in pixels.
#[derive(Clone, Debug, uniffi::Record)]
pub struct GapResult {
    pub insert_at: i64,
    pub ratio: f32,
    pub span_px: f32,
}

/// Decode one Kotlin `Int` code point into a Rust `char` (U+FFFD for invalid).
fn char_from_codepoint(cp: i32) -> char {
    u32::try_from(cp)
        .ok()
        .and_then(char::from_u32)
        .unwrap_or('\u{FFFD}')
}

fn to_core_box(b: &BoundingBox) -> jpdict_core::models::BoundingBox {
    jpdict_core::models::BoundingBox::new(b.x, b.y, b.w, b.h, 1.0)
}

fn to_core_cells(cells: &[GapCell]) -> Vec<(char, f32)> {
    cells
        .iter()
        .map(|c| (char_from_codepoint(c.ch), c.score))
        .collect()
}

fn to_core_line(line: &GapLine) -> jpdict_core::models::LineResult {
    let overrides: BTreeMap<i32, (char, f32)> = line
        .overrides
        .iter()
        .map(|o| (o.index, (char_from_codepoint(o.ch), o.score)))
        .collect();
    jpdict_core::models::LineResult {
        text: line.text.clone(),
        char_boxes: line.char_boxes.iter().map(to_core_box).collect(),
        alternatives: line.alternatives.iter().map(|c| to_core_cells(c)).collect(),
        raw_alternatives: line.raw_alternatives.iter().map(|c| to_core_cells(c)).collect(),
        sample_txt: None,
        is_vertical: line.is_vertical,
        chunk_boxes: Vec::new(),
        char_cols: line.char_cols.clone(),
        overrides,
        crop_w: line.crop_w,
        crop_h: line.crop_h,
        crop_x: line.crop_x,
        crop_y: line.crop_y,
        seq_len_total: line.seq_len_total,
    }
}

fn from_core_line(line: &jpdict_core::models::LineResult) -> GapLine {
    GapLine {
        text: line.text.clone(),
        is_vertical: line.is_vertical,
        char_boxes: line
            .char_boxes
            .iter()
            .map(|b| BoundingBox {
                x: b.x,
                y: b.y,
                w: b.w,
                h: b.h,
            })
            .collect(),
        alternatives: line
            .alternatives
            .iter()
            .map(|cells| {
                cells
                    .iter()
                    .map(|(c, s)| GapCell {
                        ch: *c as i32,
                        score: *s,
                    })
                    .collect()
            })
            .collect(),
        raw_alternatives: line
            .raw_alternatives
            .iter()
            .map(|cells| {
                cells
                    .iter()
                    .map(|(c, s)| GapCell {
                        ch: *c as i32,
                        score: *s,
                    })
                    .collect()
            })
            .collect(),
        char_cols: line.char_cols.clone(),
        overrides: line
            .overrides
            .iter()
            .map(|(index, (ch, score))| GapOverride {
                index: *index,
                ch: *ch as i32,
                score: *score,
            })
            .collect(),
        crop_w: line.crop_w,
        crop_h: line.crop_h,
        crop_x: line.crop_x,
        crop_y: line.crop_y,
        seq_len_total: line.seq_len_total,
    }
}

fn to_result(gap: jpdict_core::blank_gaps::Gap) -> GapResult {
    GapResult {
        insert_at: gap.insert_at as i64,
        ratio: gap.ratio,
        span_px: gap.span_px,
    }
}

/// Detect gaps using the line's own orientation threshold (mobile
/// `GapDetector.detect(line)`).
#[uniffi::export]
pub fn blank_gap_detect(
    line: GapLine,
    vertical_threshold: f32,
    horizontal_threshold: f32,
) -> Vec<GapResult> {
    jpdict_core::blank_gaps::GapDetector::new(vertical_threshold, horizontal_threshold)
        .detect(&to_core_line(&line))
        .into_iter()
        .map(to_result)
        .collect()
}

/// Detect gaps using an explicit `threshold` (mobile `detect(line, threshold)`),
/// so a caller can sweep the curve without rebuilding the detector.
#[uniffi::export]
pub fn blank_gap_detect_with(line: GapLine, threshold: f32) -> Vec<GapResult> {
    jpdict_core::blank_gaps::GapDetector::default()
        .detect_with(&to_core_line(&line), threshold)
        .into_iter()
        .map(to_result)
        .collect()
}

/// Materialise every measured gap as a placeholder (mobile `BlankGaps.apply`):
/// vertical lines only, idempotent, insertions right-to-left. The facade keeps
/// the identity short-circuits (horizontal, already gapped, no gaps).
#[uniffi::export]
pub fn blank_gaps_apply(line: GapLine) -> GapLine {
    from_core_line(&jpdict_core::blank_gaps::apply_blank_gaps(&to_core_line(&line)))
}

/// Insert one placeholder (mobile `LineResult.withGapCharAt`), growing every
/// parallel list together. `gap_alternatives` is the synthetic alternatives
/// entry; `None` uses the placeholder convention.
#[uniffi::export]
pub fn blank_gaps_with_gap_char_at(
    line: GapLine,
    index: i64,
    column: f32,
    gap_alternatives: Option<Vec<GapCell>>,
) -> GapLine {
    let index = usize::try_from(index).expect("with_gap_char_at: index must be non-negative");
    let alts = gap_alternatives.map(|cells| to_core_cells(&cells));
    from_core_line(&jpdict_core::blank_gaps::with_gap_char_at(
        &to_core_line(&line),
        index,
        column,
        alts,
    ))
}

/// The CTC timestep column per emitted character recovered from the raw
/// alternatives (mobile `timestepColumns`).
#[uniffi::export]
pub fn blank_gap_timestep_columns(raw: Vec<Vec<GapCell>>) -> Vec<f32> {
    let core: Vec<Vec<(char, f32)>> = raw.iter().map(|c| to_core_cells(c)).collect();
    jpdict_core::blank_gaps::timestep_columns(&core)
}

/// Median of a float list; 0 for an empty list (mobile `medianOf`).
#[uniffi::export]
pub fn blank_gap_median(values: Vec<f32>) -> f32 {
    let mut values = values;
    jpdict_core::blank_gaps::median_of(&mut values)
}

/// Vertical trigger default (mobile `GapDetector.DEFAULT_VERTICAL_RATIO`).
#[uniffi::export]
pub fn blank_gap_default_vertical_ratio() -> f32 {
    jpdict_core::blank_gaps::DEFAULT_VERTICAL_RATIO
}

/// Horizontal trigger default (mobile `GapDetector.DEFAULT_HORIZONTAL_RATIO`).
#[uniffi::export]
pub fn blank_gap_default_horizontal_ratio() -> f32 {
    jpdict_core::blank_gaps::DEFAULT_HORIZONTAL_RATIO
}

/// Model stride fallback (mobile `GapDetector.DEFAULT_TIMESTEP_STRIDE_PX`).
#[uniffi::export]
pub fn blank_gap_default_timestep_stride_px() -> f32 {
    jpdict_core::blank_gaps::DEFAULT_TIMESTEP_STRIDE_PX
}

/// Minimum emitted characters (mobile `GapDetector.MIN_EMITTED_CHARS`).
#[uniffi::export]
pub fn blank_gap_min_emitted_chars() -> i64 {
    jpdict_core::blank_gaps::MIN_EMITTED_CHARS as i64
}

/// The blank marker inside `raw_alternatives` (mobile `TIMESTEP_BLANK_CHAR`).
#[uniffi::export]
pub fn blank_gap_timestep_blank_char() -> i32 {
    jpdict_core::blank_gaps::TIMESTEP_BLANK_CHAR as i32
}

#[cfg(test)]
mod tests {
    //! Boundary mirror of the mobile gap pipeline: the record conversion plus
    //! the detector and materialiser through the exported surface. The JVM
    //! suites (`GapDetectorTest`, `BlankGapsTest`, `LineResultGapTest`) and the
    //! `gap-detection-0*` corpus cases pin the same behaviour across the
    //! boundary.

    use super::*;

    fn box_(x: i32, y: i32, w: i32, h: i32) -> BoundingBox {
        BoundingBox { x, y, w, h }
    }

    /// The measured fixture: five characters at y-centres 10/30/50/90/110,
    /// spacings 20/20/40/20 (median 20), so the third interval is 2.00×.
    fn gapped_line() -> GapLine {
        GapLine {
            text: "あいうえお".to_string(),
            is_vertical: true,
            char_boxes: [10, 30, 50, 90, 110]
                .iter()
                .map(|c| box_(0, c - 10, 40, 20))
                .collect(),
            alternatives: Vec::new(),
            raw_alternatives: Vec::new(),
            char_cols: Vec::new(),
            overrides: Vec::new(),
            crop_w: 0,
            crop_h: 0,
            crop_x: 0,
            crop_y: 0,
            seq_len_total: 0,
        }
    }

    #[test]
    fn detect_reports_the_measured_gap_through_the_boundary() {
        let got = blank_gap_detect(gapped_line(), 1.6, 1.8);
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].insert_at, 3);
        assert!((got[0].ratio - 2.0).abs() < 1e-4);
        assert!((got[0].span_px - 40.0).abs() < 1e-4);
    }

    #[test]
    fn per_orientation_thresholds_and_the_inclusive_boundary() {
        // A 1.7 ratio fires vertically (1.6) and not horizontally (1.8).
        let mut line = gapped_line();
        line.char_boxes = [0, 20, 40, 74, 94]
            .iter()
            .map(|c| box_(0, *c, 40, 20))
            .collect();
        assert_eq!(blank_gap_detect(line.clone(), 1.6, 1.8).len(), 1);
        line.is_vertical = false;
        assert!(blank_gap_detect(line, 1.6, 1.8).is_empty());

        // Exactly 1.6 fires (inclusive).
        let mut exact = gapped_line();
        exact.char_boxes = [0, 20, 40, 72, 92]
            .iter()
            .map(|c| box_(0, *c, 40, 20))
            .collect();
        assert_eq!(blank_gap_detect(exact, 1.6, 1.8).len(), 1);
    }

    #[test]
    fn char_cols_fallback_uses_the_crop_length_for_span() {
        let mut line = gapped_line();
        line.char_boxes = Vec::new();
        line.char_cols = vec![0.0, 2.0, 4.0, 8.0, 10.0];
        line.crop_h = 55;
        line.seq_len_total = 11;
        let got = blank_gap_detect(line, 1.6, 1.8);
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].insert_at, 3);
        assert!((got[0].ratio - 2.0).abs() < 1e-4);
        // 4 timesteps * (55 / 11) = 20px.
        assert!((got[0].span_px - 20.0).abs() < 1e-4);
    }

    #[test]
    fn timestep_walk_reproduces_the_collapse_rules() {
        let cells = |ch: char| vec![GapCell { ch: ch as i32, score: 1.0 }];
        // blank (U+3000) resets the repeat state, a space never collapses.
        let raw = vec![
            cells('あ'),
            cells('あ'),        // repeat: collapses
            cells('\u{3000}'),  // blank: resets
            cells('あ'),        // emitted after the blank
            cells(' '),         // space: always emitted
            cells(' '),         // space: never collapses
            cells('い'),
        ];
        assert_eq!(blank_gap_timestep_columns(raw), vec![0.0, 3.0, 4.0, 5.0, 6.0]);
    }

    #[test]
    fn degenerate_and_contaminated_geometry_report_nothing() {
        // Everything on one pixel: median spacing 0.
        let mut flat = gapped_line();
        flat.char_boxes = (0..5).map(|_| box_(0, 50, 40, 20)).collect();
        assert!(blank_gap_detect(flat, 1.6, 1.8).is_empty());
        // Fewer than two characters.
        let mut one = gapped_line();
        one.text = "あ".to_string();
        one.char_boxes = vec![box_(0, 10, 40, 20)];
        assert!(blank_gap_detect(one, 1.6, 1.8).is_empty());
        // Geometry that does not describe the text.
        let mut mismatched = gapped_line();
        mismatched.char_boxes = vec![box_(0, 10, 40, 20)];
        assert!(blank_gap_detect(mismatched, 1.6, 1.8).is_empty());
    }

    #[test]
    fn materialise_grows_every_list_and_shifts_overrides() {
        let mut line = gapped_line();
        line.alternatives = "あいうえお"
            .chars()
            .map(|c| vec![GapCell { ch: c as i32, score: 1.0 }])
            .collect();
        line.char_cols = vec![0.0, 2.0, 4.0, 8.0, 10.0];
        line.overrides = vec![GapOverride { index: 4, ch: 'ぇ' as i32, score: 1.0 }];
        let out = blank_gaps_apply(line);
        assert_eq!(out.text, "あいう◌えお");
        assert_eq!(out.char_boxes.len(), 6);
        assert_eq!(out.alternatives.len(), 6);
        assert_eq!(out.alternatives[3][0].ch, '◌' as i32);
        assert_eq!(out.char_cols.len(), 6);
        assert!((out.char_cols[3] - 6.0).abs() < 1e-4);
        // The override on character 4 moved to 5.
        assert_eq!(out.overrides.len(), 1);
        assert_eq!(out.overrides[0].index, 5);
    }

    #[test]
    fn materialise_is_vertical_only_and_idempotent() {
        let mut horizontal = gapped_line();
        horizontal.is_vertical = false;
        let out = blank_gaps_apply(horizontal.clone());
        assert_eq!(out.text, horizontal.text);
        assert_eq!(out.char_boxes.len(), horizontal.char_boxes.len());

        let once = blank_gaps_apply(gapped_line());
        let twice = blank_gaps_apply(once.clone());
        assert_eq!(twice.text, once.text);
    }

    #[test]
    fn with_gap_char_at_handles_ends_custom_alternatives_and_out_of_range() {
        let mut line = gapped_line();
        line.alternatives = "あいうえお"
            .chars()
            .map(|c| vec![GapCell { ch: c as i32, score: 1.0 }])
            .collect();
        // Custom synthetic alternatives entry.
        let out = blank_gaps_with_gap_char_at(
            line.clone(),
            2,
            0.0,
            Some(vec![GapCell { ch: 'X' as i32, score: 0.5 }]),
        );
        assert_eq!(out.text, "あい◌うえお");
        assert_eq!(out.alternatives[2][0].ch, 'X' as i32);
        // Inserting at the end reuses the single neighbour.
        let end = blank_gaps_with_gap_char_at(line.clone(), 5, 0.0, None);
        assert_eq!(end.text, "あいうえお◌");
        assert_eq!(end.char_boxes.len(), 6);
        // Past the end is a no-op clone.
        let past = blank_gaps_with_gap_char_at(line.clone(), 6, 0.0, None);
        assert_eq!(past.text, line.text);
    }

    #[test]
    fn exported_consts_match_upstream() {
        assert_eq!(
            blank_gap_default_vertical_ratio(),
            jpdict_core::blank_gaps::DEFAULT_VERTICAL_RATIO
        );
        assert_eq!(
            blank_gap_default_horizontal_ratio(),
            jpdict_core::blank_gaps::DEFAULT_HORIZONTAL_RATIO
        );
        assert_eq!(
            blank_gap_default_timestep_stride_px(),
            jpdict_core::blank_gaps::DEFAULT_TIMESTEP_STRIDE_PX
        );
        assert_eq!(
            blank_gap_min_emitted_chars(),
            jpdict_core::blank_gaps::MIN_EMITTED_CHARS as i64
        );
        assert_eq!(
            char_from_codepoint(blank_gap_timestep_blank_char()),
            '\u{3000}'
        );
        assert!((blank_gap_median(vec![1.0, 3.0]) - 2.0).abs() < 1e-6);
        assert_eq!(blank_gap_median(Vec::new()), 0.0);
    }
}
