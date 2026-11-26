# LocalType Android

[![Android download](https://img.shields.io/badge/Android-direct_download-34A853?style=flat-square)](https://dr.eamer.dev/downloads/apps/localtype/)
[![F-Droid repository](https://img.shields.io/badge/F--Droid-LocalType_releases_repo-1976D2?style=flat-square)](https://dr.eamer.dev/downloads/fdroid/)
[![iPhone beta](https://img.shields.io/badge/iPhone-TestFlight_beta-blue?style=flat-square)](https://testflight.apple.com/join/cz7zh9xm)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-green?style=flat-square)](LICENSE)

LocalType is an Android keyboard. It corrects typos on the phone, suggests
words, predicts with Chime, and has saved text, emoji, a row of tools and an
optional swipe experiment. English is the main language; twelve others, from
Spanish to Navajo, are early experiments that start off. Typing runs on the
phone, every correction can be undone, and the typing log is optional and
starts off.

LocalType isn't in F-Droid's main catalog yet. Each catalog build is a tag
named `fdroid-v<version>`, with its signed APK on the
[releases](https://github.com/lukeslp/localtype-android/releases) page. The
independent F-Droid repository **LocalType releases** is separate from the main
catalog.

## Two builds

This repository builds the `fdroid` distribution. It leaves out ML Kit, Gemini
Nano, Gemma, LiteRT-LM and the code that connects to them, and it builds without
the private `modelkit` submodule. It has no native library and no Internet
permission, and it leaves updates to the app that installed it. Chime, the
dictionaries, corrections, saved text, emoji and swipe all remain. It asks for
the microphone only when you first use voice input, and for contacts only when
you tap Allow contacts in the Contacts tool.

The `full` distribution on my
[download page](https://dr.eamer.dev/downloads/apps/localtype/) adds optional
Gemini Nano through ML Kit and optional Gemma through LiteRT-LM. ML Kit can send
Google feature-usage statistics. The build file also has a `play` flavor: the
`full` build without its own update check. Like `full`, it needs the private
`modelkit` module and isn't built from this repository.

Android treats the two as different apps. The `full` build's package is
`dev.localtype.keyboard` and the `fdroid` build's is
`dev.localtype.keyboard.fdroid`, so neither installs over the other and an
F-Droid client can't replace one with the other. You can have both installed.
Learned words and settings don't carry from one to the other. Both builds take
their version from `app/build.gradle.kts`, and each has its own version codes.
Both are signed with my existing key. No signing keys are in this repository.

The 0.5.41 and 0.5.42 builds on the releases page were made before this
split, so their package is `dev.localtype.keyboard`. From 0.5.60 the catalog
build is `dev.localtype.keyboard.fdroid`.

## Verify a download

Both packages, `dev.localtype.keyboard` and `dev.localtype.keyboard.fdroid`,
are signed with the same key. Its certificate's SHA-256 is:

```
8732e175659c05916dd81fca5dd6272ff2204ccf075216fbf16a89aef6663ef5
```

To check an APK before you install it, print its certificate with `apksigner`
from the Android SDK build tools and compare the `certificate SHA-256 digest`
line with that value. Put the name of your file in place of `localtype.apk`:

```sh
apksigner verify --print-certs localtype.apk
```

A checksum shows the file arrived whole. Compare it with
[SHA256SUMS.txt](https://dr.eamer.dev/downloads/android/localtype/SHA256SUMS.txt)
for a file from the download page, or with the `SHA256SUMS` file on the
release for a file from the releases page:

```sh
shasum -a 256 localtype.apk    # macOS
sha256sum localtype.apk        # Linux
```

Each checksum list sits beside its APK, so it can't show who made the file; the
certificate does. Android also refuses an update signed with a different key,
so the check matters most the first time you install.

The F-Droid repository **LocalType releases** signs its index with a key of its
own. Confirm this fingerprint when your client adds
`https://dr.eamer.dev/downloads/fdroid/repo`:

```
496249B0E93D1B4ED15E290320AAA319DA0E68E9FC5E542EE093FC2D06C284F0
```

## Licenses and data

My code, tests, build configuration and vector resources are Apache-2.0; see
`LICENSE` and `NOTICE`. Bundled data and third-party dependencies keep their own
terms. `DATA-SOURCES.md` maps each data file to its notice, upstream source and
generation scripts, and `ASSET-LICENSES.json` records each file's hash and
license. The notices ship under `app/src/main/assets/licenses/`. The
[Chime pipeline](https://github.com/lukeslp/chime-pipeline) is public under
Apache-2.0, with separate word-list terms and documented reproduction limits.

## Where this source comes from

This repository is an export of my private source, and I refresh it from there.
`SOURCE-MANIFEST.json` records the latest export's file hashes and the private
commit it came from; each export is a commit in this repository's Git history.
It contains no private Git history, research records,
real typing logs, corpus text, signing keys or device fixtures. `CONTRIBUTING.md`
covers building, reproducing a release and checking the bundled data.
