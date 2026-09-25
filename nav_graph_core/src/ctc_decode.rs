//! UniFFI shim over `jpdict_core::ctc_decode` — the mobile CTC decode boundary.
//!
//! The object owns the immutable vocabulary and class remap so a recognition
//! line crosses once as the recognizer's packed top-15 table, never as its
//! full `seqLen × classes` logits. [`CtcDecode::decode_full`] accepts the same
//! compact table plus the winning class's two neighbouring-row scores; those
//! scores are the only full-row values the legacy full-logits decoder reads.

use std::sync::Arc;

use crate::blank_gaps::GapCell;
use jpdict_core::ctc_decode::{
    ctc_decode_full_packed, ctc_decode_topk, top15_alternatives, CtcDecodeResult as CoreResult,
    TOP_K,
};

/// One decoded line. Kotlin converts `GapCell.ch` back to `Char` and carries
/// `rawAlternatives` on `PPOcrResult` exactly as the old in-process path did.
#[derive(Clone, Debug, uniffi::Record)]
pub struct CtcDecodeResult {
    pub text: String,
    pub alternatives: Vec<Vec<GapCell>>,
    pub char_cols: Vec<f32>,
    pub seq_len_total: i64,
    pub raw_alternatives: Vec<Vec<GapCell>>,
}

/// Immutable decoder tables shared by every line in one `OcrEngine`.
///
/// Construct once after loading `vocab.json` and `rec_remap.txt`; the large
/// tables then stay native and do not cross UniFFI again.
#[derive(uniffi::Object)]
pub struct CtcDecode {
    vocab: Vec<String>,
    remap: Vec<i32>,
}

/// Kotlin `Char` is one UTF-16 code unit, not a Unicode scalar value. This
/// intentionally selects only the first unit, matching the old Kotlin vocab
/// lookup (`String.first().code`) for both BMP and supplementary characters.
fn to_kotlin_char(ch: char) -> i32 {
    let mut code_units = [0u16; 2];
    ch.encode_utf16(&mut code_units)[0] as i32
}

fn to_gap_cells(rows: Vec<Vec<(char, f32)>>) -> Vec<Vec<GapCell>> {
    rows.into_iter()
        .map(|row| {
            row.into_iter()
                .map(|(ch, score)| GapCell {
                    ch: to_kotlin_char(ch),
                    score,
                })
                .collect()
        })
        .collect()
}

fn to_boundary_result(result: CoreResult, seq_len_total: Option<usize>) -> CtcDecodeResult {
    CtcDecodeResult {
        text: result.text,
        alternatives: to_gap_cells(result.alternatives),
        char_cols: result.char_cols,
        seq_len_total: seq_len_total.unwrap_or(result.seq_len_total) as i64,
        raw_alternatives: to_gap_cells(result.raw_alternatives),
    }
}

#[uniffi::export]
impl CtcDecode {
    #[uniffi::constructor]
    pub fn new(vocab: Vec<String>, remap: Vec<i32>) -> Arc<Self> {
        Arc::new(Self { vocab, remap })
    }

    /// Decode one native top-K line in a single call.
    ///
    /// `packed` is the unchanged `RecNcnn.inferTopK` layout: 30 `Float`s per
    /// timestep (`classId, logit`, repeated 15 times, descending). Entry zero is
    /// the greedy winner. Invalid lengths/ids fail closed to an empty result.
    pub fn decode_top_k(&self, packed: Vec<f32>, seq_len: i64) -> CtcDecodeResult {
        let seq_len = usize::try_from(seq_len).unwrap_or(0);
        to_boundary_result(
            ctc_decode_topk(&self.vocab, &self.remap, &packed, seq_len, TOP_K),
            None,
        )
    }

