package com.holopengin.instantjpdict

import android.content.pm.ActivityInfo
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #78 follow-up: the manifest half of "the OCR view inherits the camera's
 * orientation, the system-share entry point does not change, and a quarter turn
 * does not re-run the OCR".
 *
 * These assertions are about SHIPPED configuration, which is why they read the
 * committed manifest rather than a fixture: they are the standing guard on the two
 * decisions made for this change —
 *  - the orientation is DECLARED on `.ShareImageActivity` as
 *    `android:screenOrientation="fullSensor"`, the same value the viewfinder
 *    declares for itself, so the OCR view is the right way up on its FIRST layout
 *    and no post-creation quarter turn follows;
 *  - `configChanges` IS declared there, with `orientation` and `screenSize` among
 *    its flags, so a LATER quarter turn is handled in place by
 *    `ShareImageActivity.onConfigurationChanged` and the OCR run is kept instead of
 *    being rebuilt and re-run on an image that did not change.
 *
 * Why that is right, and what must NOT change with it:
 *  - `fullSensor` is not a pin. It asks the platform to resolve the window from
 *    the sensor, exactly like `.ProtoCameraActivity`, so the EXPORTED
 *    `ACTION_SEND` entry point is not frozen into a family — which is why this
 *    test fails if a pinned value (`portrait`, `landscape`, `sensorPortrait`, …)
 *    ever replaces it.
 *  - `configChanges` must be PRESENT and must cover `orientation` alongside
 *    `screenSize`: a rotation reports both, and a missing `screenSize` is the trap
 *    that looks right and still re-creates the activity on every turn. The
 *    re-fit that replaces the re-creation — and which keeps the boxes on their
 *    glyphs at the new container size — lives in
 *    `ShareImageActivity.refitComposite`/`OcrOverlayView.refitContent` and is
 *    pinned by `ImageShareRefitTest`.
 *  - The runtime request the orientation decision replaced (ShareImageActivity
 *    asking the window manager for FULL_SENSOR in onCreate, from the camera's hold
 *    extra) is gone: it resolved AFTER the first layout, which is the "starts
 *    portrait, then it rotates" the maintainer saw. The extra survives only as the
 *    marker the handoff log reads — see [InheritedOrientation].
 */
class OrientationManifestTest {

