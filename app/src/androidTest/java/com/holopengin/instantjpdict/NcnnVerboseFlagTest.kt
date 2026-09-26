package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The verbose switch must be *exactly* that: the informational lines appear
 * when it is on and not one of them appears when it is off.
 *
 * A timing test cannot prove this — the effect is small next to the noise, and
 * a switch that quietly failed to reach the rec log site would still show up as
 * "no measurable difference". So this gate makes a **counted** number of calls
 * on each side and the host checks the log:
 *
 * ```
 * ./gradlew :app:connectedBenchmarkAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.NcnnVerboseFlagTest
 * adb logcat -d -s PpocrNcnn:I | grep -c recTopK   # == REC_CALLS
 * adb logcat -d -s PpocrNcnn:I | grep -c 'Det '   # == DET_CALLS * LINES_PER_DET_CALL
 * ```
 *
 * Run it as the *only* class in the run, or the count includes whatever else
 * logged. The numbers are written out at the end so a host script can compare.
 */
@RunWith(AndroidJUnit4::class)
class NcnnVerboseFlagTest {

    @Test
    fun flagOnPrintsTheLinesAndFlagOffDoesNot() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bmp = loadBitmap("bookpage.png")
        val modelSize = 896
        val targetLong = minOf(OcrEngine.DEF_DET_LONG_SIDE, modelSize)
        val scale = targetLong.toFloat() / maxOf(bmp.width, bmp.height)
        val resizeW = maxOf((bmp.width * scale).roundToInt(), 32)
        val resizeH = maxOf((bmp.height * scale).roundToInt(), 32)
        val padX = (modelSize - resizeW + 1) / 2
        val padY = (modelSize - resizeH + 1) / 2
        val resized = Bitmap.createScaledBitmap(bmp, resizeW, resizeH, true)
        val crop = IntArray(resizeW * resizeH)
        resized.getPixels(crop, 0, resizeW, 0, 0, resizeW, resizeH)
        resized.recycle()

        val det = DetNcnn.create(ctx)!!
        val rec = RecNcnn.create(ctx)!!
        val recW = 128
        val recIn = FloatArray(3 * 48 * recW) { 0.25f }

        // What the debug screen stored, and what create() pushed into native —
        // logged so a host reading logcat can see the whole chain, and asserted
        // as a pairing so the check holds whether the pref is on or off.
        val prefOn = NcnnVerboseLog.isEnabled(ctx)
        assertEquals("create() must apply the stored pref", prefOn, DetNcnn.isVerboseLogging())

        // Warm, silently: the model-load and first-tensor lines belong to the
        // armed section below, not to the warmup.
        DetNcnn.setVerboseLogging(false)
        assertFalse("the flag must start off", DetNcnn.isVerboseLogging())
        det.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)
        rec.inferTopK(recIn, recW, 48)

        repeat(DET_CALLS) { det.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY) }
        repeat(REC_CALLS) { rec.inferTopK(recIn, recW, 48) }

        DetNcnn.setVerboseLogging(true)
        assertTrue("the flag must be on", DetNcnn.isVerboseLogging())
        repeat(DET_CALLS) { det.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY) }
        repeat(REC_CALLS) { rec.inferTopK(recIn, recW, 48) }

        // Errors are not gated: with the flag off, a zero-width rec input is
        // refused and the core says so through PPOCR_LOGE, which has no gated
        // wrapper. (It fails closed — returns null — rather than reading a stale
        // stride, so this is safe to ask for on demand.)
        DetNcnn.setVerboseLogging(false)
        assertTrue("a zero-width rec input must be refused", rec.inferTopK(FloatArray(0), 0, 48) == null)

        Log.i(
            TAG,
            "pref=$prefOn (what the debug screen stored, pushed into native at create) " +
                "expect recTopK=$REC_CALLS detLines=${DET_CALLS * LINES_PER_DET_CALL} " +
                "errorLines=1 (flag was OFF for the first $DET_CALLS det and $REC_CALLS rec calls, " +
                "ON for the second, OFF again for the error)"
        )
        det.close()
        rec.close()
    }

    private fun loadBitmap(path: String): Bitmap {
        val instr = InstrumentationRegistry.getInstrumentation()
        for ((p, assets) in listOf(path to instr.context.assets, path to instr.targetContext.assets)) {
            try {
                assets.open(p).use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    if (bmp != null) return bmp
                }
            } catch (_: Exception) {
            }
        }
        error("fixture not found: $path")
    }

    private companion object {
        const val TAG = "NcnnVerboseFlag"
        const val DET_CALLS = 6
        const val REC_CALLS = 6

        /** det_run's informational lines on the letterbox path: one for the
         *  cached extract, one for the finished infer. (The third,
         *  `Det input in0`, belongs to the float path's det_infer.) */
        const val LINES_PER_DET_CALL = 2
    }
}