    /// Decode a pre-pruned full-logits line without crossing the full matrix.
    ///
    /// `left_scores[t]` / `right_scores[t]` are the current pruned winner's values
    /// in the adjacent full rows, or `NaN` when unavailable. With `packed`, these
    /// reproduce `ctc_decode_full` exactly, including interpolation from a class
    /// that fell outside the neighbour's top-15.
    pub fn decode_full(
        &self,
        packed: Vec<f32>,
        left_scores: Vec<f32>,
        right_scores: Vec<f32>,
        seq_len: i64,
        seq_len_total: i64,
    ) -> CtcDecodeResult {
        let seq_len = usize::try_from(seq_len).unwrap_or(0);
        let seq_len_total = usize::try_from(seq_len_total).ok();
        to_boundary_result(
            ctc_decode_full_packed(
                &self.vocab,
                &self.remap,
                &packed,
                &left_scores,
                &right_scores,
                seq_len,
                TOP_K,
            ),
            seq_len_total,
        )
    }

    /// Original class id -> first vocabulary character's UTF-16 code unit.
    pub fn decode_char(&self, class_idx: i32) -> i32 {
        to_kotlin_char(jpdict_core::ctc_decode::decode_char(&self.vocab, class_idx))
    }

    /// Pruned class id -> original class id, with identity fallback.
    pub fn remap_class(&self, pruned_idx: i32) -> i32 {
        jpdict_core::ctc_decode::remap_class(&self.remap, pruned_idx)
    }

