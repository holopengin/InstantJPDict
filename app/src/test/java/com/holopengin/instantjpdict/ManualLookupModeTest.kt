package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #108: the mode is explicit and a bad stored value never traps the screen. */
class ManualLookupModeTest {

    @Test
    fun everyModeRoundTripsThroughItsStoredValue() {
        ManualLookupMode.entries.forEach { mode ->
            assertEquals(mode, ManualLookupMode.fromStored(mode.storedValue))
        }
    }

    @Test
    fun missingAndUnknownValuesFallBackToJapaneseLookup() {
        assertEquals(ManualLookupMode.JP_TO_EN, ManualLookupMode.fromStored(null))
        assertEquals(ManualLookupMode.JP_TO_EN, ManualLookupMode.fromStored(""))
        assertEquals(ManualLookupMode.JP_TO_EN, ManualLookupMode.fromStored("romaji_to_jp"))
        assertEquals(ManualLookupMode.JP_TO_EN, ManualLookupMode.fromStored("something-old"))
    }

    @Test
    fun storedValuesAreUnique() {
        val values = ManualLookupMode.entries.map { it.storedValue }
        assertEquals(values.size, values.toSet().size)
        assertTrue(values.all { it.isNotBlank() })
    }
}
