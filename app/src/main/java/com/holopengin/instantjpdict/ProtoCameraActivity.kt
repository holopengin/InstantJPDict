package com.holopengin.instantjpdict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

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
 *  - Back camera, minimise-latency capture mode, no flash/tap-to-focus.
 *  - The preview shows the WHOLE captured frame by default
 *    ([PreviewView.ScaleType.FIT_CENTER]) — a portrait view with a landscape
 *    4:3 sensor frame, so the live image is a band across the middle of the
 *    screen and what the reticle is laid over is exactly what the capture will
 *    contain. Tapping the preview switches to
 *    [PreviewView.ScaleType.FILL_CENTER], which fills the screen like an
 *    ordinary camera app but shows only the middle slice of the frame: the
 *    capture then holds text the user never saw. Both framings put the frame's
 *    centre under the reticle's crossing point, so the comparison is about
 *    what is visible around it, not about where the centre is.
 *  - COPIES_FORWARD: never taken; the captured file is left in the cache dir
 *    (see [CAPTURE_DIR_NAME]) so it can be pulled off the device for evidence.
 */
class ProtoCameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView

    /** The reticle, kept so a resumed activity can pick up a moved tuning slider. */
    private lateinit var crosshair: ProtoCrosshairView

    override fun onResume() {
        super.onResume()
        // A gap slider moved in the tuning screen applies on return, without a restart.
        if (::crosshair.isInitialized) crosshair.reloadGap()
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
            setOnClickListener { togglePreviewFraming() }
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
        // shutter button below stays reachable.
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
            setOnClickListener { capture() }
        }
        root.addView(
            captureButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                captureButton.isEnabled = true
                statusView.text = statusText()
                Log.i(TAG, "camera bound, viewfinder up")
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                statusView.text = "PROTOTYPE #78 — camera failed: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Tap on the live preview: FIT_CENTER (the whole captured frame, letterboxed
     * into the portrait view) <-> FILL_CENTER (screen filled, capture still
     * holding the whole frame — so text the user never saw is in the photo too).
     * The status line names the active framing, so the in-hand verdict can be
     * written down without having to remember which one was on screen, and the
     * framing is logged for the same reason.
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
        "FILL_CENTER (screen filled; the photo holds more)"
    } else {
        "FIT_CENTER (whole photo visible)"
    }}. Line the text up on the crossing point; tap the preview to switch."

    /**
     * One press: write a JPEG, hand its URI to the EXISTING share entry.
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
        val dir = File(cacheDir, CAPTURE_DIR_NAME).apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
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
                    val uri = try {
                        FileProvider.getUriForFile(
                            this@ProtoCameraActivity,
                            "$packageName$FILE_PROVIDER_SUFFIX",
                            file
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "FileProvider failed", t)
                        statusView.text = "PROTOTYPE #78 — FileProvider failed: ${t.message}"
                        captureButton.isEnabled = true
                        return
                    }
                    Log.i(TAG, "handing $uri to ShareImageActivity")
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

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    statusView.text = "PROTOTYPE #78 — capture failed: ${exception.message}"
                    captureButton.isEnabled = true
                }
            }
        )
    }

    companion object {
        private const val TAG = "ProtoCamera"
        private const val CAPTURE_DIR_NAME = "proto-camera"

        /** The FileProvider authority suffix; the manifest declares `${applicationId}.protofileprovider`. */
        const val FILE_PROVIDER_SUFFIX = ".protofileprovider"
    }
}
