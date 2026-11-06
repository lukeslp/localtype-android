package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSuggestionCacheTest {
    private var now = 1_000L
    private val clock = { now }

    @Test
    fun `keys back off from recent words to the last word`() {
        assertEquals(listOf("see you soon", "soon"), ModelSuggestionCache.keysFor("I will see you soon "))
        assertEquals(listOf("you"), ModelSuggestionCache.keysFor("Done. You "))
        // Sentence starts are never cached: model guesses there were random ("jumps").
        assertTrue(ModelSuggestionCache.keysFor("Done. ").isEmpty())
        assertTrue(ModelSuggestionCache.keysFor("").isEmpty())
        assertTrue(ModelSuggestionCache.keysFor("see you\n").isEmpty())
        assertTrue(ModelSuggestionCache.keysFor("call me at 5 ").isEmpty())
    }

    @Test
    fun `one model answer serves every prefix of the next word`() {
        val cache = ModelSuggestionCache(clock = clock)
        cache.put("see you ", listOf("soon", "later", "tomorrow", "then"), persist = false)

        assertEquals(listOf("soon", "later", "tomorrow", "then"), cache.lookup("see you ", ""))
        assertEquals(listOf("tomorrow", "then"), cache.lookup("see you t", "t"))
        assertEquals(listOf("tomorrow"), cache.lookup("see you tom", "tom"))
        assertTrue(cache.hasEntryFor("see you tom", "tom"))
    }

    @Test
    fun `shorter context answers when the full context is new`() {
        val cache = ModelSuggestionCache(clock = clock)
        cache.put("I will see you ", listOf("soon"), persist = false)

        assertEquals(listOf("soon"), cache.lookup("hope to see you ", ""))
        assertFalse(cache.hasEntryFor("hope to see you ", ""))
    }

    @Test
    fun `least recently used entries are evicted from memory and storage`() {
        val store = MemoryStore()
        val cache = ModelSuggestionCache(store, capacity = 2, clock = clock)
        cache.put("alpha ", listOf("one"), persist = true)
        cache.put("beta ", listOf("two"), persist = true)
        cache.lookup("alpha ", "")
        cache.put("gamma ", listOf("three"), persist = true)

        assertNull(cache.lookup("beta ", ""))
        assertEquals(listOf("one"), cache.lookup("alpha ", ""))
        assertFalse("beta" in store.data)
    }

    @Test
    fun `persisted entries reload and unpersisted ones do not`() {
        val store = MemoryStore()
        ModelSuggestionCache(store, clock = clock).apply {
            put("good ", listOf("morning"), persist = true)
            put("secret ", listOf("plans"), persist = false)
        }

        val reloaded = ModelSuggestionCache(store, clock = clock)
        assertEquals(listOf("morning"), reloaded.lookup("good ", ""))
        assertNull(reloaded.lookup("secret ", ""))
    }

    @Test
    fun `merge puts model words first without duplicates`() {
        assertEquals(listOf("soon", "later", "then"), mergeCandidates(listOf("soon", "later"), listOf("Later", "then", "now"), 3))
    }

    private class MemoryStore : ModelSuggestionCache.Store {
        val data = LinkedHashMap<String, ModelSuggestionCache.Entry>()
        override fun load() = data.toMap()
        override fun save(key: String, entry: ModelSuggestionCache.Entry) { data[key] = entry }
        override fun remove(key: String) { data.remove(key) }
        override fun clear() = data.clear()
    }

    @Test fun `an unsafe backoff answer cannot be laundered by a later safe request`() {
        val store = MemoryStore()
        val cache = ModelSuggestionCache(store, clock = clock)
        cache.put("secret hello ", listOf("inventedsecret"), persist = false)
        cache.put("say hello ", listOf("world"), persist = true)
        assertTrue(cache.lookup("hello ", "")!!.contains("inventedsecret"))
        assertEquals(listOf("world"), store.data["hello"]!!.words)
        assertEquals(listOf("world"), ModelSuggestionCache(store, clock = clock).lookup("hello ", ""))
        assertFalse(store.data.values.any { "inventedsecret" in it.words })
    }
}
