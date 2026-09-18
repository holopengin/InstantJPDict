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
signed with the debug key so it sideloads over a debug install. Expected size
~38 MB. Give the maintainer a bare, short URL named by the short commit hash
(no markdown link), e.g. `http://100.112.14.102:8001/<short-sha>.apk`.

Build a debug or benchmark variant only when there is a real reason (native
symbol triage, a benchmark run, or a bug that only reproduces outside R8), and
say why when you deliver it. See #90 for the size history behind this choice.
