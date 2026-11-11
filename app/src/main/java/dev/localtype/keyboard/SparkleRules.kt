package dev.localtype.keyboard

/**
 * When the strip's lead button shows its sparkle, the tap that asks Gemini Nano or Gemma for words. The same
 * button is also the paste chip and the Undo arrow; those never depend on this.
 */
object SparkleRules {
    /** How long one answer about the models stands before the keyboard asks again, at a field start. */
    const val RECHECK_MS = 60_000L

    /**
     * A model the sparkle could ask: Gemini Nano ready or downloading, a Gemma file on the phone, or LocalLauncher's
     * copy on loan. A Nano that is only offered, a missing file and a phone with neither are not set up, and a
     * sparkle there could only answer that nothing is ready.
     */
    fun modelSetUp(nano: FoundationModelStatus?, gemmaInstalled: Boolean, borrowed: Boolean): Boolean =
        nano == FoundationModelStatus.READY || nano == FoundationModelStatus.INITIALIZING || gemmaInstalled || borrowed

    /** The sparkle shows when the person hasn't turned it off and there is a model behind it. */
    fun shows(wanted: Boolean, modelSetUp: Boolean): Boolean = wanted && modelSetUp

    /** Whether the answer from [checkedAt] (uptime, null for never) is old enough at [now] to ask again. */
    fun due(checkedAt: Long?, now: Long): Boolean = checkedAt == null || now - checkedAt >= RECHECK_MS
}
