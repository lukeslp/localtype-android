package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionDiagnosticsTest {
    @Test fun smallSamplesKeepTheNearestRankTail() {
        val diagnostics = CorrectionDiagnostics()
        assertEquals(Triple(0L, 0L, 0L), diagnostics.keyCpuSummary())
        (1L..14L).forEach(diagnostics::recordKeyCpu)
        assertEquals(Triple(14L, 14L, 14L), diagnostics.keyCpuSummary())
        (15L..20L).forEach(diagnostics::recordKeyCpu)
        assertEquals(Triple(20L, 19L, 20L), diagnostics.keyCpuSummary())
    }

    @Test fun exportsOnlyTheBoundedRecentWindow() {
        val diagnostics = CorrectionDiagnostics()
        repeat(1024) { diagnostics.recordKeyCpu(100_000) }
        repeat(1024) { diagnostics.recordKeyCpu(-1) }
        assertEquals(Triple(2048L, 0L, 0L), diagnostics.keyCpuSummary())
    }

    @Test fun stagesStaySeparateFromKeysAndEachOther() {
        val diagnostics = CorrectionDiagnostics()
        diagnostics.recordStageCpu(CorrectionDiagnostics.Stage.RECEIPT, 50)
        diagnostics.recordStageCpu(CorrectionDiagnostics.Stage.FINALIZATION, 200)
        assertEquals(Triple(1L, 50L, 50L), diagnostics.stageCpuSummary(CorrectionDiagnostics.Stage.RECEIPT))
        assertEquals(Triple(1L, 200L, 200L), diagnostics.stageCpuSummary(CorrectionDiagnostics.Stage.FINALIZATION))
        assertEquals(Triple(0L, 0L, 0L), diagnostics.keyCpuSummary())
    }
}
