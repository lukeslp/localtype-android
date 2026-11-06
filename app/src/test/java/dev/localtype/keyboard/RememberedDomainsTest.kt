package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedDomainsTest {
    /** Keeps what the list writes as the file's text, so every test also goes through the codec. */
    private class FileStore(var file: String = "") : RememberedDomains.Store {
        var writes = 0
        override fun load() = DomainCodec.decode(file)
        override fun save(entries: List<RememberedDomains.Entry>) {
            file = DomainCodec.encode(entries)
            writes++
        }
    }

    @Test
    fun `a host is held from the first time and offered from the second`() {
        val store = FileStore()
        val domains = RememberedDomains(store)
        domains.typed("datapoems.io", 100)
        assertEquals(listOf("datapoems.io"), domains.entries.map { it.host })
        assertTrue(domains.offered.isEmpty())
        domains.typed("datapoems.io", 101)
        assertEquals(listOf(RememberedDomains.Entry("datapoems.io", 2, 101)), domains.offered)
        // The offered list is kept between changes and follows each one.
        domains.typed("b.org", 101)
        domains.typed("b.org", 102)
        assertEquals(listOf("b.org", "datapoems.io"), domains.offered.map { it.host })
        domains.remove("b.org")
        assertEquals(listOf("datapoems.io"), domains.offered.map { it.host })
        assertEquals("localtype-domains 1\ndatapoems.io\t2\t101\n", store.file)
        assertEquals(domains.entries, RememberedDomains(FileStore(store.file)).entries)
    }

    @Test
    fun `the most typed host comes first, then the most recent, then by name`() {
        val domains = RememberedDomains(FileStore())
        for ((host, times, day) in listOf(Triple("b.com", 2, 5L), Triple("a.com", 2, 5L), Triple("often.org", 5, 1L), Triple("recent.net", 2, 9L))) {
            repeat(times) { domains.typed(host, day) }
        }
        assertEquals(listOf("often.org", "recent.net", "a.com", "b.com"), domains.offered.map { it.host })
    }

    @Test
    fun `a full list drops the host used longest ago, and taking one from the strip counts as use`() {
        val domains = RememberedDomains(FileStore())
        repeat(RememberedDomains.MAX) { domains.typed("site$it.com", 10L + it) }
        // The oldest is taken from the strip today, so the second oldest is the one that goes.
        domains.used("site0.com", 500)
        domains.typed("new.org", 501)
        val hosts = domains.entries.map { it.host }
        assertEquals(RememberedDomains.MAX, hosts.size)
        assertTrue("site0.com" in hosts && "new.org" in hosts)
        assertTrue("site1.com" !in hosts)
        // Taking a host from the strip is not typing it: the count stays.
        assertEquals(1, domains.entries.first { it.host == "site0.com" }.count)
    }

    @Test
    fun `removing one, or forgetting all, writes the file and tells the listener`() {
        val store = FileStore()
        val domains = RememberedDomains(store)
        var heard = 0
        domains.onChange = { heard++ }
        domains.typed("a.com", 1)
        domains.typed("b.com", 1)
        domains.remove("a.com")
        domains.remove("never.there")
        assertEquals(listOf("b.com"), domains.entries.map { it.host })
        assertEquals(3, heard)
        domains.clear()
        domains.clear()
        assertEquals("localtype-domains 1\n", store.file)
        assertEquals(4, heard)
        assertEquals(4, store.writes)
    }

    @Test
    fun `a damaged file loses its bad lines and nothing else`() {
        val file = "localtype-domains 1\ngood.com\t3\t7\nBad Host\t2\t7\nno-tld\t2\t7\nshort.io\t2\nx.org\tmany\t7\ngood.com\t9\t9\nhttps://x.com/path\t2\t7\nzero.net\t0\t7\n\nlast.dev\t2\t8\r\n"
        assertEquals(listOf(RememberedDomains.Entry("good.com", 3, 7), RememberedDomains.Entry("last.dev", 2, 8)), RememberedDomains(FileStore(file)).entries)
        assertTrue(RememberedDomains(FileStore("something else\ngood.com\t3\t7\n")).entries.isEmpty())
        assertTrue(RememberedDomains(FileStore("")).entries.isEmpty())
    }
}
