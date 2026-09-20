package com.holopengin.instantjpdict

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * #57 (Feat — image share intent): receive a single shared image and OCR it
 * through exactly the same surface as the accessibility overlay.
 *
 * The activity does no overlay code of its own. It decodes the shared image
 * (honouring EXIF orientation, which the screenshot path never needed — a
 * camera JPEG arrives rotated) into a bitmap the size of the overlay view, then
 * hands it to [OcrOverlayView], which runs the shared detect/recognise render
 * pass and owns the boxes, lookup, popup and close behaviour.
 *
 * Exit: a tap on empty space, the system back key or gesture, the swipe down from
 * the status strip, or the #78 back control in the top-left corner. All of them
 * reach [OcrOverlayView]'s single close path; when there is
 * no layer left to close it calls [dismissOverlay] and this activity finishes.
 *
 * #78 ask: the view's own floating close button (a second logo button drawn over
 * the image) is NOT drawn in this host — the back control covers it and one thing
 * more. See [closeButtonOrigin].
 *
 * #57 rotation follow-up: two rotate buttons (⟳ / ⟲) are this activity's own
 * chrome, but they are placed in [OcrOverlayView]'s host-chrome layer
 * ([OcrOverlayView.addHostChrome]) rather than beside it, so they render UNDER
 * the dictionary panel the view opens and above the image, without the
 * activity reaching into the view's children — and the accessibility service,
 * the view's other host, is unchanged. Each press turns the image a quarter
 * turn clockwise or counterclockwise and re-runs OCR on the rotated composite.
 * The geometry that keeps the boxes and tap targets aligned lives in
 * [ImageRotation], which is plain Kotlin and unit-tested; the
 * presses-into-one-pass bookkeeping lives in [RotationQueue], likewise.
 *
 * ACTION_SEND_MULTIPLE is a deliberate later follow-up — only ACTION_SEND is
 * handled here.
 *
 * #78 follow-up: a back control sits in the top-left corner and does exactly what
 * the system back button does — one press through
 * [androidx.activity.OnBackPressedDispatcher.onBackPressed], the same entry the
 * platform's own back reaches. Back already has behaviour here beyond finishing
 * (it closes one layer at a time, so closing the results from the camera flow
 * returns to the camera), so the control goes through the dispatcher's callback
 * rather than calling `finish()`; the callback is registered in [onCreate].
 *
 * #78 follow-up (orientation): opened FROM THE VIEWFINDER, this activity comes up
 * the way the camera was held — DECLARED, not requested: its manifest entry carries
 * `android:screenOrientation="fullSensor"`, the same value [ProtoCameraActivity]
 * declares for itself, so the FIRST layout is already the device's hold and no
 * post-creation quarter turn follows. `fullSensor` FOLLOWS the device rather than
 * pinning a family, so the exported `ACTION_SEND` entry point is not pinned either.
 *
 * The runtime request this replaces is gone: [onCreate] used to ask the window
 * manager for FULL_SENSOR from the camera's
 * [InheritedOrientation.EXTRA_CAMERA_HOLD], and that request was resolving AFTER
 * the first layout — the activity was laid out in the launch orientation and then
 * re-created when it landed, which is the "starts portrait, then it rotates" the
 * maintainer saw, with this activity's whole composite rebuilt on the re-creation.
 * The extra is still read, but only to LOG the handoff ([logCameraHold]): it is no
 * longer a second writer of any window property, so nothing can disagree with the
 * manifest.
 *
 * #78 follow-up (a LATER quarter turn): "it still re-runs the inference when
 * rotating the phone while the OCR view is open." It did, and the reason was the
 * deliberate absence of `configChanges`: a turn re-created this activity, which
 * rebuilt the view, the engine and the whole detect/recognise pass on an image
 * that had not changed. `configChanges` is now declared (see the manifest) and
 * [onConfigurationChanged] handles the turn in place:
 *  - the OCR run is KEPT. The engine, the overlay view, the composite and the
 *    recognised lines all survive the turn — the whole point;
 *  - the composite is REBUILT at the new container size from [baseImage] (which is
 *    stored unrotated for the activity's life) at the SAME number of quarter turns
 *    — a turn of the phone is not a turn of the picture;
 *  - every box coordinate is carried into the new pixel space by
 *    [ImageShareFit.refit], the one transform that matches how the image itself
 *    was placed in each container, so the boxes stay on their glyphs
 *    ([OcrOverlayView.refitContent] re-renders them from the kept results).
 * The system-share path is untouched, and so is the deferred-close crash fix in
 * [onDestroy]: with no re-creation on a turn there is no torn-down engine to race
 * a native pass.
 *
 * The landscape chrome is this activity's own and needed no new anchoring: the
 * back control goes through [applySystemBarInsets], which writes all four
 * margins, so in landscape it is still clear of the status bar on the top edge
 * and of a navigation bar that has moved to a long edge; the rotate pair takes
 * the same treatment in the bottom-left, and the insets are re-dispatched when
 * the bars move. The dictionary/lookup panel and the confidence controls are
 * [OcrOverlayView]'s and are untouched.
 */
class ShareImageActivity : AppCompatActivity(), OcrOverlayView.Host {

    /** Overlay-scoped work: the image decode, the shared environment loads and
     *  the OCR run. Cancelled in [onDestroy]. */
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var engine: OcrEngine

    /**
     * A8/#86: the engine is constructed on `Dispatchers.IO` now (it copies model
     * assets and loads two native nets), so this is the construction in flight.
     * [onDestroy] waits on it before applying the pass-aware close, so an engine
     * that finishes building while the activity is tearing down is still closed
     * rather than leaked.
     */
    private var engineConstruction: Job? = null
    private val overlayState = OcrOverlayStateController()

    /**
     * The decoded, EXIF-upright image, kept UNROTATED for as long as the
     * activity lives: every rotate press re-derives the composite from this one
     * bitmap, so quarter turns never compound resampling, and a fourth press
     * returns to the original pixels.
     */
    private var baseImage: Bitmap? = null

    /** The composed image, exactly the size of the overlay view (OCR input and
     *  what the view displays). Replaced by the rotate pump once the pass that
     *  was reading the previous one has finished; the replaced bitmap is
     *  deliberately NOT recycled (see [RotationQueue] and [onDestroy]). */
    private var image: Bitmap? = null

    /** Every rotate press, coalesced: what is on screen plus what is queued
     *  behind the pass in flight. Four presses still return to the original
     *  orientation, and N presses while a pass runs cost ONE pass. */
    private var rotations = RotationQueue.State.IDLE

    /** Quarter turns actually composed into [image] and on screen. The pump's
     *  work test: a press from rest sets `turns` directly, a press during a pass
     *  accumulates in `queued` and `passFinished` folds those into `turns`, so
     *  comparing `turns` with what has been composed covers both. `running`
     *  alone does not — a press from rest leaves `queued` at zero.
     *
     *  `turns` wrapping means a burst of four from rest is correctly a no-op:
     *  the orientation it asks for is the one already composed. */
    private var composedTurns = 0

    /** The one pump that walks [rotations]; null when no rotation is pending.
     *  At most one detect/recognise pass is in flight at any time, which is
     *  what keeps a burst of presses from stacking multi-megabyte allocations. */
    private var rotatePump: Job? = null

    /** The detect/recognise pass the view is running, if any. A rotation waits
     *  for this to finish rather than cancelling it: cancellation is
     *  cooperative and the detect call is native, so a "cancelled" pass would
     *  keep reading the composite it was given. */
    private var currentPass: Job? = null

    /** The display pump: turns the image on press, independent of the OCR. Null
     *  when no turn is pending. */
    private var rotateDisplayJob: Job? = null

    /** Quarter turns the last OCR pass actually read. The pass's own work test:
     *  re-OCR only when the display has moved to an orientation it has not read
     *  yet. Wraps at 4, so four turns correctly need no pass. */
    private var lastOcrTurns = 0

    /** The container size the composite is built at — the same pixels the
     *  overlay view's box coordinates are expressed in. */
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /**
     * The activity's root container, kept so [onConfigurationChanged] can read the
     * NEW container size off it. The composite has to be composed at whatever size
     * the container actually is (the 1:1 mapping the boxes rely on), and after a
     * turn that size only exists once the window has been laid out again — which
     * is not yet true inside `onConfigurationChanged`. See [refitForNewContainer].
     */
    private var containerView: FrameLayout? = null

    /**
     * The layout wait [refitForNewContainer] has registered, if one is in flight.
     *
     * Kept as state so it can be released from every way the wait can end — the layout
     * that carries the new container size, a later turn superseding it, and the
     * activity going away ([onDestroy]) — rather than only from the branch inside the
     * listener that gets as far as re-fitting. A listener left registered would fire on
     * some later, unrelated layout (an IME resize, a panel) and re-fit the composite
     * against a size the current configuration does not report.
     */
    private var refitLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private var overlayView: OcrOverlayView? = null

    /** The activity's own rotate pair, placed in the overlay view's chrome
     *  layer once the view exists (see [OcrOverlayView.addHostChrome]), so it
     *  renders under the dictionary panel but stays tappable when none is up. */
    private var rotateBar: View? = null

    /** The top-left back control. Placed in the view's chrome layer beside the
     *  rotate pair for the same reason: a sibling of the view sits under its
     *  full-screen surface and would never see a press. */
    private var backButton: View? = null

    // ---- OcrOverlayView.Host ----

    override val bitmap: Bitmap
        get() = image ?: error("overlay requested before the image was decoded")
    override val ocrEngine: OcrEngine get() = engine
    override val controller: OcrOverlayStateController get() = overlayState

    override fun dismissOverlay() {
        finish()
    }

    override fun requestSoftInputResize() {
        // The overlay's manual-input IME handling is a no-op here: an activity
        // already resizes its own window.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /**
     * #78: null — this host wants NO floating close button, so the overlay draws
     * none (see [OcrOverlayView.addCloseButtonFor]).
     *
     * There is no floating button to sit under here, which is why this corner was
     * the button's. Since #78 the top-left corner belongs to the back control, and
     * that control does everything the close button did and one thing more: it goes
     * through the back dispatcher, so it closes the dictionary panel first and the
     * whole view second — the same order as the system back key and the empty-space
     * tap. What was left was a second, weaker exit affordance drawn with the app's
     * OCR-logo graphic floating over the image (indistinguishable from the
     * accessibility service's own floating trigger, which is the same drawable), and
     * that is the control the maintainer asked to lose from this view.
     *
     * The exits are unaffected: the top-left back control, the system back key or
     * gesture, a tap on empty space, and the swipe down from the status strip. The
     * accessibility overlay's own close button is untouched — the service passes a
     * position, and there it is the visible half of the floating trigger.
     */
    override fun closeButtonOrigin(): Pair<Int, Int>? = null

    /** No floating button to keep in step. */
    override fun onCloseButtonMoved(x: Int, y: Int) {}

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The orientation is DECLARED in the manifest (`fullSensor`), so the
        // window this activity is created with is already the device's hold and
        // nothing here asks the window manager for anything — see [logCameraHold]
        // for why the camera's extra is still read, and the class doc for why the
        // runtime request it used to make was the "starts portrait, then it
        // rotates" the maintainer saw.
        logCameraHold(intent)

        OverlayEnvironment.prepare(this, overlayState, overlayScope)

        // Back owns the layer-by-layer close, and the #78 back control calls this
        // same path: one definition of back for the key, the gesture and the
        // button. With nothing of ours up yet (the image is still decoding) the
        // press is handed to the default path — what the old `super.onBackPressed()`
        // did — rather than being swallowed or finishing directly.
        //
        // A dispatcher callback and NOT an `onBackPressed` override: the override
        // this replaced was bypassed by `onBackPressedDispatcher.onBackPressed()`
        // (the dispatcher's fallback runs the framework's own
        // `Activity.onBackPressed`, not a subclass override — checked against
        // activity 1.8.0), which would have finished the activity outright and
        // skipped the close, losing the return-to-camera behaviour.
        val backHandler = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = overlayView
                if (view == null) {
                    // The canonical "do whatever the default is" idiom: disable,
                    // re-dispatch this same press, re-enable.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                } else {
                    view.handleBackKey()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backHandler)

        // Fill the display like the overlay window does, so the bitmap the view
        // is given maps 1:1 onto its own pixels (the overlay's box coordinates
        // are bitmap pixels; any scale factor between the two would misalign
        // every hit rect).
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val container = FrameLayout(this)
        containerView = container
        setContentView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val uri = sharedImageUri(intent)
        if (uri == null) {
            Toast.makeText(this, "No image to read", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Bottom-left: the view's draggable close button starts in the top-left
        // (`closeButtonOrigin`), the status strip owns the top edge, and the
        // confidence controls run down the left at vertical centre — so this is
        // the one corner where the pair collides with nothing. It is added to
        // this container first so it is visible while the image decodes, then
        // moved into the overlay view's chrome layer once the view exists: that
        // layer renders under the dictionary panel but above the image, which a
        // sibling here could never do (it would sit above the whole surface,
        // panel included).
        rotateBar = buildRotateBar()
        val barMargin = (ROTATE_BAR_MARGIN_DP * resources.displayMetrics.density).roundToInt()
        container.addView(
            rotateBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                // Until the window insets land, keep the pair off the very edge.
                leftMargin = barMargin
                bottomMargin = barMargin
            }
        )

        // The back control, in the top-left corner, built from the same chrome as
        // the rotate pair. Added to this container first so it is visible while
        // the image decodes, then moved into the overlay view's chrome layer with
        // the pair — see [moveIntoChrome].
        val backSide = (BACK_BUTTON_DP * resources.displayMetrics.density).roundToInt()
        val backMargin = (BACK_BUTTON_MARGIN_DP * resources.displayMetrics.density).roundToInt()
        backButton = chromeButton(BACK_BUTTON_TAG, BACK_GLYPH, "Back", backSide) {
            onBackPressedDispatcher.onBackPressed()
        }
        container.addView(
            backButton,
            FrameLayout.LayoutParams(backSide, backSide).apply {
                gravity = Gravity.TOP or Gravity.START
                // Until the window insets land, keep it off the very edge.
                leftMargin = backMargin
                topMargin = backMargin
            }
        )

        // The window lays out under the system bars (FLAG_LAYOUT_NO_LIMITS), so
        // keep both corner controls clear of the bars whatever their shape.
        applySystemBarInsets(rotateBar!!, ROTATE_BAR_MARGIN_DP)
        applySystemBarInsets(backButton!!, BACK_BUTTON_MARGIN_DP)

        // Compose at the container's own size, so the image the view receives is
        // exactly the size of the view.
        container.post {
            surfaceWidth = container.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            surfaceHeight = container.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            overlayScope.launch {
                // A8/#86: the engine is built here, off the main thread. Its constructor
                // copies the model assets (once per installed APK now, see
                // [materialiseModelAsset]) and loads two native nets synchronously; on
                // Main that was startup jank on a screen whose first frame is already
                // behind the image decode. Nothing reads it before the pass this same
                // coroutine starts, so it cannot be observed half-built. [onDestroy]
                // waits for this construction before closing, so a build that outlives
                // the activity is not leaked.
                engineConstruction = launch(Dispatchers.IO) {
                    // #100: the viewfinder handoff and a shared image get their
                    // own furigana switches; the extra is the marker that tells
                    // them apart (see logCameraHold).
                    val fromCamera = intent?.getIntExtra(
                        InheritedOrientation.EXTRA_CAMERA_HOLD, InheritedOrientation.NO_HOLD
                    ) != InheritedOrientation.NO_HOLD
                    engine = OcrEngine(
                        this@ShareImageActivity,
                        if (fromCamera) OcrEngine.PREF_DET_FURIGANA_CAMERA
                        else OcrEngine.PREF_DET_FURIGANA_SCREEN,
                    )
                }
                engineConstruction?.join()
                val base = withContext(Dispatchers.IO) { decodeOriented(uri, surfaceWidth, surfaceHeight) }
                if (isFinishing || isDestroyed) {
                    base?.recycle()
                    return@launch
                }
                if (base == null) {
                    Toast.makeText(this@ShareImageActivity, "Could not read image", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                baseImage = base
                // The container can have changed size while the decode ran — a turn
                // during those few hundred milliseconds, which is one of the things
                // this activity no longer being re-created makes possible — so the
                // composite is composed at the size the container has NOW, and the
                // sizes the boxes will be expressed in are updated with it. The
                // box coordinates are composite pixels; they may not be composed at
                // a size the container no longer has.
                val width = container.width.takeIf { it > 0 } ?: surfaceWidth
                val height = container.height.takeIf { it > 0 } ?: surfaceHeight
                surfaceWidth = width
                surfaceHeight = height
                val composed = withContext(Dispatchers.IO) {
                    composeForScreen(base, width, height, rotations.turns)
                }
                if (isFinishing || isDestroyed) {
                    composed?.recycle()
                    return@launch
                }
                if (composed == null) {
                    Toast.makeText(this@ShareImageActivity, "Could not read image", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                image = composed
                val view = OcrOverlayView(this@ShareImageActivity, this@ShareImageActivity)
                overlayView = view
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                // The rotate pair and the back control move into the view's chrome
                // layer, keeping their own gravity and margins: under the
                // dictionary panel, above the image, and — because the layer
                // hit-tests its children — still taking their own presses.
                rotateBar?.let { moveIntoChrome(view, it) }
                backButton?.let { moveIntoChrome(view, it) }
                // The first pass is a pass too: a press while it runs queues
                // behind it instead of starting a second, overlapping one.
                currentPass = view.startOcr()
                rotations = RotationQueue.passStarted(rotations)
                rotatePump = overlayScope.launch { runRotations() }
            }
        }
    }

    /**
     * Back handling lives in the dispatcher callback registered in [onCreate] —
     * both the system back button and the #78 back control arrive there, so
     * there is one definition of what back does. (This used to be a deprecated
     * `onBackPressed()` override, which the dispatcher bypasses.)
     */
    override fun onDestroy() {
        // Any pending layout wait is released first. The activity is going away, so no
        // re-fit can follow, and a listener left on the container's observer would keep
        // this object alive behind it.
        unregisterRefitLayoutListener()
        overlayView?.onClosed()
        overlayView = null
        overlayScope.cancel()
        // The nets must NOT be closed while a pass is inside them. This method's own note
        // below records why: `cancel()` stops a coroutine, not a native detect, so a pass
        // can still be reading what it was handed — and it is also still executing inside
        // ncnn. Closing there tears down a Net under a running convolution, which faults
        // inside ncnn ("pool allocator destroyed too early"; SIGSEGV at 0x0 on an OpenMP
        // worker, seen twice on the device when a re-creation landed mid-pass). The shared
        // [closeEngineBehindPass] defers the close onto the pass's own completion, which
        // cannot fire until its non-suspending native work has returned; with no pass in
        // flight there is nothing to wait for. See [EngineClose] for the crash note.
        //
        // A8/#86: the engine is constructed off the main thread now, so it can still be
        // building when this runs — and `cancel()` above cannot stop a constructor that
        // has no suspension points. Waiting for [engineConstruction] first is what closes
        // an engine the teardown outran, instead of leaking its nets for the process.
        val passInFlight = currentPass
        val construction = engineConstruction
        if (construction != null && !construction.isCompleted) {
            construction.invokeOnCompletion {
                if (::engine.isInitialized) closeEngineBehindPass(passInFlight) { engine.close() }
            }
        } else {
            closeEngineBehindPass(passInFlight) { if (::engine.isInitialized) engine.close() }
        }
        super.onDestroy()
        // The composed and base bitmaps are deliberately neither recycled nor
        // nulled — here or as the rotate pump replaces them.
        //
        // `cancel()` stops a coroutine, not a native detect: a pass can still be
        // reading the composite it was handed, and the display ImageView may
        // hold that very bitmap (the strip treatment copies it in only some
        // modes). An eager recycle under a live pass is exactly what raised
        // "Bitmap is recycled", and nulling `image` would be no better: it is
        // the reference a live pass reads `host.bitmap` through, so it would
        // turn a finishing pass into a thrown error instead of letting it end.
        // Left alone, both die with this activity, which is unreachable once
        // its cancelled coroutines complete.
    }

    // ---- the window turning (handled here, not by a re-creation) ----

    /**
     * #78 (the second half of the report): "it still re-runs the inference when
     * rotating the phone while the OCR view is open."
     *
     * It did, because this activity did not declare `configChanges`: a quarter turn
     * re-created it, and a re-creation re-decodes, re-composes, re-creates the
     * overlay view, re-opens the engine and runs the whole detect/recognise pass
     * again — on an image the user had not changed. The manifest now declares
     * `configChanges` (see the comment there) and the turn lands here instead.
     * Nothing about the RUN is redone; what is redone is the geometry, because the
     * CONTAINER really did change size and the composite and its box coordinates
     * are expressed in the container's own pixels ([refitComposite]).
     *
     * The camera handoff and the system share sheet both reach this activity
     * through entries that declare `fullSensor`, so a turn is normal. The window's
     * own chrome (the back control, the rotate pair) needs nothing here: their
     * margins are written by [applySystemBarInsets]' insets listener, which the
     * window re-dispatches when the bars move to the new edges.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        Log.i(
            "ShareImageActivity",
            "window turned: ${familyName(landscape)} — keeping the OCR run and " +
                "re-fitting the composite (was ${surfaceWidth}x${surfaceHeight})"
        )
        refitForNewContainer()
    }

    /**
     * Watch for the layout that carries the new container size, then re-fit.
     *
     * The new size does not exist yet inside [onConfigurationChanged]: the window
     * is resized and laid out AFTER the callback returns, and the composite must be
     * composed at the container's real size or the 1:1 mapping every box relies on
     * is broken. So the layout is waited for — and the wait ends on the ONE shape of
     * "the window has been laid out again": the container has come to rest at the
     * size the new configuration reports. Any other size is one of the intermediate
     * layouts a turn produces while the rotation settles, so it is left alone; the
     * wait goes on, and the next layout that does reach the new screen size is the
     * one that re-fits. Taking an intermediate size here would compose the composite
     * (and express every box) at a size the window is about to leave, with the wait
     * already over and nothing left to fire when the real size arrived.
     *
     * Nothing to do when no composite exists yet (a turn during the decode): the
     * compose in [onCreate] reads the container's size at the moment it composes,
     * so it composes at the new size by itself.
     */
    private fun refitForNewContainer() {
        val container = containerView ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        // A wait left over from an earlier turn is SUPERSEDED, not stacked: two live
        // listeners would re-fit twice for one layout.
        unregisterRefitLayoutListener()
        val observer = container.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val width = container.width
                val height = container.height
                if (width <= 0 || height <= 0) return
                val metrics = resources.displayMetrics
                if (width != metrics.widthPixels || height != metrics.heightPixels) return
                // The wait is over here, whatever the re-fit then turns out to be —
                // another layout cannot supply what is missing (the overlay view or the
                // base image), so the listener is not left registered for one.
                unregisterRefitLayoutListener()
                refitComposite(width, height)
            }
        }
        refitLayoutListener = listener
        observer.addOnGlobalLayoutListener(listener)
    }

    /**
     * Release the layout wait [refitForNewContainer] registered, if one is. Called from
     * the layout that carries the new container size, from a turn that supersedes an
     * older wait, and from [onDestroy]; the listener is therefore never left on the
     * container's observer.
     */
    private fun unregisterRefitLayoutListener() {
        val listener = refitLayoutListener ?: return
        refitLayoutListener = null
        val observer = containerView?.viewTreeObserver ?: return
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
    }

    /**
     * The re-fit: rebuild the composite at the new container size, then move the
     * KEPT run's boxes into it.
     *
     * The image is re-composed from [baseImage] at the SAME number of quarter turns
     * ([composedTurns]) — a turn of the phone is not a turn of the picture, so the
     * content is not rotated here, the container around it simply changed shape and
     * [composeForScreen] re-fits it (uniform scale plus centring, the same
     * [ImageRotation.fitRotated] the first compose used). [ImageShareFit.refit]
     * takes the two placements and answers the one transform that carries a point
     * of the picture from the old composite's pixels to the new one's; that is the
     * transform [OcrOverlayView.refitContent] applies to every box, hit rect and
     * cursor, so the boxes stay on their glyphs.
     *
     * It runs behind whatever the rotation pumps are doing, and waits rather than
     * cancelling them: a pass in flight was handed the OLD composite and will paint
     * its boxes in the old pixel space, so the transform may not run until it has
     * finished — otherwise its late boxes would be moved twice or not at all. Once
     * `image` is the new composite, any pass started after that point reads the new
     * pixel space and is not transformed at all.
     *
     * The zoom/pan and the open lookup panel are dropped by
     * [OcrOverlayStateController.refitBoxes] with the transform, and why that is the
     * consistent choice is written down there.
     *
     * The RECORDED size moves with the composite and not before it: [surfaceWidth]
     * and [surfaceHeight] are written in the coroutine below, beside `image =`. The
     * compose is asynchronous, so recording them up front would leave a window in
     * which a second config change computes its own `before` placement — and this log
     * line reads its transform — against a size the composite in use does not have.
     * Recorded here, the fields always describe the composite on screen.
     */
    private fun refitComposite(width: Int, height: Int) {
        val view = overlayView ?: return
        val base = baseImage ?: return
        val oldWidth = surfaceWidth
        val oldHeight = surfaceHeight
        if (width == oldWidth && height == oldHeight) return
        val turns = composedTurns
        val before = ImageRotation.fitRotated(base.width, base.height, oldWidth, oldHeight, turns)
        val after = ImageRotation.fitRotated(base.width, base.height, width, height, turns)
        val refit = ImageShareFit.refit(before, after)
        Log.i(
            "ShareImageActivity",
            "re-fitting composite for ${width}x${height}: the picture was " +
                "${before.width}x${before.height} at ${before.left},${before.top} and " +
                "is now ${after.width}x${after.height} at ${after.left},${after.top}; " +
                "boxes scale ${refit.scaleX}x/${refit.scaleY}y, offset " +
                "${refit.offsetX},${refit.offsetY} (image ${base.width}x${base.height}, " +
                "turns $turns)"
        )
        overlayScope.launch {
            currentPass?.join()
            rotateDisplayJob?.join()
            rotatePump?.join()
            val recomposed = withContext(Dispatchers.IO) {
                composeForScreen(base, width, height, turns)
            }
            if (isFinishing || isDestroyed) {
                recomposed?.recycle()
                return@launch
            }
            if (recomposed == null) return@launch
            if (overlayView !== view) {
                recomposed.recycle()
                return@launch
            }
            // A2/#86: a press that landed while this compose ran has already moved
            // the display (the pump commits on the main dispatcher, like this), and
            // committing the captured turns here would clobber the new orientation
            // with the old composite while the queue kept asking for the new one —
            // wrong until the next press. Detect that after the suspend and re-run
            // instead of committing: the re-run's guard recomputes `before` from the
            // size still on screen and takes its turns from `composedTurns`, which
            // the press's pump has just updated. Nothing is recycled that the
            // display holds; only this never-shown composite is.
            if (composedTurns != turns || rotations.turns != turns) {
                recomposed.recycle()
                refitComposite(width, height)
                return@launch
            }
            image = recomposed
            surfaceWidth = width
            surfaceHeight = height
            composedTurns = turns
            view.refitContent(refit)
        }
    }

    // ---- orientation (the camera handoff) ----

    /**
     * #78 follow-up: "the ocr view does not inherit the camera view's orientation
     * but it should."
     *
     * The INHERITANCE is now the manifest's: this activity declares
     * `android:screenOrientation="fullSensor"`, the same value
     * [ProtoCameraActivity] declares for itself, so `fullSensor` resolves the
     * window from the same sensor the viewfinder's window came from and the FIRST
     * layout is already the hold the camera was in — for both entry points, the
     * viewfinder handoff and the exported `ACTION_SEND` filter, because
     * `fullSensor` follows the device instead of pinning it.
     *
     * This function used to be [adoptCameraHold], and it used to WRITE:
     * `requestedOrientation = requested` from the camera's
     * [InheritedOrientation.EXTRA_CAMERA_HOLD] extra. That writer is gone, and
     * deliberately — an orientation request only decides the window this activity
     * is laid out in if it lands before the first layout, and this one landed
     * after: the activity came up in the launch orientation and was then
     * re-created when the request resolved, i.e. the "starts portrait, then it
     * rotates" the maintainer saw, rebuilding this activity's whole composite on a
     * re-creation that no longer happens. A second writer that can disagree with
     * the manifest is worse than no writer, so there is not one.
     *
     * What is left is the diagnostic the in-hand check reads, and it is read-only:
     * one logcat line naming the hold the viewfinder handed over
     * ([InheritedOrientation.EXTRA_CAMERA_HOLD] — the same `Surface` rotation the
     * camera gave its use cases and read its control anchors from), the way up
     * this window actually came up in, and the value the manifest declares for it
     * ([InheritedOrientation.requestedOrientationFor], `fullSensor`). With the
     * declaration in place the window and the hold must agree on the first layout;
     * if they ever do not, this line says so without a second window property
     * being touched. Called from [onCreate] before anything is built, exactly
     * where the request used to be made.
     */
    private fun logCameraHold(intent: Intent?) {
        val hold = intent
            ?.getIntExtra(InheritedOrientation.EXTRA_CAMERA_HOLD, InheritedOrientation.NO_HOLD)
            ?: InheritedOrientation.NO_HOLD
        val declared = InheritedOrientation.requestedOrientationFor(hold) ?: return
        // The one line the in-hand check reads: the hold the viewfinder handed
        // over, the way up this window came up, and what the manifest declares.
        // A window in the hold's own family is the feature working; one in the
        // other family is a first layout the declaration did not reach.
        val createdLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        Log.i(
            "ShareImageActivity",
            "camera hold ${holdName(hold)}, window was ${familyName(createdLandscape)}, " +
                "manifest declares FULL_SENSOR ($declared)"
        )
    }

    /** The hold's own name, so a logcat line can be read without decoding an int. */
    private fun holdName(hold: Int): String = "$hold (${familyName(DeviceHold.isLandscapeHold(hold))})"

    private fun familyName(landscape: Boolean): String = if (landscape) "landscape" else "portrait"

    // ---- chrome (the rotate pair and the back control) ----

    /**
     * Lift [control] out of the activity's container and into the overlay view's
     * own chrome layer. A sibling of the view sits UNDER its full-screen surface
     * (which is MATCH_PARENT and consumes empty-space taps), so it would never
     * see a press; the view's chrome layer renders under the dictionary panel but
     * above the image, and hit-tests its own children.
     */
    private fun moveIntoChrome(view: OcrOverlayView, control: View) {
        (control.parent as? ViewGroup)?.removeView(control)
        view.addHostChrome(control)
    }

    /**
     * Keep a corner control clear of the system bars. The window lays out under
     * them ([WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS]), so every margin
     * is the control's own plus the bar's inset; the control's gravity decides
     * which of the four it actually uses.
     */
    private fun applySystemBarInsets(view: View, marginDp: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val base = (marginDp * resources.displayMetrics.density).roundToInt()
            v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = base + bars.left
                rightMargin = base + bars.right
                topMargin = base + bars.top
                bottomMargin = base + bars.bottom
            }
            insets
        }
    }

    /**
     * The two rotate buttons, in this activity's chrome rather than the shared
     * surface. One helper builds both, so the pair cannot drift apart.
     */
    private fun buildRotateBar(): View {
        val size = (ROTATE_BUTTON_DP * resources.displayMetrics.density).roundToInt()
        val gap = (ROTATE_BUTTON_GAP_DP * resources.displayMetrics.density).roundToInt()
        val bar = LinearLayout(this).apply {
            tag = "rotate_controls"
            orientation = LinearLayout.HORIZONTAL
            val pad = (ROTATE_BAR_TOUCH_PAD_DP * resources.displayMetrics.density).roundToInt()
            setPadding(pad, pad, pad, pad)
            // A near miss must not read as empty space. Without this the gap between the two
            // buttons, and the padding around them, fall through to the surface's tap-to-close,
            // so a thumb that lands next to ⟲ dismisses the results instead of doing nothing.
            // Consuming here keeps the bar and a thumb's width around it inert; empty space
            // anywhere else still closes exactly as before.
            setOnClickListener { }
        }
        val counter = chromeButton(
            "rotate_button_$ROTATE_CCW_GLYPH", ROTATE_CCW_GLYPH, "Rotate counterclockwise", size
        ) { rotate(clockwise = false) }
        val clockwise = chromeButton(
            "rotate_button_$ROTATE_CW_GLYPH", ROTATE_CW_GLYPH, "Rotate clockwise", size
        ) { rotate(clockwise = true) }
        bar.addView(counter, LinearLayout.LayoutParams(size, size).apply { rightMargin = gap })
        bar.addView(clockwise, LinearLayout.LayoutParams(size, size))
        return bar
    }

    /**
     * One chrome button: a text glyph on a dark fill with the app's cyan outline.
     * The rotate pair and the #78 back control are all built here, so the
     * activity's chrome cannot drift, and the [tag] is passed in rather than
     * derived from the glyph so the rotate pair keeps the tags it had.
     */
    private fun chromeButton(tag: String, label: String, description: String, sizePx: Int, onClick: () -> Unit): CenteredButton =
        CenteredButton(this).apply {
            this.tag = tag
            text = label
            contentDescription = description
            setTextColor(ROTATE_GLYPH_COLOR)
            textSize = ROTATE_GLYPH_TEXT_SIZE_SP
            includeFontPadding = false
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            background = chromeButtonBackground()
            setOnClickListener { onClick() }
        }

    /**
     * Dark fill with the app's cyan outline: readable over a bright photo and
     * over the overlay's dark scrim alike, which the logo drawable the other
     * buttons use is not (it is a glyph, not a text background).
     *
     * The fill and outline are deliberately semi-transparent so the image reads
     * through the button; the glyph stays opaque so it stays legible over a
     * bright photo. Values are
     * named so they are a one-line nudge on device.
     */
    private fun chromeButtonBackground(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ROTATE_BUTTON_RADIUS_DP * resources.displayMetrics.density
        setColor(ROTATE_BUTTON_FILL)
        setStroke((1.5f * resources.displayMetrics.density).roundToInt(), ROTATE_BUTTON_STROKE)
    }

    /**
     * One press. It never starts a pass of its own: with a pass in flight the
     * press only records the turn, and [runRotations] applies what accumulated
     * in ONE pass when that pass ends. N presses therefore cost one pass, not
     * N — which is what stops a burst from stacking view-sized composites
     * (≈10 MB each at 1080×2400) plus the detector's buffers faster than they
     * are released. That stacking was the OutOfMemoryError; recycling a
     * composite while the previous pass still read it was the "Bitmap is
     * recycled" error, and neither can happen while a pass is never overlapped.
     */
    private fun rotate(clockwise: Boolean) {
        rotations = RotationQueue.press(rotations, clockwise)
        // The turn is shown at once, whatever the OCR is doing: the display pump
        // composes RotationQueue.displayTurns (the net asked for, including any
        // presses already queued behind a running pass) and showImageWithoutResults
        // drops -- and so cancels -- the run it replaces. Only the next PASS is
        // coalesced: RotationQueue.press queues while one is in flight and
        // runRotations runs a single pass for the net once it ends.
        if (rotateDisplayJob?.isActive != true) {
            rotateDisplayJob = overlayScope.launch { runDisplayTurns() }
        }
        if (rotatePump?.isActive != true) {
            rotatePump = overlayScope.launch { runRotations() }
        }
    }

    /**
     * The display pump: compose the requested orientation and put it on screen as
     * soon as the user asks, with no OCR in the way. Coalesced like the pass pump —
     * a press while a compose is running only moves the target and the loop
     * re-checks, so a burst costs one compose per settled orientation.
     *
     * The previous run's results go with the old image ([showImageWithoutResults]);
     * boxes for the previous orientation would otherwise sit off their glyphs while
     * the new pass runs.
     */
    private suspend fun runDisplayTurns() {
        // The net the user asked for, not just the pass's own turn: a press that
        // landed behind a running pass is on screen immediately too, and the
        // running pass is cancelled by the showImageWithoutResults below.
        while (rotations.displayTurns != composedTurns) {
            val view = overlayView ?: break
            val base = baseImage ?: break
            val width = surfaceWidth
            val height = surfaceHeight
            if (width <= 0 || height <= 0) break

            val turns = rotations.displayTurns
            val recomposed = withContext(Dispatchers.IO) {
                composeForScreen(base, width, height, turns)
            }
            if (recomposed == null) {
                // Nothing was composed, so nothing on screen changed: park the queue at
                // what is displayed rather than leaving a turn marked in flight that
                // nothing will ever finish. A later press still starts a turn.
                rotations = RotationQueue.passAbandoned(rotations)
                break
            }
            if (isFinishing || isDestroyed) {
                recomposed.recycle()
                rotations = RotationQueue.passAbandoned(rotations)
                break
            }
            image = recomposed
            composedTurns = turns
            view.showImageWithoutResults()
            // The results belonged to the image just replaced. Park the "last pass"
            // marker at -1 so runRotations always runs one for what is now on
            // screen — even when the net orientation lands back on an orientation a
            // previous pass read (a quick one-way-then-back press), where comparing
            // orientations alone would wrongly decide the screen was already done
            // and leave it blank.
            lastOcrTurns = -1
        }
        rotateDisplayJob = null
    }

    /**
     * The single pump that walks [rotations]: wait for the pass in flight,
     * apply the accumulated turns in one pass, repeat while presses keep
     * arriving, then idle.
     *
     * Waiting — never cancelling — is the point. Cancellation is cooperative and
     * the detect call is native, so a "cancelled" pass would keep reading the
     * composite it was handed; only joining it proves the bitmap is free. Every
     * mutation here happens on the main dispatcher, so a press cannot interleave
     * between the state check and the loop's exit and be left stranded. The one
     * writer that deliberately suspends across its mutation is [refitComposite],
     * which is why it re-checks the queue after its compose and re-runs rather than
     * committing a turn a press has already superseded (A2/#86).
     *
     * The composite that is replaced is deliberately not recycled (see
     * [onDestroy]): it dies with the activity rather than under a live pass.
     */
    private suspend fun runRotations() {
        while (true) {
            currentPass?.join()
            // Let the display settle first: the pass must read the image that is
            // actually on screen, not one that is about to be replaced.
            rotateDisplayJob?.join()
            rotations = RotationQueue.passFinished(rotations)
            // #86/A2: work exists when the orientation ON SCREEN is not the one the
            // last pass read. Comparing `rotations.turns` alone missed two cases:
            // a press whose display turn already dropped the old run (so nothing
            // was on screen to compare against), and a net that landed back on the
            // orientation a previous pass read. The display pump shows every press
            // at once and parks [lastOcrTurns] at -1 when it replaces the image, so
            // comparing the SCREEN's orientation catches both.
            if (composedTurns == lastOcrTurns) break

            val view = overlayView ?: break
            if (surfaceWidth <= 0 || surfaceHeight <= 0) break
            lastOcrTurns = composedTurns
            // Mark the pass in flight BEFORE it starts, so a press that lands
            // while it runs queues behind it (the #57 coalescing) instead of
            // being taken for an idle press and starting a second, overlapping
            // pass. The initial pass does the same via passStarted().
            rotations = RotationQueue.passStarted(rotations)
            currentPass = view.startOcr()
        }
        rotatePump = null
    }

    // ---- image input ----

    private fun sharedImageUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    /**
     * Draw [base] turned by [turns] quarter turns clockwise, fit-centred onto a
     * [targetW] x [targetH] black canvas. The result is the exact size of the
     * overlay view, so OCR boxes and hit rects line up with what is on screen
     * whatever the orientation.
     */
    private fun composeForScreen(base: Bitmap, targetW: Int, targetH: Int, turns: Int): Bitmap? {
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        // Recomputed from the ROTATED dimensions, not the base ones: this is the
        // whole point of ImageRotation.fitRotated. Reusing the pre-rotation fit
        // would place the turned image at the wrong scale/offset and every box
        // drawn over it would sit off the glyph it belongs to.
        val fit = ImageRotation.fitRotated(base.width, base.height, targetW, targetH, turns)
        val dst = RectF(
            fit.left.toFloat(), fit.top.toFloat(),
            (fit.left + fit.width).toFloat(), (fit.top + fit.height).toFloat()
        )
        // #86: one draw, base -> output, with the turn folded into the matrix. The
        // old shape rotated the whole base into a second full-size bitmap and drew
        // that into `out`, so every quarter turn paid a filtered rotation of the
        // full base plus that bitmap's allocation: measured ~150 ms of
        // press-to-turn at the 90/270 orientations, against ~17 ms at 0/180. The
        // matrix folds rotate -> normalise to the rotated bounds' origin -> scale
        // to the fitted rect -> place at its offset, which is exactly what drawing
        // the pre-rotated bitmap into `dst` produced, with neither the extra
        // bitmap nor the extra pass.
        val matrix = Matrix().apply {
            postRotate(ImageRotation.degrees(turns))
            val rotatedBounds = RectF(0f, 0f, base.width.toFloat(), base.height.toFloat())
            mapRect(rotatedBounds)
            postTranslate(-rotatedBounds.left, -rotatedBounds.top)
            postScale(dst.width() / rotatedBounds.width(), dst.height() / rotatedBounds.height())
            postTranslate(dst.left, dst.top)
        }
        canvas.drawBitmap(base, matrix, paint)
        return out
    }

    /** Stream-decode via [android.content.ContentResolver]; the shared URI is a
     *  content URI, not a file path, so it is never opened as a file. */
    private fun decodeOriented(uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val orientation = readExifOrientation(uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = ImageShareFit.sampleSize(bounds.outWidth, bounds.outHeight, max(targetW, targetH))

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val correction = ExifOrientation.correction(orientation)
        if (correction.isIdentity) return decoded

        val matrix = Matrix()
        matrix.setRotate(correction.rotationDegrees.toFloat())
        if (correction.flipHorizontal) matrix.postScale(-1f, 1f)
        if (correction.flipVertical) matrix.postScale(1f, -1f)

        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun readExifOrientation(uri: Uri): Int = try {
        // A content URI's InputStream is not seekable, and androidx's ExifInterface needs a
        // seekable source, so go through the file descriptor rather than the stream.
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            ExifInterface(pfd.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifOrientation.NORMAL
            )
        } ?: ExifOrientation.NORMAL
    } catch (e: Exception) {
        // Some providers serve files without EXIF; treat as already upright.
        Log.w("ShareImageActivity", "EXIF orientation unreadable", e)
        ExifOrientation.NORMAL
    }

    companion object {
        /** The button texts, exactly as the issue asks for them. */
        private const val ROTATE_CW_GLYPH = "⟳"
        private const val ROTATE_CCW_GLYPH = "⟲"

        /**
         * #78: the top-left back control. A text glyph (U+2190 LEFT ARROW), not a
         * bundled asset — a new asset would have to satisfy the app's licence
         * index, and the chrome already speaks glyphs. Same 52dp as the rotate
         * pair, and the same corner margin.
         */
        private const val BACK_BUTTON_TAG = "back_button"
        private const val BACK_GLYPH = "\u2190"
        private const val BACK_BUTTON_DP = 52
        private const val BACK_BUTTON_MARGIN_DP = 12

        /** ≥48dp touch targets (platform minimum), a step up for legibility. */
        private const val ROTATE_BUTTON_DP = 52
        private const val ROTATE_BUTTON_GAP_DP = 8
        private const val ROTATE_BAR_MARGIN_DP = 12
        private const val ROTATE_BUTTON_RADIUS_DP = 10f
        private const val ROTATE_GLYPH_TEXT_SIZE_SP = 24f

        /** Dead zone around the rotate pair, in dp: taps here are swallowed, not treated as empty space. */
        private const val ROTATE_BAR_TOUCH_PAD_DP = 20f

        private val ROTATE_BUTTON_FILL = Color.argb(130, 25, 25, 25)
        private val ROTATE_BUTTON_STROKE = Color.argb(150, 0, 255, 255)
        private val ROTATE_GLYPH_COLOR = Color.parseColor("#00FFFF")
    }
}
