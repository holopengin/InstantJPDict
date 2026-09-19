package com.holopengin.instantjpdict

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
     * Start an email intent directly. A crash report must not land in a chat
     * app: the share sheet for an attachment (`ACTION_SEND`) also offers
     * Signal, LINE, Quick Share and friends, and `EXTRA_EXCLUDE_COMPONENTS` is
     * not honoured for the Direct Share row on current Android. So the target
     * set is computed here — packages that also answer `ACTION_SENDTO
     * mailto:` — and one mail app is launched directly, or a short in-app
     * picker when there are several. With no email app at all the system
     * chooser is the fallback, so the report can still go somewhere.
     *
     * The caller's activity stays alive (the picker is a dialog), so it must
     * not finish inside this call.
     */
    fun launch(context: Context, intent: Intent, chooserTitle: String) {
        val mailPackages = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0
        ).map { it.activityInfo.packageName }.toSet()
        val emails = context.packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName in mailPackages }
            .distinctBy { it.activityInfo.packageName }
        when (emails.size) {
            0 -> context.startActivity(intent)
            1 -> context.startActivity(intent.forTarget(emails[0]))
            else -> MaterialAlertDialogBuilder(context)
                .setTitle(chooserTitle)
                .setItems(
                    emails.map { it.loadLabel(context.packageManager).toString() }.toTypedArray()
                ) { _, index -> context.startActivity(intent.forTarget(emails[index])) }
                .show()
        }
    }

    private fun Intent.forTarget(info: ResolveInfo): Intent = apply {
        component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    }

    /**
     * Without an attachment: `ACTION_SENDTO` to a `mailto:` URI whose query
     * carries the subject and body. That keeps the chooser email-only, and the
     * prefill survives — Gmail ignores `EXTRA_SUBJECT`/`EXTRA_TEXT` on
     * `ACTION_SENDTO` (verified on device) but honours the URI parameters.
     *
     * With one: `ACTION_SEND` as `message/rfc822`, the only shape that can
     * carry the crash-log stream, with a read grant for the FileProvider URI.
     */
    fun emailIntent(
        context: Context,
        subject: String,
        body: String,
        attachment: File? = null,
    ): Intent {
        val file = attachment?.takeIf { it.isFile }
        if (file == null) {
            val uri = Uri.parse(
                "mailto:$ADDRESS?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
            )
            return Intent(Intent.ACTION_SENDTO, uri)
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
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
