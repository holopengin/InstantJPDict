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
//! ## The plan, for a host that keeps its own lists
//!
//! [`blank_gaps_apply`] crosses that whole line **in and out** — the
//! alternatives and the per-timestep top-K lists are the bulk of it, and they
//! come back only to be cloned and grown by one entry. [`blank_gaps_plan`]
//! crosses the detector's own inputs ([`GapPlanLine`], one record so the whole
//! line is a single indirect buffer) and returns [`GapPlan`]: a handful of
//! integers per line, from which the host inserts into its own parallel lists.
//! The three "was this list full-length?" decisions travel with the plan, so
//! the host never recomputes a length in its own character units (Kotlin counts
//! UTF-16 units, Rust code points, and they differ on the supplementary-plane
//! vocabulary entries).
//!
//! `raw_alternatives` is the detector's last-resort geometry source, so the
//! first call leaves it empty and the plan answers
//! [`GapPlan::needs_raw_alternatives`] — whether the walk was reachable at all.
//! Only an empty plan *and* a "yes" needs the second call, so a line whose char
//! boxes the layout stage already produced never pays for those lists.
//!
//! [`blank_gaps_apply`] stays exported: it is the parity harness the JVM
//! `BlankGapsPlanTest` measures the plan against, and the reference the plan is
//! read to be equivalent to. Drop it once that test has been retired.
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
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct GapCell {
    pub ch: i32,
    pub score: f32,
}

/// One manual override on a line: `index -> (char, score)`.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
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
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct GapResult {
    pub insert_at: i64,
    pub ratio: f32,
    pub span_px: f32,
}

/// One placeholder the host inserts into its own lists, carrying everything the
/// insertion needs: the index, the CTC column and the interpolated box.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct GapInsertion {
    /// Character index in the **original** line text, as [`GapResult::insert_at`]
    /// reports it — so insert right-to-left, and a placeholder ends up at
    /// `index` plus one per earlier insertion.
    pub index: i64,
    /// The CTC timestep column the placeholder takes.
    pub column: f32,
    /// The box interpolated between the placeholder's two neighbours; read only
    /// when [`GapPlan::grows_char_boxes`].
    pub placeholder_box: BoundingBox,
    /// This pair's spacing over the line's median, and the spacing in pixels.
    /// Diagnostics only — neither affects the insertion.
    pub ratio: f32,
    pub span_px: f32,
}

/// The measured gaps on one line, plus which of its parallel per-character
/// lists grow when they are materialised.
///
/// Ascending `insertions`: insert right-to-left. The three flags are the
/// "was this list full-length and non-empty?" guards the materialiser applies
/// per insertion, hoisted so the host applies the same decision rather than
/// measuring lengths in its own character units.
///
/// `needs_raw_alternatives` says whether the detector's *last-resort* geometry
/// source would have been read at all — the answer to "must I hand over the
/// per-timestep top-K lists?". It is a property of the line's geometry, not of
/// the call, so it holds on the retry too. A call made without the lists
/// answers that, and because the walk is the only source that can find a gap it
/// did not use, that plan comes back empty: **an empty plan plus this flag is
/// exactly "ask again with the raw lists"**, so the host pays for them only on
/// the lines that need them instead of on every call.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct GapPlan {
    pub insertions: Vec<GapInsertion>,
    pub grows_char_boxes: bool,
    pub grows_alternatives: bool,
    pub grows_char_cols: bool,
    pub needs_raw_alternatives: bool,
}

