//! UniFFI shim over `jpdict_core::furigana` (#28/#99) — WP-05.
//!
//! Mobile `FuriganaRule`: the furigana (ruby) geometry rules, pure so they are
//! host-tested. Both exported free functions delegate to the PC crate's
//! `is_ruby_vertical` / `is_ruby_horizontal` — the Kotlin fork is deleted, so
//! the rule has one source of truth. Nothing here implements an algorithm; the
//! file only converts the already-exported [`BoundingBox`] record (`x`, `y`,
//! `w`, `h`) into `jpdict_core::models::BoundingBox` (`confidence = 1.0`,
//! geometry-only, exactly what `build_nav_graph` does) and forwards the image
//! dimensions.
//!
//! Boundary notes: the PC rule consumes RAW and UNCLIPPED boxes; the
//! orientation gate (vertical vs horizontal, near-square counts as both) stays
//! with the caller on both sides (`OcrEngine.filterFurigana` /
//! `ocr_engine::filter_furigana`). The rule reads integer geometry only, so
//! `i32` crosses 1:1 and the boundary loses nothing.
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (`uniffi.nav_graph_core.BoundingBox` plus
//! `furiganaIsRubyVertical` / `furiganaIsRubyHorizontal`) is wrapped by the
//! hand-written `com.holopengin.instantjpdict.FuriganaRule` object, which
//! keeps the pre-conversion API byte-for-byte (`isRubyVertical` /
//! `isRubyHorizontal`, `JpDictRect` parameters) so `OcrEngine.filterFurigana`
//! (call sites at lines 914/916) and `FuriganaRuleTest` compile unchanged. The
//! facade maps `JpDictRect(left, top, right, bottom)` →
//! `BoundingBox(x = left, y = top, w = right - left, h = bottom - top)` on
//! every call.

use crate::BoundingBox;

/// The shared record → PC model. Geometry only: the furigana rule never reads
/// `confidence` (this is the same value `build_nav_graph` passes).
fn core_box(b: &BoundingBox) -> jpdict_core::models::BoundingBox {
    jpdict_core::models::BoundingBox::new(b.x, b.y, b.w, b.h, 1.0)
}

/// Tiny vertical box hugging a much larger vertical box (either side) (#28).
///
/// `s_*`/`b_*` is the small/big candidate and `*_raw`/`*_un` its RAW /
/// unclipped contour geometry. The center must lie OUTSIDE the big box:
/// stacked column fragments (tail of the column above/below, overlapping only
/// via unclip padding) share its x-range. Delegates to
/// `jpdict_core::furigana::is_ruby_vertical`.
#[uniffi::export]
pub fn furigana_is_ruby_vertical(
    s_raw: &BoundingBox,
    b_raw: &BoundingBox,
    s_un: &BoundingBox,
    b_un: &BoundingBox,
    img_h: i32,
) -> bool {
    jpdict_core::furigana::is_ruby_vertical(
        &core_box(s_raw),
        &core_box(b_raw),
        &core_box(s_un),
        &core_box(b_un),
        img_h,
    )
}

/// Tiny horizontal box right above a much larger horizontal box (#28, #99).
///
/// The candidate must be smaller in BOTH dimensions: thinness alone let a
/// short receipt line — detail text under a heading, same-ish width — be
/// dropped as if it were ruby. Delegates to
/// `jpdict_core::furigana::is_ruby_horizontal`.
#[uniffi::export]
pub fn furigana_is_ruby_horizontal(
    s_raw: &BoundingBox,
    b_raw: &BoundingBox,
    s_un: &BoundingBox,
    b_un: &BoundingBox,
    img_w: i32,
    img_h: i32,
) -> bool {
    jpdict_core::furigana::is_ruby_horizontal(
        &core_box(s_raw),
        &core_box(b_raw),
        &core_box(s_un),
        &core_box(b_un),
        img_w,
        img_h,
    )
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::furigana` unit tests, run against the
    //! exact dependency this crate delegates to — through the exported
    //! surface, not the upstream module, so the shim's record conversion is
    //! covered too. The `furigana-05-overlap-boundary` corpus case and the
    //! near-square shape (which the caller routes through both orientation
    //! gates) are pinned here as well. The JVM suite (`FuriganaRuleTest` +
    //! the `furigana-0*` conformance cases) pins the same behaviour across the
    //! UniFFI boundary.

    use super::*;

    const IMG: i32 = 1000;

    /// The Kotlin facade's `JpDictRect(left, top, right, bottom)` →
    /// `BoundingBox` mapping, transcribed: `x`/`y` are the origin, `w`/`h` the
    /// extents. The PC tests' `rect` helper builds the same geometry.
    fn rect(l: i32, t: i32, r: i32, b: i32) -> BoundingBox {
        BoundingBox { x: l, y: t, w: r - l, h: b - t }
    }

    #[test]
    fn thin_ruby_strip_above_its_line_is_ruby() {
        let big = rect(100, 300, 600, 360); // 500x60 line
        let small = rect(200, 276, 320, 300); // 120x24: thin and much shorter
        let small_un = rect(188, 266, 332, 308);
        let big_un = rect(90, 290, 610, 370);
        assert!(furigana_is_ruby_horizontal(
            &small, &big, &small_un, &big_un, IMG, IMG
        ));
    }

    #[test]
    fn thin_but_wide_real_line_is_not_ruby() {
        // The receipt shape: small detail text above a heading — thin, but
        // nearly as wide as the line below it. Must survive as a real line.
        let big = rect(100, 300, 600, 360);
        let small = rect(100, 270, 600, 300); // 500x30
        let small_un = rect(85, 258, 615, 312);
        let big_un = rect(90, 290, 610, 370);
        assert!(!furigana_is_ruby_horizontal(
            &small, &big, &small_un, &big_un, IMG, IMG
        ));
    }

    #[test]
    fn narrow_short_column_beside_its_column_is_ruby() {
        let big = rect(300, 50, 360, 850); // 60x800 column
        let small = rect(276, 200, 300, 300); // 24x100: much shorter and narrower
        let small_un = rect(266, 190, 310, 310);
        let big_un = rect(290, 40, 370, 860);
        assert!(furigana_is_ruby_vertical(&small, &big, &small_un, &big_un, IMG));
    }

    #[test]
    fn full_width_short_column_is_not_ruby() {
        let big = rect(300, 50, 360, 850);
        let small = rect(300, 200, 360, 300); // same glyph width, just shorter
        let small_un = rect(290, 190, 370, 310);
        let big_un = rect(290, 40, 370, 860);
        assert!(!furigana_is_ruby_vertical(&small, &big, &small_un, &big_un, IMG));
    }

    /// `furigana-05-overlap-boundary` (shared conformance corpus): two thin
    /// strips above the same line, identical except width, straddling its left
    /// edge. Overlap exactly 50% of the small long side still counts (`>=`);
    /// 44% does not.
    #[test]
    fn overlap_boundary_half_passes_and_just_under_fails() {
        let big = rect(100, 300, 600, 360);
        let big_un = rect(90, 290, 610, 370);

        let half = rect(0, 276, 200, 300); // 200 wide: 100px overlap = 50%
        let half_un = rect(-10, 266, 210, 308);
        assert!(furigana_is_ruby_horizontal(
            &half, &big, &half_un, &big_un, IMG, IMG
        ));

        let under = rect(0, 276, 180, 300); // 180 wide: 80px overlap = 44%
        let under_un = rect(-10, 266, 190, 308);
        assert!(!furigana_is_ruby_horizontal(
            &under, &big, &under_un, &big_un, IMG, IMG
        ));
    }

    /// The near-square candidate: `OcrEngine.filterFurigana` routes a
    /// near-square small box through BOTH orientation gates (near-square
    /// counts as both), and the rule itself is orientation-agnostic — a
    /// ruby-sized near-square box passes whichever rule its caller applies.
    #[test]
    fn near_square_candidate_passes_both_rules() {
        // 26x24 above a 500x60 line: horizontal ruby.
        let big = rect(100, 300, 600, 360);
        let small = rect(200, 276, 226, 300);
        let small_un = rect(188, 266, 238, 308);
        let big_un = rect(90, 290, 610, 370);
        assert!(furigana_is_ruby_horizontal(
            &small, &big, &small_un, &big_un, IMG, IMG
        ));

        // The same 24x24 beside a 60x800 column: vertical ruby.
        let big = rect(300, 50, 360, 850);
        let small = rect(276, 200, 300, 224);
        let small_un = rect(266, 190, 310, 234);
        let big_un = rect(290, 40, 370, 860);
        assert!(furigana_is_ruby_vertical(&small, &big, &small_un, &big_un, IMG));
    }

    /// The shim's record mapping: `x`/`y` are the origin, `w`/`h` extents, and
    /// the PC model's edges reconstruct the source rect exactly. A mapping bug
    /// (e.g. passing `right` as `w`) would flip the shifted verdict below.
    #[test]
    fn bounding_box_record_maps_to_the_pc_models_extent_semantics() {
        let r = rect(100, 300, 600, 360);
        let pc = core_box(&r);
        assert_eq!((pc.x, pc.y, pc.w, pc.h), (100, 300, 500, 60));
        assert_eq!((pc.left(), pc.top(), pc.right(), pc.bottom()), (100, 300, 600, 360));
        assert_eq!(pc.confidence, 1.0);

        // Translation invariance: the same ruby shape far from the origin
        // still passes, so the record is read as extents, not absolute edges.
        let shift = |r: BoundingBox| BoundingBox { x: r.x + 5000, y: r.y + 3000, w: r.w, h: r.h };
        let (big, small) = (rect(100, 300, 600, 360), rect(200, 276, 320, 300));
        let (small_un, big_un) = (rect(188, 266, 332, 308), rect(90, 290, 610, 370));
        assert!(furigana_is_ruby_horizontal(
            &shift(small),
            &shift(big),
            &shift(small_un),
            &shift(big_un),
            IMG,
            IMG
        ));
    }
}
