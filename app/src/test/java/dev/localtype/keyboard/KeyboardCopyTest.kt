package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardCopyTest {
    private val full = KeyboardCopy(
        versionName = "0.5.52",
        versionCode = 73,
        made = "2026-11-11",
        settings = KeyboardCopy.Settings(
            listOf(
                KeyboardCopy.Setting('b', "autocorrect", "0"),
                KeyboardCopy.Setting('i', "bottom_clearance", "8"),
                KeyboardCopy.Setting('s', "theme", "DARK"),
                KeyboardCopy.Setting('s', "settings_language", ""),
            ),
            listOf(KeyboardCopy.Tone("👍", 3)),
            defaultTone = 2,
        ),
        words = listOf(
            KeyboardCopy.Words("en", listOf(KeyboardCopy.Added("localtype", "LocalType"), KeyboardCopy.Added("zarf")), listOf("moist")),
            KeyboardCopy.Words("fa", listOf(KeyboardCopy.Added("می‌خواهم")), emptyList()),
        ),
        saved = listOf(
            KeyboardCopy.Saved("two\nlines\tand a tab \\ and a backslash", "addr"),
            KeyboardCopy.Saved("\r\nstarts with a Windows line end"),
            KeyboardCopy.Saved("😀 naïve"),
        ),
        learned = KeyboardCopy.Learned(
            listOf(
                KeyboardCopy.LearnedLanguage(
                    "en",
                    listOf(KeyboardCopy.Pair(AdaptiveNgramModel.START, "hello", 4), KeyboardCopy.Pair("hello", "there", 100)),
                    listOf(KeyboardCopy.Context(listOf("see", "you", "at"), "home", 3, 1, 42L)),
                    listOf(KeyboardCopy.Spelling("iphone", "iPhone")),
                ),
            ),
            listOf(KeyboardCopy.Site("datapoems.io", 3, 20_768L)),
            listOf("😀", "🎉"),
        ),
    )

    private fun ok(text: String) = (KeyboardCopy.decode(text) as KeyboardCopy.Read.Ok).copy

    @Test
    fun `every part comes back exactly, with tabs, line ends, backslashes, markers and other scripts`() {
        val text = KeyboardCopy.encode(full)
        assertEquals(full, ok(text))
        // One row to a line: nothing typed can start a line of its own.
        assertEquals(text.count { it == '\n' }, text.lines().count { it.isNotEmpty() })
        assertTrue("the sentence-start marker is written as an escape", "p\t\\x02\thello\t4" in text)
        assertEquals(full, ok(text.replace("\n", "\r\n")))
    }

    @Test
    fun `a part left out stays out and a part with nothing in it comes back empty`() {
        val bare = KeyboardCopy("0.5.52", 73, "2026-11-11")
        assertEquals(bare, ok(KeyboardCopy.encode(bare)))
        val empty = bare.copy(settings = KeyboardCopy.Settings(emptyList()), saved = emptyList(), learned = KeyboardCopy.Learned(emptyList()))
        val read = ok(KeyboardCopy.encode(empty))
        assertEquals(empty, read)
        assertNull(read.words)
        val noWords = bare.copy(words = emptyList())
        assertEquals(noWords, ok(KeyboardCopy.encode(noWords)))
        val onlyWords = bare.copy(words = listOf(KeyboardCopy.Words("en", emptyList(), listOf("moist"))))
        assertEquals(onlyWords, ok(KeyboardCopy.encode(onlyWords)))
        assertEquals(1, onlyWords.blockedCount)
        assertEquals(3, full.addedCount)
        assertEquals(2, full.pairCount)
    }

    @Test
    fun `another file is not a copy and a later format is named`() {
        assertEquals(KeyboardCopy.Read.NotACopy, KeyboardCopy.decode(""))
        assertEquals(KeyboardCopy.Read.NotACopy, KeyboardCopy.decode("# Gboard Dictionary version:1\n"))
        assertEquals(KeyboardCopy.Read.NotACopy, KeyboardCopy.decode("localtype-copy\n"))
        assertEquals(KeyboardCopy.Read.Newer(2), KeyboardCopy.decode("localtype-copy\t2\nanything at all\n"))
        assertEquals(KeyboardCopy.Read.Damaged, KeyboardCopy.decode("localtype-copy\t0\nend\t1\n"))
    }

    @Test
    fun `a copy cut short or with a line taken out or put in is refused whole`() {
        val text = KeyboardCopy.encode(full)
        val lines = text.trimEnd('\n').split('\n')
        assertEquals(KeyboardCopy.Read.Damaged, KeyboardCopy.decode(lines.dropLast(1).joinToString("\n")))
        assertEquals(KeyboardCopy.Read.Damaged, KeyboardCopy.decode(text.take(text.length / 2)))
        assertEquals(KeyboardCopy.Read.Damaged, KeyboardCopy.decode((lines.take(5) + lines.drop(6)).joinToString("\n")))
        assertEquals(KeyboardCopy.Read.Damaged, KeyboardCopy.decode((lines.take(5) + "a\tinjected" + lines.drop(5)).joinToString("\n")))
        assertEquals(KeyboardCopy.Read.Damaged, KeyboardCopy.decode(text + "a\tafter the end\n"))
    }

    @Test
    fun `an escape the format does not write is damage, never a guess`() {
        fun with(row: String) = KeyboardCopy.decode("localtype-copy\t1\npart\tsaved\n$row\nend\t3\n")
        assertEquals(listOf(KeyboardCopy.Saved("a\\b")), (with("t\ta\\\\b") as KeyboardCopy.Read.Ok).copy.saved)
        assertEquals(KeyboardCopy.Read.Damaged, with("t\ta\\qb"))
        assertEquals(KeyboardCopy.Read.Damaged, with("t\ta\\"))
        assertEquals(KeyboardCopy.Read.Damaged, with("t\ta\\x4"))
        // \x is only for control characters; a letter written that way was not written by this format.
        assertEquals(KeyboardCopy.Read.Damaged, with("t\ta\\x41"))
        // A sign is not a hex digit.
        assertEquals(KeyboardCopy.Read.Damaged, with("t\ta\\x-1"))
        assertEquals(KeyboardCopy.Read.Damaged, with("t\ta\\x+f"))
        assertEquals(listOf(KeyboardCopy.Saved("a\u0002b")), (with("t\ta\\x02b") as KeyboardCopy.Read.Ok).copy.saved)
        assertEquals(KeyboardCopy.Read.Damaged, with("t\t" + "a".repeat(40_000)))
        // The longest saved text, every character one that is written as four: Save must not write what Read refuses.
        val worst = KeyboardCopy("0.5.52", 73, "2026-11-11", saved = listOf(KeyboardCopy.Saved("\u0001".repeat(5_000))))
        assertEquals(worst, ok(KeyboardCopy.encode(worst)))
    }

    @Test
    fun `rows and parts this version does not know are left out and the rest is read`() {
        val text = listOf(
            "localtype-copy\t1",
            "app\t0.9.0\t200",
            "made\t2027-01-01",
            "part\tsettings",
            "b\tautocorrect\t1",
            "q\tsome_new_kind\tvalue",
            "b\ttoo\tmany\tfields",
            "part\tthemes",
            "b\tautocorrect\t0",
            "part\twords\ten",
            "a\tzarf",
            "x\tmoist",
            "z\tnew",
            "part\tlearned\ten",
            "p\thello\tthere\tmany",
            "c\tone two three four\tword\t1\t0\t5",
            "c\tone two\tword\t1\t0\t5",
            "end\t17",
        ).joinToString("\n")
        val copy = ok(text)
        assertEquals(listOf(KeyboardCopy.Setting('b', "autocorrect", "1")), copy.settings!!.values)
        assertEquals(listOf(KeyboardCopy.Words("en", listOf(KeyboardCopy.Added("zarf")), listOf("moist"))), copy.words)
        val learned = copy.learned!!.languages.single()
        assertTrue(learned.pairs.isEmpty())
        assertEquals(listOf(KeyboardCopy.Context(listOf("one", "two"), "word", 1, 0, 5L)), learned.contexts)
        assertNull(copy.saved)
    }

    @Test
    fun `a huge file of line ends is turned away at its first line`() {
        val started = System.nanoTime()
        assertEquals(KeyboardCopy.Read.NotACopy, KeyboardCopy.decode("\n".repeat(8_000_000)))
        assertEquals(KeyboardCopy.Read.Damaged, KeyboardCopy.decode("localtype-copy\t1\n" + "\n".repeat(2_000_000)))
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun `a file that is not text is not a copy, and a byte order mark is passed over`() {
        val text = KeyboardCopy.encode(full)
        assertEquals(KeyboardCopy.Read.Ok(full), CopyTransfer.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(KeyboardCopy.Read.Ok(full), CopyTransfer.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(KeyboardCopy.Read.NotACopy, CopyTransfer.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0xFF.toByte(), 0xFE.toByte())))
        assertEquals(KeyboardCopy.Read.NotACopy, CopyTransfer.decode(text.toByteArray(Charsets.UTF_16)))
    }
}
