# #78 — camera mode: a viewfinder into the existing OCR activity

**Branch:** `proto/78-camera-viewfinder` (based on `a3eca34`), merging to master.
This is the app's camera mode, not a scratch note: the pure layers it added are
unit-tested (six test files of its own, see "What was actually verified") and what
follows is written to be read by whoever touches this code next.

**Ticket:** #78 — *Does a captured photo actually flow through the existing
pipeline, and does the reticle read well?*

---

## What is here

| Path | Why |
|---|---|
| `app/src/main/java/com/holopengin/instantjpdict/ProtoCameraActivity.kt` | The viewfinder: CameraX preview + shutter, hands the JPEG to `ShareImageActivity` |
| `app/src/main/java/com/holopengin/instantjpdict/ProtoCrosshairView.kt` | The reticle: two 1px horizontal and two 1px vertical lines, each the full width/height of the camera view, straddling the centre by `CROSSHAIR_GAP_FRACTION` |
| `app/src/main/java/com/holopengin/instantjpdict/DeviceHold.kt` | The two hold vocabularies: the sensor's (what the capture stream is told) and the window's (what the control anchors are decided from), plus the rule that keeps a flat phone from turning either |
| `app/src/main/java/com/holopengin/instantjpdict/OrientationLock.kt` | The lock: the one switch that freezes the window and the stream together, with the lock control's icon/description/placement |
| `app/src/main/java/com/holopengin/instantjpdict/PreviewCrop.kt` | The FILL_CENTER crop geometry, in plain Kotlin so it is unit-tested |
| `app/src/main/res/xml/proto_file_paths.xml` | FileProvider root for the captured JPEG (cache only) |
| `app/src/main/AndroidManifest.xml` | `CAMERA` permission, `ProtoCameraActivity`, the FileProvider |
| `app/src/main/java/com/holopengin/instantjpdict/MainActivity.kt` | The "Camera" button, pinned above the navigation bar |
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
`Intent.EXTRA_STREAM` behind an `ACTION_SEND` check (`ShareImageActivity.kt:910-912`).
So the camera adds no second entry point, no second decode, no second renderer:
decode → EXIF → `composeForScreen` → `OcrOverlayView.startOcr()` (boxes, taps,
dictionary popups, rotate buttons, shared close semantics) is one code path.

In FILL_CENTER the handoff also writes a cropped copy beside the capture
(`capture-<ts>-zoom.jpg`) — what the recogniser is fed and what was on screen.
Both files are left in `cache/proto-camera/` for evidence, bounded to the newest
`CAPTURE_KEEP` (6) by `pruneCaptures`.

## The camera's orientation, in three notions

Written out because this is where the branch's reports came from, and because
the three are deliberately not one value (`DeviceHold.kt`, `OrientationLock.kt`):

- **The STREAM follows the sensor's HOLD.** The activity declares `fullSensor` +
  `configChanges`, so the whole window turns with the phone and the bound use
  cases are told the rotation from `OrientationEventListener`; CameraX 1.4.2 does
  not follow a display-rotation change on its own for an already-bound use case.
  A reading that falls in one of `DeviceHold.surfaceRotationFor`'s bands turns
  the stream; the sensor's `ORIENTATION_UNKNOWN` (the phone flat, or being moved)
  is not a hold, so the stream keeps the rotation it has
  (`DeviceHold.streamRotationFor`). Without that rule a phone tipped flat in a
  landscape window told the capture portrait while the window stayed landscape —
  a quarter turn out from its own window.
- **The CONTROLS follow the WINDOW.** The anchors are decided from
  `Configuration.orientation` (`applyControlAnchors`), re-applied from
  `onConfigurationChanged` — not from the sensor, whose report of a new hold
  arrives before the platform has played the rotation animation (which is the
  "buttons jump weirdly" report), and not for a flat phone, which moves nothing.
- **The LOCK freezes both halves together** (`OrientationLock`): the window is
  asked for `SCREEN_ORIENTATION_LOCKED` ("the rotation already in effect") and
  the stream is frozen at the window's rotation. Freezing only one of them is the
  failure the `fullSensor` decision exists to avoid.

One consequence to know about, and a live decision rather than a settled one:
`fullSensor` is used even when the user has locked sensor-based rotation, so both
this activity's window and the exported `ACTION_SEND` entry point turn with the
sensor on the share path too.

## How to run it

```sh
export JAVA_HOME=/tmp/instantjp-sdk/jdk21
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH:$ANDROID_HOME/platform-tools"
export ANDROID_HOME=/home/holopengin/android-sdk ANDROID_SDK_ROOT=/home/holopengin/android-sdk

adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb shell pm grant com.holopengin.instantjpdict android.permission.CAMERA
# then: app → "Camera"
```

