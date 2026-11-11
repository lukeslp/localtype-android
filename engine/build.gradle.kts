import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// LocalType's typing engine, shared by the Android keyboard (the JVM target, which :app depends on) and the
// iPhone keyboard (the iOS framework). No Android or Apple types here: the platforms differ only in the few
// expect/actual helpers in Platform.kt (hashes, checksums, character categories, a lock).
kotlin {
    // Java 17 bytecode without a toolchain, as in app/build.gradle.kts.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    val xcframework = XCFramework("LocalTypeEngine")
    val simulator = iosSimulatorArm64()
    listOf(iosArm64(), simulator).forEach { target ->
        target.binaries.framework {
            baseName = "LocalTypeEngine"
            isStatic = true
            xcframework.add(this)
        }
    }
    // EngineSelfCheck's report from the simulator, to compare with the Android one (EngineSelfCheckTest).
    simulator.binaries.executable("EngineSelfCheck") {
        entryPoint = "dev.localtype.keyboard.selfcheck.main"
    }
}
