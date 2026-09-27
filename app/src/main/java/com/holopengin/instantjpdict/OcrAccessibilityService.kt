package com.holopengin.instantjpdict

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.hardware.display.DisplayManager
import android.view.animation.DecelerateInterpolator
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.gson.Gson
import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.KanaSizeFix
import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.util.DeinflectionChain
import com.holopengin.instantjpdict.util.ComponentTable
import com.holopengin.instantjpdict.util.FuriganaAligner
import com.holopengin.instantjpdict.util.JapaneseUtil
import com.holopengin.instantjpdict.util.KanjiVariants
import com.holopengin.instantjpdict.util.OovCandidates
import com.holopengin.instantjpdict.util.OovSuggestions
import com.holopengin.instantjpdict.util.PitchAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class OcrAccessibilityService : AccessibilityService() {

    /**
     * The window manager for the overlay's window. Non-null by construction: it
     * used to be a nullable field written as a side effect of adding the floating
     * button, the only writer, and #105 removed that writer — a null there would
     * turn `addView` into a silent no-op, so the value is derived instead of
     * stored ([by lazy] so the service context is not touched before the first
     * use, which is always after [onCreate]).
     */
    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    /**
     * #105: the overlay close button's centre in natural (rotation-0) device
     * coordinates — the canonical position, re-derived per rotation by
     * [OcrOverlayView.Host.closeButtonOrigin] (see [OverlayClosePosition]). The
     * button used to borrow the floating trigger's window params for this; with the
     * trigger gone it owns the store, and a drag in
     * [OcrOverlayView.Host.onCloseButtonMoved] is the only other writer.
     */
    private var closeNatCX = 0f
    private var closeNatCY = 0f
    /**
     * #105: whether the close button still needs its starting placement derived.
     *
     * The top-left default is a DEFAULT, not a per-connect reset: seeding it on
     * every bind would throw away a position the user dragged the last time this
     * service ran, and a re-bind is routine (see [onServiceConnected]). So it is
     * derived once, on the first overlay this service builds — which also puts it
     * against the live display metrics rather than whatever they were at bind time
     * — and [rememberCloseButtonPosition] clears this on the way through, so a
     * drag never re-enables seeding.
     */
    private var closeButtonNeedsHome = true
    /** Set in onDestroy so a pending trigger cannot open an overlay during teardown. */
    private var isDestroyed = false
    /** #57: the shared OCR overlay surface; null when no overlay is showing. */
    private var overlayView: OcrOverlayView? = null
    /**
     * A1/#86: the overlay pass currently inside the nets, or null when none has
     * run. Kept so [onDestroy] can defer `ocrEngine.close()` behind it — a
     * cancellation cannot interrupt a non-suspending native detect, and closing
     * under one is the crash documented in [closeEngineBehindPass].
     */
    private var ocrPass: Job? = null
    private lateinit var ocrEngine: OcrEngine
    /**
     * A8/#86: the engine is constructed on `Dispatchers.IO` (asset copies + two
     * native net loads), so this is that construction. [showScreenshotOverlay]
     * joins it before an overlay can read `ocrEngine`, and [onDestroy] waits on
     * it before closing, so a construction the teardown outran is still closed.
     */
    private var engineReady: Job? = null
    private val controller = OcrOverlayStateController()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** #105: the post-delays below need a main-thread queue of their own now that
     *  there is no trigger view to post them on. */
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * #105: when the last activation was accepted, for the bind-storm guard in
     * [captureFromActivation]. Elapsed realtime, monotonic — see the guard.
     */
    private var lastActivationAt = 0L

    /**
     * #105: whether the framework currently has us bound.
     *
     * `onServiceConnected` is not once per activation: the framework also
     * re-connects a service that is **already enabled** — after a `serviceInfo`
     * change, a config change, or a rebind once the process is back — and none of
     * those are the user asking for a capture. A plain toggle always unbinds first
     * ([onUnbind]), so the unbind is what tells a fresh activation from a
     * re-connect.
     *
     * Deliberately in memory: a process restart starts it `false`, so a gesture
     * after the process was killed still captures. A persisted flag would catch
     * that rebind too, but a stale `true` surviving an unbind that was never
     * delivered would suppress every later capture — the worse failure of the two.
     */
    private var serviceBound = false


    private val overlayControllerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // #105: what is left here is the overlay's own business and nothing
            // else. SCREEN_ON and USER_PRESENT are gone from the filter as well as
            // the branches: every action either of them used to take was show or
            // re-attach the floating button, and the screen-off branch no longer
            // hides one — so all four are now just "close the overlay".
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> hideScreenshotOverlay()
            }
        }
    }
    
    
    private val pressedKeys = mutableSetOf<Int>()
    private var lastGlobalTriggerTime = 0L



    override fun onCreate() {
        super.onCreate()
        // A8/#86: built off the main thread. An AccessibilityService's onCreate must
        // not spend hundreds of milliseconds copying model assets and loading nets —
        // the system is waiting on this call. The first reader is [showScreenshotOverlay],
        // which joins [engineReady] before it can touch the engine.
        engineReady = serviceScope.launch {
            withContext(Dispatchers.IO) { ocrEngine = OcrEngine(this@OcrAccessibilityService) }
        }
        OverlayEnvironment.prepare(this, controller, serviceScope)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        
        ContextCompat.registerReceiver(
            this,
            overlayControllerReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A rotation invalidates the overlay: its text boxes were placed for the
        // old orientation. The close button's canonical position is rotation-free
        // ([OverlayClosePosition]), so hiding the overlay costs the user nothing —
        // the next capture opens the button back on the same physical spot.
        if (overlayView != null) {
            hideScreenshotOverlay()
        }
    }

    /**
     * #105: THE TRIGGER. Enabling the service is the capture request, and the
     * platform's own accessibility activations are the two halves of it:
     *
     *  - [onServiceConnected] — the volume-button and two-finger-swipe gestures
     *    (and the accessibility-button tap, on a system where that toggles a lone
     *    service) *toggle the service at the system level*, so the bind is the
     *    only event we get. Nothing here draws a control: this replaces the
     *    floating button that used to stand in for it.
     *  - [accessibilityButtonCallback] — the nav-bar accessibility button, and
     *    whatever the user pinned to it, does NOT toggle, so it is a genuine
     *    per-activation trigger and arrives as a callback instead. It is the other
     *    half of "the system triggers themselves".
     *
     * The floating button these replace needed none of this: it was a view of
     * ours, so a drag, a hide on screen-off and a re-attach after the system
     * dropped its window were all part of keeping it usable. A platform
     * activation has no lifecycle to maintain — there is nothing to add, hide,
     * re-attach or position — which is the whole of what #105 deletes.
     *
     * Two caveats are inherent to that choice, not bugs to fix here:
     *
     *  1. `onServiceConnected` also fires when the system re-binds a service that
     *     was enabled all along — after a process restart, a configuration change,
     *     or an app update. Every such connect captures whatever is on screen at
     *     that moment. Distinguishing a user enable from a system re-bind is not
     *     available to an AccessibilityService, so the honest reading is "the
     *     service is (re)connected, so this is a capture".
     *  2. The FIRST enable therefore captures the Settings screen the user just
     *     enabled us from, which is not Japanese text and makes for a useless
     *     first overlay. It costs one tap on the overlay's close button.
     *
     * The guards below keep that honest without a button to show: a connect while
     * an overlay is already up, while the screen is off, or on the keyguard has
     * nothing worth capturing and stays silent (logged, not toasted), and a bind
     * storm — which the re-bind caveat makes routine — cannot open two overlays.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                // #105: what puts us in the accessibility-button chooser, so the
                // user can pin us to the nav-bar button / accessibility button
                // without Settings. Pinned through `flagRequestAccessibilityButton`
                // in the service config too, for the same reason and so a fresh
                // install is offerable before the first bind has run.
                AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        serviceInfo = info
        // #105: the platform's accessibility-button callback. This is the API-35
        // replacement for the old `onAccessibilityButtonClicked(int)`, which no
        // longer exists on AccessibilityService (see [accessibilityButtonCallback]).
        //
        // Unregister first: registering the same callback object twice would deliver
        // every press twice, and this method runs on every connect, not once.
        accessibilityButtonController?.let { controller ->
            try {
                controller.unregisterAccessibilityButtonCallback(accessibilityButtonCallback)
            } catch (_: Exception) {
            }
            controller.registerAccessibilityButtonCallback(accessibilityButtonCallback)
        }
        // #105: a connect is an activation only when the user toggled us on. The
        // framework also re-connects an already-enabled service — a `serviceInfo`
        // change, a config change, a rebind — without an unbind in between, and
        // those are not asks for a capture. The one residual is a rebind after the
        // *process* was killed: the flag is in memory, so that looks like a fresh
        // enable and captures. A missed gesture would be worse than a spurious
        // capture, so the uncertainty is resolved that way on purpose.
        val reconnected = serviceBound
        serviceBound = true
        if (reconnected) {
            Log.d(TAG, "re-connected while already bound; not an activation; not capturing")
            return
        }
        captureFromActivation("service connected")
    }

    /**
     * #105: the framework released us — the user toggled the service off, which is
     * the other half of the same gesture. Clearing [serviceBound] is what makes the
     * next toggle a fresh activation again, and it is why a connect with no unbind
     * before it is never treated as one.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        serviceBound = false
        return super.onUnbind(intent)
    }

    /**
     * #105: the accessibility button — the nav-bar button, or whatever the user
     * pinned to it, which is also what the button drawer and the
     * accessibility-button shortcut reach — was activated.
     *
     * Unlike the volume-button and two-finger-swipe gestures, this does NOT toggle
     * the service, so it is a genuine per-activation trigger: it shares the guards,
     * the delay and the retry with [onServiceConnected] through
     * [captureFromActivation] rather than duplicating them.
     *
     * This is `AccessibilityButtonCallback.onClicked` because the direct
     * `onAccessibilityButtonClicked(int displayId)` override was deprecated in
     * API 33 and is gone from the API-35 `AccessibilityService`; the controller
     * callback is what that override became, and the only supported way to be told
     * about an accessibility-button press. The controller is display-scoped (the
     * no-arg one is the default display) and this service is single-display, so
     * there is one registration.
     */
    private val accessibilityButtonCallback =
        object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(button: AccessibilityButtonController) {
                captureFromActivation("accessibility button")
            }

            override fun onAvailabilityChanged(
                button: AccessibilityButtonController,
                available: Boolean,
            ) {
                // Logged, not surfaced: this fires when the user pins or unpins us
                // in Settings, and "the button is ours now" needs no dialog. It is
                // here so an on-device check can read the state out of logcat.
                Log.d(TAG, "accessibility button available=$available")
            }
        }

    /**
     * #105: the active window's package, or null when the framework will not say.
     *
     * The accessibility windows API is the one place a service can ask this without
     * another permission; [AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS]
     * is what populates it. Null is the "cannot tell" answer, and every caller
     * treats it as "not Settings" so an unreadable window cannot suppress a real
     * activation.
     */
    private fun activeWindowPackage(): String? =
        try {
            windows.firstOrNull { it.isActive }?.root?.packageName?.toString()
        } catch (_: Exception) {
            null
        }

    /**
     * #105: the one gate in front of a capture, shared by both native activations.
     *
     * Silently skips (a log line, no toast — the user did not ask for anything, so
     * an error would be noise) when there is nothing to capture: an overlay is
     * already up, the screen is off, or the keyguard is locked. The timestamp is
     * stamped BEFORE those checks, so every activation that gets past the interval
     * spends the guard window: a bind storm is exactly the case where a second
     * connect arrives while the first is still in flight, and spending the window
     * on a skipped trigger too is what stops that pair from opening two overlays.
     *
     * The one non-`suspend` guard that is *not* stamped over is the Settings-screen
     * skip below: configuring us there is not a capture at all, and it should not
     * spend the interval that a real gesture a moment later needs.
     *
     * Elapsed realtime, not wall clock: this is a monotonic interval, and a user or
     * NTP changing the time must not make the guard pass or stall.
     */
    private fun captureFromActivation(reason: String) {
        if (isDestroyed) return
        // #105: a Settings screen is where the user *configures* us — switching the
        // service on, pinning it to the shortcut — and the capture that used to fire
        // there was a capture of Settings, never what was wanted. The app in front is
        // what tells the two apart: a Settings package means configuration, anything
        // else means the gesture fired where the user was already reading. The
        // package is logged so an on-device check can see which side it landed on.
        //
        // Before the interval stamp, deliberately: configuring us must not spend the
        // guard window that a real gesture, a moment later, needs.
        val foreground = activeWindowPackage()
        if (foreground != null && foreground.contains("settings", ignoreCase = true)) {
            Log.d(TAG, "activation '$reason' from $foreground (configuration, not a capture); ignoring")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastActivationAt < ACTIVATION_GUARD_MS) {
            Log.d(TAG, "activation '$reason' within ${ACTIVATION_GUARD_MS}ms of the last one; ignoring")
            return
        }
        lastActivationAt = now
        if (overlayView != null) {
            Log.d(TAG, "activation '$reason' with the overlay already up; ignoring")
            return
        }
        val power = getSystemService(POWER_SERVICE) as? PowerManager
        if (power != null && !power.isInteractive) {
            Log.d(TAG, "activation '$reason' with the screen off; ignoring")
            return
        }
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard != null && keyguard.isKeyguardLocked) {
            Log.d(TAG, "activation '$reason' on the keyguard; ignoring")
            return
        }
        // The window server needs a moment after the bind before a screenshot
        // request is served; the old floating-button path posted 50 ms for the
        // button to hide, which a bind needs twice over (its own window is being
        // torn down as the service comes up).
        mainHandler.postDelayed({ capture(reason, isRetry = false) }, ACTIVATION_POST_DELAY_MS)
    }

    /**
     * #105: the capture itself, and the single retry.
     *
     * One retry, never a loop: a failure here is either transient (the window
     * server was not ready) or structural (the display is secure — the secure
     * camera — in which case retrying just toasts the same error twice).
     * [ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT] is the platform's own rate
     * limit (two screenshots a second), and a retry inside that window would only
     * re-enter it, so that code is not retried at all — nor is a second failure
     * after a retry. The escape hatch in both cases is another user activation.
     */
    private fun capture(reason: String, isRetry: Boolean) {
        if (isDestroyed) return
        // The gate checked this at activation time; the retry comes ~800 ms later,
        // by which point the user may have activated again (the guard window has
        // passed) and an overlay of their own is up. Nothing to capture then.
        if (overlayView != null) {
            Log.d(TAG, "activation '$reason': an overlay is up by capture time; not capturing")
            return
        }
        triggerCapture(
            onSuccessAction = { bitmap -> showScreenshotOverlay(bitmap) },
            onFailedAction = { errorCode ->
                if (isRetry || errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    Log.d(TAG, "activation '$reason': screenshot failed ($errorCode); not retrying")
                    return@triggerCapture
                }
                Log.d(TAG, "activation '$reason': screenshot failed ($errorCode); retrying in ${CAPTURE_RETRY_DELAY_MS}ms")
                mainHandler.postDelayed({ capture(reason, isRetry = true) }, CAPTURE_RETRY_DELAY_MS)
            },
        )
    }

    // ---- #105: the close button's own position ----

    /**
     * The close button's pixel size, from the one helper the view lays it out with
     * ([closeButtonSizePx]) — the position store has to know the edge length to
     * convert a centre, and the button does not exist yet when it needs to.
     */
    private fun closeButtonSize(): Int = closeButtonSizePx(resources.displayMetrics.density)

    /**
     * The display rotation the overlay is built under. Read per overlay rather than
     * watched: the button's position is derived once, when the overlay is created,
     * and a rotation while the overlay is up closes it
     * ([onConfigurationChanged]) — so there is nothing left to keep in step.
     */
    private fun displayRotation(): Int =
        (getSystemService(DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: ROTATION_NATURAL

    /** Keep the canonical natural centre in step with a user placement. */
    private fun rememberCloseButtonPosition(x: Int, y: Int) {
        val size = closeButtonSize()
        val dm = resources.displayMetrics
        val (cx, cy) = naturalCentre(x, y, size, size, dm.widthPixels, dm.heightPixels, displayRotation())
        closeNatCX = cx
        closeNatCY = cy
        closeButtonNeedsHome = false
    }

    private fun triggerCapture(
        onSuccessAction: (Bitmap) -> Unit,
        onFailedAction: (errorCode: Int) -> Unit = {},
    ) {
        takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, true)
                    buffer.close()
                    if (bitmap != null) {
                        onSuccessAction(bitmap)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot capture failed with error code: $errorCode")
                    Toast.makeText(this@OcrAccessibilityService, "Screenshot failed: $errorCode", Toast.LENGTH_SHORT).show()
                    onFailedAction(errorCode)
                }
            })
    }

    private fun showScreenshotOverlay(image: Bitmap) {
        if (overlayView != null) return
        // A8/#86: the engine is built off the main thread, so a trigger in the
        // service's first moments can arrive before it exists. Wait for the build
        // rather than reading an uninitialised lateinit — the capture bitmap is
        // already in hand, and the wait is only the tail of work that used to block
        // onCreate. A trigger after the build (every later one) takes the direct path.
        val ready = engineReady
        if (ready != null && !ready.isCompleted) {
            serviceScope.launch {
                ready.join()
                if (!isDestroyed && overlayView == null) showScreenshotOverlayNow(image)
            }
            return
        }
        showScreenshotOverlayNow(image)
    }

    private fun showScreenshotOverlayNow(image: Bitmap) {
        if (overlayView != null) return
        controller.resetState()
        Log.d(TAG, "showScreenshotOverlay detThresh=${OcrEngine.getDetThresh(this)} longSide=${OcrEngine.getDetLongSide(this)}")

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // #57: the overlay UI is the shared OcrOverlayView now, hosted here on
        // an accessibility-overlay window and by ShareImageActivity on an
        // ordinary activity window. Only the host-specific bits differ: the
        // image source, the dismiss request and the close button's position.
        val host = object : OcrOverlayView.Host {
            override val bitmap: Bitmap get() = image
            override val ocrEngine: OcrEngine get() = this@OcrAccessibilityService.ocrEngine
            override val controller: OcrOverlayStateController get() = this@OcrAccessibilityService.controller

            override fun dismissOverlay() {
                hideScreenshotOverlay()
            }

            override fun requestSoftInputResize() {
                val root = overlayView ?: return
                val p = root.layoutParams as? WindowManager.LayoutParams ?: return
                p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                windowManager.updateViewLayout(root, p)
            }

            // #105: the close button's own store. It used to read and write the
            // floating button's window params; with that window gone the button
            // keeps the position itself, and these two are its only accessors. The
            // first overlay of a service run is where the default gets derived.
            override fun closeButtonOrigin(): Pair<Int, Int> {
                if (closeButtonNeedsHome) {
                    rememberCloseButtonPosition(CLOSE_BUTTON_HOME_X, CLOSE_BUTTON_HOME_Y)
                }
                val size = closeButtonSize()
                val dm = resources.displayMetrics
                val (x, y) = logicalTopLeft(
                    closeNatCX, closeNatCY, size, size,
                    dm.widthPixels, dm.heightPixels, displayRotation(),
                )
                return x to y
            }

            override fun onCloseButtonMoved(x: Int, y: Int) {
                rememberCloseButtonPosition(x, y)
            }
        }

        val view = OcrOverlayView(this, host)
        overlayView = view
        windowManager.addView(view, params)
        // A1/#86: the returned pass gates the deferred engine close in [onDestroy].
        ocrPass = view.startOcr()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return super.onKeyEvent(event)
        
        // Handle global shortcut when overlay is NOT showing
        if (overlayView == null) {
            val prefs = getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
            val globalShortcutEnabled = prefs.getBoolean("global_shortcut_enabled", false)
            
            if (!globalShortcutEnabled) {
                pressedKeys.clear()
                return super.onKeyEvent(event)
            }

            when (keyEvent.action) {
                KeyEvent.ACTION_DOWN -> {
                    pressedKeys.add(keyEvent.keyCode)
                    if (pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_L1) && pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_R1)) {
                        val now = System.currentTimeMillis()
                        if (now - lastGlobalTriggerTime > 1500) { // 1.5s cooldown
                            lastGlobalTriggerTime = now
                            // Clear keys to prevent immediate repeat and consume the event
                            pressedKeys.clear()
                            
                            // Trigger OCR. #105: the old code hid the floating
                            // button and posted 50 ms off it before capturing, both
                            // so the button would not be in the screenshot. There is
                            // no trigger to keep out of frame, so this is a direct
                            // capture — the same one the service activations make.
                            triggerCapture(onSuccessAction = { bitmap ->
                                showScreenshotOverlay(bitmap)
                            })
                            return true
                        }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    pressedKeys.remove(keyEvent.keyCode)
                }
            }
            return super.onKeyEvent(event)
        }

        if (overlayView?.handleKeyEvent(keyEvent) == true) return true
        return super.onKeyEvent(keyEvent)
    }


    private fun hideScreenshotOverlay() {
        val view = overlayView ?: return
        view.onClosed()
        overlayView = null
        if (view.isAttachedToWindow) try { windowManager.removeViewImmediate(view) } catch (e: Exception) { Log.e(TAG, "Error removing overlay", e) }
        controller.resetState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // #105: the no-overlay branch used to re-attach the floating button here
        // (the system drops an overlay window on the way to the secure camera).
        // There is no window of ours to restore any more, so it is just a return.
        val view = overlayView ?: return
        val eventPackage = event.packageName?.toString()
        if (eventPackage == null || eventPackage == packageName) return
        if (view.hasManualInputBlocker()) return
        if (System.currentTimeMillis() - controller.lastManualInputCloseTime < 1000) return
        if (event.isFullScreen != true) return
        hideScreenshotOverlay()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isDestroyed = true
        super.onDestroy()
        try { unregisterReceiver(overlayControllerReceiver) } catch (e: Exception) {}
        // #105: the button callback goes with the service. Registering again on the
        // next connect is the pattern, and the controller drops a registration on
        // service teardown anyway, so this is belt-and-braces.
        try {
            accessibilityButtonController
                ?.unregisterAccessibilityButtonCallback(accessibilityButtonCallback)
        } catch (e: Exception) {}
        hideScreenshotOverlay()
        // A1/#86: never close the nets while a pass is inside them. A service can be
        // destroyed mid-pass (toggled off, rebound, data change); `hideScreenshotOverlay`
        // cancels the overlay scope, but cancellation stops a coroutine, not the native
        // detect it is blocked in. The same guard as the share activity's onDestroy —
        // both run through [closeEngineBehindPass] so they cannot drift again.
        //
        // A8/#86: the engine is constructed off the main thread now, so the teardown
        // can outrun the build. Wait for [engineReady] first (a constructor has no
        // suspension points, so it always finishes) — otherwise an engine that appears
        // just after this method would never be closed.
        val ready = engineReady
        if (ready != null && !ready.isCompleted) {
            ready.invokeOnCompletion {
                if (::ocrEngine.isInitialized) closeEngineBehindPass(ocrPass) { ocrEngine.close() }
            }
        } else {
            closeEngineBehindPass(ocrPass) { if (::ocrEngine.isInitialized) ocrEngine.close() }
        }
    }

    private companion object {
        private const val TAG = "OcrAccessibilityService"

        /**
         * #105: how long after an activation the capture is posted.
         *
         * A screenshot request is answered by the window server, and right after a
         * bind it is still tearing the old service instance down — which on the
         * gesture paths includes the floating button's own window, since the
         * gesture that enables the service is the one that disabled it. The button
         * path posted 50 ms to let the button hide; a bind wants more, and 300 ms is
         * short enough that the overlay still feels attached to the gesture that
         * asked for it.
         */
        private const val ACTIVATION_POST_DELAY_MS = 300L

        /**
         * #105: the minimum gap between two activations.
         *
         * Every activation claims this window, whether or not it went on to
         * capture. A bind storm is the case this exists for: the system can bind
         * twice in quick succession (a re-bind over an enable, a configuration
         * change, the volume-key gesture registering before the service is
         * enabled), and without it the second bind would open a second overlay on
         * top of the first. It is also why a skipped activation costs the next
         * real one nothing: 750 ms is far below the gap between two deliberate
         * user activations, which are a gesture or a button tap.
         */
        private const val ACTIVATION_GUARD_MS = 750L

        /** #105: the pause before the one capture retry. */
        private const val CAPTURE_RETRY_DELAY_MS = 500L

        /**
         * #105: where the close button starts, in logical pixels. A default, not a
         * per-connect reset — see [closeButtonNeedsHome].
         */
        private const val CLOSE_BUTTON_HOME_X = 100
        private const val CLOSE_BUTTON_HOME_Y = 100
    }

}
