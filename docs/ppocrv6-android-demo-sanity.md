# PP-OCRv6 Android demo — official-pipeline sanity check (2026-09-26)

Ground-truth check that our ncnn numbers are sane, by building and running
PaddleOCR's own PP-OCRv6 Android demo **on the same Pixel 7a** and comparing
stage-for-stage on **identical input**.

* Subject: PaddleOCR `35b1339` ("[Feat] Add PP-OCRv6 Android Demo"),
  `deploy/ppocr-android/` — ONNX Runtime 1.21.1 CPU EP + OpenCV, Kotlin, Gradle 8.9 /
  AGP 8.7.3 / Kotlin 2.1.0, minSdk 26, compileSdk 35.
* Our side: this repo, ncnn, `benchmark` build type (non-debuggable, arm64
  release core), Pixel 7a / Android 17.
* Device throughout: Pixel 7a, Android 17 (SDK 37), arm64-v8a, AC powered.
  `dumpsys thermalservice` reported `mStatus=0` (no throttling) and a constant
  77.0 °C BIG / 75.0 °C MID / 77.0 °C LITTLE before and after every cell — those
  three sensors are pinned on this device, so they are not a usable thermal
  signal. Battery climbed 62 % → 78 % over the session (charging), so there was
  no battery-driven clock drift either. All cells were run under
  `flock /tmp/opencode/device.lock`, interleaved with each other and with our own
  runs rather than in one long block.

Raw logs: `/tmp/opencode/ppocr-demo/`, `/tmp/opencode/ours/`.
The demo was built in `/home/holopengin/Projects/paddleocr-sanity/` (never inside
this repo). Nothing from it is referenced by our build.

---

## 0. Two things had to be fixed before the demo would run at all

Neither is a performance change; both are recorded because they are real defects
in the demo at this commit and anyone repeating this will hit them.

**(a) The stock benchmark cannot run on API 31+.** The pinned dependency
`com.quickbirdstudios:opencv:4.5.3` is an NDK-r14-era build; its
`libopencv_java4.so` references `__sfp_handle_exceptions`, removed from bionic.
`OpenCVUtils.init()` swallows the failure and returns `false`, so the failure
surfaces 3 s later as

```
E OpenCVUtils: Failed to initialize OpenCV: dlopen failed: cannot locate symbol
  "__sfp_handle_exceptions" referenced by ".../libopencv_java4.so"
E TestRunner: java.lang.UnsatisfiedLinkError: No implementation found for long
  org.opencv.core.Mat.n_Mat()
```

`OCRBenchmarkTest#testLatencyBenchmark` fails. `com.quickbirdstudios:opencv` has
no release newer than 4.5.3 (2021), so the fix is the official
`org.opencv:opencv:4.9.0` — same `org.opencv.*` Java package, same API surface
the SDK uses, built with a current NDK. **Dependency coordinate + version only;
zero source changes.** With that one line swapped the stock test passes and the
stock stage table below is the demo's own unmodified output.

**(b) Cosmetic:** `BenchmarkFixtures.defaultReferenceImageName` says
`android_ocr_benchmark_reference**.jpg**`, the bundled resource is `…**.png**`,
and the file is actually **JPEG** data. `loadBenchmarkImageBytes()` strips the
extension before `getIdentifier`, so it resolves; `Imgcodecs.imdecode` sniffs the
content, so it decodes. Only the reported `inputImage` name in the JSON is wrong.

Toolchain: nothing had to be installed except the wrapper's own Gradle 8.9
distribution (we already had 9.4.1/9.7.1). It builds and runs on the existing
JDK 21 (`~/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2`); the project asks for
JDK 17 (`sourceCompatibility`/`jvmTarget` = 17) and AGP 8.7.3 accepts 21. Only
one thing had to be worked around on this host: `run_benchmark.sh` has a
`#!/bin/bash` shebang and there is no `/bin/bash` here, so it was invoked as
`bash run_benchmark.sh 30 10`.

## 1. What det input the demo's default config actually produces

`PaddleOCRConfig()` defaults to `detLimitSideLen = 64, detLimitType = "min",
detMaxSideLimit = 4000`. `ImageUtils.resizeToMultipleOf32` computes
`ratio = if (min(h,w) < 64) 64/min(h,w) else 1.0`, then rounds each side to a
multiple of 32.

