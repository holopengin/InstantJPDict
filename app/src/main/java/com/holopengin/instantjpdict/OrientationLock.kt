package com.holopengin.instantjpdict

import android.content.pm.ActivityInfo
import android.view.Gravity

/**
 * #78: the orientation LOCK — the third orientation notion on this branch, and the
 * only one the user can turn on.
 *
 * [DeviceHold] owns the two notions this branch already had: the SENSOR's
 * ([DeviceHold.surfaceRotationFor], [DeviceHold.isLandscapeHold]), which describes
 * the CAPTURE and is what the preview stream and the image capture are told, and
 * the WINDOW's ([DeviceHold.isLandscapeWindow]), which describes the CONTROLS and
 * is what the anchor decision reads. The lock is neither of those: it says that
 * BOTH of them stop following the phone, so that the user can hold the phone at an
 * awkward angle — over a book, lying down — without the view flipping under them.
 *
 * WHY IT FREEZES THE PAIR, and not just the window. This is the trap the branch
 * has already paid for once. Freezing the WINDOW alone leaves the camera STREAM
 * still following the sensor, so the preview rotates INSIDE a window that is not
 * rotating: the "turned camera stream sitting in an upright portrait window" that
 * [ProtoCameraActivity]'s class doc names as the thing the `fullSensor` change
 * exists to avoid. A lock that did that would be worse than no lock at all — the
 * user asked for a still view and would get a rotating one. Freezing the STREAM
 * alone is the same failure from the other side. So the two halves are written
 * here, side by side, as the one decision:
 *
 *  - [requestedOrientationFor] is the WINDOW's half — the value handed to
 *    `Activity.setRequestedOrientation`;
 *  - [streamRotationFor] is the STREAM's half — the rotation handed to the bound
 *    use cases through `ProtoCameraActivity.applyTargetRotation`.
 *
 * WHY `SCREEN_ORIENTATION_LOCKED`, read out of the platform rather than guessed
 * (frameworks/base `services/core/java/com/android/server/wm/DisplayRotation.java`
 * `rotationForOrientation`, and `core/java/android/content/pm/ActivityInfo.java`,
 * where the constant is 14 and `isFixedOrientation` counts it as fixed):
 *  - it resolves to `preferredRotation = lastRotation` — "the application just
 *    wants to remain locked in the last rotation" is the platform's own comment —
 *    i.e. it holds the rotation the display is ALREADY in and never asks the
 *    sensor, which is exactly "stop following the phone";
 *  - that branch sits ABOVE the ones that consult the sensor and the user's
 *    auto-rotate lock, so a phone with rotation lock on is held just the same, and
 *    the value is independent of every dock/HDMI/demo-rotation case above it;
 *  - a runtime `setRequestedOrientation` OVERRIDES the manifest's
 *    `android:screenOrientation="fullSensor"` (the manifest supplies the activity's
 *    initial value; the runtime call is the later word), so this activity can
 *    declare `fullSensor` and still hold still;
 *  - `configChanges` — which this activity declares — is what makes the hold free:
 *    engaging the lock changes no configuration at all (the rotation it resolves to
 *    is the one already in effect), so nothing is re-created, and the UN-freeze's
 *    later quarter turn arrives as `onConfigurationChanged` instead of as a new
 *    activity.
 *
 * The tempting alternative — requesting the exact quarter back as a FAMILY
 * (`SCREEN_ORIENTATION_LANDSCAPE` / `REVERSE_LANDSCAPE` / `PORTRAIT` / …) — was
 * rejected because the family-to-quarter mapping is DEVICE-DEPENDENT:
 * `mLandscapeRotation` is `ROTATION_90` on a natural-portrait phone and
 * `ROTATION_0` on a natural-landscape one, so a frozen rotation read from the
 * window would have to be translated through a table that is wrong on the other
 * class of device. `LOCKED` needs no translation: the platform holds whatever
 * rotation the window is in, and the stream is told that same rotation.
 *
 * The cost, stated plainly: `isFixedOrientation(LOCKED)` is true, so while the lock
 * is on this window is a fixed-orientation activity to the system — the family that
 * some large-screen / multi-window rules treat specially. On the phone this feature
 * is for, held fullscreen, that has no consequence; a lock used in split-screen on a
 * foldable is the case to re-check on a device.
 *
 * The CONTROL is here too, because it is decided from the same handful of facts and
 * every one of them is a plain function of ints and booleans: what it says
 * ([glyphFor], [descriptionFor]), where it sits ([placementFor]) and which
 * system-bar edges that corner has to clear. That is what lets [OrientationLockTest]
 * pin all of it without a phone.
 */
