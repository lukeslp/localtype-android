# Build and review

Use JDK 21 to run Gradle 8.14.3, an installed JDK 17 Kotlin toolchain and Android
SDK platform 35. Set `ANDROID_HOME` to the SDK. Gradle downloads build dependencies
from Google Maven, Maven Central and the Gradle plugin portal. Artifact checksums,
the wrapper distribution checksum and resolved app dependencies are checked in.
Building downloads no language model or training corpus.

```sh
./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug :app:lintFdroidDebug
./gradlew -Plocaltype.unsigned=true :app:assembleFdroidRelease
```

The unsigned catalog APK is under `app/build/outputs/apk/fdroid/release/`.

For exact release reproduction from 0.5.42 onward, build a clean Git checkout
at the release tag or full commit. Keep `.git` available. AGP records that
commit in `META-INF/version-control-info.textproto`; an exported source archive
has no Git revision and produces different metadata. Source archives remain
useful for inspection, but are not the exact release build environment.

```sh
git clone https://github.com/lukeslp/localtype-android.git
cd localtype-android
CATALOG_REF=fdroid-v0.5.42
git checkout --detach "$CATALOG_REF"
./gradlew -Plocaltype.unsigned=true :app:assembleFdroidRelease
```

The prospective release commit is frozen before private builds and tests. A
later approved publication must push that same commit and artifact. Any source
change requires another build and comparison before publication.

The full distribution also requires its separately maintained modelkit module;
that private module is outside this source snapshot. No release keystore is
required for the catalog build. Do not use
a newly created signing key to replace an existing installed copy: Android
requires the established developer signing identity for an in-place update.

Unit tests use `app/` as their working directory and read bundled assets. Optional
model/corpus tests require separately supplied, appropriately licensed data via
`LOCALTYPE_MODEL_DATA` or their documented test properties. The public pipeline
documents those inputs. Do not change or score a held evaluation split merely to
build the app. Unit tests do not establish touch, TalkBack or physical acceptance.

The F-Droid recipe remains disabled until its exact Git-checkout build
reproduces the approved developer-signed APK and the public source/binary pins
are available. The catalog build excludes the optional native runtime.
Local builds alone do not establish publication or F-Droid acceptance.

The draft recipe lists five bundled model/data `.bin` files as exact-path
exceptions after checking their hashes, formats and notices with
`scripts/audit-bundled-data.py`. Their
treatment still needs maintainer review. The recipe's prebuild also requires
all asset rights to be cleared. The TLD list is rebuilt from included MPL-2.0
source with unchanged membership. No native library or directory is ignored.
This format check does not require retraining.
