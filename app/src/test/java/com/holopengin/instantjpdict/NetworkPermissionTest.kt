package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #71: the PRIVACY POSTURE, held to the declaration.
 *
 * This app used to declare no `INTERNET` permission at all. The in-app
 * dictionary catalog downloads from upstream, so #71 adds it — and this test is
 * the standing guard that it is the only one that changes. A future permission
 * fails here on purpose: adding one has to be a conscious decision with this
 * expectation and the note beside it updated, because every permission is a
 * promise to the user.
 *
 * `CAMERA` (#78) was already there for the viewfinder, so the expected set is
 * exactly the two. What this cannot prove: that the app makes no network call
 * outside the catalog — that is code review, not a config assertion.
 */
class NetworkPermissionTest {

    private val manifest: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        candidates.firstOrNull { it.isFile }?.readText()
            ?: error("AndroidManifest.xml not found; tried ${candidates.joinToString { it.path }}")
    }

    private fun declaredPermissions(): Set<String> =
        Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun internet_is_the_only_permission_the_catalog_added() {
        assertEquals(
            "The permission set changed. #71 adds INTERNET for the dictionary " +
                "catalog and nothing else; CAMERA is #78's viewfinder. Update this " +
                "expectation consciously if a new permission is really wanted.",
            setOf("android.permission.CAMERA", "android.permission.INTERNET"),
            declaredPermissions(),
        )
    }

    @Test
    fun the_internet_declaration_says_why_it_exists_and_when_it_is_used() {
        val at = manifest.indexOf("android.permission.INTERNET")
        assertTrue("the manifest does not declare INTERNET", at > 0)
        // The comment immediately above the declaration (the last <!-- before it).
        val comment = manifest.substring(0, at).substringAfterLast("<!--")
        assertTrue(
            "the INTERNET permission must carry a comment explaining the privacy " +
                "cost and that the app is offline until the user imports: $comment",
            comment.contains("offline", ignoreCase = true) && comment.contains("catalog", ignoreCase = true),
        )
    }
}
