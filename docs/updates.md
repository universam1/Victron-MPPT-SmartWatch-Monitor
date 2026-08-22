# Keeping test devices up to date

The app is not in any store, so nothing on a test device will ever refresh it on its own — a
sideloaded APK stays whatever version it was. This is how the app closes that gap itself, and why
it does it this way.

## The channel: the project's own GitHub releases

Tagging a commit already publishes both signed APKs plus `SHA256SUMS.txt`
([`.github/workflows/release.yml`](../.github/workflows/release.yml)):

```sh
git tag v1.2.3 && git push origin v1.2.3
```

That is the whole release process for testers too. Every installed app polls the Releases page of
this repository, notices the higher `versionCode`, downloads *its* APK — `-wear-` on a watch,
`-phone-` on a phone — and installs it.

## What happens on a device

| Step | Where | Notes |
|---|---|---|
| every 6 h, network available | `UpdateWorker` | reads `…/releases?per_page=10`, no token, public repo |
| newest release newer than installed? | `ReleaseCatalog.newestUpdate` | drafts and prereleases skipped |
| download + SHA-256 | `UpdateManager.stage` | into `cacheDir/updates`, one file kept |
| install | `ApkInstaller` | `PackageInstaller` session, `USER_ACTION_NOT_REQUIRED` on API 31+ |
| result | `InstallResultReceiver` | shows the system dialog when the platform insists |

The version arithmetic is duplicated on purpose in `ReleaseCatalog.versionCode` and in the release
workflow: `v1.2.3` → `10203`. If one side changes, devices stop seeing releases as newer — the
unit tests in `ReleaseCatalogTest` are what pin the two together.

## How silent is it really?

* **API 31+ (Android 12, Wear OS 3+)** — a self update signed with the same key can install
  unattended. Nobody has to tap anything; the app restarts on the new version. This is the case
  that matters, and it covers every Wear OS 3 watch (`minSdk` 33 there anyway).
* **API 29–30 phones** — the platform always asks. The worker therefore only *stages* the APK
  (download and checksum), and the install is offered the next time the app is in the foreground,
  because a confirmation dialog cannot be started from the background.
* **First time on any device** — Android asks once for "install unknown apps" for this app. Until
  that is granted the install fails; the reason shows up in the settings screen.

A staged APK survives a reboot and needs no network, so a device that was offline when the release
happened still updates the moment it comes back.

## Why not the Play Store or Firebase?

Both were considered:

* **Play Console internal testing** gives genuinely silent updates for up to 100 testers without
  any public release, and it handles the watch APK too. It costs a developer account, an upload
  key managed by Google, and an AAB pipeline.
* **Firebase App Distribution** is free but needs a tap per update and has no good story for a
  *standalone* Wear OS APK — the tester app lives on the phone.

Self-updating from the Releases page keeps the whole chain inside this repository, which is where
the signing key and the APKs already are.

## What it costs

Two permissions that have nothing to do with reading a charger, both in
[`data/src/main/AndroidManifest.xml`](../data/src/main/AndroidManifest.xml):

* `INTERNET` — the GitHub API and the download.
* `REQUEST_INSTALL_PACKAGES` — handing the APK to the platform installer.

There is deliberately no `POST_NOTIFICATIONS`: the updater never notifies, so it needs no runtime
prompt. Progress is visible where it belongs — in the settings screen of each app.

Both can be switched off per device: **Install updates automatically** on the phone, **Auto
update** on the watch. Off means the periodic worker is cancelled; the manual button still works.
The setting is *not* synced between phone and watch, on purpose — a watch on a metered connection
may want it off while the phone keeps it on.

## Security

The download is checked twice:

1. its SHA-256 against the release's `SHA256SUMS.txt`, which catches a truncated or corrupted
   transfer early and with a clear message;
2. its **signature**, by the platform. An update installs only when it is signed with the same key
   as the installed app, which is why `wear` and `mobile` share the signing block. This is the real
   boundary: a forged APK cannot pass it, checksum or not.

Consequence for developers: a **debug-signed** build (`./build.sh apk`) cannot be updated by the
release APKs, and vice versa. Uninstall first, or keep test devices on release builds.
