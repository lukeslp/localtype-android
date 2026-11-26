import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional local release signing is separate from the source build.
val keystoreProperties = Properties().apply {
    if (providers.gradleProperty("localtype.unsigned").orNull != "true") {
        rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
}

android {
    namespace = "dev.localtype.keyboard"
    compileSdk = 36
    // With the verification variant (verification/editor-fixture/verification-variant.gradle) the test APK can be
    // built against it, so a device check runs beside the released keyboard: -Plocaltype.testBuildType=verification
    (findProperty("localtype.testBuildType") as String?)?.let { testBuildType = it }

    defaultConfig {
        applicationId = "dev.localtype.keyboard"
        minSdk = 26
        // Google Play accepts new apps and updates only at API 36.
        targetSdk = 36
        versionCode = 86
        versionName = "0.5.65"
        // A device check names its own runner: -Plocaltype.runner=dev.localtype.keyboard.SpellCheckerDeviceChecks
        testInstrumentationRunner = (findProperty("localtype.runner") as String?) ?: "dev.localtype.keyboard.ToolsReflowChecks"
        // Phones only. The x86_64 copy of the model runtime's native library was 26 MB that only emulators loaded.
        // Here and not in release {}: benchmark and the verification variant copy release before that block runs.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Only full and play package ML Kit and Gemma (modelkit, LiteRT-LM); the F-Droid one has no
    // Nano/Gemma integration or network use. Bundled Chime remains. F-Droid clients own updates.
    // The two are different apps to Android: full keeps the issued identity, and the catalog build has
    // its own application ID, so no repository client can replace one with the other in either direction.
    // Both are signed with the same key. See docs/catalog-application-id-2026-11-10.md.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "INCLUDES_NANO", "true")
            buildConfigField("boolean", "INCLUDES_GEMMA", "true")
            buildConfigField("boolean", "DIRECT_UPDATES", "true")
            buildConfigField("boolean", "MODEL_LAB", "false")
        }
        create("fdroid") {
            dimension = "distribution"
            applicationId = "dev.localtype.keyboard.fdroid"
            // The catalog build shares defaultConfig's version: its own package has its own version-code line,
            // so it no longer needs a pinned name and code (0.5.42 (63) until November 20, 2026).
            buildConfigField("boolean", "INCLUDES_NANO", "false")
            buildConfigField("boolean", "INCLUDES_GEMMA", "false")
            buildConfigField("boolean", "DIRECT_UPDATES", "false")
            buildConfigField("boolean", "MODEL_LAB", "false")
        }
        // Google Play: the full app under the same application ID, with the app's own update check and its
        // download-page link turned off, because Play requires that only Play updates an app it delivers.
        // It shares full's sources and manifest (sourceSets below) and adds only its own privacy wording.
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "INCLUDES_NANO", "true")
            buildConfigField("boolean", "INCLUDES_GEMMA", "true")
            buildConfigField("boolean", "DIRECT_UPDATES", "false")
            buildConfigField("boolean", "MODEL_LAB", "false")
        }
    }

    sourceSets {
        getByName("play") {
            java.srcDir("src/full/java")
            assets.srcDir("src/full/assets")
            manifest.srcFile("src/full/AndroidManifest.xml")
        }
        getByName("testPlay") { java.srcDir("src/testFull/java") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Release speed with the debug key, so it installs over a debug build for on-device timing.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        release {
            // No shrinking for full and play: the model runtime loads native code by reflection. The catalog build
            // (fdroid) has no model runtime, and androidComponents below turns R8 on for its release alone.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release candidates are built from their final prospective public Git commit.
            vcsInfo.include = true
            if (keystoreProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    androidResources {
        noCompress += "txt"
        noCompress += "bin"
    }

    buildFeatures {
        // BuildConfig carries the version name and code for the typing log header.
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    // Java 17 bytecode from whatever JDK runs Gradle (17 or newer): no toolchain, so no second JDK has to be
    // installed, and F-Droid's build server can use its default JDK 21 and still reproduce the APK.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // minSdk is 26, where a newer API called without a version check crashes, so this fails the
        // release build. Lint 8.8 doesn't see such calls inside apply {} blocks; check those by hand.
        fatal += "NewApi"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// R8 for fdroidRelease only (docs/fdroid-r8-trial-2026-11-20.md). The DSL sets minification per build type, so the
// variant API sets it for this one variant; full, play and every other build type stay unshrunk.
androidComponents {
    beforeVariants(selector().withFlavor("distribution" to "fdroid").withBuildType("release")) { variant ->
        variant.isMinifyEnabled = true
    }
}

dependencyLocking {
    lockAllConfigurations()
}

// A real typing log for the replay harness: ./gradlew test -Dlocaltype.log=/path/to/log.tsv [-Dlocaltype.eval=odd|even],
// or LOCALTYPE_LOG=/path/to/log.tsv. The log's path and content are task inputs, so a different log, or the same
// path re-exported, re-runs the tests instead of reporting UP-TO-DATE.
val localtypeLog: String? = (System.getProperty("localtype.log")
    ?: providers.environmentVariable("LOCALTYPE_LOG").orNull)?.trim()?.takeIf { it.isNotEmpty() }
// A corpus split for the next-word scorer (CorpusEvaluationTest): -Dlocaltype.corpus=<model-data>/corpus
// [-Dlocaltype.corpusSplit=dev|test] [-Dlocaltype.corpusLines=N], or LOCALTYPE_CORPUS. Only the split's text and sources
// files are task inputs (train.txt is 785 MB and never read), so a rebuilt corpus re-runs the tests.
val localtypeCorpus: String? = (System.getProperty("localtype.corpus")
    ?: providers.environmentVariable("LOCALTYPE_CORPUS").orNull)?.trim()?.takeIf { it.isNotEmpty() }
val localtypeCorpusSplit: String = System.getProperty("localtype.corpusSplit")?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"
tasks.withType<Test>().configureEach {
    localtypeLog?.let { log ->
        systemProperty("localtype.log", log)
        inputs.file(log).withPropertyName("localtypeLog").withPathSensitivity(PathSensitivity.ABSOLUTE)
    }
    System.getProperty("localtype.eval")?.let { systemProperty("localtype.eval", it) }
    // -Dlocaltype.mismatchExamples=N lists that many replay disagreements in the report (10 by default).
    System.getProperty("localtype.mismatchExamples")?.let { systemProperty("localtype.mismatchExamples", it) }
    localtypeCorpus?.let { dir ->
        systemProperty("localtype.corpus", dir)
        systemProperty("localtype.corpusSplit", localtypeCorpusSplit)
        listOf("txt", "sources.txt").map { file("$dir/$localtypeCorpusSplit.$it") }.filter { it.isFile }.forEach {
            inputs.file(it).withPropertyName("localtypeCorpus." + it.name).withPathSensitivity(PathSensitivity.ABSOLUTE)
        }
    }
    System.getProperty("localtype.corpusLines")?.let { systemProperty("localtype.corpusLines", it) }
    // What a dev run of the corpus scorer covers, each a comma-separated list of names and each optional:
    // -Dlocaltype.corpusColumns=mobiletext,nus,taskmaster (source labels, instead of nus, taskmaster and the pooled sample),
    // -Dlocaltype.corpusRankers=device-cold,prior-w20,chime-shipped (instead of the whole dev list) and
    // -Dlocaltype.corpusMissBins=chime-shipped (sort that ranker's next-word misses by kind).
    listOf("corpusColumns", "corpusRankers", "corpusMissBins").forEach { name ->
        System.getProperty("localtype.$name")?.trim()?.takeIf { it.isNotEmpty() }?.let { systemProperty("localtype.$name", it) }
    }
    // A Spanish candidate build for CorpusSpanishEvaluationTest: -Dlocaltype.spanishBuild=<model-data>/languages/es/<build>
    // [-Dlocaltype.spanishSplit=dev|test] [-Dlocaltype.spanishLines=N]. Its split files and prior are task inputs.
    System.getProperty("localtype.spanishBuild")?.trim()?.takeIf { it.isNotEmpty() }?.let { build ->
        val split = System.getProperty("localtype.spanishSplit")?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"
        systemProperty("localtype.spanishBuild", build)
        systemProperty("localtype.spanishSplit", split)
        // Only dev's files are fingerprinted: the test split is read by the guarded test alone, never by Gradle beforehand.
        val tracked = (if (split == "dev") listOf("txt", "sources.txt").map { file("$build/corpus/dev.$it") } else emptyList()) +
            file("$build/corpus_prior_es.bin")
        tracked.filter { it.isFile }.forEach {
            inputs.file(it).withPropertyName("localtypeSpanish." + it.name).withPathSensitivity(PathSensitivity.ABSOLUTE)
        }
    }
    System.getProperty("localtype.spanishLines")?.let { systemProperty("localtype.spanishLines", it) }
    // A Portuguese candidate build for CorpusPortugueseEvaluationTest, the same way: only dev's files are fingerprinted.
    System.getProperty("localtype.portugueseBuild")?.trim()?.takeIf { it.isNotEmpty() }?.let { build ->
        val split = System.getProperty("localtype.portugueseSplit")?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"
        systemProperty("localtype.portugueseBuild", build)
        systemProperty("localtype.portugueseSplit", split)
        val tracked = (if (split == "dev") listOf("txt", "sources.txt").map { file("$build/corpus/dev.$it") } else emptyList()) +
            file("$build/corpus_prior_pt.bin")
        tracked.filter { it.isFile }.forEach {
            inputs.file(it).withPropertyName("localtypePortuguese." + it.name).withPathSensitivity(PathSensitivity.ABSOLUTE)
        }
    }
    System.getProperty("localtype.portugueseLines")?.let { systemProperty("localtype.portugueseLines", it) }
    // A cifg.py eval file for the same split (-Dlocaltype.cifgEval=<run>/eval.dev.best.json): the corpus test then
    // asserts the NUS and Taskmaster counts it shares with that file.
    // More CIFG runs for the dev split, by run name (-Dlocaltype.cifgRuns=abl-1m-x4,abl-4m-x4): each run's
    // export/cifg_int8.bin is a task input, so a re-exported run re-scores.
    System.getProperty("localtype.cifgRuns")?.trim()?.takeIf { it.isNotEmpty() }?.let { runs ->
        systemProperty("localtype.cifgRuns", runs)
        val data = System.getenv("LOCALTYPE_MODEL_DATA") ?: (System.getProperty("user.home") + "/.local/share/localtype-model")
        runs.split(',').map { file("$data/runs/${it.trim()}/export/cifg_int8.bin") }.filter { it.isFile }.forEach {
            inputs.file(it).withPropertyName("localtypeCifgRun." + it.parentFile.parentFile.name).withPathSensitivity(PathSensitivity.ABSOLUTE)
        }
    }
    System.getProperty("localtype.cifgEval")?.trim()?.takeIf { it.isNotEmpty() }?.let { eval ->
        systemProperty("localtype.cifgEval", eval)
        file(eval).takeIf { it.isFile }?.let {
            inputs.file(it).withPropertyName("localtypeCifgEval").withPathSensitivity(PathSensitivity.ABSOLUTE)
        }
    }
}

dependencies {
    implementation(project(":engine"))
    if (findProject(":modelkit") != null) {
        add("fullImplementation", project(":modelkit"))
        add("playImplementation", project(":modelkit"))
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.core:core:1.13.1")
    // The emoji panel's grid: recycled cells and screen-reader scrolling for about 1,900 emoji.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // The style a password manager's inline suggestions are drawn with (Android 11+); the chips themselves are Android's.
    implementation("androidx.autofill:autofill:1.3.0")

    // On-device language models, in the full distribution only: Gemini Nano through AICore, and Gemma (LiteRT-LM)
    // through :modelkit. The F-Droid distribution carries neither.
    add("fullImplementation", "com.google.mlkit:genai-prompt:1.0.0-beta4")
    add("playImplementation", "com.google.mlkit:genai-prompt:1.0.0-beta4")

    testImplementation("junit:junit:4.13.2")
}
