package com.holopengin.instantjpdict

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.nav_graph_core.PageOcrException
import uniffi.nav_graph_core.PageRecognizer

/**
 * The page OCR surface exists in the host build so the generated Kotlin
 * bindings compile, but the native inference stack is Android-only. Construction
 * must fail with a diagnosable exception instead of loading a missing model or
 * an Android `.so` on the JVM.
 */
class PageOcrHostTest {
    @Test
    fun hostConstructionReportsAndroidOnly() {
        try {
            PageRecognizer("/nonexistent", 1u)
            fail("host PageRecognizer construction should fail")
        } catch (e: PageOcrException) {
            assertTrue(
                "unexpected error: ${e.message}",
                e.message.orEmpty().contains("Android only"),
            )
        }
    }
}
