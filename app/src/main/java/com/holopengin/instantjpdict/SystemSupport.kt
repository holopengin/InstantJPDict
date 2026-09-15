package com.holopengin.instantjpdict

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.DictionaryEntry
import kotlin.math.abs

data class JpDictRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun centerX(): Int = left + width() / 2
    fun centerY(): Int = top + height() / 2
}

/** One corner of a [JpDictQuad], in source-image pixels. */
data class QuadPoint(val x: Float, val y: Float)

/**
 * #53: one Line's opt-in rotated geometry — the source placement of an upright
 * Crop rectangle. Corners `c0..c3` are the images of the crop's local
 * `(0,0)`, `(w,0)`, `(w,h)`, `(0,h)`: [localWidth] is the crop's x axis
 * (the reading axis for a horizontal Line, the cross axis for a vertical one)
 * and [localHeight] its y axis. [xAxis]/[yAxis] are the unit local axes in
 * source pixels, [tiltDeg] is the clockwise rotation the overlay draws the
 * glyphs by (0 for upright text), and [mapLocalRect] carries a box computed in
 * crop space — char boxes — back out to source pixels as an AABB.
 *
 * The default path never produces one: a fit within
 * [RotatedGeometry.AXIS_ALIGNED_TOL_DEG] of the axes stays a [JpDictRect],
 * so upright content keeps the exact behaviour it had.
 */
data class JpDictQuad(
    val c0: QuadPoint,
    val c1: QuadPoint,
    val c2: QuadPoint,
    val c3: QuadPoint,
) {
    val localWidth: Float get() = RotatedGeometry.distance(c0, c1)
    val localHeight: Float get() = RotatedGeometry.distance(c0, c3)
    val center: QuadPoint
        get() = QuadPoint(
            (c0.x + c1.x + c2.x + c3.x) / 4f,
            (c0.y + c1.y + c2.y + c3.y) / 4f,
        )

    /** Unit local x axis (left → right in crop space). */
    val xAxis: QuadPoint get() = RotatedGeometry.unit(c0, c1)

    /** Unit local y axis (top → bottom in crop space). */
    val yAxis: QuadPoint get() = RotatedGeometry.unit(c0, c3)

    /** Clockwise rotation of the upright frame in source pixels; 0 = upright. */
    val tiltDeg: Float get() = RotatedGeometry.tiltDeg(this)

    /** setPolyToPoly input: interleaved x,y for `c0,c1,c2,c3`. */
    fun corners(): FloatArray =
        floatArrayOf(c0.x, c0.y, c1.x, c1.y, c2.x, c2.y, c3.x, c3.y)

    /** The enclosing axis-aligned rect, rounded to whole pixels like every
     *  other box in this file. */
    fun toRect(): JpDictRect = RotatedGeometry.aabb(this)

    /** A local crop-space box as a source-space AABB (char boxes). */
    fun mapLocalRect(local: JpDictRect): JpDictRect = RotatedGeometry.mapLocalRect(this, local)

    /** True when the frame is within [tolDeg] of the upright axes: such a Line
     *  stays on the axis-aligned path and never needs an unrotate. */
    fun isAxisAligned(tolDeg: Float = RotatedGeometry.AXIS_ALIGNED_TOL_DEG): Boolean =
        kotlin.math.abs(tiltDeg) <= tolDeg

    companion object {
        /** The upright frame for [rect] with the corner order the fit builds on
         *  (`c0..c3`). **Test-only**: production quads come from [RotatedGeometry]
         *  fitting component pixels; the rotated-geometry tests use this factory to
         *  pin the corner order without a detect (kept deliberately, #86/E1). */
        fun fromRect(rect: JpDictRect): JpDictQuad = JpDictQuad(
            QuadPoint(rect.left.toFloat(), rect.top.toFloat()),
            QuadPoint(rect.right.toFloat(), rect.top.toFloat()),
            QuadPoint(rect.right.toFloat(), rect.bottom.toFloat()),
            QuadPoint(rect.left.toFloat(), rect.bottom.toFloat()),
        )
    }
}

/**
 * #53: one Line's geometry as detect → recognize → render passes it around.
 * The axis-aligned default is [LineBox.rect] with [LineBox.quad] null — the
 * exact type and path the engine had before rotated support. The opt-in
 * rotated path sets [quad] (the fitted upright-crop placement) and keeps
 * [rect] as its AABB for hit-testing and for the consumers that only know
 * rects (nav graph, cursor, lookup crop).
 */
data class LineBox(
    val rect: JpDictRect,
    val quad: JpDictQuad? = null,
) {
    val isRotated: Boolean get() = quad != null

    companion object {
        fun of(rect: JpDictRect): LineBox = LineBox(rect)
    }
}

fun JpDictRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)
fun Rect.toJpDictRect(): JpDictRect = JpDictRect(left, top, right, bottom)

interface DictionaryProvider {
    suspend fun findByTexts(texts: List<String>): List<DictionaryEntry>
    /** dictionaryId → display name, for per-entry source labels. */
    suspend fun dictionaryNames(): Map<Int, String>
}

