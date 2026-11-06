package dev.localtype.keyboard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class TouchOffsetStoreTest {
    private val saved = MemoryPreferences()
    private val store = TouchOffsetStore(saved.preferences)

    private fun samples(word: String, dx: Float, dy: Float): TouchOffsets = TouchOffsets().also { offsets ->
        assertTrue(offsets.record(word, word.map { KeyGeometry.touchOn(it, dx, dy) }))
    }

    @Test
    fun `offset means and counts survive a store round trip`() {
        val offsets = samples("cat", 0.2f, 0.1f)
        assertTrue(offsets.record("cap", "cap".map { KeyGeometry.touchOn(it, -0.1f, 0.3f) }))
        store.save(offsets)

        val loaded = TouchOffsets()
        TouchOffsetStore(saved.preferences).load(loaded)
        assertEquals(offsets.entries(), loaded.entries())
    }

    @Test
    fun `saving a replacement snapshot removes stale offsets`() {
        store.save(samples("cat", 0.2f, 0.2f))
        val replacement = samples("dog", -0.1f, 0.1f)
        store.save(replacement)

        val loaded = TouchOffsets()
        store.load(loaded)
        assertEquals(replacement.entries(), loaded.entries())
        assertEquals(0, loaded.count('c'))
    }

    @Test
    fun `forgetting learned words clears saved offsets and the running keyboard's means`() {
        val offsets = samples("cat", 0.2f, 0.2f)
        store.save(offsets)
        store.clear()

        assertTrue(saved.values.isEmpty())
        // Reloading into the running keyboard's existing object clears its means as well.
        store.load(offsets)
        assertTrue(offsets.entries().isEmpty())
        assertEquals(Touch(0f, 0f), offsets.offset('c'))
    }

    @Test
    fun `malformed preferences teach nothing`() {
        saved.values.putAll(mapOf(
            "a_count" to 2, "a_dx" to 0.2f, "a_dy" to -0.1f,
            "b_count" to 0, "b_dx" to 0.2f, "b_dy" to 0.2f,
            "c_count" to -2, "c_dx" to 0.2f, "c_dy" to 0.2f,
            "d_count" to 2, "d_dx" to Float.NaN, "d_dy" to 0.2f,
            "e_count" to 2, "e_dx" to 0.2f, "e_dy" to Float.POSITIVE_INFINITY,
            "f_count" to 2, "f_dx" to 0.2f,
            "g_count" to "2", "g_dx" to 0.2f, "g_dy" to 0.2f,
            "h_count" to 2, "h_dx" to 0.2, "h_dy" to 0.2f,
            "!_count" to 2, "!_dx" to 0.2f, "!_dy" to 0.2f,
        ))
        val loaded = samples("old", 0.2f, 0.2f)
        store.load(loaded)
        assertEquals(listOf(TouchOffsets.Entry('a', 2, 0.2f, -0.1f)), loaded.entries())
    }

    @Test
    fun `each layout's offsets round trip under that layout's own keys`() {
        val spanish = LanguageProfile.SPANISH
        val offsets = TouchOffsets(spanish.geometry)
        assertTrue(offsets.record("niño", "niño".map { spanish.geometry.touchOn(it, 0.1f, -0.2f) }))
        TouchOffsetStore(saved.preferences, spanish).save(offsets)
        assertTrue(saved.values.containsKey("ñ_count"))

        val loaded = TouchOffsets(spanish.geometry)
        TouchOffsetStore(saved.preferences, spanish).load(loaded)
        assertEquals(offsets.entries(), loaded.entries())
        // English reads only its own keys; in the app each layout also has its own file.
        val english = TouchOffsets()
        store.load(english)
        assertEquals(1, english.count('o'))
        assertEquals(0, english.count('ñ'))
    }

    /** Only the preference operations the adapter uses; edits become visible together on apply. */
    private class MemoryPreferences {
        val values = linkedMapOf<String, Any>()
        val preferences: SharedPreferences = proxy(SharedPreferences::class.java) { name, args ->
            when (name) {
                "getAll" -> LinkedHashMap(values)
                "getInt" -> values[args[0] as String]?.let { it as Int } ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference call: $name")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val pending = linkedMapOf<String, Any?>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            editor = proxy(SharedPreferences.Editor::class.java) { name, args ->
                when (name) {
                    "putInt", "putFloat" -> editor.also { pending[args[0] as String] = args[1] }
                    "remove" -> editor.also { pending[args[0] as String] = null }
                    "clear" -> editor.also { clear = true }
                    "apply" -> {
                        if (clear) values.clear()
                        for ((key, value) in pending) {
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        null
                    }
                    else -> error("Unexpected editor call: $name")
                }
            }
            return editor
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
                when (method.name) {
                    "toString" -> "MemoryPreferences.${type.simpleName}"
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.get(0)
                    else -> invoke(method.name, args ?: emptyArray())
                }
            } as T
    }
}
