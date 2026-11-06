package dev.localtype.keyboard

/*
 * The little the engine needs from a platform. On the JVM (the Android keyboard) each is the java.*
 * call the engine used before it was shared, so Android behaves byte for byte as it did; on iOS each
 * is plain Kotlin or Foundation.
 */

/** SHA-256 of [data]. */
expect fun sha256(data: ByteArray): ByteArray

/** CRC-32 (the zip polynomial) of [length] bytes of [data] from [offset], as an unsigned value. */
expect fun crc32(data: ByteArray, offset: Int, length: Int): Long

/** A damaged or unreadable file: java.io.IOException on the JVM, so Android catches what it always caught. */
expect open class IOException : Exception {
    constructor()
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}

/** Mutual exclusion for state one thread writes while another reads, such as the corrector's memo. */
expect class EngineLock() {
    fun <T> withLock(block: () -> T): T
}

/** [items] as a list no caller can change, even through a cast: Collections.unmodifiableList on the JVM. */
expect fun <T> frozenList(items: List<T>): List<T>

/** [map] as a map no caller can change: Collections.unmodifiableMap on the JVM. */
expect fun <K, V> frozenMap(map: Map<K, V>): Map<K, V>

/** The Unicode general category of [codePoint], numbered as java.lang.Character.getType numbers it. */
expect fun codePointType(codePoint: Int): Int

/** One value per thread, made by [initial] on first use there: java.lang.ThreadLocal on the JVM. */
expect class ThreadLocalValue<T>(initial: () -> T) {
    fun get(): T
}

/** java.lang.Character's number for this category (Kotlin's own [CharCategory.value] isn't public everywhere). */
fun CharCategory.javaType(): Int = when (this) {
    CharCategory.UNASSIGNED -> 0
    CharCategory.UPPERCASE_LETTER -> 1
    CharCategory.LOWERCASE_LETTER -> 2
    CharCategory.TITLECASE_LETTER -> 3
    CharCategory.MODIFIER_LETTER -> 4
    CharCategory.OTHER_LETTER -> 5
    CharCategory.NON_SPACING_MARK -> 6
    CharCategory.ENCLOSING_MARK -> 7
    CharCategory.COMBINING_SPACING_MARK -> 8
    CharCategory.DECIMAL_DIGIT_NUMBER -> 9
    CharCategory.LETTER_NUMBER -> 10
    CharCategory.OTHER_NUMBER -> 11
    CharCategory.SPACE_SEPARATOR -> 12
    CharCategory.LINE_SEPARATOR -> 13
    CharCategory.PARAGRAPH_SEPARATOR -> 14
    CharCategory.CONTROL -> 15
    CharCategory.FORMAT -> 16
    CharCategory.PRIVATE_USE -> 18
    CharCategory.SURROGATE -> 19
    CharCategory.DASH_PUNCTUATION -> 20
    CharCategory.START_PUNCTUATION -> 21
    CharCategory.END_PUNCTUATION -> 22
    CharCategory.CONNECTOR_PUNCTUATION -> 23
    CharCategory.OTHER_PUNCTUATION -> 24
    CharCategory.MATH_SYMBOL -> 25
    CharCategory.CURRENCY_SYMBOL -> 26
    CharCategory.MODIFIER_SYMBOL -> 27
    CharCategory.OTHER_SYMBOL -> 28
    CharCategory.INITIAL_QUOTE_PUNCTUATION -> 29
    CharCategory.FINAL_QUOTE_PUNCTUATION -> 30
}

fun crc32(data: ByteArray): Long = crc32(data, 0, data.size)

/** Lowercase hex of [bytes], two digits each. */
fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(digits[v ushr 4])
            append(digits[v and 15])
        }
    }
}

/** java.lang.Character's numbering of the Unicode general categories, which [codePointType] returns. */
object UnicodeType {
    const val UNASSIGNED = 0
    const val UPPERCASE_LETTER = 1
    const val LOWERCASE_LETTER = 2
    const val TITLECASE_LETTER = 3
    const val MODIFIER_LETTER = 4
    const val OTHER_LETTER = 5
    const val NON_SPACING_MARK = 6
    const val ENCLOSING_MARK = 7
    const val COMBINING_SPACING_MARK = 8
    const val DECIMAL_DIGIT_NUMBER = 9
    const val LETTER_NUMBER = 10
    const val OTHER_NUMBER = 11
    const val DASH_PUNCTUATION = 20
    const val START_PUNCTUATION = 21
    const val END_PUNCTUATION = 22
    const val CONNECTOR_PUNCTUATION = 23
    const val OTHER_PUNCTUATION = 24
    const val OTHER_SYMBOL = 28
    const val INITIAL_QUOTE_PUNCTUATION = 29
    const val FINAL_QUOTE_PUNCTUATION = 30
}

/** Character.isLetter(int). */
fun isLetterCodePoint(codePoint: Int): Boolean = codePointType(codePoint) in UnicodeType.UPPERCASE_LETTER..UnicodeType.OTHER_LETTER

/** Character.isDigit(int). */
fun isDigitCodePoint(codePoint: Int): Boolean = codePointType(codePoint) == UnicodeType.DECIMAL_DIGIT_NUMBER

/** Character.charCount. */
fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

/** The UTF-16 for [codePoint], as String(Character.toChars(codePoint)). */
fun codePointString(codePoint: Int): String =
    if (codePoint < 0x10000) codePoint.toChar().toString()
    else {
        val v = codePoint - 0x10000
        charArrayOf((0xD800 + (v ushr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

/** Character.codePointAt(text, index). */
fun codePointAt(text: CharSequence, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length) {
        val low = text[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}

/** Character.codePointBefore(text, index). */
fun codePointBefore(text: CharSequence, index: Int): Int {
    val low = text[index - 1]
    if (low.isLowSurrogate() && index - 2 >= 0) {
        val high = text[index - 2]
        if (high.isHighSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return low.code
}
