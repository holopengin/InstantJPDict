package com.holopengin.instantjpdict

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Crash capture: one uncaught-exception handler that writes a report file and
 * hands the user the crash screen, where the report can be emailed (with the
 * log attached, optionally) to [Feedback.ADDRESS].
 *
 * The order matters. The file is written first — it is the only reliable part —
 * then the screen is started with `NEW_TASK` because the crashing process has no
 * task to put it in; the process then kills itself so the system's own "app
 * stopped" dialog does not land on top of the report screen. The screen itself
 * is served by whichever fresh process the system starts for the pending
 * activity, so it survives the death of the one that crashed.
 *
 * Files live under `cache/crashes/`, capped to [MAX_FILES] newest so a crash
 * loop cannot fill the disk. `cache/` is also what a user can clear from system
 * settings without touching any of the app's real state.
 */
object CrashReporter {

    private const val TAG = "InstantJPDictCrash"
    private const val DIR = "crashes"
    private const val MAX_FILES = 5

    /**
     * A report younger than this means the crash screen's own process crashed
     * on the way up (the classic case: a real crash in `Application.onCreate`,
     * which runs in every process). Starting another screen would loop; the
     * report is still written and the platform handles this crash instead.
     */
    private const val LOOP_WINDOW_MS = 20_000L

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val captured = try {
                val looping = recentReport(crashDir(app))
                val file = writeCrashFile(app, thread, throwable)
                prune(app, keep = file)
                if (looping) {
                    Log.w(TAG, "crash loop: report written, not starting another screen")
                } else {
                    app.startActivity(
                        CrashReportActivity.intent(app, file)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                !looping
            } catch (t: Throwable) {
                Log.e(TAG, "could not capture the crash", t)
                false
            }
            if (captured) {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } else {
                // Capture failed (or looping): leave the crash to the platform
                // rather than dying silently.
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun recentReport(dir: File): Boolean {
        val now = System.currentTimeMillis()
        return dir.listFiles()?.any { now - it.lastModified() < LOOP_WINDOW_MS } == true
    }

    fun crashDir(context: Context): File = File(context.cacheDir, DIR)

    fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable): File {
        val dir = crashDir(context).apply { mkdirs() }
        val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
        file.writeText(
            crashText(
                time = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                app = Feedback.appVersion(context),
                device = Feedback.deviceInfo(),
                threadName = thread.name,
                process = "${context.packageName} (pid ${Process.myPid()})",
                stackTrace = Log.getStackTraceString(throwable),
            )
        )
        return file
    }

    /** Pure, so the report's shape is unit-tested on the host. */
    internal fun crashText(
        time: String,
        app: String,
        device: String,
        threadName: String,
        process: String,
        stackTrace: String,
    ): String = buildString {
        appendLine("InstantJPDict crash report")
        appendLine("Time: $time")
        appendLine("App: $app")
        appendLine("Device: $device")
        appendLine("Thread: $threadName")
        appendLine("Process: $process")
        appendLine()
        appendLine(stackTrace.trimEnd())
    }

    private fun prune(context: Context, keep: File) {
        val files = crashDir(context).listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_FILES).forEach { if (it != keep) it.delete() }
    }
}
