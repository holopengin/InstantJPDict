# #78 PROTOTYPE — camera viewfinder into the existing OCR activity

**Branch:** `proto/78-camera-viewfinder` (throwaway, based on `a3eca34`). Not for
main. No tests, no polish beyond runnable; delete the branch or cherry-pick the
conclusions once a real camera mode is designed.

**Ticket:** #78 — *Does a captured photo actually flow through the existing
pipeline, and does the reticle read well?*

---

## What is here

| Path | Why |
|---|---|
| `app/src/main/java/com/holopengin/instantjpdict/ProtoCameraActivity.kt` | The viewfinder: CameraX preview + capture button, hands the JPEG to `ShareImageActivity` |
| `app/src/main/java/com/holopengin/instantjpdict/ProtoCrosshairView.kt` | The reticle: one 1px horizontal and one 1px vertical line, each the full width/height of the camera view |
| `app/src/main/res/xml/proto_file_paths.xml` | FileProvider root for the captured JPEG (cache only) |
| `app/src/main/AndroidManifest.xml` | `CAMERA` permission, `ProtoCameraActivity`, the FileProvider — all marked `#78 PROTOTYPE` |
| `app/src/main/java/com/holopengin/instantjpdict/MainActivity.kt` | The "Camera (PROTOTYPE #78)" button |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | CameraX 1.4.2 (core/camera2/lifecycle/view) |
| `app/licenses/maven.tsv`, `app/src/main/assets/licenses/INDEX.txt` | One new licence row; see below |

Flow, all of it existing parts:

```
button → ProtoCameraActivity (PreviewView + ProtoCrosshairView)
       → ImageCapture → cache/proto-camera/capture-<ts>.jpg
       → FileProvider URI
       → Intent(ACTION_SEND, type image/jpeg, EXTRA_STREAM = uri) → ShareImageActivity
```

`ShareImageActivity` is started **explicitly** (same app, same task) but with
exactly the intent it already consumes — `sharedImageUri` reads
`Intent.EXTRA_STREAM` behind an `ACTION_SEND` check (`ShareImageActivity.kt:477`).
So the camera adds no second entry point, no second decode, no second renderer:
decode → EXIF → `composeForScreen` → `OcrOverlayView.startOcr()` (boxes, taps,
dictionary popups, rotate buttons, shared close semantics) is one code path.

## How to run it

```sh
export JAVA_HOME=/tmp/instantjp-sdk/jdk21
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH:$ANDROID_HOME/platform-tools"
export ANDROID_HOME=/home/holopengin/android-sdk ANDROID_SDK_ROOT=/home/holopengin/android-sdk

adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb shell pm grant com.holopengin.instantjpdict android.permission.CAMERA
# then: app → "Camera (PROTOTYPE #78)"
```

The activity requests `CAMERA` itself on first launch; the `pm grant` above is
only so it does not have to be granted by hand. The preview is portrait-locked.
An in-app status strip always names the active preview framing.

## The two artifacts the ticket asks for

### Artifact 1 — a captured photo flows through the pipeline and renders in an activity

**The handoff is verified on the device from the system log; the boxes-on-glyphs
rendering is not. See the addendum at the end of this note.**

Built and installed once (`adb install -r` → `Success`,
`lastUpdateTime=2026-09-13 23:01:11`, `CAMERA` granted). I could not drive it:
the phone was on a fingerprint lock screen with the display dozing, and the
instructions then were to stop touching the device. The maintainer used it
themselves at 23:08 — two captures, both handed to `ShareImageActivity`; that
session is recorded in the addendum below. What no one has is a picture of the
result screen: whether the boxes landed on the glyphs is still the open
question, and the reticle's hand-feel likewise.

To capture it (phone unlocked, adb up):

```sh
adb logcat -c
# app → Camera (PROTOTYPE #78) → line a text line up on the crossing point → Capture
adb exec-out screencap -p > results.png      # the recognised boxes, in the activity
adb logcat -d -s ProtoCamera                 # "captured <bytes> bytes -> <path>" + the URI handed over
```

On a **debug** build only, the captured JPEG can also be pulled off the phone:

