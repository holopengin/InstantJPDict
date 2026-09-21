# Shared conformance corpus (Android copy)

Byte-identical copies of the PC corpus cases, run from JVM host tests by
`ConformanceCorpusTest` (same package, `app/src/test/...`).

- Source: `accessibility_daemon/tests/conformance/cases/*.json` in the
  InstantJPDictDecky (PC) repo, at PC commit
  `c7dc78bcdd302822a6c9efe19d6941878fa268f4` (33 cases: +4 `deinflection`,
  +1 `ruby_style` graduating tickets 07/06; needs no new `images/`).
- The `deinflection` cases run against the Android-side copy of the shipped
  rules, `app/src/main/assets/deinflect.json` (verified byte-identical with
  the PC `accessibility_daemon/assets/deinflect.json` at copy time).
- Case format, tolerances and the parity-bug rule ("a parity bug fix adds a
  conformance case") are documented PC-side in `FORMAT.md` / `TOLERANCES.md`;
  this directory carries no fork of those docs on purpose, so the two sides
  cannot drift apart in prose.
- `images/` holds only the three synthetic PNGs the `detection` cases
  reference. The nine `recognition` fixtures (~4.8 MB, PC-hosted inference)
  are deliberately NOT copied: that kind stays Android-manual (see the
  runner's `ANDROID_MANUAL` table for the reason).
- The `dictionary` case's payload (`definitions_ref`) resolves to the
  Android-side copy of the shared Jitendex fixture,
  `app/src/test/resources/jitendex/entries.json` (verified byte-identical
  with the PC `tests/data/jitendex/entries.json` at copy time).

## Re-sync procedure

1. Re-copy: `cp <pc-checkout>/accessibility_daemon/tests/conformance/cases/*.json app/src/test/resources/conformance/cases/`
   (plus any new `images/*.png` a case references) and confirm with `cmp`.
2. Record the new PC commit hash at the top of this note.
3. Re-run: `./gradlew :app:testDebugUnitTest --tests "*Conformance*"`.
   A red case is drift until proven a platform substitution — record it in
   the ticket (`pipeline-sharing/01`), never hand-edit a copied expectation.
