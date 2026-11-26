//! One-call page OCR at the UniFFI boundary.
//!
//! The app captures a bitmap, passes its ARGB pixels and per-run preferences
//! through [`PageRecognizer::recognize`], and receives every line with text,
//! alternatives, CTC columns and char boxes. Detection, recognition, kana
//! correction and CAP placement all run in `jpdict_core::page_ocr`; this file
//! only converts the page records and owns the cancel flag.
//!
//! The native stack is compiled on Android only. A host build (the JVM unit
//! tests) still exports the same surface, but construction fails with a clear
//! message: the test JVM has no Android `.so` and no model assets to load.

use std::sync::Arc;
#[cfg(target_os = "android")]
use std::sync::Mutex;

use crate::blank_gaps::GapCell;
use crate::BoundingBox;

#[derive(Debug, uniffi::Error)]
pub enum PageOcrError {
    Engine { reason: String },
    Unavailable { reason: String },
}

impl std::fmt::Display for PageOcrError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Engine { reason } | Self::Unavailable { reason } => {
                formatter.write_str(reason)
            }
        }
    }
}

impl std::error::Error for PageOcrError {}

#[cfg(target_os = "android")]
fn engine_err(error: impl std::fmt::Display) -> PageOcrError {
    PageOcrError::Engine {
        reason: error.to_string(),
    }
}

/// One point of a located line's upright frame.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct QuadPoint {
    pub x: f32,
    pub y: f32,
}

/// The four corners of a non-axis-aligned line, in source pixels:
/// local `(0,0)`, `(w,0)`, `(w,h)`, `(0,h)`.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct PageQuad {
    pub c0: QuadPoint,
    pub c1: QuadPoint,
    pub c2: QuadPoint,
    pub c3: QuadPoint,
}

/// One recognized line crossing the boundary.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PageLineRecord {
    pub bbox: BoundingBox,
    pub quad: Option<PageQuad>,
    pub text: String,
    pub char_boxes: Vec<BoundingBox>,
    pub alternatives: Vec<Vec<GapCell>>,
    /// The raw top-K table flattened: `raw_cells` is every row concatenated,
    /// `raw_row_starts` its `rows + 1` boundaries. Kotlin rebuilds the compact
    /// `TimestepTopK` from these without materialising a nested list first.
    pub raw_cells: Vec<GapCell>,
    pub raw_row_starts: Vec<i64>,
    pub char_cols: Vec<f32>,
    pub seq_len_total: i32,
    pub crop_x: i32,
    pub crop_y: i32,
    pub crop_w: i32,
    pub crop_h: i32,
    pub is_vertical: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct PageResultRecord {
    pub lines: Vec<PageLineRecord>,
}

/// Per-run knobs. The app reads its preferences once per capture and passes
/// them here, so one engine instance can serve every debug switch without a
/// rebuild.
#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct PageOcrOptionsRecord {
    pub det_model_size: u32,
    pub det_long_side: u32,
    pub det_thresh: f32,
    pub det_unclip: f32,
    pub det_furigana: bool,
    pub det_rotated: bool,
    pub cap: bool,
    pub snap: bool,
    pub uniform: bool,
    pub ruby_trim_vertical: bool,
    pub rec_squish: f32,
    pub rec_workers: u32,
    pub kana_size_correction: bool,
    pub save_samples: bool,
}

/// A loaded page engine: det + rec + kana nets live here, once.
#[derive(uniffi::Object)]
pub struct PageRecognizer {
    #[cfg(target_os = "android")]
    inner: Mutex<jpdict_core::page_ocr::PageOcr>,
    /// Kept outside `inner` on purpose: `cancel()` must not wait for the
    /// in-flight `recognize()` to release the engine lock.
    #[cfg(target_os = "android")]
    cancel: Arc<std::sync::atomic::AtomicBool>,
}

#[uniffi::export]
impl PageRecognizer {
    /// Load the model set from an on-disk directory (the app materialises
    /// `PP-OCRv6_small_ncnn/` and `kana_size/` there first).
    #[uniffi::constructor]
    pub fn new(model_dir: String, batch_size: u32) -> Result<Arc<Self>, PageOcrError> {
        #[cfg(target_os = "android")]
        {
            let page = jpdict_core::page_ocr::PageOcr::new(
                &model_dir,
                jpdict_core::models::RecognitionMode::Both,
                batch_size.max(1) as usize,
            )
            .map_err(engine_err)?;
            let cancel = page.cancel_handle();
            Ok(Arc::new(Self {
                inner: Mutex::new(page),
                cancel,
            }))
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = (model_dir, batch_size);
            Err(PageOcrError::Unavailable {
                reason: "page OCR native stack is built for Android only".into(),
            })
        }
    }

