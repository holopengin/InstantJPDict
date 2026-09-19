package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash report's shape and the published feedback address. The Android
 * halves (intent, FileProvider, handler install) are exercised on device; the
 * text a report must carry is pure and pinned here.
 */
class CrashReportTest {

    private val text = CrashReporter.crashText(
        time = "2026-09-19T07:30:00+09:00",
        app = "InstantJPDict 1.0.0-rc1 (1)",
        device = "Google Pixel 7a, Android 16 (API 36)",
        threadName = "main",
        process = "com.holopengin.instantjpdict (pid 4242)",
        stackTrace = "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.kt:1)",
    )

    @Test
    fun report_carriesEveryTriageField() {
        assertTrue(text.startsWith("InstantJPDict crash report\n"))
        assertTrue(text.contains("Time: 2026-09-19T07:30:00+09:00"))
        assertTrue(text.contains("App: InstantJPDict 1.0.0-rc1 (1)"))
        assertTrue(text.contains("Device: Google Pixel 7a, Android 16 (API 36)"))
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("Process: com.holopengin.instantjpdict (pid 4242)"))
    }

    @Test
    fun stackTrace_isLast_andKeepsItsFrames() {
        assertTrue(text.trimEnd().endsWith("at Foo.bar(Foo.kt:1)"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun feedbackAddress_isThePublishedOne() {
        assertEquals("holographicpengin+InstantJPDict@gmail.com", Feedback.ADDRESS)
    }
}
