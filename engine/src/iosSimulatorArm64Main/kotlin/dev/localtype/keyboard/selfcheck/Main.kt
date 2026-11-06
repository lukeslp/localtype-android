package dev.localtype.keyboard.selfcheck

import dev.localtype.keyboard.EngineSelfCheck
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** Prints [EngineSelfCheck.report] for the assets in the directory given, run with `xcrun simctl spawn`. */
fun main(args: Array<String>) {
    val dir = args.firstOrNull() ?: error("usage: EngineSelfCheck <assets directory>")
    print(EngineSelfCheck.report { name -> readFile("$dir/$name") })
}

@OptIn(ExperimentalForeignApi::class)
private fun readFile(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path) ?: error("cannot read $path")
    val size = data.length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes
}
