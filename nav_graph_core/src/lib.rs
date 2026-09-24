//! UniFFI shim over `jpdict_core::nav_graph` (pipeline-sharing ticket 04).
//!
//! This crate used to carry a frozen snapshot of the PC navigation algorithm
//! (copied at commit `b7ddb39`, then lint-touched only). The snapshot drifted
//! from upstream on every item of the ticket-04 inventory (Phase-2 `W2`,
//! `DIR_MIN`, Phase-3 cone vs `WRAP` bonus, greedy resolution, missing
//! `enforce_connectivity`, controller-level `<5` null-out). The fork is
//! deleted here: both exported functions delegate to
//! `jpdict_core::nav_graph`, so the algorithmic delta vs PC master is zero by
//! construction. The UniFFI surface (`BoundingBox` / `NavGraph` records,
//! `build_nav_graph` / `navigate`) is byte-for-byte the old shape, so the
//! Kotlin call sites (`OcrOverlayStateController`, `OcrOverlayView`) compile
//! unchanged.
//!
//! Mapping notes:
//! * PC `BoundingBox` carries a `confidence` field the UniFFI record never
//!   had; boxes cross the boundary with `confidence = 1.0` (geometry-only,
//!   exactly what the old snapshot consumed).
//! * PC `NavGraph.edges` is `Vec<[usize; 4]>` with `n` as the empty-slot
//!   sentinel; it is flattened to the `Vec<i32>` the Kotlin side expects,
//!   and `navigate` resolves `>= n` slots to `None` exactly as before.

uniffi::setup_scaffolding!();

mod blank_gaps;
mod catalog;
mod data_models;
mod definition_format;
mod lookup_core;
mod yomitan_parse;
mod char_lm;
mod char_placement;
mod component_table;
mod deinflector;
mod furigana;
mod gap_candidates;
mod japanese;
mod kana_orthography;
mod kana_size;
mod kanji_variants;
mod oov_candidates;
mod oov_suggestions;
mod ocr_engine;
mod pitch;
mod rotated_geometry;
mod ruby_style;

