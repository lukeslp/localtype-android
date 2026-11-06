package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedPairBufferTest {
    @Test
    fun `starts empty until the worker publishes`() {
        val buffer = LearnedPairBuffer { }

        assertFalse(buffer.ready)
        assertTrue(buffer.entries().isEmpty())
        assertEquals(listOf(Triple("good", "work", 7)), buffer.publish(listOf(Triple("good", "work", 7))))
        assertTrue(buffer.ready)
        assertEquals(listOf(Triple("good", "work", 7)), buffer.entries())
    }

    @Test
    fun `an increment is visible before its queued append runs`() {
        val queued = ArrayList<LearnedPairBuffer.Mutation>()
        val buffer = LearnedPairBuffer(queued::add)

        buffer.record("good", "work")
        buffer.record("good", "work")

        assertEquals(listOf(Triple("good", "work", 2)), buffer.entries())
        assertEquals(listOf(
            LearnedPairBuffer.Mutation.Increment("good", "work"),
            LearnedPairBuffer.Mutation.Increment("good", "work"),
        ), queued)
    }

    @Test
    fun `publication merges pending increments without including them in the baseline`() {
        val queued = ArrayList<LearnedPairBuffer.Mutation>()
        val buffer = LearnedPairBuffer(queued::add)
        buffer.record("good", "work")
        buffer.record("new", "word")
        val saved = listOf(Triple("good", "work", 7), Triple("hello", "there", 3))

        val baseline = buffer.publish(saved)

        assertEquals(saved.toSet(), baseline.toSet())
        assertEquals(setOf(Triple("good", "work", 8), Triple("hello", "there", 3), Triple("new", "word", 1)), buffer.entries().toSet())
        assertEquals("publication must not append pending increments twice", 2, queued.size)
    }

    @Test
    fun `publication takes ownership of worker prepared states`() {
        val buffer = LearnedPairBuffer { }
        buffer.remove("hello", "there")
        buffer.record("good", "work")
        val saved = listOf(Triple("good", "work", 7), Triple("hello", "there", 3))
        val loaded = LearnedPairState(saved)
        val baseline = LearnedPairState(saved)

        val imported = buffer.publish(loaded, baseline)

        assertEquals(listOf(Triple("good", "work", 7)), imported)
        assertEquals(imported, baseline.entries())
        assertEquals(listOf(Triple("good", "work", 8)), loaded.entries())
        assertEquals(loaded.entries(), buffer.entries())
        assertTrue(buffer.ready)
    }

    @Test
    fun `published state and the worker applying the same queue agree`() {
        val queued = ArrayList<LearnedPairBuffer.Mutation>()
        val buffer = LearnedPairBuffer(queued::add)
        buffer.record("good", "work")
        buffer.remove("hello", "there")
        buffer.record("hello", "world")
        val saved = listOf(Triple("good", "work", 7), Triple("hello", "there", 3))
        val worker = LearnedPairState(saved)
        queued.forEach { mutation ->
            when (mutation) {
                is LearnedPairBuffer.Mutation.Increment -> worker.record(mutation.previous, mutation.next)
                is LearnedPairBuffer.Mutation.Remove -> worker.remove(mutation.previous, mutation.next)
                LearnedPairBuffer.Mutation.Clear -> worker.clear()
            }
        }

        buffer.publish(saved)

        assertEquals(worker.entries().toSet(), buffer.entries().toSet())
    }

    @Test
    fun `a pending clear discards saved pairs and earlier increments`() {
        val buffer = LearnedPairBuffer { }
        buffer.record("good", "work")
        buffer.clear()
        buffer.record("new", "word")

        val baseline = buffer.publish(listOf(Triple("good", "work", 70), Triple("hello", "there", 3)))

        assertTrue(baseline.isEmpty())
        assertEquals(listOf(Triple("new", "word", 1)), buffer.entries())
    }

    @Test
    fun `an increment after a pending remove does not restore the forgotten baseline`() {
        val buffer = LearnedPairBuffer { }
        buffer.record("good", "work")
        buffer.remove("good", "work")
        buffer.record("good", "work")

        val baseline = buffer.publish(listOf(Triple("good", "work", 70), Triple("hello", "there", 3)))

        assertEquals(listOf(Triple("hello", "there", 3)), baseline)
        assertEquals(setOf(Triple("good", "work", 1), Triple("hello", "there", 3)), buffer.entries().toSet())
    }

    @Test
    fun `remove before loading forgets a pair absent from the transient state`() {
        val buffer = LearnedPairBuffer { }
        buffer.remove("good", "work")

        assertTrue(buffer.publish(listOf(Triple("good", "work", 70))).isEmpty())
        assertTrue(buffer.entries().isEmpty())
    }

    @Test
    fun `ready mutations change the shared view synchronously and keep write order`() {
        val queued = ArrayList<LearnedPairBuffer.Mutation>()
        val buffer = LearnedPairBuffer(queued::add)
        buffer.publish(listOf(Triple("good", "work", 7)))
        buffer.remove("good", "work")
        assertTrue(buffer.entries().isEmpty())
        buffer.record("hello", "there")
        assertEquals(listOf(Triple("hello", "there", 1)), buffer.entries())
        buffer.clear()
        assertTrue(buffer.entries().isEmpty())

        assertEquals(listOf(
            LearnedPairBuffer.Mutation.Remove("good", "work"),
            LearnedPairBuffer.Mutation.Increment("hello", "there"),
            LearnedPairBuffer.Mutation.Clear,
        ), queued)
    }

    @Test
    fun `stored counts saturate at one hundred and model rebuild still caps replay at twelve`() {
        val buffer = LearnedPairBuffer { }
        buffer.publish(emptyList())
        repeat(150) { buffer.record("good", "work") }

        assertEquals(listOf(Triple("good", "work", 100)), buffer.entries())
        val rebuilt = model(buffer)
        assertEquals(12, rebuilt.pairCount("good", "work"))
        assertEquals(mapOf("work" to 12), rebuilt.tables().unigrams)
    }

    @Test
    fun `baseline import preserves live increments above the replay cap without double learning`() {
        val queued = ArrayList<LearnedPairBuffer.Mutation>()
        val buffer = LearnedPairBuffer(queued::add)
        val live = model(buffer)
        repeat(20) { live.accept("good", "work") }

        val baseline = buffer.publish(listOf(Triple("good", "work", 80)))
        live.restoreLearned(baseline)

        assertEquals(listOf(Triple("good", "work", 100)), buffer.entries())
        assertEquals("twenty live increments plus twelve restored", 32, live.pairCount("good", "work"))
        assertEquals(mapOf("work" to 32), live.tables().unigrams)
        assertEquals("baseline import only changes the model", 20, queued.size)
        assertEquals(12, model(buffer).pairCount("good", "work"))
    }

    @Test
    fun `an unpersisted model mutation never enters the journal buffer`() {
        val queued = ArrayList<LearnedPairBuffer.Mutation>()
        val buffer = LearnedPairBuffer(queued::add)
        buffer.publish(emptyList())
        val live = model(buffer)

        live.accept("private", "word", persist = false)

        assertTrue(buffer.entries().isEmpty())
        assertTrue(queued.isEmpty())
        assertEquals(0, model(buffer).pairCount("private", "word"))
    }

    private fun model(store: AdaptiveNgramModel.LearningStore) = AdaptiveNgramModel(
        learningStore = store, seedPhrases = emptyList(), sentenceStarters = emptyList(),
    )
}