The activity requests `CAMERA` itself on first launch; the `pm grant` above is
only so it does not have to be granted by hand. The window follows the phone
(`fullSensor` + `configChanges`), so the preview, the reticle and the framing
turn with it. There is no on-screen status strip: the state readout is logcat
(`ProtoCamera` — "viewfinder state: targetRotation …", the lock's own line, the
sensor's "device turned …" beside the window's "window turned …"), because the
readout is diagnostic and the preview is what is being judged.

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
# app → Camera → line a text line up on the crossing point → Capture
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

### Artifact 2 — the reticle over a live preview

**Implemented; NOT observed. Open.** `ProtoCrosshairView` draws two horizontal
and two vertical 1px lines across the whole camera view, straddling the centre —
`strokeWidth = 1f` in device pixels with anti-aliasing **off** (so "1px" is one
physical pixel, not a dp-scaled line), colour `#00FFFF`, the app's own accent.
Two lines per axis rather than one, because a single line cannot show whether a
long line is parallel to it; the gap between a pair is the tuning slider's
(`PREF_GAP`, re-read on resume). Whether that reads in the hand against real
paper is the maintainer's call, and it is untested.

```sh
# viewfinder on screen, preview live:
adb exec-out screencap -p > viewfinder.png
```

`ImgProbe` (host-side, `/tmp/proto-tools/ImgProbe.java`) measures the screenshot:
`dims`, `cyan <minRun>` (ranks rows/columns by cyan pixel count, so the reticle
lines and any box borders are identifiable), `bbox`, `ink`.

One thing the viewfinder deliberately makes comparable: **the framing**. Default
is `FIT_CENTER` — the whole captured frame visible, which on a portrait 1080x2400
screen with a 4:3 landscape sensor frame is a band across the middle, so what the
reticle lies over is what the photo will contain. The framing control (the
magnifier chrome button in the corner) switches to `FILL_CENTER`, which fills the
screen like an ordinary camera app by showing only the middle slice of the frame
— and the photo handed to the model is cropped to that same slice
(`cropToPreviewFraming`), so the recogniser reads what was on screen and not text
the user never saw. A tap on the preview refocuses instead, the ordinary camera
gesture. Both framings put the frame's centre under the crossing point, so the
comparison is about what is visible around it.

## The coordinate-space trap (the ticket's one genuinely uncertain link)

Not measured — this is what the code does, read off it after the fact:

- `ShareImageActivity` composes the OCR input at the **container's own size**
  (`container.post { surfaceWidth = container.width; surfaceHeight = container.height }`,
  `ShareImageActivity.kt:376-378`) and adds `OcrOverlayView` as `MATCH_PARENT`
  into that same container (`:418-423`).
- Inside the view, the composed bitmap goes into an `ImageView` that is
  `MATCH_PARENT` with `FIT_XY` over a bitmap of exactly that size
  (`OcrOverlayView.kt:209-214`), and the box borders and char layers are
  `MATCH_PARENT` children of `contentContainer` placed with
  `leftMargin = box.left` / `topMargin = box.top` (`OcrOverlayView.kt:826-838`).

So bitmap-pixel coordinates and view coordinates coincide **by construction**,
whatever the content view's origin or size — the compose is derived from the
view, not the other way round. The window additionally sets
`FLAG_LAYOUT_NO_LIMITS` (`ShareImageActivity.kt:309-313`), which keeps the
content view at the display origin, so both spaces also coincide with the
display — the same equivalence the accessibility overlay relies on. The camera
path inherits all of this unchanged because it enters through the same intent.

Two things that *would* break it, worth a line in the handoff spec:

1. **A container resize that is not a configuration change.** A turn is handled
   (`configChanges` + `ShareImageActivity.onConfigurationChanged` re-composes at
   the new size and `ImageShareFit.refit` carries the boxes over), but `FIT_XY`
   stretches the image on any later resize while the box margins stay in the old
   bitmap space. The manual-input IME path sets `ADJUST_RESIZE`
   (`ShareImageActivity.kt:232-234`), so that is the shape of the hazard if the
   results panel is ever driven from the camera path.
2. **A crop that fails.** `cropToPreviewFraming` falls back to the whole frame
   (and says so in the log) when a bounds read, a decode or the JPEG write fails,
   and the same fallback answers when the preview is not laid out yet. The
   recogniser then gets a frame wider than the preview showed — text the user
   never lined up can be recognised. A photo one framing too wide is a far better
   answer than a lost capture, which is why the fallback is what it is.

## What was actually verified (the rest is open)

- `./gradlew :app:testDebugUnitTest` — **316 tests, 0 failures, 0 errors**. The
  camera work's pure layers are pinned by six test files of its own:
  `DeviceHoldTest` (the hold vocabularies and the rule that a flat phone turns
  neither the stream nor the anchors), `OrientationLockTest` and
  `OrientationManifestTest` (the lock's window/stream/placement decisions, and
  the manifest values they read), `PreviewCropTest` (the FILL_CENTER crop
  geometry), `ImageShareRefitTest` (the re-fit transform) and
  `InheritedOrientationTest` (the handoff rule).
