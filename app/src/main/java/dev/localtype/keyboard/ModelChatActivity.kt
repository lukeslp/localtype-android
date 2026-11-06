package dev.localtype.keyboard

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A small chat with Gemini Nano or Gemma, opened from the version line in About. It talks only to the
 * on-device models the keyboard already uses. The conversation stays in this screen's memory, survives
 * a rotation and is gone when the screen closes: nothing is saved, logged, learned or sent anywhere.
 */
class ModelChatActivity : Activity() {
    /** Carried across a rotation or theme change by onRetainNonConfigurationInstance, never past a closed screen. */
    private class Conversation {
        val lines = ArrayList<ChatLine>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var pending: Job? = null
        /** The model the pending message went to, for its placeholder's label. */
        var pendingModel: String? = null
        var onReply: ((ChatLine) -> Unit)? = null
    }

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var conversation: Conversation
    private lateinit var providers: List<FoundationModelProvider>
    private var availability = ModelChat.Availability(ModelChat.State.CHECKING)
    private var checking: Job? = null
    /** Between onStart and onStop. */
    private var started = false

    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var intro: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var setupTitle: TextView
    private lateinit var setupBody: TextView
    private lateinit var setupAction: Button
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private var pendingLabel: TextView? = null
    private var pendingBody: TextView? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SettingsLocale.configuration(base)?.let { applyOverrideConfiguration(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversation = lastNonConfigurationInstance as? Conversation ?: Conversation()
        providers = FoundationModelProviderFactory.chatProviders(this)
        // Android saves the recent-apps thumbnail to storage, so this screen never gets one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
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
        header.addView(button(getString(R.string.model_chat_back), primary = false).apply {
            contentDescription = getString(R.string.model_chat_back_description)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(text(getString(R.string.model_chat_title), 22f, R.color.on_surface, bold = true).apply {
            setPadding(0, dp(12), 0, 0)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        status = text(getString(R.string.model_chat_checking), 14f, R.color.on_surface_variant).apply {
            setPadding(0, dp(4), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(status)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(messages)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        intro = text(getString(R.string.model_chat_intro), 14f, R.color.on_surface_variant).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(R.color.card, 18)
        }
        setupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(R.color.card, 18)
        }
        setupTitle = text("", 16f, R.color.on_surface, bold = true)
        setupBody = text("", 14f, R.color.on_surface_variant).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(12))
        }
        // Settings opens Extra suggestions and proofreading when this screen closes with RESULT_OK.
        setupAction = button("", primary = true).apply {
            setOnClickListener {
                setResult(RESULT_OK)
                finish()
            }
        }
        setupCard.addView(setupTitle)
        setupCard.addView(setupBody)
        setupCard.addView(setupAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        input = EditText(this).apply {
            hint = getString(R.string.model_chat_hint)
            setTextColor(getColor(R.color.on_surface))
            setHintTextColor(getColor(R.color.on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minHeight = dp(48)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // LocalType, and any other keyboard that honours this, learns nothing typed here.
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(ModelChat.MAX_MESSAGE_CHARS))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = updateSend()
            })
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        send = button(getString(R.string.model_chat_send), primary = true).apply { setOnClickListener { sendMessage() } }
        inputRow.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        root.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        conversation.onReply = ::showReply
        render()
        updateSend()
    }

    override fun onStart() {
        super.onStart()
        started = true
        checkModels()
    }

    override fun onStop() {
        started = false
        checking?.cancel()
        checking = null
        super.onStop()
    }

    override fun onRetainNonConfigurationInstance(): Any = conversation

    override fun onDestroy() {
        screenScope.cancel()
        conversation.onReply = null
        if (!isChangingConfigurations) {
            // Closed for good: drop the conversation and any reply still on its way.
            conversation.scope.cancel()
            conversation.lines.clear()
        }
        super.onDestroy()
    }

    /**
     * Reads which model can answer, again every second while one is loading, for up to the load
     * watchdog's two minutes. A Gemma status starts its load. Only while the screen shows: a reply that
     * lands in the background leaves the check to onStart, so nothing keeps Gemma bound meanwhile.
     */
    private fun checkModels() {
        if (!started) return
        checking?.cancel()
        checking = screenScope.launch {
            ModelChat.checkUntilSettled(providers, pollMs = CHECK_INTERVAL_MS, onEach = ::showAvailability)
        }
    }

    private fun showAvailability(next: ModelChat.Availability) {
        val setupBefore = needsSetup(availability)
        availability = next
        val provider = providers.getOrNull(next.index)
        val name = provider?.label.orEmpty()
        val message = when (next.state) {
            ModelChat.State.CHECKING -> getString(R.string.model_chat_checking)
            ModelChat.State.READY -> getString(R.string.model_chat_ready, name)
            ModelChat.State.LOADING ->
                if (provider is SystemModelDownloader) getString(R.string.model_chat_nano_downloading)
                else getString(R.string.model_chat_loading, name)
            ModelChat.State.FAILED -> getString(R.string.model_chat_failed, name)
            ModelChat.State.NOT_SET_UP -> getString(R.string.model_chat_none)
        }
        // Older Android announces a live region on every setText, even with the same text.
        if (status.text.toString() != message) status.text = message
        if (next.state == ModelChat.State.FAILED) {
            setupTitle.text = getString(R.string.model_chat_failed, name)
            setupBody.text = getString(R.string.model_chat_failed_body, name)
            setupAction.text = getString(R.string.model_chat_check_action)
        } else {
            setupTitle.text = getString(R.string.model_chat_setup_title)
            setupBody.text = getString(R.string.model_chat_setup_body)
            setupAction.text = getString(R.string.model_chat_setup_action)
        }
        inputRow.visibility = if (needsSetup(next)) View.GONE else View.VISIBLE
        if (needsSetup(next) != setupBefore) render()
        updateSend()
    }

    private fun needsSetup(state: ModelChat.Availability) =
        state.state == ModelChat.State.NOT_SET_UP || state.state == ModelChat.State.FAILED

    /** A model that is ready, or Gemma loading, which a message waits for. Gemini Nano downloading can take minutes. */
    private fun canAsk(): Boolean = when (availability.state) {
        ModelChat.State.READY -> true
        ModelChat.State.LOADING -> providers.getOrNull(availability.index) !is SystemModelDownloader
        else -> false
    }

    private fun updateSend() {
        if (!this::send.isInitialized) return
        val enabled = canAsk() && conversation.pending == null && input.text.isNotBlank()
        send.isEnabled = enabled
        send.alpha = if (enabled) 1f else 0.5f
    }

    /** Draws the whole conversation, for a new or recreated screen. */
    private fun render() {
        messages.removeAllViews()
        pendingLabel = null
        pendingBody = null
        if (conversation.lines.isEmpty() && !needsSetup(availability)) {
            messages.addView(intro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        conversation.lines.forEach(::addLine)
        if (conversation.pending != null) addPending()
        if (needsSetup(availability)) {
            messages.addView(setupCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        scrollToEnd()
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || conversation.pending != null || !canAsk()) return
        input.text.clear()
        val line = ChatLine(ChatLine.Kind.PERSON, text)
        val chat = conversation
        val asked = providers.getOrNull(availability.index)?.label
        chat.lines += line
        chat.pendingModel = asked
        messages.removeView(intro)
        addLine(line)
        val chatProviders = providers
        val sent = chat.lines.toList()
        // The retained scope holds no screen, so a rotation mid-reply hands the answer to the new one.
        chat.pending = chat.scope.launch {
            val reply = ModelChat.answer(chatProviders, sent) ?: ChatLine(ChatLine.Kind.NOTE, "", asked)
            chat.lines += reply
            chat.pending = null
            chat.onReply?.invoke(reply)
        }
        addPending()
        updateSend()
        scrollToEnd()
    }

    /** Fills in the placeholder, whose polite live region reads the reply aloud. */
    private fun showReply(line: ChatLine) {
        val label = pendingLabel
        val body = pendingBody
        if (label == null || body == null) return render()
        pendingLabel = null
        pendingBody = null
        label.text = line.model ?: getString(R.string.model_chat_model)
        if (line.kind == ChatLine.Kind.NOTE) {
            body.setTextColor(getColor(R.color.on_surface_variant))
            body.text = getString(R.string.model_chat_no_answer)
        } else {
            body.setTextColor(getColor(R.color.on_surface))
            body.text = line.text
        }
        updateSend()
        scrollToEnd()
        // The model may have unloaded or given up meanwhile; the status line catches up.
        checkModels()
    }

    private fun addLine(line: ChatLine) {
        when (line.kind) {
            ChatLine.Kind.PERSON -> addBubble(true, getString(R.string.model_chat_you), line.text)
            ChatLine.Kind.MODEL -> addBubble(false, line.model ?: getString(R.string.model_chat_model), line.text)
            ChatLine.Kind.NOTE -> addBubble(false, line.model ?: getString(R.string.model_chat_model), getString(R.string.model_chat_no_answer), note = true)
        }
    }

    private fun addPending() {
        val (label, body) = addBubble(false, conversation.pendingModel ?: getString(R.string.model_chat_model), getString(R.string.model_chat_thinking), note = true, live = true)
        pendingLabel = label
        pendingBody = body
    }

    /** One message: who, then what, read by TalkBack as one item. */
    private fun addBubble(fromPerson: Boolean, who: String, what: String, note: Boolean = false, live: Boolean = false): Pair<TextView, TextView> {
        val label = text(who, 12f, if (fromPerson) R.color.on_primary_container else R.color.primary, bold = true)
        val body = text(what, 16f, when {
            fromPerson -> R.color.on_primary_container
            note -> R.color.on_surface_variant
            else -> R.color.on_surface
        }).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(if (fromPerson) R.color.primary_container else R.color.card, 18)
            ViewCompat.setScreenReaderFocusable(this, true)
            if (live) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(label)
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        messages.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (fromPerson) Gravity.END else Gravity.START
            topMargin = dp(8)
        })
        return label to body
    }

    private fun scrollToEnd() {
        scroll.post { scroll.smoothScrollTo(0, messages.bottom) }
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String, primary: Boolean) = Button(this).apply {
        text = label
        minHeight = dp(48)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(getColor(if (primary) R.color.on_primary else R.color.on_primary_container))
        background = rounded(if (primary) R.color.primary else R.color.primary_container, 22)
        stateListAnimator = null
        setPadding(dp(20), 0, dp(20), 0)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(getColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val CHECK_INTERVAL_MS = 1_000L
    }
}
