package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean link on the facade the iPhone keyboard calls. TypingEngine.Links is a copy of the app's LinkCleaner, so
 * both run on the same links here: the cases of LinkCleanerTest and CleanLinkTest, and some more where a copy
 * could differ (characters outside the basic plane, white space that is not a space, broken escapes).
 */
class TypingEngineLinksTest {
    private val texts = listOf(
        // LinkCleanerTest.
        "https://example.com/post?utm_source=newsletter&id=7&utm_medium=email&page=2&fbclid=AbC123&sort=new&utm_campaign=x",
        "https://example.com/article?utm_source=a&utm_medium=b", "https://example.com/article?fbclid",
        "https://example.com/?&utm_source=a&", "https://example.com/doc?utm_source=a#section-2",
        "https://example.com/doc?v=3&gclid=x#section-2", "https://example.com/app#/route?utm_source=a",
        "https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=AbCdEf123&t=42",
        "https://user@music.youtube.com:443/watch?v=1&si=x", "https://open.spotify.com/track/abc?si=0123",
        "https://example.com/units?si=metric", "https://notyoutube.com/watch?si=1",
        "https://example.com/a?UTM_Source=x&FBCLID=y", "https://example.com/a?myutm_source=1&gclid2=2&utm=3&igshidx=4&xfbclid=5",
        "Look at this (https://example.com/a?id=1&utm_source=chat), then https://example.org/b?fbclid=q. Done!",
        "See https://example.com/a?x=(1)&gclid=2", "https://example.com/a?utm_source=x\nhttps://example.com/b?k=v&utm_term=y",
        "", "no link here", "https://example.com", "https://example.com/a?b=c&d=e#f",
        "mailto:someone@example.com?subject=utm_source=x", "example.com/a?utm_source=x", "ftp://example.com/a?utm_source=x",
        "intent://example.com/?utm_source=x#Intent;scheme=https;end",
        "Two: https://example.com/a?utm_source=x&k=v and https://youtu.be/abc?si=1&t=5.",
        "Two: https://example.com/a?k=v and https://youtu.be/abc?t=5.",
        "[One](https://a.example/?utm_source=test)[Two](https://b.example/)",
        "[One](https://a.example/?utm_source=test)[Two](https://b.example/?fbclid=x&k=v)",
        "https://en.wikipedia.org/wiki/Mercury_(planet)?utm_source=x", "<https://a.example/?gclid=1><https://b.example/?gclid=2>",
        "`https://a.example/?gclid=1` and \"https://b.example/?gclid=2\"",
        "https://a.example/?utm_source=x,https://b.example/", "https://a.example/?utm_source=x;https://b.example/page",
        "https://a.example/?utm_campaign=2026/10", "https://a.example/?utm_source,https://b.example/",
        "https://a.example/?utm_source;x&id=1", "https://a.example/?fbclid=a|b",
        "https://a.example/?utm_content=a%20b+c&mkt_tok=MTIz-NDU_Ng%3D%3D&mc_eid=abc==",
        "https://a.example/?next=https://b.example/home&utm_source=mail",
        "xhttps://a.example/?utm_source=x and shttp://b.example/?gclid=1", "HTTPS://A.EXAMPLE/?UTM_SOURCE=X",
        // CleanLinkTest.
        "https://www.amazon.com/Some-Thing/dp/B0ABC12345/ref=sr_1_3?tag=friend-20&qid=1700000000&sr=8-3&ref_=nav_logo&linkCode=ll1&linkId=abc123",
        "https://amazon.co.uk/dp/B0ABC12345/ref=cm_sw_r_cp", "https://smile.amazon.de/dp/B01/ref=x_1/x#reviews",
        "https://example.com/dp/B01/ref=sr_1_3", "https://example.com/watch?feature=share&pp=1&s=20&trk=a&share_id=b&ref_=c&linkcode=d",
        "https://notamazon.com/dp/B01/ref=sr_1", "https://amazon.com.evil.example/dp/B01/ref=sr_1",
        "https://www.youtube.com/watch?v=abc&pp=ygUFaGVsbG8%3D&t=42&feature=share&list=PL1", "https://youtu.be/abc?feature=shared",
        "https://x.com/someone/status/1?s=20&t=Ab12&ref_src=twsrc%5Etfw", "https://www.reddit.com/r/a/comments/b/c/?share_id=Xyz&context=3",
        "https://www.ebay.com/itm/123?_trksid=p2047675.m4097&_trkparms=amclksrc%3DITM",
        "https://www.linkedin.com/in/someone?trk=public_profile&trkInfo=AQ%3D%3D",
        "https://www.tiktok.com/@a/video/1?is_from_webapp=1&sender_device=pc&lang=en",
        "https://www.google.com/url?sa=t&source=web&url=https%3A%2F%2Fexample.com%2Fpage%3Fid%3D7%26utm_source%3Dg&usg=AOv",
        "https://www.google.com/url?q=https://example.com/a&sa=D", "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2Fb%3Ffbclid%3DIwAR&h=AT0",
        "https://www.youtube.com/redirect?event=video_description&q=https%3A%2F%2Fexample.com%2Fc",
        "see https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa here",
        "https://www.google.com/url?q=javascript%3Aalert(1)", "https://www.google.com/url?q=hello+world",
        "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa%20b", "https://www.google.com/url?q=https%3A%2F%2Fexample.com%ZZ",
        "https://www.google.com/url?q=https%3A%2F%2F", "https://www.google.com/search?q=https%3A%2F%2Fexample.com",
        "https://evil.example/url?q=https%3A%2F%2Fexample.com",
        "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.google.com%2Furl%3Fq%3Dhttps%253A%252F%252Fexample.com",
        // Where a copy could differ.
        "https://a.example/?fbclid=a🙂b", "https://a.example/?utm_source=𝐀", "https://a.example/?utm_source=é",
        "https://a.example/🙂?gclid=1", "https://a.example/?gclid=1 https://b.example/?gclid=2",
        "https://a.example/?gclid=1 next", "https://a.example/?gclid=1\r\nhttps://b.example/?gclid=2",
        "https://a.example/?gclid=1́", "https://a.example/?gclid=1​", "🙂https://a.example/?gclid=1",
        "éhttps://a.example/?gclid=1", "1http://a.example/?gclid=1", "_https://a.example/?gclid=1",
        "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%E2%82", "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%E2%82%AC",
        "https://www.google.com/url?q=https%3A%2F%2Fexample.com%4", "https://www.google.com/url?q=https%3A%2F%2Fexample.com%",
        "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%07", "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%C2%85",
        "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F🙂%41", "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%+f",
        "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%-f", "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%C0%AF",
        "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2F%ED%A0%80", "https://www.google.com/url?Q=https%3A%2F%2Fexample.com%2F",
        "https://www.google.com/url?q&url=https%3A%2F%2Fexample.com%2F", "https://www.google.com/url#x?q=https%3A%2F%2Fexample.com",
        "https://WWW.GOOGLE.COM/url?q=HTTPS%3A%2F%2Fexample.com%2F%3Futm_source%3Dx", "https://www.google.com/URL?q=https%3A%2F%2Fexample.com",
        "https://www.amazon.com/ref=", "https://www.amazon.com/ref=a/ref=b", "https://www.amazon.com/dp/ref=a,b", "https://amazon.com",
        "http://", "https://?utm_source=x", "://", "https://a.example/?utm_source=x#", "https://a.example/?#utm_source=x",
        "https://a.example/?a=1&&utm_source=x&&", "https://a.example/?utm_source=x)", "(https://a.example/?utm_source=x))",
        "https://a.example/?utm_source=x'.", "https://a.example/?utm_ſource=x", "https://a.example/?UTM_İD=x",
    )

