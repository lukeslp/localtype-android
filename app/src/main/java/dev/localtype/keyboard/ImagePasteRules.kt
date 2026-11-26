package dev.localtype.keyboard

/**
 * When the paste chip offers an image from the clipboard, decided from the clip's description and the field's
 * accepted content types alone: nothing here reads the image. The service passes what it knows about the field and
 * the clip, so the JVM tests cover every case.
 */
object ImagePasteRules {
    enum class Offer {
        /** The clip is not an image, so the text rules apply. */
        NOT_IMAGE,
        /** A password or no-learning field, a clip marked sensitive, or one copied in such a field: never read. */
        PRIVATE,
        /** The field takes no image of the clip's type. */
        NOT_TAKEN,
        OFFER,
    }

    /** The clip's image types, as its ClipDescription lists them. */
    fun imageTypes(clipTypes: List<String>): List<String> = clipTypes.filter { it.lowercase().startsWith("image/") }

    /**
     * Whether a content type [type] matches [pattern], where either may end in a wildcard ("image/" + "*", or
     * "*" + "/" + "*"), the way ClipDescription.compareMimeTypes reads them; letter case doesn't count.
     */
    fun matches(pattern: String, type: String): Boolean {
        val (pMain, pSub) = split(pattern) ?: return false
        val (tMain, tSub) = split(type) ?: return false
        if (pMain != "*" && tMain != "*" && pMain != tMain) return false
        return pSub == "*" || tSub == "*" || pSub == tSub
    }

    private fun split(type: String): Pair<String, String>? {
        val parts = type.trim().lowercase().split('/')
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return parts[0] to parts[1]
    }

    /** Whether a field whose EditorInfo lists [editorTypes] (null or empty: none) takes an image of [type]. */
    fun accepts(editorTypes: Array<String>?, type: String): Boolean =
        editorTypes.orEmpty().any { matches(it, type) }

    /** The first of the clip's image types the field takes, for a paste that sends the copied image as it is. */
    fun commitType(clipTypes: List<String>, editorTypes: Array<String>?): String? =
        imageTypes(clipTypes).firstOrNull { type -> accepts(editorTypes, type) }

    /**
     * What the chip may do with the clip now on the clipboard. [privateField] is a password field or one that
     * asks for no personalized learning; [flagged] is ClipDescription.EXTRA_IS_SENSITIVE; [copiedInPrivateField]
     * says the copy was made while such a field had the keyboard, as for text.
     */
    fun offer(
        clipTypes: List<String>, editorTypes: Array<String>?, privateField: Boolean, flagged: Boolean, copiedInPrivateField: Boolean,
    ): Offer {
        val images = imageTypes(clipTypes)
        if (images.isEmpty()) return Offer.NOT_IMAGE
        if (privateField || flagged || copiedInPrivateField) return Offer.PRIVATE
        return if (commitType(clipTypes, editorTypes) != null) Offer.OFFER else Offer.NOT_TAKEN
    }
}
