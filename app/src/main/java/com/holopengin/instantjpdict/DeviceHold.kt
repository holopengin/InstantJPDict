package com.holopengin.instantjpdict

import android.view.Surface

/**
 * #78: which way up the phone is being held, in the one vocabulary both windows
 * on this branch speak — the `Surface` rotation.
 *
 * This is the SINGLE definition of the two steps the viewfinder takes a hold
 * through, and the reason it is a file of its own: the OCR view the viewfinder
 * hands off to now opens in the hold the camera was in, and it has to agree with
 * the viewfinder about which way up that is. Moving the rule here means the
 * camera's control anchors ([ProtoCameraActivity.applyControlAnchors]) and the
 * share view's inherited orientation ([InheritedOrientation]) are not two
 * implementations of "landscape" that can drift — they are the same call.
 *
 * Both functions are plain ints in and ints/booleans out, so the JVM unit tests
 * pin them without a phone:
 *  - [DeviceHoldTest] pins the bands and the portrait/landscape rule, including
 *    every degree, because the second test is really the statement "the OCR
 *    view cannot disagree with what the viewfinder showed";
 *  - the `Surface` constants are compile-time constants, so the tests read them
 *    inlined rather than through the stub Android classes.
 */
object DeviceHold {

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
    fun surfaceRotationFor(orientation: Int): Int = when {
        orientation in 45..134 -> Surface.ROTATION_270
        orientation in 135..224 -> Surface.ROTATION_180
        orientation in 225..314 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

    /**
     * Which way up the window is, from a surface rotation.
     *
     * This is the only place a rotation becomes portrait-or-landscape, so the
     * camera's controls cannot disagree with the camera stream about which way is
     * up, and the OCR view that inherits the hold cannot disagree with either:
     * all of them ask about the same number. Held upside down (ROTATION_180)
     * counts as portrait, and it is the right answer there — the whole window
     * turns with the phone, so its bottom edge is still the one under the thumb.
     */
    fun isLandscapeHold(rotation: Int): Boolean =
        rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
}
