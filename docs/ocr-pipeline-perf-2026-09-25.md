# OCR pipeline performance — root-cause analysis (2026-09-25)

Fixture: `app/src/androidTest/assets/bookpage.png`, 1080x2400, 18 detector
boxes. Device: Pixel 7a, Android 17, `benchmark` build type
(non-debuggable), arm64 **release** core. Stage times are from the app's own
logcat (`PPOCREngine`, `PpocrNcnn`, `BookDump`) on a warm engine.

## Headline

| | baseline | first pass | final |
|---|---:|---:|---:|
| detect (net / post) | 614 ms (546 / 68) | 349 ms (248 / 52) | **310 ms** |
| recognition, 18 lines | 638 ms | 543 ms | **532 ms** |
| lines decoded | 16 / 18 (a batch threw) | 18 / 18 | **18 / 18** |
| page wall | **1252 ms** | 892 ms | **842 ms** |

"First pass" = det threads=2 + the CTC char-contract fix. "Final" adds the
native det letterbox, compact char-placement evidence, overlay globals-once,
the rec-prep fold, punctuation folded into the decode, and the blank-gap plan:
**~33% off the wall while recovering the two lines the crash dropped**.

The wall clock is **native inference**, not FFI: two ncnn nets are ~90% of the
page. The entire UniFFI + Android-frontend cost inside recognition is
~55-95 ms. FFI was worth fixing for correctness and for the overlay, not for
the page number.

## Model precision — rec is already INT8

Both nets are quantized, just differently:

* **`rec_dyn.bin` is INT8.** Its first four bytes are `38 4b 0d 00` =
  ncnn's int8 model magic `0x000D4B38`, and `docs/ncnn-conversion.md`
  records it as the renamed `rec_w480` INT8 bin (full-INT8 via
  `ncnn2table`/`ncnn2int8`, calibration via `tools/gen_rec_calib_npy.py`;
  parity vs fp16 93.3 % / CER 0.007 at w480, and full-INT8 never lost on
  speed for any bucket).