/// The detector's own inputs for [`blank_gaps_plan`]: everything
/// [`GapDetector`] reads, and nothing else.
///
/// One **record** rather than a positional argument list, deliberately: a record
/// argument is lowered into a single indirect buffer while each vector argument
/// is its own, and the fixed per-buffer cost is what a recognised page pays
/// here. `GapLine` crosses the same way, and carries the same geometry.
#[derive(Clone, Debug, uniffi::Record)]
pub struct GapPlanLine {
    pub text: String,
    pub is_vertical: bool,
    /// Raw/unclipped character boxes, `x`/`y`/`w`/`h` — the first geometry source.
    pub char_boxes: Vec<BoundingBox>,
    /// CTC timestep column per emitted character, the second source.
    pub char_cols: Vec<f32>,
    /// The length of the host's per-character alternatives list; read only to
    /// decide whether a placeholder entry keeps it index-aligned.
    pub alternatives_len: i64,
    /// The last-resort geometry source. Empty unless the plan came back
    /// `needs_raw_alternatives` — the first call, which is every line whose char
    /// boxes the layout stage already produced, never does.
    pub raw_alternatives: Vec<Vec<GapCell>>,
    pub crop_w: i32,
    pub crop_h: i32,
    pub seq_len_total: i32,
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

/// The detector's own inputs, with the per-character lists reduced to their
/// lengths.
///
/// The detector reads no character of the alternatives and no box it did not
/// choose, so a plan can be computed from the text, the orientation, the two
/// geometry sources, the crop geometry and *how long* each parallel list is.
fn to_plan_line(line: &GapPlanLine) -> jpdict_core::models::LineResult {
    jpdict_core::models::LineResult {
        text: line.text.clone(),
        is_vertical: line.is_vertical,
        char_boxes: line.char_boxes.iter().map(to_core_box).collect(),
        char_cols: line.char_cols.clone(),
        raw_alternatives: line
            .raw_alternatives
            .iter()
            .map(|c| to_core_cells(c))
            .collect(),
        crop_w: line.crop_w,
        crop_h: line.crop_h,
        seq_len_total: line.seq_len_total,
        ..Default::default()
    }
}

/// [`blank_gaps_plan`], as the exported record.
fn to_plan(line: &jpdict_core::models::LineResult, alternatives_len: usize) -> GapPlan {
    use jpdict_core::blank_gaps as core;
    let none = || GapPlan {
        insertions: Vec::new(),
        grows_char_boxes: false,
        grows_alternatives: false,
        grows_char_cols: false,
        needs_raw_alternatives: false,
    };
    if !line.is_vertical || line.text.contains(jpdict_core::models::GAP_CHAR) {
        return none();
    }
    let gaps = core::GapDetector::default().detect(line);
    if gaps.is_empty() {
        return none();
    }
    let n = line.text.chars().count();
    GapPlan {
        insertions: gaps
            .iter()
            .map(|g| GapInsertion {
                index: g.insert_at as i64,
                // Every gap's column is the midpoint of two *original* columns
                // and its box the interpolation of two *original* boxes, so
                // computing them all against the line as it stands is what the
                // right-to-left materialisation computes anyway.
                column: core::column_for(line, g.insert_at),
                placeholder_box: {
                    let b = core::interpolate_gap_box(&line.char_boxes, g.insert_at, line.is_vertical);
                    BoundingBox {
                        x: b.x,
                        y: b.y,
                        w: b.w,
                        h: b.h,
                    }
                },
                ratio: g.ratio,
                span_px: g.span_px,
            })
            .collect(),
        grows_char_boxes: line.char_boxes.len() == n && !line.char_boxes.is_empty(),
        grows_alternatives: alternatives_len == n && alternatives_len > 0,
        grows_char_cols: !line.char_cols.is_empty() && line.char_cols.len() == n,
        needs_raw_alternatives: false,
    }
}

/// Measure the gaps a materialisation would insert, without materialising them
/// (mobile `BlankGaps.apply`, planning half).
///
/// The policy is `apply_blank_gaps`' own: vertical lines only, idempotent, and
/// no plan when nothing was measured.
///
/// `line.raw_alternatives` may be empty, which is what the first call of a
/// recognised line does: the plan then carries
/// [`GapPlan::needs_raw_alternatives`], and only a line whose char boxes and CTC
/// columns both fail to describe its text needs the second call. A gap the
/// boxes *can* see is unaffected by the raw lists, so a non-empty plan is
/// already the whole answer.
#[uniffi::export]
pub fn blank_gaps_plan(line: GapPlanLine) -> GapPlan {
    let core_line = to_plan_line(&line);
    let alternatives_len = usize::try_from(line.alternatives_len).unwrap_or(0);
    let mut plan = to_plan(&core_line, alternatives_len);
    plan.needs_raw_alternatives = jpdict_core::blank_gaps::needs_raw_alternatives(
        &line.text,
        line.char_boxes.len(),
        line.char_cols.len(),
    );
    plan
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

    // ── the plan (a host that keeps its own lists) ──────────────────────────

    /// The plan, the way `BlankGaps.apply` asks for it: the detector's inputs
    /// plus the alternatives *length*, and the raw lists only when the plan
    /// asks for them.
    fn plan_line(line: &GapLine, with_raw: bool) -> GapPlanLine {
        GapPlanLine {
            text: line.text.clone(),
            is_vertical: line.is_vertical,
            char_boxes: line.char_boxes.clone(),
            char_cols: line.char_cols.clone(),
            alternatives_len: line.alternatives.len() as i64,
            raw_alternatives: if with_raw {
                line.raw_alternatives.clone()
            } else {
                Vec::new()
            },
            crop_w: line.crop_w,
            crop_h: line.crop_h,
            seq_len_total: line.seq_len_total,
        }
    }

    /// One call, as production makes it: no raw lists, and a second call only
    /// when the plan says the walk was reachable and found nothing.
    fn plan_of(line: &GapLine) -> GapPlan {
        let first = blank_gaps_plan(plan_line(line, false));
        if first.needs_raw_alternatives && first.insertions.is_empty() {
            blank_gaps_plan(plan_line(line, true))
        } else {
            first
        }
    }

    /// The host half of the plan: grow its own parallel lists from
    /// [`GapPlan`], right-to-left. This is the Kotlin `BlankGaps.apply` /
    /// `LineResult.withGapInsertions` logic, written out here so the parity
    /// gate below is a statement about the shape the facade has to reproduce —
/// not about a second Rust implementation.
    fn apply_plan(mut line: GapLine, plan: &GapPlan) -> GapLine {
        for ins in plan.insertions.iter().rev() {
            let index = usize::try_from(ins.index).expect("plan index is non-negative");
            let mut chars: Vec<char> = line.text.chars().collect();
            chars.insert(index, '\u{25CC}');
            line.text = chars.into_iter().collect();
            if plan.grows_char_boxes {
                line.char_boxes.insert(index, ins.placeholder_box);
            }
            if plan.grows_alternatives {
                line.alternatives
                    .insert(index, vec![GapCell { ch: 0x25CC, score: 0.0 }]);
            }
            if plan.grows_char_cols {
                line.char_cols.insert(index, ins.column);
            }
            line.overrides = line
                .overrides
                .iter()
                .map(|o| GapOverride {
                    index: if o.index >= ins.index as i32 { o.index + 1 } else { o.index },
                    ..*o
                })
                .collect();
        }
        line
    }

    /// The parity gate: a host that applies the plan to its own lists gets the
    /// line `blank_gaps_apply` builds, field for field — the text, the boxes,
    /// the alternatives, the columns and the shifted overrides. The two differ
    /// only in that the plan never carried the line back.
    #[test]
    fn applying_the_plan_on_the_host_reproduces_apply() {
        // Two gaps, every list full-length, an override on each side of them.
        let mut line = gapped_line();
        line.text = "あいうえおかき".to_string();
        line.char_boxes = [10, 30, 50, 90, 110, 150, 170]
            .iter()
            .map(|c| box_(0, c - 10, 40, 20))
            .collect();
        line.alternatives = line
            .text
            .chars()
            .map(|c| vec![GapCell { ch: c as i32, score: 1.0 }])
            .collect();
        line.char_cols = (0..7).map(|i| i as f32).collect();
        line.overrides = vec![
            GapOverride { index: 0, ch: 'Z' as i32, score: 1.0 },
            GapOverride { index: 6, ch: 'X' as i32, score: 1.0 },
        ];
        line.crop_w = 40;
        line.crop_h = 200;
        line.seq_len_total = 25;

        let plan = plan_of(&line);
        assert_eq!(
            plan.insertions.iter().map(|i| i.index).collect::<Vec<_>>(),
            vec![3, 5],
            "ascending, against the original text"
        );
        assert!(plan.grows_char_boxes && plan.grows_alternatives && plan.grows_char_cols);
        assert!((plan.insertions[0].ratio - 2.0).abs() < 1e-4);
        assert!((plan.insertions[0].span_px - 40.0).abs() < 1e-4);

        let expected = blank_gaps_apply(line.clone());
        let got = apply_plan(line.clone(), &plan);
        assert_eq!(got.text, expected.text);
        assert_eq!(got.text, "あいう\u{25CC}えお\u{25CC}かき");
        assert_eq!(got.char_boxes, expected.char_boxes);
        assert_eq!(got.alternatives, expected.alternatives);
        assert_eq!(got.char_cols, expected.char_cols);
        assert_eq!(got.overrides, expected.overrides);
        assert_eq!(got.is_vertical, expected.is_vertical);
        assert_eq!(got.crop_h, expected.crop_h);
        assert_eq!(got.seq_len_total, expected.seq_len_total);
        // The plan's own numbers are the line's, at the shifted positions.
        for (j, ins) in plan.insertions.iter().enumerate() {
            let at = [3, 6][j];
            assert_eq!(got.char_boxes[at], ins.placeholder_box);
            assert_eq!(got.char_cols[at], ins.column);
            assert_eq!(got.alternatives[at], vec![GapCell { ch: 0x25CC, score: 0.0 }]);
        }
        assert_eq!(got.overrides, vec![
            GapOverride { index: 0, ch: 'Z' as i32, score: 1.0 },
            GapOverride { index: 8, ch: 'X' as i32, score: 1.0 },
        ]);
    }

    /// Every line the policy declines produces an empty plan, and the facade's
    /// own short-circuits (horizontal, already gapped) are unchanged.
    #[test]
    fn a_declined_line_plans_nothing() {
        let mut horizontal = gapped_line();
        horizontal.is_vertical = false;
        assert!(plan_of(&horizontal).insertions.is_empty());
        assert!(blank_gaps_apply(horizontal).text == "あいうえお");

        let already = apply_plan(gapped_line(), &plan_of(&gapped_line()));
        assert!(plan_of(&already).insertions.is_empty(), "idempotent");

        let even = GapLine {
            char_boxes: [10, 30, 50, 70, 90].iter().map(|c| box_(0, c - 10, 40, 20)).collect(),
            ..gapped_line()
        };
        assert!(plan_of(&even).insertions.is_empty());
    }

    /// The three growth flags ride with the plan, so the host applies the
    /// materialiser's own decision instead of measuring lengths itself.
    #[test]
    fn the_growth_flags_mirror_the_full_length_guards() {
        // Boxes only: the alternatives and the columns stay empty.
        let mut boxes_only = gapped_line();
        boxes_only.alternatives = Vec::new();
        let plan = plan_of(&boxes_only);
        assert!(plan.grows_char_boxes);
        assert!(!plan.grows_alternatives);
        assert!(!plan.grows_char_cols);
        let got = apply_plan(boxes_only, &plan);
        assert!(got.alternatives.is_empty());
        assert!(got.char_cols.is_empty());
        assert_eq!(got.char_boxes.len(), 6);

        // Full-length alternatives and columns grow; a short alternatives list
        // is the host's own count, so it does not.
        let full = GapLine {
            alternatives: "あいうえお"
                .chars()
                .map(|c| vec![GapCell { ch: c as i32, score: 1.0 }])
                .collect(),
            char_cols: vec![0.0, 2.0, 4.0, 8.0, 10.0],
            ..gapped_line()
        };
        assert!(plan_of(&full).grows_alternatives);
        assert!(plan_of(&full).grows_char_cols);
        let short = GapLine {
            alternatives: full.alternatives[..2].to_vec(),
            ..full.clone()
        };
        let short_plan = plan_of(&short);
        assert!(!short_plan.grows_alternatives);
        assert!(short_plan.grows_char_boxes, "the gaps are unchanged");
        assert_eq!(short_plan.insertions, plan_of(&full).insertions);
    }

    /// The plan's `needs_raw_alternatives` is the detector's source
    /// *precedence*: only when the char boxes and the CTC columns both fail to
    /// describe the text does the per-timestep walk read the raw alternatives.
    /// Withholding them is then provably harmless — the plan is the same either
    /// way — and a line whose boxes answer the question never crosses them.
    #[test]
    fn needs_raw_is_the_detector_source_precedence() {
        // The predicate itself, as the plan reports it (5 characters).
        let flag = |text: &str, boxes: usize, cols: usize| {
            blank_gaps_plan(GapPlanLine {
                text: text.to_string(),
                is_vertical: true,
                char_boxes: vec![box_(0, 0, 1, 1); boxes],
                char_cols: vec![0.0; cols],
                alternatives_len: 0,
                raw_alternatives: Vec::new(),
                crop_w: 0,
                crop_h: 0,
                seq_len_total: 0,
            })
            .needs_raw_alternatives
        };
        assert!(!flag("あいうえお", 5, 0));
        assert!(!flag("あいうえお", 9, 5));
        assert!(!flag("あいうえお", 0, 5));
        assert!(flag("あいうえお", 0, 0));
        assert!(flag("あいうえお", 3, 4));
        assert!(!flag("", 0, 0));

        // The walk is the only source that finds this gap, so the raw lists
        // have to cross — and the plan reads them.
        let raw = |ch: char| vec![GapCell { ch: ch as i32, score: 1.0 }];
        let walk_only = GapLine {
            char_boxes: Vec::new(),
            char_cols: Vec::new(),
            alternatives: Vec::new(),
            raw_alternatives: vec![
                raw('あ'),
                vec![GapCell { ch: 0x3000, score: 1.0 }],
                raw('い'),
                vec![GapCell { ch: 0x3000, score: 1.0 }],
                raw('う'),
                vec![GapCell { ch: 0x3000, score: 1.0 }],
                vec![GapCell { ch: 0x3000, score: 1.0 }],
                vec![GapCell { ch: 0x3000, score: 1.0 }],
                raw('え'),
                vec![GapCell { ch: 0x3000, score: 1.0 }],
                raw('お'),
            ],
            ..gapped_line()
        };
        let asked = blank_gaps_plan(plan_line(&walk_only, false));
        assert!(
            asked.needs_raw_alternatives,
            "the first call says the walk was reachable"
        );
        assert!(
            asked.insertions.is_empty(),
            "and finds nothing without the lists, which is the retry condition"
        );
        let walked = plan_of(&walk_only);
        assert_eq!(walked.insertions.len(), 1);
        assert_eq!(walked.insertions[0].index, 3);
        assert!((walked.insertions[0].ratio - 2.0).abs() < 1e-4);
        assert!((walked.insertions[0].span_px - 32.0).abs() < 1e-4);
        // The flag describes the line's geometry, so the retry still reports it;
        // what changes is that the plan is no longer empty.
        assert!(walked.needs_raw_alternatives);

        // The same geometry read from the boxes: the predicate is false, one
        // call is the whole answer, and the raw lists never have to cross.
        let from_boxes = GapLine {
            char_boxes: [0, 20, 40, 80, 100].iter().map(|c| box_(0, c - 10, 40, 20)).collect(),
            raw_alternatives: Vec::new(),
            ..walk_only
        };
        let boxed = blank_gaps_plan(plan_line(&from_boxes, false));
        assert!(!boxed.needs_raw_alternatives);
        assert_eq!(boxed.insertions.len(), 1);
        assert_eq!(boxed.insertions[0].index, 3);
        // Same measured gap, different geometry source: the walk works in
        // timesteps and the model stride (32 px), the boxes in pixels (40 px),
        // and only the boxes can interpolate a placeholder box.
        assert!((boxed.insertions[0].ratio - walked.insertions[0].ratio).abs() < 1e-4);
        assert!((boxed.insertions[0].span_px - 40.0).abs() < 1e-4);
        assert!(walked.insertions[0].placeholder_box
            != boxed.insertions[0].placeholder_box);
        assert_eq!(walked.insertions[0].column, boxed.insertions[0].column);
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
