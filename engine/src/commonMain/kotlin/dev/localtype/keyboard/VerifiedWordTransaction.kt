package dev.localtype.keyboard

/** Verifies editor effects. Accepted-but-ignored commands must not teach or append a second word. */
object VerifiedWordTransaction {
    enum class Insertion { APPLIED, REJECTED, UNCHANGED, UNVERIFIED }

    /** One external insertion, including selection replacement. Never retry an uncertain commit. */
    fun insert(text: String, initial: Snapshot?, commit: (String) -> Boolean, read: () -> Snapshot?): Insertion {
        if (!commit(text)) return Insertion.REJECTED
        val after = read() ?: return Insertion.UNVERIFIED
        initial ?: return Insertion.UNVERIFIED
        if (after == initial) return Insertion.UNCHANGED
        val caret = minOf(initial.selection.start, initial.selection.end).toLong() + text.length
        // Require the whole inserted value, rather than merely a matching tail of a long paste.
        if (caret > Int.MAX_VALUE || after.before.length < text.length) return Insertion.UNVERIFIED
        return if (matches(after, initial.before + text, initial.after, caret.toInt(), initial))
            Insertion.APPLIED else Insertion.UNVERIFIED
    }

    /** A single extracted-text receipt keeps text and UTF-16 selection coherent. */
    fun window(text: String?, offset: Int, start: Int, end: Int, limit: Int): Snapshot? {
        if (text == null || offset < 0 || start !in 0..text.length || end !in 0..text.length || limit <= 0 ||
            offset.toLong() + maxOf(start, end) > Int.MAX_VALUE) return null
        val from = minOf(start, end)
        val to = maxOf(start, end)
        return Snapshot(text.substring(maxOf(0, from - limit), from), text.substring(to, minOf(text.length, to + limit)),
            EditorEditState.Selection(offset + start, offset + end), offset == 0 && from < limit, false,
            text.substring(from, to).takeIf { from != to })
    }
    data class Snapshot(
        val before: String, val after: String, val selection: EditorEditState.Selection,
        val beforeComplete: Boolean = true, val afterComplete: Boolean = true,
        val selected: String? = null,
    )

    fun apply(
        edit: WordEdits.Replacement, initial: Snapshot, direct: Boolean,
        read: () -> Snapshot?, range: (Int, Int, String) -> Boolean,
        select: (Int, Int) -> Boolean, commit: (String) -> Boolean, verified: (Snapshot) -> Unit = {},
        finishBatch: () -> Unit = {},
    ): WordEdits.Replacement? {
        val selected = if (initial.selection.collapsed) "" else initial.selected ?: return null
        if (selected.length != kotlin.math.abs(initial.selection.end - initial.selection.start) ||
            edit.before < 0 || edit.after < 0 || initial.before.length < edit.before || initial.after.length < edit.after) return null
        val left = initial.before.dropLast(edit.before)
        val right = initial.after.drop(edit.after)
        val start = minOf(initial.selection.start, initial.selection.end) - edit.before
        val end = maxOf(initial.selection.start, initial.selection.end).toLong() + edit.after
        if (start < 0 || end > Int.MAX_VALUE || start.toLong() + edit.text.length > Int.MAX_VALUE ||
            left.lastOrNull()?.isHighSurrogate() == true || right.firstOrNull()?.isLowSurrogate() == true) return null
        val replacing = selected.isNotEmpty() || edit.before > 0 || edit.after > 0
        val original = initial.before.takeLast(edit.before) + selected + initial.after.take(edit.after)
        var selectedRange = false
        val sent = when {
            !replacing -> commit(edit.text)
            direct -> range(start, end.toInt(), edit.text)
            else -> {
                // Select without removing text. Never leave a deleted word behind if the editor
                // restarts, rejects the commit, or invalidates the connection during verification.
                val accepted = select(start, end.toInt())
                selectedRange = true
                val receipt = read()
                if (!accepted || receipt?.selection != EditorEditState.Selection(start, end.toInt()) ||
                    receipt.selected != original || !anchorsMatch(receipt, left, right, initial)) {
                    select(initial.selection.start, initial.selection.end)
                    return null
                }
                commit(edit.text)
            }
        }
        // Chromium can defer delivery until the outer batch ends. Flush once before
        // reading the effect; this never sends another edit or retries an ambiguous command.
        finishBatch()
        val after = read()
        if (sent && after != null && matches(after, left + edit.text, right, start + edit.text.length, initial)) {
            verified(after)
            return edit
        }
        // An unapplied compatibility commit must not leave the original word selected, where the
        // next ordinary key could overwrite it. This restores the caret; it never retries text.
        if (selectedRange && after?.selected == original && anchorsMatch(after, left, right, initial) &&
            after.selection == EditorEditState.Selection(start, end.toInt())) select(initial.selection.start, initial.selection.end)
        return null
    }

    private fun matches(s: Snapshot, before: String, after: String, caret: Int, initial: Snapshot): Boolean =
        s.selection == EditorEditState.Selection(caret, caret) && anchorsMatch(s, before, after, initial)

    private fun anchorsMatch(s: Snapshot, before: String, after: String, initial: Snapshot): Boolean =
            s.before.length >= minOf(before.length, initial.before.length) && s.after.length >= after.length &&
            (if (initial.beforeComplete && s.beforeComplete) s.before == before else s.before.endsWith(before.takeLast(s.before.length))) &&
            (if (initial.afterComplete && s.afterComplete) s.after == after else s.after.startsWith(after))
}
