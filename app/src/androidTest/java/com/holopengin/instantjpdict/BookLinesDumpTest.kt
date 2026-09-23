package com.holopengin.instantjpdict

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.holopengin.instantjpdict.util.BlankGaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dump the device pipeline's *rendered* lines for a book-page fixture.
 *
 * Runs the app's own path — `detect` → `recognizeStreaming` → [BlankGaps]
 * (#44, vertical) — on a page image staged in androidTest/assets, and logs
 * each line's text, columns and boxes under the `LineBoxes` tag, so
 * `adb logcat -d -s LineBoxes:D` shows exactly what the overlay draws on the
 * actual device nets.  Research aid for char-placement diagnosis; the asset is
 * test material and is not committed.
 */
@RunWith(AndroidJUnit4::class)
class BookLinesDumpTest {

    @Test
    fun dumpBookPageLines() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val bmp = instr.context.assets.open("bookpage.png").use {
            BitmapFactory.decodeStream(it)
        }!!
        val detBoxes = engine.detect(bmp)
        Log.i(TAG, "det ${detBoxes.size} boxes on ${bmp.width}x${bmp.height}")

        val collected = mutableListOf<Pair<Int, LineResult>>()
        runBlocking {
            engine.recognizeStreaming(bmp, detBoxes) { pairs ->
                synchronized(collected) { collected.addAll(pairs) }
            }
            var waited = 0
            var last = -1
            var still = 0
            while (waited < 30_000) {
                delay(200)
                waited += 200
                synchronized(collected) {
                    if (collected.size == last) still += 200 else { still = 0; last = collected.size }
                }
                if (last >= detBoxes.size) break               // every box spoke
                if (waited >= 8_000 && still >= 4_000) break   // quiet for 4s
            }
        }
        Log.i(TAG, "collected ${collected.size} lines")

        for ((_, lr) in collected.sortedBy { it.first }) {
            // Exactly what the overlay renders: BlankGaps first (#44, vertical).
            val line = BlankGaps.applyIfEnabled(instr.targetContext, lr)
            Log.d(
                "LineBoxes",
                "v=${line.isVertical} crop=${line.cropX},${line.cropY},${line.cropW},${line.cropH} " +
                    "seq=${line.seqLenTotal} text=${line.text}"
            )
            Log.d("LineBoxes", "cols=${line.charCols.joinToString(",")}")
            Log.d("LineBoxes", "boxes=${line.charBoxes.joinToString(";")}")
        }
        assertTrue("no lines recognized", collected.isNotEmpty())
    }

    companion object {
        private const val TAG = "BookDump"
        private lateinit var engine: OcrEngine

        @JvmStatic
        @BeforeClass
        fun setupEngine() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            engine = OcrEngine(appContext)
            assertTrue("OcrEngine failed to load", engine.isReady())
        }
    }
}
