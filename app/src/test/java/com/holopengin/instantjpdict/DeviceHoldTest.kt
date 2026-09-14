package com.holopengin.instantjpdict

import android.content.res.Configuration
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #78: the hold vocabulary shared by the viewfinder and the OCR view it hands off
 * to, and the SEPARATE window vocabulary the viewfinder's control anchors are
 * decided from. [DeviceHold] is plain ints, so the bands and both
 * portrait/landscape rules are pinned here rather than by holding a phone the
 * right way up.
 *
 * The two load-bearing tests are the pair at the bottom:
 *  - [theWindowAndTheSensorAreTwoAnswersAndMayDisagree] sweeps every degree the
 *    sensor can report against a landscape window and names exactly where the two
 *    disagree;
 *  - [aFlatPhoneLeavesTheAnchorsWhereTheyAre] states the maintainer's own case
 *    ("if you tilt the phone down while in landscape the buttons move despite the
 *    rotate never triggering") as the property that must hold: the window decides
 *    the anchors, so the sensor's unknown reading moves nothing.
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
        // is being moved — the one reading that must not be taken for a hold. It is
        // right for the STREAM (a capture is oriented for the hand) and is exactly
        // why the anchors may not be decided from this value: see
        // [aFlatPhoneLeavesTheAnchorsWhereTheyAre].
        assertEquals(Surface.ROTATION_0, DeviceHold.surfaceRotationFor(DeviceHold.ORIENTATION_UNKNOWN))
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
     * The set of device orientations the camera calls a landscape HOLD, taken the
     * long way — through the rotation it gives the capture — is the two 90-degree
     * bands around the landscape cardinals, and nothing else. This is the stream's
     * notion of "which way up the phone is", and the handoff log names the hold
     * through it; the anchors no longer read it (they read [Configuration]).
     */
    @Test
    fun theLandscapeHoldIsTheTwoNinetyDegreeBandsAndNothingElse() {
        val landscapeDegrees = (0 until 360).filter {
            DeviceHold.isLandscapeHold(DeviceHold.surfaceRotationFor(it))
        }
        assertEquals((45..134).toList() + (225..314).toList(), landscapeDegrees)
    }

    @Test
    fun landscapeIsTheWindowsOwnFamily() {
        assertTrue(DeviceHold.isLandscapeWindow(Configuration.ORIENTATION_LANDSCAPE))
        assertFalse(DeviceHold.isLandscapeWindow(Configuration.ORIENTATION_PORTRAIT))
        assertFalse(
            "an unknown window must not read as landscape by accident",
            DeviceHold.isLandscapeWindow(Configuration.ORIENTATION_UNDEFINED)
        )
    }

    /**
     * The two notions are two answers, and they MAY disagree — that is the point of
     * splitting them (they used to be one value, and the buttons moved for a sensor
     * reading the window never had). Swept over every degree the sensor can report,
     * in a landscape window, the sensor and the window disagree over exactly the
     * portrait bands: the sensor says portrait there, the window says landscape,
     * and the anchors follow the window.
     */
    @Test
    fun theWindowAndTheSensorAreTwoAnswersAndMayDisagree() {
        val landscapeWindow = DeviceHold.isLandscapeWindow(Configuration.ORIENTATION_LANDSCAPE)
        val disagreeing = (0 until 360).filter { degree ->
            DeviceHold.isLandscapeHold(DeviceHold.surfaceRotationFor(degree)) != landscapeWindow
        }
        assertTrue("a landscape window holds the anchors while the sensor reads portrait", landscapeWindow)
        assertEquals(
            (0..44).toList() + (135..224).toList() + (315..359).toList(),
            disagreeing
        )
    }

    /**
     * The maintainer's flat-phone case: "if you tilt the phone down while in
     * landscape the buttons move despite the rotate never triggering". Tipped flat,
     * the sensor reports its unknown reading, which is portrait — the STREAM takes
     * that (correctly: the capture is oriented for the hand) — while the WINDOW is
     * still the landscape one it was laid out in. The anchors ask the window, so
     * nothing moves until the window does.
     *
     * The mirror case is just as load-bearing: flat in a PORTRAIT window, the
     * sensor's portrait reading is right and the anchors must not move either — the
     * test would pass for the wrong reason if the window answer were simply the
     * negation of the sensor's.
     */
    @Test
    fun aFlatPhoneLeavesTheAnchorsWhereTheyAre() {
        val flatSensorRotation = DeviceHold.surfaceRotationFor(DeviceHold.ORIENTATION_UNKNOWN)
        assertFalse(
            "the stream reads a flat phone as portrait, and that is right for a capture",
            DeviceHold.isLandscapeHold(flatSensorRotation)
        )
        assertTrue(
            "the window is still landscape, so the anchors stay on the right edge",
            DeviceHold.isLandscapeWindow(Configuration.ORIENTATION_LANDSCAPE)
        )
        assertFalse(
            "flat in a portrait window, they stay on the bottom edge",
            DeviceHold.isLandscapeWindow(Configuration.ORIENTATION_PORTRAIT)
        )
    }
}
