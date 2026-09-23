//! UniFFI shim over `jpdict_core::util::char_lm` (#44) — **WP-01, the template
//! module for the util-module conversion wave.**
//!
//! This file is the standing pattern for every `util/*` conversion: a thin
//! UniFFI surface that delegates to the PC crate, so the Kotlin fork is
//! deleted and the algorithm has one source of truth. Nothing here implements
//! an algorithm; if this file grows past record/argument conversion, the
//! logic belongs upstream in `jpdict_core`.
//!
//! ## Boundary conventions (decided here, copied by every later package)
//!
//! * **`char` crosses as `i32`.** UniFFI 0.28 has no `char`; `i32` maps to
//!   Kotlin `Int` natively and every code point (max `0x10FFFF`) fits.
//!   `&[char]` parameters become `Vec<i32>`; `Vec<char>` returns become
//!   `Vec<i32>`. Kotlin facades convert with `Char.code` / `Char(code)`.
//!   Invalid code points (negative, surrogates) degrade to U+FFFD.
//! * **`usize` crosses as `i64`/`Long`.** UniFFI has no `usize`; `i64` is the
//!   choice for this crate (counts, indices, entry counts).
//! * **Fixed-size arrays cross as `Vec`.** `[f32; 3]` → `Vec<f32>`,
//!   `[i32; N]` → `Vec<i32>`.
//! * **Stateful tables are `uniffi::Object`s.** They are plain owned data and
//!   `Send + Sync`, so UniFFI can hold them behind an `Arc`. `HashMap` fields
//!   stay private inside the object; only owned results cross.
//! * **Stateless functions are `#[uniffi::export]` free functions.** Data
//!   classes are `uniffi::Record`s.
//! * **Closures never cross.** A PC `&dyn Fn` parameter becomes an explicit
//!   dependency the shim closes over internally (see WP-10's planned
//!   `variant_forms`), never a Kotlin callback.
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (`uniffi.nav_graph_core.CharLm`) is wrapped by
//! the hand-written `com.holopengin.instantjpdict.util.CharLm` facade, which
//! keeps the pre-conversion API byte-for-byte (`count(CharSequence)`,
//! `logProb(CharSequence, Char)`, `rank(CharSequence, List<Char>)`,
//! `fromBytes(ByteArray)`, `load(Context)`, `MAX_ORDER`) so no call site or
//! test changes. The facade absorbs all `Char` ↔ `Int` conversion.

use std::sync::Arc;

/// Decode a Kotlin `List<Int>` of code points back into Rust `char`s.
///
/// The Kotlin side always sends valid `Char.code` values; anything else (a
/// negative, a surrogate) degrades to U+FFFD rather than panicking at the FFI
/// boundary.
fn chars_from_codepoints(codepoints: &[i32]) -> Vec<char> {
    codepoints
        .iter()
        .map(|&cp| u32::try_from(cp).ok().and_then(char::from_u32).unwrap_or('\u{FFFD}'))
        .collect()
}

/// Character n-gram language model (#44): the text prior that ranks blank
/// candidates where shape alone cannot.
///
/// Wraps `jpdict_core::util::char_lm::CharLm`; the packed-table format is
/// documented upstream. Built by [`char_lm_from_bytes`] from the host's
/// `lm/char_lm.bin` bytes (APK assets are not filesystem paths, so the host
/// reads the file and passes the bytes).
#[derive(uniffi::Object)]
pub struct CharLm {
    inner: jpdict_core::util::char_lm::CharLm,
}

/// Wrap packed bytes. `None` when the header or length does not describe a
/// table (the blank's list then keeps its discovery order).
///
/// A free function, not a constructor: uniffi_bindgen requires constructor
/// return types to be `Self`/`Arc<Self>` (no `Option`) and rejects associated
/// functions, so a fallible factory cannot be either. The Kotlin facade wraps
/// it back into its `CharLm.fromBytes` companion API.
#[uniffi::export]
pub fn char_lm_from_bytes(data: Vec<u8>) -> Option<Arc<CharLm>> {
    jpdict_core::util::char_lm::CharLm::from_bytes(data)
        .map(|inner| Arc::new(CharLm { inner }))
}

#[uniffi::export]
impl CharLm {
    /// Entry count from the header (for logs and tests).
    pub fn entries(&self) -> i64 {
        self.inner.entries() as i64
    }

    /// Occurrences of `ngram`, or 0 when it is unknown (or longer than the
    /// model's order).
    pub fn count(&self, ngram: Vec<i32>) -> u32 {
        self.inner.count(&chars_from_codepoints(&ngram))
    }

    /// `log P(ch | context)`: the longest context the model knows, backed off
    /// one character at a time, and a unigram prior when nothing longer is
    /// known. Unknown characters score last.
    pub fn log_prob(&self, context: Vec<i32>, ch: i32) -> f32 {
        let ch = chars_from_codepoints(&[ch])[0];
        self.inner.log_prob(&chars_from_codepoints(&context), ch)
    }

