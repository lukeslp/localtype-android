package dev.localtype.keyboard

import dev.localtype.keyboard.LicenseNotices.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Licenses and credits against the files that ship: every bundled notice is listed once, each library in the
 * build is named in a software notice, and a notice is shown with every word it holds.
 */
class LicenseNoticesTest {
    private val folder = File("src/main/assets/${LicenseNotices.FOLDER}")
    private val software = File("src/main/assets/${LicenseNotices.SOFTWARE_FOLDER}")
    private val modelSoftware = File("src/full/assets/${LicenseNotices.SOFTWARE_FOLDER}")

    private fun names(dir: File) = dir.list().orEmpty().toList()

    private fun bundled() = LicenseNotices.notices(names(folder), names(software))

    @Test
    fun `every bundled notice is listed once and nothing else is`() {
        val listed = bundled()
        val files = (folder.listFiles().orEmpty().filter { it.isFile }.map { "${LicenseNotices.FOLDER}/${it.name}" } +
            software.listFiles().orEmpty().filter { it.isFile }.map { "${LicenseNotices.SOFTWARE_FOLDER}/${it.name}" }).sorted()
        assertTrue("found ${files.size} notices", files.size >= 17)
        assertEquals(files, listed.map { it.path }.sorted())
        assertEquals(listed.size, listed.map { it.title }.toSet().size)
        // The folder's own entry for android/ is a name without .txt and is never a row.
        assertTrue(names(folder).any { it == "android" })
        assertTrue(listed.none { it.title == "android" })
    }

    @Test
    fun `credits come first, then software, then the license texts`() {
        val listed = bundled()
        assertEquals(listOf(Section.DATA, Section.SOFTWARE, Section.TEXTS), listed.map { it.section }.distinct())
        assertEquals(setOf("Apache-2.0", "CC-BY-4.0", "CC-BY-SA-4.0", "MPL-2.0"), listed.filter { it.section == Section.TEXTS }.map { it.title }.toSet())
        assertEquals(listOf("software-NOTICE"), listed.filter { it.section == Section.SOFTWARE }.map { it.title })
        val data = listed.filter { it.section == Section.DATA }.map { it.title }
        assertTrue(data.toString(), data.containsAll(listOf("cifg-NOTICE", "cifg-tatoeba-contributors", "wordlists-NOTICE", "SCOWL-Copyright", "Unicode-LICENSE")))
        assertEquals(data, data.sortedBy { it.lowercase() })
        // The build with the optional models lists its own software notice beside the shared one.
        val full = LicenseNotices.notices(names(folder), names(software) + names(modelSoftware))
        // The shared notice comes first: the other one says what it adds to it.
        assertEquals(listOf("software-NOTICE", "software-optional-models-NOTICE"), full.filter { it.section == Section.SOFTWARE }.map { it.title })
    }

    @Test
    fun `every row has a first line to show under its name`() {
        (bundled().map { File("src/main/assets/${it.path}") } + modelSoftware.listFiles().orEmpty()).forEach { file ->
            val summary = LicenseNotices.summary(file.readText().take(400))
            assertTrue("${file.name}: \"$summary\"", summary.length >= 10 && !summary.startsWith("#"))
        }
        assertEquals("Tatoeba contributors", LicenseNotices.summary("\n\n#  Tatoeba contributors \nCK\t1"))
        assertEquals("", LicenseNotices.summary("\n ---- \n"))
    }

    /** The Maven groups on [configuration] in the dependency lockfile: what that build is made of. */
    private fun groups(configuration: String): Set<String> = File("gradle.lockfile").readLines()
        .filter { '=' in it && !it.startsWith("#") && !it.startsWith("empty=") }
        .filter { configuration in it.substringAfter('=').split(',') }
        .map { it.substringBefore(':') }.toSet()

    /** The group names a notice lists: one to a line, indented under the library's name. */
    private fun named(file: File): Set<String> = file.readLines()
        .filter { it.startsWith("  ") }.map { it.trim() }.filter { Regex("""[a-z][a-z0-9_]*(\.[a-z0-9_]+)+""").matches(it) }.toSet()

    @Test
    fun `the software notices name every library group in each build, and no more`() {
        val catalog = groups("fdroidReleaseRuntimeClasspath")
        val full = groups("fullReleaseRuntimeClasspath")
        assertTrue("catalog ${catalog.size}, full ${full.size}", catalog.size >= 15 && full.size > catalog.size && full.containsAll(catalog))
        val shared = named(File(software, "software-NOTICE.txt"))
        val added = named(File(modelSoftware, "software-optional-models-NOTICE.txt"))
        // Every build shows the shared notice, so it names exactly the catalog build's groups: nothing the catalog
        // build lacks, such as Google Play services, may be credited there.
        assertEquals(catalog.sorted(), shared.sorted())
        // The other notice ships only with the optional models and covers the rest.
        assertEquals((full - catalog).sorted(), (added - catalog).sorted())
        assertEquals(emptySet<String>(), (shared + added) - full)
        // The Google Play build is the full build's libraries again.
        assertEquals(full, groups("playReleaseRuntimeClasspath"))
    }