* **`det.bin` is FP16-storage** (magic `0x01306B47`). Detector INT8 was
  tried (#16/#22) and rejected: box mean-IoU 0.61 calib / 0.89 bench vs the
  0.95 gate, damage distributed across the backbone; per-layer exclusions
  did not recover it.

So the `use_fp16_*` sweep was **not** about weight precision. In ncnn those
options select activation storage/arithmetic for the non-int8 parts of the
graph (Gemm, LayerNorm, Swish, the dequant/requant edges); enabling them on
the int8 rec net changed decoded text (18→17 lines) and was slower, which is
why `rec_create` keeps them off while `use_packing_layout = true` stays on
(required for packed int8). Nothing was lost when the bucketed models were
replaced by the single dynamic-width model.

## Root causes by layer

### Rust / native backend (dominant)

* **detector ran 1 ncnn thread.** `#25` had compared 1 thread against
  *4-thread fp32*, never against 2-thread fp16. Paired alternating sweep on
  the fixture: t1 medians 379/377/419/427 ms, **t2 237/265 ms**, t4 522 ms;
  every t2 cell returned the identical 18 boxes and all 18 lines (box IoU
  mean/min 1.0). **Fixed: `opt.num_threads = 2`.**
* **The 896x896 letterbox is real waste (~55% of det FLOPs on a portrait
  page) but cannot be removed.** The DB net is not quality-invariant to a
  rectangular input: 832x896 already lost a line and worst-box IoU fell to
  0.63; 512x896 fell to 0.41. The square stays.
* **Recognition config is already at its local optimum.** Measured on fixed
  detector output: fp32/t1/batch4/fanout4 = 570 ms; fanout1 1013 ms, fanout2
  967 ms, batch8+fanout4 726 ms, `REC_THREADS` 2/4 worse. **fp16 is not
  usable**: it is slower at t1/t2 and changes the result from 18 to 17 lines
  (accuracy gate). Native model *extraction* dominates per line (7-110 ms);
  the top-K projection costs 0.1-0.9 ms.
* `set_cpu_powersave(0)` is the effective default; `powersave=1` is 3.2-3.6x
  slower, so leave it.

### FFI marshalling (~55-95 ms inside recognition)

Measured element costs on device: ~66 ns/pixel for the generated pixel list,
~38 ns/pixel for the raw primitive list, ~76 us per scalar JNA call.

| crossing | per page | cost | notes |
|---|---:|---:|---|
| `CharPlacement.place` / `ocrEngineComputeCharBoxes` | 559,535 ints / 2.24 MB | ~40 ms | whole ARGB crop per line; the single worst crossing. Snap only needs a central-band ink profile + border polarity (~10,216 floats). |
| `japaneseVerticalPunctuationChars` (text + alternatives) | 21,210 ints | 8.1 ms | could be folded into `decodeTopK` |
| `blankGapsApply` | 21,851 cells | 5.4 ms | geometry-only; char boxes are already local |
| `CtcDecode.decodeTopK` + materialisation | 26,045 elem | 6.9 ms | 13,695 `GapCell` + 13,695 `Pair` objects |
| `japaneseIsHalfWidth` | 183/draw | 4.3 ms/draw | **per glyph, every overlay redraw** (`LineOverlayView.onDraw`) |
| `mergeBoxesMerge` + `ocrEngineSortOrder` | 74 elem | 0.3 ms | negligible |
| kana-size policy + callback | ~4.5 KB | 0.6 ms | negligible |

`DEF_BOX_PLACEMENT_CAP = true`, so the live char-box call also carries
`steps` (`seqLen x 15` cells); the pixel crossing dominates either way.
There is **no full-class Kotlin loop** on the normal page: the native top-15
succeeds, so the 13,353-class fallback never runs.

### Android frontend

* **`addLineToResults` rebuilt the nav graph per line** — O(L^2) prefix
  rebuilds: 19.7 ms for 17 prefixes, ~21 ms `updateGlobalData` total. Build
  once when the streaming pass completes.
* **Crop pipeline**: `createBitmap` crop -> portrait rotation -> rec resize,
  then eager `recycle`: 23 ms measured + ~15 ms extrapolated. One canvas
  draw from the source rect into the final `48 x targetW` bitmap removes the
  intermediates.
* `buildRecInput` normalisation: 840,960 channel writes/page, 3.15 ms.
* `measureInkHalfWidths` does a `Paint.getTextBounds` per character per
  horizontal line.
* `updateGlobalData` creates 1,797 one-character `String`s per page.

### Correctness regression found on the way (fixed)

The P5 CTC conversion passed a Rust `char` (Unicode scalar) as `i32` where
Kotlin needs a UTF-16 unit: a supplementary-plane vocab entry (904 of the
18,708 entries) threw `Invalid Char code: 128104` and **killed a whole
recognition batch** — 4 of 18 lines silently missing. Fixed at the shim
boundary (`to_kotlin_char`, first UTF-16 unit, matching the old
`String.firstOrNull()`), with a regression test.

## Status of the ranked fixes

| fix | layer | outcome |
|---|---|---|
| snap profile instead of crop pixels | FFI | **landed** (`0daeabd`): CAP 59.7 -> 21.5 ms, legacy 45.2 -> 13.3 ms, boxes bit-identical |
| nav graph built once, not per line | frontend | **landed** (`f68e656`): 17 -> 1 rebuild, ~20 ms |
| cache `isHalfWidth` per char | frontend | **landed** (`f68e656`): 0 crossings per redraw (was 4.3 ms) |
| native det letterbox/normalise | native + frontend | **landed** (`08787a3`): 11.5 -> 7.0 ms warm, 19.4 -> 13.5 ms cold, bit-exact |
| det input-size sweep (square 768/640) | native | **rejected**: 768 merges/loses real lines and substitutes characters; 896 is quality-bound, not size-bound |
| one-shot crop/rotate/resize transform | frontend | **partly landed**: the portrait crop+rotate fold and the char-box evidence read off the page rect are bit-exact (~3-5 ms/page); the fully fused one-draw is **rejected** — not bit-exact (83 text/column divergences over three fixtures) and slower on line-dense pages |
| vertical punctuation inside `decodeTopK` | FFI | **landed** (`8b2f135`): ~8 ms/page, one flag on the decode call |
| patch-based blank-gap result | FFI | **landed** (`8b2f135`): 4.4-6.6 -> 2.3-3.0 ms, and it stops a lone surrogate degrading to U+FFFD |
| flat/compact CTC result, lazy alternatives | FFI | **landed** (`8b2f135`) but **not a perf win on its own** (~0.3 ms) — it is where the punctuation fold lives. The real remaining win is in `LineResult`: carry `rawTopChars`/`rawTopScores` and make `rawAlternatives` lazy, since the page path only ever reads the per-timestep argmax (~4-5 ms). |

Measurement corrections from the later passes:

* the audit's "crop recycle 10.32 ms" was a batch-accounting artifact (the real
  recycles are 0.6-1.6 ms/page), and `buildRecInput` is the largest single prep
  stage (3.2/6.4/12.8 ms/page as line count grows), not the resize;
* the `recTopK extract=/topk=` log is correct as printed —
  `ncnn::get_current_time()` is ms, the `*1000` makes the timestamp microseconds
  and the print divides back to ms — so the "1000x understated" note was wrong;
* the det model must stay square: rectangles lose lines/IoU, and smaller squares
  (768/640) do too.
