package dev.localtype.keyboard

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun crc32(data: ByteArray, offset: Int, length: Int): Long = CRC32().apply { update(data, offset, length) }.value

actual typealias IOException = java.io.IOException

actual class EngineLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}

actual fun codePointType(codePoint: Int): Int = Character.getType(codePoint)

actual class ThreadLocalValue<T> actual constructor(initial: () -> T) {
    private val local: ThreadLocal<T> = ThreadLocal.withInitial(initial)

    actual fun get(): T = local.get()
}

actual fun <T> frozenList(items: List<T>): List<T> = java.util.Collections.unmodifiableList(items)

actual fun <K, V> frozenMap(map: Map<K, V>): Map<K, V> = java.util.Collections.unmodifiableMap(map)

/** Reads a whole asset from [input], as the Android keyboard opens its assets. */
fun CorpusPrior.Companion.read(input: InputStream): CorpusPrior = read(input.readBytes())

/** Reads the whole model from [input]. */
fun CifgModel.Companion.load(input: InputStream): CifgModel = load(input.readBytes())

/** [LanguageProfile.speechTag] from the phone's locales, as the Android keyboard reads them. */
fun LanguageProfile.speechTag(phoneLocales: List<java.util.Locale>): String = speechTag(phoneLocales.map { it.toLanguageTag() })
