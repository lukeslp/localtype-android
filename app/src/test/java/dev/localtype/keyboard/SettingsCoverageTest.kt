package dev.localtype.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The settings reorganization moved every control; this keeps any setting from being left without one. */
class SettingsCoverageTest {
    /** Setters the keyboard, setup or the update check use, not a person on the settings screen. */
    private val internal = setOf(
        "setLanguage", "setSetupComplete", "setSetupStep", "setTourSeen", "setChecked", "setUpdateDismissedCode",
    )

    @Test
    fun `every setting has a control on the settings screen`() {
        val settings = File("src/main/java/dev/localtype/keyboard/KeyboardSettings.kt").readText()
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        // Public setters only: a private helper such as setOffered is how the others store their value.
        val setters = Regex("""^\s*fun (set[A-Z]\w*)\(""", RegexOption.MULTILINE).findAll(settings).map { it.groupValues[1] }.toSet() - internal
        assertTrue("found ${setters.size} setters", setters.size >= 25)
        val missing = setters.filter { "KeyboardSettings.$it(" !in screen }
        assertTrue("no control for $missing", missing.isEmpty())
    }

    @Test
    fun `web links open only through the guarded helper`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        // openInBrowser catches a phone with no browser; a second ACTION_VIEW would bypass that.
        val views = Regex("""Intent\(Intent\.ACTION_VIEW""").findAll(screen).count()
        assertTrue("found $views ACTION_VIEW intents", views == 1)
    }

    @Test
    fun `only a build that updates from the download page links to it in About`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        // The page offers the full build as a file. Google Play forbids pointing there, and to the catalog build it is another app.
        val rows = screen.lines().filter { "R.string.about_download_page)" in it }
        assertTrue("found ${rows.size} download page rows", rows.size == 1)
        assertTrue(rows.single(), rows.single().trimStart().startsWith("if (BuildConfig.DIRECT_UPDATES) addLink("))
        assertTrue(rows.single(), rows.single().trimEnd().endsWith("{ openInBrowser(ReleaseInfo.PAGE_URL) }"))
    }

    @Test
    fun `only a build that updates from the download site links to LocalLauncher's page there`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        // The page is on the download site, so the row follows the download page row's rule: not in the Google Play
        // build, and not in the catalog build.
        val rows = screen.lines().filter { "R.string.about_launcher)" in it }
        assertTrue("found ${rows.size} LocalLauncher rows", rows.size == 1)
        assertTrue(rows.single(), rows.single().trimStart().startsWith("if (BuildConfig.DIRECT_UPDATES) addLink("))
        assertTrue(rows.single(), rows.single().trimEnd().endsWith("{ openInBrowser(ReleaseInfo.LAUNCHER_URL) }"))
        // That row is the only thing in any build's source that uses the address, and only ReleaseInfo spells it.
        val sources = File("src").listFiles()!!.filter { it.isDirectory && !it.name.startsWith("test") && it.name != "androidTest" }
            .flatMap { folder -> folder.walk().filter { it.isFile && it.extension in setOf("kt", "java", "xml") } }
        assertTrue("found ${sources.size} source files", sources.size > 100)
        assertTrue(sources.sumOf { Regex("""\bLAUNCHER_URL\b""").findAll(it.readText()).count() } == 2)
        assertTrue(sources.filter { "downloads/apps/locallauncher" in it.readText() }.map { it.name } == listOf("ReleaseInfo.kt"))
    }

    @Test
    fun `the rows that ask for a language or a word pack, and Chime's, are in every build`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        fun row(name: String) = screen.lines().filter { "R.string.$name)" in it }.also { assertTrue("$name: ${it.size} rows", it.size == 1) }.single().trim()
        // Both asks go to the contact page that Get help or send feedback opens.
        assertTrue(row("languages_ask") == "addLink(body, getString(R.string.languages_ask)) { openInBrowser(ReleaseInfo.SUPPORT_URL) }")
        assertTrue(row("word_packs_ask") == "addLink(body, getString(R.string.word_packs_ask)) { openInBrowser(ReleaseInfo.SUPPORT_URL) }")
        assertTrue(row("about_chime").let { it.startsWith("addLink(body, ") && it.endsWith("{ openInBrowser(ReleaseInfo.CHIME_URL) }") })
        // Each sits in its own section.
        fun body(start: String) = screen.substringAfter("private fun $start()").substringBefore("\n    }\n")
        assertTrue("R.string.languages_ask" in body("addLanguages"))
        assertTrue("R.string.word_packs_ask" in body("addWordPacks"))
        assertTrue("R.string.about_chime" in body("addAbout") && "R.string.about_launcher" in body("addAbout"))
    }

    @Test
    fun `release notes open through the rule that knows the catalog build`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        val rows = screen.lines().filter { "R.string.about_release_notes)" in it }
        assertTrue("found ${rows.size} release notes rows", rows.size == 1)
        assertTrue(rows.single(), "openInBrowser(ReleaseInfo.releaseNotesUrl(BuildConfig.FLAVOR))" in rows.single())
        // Nothing else in settings opens the download site's release notes directly.
        assertTrue("ReleaseInfo.CHANGELOG_URL" !in screen)
    }

    @Test
    fun `only a build that updates from the download page can open it`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        // The banner's button opens the page, so the banner is built only for that build; the catalog and Play
        // builds are updated by whatever installed them and never show it.
        assertTrue("if (BuildConfig.DIRECT_UPDATES) addUpdateBanner()" in screen)
        assertTrue(Regex("""^\s*addUpdateBanner\(\)""", RegexOption.MULTILINE).findAll(screen).none())
        // The banner is optional everywhere it is read.
        assertTrue("val banner = updateBanner ?: return" in screen)
    }
}
