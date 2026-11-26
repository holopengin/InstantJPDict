package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "PageOcrModels"

/**
 * Materialise the model files the Rust page engine loads from disk.
 *
 * The engine takes a directory (it opens `PP-OCRv6_small_ncnn/…` and
 * `kana_size/…` itself), so the asset tree stays intact. The copy is keyed to
 * the installed APK and reused by every later process; a partial copy is never
 * visible because each file is written to a temp and renamed.
 *
 * Returns null when an asset cannot be copied; the caller then keeps the old
 * path (and reports "not ready") rather than crashing.
 */
internal fun materialisePageOcrModelDir(context: Context): File? =
    synchronized(pageOcrModelLock) { materialisePageOcrModelDirLocked(context) }

/** One copy per process: the service and the share activity can construct an
 *  engine at the same time, and the parent-directory cleanup below must not
 *  run while the other host is still writing its first copy. */
private val pageOcrModelLock = Any()

private fun materialisePageOcrModelDirLocked(context: Context): File? {
    val stamp = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (e: Exception) {
        Log.e(TAG, "cannot read the package update time", e)
        return null
    }
    val root = File(File(context.cacheDir, "ocr_page"), stamp.toString())
    val required = listOf(
        "PP-OCRv6_small_ncnn/det.param",
        "PP-OCRv6_small_ncnn/det.bin",
        "PP-OCRv6_small_ncnn/rec_dyn.param",
        "PP-OCRv6_small_ncnn/rec_dyn.bin",
        "PP-OCRv6_small_ncnn/vocab.json",
        "PP-OCRv6_small_ncnn/rec_remap.txt",
        "kana_size/nb_all.param",
        "kana_size/nb_all.bin",
    )
    if (required.all { File(root, it).isFile && File(root, it).length() > 0L }) {
        return root
    }
    return try {
        root.mkdirs()
        // Drop the previous install's models, and the loose files an older
        // flat layout may have left in the parent.
        root.parentFile?.listFiles()?.forEach { if (it != root) it.deleteRecursively() }
        for (rel in required) {
            val target = File(root, rel)
            target.parentFile?.mkdirs()
            val tmp = File.createTempFile(target.name, ".part", target.parentFile)
            context.assets.open(rel).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        if (required.all { File(root, it).length() > 0L }) root else null
    } catch (e: Exception) {
        Log.e(TAG, "copy page OCR models failed", e)
        null
    }
}