    /// Recognize one page. `rgba` is `width * height * 4` bytes in row-major
    /// RGBA order. It crosses as a `ByteArray` (not a boxed `List<Int>`) so a
    /// full-page capture is one bulk copy.
    pub fn recognize(
        &self,
        rgba: Vec<u8>,
        width: u32,
        height: u32,
        options: PageOcrOptionsRecord,
    ) -> Result<PageResultRecord, PageOcrError> {
        #[cfg(target_os = "android")]
        {
            let pixels = (width as usize).saturating_mul(height as usize);
            let need = pixels.saturating_mul(4);
            if rgba.len() != need {
                return Err(PageOcrError::Engine {
                    reason: format!(
                        "pixel buffer has {} bytes; expected {}x{}x4= {need}",
                        rgba.len(),
                        width,
                        height
                    ),
                });
            }
            let image = image::DynamicImage::ImageRgba8(
                image::RgbaImage::from_raw(width, height, rgba).ok_or_else(|| {
                    PageOcrError::Engine {
                        reason: "pixel buffer does not match the image size".into(),
                    }
                })?,
            );
            let opts = core_options(options);
            let mut page = self.inner.lock().map_err(|_| PageOcrError::Engine {
                reason: "page OCR engine lock poisoned".into(),
            })?;
            let result = page.recognize(&image, &opts).map_err(engine_err)?;
            Ok(map_result(result))
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = (rgba, width, height, options);
            Err(PageOcrError::Unavailable {
                reason: "page OCR native stack is built for Android only".into(),
            })
        }
    }

    /// Ask the in-flight [`recognize`](Self::recognize) to stop. Safe from any
    /// thread; the next call clears the flag.
    pub fn cancel(&self) {
        #[cfg(target_os = "android")]
        {
            self.cancel
                .store(true, std::sync::atomic::Ordering::SeqCst);
        }
    }
}

#[cfg(target_os = "android")]
fn core_options(options: PageOcrOptionsRecord) -> jpdict_core::page_ocr::PageOcrOptions {
    use jpdict_core::ocr_engine::{DetectOptions, RecognizeOptions};
    jpdict_core::page_ocr::PageOcrOptions {
        detect: DetectOptions {
            model_size: options.det_model_size,
            long_side: options.det_long_side,
            thresh: options.det_thresh,
            unclip: options.det_unclip,
            furigana: options.det_furigana,
            expand_cap: f32::INFINITY,
            h_down: 0.0,
            v_trim: 1.0,
            ruby_trim_vertical: options.ruby_trim_vertical,
            split_overlap: false,
        },
        recognize: RecognizeOptions {
            cap: options.cap,
            snap: options.snap,
            uniform: options.uniform,
            rec_squish: options.rec_squish.clamp(0.2, 1.0),
            workers: options.rec_workers.max(1) as usize,
            kana_size_correction: options.kana_size_correction,
            save_samples: options.save_samples,
        },
        det_rotated: options.det_rotated,
    }
}

#[cfg(target_os = "android")]
fn map_result(result: jpdict_core::page_ocr::PageResult) -> PageResultRecord {
    PageResultRecord {
        lines: result.lines.into_iter().map(map_line).collect(),
    }
}

#[cfg(target_os = "android")]
fn map_line(line: jpdict_core::page_ocr::PageLine) -> PageLineRecord {
    PageLineRecord {
        bbox: BoundingBox {
            x: line.bbox.x,
            y: line.bbox.y,
            w: line.bbox.w,
            h: line.bbox.h,
        },
        quad: line.quad.map(map_quad),
        text: line.text,
        char_boxes: line
            .char_boxes
            .into_iter()
            .map(|b| BoundingBox {
                x: b.x,
                y: b.y,
                w: b.w,
                h: b.h,
            })
            .collect(),
        alternatives: map_alts(line.alternatives),
        raw_cells: line
            .raw_alternatives
            .iter()
            .flat_map(|row| row.iter())
            .map(|&(ch, score)| GapCell {
                ch: ch as i32,
                score,
            })
            .collect(),
        raw_row_starts: {
            let mut starts = Vec::with_capacity(line.raw_alternatives.len() + 1);
            starts.push(0i64);
            let mut at = 0i64;
            for row in &line.raw_alternatives {
                at += row.len() as i64;
                starts.push(at);
            }
            starts
        },
        char_cols: line.char_cols,
        seq_len_total: line.seq_len_total,
        crop_x: line.crop_x,
        crop_y: line.crop_y,
        crop_w: line.crop_w,
        crop_h: line.crop_h,
        is_vertical: line.is_vertical,
    }
}

#[cfg(target_os = "android")]
fn map_alts(rows: Vec<Vec<(char, f32)>>) -> Vec<Vec<GapCell>> {
    rows.into_iter()
        .map(|alts| {
            alts.into_iter()
                .map(|(ch, score)| GapCell {
                    ch: ch as i32,
                    score,
                })
                .collect()
        })
        .collect()
}

#[cfg(target_os = "android")]
fn map_quad(quad: jpdict_core::models::RotatedBox) -> PageQuad {
    let (ox, oy) = quad.origin();
    let (ux, uy) = quad.x_axis();
    let (vx, vy) = quad.y_axis();
    let c0 = QuadPoint { x: ox, y: oy };
    let c1 = QuadPoint {
        x: ox + ux * quad.w,
        y: oy + uy * quad.w,
    };
    let c2 = QuadPoint {
        x: c1.x + vx * quad.h,
        y: c1.y + vy * quad.h,
    };
    let c3 = QuadPoint {
        x: c0.x + vx * quad.h,
        y: c0.y + vy * quad.h,
    };
    PageQuad { c0, c1, c2, c3 }
}
