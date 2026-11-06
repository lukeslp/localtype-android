package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class PersonalContextTraceTest {
    private fun append(t: PersonalContextTrace, id: Long, word: String, end: Boolean = false) =
        t.appendVerifiedOwnCompletion(id, word, PersonalContextTrace.OwnOrigin.FULLY_TYPED, true, end)!!

    @Test fun `same spelling different completions and duplicate callbacks`() {
        val t = PersonalContextTrace()
        val first = append(t, 1, "Echo")
        val second = append(t, 2, "echo")
        assertEquals(listOf("echo"), second.contextBefore)
        assertEquals(emptyList<String>(), first.contextBefore)
        assertNull(t.appendVerifiedOwnCompletion(2, "echo", PersonalContextTrace.OwnOrigin.EXPLICIT_KEEP, true))
        assertEquals(listOf("echo", "echo"), t.snapshot())
    }
    @Test fun `bounded immutable snapshots and boundaries`() {
        val t = PersonalContextTrace()
        (1L..4L).forEach { append(t, it, "word") }
        val saved = t.snapshot()
        assertEquals(3, saved.size)
        assertThrows(UnsupportedOperationException::class.java) { (saved as MutableList).clear() }
        val end = append(t, 5, "done", true)
        assertEquals(3, end.contextBefore.size)
        assertTrue(t.snapshot().isEmpty())
        append(t, 6, "next")
        t.sentenceBoundary()
        assertTrue(t.snapshot().isEmpty())
        assertEquals(3, saved.size)
    }
    @Test fun `correction requires latest receipt and undo preserves later words`() {
        val t = PersonalContextTrace()
        val a = append(t, 1, "first")
        val b = append(t, 2, "second")
        assertFalse(t.replaceMostRecentAfterVerifiedCorrection(a, "wrong"))
        assertTrue(t.replaceMostRecentAfterVerifiedCorrection(b, "REVISED"))
        assertEquals(listOf("first", "revised"), t.snapshot())
        assertTrue(t.removeAfterVerifiedUndo(a))
        assertEquals(listOf("revised"), t.snapshot())
        assertFalse(t.removeAfterVerifiedUndo(a))
        assertFalse(PersonalContextTrace().removeAfterVerifiedUndo(b))
        assertTrue(t.removeAfterVerifiedUndo(b))
    }
    @Test fun `unsafe edits reset ownership and protected fields retain nothing`() {
        for (reason in PersonalContextTrace.ResetReason.values()) {
            val t = PersonalContextTrace()
            val receipt = append(t, 1, "owned")
            t.reset(reason)
            assertTrue(t.snapshot().isEmpty())
            assertFalse(t.removeAfterVerifiedUndo(receipt))
            assertNull(t.appendVerifiedOwnCompletion(1, "owned", PersonalContextTrace.OwnOrigin.FULLY_TYPED, true))
            assertTrue(append(t, 2, "fresh").contextBefore.isEmpty())
        }
        val t = PersonalContextTrace()
        append(t, 1, "owned")
        assertNull(t.appendVerifiedOwnCompletion(2, "secret", PersonalContextTrace.OwnOrigin.SUGGESTION_ACCEPT, false))
        assertTrue(t.snapshot().isEmpty())
        assertNull(t.appendVerifiedOwnCompletion(2, "secret", PersonalContextTrace.OwnOrigin.FULLY_TYPED, true))
        append(t, 3, "fresh")
        assertNull(t.appendVerifiedOwnCompletion(4, "pasted words", PersonalContextTrace.OwnOrigin.FULLY_TYPED, true))
        assertTrue(t.snapshot().isEmpty())
    }
    @Test fun `all permitted origins capture context and evicted receipts cannot edit later words`() {
        val t = PersonalContextTrace()
        val old = append(t, 1, "initial")
        PersonalContextTrace.OwnOrigin.values().forEachIndexed { index, origin ->
            val receipt = t.appendVerifiedOwnCompletion(index + 2L, "chosen", origin, true)!!
            assertEquals(index + 1, receipt.contextBefore.size)
        }
        assertFalse(t.removeAfterVerifiedUndo(old))
        assertFalse(t.replaceMostRecentAfterVerifiedCorrection(old, "changed"))
        assertEquals(listOf("chosen", "chosen", "chosen"), t.snapshot())
        val latest = append(t, 5, "latest")
        assertFalse(t.replaceMostRecentAfterVerifiedCorrection(latest, "unverified phrase"))
        assertTrue(t.snapshot().isEmpty())
    }

}
