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
 * #89: the SHIPPED half of "a manual lookup screen with a shortcut, share and a
 * selection-menu entry" — the committed configuration a launcher and the platform
 * read.
 *
 * These assertions are about committed files rather than a fixture (the
 * [CameraShortcutResourceTest] precedent), and they are the standing guard on the
 * decisions made here:
 *  - the lookup shortcut targets `.MainActivity`, the already-exported launcher
 *    entry, on the namespaced [ManualLookupShortcut.ACTION_OPEN_LOOKUP] — so Back
 *    out of a cold shortcut launch lands on the home screen, not an empty task,
 *    which is the acceptance criterion;
 *  - it carries its own repo-authored mark, so a launcher's long-press menu tells
 *    it from the camera shortcut;
 *  - [ManualLookupActivity] is exported and answers `ACTION_SEND` + `text/plain`
 *    (a share) and `ACTION_PROCESS_TEXT` + `text/plain` (the selection toolbar),
 *    with the `DEFAULT` category each needs to match;
 *  - the activity's `android:label` is a string resource — it is what the
 *    selection toolbar shows, so it must read as a clear verb phrase.
 *
 * What this test cannot prove: that a launcher shows the shortcut, or that the
 * selection toolbar lists the entry. Those need a device; what is pinned here is
 * the declaration they are read from.
 */
class ManualLookupResourceTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val shortcutsTemplate = "shortcuts.xml.template"

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

    private fun activity(document: Document, name: String): Element =
        (0 until document.getElementsByTagName("activity").length)
            .map { document.getElementsByTagName("activity").item(it) as Element }
            .firstOrNull { it.attr("name") == name }
            ?: error("no <activity android:name=\"$name\"> in the manifest")

    private fun shortcut(document: Document, id: String): Element =
        (0 until document.getElementsByTagName("shortcut").length)
            .map { document.getElementsByTagName("shortcut").item(it) as Element }
            .firstOrNull { it.attr("shortcutId") == id }
            ?: error("no <shortcut android:shortcutId=\"$id\"> in the template")

    private fun shortcutIntent(shortcut: Element): Element =
        shortcut.children("intent").singleOrNull()
            ?: error("the shortcut declares no single <intent>: $shortcut")

    private fun stringResource(reference: String): String {
        val name = reference.removePrefix("@string/")
        assertTrue("not a string resource reference: $reference", name != reference && name.isNotEmpty())
        val strings = parse("src/main/res/values/strings.xml")
        val nodes = strings.getElementsByTagName("string")
        val entry = (0 until nodes.length).map { nodes.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == name }
            ?: error("no <string name=\"$name\"> in strings.xml for $reference")
        val text = entry.textContent?.trim().orEmpty()
        assertTrue("<string name=\"$name\"> is empty", text.isNotEmpty())
        return text
    }

    @Test
    fun theLookupShortcutOpensTheLookupThroughTheExportedLauncherEntryPoint() {
        val intent = shortcutIntent(shortcut(parse(shortcutsTemplate), "lookup"))
        // The action is the runtime rule's constant, so declaration and rule
        // cannot drift.
        assertEquals(
            "the shortcut must carry the action ManualLookupShortcut.opensLookup answers to",
            ManualLookupShortcut.ACTION_OPEN_LOOKUP,
            intent.attr("action")
        )
        // The package is a build-time token, deliberately not the release id: the
        // build substitutes the variant's applicationId so a dev build's shortcut
        // opens the dev app.
        assertEquals("\${applicationId}", intent.attr("targetPackage"))
        assertEquals("com.holopengin.instantjpdict.MainActivity", intent.attr("targetClass"))

        // …and that target really is the exported MAIN/LAUNCHER entry, so the
        // task root is the home screen and Back from a cold launch lands there.
        val launcher = activity(parse("src/main/AndroidManifest.xml"), ".MainActivity")
        assertEquals("true", launcher.attr("exported"))
        val filter = launcher.children("intent-filter").first { filter ->
            filter.children("action").any { it.attr("name") == "android.intent.action.MAIN" }
        }
        assertTrue(
            "the lookup shortcut target is not the launcher entry: $launcher",
            filter.children("category").any { it.attr("name") == "android.intent.category.LAUNCHER" }
        )
    }

    @Test
    fun theLookupShortcutCarriesItsOwnMarkAndResourceLabels() {
        val shortcut = shortcut(parse(shortcutsTemplate), "lookup")
        assertEquals(
            "the lookup shortcut must name its own mark",
            "@drawable/ic_shortcut_lookup",
            shortcut.attr("icon")
        )
        assertTrue(
            "the drawable the shortcut names does not exist",
            sourceFile("src/main/res/drawable/ic_shortcut_lookup.xml").isFile
        )
        // Distinct from the camera shortcut's mark: a shared icon would make the
        // two indistinguishable in a launcher's long-press menu.
        assertTrue(
            shortcut.attr("icon") != shortcut(parse(shortcutsTemplate), "camera").attr("icon")
        )
        assertEquals("true", shortcut.attr("enabled"))
        val short = shortcut.attr("shortcutShortLabel")
        val long = shortcut.attr("shortcutLongLabel")
        assertNotNull("the shortcut has no short label: $shortcut", short)
        assertNotNull("the shortcut has no long label: $shortcut", long)
        // The labels resolve, and the long one says what the shortcut does.
        assertTrue(stringResource(short!!).isNotEmpty())
        assertTrue(
            "the long label must say what the lookup shortcut does: ${stringResource(long!!)}",
            stringResource(long).contains("look up", ignoreCase = true)
        )
    }

    @Test
    fun theManualLookupActivityIsExportedWithTheShareAndSelectionFilters() {
        val tag = activity(parse("src/main/AndroidManifest.xml"), ".ManualLookupActivity")
        assertEquals("the manual screen is an entry surface, so it must be exported",
            "true", tag.attr("exported"))

        val filters = tag.children("intent-filter")
        fun filterFor(action: String): Element =
            filters.firstOrNull { it.children("action").any { a -> a.attr("name") == action } }
                ?: error("no intent-filter for $action: $tag")

        listOf(
            "android.intent.action.SEND",
            "android.intent.action.PROCESS_TEXT",
        ).forEach { action ->
            val filter = filterFor(action)
            // A plain-text filter, and the DEFAULT category every implicit intent
            // needs to resolve — without it the platform skips the filter.
            assertTrue(
                "$action filter must be text/plain",
                filter.children("data").any { it.attr("mimeType") == "text/plain" },
            )
            assertTrue(
                "$action filter needs the DEFAULT category",
                filter.children("category").any { it.attr("name") == "android.intent.category.DEFAULT" },
            )
        }
    }

    @Test
    fun bothHostsRenderThroughTheOneSharedComponent() {
        // Acceptance criterion 2, as a standing guard: the overlay and the manual
        // screen must build their entry cards through [DictionaryResultView], not
        // each with their own copy. The source files are the only place this can be
        // pinned without a device: a fork would be a second renderer that no
        // behavioural unit test could see.
        val overlay = sourceFile("src/main/java/com/holopengin/instantjpdict/OcrOverlayView.kt").readText()
        val manual = sourceFile("src/main/java/com/holopengin/instantjpdict/ManualLookupActivity.kt").readText()
        listOf("OcrOverlayView.kt" to overlay, "ManualLookupActivity.kt" to manual).forEach { (name, src) ->
            assertTrue(
                "$name does not render through DictionaryResultView — the renderer was forked",
                src.contains("DictionaryResultView."),
            )
        }
        // The shared component must not reach back into OCR/viewport state: a
        // reference to the overlay's controller there would make the manual
        // screen's reuse impossible to reason about. (The class doc names
        // OcrOverlayView, so the check is on a CODE reference, not the name.)
        val shared = sourceFile("src/main/java/com/holopengin/instantjpdict/DictionaryResultView.kt").readText()
        assertTrue(
            "DictionaryResultView references the overlay controller",
            !shared.contains("OcrOverlayStateController") && !shared.contains("OcrEngine"),
        )
    }

    @Test
    fun theSelectionToolbarSeesAClearVerbPhraseAsTheActivityLabel() {
        val tag = activity(parse("src/main/AndroidManifest.xml"), ".ManualLookupActivity")
        val label = tag.attr("label")
        assertNotNull("the manual screen must carry its own label: $tag", label)
        // The label is what the selection toolbar shows; a resource keeps it out
        // of a literal that could outlive an intent change.
        assertTrue("the label must be a string resource: $label", label!!.startsWith("@string/"))
        val text = stringResource(label)
        assertTrue(
            "the selection-menu label must read as a lookup: '$text'",
            text.contains("look up", ignoreCase = true),
        )
    }
}
