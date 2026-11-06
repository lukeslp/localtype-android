package dev.localtype.keyboard

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import dev.localtype.modelkit.GemmaEngine
import dev.localtype.modelkit.ModelHost
import dev.localtype.modelkit.ModelProtocol
import dev.localtype.modelkit.ModelWorkQueue.Lane

/**
 * The keyboard's door to Gemma in the separate `:model` process. The keyboard talks to it over a
 * [Messenger]; if this process crashes or is killed for memory, typing carries on with
 * dictionary suggestions and the client reconnects later. The engine and its queue live in
 * modelkit's [ModelHost], which the door LocalLauncher uses (modelkit's SharedModelService) shares.
 */
class ModelInferenceService : Service() {
    private lateinit var host: ModelHost
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper(), ::handle)) }

    override fun onCreate() {
        super.onCreate()
        host = ModelHost.acquire(this)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        ModelHost.release(host)
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean {
        if (message.what == MSG_RELOAD) {
            // One-way command: it carries no reply address.
            host.reload()
            return true
        }
        val replyTo = message.replyTo ?: return true
        val id = message.arg1
        when (message.what) {
            MSG_STATUS -> {
                val status = host.status(load = true)
                ModelHost.reply(replyTo, MSG_STATUS, id, Bundle().apply { putString(KEY_STATUS, status.name) })
            }
            MSG_PROOFREAD -> {
                val text = message.data.getString(KEY_TEXT).orEmpty()
                host.answer(Lane.KEYBOARD, RemoteModelClient.PROOFREAD_TIMEOUT_MS, replyTo, MSG_PROOFREAD, id) { engine ->
                    val started = SystemClock.elapsedRealtime()
                    val result = KeyboardGemma.readProofread(text, engine.run(KeyboardGemma.proofread(text)))
                    Log.i(TAG, "Proofread ${text.length} chars in ${SystemClock.elapsedRealtime() - started} ms")
                    Bundle().apply { putString(KEY_RESULT, result) }
                }
            }
            MSG_GENERATE -> {
                val prompt = message.data.getString(KEY_TEXT).orEmpty()
                host.answer(Lane.CHAT, RemoteModelClient.GENERATE_TIMEOUT_MS, replyTo, MSG_GENERATE, id) { engine ->
                    Bundle().apply { putString(KEY_RESULT, engine.run(KeyboardGemma.chat(prompt))) }
                }
            }
            MSG_PREDICT -> {
                val text = message.data.getString(KEY_TEXT).orEmpty()
                val limit = message.data.getInt(KEY_LIMIT, 3)
                val avoid = message.data.getStringArrayList(KEY_AVOID).orEmpty()
                host.answer(Lane.KEYBOARD, ModelRequestGate.REQUEST_TIMEOUT_MS, replyTo, MSG_PREDICT, id) { engine ->
                    val started = SystemClock.elapsedRealtime()
                    val words = KeyboardGemma.readPrediction(engine.run(KeyboardGemma.predict(text, limit, avoid)), limit, avoid)
                    Log.i(TAG, "Generated ${words.size} words in ${SystemClock.elapsedRealtime() - started} ms")
                    Bundle().apply { putStringArrayList(KEY_WORDS, ArrayList(words)) }
                }
            }
        }
        return true
    }

    private fun GemmaEngine.run(ask: KeyboardGemma.Ask): String? = generate(ask.prompt, ask.sampling, ask.maxTokens)

    companion object {
        private const val TAG = "LocalTypeModel"
        const val LOAD_TIMEOUT_MS = ModelHost.LOAD_TIMEOUT_MS
        const val MSG_STATUS = ModelProtocol.MSG_STATUS
        const val MSG_PREDICT = ModelProtocol.MSG_PREDICT
        const val MSG_RELOAD = ModelProtocol.MSG_RELOAD
        const val MSG_PROOFREAD = ModelProtocol.MSG_PROOFREAD
        const val MSG_GENERATE = ModelProtocol.MSG_GENERATE
        const val KEY_RESULT = ModelProtocol.KEY_RESULT
        const val KEY_STATUS = ModelProtocol.KEY_STATUS
        const val KEY_TEXT = ModelProtocol.KEY_TEXT
        const val KEY_LIMIT = ModelProtocol.KEY_LIMIT
        const val KEY_WORDS = ModelProtocol.KEY_WORDS
        const val KEY_AVOID = ModelProtocol.KEY_AVOID
        const val KEY_BUSY = ModelProtocol.KEY_BUSY
    }
}
