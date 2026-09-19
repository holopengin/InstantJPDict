package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #82: the SHIPPED half of "camera mode is on the app's shortcut list" — the
 * static shortcut declaration and the manifest wiring a launcher reads.
 *
 * These assertions are about committed configuration, which is why they read the
 * files rather than a fixture (the `OrientationManifestTest` precedent): they are
 * the standing guard on the three decisions made for this change —
 *  - the shortcut targets `.MainActivity`, the ALREADY EXported launcher entry,
 *    with the namespaced action [CameraShortcut.ACTION_OPEN_CAMERA]; nothing new
 *    gets an `android:exported` attribute, and the viewfinder's entry stays
 *    exactly as unexported as it is today. A shortcut is started by the launcher
 *    (another app), so an unexported target would be unreachable — but the
 *    launcher entry is reachable already, and routing through it also leaves the
 *    dictionary screen below the camera, so Back out of a cold launch lands on
 *    the app's own screen instead of an empty task;
 *  - the launcher activity publishes `@xml/shortcuts` through the
 *    `android.app.shortcuts` meta-data — without it the resource is dead and the
 *    shortcut never appears, which is a failure no device-free test would
 *    otherwise catch;
 *  - the labels are STRING RESOURCES, as the platform requires (`android:shortcutShortLabel`
 *    may not be a literal), and the id is a literal, as the platform requires;
 *  - the shortcut names its own repo-authored mark (#87) rather than falling back
 *    to the badged app icon, and the drawable it names exists.
 *
 * What this test cannot prove: that a launcher shows the shortcut, that Quick Tap
 * offers it, or that Back really pops the viewfinder to the dictionary screen.
 * Those need a device; what is pinned here is the declaration those behaviours
 * are read from.
 */
class CameraShortcutResourceTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun sourceFile(rel: String): File =
        listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("$rel not found; working dir ${File(".").absolutePath}")

    private fun parse(rel: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(sourceFile(rel))

    private fun Element.attr(name: String): String? =
        if (hasAttributeNS(android, name)) getAttributeNS(android, name) else null

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }
            .filter { it.tagName == tag }

    /** The `<activity android:name="…">` element for [name], or a loud failure. */
    private fun activity(document: Document, name: String): Element =
        (0 until document.getElementsByTagName("activity").length)
            .map { document.getElementsByTagName("activity").item(it) as Element }
            .firstOrNull { it.attr("name") == name }
            ?: error("no <activity android:name=\"$name\"> in the manifest")

    /** The one `<shortcut>` in `res/xml/shortcuts.xml`. */
    private fun shortcut(document: Document): Element {
        val shortcuts = document.getElementsByTagName("shortcut")
        assertEquals("expected exactly one static shortcut", 1, shortcuts.length)
        return shortcuts.item(0) as Element
    }

    /** The shortcut's `<intent>` element. */
    private fun intent(shortcut: Element): Element =
        shortcut.children("intent").singleOrNull()
            ?: error("the shortcut declares no single <intent>: $shortcut")

    /** The value of `@string/<name>` out of the committed strings.xml, or a loud failure. */
    private fun stringResource(reference: String): String {
        val name = reference.removePrefix("@string/")
        assertTrue("not a string resource reference: $reference", name != reference && name.isNotEmpty())
        val strings = parse("src/main/res/values/strings.xml")
        val nodes = strings.getElementsByTagName("string")
        // Unlike the manifest and shortcuts resources, strings.xml has no android:
        // namespace — its attributes are plain.
        val entry = (0 until nodes.length).map { nodes.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == name }
            ?: error("no <string name=\"$name\"> in strings.xml for $reference")
        val text = entry.textContent?.trim().orEmpty()
        assertTrue("<string name=\"$name\"> is empty", text.isNotEmpty())
        return text
    }

    @Test
    fun theLauncherActivityPublishesTheShortcutsResource() {
        val tag = activity(parse("src/main/AndroidManifest.xml"), ".MainActivity")
        val meta = tag.children("meta-data")
            .firstOrNull { it.attr("name") == "android.app.shortcuts" }
        assertNotNull("the launcher activity has no android.app.shortcuts meta-data: $tag", meta)
        assertEquals(
            "the meta-data must point at the resource the shortcut is declared in",
            "@xml/shortcuts",
            meta!!.attr("resource")
        )
    }

    @Test
    fun theShortcutOpensTheCameraThroughTheExportedLauncherEntryPoint() {
        val shortcut = shortcut(parse("src/main/res/xml/shortcuts.xml"))
        val intent = intent(shortcut)
        // The action is the runtime rule's constant, so declaration and rule cannot
        // drift: rename one side and this fails.
        assertEquals(
            "the shortcut must carry the action CameraShortcut.opensCamera answers to",
            CameraShortcut.ACTION_OPEN_CAMERA,
            intent.attr("action")
        )
        val targetPackage = intent.attr("targetPackage")
        val targetClass = intent.attr("targetClass")
        assertEquals("com.holopengin.instantjpdict", targetPackage)
        assertEquals("com.holopengin.instantjpdict.MainActivity", targetClass)

        // …and that target really is the exported MAIN/LAUNCHER entry the launcher
        // starts on a normal tap: the shortcut must ride on it, not on a second
        // exported surface.
        val launcher = activity(parse("src/main/AndroidManifest.xml"), ".MainActivity")
        assertEquals("true", launcher.attr("exported"))
        val filter = launcher.children("intent-filter").first { filter ->
            filter.children("action").any { it.attr("name") == "android.intent.action.MAIN" }
        }
        assertTrue(
            "the shortcut target is not the launcher entry: $launcher",
            filter.children("category").any { it.attr("name") == "android.intent.category.LAUNCHER" }
        )
    }

    @Test
    fun nothingNewIsExportedForTheShortcut() {
        // The acceptance criterion, as a standing guard: the shortcut must not
        // widen the exported surface. The set below is what the manifest exported
        // BEFORE #82 — the launcher entry the shortcut rides on, and the share
        // sheet's ACTION_SEND entry. A future export therefore fails this test on
        // purpose: the change has to be conscious, and this expectation (and the
        // note beside it) updated with it.
        val manifest = parse("src/main/AndroidManifest.xml")
        val exported = listOf("activity", "service", "receiver", "provider")
            .flatMap { tag ->
                val nodes = manifest.getElementsByTagName(tag)
                (0 until nodes.length).map { nodes.item(it) as Element }
            }
            .filter { it.attr("exported") == "true" }
            .map { it.attr("name") }
            .toSet()
        assertEquals(
            "a component became exported; #82 must add none — the shortcut targets " +
                "the launcher entry that was already exported",
            setOf(".MainActivity", ".ShareImageActivity"),
            exported
        )
        assertEquals(
            "the shortcut must not need the viewfinder exported — it reaches the " +
                "camera through MainActivity",
            "false",
            activity(manifest, ".ProtoCameraActivity").attr("exported")
        )
    }

    @Test
    fun theShortcutCarriesItsOwnCameraMark() {
        // #87: without android:icon the launcher badges the app icon, so the
        // shortcut reads as "the app, again" beside the ordinary entry point.
        val shortcut = shortcut(parse("src/main/res/xml/shortcuts.xml"))
        assertEquals(
            "the camera shortcut must name its own mark",
            "@drawable/ic_shortcut_camera",
            shortcut.attr("icon")
        )
        // A missing resource fails the build, but a renamed one would leave this
        // reference stale — the file check keeps the two in step.
        assertTrue(
            "the drawable the shortcut names does not exist",
            sourceFile("src/main/res/drawable/ic_shortcut_camera.xml").isFile
        )
    }

    @Test
    fun theShortcutIsEnabledAndItsLabelsAreResourceStrings() {
        val shortcut = shortcut(parse("src/main/res/xml/shortcuts.xml"))
        // The id is a literal by platform rule, and it is the name a launcher
        // reports the shortcut under, so it must not follow a resource change.
        assertEquals("camera", shortcut.attr("shortcutId"))
        assertEquals("true", shortcut.attr("enabled"))
        val short = shortcut.attr("shortcutShortLabel")
        val long = shortcut.attr("shortcutLongLabel")
        assertNotNull("the shortcut has no short label: $shortcut", short)
        assertNotNull("the shortcut has no long label: $shortcut", long)
        // The short label is what the launcher's list shows, and it must read as
        // the same control the main screen's pinned button offers ("Camera").
        assertEquals("Camera", stringResource(short!!))
        val longText = stringResource(long!!)
        assertTrue("the long label must say what the shortcut does: $longText",
            longText.contains("camera", ignoreCase = true))
    }
}
