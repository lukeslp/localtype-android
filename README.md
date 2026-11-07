# LocalType Android

LocalType is an English Android keyboard. It corrects typos on the phone,
suggests words, predicts with Chime, and has saved text, emoji and an optional
swipe experiment. Typing runs on the phone, every correction can be undone, and
the typing log is optional and starts off.

LocalType isn't in F-Droid's main catalog yet. Signed builds of this source are
on the [releases](https://github.com/lukeslp/localtype-android/releases) page.
The independent F-Droid repository **LocalType releases** is separate from the
main catalog.

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

Both use the package `dev.localtype.keyboard`, my existing signing key, and the
same storage and preference keys. Installing the `fdroid` build over the full
one keeps your data and drops the model features. Every new APK, from either
distribution, gets a higher version code than the last. No signing
keys are in this repository.

## Licenses and data

My code, tests, build configuration and vector resources are Apache-2.0; see
`LICENSE` and `NOTICE`. Bundled data and third-party dependencies keep their own
terms. `DATA-SOURCES.md` maps each data file to its notice, upstream source and
generation scripts, and `ASSET-LICENSES.json` records each file's hash and
license. The notices ship under `app/src/main/assets/licenses/`. The
[Chime pipeline](https://github.com/lukeslp/chime-pipeline) is public under
Apache-2.0, with separate word-list terms and documented reproduction limits.

## Where this source comes from

This repository began as an export of my private source. `SOURCE-MANIFEST.json`
records that export's file hashes and provenance; later changes are in this
repository's Git history. It contains no private Git history, research records,
real typing logs, corpus text, signing keys or device fixtures. `CONTRIBUTING.md`
covers building, reproducing a release and checking the bundled data.