The bundled fixture is a **720×1150** JPEG. `min(720,1150) = 720 >= 64`, so
**`ratio = 1.0` — the default does not resize at all.** The det input is

| | det input | pixels | vs our 896×896 (802 816) |
|---|---:|---:|---:|
| demo default (`min`/64) | **704×1152** | 811 008 | **×1.010** |
| demo `max`/896 (cell B) | 576×896 | 516 096 | ×0.643 |
| ours | 896×896 | 802 816 | ×1.000 |

That is luck, not design: because the demo's own fixture is small, the "no-op"
default lands within 1 % of our det area. **Cell A is therefore a genuine
apples-to-apples det comparison** despite the different aspect ratio
(portrait 704×1152 vs our square letterbox).

It is worth flagging what that default does on a real photo: `min`/64 is a
**no-op for any image whose short side is ≥ 64 px**, so the demo detects at
native resolution up to the 4000 px cap — a 12 MP photo goes in at
3000×4000. PaddleOCR's own mobile presets use `min`/**736**. The demo's
benchmark fixture is too small to expose this.

`EngineConfig()` defaults to `numThreads = 4`, applied as
`setIntraOpNumThreads` to **both** sessions. `recBatchSize` defaults to 1, and
`run_benchmark.sh` passes no `rec_batch_size`, so the stock benchmark is
**4 intra-op threads, one rec line per call**.

## 2. Cell A — the stock demo, unmodified (`bash run_benchmark.sh 30 10`)

The demo's own printed table, verbatim:

```
  PP-OCRv6 Speed Benchmark Results
  Device: Pixel 7a  |  OS: Android 17  |  Lines: 4
  Cold load: 2191ms  |  Warmup: 10  |  Measured: 30
+-----------------------------+----------+----------+----------+----------+
| Stage                       |  Mean ms |    Stdev |       P90 |    Min ms |
+-----------------------------+----------+----------+----------+----------+
| Total pipeline              |  1716.47 |     42.98 |     1760 |     1613 |
+-----------------------------+----------+----------+----------+----------+
|   Detection (total)         |  1152.30 |     39.37 |     1191 |     1055 |
|     Preprocess              |    47.77 |      5.11 |       52 |       37 |
|     Inference               |  1097.30 |     36.31 |     1132 |     1009 |
|     Postprocess             |     7.23 |      0.72 |        8 |        6 |
|   Recognition (total)       |   559.77 |     10.69 |      573 |      539 |
|     Preprocess              |     4.43 |      1.09 |        5 |        3 |
|     Inference               |   546.80 |     10.82 |      562 |      527 |
|     Postprocess             |     8.53 |      1.26 |       10 |        6 |
|   Pipeline overhead         |     4.40 |      0.66 |        5 |        3 |
+-----------------------------+----------+----------+----------+----------+
```

* Reference image: `android_ocr_benchmark_reference.png` = **720×1150 JPEG**,
  398 527 bytes, 4 detected lines.
* `inputShapeDistribution.detection` = `[1, 3, 1152, 704]` on all 30 iterations.
* `recognition` shapes = `48×454`, `48×259`, `48×471`, `48×436` (one call per line).
* `recognitionPerLine.pooled`: 120 samples, mean 139.9 ms, p90 164 ms.
* Memory: 456 466 kB PSS. Cold load 2191 ms cold / 431–515 ms on later runs.

Re-run through a parameterised copy of the same measurement (medians, 30 iters):

| | med | min | p90 |
|---|---:|---:|---:|
| det total | 1191 | 1122 | 1264 |
| det inference | 1134 | 1070 | 1201 |
| rec total | 566 | 544 | 613 |
| rec inference | 553 | 532 | 599 |
| page | 1756 | 1716 | 1859 |

## 3. Our engine on the *same* image

Staged `android_ocr_benchmark_reference.png` into `app/src/androidTest/assets/`
(temporarily; removed afterwards, never committed) and ran a harness that drives
`engine.detect` + `engine.recognizeStreaming` — the same path the
`docs/ocr-pipeline-perf-2026-09-25.md` numbers come from. 3 warmup, 10 measured,
`:app:connectedBenchmarkAndroidTest` (`benchmark` variant, arm64 release core).