- `./gradlew :app:assembleBenchmark` — builds; `app/build/outputs/apk/benchmark/app-benchmark.apk`,
  70,441,193 bytes, `sha256 8258bf192b5ce5344801f26b05bdbad6ad59ccda7639be8806c25b1bdf72b571`,
  `md5 2253d9e3cb85c4dddbdc804d381685f2`. `ProtoCameraActivity`,
  `ProtoCrosshairView`, the `protofileprovider` authority and the new pure-layer
  entry points (`streamRotationFor`, `pruneCaptures`, `ownViewStartedCounting`,
  `unregisterRefitLayoutListener`) are present in `classes.dex`, and
  `aapt2 dump xmltree` on the packaged manifest shows `CAMERA`,
  `ProtoCameraActivity` and the provider — checked by extracting the APK, not by
  trusting the build.
- Licence-index guard: `:app:generateLicenseIndex` then `:app:verifyLicenseIndex`
  (separate invocations) both pass. **One hand-written row was needed** —
  `com.google.auto.value:auto-value-annotations`, a non-androidx transitive of
  `androidx.camera:camera-core`, which the `androidx.*` glob does not cover
  (`app/licenses/maven.tsv:26`, and the index at
  `app/src/main/assets/licenses/INDEX.txt:106`). The guard failed the build with
  exactly that module named, which is the guard working as designed; the row cites
  the grandparent POM (`com.google.auto:auto-parent:7`), the same pattern the
  `listenablefuture` row uses.
- Device: installed once (`adb install -r` → `Success`; provider and `CAMERA`
  present in `dumpsys package`). That build predates the preview-framing toggle
  and the lock. Nothing was driven.

## Decisions taken (each is a one-liner to change)

- **Reused `ACTION_SEND` + `EXTRA_STREAM` with a FileProvider URI** rather than
  adding a second entry to `ShareImageActivity`. The activity already consumes
  exactly that, and a second entry (a bitmap in a companion object, or an
  in-process binder call) would be a new seam to keep aligned. The cost is one
  manifest provider and one paths XML.
- **CameraX 1.4.2**, `PreviewView` in `COMPATIBLE` (TextureView) mode: no reason
  to take the `SurfaceView` path in a viewfinder whose whole point is drawing
  the reticle over the preview.
- **The window follows the phone** (`fullSensor` + `configChanges`), the stream
  follows the sensor's hold and the anchors follow the window (see the
  orientation section). A portrait-locked activity was the earlier choice and was
  wrong for the capture: a locked activity holds the display rotation at 0, so a
  phone held sideways produced an upright capture and text on its side.
- **`CAPTURE_MODE_MINIMIZE_LATENCY`**, no flash, back camera. Tapping the preview
  refocuses there (the ordinary camera gesture); framing has its own button,
  because a tap that silently changed what the capture would contain read as an
  accidental zoom.
- Captures are **left on disk** in `cache/proto-camera/` so a build can be pulled
  apart for evidence, bounded to the newest six files (`pruneCaptures`) rather
  than growing for the life of the install.
- A `#78` marker naming the ticket on every touched production file, so
  `git grep '#78'` lists them.

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
  `ShareImageActivity` explicitly. That is the capture handoff, unmodified, and
  it happened twice. The `clip={image/jpeg {U(content)}}` is the URI the OS
  carried; a `file://` path would not read that way.
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
this branch except the preview-framing toggle, the orientation lock and the
re-fit, none of which existed yet.

## Artifacts

| | |
|---|---|
| `app/build/outputs/apk/benchmark/app-benchmark.apk` (in the worktree `/tmp/ijpd-camera`, built 2026-09-14 21:37 JST) | 70,441,193 bytes, `sha256 8258bf192b5ce5344801f26b05bdbad6ad59ccda7639be8806c25b1bdf72b571`, `md5 2253d9e3cb85c4dddbdc804d381685f2` |
| earlier benchmark copy on the phone | `/sdcard/Download/IJPD-6ee833f-proto78-camera.apk`, 70,406,417 bytes (a build from before this note was updated; not re-installed) |
| debug APK (debuggable — needed for `run-as`) | `app/build/outputs/apk/debug/app-debug.apk`, 75,765,987 bytes (2026-09-13 23:07, so before this note's fixes — rebuild it if `run-as` is needed) |
| evidence kept off the repo | `/tmp/proto-evidence/` — the maintainer's two pulled captures, the screenshots taken while blocked by the lock screen, and the system-log extracts |

Both APKs are signed with the Android debug key (certificate SHA-256
`a59d1005…`) — the same key as the build already on the phone, so
`adb install -r` replaces it without an uninstall.
