package dev.localtype.keyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.annotation.RequiresApi

/**
 * Android's on-device speech recognizer for one keyboard. Only the on-device recognizer is ever created: the
 * default one may send audio to a server (VoiceInputTest fails if it appears in the sources). Main thread only.
 */
class VoiceDictation(private val context: Context, private val events: Events) {
    interface Events {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(code: Int)
    }

    private var recognizer: SpeechRecognizer? = null
    /** Which recognizer's callbacks count. [close] moves on, so a closed one's late callbacks are dropped. */
    private var current = 0

    /** From Android 13 the recognizer can punctuate and capitalize its own results. */
    val formatted: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Hears one utterance in [language] (a BCP 47 tag). False when the phone has no on-device recognizer to ask. */
    fun listen(maskOffensiveWords: Boolean, language: String = LANGUAGE): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val active = recognizer ?: create() ?: return false
        recognizer = active
        return runCatching { active.startListening(request(maskOffensiveWords, language)) }.isSuccess
    }

    /** Asks for the result of what has been heard so far. */
    fun finish() {
        runCatching { recognizer?.stopListening() }
    }

    /** Ends this dictation's recognizer. Nothing it still had in flight is delivered, and the next [listen] makes a new one. */
    fun close() {
        current++
        val closing = recognizer ?: return
        recognizer = null
        runCatching { closing.cancel() }
        runCatching { closing.destroy() }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun create(): SpeechRecognizer? {
        val created = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return null
        created.setRecognitionListener(Listener(++current))
        return created
    }

    private fun request(maskOffensiveWords: Boolean, language: String): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            intent.putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
            intent.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, maskOffensiveWords)
        }
        return intent
    }

    /** One recognizer's callbacks; they count only while that recognizer is the current one. */
    private inner class Listener(private val id: Int) : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            if (id == current) best(partialResults)?.let(events::onPartial)
        }

        override fun onResults(results: Bundle?) {
            if (id == current) events.onResult(best(results).orEmpty())
        }

        override fun onError(error: Int) {
            if (id == current) events.onError(error)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun best(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** What this phone offers for voice input, and the other app's voice input method and subtype when that is the route. */
    data class Offer(val route: VoiceRoute, val inputMethod: InputMethodInfo?, val voiceSubtype: InputMethodSubtype? = null)

    companion object {
        /** English dictation; another language asks for its own ([LanguageProfile.speechTag]). */
        const val LANGUAGE = "en-US"
        private const val VOICE_MODE = "voice"

        fun available(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

        fun offer(context: Context): Offer {
            val onDevice = available(context)
            val other = if (onDevice) null else voiceInputMethod(context)
            val subtype = other?.let { method -> (0 until method.subtypeCount).map(method::getSubtypeAt).firstOrNull { it.mode == VOICE_MODE } }
            return Offer(VoiceInputRules.route(Build.VERSION.SDK_INT, onDevice, other?.id), other, subtype)
        }

        /** An enabled input method from another app that declares a voice subtype. */
        private fun voiceInputMethod(context: Context): InputMethodInfo? = runCatching {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.enabledInputMethodList.firstOrNull { method ->
                method.packageName != context.packageName &&
                    (0 until method.subtypeCount).any { method.getSubtypeAt(it).mode == VOICE_MODE }
            }
        }.getOrNull()
    }
}