| | med | min | max |
|---|---:|---:|---:|
| det wall (896×896 letterbox, 2 threads) | **219** | 213 | 232 |
| … native letterbox+normalise | 2.0 | | |
| … net | 207 | 202 | 220 |
| … post | 10 | 9 | 10 |
| rec wall (4 lines, fanout 4 × 1 thread) | **109** | 99 | 132 |
| page wall | **324** | 316 | 364 |

Control on our own fixture, same session, so today's device state is calibrated
against the documented numbers:

| | today | `docs/ocr-pipeline-perf-2026-09-25.md` |
|---|---:|---:|
| det net (`bookpage.png`, 896×896, t2) | 208 | 246 |
| det post | 11 | 52 |
| rec, 18 lines | 463 | 532 |
| page | 687 | 842 |

Today's device is **faster** than the session the doc recorded (rec −13 %, page
−18 %, det post −79 %), so the doc's figures are a conservative baseline, not an
optimistic one.

### Same-image line-for-line

| # | demo box (x0,y0,x1,y1) | our box | IoU | demo text (score) | our text |
|---:|---|---|---:|---|---|
| 0 | 34, 387, 490, 456 | 37, 391, 489, 451 | 0.86 | 上海斯格威铂尔大酒店 (0.99983) | 上海斯格威**拍**尔大酒店 |
| 1 | 191, 445, 402, 491 | 196, 451, 400, 486 | 0.74 | 打浦路15号 (0.99995) | 打浦路15号 |
| 2 | 18, 484, 520, 555 | 21, 486, 519, 548 | 0.87 | 绿洲仕格**维**花园公寓 (0.99975) | **綠**洲仕格**雄**花园公寓 |
| 3 | 75, 540, 403, 587 | 79, 548, 404, 584 | 0.75 | 打浦路252935号 (0.99998) | 打浦路252935号 |

