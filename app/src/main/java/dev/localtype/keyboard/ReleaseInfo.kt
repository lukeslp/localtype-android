package dev.localtype.keyboard

/**
 * The newest published release, as listed in `latest.json` beside the APK on the download page. [abis] names the
 * processor types its native code is built for; a file without the field describes a build for every processor.
 */
data class ReleaseInfo(val versionCode: Int, val versionName: String, val abis: List<String> = emptyList()) {
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    /** Whether a device supporting [deviceAbis] (`Build.SUPPORTED_ABIS`) can install this release at all. */
    fun installable(deviceAbis: Array<String>): Boolean = abis.isEmpty() || abis.any { it in deviceAbis }

    companion object {
        const val PAGE_URL = "https://dr.eamer.dev/downloads/apps/localtype/"
        const val CHANGELOG_URL = PAGE_URL + "changelog.html"
        /** The catalog build's own releases, each with its notes, beside its public source. */
        const val CATALOG_RELEASES_URL = "https://github.com/lukeslp/localtype-android/releases"
        /** Settings → About links. Opened in the browser on a tap only. */
        const val SITE_URL = "https://lukesteuber.com/"
        const val SUPPORT_URL = "https://lukesteuber.com/#contact"
        const val BLUESKY_URL = "https://bsky.app/profile/coolhand.bsky.social"
        const val CHIME_URL = "https://lukesteuber.com/chime/"
        /**
         * LocalLauncher's page on the download site. Like [PAGE_URL], only a build that updates from that site links
         * to it: Google Play does not allow pointing at an app offered as a file, and the catalog build stays off
         * the site.
         */
        const val LAUNCHER_URL = "https://dr.eamer.dev/downloads/apps/locallauncher/"
        const val LATEST_URL = PAGE_URL + "latest.json"

        /**
         * The version codes a release can have. Not now stores the code it was offered and hides the banner up to
         * that code, so one absurd code in a bad file would hide every later release.
         */
        val VERSION_CODES = 1..999_999

        private val CODE = Regex("\"versionCode\"\\s*:\\s*(\\d+)")
        // The name is shown in settings as it arrives, so it is held to the characters a version name uses.
        private val NAME = Regex("\"versionName\"\\s*:\\s*\"([0-9A-Za-z.+_-]{1,40})\"")
        private val ABIS = Regex("\"abis\"\\s*:\\s*\\[([^\\]]{0,200})]")
        private val ABI = Regex("\"([A-Za-z0-9_-]{1,24})\"")

        /**
         * Where About's Release notes row goes for the build of [flavor]. The catalog build opens its own releases:
         * the download site's page is for the other build, which a catalog app should not point people to.
         */
        fun releaseNotesUrl(flavor: String): String = if (flavor == CATALOG_FLAVOR) CATALOG_RELEASES_URL else CHANGELOG_URL

        const val CATALOG_FLAVOR = "fdroid"

        /** Reads the fields it needs; anything malformed gives null. */
        fun parse(json: String): ReleaseInfo? {
            val code = CODE.find(json)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in VERSION_CODES } ?: return null
            val name = NAME.find(json)?.groupValues?.get(1) ?: return null
            val abis = ABIS.find(json)?.groupValues?.get(1)?.let { list -> ABI.findAll(list).map { it.groupValues[1] }.toList() }
            return ReleaseInfo(code, name, abis.orEmpty())
        }
    }
}
