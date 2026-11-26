package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** When the paste chip offers an image, and the folder that holds the one cleaned copy. */
class ImagePasteRulesTest {
    private val anyImage = arrayOf("image/*")

    @Test
    fun `content types match with wildcards on either side and in any case`() {
        assertTrue(ImagePasteRules.matches("image/*", "image/png"))
        assertTrue(ImagePasteRules.matches("*/*", "image/gif"))
        assertTrue(ImagePasteRules.matches("image/png", "image/*"))
        assertTrue(ImagePasteRules.matches("IMAGE/JPEG", "image/jpeg"))
        assertFalse(ImagePasteRules.matches("image/png", "image/jpeg"))
        assertFalse(ImagePasteRules.matches("video/*", "image/png"))
        assertFalse(ImagePasteRules.matches("image", "image/png"))
        assertFalse(ImagePasteRules.matches("", "image/png"))
    }

    @Test
    fun `a field with no content types takes no image`() {
        assertFalse(ImagePasteRules.accepts(null, "image/png"))
        assertFalse(ImagePasteRules.accepts(emptyArray(), "image/png"))
        assertFalse(ImagePasteRules.accepts(arrayOf("video/mp4"), "image/png"))
        assertTrue(ImagePasteRules.accepts(arrayOf("video/mp4", "image/gif", "image/png"), "image/png"))
    }

    @Test
    fun `the chip offers an image only where the field takes it`() {
        fun offer(types: List<String>, editor: Array<String>?) = ImagePasteRules.offer(types, editor, privateField = false, flagged = false, copiedInPrivateField = false)
        assertEquals(ImagePasteRules.Offer.NOT_IMAGE, offer(listOf("text/plain"), anyImage))
        assertEquals(ImagePasteRules.Offer.NOT_IMAGE, offer(emptyList(), anyImage))
        assertEquals(ImagePasteRules.Offer.OFFER, offer(listOf("image/jpeg"), anyImage))
        assertEquals(ImagePasteRules.Offer.OFFER, offer(listOf("text/plain", "image/png"), arrayOf("image/png")))
        assertEquals(ImagePasteRules.Offer.NOT_TAKEN, offer(listOf("image/jpeg"), null))
        assertEquals(ImagePasteRules.Offer.NOT_TAKEN, offer(listOf("image/jpeg"), arrayOf("image/gif")))
        assertEquals("image/png", ImagePasteRules.commitType(listOf("text/plain", "image/png"), anyImage))
        assertEquals(null, ImagePasteRules.commitType(listOf("image/png"), arrayOf("image/gif")))
    }

    @Test
    fun `an image is never offered in a private field, flagged, or copied in a private field`() {
        val types = listOf("image/png")
        assertEquals(ImagePasteRules.Offer.PRIVATE, ImagePasteRules.offer(types, anyImage, privateField = true, flagged = false, copiedInPrivateField = false))
        assertEquals(ImagePasteRules.Offer.PRIVATE, ImagePasteRules.offer(types, anyImage, privateField = false, flagged = true, copiedInPrivateField = false))
        assertEquals(ImagePasteRules.Offer.PRIVATE, ImagePasteRules.offer(types, anyImage, privateField = false, flagged = false, copiedInPrivateField = true))
        // Even where the field takes no image: nothing about a private clip is said.
        assertEquals(ImagePasteRules.Offer.PRIVATE, ImagePasteRules.offer(types, null, privateField = true, flagged = false, copiedInPrivateField = false))
    }

    @Test
    fun `the cache keeps one cleaned copy and drops it after an hour`() {
        val folder = Files.createTempDirectory("pasted").toFile().resolve(PastedImageCache.FOLDER)
        val cache = PastedImageCache(folder)
        val first = cache.write(byteArrayOf(1, 2, 3), "png")
        assertTrue(first.exists())
        assertEquals(byteArrayOf(1, 2, 3).toList(), first.readBytes().toList())
        val second = cache.write(byteArrayOf(4), "jpg")
        assertFalse("the earlier copy goes when the next is written", first.exists())
        assertTrue(second.name != first.name && second.name.endsWith(".jpg"))
        assertEquals(1, cache.count())
        val now = second.lastModified()
        cache.expire(now + PastedImageCache.MAX_AGE_MS - 1)
        assertTrue(second.exists())
        cache.expire(now + PastedImageCache.MAX_AGE_MS)
        assertFalse(second.exists())
        // A copy dated in the future (the clock moved back) doesn't stay forever either.
        val third = cache.write(byteArrayOf(5), "gif")
        third.setLastModified(now + 10 * PastedImageCache.MAX_AGE_MS)
        cache.expire(now)
        assertFalse(third.exists())
        assertEquals(0, cache.count())
    }

