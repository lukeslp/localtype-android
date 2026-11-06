package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-site rules, the redirect wrappers, and the Clean link offer for a link before the caret. */
class CleanLinkTest {
    private fun clean(text: String) = LinkCleaner.clean(text)

    @Test
    fun `Amazon loses its ref path segment and link tags, and keeps the affiliate tag and search state`() {
        assertEquals(
            "https://www.amazon.com/Some-Thing/dp/B0ABC12345?tag=friend-20&qid=1700000000&sr=8-3",
            clean("https://www.amazon.com/Some-Thing/dp/B0ABC12345/ref=sr_1_3?tag=friend-20&qid=1700000000&sr=8-3&ref_=nav_logo&linkCode=ll1&linkId=abc123"),
        )
        // With no query at all, only the path changes.
        assertEquals("https://amazon.co.uk/dp/B0ABC12345", clean("https://amazon.co.uk/dp/B0ABC12345/ref=cm_sw_r_cp"))
        assertEquals("https://smile.amazon.de/dp/B01/x#reviews", clean("https://smile.amazon.de/dp/B01/ref=x_1/x#reviews"))
    }

    @Test
    fun `a site's own trackers stay everywhere else`() {
        for (link in listOf(
            "https://example.com/dp/B01/ref=sr_1_3",
            "https://example.com/watch?feature=share&pp=1&s=20&trk=a&share_id=b&ref_=c&linkcode=d",
            "https://notamazon.com/dp/B01/ref=sr_1",
            "https://amazon.com.evil.example/dp/B01/ref=sr_1",
        )) assertSame(link, clean(link))
    }

    @Test
    fun `each listed site loses only its own trackers`() {
        assertEquals("https://www.youtube.com/watch?v=abc&t=42&list=PL1", clean("https://www.youtube.com/watch?v=abc&pp=ygUFaGVsbG8%3D&t=42&feature=share&list=PL1"))
        assertEquals("https://youtu.be/abc", clean("https://youtu.be/abc?feature=shared"))
        assertEquals("https://x.com/someone/status/1?t=Ab12", clean("https://x.com/someone/status/1?s=20&t=Ab12&ref_src=twsrc%5Etfw"))
        assertEquals("https://www.reddit.com/r/a/comments/b/c/?context=3", clean("https://www.reddit.com/r/a/comments/b/c/?share_id=Xyz&context=3"))
        assertEquals("https://www.ebay.com/itm/123", clean("https://www.ebay.com/itm/123?_trksid=p2047675.m4097&_trkparms=amclksrc%3DITM"))
        assertEquals("https://www.linkedin.com/in/someone", clean("https://www.linkedin.com/in/someone?trk=public_profile&trkInfo=AQ%3D%3D"))
        assertEquals("https://www.tiktok.com/@a/video/1?lang=en", clean("https://www.tiktok.com/@a/video/1?is_from_webapp=1&sender_device=pc&lang=en"))
    }

    @Test
    fun `a redirect wrapper gives way to the link it carries, which is cleaned too`() {
        assertEquals("https://example.com/page?id=7", clean("https://www.google.com/url?sa=t&source=web&url=https%3A%2F%2Fexample.com%2Fpage%3Fid%3D7%26utm_source%3Dg&usg=AOv"))
        assertEquals("https://example.com/a", clean("https://www.google.com/url?q=https://example.com/a&sa=D"))
        assertEquals("https://example.com/b", clean("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2Fb%3Ffbclid%3DIwAR&h=AT0"))
        assertEquals("https://example.com/c", clean("https://www.youtube.com/redirect?event=video_description&q=https%3A%2F%2Fexample.com%2Fc"))
        assertEquals("see https://example.com/a here", clean("see https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa here"))
    }

    @Test
    fun `a wrapper is left alone unless its target is a plain web link`() {
        for (link in listOf(
            "https://www.google.com/url?q=javascript%3Aalert(1)",
            "https://www.google.com/url?q=hello+world",
            "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa%20b",
            "https://www.google.com/url?q=https%3A%2F%2Fexample.com%ZZ",
            "https://www.google.com/url?q=https%3A%2F%2F",
            "https://www.google.com/search?q=https%3A%2F%2Fexample.com",
            "https://evil.example/url?q=https%3A%2F%2Fexample.com",
        )) assertSame(link, clean(link))
    }

