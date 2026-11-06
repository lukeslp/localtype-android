package dev.localtype.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat

/** The panels that can cover the keys: every emoji, Saved text, every tool, the arrow pad and contacts. */
enum class KeyboardPanel { EMOJI, SAVED, TOOLS, ARROWS, CONTACTS }

/**
 * The area under the suggestion strip: the keys, and any open panel laid over them at exactly the
 * keys' height. Opening a panel never changes the keyboard window's size, so the app underneath
 * never re-lays out. Hidden keys are INVISIBLE, not GONE, and are measured either way.
 */
class KeyArea(context: Context) : FrameLayout(context) {
    /**
     * While a panel is open, the height the keys had when it opened; 0 follows the keys. With the
     * number row on, letters are taller than symbols, and a space typed in a panel returns the
     * hidden keys to the letters, which mustn't resize the window under the panel.
     */
    var pinnedHeight = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * The one-handed rail beside narrowed keys, or null. It takes the width the keys leave, at their height; a panel
     * still covers the whole area.
     */
    var rail: View? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The keys come first and set the height; a panel takes exactly that, however much it could fill.
        val keys = getChildAt(0) ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        measureChild(keys, widthMeasureSpec, heightMeasureSpec)
        val areaHeight = if (pinnedHeight > 0) pinnedHeight else keys.measuredHeight
        // One-handed keys are narrower than the area, which keeps the full width for panels and the rail.
        val areaWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) keys.measuredWidth
            else maxOf(keys.measuredWidth, MeasureSpec.getSize(widthMeasureSpec))
        val width = MeasureSpec.makeMeasureSpec(areaWidth, MeasureSpec.EXACTLY)
        val height = MeasureSpec.makeMeasureSpec(areaHeight, MeasureSpec.EXACTLY)
        for (i in 1 until childCount) {
            val child = getChildAt(i).takeIf { it.visibility != GONE } ?: continue
            if (child === rail) child.measure(MeasureSpec.makeMeasureSpec(areaWidth - keys.measuredWidth, MeasureSpec.EXACTLY), height)
            else child.measure(width, height)
        }
        setMeasuredDimension(areaWidth, areaHeight)
    }
}

/** Views every panel shares: its root, which TalkBack announces, and the bottom bar. */
internal object PanelViews {
    /** The bar's way to the other panel. */
    class Toggle(val icon: Int, val label: Int, val onTap: () -> Unit)

    /** A built bar, and its [Toggle] key, for TalkBack's focus to land on after a switch. */
    class Bar(val view: LinearLayout, val toggle: View)

    /**
     * [content] above the [bar], titled [title] for TalkBack. It starts GONE, so showing it is a
     * visibility change that announces the title as a pane appearing.
     */
    fun root(context: Context, title: String, content: View, bar: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(context.getColor(R.color.kb_background))
        ViewCompat.setAccessibilityPaneTitle(this, title)
        addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, BAR_DP)))
    }

    /**
     * The 48 dp bar under a panel: ABC back to the letters, the [toggle] to the other panel, a
     * flexible space, and delete, which acts on press and repeats while held like the Delete key.
     * Each key fills its whole cell for touch and draws inset like the keyboard's keys.
     */
    fun bar(context: Context, toggle: Toggle, onLetters: () -> Unit, onSpace: () -> Unit, onDelete: () -> Unit): Bar {
        val toggleKey = ImageView(context).apply {
            setImageResource(toggle.icon)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(toggle.label)
            background = context.getDrawable(R.drawable.panel_modifier_background)
            setOnClickListener { tap(it, toggle.onTap) }
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The keyboard's side padding, so the bar's keys line up with the keys above when they return.
            setPadding(dp(context, 3), 0, dp(context, 3), 0)
            // Reserve one full touch target for every action before distributing spare width.
            // Zero-width weighted cells made the panel toggle narrower than 48 dp on a 320 dp phone.
            fun cell(weight: Float) = LinearLayout.LayoutParams(dp(context, BAR_DP), LinearLayout.LayoutParams.MATCH_PARENT, weight)

            addView(TextView(context).apply {
                text = context.getString(R.string.panel_letters_label)
                contentDescription = context.getString(R.string.panel_letters)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(context.getColor(R.color.kb_on_key))
                gravity = Gravity.CENTER
                maxLines = 1
                background = context.getDrawable(R.drawable.panel_modifier_background)
                setOnClickListener { tap(it, onLetters) }
            }, cell(1.5f))

            addView(toggleKey, cell(1.25f))

            addView(View(context).apply {
                contentDescription = context.getString(R.string.panel_space)
                background = context.getDrawable(R.drawable.panel_key_background)
                setOnClickListener { tap(it, onSpace) }
            }, cell(4.5f))

            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_backspace)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = context.getString(R.string.panel_delete)
                background = context.getDrawable(R.drawable.panel_modifier_background)
                repeatWhileHeld(this, onDelete)
            }, cell(1.5f))
        }
        return Bar(view, toggleKey)
    }

    private fun tap(view: View, action: () -> Unit) {
        KeyHaptics.perform(view, HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }

    /** Acts on press, then repeats while held, speeding up as the Delete key does. TalkBack's double-tap acts once. */
    private fun repeatWhileHeld(view: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var delay = REPEAT_START_MS
        val repeat = object : Runnable {
            override fun run() {
                // The panel closed under the finger (ABC with another finger): stop.
                if (!view.isShown) return
                action()
                delay = maxOf(REPEAT_MIN_MS, (delay * 0.85).toLong())
                handler.postDelayed(this, delay)
            }
        }
        view.isClickable = true
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.isPressed = true
                    tap(touched, action)
                    delay = REPEAT_START_MS
                    handler.postDelayed(repeat, HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeat)
                    touched.isPressed = false
                    // Only reports the click to accessibility; the delete already happened on press.
                    if (event.actionMasked == MotionEvent.ACTION_UP) touched.performClick()
                }
            }
            true
        }
        ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_CLICK, null) { _, _ ->
            action()
            true
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private const val BAR_DP = 48
    // The Delete key's timings (KeyboardView), so holding either feels the same.
    private const val HOLD_MS = 380L
    private const val REPEAT_START_MS = 90L
    private const val REPEAT_MIN_MS = 35L
}
