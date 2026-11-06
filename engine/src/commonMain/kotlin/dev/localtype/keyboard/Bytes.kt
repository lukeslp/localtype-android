package dev.localtype.keyboard

/**
 * Reads the engine's binary formats over a byte array with the java.nio.ByteBuffer calls they were
 * written with, big-endian unless [littleEndian]. Reading past the limit throws [Underflow], as
 * ByteBuffer throws BufferUnderflowException; an absolute index past it, IndexOutOfBoundsException.
 */
class ByteReader(
    private val bytes: ByteArray,
    offset: Int = 0,
    length: Int = bytes.size - offset,
    private val littleEndian: Boolean = false,
) {
    class Underflow : RuntimeException("read past the end")

    private var limit = offset + length
    var position: Int = offset
        private set

    init {
        require(offset in 0..bytes.size && length >= 0 && limit <= bytes.size)
    }

    fun limit(newLimit: Int) {
        require(newLimit in 0..bytes.size)
        limit = newLimit
        if (position > limit) position = limit
    }

    fun remaining(): Int = limit - position

    fun get(): Byte {
        if (position >= limit) throw Underflow()
        return bytes[position++]
    }

    fun get(dst: ByteArray) {
        if (remaining() < dst.size) throw Underflow()
        bytes.copyInto(dst, 0, position, position + dst.size)
        position += dst.size
    }

    fun getShort(): Short = relative(2).toShort()

    fun getInt(): Int = relative(4).toInt()

    fun getLong(): Long = relative(8)

    fun getFloat(): Float = Float.fromBits(getInt())

    fun getInt(index: Int): Int = absolute(index, 4).toInt()

    fun getFloat(index: Int): Float = Float.fromBits(getInt(index))

    private fun relative(size: Int): Long {
        if (remaining() < size) throw Underflow()
        return absolute(position, size).also { position += size }
    }

    private fun absolute(index: Int, size: Int): Long {
        if (index < 0 || index + size > limit) throw IndexOutOfBoundsException("index $index, limit $limit")
        var value = 0L
        if (littleEndian) {
            for (i in size - 1 downTo 0) value = (value shl 8) or (bytes[index + i].toLong() and 0xFF)
        } else {
            for (i in 0 until size) value = (value shl 8) or (bytes[index + i].toLong() and 0xFF)
        }
        return value
    }
}

/** Writes big-endian values as java.io.DataOutputStream does, into a growing byte array. */
class ByteWriter(capacity: Int = 256) {
    private var buffer = ByteArray(capacity.coerceAtLeast(16))
    var size: Int = 0
        private set

    fun writeByte(value: Int) {
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun writeShort(value: Int) = writeBits(value.toLong(), 2)

    fun writeInt(value: Int) = writeBits(value.toLong(), 4)

    fun writeLong(value: Long) = writeBits(value, 8)

    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun writeBits(value: Long, count: Int) {
        ensure(count)
        for (i in count - 1 downTo 0) buffer[size++] = (value ushr (8 * i)).toByte()
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size * 2
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}

/**
 * At most [capacity] entries, the least recently used going first: what LinkedHashMap(accessOrder = true)
 * with removeEldestEntry gave on the JVM. [get] counts as a use and [containsKey] doesn't, as there.
 */
class LruCache<K, V>(private val capacity: Int) {
    private val map = LinkedHashMap<K, V>()

    val size: Int get() = map.size

    fun containsKey(key: K): Boolean = map.containsKey(key)

    /** The value for [key], now the most recently used; null when absent, or when null was stored ([containsKey] tells). */
    operator fun get(key: K): V? {
        if (!map.containsKey(key)) return null
        @Suppress("UNCHECKED_CAST")
        val value = map.remove(key) as V
        map[key] = value
        return value
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        if (map.size > capacity) map.remove(map.keys.first())
    }

    fun clear() = map.clear()
}
