package dev.localtype.keyboard

import dev.localtype.modelkit.Sampling

/**
 * The keyboard's Gemma requests as prompts, sampling and reply limits, and how each reply is read. The
 * same request goes to LocalType's own model ([ModelInferenceService]) or to LocalLauncher's copy through
 * its door (modelkit's SharedModelClient), so both read the reply the same way.
 */
internal object KeyboardGemma {
    class Ask(val prompt: String, val sampling: Sampling, val maxTokens: Int)

    fun predict(text: String, limit: Int, avoid: List<String>) = Ask(
        prompt = predictionPrompt(text, limit, avoid),
        // Regenerate samples more freely with a fresh seed so it can find other words.
        sampling = if (avoid.isEmpty()) Sampling(topK = 8, topP = 0.8, temperature = 0.15, seed = 4)
        else Sampling(topK = 40, topP = 0.95, temperature = 0.9, seed = freshSeed()),
        maxTokens = 6 * (limit + avoid.size) + 8,
    )

    fun readPrediction(reply: String?, limit: Int, avoid: List<String>): List<String> =
        reply?.let { parsePhraseCandidates(it, limit + avoid.size).filter { word -> word.substringBefore(' ') !in avoid }.take(limit) }
            .orEmpty()

    fun proofread(text: String) = Ask(proofreadPrompt(text), Sampling.EXACT, (text.length / 2 + 48).coerceAtMost(512))

    fun readProofread(text: String, reply: String?): String? = reply?.let { cleanProofread(text, it) }

    fun chat(prompt: String) = Ask(
        prompt,
        Sampling(topK = ModelChat.TOP_K, topP = 0.95, temperature = ModelChat.TEMPERATURE.toDouble(), seed = freshSeed()),
        ModelChat.MAX_OUTPUT_TOKENS,
    )

    private fun freshSeed() = (System.nanoTime() % Int.MAX_VALUE).toInt()
}
