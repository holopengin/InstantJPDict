# UI text rewrite — change log and rationale (2026-09-17)

Source: `app-strings.json` (generated from `master@569eb33`, 231 strings); the
maintainer edited `text` and returned it. 58 strings changed. This file records
the exact changes, the reason each class implies, and the questions still open
before these become rules in the `ui-text-design` skill.

Method: the returned `id → text` map was diffed mechanically against the
generated inventory (all 231 ids matched). Blanking is represented as `""`.

## Change classes

### 1. Brevity — cut supporting lines and second sentences
- `licenses.intro`: dropped "Every entry below is read from the APK — no network
  is used."
- `bookmarks.sort.newest` / `.oldest`: "Sort: newest first" → "Sort: newest".
- `manager.empty`: "No user dictionaries installed yet." → "No dictionaries
  installed yet."
- Supporting lines blanked on the home screen: `main.settings.serif_overlay_supporting`,
  `main.settings.gamepad_supporting`, `main.settings.licenses_supporting`,
  `main.settings.debug_supporting`, `main.dictionaries.bookmarks_supporting`.
- `debug.appbar.subtitle`, `debug.behaviour.body`, `licenses.detail_label` blanked.

Inferred rule: prefer one line where a row reads fine without explanation; the
supporting line must earn its place.

### 2. Terminology — plain words over dictionary jargon
- "headword" → "word": `bookmarks.count.one` ("1 bookmarked word"),
  `bookmarks.count.many`, `overlay.copy.headword` ("Copy headword" → "Copy word").
- "popup" → "dictionary": `bookmarks.count.empty` ("Save a word from the
  dictionary").
- `overlay.copy.*` now shares one shape: Copy highlight / Copy word / Copy character.

Inferred rule: use the user's word, not the domain's; keep parallel actions in
parallel form.

### 3. Catalog copy — shorter, choice-oriented
- `catalog_json.jmdict_english.description` → "A standard Japanese-to-English
  dictionary. The smallest option."
- `…_examples.description` → "A standard Japanese-to-English dictionary w/ example
  sentences."
- `catalog_json.kanjidic_english.description` → "Supplementary kanji dictioanry
  for readings, meanings, JLPT grade, stroke count and frequency."
- `catalog_json.jitendex.description` → "The richest dictionary option and
  replacement for the legacy \"JMDict Extra.\""

Inferred rule: lead with what distinguishes the option (smallest / has examples
/ richest / supplementary), not with an inventory of features.

### 4. Pending actions take the future tense
- `manager.remove.message`: "All of its entries and tags are removed." → "…will be
  removed."

Inferred rule: a confirmation describes what is about to happen.

### 5. Debug screen de-jargoned
- `debug.tuning.header`: "PP-OCR parameters" → "OCR parameters".
- `debug.tuning.body`: rewritten from parameter mechanics to effect ("The
  defaults here are the best in *most* scenarios… try setting REC_SQUISH_FACTOR
  higher than 0.5").
- Behaviour supporting lines now state the user-visible consequence:
  "Downside: adds a delay to opening/closing the dictionary popup", "Vertical
  text only; mostly affects punctuation", "Enables handling of tilted text
  lines. Most useful for the camera".

Inferred rule: describe what changes for the reader; keep the implementer's
names for where the reader can act on them.

### 6. Remove runtime values from copy  *(behaviour change — confirm)*
- `debug.tuning.body` no longer interpolates `${OcrEngine.PREFS_NAME}` /
  `${OcrEngine.DET_MODEL_SIZE}`.
- `overlay.status.summary`: `${…} · Det ${detMs}ms | Rec ${recMs}ms | kana:
  $kanaNote` → `${…} · Time ${detMs+recMs}ms`.

### 7. Home-screen voice — shorter, verb-first, no jargon
- `main.appbar.subtitle`: "On-device Japanese OCR" → "Offline OCR".
- `main.get_started.body`: now leads with the prerequisite ("Install a
  dictionary, then turn on the accessibility service…").
- `main.dictionaries.body`: "Install a Yomitan-format dictionary, or grab one
  from the built-in catalog." → "Downloads dictionaries from the catalog, or
  install Yomitan-format dictionaries manually."

### 8. Naming the system popup  *(internally inconsistent — confirm)*
- `main.get_started.denial_note` uses `"App was denied access"`.
- `main.get_started.denied_button` uses `"App access was denied"?`.
The two disagree, and the original used a third wording.

### 9. Consistency nits
- `overlay.form_marker.pri`: `△` → `★` (now shares the glyph with the
  filled-bookmark `★`).
- `gamepad.dialog.title` stays title case ("Gamepad Controls") while every other
  dialog title is sentence case; `main.settings.gamepad_title` became "Gamepad
  controls" (sentence case).
- Spelling/abbreviation: "dictioanry"; "w/"; "JMDict" vs "JMdict".
- `debug.tuning.current_default_dragging` dropped the trailing "(dragging)",
  though its note still says the line is the dragging readout.

### 10. Error phrasing
- `overlay.status.engine_error`: "Error: OCR Engine not ready" → "Error: Failed
  to start OCR engine".

## Maintainer annotations carried on entries (not text edits)
- `main.dictionaries.download_title`/`_supporting`: the catalog replaces this
  link → the row is removed (both blanked).
- `main.settings.serif_overlay_title`: should become a dropdown.
- `overlay.manual_input.title`: the manual-entry popup needs a rework.
- `overlay.kana_fix.*`: the model is bundled, these diagnostics should never be
  reachable → all blanked.

## Resolved decisions (maintainer, 2026-09-17)

1. **A blank `text` means remove that UI string/element entirely.**
2. **Runtime values the copy no longer shows are not wanted.** Where values were
   added together in the new copy (`Time ${detMs+recMs}ms`), print their sum.
3. `overlay.form_marker.pri` → `★` is intentional: the up-triangle was
   confusing; a star marks the primary form.
4. `dictioanry` was a typo — fix it. `w/` is intentional. For `JMDict`, use the
   official capitalisation — `JMdict`.
5. `main.dictionaries.body`: "Downloads" → "Download" (agrees with "install").
6. The real system popup text is **"App was denied access"** (both the note and
   the button use it).
7. Titles switch to **Title Case** (see skill open question on scope).
8. The `(dragging)` suffix is removed — sliders are self-evident and it is
   covered by the finger anyway. Same reasoning for other transient readout
   suffixes.
