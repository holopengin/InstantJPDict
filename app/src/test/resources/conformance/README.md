# Shared conformance corpus (Android copy)

Byte-identical copies of the PC corpus cases, run from JVM host tests by
`ConformanceCorpusTest` (same package, `app/src/test/...`).

- Source: `tests/conformance/cases/*.json` in the InstantJPDictPC (PC) repo,
  at PC commit `68ed90dc0aba99bb4bd33fe959ef7185ae1b368d` (47 cases: the
  `char-placement` Tier-1 vectors and the two NBSP/deinflection dictionary
  cases are included).
- The `deinflection` cases run against the Android-side copy of the shipped
  rules, `app/src/main/assets/deinflect.json` (verified byte-identical with
  the PC `assets/deinflect.json` at copy time).
- Case format, tolerances and the parity-bug rule ("a parity bug fix adds a
  conformance case") are documented PC-side in `FORMAT.md` / `TOLERANCES.md`;
  this directory carries no fork of those docs on purpose, so the two sides
  cannot drift apart in prose.
- `images/` holds only the three synthetic PNGs the `detection` cases
  reference. The nine `recognition` fixtures (~4.8 MB, PC-hosted inference)
  are deliberately NOT copied: that kind stays Android-manual (see the
  runner's `ANDROID_MANUAL` table for the reason).
- The six `char_placement` vectors are also Android-manual here: their
  `mobile_mirror` is `CharPlacementTest`, which pins the same Python
  reference with the Android-side shim. There is no JVM runner for that kind.
- The `dictionary` cases either carry `definitions` inline (synthetic edge
  cases such as the NBSP tag case) or use `definitions_ref` to resolve through
  the Android-side copy of the shared Jitendex fixture,
  `app/src/test/resources/jitendex/entries.json` (verified byte-identical with
  the PC `tests/data/jitendex/entries.json` at copy time).

## Re-sync procedure

1. Re-copy: `cp <pc-checkout>/tests/conformance/cases/*.json app/src/test/resources/conformance/cases/`
   (plus any new `images/*.png` a case references) and confirm with `cmp`.
2. Record the new PC commit hash at the top of this note.
3. Re-run: `./gradlew :app:testBenchmarkUnitTest --tests "*Conformance*"`.
   A red case is drift until proven a platform substitution — record it in
   the ticket (`pipeline-sharing/01`), never hand-edit a copied expectation.
