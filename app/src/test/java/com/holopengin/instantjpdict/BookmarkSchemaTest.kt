package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.data.Bookmark
import com.holopengin.instantjpdict.data.BookmarkSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #67: pin the hand-written migration DDL.
 *
 * The database uses `fallbackToDestructiveMigration(dropAllTables = true)`:
 * if `MIGRATION_4_5`'s DDL does not match what Room generates for [Bookmark],
 * Room reports the migration missing and the fallback wipes every imported
 * dictionary. These assertions are the cheap guard against that drift; the
 * exact bytes are also checked against the KSP-generated `AppDatabase_Impl`
 * during review.
 */
class BookmarkSchemaTest {

    @Test
    fun table_ddl_matches_rooms_generated_shape() {
        val ddl = BookmarkSchema.CREATE_TABLE
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS `bookmark` ("))
        assertTrue("autoGenerate PK must be AUTOINCREMENT NOT NULL", ddl.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(ddl.contains("`kanji` TEXT NOT NULL"))
        assertTrue(ddl.contains("`reading` TEXT NOT NULL"))
        assertTrue(ddl.contains("`dictionaryName` TEXT NOT NULL"))
        assertTrue(ddl.contains("`definitionsText` TEXT NOT NULL"))
        assertTrue(ddl.contains("`createdAt` INTEGER NOT NULL"))
    }

    @Test
    fun unique_index_covers_the_natural_headword_key() {
        val ddl = BookmarkSchema.CREATE_UNIQUE_INDEX
        assertTrue(ddl.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS"))
        assertTrue(ddl.contains("ON `bookmark` (`kanji`, `reading`, `dictionaryName`)"))
    }

    @Test
    fun the_index_columns_are_exactly_the_bookmark_key() {
        // The key is derived from the same three fields the unique index names;
        // a rename on one side without the other would silently allow duplicates.
        val key = Bookmark(
            kanji = "食",
            reading = "しょく",
            dictionaryName = "JMdict",
            definitionsText = "eat",
            createdAt = 0
        ).key
        assertEquals("食", key.kanji)
        assertEquals("しょく", key.reading)
        assertEquals("JMdict", key.dictionaryName)
        assertTrue(BookmarkSchema.CREATE_UNIQUE_INDEX.contains("`${Bookmark::kanji.name}`"))
        assertTrue(BookmarkSchema.CREATE_UNIQUE_INDEX.contains("`${Bookmark::reading.name}`"))
        assertTrue(BookmarkSchema.CREATE_UNIQUE_INDEX.contains("`${Bookmark::dictionaryName.name}`"))
    }
}
