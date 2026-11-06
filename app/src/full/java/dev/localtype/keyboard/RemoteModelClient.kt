package dev.localtype.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The keyboard's side of [ModelInferenceService]. Binds on first use, unbinds after a quiet
 * spell so the model's memory is returned, and stops retrying if the model process keeps dying.
 */
internal class RemoteModelClient(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val ids = AtomicInteger()
    @Volatile private var service: Messenger? = null
    /** Completed when the service connects, so the first request after binding isn't dropped. */
    @Volatile private var connected = CompletableDeferred<Unit>()
    @Volatile private var bound = false
    @Volatile private var lastUse = 0L
    /** When true the idle unbind is skipped, so the model stays loaded between uses. */
    @Volatile var keepLoaded = false
        set(value) {
            val turnedOff = field && !value
            field = value
            // Turning it off starts the idle countdown now rather than at the next scheduled check.
            if (turnedOff && bound) main.post { main.removeCallbacks(idleCheck); main.postDelayed(idleCheck, IDLE_UNBIND_MS) }
        }
    private val deaths = ArrayDeque<Long>()

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        pending.remove(message.arg1)?.complete(message.data ?: Bundle())
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            connected.complete(Unit)
        }

        override fun onBindingDied(name: ComponentName) {
            // The binding can't recover by itself; drop it so the next request binds afresh.
            main.post { unbind() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The model process died (crash or low-memory kill). Android reconnects automatically.
            service = null
            connected = CompletableDeferred()
            pending.values.forEach { it.complete(Bundle()) }
            pending.clear()
            synchronized(deaths) {
                val now = SystemClock.elapsedRealtime()
                deaths.addLast(now)
                while (deaths.isNotEmpty() && now - deaths.first() > CRASH_WINDOW_MS) deaths.removeFirst()
                if (deaths.size >= MAX_DEATHS) {
                    Log.w(TAG, "Model process died ${deaths.size} times; staying on dictionary suggestions")
                    main.post { unbind() }
                }
            }
        }
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastUse
            when {
                keepLoaded -> main.postDelayed(this, IDLE_UNBIND_MS)
                idle >= IDLE_UNBIND_MS -> unbind()
                // Check again when the quiet spell would be complete, not a full interval later.
                else -> main.postDelayed(this, (IDLE_UNBIND_MS - idle).coerceAtLeast(1_000L))
            }
        }
    }

    /** True after repeated crashes; the model is left alone until its files change or the keyboard process restarts. */
    val gaveUp: Boolean get() = synchronized(deaths) { deaths.size >= MAX_DEATHS }

    suspend fun status(): FoundationModelStatus {
        if (gaveUp) return FoundationModelStatus.UNAVAILABLE
        val reply = request(ModelInferenceService.MSG_STATUS, Bundle(), STATUS_TIMEOUT_MS)
            ?: return FoundationModelStatus.INITIALIZING
        return reply.getString(ModelInferenceService.KEY_STATUS)
            ?.let { runCatching { FoundationModelStatus.valueOf(it) }.getOrNull() }
            ?: FoundationModelStatus.INITIALIZING
    }

    suspend fun predict(text: String, limit: Int, avoid: List<String> = emptyList()): List<String> {
        if (gaveUp) return emptyList()
        val data = Bundle().apply {
            putString(ModelInferenceService.KEY_TEXT, text)
            putInt(ModelInferenceService.KEY_LIMIT, limit)
            putStringArrayList(ModelInferenceService.KEY_AVOID, ArrayList(avoid))
        }
        return request(ModelInferenceService.MSG_PREDICT, data, ModelRequestGate.REQUEST_TIMEOUT_MS)
            ?.getStringArrayList(ModelInferenceService.KEY_WORDS).orEmpty()
    }

    suspend fun proofread(text: String): String? {
        if (gaveUp) return null
        val data = Bundle().apply { putString(ModelInferenceService.KEY_TEXT, text) }
        return request(ModelInferenceService.MSG_PROOFREAD, data, PROOFREAD_TIMEOUT_MS)
            ?.getString(ModelInferenceService.KEY_RESULT)
    }

    /** A chat reply. Once it has started the worker can't cancel it, so a timed-out reply still finishes and is dropped. */
    suspend fun generate(prompt: String): String? {
        if (gaveUp) return null
        val data = Bundle().apply { putString(ModelInferenceService.KEY_TEXT, prompt) }
        return request(ModelInferenceService.MSG_GENERATE, data, GENERATE_TIMEOUT_MS)
            ?.getString(ModelInferenceService.KEY_RESULT)
    }

    /**
     * Tells a running model process that the files changed, and forgets earlier crashes so a
     * replaced or removed model gets a fresh start. Sends nothing if the process isn't running.
     */
    fun reload() {
        synchronized(deaths) { deaths.clear() }
        service?.let { runCatching { it.send(Message.obtain(null, ModelInferenceService.MSG_RELOAD)) } }
    }

    private suspend fun request(what: Int, data: Bundle, timeoutMs: Long): Bundle? {
        lastUse = SystemClock.elapsedRealtime()
        ensureBound()
        val target = service ?: withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }?.let { service } ?: return null
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<Bundle>()
        pending[id] = deferred
        return try {
            target.send(Message.obtain(null, what, id, 0).apply {
                this.data = data
                replyTo = replies
            })
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            null
        } finally {
            pending.remove(id)
        }
    }

    @Synchronized
    private fun ensureBound() {
        if (bound) return
        bound = context.bindService(Intent(context, ModelInferenceService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (bound) main.postDelayed(idleCheck, IDLE_UNBIND_MS)
    }

    @Synchronized
    fun unbind() {
        main.removeCallbacks(idleCheck)
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
        connected = CompletableDeferred()
        pending.values.forEach { it.complete(Bundle()) }
        pending.clear()
    }

    companion object {
        private const val TAG = "LocalTypeModel"
        private const val STATUS_TIMEOUT_MS = 1_500L
        private const val CONNECT_TIMEOUT_MS = 1_000L
        /** The model process also drops a proofread or chat request still waiting after these. */
        const val PROOFREAD_TIMEOUT_MS = 30_000L
        /** 256 tokens on CPU can take most of a minute. */
        const val GENERATE_TIMEOUT_MS = 60_000L
        private const val IDLE_UNBIND_MS = 3 * 60_000L
        private const val CRASH_WINDOW_MS = 10 * 60_000L
        private const val MAX_DEATHS = 3
    }
}
