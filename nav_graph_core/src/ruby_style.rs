//! UniFFI shim over `jpdict_core::ruby_style` (WP-06 of the util-module
//! conversion wave; see `char_lm.rs` for the standing boundary conventions).
//!
//! PC resolves the ruby treatment as UI-free data: display-point sizes plus
//! linear-RGB colours. Android's `RubyBaseStyle` enum carries only the base
//! colour as an sRGB ARGB int and the bold flag, so the record crosses with
//! `[f32; 3]` flattened to `Vec<f32>` and the hand-written Kotlin facade
//! drops the two sizes (the `TextView` call site sets type sizes itself) and
//! gamma-encodes `base` from linear RGB to ARGB. Nothing here implements a
//! rule; the white-regular body / bold-cyan term decision lives upstream.

/// Resolved ruby treatment for one renderer input, field-for-field the PC
/// `jpdict_core::ruby_style::RubyStyle`.
///
/// `base`/`ruby` are **linear RGB** (the PC paint space), not sRGB; the
/// Kotlin facade applies the sRGB transfer function before handing Android an
/// ARGB integer. `base_size`/`ruby_size` are display points unused on Android
/// (see the module note).
#[derive(Clone, Debug, uniffi::Record)]
pub struct RubyStyle {
    /// Base-text size in display points (dropped by the Kotlin facade).
    pub base_size: f32,
    /// Ruby-text size in display points (dropped by the Kotlin facade).
    pub ruby_size: f32,
    /// Base-text colour as linear RGB.
    pub base: Vec<f32>,
    /// Ruby-text colour as linear RGB.
    pub ruby: Vec<f32>,
    /// Whether the base renders bold.
    pub bold: bool,
}

/// Resolve the ruby treatment: `is_mini = true` is the definition/example
/// body build (white regular base), `false` the headword/term display (bold
/// cyan). The ruby row is the same gray either way.
///
/// Delegates to `jpdict_core::ruby_style::ruby_style`; the Kotlin facade wraps
/// this back into `RubyBaseStyle.forMini(isMini)`.
#[uniffi::export]
pub fn ruby_style_for_mini(is_mini: bool) -> RubyStyle {
    let style = jpdict_core::ruby_style::ruby_style(is_mini);
    RubyStyle {
        base_size: style.base_size,
        ruby_size: style.ruby_size,
        base: style.base.to_vec(),
        ruby: style.ruby.to_vec(),
        bold: style.bold,
    }
}

#[cfg(test)]
mod tests {
    //! Mirror of `RubyBaseStyleTest` (the PC module ships no unit tests of its
    //! own), run against the exact dependency this crate delegates to, through
    //! the exported surface rather than upstream, so the record/argument
    //! conversion is covered too. The `ruby-style-01-modes` conformance case
    //! pins the same base-colour/bold rule across the UniFFI boundary on the
    //! JVM. Values are written as literals on purpose: they pin parity with
    //! Android, so an upstream constant change must fail here, not drift.

    use super::*;

    /// The one ruby row shared by both modes (`jpdict_core` `RUBY_GRAY`).
    const SHARED_GRAY: [f32; 3] = [0.75, 0.75, 0.75];

    #[test]
    fn mini_is_white_regular() {
        let style = ruby_style_for_mini(true);
        assert_eq!(style.base, vec![1.0, 1.0, 1.0], "body base is white");
        assert!(!style.bold, "body base is regular");
    }

    #[test]
    fn term_is_bold_cyan() {
        let style = ruby_style_for_mini(false);
        assert_eq!(style.base, vec![0.0, 1.0, 1.0], "term base is cyan");
        assert!(style.bold, "term base is bold");
    }

    #[test]
    fn ruby_row_is_shared_gray_in_both_modes() {
        for is_mini in [true, false] {
            assert_eq!(
                ruby_style_for_mini(is_mini).ruby,
                SHARED_GRAY,
                "is_mini = {is_mini}"
            );
        }
    }

    /// The sizes are part of the exported record even though Android drops
    /// them; the literals pin the values the `TextView` call site hard-codes.
    #[test]
    fn sizes_are_part_of_the_exported_record() {
        let body = ruby_style_for_mini(true);
        assert_eq!((body.base_size, body.ruby_size), (15.0, 9.0), "body sizes");
        let term = ruby_style_for_mini(false);
        assert_eq!((term.base_size, term.ruby_size), (32.0, 13.0), "term sizes");
    }
}
