package com.holopengin.instantjpdict

import android.content.pm.ActivityInfo

/**
 * #78 follow-up ("the ocr view does not inherit the camera view's orientation but
 * it should"): the orientation rule for [ShareImageActivity] when it is opened BY
 * THE VIEWFINDER, kept pure so the JVM unit tests can pin it — the activity
 * itself is view construction and cannot be.
 *
 * THE PROBLEM. [ProtoCameraActivity] declares `screenOrientation="fullSensor"`,
 * which is the sensor value the platform documents to "use the sensor even if the
 * user locked sensor-based rotation" — so the viewfinder turns with the phone
 * whatever the auto-rotate setting says, and while it is on screen the display
 * itself has turned with it. [ShareImageActivity] declares NO `screenOrientation`
 * (see the manifest: its entry is shared with the exported `ACTION_SEND` filter
 * and must not be pinned), so it obeys the user's rotation setting. With
 * auto-rotate off — exactly the state the viewfinder's `fullSensor` exists to
 * survive — a capture taken holding the phone sideways hands off to an upright
 * portrait OCR view.
 *
 * THE RULE. The camera marks the launch: it puts the hold it was in on the
 * handoff Intent ([EXTRA_CAMERA_HOLD]) and this answers
 * [ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR] for that launch — the same value
 * the viewfinder declares for itself.
 *
 * Why FULL_SENSOR is the same notion of orientation the camera used, rather than
 * a guess at it: the camera's preview stream and its control anchors both come
 * from the SENSOR ([DeviceHold.surfaceRotationFor], applied by
 * `ProtoCameraActivity.applyTargetRotation`), and the only reason its window was
 * the way up it was is that `fullSensor` had the platform resolve it from that
 * same sensor. Asking the OCR view for `fullSensor` puts it on that same source
 * and that same policy, so a landscape hold opens a landscape view — and, because
 * the platform keeps resolving from the sensor after the launch, turning the
 * phone does not leave the view stuck: it is re-created in the new orientation
 * and the image is recomposed at the new container size, so the overlay's box
 * coordinates stay 1:1 with the surface.
 *
 * Why NOT a fixed orientation, which is the tempting alternative ("it was
 * landscape, so ask for landscape"): an orientation request is a property of the
 * activity for as long as it lives, and nothing in the handoff can release it
 * later. Locking the family the camera happened to be in would trade the
 * maintainer's complaint for its mirror image the moment the phone was turned
 * back — a capture-sideways OCR view that refuses to come upright — and the
 * question "which family was the camera in?" is the wrong question anyway: the
 * phone may have moved between the shutter and the OCR view's first frame, and
 * the sensor is the only thing that knows the answer now.
 *
 * It is also idempotent by construction: the answer depends on nothing but the
 * presence of the hold. A later re-creation of the activity (a quarter turn, a
 * process death restore) runs the same call with the SAME, by then stale, camera
 * hold and asks for the same relative-to-the-device thing, so re-adopting can
 * never flip a view that is already the right way up.
 *
 * THE OTHER ENTRY POINT IS UNTOUCHED. A system share sheet sends no extra, so
 * [requestedOrientationFor] answers null for it: [ShareImageActivity] makes no
 * orientation call at all and the activity behaves exactly as it did before — the
 * user's rotation setting, and no `screenOrientation` in the manifest for anyone
 * else to inherit. Nothing about the share path changed, down to the absence of a
 * call.
 */
object InheritedOrientation {

    /**
     * The handoff extra: the camera's hold, as a `Surface` rotation
     * ([DeviceHold]). Public because the camera writes it and the OCR view reads
     * it — one name, both ends.
     *
     * Its PRESENCE is the marker of which entry point this is: the system share
     * sheet sends no such extra, so that is what selects the behaviour, and its
     * absence is what leaves the share path untouched. Its VALUE is the hold the
     * viewfinder was in, carried for the in-hand check — [ShareImageActivity]
     * logs it beside the window it came up in, so "did the OCR view open the way
     * the camera was held?" is answered by one logcat line — and it is the same
     * number the camera gave its use cases and read its control anchors from,
     * not a second reading of the sensor taken somewhere else.
     *
     * The name is namespaced rather than a bare word so no other sender can
     * collide with it by accident.
     */
    const val EXTRA_CAMERA_HOLD = "com.holopengin.instantjpdict.extra.CAMERA_HOLD"

    /** No hold was handed over: not a camera launch. Outside the `Surface`
     *  rotation range (0..3), so a real hold can never be mistaken for it. */
    const val NO_HOLD = -1

    /**
     * What [ShareImageActivity] should ask the window manager for, or null to ask
     * for nothing at all (the system-share entry point — see the class doc).
     */
    fun requestedOrientationFor(hold: Int): Int? =
        if (hold == NO_HOLD) null else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
}
