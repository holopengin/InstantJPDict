package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.JapaneseUtil
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlin.coroutines.coroutineContext
import kotlin.jvm.JvmName
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import uniffi.nav_graph_core.BoundingBox
import uniffi.nav_graph_core.CtcDecode as RustCtcDecode
import uniffi.nav_graph_core.CompactCtcDecodeResult
import uniffi.nav_graph_core.GapCell
import uniffi.nav_graph_core.gutterTrimFindRubyGutterCut
import uniffi.nav_graph_core.japaneseEstimateEm
import uniffi.nav_graph_core.japaneseIsHalfWidth
import uniffi.nav_graph_core.mergeBoxesMerge
import uniffi.nav_graph_core.mergeBoxesShouldMerge
import uniffi.nav_graph_core.ocrEngineComputeCharBoxes
import uniffi.nav_graph_core.ocrEngineReDecode
import uniffi.nav_graph_core.ocrEngineSortOrder

/** PP-OCRv6 [OcrEngine] — ncnn detect (DB, DET_MODEL_SIZE²) + dynamic-width rec (48×W) + CTC.
 *
 * Seams (single file by decision, #29): Detect §§ (detect + unclip + furigana +
 * merge/split/ruby-trim/sort) → Rec batch/stream §§ (recognizePpocrBatch + recognizeStreaming,
 * completion-order select emit) → Stitch §§ (long-line horiz/vert chunk + anchor
 * stitch) → CTC + CharBox §§ (ctcDecode, computeCharBoxes, vertical glyphs) →
 * Tunables + init §§ (SharedPreferences, vocab). Stitch chunks run unsquished
 * full-res; only the single-pass batch path applies [recSquish] (#24).
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
    // Detection model (DB, #51) + vocabulary + single dynamic-width rec model (#23).
    private var detNcnn: DetNcnn? = null
    private var ppocrVocab: List<String> = emptyList()
    private var classRemap: IntArray = IntArray(0) // pruned-out -> orig class id (#39)
    /** Rust-owned immutable decode tables; one UniFFI call decodes each line. */
    private var ctcDecoder: RustCtcDecode? = null
    /** Pruned CTC-head width, derived from rec_remap.txt when it loads (#44). */
    private var recNumOutputs = 0
    private var recDynNcnn: RecNcnn? = null

    // One line awaiting recognition; results stay keyed by idx.
    private data class Job(val idx: Int, val box: LineBox, val isVertical: Boolean)

    companion object {
        private const val TAG = "PPOCREngine"

        // SharedPreferences keys for tunables — #14 (ncnn-only; no backend switch)
        const val PREFS_NAME = "instant_jp_dict_prefs"
        const val PREF_DET_THRESH = "ppocr_det_thresh"
        const val PREF_DET_UNCLIP = "ppocr_det_unclip_ratio"
        const val PREF_X_OVERLAP = "x_overlap_thresh"
        const val PREF_REC_SQUISH = "rec_squish_factor"
        /** #53: rotated-rect detection (minAreaRect fit + unrotate before rec).
         *  ON by default; off = the axis-aligned path, bit-for-bit the pre-#53
         *  pipeline. Reachable as the debug screen's "Detect rotated lines". */
        const val PREF_DET_ROTATED = "ppocr_det_rotated"
        const val DEF_DET_ROTATED = true
        /** #28/#100: the furigana (ruby) rule, OFF by default — it is flaky on
         *  camera photos, and a wrong drop costs a whole line. Two switches: the
         *  screen-capture / image-share entry points read
         *  [PREF_DET_FURIGANA_SCREEN], the viewfinder handoff reads
         *  [PREF_DET_FURIGANA_CAMERA]. Reachable in the debug screen's Overlay
         *  behaviour card. */
        const val PREF_DET_FURIGANA_SCREEN = "ppocr_det_furigana"
        const val PREF_DET_FURIGANA_CAMERA = "ppocr_det_furigana_camera"
        const val DEF_DET_FURIGANA = false
        /** CAP char placement (research/char-placement): the debug screen's
         *  "Char placement (CAP)" switch. ON for the device A/B; off restores
         *  the shipped chain bit-for-bit. The algorithm falls back inside
         *  itself when the crop pixels or the top-K steps are missing, so the
         *  re-decode path still gets the template fit. */
        const val PREF_BOX_PLACEMENT_CAP = "box_placement_cap"
        const val DEF_BOX_PLACEMENT_CAP = true

        // Defaults (previous hard constants)
        const val DEF_DET_LONG_SIDE = 960
        /** Det net input side (#51): 896 holds box counts within ±4% on all 8
         * bench images (host study, app-exact postprocess port) for ~13%
         * less compute than 960; 832+ breaks dense screenshots. Test-flippable
         * to 960 for A/B walls. */
        var DET_MODEL_SIZE = 896
        /** Char-box layout (#49): 0=legacy uniform columns, 1=legacy +
         * image-evidence snapping (idea 4, default — quest failing line mean
         * 5.7->4.0px, max 25->15px; synth worst-case ~= legacy). Null pixels
         * (re-decode path) always take legacy. Test-flippable. */
        var BOX_LAYOUT_MODE = 1
        const val BOX_SNAP = 1
        /** Uniform em sizing (#49, default): JP + fullwidth latin share one
         * em box, em = median center-to-center distance; halfwidth = 0.5em.
         * Widths-only pass over resolved centers (positions bit-identical to
         * legacy path); punct expand runs after as before. Test-flippable. */
        var BOX_UNIFORM_SIZE = true
        /** Ruby-gutter trim for vertical lines (#48, default): detector boxes
         * that swallowed the furigana strip (~2x normal column width) are cut
         * back to the main column geometrically — crops, char boxes and overlay
         * all derive from the trimmed box, so the ruby width never re-enters.
         * Test-flippable for A/B walls. */
        var RUBY_TRIM_VERTICAL = true
        /** Halfwidth test shared with the overlay renderer (#49): ASCII +
         * halfwidth katakana get 0.5em boxes and line-height-driven sizing. */
        internal fun isHalfWidth(ch: Char): Boolean {
            return japaneseIsHalfWidth(ch.code)
        }

        /** Consistent em from width-normalized pitches (#49): raw median
         * center distance collapses in mixed JP/ASCII lines (ASCII sits
         * ~0.5em apart, so an ASCII majority drags em to ~0.5x and kanji
         * boxes shrink to half width, rendering tiny). Each gap is divided
         * by the mean advance of its two chars in em units (0.5 halfwidth,
         * 1.0 fullwidth), so every normalized gap estimates one full em
         * regardless of script mix; em is the median of those. Pure
         * function for JVM unit tests. Returns 0f when unestimable. */
        internal fun estimateEm(text: String, centers: List<Float>): Float {
            return japaneseEstimateEm(text, centers)
        }
        const val DEF_DET_THRESH = 0.25f
        const val DEF_DET_UNCLIP = 0.70f
        const val DEF_X_OVERLAP = 0.40f
        const val DEF_REC_SQUISH = 0.5f

        // Helpers for static access (no engine instance needed)
        fun getDetThresh(ctx: Context): Float =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(PREF_DET_THRESH, DEF_DET_THRESH)
        fun getDetLongSide(ctx: Context): Int = DEF_DET_LONG_SIDE
        /** #53: rotated-rect detection, on by default. Read per detection run, like
         *  the tunables. */
        fun isDetRotated(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_DET_ROTATED, DEF_DET_ROTATED)
        fun setDetRotated(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_DET_ROTATED, enabled).apply()
        }
        /** #28/#100: the furigana rule, off by default. Read per detection run;
         *  [key] selects the entry point's switch. */
        fun isDetFurigana(ctx: Context, key: String = PREF_DET_FURIGANA_SCREEN): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, DEF_DET_FURIGANA)
        fun setDetFurigana(ctx: Context, enabled: Boolean, key: String = PREF_DET_FURIGANA_SCREEN) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(key, enabled).apply()
        }

        /** #28 orientation rule, shared with the rotated fit so both paths agree.
         *  The furigana geometry rules themselves live in [FuriganaRule]. */
        private val VERTICAL_MIN_ASPECT = RotatedGeometry.VERTICAL_MIN_ASPECT

        /** Shared orientation rule (#28): near-square boxes count as horizontal
         *  so lone upright characters never enter the model sideways. Pure and
         *  `internal` so the shared conformance corpus (pipeline-sharing/01)
         *  runs the real sort from host tests; the former instance methods now
         *  resolve here with identical bodies. */
        internal fun isVerticalBox(box: JpDictRect): Boolean =
            box.height().toFloat() >= box.width() * VERTICAL_MIN_ASPECT

        /** Pure reading-order sort: horizontals top-to-bottom/left-to-right,
         *  then verticals right-edge-to-left/top-to-bottom (pipeline-sharing/01
         *  conformance entry point; `detect` resolves to this). */
        internal fun sortDetectedBoxes(boxes: List<JpDictRect>): List<JpDictRect> {
            if (boxes.size < 2) return boxes
            val order = ocrEngineSortOrder(boxes.map {
                BoundingBox(x = it.left, y = it.top, w = it.width(), h = it.height())
            })
            return order.map { boxes[it.toInt()] }
        }

        // Recognition constants (not tunable)
        private const val REC_TARGET_H = 48
        // Pruned CTC-head width (#39): gemm_8 emits one out per rec_remap entry,
        // and CLASS_REMAP[new] = orig id. Deliberately NOT a constant — the width
        // is derived from the loaded remap (`recNumOutputs`), so re-pruning the
        // head with a wider keep list (#44 added 160 CJK classes) cannot leave the
        // model and this file disagreeing. A stale constant used to be able to
        // disable the engine silently via isReady().
        private const val REC_STRIDE = 8
        /** Streaming batch size (#58 retune knob; was const 4). Batches of line
         * lines prepared per batch; per-batch concurrency = fanout. */
        var REC_BATCH_SIZE = 4
        /** Rec ncnn thread count (#58 retune knob; was hardcoded 1, #20). */
        var REC_THREADS = 1
        /** Max concurrent line infers per batch (was the literal 4 at both
         *  call sites, #58 retune knob). Together with [REC_THREADS] this is
         *  the page's total in-flight rec parallelism, so the two must be read
         *  as a pair — on a big.LITTLE device the same 4-way work is not the
         *  same cost split 4×1 (four lines, some on little cores) as 2×2
         *  (pairs sharing a big core). Default 4 is the shipped behaviour. */
        var REC_FANOUT = 4
        /** Alternative-list cap everywhere (per-timestep top-K, per-char alts, stitch merges). */
        private const val TOP_K = 15
        /** Single-pass targetW cap and stitch entry gate (targetW = rw*48/rh). #24 */
        private const val LONG_LINE_GATE = 2000
        /** Per-chunk targetW cap in the stitch paths. */
        private const val CHUNK_TARGET_MAX = 480
        /** Stitch best-pair window (last N stitched × first N current). */
        private const val STITCH_WINDOW = 10
        /** Stitch best-pair gates: center distance and prediction overlap. */
        private const val STITCH_MAX_DIST_PX = 30f
        private const val STITCH_MIN_PRED = 0.4f
        /** Stitch append rule: chars past last center + this gap start a new tail. */
        private const val STITCH_APPEND_GAP_PX = 10f
        // Lengthwise squish (#24): resample the length axis before inference (debug slider
        // 0.2–1.0, default 0.5). CTC tolerates it — JP prose holds to 0.5 (knee at 0.33),
        // narrow Latin glyphs are the first casualty (accepted: JP is the target).
	const val GAP_CHAR = '\u25CC'

        // Precomputed pixel math — bit-identical to the replaced float expressions
        // (verified over 2M random pixels in float32: same mults, same add order). #20
        // GRAY_LUT: per-channel 0.299/0.587/0.114 contributions; gray = R+G+B slots.
        private val GRAY_LUT: FloatArray = FloatArray(768) { i ->
            val v = (i % 256).toFloat()
            when (i / 256) { 0 -> v * 0.299f; 1 -> v * 0.587f; else -> v * 0.114f }
        }
        // DET_NORM_LUT: per-channel ImageNet (v/255-mean)/std for det NCHW input.
        private val DET_NORM_LUT: FloatArray = FloatArray(768) { i ->
            val v = (i % 256).toFloat() / 255f
            when (i / 256) {
                0 -> (v - 0.485f) / 0.229f
                1 -> (v - 0.456f) / 0.224f
                else -> (v - 0.406f) / 0.225f
            }
        }

        /** Squished content width for a target width: factor× length, min 8.
         * Model width snaps up to mult-of-8 with zero padding (existing pattern). #24 */
        private fun squishTarget(targetW: Int, factor: Float): Int =
            maxOf(8, (targetW * factor).roundToInt())

        /** Gray contribution sum for one pixel — replaces R*0.299+G*0.587+B*0.114. #20 */
        private fun grayLUT(r: Int, g: Int, b: Int): Float = GRAY_LUT[r] + GRAY_LUT[256 + g] + GRAY_LUT[512 + b]

        /** Pack gray pixels into 3-channel rec input with PP-OCR normalize
         * `(gray/127.5-1)`. Shared by the single-pass batch path and both stitch
         * chunk paths (was 3 copies, #29). Zero-pads columns `[contentW, modelW)`. */
        private fun buildRecInput(pixels: IntArray, contentW: Int, targetH: Int, modelW: Int): FloatArray {
            val inputFloats = FloatArray(1 * 3 * targetH * modelW)
            for (c in 0 until 3) {
                val cOff = c * targetH * modelW
                for (y in 0 until targetH) {
                    for (x in 0 until contentW) {
                        val px = pixels[y * contentW + x]
                        val gray = grayLUT(px shr 16 and 0xFF, px shr 8 and 0xFF, px and 0xFF)
                        inputFloats[cOff + y * modelW + x] = gray / 127.5f - 1f
                    }
                }
            }
            return inputFloats
        }

        /** Full rows plus the two whole-row values needed beyond their top-K. */
        internal data class CtcCandidateRows(
            val packed: FloatArray,
            val leftScores: FloatArray,
            val rightScores: FloatArray,
        )

        /**
         * Stage the full-logits fallback into the exact compact representation
         * understood by `jpdict_core::ctc_decode::ctc_decode_full_packed`.
         *
         * The upstream caller has already proved every row has at least
         * [numClasses] entries. Keeping those rows in Kotlin is deliberate: the
         * alternative is copying several MiB through UniFFI. Only 15 candidates
         * and the winning class's two neighbour scores cross to Rust.
         */
        internal fun packCtcCandidateRows(
            cropLogits: Array<FloatArray>?,
            seqLen: Int,
            numClasses: Int,
        ): CtcCandidateRows {
            check(seqLen >= 0) { "negative CTC sequence length: $seqLen" }
            check(numClasses >= TOP_K) { "CTC head has only $numClasses classes" }
            val rows = checkNotNull(cropLogits) { "full CTC logits are missing" }
            val packed = FloatArray(seqLen * TOP_K * 2)
            val winners = IntArray(seqLen) { -1 }

            for (t in 0 until seqLen) {
                val slice = checkNotNull(rows.getOrNull(t)) { "missing CTC row $t" }
                check(slice.size >= numClasses) { "short CTC row $t: ${slice.size} < $numClasses" }

                // Strict `>` preserves the legacy lowest-id-wins argmax tie.
                var maxIdx = 0
                var maxVal = Float.NEGATIVE_INFINITY
                for (c in slice.indices) {
                    if (slice[c] > maxVal) {
                        maxVal = slice[c]
                        maxIdx = c
                    }
                }
                winners[t] = maxIdx

                // Java PriorityQueue + stable descending sort is part of the
                // observable tie order, so retain it while staging candidates.
                val pq = java.util.PriorityQueue<Int>(TOP_K + 1, compareBy { slice[it] })
                for (c in slice.indices) {
                    pq.add(c)
                    if (pq.size > TOP_K) pq.poll()
                }
                val order = pq.toList().sortedByDescending { slice[it] }
                check(order.size >= TOP_K) { "CTC row $t has only ${order.size} classes" }
                for (k in 0 until TOP_K) {
                    val c = order[k]
                    packed[(t * TOP_K + k) * 2] = c.toFloat()
                    packed[(t * TOP_K + k) * 2 + 1] = slice[c]
                }
            }

            fun scoreAt(row: Int, prunedClass: Int): Float? =
                rows.getOrNull(row)?.getOrNull(prunedClass)

            val leftScores = FloatArray(seqLen) { Float.NaN }
            val rightScores = FloatArray(seqLen) { Float.NaN }
            for (t in 0 until seqLen) {
                val winner = winners[t]
                if (winner < 0) continue
                leftScores[t] = scoreAt(t - 1, winner) ?: Float.NaN
                rightScores[t] = scoreAt(t + 1, winner) ?: Float.NaN
            }
            return CtcCandidateRows(packed, leftScores, rightScores)
        }

        // Pooled det buffers — same ThreadLocal pattern as RecNcnn.tlBuffer. detect()
        // repaints the letterbox fully (opaque gray drawColor) and overwrites both
        // arrays end-to-end every call, so reuse is stale-safe. Sized by modelSize (#20, #51).
        private val tlDetImgData = ThreadLocal<FloatArray>()
        private val tlDetPixels = ThreadLocal<IntArray>()
        private val tlDetLetterbox = ThreadLocal<android.graphics.Bitmap>()
        /** Pooled rec-net input destination for the one-draw path
         * ([drawRecInput]): a bitmap + its Canvas per thread, kept at the widest
         * `contentW` seen. Sized by capacity, not exactly, because `contentW`
         * ranges 34…1518 across a page and the read-back is the sub-rect — the
         * same reasoning as [tlDetCrop]. */
        private val tlRecInput = ThreadLocal<Pair<android.graphics.Bitmap, android.graphics.Canvas>>()
        /** The one draw's Paint: filter on, dither off. `dither` matters — a
         *  dithered draw differs from `createScaledBitmap`'s un-dithered one by
         *  up to 15 levels on a text line. Never mutated, so one instance is
         *  shared across the fan-out threads. */
        private val recInputPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        /** The resized crop's pixels, for [DetNcnn.inferLetterboxed]. Exact-size
         *  match like [tlDetImgData]: the native call indexes it as
         *  resizeW×resizeH, so a 960-sized reuse under an 896 run must not
         *  silently hand over the wrong stride (#51's reasoning, same shape). */
        private val tlDetCrop = ThreadLocal<IntArray>()

        /** Native (C++) letterbox + ImageNet normalisation, vs the Kotlin one
         *  below. ON by default: the two are bit-identical (0 of 2,408,448
         *  input floats differ, and the probability maps match exactly) and the
         *  native one is 1.7x the whole preprocessing step — it does the pad,
         *  the normalisation and the NCHW write in a single pass instead of
         *  canvas → 896² getPixels → 2.4 M Kotlin LUT writes → 9.6 MB direct
         *  ByteBuffer → ncnn's own `fill_input` copy.
         *
         *  Off restores the historical Kotlin path, which stays in the tree as
         *  the reference implementation and as the fallback whenever the
         *  native call declines (see [detProbMap]). The parity gate
         *  (`DetLetterboxParityTest#boxesMatchThroughTheEngine`) flips this
         *  field to compare both paths inside one build — hence `@Volatile`
         *  and a plain var, not a pref. */
        @Volatile @JvmStatic var useNativeLetterbox = true

        /** One-draw rec preparation ([drawRecInput]) vs the historical
         *  crop → `createScaledBitmap` chain, for a **horizontal** line.
         *
         *  **OFF by default, and it should stay off.** The one draw is the same
         *  Skia scale without the copy in front of it, but it is not the same
         *  pixels: with a src rect Skia resolves the sampling in fixed point and
         *  the rect's origin shifts it, which is 2…1600 differing pixels per
         *  line (±5…75 levels). The INT8 rec net turns that into output changes
         *  on all three bench fixtures — text differs on 2 of 17 `bookpage.png`
         *  lines, 4 of 37 `Screenshot_20260530-172718.png` and 8 of 70
         *  `f5d7d08735383899.jpg` (`二`→`J`, `記者たの案`→`記者たちの案`,
         *  `QCoogle`→`Q Coogle`), alternative counts on 1/1/5 of them and CTC
         *  columns on 3/30/28 — in exchange for −8 ms/page on `bookpage.png` and
         *  **+3 and +5 ms/page, i.e. slower**, on the other two. A changed
         *  character per page is not worth a millisecond; see
         *  `RecPrepParityTest.fusedRecInputMatchesTheChainOnEveryFixture` for the
         *  table and `RecDrawParityTest` for the pixel-level split.
         *
         *  A parity toggle, not a preference: `@Volatile` and a plain var, not a
         *  pref, so the test can flip it inside one build exactly as it does for
         *  [useNativeLetterbox]. The shipped path is the chain; a portrait crop
         *  and the long-line stitch chunks take the chain whatever this says. */
        @Volatile @JvmStatic var useFusedRecInput = false

        /**
         * Recognition-preparation nanoseconds, the region between "we have a
         * line box" and "the net has its input": the crop, the rotate, the
         * resize (or the single [drawRecInput] draw) in [recPrepBitmapNanos]; the
         * `getPixels` + [buildRecInput] tensor build in [recPrepTensorNanos]; and
         * the char-box evidence read in [recPrepEvidenceNanos]. Deliberately
         * *not* including inference or the CTC decode — the net is ~90% of the
         * page and would hide the frontend entirely.
         *
         * Cumulative across the process and **summed per worker thread**, so at
         * the default `REC_FANOUT = 4` four lines' work accumulates into the
         * same counter: read it as an upper bound and only ever compare it
         * against itself, never as a page wall. The serial per-page numbers
         * come from `RecDrawParityTest`, which times the same code shapes one
         * line at a time. Two `System.nanoTime()` calls per line, same order
         * as the per-line `InferLog` timing [RecNcnn.inferTopK] already does.
         */
        @Volatile @JvmStatic var recPrepBitmapNanos = 0L

        @Volatile @JvmStatic var recPrepTensorNanos = 0L

        /** The char-box evidence read ([RecSource.evidencePixels]), in its own
         *  counter so the before/after table can show it: reading the rect out
         *  of the page instead of out of a crop is ~3x cheaper and
         *  pixel-identical, and it is the whole of the bit-exact saving. */
        @Volatile @JvmStatic var recPrepEvidenceNanos = 0L

        /** Where Skia actually put the `(modelSize - content) / 2f` translate
         *  that `drawBitmap` is given: it rounds that half pixel AWAY FROM ZERO,
         *  so the content lands at `(modelSize - content + 1) / 2` — NOT at the
         *  integer-division `(modelSize - content) / 2` that looks equivalent.
         *  Measured on bookpage.png (896-403 = 493): the nearest-copy model at
         *  247 explains all 361,088 content pixels, and at 246 it misses
         *  28,787 of them. The native path re-lays the content at this offset,
         *  so both paths have to agree on it exactly or the tensor shifts a
         *  whole pixel inside the letterbox. */
        private fun detLetterboxPad(modelSize: Int, content: Int): Int =
            (modelSize - content + 1) / 2
    }

    // ——— Tunable getters (live SharedPreferences, defaults from companion) ———
    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    /** Content long side pref; clamped to the net input side at detect() (#51). */
    private val detLongSide: Int
        get() = DEF_DET_LONG_SIDE
    private val detThresh: Float
        get() = prefs.getFloat(PREF_DET_THRESH, DEF_DET_THRESH)
    private val detUnclip: Float
        get() = prefs.getFloat(PREF_DET_UNCLIP, DEF_DET_UNCLIP)
    /** #28/#100: this entry point's furigana switch; off keeps every contour. */
    private val detFurigana: Boolean
        get() = prefs.getBoolean(furiganaPref, DEF_DET_FURIGANA)
    /** CAP char placement switch (debug screen); on for the device A/B. */
    private val capPlacement: Boolean
        get() = prefs.getBoolean(PREF_BOX_PLACEMENT_CAP, DEF_BOX_PLACEMENT_CAP)
    private val xOverlapThresh: Float
        get() = prefs.getFloat(PREF_X_OVERLAP, DEF_X_OVERLAP)
    /** Live squish factor from debug slider (0.2–1.0, default 0.5). #24 */
    val recSquish: Float
        get() = prefs.getFloat(PREF_REC_SQUISH, DEF_REC_SQUISH).coerceIn(0.2f, 1.0f)

    /**
     * The debug verbosity gate for the Kotlin log sites, hoisted to a
     * process-wide volatile read ([NcnnVerboseLog.enabled]) so a per-box or
     * per-line site costs a branch and nothing else.
     *
     * The *summary* lines never consult it — `detect: final N boxes`,
     * `detectRotated: …`, `stream start`/`stream done`, `batch N … in Xms`, the
     * head-width and top-K mismatch warnings, and the one-time load lines are
     * what a normal run still says. This only gates the walk: the per-call
     * shape/tunable detail the native `PPOCR_LOGI` lines mirror, and the
     * per-box and per-line progress (`rubyTrim`, `line idx=`, `crop rw=`, the
     * long-line stitch notes).
     */
    private val verboseLog: Boolean get() = NcnnVerboseLog.enabled

    init {
        try {
            val cacheDir = File(context.cacheDir, "model_cache")
            cacheDir.mkdirs()
            // Clear stale cached models so asset updates take effect
            cacheDir.listFiles()?.forEach { it.delete() }

            // ── Load PP-OCRv6 detection model (ncnn DB) ──
            try {
                detNcnn = DetNcnn.create(context)
                Log.d(TAG, "DetNcnn loaded: $detNcnn")
            } catch (e: Exception) {
                Log.e(TAG, "DetNcnn failed", e)
            }

            // ── Load PP-OCRv6 recognition model (single dynamic-width ncnn, #23) ──
            try {
                recDynNcnn = RecNcnn.create(context, numThreads = REC_THREADS)
                Log.d(TAG, "RecNcnn dyn loaded: $recDynNcnn")
            } catch (e: Exception) {
                Log.e(TAG, "RecNcnn dyn failed", e)
            }

            // ── Load vocabulary ──
            val vocabJson = context.assets.open("PP-OCRv6_small_ncnn/vocab.json")
                .bufferedReader().use { it.readText() }
            val listType = object : TypeToken<List<String>>() {}.type
            ppocrVocab = Gson().fromJson(vocabJson, listType)
            Log.d(TAG, "Vocabulary loaded: ${ppocrVocab.size} entries")

            // ── CTC-head remap: pruned-out id -> orig class id (#39) ──
            val remapTxt = context.assets.open("PP-OCRv6_small_ncnn/rec_remap.txt")
                .bufferedReader().use { it.readText() }
            classRemap = remapTxt.lineSequence()
                .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
                .toList().toIntArray()
            recNumOutputs = classRemap.size
            try {
                ctcDecoder = RustCtcDecode(ppocrVocab, classRemap.toList())
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Rust CTC decoder unavailable", e)
            }
            Log.d(TAG, "Class remap loaded: ${classRemap.size} entries (head width $recNumOutputs)")
            // Also into the in-app log: a native/model width disagreement (the #44 re-prune
            // left a hardcoded 13193 in ncnn_jni.cpp for a while) showed up as plausible-
            // looking garbage text rather than as an error, so the expected width has to be
            // visible in the log the user can actually read back.
            InferLog.add("rec head width=$recNumOutputs (from rec_remap.txt)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models", e)
        }
    }

    fun isReady(): Boolean =
        detNcnn != null && recDynNcnn != null && ctcDecoder != null &&
            ppocrVocab.isNotEmpty() && recNumOutputs > 0

    // ═════════════════════════════════════════════════════════════════════════
    //  Detect — DB segmentation → contours → boxes (#28 furigana, unclip)
    // ═════════════════════════════════════════════════════════════════════════

    /** Det mask in model space plus the letterbox geometry that maps it back to
     *  source pixels. Shared by [detect] and [detectRotated] so the two geometry
     *  paths cannot drift apart in preprocessing. */
    private class DetMask(
        val prob: FloatArray,
        val outW: Int,
        val outH: Int,
        val modelSize: Int,
        val resizeW: Int,
        val resizeH: Int,
        val origW: Int,
        val origH: Int,
    ) {
        val imgLeft: Float get() = (modelSize - resizeW) / 2f
        val imgTop: Float get() = (modelSize - resizeH) / 2f
        val scaleX: Float get() = origW / resizeW.toFloat()
        val scaleY: Float get() = origH / resizeH.toFloat()
    }

    /** Det preprocessing → probability map, i.e. steps 1b-3 of the old
     *  runDetMask body, moved out so the two implementations sit side by side
     *  instead of one sprawling behind an `if`. The Kotlin letterbox is
     *  unchanged and remains the reference: the native path is chosen when it
     *  is enabled and can serve this bitmap, and every failure — a null from
     *  JNI, a non-opaque source, the toggle off — lands back in the Kotlin
     *  path rather than returning fewer boxes. */
    private fun detProbMap(det: DetNcnn, bitmap: Bitmap, resizeW: Int, resizeH: Int, modelSize: Int): FloatArray? {
        // Skia owns the resize either way: its filter is the reference the net
        // was tuned against, and moving it into C++ cannot be bit-exact.
        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, resizeH, true)

        if (useNativeLetterbox && !bitmap.hasAlpha()) {
            // Only the pad + the normalisation move. `hasAlpha` is the guard for
            // the one thing the native path cannot reproduce: the Canvas
            // composites a translucent source over the 128-gray fill, and a
            // share-image PNG with transparency would otherwise be fed raw RGB.
            val needInts = resizeW * resizeH
            val crop: IntArray = tlDetCrop.get()?.takeIf { it.size == needInts }
                ?: IntArray(needInts).also { tlDetCrop.set(it) }
            resized.getPixels(crop, 0, resizeW, 0, 0, resizeW, resizeH)
            resized.recycle()
            val padX = detLetterboxPad(modelSize, resizeW)
            val padY = detLetterboxPad(modelSize, resizeH)
            val prob = det.inferLetterboxed(crop, resizeW, resizeH, modelSize, padX, padY)
            if (prob != null) {
                if (verboseLog) {
                    Log.d(TAG, "detect: native letterbox content=${resizeW}x$resizeH pad=($padX,$padY) model=$modelSize")
                }
                return prob
            }
            Log.w(TAG, "detect: native letterbox returned null, using the Kotlin path")
        }
        return kotlinProbMap(det, resized, resizeW, resizeH, modelSize)
    }

    /** The historical Kotlin preprocessing, verbatim: letterbox onto a
     *  128-gray square, read it back, write the NCHW tensor. Kept as the
     *  reference implementation and the fallback path; recycles [resized]. */
    private fun kotlinProbMap(det: DetNcnn, resized: Bitmap, resizeW: Int, resizeH: Int, modelSize: Int): FloatArray? {
        // Letterbox to modelSize × modelSize (pooled — fully repainted below, never recycled) #20
        val letterbox = tlDetLetterbox.get()
            ?.takeIf { !it.isRecycled && it.width == modelSize && it.height == modelSize }
            ?: android.graphics.Bitmap.createBitmap(modelSize, modelSize, android.graphics.Bitmap.Config.ARGB_8888)
                .also { tlDetLetterbox.set(it) }
        val canvas = android.graphics.Canvas(letterbox)
        canvas.drawColor(Color.rgb(128, 128, 128))
        canvas.drawBitmap(resized, (modelSize - resizeW) / 2f, (modelSize - resizeH) / 2f, null)
        canvas.setBitmap(null)
        resized.recycle()

        // 2. Build NCHW input with ImageNet normalisation [3,S,S].
        // Buffers pooled ThreadLocal; fully overwritten below. #20
        val needFloats = 3 * modelSize * modelSize
        // Exact-size pool match: DetNcnn.infer strict-checks array length, so a
        // 960-sized reuse under an 896 run (or vice versa) hard-fails (#51).
        val imgData: FloatArray = tlDetImgData.get()?.takeIf { it.size == needFloats }
            ?: FloatArray(needFloats).also { tlDetImgData.set(it) }
        val needInts = modelSize * modelSize
        val pixels: IntArray = tlDetPixels.get()?.takeIf { it.size >= needInts }
            ?: IntArray(needInts).also { tlDetPixels.set(it) }
        letterbox.getPixels(pixels, 0, modelSize, 0, 0, modelSize, modelSize)
        // Single-pass NCHW write.
        for (y in 0 until modelSize) {
            for (x in 0 until modelSize) {
                val px = pixels[y * modelSize + x]
                val r = DET_NORM_LUT[px shr 16 and 0xFF]
                val g = DET_NORM_LUT[256 + (px shr 8 and 0xFF)]
                val b = DET_NORM_LUT[512 + (px and 0xFF)]
                val base = y * modelSize + x
                imgData[base] = r
                imgData[modelSize * modelSize + base] = g
                imgData[2 * modelSize * modelSize + base] = b
            }
        }

        // 3. Run detection via ncnn.
        return det.infer(imgData, modelSize, modelSize)
    }

    /** Letterbox, det infer and prob-map stats — the part of detection that is
     *  identical for the axis-aligned and rotated geometry paths. Returns null
     *  when the net is not loaded or inference fails, the same bail-outs
     *  [detect] has always had. */
    private fun runDetMask(bitmap: Bitmap): DetMask? {
        val det = detNcnn ?: return null
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // 1. Resize keeping longest side = min(pref, modelSize), pad to
        // modelSize×modelSize letterbox (#51: net runs at DET_MODEL_SIZE).
        val modelSize = DET_MODEL_SIZE.coerceIn(320, 960)
        val targetLong = minOf(detLongSide, modelSize)
        if (verboseLog) {
            Log.d(TAG, "detect tunables thresh=$detThresh unclip=$detUnclip longSide=$targetLong xOverlap=$xOverlapThresh modelSize=$modelSize furigana=$detFurigana")
        }
        val scale = targetLong.toFloat() / maxOf(origW, origH)
        val resizeW = maxOf((origW * scale).roundToInt(), 32)
        val resizeH = maxOf((origH * scale).roundToInt(), 32)

        val probArr = detProbMap(det, bitmap, resizeW, resizeH, modelSize) ?: return null
        // probArr should be S×S float prob map; upsample a downsampled
        // square output (e.g. 240×240) via nearest.
        val outH: Int
        val outW: Int
        val probArrNorm: FloatArray
        if (probArr.size == modelSize * modelSize) {
            outH = modelSize
            outW = modelSize
            probArrNorm = probArr
        } else {
            val dim = kotlin.math.sqrt(probArr.size.toDouble()).toInt()
            if (dim * dim == probArr.size && dim <= modelSize) {
                // Upsample small prob map to modelSize via nearest for postprocess
                outH = modelSize
                outW = modelSize
                probArrNorm = FloatArray(modelSize * modelSize)
                val scaleSmall = dim.toFloat() / modelSize
                for (y in 0 until modelSize) {
                    for (x in 0 until modelSize) {
                        val sx = (x * scaleSmall).toInt().coerceIn(0, dim - 1)
                        val sy = (y * scaleSmall).toInt().coerceIn(0, dim - 1)
                        probArrNorm[y * modelSize + x] = probArr[sy * dim + sx]
                    }
                }
                if (verboseLog) {
                    Log.d(TAG, "detect: upsampled det output ${dim}x${dim} -> ${modelSize}x${modelSize}")
                }
            } else {
                // Fallback: treat as flat and use as is
                val total = probArr.size
                val side = kotlin.math.sqrt(total.toDouble()).toInt()
                outH = side
                outW = side
                probArrNorm = probArr
                Log.w(TAG, "detect: unexpected prob size $total, using ${outH}x${outW}")
            }
        }
        if (verboseLog) {
            Log.d(TAG, "detect: output size=${probArrNorm.size} expected=${modelSize * modelSize} out=${outW}x${outH}")
        }
        // Summary, not walk: one line per detect, and the ring the user reads
        // back when they ask "what did it see?".
        InferLog.add("detect out=${outW}x${outH} size=${probArrNorm.size}")

        val probArrFinal = probArrNorm

        // Prob map stats — this pass exists only for the `prob_map:` line below,
        // so with the gate off it does not run at all: `modelSize²` floats (the
        // 802,816 the book-page fixture runs at, up to 921,600 at the 960
        // maximum) of min/max/add that nothing else reads — `DetMask` carries
        // the map, not the stats. Gating the line without gating this would
        // have kept the cost and dropped only the output; measured at 4-5 ms
        // against the ~0.03 ms the four log writes around it cost
        // (`KotlinVerboseBenchTest`, per-detect split), and at ~2 ms of the
        // det call's own wall end to end.
        if (verboseLog) {
            var pMin = Float.MAX_VALUE
            var pMax = Float.MIN_VALUE
            var pSum = 0f
            var pCount = 0
            for (i in probArrFinal.indices) {
                val v = probArrFinal[i]
                pMin = minOf(pMin, v)
                pMax = maxOf(pMax, v)
                pSum += v
                pCount++
            }
            Log.d(TAG, "prob_map: min=$pMin max=$pMax mean=${if (pCount > 0) pSum / pCount else 0f}")
        }

        return DetMask(probArrNorm, outW, outH, modelSize, resizeW, resizeH, bitmap.width, bitmap.height)
    }

    /** Flood-fill one 8-connected component of the det prob map over
     *  `prob > thresh` into [q], starting at [start] (already known to pass the
     *  threshold; marked visited here). Returns the pixel count; `q[0, count)`
     *  holds the component's pixels as `y * outW + x`. Shared by [detect] and
     *  [detectRotated] so their fills cannot drift apart. */
    private fun floodFillComponent(
        prob: FloatArray,
        visited: ByteArray,
        q: IntArray,
        start: Int,
        outW: Int,
        outH: Int,
        thresh: Float,
    ): Int {
        var qHead = 0
        var qTail = 0
        q[qTail++] = start
        visited[start] = 1
        while (qHead < qTail) {
            val cur = q[qHead++]
            val cx = cur % outW
            val cy = cur / outW
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx in 0 until outW && ny in 0 until outH) {
                        val nIdx = ny * outW + nx
                        if (visited[nIdx].toInt() == 0 && prob[nIdx] > thresh) {
                            visited[nIdx] = 1
                            q[qTail++] = nIdx
                        }
                    }
                }
            }
        }
        return qTail
    }

    fun detect(bitmap: Bitmap): List<JpDictRect> {
        val mask = runDetMask(bitmap) ?: return emptyList()
        val probArrFinal = mask.prob
        val outW = mask.outW
        val outH = mask.outH
        val origW = mask.origW.toFloat()
        val origH = mask.origH.toFloat()
        // C2/#86: the letterbox geometry comes off the mask, through the same
        // accessors detectRotated uses — [DetMask] exists so the two geometry
        // paths cannot drift apart in preprocessing, and re-deriving the formulas
        // here (as this used to) is exactly the drift it was built to stop.
        val imgLeft = mask.imgLeft
        val imgTop = mask.imgTop
        val resScaleW = mask.scaleX
        val resScaleH = mask.scaleY
        // A7/#86: hoisted out of the full-image scan. The getter is a live
        // SharedPreferences read (map lookup + synchronized getFloat), and it was
        // evaluated per pixel (and again in the flood-fill of every component).
        val thresh = detThresh

        // 6. Connected components (contours) via flat flood-fill. The axis-aligned
        // path keeps only min/max X/Y from each component; detectRotated fits
        // those same component pixels instead (#53).
        val visited = ByteArray(outH * outW)
        val rawBoxes = mutableListOf<JpDictRect>()
        // Pre-unclip contour boxes, parallel to rawBoxes — furigana matching runs on these
        // because unclip padding fabricates overlap for stacked fragments. #28
        val rawPreBoxes = mutableListOf<JpDictRect>()
        // Reused Int queue (no per-component alloc).
        val q = IntArray(outH * outW)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val idx = y * outW + x
                if (visited[idx].toInt() != 0 || probArrFinal[idx] <= thresh) continue

                val pixelCount = floodFillComponent(probArrFinal, visited, q, idx, outW, outH, thresh)
                if (pixelCount < 3) continue // noise filter

                // Bounds of the filled component (y*W+x sits in q[0, pixelCount)).
                var minX = Int.MAX_VALUE
                var maxX = Int.MIN_VALUE
                var minY = Int.MAX_VALUE
                var maxY = Int.MIN_VALUE
                for (i in 0 until pixelCount) {
                    val cur = q[i]
                    val cx = cur % outW
                    val cy = cur / outW
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                }

                // Convert from output coords to original image coords
                // (output space is letterbox image centered in modelSize×modelSize).
                // The mask's accessors hold the letterbox geometry (C2/#86).
                val bx = ((minX - imgLeft) * resScaleW).roundToInt().coerceAtLeast(0)
                val by = ((minY - imgTop) * resScaleH).roundToInt().coerceAtLeast(0)
                val bx2 = ((maxX + 1 - imgLeft) * resScaleW).roundToInt()
                    .coerceAtMost(origW.roundToInt())
                val by2 = ((maxY + 1 - imgTop) * resScaleH).roundToInt()
                    .coerceAtMost(origH.roundToInt())

                /** Unclip (PP-OCR DB): dilate the contour box outward by
                 * `expand = area × unclipRatio / perimeter`. E.g. a 100×20 box
                 * (area 2000, perimeter 240) at ratio 1.5 expands by 12.5px per
                 * side. Clamped to the image; boxes < 4px after expansion drop. */
                val bw = (bx2 - bx).toFloat()
                val bh = (by2 - by).toFloat()
                val area = bw * bh
                val perimeter = 2f * (bw + bh)
                val expand = if (perimeter > 0f) area * detUnclip / perimeter else 0f

                val ux = (bx - expand).coerceAtLeast(0f).roundToInt()
                val uy = (by - expand).coerceAtLeast(0f).roundToInt()
                val ux2 = (bx2 + expand).coerceAtMost(origW).roundToInt()
                val uy2 = (by2 + expand).coerceAtMost(origH).roundToInt()

                if (ux2 - ux < 4 || uy2 - uy < 4) continue
                rawPreBoxes.add(JpDictRect(bx, by, bx2, by2))
                rawBoxes.add(JpDictRect(ux, uy, ux2, uy2))
            }
        }

        if (verboseLog) Log.d(TAG, "detect: raw ${rawBoxes.size} boxes")

        // 6b. Furigana filter (#28) on RAW contour geometry (pre-unclip, pre-merge):
        // unclip padding inflates overlap and fabricates ruby matches out of stacked
        // column fragments (only their padding overlaps). Kept indices gate rawBoxes.
        // The switch can turn the whole rule off; then every contour is kept.
        val keepNonRuby = if (detFurigana) {
            filterFurigana(rawPreBoxes, rawBoxes, bitmap.width, bitmap.height)
        } else {
            BooleanArray(rawBoxes.size) { true }
        }
        val keptUnclipped = rawBoxes.filterIndexed { i, _ -> keepNonRuby.getOrElse(i) { true } }
        if (keptUnclipped.size != rawBoxes.size && verboseLog) {
            val dropped = rawPreBoxes.filterIndexed { i, _ -> !keepNonRuby.getOrElse(i) { true } }
            Log.d(TAG, "detect: furigana ${rawBoxes.size} → ${keptUnclipped.size} boxes, dropped=${
                dropped.joinToString(";") { "${it.width()}x${it.height()}@${it.left},${it.top}" }
            }")
        }

        // 7. Post-processing: merge overlapping boxes
        val merged = mergeOverlappingBoxes(keptUnclipped)

        // 8. Filter degenerate boxes
        val filtered = merged.filter { it.width() >= 10 && it.height() >= 10 }

        // 9. Shrink vertical box widths by 10% (centered)
        val shrunk = filtered.map { box ->
            if (box.height() > box.width()) {
                val shrink = (box.width() * 0.05f).roundToInt()
                JpDictRect(box.left + shrink, box.top, box.right - shrink, box.bottom)
            } else box
        }

        // 9b. Ruby-gutter trim (#48): furigana-widened vertical boxes are cut
        // back to the main column HERE. Jobs, crops, char boxes, LineResult
        // and overlay all derive from the returned boxes, so the ruby width
        // can never re-enter downstream (no mask-then-keep-geometry).
        val trimmed = if (RUBY_TRIM_VERTICAL) trimRubyGutterVertical(shrunk, bitmap) else shrunk

        // 10. Split overlapping horizontal boxes at overlap midpoint
        val horizontals = trimmed.mapIndexedNotNull { i, b ->
            if (b.width() >= b.height()) i to b else null
        }
        val splitBoxes = trimmed.toMutableList()
        for (i in horizontals.indices) {
            for (j in (i + 1) until horizontals.size) {
                val ai = horizontals[i].first
                val bi = horizontals[j].first
                val a = splitBoxes[ai]; val b = splitBoxes[bi]

                val hOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
                if (hOverlap <= 0) continue

                val (upper, lower) = if (a.top <= b.top) ai to bi else bi to ai
                val upperBottom = splitBoxes[upper].bottom
                val lowerBottom = splitBoxes[lower].bottom

                if (upperBottom > splitBoxes[lower].top) {
                    val overlapMid = (splitBoxes[lower].top + minOf(upperBottom, lowerBottom)) / 2
                    splitBoxes[upper] = JpDictRect(
                        splitBoxes[upper].left, splitBoxes[upper].top,
                        splitBoxes[upper].right, overlapMid.coerceAtLeast(splitBoxes[upper].top + 1)
                    )
                    splitBoxes[lower] = JpDictRect(
                        splitBoxes[lower].left, splitBoxes[upper].bottom,
                        splitBoxes[lower].right, lowerBottom
                    )
                }
            }
        }

        // 11. Sort: horizontal top-bottom/left-right, vertical right-left/top-bottom
        val sorted = sortDetectedBoxes(splitBoxes)
        Log.d(TAG, "detect: final ${sorted.size} boxes")
        InferLog.add("detect final=${sorted.size} boxes")
        return sorted
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Rotated detect (#53, on by default) — same mask, minAreaRect per component
    // ═════════════════════════════════════════════════════════════════════════

    /** #53: the detection geometry for the caller, honouring the pref. It is ON
     *  by default; turned off it is exactly [detect], one [LineBox.of] per rect,
     *  the pre-#53 pipeline. */
    fun detectLines(bitmap: Bitmap): List<LineBox> =
        if (isDetRotated(context)) detectRotated(bitmap)
        else detect(bitmap).map { LineBox.of(it) }

    /**
     * #53: the rotated path. Same DB mask and flood-fill as [detect], but
     * each component's boundary pixels are fitted with a minimum-area rectangle
     * ([RotatedGeometry.fitQuad]) instead of being reduced to min/max X/Y — the
     * same pixels PaddleOCR's default `quad` postprocess fits. The fit is done on
     * the pixels' outer corners in source space, so an axis-aligned component
     * reproduces the default path's `maxX + 1` box convention.
     *
     * A fit within [RotatedGeometry.AXIS_ALIGNED_TOL_DEG] of the upright axes is
     * returned as a plain [LineBox.rect] with no quad: upright content keeps the
     * exact recognition path it had. Only genuinely rotated Lines carry a quad
     * and get unrotated before recognition.
     *
     * Deliberately narrower than the axis-aligned pipeline for now: furigana
     * filtering runs on AABB geometry, but box merging/splitting, the ruby-gutter
     * trim and the axis-aligned furigana geometry rules do not apply to rotated
     * Lines. The axis-aligned path is untouched and still reachable with the pref
     * off; this is the default.
     */
    fun detectRotated(bitmap: Bitmap): List<LineBox> {
        val mask = runDetMask(bitmap) ?: return emptyList()
        val prob = mask.prob
        val outW = mask.outW
        val outH = mask.outH
        val imgLeft = mask.imgLeft
        val imgTop = mask.imgTop
        val scaleX = mask.scaleX
        val scaleY = mask.scaleY
        // A7/#86: one prefs read for the whole scan, as in [detect].
        val thresh = detThresh

        val visited = ByteArray(outH * outW)
        val q = IntArray(outH * outW)
        // Component boundary pixel corners in source pixels, reused per component.
        var points = FloatArray(1024)
        var pointCount = 0
        fun addPoint(px: Float, py: Float) {
            if ((pointCount + 1) * 2 > points.size) points = points.copyOf(points.size * 2)
            points[pointCount * 2] = px
            points[pointCount * 2 + 1] = py
            pointCount++
        }

        val preQuads = mutableListOf<JpDictQuad>()
        val quads = mutableListOf<JpDictQuad>()
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val idx = y * outW + x
                if (visited[idx].toInt() != 0 || prob[idx] <= thresh) continue

                val pixelCount = floodFillComponent(prob, visited, q, idx, outW, outH, thresh)
                if (pixelCount < 3) continue

                // Boundary pixels: any of the 8 neighbours outside the mask. The
                // hull of the component is the hull of its boundary, and the outer
                // corners of those pixels enclose exactly the same pixels the
                // axis-aligned min/max box covers.
                pointCount = 0
                for (i in 0 until pixelCount) {
                    val cur = q[i]
                    val cx = cur % outW
                    val cy = cur / outW
                    var boundary = false
                    for (dy in -1..1) {
                        if (boundary) break
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx !in 0 until outW || ny !in 0 until outH ||
                                prob[ny * outW + nx] <= thresh
                            ) {
                                boundary = true
                                break
                            }
                        }
                    }
                    if (!boundary) continue
                    val mx = (cx - imgLeft) * scaleX
                    val my = (cy - imgTop) * scaleY
                    addPoint(mx - 0.5f * scaleX, my - 0.5f * scaleY)
                    addPoint(mx + 0.5f * scaleX, my - 0.5f * scaleY)
                    addPoint(mx + 0.5f * scaleX, my + 0.5f * scaleY)
                    addPoint(mx - 0.5f * scaleX, my + 0.5f * scaleY)
                }

                val preQuad = RotatedGeometry.fitQuad(points, pointCount) ?: continue
                val quad = RotatedGeometry.unclip(preQuad, detUnclip)
                if (quad.localWidth < 4f || quad.localHeight < 4f) continue
                preQuads.add(preQuad)
                quads.add(quad)
            }
        }

        // 6b. Furigana filter (#28) on AABB geometry: the same rule and the same
        // raw-vs-unclipped pair as the default path, so ruby is not promoted to a
        // Line just because the fit rotated it. Skipped when the switch is off.
        val keep = if (detFurigana) {
            filterFurigana(
                preQuads.map { it.toRect() },
                quads.map { it.toRect() },
                bitmap.width,
                bitmap.height,
            )
        } else {
            BooleanArray(quads.size) { true }
        }

        // 7-9. Min-size filter, blob filter and the centred vertical-width
        // shrink; merging, splitting and the ruby-gutter trim are default-path
        // stages (#53 note).
        val minSized = quads.indices
            .filter { keep.getOrElse(it) { true } }
            .map { quads[it] }
            .filter { it.localWidth >= 10f && it.localHeight >= 10f }
        // #53: a DB blob that merged several Lines fits one frame covering them
        // all, while the Lines inside it arrive as their own smaller fits. The
        // blob is the false positive, so drop it before it can become a Line.
        val kept = RotatedGeometry.filterEnclosingBlobs(minSized)
        val result = mutableListOf<LineBox>()
        for (quadIn in kept) {
            val quad = if (RotatedGeometry.isVertical(quadIn)) {
                RotatedGeometry.inset(quadIn, quadIn.localWidth * 0.05f, 0f)
            } else {
                quadIn
            }
            val rect = quad.toRect()
            // Upright fits stay on the exact axis-aligned path.
            result.add(if (quad.isAxisAligned()) LineBox(rect) else LineBox(rect, quad))
        }
        val blobs = minSized.size - kept.size
        val sorted = sortDetectedLineBoxes(result)
        Log.d(TAG, "detectRotated: ${quads.size} fitted, $blobs blob(s) filtered, final ${sorted.size} boxes, rotated=${sorted.count { it.isRotated }}")
        InferLog.add("detectRotated fitted=${quads.size} blobs=$blobs final=${sorted.size} rotated=${sorted.count { it.isRotated }}")
        return sorted
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Box merging & sorting
    // ═════════════════════════════════════════════════════════════════════════

    private fun mergeOverlappingBoxes(boxes: List<JpDictRect>): List<JpDictRect> {
        if (boxes.size < 2) return boxes
        return mergeBoxesMerge(
            boxes.map { BoundingBox(x = it.left, y = it.top, w = it.width(), h = it.height()) },
            xOverlapThresh,
        ).map { JpDictRect(it.x, it.y, it.x + it.w, it.y + it.h) }
    }

    private fun shouldMerge(a: JpDictRect, b: JpDictRect): Boolean =
        mergeBoxesShouldMerge(
            BoundingBox(x = a.left, y = a.top, w = a.width(), h = a.height()),
            BoundingBox(x = b.left, y = b.top, w = b.width(), h = b.height()),
            xOverlapThresh,
        )

    /** Keep-flags for likely-furigana boxes. raw/uncl are index-aligned (raw contours
     * vs unclipped detect boxes). #28
     *
     * The pair walk and the orientation gates run in the Rust core in one FFI
     * call ([FuriganaRule.filter]); calling the per-pair predicates from here
     * crossed the boundary O(n²) times and made dense pages take seconds. */
    private fun filterFurigana(raw: List<JpDictRect>, uncl: List<JpDictRect>, imgW: Int, imgH: Int): BooleanArray =
        FuriganaRule.filter(raw, uncl, imgW, imgH)

    /** Ruby-gutter trim for vertical lines (#48): detector boxes that swallowed
     * the furigana strip come out ~2x normal column width (measured 127px vs
     * 61px median on ruby_ebook). The trim is GEOMETRIC — the right side of
     * the box is cut off here in detect(), so jobs, crops, char boxes,
     * LineResult and overlay all derive from the trimmed box and the ruby
     * width can never re-enter. (Masking pixels while keeping the wide box
     * keeps the bad geometry and its timestep crush — rejected per owner.)
     *
     * A vertical box is a candidate when wider than 1.35x the median vertical
     * width with a removable strip >= 12px. The cut is image-evidence:
     * per-column ink profile over the full box height (polarity + thresholds
     * shared with snapping); the leftmost run of >= 3 near-empty (< 4%) columns
     * inside [L+0.40W, L+0.80W] with ink following it is the main/ruby gutter —
     * cut at its start. Touching ruby with no clean gutter but a thin spot
     * (window minimum < 6%) falls back to half width ("remove the right
     * half"); solid-wide boxes (headings) are left alone. */
    private fun trimRubyGutterVertical(boxes: List<JpDictRect>, bitmap: Bitmap): List<JpDictRect> {
        val vertW = boxes.filter { isVerticalBox(it) }.map { it.width() }.sorted()
        if (vertW.size < 2) return boxes
        val medW = vertW[vertW.size / 2]
        if (medW <= 0) return boxes
        // Hoisted out of the per-box walk: this is the one site on the page path
        // that fires *per box*, and a volatile read per box would be the gate
        // costing what it exists to save. The trim itself is unconditional — it
        // is geometry, not a diagnostic; only the two lines below it are gated.
        val verbose = verboseLog
        return boxes.map { box ->
            if (!isVerticalBox(box)) return@map box
            val w = box.width()
            if (w <= medW * 1.35f || w - medW < 12) return@map box
            val cut = findRubyGutterCut(box, bitmap) ?: return@map box
            if (cut <= box.left + 20 || cut >= box.right - 8) return@map box
            if (cut - box.left < (w * 0.4f).roundToInt()) return@map box
            if (verbose) {
                Log.d(TAG, "detect: rubyTrim ${box.width()}x${box.height()}@${box.left},${box.top} → w=${cut - box.left} (medW=$medW)")
                InferLog.add("rubyTrim w=$w→${cut - box.left} @${box.left},${box.top}")
            }
            JpDictRect(box.left, box.top, cut, box.bottom)
        }
    }

    /** Cut x (global coords) for a ruby-widened vertical box, or null to keep.
     * Gutter cut preferred; half-width fallback only on a thin spot. */
    private fun findRubyGutterCut(box: JpDictRect, bitmap: Bitmap): Int? {
        val x0 = box.left.coerceIn(0, bitmap.width - 1)
        val x1 = box.right.coerceIn(1, bitmap.width)
        val y0 = box.top.coerceIn(0, bitmap.height - 1)
        val y1 = box.bottom.coerceIn(1, bitmap.height)
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw < 24 || bh < 64) return null
        val px = IntArray(bw * bh)
        try {
            bitmap.getPixels(px, 0, bw, x0, y0, bw, bh)
        } catch (_: Exception) {
            return null
        }
        // Only the candidate crop crosses to the shared rust scan (the whole
        // bitmap would be ~2.6M boxed ints on a full-res page); the returned
        // cut is crop-local, so re-add the crop origin here.
        val localCut = gutterTrimFindRubyGutterCut(
            BoundingBox(x = 0, y = 0, w = bw, h = bh),
            px.toList(),
            bw,
            bh,
        ) ?: return null
        return x0 + localCut
    }

    /** #53: the same reading-order rule on [LineBox]es (a rotated Line sorts by
     *  its AABB — page-level orientation recovery is deliberately out of scope
     *  for this increment). */
    private fun sortDetectedLineBoxes(boxes: List<LineBox>): List<LineBox> {
        val horizontal = boxes.filter { !isVerticalLineBox(it) }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val vertical = boxes.filter { isVerticalLineBox(it) }
            .sortedWith(compareByDescending<LineBox> { it.rect.right }.thenBy { it.rect.top })
        return horizontal + vertical
    }

    /** Vertical-Line rule for a [LineBox]: the frame's own sizes decide for a
     *  rotated Line, the AABB for an axis-aligned one (same rule either way). */
    private fun isVerticalLineBox(box: LineBox): Boolean =
        box.quad?.let { RotatedGeometry.isVertical(it) } ?: isVerticalBox(box.rect)

    // ═════════════════════════════════════════════════════════════════════════
    //  Rec — single-pass batch + streaming + CTC + char boxes
    // ═════════════════════════════════════════════════════════════════════════

    data class PPOcrResult(
        val text: String,
        val alternatives: List<List<Pair<Char, Float>>>,
        val charCols: FloatArray,   // CTC timestep positions
        val seqLenTotal: Int,
        /**
         * Top-15 alternatives for EVERY timestep (including blanks), for cache
         * re-decode — kept **compact** (flat cells + row boundaries + a top-2
         * table), so the page path never expands 15 `Pair` + `Float` objects per
         * timestep. It *is* a `List<List<Pair<Char, Float>>>`, so every reader
         * that wants the full rows — `reDecodeLineResult`, the stitch paths,
         * `GapCandidates.generate` — sees exactly what it saw before; see
         * [TimestepTopK] for the shape and why.
         */
        val rawTopK: TimestepTopK = TimestepTopK.EMPTY,
    ) {
        /** The nested form of [rawTopK], materialised row by row on access. */
        val rawAlternatives: List<List<Pair<Char, Float>>> get() = rawTopK
    }

    /**
     * The compact decode result as a [PPOcrResult]: the flat cell list and its
     * row boundaries adopted as a [TimestepTopK] (never re-expanded), and the
     * per-character alternatives *by index* into those same rows.
     *
     * The alternatives are the raw rows at `altRows` — the decoder pushes the
     * row it built into both lists in the same iteration — so they are the same
     * cells, indexed rather than re-materialised, instead of a second crossing
     * of `topK` cells per character.
     */
    private fun CompactCtcDecodeResult.toPpoResult(): PPOcrResult {
        val topK = TimestepTopK.of(rawAlternatives, rawRows)
        // The per-character list is the raw row **by index**, read through `get`,
        // so the row is cached in `topK` and a later `rawAlternatives` read hands
        // back the very same `Pair`s — no second crossing of `topK` cells per
        // character, and only the *emitted* rows are materialised at all: the
        // other ~90% of timesteps never build a list on the page path. The one
        // mutable copy the gap filler grows in place is made by the emit path
        // (`processOneBatch`), exactly as before.
        val alts = altRows.map { t -> topK[t.toInt()] }
        return PPOcrResult(
            text = text,
            alternatives = alts,
            charCols = charCols.toFloatArray(),
            seqLenTotal = seqLenTotal.toInt(),
            rawTopK = topK,
        )
    }

    /**
     * The vertical-line punctuation map (#56, #63) applied once to a decoded
     * line, for the paths that hand the decode's rows to Kotlin before the
     * single-pass fold can (the long-line stitch paths merge alternatives across
     * chunks, and the merge keys on the character — so it has to see the raw
     * forms, exactly as it did when the map ran at emit).
     *
     * Only the stitch paths reach here: the single-pass path passes
     * `isVertical` into the decode, which folds the map while the rows are still
     * native. So re-slicing the compact table back into nested rows for
     * [JapaneseUtil.verticalPunctuationAlternatives] costs the page nothing.
     */
    private fun PPOcrResult.withVerticalPunctuation(vertical: Boolean): PPOcrResult =
        if (!vertical) this else PPOcrResult(
            text = JapaneseUtil.verticalPunctuation(text),
            alternatives = JapaneseUtil.verticalPunctuationAlternatives(alternatives)
                .map { it.toMutableList() },
            charCols = charCols,
            seqLenTotal = seqLenTotal,
            rawTopK = TimestepTopK.of(
                JapaneseUtil.verticalPunctuationAlternatives(rawAlternatives),
            ),
        )

    /** Run dynamic-width rec + CTC-decode on a `targetW×targetH` bitmap that is
     * already the net's input — the seam between *preparing* the input and
     * *running* the net, so a caller can fill that bitmap however it likes
     * ([drawRecInput]'s one draw, the historical `createScaledBitmap`, or a
     * stitch chunk) and share the tensor build, the top-K check and the decode.
     *
     * Reads only `[0,targetW) × [0,targetH)` of [bitmap], so a pooled buffer
     * wider than this line's net input is safe. Model width snaps up to
     * mult-of-8 with zero padding; `actualSeqLen = ceil(targetW/8)` trims the
     * padding timesteps. Returns null when inference fails (callers fall back:
     * batch emits empty, stitch falls through to crush). Does NOT recycle
     * [bitmap] — callers own their bitmaps.
     *
     * [isVertical] is the *line's* orientation, not the input's aspect: it is the
     * flag the vertical punctuation fold (#56, #63) keys on, so it is passed
     * into the decode rather than applied to its output. `false` from the
     * stitch chunk paths, which merge alternatives across chunks and therefore
     * have to see the raw characters (they map the line once at the end). */
    private suspend fun inferRecBitmap(bitmap: Bitmap, targetW: Int, targetH: Int, engine: RecNcnn? = null, isVertical: Boolean = false): PPOcrResult? {
        coroutineContext.ensureActive()
        val recNcnn = engine ?: recDynNcnn ?: return null
        val modelW = ((targetW + 7) / 8) * 8
        val tPrep = System.nanoTime()
        val pixels = IntArray(targetW * targetH)
        bitmap.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        val inputFloats = buildRecInput(pixels, targetW, targetH, modelW)
        recPrepTensorNanos += System.nanoTime() - tPrep
        val seqLen = modelW / REC_STRIDE
        val actualSeqLen = maxOf(1, ceil(targetW / REC_STRIDE.toFloat()).toInt())
        // Preferred path (#42): native top-15 per timestep — downloads
        // seqLen*30 floats instead of seqLen*13193 (up to ~880x smaller).
        // Falls back to full logits + Java top-15 if the native entry is missing.
        val packed = try { recNcnn.inferTopK(inputFloats, modelW, targetH) } catch (_: UnsatisfiedLinkError) { null }
        // The packed size alone can only catch a *smaller* top-K layout, so also check that
        // every class id the native reports is inside the head the remap describes: a
        // native/model width disagreement otherwise decodes into plausible-looking garbage,
        // one character per timestep (the #44 re-prune did exactly that against a hardcoded
        // width in ncnn_jni.cpp). Ids sit at even offsets; the odd entries are logits.
        val packedSized = packed != null && packed.size == seqLen * TOP_K * 2
        val idsInRange = packedSized && recNumOutputs > 0 &&
            (0 until seqLen * TOP_K).all { k -> packed!![k * 2].toInt() in 0 until recNumOutputs }
        if (packedSized && !idsInRange) {
            Log.w(TAG, "recNcnn w$modelW topK class id outside head width $recNumOutputs — full-logits fallback")
            InferLog.add("rec w=$modelW topK ID OUT OF RANGE head=${recNumOutputs} — native/model mismatch")
        }
        if (packedSized && idsInRange) {
            // Native emits descending top-15 with lowest-id-wins ties; entry 0
            // is the argmax. Trim padded model steps, then cross the packed line
            // once: no per-class FFI and no Kotlin raw-alternatives materialisation.
            val actualPacked = packed.copyOf(actualSeqLen * TOP_K * 2)
            return ctcDecodeTopK(actualPacked, actualSeqLen, isVertical)
        }
        if (packed != null) Log.w(TAG, "recNcnn w$modelW topK bad size ${packed.size} — full-logits fallback")
        if (packed != null) InferLog.add("rec w=$modelW topK BAD size=${packed.size} expect=${seqLen * TOP_K * 2}")
        val flatOutput = recNcnn.infer(inputFloats, modelW, targetH) ?: run {
            Log.e(TAG, "recNcnn w$modelW infer null")
            InferLog.add("rec w=$modelW infer NULL")
            return null
        }
        // Head width comes from the loaded remap; this is also the one place the
        // model and the remap are checked against each other at runtime.
        val numOut = recNumOutputs
        if (flatOutput.size != seqLen * numOut) {
            Log.e(TAG, "recNcnn w$modelW bad output ${flatOutput.size} vs ${seqLen * numOut}")
            InferLog.add("rec w=$modelW BAD out=${flatOutput.size} expect=${seqLen * numOut}")
            return null
        }
        val cropLogits = Array(actualSeqLen) { t ->
            FloatArray(numOut) { c -> flatOutput[t * numOut + c] }
        }
        return ctcDecode(cropLogits, actualSeqLen, numOut, actualSeqLen, isVertical)
    }

    /** The historical per-line transform: `createScaledBitmap` to the net's
     *  input size, then [inferRecBitmap]. Kept as the reference implementation
     *  and as the path a portrait crop and the stitch chunks still take; the
     *  horizontal single-pass path uses [drawRecInput] instead.
     *
     *  [isVertical] forwards to [inferRecBitmap] for the same reason; the
     *  stitch chunks keep the default `false`. */
    private suspend fun inferResizedRec(src: Bitmap, targetW: Int, targetH: Int, engine: RecNcnn? = null, isVertical: Boolean = false): PPOcrResult? {
        coroutineContext.ensureActive()
        val t0 = System.nanoTime()
        val resized = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        recPrepBitmapNanos += System.nanoTime() - t0
        return try {
            inferRecBitmap(resized, targetW, targetH, engine, isVertical)
        } finally {
            resized.recycle()
        }
    }

    /**
     * One line's recognition source: the page, plus the clamped rect
     * [processOneBatch] would have cut as its crop (or, for a #53 rotated
     * Line, the unrotate [frame] the crop *was*). A source is a *description*,
     * not a bitmap: a horizontal line never materialises its crop, it reads the
     * rect's pixels straight out of the page — into the char-box evidence and,
     * on the one-draw path, into the net's input.
     */
    private class RecSource(
        val page: Bitmap,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        /** Non-null for a rotated Line: its upright local frame, which *is* the
         *  crop (the char-box stage works in that frame). */
        val frame: Bitmap? = null,
        /** The **line's** orientation — [isVerticalLineBox] on the detector box,
         *  quad first — carried here because the decode needs it and the source
         *  is what a decode is asked to run. Default `false` so every existing
         *  construction site stays a horizontal line.
         *
         *  Deliberately **not** [isPortrait]: that is a property of the rect the
         *  net must turn 90°, which is the opposite question. A rotated Line's
         *  source is already its upright frame, so [isPortrait] is false while
         *  the line is very much vertical. */
        val isVerticalLine: Boolean = false,
    ) {
        /** A portrait crop, i.e. one the net must see turned 90°. */
        val isPortrait: Boolean get() = h >= w * 3 / 2

        /**
         * The chain's crop, exactly as `Bitmap.createBitmap(page, rect)` built
         * it: the reference the transforms below are measured against, and the
         * input the long-line stitch path chunks. Null if the allocation is
         * refused, which drops the line — the behaviour the crop-creating code
         * this replaces had.
         */
        fun crop(): Bitmap? = frame ?: try {
            Bitmap.createBitmap(page, x, y, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "rec prep: crop of ${w}x$h@$x,$y failed", e)
            null
        }

        /** The reference chain's next step for a portrait crop: the 270° turn,
         *  a whole-pixel permutation of [crop].
         *
         *  Crop and turn are **one** `createBitmap(page, rect, matrix, filter)`
         *  call — the same primitive the two-step chain makes twice, with the
         *  crop's rect as the src rect instead of a whole-image src — and that
         *  is bit-exact: 0 differing pixels on all 47 portrait lines of the
         *  three bench fixtures (`RecDrawParityTest
         *  .fusedCropAndRotateIsBitExact`). The reason the *scale* cannot be
         *  folded the same way is [drawRecInput]: a 90° turn carries no scale,
         *  so there is no fixed-point inverse for the rect's origin to shift,
         *  while a scale has one and rounds it.
         *
         *  Saves an allocation and a recycle per portrait line, and portrait
         *  lines are most of a text page (11 of 16 on `bookpage.png`, 34 of 70
         *  on `f5d7d08735383899.jpg`). Falls back to the two-step chain if the
         *  fused call is refused, and returns null (drop the line) if that is
         *  refused too. */
        fun uprightCrop(): Bitmap? {
            val t0 = System.nanoTime()
            if (!isPortrait) {
                val c = crop()
                recPrepBitmapNanos += System.nanoTime() - t0
                return c
            }
            val out = try {
                Bitmap.createBitmap(page, x, y, w, h, android.graphics.Matrix().apply { postRotate(270f) }, true)
            } catch (e: Exception) {
                Log.e(TAG, "rec prep: fused rotate-270 failed for ${w}x$h crop, falling back", e)
                val c = crop() ?: return null.also { recPrepBitmapNanos += System.nanoTime() - t0 }
                try {
                    Bitmap.createBitmap(c, 0, 0, c.width, c.height, android.graphics.Matrix().apply { postRotate(270f) }, true)
                } catch (e2: Exception) {
                    Log.e(TAG, "rec prep: rotate-270 failed for ${w}x$h crop", e2)
                    c
                }
            }
            recPrepBitmapNanos += System.nanoTime() - t0
            return out
        }

        /** The crop's own pixels, for the char-box evidence stage. Read from
         *  the page rather than from a crop: the two are the same pixels — a 1:1
         *  `drawBitmap(page, rect)` is identical to `createBitmap(page, rect)` on
         *  every line of all three fixtures (`RecDrawParityTest
         *  .evidenceReadFromPageIsBitExact`: 0 differing pixels on 18 + 37 + 72
         *  lines) — and reading the rect off the page is ~3x cheaper than
         *  reading a freshly cut crop (4.6 → 1.4 ms/page on `bookpage.png`). */
        fun evidencePixels(): IntArray? {
            val t0 = System.nanoTime()
            return try {
                val arr = IntArray(w * h)
                if (frame != null) frame.getPixels(arr, 0, w, 0, 0, w, h)
                else page.getPixels(arr, 0, w, x, y, w, h)
                recPrepEvidenceNanos += System.nanoTime() - t0
                arr
            } catch (_: Exception) {
                recPrepEvidenceNanos += System.nanoTime() - t0
                null
            }
        }
    }

    /**
     * The collapsed per-line transform: the source rect drawn **once**, by a
     * single `Canvas.drawBitmap`, straight into the `sq × 48` bitmap the net
     * consumes — no crop, no rotate, no resize bitmap, nothing to recycle. It
     * buys 8.1 ms/page on `bookpage.png` (18 lines) and *costs* 3.2 and 5.3
     * ms/page on the two line-dense screenshots, and it is not bit-exact, so it
     * does not ship: see [useFusedRecInput] for the measured gate.
     *
     * Only meaningful for a **horizontal** source. A portrait crop still needs
     * its 90° turn as a real bitmap ([uprightCrop]): folding the rotation into
     * this draw moves the sampling grid as well as the scale, which shows up as
     * thousands of pixels differing by up to ±75 levels.
     *
     * The destination is pooled per thread and only `[0,sq) × [0,48)` is ever
     * read back, so a buffer wider than this line's net input is fine. It is
     * repainted from a defined background first: an uncovered destination pixel
     * then reads as black — exactly what the freshly allocated bitmap
     * `createScaledBitmap` used to hand over — instead of silently carrying the
     * previous line's ink into the net.
     */
    private fun drawRecInput(src: RecSource, contentW: Int, targetH: Int): Bitmap {
        val t0 = System.nanoTime()
        val (bmp, canvas) = recInputBuffer(contentW, targetH)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            src.page,
            android.graphics.Rect(src.x, src.y, src.x + src.w, src.y + src.h),
            android.graphics.RectF(0f, 0f, contentW.toFloat(), targetH.toFloat()),
            recInputPaint,
        )
        recPrepBitmapNanos += System.nanoTime() - t0
        return bmp
    }

    /** Per-thread pooled `≥contentW × targetH` destination + its Canvas. */
    private fun recInputBuffer(contentW: Int, targetH: Int): Pair<Bitmap, android.graphics.Canvas> {
        val cur = tlRecInput.get()
        if (cur != null && !cur.first.isRecycled && cur.first.width >= contentW && cur.first.height == targetH) {
            return cur
        }
        // `hasAlpha = false`, the allocation `Bitmap.createBitmap(src, …, m,
        // filter)` makes for an opaque source (its decompiled body passes
        // `source.hasAlpha()` to the destination factory).
        val bmp = Bitmap.createBitmap(contentW, targetH, Bitmap.Config.ARGB_8888, false)
        val pair = bmp to android.graphics.Canvas(bmp)
        tlRecInput.set(pair)
        return pair
    }

    /**
     * Run CTC recognition over dynamic-width ncnn rec.
     *
     * Portrait crops rotate 270° so the model always sees horizontal text.
     * Lines wider than 2000 (at 48px height) go through the stitch paths;
     * everything else takes the single-pass path below with live [recSquish]
     * applied pre-inference (#24). Cooperative cancellation: checks
     * coroutineContext.isActive.
     */
    /** One batch end-to-end: sources, inference on [engine], emit, recycle.
     * Cooperative cancellation via ensureActive; a rotated Line's frame is
     * always recycled. */
    private suspend fun processOneBatch(
        batchIdx: Int,
        batch: List<Job>,
        engine: RecNcnn,
        bitmap: Bitmap,
        mainHandler: android.os.Handler,
        onLinesRecognized: (List<Pair<Int, LineResult>>) -> Unit,
        fanout: Int = REC_FANOUT,
    ) {
        coroutineContext.ensureActive()
        val tBatch = System.nanoTime()
        // Describe each line's rec input; only a rotated Line (#53) allocates,
        // because its unrotate warp *is* the frame the char boxes work in. An
        // axis-aligned Line stays a rect until the draw that consumes it.
        val sourcesWithJobs = batch.mapNotNull { job ->
            val src = if (job.box.isRotated) {
                val frame = warpRotatedCrop(bitmap, job.box.quad!!)
                if (frame == null) null
                else RecSource(frame, 0, 0, frame.width, frame.height, frame, job.isVertical)
            } else {
                val rect = job.box.rect
                val cropX = maxOf(rect.left, 0)
                val cropY = maxOf(rect.top, 0)
                val cropW = minOf(bitmap.width - cropX, rect.width()).coerceAtLeast(1)
                val cropH = minOf(bitmap.height - cropY, rect.height()).coerceAtLeast(1)
                if (cropW < 4 || cropH < 4) null
                else RecSource(bitmap, cropX, cropY, cropW, cropH, null, job.isVertical)
            }
            if (src == null) return@mapNotNull null
            if (src.w < 4 || src.h < 4) { src.frame?.recycle(); return@mapNotNull null }
            job to src
        }
        if (sourcesWithJobs.isEmpty()) return
        val sources = sourcesWithJobs.map { it.second }
        val batchJobs = sourcesWithJobs.map { it.first }
        try {
            // Early exit if cancelled before batch
            if (!coroutineContext.isActive) {
                sources.forEach { it.frame?.let { f -> try { f.recycle() } catch (_: Exception) {} } }
                return
            }
            // Stream per line as each infer completes (completion order, not
            // batch order) — same 4-concurrent throughput, faster first
            // result. Results stay keyed by job idx. #21
            var doneLines = 0
            val emitLine = emit@{ index: Int, result: PPOcrResult ->
                // Aligned with `sources`: a Line whose source could not be built
                // is not in the list, so the batch's own order cannot address it.
                // (The unused `batch` lookup this replaces mis-keyed every result
                // after a dropped crop.)
                val job = batchJobs.getOrNull(index) ?: return@emit
                if (result.text.isEmpty()) return@emit

                // Crop pixels for idea-4 snapping / CAP ink evidence (read from
                // the source rect, so it needs no crop to be alive; null-safe when
                // sizes mismatch).
                val src = sources.getOrNull(index)
                var snapPx: IntArray? = null
                var snapW = 0
                var snapH = 0
                val needCropPixels = BOX_LAYOUT_MODE == BOX_SNAP || capPlacement
                if (needCropPixels && src != null && src.w >= 8 && src.h >= 8) {
                    snapW = src.w; snapH = src.h
                    snapPx = src.evidencePixels()
                }
                // Vertical punctuation (#56, #63) already ran inside the decode,
                // keyed on the same `job.isVertical` (see `inferRecBitmap` /
                // `ctcDecodeTopK`). Mapping the rows here as well crossed every
                // character of the text and of both alternative lists a second
                // time per vertical line, for the same bytes.
                val recText = result.text
                val recAlts = result.alternatives.map { it.toMutableList() }
                // The compact per-timestep table, handed on as-is: CAP's `steps`
                // read its top-2 as the table's own `GapCell`s (see
                // `TimestepTopK.capCellRows`) and `LineResult.rawAlternatives`
                // materialises the full rows only when a rare reader asks.
                val recRaw = result.rawTopK
                val box = job.box
                val quad = box.quad
                val cropW: Int
                val cropH: Int
                val cropX: Int
                val cropY: Int
                val charBoxes: List<JpDictRect>
                if (quad != null) {
                    // #53: char boxes are computed in the Line's own upright
                    // frame (localCropW/H, the same size the warp produced),
                    // then placed back into source pixels as AABBs. The
                    // renderer rotates the glyphs by the frame's tilt.
                    cropW = localCropW(quad)
                    cropH = localCropH(quad)
                    cropX = 0
                    cropY = 0
                    val local = computeCharBoxes(
                        recText, result.charCols, result.seqLenTotal,
                        cropX, cropY, cropW, cropH,
                        job.isVertical,
                        snapPx, snapW, snapH,
                        recRaw,
                    )
                    charBoxes = local.map { quad.mapLocalRect(it) }
                } else {
                    cropW = box.rect.width()
                    cropH = box.rect.height()
                    cropX = box.rect.left
                    cropY = box.rect.top
                    charBoxes = computeCharBoxes(
                        recText, result.charCols, result.seqLenTotal,
                        cropX, cropY, cropW, cropH,
                        job.isVertical,
                        snapPx, snapW, snapH,
                        recRaw,
                    )
                }

                // Vertical lines keep horizontal chars end-to-end (#47): the
                // overlay renderer applies the font's vert subs at draw time.
                val finalText = recText
                val finalAlts = recAlts

                val lineResult = LineResult(
                    text = finalText,
                    charBoxes = charBoxes,
                    alternatives = finalAlts,
                    isVertical = job.isVertical,
                    rawAlternatives = recRaw,
                    seqLenTotal = result.seqLenTotal,
                    cropW = cropW,
                    cropH = cropH,
                    cropX = cropX,
                    cropY = cropY,
                    charCols = result.charCols,
                    quad = quad,
                )
                doneLines++
                if (verboseLog) {
                    InferLog.add("line idx=${job.idx} len=${lineResult.text.length} vert=${lineResult.isVertical}")
                }
                mainHandler.post { onLinesRecognized(listOf(job.idx to lineResult)) }
            }
            recognizePpocrBatch(sources, engine, fanout) { index, result -> emitLine(index, result) }
            val elapsed = (System.nanoTime() - tBatch) / 1_000_000
            Log.d(TAG, "Batch $batchIdx ${batch.size} jobs → $doneLines lines in ${elapsed}ms")
            InferLog.add("batch $batchIdx jobs=${batch.size} lines=$doneLines ${elapsed}ms")
            // Per-batch recycle: only a rotated Line still holds a bitmap.
            sources.forEach { it.frame?.let { f -> try { f.recycle() } catch (_: Exception) {} } }
        } catch (e: Exception) {
            Log.e(TAG, "Batch $batchIdx failed", e)
            // Ensure a rotated Line's frame is recycled even on failure
            try {
                sources.forEach { it.frame?.let { f -> f.recycle() } }
            } catch (_: Exception) {}
        }
    }

    /** #53: the upright crop size in whole pixels for [quad]. The warp bitmap
     *  and the char-box frame must agree, so both go through here. */
    private fun localCropW(quad: JpDictQuad): Int =
        quad.localWidth.roundToInt().coerceAtLeast(4)

    private fun localCropH(quad: JpDictQuad): Int =
        quad.localHeight.roundToInt().coerceAtLeast(4)

    /** #53: the unrotate — draw the source through the frame's inverse into an
     *  upright `localW × localH` bitmap. `setPolyToPoly` maps the frame's four
     *  corners to the upright rect, so the same seam already accepts a
     *  perspective fit later (a quad, not just a rotated rect). Returns null on
     *  a degenerate mapping or an allocation failure; the caller drops the Line
     *  rather than recognising a mis-framed crop. */
    private fun warpRotatedCrop(src: Bitmap, quad: JpDictQuad): Bitmap? {
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
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            )
            out
        } catch (e: Exception) {
            Log.e(TAG, "warpRotatedCrop failed", e)
            null
        }
    }

    /**
     * @param fanout max concurrent line infers in this batch (waves run
     * sequentially; completion-order streaming holds within a wave).
     */
    private suspend fun recognizePpocrBatch(
        sources: List<RecSource>,
        engine: RecNcnn? = null,
        fanout: Int = REC_FANOUT,
        onEach: ((index: Int, result: PPOcrResult) -> Unit)? = null,
    ): List<PPOcrResult> {
        coroutineContext.ensureActive()
        val numCrops = sources.size
        if (numCrops == 0 || ppocrVocab.isEmpty() || (engine ?: recDynNcnn) == null) return emptyList()

        val targetH = REC_TARGET_H

        val ordered = arrayOfNulls<PPOcrResult>(numCrops)
        for (wave in (0 until numCrops).chunked(fanout.coerceAtLeast(1))) {
            coroutineContext.ensureActive()
            coroutineScope {
            val deferreds = wave.map { ci ->
                async(Dispatchers.Default) {
                    coroutineContext.ensureActive()
                    val src = sources[ci]
                    val cw = src.w; val ch = src.h
                    if (cw < 4 || ch < 4) return@async null as PPOcrResult?

            // A portrait crop is turned 270° so the net sees horizontal text, so
            // the net's view of the line is (ch × cw); a horizontal one is
            // already the right way round. Both are pure arithmetic on the
            // source's size — no bitmap yet, which is the point: the single-pass
            // path never allocates a crop.
            val rw = if (src.isPortrait) ch else cw
            val rh = if (src.isPortrait) cw else ch
            // ——— Long-line split for extreme aspects (rw*48/rh>2000) — PP-OCR crush fix ———
            // Exact-width inference validated to aspect ~1992 on tategaki ebook lines (#24);
            // cap + gate rounded up to an 8-divisible 2000. Very long lines crush timesteps.
            // Split into overlapping exact-width chunks (10% fallback overlap), then stitch.
            // Threshold 2000 to avoid over-splitting normal lines while fixing extremes.
            val isLongHoriz = rw >= rh * 3 / 2 && (rw.toFloat() * targetH / rh.toFloat() > LONG_LINE_GATE)
            val isLongVert = rh >= rw * 3 / 2 && (rh.toFloat() * targetH / rw.toFloat() > LONG_LINE_GATE)
            if (isLongHoriz || isLongVert) {
                val upright = src.uprightCrop() ?: return@async null
                val stitched = if (isLongHoriz) {
                    recognizeAndStitchLongHoriz(upright, targetH, engine, src.isVerticalLine)
                } else {
                    recognizeAndStitchLongVert(upright, targetH, engine, src.isVerticalLine)
                }
                if (upright !== src.frame) upright.recycle()
                if (stitched != null) return@async stitched
                Log.w(TAG, "long-line stitch failed rw=$rw rh=$rh — falling through to crush")
            }
            // Dynamic width (#23): exact targetW capped at LONG_LINE_GATE (validated #24),
            // then squish (#24) applied pre-inference; stitch paths skip squish.
            // Model width snaps to mult-of-8 (≤7px pad).
            val targetW = maxOf(4, minOf(LONG_LINE_GATE,
                (rw.toFloat() * targetH / rh.toFloat()).roundToInt()
            ))
            val sqTarget = squishTarget(targetW, recSquish).let {
                // Crush floor (#48): squish must leave >= 32 timesteps; short
                // dense lines (e.g. ruby-widened vertical crops at targetW 363
                // -> 23 steps for 16 chars) keep full resolution instead.
                if (it / REC_STRIDE < 32) targetW else it
            }
            if (verboseLog) {
                InferLog.add("crop rw=$rw rh=$rh targetW=$targetW sq=$sqTarget seq=${sqTarget / REC_STRIDE}")
            }
            // The collapse: a horizontal line needs no crop and no rotate, so
            // the source rect goes into the net's own bitmap in one draw. A
            // portrait line, a long line's chunks and the parity toggle's `off`
            // keep the historical chain, which is the bit-exact reference.
            val result = if (useFusedRecInput && !src.isPortrait) {
                inferRecBitmap(
                    drawRecInput(src, sqTarget, targetH), sqTarget, targetH, engine, src.isVerticalLine
                )
            } else {
                val upright = src.uprightCrop() ?: return@async null
                try {
                    inferResizedRec(upright, sqTarget, targetH, engine, src.isVerticalLine)
                } finally {
                    if (upright !== src.frame) upright.recycle()
                }
            }
            if (result == null) {
                Log.e(TAG, "recDynNcnn w$sqTarget infer failed — skip crop")
                return@async null
            }
                    return@async result
                }
            }
            // Await in completion order so callers can stream per-line results (#21, §D5):
            // the returned list stays index-aligned; onEach fires on the selecting
            // worker and recognizeStreaming re-posts to the main thread.
            val pending = deferreds.mapIndexed { wi, d -> d to wave[wi] }.toMap().toMutableMap()
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val (done, res) = select<Pair<Deferred<PPOcrResult?>, PPOcrResult?>> {
                    pending.keys.forEach { d -> d.onAwait { d to it } }
                }
                val ci = pending.remove(done) ?: continue
                val final = res ?: PPOcrResult("", emptyList(), floatArrayOf(), 0)
                ordered[ci] = final
                if (res != null) {
                    // NEVER silent: an exception here (in emitLine: placement,
                    // LineResult build, vertical punctuation…) used to vanish
                    // and the line rendered blank on device with no trace in
                    // logcat — the "detecting all lines, recognizing half" bug.
                    try {
                        onEach?.invoke(ci, res)
                    } catch (e: Exception) {
                        Log.e(TAG, "emit failed for crop $ci", e)
                    }
                }
            }
            } // end wave scope
        } // end waves
        return ordered.map { it ?: PPOcrResult("", emptyList(), floatArrayOf(), 0) }
    }

    // ——— Long-line stitch (lines wider than 2000 @48px; CTC crush fix) ———
    // Split into overlapping exact-width chunks (≤480 targetW each), infer each
    // via inferResizedRec UNSQUISHED, then stitch (Phase 2). Chunks overlap by
    // anchor (below) with a 10% fallback step.
    /** One inferred chunk: decoded text plus the geometry to place it globally.
     * `chunkW`/`offsetX`/`offsetY` are full-res source pixels; `charCols` are
     * chunk-local timesteps; `actualSeqLen` trims mult-of-8 padding. */
    private data class ChunkInfo(
        val text: String,
        val charCols: FloatArray,
        val altsPerChar: List<List<Pair<Char, Float>>>,
        val rawAltsPerTimestep: List<List<Pair<Char, Float>>>,
        val actualSeqLen: Int,
        val targetW: Int,
        val chunkW: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    // Stitch chunks stay UNSQUISHED at full resolution: anchor/stitch geometry
    // (localXLeft, offsetGeomT, totalSeqLen) is all full-res timesteps, while the
    // single-pass batch path applies recSquish pre-inference. CTC tolerates the
    // squish for JP prose (holds to 0.5, knee at 0.33); narrow Latin glyphs go
    // first (accepted: JP is the target). #24
    /** Stitch a long horizontal line (rw*48/rh > 2000).
     *
     * Phase 1 chunks left→right: each chunk's next start anchors on its
     * second-to-last decoded char center (`localXLeft = (t+0.5)/seqLen*cw`),
     * minus a 10%-of-height margin; degenerate anchors fall back to a 90% step.
     * Phase 2 aligns chunks by identical timestep size (`rh/6` px): for each new
     * Phase 2 aligns chunks by identical timestep size (`rh/6` px): for each new
     * chunk, the best pair in the [STITCH_WINDOW] overlap window with center
     * distance ≤ [STITCH_MAX_DIST_PX] and prediction overlap ≥ [STITCH_MIN_PRED]
     * wins (`score = 0.3·(1-dist/30) + 0.7·pred`); the winner's alternatives merge
     * via [interleaveAlternatives] and later chars re-base onto it. No winner →
     * append chars past the last global center (+10px), or single-char chunks
     * unconditionally; total stall → +1-timestep fallback offset. Double spaces
     * collapse at the end. `charCols` stay global timesteps scaled by
     * `totalSeqLen = ceil(rw*48/rh/8)`.
     *
     *  [isVertical] is the *line's* orientation, and it is applied **here**
     *  rather than folded into the chunk decodes: [interleaveAlternatives] keys
     *  the merge on the character, so a chunk decoded already folded would merge
     *  `?` with `？` into one entry where the old code kept two. Chunks decode
     *  raw; each return maps the stitched line exactly once, where the map used
     *  to run at emit. */
    private suspend fun recognizeAndStitchLongHoriz(
        rotated: Bitmap, targetH: Int, engine: RecNcnn? = null, isVertical: Boolean = false
    ): PPOcrResult? {
        coroutineContext.ensureActive()
        val rw = rotated.width; val rh = rotated.height
        val scale = targetH.toFloat() / rh.toFloat()
        val maxChunkW = (480 / scale).toInt().coerceAtLeast(64)
        if (maxChunkW <= 0) return null
        val chunkMargin = (rh * 0.1f).toInt().coerceAtLeast(2)

        // ——— Phase 1: chunk with anchor-driven nextX (second-to-last char) ———
        val chunks = mutableListOf<ChunkInfo>()
        var x = 0
        while (x < rw) {
            coroutineContext.ensureActive()
            val w = minOf(maxChunkW, rw - x)
            if (w < 16) break
            val chunkBmp = Bitmap.createBitmap(rotated, x, 0, w, rh)
            val cw = chunkBmp.width; val ch = chunkBmp.height
            // Preserve aspect w → targetW via cw*48/ch (not stretch); cap at CHUNK_TARGET_MAX.
            val targetW = minOf(CHUNK_TARGET_MAX, (cw.toFloat() * targetH / ch.toFloat()).roundToInt().coerceAtLeast(4))
            val decoded = inferResizedRec(chunkBmp, targetW, targetH, engine)
            chunkBmp.recycle()
            if (decoded == null) { Log.e(TAG, "recNcnn chunk w$targetW infer null"); return null }
            val actualSeqLen = decoded.seqLenTotal
            val rawAlts = decoded.rawAlternatives
            chunks.add(ChunkInfo(decoded.text, decoded.charCols, decoded.alternatives, rawAlts, actualSeqLen, targetW, cw, x, 0))
            if (x + w >= rw) break
            // Anchor on the second-to-last char: it is fully observed, the last
            // char may be cut by the chunk edge.
            val txt = decoded.text
            if (txt.isNotEmpty() && decoded.charCols.isNotEmpty()) {
                val anchorIdx = if (txt.length >= 2) txt.length - 2 else 0
                val anchorT = decoded.charCols.getOrNull(anchorIdx) ?: decoded.charCols.last()
                // localXLeft in chunk pixel coords: (t+0.5)/actualSeqLen * cw (center)
                val localXLeft = ((anchorT + 0.5f) / actualSeqLen.toFloat()) * cw
                val nextX = (x + localXLeft.toInt() - chunkMargin).coerceAtLeast(0)
                if (nextX <= x || nextX >= x + w - 10) {
                    x += (w * 0.9f).toInt().coerceAtLeast(16)
                } else {
                    x = nextX
                }
            } else {
                x += (w * 0.9f).toInt().coerceAtLeast(16)
            }
        }
        if (chunks.isEmpty()) return null
        if (chunks.size == 1) {
            val c = chunks[0]
            if (verboseLog) {
                Log.d(TAG, "long-line stitch horiz rw=$rw rh=$rh chunks=1 stitchedLen=${c.text.length} seqLen=${c.actualSeqLen} text=${c.text.take(40)}")
            }
            return PPOcrResult(c.text, c.altsPerChar, c.charCols, c.actualSeqLen, TimestepTopK.of(c.rawAltsPerTimestep))
                .withVerticalPunctuation(isVertical)
        }
        // ——— Phase 2: stitch via anchor alignment with identical timestep size ———
        // Each timestep is rh/6 px source (48px height / stride 8); both chunks
        // share the size. The second chunk's anchor lands exactly over the
        // first's, then positions progress normally; totalSeqLen rescales to the
        // full line (ceil(rw*48/rh/8)).
        val timestepPx = rh.toFloat() / 6f
        val totalSeqLen = maxOf(1, ceil(rw.toFloat() * targetH.toFloat() / rh.toFloat() / REC_STRIDE.toFloat()).toInt())
        val chunkGlobalCenters = chunks.map { ci ->
            ci.charCols.map { t -> ci.offsetX.toFloat() + (t + 0.5f) * timestepPx }
        }
        var stitchedText = StringBuilder(chunks[0].text)
        var stitchedAlts = chunks[0].altsPerChar.toMutableList()
        var stitchedCols = chunks[0].charCols.toMutableList()
        var stitchedGlobal = chunkGlobalCenters[0].toMutableList()
        val stitchedRawAll = chunks[0].rawAltsPerTimestep.toMutableList()
        for (i in 1 until chunks.size) {
            val curr = chunks[i]
            val currGlobal = chunkGlobalCenters[i]
            val currAlts = curr.altsPerChar
            if (currAlts.isEmpty() || stitchedAlts.isEmpty()) {
                val lastPx = stitchedGlobal.lastOrNull() ?: -100f
                val offsetGeomT = curr.offsetX.toFloat() * 6f / rh.toFloat()
                for (j in currAlts.indices) {
                    val candT = offsetGeomT + curr.charCols[j]
                    val candPx = currGlobal.getOrNull(j) ?: continue
                    if (candPx > lastPx + STITCH_APPEND_GAP_PX) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(candT)
                        stitchedGlobal.add(candPx)
                    }
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
                continue
            }
            var bestPrevIdx = -1
            var bestCurrIdx = -1
            var bestScore = -1f
            val pStart = maxOf(0, stitchedAlts.size - STITCH_WINDOW)
            val cEnd = minOf(currAlts.size, STITCH_WINDOW)
            for (pIdx in stitchedAlts.size - 1 downTo pStart) {
                val pGC = stitchedGlobal[pIdx]
                for (cIdx in 0 until cEnd) {
                    val cGX = currGlobal[cIdx]
                    val dist = abs(pGC - cGX)
                    if (dist > STITCH_MAX_DIST_PX) continue
                    val pred = comparePredictionVectors(stitchedAlts[pIdx], currAlts[cIdx])
                    if (pred < STITCH_MIN_PRED) continue
                    val score = (1f - dist / STITCH_MAX_DIST_PX) * 0.3f + pred * 0.7f
                    if (score > bestScore) {
                        bestScore = score
                        bestPrevIdx = pIdx
                        bestCurrIdx = cIdx
                    }
                }
            }
            if (bestPrevIdx != -1) {
                val merged = interleaveAlternatives(stitchedAlts[bestPrevIdx], currAlts[bestCurrIdx]).toMutableList()
                val toKeep = bestPrevIdx + 1
                while (stitchedText.length > toKeep) {
                    stitchedText.deleteCharAt(stitchedText.length - 1)
                    stitchedAlts.removeAt(stitchedAlts.size - 1)
                    stitchedCols.removeAt(stitchedCols.size - 1)
                    stitchedGlobal.removeAt(stitchedGlobal.size - 1)
                }
                stitchedAlts[bestPrevIdx] = merged
                val offsetT = stitchedCols[bestPrevIdx] - curr.charCols[bestCurrIdx]
                val offsetPx = stitchedGlobal[bestPrevIdx] - currGlobal[bestCurrIdx]
                for (j in bestCurrIdx + 1 until currAlts.size) {
                    val ch = curr.text.getOrNull(j) ?: continue
                    if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                    stitchedText.append(ch)
                    stitchedAlts.add(currAlts[j])
                    stitchedCols.add(curr.charCols[j] + offsetT)
                    stitchedGlobal.add(currGlobal[j] + offsetPx)
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
            } else {
                val lastPx = stitchedGlobal.lastOrNull() ?: -100f
                var appended = 0
                for (j in currAlts.indices) {
                    val candPx = currGlobal[j]
                    if (candPx > lastPx + STITCH_APPEND_GAP_PX || (appended == 0 && currAlts.size == 1)) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                        val candT = curr.offsetX.toFloat() * 6f / rh.toFloat() + curr.charCols[j]
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(candT)
                        stitchedGlobal.add(candPx)
                        appended++
                    }
                }
                if (appended == 0) {
                    val lastT = stitchedCols.lastOrNull() ?: 0f
                    val lastPx2 = stitchedGlobal.lastOrNull() ?: 0f
                    val fallbackOffsetT = (lastT + 1f) - curr.charCols[0]
                    val fallbackOffsetPx = (lastPx2 + timestepPx) - currGlobal[0]
                    for (j in currAlts.indices) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(curr.charCols[j] + fallbackOffsetT)
                        stitchedGlobal.add(currGlobal[j] + fallbackOffsetPx)
                    }
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
            }
        }
        // Final scaling: charCols are global timesteps with identical size (rh/6). Scale to bbox via totalSeqLen.
        val finalTextRaw = stitchedText.toString()
        var finalText = finalTextRaw
        while (finalText.contains("  ")) finalText = finalText.replace("  ", " ")
        if (verboseLog) {
            Log.d(TAG, "long-line stitch horiz rw=$rw rh=$rh chunks=${chunks.size} stitchedLen=${finalText.length} seqLen=$totalSeqLen text=${finalText.take(40)}")
        }
        return PPOcrResult(finalText, stitchedAlts, stitchedCols.toFloatArray(), totalSeqLen, TimestepTopK.of(stitchedRawAll))
            .withVerticalPunctuation(isVertical)
    }

    /** Stitch a long vertical line (rh*48/rw > 2000). Same algorithm as
     * [recognizeAndStitchLongHoriz] rotated 90°: chunk top→bottom with a
     * second-to-last-char anchor, then align by identical timestep size
     * (`rw/6` px) with the same 30px / 0.4 / 0.3-0.7 best-pair rule.
     *
     *  [isVertical] is the line's orientation, applied once per return exactly
     *  as in [recognizeAndStitchLongHoriz] — the chunks decode raw so the
     *  alternatives merge sees the characters the model emitted. */
    private suspend fun recognizeAndStitchLongVert(
        rotated: Bitmap, targetH: Int, engine: RecNcnn? = null, isVertical: Boolean = false
    ): PPOcrResult? {
        coroutineContext.ensureActive()
        val rw = rotated.width; val rh = rotated.height
        val scale = targetH.toFloat() / rw.toFloat()
        val maxChunkH = (480 / scale).toInt().coerceAtLeast(64)
        if (maxChunkH <= 0) return null
        val chunkMargin = (rw * 0.1f).toInt().coerceAtLeast(2)
        val chunks = mutableListOf<ChunkInfo>()
        var y = 0
        while (y < rh) {
            coroutineContext.ensureActive()
            val h = minOf(maxChunkH, rh - y)
            if (h < 16) break
            val chunkBmp = Bitmap.createBitmap(rotated, 0, y, rw, h)
            val cw = chunkBmp.width; val ch = chunkBmp.height
            val targetW = minOf(CHUNK_TARGET_MAX, (cw.toFloat() * targetH / ch.toFloat()).roundToInt().coerceAtLeast(4))
            val decoded = inferResizedRec(chunkBmp, targetW, targetH, engine)
            chunkBmp.recycle()
            if (decoded == null) { Log.e(TAG, "recNcnn chunk w$targetW infer null"); return null }
            val actualSeqLen = decoded.seqLenTotal
            val rawAlts = decoded.rawAlternatives
            chunks.add(ChunkInfo(decoded.text, decoded.charCols, decoded.alternatives, rawAlts, actualSeqLen, targetW, h, 0, y))
            if (y + h >= rh) break
            val txt = decoded.text
            if (txt.isNotEmpty() && decoded.charCols.isNotEmpty()) {
                val anchorIdx = if (txt.length >= 2) txt.length - 2 else 0
                val anchorT = decoded.charCols.getOrNull(anchorIdx) ?: decoded.charCols.last()
                val localYTop = ((anchorT + 0.5f) / actualSeqLen.toFloat()) * h
                val nextY = (y + localYTop.toInt() - chunkMargin).coerceAtLeast(0)
                if (nextY <= y || nextY >= y + h - 10) {
                    y += (h * 0.9f).toInt().coerceAtLeast(16)
                } else {
                    y = nextY
                }
            } else {
                y += (h * 0.9f).toInt().coerceAtLeast(16)
            }
        }
        if (chunks.isEmpty()) return null
        if (chunks.size==1) {
            val c = chunks[0]
            if (verboseLog) {
                Log.d(TAG, "long-line stitch vert rh=$rh rw=$rw chunks=1 stitchedLen=${c.text.length} seqLen=${c.actualSeqLen}")
            }
            return PPOcrResult(c.text, c.altsPerChar, c.charCols, c.actualSeqLen, TimestepTopK.of(c.rawAltsPerTimestep))
                .withVerticalPunctuation(isVertical)
        }
        // ——— stitch via anchor alignment with identical timestep size (rw/6) ———
        val timestepPx = rw.toFloat() / 6f
        val totalSeqLen = maxOf(1, ceil(rh.toFloat() * targetH.toFloat() / rw.toFloat() / REC_STRIDE.toFloat()).toInt())
        val chunkGlobalCentersY = chunks.map { ci ->
            ci.charCols.map { t -> ci.offsetY.toFloat() + (t + 0.5f) * timestepPx }
        }
        var stitchedText = StringBuilder(chunks[0].text)
        var stitchedAlts = chunks[0].altsPerChar.toMutableList()
        var stitchedCols = chunks[0].charCols.toMutableList()
        var stitchedGlobal = chunkGlobalCentersY[0].toMutableList()
        val stitchedRawAll = chunks[0].rawAltsPerTimestep.toMutableList()
        for (i in 1 until chunks.size) {
            val curr = chunks[i]
            val currGlobal = chunkGlobalCentersY[i]
            val currAlts = curr.altsPerChar
            if (currAlts.isEmpty() || stitchedAlts.isEmpty()) {
                val lastPx = stitchedGlobal.lastOrNull() ?: -100f
                val offsetGeomT = curr.offsetY.toFloat() * 6f / rw.toFloat()
                for (j in currAlts.indices) {
                    val candT = offsetGeomT + curr.charCols[j]
                    val candPx = currGlobal.getOrNull(j) ?: continue
                    if (candPx > lastPx + STITCH_APPEND_GAP_PX) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last() == ' ' && ch == ' ') continue
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(candT)
                        stitchedGlobal.add(candPx)
                    }
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
                continue
            }
            var bestPrevIdx = -1
            var bestCurrIdx = -1
            var bestScore = -1f
            val pStart = maxOf(0, stitchedAlts.size - STITCH_WINDOW)
            val cEnd = minOf(currAlts.size, STITCH_WINDOW)
            for (pIdx in stitchedAlts.size - 1 downTo pStart) {
                val pGC = stitchedGlobal[pIdx]
                for (cIdx in 0 until cEnd) {
                    val cGY = currGlobal[cIdx]
                    val dist = abs(pGC - cGY)
                    if (dist > STITCH_MAX_DIST_PX) continue
                    val pred = comparePredictionVectors(stitchedAlts[pIdx], currAlts[cIdx])
                    if (pred < STITCH_MIN_PRED) continue
                    val score = (1f - dist/STITCH_MAX_DIST_PX)*0.3f + pred*0.7f
                    if (score > bestScore) { bestScore = score; bestPrevIdx = pIdx; bestCurrIdx = cIdx }
                }
            }
            if (bestPrevIdx != -1) {
                val merged = interleaveAlternatives(stitchedAlts[bestPrevIdx], currAlts[bestCurrIdx]).toMutableList()
                val toKeep = bestPrevIdx + 1
                while (stitchedText.length > toKeep) {
                    stitchedText.deleteCharAt(stitchedText.length-1)
                    stitchedAlts.removeAt(stitchedAlts.size-1)
                    stitchedCols.removeAt(stitchedCols.size-1)
                    stitchedGlobal.removeAt(stitchedGlobal.size-1)
                }
                stitchedAlts[bestPrevIdx] = merged
                val offsetT = stitchedCols[bestPrevIdx] - curr.charCols[bestCurrIdx]
                val offsetPx = stitchedGlobal[bestPrevIdx] - currGlobal[bestCurrIdx]
                for (j in bestCurrIdx+1 until currAlts.size) {
                    val ch = curr.text.getOrNull(j) ?: continue
                    if (stitchedText.isNotEmpty() && stitchedText.last()==' ' && ch==' ') continue
                    stitchedText.append(ch)
                    stitchedAlts.add(currAlts[j])
                    stitchedCols.add(curr.charCols[j] + offsetT)
                    stitchedGlobal.add(currGlobal[j] + offsetPx)
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
            } else {
                val lastPx = stitchedGlobal.lastOrNull() ?: -100f
                var appended=0
                for (j in currAlts.indices) {
                    val candPx = currGlobal[j]
                    if (candPx > lastPx + STITCH_APPEND_GAP_PX || (appended==0 && currAlts.size==1)) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last()==' ' && ch==' ') continue
                        val candT = curr.offsetY.toFloat() * 6f / rw.toFloat() + curr.charCols[j]
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(candT)
                        stitchedGlobal.add(candPx)
                        appended++
                    }
                }
                if (appended==0) {
                    val lastT = stitchedCols.lastOrNull() ?: 0f
                    val lastPx2 = stitchedGlobal.lastOrNull() ?: 0f
                    val fallbackOffsetT = (lastT + 1f) - curr.charCols[0]
                    val fallbackOffsetPx = (lastPx2 + timestepPx) - currGlobal[0]
                    for (j in currAlts.indices) {
                        val ch = curr.text.getOrNull(j) ?: continue
                        if (stitchedText.isNotEmpty() && stitchedText.last()==' ' && ch==' ') continue
                        stitchedText.append(ch)
                        stitchedAlts.add(currAlts[j])
                        stitchedCols.add(curr.charCols[j] + fallbackOffsetT)
                        stitchedGlobal.add(currGlobal[j] + fallbackOffsetPx)
                    }
                }
                stitchedRawAll.addAll(curr.rawAltsPerTimestep)
            }
        }
        var finalText = stitchedText.toString()
        while (finalText.contains("  ")) finalText = finalText.replace("  ", " ")
        if (verboseLog) {
            Log.d(TAG, "long-line stitch vert rh=$rh rw=$rw chunks=${chunks.size} stitchedLen=${finalText.length} seqLen=$totalSeqLen")
        }
        return PPOcrResult(finalText, stitchedAlts, stitchedCols.toFloatArray(), totalSeqLen, TimestepTopK.of(stitchedRawAll))
            .withVerticalPunctuation(isVertical)
    }

    /** Prediction overlap 0–1 for a stitch candidate pair: 0.6–1.0 when top-1
     * agrees (scaled by top-5 overlap), 0.5 when either top-1 appears in the
     * other's top-3, else 0. Pairs below [STITCH_MIN_PRED] never stitch. */
    private fun comparePredictionVectors(alt1: List<Pair<Char,Float>>, alt2: List<Pair<Char,Float>>): Float {
        if (alt1.isEmpty()||alt2.isEmpty()) return 0f
        if (alt1[0].first==alt2[0].first) {
            val set2=alt2.take(5).map{ it.first }.toSet(); var m=0; alt1.take(5).forEach{ if(set2.contains(it.first)) m++ }
            return 0.6f + (m/5f)*0.4f
        }
        val c1=alt1[0].first; val c2=alt2[0].first
        if (alt2.take(3).any{ it.first==c1 } || alt1.take(3).any{ it.first==c2 }) return 0.5f
        return 0f
    }

    /** Merge two alternative lists at a stitch anchor: shared chars average up
     * (×0.8), unique chars discount (×0.6), keep top 15 by score. */
    private fun interleaveAlternatives(alt1: List<Pair<Char, Float>>, alt2: List<Pair<Char, Float>>): List<Pair<Char, Float>> {
        val merged = mutableMapOf<Char, Float>()
        alt1.forEach { (ch, sc) -> merged[ch] = sc }
        alt2.forEach { (ch, sc) ->
            val ex = merged[ch] ?: 0f
            if (ex > 0f) merged[ch] = (ex + sc) * 0.8f else merged[ch] = sc * 0.6f
        }
        return merged.toList().sortedByDescending { it.second }.take(TOP_K)
    }

    /**
     * Decode full-logits fallback rows after compact Kotlin staging. The full
     * matrix stays on the JVM; Rust receives the top-15 table plus the two
     * neighbour values needed to reproduce whole-row peak interpolation.
     *
     *  [isVertical] folds the vertical punctuation (#56, #63) into the decode;
     *  see [inferRecBitmap]. */
    private fun ctcDecode(
        cropLogits: Array<FloatArray>?,
        seqLen: Int,
        numClasses: Int,
        seqLenTotal: Int,
        isVertical: Boolean = false,
    ): PPOcrResult {
        val rows = packCtcCandidateRows(cropLogits, seqLen, numClasses)
        return checkNotNull(ctcDecoder).decodeFullCompact(
            rows.packed.asList(),
            rows.leftScores.asList(),
            rows.rightScores.asList(),
            seqLen.toLong(),
            seqLenTotal.toLong(),
            isVertical,
        ).toPpoResult()
    }

    /** Decode one already-pruned native line across UniFFI exactly once.
     *
     *  [isVertical] folds the vertical punctuation (#56, #63) into the decode;
     *  see [inferRecBitmap]. */
    private fun ctcDecodeTopK(
        packed: FloatArray,
        seqLen: Int,
        isVertical: Boolean = false,
    ): PPOcrResult {
        check(seqLen >= 0) { "negative CTC sequence length: $seqLen" }
        check(packed.size == seqLen * TOP_K * 2) {
            "packed CTC row has ${packed.size} floats; expected ${seqLen * TOP_K * 2}"
        }
        return checkNotNull(ctcDecoder)
            .decodeTopKCompact(packed.asList(), seqLen.toLong(), isVertical)
            .toPpoResult()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Char boxes from CTC columns (pure stages in jpdict_core::char_boxes)
    // ═════════════════════════════════════════════════════════════════════════

    /** Per-character ink half-widths at [renderTextSize], measured with the
     * device typeface exactly as the old `resolveInkCollisions` did — the
     * Rust side resolves collisions from these, so the measuring stays here
     * (the desktop measures from its bundled font instead, and the legacy
     * columns / snap / punctuation rules moved across whole). Local Paint
     * per call, so this is safe on any dispatcher. */
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

        // CAP (research/char-placement): CTC activation runs + layout template +
        // ink refinement + the final boundary pass.  Falls back inside itself
        // when the top-K steps or the crop pixels are missing, so this branch is
        // safe on the re-decode path too; the shipped chain below stays as the
        // debug-screen kill switch (PREF_BOX_PLACEMENT_CAP off).
        //
        // `steps` arrives compact ([TimestepTopK]) and is truncated to the top
        // **two** cells per timestep right here: `runs_from_steps` — the only
        // reader of `steps` in `jpdict_core::char_placement` — walks on
        // `alts[0].0`, takes the mean confidence from `alts[0].1` and the mean
        // top1−top2 margin from `alts[1].1` (0.0 for a row with a single cell),
        // and never looks at anything else. Building all 15 `Step`s per timestep
        // was a second full copy of the page's top-15 table for two numbers, and
        // `place`'s `Step`→`GapCell` conversion a third — the boundary itself
        // takes `GapCell`s, so `placeCells` gets the table's own
        // ([TimestepTopK.capCellRows]) and the page allocates no cell for it.
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

        // Legacy chain (#49) now lives in jpdict_core::char_boxes — same
        // columns, snap, ink resolve and uniform sizing, with the ink widths
        // measured above and the layout prefs passed explicitly. The snap
        // stage's ink evidence is measured here (a polarity plus one count per
        // reading-axis position) instead of shipped as the whole ARGB crop: a
        // `List<Int>` of 31k pixels per line was the most expensive marshalling
        // on the page, and the evidence is ~1.7 kB of byte arrays. Identical
        // boxes; a crop that cannot be reduced falls back to the pixel call.
        val inkHalf = if (!isVertical) {
            measureInkHalfWidths(text, n, cropH.toFloat() * 0.90f)
        } else FloatArray(0)
        return ocrEngineCharBoxesWithEvidence(
            text = text,
            charCols = charCols,
            seqLenTotal = seqLenTotal,
            cropX = cropX,
            cropY = cropY,
            cropW = cropW,
            cropH = cropH,
            isVertical = isVertical,
            pixels = pixels,
            pixW = pixW,
            pixH = pixH,
            snap = BOX_LAYOUT_MODE == BOX_SNAP,
            uniform = BOX_UNIFORM_SIZE,
            inkHalfWidths = inkHalf,
        ).map { JpDictRect(it.x, it.y, it.x + it.w, it.y + it.h) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Streaming rec — batches of 4, completion-order emit
    // ═════════════════════════════════════════════════════════════════════════

    /** Recognize all line boxes, streaming per-line results in completion order.
     *
     * One dynamic-width model serves both orientations: portrait crops rotate
     * 270° pre-inference, char boxes compute on the x- vs y-axis post-inference.
     * Jobs sort into reading order (vertical right-to-left, then horizontal
     * top-to-bottom; results stay keyed by job idx so only arrival order
     * changes), run in batches of [REC_BATCH_SIZE] with each line's rec input
     * described up front and drawn inside the batch. Within a
     * batch, [recognizePpocrBatch] awaits via `select` over the deferreds so
     * each line emits as its infer completes; the callback re-posts to the main
     * thread. Cooperative cancellation (#18): overlay close cancels the job and
     * each batch boundary checks `ensureActive()`.
     *
     * #53: [LineBox]es are accepted directly; a rotated Line's crop is the
     * unrotate warp, an axis-aligned one's is the same clamped rect crop as
     * before. The older [JpDictRect] overload below stays for callers that only
     * know rects (the androidTest benchmark, tests) and behaves identically. */
    suspend fun recognizeStreaming(
        bitmap: Bitmap,
        lineBoxes: List<LineBox>,
        onLinesRecognized: (List<Pair<Int, LineResult>>) -> Unit
    ) = coroutineScope {
        val startTime = System.currentTimeMillis()
        if (recDynNcnn == null || ppocrVocab.isEmpty()) return@coroutineScope

        // Build job queue — no Bitmaps yet; each line's rec input is described
        // per batch below and only a rotated Line allocates up front.

        val jobs = lineBoxes.mapIndexedNotNull { i, box ->
            if (box.rect.width() < 4 || box.rect.height() < 4) null
            else Job(i, box, isVerticalLineBox(box))
        }
        if (jobs.isEmpty()) return@coroutineScope

        Log.d(TAG, "Processing ${jobs.size} boxes in batches of $REC_BATCH_SIZE")
        InferLog.add("stream start boxes=${jobs.size} batch=$REC_BATCH_SIZE squish=$recSquish")

        // Reading order: vertical lines right-to-left first, then horizontal
        // lines top-to-bottom (same per-group comparators as sortDetectedBoxes).
        // Results stay keyed by job idx, so only arrival order changes.
        val verticals = jobs.filter { it.isVertical }
            .sortedWith(compareByDescending<Job> { it.box.rect.right }.thenBy { it.box.rect.top })
        val horizontals = jobs.filter { !it.isVertical }
            .sortedWith(compareBy<Job> { it.box.rect.top }.thenBy { it.box.rect.left })
        val sortedJobs = verticals + horizontals

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // Process in batches — cooperative cancellation for #18 (overlay closed → cancel).
        val batches = sortedJobs.chunked(REC_BATCH_SIZE.coerceAtLeast(1))
        val engine = recDynNcnn ?: return@coroutineScope
        for ((batchIdx, batch) in batches.withIndex()) {
            coroutineContext.ensureActive()
            processOneBatch(batchIdx, batch, engine, bitmap, mainHandler,
                onLinesRecognized, fanout = REC_FANOUT)
        }
        Log.d(TAG, "All batches finished")

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Streaming recognition for ${lineBoxes.size} lines took ${elapsed}ms")
        InferLog.add("stream done lines=${lineBoxes.size} total=${elapsed}ms")
    }

    /** Pre-#53 signature: every rect is an axis-aligned [LineBox]. */
    @JvmName("recognizeStreamingRects")
    suspend fun recognizeStreaming(
        bitmap: Bitmap,
        lineBoxes: List<JpDictRect>,
        onLinesRecognized: (List<Pair<Int, LineResult>>) -> Unit
    ) = recognizeStreaming(bitmap, lineBoxes.map { LineBox.of(it) }, onLinesRecognized)

    // ═════════════════════════════════════════════════════════════════════════
    //  Re-decode from cache (no re-inference)
    // ═════════════════════════════════════════════════════════════════════════

    /** Re-decode a [LineResult] from its cached [rawAlternatives] without
     * re-running recognition. This walk is the reference the gap detector's
     * collapse rules are written against (see [GapDetector.timestepColumns]);
     * it is greedy, like the decoders — the A5/#86 audit deleted the
     * `blankThreshold` surfacing path and its second, unrelated formula. Char
     * boxes recompute when crop geometry is known; user
     * [overrides][LineResult.overrides] carry over. */
    fun reDecodeLineResult(oldLine: LineResult): LineResult {
        // The slider walk wants *every* cell of *every* timestep — it is the
        // reference the collapse rules are written against — so this is one of
        // the few readers that materialises the full nested rows. It crosses
        // them as `GapCell`s straight out of the compact table
        // ([TimestepTopK.gapCellRows]), without the intermediate `Pair`/`Float`
        // boxes the nested form needs.
        val topK = oldLine.rawTopK
        if (topK.isEmpty()) return oldLine

        val re = ocrEngineReDecode(
            topK.gapCellRows(),
            oldLine.isVertical,
        ) ?: return oldLine
        // The walk already applies the vertical punctuation normalisation to
        // the text and every alternative entry, like the emit path does.
        val vertText = re.text
        val newAlts = re.alternatives.map { alts ->
            alts.map { g -> Char(g.ch) to g.score }.toMutableList()
        }
        val charCols = re.charCols.toFloatArray()

        val newCharBoxes = if (oldLine.cropW > 0 && oldLine.cropH > 0) {
            // CAP evidence on the re-decode path: the cached top-K is still
            // here, normalized like the emit path (no crop pixels survive, so
            // CAP runs its template without ink refinement).
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
            // #53: a rotated Line's char boxes live in its upright frame, so
            // re-decode must place them back through the frame too.
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
        try { ctcDecoder?.close() } catch (_: Exception) {}
        try { detNcnn?.close() } catch (_: Exception) {}
        try { recDynNcnn?.close() } catch (_: Exception) {}
    }
}

// Horizontal → vertical glyph equivalents (most CJK brackets are already upright
// in the font; only chōonpu and ASCII-ish dashes need remapping).
// Ellipses need no entry here: `…`/`‥` → `︙`/`︰` is handled vertical-only in
// JapaneseUtil.verticalPunctuation at emit time (#63).


