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
 * orientation and the system-share entry point does not change".
 *
 * These assertions are about SHIPPED configuration, which is why they read the
 * committed manifest rather than a fixture: they are the standing guard on the
 * decision made for this change — the orientation is DECLARED on
 * `.ShareImageActivity` as `android:screenOrientation="fullSensor"`, the same
 * value the viewfinder declares for itself, so the OCR view is the right way up
 * on its FIRST layout and no post-creation quarter turn follows.
 *
 * Why that is right, and what must NOT change with it:
 *  - `fullSensor` is not a pin. It asks the platform to resolve the window from
 *    the sensor, exactly like `.ProtoCameraActivity`, so the EXPORTED
 *    `ACTION_SEND` entry point is not frozen into a family — which is why this
 *    test fails if a pinned value (`portrait`, `landscape`, `sensorPortrait`, …)
 *    ever replaces it.
 *  - `configChanges` must stay absent: the quarter-turn re-creation is what
 *    recomposes the image at the new container size, which is what keeps the
 *    overlay's box coordinates 1:1 with the surface. Declaring an orientation
 *    must not smuggle a `configChanges` in.
 *  - The runtime request this replaced (ShareImageActivity asking the window
 *    manager for FULL_SENSOR in onCreate, from the camera's hold extra) is gone:
 *    it resolved AFTER the first layout, which is the "starts portrait, then it
 *    rotates" the maintainer saw. The extra survives only as the marker the
 *    handoff log reads — see [InheritedOrientation].
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
        // Declaring an orientation must not smuggle in a `configChanges`: the
        // quarter-turn re-creation is what recomposes the image at the new
        // container size.
        assertFalse(
            "the share entry must keep being re-created on a quarter turn, so the " +
                "composite is recomposed at the new container size: $tag",
            tag.contains("configChanges")
        )
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
