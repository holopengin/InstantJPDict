//! UniFFI shim over `jpdict_core::models` rotated geometry — **WP-17.**
//!
//! The minimum-area fit, orientation rule, unclip/inset arithmetic, local
//! rectangle mapping, and enclosing-blob filter all live in the PC crate's
//! ungated `models` module.  This file is only the Android/PC boundary: it
//! turns the four-corner mobile frame into the PC centre/size/angle frame and
//! turns results back into corner records.
//!
//! ## Frame representation
//!
//! `JpDictQuad` calls its corners `c0..c3`, the images of local
//! `(0,0)`, `(w,0)`, `(w,h)`, `(0,h)`.  `jpdict_core::models::RotatedBox`
//! stores the same frame as `(cx, cy, w, h, angle)`, where `angle` is the
//! local-x-axis angle in y-down screen coordinates.  The boundary therefore:
//!
//! * takes `w = |c1-c0|`, `h = |c3-c0|`, and `angle = atan2(c1-c0)`;
//! * takes the centre from the four-corner average (the existing
//!   `JpDictQuad.center` contract); and
//! * reconstructs `c0` from the PC origin and emits `c1,c2,c3` in the same
//!   order, using the PC's canonical y axis `(-sin(angle), cos(angle))`.
//!
//! Fit output is consequently right/down-oriented just like the old Kotlin
//! implementation.  In particular, for a vertical frame the local x axis is
//! the cross axis and the local y axis is the clockwise reading direction;
//! the PC angle is still the local-x angle, so its sign is the same as the
//! old `tiltDeg` sign.  A non-parallelogram four-corner value cannot be
//! represented by `RotatedBox`; the production fit and all callers use the
//! parallelogram frame documented above.
//!
//! `axis_aligned_bound_deg` and `tilt_deg` are the two small formulas that
//! are not separate PC methods: the former is the `max(tol, atan(quant/long))`
//! rule used by `RotatedBox::is_axis_aligned`, and the latter is the PC
//! frame angle converted to degrees.  They are expressed over the PC model
//! constants and the mapped `RotatedBox`, rather than reimplementing a geometry
//! algorithm.
//!
//! ## Kotlin facade contract
//!
//! `com.holopengin.instantjpdict.RotatedGeometry` keeps its existing names,
//! nullability, and `JpDictQuad`/`JpDictRect` types.  UniFFI cannot carry
//! those app-owned Kotlin data classes, so the shim exposes module-prefixed
//! records/functions and the facade performs only record conversion.  The
//! public constants are retained as Kotlin `const val`s for source
//! compatibility (notably `OcrEngine`'s private const initializer); the Rust
//! accessors are checked against those mirrors when the facade initializes.
//! The frame arithmetic itself, including the vertical threshold used by
//! `isVertical`, is delegated to Rust.

/// One source-space corner of a mobile `JpDictQuad`.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RotatedGeometryPoint {
    pub x: f32,
    pub y: f32,
}

/// The four-corner wire form of the mobile `JpDictQuad`.
///
/// The declaration order is the public Kotlin corner order: local
/// `(0,0)`, `(w,0)`, `(w,h)`, `(0,h)`.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RotatedGeometryQuad {
    pub c0: RotatedGeometryPoint,
    pub c1: RotatedGeometryPoint,
    pub c2: RotatedGeometryPoint,
    pub c3: RotatedGeometryPoint,
}

/// The mobile `JpDictRect` wire form (right/bottom edges, not width/height).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RotatedGeometryRect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

const EPS: f32 = 1e-6;

fn distance(a: &RotatedGeometryPoint, b: &RotatedGeometryPoint) -> f32 {
    let dx = b.x - a.x;
    let dy = b.y - a.y;
    (dx * dx + dy * dy).sqrt()
}

