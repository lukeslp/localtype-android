# LocalType Android

LocalType is an English Android keyboard with local correction, word suggestions,
Chime predictions, saved text, emoji and an optional swipe experiment. Typing runs
on the phone. Logging is optional and starts off. Corrections can be undone.

This catalog release candidate is being prepared for F-Droid review. It has not
been accepted by F-Droid. First-party code, tests, build configuration and vector
resources are Apache-2.0; see `LICENSE` and `NOTICE`. Bundled data and third-party
dependencies retain separate terms.

The `full` distribution includes optional Gemini Nano through ML Kit and optional
Gemma through LiteRT-LM. ML Kit can send Google feature-usage statistics. The
`fdroid` distribution excludes ML Kit, Nano, Gemma, LiteRT-LM and their
integration code. It has no native library or Internet permission. Chime,
dictionaries, corrections, saved text, emoji and swipe remain available.
The F-Droid build leaves updates to the client that installed it. The public
snapshot builds this distribution without the private modelkit submodule.

Both distributions use `dev.localtype.keyboard` and the same existing developer
signing identity. They share storage and preference keys. No private signing
material is included here. Version 0.5.41 (62) is reserved for the catalog
candidate; full remains 0.5.40 (61) in this checkpoint. Every different APK must
use a higher version code, across both distributions. Rebuilt copies of the same
release must reproduce its exact bytes.

See `CONTRIBUTING.md` for building, `ASSET-LICENSES.json` for the file-level asset
review and `SOURCE-MANIFEST.json` for exact source hashes and disclosed edits.
`python3 scripts/audit-bundled-data.py` checks the five model/statistics files'
hashes and documented layouts. `--require-cleared` also rejects unresolved asset
rights. `python3 scripts/build-tlds.py --check` rebuilds the TLD list from the
included MPL-2.0 PSL source, preserving the exact shipped recognition set.
The five exact-path data exceptions still require F-Droid maintainer review;
they do not exempt executable code. `DATA-SOURCES.md` maps the data to its
notices, upstream sources and included generation scripts.
Existing notices remain under `app/src/main/assets/licenses/`. The vocabulary
comment edit changes its bytes but keeps every data row unchanged. Optional
external-data test defaults use a portable home-directory path. Two tests tied
to private frozen evaluation evidence are excluded. No private Git history,
research records, real typing logs, corpus text, signing keys or device fixtures
are part of this candidate.

The [Chime pipeline](https://github.com/lukeslp/chime-pipeline) is already public
under Apache-2.0, with separate word-list terms and documented reproduction limits.
The current signed app is available at
[the download page](https://dr.eamer.dev/downloads/apps/localtype/).
The independent repository **LocalType releases** is separate from main-catalog
inclusion. This source candidate is a proposal for the next release, not the exact
source payload of the current published APK.