    /** before, after, whole. */
    private val carets = listOf(
        Triple("look at https://example.com/a?id=1&utm_source=feed", "", true),
        Triple("look at https://example.com/a?utm_source=feed.", " Then", true),
        Triple("[here](https://example.com/a?fbclid=x)", "", true),
        Triple("x https://example.com/a?gclid=1", ")", true),
        Triple("look at https://example.com/a?id=1", "", true),
        Triple("look at https://example.com/a?utm_source=fe", "ed", true),
        Triple("look at https://example.com/a?utm_source=feed", ".x", true),
        Triple("look at https://example.com/a?utm_source=feed", ".", true),
        Triple("look at https://example.com/a?utm_source=feed", ". ", true),
        Triple("look at https://example.com/a?utm_source=feed", "\n", true),
        Triple("look at https://example.com/a?utm_source=feed", " x", true),
        Triple("look at https://example.com/a?utm_source=feed", "🙂", true),
        Triple("look at https://example.com/a?utm_source=feed", "\"", true),
        Triple("https://example.com/a?utm_source=feed", "", false),
        Triple("https://example.com/a?utm_source=feed", "", true),
        Triple("https://example.com/a?utm_source=feed and then", "", true),
        Triple("https://example.com/a?utm_source=feed.....", "", true),
        Triple("https://example.com/a?utm_source=feed...", "", true),
        Triple("x".repeat(LinkCleaner.CARET_READ_CHARS) + " https://example.com/a?utm_source=feed", "", true),
        Triple("x".repeat(LinkCleaner.CARET_READ_CHARS - 38) + " https://example.com/a?utm_source=feed", "", true),
        Triple("", "", true),
        Triple("see https://x.example/?u=https://y.example/?utm_source=z", "", true),
        Triple(("https://x.example/?u=" + "a".repeat(LinkCleaner.CARET_READ_CHARS) + "&next=https://y.example/?fbclid=1").takeLast(LinkCleaner.CARET_READ_CHARS), "", false),
        Triple("(https://a.example/?utm_source=1)https://b.example/?keep=1&fbclid=2", "", true),
        Triple("go https://www.amazon.com/dp/B01/ref=sr_1!", "", true),
        Triple("go https://youtu.be/abc?si=XYZ", "", false),
        Triple("go\nhttps://youtu.be/abc?si=XYZ).", "", false),
        Triple("🙂https://youtu.be/abc?si=XYZ", "", true),
        Triple("see https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa%3Futm_source%3Dx&sa=D", "", true),
        Triple("see https://user@Example.COM:8080/a?gclid=1#top", "", true),
    )

