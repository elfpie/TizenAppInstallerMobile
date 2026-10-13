# Tizen Pocket

A React Native Android app that installs custom Tizen TV packages directly over Wi-Fi. This is a separate repository from TizenAppInstallerCli; it does not require a computer, Tizen Studio, a relay server, or Expo Go during use.

## Features

- Discover TVs through Samsung HTTP metadata or SDB, or connect by IPv4 address. Show the phone’s Wi-Fi/Ethernet IP and route TV connections through that LAN network when a VPN is active.
- Built-in Jellyfin, TizenBrew and Moonlight catalog using current GitHub releases, with explicit package variant selection.
- Automatic update checks after connecting and installing, plus a manual **Check for updates** button. Show the installed TV version, available package version and update status on each app card.
- Install local `.wgt` / ZIP-based `.tpk` packages or download them from HTTPS links. Native TPK compatibility and privilege requirements still depend on the TV.
- Samsung account sign-in in the system browser, public author/distributor certificate provisioning, reuse of saved profiles, and PKCS#12 import from the CLI or Tizen Studio.
- In-place updates, progress, cancellation, installed-app listing, and password-protected signing certificate backups.
- GitHub Actions builds standalone preview APKs on pushes and pull requests and signed release APKs on version tags.

## Start using the app

1. Install the preview APK from the workflow's **tizen-pocket-preview** artifact, or build one locally with `npm run apk`. It contains the JavaScript bundle and runs without Metro.
2. Connect the phone and TV to the same network.
3. Open **Apps** on the TV, enter **12345**, enable Developer Mode, and set the Developer Mode host IP to the phone IP displayed under **Your TV**. Restart the TV. [Samsung's device setup guide](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html).
4. Scan for TVs or enter the TV IP. The app reads installed versions and checks upstream releases automatically.
5. Under **Settings**, create a Samsung signing profile for a new installation, or import the original certificates for an existing installation.
6. Pick a catalog app or a custom package, then select **Install / update in place**.

An imported package can also be installed with **Samsung signing** turned off, using its existing signatures. Those signatures must already be valid for your TV. Apps needing partner privileges require an appropriate imported signing profile; automatic provisioning currently creates Public profiles.

## Keeping Jellyfin settings and login

The installer never uninstalls an existing app. It preserves the package/application IDs and package content, and reuses the author key. Samsung requires the original author key for updates: [certificate guidance](https://developer.samsung.com/galaxy-watch-tizen/getting-certificates/overview.html).

For Jellyfin already installed with the CLI, copy the matching TV's `author.p12` and `distributor.p12` to the phone, then import them in **Settings**. On Linux the CLI stores these under:

    ~/.local/share/TizenAppInstallerCli/CertificateCache/<profile>/

The CLI's cached certificate files use an empty password. Import validates the keys, certificate expiry, and the distributor certificate's TV DUID before saving. A profile generated for another TV is rejected.

Use the same Jellyfin package variant. Changing the application ID or author certificate is refused for tracked installations. Distributor renewal retains the saved author certificate. A TV install failure is reported without an uninstall fallback. Settings retention depends on Tizen's in-place upgrade behavior and the TV application itself; live TV testing is still required, including verifying Jellyfin's server and login after an upgrade.

Generated profiles are encrypted using an Android Keystore AES-GCM key and excluded from Android backup. Phone app updates signed with the same Android key retain them. Use **Settings → Back up your signing keys** to export a ZIP containing password-protected author.p12 and distributor.p12 files. This also lets you migrate from the preview app to the release app or another phone. Unzip it and import both certificates with the backup password. Uninstalling the phone app or clearing its data deletes its saved profiles; export a backup first. The Samsung access token is used only during provisioning and is not saved.

## How update detection works

The native module reads `app_version`, `app_tizen_id`, title and installation path from the TV's `vd_applist` response. It reads the actual upstream package manifest instead of treating a GitHub release tag as an app version. Package inspections download the candidate package to temporary phone storage, verify the GitHub SHA-256 digest when provided, compute a content fingerprint and cache metadata for six hours; they never install anything.

TizenBrew and Moonlight normally have comparable numeric package versions. Jellyfin releases currently reuse manifest version **0.1.0**, so version equality alone cannot confirm its client build. When readable, the app pulls the installed author signature through SDB and compares its SHA-512 file references with the upstream package's file hashes:

| Status | Meaning |
| --- | --- |
| Update available | The upstream package has a higher comparable version. |
| Up to date | The content fingerprint matches, or a versioned non-Jellyfin package has the same version. |
| Different build available | Same version, different signed content. Review the release; this does not prove it is chronologically newer. |
| Installed version is newer | The TV has a higher version; no upgrade is claimed. |
| Build version unknown | The TV did not expose enough information, including fixed-version Jellyfin builds with unreadable signatures. |
| Different variant installed | App IDs differ; select the matching variant before updating. |
| Not installed | No matching app is present in a valid TV inventory. |
| Could not check updates | TV, download or GitHub request failed; not an up-to-date verdict. |

Checks use the last successfully installed catalog asset variant when known. An app installed elsewhere may need its variant selected manually. Custom apps have no universal release source; they can be installed/updated from a new local package or URL, and their TV versions appear in the installed-app listing.

## Development and builds

Requirements: Node 24 LTS or newer supported by React Native 0.87.1, Java 21, Android SDK platform **37.0**, build tools **37.0.0**, and NDK **27.1.12297006**. The app targets API 36 and supports Android 7.0 / API 24 and newer.

```bash
npm ci
# Set your SDK path in android/local.properties, or set ANDROID_HOME.
npm run typecheck
npm run lint
npm test -- --runInBand
npm run test:android
npm run apk
```

The standalone preview APK and checksum are written to `artifacts/`. Preview uses a development Android signing key and a separate `.preview` application ID. For development with a phone/emulator, run `npm start` and `npm run android`.

The transport and signing module is Android-only. The generated iOS scaffold is not a working TV installer; no iOS builds are configured.

## CI and release signing

Push this repository to GitHub to activate `.github/workflows/android.yml`.

Every push and pull request runs TypeScript, ESLint, Jest, and native JVM tests, then builds a standalone preview APK with all four Android ABIs. Download the APK and SHA256SUMS from the workflow's artifact. No secrets are needed for previews, including forked pull requests.

For production releases, create and retain an Android signing key:

```bash
keytool -genkeypair -v -keystore tizen-pocket.jks -alias tizen-pocket -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 tizen-pocket.jks > tizen-pocket.keystore.base64
```

Configure these repository Actions secrets:

| Secret | Value |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | Base64-encoded keystore file. |
| ANDROID_KEYSTORE_PASSWORD | Keystore password. |
| ANDROID_KEY_ALIAS | Key alias, e.g. tizen-pocket. |
| ANDROID_KEY_PASSWORD | Key password. |

Pushing a tag such as `v0.1.0` builds a release APK, uploads an artifact and creates a GitHub release with the APK and checksum. The workflow run number supplies a monotonically increasing Android versionCode. Reuse the same keystore for later APK updates. Release builds fail clearly if signing is missing; they do not silently use the preview key.

For a local release, set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `ANDROID_VERSION_CODE`, and `ANDROID_VERSION_NAME`, then run `./android/gradlew -p android assembleRelease`.

## Validation and limits

JVM tests validate both XML signatures using Apache Santuario independently of the signer, detect content tampering, exercise PKCS#12 round trips, validate package IDs, reject unsafe archives, and simulate SDB framing, flow control, upload timestamps and TV inventory parsing. Jest tests cover semantic versions, fixed-version Jellyfin builds, package variants and the connection-to-update-screen flow.

Read-only live checks passed on a Samsung QN55QNX1DAGXZD running Tizen 9.0: repeated SDB connections, capabilities, DUID and installed-app/version queries. These confirmed the TV closes services with CLSE remote ID 0; the client now accepts that valid close while still checking IDs on data frames. The TV reported 22 installed apps, including Jellyfin 0.1.0 and TizenBrew 2.0.5. HTTP discovery also works on TVs that expose the Samsung remote API, with SDB fallback when that API is unavailable. Samsung certificate provisioning and Jellyfin settings retention during installation still need authenticated, end-to-end testing. The current SDB client supports the Developer Mode connection used by the CLI; it reports an explicit setup error if a TV requests SDB AUTH. Broad networks are scanned within the phone's /24 and at most 512 addresses; manual IP entry works across routed networks when reachable.

## Catalog sources

- [Jellyfin Tizen builds](https://github.com/jeppevinkel/jellyfin-tizen-builds)
- [TizenBrew](https://github.com/reisxd/TizenBrew)
- [Moonlight for Tizen](https://github.com/brightcraft/moonlight-tizen)

See [NOTICE.md](NOTICE.md) for dependencies and public CA certificate provenance.

### Optional live diagnostics

These checks only read TV metadata and app listings. They never install or uninstall apps. The TV must authorize the computer’s IP for SDB; restore the phone’s host IP afterward.

```bash
TIZEN_SDB_TEST_HOST=192.168.15.5 ./android/gradlew -p android testDebugUnitTest --tests '*LiveTvTest.sdbQueries' --rerun
# Optional if the TV exposes port 8001:
TIZEN_HTTP_TEST_HOST=192.168.15.5 ./android/gradlew -p android testDebugUnitTest --tests '*LiveTvTest.httpDiscovery' --rerun
```
