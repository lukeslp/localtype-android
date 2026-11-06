package dev.localtype.keyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.provider.ContactsContract

/**
 * Reads the phone's contacts for the Contacts panel, once the person has granted it. Called off the main thread.
 * Only names, phone numbers and email addresses are read, and the result is kept in memory by the caller alone.
 */
object ContactsReader {
    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Everyone with a name, a number or an address, or an empty list without the permission, on any failure, or once
     * [cancel] is cancelled, which stops the provider's query and the reading of its rows.
     */
    fun read(context: Context, cancel: CancellationSignal? = null): List<Contact> {
        if (!granted(context)) return emptyList()
        val name = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        val email = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        val builder = ContactBook.Builder()
        return try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY, ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1, ContactsContract.Data.STARRED),
                "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)", arrayOf(name, phone, email), null, cancel,
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val display = cursor.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME_PRIMARY)
                val type = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                val data = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                val starred = cursor.getColumnIndexOrThrow(ContactsContract.Data.STARRED)
                while (cursor.moveToNext()) {
                    cancel?.throwIfCanceled()
                    val kind = when (cursor.getString(type)) {
                        phone -> ContactBook.Kind.PHONE
                        email -> ContactBook.Kind.EMAIL
                        else -> ContactBook.Kind.NAME
                    }
                    builder.add(cursor.getLong(id), cursor.getString(display), kind, cursor.getString(data), cursor.getInt(starred) != 0)
                }
            }
            builder.build()
        } catch (e: RuntimeException) {
            // A provider that refuses, a permission taken back mid-read or a cancelled read (OperationCanceledException):
            // no contacts, typing carries on.
            emptyList()
        }
    }
}