    @Test
    fun `the limits are the app's`() {
        assertEquals(LinkCleaner.MAX_CHARS, TypingEngine.Links.MAX_CHARS)
        assertEquals(LinkCleaner.CARET_READ_CHARS, TypingEngine.Links.CARET_READ_CHARS)
    }

    @Test
    fun `text is cleaned as the app cleans it, and text with nothing to remove gives null`() {
        var changed = 0
        for (text in texts) {
            val app = LinkCleaner.clean(text)
            val here = TypingEngine.Links.clean(text)
            if (app === text) assertNull(text, here) else {
                changed++
                assertEquals(text, app, here)
                // A second pass does what the app's does: nothing, except to a wrapper that was inside a wrapper.
                val again = LinkCleaner.clean(app)
                assertEquals(text, again.takeUnless { it === app }, TypingEngine.Links.clean(here!!))
            }
        }
        // Both kinds are in the list, so an empty comparison can't pass.
        assertTrue(changed >= 40 && texts.size - changed >= 40)
    }

    @Test
    fun `very long text is left alone`() {
        val long = "https://example.com/a?utm_source=x " + "a".repeat(LinkCleaner.MAX_CHARS)
        assertSame(long, LinkCleaner.clean(long))
        assertNull(TypingEngine.Links.clean(long))
        assertNotNull(TypingEngine.Links.clean(long.take(LinkCleaner.MAX_CHARS)))
    }

    @Test
    fun `the link at the caret is the app's, with the text it replaces`() {
        var offered = 0
        for ((before, after, whole) in carets) {
            val app = LinkCleaner.atCaret(before, after, whole)
            val here = TypingEngine.Links.atCaret(before, after, whole)
            if (app == null) {
                assertNull(before, here)
                continue
            }
            offered++
            assertNotNull(before, here)
            assertEquals(before, app.replace, here!!.replace)
            assertEquals(before, app.text, here.text)
            assertEquals(before, app.host, here.host)
            assertEquals(before, before.takeLast(app.replace), here.original)
        }
        assertTrue(offered >= 12 && carets.size - offered >= 8)
    }

    @Test
    fun `the end of a long text is read as the app reads it`() {
        // The app asks its field for the last CARET_READ_CHARS; the iPhone is handed the text and cuts it the same.
        val link = "https://example.com/a?utm_source=x"
        val long = "note ".repeat(1_000) + link
        assertNull(TypingEngine.Links.atCaret(long, "", false))
        val tail = long.takeLast(TypingEngine.Links.CARET_READ_CHARS)
        val app = LinkCleaner.atCaret(tail, "", false)!!
        val here = TypingEngine.Links.atCaret(tail, "", false)!!
        assertEquals(app.replace, here.replace)
        assertEquals("https://example.com/a", here.text)
        assertEquals(link, here.original)
        assertTrue(long.endsWith(here.original))
        // One unbroken run that fills what was read may be the end of a longer link: nothing.
        assertNull(TypingEngine.Links.atCaret(("x".repeat(5_000) + link).takeLast(TypingEngine.Links.CARET_READ_CHARS), "", false))
    }

    @Test
    fun `an offer names the cleaned link's host and keeps the punctuation after it`() {
        val found = TypingEngine.Links.atCaret("go https://www.amazon.com/dp/B01/ref=sr_1!", "", true)!!
        assertEquals("https://www.amazon.com/dp/B01/ref=sr_1!", found.original)
        assertEquals("https://www.amazon.com/dp/B01!", found.text)
        assertEquals("www.amazon.com", found.host)
        val unwrapped = TypingEngine.Links.atCaret("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa&sa=D", "", true)!!
        assertEquals("https://example.com/a", unwrapped.text)
        assertEquals("example.com", unwrapped.host)
    }
}
