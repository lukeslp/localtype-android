package dev.localtype.keyboard

import android.annotation.SuppressLint
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Puts TalkBack's focus somewhere on purpose. When the control a person double-tapped hides or is
 * replaced (ABC closing a panel, ✕ ending search, Remove redrawing the list), Android clears
 * accessibility focus and nothing has it, so the next swipe starts from nowhere. Everything here
 * does nothing unless touch exploration is on, so a sighted tap, or Switch Access, never moves it.
 */
internal object TalkBackFocus {
    fun isExploring(view: View): Boolean =
        view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /**
     * Focuses [target] on the next turn of the main loop, once the change that hid the old control
     * has been made, if [target] is still showing then. Null does nothing. Lint warns against
     * moving accessibility focus at all; the exception Android's guidance makes is this one, where
     * the focused control has just gone, so call it only for that.
     */
    @SuppressLint("AccessibilityFocus")
    fun moveTo(target: View?) {
        if (target == null || !isExploring(target)) return
        target.post {
            if (target.isShown) target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    /** Whether TalkBack's focus is on [view]. View's own check is hidden, so this asks the view's node. */
    fun isOn(view: View): Boolean = view.createAccessibilityNodeInfo().isAccessibilityFocused
}
