# Package 01 — Catalog single-source

**Goal:** one source of truth for the dictionary download catalog — the pinned
entries *and* their parse/validation/installed-state logic — owned by
`jpdict_core` and consumed by Android. The catalog is already shared in intent
but has **diverged**; this package removes the drift and the duplicate parser.

## The drift (verified)

| | PC (`assets/catalog/dictionaries.json`) | Android (`app/src/main/assets/catalog/dictionaries.json`) |
|---|---|---|
| entries | 2 (`jitendex`, `kanjidic-english`) | 4 (adds `jmdict-english`, `jmdict-english-with-examples`) |
| keys | `id,name,description,url,bytes,sha256,family,license,source` | `id,name,description,license,source,title,bytes,sha256,recommended,url` |
| `schema` | absent | `1` |

Different SHA-256. Android's file is the newer/canonical one.

## Deliverable

1. **Canonical data**: replace the PC repo's
   `accessibility_daemon/assets/catalog/dictionaries.json` with Android's
   4-entry file (byte-identical). Android keeps its asset (it is the licence
   reference and the test fixture) but stops reading it at runtime.
2. **PC model/parse** in `core/src/data/catalog.rs`:
   - `CatalogEntry` gains `title` (rename from `family`), `recommended: bool`
     (default false); `CatalogFile` gains `schema`; keep `id/name/description/
     url/bytes/sha256/license/source`.
   - Move the strict validation Android does at parse time into the core: schema
     == 1, required fields, `bytes > 0`, 64 lowercase hex `sha256`, https url,
     no `/releases/latest/`, no duplicate ids. Keep `entries()` embedded via
     `include_str!` and `OnceLock`.
   - Lift `name_matches_family` out of `data/db.rs` into `catalog.rs` (it is pure
     string matching) and make `is_installed` ungated.
   - Update `db.rs` call sites to the moved function.
3. **Shim** `nav_graph_core/src/catalog.rs` (`mod catalog;` in `lib.rs`):
   - Record `CatalogEntryRecord` (all fields; `title`/`recommended` included).
   - `catalog_entries() -> Vec<CatalogEntryRecord>`.
   - `catalog_installed_ids(installed_names: Vec<String>, installed_catalog_ids:
     Vec<Option<String>>) -> Vec<String>` (or an `InstalledDict` record list) and
     `catalog_base_title(name: String) -> String` — mirror Android's
     `DictionaryCatalog.installedIds`/`baseTitle` semantics.
4. **Android** `util/DictionaryCatalog.kt`: `parse`, `baseTitle`, `installedIds`
   delegate to the shim; `CatalogEntry` maps to/from the record. Keep
   `formatBytes`/`sizeLabel`/`fileName` in Kotlin (UI presentation). `ASSET`
   stays as the fixture path for the parity test.
5. **Parity guard**: `DictionaryCatalogTest` reads the shipped asset and asserts
   the core's entries match it (same ids, and the asset parses to the same
   entries). Then a stale Android asset fails the suite instead of silently
   disagreeing.

## Files owned

- PC: `accessibility_daemon/assets/catalog/dictionaries.json`,
  `core/src/data/catalog.rs`, `core/src/data/db.rs` (only the
  `name_matches_family` move + call sites).
- Android: `nav_graph_core/src/catalog.rs` (new), `nav_graph_core/src/lib.rs`
  (wire), `app/src/main/java/com/holopengin/instantjpdict/util/DictionaryCatalog.kt`,
  `app/src/test/java/com/holopengin/instantjpdict/DictionaryCatalogTest.kt`.
- Call sites to check: `DictionaryCatalogDialog.kt` (uses entries/installedIds),
  `data/DictionaryDownloader.kt`.

## Tests to keep green

`DictionaryCatalogTest` (10), `DictionaryDownloadTest` (4), and the suite. The
PC `catalog.rs` tests move to the new field names and gain the strict-validation
cases.

## Verify

`source /tmp/opencode/env.sh && cd /home/holopengin/Projects/InstantJPDict/nav_graph_core && cargo test`
plus the PC watcher's `cargo test -p jpdict_core --no-default-features`. Report
the exact generated Kotlin names.
