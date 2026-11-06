package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class SwipeGestureTest {
    private fun snapshot(before: String, after: String = "") = VerifiedWordTransaction.Snapshot(
        before, after, EditorEditState.Selection(before.length, before.length))

    @Test fun tapAndSmallDriftNeverBecomeSwipe() {
        val gesture = SwipeGesture(1.35f)
        gesture.add(.5f, .5f, 0f); gesture.add(.7f, .6f, 150f)
        assertFalse(gesture.active); assertNull(gesture.finish())
    }
    @Test fun completedPathIsDetachedAndBounded() {
        val gesture = SwipeGesture(1.35f)
        for (i in 0..7000) gesture.add(.5f + i % 8, .5f, i.toFloat())
        val result = gesture.finish()!!
        assertTrue(result.x.size <= SwipeGesture.MAX_POINTS); assertEquals(.5f, result.x.first(), 0f)
        assertEquals(7000f, result.time.last(), 0f)
        result.x[0] = 99f; assertEquals(.5f, gesture.points.first().x, 0f)
    }
    @Test fun aLongSwipeKeepsEveryPoint() {
        // "anticapitalism" swiped slowly on a 240 Hz screen: about three seconds, 720 touch reports. Until 2026-11
        // the path was halved at 256 points, so the start of a long word lost its shape while the finger moved on.
        val gesture = SwipeGesture(1.35f)
        for (i in 0 until 720) gesture.add(.5f + 9f * (i % 120) / 120f, .5f + (i / 120 % 3), i * 4.17f)
        assertEquals(720, gesture.points.size)
        assertEquals(720, gesture.finish()!!.x.size)
    }
    @Test fun overshootingTheLettersKeepsTheSwipe() {
        // From "q" up into the strip and back down, then past "p" and onto the space bar: still one swipe.
        val gesture = SwipeGesture(1.35f)
        for ((x, y, t) in listOf(Triple(.5f, .5f, 0f), Triple(.6f, -.8f, 40f), Triple(3f, .5f, 120f), Triple(9.5f, .5f, 300f),
            Triple(10.8f, .6f, 340f), Triple(6.5f, 2.5f, 500f), Triple(6.6f, 3.7f, 560f))) gesture.add(x, y, t)
        assertFalse(gesture.cancelled)
        val path = gesture.finish()!!
        // Points beyond the letters sit on their nearest edge.
        assertTrue(path.x.all { it in 0f..10f } && path.y.all { it in 0f..3f })
        assertEquals(0f, path.y[1], 0f); assertEquals(10f, path.x[4], 0f); assertEquals(3f, path.y.last(), 0f)
    }
    @Test fun aTapThatDriftsOffTheBottomRowStillTypesItsKey() {
        // Lifting from "m" a little low used to cancel the press, which then typed nothing.
        val gesture = SwipeGesture(1.35f)
        gesture.add(6.5f, 2.9f, 0f); gesture.add(6.55f, 3.15f, 90f)
        assertFalse(gesture.cancelled); assertFalse(gesture.active); assertNull(gesture.finish())
    }
    @Test fun theTrailShowsOnlyTheNewestStretch() {
        val points = (0..40).map { SwipeGesture.Point(it * .25f, .5f, it.toFloat()) }
        // Ten key widths of path, of which the trail draws the last five.
        assertEquals(20, SwipeTrail.start(points, 1.35f))
        assertEquals(0, SwipeTrail.start(points.take(10), 1.35f))
        assertEquals(0, SwipeTrail.start(points.take(1), 1.35f))
    }
    @Test fun adjacentLetterWordsArePossibleButStationaryHoldsStayTaps() {
        val adjacent = SwipeGesture(1.35f)
        adjacent.add(.5f, 1.5f, 0f); adjacent.add(1.5f, 1.5f, 100f)
        assertNotNull(adjacent.finish()); assertTrue(adjacent.moved)
        val hold = SwipeGesture(1.35f)
        hold.add(.5f, 1.5f, 0f); hold.add(.5f, 1.5f, 700f)
        assertNull(hold.finish()); assertFalse(hold.moved)
    }
    @Test fun badCoordinatesBackwardsTimeAndDeadlineCancel() {
        for (point in listOf(Triple(Float.NaN, .5f, 100f), Triple(4f, Float.POSITIVE_INFINITY, 100f),
            Triple(4f, .5f, -1f), Triple(4f, .5f, 8001f))) {
            val gesture = SwipeGesture(1.35f); gesture.add(.5f, .5f, 0f)
            gesture.add(point.first, point.second, point.third)
            assertTrue(gesture.cancelled); assertNull(gesture.finish())
        }
    }
    @Test fun swipeStartsOnlyAtReadableWordBoundaries() {
        assertTrue(SwipeInsertion.mayStart(VerifiedWordTransaction.window("say ", 0, 4, 4, 1024)!!))
        for (before in listOf("", "say ", "(", "“")) assertTrue(before, SwipeInsertion.mayStart(snapshot(before)))
        for (before in listOf("hel", "#", "@", "https://", "path/", "42", "name_"))
            assertFalse(before, SwipeInsertion.mayStart(snapshot(before)))
        assertFalse(SwipeInsertion.mayStart(snapshot("say ", "word")))
        assertFalse(SwipeInsertion.mayStart(snapshot("").copy(selection = EditorEditState.Selection(0, 3))))
    }
    @Test fun swipeMayFollowALoneAOrI() {
        for (before in listOf("a", "say a", "I", "and i", "(a", "“I", "so A"))
            assertTrue(before, SwipeInsertion.mayStart(snapshot(before)))
        for (before in listOf("ba", "hi", "say x", "say ai", "#a", "@i", "path/a", "a_", "2a"))
            assertFalse(before, SwipeInsertion.mayStart(snapshot(before)))
        assertFalse(SwipeInsertion.mayStart(snapshot("say a", "word")))
        assertTrue(SwipeInsertion.mayStart(snapshot("say a", ".")))
    }
    @Test fun aLoneLetterGetsTheSpaceATapWouldHaveNeeded() {
        assertEquals(SwipeInsertion.Lead(0, ""), SwipeInsertion.lead("say ", capitalizeI = true))
        assertEquals(SwipeInsertion.Lead(0, ""), SwipeInsertion.lead("", capitalizeI = true))
        assertEquals(SwipeInsertion.Lead(0, " "), SwipeInsertion.lead("say a", capitalizeI = true))
        assertEquals(SwipeInsertion.Lead(0, " "), SwipeInsertion.lead("I", capitalizeI = true))
        // A lone "i" becomes "I" as a typed space would make it, unless capitals are off for this field.
        assertEquals(SwipeInsertion.Lead(1, "I "), SwipeInsertion.lead("and i", capitalizeI = true))
        assertEquals(SwipeInsertion.Lead(0, " "), SwipeInsertion.lead("and i", capitalizeI = false))
        val lead = SwipeInsertion.lead("and i", capitalizeI = true)
        val text = lead.text + SwipeInsertion.text("think", KeyboardView.ShiftState.OFF, "")
        assertEquals("and I think ", "and i".dropLast(lead.replace) + text)
        val a = SwipeInsertion.lead("say a", capitalizeI = true)
        assertEquals("say a word ", "say a".dropLast(a.replace) + a.text + SwipeInsertion.text("word", KeyboardView.ShiftState.OFF, ""))
    }

    /** What commitSwipe makes of [s] with [word]: the text before the caret, then what follows it. */
    private fun swiped(s: VerifiedWordTransaction.Snapshot, word: String, afterSwipe: Boolean = false, capitalizeI: Boolean = true): String {
        val lead = SwipeInsertion.lead(s.before, capitalizeI, afterSwipe)
        return s.before.dropLast(lead.replace) + lead.text +
            SwipeInsertion.text(SwipeInsertion.word(word, capitalizeI), KeyboardView.ShiftState.OFF, s.after)
    }

    @Test fun aSwipeStraightAfterASwipedWordThatGotNoSpacePutsOneIn() {
        // Inside "()" the first swipe after a lone "i" gets no space after it, since text follows it.
        val first = swiped(snapshot("(i", ")"), "think")
        assertEquals("(I think", first)
        // A typed word there still refuses a swipe; the word the last swipe left there takes one, with the space first.
        assertFalse(SwipeInsertion.mayStart(snapshot(first, ")")))
        assertTrue(SwipeInsertion.mayStart(snapshot(first, ")"), afterSwipe = true))
        assertEquals(SwipeInsertion.Lead(0, " "), SwipeInsertion.lead(first, capitalizeI = true, afterSwipe = true))
        assertEquals("(I think so)", swiped(snapshot(first, ")"), "so", afterSwipe = true) + ")")
        // The same before a line break or the rest of a sentence, where no space goes after the word either.
        assertEquals("I think so\nmore", swiped(snapshot("I think", "\nmore"), "so", afterSwipe = true) + "\nmore")
        assertEquals("say hello world there", swiped(snapshot("say hello", " there"), "world", afterSwipe = true) + " there")
        // Never a second space, never before a word, never over a selection.
        assertEquals(SwipeInsertion.Lead(0, ""), SwipeInsertion.lead("say ", capitalizeI = true, afterSwipe = true))
        assertFalse(SwipeInsertion.mayStart(snapshot("say hello", "world"), afterSwipe = true))
        assertFalse(SwipeInsertion.mayStart(snapshot("say hello").copy(selection = EditorEditState.Selection(4, 9)), afterSwipe = true))
    }

    @Test fun inAPasswordOrAddressASwipeAfterASwipedWordRunsOn() {
        // Words run together there, so the first swipe gets no space after it, even at the end of the text...
        val first = "say " + SwipeInsertion.lead("say ", capitalizeI = true, joined = true).text +
            SwipeInsertion.text("hello", KeyboardView.ShiftState.OFF, "", space = false)
        assertEquals("say hello", first)
        // ...and the next one, straight after it, starts there and runs on: a passphrase can be swiped word by word.
        assertFalse(SwipeInsertion.mayStart(snapshot(first), afterJoiners = true))
        assertTrue(SwipeInsertion.mayStart(snapshot(first), afterJoiners = true, afterSwipe = true))
        val lead = SwipeInsertion.lead(first, capitalizeI = true, afterSwipe = true, joined = true)
        assertEquals(SwipeInsertion.Lead(0, ""), lead)
        assertEquals("say helloworld", first + lead.text + SwipeInsertion.text("world", KeyboardView.ShiftState.OFF, "", space = false))
        // A lone "a" or "i" gets no space there either, and the "i" stays as typed.
        assertEquals(SwipeInsertion.Lead(0, ""), SwipeInsertion.lead("say a", capitalizeI = true, joined = true))
        assertEquals(SwipeInsertion.Lead(0, ""), SwipeInsertion.lead("and i", capitalizeI = true, afterSwipe = true, joined = true))
    }

    @Test fun aSwipedIOrAGetsItsSpaceAndICapital() {
        // A swipe read as a one-letter word gets the space any swiped word gets at the end of the text.
        assertEquals("say a ", swiped(snapshot("say "), "a"))
        assertEquals("so I ", swiped(snapshot("so "), "i"))
        assertEquals("I'm ", swiped(snapshot(""), "i'm"))
        assertEquals("so I'll ", swiped(snapshot("so "), "i'll"))
        assertEquals("I'M ", SwipeInsertion.text(SwipeInsertion.word("i'm", true), KeyboardView.ShiftState.LOCKED, ""))
        // With Capitalize automatically off, or in a name or address field, it stays as typed.
        assertEquals("so i ", swiped(snapshot("so "), "i", capitalizeI = false))
        for (word in listOf("in", "it's", "ill", "a")) assertEquals(word, SwipeInsertion.word(word, capitalizeI = true))
        // Mid-text, a swiped "I" gets no space, so the next swipe puts it in.
        val first = swiped(snapshot("(", ")"), "i")
        assertEquals("(I", first)
        assertEquals("(I think)", swiped(snapshot(first, ")"), "think", afterSwipe = true) + ")")
    }
    @Test fun caseAndExistingFollowingPunctuationArePreserved() {
        assertEquals("Hello ", SwipeInsertion.text("hello", KeyboardView.ShiftState.ON, ""))
        assertEquals("HELLO ", SwipeInsertion.text("hello", KeyboardView.ShiftState.LOCKED, ""))
        assertEquals("hello)", SwipeInsertion.text("hello", KeyboardView.ShiftState.OFF, ")") + ")")
        assertEquals("hello", SwipeInsertion.text("hello", KeyboardView.ShiftState.OFF, " next"))
    }
    @Test fun swipeTypesIntoEveryTextFieldButNotNumberPads() {
        val text = android.text.InputType.TYPE_CLASS_TEXT
        // Every text field: ordinary text, boxes without suggestions, web and email addresses, passwords (hidden,
        // visible and web), list filters, address bars, and raw-key fields such as terminals. A swiped word is an
        // insertion and teaches nothing in any of them.
        for (type in listOf(text, text or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0xa1 or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            17, 33, 209, 129, 225, 145, 0xb1, 0, 17 or 0x80000)) assertTrue("type=$type", SwipeRules.field(type))
        // Number, phone and date fields have no letter keys to swipe on.
        for (type in listOf(2, 3, 4, 18, 0x12)) assertFalse("type=$type", SwipeRules.field(type))
    }

    @Test fun wordsRunTogetherInAddressesAndPasswords() {
        for (type in listOf(17, 33, 209, 129, 225, 145)) assertTrue("type=$type", SwipeRules.joined(type))
        for (type in listOf(1, 0xb1, 0, 0x4001)) assertFalse("type=$type", SwipeRules.joined(type))
        assertEquals("hello", SwipeInsertion.text("hello", KeyboardView.ShiftState.OFF, "", space = false))
        assertEquals("hello ", SwipeInsertion.text("hello", KeyboardView.ShiftState.OFF, ""))
        // In an address a word may start right after a joiner, and stop right before one.
        assertTrue(SwipeInsertion.mayStart(snapshot("john@"), afterJoiners = true))
        assertTrue(SwipeInsertion.mayStart(snapshot("www."), afterJoiners = true))
        assertFalse(SwipeInsertion.mayStart(snapshot("john@"), afterJoiners = false))
        assertFalse(SwipeInsertion.mayStart(snapshot("jo"), afterJoiners = true))
        assertTrue(SwipeInsertion.mayStart(snapshot("say "), afterJoiners = true))
    }
    @Test fun lateAnswerCannotSurviveAnEditResetOrDisabledSetting() {
        val s = snapshot("say ")
        val request = SwipeRequest(1, s)
        assertTrue(request.matches(1, true, s))
        assertFalse(request.matches(2, true, s))
        assertFalse(request.matches(1, false, s))
        assertFalse(request.matches(1, true, snapshot("say x")))
        assertFalse(request.matches(1, true, s.copy(after = "elsewhere")))
        assertFalse(request.matches(1, true, null))
    }
    @Test fun removingSwipeKeepsLaterTypingAndRejectsMovedCaret() {
        val id = RecentWordCorrection.Identity(1, 2, "swipe")
        val record = RecentWordCorrection.Word(id, "", "", 4, "say ", "", 100, grammar = true)
        val undo = RecentWordCorrection(); undo.applied(record, "hello ", 100, "")
        undo.append("next", 120)
        assertEquals(RecentWordCorrection.RangeEdit(4, 10, "", 8), undo.undoEdit(id, snapshot("say hello next"), 140))
        assertNull(undo.undoEdit(id, snapshot("say hello next").copy(selection = EditorEditState.Selection(3, 3)), 150))
        assertFalse(SmallAccommodations.loggable("", "hello "))
    }
}
