package dev.localtype.keyboard

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * A screen LocalType draws. Hide LocalType from screen capture ([ScreenCapture]) marks its window before any content
 * is set and again each time it comes back, and marks the dialogs it shows through [cover].
 *
 * While the switch is on, the keyboard is put away as the screen is left, and the text box that had it gets it back
 * when the screen returns. On Android 16 a keyboard that is still up over a marked window when another screen takes
 * over, LocalType's own or another app's, can leave every later capture black, the other app and the home screen
 * included. The keyboard does not have to be LocalType's (`docs/screen-capture-2026-11-24.md`). A screen that is only
 * being rebuilt keeps its keyboard, and with the switch off nothing here touches it.
 */
abstract class CoveredActivity : Activity() {
    /** Dialogs shown from this screen. One with a text box is the window the keyboard types into. */
    private val dialogs = WeakHashMap<Dialog, Unit>()
    /** The text box whose keyboard was put away as the screen was left. */
    private var typedInto: WeakReference<View>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenCapture.cover(window, KeyboardSettings.hideFromCapture(this))
    }

    override fun onResume() {
        super.onResume()
        ScreenCapture.cover(window, KeyboardSettings.hideFromCapture(this))
        // A dialog left open while the switch changed elsewhere keeps the flags it was shown with: mark it too.
        dialogs.keys.filter { it.isShowing }.forEach(::cover)
        giveKeyboardBack()
    }

    override fun onPause() {
        if (ScreenCapture.dropsKeyboard(KeyboardSettings.hideFromCapture(this), isChangingConfigurations)) dropKeyboard()
        super.onPause()
    }

    /** Marks a dialog of this screen. Call it before the dialog is shown. */
    protected fun cover(dialog: Dialog) {
        ScreenCapture.cover(dialog.window, KeyboardSettings.hideFromCapture(this))
        dialogs[dialog] = Unit
    }

    private fun dropKeyboard() {
        val keyboard = getSystemService(InputMethodManager::class.java) ?: return
        // Android acts on the request only for the window being typed into: this screen's, or a dialog's.
        val windows = listOf(window) + dialogs.keys.filter { it.isShowing }.map { it.window }
        for (window in windows) {
            val root = window?.peekDecorView() ?: continue
            val up = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            window.currentFocus?.takeIf { up && it.onCheckIsTextEditor() }?.let { typedInto = WeakReference(it) }
            keyboard.hideSoftInputFromWindow(root.windowToken, 0)
        }
    }

    /** Android would have brought the keyboard back by itself had it not been put away here. */
    private fun giveKeyboardBack() {
        val box = typedInto?.get()
        typedInto = null
        if (box == null || !box.isAttachedToWindow || !box.isFocused) return
        val show = Runnable { getSystemService(InputMethodManager::class.java)?.showSoftInput(box, 0) }
        if (box.hasWindowFocus()) {
            box.post(show)
            return
        }
        // A returning screen's window is focused a moment after it resumes, and only a focused window is given a keyboard.
        box.viewTreeObserver.addOnWindowFocusChangeListener(object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                box.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                box.post(show)
            }
        })
    }
}
