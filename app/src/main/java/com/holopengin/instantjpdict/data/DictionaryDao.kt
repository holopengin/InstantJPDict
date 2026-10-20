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
     * #108: bounded English-gloss scan. `definitions` is JSON text, so a
     * leading-wildcard search cannot use the kanji/reading indexes. `json_tree`
     * restricts the match to string values, which keeps JSON keys (`content`,
     * `tag`, `style`) out of the candidate set; the cap and the
     * priority/popularity order bound the work. [DefinitionSearch] then filters
     * the remaining markup-only hits and ranks the real gloss matches.
     *
     * `json_valid` is the guard for the malformed-blob fallback some old rows
     * may carry: an invalid document simply does not enter the scan.
     */
    @Query("""
        SELECT d.* FROM dictionary d
        JOIN dictionary_meta m ON d.dictionaryId = m.id
        WHERE json_valid(d.definitions)
          AND EXISTS (
            SELECT 1 FROM json_tree(d.definitions) AS jt
            WHERE jt.type = 'text'
              AND instr(lower(jt.value), lower(:query)) > 0
          )
        ORDER BY m.priority ASC, d.popularity DESC
        LIMIT :limit
    """)
    suspend fun findByDefinition(query: String, limit: Int): List<DictionaryEntry>

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
