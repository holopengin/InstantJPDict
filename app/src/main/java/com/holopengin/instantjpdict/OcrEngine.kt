package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.JapaneseUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt
import uniffi.nav_graph_core.BoundingBox
import uniffi.nav_graph_core.PageOcrOptionsRecord
import uniffi.nav_graph_core.PageQuad
import uniffi.nav_graph_core.PageRecognizer
import uniffi.nav_graph_core.japaneseEstimateEm
import uniffi.nav_graph_core.japaneseIsHalfWidth
import uniffi.nav_graph_core.ocrEngineComputeCharBoxes
import uniffi.nav_graph_core.ocrEngineReDecode
import uniffi.nav_graph_core.ocrEngineSortOrder

/**
 * PP-OCRv6 page engine facade.
 *
 * All inference lives in `jpdict_core::page_ocr` now: one [recognizePage] call
 * detects, recognizes, kana-corrects and places every line. This class owns the
 * native engine lifecycle, the per-run preferences, and the model-free
 * re-decode/geometry helpers the overlay still calls.
 *
 * The old Kotlin detect/recognize/stitch backend and the JNI `libncnn_jni.so`
 * are gone. `CharPlacement` is a thin UniFFI facade over the Rust CAP entry,
 * kept because the re-decode path recomputes boxes from cached columns without
 * crop pixels.
 */
