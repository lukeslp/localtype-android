package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class NeuralAssetTest {
    private val asset = File("src/main/assets/${NeuralAsset.FILE_NAME}")

    @Test
    fun `the bundled asset passes its pin and yields a model`() {
        assertEquals(2_221_879L, asset.length())
        val model = asset.inputStream().use(NeuralAsset::load)
        assertEquals(16_384, model.vocabSize)
        assertTrue(model.newSession().predict("hello ").isNotEmpty())
    }

    @Test
    fun `damage is rejected by the digest before parsing the header`() {
        val bytes = asset.readBytes()
        bytes[0] = 0
        val failure = assertThrows(IllegalArgumentException::class.java) { NeuralAsset.load(ByteArrayInputStream(bytes)) }
        assertEquals("neural asset SHA-256 mismatch", failure.message)
    }

    @Test
    fun `a truncated or another valid model cannot use the bundled identity`() {
        for (bytes in listOf(byteArrayOf(), asset.readBytes().dropLast(1).toByteArray(), File("src/test/fixtures/cifg_tiny.bin").readBytes())) {
            val failure = assertThrows(IllegalArgumentException::class.java) { NeuralAsset.load(ByteArrayInputStream(bytes)) }
            assertEquals("neural asset SHA-256 mismatch", failure.message)
        }
    }
}
