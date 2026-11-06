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
        /** Settings → About links. Opened in the browser on a tap only. */
        const val SITE_URL = "https://lukesteuber.com/"
        const val SUPPORT_URL = "https://lukesteuber.com/#contact"
        const val BLUESKY_URL = "https://bsky.app/profile/coolhand.bsky.social"
        const val LATEST_URL = PAGE_URL + "latest.json"

        private val CODE = Regex("\"versionCode\"\\s*:\\s*(\\d+)")
        private val NAME = Regex("\"versionName\"\\s*:\\s*\"([^\"]{1,40})\"")
        private val ABIS = Regex("\"abis\"\\s*:\\s*\\[([^\\]]{0,200})]")
        private val ABI = Regex("\"([A-Za-z0-9_-]{1,24})\"")

        /** Reads the fields it needs; anything malformed gives null. */
        fun parse(json: String): ReleaseInfo? {
            val code = CODE.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val name = NAME.find(json)?.groupValues?.get(1) ?: return null
            val abis = ABIS.find(json)?.groupValues?.get(1)?.let { list -> ABI.findAll(list).map { it.groupValues[1] }.toList() }
            return ReleaseInfo(code, name, abis.orEmpty())
        }
    }
}