    @Test
    fun `only the cleaned copies folder is shared, by a provider no other app can open`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val provider = Regex("""<provider[\s\S]*?</provider>""").findAll(manifest).map { it.value }.single { "pastedimages" in it }
        assertTrue(provider, "android:exported=\"false\"" in provider)
        assertTrue(provider, "android:grantUriPermissions=\"true\"" in provider)
        assertTrue(provider, "android:authorities=\"\${applicationId}.pastedimages\"" in provider)
        val paths = File("src/main/res/xml/pasted_images.xml").readText()
        val entries = Regex("""<(\w+-path|root-path)\b[^>]*>""").findAll(paths).map { it.value }.toList()
        assertEquals(listOf("<cache-path name=\"pasted\" path=\"${PastedImageCache.FOLDER}/\" />"), entries)
    }

    @Test
    fun `the image code reads no network and keeps nothing outside the cache folder`() {
        listOf("ImageMetadata.kt", "ImagePasteRules.kt", "PastedImageCache.kt", "PastedImages.kt").forEach { name ->
            val source = File("src/main/java/dev/localtype/keyboard/$name").readText()
            listOf("java.net", "HttpURLConnection", "Socket", "filesDir", "noBackupFilesDir", "getExternal", "SharedPreferences", "Log.").forEach {
                assertFalse("$name uses $it", it in source)
            }
        }
    }

    @Test
    fun `the image chip has its own picture and the dump says why an image is or isn't offered`() {
        val service = File("src/main/java/dev/localtype/keyboard/LocalTypeInputMethodService.kt").readText()
        assertTrue("pasteImage != null -> R.drawable.ic_paste_image to R.string.paste_image_description" in service)
        // The text chip keeps the plain clipboard.
        assertTrue("pasteClip != null -> R.drawable.ic_paste to pasteDescription()" in service)
        val text = File("src/main/res/drawable/ic_paste.xml").readText()
        val image = File("src/main/res/drawable/ic_paste_image.xml").readText()
        val paths = Regex("""android:pathData="([^"]+)"""")
        val clipboard = paths.find(text)!!.groupValues[1]
        val drawn = paths.findAll(image).map { it.groupValues[1] }.toList()
        // The same clipboard, with a picture drawn on it.
        assertEquals(clipboard, drawn.first())
        assertTrue("found ${drawn.size} shapes", drawn.size >= 3)
        // The dump names states and counts only: never the clip's address, label or types.
        val dump = service.substringAfter("writer.println(\"images chip=").substringBefore("\n        writer.println(")
        listOf("uri", "label", "getMimeType", "primaryClip?").forEach { assertFalse("the dump reads $it", it in dump) }
    }

    @Test
    fun `a copy kept from before says when its hour is up`() {
        val folder = Files.createTempDirectory("pasted").toFile()
        try {
            val cache = PastedImageCache(folder)
            assertEquals(null, cache.dueIn(1_000_000L))
            val file = cache.write(byteArrayOf(1, 2, 3), "jpg")
            file.setLastModified(1_000_000L)
            assertEquals(PastedImageCache.MAX_AGE_MS, cache.dueIn(1_000_000L))
            assertEquals(PastedImageCache.MAX_AGE_MS - 60_000L, cache.dueIn(1_060_000L))
            // Past its hour it is due now, never a negative wait; a second instance on the folder sees the same copy.
            assertEquals(0L, PastedImageCache(folder).dueIn(1_000_000L + 2 * PastedImageCache.MAX_AGE_MS))
            cache.expire(1_000_000L + PastedImageCache.MAX_AGE_MS)
            assertEquals(null, cache.dueIn(1_000_000L + PastedImageCache.MAX_AGE_MS))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `a paste given up writes no copy and a new field gives it up`() {
        val images = File("src/main/java/dev/localtype/keyboard/PastedImages.kt").readText()
        // Asked after the image is cleaned and before the cache is touched: writing deletes the copy before it.
        val clean = images.substringAfter("fun clean(").substringBefore("private fun read(")
        assertTrue(clean.indexOf("if (!wanted()) return Failed") in 0 until clean.indexOf("cache(context).write("))
        val service = File("src/main/java/dev/localtype/keyboard/LocalTypeInputMethodService.kt").readText()
        assertTrue("PastedImages.clean(context, uri) { isActive }" in service)
        // Every field start, the field's view going, and Private for now give up a paste still being cleaned.
        assertTrue(service.substringAfter("override fun onStartInputView(").substringBefore("cancelFoundationRequest()").contains("dropImagePaste(tell = true)"))
        assertTrue(service.substringAfter("override fun onFinishInputView(").substringBefore("override fun onStartInputView(").contains("dropImagePaste(tell = false)") ||
            service.substringAfter("override fun onFinishInputView(").take(3_000).contains("dropImagePaste(tell = false)"))
        assertTrue(service.substringAfter("private fun privateForNow()").take(600).contains("dropImagePaste(tell = true)"))
        // A field that turned private while the image was cleaned takes nothing.
        assertTrue("!inputIsSensitive && learningAllowed" in service.substringAfter("PastedImages.clean(context, uri) { isActive }").take(700))
        // A keyboard that starts while a copy is kept checks again when its hour is up.
        assertTrue("cache.dueIn(System.currentTimeMillis())" in service)
    }
}
