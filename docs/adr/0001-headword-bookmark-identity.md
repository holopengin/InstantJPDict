# 0001 — Headword bookmark identity, storage and surface (#67)

Status: accepted · 2026-09-16

## Context

Bookmarks let a lookup outlive the popup and feed a CSV into Anki. Two things
must hold: a bookmark made today must still point at the same headword after the
user re-imports or replaces a dictionary, and the same headword must not be
saved twice.

The obvious key, `DictionaryEntry.id`, is a Room `autoGenerate` primary key. The
importer does not treat an entry row as stable: a bundled dictionary is
installed by deleting the existing meta row and re-inserting every row
(`DictionaryImporter.importBundledAsset`), and `DictionaryMeta.id` is likewise
reassigned. A bookmark keyed on either id would survive the version bump but
orphan on the next replace.

## Decision

**Identity is the natural key `(kanji, reading, dictionaryName)`.** A unique
index on those three columns plus an `INSERT OR IGNORE` is the "no duplicates"
guarantee.

**The definition text is snapshotted at save time**, not joined from the
`dictionary` table. A deleted or renamed source dictionary therefore cannot
leave an empty or wrong bookmark; the cost is duplicated text, which is
acceptable at vocabulary-collector scale.

**The viewer is a dialog**, opened from the MainActivity button column beside
Manage Dictionaries. The two existing second-level screens
(`DictionaryManagerDialog`, `LicenseDialog`) are dialogs, and a bookmark list
needs no manifest entry or second activity lifecycle. Sort defaults to
insertion order; newest-first is the option.

**Export is a pure, RFC 4180 CSV** with columns
`kanji,reading,dictionary,definitions,added_at` (ISO-8601 UTC), written to
`cache/bookmarks/instant-jpdict-bookmarks-<UTC>.csv` and shared through the
existing FileProvider + `ACTION_SEND`. Escaping is unit-tested because
definitions are structured JSON and routinely contain commas, quotes and
newlines.

**Schema 4 → 5 ships a hand-written `MIGRATION_4_5`.** The database keeps
`fallbackToDestructiveMigration(dropAllTables = true)`; without the migration,
adding a table would wipe every user-imported dictionary. This follows
`74b7dc4`, which added `MIGRATION_3_4` for the same reason.

## Consequences

- A user rename of a dictionary orphans its bookmarks (the bookmarked text
  survives, but it no longer matches the renamed source). Renames are explicit
  and rare; the alternative — keying on a churning id — orphans on every
  re-import.
- Two dictionaries sharing a title collapse to one identity. This mirrors
  `importBundledAsset`, which already replaces by title.
- The bookmark table duplicates definition text; a large collection grows the
  database by the size of its senses.
