# Package 03 — Yomitan importer parse

**Goal:** one Yomitan bank parser. Android's `DictionaryImporter` re-reads and
re-interprets the same zip banks PC's importer does; divergence silently changes
which dictionaries import and how (pitch rows, tags, kanji rows, the index.json
title). Move the pure parse half into the core; keep the zip/room I/O per-app.

**Depends on 02** (the row records).

## The PC seam

`core/src/data/importer.rs` (gated `db`) already separates the pure parsing from
the I/O:

- Pure: `extract_bank_number`, `parse_term_bank`, `parse_kanji_bank`,
  `parse_term_meta_bank`, `parse_tag_bank`, `value_to_string`, and the bank
  name/prefix classification + numeric ordering in `import_zip_with`.
- Bound: `import_zip`/`import_zip_with` (`File::open`, `ZipArchive`, `db.*`).

Move the pure functions + the bank classification into a new **ungated**
`core/src/yomitan_parse.rs` (`pub mod yomitan_parse;`); `importer.rs` keeps the
zip/db path and delegates. The parse functions work on bank text and need only
`serde_json` + `data::models` (both ungated).

## Android seam

`data/DictionaryImporter.kt` has the same split: `readZipTitle` (index.json),
`parseTermEntry`, `parseKanjiEntry`, `parseTagBank`, `processTermMetaBank`,
`nextStringOrArray`, `nextIntSafe` are pure; `importZip`/`importBundledAsset`
(Uri/assets), `importZipStream` (Room + Channel), `replaceExisting` (Room) are
bound. Replace the pure ones with shim calls; keep the zip walk and all Room
writes in Kotlin.

## Deliverable

1. PC: new `yomitan_parse.rs` as above; `importer.rs` delegates; add crate tests
   (there is **no** Android `DictionaryImporterTest` today, so the crate tests
   are the parser's only direct pin — mirror the Android shapes you find and use
   the existing Yomitan fixtures under `accessibility_daemon/tests/data/`).
2. Shim `nav_graph_core/src/yomitan_parse.rs` (`mod yomitan_parse;`):
   - `yomitan_parse_term_bank(json: String) -> Vec<DictionaryEntryRow>`
   - `yomitan_parse_kanji_bank(json: String) -> Vec<DictionaryEntryRow>`
     (or a kanji-specific record if the shape differs)
   - `yomitan_parse_tag_bank(json: String) -> Vec<DictionaryTagRow>`
   - `yomitan_parse_term_meta_bank(json: String) -> ...` (pitch rows — follow the
     Android shape; if it is a `DictionaryEntry` with a pitch definitions JSON,
     use the same row record)
   - `yomitan_bank_number(filename: String) -> Option<i64>` if the Android walk
     needs it.
3. Android: the importer's pure functions delegate; the record→Room-entity
   mapping uses package 02's mappers.

## Files owned

- PC: `core/src/data/importer.rs`, new `core/src/yomitan_parse.rs`,
  `core/src/lib.rs` (wire), `core/src/data/mod.rs` if needed.
- Android: `nav_graph_core/src/yomitan_parse.rs` (new),
  `nav_graph_core/src/lib.rs` (wire),
  `app/src/main/java/com/holopengin/instantjpdict/data/DictionaryImporter.kt`.

## Tests to keep green

`ImportProgressTest` (15), `JitendexStructuredContentTest` (23, consumes the
`definitions` JSON the importer stores), `DictionaryMetaSchemaTest` (2), and the
suite. Report how you pinned the parser given there is no importer test.

## Verify

`source /tmp/opencode/env.sh && cd /home/holopengin/Projects/InstantJPDict/nav_graph_core && cargo test`
plus `cargo test -p jpdict_core --no-default-features`. Report the generated
Kotlin names and the fixtures you added.
