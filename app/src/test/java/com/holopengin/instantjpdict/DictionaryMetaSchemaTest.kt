package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.data.DictionaryMeta
import com.holopengin.instantjpdict.data.DictionaryMetaSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #71 follow-up: pin the hand-written `catalogId` migration DDL.
 *
 * Same contract as [BookmarkSchemaTest]. The database uses
 * `fallbackToDestructiveMigration(dropAllTables = true)`: if
 * `MIGRATION_5_6`'s DDL does not match what Room generates for
 * [DictionaryMeta], Room reports the migration missing and the fallback wipes
 * every imported dictionary. This is the cheap guard against that drift.
 */
class DictionaryMetaSchemaTest {

    @Test
    fun the_catalog_id_migration_matches_rooms_generated_shape() {
        val ddl = DictionaryMetaSchema.ADD_CATALOG_ID
        assertTrue(ddl.startsWith("ALTER TABLE `dictionary_meta` ADD COLUMN"))
        // A `String?` property is plain TEXT: nullable, so no NOT NULL and no
        // default — which is also what ADD COLUMN leaves pre-existing rows as.
        assertTrue(ddl.contains("`catalogId` TEXT"))
        assertFalse("a nullable column must not be NOT NULL", ddl.contains("NOT NULL"))
    }

    @Test
    fun the_column_name_is_the_entity_property() {
        assertTrue(DictionaryMetaSchema.ADD_CATALOG_ID.contains("`${DictionaryMeta::catalogId.name}`"))
    }
}
