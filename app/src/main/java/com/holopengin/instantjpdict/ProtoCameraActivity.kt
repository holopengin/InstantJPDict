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
import android.widget.TextView
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
 *  - The CONTROLS FOLLOW THE HOLD TOO, from the same value the camera is told.
 *    Portrait is the layout this prototype has always had; landscape moves the
 *    shutter and the framing control to the window's RIGHT edge — the short edge,
 *    where a thumb sits when the phone is held sideways — instead of leaving a
 *    bottom-row layout running along a long edge. See [applyControlAnchors] for
 *    the anchors and [initialHoldRotation] for why a cold start into landscape
 *    comes up right without waiting for a turn.
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
 *    any failure hands over the ORIGINAL file and says so in the status line
 *    and the log rather than losing the capture.
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
     * hold, false for portrait — the state [applyControlAnchors] reads, and the
     * gate that keeps a sensor reporting every degree of tilt from re-writing two
     * sets of LayoutParams per callback. Seeded in [onCreate] from the hold the
     * window came up in (see [initialHoldRotation]) so the first frame is right.
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
    private lateinit var statusView: TextView
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

        // The top-left corner, as ONE stack: the back control in the corner with the
        // status line under it, so neither collides with the other. The window is
        // edge-to-edge (targetSdk 35 on Android 15+ forces it) and the preview is
        // deliberately full-bleed, so the insets are taken by this stack rather than
        // by the root: without them the button's top slice sits under the status bar
        // and the system takes the touches.
        statusView = TextView(this).apply {
            tag = "proto_status"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 12f
            text = "PROTOTYPE #78 — camera mode"
        }
        val corner = LinearLayout(this).apply {
            tag = "proto_corner"
            orientation = LinearLayout.VERTICAL
        }
        val backSide = (BACK_BUTTON_DP * resources.displayMetrics.density).roundToInt()
        corner.addView(backButton(), LinearLayout.LayoutParams(backSide, backSide))
        corner.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (STATUS_GAP_DP * resources.displayMetrics.density).roundToInt() }
        )
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
            if (bars.right != systemBarRight || bars.bottom != systemBarBottom) {
                systemBarRight = bars.right
                systemBarBottom = bars.bottom
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

        // Which way up the window came up, BEFORE it is first laid out, and the
        // controls are anchored for it in the same breath — that is what makes a
        // cold start into landscape come up with the controls already on the right
        // edge instead of waiting for the sensor to report a turn that never comes.
        // See [initialHoldRotation].
        controlsLandscape = isLandscapeHold(initialHoldRotation())
        applyControlAnchors()

        setContentView(root)

        // The sensor, built here and enabled in onResume. A callback fires for
        // every degree of tilt, so [targetRotation] is what keeps this cheap: only
        // an actual quarter turn reaches the camera.
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = surfaceRotationFor(orientation)
                // The controls' anchors come off the SAME value the camera is told,
                // decided here and not behind the gate below, because the two care
                // about different things: the camera only wants to hear about a
                // change ([targetRotation] is what keeps a sensor reporting every
                // degree of tilt from re-telling it a rotation it already has), while
                // the anchors only care about portrait-versus-landscape. One value,
                // two questions — so they cannot disagree about which way is up.
                val landscape = isLandscapeHold(rotation)
                if (landscape != controlsLandscape) {
                    controlsLandscape = landscape
                    applyControlAnchors()
                }
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
                if (::statusView.isInitialized) statusView.text = statusText()
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
     * The rotation of what the camera writes, from the phone's own orientation.
     *
     * The bands are CameraX's own (androidx.camera.view.RotationProvider
     * .orientationToSurfaceRotation, camera-view 1.4.2 — the mapping
     * LifecycleCameraController's device-rotation handling is built on), so a
     * quarter turn here means the same thing it means to CameraX: each band is
     * 90 degrees wide around a cardinal hold, and everything else — including
     * OrientationEventListener.ORIENTATION_UNKNOWN, which arrives as -1 while the
     * phone is flat or is being moved — reads as portrait.
     *
     * | orientation (degrees) | targetRotation      |
     * | 45..134               | ROTATION_270        |
     * | 135..224              | ROTATION_180        |
     * | 225..314              | ROTATION_90         |
     * | else (incl. -1)       | ROTATION_0          |
     */
    private fun surfaceRotationFor(orientation: Int): Int = when {
        orientation in 45..134 -> Surface.ROTATION_270
        orientation in 135..224 -> Surface.ROTATION_180
        orientation in 225..314 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

    /**
     * Which way up the window is, from a surface rotation.
     *
     * [surfaceRotationFor] is the only place a hold becomes a rotation and this is
     * the only place a rotation becomes portrait-or-landscape, so the controls
     * cannot disagree with the camera stream about which way is up: both ask about
     * the same number. Held upside down (ROTATION_180) counts as portrait, and it
     * is the right answer there — the whole window turns with the phone, so its
     * bottom edge is still the one under the thumb.
     */
    private fun isLandscapeHold(rotation: Int): Boolean =
        rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

    /**
     * The hold the window came up in, expressed as the rotation [isLandscapeHold]
     * reads — the cold start's answer, and the reason it is taken here instead of
     * waited for.
     *
     * The trap this avoids: a layout that only re-anchors when the sensor reports a
     * CHANGE is still portrait-anchored when the app is launched with the phone
     * already sideways, because nothing changes after launch. `resources` is this
     * activity's own configuration as it was created with, and for a `fullSensor`
     * activity that configuration IS the orientation the system chose for the
     * launch, from the same sensor [orientationListener] reads — so the two cannot
     * disagree about the hold except while the phone is literally mid-turn, and the
     * sensor's first callback, which arrives as soon as the listener is enabled on
     * resume, re-anchors if it disagrees with what was assumed here.
     *
     * Only the landscape/portrait distinction is taken from it; WHICH quarter turn
     * it is does not matter to a layout anchored to the right edge, which is why
     * landscape answers ROTATION_90 here without asking whether the display is
     * really at 90 or 270.
     */
    private fun initialHoldRotation(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Surface.ROTATION_90
        } else {
            Surface.ROTATION_0
        }

    /**
     * Where the shutter and the framing control sit, for the hold on screen.
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
     *     1080px-tall landscape window leaves there, which is where the margin
     *     constant's 16dp comes from rather than from taste — see
     *     [LANDSCAPE_ZOOM_BOTTOM_MARGIN_DP].
     * The right system-bar inset is added to both, and the bottom one to the framing
     * control, because in landscape the navigation bar lies along a long edge — the
     * edge these two now hug — which is the same reason the corner stack takes
     * insets in the first place.
     *
     * This is the ONLY place either control's LayoutParams are written, so a turn
     * and an inset change cannot half-update the layout, and the params are rebuilt
     * rather than mutated so no margin can survive from an anchor it no longer
     * belongs to (a `marginEnd` left behind on a `CENTER_HORIZONTAL` control shifts
     * it off centre).
     */
    private fun applyControlAnchors() {
        if (!::captureButton.isInitialized || !::zoomButton.isInitialized) return
        val density = resources.displayMetrics.density
        val side = (CAPTURE_BUTTON_DP * density).roundToInt()
        if (controlsLandscape) {
            val edge = (LANDSCAPE_EDGE_MARGIN_DP * density).roundToInt() + systemBarRight
            val floor = (LANDSCAPE_ZOOM_BOTTOM_MARGIN_DP * density).roundToInt() + systemBarBottom
            placeControl(captureButton, side, Gravity.END or Gravity.CENTER_VERTICAL, edge, 0)
            placeControl(zoomButton, side, Gravity.END or Gravity.BOTTOM, edge, floor)
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
                "landscape (right edge), insets ${systemBarRight}/${systemBarBottom}"
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
    private fun placeControl(view: View, side: Int, anchor: Int, endMargin: Int, bottomMargin: Int) {
        view.layoutParams = FrameLayout.LayoutParams(side, side).apply {
            gravity = anchor
            marginEnd = endMargin
            this.bottomMargin = bottomMargin
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

    /** The value's own name, so the status line and the log can be read off together. */
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
                statusView.text = statusText()
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
                statusView.text = "PROTOTYPE #78 — camera failed: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * The Zoom button: FIT_CENTER (the whole captured frame, letterboxed into
     * the portrait view, and the whole frame goes to the model) <->
     * FILL_CENTER (screen filled by the middle slice of the frame, and that
     * slice is what goes to the model — see [cropToPreviewFraming]). The status
     * line names the active framing, so the in-hand verdict can be written down
     * without having to remember which one was on screen, and the framing is
     * logged for the same reason.
     */
    private fun togglePreviewFraming() {
        previewFill = !previewFill
        previewView.scaleType = if (previewFill) {
            PreviewView.ScaleType.FILL_CENTER
        } else {
            PreviewView.ScaleType.FIT_CENTER
        }
        statusView.text = statusText()
        Log.i(TAG, "preview framing = ${if (previewFill) "FILL_CENTER" else "FIT_CENTER"}")
    }

    private fun statusText(): String = "PROTOTYPE #78 — camera targetRotation: " +
        "${rotationName(targetRotation)}; framing: ${if (previewFill) {
            "FILL_CENTER (screen filled; the photo sent is this crop)"
        } else {
            "FIT_CENTER (whole photo visible, and sent whole)"
        }}. Line the text up on the crossing point; the Zoom button switches framing."

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
        statusView.text = "PROTOTYPE #78 — capturing..."
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
                    statusView.text = "PROTOTYPE #78 — capture failed: ${exception.message}"
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
     * heap — falls back to [source] and says so on the status line and in the
     * log. A photo one framing too wide is a worse answer than no answer, but a
     * far better one than a lost capture.
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
     * [frameH] UPRIGHT frame's pixels.
     *
     * FILL_CENTER scales the frame up until it covers a view of [viewAspect] and
     * lets the overflow fall off both sides, so the visible region is the
     * centred rect with the VIEW's aspect: full height and a narrower width on
     * this phone (a 0.45-ish view against a 0.75 frame), or full width and a
     * shorter height if the view ever came out the wider one. Centred, so the
     * frame's centre — and with it the reticle's crossing point — stays inside
     * the crop, which is what keeps the crosshair meaning the same thing in both
     * framings.
     */
    private fun cropRect(frameW: Int, frameH: Int, viewAspect: Float): Rect {
        if (frameW <= 0 || frameH <= 0 || viewAspect <= 0f) return Rect(0, 0, frameW, frameH)
        val frameAspect = frameW.toFloat() / frameH.toFloat()
        return if (viewAspect >= frameAspect) {
            // The view is the wider shape: the frame covers it from edge to edge and
            // the TOP AND BOTTOM fall off.
            val height = (frameW / viewAspect).roundToInt().coerceIn(1, frameH)
            val top = (frameH - height) / 2
            Rect(0, top, frameW, top + height)
        } else {
            // The view is the taller shape (this device): full height, the SIDES fall off.
            val width = (frameH * viewAspect).roundToInt().coerceIn(1, frameW)
            val left = (frameW - width) / 2
            Rect(left, 0, left + width, frameH)
        }
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
     * entry, and [note] on the status line — which is left standing when the
     * results view closes, so the framing that was actually recognised can be
     * read off the screen afterwards.
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
            statusView.text = "PROTOTYPE #78 — FileProvider failed: ${t.message}"
            setShutterEnabled(true)
            return
        }
        Log.i(TAG, "handing $uri to ShareImageActivity ($note)")
        statusView.text = "PROTOTYPE #78 — $note"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
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
        private const val LANDSCAPE_ZOOM_BOTTOM_MARGIN_DP = 16f

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

        /** Between the back control and the status line under it, and off the corner. */
        private const val STATUS_GAP_DP = 8f
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
