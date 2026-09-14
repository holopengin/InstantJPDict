package com.holopengin.instantjpdict

/**
 * #82: the action that makes [MainActivity] open the camera viewfinder instead of
 * the dictionary screen.
 *
 * The static shortcut the launcher (and Pixel's Quick Tap) reads lives in
 * `res/xml/shortcuts.xml`, and a shortcut's `<intent>` can carry an ACTION and
 * nothing else — there is no extras slot in the declaration. So the declaration
 * and [MainActivity] meet on one string, and [opensCamera] is the whole rule that
 * decides whether an incoming intent is that shortcut.
 *
 * The contract is deliberately equality with [ACTION_OPEN_CAMERA], not "anything
 * that reaches this activity": the shortcut targets `.MainActivity`, the same
 * activity an ordinary launcher tap starts with `ACTION_MAIN`, and that tap must
 * keep opening the dictionary screen.
 *
 * Why the shortcut goes through [MainActivity] rather than straight to
 * [ProtoCameraActivity] — the choice this action exists to implement:
 *  - the viewfinder's manifest entry is `android:exported="false"` (and stays
 *    that way). A shortcut's intent is started by the launcher, another app, so
 *    it may only name an exported component; exporting the viewfinder would hand
 *    every app on the device a camera entry point to trigger at will, which is
 *    exactly the surface the ask says not to grow. [MainActivity] is already
 *    exported as the launcher entry, so routing through it adds nothing a
 *    launcher tap could not already reach: the camera UI, whose runtime
 *    permission request is unchanged and whose capture goes nowhere until the
 *    user presses the shutter.
 *  - a shortcut launched straight into the viewfinder would make it the task's
 *    ROOT, so Back out of it would leave an empty task. Through [MainActivity]
 *    the dictionary screen is the root, below the camera, which is where Back
 *    lands.
 * Nothing calls this on the system's behalf and nothing outside the app can
 * reach [ProtoCameraActivity]: the only writer of this action is the shortcut
 * declaration, and the only reader is [MainActivity].
 */
object CameraShortcut {

    /**
     * The action the shortcut carries. Namespaced like the handoff extra
     * ([InheritedOrientation.EXTRA_CAMERA_HOLD]) rather than a bare word, so no
     * other sender can collide with it by accident.
     */
    const val ACTION_OPEN_CAMERA = "com.holopengin.instantjpdict.action.OPEN_CAMERA"

    /** True when [action] is this shortcut's action, and only then. */
    fun opensCamera(action: String?): Boolean = action == ACTION_OPEN_CAMERA
}