fn unit(a: &RotatedGeometryPoint, b: &RotatedGeometryPoint) -> (f32, f32) {
    let dx = b.x - a.x;
    let dy = b.y - a.y;
    let len = (dx * dx + dy * dy).sqrt();
    if len <= EPS {
        (0.0, 0.0)
    } else {
        (dx / len, dy / len)
    }
}

/// Convert the mobile four-corner frame to the PC centre/size/angle frame.
///
/// This is deliberately representation conversion, not a second fit.  The
/// centre follows the Kotlin value type's four-corner average; the axes and
/// extents follow `c0 -> c1` and `c0 -> c3`, which are the definitions used by
/// `JpDictQuad.localWidth`, `localHeight`, `xAxis`, and `yAxis`. The PC
/// confidence is geometry-only here, so it is set to `1.0` as in the other
/// geometry shims.
fn to_core_box(quad: &RotatedGeometryQuad) -> jpdict_core::models::RotatedBox {
    let w = distance(&quad.c0, &quad.c1);
    let h = distance(&quad.c0, &quad.c3);
    let (ux, uy) = unit(&quad.c0, &quad.c1);
    let angle = if w > EPS {
        uy.atan2(ux)
    } else if h > EPS {
        // A malformed/degenerate frame can still have a meaningful y axis.
        // Recover the canonical x axis from it so the old vertical tilt sign
        // is not lost merely because c0 == c1.
        let (vx, vy) = unit(&quad.c0, &quad.c3);
        (-vx).atan2(vy)
    } else {
        0.0
    };
    let cx = (quad.c0.x + quad.c1.x + quad.c2.x + quad.c3.x) * 0.25;
    let cy = (quad.c0.y + quad.c1.y + quad.c2.y + quad.c3.y) * 0.25;
    jpdict_core::models::RotatedBox::new(cx, cy, w, h, angle, 1.0)
}

fn point(x: f32, y: f32) -> RotatedGeometryPoint {
    RotatedGeometryPoint { x, y }
}

/// Convert a PC frame back to the mobile four-corner representation.
fn from_core_box(quad: &jpdict_core::models::RotatedBox) -> RotatedGeometryQuad {
    let (ox, oy) = quad.origin();
    let (xa, ya) = quad.x_axis();
    let (vx, vy) = quad.y_axis();
    RotatedGeometryQuad {
        c0: point(ox, oy),
        c1: point(ox + quad.w * xa, oy + quad.w * ya),
        c2: point(
            ox + quad.w * xa + quad.h * vx,
            oy + quad.w * ya + quad.h * vy,
        ),
        c3: point(ox + quad.h * vx, oy + quad.h * vy),
    }
}

fn to_core_quads(quads: &[RotatedGeometryQuad]) -> Vec<jpdict_core::models::RotatedBox> {
    quads.iter().map(to_core_box).collect()
}

/// The filter returns copies of its input frames. Compare the f32 bit patterns
/// so even an unusual NaN/-0 frame cannot make the index adapter lose a kept
/// item; normal geometry still compares exactly as the PC `PartialEq` does.
fn same_core_frame(
    a: &jpdict_core::models::RotatedBox,
    b: &jpdict_core::models::RotatedBox,
) -> bool {
    a.cx.to_bits() == b.cx.to_bits()
        && a.cy.to_bits() == b.cy.to_bits()
        && a.w.to_bits() == b.w.to_bits()
        && a.h.to_bits() == b.h.to_bits()
        && a.angle.to_bits() == b.angle.to_bits()
        && a.confidence.to_bits() == b.confidence.to_bits()
}

fn from_core_quads(quads: &[jpdict_core::models::RotatedBox]) -> Vec<RotatedGeometryQuad> {
    quads.iter().map(from_core_box).collect()
}

fn from_core_rect(rect: jpdict_core::models::BoundingBox) -> RotatedGeometryRect {
    RotatedGeometryRect {
        left: rect.x,
        top: rect.y,
        right: rect.x + rect.w,
        bottom: rect.y + rect.h,
    }
}

