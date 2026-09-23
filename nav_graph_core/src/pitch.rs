//! UniFFI shim over `jpdict_core::util::pitch` and the pitch half of
//! `jpdict_core::util::japanese` (#43, WP-12 of the util-module conversion
//! wave).
//!
//! Mobile's `PitchAccent` object is two halves:
//!
//! * **Payload parsing** — [`pitch_positions_of`] / [`pitch_reading_of`]
//!   recognise a stored Yomitan pitch payload by shape
//!   (`{"reading":…, "pitches":[{"position":N},…]}`), so any pitch dictionary
//!   works, not just the shipped Kanjium build. On PC these used to sit on the
//!   `db`-gated `overlay_state`; they were moved to the ungated
//!   `core/src/util/pitch.rs` so a nav-only build (Android) can reach them.
//! * **Mora/contour math** — [`pitch_morae_of`], [`pitch_pattern`] and
//!   [`pitch_falls_beyond_word`] delegate to `jpdict_core::util::japanese`,
//!   where the small-kana fusing rule and the Tokyo contour live.
//!
//! `isEnabled`/`setEnabled` are `SharedPreferences` and stay in Kotlin: nothing
//! here touches the Android context. The renderer (`PitchAccentLine`) never
//! consumed the `FUSING` constant directly; it goes through [`pitch_morae_of`].
//!
//! ## Boundary conventions
//!
//! * `usize` crosses as `i64`/Kotlin `Long` (copied from `char_lm.rs`). Kotlin
//!   `Int` mora counts are widened at the call, and the shim reads the widened
//!   value back with `try_from`: a negative or beyond-`i32` count means "no
//!   morae" exactly as the old Kotlin `moraCount <= 0` guard did, never a
//!   wrapped multi-exabyte allocation.
//! * `Option<T>` → nullable `T`; `Vec<bool>` → `List<Boolean>`.
//!
//! ## Kotlin facade contract
//!
//! The generated Kotlin surface (the free functions above in package
//! `uniffi.nav_graph_core`) is wrapped by the hand-written
//! `com.holopengin.instantjpdict.util.PitchAccent` object, which keeps the
//! pre-conversion API byte-for-byte (`PREF_PITCH_ENABLED`, `DEF_PITCH_ENABLED`,
//! `BUNDLED_ASSET`, `isEnabled(Context)`, `setEnabled(Context, Boolean)`,
//! `moraeOf(String)`, `pattern(Int, Int)`, `fallsBeyondWord(Int, Int)`,
//! `positionsOf(String)` and `readingOf(String)`) so no call site or test
//! changes.

/// Downstep positions from a stored pitch payload, or `None` when the entry is
/// not pitch data.
///
/// Detection is by payload shape (both `reading` and a `pitches` array are
/// required); positions come back deduplicated and ascending. Delegates to
/// `jpdict_core::util::pitch::pitch_positions_of`, whose Gson-compatible number
/// handling truncates integer-valued floats (`{"position":1.0}` → `1`).
#[uniffi::export]
pub fn pitch_positions_of(definitions_json: String) -> Option<Vec<i32>> {
    jpdict_core::util::pitch::pitch_positions_of(&definitions_json)
}

/// Reading of a stored pitch payload (`None` when absent or unparsable).
#[uniffi::export]
pub fn pitch_reading_of(definitions_json: String) -> Option<String> {
    jpdict_core::util::pitch::pitch_reading_of(&definitions_json)
}

/// Split a reading into morae: small kana (ゃゅょ… and their katakana) fuse
/// with the preceding kana, while っ, ー and ん keep their own mora (きょう = 2,
/// がっこう = 4, コーヒー = 4).
#[uniffi::export]
pub fn pitch_morae_of(reading: String) -> Vec<String> {
    jpdict_core::util::japanese::morae_of(&reading)
}

/// High/low per mora for a Yomitan downstep position (0 = heiban, no downstep;
/// `N` = pitch falls after mora `N`).
///
/// `mora_count` is Kotlin's `Int` widened to `i64`; a count that is not a
/// non-negative `i32` means "no morae" and yields an empty contour.
#[uniffi::export]
pub fn pitch_pattern(mora_count: i64, position: i32) -> Vec<bool> {
    match mora_count_from_i64(mora_count) {
        Some(n) => jpdict_core::util::japanese::pitch_pattern(n, position),
        None => Vec::new(),
    }
}

/// True when the downstep lands past the final mora (odaka, or the one
/// known-bad row whose position exceeds its mora count): the following particle
/// carries the fall.
#[uniffi::export]
pub fn pitch_falls_beyond_word(mora_count: i64, position: i32) -> bool {
    match mora_count_from_i64(mora_count) {
        Some(n) => jpdict_core::util::japanese::falls_beyond_word(n, position),
        None => false,
    }
}

