package com.holopengin.instantjpdict

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.holopengin.instantjpdict.util.CharLm
import com.holopengin.instantjpdict.util.ComponentTable
import com.holopengin.instantjpdict.util.Deinflector
import com.holopengin.instantjpdict.util.InferLog
import com.holopengin.instantjpdict.util.KanaOrthography
import com.holopengin.instantjpdict.util.KanaSoundChanges
import com.holopengin.instantjpdict.util.KanjiVariants
import com.holopengin.instantjpdict.util.OovCandidates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #57: the host-side wiring for [OcrOverlayStateController] in one place, so
 * the accessibility service and the image-share activity load exactly the same
 * stack. Duplicating it would let the two hosts drift on what the dictionary
 * popup can show — the same class of bug the "one assembly path" rule targets.
 *
 * Only the tunable-reading calls take a [Context]; the decision logic itself
 * lives in the controller, which is JVM-unit-testable.
 */
object OverlayEnvironment {
    private val gson = Gson()

    /**
     * Configure [controller] and start the optional #44 table loads on
     * [scope]. Called once per host, exactly like the service's `onCreate`.
     * The popup degrades to the head's own list until the tables land.
     */
    fun prepare(context: Context, controller: OcrOverlayStateController, scope: CoroutineScope) {
        controller.deinflector = Deinflector(java.io.InputStreamReader(context.assets.open("deinflect.json")))
        controller.dictionaryProvider = AndroidDictionaryProvider(context)
        controller.gson = gson

        // #44: component-derived popup candidates. Parsing the 266 KB component table is
        // cheap but not free, so it happens once off the main thread; until it lands (and
        // if it fails) the popup shows the head's own list, exactly as before.
        scope.launch {
            // A4/#86: the three normaliser tables used to sit behind this load, so a
            // ComponentTable failure silently disabled the #75 pre-reform kana fold, the
            // #81 historical-kana normaliser and the #44 kanji-variant fold for BOTH
            // hosts. They do not depend on the component table, so they install first
            // and independently; only the OOV wiring below needs the table.
            withContext(Dispatchers.IO) {
                runCatching { KanjiVariants.install(context) }
                    .onFailure { warn("KanjiVariants.install", it) }
                // #75: pre-reform orthography for the lookup query. Loaded here rather than
                // in either host so the share activity normalises exactly like the overlay.
                runCatching { KanaOrthography.install(context) }
                    .onFailure { warn("KanaOrthography.install", it) }
                // #81: historical sound changes, composed after the #75 table.
                runCatching { KanaSoundChanges.install(context) }
                    .onFailure { warn("KanaSoundChanges.install", it) }
            }
            val table = withContext(Dispatchers.IO) {
                runCatching { ComponentTable.load(context) }
                    .onFailure { warn("ComponentTable.load", it) }
                    .getOrNull()
            }
            if (table != null) {
                controller.installOovSuggestions(OovCandidates(table))
            }
            // The 14 MB packed model behind the blank's candidate ranking. Mapped the same
            // way and off the main thread; a failure here only narrows the blank's list.
            // It ranks candidates but shares no input with the component table, so it
            // loads even when that table failed.
            controller.installCharLm(
                withContext(Dispatchers.IO) {
                    runCatching { CharLm.load(context) }
                        .onFailure { warn("CharLm.load", it) }
                        .getOrNull()
                })
        }
    }

    /** A4/#86: an install/load failure used to be swallowed by `runCatching`. Surface
     *  it in logcat and in the in-app InferLog, which is what a device report can be
     *  read back through. */
    private fun warn(what: String, e: Throwable) {
        Log.w(TAG, "$what failed", e)
        InferLog.add("$what failed: ${e.message}")
    }

    private const val TAG = "OverlayEnvironment"
}
