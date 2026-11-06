package dev.localtype.keyboard

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Every vibration the keyboard makes goes through here, so **Vibrate on key press**
 * ([KeyboardSettings.vibrateOnKeyPress]) covers keys, holds, slides, the strip and both panels.
 * While it is on, Android's own touch feedback setting still decides whether anything is felt.
 */
object KeyHaptics {
    /** The switch, read by the keyboard at each field and when it changes in settings. */
    var enabled = true

    /**
     * One [feedback] on [view] while the switch is on. The view's own flag is ignored, because views
     * whose holds go through [onHold] have it off; Android's global setting is never bypassed.
     */
    fun perform(view: View?, feedback: Int) {
        if (enabled) view?.performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    /**
     * A long-click listener for [view]. Android vibrates by itself when a hold is handled; here that is
     * turned off and the same vibration goes through [perform] instead, so the switch covers it too.
     */
    fun onHold(view: View, hold: (View) -> Boolean) {
        view.isHapticFeedbackEnabled = false
        view.setOnLongClickListener { held -> hold(held).also { if (it) perform(held, HapticFeedbackConstants.LONG_PRESS) } }
    }
}
