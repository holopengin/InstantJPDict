package com.holopengin.instantjpdict

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * #57 (Feat — image share intent): receive a single shared image and OCR it
 * through exactly the same surface as the accessibility overlay.
 *
 * The activity does no overlay code of its own. It decodes the shared image
 * (honouring EXIF orientation, which the screenshot path never needed — a
 * camera JPEG arrives rotated) into a bitmap the size of the overlay view, then
 * hands it to [OcrOverlayView], which runs the shared detect/recognise render
 * pass and owns the boxes, lookup, popup and close behaviour.
 *
 * Exit is the same as the overlay's, per the issue: a tap on empty space or the
 * close button. Both reach [OcrOverlayView]'s single close path; when there is
 * no layer left to close it calls [dismissOverlay] and this activity finishes.
 *
 * #57 rotation follow-up: two rotate buttons (⟳ / ⟲) sit in this activity's own
 * chrome — deliberately NOT inside [OcrOverlayView], whose other host is the
 * accessibility service and must not change. Each press turns the image a
 * quarter turn clockwise or counterclockwise and re-runs OCR on the rotated
 * composite. The geometry that keeps the boxes and tap targets aligned lives in
 * [ImageRotation], which is plain Kotlin and unit-tested.
 *
 * ACTION_SEND_MULTIPLE is a deliberate later follow-up — only ACTION_SEND is
 * handled here.
 */
class ShareImageActivity : AppCompatActivity(), OcrOverlayView.Host {

    /** Overlay-scoped work: the image decode, the shared environment loads and
     *  the OCR run. Cancelled in [onDestroy]. */
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var engine: OcrEngine
    private val overlayState = OcrOverlayStateController()

    /**
     * The decoded, EXIF-upright image, kept UNROTATED for as long as the
     * activity lives: every rotate press re-derives the composite from this one
     * bitmap, so quarter turns never compound resampling, and a fourth press
     * returns to the original pixels.
     */
    private var baseImage: Bitmap? = null

    /** The composed image, exactly the size of the overlay view (OCR input and
     *  what the view displays). Replaced on every rotate press. */
    private var image: Bitmap? = null

    /** Quarter turns clockwise applied by the rotate buttons; 0..3, cumulative. */
    private var rotationTurns = 0

    /** The container size the composite is built at — the same pixels the
     *  overlay view's box coordinates are expressed in. */
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var overlayView: OcrOverlayView? = null

    /** The activity's own rotate pair; kept above the full-screen overlay view
     *  so it stays tappable. */
    private var rotateBar: View? = null

    // ---- OcrOverlayView.Host ----

    override val bitmap: Bitmap
        get() = image ?: error("overlay requested before the image was decoded")
    override val ocrEngine: OcrEngine get() = engine
    override val controller: OcrOverlayStateController get() = overlayState

    override fun dismissOverlay() {
        finish()
    }

    override fun requestSoftInputResize() {
        // The overlay's manual-input IME handling is a no-op here: an activity
        // already resizes its own window.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** No floating button to sit under; a fixed corner is all the button needs.
     *  Top-left — which is why the rotate pair goes bottom-left. */
    override fun closeButtonOrigin(): Pair<Int, Int> = 100 to 100

    /** No floating button to keep in step. */
    override fun onCloseButtonMoved(x: Int, y: Int) {}

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = OcrEngine(this)
        OverlayEnvironment.prepare(this, overlayState, overlayScope)

        // Fill the display like the overlay window does, so the bitmap the view
        // is given maps 1:1 onto its own pixels (the overlay's box coordinates
        // are bitmap pixels; any scale factor between the two would misalign
        // every hit rect).
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val container = FrameLayout(this)
        setContentView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val uri = sharedImageUri(intent)
        if (uri == null) {
            Toast.makeText(this, "No image to read", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Bottom-left: the view's draggable close button starts in the top-left
        // (`closeButtonOrigin`), the status strip owns the top edge, and the
        // confidence controls run down the left at vertical centre — so this is
        // the one corner where the pair collides with nothing. It is added
        // first and brought back to the front once the overlay view exists, so
        // the full-screen surface never swallows a press meant for a button.
        rotateBar = buildRotateBar()
        val barMargin = (ROTATE_BAR_MARGIN_DP * resources.displayMetrics.density).roundToInt()
        container.addView(
            rotateBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                // Until the window insets land, keep the pair off the very edge.
                leftMargin = barMargin
                bottomMargin = barMargin
            }
        )
        // The window lays out under the system bars (FLAG_LAYOUT_NO_LIMITS), so
        // keep the pair clear of the navigation bar whatever its shape.
        ViewCompat.setOnApplyWindowInsetsListener(rotateBar!!) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val base = (ROTATE_BAR_MARGIN_DP * resources.displayMetrics.density).roundToInt()
            v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = base + bars.left
                bottomMargin = base + bars.bottom
                rightMargin = base
                topMargin = base + bars.top
            }
            insets
        }