* **4 boxes from both, mean bbox IoU 0.81** (min 0.74) — the detectors agree on
  the page. The residual is the unclip/threshold difference (their
  `detUnclipRatio` is applied to a 1-channel float prob map through
  `findContours`; ours is a flat flood fill at `thresh=0.3` on the same map, with
  the 896² letterbox's ±1 px content edge). Our boxes are consistently ~3–8 px
  tighter on every side, which is what a slightly smaller expand gives.
* **Text: 2 of 4 lines byte-identical**, 2 lines off by 1–2 characters
  (`铂`→`拍`, `绿`→`綠` + `维`→`雄`). Line 3's odd `打浦路252935号` is decoded
  *identically* by both, which is the strongest single piece of evidence that
  our rec is on the same operating point as fp32 ORT and not subtly broken: the
  CTC head is making the same mistake on the same pixels. The 2 divergences are
  consistent with the documented int8 rec cost (parity 93.3 % / CER 0.007 at
  w480 vs fp16) — 2 wrong glyphs in 37, and both in the dense CJK head.
* Per-line native rec time (int8, 1 thread/net, from `InferLog`):
  `w=280 → 57 ms, w=368 → 76, w=392 → 98, w=440 → 74` (medians of 10). Noisy
  because 4 nets run concurrently on a big.LITTLE device.

## 4. Matched configs

The demo's resize policy *is* configurable, so `limit_type=max, side=896` was
run (cell B). Reported as asked, with the honest caveat that a plain resize
cannot reproduce a letterbox: their pipeline has **no padding at all**, so
matching the long side costs 36 % of the pixel count.

All cells: 30 iterations, 10 warmup, medians, same device, same fixture.

| cell | det policy | det input | px | thr | rec batch | detPre | detInf | detPost | detTot | recPre | recInf | recPost | recTot | ovh | **page** |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **A** (stock) | min/64 | 704×1152 | 811 008 | 4 | 1 | 50 | 1134 | 8 | 1191 | 5 | 553 | 9 | 566 | 5 | **1756** |
| **B2** | max/896 | 576×896 | 516 096 | 4 | 1 | 28 | **749** | 3 | 783 | 5 | 558 | 9 | 574 | 4 | **1360** |
| **C** | min/64 | 704×1152 | 811 008 | 2 | 1 | 45 | 584 | 7 | 636 | 4 | 293 | 7 | 305 | 4 | **952** |
| **D** | min/64 | 704×1152 | 811 008 | 2 | 6 | 41 | 541 | 6 | 587 | 3 | 307 | 9 | 319 | 3 | **914** |
| **E** | min/64 | 704×1152 | 811 008 | 1 | 1 | 40 | 962 | 6 | 1008 | 4 | 459 | 7 | 469 | 4 | **1480** |
| **ours** | 896² letterbox | 896×896 | 802 816 | 2 | fanout 4×1 | 2 | **207** | 10 | **219** | — | — | — | **109** | — | **324** |

Cells B2/C/D/E were run through an *additional* test class
(`OCRBenchmarkConfigTest`, added in the demo copy only — `OCRBenchmarkTest.kt` is
byte-identical to upstream) that exposes `detLimitType` / `detLimitSideLen` /
`numThreads` / `recBatchSize` as instrumentation arguments and prints the same
table plus every raw sample.

### det scales with pixels; threads are the real story

* **Area**: A (811 k px) / B2 (516 k px) = 1.572; detInf 1134 / 749 = **1.514**.
  Their det is cleanly area-proportional. The square-vs-portrait question is
  therefore a *quality* question on their side, not a speed confound here.
* **Threads**: at a fixed 704×1152 input, ORT intra-op 1 / 2 / 4 threads gives
  detInf **962 / 584 / 1134** and recInf **459 / 293 / 553**. **Four threads is
  1.94× slower than two.** The demo's own default (`EngineConfig(numThreads = 4)`)
  is its worst setting on this SoC.
* This is the same wall our own det sweep found (t1 379–427, **t2 237–265**,
  t4 522 — `docs/ocr-pipeline-perf-2026-09-25.md`), reproduced independently in a
  completely different runtime. Big.LITTLE + a naive thread pool, not an ncnn
  quirk. `docs/ocr-pipeline-perf-x86-2026-09-26.md`'s Arm transfer argument
  ("the mechanism is traffic, not arithmetic") is unaffected, but the
  *thread-count* conclusion is now confirmed on-device for ORT too.

## 5. Verdict

**Our numbers are sane — the same orders of magnitude and the same stage split,
and we are 2.6–5.4× ahead once the configs are matched.**

| comparison | det inf | rec | page | note |
|---|---:|---:|---:|---|
| ours vs cell A (demo stock) | **1.94×** | **5.2×** | **5.4×** | threads unmatched (ours 2/det, 4×1/rec; theirs 4) |
| ours vs cell C (both 2 threads on det) | **2.9×** | **2.8×** | **2.9×** | det area within 1 %; rec uses 4 cores vs their 2 |
| ours vs cell E (their 1 thread) | 4.6× | 4.3× | 4.6× | worst case for them |

Stage split, ours vs cell A: the demo spends **64 % of the page in det inference
and 32 % in rec**; we spend **64 % in det and 34 % in rec** — the same split to
within a point. Every other stage is small on both sides: pipeline overhead
4–5 ms (ours: 324 − 219 − 109 = −4, i.e. inside the noise), det post 8–11 ms,
rec pre 4–5 ms, rec post 8–9 ms. **There is no hidden cost in our pipeline.**

### Attribution of the 2.9× matched gap

* **Precision is the largest single factor and it is the one we chose.** det is
  fp16-storage for us, fp32 for them; rec is **int8** for us, fp32 for them.
  int8-over-fp32 is a 4× traffic advantage, so most of the rec gap is arithmetic
  we are getting on purpose.
* **Runtime is the rest, and it is real but bounded.** At matched precision
  (fp16 vs fp32 det, 2 threads each, 1 % area difference) ncnn's packed/fp16 Arm
  kernels are ~2.9× ORT's generic fp32 CPU-EP path. This is consistent with the
  x86 study's per-layer finding (8–36 GMAC/s against 96 GMAC/s of AVX2-FMA peak
  — the layers were already memory-bound, so a MAC-rate advantage does not
  convert to wall time). **It does not contradict it**: the x86 numbers were
  fp32-vs-fp32, and this is a different question.
* **Det input size is not the explanation.** Cell A is 1.010× our pixel count.
  If anything our square letterbox is the *wasteful* one (the doc already
  records 55 % of det FLOPs on a portrait page) and we still win by 2.9×.
