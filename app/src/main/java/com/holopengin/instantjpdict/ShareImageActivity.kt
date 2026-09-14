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
 * the way the camera was held. [ProtoCameraActivity] sends the hold it was in as
 * [InheritedOrientation.EXTRA_CAMERA_HOLD] — the same `Surface` rotation it gives
 * its use cases and reads its control anchors from — and [adoptCameraHold] asks
 * the window manager for a sensor-based orientation before the first layout, so
 * a landscape hold opens a landscape view and it keeps following the phone
 * afterwards. Opened from a SYSTEM SHARE SHEET there is no such extra and no
 * orientation call is made at all: that path is exactly what it was. Nothing is
 * declared in the manifest for either path — the entry there is shared with the
 * exported `ACTION_SEND` filter, so a `screenOrientation` (or `configChanges`)
 * on it would pin or restart the share path too; the whole difference lives in
 * [adoptCameraHold] and in one extra on the camera's Intent.
 *
 * The landscape chrome is this activity's own and needed no new anchoring: the
 * back control goes through [applySystemBarInsets], which writes all four
 * margins, so in landscape it is still clear of the status bar on the top edge
 * and of a navigation bar that has moved to a long edge; the rotate pair takes
 * the same treatment in the bottom-left. The dictionary/lookup panel and the
 * confidence controls are [OcrOverlayView]'s and are untouched.
 */
class ShareImageActivity : AppCompatActivity(), OcrOverlayView.Host {

    /** Overlay-scoped work: the image decode, the shared environment loads and
     *  the OCR run. Cancelled in [onDestroy]. */
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var engine: OcrEngine
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

        // The window is told which way up it is FIRST — before a single view
        // exists and before the image is decoded — because an orientation
        // request only decides the window this activity is laid out in if it
        // lands before the first layout. See [adoptCameraHold] and
        // [InheritedOrientation].
        adoptCameraHold(intent)

        engine = OcrEngine(this)
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
            val width = container.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val height = container.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            surfaceWidth = width
            surfaceHeight = height
            overlayScope.launch {
                val base = withContext(Dispatchers.IO) { decodeOriented(uri, width, height) }
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
        overlayView?.onClosed()
        overlayView = null
        overlayScope.cancel()
        if (::engine.isInitialized) engine.close()
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

    // ---- orientation (the camera handoff) ----

    /**
     * #78 follow-up: "the ocr view does not inherit the camera view's orientation
     * but it should."
     *
     * [ProtoCameraActivity] marks the handoff with the hold it was in
     * ([InheritedOrientation.EXTRA_CAMERA_HOLD] — the same `Surface` rotation it
     * gave its use cases and read its control anchors from). When that extra is
     * present this asks the window manager for
     * [InheritedOrientation.requestedOrientationFor]'s answer, `fullSensor`: the
     * view opens the way up the viewfinder was, and keeps following the phone
     * afterwards. When the extra is absent (a system share sheet) NOTHING is
     * asked for and not one window property is touched, so the share path is this
     * activity exactly as it was before.
     *
     * Called from [onCreate] before anything is built, because an orientation
     * request only decides the window this activity is laid out in if it lands
     * before the first layout. See [InheritedOrientation] for why the request is
     * `fullSensor` rather than the family the camera happened to be in, and for
     * why re-running this on a later re-creation (with a stale hold) cannot flip
     * a view that is already the right way up. A request that does change the
     * window re-creates this activity in the new orientation, which is the clean
     * way here: the image and the box coordinates are composed at the container's
     * own size ([surfaceWidth] / [surfaceHeight]), so the re-created instance
     * composes at the new container's size and the mapping stays 1:1 — where a
     * `configChanges` activity would have had to recompose in place to avoid
     * drawing stale portrait-space boxes over a resized view.
     */
    private fun adoptCameraHold(intent: Intent?) {
        val hold = intent
            ?.getIntExtra(InheritedOrientation.EXTRA_CAMERA_HOLD, InheritedOrientation.NO_HOLD)
            ?: InheritedOrientation.NO_HOLD
        val requested = InheritedOrientation.requestedOrientationFor(hold) ?: return
        // The one line the in-hand check reads: the hold the viewfinder handed
        // over, the way up the window this instance came up in, and what went in.
        // A window in the hold's own family is the feature working; one in the
        // other family is the platform not having followed the sensor yet.
        val createdLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        Log.i(
            "ShareImageActivity",
            "camera hold ${holdName(hold)}, window was ${familyName(createdLandscape)}, " +
                "requesting FULL_SENSOR ($requested)"
        )
        requestedOrientation = requested
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
        // The turn is cheap and the user asked for it now, so show it now whatever the
        // OCR is doing — waiting for the pass in flight is what made a second press feel
        // stuck. The pass for the new orientation follows from runRotations.
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
        while (rotations.turns != composedTurns) {
            val view = overlayView ?: break
            val base = baseImage ?: break
            val width = surfaceWidth
            val height = surfaceHeight
            if (width <= 0 || height <= 0) break

            val turns = rotations.turns
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
     * between the state check and the loop's exit and be left stranded.
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
            // Work exists when the orientation the queue asks for is not the one the last
            // pass read — see [lastOcrTurns]. `turns` wraps, so four turns need no pass.
            if (rotations.turns == lastOcrTurns) break

            val view = overlayView ?: break
            if (surfaceWidth <= 0 || surfaceHeight <= 0) break
            lastOcrTurns = rotations.turns
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
        val rotated = rotateBitmap(base, turns)
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
        canvas.drawBitmap(rotated, null, dst, paint)
        if (rotated !== base) rotated.recycle()
        return out
    }

    /** Clockwise quarter turns of [base]; [base] itself when there is nothing to
     *  do, so the caller can tell whether it owns a second bitmap. */
    private fun rotateBitmap(base: Bitmap, turns: Int): Bitmap {
        val degrees = ImageRotation.degrees(turns)
        if (degrees == 0f) return base
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
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
