package com.holopengin.instantjpdict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device gate for the kana small/large model (#44) through the shared
 * `kana_size_core` and the real ARM kernels.
 *
 * `selfCheck()` runs the model author's ten published vectors through the
 * device's own encoder + JNI and fails above 1e-4. The PC host test
 * (`kana_model_self_check`) covers the same vectors; this is the arm64 half
 * that cannot run on the build host.
 *
 * Run:
 * `./gradlew :app:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.KanaSizeNcnnDeviceTest`
 */
@RunWith(AndroidJUnit4::class)
class KanaSizeNcnnDeviceTest {
    @Test
    fun selfCheckMatchesThePublishedVectors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = KanaSizeNcnn.load(context)
        assertNotNull("kana ncnn model failed to load", engine)
        val verdict = engine!!.selfCheck()
        assertTrue(verdict, verdict.startsWith("kana model OK"))
    }
}
