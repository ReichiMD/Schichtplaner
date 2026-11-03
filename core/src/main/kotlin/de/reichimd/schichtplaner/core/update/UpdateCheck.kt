package de.reichimd.schichtplaner.core.update

/**
 * Entscheidet, ob es eine neuere App-Version auf GitHub gibt. Reines Kotlin ohne Android,
 * damit es automatisch getestet werden kann. Das Laden und Installieren macht die App.
 */
object UpdateCheck {

    /** Nur von hier werden APKs geladen. */
    const val DOWNLOAD_PREFIX = "https://github.com/ReichiMD/Schichtplaner/releases/download/"

    private val NUM = "(0|[1-9][0-9]{0,5})"
    private val TAG = Regex("^v$NUM\\.$NUM\\.$NUM$")

    /**
     * Gleiche Rechnung wie im Release-Workflow: Major * 10000 + Minor * 100 + Patch.
     * Gibt null zurück, wenn der Tag nicht dem Muster vX.Y.Z entspricht.
     */
    fun versionCodeOf(tag: String): Int? {
        val m = TAG.matchEntire(tag.trim()) ?: return null
        val (major, minor, patch) = m.destructured.toList().map { it.toInt() }
        if (minor > 99 || patch > 99) return null
        // Android erlaubt höchstens 2.100.000.000 als versionCode.
        if (major > 209_999) return null
        return major * 10_000 + minor * 100 + patch
    }

    /**
     * Die höchste veröffentlichte Version, die neuer als [currentCode] ist und eine
     * ladbare APK hat. Entwürfe und Einträge ohne gültigen Tag werden übersprungen.
     */
    fun newest(releases: List<Release>, currentCode: Int, includePrereleases: Boolean = true): Update? =
        releases.asSequence()
            .filter { !it.draft && (includePrereleases || !it.prerelease) }
            .mapNotNull { r ->
                val code = versionCodeOf(r.tag) ?: return@mapNotNull null
                val name = r.tag.removePrefix("v")
                // Genau die Datei, die der Release-Workflow anhängt, nie eine andere APK.
                val apk = r.assets.firstOrNull { it.name == apkName(name) && it.isUsableApk() } ?: return@mapNotNull null
                Update(name, code, r.prerelease, apk.url, apk.size)
            }
            .filter { it.versionCode > currentCode }
            .maxByOrNull { it.versionCode }

    /** So nennt der Release-Workflow die APK. */
    fun apkName(versionName: String) = "schichtplaner-$versionName.apk"

    private fun Asset.isUsableApk() =
        size in 1..MAX_APK_BYTES &&
            url.startsWith(DOWNLOAD_PREFIX) &&
            !url.contains("..")

    /** Schutz gegen kaputte Angaben: unsere APK ist rund 8 MB. */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val tag: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val assets: List<Asset>,
    )

    data class Update(
        val versionName: String,
        val versionCode: Int,
        val prerelease: Boolean,
        val apkUrl: String,
        val apkSize: Long,
    )
}
