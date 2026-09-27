package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * #102: the character-crop previews, on a real `Bitmap` — the part the host
 * geometry tests cannot reach, because the previews are pixels.
 *
 * The claim under test is the one the issue makes about a rotated (#53) Line:
 * both previews show the **unrotated** crop, the same one the recogniser saw,
 * padded in the upright frame — while an axis-aligned Line (`quad == null`)
 * keeps *exactly* today's crop, byte for byte.
 *
 * ## What is compared
 *
 * 1. [aRotatedLinesPreviewIsTheUprightCrop] — for a real rotated Line detected
 *    on a real fixture page, the preview equals a reference built independently
 *    in the test: warp the page through the frame's own `Matrix.setPolyToPoly`
 *    (the unrotate, written out again here rather than called), crop it at the
 *    rect [RotatedGeometry.mapSourceRectToLocal] gives for the char box, and
 *    compare pixel by pixel. It also asserts the pixels are *upright* — a
 *    glyph's ink is taller than it is wide in the frame's own axes, and the
 *    old axis-aligned grab of a tilted line's box is neither, so this is the
 *    check that would fail if the helper quietly fell back to the old path.
 * 2. [theTwoPreviewSitesAgreeOnTheSameCharacter] — the issue's third "done
 *    when": the alternatives preview and the manual-entry dialog, rendered
 *    through the shared helper for the same character, are the same upright
 *    crop up to their own padding (0.2 vs 0.5 of the glyph's height) and in the
 *    same orientation. It is the guard against one site drifting back to its own
 *    arithmetic.
 * 3. [anAxisAlignedLinesPreviewIsByteIdenticalToTheOldPath] — the regression
 *    pin. For the same page, an axis-aligned Line's preview is compared against
 *    the pre-#102 three lines transcribed verbatim (`Rect(box ± padding)` then
 *    `createBitmap`), byte for byte, over every character of every
 *    axis-aligned Line on the fixture.
 *
 * Fixtures: `benchmark/ebook_tategaki.png` and `benchmark/ruby_ebook.png` (the
 * two scanned book pages) plus `bookpage.png`. The rotated detection switch is
 * on by default ([OcrEngine.DEF_DET_ROTATED]), and the test fails loudly if no
 * fixture produced a rotated Line, so the "unrotate" claim can never be
 * satisfied vacuously by an all-axis-aligned page.
 *
 * Run:
 * `./gradlew :app:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.CharPreviewUprightTest`
 */
@RunWith(AndroidJUnit4::class)
class CharPreviewUprightTest {

    private val instr by lazy { InstrumentationRegistry.getInstrumentation() }

    private fun loadBitmap(path: String): Bitmap {
        for (assets in listOf(instr.context.assets, instr.targetContext.assets)) {
            try {
                assets.open(path).use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    if (bmp != null) return bmp
                }
            } catch (_: Exception) { }
        }
        error("fixture not found: $path")
    }

    private fun fixtures(): List<Pair<String, Bitmap>> = listOf(
        "benchmark/ebook_tategaki.png" to loadBitmap("benchmark/ebook_tategaki.png"),
        "benchmark/ruby_ebook.png" to loadBitmap("benchmark/ruby_ebook.png"),
        "bookpage.png" to loadBitmap("bookpage.png"),
    )

    /**
     * The unrotate, written out again rather than called through
     * `OcrEngine.warpRotatedCrop`, so the preview is compared against a
     * reference this test builds itself instead of against the thing under
     * test. Same `setPolyToPoly` seam, transcribed.
     */
    private fun referenceWarp(src: Bitmap, quad: JpDictQuad): Bitmap? {
        val w = quad.localWidth.roundToInt().coerceAtLeast(4)
        val h = quad.localHeight.roundToInt().coerceAtLeast(4)
        val matrix = android.graphics.Matrix()
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        if (!matrix.setPolyToPoly(quad.corners(), 0, dst, 0, 4)) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** The pre-#102 crop, transcribed verbatim from the three call sites. */
    private fun legacyPreview(src: Bitmap, box: JpDictRect, padRatio: Float): Bitmap? {
        val padding = (box.height() * padRatio).toInt()
        val r = Rect(
            (box.left - padding).coerceAtLeast(0),
            (box.top - padding).coerceAtLeast(0),
            (box.right + padding).coerceAtMost(src.width),
            (box.bottom + padding).coerceAtMost(src.height),
        )
        if (r.width() <= 0 || r.height() <= 0) return null
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }

    private fun pixels(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }

    /** (differing pixels, worst per-channel delta) between two same-size bitmaps. */
    private fun diff(a: Bitmap, b: Bitmap): Pair<Int, Int> {
        if (a.width != b.width || a.height != b.height) return -1 to -1
        val pa = pixels(a)
        val pb = pixels(b)
        var n = 0
        var worst = 0
        for (i in pa.indices) {
            if (pa[i] == pb[i]) continue
            n++
            for (shift in intArrayOf(16, 8, 0)) {
                worst = max(worst, abs((pa[i] shr shift and 0xFF) - (pb[i] shr shift and 0xFF)))
            }
        }
        return n to worst
    }

    private fun assertSamePixels(what: String, expected: Bitmap, actual: Bitmap) {
        assertEquals("$what size ${actual.width}x${actual.height} vs ${expected.width}x${expected.height}", expected.width, actual.width)
        assertEquals("$what size ${actual.width}x${actual.height} vs ${expected.width}x${expected.height}", expected.height, actual.height)
        val (n, worst) = diff(expected, actual)
        assertEquals("$what: $n/${expected.width * expected.height} pixels differ, worst channel delta $worst", 0, n)
    }

    /**
     * A frame's upright ink, measured on the preview: the tight bounding box of
     * the pixels that are not the (white) page. For a Japanese glyph that box
     * is about as tall as it is wide, and — the point here — it is *taller* than
     * wide along the frame's own cross axis, which for a near-vertical line is
     * the frame's x. A crop of a tilted line's *source* AABB has no such
     * property: its ink comes out a rotated parallelogram's worth, stretched
     * along the page axes instead.
     */
    private fun inkBox(bmp: Bitmap): IntArray {
        val px = pixels(bmp)
        var minX = bmp.width
        var minY = bmp.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val p = px[y * bmp.width + x]
                val lum = (p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114
                if (lum < 200 * 1000) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return intArrayOf(0, 0, 0, 0)
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    // ── 1. a rotated Line's preview is the upright crop ─────────────────────

    @Test
    fun aRotatedLinesPreviewIsTheUprightCrop() {
        val eng = OcrEngine(instr.targetContext)
        var rotatedSeen = 0
        var compared = 0
        try {
            for ((name, page) in fixtures()) {
                val lines = eng.detectLines(page)
                val rotated = lines.filter { it.isRotated }
                Log.i(TAG, "$name ${page.width}x${page.height} lines=${lines.size} rotated=${rotated.size}")
                for (box in rotated) {
                    val quad = box.quad ?: continue
                    // A char box for this line, exactly as the recogniser would
                    // place it: a source AABB of a local cell of the frame.
                    val fw = quad.localWidth.roundToInt().coerceAtLeast(4)
                    val fh = quad.localHeight.roundToInt().coerceAtLeast(4)
                    val local = JpDictRect(fw / 8, fh / 8, fw / 8 + max(4, fw / 4), fh / 8 + max(4, fh / 4))
                    val charBox = quad.mapLocalRect(local)
                    val line = LineResult(
                        text = "字",
                        charBoxes = listOf(charBox),
                        alternatives = emptyList(),
                        cropW = fw,
                        cropH = fh,
                        quad = quad,
                    )
                    val preview = CharPreviewCrop.crop(page, line, 0, CharPreviewCrop.ALTERNATIVES_PAD_RATIO)
                    assertNotNull("$name: no preview for a rotated line", preview)
                    preview!!

                    // The reference: warp, then crop at the mapped local rect,
                    // both rebuilt here rather than taken from the helper.
                    val frame = referenceWarp(page, quad)
                    assertNotNull("$name: reference warp failed", frame)
                    val rect = CharPreviewCrop.localRect(
                        quad, charBox, CharPreviewCrop.ALTERNATIVES_PAD_RATIO, frame!!.width, frame.height,
                    )
                    val expected = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
                    compared++
                    val (n, worst) = diff(expected, preview)
                    assertEquals(
                        "$name: preview != the upright crop ($n/${preview.width * preview.height} px, worst $worst)",
                        0, n,
                    )

                    // And the pixels are the recogniser's view, not the page's:
                    // the preview is a crop of the *unrotated* frame, so its ink
                    // runs along the frame's axes. A tilted line's source AABB
                    // would instead be over-wide along the page's x.
                    val ink = inkBox(preview)
                    if (ink[2] > 0) {
                        assertTrue(
                            "$name: the preview's ink is ${ink[2]}x${ink[3]} — not glyph-shaped in the frame's axes",
                            ink[2] <= 2 * max(1, ink[3]),
                        )
                        assertTrue("$name: the preview's ink is ${ink[2]}x${ink[3]}", ink[3] > 4)
                    }
                    Log.i(TAG, "$name frame=${fw}x$fh rect=$rect ink=${ink.contentToString()}")
                    expected.recycle()
                    frame.recycle()
                    preview.recycle()
                }
                rotatedSeen += rotated.size
            }
            assertTrue("no fixture produced a rotated Line — the claim was never exercised", rotatedSeen > 0)
            assertTrue("no rotated preview was compared", compared > 0)
            Log.i(TAG, "SUMMARY rotatedLines=$rotatedSeen previewsCompared=$compared")
        } finally {
            eng.close()
        }
    }

    // ── 2. the two sites agree on the same character ────────────────────────

    @Test
    fun theTwoPreviewSitesAgreeOnTheSameCharacter() {
        val eng = OcrEngine(instr.targetContext)
        try {
            for ((name, page) in fixtures()) {
                for (box in eng.detectLines(page).filter { it.isRotated }) {
                    val quad = box.quad ?: continue
                    val fw = quad.localWidth.roundToInt().coerceAtLeast(4)
                    val fh = quad.localHeight.roundToInt().coerceAtLeast(4)
                    val charBox = quad.mapLocalRect(
                        JpDictRect(fw / 8, fh / 8, fw / 8 + max(4, fw / 4), fh / 8 + max(4, fh / 4)),
                    )
                    val line = LineResult(
                        text = "字",
                        charBoxes = listOf(charBox),
                        alternatives = emptyList(),
                        cropW = fw,
                        cropH = fh,
                        quad = quad,
                    )
                    // The alternatives preview, and the manual-entry dialog's.
                    val alternatives = CharPreviewCrop.crop(page, line, 0, CharPreviewCrop.ALTERNATIVES_PAD_RATIO)!!
                    val manual = CharPreviewCrop.crop(page, line, 0, CharPreviewCrop.MANUAL_PAD_RATIO)!!
                    // Same glyph, same orientation, more room: the manual entry's
                    // padding is 0.5 of the upright box's height against the
                    // alternatives' 0.2, so it is strictly the larger of the two
                    // in the frame's own axes.
                    assertTrue("$name: manual ${manual.width}x${manual.height} vs alternatives ${alternatives.width}x${alternatives.height}", manual.width >= alternatives.width)
                    assertTrue("$name: manual ${manual.width}x${manual.height} vs alternatives ${alternatives.width}x${alternatives.height}", manual.height >= alternatives.height)
                    assertTrue("$name: the sites' crops differ in size", manual.width * manual.height > alternatives.width * alternatives.height)
                    // And the wider one is the same picture, centred: the two
                    // crops are the same upright frame, so their ink boxes have
                    // the same aspect to within the resampling a different crop
                    // size causes.
                    val a = inkBox(alternatives)
                    val m = inkBox(manual)
                    if (a[2] > 0 && m[2] > 0) {
                        val ar = a[2].toFloat() / a[3]
                        val mr = m[2].toFloat() / m[3]
                        assertTrue("$name: ink aspect $ar vs $mr — the two sites are not the same picture", abs(ar - mr) <= 0.5f * max(ar, mr))
                    }
                    alternatives.recycle()
                    manual.recycle()
                }
            }
        } finally {
            eng.close()
        }
    }

    // ── 3. an axis-aligned Line is untouched ────────────────────────────────

    @Test
    fun anAxisAlignedLinesPreviewIsByteIdenticalToTheOldPath() {
        val eng = OcrEngine(instr.targetContext)
        var compared = 0
        try {
            for ((name, page) in fixtures()) {
                for (box in eng.detectLines(page).filter { !it.isRotated }) {
                    val rect = box.rect
                    val cells = listOf(
                        JpDictRect(rect.left, rect.top, min(rect.right, rect.left + 40), min(rect.bottom, rect.top + 40)),
                        JpDictRect(rect.left + 1, rect.top + 1, min(rect.right, rect.left + 21), min(rect.bottom, rect.top + 31)),
                        rect,
                    )
                    for (cell in cells) {
                        val line = LineResult(
                            text = "字",
                            charBoxes = listOf(cell),
                            alternatives = emptyList(),
                            cropW = rect.width(),
                            cropH = rect.height(),
                            quad = null,
                        )
                        for ((ratio, where) in listOf(
                            CharPreviewCrop.ALTERNATIVES_PAD_RATIO to "alternatives",
                            CharPreviewCrop.MANUAL_PAD_RATIO to "manual",
                        )) {
                            val actual = CharPreviewCrop.crop(page, line, 0, ratio)
                            val expected = legacyPreview(page, cell, ratio)
                            if (expected == null) {
                                assertEquals("$name $where: the old path cropped nothing, so neither does this", null, actual)
                                continue
                            }
                            assertNotNull("$name $where cell=$cell", actual)
                            assertSamePixels("$name $where cell=$cell", expected, actual!!)
                            compared++
                            // `createBitmap` hands back the *source* when the
                            // rect is the whole page and the source is immutable,
                            // so recycling blindly would recycle the fixture.
                            if (actual !== page) actual.recycle()
                            if (expected !== page) expected.recycle()
                        }
                    }
                }
                Log.i(TAG, "$name axisAligned previews compared (cumulative) = $compared")
            }
            assertTrue("no axis-aligned preview was compared", compared > 0)
        } finally {
            eng.close()
        }
    }

    // ── 4. a synthetic frame, so the claim does not rest on the detector ────

    /**
     * The same claim on a page this test *builds*: a glyph drawn upright into
     * a line's own frame and then the frame's inverse drawn into the page, so
     * the page holds a tilted glyph whose upright content is known exactly. The
     * preview must come back as that upright content, in its own axes — a
     * property no detector's opinion is involved in.
     */
    @Test
    fun aSyntheticTiltedGlyphComesBackUpright() {
        for (tilt in listOf(20f, 35f, 60f)) {
            val w = 200
            val h = 60
            // An upright marker: a tall bar near the frame's left, and a short
            // one near its right. Tall-vs-short is what makes "upright" a
            // checkable claim rather than a vibe.
            val upright = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(upright).drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            val canvas = Canvas(upright)
            canvas.drawRect(20f, 5f, 34f, 55f, paint)   // tall bar
            canvas.drawRect(w - 40f, 20f, w - 26f, 40f, paint) // short bar

            // The frame, and the page that holds this frame's content tilted.
            val quad = RotatedGeometry.fitQuad(frameCorners(400f, 400f, w.toFloat(), h.toFloat(), tilt), 4)!!
            val page = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
            Canvas(page).drawColor(Color.WHITE)
            val fwd = android.graphics.Matrix()
            val dst = floatArrayOf(
                quad.c0.x, quad.c0.y, quad.c1.x, quad.c1.y, quad.c2.x, quad.c2.y, quad.c3.x, quad.c3.y,
            )
            assertTrue("the forward frame map must be solvable", fwd.setPolyToPoly(
                floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()),
                0, dst, 0, 4,
            ))
            Canvas(page).drawBitmap(upright, fwd, Paint(Paint.FILTER_BITMAP_FLAG))

            // A char box covering the tall bar's cell, as the recogniser would
            // place it: a source AABB of the local cell (8,0)-(40,60).
            val local = JpDictRect(8, 0, 40, 60)
            val charBox = quad.mapLocalRect(local)
            val line = LineResult(
                text = "字",
                charBoxes = listOf(charBox),
                alternatives = emptyList(),
                cropW = w,
                cropH = h,
                quad = quad,
            )
            val preview = CharPreviewCrop.crop(page, line, 0, CharPreviewCrop.ALTERNATIVES_PAD_RATIO)
            assertNotNull("tilt=$tilt: no preview", preview)
            val frame = referenceWarp(page, quad)
            assertNotNull("tilt=$tilt: reference warp failed", frame)
            val rect = CharPreviewCrop.localRect(quad, charBox, CharPreviewCrop.ALTERNATIVES_PAD_RATIO, frame!!.width, frame.height)
            val expected = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
            assertSamePixels("tilt=$tilt", expected, preview!!)

            // The tall bar is tall in the preview: in the frame's own axes it
            // is ~50x14, so the preview's ink is taller than wide. Had the
            // helper cropped the source AABB (the pre-#102 path), the same ink
            // would arrive sheared into the page's axes — wider than tall at
            // these tilts.
            val ink = inkBox(preview)
            Log.i(TAG, "synthetic tilt=$tilt rect=$rect ink=${ink.contentToString()}")
            assertTrue("tilt=$tilt: the preview's ink is ${ink[2]}x${ink[3]} — not upright", ink[2] < ink[3])
            val old = legacyPreview(page, charBox, CharPreviewCrop.ALTERNATIVES_PAD_RATIO)
            if (old != null) {
                Log.i(TAG, "tilt=$tilt old-path ink=${inkBox(old).contentToString()} (wider than tall: ${inkBox(old)[2] > inkBox(old)[3]})")
                old.recycle()
            }
            expected.recycle()
            frame.recycle()
            preview.recycle()
            upright.recycle()
            page.recycle()
        }
    }

    /** The four outer pixel corners of a `w × h` rect at (cx,cy), turned
     *  [tiltDeg] — the detector's boundary-walk corner order. */
    private fun frameCorners(cx: Float, cy: Float, w: Float, h: Float, tiltDeg: Float): FloatArray {
        val a = Math.toRadians(tiltDeg.toDouble())
        val ux = kotlin.math.cos(a).toFloat()
        val uy = kotlin.math.sin(a).toFloat()
        val vx = -uy
        val vy = ux
        val hw = w / 2f
        val hh = h / 2f
        return floatArrayOf(
            cx - hw * ux - hh * vx, cy - hw * uy - hh * vy,
            cx + hw * ux - hh * vx, cy + hw * uy - hh * vy,
            cx + hw * ux + hh * vx, cy + hw * uy + hh * vy,
            cx - hw * ux + hh * vx, cy - hw * uy + hh * vy,
        )
    }

    companion object {
        private const val TAG = "CharPreviewUpright"
    }
}
