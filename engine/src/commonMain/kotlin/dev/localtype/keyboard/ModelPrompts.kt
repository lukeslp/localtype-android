package dev.localtype.keyboard

// The prompts and reply cleanup for an on-device foundation model, shared by Android (Gemini Nano, Gemma)
// and the iPhone (Apple's model). Pure text in and out.

/**
 * Normalizes model output into continuations of up to two words ("a coffee", "tomorrow"). The first
 * word fills the strip; the second is cached for the spot after it, which keeps suggestions a word ahead.
 */
fun parsePhraseCandidates(text: String, limit: Int): List<String> = text
    .lineSequence()
    .flatMap { line -> line.split(',', ';', '/', '\t').asSequence() }
    .map { it.trim().lowercase().replace(Regex("^[0-9.\\- )*•]+"), "") }
    .map { phrase ->
        phrase.split(' ')
            .map { it.replace(Regex("[^\\p{L}'’-]"), "") }
            // A word needs a letter: a lone "-" or "'" is punctuation, not a suggestion.
            .filter { w -> w.any(Char::isLetter) }
            .take(2)
    }
    .filter { words -> words.isNotEmpty() && (words[0].length > 1 || words[0] == "a" || words[0] == "i") }
    .map { it.joinToString(" ") }
    .distinct()
    .take(limit)
    .toList()

fun proofreadPrompt(text: String): String = buildString {
    appendLine("Proofread this text typed on a phone keyboard.")
    appendLine("Fix spelling, grammar, capitalization and punctuation.")
    appendLine("Keep the same words, tone, slang and meaning. Do not add or remove ideas.")
    appendLine("Reply with only the corrected text and nothing else.")
    appendLine()
    append(text)
}

/**
 * Cleans a model's proofreading reply and rejects answers that aren't a plausible correction of
 * [original] (explanations, a different length, empty output).
 */
fun cleanProofread(original: String, reply: String): String? {
    var text = reply.trim()
    text = text.replace(Regex("^(corrected( text)?|here is the corrected text|proofread( text)?)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
    text = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (text.length >= 2 && text.first() in "\"“'" && text.last() in "\"”'" && !(original.first() in "\"“'")) {
        text = text.substring(1, text.length - 1).trim()
    }
    if (text.isEmpty()) return null
    val ratio = text.length.toDouble() / original.length.coerceAtLeast(1)
    return text.takeIf { ratio in 0.6..1.6 }
}

fun predictionPrompt(context: String, limit: Int, avoid: List<String> = emptyList()): String = buildString {
    appendLine("Act as a keyboard next-word predictor. Return up to $limit likely continuations of one or two words, one per line.")
    appendLine("Do not use punctuation, numbering, explanations, or sentences. Do not repeat words already typed.")
    // Regenerate: steer away from the words already offered.
    if (avoid.isNotEmpty()) appendLine("Suggest different words than these: ${avoid.joinToString(", ")}.")
    appendLine("Text before cursor:")
    append(context.takeLast(280))
}
