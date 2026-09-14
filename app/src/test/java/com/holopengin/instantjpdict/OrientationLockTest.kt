package com.holopengin.instantjpdict

import android.content.pm.ActivityInfo
import android.view.Gravity
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #78: the orientation LOCK, pinned without a phone. [OrientationLock] is plain ints
 * and booleans in, plain ints and booleans out — the same shape [DeviceHoldTest]
 * holds [DeviceHold] to — which is what makes the two load-bearing claims here
 * checkable at all, because the alternative is holding a phone at an awkward angle
 * and watching.
 *
 * THE TWO THAT MATTER:
 *  - [lockingFreezesTheStreamAtTheWindowsRotationAndNotTheSensorsNewBand] is the
 *    trap this feature exists inside: the sensor reports a new hold BEFORE the
 *    platform turns the window, and `SCREEN_ORIENTATION_LOCKED` then holds the OLD
 *    window — so freezing the stream at the sensor's new band would leave the
 *    preview rotating inside a window that does not. The test names the two values
 *    and asserts which one the lock takes.
 *  - [unlockingIsTheSensorAgainForEveryReadingTheDeviceCanReport] is the other end
 *    of the same claim: un-freezing is "the sensor" at every one of the 360
 *    degrees, including the flat-phone reading, i.e. exactly what today's code
 *    would have applied — never a value [OrientationLock] chose for itself.
 *
 * The control's own facts (its corner, the insets that corner takes, and its state)
 * are here too, because they are the rest of the ask and they are just as
 * phone-free: [thePortraitCornerTakesTheLeftInsetAndTheLandscapeOneTheRight] is the
 * one that would otherwise be discovered by a button sitting under a navigation bar.
 *
 * The `Surface`, `Gravity`, `Configuration` and `ActivityInfo` constants read here
 * are compile-time constants, so (as in [DeviceHoldTest]) they come in inlined
 * rather than through the stub Android classes.
 */
class OrientationLockTest {

    private val margin = 32

    private fun bars(left: Int = 0, right: Int = 0, bottom: Int = 0) =
        OrientationLock.SystemBarEdges(left = left, right = right, bottom = bottom)

