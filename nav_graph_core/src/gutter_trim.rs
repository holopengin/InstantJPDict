//! UniFFI shim over `jpdict_core::gutter_trim` — Android ruby-gutter trim.
//!
//! The shared PC algorithm is crop-scoped at this boundary.  Kotlin clips the
//! candidate box to the Bitmap, reads only that `crop_width * crop_height`
//! ARGB buffer, and passes it here as a row-major `List<Int>`.  This shim
//! converts that one crop to the row-major luminance buffer consumed by the
//! ungated PC module; it contains no median/candidate orchestration of its
//! own.
//!
//! Android's existing `RUBY_TRIM_VERTICAL` preference and median/gate walk
//! remain in `OcrEngine.kt`; only its per-candidate `findRubyGutterCut` call
//! crosses this boundary.  The desktop keeps its full-image, environment-
//! gated adapter and pure PC trim API unchanged.
//!
//! The one generated Kotlin function is
//! `gutterTrimFindRubyGutterCut(bboxInCrop, pixels, cropWidth, cropHeight): Int?`.
//! `bboxInCrop` is expressed in crop-local coordinates.  A successful result is a
//! crop-local x coordinate; the Kotlin facade adds the crop's global `x0`.
//! A missing or short pixel buffer is a no-cut input, matching the old
//! `Bitmap.getPixels` failure guard.

use crate::BoundingBox;

fn core_box(b: &BoundingBox) -> jpdict_core::models::BoundingBox {
    jpdict_core::models::BoundingBox::new(b.x, b.y, b.w, b.h, 1.0)
}

/// Convert one row-major crop of ARGB `Int`s to the channel-mean luminance
/// buffer consumed by the PC pure stage.  A short buffer disables the cut;
/// extra values are ignored, as in the existing char-placement shim.
fn luminance_from_argb(
    pixels: Option<&[i32]>,
    crop_width: i32,
    crop_height: i32,
) -> Option<(Vec<f32>, u32, u32)> {
    let width = u32::try_from(crop_width).ok()?;
    let height = u32::try_from(crop_height).ok()?;
    if width == 0 || height == 0 {
        return None;
    }
    let need = (width as usize).checked_mul(height as usize)?;
    let pixels = pixels?;
    if pixels.len() < need {
        return None;
    }

    let mut rgb = Vec::with_capacity(need.saturating_mul(3));
    for &pixel in pixels.iter().take(need) {
        let pixel = pixel as u32;
        rgb.push(((pixel >> 16) & 0xFF) as u8);
        rgb.push(((pixel >> 8) & 0xFF) as u8);
        rgb.push((pixel & 0xFF) as u8);
    }
    Some((
        jpdict_core::gutter_trim::luminance_from_rgb(&rgb, width, height),
        width,
        height,
    ))
}

/// Evaluate the shared gutter cut/fallback rule for one candidate crop.
///
/// `bbox_in_crop` uses crop-local coordinates and `pixels` is exactly the
/// corresponding `crop_width * crop_height` row-major ARGB crop.  The
/// returned x coordinate is also crop-local; the Android facade translates
/// it back to image coordinates after adding its crop origin.
#[uniffi::export]
pub fn gutter_trim_find_ruby_gutter_cut(
    bbox_in_crop: BoundingBox,
    pixels: Option<Vec<i32>>,
    crop_width: i32,
    crop_height: i32,
) -> Option<i32> {
    let (luminance, width, height) =
        luminance_from_argb(pixels.as_deref(), crop_width, crop_height)?;
    jpdict_core::gutter_trim::find_ruby_gutter_cut(
        &core_box(&bbox_in_crop),
        &luminance,
        width,
        height,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bb(x: i32, y: i32, w: i32, h: i32) -> BoundingBox {
        BoundingBox { x, y, w, h }
    }

    const WHITE: i32 = -1;
    const BLACK: i32 = 0xFF00_0000u32 as i32;

    /// The PC `ruby_gutter_trim_cuts_the_ruby_side` candidate expressed as
    /// only its 120×240 crop: main ink at x=30..56, ruby at x=66..90.
    fn cut_pixels() -> Vec<i32> {
        let (w, h) = (120usize, 240usize);
        let mut pixels = vec![WHITE; w * h];
        for y in 10..230 {
            for x in 30..56 {
                pixels[y * w + x] = BLACK;
            }
            for x in 66..90 {
                pixels[y * w + x] = BLACK;
            }
        }
        pixels
    }

    /// A white-border / black-interior crop has light background polarity and
    /// ink in every column, so neither the gutter nor the thin-spot fallback
    /// fires.
    fn no_cut_pixels() -> Vec<i32> {
        let (w, h) = (120usize, 240usize);
        let mut pixels = vec![BLACK; w * h];
        let mut x = 0;
        while x < w {
            pixels[x] = WHITE;
            pixels[(h - 1) * w + x] = WHITE;
            x += 7;
        }
        let mut y = 0;
        while y < h {
            pixels[y * w] = WHITE;
            pixels[y * w + w - 1] = WHITE;
            y += 7;
        }
        pixels
    }

    fn candidate() -> BoundingBox {
        bb(0, 0, 120, 240)
    }

    #[test]
    fn ruby_gutter_trim_cuts_the_ruby_side() {
        assert_eq!(
            gutter_trim_find_ruby_gutter_cut(candidate(), Some(cut_pixels()), 120, 240,),
            Some(56)
        );
    }

    #[test]
    fn solid_wide_box_has_no_cut() {
        assert_eq!(
            gutter_trim_find_ruby_gutter_cut(candidate(), Some(no_cut_pixels()), 120, 240,),
            None
        );
    }

    #[test]
    fn missing_or_short_pixels_return_no_cut() {
        assert_eq!(
            gutter_trim_find_ruby_gutter_cut(candidate(), None, 120, 240),
            None
        );
        assert_eq!(
            gutter_trim_find_ruby_gutter_cut(candidate(), Some(vec![WHITE; 10]), 120, 240,),
            None
        );
    }
}
