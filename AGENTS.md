# AGENTS.md

## Agent skills

### Issue tracker

GitHub Issues via `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default canonical labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context (one `CONTEXT.md` + `docs/adr/` at repo root). See `docs/agents/domain.md`.

## Builds delivered to the maintainer

**Default: the release build.**

```
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

It is R8-minified + resource-shrunk, native debug symbols stripped
(`keepDebugSymbols` is off unless `-PkeepNativeDebugSymbols` is passed), and
signed with the maintainer's release key when
`~/.config/instantjpdict/keystore.properties` exists (keystore at
`~/keys/instantjpdict-release.p12`; the password manager holds the backup and
password). Without that file the build falls back to the debug key and warns
on the console — never hand a fallback build to testers as a release. The
debug→release signing switch was one-way: installs signed with the old debug
key needed one uninstall/reinstall. Verify any artifact with
`~/android-sdk/build-tools/36.0.0/apksigner verify --print-certs <apk>`.
Expected size ~38 MB. Give the maintainer a bare, short URL named by the short
commit hash (no markdown link), e.g.
`http://100.112.14.102:8001/<short-sha>.apk`.

**Release tags come from master.** Bump `versionName`/`versionCode` in a commit
on master, tag that commit (`vX.Y.Z-rcN`), and push both. Every release tag must
be an ancestor of master, so a master build installs over the latest release
instead of being a version downgrade. `release/*` branches are optional; if one
is cut, merge it back into master so the tagged commit lands in master's
history. Version bumps happen only with tags — between tags master keeps the
last released version.

Build a debug or benchmark variant only when there is a real reason (native
symbol triage, a benchmark run, or a bug that only reproduces outside R8), and
say why when you deliver it. See #90 for the size history behind this choice.
