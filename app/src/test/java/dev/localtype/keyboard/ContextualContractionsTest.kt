package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class ContextualContractionsTest {
    private val id = RecentWordCorrection.Identity(1, 2, "grammar-v1")
    private fun snapshot(text: String) = VerifiedWordTransaction.Snapshot(text, "", EditorEditState.Selection(text.length, text.length))
    private fun offer(prefix: String = "", word: String = "its", follower: String = "been") =
        ContextualContractions.propose(ContextualContractions.remember(id, snapshot("$prefix$word "), word, 100, verifiedOwn = true),
            id, snapshot("$prefix$word $follower "), follower, true, "its", 300)

    @Test fun `only been at a verified sentence start is automatic`() {
        for (prefix in listOf("", " ", "Thanks. ", "Thanks! ", "Thanks? ", "Hello\n")) {
            assertTrue(prefix, offer(prefix)!!.automatic)
        }
        assertEquals("It's", offer(word = "Its")!!.replacement)
        assertFalse(offer("I think ")!!.automatic)
        for (follower in listOf("a", "an", "not")) assertFalse(offer(follower = follower)!!.automatic)
    }

    @Test fun `possessives other real words and unusual case stay literal`() {
        for (follower in listOf("colour", "color", "own", "time", "good", "running", "being", "Been", "been-more"))
            assertNull(follower, offer(follower = follower))
        for (word in listOf("ITS", "iTs", "bits", "it's", "its'")) assertNull(offer(word = word))
    }

    @Test fun `quotes literal code and identifier boundaries supply no anchor`() {
        for (prefix in listOf("\"", "“", "'", "`", "key=", "path/", "email@", "#", "snake_", "say: ", "not"))
            assertNull(prefix, offer(prefix))
    }

    @Test fun `unverified pasted partial and reset ownership cannot repair`() {
        assertNull(ContextualContractions.remember(id, snapshot("its "), "its", 100))
        val anchor = ContextualContractions.remember(id, snapshot("its "), "its", 100, verifiedOwn = true)
        assertNull(ContextualContractions.propose(null, id, snapshot("its been "), "been", true, "its", 200))
        assertNull(ContextualContractions.propose(anchor, id, snapshot("its been "), "been", false, "its", 200))
        for (previous in listOf(null, "other"))
            assertNull(ContextualContractions.propose(anchor, id, snapshot("its been "), "been", true, previous, 200))
    }

    @Test fun `editor dictionary policy selection and time changes fail closed`() {
        val anchor = ContextualContractions.remember(id, snapshot("its "), "its", 100, verifiedOwn = true)
        for (identity in listOf(id.copy(field = 2), id.copy(dictionary = 3), id.copy(policy = "other")))
            assertNull(ContextualContractions.propose(anchor, identity, snapshot("its been "), "been", true, "its", 200))
        for (s in listOf(snapshot("its been ").copy(after = "suffix"), snapshot("its been ").copy(selection = EditorEditState.Selection(1, 1)),
            snapshot("its been ").copy(selection = EditorEditState.Selection(0, 3)), snapshot("its  been "), snapshot("its been. ")))
            assertNull(ContextualContractions.propose(anchor, id, s, "been", true, "its", 200))
        for (now in listOf(99L, 10_101L))
            assertNull(ContextualContractions.propose(anchor, id, snapshot("its been "), "been", true, "its", now))
    }

    @Test fun `a clipped start can offer but cannot establish an automatic sentence start`() {
        val s = snapshot("its ").copy(beforeComplete = false)
        val anchor = ContextualContractions.remember(id, s, "its", 100, verifiedOwn = true)
        assertFalse(ContextualContractions.propose(anchor, id, snapshot("its been "), "been", true, "its", 200)!!.automatic)
    }

    @Test fun `grammar Apply and Undo preserve following text without changing the ordinary repair deadline`() {
        val word = RecentWordCorrection.Word(id, "its", " a ", 0, "", "", 100,
            repairWindowMs = ContextualContractions.OFFER_MS, grammar = true)
        val state = RecentWordCorrection(); state.begin(word)
        assertEquals(RecentWordCorrection.RangeEdit(0, 3, "it's", 7), state.repair(id, snapshot("its a "), "it's", 2_000))
        state.applied(word, "it's", 2_000)
        state.append("nice day ", 2_100, boundary = true)
        assertEquals(RecentWordCorrection.RangeEdit(0, 4, "its", 15), state.undoEdit(id, snapshot("it's a nice day "), 2_200))
        val ordinary = RecentWordCorrection(); ordinary.begin(word.copy(repairWindowMs = RecentWordCorrection.REPAIR_MS))
        assertNull(ordinary.repair(id, snapshot("its a "), "it's", 601))
    }

    @Test fun `the automatic repair gets existing bounded Undo for its original target alone`() {
        val word = RecentWordCorrection.Word(id, "Its", " been ", 0, "", "", 100, grammar = true)
        val state = RecentWordCorrection(); state.applied(word, "It's", 100, "")
        assertEquals(RecentWordCorrection.RangeEdit(0, 4, "Its", 9), state.undoEdit(id, snapshot("It's been "), 200))
        assertNull(state.undoEdit(id, snapshot("It's been "), 5_100))
    }

    @Test fun `grammar identity preserves old log readability but rejects append`() {
        val prior = PredictionLogCodec.correctionHeader(CorrectionPolicy().version, true, true)
        val current = PredictionLogCodec.correctionHeader("${CorrectionPolicy().version}:${ContextualContractions.VERSION}", true, true)
        assertFalse(PredictionLogCodec.sameRanking(prior, current))
        val parsed = PredictionLogCodec.encodeHeader(prior).trimEnd().lines().mapNotNull(PredictionLogCodec::parseHeader).toMap()
        assertEquals(prior, parsed.filterKeys { it in prior })
    }
}
