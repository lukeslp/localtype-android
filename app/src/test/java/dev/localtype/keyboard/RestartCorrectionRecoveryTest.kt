package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class RestartCorrectionRecoveryTest {
    private val end = EditorEditState.Selection(6, 6)
    private val receipt = VerifiedWordTransaction.Snapshot("hello ", "", end)
    private fun resumes(
        value: VerifiedWordTransaction.Snapshot? = receipt,
        observed: VerifiedWordTransaction.Snapshot? = value,
        generation: Long = 3,
        current: Long = generation,
        expected: EditorEditState.Selection? = value?.selection,
        delimiter: String = " ",
    ) = RestartCorrectionRecovery.canResume(delimiter, generation, current, value, observed, expected)

    @Test fun `verified boundary releases only future words without restoring the old trace`() {
        val trace = WordTrace().also { it.revisit() }
        assertTrue(resumes())
        assertTrue(trace.revisited)
        assertFalse(trace.hasTouchesFor("hello"))
        trace.finish()
        "dont".forEachIndexed { index, _ -> trace.letter("dont".take(index), null) }
        assertTrue(trace.hasTouchesFor("dont"))
        assertFalse(trace.revisited)
    }
    @Test fun `uncertainty and a changed generation or caret retain suppression`() {
        assertFalse(resumes(value = null))
        assertFalse(resumes(observed = null))
        assertFalse(resumes(current = 4))
        assertFalse(resumes(expected = null))
        assertFalse(resumes(expected = EditorEditState.Selection(5, 5)))
        assertFalse(resumes(observed = receipt.copy(selection = EditorEditState.Selection(5, 5))))
        assertFalse(resumes(observed = receipt.copy(before = "world ")))
    }
    @Test fun `selection replacement and splitting a word retain suppression`() {
        assertFalse(resumes(value = receipt.copy(selection = EditorEditState.Selection(4, 6), selected = "lo")))
        for (right in listOf("world", "7", "'s", "’s", "_word", "-word", "@name", "#tag")) {
            assertFalse(right, resumes(value = receipt.copy(after = right)))
        }
        assertFalse(resumes(value = receipt.copy(before = "hello")))
        assertFalse(resumes(delimiter = "."))
        assertFalse(resumes(delimiter = "  "))
        assertTrue(resumes(value = receipt.copy(before = "hello\n"), delimiter = "\n"))
    }
    @Test fun `accepted but ignored delimiter has no verified receipt to release guard`() {
        val initial = VerifiedWordTransaction.Snapshot("hello", "", EditorEditState.Selection(5, 5))
        var verified: VerifiedWordTransaction.Snapshot? = null
        val applied = VerifiedWordTransaction.apply(WordEdits.Replacement(0, 0, " "), initial, false,
            read = { initial }, range = { _, _, _ -> false }, select = { _, _ -> false },
            commit = { true }, verified = { verified = it })
        assertNull(applied)
        assertNull(verified)
        assertFalse(resumes(value = verified))
    }
}
