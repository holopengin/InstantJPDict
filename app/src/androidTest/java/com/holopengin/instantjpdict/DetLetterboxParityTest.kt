package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Det letterbox-in-C++ gate (#det-preprocessing perf pass).
 *
 * `OcrEngine.runDetMask` used to letterbox on a `Canvas`, read the 896² bitmap
 * back with `getPixels`, write 3·896² floats through `DET_NORM_LUT` in Kotlin and
 * hand the array to `DetNcnn.infer`, which copied it into a direct ByteBuffer
 * before JNI (and ncnn copied it again in `fill_input`). [DetNcnn.inferLetterboxed]
 * does letterbox + normalise natively from the *resized* crop's pixels, so the
 * array never exists on the JVM.
 *
 * The gate is deliberately stronger than "the boxes look the same": the
 * preprocessed tensor is compared **bit for bit** against the Kotlin build it
 * replaces, then the probability maps, and only then the boxes. Each link is
 * measured and logged, so a regression says which link broke.
 *
 * Fixtures: `bookpage.png` (1080x2400 portrait, 493px of horizontal padding →
 * the odd half-pixel offset case) and `benchmark/Screenshot_20260530-172718.png`
 * (2400x1080 landscape, padding vertical). Both exercise a non-zero pad on
 * both axes; a fixture that letterboxed exactly would prove nothing about the
 * padding constants.
 *
 * Run: `./gradlew :app:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.DetLetterboxParityTest`
 */
@RunWith(AndroidJUnit4::class)
class DetLetterboxParityTest {

    // ─────────── fixtures ───────────

    private fun loadBitmap(path: String): Bitmap {
        val instr = InstrumentationRegistry.getInstrumentation()
        for ((p, assets) in listOf(
            path to instr.context.assets,
            path to instr.targetContext.assets,
        )) {
            try {
                assets.open(p).use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    if (bmp != null) return bmp
                }
            } catch (_: Exception) { }
        }
        error("fixture not found: $path")
    }

    /** Everything the two paths are measured over, one warm image. */
    private class Fixture(val name: String, val bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        override fun toString() = "$name (${bmp.width}x${bmp.height})"
    }

    private fun fixtures(): List<Fixture> = listOf(
        Fixture("bookpage.png", loadBitmap("bookpage.png")),
        Fixture("benchmark/Screenshot_20260530-172718.png", loadBitmap("benchmark/Screenshot_20260530-172718.png")),
    )

    // ─────────── the Kotlin path, copied verbatim from OcrEngine.runDetMask ───────────

    /**
     * `DET_NORM_LUT` read out of OcrEngine by reflection, so the native LUT is
     * compared against the *live* one and not against a second copy that could
     * drift from it. Falls back to the literal copy below only if reflection is
     * blocked, and says so in the log.
     */
    private fun liveNormLut(): FloatArray {
        try {
            val f = OcrEngine::class.java.getDeclaredField("DET_NORM_LUT").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return f.get(OcrEngine) as FloatArray
        } catch (e: Exception) {
            Log.w(TAG, "could not reflect DET_NORM_LUT ($e); using a local copy")
            return normLut()
        }
    }

    /** Verbatim copy of OcrEngine.DET_NORM_LUT; see [liveNormLut]. */
    private fun normLut(): FloatArray = FloatArray(768) { i ->
        val v = (i % 256).toFloat() / 255f
        when (i / 256) {
            0 -> (v - 0.485f) / 0.229f
            1 -> (v - 0.456f) / 0.224f
            else -> (v - 0.406f) / 0.225f
        }
    }

    /** The old preprocessing, with its four phases timed separately. */
    private class LegacyPrep(
        val tag: String,
        val pixels: IntArray,       // the 896² letterbox, as getPixels saw it
        val resizeW: Int,
        val resizeH: Int,
        val padX: Int,
        val padY: Int,
        val modelSize: Int,
        val tensor: FloatArray,
        val resizeMs: Double,
        val drawMs: Double,
        val getPixelsMs: Double,
        val loopMs: Double,
    ) {
        val prepMs: Double get() = resizeMs + drawMs + getPixelsMs + loopMs
    }

    // Pooled exactly like OcrEngine's tlDetLetterbox / tlDetPixels /
    // tlDetImgData, so the legacy timing includes the real (warm) buffers.
    private val letterboxes = HashMap<Int, Pair<Bitmap, Canvas>>()

    private fun letterboxFor(modelSize: Int): Pair<Bitmap, Canvas> =
        letterboxes.getOrPut(modelSize) {
            val bmp = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
            bmp to Canvas(bmp)
        }

    private val tlPixels = arrayOfNulls<IntArray>(4)
    private val tlTensor = arrayOfNulls<FloatArray>(4)

    /**
     * What did `Canvas.drawBitmap` at a *fractional* offset actually put in the
     * letterbox? The Kotlin caller centres the image with `(S - w) / 2f`, which
     * is a half pixel whenever the difference is odd, so the naive "content
     * starts at (S-w)/2" assumption has to be checked, not trusted. Prints the
     * offending columns, a raw slice across the seam, and how many pixels each
     * candidate placement explains.
     */
    private fun diagnoseLetterbox(legacy: LegacyPrep, pixels: IntArray, built: FloatArray, modelSize: Int) {
        val S = modelSize
        // Which tensor plane/rows/columns differ.
        val plane = S * S
        var perPlane = IntArray(3)
        var minX = S; var maxX = -1; var minY = S; var maxY = -1
        var sampleAt = -1
        for (i in built.indices) {
            if (built[i] == legacy.tensor[i]) continue
            val p = i / plane
            val b = i % plane
            perPlane[p]++
            if (p == 0) {
                val y = b / S
                val x = b % S
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (sampleAt < 0) sampleAt = i
            }
        }
        Log.i(TAG, "DIAG ${legacy.tag} tensor diff planes=${perPlane.toList()} " +
            "xRange=$minX..$maxX yRange=$minY..$maxY")
        if (sampleAt >= 0) {
            val b = sampleAt % plane
            Log.i(
                TAG,
                "DIAG sample at (x=${b % S},y=${b / S}) legacy=" +
                    "${legacy.tensor[sampleAt]},${legacy.tensor[plane + b]},${legacy.tensor[2 * plane + b]} " +
                    "native=${built[sampleAt]},${built[plane + b]},${built[2 * plane + b]}"
            )
        }
        // Raw letterbox pixels across the seam, vs the source row.
        val yProbe = minOf(legacy.resizeH / 2, S - 1)
        val lbRow = yProbe + legacy.padY
        val sb = StringBuilder()
        for (x in (legacy.padX - 4)..(legacy.padX + 5)) sb.append(" ${Integer.toHexString(legacy.pixels[lbRow * S + x])}")
        sb.append(" | ")
        for (x in (legacy.padX + legacy.resizeW - 5)..(legacy.padX + legacy.resizeW + 3)) sb.append(" ${Integer.toHexString(legacy.pixels[lbRow * S + x])}")
        val ss = StringBuilder()
        for (x in 0..5) ss.append(" ${Integer.toHexString(pixels[yProbe * legacy.resizeW + x])}")
        ss.append(" | ")
        for (x in (legacy.resizeW - 6) until legacy.resizeW) ss.append(" ${Integer.toHexString(pixels[yProbe * legacy.resizeW + x])}")
        Log.i(TAG, "DIAG letterboxRow$yProbe=[$sb]")
        Log.i(TAG, "DIAG sourceRow  $yProbe=[$ss]")
        // How many letterbox pixels does each candidate placement explain?
        for (x0 in (legacy.padX - 2)..(legacy.padX + 2)) {
            var bad = 0
            for (y in 0 until legacy.resizeH) {
                for (x in 0 until legacy.resizeW) {
                    val dx = x0 + x
                    if (dx < 0 || dx >= S) { bad += legacy.resizeW * legacy.resizeH; continue }
                    if (legacy.pixels[(y + legacy.padY) * S + dx] != pixels[y * legacy.resizeW + x]) bad++
                }
            }
            Log.i(TAG, "DIAG nearest-copy offsetX=$x0 mismatchingPixels=$bad")
        }
        // 50/50 horizontal blend at the seam (a half-pixel translate with a
        // filtering raster blit shows up as neighbour averages).
        var badBlend = 0
        for (y in 0 until legacy.resizeH) {
            for (x in 0 until legacy.resizeW) {
                val a = pixels[y * legacy.resizeW + maxOf(0, x - 1)]
                val b = pixels[y * legacy.resizeW + x]
                val mix = ((a and 0xFF) + (b and 0xFF)) / 2 and 0xFF
                val want = (0xFF shl 24) or (mix shl 16) or (mix shl 8) or mix
                if (legacy.pixels[(y + legacy.padY) * S + legacy.padX + x] != want) badBlend++
            }
        }
        Log.i(TAG, "DIAG h-blend-at-$legacy.padX mismatchingPixels=$badBlend")
    }

    /**
     * OcrEngine.runDetMask steps 1-2, byte for byte: Skia resize, 128-gray
     * fill, `drawBitmap` at the *float* centre offset, `getPixels`, then the
     * single-pass NCHW write. Returns the pixels/geometry the native path needs
     * as well (`padX`/`padY` are the integer rounding of the same offset).
     */
    private fun legacyPrepare(tag: String, bmp: Bitmap, modelSize: Int, targetLong: Int, lut: FloatArray, slot: Int): LegacyPrep {
        val origW = bmp.width.toFloat()
        val origH = bmp.height.toFloat()
        val scale = targetLong.toFloat() / maxOf(origW, origH)
        val resizeW = maxOf((origW * scale).roundToInt(), 32)
        val resizeH = maxOf((origH * scale).roundToInt(), 32)

        var t0 = System.nanoTime()
        val resized = Bitmap.createScaledBitmap(bmp, resizeW, resizeH, true)
        val resizeMs = (System.nanoTime() - t0) / 1e6

        val (lb, canvas) = letterboxFor(modelSize)
        t0 = System.nanoTime()
        canvas.drawColor(Color.rgb(128, 128, 128))
        canvas.drawBitmap(resized, (modelSize - resizeW) / 2f, (modelSize - resizeH) / 2f, null)
        resized.recycle()
        val drawMs = (System.nanoTime() - t0) / 1e6

        val needFloats = 3 * modelSize * modelSize
        val needInts = modelSize * modelSize
        val imgData = tlTensor[slot]?.takeIf { it.size == needFloats }
            ?: FloatArray(needFloats).also { tlTensor[slot] = it }
        val pixels = tlPixels[slot]?.takeIf { it.size >= needInts }
            ?: IntArray(needInts).also { tlPixels[slot] = it }

        t0 = System.nanoTime()
        lb.getPixels(pixels, 0, modelSize, 0, 0, modelSize, modelSize)
        val getPixelsMs = (System.nanoTime() - t0) / 1e6

        t0 = System.nanoTime()
        for (y in 0 until modelSize) {
            for (x in 0 until modelSize) {
                val px = pixels[y * modelSize + x]
                val r = lut[px shr 16 and 0xFF]
                val g = lut[256 + (px shr 8 and 0xFF)]
                val b = lut[512 + (px and 0xFF)]
                val base = y * modelSize + x
                imgData[base] = r
                imgData[modelSize * modelSize + base] = g
                imgData[2 * modelSize * modelSize + base] = b
            }
        }
        val loopMs = (System.nanoTime() - t0) / 1e6

        return LegacyPrep(
            tag = tag,
            pixels = pixels,
            resizeW = resizeW,
            resizeH = resizeH,
            // NOT `(modelSize - resizeW) / 2`: the Canvas is asked for
            // `(modelSize - resizeW) / 2f`, and Skia's blit rounds that half
            // pixel AWAY FROM ZERO. Measured on bookpage.png (896-403 = 493):
            // the content lands on x=247, and the nearest-copy model at 247
            // explains 0 of 361,088 pixels wrong while 246 explains 28,787.
            // A whole-pixel shift of the content is not a rounding nit — it
            // moves the letterbox relative to the probability map.
            padX = skiaPad(modelSize, resizeW),
            padY = skiaPad(modelSize, resizeH),
            modelSize = modelSize,
            tensor = imgData,
            resizeMs = resizeMs,
            drawMs = drawMs,
            getPixelsMs = getPixelsMs,
            loopMs = loopMs,
        )
    }

    /** Where Skia actually put a `(S - content) / 2f` translate: round half up. */
    private fun skiaPad(modelSize: Int, content: Int): Int = (modelSize - content + 1) / 2

    /** The pixel array the native path consumes: just the resized crop. */
    private fun resizedPixels(bmp: Bitmap, resizeW: Int, resizeH: Int): IntArray {
        val resized = Bitmap.createScaledBitmap(bmp, resizeW, resizeH, true)
        val px = IntArray(resizeW * resizeH)
        resized.getPixels(px, 0, resizeW, 0, 0, resizeW, resizeH)
        resized.recycle()
        return px
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "size ${a.size} vs ${b.size}" }
        var m = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > m) m = d
            if (d == 0f) continue
        }
        return m
    }

    private fun countDiff(a: FloatArray, b: FloatArray): Int {
        var n = 0
        for (i in a.indices) if (a[i] != b[i]) n++
        return n
    }
    // ─────────── 1. bit parity + timing ───────────

    @Test
    fun letterboxIsBitIdenticalAndFaster() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val det = DetNcnn.create(instr.targetContext)
        assertNotNull("DetNcnn failed to create", det)
        val d = det!!
        val lut = liveNormLut()
        val modelSize = OcrEngine.DET_MODEL_SIZE.coerceIn(320, 960)
        Log.i(TAG, "PARITY modelSize=$modelSize lut[0]=${lut[0]} lut[128]=${lut[128]} lut[384]=${lut[384]}")

        try {
            for (fx in fixtures()) {
                val targetLong = minOf(OcrEngine.DEF_DET_LONG_SIDE, modelSize)
                // Warm both paths (pooled bitmaps, first-touch pages, ncnn caches).
                val warm = legacyPrepare(fx.name, fx.bmp, modelSize, targetLong, lut, 0)
                val warmPx = resizedPixels(fx.bmp, warm.resizeW, warm.resizeH)
                assertNotNull("warmup letterboxed infer", d.inferLetterboxed(warmPx, warm.resizeW, warm.resizeH, modelSize, warm.padX, warm.padY))
                assertNotNull("warmup float infer", d.infer(warm.tensor, modelSize, modelSize))

                val legacy = legacyPrepare(fx.name, fx.bmp, modelSize, targetLong, lut, 0)
                val pixels = resizedPixels(fx.bmp, legacy.resizeW, legacy.resizeH)

                Log.i(
                    TAG,
                    "PREP ${fx.name} ${fx.w}x${fx.h} content=${legacy.resizeW}x${legacy.resizeH} " +
                        "pad=(${legacy.padX},${legacy.padY}) model=$modelSize " +
                        "kt_resize=${"%.1f".format(legacy.resizeMs)}ms kt_draw=${"%.1f".format(legacy.drawMs)}ms " +
                        "kt_getPixels=${"%.1f".format(legacy.getPixelsMs)}ms kt_lut=${"%.1f".format(legacy.loopMs)}ms " +
                        "kt_total=${"%.1f".format(legacy.prepMs)}ms"
                )

                // ── link 1: the preprocessed tensor, bit for bit ──
                val built = d.buildLetterboxedInput(pixels, legacy.resizeW, legacy.resizeH, modelSize, legacy.padX, legacy.padY)
                assertNotNull("buildLetterboxedInput returned null", built)
                val nDiff = countDiff(legacy.tensor, built!!)
                val maxDiff = maxAbsDiff(legacy.tensor, built)
                Log.i(TAG, "PARITY ${fx.name} L1_tensor maxAbsDiff=$maxDiff differingFloats=$nDiff/${built.size}")
                if (nDiff != 0) {
                    diagnoseLetterbox(legacy, pixels, built, modelSize)
                }
                assertEquals("${fx.name}: native letterbox must reproduce the Kotlin tensor bit for bit", 0, nDiff)

                // ── link 2: the probability map ──
                val probLegacy = d.infer(legacy.tensor, modelSize, modelSize)
                val probNative = d.inferLetterboxed(pixels, legacy.resizeW, legacy.resizeH, modelSize, legacy.padX, legacy.padY)
                assertNotNull(probLegacy)
                assertNotNull(probNative)
                assertEquals("${fx.name}: prob map size changed", probLegacy!!.size, probNative!!.size)
                val probDiff = maxAbsDiff(probLegacy, probNative)
                Log.i(TAG, "PARITY ${fx.name} L2_probMap maxAbsDiff=$probDiff size=${probLegacy.size}")
                assertEquals("${fx.name}: probability map must be identical", 0f, probDiff, 0f)

                // ── link 3: the net is deterministic, so link 2 ⇒ same boxes ──
                val again = d.infer(legacy.tensor, modelSize, modelSize)!!
                val selfDiff = maxAbsDiff(probLegacy, again)
                Log.i(TAG, "PARITY ${fx.name} L3_determinism maxAbsDiff=$selfDiff")
                assertEquals("${fx.name}: det net is not deterministic; L2 proves less than it looks", 0f, selfDiff, 0f)

                // ── timing: interleaved on the same warm engine ──
                //
                // "Preprocessing" = everything between having the source Bitmap
                // and the net having its input, and the net is deliberately NOT
                // in the measurement: at ~220 ms it is both the wrong order of
                // magnitude and the noisiest thing on the device (a concurrent
                // test run moves it by 100 ms+), so the two sides are priced
                // with their net-free equivalents.
                //   old = Skia resize + 128-gray fill + drawBitmap + getPixels(896²)
                //         + the 2.4 M float LUT loop + the direct-ByteBuffer copy
                //         + ncnn's fill_input copy  (fillInputOnlyMs)
                //   new = Skia resize + getPixels(content) + the native build
                //         (the det_build_input timer inside inferLetterboxed)
                val ktOld = ArrayList<Double>()
                val copyOld = ArrayList<Double>()
                val fillOld = ArrayList<Double>()
                val cropNew = ArrayList<Double>()
                val buildNew = ArrayList<Double>()
                val wallNew = ArrayList<Double>()
                val wallOld = ArrayList<Double>()
                val netOld = ArrayList<Double>()
                val netNew = ArrayList<Double>()
                val bb = java.nio.ByteBuffer.allocateDirect(3 * modelSize * modelSize * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                repeat(TIMING_RUNS) {
                    // ── old path ──
                    val l = legacyPrepare(fx.name, fx.bmp, modelSize, targetLong, lut, 0)
                    ktOld.add(l.prepMs)
                    val t0 = System.nanoTime()
                    bb.clear()
                    bb.asFloatBuffer().put(l.tensor)
                    copyOld.add((System.nanoTime() - t0) / 1e6)
                    bb.position(0)
                    fillOld.add(d.fillInputOnlyMs(l.tensor, modelSize, modelSize).toDouble())
                    val p = d.infer(l.tensor, modelSize, modelSize)
                    wallOld.add((System.nanoTime() - t0) / 1e6)
                    assertNotNull(p)
                    netOld.add(d.lastTimings()!![1].toDouble())

                    // ── new path ──
                    // `crop` timed here in Kotlin; `build` read out of the
                    // native timer, which brackets det_build_input only — so it
                    // is neither inflated by the ~220 ms net that follows nor
                    // by the 9.6 MB prob-map copy on the way back.
                    val t1 = System.nanoTime()
                    val px = resizedPixels(fx.bmp, l.resizeW, l.resizeH)
                    cropNew.add((System.nanoTime() - t1) / 1e6)
                    val p2 = d.inferLetterboxed(px, l.resizeW, l.resizeH, modelSize, l.padX, l.padY)
                    wallNew.add((System.nanoTime() - t1) / 1e6)
                    assertNotNull(p2)
                    val tt = d.lastTimings()!!
                    buildNew.add(tt[0].toDouble())
                    netNew.add(tt[1].toDouble())
                }
                fun med(v: List<Double>) = v.sorted()[v.size / 2]
                fun min(v: List<Double>) = v.min()
                val oldPrep = ktOld.mapIndexed { k, kt -> kt + copyOld[k] + fillOld[k] }
                val newPrep = cropNew.mapIndexed { k, c -> c + buildNew[k] }
                Log.i(
                    TAG,
                    "TIME ${fx.name} WARM OLD prep min=${"%.1f".format(min(oldPrep))} med=${"%.1f".format(med(oldPrep))}ms " +
                        "[kt=${"%.1f".format(min(ktOld))} (resize+draw+getPixels(896²)+lut2.4M), bufcopy=${"%.1f".format(min(copyOld))}, " +
                        "fill_input=${"%.1f".format(min(fillOld))}] " +
                        "NEW min=${"%.1f".format(min(newPrep))} med=${"%.1f".format(med(newPrep))}ms " +
                        "[crop=${"%.1f".format(min(cropNew))}, native_build=${"%.1f".format(min(buildNew))}] " +
                        "-> speedup=${"%.2f".format(min(oldPrep) / min(newPrep))}x (median ${"%.2f".format(med(oldPrep) / med(newPrep))}x) " +
                        "saved=${"%.1f".format(min(oldPrep) - min(newPrep))}ms"
                )
                Log.i(
                    TAG,
                    "TIME ${fx.name} warm_raw old=${oldPrep.map { "%.1f".format(it) }} " +
                        "new=${newPrep.map { "%.1f".format(it) }} " +
                        "kt=${ktOld.map { "%.1f".format(it) }} bufcopy=${copyOld.map { "%.1f".format(it) }} " +
                        "fill_input=${fillOld.map { "%.1f".format(it) }} " +
                        "crop=${cropNew.map { "%.1f".format(it) }} build=${buildNew.map { "%.1f".format(it) }} " +
                        "wall_infer_old=${wallOld.map { "%.1f".format(it) }} wall_infer_new=${wallNew.map { "%.1f".format(it) }} " +
                        "net_old=${netOld.map { "%.1f".format(it) }} net_new=${netNew.map { "%.1f".format(it) }}"
                )
                assertTrue(
                    "${fx.name}: native preprocessing incl. the resized crop (${med(newPrep)}ms) is not faster than the old path (${med(oldPrep)}ms)",
                    med(newPrep) < med(oldPrep)
                )
                coldRun(fx, det, modelSize, targetLong, lut)
                fx.bmp.recycle()
            }
        } finally {
            d.close()
        }
    }

    /**
     * First-call cost, on a thread with nothing pooled: no letterbox bitmap, no
     * pixel array, no 9.6 MB direct buffer, no native input Mat, cold page
     * tables. The app's first `detect()` after an engine load looks exactly like
     * this, and it is where a first-call figure like "27-68 ms of preprocessing"
     * comes from — the warm numbers above are the steady state. Both paths run
     * on the same fresh thread, old path first (so it cannot benefit from the
     * new path's warm-up, and vice versa: the native Mat is thread-local, so
     * the new path's first call on this thread also pays for its own).
     */
    private fun coldRun(fx: Fixture, det: DetNcnn, modelSize: Int, targetLong: Int, lut: FloatArray) {
        var ktCold = 0.0
        var copyCold = 0.0
        var wallOld = 0.0
        var cropCold = 0.0
        var newBuild = 0.0
        var wallNew = 0.0
        val t = Thread {
            val l = legacyPrepare("${fx.name}/cold", fx.bmp, modelSize, targetLong, lut, 3)
            ktCold = l.prepMs
            val t0 = System.nanoTime()
            val bb = java.nio.ByteBuffer.allocateDirect(3 * modelSize * modelSize * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            bb.asFloatBuffer().put(l.tensor)
            copyCold = (System.nanoTime() - t0) / 1e6
            val p = det.infer(l.tensor, modelSize, modelSize)
            wallOld = (System.nanoTime() - t0) / 1e6
            checkNotNull(p)

            val t1 = System.nanoTime()
            val px = resizedPixels(fx.bmp, l.resizeW, l.resizeH)
            val p2 = det.inferLetterboxed(px, l.resizeW, l.resizeH, modelSize, l.padX, l.padY)
            wallNew = (System.nanoTime() - t1) / 1e6
            checkNotNull(p2)
            val tt = det.lastTimings()!!
            newBuild = tt[0].toDouble()
            cropCold = wallNew - tt[0] - tt[1]
        }
        t.start()
        t.join()
        val oldTotal = ktCold + copyCold
        Log.i(
            TAG,
            "TIME ${fx.name} COLD OLD prep=${"%.1f".format(oldTotal)}ms " +
                "(kt=${"%.1f".format(ktCold)}: first-touch 3.2MB letterbox + 3.2MB pixel array + 9.6MB float array; " +
                "bufcopy=${"%.1f".format(copyCold)}: first-touch 9.6MB direct) " +
                "NEW prep=${"%.1f".format(newBuild + cropCold)}ms (crop=${"%.1f".format(cropCold)} + build=${"%.1f".format(newBuild)}) " +
                "wall_infer_old=${"%.1f".format(wallOld)}ms wall_infer_new=${"%.1f".format(wallNew)}ms"
        )
    }

    // ─────────── 2. end-to-end box parity, once runDetMask is wired ───────────
    //
    // The reflection lookup is what lets this test ship before the wiring: with
    // the flag absent the end-to-end half is skipped and the tensor/prob-map
    // halves above still stand on their own. See the reported runDetMask patch
    // for the flag's name and default.

    @Test
    fun boxesMatchThroughTheEngine() {
        val toggle = nativeLetterboxToggle()
        if (toggle == null) {
            Log.i(
                TAG,
                "SKIP end-to-end box parity: OcrEngine.useNativeLetterbox not present — " +
                    "runDetMask is not wired to DetNcnn.inferLetterboxed yet"
            )
            return
        }
        val (flag, target) = toggle
        for (fx in fixtures()) {
            val eng = engine()
            flag.setBoolean(target, false)
            val legacy = eng.detect(fx.bmp)
            flag.setBoolean(target, true)
            val nativeBoxes = eng.detect(fx.bmp)
            flag.setBoolean(target, false)
            Log.i(TAG, "E2E ${fx.name} legacyBoxes=${legacy.size} nativeBoxes=${nativeBoxes.size}")
            assertEquals("${fx.name}: box count changed", legacy.size, nativeBoxes.size)
            val ious = matchIous(legacy, nativeBoxes)
            val mean = if (ious.isEmpty()) 1.0 else ious.average()
            val min = if (ious.isEmpty()) 1.0 else ious.min()
            Log.i(
                TAG,
                "E2E ${fx.name} boxes=${legacy.size} meanIoU=${"%.4f".format(mean)} minIoU=${"%.4f".format(min)} " +
                    "exact=${ious.count { it >= 0.9999f }}/${ious.size}"
            )
            assertEquals("${fx.name}: mean box IoU", 1.0, mean, 1e-6)
            fx.bmp.recycle()
            eng.close()
        }
    }

    /**
     * The `useNativeLetterbox` flag the reported `runDetMask` patch adds, located
     * by reflection: with `@JvmStatic` it is a static on OcrEngine, without it
     * the field lives on the Companion. Absent ⇒ the patch is not wired and the
     * end-to-end half has nothing to compare.
     */
    private fun nativeLetterboxToggle(): Pair<java.lang.reflect.Field, Any>? {
        try {
            val f = OcrEngine::class.java.getDeclaredField("useNativeLetterbox").apply { isAccessible = true }
            return f to OcrEngine
        } catch (_: Exception) { }
        try {
            val c = OcrEngine.Companion
            val f = c.javaClass.getDeclaredField("useNativeLetterbox").apply { isAccessible = true }
            return f to c
        } catch (_: Exception) { }
        return null
    }

    private fun engine(): OcrEngine {
        val e = OcrEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("OcrEngine failed to load", e.isReady())
        return e
    }

    private fun iou(a: JpDictRect, b: JpDictRect): Float {
        val ix = max(0, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u > 0) inter.toFloat() / u else 0f
    }

    /** Greedy best-match by area, like tools/det_box_iou.py's match_iou. */
    private fun matchIous(a: List<JpDictRect>, b: List<JpDictRect>): List<Float> {
        val rem = b.toMutableList()
        val out = ArrayList<Float>()
        for (box in a.sortedByDescending { it.width() * it.height() }) {
            var best = -1f
            var at = -1
            for (i in rem.indices) {
                val v = iou(box, rem[i])
                if (v > best) { best = v; at = i }
            }
            if (at >= 0) { out.add(best); rem.removeAt(at) }
        }
        return out
    }

    // ─────────── 3. square size sweep, quality-gated ───────────
    //
    // The DB net is not quality-invariant to its input geometry (a rectangular
    // letterbox already loses lines and box quality, #51), so this only tries
    // squares. 896 is the shipped baseline; 768 and 640 are the cheaper
    // candidates. Runs the real engine, so what is reported is boxes and text,
    // not a prob-map proxy.

    @Test
    fun squareSizeSweep() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val results = mutableMapOf<Int, MutableMap<String, Snapshot>>()
        try {
            for (size in listOf(896, 768, 640)) {
                OcrEngine.DET_MODEL_SIZE = size
                val eng = OcrEngine(instr.targetContext)
                assertTrue("engine ready @$size", eng.isReady())
                val per = mutableMapOf<String, Snapshot>()
                for (fx in fixtures()) {
                    // warm, then min-of-3
                    eng.detect(fx.bmp)
                    var bestDet = Long.MAX_VALUE
                    var boxes = emptyList<JpDictRect>()
                    repeat(3) {
                        val t0 = System.nanoTime()
                        boxes = eng.detect(fx.bmp)
                        bestDet = minOf(bestDet, (System.nanoTime() - t0) / 1_000_000)
                    }
                    val texts = recognizeAll(eng, fx.bmp, boxes.take(SWEEP_REC_BOXES))
                    per[fx.name] = Snapshot(boxes, texts, bestDet)
                    Log.i(TAG, "SWEEP size=$size img=${fx.name} boxes=${boxes.size} detMs=$bestDet " +
                        "recBoxes=${minOf(boxes.size, SWEEP_REC_BOXES)} lines=${texts.size}")
                    for ((idx, t) in texts.entries.sortedBy { it.key }) {
                        Log.i(TAG, "SWEEP-LINE size=$size img=${fx.name} idx=$idx text=$t")
                    }
                }
                eng.close()
                results[size] = per
            }
        } finally {
            OcrEngine.DET_MODEL_SIZE = 896
        }
        val base = results[896]!!
        for (size in listOf(768, 640)) {
            val cur = results[size]!!
            for (name in base.keys) {
                val b = base[name]!!
                val c = cur[name]!!
                val ious = matchIous(b.boxes, c.boxes)
                val mean = if (ious.isEmpty()) 0.0 else ious.average()
                val min = if (ious.isEmpty()) 0.0 else ious.min()
                val common = b.texts.keys.intersect(c.texts.keys)
                val same = common.count { b.texts[it] == c.texts[it] }
                val lost = b.texts.keys.filter { it !in c.texts }
                val gained = c.texts.keys.filter { it !in b.texts }
                Log.i(
                    TAG,
                    "SWEEP-SUMMARY $size vs 896 img=$name boxes=${b.boxes.size}->${c.boxes.size} " +
                        "meanIoU=${"%.4f".format(mean)} minIoU=${"%.4f".format(min)} " +
                        "detMs=${b.detMs}->${c.detMs} lines=${b.texts.size}->${c.texts.size} " +
                        "textSame=$same/${common.size} lostLines=${lost.size} gainedLines=${gained.size}"
                )
                if (lost.isNotEmpty() || gained.isNotEmpty()) {
                    Log.i(TAG, "SWEEP-LINE-DIFF $size img=$name lostIdx=$lost gainedIdx=$gained")
                }
            }
        }
    }

    private class Snapshot(
        val boxes: List<JpDictRect>,
        val texts: Map<Int, String>,
        val detMs: Long,
    )

    /** Recognize every box, with the long-quiescence wait the real page uses. */
    private fun recognizeAll(eng: OcrEngine, bmp: Bitmap, boxes: List<JpDictRect>): Map<Int, String> {
        if (boxes.isEmpty()) return emptyMap()
        val collected = mutableListOf<Pair<Int, LineResult>>()
        runBlocking {
            eng.recognizeStreaming(bmp, boxes) { pairs -> synchronized(collected) { collected.addAll(pairs) } }
            var waited = 0
            var last = -1
            var still = 0
            while (waited < 120_000) {
                delay(200)
                waited += 200
                synchronized(collected) {
                    if (collected.size == last) still += 200 else { still = 0; last = collected.size }
                }
                if (last >= boxes.size) break
                if (waited >= 10_000 && still >= 4_000) break
            }
        }
        return synchronized(collected) { collected.associate { it.first to it.second.text } }
    }

    companion object {
        private const val TAG = "DetLetterbox"
        private const val TIMING_RUNS = 7
        /**
         * Boxes recognised per image in the sweep. The quest-style screenshot
         * carries ~65 lines and recognition is ~1-2 s each, so recognising all
         * of them at three sizes would be minutes of device time for a quality
         * gate that 32 lines answers; boxes themselves (the IoU) are always
         * compared over the full list.
         */
        private const val SWEEP_REC_BOXES = 32
    }
}
