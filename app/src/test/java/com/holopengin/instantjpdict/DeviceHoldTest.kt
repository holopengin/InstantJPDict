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
 * decided from. [DeviceHold] is plain ints, so the bands, the rule that turns the
 * capture stream, and both portrait/landscape rules are pinned here rather than by
 * holding a phone the right way up.
 *
 * The load-bearing tests are:
 *  - [theWindowAndTheSensorAreTwoAnswersAndMayDisagree] sweeps every degree the
 *    sensor can report against a landscape window and names exactly where the two
 *    disagree;
 *  - [aFlatPhoneLeavesTheAnchorsWhereTheyAre] states the maintainer's own case
 *    ("if you tilt the phone down while in landscape the buttons move despite the
 *    rotate never triggering") as the property that must hold: the window decides
 *    the anchors, so the sensor's unknown reading moves nothing;
 *  - [aFlatPhoneDoesNotMoveTheStream] and [everyReadingThatIsAHoldStillTurnsTheStream]
 *    are the stream's half of the same case: a reading that is not a hold does not
 *    turn the capture either, while every reading that is one still does.
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
        // is being moved — the one reading that must not be taken for a hold. As a
        // BAND it is portrait (CameraX's band for a value it has no band for), and
        // that is exactly why the raw band may not be handed to the stream: see
        // [aFlatPhoneDoesNotMoveTheStream] for the stream's rule and
        // [aFlatPhoneLeavesTheAnchorsWhereTheyAre] for the anchors'.
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

    /**
     * THE STREAM'S half of the flat-phone report, and the half that was still
     * live: tipped toward flat (the sensor reports ORIENTATION_UNKNOWN), the
     * stream must NOT be sent to portrait. A landscape window does not turn
     * because the phone was laid on a table, so a stream sent to portrait there
     * is a quarter turn out from its own window — the turned-preview-inside-an-
     * upright-window failure the `fullSensor` decision exists to avoid — and the
     * FILL_CENTER crop, which is a centred aspect-crop of the upright frame, stops
     * being a crop of what is on screen.
     *
     * Swept over all four rotations the stream can already be carrying, so this
     * cannot pass by the unknown reading happening to agree with the value the
     * test started from.
     */
    @Test
    fun aFlatPhoneDoesNotMoveTheStream() {
        val carried = listOf(
            Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270
        )
        for (current in carried) {
            assertEquals(
                "the sensor has no hold to report, so the stream keeps the one it has ($current)",
                current,
                DeviceHold.streamRotationFor(DeviceHold.ORIENTATION_UNKNOWN, current)
            )
        }
    }

    /**
     * The other half of the same rule, so the fix cannot be "the stream stops
     * following the hand": every reading that IS a hold still turns it. Swept over
     * every degree the sensor can report, from a stream carrying ROTATION_90, the
     * only degrees it keeps ROTATION_90 for are that rotation's OWN band — i.e. the
     * sensor still drives the capture, and the flat-phone rule is exactly the one
     * no-reading case.
     */
    @Test
    fun everyReadingThatIsAHoldStillTurnsTheStream() {
        val kept = (0 until 360).filter { degree ->
            DeviceHold.streamRotationFor(degree, Surface.ROTATION_90) == Surface.ROTATION_90
        }
        assertEquals((225..314).toList(), kept)
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
            "the flat phone's band is portrait — which is why the stream must not take it " +
                "as a hold, and keeps the rotation it already has",
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
