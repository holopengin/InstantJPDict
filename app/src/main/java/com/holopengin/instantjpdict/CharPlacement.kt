package com.holopengin.instantjpdict

import uniffi.nav_graph_core.GapCell
import uniffi.nav_graph_core.InkSpec
import uniffi.nav_graph_core.PlaceOptions
import uniffi.nav_graph_core.SnapSpec
import uniffi.nav_graph_core.charPlacementBlank
import uniffi.nav_graph_core.charPlacementInkSpec
import uniffi.nav_graph_core.charPlacementPlace
import uniffi.nav_graph_core.charPlacementPlaceEvidence
import uniffi.nav_graph_core.ocrEngineComputeCharBoxes
import uniffi.nav_graph_core.ocrEngineComputeCharBoxesEvidence
import uniffi.nav_graph_core.ocrEngineSnapSpec

internal object CharPlacement {

    val BLANK: Char = Char(charPlacementBlank())

    /** The crate's ink-measurement constants (thresholds, border stride, the
     *  count ceiling), read once: the facade must not restate them. */
    val INK: InkSpec = charPlacementInkSpec()

    data class Step(val char: Char, val score: Float)

    data class Options(
        val inkMaxPullEm: Float = 0.45f,
        val windowEm: Float = 0.6f,
        val windowStride: Float = 0.4f,
        val anchorTolEm: Float = 0.4f,
        val anchorTolStride: Float = 1.2f,
        val huberEm: Float = 0.35f,
        val minMassFrac: Float = 0.12f,
        val maxSpreadEm: Float = 0.8f,
        val confFloor: Float = 1.0f,
        val refinePasses: Int = 1,
        val finalPass: Boolean = true,
        val extentPadPx: Float = 1f,
        val extentFloor: Float = 0.5f,
        val extentWindowEm: Float = 0.15f,
        val extentGrowFrac: Float = 0.3f,
        val splitFloorFrac: Float = 1f,
        val minHalfPx: Float = 2f,
        val punctSpreadFallback: Boolean = true,
        val bimodalRetry: Boolean = true,
        val bimodalValleyEm: Float = 0.20f,
        val bimodalMinFrac: Float = 0.12f,
        val punctFallbackWindowEm: Float = 0.5f,
        val punctFallbackMaxEm: Float = 0.6f,
        val translateOverlap: Boolean = true,
        val translateMaxEm: Float = 0.04f,
        val translatePasses: Int = 2,
        val translateGateCut: Boolean = false,
    )

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** The crop's ink evidence reduced to what the stage reads: the background
     *  polarity and the ink mask at 1 bit per pixel. `null` when the crop cannot
     *  be reduced (no pixels, a sub-8px frame, a short array, a cross axis
     *  thicker than the count ceiling) — the caller then keeps passing pixels,
     *  so the ink pass is skipped exactly as it is today. */
    private class Mask(val bgLight: Boolean, val bits: ByteArray)

