package com.holopengin.instantjpdict

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #89: which launcher intent opens the manual lookup screen.
 *
 * The mirror of [CameraShortcutTest], and it matters for the same reason: the
 * static shortcut can carry an ACTION and no extras, so the declaration
 * (`shortcuts.xml.template`) and `MainActivity` meet on one string. Change the
 * rule without the declaration (or the other way round) and the shortcut silently
 * opens the dictionary screen instead of the lookup.
 *
 * The false cases are the point: the lookup shortcut targets `.MainActivity`, the
 * same activity an ordinary launcher tap starts with `ACTION_MAIN` and the same
 * one the CAMERA shortcut names — so the rule must be equality with the lookup
 * action, not "anything that reaches this activity", and it must not swallow the
 * camera's action either.
 */
class ManualLookupShortcutTest {

    @Test
    fun theShortcutActionOpensTheManualLookup() {
        assertTrue(ManualLookupShortcut.opensLookup(ManualLookupShortcut.ACTION_OPEN_LOOKUP))
    }

    @Test
    fun anOrdinaryLauncherTapDoesNot() {
        assertFalse(ManualLookupShortcut.opensLookup(Intent.ACTION_MAIN))
    }

    @Test
    fun anIntentWithNoActionDoesNot() {
        assertFalse(ManualLookupShortcut.opensLookup(null))
    }

    @Test
    fun theCameraShortcutDoesNotOpenTheLookup() {
        // The two shortcuts share the same target activity; neither may answer to
        // the other's action, or both would open the same screen.
        assertFalse(ManualLookupShortcut.opensLookup(CameraShortcut.ACTION_OPEN_CAMERA))
        assertFalse(CameraShortcut.opensCamera(ManualLookupShortcut.ACTION_OPEN_LOOKUP))
    }

    @Test
    fun anotherAppsActionDoesNot() {
        assertFalse(ManualLookupShortcut.opensLookup("com.example.other.action.OPEN_LOOKUP"))
    }
}
