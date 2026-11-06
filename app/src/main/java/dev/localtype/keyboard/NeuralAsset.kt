package dev.localtype.keyboard

import java.io.InputStream
import java.security.MessageDigest

/** The single swappable identity of the bundled experimental next-word model. */
object NeuralAsset {
    const val FILE_NAME = "cifg_int8.bin"
    const val SHA256 = "3255dbeaf6e7fc0922afdca42ef751a22e3ad28903ab4acc9eb0022b9a704318"

    /** The caller closes [input] and runs this off the typing thread. Verify before parsing. */
    fun load(input: InputStream): CifgModel {
        val bytes = input.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(digest == SHA256) { "neural asset SHA-256 mismatch" }
        return CifgModel.load(bytes)
    }
}
