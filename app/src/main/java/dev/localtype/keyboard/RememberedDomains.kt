package dev.localtype.keyboard

/**
 * Hosts the person has typed, so the strip can finish one. Hosts only (see [DomainHosts]); at most [MAX], and
 * a host is offered once it has been typed [OFFER_AFTER] times. When the list is full the host used longest
 * ago makes room. Main thread only; the [Store] does the writing.
 */
class RememberedDomains(private val store: Store) {
    /** Where the list persists. [load] runs once, off the main thread; [save] gets the whole list after every change. */
    interface Store {
        fun load(): List<Entry>
        fun save(entries: List<Entry>)
    }

    /** [count] is how many times it was typed in full; [day] the last day it was typed or taken from the strip, in days since 1970. */
    data class Entry(val host: String, val count: Int, val day: Long)

    private val byHost = LinkedHashMap<String, Entry>().also { map ->
        // A damaged or hand-edited file loses its bad lines, its duplicates and its overflow here.
        for (entry in store.load()) {
            if (entry.host.isNotEmpty() && entry.count >= 1 && entry.host !in map && map.size < MAX) map[entry.host] = entry.copy(count = entry.count.coerceAtMost(MAX_COUNT))
        }
    }

    /** Called after every change, including Forget learned words in settings while the keyboard is open. */
    var onChange: (() -> Unit)? = null

    /** Every host held, most used first: what settings lists. */
    val entries: List<Entry> get() = byHost.values.sortedWith(ORDER)

    private var offeredNow: List<Entry>? = null

    /** The hosts typed often enough to be offered, best first. Read on every keystroke, so sorted once per change. */
    val offered: List<Entry>
        get() = offeredNow ?: entries.filter { it.count >= OFFER_AFTER }.also { offeredNow = it }

    /** The person typed [host] in full on [day]. */
    fun typed(host: String, day: Long) {
        val known = byHost[host]
        if (known == null && byHost.size >= MAX) byHost.remove(byHost.values.minWithOrNull(EVICTION)!!.host)
        byHost[host] = Entry(host, ((known?.count ?: 0) + 1).coerceAtMost(MAX_COUNT), day)
        changed()
    }

    /** The person took [host] from the strip on [day]: it was wanted, which keeps it from being the one that makes room. */
    fun used(host: String, day: Long) {
        val known = byHost[host] ?: return
        if (known.day == day) return
        byHost[host] = known.copy(day = day)
        changed()
    }

    fun remove(host: String) {
        if (byHost.remove(host) != null) changed()
    }

    /** Forget learned words. */
    fun clear() {
        if (byHost.isEmpty()) return
        byHost.clear()
        changed()
    }

    private fun changed() {
        offeredNow = null
        store.save(byHost.values.toList())
        onChange?.invoke()
    }

    companion object {
        const val MAX = 64
        const val OFFER_AFTER = 2
        private const val MAX_COUNT = 9_999

        /** Most typed first, then most recent, then by name so the order never depends on the file's. */
        private val ORDER = compareByDescending<Entry> { it.count }.thenByDescending { it.day }.thenBy { it.host }
        /** The one to drop: used longest ago, then typed least, then last by name. */
        private val EVICTION = compareBy<Entry> { it.day }.thenBy { it.count }.thenByDescending { it.host }
    }
}

/**
 * The remembered hosts' file: a version line, then `host`, `count` and `day` on each line, separated by tabs.
 * Pure, so the file is tested without Android. A line that does not parse is dropped; a file that is not this
 * format reads as empty.
 */
object DomainCodec {
    const val HEADER = "localtype-domains 1"

    fun encode(entries: List<RememberedDomains.Entry>): String = buildString {
        append(HEADER).append('\n')
        for (entry in entries) append(entry.host).append('\t').append(entry.count).append('\t').append(entry.day).append('\n')
    }

    fun decode(file: String): List<RememberedDomains.Entry> {
        val lines = file.split('\n').map { it.removeSuffix("\r") }
        if (lines.first() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 3) return@mapNotNull null
            val count = fields[1].toIntOrNull() ?: return@mapNotNull null
            val day = fields[2].toLongOrNull() ?: return@mapNotNull null
            // Only something [DomainHosts] could have produced: a hand-edited line cannot put other text in the strip.
            if (!HOST.matches(fields[0])) return@mapNotNull null
            RememberedDomains.Entry(fields[0], count, day)
        }
    }

    private val HOST = Regex("[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63})+")
}