```sh
adb shell "run-as com.holopengin.instantjpdict sh -c 'ls -l /data/data/com.holopengin.instantjpdict/cache/proto-camera/'"
adb exec-out run-as com.holopengin.instantjpdict cat cache/proto-camera/capture-<ts>.jpg > captured.jpg
```

(`run-as` needs a debuggable build — use the debug APK, not the benchmark one.)

**Cheaper way to answer the coordinate half of this question without the camera**
— same activity, same entry, known pixels: push a photo of Japanese text into
the app's cache and fire the identical intent by hand.

```sh
adb push <photo-of-japanese-text.jpg> /data/local/tmp/probe.jpg
adb shell "run-as com.holopengin.instantjpdict sh -c 'mkdir -p /data/data/com.holopengin.instantjpdict/cache/proto-camera; cat /data/local/tmp/probe.jpg > /data/data/com.holopengin.instantjpdict/cache/proto-camera/probe.jpg'"
adb shell am start -n com.holopengin.instantjpdict/.ShareImageActivity \
  -a android.intent.action.SEND -t image/jpeg \
  --eu android.intent.extra.STREAM \
  content://com.holopengin.instantjpdict.protofileprovider/proto_camera/probe.jpg
adb exec-out screencap -p > probe_results.png
```

That separates *"does the recognition path render outside the accessibility
context, with boxes on the right glyphs"* from *"does this particular camera
frame give the recogniser something to work with"*. The second only the phone
can answer.

### Artifact 2 — the 1px full-frame reticle over a live preview

**Implemented; NOT observed. Open.** `ProtoCrosshairView` draws exactly one
horizontal and one vertical 1px line across the whole camera view, crossing at
the centre — `strokeWidth = 1f` in device pixels with anti-aliasing **off** (so
"1px" is one physical pixel, not a dp-scaled line), colour `#00FFFF`, the app's
own accent. Whether that reads in the hand against real paper is the
maintainer's call, and it is untested.

```sh
# viewfinder on screen, preview live:
adb exec-out screencap -p > viewfinder.png
```

`ImgProbe` (throwaway, host-side, `/tmp/proto-tools/ImgProbe.java`) measures the
screenshot: `dims`, `cyan <minRun>` (ranks rows/columns by cyan pixel count, so
the two reticle lines and any box borders are identifiable), `bbox`, `ink`, `row`.

One thing the prototype deliberately makes comparable: **the framing**. Default
is `FIT_CENTER` — the whole captured frame visible, which on a portrait 1080x2400
screen with a 4:3 landscape sensor frame is a band across the middle, so what
the reticle lies over is what the photo will contain. A tap on the preview
switches to `FILL_CENTER`, which fills the screen like an ordinary camera app but
shows only the middle slice of the frame — the capture still holds the full
frame, i.e. text the user never saw. Both framings put the frame's centre under
the crossing point, so the comparison is about what is visible around it.

## The coordinate-space trap (the ticket's one genuinely uncertain link)

Not measured — this is what the code does, read off it after the fact:

- `ShareImageActivity` composes the OCR input at the **container's own size**
  (`container.post { surfaceWidth = container.width; surfaceHeight = container.height }`,
  `ShareImageActivity.kt:224-244`) and adds `OcrOverlayView` as `MATCH_PARENT`
  into that same container (`:254-262`).
- Inside the view, the composed bitmap goes into an `ImageView` that is
  `MATCH_PARENT` with `FIT_XY` over a bitmap of exactly that size
  (`OcrOverlayView.kt:201-206`), and the box borders and char layers are
  `MATCH_PARENT` children of `contentContainer` placed with
  `leftMargin = box.left` / `topMargin = box.top` (`:644-667`).

So bitmap-pixel coordinates and view coordinates coincide **by construction**,
whatever the content view's origin or size — the compose is derived from the
view, not the other way round. The window additionally sets
`FLAG_LAYOUT_NO_LIMITS` (`ShareImageActivity.kt:169-173`), which keeps the
content view at the display origin, so both spaces also coincide with the
display — the same equivalence the accessibility overlay relies on. The camera
path inherits all of this unchanged because it enters through the same intent.

