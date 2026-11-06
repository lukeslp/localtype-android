package dev.localtype.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** First app launch walks through Android setup, a real typing field, then reversible controls. */
class SetupActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var progress: TextView
    private lateinit var heading: TextView
    private lateinit var body: TextView
    private lateinit var actions: LinearLayout
    private lateinit var scroll: ScrollView
    private var step = SetupStep.WELCOME
    private var status = SetupStatus(false, false)
    private var tryInput: EditText? = null
    private var draft = ""
    /** Opened from settings for the tour alone: it ends there, not at the setup controls. */
    private var tourOnly = false
    private val selectionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshStatus() }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SettingsLocale.configuration(base)?.let { applyOverrideConfiguration(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tourOnly = intent.getBooleanExtra(TOUR, false)
        step = SetupFlow.restore(savedInstanceState?.getString("step") ?: when {
            tourOnly -> SetupStep.TOUR.name
            intent.getBooleanExtra(REPLAY, false) -> SetupStep.WELCOME.name
            else -> KeyboardSettings.setupStep(this)
        })
        draft = savedInstanceState?.getString("draft").orEmpty()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, scroll).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        progress = label("", 14f, R.color.on_surface_variant).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        heading = label("", 27f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(12), 0, dp(12))
            ViewCompat.setAccessibilityHeading(this, true)
        }
        body = label("", 17f).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, 0) }
        content.addView(progress)
        content.addView(heading)
        content.addView(body)
        content.addView(actions)
        status = androidStatus()
        render(SetupFlow.reconcile(step, status))
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, selectionObserver)
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(selectionObserver)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("step", step.name)
        outState.putString("draft", tryInput?.text?.toString() ?: draft)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val previous = SetupFlow.previous(step)
        if (previous == null || tourOnly) finish() else render(previous)
    }

    private fun androidStatus(): SetupStatus {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return SetupStatus(
            imm.enabledInputMethodList.any { it.packageName == packageName },
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true,
        )
    }

    private fun refreshStatus() {
        if (!this::actions.isInitialized) return
        val latest = androidStatus()
        if (latest == status) return
        status = latest
        val reconciled = SetupFlow.reconcile(step, status)
        if (reconciled != step) render(reconciled)
    }

    private fun render(requested: SetupStep) {
        // The tour on its own skips the first-run sample: once the keyboard is selected it goes straight to the tips.
        val next = if (tourOnly && requested == SetupStep.TRY) SetupStep.TOUR else requested
        draft = tryInput?.text?.toString() ?: draft
        if (next != SetupStep.TRY && next != SetupStep.TOUR) tryInput?.windowToken?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it, 0)
        }
        tryInput = null
        step = next
        // The tour on its own, from settings, is not setup progress.
        if (!tourOnly) KeyboardSettings.setSetupStep(this, step.name)
        progress.text = if (tourOnly) "" else getString(R.string.setup_progress, step.ordinal + 1, SetupStep.entries.size)
        val (title, description) = when (step) {
            SetupStep.WELCOME -> R.string.walkthrough_welcome_title to R.string.walkthrough_welcome_body
            SetupStep.ENABLE -> R.string.step_enable_title to R.string.walkthrough_enable_body
            SetupStep.SELECT -> R.string.step_select_title to R.string.step_select_body
            SetupStep.TRY -> R.string.walkthrough_try_title to R.string.walkthrough_try_body
            SetupStep.TOUR -> R.string.tour_title to R.string.tour_body
            SetupStep.CONTROLS -> R.string.walkthrough_controls_title to R.string.walkthrough_controls_body
        }
        heading.setText(title)
        body.setText(description)
        actions.removeAllViews()
        if (step == SetupStep.TRY) {
            actions.addView(label(getString(R.string.setup_done), 15f, R.color.primary))
            tryInput = EditText(this).apply {
                id = R.id.setup_try_input
                hint = getString(R.string.try_it_hint)
                contentDescription = getString(R.string.walkthrough_try_field)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                setTextColor(getColor(R.color.on_surface))
                setHintTextColor(getColor(R.color.on_surface_variant))
                setTextSize(18f)
                setText(draft)
            }.also { actions.addView(it, row()) }
            actions.addView(label(getString(R.string.walkthrough_try_note), 15f, R.color.on_surface_variant))
        }
        if (step == SetupStep.TOUR) {
            KeyboardSettings.setTourSeen(this)
            tryInput = EditText(this).apply {
                hint = getString(R.string.try_it_hint)
                contentDescription = getString(R.string.walkthrough_try_field)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                setTextColor(getColor(R.color.on_surface))
                setHintTextColor(getColor(R.color.on_surface_variant))
                setTextSize(18f)
                setText(draft)
            }.also { actions.addView(it, row()) }
            for ((tipTitle, tipBody) in TOUR_TIPS) {
                actions.addView(label(getString(tipTitle), 17f).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    ViewCompat.setAccessibilityHeading(this, true)
                }, row().apply { topMargin = dp(18) })
                actions.addView(label(getString(tipBody), 15f, R.color.on_surface_variant).apply { setLineSpacing(dp(2).toFloat(), 1f) },
                    row().apply { topMargin = dp(2) })
            }
            actions.addView(label(getString(R.string.tour_more), 14f, R.color.on_surface_variant), row().apply { topMargin = dp(18) })
        }
        if (step == SetupStep.CONTROLS) {
            actions.addView(Switch(this).apply {
                text = getString(R.string.walkthrough_chime_toggle)
                textSize = 17f
                setTextColor(getColor(R.color.on_surface))
                minHeight = dp(48)
                isChecked = KeyboardSettings.neuralPredictions(this@SetupActivity)
                setOnCheckedChangeListener { _, checked -> KeyboardSettings.setNeuralPredictions(this@SetupActivity, checked) }
            }, row())
            actions.addView(Switch(this).apply {
                text = getString(R.string.model_on_tap_title)
                textSize = 17f
                setTextColor(getColor(R.color.on_surface))
                minHeight = dp(48)
                isChecked = KeyboardSettings.modelOnTap(this@SetupActivity)
                setOnCheckedChangeListener { _, checked -> KeyboardSettings.setModelOnTap(this@SetupActivity, checked) }
            }, row().apply { topMargin = dp(8) })
            actions.addView(label(getString(R.string.model_on_tap_body), 15f, R.color.on_surface_variant))
            actions.addView(label(getString(R.string.walkthrough_privacy_body), 15f, R.color.on_surface_variant))
        }
        val primary = when {
            step == SetupStep.ENABLE && !status.enabled -> R.string.step_enable_action
            step == SetupStep.SELECT && !status.ready -> R.string.step_select_action
            step == SetupStep.CONTROLS -> R.string.walkthrough_finish
            step == SetupStep.TOUR && tourOnly -> R.string.tour_done
            else -> R.string.walkthrough_continue
        }
        action(primary, primary = true) {
            status = androidStatus()
            when {
                step == SetupStep.ENABLE && !status.enabled -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                step == SetupStep.SELECT && status.enabled && !status.selected ->
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                step == SetupStep.TOUR && tourOnly && status.ready -> openSettings()
                else -> {
                    val following = SetupFlow.next(step, status)
                    if (following == null) {
                        KeyboardSettings.setSetupComplete(this)
                        openSettings()
                    } else render(following)
                }
            }
        }
        if (!(tourOnly && step == SetupStep.TOUR)) {
            if (step != SetupStep.WELCOME) action(R.string.walkthrough_back) { render(SetupFlow.previous(step)!!) }
            action(R.string.walkthrough_later) { openSettings() }
        }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(SKIP, true))
        finish()
    }

    private fun action(label: Int, primary: Boolean = false, onClick: () -> Unit) {
        actions.addView(Button(this).apply {
            setText(label)
            isAllCaps = false
            textSize = 16f
            minHeight = dp(48)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setTextColor(getColor(if (primary) R.color.on_primary else R.color.on_primary_container))
            background = GradientDrawable().apply {
                setColor(getColor(if (primary) R.color.primary else R.color.primary_container))
                cornerRadius = dp(20).toFloat()
            }
            stateListAnimator = null
            setOnClickListener { onClick() }
        }, row().apply { topMargin = dp(12) })
    }

    private fun label(value: String, size: Float, color: Int = R.color.on_surface) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
    }
    private fun row() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val REPLAY = "replay_walkthrough"
        const val SKIP = "skip_walkthrough"
        /** Open at the tour and end there. */
        const val TOUR = "tour_only"

        /** What the keys themselves do not show, in the order a person meets them. */
        private val TOUR_TIPS = listOf(
            R.string.tour_hold_title to R.string.tour_hold_body,
            R.string.tour_clipboard_title to R.string.tour_clipboard_body,
            R.string.tour_saved_title to R.string.tour_saved_body,
            R.string.tour_shortcut_title to R.string.tour_shortcut_body,
            R.string.tour_slide_title to R.string.tour_slide_body,
            R.string.tour_voice_title to R.string.tour_voice_body,
            R.string.tour_undo_title to R.string.tour_undo_body,
            R.string.tour_switch_title to R.string.tour_switch_body,
        )
    }
}
