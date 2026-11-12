package dev.localtype.keyboard

/**
 * Whether this phone has the memory for the Gemma that settings offers to download, and the two sizes settings
 * names when it does not (the sentence itself is the string resource `models_gemma_too_small`).
 * The model kit refuses a model larger than [MAX_FRACTION_OF_RAM] of the phone's memory when a download starts;
 * settings asks first, so a phone that can't run it is told so instead of being offered a Download button
 * that can only fail (reported from a 4 GB Moto G on 2026-11-11).
 */
object GemmaFit {
    /** The model kit's bound (`ModelImportRules.MAX_FRACTION_OF_RAM`); a test in the full build holds them equal. */
    const val MAX_FRACTION_OF_RAM = 0.35
    private const val GIB = 1024.0 * 1024 * 1024
    /** A phone reports a little less than the size it is sold as; 8 GB phones report about 7.4. */
    private const val REPORTED_SHARE = 0.9
    private val SOLD_AS = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)

    fun fits(modelBytes: Long, totalRamBytes: Long, lowRamDevice: Boolean): Boolean =
        !lowRamDevice && modelBytes <= totalRamBytes * MAX_FRACTION_OF_RAM

    /** The size this phone is sold as: the smallest usual size at or above what it reports. */
    fun phoneGb(totalRamBytes: Long): Int = SOLD_AS.firstOrNull { it * GIB >= totalRamBytes } ?: SOLD_AS.last()

    /** The smallest usual phone size whose reported memory holds the model within the bound. */
    fun neededGb(modelBytes: Long): Int =
        SOLD_AS.firstOrNull { it * GIB * REPORTED_SHARE * MAX_FRACTION_OF_RAM >= modelBytes } ?: SOLD_AS.last()

    /** What the too-small sentence states: the phone size the model needs and the size this phone is sold as. */
    data class Sizes(val neededGb: Int, val phoneGb: Int)

    fun tooSmall(modelBytes: Long, totalRamBytes: Long): Sizes = Sizes(neededGb(modelBytes), phoneGb(totalRamBytes))
}
