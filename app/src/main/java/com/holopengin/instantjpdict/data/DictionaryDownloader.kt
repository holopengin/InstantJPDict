package com.holopengin.instantjpdict.data

import android.content.Context
import com.holopengin.instantjpdict.util.CatalogEntry
import com.holopengin.instantjpdict.util.DictionaryDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * #71: fetches a catalog entry to the app cache and verifies it.
 *
 * The HTTP half is deliberately `HttpURLConnection` — the one client the
 * platform already ships. Adding OkHttp for a single GET would be a dependency
 * and its licence (#70) for no gain, and a plain GET is all GitHub's release
 * assets need.
 *
 * What this does NOT do: touch the database. Nothing is inserted until
 * [DictionaryDownload.writeVerified] has confirmed the pinned size and SHA-256,
 * so an intercepted, truncated or corrupted download cannot become a
 * dictionary. On any failure or cancellation the cache file is deleted.
 */
class DictionaryDownloader(private val context: Context) {

    /**
     * Downloads [entry] to a fresh file in the app cache and returns it, already
     * verified. [onProgress] receives bytes written so far.
     *
     * @throws IOException on a non-2xx response, a network failure, or a
     *   size/hash mismatch (the last two leave no file behind).
     */
    suspend fun download(entry: CatalogEntry, onProgress: (Long) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val url = entry.url
                ?: throw IOException("${entry.name} has no download URL")
            val dest = File.createTempFile("dictionary-${entry.id}-", ".zip", context.cacheDir)
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    // GitHub redirects the release URL to a CDN and is happy
                    // without a UA; naming the app is still the courteous default.
                    setRequestProperty("User-Agent", "InstantJPDict/1.0 (dictionary catalog)")
                }
                try {
                    connection.connect()
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw IOException("Server returned HTTP $code for ${entry.name}")
                    }
                    connection.inputStream.use { input ->
                        DictionaryDownload.writeVerified(
                            input, dest, entry.bytes, entry.sha256, onProgress,
                        )
                    }
                } finally {
                    connection.disconnect()
                }
                dest
            } catch (t: Throwable) {
                // Verified files are the caller's to delete after import; a
                // failure before that point must not leave a partial file.
                dest.delete()
                throw t
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
