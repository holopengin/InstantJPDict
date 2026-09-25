//! UniFFI shim over `jpdict_core::char_boxes` peak offset (#49).
//!
//! The sub-column peak-offset formula (parabolic interpolation of the winning
//! class value across neighbouring CTC timesteps) has one source of truth in
//! the PC crate now (`jpdict_core::char_boxes::peak_offset`). This file is
//! only the boundary — no algorithm, no copied source.
//!
//! ## Kotlin facade contract
//!
//! `com.holopengin.instantjpdict.OcrEngine.peakOffset(v0, v1, v2)` keeps its
//! signature and delegates to the generated `charBoxesPeakOffset(v0, v1, v2)`.
//! `peakOffset` is only used by the CTC decode path, which package 5 is
//! converting; this shim is implemented anyway so the facade can land.

/// Sub-column peak offset (#49): parabolic interpolation of the winning
/// class value across neighbouring timesteps. Returns 0 when the peak is
/// flat, at a boundary, or prominence is below the mobile gate.
#[uniffi::export]
pub fn char_boxes_peak_offset(v0: f32, v1: f32, v2: f32) -> f32 {
    jpdict_core::char_boxes::peak_offset(v0, v1, v2)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reference(v0: f32, v1: f32, v2: f32) -> f32 {
        let denom = v0 - 2.0f32 * v1 + v2;
        if denom >= -1e-6f32 {
            return 0.0f32;
        }
        if (v1 - v0).min(v1 - v2) <= 1.0f32 {
            return 0.0f32;
        }
        (0.5f32 * (v0 - v2) / denom).clamp(-0.5, 0.5)
    }

    #[test]
    fn mirrors_peak_offset_formula_and_edge_cases() {
        // Interior asymmetric peak shifts toward the higher neighbor.
        let got = char_boxes_peak_offset(2.0, 10.0, 4.0);
        assert!((got - reference(2.0, 10.0, 4.0)).abs() < 1e-6);
        assert!(got > 0.0, "peak nearer v0 side, offset {got}");
        let mirrored = char_boxes_peak_offset(4.0, 10.0, 2.0);
        assert!((mirrored + got).abs() < 1e-6, "symmetric inputs mirror sign");
        // Symmetric peak: zero offset.
        assert_eq!(char_boxes_peak_offset(3.0, 10.0, 3.0), 0.0);
        // Flat peak (denominator >= -eps): zero.
        assert_eq!(char_boxes_peak_offset(5.0, 5.0, 5.0), 0.0);
        // Non-concave (denominator positive): zero.
        assert_eq!(char_boxes_peak_offset(0.0, 1.0, 10.0), 0.0);
        // Prominence gate: peak must stand > 1.0 above BOTH neighbors.
        assert_eq!(char_boxes_peak_offset(9.5, 10.0, 0.0), 0.0);
        assert_eq!(char_boxes_peak_offset(0.0, 10.0, 9.5), 0.0);
        // Bound: extreme asymmetry never exceeds half a column (|p0-p1| <
        // p0+p1, so the raw value is strictly inside ±0.5; the clamp is a
        // backstop).
        let extreme = char_boxes_peak_offset(8.999, 10.0, -90.0);
        assert!(extreme.abs() <= 0.5);
        assert!((extreme + 0.5).abs() < 0.01, "near bound {extreme}");
    }
}
