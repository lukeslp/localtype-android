package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentCopiesTest {
    private val hour = RecentCopies.MAX_AGE_MS

    @Test
    fun `only plain copies from fields that allow learning are kept`() {
        assertTrue(RecentCopies.keeps("see you at 5", flaggedSensitive = false, privateField = false))
        // A password manager flags what it copies.
        assertFalse(RecentCopies.keeps("hunter2", flaggedSensitive = true, privateField = false))
        // Copied while a password field, or an incognito field, had the keyboard.
        assertFalse(RecentCopies.keeps("hunter2", flaggedSensitive = false, privateField = true))
        // Blank, an image or other non-text clip, or longer than the paste chip takes.
        assertFalse(RecentCopies.keeps(" \n", flaggedSensitive = false, privateField = false))
        assertFalse(RecentCopies.keeps(null, flaggedSensitive = false, privateField = false))
        assertFalse(RecentCopies.keeps("x".repeat(SavedTexts.MAX_CHARS + 1), flaggedSensitive = false, privateField = false))
        assertTrue(RecentCopies.keeps("x".repeat(SavedTexts.MAX_CHARS), flaggedSensitive = false, privateField = false))
    }

    @Test
    fun `copies are newest first, at most ten`() {
        val copies = RecentCopies()
        (1..12).forEach { copies.copied("copy $it", now = it * 1_000L) }
        val items = copies.items(now = 13_000L)
        assertEquals(RecentCopies.MAX, items.size)
        assertEquals((12 downTo 3).map { "copy $it" }, items)
    }

    @Test
    fun `copying the same text again keeps one entry, at the front, with a fresh hour`() {
        val copies = RecentCopies()
        copies.copied("a", now = 0L)
        copies.copied("a", now = 1_000L)
        assertEquals(listOf("a"), copies.items(now = 2_000L))
        copies.copied("b", now = 3_000L)
        copies.copied("a", now = hour - 1_000L)
        assertEquals(listOf("a", "b"), copies.items(now = hour))
        // b's hour is up; a's started again when it was copied again.
        assertEquals(listOf("a"), copies.items(now = hour + 3_000L))
    }

    @Test
    fun `copies expire after an hour`() {
        val copies = RecentCopies()
        assertNull(copies.nextExpiryIn(now = 0L))
        copies.copied("old", now = 0L)
        copies.copied("new", now = 10 * 60_000L)
        assertEquals(hour, copies.nextExpiryIn(now = 0L))
        assertEquals(listOf("new", "old"), copies.items(now = hour - 1))
        assertEquals(listOf("new"), copies.items(now = hour))
        assertEquals(10 * 60_000L, copies.nextExpiryIn(now = hour))
        // The keyboard prunes when the next hour is up, without anything asking for the list.
        copies.prune(now = hour + 10 * 60_000L)
        assertNull(copies.nextExpiryIn(now = hour + 10 * 60_000L))
        assertEquals(emptyList<String>(), copies.items(now = hour + 10 * 60_000L))
        // A late run (the handler's clock stops in deep sleep) is never told to wait a negative time.
        copies.copied("again", now = 0L)
        assertEquals(0L, copies.nextExpiryIn(now = 2 * hour))
    }

    @Test
    fun `an emptied clipboard takes its copy with it`() {
        val copies = RecentCopies()
        copies.copied("a", now = 0L)
        copies.copied("one-time code 482913", now = 1_000L)
        // A password manager that didn't flag the copy clears it a little later.
        copies.cleared()
        assertEquals(listOf("a"), copies.items(now = 2_000L))
        // Clearing again, or after something not kept replaced it, leaves older copies alone.
        copies.cleared()
        copies.skipped()
        copies.cleared()
        assertEquals(listOf("a"), copies.items(now = 2_000L))
    }

    @Test
    fun `a skipped clip keeps the older copies`() {
        val copies = RecentCopies()
        copies.copied("a", now = 0L)
        copies.skipped()
        assertEquals(listOf("a"), copies.items(now = 1_000L))
    }
}