fn to_core_rect(rect: &RotatedGeometryRect) -> (f32, f32, f32, f32) {
    (
        rect.left as f32,
        rect.top as f32,
        (rect.right - rect.left) as f32,
        (rect.bottom - rect.top) as f32,
    )
}

/// Fit a minimum-area upright frame to interleaved x/y points.
///
/// `count` follows the old Kotlin `fitQuad(points, count)` contract: a count
/// below three, a negative count, or a count larger than the supplied buffer
/// is a degenerate fit and returns `None`.  Extra array values are ignored,
/// just as they were by the Kotlin implementation.
#[uniffi::export]
pub fn rotated_geometry_fit_quad(points: Vec<f32>, count: i64) -> Option<RotatedGeometryQuad> {
    let count = usize::try_from(count).ok()?;
    let end = count.checked_mul(2)?;
    if count < 3 || end > points.len() {
        return None;
    }
    let core_points: Vec<(f32, f32)> = points[..end]
        .chunks_exact(2)
        .map(|pair| (pair[0], pair[1]))
        .collect();
    jpdict_core::models::fit_quad(&core_points).map(|quad| from_core_box(&quad))
}

/// Whether the frame's local height is at least the PC vertical aspect ratio.
#[uniffi::export]
pub fn rotated_geometry_is_vertical(quad: &RotatedGeometryQuad) -> bool {
    to_core_box(quad).is_vertical()
}

/// Clockwise tilt in degrees, with the same y-down sign as the mobile API.
#[uniffi::export]
pub fn rotated_geometry_tilt_deg(quad: &RotatedGeometryQuad) -> f32 {
    to_core_box(quad).angle.to_degrees()
}

/// Whether the PC model considers the frame axis-aligned at its default band.
#[uniffi::export]
pub fn rotated_geometry_is_axis_aligned(quad: &RotatedGeometryQuad) -> bool {
    to_core_box(quad).is_axis_aligned()
}

/// Grow both local axes by the DB unclip amount, preserving the centre.
#[uniffi::export]
pub fn rotated_geometry_unclip(quad: &RotatedGeometryQuad, ratio: f32) -> RotatedGeometryQuad {
    from_core_box(&to_core_box(quad).unclip(ratio))
}

/// Shrink (positive) or grow (negative) along the frame's local axes.
#[uniffi::export]
pub fn rotated_geometry_inset(
    quad: &RotatedGeometryQuad,
    x_inset: f32,
    y_inset: f32,
) -> RotatedGeometryQuad {
    from_core_box(&to_core_box(quad).inset(x_inset, y_inset))
}

/// Rounded enclosing axis-aligned rectangle for a frame.
///
/// The PC model returns `(x, y, w, h)`, while the mobile value type stores
/// edges. Keep the same edge conversion as `map_local_rect`: round the PC
/// origin and extent, then add the rounded extent to form the right/bottom
/// edge. This makes a whole-frame `aabb` and `map_local_rect` agree even
/// when a rotated extent lands near a half-pixel. It is intentionally the
/// PC `BoundingBox` edge convention; the old Kotlin code rounded min and max
/// independently.
#[uniffi::export]
pub fn rotated_geometry_aabb(quad: &RotatedGeometryQuad) -> RotatedGeometryRect {
    let core = to_core_box(quad);
    let (x, y, w, h) = core.aabb();
    let left = x.round() as i32;
    let top = y.round() as i32;
    RotatedGeometryRect {
        left,
        top,
        right: left + w.round() as i32,
        bottom: top + h.round() as i32,
    }
}

/// Map a local crop rectangle to its rounded source-space AABB.
#[uniffi::export]
pub fn rotated_geometry_map_local_rect(
    quad: &RotatedGeometryQuad,
    local: &RotatedGeometryRect,
) -> RotatedGeometryRect {
    let (x, y, w, h) = to_core_rect(local);
    from_core_rect(to_core_box(quad).map_local_rect(x, y, w, h))
}

