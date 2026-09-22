package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status-bar inset the dictionary render owes
 * ([OcrOverlayStateController.dictionaryTopInset]).
 *
 * The overlay windows lay out under the system bars, so the panel whose top
 * edge is the screen's top edge renders its first lines behind the status bar
 * unless it pads them clear. The decision is gravity — where the panel is hung
 * — so that is what is pinned here: a top-hung portrait panel and either
 * landscape side panel pay the bar, a bottom-hung one does not.
 */
class DictionaryTopInsetTest {

    /** A plausible device status bar; the value is opaque to the rule. */
    private val statusBarPx = 196

    @Test
    fun `a portrait panel hung at the top pays the status bar its full height`() {
        val controller = OcrOverlayStateController()
        // Character in the bottom half → the panel hangs at the TOP so it does
        // not cover what was just tapped.
        controller.updateGravity(1080, 1920, JpDictRect(0, 1500, 100, 1600))
        assertEquals(JpDictGravity.TOP, controller.lastPortraitGravity)

        assertEquals(
            statusBarPx,
            controller.dictionaryTopInset(controller.lastPortraitGravity, statusBarPx),
        )
    }

    @Test
    fun `a portrait panel near the navigation bar owes the status bar nothing`() {
        val controller = OcrOverlayStateController()
        // Character in the top half → the panel hangs at the BOTTOM.
        controller.updateGravity(1080, 1920, JpDictRect(0, 100, 100, 200))
        assertEquals(JpDictGravity.BOTTOM, controller.lastPortraitGravity)

        assertEquals(
            0,
            controller.dictionaryTopInset(controller.lastPortraitGravity, statusBarPx),
        )
    }

    @Test
    fun `a landscape side panel always clears the status bar, whichever side it is on`() {
        // Both sides, because the side panel spans the full screen height: its
        // top edge is the screen's top edge whether it hangs left or right.
        for (tappedLeft in listOf(true, false)) {
            val controller = OcrOverlayStateController()
            val tapped = if (tappedLeft) JpDictRect(100, 0, 180, 100)
            else JpDictRect(1740, 0, 1820, 100)
            controller.updateGravity(1920, 1080, tapped)
            val gravity = controller.lastLandscapeGravity
            assertTrue(
                "expected a side gravity, got $gravity",
                gravity == JpDictGravity.START || gravity == JpDictGravity.END,
            )

            assertEquals(
                statusBarPx,
                controller.dictionaryTopInset(gravity, statusBarPx),
            )
            // The panel already on screen is read back out of its laid-out view,
            // which round-trips START/END through Gravity as START|LEFT /
            // END|RIGHT — that value has to count too, or the update path would
            // skip the inset the creation path just applied.
            assertEquals(
                statusBarPx,
                controller.dictionaryTopInset(
                    gravity.toAndroidGravity().toJpDictGravity(),
                    statusBarPx,
                ),
            )
        }
    }

    @Test
    fun `no status bar to clear means no inset, at any edge`() {
        val controller = OcrOverlayStateController()
        for (gravity in listOf(
            JpDictGravity.TOP, JpDictGravity.BOTTOM,
            JpDictGravity.START, JpDictGravity.END,
        )) {
            assertEquals(0, controller.dictionaryTopInset(gravity, 0))
        }
    }
}
