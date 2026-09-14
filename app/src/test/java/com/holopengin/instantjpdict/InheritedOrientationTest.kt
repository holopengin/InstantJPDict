package com.holopengin.instantjpdict

import android.content.pm.ActivityInfo
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #78 follow-up: the handoff rule the viewfinder and the OCR view share. The
 * activity is view construction and cannot be JVM-tested; this decision is a pure
 * function of the handoff extra, so it is pinned here.
 *
 * It is no longer the value [ShareImageActivity] ASKS for — that activity now
 * declares `android:screenOrientation="fullSensor"` in its manifest entry, so the
 * orientation is part of the window it is created with and no runtime request is
 * made at all (see [OrientationManifestTest]). What is pinned here is the value
 * the handoff log names as what the manifest declares, and the two properties
 * that must hold of it:
 *  - [aShareSheetLaunchAsksForNothingAtAll] — the system-share entry point hands
 *    over no hold, so it names no orientation and logs no camera handoff line;
 *  - [theCameraPathNeverAsksForAFixedOrientation] — the value is sensor-based, so
 *    the view keeps following the phone after it opens, and it does not depend on
 *    the hold: the phone can move between the shutter and this activity's first
 *    frame, and the sensor is the only thing that knows which way up it is by
 *    then.
 *
 * [OrientationManifestTest] takes the last step, which needs the manifest: the
 * value named here is the one the viewfinder declares for itself.
 */
class InheritedOrientationTest {

    private val cameraPathHolds = listOf(
        Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270
    )

    @Test
    fun aShareSheetLaunchAsksForNothingAtAll() {
        assertNull(InheritedOrientation.requestedOrientationFor(InheritedOrientation.NO_HOLD))
    }

    @Test
    fun aCameraLaunchOpensOnTheSensor() {
        // fullSensor: the OCR view comes up the way up the camera's own window
        // was, and keeps following the device afterwards instead of being left
        // in the family the camera happened to be in.
        for (hold in cameraPathHolds) {
            assertEquals(
                "hold=$hold",
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                InheritedOrientation.requestedOrientationFor(hold)
            )
        }
    }

    @Test
    fun aLandscapeHoldGetsTheSameAnswerAsAPortraitOne() {
        // Which way up the camera was is the camera's business: it is passed along
        // for the record (the log the in-hand check reads) and does NOT decide the
        // value, which is the same sensor-based one for every hold — so there is no
        // stale hold for a re-creation to act on: a quarter turn re-creates this
        // activity and `onCreate` runs again with the camera's OLD hold in the same
        // Intent, naming the same declaration it named the first time.
        assertEquals(
            InheritedOrientation.requestedOrientationFor(Surface.ROTATION_0),
            InheritedOrientation.requestedOrientationFor(Surface.ROTATION_90)
        )
        assertEquals(
            InheritedOrientation.requestedOrientationFor(Surface.ROTATION_90),
            InheritedOrientation.requestedOrientationFor(Surface.ROTATION_270)
        )
    }

    /**
     * The camera path follows the device after it opens: the answer is either
     * "ask for nothing" (only the share path) or a SENSOR-based request — never a
     * fixed [ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE] /
     * [ActivityInfo.SCREEN_ORIENTATION_PORTRAIT]. A fixed one would be a property
     * of the activity for as long as it lived, and would leave the OCR view
     * refusing to come upright the moment the phone was turned back.
     */
    @Test
    fun theCameraPathNeverAsksForAFixedOrientation() {
        assertNull(InheritedOrientation.requestedOrientationFor(InheritedOrientation.NO_HOLD))
        for (hold in cameraPathHolds) {
            val requested = InheritedOrientation.requestedOrientationFor(hold)
            assertTrue(
                "hold=$hold -> $requested",
                requested == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            )
            assertTrue(
                "hold=$hold pinned a family",
                requested != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE &&
                    requested != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT &&
                    requested != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            )
        }
    }

    /**
     * The hold is carried as an Intent extra and doubles as the marker of which
     * entry point this is, so its name is part of the contract between the camera
     * and the OCR view — namespaced, so no other sender can collide with it, and
     * its "no hold" sentinel is outside the `Surface` rotation range so a real
     * hold (ROTATION_0 is 0) can never be mistaken for it.
     */
    @Test
    fun theHandoffExtraIsNamespacedAndOutsideTheRotationRange() {
        assertEquals(
            "com.holopengin.instantjpdict.extra.CAMERA_HOLD",
            InheritedOrientation.EXTRA_CAMERA_HOLD
        )
        assertTrue(InheritedOrientation.NO_HOLD < 0)
        for (hold in cameraPathHolds) {
            assertTrue("$hold is a real hold", hold != InheritedOrientation.NO_HOLD)
        }
    }
}
