# Package 02 — Row models as core records

**Goal:** make `jpdict_core::data::models` the shared vocabulary for the three
DB row types, so the importer (03) and lookup (05) packages can cross rows over
UniFFI, and the "Equivalent to … in Kotlin" comments stop drifting silently.

The PC structs already mirror the Room entities field-for-field and are already
ungated (`data/models.rs` is reachable with `default-features = false`). This
package adds the boundary and the Android mappers; it does **not** change the
Room entities (Room needs its annotated classes).

## Deliverable

1. **Shim** `nav_graph_core/src/data_models.rs` (`mod data_models;` in `lib.rs`):
   - Records `DictionaryEntryRow { id: i64, kanji: String, reading: String,
     definitions: String, rules: String, popularity: i32, dictionary_id: i64,
     onyomi: Option<String>, kunyomi: Option<String>, jlpt: Option<String> }`,
     `DictionaryMetaRow { id, name, priority, enabled, built_in, catalog_id }`,
     `DictionaryTagRow { id, name, category, order, notes, popularity,
     dictionary_id }`.
   - No free functions needed beyond constructors if UniFFI records already
     construct positionally in Kotlin; add `dictionary_entry_row(...)` factories
     only if the generated constructor is awkward.
2. **Android mappers** (new file `app/src/main/java/com/holopengin/instantjpdict/data/RowMapping.kt`
   or extensions in the entity files — keep the entities untouched):
   - `DictionaryEntry.toRow()` / `DictionaryEntryRow.toEntity()`.
   - `DictionaryMeta`/`DictionaryTag` equivalents, plus
     `InstalledDictionary`/`DictionaryMetaRow` name+id helpers if package 01
     needs them.
3. **No PC change expected** (models.rs is already public/ungated). If you find
   a field mismatch between `data/models.rs` and the Room entity, report it
   loudly rather than silently reconciling.

## Files owned

- Android: `nav_graph_core/src/data_models.rs` (new), `nav_graph_core/src/lib.rs`
  (wire), the new `data/RowMapping.kt` (or entity-file extensions).
- Do not edit the Room entities or DAOs.

## Tests to keep green

`DictionaryMetaSchemaTest` (2), `BookmarkSchemaTest` (3), `DictionaryCatalogTest`
(10) — the mappers must be lossless for the fields the entities carry. Add shim
tests pinning each record round trip.

## Verify

`source /tmp/opencode/env.sh && cd /home/holopengin/Projects/InstantJPDict/nav_graph_core && cargo test`.
Report the generated Kotlin record names and any field mismatch you found.

**Blocks 03 and 05** (they consume these records).