/// Drop frames enclosing at least two substantially smaller, line-shaped
/// frames.  The returned values are newly reconstructed by the PC model.
#[uniffi::export]
pub fn rotated_geometry_filter_enclosing_blobs(
    quads: Vec<RotatedGeometryQuad>,
) -> Vec<RotatedGeometryQuad> {
    let core = to_core_quads(&quads);
    from_core_quads(&jpdict_core::models::filter_enclosing_blobs(&core))
}

/// The same PC filter, returning retained input indices.
///
/// The Kotlin API filters an existing list and historically returns the
/// original objects (including their exact float corner bits).  The index
/// form lets the facade preserve that identity while the decision itself
/// still comes from `jpdict_core::models::filter_enclosing_blobs`.
#[uniffi::export]
pub fn rotated_geometry_filter_enclosing_blobs_indices(
    quads: Vec<RotatedGeometryQuad>,
) -> Vec<i64> {
    let core = to_core_quads(&quads);
    let kept = jpdict_core::models::filter_enclosing_blobs(&core);
    let mut used = vec![false; core.len()];
    let mut indices = Vec::with_capacity(kept.len());
    for candidate in kept {
        // The PC filter returns copies of its input values, so bit-for-bit
        // identity is intentional here.  The used bitmap handles duplicate
        // frames in input order without making the facade guess by coordinates.
        let mut found = None;
        for (i, original) in core.iter().enumerate() {
            if !used[i] && same_core_frame(original, &candidate) {
                found = Some(i);
                break;
            }
        }
        if let Some(i) = found {
            used[i] = true;
            indices.push(i as i64);
        }
    }
    indices
}

/// The widened default axis-aligned bound for a frame's long side.
///
/// This is the exact `RotatedBox::is_axis_aligned` threshold expressed in
/// degrees, with the caller's explicit `tolDeg` replacing the PC floor.
#[uniffi::export]
pub fn rotated_geometry_axis_aligned_bound_deg(long_side: f32, tol_deg: f32) -> f32 {
    let long = long_side.max(1.0);
    let quantization = (jpdict_core::models::AXIS_ALIGNED_QUANT_TOL_PX / long).atan();
    tol_deg.max(quantization.to_degrees())
}

/// The PC vertical-orientation threshold (`VERTICAL_MIN_ASPECT`).
#[uniffi::export]
pub fn rotated_geometry_vertical_min_aspect() -> f32 {
    jpdict_core::models::VERTICAL_MIN_ASPECT
}

/// The PC axis-aligned floor in degrees (`AXIS_ALIGNED_TOL_RAD` converted for
/// the Kotlin-facing unit).
#[uniffi::export]
pub fn rotated_geometry_axis_aligned_tol_deg() -> f32 {
    jpdict_core::models::AXIS_ALIGNED_TOL_RAD.to_degrees()
}

/// The PC fit-quantization allowance in pixels.
#[uniffi::export]
pub fn rotated_geometry_axis_aligned_quant_tol_px() -> f32 {
    jpdict_core::models::AXIS_ALIGNED_QUANT_TOL_PX
}

#[cfg(test)]
mod tests {
    //! Boundary mirrors of all 21 `RotatedGeometryTest` cases.  They call the
    //! exported shim surface only, so they cover the corner/centre/angle
    //! conversion as well as the PC algorithms.  The JVM suite is unchanged and
    //! exercises the same records through UniFFI at integration time.

    use super::*;
    use std::f64::consts::PI;

    fn p(x: f32, y: f32) -> RotatedGeometryPoint {
        point(x, y)
    }

    fn rect(left: i32, top: i32, right: i32, bottom: i32) -> RotatedGeometryQuad {
        RotatedGeometryQuad {
            c0: p(left as f32, top as f32),
            c1: p(right as f32, top as f32),
            c2: p(right as f32, bottom as f32),
            c3: p(left as f32, bottom as f32),
        }
    }

