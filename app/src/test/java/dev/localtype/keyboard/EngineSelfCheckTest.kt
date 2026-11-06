package dev.localtype.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes [EngineSelfCheck]'s report from the bundled assets to build/reports/engine-selfcheck-jvm.txt. The iOS
 * simulator writes the same report (engine/src/iosSimulatorArm64Main); the two files must be identical.
 */
class EngineSelfCheckTest {
    @Test
    fun `the engine's report on the bundled assets`() {
        val report = EngineSelfCheck.report { name -> File("src/main/assets/$name").readBytes() }
        File("build/reports").mkdirs()
        File("build/reports/engine-selfcheck-jvm.txt").writeText(report)
        println(report)
        assertTrue(report.contains("en correct teh -> the"))
        assertTrue(report.contains("es correct tambien -> también"))
    }
}
