package com.holopengin.instantjpdict.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * #67: storage for saved headwords.
 *
 * Uniqueness is enforced by the table's unique index on
 * `(kanji, reading, dictionaryName)`; [insert] uses IGNORE so a double tap (or
 * two hosts racing) is a no-op rather than a crash or a duplicate row. That
 * IGNORE is the "no duplicate bookmark for the same headword" guarantee, not
 * just an optimisation.
 */
@Dao
interface BookmarkDao {
    /** @return the new row id, or -1 when the headword was already bookmarked. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: Bookmark): Long

    /** @return rows deleted (0 or 1) — the un-bookmark path. */
    @Query(
        "DELETE FROM bookmark " +
            "WHERE kanji = :kanji AND reading = :reading AND dictionaryName = :dictionaryName"
    )
    suspend fun deleteByKey(kanji: String, reading: String, dictionaryName: String): Int

    /** Insertion order. The viewer sorts in memory ([com.holopengin.instantjpdict.util.BookmarkSort]). */
    @Query("SELECT * FROM bookmark")
    suspend fun getAll(): List<Bookmark>
}
