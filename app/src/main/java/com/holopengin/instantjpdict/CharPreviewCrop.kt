package com.holopengin.instantjpdict

import android.graphics.Bitmap

/**
 * #102: the character-crop preview the three sites that show one share — the
 * alternatives list's `preview_image` (built in `OcrOverlayView`'s
 * `toggleAlternativesPanel`, refreshed in `updateAlternativesPanelContent`) and
 * the manual character entry dialog (`showManualInput`). All three used to be
 * the same three lines: take the char box, grow it by a padding, `createBitmap`
 * the page at that rect.
 *
 * For an **axis-aligned** Line that is still exactly what happens — the same
 * rect arithmetic, the same `Bitmap.createBitmap`, the same pixels, byte for
 * byte. Only a rotated (#53) Line changes, and only in *where* the crop is
 * taken: its `charBoxes` are the AABBs of **tilted** glyphs, so an axis-aligned
 * grab of one showed the character lying on its side and swept in background
 * and the neighbouring glyph with it. A rotated Line is now unrotated first —
 * through [OcrEngine.warpRotatedCrop], the very warp the recogniser reads — and
 * the char box is carried back into that upright frame
 * ([RotatedGeometry.mapSourceRectToLocal]), where the padding is applied. A
 * padding of 0.2 of the glyph's height then means what it means for upright
 * text, and all three sites show the same upright crop for the same character.
 *
 * The rect arithmetic is kept in the pure [sourceRect]/[localRect] pair so the
 * host tests can pin it without a `Bitmap`; only [crop] needs one. The
 * intermediate upright frame is recycled here — the caller owns only the crop.
 */
object CharPreviewCrop {

    /**
     * The alternatives previews' padding, as a fraction of the char box's
     * height. Tightened from the 0.5 the manual entry still uses: the preview
     * is a small square next to the candidate list, and a wide margin made the
     * glyph a speck in the middle of it.
     */
    const val ALTERNATIVES_PAD_RATIO = 0.2f

    /**
     * The manual entry dialog's padding, as a fraction of the char box's
     * height. Generous on purpose: this is the one preview the user has to
     * *read a character off* before typing it.
     */
    const val MANUAL_PAD_RATIO = 0.5f

    /**
     * The axis-aligned Line's crop rect: the arithmetic the three sites
     * inlined before #102, kept verbatim (including the `.toInt()` truncation
     * and the one-sided clamps) so an upright Line's preview cannot move by a
     * pixel.
     *
     * The rect is a [JpDictRect] rather than a `Rect` so the host tests can pin
     * it; the caller turns it into a [Bitmap] at [crop].
     */
    fun sourceRect(box: JpDictRect, padRatio: Float, srcW: Int, srcH: Int): JpDictRect {
        val padding = (box.height() * padRatio).toInt()
        return JpDictRect(
            (box.left - padding).coerceAtLeast(0),
            (box.top - padding).coerceAtLeast(0),
            (box.right + padding).coerceAtMost(srcW),
            (box.bottom + padding).coerceAtMost(srcH),
        )
    }

    /**
     * A rotated Line's crop rect, in its upright frame: the char box's source
     * AABB carried back to local coordinates ([frameW] × [frameH], the frame
     * the unrotate produced), then padded **there** and clamped to the frame.
     *
     * The returned rect encloses the glyph's true local cell and is usually a
     * little larger — see [RotatedGeometry.mapSourceRectToLocal] for why, and
     * for what that costs — so the preview shows the character upright with a
     * margin, rather than clipped.
     */
    fun localRect(quad: JpDictQuad, box: JpDictRect, padRatio: Float, frameW: Int, frameH: Int): JpDictRect {
        val local = RotatedGeometry.mapSourceRectToLocal(quad, box, frameW, frameH)
        val padding = (local.height() * padRatio).toInt()
        return JpDictRect(
            (local.left - padding).coerceAtLeast(0),
            (local.top - padding).coerceAtLeast(0),
            (local.right + padding).coerceAtMost(frameW),
            (local.bottom + padding).coerceAtMost(frameH),
        )
    }

    /**
     * The preview crop for character [charIdx] of [line] in [src], or null when
     * there is no such character or nothing to crop.
     *
     * [padRatio] is the site's own padding ([ALTERNATIVES_PAD_RATIO] or
     * [MANUAL_PAD_RATIO]), applied in whichever frame the crop is taken in.
     *
     * The upright frame a rotated Line needs is a small per-line allocation (the
     * Line's own extent, not the page's) and is recycled before returning, so
     * the caller holds only the crop — which is what [OcrOverlayView]'s
     * `setCropBitmap` recycles in turn.
     */
    fun crop(src: Bitmap, line: LineResult?, charIdx: Int, padRatio: Float): Bitmap? {
        val result = line ?: return null
        val box = result.charBoxes.getOrNull(charIdx) ?: return null
        val quad = result.quad
        if (quad == null) return cropOf(src, sourceRect(box, padRatio, src.width, src.height))
        val frame = OcrEngine.warpRotatedCrop(src, quad) ?: return null
        val crop = cropOf(frame, localRect(quad, box, padRatio, frame.width, frame.height))
        // `createBitmap` always copies out of a mutable source, but the whole
        // frame as a crop is the one case where it may hand the frame back.
        if (crop !== frame) frame.recycle()
        return crop
    }

    /**
     * `Bitmap.createBitmap(src, rect)`, with the empty and out-of-bounds cases
     * turned into null. Those are what `createBitmap` does with a degenerate
     * rect anyway (the pre-#102 sites never hit them, since a char box is on
     * the page); stating it here keeps a clamped local rect from throwing out
     * of a click handler.
     */
    private fun cropOf(src: Bitmap, rect: JpDictRect): Bitmap? {
        if (rect.width() <= 0 || rect.height() <= 0) return null
        if (rect.left + rect.width() > src.width || rect.top + rect.height() > src.height) return null
        return try {
            Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
        } catch (_: Exception) {
            null
        }
    }
}