    @Test
    fun `a wrapper inside a wrapper is unwrapped once`() {
        val inner = "https://www.google.com/url?q=https%3A%2F%2Fexample.com"
        assertEquals(inner, clean("https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.google.com%2Furl%3Fq%3Dhttps%253A%252F%252Fexample.com"))
    }

    private fun atCaret(before: String, after: String = "", whole: Boolean = true) = LinkCleaner.atCaret(before, after, whole)

    @Test
    fun `a link right before the caret is offered clean, punctuation and brackets kept after it`() {
        assertEquals(LinkCleaner.AtCaret("https://example.com/a?id=1&utm_source=feed".length, "https://example.com/a?id=1", "example.com"),
            atCaret("look at https://example.com/a?id=1&utm_source=feed"))
        val dotted = atCaret("look at https://example.com/a?utm_source=feed.", " Then")!!
        assertEquals("https://example.com/a.", dotted.text)
        assertEquals("https://example.com/a?utm_source=feed.".length, dotted.replace)
        assertEquals("https://example.com/a)", atCaret("[here](https://example.com/a?fbclid=x)")!!.text)
        assertEquals("https://example.com/a", atCaret("x https://example.com/a?gclid=1", ")")!!.text)
    }

    @Test
    fun `nothing is offered for a clean link, a caret inside one, or a link that may start before what was read`() {
        assertNull(atCaret("look at https://example.com/a?id=1"))
        assertNull(atCaret("look at https://example.com/a?utm_source=fe", "ed"))
        assertNull(atCaret("look at https://example.com/a?utm_source=feed", ".x"))
        assertNull(atCaret("https://example.com/a?utm_source=feed", whole = false))
        assertEquals("https://example.com/a", atCaret("https://example.com/a?utm_source=feed", whole = true)!!.text)
        assertNull(atCaret("https://example.com/a?utm_source=feed and then"))
        assertNull(atCaret("https://example.com/a?utm_source=feed....."))
        assertNull(atCaret("x".repeat(LinkCleaner.CARET_READ_CHARS) + " https://example.com/a?utm_source=feed"))
        assertNull(atCaret(""))
    }

    @Test
    fun `a link nested in another's query is part of it, and a run that may start before the read is refused`() {
        // The outer link has no tracker of its own; the nested one is its parameter's value, not a link to clean alone.
        assertNull(atCaret("see https://x.example/?u=https://y.example/?utm_source=z"))
        val long = "https://x.example/?u=" + "a".repeat(LinkCleaner.CARET_READ_CHARS) + "&next=https://y.example/?fbclid=1"
        assertNull(atCaret(long.takeLast(LinkCleaner.CARET_READ_CHARS), whole = false))
    }

    @Test
    fun `the last link in the run is the one offered`() {
        assertEquals("https://b.example/?keep=1", atCaret("(https://a.example/?utm_source=1)https://b.example/?keep=1&fbclid=2")!!.text)
    }

    @Test
    fun `an offer replaces exactly the link and its punctuation, and says where it goes`() {
        val found = atCaret("go https://www.amazon.com/dp/B01/ref=sr_1!")!!
        val offer = Expansions.link(found, "Clean link")
        assertEquals(Expansions.Kind.LINK, offer.kind)
        assertEquals("Clean link", offer.label)
        assertEquals("https://www.amazon.com/dp/B01/ref=sr_1!".length, offer.replace)
        assertEquals("https://www.amazon.com/dp/B01!", offer.text)
        assertEquals("www.amazon.com", offer.host)
    }

    @Test
    fun `the tool reads text, so never in a password field or a terminal`() {
        val plain = GestureRules.State(enabled = false)
        assertTrue(StripTools.allowed(StripTool.CLEAN_LINKS, plain))
        assertTrue(StripTools.allowed(StripTool.CLEAN_LINKS, plain.copy(learning = false)))
        assertFalse(StripTools.allowed(StripTool.CLEAN_LINKS, plain.copy(sensitive = true)))
        assertFalse(StripTools.allowed(StripTool.CLEAN_LINKS, plain.copy(terminal = true)))
        assertEquals(StripTools.Group.EDIT, StripTool.CLEAN_LINKS.group)
    }
}
