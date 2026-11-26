# R8 rules for the F-Droid catalog build's release (fdroidRelease), the only variant that is shrunk.
# The full and Google Play releases are not minified (app/build.gradle.kts). On top of
# proguard-android-optimize.txt and the rules AAPT2 writes for the manifest's activities and services.

# Shrink and optimize, but keep every class and member name:
# - scripts/audit-distribution-apk.py looks for ML Kit, Firebase, Play services, modelkit and LiteRT-LM
#   classes by name, and renamed classes would let it pass without finding them;
# - a stack trace from a catalog user reads without a mapping file.
-dontobfuscate

# File names and line numbers in those stack traces.
-keepattributes SourceFile,LineNumberTable
