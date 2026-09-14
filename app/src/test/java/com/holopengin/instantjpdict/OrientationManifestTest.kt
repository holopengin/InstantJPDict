package com.holopengin.instantjpdict

import android.content.pm.ActivityInfo
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #78 follow-up: the manifest half of "the OCR view inherits the camera's
 * orientation and the system-share entry point does not change".
 *
 * These assertions are about SHIPPED configuration, which is why they read the
 * committed manifest rather than a fixture: they are the standing guard on the
 * decision made for this change — the orientation fix is carried on the camera's
 * Intent and applied in [ShareImageActivity.onCreate], and deliberately NOT
 * declared in the manifest.
 *
 * Why that matters: `.ShareImageActivity` is the app's EXPORTED `ACTION_SEND`
 * target. A `android:screenOrientation` on it would pin the system-share entry
 * point too — the one path the ask excludes — and a `configChanges` on it would
 * stop the quarter-turn restart that recomposes the image at the new container
 * size, which is what keeps the overlay's box coordinates 1:1 with the surface.
 * So the manifest entry must stay exactly as it is, and this test fails loudly if
 * someone gives it an orientation later.
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

    @Test
    fun theShareEntryPointDeclaresNoOrientationOfItsOwn() {
        val tag = activityTag(".ShareImageActivity")
        assertFalse(
            "the exported share entry must not pin an orientation — the camera's " +
                "handoff is what decides, see InheritedOrientation: $tag",
            tag.contains("screenOrientation")
        )
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
    }

    @Test
    fun theViewfinderIsTheSensorFollowingWindowTheHoldComesFrom() {
        // fullSensor is the value the platform documents to use the sensor even
        // with a locked auto-rotate: the reason the viewfinder can be landscape
        // while a manifest-silent activity opens portrait, and the reason the OCR
        // view is TOLD the hold instead of inferring one.
        val tag = activityTag(".ProtoCameraActivity")
        assertTrue("the viewfinder must still follow the sensor: $tag",
            tag.contains("android:screenOrientation=\"fullSensor\""))
        assertTrue("the viewfinder must still survive the quarter turn: $tag",
            tag.contains("android:configChanges") && tag.contains("orientation"))
    }

    /**
     * The end of the chain: what the OCR view asks for on a camera launch IS what
     * the viewfinder declares for itself, read out of this same manifest. That is
     * what makes the OCR view's orientation the camera's own notion of it rather
     * than a lookalike — the two entries name one platform value, and this test
     * fails if either side is edited without the other.
     */
    @Test
    fun theInheritedRequestIsTheValueTheViewfinderDeclares() {
        assertTrue(
            "the viewfinder no longer declares fullSensor, so the inherited " +
                "request is no longer the same notion of orientation",
            activityTag(".ProtoCameraActivity").contains(
                "android:screenOrientation=\"fullSensor\""
            )
        )
        for (hold in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_270)) {
            assertEquals(
                "hold=$hold",
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                InheritedOrientation.requestedOrientationFor(hold)
            )
        }
    }
}
