package dev.localtype.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.LocaleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup and preferences: enable the keyboard, try it, manage languages, models and learned words.
 *
 * One folding rule: sections fold, rows don't. Each section is a heading with a live one-line summary and
 * remembers whether it was open. Each setting is one row and one TalkBack stop, with a short summary; its full
 * explanation, word for word, opens from the row's ⓘ button or its Details action.
 */
class SettingsActivity : Activity() {
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var enableStep: StepViews
    private lateinit var selectStep: StepViews
    private lateinit var learnedSummary: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var voicePermission: Button
    private var modelProgressChecked = false

    /** The locale the screen's text is spoken in when it isn't the phone's own, for TalkBack's voice. */
    private var speakLocale: Locale? = null

    private class StepViews(val number: TextView, val button: Button)
    private lateinit var tagline: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var setupDone: LinearLayout

    private val sections = ArrayList<Section>()
    private lateinit var modelsSection: Section

    /** Light or dark as chosen under Appearance, and the language from Settings shown in, before any view reads them. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SettingsLocale.configuration(base)?.let { applyOverrideConfiguration(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!KeyboardSettings.setupComplete(this)) {
            val enabled = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.any { it.packageName == packageName }
            // An existing enabled keyboard keeps its settings screen when upgrading.
            if (!SetupFlow.shouldStart(false, enabled, KeyboardSettings.setupStep(this) != null)) KeyboardSettings.setSetupComplete(this)
            else if (!intent.getBooleanExtra(SetupActivity.SKIP, false)) {
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
                return
            }
        }
        speakLocale = SettingsLocale.override(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            // Clip at the status bar so scrolled cards don't slide under the clock.
            clipToPadding = true
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        // Dark icons on a light screen and light icons on a dark one, whatever the phone's own theme.
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, scroll).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        modelProgressChecked = savedInstanceState?.getBoolean("modelProgressChecked") ?: false
        addedExpanded = savedInstanceState?.getBoolean("addedWordsExpanded") ?: false
        savedExpanded = savedInstanceState?.getBoolean("savedTextExpanded") ?: false
        sitesExpanded = savedInstanceState?.getBoolean("sitesExpanded") ?: false
        learnedExpanded = savedInstanceState?.getBoolean("learnedExpanded") ?: false
        addHeader()
        addUpdateBanner()
        addTourCard()
        addSetupSteps()
        addTryIt()
        addTips()
        addLanguages()
        addTyping()
        // The catalog build keeps Chime in Typing; optional Nano/Gemma controls are full-only.
        if (BuildConfig.INCLUDES_NANO || BuildConfig.INCLUDES_GEMMA) addModels()
        addKeysAndGestures()
        addTouchAndTiming()
        addVoice()
        addAppearance()
        addPasting()
        addYourData()
        addAbout()
        addedInput.setText(savedInstanceState?.getString("addedWordDraft").orEmpty())
        savedInput.setText(savedInstanceState?.getString("savedTextDraft").orEmpty())
        refreshSections()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (this::addedInput.isInitialized) {
            outState.putString("addedWordDraft", addedInput.text.toString())
            outState.putBoolean("addedWordsExpanded", addedExpanded)
        }
        if (this::savedInput.isInitialized) {
            outState.putString("savedTextDraft", savedInput.text.toString())
            outState.putBoolean("savedTextExpanded", savedExpanded)
        }
        outState.putBoolean("sitesExpanded", sitesExpanded)
        outState.putBoolean("learnedExpanded", learnedExpanded)
        outState.putBoolean("modelProgressChecked", modelProgressChecked)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!this::content.isInitialized) return
        refreshSetupState()
        refreshAddedWords()
        refreshLearnedSummary()
        refreshBlockedWords()
        refreshLogStatus()
        refreshSavedSummary()
        refreshSites()
        refreshVoiceState()
        refreshUpdateBanner(announce = false)
        checkForUpdateQuietly()
        refreshSections()
        if (BuildConfig.INCLUDES_GEMMA) screenScope.launch {
            val resumed = withContext(Dispatchers.IO) { ModelKitBridge.repairQueuedDownload(this@SettingsActivity) }
            resumed.fold(
                onSuccess = { refreshModelState() },
                onFailure = { showGemmaState(it.message ?: "Couldn't resume the download.") },
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard chooser is a dialog, so focus returning is the signal it closed.
        if (hasFocus) refreshSetupState()
    }

    override fun onDestroy() {
        screenScope.cancel()
        super.onDestroy()
    }

    private fun addHeader() {
        content.addView(text(getString(R.string.app_name), 26f, R.color.on_surface, bold = true).apply {
            setPadding(0, dp(16), 0, 0)
            // ViewCompat: View.setAccessibilityHeading is API 28 and minSdk is 26.
            ViewCompat.setAccessibilityHeading(this, true)
        })
        // The tagline only helps before setup; afterwards the screen is for settings.
        tagline = text(getString(R.string.tagline), 15f, R.color.on_surface_variant).apply { setPadding(0, dp(4), 0, 0) }
        content.addView(tagline)
        // Spanish and Portuguese are machine-translated: say so wherever they show, as the word lists do.
        if (SettingsLocale.shown(this) != "en") {
            content.addView(text(getString(R.string.settings_translated_note), 13f, R.color.on_surface_variant).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    /** Shown once to someone already set up: the tour of what the keys do not show, or a way to decline it. */
    private fun addTourCard() {
        if (!KeyboardSettings.setupComplete(this) || KeyboardSettings.tourSeen(this)) return
        val card = card()
        card.addView(text(getString(R.string.tour_card_title), 16f, R.color.on_surface, bold = true))
        card.addView(text(getString(R.string.tour_card_body), 14f, R.color.on_surface_variant).apply { setPadding(0, dp(4), 0, dp(10)) })
        card.addView(button(getString(R.string.tour_action), primary = true).apply { setOnClickListener { openTour() } })
        card.addView(button(getString(R.string.tour_card_dismiss), primary = false).apply {
            setOnClickListener {
                KeyboardSettings.setTourSeen(this@SettingsActivity)
                content.removeView(card)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun openTour() {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.TOUR, true))
    }

    private fun addSetupSteps() {
        // Once LocalType is on and selected, the steps shrink to one line with a way to switch.
        setupDone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        setupDone.addView(text(getString(R.string.setup_done), 15f, R.color.on_surface_variant),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setupDone.addView(button(getString(R.string.setup_switch), primary = false).apply {
            setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        content.addView(setupDone)
        val card = card().also { setupCard = it }
        (card.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        enableStep = step(card, 1, R.string.step_enable_title, R.string.step_enable_body, R.string.step_enable_action) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        card.addView(View(this).apply { setBackgroundColor(getColor(R.color.kb_divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(16), 0, dp(16)) })
        selectStep = step(card, 2, R.string.step_select_title, R.string.step_select_body, R.string.step_select_action) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    private fun step(parent: LinearLayout, index: Int, title: Int, body: Int, action: Int, onClick: () -> Unit): StepViews {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val number = text(index.toString(), 15f, R.color.on_primary_container, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(R.color.primary_container, 16)
        }
        row.addView(number, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) })
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(getString(title), 17f, R.color.on_surface, bold = true))
        column.addView(text(getString(body), 14f, R.color.on_surface_variant).apply { setPadding(0, dp(4), 0, dp(10)) })
        val button = button(getString(action), primary = true).apply { setOnClickListener { onClick() } }
        column.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
        return StepViews(number, button)
    }

    private fun refreshSetupState() {
        if (!this::enableStep.isInitialized) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        markStep(enableStep, 1, enabled)
        markStep(selectStep, 2, selected)
        val done = enabled && selected
        setupCard.visibility = if (done) View.GONE else View.VISIBLE
        setupDone.visibility = if (done) View.VISIBLE else View.GONE
        tagline.visibility = if (done) View.GONE else View.VISIBLE
        selectStep.button.isEnabled = enabled
        selectStep.button.alpha = if (enabled) 1f else 0.5f
    }

    private fun markStep(step: StepViews, index: Int, done: Boolean) {
        step.number.text = if (done) "✓" else index.toString()
        step.number.contentDescription = if (done) getString(R.string.step_done) else null
        step.button.visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun addTryIt() {
        val card = card()
        (card.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        card.addView(EditText(this).apply {
            hint = getString(R.string.try_it_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(getColor(R.color.on_surface))
            setHintTextColor(getColor(R.color.on_surface_variant))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // Sections

    /** Remembers which sections are open; separate from the keyboard's own settings. */
    private val sectionState by lazy { getSharedPreferences("settings_view", MODE_PRIVATE) }

    /**
     * One fold: a heading row with the section's name and a live one-line summary, and its body below as one
     * card. The heading is a TalkBack heading with Expand or Collapse; the open state is remembered.
     */
    private inner class Section(private val key: String, private val title: String, openByDefault: Boolean, private val summary: () -> String) {
        val body: LinearLayout = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(10))
            background = rounded(R.color.card, 18)
        }
        private val summaryView = text("", 13f, R.color.on_surface_variant).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val chevron = text("", 18f, R.color.on_surface_variant).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val heading: LinearLayout = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            isClickable = true
            isFocusable = true
            background = selectable()
        }
        var open = sectionState.getBoolean("open_$key", openByDefault)
            private set

        init {
            val column = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(text(title, 17f, R.color.on_surface, bold = true))
            column.addView(summaryView)
            heading.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            heading.addView(chevron, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
            heading.setOnClickListener { setOpen(!open, remember = true) }
            ViewCompat.setAccessibilityHeading(heading, true)
            ViewCompat.setAccessibilityDelegate(heading, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(if (open) AccessibilityActionCompat.ACTION_COLLAPSE else AccessibilityActionCompat.ACTION_EXPAND)
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val expand = AccessibilityNodeInfoCompat.ACTION_EXPAND
                    val collapse = AccessibilityNodeInfoCompat.ACTION_COLLAPSE
                    if ((action == expand && !open) || (action == collapse && open)) {
                        setOpen(!open, remember = true)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
            content.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) })
            content.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(4) })
            sections += this
            show()
        }

        /** Opens or closes the body. [remember]: a person's choice, kept for next time; a reveal by the screen is not. */
        fun setOpen(value: Boolean, remember: Boolean) {
            open = value
            if (remember) sectionState.edit().putBoolean("open_$key", value).apply()
            show()
        }

        fun refresh() {
            val line = summary()
            if (summaryView.text.toString() != line) summaryView.text = speak(line)
        }

        private fun show() {
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.text = if (open) "▴" else "▾"
            ViewCompat.setStateDescription(heading, getString(if (open) R.string.expanded else R.string.collapsed))
        }
    }

    private fun section(key: String, title: Int, openByDefault: Boolean, summary: () -> String) =
        Section(key, getString(title), openByDefault, summary)

    private fun refreshSections() = sections.forEach { it.refresh() }

    private fun onCount(vararg on: Boolean) = getString(R.string.section_on_count, on.count { it }, on.size)

    private fun onOff(on: Boolean) = getString(if (on) R.string.state_on else R.string.state_off)

    // Rows

    /** One switch row: a single TalkBack stop, read as a switch with its title, summary and state. */
    private class SwitchRow(val row: LinearLayout, val switch: Switch)

    /**
     * A setting as one row: title, one-line summary, the ⓘ button for [details] and the switch. Tapping
     * anywhere on the row flips it. TalkBack reads the row as a switch and offers Details as an action, so the
     * ⓘ button itself is hidden from it.
     */
    private fun addSwitch(parent: LinearLayout, title: Int, summary: Int, details: Int?, checked: Boolean, onChange: (Boolean) -> Unit): SwitchRow =
        addSwitch(parent, getString(title), getString(summary), details?.let { getString(it) }, checked, onChange)

    private fun addSwitch(parent: LinearLayout, title: String, summary: String, details: String?, checked: Boolean, onChange: (Boolean) -> Unit): SwitchRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            background = selectable()
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(title, 16f, R.color.on_surface, bold = true))
        val summaryView = text(summary, 13f, R.color.on_surface_variant).apply { setPadding(0, dp(2), 0, 0) }
        column.addView(summaryView)
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (details != null) {
            row.addView(infoButton(title, details, forTalkBack = false), LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val switch = Switch(this).apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(4) })
        row.setOnClickListener {
            switch.toggle()
            onChange(switch.isChecked)
            refreshSections()
        }
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = switch.isChecked
            }
        })
        if (details != null) {
            ViewCompat.addAccessibilityAction(row, getString(R.string.details_action)) { _, _ ->
                showDetails(title, details)
                true
            }
        }
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return SwitchRow(row, switch)
    }

    /** The ⓘ button: the full explanation in a dialog. Hidden from TalkBack where the row offers it as an action. */
    private fun infoButton(title: String, details: String, forTalkBack: Boolean) = ImageButton(this).apply {
        setImageResource(R.drawable.ic_info)
        imageTintList = ColorStateList.valueOf(getColor(R.color.on_surface_variant))
        background = selectable()
        contentDescription = getString(R.string.details_for, title)
        if (!forTalkBack) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { showDetails(title, details) }
    }

    private fun showDetails(title: String, details: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** A row that opens something: a title, an optional summary, and a › mark. Read by TalkBack as a button. */
    private fun addLink(parent: LinearLayout, title: String, summary: String? = null, onClick: () -> Unit): Pair<LinearLayout, TextView?> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            isFocusable = true
            background = selectable()
            setOnClickListener { onClick() }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(title, 15f, R.color.on_surface, bold = true))
        val summaryView = summary?.let { text(it, 13f, R.color.on_surface_variant).also(column::addView) }
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
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row to summaryView
    }