    /// `candidates` ordered by how well `context` predicts them, best first.
    /// Ties keep the input order.
    pub fn rank(&self, context: Vec<i32>, candidates: Vec<i32>) -> Vec<i32> {
        self.inner
            .rank(&chars_from_codepoints(&context), &chars_from_codepoints(&candidates))
            .into_iter()
            .map(|c| c as i32)
            .collect()
    }
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::util::char_lm` unit tests, run against
    //! the exact dependency this crate delegates to — through the exported
    //! surface, not the upstream module, so the shim's argument conversion is
    //! covered too. The JVM suite (`CharLmAssetTest` + the `charlm-01`
    //! conformance case) pins the same behaviour across the UniFFI boundary.

    use super::*;
    use jpdict_core::util::char_lm::MAX_ORDER;

    /// "CLM1" little endian (the shim does not re-export upstream consts).
    const MAGIC: u32 = 0x314D_4C43;

    /// A toy table packed exactly like `tools/pack_char_lm.py` writes it:
    /// header + 10-byte records sorted by zero-padded code units.
    fn packed(entries: &[(&str, u16)], mass: u32) -> Vec<u8> {
        let mut records: Vec<([u16; MAX_ORDER], u16)> = entries
            .iter()
            .map(|(ngram, count)| {
                let mut units = [0u16; MAX_ORDER];
                for (i, ch) in ngram.chars().enumerate() {
                    units[i] = ch as u16;
                }
                (units, *count)
            })
            .collect();
        records.sort_by(|a, b| a.0.cmp(&b.0));
        let mut out = Vec::new();
        out.extend_from_slice(&MAGIC.to_le_bytes());
        out.extend_from_slice(&(records.len() as u32).to_le_bytes());
        out.extend_from_slice(&(MAX_ORDER as u32).to_le_bytes());
        out.extend_from_slice(&mass.to_le_bytes());
        for (units, count) in records {
            for unit in units {
                out.extend_from_slice(&unit.to_le_bytes());
            }
            out.extend_from_slice(&count.to_le_bytes());
        }
        out
    }

    fn toy() -> Arc<CharLm> {
        // A tiny "私の…" corpus: 私 100, の 200, を 50; 私の 40, 私を 5, のは 60.
        char_lm_from_bytes(packed(
            &[
                ("私", 100),
                ("の", 200),
                ("を", 50),
                ("は", 30),
                ("私の", 40),
                ("私を", 5),
                ("のは", 60),
                ("私は", 20),
            ],
            500,
        ))
        .expect("toy table")
    }

    fn cps(s: &str) -> Vec<i32> {
        s.chars().map(|c| c as i32).collect()
    }

    #[test]
    fn count_finds_entries_of_every_order_and_misses_cleanly() {
        let lm = toy();
        assert_eq!(lm.count(cps("私")), 100);
        assert_eq!(lm.count(cps("私の")), 40);
        assert_eq!(lm.count(cps("私のは")), 0, "unknown trigram");
        assert_eq!(lm.count(vec![]), 0, "empty");
        assert_eq!(lm.count(cps("私のはをに")), 0, "too long");
    }

    /// The back-off chain: the longest known context wins, and a context the
    /// model does not know falls back to the unigram prior.
    #[test]
    fn log_prob_backs_off_from_the_longest_known_context() {
        let lm = toy();
        let bigram = lm.log_prob(cps("私"), 'の' as i32); // 40/100
        let unigram = lm.log_prob(cps("誰"), 'の' as i32); // 200/500
        assert!((bigram - (40.0f32 / 100.0).ln()).abs() < 1e-6);
        assert!((unigram - (200.0f32 / 500.0).ln()).abs() < 1e-6);
        assert!(lm.log_prob(cps("誰"), '漢' as i32) <= -30.0 + 1e-6, "unknown scores last");
    }

    /// Ranking follows the context: after 私 the model prefers の over を.
    #[test]
    fn rank_prefers_what_the_context_predicts() {
        let lm = toy();
        assert_eq!(lm.rank(cps("私"), cps("をの")), cps("のを"));
        assert_eq!(lm.rank(vec![], cps("のは")), cps("のは"), "unigram prior");
    }

    #[test]
    fn malformed_headers_are_rejected() {
        assert!(char_lm_from_bytes(Vec::new()).is_none());
        let mut short = packed(&[("の", 1)], 1);
        short.truncate(16 + 10 - 1);
        assert!(char_lm_from_bytes(short).is_none(), "truncated records");
        let mut wrong_magic = packed(&[("の", 1)], 1);
        wrong_magic[0] = b'X';
        assert!(char_lm_from_bytes(wrong_magic).is_none());
    }

    /// The shipped Android asset: sanity-check the header and that common
    /// Japanese is known while rare/unseen text scores last. Skipped when the
    /// asset is absent (e.g. a checkout without it).
    #[test]
    fn shipped_model_parses_and_knows_japanese() {
        let path = std::path::Path::new(concat!(env!("CARGO_MANIFEST_DIR")))
            .join("../app/src/main/assets/lm/char_lm.bin");
        let Some(lm) = std::fs::read(&path)
            .ok()
            .and_then(char_lm_from_bytes)
        else {
            eprintln!("skipping: {} not present", path.display());
            return;
        };
        // Header sanity (the shipped table is 1.43M entries).
        assert!(lm.entries() > 1_000_000, "entries = {}", lm.entries());
        // の is the most common character in Japanese (its count saturates at
        // the 16-bit cap); a nonsense char is unseen.
        assert!(lm.count(cps("の")) > 50_000, "の = {}", lm.count(cps("の")));
        assert!(lm.count(cps("私")) > 1_000, "私 = {}", lm.count(cps("私")));
        assert_eq!(lm.count(vec!['𐐷' as i32]), 0, "supplementary plane is skipped");
        assert!(lm.log_prob(cps("私"), 'の' as i32) > lm.log_prob(cps("私"), '𠮟' as i32));
        // The context prior works on real text: after 「今日」 the model prefers
        // は, after 「だから」 the comma, after 「定期船」 the の.
        assert_eq!(lm.rank(cps("今日"), cps("をは")), cps("はを"));
        assert_eq!(lm.rank(cps("だから"), cps("を、")), cps("、を"));
        assert_eq!(lm.rank(cps("定期船"), cps("をの")), cps("のを"));
    }
}
