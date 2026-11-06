package dev.localtype.keyboard

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class KeyHapticsTest {
    @After
    fun switchBackOn() {
        KeyHaptics.enabled = true
    }

    @Test
    fun `vibration is on by default and a saved choice wins`() {
        val context = MemoryContext()
        assertTrue(KeyboardSettings.vibrateOnKeyPress(context))
        KeyboardSettings.setVibrateOnKeyPress(context, false)
        assertFalse(KeyboardSettings.vibrateOnKeyPress(context))
        KeyboardSettings.setVibrateOnKeyPress(context, true)
        assertTrue(KeyboardSettings.vibrateOnKeyPress(context))
    }

    @Test
    fun `with the switch on Android's own setting still decides`() {
        val view = FeltView()
        KeyHaptics.perform(view, HapticFeedbackConstants.KEYBOARD_TAP)
        KeyHaptics.perform(view, HapticFeedbackConstants.CLOCK_TICK)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.CLOCK_TICK), view.felt.map { it.first })
        // Only the view's own flag is passed over; the global touch feedback setting is never bypassed.
        assertTrue(view.felt.all { (_, flags) -> flags == HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING })
    }

    @Test
    fun `with the switch off nothing vibrates`() {
        val view = FeltView()
        KeyHaptics.enabled = false
        KeyHaptics.perform(view, HapticFeedbackConstants.KEYBOARD_TAP)
        KeyHaptics.perform(view, HapticFeedbackConstants.LONG_PRESS)
        KeyHaptics.perform(null, HapticFeedbackConstants.KEYBOARD_TAP)
        assertEquals(emptyList<Pair<Int, Int>>(), view.felt)
    }

    @Test
    fun `a handled hold vibrates through the switch instead of on its own`() {
        val view = FeltView()
        var holds = 0
        KeyHaptics.onHold(view) { holds++; true }
        assertFalse(view.ownFeedback)

        assertTrue(view.hold())
        assertEquals(listOf(HapticFeedbackConstants.LONG_PRESS), view.felt.map { it.first })

        KeyHaptics.enabled = false
        assertTrue(view.hold())
        assertEquals(2, holds)
        assertEquals(1, view.felt.size)
    }

    @Test
    fun `a hold the listener declines does not vibrate`() {
        val view = FeltView()
        KeyHaptics.onHold(view) { false }
        assertFalse(view.hold())
        assertEquals(emptyList<Pair<Int, Int>>(), view.felt)
    }

    /** Records each vibration asked of it, and the hold listener it is given, instead of reaching Android. */
    private class FeltView : View(null) {
        val felt = ArrayList<Pair<Int, Int>>()
        var ownFeedback = true
        private var listener: OnLongClickListener? = null

        override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
            felt += feedbackConstant to flags
            return true
        }

        override fun setHapticFeedbackEnabled(hapticFeedbackEnabled: Boolean) {
            ownFeedback = hapticFeedbackEnabled
        }

        override fun setOnLongClickListener(l: OnLongClickListener?) {
            listener = l
        }

        fun hold(): Boolean = listener?.onLongClick(this) ?: false
    }

    /** Only the preference operations KeyboardSettings uses for a switch; edits apply together. */
    private class MemoryContext : ContextWrapper(null) {
        private val values = HashMap<String, Boolean>()
        private val preferences: SharedPreferences = proxy(SharedPreferences::class.java) { name, args ->
            when (name) {
                "getBoolean" -> values[args[0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference call: $name")
            }
        }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences

        private fun editor(): SharedPreferences.Editor {
            val pending = HashMap<String, Boolean>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy(SharedPreferences.Editor::class.java) { name, args ->
                when (name) {
                    "putBoolean" -> editor.also { pending[args[0] as String] = args[1] as Boolean }
                    "apply" -> { values += pending; null }
                    else -> error("Unexpected editor call: $name")
                }
            }
            return editor
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
                when (method.name) {
                    "toString" -> "MemoryContext.${type.simpleName}"
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.get(0)
                    else -> invoke(method.name, args ?: emptyArray())
                }
            } as T
    }
}
