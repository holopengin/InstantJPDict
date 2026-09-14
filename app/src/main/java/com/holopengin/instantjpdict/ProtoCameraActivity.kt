package com.holopengin.instantjpdict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PROTOTYPE (#78, throwaway — not for main, no tests, no polish beyond runnable).
 *
 * The question this answers: can our own viewfinder drop a captured photo into
 * the recognition pipeline the app already has, and does the 1px full-frame
 * crosshair read well enough in the hand to line a text line up (i.e. is
 * alignment workable before rotated-box support lands, #53)?
 *
 * Shape of the answer:
 *   camera → [ProtoCrosshairView] over the live preview → ImageCapture to a file
 *   → a FileProvider URI → ACTION_SEND, explicitly addressed to
 *   [ShareImageActivity]. That last step is the whole point: the capture reuses
 *   the EXISTING entry (`sharedImageUri` → `Intent.EXTRA_STREAM`, the same thing
 *   the system share sheet hands it), so there is exactly one decode / EXIF /
 *   compose / [OcrOverlayView] path in the app and no second renderer to drift.
 *   The boxes therefore land wherever the share path already puts them — which
 *   is what makes this a test of the capture, not a copy of the renderer.
 *
 * Deliberate prototype decisions:
 *  - The viewfinder FOLLOWS THE PHONE. It used to be portrait-locked (one
 *    orientation for the in-hand crosshair judgement, no camera reopen on a
 *    quarter turn), but a locked activity also holds the DISPLAY rotation at 0
 *    forever, so anything reading WindowManager/DisplayManager/display.rotation
 *    detects nothing — and holding the phone sideways to line the reticle up
 *    with a text line left the capture upright for a portrait hold, so the text
 *    arrived on its side. The activity is now `fullSensor` + `configChanges`
 *    (see the manifest): the whole WINDOW turns with the phone, so the preview,
 *    the reticle and the framing turn WITH it rather than a turned camera
 *    stream sitting inside an upright portrait window; the activity is not
 *    recreated, so the camera is not reopened on a quarter turn; and the bound
 *    use cases are told the rotation from the SENSOR
 *    ([android.view.OrientationEventListener], see [applyTargetRotation]),
 *    because CameraX 1.4.2 does not follow a display-rotation change on its own
 *    for an already-bound use case (only PreviewView's own surface transform
 *    tracks the display, and that is 0 under a lock anyway).
 *  - The CONTROLS FOLLOW THE WINDOW, deliberately NOT the same value the camera
 *    is told. Portrait is the layout this prototype has always had; landscape
 *    moves the shutter and the framing control to the window's RIGHT edge — the
 *    short edge, where a thumb sits when the phone is held sideways — instead of
 *    leaving a bottom-row layout running along a long edge. See
 *    [applyControlAnchors] for the anchors and [onConfigurationChanged] for why
 *    the anchors change when the WINDOW does and not when the sensor first
 *    reports the turn: a button that moved on the sensor jumped to its landscape
 *    edge before the platform had begun turning the window (and before the
 *    button itself had turned), and a phone laid flat reports ORIENTATION_UNKNOWN
 *    — portrait to [DeviceHold.surfaceRotationFor] and to the stream — so two
 *    buttons moved while nothing on screen had. The STREAM is unchanged: it still
 *    follows the sensor, because a capture must be oriented for the hand holding
 *    the phone. See [DeviceHold] for the two notions.
 *  - Back camera, minimise-latency capture mode, no flash. Tapping the preview refocuses
 *    there — the ordinary camera gesture. Framing has its own square button in the
 *    bottom-right, because a tap that silently changed what the capture would contain read
 *    as an accidental zoom.
 *  - The SHUTTER is the app's own OCR-button graphic — the cyan circle on black with the
 *    cyan 辞典 ([androidx.core.content.ContextCompat]-loaded `R.drawable.logo`, the same
 *    drawable the accessibility service's floating trigger draws), in the same square
 *    footprint the labelled button had. It is a graphic and not a new asset: a new
 *    bundled asset has to satisfy the licence index the build verifies. With no label
 *    left, the disabled state is shown by dimming ([setShutterEnabled]) and the control
 *    carries a contentDescription, because it is still the shutter.
 *  - The preview shows the WHOLE captured frame by default
 *    ([PreviewView.ScaleType.FIT_CENTER]) — a portrait view with a landscape
 *    4:3 sensor frame, so the live image is a band across the middle of the
 *    screen and what the reticle is laid over is exactly what the capture will
 *    contain. The Zoom button switches to
 *    [PreviewView.ScaleType.FILL_CENTER], which fills the screen like an
 *    ordinary camera app by showing only the middle slice of the frame — and
 *    the photo handed to the model is cropped to that same slice
 *    ([cropToPreviewFraming]), so the recogniser reads what was on screen and
 *    not text the user never saw. Both framings put the frame's centre under
 *    the reticle's crossing point, so the comparison is about what is visible
 *    around it, not about where the centre is.
 *  - The FILL_CENTER crop is taken in the handoff, not by a CameraX
 *    [androidx.camera.core.ViewPort]. A viewport is the 'proper' route — the
 *    sensor pipeline itself is cropped, so preview and capture agree by
 *    construction and no JPEG is re-encoded — but it only applies to a bound
 *    use-case group, so every framing toggle would have to unbind and rebind
 *    the camera, turning the live preview (the one thing on this branch that
 *    cannot be checked without the phone) into the part being changed. The
 *    cost of cropping in the handoff is one bounded decode, a rotate and a
 *    re-encode of a ~0.9 MP result, on a background thread; FIT_CENTER hands
 *    over the camera's own file untouched and pays nothing at all.
 *  - The crop is computed through the EXIF-UPRIGHT orientation — the stored
 *    pixels are LANDSCAPE (4032x3024) while the picture is PORTRAIT, so a rect
 *    taken in stored-pixel space would be turned a quarter turn against the
 *    picture and crop the wrong axis. It is bounded by [CROP_LONG_SIDE_PX], and
 *    any failure hands over the ORIGINAL file and says so in the log rather
 *    than losing the capture.
 *  - COPIES_FORWARD: never taken; the captured file — and, in FILL_CENTER, the
 *    cropped one beside it — is left in the cache dir (see [CAPTURE_DIR_NAME])
 *    so it can be pulled off the device for evidence.
 */
class ProtoCameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView

    /** The camera's controls (focus). Kept from the bind, which returns the session. */
    private var cameraControl: androidx.camera.core.CameraControl? = null

    /**
     * The bound preview use case. Kept with [imageCapture] because both carry the
     * rotation the viewfinder follows the phone with (see [applyTargetRotation]).
     */
    private var previewUseCase: Preview? = null

    /**
     * The rotation handed to the use cases last — the gate that keeps a
     * continuously-firing [orientationListener] from doing work on every degree
     * of tilt. ROTATION_0 is only the value before the first sensor callback,
     * which arrives as soon as the listener is enabled.
     */
    private var targetRotation = Surface.ROTATION_0

    /**
     * The phone's own rotation, which is the only source that reports it here:
     * this activity's display rotation used to be pinned at 0 by the portrait
     * lock, and even unlocked it is the window's answer, arriving late. Built in
     * [onCreate] (an Activity is not a usable Context before then) and enabled
     * only while this activity is the one on screen.
     */
    private lateinit var orientationListener: OrientationEventListener

    /** The reticle, kept so a resumed activity can pick up a moved tuning slider. */
    private lateinit var crosshair: ProtoCrosshairView

    /**
     * The framing control (the "Zoom" button). Kept as a field because its anchor
     * is re-decided when the hold changes (see [applyControlAnchors]) — the views
     * are not rebuilt on a turn, `configChanges` means this activity is never
     * recreated, so the LayoutParams have to be re-applied to the views that are
     * already there.
     */
    private lateinit var zoomButton: Button

    /**
     * True while the shutter and the framing control are anchored for a landscape
     * WINDOW, false for portrait — the state [applyControlAnchors] reads, and the
     * gate that keeps a window change from re-writing two sets of LayoutParams
     * when nothing about the anchors would differ. Seeded in [onCreate] from the
     * hold the window came up in ([windowIsLandscape]) so the first frame is
     * right, and re-decided in [onConfigurationChanged] — NOT from the sensor:
     * see [DeviceHold] for why the anchors may not follow the sensor.
     */
    private var controlsLandscape = false

    /**
     * The system bars' right and bottom insets, in pixels, as the root last
     * reported them. Only the landscape anchors use them — the right edge is the
     * edge the navigation bar moves to in landscape — but they are kept as state
     * because the anchors are re-applied from two directions (a turn, and an inset
     * change) and both need the same numbers.
     */
    private var systemBarRight = 0
    private var systemBarBottom = 0
    private var systemBarTop = 0

    /**
     * The decode / rotate / crop of one FILL_CENTER handoff. One thread, because
     * a capture is one press and the shutter stays disabled until the handoff:
     * a 12 MP decode is tens of megabytes of ARGB and must never run on the
     * thread that is also drawing the preview. Shut down with the activity.
     */
    private val cropExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Tap to focus: the ordinary camera gesture, moved here from the framing toggle. */
    private fun focusAt(x: Float, y: Float) {
        val control = cameraControl ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        try {
            control.startFocusAndMetering(action)
            Log.i(TAG, "tap-focus at ${x.toInt()},${y.toInt()}")
        } catch (t: Throwable) {
            Log.e(TAG, "tap-focus failed", t)
        }
    }

    override fun onResume() {
        super.onResume()
        // A gap slider moved in the tuning screen applies on return, without a restart.
        if (::crosshair.isInitialized) crosshair.reloadGap()
        // Returning from the results view: capture() disables the shutter and only its FAILURE
        // paths re-enable it, so without this the button stayed grey and unclickable until the
        // app restarted. The capture session lives as long as this activity, so having one is
        // the right test.
        if (::captureButton.isInitialized) setShutterEnabled(imageCapture != null)
        // The phone can be turned while another activity is up; watch it again from here.
        if (::orientationListener.isInitialized) {
            if (orientationListener.canDetectOrientation()) {
                orientationListener.enable()
            } else {
                // No sensor to read (some emulators, a device with the sensor denied): the
                // viewfinder then keeps whatever rotation it was bound with, which is the
                // behaviour before this change rather than something worse.
                Log.w(TAG, "no orientation sensor: the viewfinder will not follow the phone")
            }
        }
    }

    override fun onPause() {
        // The results view is another activity: nothing to watch while it is up.
        if (::orientationListener.isInitialized) orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        cropExecutor.shutdown()
        super.onDestroy()
    }
    private lateinit var captureButton: Button
    private var imageCapture: ImageCapture? = null

    /** Which preview framing is on screen; see [togglePreviewFraming]. */
    private var previewFill = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            tag = "proto_preview"
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // Default: the whole captured frame visible (see the class doc).
            scaleType = PreviewView.ScaleType.FIT_CENTER
            // Tap = refocus at that point, the ordinary camera gesture. Framing moved to its
            // own button: a tap that silently changed what the capture would contain read as
            // an accidental zoom.
            setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    focusAt(ev.x, ev.y)
                    v.performClick() // silences ClickableViewAccessibility; no click action
                }
                true
            }
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // The reticle. Added after the preview so it draws over the live image;
        // it never takes touch (no click listeners, not clickable), so the
        // shutter button below stays reachable. Same bounds as the preview, so
        // its crossing point is the view centre — and therefore the centre of
        // whatever the FILL_CENTER crop turns out to be.
        root.addView(
            ProtoCrosshairView(this).also { crosshair = it },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // The top-left corner: the back control, on its own. The window is
        // edge-to-edge (targetSdk 35 on Android 15+ forces it) and the preview is
        // deliberately full-bleed, so the insets are taken by this stack rather than
        // by the root: without them the button's top slice sits under the status bar
        // and the system takes the touches.
        val corner = LinearLayout(this).apply {
            tag = "proto_corner"
            orientation = LinearLayout.VERTICAL
        }
        val backSide = (BACK_BUTTON_DP * resources.displayMetrics.density).roundToInt()
        corner.addView(backButton(), LinearLayout.LayoutParams(backSide, backSide))
        root.addView(
            corner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        )
        ViewCompat.setOnApplyWindowInsetsListener(corner) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val base = (CORNER_MARGIN_DP * resources.displayMetrics.density).roundToInt()
            v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = base + bars.left
                topMargin = base + bars.top
            }
            insets
        }
        // The same system bars, taken once more for the two camera controls: in
        // landscape the navigation bar sits along a long edge, and the anchors below
        // put both controls against the right one, so their margins carry the right
        // (and the framing control the bottom) inset. Attached to the root rather
        // than to either control because insets are dispatched down the tree from
        // here and this is also where they are stored — one place reports them, and
        // [applyControlAnchors] is the only place that spends them. Guarded on a
        // change, because a re-apply inside an inset dispatch requests another
        // layout pass.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (bars.right != systemBarRight || bars.bottom != systemBarBottom ||
                bars.top != systemBarTop
            ) {
                systemBarRight = bars.right
                systemBarBottom = bars.bottom
                systemBarTop = bars.top
                applyControlAnchors()
            }
            insets
        }

        captureButton = CenteredButton(this).apply {
            tag = "proto_capture"
            // The app's OWN OCR-button graphic — the cyan circle on black with the cyan
            // 辞典, i.e. R.drawable.logo, the very drawable the accessibility service's
            // floating button draws (logoButtonBackground). No new asset: a new bundled
            // asset has to satisfy the licence index (app/licenses/components.tsv) and
            // would fail the build's verifyLicenseIndex step.
            background = logoButtonBackground(this@ProtoCameraActivity)
            // Cleared so the fixed square side below wins over Button's own minimums.
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            // The label is gone, so the control says what it is to a screen reader:
            // this is the shutter (see [setShutterEnabled] for how the disabled state
            // reads without a text colour to grey out).
            contentDescription = SHUTTER_DESCRIPTION
            setOnClickListener { capture() }
        }
        setShutterEnabled(false)
        // Both controls are added with their square footprint and NO anchor: where
        // they sit is decided in one place, [applyControlAnchors], because the anchor
        // depends on which way up the window is and has to be re-decided when that
        // changes. The views themselves are never rebuilt.
        //
        // One side for both dimensions, because this is a shutter, not a label:
        // WRAP_CONTENT made it a wide, short pill, and one square side keeps it
        // square whatever the label measures — CAPTURE_BUTTON_DP is the single knob
        // for the size of both controls.
        val controlSide = (CAPTURE_BUTTON_DP * resources.displayMetrics.density).roundToInt()
        zoomButton = Button(this).apply {
            tag = "proto_zoom"
            text = "Zoom"
            minWidth = 0
            minHeight = 0
            setOnClickListener { togglePreviewFraming() }
        }
        root.addView(zoomButton, FrameLayout.LayoutParams(controlSide, controlSide))
        root.addView(captureButton, FrameLayout.LayoutParams(controlSide, controlSide))

        // Which way up the WINDOW came up, BEFORE it is first laid out, and the
        // controls are anchored for it in the same breath — that is what makes a
        // cold start into landscape come up with the controls already on the right
        // edge instead of waiting for a sensor report that would never come (the
        // sensor already agrees; nothing is going to change). See
        // [windowIsLandscape], and [DeviceHold] for why this is the WINDOW's
        // answer and not the sensor's.
        controlsLandscape = windowIsLandscape()
        applyControlAnchors()

        setContentView(root)

        // The sensor, built here and enabled in onResume. A callback fires for
        // every degree of tilt, so [targetRotation] is what keeps this cheap: only
        // an actual quarter turn reaches the camera.
        //
        // The CALLBACK drives the STREAM and nothing else. It used to re-decide the
        // controls' anchors from the same value, "so buttons and stream cannot
        // disagree" — which was right for the stream and wrong for the buttons: it
        // moved them the moment the sensor noticed the turn, i.e. before the
        // platform had begun turning the window (and before the buttons
        // themselves had turned), and it moved them for a phone laid flat, whose
        // orientation reads as portrait while the window is still landscape. The
        // anchors now follow the window ([onConfigurationChanged]); a capture
        // still follows the hand.
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = surfaceRotationFor(orientation)
                if (rotation == targetRotation) return
                val was = targetRotation
                targetRotation = rotation
                Log.i(
                    TAG,
                    "device turned: orientation $orientation -> " +
                        "${rotationName(rotation)} (was ${rotationName(was)}), " +
                        "view ${previewView.width}x${previewView.height}"
                )
                applyTargetRotation(rotation)
                Log.i(TAG, statusText())
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * The back control, in the top-left corner. It does exactly what the system
     * back button does — one press through [onBackPressedDispatcher], the same
     * entry the platform's own back reaches (this activity overrides no back
     * handling of its own, so there is no second exit path to drift from).
     *
     * Styled like the share activity's chrome — a text glyph on a semi-transparent
     * dark fill with the app's cyan outline — rather than a bundled asset: a new
     * asset would have to satisfy the app's licence index, a glyph does not. 52dp
     * is the chrome size there; the camera's 84dp squares are its shutter-sized
     * primary controls, which a corner affordance is not.
     */
    private fun backButton(): Button = CenteredButton(this).apply {
        tag = "proto_back"
        text = BACK_GLYPH
        contentDescription = "Back"
        setTextColor(BACK_GLYPH_COLOR)
        textSize = BACK_GLYPH_TEXT_SIZE_SP
        includeFontPadding = false
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        background = chromeBackground()
        setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    /** Dark fill with the app's cyan outline, as the share activity's chrome uses. */
    private fun chromeBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = BACK_BUTTON_RADIUS_DP * resources.displayMetrics.density
        setColor(BACK_BUTTON_FILL)
        setStroke((1.5f * resources.displayMetrics.density).roundToInt(), BACK_BUTTON_STROKE)
    }

    /**
     * The rotation of what the camera writes, from the phone's own orientation —
     * [DeviceHold.surfaceRotationFor], which owns the bands (CameraX's own, and
     * the reason `ORIENTATION_UNKNOWN` reads as portrait there) so this is not a
     * second implementation of them. This is the STREAM's and the capture's value:
     * it goes to both use cases ([applyTargetRotation]) and, in the handoff, to
     * [ShareImageActivity]. It is deliberately NOT what the control anchors are
     * decided from — see [windowIsLandscape] and [DeviceHold].
     */
    private fun surfaceRotationFor(orientation: Int): Int =
        DeviceHold.surfaceRotationFor(orientation)

    /**
     * Which way up this activity's WINDOW is — [DeviceHold]'s `isLandscapeWindow`,
     * the single definition of that notion, read from the configuration the
     * activity is laid out with. This is the one thing the control anchors are
     * decided from, and the reason they are not decided from the sensor:
     * see [DeviceHold], [onConfigurationChanged] and [applyControlAnchors].
     */
    private fun windowIsLandscape(): Boolean =
        DeviceHold.isLandscapeWindow(resources.configuration.orientation)

    /**
     * The window turned. This activity declares `configChanges` for exactly this
     * (see the manifest), so it is never re-created on a quarter turn and this is
     * where the CONSEQUENCE of the turn for this activity's own layout is worked
     * out. The camera needs nothing here — it follows the sensor, which reported
     * the new hold before the platform had finished turning the window — and the
     * crosshair is MATCH_PARENT, so it needs nothing either. The controls do: the
     * anchors are for the window's edges, so they move when the window moves and
     * not one frame before it.
     *
     * Why here and not in the sensor callback (which is where they used to be
     * decided): the window only turns once the platform plays its rotation
     * animation, which is well after the sensor's first report of the new hold,
     * so anchors decided from the sensor arrived while the window — and the
     * buttons' own drawing — was still portrait: the button appeared to jump to
     * the wrong edge and then jump back. And a phone laid FLAT reports
     * ORIENTATION_UNKNOWN, which [surfaceRotationFor] reads as portrait (right for
     * a capture, which must be oriented for the hand) while the window is still
     * landscape: the anchors moved two buttons while nothing on screen had moved.
     * Neither can happen while the window is the only thing that decides them.
     *
     * Sizes are untouched — 84dp squares, the same margins; only which edge they
     * are anchored to changes. The right/bottom system-bar insets
     * [applyControlAnchors] folds in are re-reported by the root's own insets
     * listener when the bars move to the new edges, so nothing is read twice.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = DeviceHold.isLandscapeWindow(newConfig.orientation)
        // Logged whether or not it changes anything: this line beside the sensor's
        // own "device turned" line is what says the window lagged the sensor rather
        // than the two having moved together.
        Log.i(
            TAG,
            "window turned: ${if (landscape) "landscape" else "portrait"}" +
                " (was ${if (controlsLandscape) "landscape" else "portrait"}), " +
                "view ${previewView.width}x${previewView.height}"
        )
        if (landscape == controlsLandscape) return
        controlsLandscape = landscape
        applyControlAnchors()
    }

    /**
     * Where the shutter and the framing control sit, for the WINDOW on screen.
     *
     * PORTRAIT is the layout this prototype has always had, unchanged: the shutter
     * centred on the bottom edge, the framing control in the bottom-right corner,
     * both 84dp squares, at the raw-pixel margins they were first placed with
     * ([CONTROL_BOTTOM_MARGIN_PX] / [ZOOM_END_MARGIN_PX] — kept as pixels on
     * purpose, because portrait has been judged in the hand and a unit conversion
     * would move both controls on the phone it was judged on). Portrait takes no
     * insets, then or now: the corner stack is the control that carries them there,
     * and the bottom margin has always been that raw 48px.
     *
     * LANDSCAPE moves both to the window's RIGHT edge — in a landscape window the
     * short edges are the left and right ones, so the right edge is where a thumb
     * sits when the phone is held sideways, while the bottom edge is a long one a
     * re-applied portrait row would have run along, out of reach:
     *   - the SHUTTER goes on the right edge, VERTICALLY CENTRED: the ordinary
     *     landscape camera hold, thumb-high, and the one anchor the maintainer
     *     asked for by name ("the right side of the screen when landscape");
     *   - the FRAMING control goes in the bottom-right corner — still under that
     *     thumb, and clear of the shutter: what the framing control needs below the
     *     centred shutter (its own 84dp, its margin, and the navigation bar when a
     *     three-button device puts it along that bottom edge) fits in the space a
     *     1080px-tall landscape window leaves there. That calculation is where the
     *     framing control's own margin came from; see [applyControlAnchors] for how
     *     the zoom control's margins are swapped in landscape so that it keeps the
     *     same PHYSICAL corner rather than the same relative one.
     * The right system-bar inset is added to both, and the bottom one to the framing
     * control, because in landscape the navigation bar lies along a long edge — the
     * edge these two now hug — which is the same reason the corner stack takes
     * insets in the first place.
     *
     * This is the ONLY place either control's LayoutParams are written, so a WINDOW
     * turn ([onConfigurationChanged]) and an inset change cannot half-update the
     * layout, and the params are rebuilt rather than mutated so no margin can
     * survive from an anchor it no longer belongs to (a `marginEnd` left behind on a
     * `CENTER_HORIZONTAL` control shifts it off centre). Nothing else calls it: the
     * SENSOR deliberately does not, because the anchors are the window's and not the
     * hold's (see [DeviceHold]).
     */
    private fun applyControlAnchors() {
        if (!::captureButton.isInitialized || !::zoomButton.isInitialized) return
        val density = resources.displayMetrics.density
        val side = (CAPTURE_BUTTON_DP * density).roundToInt()
        if (controlsLandscape) {
            val edge = (LANDSCAPE_EDGE_MARGIN_DP * density).roundToInt() + systemBarRight
            placeControl(captureButton, side, Gravity.END or Gravity.CENTER_VERTICAL, edge, 0)
            // Zoom goes to the TOP of the right edge in landscape, which is the same
            // PHYSICAL corner it occupies in portrait (bottom-right): the quarter turn
            // carries the picture's right edge to the window's top. The margins are the
            // portrait ones swapped for the same reason — the 24px that was off the
            // physical right edge is now off the window's top, and the 48px that was off
            // the physical bottom is now off the window's right. The top inset is folded
            // in exactly as the right one is, so the control does not sit under the
            // status bar in landscape.
            val zoomEdge = CONTROL_BOTTOM_MARGIN_PX + systemBarRight
            val zoomTop = ZOOM_END_MARGIN_PX + systemBarTop
            placeControl(zoomButton, side, Gravity.END or Gravity.TOP, zoomEdge, 0, zoomTop)
        } else {
            placeControl(
                captureButton, side,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, CONTROL_BOTTOM_MARGIN_PX
            )
            placeControl(
                zoomButton, side,
                Gravity.BOTTOM or Gravity.END, ZOOM_END_MARGIN_PX, CONTROL_BOTTOM_MARGIN_PX
            )
        }
        Log.i(
            TAG,
            "controls anchored ${if (controlsLandscape) {
                "landscape (right edge), insets ${systemBarRight}/${systemBarBottom}/${systemBarTop}"
            } else {
                "portrait (bottom edge)"
            }}"
        )
    }

    /**
     * One square control at one anchor, with its margins already in pixels (the
     * caller folds the insets in). A fresh LayoutParams every time, deliberately:
     * see [applyControlAnchors].
     */
    private fun placeControl(
        view: View,
        side: Int,
        anchor: Int,
        endMargin: Int,
        bottomMargin: Int,
        topMargin: Int = 0,
    ) {
        view.layoutParams = FrameLayout.LayoutParams(side, side).apply {
            gravity = anchor
            marginEnd = endMargin
            this.bottomMargin = bottomMargin
            this.topMargin = topMargin
        }
    }

    /**
     * Tells both bound use cases which way up the camera's output is. One value,
     * set on both, from one physical hold — that is what makes what is on screen
     * and what lands in the file agree.
     *
     * Both are settable after binding: camera-core 1.4.2 declares a public
     * `setTargetRotation(int)` on `Preview` and on `ImageCapture` (checked against
     * the AAR, not assumed), and CameraX rebinds the use case internally when it
     * changes — so this is a property set, not an unbind/rebind of the camera.
     * Before the bind lands the use cases are null and the value is carried in
     * [targetRotation], which [startCamera] applies once they exist.
     */
    private fun applyTargetRotation(rotation: Int) {
        previewUseCase?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    /** The value's own name, so the log can be read without decoding a bare int. */
    private fun rotationName(rotation: Int): String = when (rotation) {
        Surface.ROTATION_0 -> "ROTATION_0"
        Surface.ROTATION_90 -> "ROTATION_90"
        Surface.ROTATION_180 -> "ROTATION_180"
        Surface.ROTATION_270 -> "ROTATION_270"
        else -> "rotation $rotation"
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                previewUseCase = preview
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                cameraControl = camera.cameraControl
                setShutterEnabled(true)
                // AFTER the bind, deliberately: a use case takes its target rotation
                // from the display at bind time, so anything set before this would be
                // overwritten. The sensor may already have reported a hold while this
                // future was pending — [targetRotation] is the value to hand over.
                applyTargetRotation(targetRotation)
                Log.i(TAG, statusText())
                // The crop's one assumption, printed so it can be checked from logcat:
                // the FILL_CENTER rect is worked out against the CAPTURE's frame, and it
                // is only the right rect if the preview stream is the same shape.
                Log.i(
                    TAG,
                    "camera bound, viewfinder up: preview " +
                        "${preview.resolutionInfo?.resolution}, capture " +
                        "${capture.resolutionInfo?.resolution}, view " +
                        "${previewView.width}x${previewView.height}, " +
                        "targetRotation ${rotationName(capture.targetRotation)}"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * The Zoom button: FIT_CENTER (the whole captured frame, letterboxed into
     * the portrait view, and the whole frame goes to the model) <->
     * FILL_CENTER (screen filled by the middle slice of the frame, and that
     * slice is what goes to the model — see [cropToPreviewFraming]). The active
     * framing is logged so the in-hand verdict can be written down without having
     * to remember which one was on screen.
     */
    private fun togglePreviewFraming() {
        previewFill = !previewFill
        previewView.scaleType = if (previewFill) {
            PreviewView.ScaleType.FILL_CENTER
        } else {
            PreviewView.ScaleType.FIT_CENTER
        }
        Log.i(TAG, "preview framing = ${if (previewFill) "FILL_CENTER" else "FIT_CENTER"}")
    }

    /**
     * The viewfinder's state as one log line — which way up the camera's output is
     * being told to sit and which framing the preview is showing. This used to be
     * the top-left status line; the readout is diagnostic, so it goes to logcat
     * instead of onto the preview.
     */
    private fun statusText(): String = "viewfinder state: targetRotation " +
        "${rotationName(targetRotation)}, framing ${if (previewFill) {
            "FILL_CENTER (screen filled; the photo sent is this crop)"
        } else {
            "FIT_CENTER (whole photo visible, and sent whole)"
        }}"

    /**
     * The shutter's enabled state and its look, in one place.
     *
     * The control is the app's OCR-button graphic now, not a labelled Button, so
     * there is no text colour for the platform to grey out when it is disabled —
     * and [capture] disables it for the whole handoff (a second press would write a
     * second file for the same shot), so an indistinguishable disabled state would
     * read as a dead button. The dimming is the whole of the difference; which
     * presses are allowed is unchanged, including the onResume restore in
     * [onResume].
     */
    private fun setShutterEnabled(enabled: Boolean) {
        captureButton.isEnabled = enabled
        captureButton.alpha = if (enabled) SHUTTER_ALPHA_ENABLED else SHUTTER_ALPHA_DISABLED
    }

    /**
     * One press: write a JPEG, hand its URI to the EXISTING share entry.
     *
     * Which JPEG depends on the framing that was on screen at the press —
     * latched here, not re-read when the file lands, so a toggle while the
     * shutter is writing cannot pick the wrong one. FILL_CENTER goes through
     * [cropToPreviewFraming]; FIT_CENTER hands over the camera's own file
     * untouched.
     *
     * [ShareImageActivity] is started explicitly rather than through the system
     * share sheet, but with exactly the intent it documents it consumes
     * (ACTION_SEND + EXTRA_STREAM), so nothing about its decode / EXIF /
     * compose / overlay path is special-cased for the camera. No
     * FLAG_ACTIVITY_NEW_TASK: same app, same task, so back from the results
     * returns here — and the activity finishes so this does not pile up.
     */
    private fun capture() {
        val capture = imageCapture ?: return
        setShutterEnabled(false)
        Log.i(TAG, "capturing")
        val framingWasFill = previewFill
        val dir = File(cacheDir, CAPTURE_DIR_NAME).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val file = File(dir, "capture-$stamp.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    Log.i(
                        TAG,
                        "captured ${file.length()} bytes -> ${file.absolutePath} (resultUri=${results.savedUri})"
                    )
                    if (!framingWasFill) {
                        // The default framing's whole point: the photo already holds exactly
                        // what was on screen, so the crop path is skipped and the file the
                        // camera wrote is the one that goes over.
                        handOff(file, "FIT_CENTER: full frame, ${file.length()} B")
                        return
                    }
                    cropThenHandOff(file, File(dir, "capture-$stamp-zoom.jpg"))
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    setShutterEnabled(true)
                }
            }
        )
    }

    /**
     * FILL_CENTER's handoff: crop [source] to the slice the preview showed, off
     * the main thread, then hand the result over.
     *
     * ANY failure — an unreadable EXIF tag, a decode that returns null, a full
     * heap — falls back to [source] and says so in the log. A photo one framing
     * too wide is a worse answer than no answer, but a far better one than a
     * lost capture.
     */
    private fun cropThenHandOff(source: File, dest: File) {
        val view = previewView
        if (view.width <= 0 || view.height <= 0) {
            Log.w(TAG, "preview not laid out (${view.width}x${view.height}); handing the full frame")
            handOff(source, "FILL_CENTER: crop skipped, preview not laid out — full frame")
            return
        }
        // The view's own aspect, not the screen's: this is the shape the preview
        // actually filled, so this is the shape the preview actually showed.
        val viewAspect = view.width.toFloat() / view.height.toFloat()
        cropExecutor.execute {
            val cropped = try {
                cropToPreviewFraming(source, dest, viewAspect)
            } catch (t: Throwable) {
                Log.e(TAG, "crop failed; handing the full frame instead", t)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (cropped != null) {
                    handOff(
                        cropped.file,
                        "FILL_CENTER: crop ${cropped.width} x ${cropped.height} sent" +
                            " (${cropped.file.length()} B of ${source.length()} B)"
                    )
                } else {
                    handOff(source, "FILL_CENTER: crop failed (see log) — full frame instead")
                }
            }
        }
    }

    /** What [cropToPreviewFraming] produced: the file, and the OCR input's own size. */
    private data class CroppedFrame(val file: File, val width: Int, val height: Int)

    /**
     * The centred region the FILL_CENTER preview showed, taken from [source] and
     * written to [dest] as an upright JPEG. Null when anything failed — see
     * [cropThenHandOff], which then hands over the original.
     *
     * The frame is decoded with [cropSampleSize]'s `inSampleSize` and always
     * through the UPRIGHT orientation: the stored pixels on this device are
     * LANDSCAPE (4032x3024) with an EXIF 6 tag while the picture is PORTRAIT,
     * so a rect computed against the stored pixels is a quarter turn out from
     * the picture and crops the wrong dimension. That is trap #1 on this
     * branch, and it is why the geometry below only ever sees an upright bitmap.
     */
    private fun cropToPreviewFraming(source: File, dest: File, viewAspect: Float): CroppedFrame? {
        val orientation = try {
            ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifOrientation.NORMAL)
        } catch (t: Throwable) {
            // No EXIF at all is the same as already upright, and is not worth
            // abandoning the crop for.
            Log.w(TAG, "EXIF unreadable on ${source.absolutePath}; assuming upright", t)
            ExifOrientation.NORMAL
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "no bounds for ${source.absolutePath}; no crop")
            return null
        }
        val sample = cropSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options)
        if (decoded == null) {
            Log.w(TAG, "decode returned null for ${source.absolutePath}; no crop")
            return null
        }

        val upright = uprightCopy(decoded, orientation)
        if (upright !== decoded) decoded.recycle()
        val frameW = upright.width
        val frameH = upright.height
        val rect = cropRect(frameW, frameH, viewAspect)
        val out = Bitmap.createBitmap(upright, rect.left, rect.top, rect.width(), rect.height())
        if (out !== upright) upright.recycle()
        val cropW = out.width
        val cropH = out.height

        val wrote = try {
            FileOutputStream(dest).use {
                out.compress(Bitmap.CompressFormat.JPEG, CROP_JPEG_QUALITY, it)
            }
        } finally {
            out.recycle()
        }
        if (!wrote) {
            Log.w(TAG, "JPEG write failed for ${dest.absolutePath}; no crop")
            return null
        }
        // The crop is already upright, so the file says so: the reader then needs
        // no guessing about a tag the encoder never wrote.
        try {
            ExifInterface(dest).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifOrientation.NORMAL.toString())
                saveAttributes()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not stamp orientation on ${dest.absolutePath}", t)
        }

        Log.i(
            TAG,
            "crop: ${bounds.outWidth}x${bounds.outHeight} stored (orientation $orientation," +
                " sample $sample) -> upright ${frameW}x${frameH} -> ${rect.width()}x${rect.height()}" +
                " at ${rect.left},${rect.top} (viewAspect=${"%.3f".format(viewAspect)})" +
                " -> ${dest.absolutePath} (${dest.length()} B)"
        )
        return CroppedFrame(dest, cropW, cropH)
    }

    /**
     * The region a FILL_CENTER preview showed, as a rect in an [frameW] x
     * [frameH] UPRIGHT frame's pixels — [PreviewCrop.cover], which owns the
     * geometry (and is unit-tested), converted to the `Rect` the Bitmap crop
     * wants.
     *
     * FILL_CENTER scales the frame up until it covers a view of [viewAspect] and
     * lets the overflow fall off, so the visible region is the centred rect with
     * the VIEW's aspect. Which pair of edges falls off follows from the two
     * aspects alone, which is why a landscape hold needs nothing new here: the
     * view is then the wider shape, and the crop is the full width with the top
     * and bottom cut — the same slice the preview was filling.
     */
    private fun cropRect(frameW: Int, frameH: Int, viewAspect: Float): Rect {
        val region = PreviewCrop.cover(frameW, frameH, viewAspect)
        return Rect(region.left, region.top, region.left + region.width, region.top + region.height)
    }

    /**
     * The power-of-two `inSampleSize` that lands the stored frame's long side at
     * or under [CROP_LONG_SIDE_PX]. That one constant is the whole knob: it is
     * both the memory bound (a 12 MP JPEG is ~48 MB of ARGB at full size, and
     * this app has already OOM'd against a 256 MB heap) and the size the
     * recogniser is ultimately fed.
     */
    private fun cropSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > CROP_LONG_SIDE_PX) sample *= 2
        return sample
    }

    /**
     * [decoded] turned so the picture is upright, or [decoded] itself when it
     * already is. The same correction the share path applies, for the same
     * reason ([ShareImageActivity]): this device stores a portrait picture as
     * landscape pixels plus an orientation tag.
     */
    private fun uprightCopy(decoded: Bitmap, orientation: Int): Bitmap {
        val correction = ExifOrientation.correction(orientation)
        if (correction.isIdentity) return decoded
        val matrix = Matrix()
        matrix.setRotate(correction.rotationDegrees.toFloat())
        if (correction.flipHorizontal) matrix.postScale(-1f, 1f)
        if (correction.flipVertical) matrix.postScale(1f, -1f)
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    /**
     * The single handoff: [file]'s FileProvider URI into the existing share
     * entry, with [note] — which framing actually went over — written to the log
     * so it can be read back afterwards.
     *
     * It carries the hold as well, and that is the handoff marker:
     * [InheritedOrientation.EXTRA_CAMERA_HOLD] gets [targetRotation] — the same
     * number [applyTargetRotation] just gave both use cases, so nothing new is
     * decided here, a value the viewfinder already had is passed on. (It is the
     * STREAM's hold and not the anchors', which is right for this extra: what the
     * OCR view logs it against is the way the camera's CAPTURE was oriented,
     * which is what the photo it is about to read was written with.) The OCR view
     * reads the extra in `onCreate` and logs it
     * beside the window it came up in (see [InheritedOrientation]): the OCR view's
     * ORIENTATION is no longer asked for at runtime — it is declared as
     * `fullSensor` in that activity's manifest entry, the same value this activity
     * declares for itself, so both windows resolve the hold from the same sensor
     * and the OCR view's first layout is already the hold. The explicit
     * `setClass` below is what keeps the handoff on that entry; the system share
     * sheet reaches the same activity with no extra of its own.
     */
    private fun handOff(file: File, note: String) {
        val uri = try {
            FileProvider.getUriForFile(
                this,
                "$packageName$FILE_PROVIDER_SUFFIX",
                file
            )
        } catch (t: Throwable) {
            Log.e(TAG, "FileProvider failed", t)
            setShutterEnabled(true)
            return
        }
        Log.i(
            TAG,
            "handing $uri to ShareImageActivity ($note), hold " +
                "${rotationName(targetRotation)}"
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(InheritedOrientation.EXTRA_CAMERA_HOLD, targetRotation)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setClass(this@ProtoCameraActivity, ShareImageActivity::class.java)
        }
        startActivity(send)
        // Deliberately NOT finishing: leaving this activity on the back stack is
        // what makes the results view return HERE rather than to the main
        // activity when it closes. The camera rebinds its preview on resume.
    }

    companion object {
        private const val TAG = "ProtoCamera"
        private const val CAPTURE_DIR_NAME = "proto-camera"
        /** Side of the square shutter button, in dp. One knob for its size — and the
         *  square it is a side of is the footprint of the OCR-button graphic. */
        private const val CAPTURE_BUTTON_DP = 84f

        /**
         * PORTRAIT's margins for those two squares, in RAW PIXELS — the values they
         * were first placed with. Deliberately not converted to dp: portrait has
         * been judged in the hand on a real phone, and a unit change would move both
         * controls on that phone for no reason. The corner stack's margins below are
         * dp, and that is the whole point of the split: this pair is frozen.
         */
        private const val CONTROL_BOTTOM_MARGIN_PX = 48

        /** The framing control's gap from the right edge in portrait (see above). */
        private const val ZOOM_END_MARGIN_PX = 24

        /**
         * LANDSCAPE's margins, in dp: new layout, so written in the unit that holds
         * up on a density this layout has never been seen at. Off the right edge for
         * both controls, and off the bottom for the framing control. The system-bar
         * insets are added on top of these (see [applyControlAnchors]).
         *
         * 16dp on both is also the number that keeps the two controls apart in the
         * WORST landscape the bars can produce. A vertically centred 84dp shutter on
         * a 1080px-tall window (1080x2400 at density 2.625 — this app's device) ends
         * 650px down, leaving ~430px below it. The framing control needs its own
         * 221px plus its bottom margin plus, on a three-button device, a 48dp
         * navigation bar along that bottom edge: 126px. At a 24dp margin that is
         * 126 + 63 + 221 = 410 of the 430, i.e. a 5dp gap; at 16dp it is 389, i.e.
         * ~15dp. Neither overlaps, but the smaller margin is the one with room to
         * spare when a device reports a taller bar. (The layout assumes the window
         * is at least ~2.2 shutter-sides tall, which any phone in a landscape hold
         * is; in a very short free-form window the two would meet.)
         */
        private const val LANDSCAPE_EDGE_MARGIN_DP = 16f

        /** What the shutter says to a screen reader, now that it has no label. */
        private const val SHUTTER_DESCRIPTION = "Capture"

        /** The shutter's own alpha, enabled and disabled: the graphic does not dim itself. */
        private const val SHUTTER_ALPHA_ENABLED = 1f
        private const val SHUTTER_ALPHA_DISABLED = 0.4f

        /**
         * The corner control: the same 52dp chrome size and glyph treatment the
         * share activity's rotate pair uses, and the glyph is a text arrow rather
         * than a drawable — a new bundled asset would have to satisfy the app's
         * licence index (see app/licenses/components.tsv), a glyph costs nothing.
         */
        private const val BACK_BUTTON_DP = 52
        /** U+2190 LEFT ARROW. */
        private const val BACK_GLYPH = "\u2190"
        private const val BACK_GLYPH_TEXT_SIZE_SP = 24f
        private const val BACK_BUTTON_RADIUS_DP = 10f
        private val BACK_GLYPH_COLOR = Color.parseColor("#00FFFF")
        private val BACK_BUTTON_FILL = Color.argb(130, 25, 25, 25)
        private val BACK_BUTTON_STROKE = Color.argb(150, 0, 255, 255)

        /** Off the corner, so the back control clears the system bars. */
        private const val CORNER_MARGIN_DP = 12f

        /**
         * The long side, in pixels, of the frame the FILL_CENTER crop is taken
         * from — 2048 turns the 4032-px capture into a 2016-px decode, which is
         * ~12 MB of ARGB instead of ~48 MB. The single knob for the memory bound
         * and, with it, the size the recogniser is fed.
         */
        private const val CROP_LONG_SIDE_PX = 2048

        /** High on purpose: this JPEG is the recogniser's input, not a thumbnail. */
        private const val CROP_JPEG_QUALITY = 95

        /** The FileProvider authority suffix; the manifest declares `${applicationId}.protofileprovider`. */
        const val FILE_PROVIDER_SUFFIX = ".protofileprovider"
    }
}
