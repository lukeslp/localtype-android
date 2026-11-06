package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsTest {
    private val ana = Contact("Ana Lucía Pérez", listOf("+1 555 0100"), listOf("ana@example.org"))
    private val bo = Contact("Bo O'Neill", emptyList(), listOf("bo@example.net", "bo@example.net"), starred = true)
    private val kai = Contact("Kai", listOf("555 0199", "555 0199"), emptyList())
    private val people = listOf(kai, ana, bo)
    private val plain = GestureRules.State(enabled = false)

    @Test
    fun `a blank query lists starred people first, then by name`() {
        assertEquals(listOf(bo, ana, kai), ContactBook.matches(people, ""))
    }

    @Test
    fun `the word before the caret matches the start of any name word, ignoring case`() {
        assertEquals(listOf(ana), ContactBook.matches(people, "luc"))
        assertEquals(listOf(ana), ContactBook.matches(people, "PÉR"))
        assertEquals(listOf(bo), ContactBook.matches(people, "o'n"))
        assertEquals(emptyList<Contact>(), ContactBook.matches(people, "zed"))
    }

    @Test
    fun `at most a screenful of matches`() {
        val many = (1..200).map { Contact("Person $it", emptyList(), emptyList()) }
        assertEquals(ContactBook.MAX_SHOWN, ContactBook.matches(many, "per").size)
    }

    @Test
    fun `the field's own kind of detail comes first, without repeats`() {
        fun kinds(contact: Contact, field: ContactBook.Field) = ContactBook.choices(contact, field).map { it.kind }
        assertEquals(listOf(ContactBook.Kind.NAME, ContactBook.Kind.PHONE, ContactBook.Kind.EMAIL), kinds(ana, ContactBook.Field.TEXT))
        assertEquals(listOf(ContactBook.Kind.EMAIL, ContactBook.Kind.NAME, ContactBook.Kind.PHONE), kinds(ana, ContactBook.Field.EMAIL))
        assertEquals(listOf(ContactBook.Kind.PHONE, ContactBook.Kind.NAME, ContactBook.Kind.EMAIL), kinds(ana, ContactBook.Field.PHONE))
        assertEquals(1, ContactBook.choices(bo, ContactBook.Field.EMAIL).count { it.kind == ContactBook.Kind.EMAIL })
        assertEquals(1, ContactBook.choices(kai, ContactBook.Field.PHONE).count { it.kind == ContactBook.Kind.PHONE })
    }

    @Test
    fun `a name is followed by a space, a number or address is not`() {
        val name = ContactBook.Choice("Kai", ContactBook.Kind.NAME)
        assertEquals("Kai ", ContactBook.inserted(name, ""))
        assertEquals("Kai", ContactBook.inserted(name, " and"))
        assertEquals("ana@example.org", ContactBook.inserted(ContactBook.Choice("ana@example.org", ContactBook.Kind.EMAIL), ""))
    }

    @Test
    fun `name words are protected, lowercased, two letters or longer`() {
        val names = ContactBook.nameWords(people + Contact("J Smith-Jones", emptyList(), emptyList()))
        assertTrue("lucía" in names)
        assertTrue("o'neill" in names)
        assertTrue("smith" in names)
        assertTrue("jones" in names)
        assertFalse("j" in names)
    }

    @Test
    fun `the builder merges rows by person and names a nameless one by its detail`() {
        val builder = ContactBook.Builder()
        builder.add(1, "Kai", ContactBook.Kind.NAME, "Kai", starred = false)
        builder.add(1, "Kai", ContactBook.Kind.PHONE, " 555 0199 ", starred = true)
        builder.add(2, null, ContactBook.Kind.EMAIL, "x@example.com", starred = false)
        builder.add(3, null, ContactBook.Kind.NAME, " ", starred = false)
        assertEquals(listOf(
            Contact("Kai", listOf("555 0199"), emptyList(), starred = true),
            Contact("x@example.com", emptyList(), listOf("x@example.com")),
        ), builder.build())
    }

    @Test
    fun `contacts go into any field but a password`() {
        assertTrue(StripTools.allowed(StripTool.CONTACTS, plain))
        assertTrue(StripTools.allowed(StripTool.CONTACTS, plain.copy(learning = false)))
        assertTrue(StripTools.allowed(StripTool.CONTACTS, plain.copy(terminal = true)))
        assertFalse(StripTools.allowed(StripTool.CONTACTS, plain.copy(sensitive = true)))
    }

    @Test
    fun `contacts sit in the insert section`() {
        val sections = StripTools.panel(StripTools.Field(voice = true, proofread = true)).toMap()
        assertTrue(StripTool.CONTACTS in sections.getValue(StripTools.Group.INSERT))
    }
}