/// Narrow Kotlin's widened mora count back to the `usize` PC takes.
///
/// Kotlin's `Int` is an `i32`, so the old call sites never produced a count
/// outside that range. Anything else (a negative from the shim's own callers,
/// or a value past `i32::MAX` only a non-Kotlin client could send) is read as
/// "no morae" — the same `moraCount <= 0` guard the Kotlin implementation had,
/// and no `as usize` wrap into a runaway allocation.
fn mora_count_from_i64(mora_count: i64) -> Option<usize> {
    usize::try_from(i32::try_from(mora_count).ok()?).ok()
}

#[cfg(test)]
mod tests {
    //! Mirror of the PC `jpdict_core::util::pitch` unit tests plus the pitch
    //! assertions of `jpdict_core::util::japanese::tests::pitch_morae_and_contour`,
    //! run through the exported surface (not the upstream module), so the shim's
    //! argument conversion is covered too. The JVM suite (`PitchAccentTest`, 11
    //! tests) pins the same behaviour across the UniFFI boundary.

    use super::*;

    // ── morae (mirrors `PitchAccentTest.morae_basic` / `morae_empty`) ──────
    #[test]
    fn morae_basic_and_empty() {
        assert_eq!(pitch_morae_of("きみ".into()), vec!["き", "み"]);
        assert_eq!(pitch_morae_of("うた".into()), vec!["う", "た"]);
        assert_eq!(pitch_morae_of(String::new()), Vec::<String>::new());
    }

    /// Mirrors `PitchAccentTest.morae_small_kana_fuse_but_sokuon_and_long_vowel_stay`.
    #[test]
    fn morae_small_kana_fuse_but_sokuon_and_long_vowel_stay() {
        // 拗音 fuses: きょ is one mora.
        assert_eq!(pitch_morae_of("きょう".into()), vec!["きょ", "う"]);
        assert_eq!(pitch_morae_of("しゃしん".into()), vec!["しゃ", "し", "ん"]);
        // ー and っ are their own morae.
        assert_eq!(pitch_morae_of("コーヒー".into()), vec!["コ", "ー", "ヒ", "ー"]);
        assert_eq!(pitch_morae_of("がっこう".into()), vec!["が", "っ", "こ", "う"]);
        // ん is its own mora.
        assert_eq!(pitch_morae_of("しんぶん".into()), vec!["し", "ん", "ぶ", "ん"]);
    }

    // ── contour (mirrors `pattern_heiban` / `pattern_atamadaka` /
    //    `pattern_nakadaka_and_odaka` / `pattern_empty`) ─────────────────────
    #[test]
    fn pattern_heiban() {
        // 0 = no downstep: L then H — とり renders と gray, り white.
        assert_eq!(pitch_pattern(2, 0), vec![false, true]);
        assert_eq!(pitch_pattern(3, 0), vec![false, true, true]);
        assert_eq!(pitch_pattern(1, 0), vec![false]);
    }

    #[test]
    fn pattern_atamadaka() {
        // 1 = fall right after the first mora.
        assert_eq!(pitch_pattern(2, 1), vec![true, false]);
        assert_eq!(pitch_pattern(3, 1), vec![true, false, false]);
    }

    #[test]
    fn pattern_nakadaka_and_odaka() {
        // L H L for a 3-mora word accented on mora 2.
        assert_eq!(pitch_pattern(3, 2), vec![false, true, false]);
        // Position >= mora count renders like heiban within the word; the
        // particle placeholder carries the difference.
        assert_eq!(pitch_pattern(2, 2), vec![false, true]);
        assert_eq!(pitch_pattern(3, 3), vec![false, true, true]);
        assert_eq!(pitch_pattern(3, 9), vec![false, true, true]);
    }

    #[test]
    fn pattern_empty() {
        assert_eq!(pitch_pattern(0, 1), Vec::<bool>::new());
    }

    // ── falls beyond word (mirrors
    //    `falls_beyond_word_only_for_odaka_and_overrange`) ──────────────────
    #[test]
    fn falls_beyond_word_only_for_odaka_and_overrange() {
        assert!(!pitch_falls_beyond_word(3, 0)); // heiban: no fall at all
        assert!(!pitch_falls_beyond_word(3, 1));
        assert!(!pitch_falls_beyond_word(3, 2));
        assert!(pitch_falls_beyond_word(3, 3)); // odaka
        assert!(pitch_falls_beyond_word(7, 8)); // known-bad row clamps here
        assert!(!pitch_falls_beyond_word(0, 1));
    }

