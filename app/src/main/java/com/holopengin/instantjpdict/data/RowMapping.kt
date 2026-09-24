package com.holopengin.instantjpdict.data

import uniffi.nav_graph_core.DictionaryEntryRow
import uniffi.nav_graph_core.DictionaryMetaRow
import uniffi.nav_graph_core.DictionaryTagRow

/** Convert a Room dictionary-entry row to the shared UniFFI record. */
fun DictionaryEntry.toRow(): DictionaryEntryRow = DictionaryEntryRow(
    id.toLong(),
    kanji,
    reading,
    definitions,
    rules,
    popularity,
    dictionaryId.toLong(),
    onyomi,
    kunyomi,
    jlpt,
)

/** Convert a shared UniFFI dictionary-entry row back to its Room entity. */
fun DictionaryEntryRow.toEntity(): DictionaryEntry = DictionaryEntry(
    id = id.toInt(),
    kanji = kanji,
    reading = reading,
    definitions = definitions,
    rules = rules,
    popularity = popularity,
    dictionaryId = dictionaryId.toInt(),
    onyomi = onyomi,
    kunyomi = kunyomi,
    jlpt = jlpt,
)

/** Convert a Room dictionary-metadata row to the shared UniFFI record. */
fun DictionaryMeta.toRow(): DictionaryMetaRow = DictionaryMetaRow(
    id.toLong(),
    name,
    priority,
    enabled,
    builtIn,
    catalogId,
)

/** Convert a shared UniFFI dictionary-metadata row back to its Room entity. */
fun DictionaryMetaRow.toEntity(): DictionaryMeta = DictionaryMeta(
    id = id.toInt(),
    name = name,
    priority = priority,
    enabled = enabled,
    builtIn = builtIn,
    catalogId = catalogId,
)

/** Convert a Room dictionary-tag row to the shared UniFFI record. */
fun DictionaryTag.toRow(): DictionaryTagRow = DictionaryTagRow(
    id.toLong(),
    name,
    category,
    order,
    notes,
    popularity,
    dictionaryId.toLong(),
)

/** Convert a shared UniFFI dictionary-tag row back to its Room entity. */
fun DictionaryTagRow.toEntity(): DictionaryTag = DictionaryTag(
    id = id.toInt(),
    name = name,
    category = category,
    order = order,
    notes = notes,
    popularity = popularity,
    dictionaryId = dictionaryId.toInt(),
)
