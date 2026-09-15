package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import com.holopengin.instantjpdict.util.InferLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ncnn Rec wrapper — single dynamic-width model rec_dyn (#23).
 * Loads the 166-layer pnnx→ncnn INT8 bin via JNI; exact-width inference (mult of 8).
 */
class RecNcnn private constructor(private val handle: Long, val targetW: Int) {

    fun infer(floats: FloatArray, w: Int, h: Int = 48): FloatArray? {
        if (floats.size != 3 * 48 * w) {
            Log.e(TAG, "infer: bad floats ${floats.size} vs ${3*48*w}")
            return null
        }
        var bb = tlBuffer.get()
        val needed = floats.size * 4
        if (bb == null || bb.capacity() < needed) {
            bb = ByteBuffer.allocateDirect(needed.coerceAtLeast(3 * 48 * 480 * 4)).order(ByteOrder.nativeOrder())
            tlBuffer.set(bb)
        } else {
            bb.clear()
            bb.order(ByteOrder.nativeOrder())
        }
        bb.asFloatBuffer().put(floats)
        bb.position(0)
        val t0 = System.nanoTime()
        val out = inferNative(handle, bb, w, h)
        InferLog.add("rec infer w=$w seq=${w / 8} out=${out?.size ?: "null"} ${(System.nanoTime() - t0) / 1_000_000}ms")
        return out
    }

    /** Top-15 per timestep, packed [idx,val] pairs (see inferTopKNative in
     * ncnn_jni.cpp). Returns seqLen*15*2 floats, or null on failure. */
    fun inferTopK(floats: FloatArray, w: Int, h: Int = 48): FloatArray? {
        if (floats.size != 3 * 48 * w) {
            Log.e(TAG, "inferTopK: bad floats ${floats.size} vs ${3*48*w}")
            return null
        }
        var bb = tlBuffer.get()
        val needed = floats.size * 4
        if (bb == null || bb.capacity() < needed) {
            bb = ByteBuffer.allocateDirect(needed.coerceAtLeast(3 * 48 * 480 * 4)).order(ByteOrder.nativeOrder())
            tlBuffer.set(bb)
        } else {
            bb.clear()
            bb.order(ByteOrder.nativeOrder())
        }
        bb.asFloatBuffer().put(floats)
        bb.position(0)
        val t0 = System.nanoTime()
        val out = inferTopKNative(handle, bb, w, h)
        InferLog.add("rec topk w=$w seq=${w / 8} out=${out?.size ?: "null"} expect=${w / 8 * 15 * 2} ${(System.nanoTime() - t0) / 1_000_000}ms")
        return out
    }

    fun close() {
        destroy(handle)
    }

    companion object {
        private const val TAG = "RecNcnn"
        private val tlBuffer = ThreadLocal<ByteBuffer>()
        private var loaded = false
        fun ensureLoaded() {
            if (!loaded) {
                try {
                    System.loadLibrary("ncnn_jni")
                    loaded = true
                    Log.d(TAG, "libncnn_jni loaded")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "loadLibrary ncnn_jni failed", e)
                }
            }
        }

        fun create(context: Context, targetW: Int = 64, numThreads: Int = 1): RecNcnn? {
            ensureLoaded()
            // Single dynamic-width model (#23) — targetW only sizes seqLen/buffers now.
            // A8/#86: copied once per installed APK, not once per engine.
            val paramFile = materialiseModelAsset(context, "PP-OCRv6_small_ncnn/rec_dyn.param", "rec_dyn.param")
                ?: return null
            val binFile = materialiseModelAsset(context, "PP-OCRv6_small_ncnn/rec_dyn.bin", "rec_dyn.bin")
                ?: return null
            val h = create(paramFile.absolutePath, binFile.absolutePath, targetW, numThreads)
            if (h == 0L) {
                Log.e(TAG, "RecNcnn.create failed for W=$targetW")
                return null
            }
            Log.d(TAG, "RecNcnn W=$targetW handle=$h")
            return RecNcnn(h, targetW)
        }

        @JvmStatic private external fun create(paramPath: String, binPath: String, targetW: Int, numThreads: Int): Long
        @JvmStatic private external fun destroy(handle: Long)
        @JvmStatic private external fun inferNative(handle: Long, buffer: ByteBuffer, w: Int, h: Int): FloatArray?
        @JvmStatic private external fun inferTopKNative(handle: Long, buffer: ByteBuffer, w: Int, h: Int): FloatArray?
    }
}
