package com.holopengin.instantjpdict

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.holopengin.instantjpdict.util.KanaSizeFix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Interleaved A/B timing of the two page paths on one real page:
 *
 *  - old: Kotlin `detectLines` + `recognizeStreaming`, then the separate
 *    `KanaSizeFix.correctPage` pass the overlay used to run;
 *  - new: one `recognizePage` call, kana correction inside Rust.
 *
 * Both runs use the same `OcrEngine`, the same bitmap, and warm nets. Run with:
 *
 *   ./gradlew :app:connectedBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.OcrPipelineBenchmarkTest
 */
@RunWith(AndroidJUnit4::class)
class OcrPipelineBenchmarkTest {
    private val tag = "OcrPipelineBenchmark"

    private data class Old(
        val total: Long,
        val detect: Long,
        val rec: Long,
        val kana: Long,
        val boxes: Int,
        val lines: Int,
        val chars: Int,
    )

    private data class New(val total: Long, val lines: Int, val chars: Int)

    @Test
    fun newRustPageCallVersusOldMixedPath() {
        benchmark("benchmark/ebook_tategaki.png", "ebook")
    }

    private fun benchmark(asset: String, label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = instrumentation.context.assets.open(asset).use {
            checkNotNull(BitmapFactory.decodeStream(it))
        }
        val engine = OcrEngine(context)
        Log.i(
            tag,
            "$label config rotated=${OcrEngine.isDetRotated(context)} " +
                "layout=${OcrEngine.BOX_LAYOUT_MODE} uniform=${OcrEngine.BOX_UNIFORM_SIZE} " +
                "cap=${context.getSharedPreferences(OcrEngine.PREFS_NAME, 0).getBoolean(OcrEngine.PREF_BOX_PLACEMENT_CAP, true)} " +
                "squish=${engine.recSquish}",
        )
        try {
            // Warm both paths: model load, page-recognizer construction, kana
            // net materialisation and ncnn thread pools are excluded.
            measureOld(engine, context, bitmap)
            measureNew(engine, bitmap)

            val old = ArrayList<Old>(10)
            val new = ArrayList<New>(10)
            repeat(10) { i ->
                // Alternate the order so a thermal ramp cannot favour one arm.
                if (i % 2 == 0) {
                    old += measureOld(engine, context, bitmap)
                    new += measureNew(engine, bitmap)
                } else {
                    new += measureNew(engine, bitmap)
                    old += measureOld(engine, context, bitmap)
                }
            }
            val oldSorted = old.sortedBy { it.total }
            val newSorted = new.sortedBy { it.total }
            val om = oldSorted[oldSorted.size / 2]
            val nm = newSorted[newSorted.size / 2]
            Log.i(tag, "$label old_ms=" + old.joinToString(",") { it.total.toString() })
            Log.i(tag, "$label new_ms=" + new.joinToString(",") { it.total.toString() })
            Log.i(tag, "$label old_median=${om.total} new_median=${nm.total}")
            Log.i(
                tag,
                "$label old_stage_median detect=${old.map { it.detect }.sorted()[old.size / 2]} " +
                    "rec=${old.map { it.rec }.sorted()[old.size / 2]} " +
                    "kana=${old.map { it.kana }.sorted()[old.size / 2]}",
            )
            Log.i(
                tag,
                "$label boxes old=${om.boxes} new=${nm.lines} lines old=${om.lines} new=${nm.lines} " +
                    "chars old=${om.chars} new=${nm.chars}",
            )
            assertTrue("no lines measured", om.total > 0 && nm.total > 0)
        } finally {
            engine.close()
        }
    }

    private fun measureNew(engine: OcrEngine, bitmap: android.graphics.Bitmap): New {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val lines = runBlocking { engine.recognizePage(bitmap) }
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        assertTrue("new path returned no lines", lines.isNotEmpty())
        return New(ms, lines.size, lines.sumOf { it.second.text.length })
    }

    private fun measureOld(
        engine: OcrEngine,
        context: android.content.Context,
        bitmap: android.graphics.Bitmap,
    ): Old {
        val totalStart = SystemClock.elapsedRealtimeNanos()
        val tDetect = SystemClock.elapsedRealtimeNanos()
        val boxes = engine.detectLines(bitmap)
        val detectMs = (SystemClock.elapsedRealtimeNanos() - tDetect) / 1_000_000
        val results = mutableListOf<LineResult>()
        val tRec = SystemClock.elapsedRealtimeNanos()
        runBlocking {
            engine.recognizeStreaming(bitmap, boxes) { batch ->
                results.addAll(batch.map { it.second })
            }
        }
        // recognizeStreaming posts each line to the main looper; a sentinel
        // posted after it returns runs after those posts.
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { done.countDown() }
        check(done.await(30, TimeUnit.SECONDS)) { "old recognition callbacks did not drain" }
        val recMs = (SystemClock.elapsedRealtimeNanos() - tRec) / 1_000_000
        val tKana = SystemClock.elapsedRealtimeNanos()
        KanaSizeFix.correctPage(context, results)
        val kanaMs = (SystemClock.elapsedRealtimeNanos() - tKana) / 1_000_000
        val totalMs = (SystemClock.elapsedRealtimeNanos() - totalStart) / 1_000_000
        assertTrue("old path returned no lines", results.isNotEmpty())
        return Old(
            total = totalMs,
            detect = detectMs,
            rec = recMs,
            kana = kanaMs,
            boxes = boxes.size,
            lines = results.size,
            chars = results.sumOf { it.text.length },
        )
    }
}
