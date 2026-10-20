package com.holopengin.instantjpdict

import android.content.Context
import android.content.SharedPreferences

/**
 * #108: what the manual lookup box searches against.
 *
 * The mode is explicit, never inferred. Romaji and English can collide on the
 * same input ("tori" is both 鳥 and an English-looking word), so the user picks
 * the direction once and it is persisted with the other input preferences.
 *
 * [JP_TO_EN] is the default and the behaviour #89 shipped: the box is a window
 * onto the ordinary term lookup. [ROMAJI_TO_JP] converts the typed romaji to
 * kana and then runs that same lookup. [EN_TO_JP] searches the English glosses
 * stored in `dictionary.definitions`.
 */
enum class ManualLookupMode(
    val storedValue: String,
    val labelRes: Int,
    val hintRes: Int,
) {
    JP_TO_EN(
        storedValue = "jp_to_en",
        labelRes = R.string.manual_lookup_mode_jp_to_en,
        hintRes = R.string.manual_lookup_hint,
    ),
    EN_TO_JP(
        storedValue = "en_to_jp",
        labelRes = R.string.manual_lookup_mode_en_to_jp,
        hintRes = R.string.manual_lookup_hint_en,
    ),
    ROMAJI_TO_JP(
        storedValue = "romaji_to_jp",
        labelRes = R.string.manual_lookup_mode_romaji_to_jp,
        hintRes = R.string.manual_lookup_hint_romaji,
    );

    companion object {
        /** Same preference file as the other input preferences (#84, #106). */
        const val PREFS_NAME = OcrEngine.PREFS_NAME
        const val PREF_KEY = "manual_lookup_mode"

        val DEFAULT = JP_TO_EN

        /** Unknown or missing values fall back to the default, never throw. */
        fun fromStored(value: String?): ManualLookupMode =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT

        fun read(prefs: SharedPreferences): ManualLookupMode =
            fromStored(prefs.getString(PREF_KEY, DEFAULT.storedValue))

        fun write(prefs: SharedPreferences, mode: ManualLookupMode) {
            prefs.edit().putString(PREF_KEY, mode.storedValue).apply()
        }

        fun read(context: Context): ManualLookupMode =
            read(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        fun write(context: Context, mode: ManualLookupMode) =
            write(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), mode)
    }
}
