package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.data.DictionaryGlossSchema
import com.holopengin.instantjpdict.data.DictionaryMetaSchema
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #108 follow-up: the hand-written migration for the English index. Same
 * contract as [DictionaryMetaSchemaTest] and [BookmarkSchemaTest]: if this
 * drifts from Room's generated schema, the destructive fallback wipes imported
 * dictionaries on upgrade.
 */
class DictionaryGlossSchemaTest {

    @Test
    fun theGlossTableAndItsIndicesMatchTheEntityShape() {
        val table = DictionaryGlossSchema.CREATE_TABLE
        assertTrue(table.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(table.contains("`entryId` INTEGER NOT NULL"))
        assertTrue(table.contains("`dictionaryId` INTEGER NOT NULL"))
        assertTrue(table.contains("`text` TEXT NOT NULL"))
        assertTrue(DictionaryGlossSchema.CREATE_ENTRY_INDEX.contains("`entryId`"))
        assertTrue(DictionaryGlossSchema.CREATE_DICTIONARY_INDEX.contains("`dictionaryId`"))
    }

    @Test
    fun theFtsTableIsExternalContentWithTheUnicodeTokenizer() {
        val fts = DictionaryGlossSchema.CREATE_FTS
        assertTrue(fts.contains("USING FTS4"))
        assertTrue(fts.contains("content=`dictionary_gloss`"))
        assertTrue(fts.contains("tokenize=unicode61"))
    }

    @Test
    fun theCompletionFlagIsAddedWithTheMigrationDefault() {
        val ddl = DictionaryMetaSchema.ADD_GLOSS_INDEXED
        assertTrue(ddl.contains("`glossIndexed` INTEGER NOT NULL DEFAULT 0"))
    }
}
