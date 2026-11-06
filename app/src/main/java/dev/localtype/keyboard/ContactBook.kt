package dev.localtype.keyboard

/**
 * One person from the phone's contacts, as the Contacts panel needs them: a name and what can be typed for them.
 * Read only after the person grants the permission, held in memory only, and never learned, logged or sent.
 */
data class Contact(val name: String, val phones: List<String>, val emails: List<String>, val starred: Boolean = false)

/** Finding a contact to insert, and the names correction leaves alone. Pure, so the rules are tested. */
object ContactBook {
    /** More than this many are not read: a panel scrolled that far is a search problem, not a list. */
    const val MAX_CONTACTS = 5_000

    /** The panel shows at most this many matches. */
    const val MAX_SHOWN = 60

    /** The longest word before the caret that filters the list. */
    const val MAX_QUERY = 40

    /** What a field mostly wants, so its kind of detail comes first. */
    enum class Field { TEXT, EMAIL, PHONE }

    enum class Kind { NAME, PHONE, EMAIL }

    data class Choice(val text: String, val kind: Kind)

    /** The words of a name, for matching and for protection: letters, marks, digits and apostrophes inside a word. */
    fun words(name: String): List<String> = WORD.findAll(name).map { it.value.trim('\'', '’') }.filter { it.isNotEmpty() }.toList()

    /**
     * The contacts for [query], the word being typed before the caret: those with a name word, or the whole name,
     * starting with it, ignoring case. Starred first, then the rest by name. A blank query lists everyone that way.
     */
    fun matches(contacts: List<Contact>, query: String): List<Contact> {
        val q = query.trim().lowercase()
        val found = if (q.isEmpty()) contacts else contacts.filter { contact ->
            contact.name.lowercase().startsWith(q) || words(contact.name).any { it.lowercase().startsWith(q) }
        }
        return found.sortedWith(compareByDescending<Contact> { it.starred }.thenBy { it.name.lowercase() }.thenBy { it.name })
            .take(MAX_SHOWN)
    }

    /**
     * Every word of every name, lowercased, two letters or longer: correction never changes one of these. A name
     * word that is also a dictionary word changes nothing, because correction only looks at words it doesn't know.
     */
    fun nameWords(contacts: List<Contact>): Set<String> =
        contacts.flatMapTo(HashSet()) { contact -> words(contact.name).map { it.lowercase() }.filter { it.length >= 2 } }

    /** What can be inserted for [contact], the field's own kind of detail first. Repeats and blanks are dropped. */
    fun choices(contact: Contact, field: Field): List<Choice> {
        val name = listOf(Choice(contact.name, Kind.NAME)).filter { it.text.isNotBlank() }
        val phones = contact.phones.map { Choice(it.trim(), Kind.PHONE) }.filter { it.text.isNotEmpty() }
        val emails = contact.emails.map { Choice(it.trim(), Kind.EMAIL) }.filter { it.text.isNotEmpty() }
        val ordered = when (field) {
            Field.EMAIL -> emails + name + phones
            Field.PHONE -> phones + name + emails
            Field.TEXT -> name + phones + emails
        }
        return ordered.distinctBy { it.kind to it.text.lowercase() }
    }

    /**
     * The text that goes in for [choice]. In prose a name is followed by a space unless [after] (the text after the
     * caret) already starts with one, like the date tools; a number or an address goes in alone.
     */
    fun inserted(choice: Choice, after: String?): String =
        if (choice.kind == Kind.NAME) ToolText.inserted(choice.text, after) else choice.text

    /** Contacts read in any order, merged by id. Names that are only an address or a number still show. */
    class Builder {
        private val byId = LinkedHashMap<Long, Entry>()

        private class Entry(var name: String, var starred: Boolean) {
            val phones = LinkedHashSet<String>()
            val emails = LinkedHashSet<String>()
        }

        /** One row of the phone's contact data. Past [MAX_CONTACTS] people a new one is ignored. */
        fun add(id: Long, name: String?, kind: Kind, value: String?, starred: Boolean) {
            val entry = byId[id] ?: if (byId.size >= MAX_CONTACTS) return else Entry("", false).also { byId[id] = it }
            if (entry.name.isEmpty() && !name.isNullOrBlank()) entry.name = name.trim()
            entry.starred = entry.starred || starred
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return
            when (kind) {
                Kind.PHONE -> entry.phones += text
                Kind.EMAIL -> entry.emails += text
                Kind.NAME -> Unit
            }
        }

        fun build(): List<Contact> = byId.values.mapNotNull { entry ->
            val name = entry.name.ifEmpty { entry.emails.firstOrNull() ?: entry.phones.firstOrNull() ?: return@mapNotNull null }
            Contact(name, entry.phones.toList(), entry.emails.toList(), entry.starred)
        }
    }

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}'’]+")
}