    /// Top-15 alternatives for one full-logits timestep.
    ///
    /// This compatibility method intentionally has a per-timestep input shape,
    /// but production line decoding does not call it: [`Self::decode_top_k`] and
    /// [`Self::decode_full`] cross one compact table for the whole line.
    pub fn top15_alternatives(&self, logits: Vec<f32>) -> Vec<GapCell> {
        top15_alternatives(&self.vocab, &self.remap, &logits)
            .into_iter()
            .map(|(ch, score)| GapCell {
                ch: to_kotlin_char(ch),
                score,
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    //! Boundary mirrors run through the exported methods, not the upstream
    //! module, so code-point/record conversion and the compact layouts are
    //! covered with the same Rust tests as the other nav-core shims.

    use super::*;

    const TEST_VOCAB: [&str; 5] = ["あ", "い", "う", "え", "お"];

    fn decoder(remap: Vec<i32>) -> Arc<CtcDecode> {
        CtcDecode::new(TEST_VOCAB.iter().map(|s| (*s).to_string()).collect(), remap)
    }

    fn step15(winner: i32, score: f32) -> [f32; 30] {
        let mut row = [0.0f32; 30];
        for k in 0..15 {
            let class = if k == 0 {
                winner
            } else {
                (winner + k as i32).rem_euclid(20)
            };
            row[k * 2] = class as f32;
            row[k * 2 + 1] = score - k as f32 * 0.01;
        }
        row
    }

    fn flatten(rows: &[[f32; 30]]) -> Vec<f32> {
        rows.iter().flat_map(|row| row.iter().copied()).collect()
    }

    #[test]
    fn scalar_helpers_cross_char_and_remap_shapes() {
        let d = decoder(vec![0, 2, 1]);
        assert_eq!(d.decode_char(1), 'あ' as i32);
        assert_eq!(d.decode_char(18708), '\u{3000}' as i32);
        assert_eq!(d.decode_char(18709), ' ' as i32);
        assert_eq!(d.decode_char(99), '\u{FFFD}' as i32);
        assert_eq!(d.remap_class(1), 2);
        assert_eq!(d.remap_class(9), 9);
    }

    #[test]
    fn supplementary_vocab_chars_cross_as_kotlin_utf16_code_units() {
        let supplementary = '\u{1F468}';
        let expected = supplementary.to_string().encode_utf16().next().unwrap() as i32;
        assert_eq!(expected, 0xD83D);
        assert!(expected <= 0xFFFF);

        let d = CtcDecode::new(
            vec![supplementary.to_string(), "あ".to_string()],
            vec![0, 1],
        );

        assert_eq!(d.decode_char(1), expected);

        let mut row = vec![0.0f32; 20];
        row[1] = 0.9;
        let top = d.top15_alternatives(row);
        assert_eq!(top[0].ch, expected);
        assert!(top.iter().all(|cell| (0..=0xFFFF).contains(&cell.ch)));

        let rows = [step15(1, 0.9)];
        let decoded = d.decode_top_k(flatten(&rows), rows.len() as i64);
        assert_eq!(decoded.alternatives[0][0].ch, expected);
        assert_eq!(decoded.raw_alternatives[0][0].ch, expected);
        assert!(decoded
            .alternatives
            .iter()
            .flatten()
            .chain(decoded.raw_alternatives.iter().flatten())
            .all(|cell| (0..=0xFFFF).contains(&cell.ch)));
    }

    #[test]
    fn top15_helpers_map_the_whole_row_in_one_call() {
        let d = decoder(vec![0, 2, 1]);
        let mut row = vec![0.0f32; 20];
        for (class, score) in [(1, 0.1), (2, 0.9), (0, 0.5)] {
            row[class] = score;
        }
        let got = d.top15_alternatives(row);
        assert_eq!(got.len(), 15);
        assert_eq!(got[0].ch, 'あ' as i32);
        assert_eq!(got[0].score, 0.9);
        assert_eq!(got[1].ch, '\u{3000}' as i32);
        assert_eq!(got[1].score, 0.5);
        assert_eq!(got[2].ch, 'い' as i32);
        assert_eq!(got[2].score, 0.1);
    }

    #[test]
    fn one_packed_call_pins_text_columns_and_raw_rows() {
        let d = decoder((0..=20).collect());
        let rows = [
            step15(1, 0.9),
            step15(1, 0.8),  // collapsed repeat
            step15(0, 0.95), // blank resets
            step15(3, 0.7),
            step15(18709, 0.6),
            step15(2, 0.9),
        ];
        let got = d.decode_top_k(flatten(&rows), rows.len() as i64);
        assert_eq!(got.text, "あう い");
        assert_eq!(got.char_cols, vec![0.0, 3.0, 4.0, 5.0]);
        assert_eq!(got.seq_len_total, 6);
        assert_eq!(got.raw_alternatives.len(), 6);
        assert!(got.raw_alternatives.iter().all(|row| row.len() == 15));
        assert_eq!(got.alternatives.len(), 4);
        assert_eq!(got.alternatives[0][0].ch, 'あ' as i32);
        assert_eq!(got.raw_alternatives[2][0].ch, '\u{3000}' as i32);
    }

    #[test]
    fn compact_full_call_preserves_outside_topk_interpolation() {
        let d = decoder((0..20).collect());
        let num_classes = 20;
        let mut t0 = vec![0.0f32; num_classes];
        t0[2] = 1.0;
        for c in 3..18 {
            t0[c] = 0.5;
        }
        t0[1] = 0.49;
        let mut t1 = vec![0.05f32; num_classes];
        t1[1] = 3.0;
        let mut t2 = vec![0.05f32; num_classes];
        t2[2] = 0.9;
        t2[1] = 0.0;
        let rows = vec![t0, t1, t2];

        let mut packed = Vec::new();
        for row in &rows {
            let mut order: Vec<usize> = (0..num_classes).collect();
            order.sort_by(|&a, &b| row[b].partial_cmp(&row[a]).unwrap());
            for class in order.into_iter().take(TOP_K) {
                packed.push(class as f32);
                packed.push(row[class]);
            }
        }
        let got = d.decode_full(
            packed,
            vec![f32::NAN, 0.49, 3.0],
            vec![3.0, 0.0, f32::NAN],
            3,
            99,
        );
        assert_eq!(got.text, "いあい");
        assert_eq!(got.seq_len_total, 99);
        assert!(got.raw_alternatives[0]
            .iter()
            .all(|cell| cell.ch != 'あ' as i32));
        assert!(got.char_cols[1] < 1.0 && got.char_cols[1] > 0.9);
    }

    #[test]
    fn malformed_compact_inputs_fail_closed_without_panicking() {
        let d = decoder((0..20).collect());
        let short = d.decode_top_k(vec![0.0, 1.0], 1);
        assert!(short.text.is_empty());
        assert!(short.raw_alternatives.is_empty());

        let bad_id = d.decode_top_k(vec![f32::NAN; 30], 1);
        assert!(bad_id.text.is_empty());

        let full = d.decode_full(vec![0.0; 30], vec![0.0], vec![], 1, 7);
        assert!(full.text.is_empty());
        assert_eq!(full.seq_len_total, 7);
    }
}
