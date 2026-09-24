# Package 05 — Lookup candidates / results

**Goal:** one implementation of the pure lookup pipeline — the query-variant
preparation and the DB-row → term-match grouping — which is currently mirrored
between Android's `OcrOverlayStateController` and PC's `lookup.rs`. The DB query
itself stays per-platform.

**Depends on 02** (entry records) and **04** (the definition parser the format
wrapper uses).

## The PC seam (measured)

`core/src/lookup.rs` (gated `db`) is pure except `lookup_term`, which calls
`db.find_by_texts`/`db.dictionary_names`:

- Pure: `following_text`, `prepare_search_candidates`, `process_results`,
  `format_dictionary_results`, `expand_max_len` (private), `LookupOutcome`.
  `format_dictionary_results` uses `definition_format` (package 04) + the
  already-ungated pitch parsers.
- Bound: `lookup_term` (the DB query + redirect BFS).

Move the pure functions + `LookupOutcome` into a new **ungated**
`core/src/lookup_core.rs` (`pub mod lookup_core;`); `lookup.rs` keeps
`lookup_term` and imports from the new module, so the desktop is unchanged.

## Android seam

`OcrOverlayStateController.kt`:

- `prepareSearchCandidates` (844–914) — pure over `JapaneseUtil`,
  `KanaOrthography`, `KanaSoundChanges`, `Deinflector` and `DeinflectionChain`.
- `processResults` (916–955) — pure over `List<DictionaryEntry>` +
  `JapaneseUtil.normalize`.
- `lookup` (489–600) — DB + UI state + `suspend`; **stays Kotlin** (it can keep
  calling the now-shared pure functions).
- Redirect BFS — stays Kotlin (calls the DB); it consumes package 04's
  `extract_redirect_targets`.

## The tuple problem (decide the crossing shape)

`prepare_search_candidates` returns nested tuples
(`Vec<(usize, Vec<(String, Option<Vec<String>>, Option<DeinflectionChain>)>)>`),
which UniFFI cannot carry. Introduce records, e.g.:

```rust
#[derive(uniffi::Record)] pub struct SearchCandidate {
    pub term: String,
    pub required_types: Vec<String>,          // None -> empty
    pub chain_steps: Vec<String>,             // None -> empty (surface kept separately)
    pub chain_surface: String,
}
#[derive(uniffi::Record)] pub struct CandidateGroup { pub length: i64, pub candidates: Vec<SearchCandidate> }
#[derive(uniffi::Record)] pub struct PreparedCandidates { pub terms: Vec<String>, pub by_length: Vec<CandidateGroup> }
```

and for results a `TermMatch`/`MatchedEntry` record pair. `DictionaryEntryRow`
comes from package 02. Keep the Kotlin `SearchCandidate`/`TermMatch` public
classes and map at the facade. If a smaller boundary is possible (e.g. share
only `prepare`), do that and report — but no duplicated pipeline.

## Deliverable

1. PC: new `lookup_core.rs`; `lookup.rs` delegates; `lookup_term` untouched in
   behaviour.
2. Shim `nav_graph_core/src/lookup_core.rs` (`mod lookup_core;`):
   - `lookup_following_text(active_chars: Vec<String>, global_idx: i64) -> String`
   - `lookup_prepare_candidates(text: String, deinflector: Arc<Deinflector>) -> PreparedCandidates`
   - `lookup_process_results(rows: Vec<DictionaryEntryRow>, prepared, following_text: String, query: String) -> ...`
   - `lookup_format_results(matches, dict_names: Vec<(i64,String)>) -> String` (package 04's JSON shape if it fits).
3. Android: `prepareSearchCandidates`/`processResults` delegate; keep `lookup`
   orchestration.

## Files owned

- PC: `core/src/lookup.rs`, new `core/src/lookup_core.rs`, `core/src/lib.rs`
  (wire).
- Android: `nav_graph_core/src/lookup_core.rs` (new),
  `nav_graph_core/src/lib.rs` (wire),
  `app/src/main/java/com/holopengin/instantjpdict/OcrOverlayStateController.kt`
  (the two candidate functions only — package 04 already edited the parse
  functions).

## Tests to keep green

`DeinflectionChainTest` (8 — exercises `prepareSearchCandidates`,
`processResults`, `formatDictionaryResults`), `LookupCopyTargetsTest`,
`FormatPerDictTest` (3), `BookmarkCandidateTest` (3), and the suite. Mirror the
PC `lookup` tests in the shim.

## Verify

`source /tmp/opencode/env.sh && cd /home/holopengin/Projects/InstantJPDict/nav_graph_core && cargo test`
plus `cargo test -p jpdict_core --no-default-features`. Report the crossing
records you chose and the generated Kotlin names.
