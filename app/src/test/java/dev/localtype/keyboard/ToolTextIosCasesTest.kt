package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android's answers for the change-case inputs that `ios/checks/tools/main.swift` holds the iPhone's hand copy
 * (`ToolText.swift`) to beyond those of [ToolsHandOffTest]. Change the two together.
 */
class ToolTextIosCasesTest {
    @Test
    fun `lowercase reads the letters around each one`() {
        assertEquals("οδος", ToolText.inCase("ΟΔΟΣ", ToolText.Case.LOWER))
        assertEquals("οδος σοφος", ToolText.inCase("ΟΔΟΣ ΣΟΦΟΣ", ToolText.Case.LOWER))
        assertEquals("i̇", ToolText.inCase("İ", ToolText.Case.LOWER))
    }

    @Test
    fun `uppercase may make one letter two`() {
        assertEquals("STRASSE", ToolText.inCase("Straße", ToolText.Case.UPPER))
    }
}
