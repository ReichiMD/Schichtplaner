package de.reichimd.schichtplaner

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.reichimd.schichtplaner.core.update.UpdateCheck
import de.reichimd.schichtplaner.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prüft das Lesen der GitHub-Antwort mit dem echten org.json von Android. */
@RunWith(AndroidJUnit4::class)
class UpdaterOnDeviceTest {

    private val p = UpdateCheck.DOWNLOAD_PREFIX

    private val json = """
        [
          {"tag_name": "v0.2.0", "draft": false, "prerelease": true,
           "assets": [{"name": "schichtplaner-0.2.0.apk", "state": "uploaded", "size": 8240915,
                       "browser_download_url": "${p}v0.2.0/schichtplaner-0.2.0.apk"}]},
          {"tag_name": "v0.3.0", "draft": false, "prerelease": false,
           "assets": [{"name": "schichtplaner-0.3.0.apk", "state": "starter", "size": 8240915,
                       "browser_download_url": "${p}v0.3.0/schichtplaner-0.3.0.apk"}]},
          {"tag_name": "v0.1.0", "draft": false, "prerelease": true, "assets": []},
          {"tag_name": "v9.0.0"},
          "unsinn",
          {"draft": false, "assets": [{"name": "x.apk"}]}
        ]
    """.trimIndent()

    @Test
    fun githubAntwortWirdGelesen() {
        val releases = Updater.parse(json)
        assertEquals(5, releases.size)
        // Ohne "draft"-Angabe gilt ein Eintrag sicherheitshalber als Entwurf.
        assertTrue(releases.first { it.tag == "v9.0.0" }.draft)
        // Halb hochgeladene Dateien werden nicht angeboten.
        assertTrue(releases.first { it.tag == "v0.3.0" }.assets.isEmpty())

        val u = UpdateCheck.newest(releases, currentCode = 100)
        assertEquals("0.2.0", u?.versionName)
        assertEquals(8240915L, u?.apkSize)
        assertNull(UpdateCheck.newest(releases, currentCode = 200))
    }

    @Test(expected = org.json.JSONException::class)
    fun kaputteAntwortWirftFehler() {
        Updater.parse("<html>Rate limit</html>")
    }
}
