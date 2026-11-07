# 0002 — Shared OCR backend boundary between Android and PC

Status: accepted · 2026-11-07

## Context

InstantJPDict (Android) and InstantJPDictPC (desktop, Rune) run the same
PP-OCRv6 models and the same kana small/large model. The desktop repo also
owns the UI-free Rust pipeline (`jpdict-core`) that the Android app consumes
through the `nav_graph_core` UniFFI shim.

The sharing has two opposite source-of-truth directions:

- **native cores and assets:** mobile is authoritative; PC mirrors
  `ppocr_ncnn_core`, `kana_size_core`, the model directories, and the shared
  pipeline data.
- **pure Rust pipeline:** PC is authoritative; Android pins a `jpdict-core`
  git rev and delegates decode/placement/reading-order/lookup/formatting
  through `nav_graph_core`.

The mirror was manual and drifted: the core sync check failed on a stale
comment, the PC ncnn pin still named the pre-rename `vgf89` account, the
Android `jpdict-core` pin was 18 commits behind, and the Android conformance
corpus was 8 cases behind PC. None of these were caught by CI because neither
repo had a workflow.

## Decision

**Keep the three-layer boundary, and enforce the two mirrors.**

1. **Shared native kernels and models.** `ppocr_ncnn_core.{h,cpp}` and
   `kana_size_core.{h,cpp}`, the PP-OCR/kana models, and the shared pipeline
   data are byte-identical mirrors. `tools/sync_ppocr_core.sh` (PC repo) is
   the single checker/updater; `.githooks/pre-commit` and the
   `shared-ocr-sync` workflows in both repos run `--check`.
2. **Shared pure Rust pipeline.** `jpdict-core` remains the PC-owned source
   of truth. Android consumes it at a pinned rev; a bump updates
   `nav_graph_core/Cargo.toml` + `Cargo.lock` and rebuilds the `.so`/bindings.
3. **Platform I/O and hot pixel loops stay per-platform.** Capture/decoding,
   `Bitmap`/`Canvas` scaling, det/rec pixel packing, and long-line inference
   orchestration are not moved into `jpdict-core`. They are bound to each
   platform's image type, and crossing full pixel/logit buffers per line over
   UniFFI costs more than the duplicated code.

**Do not introduce a shared repo or submodule for the two C++ files.** The
mirror is now enforced automatically; the friction that would justify a
submodule is gone.

## Consequences

- **Merge order is mobile-first.** The PC sync check requires
  `app/src/main/cpp/kana_size_core.{h,cpp}` and the shared assets to exist in
  Android `master`, so the Android change must land before the PC change.
- The duplicated pre/post stages (det letterbox, rec input packing, long-line
  stitch) remain two implementations, pinned by `tests/conformance` and the
  synth parity tests. If a parity bug originates in one of them, move **that
  stage** into `jpdict-core`; do not port the whole pipeline at once.
- A core/asset change must land in Android first. A `jpdict-core` change must
  land in PC first and then be consumed by an Android rev bump.
- CI now checks the mirrors on every push/PR, so a stale copy fails a build
  instead of waiting for a parity test to notice.
