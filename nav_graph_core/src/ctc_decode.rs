//! UniFFI shim over `jpdict_core::ctc_decode` — the mobile CTC decode boundary.
//!
//! The object owns the immutable vocabulary and class remap so a recognition
//! line crosses once as the recognizer's packed top-15 table, never as its
//! full `seqLen × classes` logits. [`CtcDecode::decode_full_compact`] accepts
//! the same compact table plus the winning class's two neighbouring-row scores;
//! those scores are the only full-row values the legacy full-logits decoder
//! reads.
//!
//! ## What the compact result carries, and why
//!
//! A recognised line's alternatives are `seqLen × topK` cells — the single
//! largest per-line payload at this boundary. Two facts let the result cross
//! without rebuilding that shape in Kotlin:
//!
//! * **The per-character list is a subset of the per-timestep one.** The greedy
//!   walk pushes the row it just built into both, so the emitted rows are the
//!   raw rows at `alt_rows`: strictly ascending, in range. Carrying those
//!   indices instead of the rows themselves is one integer per *character*
//!   instead of `top_k` cells per character.
//! * **The rows do not need to be nested.** `raw_alternatives` is one flat
//!   row-major cell list plus `raw_rows`, the row boundaries, so UniFFI lifts a
//!   primitive-shaped list instead of allocating a list per timestep.
//!
//! These two facts are the whole reason the nested result is not exported
//! twice over: the flat shape is a re-presentation of the same rows, and the
//! host's only job on arrival is to index them. The crate's own nested
//! `jpdict_core::ctc_decode::CtcDecodeResult` is what the parity tests compare
//! against (reached directly, not through a second export), so the equivalence
//! is still pinned without a binding the app never calls.
//!
//! ## Vertical punctuation is folded in
//!
//! [`CtcDecode::decode_top_k_compact`] takes a `vertical` flag and applies the
//! crate's `CtcDecodeResult::apply_vertical_punctuation` — the fixed 1:1
//! `?`→`？`, `…`→`︙`, `‥`→`︰` substitution of #56/#63 — while the rows are
//! still being built. The host used to call three more exports per vertical
//! line to do the same thing to the rows it had just received, crossing every
//! character of the text and of both alternative lists a second time.

use std::sync::Arc;

use crate::blank_gaps::GapCell;
use jpdict_core::ctc_decode::{
    ctc_decode_full_packed, ctc_decode_topk, top15_alternatives, CtcDecodeResult as CoreResult,
    TOP_K,
};

