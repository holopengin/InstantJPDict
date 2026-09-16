package com.holopengin.instantjpdict.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * #71: the integrity-checked write at the centre of a catalog download.
 *
 * Kept free of Android and HTTP types so the part that must not go wrong — the
 * file that becomes a dictionary is exactly the file whose hash the catalog
 * pinned — is a plain, JVM-tested function. The Android side
 * (`data/DictionaryDownloader`) opens the URL and hands the stream here.
 *
 * Cleanup is part of the contract, not the caller's job: on a size/hash
 * mismatch, an I/O error or coroutine cancellation the partial file is deleted
 * before the failure propagates, so a failed or cancelled download leaves
 * nothing behind that a later step could mistake for a dictionary.
 */
object DictionaryDownload {

    private const val BUFFER = 64 * 1024

    /**
     * Streams [source] into [dest], hashing as it goes, and verifies the result
     * against [expectedBytes] and [expectedSha256] (lowercase hex).
     *
     * Calls [onProgress] with the number of bytes written so far. Cancellation
     * is cooperative: the caller must be inside a live coroutine, and a
     * cancelled one aborts the copy (and deletes [dest]) at the next chunk.
     *
     * @throws IOException when the size or hash does not match, or the write
     *   fails. The exception message names which check failed and is shown to
     *   the user.
     */
    suspend fun writeVerified(
        source: InputStream,
        dest: File,
        expectedBytes: Long,
        expectedSha256: String,
        onProgress: (Long) -> Unit = {},
    ) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            dest.outputStream().buffered().use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    written += read
                    onProgress(written)
                }
            }
            if (written != expectedBytes) {
                throw IOException(
                    "Download is ${written} bytes, expected ${expectedBytes} — discarded"
                )
            }
            val actual = digest.digest().toHex()
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IOException(
                    "Download hash $actual does not match the pinned $expectedSha256 — discarded"
                )
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
