package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainHostsTest {
    private val tlds = DomainHosts.parseTlds(File("src/main/assets/tlds.txt").readLines().asSequence())
    private fun host(run: String) = DomainHosts.host(run, tlds)

    @Test
    fun `the licensed list preserves the shipped TLD set without its comments`() {
        assertEquals(1437, tlds.size)
        assertTrue(listOf("com", "io", "dev", "org", "uk", "xn--p1ai").all { it in tlds })
        assertTrue(tlds.none { it.startsWith("#") || it != it.lowercase() || it.isBlank() })
    }

    @Test
    fun `only the host is taken from an address`() {
        assertEquals("datapoems.io", host("datapoems.io"))
        assertEquals("lukesteuber.com", host("https://www.LukeSteuber.com/about?x=1#top"))
        assertEquals("example.com", host("http://user:secret@example.com:8080/private/path"))
        assertEquals("dr.eamer.dev", host("dr.eamer.dev/downloads/apps/localtype/"))
        assertEquals("example.co.uk", host("someone@example.co.uk"))
        assertEquals("example.com", host("mailto:x@example.com"))
    }

    @Test
    fun `punctuation around an address in a sentence is not part of it`() {
        assertEquals("example.com", host("example.com."))
        assertEquals("example.com", host("(example.com),"))
        assertEquals("example.com", host("“example.com”"))
        assertEquals("example.com", host("example.com?"))
    }

    @Test
    fun `ordinary text with a dot in it is not a host`() {
        for (run in listOf("e.g.", "end.The", "3.14", "v2.0", "hello", "done.", "a.com", "192.168.1.1", "file.txt", "wait...",
            "-bad.com", "bad-.com", "ex ample.com", "example..com", ".com", "com", "ftp://example.com",
            "example.com:port", "@example.com", "x".repeat(64) + ".com")) {
            assertNull(run, host(run))
        }
    }

    @Test
    fun `a host still being typed is read without its scheme, user part or www`() {
        assertEquals("data", DomainHosts.typing("data"))
        assertEquals("datapoems.i", DomainHosts.typing("Datapoems.i"))
        assertEquals("exa", DomainHosts.typing("https://www.exa"))
        assertEquals("example.", DomainHosts.typing("example."))
        assertEquals("gm", DomainHosts.typing("luke@gm"))
        assertEquals("exa", DomainHosts.typing("(exa"))
    }

    @Test
    fun `once a path, a port or anything else has begun there is no host left to finish`() {
        for (run in listOf("example.com/", "example.com/pa", "example.com:80", "example.com?x", "ftp://exa", "", "https://", "luke@",
            "www.", "ex_ample", "exa!", "a..b", "-ab.c", "@exa", "(@exa")) {
            assertNull(run, DomainHosts.typing(run))
        }
    }
}