    private fun mask(px: IntArray, w: Int, h: Int): Mask? {
        if (w < 8 || h < 8 || px.size != w * h) return null
        val n = w * h
        val bits = ByteArray((n + 7) / 8)
        // The border sample decides the polarity in Rust (one median, one place).
        val border = ByteArray(3 * 2 * ((w + INK.borderStride - 1) / INK.borderStride + (h + INK.borderStride - 1) / INK.borderStride))
        var k = 0
        var bi = 0
        while (bi < w) {
            for (p in intArrayOf(px[bi], px[(h - 1) * w + bi])) {
                border[k++] = (p shr 16 and 0xFF).toByte()
                border[k++] = (p shr 8 and 0xFF).toByte()
                border[k++] = (p and 0xFF).toByte()
            }
            bi += INK.borderStride
        }
        bi = 0
        while (bi < h) {
            for (p in intArrayOf(px[bi * w], px[bi * w + w - 1])) {
                border[k++] = (p shr 16 and 0xFF).toByte()
                border[k++] = (p shr 8 and 0xFF).toByte()
                border[k++] = (p and 0xFF).toByte()
            }
            bi += INK.borderStride
        }
        val bgLight = uniffi.nav_graph_core.charPlacementProfileBand(
            border.copyOf(k), ByteArray(1), ByteArray(1),
        ).bgLight
        // One flat pass over the crop: the output byte stays in a register and
        // the polarity branch is hoisted out of the loop. The ink test is an
        // integer compare on the channel sum — identical to the reference's
        // float mean, since both thresholds are exact multiples of three.
        var byte = 0
        var bit = 0x80
        var i = 0
        if (bgLight) {
            val thr = INK.inkBelowSum
            while (i < n) {
                val p = px[i]
                if ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) < thr) byte = byte or bit
                bit = bit shr 1
                if (bit == 0) { bits[i shr 3] = byte.toByte(); byte = 0; bit = 0x80 }
                i++
            }
        } else {
            val thr = INK.inkAboveSum
            while (i < n) {
                val p = px[i]
                if ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) >= thr) byte = byte or bit
                bit = bit shr 1
                if (bit == 0) { bits[i shr 3] = byte.toByte(); byte = 0; bit = 0x80 }
                i++
            }
        }
        if (bit != 0x80) bits[i shr 3] = byte.toByte()
        return Mask(bgLight, bits)
    }

    fun place(
        text: String,
        charCols: FloatArray,
        seqLenTotal: Int,
        cropW: Int,
        cropH: Int,
        isVertical: Boolean,
        pixels: IntArray? = null,
        steps: List<List<Step>>? = null,
        options: Options = Options(),
    ): List<Box> {
        val stepsRust = steps?.map { alts -> alts.map { GapCell(ch = it.char.code, score = it.score) } }
        val opts = options.toRust()
        // The evidence path when the crop reduces; the pixel path otherwise.
        val m = pixels?.let { mask(it, cropW, cropH) }
        val raw = if (m != null) {
            charPlacementPlaceEvidence(
                text = text,
                charCols = charCols.toList(),
                seqLenTotal = seqLenTotal.toLong(),
                cropW = cropW.toUInt(),
                cropH = cropH.toUInt(),
                vertical = isVertical,
                bgLight = m.bgLight,
                inkBits = m.bits,
                steps = stepsRust,
                options = opts,
            )
        } else {
            charPlacementPlace(
                text = text,
                charCols = charCols.toList(),
                seqLenTotal = seqLenTotal.toLong(),
                cropW = cropW.toUInt(),
                cropH = cropH.toUInt(),
                vertical = isVertical,
                pixels = pixels?.toList(),
                steps = stepsRust,
                options = opts,
            )
        }
        return raw.map { Box(it.left, it.top, it.right, it.bottom) }
    }

    private fun Options.toRust(): PlaceOptions = PlaceOptions(
        inkMaxPullEm = inkMaxPullEm,
        windowEm = windowEm,
        windowStride = windowStride,
        anchorTolEm = anchorTolEm,
        anchorTolStride = anchorTolStride,
        huberEm = huberEm,
        minMassFrac = minMassFrac,
        maxSpreadEm = maxSpreadEm,
        confFloor = confFloor,
        refinePasses = refinePasses,
        finalPass = finalPass,
        extentPadPx = extentPadPx,
        extentFloor = extentFloor,
        extentWindowEm = extentWindowEm,
        extentGrowFrac = extentGrowFrac,
        splitFloorFrac = splitFloorFrac,
        minHalfPx = minHalfPx,
        punctSpreadFallback = punctSpreadFallback,
        bimodalRetry = bimodalRetry,
        bimodalValleyEm = bimodalValleyEm,
        bimodalMinFrac = bimodalMinFrac,
        punctFallbackWindowEm = punctFallbackWindowEm,
        punctFallbackMaxEm = punctFallbackMaxEm,
        translateOverlap = translateOverlap,
        translateMaxEm = translateMaxEm,
        translatePasses = translatePasses,
        translateGateCut = translateGateCut,
    )
}

