package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LinkCleanerTest {
    private fun clean(text: String) = LinkCleaner.clean(text)

    @Test
    fun `campaign tags and click identifiers go and everything else stays in order`() {
        assertEquals(
            "https://example.com/post?id=7&page=2&sort=new",
            clean("https://example.com/post?utm_source=newsletter&id=7&utm_medium=email&page=2&fbclid=AbC123&sort=new&utm_campaign=x"),
        )
        for (name in listOf("gclid", "gbraid", "wbraid", "dclid", "msclkid", "mc_eid", "mc_cid", "igshid", "igsh", "twclid", "ttclid", "yclid", "_hsenc", "_hsmi", "mkt_tok", "srsltid", "utm_anything_at_all")) {
            assertEquals(name, "https://example.com/a?keep=1", clean("https://example.com/a?keep=1&$name=zzz"))
        }
    }

    @Test
    fun `a query made only of trackers loses its question mark`() {
        assertEquals("https://example.com/article", clean("https://example.com/article?utm_source=a&utm_medium=b"))
        assertEquals("https://example.com/article", clean("https://example.com/article?fbclid"))
        assertEquals("https://example.com/", clean("https://example.com/?&utm_source=a&"))
    }

    @Test
    fun `the fragment is kept`() {
        assertEquals("https://example.com/doc#section-2", clean("https://example.com/doc?utm_source=a#section-2"))
        assertEquals("https://example.com/doc?v=3#section-2", clean("https://example.com/doc?v=3&gclid=x#section-2"))
    }

    @Test
    fun `a question mark inside the fragment is not a query`() {
        val link = "https://example.com/app#/route?utm_source=a"
        assertSame(link, clean(link))
    }

    @Test
    fun `si is a share identifier on YouTube and Spotify only`() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", clean("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42", clean("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=AbCdEf123&t=42"))
        assertEquals("https://user@music.youtube.com:443/watch?v=1", clean("https://user@music.youtube.com:443/watch?v=1&si=x"))
        assertEquals("https://open.spotify.com/track/abc", clean("https://open.spotify.com/track/abc?si=0123"))
        val other = "https://example.com/units?si=metric"
        assertSame(other, clean(other))
        // A host that only ends with the same letters is another site.
        val lookalike = "https://notyoutube.com/watch?si=1"
        assertSame(lookalike, clean(lookalike))
    }

    @Test
    fun `names are matched whole and without regard to case`() {
        assertEquals("https://example.com/a", clean("https://example.com/a?UTM_Source=x&FBCLID=y"))
        val kept = "https://example.com/a?myutm_source=1&gclid2=2&utm=3&igshidx=4&xfbclid=5"
        assertSame(kept, clean(kept))
    }

    @Test
    fun `links inside a message are cleaned where they stand`() {
        assertEquals(
            "Look at this (https://example.com/a?id=1), then https://example.org/b. Done!",
            clean("Look at this (https://example.com/a?id=1&utm_source=chat), then https://example.org/b?fbclid=q. Done!"),
        )
        assertEquals("See https://example.com/a?x=(1)", clean("See https://example.com/a?x=(1)&gclid=2"))
        assertEquals("https://example.com/a\nhttps://example.com/b?k=v", clean("https://example.com/a?utm_source=x\nhttps://example.com/b?k=v&utm_term=y"))
    }

    @Test
    fun `text with nothing to remove comes back as the same object`() {
        for (text in listOf(
            "", "no link here", "https://example.com", "https://example.com/a?b=c&d=e#f",
            "mailto:someone@example.com?subject=utm_source=x", "example.com/a?utm_source=x", "ftp://example.com/a?utm_source=x",
            "intent://example.com/?utm_source=x#Intent;scheme=https;end",
        )) assertSame(text, clean(text))
    }

    @Test
    fun `cleaning twice changes nothing more`() {
        val once = clean("Two: https://example.com/a?utm_source=x&k=v and https://youtu.be/abc?si=1&t=5.")
        assertEquals("Two: https://example.com/a?k=v and https://youtu.be/abc?t=5.", once)
        assertSame(once, clean(once))
    }

    @Test
    fun `very long text is left alone`() {
        val long = "https://example.com/a?utm_source=x " + "a".repeat(LinkCleaner.MAX_CHARS)
        assertSame(long, clean(long))
    }

    @Test
    fun `a link ends at a bracket it did not open, so the next Markdown link survives`() {
        assertEquals(
            "[One](https://a.example/)[Two](https://b.example/)",
            clean("[One](https://a.example/?utm_source=test)[Two](https://b.example/)"),
        )
        assertEquals(
            "[One](https://a.example/)[Two](https://b.example/?k=v)",
            clean("[One](https://a.example/?utm_source=test)[Two](https://b.example/?fbclid=x&k=v)"),
        )
        // Brackets the link opened itself belong to it.
        assertEquals("https://en.wikipedia.org/wiki/Mercury_(planet)", clean("https://en.wikipedia.org/wiki/Mercury_(planet)?utm_source=x"))
        assertEquals("<https://a.example/><https://b.example/>", clean("<https://a.example/?gclid=1><https://b.example/?gclid=2>"))
        assertEquals("`https://a.example/` and \"https://b.example/\"", clean("`https://a.example/?gclid=1` and \"https://b.example/?gclid=2\""))
    }

    @Test
    fun `a tracker whose value is not a plain token is left alone`() {
        // Two links run together with a comma: the second would otherwise be deleted as the first one's value.
        for (text in listOf(
            "https://a.example/?utm_source=x,https://b.example/",
            "https://a.example/?utm_source=x;https://b.example/page",
            "https://a.example/?utm_campaign=2026/10",
            // No "=": the whole parameter would be the name.
            "https://a.example/?utm_source,https://b.example/",
            "https://a.example/?utm_source;x&id=1",
            "https://a.example/?fbclid=a|b",
        )) assertSame(text, clean(text))
        // Encoded and padded values are plain tokens.
        assertEquals("https://a.example/", clean("https://a.example/?utm_content=a%20b+c&mkt_tok=MTIz-NDU_Ng%3D%3D&mc_eid=abc=="))
        assertEquals("https://a.example/?next=https://b.example/home", clean("https://a.example/?next=https://b.example/home&utm_source=mail"))
    }

    @Test
    fun `http inside another word is not a link`() {
        val text = "xhttps://a.example/?utm_source=x and shttp://b.example/?gclid=1"
        assertSame(text, clean(text))
        assertEquals("HTTPS://A.EXAMPLE/", clean("HTTPS://A.EXAMPLE/?UTM_SOURCE=X"))
    }

    @Test
    fun `undo needs the caret where the paste left it`() {
        val at = EditorEditState.Selection(40, 40)
        assertEquals(true, LinkCleaner.caretUnmoved(40, at))
        assertEquals(false, LinkCleaner.caretUnmoved(40, EditorEditState.Selection(19, 19)))
        assertEquals(false, LinkCleaner.caretUnmoved(40, EditorEditState.Selection(30, 40)))
        // A place that is not known, then or now, is not a place to undo at.
        assertEquals(false, LinkCleaner.caretUnmoved(null, at))
        assertEquals(false, LinkCleaner.caretUnmoved(40, null))
        assertEquals(false, LinkCleaner.caretUnmoved(null, null))
    }
}
