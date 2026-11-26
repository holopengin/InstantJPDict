package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the unified page pipeline on the device, without the
 * overlay UI. This is the test that would have caught the missing
 * `libomp.so`: constructing the engine and calling `recognizePage` forces
 * `libnav_graph_core.so` through dlopen and every native OCR net.
 */
@RunWith(AndroidJUnit4::class)
class PageOcrDeviceTest {
    /**
     * The debug screen reads `RotatedGeometry` constants at class-init time,
     * which is the first nav-graph/native call. On rc11 this crashed because
     * `libnav_graph_core.so` could not resolve `__kmpc_global_thread_num`.
     */
    @Test
    fun bitmapCopyPixelsToBufferIsRgbaOnThisDevice() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.RED)
        val buffer = java.nio.ByteBuffer.allocate(4)
        bitmap.copyPixelsToBuffer(buffer)
        val b = buffer.array()
        assertTrue("red pixel bytes were ${b.joinToString()}", b[0] == 0xFF.toByte())
        assertTrue("red pixel bytes were ${b.joinToString()}", b[1] == 0.toByte())
        assertTrue("red pixel bytes were ${b.joinToString()}", b[2] == 0.toByte())
        assertTrue("red pixel bytes were ${b.joinToString()}", b[3] == 0xFF.toByte())
    }

    @Test
    fun debugSettingsOpensWithTheNativeLibraryLoaded() {
        ActivityScenario.launch(DebugSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("debug settings did not reach resumed state", !activity.isFinishing)
            }
        }
    }

    private val tag = "PageOcrDeviceTest"

    @Test
    fun pageTimingsOnTheBenchmarkPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = instrumentation.context.assets.open("benchmark/ebook_tategaki.png").use {
            checkNotNull(BitmapFactory.decodeStream(it))
        }
        val engine = OcrEngine(context)
        try {
            runBlocking { engine.recognizePage(bitmap) }
            runBlocking { engine.recognizePage(bitmap) }
            repeat(5) { i ->
                val t0 = SystemClock.elapsedRealtimeNanos()
                val lines = runBlocking { engine.recognizePage(bitmap) }
                val total = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
                val t = engine.lastPageTimings
                Log.i(
                    tag,
                    "run=$i total=$total lines=${lines.size} prepare=${t?.prepareMs} " +
                        "detect=${t?.detectMs} rec=${t?.recognizeMs} native=${t?.nativeMs} " +
                        "map=${t?.mapMs}",
                )
                assertTrue("page returned no lines", lines.isNotEmpty())
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun recognizesADrawnJapaneseLine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = OcrEngine(context)
        try {
            val bitmap = Bitmap.createBitmap(1200, 320, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 120f
                typeface = Typeface.DEFAULT
            }
            canvas.drawText("日本語を勉強しています。", 40f, 210f, paint)

            val lines = runBlocking { engine.recognizePage(bitmap) }
            assertTrue("no lines detected", lines.isNotEmpty())
            val text = lines.joinToString("") { it.second.text }
            assertTrue("empty recognition result", text.isNotBlank())
            assertTrue(
                "recognition did not contain the drawn kana: $text",
                text.contains("日本語") || text.contains("勉強"),
            )
        } finally {
            engine.close()
        }
    }
}
