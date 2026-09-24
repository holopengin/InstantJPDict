# Package 04 — Definition format + redirects + plain text

**Goal:** one Yomitan/Jitendex definition implementation. Delete Android's
recursive structured-content walker, sense-group splitter, redirect extractor
and plain-text walker; delegate to `jpdict_core`. The `dictionary-01-jitendex`
conformance case already runs this formatting **blind on both implementations**,
so the contract is already treated as shared — this package removes the second
implementation.

## The PC seam (measured)

`core/src/overlay_state.rs` (currently `#[cfg(feature = "db")]`, but nothing in
these functions needs the DB):

- Pure parsers/walkers: `content_class`, `parse_definition`,
  `parse_definition_into`, `example_parts`, `parse_glossary` (`pub(crate)`),
  `split_sense_group`, `citation_text`, `get_attr`, `is_example`,
  `is_inline_node`, `is_block`, `ParsedGlossary`/`ParsedSenseGroup`.
- `extract_redirect_targets` + free `percent_decode` (pure).
- `format_dictionary_results` (wrapper over `lookup::format_dictionary_results`).
- `pitch_positions_of`/`pitch_reading_of` already delegate to the ungated
  `util::pitch`.

Move all of that into a **new ungated module** `core/src/definition_format.rs`
(`pub mod definition_format;`), leaving `overlay_state.rs` a thin delegating
caller and keeping only the `Rc`/`Cell` viewport/cache state machine there.

## The recursion problem (decide first)

`DefinitionNode` is recursive (`Group{ nodes }`, `Example{ content, parts }`, …),
which UniFFI records cannot express directly. Recommended boundary: the shim
returns the **formatted result as a JSON string** (serde on the core types); the
Kotlin facade maps that JSON into its existing `DefinitionNode`/`FormattedEntry`
model. That keeps the algorithm single-sourced and the mapper presentation-only.
If you find a cleaner non-recursive shape (a flattened arena with child indices
also works), use it — but **no duplicated walker** on the Kotlin side.

Flat outputs are straightforward records: `extract_redirect_targets` →
`Vec<String>`, and `Definitions.plain`/`plainAll` (Android
`util/Bookmarks.kt`) → `String`.

## Deliverable

1. PC: split `overlay_state.rs` as above; new `definition_format.rs`; keep the
   desktop and the `dictionary` conformance working (`format_dictionary_results`
   delegates to the new module).
2. Shim `nav_graph_core/src/definition_format.rs` (`mod definition_format;`):
   - `definition_format_parse(glossary_json: String) -> String` (the formatted
     result JSON) — or the arena/records shape you choose;
   - `definition_format_redirect_targets(definitions_json: String) -> Vec<String>`;
   - `definition_format_plain(definitions_json: String, include_all: bool) -> String`.
3. Android: `OcrOverlayStateController.parseDefinition`/`parseGlossary`/
   `splitSenseGroup` map the shim's output to the existing `DefinitionNode`
   model; `util/DictionaryRedirects.extractTargets` and
   `Bookmarks.plain`/`plainAll` delegate. Keep `formatDictionaryResults`'s
   Android grouping only if the shim does not already return the grouped result;
   prefer delegating the whole grouping.

## Files owned

- PC: `core/src/overlay_state.rs`, new `core/src/definition_format.rs`,
  `core/src/lib.rs` (wire), `core/src/lookup.rs` only if the
  `format_dictionary_results` wrapper moves.
- Android: `nav_graph_core/src/definition_format.rs` (new),
  `nav_graph_core/src/lib.rs` (wire),
  `app/src/main/java/com/holopengin/instantjpdict/OcrOverlayStateController.kt`
  (parse fns only — package 05 edits the candidate fns later, not now),
  `app/src/main/java/com/holopengin/instantjpdict/util/DictionaryRedirects.kt`,
  `app/src/main/java/com/holopengin/instantjpdict/util/Bookmarks.kt`
  (`plain`/`plainAll` only).

## Tests to keep green

`JitendexStructuredContentTest` (23), `FormatPerDictTest` (3),
`DictionaryRedirectsTest` (6), `BookmarkExportTest` (21), the `dictionary-01`
conformance case, and the suite. Mirror the PC assertions in the shim.

## Verify

`source /tmp/opencode/env.sh && cd /home/holopengin/Projects/InstantJPDict/nav_graph_core && cargo test`
plus `cargo test -p jpdict_core --no-default-features` and the `dictionary`
conformance on the PC side. Report the JSON/record shape you chose and the exact
generated Kotlin names.
