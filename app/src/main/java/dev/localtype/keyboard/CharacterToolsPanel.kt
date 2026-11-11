package dev.localtype.keyboard

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat

/** Ordinary, named controls in the existing panel space; no editor, history or persistence ownership. */
internal class CharacterToolViews(val context: Context) {
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
    fun text(value: String) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(context.getColor(R.color.kb_on_key)); setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; minHeight = dp(48); minimumWidth = dp(48)
        // Named symbol rows and translated style names must reflow with enlarged text.
        isSingleLine = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(context.getColor(R.color.kb_on_key))
        background = context.getDrawable(R.drawable.panel_key_background)
        setOnClickListener { action() }
    }
    fun scroll(child: View) = ScrollView(context).apply { isFillViewport = false; addView(child) }
    fun header(title: Int, action: Int, run: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(text(context.getString(title)).apply { ViewCompat.setAccessibilityHeading(this, true) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(button(context.getString(action), run).apply { maxLines = 1 }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }
}

internal class SymbolToolsPanel(private val context: Context, onSearch: () -> Unit, private val onInsert: (String) -> Unit) {
    private val ui = CharacterToolViews(context)
    val header = ui.header(R.string.symbol_tool_title, R.string.local_tool_search, onSearch)
    private val list = ui.column()
    val content: View = ui.scroll(list)
    fun show(query: String) {
        list.removeAllViews()
        list.addView(ui.text(context.getString(R.string.symbol_tool_presentation)))
        val language = context.resources.configuration.locales[0].language
        val results = PlainSymbols.search(query)
        if (results.isEmpty()) list.addView(ui.text(context.getString(R.string.local_tool_no_results)))
        results.forEach { entry ->
            val kind = context.getString(if (entry.kind == PlainSymbols.Kind.TEXT) R.string.symbol_tool_text else R.string.symbol_tool_emoji)
            val label = "${entry.text} · ${entry.name(language)} · $kind"
            list.addView(ui.button(label) { onInsert(entry.text) }.apply {
                contentDescription = context.getString(R.string.local_tool_insert_named, label)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}

internal class TextEffectsPanel(
    private val context: Context,
    onEdit: () -> Unit,
    private val onInsert: (String) -> Unit,
    private val onCopy: (String) -> Unit,
) {
    private val ui = CharacterToolViews(context)
    val header = ui.header(R.string.text_effects_title, R.string.local_tool_edit, onEdit)
    private val list = ui.column()
    private val scroll = ui.scroll(list)
    val content: View = scroll
    private var selectedStyleButton: Button? = null
    private var source = ""
    private var style = TextEffects.Style.SMALL_CAPS
    fun show(original: String) { source = original; draw() }
    fun clear() { source = ""; draw() }
    private fun draw() {
        val position = scroll.scrollY
        list.removeAllViews()
        list.addView(ui.text(context.getString(R.string.text_effects_caution)))
        list.addView(ui.text(context.getString(R.string.local_tool_source, source.ifEmpty { context.getString(R.string.local_tool_empty) })))
        val result = TextEffects.preview(source, style)
        list.addView(ui.text(context.getString(R.string.local_tool_preview, result ?: context.getString(R.string.text_effects_unavailable))))
        var row: LinearLayout? = null
        TextEffects.Style.entries.forEachIndexed { index, choice ->
            if (index % 2 == 0) row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                list.addView(this)
            }
            val label = context.getString(when (choice) {
                TextEffects.Style.SMALL_CAPS -> R.string.effect_small_caps
                TextEffects.Style.SUPERSCRIPT -> R.string.effect_superscript
                TextEffects.Style.MONOSPACE -> R.string.effect_monospace
                TextEffects.Style.FULLWIDTH -> R.string.effect_fullwidth
                TextEffects.Style.CURSED -> R.string.effect_cursed
            })
            row!!.addView(ui.button(if (choice == style) "✓ $label" else label) {
                style = choice
                draw()
                selectedStyleButton?.requestFocus()
                TalkBackFocus.moveTo(selectedStyleButton)
            }.apply {
                isSelected = choice == style
                if (isSelected) selectedStyleButton = this
                contentDescription = if (isSelected) context.getString(R.string.local_tool_selected, label) else label
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.button(context.getString(R.string.local_tool_insert)) { result?.let(onInsert) }.apply { isEnabled = result != null },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.button(context.getString(R.string.local_tool_copy)) { result?.let(onCopy) }.apply { isEnabled = result != null },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        scroll.post { scroll.scrollTo(0, position) }
    }
}
