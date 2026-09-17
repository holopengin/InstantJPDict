# Dictionary catalog (#71)

The **Dictionary Catalog** button on the main screen lists popular Yomitan
dictionaries and imports one in a tap — download, verify, insert — instead of
making the user find a zip, get it onto the phone and pick it. It is a static,
bundled list (`app/src/main/assets/catalog/dictionaries.json`); there is no
remote catalog service.

The built file picker (**Install Yomitan dictionary**) and the upstream
browser link (**Download Dictionaries**) are unchanged. The catalog is additive:
all three write rows through the one `DictionaryImporter` path.

## Surface

The dialog follows the app's "Harbour" Material 3 language (the main-screen
redesign): one tonal `MaterialCardView` per dictionary on
`colorSurfaceContainerLow`, the Material 3 type scale, `MaterialButton` roles
for **Install**/**Cancel**, a `MaterialAlertDialog` and a
`LinearProgressIndicator`. Every colour comes from the theme, so day/night and
Material You both flow through untouched. That replaced the hand-rolled
`CatalogPalette` (and its test): the palette existed only because the dialog was
not themed, and it is what the #71 night-mode bug was fixed through.

## The asset

`app/src/main/assets/catalog/dictionaries.json`, hand-maintained. Schema 1:

```json
{
  "schema": 1,
  "entries": [
    {
      "id": "jmdict-english",
      "name": "JMdict (English)",
      "description": "…",
      "kind": "yomitanZip",
      "url": "https://github.com/yomidevs/jmdict-yomitan/releases/download/2026-09-15/JMdict_english.zip",
      "bytes": 15594803,
      "sha256": "58983250…",
      "title": "JMdict",
      "license": "CC BY-SA 4.0 (EDRDG)",
      "source": "yomidevs/jmdict-yomitan @ 2026-09-15"
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | stable catalog key, unique |
| `name`, `description` | the row's title and body |
| `kind` | `yomitanZip` (download) or `bundledAsset` (already in the APK) |
| `url` | HTTPS source, **only** for `yomitanZip` |
| `asset` | APK asset path, **only** for `bundledAsset` |
| `bytes`, `sha256` | the pinned size and digest, checked after download |
| `title` | the dictionary's stable title family |
| `license` | label shown on the row; full texts in the Licenses viewer (#70) |
| `source` | one-line provenance shown on the row |

`DictionaryCatalog.parse` is strict, and `DictionaryCatalogTest` asserts the
shipped file: a missing pin, a non-HTTPS URL, or a URL pointing at
`/releases/latest/` fails rather than importing something unverifiable.

### Why `title` is separate from `name`

The upstream zip's `index.json` titles carry the release date — `JMdict
[2026-09-15]`, `KANJIDIC [2026-258]` — so installed state is matched on the
family title with `DictionaryCatalog.baseTitle`, which strips a trailing
bracketed revision. `JMdict Forms` therefore does **not** count as `JMdict`.

### Shared titles: the two JMdict variants

Upstream publishes two English JMdict builds that are alternatives to each
other:

* `JMdict_english.zip` — no example sentences (the default);
* `JMdict_english_with_examples.zip` — the same entries with Tatoeba example
  sentences on each sense.

They declare the **same** `index.json` title, `JMdict [2026-09-15]`, so
`DictionaryMeta.name` alone cannot tell them apart, and the importer — which
replaces by title — means installing one deletes the other. That is real mutual
exclusivity on disk; the missing piece is the UI knowing *which* one is
installed.

`DictionaryMeta.catalogId` (migration `5→6`) closes it: a catalog import stamps
the catalog entry's `id` on the meta row, and
`DictionaryCatalog.installedIds` matches a catalog install by that id. A
title-only install — the file picker, or a dictionary imported before this
column existed — has no id and resolves to the **first** entry of its title
family (the default, `jmdict-english`). So two entries may share a title family,
and only one of them ever reports **Installed**. This is why the catalog may now
list more entries than it has distinct titles.

## The two import shapes

* **`yomitanZip`** — `DictionaryDownloader` GETs the URL with
  `HttpURLConnection` (no HTTP client dependency, and GitHub release assets
  need no `User-Agent`), streams it into the app cache, and
  `DictionaryDownload.writeVerified` hashes as it writes. Nothing touches the
  database until the size and SHA-256 match. The cached file is deleted after
  the import, on a failed import, and on cancel.
* **`bundledAsset`** — the pitch dictionary, installed from the APK with no
  network at all.

Both then call the existing importer (`importZip` for the cached file via a
`file://` Uri, `importBundledAsset` for the asset), passing the entry's `id` as
`catalogId` so the install is attributable. The catalog never writes dictionary
rows itself.

## Idempotency

`DictionaryImporter.importZip` reads the zip's declared title from a first open
and deletes any existing dictionary with that title before importing — the same
replace the bundled path always did. So re-importing the same dictionary (from
the catalog's **Reinstall** or from the file picker) repairs the install instead
of stacking a second copy, and importing the other JMdict variant replaces the
installed one rather than adding beside it. Installed state is read from
`DictionaryMeta` — `name` plus `catalogId` — so the row says **Installed** from
the same truth **Manage Dictionaries** shows.

## The pins

Pinned to release `2026-09-15` of
[yomidevs/jmdict-yomitan](https://github.com/yomidevs/jmdict-yomitan), recorded
2026-09-16 from the real artifacts:

| Dictionary | URL (release `2026-09-15`) | Bytes | SHA-256 |
|---|---|---|---|
| JMdict (English) | `JMdict_english.zip` | 15,594,803 | `58983250d41fb8e9ea656cb7678939ecc0f82b4a91a595ad04696f88fe599472` |
| JMdict (English, with examples) | `JMdict_english_with_examples.zip` | 18,080,622 | `cb7891fae661b901ae17f716d2e55ecaf377aa7c2dbf69841378af13d5e73cd8` |
| KANJIDIC (English) | `KANJIDIC_english.zip` | 721,032 | `51a7a1aa3f996e60742b9e5f62144422926060d48208d9bdb9f51ecfe9dd4f74` |
| Kanjium pitch accents | bundled `pitch/kanjium_pitch_accents.zip` (#43) | 1,207,703 | `c1cdb4f4d930ff569490fe0b7d93b58eef23880c75f54660fd5fbca05b71740f` |

The pitch row names its source, not a URL: the data is vendored in the APK (see
[bundled-dictionaries.md](bundled-dictionaries.md)) and installed as a built-in
at first launch, so the catalog row reflects that install and needs no network.
That is the reconciliation of #43's pitch question with the maintainer's scope
note: the catalog points at the bundled asset.

**Updating a pin.** Download the new asset, `sha256sum` it, `stat -c %s` it, and
edit both the URL's release tag and the numbers together. A pin that no longer
matches either fails the download (and cleans up) or fails
`DictionaryCatalogTest` for the bundled zip.

## `INTERNET`, and the offline state

The catalog adds `android.permission.INTERNET` — the only permission #71 adds,
and the only change to the app's privacy posture. `NetworkPermissionTest` holds
that: the declared set is exactly `CAMERA` (#78) + `INTERNET`.

The app is offline until the user taps **Install**. The licence viewer, OCR,
pitch install and manual `.zip` import never touch the network. If a download
cannot reach the host (no DNS, refused connection, no route, timeout) the row
shows **Download unavailable — no connection** and offers **Retry**, with a
banner above the list saying the same; the bundled pitch row is unaffected. A
failed or cancelled download leaves no cache file and no database row behind.

The offline state is reached from the failed request, not asked for ahead of
time: reading connectivity would need `ACCESS_NETWORK_STATE`, and the acceptance
criterion is that `INTERNET` is the only permission added. That is the one
decision in the feature for the maintainer to ratify.

## Licences

The catalog names each dictionary's licence and its **Licences** button opens
the #70 viewer, where the CC BY-SA 4.0 and EDRDG texts ship in full. The
catalog metadata asset itself is first-party app data and has its own entry in
the bundled-licence index
(`app/src/main/assets/licenses/notices/dictionary-catalog.txt`). The imported
dictionaries' own licences travel with the import; see
[licenses.md](licenses.md) ("Out of scope").

## Tests

| Test | What it pins |
|---|---|
| `DictionaryCatalogTest` | the shipped asset lists both JMdict variants/KANJIDIC/pitch; every network row is pinned to a dated release with size + hash; the bundled row's pin matches the committed zip; only the JMdict variants share a title, and `catalogId` resolves them to exactly one installed row; installed-state matching; strict parsing |
| `DictionaryDownloadTest` | verification against known-good SHA-256 literals; a file is deleted on a hash/size mismatch or cancellation; a verified file is kept |
| `DictionaryMetaSchemaTest` | the hand-written `catalogId` migration DDL matches Room's generated shape (a nullable `TEXT` column), so the destructive fallback cannot fire |
| `NetworkPermissionTest` | `INTERNET` is the only permission the feature adds, and the declaration says why |
