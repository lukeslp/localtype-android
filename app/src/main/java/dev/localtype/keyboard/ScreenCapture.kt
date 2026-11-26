package dev.localtype.keyboard

import android.content.Context
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow

/**
 * Hide LocalType from screen capture (`hide_from_capture`, off by default): the keyboard's own windows are marked
 * secure, so Android leaves them out of screenshots, screen recordings, casting to a display that is not secure,
 * and the picture of the screen an assistant is handed. It covers only what LocalType draws: the keys, the strip,
 * the panels, the hold popups and the settings screens. The app being typed into is another app's window and is
 * not covered, and neither is what an accessibility service reads.
 *
 * This file takes the choice as a parameter and reads no setting, so the editor fixture can copy it with
 * `KeyboardView`.
 */
object ScreenCapture {
    private const val SECURE = WindowManager.LayoutParams.FLAG_SECURE

    /** [flags] with the secure bit set when [hide] and cleared otherwise; no other bit changes. */
    fun flags(flags: Int, hide: Boolean): Int = if (hide) flags or SECURE else flags and SECURE.inv()

    /** Whether marking or clearing would change [flags]; a window that already has the wanted state is left alone. */
    fun changes(flags: Int, hide: Boolean): Boolean = flags(flags, hide) != flags

    /**
     * Sets or clears the mark through [set], which takes what `Window.setFlags` takes, and only when [current] is not
     * in the wanted state already. `setFlags` reports new attributes to the window manager every time it is called,
     * changed or not, and the keyboard asks at every field and each screen every time it comes back, with the switch
     * off as well. Returns whether it set anything.
     */
    internal inline fun mark(current: Int, hide: Boolean, set: (flags: Int, mask: Int) -> Unit): Boolean {
        if (!changes(current, hide)) return false
        set(if (hide) SECURE else 0, SECURE)
        return true
    }

    /**
     * Whether a marked screen puts the keyboard away as it is left ([CoveredActivity]): only while LocalType is hidden
     * from capture, and not when the screen is only being rebuilt, as it is when the phone turns.
     */
    fun dropsKeyboard(hide: Boolean, rebuilding: Boolean): Boolean = hide && !rebuilding

    /** Marks an activity's, a dialog's or the keyboard's window, or clears the mark. It can change while showing. */
    fun cover(window: Window?, hide: Boolean) {
        window ?: return
        mark(window.attributes.flags, hide, window::setFlags)
    }

    /**
     * A popup is a window of its own and does not take the mark from the window it hangs from, and `PopupWindow`
     * has no setter for it. Call this right after showing the popup: the popup's first frame is drawn after this
     * returns, so it is never on screen unmarked. Each show starts from fresh window attributes, so call it per show.
     */
    fun cover(popup: PopupWindow, hide: Boolean) {
        if (!hide || !popup.isShowing) return
        val root = popup.contentView?.rootView ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        if (!changes(params.flags, hide)) return
        params.flags = flags(params.flags, hide)
        (root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, params)
    }
}