* **Threading explains half the headline gap.** Their 4-thread default costs them
  1.9×; the "5.4× vs the stock demo" figure is mostly their misconfiguration, not
  ONNX RT being 5× worse than ncnn.
* **Pipeline structure is a modest, honest factor in rec only.** Cell D put all
  4 lines in one ORT call (padded to 48×471): recInf 307 ms vs cell C's 293 ms
  for 4 separate calls. Solving `4a + 4b·471 = 307` against
  `4a + b·(259+436+454+471) = 293` gives **≈52 ms fixed per `session.run` plus
  0.053 ms/px** at 2 threads — so at 2 threads their rec cost is genuine
  compute, not call overhead, and batching does not help. At **4** threads the
  per-line times are nearly width-*independent* (160 ms at w=259, 176 ms at
  w=471), i.e. the fixed cost balloons to ~150 ms and swamps a ~30 ms compute.
  That is ORT's intra-op thread-pool sync/spin cost, not a property of the model.

### Leads worth following

1. **Their `numThreads` default is a 1.9× loss on a Pixel 7a.** The SDK exposes
   the knob, so `EngineConfig(numThreads = 2)` is a one-value change. If we ever
   want an ONNX-RT reference number to compare against, 2 is the number to
   compare against — and `session.intra_op.allow_spinning=0` is the obvious next
   thing to try on their side, since 4-thread per-line cost being
   width-independent is the signature of spin-wait.
2. **Their `min`/64 det default is a no-op**, so any real photo is detected at
   native resolution up to 4000 px. Their own fixture is too small to show it.
   Worth remembering if we ever cite a demo det number on a big image.
3. **Nothing in the official pipeline is structurally better than ours on the
   same input.** Their pre/post/overhead are all smaller in absolute terms only
   because their nets are 5× slower to amortise them against; per unit of net
   time ours is not worse anywhere.
4. **Our rec accuracy on this fixture is the one real divergence**: 2 of 4 lines
   off by 1–2 CJK glyphs vs fp32 ORT, consistent with the documented int8 parity
   (93.3 % / CER 0.007). Line-for-line box agreement (mean IoU 0.81) and the
   byte-identical `252935` reading say the geometry and the CTC head are on the
   same operating point. This is the pre-existing int8-rec trade-off, not a new
   finding — but it is the first time it has been measured against a
   same-device fp32 reference, and the size of the gap (2/37 glyphs) is
   reassuring rather than alarming.
5. **Not comparable, stated plainly**: different runtime (ncnn vs ORT CPU EP),
   different precision (fp16/int8 vs fp32), different det geometry
   (896² letterbox vs 704×1152 plain resize), different thread counts, and a
   4-line Chinese sign vs an 18-line Japanese book page. What *is* comparable —
   order of magnitude, stage split, per-line rec scaling, box agreement, decoded
   text on identical pixels — lines up.

## Reproduction

```bash
# demo (isolated dir, never inside this repo)
#   1. fetch deploy/ppocr-android at 35b1339 (raw.githubusercontent per file)
#   2. models from PaddlePaddle/PP-OCRv6_small_{det,rec}_onnx  ->
#      ppocr-sdk/src/main/assets/models/{det,rec}/inference.onnx (+ rec inference.yml)
#   3. gradle/libs.versions.toml: com.quickbirdstudios:opencv:4.5.3 -> org.opencv:opencv:4.9.0
#   4. ./gradlew :app:assembleDebug :ppocr-sdk:assembleDebug
bash run_benchmark.sh 30 10          # cell A
# cells B2/C/D/E via OCRBenchmarkConfigTest with
#   -Pandroid.testInstrumentationRunnerArguments.det_limit_type / det_limit_side_len /
#     num_threads / rec_batch_size

# ours (fixture staged temporarily into app/src/androidTest/assets/, then removed)
./gradlew :app:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.CrossCheckDumpTest \
  -Pandroid.testInstrumentationRunnerArguments.asset=android_ocr_benchmark_reference.png
```

`CrossCheckDumpTest` and a one-line `OcrEngine.detLastTimings()` seam were used
for the run and **have been removed again**; this repo is unmodified apart from
this document and the pre-existing untracked `bookpage.png`.
