# Installing and releasing with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs and updates an app
directly from its GitHub releases. This repo is already shaped for it: tagged
releases, one APK asset, a stable production `applicationId`, and a stable
release key.

## Install

Use the link in the README, or add this URL in Obtainium by hand:

```
https://github.com/holopengin/InstantJPDict
```

The current releases are **release candidates**, so enable **Include
prereleases** for the app. The README's link uses Obtainium's `/app/` deep link
and sets it for you:

```
https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.holopengin.instantjpdict%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fholopengin%2FInstantJPDict%22%2C%22author%22%3A%22holopengin%22%2C%22name%22%3A%22InstantJPDict%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%7D%22%7D
```

Decoded, that is:

```json
{
  "id": "com.holopengin.instantjpdict",
  "url": "https://github.com/holopengin/InstantJPDict",
  "author": "holopengin",
  "name": "InstantJPDict",
  "additionalSettings": "{\"includePrereleases\":true,\"fallbackToOlderReleases\":true,\"autoApkFilterByArch\":true}"
}
```

`autoApkFilterByArch` matters because the APK ships **arm64-v8a only**. The app
requires Android 11 (API 30) or newer.

If a non-prerelease `v1.0.0` is cut later, the shorter form is enough:

```
https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/holopengin/InstantJPDict
```

## What Obtainium checks

- The **APK versionCode** must increase. Obtainium uses it to decide whether an
  update is newer; it also reads the release tag and asset name for display.
- The APK must have the production id **`com.holopengin.instantjpdict`**. Only a
  tagged commit builds that id. An untagged build is `…instantjpdict.dev` and
  would install as a second app rather than update the first.
- The APK must be **signed with the same key as the installed app**. Android
  enforces this; a debug-signed release cannot update a release-signed install.
- The release must expose an **APK asset**. The current naming convention is
  `InstantJPDict-<versionName>.apk`.

## Release checklist

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` on `master`.
2. Commit that bump, then tag the same commit:
   `git tag v1.0.0-rc8 && git push origin master v1.0.0-rc8`.
   Every release tag must be an ancestor of `master`.
3. The `Release APK` workflow builds the tag, verifies the signer and package
   id, and creates/updates the GitHub release. It uploads
   `InstantJPDict-<versionName>.apk` and marks `-rc` tags as pre-releases.
4. If you build locally instead, use the tag, not the working tree:
   `git checkout v1.0.0-rc8 && ./gradlew :app:assembleRelease -Ptagged`.
   Upload `app/build/outputs/apk/release/app-release.apk` under the release
   asset name above.
5. Check the result in Obtainium (or `gh release view <tag> --json assets`).

Do **not** upload `app/release/app-release.apk`; that legacy file was a stale
versionCode-1 artifact signed with a different key, and has been removed. The
only release APK is the one Gradle writes under `app/build/outputs/apk/release/`.

## Signing

The release key is `~/keys/instantjpdict-release.p12`, alias `instantjpdict`,
with its properties in `~/.config/instantjpdict/keystore.properties` (both
outside the tree). The certificate used by the current releases is:

```
SHA-256: E6:8B:44:B6:24:31:3D:55:21:6C:48:72:42:CB:E4:D6:2D:DB:DE:8B:54:AA:92:8C:C9:CF:63:48:22:D2:8B:89
```

The release workflow hard-codes the expected value
(`e68b44b624313d55216c487242cbe4d62ddbde8b54aa928cc9cf634822d28b89`) and fails
the build if the APK is signed with anything else. The Gradle build warns when
the keystore is missing and falls back to the debug key; that fallback must
never be uploaded.

If the key is ever rotated, update the expected fingerprint in
`.github/workflows/release.yml` and this doc. Existing installs cannot update
across a key change; users must uninstall and reinstall.

## Release workflow secrets

`.github/workflows/release.yml` needs these repository secrets:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 ~/keys/instantjpdict-release.p12` |
| `RELEASE_STORE_PASSWORD` | the PKCS12 store password |
| `RELEASE_KEY_ALIAS` | `instantjpdict` |
| `RELEASE_KEY_PASSWORD` | the key password |

The workflow writes the keystore and properties under `$HOME`, so the same
Gradle signing path is used locally and in CI. It checks:

- the tag is an ancestor of `master`;
- the package id is `com.holopengin.instantjpdict`;
- the APK's `versionName` matches the tag (minus the leading `v`);
- the signer's SHA-256 matches the expected release key.

It uses `docs/<versionName>-tester-notes.md` as the release body when that file
exists, otherwise GitHub's generated notes. It is safe to re-run: an existing
release gets its asset replaced and its title/prerelease flag updated.

## Troubleshooting

- **Obtainium shows no update:** check **Include prereleases**, then compare the
  installed app's versionCode with the release APK. A new tag with an unchanged
  versionCode will not offer an update.
- **Install fails with a signature mismatch:** the release was built without the
  release keystore. Rebuild from the tag with the key present.
- **It installs as a second app:** the APK was built from an untagged commit
  (`.dev` id). Tags are required for the production id.
- **“App not installed” on a non-arm64 device:** the APK is arm64-v8a only.

The Obtainium badge artwork used in the README is from the Obtainium project.
