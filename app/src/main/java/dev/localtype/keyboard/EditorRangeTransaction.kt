package dev.localtype.keyboard

/** Replaces only the requested range, preserving later text. A command is never retried. */
object EditorRangeTransaction {
    data class SelectionRead(val start: Int, val end: Int, val selected: String?)
    fun apply(
        edit: RecentWordCorrection.RangeEdit, initial: VerifiedWordTransaction.Snapshot,
        direct: Boolean, read: () -> VerifiedWordTransaction.Snapshot?,
        range: (Int, Int, String, Int) -> Boolean,
        select: (Int, Int) -> Boolean, selected: () -> SelectionRead?, commit: (String, Int) -> Boolean,
    ): Boolean {
        if (!initial.selection.collapsed || edit.start < 0 || edit.end < edit.start || edit.end > initial.selection.end) return false
        val offset = initial.selection.end - initial.before.length
        if (edit.start < offset) return false
        val from = edit.start - offset
        val to = edit.end - offset
        val original = initial.before.substring(from, to)
        if (original.isEmpty() || original.first().isLowSurrogate() || original.last().isHighSurrogate()) return false
        val cursorPosition = initial.selection.end - edit.end + 1
        val sent = if (direct) range(edit.start, edit.end, edit.text, cursorPosition) else {
            // Explicit Apply/Undo may use the older selection API, only after verifying the exact
            // selected word. Automatic late edits never use this compatibility path.
            if (!select(edit.start, edit.end)) return false
            val selection = selected()
            if (selection == null || selection.start != edit.start || selection.end != edit.end || selection.selected != original) {
                select(initial.selection.start, initial.selection.end)
                return false
            }
            commit(edit.text, cursorPosition)
        }
        val result = read()
        if (!direct && result?.selection == EditorEditState.Selection(edit.start, edit.end) && result.selected == original &&
            result.before == initial.before.substring(0, from) && result.after == initial.before.substring(to) + initial.after) {
            select(initial.selection.start, initial.selection.end)
        }
        if (!sent || result == null) return false
        val expected = initial.before.substring(0, from) + edit.text + initial.before.substring(to)
        return result.selection == EditorEditState.Selection(edit.caret, edit.caret) &&
            result.before.length >= minOf(expected.length, initial.before.length) &&
            (if (initial.beforeComplete && result.beforeComplete) result.before == expected else result.before.endsWith(expected.takeLast(result.before.length))) &&
            result.after == initial.after
    }
}