    private val manifest: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found; tried ${candidates.joinToString { it.path }}")
        file.readText()
    }

    /** The `<activity>` tag that declares [name] — attributes and all. */
    private fun activityTag(name: String): String {
        val at = manifest.indexOf("android:name=\"$name\"")
        assertTrue("no activity for $name in the manifest", at > 0)
        val open = manifest.lastIndexOf("<activity", at)
        val close = manifest.indexOf('>', at)
        return manifest.substring(open, close + 1)
    }

    /** The text `android:screenOrientation` is set to in [tag], or null. */
    private fun orientationValue(tag: String): String? =
        Regex("android:screenOrientation=\"([^\"]*)\"")
            .find(tag)?.groupValues?.get(1)

    /** The text `android:configChanges` is set to in [tag], or null. */
    private fun configChangesValue(tag: String): String? =
        Regex("android:configChanges=\"([^\"]*)\"")
            .find(tag)?.groupValues?.get(1)

    @Test
    fun theShareEntryPointDeclaresTheSensorFollowingOrientationOnItsFirstLayout() {
        val tag = activityTag(".ShareImageActivity")
        assertEquals(
            "the exported share entry must declare fullSensor so the camera's hold " +
                "is right on the FIRST layout — the runtime request it replaced " +
                "resolved after it. See InheritedOrientation: $tag",
            "fullSensor",
            orientationValue(tag)
        )
        // fullSensor resolves from the sensor; a pinned family would freeze the
        // exported ACTION_SEND entry point, which is the one path the ask excludes.
        for (pin in listOf("portrait", "landscape", "user", "sensorPortrait", "sensorLandscape")) {
            assertFalse(
                "the share entry must not pin $pin — fullSensor follows the device: $tag",
                tag.contains("android:screenOrientation=\"$pin\"")
            )
        }
    }

    /**
     * The other half of the second fix: a LATER quarter turn must be handled in
     * place, so the OCR run is not rebuilt and re-run on an image that did not
     * change. That means `configChanges` IS declared on the share entry, and it
     * covers `orientation` AND `screenSize` — a rotation reports both, and a
     * declaration missing `screenSize` still re-creates the activity, which is the
     * trap this test exists for. The viewfinder's entry must keep its own.
     */
    @Test
    fun theQuarterTurnIsHandledInPlaceRatherThanByReCreation() {
        val tag = activityTag(".ShareImageActivity")
        val declared = configChangesValue(tag)
            ?: error(
                "the share entry must declare configChanges or a quarter turn " +
                    "re-creates it and the OCR runs again: $tag"
            )
        val flags = declared.split('|').map { it.trim() }
        for (required in listOf("orientation", "screenSize", "screenLayout", "smallestScreenSize", "keyboardHidden")) {
            assertTrue(
                "the share entry must declare $required (declared: $declared): $tag",
                flags.contains(required)
            )
        }
        // The re-fit that replaces the re-creation is only reachable if the activity
        // really is the one that survives: a pinned orientation would still recreate.
        assertEquals("fullSensor", orientationValue(tag))
    }

    @Test
    fun theShareEntryPointIsStillTheExportedSendTarget() {
        // The other entry point into this activity, unchanged by this work: the
        // system share sheet still resolves it for a single image.
        val tag = activityTag(".ShareImageActivity")
        assertTrue(tag.contains("android:exported=\"true\""))
        val filter = manifest.substringAfter(tag)
        assertTrue(filter.contains("android.intent.action.SEND"))
        assertTrue(filter.contains("image/*"))
        assertTrue(
            "the SEND filter must still resolve image/* — the data element carries it: " +
                filter.substringBefore("</intent-filter>"),
            filter.contains("android:mimeType=\"image/*\"")
        )
    }

    @Test
    fun theViewfinderIsTheSensorFollowingWindowTheHoldComesFrom() {
        // fullSensor is the value the platform documents to use the sensor even
        // with a locked auto-rotate: the reason the viewfinder can be landscape
        // while a manifest-silent activity opens portrait, and the reason the OCR
        // view declares the same value rather than inferring a family.
        val tag = activityTag(".ProtoCameraActivity")
        assertTrue("the viewfinder must still follow the sensor: $tag",
            tag.contains("android:screenOrientation=\"fullSensor\""))
        assertTrue("the viewfinder must still survive the quarter turn: $tag",
            tag.contains("configChanges") && tag.contains("orientation"))
    }

    /**
     * The lock's RELEASE end, held to the declaration rather than to a second policy:
     * [OrientationLock]'s free value IS the value this activity's manifest declares for
     * itself, so un-freezing the viewfinder cannot drift into an orientation invented in
     * code. The held value is the platform's "hold the rotation already in effect" — see
     * [OrientationLock] — and the assertion that the two differ lives in
     * `OrientationLockTest`.
     */
    @Test
    fun theOrientationLockReleasesBackToTheValueTheViewfinderDeclares() {
        val declared = orientationValue(activityTag(".ProtoCameraActivity"))
        assertEquals(
            "the viewfinder's declaration is the lock's release value; " +
                "this test fails if either side is edited without the other: $declared",
            "fullSensor",
            declared
        )
        assertEquals(
            "and the constant that stands for that declaration is the one the lock hands back",
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            OrientationLock.requestedOrientationFor(locked = false)
        )
    }

    /**
     * The end of the chain: the OCR view declares the SAME platform value the
     * viewfinder declares for itself, read out of this same manifest. That is what
     * makes the OCR view's orientation the camera's own notion of it rather than a
     * lookalike — the two entries name one platform value, and this test fails if
     * either side is edited without the other. The value the handoff log names
     * ([InheritedOrientation.requestedOrientationFor]) is that same platform
     * constant, and it is null when there was no camera handoff at all.
     */
    @Test
    fun theOcrViewDeclaresTheValueTheViewfinderDeclares() {
        assertEquals(
            "the two entries must name one platform value: " +
                "${activityTag(".ProtoCameraActivity")} vs ${activityTag(".ShareImageActivity")}",
            orientationValue(activityTag(".ProtoCameraActivity")),
            orientationValue(activityTag(".ShareImageActivity"))
        )
        for (hold in listOf(
            Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270
        )) {
            assertEquals(
                "hold=$hold",
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                InheritedOrientation.requestedOrientationFor(hold)
            )
        }
        assertNull(
            "the system-share entry point hands over no hold, so it logs no handoff " +
                "line and no orientation is named for it",
            InheritedOrientation.requestedOrientationFor(InheritedOrientation.NO_HOLD)
        )
    }
}
