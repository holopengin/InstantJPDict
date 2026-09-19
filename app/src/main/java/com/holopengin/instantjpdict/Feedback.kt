package com.holopengin.instantjpdict

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * The one place the maintainer's address, the diagnostics block and the
 * email-intent shape live. Two surfaces reach the maintainer — the "Have
 * Feedback?" button and the crash report screen — and they must not drift.
 */
object Feedback {

    /**
     * Plus-addressed so crash reports and ordinary feedback can be filtered
     * apart in the mailbox; the address itself is the deliverable.
     */
    const val ADDRESS = "holographicpengin+InstantJPDict@gmail.com"

    /** Matches the manifest's `${applicationId}.protofileprovider`. */
    private const val FILE_PROVIDER_SUFFIX = ".protofileprovider"

    /** "InstantJPDict 1.0.0-rc1 (1)". */
    fun appVersion(context: Context): String {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
        return "InstantJPDict ${info.versionName} (${info.longVersionCode})"
    }

    /** "Google Pixel 7a, Android 16 (API 36)". */
    fun deviceInfo(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT})"

    /**
     * Always `ACTION_SEND` as `message/rfc822`: the one shape that both
     * prefills subject/body (Gmail ignores the extras on `ACTION_SENDTO`) and
     * can carry the optional crash-log stream, with a read grant for the
     * FileProvider URI. `message/rfc822` keeps the chooser to mail apps, where
     * `text/plain` would offer chat apps too.
     */
    fun emailIntent(
        context: Context,
        subject: String,
        body: String,
        attachment: File? = null,
    ): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        attachment?.takeIf { it.isFile }?.let { file ->
            putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(
                    context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file
                ),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
