package com.holopengin.instantjpdict

/**
 * #89: the action that makes [MainActivity] open the manual lookup screen.
 *
 * The pattern is #82's camera shortcut, and so is the reasoning: a static
 * shortcut's `<intent>` (in `shortcuts.xml.template`) can carry an ACTION and
 * nothing else, so the declaration and [MainActivity] meet on one namespaced
 * string and [opensLookup] is the whole rule that decides whether an incoming
 * intent is that shortcut.
 *
 * The contract is equality with [ACTION_OPEN_LOOKUP], not "anything that reaches
 * this activity": the shortcut targets `.MainActivity`, the launcher entry an
 * ordinary tap also starts with `ACTION_MAIN`, and that tap must keep opening the
 * dictionary screen.
 *
 * Why the shortcut goes through [MainActivity] rather than naming
 * [ManualLookupActivity] directly — [ManualLookupActivity] IS exported anyway (it
 * must be, for `ACTION_SEND` and `ACTION_PROCESS_TEXT`), so the export-surface
 * argument #82 used for the camera does not apply. The task-root/Back argument
 * does: a shortcut launched straight into the manual screen would make it the
 * task's ROOT, and Back out of it would leave an empty task. Through
 * [MainActivity] the home screen is the root, below the lookup, which is where
 * Back lands — the same semantics #82 chose, and the acceptance criterion this
 * issue repeats.
 */
object ManualLookupShortcut {

    /**
     * The action the shortcut carries. Namespaced like [CameraShortcut]'s rather
     * than a bare word, so no other sender can collide with it by accident.
     */
    const val ACTION_OPEN_LOOKUP = "com.holopengin.instantjpdict.action.OPEN_LOOKUP"

    /** True when [action] is this shortcut's action, and only then. */
    fun opensLookup(action: String?): Boolean = action == ACTION_OPEN_LOOKUP
}