Two things that *would* break it, worth a line in the handoff spec:

1. **A container resize after the compose.** The compose size is snapshotted
   once; `FIT_XY` then stretches the image on any later resize while the box
   margins stay in the old bitmap space. Nothing in the camera path resizes the
   window (no IME, no rotation — the activity is portrait-locked and rotation is
   handled by recomposing deliberately), but the manual-input IME path sets
   `ADJUST_RESIZE` (`ShareImageActivity.kt:145-149`), so this is the shape of the
   hazard if the results panel is ever driven from the camera path.
2. **A preview the user cannot see the edges of.** Reticle alignment is a claim
   about what will be captured; with `FILL_CENTER` the captured frame is wider
   than the preview, so a user can line a line up correctly and still get
   neighbouring text recognised.

## What was actually verified (the rest is open)

- `./gradlew :app:testDebugUnitTest` — **272 tests, 0 failures, 0 errors** (no
  tests added for prototype code).
- `./gradlew :app:assembleBenchmark` — builds; `app/build/outputs/apk/benchmark/app-benchmark.apk`,
  70,406,417 bytes, `sha256 05250b84071404494218def4c7a69e2e38ac8dd32c04305211816e4f0892f261`.
  `ProtoCameraActivity`, `ProtoCrosshairView` and the `protofileprovider`
  authority are present in `classes.dex` (checked by extracting the APK, not by
  trusting the build).
- Licence-index guard: `:app:generateLicenseIndex` then `:app:verifyLicenseIndex`
  (separate invocations) both pass. **One hand-written row was needed** —
  `com.google.auto.value:auto-value-annotations`, a non-androidx transitive of
  `androidx.camera:camera-core`, which the `androidx.*` glob does not cover. The
  guard failed the build with exactly that module named, which is the guard
  working as designed; the row cites the grandparent POM
  (`com.google.auto:auto-parent:7`), the same pattern the `listenablefuture` row
  uses.
- Device: installed once (`adb install -r` → `Success`; provider and `CAMERA`
  present in `dumpsys package`). That build predates the preview-framing toggle.
  Nothing was driven.

## Decisions taken (each is a one-liner to change)

- **Reused `ACTION_SEND` + `EXTRA_STREAM` with a FileProvider URI** rather than
  adding a second entry to `ShareImageActivity`. The activity already consumes
  exactly that, and a second entry (a bitmap in a companion object, or an
  in-process binder call) would be a new seam to keep aligned. The cost is one
  manifest provider and one paths XML, both throwaway.
- **CameraX 1.4.2**, `PreviewView` in `COMPATIBLE` (TextureView) mode: no reason
  to take the `SurfaceView` path in a prototype whose whole point is drawing
  over the preview.
- **Portrait-locked**, so the reticle judgement happens in one orientation.
- **`CAPTURE_MODE_MINIMIZE_LATENCY`**, no flash, no tap-to-focus, back camera.
- Captures are **left on disk** in `cache/proto-camera/` (never cleaned) purely
  so a build can be pulled apart for evidence.
- A `#78 PROTOTYPE` marker on every touched production file, so `git grep`
  finds all of it when the branch is dropped.

---

## Addendum — the maintainer's own session, 2026-09-13 23:08 JST

Found while packing up: the app's cache held two captures, and the device's
system log held the rest of the story. This is the first device evidence, and
it is evidence about the *handoff*, not about how anything looked.

**The sequence, from `adb logcat -d -b system`:**

```
23:08:16.377 I/ActivityTaskManager: START u0 {xflg=0x4 cmp=.../.ProtoCameraActivity}
23:08:26.419 I/ActivityTaskManager: START u0 {act=android.intent.action.SEND typ=image/jpeg
             flg=0x1 xflg=0x4 cmp=.../.ShareImageActivity clip={image/jpeg {U(content)}}
             (has extras)} with LAUNCH_MULTIPLE from uid 11186 (com.holopengin.instantjpdict)
23:08:42.540 I/ActivityTaskManager: START u0 {xflg=0x4 cmp=.../.ProtoCameraActivity}
23:08:54.446 I/ActivityTaskManager: START u0 {act=android.intent.action.SEND ... }   # as above
23:09:28.449 D/CoreBackPreview: startBackNavigation ... topRunningActivity=.../.ShareImageActivity
```

