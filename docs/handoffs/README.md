# Sharing follow-ups: single-sourcing the data / format layer

These five packages move the remaining pure, duplicated logic out of the Android
app and into `jpdict_core`, using the established `nav_graph_core` UniFFI shim
pattern. Background and the can/should/shouldn't analysis: the coordinator's
report on this branch.

## Shared rules (every package)

- **Environment**: prefix shell commands with `source /tmp/opencode/env.sh`
  (repo paths, JDK, linker, python). Android repo
  `/home/holopengin/Projects/InstantJPDict` (branch `master`); PC repo
  `/home/holopengin/repos/InstantJPDictDecky` (`master`).
- **Pattern**: a shim in `nav_graph_core/src/<module>.rs` delegates to
  `jpdict_core`; the Kotlin facade keeps its public API so call sites and tests
  compile unchanged; the shim carries mirror tests through the exported surface.
  Study `nav_graph_core/src/char_placement.rs` and `blank_gaps.rs`.
- **Boundary conventions**: `char`↔`i32`, `usize`↔`i64`, `[T;N]`↔`Vec<T>`,
  `HashMap` fields stay private behind objects; records are
  `#[derive(uniffi::Record)]`.
- **PC changes**: for packages that need them, edit the **shared PC working
  tree** (the coordinator has pointed `nav_graph_core/Cargo.toml` at the local PC
  path for these waves). Do **not** commit, do **not** bump the pin, do **not**
  run gradle or `build_nav_graph.sh`. Leave the tree changed and report the exact
  file list. The coordinator commits and re-pins.
- **Tests to keep green** are named in each package; do not edit an existing
  test unless the package explicitly says so.
- **No Rust source copied into Kotlin**; no algorithm in the shim — it only
  converts.

## Packages and waves

| # | Package | Depends on | Wave |
|---|---|---|---|
| 01 | [Catalog single-source](01-catalog.md) | — | 1 |
| 02 | [Row models as core records](02-data-models.md) | — | 1 |
| 04 | [Definition format + redirects + plain](04-definition-format.md) | — | 1 |
| 03 | [Yomitan importer parse](03-yomitan-parse.md) | 02 | 2 |
| 05 | [Lookup candidates / results](05-lookup-candidates.md) | 02, 04 | 2 |

Wave 1 runs in parallel (disjoint files): 01 (PC `data/catalog.rs`,
`data/db.rs`; Android `util/DictionaryCatalog.kt`), 02 (Android `data/*`
entities; shim only), 04 (PC `overlay_state.rs`, new `core/src/definition_format.rs`;
Android `OcrOverlayStateController.kt` parse fns, `util/Bookmarks.kt`).

Wave 2 starts after Wave 1 is merged: 03 (PC `data/importer.rs`, new
`core/src/yomitan_parse.rs`; Android `data/DictionaryImporter.kt`) and 05
(PC `lookup.rs`, new `core/src/lookup_core.rs`; Android
`OcrOverlayStateController.kt` prepare/process), which are disjoint from each
other.
