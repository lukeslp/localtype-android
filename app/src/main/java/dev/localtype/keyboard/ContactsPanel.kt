package dev.localtype.keyboard

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * The Contacts panel: the people matching the word before the caret, each with their name, numbers and addresses as
 * large rows to tap. The field's own kind of detail comes first ([ContactBook.choices]). Nothing here reads the
 * contacts or edits the field: the service hands it the list and [onChoice] inserts.
 */
class ContactsPanel(
    private val context: Context,
    private val onChoice: (ContactBook.Choice) -> Unit,
    private val onAllow: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    val header: TextView = TextView(context).apply {
        text = context.getString(R.string.tool_contacts)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(context.getColor(R.color.kb_on_key))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(8), 0, dp(8), 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(6))
    }

    val content: View = ScrollView(context).apply {
        isFillViewport = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(16))
        addView(list)
    }

    /** What the panel can show. */
    sealed interface State {
        /** The permission isn't granted: say what it's for, and offer to ask. */
        data object NotAllowed : State
        data object Loading : State
        data class Found(val query: String, val contacts: List<Contact>, val field: ContactBook.Field) : State
    }

    fun show(state: State) {
        list.removeAllViews()
        header.text = if (state is State.Found && state.query.isNotEmpty())
            context.getString(R.string.contacts_title_for, state.query) else context.getString(R.string.tool_contacts)
        when (state) {
            State.NotAllowed -> {
                list.addView(message(context.getString(R.string.contacts_why)))
                list.addView(row(context.getString(R.string.contacts_allow), primary = true) { onAllow() })
            }
            State.Loading -> list.addView(message(context.getString(R.string.contacts_loading)))
            is State.Found -> {
                if (state.contacts.isEmpty()) {
                    list.addView(message(if (state.query.isEmpty()) context.getString(R.string.contacts_none)
                        else context.getString(R.string.contacts_no_match, state.query)))
                }
                state.contacts.forEach { contact ->
                    ContactBook.choices(contact, state.field).forEachIndexed { index, choice ->
                        list.addView(row(choice.text, primary = index == 0, description = describe(contact, choice)) { onChoice(choice) })
                    }
                    list.addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)))
                }
            }
        }
        content.post { (content as ScrollView).scrollTo(0, 0) }
    }

    /** TalkBack hears whose detail it is and what kind, since only the first row of a person carries the name. */
    private fun describe(contact: Contact, choice: ContactBook.Choice): String = when (choice.kind) {
        ContactBook.Kind.NAME -> choice.text
        ContactBook.Kind.PHONE -> context.getString(R.string.contacts_phone_of, choice.text, contact.name)
        ContactBook.Kind.EMAIL -> context.getString(R.string.contacts_email_of, choice.text, contact.name)
    }

    private fun message(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(context.getColor(R.color.kb_hint))
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }

    private fun row(text: String, primary: Boolean, description: String? = null, onTap: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 16f else 14f)
        setTextColor(context.getColor(if (primary) R.color.kb_on_key else R.color.kb_hint))
        if (primary) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(48)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(if (primary) 10 else 22), 0, dp(10), 0)
        background = context.getDrawable(R.drawable.panel_key_background)
        isClickable = true
        isFocusable = true
        contentDescription = description ?: text
        setOnClickListener { onTap() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        }
    }
}
