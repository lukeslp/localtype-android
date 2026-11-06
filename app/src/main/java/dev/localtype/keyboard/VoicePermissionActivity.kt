package dev.localtype.keyboard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Asks for the microphone when the mic key is first tapped. A keyboard has no window Android will show a permission
 * prompt over, so this bare, see-through activity carries the prompt and closes as soon as it is answered.
 */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return finish()
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (granted) R.string.voice_permission_granted else R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val REQUEST_MICROPHONE = 420
    }
}
