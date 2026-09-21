package com.holopengin.instantjpdict

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test. The app id carries a build-type suffix
        // now (.debug, and .dev for untagged releases), so the expected package
        // comes from the instrumentation instead of a literal: the test APK is
        // the target app's id plus ".test".
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(
            instrumentation.context.packageName.removeSuffix(".test"),
            instrumentation.targetContext.packageName
        )
    }
}