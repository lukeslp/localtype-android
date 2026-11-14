package dev.localtype.keyboard

import java.io.InputStream
import java.security.MessageDigest

/** The single swappable identity of the bundled experimental next-word model. */
object NeuralAsset {
    const val FILE_NAME = "cifg_int8.bin"
    const val SHA256 = "4099fe04fb2b10597b0e1068f51aa94b9618d4f326abf72a08a6aab88b32ea7a"

    /** The caller closes [input] and runs this off the typing thread. Verify before parsing. */
    fun load(input: InputStream): CifgModel {
        val bytes = input.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(digest == SHA256) { "neural asset SHA-256 mismatch" }
        return CifgModel.load(bytes)
    }
}
