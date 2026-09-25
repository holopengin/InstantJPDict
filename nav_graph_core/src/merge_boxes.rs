//! UniFFI shim over `jpdict_core::merge_boxes` — the single-source home of
//! mobile `OcrEngine.mergeOverlappingBoxes` / `shouldMerge`.
//!
//! The pure rule (intersection over the smaller box plus the vertical-centre
//! gate) used to live twice: once here in Kotlin over `JpDictRect`, once in
//! the desktop `ocr_engine.rs` over `(BoundingBox, RotatedBox)` pairs. It now
//! lives once in `jpdict_core::merge_boxes` at rect level; this file only
//! converts the already-exported [`BoundingBox`] record (`x`, `y`, `w`, `h`)
//! into `jpdict_core::models::BoundingBox` (`confidence = 1.0`,
//! geometry-only, exactly what `build_nav_graph` does) and forwards the live
//! `xOverlapThresh` pref. Nothing here implements an algorithm.
//!
//! Boundary notes: `i32` geometry crosses 1:1; the threshold crosses as `f32`
//! so the facade keeps reading its live pref (0.40 default, same value the
//! desktop bakes in as `X_OVERLAP_THRESHOLD`). `usize` never crosses —
//! lengths stay behind `Vec`.
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (`mergeBoxesShouldMerge` / `mergeBoxesMerge`)
//! is wrapped by the hand-written `com.holopengin.instantjpdict.OcrEngine`
//! private methods, which keep their signatures byte-for-byte
//! (`mergeOverlappingBoxes(List<JpDictRect>): List<JpDictRect>`,
//! `shouldMerge(JpDictRect, JpDictRect): Boolean`) so call sites and the
//! existing tests compile unchanged. The facade maps
//! `JpDictRect(left, top, right, bottom)` →
//! `BoundingBox(x = left, y = top, w = right - left, h = bottom - top)` and
//! back on every call. The shim names carry the `merge_boxes_` prefix (crate
//! convention) so they cannot collide with the facade members they back.

use crate::BoundingBox;

/// The shared record → PC model. Geometry only: the merge rule never reads
/// `confidence` (this is the same value `build_nav_graph` passes).
fn core_box(b: &BoundingBox) -> jpdict_core::models::BoundingBox {
    jpdict_core::models::BoundingBox::new(b.x, b.y, b.w, b.h, 1.0)
}

/// PC model → the shared record (`confidence` is merge-internal only).
fn shim_box(b: &jpdict_core::models::BoundingBox) -> BoundingBox {
    BoundingBox { x: b.x, y: b.y, w: b.w, h: b.h }
}

/// Mobile `OcrEngine.shouldMerge`: the intersection must cover at least
/// `x_overlap_thresh` of the smaller box and the vertical centres must sit
/// within one average height. Delegates to
/// `jpdict_core::merge_boxes::should_merge`.
#[uniffi::export]
pub fn merge_boxes_should_merge(a: &BoundingBox, b: &BoundingBox, x_overlap_thresh: f32) -> bool {
    jpdict_core::merge_boxes::should_merge(&core_box(a), &core_box(b), x_overlap_thresh)
}

/// Mobile `OcrEngine.mergeOverlappingBoxes`: largest box first, greedily
/// unioning every later box the running union should merge with. Delegates to
/// `jpdict_core::merge_boxes::merge_overlapping_boxes`.
#[uniffi::export]
pub fn merge_boxes_merge(boxes: Vec<BoundingBox>, x_overlap_thresh: f32) -> Vec<BoundingBox> {
    jpdict_core::merge_boxes::merge_overlapping_boxes(
        boxes.iter().map(core_box).collect(),
        x_overlap_thresh,
    )
    .iter()
    .map(shim_box)
    .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bb(x: i32, y: i32, w: i32, h: i32) -> BoundingBox {
        BoundingBox { x, y, w, h }
    }

    #[test]
    fn overlapping_halves_union() {
        // Overlap 50x20 of a 100x20 box: IoM 0.50 >= 0.40 → one (0,0,150,20).
        let merged = merge_boxes_merge(vec![bb(0, 0, 100, 20), bb(50, 0, 100, 20)], 0.40);
        assert_eq!(merged.len(), 1);
        assert_eq!((merged[0].x, merged[0].y, merged[0].w, merged[0].h), (0, 0, 150, 20));
    }

    #[test]
    fn small_overlap_stays_split() {
        // Overlap 30x20: IoM 0.30 < 0.40 → two boxes.
        let apart = merge_boxes_merge(vec![bb(0, 0, 100, 20), bb(70, 0, 100, 20)], 0.40);
        assert_eq!(apart.len(), 2);
        assert!(!merge_boxes_should_merge(&bb(0, 0, 100, 20), &bb(70, 0, 100, 20), 0.40));
    }

    #[test]
    fn threshold_is_live() {
        // IoM 0.30 merges at 0.25 but not at 0.40 (the facade's pref).
        let boxes = vec![bb(0, 0, 100, 20), bb(70, 0, 100, 20)];
        assert_eq!(merge_boxes_merge(boxes.clone(), 0.25).len(), 1);
        assert_eq!(merge_boxes_merge(boxes, 0.40).len(), 2);
    }

    #[test]
    fn vertical_gate_blocks_stacked_lines() {
        // Full x-overlap but centres 40px apart with avg height 20: no merge.
        let stacked = merge_boxes_merge(vec![bb(0, 0, 100, 20), bb(0, 40, 100, 20)], 0.40);
        assert_eq!(stacked.len(), 2);
        assert!(!merge_boxes_should_merge(&bb(0, 0, 100, 20), &bb(0, 40, 100, 20), 0.40));
    }

    #[test]
    fn disjoint_and_degenerate_never_merge() {
        assert!(!merge_boxes_should_merge(&bb(0, 0, 50, 20), &bb(100, 0, 50, 20), 0.0));
        assert!(!merge_boxes_should_merge(&bb(0, 0, 0, 20), &bb(0, 0, 50, 20), 0.0));
    }

    #[test]
    fn singleton_and_empty_pass_through() {
        assert_eq!(merge_boxes_merge(vec![], 0.40).len(), 0);
        assert_eq!(merge_boxes_merge(vec![bb(1, 2, 30, 40)], 0.40).len(), 1);
    }
}
