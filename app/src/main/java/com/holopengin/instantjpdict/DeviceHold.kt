package com.holopengin.instantjpdict

import android.content.res.Configuration
import android.view.Surface

/**
 * #78: which way up the phone is being held — and, separately, which way up the
 * WINDOW is — in the one vocabulary both windows on this branch speak.
 *
 * TWO NOTIONS, and the reason this file has both. They agree almost always, and
 * the cases where they do not are exactly the ones the maintainer reported:
 *
 *  - [isLandscapeHold] is the SENSOR's answer, and it describes the CAPTURE: a
 *    photo has to be written the way up the hand is holding the phone, so the
 *    preview stream and the image capture are told this one
 *    (`ProtoCameraActivity.applyTargetRotation`). It is a `Surface` rotation
 *    because that is the vocabulary CameraX takes.
 *  - [isLandscapeWindow] is the WINDOW's answer — the orientation the UI is
 *    actually laid out and drawn in — and it describes the CONTROLS: where a
 *    button is anchored has to be the edge of the window that exists, so the
 *    anchors are re-decided when the window changes
 *    (`ProtoCameraActivity.onConfigurationChanged`).
 *
 * Why a button may not be anchored off the sensor: the two update at different
 * times, and the sensor also reports states the window never has.
 *  - The window turns when the platform plays its rotation ANIMATION, which is
 *    later than the sensor's report of the new hold. Anchored off the sensor, a
 *    button jumps to its landscape edge while the window is still portrait (and
 *    the button itself has not turned yet), which is the "moves the button
 *    before the phone's native orientation switch rotate animation happens …
 *    they just jump weirdly" half of the report.
 *  - Laid flat, the phone's orientation is UNKNOWN ([ORIENTATION_UNKNOWN], -1),
 *    which [surfaceRotationFor] reads as portrait because that is the safe
 *    reading for a CAPTURE. The WINDOW is still landscape — it does not turn
 *    because a phone was tipped onto a table — so anchoring off the sensor moves
 *    two buttons that nothing on screen moved with: "if you tilt the phone down
 *    while in landscape the buttons move despite the rotate never triggering".
 *
 * The two functions are plain ints in and ints/booleans out, so the JVM unit
 * tests pin them without a phone:
 *  - [DeviceHoldTest] pins the bands, the portrait/landscape hold rule including
 *    every degree, and the window rule — including the two disagreeing cases
 *    above, which is the statement "the anchors do not move before the window
 *    does, and a flat phone moves nothing";
 *  - the `Surface` and `Configuration` constants are compile-time constants, so
 *    the tests read them inlined rather than through the stub Android classes.
 *
 * A THIRD NOTION lives beside these two rather than in this file: the orientation
 * LOCK ([OrientationLock]) is not another reading of the phone — it is the switch
 * that stops BOTH of the readings above from following it, and it is kept separate
 * on purpose. These two functions stay what they are, readings with no state, and
 * the lock answers its own questions (the value the WINDOW is told, the value the
 * STREAM is frozen at, and where its control sits) in one pure place of its own.
 */
object DeviceHold {

    /** The sensor's "I do not know", which arrives while the phone is flat or is
     *  being moved. Spelt here so the reasoning above can name it. */
    const val ORIENTATION_UNKNOWN = -1

    /**
     * The rotation of what the camera writes, from the phone's own orientation.
     *
     * The bands are CameraX's own (androidx.camera.view.RotationProvider
     * .orientationToSurfaceRotation, camera-view 1.4.2 — the mapping
     * LifecycleCameraController's device-rotation handling is built on), so a
     * quarter turn here means the same thing it means to CameraX: each band is
     * 90 degrees wide around a cardinal hold, and everything else — including
     * [ORIENTATION_UNKNOWN], which arrives as -1 while the phone is flat or is
     * being moved — reads as portrait.
     *
     * The SENSOR's answer, so this is the STREAM's and the capture's notion of
     * orientation, never the controls': see [isLandscapeWindow].
     *
     * | orientation (degrees) | targetRotation      |
     * | 45..134               | ROTATION_270        |
     * | 135..224              | ROTATION_180        |
     * | 225..314              | ROTATION_90         |
     * | else (incl. -1)       | ROTATION_0          |
     */
    fun surfaceRotationFor(orientation: Int): Int = when {
        orientation in 45..134 -> Surface.ROTATION_270
        orientation in 135..224 -> Surface.ROTATION_180
        orientation in 225..314 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

    /**
     * Which way up the phone is being HELD, from a surface rotation — the
     * CAPTURE's notion of orientation.
     *
     * Held upside down (ROTATION_180) counts as portrait, and it is the right
     * answer there — the whole window turns with the phone, so its bottom edge is
     * still the one under the thumb.
     *
     * This is NOT what the control anchors ask any more (they ask
     * [isLandscapeWindow]), and the difference is the point: this answer moves
     * the moment the sensor reports the turn, while the window — and therefore
     * the edge a button can be anchored to — moves when the system's rotation
     * animation lands. The readers left are the handoff diagnostics, which name
     * the hold the viewfinder was in ([ProtoCameraActivity.handOff] carries it as
     * [InheritedOrientation.EXTRA_CAMERA_HOLD] and the OCR view logs it), and the
     * pure tests.
     */
    fun isLandscapeHold(rotation: Int): Boolean =
        rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

    /**
     * Which way up the WINDOW is, from its own [Configuration.orientation] — the
     * CONTROLS' notion of orientation, and the one thing a control anchor may be
     * decided from.
     *
     * `Configuration.ORIENTATION_LANDSCAPE` is the window the UI is laid out in:
     * it changes when the platform turns the window (i.e. with the system
     * rotation animation, not when the sensor first notices), it does not change
     * when a phone is tipped flat, and it is what `ProtoCameraActivity` already
     * read at cold start. [ORIENTATION_UNDEFINED] and [ORIENTATION_PORTRAIT] are
     * both portrait, so an unknown window never reads as landscape by accident.
     *
     * Landscape here is deliberately only the family, not the quarter turn: which
     * of the two landscape quarters it is does not matter to a layout anchored to
     * the right edge, so nothing has to ask.
     */
    fun isLandscapeWindow(orientation: Int): Boolean =
        orientation == Configuration.ORIENTATION_LANDSCAPE
}