object OrientationLock {

    /**
     * The system bars' own edges, in pixels, as `ProtoCameraActivity`'s root insets
     * listener last reported them.
     *
     * Only the three the lock control's corner can lie on are here, and that is the
     * point of passing them in rather than reading them: the portrait anchor is
     * against the LEFT edge and the landscape one against the right, and in
     * landscape the bottom edge is a LONG edge with the navigation bar along it — so
     * which inset matters follows from which way up the window is, which is a
     * decision, and decisions belong where they can be read and tested.
     */
    data class SystemBarEdges(val left: Int, val right: Int, val bottom: Int)

    /**
     * One control's place on the window: the [Gravity] it is anchored to, and the
     * raw-pixel margins of that anchor. The same vocabulary
     * `ProtoCameraActivity.placeControl` writes, kept out of the view so the
     * placement is a JVM test rather than a phone.
     *
     * Margins are positive and belong to the anchored edge only; the sides a control
     * is NOT anchored to stay 0, which is what stops a margin left over from the
     * other anchor shifting it (a `marginEnd` on a start-anchored square, say).
     */
    data class ControlPlacement(
        val gravity: Int,
        val startMargin: Int = 0,
        val endMargin: Int = 0,
        val topMargin: Int = 0,
        val bottomMargin: Int = 0,
    )

    /**
     * The WINDOW's half of the lock: `LOCKED` while [locked] — the platform's "hold
     * the rotation that is already in effect" — and the value this activity's
     * manifest declares for itself (`android:screenOrientation="fullSensor"`) while
     * not.
     *
     * The two are the whole window story of the feature: engaging the lock replaces
     * the declaration with a hold, and disengaging it puts the declaration back, so
     * "un-freezes back to exactly today's behaviour" is a value read off the same
     * constant the manifest names rather than a second policy invented here.
     * `OrientationManifestTest` is what keeps that claim honest: it reads the
     * committed manifest, so the free value this returns and the declaration on
     * disk cannot drift apart.
     *
     * See the object doc for why `LOCKED` and not a fixed family.
     */
    fun requestedOrientationFor(locked: Boolean): Int =
        if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

    /**
     * The STREAM's half of the lock: while [locked] the bound use cases keep
     * [frozenRotation], and while free they are told the sensor's own
     * [sensorRotation] again.
     *
     * [frozenRotation] is the WINDOW's rotation — the quarter the platform is
     * holding the window in — and NOT the sensor's last band, and the difference is
     * the whole reason this is a function instead of an `if` at the call site: the
     * sensor reports a new hold BEFORE the platform has played the rotation
     * animation, so for that moment the stream's band and the window's quarter
     * disagree. Freezing the stream at the sensor's band there would put the new
     * hold inside the OLD window — which `LOCKED` then holds — and the preview would
     * rotate inside a window that does not: the exact anti-goal. The window's own
     * rotation is the one value that cannot be wrong, because it is the value the
     * window is about to be held at.
     *
     * [sensorRotation] is what the camera follows when nothing is frozen, so
     * un-freezing is "the sensor again" and never a value of this object's own
     * choosing; when the phone is flat that is its portrait reading
     * ([DeviceHold.surfaceRotationFor] of `ORIENTATION_UNKNOWN`), which is exactly
     * what today's code would have applied.
     */
    fun streamRotationFor(locked: Boolean, frozenRotation: Int, sensorRotation: Int): Int =
        if (locked) frozenRotation else sensorRotation

    /**
     * The requested-orientation value's own name, so a log line can be read without
     * decoding a bare int — the same courtesy `ProtoCameraActivity.rotationName`
     * extends to the surface rotations. Both values this object ever produces have a
     * name.
     */
    fun orientationName(value: Int): String = when (value) {
        ActivityInfo.SCREEN_ORIENTATION_LOCKED -> "SCREEN_ORIENTATION_LOCKED"
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR -> "SCREEN_ORIENTATION_FULL_SENSOR"
        else -> "orientation $value"
    }

