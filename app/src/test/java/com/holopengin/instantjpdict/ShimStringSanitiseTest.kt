package com.holopengin.instantjpdict

import org.junit.Assert.assertNotNull
import org.junit.Test
import uniffi.nav_graph_core.FfiConverterString

/**
 * #106: the shim, not its callers, guarantees that a Java string can cross into
 * Rust.
 *
 * An app's own text — an accessibility node's, or a slice of one — can carry an
 * unpaired surrogate, which is legal in a `String` and impossible to encode as
 * UTF-8. UniFFI's generated converter is deliberately strict about that, so it
 * threw `MalformedInputException: Input length = 1` out of every Rust call taking
 * a string — a dictionary lookup, crashing the app on the main thread when the
 * robot emoji was tapped.
 *
 * The fix is a LOCAL PATCH inside [FfiConverterString.toUtf8]
 * (`tools/patch_uniffi_string_sanitise.py` re-applies it after a regeneration),
 * and these tests exercise it directly, because a regeneration that silently
 * dropped it would otherwise only show up on a device.
 */
class ShimStringSanitiseTest {

    @Test
    fun aLoneHighSurrogateCrossesTheShim() {
        assertNotNull(FfiConverterString.lower("a\uD83Db"))
    }

    @Test
    fun aLoneLowSurrogateCrossesTheShim() {
        assertNotNull(FfiConverterString.lower("a\uDE00b"))
    }

    @Test
    fun aSliceStartingInsideAnEmojiCrossesTheShim() {
        // What the lookup path actually manufactures: `subList` over UTF-16 units,
        // so the robot emoji's lower half can begin the string.
        val robot = "\uD83E\uDD16"
        assertNotNull(FfiConverterString.lower(robot.substring(1) + "の"))
        assertNotNull(FfiConverterString.lower("猫" + robot.substring(0, 1)))
    }

    @Test
    fun aValidPairAndOrdinaryTextStillCross() {
        assertNotNull(FfiConverterString.lower("😀 吾輩は猫である"))
    }
}
