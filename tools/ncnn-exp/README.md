# ncnn x86 experiment harness (det + rec)

Everything here is measurement scaffolding for the ncnn workstream. It builds
against the **pinned fork** the repo ships (`tools/build_ncnn.sh`,
`FORK_PIN=b498fc07`) and never modifies the models or the pinned tree.

## Files

| file | what it is |
|---|---|
| `build.sh` | builds the harness; `--bench` adds `-DNCNN_BENCHMARK=ON` for ncnn's own per-layer timing |
| `bench.cpp` | loads a model, synthesises or reads the exact runtime input, times `Extractor::extract`, prints one TSV row |
| `mkraw.py` | PNG -> raw uint8 (stdlib zlib only; this host has no PIL/numpy) |
| `sweep.py` | interleaved, rotating-order option sweep with per-config parity against the baseline |
| `layerprof.py` | parses ncnn's `NCNN_BENCHMARK` stderr into a per-layer / per-class ranking |

## Build

```sh
source /tmp/opencode/env-x86.sh        # cmake shim + ninja + gcc wrapper
bash tools/build_ncnn.sh --out /tmp/opencode/ncnn_build --skip-android
bash tools/ncnn-exp/build.sh           # -> tools/ncnn-exp/build/bench
bash tools/ncnn-exp/build.sh --bench   # -> tools/ncnn-exp/build/bench-bench
```

The repo's `build_ncnn.sh` only builds the quantisation tools for the host, so
`build.sh` adds the `ncnn` library target to the *same* build directory (same
source, same flags). `--bench` uses a second build dir because
`NCNN_BENCHMARK` is a compile-time option.

## Inputs

Both models are used at their shipped runtime shapes, with the app's own
geometry and normalisation (`ppocr_ncnn_core.cpp` / `OcrEngine.kt`):

* **det** — a real page letterboxed to 896x896, gray-128 padding *before*
  normalisation, per-channel ImageNet mean/std.
* **rec** — a real text line's tight crop scaled to 48 x W, PP-OCR
  `gray/127.5-1`, columns `[targetW, modelW)` left at 0.0f (the app's
  `buildRecInput` writes only `[0, contentW)` into a zero-filled array).

`--fill` stretches the crop over the whole model width, which is what a line
whose *natural* width is that width produces; without it the harness uses the
app's `targetW = rw*48/rh` rule and zero-pads to the mult-of-8 width.

## Trust rules

1. `stable=ok` — the first two timed runs were bitwise identical. A config that
   reports `UNSTABLE` is not to be believed.
2. `bitident=1` — the whole output tensor equals the baseline's bytes.
3. rec additionally decodes the logits (greedy CTC + `vocab.json` +
   `rec_remap.txt`, the app's own convention: class 0 is blank, class i+1 is
   `vocab[remap[i]]`) and prints the text. **Equal text is the gate**, not a
   small float delta: the decode only needs the argmax to survive.
4. det compares the DB probability map at the app's own 0.3 threshold as a mask
   IoU — the quantity the detector's quality gate is stated in (box IoU).

## A measurement trap worth knowing

det at 896x896 costs **~268 ms on a synthetic letterbox and ~432 ms on a real
page** on this host — 1.6x from the pixels alone (the graph is fixed, so this is
data-dependent time: activations stop being zeros). Any det number is only
comparable at identical input, so every config in a sweep gets the same one.