/// One decoded line in the shape that crosses: `raw_alternatives` is one flat
/// row-major cell list and `raw_rows` holds the row boundaries, so timestep `i`
/// is `raw_alternatives[raw_rows[i] .. raw_rows[i + 1]]`.
///
/// `alt_rows[j]` is the timestep the `j`-th emitted character came from, so
/// the per-character alternatives are *those* rows by index. Kotlin indexes
/// the list it already has instead of receiving the cells twice; see the module
/// docs for why the two facts hold.
#[derive(Clone, Debug, uniffi::Record)]
pub struct CompactCtcDecodeResult {
    pub text: String,
    /// The timestep each emitted character's alternatives came from; ascending.
    pub alt_rows: Vec<i64>,
    pub char_cols: Vec<f32>,
    pub seq_len_total: i64,
    /// Every timestep's top-K, row-major, `raw_rows.len() - 1` rows long.
    pub raw_alternatives: Vec<GapCell>,
    /// Row boundaries into `raw_alternatives`, starting at 0.
    pub raw_rows: Vec<i64>,
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

/// Flatten the rows into one cell list plus its row boundaries, applying the
/// vertical-punctuation substitution to the nested result first when asked.
///
/// `emitted_rows` is the identity the host relies on when it indexes back: the
/// pinned decoder builds both lists in the same loop iteration, so
/// `alternatives[j] == raw_alternatives[t]`. The indices are recovered by
/// *content* rather than assumed, which makes a shape change upstream show up
/// as a failed identity here instead of as another timestep's characters — and
/// a failed identity fails closed (an empty result, exactly like a malformed
/// `packed` input) rather than crossing the wrong rows.
fn to_compact_result(
    mut result: CoreResult,
    seq_len_total: Option<usize>,
    vertical: bool,
) -> CompactCtcDecodeResult {
    // Recover the emitted-row identity before any folding: both sides must be
    // compared in the shape the decoder produced.
    let fallback_len = seq_len_total.unwrap_or(result.seq_len_total) as i64;
    let Some(alt_rows) = emitted_rows(&result.alternatives, &result.raw_alternatives) else {
        return CompactCtcDecodeResult {
            text: String::new(),
            alt_rows: Vec::new(),
            char_cols: Vec::new(),
            seq_len_total: fallback_len,
            raw_alternatives: Vec::new(),
            raw_rows: vec![0],
        };
    };

    if vertical {
        // The #56/#63 substitution, folded in by the crate while the rows are
        // still the nested ones: the same 1:1 map the host used to apply to the
        // text and both alternative lists in two more crossings per vertical
        // line.
        result.apply_vertical_punctuation();
    }
    let text = result.text;
    let char_cols = result.char_cols;
    let raw_alternatives = result.raw_alternatives;
    let total = seq_len_total.unwrap_or(result.seq_len_total);

    let cell_count: usize = raw_alternatives.iter().map(Vec::len).sum();
    let mut cells: Vec<GapCell> = Vec::with_capacity(cell_count);
    let mut rows: Vec<i64> = Vec::with_capacity(raw_alternatives.len() + 1);
    rows.push(0);
    for row in &raw_alternatives {
        for (ch, score) in row {
            cells.push(GapCell {
                ch: to_kotlin_char(*ch),
                score: *score,
            });
        }
        rows.push(cells.len() as i64);
    }

    CompactCtcDecodeResult {
        text,
        alt_rows: alt_rows.iter().map(|t| *t as i64).collect(),
        char_cols,
        seq_len_total: total as i64,
        raw_alternatives: cells,
        raw_rows: rows,
    }
}

/// The timestep each emitted row came from: `alternatives[j] ==
/// raw_alternatives[out[j]]`, in order. `None` when the identity does not hold
/// for every emitted row.
///
/// The decoder pushes one raw row per timestep and clones it into
/// `alternatives` on the timesteps that emit, so the emitted rows appear in
/// `raw_alternatives` in the same order — which is all this needs, and it
/// recovers the indices *by content* rather than assuming them.
///
/// Why a content match is exact even when two timesteps carry the same row:
/// every match is verified equal to the emitted row it stands for, so a match
/// at a duplicate timestep hands the host a row whose cells are byte-identical.
/// And the scan cannot run past a row it still needs: the raw row at emitted
/// timestep `t_j` equals `alternatives[j]`, so the scan either reaches `t_j` and
/// matches, or it already matched an earlier emitted row at a timestep `<= t_j`
/// (the first match after the previous position is at most the previous
/// timestep). Induction on `j` gives a match for every emitted row, so `None`
/// is unreachable for a result this decoder produced — it is a fail-closed
/// guard for a shape change upstream, not a normal outcome.
fn emitted_rows(
    alternatives: &[Vec<(char, f32)>],
    raw_alternatives: &[Vec<(char, f32)>],
) -> Option<Vec<usize>> {
    let mut rows = Vec::with_capacity(alternatives.len());
    let mut at = 0usize;
    for (t, row) in raw_alternatives.iter().enumerate() {
        if at < alternatives.len() && *row == alternatives[at] {
            rows.push(t);
            at += 1;
        }
    }
    (at == alternatives.len()).then_some(rows)
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
    ///
    /// The vertical punctuation fold (`vertical`) is applied to the text and
    /// both alternative lists while the rows are built, so the host does not
    /// make a second pass over them.
    pub fn decode_top_k_compact(
        &self,
        packed: Vec<f32>,
        seq_len: i64,
        vertical: bool,
    ) -> CompactCtcDecodeResult {
        let seq_len = usize::try_from(seq_len).unwrap_or(0);
        to_compact_result(
            ctc_decode_topk(&self.vocab, &self.remap, &packed, seq_len, TOP_K),
            None,
            vertical,
        )
    }

    /// Decode a pre-pruned full-logits line without crossing the full matrix.
    ///
    /// `left_scores[t]` / `right_scores[t]` are the current pruned winner's values
    /// in the adjacent full rows, or `NaN` when unavailable. With `packed`, these
    /// reproduce the crate's `ctc_decode_full` exactly, including interpolation
    /// from a class that fell outside the neighbour's top-15.
    ///
    /// The vertical punctuation fold (`vertical`) is folded in as in
    /// [`Self::decode_top_k_compact`].
    pub fn decode_full_compact(
        &self,
        packed: Vec<f32>,
        left_scores: Vec<f32>,
        right_scores: Vec<f32>,
        seq_len: i64,
        seq_len_total: i64,
        vertical: bool,
    ) -> CompactCtcDecodeResult {
        let seq_len = usize::try_from(seq_len).unwrap_or(0);
        let seq_len_total = usize::try_from(seq_len_total).ok();
        to_compact_result(
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
            vertical,
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
    /// but production line decoding does not call it:
    /// [`Self::decode_top_k_compact`] and [`Self::decode_full_compact`] cross one
    /// compact table for the whole line.
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
    //! covered with the same Rust tests as the other nav-core shims. The one
    //! exception is deliberate: the compact-vs-nested parity tests call
    //! `jpdict_core::ctc_decode` directly, because the nested shape is no longer
    //! a binding and the oracle has to come from somewhere. See [`NestedResult`].

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

    /// Re-expand a compact result into the nested rows a host would otherwise
    /// have received, using `alt_rows` to index the flat cell list. This is
    /// exactly the Kotlin the recognition path does, so comparing it against
    /// [`NestedResult`] *is* the parity gate for the compact shape.
    fn expand(c: &CompactCtcDecodeResult) -> (Vec<Vec<GapCell>>, Vec<Vec<GapCell>>) {
        let rows: Vec<Vec<GapCell>> = c
            .raw_rows
            .windows(2)
            .map(|w| c.raw_alternatives[w[0] as usize..w[1] as usize].to_vec())
            .collect();
        let alts = c
            .alt_rows
            .iter()
            .map(|t| rows[*t as usize].clone())
            .collect();
        (alts, rows)
    }

    /// The nested shape the shim used to export, rebuilt here from the crate's
    /// own result so the parity gate still compares *two shapes* without a
    /// second binding. `#[cfg(test)]`-only: production only ever sees the
    /// compact one.
    struct NestedResult {
        text: String,
        alternatives: Vec<Vec<GapCell>>,
        char_cols: Vec<f32>,
        seq_len_total: i64,
        raw_alternatives: Vec<Vec<GapCell>>,
        /// The emitted timestep per character, as the crate records it walking
        /// — so the shim's content-recovered `alt_rows` can be checked against
        /// the upstream answer rather than only against itself.
        alt_rows: Vec<i64>,
    }

    impl NestedResult {
        fn new(result: CoreResult, seq_len_total: Option<usize>) -> Self {
            let seq_len_total = seq_len_total.unwrap_or(result.seq_len_total) as i64;
            Self {
                text: result.text,
                alternatives: to_cells(result.alternatives),
                char_cols: result.char_cols,
                seq_len_total,
                raw_alternatives: to_cells(result.raw_alternatives),
                alt_rows: result.alt_rows.iter().map(|t| *t as i64).collect(),
            }
        }
    }

    fn to_cells(rows: Vec<Vec<(char, f32)>>) -> Vec<Vec<GapCell>> {
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

    /// The top-K decode as the nested shape, via the crate directly.
    fn nested_top_k(d: &CtcDecode, packed: Vec<f32>, seq_len: i64) -> NestedResult {
        let seq_len = usize::try_from(seq_len).unwrap_or(0);
        NestedResult::new(
            ctc_decode_topk(&d.vocab, &d.remap, &packed, seq_len, TOP_K),
            None,
        )
    }

    /// The full-logits decode as the nested shape, via the crate directly.
    fn nested_full(
        d: &CtcDecode,
        packed: Vec<f32>,
        left_scores: Vec<f32>,
        right_scores: Vec<f32>,
        seq_len: i64,
        seq_len_total: i64,
    ) -> NestedResult {
        let seq_len = usize::try_from(seq_len).unwrap_or(0);
        let seq_len_total = usize::try_from(seq_len_total).ok();
        NestedResult::new(
            ctc_decode_full_packed(
                &d.vocab,
                &d.remap,
                &packed,
                &left_scores,
                &right_scores,
                seq_len,
                TOP_K,
            ),
            seq_len_total,
        )
    }

    /// The host's own vertical-punctuation map, the operation the compact
    /// `vertical` flag replaces: text plus every cell of both lists.
    fn vertical_punctuate(r: &mut NestedResult) {
        fn map(c: char) -> char {
            match c {
                '?' => '？',
                '…' => '︙',
                '‥' => '︰',
                _ => c,
            }
        }
        r.text = r.text.chars().map(map).collect();
        for row in r.alternatives.iter_mut().chain(r.raw_alternatives.iter_mut()) {
            for cell in row.iter_mut() {
                cell.ch = map(char::from_u32(cell.ch as u32).unwrap_or('\u{FFFD}')) as i32;
            }
        }
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
        let decoded = d.decode_top_k_compact(flatten(&rows), rows.len() as i64, false);
        let (alts, raw) = expand(&decoded);
        assert_eq!(alts[0][0].ch, expected);
        assert_eq!(raw[0][0].ch, expected);
        assert!(alts
            .iter()
            .flatten()
            .chain(raw.iter().flatten())
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
        let got = d.decode_top_k_compact(flatten(&rows), rows.len() as i64, false);
        let (alts, raw) = expand(&got);
        assert_eq!(got.text, "あう い");
        assert_eq!(got.char_cols, vec![0.0, 3.0, 4.0, 5.0]);
        assert_eq!(got.seq_len_total, 6);
        assert_eq!(raw.len(), 6);
        assert!(raw.iter().all(|row| row.len() == 15));
        assert_eq!(alts.len(), 4);
        assert_eq!(alts[0][0].ch, 'あ' as i32);
        assert_eq!(raw[2][0].ch, '\u{3000}' as i32);
        // The flat list is exactly those rows, and the boundaries say where each
        // one starts, so the host re-derives the shape without a second crossing.
        assert_eq!(got.raw_alternatives.len(), 6 * 15);
        assert_eq!(got.raw_rows, vec![0, 15, 30, 45, 60, 75, 90]);
        assert_eq!(got.alt_rows, vec![0, 3, 4, 5]);
    }

    /// The compact result is the nested one, re-derived: the flat cell list
    /// splits back into the same rows, `alt_rows` picks the same per-character
    /// rows, and the columns, length and text are untouched. This is the whole
    /// parity gate for the flat shape — the host's only job is indexing — and
    /// the nested side is the crate's own result, reached directly.
    #[test]
    fn compact_rows_expand_back_to_the_nested_result() {
        let d = decoder((0..=20).collect());
        let rows = [
            step15(1, 0.9),
            step15(1, 0.8),  // collapsed repeat: a raw row, no alt row
            step15(0, 0.95), // blank resets
            step15(3, 0.7),
            step15(18709, 0.6), // space: emitted
            step15(2, 0.9),
        ];
        let nested = nested_top_k(&d, flatten(&rows), rows.len() as i64);
        let compact = d.decode_top_k_compact(flatten(&rows), rows.len() as i64, false);
        let (alts, raw) = expand(&compact);

        assert_eq!(compact.text, nested.text);
        assert_eq!(compact.char_cols, nested.char_cols);
        assert_eq!(compact.seq_len_total, nested.seq_len_total);
        assert_eq!(raw, nested.raw_alternatives);
        assert_eq!(alts, nested.alternatives);
        // `raw_rows` is the boundary list: it starts at 0, ends at the cell
        // count, and has one more entry than there are rows.
        assert_eq!(compact.raw_rows.first(), Some(&0));
        assert_eq!(*compact.raw_rows.last().unwrap(), compact.raw_alternatives.len() as i64);
        assert_eq!(compact.raw_rows.len(), nested.raw_alternatives.len() + 1);
        // The emitted rows are the raw rows at `alt_rows`: ascending, in range,
        // and as many as there are characters. The crate records those indices
        // itself while it walks, so this is its answer, not a re-derivation.
        assert_eq!(compact.alt_rows, vec![0, 3, 4, 5]);
        assert_eq!(compact.alt_rows, nested.alt_rows);
        assert!(compact.alt_rows.windows(2).all(|w| w[0] < w[1]));
        assert_eq!(compact.alt_rows.len(), nested.alternatives.len());
        for (&t, row) in compact.alt_rows.iter().zip(&alts) {
            assert!(raw[t as usize] == *row);
        }
    }

    /// The vertical fold is the host's map, moved into the decode: the same
    /// characters, the same cells, the same everything else. Three source
    /// characters in one fixture, plus a fourth that is not substituted, and
    /// the non-emitted timesteps so the raw rows are covered too.
    #[test]
    fn vertical_fold_matches_mapping_the_nested_result_afterwards() {
        // class i+1 -> vocab[i]: 1='?', 2='…', 3='‥', 4='a', 5='あ'.
        let d = CtcDecode::new(
            ["?", "…", "‥", "a", "あ"]
                .iter()
                .map(|s| (*s).to_string())
                .collect(),
            (0..=5).collect(),
        );
        let rows = [
            step15(1, 0.9),
            step15(2, 0.85),
            step15(3, 0.8),
            step15(0, 0.95), // blank: no character, but its row is substituted
            step15(4, 0.7),
            step15(5, 0.6),
        ];
        let packed = flatten(&rows);

        // Horizontal: the compact shape with the flag off is the plain decode.
        let flat = d.decode_top_k_compact(packed.clone(), rows.len() as i64, false);
        let (alts, raw) = expand(&flat);
        let plain = nested_top_k(&d, packed.clone(), rows.len() as i64);
        assert_eq!(flat.text, plain.text);
        assert_eq!(flat.text, "?…‥aあ");
        assert_eq!(alts, plain.alternatives);
        assert_eq!(raw, plain.raw_alternatives);

        // Vertical: identical to the nested decode + the host's own mapping.
        let mut by_hand = nested_top_k(&d, packed.clone(), rows.len() as i64);
        vertical_punctuate(&mut by_hand);
        let vertical = d.decode_top_k_compact(packed.clone(), rows.len() as i64, true);
        let (v_alts, v_raw) = expand(&vertical);
        assert_eq!(vertical.text, "？︙︰aあ");
        assert_eq!(vertical.text, by_hand.text);
        assert_eq!(v_alts, by_hand.alternatives);
        assert_eq!(v_raw, by_hand.raw_alternatives);
        assert_eq!(vertical.char_cols, by_hand.char_cols);
        assert_eq!(vertical.alt_rows, vec![0, 1, 2, 4, 5]);
        // Only the blank timestep's row carries a substitute with no character
        // of its own, and it is substituted like every other cell.
        assert_eq!(v_raw[3][0].ch, '\u{3000}' as i32);
        assert_eq!(v_raw[0][0].ch, '？' as i32);
        assert_eq!(v_raw[1][0].ch, '︙' as i32);
        assert_eq!(v_raw[2][0].ch, '︰' as i32);
        assert_eq!(v_raw[4][0].ch, 'a' as i32, "'a' is not a substitution");
        assert_eq!(v_raw[5][0].ch, 'あ' as i32);
        // Scores and columns never move.
        for (v, p) in v_raw.iter().zip(plain.raw_alternatives.iter()) {
            for (vc, pc) in v.iter().zip(p) {
                assert_eq!(vc.score, pc.score);
            }
        }
        assert_eq!(vertical.char_cols, plain.char_cols);
        assert_eq!(vertical.seq_len_total, plain.seq_len_total);
    }

    /// The full-logits lane folds the same way and keeps its own
    /// `seq_len_total` override.
    #[test]
    fn vertical_fold_also_covers_the_full_logits_lane() {
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
        let left = vec![f32::NAN, 0.49, 3.0];
        let right = vec![3.0, 0.0, f32::NAN];

        let nested = nested_full(&d, packed.clone(), left.clone(), right.clone(), 3, 99);
        let compact = d.decode_full_compact(packed.clone(), left.clone(), right.clone(), 3, 99, false);
        let (alts, raw) = expand(&compact);
        assert_eq!(compact.seq_len_total, 99);
        assert_eq!(compact.text, nested.text);
        assert_eq!(compact.char_cols, nested.char_cols);
        assert_eq!(alts, nested.alternatives);
        assert_eq!(raw, nested.raw_alternatives);
        assert_eq!(compact.alt_rows, nested.alt_rows);

        let mut by_hand = nested_full(&d, packed.clone(), left.clone(), right.clone(), 3, 99);
        vertical_punctuate(&mut by_hand);
        let vertical = d.decode_full_compact(packed, left, right, 3, 99, true);
        let (v_alts, v_raw) = expand(&vertical);
        assert_eq!(vertical.text, by_hand.text);
        assert_eq!(v_alts, by_hand.alternatives);
        assert_eq!(v_raw, by_hand.raw_alternatives);
        assert_eq!(vertical.seq_len_total, 99);
    }

    /// A malformed input still fails closed, in the compact shape: an empty
    /// text, no cells, and row boundaries that still describe "no rows".
    #[test]
    fn malformed_compact_input_fails_closed() {
        let d = decoder((0..20).collect());
        let short = d.decode_top_k_compact(vec![0.0, 1.0], 1, true);
        assert!(short.text.is_empty());
        assert!(short.raw_alternatives.is_empty());
        assert!(short.alt_rows.is_empty());
        assert_eq!(short.raw_rows, vec![0]);

        let bad_id = d.decode_top_k_compact(vec![f32::NAN; 30], 1, true);
        assert!(bad_id.text.is_empty());
        assert_eq!(bad_id.raw_rows, vec![0]);

        let full = d.decode_full_compact(vec![0.0; 30], vec![0.0], vec![], 1, 7, true);
        assert!(full.text.is_empty());
        assert_eq!(full.seq_len_total, 7);
        assert_eq!(full.raw_rows, vec![0]);
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
        let got = d.decode_full_compact(
            packed,
            vec![f32::NAN, 0.49, 3.0],
            vec![3.0, 0.0, f32::NAN],
            3,
            99,
            false,
        );
        let (_, raw) = expand(&got);
        assert_eq!(got.text, "いあい");
        assert_eq!(got.seq_len_total, 99);
        assert!(raw[0].iter().all(|cell| cell.ch != 'あ' as i32));
        assert!(got.char_cols[1] < 1.0 && got.char_cols[1] > 0.9);
    }
}