        // Compose at the container's own size, so the image the view receives is
        // exactly the size of the view.
        container.post {
            val width = container.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val height = container.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            surfaceWidth = width
            surfaceHeight = height
            overlayScope.launch {
                val base = withContext(Dispatchers.IO) { decodeOriented(uri, width, height) }
                if (isFinishing || isDestroyed) {
                    base?.recycle()
                    return@launch
                }
                if (base == null) {
                    Toast.makeText(this@ShareImageActivity, "Could not read image", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                baseImage = base
                val composed = withContext(Dispatchers.IO) {
                    composeForScreen(base, width, height, rotationTurns)
                }
                if (isFinishing || isDestroyed) {
                    composed?.recycle()
                    return@launch
                }
                if (composed == null) {
                    Toast.makeText(this@ShareImageActivity, "Could not read image", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                image = composed
                val view = OcrOverlayView(this@ShareImageActivity, this@ShareImageActivity)
                overlayView = view
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                rotateBar?.bringToFront()
                view.startOcr()
            }
        }
    }

    /**
     * The view tree is our own, but the activity's default back handler would
     * finish on the first press. Route every press through the view's shared
     * close so back closes one layer at a time, exactly like the overlay.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val view = overlayView
        if (view == null) {
            super.onBackPressed()
        } else {
            view.handleBackKey()
        }
    }

    override fun onDestroy() {
        overlayView?.onClosed()
        overlayView = null
        overlayScope.cancel()
        if (::engine.isInitialized) engine.close()
        val composed = image
        image = null
        val base = baseImage
        baseImage = null
        super.onDestroy()
        // The views are gone by now; nothing is drawing these bitmaps any more.
        if (composed != null && !composed.isRecycled) composed.recycle()
        if (base != null && base !== composed && !base.isRecycled) base.recycle()
    }

    // ---- rotate chrome ----

    /**
     * The two rotate buttons, in this activity's chrome rather than the shared
     * surface. One helper builds both, so the pair cannot drift apart.
     */
    private fun buildRotateBar(): View {
        val size = (ROTATE_BUTTON_DP * resources.displayMetrics.density).roundToInt()
        val gap = (ROTATE_BUTTON_GAP_DP * resources.displayMetrics.density).roundToInt()
        val bar = LinearLayout(this).apply {
            tag = "rotate_controls"
            orientation = LinearLayout.HORIZONTAL
        }
        val counter = rotateButton(ROTATE_CCW_GLYPH, "Rotate counterclockwise", size) { rotate(clockwise = false) }
        val clockwise = rotateButton(ROTATE_CW_GLYPH, "Rotate clockwise", size) { rotate(clockwise = true) }
        bar.addView(counter, LinearLayout.LayoutParams(size, size).apply { rightMargin = gap })
        bar.addView(clockwise, LinearLayout.LayoutParams(size, size))
        return bar
    }

    private fun rotateButton(label: String, description: String, sizePx: Int, onClick: () -> Unit): CenteredButton =
        CenteredButton(this).apply {
            tag = "rotate_button_$label"
            text = label
            contentDescription = description
            setTextColor(ROTATE_GLYPH_COLOR)
            textSize = ROTATE_GLYPH_TEXT_SIZE_SP
            includeFontPadding = false
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            background = rotateButtonBackground()
            setOnClickListener { onClick() }
        }

    /**
     * Dark fill with the app's cyan outline: readable over a bright photo and
     * over the overlay's dark scrim alike, which the logo drawable the other
     * buttons use is not (it is a glyph, not a text background).
     *
     * The fill and outline are deliberately semi-transparent so the image reads
     * through the button; the glyph stays opaque so it stays legible over a
     * bright photo. Values are
     * named so they are a one-line nudge on device.
     */
    private fun rotateButtonBackground(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ROTATE_BUTTON_RADIUS_DP * resources.displayMetrics.density
        setColor(ROTATE_BUTTON_FILL)
        setStroke((1.5f * resources.displayMetrics.density).roundToInt(), ROTATE_BUTTON_STROKE)
    }

    /**
     * One press: bump the quarter-turn count, rebuild the composite from the
     * unrotated base, hand the surface the new bitmap and have it re-run detect
     * + recognise.
     *
     * The composite is rebuilt at the view's own pixel size with the fit derived
     * from the ROTATED dimensions ([ImageRotation.fitRotated]); that is the step
     * that keeps the recognition boxes and the per-character hit rects on the
     * glyphs, because a quarter turn swaps the image's width and height. The
     * previous composite is only recycled once the view has switched its display
     * copy and the new run (which reads [image]) has taken over.
     */
    private fun rotate(clockwise: Boolean) {
        val view = overlayView ?: return
        val base = baseImage ?: return
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        rotationTurns = ImageRotation.turn(rotationTurns, clockwise)
        val turns = rotationTurns
        overlayScope.launch {
            val recomposed = withContext(Dispatchers.IO) {
                composeForScreen(base, width, height, turns)
            }
            if (isFinishing || isDestroyed) {
                recomposed?.recycle()
                return@launch
            }
            if (recomposed == null) return@launch
            val previous = image
            image = recomposed
            view.refreshImage()
            if (previous != null && previous !== recomposed && !previous.isRecycled) previous.recycle()
        }
    }

    // ---- image input ----

    private fun sharedImageUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    /**
     * Draw [base] turned by [turns] quarter turns clockwise, fit-centred onto a
     * [targetW] x [targetH] black canvas. The result is the exact size of the
     * overlay view, so OCR boxes and hit rects line up with what is on screen
     * whatever the orientation.
     */
    private fun composeForScreen(base: Bitmap, targetW: Int, targetH: Int, turns: Int): Bitmap? {
        val rotated = rotateBitmap(base, turns)
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        // Recomputed from the ROTATED dimensions, not the base ones: this is the
        // whole point of ImageRotation.fitRotated. Reusing the pre-rotation fit
        // would place the turned image at the wrong scale/offset and every box
        // drawn over it would sit off the glyph it belongs to.
        val fit = ImageRotation.fitRotated(base.width, base.height, targetW, targetH, turns)
        val dst = RectF(
            fit.left.toFloat(), fit.top.toFloat(),
            (fit.left + fit.width).toFloat(), (fit.top + fit.height).toFloat()
        )
        canvas.drawBitmap(rotated, null, dst, paint)
        if (rotated !== base) rotated.recycle()
        return out
    }

    /** Clockwise quarter turns of [base]; [base] itself when there is nothing to
     *  do, so the caller can tell whether it owns a second bitmap. */
    private fun rotateBitmap(base: Bitmap, turns: Int): Bitmap {
        val degrees = ImageRotation.degrees(turns)
        if (degrees == 0f) return base
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
    }

    /** Stream-decode via [android.content.ContentResolver]; the shared URI is a
     *  content URI, not a file path, so it is never opened as a file. */
    private fun decodeOriented(uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val orientation = readExifOrientation(uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = ImageShareFit.sampleSize(bounds.outWidth, bounds.outHeight, max(targetW, targetH))

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val correction = ExifOrientation.correction(orientation)
        if (correction.isIdentity) return decoded

        val matrix = Matrix()
        matrix.setRotate(correction.rotationDegrees.toFloat())
        if (correction.flipHorizontal) matrix.postScale(-1f, 1f)
        if (correction.flipVertical) matrix.postScale(1f, -1f)

        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun readExifOrientation(uri: Uri): Int = try {
        // A content URI's InputStream is not seekable, and androidx's ExifInterface needs a
        // seekable source, so go through the file descriptor rather than the stream.
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            ExifInterface(pfd.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifOrientation.NORMAL
            )
        } ?: ExifOrientation.NORMAL
    } catch (e: Exception) {
        // Some providers serve files without EXIF; treat as already upright.
        Log.w("ShareImageActivity", "EXIF orientation unreadable", e)
        ExifOrientation.NORMAL
    }

    companion object {
        /** The button texts, exactly as the issue asks for them. */
        private const val ROTATE_CW_GLYPH = "⟳"
        private const val ROTATE_CCW_GLYPH = "⟲"

        /** ≥48dp touch targets (platform minimum), a step up for legibility. */
        private const val ROTATE_BUTTON_DP = 52
        private const val ROTATE_BUTTON_GAP_DP = 8
        private const val ROTATE_BAR_MARGIN_DP = 12
        private const val ROTATE_BUTTON_RADIUS_DP = 10f
        private const val ROTATE_GLYPH_TEXT_SIZE_SP = 24f

        private val ROTATE_BUTTON_FILL = Color.argb(130, 25, 25, 25)
        private val ROTATE_BUTTON_STROKE = Color.argb(150, 0, 255, 255)
        private val ROTATE_GLYPH_COLOR = Color.parseColor("#00FFFF")
    }
}
