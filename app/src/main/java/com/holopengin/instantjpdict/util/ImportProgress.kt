package com.holopengin.instantjpdict.util

import java.util.Locale

/**
 * #71: the row text above the catalog's progress bar.
 *
 * The bar answers "is it moving?"; only this line answers "through what, how
 * far, and of how much" — which is the difference between a 15 MB download that
 * reads as progress and one that reads as a hang. Keeping it a pure function
 * (no Android types, no view state) is what lets the wording, the rounding and
 * the unknown-total shape be pinned in unit tests rather than eyeballed.
 *
 * Two shapes recur and both are deliberate:
 *  - a phase with a KNOWN total gets `n / total` and a percentage, so the text
 *    and the determinate bar agree;
 *  - a phase with an UNKNOWN total gets the count so far and no percentage —
 *    the pitch install reports entries as it goes but has no total to divide
 *    by, and inventing one would make the number meaningless. The bar is
 *    indeterminate in exactly those cases.
 *
 * The download path is the one case that carries a file name: the row's title
 * is the dictionary, but the artifact has its own name upstream, and seeing
 * `JMdict_english.zip` is what makes a stalled 15 MB fetch legible.
 *
 * Wording follows the copy-feedback precedent elsewhere in the app (#66: a
 * plain `Copied: …` toast, no engineer voice): the user is told what is
 * happening in words they would use, not `DONE_SHA256_MISMATCH`.
 */
object ImportProgress {

    /** What the row says while bytes are arriving from the network. */
    fun download(written: Long, total: Long, fileName: String): String =
        "Downloading $fileName — ${formatBytes(written)} / ${formatBytes(total)} (${percent(written, total)}%)"

    /** What the row says between "all bytes are here" and "the import ran". */
    fun verify(total: Long, fileName: String): String =
        "Verifying $fileName — checking SHA-256 against the pinned ${formatBytes(total)}…"

    /**
     * What the row says while rows are being written.
     *
     * [indeterminate] is the caller's knowledge, not a guess: when it is true
     * the total is unknown (the bundled asset path reports counts but no
     * target), so the text states the count and drops the percentage rather
     * than pretending to a denominator.
     */
    fun importing(processed: Int, total: Int?, name: String, indeterminate: Boolean): String =
        if (indeterminate || total == null || total <= 0) {
            "Importing $name — ${formatCount(processed)} entries…"
        } else {
            "Importing $name — ${formatCount(processed)} / ${formatCount(total)} entries (${percent(processed.toLong(), total.toLong())}%)"
        }

    /** The resting line once the import has landed. */
    fun done(entries: Int, name: String, builtIn: Boolean): String =
        if (builtIn) {
            "Imported $name — ${formatCount(entries)} entries (bundled)"
        } else {
            "Imported $name — ${formatCount(entries)} entries"
        }

    /**
     * A failure in words the user can act on.
     *
     * [reason] is the exception message from the download/import path, which
     * names the check that failed and is not written for a user's eyes. The
     * known shapes are recognised and restated; anything unrecognised keeps
     * none of the raw exception spelling but does not lose the fact that
     * nothing was written, because that is the part that determines what the
     * user's next action should be.
     */
    fun failure(reason: String?): String {
        val detail = reason ?: ""
        val explanation = when {
            detail.contains("hash") && detail.contains("does not match") ->
                "Download failed — the file does not match its pinned SHA-256, so it was discarded."
            detail.contains("bytes, expected") || detail.contains("unexpected end") ->
                "Download failed — the download was interrupted, so the partial file was discarded."
            detail.contains("Failed to open input stream") ->
                "Download failed — the downloaded file could not be opened."
            detail.contains("No such file") || detail.contains("FileNotFound") ->
                "Download failed — the downloaded file went missing before it could be imported."
            else ->
                "Download failed — the file could not be downloaded."
        }
        return "$explanation Nothing was imported; tap Retry to try again."
    }

    /**
     * Bytes in the units a person reads, matching the catalog row's own size
     * label ([CatalogEntry.formatBytes]) so a download's total agrees with the
     * size printed on the row above it.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "${String.format(Locale.ROOT, "%.1f", value)} ${units[unit]}"
    }

    /** Entry counts grouped so a six-digit number stays readable. */
    fun formatCount(entries: Int): String = String.format(Locale.ROOT, "%,d", entries)

    /**
     * Floored, clamped percentage. Floored because showing 100% while the last
     * bytes are still arriving is a lie, and clamped because a total the server
     * over-delivers must not print 120%.
     */
    private fun percent(done: Long, total: Long): Int {
        if (total <= 0) return 0
        val pct = (done * 100) / total
        return pct.coerceIn(0, 100).toInt()
    }

    /**
     * The same percentage the progress text prints, for a bar or a label that
     * has to agree with it. Public so a second percentage is never computed
     * somewhere else and allowed to disagree.
     */
    fun percentOf(done: Long, total: Long): Int = percent(done, total)
}
