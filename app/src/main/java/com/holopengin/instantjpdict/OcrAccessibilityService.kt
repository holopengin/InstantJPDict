package com.holopengin.instantjpdict

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import android.view.accessibility.AccessibilityNodeInfo
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
     * #105: which activation that was, for the one timing line that says whether a
     * trigger felt immediate ([showScreenshotOverlayNow]).
     */
    private var lastActivationReason = ""

    /** #105: the last volume-down press, for the double-press chord (0 = none yet). */
    private var lastVolumeDownAt = 0L

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
            // #105: the button's show/hide branches are gone from the filter and
            // the code; what is left is the overlay's own lifecycle. Screen off ends
            // the session; a closed system dialog only matters when it closed over
            // the overlay.
            when (intent?.action) {
                // Screen off ends the session, whatever was on it.
                Intent.ACTION_SCREEN_OFF -> closeOverlay("screen off")
                // Deprecated and noisy: a Settings toggle, an IME, the power menu can
                // all close a system dialog. It matters only when it closed over our
                // overlay; on its own it must never disable the service — this was
                // one path that flipped the switch off under the user in Settings.
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> if (overlayView != null) {
                    closeOverlay("system dialog closed over the overlay")
                }
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
        // old orientation.
        if (overlayView != null) {
            closeOverlay("rotation")
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
        // #105: the framework re-enables an accessibility service when its app is
        // updated, and that connect is not an activation — on this device it fired
        // 1.9 s after the install, with the Settings screen on display. See
        // [isFirstRunSinceUpdate].
        if (isFirstRunSinceUpdate()) {
            Log.d(TAG, "first connect since the app was updated; not an activation; not capturing")
            return
        }
        captureFromActivation("service connected", freshBind = true)
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
     * #106: the windows to read for this capture, best first (see
     * [ScreenTextReader.windowsToTry] for why the order is a walk, not a choice).
     *
     * #105's guard is untouched: [activeWindowPackage] still reads the plain active
     * window, because it runs before the overlay exists and must keep saying
     * "Settings" rather than "whatever is behind Settings".
     */
    private fun screenTextRoots(): List<AccessibilityNodeInfo> {
        val list = try {
            windows
        } catch (_: Exception) {
            return emptyList()
        }
        val bounds = Rect()
        val candidates = list.map { window ->
            window.getBoundsInScreen(bounds)
            ScreenTextReader.WindowCandidate(
                isActive = window.isActive,
                packageName = try {
                    window.root?.packageName?.toString()
                } catch (_: Exception) {
                    null
                },
                area = bounds.width().toLong() * bounds.height().toLong(),
            )
        }
        return ScreenTextReader.windowsToTry(candidates, packageName).mapNotNull { index ->
            try {
                list[index].root
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * #105: whether this connect is the first since the app was replaced.
     *
     * The framework re-enables an accessibility service when its app is updated,
     * and that connect is not a user activation: on this device it arrived 1.9 s
     * after the install, with the Settings screen on display. `lastUpdateTime` is
     * the signal — the first connect that sees a value different from the one this
     * process last recorded is that re-enable, whatever its delay, and it is
     * skipped; every later connect (the user's gestures) matches and captures.
     *
     * Persisted, unlike [serviceBound]: the update outlives the process, so the
     * signal must too. The failure a stale value can cause is the *opposite* of
     * [serviceBound]'s — one missed activation right after an update, never a
     * permanently suppressed trigger — because the value is written on the same
     * connect that reads it.
     */
    private fun isFirstRunSinceUpdate(): Boolean {
        val updatedAt = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            return false
        }
        val prefs = getSharedPreferences(ACCESSIBILITY_PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_SEEN_UPDATE_TIME, 0L) == updatedAt) return false
        // commit(), not apply(): the stand-down that follows can take the process
        // with it, and an async write that never lands re-arms this guard on every
        // later connect — every enable would look like the post-update one.
        prefs.edit().putLong(KEY_SEEN_UPDATE_TIME, updatedAt).commit()
        return true
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
     * The Settings-screen check is *not* here: it reads the accessibility windows
     * list, which is not populated yet when this runs (see [capture]), and the
     * just-updated check is in [onServiceConnected] because it must also persist
     * the signal.
     *
     * Elapsed realtime, not wall clock: this is a monotonic interval, and a user or
     * NTP changing the time must not make the guard pass or stall.
     */
    private fun captureFromActivation(reason: String, freshBind: Boolean = false) {
        if (isDestroyed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastActivationAt < ACTIVATION_GUARD_MS) {
            // A duplicate, not an idle service: the earlier activation is already in
            // flight (armed or capturing), and standing down here would cancel it.
            Log.d(TAG, "activation '$reason' within ${ACTIVATION_GUARD_MS}ms of the last one; ignoring")
            return
        }
        lastActivationAt = now
        lastActivationReason = reason
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
        // Only a fresh bind has to wait: the window server is still tearing the old
        // service instance down and will not serve a screenshot yet, and the
        // Settings gate that runs at capture time needs the accessibility windows
        // list to be populated. A live trigger — the accessibility button, the
        // volume chord — arrives on a service that is already running,
        // where waiting 300 ms was the whole of the delay the app-owned button
        // never had. So the bind waits [BIND_POST_DELAY_MS] and a live trigger waits
        // the old button's own [LIVE_POST_DELAY_MS].
        val postDelay = if (freshBind) BIND_POST_DELAY_MS else LIVE_POST_DELAY_MS
        mainHandler.postDelayed({ capture(reason, isRetry = false, freshBind = freshBind) }, postDelay)
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
    private fun capture(reason: String, isRetry: Boolean, freshBind: Boolean = false) {
        if (isDestroyed) return
        // The gate checked this at activation time; the retry comes ~800 ms later,
        // by which point the user may have activated again (the guard window has
        // passed) and an overlay of their own is up. Nothing to capture then.
        if (overlayView != null) {
            Log.d(TAG, "activation '$reason': an overlay is up by capture time; not capturing")
            return
        }
        // #105: the Settings gate belongs to the BIND activation — turning the
        // service on from Settings must not scan the screen it is being configured
        // from. The foreground is read HERE rather than at activation time because
        // when `onServiceConnected` fires the accessibility windows list is not
        // populated yet (an on-device log showed the check that used to live there
        // seeing nothing and capturing the Settings screen the user was configuring
        // us from), but the bind's post delay later it is.
        //
        // A live trigger — the accessibility button, the volume chord — is the user
        // asking for a capture wherever they are, so it takes the
        // screenshot directly: no gate, and the window-list read the gate needs stays
        // off the path that has to feel immediate.
        if (freshBind) {
            val foreground = activeWindowPackage()
            if (foreground != null && isSettingsSurface(foreground)) {
                Log.d(TAG, "activation '$reason' from $foreground (configuration, not a capture); no capture")
                return
            }
            // The value is logged on the capturing path too: a null here (no window,
            // or no window-content capability) is indistinguishable from "not
            // Settings", and that is exactly the reading an on-device check needs.
            Log.d(TAG, "activation '$reason': foreground=${foreground ?: "unknown"}; capturing")
        } else {
            Log.d(TAG, "activation '$reason': live trigger; capturing")
        }
        triggerCapture(
            onSuccessAction = { bitmap -> showScreenshotOverlay(bitmap) },
            onFailedAction = { errorCode ->
                if (isRetry || errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    Log.d(TAG, "activation '$reason': screenshot failed ($errorCode); not retrying")
                    return@triggerCapture
                }
                Log.d(TAG, "activation '$reason': screenshot failed ($errorCode); retrying in ${CAPTURE_RETRY_DELAY_MS}ms")
                mainHandler.postDelayed({ capture(reason, isRetry = true, freshBind = freshBind) }, CAPTURE_RETRY_DELAY_MS)
            },
        )
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
                    if (bitmap == null) return
                    // #105: the service can be toggled off while the screenshot is in
                    // flight — that is the gesture's other half — and the callback
                    // then belongs to a window token the system has already revoked.
                    // Drop the frame rather than trying to draw on it (the overlay
                    // path would otherwise reach the framework as an uncaught
                    // BadTokenException; an on-device toggle caught exactly that).
                    if (isDestroyed) {
                        bitmap.recycle()
                        return
                    }
                    onSuccessAction(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot capture failed with error code: $errorCode")
                    if (isDestroyed) return
                    Toast.makeText(this@OcrAccessibilityService, "Screenshot failed: $errorCode", Toast.LENGTH_SHORT).show()
                    onFailedAction(errorCode)
                }
            })
    }

    private fun showScreenshotOverlay(image: Bitmap) {
        if (isDestroyed || overlayView != null) return
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
        if (isDestroyed || overlayView != null) return
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
                closeOverlay("overlay dismissed")
            }

            override fun requestSoftInputResize() {
                val root = overlayView ?: return
                val p = root.layoutParams as? WindowManager.LayoutParams ?: return
                p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                windowManager.updateViewLayout(root, p)
            }

            // #105: the accessibility overlay draws the view's own back button
            // (top-left). The share activity hosts the same view and declines it
            // in favour of the back control it has had in that corner since #78.
            override fun wantsBackButton(): Boolean = true

            // #106: the screen's text, read from the tree this service already
            // consults for the Settings guard — the active window's root, the same
            // node [activeWindowPackage] reads. Called on IO once per capture by
            // `OcrOverlayView.startOcr`.
            //
            // `ocr` mode skips the walk: nothing downstream would use the result,
            // and walking a WebView's virtual hierarchy is real work. That is also
            // what makes "OCR always" the *old* pipeline rather than "an empty tree
            // routed through the new one".
            override fun screenTextNodes(): List<ScreenTextNode> {
                val mode = ScreenTextPrefs.mode(this@OcrAccessibilityService)
                if (!ScreenTextPrefs.readsTree(mode)) {
                    Log.d(TAG, "screen text: mode=$mode, not reading the tree")
                    return emptyList()
                }
                return try {
                    val screen = JpDictRect(0, 0, image.width, image.height)
                    val roots = screenTextRoots()
                    for (root in roots) {
                        val nodes = ScreenTextReader.read(root, screen)
                        if (nodes.isNotEmpty()) {
                            Log.d(
                                TAG,
                                "screen text: ${ScreenTextReader.lastPackage} answered with ${nodes.size} nodes" +
                                    " (${ScreenTextReader.lastTextNodes} text nodes seen," +
                                    " ${ScreenTextReader.lastJapanese} Japanese," +
                                    " ${ScreenTextReader.lastDroppedLong} dropped as too long)",
                            )
                            return nodes
                        }
                    }
                    // Why the OCR path is about to run on a screen that looked like
                    // it should have text: nothing readable carried any, or nothing
                    // but our own overlay and system chrome was there to read. This
                    // is the line a "the tree path never runs" report needs.
                    Log.d(
                        TAG,
                        "screen text: ${roots.size} readable windows, none answered" +
                            " (${ScreenTextReader.lastTextNodes} text nodes seen," +
                            " ${ScreenTextReader.lastDroppedLong} dropped as too long, mode=$mode)",
                    )
                    emptyList()
                } catch (t: Throwable) {
                    // The tree is not something a capture may fail on. A window that
                    // closed under the walk, a capability the framework withdrew, an
                    // OEM node provider that throws — each of those means "no nodes
                    // this time", which is exactly the pre-#106 path.
                    Log.w(TAG, "screen text: tree walk failed; OCR answers as before", t)
                    emptyList()
                }
            }
        }

        val view = OcrOverlayView(this, host)
        // #105: add the window BEFORE recording the overlay. A token the system has
        // already revoked — the service was toggled off while the screenshot was in
        // flight — then leaves no half-set state to clean up, and cannot reach the
        // framework as an uncaught BadTokenException, which is what an on-device
        // toggle did before this guard ("Unable to add window ... is your activity
        // running?", on the screenshot callback).
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "overlay window refused (service no longer able to draw)", e)
            return
        }
        overlayView = view
        // #105: the one number that says whether a trigger felt immediate — the gap
        // between the activation and the overlay being on screen. A live trigger
        // should read a screenshot's worth (~100-150 ms on the Pixel 7a); anything
        // near the bind's 300 ms means the live path grew the bind's wait back.
        Log.d(TAG, "overlay up ${SystemClock.elapsedRealtime() - lastActivationAt} ms after the '$lastActivationReason' activation")
        // A1/#86: the returned pass gates the deferred engine close in [onDestroy].
        ocrPass = view.startOcr()
    }

    /**
     * #105: double-press volume-down — a per-press trigger we own.
     *
     * The platform's accessibility shortcut *toggles the service*, so it cannot be
     * per-press: the press that disables can never capture, and disabling the
     * service ourselves takes the shortcut's own target binding with it (the
     * shortcut then stops being listed and does nothing). This chord is ours
     * instead — the service stays resident, each double press is exactly one
     * capture, and single presses still change the volume: only the *second* press
     * of a pair is consumed.
     */
    private fun handleVolumeChord(keyEvent: KeyEvent): Boolean {
        if (keyEvent.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount != 0) return false
        val now = SystemClock.elapsedRealtime()
        val previous = lastVolumeDownAt
        lastVolumeDownAt = now
        if (previous == 0L || now - previous > VOLUME_CHORD_MS) return false
        lastVolumeDownAt = 0L
        captureFromActivation("volume double-press")
        return true
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return super.onKeyEvent(event)
        
        // Handle global shortcut when overlay is NOT showing
        if (overlayView == null) {
            // #105: our own volume chord, before and independent of the gamepad pref.
            if (handleVolumeChord(keyEvent)) return true

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

    /**
     * #105: close the overlay. The service itself stays enabled.
     *
     * `disableSelf()` was tried here and is wrong on this platform: it disables the
     * *service*, and the system's accessibility-shortcut target binding goes with
     * it — the shortcut stops being listed and then does nothing when pressed
     * (confirmed on-device). The service is what the triggers are attached to, so it
     * is resident by design; the per-press triggers are the accessibility button,
     * the volume chord in [onKeyEvent], and the shortcut's *enable* press, which
     * captures through [onServiceConnected].
     */
    private fun closeOverlay(reason: String) {
        if (isDestroyed) return
        hideScreenshotOverlay()
        Log.d(TAG, "overlay closed ($reason); the service stays enabled for the next activation")
    }

    /** #105: a configuration screen (ours or a vendor's) — never a capture. */
    private fun isSettingsSurface(pkg: String): Boolean = pkg.contains("settings", ignoreCase = true)

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
        closeOverlay("overlay left behind by $eventPackage")
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
         * #105: how long a FRESH BIND waits before asking for the screenshot.
         *
         * A screenshot request is answered by the window server, and right after a
         * bind it is still tearing the old service instance down — which on the
         * gesture paths includes the floating button's own window, since the gesture
         * that enables the service is the one that disabled it. 300 ms is short
         * enough that the overlay still feels attached to the gesture that asked for
         * it, and long enough for the accessibility windows list to be populated for
         * the Settings gate that runs at capture time.
         */
        private const val BIND_POST_DELAY_MS = 300L

        /**
         * #105: how long a LIVE trigger waits — one that arrives on a service that
         * is already connected (the accessibility button, the volume chord).
         *
         * Nothing is being torn down and no window list has to be read, so this is
         * deliberately not the bind's 300 ms: it is the 50 ms the old app-owned
         * button posted before hiding itself, the only wait that path ever had.
         * What is left after it is the platform's screenshot cost — the same cost
         * the button paid.
         */
        private const val LIVE_POST_DELAY_MS = 50L

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

        /** #105: the window a second volume-down press has to land in. */
        private const val VOLUME_CHORD_MS = 450L

        /** #105: the pause before the one capture retry. */
        private const val CAPTURE_RETRY_DELAY_MS = 500L

        /** #105: the update signal's store (separate from the gamepad prefs). */
        private const val ACCESSIBILITY_PREFS = "accessibility_prefs"
        private const val KEY_SEEN_UPDATE_TIME = "seen_update_time"
    }

}
