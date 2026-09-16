package com.holopengin.instantjpdict.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.holopengin.instantjpdict.util.BookmarkKey

/**
 * #67: one saved headword.
 *
 * Identity is the natural key `(kanji, reading, dictionaryName)`, not a Room
 * row id. [DictionaryEntry.id] and [DictionaryMeta.id] are `autoGenerate`
 * keys: the importer deletes and re-inserts a dictionary's rows on a bundled
 * re-import and assigns fresh ids, so a bookmark that pointed at them would
 * orphan. The dictionary *name* is what the importer replaces by
 * (`findDictionaryByName`) and what the popup labels the block with, so it is
 * the durable handle.
 *
 * The definition text is snapshotted at save time rather than joined from the
 * `dictionary` table, so the viewer and the CSV stay correct even after the
 * source dictionary is deleted or re-imported.
 */
@Entity(
    tableName = "bookmark",
    indices = [
        Index(value = ["kanji", "reading", "dictionaryName"], unique = true)
    ]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kanji: String,
    val reading: String,
    val dictionaryName: String,
    /** Plain-text senses, snapshotted. See [com.holopengin.instantjpdict.util.Definitions]. */
    val definitionsText: String,
    val createdAt: Long
) {
    val key: BookmarkKey get() = BookmarkKey(kanji, reading, dictionaryName)
}

/**
 * #67: the hand-written DDL for the `bookmark` table, in one place.
 *
 * The migration uses these strings and a unit test pins their shape. Room
 * verifies the schema at open: if either statement drifts from what KSP
 * generates for [Bookmark] and [Bookmark.key], the migration is considered
 * missing and the destructive fallback fires. The strings are byte-for-byte
 * what Room generates (verified against `AppDatabase_Impl`): Booleans/Integers
 * are `INTEGER NOT NULL`, an `autoGenerate` primary key is
 * `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`, and index names are
 * `index_<table>_<col>_<col>`.
 */
object BookmarkSchema {
    const val CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS `bookmark` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`kanji` TEXT NOT NULL, " +
            "`reading` TEXT NOT NULL, " +
            "`dictionaryName` TEXT NOT NULL, " +
            "`definitionsText` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL)"

    const val CREATE_UNIQUE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmark_kanji_reading_dictionaryName` " +
            "ON `bookmark` (`kanji`, `reading`, `dictionaryName`)"
}
