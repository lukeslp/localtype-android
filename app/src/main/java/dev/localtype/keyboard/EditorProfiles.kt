package dev.localtype.keyboard

/** Exact qualified editor versions only. Ordinary applications receive repair offers by default. */
object EditorProfiles {
    data class Profile(val packageName: String, val version: Long, val api: Int, val inputType: Int)
    // Populate only from measured range-replacement and Undo fixture acceptance evidence.
    private val qualified = emptySet<Profile>()
    fun allowsRangeEdit(profile: Profile): Boolean = profile.api >= 34 && profile in qualified
}
