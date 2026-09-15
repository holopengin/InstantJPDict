package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "ModelAssets"

/**
 * A8/#86: materialise one model asset into a cache copy that survives the process,
 * keyed to the installed APK.
 *
 * `RecNcnn.create` and `DetNcnn.create` used to rewrite `rec_dyn.bin` (4.7 MB) and
 * `det.bin` (4.9 MB) from the APK into `cacheDir/ncnn` on **every** engine
 * construction — and an engine is constructed per host (the accessibility service
 * and the share activity each hold one), so a shared image paid for ~10 MB of
 * copying each time. The assets only change when the APK does, so the copies live
 * under `cacheDir/ncnn/<lastUpdateTime>`: the first engine of an install copies,
 * every later one (and every later process) reuses.
 *
 * The copy is written to a temporary file and renamed into place, so a process
 * killed mid-copy cannot leave a short file that ncnn would happily read. A copy
 * of the previous install's directory is dropped when a new one is created, so the
 * cache does not accumulate one model set per app update.
 *
 * Returns null when [asset] cannot be copied; the callers turn that into a null
 * engine, the same as the inline copy did.
 */
internal fun materialiseModelAsset(context: Context, asset: String, name: String): File? {
    val stamp = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (e: Exception) {
        Log.e(TAG, "cannot read the package update time for $asset", e)
        return null
    }
    val dir = File(File(context.cacheDir, "ncnn"), stamp.toString())
    val target = File(dir, name)
    if (target.isFile && target.length() > 0L) return target

    return try {
        dir.mkdirs()
        // The previous install's models are dead weight (the assets they were copied
        // from no longer exist), and so are the loose files the old flat layout left.
        dir.parentFile?.listFiles()?.forEach { if (it != dir) it.deleteRecursively() }
        val tmp = File.createTempFile(name, ".part", dir)
        context.assets.open(asset).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target
    } catch (e: Exception) {
        Log.e(TAG, "copy asset $asset failed", e)
        null
    }
}
