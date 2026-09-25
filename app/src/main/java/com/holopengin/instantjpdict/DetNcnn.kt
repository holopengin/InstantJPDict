package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer

/**
 * ncnn Det — loads det.param/bin (19KB/4.8MB, 217 layers) via JNI,
 * runs DB segmentation 960×960, returns prob map [960*960] float.
 */
class DetNcnn private constructor(private val handle: Long) {

    fun infer(floats: FloatArray, w: Int, h: Int): FloatArray? {
        if (floats.size != 3 * w * h) {
            Log.e(TAG, "infer: bad floats ${floats.size} vs ${3*w*h}")
            return null
        }
        var bb = tlBuffer.get()
        val needed = floats.size * 4
        if (bb == null || bb.capacity() < needed) {
            bb = ByteBuffer.allocateDirect(needed.coerceAtLeast(3 * 960 * 960 * 4)).order(java.nio.ByteOrder.nativeOrder())
            tlBuffer.set(bb)
        } else {
            bb.clear()
            bb.order(java.nio.ByteOrder.nativeOrder())
        }
        bb.asFloatBuffer().put(floats)
        bb.position(0)
        return inferNative(handle, bb, w, h)
    }

    /** Legacy alias for older call sites. */
    fun detect(floats: FloatArray, w: Int, h: Int): FloatArray? = infer(floats, w, h)

    /**
     * Native letterbox: hand the ARGB8888 pixels of the already-resized crop
     * straight to C++, which pads them into a `modelSize`² canvas with pixel-128
     * gray and writes the ImageNet-normalised NCHW tensor itself, then runs the
     * net. Same input as [infer] — bit-identical tensor, verified 0/2,408,448
     * floats differing on both fixtures — without the Kotlin `getPixels` of the
     * square letterbox, the 3·modelSize² float loop, the 9.6 MB direct-ByteBuffer
     * copy, and ncnn's own `fill_input` copy.
     *
     * The caller keeps the resize (Skia is the reference filter) and supplies
     * the content's top-left corner. **The pad is `(modelSize - content + 1) / 2`,
     * not `(modelSize - content) / 2`**: the Canvas is asked for a
     * `(modelSize - content) / 2f` translate and Skia rounds that half pixel
     * *away from zero*, so the content really lands one pixel right of integer
     * division. Get it wrong and the whole letterbox shifts a pixel inside the
     * canvas — a silent, box-visible change.
     */
    fun inferLetterboxed(
        pixels: IntArray,
        resizeW: Int,
        resizeH: Int,
        modelSize: Int,
        padX: Int,
        padY: Int,
    ): FloatArray? {
        val need = resizeW.toLong() * resizeH.toLong()
        if (resizeW <= 0 || resizeH <= 0 || need > pixels.size) {
            Log.e(TAG, "inferLetterboxed: bad pixels ${pixels.size} for ${resizeW}x$resizeH")
            return null
        }
        if (modelSize <= 0 || padX < 0 || padY < 0 || padX + resizeW > modelSize || padY + resizeH > modelSize) {
            Log.e(TAG, "inferLetterboxed: bad geometry content=${resizeW}x$resizeH pad=($padX,$padY) model=$modelSize")
            return null
        }
        return inferLetterboxedNative(handle, pixels, resizeW, resizeH, modelSize, padX, padY)
    }

    /**
     * The tensor [inferLetterboxed] would feed the net, without running it.
     * Parity seam: a test diffs this against the Kotlin-built array bit for bit,
     * so a preprocessing regression is reported as a float diff instead of as
     * quietly different boxes.
     */
    fun buildLetterboxedInput(
        pixels: IntArray,
        resizeW: Int,
        resizeH: Int,
        modelSize: Int,
        padX: Int,
        padY: Int,
    ): FloatArray? = buildLetterboxedInputNative(pixels, resizeW, resizeH, modelSize, padX, padY)

    /** `[preprocess_ms, net_ms]` of the most recent det call, native side only. */
    fun lastTimings(): FloatArray? = lastTimingsNative(handle)

    /**
     * The float path's input half with no net: copies [floats] into an ncnn Mat
     * the way `infer` does, and returns the milliseconds that took (-1 on
     * failure). Measurement seam — the letterbox path has no such copy, so this
     * is how the old path's third pass over 2.4 M floats gets a number without
     * differencing two net calls.
     */
    fun fillInputOnlyMs(floats: FloatArray, w: Int, h: Int): Float {
        if (floats.size != 3 * w * h) return -1f
        var bb = tlBuffer.get()
        val needed = floats.size * 4
        if (bb == null || bb.capacity() < needed) {
            bb = ByteBuffer.allocateDirect(needed.coerceAtLeast(3 * 960 * 960 * 4)).order(java.nio.ByteOrder.nativeOrder())
            tlBuffer.set(bb)
        } else {
            bb.clear()
            bb.order(java.nio.ByteOrder.nativeOrder())
        }
        bb.asFloatBuffer().put(floats)
        bb.position(0)
        return fillInputOnlyNative(bb, w, h)
    }

    fun close() {
        destroy(handle)
    }

    companion object {
        private const val TAG = "DetNcnn"
        private val tlBuffer = ThreadLocal<ByteBuffer>()
        private var loaded = false
        fun ensureLoaded() {
            if (!loaded) {
                try {
                    System.loadLibrary("ncnn_jni")
                    loaded = true
                    Log.d(TAG, "libncnn_jni loaded for Det")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "loadLibrary ncnn_jni failed for Det", e)
                }
            }
        }

        fun create(context: Context): DetNcnn? {
            ensureLoaded()
            val paramFile = materialiseModelAsset(context, "PP-OCRv6_small_ncnn/det.param", "det.param")
                ?: return null
            val binFile = materialiseModelAsset(context, "PP-OCRv6_small_ncnn/det.bin", "det.bin")
                ?: return null
            val h = create(paramFile.absolutePath, binFile.absolutePath)
            if (h == 0L) {
                Log.e(TAG, "DetNcnn.create failed")
                return null
            }
            Log.d(TAG, "DetNcnn handle=$h")
            return DetNcnn(h)
        }

        @JvmStatic private external fun create(paramPath: String, binPath: String): Long
        @JvmStatic private external fun destroy(handle: Long)
        @JvmStatic private external fun inferNative(handle: Long, buffer: ByteBuffer, w: Int, h: Int): FloatArray?
        @JvmStatic private external fun inferLetterboxedNative(
            handle: Long,
            pixels: IntArray,
            resizeW: Int,
            resizeH: Int,
            modelSize: Int,
            padX: Int,
            padY: Int,
        ): FloatArray?

        @JvmStatic private external fun buildLetterboxedInputNative(
            pixels: IntArray,
            resizeW: Int,
            resizeH: Int,
            modelSize: Int,
            padX: Int,
            padY: Int,
        ): FloatArray?

        @JvmStatic private external fun lastTimingsNative(handle: Long): FloatArray?
        @JvmStatic private external fun fillInputOnlyNative(buffer: ByteBuffer, w: Int, h: Int): Float
    }
}
