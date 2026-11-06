package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppendLedgerTest {
    private val editor = "app:7:body:131073:16777220"

    @Test
    fun `an append that raced the restart is sent again`() {
        val ledger = AppendLedger(window = 280)
        ledger.record(editor, "qpalzm ", "q")
        // The probe passed, then the connection retired: the next read failed and the commit vanished.
        ledger.record(editor, null, "p")
        assertEquals(listOf("p"), ledger.missing(editor, "qpalzm q"))
        assertEquals(0, ledger.size)
    }

    @Test
    fun `everything that arrived is never sent twice`() {
        val ledger = AppendLedger(window = 280)
        ledger.record(editor, "see you", " ")
        ledger.record(editor, null, "t")
        assertEquals(emptyList<String>(), ledger.missing(editor, "see you t"))
    }

    @Test
    fun `several appends after the last arrival are resent in order`() {
        val ledger = AppendLedger(window = 280)
        ledger.record(editor, "ab", "c")
        ledger.record(editor, null, "d")
        ledger.record(editor, null, " ")
        assertEquals(listOf("d", " "), ledger.missing(editor, "abc"))
    }

    @Test
    fun `a successful read starts the ledger over`() {
        val ledger = AppendLedger(window = 280)
        ledger.record(editor, "a", "b")
        ledger.record(editor, "ab", "c")
        assertEquals(1, ledger.size)
        assertEquals(listOf("c"), ledger.missing(editor, "ab"))
    }

    @Test
    fun `unknown text, another editor or no read resends nothing`() {
        val ledger = AppendLedger(window = 280)
        ledger.record(editor, "ab", "c")
        assertEquals(emptyList<String>(), ledger.missing(editor, "xyz"))
        ledger.record(editor, "ab", "c")
        assertEquals(emptyList<String>(), ledger.missing("other", "ab"))
        ledger.record(editor, "ab", "c")
        assertEquals(emptyList<String>(), ledger.missing(editor, null))
    }

    @Test
    fun `a run of one character too long for the window is ambiguous and left alone`() {
        val ledger = AppendLedger(window = 4)
        ledger.record(editor, "aaaa", "a")
        ledger.record(editor, null, "a")
        assertEquals(emptyList<String>(), ledger.missing(editor, "aaaa"))
    }

    @Test
    fun `with nothing to chain onto, a failed read uses the key's own earlier read or records nothing`() {
        val ledger = AppendLedger(window = 280)
        ledger.record(editor, null, "x")
        assertEquals(0, ledger.size)
        ledger.record(editor, null, "x", fallback = "hello ")
        assertEquals(listOf("x"), ledger.missing(editor, "hello "))
    }

    @Test
    fun `windows compare only the last characters before the cursor`() {
        val ledger = AppendLedger(window = 5)
        ledger.record(editor, "0123456789", "a")
        val entry = "56789"
        assertTrue(ledger.missing(editor, entry) == listOf("a"))
    }
}