    /**
     * What the control shows: a padlock that is shut while [locked] and open while
     * not. Text glyphs, not bundled assets — a new asset would have to satisfy the
     * app's licence index (`app/licenses/components.tsv`, checked by the build's
     * `verifyLicenseIndex` step), and this feature needs nothing drawn.
     *
     * The pair is the chrome's own grammar: the share activity's rotate pair says
     * which way with ⟳/⟲, this one says held-or-not with two forms of one symbol, so
     * the state reads at a glance and not from a label the maintainer would have to
     * read.
     */
    fun glyphFor(locked: Boolean): String = if (locked) LOCKED_GLYPH else UNLOCKED_GLYPH

    /**
     * What the control says to a screen reader — the ACTION a press takes, because
     * that is what a control is for, and the reason the two differ is the same
     * reason two glyphs exist.
     */
    fun descriptionFor(locked: Boolean): String =
        if (locked) UNLOCK_DESCRIPTION else LOCK_DESCRIPTION

    /**
     * Where the lock control sits, for the window on screen, and which system-bar
     * edges it clears while it is there.
     *
     * The anchors are the ask, literally:
     *  - PORTRAIT: bottom-LEFT, plus the LEFT system-bar inset (the edge its corner
     *    lies on, and the only one the ask names for portrait) and the bottom one,
     *    because a control anchored to the bottom edge sits exactly where the
     *    navigation bar is drawn in portrait;
     *  - LANDSCAPE: bottom-RIGHT, plus the RIGHT and BOTTOM insets.
     *
     * Both the right and the bottom inset, because BOTH of those edges are edges this
     * corner lies on and the bar is along one of them depending on how the phone is
     * held and which navigation it uses: the gesture area is a strip along the
     * window's bottom, which in landscape is a LONG edge, while a three-button bar
     * turns onto a side. An inset the system reports as 0 costs nothing and a missed
     * one puts a slice of the control under a bar that swallows the touches, so the
     * rule is the corner's edges and not a guess at which bar is there today.
     *
     * The insets are folded in rather than hardcoded for the same reason the other
     * controls fold them in: this window is edge-to-edge (`targetSdk 35`, and the
     * preview is deliberately full-bleed), so a raw margin would put a slice of the
     * control under a bar.
     *
     * ONE THING TO KNOW ABOUT THESE ANCHORS: they are the WINDOW's corners, which
     * are the ones the ask names, and not the phone's. The window turns with the
     * phone, so portrait's bottom-left really is the physical bottom-left — but in a
     * landscape hold the window's bottom edge is a different physical edge depending
     * on which of the two landscape quarters the phone is in, and the control
     * therefore follows the window rather than the hand. The zoom control on this
     * branch was moved to the PHYSICAL corner for exactly that reason (see
     * `ProtoCameraActivity.applyControlAnchors`), so the two controls can sit at
     * opposite ends of the same edge in one of the two landscape quarters. That is
     * reported rather than "fixed" here: it is what the ask says, and whether this
     * control wants the same physical-corner treatment is the maintainer's call.
     */
    fun placementFor(
        windowLandscape: Boolean,
        marginPx: Int,
        bars: SystemBarEdges,
    ): ControlPlacement = if (windowLandscape) {
        ControlPlacement(
            gravity = Gravity.BOTTOM or Gravity.END,
            endMargin = marginPx + bars.right,
            bottomMargin = marginPx + bars.bottom,
        )
    } else {
        ControlPlacement(
            gravity = Gravity.BOTTOM or Gravity.START,
            startMargin = marginPx + bars.left,
            bottomMargin = marginPx + bars.bottom,
        )
    }

    /** U+1F512 LOCK: the state a press turns ON. */
    private const val LOCKED_GLYPH = "\uD83D\uDD12"

    /** U+1F513 OPEN LOCK: the state a press turns OFF. */
    private const val UNLOCKED_GLYPH = "\uD83D\uDD13"

    private const val LOCK_DESCRIPTION = "Lock orientation"
    private const val UNLOCK_DESCRIPTION = "Unlock orientation"
}
