package com.holopengin.instantjpdict.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * #108 follow-up: the English search index.
 *
 * One row per dictionary entry holds that entry's readable gloss text. A query
 * tokenizes the box and asks the FTS shadow table for matching entries, so a
 * lookup no longer scans every JSON definition blob. The engine's JSON remains
 * the source of truth; this is a derived index and can be rebuilt.
 */
@Entity(
    tableName = "dictionary_gloss",
    indices = [
        Index(value = ["entryId"]),
        Index(value = ["dictionaryId"]),
    ],
)
data class DictionaryGloss(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val entryId: Int,
    val dictionaryId: Int,
    val text: String,
)

/**
 * The FTS4 shadow table. Room's `contentEntity` makes this external-content:
 * the text lives in [DictionaryGloss], and this table holds the inverted index.
 * Its rowid is the [DictionaryGloss.id].
 */
@Fts4(
    contentEntity = DictionaryGloss::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "dictionary_gloss_fts")
data class DictionaryGlossFts(val text: String)

/** The three columns the indexer needs from an existing dictionary row. */
data class GlossSource(
    val id: Int,
    val dictionaryId: Int,
    val definitions: String,
)

/**
 * The one entry in the gloss table's schema that the hand-written migration
 * must match. Pinned by [DictionaryGlossSchemaTest] for the same reason the
 * bookmark and catalog DDL are pinned: a missing migration falls back to a
 * destructive recreate.
 */
object DictionaryGlossSchema {
    const val CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS `dictionary_gloss` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`entryId` INTEGER NOT NULL, " +
            "`dictionaryId` INTEGER NOT NULL, " +
            "`text` TEXT NOT NULL)"

    const val CREATE_ENTRY_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_dictionary_gloss_entryId` " +
            "ON `dictionary_gloss` (`entryId`)"

    const val CREATE_DICTIONARY_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_dictionary_gloss_dictionaryId` " +
            "ON `dictionary_gloss` (`dictionaryId`)"

    const val CREATE_FTS =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `dictionary_gloss_fts` " +
            "USING FTS4(`text` TEXT NOT NULL, tokenize=unicode61, content=`dictionary_gloss`)"
}