**What that establishes:**

- The `ACTION_SEND` + `image/jpeg` + **content URI** + `FLAG_GRANT_READ_URI_PERMISSION`
  (`flg=0x1`) start comes from `uid 11186` — the app itself — and targets
  `ShareImageActivity` explicitly. That is the prototype's capture handoff,
  unmodified, and it happened twice. The `clip={image/jpeg {U(content)}}` is the
  URI the OS carried; a `file://` path would not read that way.
- Both captures are real camera files: `cache/proto-camera/capture-1789308506138.jpg`
  (1,487,457 bytes) and `capture-1789308534266.jpg` (1,502,426 bytes), pulled off
  the phone — 4032×3024 JPEG, **EXIF orientation 6** on both. So the photo was
  stored sideways with the turn in EXIF, and `ShareImageActivity`'s EXIF branch
  (`ExifOrientation.correction`, the part written for exactly this and never
  needed by the screenshot path) was exercised for real.
- The results activity did not take its failure exit. It was the top, visible
  activity from the send until the back press at 23:09:28 — ~16 s on the first
  capture and ~34 s on the second. `ShareImageActivity` finishes immediately with
  "Could not read image" if the decode or the compose fails, so the decode,
  the EXIF correction, `composeForScreen` and the `OcrOverlayView` construction
  all ran; it left the screen the way the shared close semantics intend.
- What the photos were pointed at: a wall-mounted Panasonic bidet control panel,
  full of short Japanese labels — i.e. the maintainer reached for a real
  Japanese-text object, not a test image.

**Geometry arithmetic for these two captures** (derived, not measured — the
decode path is `ShareImageActivity.composeForScreen` + `ImageRotation.fitRotated`):

| | |
|---|---|
| stored pixels | 4032 × 3024, EXIF orientation 6 |
| after EXIF correction | 3024 × 4032 (portrait) |
| composed into the 1080 × 2400 container | `scale = min(1080/3024, 2400/4032) = 0.357` → 1079 × 1440 |
| on screen | a portrait panel spanning y ≈ 480…1920, with 480 px of black above and below |
| glyph size in the OCR input | ~36% of the captured scale |

That last row is the one worth a look when the results screen is finally
pictured: the accessibility path feeds the detector a 1:1 screenshot, while the
camera path hands it a photo whose text has been shrunk to about a third before
the detector ever sees it. Whether the labels on that panel were still readable
to the detector is exactly the kind of thing only the screen can say.

**What it does not establish:** whether the boxes landed on the glyphs, how the
reticle read in the hand, or whether any text was recognised. No screenshot of
that session exists (the newest file in `/sdcard/Pictures/Screenshots/` is
22:26) and the app's own log lines had already rotated out of `logcat` by the
time I looked. Both remain the maintainer's call.

The build exercised was the **debug** APK installed at 23:01 — everything in
this branch except the preview-framing toggle, whose default framing
(`FIT_CENTER`) is what it shipped with anyway. The benchmark APK below has the
toggle.

## Artifacts

| | |
|---|---|
| `app/build/outputs/apk/benchmark/app-benchmark.apk` (in the worktree `/tmp/ijpd-camera`) | 70,406,417 bytes, `sha256 05250b84071404494218def4c7a69e2e38ac8dd32c04305211816e4f0892f261`, `md5 0470526766fd836201890bdf5b38d64b` |
| copy on the phone | `/sdcard/Download/IJPD-6ee833f-proto78-camera.apk`, same bytes (md5 matched on device) |
| debug APK (debuggable — needed for `run-as`) | `app/build/outputs/apk/debug/app-debug.apk`, 75,765,987 bytes |
| evidence kept off the repo | `/tmp/proto-evidence/` — the maintainer's two pulled captures, the screenshots taken while blocked by the lock screen, and the system-log extracts |

Both APKs are signed with the Android debug key
(`a59d1005…` SHA-256) — the same key as the build already on the phone, so
`adb install -r` replaces it without an uninstall.


