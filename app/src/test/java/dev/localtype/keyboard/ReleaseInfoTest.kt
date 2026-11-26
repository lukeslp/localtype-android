package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {
    @Test
    fun `the catalog build's release notes are its own, not the download site's`() {
        assertEquals(ReleaseInfo.CATALOG_RELEASES_URL, ReleaseInfo.releaseNotesUrl("fdroid"))
        assertEquals(ReleaseInfo.CHANGELOG_URL, ReleaseInfo.releaseNotesUrl("full"))
        assertEquals(ReleaseInfo.CHANGELOG_URL, ReleaseInfo.releaseNotesUrl("play"))
        // The catalog build must not send anyone to the page that offers the other build.
        assertFalse(ReleaseInfo.CATALOG_RELEASES_URL.startsWith(ReleaseInfo.PAGE_URL))
        assertTrue(ReleaseInfo.CATALOG_RELEASES_URL.startsWith("https://github.com/lukeslp/localtype-android/"))
        // The flavor's name is the build file's.
        val build = java.io.File("build.gradle.kts").readText()
        assertTrue("create(\"${ReleaseInfo.CATALOG_FLAVOR}\")" in build)
    }

    @Test
    fun `the links every build shows stay off the download site`() {
        val downloadSite = "https://dr.eamer.dev/"
        // Shown only by a build that updates from the site (SettingsCoverageTest holds the rows to that).
        assertTrue(ReleaseInfo.PAGE_URL.startsWith(downloadSite + "downloads/"))
        assertEquals(downloadSite + "downloads/apps/locallauncher/", ReleaseInfo.LAUNCHER_URL)
        // Shown by the catalog and Google Play builds too.
        val everywhere = listOf(ReleaseInfo.SITE_URL, ReleaseInfo.SUPPORT_URL, ReleaseInfo.CHIME_URL, ReleaseInfo.BLUESKY_URL,
            ReleaseInfo.CATALOG_RELEASES_URL)
        everywhere.forEach { assertFalse(it, it.startsWith(downloadSite)) }
        everywhere.forEach { assertTrue(it, it.startsWith("https://")) }
        assertEquals(ReleaseInfo.SITE_URL + "chime/", ReleaseInfo.CHIME_URL)
        assertEquals(ReleaseInfo.SITE_URL + "#contact", ReleaseInfo.SUPPORT_URL)
    }

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

    @Test
    fun `a version code outside the plausible range gives nothing`() {
        fun feed(code: String) = ReleaseInfo.parse("""{"versionCode": $code, "versionName": "0.5.60"}""")
        assertEquals(1..999_999, ReleaseInfo.VERSION_CODES)
        assertEquals(ReleaseInfo(1, "0.5.60"), feed("1"))
        assertEquals(ReleaseInfo(81, "0.5.60"), feed("81"))
        assertEquals(ReleaseInfo(999_999, "0.5.60"), feed("999999"))
        // Int.MAX_VALUE followed by Not now would hide the banner for every later release.
        for (code in listOf("0", "1000000", "2147483647", "2147483648", "99999999999999999999", "-5", "\"81\"")) {
            assertNull(code, feed(code))
        }
    }

    @Test
    fun `a version name is letters, digits, dots, plus, hyphen and underscore`() {
        fun feed(name: String) = ReleaseInfo.parse("{\"versionCode\": 81, \"versionName\": \"$name\"}")
        for (name in listOf("0.5.60", "1", "2.0.0-rc.1", "1.0+build_7", "0.5.60-modellab", "a".repeat(40))) {
            assertEquals(name, feed(name)?.versionName)
        }
        val refused = listOf(
            "", "a".repeat(41), "0.5.60 is out. Tap here", "0.5.60\n0.5.61", "0.5.60\\n", "<b>0.5.60</b>", "0.5.60;", "0,5,60",
            "0.5.60\u202E", "\u200F0.5.60", "0.5.6\u0660", "0.5.60é",
        )
        for (name in refused) assertNull(name, feed(name))
    }
}
