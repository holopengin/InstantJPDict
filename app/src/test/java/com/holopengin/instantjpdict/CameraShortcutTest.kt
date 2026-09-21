package com.holopengin.instantjpdict

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #82: which launcher intent opens the viewfinder.
 *
 * The static shortcut can carry an ACTION and no extras (`shortcuts.xml.template`
 * has no place to put them), so the declaration and `MainActivity` meet on one
 * string and nothing else. That string is the seam these tests pin: change the
 * rule without changing the declaration (or the other way round) and the shortcut
 * silently opens the dictionary screen instead of the camera, which a device —
 * and these tests — should catch.
 *
 * The false cases matter as much as the true one: the shortcut targets
 * `.MainActivity`, the same activity an ordinary launcher tap starts with
 * `ACTION_MAIN`, and that tap must keep opening the dictionary screen. The rule
 * has to be equality with the shortcut's own action, not "anything that reaches
 * this activity".
 */
class CameraShortcutTest {

    @Test
    fun theShortcutActionOpensTheCamera() {
        assertTrue(CameraShortcut.opensCamera(CameraShortcut.ACTION_OPEN_CAMERA))
    }

    @Test
    fun anOrdinaryLauncherTapDoesNot() {
        // The launcher's own entry point names the SAME activity with ACTION_MAIN.
        assertFalse(CameraShortcut.opensCamera(Intent.ACTION_MAIN))
    }

    @Test
    fun anIntentWithNoActionDoesNot() {
        assertFalse(CameraShortcut.opensCamera(null))
    }

    @Test
    fun anotherAppsActionDoesNot() {
        assertFalse(CameraShortcut.opensCamera("com.example.other.action.OPEN_CAMERA"))
    }
}
