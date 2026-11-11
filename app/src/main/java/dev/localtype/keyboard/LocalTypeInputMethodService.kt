package dev.localtype.keyboard

import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.thread

class LocalTypeInputMethodService : InputMethodService(), KeyboardView.Listener {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var localModel: AdaptiveNgramModel
    private lateinit var personalStore: PersonalLearningStore
    private lateinit var personalLearning: PersonalLearningCoordinator
    private var personalWordOwned = false
    private var completedPersonal: PersonalLearningCoordinator.Contribution? = null
    private var correctionPersonal: PersonalLearningCoordinator.Contribution? = null
    private var boundaryPersonalContext: List<String> = emptyList()
    private var pendingPersonalContext: List<String> = emptyList()
    private lateinit var blockedStore: BlockedWordStore
    private lateinit var learningStore: JournalLearningStore
    private lateinit var touchOffsetStore: TouchOffsetStore
    /** Laid out on the language's keys; replaced with its store on every switch. */
    private var touchOffsets = TouchOffsets()
    private lateinit var foundationModel: FoundationModelProvider
    @Volatile private var dictionary = WordDictionary.EMPTY
    private var sharedVocabulary = CurrentVocabulary.EMPTY
    private var fieldVocabulary = CurrentVocabulary.EMPTY
    /**
     * The bundled corpus prior (`corpus_prior.bin`), published with this service's first dictionary;
     * null until then, and for the rest of the process if the asset couldn't be read. The process
     * keeps one copy ([processPrior]). It ranks next words and bounded correction candidates;
     * it never changes the learned tables or the legacy automatic correction decisions.
     */
    @Volatile private var corpusPrior: CorpusPrior? = null
    private var learnedGeneration = 0
    /**
     * The language being typed ([KeyboardSettings.language]), switched by hand with a hold on Space. Anything
     * other than English is experimental: it has its own word lists, learning files and tap offsets, and the
     * English-only parts (Chime, the extra models, proofreading, swipe, contractions, a/an, the typing log) stay off.
     */
    @Volatile private var language = LanguageProfile.ENGLISH
    /** Which language [lexicon] was read for, so a dictionary load never reuses another language's. */
    @Volatile private var lexiconLanguage: LanguageProfile? = null
    /** Bumped each time a language's stores open, so a late callback from the stores of an earlier opening does nothing. */
    private var languageOpening = 0
    private val languagePopup by lazy { SuggestionPopup(this) }

    private var swipeWorker: SwipeWorker? = null
    private var swipeSerial = 0L
    private var swipeStart: VerifiedWordTransaction.Snapshot? = null
    private var swipeShift = KeyboardView.ShiftState.OFF
    /**
     * The editor as the last swipe left it when no space went after its word (text followed it, or words run together
     * there), and whether they ran together ([swipeUnspacedJoined]); any other change clears both.
     */
    private var swipeUnspaced: VerifiedWordTransaction.Snapshot? = null
    private var swipeUnspacedJoined = false
    private data class SwipeChoices(val base: VerifiedWordTransaction.Snapshot, val receipt: VerifiedWordTransaction.Snapshot,
        val words: List<String>, val inserted: String, val shift: KeyboardView.ShiftState, val expires: Long, val spacing: SwipeSpacing)
    /**
     * How a swiped word sits in this field: [joined] words run together (no lead space, none after), [space] adds one
     * after, and [afterSwipe] marks a word straight after a swiped word that got none ([swipeUnspaced]): it puts one
     * ahead, or runs straight on where words run together.
     */
    private data class SwipeSpacing(val joined: Boolean, val space: Boolean, val afterSwipe: Boolean = false)
    private var swipeSpacing = SwipeSpacing(joined = false, space = true)
    /** A raw-key field (a terminal) that can't show its text: the word goes straight in, with no alternatives or Undo. */
    private var swipeBlind = false
    private var swipeChoices: SwipeChoices? = null
    private var swipeLastMs = 0L
    private var swipeStartStatus = "none"
    private var swipeRefusals = 0

    private var neuralEnabled = false
    private var neuralReady = false
    private val neuralState = NeuralRequestState()
    private val editorEdits = EditorEditState()
    private val correctionDiagnostics = CorrectionDiagnostics()
    private var addedCorrectionCpu = 0L
    private var correctionMeasurementDepth = 0
    private inline fun <T> measureAddedCorrection(stage: CorrectionDiagnostics.Stage = CorrectionDiagnostics.Stage.RECEIPT, block: () -> T): T {
        val outer = correctionMeasurementDepth++ == 0
        val start = if (outer) android.os.Debug.threadCpuTimeNanos() else 0L
        try { return block() } finally {
            correctionMeasurementDepth--
            if (outer) {
                val elapsed = (android.os.Debug.threadCpuTimeNanos() - start).coerceAtLeast(0)
                addedCorrectionCpu += elapsed
                correctionDiagnostics.recordStageCpu(stage, elapsed)
            }
        }
    }
    private var fieldIdentity: String? = null
    private var compatibilityEditorRestarted = false
    private var inputViewStarts = 0L
    private var inputViewRestarts = 0L
    private var inputViewFinishes = 0L
    private var finishingInputViews = 0L
    private var windowsShown = 0L
    private var windowsHidden = 0L
    private var explicitHideRequests = 0L
    private var restartBoundaryRecoveries = 0L
    /** Only reused synchronously by this key's finalization; async results always read anew. */
    private var verifiedWordReceipt: VerifiedWordTransaction.Snapshot? = null
    private var expectedContext: String? = null
    private val recentCorrection = RecentWordCorrection()
    private val correctionPolicy = CorrectionPolicy(priorCap = 8f, neuralCap = 8f)
    private val correctionLogPolicy = "${correctionPolicy.version}:${ContextualContractions.VERSION}:${SmallAccommodations.VERSION}"
    private var correctionRequest = 0L
    private var correctionOffer: String? = null
    private var loggedCorrectionMode: Triple<String, Boolean, Boolean>? = null
    private val correctionSettingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bottom_clearance") handler.post { keyboardRoot?.let { ViewCompat.requestApplyInsets(it) } }
        if (key == KeyboardSettings.INACTIVE_GAPS) handler.post { keyboardView?.inactiveGaps = KeyboardSettings.inactiveGaps(this) }
        // Spanish turned off in settings while it was in use goes back to English; on, the space bar names the language.
        if (key == KeyboardSettings.SPANISH || key == KeyboardSettings.PORTUGUESE || key == KeyboardSettings.HEBREW ||
            key == KeyboardSettings.WELSH || key == KeyboardSettings.IRISH || key == KeyboardSettings.MAORI ||
            key == KeyboardSettings.HAWAIIAN || key == KeyboardSettings.IPA || key == KeyboardSettings.LANGUAGE ||
            key in KeyboardSettings.EXTRA_KEYS) handler.post { if (!destroyed) followLanguageSetting() }
        // The sparkle goes or comes back at once: its switch, or a model set up or removed in settings.
        if (key == KeyboardSettings.SPARKLE_BUTTON || key == KeyboardSettings.MODEL_SET_UP) handler.post { if (!destroyed) readSparkle(recheck = false) }
        // Vibration goes off or on at once, without waiting for the next field.
        if (key == KeyboardSettings.VIBRATE_ON_KEY_PRESS) handler.post { KeyHaptics.enabled = KeyboardSettings.vibrateOnKeyPress(this) }
        if (key in setOf("autocorrect", "web_autocorrect", "auto_capitalize", "profanity", "emoji", "neural_predictions", "number_row", "theme", "model_on_tap", "triple_space_proofread", "prediction_log", "learned_generation", "experimental_swipe")) {
            val refresh = Runnable {
                // A newly published exclusion must stop collection before canceling any old logged offer.
                if (key == "learned_generation" && this::blockedStore.isInitialized &&
                    (!blockedStore.ready || blockedStore.words.isNotEmpty())) {
                    if (logAllowed) PredictionLog.endField()
                    logAllowed = false
                    loggedFieldGate.finish()
                }
                cancelRecentCorrection()
                spaceProofread.reset()
                tripleSpaceProofread = KeyboardSettings.tripleSpaceProofread(this)
                if (this::corrector.isInitialized) corrector.forgetRecentAnswers()
                autocorrectEnabled = KeyboardSettings.autocorrect(this)
                autoCapitalize = KeyboardSettings.autoCapitalize(this)
                fieldAllowsAutocorrect = TypingRules.allowsAutocorrect(currentInputEditorInfo?.inputType ?: 0, KeyboardSettings.webAutocorrect(this))
                applySymbolsPolicy()
                scheduleRefresh()
            }
            if (android.os.Looper.myLooper() == handler.looper) refresh.run() else handler.post(refresh)
        }
    }
    private var correctionRow: LinearLayout? = null
    private var correctionText: TextView? = null
    private var correctionAction: TextView? = null
    private var neuralWorker: NeuralPredictionWorker? = null
    /** Attribution for the strip currently on screen, independent of pending editor edits. */
    private var shownNeuralWords: Set<String> = emptySet()
    private var shownPersonalWords: Set<String> = emptySet()
    private var loggedNeuralDigest: String? = null

    private var keyboardView: KeyboardView? = null
    private var keyboardRoot: View? = null
    private val candidateViews = mutableListOf<TextView>()
    /** What each strip slot commits; the display text may add quotes around a literal word. */
    private var slotValues = listOf("", "", "")
    private var literalSlot = -1
    private lateinit var corrector: Corrector
    private var autocorrectEnabled = true
    /** Words the person kept by undoing a correction or tapping the quoted literal, in the language being typed. */
    private var keptWords = HashSet<String>()
    /** Each language's kept words, so a spelling kept in one isn't left alone in another. */
    private val keptByLanguage = HashMap<String, HashSet<String>>()
    /** The part of [keptWords] kept in a field that doesn't allow learning; dropped on every new field. */
    private val fieldKeptWords = HashSet<String>()
    /** A replacement delete can undo. [keep] also protects the original from correction afterwards. */
    private data class AutoCorrection(val original: String, val committed: String, val keep: Boolean = true,
        val personal: PersonalLearningCoordinator.Contribution? = null)
    private var lastAutocorrect: AutoCorrection? = null
    private var contractionAnchor: ContextualContractions.Anchor? = null
    private var accommodationAnchor: SmallAccommodations.Anchor? = null
    private val ownedSpacing = OwnedSpacing()
    private var capsLockUsedForWord = false
    private var pendingPeriod: DomainTyping.Pending? = null
    private var pendingPeriodPersonalContext: List<String> = emptyList()
    private var pendingPeriodWordVerified = false
    /** Taps of the word being typed, and whether the person came back to edit it. */
    private val trace = WordTrace()
    /** Where the finger lifted for the key about to arrive in [onKey]. */
    private var pendingTouch: Touch? = null
    /** A text key typed while an editor restart had retired the connection, replayed when it starts again ([PendingKeys]). */
    private class HeldKey(val action: KeyAction, val touch: Touch?, val raw: Boolean = false)
    private val pendingKeys = PendingKeys<HeldKey>()
    /** Plain appends since the editor last showed its text, so a restart can resend what it swallowed ([AppendLedger]). */
    private val appendLedger = AppendLedger(MAX_CONTEXT_CHARS)
    /** The text before the cursor read for the key being dispatched, or null; a failed read after it means a restart. */
    private var keyContext: String? = null
    private var heldKeysResent = 0L
    /** True while held keys replay: their case and layer changes already happened when they were pressed. */
    private var replayingKeys = false
    private var heldKeysReplayed = 0L
    private var heldKeysSent = 0L
    private var heldKeysDropped = 0L
    private val pendingKeysDrain = Runnable { drainPendingKeys() }
    private val pendingKeysExpiry = Runnable { expirePendingKeys() }
    /** Left of the strip: paste chip, or the model's generate / generating / generated control. */
    private var leadButton: android.widget.ImageView? = null
    /** The lead button's TalkBack action for what holding it does (save the copy, or proofread), relabelled when that changes. */
    private var leadHoldAction = View.NO_ID
    private var leadHoldLabel = 0
    private var leadPulse: android.animation.ObjectAnimator? = null
    private var modelOnTap = false
    /** Settings' Sparkle button switch, and whether a model is set up behind it ([SparkleRules]). */
    private var sparkleWanted = true
    private var modelSetUp = false
    private var modelSetUpCheckedAt: Long? = null
    private var modelSetUpJob: Job? = null
    /** An explicit foundation-model tap may temporarily override neural suggestions for this word. */
    private var manualFoundationContext: String? = null
    /** True while any model request (tapped or automatic) is running. */
    private var modelRunning = false
    private var showingModelWords = false
    private var pasteClip: FreshClip? = null
    /** A proofreading proposal on screen, replacing the suggestions until applied or dismissed. */
    private data class ProofreadProposal(val request: ProofreadRequest, val corrected: String) {
        val original get() = request.original
    }
    private var proposal: ProofreadProposal? = null
    private var proofreading = false
    private var proofreadJob: Job? = null
    /** Between onStartInputView and onFinishInputView; model answers that land while hidden start no more work. */
    private var viewShown = false
    private var proposalRow: LinearLayout? = null
    private var proposalText: TextView? = null
    private var proposalApply: View? = null
    private var proposalSettings: TextView? = null
    private var selectionProofread: TextView? = null
    private var selectionProofreadEligible = false
    private val spaceProofread = ProofreadSpaceGesture()
    private var tripleSpaceProofread = false
    private var handlingSpace = false
    @Volatile private var profanityWords: Set<String> = emptySet()
    @Volatile private var lexicon: Lexicon = Lexicon.EMPTY
    @Volatile private var emojiSuggestions: EmojiSuggestions = EmojiSuggestions.EMPTY
    private var emojiEnabled = true
    private var numberRow = false
    private var commonSymbolHolds = false
    private var spaceBar = SpaceBar.STANDARD
    /** English's Spelling: British choice ([KeyboardSettings.britishSpelling]), read once per field. */
    private var britishSpelling = false
    private var currency = KeyboardCurrency.FOLLOW_SPELLING
    /** The American-to-British pairs, read with the English dictionary; empty for any other language. */
    private var britishWords = BritishSpelling.EMPTY
    private var stripHeight = StripHeight.STANDARD
    private var candidateStripView: LinearLayout? = null
    /** Strip slot showing an emoji, or -1. Tapping it swaps the matched word or phrase for the emoji. */
    private var emojiSlot = -1
    /** The text the offered emoji would replace. */
    /** Every emoji offered for the current word; holding the slot shows them. */
    private var emojiOptions: List<String> = emptyList()
    private val emojiPopup by lazy { SuggestionPopup(this) }
    /** Strip slot offering an expansion, or -1: a saved text for its shortcut, or the rest of a remembered host. */
    private var expansionSlot = -1
    /**
     * What that slot, or the host chip in a field with no word slots, would put in. Its text is never in
     * [slotValues], so it cannot reach the typing log, learning or a model.
     */
    private var expansion: Expansions.Offer? = null
    /** A remembered host in an address bar or a mail field, which have no word slots. */
    private var hostChip: TextView? = null
    /** The run before the caret while every character of it was typed here; a host is remembered only from one. */
    private val typedRun = TypedRun()
    /** This process's remembered hosts, shared with settings; null until their file has been read. */
    private var rememberedDomains: RememberedDomains? = null
    /** Hosts are remembered and offered; read once per field. */
    private var rememberDomains = true
    /** How soon this field offers a host: at once in an address bar, after the "@" in a mail field. */
    private var hostField = Expansions.Field.TEXT
    private var expansionsShown = 0L
    private var expansionsTaken = 0L
    private var linksCleaned = 0L
    /** Whether the clip the paste chip offers has tracking to remove: its text, and the answer. */
    private var pasteCleanCheck: Pair<String, Boolean>? = null

    // Panels (see KeyboardPanel): one covers the keys at their exact height and puts its header in the strip.
    /** The open panel, or null while the keys show. */
    private var panel: KeyboardPanel? = null
    private var symbolsPanel: SymbolToolsPanel? = null
    private var symbolsPanelRoot: View? = null
    private var effectsPanel: TextEffectsPanel? = null
    private var effectsPanelRoot: View? = null
    // In-memory only; never sent to the editor, prediction model or typing log as keys.
    private var symbolQuery = ""
    private var effectsSource = ""
    private data class EffectsSelection(val start: Int, val end: Int, val text: String)
    private var effectsSelection: EffectsSelection? = null
    /** Last explicitly selected source, retained when Edit source makes an independent local draft. */
    private var effectsEditorSelection: EffectsSelection? = null
    private var localInputKind: KeyboardPanel? = null
    private var localInputMode = KeyboardLayout.Mode.LETTERS
    private var localInputShift = KeyboardView.ShiftState.OFF
    private var localInputHeader: android.widget.TextView? = null

    /** The keys, with any panel laid over them. */
    private var keyArea: KeyArea? = null
    /** The strip's place for an open panel's header, in place of the lead button, suggestions and notices. */
    private var panelHeader: android.widget.FrameLayout? = null
    /** At the strip's right end, mirroring the lead button: opens and closes the emoji panel. */
    private var emojiButton: android.widget.ImageView? = null
    /** The handle at the strip's right end that opens the tools row; it stays while a panel is open. */
    private var toolsHandle: android.widget.ImageView? = null
    /** The tools row, drawn in the strip over the word slots while [toolsOpen]. */
    private var toolRow: LinearLayout? = null
    private var toolsOpen = false
    private var toolsPanel: ToolsPanel? = null
    private var toolsPanelRoot: View? = null
    private var arrowPad: ArrowPadPanel? = null
    private var arrowsPanelRoot: View? = null
    private var contactsPanel: ContactsPanel? = null
    /** Passwords & cards: the password manager's chips moved over the keys, or why there are none. */
    private var passwordsPanel: PasswordsPanel? = null
    private var passwordsPanelRoot: View? = null
    /** The icons in the open tools row, so the Passwords dot can change in place. */
    private val rowToolIcons = HashMap<StripTool, android.widget.ImageView>()
    /** One-handed keys ([OneHanded]) and the rail beside them, with the move and full-width buttons. */
    private var oneHanded = OneHanded.OFF
    /** Keys on a wide window ([WideKeys]), read once per field, and where [applyKeyWidth] last put them. */
    private var wideKeys = WideKeys.CENTERED
    private var keyPlacement: KeyWidthRules.Placement? = null
    private var oneHandedRail: LinearLayout? = null
    private var oneHandedSwap: android.widget.ImageView? = null
    private var oneHandedFull: android.widget.ImageView? = null
    private var contactsPanelRoot: View? = null
    /**
     * The phone's contacts, read only once the person allowed it and kept in memory only; null until read. Their
     * name words ([contactNames]) are never corrected. Taking the permission back empties both at the next field.
     */
    private var contacts: List<Contact>? = null
    private var contactNames: Set<String> = emptySet()
    private var contactsReadAt = 0L
    private var contactsReading = false
    /** Stops the read under way, if any, when the service goes. */
    private var contactsCancel: android.os.CancellationSignal? = null
    /** The word before the caret that the open Contacts panel was filtered by. */
    private var contactsQuery = ""
    /** Private for now: this field learns and logs nothing until a new field starts. A restart of it keeps that. */
    private var privateNow = false
    /** Read aloud's speech engine, started on its first use and kept for the service. */
    private var speech: android.speech.tts.TextToSpeech? = null
    private var speechReady = false
    private var speechPending: String? = null
    /** Beside the emoji button: starts and stops dictation, or switches to another app's voice typing. */
    private var voiceButton: android.widget.ImageView? = null
    /** The strip's status row: what dictation is hearing, what a delete slide will remove, or a notice over either. */
    private var voiceRow: LinearLayout? = null
    private var voiceText: TextView? = null
    private var noticeText: TextView? = null
    /** A password manager's chips (Android 11+): the asking and arranging, and the strip's row they sit in. */
    private var inlineAutofill: InlineAutofill? = null
    private var inlineRow: InlineChipRow? = null
    /** A key was pressed since this field asked for chips: the manager's pinned buttons then give the word slots back. */
    private var inlineTyped = false
    /** The caret slide on space and the word slide on delete; read once per field. */
    private var slideGestures = true
    /** Tracking parameters come off links pasted through the keyboard; read once per field. */
    private var cleanLinks = true
    /**
     * The last paste, when tracking came off it and no key has been pressed since: delete, or Undo in the strip,
     * puts back what was copied. Kept apart from [lastAutocorrect], which the caret report that follows every
     * paste clears. A contact put in at the caret leaves its way back here too ([insertContact]).
     */
    private var cleanedPaste: CleanedPaste? = null
    /** [caret] is where the paste left the caret: Undo is for that place only. */
    private class CleanedPaste(val undo: AutoCorrection, val caret: Int)
    /** The correction row is showing that paste's offer. */
    private var linkNotice = false
    /** Two-finger swipes on the keys, and what each does; read once per field. */
    private var twoFingerGestures = false
    private var gestureActions: Map<TwoFingerSwipe, GestureAction> = GestureRules.DEFAULTS
    /** What a delete-word gesture removed and the editor's receipt right after: Undo puts it back only there. */
    private class GestureUndo(val deleted: String, val receipt: VerifiedWordTransaction.Snapshot, val generation: Long)
    private var gestureUndo: GestureUndo? = null
    /** The correction row is saying what a gesture did. */
    private var gestureNotice = false
    private var gesturesRun = 0
    private var gesturesRefused = 0
    private var gestureLast = "none"
    /** A slide left from delete in progress: the text it was measured on, and how much of its end goes. */
    private class DeleteSlide(val before: String, val caret: EditorEditState.Selection, val generation: Long, var length: Int = 0)
    private var deleteSlide: DeleteSlide? = null
    /** What the status row says for a delete slide, or null when dictation (or nothing) has the row. */
    private var stripStatus: String? = null
    /**
     * The notice on show where a toast once went ([showNotice]), or null. It has its own timer: the caret report
     * that can follow the very paste it is about would clear [stripStatus] at once.
     */
    private var notice: NoticeRules.Message? = null
    /** Its text while the status row is where it shows, and its view while the emoji panel's header is. */
    private var stripNotice: String? = null
    private var headerNotice: TextView? = null
    private var noticesShown = 0
    private var noticePlace: NoticeRules.Place? = null
    /** Read once per field: what this phone offers, and whether this field gets the mic key. */
    private var voiceRoute = VoiceRoute.NONE
    private var voiceInputMethod: VoiceDictation.Offer? = null
    private var voiceOffered = false
    /** Where Settings puts the voice key besides the tools row, read at each field. */
    private var voiceKey = VoiceKey.OFF
    /** Whether the drawn layout has the voice key, so a change in what the field offers redraws it. */
    private var layoutVoiceKey = false
    /** The input type the mic key was last decided for, so an editor that restarts on every change asks once. */
    private var voiceFieldType = -1
    private val dictationSession = DictationSession()
    private var dictation: VoiceDictation? = null
    /** True from a tap on the mic key that ends dictation until the recognizer's last result arrives. */
    private var dictationFinishing = false
    private val dictationFinishTimeout = Runnable { stopDictationNow() }
    /** The recognizer hears one utterance at a time; this asks for the next, outside its result callback. */
    private val dictationListenAgain = Runnable {
        if (dictationSession.listening && !dictationFinishing &&
            dictation?.listen(maskOffensiveWords = !profanityAllowed, language = speechLanguage()) != true) endDictation(getString(R.string.voice_unavailable))
    }
    /** How the last dictation ended, for dumpsys: the reason and the recognizer's error code. */
    private var dictationLastEnd = "none"
    /** The suggestion slots and their dividers, which hide and show together. */
    private val slotViews = mutableListOf<View>()
    /** Built on first open for each set of views; a theme change rebuilds them. */
    private var emojiPanel: EmojiPanel? = null
    private var emojiPanelRoot: View? = null
    /**
     * The emoji search query while searching, else null; only ever set while the emoji panel is open.
     * While it's set the letter keys show and [onKey] hands every key to [searchKey], so the query
     * never reaches the field.
     */
    private var emojiQuery: String? = null
    /** Search temporarily borrows ordinary letter keys; closing it gives the previous field layout back. */
    private var emojiSearchMode = KeyboardLayout.Mode.LETTERS
    private var emojiSearchShift = KeyboardView.ShiftState.OFF
    private var emojiSearchHeight = 0
    /** Every emoji this phone can draw; empty until the background load publishes, and after a failed one. */
    private var emojiInventory = EmojiInventory.EMPTY
    /** A load of [emojiInventory] is under way, so the panel says "Loading emoji…" rather than that it failed. */
    private var emojiLoading = false
    private val emojiStore by lazy { PreferenceEmojiStore(this) }
    private val recentEmoji by lazy { RecentEmoji(emojiStore) }
    private val emojiTones by lazy { EmojiTones(emojiStore) }
    private var savedPanel: SavedTextPanel? = null
    private var savedPanelRoot: View? = null
    /** Each panel's bar key for the other panel, where TalkBack's focus lands after switching with it. */
    private val panelToggles = java.util.EnumMap<KeyboardPanel, View>(KeyboardPanel::class.java)
    /** This process's saved text, shared with settings; null until its file has been read. */
    private var savedTexts: SavedTexts? = null
    /** Redraws an open Saved text panel when the list changes, here or in settings. */
    private val savedTextListener: () -> Unit = {
        renderSavedPanel()
        // A shortcut may have been set or removed: it is offered, and left alone by correction, from now.
        if (this::corrector.isInitialized) corrector.forgetRecentAnswers()
        scheduleRefresh()
    }
    /** What was copied in the last hour, in memory only. */
    private val recentCopies = RecentCopies()
    /** Where the clip now on the clipboard was copied; holding the paste chip won't save one from a password or incognito field. */
    private var copyOrigin: CopyOrigin? = null

    private var loadedWithProfanity: Boolean? = null
    /** What the dictionary in use was built with, set when a load publishes; null until the first one does. */
    private var publishedWithProfanity: Boolean? = null
    /** The newest dictionary load has published, so the strip and the log see the same word list. */
    private var dictionaryReady = false
    private var dictionaryGeneration = 0
    private var destroyed = false
    private var profanityAllowed = true
    private var autoCapitalize = true
    private var clipboard: android.content.ClipboardManager? = null
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        recordCopy()
        scheduleRefresh()
    }
    /** Clip text offered as a paste chip; applied or ambiguous attempts must not be offered twice. */
    private var pastedClipStamp = -1L
    private var privateNotice: TextView? = null

    private var mode = KeyboardLayout.Mode.LETTERS
    private var shiftState = KeyboardView.ShiftState.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    /** Last selection reported by the editor; protects neighbors if getSelectedText is unsupported. */
    private var selectionActive = false
    /** True right after a suggestion inserted its own trailing space. */
    private var autoSpaced = false

    private var suggestionsEnabled = true
    /** Why the strip shows a notice instead of suggestions in this field, or null. */
    private var stripOff: TypingRules.StripOff? = null
    /** The field asked for suggestions itself. The typing log follows this; a web box that only turned off autofill still gets predictions. */
    private var fieldWantsSuggestions = true
    /** Names and addresses still get suggestions, but space and punctuation must preserve their spelling. */
    private var fieldAllowsAutocorrect = true
    /** The keys the current field started on, so a restart into a different kind of field can tell. */
    private var fieldLayout: KeyboardLayout.Mode? = null
    private var learningAllowed = true
    /**
     * A browser's address bar: prose predicts and learns (0.5.18), while automatic correction and
     * the typing log stay off. [fieldWantsSuggestions] remains the separate correction/logging gate.
     */
    private var addressBar = false
    /** The token at the caret in an address bar is a word, not an address; set where the strip decides it. */
    private var addressProse = true
    private val fieldLearnsWords: Boolean
        get() = WordPersistence.field(learningAllowed, suggestionsEnabled, fieldWantsSuggestions, addressBar)
    private var inputIsSensitive = false

    // Typing log state (see PredictionLog). Nothing here does I/O on this thread.
    /** The opt-in log covers this field: the setting is on and the field allows learning and suggestions. */
    private var logAllowed = false
    /** Holds a logged field back until the dictionary it will describe has published. */
    private val loggedFieldGate = LoggedFieldGate()
    /** The in-memory model has learned since it was built, so a snapshot must rebuild it from the store first. */
    private var modelMutated = false
    /** A late saved baseline was added to a live model; its next snapshot must rebuild the merged store. */
    private var learnedBaselineImported = false
    /** The last snapshot still describes the model the log's next field will see. */
    private var snapshotClean = false
    /** Learned words were cleared in settings since the last snapshot. */
    private var logRebuilt = false
    /** Bumped by every setSlots, so a tap on a strip the log never saw is marked as such. */
    private var stripRevision = 0
    /** The lists behind the last logged offer, for naming where a tapped word came from. */
    private var lastLoggedRanked: List<Pair<String, Int>> = emptyList()
    private var lastLoggedCached: List<String> = emptyList()
    private var logGenerationSeen = 0
    private var pendingFoundationRequest: Runnable? = null
    private var foundationJob: Job? = null
    /** Saved predictions, used only in fields that allow learning. */
    private lateinit var modelCache: ModelSuggestionCache
    /** Memory-only predictions for fields that don't allow learning; emptied on every new field. */
    private val privateModelCache = ModelSuggestionCache()
    /** Bumped when caches are replaced, so an answer that was in flight can't write into the new ones. */
    private var cacheGeneration = 0

    private val activeModelCache: ModelSuggestionCache
        get() = if (learningAllowed) modelCache else privateModelCache
    private val modelGate = ModelRequestGate()
    private val modelBudget = ModelBudget()

    private fun powerState(): ModelBudget.PowerState {
        val power = getSystemService(android.os.PowerManager::class.java)
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE
        } else false
        return ModelBudget.PowerState(
            powerSave = power.isPowerSaveMode,
            batteryPercent = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100,
            charging = battery?.isCharging == true,
            thermalSevere = thermal,
        )
    }
    private val refreshRunnable = Runnable { refreshCandidates() }

    /** Light or dark as chosen in settings; null until the service reads it. */
    private var themeMode: ThemeMode? = null
    private var themed: Pair<Configuration, android.content.res.Resources>? = null

    /**
     * Colors and drawables resolve through here, so a forced light or dark theme reaches every view
     * the keyboard draws without each one asking.
     */
    override fun getResources(): android.content.res.Resources {
        val base = super.getResources()
        val config = themeMode?.apply(base.configuration) ?: return base
        themed?.takeIf { it.first == config }?.let { return it.second }
        return createConfigurationContext(config).resources.also { themed = config to it }
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("keyboard_state", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(correctionSettingsListener)
        themeMode = ThemeMode.of(this)
        if (PasswordsTile.available(Build.VERSION.SDK_INT)) inlineAutofill = InlineAutofill(this, ::dp, ::attachInlineChips, ::inlineChipTapped)
        language = KeyboardSettings.language(this)
        openLanguageStores()
        learnedGeneration = KeyboardSettings.learnedGeneration(this)
        foundationModel = FoundationModelProviderFactory.create(this)
        // Swear words are never "corrected" into other words, even when they aren't suggested. While
        // they're switched off they aren't offered as fixes either, though most are still in the lexicon.
        corrector = Corrector(
            dictionary = { dictionary },
            isKnown = { localModel.knows(it) || it in keptWords || it in profanityWords || it in fieldVocabulary },
            lexicon = { lexicon },
            contextCount = { previous, word -> localModel.pairCount(previous, word) },
            blocked = { personallyBlocked(it) || (publishedWithProfanity == false && it in profanityWords) },
            isKept = { it in keptWords || localModel.isAddedWord(it) || isProtectedWord(it) },
            touchOffsets = { touchOffsets },
            language = { language },
        )
        modelCache = ModelSuggestionCache(PreferenceModelCacheStore(this))
        clipboard = getSystemService(android.content.ClipboardManager::class.java)?.also { it.addPrimaryClipChangedListener(clipListener) }
        loadDictionary(KeyboardSettings.profanity(this))
        awaitLearnedBaseline()
        loadEmojiInventory()
        // Read on its own worker; settings shares the same list, so Remove all reaches an open panel too.
        SavedTextStore.open(this) { texts ->
            if (destroyed) return@open
            savedTexts = texts
            texts.onChange = savedTextListener
            renderSavedPanel()
        }
        DomainStore.open(this) { domains ->
            if (destroyed) return@open
            rememberedDomains = domains
            scheduleRefresh()
        }
    }

    /**
     * Loads every emoji this phone can draw, once per process, on its own background thread, so the
     * dictionary and typing never wait for it. A load that fails is tried again when the emoji
     * panel next opens.
     */
    private fun loadEmojiInventory() {
        if (emojiLoading) return
        emojiLoading = true
        EmojiCatalog.load(this) { inventory, elapsedMs ->
            if (destroyed) return@load
            emojiLoading = false
            diag { "emoji inventory size=${inventory.size} ms=$elapsedMs" }
            emojiInventory = inventory
            // Opened before the load finished: fill it now (or say it failed), and a search already under way looks again.
            if (panel == KeyboardPanel.EMOJI && emojiPanel?.isEmpty == true) emojiPanel?.show(inventory, recentEmoji.items, loading = false)
            emojiQuery?.let { emojiPanel?.showSearch(it, emojiSuggestions.phrases) }
        }
    }

    /** The learning stores, tap offsets and model of [language]: when the service starts, and on every switch. */
    private fun openLanguageStores() {
        languageOpening++
        blockedStore = BlockedWordStore.get(this, language)
        learningStore = JournalLearningStore.get(this, language)
        personalStore = PersonalLearningStore.get(this, language)
        personalLearning = PersonalLearningCoordinator({ context, word, origin ->
            if (mayLearnWord(word, context)) personalStore.record(context, word, origin) else null
        }, personalStore::undo)
        val opening = languageOpening
        personalStore.whenReady { if (!destroyed && opening == languageOpening) scheduleRefresh() }
        blockedStore.whenLoaded {
            handler.post { if (!destroyed && opening == languageOpening) { openPendingLoggedField(); scheduleRefresh() } }
        }
        touchOffsetStore = TouchOffsetStore(this, language)
        touchOffsets = if (language.english) TouchOffsets() else TouchOffsets(language.geometry)
        touchOffsetStore.load(touchOffsets)
        localModel = buildLocalModel()
    }

    /** Adds the saved learned pairs to the live model once the store has read them, if it hadn't when the model was built. */
    private fun awaitLearnedBaseline() {
        val opening = languageOpening
        if (!learningStore.ready) learningStore.whenReady { baseline ->
            if (destroyed || opening != languageOpening) return@whenReady
            // Keep increments already learned while loading; only add the older saved baseline.
            // A generation change instead builds everything from the newly published cache.
            if (!reloadLearnedIfChanged()) {
                localModel.restoreLearned(baseline)
                learnedBaselineImported = true
            }
            corrector.forgetRecentAnswers()
            snapshotClean = false
            openPendingLoggedField()
            scheduleRefresh()
        }
    }

    /** Settings changed the language or the languages on offer: follow it, and redraw the space bar's name. */
    private fun followLanguageSetting() {
        val chosen = KeyboardSettings.language(this)
        if (chosen !== language) switchLanguage(chosen, announce = false) else if (mode == KeyboardLayout.Mode.LETTERS) applyLayout()
    }

    /**
     * Types in [next] from now on: its keys, word lists, learning and tap offsets. The switch is a boundary, as a new
     * field is: nothing typed before it ranks, corrects or teaches what follows, a pending repair is dropped, and a
     * logged field ends (only English is logged). The person stays in the field, on the keys they were on.
     */
    private fun switchLanguage(next: LanguageProfile, announce: Boolean = true) {
        if (next === language) return
        KeyboardSettings.setLanguage(this, next)
        // Words kept in this field go with the field's language; the rest are kept per language.
        keptWords -= fieldKeptWords
        fieldKeptWords.clear()
        keptByLanguage[language.code] = keptWords
        keptWords = keptByLanguage.getOrPut(next.code) { HashSet() }
        val leavingSymbols = !language.words
        language = next
        if (leavingSymbols) restoreFieldPolicy()
        applySymbolsPolicy()
        // Dictation heard in the old language doesn't land in the new one.
        stopDictationNow(commit = false)
        spaceProofread.reset()
        cancelSwipeWork()
        proofreadJob?.cancel()
        dismissProposal()
        cancelFoundationRequest()
        // An English model answer still on its way is for the old language: drop it, as a new field would.
        cacheGeneration++
        manualFoundationContext = null
        cancelRecentCorrection()
        resetNeuralSession()
        personalLearning.reset(PersonalContextTrace.ResetReason.FIELD)
        boundaryPersonalContext = emptyList()
        completedPersonal = null
        personalWordOwned = false
        contractionAnchor = null
        accommodationAnchor = null
        ownedSpacing.reset()
        lastAutocorrect = null
        autoSpaced = false
        expectedContext = null
        trace.revisit()
        loggedFieldGate.finish()
        if (logAllowed) {
            PredictionLog.endField()
            logAllowed = false
        }
        // Nothing of the old language's lists stays in use while the new ones load.
        dictionary = WordDictionary.EMPTY
        lexicon = Lexicon.EMPTY
        corpusPrior = null
        profanityWords = emptySet()
        sharedVocabulary = CurrentVocabulary.EMPTY
        fieldVocabulary = CurrentVocabulary.EMPTY
        if (!language.english) emojiSuggestions = EmojiSuggestions.EMPTY
        neuralEnabled = neuralAllowedHere()
        if (neuralEnabled && !neuralReady) loadNeuralModel()
        openLanguageStores()
        corrector.forgetRecentAnswers()
        snapshotClean = false
        modelMutated = false
        loadDictionary(profanityAllowed)
        awaitLearnedBaseline()
        applyLayout()
        updateShiftFromEditor()
        updateLead()
        if (announce) showNotice(NoticeRules.Message.LANGUAGE)
        scheduleRefresh()
    }

    /** Holding Space with more than one language on: the languages, then Android's own keyboard picker. */
    private fun showLanguageChooser() {
        val anchor = keyboardView ?: return
        val languages = KeyboardSettings.languages(this)
        val other = getString(R.string.language_other_keyboards)
        val options = languages.map { it.nativeName } + other
        languagePopup.show(
            anchor = anchor,
            options = options,
            describe = { it },
            title = getString(R.string.language_chooser_title),
            selected = languages.indexOf(language),
            words = true,
        ) { picked ->
            if (picked == other) showInputMethodPicker()
            else languages.firstOrNull { it.nativeName == picked }?.let { switchLanguage(it) }
        }
    }

    private fun showInputMethodPicker() =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()

    /**
     * The IPA keyboard types symbols, not words: the strip says so, and nothing is suggested, corrected, capitalized
     * or learned, whatever the field would allow. Applied after the field's own policy, at its start and on a switch.
     */
    private fun applySymbolsPolicy() {
        if (language.words) return
        stripOff = TypingRules.StripOff.IPA
        suggestionsEnabled = false
        fieldAllowsAutocorrect = false
        learningAllowed = false
        autoCapitalize = false
        neuralEnabled = false
    }

    /** Leaving the IPA keyboard mid-field: the field's own policy again, as its start set it. */
    private fun restoreFieldPolicy() {
        val info = currentInputEditorInfo ?: return
        stripOff = TypingRules.stripOff(info.inputType)
        suggestionsEnabled = stripOff == null
        fieldAllowsAutocorrect = TypingRules.allowsAutocorrect(info.inputType, KeyboardSettings.webAutocorrect(this))
        learningAllowed = TypingRules.allowsLearning(info.imeOptions, info.inputType) && !privateNow
        autoCapitalize = KeyboardSettings.autoCapitalize(this)
    }

    /** Chime, the bundled next-word model, in this field: it is English, like the setting that turns it on. */
    private fun neuralAllowedHere(): Boolean = KeyboardSettings.neuralPredictions(this) && learningAllowed && suggestionsEnabled &&
        (fieldWantsSuggestions || addressBar) && !inputIsSensitive && !NeuralPredictionWorker.unavailable && language.english

    /** Gemini Nano and Gemma, for words and proofreading: their prompts are English, so other languages don't ask them. */
    // Gemini Nano and Gemma read English only, and the F-Droid distribution carries neither. The private Model Lab
    // build has its own explicitly chosen model instead.
    private val foundationAllowed: Boolean get() = language.english &&
        (BuildConfig.INCLUDES_NANO || BuildConfig.INCLUDES_GEMMA || BuildConfig.MODEL_LAB)

    /** The phone's own Spanish ("es-MX") where it has one, for dictation in that language. */
    private fun speechLanguage(): String {
        val locales = resources.configuration.locales
        return language.speechTag((0 until locales.size()).map { locales[it] })
    }

    override fun languageChooserOffered(): Boolean = KeyboardSettings.languages(this).size > 1

    /** What the space bar says: the language being typed, once there is more than one to choose from. */
    private fun spaceLabel(): String = if (KeyboardSettings.languages(this).size > 1) language.nativeName else ""

    /** Loads the word list off the main thread, with or without the swear-word list. */
    private fun loadDictionary(includeProfanity: Boolean) {
        loadedWithProfanity = includeProfanity
        dictionaryReady = false
        // Only the newest load may publish; a slower older one (setting toggled quickly, a language switch) is dropped.
        val generation = ++dictionaryGeneration
        val lang = language
        thread(name = "localtype-dictionary", isDaemon = true) {
            // The current-words list, the modern additions, emoji keywords and the corpus prior are English.
            // A symbols keyboard (IPA) has no lists at all; every read below then fails to the empty value.
            val vocabulary = if (!lang.english) CurrentVocabulary.EMPTY else runCatching {
                CurrentVocabulary.parse(assets.open(CurrentVocabulary.ASSET).bufferedReader().use { it.readText() })
            }.getOrDefault(CurrentVocabulary.EMPTY)
            val swears = if (!lang.words) emptyList() else runCatching { assets.open(lang.profanityAsset).bufferedReader().readLines() }.getOrDefault(emptyList())
            val loaded = if (!lang.words) WordDictionary.EMPTY else runCatching {
                val main = assets.open(lang.wordsAsset).bufferedReader().readLines() +
                    if (lang.english) assets.open(MODERN_ASSET).bufferedReader().readLines() else emptyList()
                WordDictionary.parse((if (includeProfanity) main + swears else main).asSequence(), lang)
            }.getOrDefault(WordDictionary.EMPTY)
            val emoji = if (!lang.english) EmojiSuggestions.EMPTY else if (emojiSuggestions.size > 0) emojiSuggestions else runCatching {
                assets.open(EMOJI_ASSET).bufferedReader().useLines { EmojiSuggestions.parse(it) }
            }.getOrDefault(EmojiSuggestions.EMPTY)
            // Modern additions count as known words too, so a rare one ("bday") is never corrected away.
            val known = if (!lang.words) Lexicon.EMPTY else if (lexicon.size > 0 && lexiconLanguage === lang) lexicon else runCatching {
                val modern = if (lang.english) assets.open(MODERN_ASSET).bufferedReader().readLines().map { it.substringBefore('\t') } else emptyList()
                assets.open(lang.lexiconAsset).bufferedReader().useLines { Lexicon.parse(it + modern.asSequence()) }
            }.getOrDefault(Lexicon.EMPTY)
            val swearSet = swears.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
            // Read once per process, so every logged field in it ranks with the same prior, even after the system
            // replaces this service with a new instance. It held 864,256 bytes of heap in the dalvikvm bench on both
            // phones (model/README.md, "On the phones").
            val priorStartedAt = SystemClock.elapsedRealtime()
            // Another language's prior, read once per process like English's; null when it has none or it can't be read.
            val languagePrior = if (lang.english) null else lang.priorAsset?.let { asset ->
                languagePriors[asset] ?: runCatching { assets.open(asset).use { CorpusPrior.read(it) } }.getOrNull()?.also { languagePriors[asset] = it }
            }
            val settled = processPriorSettled || !lang.english
            val prior = if (settled) processPrior else runCatching {
                assets.open(CorpusPrior.ASSET).use { CorpusPrior.read(it) }
            }.getOrNull()
            val priorMs = SystemClock.elapsedRealtime() - priorStartedAt
            val british = if (!lang.english) BritishSpelling.EMPTY else if (britishWords.size > 0) britishWords else runCatching {
                assets.open(BritishSpelling.ASSET).bufferedReader().useLines { BritishSpelling.parse(it) }
            }.getOrDefault(BritishSpelling.EMPTY)
            corrector.prepare(loaded, known)
            handler.post {
                if (generation != dictionaryGeneration || destroyed) return@post
                profanityWords = swearSet
                lexicon = known
                lexiconLanguage = lang
                emojiSuggestions = emoji
                britishWords = british
                corrector.forgetRecentAnswers()
                dictionary = loaded
                localModel.dictionary = loaded
                sharedVocabulary = vocabulary
                fieldVocabulary = vocabulary.at(Math.floorDiv(System.currentTimeMillis(), 86_400_000L))
                localModel.vocabulary = fieldVocabulary
                // With the dictionary, never before it: the log's gate waits for this post, so no logged offer ranks without both.
                if (!settled) diag { "corpus prior ${if (prior != null) "loaded" else "unavailable"} ms=$priorMs" }
                if (lang.english && !processPriorSettled) {
                    processPrior = prior
                    processPriorSettled = true
                }
                // Another language's prior ships only once it has cleared a bar written first (decision 3).
                corpusPrior = if (lang.english) processPrior else languagePrior
                localModel.prior = corpusPrior
                publishedWithProfanity = includeProfanity
                dictionaryReady = true
                // A logged field that opened during the load joins the log now; anything learned meanwhile was unlogged.
                openPendingLoggedField()
                scheduleRefresh()
            }
        }
    }

    /** Fullscreen only in a short landscape window, not on a tablet or an unfolded Fold ([TypingRules.fullscreen]). */
    override fun onEvaluateFullscreenMode(): Boolean =
        TypingRules.fullscreen(super.onEvaluateFullscreenMode(), resources.configuration.screenHeightDp)

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        keyboardRoot = this
        // New views (first show, rotation, a theme change) start on the keys; the old panel went with the old views.
        if (emojiQuery != null) {
            mode = emojiSearchMode
            shiftState = emojiSearchShift
        }
        if (localInputKind != null) {
            mode = localInputMode; shiftState = localInputShift; localInputKind = null
        }
        symbolsPanel = null
        symbolsPanelRoot = null
        effectsPanel = null
        effectsPanelRoot = null
        localInputHeader = null
        panel = null
        // The strip that showed the words being heard is going away with the old views.
        stopDictationNow(commit = false)
        emojiQuery = null
        emojiPanel = null
        emojiPanelRoot = null
        savedPanel = null
        savedPanelRoot = null
        toolsPanel = null
        toolsPanelRoot = null
        arrowPad = null
        arrowsPanelRoot = null
        contactsPanel = null
        contactsPanelRoot = null
        passwordsPanel = null
        passwordsPanelRoot = null
        panelToggles.clear()
        savedTexts?.forgetLastChange()
        emojiPopup.dismiss()
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.kb_background))
        addView(candidateStrip().also { candidateStripView = it }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(stripHeight.dp)))
        keyArea = KeyArea(context).also { area ->
            keyboardView = KeyboardView(this@LocalTypeInputMethodService).also { view ->
                view.listener = this@LocalTypeInputMethodService
                view.setPadding(0, 0, 0, dp(4))
                area.addView(view, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            oneHandedRail = oneHandedRail(context).also { rail ->
                area.rail = rail
                area.addView(rail, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            }
            addView(area, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // A view swapped in by setInputView isn't sent insets until something asks, which left the bottom row
        // under the navigation bar; ask on every attach.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = ViewCompat.requestApplyInsets(view)
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        // Edge-to-edge IME windows draw the navigation bar over the bottom row unless we pad for it.
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            // The gesture handle is only 24 dp, but the system's back and keyboard-switch buttons
            // sit in a 48 dp tappable strip; pad for whichever is taller.
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            val bars = androidx.core.graphics.Insets.max(nav, tappable)
            val bottom = bars.bottom + dp(KeyboardSettings.bottomClearance(this@LocalTypeInputMethodService))
            val changed = view.paddingLeft != bars.left || view.paddingRight != bars.right || view.paddingBottom != bottom
            view.setPadding(bars.left, 0, bars.right, bottom)
            // Insets that land after the window's height was settled for this frame re-measure against the old
            // height, and the padding's own layout request is lost; ask again so the window can grow to fit.
            if (changed) view.post { view.requestLayout() }
            insets
        }
        applyLayout()
        applyKeyWidth()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        inputViewStarts++
        if (restarting) inputViewRestarts++
        cancelSwipeWork()
        ownedSpacing.reset()
        super.onStartInputView(info, restarting)
        closeTools()
        personalLearning.reset(if (restarting) PersonalContextTrace.ResetReason.RESTART else PersonalContextTrace.ResetReason.FIELD)
        completedPersonal = null
        personalWordOwned = false
        boundaryPersonalContext = emptyList()
        spaceProofread.reset()
        selectionProofreadEligible = false
        proofreadJob?.cancel()
        dismissProposal()
        verifiedWordReceipt = null
        cancelRecentCorrection()
        val identity = "${info.packageName}:${info.fieldId}:${info.fieldName}:${info.inputType}:${info.imeOptions}"
        val sameEditorRestart = TypingRules.sameEditorRestart(restarting, identity, fieldIdentity)
        val observed = observedSelection()
        val previousPosition = editorEdits.expected
        // Android may finish the input view before delivering a same-editor restart.
        val sameFieldRestart = restarting && info.fieldId > 0 &&
            info.inputType and InputType.TYPE_MASK_VARIATION != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT &&
            identity == fieldIdentity && expectedContext != null && currentContext() == expectedContext &&
            observed != null && observed == previousPosition
        if (!sameFieldRestart) clearCharacterTools()
        val retainTrace = sameFieldRestart && trace.hasTouchesFor(localModel.activePrefix(expectedContext!!))
        // Dictation survives only a restart proven to be the same field with the text and caret this keyboard left.
        // Two boxes on one web page share an identity, so any other restart may be another field: stop listening.
        if (!sameFieldRestart) stopDictationNow(commit = false)
        compatibilityEditorRestarted = !TypingRules.prefersRangeReplacement(info.inputType, Build.VERSION.SDK_INT) && restarting && identity == fieldIdentity
        fieldIdentity = identity
        editorEdits.start(observed?.start ?: info.initialSelStart, observed?.end ?: info.initialSelEnd, retainHistory = sameFieldRestart)
        viewShown = true
        fieldVocabulary = sharedVocabulary.at(Math.floorDiv(System.currentTimeMillis(), 86_400_000L))
        localModel.vocabulary = fieldVocabulary
        // A theme change in settings rebuilds the views, which read their colors once.
        val theme = ThemeMode.of(this)
        if (theme != themeMode) {
            themeMode = theme
            themed = null
            setInputView(onCreateInputView())
        }
        val inputType = info.inputType
        selectionActive = info.initialSelStart >= 0 && info.initialSelEnd >= 0 && info.initialSelStart != info.initialSelEnd
        inputIsSensitive = TypingRules.isSensitive(inputType)
        // The last field's offer goes before this field's kind is known to the strip.
        dropExpansion()
        stripOff = TypingRules.stripOff(inputType)
        suggestionsEnabled = stripOff == null
        fieldWantsSuggestions = TypingRules.wantsSuggestions(inputType)
        fieldAllowsAutocorrect = TypingRules.allowsAutocorrect(inputType, KeyboardSettings.webAutocorrect(this))
        learningAllowed = TypingRules.allowsLearning(info.imeOptions, inputType)
        // Private for now lasts for the field: a new one clears it, the same one restarting keeps it.
        if (!restarting) privateNow = false
        if (privateNow) learningAllowed = false
        resetNeuralSession()
        addressBar = TypingRules.predictsInAddressBar(inputType)
        addressProse = true
        neuralEnabled = neuralAllowedHere()
        if (neuralEnabled && !neuralReady) loadNeuralModel()
        // A new field: forget private predictions and private kept words, and ignore answers still in flight for the old one.
        privateModelCache.clear()
        keptWords -= fieldKeptWords
        fieldKeptWords.clear()
        cacheGeneration++
        manualFoundationContext = null
        autocorrectEnabled = KeyboardSettings.autocorrect(this)
        autoCapitalize = KeyboardSettings.autoCapitalize(this)
        applySymbolsPolicy()
        modelOnTap = KeyboardSettings.modelOnTap(this)
        readSparkle(recheck = true)
        tripleSpaceProofread = KeyboardSettings.tripleSpaceProofread(this)
        val keepLoaded = KeyboardSettings.keepModelLoaded(this)
        foundationModel.setKeepLoaded(keepLoaded)
        if (keepLoaded && foundationAllowed) {
            // Warm the model up as soon as the keyboard opens, so the first pause already has it.
            uiScope.launch { runCatching { withContext(Dispatchers.Default) { foundationModel.status() } } }
        }
        emojiEnabled = KeyboardSettings.emoji(this)
        numberRow = KeyboardSettings.numberRow(this)
        commonSymbolHolds = KeyboardSettings.commonSymbolHolds(this)
        spaceBar = KeyboardSettings.spaceBar(this)
        britishSpelling = KeyboardSettings.britishSpelling(this)
        currency = KeyboardSettings.currency(this)
        applyStripHeight(KeyboardSettings.stripHeight(this))
        KeyHaptics.enabled = KeyboardSettings.vibrateOnKeyPress(this)
        profanityAllowed = KeyboardSettings.profanity(this)
        if (profanityAllowed != loadedWithProfanity) loadDictionary(profanityAllowed)
        val placement = KeyboardSettings.voiceKey(this)
        if (!sameEditorRestart || voiceFieldType != inputType || placement != voiceKey) {
            voiceKey = placement
            updateVoiceAvailability(inputType)
        }
        slideGestures = KeyboardSettings.slideGestures(this)
        keyboardView?.slideSpeed = KeyboardSettings.slideSpeed(this)
        keyboardView?.keyShapes = KeyboardSettings.keyShapes(this)
        keyboardView?.motor = KeyboardSettings.motor(this)
        keyboardView?.inactiveGaps = KeyboardSettings.inactiveGaps(this)
        // Read at each touch, so it follows the offsets as they learn and the language's own set.
        keyboardView?.aimShift = { key ->
            key.label.singleOrNull()?.lowercaseChar()?.let { letter ->
                touchOffsets.offset(letter).let { MotorRules.shift(touchOffsets.count(letter), it.x, it.y) }
            }
        }
        twoFingerGestures = KeyboardSettings.twoFingerGestures(this)
        gestureActions = TwoFingerSwipe.entries.associateWith { KeyboardSettings.gestureAction(this, it) }
        gestureUndo = null
        cleanLinks = KeyboardSettings.cleanPastedLinks(this)
        rememberDomains = KeyboardSettings.rememberDomains(this)
        oneHanded = KeyboardSettings.oneHanded(this)
        wideKeys = KeyboardSettings.wideKeys(this)
        applyKeyWidth()
        // Contact names stay protected from correction while the permission stands; a read is at most every few minutes.
        readContactsIfStale(CONTACTS_FIELD_STALE_MS)
        hostField = when {
            TypingRules.isUrl(inputType) -> Expansions.Field.ADDRESS
            TypingRules.isEmail(inputType) -> Expansions.Field.MAIL
            else -> Expansions.Field.TEXT
        }
        // Text another field held, or that this one changed while it restarted, was not typed here.
        if (!sameFieldRestart) typedRun.reset()
        corrector.forgetRecentAnswers()
        diag { "field pkg=${info.packageName} inputType=0x${Integer.toHexString(inputType)} imeOptions=0x${Integer.toHexString(info.imeOptions)} suggestions=$suggestionsEnabled learning=$learningAllowed autocorrect=$autocorrectEnabled" }
        // Text already in the field was not typed here; editing it counts as revisiting.
        if (!retainTrace) revisitTrace(if (restarting) CorrectionDiagnostics.Revisit.RESTART else CorrectionDiagnostics.Revisit.FIELD_START)
        if (!sameFieldRestart) { expectedContext = null; lastAutocorrect = null; autoSpaced = false; cleanedPaste = null; hideLinkNotice(); hideGestureNotice(); hideNotice() }
        if (TypingRules.startsNewLayout(restarting, fieldLayout, inputType)) {
            // A new field starts on the keys. A restart is usually the same field (some apps restart on every change),
            // so a panel stays; one whose field wants other keys is Chrome moving to the next box on the page.
            closePanel()
            mode = TypingRules.layoutFor(inputType)
            fieldLayout = mode
            shiftState = KeyboardView.ShiftState.OFF
            autoSpaced = false
            lastAutocorrect = null
        }
        // Settings may have cleared learned words since this service started.
        reloadLearnedIfChanged()
        // The log follows the learning gate exactly, so it never sees a field learning skips.
        // While the dictionary is still loading the field waits, so the header and O.dict never describe a list the strip didn't use.
        // Only English is logged: the log's replay and its word list are English. British spelling changes the words
        // the strip shows from the ones it ranked, which the log's slot sources can't express, so it isn't logged.
        val wantsLog = KeyboardSettings.predictionLog(this) && learningAllowed && fieldWantsSuggestions && language.english &&
            !britishSpelling
        logAllowed = loggedFieldGate.start(wantsLog && blockedStore.words.isEmpty(), dictionaryReady, restarting, learningStore.ready && blockedStore.ready)
        if (logAllowed) openLoggedField(restarting)
        // An uncertain text receipt invalidates correction eligibility, not a finger already
        // pressing an unchanged key in this editor. Reassigning the layout would drop that key.
        applyLayout(preserveTouches = sameEditorRestart)
        keyboardView?.enterLabel = enterLabel(info)
        updateShiftFromEditor()
        refreshCandidates()
        // Keys typed while this restart was on its way go out now, through the new connection, in order.
        val held = pendingKeys.size
        if (pendingKeys.started(identity, restarting) == PendingKeys.Start.DROPPED) {
            heldKeysDropped += held
            handler.removeCallbacks(pendingKeysExpiry)
        }
        // Plain appends the restart swallowed after their last read go out first, ahead of the keys held since.
        val lost = if (sameEditorRestart) appendLedger.missing(identity, rawContext()) else { appendLedger.clear(); emptyList() }
        if (lost.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            for (text in lost.asReversed()) pendingKeys.holdFirst(HeldKey(KeyAction.Text(text), null, raw = true), identity, now)
            heldKeysResent += lost.size
        }
        if (!pendingKeys.isEmpty) handler.post(pendingKeysDrain)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputViewFinishes++
        if (finishingInput) finishingInputViews++
        cancelSwipeWork()
        // A proofread or its proposal belongs to this field; dismissing first lets the removeCallbacks below drop its refresh.
        proofreadJob?.cancel()
        spaceProofread.reset()
        dismissProposal()
        closePanel()
        closeTools()
        viewShown = false
        personalLearning.reset(PersonalContextTrace.ResetReason.FIELD)
        boundaryPersonalContext = emptyList()
        completedPersonal = null
        resetNeuralSession()
        cancelRecentCorrection()
        emojiPopup.dismiss()
        pasteClip = null
        cancelFoundationRequest()
        // The field's view is gone; nothing listens for a field that is not on screen.
        stopDictationNow(commit = false)
        onDeleteSlideEnd(commit = false)
        // An offer made for this field's text is not shown over the next field, even for a frame.
        dropExpansion()
        typedRun.reset()
        // Its chips go with it; Android answers again when a field is next shown.
        inlineAutofill?.clear()
        cleanedPaste = null
        hideLinkNotice()
        gestureUndo = null
        hideGestureNotice()
        hideNotice()
        handler.removeCallbacks(refreshRunnable)
        loggedFieldGate.finish()
        if (logAllowed) {
            PredictionLog.endField()
            // A refresh that lands after this (a model answer, a clip timer) belongs to no logged field.
            logAllowed = false
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionActive = newSelStart >= 0 && newSelEnd >= 0 && newSelStart != newSelEnd
        // Intermediate receipts need a fresh observation to distinguish them from a real move
        // back to the same old position. Latest receipts need no additional synchronous read.
        val received = EditorEditState.Selection(newSelStart, newSelEnd)
        if (received != editorEdits.expected) cancelSwipeWork()
        val observed = if (received != editorEdits.expected) observedSelection() else null
        when (editorEdits.acknowledge(newSelStart, newSelEnd, observed)) {
            EditorEditState.Acknowledgement.UNEXPLAINED -> {
                // A WebView can anchor the keyboard at a caret the page then moves before the first letter.
                // When the callback and a fresh read agree with each other and the text before the caret
                // is exactly what this keyboard appended, the editor is where the typing put it and only
                // the numeric anchor was wrong: acknowledge() has already re-anchored, so the word stays own.
                val context = expectedContext
                if (received == observed && context != null && currentContext() == context) {
                    correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.SELECTION_REANCHORED)
                    // The word being typed stays own, but the caret was not where this keyboard thought. The same
                    // text can stand before two places in a field, so words completed before this are no longer
                    // known to be the ones before the caret.
                    personalLearning.reset(PersonalContextTrace.ResetReason.CURSOR)
                    pendingPeriodPersonalContext = emptyList()
                } else {
                    if (received == observed) correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.SELECTION_READ_AGREES)
                    invalidateUnexplainedSelection()
                }
            }
            EditorEditState.Acknowledgement.DELAYED -> scheduleDelayedSelectionCheck()
            else -> Unit
        }
        scheduleRefresh()
    }

    /** The cursor moved somewhere this keyboard did not put it: the current word and pending work are no longer its own. */
    private fun invalidateUnexplainedSelection() {
        correctionDiagnostics.revisit(when (editorEdits.lastCause) {
            EditorEditState.Cause.NEGATIVE -> CorrectionDiagnostics.Revisit.SELECTION_NEGATIVE
            EditorEditState.Cause.NO_ANCHOR -> CorrectionDiagnostics.Revisit.SELECTION_NO_ANCHOR
            EditorEditState.Cause.READ_MISSING -> CorrectionDiagnostics.Revisit.SELECTION_READ_MISSING
            EditorEditState.Cause.READ_MISMATCH -> CorrectionDiagnostics.Revisit.SELECTION_READ_MISMATCH
            EditorEditState.Cause.UNKNOWN_RECEIPT -> CorrectionDiagnostics.Revisit.SELECTION_UNKNOWN_RECEIPT
            EditorEditState.Cause.VERIFY_FAILED -> CorrectionDiagnostics.Revisit.SELECTION_VERIFY_FAILED
            EditorEditState.Cause.NONE -> CorrectionDiagnostics.Revisit.SELECTION
        })
        caretLeftOwnPosition()
    }

    /** What stops being this keyboard's own once the caret is no longer where its typing left it. */
    private fun caretLeftOwnPosition() {
        // A delete slide was measured where the caret was; it does not follow it somewhere else.
        cancelDeleteSlide()
        handler.removeCallbacks(delayedSelectionCheck)
        personalLearning.reset(PersonalContextTrace.ResetReason.CURSOR)
        personalWordOwned = false
        spaceProofread.reset()
        proofreadJob?.cancel()
        dismissProposal()
        resetNeuralSession()
        expectedContext = null
        cancelRecentCorrection()
        autoSpaced = false
        // Undo only applies to delete right after the correction, at the same spot.
        lastAutocorrect = null
        gestureUndo = null
        trace.revisit()
        // The run was typed where the caret was. The same letters standing somewhere else are not it.
        typedRun.reset()
        updateShiftFromEditor()
    }

    // A WebView's reads trail its commits, so a receipt for an earlier letter can arrive while the
    // read still names that letter's position. Deciding "moved back" there threw away the word on
    // every fast web typo (reserve A, 2026-10-09: 12 to 18 of 30 web typos corrected, by phone and
    // run). A later fresh read settles it instead, within EditorEditState.maxVerifications attempts.
    private var delayedSelectionGeneration = -1L
    private val delayedSelectionCheck = Runnable {
        if (editorEdits.generation != delayedSelectionGeneration) return@Runnable
        when (editorEdits.verify(observedSelection())) {
            EditorEditState.Acknowledgement.UNEXPLAINED -> invalidateUnexplainedSelection()
            EditorEditState.Acknowledgement.DELAYED -> scheduleDelayedSelectionCheck()
            else -> Unit
        }
    }

    private fun scheduleDelayedSelectionCheck() {
        delayedSelectionGeneration = editorEdits.generation
        handler.removeCallbacks(delayedSelectionCheck)
        handler.postDelayed(delayedSelectionCheck, DELAYED_SELECTION_CHECK_MS)
    }

    override fun onDestroy() {
        getSharedPreferences("keyboard_state", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(correctionSettingsListener)
        cancelFoundationRequest()
        dictationSession.stop()
        dictation?.close()
        dictation = null
        // Drops the chips and its own pending timer, so nothing calls back into a destroyed service.
        inlineAutofill?.clear()
        handler.removeCallbacksAndMessages(null)
        uiScope.cancel()
        destroyed = true
        contactsCancel?.cancel()
        contactsCancel = null
        speech?.shutdown()
        speech = null
        neuralWorker?.close()
        swipeWorker?.close(); swipeWorker = null
        clipboard?.removePrimaryClipChangedListener(clipListener)
        // The list outlives this service; a newer one may already have put its own listener there.
        savedTexts?.let { if (it.onChange === savedTextListener) it.onChange = null }
        if (this::foundationModel.isInitialized) foundationModel.close()
        super.onDestroy()
    }

    // region Keys

    override fun onKeyTouch(key: Key, dx: Float, dy: Float) {
        pendingTouch = key.label.singleOrNull()?.lowercaseChar()?.let {
            if (language.english) KeyGeometry.touchOn(it, dx, dy) else language.geometry.touchOn(it, dx, dy)
        }
    }

    override fun onKey(action: KeyAction) {
        // Panel-local input returns before Voice, dictation, editor reads, learning or logging.
        if (localInputKind != null) {
            pendingTouch = null
            cancelSwipeWork()
            return characterToolKey(action)
        }
        // The voice key starts or stops dictation as the strip's mic does; it is not a key that edits the field, so it
        // never reaches the typing path, held keys or a running dictation's stop-on-any-key. A swipe still being decoded
        // is dropped first, as any key drops it, so its word can't land after dictation starts.
        if (action == KeyAction.Voice) {
            cancelSwipeWork()
            pendingTouch = null
            return onVoiceTapped()
        }
        cancelSwipeWork()
        val touch = pendingTouch
        pendingTouch = null
        val start = addedCorrectionCpu
        try { dispatchKey(action, touch) } finally { correctionDiagnostics.recordKeyCpu(addedCorrectionCpu - start) }
    }

    private fun dispatchKey(action: KeyAction, touch: Touch?, replayed: Boolean = false) {
        emojiPopup.dismiss()
        // Any key closes the tools row: typing wants the words back.
        closeTools()
        // A key ends a notice. First, so one about the dictated words that go in next is still there after it.
        hideNotice()
        // A key ends dictation, and what was heard goes in first so a late result can never land after the key.
        if (dictationSession.listening) stopDictationNow()
        if (action != KeyAction.Space || replayed) spaceProofread.reset()
        // Any other key between two shift taps makes them two single taps, not a double tap.
        if (action != KeyAction.Shift) lastShiftTap = 0L
        // Searching emoji: the key is the query's. This returns before anything reads or edits the field, so
        // the query never reaches the app, the trace, learning, the typing log, autocorrect or the model.
        if (emojiQuery != null) return searchKey(action)
        if (!inlineTyped) {
            inlineTyped = true
            if (inlineRow?.visibility == View.VISIBLE) updateSlotVisibility()
        }
        // A cleaned paste's way back is the very next key: delete takes it up below, anything else gives it up.
        val cleaned = if (action == KeyAction.Backspace) takeCleanedPaste() else null
        cleanedPaste = null
        hideLinkNotice()
        hideGestureNotice()
        if (cleaned != null) lastAutocorrect = cleaned
        if (!replayed) {
            keyContext = null
            if (holdForRestart(action, touch)) return
        }
        if (action != KeyAction.Space && action != KeyAction.Enter) {
            pendingPeriod = null
            pendingPeriodPersonalContext = emptyList()
        }
        hideCorrectionFeedback()
        if (proposalRow?.visibility == View.VISIBLE) { proofreadJob?.cancel(); dismissProposal() }
        val ic = currentInputConnection ?: return
        // Forget learned words since the last refresh: a separator would otherwise correct and learn with the forgotten model.
        syncLearnedState()
        // Space or Enter ends a run: an address typed in full here has its host remembered.
        if (action == KeyAction.Space || action == KeyAction.Enter) rememberTypedHost()
        when (action) {
            is KeyAction.Text -> typeText(action.value, touch)
            KeyAction.Space -> {
                handlingSpace = true
                try { typeSpace() } finally { handlingSpace = false }
            }
            KeyAction.Backspace -> {
                contractionAnchor = null
                accommodationAnchor = null
                recentCorrection.cancelPending()
                correctionOffer = null
                autoSpaced = false
                if (undoAutocorrect()) {
                    revisitTrace(CorrectionDiagnostics.Revisit.UNDO)
                    scheduleRefresh()
                    return
                }
                personalLearning.reset(PersonalContextTrace.ResetReason.CURSOR)
                personalWordOwned = false
                recentCorrection.clearUndo()
                val selected = ic.getSelectedText(0)
                // Read before deleting: the editor may not have applied the deletion when asked right after.
                val before = currentContext()
                // A whole emoji (❤️, 👨‍👩‍👧, a flag) or a letter with its accents goes in one press, held or not.
                val visible = TypingRules.lastGraphemeLength(before)
                val plainDelete = selected.isNullOrEmpty() && !selectionActive
                markEdit(deletedBefore = if (plainDelete) visible else 0, keepsTypedRun = plainDelete)
                if (plainDelete) typedRun.deleted(before, visible)
                expectedContext = if (selected.isNullOrEmpty()) before.dropLast(visible) else null
                when {
                    !selected.isNullOrEmpty() -> ic.commitText("", 1)
                    ic.getTextBeforeCursor(1, 0).isNullOrEmpty() -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    visible > 0 -> ic.deleteSurroundingText(visible, 0)
                    else -> ic.deleteSurroundingTextInCodePoints(1, 0)
                }
                if (!selected.isNullOrEmpty()) revisitTrace(CorrectionDiagnostics.Revisit.SELECTION_DELETE)
                else if (trace.backspace(localModel.activePrefix(TypingRules.dropLastGrapheme(before)))) correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.BACKSPACE_PREFIX)
                updateShiftFromEditor()
            }
            KeyAction.Enter -> typeEnter()
            KeyAction.Shift -> toggleShift()
            KeyAction.ToSymbols -> setMode(KeyboardLayout.Mode.SYMBOLS)
            KeyAction.ToMoreSymbols -> setMode(KeyboardLayout.Mode.MORE_SYMBOLS)
            KeyAction.ToLetters -> setMode(KeyboardLayout.Mode.LETTERS)
            KeyAction.ToFieldLayout -> setMode(TypingRules.layoutFor(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT))
            KeyAction.SwitchKeyboard -> switchKeyboard()
            KeyAction.Voice -> Unit
        }
        scheduleRefresh()
    }

    override fun onLongPressSpace() {
        if (localInputKind != null) return
        if (KeyboardSettings.languages(this).size > 1) showLanguageChooser() else showInputMethodPicker()
    }

    /**
     * Holds a text key pressed while an editor restart has retired the input connection: reads through it come back
     * null and the app drops whatever is sent, while the send still reports success. The key is replayed when the same
     * editor starts again ([PendingKeys]). What the key does to the keyboard itself happens now (its case, the one-shot
     * shift, a return to the letters layer), so the next tap lands on the keys the person sees.
     */
    private fun holdForRestart(action: KeyAction, touch: Touch?): Boolean {
        if (!TypingRules.editsText(action)) return false
        if (pendingKeys.isEmpty) {
            if (!pendingKeys.probes(fieldIdentity)) return false
            val ic = currentInputConnection ?: return false
            keyContext = ic.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString()
            if (keyContext != null) return false
        }
        val now = SystemClock.uptimeMillis()
        // Unreachable in practice (64 keys inside one restart); sending it now would type it out of order.
        if (pendingKeys.hold(heldKeyFor(action, touch), fieldIdentity, now) == PendingKeys.Hold.FULL) { heldKeysDropped++; return true }
        handler.removeCallbacks(pendingKeysExpiry)
        handler.postDelayed(pendingKeysExpiry, pendingKeys.remainingMs(now).coerceAtLeast(1))
        return true
    }

    /** A key that met a retired connection before changing anything goes back in front of the held keys. */
    private fun requeueKey(action: KeyAction, touch: Touch?) {
        val held = if (replayingKeys) HeldKey(action, touch) else heldKeyFor(action, touch)
        val now = SystemClock.uptimeMillis()
        if (pendingKeys.holdFirst(held, fieldIdentity, now) == PendingKeys.Hold.FULL) { heldKeysDropped++; return }
        handler.removeCallbacks(pendingKeysExpiry)
        handler.postDelayed(pendingKeysExpiry, pendingKeys.remainingMs(now).coerceAtLeast(1))
    }

    /** The held form of a key: its case is fixed now, and its one-shot shift and layer change happen now. */
    private fun heldKeyFor(action: KeyAction, touch: Touch?): HeldKey {
        return when (action) {
            is KeyAction.Text -> {
                val first = action.value.firstOrNull()
                val text = if (shiftState != KeyboardView.ShiftState.OFF) TypingRules.shifted(action.value) else action.value
                if (shiftState == KeyboardView.ShiftState.ON && first?.isLetter() == true) setShift(KeyboardView.ShiftState.OFF)
                if (action.value.length == 1 && first != null && first in "'’" && onSymbols()) setMode(fieldLayer())
                HeldKey(KeyAction.Text(text), touch)
            }
            KeyAction.Space -> {
                if (onSymbols()) setMode(fieldLayer())
                HeldKey(action, touch)
            }
            else -> HeldKey(action, touch)
        }
    }

    /**
     * Sends held keys through the current connection, oldest first. With [probe], each waits for a connection that
     * answers a read, and a connection retired again stops the drain until the next start; without it (no restart
     * came, so the editor answers reads with null while live) they go out as typed.
     */
    private fun drainPendingKeys(probe: Boolean = true) {
        handler.removeCallbacks(pendingKeysDrain)
        if (pendingKeys.isEmpty) return
        if (!probe && currentInputConnection == null) {
            heldKeysDropped += pendingKeys.clear()
            handler.removeCallbacks(pendingKeysExpiry)
            return
        }
        replayingKeys = true
        try {
            while (!pendingKeys.isEmpty) {
                val ic = currentInputConnection ?: break
                val context = rawContext()
                if (probe && context == null) break
                val held = pendingKeys.next() ?: break
                pendingKeys.progressed(SystemClock.uptimeMillis())
                if (held.raw) {
                    // Text the editor swallowed in a restart: it was already traced and decided, so only the text goes.
                    val text = (held.action as KeyAction.Text).value
                    markEdit(plainAppend = true, appendedCharacters = text.length)
                    if (ic.commitText(text, 1)) {
                        recordAppend(context, text)
                        expectedContext = context?.let { (it + text).takeLast(MAX_CONTEXT_CHARS) }
                    }
                    continue
                }
                if (probe) heldKeysReplayed++ else heldKeysSent++
                keyContext = context
                val start = addedCorrectionCpu
                try { dispatchKey(held.action, held.touch, replayed = true) }
                finally { correctionDiagnostics.recordKeyCpu(addedCorrectionCpu - start) }
            }
        } finally { replayingKeys = false }
        handler.removeCallbacks(pendingKeysExpiry)
        if (pendingKeys.isEmpty) updateShiftFromEditor()
        else handler.postDelayed(pendingKeysExpiry, pendingKeys.remainingMs(SystemClock.uptimeMillis()).coerceAtLeast(1))
    }

    private fun expirePendingKeys() {
        val now = SystemClock.uptimeMillis()
        val held = pendingKeys.size
        when (pendingKeys.expire(fieldIdentity, now)) {
            PendingKeys.Expiry.NONE -> Unit
            PendingKeys.Expiry.WAIT -> {
                handler.removeCallbacks(pendingKeysExpiry)
                handler.postDelayed(pendingKeysExpiry, pendingKeys.remainingMs(now).coerceAtLeast(1))
            }
            PendingKeys.Expiry.SEND -> drainPendingKeys(probe = false)
            PendingKeys.Expiry.DROP -> heldKeysDropped += held
        }
    }

    private fun typeText(value: String, touch: Touch? = null) {
        if (value.isEmpty()) return
        val ic = currentInputConnection ?: return
        val selected = hasEditorSelection()
        val first = value[0]
        lastAutocorrect = null
        if (value == ")") {
            typeClosingParenthesis()
            return
        }
        val finishesWord = value.length == 1 && (!autoSpaced || selected) && TypingRules.attachesToPreviousWord(first)
        if (finishesWord) {
            appendRecentCorrection(value, boundary = true)
            correctionOffer = null
            val edit = separatorEdit(value)
            if (value == ".") {
                // The next letters may be a TLD. Commit the dot exactly now; whitespace can
                // certify the stored sentence correction later, without reconstructing text.
                val raw = WordEdits.Replacement(0, 0, value)
                val applied = applyWordReplacement(raw, fallback = value, precondition = edit.precondition)
                if (applied == raw && edit.learningEligible) {
                    verifiedWordReceipt?.let { receipt ->
                        pendingPeriodPersonalContext = if (fieldLearnsWords && personalWordOwned)
                            personalLearning.context(edit.precondition!!.before.dropLast(edit.prefix.length)).toList() else emptyList()
                        pendingPeriod = DomainTyping.Pending(editorEdits.generation, receipt, edit.prefix,
                            if (edit.replacement.before > 0) edit.replacement.text.dropLast(1) else edit.prefix,
                            edit.precondition!!.before.dropLast(edit.prefix.length),
                            logAllowed && trace.hasTouchesFor(edit.prefix))
                        pendingPeriodWordVerified = personalWordOwned && trace.hasTouchesFor(edit.prefix)
                        // A period typed for a space ("of.the") is offered back at the next word's space.
                        ownedSpacing.dotTyped(correctionIdentity(), receipt, edit.prefix, SystemClock.uptimeMillis(),
                            pendingPeriodWordVerified && !edit.corrected && smallAccommodationsAllowed() && !capsLockUsedForWord &&
                                language.words && ordinarySpacingWord(edit.prefix))
                    }
                }
                autoSpaced = false
                trace.finish()
                // A possible domain separator never learns, logs or shows a repair as applied.
                updateShiftFromEditor()
                return
            }
            // Read before clearing the trace, including when ?/! came from the symbols layer.
            val typedVerified = edit.learningEligible && personalWordOwned && trace.hasTouchesFor(edit.prefix)
            val repairTouches = trace.touchesFor(edit.prefix)
            val touches = trace.touchesFor(edit.prefix).takeIf { learningAllowed && edit.learningEligible && !edit.corrected }
            val applied = applyWordReplacement(edit.replacement, fallback = value, precondition = edit.precondition) ?: return
            if (applied == edit.replacement && edit.corrected) lastAutocorrect = AutoCorrection(edit.prefix, edit.replacement.text)
            autoSpaced = false
            trace.finish()
            if (applied == edit.replacement) {
                learnJustCompletedWord(edit, typedVerified, touches)
                finishCorrection(edit, repairTouches)
                if (value in setOf(",", "!", "?")) finishCommaSpacing(value)
                if (value == ",") verifiedWordReceipt?.let { s ->
                    ownedSpacing.commaTyped(correctionIdentity(), s, edit.prefix, SystemClock.uptimeMillis(),
                        typedVerified && !edit.corrected && smallAccommodationsAllowed() && ordinarySpacingWord(edit.prefix))
                }
                if (value in setOf(":", ";", "!", "?")) {
                    val anchor = accommodationAnchor
                    accommodationAnchor = null
                    verifiedWordReceipt?.let { s ->
                        val ordinary = if (value in setOf(":", ";")) anchor != null && ordinarySpacingWord(anchor.original)
                            else typedVerified && !edit.corrected && ordinarySpacingWord(edit.prefix)
                        ownedSpacing.punctuationTyped(correctionIdentity(), s, edit.prefix, value, SystemClock.uptimeMillis(),
                            ordinary && smallAccommodationsAllowed() && !capsLockUsedForWord, anchor)
                    }
                }
            }
            updateShiftFromEditor()
            return
        }
        // A replayed key was cased when it was pressed (holdForRestart).
        val shifting = shiftState != KeyboardView.ShiftState.OFF && !replayingKeys
        val appendLength = if (shifting) TypingRules.shifted(value).length else value.length
        // Letters (and apostrophes or hyphens inside a word) extend the word being traced.
        val rawBefore = rawContext()
        // The connection answered this key's probe and now doesn't: a restart retired it before anything changed.
        if (rawBefore == null && keyContext != null) return requeueKey(KeyAction.Text(value), touch)
        val currentBeforeAppend = rawBefore.orEmpty()
        val ownedAppend = !selected && rawBefore != null && (expectedContext == null || currentBeforeAppend == expectedContext)
        if (!ownedAppend) {
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            personalWordOwned = false
        }
        val prefixBefore = localModel.activePrefix(currentBeforeAppend)
        // A language's own marks continue a word as an apostrophe does (the non-joiner in Persian "می‌رود"), and one hold
        // can type a letter with its mark or two letters ("ą́", "لا"): each character joins the word's trace in turn.
        val wordMarks = language.marks
        val continuesWord = (first.isLetter() || ((first in "'’-" || first in wordMarks) && prefixBefore.isNotEmpty())) &&
            (value.length == 1 || (!language.english && language.words && value.all { it.isLetter() || it in wordMarks }))
        if (continuesWord) {
            if (prefixBefore.isEmpty()) {
                personalWordOwned = ownedAppend && fieldLearnsWords && first.isLetter()
                capsLockUsedForWord = false
            }
            if (first.isLetter() && capsLocked()) capsLockUsedForWord = true
            var mismatch = trace.letter(prefixBefore, touch.takeIf { first.isLetter() })
            for (i in 1 until value.length) mismatch = trace.letter(prefixBefore + value.take(i), null) || mismatch
            if (mismatch) correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.LETTER_PREFIX)
        } else {
            personalWordOwned = false
            trace.finish()
        }
        val attaching = WordEdits.attachPunctuation(currentBeforeAppend, value, autoSpaced, selected)
        var provisionalSpace = false
        if (attaching != null) {
            val applied = applyWordReplacement(attaching, fallback = value) ?: return
            provisionalSpace = applied == attaching && attaching.autoSpaced
            if (first in ".!?\n") personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            else if (first in "'’") personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            if (first in "'’") revisitTrace(CorrectionDiagnostics.Revisit.APOSTROPHE) // Extending a tapped word is an edit, not another learned word.
        } else {
            val text = if (shifting) TypingRules.shifted(value) else value
            markEdit(plainAppend = !selected, appendedCharacters = appendLength)
            if (ic.commitText(text, 1)) {
                if (!selected) recordAppend(rawBefore, text, keyContext)
                // Typing over a selection, as in an address bar that selects the old address, starts the run
                // again when the selection began the field or followed white space: the read is of the text before it.
                else if (hostsAllowed) typedRun.appended(rawBefore, text)
                expectedContext = (currentBeforeAppend + text).takeLast(MAX_CONTEXT_CHARS)
                appendRecentCorrection(text, boundary = !continuesWord && !first.isLetter() && first !in "'’-" )
            } else { editorEdits.uncertain(); revisitTrace(CorrectionDiagnostics.Revisit.COMMIT_FAILED); expectedContext = null }
        }
        autoSpaced = provisionalSpace
        if (shiftState == KeyboardView.ShiftState.ON && first.isLetter() && !replayingKeys) setShift(KeyboardView.ShiftState.OFF)
        if (value.length == 1 && first in "'’" && onSymbols() && !replayingKeys) setMode(fieldLayer())
        // A field that asks for every letter as a capital gets Shift back after each one; elsewhere a letter leaves it off.
        if (!first.isLetter() || TypingRules.capitalizesEveryLetter(currentInputEditorInfo?.inputType ?: 0)) updateShiftFromEditor()
    }

    private fun typeSpace() {
        val ic = currentInputConnection ?: return
        val boundaryGeneration = editorEdits.generation
        val now = SystemClock.uptimeMillis()
        // Proofreading is English, so in another language three spaces are three spaces.
        val tripleSpaceProofread = tripleSpaceProofread && foundationAllowed
        val gestureInitial = if (tripleSpaceProofread && !replayingKeys && suggestionsEnabled && fieldWantsSuggestions &&
            !inputIsSensitive && mode == KeyboardLayout.Mode.LETTERS) editorSnapshot() else null
        if (tripleSpaceProofread && spaceProofread.consume(now, editorEdits.generation, gestureInitial)) {
            lastSpaceTap = 0L
            autoSpaced = false
            proofreadSentence()
            return
        }
        if (applyPendingPeriod(" ")) {
            spaceProofread.reset()
            lastSpaceTap = SystemClock.uptimeMillis()
            autoSpaced = false
            trace.finish()
            if (onSymbols() && !replayingKeys) setMode(fieldLayer())
            updateShiftFromEditor()
            return
        }
        val automaticSpace = autoSpaced
        appendRecentCorrection(" ", boundary = true)
        correctionOffer = null
        val quickSecondSpace = autoSpaced || now - lastSpaceTap < DOUBLE_SPACE_MS
        val period = WordEdits.doubleSpace(
            ic.getTextBeforeCursor(2, 0)?.toString().orEmpty(), quickSecondSpace, hasEditorSelection(),
            mode == KeyboardLayout.Mode.LETTERS && TypingRules.doubleSpacePeriod(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT),
            autoSpaced = autoSpaced,
        )
        if (period != null) {
            val applied = applyWordReplacement(period, fallback = " ") ?: run { spaceProofread.reset(); return }
            if (tripleSpaceProofread) spaceProofread.committed(now, editorEdits.generation, gestureInitial,
                verifiedWordReceipt, madePeriod = applied == period, automaticSpace = automaticSpace)
            if (applied == period) personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            lastSpaceTap = if (applied == period) 0L else now
            lastAutocorrect = null
        } else {
            lastAutocorrect = null
            val edit = separatorEdit(" ")
            val typedVerified = edit.learningEligible && personalWordOwned && trace.hasTouchesFor(edit.prefix)
            val repairTouches = trace.touchesFor(edit.prefix)
            val touches = trace.touchesFor(edit.prefix).takeIf { learningAllowed && edit.learningEligible && !edit.corrected }
            val applied = applyWordReplacement(edit.replacement, fallback = " ", precondition = edit.precondition) ?: run { spaceProofread.reset(); return }
            if (applied == edit.replacement && edit.replacement == WordEdits.Replacement(0, 0, " ") &&
                edit.precondition?.selection?.collapsed == true) resumeCorrectionAfterBoundary(" ", boundaryGeneration, ic)
            if (tripleSpaceProofread) spaceProofread.committed(now, editorEdits.generation, gestureInitial,
                verifiedWordReceipt, madePeriod = false, automaticSpace = automaticSpace)
            if (applied == edit.replacement && edit.corrected) lastAutocorrect = AutoCorrection(edit.prefix, edit.replacement.text)
            lastSpaceTap = now
            if (applied == edit.replacement) {
                learnJustCompletedWord(edit, typedVerified, touches)
                if (!finishDotSpace(edit) && !finishSmallAccommodation(edit, typedVerified) && !finishContextualContraction(edit, typedVerified))
                    finishCorrection(edit, repairTouches)
            }
        }
        autoSpaced = false
        trace.finish()
        if (onSymbols() && !replayingKeys) setMode(fieldLayer())
        updateShiftFromEditor()
    }

    private fun typeEnter() {
        val ic = currentInputConnection ?: return
        val boundaryGeneration = editorEdits.generation
        val action = currentInputEditorInfo?.let { TypingRules.enterAction(it.imeOptions) }
        if (applyPendingPeriod(if (action == null) "\n" else "")) {
            autoSpaced = false
            trace.finish()
            if (action != null) { cancelRecentCorrection(); resetNeuralSession(); ic.performEditorAction(action) }
            updateShiftFromEditor()
            return
        }
        cancelRecentCorrection()
        val edit = separatorEdit(if (action == null) "\n" else "")
        val verified = edit.learningEligible && personalWordOwned && trace.hasTouchesFor(edit.prefix)
        val repairTouches = trace.touchesFor(edit.prefix)
        val touches = trace.touchesFor(edit.prefix).takeIf { learningAllowed && edit.learningEligible && !edit.corrected }
        val applied = if (edit.replacement.text.isEmpty()) edit.replacement.takeIf { edit.precondition != null && editorSnapshot() == edit.precondition } else applyWordReplacement(edit.replacement, precondition = edit.precondition)
        if (action == null && applied == edit.replacement && edit.replacement == WordEdits.Replacement(0, 0, "\n") &&
            edit.precondition?.selection?.collapsed == true) resumeCorrectionAfterBoundary("\n", boundaryGeneration, ic)
        if (applied == edit.replacement) {
            learnJustCompletedWord(edit, verified, touches)
            if (action == null) finishCorrection(edit, repairTouches)
            else if (edit.corrected && verified && logAllowed) PredictionLog.repair(RepairEvent(
                edit.beforeSeparator.dropLast(localModel.activePrefix(edit.beforeSeparator).length), edit.prefix,
                localModel.activePrefix(edit.beforeSeparator), 'P'))
        }
        personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        // A rejected edit may leave the provisional separator intact. Keep it only
        // when a fresh coherent receipt proves that neither text nor caret changed.
        autoSpaced = autoSpaced && applied == null && edit.replacement.before == 1 &&
            edit.precondition != null && editorSnapshot() == edit.precondition
        lastAutocorrect = if (action == null && applied == edit.replacement && edit.corrected)
            AutoCorrection(edit.prefix, edit.replacement.text, personal = completedPersonal) else null
        trace.finish()
        if (action != null) {
            cancelRecentCorrection()
            resetNeuralSession()
            ic.performEditorAction(action)
        }
        updateShiftFromEditor()
    }

    /** A verified delimiter ends restart suppression; it never makes a revisited word owned again. */
    private fun resumeCorrectionAfterBoundary(delimiter: String, generation: Long, connection: android.view.inputmethod.InputConnection) {
        if (!compatibilityEditorRestarted || connection !== currentInputConnection) return
        val receipt = verifiedWordReceipt
        val observed = editorSnapshot()
        if (connection === currentInputConnection && RestartCorrectionRecovery.canResume(delimiter, generation,
                editorEdits.generation, receipt, observed, editorEdits.expected)) {
            compatibilityEditorRestarted = false
            restartBoundaryRecoveries++
        }
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        val next = TypingRules.shiftAfterTap(shiftState, quick = now - lastShiftTap < CAPS_LOCK_TAP_MS)
        // Entering or leaving caps lock ends the double-tap window, so a quick tap after unlocking doesn't lock again.
        lastShiftTap = if (next == KeyboardView.ShiftState.LOCKED || shiftState == KeyboardView.ShiftState.LOCKED) 0L else now
        setShift(next)
    }

    override fun onCapsLock() {
        setShift(if (shiftState == KeyboardView.ShiftState.LOCKED) KeyboardView.ShiftState.OFF else KeyboardView.ShiftState.LOCKED)
        lastShiftTap = 0L
        // Suggestions follow shift, as after a shift tap.
        scheduleRefresh()
    }

    private fun capsLocked() = shiftState == KeyboardView.ShiftState.LOCKED

    private fun setShift(state: KeyboardView.ShiftState) {
        shiftState = state
        keyboardView?.shiftState = state
    }

    /** Follows the field's own capitalization request, e.g. sentence caps in a message box, unless automatic capitalization is off; not while the keys type an emoji search. */
    private fun updateShiftFromEditor() {
        // Replayed keys keep the case they were pressed with; the drain syncs shift once it finishes.
        if (replayingKeys || emojiQuery != null || localInputKind != null || shiftState == KeyboardView.ShiftState.LOCKED || mode != KeyboardLayout.Mode.LETTERS) return
        val info = currentInputEditorInfo ?: return
        var caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        // Android doesn't know "¿" and "¡" open a sentence; "Hola. ¿" still wants a capital next.
        if (caps == 0 && language.openingMarks.isNotEmpty() && info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) {
            val before = currentInputConnection?.getTextBeforeCursor(OPENING_MARK_CONTEXT, 0)?.toString().orEmpty()
            if (before.trimEnd(' ', '\t').lastOrNull()?.let { it in language.openingMarks } == true && localModel.isSentenceStart(before))
                caps = TextUtils.CAP_MODE_SENTENCES
        }
        setShift(if (TypingRules.autoShift(caps, autoCapitalize, info.inputType)) KeyboardView.ShiftState.ON else KeyboardView.ShiftState.OFF)
    }

    private fun onSymbols(): Boolean = mode == KeyboardLayout.Mode.SYMBOLS || mode == KeyboardLayout.Mode.MORE_SYMBOLS

    /** The keys this field starts on, which a symbols page returns to after a space or an apostrophe ([TypingRules.layerAfterSymbol]). */
    private fun fieldLayer(): KeyboardLayout.Mode =
        TypingRules.layerAfterSymbol(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT)

    private fun setMode(newMode: KeyboardLayout.Mode) {
        mode = newMode
        applyLayout()
        if (newMode == KeyboardLayout.Mode.LETTERS) updateShiftFromEditor()
    }

    private fun applyLayout(preserveTouches: Boolean = false) {
        val view = keyboardView ?: return
        // Search types a query, so field-specific keys and the return-to-pad key don't belong there.
        val inputType = if (emojiQuery != null || localInputKind != null) InputType.TYPE_CLASS_TEXT else currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        layoutVoiceKey = voiceOnKeys()
        val next = KeyboardLayout.build(mode, showSwitchKey = shouldOfferSwitchKey(), numberRow = numberRow, inputType = inputType,
            language = language, spaceLabel = spaceLabel(), spaceBar = spaceBar, pound = poundFirst(), voiceKey = layoutVoiceKey,
            commonSymbolHolds = commonSymbolHolds)
        // Assigning a layout cancels held pointers. A proven same-field restart must retain
        // the second thumb already down; any actual geometry/field change still cancels it.
        if (!preserveTouches || view.layout != next) view.layout = next
        view.shiftState = if (mode == KeyboardLayout.Mode.LETTERS) shiftState else KeyboardView.ShiftState.OFF
    }

    /** The voice key belongs on the keys: chosen in Settings, offered in this field, and not while emoji search has them. */
    private fun voiceOnKeys(): Boolean = voiceKey == VoiceKey.KEYS && voiceOffered && emojiQuery == null && localInputKind == null

    /** Android 13+ puts its own keyboard switcher in the navigation bar, so skip the duplicate there. */
    private fun shouldOfferSwitchKey(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> shouldOfferSwitchingToNextInputMethod()
        else -> false
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) switchToNextInputMethod(false)
    }

    /**
     * A plain append went out. The restart ledger records it, and so does the typed run. [before] is the text
     * that stood before the caret, or null when that read failed; [earlier] is a read from earlier in the same key.
     */
    private fun recordAppend(before: String?, text: String, earlier: String? = null) {
        appendLedger.record(fieldIdentity, before, text, earlier)
        // One more read, through the connection the text just went through. An answer means that connection was still
        // live, so the append reached its editor, and a restart that later shows the old text is the app's own doing:
        // a field it emptied, or focus moving to the next box of a web page, where a resend would type the letter
        // twice (a crossword grid, reported 2026-11-10). A retired connection answers nothing, and only then can the
        // append have been swallowed. Skipped where the read before the key failed: that editor has no text to read.
        if (before != null && currentInputConnection?.getTextBeforeCursor(1, 0) != null) appendLedger.landed()
        val spacingAllowed = smallAccommodationsAllowed() && !capsLockUsedForWord && !replayingKeys
        if (text == "(" && spacingAllowed && before != null) {
            val s = editorSnapshot()
            if (s != null && s.before == before + text)
                ownedSpacing.opened(correctionIdentity(), s, SystemClock.uptimeMillis())
            else ownedSpacing.reset()
        } else ownedSpacing.appended(correctionIdentity(), before, text, SystemClock.uptimeMillis(), spacingAllowed)
        // Where no host may be remembered the run is not kept at all, so a password is never held for it.
        if (hostsAllowed) typedRun.appended(before ?: earlier, text) else typedRun.reset()
    }

    /** Hosts may be remembered from this field and offered in it: never a password, incognito or terminal field. */
    private val hostsAllowed: Boolean
        get() = rememberDomains && learningAllowed && !inputIsSensitive && stripOff != TypingRules.StripOff.PRIVATE &&
            stripOff != TypingRules.StripOff.TERMINAL

    /**
     * Every edit this keyboard makes goes through here first. [keepsTypedRun] is Backspace's alone: any other
     * edit that is not a plain append (a paste, a strip tap, a correction, a slide) ends the run being typed.
     */
    private fun markEdit(plainAppend: Boolean = false, appendedCharacters: Int = 0, deletedBefore: Int? = null, deletedAfter: Int = 0,
                         keepsTypedRun: Boolean = false) {
        cancelSwipeWork()
        // A gesture's delete is put back only into the text it left.
        gestureUndo = null
        if (!plainAppend) ownedSpacing.reset()
        if (!plainAppend && !keepsTypedRun) typedRun.reset()
        if (!handlingSpace) spaceProofread.reset()
        if (proofreading) { proofreadJob?.cancel(); dismissProposal() }
        verifiedWordReceipt = null
        // Only an unbroken run of plain appends can be checked against a restarted editor's text.
        if (!plainAppend) appendLedger.clear()
        if (!plainAppend && deletedBefore == null) personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        if (plainAppend || deletedBefore != null) measureAddedCorrection(CorrectionDiagnostics.Stage.POSITION) { editorEdits.edit(deletedBefore ?: 0, deletedAfter, appendedCharacters) }
        else { editorEdits.uncertain(); expectedContext = null; cancelRecentCorrection() }
        if (plainAppend) {
            neuralState.invalidate()
            neuralWorker?.cancel()
        } else resetNeuralSession()
        // Every editor edit here collapses a selection. A later selection callback can set it again.
        selectionActive = false
    }

    // endregion

    // region Panels

    private fun toggleEmojiPanel() {
        if (panel == KeyboardPanel.EMOJI) closePanel() else openPanel(KeyboardPanel.EMOJI)
    }

    /**
     * Lays [kind]'s panel over the keys at their exact height and puts its header in the strip, so
     * the window never changes size. A proofread belongs to the sentence the strip was showing, so
     * it ends, as does a pending model request; suggestions come back when the panel closes.
     */
    private fun openPanel(kind: KeyboardPanel) {
        hideNotice()
        // The words dictation still held go in now. If the field refuses them, the panel says so once it is open.
        stopDictationNow()
        val refused = notice
        hideNotice()
        cancelDeleteSlide()
        personalWordOwned = false
        personalLearning.reset(PersonalContextTrace.ResetReason.PASTE)
        val area = keyArea ?: return
        val header = panelHeader ?: return
        if (!viewShown || panel == kind) return
        closeTools()
        spaceProofread.reset()
        cancelRecentCorrection()
        proofreadJob?.cancel()
        dismissProposal()
        emojiPopup.dismiss()
        cancelFoundationRequest()
        // Restoring a pad after search requests a layout; its measured height is still the search's until the next frame.
        neuralState.invalidate()
        neuralWorker?.cancel()
        val returningSearchHeight = if (emojiQuery != null) emojiSearchHeight else 0
        panel?.let(::leavePanel)
        // Keep this height while any panel is open, even if the hidden keys change layer underneath. Emoji
        // search unpins it to show the keys, so leaving search for Saved text pins it again here.
        if (area.pinnedHeight == 0) area.pinnedHeight = returningSearchHeight.takeIf { it > 0 } ?: keyboardView?.height ?: 0
        val (panelHeaderView, root) = panelViews(kind, area)
        panel = kind
        setKeysVisible(false)
        header.removeAllViews()
        header.addView(panelHeaderView, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        when (kind) {
            KeyboardPanel.EMOJI -> {
                if (emojiInventory.size == 0) loadEmojiInventory()
                emojiPanel?.show(emojiInventory, recentEmoji.items, emojiLoading)
            }
            KeyboardPanel.SAVED -> {
                savedPanel?.prepareToOpen()
                renderSavedPanel()
            }
            KeyboardPanel.TOOLS -> {
                renderToolsPanel()
                toolsPanel?.scrollToTop()
            }
            KeyboardPanel.ARROWS -> arrowPad?.reset()
            KeyboardPanel.CONTACTS -> {
                renderContactsPanel()
                readContactsIfStale(CONTACTS_PANEL_STALE_MS)
            }
            KeyboardPanel.PASSWORDS -> renderPasswordsPanel()
            KeyboardPanel.SYMBOLS -> symbolsPanel?.show(symbolQuery)
            KeyboardPanel.TEXT_EFFECTS -> effectsPanel?.show(effectsSource)
        }
        // Built GONE, so this is the visibility change that has TalkBack announce the pane's title.
        root.visibility = View.VISIBLE
        updateStripForPanel()
        refused?.let(::showNotice)
    }

    /** Puts the keys back: ABC, the emoji button, a new field, or the keyboard hiding. */
    private fun closePanel() {
        val open = panel ?: return
        hideNotice()
        panel = null
        leavePanel(open)
        keyArea?.pinnedHeight = 0
        setKeysVisible(true)
        panelHeader?.removeAllViews()
        updateStripForPanel()
        scheduleRefresh()
    }

    /**
     * ABC: back to the letters, whichever layer the keys were on. ABC went with the panel, so
     * TalkBack's focus goes to the emoji button, which stays, rather than nowhere.
     */
    private fun lettersFromPanel() {
        closePanel()
        setMode(KeyboardLayout.Mode.LETTERS)
        TalkBackFocus.moveTo(toolsHandle)
    }

    /**
     * The bar's key for the other panel. The key that was tapped goes with its panel, so TalkBack's
     * focus moves to the new bar's key for the way back, in the same spot.
     */
    private fun switchPanel(kind: KeyboardPanel) {
        if (kind == KeyboardPanel.SAVED) openSavedPanel() else openPanel(kind)
        TalkBackFocus.moveTo(panelToggles[kind])
    }

    /**
     * Hides [kind]'s panel, closing or switching to the other one. Leaving the emoji panel ends a
     * search, so the emoji button, Saved text, a new field and hiding all give the keys back to the
     * field. Saved text's "Saved" or Undo row ends with the visit.
     */
    private fun leavePanel(kind: KeyboardPanel) {
        emojiPopup.dismiss()
        if (kind == localInputKind) stopCharacterToolInput()
        if (kind == KeyboardPanel.EMOJI) stopEmojiSearch()
        panelRootOf(kind)?.visibility = View.GONE
        if (kind == KeyboardPanel.SAVED) savedTexts?.forgetLastChange()
        // The manager's chips go back to the strip, which shows them again once nothing covers it.
        if (kind == KeyboardPanel.PASSWORDS) inlineRow?.let { inlineAutofill?.attach(it) }
    }

    /**
     * Opens Saved text: holding the emoji button, its TalkBack action or the lead button's, or the
     * emoji panel's bar. [justSaved] keeps the "Saved" row that holding the paste chip just earned.
     */
    private fun openSavedPanel(justSaved: Boolean = false) {
        if (!justSaved) savedTexts?.forgetLastChange()
        if (panel == KeyboardPanel.SAVED) renderSavedPanel() else openPanel(KeyboardPanel.SAVED)
    }

    /** The handle: from a panel it gives the keys back and opens the row; otherwise it opens or closes the row. */
    private fun onToolsHandle() {
        if (panel != null) {
            closePanel()
            return openTools()
        }
        if (toolsOpen) closeTools() else openTools()
    }

    /** What this field offers among the tools that are not always there. */
    private fun toolsField() = StripTools.Field(
        voice = voiceOffered,
        proofread = suggestionsEnabled && fieldWantsSuggestions && foundationAllowed &&
            TypingRules.proofreadsOnHold(currentInputEditorInfo?.inputType ?: 0),
        // Android 11 and later only: the keyboard has an InlineAutofill exactly when chips can reach it.
        passwords = inlineAutofill != null,
    )

    /**
     * Opens the tools row over the word slots, built for this field and the strip's width: the tools that fit, then
     * More. A notice, a gesture's or link's message and a finished proposal give way; dictation stops as for a key.
     */
    private fun openTools() {
        val row = toolRow ?: return
        if (!viewShown || emojiQuery != null || panel != null) return
        emojiPopup.dismiss()
        hideNotice()
        hideGestureNotice()
        hideLinkNotice()
        hideCorrectionFeedback()
        if (dictationSession.listening) stopDictationNow()
        if (!proofreading) dismissProposal()
        row.removeAllViews()
        rowToolIcons.clear()
        val metrics = resources.displayMetrics
        val stripWidth = candidateStripView?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val state = gestureState()
        StripTools.row((stripWidth / metrics.density).toInt(), toolsField(), toolsOrder()).forEach { tool ->
            row.addView(toolIcon(tool, StripTools.allowed(tool, state)), LinearLayout.LayoutParams(dp(StripTools.CELL_DP), dp(StripTools.CELL_DP)))
        }
        row.addView(stripIcon(R.drawable.ic_tools_more, R.string.tools_all) { openToolsPanel() },
            LinearLayout.LayoutParams(dp(StripTools.CELL_DP), dp(StripTools.CELL_DP)))
        toolsOpen = true
        updateToolsHandle()
        updateSlotVisibility()
        TalkBackFocus.moveTo(row.getChildAt(0))
    }

    /** Closes the tools row and gives the words back. Does nothing while it's closed. */
    private fun closeTools() {
        if (!toolsOpen) return
        toolsOpen = false
        toolRow?.removeAllViews()
        rowToolIcons.clear()
        updateToolsHandle()
        updateSlotVisibility()
        scheduleRefresh()
    }

    /** The handle points the way the row opens, and TalkBack hears whether it's open. */
    private fun updateToolsHandle() {
        val handle = toolsHandle ?: return
        handle.rotation = if (toolsOpen) 180f else 0f
        ViewCompat.setStateDescription(handle, getString(if (toolsOpen) R.string.tools_open else R.string.tools_closed))
    }

    /**
     * One tool in the row: dimmed where the field forbids it, and it still answers a tap to say so. Passwords & cards
     * carries a dot while the manager's chips are waiting.
     */
    private fun toolIcon(tool: StripTool, allowed: Boolean) = stripIcon(tool.icon, tool.label) { runTool(tool) }.apply {
        if (!allowed) alpha = 0.45f
        setImageDrawable(ToolIcon.drawable(this@LocalTypeInputMethodService, tool.icon, toolDot(tool)))
        contentDescription = ToolIcon.description(this@LocalTypeInputMethodService, tool, allowed, toolDot(tool))
        rowToolIcons[tool] = this
    }

    /** Whether [tool]'s icon has the dot: only Passwords & cards, while chips are waiting for this field. */
    private fun toolDot(tool: StripTool): Boolean = tool == StripTool.PASSWORDS && PasswordsTile.dot(inlineAutofill?.drawn ?: 0)

    /** The chips changed: the dot on Passwords & cards follows, in the open row and the Tools panel, without a redraw. */
    private fun updatePasswordsDot() {
        val dot = toolDot(StripTool.PASSWORDS)
        rowToolIcons[StripTool.PASSWORDS]?.let { icon ->
            icon.setImageDrawable(ToolIcon.drawable(this, StripTool.PASSWORDS.icon, dot))
            icon.contentDescription = ToolIcon.description(this, StripTool.PASSWORDS, StripTools.allowed(StripTool.PASSWORDS, gestureState()), dot)
        }
        if (panel == KeyboardPanel.TOOLS) toolsPanel?.setDot(StripTool.PASSWORDS, dot)
    }

    /** More, or holding the handle: every tool over the keys. */
    private fun openToolsPanel() {
        closeTools()
        if (panel == KeyboardPanel.TOOLS) renderToolsPanel() else openPanel(KeyboardPanel.TOOLS)
    }

    private fun renderToolsPanel() {
        if (panel != KeyboardPanel.TOOLS) return
        val state = gestureState()
        val order = toolsOrder()
        toolsPanel?.show(StripTools.panel(toolsField()), { StripTools.allowed(it, state) }, { it in order }, ::toolDot)
    }

    /** The tools row's order as the person left it, or the built-in one. */
    private fun toolsOrder(): List<StripTool> = StripTools.rowOrder(KeyboardSettings.toolsRow(this))

    /** Holding a tile in the Tools panel: puts that tool in the row, at the end, or takes it out. */
    private fun pinTool(tool: StripTool) {
        val next = StripTools.toggle(toolsOrder(), tool) ?: return showNotice(NoticeRules.Message.TOOLS_ROW_FULL)
        KeyboardSettings.setToolsRow(this, next.map { it.id })
        renderToolsPanel()
        // The redraw replaced the tile TalkBack was on; focus goes to the same tool's new tile.
        TalkBackFocus.moveTo(toolsPanel?.tileFor(tool))
        showNotice(if (tool in next) NoticeRules.Message.TOOL_PINNED else NoticeRules.Message.TOOL_UNPINNED)
    }

    /**
     * Runs [tool] from the row or the panel. Both close first, so the tool acts on the keys and the field as they are,
     * and a tool the field forbids says so in the strip instead.
     */
    private fun runTool(tool: StripTool) {
        closeTools()
        if (panel != null) closePanel()
        // The row or tile that was tapped has gone; TalkBack's focus goes to the handle, which stays.
        TalkBackFocus.moveTo(toolsHandle)
        if (!StripTools.allowed(tool, gestureState())) return showGestureNotice(getString(R.string.gesture_not_here, getString(tool.label)))
        when (tool) {
            StripTool.SYMBOLS -> openPanel(KeyboardPanel.SYMBOLS)
            StripTool.TEXT_EFFECTS -> openTextEffects()
            StripTool.VOICE -> onVoiceTapped()
            StripTool.PROOFREAD -> proofreadSentence()
            StripTool.REWRITE_SHORTER -> proofreadSentence(RewriteStyle.SHORTER)
            StripTool.REWRITE_FORMAL -> proofreadSentence(RewriteStyle.FORMAL)
            StripTool.REWRITE_FRIENDLY -> proofreadSentence(RewriteStyle.FRIENDLY)
            StripTool.SETTINGS -> startActivity(android.content.Intent(this, SettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            // The keyboard's own last edit first, as the gesture does; with none, the app's own undo.
            StripTool.UNDO -> gestureUndoLast(editorFallback = true)
            StripTool.REDO -> editorHistory(StripTools.EDITOR_REDO)
            StripTool.ARROWS -> openPanel(KeyboardPanel.ARROWS)
            StripTool.READ_ALOUD -> readAloud()
            StripTool.PRIVATE -> privateForNow()
            StripTool.CHANGE_CASE -> changeCase()
            StripTool.COUNT -> countWords()
            StripTool.CLEAN_LINKS -> cleanLinksTool()
            StripTool.TODAY -> insertDated(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date()))
            StripTool.TIME_NOW -> insertDated(android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date()))
            StripTool.SEARCH_WEB -> searchWeb()
            StripTool.SEND_TO_APP -> sendToApp()
            StripTool.CONTACTS -> openContacts()
            StripTool.PASSWORDS -> openPanel(KeyboardPanel.PASSWORDS)
            StripTool.ONE_HANDED -> setOneHanded(OneHandedRules.toggled(oneHanded))
            else -> tool.gesture?.let(::runGestureAction)
        }
    }

    /** The keys and, with one-handed on, the rail beside them: hidden under a panel, shown with the keys. */
    private fun setKeysVisible(visible: Boolean) {
        keyboardView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        oneHandedRail?.let { if (it.visibility != View.GONE) it.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
    }

    /** The rail beside one-handed keys: move them to the other side, or give them the full width again. */
    private fun oneHandedRail(context: android.content.Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        visibility = View.GONE
        val swap = stripIcon(R.drawable.ic_swap_side, R.string.one_handed_swap_left) {
            KeyHaptics.perform(this, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            setOneHanded(OneHandedRules.swapped(oneHanded))
        }.also { oneHandedSwap = it }
        addView(swap, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(View(context), LinearLayout.LayoutParams(dp(48), dp(12)))
        addView(stripIcon(R.drawable.ic_full_width, R.string.one_handed_full) {
            KeyHaptics.perform(this, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            setOneHanded(OneHanded.OFF)
            // The rail went with the full width; TalkBack's focus goes to the handle, which stays.
            TalkBackFocus.moveTo(toolsHandle)
        }.also { oneHandedFull = it }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    /** Saves [next] and lays the keys out for it at once, saying which way they went. */
    private fun setOneHanded(next: OneHanded) {
        val was = oneHanded
        oneHanded = next
        KeyboardSettings.setOneHanded(this, next)
        applyKeyWidth()
        if (was == next) return
        showGestureNotice(getString(when (next) {
            OneHanded.OFF -> if (keyPlacement?.side == KeyWidthRules.Side.CENTER) R.string.one_handed_centered else R.string.one_handed_off_notice
            OneHanded.LEFT -> R.string.one_handed_left_notice
            OneHanded.RIGHT -> R.string.one_handed_right_notice
        }))
    }

    /**
     * Lays out the keys' width ([KeyWidthRules]): one-handed narrows them toward [oneHanded]'s side with the rail in the
     * space they leave, a wide window centers them at [KeyWidthRules.MAX_KEYS_DP] unless [wideKeys] is full width, and
     * anything else keeps the full width. The strip and panels keep the full width. Only the view narrows; its keys
     * stay in key widths, so correction's geometry and the touch offsets are the same on every width.
     */
    private fun applyKeyWidth() {
        val view = keyboardView ?: return
        val rail = oneHandedRail ?: return
        val config = resources.configuration
        val placement = KeyWidthRules.place(oneHanded, wideKeys, config.screenWidthDp, config.screenHeightDp)
        keyPlacement = placement
        val keys = view.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        keys.width = if (placement.side == KeyWidthRules.Side.FULL) android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            else dp(placement.widthDp)
        keys.gravity = android.view.Gravity.TOP or when (placement.side) {
            KeyWidthRules.Side.LEFT -> android.view.Gravity.START
            KeyWidthRules.Side.CENTER -> android.view.Gravity.CENTER_HORIZONTAL
            else -> android.view.Gravity.END
        }
        view.layoutParams = keys
        val railParams = rail.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        railParams.gravity = if (oneHanded == OneHanded.LEFT) android.view.Gravity.END else android.view.Gravity.START
        rail.layoutParams = railParams
        rail.visibility = if (!placement.rail) View.GONE else if (view.visibility == View.VISIBLE) View.VISIBLE else View.INVISIBLE
        oneHandedSwap?.contentDescription = getString(
            if (oneHanded == OneHanded.LEFT) R.string.one_handed_swap_right else R.string.one_handed_swap_left)
        // On a wide window, leaving one-handed centers the keys rather than spreading them, and the button says so.
        val off = KeyWidthRules.place(OneHanded.OFF, wideKeys, config.screenWidthDp, config.screenHeightDp)
        oneHandedFull?.contentDescription = getString(
            if (off.side == KeyWidthRules.Side.CENTER) R.string.one_handed_centered else R.string.one_handed_full)
        keyArea?.requestLayout()
    }

    /**
     * Contacts: the people matching the word before the caret. Without the permission the panel says what it is for
     * and offers to ask; the keyboard never asks on its own.
     */
    private fun openContacts() {
        contactsQuery = contactsQueryNow()
        if (panel == KeyboardPanel.CONTACTS) renderContactsPanel() else openPanel(KeyboardPanel.CONTACTS)
    }

    /**
     * The word being typed, when it ends at the caret: a tapped contact replaces it. Over a selection, in the middle
     * of a word or after a space there is none, and the contact goes in at the caret.
     */
    private fun contactsQueryNow(): String {
        val ic = currentInputConnection ?: return ""
        if (hasEditorSelection()) return ""
        val after = ic.getTextAfterCursor(2, 0) ?: return ""
        if (!Expansions.wordEndsAt(after)) return ""
        val before = ic.getTextBeforeCursor(ContactBook.MAX_QUERY + 1, 0)?.toString() ?: return ""
        val prefix = localModel.activePrefix(before)
        return if (prefix.length in 1..ContactBook.MAX_QUERY) prefix else ""
    }

    private fun contactsField(): ContactBook.Field {
        val inputType = currentInputEditorInfo?.inputType ?: 0
        return when {
            inputType and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_PHONE -> ContactBook.Field.PHONE
            TypingRules.isEmail(inputType) -> ContactBook.Field.EMAIL
            else -> ContactBook.Field.TEXT
        }
    }

    private fun renderContactsPanel() {
        if (panel != KeyboardPanel.CONTACTS) return
        val list = contacts
        contactsPanel?.show(when {
            !ContactsReader.granted(this) -> ContactsPanel.State.NotAllowed
            list == null -> ContactsPanel.State.Loading
            else -> ContactsPanel.State.Found(contactsQuery, ContactBook.matches(list, contactsQuery), contactsField())
        })
    }

    /** The see-through prompt, from the panel's Allow button. The person taps Contacts again once it's answered. */
    private fun askForContacts() {
        closePanel()
        startActivity(android.content.Intent(this, ContactsPermissionActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Reads the contacts again on a thread of its own when the last read is older than [staleMs], and only with the
     * permission; without it, what was read is dropped. The list reaches the main thread in one post.
     */
    private fun readContactsIfStale(staleMs: Long) {
        if (!ContactsReader.granted(this)) {
            contacts = null
            contactNames = emptySet()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (contactsReading || (contacts != null && now - contactsReadAt < staleMs)) return
        contactsReading = true
        val app = applicationContext
        // onDestroy cancels it, so a slow provider's query doesn't outlive the keyboard.
        val cancel = android.os.CancellationSignal().also { contactsCancel = it }
        Thread({
            val read = ContactsReader.read(app, cancel)
            val names = ContactBook.nameWords(read)
            handler.post {
                contactsReading = false
                if (contactsCancel === cancel) contactsCancel = null
                if (destroyed) return@post
                contacts = read
                contactNames = names
                contactsReadAt = SystemClock.elapsedRealtime()
                renderContactsPanel()
            }
        }, "localtype-contacts").apply { isDaemon = true }.start()
    }

    /**
     * A tapped name, number or address. It replaces the word the list was filtered by, through one verified
     * replacement, or goes in at the caret when there was none. Like a paste it teaches nothing and writes no log row,
     * and delete right after takes it back.
     */
    private fun insertContact(choice: ContactBook.Choice) {
        val query = contactsQuery
        // The panel's own space and delete can change the word under it; a list for another word is redrawn instead.
        if (contactsQueryNow() != query) {
            contactsQuery = contactsQueryNow()
            return renderContactsPanel()
        }
        closePanel()
        // The row that was tapped went with the panel; TalkBack's focus goes to the handle, which stays.
        TalkBackFocus.moveTo(toolsHandle)
        val ic = currentInputConnection ?: return
        drainPendingKeys()
        val text = ContactBook.inserted(choice, ic.getTextAfterCursor(1, 0)?.toString())
        if (query.isEmpty()) {
            // Delete right after takes it back, putting back any selection it replaced. The caret report that follows
            // the insertion clears [lastAutocorrect], so the way back waits in [cleanedPaste], as a paste's does, and
            // only for an insertion the field confirmed, at the caret it reports.
            val replaced = ic.getSelectedText(0)?.toString().orEmpty()
            if (!insertFromPanel(text)) return
            val caret = readEditorSnapshot()?.selection?.takeIf { it.collapsed }?.end ?: return
            cleanedPaste = CleanedPaste(AutoCorrection(original = replaced, committed = text, keep = false), caret)
            return
        }
        val limit = MAX_CONTEXT_CHARS + text.length
        val initial = readEditorSnapshot(limit) ?: return
        if (!initial.before.endsWith(query)) return scheduleRefresh()
        KeyHaptics.perform(keyArea, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        personalWordOwned = false
        personalLearning.reset(PersonalContextTrace.ResetReason.PASTE)
        syncLearnedState()
        hideNotice()
        lastAutocorrect = null
        val applied = applyWordReplacement(WordEdits.Replacement(query.length, 0, text), precondition = initial, readLimit = limit)
        typedRun.reset()
        revisitTrace(CorrectionDiagnostics.Revisit.INSERT)
        autoSpaced = false
        if (applied != null) lastAutocorrect = AutoCorrection(original = query, committed = text, keep = false)
        updateShiftFromEditor()
        scheduleRefresh()
    }

    /**
     * Asks the editor for its own undo or redo through its text menu, which standard text fields and Chrome answer.
     * The keyboard can't see what changed, so it treats the result like a caret moved by hand: the word there is
     * revisited and nothing it held for the old text survives.
     */
    private fun editorHistory(id: Int) {
        val ic = currentInputConnection ?: return
        drainPendingKeys()
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        markEdit()
        caretLeftOwnPosition()
        val sent = ic.performContextMenuAction(id)
        showGestureNotice(getString(if (!sent) R.string.tool_history_refused
            else if (id == StripTools.EDITOR_REDO) R.string.tool_redo_sent else R.string.tool_undo_sent))
        scheduleRefresh()
    }

    /** One move from the arrow pad, sent as the key a hardware keyboard would send, then the caret is revisited. */
    private fun arrowMove(move: ArrowMove, selecting: Boolean) {
        val ic = currentInputConnection ?: return
        drainPendingKeys()
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.SELECTION)
        caretLeftOwnPosition()
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        for ((action, code, meta) in ArrowPad.keys(move, selecting)) {
            ic.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        }
        KeyHaptics.perform(arrowsPanelRoot, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * Speaks the selection, or else the sentence before the caret, with the phone's text-to-speech engine. Nothing is
     * kept; the engine is Android's, chosen in the phone's settings, but only an offline voice speaks ([speak]).
     * Never in a password field (the tool is off there).
     */
    private fun readAloud() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val text = selected ?: ToolText.lastSentence(ic.getTextBeforeCursor(ToolText.READ_CHARS, 0)?.toString().orEmpty())
        if (text.isBlank()) return showGestureNotice(getString(R.string.tool_read_nothing))
        if (!ReadAloud.fits(text, android.speech.tts.TextToSpeech.getMaxSpeechInputLength()))
            return showGestureNotice(getString(R.string.tool_read_too_long))
        val engine = speech
        if (engine != null && speechReady) return speak(engine, text)
        speechPending = text
        if (engine != null) return
        speech = android.speech.tts.TextToSpeech(this) { status ->
            handler.post {
                if (destroyed) return@post
                speechReady = status == android.speech.tts.TextToSpeech.SUCCESS
                val pending = speechPending
                speechPending = null
                val ready = speech
                if (!speechReady || ready == null) {
                    speech?.shutdown()
                    speech = null
                    showGestureNotice(getString(R.string.tool_read_unavailable))
                } else if (pending != null) speak(ready, pending)
            }
        }
    }

    /**
     * Change case on the selection: UPPERCASE, lowercase, Title Case in turn ([ToolText.nextCase]). The new text keeps
     * the selection, so another tap goes on to the next case. It edits text the keyboard didn't type, so the word
     * there is revisited, as after a paste.
     */
    private fun changeCase() {
        val ic = currentInputConnection ?: return
        drainPendingKeys()
        val snapshot = editorSnapshot()
        val selected = ic.getSelectedText(0)?.toString()
        // A selection made leftwards is reported end first; the range is taken start first either way.
        val range = snapshot?.selection?.let { ToolText.caseRange(it.start, it.end, selected?.length ?: 0) }
        if (selected.isNullOrEmpty() || range == null) return showGestureNotice(getString(R.string.tool_case_select))
        val changed = ToolText.inCase(selected, ToolText.nextCase(selected))
        if (changed == selected) return
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        markEdit()
        caretLeftOwnPosition()
        lastAutocorrect = null
        ic.beginBatchEdit()
        val done = ic.commitText(changed, 1) && ic.setSelection(range.first, range.first + changed.length)
        ic.endBatchEdit()
        if (!done) showGestureNotice(getString(R.string.tool_history_refused))
        scheduleRefresh()
    }

    /**
     * Clean links: the selection's links, or with nothing selected the link the caret stands right after, without
     * their tracking. It is asked for, so it runs with the pasted-links setting off too. Delete right after, or Undo
     * in the strip, puts the original back.
     */
    private fun cleanLinksTool() {
        val ic = currentInputConnection ?: return
        drainPendingKeys()
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            if (selected.length > LinkCleaner.MAX_CHARS || LinkCleaner.clean(selected) === selected)
                return showGestureNotice(getString(R.string.tool_clean_links_none))
            insertPasted(selected, asked = true)
            return
        }
        val context = currentContext()
        val offer = linkOffer(context) ?: return showGestureNotice(getString(
            if ("://" in Expansions.run(context)) R.string.tool_clean_links_none else R.string.tool_clean_links_where))
        takeExpansion(offer)
    }

    /** Today's date or the time, with a space after it so the strip doesn't complete "AM" as a word. */
    private fun insertDated(text: String) {
        insertFromPanel(ToolText.inserted(text, currentInputConnection?.getTextAfterCursor(1, 0)?.toString()))
    }

    /**
     * Count: words and characters in the selection, or in the whole field when nothing is selected. A count made from
     * only the text around the caret says it may be more.
     */
    private fun countWords() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() }
        var complete = true
        val text = selected ?: run {
            val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = ToolText.COUNT_CHARS }, 0)
            val whole = extracted?.text?.toString()
            if (extracted == null || whole == null || extracted.partialStartOffset >= 0 || extracted.startOffset != 0 || whole.length >= ToolText.COUNT_CHARS) {
                // Some editors give no whole text; what is around the caret is all there is to count then, and the
                // editor may have given only part of the field.
                val before = ic.getTextBeforeCursor(ToolText.COUNT_CHARS, 0)?.toString()
                val after = ic.getTextAfterCursor(ToolText.COUNT_CHARS, 0)?.toString()
                if (before == null || after == null || before.length + after.length >= ToolText.COUNT_CHARS)
                    return showGestureNotice(getString(R.string.tool_count_too_long))
                complete = false
                before + after
            } else whole
        }
        val (words, chars) = ToolText.countWithin(text) ?: return showGestureNotice(getString(R.string.tool_count_too_long))
        showGestureNotice(getString(if (complete) R.string.tool_count_result else R.string.tool_count_partial, words, chars))
    }

    /** The selection, or with none the word at the caret: what Search web looks up. */
    private fun searchQuery(): String {
        val ic = currentInputConnection ?: return ""
        ic.getSelectedText(0)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(ToolText.SEARCH_CHARS) }
        return ToolText.wordAt(ic.getTextBeforeCursor(64, 0)?.toString().orEmpty(), ic.getTextAfterCursor(64, 0)?.toString().orEmpty())
    }

    /**
     * Search web hands the query to whichever app answers a web search, usually the browser. The keyboard itself
     * connects to nothing; the text leaves only on this tap, and never from a field the keyboard may not learn from.
     */
    private fun searchWeb() {
        val query = searchQuery()
        if (query.isEmpty()) return showGestureNotice(getString(R.string.tool_search_nothing))
        val intent = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            showGestureNotice(getString(R.string.tool_search_no_app))
        }
    }

    /**
     * Send to app: the selection, or the sentence before the caret, to an app's text action (Translate, Define and so
     * on: `ACTION_PROCESS_TEXT`), read-only, chosen from Android's own chooser. Same field rules as Search web.
     */
    private fun sendToApp() {
        val ic = currentInputConnection ?: return
        val text = (ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
            ?: ToolText.lastSentence(ic.getTextBeforeCursor(ToolText.READ_CHARS, 0)?.toString().orEmpty())).take(ToolText.SEND_CHARS)
        if (text.isBlank()) return showGestureNotice(getString(R.string.tool_send_nothing))
        val action = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        if (packageManager.queryIntentActivities(action, 0).isEmpty()) return showGestureNotice(getString(R.string.tool_send_no_app))
        val chooser = android.content.Intent.createChooser(action, getString(R.string.tool_send_title))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(chooser)
        } catch (_: android.content.ActivityNotFoundException) {
            showGestureNotice(getString(R.string.tool_send_no_app))
        }
    }

    /**
     * Speaks [text] only through a voice installed on the phone that needs no connection ([offlineVoice]): typing never
     * touches the network, and the text may come from an incognito or private-for-now field. With no such voice
     * nothing is spoken and the strip says so; a request the engine refuses says so too.
     */
    private fun speak(engine: android.speech.tts.TextToSpeech, text: String) {
        val voice = offlineVoice(engine)
        if (voice == null || engine.setVoice(voice) != android.speech.tts.TextToSpeech.SUCCESS)
            return showGestureNotice(getString(R.string.tool_read_no_offline_voice))
        if (engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "localtype-read-aloud") != android.speech.tts.TextToSpeech.SUCCESS)
            return showGestureNotice(getString(R.string.tool_read_failed))
        showGestureNotice(getString(R.string.tool_reading))
    }

    /**
     * The engine's voice for the keyboard's language, else the engine's default language or the phone's, that is
     * installed and needs no network ([ReadAloud.choose]); null when there is none or the engine won't list its voices.
     */
    private fun offlineVoice(engine: android.speech.tts.TextToSpeech): android.speech.tts.Voice? {
        val voices = try { engine.voices?.toList() } catch (_: RuntimeException) { null }.orEmpty()
        val default = try { engine.defaultVoice } catch (_: RuntimeException) { null }
        val options = voices.mapNotNull { voice ->
            val locale = voice.locale ?: return@mapNotNull null
            ReadAloud.VoiceOption(voice.name, locale, offline = !voice.isNetworkConnectionRequired,
                installed = voice.features?.contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true,
                quality = voice.quality)
        }
        val wanted = listOfNotNull(java.util.Locale.forLanguageTag(speechLanguage()), default?.locale, java.util.Locale.getDefault())
        val name = ReadAloud.choose(options, wanted, default?.name)?.name ?: return null
        return voices.firstOrNull { it.name == name }
    }

    /**
     * Private for now: from here to the end of this field the keyboard learns nothing (words, pairs, capitals, tap
     * offsets, sites, copies) and the typing log stops for it, exactly as in a field the app marks incognito.
     */
    private fun privateForNow() {
        if (privateNow) return showGestureNotice(getString(R.string.tool_private_already))
        privateNow = true
        learningAllowed = false
        personalLearning.reset(PersonalContextTrace.ResetReason.FIELD)
        personalWordOwned = false
        typedRun.reset()
        loggedFieldGate.finish()
        if (logAllowed) {
            PredictionLog.endField()
            logAllowed = false
        }
        showGestureNotice(getString(R.string.tool_private_on))
        scheduleRefresh()
    }

    /** Redraws Saved text, if it's open, from the saved list and the copies of the last hour that aren't saved. */
    private fun renderSavedPanel() {
        if (panel != KeyboardPanel.SAVED) return
        val texts = savedTexts
        val copies = recentCopies.items(SystemClock.elapsedRealtime())
        savedPanel?.show(texts?.items.orEmpty(), texts?.unsaved(copies) ?: copies, texts?.lastChange)
    }

    /** The strip's header and the key area's root for [kind], built on first use for the current views. */
    private fun panelViews(kind: KeyboardPanel, area: KeyArea): Pair<View, View> = when (kind) {
        KeyboardPanel.SYMBOLS -> {
            val tool = symbolsPanel ?: SymbolToolsPanel(this, { startCharacterToolInput(KeyboardPanel.SYMBOLS) }) { text ->
                if (panel == KeyboardPanel.SYMBOLS && localInputKind == null && insertFromPanel(text)) closePanel()
            }.also { symbolsPanel = it }
            val root = symbolsPanelRoot ?: characterToolRoot(area, kind, R.string.symbol_tool_title, tool.content).also { symbolsPanelRoot = it }
            tool.header to root
        }
        KeyboardPanel.TEXT_EFFECTS -> {
            val tool = effectsPanel ?: TextEffectsPanel(this, { startCharacterToolInput(KeyboardPanel.TEXT_EFFECTS) },
                ::insertTextEffect, ::copyTextEffect).also { effectsPanel = it }
            val root = effectsPanelRoot ?: characterToolRoot(area, kind, R.string.text_effects_title, tool.content).also { effectsPanelRoot = it }
            tool.header to root
        }
        KeyboardPanel.EMOJI -> {
            val emoji = emojiPanel ?: EmojiPanel(
                this, emojiPopup, emojiTones, ::insertPanelEmoji,
                onSearch = ::startEmojiSearch,
                onSearchDone = ::endEmojiSearch,
            ).also { emojiPanel = it }
            val root = emojiPanelRoot ?: PanelViews.root(
                this, getString(R.string.emoji_panel), emoji.content,
                panelBar(KeyboardPanel.EMOJI, PanelViews.Toggle(R.drawable.ic_paste, R.string.saved_text) { switchPanel(KeyboardPanel.SAVED) }),
            ).also {
                area.addView(it, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                emojiPanelRoot = it
            }
            emoji.header to root
        }
        KeyboardPanel.SAVED -> {
            // Inserting is a paste: never learned, never logged, and it never touches the saved list.
            val saved = savedPanel ?: SavedTextPanel(
                this,
                onInsert = { drainPendingKeys(); insertPasted(it) == VerifiedWordTransaction.Insertion.APPLIED },
                // Through the store, so a Save tapped before the file has been read still lands.
                onSave = { text -> SavedTextStore.open(this) { it.save(text) } },
                onRemove = { savedTexts?.remove(it) },
                onUndo = { savedTexts?.undo() },
            ).also { savedPanel = it }
            val root = savedPanelRoot ?: PanelViews.root(
                this, getString(R.string.saved_text), saved.content,
                panelBar(KeyboardPanel.SAVED, PanelViews.Toggle(R.drawable.ic_emoji, R.string.emoji_panel) { switchPanel(KeyboardPanel.EMOJI) }),
            ).also {
                area.addView(it, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                savedPanelRoot = it
            }
            saved.header to root
        }
        KeyboardPanel.TOOLS -> {
            val tools = toolsPanel ?: ToolsPanel(this, ::runTool, ::pinTool).also { toolsPanel = it }
            val root = toolsPanelRoot ?: PanelViews.root(
                this, getString(R.string.tools_title), tools.content,
                panelBar(KeyboardPanel.TOOLS, PanelViews.Toggle(R.drawable.ic_paste, R.string.tool_clipboard) { switchPanel(KeyboardPanel.SAVED) }),
            ).also {
                area.addView(it, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                toolsPanelRoot = it
            }
            tools.header to root
        }
        KeyboardPanel.ARROWS -> {
            val pad = arrowPad ?: ArrowPadPanel(this, ::arrowMove).also { arrowPad = it }
            val root = arrowsPanelRoot ?: PanelViews.root(
                this, getString(R.string.tool_arrows), pad.content,
                panelBar(KeyboardPanel.ARROWS, PanelViews.Toggle(R.drawable.ic_tools_more, R.string.tools_all) { switchPanel(KeyboardPanel.TOOLS) }),
            ).also {
                area.addView(it, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                arrowsPanelRoot = it
            }
            pad.header to root
        }
        KeyboardPanel.PASSWORDS -> {
            val passwords = passwordsPanel ?: PasswordsPanel(this, ::openPasswordManager).also { passwordsPanel = it }
            val root = passwordsPanelRoot ?: PanelViews.root(
                this, getString(R.string.tool_passwords), passwords.content,
                panelBar(KeyboardPanel.PASSWORDS, PanelViews.Toggle(R.drawable.ic_tools_more, R.string.tools_all) { switchPanel(KeyboardPanel.TOOLS) }),
            ).also {
                area.addView(it, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                passwordsPanelRoot = it
            }
            passwords.header to root
        }
        KeyboardPanel.CONTACTS -> {
            val list = contactsPanel ?: ContactsPanel(this, ::insertContact, ::askForContacts).also { contactsPanel = it }
            val root = contactsPanelRoot ?: PanelViews.root(
                this, getString(R.string.tool_contacts), list.content,
                panelBar(KeyboardPanel.CONTACTS, PanelViews.Toggle(R.drawable.ic_tools_more, R.string.tools_all) { switchPanel(KeyboardPanel.TOOLS) }),
            ).also {
                area.addView(it, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                contactsPanelRoot = it
            }
            list.header to root
        }
    }

    private fun panelRootOf(kind: KeyboardPanel): View? = when (kind) {
        KeyboardPanel.EMOJI -> emojiPanelRoot
        KeyboardPanel.SAVED -> savedPanelRoot
        KeyboardPanel.TOOLS -> toolsPanelRoot
        KeyboardPanel.ARROWS -> arrowsPanelRoot
        KeyboardPanel.CONTACTS -> contactsPanelRoot
        KeyboardPanel.PASSWORDS -> passwordsPanelRoot
        KeyboardPanel.SYMBOLS -> symbolsPanelRoot
        KeyboardPanel.TEXT_EFFECTS -> effectsPanelRoot
    }

    /** [kind]'s bar. Space and delete behave exactly as the keys do; there's no Enter in a panel. */
    private fun panelBar(kind: KeyboardPanel, toggle: PanelViews.Toggle): View {
        val bar = PanelViews.bar(
            this, toggle,
            onLetters = ::lettersFromPanel,
            onSpace = { onKey(KeyAction.Space) },
            onDelete = { onKey(KeyAction.Backspace) },
        )
        panelToggles[kind] = bar.toggle
        return bar.view
    }

    /** While a panel is open, its header has the strip in place of the lead button, suggestions, notices and proofread row. */
    private fun updateStripForPanel() {
        val open = panel != null
        panelHeader?.visibility = if (open) View.VISIBLE else View.GONE
        if (open) { hideLinkNotice(); hideGestureNotice() }
        updateSlotVisibility()
        updateLead()
        updateVoiceButton()
        toolsHandle?.isSelected = panel == KeyboardPanel.TOOLS
    }

    /**
     * Types [text] from a panel the way the paste chip pastes: nothing is learned from it as it goes
     * in, it writes no typing-log row, and the log treats the next word's context as unverified
     * (docs/prediction-log-spec.md). The keyboard didn't watch it being typed, so the trace counts
     * it as revisited: a space right after saved text never autocorrects its last word. False when
     * there's no field to type into.
     */
    private fun insertFromPanel(text: String): Boolean {
        drainPendingKeys()
        return insertExternalText(text) == VerifiedWordTransaction.Insertion.APPLIED
    }

    /** Clipboard, saved text and panel emoji share one unlearned, non-retrying insertion path. */
    private fun insertExternalText(text: String, expectedSelection: EffectsSelection? = null): VerifiedWordTransaction.Insertion {
        personalWordOwned = false
        personalLearning.reset(PersonalContextTrace.ResetReason.PASTE)
        syncLearnedState()
        // What an earlier insertion was told no longer describes the field.
        hideNotice()
        val ic = currentInputConnection ?: return VerifiedWordTransaction.Insertion.REJECTED
        KeyHaptics.perform(keyArea, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        // Reads stay bounded even for an unusually large system clipboard. The whole value is
        // still committed once; a shortened receipt simply cannot certify that whole insertion.
        val limit = MAX_CONTEXT_CHARS + minOf(text.length, 5_000)
        val initial = readEditorSnapshot(limit)
        if (expectedSelection != null && (initial?.selection?.start != expectedSelection.start ||
                initial?.selection?.end != expectedSelection.end || initial?.selected != expectedSelection.text)) {
            showNotice(NoticeRules.Message.EFFECT_SOURCE_CHANGED)
            return VerifiedWordTransaction.Insertion.REJECTED
        }
        val generation = editorEdits.generation
        markEdit()
        revisitTrace(CorrectionDiagnostics.Revisit.INSERT)
        lastAutocorrect = null
        autoSpaced = false
        val outcome = VerifiedWordTransaction.insert(text, initial, { ic.commitText(it, 1) }, {
            readEditorSnapshot(limit).takeIf { editorEdits.generation == generation }
        })
        NoticeRules.message(outcome)?.let(::showNotice)
        updateShiftFromEditor()
        scheduleRefresh()
        return outcome
    }

    /**
     * A paste through the keyboard: the paste chip or Saved text. Known tracking parameters come off its links
     * first ([LinkCleaner]), except in a password field or a terminal, where a paste goes in exactly as copied.
     * There are two ways back, both only while the cleaned text still ends at the caret: delete right after, as
     * for an emoji or a correction, and Undo in the strip for a few seconds. The clipboard keeps what was copied.
     */
    private fun insertPasted(text: String, asked: Boolean = false): VerifiedWordTransaction.Insertion {
        val exact = (!cleanLinks && !asked) || inputIsSensitive || stripOff == TypingRules.StripOff.TERMINAL
        val cleaned = if (exact) text else LinkCleaner.clean(text)
        val outcome = insertExternalText(cleaned)
        // Only a paste the editor confirmed, at a caret it reports, can be put back: an insertion a length limit
        // blocked can leave the same text before the caret that a successful one would.
        val caret = if (cleaned !== text && outcome == VerifiedWordTransaction.Insertion.APPLIED)
            readEditorSnapshot()?.selection?.takeIf { it.collapsed }?.end else null
        if (caret != null) {
            cleanedPaste = CleanedPaste(AutoCorrection(original = text, committed = cleaned, keep = false), caret)
            showLinkCleaned()
        }
        return outcome
    }

    /** The paste chip says when what it pastes will lose tracking, the way [insertPasted] will clean it. */
    private fun pasteDescription(): Int {
        val text = pasteClip?.text ?: return R.string.paste_description
        if (!cleanLinks || inputIsSensitive || stripOff == TypingRules.StripOff.TERMINAL) return R.string.paste_description
        val cleans = pasteCleanCheck?.takeIf { it.first == text }?.second
            ?: (LinkCleaner.clean(text) !== text).also { pasteCleanCheck = text to it }
        return if (cleans) R.string.paste_clean_description else R.string.paste_description
    }

    private fun showLinkCleaned() {
        // Saved text has the strip for its title: the title says it for a moment, and the panel's delete key undoes.
        if (panel == KeyboardPanel.SAVED) return showSavedTitle(getString(R.string.link_cleaned_panel))
        // The proofread row has the strip; delete still undoes.
        if (panel != null || proofreading || proposal != null || proposalRow?.visibility == View.VISIBLE) return
        hideGestureNotice()
        linkNotice = true
        correctionAction?.visibility = View.VISIBLE
        correctionText?.text = getString(R.string.link_cleaned)
        correctionText?.contentDescription = getString(R.string.link_cleaned)
        correctionAction?.text = getString(R.string.correction_undo)
        correctionAction?.contentDescription = getString(R.string.correction_undo)
        correctionRow?.visibility = View.VISIBLE
        updateSlotVisibility()
        updateLead()
        handler.removeCallbacks(hideCorrectionMessage)
        handler.removeCallbacks(hideLinkMessage)
        handler.postDelayed(hideLinkMessage, LINK_NOTICE_MS)
    }

    private val hideLinkMessage = Runnable { hideLinkNotice() }
    private var savedTitleNotice = false
    private val restoreSavedTitle = Runnable {
        savedTitleNotice = false
        savedPanel?.header?.text = getString(R.string.saved_text)
    }

    /** Saved text's title says [message] for a few seconds, and TalkBack hears it once. */
    private fun showSavedTitle(message: String) {
        val title = savedPanel?.header ?: return
        title.text = message
        title.announceForAccessibility(message)
        savedTitleNotice = true
        handler.removeCallbacks(restoreSavedTitle)
        handler.postDelayed(restoreSavedTitle, noticeMillis())
    }

    /**
     * Says what a toast once said, in the keyboard's own views: Android 13 and later drop a keyboard's toasts unless
     * the app may post notifications. [NoticeRules.place] picks where: the status row, Saved text's title, or a line
     * laid over the emoji panel's tabs or search bar, which stay as they are underneath. It ends after
     * [noticeMillis], at the next key or insertion, when a panel opens or closes, or with the field. TalkBack hears
     * it once.
     */
    private fun showNotice(message: NoticeRules.Message) {
        hideNotice()
        val text = getString(when (message) {
            NoticeRules.Message.REFUSED -> R.string.insert_refused
            NoticeRules.Message.UNVERIFIED -> R.string.insert_unverified
            NoticeRules.Message.SWIPE_OFF_HERE -> R.string.swipe_off_here
            NoticeRules.Message.SWIPE_NEW_WORD -> R.string.swipe_new_word
            NoticeRules.Message.SWIPE_SELECTION -> R.string.swipe_selection
            NoticeRules.Message.SWIPE_LANGUAGE -> R.string.swipe_english_only
            NoticeRules.Message.LANGUAGE -> if (language.english || !language.words) R.string.language_now else R.string.language_now_experimental
            NoticeRules.Message.TOOL_PINNED -> R.string.tool_pinned
            NoticeRules.Message.TOOL_UNPINNED -> R.string.tool_unpinned
            NoticeRules.Message.TOOLS_ROW_FULL -> R.string.tools_row_full
            NoticeRules.Message.NOT_OPENED -> R.string.passwords_not_opened
            NoticeRules.Message.EFFECT_SOURCE_CHANGED -> R.string.text_effects_source_changed
            NoticeRules.Message.COPY_FAILED -> R.string.local_tool_copy_failed
        }, language.nativeName)
        val place = NoticeRules.place(panel)
        when (place) {
            NoticeRules.Place.SAVED_TITLE -> showSavedTitle(text)
            NoticeRules.Place.OVER_HEADER -> {
                val header = panelHeader ?: return
                val view = headerNotice ?: noticeView().apply {
                    // Opaque, and it takes the tap: a tab it covers must not answer a touch on the message.
                    setBackgroundColor(getColor(R.color.kb_background))
                    setOnClickListener { hideNotice() }
                    ViewCompat.replaceAccessibilityAction(this,
                        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                        getString(R.string.notice_dismiss), null)
                }.also { headerNotice = it }
                view.text = text
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                header.addView(view, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                view.announceForAccessibility(text)
            }
            NoticeRules.Place.STATUS_ROW -> {
                // One row at a time: the notice is about what was just tried, which is newer than any of these.
                hideLinkNotice()
                hideCorrectionFeedback()
                dismissProposal()
                stripNotice = text
                updateVoiceButton()
                noticeText?.announceForAccessibility(text)
            }
        }
        notice = message
        noticePlace = place
        noticesShown++
        handler.postDelayed(hideNoticeMessage, noticeMillis())
    }

    private val hideNoticeMessage = Runnable { hideNotice() }

    /** Ends a notice, wherever it is: Saved text's title is its own again, and the strip goes back to what it had. */
    private fun hideNotice() {
        if (savedTitleNotice) {
            handler.removeCallbacks(restoreSavedTitle)
            restoreSavedTitle.run()
        }
        if (notice == null) return
        notice = null
        handler.removeCallbacks(hideNoticeMessage)
        headerNotice?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        if (stripNotice == null) return
        stripNotice = null
        updateVoiceButton()
    }

    /** A notice is a sentence, read from its start: two lines where the strip is too narrow for one. */
    private fun noticeView() = TextView(this).apply {
        setTextColor(getColor(R.color.kb_on_key))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        readFromStart()
    }

    /**
     * A sentence in the strip is read from its first word: two lines where one is too narrow, cut at the end. The
     * strip's height is fixed and the person's text size is not, so the text grows with it only as far as two lines
     * still fit ([NoticeRules.twoLineSize]); a second line cut through its middle says less than a smaller whole one.
     */
    private fun TextView.readFromStart() {
        sentenceSize()
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    /** 14sp at the person's text size, held to the largest size whose two lines fit the strip. */
    private fun TextView.sentenceSize() {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val metrics = paint.fontMetricsInt
        // Two lines as a TextView stacks them: the first from the font's top, the second down to its bottom.
        val twoLines = metrics.bottom - metrics.top + metrics.descent - metrics.ascent
        setTextSize(TypedValue.COMPLEX_UNIT_PX, NoticeRules.twoLineSize(textSize, twoLines.toFloat(), dp(stripHeight.dp).toFloat()))
    }

    /** [NOTICE_MS], or longer for someone who has asked Android for more time to read messages that go away. */
    private fun noticeMillis(): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return NOTICE_MS
        val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return NOTICE_MS
        return manager.getRecommendedTimeoutMillis(NOTICE_MS.toInt(), android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT).toLong()
    }

    /** Ends the strip's offer to put a cleaned link back: its timer ran out, a key was pressed, or it was taken up. */
    private fun hideLinkNotice() {
        if (!linkNotice) return
        linkNotice = false
        handler.removeCallbacks(hideLinkMessage)
        hideCorrectionFeedback()
    }

    /**
     * The cleaned paste to undo, taken once: null when there is none or the caret has left the place the paste
     * put it ([LinkCleaner.caretUnmoved]). [undoAutocorrect] then checks the text before the caret as well.
     */
    private fun takeCleanedPaste(): AutoCorrection? {
        val paste = cleanedPaste ?: return null
        cleanedPaste = null
        return paste.undo.takeIf { LinkCleaner.caretUnmoved(paste.caret, readEditorSnapshot()?.selection) }
    }

    /** Undo in the strip after a cleaned paste: the replacement delete would make, at the place it was pasted. */
    private fun undoLinkCleaning() {
        val undo = takeCleanedPaste()
        hideLinkNotice()
        if (undo == null) return
        lastAutocorrect = undo
        if (undoAutocorrect()) {
            revisitTrace(CorrectionDiagnostics.Revisit.UNDO)
            scheduleRefresh()
        }
    }

    private fun insertPanelEmoji(emoji: String) {
        if (insertFromPanel(emoji)) rememberEmoji(emoji)
    }

    /**
     * Recent emoji are kept only where learning is allowed, so password and incognito fields leave
     * none. They're history, which Forget learned words clears, so this syncs first and can't write
     * a forgotten list back.
     */
    private fun rememberEmoji(emoji: String) {
        if (!learningAllowed) return
        syncLearnedState()
        recentEmoji.record(emoji)
    }

    /**
     * 🔍 in the emoji header: the grid gives way to the letter keys at their own height, and the
     * strip becomes the search bar. The query starts lowercase whatever the field asks, and Enter
     * shows Done, because it goes back to the grid rather than acting on the field.
     */
    private fun startEmojiSearch() {
        val emoji = emojiPanel ?: return
        val header = panelHeader ?: return
        if (panel != KeyboardPanel.EMOJI || emojiQuery != null) return
        emojiPopup.dismiss()
        emojiSearchMode = mode
        emojiSearchShift = shiftState
        emojiSearchHeight = keyArea?.pinnedHeight?.takeIf { it > 0 } ?: keyboardView?.height ?: 0
        emojiQuery = ""
        emojiPanelRoot?.visibility = View.GONE
        keyArea?.pinnedHeight = 0
        setKeysVisible(true)
        setMode(KeyboardLayout.Mode.LETTERS)
        setShift(KeyboardView.ShiftState.OFF)
        keyboardView?.enterLabel = enterLabel(currentInputEditorInfo)
        header.removeAllViews()
        header.addView(emoji.searchBar, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        emoji.showSearch("", emojiSuggestions.phrases)
        // 🔍 went with the tabs: TalkBack's focus goes to the query instead of nowhere.
        TalkBackFocus.moveTo(emoji.queryView)
    }

    /**
     * Enter or ✕ while searching: back to the grid, pinned at the keys' height again and refilled,
     * so Recent includes what search just inserted, where the field let it be recorded. Both go,
     * so TalkBack's focus moves to 🔍, back among the tabs.
     */
    private fun endEmojiSearch() {
        if (emojiQuery == null || panel != KeyboardPanel.EMOJI) return
        stopEmojiSearch()
        val emoji = emojiPanel ?: return closePanel()
        val header = panelHeader ?: return closePanel()
        keyArea?.pinnedHeight = emojiSearchHeight.takeIf { it > 0 } ?: keyboardView?.height ?: 0
        setKeysVisible(false)
        header.removeAllViews()
        header.addView(emoji.header, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        emoji.show(emojiInventory, recentEmoji.items, emojiLoading)
        // Hidden while searching, so this is a visibility change that has TalkBack announce the pane again.
        emojiPanelRoot?.visibility = View.VISIBLE
        TalkBackFocus.moveTo(emoji.searchButton)
    }

    /** Drops the query and gives the keys, Enter and shift back to the field. What shows next is the caller's choice. */
    private fun stopEmojiSearch() {
        if (emojiQuery == null) return
        emojiQuery = null
        emojiPanel?.hideSearch()
        // Not setMode: it would replace a Shift the person set (or turned off) with the editor's caps mode.
        shiftState = emojiSearchShift
        mode = emojiSearchMode
        applyLayout()
        keyboardView?.enterLabel = enterLabel(currentInputEditorInfo)
    }

    /**
     * A key while searching emoji. Letters, space and delete edit the query (shown with shift
     * applied; matching ignores case), and Enter goes back to the grid. Shift and the layer keys
     * work as usual. Nothing here reads or edits the field, the trace, learning, the typing log,
     * autocorrect, the corrector or the model.
     */
    private fun searchKey(action: KeyAction) {
        val query = emojiQuery ?: return
        when (action) {
            KeyAction.Enter -> endEmojiSearch()
            KeyAction.Shift -> toggleShift()
            KeyAction.ToSymbols -> setMode(KeyboardLayout.Mode.SYMBOLS)
            KeyAction.ToMoreSymbols -> setMode(KeyboardLayout.Mode.MORE_SYMBOLS)
            KeyAction.ToLetters -> setMode(KeyboardLayout.Mode.LETTERS)
            KeyAction.ToFieldLayout -> setMode(KeyboardLayout.Mode.LETTERS)
            KeyAction.SwitchKeyboard -> switchKeyboard()
            KeyAction.Voice -> Unit
            is KeyAction.Text, KeyAction.Space, KeyAction.Backspace -> {
                val typed = if (action is KeyAction.Text && shiftState != KeyboardView.ShiftState.OFF) KeyAction.Text(TypingRules.shifted(action.value)) else action
                val edited = EmojiSearch.edit(query, typed) ?: return
                if (action is KeyAction.Text && shiftState == KeyboardView.ShiftState.ON) setShift(KeyboardView.ShiftState.OFF)
                emojiQuery = edited
                emojiPanel?.showSearch(edited, emojiSuggestions.phrases)
            }
        }
    }

    /** What Enter shows: Done while searching emoji, where it only goes back to the grid, else the field's own action. */
    private fun enterLabel(info: EditorInfo?): String = when {
        localInputKind != null -> getString(R.string.local_tool_done)
        emojiQuery != null -> TypingRules.enterLabel(EditorInfo.IME_ACTION_DONE)
        else -> info?.let { TypingRules.enterAction(it.imeOptions) }?.let(TypingRules::enterLabel) ?: "↵"
    }

    // endregion

    /** Local character tools share the panel shell and the paste insertion contract. */
    private fun characterToolRoot(area: KeyArea, kind: KeyboardPanel, title: Int, content: View): View =
        PanelViews.root(this, getString(title), content,
            panelBar(kind, PanelViews.Toggle(R.drawable.ic_tools_more, R.string.tools_all) { switchPanel(KeyboardPanel.TOOLS) })
        ).also { area.addView(it, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)) }

    private fun effectsAllowed(): Boolean = StripTools.allowed(StripTool.TEXT_EFFECTS, gestureState())

    private fun openTextEffects() {
        if (!effectsAllowed()) return
        // Read only a bounded, explicitly selected range. No surrounding field or password draft is harvested.
        val range = observedSelection()
        val size = range?.let { kotlin.math.abs(it.end.toLong() - it.start.toLong()) } ?: 0
        if (range != null && size > 0) {
            val selected = if (range.start >= 0 && range.end >= 0 && size <= TextEffects.MAX_INPUT)
                currentInputConnection?.getSelectedText(0) else null
            val fresh = selected?.takeIf { it.length.toLong() == size }?.let {
                EffectsSelection(range.start, range.end, it.toString())
            }
            if (fresh == null) {
                // An unreadable or oversized new selection must not reuse an older source over it.
                effectsSource = ""; effectsSelection = null; effectsEditorSelection = null
            } else if (fresh != effectsEditorSelection) {
                effectsSource = fresh.text
                effectsSelection = fresh
                effectsEditorSelection = fresh
            }
        }
        openPanel(KeyboardPanel.TEXT_EFFECTS)
    }

    private fun insertTextEffect(text: String) {
        if (panel != KeyboardPanel.TEXT_EFFECTS || localInputKind != null || !effectsAllowed()) return
        val original = effectsSelection
        if (original != null) {
            val range = observedSelection()
            if (range?.start != original.start || range?.end != original.end) {
                return showNotice(NoticeRules.Message.EFFECT_SOURCE_CHANGED)
            }
            val selected = currentInputConnection?.getSelectedText(0)
            if (selected == null || selected.length != original.text.length || selected.toString() != original.text) {
                return showNotice(NoticeRules.Message.EFFECT_SOURCE_CHANGED)
            }
        }
        drainPendingKeys()
        if (insertExternalText(text, expectedSelection = original) == VerifiedWordTransaction.Insertion.APPLIED) {
            effectsSelection = null
            closePanel()
        }
    }

    private fun copyTextEffect(text: String) {
        if (panel != KeyboardPanel.TEXT_EFFECTS || localInputKind != null || !effectsAllowed()) return
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        runCatching { clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.text_effects_title), text)) }
            .onSuccess { effectsPanelRoot?.announceForAccessibility(getString(R.string.local_tool_copied)) }
            .onFailure { showNotice(NoticeRules.Message.COPY_FAILED) }
    }

    private fun clearCharacterTools() {
        if (panel == KeyboardPanel.SYMBOLS || panel == KeyboardPanel.TEXT_EFFECTS) closePanel()
        symbolQuery = ""; effectsSource = ""; effectsSelection = null; effectsEditorSelection = null
        symbolsPanel?.show(""); effectsPanel?.clear()
    }

    private fun startCharacterToolInput(kind: KeyboardPanel) {
        if (panel != kind || localInputKind != null || (kind == KeyboardPanel.TEXT_EFFECTS && !effectsAllowed())) return
        localInputMode = mode; localInputShift = shiftState
        // Editing explicitly turns the source copy into an independent local draft.
        if (kind == KeyboardPanel.TEXT_EFFECTS) effectsSelection = null
        localInputKind = kind
        panelRootOf(kind)?.visibility = View.GONE
        setKeysVisible(true)
        // The pinned panel height remains fixed while the ordinary keys collect a local draft/query.
        setMode(KeyboardLayout.Mode.LETTERS); setShift(KeyboardView.ShiftState.OFF)
        val ui = CharacterToolViews(this)
        val query = ui.text("").apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        localInputHeader = query
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(query, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(ui.button(getString(R.string.local_tool_done)) { endCharacterToolInput() })
        }
        panelHeader?.removeAllViews(); panelHeader?.addView(row)
        updateCharacterToolQuery()
        keyboardView?.enterLabel = getString(R.string.local_tool_done)
        TalkBackFocus.moveTo(query)
    }

    private fun updateCharacterToolQuery() {
        val value = if (localInputKind == KeyboardPanel.SYMBOLS) symbolQuery else effectsSource
        localInputHeader?.text = value.ifEmpty { getString(if (localInputKind == KeyboardPanel.SYMBOLS) R.string.local_tool_search else R.string.local_tool_empty) }
    }

    private fun stopCharacterToolInput() {
        if (localInputKind == null) return
        localInputKind = null; localInputHeader = null
        mode = localInputMode; shiftState = localInputShift
        applyLayout(); keyboardView?.enterLabel = enterLabel(currentInputEditorInfo)
    }

    private fun endCharacterToolInput() {
        val kind = localInputKind ?: return
        stopCharacterToolInput()
        val area = keyArea ?: return
        val (title, root) = panelViews(kind, area)
        setKeysVisible(false)
        panelHeader?.removeAllViews(); panelHeader?.addView(title)
        if (kind == KeyboardPanel.SYMBOLS) symbolsPanel?.show(symbolQuery) else effectsPanel?.show(effectsSource)
        root.visibility = View.VISIBLE
        TalkBackFocus.moveTo(title)
    }

    private fun characterToolKey(action: KeyAction) {
        val kind = localInputKind ?: return
        when (action) {
            KeyAction.Enter -> endCharacterToolInput()
            KeyAction.Shift -> toggleShift()
            KeyAction.ToSymbols -> setMode(KeyboardLayout.Mode.SYMBOLS)
            KeyAction.ToMoreSymbols -> setMode(KeyboardLayout.Mode.MORE_SYMBOLS)
            KeyAction.ToLetters, KeyAction.ToFieldLayout -> setMode(KeyboardLayout.Mode.LETTERS)
            KeyAction.SwitchKeyboard, KeyAction.Voice -> Unit
            else -> {
                val typed = if (action is KeyAction.Text && shiftState != KeyboardView.ShiftState.OFF) KeyAction.Text(TypingRules.shifted(action.value)) else action
                val old = if (kind == KeyboardPanel.SYMBOLS) symbolQuery else effectsSource
                val next = LocalPanelInput.edit(old, typed, if (kind == KeyboardPanel.SYMBOLS) 64 else TextEffects.MAX_INPUT) ?: return
                if (kind == KeyboardPanel.SYMBOLS) symbolQuery = next else effectsSource = next
                if (action is KeyAction.Text && shiftState == KeyboardView.ShiftState.ON) setShift(KeyboardView.ShiftState.OFF)
                updateCharacterToolQuery()
            }
        }
    }

    // region Candidates

    /** The strip's height and its words' largest size, from settings; the icons stay 48 dp, centered. */
    private fun applyStripHeight(height: StripHeight) {
        if (height == stripHeight && candidateStripView?.layoutParams?.height == dp(height.dp)) return
        stripHeight = height
        candidateStripView?.let { strip -> strip.layoutParams = strip.layoutParams?.apply { this.height = dp(height.dp) } }
        candidateViews.forEach { it.setAutoSizeTextTypeUniformWithConfiguration(StripHeight.MIN_WORD_SP, height.wordSp, 1, TypedValue.COMPLEX_UNIT_SP) }
    }

    private fun candidateStrip(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(4), 0)
        candidateViews.clear()
        slotViews.clear()

        leadButton = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER
            // No fill: the icon sits in the strip like text, with just a touch ripple.
            background = getDrawable(R.drawable.candidate_background)
            visibility = View.GONE
            setOnClickListener { onLeadTapped() }
            // Hold to save a fresh copy while it's the paste chip, otherwise to proofread the current sentence.
            KeyHaptics.onHold(this) { onLeadHeld(); true }
            // The paste chip lasts two minutes after a copy; this way into Saved text is always there.
            // The lead button hides while a panel is open, so TalkBack's focus moves to the emoji button, which stays.
            ViewCompat.addAccessibilityAction(this, getString(R.string.saved_text)) { _, _ ->
                openSavedPanel()
                TalkBackFocus.moveTo(toolsHandle)
                true
            }
        }
        leadHoldAction = View.NO_ID
        leadHoldLabel = 0
        updateLeadHoldAction()
        // Fixed size whatever it shows, so the suggestion slots never shift under the thumb.
        addView(leadButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(4) })

        privateNotice = TextView(context).apply {
            text = getString(R.string.private_field)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.kb_hint))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener {
                if (blockedLoadFailed()) startActivity(android.content.Intent(this@LocalTypeInputMethodService, SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            isClickable = false
            isFocusable = false
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (host.isClickable) info.className = android.widget.Button::class.java.name
                }
            }
        }
        addView(privateNotice, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        hostChip = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(getColor(R.color.kb_accent))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            background = getDrawable(R.drawable.candidate_background)
            visibility = View.GONE
            setOnClickListener { acceptCandidate(expansionSlot) }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
        }
        addView(hostChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        repeat(CANDIDATE_COUNT) { index ->
            if (index > 0) addView(View(context).apply { setBackgroundColor(getColor(R.color.kb_divider)) }.also { slotViews += it },
                LinearLayout.LayoutParams(dp(1), dp(20)))
            val candidate = TextView(context).apply {
                setTextColor(getColor(R.color.kb_on_key))
                gravity = Gravity.CENTER
                maxLines = 1
                // A long word shrinks to fit its slot before it is cut: its ending is what tells it from the others.
                setAutoSizeTextTypeUniformWithConfiguration(StripHeight.MIN_WORD_SP, stripHeight.wordSp, 1, TypedValue.COMPLEX_UNIT_SP)
                ellipsize = TextUtils.TruncateAt.MIDDLE
                // A long word or an expansion fills its slot: keep it off the dividers.
                setPadding(dp(4), 0, dp(4), 0)
                background = getDrawable(R.drawable.candidate_background)
                if (index == 1) paint.isFakeBoldText = true
                setOnClickListener { acceptCandidate(index) }
                KeyHaptics.onHold(this) { view -> showEmojiVariants(index, view) }
                androidx.core.view.ViewCompat.addAccessibilityAction(this, getString(R.string.emoji_variants_action)) { view, _ ->
                    showEmojiVariants(index, view)
                }
            }
            candidateViews += candidate
            slotViews += candidate
            addView(candidate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }

        selectionProofread = TextView(context).apply {
            text = getString(R.string.proofread_selection)
            contentDescription = getString(R.string.proofread_selection)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(getColor(R.color.kb_accent))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            background = getDrawable(R.drawable.candidate_background)
            visibility = View.GONE
            setOnClickListener { proofreadSentence() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
        }
        addView(selectionProofread, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        proposalRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            // Sized and cut by what fills it: showProposalMessage for a sentence, showProposal for a rewrite.
            proposalText = TextView(context).apply {
                setTextColor(getColor(R.color.kb_on_key))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(proposalText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            proposalApply = stripIcon(R.drawable.ic_apply, R.string.proofread_apply) { applyProposal() }
            addView(proposalApply, LinearLayout.LayoutParams(dp(48), dp(48)))
            proposalSettings = TextView(context).apply {
                text = getString(R.string.proofread_settings)
                // The sentence's size, on one line: at a larger text size the button widens and never breaks its word.
                sentenceSize()
                maxLines = 1
                minWidth = dp(64)
                setPadding(dp(6), 0, dp(6), 0)
                setTextColor(getColor(R.color.kb_accent))
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.candidate_background)
                visibility = View.GONE
                setOnClickListener {
                    startActivity(android.content.Intent(this@LocalTypeInputMethodService, SettingsActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            addView(proposalSettings, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
            // ✕ while it's still checking cancels the check, so the row doesn't come back with the answer.
            addView(stripIcon(R.drawable.ic_dismiss, R.string.proofread_dismiss) { proofreadJob?.cancel(); dismissProposal() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        addView(proposalRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        correctionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            correctionText = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(getColor(R.color.kb_on_key))
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(correctionText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            correctionAction = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.kb_accent))
                background = getDrawable(R.drawable.candidate_background)
                setOnClickListener {
                    when {
                        gestureNotice -> undoGestureDelete()
                        linkNotice -> undoLinkCleaning()
                        correctionOffer != null -> applyRecentOffer()
                        else -> undoRecentCorrection()
                    }
                }
            }
            addView(correctionAction, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        addView(correctionRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        voiceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            voiceText = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(getColor(R.color.kb_on_key))
                maxLines = 1
                // The newest words are the ones to watch. Not a live region: TalkBack reading the words back
                // would be heard by the microphone. Starting and stopping are announced once each.
                ellipsize = TextUtils.TruncateAt.START
            }
            addView(voiceText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            noticeText = noticeView().apply { visibility = View.GONE }
            addView(noticeText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(voiceRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val chipRow = InlineChipRow(context)
        chipRow.visibility = View.GONE
        inlineRow = chipRow
        addView(chipRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        // Chips that outlived the old views (a rotation, a theme change) move into the new row.
        inlineAutofill?.attach(chipRow)

        panelHeader = android.widget.FrameLayout(context).apply { visibility = View.GONE }
        headerNotice = null
        addView(panelHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        voiceButton = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            // Selected while listening, the same way the emoji button is while its panel is open.
            background = getDrawable(R.drawable.panel_tab_background)
            contentDescription = getString(R.string.voice_input)
            visibility = View.GONE
            setOnClickListener { onVoiceTapped() }
        }
        addView(voiceButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        updateVoiceButton()

        // Emoji and Saved text moved into the tools row; the handle took the emoji button's place.
        emojiButton = null
        toolsOpen = false
        toolRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            visibility = View.GONE
        }
        addView(toolRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        toolsHandle = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_tools)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            // Selected while the Tools panel is open, as the emoji button was for its panel.
            background = getDrawable(R.drawable.panel_tab_background)
            contentDescription = getString(R.string.tools_title)
            setOnClickListener { onToolsHandle() }
            // Holding is a shortcut to every tool, never the only way: More in the row opens the same panel.
            KeyHaptics.onHold(this) { openToolsPanel(); true }
            ViewCompat.addAccessibilityAction(this, getString(R.string.tools_all)) { _, _ -> openToolsPanel(); true }
        }
        addView(toolsHandle, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        updateToolsHandle()
    }

    private fun stripIcon(icon: Int, description: Int, onTap: () -> Unit) = android.widget.ImageView(this).apply {
        setImageResource(icon)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        background = getDrawable(R.drawable.candidate_background)
        contentDescription = getString(description)
        setOnClickListener { onTap() }
    }

    /** Explicitly review the selected text, or the current sentence when the caret is collapsed. */
    /**
     * Proofreads, or with a [rewrite] style rewrites, the selection or the sentence before the caret. Both end in the
     * same proposal row, applied only by a tap and verified the same way; nothing in either is learned.
     */
    private fun proofreadSentence(rewrite: RewriteStyle? = null) {
        personalWordOwned = false
        personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        spaceProofread.reset()
        if (!suggestionsEnabled || !fieldWantsSuggestions || panel != null || !foundationAllowed) return
        if (proofreading || modelRunning) {
            showProposalMessage(getString(R.string.proofread_busy), autoDismissMs = 2_000)
            return
        }
        cancelRecentCorrection()
        val request = ProofreadRequest.capture(editorEdits.generation, readEditorSnapshot(ProofreadRequest.LIMIT))
            ?: return showProposalMessage(getString(R.string.proofread_unverified), autoDismissMs = 3_000)
        KeyHaptics.perform(keyboardView, android.view.HapticFeedbackConstants.LONG_PRESS)
        proofreading = true
        setModelRunning(true)
        showProposalMessage(getString(if (rewrite == null) R.string.proofread_working else R.string.rewrite_working))
        proofreadJob = uiScope.launch {
            try {
                var status = withContext(Dispatchers.Default) { foundationModel.status() }
                val deadline = SystemClock.uptimeMillis() + MANUAL_LOAD_WAIT_MS
                while (status == FoundationModelStatus.INITIALIZING && SystemClock.uptimeMillis() < deadline) {
                    kotlinx.coroutines.delay(1_000)
                    status = withContext(Dispatchers.Default) { foundationModel.status() }
                }
                val corrected = if (status == FoundationModelStatus.READY) {
                    // The native worker cannot cancel queued work. Discard a cancelled/stale answer on return.
                    withContext(NonCancellable + Dispatchers.Default) {
                        if (rewrite == null) foundationModel.proofread(request.original) else rewriteWith(rewrite, request.original)
                    }
                } else null
                if (!viewShown || !request.matches(editorEdits.generation, readEditorSnapshot(ProofreadRequest.LIMIT))) {
                    dismissProposal()
                    return@launch
                }
                when {
                    status == FoundationModelStatus.INITIALIZING -> showProposalMessage(
                        getString(R.string.model_still_loading), autoDismissMs = 3_000)
                    status != FoundationModelStatus.READY -> {
                        showProposalMessage(getString(if (rewrite == null) R.string.proofread_setup else R.string.rewrite_setup))
                        proposalSettings?.visibility = View.VISIBLE
                    }
                    corrected == null -> showProposalMessage(getString(R.string.proofread_unavailable), autoDismissMs = 3_000)
                    corrected == request.original -> showProposalMessage(getString(
                        if (rewrite == null) R.string.proofread_no_changes else R.string.rewrite_no_changes), autoDismissMs = 1_500)
                    else -> showProposal(ProofreadProposal(request, corrected))
                }
            } catch (cancelled: CancellationException) {
                scheduleRefresh()
                throw cancelled
            } catch (_: Throwable) {
                if (viewShown && request.matches(editorEdits.generation, readEditorSnapshot(ProofreadRequest.LIMIT)))
                    showProposalMessage(getString(R.string.proofread_unavailable), autoDismissMs = 3_000)
            } finally {
                proofreading = false
                setModelRunning(false)
            }
        }
    }

    /**
     * A rewrite from the first ready on-device model, Gemini Nano before Gemma as for the model chat, read by
     * [Rewriting.clean]; null when none answers with a plausible one.
     */
    private suspend fun rewriteWith(style: RewriteStyle, text: String): String? {
        val prompt = Rewriting.prompt(style, text)
        FoundationModelProviderFactory.chatProviders(this).forEach { provider ->
            if (provider.status() != FoundationModelStatus.READY) return@forEach
            provider.generate(prompt)?.let { Rewriting.clean(text, it, style) }?.let { return it }
        }
        return null
    }

    /** Swaps the suggestion slots (and their dividers) for the proposal row, or back. */
    private fun setProposalVisible(visible: Boolean) {
        val row = proposalRow ?: return
        if (visible) { hideGestureNotice(); hideCorrectionFeedback(); hideNotice() }
        row.visibility = if (visible) View.VISIBLE else View.GONE
        updateSlotVisibility()
    }

    /**
     * The one place that decides what the middle of the strip shows, and the only writer of the notice's, the
     * chip row's and the host chip's visibility. In order: a panel's header, the proofread row, the correction
     * row, the status row (a notice from [showNotice], dictation, a delete slide), a password manager's chips,
     * the Proofread selection button, then the word slots, or where a field has none a remembered host or the
     * field's one-line notice.
     */
    private fun updateSlotVisibility() {
        val busy = panel != null || proposalRow?.visibility == View.VISIBLE || correctionRow?.visibility == View.VISIBLE ||
            voiceRow?.visibility == View.VISIBLE
        // The tools row, once opened, has the middle of the strip until it closes or something above takes it.
        val tools = toolsOpen && !busy
        val toolsWere = toolRow?.visibility == View.VISIBLE
        toolRow?.visibility = if (tools) View.VISIBLE else View.GONE
        // The lead button shows only while the strip is for words ([updateLead]).
        if (tools != toolsWere) updateLead()
        if (tools) {
            inlineRow?.visibility = View.GONE
            hostChip?.visibility = View.GONE
            privateNotice?.visibility = View.GONE
            selectionProofread?.visibility = View.GONE
            slotViews.forEach { it.visibility = View.GONE }
            return
        }
        val chips = inlineAutofill
        val blockedFailure = stripOff == null && blockedLoadFailed()
        val inline = !busy && !blockedFailure && chips != null && InlineRowRules.shows(chips.count, chips.unpinned, stripOff == null, inlineTyped)
        inlineRow?.visibility = if (inline) View.VISIBLE else View.GONE
        val host = !busy && !inline && stripOff != null && expansion != null
        hostChip?.visibility = if (host) View.VISIBLE else View.GONE
        val notice = !busy && !inline && !host && (stripOff != null || blockedFailure)
        privateNotice?.visibility = if (notice) View.VISIBLE else View.GONE
        val occupied = busy || inline || stripOff != null || blockedFailure
        val selected = selectionProofreadEligible && suggestionsEnabled && fieldWantsSuggestions && !occupied
        selectionProofread?.visibility = if (selected) View.VISIBLE else View.GONE
        slotViews.forEach { it.visibility = if (occupied || selected) View.GONE else View.VISIBLE }
    }

    private val dismissMessageRunnable = Runnable { if (proposal == null) dismissProposal() }

    /** The proofread row says [message] in place of a rewrite, from its first word as a notice is. */
    private fun showProposalMessage(message: String, autoDismissMs: Long = 0) {
        proposal = null
        proposalText?.readFromStart()
        proposalText?.text = message
        proposalText?.contentDescription = null
        proposalApply?.visibility = View.GONE
        proposalSettings?.visibility = View.GONE
        setProposalVisible(true)
        handler.removeCallbacks(dismissMessageRunnable)
        if (autoDismissMs > 0) handler.postDelayed(dismissMessageRunnable, autoDismissMs)
    }

    private fun showProposal(value: ProofreadProposal) {
        proposal = value
        handler.removeCallbacks(dismissMessageRunnable)
        val accent = getColor(R.color.kb_accent)
        val spannable = android.text.SpannableStringBuilder()
        Proofreading.diff(value.original, value.corrected).forEachIndexed { index, segment ->
            if (index > 0) spannable.append(' ')
            val start = spannable.length
            spannable.append(segment.text)
            if (segment.changed) {
                spannable.setSpan(android.text.style.ForegroundColorSpan(accent), start, spannable.length, 0)
                spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, spannable.length, 0)
            }
        }
        proposalText?.run {
            // One line, cut at its start: the end of the sentence is where the caret is.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            text = spannable
            contentDescription = getString(R.string.proofread_proposal_description, value.corrected)
        }
        proposalApply?.visibility = View.VISIBLE
        proposalSettings?.visibility = View.GONE
        setProposalVisible(true)
    }

    private fun applyProposal() {
        val value = proposal ?: return dismissProposal()
        val request = value.request
        if (!request.matches(editorEdits.generation, readEditorSnapshot(ProofreadRequest.LIMIT))) return dismissProposal()
        val edit = request.replacement(value.corrected)
        val applied = applyWordReplacement(edit, precondition = request.snapshot, readLimit = ProofreadRequest.LIMIT)
        if (applied == edit) {
            // A verified explicit rewrite does not teach typing or touch offsets.
            lastAutocorrect = AutoCorrection(original = request.replaced, committed = edit.text, keep = false)
            autoSpaced = false
            revisitTrace(CorrectionDiagnostics.Revisit.PROOFREAD)
        } else {
            lastAutocorrect = null
        }
        dismissProposal()
        // Said once the row it replaces has gone: the rewrite may or may not be in the field.
        if (applied != edit) showNotice(NoticeRules.Message.UNVERIFIED)
        updateShiftFromEditor()
        TalkBackFocus.moveTo(leadButton)
    }

    private fun dismissProposal() {
        proposal = null
        handler.removeCallbacks(dismissMessageRunnable)
        if (proposalRow?.visibility == View.VISIBLE) {
            setProposalVisible(false)
            scheduleRefresh()
        }
    }

    private fun onLeadTapped() {
        if (recentCorrection.hasUndo(SystemClock.uptimeMillis()) && !proofreading && proposal == null) return undoRecentCorrection()
        onLeadOriginalTapped()
    }

    private fun onLeadOriginalTapped() {
        if (pasteClip != null) {
            // The system clipboard may have changed since this chip was drawn.
            val clip = freshClipText() ?: run { pasteClip = null; scheduleRefresh(); return }
            val stamp = clipStamp()
            val outcome = insertPasted(clip.text)
            if (outcome != VerifiedWordTransaction.Insertion.REJECTED) {
                // An ambiguous receipt could follow a successful paste. Suppress its quick chip
                // too, without claiming success or automatically repeating the command.
                pastedClipStamp = stamp
                pasteClip = null
            }
            return
        }
        if (suggestionsEnabled && !modelRunning) generateOnTap()
    }

    /**
     * Holding the lead button. While it's the paste chip, keeps that copy in Saved text and opens
     * the panel, which says "Saved" and puts TalkBack's focus on the item; a copy marked sensitive
     * or made in a password or incognito field is never kept, and the strip says so instead.
     * Otherwise it proofreads the sentence.
     */
    private fun onLeadHeld() {
        if (recentCorrection.hasUndo(SystemClock.uptimeMillis())) {
            val popup = android.widget.PopupMenu(this, leadButton)
            if (pasteClip != null || sparkleShown()) {
                popup.menu.add(if (pasteClip != null) R.string.paste_description else R.string.model_trigger).setOnMenuItemClickListener { onLeadOriginalTapped(); true }
            }
            popup.menu.add(R.string.proofread_action).setOnMenuItemClickListener { proofreadSentence(); true }
            popup.menu.add(R.string.saved_text).setOnMenuItemClickListener { openSavedPanel(); true }
            popup.show()
            return
        }
        val clip = pasteClip ?: return proofreadSentence()
        if (!clip.mayKeep) return showProposalMessage(getString(R.string.saved_text_private_copy), autoDismissMs = PRIVATE_COPY_NOTE_MS)
        SavedTextStore.open(this) { texts ->
            if (destroyed || !texts.save(clip.text)) return@open
            openSavedPanel(justSaved = true)
        }
    }

    /** Keeps the lead button's TalkBack action named for what holding it does right now. */
    private fun updateLeadHoldAction() {
        val button = leadButton ?: return
        val label = if (recentCorrection.hasUndo(SystemClock.uptimeMillis())) R.string.correction_more_actions
            else if (pasteClip != null) R.string.saved_text_save_copy
            // Where holding does not proofread (an address bar, a web box without suggestions), there is no hold action.
            else if (fieldWantsSuggestions && foundationAllowed && TypingRules.proofreadsOnHold(currentInputEditorInfo?.inputType ?: 0)) R.string.proofread_action
            else View.NO_ID
        if (label == leadHoldLabel) return
        if (leadHoldAction != View.NO_ID) ViewCompat.removeAccessibilityAction(button, leadHoldAction)
        leadHoldLabel = label
        leadHoldAction = if (label == View.NO_ID) View.NO_ID
            else ViewCompat.addAccessibilityAction(button, getString(label)) { _, _ -> onLeadHeld(); true }
    }

    /** Hold the emoji suggestion to pick a variation ("wave" also offers 🤚 🖐️ ✋ 🫡). */
    private fun showEmojiVariants(index: Int, view: View): Boolean {
        if (index != emojiSlot || emojiOptions.size < 2) return false
        KeyHaptics.perform(keyboardView, android.view.HapticFeedbackConstants.LONG_PRESS)
        emojiPopup.show(
            anchor = view,
            options = emojiOptions,
            describe = { getString(R.string.emoji_insert_description, it) },
            title = getString(R.string.emoji_variants_action),
            onPick = { insertEmoji(it) },
        )
        return true
    }

    /**
     * Swaps the matched word or phrase for [emoji] ("coffee " → "☕ "), keeping a space after it.
     * Delete right after brings the words back.
     */
    private fun insertEmoji(emoji: String): Boolean {
        if (!suggestionsEnabled) return false
        currentInputConnection ?: return false
        if (hasEditorSelection()) {
            return insertFromPanel(emoji).also { if (it) rememberEmoji(emoji) }
        }
        val initial = editorSnapshot() ?: return false
        val prefix = localModel.activePrefix(initial.before)
        val match = emojiSuggestions.match(initial.before, prefix, limit = EMOJI_VARIANTS,
            lookupAs = correctionFor(initial.before, prefix))
        if (match == null || emoji !in match.options) return false
        KeyHaptics.perform(keyboardView, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        val replaced = initial.before.takeLast(match.replaceLength + match.trailing.length)
        if (replaced.length != match.replaceLength + match.trailing.length) return false
        val committed = emoji + match.trailing.ifEmpty { " " }
        val edit = WordEdits.Replacement(replaced.length, 0, committed)
        if (applyWordReplacement(edit, precondition = initial) == null) return false
        lastAutocorrect = AutoCorrection(original = replaced, committed = committed, keep = false)
        autoSpaced = match.trailing.isEmpty()
        revisitTrace(CorrectionDiagnostics.Revisit.RECENT_REPAIR)
        rememberEmoji(emoji)
        scheduleRefresh()
        return true
    }

    private fun acceptCandidate(index: Int) {
        syncLearnedState()
        swipeChoices?.let { choice ->
            if (choice.expires > SystemClock.uptimeMillis() && editorSnapshot() == choice.receipt) {
                choice.words.getOrNull(index)?.let { commitSwipe(choice.base, choice.receipt, it, choice.words, choice.shift, choice.inserted.length, choice.spacing) }
                return
            }
            cancelSwipeWork()
        }
        // Keys still held for a restart were typed before this tap.
        drainPendingKeys()
        // A tap learns and logs, so it must see a Forget learned words made since the strip was drawn.
        syncLearnedState()
        cancelRecentCorrection()
        if (index == expansionSlot) return acceptExpansion()
        val word = slotValues.getOrNull(index).orEmpty()
        if (word.isBlank() || !suggestionsEnabled || personallyBlocked(word)) { scheduleRefresh(); return }
        // Emoji replace the matched words and are never learned as a word.
        if (index == emojiSlot) {
            if (insertEmoji(word) && logAllowed) PredictionLog.tapEmoji(index)
            return
        }
        val ic = currentInputConnection ?: return
        val selected = hasEditorSelection()
        val initial = measureAddedCorrection { editorSnapshot() }
        val context = initial?.before ?: ic.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString()
        if (expectedContext != null && context != expectedContext) {
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            personalWordOwned = false
        }
        val edit = WordEdits.suggestion(
            context, initial?.after ?: ic.getTextAfterCursor(MAX_CONTEXT_CHARS, 0)?.toString(), selected, word,
            trace.revisited, MAX_CONTEXT_CHARS, capsLocked(), rule = language.wordRule,
        ) ?: return
        lastAutocorrect = null
        KeyHaptics.perform(keyboardView, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        if (!selected && initial == null) return
        // Capture provenance before the editor transaction can invalidate it.
        val ownedPrefix = edit.prefix.isEmpty() || (personalWordOwned && trace.hasTouchesFor(edit.prefix))
        val ownPrevious = if (fieldLearnsWords) personalLearning.context(edit.contextBeforeWord).lastOrNull() else null
        if (applyWordReplacement(edit.replacement, precondition = initial) == null) return
        val wordVerified = edit.learningEligible && verifiedWordReceipt != null && ownedPrefix
        val keepContext = personalLearning.context(edit.contextBeforeWord) + listOfNotNull(localModel.lastCompletedWord(context.orEmpty()))
        if (index == literalSlot) keepWord(edit.word, persist = fieldLearnsWords && wordVerified && mayLearnWord(edit.word, keepContext))
        personalLearning.complete(edit.contextBeforeWord, edit.word, edit.word,
            if (index == literalSlot) LearningOrigin.EXPLICIT_KEEP else LearningOrigin.SUGGESTION_ACCEPT,
            fieldLearnsWords, wordVerified)
        personalWordOwned = false
        val previous = localModel.lastCompletedWord(context.orEmpty())
        // A field that doesn't allow learning teaches nothing, not even the in-memory counts later fields rank with.
        // Existing-word and selection edits are not a new typed word or a scored prediction instance.
        // A paste, expansion or cursor move cannot donate a previous word or a partial prefix.
        val canLearn = mayLearnWord(edit.word, listOfNotNull(previous)) && WordPersistence.pair(fieldLearnsWords, wordVerified, previous, ownPrevious)
        if (canLearn) {
            localModel.accept(previous, edit.word)
            modelMutated = true
            if (!logAllowed) snapshotClean = false
        }
        if (logAllowed && canLearn) {
            // The literal slot and its correction echo the editor's prefix, which only counts as typed here when every letter was touched.
            val echoesPrefix = index == literalSlot || (literalSlot == 0 && index == 1)
            val verified = !echoesPrefix || trace.hasTouchesFor(edit.prefix)
            PredictionLog.tap(TapEvent(edit.contextBeforeWord, edit.prefix, index, edit.word, tapSource(index, word), previous, stripRevision, verified))
        }
        if (edit.learningEligible) trace.finish() else revisitTrace(CorrectionDiagnostics.Revisit.TAP_INELIGIBLE)
        autoSpaced = edit.replacement.autoSpaced
        if (shiftState != KeyboardView.ShiftState.LOCKED) setShift(KeyboardView.ShiftState.OFF)
        updateShiftFromEditor()
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    /** Shows immediate local candidates, then replaces them after a pause if an on-device model is ready. */
    private fun refreshCandidates() {
        // Hidden: a late model answer, clip change or dictionary load must not start more model work. Showing refreshes.
        // isInputViewShown backs up viewShown, so a show that skipped onStartInputView can't freeze the strip.
        if (candidateViews.isEmpty() || (!viewShown && !isInputViewShown)) return
        // An open panel has the strip for its header, and its inserts are pastes; closing it refreshes.
        if (panel != null) return cancelFoundationRequest()
        swipeChoices?.let { choice ->
            if (choice.expires > SystemClock.uptimeMillis() && editorSnapshot() == choice.receipt) {
                cancelFoundationRequest()
                expansion = null; expansionSlot = -1; emojiSlot = -1; pasteClip = null
                setSlots(choice.words, literal = -1)
                candidateViews.forEachIndexed { i, view -> choice.words.getOrNull(i)?.let {
                    view.contentDescription = getString(R.string.swipe_alternative, it)
                } }
                updateLead()
                handler.removeCallbacks(refreshRunnable)
                handler.postDelayed(refreshRunnable, choice.expires - SystemClock.uptimeMillis() + 1)
                return
            }
            swipeChoices = null
        }
        if (neuralEnabled && NeuralPredictionWorker.unavailable) disableNeuralPredictions()
        val startedAt = if (logAllowed) SystemClock.elapsedRealtimeNanos() else 0L
        cancelFoundationRequest()
        showingModelWords = false
        // A field without suggestions says why, instead of showing three empty slots.
        privateNotice?.text = when (stripOff) {
            TypingRules.StripOff.PRIVATE -> getString(R.string.private_field)
            TypingRules.StripOff.TERMINAL -> getString(R.string.terminal_field)
            TypingRules.StripOff.FIELD -> getString(R.string.no_suggestions_field)
            TypingRules.StripOff.IPA -> getString(R.string.ipa_field)
            null -> if (blockedLoadFailed()) getString(R.string.blocked_words_keyboard_error) else ""
        }
        privateNotice?.let { view ->
            val recovery = stripOff == null && blockedLoadFailed()
            view.isClickable = recovery
            view.isFocusable = recovery
            if (recovery) view.readFromStart()
        }
        updateSlotVisibility()
        val context = currentContext()
        val prefix = localModel.activePrefix(context)
        // Something freshly copied and nothing typed yet: the left control becomes a paste chip.
        pasteClip = if (prefix.isEmpty()) freshClipText() else null
        pasteClip?.let {
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, it.expiresInMs)
        }
        if (!suggestionsEnabled) {
            // No words here; an address bar or a mail field can still be offered a remembered host.
            showCandidates(emptyList(), context)
            updateLead()
            return
        }
        // In an address bar words are predicted for words. Once the run being typed has anything else in it
        // (a dot, a slash, a colon) it is an address, and only a remembered site is offered for it.
        if (addressBar) {
            // The whole token the caret stands in decides it, so the caret put back into "github.com/x" still
            // counts: a tapped word replaces the word around the caret, and that word would be the site's name.
            val rest = currentInputConnection?.getTextAfterCursor(ADDRESS_TOKEN_CHARS, 0)?.toString().orEmpty().takeWhile { !it.isWhitespace() }
            addressProse = Expansions.proseRun(Expansions.run(context) + rest)
            if (!addressProse) {
                showCandidates(emptyList(), context)
                updateLead()
                return
            }
        }
        syncLearnedState()
        if (!blockedStore.ready) { showCandidates(emptyList(), context); updateLead(); return }
        // Selecting an existing word is a request to edit that word, not to predict
        // from the empty prefix before the selection. These offers are never logged
        // as newly typed outcomes or sent to the neural worker.
        val selectedWord = if (selectionActive) currentInputConnection?.getSelectedText(0)?.toString() else null
        selectionProofreadEligible = foundationAllowed && ProofreadRequest.sentenceSelection(selectedWord)
        updateSlotVisibility()
        if (selectionProofreadEligible) {
            clearLastSlot()
            shownNeuralWords = emptySet()
            shownPersonalWords = emptySet()
            updateLead()
            return
        }
        if (selectedWord != null && selectedWord.length <= MAX_CONTEXT_CHARS &&
            AdaptiveNgramModel.WORD_REGEX.matches(selectedWord)) {
            val fix = correctionFor(context + selectedWord, selectedWord, explicitSelection = true)
            clearLastSlot()
            setSlots(listOf(selectedWord, fix.orEmpty(), ""), literal = 0)
            shownNeuralWords = emptySet()
            shownPersonalWords = emptySet()
            updateLead()
            return
        }
        val predictStart = if (logAllowed) SystemClock.elapsedRealtimeNanos() else 0L
        val scored = localModel.predictScored(context, CANDIDATE_COUNT + 3)
        val predictUs = if (logAllowed) ((SystemClock.elapsedRealtimeNanos() - predictStart) / 1_000).toInt() else 0
        val ranked = scored.map { it.first }
        val neuralContext = if (canPredictNeural()) currentNeuralContext() else null
        val neural = neuralContext?.let { neuralState.answer(it) }
        val ownContext = if (fieldLearnsWords) personalLearning.context(context.dropLast(prefix.length)) else emptyList()
        val followers = if (ownContext.isNotEmpty()) personalStore.followers(ownContext, NeuralRanking.PERSONAL_POLICY.maxContext) else emptyList()
        val learned = if (ownContext.isNotEmpty()) localModel.learnedFollowers(ownContext.last()) else emptyList()
        val blocked = blockedStore.words + if (profanityAllowed) emptySet() else profanityWords
        val shared = fieldVocabulary.candidates(prefix, localModel.lastCompletedWord(context)).map { it.first }
        val correction = correctionFor(context, prefix)
        if (correction != null) {
            // Typed word on the left (in quotes, tap to keep it), the correction in the middle, and an
            // emoji for the corrected word ("cofee" → ☕) in the last slot when one fits.
            val neuralChoices = if (neural != null || followers.isNotEmpty() || learned.any { it.second >= 2 }) {
                val hasEmoji = expansionFor(context, prefix) != null ||
                    (emojiEnabled && !addressBar && emojiSuggestions.match(context, prefix, limit = EMOJI_VARIANTS, lookupAs = correction) != null)
                NeuralRanking.rankWithSources(
                    neural.orEmpty().filter { !it.equals(correction, ignoreCase = true) },
                    ranked.filter { !it.equals(correction, ignoreCase = true) },
                    learned.filter { !it.first.equals(correction, ignoreCase = true) }, prefix, blocked, if (hasEmoji) 0 else 1,
                    added = localModel.addedWords(prefix).filter { !it.equals(correction, ignoreCase = true) },
                    shared = shared.filter { !it.equals(correction, ignoreCase = true) },
                    followers = followers.filter { !it.word.equals(correction, ignoreCase = true) }, language = language,
                )
            } else null
            val others = (neuralChoices?.map { it.word } ?: ranked.filter {
                !it.equals(correction, ignoreCase = true) && (profanityAllowed || it !in profanityWords)
            }).filter { !british(it).equals(correction, ignoreCase = true) }
            val slots = listOf(prefix, correction, others.firstOrNull()?.let { displayCandidate(it, prefix, false) }.orEmpty())
            setSlots(withEmoji(slots, context, prefix, lookupAs = correction), literal = 0)
            shownNeuralWords = neuralChoices.orEmpty().filter { it.neural }.mapTo(HashSet()) { it.word }
            shownPersonalWords = neuralChoices.orEmpty().filter { !it.neural && (it.personalSource != null ||
                learned.any { pair -> pair.first == it.word && pair.second >= 2 } || localModel.isAddedWord(it.word)) }.mapTo(HashSet()) { it.word }
            logOffer(context, prefix, scored, branch = 'C', cached = emptyList(), predictUs = predictUs, startedAt = startedAt)
            updateLead()
            neuralContext?.let(::requestNeuralPrediction)
            return
        }
        // Cached model words for this context show instantly; the model itself never blocks typing.
        val cached = if (!fieldWantsSuggestions || !foundationAllowed || (neuralEnabled && manualFoundationContext != context.dropLast(prefix.length))) emptyList()
            else activeModelCache.lookup(context, prefix).orEmpty().filterNot(::personallyBlocked)
        val neuralChoices = if ((neural != null || followers.isNotEmpty() || learned.any { it.second >= 2 }) && cached.isEmpty()) {
            val hasEmoji = expansionFor(context, prefix) != null ||
                (emojiEnabled && !addressBar && emojiSuggestions.match(context, prefix, limit = EMOJI_VARIANTS) != null)
            NeuralRanking.rankWithSources(neural.orEmpty(), ranked, learned, prefix, blocked, if (hasEmoji) 2 else CANDIDATE_COUNT,
                added = localModel.addedWords(prefix), shared = shared, followers = followers, language = language)
        } else null
        val merged = neuralChoices?.map { it.word } ?: mergeCandidates(cached, ranked, CANDIDATE_COUNT + 3)
        if (merged.isEmpty() && prefix.isNotEmpty() && mode == KeyboardLayout.Mode.LETTERS) {
            // Nothing completes the letters and nothing corrects them: show the typed word, so a tap keeps and learns it.
            setSlots(withEmoji(listOf(prefix, "", ""), context, prefix), literal = 0)
        } else {
            showCandidates(merged, context)
        }
        shownNeuralWords = neuralChoices.orEmpty().filter { it.neural }.mapTo(HashSet()) { it.word }
        shownPersonalWords = neuralChoices.orEmpty().filter { !it.neural && (it.personalSource != null ||
                learned.any { pair -> pair.first == it.word && pair.second >= 2 } || localModel.isAddedWord(it.word)) }.mapTo(HashSet()) { it.word }
        showingModelWords = cached.any { it in slotValues.map(String::lowercase) }
        logOffer(context, prefix, scored, branch = 'N', cached = cached, predictUs = predictUs, startedAt = startedAt)
        updateLead()
        neuralContext?.let(::requestNeuralPrediction)
        if (!modelOnTap && !neuralEnabled && fieldWantsSuggestions && foundationAllowed) {
            if (!activeModelCache.hasEntryFor(context, prefix)) scheduleFoundationPrediction(context, prefix)
            else scheduleLookahead(context, prefix)
        }
    }

    /**
     * Stays one word ahead: when this spot is already answered, generate for the spot after the most
     * likely next word, so its suggestions are cached by the time that word is typed or tapped.
     */
    private fun scheduleLookahead(context: String, prefix: String) {
        if (!fieldWantsSuggestions || !foundationAllowed) return
        val contextBeforeWord = context.dropLast(prefix.length)
        // The likeliest next words that match what's typed, skipping any spot already answered.
        val ahead = activeModelCache.lookup(contextBeforeWord, "").orEmpty()
            .filter { prefix.isEmpty() || it.startsWith(prefix.lowercase()) }
            .filter { profanityAllowed || it !in profanityWords }
            .take(LOOKAHEAD_CANDIDATES)
            .map { "$contextBeforeWord$it " }
            .firstOrNull { ModelSuggestionCache.keysFor(it).isNotEmpty() && !activeModelCache.hasEntryFor(it, "") }
            ?: return
        pendingFoundationRequest = Runnable { requestFoundationPrediction(ahead, lookahead = true) }
            .also { handler.postDelayed(it, modelGate.idleDelayMs()) }
    }

    /** Clipboard if there's a fresh clip; otherwise predict, generating (pulsing) or regenerate. */
    private fun updateLead() {
        val button = leadButton ?: return
        updateLeadHoldAction()
        val (icon, description) = when {
            panel != null -> null to null
            // The sparkle asks for words and the paste chip pastes: neither belongs beside a row of tools, whose
            // Clipboard would sit right next to a second paste. The row gets the room instead.
            toolRow?.visibility == View.VISIBLE -> null to null
            recentCorrection.hasUndo(SystemClock.uptimeMillis()) && !proofreading && proposal == null -> R.drawable.ic_regenerate to R.string.correction_undo
            pasteClip != null -> R.drawable.ic_paste to pasteDescription()
            !sparkleShown() -> null to null
            modelRunning -> R.drawable.ic_generating to R.string.model_generating
            showingModelWords -> R.drawable.ic_regenerate to R.string.model_showing
            neuralEnabled && neuralReady && shownNeuralWords.isNotEmpty() -> R.drawable.ic_predict to R.string.model_trigger_chime
            else -> R.drawable.ic_predict to R.string.model_trigger
        }
        if (icon == null || description == null) {
            button.visibility = View.GONE
            setLeadPulse(false)
            return
        }
        button.visibility = View.VISIBLE
        button.setImageResource(icon)
        button.contentDescription = getString(description)
        setLeadPulse(icon == R.drawable.ic_generating)
    }

    /** A slow fade while generating, so progress reads without a spinner. */
    private fun setLeadPulse(on: Boolean) {
        val button = leadButton ?: return
        if (on) {
            if (leadPulse?.isRunning == true) return
            leadPulse = android.animation.ObjectAnimator.ofFloat(button, View.ALPHA, 1f, 0.35f).apply {
                duration = 700
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else {
            leadPulse?.cancel()
            leadPulse = null
            button.alpha = 1f
        }
    }

    private fun setModelRunning(running: Boolean) {
        modelRunning = running
        updateLead()
    }

    /** A tap on the sparkle may ask for words here: the strip offers words, and an address bar's token is a word. */
    private fun modelWordsOnTap(): Boolean = suggestionsEnabled && foundationAllowed &&
        TypingRules.offersModelWordsOnTap(currentInputEditorInfo?.inputType ?: 0) && !(addressBar && !addressProse)

    /** The sparkle is drawn: a tap may ask here, the person hasn't hidden it, and a model is set up to answer. */
    private fun sparkleShown(): Boolean = modelWordsOnTap() && SparkleRules.shows(sparkleWanted, modelSetUp)

    /**
     * Reads the sparkle's switch and the last answer about the models, and with [recheck] asks again when that
     * answer is over a minute old. The question only peeks (Nano's feature status, Gemma's files, LocalLauncher's
     * copy): it never loads a model, and the F-Droid build, which has none, never asks.
     */
    private fun readSparkle(recheck: Boolean) {
        sparkleWanted = KeyboardSettings.sparkleButton(this)
        modelSetUp = BuildConfig.MODEL_LAB || KeyboardSettings.modelSetUp(this)
        updateLead()
        if (!recheck || !sparkleWanted || !(BuildConfig.INCLUDES_NANO || BuildConfig.INCLUDES_GEMMA)) return
        val now = SystemClock.uptimeMillis()
        if (modelSetUpJob?.isActive == true || !SparkleRules.due(modelSetUpCheckedAt, now)) return
        modelSetUpCheckedAt = now
        modelSetUpJob = uiScope.launch {
            val setUp = runCatching {
                withContext(Dispatchers.Default) {
                    val nano = if (BuildConfig.INCLUDES_NANO) FoundationModelProviderFactory.systemDownloader(this@LocalTypeInputMethodService).status() else null
                    val installer = FoundationModelProviderFactory.installer(this@LocalTypeInputMethodService)
                    val installed = installer.installedModel() != null
                    SparkleRules.modelSetUp(nano, installed, borrowed = !installed && BuildConfig.INCLUDES_GEMMA && installer.sharedFromPeer())
                }
            }.getOrNull() ?: return@launch
            // The preference listener redraws the lead button when the answer changed.
            if (!destroyed) KeyboardSettings.rememberModelSetUp(this@LocalTypeInputMethodService, setUp)
        }
    }

    /** Tap-to-suggest: waits for the model to load if needed, then shows its words for this spot. */
    private fun generateOnTap() {
        if (!sparkleShown() || modelRunning || foundationJob?.isActive == true) return
        KeyHaptics.perform(keyboardView, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        val context = currentContext()
        val prefix = localModel.activePrefix(context)
        val contextBeforeWord = context.dropLast(prefix.length)
        val targetCache = activeModelCache
        val generation = cacheGeneration
        val persistence = personalLearning.modelRequest(contextBeforeWord, fieldLearnsWords)
        // Regenerate: ask for words other than the model words already offered here.
        val avoid = if (showingModelWords) (targetCache.lookup(contextBeforeWord, "").orEmpty() + slotValues.map { it.lowercase() })
            .filter { it.isNotBlank() }.distinct() else emptyList()
        setModelRunning(true)
        foundationJob = uiScope.launch {
            try {
                var status = withContext(Dispatchers.Default) { foundationModel.status() }
                val deadline = SystemClock.uptimeMillis() + MANUAL_LOAD_WAIT_MS
                while (status == FoundationModelStatus.INITIALIZING && SystemClock.uptimeMillis() < deadline) {
                    kotlinx.coroutines.delay(1_000)
                    status = withContext(Dispatchers.Default) { foundationModel.status() }
                }
                if (status != FoundationModelStatus.READY) {
                    val message = when {
                        status == FoundationModelStatus.INITIALIZING -> R.string.model_still_loading
                        neuralEnabled && neuralReady && shownNeuralWords.isNotEmpty() -> R.string.model_unavailable_chime
                        else -> R.string.model_unavailable
                    }
                    showManualModelMessage(getString(message), context, generation)
                    return@launch
                }
                val words = withTimeoutOrNull(ModelRequestGate.REQUEST_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) { foundationModel.predict(contextBeforeWord, ModelSuggestionCache.MAX_WORDS, avoid) }
                }.orEmpty()
                // As for automatic answers: a Forget learned words since the tap drops this one.
                syncLearnedState()
                if (generation != cacheGeneration) return@launch
                if (words.isEmpty()) {
                    showManualModelMessage(getString(R.string.model_no_words), context, generation)
                    return@launch
                }
                if (neuralEnabled) manualFoundationContext = contextBeforeWord
                if (ModelSuggestionCache.keysFor(contextBeforeWord).isEmpty()) {
                    // Sentence starts aren't cached; show this answer once.
                    if (currentContext() == context) {
                        showCandidates(words.map { it.substringBefore(' ') }.distinct().filter { prefix.isEmpty() || it.startsWith(prefix, ignoreCase = true) }, context)
                        showingModelWords = true
                        setModelRunning(false)
                    }
                    return@launch
                }
                storeModelAnswer(targetCache, contextBeforeWord, words, persistence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                diag { "manual generation failed: ${error.javaClass.simpleName}" }
                showManualModelMessage(getString(R.string.model_request_failed), context, generation)
            } finally {
                if (modelRunning) {
                    setModelRunning(false)
                    scheduleRefresh()
                }
            }
        }
    }

    /** A manual request may report failure only in its unchanged field, without replacing proofreading. */
    private fun showManualModelMessage(message: String, context: String, generation: Int) {
        syncLearnedState()
        if (destroyed || !viewShown || panel != null || proposalRow?.visibility == View.VISIBLE ||
            generation != cacheGeneration || currentContext() != context) return
        showProposalMessage(message, autoDismissMs = 2_500)
    }

    private fun clipStamp(): Long = clipboard?.primaryClipDescription?.timestamp ?: -1L

    /** [mayKeep] is false for a copy marked sensitive or made in a password or incognito field, which holding the chip won't save. */
    private data class FreshClip(val text: String, val hidePreview: Boolean, val expiresInMs: Long, val mayKeep: Boolean)

    /**
     * Whether the clip with [stamp] (its ClipDescription timestamp) was copied while a password
     * field, or one that doesn't allow learning such as an incognito tab, was in use.
     */
    private data class CopyOrigin(val stamp: Long, val privateField: Boolean)

    /**
     * The clipboard changed. Keeps the new copy in [recentCopies] when [RecentCopies.keeps] allows,
     * and notes whether it came from a password or no-learning field, so holding the paste chip
     * won't save it either. The field that counts is the one the keyboard last opened in as well as
     * the one focused now: a password manager that doesn't flag what it copies is usually used
     * straight after a password field. Reading the clip shows no "pasted" notice, because Android
     * exempts the default keyboard.
     */
    private fun recordCopy() {
        val manager = clipboard ?: return
        val description = manager.primaryClipDescription
        val isText = description != null && (description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_HTML))
        val text = if (isText) runCatching { manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() else null
        copyOrigin = null
        if (description == null || (isText && text.isNullOrEmpty())) {
            // Emptied: by a password manager after a while, by Android after an hour, or unreadable while locked.
            recentCopies.cleared()
        } else {
            val field = currentInputEditorInfo?.takeIf { currentInputStarted }
            val passwordField = inputIsSensitive || (field != null && TypingRules.isSensitive(field.inputType))
            val privateField = passwordField || !learningAllowed || (field != null && !TypingRules.allowsLearning(field.imeOptions, field.inputType))
            val flagged = description.extras?.getBoolean(CLIP_EXTRA_IS_SENSITIVE, false) == true
            copyOrigin = CopyOrigin(description.timestamp, privateField)
            if (text != null && RecentCopies.keeps(text, flagged, privateField)) {
                recentCopies.copied(text, SystemClock.elapsedRealtime())
            } else {
                recentCopies.skipped()
            }
        }
        scheduleCopyExpiry()
        renderSavedPanel()
    }

    /** Drops each recent copy when its hour is up, so none stays in memory longer, and redraws an open Saved text panel. */
    private val expireCopies = Runnable {
        scheduleCopyExpiry()
        renderSavedPanel()
    }

    private fun scheduleCopyExpiry() {
        handler.removeCallbacks(expireCopies)
        val now = SystemClock.elapsedRealtime()
        recentCopies.prune(now)
        // The handler's clock stops in deep sleep and the copies' doesn't, so a late run still drops every copy that's due.
        recentCopies.nextExpiryIn(now)?.let { handler.postDelayed(expireCopies, it) }
    }

    /** Text copied in the last couple of minutes and not pasted yet. */
    private fun freshClipText(fresh: Boolean = true): FreshClip? {
        if (inputIsSensitive) return null
        val manager = clipboard ?: return null
        val description = manager.primaryClipDescription ?: return null
        if (!description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_HTML)
        ) return null
        val stamp = clipStamp()
        if (fresh && stamp == pastedClipStamp) return null
        val age = System.currentTimeMillis() - description.timestamp
        // The paste chip offers only a fresh copy; a paste the person asks for takes whatever the clipboard holds.
        if (fresh && age > CLIP_FRESH_MS) return null
        val text = runCatching { manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_PASTE_CHARS } ?: return null
        // Password managers flag copied secrets; never show them. Incognito fields get the chip without a preview.
        val sensitiveClip = description.extras?.getBoolean(CLIP_EXTRA_IS_SENSITIVE, false) == true
        // Copied before this keyboard process started: only the flag is known.
        val fromPrivateField = copyOrigin?.let { it.stamp == stamp && it.privateField } == true
        return FreshClip(
            text, hidePreview = sensitiveClip || !learningAllowed, expiresInMs = (CLIP_FRESH_MS - age).coerceAtLeast(1_000L),
            mayKeep = !sensitiveClip && !fromPrivateField,
        )
    }

    /**
     * Asks the model once per word boundary: the request uses the text before the current word, so
     * its answer keeps serving every prefix typed afterwards straight from the cache.
     */
    private fun scheduleFoundationPrediction(context: String, prefix: String) {
        if (!fieldWantsSuggestions) return
        val contextBeforeWord = context.dropLast(prefix.length)
        // Nothing to key a cache entry on (field start, sentence start, after a number): don't ask.
        if (ModelSuggestionCache.keysFor(contextBeforeWord).isEmpty()) return
        pendingFoundationRequest = Runnable { requestFoundationPrediction(contextBeforeWord) }
            .also { handler.postDelayed(it, modelGate.idleDelayMs()) }
    }

    private fun requestFoundationPrediction(contextBeforeWord: String, lookahead: Boolean = false) {
        // Nothing automatic while hidden, or while a proofread holds the model: the request would only queue
        // behind it and time out. Every way a proofread ends refreshes the strip, which asks again. Nothing
        // while a panel is open either, since the strip isn't showing suggestions; closing it refreshes.
        if (!fieldWantsSuggestions || !foundationAllowed || !viewShown || panel != null || proofreading || !modelGate.canRequest() || foundationJob?.isActive == true) {
            return
        }
        // Automatic work only: taps and proofreading don't go through here.
        modelBudget.blockReason(powerState(), lookahead)?.let { reason ->
            return diag { "automatic model work paused: $reason" }
        }
        val targetCache = activeModelCache
        val generation = cacheGeneration
        val persistence = personalLearning.modelRequest(contextBeforeWord, fieldLearnsWords && !lookahead)
        foundationJob = uiScope.launch {
            val status = modelGate.cachedStatus()
                ?: withContext(Dispatchers.Default) { foundationModel.status() }.also(modelGate::recordStatus)
            // A panel opened while the status was being asked: no retry and no predict until it closes.
            if (panel != null) return@launch
            if (status != FoundationModelStatus.READY) {
                // Still loading: try again shortly, so the first pause after opening isn't lost. Not once hidden,
                // or the retry would poll the model every couple of seconds until the next time it shows.
                if (status == FoundationModelStatus.INITIALIZING && generation == cacheGeneration && viewShown) {
                    pendingFoundationRequest = Runnable { requestFoundationPrediction(contextBeforeWord, lookahead) }
                        .also { handler.postDelayed(it, LOADING_RETRY_MS) }
                }
                return@launch
            }
            val startedAt = modelGate.begin()
            modelBudget.record()
            // Background lookahead stays quiet; only work for the current spot shows "…".
            if (!lookahead) setModelRunning(true)
            val words = try {
                withTimeoutOrNull(ModelRequestGate.REQUEST_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        foundationModel.predict(contextBeforeWord, ModelSuggestionCache.MAX_WORDS)
                    }
                }
            } catch (cancelled: CancellationException) {
                modelGate.fail()
                modelRunning = false
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (!lookahead) setModelRunning(false)
            if (words.isNullOrEmpty()) {
                modelGate.fail()
                return@launch
            }
            modelGate.succeed(startedAt)
            // Forgotten since the request? Writing into the old cache would save its forgotten words back.
            syncLearnedState()
            if (generation != cacheGeneration) return@launch
            storeModelAnswer(targetCache, contextBeforeWord, words, persistence)
            // Still typing in the same context? Refresh so the new words appear under the current prefix.
            scheduleRefresh()
        }
    }

    /**
     * Caches first words for this spot and, from two-word answers ("a coffee"), second words for the
     * spot after each first word, without overwriting an answer that spot already has.
     */
    private fun storeModelAnswer(cache: ModelSuggestionCache, contextBeforeWord: String, phrases: List<String>,
                                 persistence: WordPersistence.Request) {
        val persist = cache === modelCache && persistence.persists(personalLearning.generation)
        val permitted = phrases.filter { phrase ->
            blockedStore.ready && AdaptiveNgramModel.WORD_REGEX.findAll(phrase).none { personallyBlocked(it.value) }
        }
        val mayPersist = persist && AdaptiveNgramModel.WORD_REGEX.findAll(contextBeforeWord).none { personallyBlocked(it.value) }
        val firsts = permitted.map { it.substringBefore(' ') }.distinct()
        cache.put(contextBeforeWord, firsts, mayPersist)
        permitted.filter { ' ' in it }.groupBy({ it.substringBefore(' ') }, { it.substringAfter(' ') }).forEach { (first, seconds) ->
            val ahead = "$contextBeforeWord$first "
            // A hypothetical model word is not a personally completed word.
            if (!cache.hasEntryFor(ahead, "")) cache.put(ahead, seconds.distinct(), persist = false)
        }
    }

    private fun cancelFoundationRequest() {
        // Only the pending timer is cancelled; an in-flight answer is still worth caching.
        pendingFoundationRequest?.let(handler::removeCallbacks)
        pendingFoundationRequest = null
    }

    private fun showCandidates(ranked: List<String>, context: String = "") {
        val prefix = localModel.activePrefix(context)
        // With swear words off, nothing from any source (learned pairs, model, cache) suggests one.
        val allowed = ranked.filter { !personallyBlocked(it) && (profanityAllowed || it.lowercase() !in profanityWords) }
        // Shift may be off after a line break even in a sentence-caps field, so check the text too.
        val capsField = TypingRules.capitalizesSentences(currentInputEditorInfo?.inputType ?: 0, autoCapitalize)
        val sentenceStart = shiftState != KeyboardView.ShiftState.OFF ||
            (capsField && prefix.isEmpty() && localModel.isSentenceStart(context))
        val words = TypingRules.arrangeForStrip(BritishSpelling.distinct(allowed.map(::british)).map { displayCandidate(it, prefix, sentenceStart) }.filterNot(::personallyBlocked))
        setSlots(withEmoji(words, context, prefix), literal = -1)
    }

    private fun displayCandidate(word: String, prefix: String, sentenceStart: Boolean): String {
        // The person's own capitals for a word they've taught ("LocalType", "iPhone") come first.
        spellings().display(word)?.let { return TextRules.applyCase(it, prefix, sentenceStart, capsLocked()) }
        val normalized = AdaptiveNgramModel.normalize(word)
        val forms = setOf(normalized, normalized.removeSuffix("'s").removeSuffix("'"))
        if (forms.none { it in keptWords || localModel.isAddedWord(it) }) british(word).let { gb ->
            if (gb != word) return TextRules.applyCase(gb, prefix, sentenceStart, capsLocked())
        }
        return fieldVocabulary.display(word, prefix, sentenceStart, capsLocked(), forms.any { it in keptWords || localModel.isAddedWord(it) })
    }

    private fun clearLastSlot() {
        emojiSlot = -1
        emojiOptions = emptyList()
        expansionSlot = -1
        expansion = null
    }

    /**
     * Chooses the last slot: an expansion for what is being typed, else a fitting emoji for the current word or
     * phrase, else the word already there. An expansion's own entry stays blank; [setSlots] draws its label.
     */
    private fun withEmoji(slots: List<String>, context: String, prefix: String, lookupAs: String? = null): List<String> {
        clearLastSlot()
        expansionFor(context, prefix)?.let { offer ->
            expansion = offer
            expansionSlot = slots.lastIndex.coerceAtLeast(0)
            expansionsShown++
            return slots.toMutableList().also { it[expansionSlot] = "" }
        }
        // An address bar keeps its three slots for words and sites.
        if (!emojiEnabled || !suggestionsEnabled || addressBar) return slots
        val match = emojiSuggestions.match(context, prefix, limit = EMOJI_VARIANTS, lookupAs = lookupAs) ?: return slots
        val options = match.options
        val emoji = options.firstOrNull() ?: return slots
        if (emoji in slots) return slots
        emojiOptions = options
        emojiSlot = slots.lastIndex.coerceAtLeast(0)
        return slots.toMutableList().also { it[emojiSlot] = emoji }
    }

    private fun setSlots(slots: List<String>, literal: Int) {
        shownNeuralWords = emptySet()
        shownPersonalWords = emptySet()
        val permitted = slots.map { it.takeUnless(::personallyBlocked).orEmpty() }
        slotValues = permitted
        literalSlot = literal
        stripRevision++
        val offer = expansion
        candidateViews.forEachIndexed { index, view ->
            val word = permitted.getOrNull(index).orEmpty()
            val expands = offer != null && index == expansionSlot
            if (expands != (view.tag == true)) {
                // A host or saved text takes the accent colour and still shrinks to fit before being cut.
                view.tag = expands
                view.setTextColor(getColor(if (expands) R.color.kb_accent else R.color.kb_on_key))
            }
            view.ellipsize = when {
                expands && offer!!.kind != Expansions.Kind.DOMAIN -> TextUtils.TruncateAt.END
                index == emojiSlot -> TextUtils.TruncateAt.END
                else -> TextUtils.TruncateAt.MIDDLE
            }
            view.text = if (expands) offer!!.label else if (index == literal && word.isNotEmpty()) "“$word”" else word
            view.isEnabled = word.isNotEmpty() || expands
            view.contentDescription = when {
                expands -> expansionDescription(offer!!)
                word.isEmpty() -> null
                index == emojiSlot && emojiOptions.size > 1 -> getString(R.string.emoji_description_variants, word)
                index == emojiSlot -> getString(R.string.emoji_insert_description, word)
                else -> getString(R.string.suggestion_description, word)
            }
        }
        // A field with no word slots shows a remembered host in their place.
        hostChip?.let { chip ->
            val shown = offer?.takeIf { stripOff != null }
            val label = shown?.label.orEmpty()
            // Every refresh comes through here; a field that never shows a host must not pay for it.
            if (chip.text.toString() != label) {
                chip.text = label
                chip.contentDescription = shown?.let(::expansionDescription)
            }
            if ((chip.visibility == View.VISIBLE) != (shown != null)) updateSlotVisibility()
        }
    }

    /** Takes the expansion out of the strip at once; the next refresh decides afresh. */
    private fun dropExpansion() {
        if (expansion == null) return
        candidateViews.getOrNull(expansionSlot)?.let { view ->
            view.text = ""
            view.isEnabled = false
            view.contentDescription = null
        }
        expansionSlot = -1
        expansion = null
        hostChip?.text = ""
        updateSlotVisibility()
    }

    private fun expansionDescription(offer: Expansions.Offer): String = when (offer.kind) {
        Expansions.Kind.SHORTCUT -> getString(R.string.expansion_saved_text_description, offer.label)
        Expansions.Kind.DOMAIN -> getString(R.string.expansion_host_description, offer.label)
        Expansions.Kind.LINK -> getString(R.string.clean_link_description, offer.host.orEmpty())
        Expansions.Kind.SYMBOL -> getString(R.string.expansion_symbol_description, offer.text, symbolName(offer.text))
    }

    /** A sign's Unicode name in lowercase ("copyright sign"), for TalkBack; the sign itself when Android has none. */
    private fun symbolName(symbol: String): String =
        runCatching { Character.getName(symbol.codePointAt(0)) }.getOrNull()?.lowercase() ?: symbol

    /** A saved text's shortcut, or a word of a contact's name: correction never changes either. */
    private fun isProtectedWord(word: String): Boolean =
        savedTexts?.isCode(word) == true || (contactNames.isNotEmpty() && word.lowercase() in contactNames)

    /**
     * What the last slot can offer for the text before the caret that is not a word: the saved text whose
     * shortcut is the word being typed, else the rest of a remembered host. A saved text needs the word slots.
     * A host is offered wherever one may be remembered, so never in a password or incognito field, and in a
     * field with no word slots only when it is an address bar or a mail field.
     */
    private fun expansionFor(context: String, prefix: String): Expansions.Offer? {
        val offer = expansionBefore(context, prefix) ?: return null
        // Only at the end of what is being typed, or the text would go into the middle of it: no letter or
        // digit after a shortcut's word, and nothing that continues the address after a host. An editor that
        // cannot say what follows gets no offer.
        val after = currentInputConnection?.getTextAfterCursor(2, 0) ?: return null
        return offer.takeIf { when (it.kind) {
            Expansions.Kind.DOMAIN -> Expansions.hostEndsAt(after)
            // [LinkCleaner.atCaret] has already checked what follows the link.
            Expansions.Kind.LINK -> true
            Expansions.Kind.SHORTCUT -> Expansions.wordEndsAt(after)
            // A cue like (c) or -> ends at the caret when what follows doesn't continue it.
            Expansions.Kind.SYMBOL -> Expansions.hostEndsAt(after)
        } }
    }

    private fun expansionBefore(context: String, prefix: String): Expansions.Offer? {
        if (inputIsSensitive || stripOff == TypingRules.StripOff.PRIVATE || stripOff == TypingRules.StripOff.TERMINAL) return null
        // A tap could not act on a selection, so nothing is offered over one.
        if (selectionActive) return null
        // A link standing right before the caret that still carries tracking: pasted from the app's own menu, typed,
        // or there before. Only where words show, since the offer takes the last word slot.
        if (cleanLinks && suggestionsEnabled) linkOffer(context)?.let { return it }
        if (suggestionsEnabled && prefix.isNotEmpty()) Expansions.shortcut(prefix, savedTexts)?.let { return it }
        // A typed cue or a sign's name: (c) or "copyright" offers ©, -> offers →. After saved texts, so a shortcut wins.
        if (suggestionsEnabled && !addressBar) Expansions.symbol(Expansions.run(context), prefix)?.let { return it }
        if (!hostsAllowed) return null
        if (!suggestionsEnabled && hostField == Expansions.Field.TEXT) return null
        // The usual mail providers fill in after an @ until the person's own hosts are remembered.
        val offered = rememberedDomains?.offered.orEmpty()
        return Expansions.domain(Expansions.run(context), offered, hostField, MailDomains.COMMON) {
            prefix.isNotEmpty() && dictionary.completions(prefix.lowercase(), 1).isNotEmpty()
        }
    }

    /**
     * The link the caret stands right after, offered without its tracking, or null. Most text costs no read: only a
     * run before the caret with a scheme in it, or one that fills all [context], can hold a link, and then up to
     * [LinkCleaner.CARET_READ_CHARS] are read, so a long shopping link is seen whole.
     */
    private fun linkOffer(context: String): Expansions.Offer? {
        val run = Expansions.run(context)
        if ("://" !in run && !(run.length == context.length && context.length >= MAX_CONTEXT_CHARS)) return null
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(LinkCleaner.CARET_READ_CHARS, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(2, 0) ?: return null
        val found = LinkCleaner.atCaret(before, after, whole = before.length < LinkCleaner.CARET_READ_CHARS) ?: return null
        return Expansions.link(found, getString(R.string.clean_link))
    }

    /**
     * Space or Enter is about to end the run before the caret. When every character of that run was typed on
     * this keyboard and it is a web or mail address, its host is remembered: the host alone, and only where
     * learning is allowed.
     */
    private fun rememberTypedHost() {
        val domains = rememberedDomains ?: return
        if (!hostsAllowed) return
        // An ordinary word costs no read: only a run with a dot in it can be an address.
        if (!typedRun.mayBeAddress) return
        val before = keyContext ?: rawContext() ?: return
        val run = typedRun.current(before) ?: return
        val host = DomainHosts.host(run, DomainStore.tlds) ?: return
        // The run must end where the address does: text standing right after the caret is more of the same token.
        val after = currentInputConnection?.getTextAfterCursor(1, 0) ?: return
        if (after.isNotEmpty() && !after[0].isWhitespace()) return
        domains.typed(host, today())
        // Counted once: Enter pressed again on a search that keeps its text is not a second typing of it.
        typedRun.reset()
    }

    private fun today(): Long = Math.floorDiv(System.currentTimeMillis(), 86_400_000L)

    /**
     * Takes the expansion on offer: a shortcut's word becomes its saved text, exactly as saved, and a host gets
     * the part not typed yet. Like a paste it teaches nothing and writes no tap to the typing log, and the word
     * it touched is no longer a typed word. Delete right after takes it back.
     */
    private fun acceptExpansion() {
        takeExpansion(expansion ?: return)
    }

    /** [shown], once it is still what the text before the caret offers. The Clean links tool comes here too. */
    private fun takeExpansion(shown: Expansions.Offer) {
        currentInputConnection ?: return
        if (hasEditorSelection()) return
        val link = shown.kind == Expansions.Kind.LINK
        // A cleaned link replaces the whole link, which can be longer than the usual read.
        val limit = if (link) maxOf(MAX_CONTEXT_CHARS, shown.replace) + shown.text.length
            else MAX_CONTEXT_CHARS + shown.text.length.coerceAtMost(SavedTexts.MAX_CHARS)
        val initial = readEditorSnapshot(limit) ?: return
        // The strip was drawn a moment ago: the offer must still be the one for the text there now.
        val context = initial.before.takeLast(MAX_CONTEXT_CHARS)
        val offer = if (link) linkOffer(context) else expansionFor(context, localModel.activePrefix(context))
        if (offer != shown) return scheduleRefresh()
        KeyHaptics.perform(keyboardView, android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        val replaced = initial.before.takeLast(offer.replace)
        if (replaced.length != offer.replace) return
        personalWordOwned = false
        personalLearning.reset(PersonalContextTrace.ResetReason.PASTE)
        lastAutocorrect = null
        val applied = applyWordReplacement(WordEdits.Replacement(offer.replace, 0, offer.text), precondition = initial, readLimit = limit)
        // What went in was not typed: a host taken from the strip is never counted as one typed in full.
        typedRun.reset()
        revisitTrace(CorrectionDiagnostics.Revisit.INSERT)
        autoSpaced = false
        if (applied != null) {
            expansionsTaken++
            lastAutocorrect = AutoCorrection(original = replaced, committed = offer.text, keep = false)
            if (offer.kind == Expansions.Kind.DOMAIN) offer.host?.let { rememberedDomains?.used(it, today()) }
            if (link) {
                linksCleaned++
                // Like a cleaned paste: the strip says so with an Undo, and delete right after takes it back.
                readEditorSnapshot()?.selection?.takeIf { it.collapsed }?.end?.let { caret ->
                    cleanedPaste = CleanedPaste(AutoCorrection(original = replaced, committed = offer.text, keep = false), caret)
                    showLinkCleaned()
                }
            }
        }
        updateShiftFromEditor()
        scheduleRefresh()
    }

    /**
     * Learns the word just finished by a space or punctuation, when this field allows learning.
     * [typedVerified] says whether every letter of it was tapped here, read by the caller before
     * the trace moved on; the log accepts the typed word only then. [touches] captures the lifts
     * before punctuation clears the trace; only an unchanged, learned word calibrates letter keys.
     */
    private fun learnJustCompletedWord(edit: WordEdits.Separator, typedVerified: Boolean, touches: List<Touch?>?,
                                       sentenceContext: List<String>? = null) {
        syncLearnedState()
        completedPersonal = null
        boundaryPersonalContext = emptyList()
        if (!fieldLearnsWords || !edit.learningEligible) return
        val before = edit.beforeSeparator
        if (!mayLearnWord(localModel.activePrefix(before), listOfNotNull(localModel.lastCompletedWord(before)))) {
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            personalWordOwned = false
            return
        }
        val beforeWord = before.dropLast(localModel.activePrefix(before).length)
        boundaryPersonalContext = personalLearning.context(beforeWord)
        val previous = localModel.lastCompletedWord(before)
        val ownPrevious = (sentenceContext ?: boundaryPersonalContext).lastOrNull()
        val wordVerified = typedVerified && (personalWordOwned || sentenceContext != null) && edit.precondition != null &&
            (verifiedWordReceipt != null || edit.replacement.text.isEmpty())
        val afterForeignContext = !WordPersistence.pair(fieldLearnsWords, wordVerified, previous, ownPrevious)
        val isKept: (String) -> Boolean = { it in keptWords || it in lexicon }
        val learned = !afterForeignContext && localModel.learnTypedWord(before, persist = true, isKept = isKept)
        val committed = localModel.activePrefix(before)
        // Even when its pair is blocked, a verified completed word starts the own tail for the next word.
        if (wordVerified && !edit.corrected && localModel.learnable(before, isKept)) {
            val common = dictionary.score(AdaptiveNgramModel.normalize(edit.prefix)) >= COMMON_WORD_SCORE
            spellings().change(edit.prefix, WordSpellings.change(edit.prefix, localModel.isSentenceStart(beforeWord), capsLockUsedForWord, common), localModel::isAddedWord)
        }
        if (wordVerified && localModel.learnable(before, isKept)) {
            completedPersonal = if (sentenceContext != null)
                personalLearning.repair(sentenceContext, committed, true,
                    if (edit.corrected) LearningOrigin.VERIFIED_REPAIR else LearningOrigin.TYPED)
            else personalLearning.complete(
                beforeWord, edit.prefix, committed,
                if (edit.corrected) LearningOrigin.VERIFIED_REPAIR else LearningOrigin.TYPED,
                allowed = true, verified = true,
                sentenceEnded = edit.replacement.text.any { it in ".!?\n" },
            )
            if (edit.corrected) lastAutocorrect = lastAutocorrect?.copy(personal = completedPersonal)
        } else if (edit.prefix.isNotEmpty()) personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        personalWordOwned = false
        if (learned) {
            if (!edit.corrected && touchOffsets.record(edit.prefix, touches)) touchOffsetStore.save(touchOffsets)
            corrector.forgetRecentAnswers()
            modelMutated = true
        }
        // A camel-case word or #tag is learned, but the typing log keeps to the words it always recorded.
        if (!logAllowed || edit.casedToken) {
            if (learned) snapshotClean = false
            return
        }
        PredictionLog.typed(TypedEvent(
            contextBeforeSeparator = before,
            typedRaw = edit.prefix.takeIf { edit.corrected },
            committed = localModel.activePrefix(before),
            sep = edit.replacement.text.lastOrNull() ?: '\n',
            learned = learned,
            typedVerified = typedVerified,
            prev = if (afterForeignContext) null else previous,
        ))
    }

    /**
     * The ranker as this service always builds it: learned pairs from the store, with the dictionary
     * and the corpus prior once they've loaded. Every rebuild goes through here so none drops the prior.
     */
    /** English starts with its seed phrases and sentence starters; another language starts from nothing but its own typing. */
    private fun buildLocalModel() = if (language.english) AdaptiveNgramModel(learningStore, dictionary = dictionary, prior = corpusPrior, vocabulary = fieldVocabulary, excluded = ::personallyBlocked)
        else AdaptiveNgramModel(learningStore, seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(),
            prior = corpusPrior, priorWeights = AdaptiveNgramModel.PriorWeights(weight = language.priorWeight), vocabulary = CurrentVocabulary.EMPTY,
            openingMarks = language.openingMarks, oneLetterWords = language.oneLetterWords, excluded = ::personallyBlocked,
            wordRule = language.wordRule)

    /** Either background load can finish first; the waiting field opens only after both publish. */
    private fun openPendingLoggedField() {
        if (blockedStore.ready && blockedStore.words.isNotEmpty()) { loggedFieldGate.finish(); return }
        loggedFieldGate.published(dictionaryReady, learningStore.ready && blockedStore.ready)?.let { restart ->
            logAllowed = true
            snapshotClean = false
            openLoggedField(restart)
        }
    }

    // region Typing log

    /** Settings cleared or removed learned words since this model was built: rebuild it and the model cache. */
    private fun reloadLearnedIfChanged(): Boolean {
        val generation = KeyboardSettings.learnedGeneration(this)
        if (generation == learnedGeneration) return false
        learnedGeneration = generation
        personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        boundaryPersonalContext = emptyList()
        completedPersonal = null
        cancelRecentCorrection()
        // Forget learned words clears the offsets' file with the pairs; a forgotten single pair leaves offsets.
        touchOffsetStore.load(touchOffsets)
        localModel = buildLocalModel()
        modelCache = ModelSuggestionCache(PreferenceModelCacheStore(this))
        cacheGeneration++
        resetNeuralSession()
        manualFoundationContext = null
        keptWords.clear()
        fieldKeptWords.clear()
        corrector.forgetRecentAnswers()
        // Forget learned words empties the recent emoji too; forgetting one pair leaves them, and this rereads either way.
        // An open emoji panel keeps showing its Recent row until it next opens.
        recentEmoji.reload()
        modelMutated = false
        learnedBaselineImported = false
        snapshotClean = false
        logRebuilt = true
        return true
    }

    /**
     * Forget learned words or Clear log in settings while this field was open (the settings Try it field):
     * rebuild and reopen the log first, so no offer, correction, learned word or log row after it comes
     * from the forgotten model or lands in the new file's tables.
     */
    private fun syncLearnedState() {
        val reloaded = reloadLearnedIfChanged()
        if (!blockedStore.ready || blockedStore.words.isNotEmpty()) {
            if (logAllowed) PredictionLog.endField()
            logAllowed = false
            // Collection resumes only in a fresh eligible field after every exclusion has been removed.
            if (blockedStore.ready) loggedFieldGate.finish()
        }
        if (logAllowed && (reloaded || PredictionLog.logGeneration != logGenerationSeen)) {
            // A single forgotten pair keeps the file; end this field there so the new tables don't land inside it.
            PredictionLog.endField()
            openLoggedField(restart = false)
        }
    }

    /** Takes a fresh table snapshot when the log needs one, then opens this field in the log. */
    private fun openLoggedField(restart: Boolean) {
        if (!blockedStore.ready || blockedStore.words.isNotEmpty()) { logAllowed = false; return }
        // Covers the dictionary-publish path too, which opens a field without passing onStartInputView.
        reloadLearnedIfChanged()
        if (PredictionLog.takeHiddenMutation()) snapshotClean = false
        val neuralDigest = if (neuralEnabled) NeuralAsset.SHA256 else null
        val correctionMode = Triple(correctionLogPolicy, autocorrectEnabled, KeyboardSettings.webAutocorrect(this))
        if (!snapshotClean || PredictionLog.needsSnapshot() || loggedNeuralDigest != neuralDigest || loggedCorrectionMode != correctionMode) {
            val reason = when {
                modelMutated -> PredictionLogCodec.SNAP_UNLOGGED
                logRebuilt -> PredictionLogCodec.SNAP_REBUILD
                else -> PredictionLogCodec.SNAP_PROCESS
            }
            if (modelMutated || learnedBaselineImported) {
                // Only a freshly built model is written: counts from unlogged taps and typing never reach the file.
                localModel = buildLocalModel()
                modelMutated = false
                learnedBaselineImported = false
            }
            // The swear list loads with the dictionary or not at all, so the other size is inferred from it.
            val swears = profanityWords.size
            val withProfanity = if (publishedWithProfanity == true) dictionary.size else dictionary.size + swears
            // The header names the prior this model ranks with, so the replay rebuilds the same ranker from the tables.
            PredictionLog.snapshot(
                this, reason, localModel.tables(), (withProfanity - swears).coerceAtLeast(0), withProfanity,
                localModel.prior?.sha256, localModel.priorWeights,
                neuralSha256 = neuralDigest,
                correctionPolicy = correctionMode.first, autocorrect = correctionMode.second, webAutocorrect = correctionMode.third,
                vocabularyHeader = fieldVocabulary.header(),
                personalLearning = WordPersistence.LOG_POLICY_ID,
            )
            loggedNeuralDigest = neuralDigest
            loggedCorrectionMode = correctionMode
            snapshotClean = true
            logRebuilt = false
        }
        logGenerationSeen = PredictionLog.logGeneration
        // 0 marks logs from earlier model-free builds; this build always writes 1 or 2.
        val model = if (modelOnTap) 2 else 1
        PredictionLog.beginField(restart, autocorrectEnabled && fieldAllowsAutocorrect, profanityAllowed, model, emojiEnabled,
            neuralSha256 = neuralDigest, correctionPolicy = correctionMode.first, webAutocorrect = correctionMode.third,
            configuredAutocorrect = correctionMode.second, vocabularyHeader = fieldVocabulary.header(),
            personalLearning = WordPersistence.LOG_POLICY_ID)
    }

    /** Records the strip just shown: one immutable event, posted to the log's worker. */
    private fun logOffer(context: String, prefix: String, scored: List<Pair<String, Int>>, branch: Char, cached: List<String>, predictUs: Int, startedAt: Long) {
        if (!logAllowed) return
        lastLoggedRanked = scored
        lastLoggedCached = cached
        val src3 = String(CharArray(CANDIDATE_COUNT) { slotSource(it, scored, cached, branch) })
        PredictionLog.offer(OfferEvent(
            context = context,
            prefix = prefix,
            touchCount = trace.touchCount,
            revisited = trace.revisited,
            mode = if (mode == KeyboardLayout.Mode.LETTERS) 'L' else 'S',
            branch = branch,
            // The proofread row or a password manager's chips had the slots: the words were ranked, not seen.
            shown = proposalRow?.visibility != View.VISIBLE && inlineRow?.visibility != View.VISIBLE,
            ranked = scored,
            slots = slotValues,
            src3 = src3,
            modelCount = cached.size.coerceAtMost(CANDIDATE_COUNT + 3),
            dict = dictionary.size,
            us = predictUs,
            rus = ((SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000).toInt(),
            stripRevision = stripRevision,
        ))
    }

    /**
     * N neural, P reserved personal outside the ranker shortlist, R ranker, M cache, C fix, L literal, E emoji,
     * S an expansion (a saved text or a remembered host, whose text the log never holds), B blank.
     */
    private fun slotSource(index: Int, scored: List<Pair<String, Int>>, cached: List<String>, branch: Char): Char {
        val word = slotValues.getOrNull(index).orEmpty()
        val normalized = AdaptiveNgramModel.normalize(word)
        return when {
            index == expansionSlot -> 'S'
            word.isEmpty() -> 'B'
            index == emojiSlot -> 'E'
            index == literalSlot -> 'L'
            branch == 'C' && index == 1 -> 'C'
            normalized in shownNeuralWords -> 'N'
            normalized in shownPersonalWords -> 'P'
            scored.any { it.first == normalized } -> 'R'
            cached.any { AdaptiveNgramModel.normalize(it) == normalized } -> 'M'
            else -> 'X'
        }
    }

    /** Where a tapped word came from, from live state, with X for a strip the log never saw. */
    private fun tapSource(index: Int, word: String): Char {
        val normalized = AdaptiveNgramModel.normalize(word)
        return when {
            index == emojiSlot -> 'E'
            index == literalSlot -> 'L'
            literalSlot == 0 && index == 1 -> 'C'
            normalized in shownNeuralWords -> 'N'
            normalized in shownPersonalWords -> 'P'
            lastLoggedRanked.any { it.first == normalized } -> 'R'
            lastLoggedCached.any { AdaptiveNgramModel.normalize(it) == normalized } -> 'M'
            else -> 'X'
        }
    }

    // endregion

    /**
     * A cased correction for the word being typed, or null when it should be left alone. With
     * [automaticOnly], only fixes confident enough to apply on space are returned.
     */
    private fun correctionFor(context: String, prefix: String, automaticOnly: Boolean = false, explicitSelection: Boolean = false): String? {
        if (!blockedStore.ready || !autocorrectEnabled || !suggestionsEnabled || (!automaticOnly && mode != KeyboardLayout.Mode.LETTERS)) return null
        if (DomainTyping.protected(context, currentInputConnection?.getTextAfterCursor(MAX_CONTEXT_CHARS, 0)?.toString())) return null
        if (prefix.length < 2 || prefix.lowercase() in keptWords || isProtectedWord(prefix)) return null
        // A word the person came back to edit is theirs: still suggest, never replace on a boundary.
        // Ordinary web boxes follow the explicit web-autocorrect setting.
        if (automaticOnly && (trace.revisited || !trace.hasTouchesFor(prefix) || !fieldAllowsAutocorrect || compatibilityEditorRestarted)) return null
        if (!localModel.isAddedWord(prefix)) fieldVocabulary.capitalization(prefix)?.takeUnless(::personallyBlocked)?.let { return it }
        val touches = trace.touchesFor(prefix)
        val previous = localModel.lastCompletedWord(context)
        val sentenceStart = localModel.isSentenceStart(context)
        val afterComma = context.dropLast(prefix.length).trimEnd().endsWith(',')
        // In-progress prefixes retain completions; explicit selections are completed spelling requests.
        val completions = !automaticOnly && !explicitSelection &&
            (dictionary.completions(prefix.lowercase(), 1).isNotEmpty() || fieldVocabulary.candidates(prefix, previous).isNotEmpty())
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val fix = if (automaticOnly) corrector.correct(prefix, touches, previous, sentenceStart, afterComma)
        else corrector.suggestForStrip(prefix, touches, previous, sentenceStart, afterComma, explicitSelection, completions)
        if (automaticOnly) correctionDiagnostics.count(if (fix == null)
            CorrectionDiagnostics.Reason.NO_AUTOMATIC_CANDIDATE else CorrectionDiagnostics.Reason.AUTOMATIC_CANDIDATE)
        diag { "correction lookup len=${prefix.length} us=${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000}" }
        return fix?.let { TypingRules.applyCase(british(it), prefix, sentenceStart = false, capsLock = capsLocked()) }?.takeUnless(::personallyBlocked)
    }

    /** Whether English's Spelling: British choice applies to this field's words. */
    private fun britishOn(): Boolean = britishSpelling && language.english

    /** An explicit currency choice overrides the spelling-based default. */
    private fun poundFirst(): Boolean = currency.poundFirst(britishOn())

    /**
     * [word] as British spelling would offer it, or unchanged: with the choice off, for a word the person gave
     * their own capitals to, kept or added, and for anything not in the list.
     */
    private fun british(word: String): String {
        if (!britishOn() || spellings().display(word) != null) return word
        val normalized = AdaptiveNgramModel.normalize(word)
        if (normalized in keptWords || localModel.isAddedWord(normalized)) return word
        return britishWords.of(word)
    }

    /** A separator uses the text on both sides, never the active key layer, to decide whether a word ended. */
    private fun separatorEdit(separator: String): WordEdits.Separator {
        val initial = editorSnapshot()
        val context = initial?.before ?: currentContext()
        if (expectedContext != null && context != expectedContext) {
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            personalWordOwned = false
        }
        val typed = initial != null && trace.hasTouchesFor(localModel.activePrefix(context))
        correctionDiagnostics.count(when {
            !autocorrectEnabled || !fieldAllowsAutocorrect || !suggestionsEnabled -> CorrectionDiagnostics.Reason.FIELD
            initial == null -> CorrectionDiagnostics.Reason.MISSING_READ
            trace.revisited || !initial.selection.collapsed -> CorrectionDiagnostics.Reason.REVISITED
            compatibilityEditorRestarted -> CorrectionDiagnostics.Reason.RESTART
            !typed -> CorrectionDiagnostics.Reason.UNTRACED
            else -> CorrectionDiagnostics.Reason.ELIGIBLE
        })
        return WordEdits.separator(
            context, initial?.after ?: currentInputConnection?.getTextAfterCursor(MAX_CONTEXT_CHARS, 0)?.toString(),
            initial?.let { !it.selection.collapsed } ?: hasEditorSelection(),
            separator, trace.revisited, suggestionsEnabled, autocorrectEnabled && fieldAllowsAutocorrect && language.autocorrect, MAX_CONTEXT_CHARS,
            typed = typed,
            capitalizeI = !compatibilityEditorRestarted && language.english &&
                TypingRules.capitalizesLoneI(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT, autoCapitalize),
            autoSpaced = autoSpaced && initial != null, rule = language.wordRule,
        ) { prefix -> correctionFor(context, prefix, automaticOnly = true) }.copy(precondition = initial)
    }

    private fun hasEditorSelection(): Boolean = !currentInputConnection?.getSelectedText(0).isNullOrEmpty() || selectionActive

    private fun applyPendingPeriod(delimiter: String): Boolean {
        val pending = pendingPeriod ?: return false
        val personalContext = pendingPeriodPersonalContext.toList()
        val wordVerified = pendingPeriodWordVerified
        pendingPeriodWordVerified = false
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        if (!suggestionsEnabled || compatibilityEditorRestarted) return false
        if (pending.original != pending.corrected && (!autocorrectEnabled || !fieldAllowsAutocorrect ||
            pending.original.lowercase() in keptWords || localModel.isAddedWord(pending.original) || isProtectedWord(pending.original))) return false
        val edit = pending.edit(editorEdits.generation, editorSnapshot(), delimiter) ?: return false
        val applied = applyWordReplacement(edit.replacement, fallback = delimiter, precondition = edit.precondition)
        if (applied == edit.replacement) {
            appendRecentCorrection(delimiter, boundary = true)
            lastAutocorrect = if (edit.corrected) AutoCorrection(edit.prefix, edit.replacement.text) else null
            learnJustCompletedWord(edit, wordVerified, null, sentenceContext = personalContext)
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            if (edit.corrected) finishCorrection(edit, emptyList(), boundary = ".$delimiter")
        } else {
            cancelRecentCorrection()
        }
        // An ambiguous dispatched edit must never be retried as another insertion.
        return true
    }

    private fun correctionCommand(sent: Boolean): Boolean {
        if (!sent) correctionDiagnostics.count(CorrectionDiagnostics.Reason.COMMAND_REJECTED)
        return sent
    }

    /** Replace a current word without a destructive delete/commit gap. Verify before learning. */
    private fun applyWordReplacement(edit: WordEdits.Replacement, fallback: String? = null, precondition: VerifiedWordTransaction.Snapshot? = null,
                                     readLimit: Int = MAX_CONTEXT_CHARS): WordEdits.Replacement? {
        verifiedWordReceipt = null
        val ic = currentInputConnection ?: return null
        // A plain append replaces nothing, so it needs no second read: the receipt after the commit still verifies
        // the whole result, and a stale plan fails that check instead of removing text.
        val generation = editorEdits.generation
        val read = {
            (if (readLimit == MAX_CONTEXT_CHARS) editorSnapshot() else readEditorSnapshot(readLimit))
                .takeIf { editorEdits.generation == generation }
        }
        val initial = if (readLimit == MAX_CONTEXT_CHARS && precondition != null && edit.before == 0 && edit.after == 0) precondition else read()
        if (precondition != null && precondition != initial) {
            cancelRecentCorrection()
            revisitTrace(CorrectionDiagnostics.Revisit.PRECONDITION)
            correctionDiagnostics.count(CorrectionDiagnostics.Reason.STALE)
            // Preserve the actual key as ordinary text, but certify no planned correction/learning.
            if (fallback != null) {
                val plain = !hasEditorSelection()
                markEdit(plainAppend = plain, appendedCharacters = fallback.length)
                if (ic.commitText(fallback, 1) && plain) recordAppend(initial?.before, fallback, keyContext)
            }
            return null
        }
        // Selection replacements stay explicit user actions, never automatic correction/learning.
        if (initial == null && hasEditorSelection()) {
            markEdit()
            if (edit.before != 0 || edit.after != 0) return null
            return edit.takeIf { ic.commitText(it.text, 1) }
        }
        if (initial == null) {
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            correctionDiagnostics.count(CorrectionDiagnostics.Reason.MISSING_READ)
            // Ordinary input still works in editors that cannot certify a replacement.
            if (edit.before == 0 && edit.after == 0) {
                markEdit(plainAppend = true, appendedCharacters = edit.text.length)
                if (ic.commitText(edit.text, 1)) recordAppend(null, edit.text, keyContext)
            } else if (fallback != null) {
                markEdit(plainAppend = true, appendedCharacters = fallback.length)
                if (ic.commitText(fallback, 1)) recordAppend(null, fallback, keyContext)
            }
            revisitTrace(CorrectionDiagnostics.Revisit.FALLBACK)
            return null
        }
        // Older restarting editors cannot safely span selection/read/commit across connections.
        // Preserve the typed delimiter; contextual offers remain available for uncertain words.
        val planned = if (compatibilityEditorRestarted && (edit.before > 0 || edit.after > 0) && fallback != null)
            WordEdits.Replacement(0, 0, fallback) else edit
        // A verified explicit selection can consume one following space. Track its
        // actual range before collapsing it, so subsequent typing starts a new word.
        if (!initial.selection.collapsed) editorEdits.select(initial.selection.start, initial.selection.end)
        ic.beginBatchEdit()
        var batchOpen = true
        var receipt: VerifiedWordTransaction.Snapshot? = null
        val applied = try {
            VerifiedWordTransaction.apply(planned, initial, direct = TypingRules.prefersRangeReplacement(
                currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT, Build.VERSION.SDK_INT), read = read,
                range = { start, end, text ->
                    markEdit(deletedBefore = planned.before, deletedAfter = planned.after, appendedCharacters = text.length)
                    correctionCommand(if (Build.VERSION.SDK_INT >= 34) ic.replaceText(start, end, text, 1, null) else false)
                },
                select = { start, end ->
                    editorEdits.select(start, end)
                    correctionCommand(ic.setSelection(start, end))
                },
                commit = { text ->
                    val plain = planned.before == 0 && planned.after == 0
                    markEdit(plainAppend = plain, appendedCharacters = text.length, deletedBefore = 0)
                    val sent = ic.commitText(text, 1)
                    if (sent && plain) recordAppend(initial.before, text, keyContext)
                    correctionCommand(sent)
                },
                verified = { receipt = it },
                finishBatch = { batchOpen = false; ic.endBatchEdit(); Unit },
            )
        } finally { if (batchOpen) ic.endBatchEdit() }
        if (applied == null) {
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            editorEdits.uncertain()
            expectedContext = null
            revisitTrace(CorrectionDiagnostics.Revisit.UNSAFE_READ)
            correctionDiagnostics.count(CorrectionDiagnostics.Reason.UNVERIFIED)
        } else {
            expectedContext = (initial.before.dropLast(applied.before) + applied.text).takeLast(MAX_CONTEXT_CHARS)
            verifiedWordReceipt = receipt
        }
        return applied
    }

    private fun observedSelection(): EditorEditState.Selection? {
        val ic = currentInputConnection ?: return null
        val value = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_CONTEXT_CHARS; hintMaxLines = 1 }, 0)
            ?: return surroundingSnapshot(MAX_CONTEXT_CHARS)?.selection
        return EditorEditState.Selection(value.selectionStart + value.startOffset, value.selectionEnd + value.startOffset)
            .takeIf { it.start >= 0 && it.end >= 0 }
    }

    private fun editorSnapshot(): VerifiedWordTransaction.Snapshot? = measureAddedCorrection { readEditorSnapshot() }

    private fun readEditorSnapshot(limit: Int = MAX_CONTEXT_CHARS): VerifiedWordTransaction.Snapshot? {
        val ic = currentInputConnection ?: return null
        val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = limit * 2 + 1 }, 0)
            ?: return surroundingSnapshot(limit)
        // Nonmonitoring requests must return a full receipt, never a delta against an earlier one.
        if (extracted.partialStartOffset >= 0) return null
        return VerifiedWordTransaction.window(extracted.text?.toString(), extracted.startOffset,
            extracted.selectionStart, extracted.selectionEnd, limit)
    }

    /** Some editors support coherent surrounding receipts but not extracted text.
     * Unknown offsets remain unsafe for range edits; never assemble a receipt from separate reads. */
    private fun surroundingSnapshot(limit: Int): VerifiedWordTransaction.Snapshot? {
        if (Build.VERSION.SDK_INT < 31) return null
        val surrounding = currentInputConnection?.getSurroundingText(limit, limit, 0) ?: return null
        return VerifiedWordTransaction.window(surrounding.text.toString(), surrounding.offset,
            surrounding.selectionStart, surrounding.selectionEnd, limit)
    }

    /** Backspace right after a correction restores exactly what was typed and remembers it. */
    private fun undoAutocorrect(): Boolean {
        val undo = lastAutocorrect ?: return false
        lastAutocorrect = null
        val ic = currentInputConnection ?: return false
        if (ic.getTextBeforeCursor(undo.committed.length, 0)?.toString() != undo.committed) return false
        val record = recentCorrection.undo
        if (applyWordReplacement(WordEdits.Replacement(undo.committed.length, 0, undo.original),
                readLimit = maxOf(MAX_CONTEXT_CHARS, undo.committed.length, undo.original.length)) == null) { cancelRecentCorrection(); return true }
        personalWordOwned = false
        personalLearning.undo(undo.personal)
        if (record != null) logRepair(record.word, record.replacement, 'U')
        cancelRecentCorrection()
        if (undo.keep) keepWord(undo.original)
        return true
    }

    /**
     * Protects [word] from correction. In a field that doesn't allow learning it's kept only until the
     * field ends, so it never reaches another field or unlocks learning there.
     */
    private fun keepWord(word: String, persist: Boolean = fieldLearnsWords) {
        val key = word.lowercase()
        if (personallyBlocked(word)) return
        // Private fields and unverified edits keep a spelling only for this field.
        if (keptWords.add(key) && !persist) fieldKeptWords += key
        // A kept spelling with a capital inside it ("LocalType") is how the person writes it.
        if (persist && word.drop(1).any { it.isUpperCase() }) spellings().change(word, WordSpellings.added(word), localModel::isAddedWord)
        corrector.forgetRecentAnswers()
    }

    private fun blockedLoadFailed(): Boolean = this::blockedStore.isInitialized && !blockedStore.ready && blockedStore.failed

    private fun personallyBlocked(word: String): Boolean = !blockedStore.ready || BlockedWords.contains(blockedStore.words, word)

    private fun blockedOutput(text: String): Boolean = !blockedStore.ready || BlockedWords.blocksText(blockedStore.words, text)

    private fun mayLearnWord(word: String, context: List<String> = emptyList()): Boolean =
        blockedStore.ready && BlockedWords.mayLearn(blockedStore.words, context, word)

    private fun spellings() = WordSpellingStore.get(this, language)

    private fun correctionIdentity() = RecentWordCorrection.Identity(editorEdits.generation, dictionaryGeneration, correctionLogPolicy)

    private fun smallAccommodationsAllowed(): Boolean = fieldLearnsWords && autocorrectEnabled && fieldAllowsAutocorrect &&
        suggestionsEnabled && viewShown && !compatibilityEditorRestarted && !proofreading && proposal == null &&
        panel == null && !capsLocked()

    private fun accommodationProtected(word: String): Boolean {
        val key = AdaptiveNgramModel.normalize(word)
        return key in keptWords || localModel.isAddedWord(key) || isProtectedWord(key) || key in fieldVocabulary ||
            (!profanityAllowed && key in profanityWords)
    }

    private fun ordinarySpacingWord(word: String): Boolean =
        AdaptiveNgramModel.normalize(word) in dictionary && !accommodationProtected(word) && !DomainTyping.protected(word, "")

    /** Commit the actual key once, then clean only a verified own parenthetical run. */
    private fun typeClosingParenthesis() {
        val initial = editorSnapshot()
        val offer = if (initial != null && smallAccommodationsAllowed() && !capsLockUsedForWord)
            ownedSpacing.closing(correctionIdentity(), initial, SystemClock.uptimeMillis(), ::ordinarySpacingWord) else null
        ownedSpacing.reset()
        val raw = WordEdits.Replacement(0, 0, ")")
        if (applyWordReplacement(raw, fallback = ")", precondition = initial) == raw) {
            appendRecentCorrection(")", boundary = true)
            verifiedWordReceipt?.let { s -> if (offer != null) offerSmallAccommodation(offer, s) }
        }
        personalWordOwned = false
        autoSpaced = false
        trace.finish()
        updateShiftFromEditor()
    }

    private fun finishSmallAccommodation(edit: WordEdits.Separator, verified: Boolean): Boolean =
        measureAddedCorrection(CorrectionDiagnostics.Stage.FINALIZATION) { finishSmallAccommodationMeasured(edit, verified) }

    private fun finishSmallAccommodationMeasured(edit: WordEdits.Separator, verified: Boolean): Boolean {
        val previous = accommodationAnchor
        accommodationAnchor = null
        val s = verifiedWordReceipt ?: return false
        if (!verified || !edit.learningEligible || edit.corrected || !smallAccommodationsAllowed()) return false
        val now = SystemClock.uptimeMillis()
        val identity = correctionIdentity()
        val spacing = ownedSpacing.completed(identity, s, edit.prefix, now,
            !capsLockUsedForWord && ordinarySpacingWord(edit.prefix))
        if (spacing != null) {
            offerSmallAccommodation(spacing, s)
            return true
        }
        val commaSpace = ownedSpacing.missingCommaSpace(identity, s, edit.prefix, now,
            !capsLockUsedForWord && ordinarySpacingWord(edit.prefix))
        if (commaSpace != null) {
            offerSmallAccommodation(commaSpace, s)
            return true
        }
        // The word fixes (a/an, "your" before a verb, a repeated word) are English; spacing above is any language's.
        if (!language.english) return false
        val current = SmallAccommodations.remember(identity, s, edit.prefix, now, verified,
            protected = accommodationProtected(edit.prefix) || capsLockUsedForWord)
        accommodationAnchor = current
        val offer = SmallAccommodations.word(previous, current, identity, s, boundaryPersonalContext.lastOrNull(), now,
            known = AdaptiveNgramModel.normalize(edit.prefix) in dictionary, capsLockUsed = capsLockUsedForWord) ?: return false
        offerSmallAccommodation(offer, s)
        return true
    }

    /**
     * "of.the" just got its space: offer "of the". Both words are common dictionary words typed here, and the
     * second is none of the usual address endings ([OwnedSpacing.addressEndings]), so "example.com" is left alone. The word after the dot is inside a dotted
     * run, which never learns, so this is checked before the learning-eligibility guard the others use.
     */
    private fun finishDotSpace(edit: WordEdits.Separator): Boolean {
        val s = verifiedWordReceipt ?: return false
        if (!smallAccommodationsAllowed() || capsLockUsedForWord || edit.corrected) return false
        fun common(word: String) = ordinarySpacingWord(word) && dictionary.score(AdaptiveNgramModel.normalize(word)) >= DOT_SPACE_MIN_SCORE
        val offer = ownedSpacing.dotSpace(correctionIdentity(), s, edit.prefix, SystemClock.uptimeMillis()) { first, second ->
            common(first) && common(second)
        } ?: return false
        offerSmallAccommodation(offer, s)
        return true
    }

    /** Punctuation was already delivered, so an uncertain cleanup never retries or loses that key. */
    private fun finishCommaSpacing(punctuation: String) = measureAddedCorrection(CorrectionDiagnostics.Stage.FINALIZATION) {
        finishCommaSpacingMeasured(punctuation)
    }

    private fun finishCommaSpacingMeasured(punctuation: String) {
        val anchor = accommodationAnchor
        accommodationAnchor = null
        if (autoSpaced || !smallAccommodationsAllowed() || anchor == null || accommodationProtected(anchor.original)) return
        val s = verifiedWordReceipt ?: return
        val offer = SmallAccommodations.comma(anchor, correctionIdentity(), s, SystemClock.uptimeMillis(), punctuation) ?: return
        offerSmallAccommodation(offer, s)
    }

    private fun offerSmallAccommodation(offer: SmallAccommodations.Offer, snapshot: VerifiedWordTransaction.Snapshot) {
        if (blockedOutput(offer.replacement)) return
        val now = SystemClock.uptimeMillis()
        val word = RecentWordCorrection.Word(correctionIdentity(), offer.original, offer.delimiter, offer.start,
            offer.context.takeLast(32), snapshot.after, now, previous = localModel.lastCompletedWord(offer.context),
            contextBeforeWord = offer.context, repairWindowMs = SmallAccommodations.OFFER_MS, grammar = true)
        cancelPendingCorrection()
        contractionAnchor = null
        correctionRequest++
        neuralWorker?.cancelCorrection()
        pendingPersonalContext = emptyList()
        recentCorrection.begin(word)
        if (offer.automatic) {
            val replacement = WordEdits.Replacement(offer.original.length + offer.delimiter.length, 0,
                offer.replacement + offer.delimiter)
            if (applyWordReplacement(replacement, precondition = snapshot) == replacement) {
                completeRecentRepair(word, offer.replacement)
                // Delete undoes the automatic punctuation and restores its preceding manual space.
                lastAutocorrect = AutoCorrection(offer.original, offer.replacement + offer.delimiter, keep = false)
                showCorrectionFeedback(offer.original + offer.delimiter, offer.replacement + offer.delimiter, offered = false)
                verifiedWordReceipt?.let { receipt ->
                    ownedSpacing.openingApplied(correctionIdentity(), offer, receipt, now)
                }
            } else cancelRecentCorrection()
        } else {
            correctionOffer = offer.replacement
            logRepair(word, offer.replacement, 'O')
            showCorrectionFeedback(offer.original, offer.replacement, offered = true)
            handler.removeCallbacks(expirePendingCorrection)
            handler.postDelayed(expirePendingCorrection, SmallAccommodations.OFFER_MS)
            handler.removeCallbacks(hideCorrectionMessage)
            handler.postDelayed(hideCorrectionMessage, SmallAccommodations.OFFER_MS)
        }
        spaceProofread.reset()
    }

    /** Synchronous at the second word's space, never a model-driven late edit. */
    private fun finishContextualContraction(edit: WordEdits.Separator, verified: Boolean): Boolean =
        measureAddedCorrection(CorrectionDiagnostics.Stage.FINALIZATION) { finishContextualContractionMeasured(edit, verified) }

    private fun finishContextualContractionMeasured(edit: WordEdits.Separator, verified: Boolean): Boolean {
        val previous = contractionAnchor
        contractionAnchor = null
        if (previous == null && edit.prefix != "its" && edit.prefix != "Its") return false
        if (!language.english) return false
        val s = verifiedWordReceipt ?: return false
        if (!verified || !edit.learningEligible || edit.corrected || !fieldLearnsWords ||
            !autocorrectEnabled || !fieldAllowsAutocorrect || !language.autocorrect || !viewShown || proofreading || proposal != null ||
            panel != null || capsLocked() || "its" in keptWords || localModel.isAddedWord("its") || isProtectedWord("its")) return false
        val identity = correctionIdentity()
        val now = SystemClock.uptimeMillis()
        contractionAnchor = ContextualContractions.remember(identity, s, edit.prefix, now, verifiedOwn = verified)
        val offer = ContextualContractions.propose(previous, identity, s, edit.prefix, verified,
            boundaryPersonalContext.lastOrNull(), now) ?: return false
        if (blockedOutput(offer.replacement)) return false
        val anchor = offer.anchor
        val word = RecentWordCorrection.Word(identity, anchor.original, offer.delimiter, anchor.start,
            anchor.context.takeLast(32), s.after, now,
            previous = localModel.lastCompletedWord(anchor.context), contextBeforeWord = anchor.context,
            repairWindowMs = ContextualContractions.OFFER_MS, grammar = true)
        cancelPendingCorrection()
        correctionRequest++
        neuralWorker?.cancelCorrection()
        pendingPersonalContext = emptyList()
        recentCorrection.begin(word)
        if (offer.automatic) {
            val replacement = WordEdits.Replacement(anchor.original.length + offer.delimiter.length, 0,
                offer.replacement + offer.delimiter)
            if (applyWordReplacement(replacement, precondition = s) == replacement) {
                completeRecentRepair(word, offer.replacement)
                // Immediate delete removes the final space too, just like ordinary autocorrection.
                lastAutocorrect = AutoCorrection(anchor.original + offer.delimiter.dropLast(1),
                    offer.replacement + offer.delimiter, keep = false)
            }
        } else {
            correctionOffer = offer.replacement
            logRepair(word, offer.replacement, 'O')
            showCorrectionFeedback(anchor.original, offer.replacement, offered = true)
            handler.removeCallbacks(expirePendingCorrection)
            handler.postDelayed(expirePendingCorrection, ContextualContractions.OFFER_MS)
            handler.removeCallbacks(hideCorrectionMessage)
            handler.postDelayed(hideCorrectionMessage, ContextualContractions.OFFER_MS)
        }
        spaceProofread.reset()
        return true
    }

    private fun finishCorrection(edit: WordEdits.Separator, touches: List<Touch?>?, boundary: String? = null) {
        measureAddedCorrection(CorrectionDiagnostics.Stage.FINALIZATION) { finishCorrectionMeasured(edit, touches, boundary) }
    }

    private fun finishCorrectionMeasured(edit: WordEdits.Separator, touches: List<Touch?>?, boundary: String?) {
        if (!blockedStore.ready || !edit.learningEligible || !autocorrectEnabled || !fieldAllowsAutocorrect || !language.autocorrect || touches == null) return
        val s = verifiedWordReceipt ?: return
        val delimiter = boundary ?: edit.replacement.text.lastOrNull()?.toString() ?: return
        val original = edit.prefix
        val key = AdaptiveNgramModel.normalize(original)
        if (!edit.corrected && (original.length < 2 || key in dictionary || key in lexicon ||
            key in fieldVocabulary || localModel.knows(key) || key in keptWords || key in profanityWords || isProtectedWord(key))) return
        val replacement = if (edit.corrected) edit.replacement.text.dropLast(delimiter.length) else original
        val start = s.selection.end - replacement.length - delimiter.length
        val fullContext = s.before.dropLast(replacement.length + delimiter.length)
        val typedContext = fullContext + original
        val contextState = CorrectionTextContext.capture(typedContext, language.marks)
        val precedingWord = contextState.previous
        val word = RecentWordCorrection.Word(correctionIdentity(), original, delimiter, start, fullContext.takeLast(32), s.after, SystemClock.uptimeMillis(),
            previous = precedingWord, contextBeforeWord = fullContext)
        if (edit.corrected) {
            correctionPersonal = completedPersonal
            recentCorrection.applied(word, replacement, SystemClock.uptimeMillis(), following = "")
            logRepair(word, replacement, 'P')
            showCorrectionFeedback(original, replacement, offered = false)
            handler.removeCallbacks(expireCorrectionUndo)
            handler.postDelayed(expireCorrectionUndo, RecentWordCorrection.UNDO_MS)
            return
        }
        pendingPersonalContext = if (fieldLearnsWords) boundaryPersonalContext.toList() else emptyList()
        recentCorrection.begin(word)
        handler.removeCallbacks(expirePendingCorrection)
        handler.postDelayed(expirePendingCorrection, RecentWordCorrection.REPAIR_MS)
        val sentenceStart = contextState.sentenceStart
        val previous = precedingWord ?: AdaptiveNgramModel.START.takeIf { sentenceStart }
        val stem = key.removeSuffix("'s").removeSuffix("s'")
        val spellings = setOf(key, stem)
        val protected = spellings.filterTo(HashSet()) { localModel.knows(it) || it in profanityWords || it in fieldVocabulary }
        val input = CorrectionInput(original, fullContext, previous, sentenceStart, fullContext.trimEnd().endsWith(','),
            dictionary, lexicon, protected, spellings.filterTo(HashSet()) { it in keptWords || localModel.isAddedWord(it) || isProtectedWord(it) },
            // Published asset sets are immutable; personal counts and touch offsets are detached.
            blockedStore.words + if (profanityAllowed) emptySet() else profanityWords, localModel.correctionCounts(previous),
            touchOffsets.entries(), touches.toList(), language)
        val request = ++correctionRequest
        val worker = neuralWorker ?: NeuralPredictionWorker(this, handler).also { neuralWorker = it }
        worker.evaluateCorrection(request, input, corpusPrior, correctionPolicy, neuralEnabled && neuralReady && fieldWantsSuggestions && learningAllowed) { answer ->
            syncLearnedState()
            if (request != correctionRequest || recentCorrection.pending != word || !viewShown || proofreading || panel != null || answer == null) return@evaluateCorrection
            val now = SystemClock.uptimeMillis()
            val fix = answer.suggestion?.let { TypingRules.applyCase(it, original, false, capsLocked()) } ?: return@evaluateCorrection
            if (personallyBlocked(fix)) return@evaluateCorrection
            val current = editorSnapshot() ?: return@evaluateCorrection
            val range = recentCorrection.repair(correctionIdentity(), current, fix, now) ?: return@evaluateCorrection
            // The reserved validation gate did not pass. This branch stays an offer unless a
            // future frozen policy and this exact editor profile have both qualified.
            if (answer.lateAutomaticEligible && rangeEditorQualified() && applyRange(range, current, automatic = true)) {
                completeRecentRepair(word, fix)
            } else {
                if (recentCorrection.pending != word) return@evaluateCorrection
                correctionOffer = fix
                logRepair(word, fix, 'O')
                correctionDiagnostics.count(CorrectionDiagnostics.Reason.OFFERED)
                showCorrectionFeedback(original, fix, offered = true)
            }
        }
    }

    private fun rangeEditorQualified(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val pkg = info.packageName ?: return false
        val version = runCatching {
            val p = packageManager.getPackageInfo(pkg, 0)
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        }.getOrNull() ?: return false
        return EditorProfiles.allowsRangeEdit(EditorProfiles.Profile(pkg, version, Build.VERSION.SDK_INT, info.inputType))
    }

    private fun applyRange(edit: RecentWordCorrection.RangeEdit, initial: VerifiedWordTransaction.Snapshot, automatic: Boolean): Boolean {
        val ic = currentInputConnection ?: return false
        if (automatic && (Build.VERSION.SDK_INT < 34 || !rangeEditorQualified())) return false
        resetNeuralSession()
        editorEdits.select(edit.start, edit.end)
        editorEdits.edit(0, 0, edit.text.length + initial.selection.end - edit.end)
        ic.beginBatchEdit()
        val applied = try {
            EditorRangeTransaction.apply(edit, initial, direct = automatic || TypingRules.prefersRangeReplacement(
                currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT, Build.VERSION.SDK_INT), read = ::editorSnapshot,
                range = { start, end, text, cursor -> correctionCommand(if (Build.VERSION.SDK_INT >= 34) ic.replaceText(start, end, text, cursor, null) else false) },
                select = { start, end -> correctionCommand(ic.setSelection(start, end)) },
                selected = {
                    observedSelection()?.let { EditorRangeTransaction.SelectionRead(it.start, it.end, ic.getSelectedText(0)?.toString()) }
                },
                commit = { text, cursor -> correctionCommand(ic.commitText(text, cursor)) },
            )
        } finally { ic.endBatchEdit() }
        if (!applied) {
            editorEdits.uncertain(); expectedContext = null
            correctionDiagnostics.count(CorrectionDiagnostics.Reason.UNVERIFIED)
            cancelRecentCorrection()
            revisitTrace(CorrectionDiagnostics.Revisit.UNVERIFIED)
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        } else expectedContext = currentContext()
        return applied
    }

    private fun applyRecentOffer() {
        syncLearnedState()
        val fix = correctionOffer?.takeUnless(::blockedOutput) ?: return
        val word = recentCorrection.pending ?: return hideCorrectionFeedback()
        val s = editorSnapshot() ?: return cancelRecentCorrection()
        val edit = recentCorrection.repair(correctionIdentity(), s, fix, SystemClock.uptimeMillis()) ?: return cancelRecentCorrection()
        if (applyRange(edit, s, automatic = false)) completeRecentRepair(word, fix, explicit = true)
    }

    private fun completeRecentRepair(word: RecentWordCorrection.Word, fix: String, explicit: Boolean = false) {
        val ownPrevious = pendingPersonalContext.lastOrNull()
        correctionPersonal = if (word.grammar) null else personalLearning.repair(pendingPersonalContext, fix,
            fieldLearnsWords && mayLearnWord(fix, pendingPersonalContext) && !fix.any(Char::isWhitespace),
            if (explicit) LearningOrigin.SUGGESTION_ACCEPT else LearningOrigin.VERIFIED_REPAIR)
        pendingPersonalContext = emptyList()
        if (word.grammar) {
            ownedSpacing.reset()
            accommodationAnchor = null
            contractionAnchor = null
            personalWordOwned = false
            personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
        }
        recentCorrection.applied(word, fix, SystemClock.uptimeMillis())
        correctionOffer = null
        lastAutocorrect = null
        correctionDiagnostics.count(CorrectionDiagnostics.Reason.APPLIED)
        // No touch training: this word was corrected. Only the verified replacement learns once.
        // A multiword offer is an editor repair, not one dictionary entry or a
        // single-word replay outcome. Its bounded Undo still uses the exact phrase.
        val learned = mayLearnWord(fix, listOfNotNull(word.previous)) && !word.grammar && !fix.any(Char::isWhitespace) &&
            WordPersistence.pair(fieldLearnsWords, true, word.previous, ownPrevious)
        if (learned) {
            localModel.accept(word.previous, fix)
            modelMutated = true
            if (!logAllowed) snapshotClean = false
            corrector.forgetRecentAnswers()
        }
        logRepair(word, fix, 'P', learned)
        showCorrectionFeedback(word.original, fix, offered = false)
        handler.removeCallbacks(expireCorrectionUndo)
        handler.postDelayed(expireCorrectionUndo, RecentWordCorrection.UNDO_MS)
    }

    private fun undoRecentCorrection() {
        syncLearnedState()
        val record = recentCorrection.undo ?: return
        val s = editorSnapshot() ?: return cancelRecentCorrection()
        val edit = recentCorrection.undoEdit(correctionIdentity(), s, SystemClock.uptimeMillis()) ?: return cancelRecentCorrection()
        if (applyRange(edit, s, automatic = false)) {
            personalWordOwned = false
            personalLearning.undo(correctionPersonal)
            if (record.word.grammar) personalLearning.reset(PersonalContextTrace.ResetReason.UNVERIFIABLE)
            correctionPersonal = null
            logRepair(record.word, record.replacement, 'U')
            if (!record.word.grammar) keepWord(record.word.original)
            recentCorrection.cancel()
            correctionOffer = null
            lastAutocorrect = null
            correctionDiagnostics.count(CorrectionDiagnostics.Reason.UNDONE)
            revisitTrace(CorrectionDiagnostics.Revisit.UNDONE)
            hideCorrectionFeedback()
            scheduleRefresh()
        }
    }

    private val hideCorrectionMessage = Runnable { hideCorrectionFeedback() }
    private val expireCorrectionUndo = Runnable { recentCorrection.hasUndo(SystemClock.uptimeMillis()); updateLead() }
    private val expirePendingCorrection = Runnable { cancelPendingCorrection(); hideCorrectionFeedback() }

    private fun logRepair(word: RecentWordCorrection.Word, fix: String, outcome: Char, learned: Boolean = false) {
        if (logAllowed && SmallAccommodations.loggable(word.original, fix))
            PredictionLog.repair(RepairEvent(word.contextBeforeWord, word.original, fix, outcome, learned, word.previous))
    }

    private fun cancelPendingCorrection() {
        recentCorrection.pending?.let { word -> correctionOffer?.let { fix -> logRepair(word, fix, 'J') } }
        recentCorrection.cancelPending()
        correctionOffer = null
        handler.removeCallbacks(expirePendingCorrection)
    }

    private fun appendRecentCorrection(text: String, boundary: Boolean) {
        if (boundary) cancelPendingCorrection()
        recentCorrection.append(text, SystemClock.uptimeMillis(), boundary)
    }

    private fun showCorrectionFeedback(original: String, replacement: String, offered: Boolean) {
        if (proofreading || proposal != null || proposalRow?.visibility == View.VISIBLE || panel != null || stripOff != null) return
        if (offered && blockedOutput(replacement)) return
        hideNotice()
        linkNotice = false
        handler.removeCallbacks(hideLinkMessage)
        gestureNotice = false
        handler.removeCallbacks(hideGestureMessage)
        correctionAction?.visibility = View.VISIBLE
        correctionText?.text = "$original → $replacement"
        correctionText?.contentDescription = getString(if (offered) R.string.correction_offer_description else R.string.correction_applied_description, original, replacement)
        correctionAction?.text = getString(if (offered) R.string.correction_apply else R.string.correction_undo)
        correctionAction?.contentDescription = getString(if (offered) R.string.correction_apply else R.string.correction_undo)
        correctionRow?.visibility = View.VISIBLE
        updateSlotVisibility()
        updateLead()
        handler.removeCallbacks(hideCorrectionMessage)
        handler.postDelayed(hideCorrectionMessage, 750)
    }

    private fun hideCorrectionFeedback() {
        // The row is showing a cleaned paste's offer, which is not a word correction: the edits and caret reports
        // that end a correction's feedback follow every paste. hideLinkNotice ends it. A gesture's notice is the same.
        if (linkNotice || gestureNotice) return
        handler.removeCallbacks(hideCorrectionMessage)
        correctionRow?.visibility = View.GONE
        updateSlotVisibility()
        updateLead()
    }

    private fun cancelRecentCorrection() {
        cancelSwipeWork()
        ownedSpacing.reset()
        contractionAnchor = null
        accommodationAnchor = null
        capsLockUsedForWord = false
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        cancelPendingCorrection()
        correctionRequest++
        neuralWorker?.cancelCorrection()
        recentCorrection.cancel()
        correctionPersonal = null
        pendingPersonalContext = emptyList()
        correctionOffer = null
        handler.removeCallbacks(expireCorrectionUndo)
        hideCorrectionFeedback()
    }

    /** Explicit developer export: bounded counters and CPU durations, never editor text. */
    // region Experimental letter swipe

    private fun cancelSwipeWork() {
        swipeSerial++
        swipeStart = null
        swipeBlind = false
        swipeUnspaced = null
        swipeUnspacedJoined = false
        swipeChoices = null
        swipeWorker?.cancel()
    }

    private fun swipeCapitalizesI(): Boolean =
        TypingRules.capitalizesLoneI(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT, autoCapitalize)

    /**
     * Whether a swipe may start now. Off while the setting is off or the keyboard is busy, which keeps
     * taps as they are with no notice; refused, with the reason a notice gives, in a field swipe can't
     * type into or with a selection. Every text field takes a swiped word, passwords and addresses
     * included ([SwipeRules.field]): a swipe is an insertion, which teaches nothing anywhere.
     */
    private fun swipeStartNow(): SwipeStart {
        if (!KeyboardSettings.experimentalSwipe(this) || !viewShown || mode != KeyboardLayout.Mode.LETTERS || !dictionaryReady ||
            emojiQuery != null || panel != null || proofreading || proposal != null || compatibilityEditorRestarted ||
            currentInputConnection == null ||
            getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        ) return SwipeStart.Off
        if (!SwipeRules.field(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT)) {
            return SwipeStart.Refused(SwipeRefusal.FIELD)
        }
        if (!language.english) return SwipeStart.Refused(SwipeRefusal.LANGUAGE)
        if (selectionActive) return SwipeStart.Refused(SwipeRefusal.SELECTION)
        return SwipeStart.Accepted
    }

    private fun swipeAllowedNow(): Boolean = swipeStartNow() == SwipeStart.Accepted

    override fun onSwipeStart(): SwipeStart {
        // Read before cancelSwipeWork forgets it.
        val unspaced = swipeUnspaced
        val unspacedJoined = swipeUnspacedJoined
        cancelSwipeWork()
        val start = swipeStartNow()
        swipeStartStatus = when (start) {
            SwipeStart.Off -> "off"
            is SwipeStart.Refused -> start.reason.name.lowercase()
            SwipeStart.Accepted -> "read"
        }
        if (start != SwipeStart.Accepted) return start
        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        // A field that won't show its text can't be checked before a word goes in, except a raw-key field such as a
        // terminal, which takes the word as it would a typed one.
        val initial = editorSnapshot() ?: run {
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_NULL) return SwipeStart.Refused(SwipeRefusal.FIELD)
            swipeStartStatus = "blind"
            swipeBlind = true
            swipeShift = shiftState
            swipeSpacing = SwipeSpacing(joined = false, space = true)
            return SwipeStart.Accepted
        }
        swipeStartStatus = "boundary"
        val joined = SwipeRules.joined(inputType)
        // An address bar holds searches as well as addresses: after a joiner the word is part of an address.
        val afterJoiner = initial.before.lastOrNull()?.let { it in SwipeRules.JOINERS } == true
        // Straight after a swiped word that text followed, so it got no space, the next swipe supplies the space.
        val afterSwipe = unspaced != null && unspaced == initial
        if (!SwipeInsertion.mayStart(initial, afterJoiners = joined || addressBar, afterSwipe = afterSwipe)) {
            return SwipeStart.Refused(if (initial.selection.collapsed) SwipeRefusal.WORD_START else SwipeRefusal.SELECTION)
        }
        swipeStartStatus = "accepted"
        swipeStart = initial
        swipeShift = shiftState
        // A swipe straight after one that ran together (in an address bar, after a joiner) runs on too.
        val runTogether = joined || (addressBar && afterJoiner) || (afterSwipe && unspacedJoined)
        swipeSpacing = SwipeSpacing(joined = runTogether, space = !runTogether, afterSwipe = afterSwipe)
        return SwipeStart.Accepted
    }

    override fun onSwipeRefused(reason: SwipeRefusal) {
        swipeRefusals++
        showNotice(when (reason) {
            SwipeRefusal.FIELD -> NoticeRules.Message.SWIPE_OFF_HERE
            SwipeRefusal.WORD_START -> NoticeRules.Message.SWIPE_NEW_WORD
            SwipeRefusal.SELECTION -> NoticeRules.Message.SWIPE_SELECTION
            SwipeRefusal.LANGUAGE -> NoticeRules.Message.SWIPE_LANGUAGE
        })
    }

    override fun onSwipeCancel() { cancelSwipeWork() }

    override fun onSwipe(path: SwipePath) {
        if (swipeBlind) return onBlindSwipe(path)
        val initial = swipeStart ?: return
        if (!swipeAllowedNow() || editorSnapshot() != initial) return cancelSwipeWork()
        val serial = swipeSerial
        val request = SwipeRequest(serial, initial)
        val case = swipeShift
        val engine = swipeWorker ?: SwipeWorker(this).also { swipeWorker = it }
        engine.decode(path, dictionary) { candidates, duration ->
            swipeLastMs = duration
            if (destroyed || !request.matches(swipeSerial, swipeAllowedNow(), editorSnapshot())) return@decode
            // Words run together in an address or a password, as decoded: no capital I there.
            val capitalizeI = swipeCapitalizesI() && !swipeSpacing.joined
            val words = candidates.filter { !personallyBlocked(it) && (profanityAllowed || it !in profanityWords) }
                .map { SwipeInsertion.word(it, capitalizeI) }.distinct().take(3)
            if (words.isEmpty() || duration > 2_000) {
                cancelSwipeWork()
                showProposalMessage(getString(R.string.swipe_no_word), autoDismissMs = 2_000)
            } else commitSwipe(initial, initial, words.first(), words, case, spacing = swipeSpacing)
        }
    }

    /** A swipe in a raw-key field that can't show its text: the best word and a space go in as typed text would. */
    private fun onBlindSwipe(path: SwipePath) {
        val serial = swipeSerial
        val case = swipeShift
        val engine = swipeWorker ?: SwipeWorker(this).also { swipeWorker = it }
        engine.decode(path, dictionary) { candidates, duration ->
            swipeLastMs = duration
            if (destroyed || serial != swipeSerial || !swipeAllowedNow()) return@decode
            swipeBlind = false
            val word = candidates.firstOrNull { !personallyBlocked(it) && (profanityAllowed || it !in profanityWords) }
            if (word == null || duration > 2_000) {
                showProposalMessage(getString(R.string.swipe_no_word), autoDismissMs = 2_000)
                return@decode
            }
            currentInputConnection?.commitText(SwipeInsertion.text(word, case, ""), 1)
            if (shiftState != KeyboardView.ShiftState.LOCKED) setShift(KeyboardView.ShiftState.OFF)
            updateShiftFromEditor()
            scheduleRefresh()
        }
    }

    private fun commitSwipe(base: VerifiedWordTransaction.Snapshot, initial: VerifiedWordTransaction.Snapshot,
        word: String, words: List<String>, case: KeyboardView.ShiftState, replaced: Int = 0, spacing: SwipeSpacing) {
        if (!swipeAllowedNow() || editorSnapshot() != initial) return cancelSwipeWork()
        syncLearnedState()
        if (personallyBlocked(word)) { cancelSwipeWork(); scheduleRefresh(); return }
        cancelRecentCorrection()
        // After a lone "a" or "I", or a swiped word that got no space ([SwipeSpacing.afterSwipe]), the swipe adds the
        // space a tap would have needed. A later choice replaces everything the first commit inserted, lead included.
        // Where words run together (an address, a password) a lone "a" gets no space after it either, and a swipe
        // straight after a swiped word runs on.
        val lead = SwipeInsertion.lead(base.before, swipeCapitalizesI(), spacing.afterSwipe, spacing.joined)
        val text = lead.text + SwipeInsertion.text(word, case, base.after, spacing.space)
        val edit = WordEdits.Replacement(if (replaced > 0) replaced else lead.replace, 0, text)
        if (applyWordReplacement(edit, precondition = initial) != edit) {
            cancelSwipeWork()
            showProposalMessage(getString(R.string.swipe_check_text), autoDismissMs = 2_000)
            return
        }
        val receipt = verifiedWordReceipt ?: return cancelSwipeWork()
        revisitTrace(CorrectionDiagnostics.Revisit.INSERT)
        personalLearning.reset(PersonalContextTrace.ResetReason.PASTE)
        completedPersonal = null
        boundaryPersonalContext = emptyList()
        personalWordOwned = false
        appendLedger.clear()
        typedRun.reset()
        autoSpaced = base.after.isEmpty()
        val now = SystemClock.uptimeMillis()
        val original = base.before.takeLast(lead.replace)
        val anchor = RecentWordCorrection.Word(correctionIdentity(), original, "", base.selection.end - lead.replace,
            base.before.dropLast(lead.replace), base.after, now, grammar = true)
        recentCorrection.applied(anchor, text, now, following = "")
        lastAutocorrect = AutoCorrection(original = original, committed = text, keep = false)
        swipeChoices = SwipeChoices(base, receipt, words, text, case, now + RecentWordCorrection.UNDO_MS, spacing)
        // No space went after the word (text followed it, or words run together here): a swipe straight after this
        // one puts it in first, or runs on.
        swipeUnspaced = receipt.takeIf { base.after.isNotEmpty() || !spacing.space }
        swipeUnspacedJoined = spacing.joined
        // Keep the decoded alternatives visible immediately; the lead button supplies Undo.
        updateLead()
        handler.removeCallbacks(expireCorrectionUndo)
        handler.postDelayed(expireCorrectionUndo, RecentWordCorrection.UNDO_MS)
        if (shiftState != KeyboardView.ShiftState.LOCKED) setShift(KeyboardView.ShiftState.OFF)
        updateShiftFromEditor()
        scheduleRefresh()
    }

    // endregion

    // region Slide gestures

    override fun slideAllowed(action: KeyAction): Boolean = slideGestures && viewShown && emojiQuery == null && panel == null &&
        !inputIsSensitive && currentInputConnection != null && when (action) {
            KeyAction.Space -> true
            // A field that takes raw keys has no text to measure words on.
            KeyAction.Backspace -> stripOff != TypingRules.StripOff.TERMINAL
            else -> false
        }

    /** Moves the caret [delta] characters, as a person moving it by hand would: the word there is no longer being typed. */
    override fun onCursorSlide(delta: Int) {
        val ic = currentInputConnection ?: return
        if (delta == 0 || !slideAllowed(KeyAction.Space)) return
        drainPendingKeys()
        val arrow = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val selection = if (stripOff == TypingRules.StripOff.TERMINAL) null else observedSelection()
        val before = selection?.let { ic.getTextBeforeCursor(CARET_READ_CHARS, 0)?.toString() }
        val after = selection?.let { ic.getTextAfterCursor(CARET_READ_CHARS, 0)?.toString() }
        if (selection == null || before == null || after == null) {
            // A terminal, or an editor that cannot say where its caret is: arrow keys are its own way to move.
            pendingPeriod = null
            pendingPeriodPersonalContext = emptyList()
            correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.SELECTION)
            caretLeftOwnPosition()
            repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(arrow) }
            return
        }
        val target = CursorGestures.caretTarget(selection.start, selection.end, before, after, delta)
        if (target == selection.start && target == selection.end) return
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.SELECTION)
        caretLeftOwnPosition()
        ic.setSelection(target, target)
        scheduleRefresh()
    }

    /** Shows what a slide left from delete would remove. Nothing changes in the field until the slide ends. */
    override fun onDeleteSlide(words: Int) {
        val ic = currentInputConnection ?: return
        if (!slideAllowed(KeyAction.Backspace)) return
        val slide = deleteSlide ?: run {
            drainPendingKeys()
            if (hasEditorSelection()) return
            // The press that began this slide deleted a character. Start only once the editor shows that text;
            // a read that still trails the press, or follows an undone correction, measures nothing yet.
            // (Its own window is one character shorter than a fresh read's, so the test is on the ending.)
            val expected = expectedContext ?: return
            val caret = observedSelection()?.takeIf { it.start == it.end } ?: return
            val before = ic.getTextBeforeCursor(DELETE_SLIDE_CHARS, 0)?.toString()?.takeIf { it.endsWith(expected) } ?: return
            DeleteSlide(before, caret, editorEdits.generation).also { deleteSlide = it }
        }
        slide.length = CursorGestures.wordsLength(slide.before, words)
        stripStatus = if (slide.length == 0) getString(R.string.delete_slide_none)
            else getString(R.string.delete_slide_preview, slide.before.takeLast(slide.length).trim().replace('\n', '⏎'))
        hideCorrectionFeedback()
        dismissProposal()
        updateVoiceButton()
    }

    /** Removes what the slide covered, if the text is still what the preview was measured on. */
    override fun onDeleteSlideEnd(commit: Boolean) {
        val slide = deleteSlide ?: return
        cancelDeleteSlide()
        val ic = currentInputConnection
        // Same field, same caret, same text as the preview was measured on, and still a place slides are allowed.
        if (!commit || slide.length == 0 || ic == null || !slideAllowed(KeyAction.Backspace) || hasEditorSelection() ||
            editorEdits.generation != slide.generation || observedSelection() != slide.caret ||
            ic.getTextBeforeCursor(DELETE_SLIDE_CHARS, 0)?.toString() != slide.before) {
            if (viewShown) scheduleRefresh()
            return
        }
        recentCorrection.cancel()
        correctionOffer = null
        autoSpaced = false
        lastAutocorrect = null
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        personalLearning.reset(PersonalContextTrace.ResetReason.CURSOR)
        personalWordOwned = false
        markEdit(deletedBefore = slide.length)
        expectedContext = slide.before.dropLast(slide.length)
        ic.deleteSurroundingText(slide.length, 0)
        // Whatever word is left at the caret was typed before this; adding to it is editing, not typing it fresh.
        revisitTrace(CorrectionDiagnostics.Revisit.SELECTION_DELETE)
        updateShiftFromEditor()
        scheduleRefresh()
    }

    private fun cancelDeleteSlide() {
        if (deleteSlide == null && stripStatus == null) return
        deleteSlide = null
        stripStatus = null
        updateVoiceButton()
    }

    // endregion

    // region Two-finger gestures

    private fun gestureState() = GestureRules.State(
        enabled = twoFingerGestures, shown = viewShown, connected = currentInputConnection != null,
        panel = panel != null, emojiSearch = emojiQuery != null || localInputKind != null,
        proofreading = proofreading || proposal != null || proposalRow?.visibility == View.VISIBLE,
        touchExploration = getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true,
        sensitive = inputIsSensitive, learning = learningAllowed, terminal = stripOff == TypingRules.StripOff.TERMINAL,
        letters = mode == KeyboardLayout.Mode.LETTERS,
    )

    override fun twoFingerAllowed(): Boolean = GestureRules.watches(gestureState())

    override fun twoFingerActions(): List<Pair<TwoFingerSwipe, String>> =
        GestureRules.offered(gestureActions, gestureState()).map { (swipe, action) -> swipe to getString(action.label) }

    /** A recognized two-finger swipe, or TalkBack's action for one on the space key: does what the person set it to. */
    override fun onTwoFingerSwipe(swipe: TwoFingerSwipe) {
        val action = gestureActions[swipe] ?: GestureAction.NONE
        if (action == GestureAction.NONE) return
        gestureLast = "${swipe.id}:${action.id}"
        if (!GestureRules.allowed(action, gestureState())) {
            gesturesRefused++
            return showGestureNotice(getString(R.string.gesture_not_here, getString(action.label)))
        }
        gesturesRun++
        runGestureAction(action)
    }

    /** Runs [action] for a two-finger swipe or a tool. The caller has checked [GestureRules.allowed]. */
    private fun runGestureAction(action: GestureAction) {
        emojiPopup.dismiss()
        hideNotice()
        // As before a key: what dictation heard goes in first.
        if (dictationSession.listening) stopDictationNow()
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.DELETE_WORD -> gestureDeleteWord()
            GestureAction.UNDO -> gestureUndoLast()
            GestureAction.CURSOR_START -> gestureMoveCaret(start = true)
            GestureAction.CURSOR_END -> gestureMoveCaret(start = false)
            GestureAction.SELECT_ALL -> gestureSelectAll()
            GestureAction.PASTE -> gesturePaste()
            GestureAction.HIDE_KEYBOARD -> { explicitHideRequests++; requestHideSelf(0) }
            GestureAction.EMOJI -> openPanel(KeyboardPanel.EMOJI)
            GestureAction.SAVED_TEXT -> openSavedPanel()
            GestureAction.SYMBOLS -> setMode(if (mode == KeyboardLayout.Mode.SYMBOLS || mode == KeyboardLayout.Mode.MORE_SYMBOLS)
                TypingRules.layoutFor(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT) else KeyboardLayout.Mode.SYMBOLS)
            GestureAction.CAPS_LOCK -> {
                onCapsLock()
                showGestureNotice(getString(if (capsLocked()) R.string.gesture_caps_on else R.string.gesture_caps_off))
            }
        }
    }

    /**
     * Removes the word before the caret the way a slide left from delete does, but in one verified replacement against
     * a fresh receipt. The word and the receipt after it are kept for Undo; deletes in a row are put back together.
     */
    private fun gestureDeleteWord() {
        drainPendingKeys()
        val previous = gestureUndo
        val generation = editorEdits.generation
        val initial = editorSnapshot() ?: return showGestureNotice(getString(R.string.gesture_check_text))
        val length = if (initial.selection.collapsed) CursorGestures.wordsLength(initial.before, 1) else 0
        // A word that may go on beyond what was read is not measured: the edit could cut it in two.
        if (length == 0 || length == initial.before.length && !initial.beforeComplete)
            return showGestureNotice(getString(R.string.gesture_nothing_to_delete))
        val deleted = initial.before.takeLast(length)
        recentCorrection.cancel()
        correctionOffer = null
        autoSpaced = false
        lastAutocorrect = null
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        personalLearning.reset(PersonalContextTrace.ResetReason.CURSOR)
        personalWordOwned = false
        val edit = WordEdits.Replacement(length, 0, "")
        if (applyWordReplacement(edit, precondition = initial) != edit) {
            scheduleRefresh()
            return showGestureNotice(getString(R.string.gesture_check_text))
        }
        val receipt = verifiedWordReceipt ?: return scheduleRefresh()
        // Whatever word is left at the caret was typed before this; adding to it is editing, not typing it fresh.
        revisitTrace(CorrectionDiagnostics.Revisit.SELECTION_DELETE)
        val merged = if (previous != null && previous.receipt == initial && previous.generation == generation) deleted + previous.deleted else deleted
        gestureUndo = GestureUndo(merged, receipt, generation)
        showGestureNotice(getString(R.string.gesture_deleted, shownText(merged)), undoable = true)
        updateShiftFromEditor()
        scheduleRefresh()
    }

    /** Puts back what delete-word gestures removed, only into exactly the text they left. */
    private fun undoGestureDelete() {
        val undo = gestureUndo ?: return
        gestureUndo = null
        hideGestureNotice()
        drainPendingKeys()
        val initial = editorSnapshot()
        if (initial == null || initial != undo.receipt || editorEdits.generation != undo.generation)
            return showGestureNotice(getString(R.string.gesture_check_text))
        val edit = WordEdits.Replacement(0, 0, undo.deleted)
        if (applyWordReplacement(edit, precondition = initial) != edit) {
            scheduleRefresh()
            return showGestureNotice(getString(R.string.gesture_check_text))
        }
        // Put back, not typed here: nothing learns from it, and a space right after never corrects it.
        revisitTrace(CorrectionDiagnostics.Revisit.UNDO)
        personalLearning.reset(PersonalContextTrace.ResetReason.PASTE)
        personalWordOwned = false
        appendLedger.clear()
        typedRun.reset()
        ownedSpacing.reset()
        showGestureNotice(getString(R.string.gesture_restored, shownText(undo.deleted)))
        updateShiftFromEditor()
        scheduleRefresh()
    }

    /**
     * The newest way back the keyboard holds: a gesture's delete, then what delete right after would undo (a
     * correction, emoji or swiped word), then a cleaned paste, then the five-second correction Undo.
     */
    private fun gestureUndoLast(editorFallback: Boolean = false) {
        if (gestureUndo != null) return undoGestureDelete()
        val before = editorSnapshot()
        when {
            lastAutocorrect != null -> if (undoAutocorrect()) revisitTrace(CorrectionDiagnostics.Revisit.UNDO)
            cleanedPaste != null -> undoLinkCleaning()
            recentCorrection.hasUndo(SystemClock.uptimeMillis()) -> undoRecentCorrection()
            editorFallback -> return editorHistory(StripTools.EDITOR_UNDO)
            else -> return showGestureNotice(getString(R.string.gesture_nothing_to_undo))
        }
        scheduleRefresh()
        // Said only when the text did change: an undo whose text had moved on leaves it alone.
        showGestureNotice(getString(if (editorSnapshot() != before) R.string.gesture_undone else R.string.gesture_nothing_to_undo))
    }

    /** The caret goes to an end of the field, as a person moving it by hand would put it: the word there is revisited. */
    private fun gestureMoveCaret(start: Boolean) {
        val ic = currentInputConnection ?: return
        drainPendingKeys()
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.SELECTION)
        caretLeftOwnPosition()
        // The start is offset 0. The end is the field's length where the editor gives its whole text; otherwise, and
        // always in a password field, Ctrl+End asks the editor to move there itself.
        if (start) ic.setSelection(0, 0) else fieldLength()?.let { ic.setSelection(it, it) } ?: sendControlKey(KeyEvent.KEYCODE_MOVE_END)
        showGestureNotice(getString(if (start) R.string.gesture_cursor_start else R.string.gesture_cursor_end))
        scheduleRefresh()
    }

    private fun gestureSelectAll() {
        val ic = currentInputConnection ?: return
        drainPendingKeys()
        pendingPeriod = null
        pendingPeriodPersonalContext = emptyList()
        correctionDiagnostics.revisit(CorrectionDiagnostics.Revisit.SELECTION)
        caretLeftOwnPosition()
        // As for the caret's end: the field's length where it is given, else the editor's own Select all.
        fieldLength()?.let { ic.setSelection(0, it) } ?: ic.performContextMenuAction(android.R.id.selectAll)
        showGestureNotice(getString(R.string.gesture_selected_all))
        scheduleRefresh()
    }

    /** The paste chip's paste, of whatever the clipboard holds now, however old. */
    private fun gesturePaste() {
        val clip = freshClipText(fresh = false) ?: return showGestureNotice(getString(R.string.gesture_nothing_to_paste))
        val stamp = clipStamp()
        if (insertPasted(clip.text) != VerifiedWordTransaction.Insertion.REJECTED) {
            pastedClipStamp = stamp
            pasteClip = null
        }
    }

    /**
     * The field's length, when the editor hands over its whole text from the start, so a caret or selection can be set
     * exactly; null where it gives only part, gives nothing, or must not be read (a password field).
     */
    private fun fieldLength(): Int? {
        if (inputIsSensitive) return null
        val extracted = currentInputConnection?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = FIELD_LENGTH_CHARS }, 0) ?: return null
        val text = extracted.text ?: return null
        if (extracted.partialStartOffset >= 0 || extracted.startOffset != 0 || text.length >= FIELD_LENGTH_CHARS) return null
        return text.length
    }

    private fun sendControlKey(code: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
    }

    private fun shownText(text: String) = text.trim().replace('\n', '⏎')

    private val hideGestureMessage = Runnable { hideGestureNotice() }

    /**
     * Says what a gesture did in the correction row, with Undo when [undoable], for the notice time, so a swipe that
     * did something unexpected is seen. A panel or the proofread row has the strip; the field itself still shows it.
     */
    private fun showGestureNotice(message: String, undoable: Boolean = false) {
        if (panel != null || proofreading || proposal != null || proposalRow?.visibility == View.VISIBLE) return
        hideNotice()
        hideLinkNotice()
        gestureNotice = true
        correctionText?.text = message
        correctionText?.contentDescription = message
        correctionAction?.text = getString(R.string.correction_undo)
        correctionAction?.contentDescription = getString(R.string.correction_undo)
        correctionAction?.visibility = if (undoable) View.VISIBLE else View.GONE
        correctionRow?.visibility = View.VISIBLE
        updateSlotVisibility()
        updateLead()
        handler.removeCallbacks(hideCorrectionMessage)
        handler.removeCallbacks(hideGestureMessage)
        handler.postDelayed(hideGestureMessage, noticeMillis())
    }

    /** Ends a gesture's notice: its time ran out, a key was pressed, a panel or the proofread row took the strip. */
    private fun hideGestureNotice() {
        if (!gestureNotice) return
        gestureNotice = false
        handler.removeCallbacks(hideGestureMessage)
        correctionAction?.visibility = View.VISIBLE
        hideCorrectionFeedback()
    }

    // endregion

    // region Voice input

    /** The keyboard's window has gone; nothing is listening after that. */
    override fun onWindowHidden() {
        windowsHidden++
        cancelSwipeWork()
        stopDictationNow(commit = false)
        super.onWindowHidden()
    }

    override fun onWindowShown() {
        windowsShown++
        super.onWindowShown()
    }

    /** The editor has finished for good, whether or not its view was showing. */
    override fun onFinishInput() {
        clearCharacterTools()
        cancelSwipeWork()
        stopDictationNow(commit = false)
        super.onFinishInput()
    }

    private fun updateVoiceAvailability(inputType: Int) {
        voiceFieldType = inputType
        val enabled = KeyboardSettings.voiceInput(this)
        val offer = if (enabled) VoiceDictation.offer(this) else VoiceDictation.Offer(VoiceRoute.NONE, null)
        voiceRoute = offer.route
        voiceInputMethod = offer
        voiceOffered = VoiceInputRules.offered(enabled, voiceRoute, inputIsSensitive,
            rawKeys = inputType and InputType.TYPE_MASK_CLASS == 0,
            letters = TypingRules.layoutFor(inputType) == KeyboardLayout.Mode.LETTERS)
        updateVoiceButton()
        if (layoutVoiceKey != voiceOnKeys()) applyLayout()
    }

    private fun updateVoiceButton() {
        val listening = dictationSession.listening
        voiceButton?.let { button ->
            // Voice starts from the tools row, or from the strip or the keys where Settings puts it ([VoiceKey]); the
            // strip's mic also shows while listening, as the way to stop.
            val shown = listening || (voiceKey == VoiceKey.STRIP && emojiQuery == null)
            button.visibility = if (voiceOffered && shown && panel == null) View.VISIBLE else View.GONE
            button.isSelected = listening
            button.contentDescription = getString(if (listening) R.string.voice_stop else R.string.voice_input)
        }
        stripStatus?.let { voiceText?.text = it }
        // A notice has the row until it ends. What dictation hears stays current underneath.
        val said = stripNotice
        noticeText?.text = said
        noticeText?.visibility = if (said != null) View.VISIBLE else View.GONE
        voiceText?.visibility = if (said != null) View.GONE else View.VISIBLE
        voiceRow?.visibility = if ((listening || stripStatus != null || said != null) && panel == null) View.VISIBLE else View.GONE
        updateSlotVisibility()
    }

    private fun onVoiceTapped() {
        if (dictationSession.listening) return finishDictation()
        when (voiceRoute) {
            VoiceRoute.ON_DEVICE -> startDictation()
            VoiceRoute.HANDOFF -> handOffToVoiceInput()
            VoiceRoute.NONE -> Unit
        }
    }

    private fun startDictation() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // A keyboard has no window Android will show a permission prompt over; a bare activity carries it.
            startActivity(android.content.Intent(this, VoicePermissionActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        drainPendingKeys()
        proofreadJob?.cancel()
        dismissProposal()
        hideCorrectionFeedback()
        hideNotice()
        val engine = dictation ?: VoiceDictation(this, dictationEvents).also { dictation = it }
        if (!dictationSession.start()) return
        dictationFinishing = false
        if (!engine.listen(maskOffensiveWords = !profanityAllowed, language = speechLanguage())) {
            dictationSession.stop()
            showProposalMessage(getString(R.string.voice_unavailable), VOICE_MESSAGE_MS)
            return
        }
        voiceText?.text = getString(R.string.voice_listening)
        updateVoiceButton()
        voiceButton?.announceForAccessibility(getString(R.string.voice_listening))
    }

    private val dictationEvents = object : VoiceDictation.Events {
        override fun onPartial(text: String) {
            dictationSession.partial(text)
            if (dictationSession.listening) voiceText?.text = dictationSession.partial.ifEmpty { getString(R.string.voice_listening) }
        }

        override fun onResult(text: String) = onDictationStep(dictationSession.result(text))
        override fun onError(code: Int) = onDictationStep(dictationSession.error(code))
    }

    private fun onDictationStep(step: DictationSession.Step) {
        when (step) {
            is DictationSession.Step.Insert -> {
                handler.removeCallbacks(dictationFinishTimeout)
                commitDictated(step.text, dictation?.formatted == true)
                // The mic key was tapped to stop, and this was the last of what it heard.
                if (dictationFinishing) {
                    dictationLastEnd = "USER"
                    endDictation(null)
                } else {
                    voiceText?.text = getString(R.string.voice_listening)
                    handler.removeCallbacks(dictationListenAgain)
                    handler.postDelayed(dictationListenAgain, DICTATION_RESTART_MS)
                }
            }
            is DictationSession.Step.Stop -> {
                handler.removeCallbacks(dictationFinishTimeout)
                // After words have gone in, a pause or a recognizer that is slow to listen again is just the end.
                val quiet = dictationFinishing || dictationSession.inserted > 0
                dictationLastEnd = "${step.reason} code=${step.code}"
                endDictation(when (step.reason) {
                    DictationSession.End.SILENCE -> if (quiet) null else getString(R.string.voice_nothing_heard)
                    DictationSession.End.PERMISSION -> getString(R.string.voice_permission_needed)
                    DictationSession.End.LANGUAGE -> getString(when (language) {
                        LanguageProfile.SPANISH -> R.string.voice_language_missing_spanish
                        LanguageProfile.PORTUGUESE -> R.string.voice_language_missing_portuguese
                        else -> R.string.voice_language_missing
                    })
                    DictationSession.End.BUSY -> if (quiet) null else getString(R.string.voice_busy)
                    DictationSession.End.FAILED -> getString(R.string.voice_failed, step.code)
                })
            }
            DictationSession.Step.Ignore -> Unit
        }
    }

    /**
     * Dictated text goes in the way a paste does: nothing is learned from it, it writes no typing-log row, and a
     * space right after it never autocorrects its last word.
     */
    private fun commitDictated(text: String, formatted: Boolean) {
        val fitted = DictationText.insertion(currentContext(), text, formatted)
        if (fitted.isNotEmpty()) insertFromPanel(fitted)
    }

    /** The mic key, tapped while listening: take the recognizer's result for what it has heard, then stop. */
    private fun finishDictation() {
        if (!dictationSession.listening || dictationFinishing) return
        dictationFinishing = true
        handler.removeCallbacks(dictationListenAgain)
        dictation?.finish()
        handler.removeCallbacks(dictationFinishTimeout)
        handler.postDelayed(dictationFinishTimeout, DICTATION_FINISH_MS)
    }

    /** Ends at once. With [commit], the words heard so far go into the field first. */
    private fun stopDictationNow(commit: Boolean = true) {
        handler.removeCallbacks(dictationFinishTimeout)
        if (!dictationSession.listening) return
        val pending = dictationSession.stop()
        dictationLastEnd = if (commit) "KEY" else "FIELD"
        dictation?.close()
        if (commit && pending.isNotEmpty()) commitDictated(pending, formatted = false)
        endDictation(null)
    }

    private fun endDictation(message: String?) {
        handler.removeCallbacks(dictationListenAgain)
        dictationSession.stop()
        // Each dictation gets its own recognizer, so nothing a finished one still had in flight can reach the next.
        dictation?.close()
        dictationFinishing = false
        updateVoiceButton()
        // A message row announces itself; otherwise TalkBack says once that listening has ended.
        if (message != null) showProposalMessage(message, VOICE_MESSAGE_MS) else if (viewShown) {
            toolsHandle?.announceForAccessibility(getString(R.string.voice_stopped))
            scheduleRefresh()
        }
    }

    private fun handOffToVoiceInput() {
        val method = voiceInputMethod?.inputMethod ?: return
        val subtype = voiceInputMethod?.voiceSubtype
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // An input method can offer typing and voice; ask for its voice subtype, not whatever it showed last.
        runCatching { if (subtype != null) switchInputMethod(method.id, subtype) else switchInputMethod(method.id) }
            .onFailure { showProposalMessage(getString(R.string.voice_handoff_failed), VOICE_MESSAGE_MS) }
    }

    // endregion

    /**
     * Android asks where a password manager's suggestions may go for the field being focused. Null (the
     * setting is off) leaves the manager to show its own dropdown under the field, as it does for a keyboard
     * without this support.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): InlineSuggestionsRequest? {
        val chips = inlineAutofill ?: return null
        inlineTyped = false
        if (!KeyboardSettings.passwordManagerSuggestions(this)) {
            chips.clear()
            return null
        }
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return chips.request(uiExtras, dp(INLINE_CHIP_HEIGHT_DP), dark, getColor(R.color.kb_on_key), getColor(R.color.kb_hint))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean =
        inlineAutofill?.response(response) ?: false

    /**
     * The chips changed: into the strip's row, and the strip decides whether that row shows; or, while Passwords &
     * cards is open, into its panel. The tile's dot follows either way.
     */
    private fun attachInlineChips() {
        val covered = inlineRow?.visibility == View.VISIBLE
        if (panel == KeyboardPanel.PASSWORDS) renderPasswordsPanel() else inlineRow?.let { inlineAutofill?.attach(it) }
        updateSlotVisibility()
        updatePasswordsDot()
        // The last offer was logged as covered by chips or as seen, and that just changed: a tap on a word that
        // has only now come into view must not be logged against an offer marked unseen.
        if (covered != (inlineRow?.visibility == View.VISIBLE)) scheduleRefresh()
    }

    /** A chip was tapped and Android is filling the field: if Passwords & cards held it, the keys come back. */
    private fun inlineChipTapped() {
        if (panel != KeyboardPanel.PASSWORDS) return
        closePanel()
        TalkBackFocus.moveTo(toolsHandle)
    }

    /**
     * Passwords & cards, while it's open: the chips drawn so far, moved here from the strip, or a sentence for why
     * there are none, and a button for the manager's app. Counts, the setting and the manager's name only: what a chip
     * says never reaches the keyboard.
     */
    private fun renderPasswordsPanel() {
        if (panel != KeyboardPanel.PASSWORDS) return
        val view = passwordsPanel ?: return
        val chips = inlineAutofill
        chips?.attach(view.chips)
        val manager = passwordManager()
        val touch = getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        val state = PasswordsTile.state(chips?.drawn ?: 0, chips?.count ?: 0, KeyboardSettings.passwordManagerSuggestions(this), touch, manager)
        val button = when (PasswordsTile.opens(manager, managerLaunch(manager) != null)) {
            PasswordsTile.Opens.MANAGER_APP -> managerName(manager)?.let { getString(R.string.passwords_open, it) }
                ?: getString(R.string.passwords_open_manager)
            PasswordsTile.Opens.AUTOFILL_SETTINGS -> getString(R.string.passwords_autofill_settings)
        }
        view.show(state, button)
    }

    /** The autofill service's package, from the system's own setting; null when none is selected. */
    private fun passwordManager(): String? = PasswordsTile.managerPackage(
        runCatching { android.provider.Settings.Secure.getString(contentResolver, AUTOFILL_SERVICE_SETTING) }.getOrNull())

    /** The manager's own app, when it has one to open (the manifest's queries make autofill services visible). */
    private fun managerLaunch(manager: String?): android.content.Intent? =
        manager?.let { runCatching { packageManager.getLaunchIntentForPackage(it) }.getOrNull() }

    private fun managerName(manager: String?): String? = manager?.let {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    /**
     * The panel's button: the manager's app, or where it has none, Android's choice of autofill service, which changes
     * nothing until the person picks one there. It never asks for a fill. The panel closes first, as for Contacts.
     */
    private fun openPasswordManager() {
        val manager = passwordManager()
        val launch = managerLaunch(manager)
        val intent = if (PasswordsTile.opens(manager, launch != null) == PasswordsTile.Opens.MANAGER_APP && launch != null) launch
            else android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, android.net.Uri.parse("package:$packageName"))
        closePanel()
        TalkBackFocus.moveTo(toolsHandle)
        runCatching { startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { showNotice(NoticeRules.Message.NOT_OPENED) }
    }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        writer.println("language=${language.code} offered=${KeyboardSettings.languages(this).joinToString(",") { it.code }} words=${dictionary.size} lexicon=${lexicon.size} prior=${corpusPrior != null} ready=$dictionaryReady")
        writer.println("swipe enabled=${KeyboardSettings.experimentalSwipe(this)} last_ms=$swipeLastMs start=$swipeStartStatus refused=$swipeRefusals allowed=${swipeAllowedNow()} ready=$dictionaryReady field=$fieldWantsSuggestions learning=$learningAllowed shown=$viewShown")
        val summary = correctionDiagnostics.keyCpuSummary()
        writer.println("LocalType correction counters=${correctionDiagnostics.snapshot()}")
        writer.println("LocalType revisit counters=${correctionDiagnostics.revisitSnapshot()}")
        writer.println("added_correction_key_cpu samples=${summary.first} p95_us=${summary.second / 1000} max_us=${summary.third / 1000}")
        writer.println("held_keys replayed=$heldKeysReplayed resent=$heldKeysResent sent=$heldKeysSent dropped=$heldKeysDropped held=${pendingKeys.size}")
        writer.println("oneHanded mode=$oneHanded rail=${oneHandedRail?.visibility == View.VISIBLE}")
        writer.println("window fullscreen=$isFullscreenMode heightDp=${resources.configuration.screenHeightDp} widthDp=${resources.configuration.screenWidthDp} keysDp=${keyPlacement?.widthDp ?: -1} keysAt=${keyPlacement?.side ?: "none"} wideKeys=$wideKeys")
        writer.println("input_view starts=$inputViewStarts restarts=$inputViewRestarts finishes=$inputViewFinishes finishing=$finishingInputViews windowShown=$windowsShown windowHidden=$windowsHidden explicitHides=$explicitHideRequests visible=$isInputViewShown restartBlocked=$compatibilityEditorRestarted boundaryRecoveries=$restartBoundaryRecoveries")
        writer.println("contacts allowed=${ContactsReader.granted(this)} read=${contacts?.size ?: -1} names=${contactNames.size}")
        writer.println("voice route=$voiceRoute offered=$voiceOffered listening=${dictationSession.listening} inserted=${dictationSession.inserted} lastEnd=$dictationLastEnd")
        writer.println("notices shown=$noticesShown place=$noticePlace showing=${notice != null}")
        // Counts and the last swipe's direction and action only; never text.
        writer.println("gestures enabled=$twoFingerGestures watching=${twoFingerAllowed()} ${keyboardView?.twoFingerCounts} run=$gesturesRun refused=$gesturesRefused last=$gestureLast undo=${gestureUndo != null}")
        // Counts only. The chips are drawn by Android in another process; the keyboard has nothing of theirs to print.
        inlineAutofill?.let { writer.println("inline requests=${it.requests} responses=${it.responses} inflated=${it.inflated} chips=${it.count} unpinned=${it.unpinned} drawn=${it.drawn} shown=${inlineRow?.visibility == View.VISIBLE} row=${inlineRow?.width}x${inlineRow?.height} at=${it.geometry()} panel=${panel == KeyboardPanel.PASSWORDS} panelChips=${passwordsPanel?.chips?.flow?.childCount ?: 0}") }
        // Counts only: no host and no saved text is ever printed.
        writer.println("expansions hosts=${rememberedDomains?.entries?.size} offered=${rememberedDomains?.offered?.size} shortcuts=${savedTexts?.codes?.size} " +
            "endings=${DomainStore.tlds.size} remembering=$rememberDomains field=$hostField showing=${expansion?.kind} slot=$expansionSlot " +
            "chip=${hostChip?.visibility == View.VISIBLE} drawn=$expansionsShown taken=$expansionsTaken links=$linksCleaned run=${typedRun.length}")
        for (stage in CorrectionDiagnostics.Stage.entries) {
            val timing = correctionDiagnostics.stageCpuSummary(stage)
            writer.println("correction_stage=$stage samples=${timing.first} p95_us=${timing.second / 1000} max_us=${timing.third / 1000}")
        }
    }

    /** The text before the cursor, or null when the read fails (a retired connection, or an editor that can't answer). */
    private fun rawContext(): String? = currentInputConnection?.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString()

    private fun currentContext(): String = rawContext().orEmpty()

    private fun resetNeuralSession() {
        neuralState.reset()
        neuralWorker?.cancel(reset = true)
    }

    private fun canPredictNeural(): Boolean = neuralEnabled && neuralReady && !NeuralPredictionWorker.unavailable && learningAllowed &&
        !inputIsSensitive && suggestionsEnabled && (fieldWantsSuggestions || addressBar) && panel == null && emojiQuery == null &&
        dictionaryReady && publishedWithProfanity == profanityAllowed && proposalRow?.visibility != View.VISIBLE && viewShown && !destroyed

    /** A single bounded editor read; sentence trimming and tokenization happen on the worker. */
    private fun currentNeuralContext(): String? = currentInputConnection
        ?.getTextBeforeCursor(NeuralContext.MAX_READ_CHARS, 0)?.toString()

    private fun loadNeuralModel() {
        val worker = neuralWorker ?: NeuralPredictionWorker(this, handler).also { neuralWorker = it }
        worker.load { ready ->
            if (destroyed) return@load
            neuralReady = ready
            if (!ready) disableNeuralPredictions()
            scheduleRefresh()
        }
    }

    private fun requestNeuralPrediction(context: String) {
        if (!canPredictNeural()) return
        val request = neuralState.request(context) ?: return
        neuralWorker?.predict(request) { words ->
            // Forget learned words invalidates the session before an answer can enter live state.
            syncLearnedState()
            if (words == null) {
                disableNeuralPredictions()
                scheduleRefresh()
                return@predict
            }
            if (!canPredictNeural()) {
                neuralState.abandon(request)
                return@predict
            }
            val current = currentNeuralContext()
            if (current != null && neuralState.complete(request, current, words)) scheduleRefresh()
            else neuralState.abandon(request)
        }
    }

    private fun disableNeuralPredictions() {
        neuralReady = false
        neuralEnabled = false
        resetNeuralSession()
        if (logAllowed) {
            PredictionLog.endField()
            openLoggedField(restart = false)
        }
    }

    // endregion

    /** Every path that makes the current word no longer the keyboard's own, counted content-free for dumpsys. */
    private fun revisitTrace(reason: CorrectionDiagnostics.Revisit) {
        ownedSpacing.reset()
        correctionDiagnostics.revisit(reason)
        trace.revisit()
    }

    /** Debug builds only: field flags and correction decisions, never typed text. */
    private inline fun diag(message: () -> String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("LocalTypeDiag", message())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * The process's one copy of the corpus prior, so a new service instance (the system can
         * destroy this one and bind another in the same process) neither reads nor hashes the asset
         * again. Written only on the main thread, in the first dictionary post that publishes it;
         * [processPriorSettled] keeps a failed read's null for the rest of the process too.
         */
        @Volatile private var processPrior: CorpusPrior? = null
        /** Other languages' priors by asset, read once per process like [processPrior]. */
        private val languagePriors = java.util.concurrent.ConcurrentHashMap<String, CorpusPrior>()
        @Volatile private var processPriorSettled = false

        private const val CANDIDATE_COUNT = 3
        private const val MAX_CONTEXT_CHARS = 280
        /** How old a contacts read may be when the panel opens, and when a field starts. */
        private const val CONTACTS_PANEL_STALE_MS = 60_000L
        private const val CONTACTS_FIELD_STALE_MS = 10 * 60_000L
        /** Enough text before the caret to see "¿" and the sentence end before it. */
        private const val OPENING_MARK_CONTEXT = 64
        /** How much of the token after the caret an address bar reads to tell a word from an address. */
        private const val ADDRESS_TOKEN_CHARS = 64
        private const val DOUBLE_SPACE_MS = 700L
        /** A dictionary word at least this common is everyday lowercase; its capitals never become the person's spelling. */
        private const val COMMON_WORD_SCORE = 40
        /** Both words of a dot-for-space offer must be at least this common ([WordDictionary.score]). */
        private const val DOT_SPACE_MIN_SCORE = 40
        private const val DELAYED_SELECTION_CHECK_MS = 50L
        private const val CAPS_LOCK_TAP_MS = 350L
        private const val MODERN_ASSET = "words_en_modern.txt"
        private const val EMOJI_ASSET = "emoji_en.txt"
        private const val EMOJI_VARIANTS = 8
        private const val MAX_PASTE_CHARS = SavedTexts.MAX_CHARS
        private const val CLIP_FRESH_MS = 2 * 60_000L
        /** How long the strip says a private copy wasn't saved. */
        private const val PRIVATE_COPY_NOTE_MS = 2_500L
        private const val MANUAL_LOAD_WAIT_MS = 60_000L
        private const val LOOKAHEAD_CANDIDATES = 3
        private const val LOADING_RETRY_MS = 2_000L
        private const val PROOFREAD_CONTEXT_CHARS = 600
        /** How long a tap on the mic key waits for the recognizer's last result before taking the partial text. */
        private const val DICTATION_FINISH_MS = 1_500L
        private const val DICTATION_RESTART_MS = 60L
        /** Enough text either side of the caret for any slide a thumb can make along the space bar. */
        private const val CARET_READ_CHARS = 96
        /** The longest field whose end a gesture finds from its text; past it the editor is asked to move there itself. */
        private const val FIELD_LENGTH_CHARS = 65_536
        /** How far back a delete slide can reach. */
        private const val DELETE_SLIDE_CHARS = 400
        private const val VOICE_MESSAGE_MS = 4_000L
        /** A password manager's chip in the 48 dp strip, with room above and below it. */
        private const val INLINE_CHIP_HEIGHT_DP = 36
        /** The secure setting naming the autofill service, a flattened component. Readable by apps, though not in the SDK. */
        private const val AUTOFILL_SERVICE_SETTING = "autofill_service"
        /** How long the strip offers to put a cleaned link back. */
        private const val LINK_NOTICE_MS = 4_000L
        /** How long a notice stays, the same as a cleaned link's offer and a voice message. */
        private const val NOTICE_MS = 4_000L
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33), used by name so older SDKs also honor it. */
        private const val CLIP_EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