    @Test
    fun `the play build takes the full build's notices`() {
        val build = File("build.gradle.kts").readText()
        assertTrue("assets.srcDir(\"src/full/assets\")" in build.substringAfter("getByName(\"play\")").substringBefore("}"))
        assertTrue(File(modelSoftware, "software-optional-models-NOTICE.txt").isFile)
        // The catalog build's sources hold no notice of their own: it shows only what every build shows.
        assertFalse(File("src/fdroid/assets/licenses").exists())
    }

    @Test
    fun `settings opens the screen and the manifest declares it`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        assertEquals(1, Regex("""R\.string\.licenses_title""").findAll(screen).count())
        assertTrue("LicensesActivity::class.java" in screen)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".LicensesActivity\"", "").substringBefore("/>")
        assertTrue(entry, "android:exported=\"false\"" in entry)
    }

    /** The words of [text], without the # that marks a file's own remark. */
    private fun words(text: String) = text.lines().joinToString("\n") { it.trimStart('#') }
        .split(Regex("""\s+""")).filter { it.isNotEmpty() }

    @Test
    fun `a shown notice keeps every word in order`() {
        (bundled().map { File("src/main/assets/${it.path}") } + modelSoftware.listFiles().orEmpty()).forEach { file ->
            val text = file.readText()
            val blocks = LicenseNotices.blocks(text)
            assertTrue(file.name, blocks.isNotEmpty() && blocks.none { it.text.isBlank() })
            // A word the page broke at its hyphen is closed up again, so compare without the break.
            val shown = words(blocks.joinToString("\n") { it.text }).joinToString(" ")
            val written = words(text).joinToString(" ").replace(Regex("""(?<=\p{L})- (?=\p{L})"""), "-")
            assertEquals(file.name, written, shown.replace(Regex("""(?<=\p{L})- (?=\p{L})"""), "-"))
            blocks.filter { !it.prose }.forEach { assertTrue(file.name, it.text.lines().size <= LicenseNotices.BLOCK_LINES) }
        }
    }

    @Test
    fun `sentences wrap to the screen and tables keep their lines`() {
        val text = listOf(
            "LocalType word lists",
            "",
            "words_en.txt and words_en_profanity.txt derive from Hermit Dave's FrequencyWords,",
            "The upstream README licenses content under Creative Commons Attribution-",
            "ShareAlike 4.0 International and code separately under MIT.",
            "",
            "# Tatoeba contributors whose English sentences trained this model.",
            "# username\tsentences",
            "CK\t374626",
            "Amastan\t139645",
            "",
            "                                 Apache License",
            "                           Version 2.0, January 2004",
            "",
            "   1. Definitions.",
            "",
            "      \"License\" shall mean the terms and conditions for use, reproduction,",
            "      and distribution as defined by Sections 1 through 9 of this document.",
            "",
            "  a. Adapted Material means material subject to Copyright and Similar",
            "     Rights that is derived from or based upon the Licensed Material.",
            "",
            "AndroidX libraries, from The Android Open Source Project",
            "  androidx.core",
            "",
            "Kotlin standard library, from JetBrains and the Kotlin contributors",
            "  org.jetbrains.kotlin",
            "",
            "       1. reproduce and Share the Licensed Material, in whole or in part; and",
            "       2. produce, reproduce, and Share Adapted Material.",
            "",
            "5.2. If You initiate litigation against any entity by asserting a patent",
            "infringement claim, then the rights granted to You by any and all Contributors",
            "2.1 of this License shall terminate.",
            "",
            "          4. If requested by the Licensor, You must remove any of the information required by Section 3(a)(1)(A) to the",
            "             extent reasonably practicable. A license applies as described in Section",
            "             (4) never produces Adapted Material.",
            "",
            "3. Taskmaster-3, human-written user and customer turns from the dataset at",
            "   https://github.com/google-research-datasets/Taskmaster/tree/master/TM-3-2020",
            "   Commit d92cb6af0d6a2c8b2d5c7f1e3a4b5c6d7e8f9a0b, licensed under CC BY 4.0.",
            "",
            "# The VarCon notice, for spellings_en_gb.txt. LocalType modified the data: a script keeps",
            "# a filtered list of American-to-British pairs.",
            "# Read on 2026-11-03.",
        ).joinToString("\n")
        val blocks = LicenseNotices.blocks(text)
        fun prose(text: String, indent: Int = 0) = LicenseNotices.Block(text, prose = true, indent = indent)
        fun kept(text: String, indent: Int = 0) = LicenseNotices.Block(text, prose = false, indent = indent)
        assertEquals(
            listOf(
                prose("LocalType word lists"),
                prose("words_en.txt and words_en_profanity.txt derive from Hermit Dave's FrequencyWords, The upstream README " +
                    "licenses content under Creative Commons Attribution-ShareAlike 4.0 International and code separately under MIT."),
                // A remark is a paragraph without its mark; a table's heading stays with it, and tabs become a gap.
                prose("Tatoeba contributors whose English sentences trained this model."),
                kept("# username   sentences\nCK   374626\nAmastan   139645"),
                // A centered heading keeps its lines and loses only the indentation they share.
                kept("      Apache License\nVersion 2.0, January 2004", indent = 27),
                prose("1. Definitions.", indent = 3),
                prose("\"License\" shall mean the terms and conditions for use, reproduction, and distribution as defined by " +
                    "Sections 1 through 9 of this document.", indent = 6),
                // A lettered clause's lines hang under it and are one paragraph.
                prose("a. Adapted Material means material subject to Copyright and Similar Rights that is derived from or " +
                    "based upon the Licensed Material.", indent = 2),
                // A name over its indented entries is a list, however long the name.
                kept("AndroidX libraries, from The Android Open Source Project\n  androidx.core"),
                kept("Kotlin standard library, from JetBrains and the Kotlin contributors\n  org.jetbrains.kotlin"),
                kept("1. reproduce and Share the Licensed Material, in whole or in part; and\n2. produce, reproduce, and Share Adapted Material.", indent = 7),
                // A sentence that wraps at a section number or a bracketed reference is still a sentence.
                prose("5.2. If You initiate litigation against any entity by asserting a patent infringement claim, then the " +
                    "rights granted to You by any and all Contributors 2.1 of this License shall terminate."),
                prose("4. If requested by the Licensor, You must remove any of the information required by Section 3(a)(1)(A) to the " +
                    "extent reasonably practicable. A license applies as described in Section (4) never produces Adapted Material.", indent = 10),
                // A numbered entry with a link on its own line is one fact to a line.
                kept("3. Taskmaster-3, human-written user and customer turns from the dataset at\n" +
                    "   https://github.com/google-research-datasets/Taskmaster/tree/master/TM-3-2020\n" +
                    "   Commit d92cb6af0d6a2c8b2d5c7f1e3a4b5c6d7e8f9a0b, licensed under CC BY 4.0."),
                // A remark that stops mid-sentence runs on into the next remark line; one that ended starts a new paragraph.
                prose("The VarCon notice, for spellings_en_gb.txt. LocalType modified the data: a script keeps a filtered list of " +
                    "American-to-British pairs."),
                prose("Read on 2026-11-03."),
            ),
            blocks,
        )
    }

    @Test
    fun `numbered entries at one depth in a notice are drawn alike`() {
        // One numbered list half joined and half kept reads as two lists. Entries of several lines are compared;
        // a one-line heading such as "1. Definitions." is a paragraph either way.
        val numbered = Regex("""^\d+\.\s""")
        (bundled().map { File("src/main/assets/${it.path}") } + modelSoftware.listFiles().orEmpty()).forEach { file ->
            val text = file.readText()
            val runs = text.split(Regex("""\n\s*\n""")).map { run -> run.lines().filter { it.isNotBlank() } }.filter { it.size > 1 }
            val entries = runs.filter { numbered.containsMatchIn(it.first().trimStart()) }
            val drawn = entries.groupBy({ it.first().length - it.first().trimStart().length }) { lines ->
                LicenseNotices.blocks(lines.joinToString("\n")).map { it.prose }.toSet()
            }
            drawn.forEach { (indent, kinds) -> assertTrue("${file.name}: entries at indent $indent drawn as ${kinds.toSet()}", kinds.toSet().size <= 1) }
        }
    }

    @Test
    fun `a long list is cut into blocks of forty lines`() {
        val list = (1..95).joinToString("\n") { "name$it\t$it" }
        val blocks = LicenseNotices.blocks(list)
        assertEquals(listOf(40, 40, 15), blocks.map { it.text.lines().size })
        assertTrue(blocks.none { it.prose })
        assertEquals(list.replace("\t", "   "), blocks.joinToString("\n") { it.text })
    }
}