/// Bounding box for a detected character.
#[derive(Clone, Debug, uniffi::Record)]
pub struct BoundingBox {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

/// Navigation graph with one outgoing edge per cardinal direction per node.
#[derive(Clone, Debug, uniffi::Record)]
pub struct NavGraph {
    pub edges: Vec<i32>,
    pub n: i32,
}

/// Build a nav graph from detected character bounding boxes.
///
/// Delegates to `jpdict_core::nav_graph::NavGraph::build` (PC upstream).
#[uniffi::export]
pub fn build_nav_graph(boxes: Vec<BoundingBox>) -> NavGraph {
    let core_boxes: Vec<jpdict_core::models::BoundingBox> = boxes
        .iter()
        .map(|b| jpdict_core::models::BoundingBox::new(b.x, b.y, b.w, b.h, 1.0))
        .collect();
    let graph = jpdict_core::nav_graph::NavGraph::build(&core_boxes);
    NavGraph {
        edges: graph
            .edges
            .iter()
            .flat_map(|e| e.iter())
            .map(|&v| v as i32)
            .collect(),
        n: graph.n as i32,
    }
}

/// Navigate from `idx` in `dir` (0=N,1=S,2=E,3=W). Returns `None` if the slot
/// is empty or the inputs are out of range.
#[uniffi::export]
pub fn navigate(graph: &NavGraph, idx: i32, dir: i32) -> Option<i32> {
    if !(0..graph.n).contains(&idx) || !(0..4).contains(&dir) {
        return None;
    }
    let target = graph.edges[(idx * 4 + dir) as usize];
    if target >= graph.n { None } else { Some(target) }
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::nav_graph` regression tests, run against
    //! the exact dependency this crate delegates to.
    //!
    //! The JVM suite (`NavGraphCoreTest`) pins the same neighbour tables
    //! through the UniFFI boundary, but the `NavGraph` record exposes only
    //! `edges`/`n` — so the PC `initial_edges` assertions live here, where
    //! `jpdict_core::nav_graph::NavGraph` is reachable directly. Together the
    //! two mirrors cover every PC assertion with no silent skips. If the path
    //! dependency ever resolves to a diverged `jpdict_core`, these fail first.

    use crate::{build_nav_graph, navigate};
    use jpdict_core::models::BoundingBox;
    use jpdict_core::nav_graph::NavGraph;

    const N: usize = 0;
    const S: usize = 1;
    const E: usize = 2;
    const W: usize = 3;

    fn bb(x: i32, y: i32, w: i32, h: i32) -> BoundingBox {
        BoundingBox::new(x, y, w, h, 1.0)
    }

    fn h_lines_2x6() -> Vec<BoundingBox> {
        let mut out = Vec::new();
        for r in 0..2 {
            for c in 0..6 {
                out.push(bb(100 + c * 32, 100 + r * 80, 24, 24));
            }
        }
        out
    }

    fn v_cols_2x6() -> Vec<BoundingBox> {
        let mut out = Vec::new();
        for &x in &[380, 300] {
            for r in 0..6 {
                out.push(bb(x, 100 + r * 32, 24, 24));
            }
        }
        out
    }

    #[test]
    fn mirror_01_horizontal_lines() {
        let boxes = h_lines_2x6();
        let g = NavGraph::build(&boxes);
        assert!(g.initial_edges.iter().all(|e| *e == [12, 12, 12, 12]));
        let expect: [[usize; 4]; 12] = [
            [7, 6, 1, 5], [6, 7, 2, 0], [9, 8, 3, 1], [8, 9, 4, 2],
            [9, 10, 5, 3], [10, 11, 0, 4], [0, 1, 7, 11], [1, 0, 8, 6],
            [2, 3, 9, 7], [3, 2, 10, 8], [4, 3, 11, 9], [5, 4, 6, 10],
        ];
        for (i, want) in expect.iter().enumerate() {
            assert_eq!(g.edges[i], *want, "node {i}");
        }
    }

    #[test]
    fn mirror_02_vertical_columns() {
        let boxes = v_cols_2x6();
        let g = NavGraph::build(&boxes);
        assert!(g.initial_edges.iter().all(|e| *e == [12, 12, 12, 12]));
        let expect: [[usize; 4]; 12] = [
            [5, 1, 7, 6], [0, 2, 6, 7], [1, 3, 9, 8], [2, 4, 8, 9],
            [3, 5, 9, 10], [4, 0, 10, 11], [11, 7, 0, 1], [6, 8, 1, 0],
            [7, 9, 2, 3], [8, 10, 3, 2], [9, 11, 4, 3], [10, 6, 5, 4],
        ];
        for (i, want) in expect.iter().enumerate() {
            assert_eq!(g.edges[i], *want, "node {i}");
        }
    }

    #[test]
    fn mirror_03_single_line_and_03b_long_row() {
        let boxes: Vec<_> = (0..6).map(|c| bb(100 + c * 32, 100, 24, 24)).collect();
        let g = NavGraph::build(&boxes);
        let expect: [[usize; 4]; 6] = [
            [6, 6, 1, 5], [6, 6, 2, 0], [6, 6, 3, 1],
            [6, 6, 4, 2], [6, 6, 5, 3], [6, 6, 0, 4],
        ];
        for (i, want) in expect.iter().enumerate() {
            assert_eq!(g.edges[i], *want, "node {i}");
        }

        let boxes: Vec<_> = (0..25).map(|c| bb(100 + c * 32, 100, 24, 24)).collect();
        let g = NavGraph::build(&boxes);
        assert_eq!(g.initial_edges[12], [25, 25, 13, 11]);
        assert_eq!(g.initial_edges[1], [25, 25, 2, 0]);
        for i in 0..25 {
            assert_eq!(g.edges[i][E], (i + 1) % 25, "node {i} east");
            assert_eq!(g.edges[i][W], (i + 24) % 25, "node {i} west");
        }
    }

    #[test]
    fn mirror_04_fallback_ring() {
        let g = NavGraph::build(&[]);
        assert_eq!(g.n, 0);
        let one = [bb(100, 100, 24, 24)];
        assert_eq!(NavGraph::build(&one).edges, vec![[0, 0, 0, 0]]);
        let two: Vec<_> = (0..2).map(|c| bb(100 + c * 32, 100, 24, 24)).collect();
        assert_eq!(NavGraph::build(&two).edges, vec![[1, 0, 1, 0], [0, 1, 0, 1]]);
        let four: Vec<_> = (0..4).map(|c| bb(100 + c * 32, 100, 24, 24)).collect();
        assert_eq!(
            NavGraph::build(&four).edges,
            vec![[1, 2, 3, 0], [2, 3, 0, 1], [3, 0, 1, 2], [0, 1, 2, 3]]
        );
    }

    #[test]
    fn mirror_05_mixed_06b_near_square_07_corpus() {
        let mut boxes = Vec::new();
        for c in 0..3 {
            boxes.push(bb(100 + c * 32, 100, 24, 24));
        }
        for r in 0..3 {
            boxes.push(bb(400, 100 + r * 32, 24, 24));
        }
        let expect: [[usize; 4]; 6] = [
            [4, 6, 1, 3], [5, 6, 2, 0], [5, 6, 3, 1],
            [5, 4, 0, 2], [3, 5, 0, 2], [4, 3, 1, 2],
        ];
        let g = NavGraph::build(&boxes);
        for (i, want) in expect.iter().enumerate() {
            assert_eq!(g.edges[i], *want, "node {i}");
        }

        let boxes: Vec<_> = (0..6)
            .map(|c| {
                if c == 2 { bb(100 + c * 32 - 8, 92, 40, 40) } else { bb(100 + c * 32, 100, 24, 24) }
            })
            .collect();
        let g = NavGraph::build(&boxes);
        for i in 0..6 {
            assert_eq!(g.edges[i][E], (i + 1) % 6, "node {i} east");
            assert_eq!(g.edges[i][W], (i + 5) % 6, "node {i} west");
        }

        let boxes = [
            bb(10, 10, 200, 30), bb(10, 100, 200, 30), bb(10, 200, 40, 40),
            bb(300, 10, 30, 200), bb(100, 10, 30, 200),
        ];
        let expect: [[usize; 4]; 5] = [
            [4, 1, 3, 5], [4, 2, 3, 5], [1, 4, 3, 5], [1, 0, 5, 4], [0, 1, 3, 5],
        ];
        let g = NavGraph::build(&boxes);
        for (i, want) in expect.iter().enumerate() {
            assert_eq!(g.edges[i], *want, "node {i}");
        }
    }

    #[test]
    fn mirror_08_navigate_bounds_through_shim() {
        // The shim's own boundary layer (sentinel/out-of-range → None).
        let boxes: Vec<BoundingBox> =
            (0..6).map(|c| bb(100 + c * 32, 100, 24, 24)).collect();
        let g = build_nav_graph(
            boxes.iter().map(|b| crate::BoundingBox { x: b.x, y: b.y, w: b.w, h: b.h }).collect(),
        );
        assert_eq!(navigate(&g, 0, N as i32), None);
        assert_eq!(navigate(&g, 6, N as i32), None);
        assert_eq!(navigate(&g, 0, 4), None);
        assert_eq!(navigate(&g, 0, E as i32), Some(1));
        let empty = build_nav_graph(vec![]);
        assert_eq!(navigate(&empty, 0, N as i32), None);
    }
}