class OcrEngine(
    private val context: Context,
    /**
     * #100: which furigana switch this engine obeys. Every instance serves one
     * entry point — the accessibility service's engine only runs overlay
     * captures (screen), and [ShareImageActivity] builds its own, passing the
     * camera key only for the viewfinder handoff.
     */
    private val furiganaPref: String = PREF_DET_FURIGANA_SCREEN,
) {
    @Volatile private var pageRecognizer: PageRecognizer? = null
    @Volatile private var pageRecognizerFailed = false

    /** Last page-call split, for the benchmark and the diagnostic log. */
    data class PageTimings(
        val prepareMs: Long,
        val detectMs: Long,
        val recognizeMs: Long,
        val nativeMs: Long,
        val mapMs: Long,
        val totalMs: Long,
    )

    @Volatile var lastPageTimings: PageTimings? = null
        private set

    companion object {
        private const val TAG = "PPOCREngine"

        const val PREFS_NAME = "instant_jp_dict_prefs"
        const val PREF_DET_THRESH = "ppocr_det_thresh"
        const val PREF_DET_UNCLIP = "ppocr_det_unclip_ratio"
        const val PREF_X_OVERLAP = "x_overlap_thresh"
        const val PREF_REC_SQUISH = "rec_squish_factor"
        const val PREF_DET_ROTATED = "ppocr_det_rotated"
        const val DEF_DET_ROTATED = true
        const val PREF_DET_FURIGANA_SCREEN = "ppocr_det_furigana"
        const val PREF_DET_FURIGANA_CAMERA = "ppocr_det_furigana_camera"
        const val DEF_DET_FURIGANA = false
        const val PREF_BOX_PLACEMENT_CAP = "box_placement_cap"
        const val DEF_BOX_PLACEMENT_CAP = true

        const val DEF_DET_LONG_SIDE = 960
        var DET_MODEL_SIZE = 896
        var BOX_LAYOUT_MODE = 1
        const val BOX_SNAP = 1
        var BOX_UNIFORM_SIZE = true
        var RUBY_TRIM_VERTICAL = true

        const val DEF_DET_THRESH = 0.25f
        const val DEF_DET_UNCLIP = 0.70f
        const val DEF_X_OVERLAP = 0.40f
        const val DEF_REC_SQUISH = 0.5f

        var REC_BATCH_SIZE = 4
        var REC_FANOUT = 4

        const val GAP_CHAR = '\u25CC'

        fun getDetThresh(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(PREF_DET_THRESH, DEF_DET_THRESH)

        fun getDetLongSide(ctx: Context): Int = DEF_DET_LONG_SIDE

        fun isDetRotated(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_DET_ROTATED, DEF_DET_ROTATED)

        fun setDetRotated(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_DET_ROTATED, enabled).apply()
        }

        fun isDetFurigana(ctx: Context, key: String = PREF_DET_FURIGANA_SCREEN): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, DEF_DET_FURIGANA)

        fun setDetFurigana(ctx: Context, enabled: Boolean, key: String = PREF_DET_FURIGANA_SCREEN) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(key, enabled).apply()
        }

        private val VERTICAL_MIN_ASPECT = RotatedGeometry.VERTICAL_MIN_ASPECT

        internal fun isHalfWidth(ch: Char): Boolean = japaneseIsHalfWidth(ch.code)

        internal fun estimateEm(text: String, centers: List<Float>): Float =
            japaneseEstimateEm(text, centers)

        internal fun isVerticalBox(box: JpDictRect): Boolean =
            box.height().toFloat() >= box.width() * VERTICAL_MIN_ASPECT

        internal fun sortDetectedBoxes(boxes: List<JpDictRect>): List<JpDictRect> {
            if (boxes.size < 2) return boxes
            val order = ocrEngineSortOrder(boxes.map {
                BoundingBox(x = it.left, y = it.top, w = it.width(), h = it.height())
            })
            return order.map { boxes[it.toInt()] }
        }

        private fun localCropW(quad: JpDictQuad): Int =
            quad.localWidth.roundToInt().coerceAtLeast(4)

        private fun localCropH(quad: JpDictQuad): Int =
            quad.localHeight.roundToInt().coerceAtLeast(4)

        /** The single upright-crop warp the character preview and the recogniser
         *  both use, so "what the net saw" cannot drift between them. */
        internal fun warpRotatedCrop(src: Bitmap, quad: JpDictQuad): Bitmap? {
            val w = localCropW(quad)
            val h = localCropH(quad)
            val matrix = android.graphics.Matrix()
            val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
            if (!matrix.setPolyToPoly(quad.corners(), 0, dst, 0, 4)) return null
            return try {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(out)
                canvas.drawBitmap(
                    src, matrix,
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                out
            } catch (e: Exception) {
                Log.e(TAG, "warpRotatedCrop failed", e)
                null
            }
        }
    }

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val capPlacement: Boolean
        get() = prefs.getBoolean(PREF_BOX_PLACEMENT_CAP, DEF_BOX_PLACEMENT_CAP)

    private val detUnclip: Float
        get() = prefs.getFloat(PREF_DET_UNCLIP, DEF_DET_UNCLIP)

    val recSquish: Float
        get() = prefs.getFloat(PREF_REC_SQUISH, DEF_REC_SQUISH).coerceIn(0.2f, 1.0f)

    fun isReady(): Boolean = pageRecognizer != null || !pageRecognizerFailed

    /**
     * One page in, one page out: detection, recognition, kana correction and
     * CAP placement all run in `jpdict_core::page_ocr`. Pixels cross as RGBA
     * bytes; per-run switches cross in [PageOcrOptionsRecord].
     */
    suspend fun recognizePage(bitmap: Bitmap): List<Pair<LineBox, LineResult>> {
        val totalStart = System.nanoTime()
        val recognizer = pageRecognizer() ?: return emptyList()
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()
        // ARGB_8888's raw buffer is RGBA byte order on the Pixel 7a (pinned by
        // PageOcrDeviceTest.bitmapCopyPixelsToBufferIsRgbaOnThisDevice), so the
        // bytes go straight to Rust without a per-pixel Kotlin swizzle.
        check(bitmap.byteCount == w * h * 4) {
            "unexpected ARGB_8888 byte count ${bitmap.byteCount} for ${w}x$h"
        }
        val buffer = java.nio.ByteBuffer.allocate(w * h * 4)
        bitmap.copyPixelsToBuffer(buffer)
        val rgba = buffer.array()
        val prepareMs = (System.nanoTime() - totalStart) / 1_000_000
        kotlin.coroutines.coroutineContext.ensureActive()
        val cancelHandle = kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) recognizer.cancel()
        }
        val nativeStart = System.nanoTime()
        val page = try {
            recognizer.recognize(rgba, w.toUInt(), h.toUInt(), pageOptions())
        } finally {
            cancelHandle?.dispose()
        }
        val nativeMs = (System.nanoTime() - nativeStart) / 1_000_000
        val mapStart = System.nanoTime()
        val mapped = page.lines.map { rec ->
            val rect = JpDictRect(
                rec.bbox.x,
                rec.bbox.y,
                rec.bbox.x + rec.bbox.w,
                rec.bbox.y + rec.bbox.h,
            )
            val quad = rec.quad?.toJpDictQuad()
            val alternatives = rec.alternatives.map { alts ->
                alts.map { Char(it.ch) to it.score }.toMutableList()
            }
            val rawAlternatives = TimestepTopK.of(rec.rawChars, rec.rawScores, rec.rawRowStarts)
            val line = LineResult(
                text = rec.text,
                charBoxes = rec.charBoxes.map {
                    JpDictRect(it.x, it.y, it.x + it.w, it.y + it.h)
                },
                alternatives = alternatives,
                isVertical = rec.isVertical,
                rawAlternatives = rawAlternatives,
                seqLenTotal = rec.seqLenTotal,
                cropW = rec.cropW,
                cropH = rec.cropH,
                cropX = rec.cropX,
                cropY = rec.cropY,
                charCols = rec.charCols.toFloatArray(),
                quad = quad,
            )
            LineBox(rect, quad) to line
        }
        val mapMs = (System.nanoTime() - mapStart) / 1_000_000
        val totalMs = (System.nanoTime() - totalStart) / 1_000_000
        val timings = PageTimings(
            prepareMs = prepareMs,
            detectMs = page.detectMs.toLong(),
            recognizeMs = page.recognizeMs.toLong(),
            nativeMs = nativeMs,
            mapMs = mapMs,
            totalMs = totalMs,
        )
        lastPageTimings = timings
        InferLog.add(
            "page prepare=${timings.prepareMs} detect=${timings.detectMs} " +
                "rec=${timings.recognizeMs} native=${timings.nativeMs} " +
                "map=${timings.mapMs} total=${timings.totalMs}",
        )
        return mapped
    }

    private fun pageOptions(): PageOcrOptionsRecord = PageOcrOptionsRecord(
        detModelSize = DET_MODEL_SIZE.toUInt(),
        detLongSide = getDetLongSide(context).toUInt(),
        detThresh = getDetThresh(context),
        detUnclip = detUnclip,
        detFurigana = isDetFurigana(context, furiganaPref),
        detRotated = isDetRotated(context),
        cap = capPlacement,
        snap = BOX_LAYOUT_MODE == BOX_SNAP,
        uniform = BOX_UNIFORM_SIZE,
        rubyTrimVertical = RUBY_TRIM_VERTICAL && !isDetRotated(context),
        recSquish = recSquish,
        recWorkers = REC_FANOUT.toUInt(),
        kanaSizeCorrection = true,
        saveSamples = false,
    )

    private fun PageQuad.toJpDictQuad(): JpDictQuad = JpDictQuad(
        QuadPoint(c0.x, c0.y),
        QuadPoint(c1.x, c1.y),
        QuadPoint(c2.x, c2.y),
        QuadPoint(c3.x, c3.y),
    )

    private fun pageRecognizer(): PageRecognizer? {
        pageRecognizer?.let { return it }
        if (pageRecognizerFailed) return null
        synchronized(this) {
            pageRecognizer?.let { return it }
            if (pageRecognizerFailed) return null
            return try {
                val dir = materialisePageOcrModelDir(context)
                    ?: error("page OCR models could not be materialised")
                PageRecognizer(dir.absolutePath, REC_BATCH_SIZE.toUInt()).also {
                    pageRecognizer = it
                    Log.d(TAG, "PageRecognizer loaded: $it")
                }
            } catch (e: Throwable) {
                pageRecognizerFailed = true
                Log.e(TAG, "PageRecognizer failed", e)
                null
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Model-free paths the overlay still calls
    // ═════════════════════════════════════════════════════════════════════════

    private fun measureInkHalfWidths(text: String, n: Int, renderTextSize: Float): FloatArray {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = renderTextSize.coerceAtLeast(1f)
            textLocale = java.util.Locale.ROOT
        }
        val bounds = Rect()
        return FloatArray(n) { i ->
            val s = text.getOrNull(i)?.toString() ?: ""
            if (s.isEmpty()) 0f
            else {
                paint.getTextBounds(s, 0, s.length, bounds)
                bounds.width() / 2f
            }
        }
    }

    /**
     * Recompute one line's char boxes from cached columns, used by the slider
     * re-decode. No model and no image are involved; CAP runs the Rust
     * template when pixels are absent, exactly as the page call does.
     */
    fun computeCharBoxes(
        text: String,
        charCols: FloatArray,
        seqLenTotal: Int,
        cropX: Int, cropY: Int,
        cropW: Int, cropH: Int,
        isVertical: Boolean,
        pixels: IntArray? = null,
        pixW: Int = 0,
        pixH: Int = 0,
        steps: TimestepTopK? = null,
    ): List<JpDictRect> {
        val n = charCols.size
        if (n == 0 || seqLenTotal <= 0) return emptyList()

        if (capPlacement) {
            val placed = CharPlacement.placeCells(
                text = text,
                charCols = charCols,
                seqLenTotal = seqLenTotal,
                cropW = cropW,
                cropH = cropH,
                isVertical = isVertical,
                pixels = pixels,
                cells = steps?.capCellRows(),
            )
            if (placed.isNotEmpty()) {
                return placed.map { box ->
                    JpDictRect(
                        (cropX + box.left).roundToInt(),
                        (cropY + box.top).roundToInt(),
                        (cropX + box.right).roundToInt(),
                        (cropY + box.bottom).roundToInt(),
                    )
                }
            }
        }

        val inkHalf = if (!isVertical) {
            measureInkHalfWidths(text, n, cropH.toFloat() * 0.90f)
        } else FloatArray(0)
        return ocrEngineComputeCharBoxes(
            text = text,
            charCols = charCols.toList(),
            seqLenTotal = seqLenTotal.toLong(),
            cropX = cropX,
            cropY = cropY,
            cropW = cropW,
            cropH = cropH,
            isVertical = isVertical,
            pixels = pixels?.toList(),
            pixW = pixW,
            pixH = pixH,
            snap = BOX_LAYOUT_MODE == BOX_SNAP,
            uniform = BOX_UNIFORM_SIZE,
            inkHalfWidths = inkHalf.toList(),
        ).map { JpDictRect(it.x, it.y, it.x + it.w, it.y + it.h) }
    }

    /** Re-decode a line from its cached raw alternatives, without inference. */
    fun reDecodeLineResult(oldLine: LineResult): LineResult {
        val topK = oldLine.rawTopK
        if (topK.isEmpty()) return oldLine

        val re = ocrEngineReDecode(topK.gapCellRows(), oldLine.isVertical) ?: return oldLine
        val vertText = re.text
        val newAlts = re.alternatives.map { alts ->
            alts.map { g -> Char(g.ch) to g.score }.toMutableList()
        }
        val charCols = re.charCols.toFloatArray()

        val newCharBoxes = if (oldLine.cropW > 0 && oldLine.cropH > 0) {
            val steps = if (oldLine.isVertical) {
                TimestepTopK.of(JapaneseUtil.verticalPunctuationAlternatives(oldLine.rawAlternatives))
            } else {
                topK
            }
            val local = computeCharBoxes(
                vertText, charCols, oldLine.seqLenTotal,
                oldLine.cropX, oldLine.cropY, oldLine.cropW, oldLine.cropH,
                oldLine.isVertical,
                steps = steps,
            )
            if (oldLine.quad != null) local.map { oldLine.quad.mapLocalRect(it) } else local
        } else oldLine.charBoxes

        return LineResult(
            text = vertText,
            charBoxes = newCharBoxes,
            alternatives = newAlts,
            isVertical = oldLine.isVertical,
            overrides = oldLine.overrides,
            rawAlternatives = oldLine.rawAlternatives,
            seqLenTotal = oldLine.seqLenTotal,
            cropW = oldLine.cropW,
            cropH = oldLine.cropH,
            cropX = oldLine.cropX,
            cropY = oldLine.cropY,
            charCols = charCols,
            quad = oldLine.quad,
        )
    }

    fun close() {
        try { pageRecognizer?.cancel() } catch (_: Exception) {}
        try { pageRecognizer?.destroy() } catch (_: Exception) {}
        pageRecognizer = null
    }
}