    fn from_rect_output(left: i32, top: i32, right: i32, bottom: i32) -> RotatedGeometryRect {
        RotatedGeometryRect {
            left,
            top,
            right,
            bottom,
        }
    }

    fn close(a: f32, b: f32, eps: f32) -> bool {
        (a - b).abs() <= eps
    }

    fn assert_point(actual: &RotatedGeometryPoint, x: f32, y: f32, eps: f32) {
        assert!(close(actual.x, x, eps), "x: {} vs {}", actual.x, x);
        assert!(close(actual.y, y, eps), "y: {} vs {}", actual.y, y);
    }

    fn assert_same_quad(actual: &RotatedGeometryQuad, expected: &RotatedGeometryQuad, eps: f32) {
        for (a, e) in [
            (&actual.c0, &expected.c0),
            (&actual.c1, &expected.c1),
            (&actual.c2, &expected.c2),
            (&actual.c3, &expected.c3),
        ] {
            assert_point(a, e.x, e.y, eps);
        }
    }

    fn rotated_rect(left: i32, top: i32, right: i32, bottom: i32, deg: f32) -> RotatedGeometryQuad {
        let rad = (deg as f64 * PI / 180.0) as f32;
        let c = rad.cos();
        let s = rad.sin();
        let cx = (left + right) as f32 * 0.5;
        let cy = (top + bottom) as f32 * 0.5;
        let map = |x: f32, y: f32| {
            let dx = x - cx;
            let dy = y - cy;
            p(cx + dx * c - dy * s, cy + dx * s + dy * c)
        };
        RotatedGeometryQuad {
            c0: map(left as f32, top as f32),
            c1: map(right as f32, top as f32),
            c2: map(right as f32, bottom as f32),
            c3: map(left as f32, bottom as f32),
        }
    }

    fn fit(points: &[(f32, f32)]) -> Option<RotatedGeometryQuad> {
        let mut flat = Vec::with_capacity(points.len() * 2);
        for &(x, y) in points {
            flat.push(x);
            flat.push(y);
        }
        rotated_geometry_fit_quad(flat, points.len() as i64)
    }

    #[test]
    fn mirror_01_axis_aligned_component() {
        let q = fit(&[(10.0, 30.0), (30.0, 30.0), (30.0, 40.0), (10.0, 40.0)]).unwrap();
        let r = rotated_geometry_aabb(&q);
        assert_eq!(r, from_rect_output(10, 30, 30, 40));
        assert!(close(rotated_geometry_tilt_deg(&q), 0.0, 0.01));
        assert!(!rotated_geometry_is_vertical(&q));
        assert!(rotated_geometry_is_axis_aligned(&q));
    }

    #[test]
    fn mirror_02_rotated_horizontal() {
        let input = [
            (85.17949192431122, 85.6698729810778),
            (119.82050807568876, 105.6698729810778),
            (114.82050807568876, 114.33012701892218),
            (80.17949192431122, 94.33012701892218),
        ];
        let q = fit(&input).unwrap();
        assert_point(&q.c0, 85.17949192431122, 85.6698729810778, 0.01);
        assert_point(&q.c1, 119.82050807568876, 105.6698729810778, 0.01);
        assert_point(&q.c2, 114.82050807568876, 114.33012701892218, 0.01);
        assert_point(&q.c3, 80.17949192431122, 94.33012701892218, 0.01);
        assert!(close(rotated_geometry_tilt_deg(&q), 30.0, 0.01));
        assert!(!rotated_geometry_is_vertical(&q));
        assert!(!rotated_geometry_is_axis_aligned(&q));
    }

    #[test]
    fn mirror_03_rotated_vertical() {
        let input = [
            (50.34675177060507, 39.38738824870603),
            (60.00601003349575, 41.97557869973124),
            (49.65324822939492, 80.61261175129397),
            (39.99398996650424, 78.02442130026876),
        ];
        let q = fit(&input).unwrap();
        assert!(rotated_geometry_is_vertical(&q));
        assert!(close(rotated_geometry_tilt_deg(&q), 15.0, 0.01));
        let a = rotated_geometry_aabb(&q);
        assert_eq!(a.left, 40);
        assert_eq!(a.top, 39);
        assert_eq!(a.right, 60);
        assert_eq!(a.bottom, 80);
    }

