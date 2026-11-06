pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LocalType Keyboard"
include(":app")
include(":engine")
// modelkit (Gemma, shared with LocalLauncher) is a private submodule that only the full distribution uses. A checkout
// without it, such as the public source, still builds the F-Droid distribution.
if (file("modelkit/build.gradle.kts").exists()) include(":modelkit")