/** The legacy chain's snap evidence: the border sample plus the two central-band
 *  count series. `null` when the crop cannot be reduced, so the caller keeps
 *  passing pixels (and the snap stage is skipped) exactly as today. */
internal class SnapEvidence(
    val border: ByteArray,
    val dark: ByteArray,
    val light: ByteArray,
)

internal object SnapEvidenceFactory {
    /** The crate's snap-measurement constants, read once. */
    val SPEC: SnapSpec = ocrEngineSnapSpec()

    fun of(px: IntArray, pixW: Int, pixH: Int, vertical: Boolean): SnapEvidence? {
        if (pixW < 8 || pixH < 8 || px.size != pixW * pixH) return null
        val border = ByteArray(3 * 2 * ((pixW + SPEC.borderStride - 1) / SPEC.borderStride + (pixH + SPEC.borderStride - 1) / SPEC.borderStride))
        var k = 0
        fun push(p: Int) {
            border[k++] = (p shr 16 and 0xFF).toByte()
            border[k++] = (p shr 8 and 0xFF).toByte()
            border[k++] = (p and 0xFF).toByte()
        }
        var i = 0
        while (i < pixW) { push(px[i]); push(px[(pixH - 1) * pixW + i]); i += SPEC.borderStride }
        var j = 0
        while (j < pixH) { push(px[j * pixW]); push(px[j * pixW + pixW - 1]); j += SPEC.borderStride }
        val cross = if (vertical) pixW else pixH
        // A cross axis thicker than the count ceiling cannot be reduced to
        // counts: keep the pixel path (the crate refuses a saturated count too).
        if (cross > SPEC.maxCount) return null
        val lo = (cross * SPEC.bandLo).toInt()
        val hi = (cross * SPEC.bandHi).toInt()
        val read = if (vertical) pixH else pixW
        val dark = ByteArray(read)
        val light = ByteArray(read)
        val below = SPEC.inkBelowSum
        val above = SPEC.inkAboveSum
        for (r in 0 until read) {
            var base = if (vertical) r * pixW else r
            for (c in lo until minOf(hi, cross)) {
                val p = px[if (vertical) base else base + c * pixW]
                val sum = (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)
                if (sum < below) dark[r]++
                if (sum >= above) light[r]++
            }
        }
        return SnapEvidence(border.copyOf(k), dark, light)
    }
}

/** The legacy chain with the snap evidence measured here instead of shipped as
 *  pixels: same boxes, ~1.7 kB across the boundary instead of `cropW * cropH`
 *  ARGB ints. */
internal fun ocrEngineCharBoxesWithEvidence(
    text: String,
    charCols: FloatArray,
    seqLenTotal: Int,
    cropX: Int,
    cropY: Int,
    cropW: Int,
    cropH: Int,
    isVertical: Boolean,
    pixels: IntArray?,
    pixW: Int,
    pixH: Int,
    snap: Boolean,
    uniform: Boolean,
    inkHalfWidths: FloatArray,
): List<uniffi.nav_graph_core.BoundingBox> {
    val ev = pixels?.let { SnapEvidenceFactory.of(it, pixW, pixH, isVertical) }
    return if (ev != null) {
        ocrEngineComputeCharBoxesEvidence(
            text = text,
            charCols = charCols.toList(),
            seqLenTotal = seqLenTotal.toLong(),
            cropX = cropX,
            cropY = cropY,
            cropW = cropW,
            cropH = cropH,
            isVertical = isVertical,
            borderRgb = ev.border,
            inkDark = ev.dark,
            inkLight = ev.light,
            pixW = pixW,
            pixH = pixH,
            snap = snap,
            uniform = uniform,
            inkHalfWidths = inkHalfWidths.toList(),
        )
    } else {
        ocrEngineComputeCharBoxes(
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
            snap = snap,
            uniform = uniform,
            inkHalfWidths = inkHalfWidths.toList(),
        )
    }
}