    #[test]
    fn mirror_04_near_square_is_horizontal() {
        let q = fit(&[(0.0, 0.0), (20.0, 0.0), (20.0, 22.0), (0.0, 22.0)]).unwrap();
        assert!(!rotated_geometry_is_vertical(&q));
        assert!(rotated_geometry_is_axis_aligned(&q));
    }

    #[test]
    fn mirror_05_interior_and_duplicate_points() {
        let q = fit(&[
            (0.0, 0.0),
            (10.0, 0.0),
            (10.0, 20.0),
            (0.0, 20.0),
            (5.0, 10.0),
            (10.0, 0.0),
            (5.0, 0.0),
        ])
        .unwrap();
        assert_eq!(rotated_geometry_aabb(&q), from_rect_output(0, 0, 10, 20));
    }

    #[test]
    fn mirror_06_degenerate_points() {
        assert!(fit(&[(1.0, 1.0)]).is_none());
        assert!(fit(&[(0.0, 0.0), (10.0, 10.0)]).is_none());
        assert!(fit(&[(0.0, 0.0), (5.0, 5.0), (10.0, 10.0)]).is_none());
        assert!(rotated_geometry_fit_quad(vec![1.0, 1.0], 1).is_none());
        assert!(rotated_geometry_fit_quad(vec![0.0, 0.0, 10.0, 10.0], 3).is_none());
    }

    #[test]
    fn mirror_07_unclip_axis_aligned() {
        let q = rect(0, 0, 40, 10);
        let out = rotated_geometry_unclip(&q, 1.5);
        assert_eq!(
            rotated_geometry_aabb(&out),
            from_rect_output(-6, -6, 46, 16)
        );
    }

    #[test]
    fn mirror_08_unclip_rotated() {
        let q = RotatedGeometryQuad {
            c0: p(93.050013, 91.242278),
            c1: p(111.176169, 99.694644),
            c2: p(106.949987, 108.757722),
            c3: p(88.823831, 100.305356),
        };
        let out = rotated_geometry_unclip(&q, 1.5);
        let before = distance(&q.c0, &q.c1);
        let before_h = distance(&q.c0, &q.c3);
        let after = distance(&out.c0, &out.c1);
        let after_h = distance(&out.c0, &out.c3);
        assert!(close(after, before + 10.0, 0.05));
        assert!(close(after_h, before_h + 10.0, 0.05));
    }

    #[test]
    fn mirror_09_axis_aligned_local_mapping() {
        let q = rect(10, 30, 30, 40);
        let got = rotated_geometry_map_local_rect(
            &q,
            &RotatedGeometryRect {
                left: 2,
                top: 4,
                right: 8,
                bottom: 8,
            },
        );
        assert_eq!(got, from_rect_output(12, 34, 18, 38));
    }

    #[test]
    fn mirror_10_rotated_local_mapping() {
        let q = fit(&[
            (85.17949192431122, 85.6698729810778),
            (119.82050807568876, 105.6698729810778),
            (114.82050807568876, 114.33012701892218),
            (80.17949192431122, 94.33012701892218),
        ])
        .unwrap();
        let whole = rotated_geometry_map_local_rect(
            &q,
            &RotatedGeometryRect {
                left: 0,
                top: 0,
                right: 40,
                bottom: 10,
            },
        );
        assert_eq!(whole, rotated_geometry_aabb(&q));
        let mapped = rotated_geometry_map_local_rect(
            &q,
            &RotatedGeometryRect {
                left: 0,
                top: 0,
                right: 10,
                bottom: 5,
            },
        );
        assert!(mapped.right - mapped.left > 10);
        assert!(mapped.bottom - mapped.top > 5);
    }

