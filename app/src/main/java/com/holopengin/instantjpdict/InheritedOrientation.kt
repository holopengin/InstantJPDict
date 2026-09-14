package com.holopengin.instantjpdict

import android.content.pm.ActivityInfo

/**
 * #78 follow-up ("the ocr view does not inherit the camera view's orientation but
 * it should"): the orientation vocabulary of the [ShareImageActivity] handoff,
 * kept pure so the JVM unit tests can pin it — the activity itself is view
 * construction and cannot be.
 *
 * HOW IT IS APPLIED NOW. [ShareImageActivity] DECLARES
 * `android:screenOrientation="fullSensor"` in the manifest, the same value
 * [ProtoCameraActivity] declares for itself, so the FIRST layout is already the
 * hold the camera was in and `fullSensor` keeps following the sensor afterwards.
 * This object no longer writes anything: the runtime request it used to serve
 * (`requestedOrientation` set from the extra, in `onCreate`) resolved after the
 * first layout — the activity came up in the launch orientation and was
 * re-created when the request landed, which is the "starts portrait, then it
 * rotates" the maintainer saw.
 *
 * Why FULL_SENSOR is the same notion of orientation the camera used, rather than
 * a guess at it: the camera's preview stream and its control anchors both come
 * from the SENSOR ([DeviceHold.surfaceRotationFor], applied by
 * `ProtoCameraActivity.applyTargetRotation`), and the only reason its window was
 * the way up it was is that `fullSensor` had the platform resolve it from that
 * same sensor. Declaring the same value on the OCR view puts it on that same
 * source and that same policy, so a landscape hold opens a landscape view — and,
 * because the platform keeps resolving from the sensor after the launch, turning
 * the phone does not leave the view stuck: it is re-created in the new
 * orientation and the image is recomposed at the new container size, so the
 * overlay's box coordinates stay 1:1 with the surface.
 *
 * Why NOT a fixed orientation, which is the tempting alternative ("it was
 * landscape, so ask for landscape"): pinning the family the camera happened to be
 * in would trade the maintainer's complaint for its mirror image the moment the
 * phone was turned back — a capture-sideways OCR view that refuses to come
 * upright — and it would pin the exported `ACTION_SEND` entry point too, which is
 * the one path the ask excludes. `fullSensor` follows the device; it does not
 * freeze anything.
 *
 * WHAT IS LEFT HERE. The extra is still carried and still read, but only for
 * logging: [EXTRA_CAMERA_HOLD] is the marker that says which entry point this is
 * (the system share sheet sends no such extra, so the line is a camera handoff
 * line), and [requestedOrientationFor] is the value the log names as what the
 * manifest declares. Presence of the extra no longer selects any behaviour — the
 * manifest's declaration applies to both entry points — so nothing here can
 * disagree with it.
 */
object InheritedOrientation {

    /**
     * The handoff extra: the camera's hold, as a `Surface` rotation
     * ([DeviceHold]). Public because the camera writes it and the OCR view reads
     * it — one name, both ends.
     *
     * The system share sheet sends no such extra, so its presence is what makes
     * the handoff log line ([ShareImageActivity.logCameraHold]) a camera line.
     * Its VALUE is the hold the viewfinder was in, carried for the in-hand check —
     * the OCR view logs it beside the window it came up in, so "did the OCR view
     * open the way the camera was held?" is answered by one logcat line — and it
     * is the same number the camera gave its use cases and read its control
     * anchors from, not a second reading of the sensor taken somewhere else.
     *
     * The name is namespaced rather than a bare word so no other sender can
     * collide with it by accident.
     */
    const val EXTRA_CAMERA_HOLD = "com.holopengin.instantjpdict.extra.CAMERA_HOLD"

    /** No hold was handed over: not a camera launch. Outside the `Surface`
     *  rotation range (0..3), so a real hold can never be mistaken for it. */
    const val NO_HOLD = -1

    /**
     * The orientation the OCR view's manifest entry declares — `fullSensor`, the
     * same value the viewfinder declares for itself — or null when there was no
     * camera handoff at all (the system-share entry point).
     *
     * Answering null for [NO_HOLD] is what keeps the share sheet from logging a
     * camera line. Nothing calls this to SET an orientation any more; the value it
     * answers is the one the log line compares the first layout against.
     */
    fun requestedOrientationFor(hold: Int): Int? =
        if (hold == NO_HOLD) null else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
}
