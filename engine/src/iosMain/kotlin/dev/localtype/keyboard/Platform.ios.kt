package dev.localtype.keyboard

import platform.Foundation.NSCharacterSet
import platform.Foundation.NSRecursiveLock
import kotlin.native.concurrent.ThreadLocal

actual fun sha256(data: ByteArray): ByteArray = Sha256.digest(data)

actual fun crc32(data: ByteArray, offset: Int, length: Int): Long = Crc32.of(data, offset, length)

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

actual fun <T> frozenList(items: List<T>): List<T> = items.toList()

actual fun <K, V> frozenMap(map: Map<K, V>): Map<K, V> = map.toMap()

actual class EngineLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Kotlin's own categories for the Basic Multilingual Plane, which match java.lang.Character's; above it,
 * Foundation's character sets give the coarse kind (letter, digit, mark, punctuation, symbol), which is all
 * the engine asks of an astral character.
 */
actual fun codePointType(codePoint: Int): Int {
    if (codePoint < 0x10000) return codePoint.toChar().category.javaType()
    val c = codePoint.toUInt()
    return when {
        NSCharacterSet.letterCharacterSet.longCharacterIsMember(c) -> UnicodeType.OTHER_LETTER
        NSCharacterSet.decimalDigitCharacterSet.longCharacterIsMember(c) -> UnicodeType.DECIMAL_DIGIT_NUMBER
        NSCharacterSet.nonBaseCharacterSet.longCharacterIsMember(c) -> UnicodeType.NON_SPACING_MARK
        NSCharacterSet.punctuationCharacterSet.longCharacterIsMember(c) -> UnicodeType.OTHER_PUNCTUATION
        else -> UnicodeType.OTHER_SYMBOL
    }
}

/** Each thread's values, keyed by their [ThreadLocalValue]: a top-level @ThreadLocal is one map per thread. */
@ThreadLocal
private val threadValues = HashMap<Any, Any?>()

actual class ThreadLocalValue<T> actual constructor(private val initial: () -> T) {
    @Suppress("UNCHECKED_CAST")
    actual fun get(): T = threadValues.getOrPut(this) { initial() } as T
}