    #[test]
    fn mirror_11_axis_aligned_routing() {
        let level = fit(&[(90.0, 95.0), (110.0, 95.0), (110.0, 105.0), (90.0, 105.0)]).unwrap();
        assert!(rotated_geometry_is_axis_aligned(&level));
        let half = fit(&[
            (90.04401344685016, 94.9129250296954),
            (110.04325190813358, 95.08745573966289),
            (109.95598655314984, 105.0870749703046),
            (89.95674809186642, 104.91254426033711),
        ])
        .unwrap();
        assert!(close(rotated_geometry_tilt_deg(&half), 0.5, 0.01));
        assert!(rotated_geometry_is_axis_aligned(&half));
        let five = fit(&[
            (90.47383173282083, 94.14746908206469),
            (110.39772569465573, 95.89058393701785),
            (109.52616826717914, 105.85253091793531),
            (89.60227430534424, 104.10941606298215),
        ])
        .unwrap();
        assert!(close(rotated_geometry_tilt_deg(&five), 5.0, 0.01));
        assert!(!rotated_geometry_is_axis_aligned(&five));
    }

    #[test]
    fn mirror_12_corner_order_round_trip() {
        let q = rect(10, 30, 30, 40);
        // An identity inset crosses the same conversion and must recover the
        // exact corner order and values.
        let out = rotated_geometry_inset(&q, 0.0, 0.0);
        assert_same_quad(&out, &q, 0.0);
        assert_eq!(
            rotated_geometry_aabb(&out),
            from_rect_output(10, 30, 30, 40)
        );
    }

    #[test]
    fn mirror_13_vertical_rect_axes() {
        let q = rect(10, 30, 20, 70);
        assert!(rotated_geometry_is_vertical(&q));
        assert!(close(rotated_geometry_tilt_deg(&q), 0.0, 0.001));
        let out = rotated_geometry_inset(&q, 0.0, 0.0);
        assert_point(&out.c0, 10.0, 30.0, 0.0);
        assert_point(&out.c1, 20.0, 30.0, 0.0);
        assert_point(&out.c2, 20.0, 70.0, 0.0);
        assert_point(&out.c3, 10.0, 70.0, 0.0);
    }

    #[test]
    fn mirror_14_line_box_value_contract() {
        // `LineBox` is Kotlin-only; the wire-level equivalent is a plain frame
        // surviving the same identity conversion.
        let q = rect(1, 2, 3, 4);
        let out = rotated_geometry_inset(&q, 0.0, 0.0);
        assert_eq!(rotated_geometry_aabb(&out), from_rect_output(1, 2, 3, 4));
    }

    #[test]
    fn mirror_15_drop_enclosing_blob() {
        let input = vec![
            rect(0, 0, 300, 300),
            rect(10, 10, 210, 35),
            rect(10, 100, 210, 125),
        ];
        let got = rotated_geometry_filter_enclosing_blobs_indices(input.clone());
        assert_eq!(got, vec![1, 2]);
        let frames = rotated_geometry_filter_enclosing_blobs(input.clone());
        assert_eq!(frames.len(), 2);
    }

    #[test]
    fn mirror_16_drop_tilted_enclosing_blob() {
        let input = vec![
            rotated_rect(0, 0, 300, 300, 30.0),
            rotated_rect(20, 20, 220, 45, 30.0),
            rotated_rect(20, 120, 220, 145, 30.0),
        ];
        assert_eq!(
            rotated_geometry_filter_enclosing_blobs_indices(input),
            vec![1, 2]
        );
    }

    #[test]
    fn mirror_17_one_enclosed_line_is_kept() {
        let input = vec![rect(0, 0, 300, 300), rect(10, 10, 210, 35)];
        assert_eq!(
            rotated_geometry_filter_enclosing_blobs_indices(input),
            vec![0, 1]
        );
    }

