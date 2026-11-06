package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class PersonalRankingTest {
    private fun f(word: String, count: Int = 1, explicit: Int = 1, time: Long = 0, context: Int = 1) =
        PersonalFollower(word, count, explicit, time, context)
    private fun rank(f: List<PersonalFollower>, slots: Int = 3, reserve: Int = 1,
        neural: List<String> = listOf("first", "second", "third"), legacy: List<PersonalRanking.LegacyFollower> = emptyList(),
        prefix: String = "", maxContext: Int = 1, allowed: (String) -> Boolean = { true }) =
        PersonalRanking.rank(neural, f, legacy, prefix, slots, PersonalRanking.Policy(maxContext, reserve), allowed = allowed)
    @Test fun `explicit passive legacy thresholds and untouched controls`() {
        assertEquals(listOf("first", "second", "third"), rank(listOf(f("once", explicit = 0))).map { it.word })
        val result = rank(listOf(f("choice")), legacy = listOf(PersonalRanking.LegacyFollower("old", 1)))
        assertEquals(listOf("first", "second", "choice"), result.map { it.word })
        assertEquals(PersonalRanking.Source.PERSONAL, result.last().source)
        assertEquals(PersonalRanking.Source.LEGACY, rank(emptyList(), legacy = listOf(
            PersonalRanking.LegacyFollower("old", 2))).last().source)
        assertEquals("twice", rank(listOf(f("twice", 2, 0))).last().word)
        // One use qualifies behind two or more own words; behind one word it still needs two.
        assertEquals("once", rank(listOf(f("once", 1, 0, context = 2)), maxContext = 3).last().word)
        assertEquals(listOf("first", "second", "third"), rank(listOf(f("once", 1, 0, context = 2))).map { it.word })
    }
    @Test fun `shortages reserve one at most and three slots retain neural`() {
        val choices = listOf(f("alpha", 4), f("beta", 3), f("gamma", 2))
        for (slots in 0..3) {
            val result = rank(choices, slots, 2)
            assertEquals(slots, result.size)
            assertEquals(if (slots == 3) 2 else minOf(slots, 1), result.count { it.source != PersonalRanking.Source.NEURAL })
        }
        assertEquals(listOf("first", "alpha", "beta"), rank(choices, reserve = 2).map { it.word })
        assertEquals(listOf("alpha", "beta"), rank(choices, reserve = 2, neural = emptyList()).map { it.word })
    }
    @Test fun `prefix filters normalization and context policy`() {
        val choices = listOf(f("ALPINE", 3, context = 3), f("alpha", 2), f("blocked", 9))
        assertEquals(listOf("alpha"), rank(choices, neural = emptyList(), prefix = "AL").map { it.word })
        assertEquals(listOf("alpine", "alpha"), rank(choices, reserve = 2, neural = emptyList(),
            prefix = "AL", maxContext = 3).map { it.word })
        assertTrue(rank(choices, neural = emptyList(), allowed = { false }).isEmpty())
    }
    @Test fun `conflicting candidates do not sum counts and ties are deterministic`() {
        val input = listOf(f("ZETA", 2, time = 7), f("alpha", 2, time = 7), f("recent", 2, time = 8))
        for (items in listOf(input, input.reversed())) {
            assertEquals(listOf("first", "recent", "alpha"), rank(items, reserve = 2).map { it.word })
        }
        val result = rank(listOf(f("same", 2)), reserve = 2, legacy = listOf(
            PersonalRanking.LegacyFollower("SAME", 2), PersonalRanking.LegacyFollower("strong", 3)))
        assertEquals(listOf("first", "strong", "same"), result.map { it.word })
        assertEquals(PersonalRanking.Source.PERSONAL, result.last().source)
    }
    @Test fun `already displayed personal preserves useful neural choices`() {
        assertEquals(listOf("first", "second", "third"), rank(listOf(f("SECOND"))).map { it.word })
        assertEquals(listOf("first", "third", "new"),
            rank(listOf(f("third", 9), f("new", 2)), reserve = 2).map { it.word })
        assertTrue(rank(listOf(f("SECOND"))).all { it.source == PersonalRanking.Source.NEURAL })
        assertEquals(listOf("first", "second", "third"), rank(listOf(f("third", 9), f("new", 2))).map { it.word })
    }
}
