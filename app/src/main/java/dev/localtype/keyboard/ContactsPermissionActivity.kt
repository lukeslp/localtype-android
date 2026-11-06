package dev.localtype.keyboard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Asks to read contacts when the Contacts tool is first tapped. A keyboard has no window Android will show a
 * permission prompt over, so this bare, see-through activity carries the prompt and closes as soon as it is answered.
 * Taking the permission back in Android's settings is how the keyboard forgets every contact: it never stores one.
 */
class ContactsPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) return finish()
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (granted) R.string.contacts_permission_granted else R.string.contacts_permission_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val REQUEST_CONTACTS = 421
    }
}
