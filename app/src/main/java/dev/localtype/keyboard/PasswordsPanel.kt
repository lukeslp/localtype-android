package dev.localtype.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * The Passwords & cards panel: the password manager's chips over the keys, out of the strip, in lines with room
 * between them, or a sentence saying why there are none; and a button for the manager's own app. The chips are the
 * views Android drew, moved here by [InlineAutofill.attach]; this class never looks inside them, and nothing here
 * asks for a fill. [onOpen] opens the manager or Android's autofill choice.
 */
class PasswordsPanel(private val context: Context, private val onOpen: () -> Unit) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    val header: TextView = TextView(context).apply {
        text = context.getString(R.string.tool_passwords)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(context.getColor(R.color.kb_on_key))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(8), 0, dp(8), 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this, true)
    }

    /** Where the chips go while the panel is open. Wider gaps than the strip's, so a tap lands on one chip. */
    val chips = InlineChipColumn(context).apply {
        visibility = View.GONE
        flow.gap = dp(CHIP_GAP_DP)
        flow.setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private val message = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(context.getColor(R.color.kb_on_key))
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(14), dp(10), dp(14), dp(10))
        // The answer can change while the panel is open (chips arriving, or going after a fill): TalkBack hears it.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private val open = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(context.getColor(R.color.kb_on_key))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(48)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(14), 0, dp(14), 0)
        background = context.getDrawable(R.drawable.panel_key_background)
        isClickable = true
        isFocusable = true
        setOnClickListener { onOpen() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
    }

    val content: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(6))
        addView(chips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(open, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Shows [state]; [button] names what the button opens: the manager by its name, or Android's autofill choice. */
    fun show(state: PasswordsTile.State, button: String) {
        val holding = state == PasswordsTile.State.CHIPS
        chips.visibility = if (holding) View.VISIBLE else View.GONE
        message.visibility = if (holding) View.GONE else View.VISIBLE
        val said = when (state) {
            PasswordsTile.State.CHIPS -> null
            PasswordsTile.State.WAITING -> R.string.passwords_waiting
            PasswordsTile.State.NONE -> R.string.passwords_none
            PasswordsTile.State.OFF -> R.string.passwords_off
            PasswordsTile.State.TALKBACK -> R.string.passwords_talkback
            PasswordsTile.State.NO_MANAGER -> R.string.passwords_no_manager
        }
        val text = said?.let(context::getString).orEmpty()
        if (message.text.toString() != text) message.text = text
        open.text = button
    }

    private companion object {
        const val CHIP_GAP_DP = 10
    }
}