    // ── payload parsing (mirrors `positions_parsed_from_kanjium_payload`) ──
    #[test]
    fn positions_parsed_from_kanjium_payload() {
        assert_eq!(
            pitch_positions_of(r#"{"reading":"ひと","pitches":[{"position":0},{"position":2}]}"#.into()),
            Some(vec![0, 2])
        );
        // Integer-valued floats (Gson round-trip) still parse.
        assert_eq!(
            pitch_positions_of(r#"{"reading":"きみ","pitches":[{"position":1.0}]}"#.into()),
            Some(vec![1])
        );
        // Duplicates collapse, order normalized.
        assert_eq!(
            pitch_positions_of(
                r#"{"reading":"あ","pitches":[{"position":3},{"position":0},{"position":3}]}"#.into()
            ),
            Some(vec![0, 3])
        );
    }

    // ── non-pitch rejection (mirrors `non_pitch_payloads_are_rejected`) ────
    #[test]
    fn non_pitch_payloads_are_rejected() {
        assert_eq!(pitch_positions_of(r#""just a gloss""#.into()), None);
        assert_eq!(pitch_positions_of(r#"{"glossary":"x"}"#.into()), None);
        // pitches present but no reading -> not a pitch payload
        assert_eq!(pitch_positions_of(r#"{"pitches":[{"position":1}]}"#.into()), None);
        // reading present but no pitches -> not pitch data
        assert_eq!(pitch_positions_of(r#"{"reading":"きみ"}"#.into()), None);
        assert_eq!(pitch_positions_of("not json{[".into()), None);
        assert_eq!(pitch_positions_of(String::new()), None);
    }

    // ── reading extraction (mirrors `reading_extracted`) ───────────────────
    #[test]
    fn reading_extracted() {
        assert_eq!(
            pitch_reading_of(r#"{"reading":"きみ","pitches":[{"position":1}]}"#.into()),
            Some("きみ".to_string())
        );
        assert_eq!(pitch_reading_of(r#"{"pitches":[]}"#.into()), None);
        assert_eq!(pitch_reading_of("nope".into()), None);
    }

    // ── PC-only pin of the Gson parity fix (`pitch.rs::float_positions_truncate_like_gson`) ──
    #[test]
    fn number_positions_truncate_like_gson() {
        assert_eq!(
            pitch_positions_of(r#"{"reading":"きみ","pitches":[{"position":2.0},{"position":1.9}]}"#.into()),
            Some(vec![1, 2])
        );
        // A numeric string parses too; a boolean/object/array position is skipped.
        assert_eq!(
            pitch_positions_of(r#"{"reading":"きみ","pitches":[{"position":"3"}]}"#.into()),
            Some(vec![3])
        );
        assert_eq!(
            pitch_positions_of(r#"{"reading":"きみ","pitches":[{"position":true},{"position":1}]}"#.into()),
            Some(vec![1])
        );
    }

    /// The `usize` ↔ `i64` boundary: a negative or beyond-`i32` mora count is
    /// read as "no morae" (the old Kotlin `moraCount <= 0` guard), never
    /// wrapped into a runaway allocation.
    #[test]
    fn mora_count_conversion_guards_the_i64_boundary() {
        assert_eq!(pitch_pattern(-1, 1), Vec::<bool>::new());
        assert_eq!(pitch_pattern(i64::MIN, 1), Vec::<bool>::new());
        assert!(!pitch_falls_beyond_word(-1, 1));
        assert!(!pitch_falls_beyond_word(i64::MIN, 1));
        // Past Kotlin's `Int` range: unreachable from the old call sites, so
        // out-of-contract both ways (no wrap, no allocation).
        assert_eq!(pitch_pattern(i32::MAX as i64 + 1, 0), Vec::<bool>::new());
        assert!(!pitch_falls_beyond_word(i32::MAX as i64 + 1, 1));
        // A negative position is heiban in the old math (position <= 0).
        assert_eq!(pitch_pattern(3, i32::MIN), vec![false, true, true]);
    }

    // ── PC japanese.rs pin (`pitch_morae_and_contour`) through the surface ─
    #[test]
    fn pc_morae_and_contour_pin() {
        assert_eq!(pitch_morae_of("きょう".into()), vec!["きょ", "う"]);
        assert_eq!(pitch_morae_of("がっこう".into()).len(), 4);
        assert_eq!(pitch_morae_of("コーヒー".into()).len(), 4);
        assert_eq!(pitch_pattern(4, 0), vec![false, true, true, true]);
        assert_eq!(pitch_pattern(4, 1), vec![true, false, false, false]);
        assert_eq!(pitch_pattern(4, 3), vec![false, true, true, false]);
        assert!(pitch_falls_beyond_word(4, 4));
        assert!(!pitch_falls_beyond_word(4, 3));
        assert!(!pitch_falls_beyond_word(0, 0));
    }
}
