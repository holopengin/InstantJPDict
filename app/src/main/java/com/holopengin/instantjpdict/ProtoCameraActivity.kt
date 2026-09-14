package com.holopengin.instantjpdict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PROTOTYPE (#78, throwaway — not for main, no tests, no polish beyond runnable).
 *
 * The question this answers: can our own viewfinder drop a captured photo into
 * the recognition pipeline the app already has, and does the 1px full-frame
 * crosshair read well enough in the hand to line a text line up (i.e. is
 * alignment workable before rotated-box support lands, #53)?
 *
 * Shape of the answer:
 *   camera → [ProtoCrosshairView] over the live preview → ImageCapture to a file
 *   → a FileProvider URI → ACTION_SEND, explicitly addressed to
 *   [ShareImageActivity]. That last step is the whole point: the capture reuses
 *   the EXISTING entry (`sharedImageUri` → `Intent.EXTRA_STREAM`, the same thing
 *   the system share sheet hands it), so there is exactly one decode / EXIF /
 *   compose / [OcrOverlayView] path in the app and no second renderer to drift.
 *   The boxes therefore land wherever the share path already puts them — which
 *   is what makes this a test of the capture, not a copy of the renderer.
 *
 * Deliberate prototype decisions:
 *  - Portrait-locked, so the in-hand crosshair judgement happens in one
 *    orientation and the camera is not reopened on every quarter turn.
 *  - Back camera, minimise-latency capture mode, no flash. Tapping the preview refocuses
 *    there — the ordinary camera gesture. Framing has its own square button in the
 *    bottom-right, because a tap that silently changed what the capture would contain read
 *    as an accidental zoom.
 *  - The preview shows the WHOLE captured frame by default
 *    ([PreviewView.ScaleType.FIT_CENTER]) — a portrait view with a landscape
 *    4:3 sensor frame, so the live image is a band across the middle of the
 *    screen and what the reticle is laid over is exactly what the capture will
 *    contain. The Zoom button switches to
 *    [PreviewView.ScaleType.FILL_CENTER], which fills the screen like an
 *    ordinary camera app by showing only the middle slice of the frame — and
 *    the photo handed to the model is cropped to that same slice
 *    ([cropToPreviewFraming]), so the recogniser reads what was on screen and
 *    not text the user never saw. Both framings put the frame's centre under
 *    the reticle's crossing point, so the comparison is about what is visible
 *    around it, not about where the centre is.
 *  - The FILL_CENTER crop is taken in the handoff, not by a CameraX
 *    [androidx.camera.core.ViewPort]. A viewport is the 'proper' route — the
 *    sensor pipeline itself is cropped, so preview and capture agree by
 *    construction and no JPEG is re-encoded — but it only applies to a bound
 *    use-case group, so every framing toggle would have to unbind and rebind
 *    the camera, turning the live preview (the one thing on this branch that
 *    cannot be checked without the phone) into the part being changed. The
 *    cost of cropping in the handoff is one bounded decode, a rotate and a
 *    re-encode of a ~0.9 MP result, on a background thread; FIT_CENTER hands
 *    over the camera's own file untouched and pays nothing at all.
 *  - The crop is computed through the EXIF-UPRIGHT orientation — the stored
 *    pixels are LANDSCAPE (4032x3024) while the picture is PORTRAIT, so a rect
 *    taken in stored-pixel space would be turned a quarter turn against the
 *    picture and crop the wrong axis. It is bounded by [CROP_LONG_SIDE_PX], and
 *    any failure hands over the ORIGINAL file and says so in the status line
 *    and the log rather than losing the capture.
 *  - COPIES_FORWARD: never taken; the captured file — and, in FILL_CENTER, the
 *    cropped one beside it — is left in the cache dir (see [CAPTURE_DIR_NAME])
 *    so it can be pulled off the device for evidence.
 */
class ProtoCameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView

    /** The camera's controls (focus). Kept from the bind, which returns the session. */
    private var cameraControl: androidx.camera.core.CameraControl? = null

    /** The reticle, kept so a resumed activity can pick up a moved tuning slider. */
    private lateinit var crosshair: ProtoCrosshairView

    /**
     * The decode / rotate / crop of one FILL_CENTER handoff. One thread, because
     * a capture is one press and the shutter stays disabled until the handoff:
     * a 12 MP decode is tens of megabytes of ARGB and must never run on the
     * thread that is also drawing the preview. Shut down with the activity.
     */
    private val cropExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Tap to focus: the ordinary camera gesture, moved here from the framing toggle. */
    private fun focusAt(x: Float, y: Float) {
        val control = cameraControl ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        try {
            control.startFocusAndMetering(action)
            Log.i(TAG, "tap-focus at ${x.toInt()},${y.toInt()}")
        } catch (t: Throwable) {
            Log.e(TAG, "tap-focus failed", t)
        }
    }

    override fun onResume() {
        super.onResume()
        // A gap slider moved in the tuning screen applies on return, without a restart.
        if (::crosshair.isInitialized) crosshair.reloadGap()
        // Returning from the results view: capture() disables the shutter and only its FAILURE
        // paths re-enable it, so without this the button stayed grey and unclickable until the
        // app restarted. The capture session lives as long as this activity, so having one is
        // the right test.
        if (::captureButton.isInitialized) captureButton.isEnabled = imageCapture != null
    }

    override fun onDestroy() {
        cropExecutor.shutdown()
        super.onDestroy()
    }
    private lateinit var statusView: TextView
    private lateinit var captureButton: Button
    private var imageCapture: ImageCapture? = null

    /** Which preview framing is on screen; see [togglePreviewFraming]. */
    private var previewFill = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            tag = "proto_preview"
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // Default: the whole captured frame visible (see the class doc).
            scaleType = PreviewView.ScaleType.FIT_CENTER
            // Tap = refocus at that point, the ordinary camera gesture. Framing moved to its
            // own button: a tap that silently changed what the capture would contain read as
            // an accidental zoom.
            setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    focusAt(ev.x, ev.y)
                    v.performClick() // silences ClickableViewAccessibility; no click action
                }
                true
            }
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // The reticle. Added after the preview so it draws over the live image;
        // it never takes touch (no click listeners, not clickable), so the
        // shutter button below stays reachable. Same bounds as the preview, so
        // its crossing point is the view centre — and therefore the centre of
        // whatever the FILL_CENTER crop turns out to be.
        root.addView(
            ProtoCrosshairView(this).also { crosshair = it },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this).apply {
            tag = "proto_status"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 12f
            text = "PROTOTYPE #78 — camera mode"
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        )

        captureButton = Button(this).apply {
            tag = "proto_capture"
            text = "Capture"
            isEnabled = false
            // Cleared so the fixed square side below wins over Button's own minimums.
            minWidth = 0
            minHeight = 0
            setOnClickListener { capture() }
        }
        val zoomSide = (CAPTURE_BUTTON_DP * resources.displayMetrics.density).roundToInt()
        root.addView(
            Button(this).apply {
                tag = "proto_zoom"
                text = "Zoom"
                minWidth = 0
                minHeight = 0
                setOnClickListener { togglePreviewFraming() }
            },
            FrameLayout.LayoutParams(zoomSide, zoomSide).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = 48
                marginEnd = 24
            }
        )

        val captureSide = (CAPTURE_BUTTON_DP * resources.displayMetrics.density).roundToInt()
        root.addView(
            captureButton,
            FrameLayout.LayoutParams(
                // Square: this is a shutter, not a label. WRAP_CONTENT made it a wide, short
                // pill; one side for both dimensions keeps it square whatever the label
                // measures, and the constant is the single knob for its size.
                captureSide, captureSide
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 48
            }
        )

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                cameraControl = camera.cameraControl
                captureButton.isEnabled = true
                statusView.text = statusText()
                // The crop's one assumption, printed so it can be checked from logcat:
                // the FILL_CENTER rect is worked out against the CAPTURE's frame, and it
                // is only the right rect if the preview stream is the same shape.
                Log.i(
                    TAG,
                    "camera bound, viewfinder up: preview " +
                        "${preview.resolutionInfo?.resolution}, capture " +
                        "${capture.resolutionInfo?.resolution}, view " +
                        "${previewView.width}x${previewView.height}"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                statusView.text = "PROTOTYPE #78 — camera failed: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * The Zoom button: FIT_CENTER (the whole captured frame, letterboxed into
     * the portrait view, and the whole frame goes to the model) <->
     * FILL_CENTER (screen filled by the middle slice of the frame, and that
     * slice is what goes to the model — see [cropToPreviewFraming]). The status
     * line names the active framing, so the in-hand verdict can be written down
     * without having to remember which one was on screen, and the framing is
     * logged for the same reason.
     */
    private fun togglePreviewFraming() {
        previewFill = !previewFill
        previewView.scaleType = if (previewFill) {
            PreviewView.ScaleType.FILL_CENTER
        } else {
            PreviewView.ScaleType.FIT_CENTER
        }
        statusView.text = statusText()
        Log.i(TAG, "preview framing = ${if (previewFill) "FILL_CENTER" else "FIT_CENTER"}")
    }

    private fun statusText(): String = "PROTOTYPE #78 — framing: ${if (previewFill) {
        "FILL_CENTER (screen filled; the photo sent is this crop)"
    } else {
        "FIT_CENTER (whole photo visible, and sent whole)"
    }}. Line the text up on the crossing point; the Zoom button switches framing."

    /**
     * One press: write a JPEG, hand its URI to the EXISTING share entry.
     *
     * Which JPEG depends on the framing that was on screen at the press —
     * latched here, not re-read when the file lands, so a toggle while the
     * shutter is writing cannot pick the wrong one. FILL_CENTER goes through
     * [cropToPreviewFraming]; FIT_CENTER hands over the camera's own file
     * untouched.
     *
     * [ShareImageActivity] is started explicitly rather than through the system
     * share sheet, but with exactly the intent it documents it consumes
     * (ACTION_SEND + EXTRA_STREAM), so nothing about its decode / EXIF /
     * compose / overlay path is special-cased for the camera. No
     * FLAG_ACTIVITY_NEW_TASK: same app, same task, so back from the results
     * returns here — and the activity finishes so this does not pile up.
     */
    private fun capture() {
        val capture = imageCapture ?: return
        captureButton.isEnabled = false
        statusView.text = "PROTOTYPE #78 — capturing..."
        val framingWasFill = previewFill
        val dir = File(cacheDir, CAPTURE_DIR_NAME).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val file = File(dir, "capture-$stamp.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    Log.i(
                        TAG,
                        "captured ${file.length()} bytes -> ${file.absolutePath} (resultUri=${results.savedUri})"
                    )
                    if (!framingWasFill) {
                        // The default framing's whole point: the photo already holds exactly
                        // what was on screen, so the crop path is skipped and the file the
                        // camera wrote is the one that goes over.
                        handOff(file, "FIT_CENTER: full frame, ${file.length()} B")
                        return
                    }
                    cropThenHandOff(file, File(dir, "capture-$stamp-zoom.jpg"))
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    statusView.text = "PROTOTYPE #78 — capture failed: ${exception.message}"
                    captureButton.isEnabled = true
                }
            }
        )
    }

    /**
     * FILL_CENTER's handoff: crop [source] to the slice the preview showed, off
     * the main thread, then hand the result over.
     *
     * ANY failure — an unreadable EXIF tag, a decode that returns null, a full
     * heap — falls back to [source] and says so on the status line and in the
     * log. A photo one framing too wide is a worse answer than no answer, but a
     * far better one than a lost capture.
     */
    private fun cropThenHandOff(source: File, dest: File) {
        val view = previewView
        if (view.width <= 0 || view.height <= 0) {
            Log.w(TAG, "preview not laid out (${view.width}x${view.height}); handing the full frame")
            handOff(source, "FILL_CENTER: crop skipped, preview not laid out — full frame")
            return
        }
        // The view's own aspect, not the screen's: this is the shape the preview
        // actually filled, so this is the shape the preview actually showed.
        val viewAspect = view.width.toFloat() / view.height.toFloat()
        cropExecutor.execute {
            val cropped = try {
                cropToPreviewFraming(source, dest, viewAspect)
            } catch (t: Throwable) {
                Log.e(TAG, "crop failed; handing the full frame instead", t)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (cropped != null) {
                    handOff(
                        cropped.file,
                        "FILL_CENTER: crop ${cropped.width} x ${cropped.height} sent" +
                            " (${cropped.file.length()} B of ${source.length()} B)"
                    )
                } else {
                    handOff(source, "FILL_CENTER: crop failed (see log) — full frame instead")
                }
            }
        }
    }

    /** What [cropToPreviewFraming] produced: the file, and the OCR input's own size. */
    private data class CroppedFrame(val file: File, val width: Int, val height: Int)

    /**
     * The centred region the FILL_CENTER preview showed, taken from [source] and
     * written to [dest] as an upright JPEG. Null when anything failed — see
     * [cropThenHandOff], which then hands over the original.
     *
     * The frame is decoded with [cropSampleSize]'s `inSampleSize` and always
     * through the UPRIGHT orientation: the stored pixels on this device are
     * LANDSCAPE (4032x3024) with an EXIF 6 tag while the picture is PORTRAIT,
     * so a rect computed against the stored pixels is a quarter turn out from
     * the picture and crops the wrong dimension. That is trap #1 on this
     * branch, and it is why the geometry below only ever sees an upright bitmap.
     */
    private fun cropToPreviewFraming(source: File, dest: File, viewAspect: Float): CroppedFrame? {
        val orientation = try {
            ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifOrientation.NORMAL)
        } catch (t: Throwable) {
            // No EXIF at all is the same as already upright, and is not worth
            // abandoning the crop for.
            Log.w(TAG, "EXIF unreadable on ${source.absolutePath}; assuming upright", t)
            ExifOrientation.NORMAL
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "no bounds for ${source.absolutePath}; no crop")
            return null
        }
        val sample = cropSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options)
        if (decoded == null) {
            Log.w(TAG, "decode returned null for ${source.absolutePath}; no crop")
            return null
        }

        val upright = uprightCopy(decoded, orientation)
        if (upright !== decoded) decoded.recycle()
        val frameW = upright.width
        val frameH = upright.height
        val rect = cropRect(frameW, frameH, viewAspect)
        val out = Bitmap.createBitmap(upright, rect.left, rect.top, rect.width(), rect.height())
        if (out !== upright) upright.recycle()
        val cropW = out.width
        val cropH = out.height

        val wrote = try {
            FileOutputStream(dest).use {
                out.compress(Bitmap.CompressFormat.JPEG, CROP_JPEG_QUALITY, it)
            }
        } finally {
            out.recycle()
        }
        if (!wrote) {
            Log.w(TAG, "JPEG write failed for ${dest.absolutePath}; no crop")
            return null
        }
        // The crop is already upright, so the file says so: the reader then needs
        // no guessing about a tag the encoder never wrote.
        try {
            ExifInterface(dest).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifOrientation.NORMAL.toString())
                saveAttributes()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not stamp orientation on ${dest.absolutePath}", t)
        }

        Log.i(
            TAG,
            "crop: ${bounds.outWidth}x${bounds.outHeight} stored (orientation $orientation," +
                " sample $sample) -> upright ${frameW}x${frameH} -> ${rect.width()}x${rect.height()}" +
                " at ${rect.left},${rect.top} (viewAspect=${"%.3f".format(viewAspect)})" +
                " -> ${dest.absolutePath} (${dest.length()} B)"
        )
        return CroppedFrame(dest, cropW, cropH)
    }

    /**
     * The region a FILL_CENTER preview showed, as a rect in an [frameW] x
     * [frameH] UPRIGHT frame's pixels.
     *
     * FILL_CENTER scales the frame up until it covers a view of [viewAspect] and
     * lets the overflow fall off both sides, so the visible region is the
     * centred rect with the VIEW's aspect: full height and a narrower width on
     * this phone (a 0.45-ish view against a 0.75 frame), or full width and a
     * shorter height if the view ever came out the wider one. Centred, so the
     * frame's centre — and with it the reticle's crossing point — stays inside
     * the crop, which is what keeps the crosshair meaning the same thing in both
     * framings.
     */
    private fun cropRect(frameW: Int, frameH: Int, viewAspect: Float): Rect {
        if (frameW <= 0 || frameH <= 0 || viewAspect <= 0f) return Rect(0, 0, frameW, frameH)
        val frameAspect = frameW.toFloat() / frameH.toFloat()
        return if (viewAspect >= frameAspect) {
            // The view is the wider shape: the frame covers it from edge to edge and
            // the TOP AND BOTTOM fall off.
            val height = (frameW / viewAspect).roundToInt().coerceIn(1, frameH)
            val top = (frameH - height) / 2
            Rect(0, top, frameW, top + height)
        } else {
            // The view is the taller shape (this device): full height, the SIDES fall off.
            val width = (frameH * viewAspect).roundToInt().coerceIn(1, frameW)
            val left = (frameW - width) / 2
            Rect(left, 0, left + width, frameH)
        }
    }

    /**
     * The power-of-two `inSampleSize` that lands the stored frame's long side at
     * or under [CROP_LONG_SIDE_PX]. That one constant is the whole knob: it is
     * both the memory bound (a 12 MP JPEG is ~48 MB of ARGB at full size, and
     * this app has already OOM'd against a 256 MB heap) and the size the
     * recogniser is ultimately fed.
     */
    private fun cropSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > CROP_LONG_SIDE_PX) sample *= 2
        return sample
    }

    /**
     * [decoded] turned so the picture is upright, or [decoded] itself when it
     * already is. The same correction the share path applies, for the same
     * reason ([ShareImageActivity]): this device stores a portrait picture as
     * landscape pixels plus an orientation tag.
     */
    private fun uprightCopy(decoded: Bitmap, orientation: Int): Bitmap {
        val correction = ExifOrientation.correction(orientation)
        if (correction.isIdentity) return decoded
        val matrix = Matrix()
        matrix.setRotate(correction.rotationDegrees.toFloat())
        if (correction.flipHorizontal) matrix.postScale(-1f, 1f)
        if (correction.flipVertical) matrix.postScale(1f, -1f)
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    /**
     * The single handoff: [file]'s FileProvider URI into the existing share
     * entry, and [note] on the status line — which is left standing when the
     * results view closes, so the framing that was actually recognised can be
     * read off the screen afterwards.
     */
    private fun handOff(file: File, note: String) {
        val uri = try {
            FileProvider.getUriForFile(
                this,
                "$packageName$FILE_PROVIDER_SUFFIX",
                file
            )
        } catch (t: Throwable) {
            Log.e(TAG, "FileProvider failed", t)
            statusView.text = "PROTOTYPE #78 — FileProvider failed: ${t.message}"
            captureButton.isEnabled = true
            return
        }
        Log.i(TAG, "handing $uri to ShareImageActivity ($note)")
        statusView.text = "PROTOTYPE #78 — $note"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setClass(this@ProtoCameraActivity, ShareImageActivity::class.java)
        }
        startActivity(send)
        // Deliberately NOT finishing: leaving this activity on the back stack is
        // what makes the results view return HERE rather than to the main
        // activity when it closes. The camera rebinds its preview on resume.
    }

    companion object {
        private const val TAG = "ProtoCamera"
        private const val CAPTURE_DIR_NAME = "proto-camera"
        /** Side of the square shutter button, in dp. One knob for its size. */
        private const val CAPTURE_BUTTON_DP = 84f

        /**
         * The long side, in pixels, of the frame the FILL_CENTER crop is taken
         * from — 2048 turns the 4032-px capture into a 2016-px decode, which is
         * ~12 MB of ARGB instead of ~48 MB. The single knob for the memory bound
         * and, with it, the size the recogniser is fed.
         */
        private const val CROP_LONG_SIDE_PX = 2048

        /** High on purpose: this JPEG is the recogniser's input, not a thumbnail. */
        private const val CROP_JPEG_QUALITY = 95

        /** The FileProvider authority suffix; the manifest declares `${applicationId}.protofileprovider`. */
        const val FILE_PROVIDER_SUFFIX = ".protofileprovider"
    }
}