    #[test]
    fn mirror_18_marks_are_kept() {
        let input = vec![
            rect(0, 0, 300, 300),
            rect(10, 10, 40, 40),
            rect(100, 100, 130, 130),
        ];
        assert_eq!(
            rotated_geometry_filter_enclosing_blobs_indices(input),
            vec![0, 1, 2]
        );
    }

    #[test]
    fn mirror_19_comparable_inner_frame_is_kept() {
        let input = vec![
            rect(0, 0, 300, 300),
            rect(10, 10, 260, 260),
            rect(20, 20, 240, 40),
        ];
        assert_eq!(
            rotated_geometry_filter_enclosing_blobs_indices(input),
            vec![0, 1, 2]
        );
    }

    #[test]
    fn mirror_20_disjoint_lines_are_kept() {
        let input = vec![
            rect(0, 0, 200, 30),
            rect(0, 50, 200, 80),
            rect(0, 100, 200, 130),
        ];
        assert_eq!(
            rotated_geometry_filter_enclosing_blobs_indices(input),
            vec![0, 1, 2]
        );
    }

    #[test]
    fn mirror_21_dropped_blob_preserves_order() {
        let input = vec![
            rect(5, 5, 205, 30),
            rect(0, 0, 400, 300),
            rect(5, 50, 205, 75),
            rect(500, 500, 700, 530),
        ];
        assert_eq!(
            rotated_geometry_filter_enclosing_blobs_indices(input),
            vec![0, 2, 3]
        );
    }

    #[test]
    fn frame_mapping_round_trip_is_exact_for_fit_frames() {
        // A fit followed by an identity inset crosses the complete
        // corner-set -> RotatedBox -> corner-set mapping.  The tolerances are
        // deliberately tight: a sign/axis error would swap an edge or change
        // the angle, not merely add a harmless float ulp.
        let q = fit(&[
            (85.17949192431122, 85.6698729810778),
            (119.82050807568876, 105.6698729810778),
            (114.82050807568876, 114.33012701892218),
            (80.17949192431122, 94.33012701892218),
        ])
        .unwrap();
        let out = rotated_geometry_inset(&q, 0.0, 0.0);
        assert_same_quad(&out, &q, 1e-4);
        assert!(close(rotated_geometry_tilt_deg(&out), 30.0, 1e-4));

        // Repeat for a vertical frame: its local x is the cross axis, so a
        // sign error here would turn +15° into -15° even though the horizontal
        // round trip still looks correct.
        let vertical = fit(&[
            (50.34675177060507, 39.38738824870603),
            (60.00601003349575, 41.97557869973124),
            (49.65324822939492, 80.61261175129397),
            (39.99398996650424, 78.02442130026876),
        ])
        .unwrap();
        let vertical_out = rotated_geometry_inset(&vertical, 0.0, 0.0);
        assert_same_quad(&vertical_out, &vertical, 1e-4);
        assert!(rotated_geometry_is_vertical(&vertical_out));
        assert!(close(rotated_geometry_tilt_deg(&vertical_out), 15.0, 1e-4));
    }

    #[test]
    fn exported_constants_match_pc_models() {
        assert_eq!(
            rotated_geometry_vertical_min_aspect(),
            jpdict_core::models::VERTICAL_MIN_ASPECT
        );
        assert_eq!(
            rotated_geometry_axis_aligned_quant_tol_px(),
            jpdict_core::models::AXIS_ALIGNED_QUANT_TOL_PX
        );
        assert!(close(
            rotated_geometry_axis_aligned_tol_deg(),
            jpdict_core::models::AXIS_ALIGNED_TOL_RAD.to_degrees(),
            1e-6
        ));
        assert!(close(
            rotated_geometry_axis_aligned_bound_deg(70.0, 1.0),
            (jpdict_core::models::AXIS_ALIGNED_TOL_RAD
                .max((jpdict_core::models::AXIS_ALIGNED_QUANT_TOL_PX / 70.0).atan()))
            .to_degrees(),
            1e-6
        ));
    }
}
