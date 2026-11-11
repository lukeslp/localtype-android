# LocalType Android

[![Android download](https://img.shields.io/badge/Android-direct_download-34A853?style=flat-square)](https://dr.eamer.dev/downloads/apps/localtype/)
[![F-Droid repository](https://img.shields.io/badge/F--Droid-LocalType_releases_repo-1976D2?style=flat-square)](https://dr.eamer.dev/downloads/fdroid/)
[![iPhone beta](https://img.shields.io/badge/iPhone-TestFlight_beta-blue?style=flat-square)](https://testflight.apple.com/join/cz7zh9xm)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-green?style=flat-square)](LICENSE)

LocalType is an English Android keyboard. It corrects typos on the phone,
suggests words, predicts with Chime, and has saved text, emoji and an optional
swipe experiment. Typing runs on the phone, every correction can be undone, and
the typing log is optional and starts off.

LocalType isn't in F-Droid's main catalog yet. The
[releases](https://github.com/lukeslp/localtype-android/releases) page has two
signed catalog builds, 0.5.41 and 0.5.42. The source on `main` is newer than
both, and the `fdroid` build's version in `app/build.gradle.kts` changes only
when I cut a catalog release. The independent F-Droid repository **LocalType
releases** is separate from the main catalog.

## Two builds

This repository builds the `fdroid` distribution. It leaves out ML Kit, Gemini
Nano, Gemma, LiteRT-LM and the code that connects to them, and it builds without
the private `modelkit` submodule. It has no native library and no Internet
permission, and it leaves updates to the app that installed it. Chime, the
dictionaries, corrections, saved text, emoji and swipe all remain. It asks for
the microphone only when you first use voice input, and for contacts only when
you choose Allow in the Contacts tool.

The `full` distribution on my
[download page](https://dr.eamer.dev/downloads/apps/localtype/) adds optional
Gemini Nano through ML Kit and optional Gemma through LiteRT-LM. ML Kit can send
Google feature-usage statistics.

Android treats the two as different apps. The `full` build's package is
`dev.localtype.keyboard` and the `fdroid` build's is
`dev.localtype.keyboard.fdroid`, so neither installs over the other and an
F-Droid client can't replace one with the other. You can have both installed.
Learned words and settings don't carry from one to the other, and each has its
own version codes. Both are signed with my existing key. No signing keys are in
this repository.

The two builds on the releases page were made before this split, so their
package is `dev.localtype.keyboard`.

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
