package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateBannerTest {
    private val hour = 60 * 60 * 1000L

    @Test
    fun `settings checks at most every six hours, and again if the clock went back`() {
        val now = 1_800_000_000_000L
        assertTrue(UpdateBanner.due(0L, now))
        assertFalse(UpdateBanner.due(now - 5 * hour, now))
        assertTrue(UpdateBanner.due(now - 6 * hour, now))
        assertTrue(UpdateBanner.due(now + hour, now))
    }

    @Test
    fun `the banner shows a newer installable release until Not now, and the next release brings it back`() {
        assertTrue(UpdateBanner.shows(installedCode = 48, latestCode = 49, installable = true, dismissedCode = 0))
        assertFalse(UpdateBanner.shows(48, 48, true, 0))
        assertFalse(UpdateBanner.shows(48, 47, true, 0))
        // A build this phone's processor can't load is never offered.
        assertFalse(UpdateBanner.shows(48, 49, installable = false, dismissedCode = 0))
        assertFalse(UpdateBanner.shows(48, 49, true, dismissedCode = 49))
        assertTrue(UpdateBanner.shows(48, 50, true, dismissedCode = 49))
        // Nothing found yet.
        assertFalse(UpdateBanner.shows(48, 0, false, 0))
    }

    @Test
    fun `a tip is one gesture and one effect`() {
        assertEquals("Hold a letter" to "Its accents.", SettingsTips.split("Hold a letter|Its accents."))
        assertThrows(IllegalArgumentException::class.java) { SettingsTips.split("no bar") }
        assertThrows(IllegalArgumentException::class.java) { SettingsTips.split("two|bars|here") }
        assertThrows(IllegalArgumentException::class.java) { SettingsTips.split("|empty gesture") }
    }

    @Test
    fun `every bundled tip parses, and each group has some`() {
        val xml = File("src/main/res/values/strings.xml").readText()
        val groups = listOf("tips_typing", "tips_editing", "tips_suggestions", "tips_saved", "tips_more")
        var total = 0
        for (group in groups) {
            val body = Regex("<string-array name=\"$group\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                ?: error("no $group")
            val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(body).map { it.groupValues[1] }.toList()
            assertTrue("$group is empty", items.size >= 3)
            items.forEach { SettingsTips.split(it.replace("&amp;", "&")) }
            total += items.size
            assertTrue("no heading for $group", xml.contains("<string name=\"tips_group_${group.removePrefix("tips_")}\">"))
        }
        assertTrue(total >= 20)
        assertFalse("the old one-paragraph tips are gone", xml.contains("name=\"tips_body\""))
    }
}
