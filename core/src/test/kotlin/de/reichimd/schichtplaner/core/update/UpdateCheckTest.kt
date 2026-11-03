package de.reichimd.schichtplaner.core.update

import de.reichimd.schichtplaner.core.update.UpdateCheck.Asset
import de.reichimd.schichtplaner.core.update.UpdateCheck.Release
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckTest {

    private fun apk(tag: String, size: Long = 8_000_000) = Asset(
        "schichtplaner-${tag.removePrefix("v")}.apk",
        "${UpdateCheck.DOWNLOAD_PREFIX}$tag/schichtplaner-${tag.removePrefix("v")}.apk",
        size,
    )

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, assets: List<Asset> = listOf(apk(tag))) =
        Release(tag, draft, prerelease, assets)

    @Test
    fun `Versionsnummer wird wie im Release-Workflow berechnet`() {
        assertEquals(100, UpdateCheck.versionCodeOf("v0.1.0"))
        assertEquals(10203, UpdateCheck.versionCodeOf("v1.2.3"))
        assertEquals(9999, UpdateCheck.versionCodeOf("v0.99.99"))
    }

    @Test
    fun `ungueltige Tags werden ignoriert`() {
        listOf("0.1.0", "v01.0.0", "v0.100.0", "v0.1", "v0.1.0-beta", "", "vX.Y.Z", "v999999999.0.0")
            .forEach { assertNull(UpdateCheck.versionCodeOf(it), it) }
    }

    @Test
    fun `neueste hoehere Version wird gewaehlt, egal in welcher Reihenfolge`() {
        val releases = listOf(release("v0.1.1"), release("v0.3.0", prerelease = true), release("v0.2.0"))
        val u = UpdateCheck.newest(releases, currentCode = 100)
        assertEquals("0.3.0", u?.versionName)
        assertEquals(300, u?.versionCode)
    }

    @Test
    fun `keine Meldung wenn die App schon aktuell ist`() {
        assertNull(UpdateCheck.newest(listOf(release("v0.1.0")), currentCode = 100))
        assertNull(UpdateCheck.newest(listOf(release("v0.1.0")), currentCode = 200))
        assertNull(UpdateCheck.newest(emptyList(), currentCode = 100))
    }

    @Test
    fun `Testversionen nur wenn gewuenscht`() {
        val releases = listOf(release("v0.2.0", prerelease = true), release("v0.1.5"))
        assertEquals("0.2.0", UpdateCheck.newest(releases, 100, includePrereleases = true)?.versionName)
        assertEquals("0.1.5", UpdateCheck.newest(releases, 100, includePrereleases = false)?.versionName)
    }

    @Test
    fun `Entwuerfe und Versionen ohne APK werden uebersprungen`() {
        val releases = listOf(
            release("v0.4.0", draft = true),
            release("v0.3.0", assets = emptyList()),
            release("v0.2.5", assets = listOf(Asset("notes.txt", "${UpdateCheck.DOWNLOAD_PREFIX}v0.2.5/notes.txt", 10))),
            release("v0.2.0"),
        )
        assertEquals("0.2.0", UpdateCheck.newest(releases, 100)?.versionName)
    }

    @Test
    fun `APKs von fremden Adressen oder mit unsinniger Groesse werden nicht geladen`() {
        val n = "schichtplaner-0.2.0.apk"
        val fremd = Asset(n, "https://example.com/$n", 8_000_000)
        val umweg = Asset(n, "${UpdateCheck.DOWNLOAD_PREFIX}../../evil/$n", 8_000_000)
        val http = Asset(n, UpdateCheck.DOWNLOAD_PREFIX.replace("https", "http") + "v0.2.0/$n", 8_000_000)
        listOf(fremd, umweg, http, apk("v0.2.0", size = 0), apk("v0.2.0", size = UpdateCheck.MAX_APK_BYTES + 1)).forEach {
            assertNull(UpdateCheck.newest(listOf(release("v0.2.0", assets = listOf(it))), 100), it.toString())
        }
    }

    @Test
    fun `nur die APK mit dem Namen aus dem Release-Workflow wird genommen`() {
        val p = UpdateCheck.DOWNLOAD_PREFIX
        val debug = Asset("app-debug.apk", "${p}v0.2.0/app-debug.apk", 9_000_000)
        val richtig = apk("v0.2.0")
        assertNull(UpdateCheck.newest(listOf(release("v0.2.0", assets = listOf(debug))), 100))
        assertEquals(richtig.url, UpdateCheck.newest(listOf(release("v0.2.0", assets = listOf(debug, richtig))), 100)?.apkUrl)
    }
}
