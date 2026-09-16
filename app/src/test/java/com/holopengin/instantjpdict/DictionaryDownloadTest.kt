package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.DictionaryDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * #71: the download's integrity check and cleanup contract — the half of the
 * feature that must not go wrong, kept free of Android and HTTP so it can be
 * tested directly.
 *
 * The expected hashes are known-good literals from the SHA-256 spec, not
 * recomputed the way the code computes them, so a broken verifier cannot pass
 * by agreeing with itself.
 */
class DictionaryDownloadTest {

    private val helloWorld = "hello world".toByteArray()
    // sha256("hello world")
    private val helloWorldSha = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

    private fun tempFile(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "ijpd-catalog-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs() || dir.isDirectory)
        return File(dir, "download.zip")
    }

    @Test
    fun a_verified_download_is_written_intact_and_kept() = runBlocking {
        val dest = tempFile()
        val seen = mutableListOf<Long>()
        DictionaryDownload.writeVerified(
            ByteArrayInputStream(helloWorld), dest, helloWorld.size.toLong(), helloWorldSha,
        ) { seen += it }
        assertTrue("the verified file must be kept", dest.isFile)
        assertEquals(helloWorld.toList(), dest.readBytes().toList())
        assertEquals(
            "progress must be reported as bytes written",
            helloWorld.size.toLong(),
            seen.last(),
        )
    }

    @Test
    fun a_hash_mismatch_is_rejected_and_the_partial_file_deleted() = runBlocking {
        val dest = tempFile()
        val wrong = "0000000000000000000000000000000000000000000000000000000000000000"
        try {
            DictionaryDownload.writeVerified(
                ByteArrayInputStream(helloWorld), dest, helloWorld.size.toLong(), wrong,
            )
            fail("a hash mismatch must throw")
        } catch (e: IOException) {
            assertTrue("the failure must name the hash: ${e.message}", e.message!!.contains("hash"))
        }
        assertFalse("a failed download must leave no file behind", dest.exists())
    }

    @Test
    fun a_size_mismatch_is_rejected_and_the_partial_file_deleted() = runBlocking {
        val dest = tempFile()
        try {
            DictionaryDownload.writeVerified(
                ByteArrayInputStream(helloWorld), dest, helloWorld.size.toLong() + 1, helloWorldSha,
            )
            fail("a size mismatch must throw")
        } catch (e: IOException) {
            assertTrue("the failure must name the size: ${e.message}", e.message!!.contains("bytes"))
        }
        assertFalse("a failed download must leave no file behind", dest.exists())
    }

    @Test
    fun a_cancelled_download_is_cleaned_up_and_the_cancellation_propagates() = runBlocking {
        val dest = tempFile()
        // A source that yields a little data then reports the download was
        // cancelled. This is the same throwable a cancelled coroutine surfaces,
        // so it exercises the cleanup path the Android caller depends on.
        val cancelled = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw CancellationException("cancelled")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served == 0) {
                    served = 1
                    b[off] = 'h'.code.toByte()
                    return 1
                }
                throw CancellationException("cancelled")
            }
        }
        try {
            DictionaryDownload.writeVerified(cancelled, dest, 100, helloWorldSha)
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // expected
        }
        assertFalse("a cancelled download must leave no file behind", dest.exists())
    }
}
