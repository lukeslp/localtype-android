package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {
    @Test
    fun `reads the published version`() {
        val info = ReleaseInfo.parse("""{"versionCode": 14, "versionName": "0.4.4", "apks": {"llm": {"file": "x.apk"}}}""")
        assertEquals(ReleaseInfo(14, "0.4.4"), info)
        assertTrue(info!!.isNewerThan(13))
        assertFalse(info.isNewerThan(14))
    }

    @Test
    fun `a release names the processors it runs on`() {
        val info = ReleaseInfo.parse("""{"versionCode": 37, "versionName": "0.5.17", "abis": ["arm64-v8a"], "apks": {}}""")
        assertEquals(ReleaseInfo(37, "0.5.17", listOf("arm64-v8a")), info)
        assertTrue(info!!.installable(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(info.installable(arrayOf("x86_64", "x86")))
        assertFalse(info.installable(emptyArray()))
    }

    @Test
    fun `a release without the list runs anywhere`() {
        val info = ReleaseInfo.parse("""{"versionCode": 36, "versionName": "0.5.16"}""")!!
        assertEquals(emptyList<String>(), info.abis)
        assertTrue(info.installable(arrayOf("x86_64")))
        assertTrue(ReleaseInfo.parse("""{"versionCode": 36, "versionName": "0.5.16", "abis": []}""")!!.installable(arrayOf("x86_64")))
    }

    @Test
    fun `malformed or partial files give nothing`() {
        assertNull(ReleaseInfo.parse("<html>not found</html>"))
        assertNull(ReleaseInfo.parse("""{"versionCode": 14}"""))
    }
}