    /**
     * The window's half: the platform's "hold the rotation already in effect" while
     * locked, and the value this activity's manifest itself declares while not.
     * `OrientationManifestTest` reads the committed manifest, so the free value here
     * and the `fullSensor` declaration on disk are held to each other from both
     * sides.
     */
    @Test
    fun theWindowIsHeldWhileLockedAndFollowsTheSensorAgainWhenNot() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            OrientationLock.requestedOrientationFor(locked = true)
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            OrientationLock.requestedOrientationFor(locked = false)
        )
        assertNotEquals(
            "the held value and the free value must not be the same value",
            OrientationLock.requestedOrientationFor(locked = true),
            OrientationLock.requestedOrientationFor(locked = false)
        )
    }

    @Test
    fun lockingFreezesTheStreamAtTheWindowsRotationAndNotTheSensorsNewBand() {
        // The window is landscape (ROTATION_90) and the sensor has ALREADY reported
        // the turn to portrait (ROTATION_0) — the state the two are in for the moment
        // between the sensor's report and the platform's rotation animation. What the
        // lock does is hold the WINDOW, and LOCKED holds the rotation already in
        // effect, so the stream has to stay on the window's quarter. Taking the
        // sensor's would put a portrait frame inside the held landscape window.
        assertEquals(
            "a locked stream is the window's rotation, never the sensor's newer band",
            Surface.ROTATION_90,
            OrientationLock.streamRotationFor(
                locked = true,
                frozenRotation = Surface.ROTATION_90,
                sensorRotation = Surface.ROTATION_0
            )
        )
        // And the same two values the other way round, so the answer is the FROZEN
        // one and not an accident of which argument is portrait.
        assertEquals(
            Surface.ROTATION_0,
            OrientationLock.streamRotationFor(
                locked = true,
                frozenRotation = Surface.ROTATION_0,
                sensorRotation = Surface.ROTATION_90
            )
        )
    }

    @Test
    fun unlockingIsTheSensorAgainForEveryReadingTheDeviceCanReport() {
        val frozen = Surface.ROTATION_270
        for (degree in 0 until 360) {
            val sensor = DeviceHold.surfaceRotationFor(degree)
            assertEquals(
                "unlocked, degree $degree is the sensor's own band",
                sensor,
                OrientationLock.streamRotationFor(
                    locked = false,
                    frozenRotation = frozen,
                    sensorRotation = sensor
                )
            )
            assertEquals(
                "locked, degree $degree leaves the stream where it was",
                frozen,
                OrientationLock.streamRotationFor(
                    locked = true,
                    frozenRotation = frozen,
                    sensorRotation = sensor
                )
            )
        }
        // The flat-phone reading named, because it is the one that leaves the WINDOW
        // where it was too: flat reads as portrait for the STREAM (right for a
        // capture) while a landscape window stays landscape, so a release applies the
        // portrait reading — which is what today's code does, and why the lock and its
        // release are one behaviour and not two.
        assertEquals(
            Surface.ROTATION_0,
            OrientationLock.streamRotationFor(
                locked = false,
                frozenRotation = Surface.ROTATION_270,
                sensorRotation = DeviceHold.surfaceRotationFor(DeviceHold.ORIENTATION_UNKNOWN)
            )
        )
    }

    /**
     * The ask, literally: the window's bottom-LEFT in portrait and its bottom-RIGHT in
     * landscape. `Gravity.START`/`END` rather than `LEFT`/`RIGHT`, because they are the
     * end of the line the chrome already anchors the framing control to.
     */
    @Test
    fun theControlIsBottomLeftInPortraitAndBottomRightInLandscape() {
        val portrait = OrientationLock.placementFor(
            windowLandscape = false, marginPx = margin, bars = bars()
        )
        val landscape = OrientationLock.placementFor(
            windowLandscape = true, marginPx = margin, bars = bars()
        )
        assertEquals(Gravity.BOTTOM or Gravity.START, portrait.gravity)
        assertEquals(Gravity.BOTTOM or Gravity.END, landscape.gravity)
        assertNotEquals(
            "the two corners must not be the same corner",
            portrait.gravity, landscape.gravity
        )
        // Portrait's corner is the START one, so nothing may be written to the end
        // margin — a leftover marginEnd on a start-anchored square shifts it.
        assertEquals(margin, portrait.startMargin)
        assertEquals(0, portrait.endMargin)
        assertEquals(0, landscape.startMargin)
        assertEquals(margin, landscape.endMargin)
        // Neither is anchored to the top, either way up.
        assertEquals(0, portrait.topMargin)
        assertEquals(0, landscape.topMargin)
    }

    /**
     * The insets, in and out: the corner takes the inset of the edges it lies on — the
     * LEFT in portrait, the right and bottom in landscape (where the navigation bar is
     * along a long edge, the very edge that corner hugs) — and takes nothing from the
     * edges it is not on. This is the one that stops the control sitting under a bar
     * that would swallow the touches, which is a thing the simulators do not show.
     */
    @Test
    fun thePortraitCornerTakesTheLeftInsetAndTheLandscapeOneTheRight() {
        val allBars = bars(left = 11, right = 22, bottom = 33)

        val portrait = OrientationLock.placementFor(
            windowLandscape = false, marginPx = margin, bars = allBars
        )
        assertEquals("portrait folds the LEFT inset in", margin + 11, portrait.startMargin)
        assertEquals("portrait folds the BOTTOM inset in", margin + 33, portrait.bottomMargin)
        assertEquals("portrait takes nothing from the right edge", 0, portrait.endMargin)

        val landscape = OrientationLock.placementFor(
            windowLandscape = true, marginPx = margin, bars = allBars
        )
        assertEquals("landscape folds the RIGHT inset in", margin + 22, landscape.endMargin)
        assertEquals("landscape folds the BOTTOM inset in", margin + 33, landscape.bottomMargin)
        assertEquals("landscape takes nothing from the left edge", 0, landscape.startMargin)

        // With no bars at all the control sits the plain margin off its corner, so the
        // insets are added and never substituted for the margin.
        val bare = OrientationLock.placementFor(
            windowLandscape = false, marginPx = margin, bars = bars()
        )
        assertEquals(margin, bare.startMargin)
        assertEquals(margin, bare.bottomMargin)
    }

    /** The state has to be visible, and the two readouts are the glyph and the words. */
    @Test
    fun theControlSaysWhichStateItIsIn() {
        // The glyph is a DRAWABLE now, not a text glyph: the emoji pair this used to draw
        // could not be tinted the chrome's cyan at all (see [OrientationLock.iconFor]).
        // What is still phone-free to pin is that the two states are two DIFFERENT
        // drawables — the visual distinction the control's whole readout rests on — and
        // that each of them resolves (an id of 0 would mean R had named nothing).
        val lockedGlyph = OrientationLock.iconFor(locked = true)
        val unlockedGlyph = OrientationLock.iconFor(locked = false)
        assertTrue("the shut padlock must name a drawable (got $lockedGlyph)", lockedGlyph != 0)
        assertTrue("the open padlock must name a drawable (got $unlockedGlyph)", unlockedGlyph != 0)
        assertNotEquals(
            "a locked control and a free one must not look identical",
            lockedGlyph,
            unlockedGlyph
        )
        for (locked in listOf(true, false)) {
            val description = OrientationLock.descriptionFor(locked)
            assertTrue(
                "the control must say something to a screen reader (locked=$locked)",
                description.isNotBlank()
            )
        }
        assertNotEquals(
            "the two states must not read the same",
            OrientationLock.descriptionFor(locked = true),
            OrientationLock.descriptionFor(locked = false)
        )
        // The action, not the object: pressing a LOCKED control unlocks it.
        assertEquals("Unlock orientation", OrientationLock.descriptionFor(locked = true))
        assertEquals("Lock orientation", OrientationLock.descriptionFor(locked = false))
    }

    /**
     * Every value this object can hand a log line has a name, so a maintainer reading
     * logcat does not have to decode a bare int, and the two names are not each
     * other's.
     */
    @Test
    fun bothRequestedOrientationValuesHaveTheirOwnName() {
        val held = OrientationLock.orientationName(OrientationLock.requestedOrientationFor(true))
        val free = OrientationLock.orientationName(OrientationLock.requestedOrientationFor(false))
        assertEquals("SCREEN_ORIENTATION_LOCKED", held)
        assertEquals("SCREEN_ORIENTATION_FULL_SENSOR", free)
        assertNotEquals(held, free)
        assertFalse(
            "a value this object never produces must not borrow a name: " +
                OrientationLock.orientationName(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
            OrientationLock.orientationName(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .contains("LOCKED")
        )
    }
}
