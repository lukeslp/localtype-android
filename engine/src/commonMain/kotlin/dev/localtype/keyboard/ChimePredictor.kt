package dev.localtype.keyboard

/**
 * Chime's next-word and completion words for a keyboard host other than the Android service: the iPhone
 * keyboard runs it on its own queue and hands the answer to [TypingEngine.suggestions]. It holds the int8
 * model and one session for the current field, as the Android keyboard's NeuralPredictionWorker does, and
 * reads nothing but the text it is given. That text never trains the model.
 *
 * Not thread-safe: build it anywhere, then use it from one thread (not necessarily the engine's).
 */
class ChimePredictor(blob: ByteArray) {
    private val model = CifgModel.load(blob)
    private val session = model.newSession()

    /** Up to [limit] lowercase words for the text before the caret, best first, read from its sentence start. */
    fun predict(beforeCursor: String, limit: Int = WORDS): List<String> =
        session.predict(NeuralContext.sentence(beforeCursor), limit).map { it.word }

    /** Starts over, for a new field. */
    fun reset() = session.reset()

    companion object {
        /** As many words as the Android keyboard asks for, so its ranking has the same choice. */
        const val WORDS = 12
        const val FILE_NAME = "cifg_int8.bin"
        /** The bundled export's SHA-256, as Android's NeuralAsset checks it. */
        const val SHA256 = "3255dbeaf6e7fc0922afdca42ef751a22e3ad28903ab4acc9eb0022b9a704318"

        /** Loads [blob] after checking it is the bundled export, or fails with [IllegalArgumentException]. */
        fun verified(blob: ByteArray): ChimePredictor {
            val digest = sha256(blob).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            require(digest == SHA256) { "Chime asset SHA-256 mismatch" }
            return ChimePredictor(blob)
        }
    }
}
