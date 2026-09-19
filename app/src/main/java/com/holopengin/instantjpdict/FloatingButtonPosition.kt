package com.holopengin.instantjpdict

import kotlin.math.roundToInt

/**
 * #93: the floating OCR trigger's rotation geometry, in physical device space.
 *
 * The trigger's window params are expressed in the CURRENT display's
 * coordinate space. On rotation the system reuses those numbers in the new
 * space, so the logical offset survives while its origin moves to a different
 * physical edge, and the button appears to jump to another part of the screen.
 *
 * The trigger is taped to the device, so the canonical position here is the
 * button CENTRE in natural (rotation-0) device coordinates, and every display
 * size/rotation change re-derives the logical pixels from it. A trigger parked
 * beside the front-camera cutout stays beside that cutout in every
 * orientation: the cutout itself migrates through the logical edges as the
 * display rotates (verified against the system's own cutout placement — in
 * `ROTATION_90` the Pixel 7a cutout sits on the logical left edge).
 *
 * The natural centre is canonical: a logical position that must clamp at an
 * edge (the physical spot would fall outside the rotated screen) does not
 * rewrite it, so rotating back returns the trigger to the exact spot.
 *
 * Pure, so the geometry is host-tested; the service owns when it is applied.
 * The rotation constants mirror `android.view.Surface` but stay local so this
 * file needs no Android types to be exercised on the host.
 */

internal const val ROTATION_NATURAL = 0
internal const val ROTATION_90 = 1
internal const val ROTATION_180 = 2
internal const val ROTATION_270 = 3

/** Natural (rotation-0) display width/height for a logical [w]x[h] at [rotation]. */
internal fun naturalDisplaySize(w: Int, h: Int, rotation: Int): Pair<Int, Int> =
    if (rotation == ROTATION_90 || rotation == ROTATION_270) h to w else w to h

/**
 * The button centre in natural device coordinates for a logical top-left at
 * [x],[y] on a [logicalW]x[logicalH] display.
 */
internal fun naturalCentre(
    x: Int,
    y: Int,
    viewW: Int,
    viewH: Int,
    logicalW: Int,
    logicalH: Int,
    rotation: Int,
): Pair<Float, Float> {
    val cx = x + viewW / 2f
    val cy = y + viewH / 2f
    val (natW, natH) = naturalDisplaySize(logicalW, logicalH, rotation)
    return when (rotation) {
        ROTATION_90 -> (natW - cy) to cx
        ROTATION_180 -> (natW - cx) to (natH - cy)
        ROTATION_270 -> cy to (natH - cx)
        else -> cx to cy
    }
}

/**
 * The logical top-left for a natural centre, clamped so the whole button stays
 * on the [logicalW]x[logicalH] display (the drag handler's own range).
 */
internal fun logicalTopLeft(
    natCX: Float,
    natCY: Float,
    viewW: Int,
    viewH: Int,
    logicalW: Int,
    logicalH: Int,
    rotation: Int,
): Pair<Int, Int> {
    if (logicalW <= 0 || logicalH <= 0) return 0 to 0
    val (natW, natH) = naturalDisplaySize(logicalW, logicalH, rotation)
    val (cx, cy) = when (rotation) {
        ROTATION_90 -> natCY to (natW - natCX)
        ROTATION_180 -> (natW - natCX) to (natH - natCY)
        ROTATION_270 -> (natH - natCY) to natCX
        else -> natCX to natCY
    }
    val x = (cx - viewW / 2f).roundToInt()
    val y = (cy - viewH / 2f).roundToInt()
    return x.coerceIn(0, (logicalW - viewW).coerceAtLeast(0)) to
        y.coerceIn(0, (logicalH - viewH).coerceAtLeast(0))
}
