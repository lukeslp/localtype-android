buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kotlin by artifact rather than plugin marker: the Android app and the multiplatform engine
        // (engine/, shared with the iPhone keyboard) take it from here at one version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "8.8.2" apply false
    id("com.android.library") version "8.8.2" apply false
}
