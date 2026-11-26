package dev.localtype.keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Licenses and credits, opened from About: the notices the bundled word lists, models, emoji and libraries ask
 * the app to show. It lists the files in the assets' `licenses` folder ([LicenseNotices]) and shows one when
 * its row is tapped. It reads only the app's own assets and keeps nothing.
 */
class LicensesActivity : CoveredActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SettingsLocale.configuration(base)?.let { applyOverrideConfiguration(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notices = LicenseNotices.notices(names(LicenseNotices.FOLDER), names(LicenseNotices.SOFTWARE_FOLDER))
        // Only a notice from the list is ever opened, whatever the intent names.
        val open = intent.getStringExtra(PATH)?.let { path -> notices.firstOrNull { it.path == path } }
        val heading = open?.title ?: getString(R.string.licenses_title)
        title = heading

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        header.addView(button(getString(R.string.licenses_back)).apply {
            contentDescription = getString(if (open == null) R.string.licenses_back_settings else R.string.licenses_back_list)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(text(heading, 22f, R.color.on_surface, bold = true).apply {
            setPadding(0, dp(12), 0, 0)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (open == null) showList(body, notices) else showNotice(body, open)
    }

    private fun showList(body: LinearLayout, notices: List<LicenseNotices.Notice>) {
        body.addView(text(getString(R.string.licenses_intro), 14f, R.color.on_surface_variant).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(4))
        })
        LicenseNotices.Section.values().forEach { section ->
            val rows = notices.filter { it.section == section }
            if (rows.isEmpty()) return@forEach
            body.addView(text(getString(sectionTitle(section)), 14f, R.color.primary, bold = true).apply {
                setPadding(0, dp(18), 0, dp(2))
                ViewCompat.setAccessibilityHeading(this, true)
            })
            rows.forEach { notice -> body.addView(row(notice), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
    }

    private fun sectionTitle(section: LicenseNotices.Section) = when (section) {
        LicenseNotices.Section.DATA -> R.string.licenses_section_data
        LicenseNotices.Section.SOFTWARE -> R.string.licenses_section_software
        LicenseNotices.Section.TEXTS -> R.string.licenses_section_texts
    }

    /** A notice's row: its name, the first line of what it says, and a › mark. Read by TalkBack as a button. */
    private fun row(notice: LicenseNotices.Notice): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            background = selectable()
            setOnClickListener { startActivity(Intent(this@LicensesActivity, LicensesActivity::class.java).putExtra(PATH, notice.path)) }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(notice.title, 15f, R.color.on_surface, bold = true))
        val summary = LicenseNotices.summary(read(notice.path, SUMMARY_CHARS))
        if (summary.isNotEmpty()) column.addView(text(summary, 13f, R.color.on_surface_variant).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("›", 20f, R.color.on_surface_variant).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(8), 0, dp(4), 0)
        })
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
        return row
    }

    /**
     * The notice, block by block. A paragraph wraps to the screen in the ordinary face; a table, a list or a
     * centered heading keeps its lines in a fixed-width face, so columns keep their shape.
     */
    private fun showNotice(body: LinearLayout, notice: LicenseNotices.Notice) {
        LicenseNotices.blocks(read(notice.path)).forEach { block ->
            body.addView(text(block.text, if (block.prose) 14f else 13f, R.color.on_surface).apply {
                if (!block.prose) typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                // A selectable block takes focus when touched, and the scroller would otherwise jump to show all of it.
                revealOnFocusHint = false
                setLineSpacing(dp(2).toFloat(), 1f)
                // A clause set in under its number keeps a margin; a deep one stops growing so the text keeps its room.
                setPadding(dp(2 * block.indent.coerceAtMost(MAX_INDENT)), dp(6), 0, dp(6))
            })
        }
    }

    private fun names(folder: String): List<String> = runCatching { assets.list(folder) }.getOrNull().orEmpty().toList()

    /** The asset's text, or its first [limit] characters; empty when it can't be read. */
    private fun read(path: String, limit: Int = Int.MAX_VALUE): String = runCatching {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { reader ->
            if (limit == Int.MAX_VALUE) reader.readText() else CharArray(limit).let { String(it, 0, reader.read(it).coerceAtLeast(0)) }
        }
    }.getOrDefault("")

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String) = Button(this).apply {
        text = label
        minHeight = dp(48)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(getColor(R.color.on_primary_container))
        background = GradientDrawable().apply {
            setColor(getColor(R.color.primary_container))
            cornerRadius = dp(22).toFloat()
        }
        stateListAnimator = null
        setPadding(dp(20), 0, dp(20), 0)
    }

    private fun selectable() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PATH = "dev.localtype.keyboard.licenses.PATH"
        /** Spaces of indentation drawn as a margin, at 2 dp each. */
        const val MAX_INDENT = 12
        /** Enough of a notice to hold its first line. */
        const val SUMMARY_CHARS = 400
    }
}
