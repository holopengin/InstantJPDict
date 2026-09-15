package com.holopengin.instantjpdict

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
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
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import kotlin.math.abs
import kotlin.math.roundToInt

class OcrAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var ocrButton: Button? = null
    /** Set in onDestroy so restore paths never re-add windows during teardown. (#60) */
    private var isDestroyed = false
    /**
     * #78: what the floating button's own paths (screen off/on, a capture in
     * flight, the gamepad shortcut) last asked for. Latched rather than written
     * straight to the view, so [applyFloatingButtonVisibility] can recombine it
     * with whether one of the app's own views is in front: two independent writers
     * on one `visibility` field would each silently undo the other.
     */
    private var floatingButtonRequested = true
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
    private val controller = OcrOverlayStateController()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    

    private val overlayControllerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    hideScreenshotOverlay()
                    if (intent.action == Intent.ACTION_SCREEN_OFF) {
                        hideFloatingButton()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Double-tap power to camera turns the screen off and on
                    // without locking: USER_PRESENT never follows, so the
                    // button would stay GONE until the next unlock. (#60)
                    // Stay hidden on the keyguard itself; USER_PRESENT shows it.
                    ensureFloatingButton()
                    val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (!km.isKeyguardLocked) showFloatingButton()
                }
                Intent.ACTION_USER_PRESENT -> {
                    ensureFloatingButton()
                    showFloatingButton()
                }
            }
        }
    }
    
    
    private val pressedKeys = mutableSetOf<Int>()
    private var lastGlobalTriggerTime = 0L



    override fun onCreate() {
        super.onCreate()
        ocrEngine = OcrEngine(this)
        OverlayEnvironment.prepare(this, controller, serviceScope)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        
        ContextCompat.registerReceiver(
            this,
            overlayControllerReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        // #78: watch the app's own views so the floating button can step out of their
        // way. Any previous registration is released first: onDestroy is not guaranteed
        // to run (a force-stop, a crash), and two live copies of the callback would both
        // write the same state. The state itself is the process-wide [ownViewsStarted],
        // so a restart here does not lose a view that is already in front.
        ownViewCallbacks?.let { application.unregisterActivityLifecycleCallbacks(it) }
        application.registerActivityLifecycleCallbacks(ownViewLifecycle)
        ownViewCallbacks = ownViewLifecycle
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayView != null) {
            hideScreenshotOverlay()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
        // Reconnects must not duplicate the button; creation happens once. (#60)
        if (floatingView == null) addFloatingButton() else ensureFloatingButton()
    }

    private fun addFloatingButton() {
        if (floatingView?.isAttachedToWindow == true) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        floatingParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        val frameLayout = FrameLayout(this)
        ocrButton = CenteredButton(this).apply {
            background = logoButtonBackground(this@OcrAccessibilityService)
            val size = (44 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = 0.3f
        }
        frameLayout.addView(ocrButton)
        
        ocrButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = floatingParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val displayMetrics = resources.displayMetrics
                        val maxX = displayMetrics.widthPixels - v.width
                        val maxY = displayMetrics.heightPixels - v.height

                        val newX = (initialX + (event.rawX - initialTouchX).roundToInt()).coerceIn(0, maxX)
                        val newY = (initialY + (event.rawY - initialTouchY).roundToInt()).coerceIn(0, maxY)
                        params.x = newX
                        params.y = newY
                        
                        val fv = floatingView ?: return false
                        if (fv.parent == overlayView) {
                            val lp = fv.layoutParams as FrameLayout.LayoutParams
                            lp.leftMargin = newX
                            lp.topMargin = newY
                            fv.layoutParams = lp
                        } else if (fv.isAttachedToWindow) {
                            windowManager?.updateViewLayout(fv, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY
                        if (abs(diffX) < 10 && abs(diffY) < 10) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        ocrButton?.setOnClickListener {
            if (overlayView != null) {
                hideScreenshotOverlay()
                return@setOnClickListener
            }
            
            hideFloatingButton()
            it.postDelayed({
                triggerCapture { bitmap ->
                    showScreenshotOverlay(bitmap)
                }
            }, 50)
        }
        
        floatingView = frameLayout
        windowManager?.addView(floatingView, floatingParams)
        // #78: the button is created visible, but a view of ours may already be in
        // front (this service starting while the camera is up), so the decision is
        // taken once here too rather than left to the next lifecycle event.
        applyFloatingButtonVisibility()
    }

    /**
     * Re-add the floating button's existing view (with its visibility and
     * dragged position intact) if the system dropped its window without
     * telling us — e.g. the secure camera from double-tap power. (#60)
     * Cheap no-op when attached, so it is safe to call from every
     * window-state change. Never creates a second view.
     */
    private fun ensureFloatingButton() {
        val fv = floatingView ?: return
        if (!shouldReattachFloatingButton(true, fv.isAttachedToWindow, isDestroyed)) return
        try {
            val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager = wm
            wm.addView(fv, floatingParams)
        } catch (e: Exception) {
            Log.e("OcrAccessibilityService", "Error restoring floating button", e)
        }
    }

    // ---- #78: the floating button over the app's own views ----

    /**
     * #78: the app's own full-screen views get out of the floating button's way.
     * While [ProtoCameraActivity] (the viewfinder) or [ShareImageActivity] (the
     * image-share / camera-to-OCR view) is in front, the trigger is hidden: both
     * run the same OCR surface from inside the app, and floating a second trigger
     * over them is noise — in the camera view it is a button drawn with the very
     * graphic the shutter now uses.
     *
     * The SCREENSHOT OVERLAY is deliberately not in that set. It is the button's
     * own home — the button is the overlay's trigger and the visible half of the
     * close button that takes its position — so its behaviour there is unchanged,
     * down to staying visible over it.
     *
     * Mechanism: [Application.registerActivityLifecycleCallbacks], not a flag the
     * activities set. Every component here is in one process (the manifest
     * declares no `android:process`), so no IPC is needed either way; what the
     * callbacks buy is that the state lives on the Application, which outlives this
     * service — a service restarted mid-flow (or one that never saw onDestroy)
     * finds the camera still in [ownViewsStarted] and keeps the button hidden,
     * where a flag the activities set would have to be re-set by a lifecycle event
     * that already happened. The callback is also idempotent on visibility: it
     * re-runs [applyFloatingButtonVisibility] on every entry and exit path, so a
     * path that forgot to restore the button cannot leave the overlay flow
     * inheriting a hidden one.
     *
     * STARTED, not RESUMED, and that is what keeps a handoff from flashing: the
     * documented order for A → B in one task is A.onPause, B.onCreate/onStart/
     * onResume, A.onStop — so the incoming view is already in the set before the
     * outgoing one leaves it. With resumed/paused the camera's pause would empty
     * the set for the moment before the share activity resumed.
     *
     * That same ordering is why [ownViewsStarted] counts instances and is not a set
     * of names: for an A → B handoff between two instances of one class, B's onStart
     * precedes A's onStop, so a set keyed by class name would have B's own entry
     * removed by A's exit and the trigger would reappear over B. The count takes the
     * class to 2 and back to 1 instead, and only the last instance to leave empties
     * it.
     */
    private val ownViewLifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (!isOwnForegroundView(activity)) return
            ownViewStartedCounting(activity.javaClass.name)
            applyFloatingButtonVisibility()
        }

        override fun onActivityStopped(activity: Activity) {
            if (!isOwnForegroundView(activity)) return
            ownViewStoppedCounting(activity.javaClass.name)
            applyFloatingButtonVisibility()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /**
     * #78: the single writer of the floating button's visibility: the button's own
     * request AND none of the app's own views in front. Every path goes through it —
     * a capture in flight, the screen going off, the share activity finishing (its
     * onStop), the camera resuming or stopping — so the screenshot overlay can never
     * inherit a hidden button from any of them.
     */
    private fun applyFloatingButtonVisibility() {
        val fv = floatingView ?: return
        fv.visibility = if (floatingButtonRequested && ownViewsStarted.isEmpty()) View.VISIBLE else View.GONE
    }

    /** #78: hide the button, for the paths that already did (screen off, a capture in
     *  flight). Recorded as a request so a lifecycle change does not lose it. */
    private fun hideFloatingButton() {
        floatingButtonRequested = false
        applyFloatingButtonVisibility()
    }

    /** #78: show the button, for the paths that already did (screen on, unlock, the
     *  capture callback, the overlay closing). Same recorded request. */
    private fun showFloatingButton() {
        floatingButtonRequested = true
        applyFloatingButtonVisibility()
    }

    private fun triggerCapture(onSuccessAction: (Bitmap) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    showFloatingButton()
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, true)
                    buffer.close()
                    if (bitmap != null) {
                        onSuccessAction(bitmap)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    showFloatingButton()
                    Log.e("OcrAccessibilityService", "Screenshot capture failed with error code: $errorCode")
                    Toast.makeText(this@OcrAccessibilityService, "Screenshot failed: $errorCode", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun showScreenshotOverlay(image: Bitmap) {
        if (overlayView != null) return
        controller.resetState()
        Log.d("OcrAccessibilityService", "showScreenshotOverlay detThresh=${OcrEngine.getDetThresh(this)} longSide=${OcrEngine.getDetLongSide(this)}")

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
        // image source, the dismiss request and the floating-button sync.
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
                windowManager?.updateViewLayout(root, p)
            }

            override fun closeButtonOrigin(): Pair<Int, Int> =
                (floatingParams?.x ?: 100) to (floatingParams?.y ?: 100)

            override fun onCloseButtonMoved(x: Int, y: Int) {
                floatingParams?.x = x
                floatingParams?.y = y
            }
        }

        val view = OcrOverlayView(this, host)
        overlayView = view
        windowManager?.addView(view, params)
        // A1/#86: the returned pass gates the deferred engine close in [onDestroy].
        ocrPass = view.startOcr()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return super.onKeyEvent(event)
        
        // Handle global shortcut when overlay is NOT showing
        if (overlayView == null) {
            val prefs = getSharedPreferences("gamepad_prefs", Context.MODE_PRIVATE)
            val globalShortcutEnabled = prefs.getBoolean("global_shortcut_enabled", true)
            
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
                            
                            // Trigger OCR (same logic as floating button click)
                            hideFloatingButton()
                            ocrButton?.postDelayed({
                                triggerCapture { bitmap ->
                                    showScreenshotOverlay(bitmap)
                                }
                            }, 50)
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
        (floatingView?.parent as? android.view.ViewGroup)?.removeView(floatingView)
        if (view.isAttachedToWindow) try { windowManager?.removeViewImmediate(view) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error removing overlay", e) }
        // #78: through the one writer — if one of the app's own views is in front
        // (an overlay over our own camera view, launched from the gamepad shortcut)
        // the button stays hidden rather than being restored onto it.
        showFloatingButton()
        controller.resetState()
        ensureFloatingButton()
        floatingView?.let { fv ->
            if (fv.isAttachedToWindow) try { windowManager?.updateViewLayout(fv, floatingParams) } catch (e: Exception) { Log.e("OcrAccessibilityService", "Error syncing floating button layout", e) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val view = overlayView
        if (view == null) {
            // No overlay open: restore the floating button if the system
            // dropped its window (e.g. returning from the camera). (#60)
            ensureFloatingButton()
            return
        }
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
        // #78: release the lifecycle watch with the service. The state itself
        // ([ownViewsStarted]) is deliberately NOT cleared: it describes the app's
        // views, not this service, and a restarted service reads it back.
        application.unregisterActivityLifecycleCallbacks(ownViewLifecycle)
        ownViewCallbacks = null
        hideScreenshotOverlay()
        floatingView?.let { if (it.isAttachedToWindow) windowManager?.removeView(it) }
        // A1/#86: never close the nets while a pass is inside them. A service can be
        // destroyed mid-pass (toggled off, rebound, data change); `hideScreenshotOverlay`
        // cancels the overlay scope, but cancellation stops a coroutine, not the native
        // detect it is blocked in. The same guard as the share activity's onDestroy —
        // both run through [closeEngineBehindPass] so they cannot drift again.
        closeEngineBehindPass(ocrPass) { ocrEngine.close() }
    }

    private companion object {
        /**
         * #78: the app's own views whose foreground presence hides the floating
         * button — the camera viewfinder and the image-share/OCR view. The
         * screenshot overlay is not here: it is the button's own home.
         */
        private val OWN_FOREGROUND_VIEWS = setOf(
            ProtoCameraActivity::class.java.name,
            ShareImageActivity::class.java.name
        )

        /**
         * #78: how many STARTED instances of each of the app's own views are in front,
         * keyed by class name.
         *
         * A COUNT and not a set of names. The lifecycle order for A → B in one task
         * delivers the incoming activity's onStart BEFORE the outgoing one's onStop
         * ([ownViewLifecycle]), so for two instances of the SAME class a set would have
         * B's entry removed by A's exit: the map would come out empty while B was still
         * in front, and the floating trigger would reappear over it. Counting makes the
         * handoff additive — A's entry is still there when B arrives (2), and A's exit
         * only takes it back to 1 — so the trigger stays hidden until the last instance
         * leaves. The normal case is unchanged: a single view in front is a count of 1
         * and an empty map is what [applyFloatingButtonVisibility] tests.
         *
         * Process-wide on purpose, not a field of the service: everything here is in
         * one process, and the Application — this static with it — outlives the
         * service. A service that is destroyed and recreated while the camera is up
         * therefore still finds the camera here and keeps the button hidden, instead
         * of waiting for a lifecycle event that has already been delivered. The
         * callback that maintains it lives on the same Application, so a view can
         * only be in it while its activity really is started.
         *
         * Keyed by NAME rather than by the Activity: nothing reads which instance is
         * in front, only whether any of the app's own views is, and an instance handed
         * to one callback is never needed by another.
         */
        private val ownViewsStarted = java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())

        /** #78: one more started instance of [name]. See [ownViewsStarted]. */
        private fun ownViewStartedCounting(name: String) {
            synchronized(ownViewsStarted) {
                ownViewsStarted[name] = (ownViewsStarted[name] ?: 0) + 1
            }
        }

        /**
         * #78: one fewer started instance of [name], and the entry goes at zero — so
         * an exit that arrives without a matching start (a state left over from a
         * previous service, say) cannot leave a negative count that would keep the
         * trigger hidden for good.
         */
        private fun ownViewStoppedCounting(name: String) {
            synchronized(ownViewsStarted) {
                val count = ownViewsStarted[name] ?: return
                if (count <= 1) ownViewsStarted.remove(name) else ownViewsStarted[name] = count - 1
            }
        }

        /** #78: the callback registered by the live service, so a restart releases it. */
        private var ownViewCallbacks: Application.ActivityLifecycleCallbacks? = null

        /** #78: is [activity] one of the app's own full-screen views? */
        private fun isOwnForegroundView(activity: Activity): Boolean =
            activity.javaClass.name in OWN_FOREGROUND_VIEWS
    }

}
