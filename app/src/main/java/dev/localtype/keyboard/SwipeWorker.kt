package dev.localtype.keyboard

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.security.MessageDigest

/** One decoder thread, at most one running request and one pending request. No editor/context input. */
class SwipeWorker(context: Context) {
    private val assets = context.applicationContext.assets
    private val thread = HandlerThread("localtype-swipe").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var decoder: SwipeDecoder? = null
    private var failed = false

    fun decode(path: SwipePath, words: WordDictionary, callback: (List<String>, Long) -> Unit) {
        cancel()
        val request = generation
        worker.post {
            if (generation != request) return@post
            val started = android.os.SystemClock.uptimeMillis()
            val answer = try {
                if (failed) emptyList() else {
                    val engine = decoder ?: run {
                        val bytes = assets.open(FILE).use { it.readBytes() }
                        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(digest == SHA256) { "Swipe model digest mismatch" }
                        SwipeDecoder(SwipeModel.read(bytes.inputStream()), GestureIndex(words.allWords().filter {
                            it.length in 1..32 && it.all { c -> c in 'a'..'z' || c == '\'' || c == '-' }
                        }.map { it to words.score(it) })).also { decoder = it }
                    }
                    if (generation != request) emptyList() else
                        engine.decode(path.x, path.y, path.time, path.aspect, limit = 12).map { it.word }
                }
            } catch (_: Exception) { failed = true; emptyList() }
            val duration = android.os.SystemClock.uptimeMillis() - started
            main.post { if (generation == request) callback(answer, duration) }
        }
    }

    fun cancel() { generation++; worker.removeCallbacksAndMessages(null) }
    fun close() { cancel(); thread.quitSafely() }

    companion object {
        const val FILE = "swipe_en.bin"
        const val SHA256 = "665f2879a6294dddfa4487e25ac4504eeea21934645a542addd0f134e6b563e0"
    }
}
