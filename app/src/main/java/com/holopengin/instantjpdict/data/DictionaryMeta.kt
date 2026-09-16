package com.holopengin.instantjpdict.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictionary_meta")
data class DictionaryMeta(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val priority: Int,
    val enabled: Boolean = true,
    /**
     * True for dictionaries shipped inside the app (#43 — the vendored pitch
     * dictionary). Built-ins are not user-editable: the manager hides them and
     * there is no way to delete them, so their presence is app state rather
     * than user state.
     */
    val builtIn: Boolean = false,
    /**
     * #71 follow-up: the catalog entry this dictionary was installed from, when
     * it came through the catalog, else null (file picker, bundled install).
     *
     * The name alone cannot identify a variant: upstream gives
     * `JMdict_english.zip` and `JMdict_english_with_examples.zip` the *same*
     * title (`JMdict [2026-09-15]`), so `DictionaryMeta.name` is identical for
     * both and the catalog could not tell which one is installed. This records
     * the provenance so the two rows can be mutually exclusive.
     */
    val catalogId: String? = null
)

/**
 * #71: the hand-written DDL for the `catalogId` column, in one place.
 *
 * The same contract as [BookmarkSchema]: the migration uses this string and a
 * unit test pins its shape, so the hand-written migration cannot silently drift
 * from what KSP generates for [DictionaryMeta]. A `String?` column is plain
 * `TEXT` — no `NOT NULL` and no default — which is also what
 * `ALTER TABLE ... ADD COLUMN` leaves existing rows as (NULL).
 */
object DictionaryMetaSchema {
    const val ADD_CATALOG_ID =
        "ALTER TABLE `dictionary_meta` ADD COLUMN `catalogId` TEXT"
}