    /** A heading inside a section, with its ⓘ button when it has more to say. */
    private fun subhead(parent: LinearLayout, title: String, details: String? = null, topMargin: Int = 10) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (details != null) 48 else 32)
        }
        row.addView(text(title, 14f, R.color.primary, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (details != null) row.addView(infoButton(title, details, forTalkBack = true), LinearLayout.LayoutParams(dp(48), dp(48)))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { this.topMargin = dp(topMargin) })
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(getColor(R.color.kb_divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(10), 0, dp(6)) })
    }

    /**
     * Choices side by side that read as radio buttons: [options] are a short label and, when the label alone
     * says too little, a fuller description for TalkBack.
     */
    private fun segmented(parent: LinearLayout, label: String, options: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit) {
        val group = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            contentDescription = label
        }
        options.forEachIndexed { index, (name, description) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = speak(name)
                description?.let { contentDescription = it }
                buttonDrawable = null
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(6), 0, dp(6), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = index == selected
                fun style() {
                    setTextColor(getColor(if (isChecked) R.color.on_primary else R.color.on_primary_container))
                    background = rounded(if (isChecked) R.color.primary else R.color.primary_container, 22)
                }
                style()
                setOnCheckedChangeListener { _, checked ->
                    style()
                    if (checked) onSelect(index)
                }
            }, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index < options.size - 1) marginEnd = dp(6)
            })
        }
        parent.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6); bottomMargin = dp(4) })
    }

    // Tips and gestures

    /**
     * Tips and gestures, near the top and folded: what the keys can do without showing it. Each tip is one TalkBack
     * stop that reads the gesture and then what it does; each group's title is a heading to jump between.
     */
    private fun addTips() {
        val count = TIP_GROUPS.sumOf { (_, items) -> resources.getStringArray(items).size }
        val list = section("tips", R.string.tips_title, openByDefault = false) { getString(R.string.tips_summary, count) }.body
        list.addView(text(getString(R.string.tips_intro), 14f, R.color.on_surface_variant).apply { setPadding(0, dp(6), 0, dp(4)) })
        TIP_GROUPS.forEach { (title, items) ->
            list.addView(text(getString(title), 14f, R.color.primary, bold = true).apply {
                setPadding(0, dp(12), 0, dp(2))
                ViewCompat.setAccessibilityHeading(this, true)
            })
            resources.getStringArray(items).forEach { item ->
                val (gesture, effect) = SettingsTips.split(item)
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    // One stop for the pair: the gesture, then what it does.
                    ViewCompat.setScreenReaderFocusable(this, true)
                    addView(text(gesture, 15f, R.color.on_surface, bold = true))
                    addView(text(effect, 14f, R.color.on_surface_variant).apply { setLineSpacing(dp(2).toFloat(), 1f) })
                })
            }
        }
    }

    // Languages

    private val languageRows = ArrayList<Pair<LanguageProfile, SwitchRow>>()
    private lateinit var languageFooter: TextView
    private lateinit var pageLanguageRow: View
    private var pageLanguageSummary: TextView? = null

    /**
     * Languages as a quick list: English, Spanish and Portuguese with their words, then the IPA keyboard, which
     * has none. The last language with words can't be turned off. Below them, the hold-Space order and the
     * language this screen is shown in.
     */
    private fun addLanguages() {
        val body = section("languages", R.string.section_languages, openByDefault = true) {
            KeyboardSettings.languages(this).joinToString(", ") { "\u2068${it.nativeName}\u2069" }
        }.body
        val words = listOf(
            Triple(LanguageProfile.ENGLISH, R.string.english_summary, null),
            Triple(LanguageProfile.SPANISH, R.string.spanish_summary, R.string.spanish_body),
            Triple(LanguageProfile.PORTUGUESE, R.string.portuguese_summary, R.string.portuguese_body),
            Triple(LanguageProfile.HEBREW, R.string.hebrew_summary, R.string.hebrew_body),
            Triple(LanguageProfile.WELSH, R.string.welsh_summary, R.string.welsh_body),
            Triple(LanguageProfile.IRISH, R.string.irish_summary, R.string.irish_body),
            Triple(LanguageProfile.MAORI, R.string.maori_summary, R.string.maori_body),
            Triple(LanguageProfile.HAWAIIAN, R.string.hawaiian_summary, R.string.hawaiian_body),
            Triple(LanguageProfile.ARABIC, R.string.arabic_summary, R.string.arabic_body),
            Triple(LanguageProfile.PERSIAN, R.string.persian_summary, R.string.persian_body),
            Triple(LanguageProfile.GALICIAN, R.string.galician_summary, R.string.galician_body),
            Triple(LanguageProfile.SCOTTISH_GAELIC, R.string.gaelic_summary, R.string.gaelic_body),
            Triple(LanguageProfile.NAVAJO, R.string.navajo_summary, R.string.navajo_body),
        )
        words.forEach { (language, summary, details) ->
            val row = addSwitch(body, language.nativeName, getString(summary), details?.let { getString(it) }, languageOn(language)) { on ->
                setLanguageOn(language, on)
            }
            languageRows += language to row
            if (language === LanguageProfile.ENGLISH) {
                addSwitch(body, R.string.british_spelling_title, R.string.british_spelling_summary, R.string.british_spelling_body,
                    KeyboardSettings.britishSpelling(this)) { KeyboardSettings.setBritishSpelling(this, it) }
                addCurrency(body)
            }
        }
        divider(body)
        val ipa = addSwitch(body, R.string.ipa_title, R.string.ipa_summary, R.string.ipa_body, KeyboardSettings.ipa(this)) { on ->
            setLanguageOn(LanguageProfile.IPA, on)
        }
        languageRows += LanguageProfile.IPA to ipa
        languageFooter = text("", 13f, R.color.on_surface_variant).apply { setPadding(0, dp(6), 0, dp(4)) }
        body.addView(languageFooter)
        val (row, summary) = addLink(body, getString(R.string.settings_language_title), "") { choosePageLanguage() }
        pageLanguageRow = row
        pageLanguageSummary = summary
        refreshLanguages()
    }

    /** Currency is independent of spelling; the default keeps the old $/£ choice. */
    private fun addCurrency(parent: LinearLayout) {
        val choices = listOf(
            KeyboardCurrency.FOLLOW_SPELLING to R.string.currency_follow_spelling,
            KeyboardCurrency.DOLLAR to R.string.currency_dollar,
            KeyboardCurrency.POUND to R.string.currency_pound,
        )
        fun label() = getString(choices.first { it.first == KeyboardSettings.currency(this) }.second)
        lateinit var row: Pair<LinearLayout, TextView?>
        row = addLink(parent, getString(R.string.currency_title), label()) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.currency_title)
                .setSingleChoiceItems(choices.map { getString(it.second) }.toTypedArray(),
                    choices.indexOfFirst { it.first == KeyboardSettings.currency(this) }) { dialog, which ->
                    KeyboardSettings.setCurrency(this, choices[which].first)
                    row.second?.text = speak(label())
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        parent.addView(text(getString(R.string.currency_body), 13f, R.color.on_surface_variant))
    }

    private fun languageOn(language: LanguageProfile) = language in KeyboardSettings.languages(this)

    private fun setLanguageOn(language: LanguageProfile, on: Boolean) {
        // The last language with words stays on: the switch flips back and says why, rather than looking disabled.
        if (!on && !LanguageChoice.canTurnOff(language, KeyboardSettings.languages(this))) {
            Toast.makeText(this, R.string.language_last, Toast.LENGTH_SHORT).show()
            refreshLanguages()
            return
        }
        when (language) {
            LanguageProfile.ENGLISH -> KeyboardSettings.setEnglish(this, on)
            LanguageProfile.SPANISH -> KeyboardSettings.setSpanish(this, on)
            LanguageProfile.PORTUGUESE -> KeyboardSettings.setPortuguese(this, on)
            LanguageProfile.HEBREW -> KeyboardSettings.setHebrew(this, on)
            LanguageProfile.WELSH -> KeyboardSettings.setWelsh(this, on)
            LanguageProfile.IRISH -> KeyboardSettings.setIrish(this, on)
            LanguageProfile.MAORI -> KeyboardSettings.setMaori(this, on)
            LanguageProfile.HAWAIIAN -> KeyboardSettings.setHawaiian(this, on)
            in LanguageProfile.EXTRA -> KeyboardSettings.setExtraLanguage(this, language, on)
            else -> KeyboardSettings.setIpa(this, on)
        }
        refreshLanguages()
        refreshDataLanguage()
    }

    private fun refreshLanguages() {
        val offered = KeyboardSettings.languages(this)
        languageRows.forEach { (language, row) -> row.switch.isChecked = language in offered }
        languageFooter.text = speak(
            // Each name is isolated, so two right-to-left names in a row (العربية, فارسی) keep the order they are offered in.
            if (offered.size > 1) getString(R.string.languages_hold_space, offered.joinToString(" → ") { "\u2068${it.nativeName}\u2069" })
            else getString(R.string.languages_hold_space_one)
        )
        val chosen = KeyboardSettings.settingsLanguage(this)
        pageLanguageRow.visibility = if (PageLanguage.rowShown(chosen, SettingsLocale.phoneLanguages(), offered)) View.VISIBLE else View.GONE
        pageLanguageSummary?.text = speak(pageLanguageName(chosen))
    }

    private fun pageLanguageName(code: String): String =
        if (code.isEmpty()) getString(R.string.settings_language_phone, nativeNameOf(PageLanguage.shown("", SettingsLocale.phoneLanguages())))
        else nativeNameOf(code)

    private fun nativeNameOf(code: String) = LanguageProfile.of(code)?.nativeName ?: code

    /** Settings shown in: the phone's language, or English, Español or Português; the screen opens again in it. */
    private fun choosePageLanguage() {
        val codes = listOf("") + PageLanguage.TRANSLATED
        val current = codes.indexOf(KeyboardSettings.settingsLanguage(this)).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_language_title)
            .setSingleChoiceItems(codes.map { pageLanguageName(it) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                val before = SettingsLocale.shown(this)
                KeyboardSettings.setSettingsLanguage(this, codes[which])
                if (SettingsLocale.shown(this) != before) recreate() else refreshLanguages()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Typing

    /**
     * Corrections and suggestions: what the keyboard changes and what the strip offers. Folded at first like
     * the rest, so the screen opens on one page of section names with Languages open.
     */
    private fun addTyping() {
        val body = section("typing", R.string.section_typing, openByDefault = false) {
            val passwords = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
            val on = mutableListOf(
                KeyboardSettings.autocorrect(this), KeyboardSettings.webAutocorrect(this), KeyboardSettings.autoCapitalize(this),
                KeyboardSettings.neuralPredictions(this), KeyboardSettings.emoji(this), KeyboardSettings.profanity(this),
            )
            if (passwords) on += KeyboardSettings.passwordManagerSuggestions(this)
            onCount(*on.toBooleanArray())
        }.body
        subhead(body, getString(R.string.section_corrections), topMargin = 4)
        addSwitch(body, R.string.autocorrect_title, R.string.autocorrect_summary, R.string.autocorrect_body, KeyboardSettings.autocorrect(this)) {
            KeyboardSettings.setAutocorrect(this, it)
        }
        addLink(body, getString(R.string.small_fixes_title)) { showDetails(getString(R.string.small_fixes_title), getString(R.string.small_fixes_body)) }
        addSwitch(body, R.string.web_autocorrect_title, R.string.web_autocorrect_summary, R.string.web_autocorrect_body, KeyboardSettings.webAutocorrect(this)) {
            KeyboardSettings.setWebAutocorrect(this, it)
        }
        addSwitch(body, R.string.auto_capitalize_title, R.string.auto_capitalize_summary, R.string.auto_capitalize_body, KeyboardSettings.autoCapitalize(this)) {
            KeyboardSettings.setAutoCapitalize(this, it)
        }
        subhead(body, getString(R.string.section_suggestions))
        addSwitch(body, R.string.neural_predictions_title, R.string.neural_predictions_summary, R.string.neural_predictions_body,
            KeyboardSettings.neuralPredictions(this)) {
            KeyboardSettings.setNeuralPredictions(this, it)
        }
        addSwitch(body, R.string.emoji_title, R.string.emoji_summary, R.string.emoji_body, KeyboardSettings.emoji(this)) {
            KeyboardSettings.setEmoji(this, it)
        }
        addSwitch(body, R.string.profanity_title, R.string.profanity_summary, R.string.profanity_body, KeyboardSettings.profanity(this)) {
            KeyboardSettings.setProfanity(this, it)
        }
        // Android 11 added the way a keyboard can host a password manager's suggestions; earlier versions have nothing to switch.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            addSwitch(body, R.string.password_manager_title, R.string.password_manager_summary, R.string.password_manager_body,
                KeyboardSettings.passwordManagerSuggestions(this)) {
                KeyboardSettings.setPasswordManagerSuggestions(this, it)
            }
        }
    }

    // Keys and gestures

    private lateinit var gestureGrid: LinearLayout

    /** What the keys show and the gestures on them; the four two-finger directions show only while they're on. */
    private fun addKeysAndGestures() {
        val body = section("keys", R.string.section_keys, openByDefault = false) {
            onCount(KeyboardSettings.numberRow(this), KeyboardSettings.commonSymbolHolds(this),
                KeyboardSettings.vibrateOnKeyPress(this), KeyboardSettings.slideGestures(this),
                KeyboardSettings.experimentalSwipe(this), KeyboardSettings.twoFingerGestures(this))
        }.body
        addSwitch(body, R.string.number_row_title, R.string.number_row_summary, R.string.number_row_body, KeyboardSettings.numberRow(this)) {
            KeyboardSettings.setNumberRow(this, it)
        }
        addSwitch(body, R.string.common_symbol_holds_title, R.string.common_symbol_holds_summary, R.string.common_symbol_holds_body,
            KeyboardSettings.commonSymbolHolds(this)) {
            KeyboardSettings.setCommonSymbolHolds(this, it)
        }
        addSwitch(body, R.string.vibrate_title, R.string.vibrate_summary, R.string.vibrate_body, KeyboardSettings.vibrateOnKeyPress(this)) {
            KeyboardSettings.setVibrateOnKeyPress(this, it)
        }
        addSwitch(body, R.string.slide_gestures_title, R.string.slide_gestures_summary, R.string.slide_gestures_body, KeyboardSettings.slideGestures(this)) {
            KeyboardSettings.setSlideGestures(this, it)
        }
        subhead(body, getString(R.string.slide_speed_title), topMargin = 10)
        val speeds = listOf(CursorGestures.Speed.SLOWER to R.string.slide_speed_slower, CursorGestures.Speed.STANDARD to R.string.slide_speed_standard,
            CursorGestures.Speed.FASTER to R.string.slide_speed_faster)
        segmented(body, getString(R.string.slide_speed_title), speeds.map { getString(it.second) to null },
            speeds.indexOfFirst { it.first == KeyboardSettings.slideSpeed(this) }) { index ->
            KeyboardSettings.setSlideSpeed(this, speeds[index].first)
        }
        body.addView(text(getString(R.string.slide_speed_body), 13f, R.color.on_surface_variant))
        addSwitch(body, R.string.experimental_swipe_title, R.string.experimental_swipe_summary, R.string.experimental_swipe_body,
            KeyboardSettings.experimentalSwipe(this)) {
            KeyboardSettings.setExperimentalSwipe(this, it)
        }
        addSwitch(body, R.string.two_finger_title, R.string.two_finger_summary, R.string.two_finger_body, KeyboardSettings.twoFingerGestures(this)) {
            KeyboardSettings.setTwoFingerGestures(this, it)
            gestureGrid.visibility = if (it) View.VISIBLE else View.GONE
        }
        addGestureGrid(body)
        // Holding a tile in the keyboard's Tools panel changes the row; this puts it back.
        lateinit var reset: Pair<LinearLayout, TextView?>
        reset = addLink(body, getString(R.string.tools_row_reset_title), toolsRowSummary()) {
            KeyboardSettings.setToolsRow(this, null)
            reset.second?.text = toolsRowSummary()
            reset.first.announceForAccessibility(getString(R.string.tools_row_reset_done))
        }
    }

    private fun toolsRowSummary(): String = StripTools.rowOrder(KeyboardSettings.toolsRow(this))
        .joinToString(", ") { getString(it.label) }.ifEmpty { getString(R.string.tools_row_empty) }

    // Touch and timing

    /** Touch accommodations for a tremor or limited reach ([MotorSettings]): three timings and two switches. */
    private fun addTouchAndTiming() {
        val body = section("motor", R.string.section_motor, openByDefault = false) {
            val m = KeyboardSettings.motor(this)
            onCount(m.ignoreRepeatMs > 0, m.holdMs > 0, m.dwell, m.steadyKeys, m.aimFromTouches, KeyboardSettings.inactiveGaps(this))
        }.body
        body.addView(text(getString(R.string.motor_intro), 13f, R.color.on_surface_variant))
        val motor = KeyboardSettings.motor(this)
        timing(body, R.string.ignore_repeat_title, R.string.ignore_repeat_body, MotorSettings.REPEAT_CHOICES, motor.ignoreRepeatMs) {
            KeyboardSettings.setIgnoreRepeatMs(this, it)
        }
        timing(body, R.string.key_hold_title, R.string.key_hold_body, MotorSettings.HOLD_CHOICES, motor.holdMs) {
            KeyboardSettings.setKeyHoldMs(this, it)
        }
        timing(body, R.string.dwell_title, R.string.dwell_body, MotorSettings.DWELL_CHOICES, motor.dwellMs) {
            KeyboardSettings.setDwellMs(this, it)
        }
        addSwitch(body, R.string.steady_keys_title, R.string.steady_keys_summary, R.string.steady_keys_body, motor.steadyKeys) {
            KeyboardSettings.setSteadyKeys(this, it)
        }
        addSwitch(body, R.string.aim_touches_title, R.string.aim_touches_summary, R.string.aim_touches_body, motor.aimFromTouches) {
            KeyboardSettings.setAimFromTouches(this, it)
        }
        addSwitch(body, R.string.inactive_gaps_title, R.string.inactive_gaps_summary, R.string.inactive_gaps_body, KeyboardSettings.inactiveGaps(this)) {
            KeyboardSettings.setInactiveGaps(this, it)
        }
    }

    /** A row of times in seconds, Off first, with what the setting does under it. */
    private fun timing(body: LinearLayout, title: Int, explanation: Int, choices: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
        subhead(body, getString(title), topMargin = 14)
        val number = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
        val options = choices.map { ms ->
            if (ms == 0) getString(R.string.state_off) to null
            else number.format(ms / 1000.0).let { getString(R.string.seconds_short, it) to getString(R.string.seconds_long, it) }
        }
        segmented(body, getString(title), options, choices.indexOf(selected).coerceAtLeast(0)) { index ->
            onSelect(choices[index])
            refreshSections()
        }
        body.addView(text(getString(explanation), 13f, R.color.on_surface_variant))
    }

    /** The four directions in two rows of two; each opens a choice of action. */
    private fun addGestureGrid(parent: LinearLayout) {
        gestureGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (KeyboardSettings.twoFingerGestures(this@SettingsActivity)) View.VISIBLE else View.GONE
        }
        val names = mapOf(TwoFingerSwipe.LEFT to R.string.gesture_left, TwoFingerSwipe.RIGHT to R.string.gesture_right,
            TwoFingerSwipe.UP to R.string.gesture_up, TwoFingerSwipe.DOWN to R.string.gesture_down)
        TwoFingerSwipe.entries.chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, swipe ->
                val name = getString(names.getValue(swipe))
                val cell = button("", primary = false).apply { setPadding(dp(10), dp(4), dp(10), dp(4)) }
                fun show() {
                    cell.text = speak(getString(R.string.gesture_row, name, getString(KeyboardSettings.gestureAction(this, swipe).label)))
                }
                show()
                cell.setOnClickListener {
                    val actions = GestureAction.entries
                    val current = actions.indexOf(KeyboardSettings.gestureAction(this, swipe))
                    android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.gesture_choose, name))
                        .setSingleChoiceItems(actions.map { getString(it.label) }.toTypedArray(), current) { dialog, which ->
                            KeyboardSettings.setGestureAction(this, swipe, actions[which])
                            show()
                            dialog.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) marginEnd = dp(8)
                })
            }
            gestureGrid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
        }
        parent.addView(gestureGrid)
    }

    // Voice

    /** The mic key's switch, what this phone offers for it, and the microphone permission when it is needed. */
    private fun addVoice() {
        val body = section("voice", R.string.section_voice, openByDefault = false) { onOff(KeyboardSettings.voiceInput(this)) }.body
        addSwitch(body, R.string.voice_title, R.string.voice_summary, R.string.voice_body, KeyboardSettings.voiceInput(this)) {
            KeyboardSettings.setVoiceInput(this, it)
            refreshVoiceState()
        }
        voiceStatus = text("", 13f, R.color.on_surface_variant).apply { setPadding(0, dp(6), 0, 0) }
        body.addView(voiceStatus)
        voicePermission = button(getString(R.string.voice_allow_microphone), primary = false).apply {
            setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE) }
        }
        body.addView(voicePermission, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })
        subhead(body, getString(R.string.voice_key_title), topMargin = 14)
        val places = listOf(VoiceKey.OFF to R.string.voice_key_off, VoiceKey.STRIP to R.string.voice_key_strip,
            VoiceKey.KEYS to R.string.voice_key_keys)
        segmented(body, getString(R.string.voice_key_title), places.map { getString(it.second) to null },
            places.indexOfFirst { it.first == KeyboardSettings.voiceKey(this) }) { index ->
            KeyboardSettings.setVoiceKey(this, places[index].first)
        }
        body.addView(text(getString(R.string.voice_key_body), 13f, R.color.on_surface_variant))
        refreshVoiceState()
    }

    private fun refreshVoiceState() {
        if (!this::voiceStatus.isInitialized) return
        val enabled = KeyboardSettings.voiceInput(this)
        val offer = VoiceDictation.offer(this)
        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val needsMicrophone = enabled && offer.route == VoiceRoute.ON_DEVICE
        voiceStatus.text = when {
            !enabled -> getString(R.string.voice_status_off)
            offer.route == VoiceRoute.ON_DEVICE && granted ->
                getString(R.string.voice_status_on_device) + " " + getString(R.string.voice_microphone_allowed)
            offer.route == VoiceRoute.ON_DEVICE -> getString(R.string.voice_status_on_device)
            offer.route == VoiceRoute.HANDOFF ->
                getString(R.string.voice_status_handoff, offer.inputMethod?.loadLabel(packageManager) ?: getString(R.string.voice_input))
            else -> getString(R.string.voice_status_none)
        }
        voicePermission.visibility = if (needsMicrophone && !granted) View.VISIBLE else View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        refreshVoiceState()
        val denied = grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED
        // After "Don't allow" twice Android stops showing the prompt, and only its own app page can grant it.
        if (denied && !shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    // Appearance

    private val clearances = listOf(
        0 to (R.string.clearance_none to R.string.bottom_clearance_none),
        16 to (R.string.clearance_small to R.string.bottom_clearance_small),
        32 to (R.string.clearance_medium to R.string.bottom_clearance_medium),
        48 to (R.string.clearance_large to R.string.bottom_clearance_large),
    )
    private val themes = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)

    /** System, Light or Dark for the keyboard and this screen, and the room below the keys, as two rows of choices. */
    private fun addAppearance() {
        val body = section("appearance", R.string.section_appearance, openByDefault = false) {
            val theme = getString(themes.first { it.first == ThemeMode.of(this) }.second)
            val clearance = getString((clearances.firstOrNull { it.first == KeyboardSettings.bottomClearance(this) } ?: clearances.first()).second.second)
            getString(R.string.appearance_summary, theme, clearance)
        }.body
        subhead(body, getString(R.string.theme_title), topMargin = 4)
        val current = ThemeMode.of(this)
        segmented(body, getString(R.string.theme_title), themes.map { getString(it.second) to null }, themes.indexOfFirst { it.first == current }) { index ->
            val mode = themes[index].first
            if (mode == ThemeMode.of(this)) return@segmented
            KeyboardSettings.setTheme(this, mode)
            recreate()
        }
        body.addView(text(getString(R.string.theme_body), 13f, R.color.on_surface_variant))
        subhead(body, getString(R.string.bottom_clearance_title), topMargin = 14)
        val selected = clearances.indexOfFirst { it.first == KeyboardSettings.bottomClearance(this) }.coerceAtLeast(0)
        segmented(body, getString(R.string.bottom_clearance_title), clearances.map { (_, labels) -> getString(labels.first) to getString(labels.second) },
            selected) { index ->
            KeyboardSettings.setBottomClearance(this, clearances[index].first)
            refreshSections()
        }
        body.addView(text(getString(R.string.bottom_clearance_body), 13f, R.color.on_surface_variant))
        addSwitch(body, R.string.key_shapes_title, R.string.key_shapes_summary, R.string.key_shapes_body, KeyboardSettings.keyShapes(this)) {
            KeyboardSettings.setKeyShapes(this, it)
        }
        subhead(body, getString(R.string.space_bar_title), topMargin = 14)
        val bars = listOf(SpaceBar.STANDARD to R.string.space_bar_standard, SpaceBar.WIDER to R.string.space_bar_wider,
            SpaceBar.WIDEST to R.string.space_bar_widest)
        segmented(body, getString(R.string.space_bar_title), bars.map { getString(it.second) to null },
            bars.indexOfFirst { it.first == KeyboardSettings.spaceBar(this) }) { index ->
            KeyboardSettings.setSpaceBar(this, bars[index].first)
        }
        body.addView(text(getString(R.string.space_bar_body), 13f, R.color.on_surface_variant))
        subhead(body, getString(R.string.strip_height_title), topMargin = 14)
        val strips = listOf(StripHeight.STANDARD to R.string.strip_height_standard, StripHeight.TALLER to R.string.strip_height_taller,
            StripHeight.TALLEST to R.string.strip_height_tallest)
        segmented(body, getString(R.string.strip_height_title), strips.map { getString(it.second) to null },
            strips.indexOfFirst { it.first == KeyboardSettings.stripHeight(this) }) { index ->
            KeyboardSettings.setStripHeight(this, strips[index].first)
        }
        body.addView(text(getString(R.string.strip_height_body), 13f, R.color.on_surface_variant))
        subhead(body, getString(R.string.one_handed_title), topMargin = 14)
        val hands = listOf(OneHanded.OFF to R.string.one_handed_off, OneHanded.LEFT to R.string.one_handed_left,
            OneHanded.RIGHT to R.string.one_handed_right)
        segmented(body, getString(R.string.one_handed_title), hands.map { getString(it.second) to null },
            hands.indexOfFirst { it.first == KeyboardSettings.oneHanded(this) }) { index ->
            KeyboardSettings.setOneHanded(this, hands[index].first)
        }
        body.addView(text(getString(R.string.one_handed_body), 13f, R.color.on_surface_variant))
        subhead(body, getString(R.string.wide_keys_title), topMargin = 14)
        val wides = listOf(WideKeys.CENTERED to R.string.wide_keys_centered, WideKeys.FULL_WIDTH to R.string.wide_keys_full)
        segmented(body, getString(R.string.wide_keys_title), wides.map { getString(it.second) to null },
            wides.indexOfFirst { it.first == KeyboardSettings.wideKeys(this) }) { index ->
            KeyboardSettings.setWideKeys(this, wides[index].first)
        }
        body.addView(text(getString(R.string.wide_keys_body), 13f, R.color.on_surface_variant))
    }

    // Pasting and saved text

    private var savedCount = 0

    private fun addPasting() {
        val body = section("pasting", R.string.section_pasting, openByDefault = false) {
            getString(R.string.pasting_summary, onOff(KeyboardSettings.cleanPastedLinks(this)), savedCount)
        }.body
        addSwitch(body, R.string.clean_links_title, R.string.clean_links_summary, R.string.clean_links_body, KeyboardSettings.cleanPastedLinks(this)) {
            KeyboardSettings.setCleanPastedLinks(this, it)
        }
        divider(body)
        addSavedText(body)
    }

    private lateinit var addedInput: EditText
    private lateinit var addedButton: Button
    private lateinit var addedImport: Button
    private lateinit var addedSummary: TextView
    private lateinit var addedToggle: Button
    private lateinit var addedList: LinearLayout
    private var addedExpanded = false
    private var addedCount = 0
    private var learnedCount = 0

    /** Whose words the Added words and Learned words parts show and change: each language keeps its own. */
    private var dataLanguage = LanguageProfile.ENGLISH
    private var dataLanguageRow: View? = null
    private val dataLanguageButtons = ArrayList<Pair<LanguageProfile, Button>>()

    /** Words you added and the ones learned, sites, the typing log, and the one way to forget them all. */
    private fun addYourData() {
        val body = section("data", R.string.section_your_data, openByDefault = false) {
            getString(R.string.data_summary, addedCount, learnedCount)
        }.body
        addDataLanguage(body)
        addBlockedWords(body)
        divider(body)
        addAddedWords(body)
        divider(body)
        addLearnedWords(body)
        divider(body)
        addRememberedSites(body)
        divider(body)
        addPredictionLog(body)
        divider(body)
        addForgetLearnedWords(body)
    }

    /** With two or more languages with words on, a choice of language above the word lists; hidden otherwise. */
    private fun addDataLanguage(parent: LinearLayout) {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dataLanguageRow = group
        group.addView(text(getString(R.string.data_language_title), 15f, R.color.on_surface, bold = true).apply { setPadding(0, dp(6), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        LanguageProfile.WITH_WORDS.forEach { language ->
            val button = button(language.nativeName, primary = false).apply {
                setOnClickListener {
                    dataLanguage = language
                    refreshDataLanguage()
                }
            }
            dataLanguageButtons += language to button
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
        }
        group.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        parent.addView(group)
        divider(group)
        refreshDataLanguage()
    }

    private fun refreshDataLanguage() {
        val offered = KeyboardSettings.languages(this).filter { it.words }
        if (dataLanguage !in offered) dataLanguage = offered.first()
        dataLanguageRow?.visibility = if (offered.size > 1) View.VISIBLE else View.GONE
        dataLanguageButtons.forEach { (language, button) ->
            val chosen = language === dataLanguage
            button.isSelected = chosen
            button.visibility = if (language in offered) View.VISIBLE else View.GONE
            button.setTextColor(getColor(if (chosen) R.color.on_primary else R.color.on_primary_container))
            button.background = rounded(if (chosen) R.color.primary else R.color.primary_container, 22)
        }
        refreshAddedWords()
        refreshLearnedSummary()
        refreshBlockedWords()
    }

    private lateinit var blockedInput: EditText
    private lateinit var blockedButton: Button
    private lateinit var blockedSummary: TextView
    private lateinit var blockedList: LinearLayout
    private lateinit var blockedToggle: Button
    private lateinit var blockedRetry: Button
    private lateinit var blockedReset: Button
    private var blockedExpanded = false

    /** Exclusions live beside learned words, but Forget never removes this deliberate choice. */
    private fun addBlockedWords(parent: LinearLayout) {
        subhead(parent, getString(R.string.blocked_words_title), getString(R.string.blocked_words_details), topMargin = 4)
        parent.addView(text(getString(R.string.blocked_words_summary), 13f, R.color.on_surface_variant))
        val entry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        blockedInput = EditText(this).apply {
            hint = getString(R.string.blocked_words_input)
            setSingleLine(true)
            minHeight = dp(48)
            setTextColor(getColor(R.color.on_surface))
            setHintTextColor(getColor(R.color.on_surface_variant))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(BlockedWords.MAX_WORD_UNITS))
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    if (blockedButton.isEnabled) blockedButton.performClick()
                    true
                } else false
            }
        }
        blockedButton = button(getString(R.string.blocked_words_block), primary = false).apply {
            setOnClickListener {
                val chosen = dataLanguage
                val store = BlockedWordStore.get(this@SettingsActivity, chosen)
                val word = BlockedWords.normalize(blockedInput.text.toString())
                when {
                    word == null -> blockedInput.error = getString(R.string.blocked_words_invalid)
                    word in store.words -> blockedInput.error = getString(R.string.blocked_words_exists)
                    store.words.size >= BlockedWords.MAX_WORDS -> blockedInput.error = getString(R.string.blocked_words_full, BlockedWords.MAX_WORDS)
                    else -> {
                        store.change(word) { success ->
                            if (!isDestroyed && chosen == dataLanguage) {
                                if (success) { blockedInput.text.clear(); blockedInput.error = null }
                                refreshBlockedWords()
                            }
                        }
                        refreshBlockedWords()
                    }
                }
            }
        }
        entry.addView(blockedInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        entry.addView(blockedButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        parent.addView(entry)
        blockedSummary = text("", 13f, R.color.on_surface_variant)
        parent.addView(blockedSummary)
        blockedRetry = button(getString(R.string.blocked_words_retry), primary = false).apply {
            setOnClickListener { BlockedWordStore.get(this@SettingsActivity, dataLanguage).reload(); refreshBlockedWords() }
        }
        parent.addView(blockedRetry)
        blockedReset = button(getString(R.string.blocked_words_reset), primary = false).apply {
            setOnClickListener {
                val chosen = dataLanguage
                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.blocked_words_reset)
                    .setMessage(R.string.blocked_words_reset_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.blocked_words_reset) { _, _ ->
                        BlockedWordStore.get(this@SettingsActivity, chosen).change(null, reset = true) {
                            if (!isDestroyed && chosen == dataLanguage) refreshBlockedWords()
                        }
                        refreshBlockedWords()
                    }.show()
            }
        }
        parent.addView(blockedReset)
        blockedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(blockedList)
        blockedToggle = listToggle(parent) { blockedExpanded = !blockedExpanded; refreshBlockedWords() }
        refreshBlockedWords()
    }

    private fun refreshBlockedWords() {
        if (!this::blockedSummary.isInitialized) return
        val chosen = dataLanguage
        val store = BlockedWordStore.get(this, chosen)
        blockedInput.isEnabled = store.ready && !store.busy
        blockedButton.isEnabled = store.ready && !store.busy
        blockedSummary.text = when {
            store.busy -> getString(R.string.blocked_words_loading)
            store.failed -> getString(if (store.ready) R.string.blocked_words_save_failed else R.string.blocked_words_load_failed)
            else -> getString(R.string.blocked_words_count, store.words.size)
        }
        blockedRetry.visibility = if (store.failed && !store.busy) View.VISIBLE else View.GONE
        blockedReset.visibility = blockedRetry.visibility
        blockedList.removeAllViews()
        val words = store.words.sorted()
        showToggle(blockedToggle, words.size, blockedExpanded)
        (if (blockedExpanded) words else words.take(LIST_PREVIEW)).forEach { word ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(word, 15f, R.color.on_surface), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button(getString(R.string.blocked_words_unblock), primary = false).apply {
                contentDescription = getString(R.string.blocked_words_unblock_named, word)
                isEnabled = !store.busy
                setOnClickListener {
                    store.change(word, block = false) { if (!isDestroyed && chosen == dataLanguage) refreshBlockedWords() }
                    refreshBlockedWords()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
            blockedList.addView(row)
        }
        if (store.busy) store.whenLoaded { if (!isDestroyed && chosen == dataLanguage) refreshBlockedWords() }
    }

    /** Explicit additions share the private journal and reset path, without pretending to be typed pairs. */
    private fun addAddedWords(parent: LinearLayout) {
        subhead(parent, getString(R.string.added_words_title),
            resources.getQuantityString(R.plurals.added_words_body, AddedWords.MAX_WORDS, AddedWords.MAX_WORDS), topMargin = 4)
        addedInput = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.added_words_input)
            setTextColor(getColor(R.color.on_surface))
            setHintTextColor(getColor(R.color.on_surface_variant))
            setSingleLine(true)
            minHeight = dp(48)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    if (addedButton.isEnabled) addedButton.performClick()
                    true
                } else false
            }
            // With a long list open, the box also finds a word in it.
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (addedExpanded && addedCount > MAX_ADDED_ROWS) refreshAddedWords()
                }
            })
        }
        val entry = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entry.addView(addedInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addedButton = button(getString(R.string.added_words_add), primary = false).apply {
            setOnClickListener {
                val store = JournalLearningStore.get(this@SettingsActivity, dataLanguage)
                if (!store.ready) return@setOnClickListener
                val word = AddedWords.normalize(addedInput.text.toString(), dataLanguage)
                val blocked = BlockedWordStore.get(this@SettingsActivity, dataLanguage)
                if (!blocked.ready || word?.let { BlockedWords.contains(blocked.words, it) } == true) {
                    addedInput.error = getString(R.string.blocked_words_unblock_first)
                    return@setOnClickListener
                }
                val result = AddedWords.add(store, addedInput.text.toString(), dataLanguage)
                when (result) {
                    AddedWords.Result.INVALID -> addedInput.error = getString(R.string.added_words_invalid)
                    AddedWords.Result.FULL -> addedInput.error = resources.getQuantityString(R.plurals.added_words_full, AddedWords.MAX_WORDS, AddedWords.MAX_WORDS)
                    AddedWords.Result.EXISTS -> addedInput.error = getString(R.string.added_words_exists)
                    AddedWords.Result.ADDED -> {
                        // "LocalType" is kept as typed, so it is suggested with its capitals.
                        val added = AddedWords.words(store.entries()).toHashSet()
                        WordSpellingStore.get(this@SettingsActivity, dataLanguage)
                            .change(word.orEmpty(), WordSpellings.added(addedInput.text.toString())) { it in added }
                        KeyboardSettings.bumpLearnedGeneration(this@SettingsActivity)
                        addedInput.text.clear()
                        addedInput.error = null
                        refreshAddedWords()
                        refreshLearnedSummary()
                        Toast.makeText(this@SettingsActivity, getString(R.string.added_words_added,
                            WordSpellingStore.get(this@SettingsActivity, dataLanguage).display(word.orEmpty()) ?: word), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        entry.addView(addedButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) })
        parent.addView(entry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addedImport = button(getString(R.string.added_words_import), primary = false).apply { setOnClickListener { chooseWordList() } }
        parent.addView(addedImport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(4) })
        parent.addView(text(getString(R.string.added_words_import_hint), 13f, R.color.on_surface_variant).apply { setPadding(0, dp(2), 0, 0) })
        addedSummary = text("", 13f, R.color.on_surface_variant).apply { setPadding(0, dp(8), 0, dp(4)) }
        parent.addView(addedSummary)
        addedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(addedList)
        addedToggle = listToggle(parent) {
            addedExpanded = !addedExpanded
            refreshAddedWords()
        }
    }

    /** The system's file picker, for a word list to add: nothing is read until a file is chosen. */
    private fun chooseWordList() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // Any type: file managers give word lists and exports all sorts of types. The reader checks the contents.
                type = "*/*"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(DOWNLOADS_URI))
            },
            REQUEST_WORD_LIST,
        )
    }

    /**
     * Adds the words of the chosen file to the language being shown, as the Add button would one at a time: the
     * same validation, blocked words left out, capitals kept. The file is read off the main thread and never kept.
     */
    private fun importWordList(source: Uri) {
        val language = dataLanguage
        addedImport.isEnabled = false
        screenScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(source)?.use(WordListFile::read) }.getOrNull() ?: WordListFile.Read.Unreadable
            }
            if (isDestroyed) return@launch
            addedImport.isEnabled = true
            val store = JournalLearningStore.get(this@SettingsActivity, language)
            val blocked = BlockedWordStore.get(this@SettingsActivity, language)
            val message = when {
                read is WordListFile.Read.TooLarge -> getString(R.string.added_words_import_too_large)
                read !is WordListFile.Read.Text || !store.ready || !blocked.ready -> getString(R.string.added_words_import_unreadable)
                else -> {
                    val result = AddedWords.addAll(store, WordListImport.parse(read.text), language) { BlockedWords.contains(blocked.words, it) }
                    if (result.added.isNotEmpty()) {
                        val added = AddedWords.words(store.entries()).toHashSet()
                        WordSpellingStore.get(this@SettingsActivity, language)
                            .changeAll(result.added.map { (word, typed) -> word to WordSpellings.added(typed) }) { it in added }
                        KeyboardSettings.bumpLearnedGeneration(this@SettingsActivity)
                        refreshAddedWords()
                        refreshLearnedSummary()
                    }
                    importSummary(result)
                }
            }
            android.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.added_words_import)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** What an import did, in up to three sentences: added, left out, and what the limit turned away. */
    private fun importSummary(result: AddedWords.Import): String {
        val left = result.existing + result.invalid + result.blocked
        return listOfNotNull(
            if (result.added.isEmpty()) getString(R.string.added_words_import_none)
            else resources.getQuantityString(R.plurals.added_words_imported, result.added.size, result.added.size),
            if (left > 0) resources.getQuantityString(R.plurals.added_words_import_skipped, left, left) else null,
            if (result.full > 0) resources.getQuantityString(R.plurals.added_words_import_full, result.full, result.full, AddedWords.MAX_WORDS) else null,
        ).joinToString(" ")
    }

    /** Show all (n) or Show fewer under a list that shows its first three rows. */
    private fun listToggle(parent: LinearLayout, onClick: () -> Unit): Button {
        val toggle = button("", primary = false).apply { setOnClickListener { onClick() } }
        parent.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(4) })
        return toggle
    }

    private fun showToggle(toggle: Button, count: Int, expanded: Boolean) {
        toggle.visibility = if (count > LIST_PREVIEW) View.VISIBLE else View.GONE
        toggle.text = speak(if (expanded) getString(R.string.show_fewer) else getString(R.string.show_all, count))
    }

    private fun refreshAddedWords() {
        if (!this::addedSummary.isInitialized) return
        val store = JournalLearningStore.get(this, dataLanguage)
        addedButton.isEnabled = store.ready
        if (!store.ready) {
            addedSummary.text = getString(R.string.learned_words_loading)
            addedToggle.visibility = View.GONE
            addedList.visibility = View.GONE
            store.whenReady { if (!isDestroyed) refreshAddedWords() }
            return
        }
        val words = AddedWords.words(store.entries())
        addedCount = words.size
        addedSummary.text = resources.getQuantityString(R.plurals.added_words_count, words.size, words.size, AddedWords.MAX_WORDS)
        showToggle(addedToggle, words.size, addedExpanded)
        addedList.removeAllViews()
        addedList.visibility = if (words.isNotEmpty()) View.VISIBLE else View.GONE
        // A long list is narrowed by what is in the Add word box, and never draws more than MAX_ADDED_ROWS rows.
        val long = addedExpanded && words.size > MAX_ADDED_ROWS
        val shown = if (long) AddedWords.shown(words, addedInput.text.toString(), MAX_ADDED_ROWS)
            else AddedWords.Shown(if (addedExpanded) words else words.take(LIST_PREVIEW), words.size)
        if (long) addedList.addView(text(getString(R.string.added_words_showing, shown.rows.size, shown.matching), 13f, R.color.on_surface_variant)
            .apply { setPadding(0, 0, 0, dp(4)) })
        shown.rows.forEach { word ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val spelling = WordSpellingStore.get(this, dataLanguage)
            val shown = spelling.display(word) ?: word
            row.addView(text(shown, 15f, R.color.on_surface), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(removeButton(getString(R.string.added_words_remove, shown)) {
                PersonalLearningStore.get(this@SettingsActivity, dataLanguage).removeWord(word)
                spelling.change(word, WordSpellings.Change.Forget)
                // Remove learned uses too, so this word cannot linger as a private completion.
                store.entries().filter { it.second == word }.forEach { store.remove(it.first, it.second) }
                KeyboardSettings.bumpLearnedGeneration(this@SettingsActivity)
                refreshAddedWords()
                refreshLearnedSummary()
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addedList.addView(row)
        }
        sections.forEach { it.refresh() }
    }

    private fun removeButton(description: String, onClick: () -> Unit) = Button(this).apply {
        text = "✕"
        isAllCaps = false
        background = null
        setTextColor(getColor(R.color.on_surface_variant))
        contentDescription = description
        setOnClickListener { onClick() }
    }

    private fun addLearnedWords(parent: LinearLayout) {
        subhead(parent, getString(R.string.learned_words_title), getString(R.string.learned_words_body), topMargin = 0)
        learnedSummary = text("", 14f, R.color.on_surface_variant).apply { setPadding(0, dp(2), 0, dp(6)) }
        parent.addView(learnedSummary)
        learnedList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        parent.addView(learnedList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        learnedToggle = listToggle(parent) {
            learnedExpanded = !learnedExpanded
            refreshLearnedSummary()
        }
    }

    /**
     * Forget learned words empties every list above it in this section (and recent emoji), so it closes the
     * section with the whole list of what goes and what stays beside the button.
     */
    private fun addForgetLearnedWords(parent: LinearLayout) {
        parent.addView(text(getString(R.string.forget_learned_body), 13f, R.color.on_surface_variant).apply { setPadding(0, dp(4), 0, dp(10)) })
        parent.addView(button(getString(R.string.learned_words_clear), primary = false).apply {
            setOnClickListener { confirmLosingLog(R.string.learned_words_forget_confirm_title, R.string.learned_words_forget_confirm_body, R.string.learned_words_forget_confirm_action, ::forgetLearnedWords) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun forgetLearnedWords() {
        // The typing log goes with the learned words, as the privacy text promises.
        val hadLog = PredictionLog.status(this).bytes > 0
        // Every language's learning goes, whichever languages are on now.
        try {
            LanguageProfile.WITH_WORDS.forEach { PersonalLearningStore.get(this, it).clear() }
        } catch (_: IllegalStateException) {
            Toast.makeText(this, R.string.personal_learning_forget_failed, Toast.LENGTH_LONG).show()
            return
        }
        LanguageProfile.WITH_WORDS.forEach {
            JournalLearningStore.get(this, it).clear()
            TouchOffsetStore(this, it).clear()
            WordSpellingStore.get(this, it).clear()
        }
        PreferenceModelCacheStore(this).clear()
        // Recent emoji are history too. Skin tones and saved text stay, as the card says.
        PreferenceEmojiStore(this).forgetRecent()
        // So are the sites typed. The keyboard shares this list, so an open one stops offering them at once.
        DomainStore.forget(this) { if (!isDestroyed) refreshSites() }
        KeyboardSettings.bumpLearnedGeneration(this)
        PredictionLog.clear(this) { runOnUiThread { refreshLogStatus() } }
        refreshAddedWords()
        refreshLearnedSummary()
        Toast.makeText(this, if (hadLog) R.string.learned_words_and_log_cleared else R.string.learned_words_cleared, Toast.LENGTH_SHORT).show()
    }

    /**
     * Runs [clear] at once when the typing log holds nothing, and asks first when it holds something. A log is
     * the one thing on this screen that cannot be made again, and both Clear log and Forget learned words
     * removed it on a single tap. The question says how much is there and that Export comes first.
     */
    private fun confirmLosingLog(title: Int, body: Int, action: Int, clear: () -> Unit) {
        val status = PredictionLog.status(this)
        if (status.bytes == 0L) return clear()
        val size = ModelKitBridge.formatBytes(status.bytes)
        val holds = if (status.startedAtMs > 0L) getString(R.string.prediction_log_status_size, size,
            DateUtils.formatDateTime(this, status.startedAtMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH))
        else getString(R.string.prediction_log_status_size_undated, size)
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(body, holds))
            .setNegativeButton(R.string.prediction_log_confirm_keep, null)
            .setPositiveButton(action) { _, _ -> clear() }
            .show()
    }

    private lateinit var learnedToggle: Button
    private lateinit var learnedList: LinearLayout
    private var learnedExpanded = false

    private lateinit var sitesSummary: TextView
    private lateinit var sitesToggle: Button
    private lateinit var sitesList: LinearLayout
    private var sitesExpanded = false

    /** The hosts of addresses typed in full: the switch, how many are held, and each with a way to remove it. */
    private fun addRememberedSites(parent: LinearLayout) {
        addSwitch(parent, R.string.sites_title, R.string.sites_summary, R.string.sites_body, KeyboardSettings.rememberDomains(this)) {
            KeyboardSettings.setRememberDomains(this, it)
        }
        sitesSummary = text("", 13f, R.color.on_surface_variant).apply { setPadding(0, dp(4), 0, dp(4)) }
        parent.addView(sitesSummary)
        sitesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(sitesList)
        sitesToggle = listToggle(parent) {
            sitesExpanded = !sitesExpanded
            refreshSites()
        }
        refreshSites()
    }

    private fun refreshSites() {
        if (!this::sitesSummary.isInitialized) return
        DomainStore.open(this) { sites ->
            if (isDestroyed) return@open
            val entries = sites.entries
            sitesSummary.text = if (entries.isEmpty()) getString(R.string.sites_none)
            else resources.getQuantityString(R.plurals.sites_count, entries.size, entries.size, RememberedDomains.MAX)
            showToggle(sitesToggle, entries.size, sitesExpanded)
            sitesList.removeAllViews()
            sitesList.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
            (if (sitesExpanded) entries else entries.take(LIST_PREVIEW)).forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                label.addView(text(entry.host, 15f, R.color.on_surface))
                // One typed once is held but not offered yet; say so, or its absence from the strip looks like a fault.
                if (entry.count < RememberedDomains.OFFER_AFTER) label.addView(text(getString(R.string.sites_typed_once), 12f, R.color.on_surface_variant))
                row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(removeButton(getString(R.string.sites_remove, entry.host)) {
                    sites.remove(entry.host)
                    refreshSites()
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                sitesList.addView(row)
            }
        }
    }

    private lateinit var logStatus: TextView
    private lateinit var logActions: LinearLayout

    /** The opt-in typing log: its switch, what it holds so far, and the only two ways it leaves or goes. */
    private fun addPredictionLog(parent: LinearLayout) {
        addSwitch(parent, R.string.prediction_log_title, R.string.prediction_log_summary, R.string.prediction_log_body,
            KeyboardSettings.predictionLog(this)) {
            KeyboardSettings.setPredictionLog(this, it)
        }
        logStatus = text("", 14f, R.color.on_surface_variant).apply {
            setPadding(0, dp(4), 0, dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        parent.addView(logStatus)
        logActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        logActions.addView(button(getString(R.string.prediction_log_export), primary = false).apply {
            setOnClickListener { exportLog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        logActions.addView(button(getString(R.string.prediction_log_clear), primary = false).apply {
            setOnClickListener {
                confirmLosingLog(R.string.prediction_log_confirm_title, R.string.prediction_log_confirm_body, R.string.prediction_log_clear) {
                    PredictionLog.clear(this@SettingsActivity) {
                        runOnUiThread {
                            refreshLogStatus()
                            Toast.makeText(this@SettingsActivity, R.string.prediction_log_cleared, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(logActions)
        refreshLogStatus()
    }

    private lateinit var savedSummary: TextView
    private lateinit var savedClear: Button
    private lateinit var savedInput: EditText
    private lateinit var savedToggle: Button
    private lateinit var savedList: LinearLayout
    private var savedExpanded = false

    /**
     * Saved text: how much is kept, a place to type a new one, and each item with its shortcut word. The field
     * asks the keyboard for no suggestions and no learning, so what is typed here goes nowhere but the list.
     * Forget learned words leaves it alone; items go one at a time here or from the keyboard's panel.
     */
    private fun addSavedText(parent: LinearLayout) {
        subhead(parent, getString(R.string.saved_text), getString(R.string.saved_text_body), topMargin = 0)
        savedSummary = text("", 14f, R.color.on_surface_variant).apply { setPadding(0, 0, 0, dp(6)) }
        parent.addView(savedSummary)
        savedInput = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.saved_text_new_hint)
            setTextColor(getColor(R.color.on_surface))
            setHintTextColor(getColor(R.color.on_surface_variant))
            minHeight = dp(48)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(android.text.InputFilter.LengthFilter(SavedTexts.MAX_CHARS))
        }
        parent.addView(savedInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(button(getString(R.string.saved_text_new_save), primary = false).apply {
            setOnClickListener {
                SavedTextStore.open(this@SettingsActivity) { texts ->
                    if (isDestroyed) return@open
                    if (!texts.save(savedInput.text.toString().trim())) {
                        savedInput.error = getString(R.string.saved_text_new_invalid)
                        return@open
                    }
                    savedInput.text.clear()
                    savedInput.error = null
                    refreshSavedSummary()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        savedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(savedList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        savedToggle = listToggle(parent) {
            savedExpanded = !savedExpanded
            refreshSavedSummary()
        }
        savedClear = button(getString(R.string.saved_text_clear), primary = false).apply {
            setOnClickListener {
                // The keyboard shares this list, so an open Saved text panel empties too.
                SavedTextStore.open(this@SettingsActivity) { texts ->
                    texts.clear()
                    if (isDestroyed) return@open
                    refreshSavedSummary()
                    Toast.makeText(this@SettingsActivity, R.string.saved_text_cleared, Toast.LENGTH_SHORT).show()
                }
            }
        }
        parent.addView(savedClear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        refreshSavedSummary()
    }

    private fun refreshSavedSummary() {
        if (!this::savedSummary.isInitialized) return
        SavedTextStore.open(this) { texts ->
            if (isDestroyed) return@open
            val count = texts.items.size
            savedCount = count
            savedSummary.text = if (count == 0) getString(R.string.saved_text_none)
            else resources.getQuantityString(R.plurals.saved_text_count, count, count)
            // Nothing to remove: no button for it.
            savedClear.visibility = if (count > 0) View.VISIBLE else View.GONE
            showToggle(savedToggle, count, savedExpanded)
            savedList.removeAllViews()
            savedList.visibility = if (count > 0) View.VISIBLE else View.GONE
            (if (savedExpanded) texts.items else texts.items.take(LIST_PREVIEW)).forEach { item -> savedList.addView(savedRow(texts, item)) }
            sections.forEach { it.refresh() }
        }
    }

    /** One saved text: a preview, its shortcut word (or the way to give it one), and a remove button. */
    private fun savedRow(texts: SavedTexts, item: String): View {
        val preview = SavedTexts.preview(item, 120)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label.addView(text(preview, 15f, R.color.on_surface).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val code = texts.codes[item]
        label.addView(Button(this).apply {
            text = if (code == null) getString(R.string.saved_text_shortcut_add) else getString(R.string.saved_text_shortcut, code)
            isAllCaps = false
            background = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.primary))
            contentDescription = if (code == null) getString(R.string.saved_text_shortcut_add_for, preview)
                else getString(R.string.saved_text_shortcut_change, code, preview)
            setOnClickListener { askShortcut(texts, item) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(removeButton(getString(R.string.saved_text_remove, preview)) {
            // The panel has Undo for this; here there is none, so ask.
            android.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.saved_text_remove_confirm_title)
                .setMessage(preview)
                .setNegativeButton(R.string.saved_text_remove_confirm_keep, null)
                .setPositiveButton(R.string.saved_text_remove_confirm_action) { _, _ ->
                    texts.remove(item)
                    texts.forgetLastChange()
                    refreshSavedSummary()
                }
                .show()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return row
    }

    /** Asks for [item]'s shortcut word. The dialog stays open on a word that can't be used and says why. */
    private fun askShortcut(texts: SavedTexts, item: String) {
        val current = texts.codes[item]
        val input = EditText(this).apply {
            hint = getString(R.string.saved_text_shortcut_hint)
            setSingleLine(true)
            setText(current.orEmpty())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(SavedTexts.MAX_CODE))
        }
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input)
        }
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.saved_text_shortcut_title)
            .setMessage(getString(R.string.saved_text_shortcut_body, SavedTexts.preview(item, 60)))
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_text_shortcut_save, null)
        if (current != null) builder.setNeutralButton(R.string.saved_text_shortcut_remove) { _, _ ->
            texts.setCode(item, "")
            refreshSavedSummary()
        }
        val dialog = builder.create()
        val save = {
            when (texts.setCode(item, input.text.toString())) {
                SavedTexts.CodeResult.INVALID -> input.error = getString(R.string.saved_text_shortcut_invalid, SavedTexts.MIN_CODE, SavedTexts.MAX_CODE)
                SavedTexts.CodeResult.TAKEN -> input.error = getString(R.string.saved_text_shortcut_taken)
                else -> {
                    dialog.dismiss()
                    refreshSavedSummary()
                }
            }
        }
        // Set after showing, so a word that can't be used does not close the dialog.
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() } }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        dialog.show()
    }

    private fun refreshLogStatus() {
        if (!this::logStatus.isInitialized) return
        val status = PredictionLog.status(this)
        val size = ModelKitBridge.formatBytes(status.bytes)
        logStatus.text = when {
            status.full -> getString(R.string.prediction_log_status_full)
            status.stale -> getString(R.string.prediction_log_status_stale)
            status.bytes == 0L -> getString(R.string.prediction_log_status_empty)
            status.startedAtMs > 0L -> getString(R.string.prediction_log_status_size, size,
                DateUtils.formatDateTime(this, status.startedAtMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH))
            else -> getString(R.string.prediction_log_status_size_undated, size)
        }
        // Nothing to export or clear yet: no buttons for it.
        logActions.visibility = if (status.bytes > 0) View.VISIBLE else View.GONE
    }

    /** Lets the person pick where the log goes; the copy itself runs on the log's worker. */
    private fun exportLog() {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/tab-separated-values"
                putExtra(Intent.EXTRA_TITLE, "localtype-typing-log-$stamp.tsv")
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(DOWNLOADS_URI))
            },
            REQUEST_LOG_EXPORT,
        )
    }

    private fun refreshLearnedSummary() {
        if (!this::learnedSummary.isInitialized) return
        val store = JournalLearningStore.get(this, dataLanguage)
        if (!store.ready) {
            learnedSummary.text = getString(R.string.learned_words_loading)
            learnedToggle.visibility = View.GONE
            learnedList.visibility = View.GONE
            store.whenReady { if (!isDestroyed) refreshLearnedSummary() }
            return
        }
        val entries = store.entries().filter { it.first != AddedWords.CONTEXT }.sortedByDescending { it.third }
        val count = entries.size
        learnedCount = count
        learnedSummary.text = if (count == 0) getString(R.string.learned_words_empty)
        else resources.getQuantityString(R.plurals.learned_words_count, count, count)
        showToggle(learnedToggle, count, learnedExpanded)
        learnedList.removeAllViews()
        learnedList.visibility = if (count > 0) View.VISIBLE else View.GONE
        entries.take(if (learnedExpanded) MAX_LEARNED_ROWS else LIST_PREVIEW).forEach { (previous, next, uses) ->
            val pair = if (previous == AdaptiveNgramModel.START) getString(R.string.learned_words_start, next) else "$previous → $next"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(pair, 15f, R.color.on_surface), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text(uses.toString(), 13f, R.color.on_surface_variant).apply { setPadding(dp(8), 0, dp(8), 0) })
            row.addView(removeButton(getString(R.string.learned_words_forget_one, pair)) {
                PersonalLearningStore.get(this@SettingsActivity, dataLanguage).removePair(previous, next)
                store.remove(previous, next)
                KeyboardSettings.bumpLearnedGeneration(this@SettingsActivity)
                refreshLearnedSummary()
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            learnedList.addView(row)
        }
        if (learnedExpanded && count > MAX_LEARNED_ROWS) {
            learnedList.addView(text(getString(R.string.learned_words_more, count - MAX_LEARNED_ROWS), 13f, R.color.on_surface_variant))
        }
        sections.forEach { it.refresh() }
    }

    // Extra suggestions and proofreading

    private var nanoState: TextView? = null
    /** The last Gemini Nano status; the progress poll skips the Nano check and reuses it. */
    private var lastNanoState: FoundationModelStatus? = null
    private var modelsSummary = ""
    private var nanoPrepare: Button? = null
    /** False once the phone is found to have too little memory for the Gemma download ([GemmaFit]). */
    private var gemmaFits = true
    private var gemmaState: TextView? = null
    private var gemmaImport: Button? = null
    private var gemmaDownload: Button? = null
    private var gemmaCancel: Button? = null
    private var gemmaRemove: Button? = null
    private var modelRemovalError: String? = null
    private var modelSharingError: String? = null

    /** Download or import progress, announced once when it starts and then updated silently. */
    private enum class Ongoing { DOWNLOAD, IMPORT }
    private var announced: Ongoing? = null

    /** Gemini Nano and Gemma: when to ask them, the proofreading shortcut, and setting each one up. */
    private fun addModels() {
        val installer = FoundationModelProviderFactory.installer(this)
        val downloader = FoundationModelProviderFactory.systemDownloader(this)
        modelsSummary = getString(R.string.models_summary_none)
        modelsSection = section("models", R.string.extra_models_title, openByDefault = false) { modelsSummary }
        val controls = modelsSection.body
        controls.addView(text(getString(R.string.extra_models_intro), 14f, R.color.on_surface_variant).apply { setPadding(0, dp(6), 0, dp(4)) })
        addSwitch(controls, R.string.sparkle_button_title, R.string.sparkle_button_summary, R.string.sparkle_button_body,
            KeyboardSettings.sparkleButton(this)) {
            KeyboardSettings.setSparkleButton(this, it)
        }
        addSwitch(controls, R.string.model_on_tap_title, R.string.model_on_tap_summary, R.string.model_on_tap_body, KeyboardSettings.modelOnTap(this)) {
            KeyboardSettings.setModelOnTap(this, it)
        }
        addSwitch(controls, R.string.keep_model_loaded_title, R.string.keep_model_loaded_summary, R.string.keep_model_loaded_body,
            KeyboardSettings.keepModelLoaded(this)) {
            KeyboardSettings.setKeepModelLoaded(this, it)
        }
        // LocalLauncher may borrow Gemma only where Android can check its signing key (knownSigner, Android 12).
        if (ModelKitBridge.sharingOffered(android.os.Build.VERSION.SDK_INT)) {
            lateinit var sharingRow: SwitchRow
            sharingRow = addSwitch(controls, R.string.share_model_title, R.string.share_model_summary, R.string.share_model_body,
                ModelKitBridge.sharingEnabled(this)) { wanted ->
                // The generic row toggles first; show only actual authorization while the file write is pending.
                sharingRow.switch.isChecked = ModelKitBridge.sharingEnabled(this)
                sharingRow.row.isEnabled = false
                modelSharingError = null
                screenScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ModelKitBridge.setSharingEnabled(this@SettingsActivity, wanted) }
                    }
                    val actual = ModelKitBridge.sharingEnabled(this@SettingsActivity)
                    sharingRow.switch.isChecked = actual
                    sharingRow.row.isEnabled = true
                    if (result.isFailure) {
                        modelSharingError = "Couldn't save sharing. Sharing is ${if (actual) "on" else "off"}. Try again."
                        Toast.makeText(this@SettingsActivity, modelSharingError, Toast.LENGTH_LONG).show()
                    }
                    refreshModelState()
                }
            }
        }
        // Proofreading needs a model, so its shortcut sits with them.
        addSwitch(controls, R.string.triple_space_proofread_title, R.string.triple_space_proofread_summary, R.string.triple_space_proofread_body,
            KeyboardSettings.tripleSpaceProofread(this)) {
            KeyboardSettings.setTripleSpaceProofread(this, it)
        }

        if (BuildConfig.INCLUDES_NANO) {
            subhead(controls, "Gemini Nano")
            nanoState = text("Checking…", 14f, R.color.on_surface_variant)
            controls.addView(nanoState)
            nanoPrepare = button("Download Gemini Nano", primary = false).apply {
                visibility = View.GONE
                setOnClickListener {
                    isEnabled = false
                    nanoState?.text = "Downloading through Android. This can take a few minutes on Wi-Fi."
                    screenScope.launch {
                        withContext(Dispatchers.Default) { downloader.download() }
                        isEnabled = true
                        refreshModelState()
                    }
                }
            }
            controls.addView(nanoPrepare, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        subhead(controls, "Gemma")
        gemmaState = text("Checking…", 14f, R.color.on_surface_variant).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        controls.addView(gemmaState)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        installer.downloadableModel?.let { pinned ->
            gemmaDownload = button("Download ${pinned.displayName} (${ModelKitBridge.formatBytes(pinned.bytes)})", primary = true).apply {
                setOnClickListener { confirmDownload(pinned) }
            }
            buttons.addView(gemmaDownload, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            gemmaCancel = button("Cancel download", primary = false).apply {
                visibility = View.GONE
                setOnClickListener {
                    FoundationModelProviderFactory.installer(this@SettingsActivity).cancelDownload(this@SettingsActivity)
                    refreshModelState()
                }
            }
            buttons.addView(gemmaCancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        gemmaImport = button("Use a model file instead", primary = false).apply {
            setOnClickListener { chooseModelFile() }
        }
        buttons.addView(gemmaImport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        gemmaRemove = button("Remove Gemma", primary = false).apply {
            visibility = View.GONE
            setOnClickListener {
                isEnabled = false
                modelRemovalError = null
                showGemmaState("Removing Gemma…")
                screenScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { FoundationModelProviderFactory.installer(this@SettingsActivity).remove() }
                    }
                    modelRemovalError = if (result.isFailure) "Some Gemma files couldn't be removed. Try Remove again." else null
                    gemmaRemove?.isEnabled = true
                    refreshModelState()
                }
            }
        }
        buttons.addView(gemmaRemove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        controls.addView(buttons)
        refreshModelState()
    }

    private fun confirmDownload(pinned: PinnedModel) {
        val wifiOnly = android.widget.CheckBox(this).apply {
            text = "Unmetered network"
            isChecked = true
            setPadding(dp(4), 0, 0, 0)
        }
        // Only the Android 14+ user-initiated job has a notification; earlier versions run an ordinary job.
        val notified = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val background = if (notified) "The download continues in the background with a notification, "
        else "The download continues in the background when Android allows it (progress shows here), "
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(text(
                "This downloads ${ModelKitBridge.formatBytes(pinned.bytes)} from Hugging Face (${pinned.displayName}, Apache 2.0). " +
                    "Nothing you type is sent anywhere. " + background +
                    "resumes if it's interrupted, and is checked before use.",
                15f, R.color.on_surface_variant,
            ))
            addView(wifiOnly, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(8) })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Download ${pinned.displayName}?")
            .setView(body)
            .setNegativeButton("Not now", null)
            .setPositiveButton("Download") { _, _ ->
                // Only the Android 14+ job posts a progress notification, and it needs this permission;
                // the download runs either way.
                if (notified &&
                    checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQUEST_NOTIFICATIONS)
                }
                showGemmaState("Starting…")
                screenScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        FoundationModelProviderFactory.installer(this@SettingsActivity).startDownload(this@SettingsActivity, wifiOnly.isChecked)
                    }
                    when (val error = result.exceptionOrNull()) {
                        null -> refreshModelState()
                        is NetworkPermissionDenied -> showNetworkPermissionHelp(error.message.orEmpty())
                        else -> showGemmaState(error.message ?: "Couldn't start the download.")
                    }
                }
            }
            .show()
    }

    private fun showNetworkPermissionHelp(message: String) {
        showGemmaState(message)
        android.app.AlertDialog.Builder(this)
            .setTitle("Can't reach Hugging Face")
            .setMessage("$message\n\nThis download needs network access. LocalType also goes online when you tap Check for updates. Neither sends your typing.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .show()
    }

    private val progressPoller = object : Runnable {
        override fun run() {
            refreshModelState(includeNano = false)
            content.postDelayed(this, 750)
        }
    }

    override fun onStart() {
        super.onStart()
        content.post(progressPoller)
    }

    override fun onStop() {
        content.removeCallbacks(progressPoller)
        super.onStop()
    }

    private fun chooseModelFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                // Start in Downloads, where the browser saved the model.
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(DOWNLOADS_URI))
            },
            REQUEST_MODEL_FILE,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQUEST_MODEL_CHAT) return showModelSetup()
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQUEST_MODEL_FILE -> importModelFile(uri)
            REQUEST_WORD_LIST -> importWordList(uri)
            REQUEST_LOG_EXPORT -> PredictionLog.export(this, uri) { ok ->
                runOnUiThread {
                    Toast.makeText(this, if (ok) R.string.prediction_log_exported else R.string.prediction_log_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun importModelFile(source: Uri) {
        val installer = FoundationModelProviderFactory.installer(this)
        modelsSection.setOpen(true, remember = false)
        showImportProgress(0)
        // The copy belongs to the process, not this screen: a recreated screen (rotation, theme) reads
        // its progress from the installer, so the callbacks only touch a screen that still exists.
        installer.install(
            applicationContext, source,
            onProgress = { percent -> runOnUiThread { if (!isDestroyed) showImportProgress(percent) } },
            onResult = { result ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    gemmaImport?.isEnabled = true
                    result.fold(
                        onSuccess = { refreshModelState() },
                        onFailure = { showGemmaState(it.message ?: "Import failed.") },
                    )
                }
            },
        )
    }

    /** A running import's status; [percent] is 0 while the file is still being checked. */
    private fun showImportProgress(percent: Int) {
        if (percent <= 0) showGemmaState("Checking the file…") else showGemmaState("Importing… $percent%", Ongoing.IMPORT)
        gemmaImport?.isEnabled = false
        gemmaDownload?.visibility = View.GONE
        gemmaRemove?.visibility = View.GONE
    }

    /**
     * Sets the Gemma status line, a polite live region. Ongoing progress is announced when it starts;
     * later updates change the text silently, and focusing the line still reads the current value.
     */
    private fun showGemmaState(message: String, ongoing: Ongoing? = null) {
        val view = gemmaState ?: return
        val quiet = ongoing != null && ongoing == announced
        announced = ongoing
        // Silence the region before a quiet update. Leaving progress, the new text goes in while still
        // silent and switching back to polite announces it once, without the stale progress line.
        if (quiet) view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        // Older Android announces a live region on every setText, even with the same text.
        if (view.text.toString() != message) view.text = message
        if (!quiet) view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun refreshModelState(includeNano: Boolean = true) {
        if (!this::modelsSection.isInitialized) return
        screenScope.launch {
            if (includeNano && BuildConfig.INCLUDES_NANO) {
                val downloader = FoundationModelProviderFactory.systemDownloader(this@SettingsActivity)
                val state = withContext(Dispatchers.Default) { downloader.status() }
                lastNanoState = state
                nanoState?.text = nanoMessage(state)
                nanoPrepare?.visibility = if (state == FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE) View.VISIBLE else View.GONE
            }
            val installer = FoundationModelProviderFactory.installer(this@SettingsActivity)
            installer.importProgress?.let { percent ->
                if (!modelProgressChecked) {
                    modelProgressChecked = true
                    modelsSection.setOpen(true, remember = false)
                }
                showImportProgress(percent)
                return@launch
            }
            gemmaImport?.isEnabled = true
            val model = withContext(Dispatchers.IO) { installer.installedModel() }
            val progress = withContext(Dispatchers.IO) { installer.downloadProgress(this@SettingsActivity) }
            // Reveal an existing job once on entry. A person's later collapse stays collapsed.
            if (!modelProgressChecked) {
                modelProgressChecked = true
                if (progress.isActive) modelsSection.setOpen(true, remember = false)
            }
            // With no copy of its own, LocalLauncher's copy counts: one copy of Gemma per phone.
            val borrowed = model == null && !progress.isActive && withContext(Dispatchers.IO) { installer.sharedFromPeer() }
            // Both apps downloaded it: one copy is enough, since LocalType can use LocalLauncher's. It only peeks.
            val twoCopies = BuildConfig.INCLUDES_GEMMA && model != null && !progress.isActive && withContext(Dispatchers.IO) { installer.peerHasCopyToo() }
            // A cancelled, failed or interrupted download keeps its partial file for resuming.
            val partialBytes = withContext(Dispatchers.IO) { installer.partialDownloadBytes() }
            val modelFilesRemain = withContext(Dispatchers.IO) { installer.hasModelFiles() }
            val pinned = installer.downloadableModel
            // A phone without the memory for the download is told so, and is not offered a button that can only fail.
            val memory = android.app.ActivityManager.MemoryInfo()
            val activityManager = getSystemService(android.app.ActivityManager::class.java)
            activityManager.getMemoryInfo(memory)
            val fits = pinned == null || GemmaFit.fits(pinned.bytes, memory.totalMem, activityManager.isLowRamDevice)
            gemmaFits = fits
            if (includeNano && BuildConfig.INCLUDES_NANO) lastNanoState?.let { nanoState?.text = nanoMessage(it) }
            val kept = if (pinned != null && partialBytes > 0) {
                "${ModelKitBridge.formatBytes(partialBytes)} of ${ModelKitBridge.formatBytes(pinned.bytes)} downloaded"
            } else null
            val message = when {
                progress.state == ModelDownloadState.DOWNLOADING ->
                    "Downloading ${ModelKitBridge.formatBytes(progress.bytes)} of ${ModelKitBridge.formatBytes(progress.totalBytes)} " +
                        "(${progress.bytes * 100 / progress.totalBytes.coerceAtLeast(1)}%). You can leave this screen."
                progress.state == ModelDownloadState.WAITING_FOR_NETWORK ->
                    "Waiting for a connection" + if (partialBytes > 0) " (${ModelKitBridge.formatBytes(partialBytes)} saved so far)." else "."
                progress.state == ModelDownloadState.VERIFYING -> "Checking the download…"
                model != null ->
                    "Ready: ${model.name} (${ModelKitBridge.formatBytes(model.sizeBytes)}). " +
                        (if (lastNanoState == FoundationModelStatus.READY) "Used only when Gemini Nano has no answer."
                        else if (KeyboardSettings.modelOnTap(this@SettingsActivity)) "Loads when you tap the sparkle."
                        else "Loads when you pause while typing or tap the sparkle.") +
                        if (twoCopies) " LocalLauncher has the same Gemma, so this phone keeps two copies. One is enough: " +
                            "after Remove Gemma, LocalType uses LocalLauncher's." else ""
                modelFilesRemain -> "Gemma files remain on this phone. Remove Gemma files retries deleting them."
                borrowed -> "Using LocalLauncher's copy of Gemma on this phone, so there's nothing to download."
                progress.state == ModelDownloadState.FAILED ->
                    (progress.message ?: "The download failed. Try again.") + (kept?.let { " $it; Download picks up from there." } ?: "")
                kept != null -> "$kept. Download picks up from there, or discard it to free the space."
                pinned != null && !fits -> GemmaFit.tooSmall(pinned.displayName, pinned.bytes, memory.totalMem)
                pinned != null -> "Optional. Download Gemma to use it for extra suggestions and proofreading."
                else -> "Not installed."
            }
            val failure = listOfNotNull(modelSharingError, modelRemovalError).joinToString(" ")
            showGemmaState(if (failure.isEmpty()) message else "$failure $message",
                if (progress.state == ModelDownloadState.DOWNLOADING && failure.isEmpty()) Ongoing.DOWNLOAD else null)
            gemmaDownload?.visibility = if (model == null && !borrowed && !progress.isActive && fits) View.VISIBLE else View.GONE
            gemmaCancel?.visibility = if (progress.isActive) View.VISIBLE else View.GONE
            gemmaImport?.visibility = if (progress.isActive) View.GONE else View.VISIBLE
            val importLabel = if (model != null) "Replace Gemma" else if (!fits) "Use a smaller model file" else "Use a model file instead"
            gemmaImport?.let { if (it.text.toString() != importLabel) it.text = importLabel }
            // Remove deletes the partial file too, so the same button discards a download nobody will finish.
            gemmaRemove?.visibility = if ((modelFilesRemain || partialBytes > 0) && !progress.isActive) View.VISIBLE else View.GONE
            val removeLabel = when {
                model != null -> "Remove Gemma"
                modelFilesRemain -> "Remove Gemma files"
                else -> "Discard partial download"
            }
            gemmaRemove?.let { if (it.text.toString() != removeLabel) it.text = removeLabel }
            // What the keyboard's sparkle goes by: it shows once there is a model to ask, and settings knows first.
            if (lastNanoState != null || !BuildConfig.INCLUDES_NANO) KeyboardSettings.rememberModelSetUp(this@SettingsActivity,
                SparkleRules.modelSetUp(lastNanoState, gemmaInstalled = model != null, borrowed = borrowed))
            val nano = lastNanoState == FoundationModelStatus.READY
            modelsSummary = getString(when {
                progress.state == ModelDownloadState.DOWNLOADING -> R.string.models_summary_downloading
                nano && model != null -> R.string.models_summary_both
                nano -> R.string.models_summary_nano
                model != null || borrowed -> R.string.models_summary_gemma
                else -> R.string.models_summary_none
            })
            modelsSection.refresh()
        }
    }

    private fun nanoMessage(state: FoundationModelStatus): String = when (state) {
        FoundationModelStatus.READY -> "Ready. Gemini Nano is used first."
        FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE -> "Supported on this phone but not downloaded yet."
        FoundationModelStatus.INITIALIZING -> "Downloading through Android…"
        FoundationModelStatus.NOT_SUPPORTED ->
            "Android does not offer Gemini Nano to apps on this phone." + if (gemmaFits) " You can use Gemma below." else ""
        else -> "Not available right now." + if (gemmaFits) " You can use Gemma below." else ""
    }

    private fun openModelChat() {
        startActivityForResult(Intent(this, ModelChatActivity::class.java), REQUEST_MODEL_CHAT)
    }

    /** Back from the chat's Set up a model: open Extra suggestions and proofreading and bring it into view. */
    private fun showModelSetup() {
        if (!this::modelsSection.isInitialized) return
        modelProgressChecked = true
        modelsSection.setOpen(true, remember = false)
        val heading = modelsSection.heading
        scroll.post { scroll.smoothScrollTo(0, heading.top) }
        TalkBackFocus.moveTo(heading)
    }

    // About

    /** Version and updates, then the walkthrough, the tour and what stays on the phone. */
    private fun addAbout() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val body = section("about", R.string.section_about, openByDefault = false) {
            getString(R.string.about_summary, info.versionName)
        }.body
        addUpdates(body)
        addLink(body, getString(R.string.about_release_notes)) { openInBrowser(ReleaseInfo.CHANGELOG_URL) }
        divider(body)
        addLink(body, getString(R.string.walkthrough_reopen)) {
            startActivity(Intent(this@SettingsActivity, SetupActivity::class.java).putExtra(SetupActivity.REPLAY, true))
        }
        addLink(body, getString(R.string.tour_action)) { openTour() }
        addLink(body, getString(R.string.privacy_title)) { showDetails(getString(R.string.privacy_title), getString(R.string.privacy_body)) }
        divider(body)
        // Each opens in the browser only when tapped; settings itself fetches nothing for them.
        addLink(body, getString(R.string.about_download_page), getString(R.string.about_download_page_summary)) { openInBrowser(ReleaseInfo.PAGE_URL) }
        addLink(body, getString(R.string.about_support), getString(R.string.about_support_summary)) { openInBrowser(ReleaseInfo.SUPPORT_URL) }
        addLink(body, getString(R.string.about_bluesky), getString(R.string.about_bluesky_summary)) { openInBrowser(ReleaseInfo.BLUESKY_URL) }
        addLink(body, getString(R.string.about_website), getString(R.string.about_website_summary)) { openInBrowser(ReleaseInfo.SITE_URL) }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.about_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    private var updateBanner: LinearLayout? = null
    private var updateBannerBody: TextView? = null

    /** At the top of settings when a newer version this phone can install is out; hidden otherwise. */
    private fun addUpdateBanner() {
        val card = card().apply { background = rounded(R.color.primary_container, 18); visibility = View.GONE }
        (card.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        card.addView(text(getString(R.string.update_banner_title), 16f, R.color.on_primary_container, bold = true))
        val body = text("", 14f, R.color.on_primary_container).apply { setPadding(0, dp(4), 0, dp(10)) }
        card.addView(body)
        card.addView(button(getString(R.string.update_banner_open), primary = true).apply {
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseInfo.PAGE_URL))) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(button(getString(R.string.update_banner_later), primary = false).apply {
            setOnClickListener {
                KeyboardSettings.setUpdateDismissedCode(this@SettingsActivity, KeyboardSettings.seenRelease(this@SettingsActivity).code)
                refreshUpdateBanner(announce = false)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        updateBanner = card
        updateBannerBody = body
        refreshUpdateBanner(announce = false)
    }

    private fun installedCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Shows or hides the banner from the last check's answer. [announce]: it came from a check that just finished. */
    private fun refreshUpdateBanner(announce: Boolean) {
        val banner = updateBanner ?: return
        val seen = KeyboardSettings.seenRelease(this)
        val show = UpdateBanner.shows(installedCode(), seen.code, seen.installable, KeyboardSettings.updateDismissedCode(this))
        if (!show) {
            banner.visibility = View.GONE
            return
        }
        val wasShown = banner.visibility == View.VISIBLE
        val body = getString(R.string.update_banner_body, seen.name)
        updateBannerBody?.text = body
        banner.visibility = View.VISIBLE
        // Read in its place at the top when settings opens with it; said once when it arrives during a visit.
        @Suppress("DEPRECATION")
        if (announce && !wasShown) banner.announceForAccessibility(getString(R.string.update_banner_title) + ". " + body)
    }

    /** With Check for updates automatically on and a check due, reads the download page off the main thread. */
    private fun checkForUpdateQuietly() {
        if (!KeyboardSettings.autoUpdateCheck(this)) return
        val now = System.currentTimeMillis()
        if (!UpdateBanner.due(KeyboardSettings.seenRelease(this).checkedAt, now)) return
        KeyboardSettings.setChecked(this, null, false, now)
        screenScope.launch {
            val latest = withContext(Dispatchers.IO) { UpdateChecker.latest() }
            if (isDestroyed) return@launch
            KeyboardSettings.setChecked(this@SettingsActivity, latest, latest?.installable(android.os.Build.SUPPORTED_ABIS) == true, now)
            refreshUpdateBanner(announce = true)
        }
    }

    /** Installed version, and a way to get the newest one from the download page. */
    private fun addUpdates(card: LinearLayout) {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val status = text(getString(R.string.updates_installed, info.versionName, code), 14f, R.color.on_surface_variant).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            background = selectable()
        }
        // The easter egg: five quick taps on the version line open a chat with the on-device model.
        // TalkBack and Switch Access offer the same thing as a named action on the line.
        // The last two taps say how many are left on the line itself, a polite live region. Toasts were
        // tried first: with quick taps Android lost the cancel and left "1 more tap" over the chat.
        val taps = SecretTaps()
        var hint: String? = null
        var beforeHint: CharSequence = status.text
        val clearHint = Runnable {
            // An update check that answered meanwhile keeps its own text.
            if (hint != null && status.text.toString() == hint) status.text = beforeHint
            hint = null
        }
        // The F-Droid distribution has no model to chat with.
        if (BuildConfig.INCLUDES_NANO || BuildConfig.INCLUDES_GEMMA) status.setOnClickListener {
            val left = taps.tap(android.os.SystemClock.elapsedRealtime())
            status.removeCallbacks(clearHint)
            clearHint.run()
            if (left == 0) {
                openModelChat()
            } else if (left <= 2) {
                beforeHint = status.text
                hint = resources.getQuantityString(R.plurals.model_chat_taps_left, left, left)
                status.text = hint
                status.postDelayed(clearHint, HINT_MS)
            }
        }
        if (BuildConfig.INCLUDES_NANO || BuildConfig.INCLUDES_GEMMA) {
            ViewCompat.addAccessibilityAction(status, getString(R.string.model_chat_title)) { _, _ ->
                openModelChat()
                true
            }
        }
        card.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        // The F-Droid distribution is updated by the client that installed it, never by this app.
        if (!BuildConfig.DIRECT_UPDATES) {
            card.addView(text(getString(R.string.updates_client_managed), 14f, R.color.on_surface_variant))
            return
        }
        val openPage = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseInfo.PAGE_URL))) }
        val action = button(getString(R.string.updates_check), primary = false)
        card.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSwitch(card, R.string.auto_update_title, R.string.auto_update_summary, R.string.auto_update_body, KeyboardSettings.autoUpdateCheck(this)) {
            KeyboardSettings.setAutoUpdateCheck(this, it)
            if (it) checkForUpdateQuietly() else updateBanner?.visibility = View.GONE
        }
        var newer: ReleaseInfo? = null
        action.setOnClickListener {
            if (newer != null) return@setOnClickListener openPage()
            action.isEnabled = false
            status.text = getString(R.string.updates_checking)
            screenScope.launch {
                val latest = withContext(Dispatchers.IO) { UpdateChecker.latest() }
                action.isEnabled = true
                if (latest != null) {
                    KeyboardSettings.setChecked(this@SettingsActivity, latest, latest.installable(android.os.Build.SUPPORTED_ABIS), System.currentTimeMillis())
                    refreshUpdateBanner(announce = true)
                }
                when {
                    latest == null -> {
                        status.text = getString(R.string.updates_unreachable)
                        newer = ReleaseInfo(Int.MAX_VALUE, "")
                        action.text = getString(R.string.updates_open_page)
                    }
                    // Nothing to offer: the newer build carries no native code this device's processor can load.
                    latest.isNewerThan(code) && !latest.installable(android.os.Build.SUPPORTED_ABIS) ->
                        status.text = getString(R.string.updates_unsupported, latest.versionName)
                    latest.isNewerThan(code) -> {
                        status.text = getString(R.string.updates_available, latest.versionName)
                        newer = latest
                        action.text = getString(R.string.updates_open_page)
                    }
                    else -> status.text = getString(R.string.updates_current, info.versionName)
                }
            }
        }
    }

    // Building blocks

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(R.color.card, 18)
        content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) })
    }

    /**
     * [value] marked with the screen's language when it differs from the phone's, so TalkBack can read Spanish
     * or Portuguese with a matching voice on a phone set to English, and the other way round.
     */
    private fun speak(value: CharSequence): CharSequence {
        val locale = speakLocale ?: return value
        return SpannableString(value).apply { setSpan(LocaleSpan(locale), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = speak(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String, primary: Boolean) = Button(this).apply {
        text = speak(label)
        minHeight = dp(48)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(getColor(if (primary) R.color.on_primary else R.color.on_primary_container))
        background = rounded(if (primary) R.color.primary else R.color.primary_container, 22)
        stateListAnimator = null
        setPadding(dp(20), 0, dp(20), 0)
    }

    private fun selectable() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(getColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Tips and gestures, by group: a heading and its "gesture|effect" items. */
        val TIP_GROUPS = listOf(
            R.string.tips_group_typing to R.array.tips_typing,
            R.string.tips_group_editing to R.array.tips_editing,
            R.string.tips_group_suggestions to R.array.tips_suggestions,
            R.string.tips_group_saved to R.array.tips_saved,
            R.string.tips_group_more to R.array.tips_more,
        )
        const val REQUEST_MODEL_FILE = 410
        const val MAX_LEARNED_ROWS = 150
        /** Rows a list shows before Show all. */
        const val LIST_PREVIEW = 3
        /** Rows the Added words list draws at most; a longer list is searched from the Add word box. */
        const val MAX_ADDED_ROWS = 100
        const val REQUEST_NOTIFICATIONS = 411
        const val REQUEST_LOG_EXPORT = 412
        const val REQUEST_MICROPHONE = 413
        const val REQUEST_MODEL_CHAT = 414
        const val REQUEST_WORD_LIST = 415
        /** How long the version line shows "2 more taps" before going back to the version. */
        const val HINT_MS = 2_000L
        const val DOWNLOADS_URI = "content://com.android.externalstorage.documents/document/primary%3ADownload"
    }
}
