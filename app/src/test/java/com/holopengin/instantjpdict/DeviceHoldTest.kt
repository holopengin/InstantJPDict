package com.holopengin.instantjpdict

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #78: the hold vocabulary shared by the viewfinder and the OCR view it hands
 * off to. [DeviceHold] is plain ints, so the bands and the portrait/landscape
 * rule are pinned here rather than by holding a phone the right way up.
 *
 * The load-bearing test is [everyDegreeTheCameraCallsLandscapeIsTheOnlyOneTheOcrViewInherits]:
 * the OCR view opens in the family this rule names, so this is the statement
 * "the OCR view cannot disagree with what the viewfinder showed".
 */
class DeviceHoldTest {

    @Test
    fun theBandsAreCameraXsOwn() {
        assertEquals(Surface.ROTATION_270, DeviceHold.surfaceRotationFor(45))
        assertEquals(Surface.ROTATION_270, DeviceHold.surfaceRotationFor(90))
        assertEquals(Surface.ROTATION_270, DeviceHold.surfaceRotationFor(134))

        assertEquals(Surface.ROTATION_180, DeviceHold.surfaceRotationFor(135))
        assertEquals(Surface.ROTATION_180, DeviceHold.surfaceRotationFor(180))
        assertEquals(Surface.ROTATION_180, DeviceHold.surfaceRotationFor(224))

        assertEquals(Surface.ROTATION_90, DeviceHold.surfaceRotationFor(225))
        assertEquals(Surface.ROTATION_90, DeviceHold.surfaceRotationFor(270))
        assertEquals(Surface.ROTATION_90, DeviceHold.surfaceRotationFor(314))
    }

    @Test
    fun flatAndUnknownReadAsPortraitNotLandscape() {
        // ORIENTATION_UNKNOWN, what the sensor reports while the phone is flat or
        // is being moved — the one reading that must not be taken for a hold.
        assertEquals(Surface.ROTATION_0, DeviceHold.surfaceRotationFor(-1))
        assertEquals(Surface.ROTATION_0, DeviceHold.surfaceRotationFor(0))
        assertEquals(Surface.ROTATION_0, DeviceHold.surfaceRotationFor(44))
        assertEquals(Surface.ROTATION_0, DeviceHold.surfaceRotationFor(315))
        assertEquals(Surface.ROTATION_0, DeviceHold.surfaceRotationFor(359))
    }

    @Test
    fun upsideDownIsAPortraitHold() {
        // ROTATION_180 is 180 degrees from upright: the window turns with the
        // phone, so its bottom edge is still the one under the thumb.
        assertFalse(DeviceHold.isLandscapeHold(Surface.ROTATION_180))
    }

    @Test
    fun landscapeIsTheTwoQuarterTurns() {
        assertTrue(DeviceHold.isLandscapeHold(Surface.ROTATION_90))
        assertTrue(DeviceHold.isLandscapeHold(Surface.ROTATION_270))
        assertFalse(DeviceHold.isLandscapeHold(Surface.ROTATION_0))
    }

    /**
     * The set of device orientations the camera calls a landscape hold, taken the
     * long way — through the rotation, exactly as the viewfinder and the OCR view
     * read it — is the two 90-degree bands around the landscape cardinals, and
     * nothing else. If this ever changes, the viewfinder's anchors and the OCR
     * view's inherited orientation change together, because both ask this.
     */
    @Test
    fun everyDegreeTheCameraCallsLandscapeIsTheOnlyOneTheOcrViewInherits() {
        val landscapeDegrees = (0 until 360).filter {
            DeviceHold.isLandscapeHold(DeviceHold.surfaceRotationFor(it))
        }
        assertEquals((45..134).toList() + (225..314).toList(), landscapeDegrees)
    }
}
