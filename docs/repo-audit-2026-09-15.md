# InstantJPDict repository audit — 2026-09-15

Read-only audit of the combined worktree `/tmp/ijpd-combined` (branch
`test/combined-82-53-81-84`, commit `7c67665`), which is the superset of all shipped
work: camera mode (#78), image share intent (#57), camera shortcut (#82), opt-in
rotated lines plus tategaki size and blob-filter fixes (#53), the historical kana
normaliser (#81), the bundled overlay fonts (#84), the shutter nudge, and
`docs/85-keystone-fit-findings.md`. Where a finding is about a file that does not
exist on the combined branch the master worktree
`/home/holopengin/Projects/InstantJPDict` is named explicitly. Every claim below
cites `file:line` from the combined worktree unless stated otherwise.

**This document is a plan, not a change.** It has not been committed. Nothing in the
audited worktree was modified; the pre-existing dirty `models/archive/*.onnx` LFS
files were not touched.

---

## 1. Scope and method

### 1.1 Read

Project conventions first: `CONTEXT.md`, `AGENTS.md`, `docs/agents/*`, then the
whole of `docs/`. All first-party Kotlin under `app/src/main/java/**` (~11,000 lines
excluding the generated uniffi binding), all JVM tests under `app/src/test/**`, the
instrumentation suite under `app/src/androidTest/**`, the four first-party C++
files under `app/src/main/cpp/**` (the vendored `ncnn/include` headers were only
skimmed), `nav_graph_core/src/lib.rs` and its build script, the Gradle build files
and wrapper metadata, the manifest and resources, the licence-index machinery
(`app/licenses/*.tsv`, `app/src/main/assets/licenses/**`), `tools/**` (headers and
structure), `README.md` and every file in `docs/`.

### 1.2 Ran

| Command (in `/tmp/ijpd-combined`) | Result |
|---|---|
| `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac --rerun-tasks --console=plain` | **BUILD SUCCESSFUL**; 8 Kotlin deprecation warnings (see F5) |
| `./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain` | **BUILD SUCCESSFUL**; 372 tests, 0 failures, 0 errors, 0 skipped (46 result XMLs) |
| `./gradlew :app:lintDebug --console=plain` | **BUILD FAILED** — 3 lint **errors**, 189 warnings; report at `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt` (see F2) |
| `./gradlew :app:assembleDebug --console=plain` | **BUILD SUCCESSFUL**; `app/build/outputs/apk/debug/app-debug.apk` 89,822,599 bytes, native build ran |
| `cargo check --all-targets` in `nav_graph_core` | succeeds with 1 warning (`initial_edges` unused, `src/lib.rs:50`) |
| `cargo clippy --all-targets` | 9 warnings (unused var, 3× `type_complexity`, 3× `needless_range_loop`, `ptr_arg`, manual `!Range::contains`) |
| `python3 -m py_compile tools/*.py tools/model_patching/*.py tools/onnx_quantization/*.py` and an `ast.parse` sweep of `tools/**/*.py` | clean |
| `unzip -v` / `readelf -lW` on the built debug APK | native-library and asset packaging checks; evidence for F1 and §4 |
| `git diff --stat master...HEAD`, `git status`, `git log` | the combined branch adds ~2,246 insertions over master (mostly tests/docs); `models/archive/*.onnx` are pre-existing dirty LFS artifacts, untouched |

Not run, honestly: `app:connectedDebugAndroidTest` (needs a device), the host ncnn
parity gate (needs the `tools/build_ncnn.sh` tree), `uv run` for the Python benches
(needs the uv project / corpus, and their findings are already recorded in docs).

### 1.3 Severity vocabulary

P0 = correctness/data loss/crash; P1 = real bug or contradiction; P2 =
maintainability (dead code, stale docs, duplication, warnings); P3 = polish. Effort
S = under half a day, M = a day or so, L = needs a device/bench or a multi-file
change.

---

## 2. Findings by category

### A. Runtime correctness, crashes and reliability

#### A1 — P0 — the accessibility service closes ncnn nets while a pass may still be inside them
`app/src/main/java/com/holopengin/instantjpdict/OcrAccessibilityService.kt:546-548`
```kotlin
hideScreenshotOverlay()
floatingView?.let { if (it.isAttachedToWindow) windowManager?.removeView(it) }
ocrEngine.close()
```
`OcrEngine.close()` (`OcrEngine.kt:2578-2581`) calls `detNcnn?.close()` /
`recDynNcnn?.close()`, which is `Net::clear()` + `delete` in JNI. `hideScreenshotOverlay`
calls `view.onClosed()` (cancels the overlay coroutine) but cancellation cannot
interrupt a non-suspending native `detect`/`extract`, so a pass can still be
executing when `close()` runs. The image-share path already paid for exactly this
and guards against it, with the crash signature written down:

`ShareImageActivity.kt:453-467`
```kotlin
// Closing there tears down a Net under a running convolution, which faults
// inside ncnn ("pool allocator destroyed too early"; SIGSEGV at 0x0 on an OpenMP
// worker, seen twice on the device when a re-creation landed mid-pass). So the
// close is deferred onto the pass's own completion ...
val passInFlight = currentPass
if (passInFlight == null) { if (::engine.isInitialized) engine.close() }
else { passInFlight.invokeOnCompletion { if (::engine.isInitialized) engine.close() } }
```
Why: two code paths that own the same resource (an `OcrEngine`) apply different
close disciplines; one of them documents the crash. Trigger: the accessibility
service is destroyed (service toggled off, system rebinds/restarts it, app data or
settings change) while an OCR run is in flight. The service is destroyed
asynchronously and there is no `onInterrupt`/`onUnbind` guard either.

Fix: give the service the same discipline — keep the `Job` returned by
`view.startOcr()` (or expose `OcrOverlayView.awaitCurrentPass()`), and move
`ocrEngine.close()` into `passInFlight.invokeOnCompletion { ... }` when a pass is
live; only close inline when none is. Alternatively make `OcrEngine.close()` itself
defer: track the active pass inside the engine (it already knows the batches it is
running) and `close()` behind it. Effort: **S**.

#### A2 — P2 — a rotate press during `refitComposite` can be clobbered (wrong orientation on screen until the next press)
`ShareImageActivity.kt:604-644` captures `val turns = composedTurns` (610) on the
main thread, then suspends at `withContext(Dispatchers.IO) { composeForScreen(...) }`
(627-629) and later commits unconditionally:
```kotlin
image = recomposed
surfaceWidth = width
surfaceHeight = height
composedTurns = turns          // (lines 639-642)
view.refitContent(refit)
```
A `rotate()` press that arrives in that window (`rotate()`, 820-831) starts
`runDisplayTurns()`, which sets `image` and `composedTurns` to the NEW orientation;
the refit coroutine then overwrites both with the OLD `turns`, and calls
`refitContent`. The run queue still says the new orientation, so the next press
"fixes" it, but in between the display shows the old composite while the OCR pump
may start a pass for the wrong orientation. The class comment's claim that "every
mutation here happens on the main dispatcher, so a press cannot interleave"
(882-883) is true only of `runRotations`; `refitComposite` deliberately suspends
across the compose.

Fix: re-read `rotations.turns`/`composedTurns` after the `withContext` returns and
abandon the refit (recycle `recomposed`) when it no longer equals the captured
`turns`; or run the refit through the same `RotationQueue` pump so it cannot
interleave with a press. Effort: **M**.

#### A3 — P2 — the pitch-dictionary failure message is computed and thrown away
`MainActivity.kt:650-661`
```kotlin
withContext(Dispatchers.Main) {
    result.fold(
        onSuccess = { count -> tvStatus.text = ... },
        onFailure = { e -> "Pitch dictionary error: ${e.message}" },   // line 660
    )
}
```
The `onFailure` branch's string is the `fold` result and is never assigned; the
status line keeps showing "Installing pitch dictionary: N entries…" (or nothing)
when the import fails. The sibling path does it correctly
(`MainActivity.kt:687`: `tvStatus.text = result.fold(...)`).

Fix: `tvStatus.text = result.fold(onSuccess = { ... }, onFailure = { ... })` in
both `fold`s (or assign the result to a val). Add a JVM-testable helper if the
message formatting is worth pinning. Effort: **S**.

#### A4 — P2 — #75/#81/#44 installs are silently gated behind the unrelated component-table load
`OverlayEnvironment.kt:42-53`
```kotlin
scope.launch {
    val table = withContext(Dispatchers.IO) {
        runCatching { ComponentTable.load(context) }.getOrNull()
    } ?: return@launch                       // <- everything below is skipped
    withContext(Dispatchers.IO) {
        runCatching { KanjiVariants.install(context) }
        runCatching { KanaOrthography.install(context) }   // #75
        runCatching { KanaSoundChanges.install(context) }  // #81
    }
    controller.installOovSuggestions(OovCandidates(table))
```
The comment at 56-57 says "a failure here only narrows the blank's list", but a
`ComponentTable` failure also disables the pre-reform kana fold (#75), the
historical kana normaliser (#81) and the kanji variant fold for both hosts — with
no log. `OverlayEnvironment` is the single wiring seam for both hosts, so the
failure is uniform and invisible.

Fix: install the three normaliser tables unconditionally (their own
`runCatching`s already isolate failures), and only gate the
`installOovSuggestions`/`installCharLm` calls on the table/LM. Log a warning when
any of them fails so the in-app `InferLog` shows it. Effort: **S**.

#### A5 — P2 — the blank-surfacing path is unreachable and its two decoders use different rules
Three implementations of "surface a character where blank won":
1. Live decode: `OcrEngine.kt:1906-1924` (`ctcDecode`) and `1954-2000`
   (`ctcDecodeTopK`) call `BlankRecovery.shouldSurface(maxVal, sc, blankThreshold)`
   — `candidateProb >= minCandidateProb && candidateProb > blankProb`
   (`BlankRecovery.kt:47-51`).
2. Cached re-decode: `OcrEngine.kt:2498-2501`
   `(1f / (1f + abs(blankScore - sc)) > blankThreshold)` — an unrelated
   distance-to-blank formula.
3. There is no caller of (2) in production: `refreshLinesWithThreshold`
   (`OcrOverlayStateController.kt:191-206`) has no production call site, and every
   live call passes `blankThreshold = 0f`.

Two further traps in the same area: the native rec path deliberately returns
**logits**, not probabilities ("scores become logits, which no consumer reads
absolutely (blankThreshold path is relative, default 0 = pure greedy)",
`ppocr_ncnn_core.cpp:140-143`), so `BlankRecovery`'s "probability" parameters and
its default 0.5 floor compare logits; and `BlankRecovery`'s doc says "enabling it is
about error neutral on the bench — which is why [DISABLED] is the default and this
stays a user-visible choice" (`BlankRecovery.kt:21-25`) while no user-visible
control exists.

Fix: either delete the surfacing branch, `BlankRecovery`, `refreshLinesWithThreshold`
and `reDecodeLineResult`'s `blankThreshold` parameter (keeping the greedy decode
path), or, if it is to be revived, route both decoders through `BlankRecovery` and
document/pin the value domain (logits) with a test. Do not leave a third formula.
Effort: **M** (delete) / **L** (revive).

#### A6 — P2 — `DictionaryImporter` can leak the content stream on the error path
`DictionaryImporter.kt:26-42` opens `contentResolver.openInputStream(uri)` and
hands it to `importZipStream`, which closes the `ZipInputStream` only on the
success path (`:184`). Any exception between `:111` and `:183` (malformed JSON,
IO error, cancellation) propagates to the `catch` at `:38`/`:72` and the underlying
stream is never closed. `importBundledAsset` has the same shape for the asset
stream (`:63`).

Fix: `try/finally` around the stream in `importZipStream` (and the two callers), or
`bufferedStream.use { ... }`. Effort: **S**.

#### A7 — P2 — `detThresh` is read from `SharedPreferences` once per pixel in the detect hot loop
`OcrEngine.kt:265-266` defines the getter as a live prefs read; it is then used
inside the full-image scan at `OcrEngine.kt:529` (`detect`) and `712`/`734`
(`detectRotated`). Each access is a `ContextImpl.getSharedPreferences` map lookup
plus a synchronized `getFloat`, i.e. up to ~800k of them per 896×896 detect.

Fix: hoist to a local at the top of `detect()`/`detectRotated()`
(`val thresh = detThresh`), the way `runDetMask` already does for its log line.
Effort: **S**.

#### A8 — P2 — model assets are copied and nets loaded on the main thread per engine construction, and the kana net runs on the main dispatcher
- `RecNcnn.create` copies `rec_dyn.param` (7.9 KB) **and `rec_dyn.bin` (4.7 MB)**
  from assets to `cacheDir/ncnn` unconditionally on every call,
  `app/src/main/java/com/holopengin/instantjpdict/RecNcnn.kt:91-98`; `DetNcnn.create`
  does the same for `det.bin` (4.9 MB), `DetNcnn.kt:62-69`. `OcrEngine.init` then
  loads both native nets synchronously (`OcrEngine.kt:275-296`).
- `OcrEngine(this)` is constructed on the main thread in
  `OcrAccessibilityService.onCreate` (`:128`) and `ShareImageActivity.onCreate`
  (`:274`); the share activity therefore copies ~10 MB and loads a second pair of
  nets even when the service already has an engine.
- `KanaSizeFix.correctPage` is called from `OcrOverlayView.startOcr`'s coroutine
  body, which runs on `Dispatchers.Main`
  (`OcrOverlayView.kt:105` scope, `:661-663` launch, `:702-703` the call), and it
  can do the first `KanaSizeNcnn.load` (two assets materialised, one net loaded,
  `KanaSizeNcnn.kt:94-108`, `:214-229`) plus a native batch of one extractor per
  candidate character (`kana_size_ncnn.cpp:116-133`) before the results UI is built.

Fix: move `correctPage` into the surrounding `withContext(Dispatchers.IO)` (it is
already inside the recognition coroutine) and construct the engine off the main
thread (`lifecycleScope.launch { withContext(Dispatchers.IO) { OcrEngine(...) } }`),
or share one engine between the service and the share activity. Cache the model
copy with a size/mtime check, or copy once into `filesDir`. Effort: **M**.

#### A9 — P3 — re-pressing the camera shortcut while the viewfinder is on top stacks viewfinder instances
`MainActivity.kt:586-592` starts `ProtoCameraActivity` and then `finish()`es; with
the viewfinder already topmost, `singleTop` does not reuse `MainActivity`, so the
platform pushes a second `MainActivity`, the shortcut handler pushes a second
`ProtoCameraActivity`, and the first viewfinder stays beneath it. Back from the new
camera returns to the previous camera, not the dictionary. The manifest comment
(`AndroidManifest.xml:32-41`) describes the singleTop rule but not this
consequence.

Fix: launch `ProtoCameraActivity` with `FLAG_ACTIVITY_CLEAR_TOP |
FLAG_ACTIVITY_SINGLE_TOP` (then nothing is stacked), or make the shortcut action
a no-op when the viewfinder is already the task top (needs a `singleTask`/
`onNewIntent` route or a task check). Effort: **S**.

---

### B. Lifecycle, threading and native-memory handling

#### B1 — P2 — `shouldShowOnScreenOn` is documented as applied by the service but is inlined instead
`FloatingButtonRestore.kt:23-28`
```kotlin
/**
 * SCREEN_ON visibility rule (refs #60): re-show the button when the screen
 * comes back on an unlocked phone ... the service applies it
 * [sic] in [OcrAccessibilityService.ensureFloatingButton]   <- actually not
 */
internal fun shouldShowOnScreenOn(isKeyguardLocked: Boolean): Boolean = !isKeyguardLocked
```
The receiver inlines the same rule: `OcrAccessibilityService.kt:103-111`
`if (!km.isKeyguardLocked) showFloatingButton()`. The helper is used only by
`FloatingButtonRestoreTest`. So there are two copies of the policy and the KDoc
points at the wrong one.

Fix: call `shouldShowOnScreenOn(km.isKeyguardLocked)` from the receiver (one line),
or delete the helper and the test if the inline form is the intended seam. Also
correct the KDoc ("the service applies it in the SCREEN_ON receiver"). Effort: **S**.

#### B2 — P3 — first `Typeface.createFromAsset` happens on the main thread inside overlay construction
`OverlayFont.typeface` parses the selected 5.8 MB / 8.1 MB TTF on first use, and
the first call is `LineOverlayView`'s field initialiser
(`LineOverlayView.kt:31`, constructed in `addLineToResults`,
`OcrOverlayView.kt:953`) or `OcrOverlayView`'s debug label (`OcrOverlayView.kt:367`),
both on the main thread. `OverlayFont.kt:100-107` has a graceful fallback, but the
first parse janks the lookup it is part of.

Fix: warm the selected face during `OverlayEnvironment.prepare`'s IO block (call
`OverlayFont.typeface(context)` there) so the first overlay build finds it cached.
Effort: **S**.

#### B3 — P2 — the service engine-close path is the only place `OcrEngine.close()` is not pass-aware (see A1)
Tracked as A1; listed here so the lifecycle category is complete.

---

### C. Duplication, drift and design seams

#### C1 — P2 — two copies of the bigram sigmoid
`KanaSizeFix.kt:194` `private fun probBig(logit: Float)` and
`KanaSizeNcnn.kt:43` `fun probBig(logit: Float)` are the same
`1 / (1 + exp(-logit))`, one of them unused by production (`KanaSizeFix.apply`
takes a `score` lambda, so its own copy is only exercised by tests through
`KanaSizeNcnn`?). Only one is used per call path, but a model-side change (e.g.
temperature) would have to be applied twice.

Fix: keep one implementation (in `KanaSizeNcnn`, or a small `Sigmoid` helper) and
have the other call it. Effort: **S**.

#### C2 — P2 — `detect()` re-derives the `DetMask` letterbox geometry instead of reading it
`OcrEngine.kt:551-554` recomputes `imgLeft`, `imgTop`, `resScaleW`, `resScaleH`
with the same formulas the shared `DetMask` class already exposes as
`imgLeft`/`imgTop`/`scaleX`/`scaleY` (`OcrEngine.kt:344-347`), while `detectRotated`
uses the accessors (`:690-693`). The class exists precisely so the two geometry
paths "cannot drift apart in preprocessing" (`:331-333`).

Fix: in `detect()`, use `mask.imgLeft`, `mask.imgTop`, `mask.scaleX`, `mask.scaleY`.
Effort: **S**.

#### C3 — P2 — `CenteredButton`/`CenteredTextView` are the app's only custom text views and both bypass AppCompat inflation
`CenteredButton.kt:13-17` and `CenteredTextView.kt:13-17` extend the platform
`Button`/`TextView` and re-draw text in `onDraw`; both are lint errors
(`AppCompatCustomView`, F2) and both are constructed programmatically, so the lint
fix (extend `AppCompatButton`/`AppCompatTextView` and drop the `onDraw`-only
customisation in favour of a `TextView`-level gravity correction) is local. Also
note `CenteredTextView` is itself unused in production (E1).

Fix: for `CenteredButton`, extend `androidx.appcompat.widget.AppCompatButton`
(keeping `onDraw`); delete `CenteredTextView` if unused. Effort: **S**.

#### C4 — P3 — the Python bench re-implements the Kotlin normaliser rules
`tools/kana_sound_changes_bench.py:35-45` mirrors `KanaOrthography` and
`KanaSoundChanges` "including the grammatical conditions" (the file says so
itself). It reads the pair table from the asset but duplicates the conditions, so a
Kotlin rule change silently invalidates the bench numbers unless the Python is
edited too.

Fix: nothing structural is cheap; add a comment in both places naming the other
file and the last-verified date (the repo's existing provenance pattern), or export
the conditions into the asset so both read one source. Effort: **S** (comment) /
**L** (single source).

---

### D. Stale or wrong docs and comments

#### D1 — P2 — `README.md` credits the wrong OCR models and lists camera mode as unbuilt
`README.md:24-27`
```
This project utilizes the excellent OCR models from the **MeikiOCR** project:
- meiki.text.detect.v0 ...
- meiki.txt.recognition.v0 ...
```
The shipped models are PP-OCRv6 small (`CONTEXT.md:8-12`, `docs/ncnn-conversion.md`,
and the licence row `PP-OCRv6 small (PaddleOCR det+rec)`, `app/licenses/components.tsv`).
`models/archive/meiki*.onnx` are the legacy baselines the repro runbook calls
"unused by this runbook" (`docs/repro-runbook.md:225-233`). The same file's roadmap
still has `- [ ] Camera mode` (`README.md:20`) after #78 shipped it.

Fix: rewrite the Credits section around PaddleOCR/PP-OCRv6 (linking the HF model
cards already cited in `components.tsv`), keep any MeikiOCR acknowledgement only if
the archive models are still relevant, and tick the camera-mode roadmap item (or
replace the roadmap with the current gaps). Effort: **S**.

#### D2 — P2 — `docs/bundled-dictionaries.md` says the char LM is not vendored, and contradicts itself about the pitch install button
- `docs/bundled-dictionaries.md:134-141`: "Character n-gram language model — **not
  yet vendored** … **No asset is committed yet**: the model is conditional on a
  later measurement". In fact `app/src/main/assets/lm/char_lm.bin` (14 MB) is
  committed, `CharLm.load` is called by `OverlayEnvironment`, and the licence index
  carries a `char n-gram language model (char_lm.bin)` row.
- `:18` says the pitch dictionary is "Installed with the **Install Bundled Pitch
  Dictionary** button"; `:61` says "There is no install/reinstall button".
  Production follows `:61` (`MainActivity.ensureBundledPitchDictionary` has no
  button; `MainActivity.kt:621-669`).

Fix: rewrite the LM section as shipped (format, where it is loaded, what gates it),
delete the "no asset" paragraphs, and remove the button sentence at `:18`. Effort:
**S**.

#### D3 — P2 — `docs/camera-mode-facts.md` is a pre-camera survey presented as current fact
The file is a snapshot from before #57/#78 ("Read-only survey … All references are
`path:line`. Facts only.") but its statements are now false:
- `:130` "**No camera code exists.** `README.md:20` `- [ ] Camera mode`; no
  `CameraX`/`ImageCapture`/`ExifInterface` reference anywhere in `app/src/main`" —
  `ProtoCameraActivity`, `ShareImageActivity` and `ExifOrientation` all exist.
- `:79` "Everything is built programmatically inside `OcrAccessibilityService` …
  There is no extracted renderer class" — `OcrOverlayView` is that class.
- `:119` "`refreshLinesWithThreshold` (`:161`, calls `OcrEngine.reDecodeLineResult`)" —
  that path now has no production caller (A5).
- Every line number in the doc is from the monolith and no longer resolves.

Fix: either delete the file (its conclusions fed #57/#78 and the decisions are
recorded in the code and `prototype-78-camera-mode.md`) or add a dated header
saying it is a historical survey and pointing at the current files. Effort: **S**.

#### D4 — P2 — `docs/ncnn-conversion.md` describes the superseded static-bucket pipeline
The doc's current-tense claims are wrong for the shipped app:
- header/`:33-36` and `:55-58` describe "4 static buckets" `rec_w{64,128,256,480}`
  while only `rec_dyn.param/.bin` (one dynamic model, #23) ships —
  `app/src/main/assets/PP-OCRv6_small_ncnn/` has no `rec_w*` files.
- `:17` "`opt.num_threads=4`" — `RecNcnn.create` defaults to 1 and `rec_create`
  falls back to 1 (`RecNcnn.kt:82`, `ppocr_ncnn_core.cpp:84-93`).
- `:50-52` "`recognizeStreaming` `RecNcnn` `4` buckets `64..480`" and `:37`
  "`litert` + `onnxruntime` … removed for #15" in the same bullet as if present.
- `OcrEngine:508` `>640` split references (`:5, :37`) point at a long-gone line.

Fix: add a "superseded by #23/#25/#42" banner at the top and mark the rec section
historical, or trim it to the det half (which is still accurate). Effort: **S**.

#### D5 — P2 — in-code line-number cross references and two value claims are stale
| Where | Says | Actual |
|---|---|---|
| `GapDetector.kt:177-183` | `OcrEngine.kt:2158` for `alts.firstOrNull()`, `:1705-1713` for `decodeChar` | `OcrEngine.kt:2492` and `:2026-2035` |
| `LineResultGap.kt:14-16,47` | `OcrOverlayStateController.kt:366` returns null for GAP_CHAR; `OcrEngine.kt:1596`, `:2173` for `GAP_CHAR to 0f` | controller `:534`; engine `:1917`, `:1993`, `:2507` |
| `OcrEngine.kt:143` | "its exp/sum over 13193×seq post-prune" | head width is 13353 (`assets/.../rec_remap.txt`, 13,353 lines) |
| `CharLm.kt:14-17` | "header 12 bytes magic … entry count … max order" | header is 16 bytes (`tools/pack_char_lm.py:30` `<4sIII`, plus `unigramMass`; `CharLm.kt:87` `HEADER = 16`) |
| `MainActivity.kt:297` | debug help "LONG_SIDE fixed at 960 (model input)" | det input side is `DET_MODEL_SIZE = 896`; 960 is only `DEF_DET_LONG_SIDE`, clamped to `min(960, 896)` (`OcrEngine.kt:70-75, 361-362`) |
| `LicenseIndex.kt:42` | "the version goes into [LicenseEntry.textFiles] as a comma-separated list" | version goes to `version`; `textFiles` is column 4 (`:54-75`) |
| `app/licenses/maven.tsv:26-28` | "#78 PROTOTYPE: … the throwaway viewfinder. Remove with the prototype dependency." | camera mode is shipped; the dependency is required and the comment invites removing a live row |
| `docs/repro-runbook.md:34` | `git checkout wayfinder-ncnn-port` | that branch name no longer matches the workflow (master/feature branches) |

Fix: batch-edit the cross references (or drop the numbers and name the symbols, which
cannot rot), fix the two values, and reword the `maven.tsv` comment. Effort: **S**.

#### D6 — P2 — the OOV plan's status table no longer matches the code
`docs/ocr-oov-correction-plan.md:60-70` records Step 1 as shipped "with setting
'Suggest similar characters in the alternatives list' (default on)"; that setting
was removed — `OovSuggestions.kt:18-21` ("Always on … a stored
`oov_suggestions_enabled=false` from an install that predates this is inert, and
nothing consults it"), and `MainActivity` has no such row. `docs/bundled-dictionaries.md`
also still lists a `kana_size_fix_enabled` preference as if it existed, while
`KanaSizeFix.kt:13-15` says it is inert.

Fix: add a dated "status update" block at the top of the plan pointing at the
removal commits, or strike the setting clauses. Effort: **S**.

---

### E. Dead code and unused symbols

#### E1 — P2 — production-unused declarations (verified by occurrence count across `app/src/main`)
| Symbol | Location | Notes |
|---|---|---|
| `CenteredTextView` (whole class) | `CenteredTextView.kt:13` | no production use |
| `CharCandidate` + `getGlobalRect` | `SystemSupport.kt:112-161` | legacy chunk path |
| `OcrOverlayView.refreshOcrResults` | `OcrOverlayView.kt:980-999` | never called |
| `OcrOverlayStateController.navigateLines` | `OcrOverlayStateController.kt:459-488` | never called |
| `OcrOverlayStateController.calculateDisplayBoxes` | `:1000-1046` | never called; note its `fixedSize` if/else has two identical branches (`:1001-1005`) |
| `ComponentTable.allKanji` / `componentCount` | `ComponentTable.kt:55, 114` | test-only / unused |
| `OcrEngine.BOX_LEGACY` | `OcrEngine.kt:81` | never referenced |
| `OcrEngine.REC_NUM_CLASSES` | `OcrEngine.kt:177` | superseded by the derived `recNumOutputs`; the comment even explains why not to use a constant |
| `OverlayBackdrop.SCREENSHOT_ALPHA_OPAQUE` | `OverlayBackdrop.kt:55` | never referenced |
| `JpDictKeyEvent.KEYCODE_BUTTON_X/Y` | `SystemSupport.kt:232-233` | never referenced |
| `OcrEngine.getDetUnclip` / `getRecSquish` / `getXOverlap` | `OcrEngine.kt:131-137` | the engine reads the prefs directly |
| `JpDictQuad.fromRect` | `SystemSupport.kt:81-87` | test-only |
| `withGapAt` | `LineResultGap.kt:112-115` | test-only alias kept "under the name the subtask used" |

Fix: delete the fully-dead set; leave the test-only helpers only if the tests are
the contract being documented (then say so in a one-line KDoc). Effort: **S**.

#### E2 — P3 — deliberately kept diagnostics with no entry point
`KanaSizeNcnn.selfCheck` (`:53-74`) and `probeWithTrace` (`:128-204`) have no
production caller since the "Check kana size model" button was removed; the
`MainActivity` comment at `:192-198` says this is on purpose. Similarly
`tools/int8-dev/*.cpp` and `models/archive/*` are documented as non-shipping.
Recommendation: leave, but the audit flags that the only way to run them today is a
test — if that is intended, a one-line note in `docs/repro-runbook.md` would make it
true for the next reader. Effort: **S**.

#### E3 — P3 — cross-language constants tied only by comments
`kana_size_ncnn.cpp:44` `constexpr int PAIRS = 20` ("pair-table size, i.e. valid
`base` values") must equal `KanaSizeEncoder.BASE_ORDER.size`
(`KanaSizeEncoder.kt:27-30`); a Kotlin-side addition would be clamped to base 0 by
`kana_size_ncnn.cpp:117-118` (`if (base < 0 || base >= PAIRS) base = 0`) and produce
silently wrong logits. Same pattern: `REC_HEAD_K = 15` vs `OcrEngine.TOP_K = 15`
(`ppocr_ncnn_core.cpp:37,196`), which at least fails safe (size check → full-logits
fallback).

Fix: add an assertion in `KanaSizeNcnn.logits` that every `base < BASE_ORDER.size`
(cheap, fail fast), and/or a JNI-side log. Effort: **S**.

---

### F. Build, packaging and toolchain

#### F1 — P1 — the shipped `libnav_graph_core.so` is 4 KB-aligned and will not load on 16 KB-page devices
Evidence from the built APK (`unzip` + `readelf -lW`):
```
lib/arm64-v8a/libjnidispatch.so   align=0x10000
lib/arm64-v8a/libncnn_jni.so      align=0x4000
lib/arm64-v8a/libomp.so           align=0x4000
lib/arm64-v8a/libnav_graph_core.so align=0x1000   <-- 4 KB
```
`file app/src/main/jniLibs/arm64-v8a/libnav_graph_core.so` says
"built by NDK r27 (12077973), for Android 21", while the project pins NDK
`28.2.13676358` (`app/build.gradle.kts:17-23`) and builds the app's own libs with it
(those come out 0x4000). `nav_graph_core/build_nav_graph.sh` neither passes
`-C link-arg=-Wl,-z,max-page-size=16384` nor exports the pinned NDK for the cargo
linker beyond `ANDROID_NDK_HOME` discovery (`:10-44`); and
`app/build.gradle.kts`'s `buildNavGraphCore` has `isIgnoreExitValue = true`, so a
failed/absent Rust build silently ships the stale committed `.so`.

Consequence: on an arm64 Android 15/16 device with 16 KB pages, `dlopen` of this
library fails; the uniffi binding loads it through JNA
(`uniffi/nav_graph_core/nav_graph_core.kt:372-383, 718-724`), first touched by
`OcrOverlayStateController.rebuildNavGraph` (`:268-278`), which runs inside the OCR
coroutine. An `UnsatisfiedLinkError` is an `Error`, so the overlay's
`catch (e: Exception)` (`OcrOverlayView.kt:733`) does not catch it and the process
can crash. Even where it merely fails, gamepad navigation and every
`updateGlobalData` path that builds the graph are broken. Google Play has required
16 KB support for targetSdk-35 submissions since late 2025.

Fix: (a) rebuild and commit `libnav_graph_core.so` with NDK 28.2 and
`RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"` (or a `.cargo/config.toml`);
(b) add a preBuild/CI guard (e.g. a Gradle task running `llvm-readelf -l` over
`jniLibs/**/*.so` and failing when any `LOAD` align < 0x4000); (c) remove
`isIgnoreExitValue = true` or at least fail the build when the committed `.so` is
older than `src/lib.rs`. Effort: **M**.

#### F2 — P2 — `lintDebug` fails the build with 3 errors
Report: `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt` lines 44-147.
1. `OcrOverlayView.kt:2176` `Error: Call requires API level 33 (current min is 30):
   android.window.OnBackInvokedDispatcher#unregisterOnBackInvokedCallback [NewApi]`.
   The call is currently unreachable below API 33 (`backCallback` is only set by the
   SDK-guarded `registerBackCallback`, `:2153-2166`, and `unregisterBackCallback`
   returns early on null, `:2174`), so this is a lint-provable-seam issue rather
   than a live API-30 crash. Fix: add the same `Build.VERSION.SDK_INT < 33` guard
   (or `@RequiresApi(33)` on the two private helpers) so lint can see it.
2. `CenteredButton.kt:17` and `CenteredTextView.kt:17` `AppCompatCustomView`.
   Fix as C3.
Effort: **S**. This is a CI gate: the project has no baseline and lint fails hard.

#### F3 — P2 — dead/duplicated ProGuard configuration
`app/proguard-rules.pro:11-12` keeps the entire first-party package
(`-keep class com.holopengin.instantjpdict.** { *; }`) so R8's minification buys
nothing for it in the `release` build (`isMinifyEnabled = true`,
`app/build.gradle.kts:29-37`); `:2-3` keeps `ai.onnxruntime.**`, a dependency that
was removed with the LiteRT/ORT cutover (`docs/ncnn-conversion.md:50-52`,
`app/build.gradle.kts` has no ORT entry). `gradle.properties:16` still sets
`android.enableJetifier=true` although the project is AndroidX-only.

Fix: narrow the first-party keep rules to the classes that actually need reflection
(`LineResult`, the Room entities, the uniffi binding), delete the ORT rule, and drop
jetifier (it is deprecated and adds build time). Effort: **S**.

#### F4 — P2 — Rust warnings and a silent build step
`cargo check` (1 warning) and `cargo clippy` (9) over `nav_graph_core/src/lib.rs`:
- `:50` `let initial_edges = edges.clone();` — unused (the clone is dead work).
- `:133, :161` very complex 4-tuple return types (`type_complexity`).
- `:139, :167` `for j in 0..n` + index (`needless_range_loop`).
- `:203` `&mut Vec<[usize; 4]>` (`ptr_arg`).
- one manual `!Range::contains`.
Additionally `greedy_assignment(_i: usize, ...)` (`:221`) takes an unused `_i`, and
`fill_empty`/`greedy_assignment` are only exercised by unit tests? (no Rust tests in
the crate — `--all-targets` compiles none, so the nav-graph behaviour is pinned only
through the generated Kotlin and any androidTest). At minimum, delete the dead clone
and fix the loop warnings.

Fix: `cargo clippy --fix` for the mechanical four, add a `type EdgeList =
Vec<Vec<(usize, f32)>>` alias, and decide whether `initial_edges` was meant to be
returned/logged. Effort: **S**.

#### F5 — P3 — deprecation/obsolete-API warning noise
Kotlin (8, from the compile): `OcrAccessibilityService.kt:97` (`ACTION_CLOSE_SYSTEM_DIALOGS`),
`:436` (`SOFT_INPUT_ADJUST_RESIZE`), `OcrOverlayView.kt:172` (`systemUiVisibility`),
`:1899` (`scaledDensity`), `ShareImageActivity.kt:234` (`SOFT_INPUT_ADJUST_RESIZE`),
`data/AppDatabase.kt:39` (`fallbackToDestructiveMigration()` overload).
Gradle/AGP: nine `android.*` option deprecations plus obsolete `applicationVariants`
/`testVariants`/`unitTestVariants` API warnings (`gradle.properties:11-23`) and
"Configuration 'debugRuntimeClasspath' was resolved during configuration time"
(`app/build.gradle.kts` `afterEvaluate`). Lint also reports unused resources
(`res/values/colors.xml` boilerplate), 45 `UseKtx`, 73 `SetTextI18n`, 5 `DefaultLocale`
(`MainActivity.kt:316, 334`), 4 `RtlHardcoded` (`SystemSupport.kt:198-212`).

Fix: the real candidates are `fallbackToDestructiveMigration(dropAllTables = true)`
and replacing `String.format` with a locale-pinned call; the rest is optional.
Effort: **S** per item, not worth one ticket for all of it.

#### F6 — P3 — JNA `Aligned16KB` lint warning is about a library the APK does not ship
Lint flags `x86_64/libjnidispatch.so` (JNA 5.14.0) as 4 KB-aligned
(`app/build.gradle.kts:86`), but the build filters `abiFilters.add("arm64-v8a")` and
the built APK contains only `lib/arm64-v8a/libjnidispatch.so`, which **is**
16 KB-aligned (`align=0x10000`). Recorded so the warning is not chased; the real
alignment problem is F1. No action.

---

### G. Test gaps at recently changed seams

The suite is unusually strong (372 tests, 0 failures) and the new pure layers are
covered (`RotatedGeometryTest`, `ImageShareRefitTest`, `PreviewCropTest`,
`DeviceHoldTest`, `OrientationLockTest`, `OrientationManifestTest`,
`OverlayFontTest`, `CameraShortcutResourceTest`, `KanaSoundChangesTest`,
`LicenseIndexTest`). The gaps below are the ones that map to a changed seam.

#### G1 — P2 — the debug reset's "lists every control" invariant is comment-only
`MainActivity.kt:510-533` resets 7 tunables + 4 feature booleans and the comment at
`:517-523` states the rule ("a control the reset does not know about is a bug, so
the reset lists every addTunable above"). Nothing enforces it: a new `addTunable`
row (or a new feature checkbox) added above without a line in the reset silently
survives a reset. `OverlayFont.PREF_FACE` is deliberately outside (user-visible),
which is exactly the kind of exception that is easy to get wrong.

Fix: make the reset read a single list (e.g. `private val TUNABLES = listOf(...)`
used by both `addTunable` and the reset), then unit-test the list against the keys.
Effort: **S** (refactor) — an easier half step is a JVM test that reflects over the
`PREF_*` constants and asserts each appears in `resetAllTuning`.

#### G2 — P2 — the rotated detect path has no instrumentation coverage
`app/src/androidTest/.../OcrBenchmarkTest.kt` still calls `engine.detect` and the
rect overload of `recognizeStreaming` (e.g. `:155, :174, :402, :437`) and never
`detectLines`/`detectRotated`, so the opt-in path's end-to-end crop→rec congruence
(a rotated `Crop`, char boxes through `mapLocalRect`, the blob filter) is only
pinned by JVM tests on the pure geometry. The `#53` notes themselves call this out
as the risk area. The upcoming `docs/85-keystone-fit-findings.md` bench is host-only.

Fix: add one `@Test` to `OcrBenchmarkTest` that flips
`OcrEngine.setDetRotated(ctx, true)`, runs `detectLines` + `recognizeStreaming` on
the synthetic `synth/line_*_h/v` assets, asserts box count and that a rotated
`LineBox` is produced for a deliberately skewed asset; and one that asserts the
axis-aligned fixtures stay `LineBox.rect` (no quad) on the opt-in path. Effort:
**M** (device required).

#### G3 — P3 — the EXIF test pins the table against itself and the matrix order is untested
`ImageShareInputTest.kt:18-27` asserts `ExifOrientation.correction(n)` equals the
values hardcoded there; the test name says "matchTheStandardOrientationTag" but the
expected values were transcribed from the implementation, so they cannot catch a
wrong table. I checked the table against the EXIF spec and the
`setRotate → postScale(-1,1)` composition in
`ShareImageActivity.decodeOriented:966-976` / `ProtoCameraActivity.uprightCopy:1492-1500`
and it is correct for all eight values (transpose = rotate 90 CW then mirror
horizontal; transverse = rotate 270 CW then mirror horizontal; `Bitmap.createBitmap`
normalises the negative bounds). The gap is that no test renders a known 2×1 pixel
fixture through the correction and checks orientation 2/5/7 explicitly on-device.

Fix: leave the table test (it pins accidental edits) but add the pixel-level case to
an androidTest, or a JVM test using a small extracted copy of the matrix maths.
Effort: **S**.

#### G4 — P3 — no guard that the model-asset copies and pref reads stay off the main thread
A8 is exactly the class of regression a cheap test could catch: e.g. a JVM test
asserting `KanaSizeFix.apply` does not touch Android APIs, or an instrumentation
test with a `StrictMode` thread policy around `startOcr`. Not straightforward;
listed so the gap is visible rather than implied. Effort: **M**.

---

## 3. Prioritized fix plan

Dependencies are called out; "independent" means the batch can land on its own.

### Phase 0 — P0 (land first, small)

1. **A1 (S)** — defer `OcrEngine.close()` in `OcrAccessibilityService.onDestroy`
   behind the live OCR job, mirroring `ShareImageActivity.kt:462-467`. Files:
   `OcrAccessibilityService.kt` (keep the `startOcr()` return value / add
   `OcrOverlayView.awaitPass()`), optionally `OcrEngine.kt` (self-deferring close).
   Add a comment pointing at the share-activity guard so the two cannot drift again.
   Depends on: nothing. Independent.

### Phase 1 — P1 packaging blocker (needs the Rust/NDK toolchain)

2. **F1 (M)** — rebuild `app/src/main/jniLibs/arm64-v8a/libnav_graph_core.so` with
   NDK 28.2.13676358 and 16 KB alignment; add the `llvm-readelf` preBuild guard and
   stop hiding `buildNavGraphCore` failures. Files: `nav_graph_core/build_nav_graph.sh`,
   `app/build.gradle.kts` (guard task + `isIgnoreExitValue`), the committed `.so`.
   Depends on: toolchain only. Independent of everything else. Risk: the guard must
   not run on machines without `llvm-readelf` — make it skip with a warning there,
   or use `objdump -p`/AGP's own `Aligned16KB` lint if it can be pointed at
   `jniLibs` (it currently is not — see §4). Verify by unzipping a fresh APK and
   checking every `LOAD` segment ≥ 0x4000.

### Phase 2 — P2 batches that can land independently

Batch 2a — silently-broken behaviour (all S, independent):
3. **A3** — assign the pitch-import failure message (`MainActivity.kt:650-661`).
4. **A4** — install #75/#81/#44 tables unconditionally in `OverlayEnvironment`.
5. **A7** — hoist `detThresh` in `detect`/`detectRotated`.
6. **A6** — close importer streams on error.
7. **B1** — use `shouldShowOnScreenOn` in the SCREEN_ON receiver or delete it.
8. **D5** — correct the stale cross references / values / dates listed in the table
   (batch edit; no behaviour change).
9. **D6** — status-update note on `ocr-oov-correction-plan.md` and the
   `kana_size_fix_enabled` sentence.

Batch 2b — quality gates (all S, independent of 2a):
10. **F2** — add the API-33 guard in `unregisterBackCallback`; fix the two
    `AppCompatCustomView` errors (C3) by extending `AppCompatButton` and deleting
    the unused `CenteredTextView` (E1). Re-run `:app:lintDebug` until green.
11. **F3** — narrow `proguard-rules.pro`, delete the ORT rule, drop jetifier.
12. **F4** — `cargo clippy --fix` + remove `initial_edges`; decide the
    `type_complexity` alias.

Batch 2c — doc truth-up (all S, independent):
13. **D1** — README credits + roadmap.
14. **D2** — bundled-dictionaries LM/pitch sections.
15. **D3** — camera-mode-facts banner or delete.
16. **D4** — ncnn-conversion superseded banner.

Batch 2d — maintainability (mixed):
17. **C2 (S)** — `detect()` uses the `DetMask` accessors.
18. **C1 (S)** — one sigmoid.
19. **E1 (S)** — delete the production-dead declarations; classify the test-only ones.
20. **A5 (M)** — decide the blank-surfacing branch: delete the dead path (recommended,
    since the default is `DISABLED` and no UI exposes it) or unify the two decoders.
21. **A2 (M)** — make `refitComposite` abandon its commit when `rotations.turns`
    moved while it composed (re-read at commit, recycle the stale composite), and
    re-word the "every mutation happens on the main dispatcher" comment.
22. **A8 (M)** — move `KanaSizeFix.correctPage` to IO and construct engines off the
    main thread; add the asset-copy size/mtime cache.
23. **B2 (S)** — warm the selected typeface in `OverlayEnvironment`'s IO block.
24. **G1 (S)** — single source for the tunable/reset list + a test.
25. **A9 (P3, S)** — `FLAG_ACTIVITY_CLEAR_TOP|SINGLE_TOP` for the shortcut's camera
    launch.
26. **E3 (S)** — `KanaSizeNcnn` base-index assertion (cheap fail-fast).

### Phase 3 — device/bench work (needs the phone, can be batched)

27. **G2 (M)** — rotated-path instrumentation tests on the synth assets.
28. **G3 (S)** — EXIF pixel-level case on-device.
29. **D2 follow-through** — if the char LM section is rewritten, re-check the licence
    index row and `docs/licenses.md` inventory are consistent (they are today; D2 is
    a doc-only change, but the LM/`char_lm.bin` row is the fixed point to preserve).

### Ordering and dependency notes

- Phase 0 has no dependencies and is not blocked by anything else; do it before any
  other engine work so later `OcrEngine` edits are not made on top of a crash path.
- F1 is independent of the code changes but is the only *shipping-artifact* bug; if
  the device is available it is the natural first commit of the audit's fixes.
- A5's delete option touches `OcrEngine.kt`, `BlankRecovery.kt`,
  `OcrOverlayStateController.kt`, `GapDetector.kt` docs and several tests
  (`BlankRecoveryTest`, `BlankAlternativesTest`) — schedule it after A7/C2 in the
  same file to avoid churn, but it is not blocked.
- A2 and A8 both touch the share/overlay lifecycle; they do not conflict textually
  but the A2 fix should be tested against the A8 change since both alter when the
  image/engine transitions happen.
- B2 + F1 + F3 all touch packaging/startup; B2 is pure Kotlin and can land anytime.

### Risk notes

- **A1's fix must not reintroduce the bug it solves**: waiting on the job with
  `invokeOnCompletion` and closing inside the callback must not itself hold the
  engine alive past `onDestroy`'s return path; the share activity's version is the
  template and is already proven on device.
- **F1 changes a committed binary**: the rebuild must be done with the same Rust
  toolchain/uniffi version or the generated Kotlin may drift (`build_nav_graph.sh`
  step 5 regenerates the binding); check `git status` after the rebuild and commit
  the `.so` + any regenerated binding together.
- **A8's engine sharing** risks a new concurrent-use path (`OcrEngine` is not
  advertised as safe to call concurrently). Prefer moving construction off the main
  thread first, and treat sharing as a separate, benchmarked change.
- **A5 delete** removes a documented experiment (`BlankRecovery`'s measured numbers);
  keep the measurement in `docs/` before deleting the code, per the repo's habit of
  recording the negative results (as `docs/85-keystone-fit-findings.md` does).

### Quick wins (each a single small commit, no design work)

| # | Finding | One-line change |
|---|---|---|
| 1 | A3 | `tvStatus.text = result.fold(...)` for the pitch import |
| 2 | A7 | hoist `detThresh` out of the detect loops |
| 3 | A4 | un-gate the three normaliser installs |
| 4 | A6 | `use {}` around the importer streams |
| 5 | B1 | call the existing `shouldShowOnScreenOn` |
| 6 | C1 | one `probBig` |
| 7 | C2 | use the `DetMask` accessors in `detect()` |
| 8 | F2.1 | SDK guard in `unregisterBackCallback` |
| 9 | F2.2/F2.3 | `AppCompatButton` + delete `CenteredTextView` |
| 10 | F3 | drop the ORT keep rule and jetifier |
| 11 | D1 | README credits + roadmap tick |
| 12 | D5 | the seven cross-reference/value fixes |
| 13 | E1 | delete the fully-dead declarations |
| 14 | A9 | `CLEAR_TOP` on the shortcut camera launch |

---

## 4. Checked and clean

Recorded so the negative space is visible; each was read or executed, not assumed.

- **Compilation and tests**: Kotlin compiles clean apart from the 8 deprecation
  warnings in F5; `:app:testDebugUnitTest --rerun-tasks` is 372 tests / 0 failures,
  including the new #81, #82 and #84 suites. `:app:assembleDebug` succeeds.
- **Licence index**: `:app:verifyLicenseIndex` runs in `preBuild` and passed on
  every build in this audit; `LicenseIndexTest` (14 tests) asserts file existence,
  per-licence text markers, AGPL copy == repo `LICENSE`, the EDRDG/CC BY-SA
  acknowledgements and the `vert`/`vrt2` font checks. `INDEX.txt` matches
  `components.tsv`/`maven.tsv`/`rust.tsv`; the new Noto and 内閣告示 rows are present
  and their text/notice files exist and are non-empty; fonts are packaged
  **uncompressed** (`unzip -v`: `Stored` for both `.ttf`), as `noCompress += "ttf"`
  intends.
- **`libnav_graph_core` correctness** (as opposed to alignment, F1): `navigate`
  bounds-checks `idx` and `dir` (`lib.rs:66-71`); `fallback` handles `n ≤ 4` without
  division by zero (`:278-287`); `fill_empty` prevents duplicate edges (`:211-213`).
  `cargo check` is warning-clean other than F4.
- **EXIF orientation table**: verified by hand against the EXIF spec and Android's
  `Bitmap.createBitmap` semantics; all eight values and the two mirror+rotate
  compositions are correct (G3 is about the test's independence, not the code).
- **16 KB alignment**: every native library in the built APK except
  `libnav_graph_core.so` meets 16 KB (`libncnn_jni.so`, `libomp.so`,
  `libimage_processing_util_jni.so`, `libsurface_util_jni.so` = 0x4000;
  `libjnidispatch.so` arm64 = 0x10000). The lint `Aligned16KB` warning is about
  JNA's x86_64 slice, which the arm64-only APK does not ship (F6).
- **`#82` shortcut surface**: `CameraShortcutResourceTest` pins the action string
  against `CameraShortcut.ACTION_OPEN_CAMERA`, the exported-activity set
  (`.MainActivity`, `.ShareImageActivity` only) and the string-resource labels; the
  manifest's `android.app.shortcuts` meta-data is asserted. The `intent.action = null`
  one-shot clear (`MainActivity.kt:586-592`) is sound: the platform re-delivers the
  same mutated `Intent` on a re-creation, and the cold-launch `finish()` is
  deliberate.
- **`#53` rotated geometry** (pure layer): `RotatedGeometry.fitQuad`'s hull-edge
  minimum-area fit, `unclip`, `inset`, `mapLocalRect`, `tiltDeg` sign convention and
  `filterEnclosingBlobs`' three gates are pinned by `RotatedGeometryTest` (344
  lines) and `RotatedLineResultTest`; `detectRotated` keeps its `preQuads`/`quads`
  lists index-aligned and passes both to `filterFurigana`, so the AABB ruby rule
  cannot desync. The `#85` keystone findings are honest and reach a negative result.
- **`#81` kana normaliser**: the table (`variants/kana_sound_changes.txt`, 31 pairs)
  matches its PROVENANCE counts and its withheld list; composition order after
  `KanaOrthography.modernise` is as documented (`やう → よう`, `けふ → きょう`);
  `KanaSoundChangesTest` pins the asset and the conditions. The same for #75's
  `KanaOrthography` and #44's `KanjiVariants`/`JapaneseUtil` folds.
- **`#84` fonts**: the overlay face is read at point of use, defaults to the
  platform-metric-identical sans, normalises unknown stored values, caches the
  typeface behind a lock and falls back to the platform sans on a broken APK; the
  call sites in `OcrOverlayView` cover every text-bearing branch (glyphs are
  `LineOverlayView`'s own paint, not a TextView).
- **Overlay geometry consistency**: box coordinates are bitmap pixels composed at
  the container size and drawn 1:1 (`ShareImageActivity.composeForScreen` +
  `OcrOverlayView` `FIT_XY`); `ImageShareFit.refit`'s scale+offset derivation,
  degenerate-axis identity and `isIdentity` are pinned by `ImageShareRefitTest`.
  The `#78` re-fit's deliberate dropping of zoom/pan and the open panel is
  documented and coherent.
- **ncnn native guards**: the rec width is derived from the extracted tensor
  (`recClassWidth`, `ppocr_ncnn_core.cpp:54-60`) and both the Kotlin
  (`OcrEngine.kt:1108-1114`) and native sides fail closed on a width/id mismatch;
  the `#44` hardcoded-13193 bug class cannot recur silently. `det_infer`'s
  `cachedOutName` mutation is only reachable from a single detect at a time on both
  hosts today (the service guards with `overlayView`, the share activity joins its
  pass).
- **`docs/85-keystone-fit-findings.md`**: internal arithmetic and conclusions are
  consistent and the doc explicitly scopes itself as not shipped.
- **Python tooling**: all `tools/**/*.py` parse and byte-compile; the generators for
  the vendored assets document their inputs, licences and determinism, and the
  `#81` table's PROVENANCE records a SHA-256 (not re-verified against the asset
  here — the tool is the intended verifier).
- **Dirty worktree**: `git status` on `/tmp/ijpd-combined` shows only the three
  pre-existing modified `models/archive/*.onnx` LFS files, which the audit left
  alone.
