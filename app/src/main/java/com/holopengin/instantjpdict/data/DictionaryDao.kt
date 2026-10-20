package com.holopengin.instantjpdict.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DictionaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntry>)

    @Query("""
        SELECT d.* FROM dictionary d 
        JOIN dictionary_meta m ON d.dictionaryId = m.id 
        WHERE d.kanji IN (:texts) OR d.reading IN (:texts) 
        ORDER BY m.priority ASC, d.popularity DESC
    """)
    suspend fun findByTexts(texts: List<String>): List<DictionaryEntry>

    /**
     * #108: fast bounded English-gloss scan. `definitions` is JSON text, so a
     * leading-wildcard search cannot use the kanji/reading indexes; the cap and
     * the priority/popularity order bound the work. [DefinitionSearch] filters
     * the remaining hits and ranks the real gloss matches.
     *
     * This scan does not strip JSON keys, so it can return markup-only rows.
     * The controller calls [findByDefinitionExcludingKeys] only when this pass
     * produced no real gloss match. Normal words pay for one cheap `instr`
     * scan; words that are also JSON keys pay for the slower fallback.
     *
     * JSON1 is deliberately not used: it is not enabled in Android's SQLite on
     * every supported release (minSdk 30), so a `json_tree` query would fail at
     * runtime on older devices.
     */
    @Query("""
        SELECT d.* FROM dictionary d
        JOIN dictionary_meta m ON d.dictionaryId = m.id
        WHERE instr(lower(d.definitions), lower(:query)) > 0
        ORDER BY m.priority ASC, d.popularity DESC
        LIMIT :limit
    """)
    suspend fun findByDefinition(query: String, limit: Int): List<DictionaryEntry>

    /**
     * The slower fallback: remove the exact `"query":` key spelling before the
     * substring test, so a word like `content` does not match every structured
     * row's JSON keys. Values such as `"span"` can still match a tag value;
     * [DefinitionSearch] drops those after the scan.
     */
    @Query("""
        SELECT d.* FROM dictionary d
        JOIN dictionary_meta m ON d.dictionaryId = m.id
        WHERE instr(
            lower(replace(d.definitions, '"' || lower(:query) || '":', '')),
            lower(:query)
        ) > 0
        ORDER BY m.priority ASC, d.popularity DESC
        LIMIT :limit
    """)
    suspend fun findByDefinitionExcludingKeys(query: String, limit: Int): List<DictionaryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDictionary(dictionary: DictionaryMeta): Long

    @Query("SELECT * FROM dictionary_meta ORDER BY priority ASC")
    suspend fun getAllDictionaries(): List<DictionaryMeta>

    /**
     * #43: built-in dictionaries are hidden from the user-editable list. Also
     * the completion check for the bundled install: the flag is set only after
     * every entry is written, so a half-finished import is not mistaken for a
     * finished one.
     */
    @Query("SELECT * FROM dictionary_meta WHERE builtIn = 1 LIMIT 1")
    suspend fun findBuiltInDictionary(): DictionaryMeta?

    @Query("UPDATE dictionary_meta SET builtIn = :builtIn WHERE id = :dictionaryId")
    suspend fun updateBuiltIn(dictionaryId: Int, builtIn: Boolean)

    /** #43: used to make bundled-dictionary installs idempotent. */
    @Query("SELECT * FROM dictionary_meta WHERE name = :name LIMIT 1")
    suspend fun findDictionaryByName(name: String): DictionaryMeta?

    @Query("DELETE FROM dictionary_meta WHERE id = :dictionaryId")
    suspend fun deleteDictionary(dictionaryId: Int)

    @Query("DELETE FROM dictionary WHERE dictionaryId = :dictionaryId")
    suspend fun deleteEntriesForDictionary(dictionaryId: Int)

    @Query("UPDATE dictionary_meta SET name = :name WHERE id = :dictionaryId")
    suspend fun updateName(dictionaryId: Int, name: String)

    @Query("UPDATE dictionary_meta SET priority = :priority WHERE id = :dictionaryId")
    suspend fun updatePriority(dictionaryId: Int, priority: Int)

    @Query("SELECT MAX(priority) FROM dictionary_meta")
    suspend fun getMaxPriority(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<DictionaryTag>)

    @Query("SELECT notes FROM dictionary_tag WHERE name = :tagName AND dictionaryId = :dictionaryId")
    suspend fun getTagNotes(tagName: String, dictionaryId: Int): String?

    @Query("SELECT notes FROM dictionary_tag WHERE name IN (:tagNames)")
    suspend fun getNotesForTags(tagNames: List<String>): List<String>

    @Query("SELECT * FROM dictionary_tag WHERE name IN (:tagNames)")
    suspend fun getTagsByName(tagNames: List<String>): List<DictionaryTag>

    @Query("DELETE FROM dictionary_tag WHERE dictionaryId = :dictionaryId")
    suspend fun deleteTagsForDictionary(dictionaryId: Int)

    @Query("SELECT COUNT(*) FROM dictionary")
    suspend fun getCount(): Int

    @Query("DELETE FROM dictionary")
    suspend fun clearAll()
}
