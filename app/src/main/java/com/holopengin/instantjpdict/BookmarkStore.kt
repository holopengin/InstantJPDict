package com.holopengin.instantjpdict

import android.content.Context
import com.holopengin.instantjpdict.data.AppDatabase
import com.holopengin.instantjpdict.data.Bookmark
import com.holopengin.instantjpdict.util.BookmarkCandidate
import com.holopengin.instantjpdict.util.BookmarkKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #67: the bookmark set the popup can read synchronously.
 *
 * The overlay builds its dictionary panel on the main thread and cannot wait on
 * Room, so the saved-headword keys are mirrored in memory: loaded once per host
 * ([com.holopengin.instantjpdict.OverlayEnvironment.prepare]) and kept in step by
 * [toggle]. The database remains the source of truth — the viewer reads it
 * directly — and [refresh] rebuilds the mirror.
 */
object BookmarkStore {
    private val keys = mutableSetOf<BookmarkKey>()

    fun isBookmarked(key: BookmarkKey): Boolean = synchronized(keys) { key in keys }

    suspend fun refresh(context: Context) {
        val rows = withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(context).bookmarkDao().getAll()
        }
        synchronized(keys) {
            keys.clear()
            keys.addAll(rows.map { it.key })
        }
    }

    /**
     * Toggle [candidate] and update the mirror.
     *
     * Uniqueness comes from the table's unique index plus the DAO's IGNORE
     * insert, so a double tap cannot create two rows; this method only decides
     * insert-vs-delete and reports the resulting state.
     *
     * @return true when the headword is now bookmarked.
     */
    suspend fun toggle(context: Context, candidate: BookmarkCandidate): Boolean {
        val key = candidate.key
        val dao = AppDatabase.getDatabase(context).bookmarkDao()
        return withContext(Dispatchers.IO) {
            if (isBookmarked(key)) {
                dao.deleteByKey(key.kanji, key.reading, key.dictionaryName)
                synchronized(keys) { keys.remove(key) }
                false
            } else {
                val inserted = dao.insert(
                    Bookmark(
                        kanji = candidate.kanji,
                        reading = candidate.reading,
                        dictionaryName = candidate.dictionaryName,
                        definitionsText = candidate.definitionsText,
                        createdAt = System.currentTimeMillis()
                    )
                )
                // IGNORE yields -1 for a row that already exists (the mirror was
                // stale); either way the headword IS bookmarked, so report it.
                val stored = inserted != -1L || dao.getAll().any { it.key == key }
                if (stored) synchronized(keys) { keys.add(key) }
                stored
            }
        }
    }
}
