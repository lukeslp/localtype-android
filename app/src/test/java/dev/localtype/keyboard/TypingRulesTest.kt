package dev.localtype.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingRulesTest {
    @Test fun `the keyboard covers the app only in a short landscape window`() {
        // A phone on its side keeps Android's default.
        assertTrue(TypingRules.fullscreen(landscapeDefault = true, screenHeightDp = 411))
        // An unfolded Fold, a tablet or a desktop window in landscape leaves the app in view.
        assertFalse(TypingRules.fullscreen(landscapeDefault = true, screenHeightDp = 704))
        assertFalse(TypingRules.fullscreen(landscapeDefault = true, screenHeightDp = 800))
        assertFalse(TypingRules.fullscreen(landscapeDefault = true, screenHeightDp = TypingRules.COMPACT_HEIGHT_DP))
        // Portrait, or an editor that asks for no fullscreen, never is; an unknown height keeps the default.
        assertFalse(TypingRules.fullscreen(landscapeDefault = false, screenHeightDp = 411))
        assertTrue(TypingRules.fullscreen(landscapeDefault = true, screenHeightDp = 0))
    }

    @Test
    fun `a longer Touch and hold delay in Android's settings lengthens every hold`() {
        assertEquals(380L, TypingRules.holdDelayMs(300))
        assertEquals(380L, TypingRules.holdDelayMs(380))
        // Android's Short, Medium and Long choices.
        assertEquals(400L, TypingRules.holdDelayMs(400))
        assertEquals(1_000L, TypingRules.holdDelayMs(1_000))
        assertEquals(1_500L, TypingRules.holdDelayMs(1_500))
    }

    @Test fun `with key shapes off only an unpressed letter key loses its shape`() {
        for (style in KeyStyle.entries) assertTrue(TypingRules.drawsKeyShape(keyShapes = true, pressed = false, style = style))
        assertFalse(TypingRules.drawsKeyShape(keyShapes = false, pressed = false, style = KeyStyle.CHARACTER))
        // A pressed key still shows, and space, Shift, delete, Return and the accent keys stay as landmarks.
        assertTrue(TypingRules.drawsKeyShape(keyShapes = false, pressed = true, style = KeyStyle.CHARACTER))
        for (style in listOf(KeyStyle.MODIFIER, KeyStyle.ACCENT, KeyStyle.SPACE))
            assertTrue(TypingRules.drawsKeyShape(keyShapes = false, pressed = false, style = style))
    }

    @Test fun webConnectionsUseVerifiedSelectionEvenWhenRangeApiExists() {
        val ordinary = InputType.TYPE_CLASS_TEXT
        val web = ordinary or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        assertFalse(TypingRules.prefersRangeReplacement(ordinary, 28))
        assertFalse(TypingRules.prefersRangeReplacement(ordinary, 33))
        assertTrue(TypingRules.prefersRangeReplacement(ordinary, 34))
        for (api in listOf(28, 35, 37)) assertFalse(TypingRules.prefersRangeReplacement(web, api))
    }
    @Test
    fun `double space after a word becomes a period`() {
        assertTrue(TypingRules.shouldInsertPeriodOnSpace("hello "))
        assertTrue(TypingRules.shouldInsertPeriodOnSpace("at 5 "))
        assertFalse(TypingRules.shouldInsertPeriodOnSpace("done. "))
        assertFalse(TypingRules.shouldInsertPeriodOnSpace("  "))
        assertFalse(TypingRules.shouldInsertPeriodOnSpace(" "))
        assertFalse(TypingRules.shouldInsertPeriodOnSpace("hello"))
    }

    @Test
    fun `candidates follow the typed case`() {
        assertEquals("World", TypingRules.applyCase("world", "Wor", sentenceStart = false))
        assertEquals("WORLD", TypingRules.applyCase("world", "WOR", sentenceStart = false))
        assertEquals("world", TypingRules.applyCase("world", "wor", sentenceStart = true))
        assertEquals("The", TypingRules.applyCase("the", "", sentenceStart = true))
        assertEquals("the", TypingRules.applyCase("the", "", sentenceStart = false))
        // One capital before an apostrophe or hyphen is a capitalized word, not caps lock.
        assertEquals("X-ray", TypingRules.applyCase("x-ray", "X-", sentenceStart = false))
        assertEquals("DON'T", TypingRules.applyCase("don't", "DON'", sentenceStart = false))
        // Caps lock makes every candidate all caps, even after a single capital or at an empty prefix.
        assertEquals("I'LL", TypingRules.applyCase("i'll", "I'", sentenceStart = false, capsLock = true))
        assertEquals("X-RAY", TypingRules.applyCase("x-ray", "X-", sentenceStart = false, capsLock = true))
        assertEquals("THE", TypingRules.applyCase("the", "", sentenceStart = true, capsLock = true))
    }

    @Test
    fun `pronoun i is always capitalized`() {
        assertEquals("I", TypingRules.applyCase("i", "", sentenceStart = false))
        assertEquals("I'm", TypingRules.applyCase("i'm", "i", sentenceStart = false))
        assertEquals("if", TypingRules.applyCase("if", "i", sentenceStart = false))
        assertEquals("I'm", TypingRules.applyCase("i'm", "I'", sentenceStart = false))
        assertEquals("I'll", TypingRules.applyCase("i'll", "I'", sentenceStart = true))
    }

    @Test
    fun `double tap on shift locks caps even when the field turned shift on`() {
        val off = KeyboardView.ShiftState.OFF
        val on = KeyboardView.ShiftState.ON
        val locked = KeyboardView.ShiftState.LOCKED
        // Sentence start: shift is already on, the first tap turns it off and the quick second tap locks.
        assertEquals(off, TypingRules.shiftAfterTap(on, quick = false))
        assertEquals(locked, TypingRules.shiftAfterTap(off, quick = true))
        // From off: on, then locked.
        assertEquals(on, TypingRules.shiftAfterTap(off, quick = false))
        assertEquals(locked, TypingRules.shiftAfterTap(on, quick = true))
        // Any tap leaves caps lock.
        assertEquals(off, TypingRules.shiftAfterTap(locked, quick = true))
        assertEquals(off, TypingRules.shiftAfterTap(locked, quick = false))
    }

    @Test
    fun `shift capitalizes latin letters and leaves ipa and greek as shown`() {
        assertEquals("A", TypingRules.shifted("a"))
        assertEquals("É", TypingRules.shifted("é"))
        assertEquals("SS", TypingRules.shifted("ß"))
        assertEquals("ə", TypingRules.shifted("ə"))
        assertEquals("ʃ", TypingRules.shifted("ʃ"))
        assertEquals("θ", TypingRules.shifted("θ"))
        assertEquals("π", TypingRules.shifted("π"))
        assertEquals("7", TypingRules.shifted("7"))
        // The email and URL domain keys keep their lowercase faces under Shift and caps lock.
        for (domain in listOf(".com", ".org", ".net")) assertEquals(domain, TypingRules.shifted(domain))
    }

    @Test
    fun `delete takes the whole visible character`() {
        assertEquals(0, TypingRules.lastGraphemeLength(""))
        // Plain letters, and a letter with a combining accent.
        assertEquals(1, TypingRules.lastGraphemeLength("ab"))
        assertEquals(1, TypingRules.lastGraphemeLength("a"))
        assertEquals(2, TypingRules.lastGraphemeLength("café"))
        // A surrogate pair, and the text-style heart with VS16 that "love" and "heart" offer first.
        assertEquals(2, TypingRules.lastGraphemeLength("😀"))
        assertEquals(2, TypingRules.lastGraphemeLength("love ❤️"))
        assertEquals(2, TypingRules.lastGraphemeLength("☀️"))
        // Skin tone.
        assertEquals(4, TypingRules.lastGraphemeLength("👍🏽"))
        // ZWJ sequences, including one with a VS16 and one with a skin tone inside.
        assertEquals(5, TypingRules.lastGraphemeLength("🧑‍💻"))
        assertEquals(5, TypingRules.lastGraphemeLength("🏃‍♀️"))
        assertEquals(8, TypingRules.lastGraphemeLength("x👨‍👩‍👧"))
        assertEquals(7, TypingRules.lastGraphemeLength("🧑🏽‍💻"))
        // Flags are pairs of regional indicators; an odd one out goes alone.
        assertEquals(4, TypingRules.lastGraphemeLength("🇺🇸"))
        assertEquals(4, TypingRules.lastGraphemeLength("🇺🇸🇬🇧"))
        assertEquals(2, TypingRules.lastGraphemeLength("🇺🇸🇬"))
        // Keycap: digit, VS16, enclosing keycap.
        assertEquals(3, TypingRules.lastGraphemeLength("call 1️⃣"))
        // A ZWJ after a letter joins nothing.
        assertEquals(2, TypingRules.lastGraphemeLength("a‍😀"))
        assertEquals("hi ", TypingRules.dropLastGrapheme("hi 👨‍👩‍👧"))
        assertEquals("hel", TypingRules.dropLastGrapheme("help"))
    }

    @Test
    fun `automatic capitalization off leaves shift, sentence starts and a lone i alone`() {
        val message = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val name = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        // getCursorCapsMode reports the field's request with the TYPE_TEXT_FLAG_CAP_* bits.
        assertTrue(TypingRules.autoShift(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, autoCapitalize = true))
        assertFalse(TypingRules.autoShift(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, autoCapitalize = false))
        assertFalse(TypingRules.autoShift(0, autoCapitalize = true))
        assertTrue(TypingRules.capitalizesSentences(message, autoCapitalize = true))
        assertFalse(TypingRules.capitalizesSentences(message, autoCapitalize = false))
        assertFalse(TypingRules.capitalizesSentences(InputType.TYPE_CLASS_TEXT, autoCapitalize = true))
        assertTrue(TypingRules.capitalizesLoneI(message, autoCapitalize = true))
        assertFalse(TypingRules.capitalizesLoneI(message, autoCapitalize = false))
        assertFalse(TypingRules.capitalizesLoneI(name, autoCapitalize = true))
    }

    @Test
    fun `the sparkle asks for words wherever the strip offers them and proofreads only where suggestions are wanted`() {
        val message = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val addressBar = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val chromeAddressBar = 0x80011
        val webBoxWithoutSuggestions = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        for (type in listOf(message, addressBar, chromeAddressBar, webBoxWithoutSuggestions))
            assertTrue("type=$type", TypingRules.offersModelWordsOnTap(type))
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val noSuggestions = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        for (type in listOf(password, email, noSuggestions, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, 0))
            assertFalse("type=$type", TypingRules.offersModelWordsOnTap(type))
        assertTrue(TypingRules.proofreadsOnHold(message))
        for (type in listOf(addressBar, chromeAddressBar, webBoxWithoutSuggestions, password))
            assertFalse("type=$type", TypingRules.proofreadsOnHold(type))
    }

    @Test
    fun `lone i is detected for capitalization`() {
        assertTrue(TypingRules.isLoneLowercaseI("i"))
        assertTrue(TypingRules.isLoneLowercaseI("so i"))
        assertFalse(TypingRules.isLoneLowercaseI("hi"))
        assertFalse(TypingRules.isLoneLowercaseI("wi-fi"))
        assertFalse(TypingRules.isLoneLowercaseI("I"))
    }

    @Test
    fun `best candidate sits in the middle slot`() {
        assertEquals(listOf("b", "a", "c"), TypingRules.arrangeForStrip(listOf("a", "b", "c")))
        assertEquals(listOf("b", "a", ""), TypingRules.arrangeForStrip(listOf("a", "b")))
        assertEquals(listOf("", "a", ""), TypingRules.arrangeForStrip(listOf("a")))
        assertEquals(listOf("", "", ""), TypingRules.arrangeForStrip(emptyList()))
    }

    @Test
    fun `password fields are sensitive and get no suggestions`() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val pin = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(TypingRules.isSensitive(password))
        assertTrue(TypingRules.isSensitive(pin))
        assertFalse(TypingRules.wantsSuggestions(password))
        assertFalse(TypingRules.allowsLearning(0, password))
    }

    @Test
    fun `number class with a text variation value is not mistaken for a password`() {
        // TYPE_TEXT_VARIATION_PASSWORD shares its bit pattern with a number variation.
        val number = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        assertFalse(TypingRules.isSensitive(number))
        assertEquals(KeyboardLayout.Mode.NUMBER, TypingRules.layoutFor(number))
    }

    @Test
    fun `email url and no-suggestion fields skip suggestions`() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(TypingRules.wantsSuggestions(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertFalse(TypingRules.wantsSuggestions(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(TypingRules.wantsSuggestions(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(TypingRules.wantsSuggestions(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun `a field without suggestions says why instead of going blank`() {
        val text = InputType.TYPE_CLASS_TEXT
        assertNull(TypingRules.stripOff(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertEquals(TypingRules.StripOff.PRIVATE, TypingRules.stripOff(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        // TYPE_NULL, and Termux's class-0 visible-password flags.
        assertEquals(TypingRules.StripOff.TERMINAL, TypingRules.stripOff(InputType.TYPE_NULL))
        assertEquals(TypingRules.StripOff.TERMINAL, TypingRules.stripOff(0x80090))
        assertEquals(TypingRules.StripOff.FIELD, TypingRules.stripOff(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        // An address bar gets predictions from 0.5.18; a mail field still says it has none.
        assertNull(TypingRules.stripOff(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertEquals(TypingRules.StripOff.FIELD, TypingRules.stripOff(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertEquals(TypingRules.StripOff.FIELD, TypingRules.stripOff(InputType.TYPE_CLASS_NUMBER))
    }

    @Test
    fun `a web box that turned off autofill still gets suggestions but no autocorrect`() {
        // What Chrome sends for <textarea autocomplete="off">, like the google.com search box.
        val webOff = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertNull(TypingRules.stripOff(webOff))
        assertTrue(TypingRules.isWebTextWithoutSuggestions(webOff))
        assertFalse(TypingRules.wantsSuggestions(webOff))
        // An app's own text field that turned suggestions off keeps them off.
        assertFalse(TypingRules.isWebTextWithoutSuggestions(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        // Chrome's address bar is a URL field.
        val urlBar = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        // Chrome's address bar: predictions, but none of what a field that asked for suggestions gets.
        assertNull(TypingRules.stripOff(urlBar))
        assertTrue(TypingRules.predictsInAddressBar(urlBar))
        assertFalse(TypingRules.wantsSuggestions(urlBar))
        assertFalse(TypingRules.allowsAutocorrect(urlBar))
        assertFalse(TypingRules.allowsAutocorrect(urlBar, webText = false))
        assertFalse(TypingRules.predictsInAddressBar(InputType.TYPE_CLASS_TEXT))
        assertFalse(TypingRules.predictsInAddressBar(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(TypingRules.predictsInAddressBar(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_URI))
    }

    @Test
    fun `incognito fields never learn`() {
        assertFalse(TypingRules.allowsLearning(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, InputType.TYPE_CLASS_TEXT))
        assertTrue(TypingRules.allowsLearning(0, InputType.TYPE_CLASS_TEXT))
    }

    @Test
    fun `enter follows the editor action`() {
        assertEquals(EditorInfo.IME_ACTION_SEND, TypingRules.enterAction(EditorInfo.IME_ACTION_SEND))
        assertNull(TypingRules.enterAction(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertNull(TypingRules.enterAction(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals("Search", TypingRules.enterLabel(EditorInfo.IME_ACTION_SEARCH))
    }

    @Test
    fun `field class chooses the layout before variation and flags`() {
        for (signed in listOf(false, true)) for (decimal in listOf(false, true)) {
            val flags = (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0) or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            for (variation in listOf(InputType.TYPE_NUMBER_VARIATION_NORMAL, InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
                assertEquals(KeyboardLayout.Mode.NUMBER,
                    TypingRules.layoutFor(InputType.TYPE_CLASS_NUMBER or variation or flags))
            }
            assertEquals(KeyboardLayout.Mode.PHONE, TypingRules.layoutFor(InputType.TYPE_CLASS_PHONE or flags))
        }
        for (variation in listOf(InputType.TYPE_DATETIME_VARIATION_NORMAL, InputType.TYPE_DATETIME_VARIATION_DATE,
            InputType.TYPE_DATETIME_VARIATION_TIME)) {
            assertEquals(KeyboardLayout.Mode.SYMBOLS, TypingRules.layoutFor(InputType.TYPE_CLASS_DATETIME or variation))
        }
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
            assertEquals(KeyboardLayout.Mode.LETTERS, TypingRules.layoutFor(InputType.TYPE_CLASS_TEXT or variation or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        }
        assertEquals(KeyboardLayout.Mode.LETTERS, TypingRules.layoutFor(InputType.TYPE_NULL))
        assertEquals(KeyboardLayout.Mode.LETTERS, TypingRules.layoutFor(0x80090))
    }

    @Test
    fun `a restart keeps the layer unless the field wants different keys`() {
        val text = InputType.TYPE_CLASS_TEXT
        val email = text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val number = InputType.TYPE_CLASS_NUMBER
        // A new field always starts over; a restart of the same kind of field keeps symbols or shift.
        assertTrue(TypingRules.startsNewLayout(false, KeyboardLayout.Mode.LETTERS, text))
        assertFalse(TypingRules.startsNewLayout(true, KeyboardLayout.Mode.LETTERS, text))
        assertFalse(TypingRules.startsNewLayout(true, KeyboardLayout.Mode.LETTERS, email))
        // Chrome moving from a number box to an email box, and back, restarts input both times.
        assertTrue(TypingRules.startsNewLayout(true, KeyboardLayout.Mode.NUMBER, email))
        assertTrue(TypingRules.startsNewLayout(true, KeyboardLayout.Mode.LETTERS, number))
        assertTrue(TypingRules.startsNewLayout(true, KeyboardLayout.Mode.NUMBER, InputType.TYPE_CLASS_PHONE))
        assertFalse(TypingRules.startsNewLayout(true, KeyboardLayout.Mode.NUMBER, number or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        // Nothing started yet (a restart before any field) picks keys too.
        assertTrue(TypingRules.startsNewLayout(true, null, text))
    }

    @Test
    fun `email and url traits require a text class and preserve web email`() {
        val text = InputType.TYPE_CLASS_TEXT
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            val inputType = text or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            assertTrue(TypingRules.isEmail(inputType))
            assertFalse(TypingRules.isUrl(inputType))
            assertFalse(TypingRules.wantsSuggestions(inputType))
            assertFalse(TypingRules.allowsAutocorrect(inputType))
        }
        val url = text or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue(TypingRules.isUrl(url))
        assertFalse(TypingRules.isEmail(url))
        assertFalse(TypingRules.allowsAutocorrect(url))
        assertFalse(TypingRules.isEmail(text))
        assertFalse(TypingRules.isUrl(text))
        for (inputClass in listOf(0, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
            for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI)) {
                assertFalse(TypingRules.isEmail(inputClass or variation))
                assertFalse(TypingRules.isUrl(inputClass or variation))
            }
        }
    }

    @Test
    fun `names and postal addresses keep suggestions but never automatically correct`() {
        val text = InputType.TYPE_CLASS_TEXT
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PERSON_NAME, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)) {
            for (flags in listOf(0, InputType.TYPE_TEXT_FLAG_CAP_WORDS, InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)) {
                val inputType = text or variation or flags
                assertTrue(TypingRules.isNameOrAddress(inputType))
                assertTrue(TypingRules.wantsSuggestions(inputType))
                assertNull(TypingRules.stripOff(inputType))
                assertTrue(TypingRules.allowsLearning(0, inputType))
                assertFalse(TypingRules.allowsAutocorrect(inputType))
                assertEquals(KeyboardLayout.Mode.LETTERS, TypingRules.layoutFor(inputType))
            }
        }
        assertTrue(TypingRules.allowsAutocorrect(text))
        assertFalse(TypingRules.isNameOrAddress(text))
        for (inputClass in listOf(0, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
            for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PERSON_NAME, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)) {
                assertFalse(TypingRules.isNameOrAddress(inputClass or variation))
            }
        }
    }

    @Test
    fun `autocorrect stays off wherever field suggestions are forbidden`() {
        val text = InputType.TYPE_CLASS_TEXT
        for (inputType in listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME, text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, text or InputType.TYPE_TEXT_VARIATION_FILTER)) {
            assertFalse(TypingRules.allowsAutocorrect(inputType))
        }
        val web = text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue(TypingRules.allowsAutocorrect(web))
        assertFalse(TypingRules.allowsAutocorrect(web, webText = false))
        assertFalse(TypingRules.wantsSuggestions(web))
        assertTrue(TypingRules.allowsAutocorrect(text or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
        assertTrue(TypingRules.allowsAutocorrect(text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT))
    }
}
