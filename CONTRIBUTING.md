# Build and review

Gradle 8.14.3 runs on JDK 21 or 17, the modules compile to Java 17 bytecode with
no JDK toolchain, and the app builds against Android SDK platform 35. Set
`ANDROID_HOME` to the SDK. Gradle downloads dependencies from Google Maven, Maven
Central and the Gradle plugin portal. The artifact checksums, the wrapper
distribution checksum and the app's resolved dependencies are checked in.
Building downloads no language model or training corpus.

```sh
./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug :app:lintFdroidDebug
./gradlew -Plocaltype.unsigned=true :app:assembleFdroidRelease
```

The unsigned catalog APK is under `app/build/outputs/apk/fdroid/release/`. The
catalog build needs no keystore and leaves out the optional native runtime. The
full distribution also needs the separately maintained `modelkit` module, which
is private and not in this repository. Don't sign a build with a new key to
replace an installed copy: Android updates an app in place only with its
original signing key.

## Reproducing a release

From 0.5.42 on, build a clean Git checkout of the release tag and keep `.git`.
AGP records the commit in `META-INF/version-control-info.textproto`, so a source
archive without Git metadata produces a different APK. Archives are still fine
for reading the source.

```sh
git clone https://github.com/lukeslp/localtype-android.git
cd localtype-android
CATALOG_REF=fdroid-v0.5.42
git checkout --detach "$CATALOG_REF"
./gradlew -Plocaltype.unsigned=true :app:assembleFdroidRelease
```

0.5.41 predates this. To reproduce it, export `fdroid-v0.5.41` with
`git archive`, extract it outside any Git checkout and build there.

I build and test each release from the commit that becomes its tag, and a rebuild
of the same release must produce the same bytes. Any source change needs another
build and comparison before release. F-Droid's recipe builds the `fdroid` release
from the tag and publishes my signed APK only when its own build matches it.
Local builds alone don't show that F-Droid accepts the app.

## Bundled data

`python3 scripts/audit-bundled-data.py` checks the hashes and documented layouts
of the five model and statistics files, and `--require-cleared` also fails on
unresolved asset rights. `python3 scripts/build-tlds.py --check` rebuilds the TLD
list from the included MPL-2.0 Public Suffix List source and checks that it
recognizes exactly the shipped set. The recipe's prebuild runs both. It then
lists the five `.bin` files as exact-path exceptions to F-Droid's binary scan, a
treatment that still needs maintainer review. No native library or directory is
exempted, and the audit checks formats without retraining anything.

For this export I edited a comment in `app/src/main/assets/current_words.txt`;
every data row is unchanged. `DATA-SOURCES.md` covers where the data comes from.

## Tests

Unit tests run with `app/` as the working directory and read the bundled assets.
Optional model and corpus tests need separately supplied, appropriately licensed
data through `LOCALTYPE_MODEL_DATA` (default `~/.local/share/localtype-model`) or
their documented test properties. The public pipeline documents those inputs.
Two tests tied to private, frozen evaluation evidence are left out. Don't change
or score a held-out evaluation split just to build the app. Unit tests don't
establish touch, TalkBack or on-phone behavior.