class AndroidDictionaryProvider(private val context: Context) : DictionaryProvider {
    override suspend fun findByTexts(texts: List<String>): List<DictionaryEntry> {
        val db = AppDatabase.getDatabase(context)
        return db.dictionaryDao().findByTexts(texts)
    }

    override suspend fun dictionaryNames(): Map<Int, String> {
        val db = AppDatabase.getDatabase(context)
        return db.dictionaryDao().getAllDictionaries().associate { it.id to it.name }
    }
}

object JpDictGravity {
    const val NONE = 0
    const val TOP = 1
    const val BOTTOM = 2
    const val LEFT = 4
    const val RIGHT = 8
    const val START = 16
    const val END = 32
    const val CENTER_HORIZONTAL = 64
    const val CENTER_VERTICAL = 128
    const val CENTER = CENTER_HORIZONTAL or CENTER_VERTICAL
}

fun Int.toAndroidGravity(): Int {
    var g = Gravity.NO_GRAVITY
    if (this and JpDictGravity.TOP != 0) g = g or Gravity.TOP
    if (this and JpDictGravity.BOTTOM != 0) g = g or Gravity.BOTTOM
    if (this and JpDictGravity.LEFT != 0) g = g or Gravity.LEFT
    if (this and JpDictGravity.RIGHT != 0) g = g or Gravity.RIGHT
    if (this and JpDictGravity.START != 0) g = g or Gravity.START
    if (this and JpDictGravity.END != 0) g = g or Gravity.END
    if (this and JpDictGravity.CENTER_HORIZONTAL != 0) g = g or Gravity.CENTER_HORIZONTAL
    if (this and JpDictGravity.CENTER_VERTICAL != 0) g = g or Gravity.CENTER_VERTICAL
    return g
}

fun Int.toJpDictGravity(): Int {
    var g = JpDictGravity.NONE
    if (this and Gravity.TOP != 0) g = g or JpDictGravity.TOP
    if (this and Gravity.BOTTOM != 0) g = g or JpDictGravity.BOTTOM
    if (this and Gravity.LEFT != 0) g = g or JpDictGravity.LEFT
    if (this and Gravity.RIGHT != 0) g = g or JpDictGravity.RIGHT
    if (this and Gravity.START != 0) g = g or JpDictGravity.START
    if (this and Gravity.END != 0) g = g or JpDictGravity.END
    if (this and Gravity.CENTER_HORIZONTAL != 0) g = g or JpDictGravity.CENTER_HORIZONTAL
    if (this and Gravity.CENTER_VERTICAL != 0) g = g or JpDictGravity.CENTER_VERTICAL
    return g
}

object JpDictKeyEvent {
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_BACK = 4
    const val KEYCODE_ENTER = 66
    const val KEYCODE_ESCAPE = 111
    
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_BUTTON_L1 = 102
    const val KEYCODE_BUTTON_R1 = 103
    const val KEYCODE_BUTTON_L2 = 104
    const val KEYCODE_BUTTON_R2 = 105
}

fun MotionEvent.getJoystickKeyCode(): Int {
    fun getCenteredAxis(event: MotionEvent, axis1: Int, axis2: Int): Float {
        val range1 = event.device?.getMotionRange(axis1, event.source)
        val v1 = if (range1 != null) event.getAxisValue(axis1) else 0f
        val range2 = event.device?.getMotionRange(axis2, event.source)
        val v2 = if (range2 != null) event.getAxisValue(axis2) else 0f
        
        val v = if (abs(v1) > abs(v2)) v1 else v2
        return if (abs(v) > 0.3f) v else 0f
    }

    val x = getCenteredAxis(this, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X)
    val y = getCenteredAxis(this, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y)

    val threshold = 0.5f
    return when {
        x > threshold -> KeyEvent.KEYCODE_DPAD_RIGHT
        x < -threshold -> KeyEvent.KEYCODE_DPAD_LEFT
        y > threshold -> KeyEvent.KEYCODE_DPAD_DOWN
        y < -threshold -> KeyEvent.KEYCODE_DPAD_UP
        else -> 0
    }
}

fun MotionEvent.getFocusCoords(): Pair<Float, Float> {
    var (sx, sy, c) = Triple(0f, 0f, 0)
    for (i in 0 until pointerCount) {
        if (actionMasked == MotionEvent.ACTION_POINTER_UP && i == actionIndex) continue
        sx += getX(i); sy += getY(i); c++
    }
    return if (c > 0) sx / c to sy / c else 0f to 0f
}

fun handleJoystick(
    event: MotionEvent,
    lastKeyCode: Int,
    onKeyChange: (Int) -> Unit,
    onSimulatedKeyEvent: (KeyEvent) -> Unit
): Boolean {
    if (event.action != MotionEvent.ACTION_MOVE) return false

    val currentKeyCode = event.getJoystickKeyCode()
    if (currentKeyCode != lastKeyCode) {
        if (lastKeyCode != 0) {
            onSimulatedKeyEvent(KeyEvent(KeyEvent.ACTION_UP, lastKeyCode))
        }
        onKeyChange(currentKeyCode)
        if (currentKeyCode != 0) {
            onSimulatedKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, currentKeyCode))
        }
    }
    return true
}
