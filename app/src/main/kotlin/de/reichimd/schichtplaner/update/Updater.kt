package de.reichimd.schichtplaner.update

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import de.reichimd.schichtplaner.BuildConfig
import de.reichimd.schichtplaner.core.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sucht auf GitHub nach einer neueren Version, lädt die APK und öffnet den Android-Installer.
 * Android prüft beim Installieren selbst, dass die neue APK mit demselben Schlüssel signiert ist.
 */
class Updater(private val context: Context) {

    private val updateDir get() = File(context.cacheDir, "updates")

    /** Fragt GitHub nach allen Versionen. Wirft IOException ohne Netz. */
    suspend fun check(): UpdateCheck.Update? = withContext(Dispatchers.IO) {
        val json = get(RELEASES_URL)
        UpdateCheck.newest(parse(json), BuildConfig.VERSION_CODE)
    }

    /** Lädt die APK in den App-Cache und prüft, ob sie wirklich unsere neuere App ist. */
    suspend fun download(update: UpdateCheck.Update, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = updateDir.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "download.part")
        val apk = File(dir, "schichtplaner-${update.versionCode}.apk")

        val conn = open(update.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Download: HTTP ${conn.responseCode}")
            var read = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > update.apkSize) throw IOException("Download größer als angekündigt")
                        out.write(buf, 0, n)
                        progress(read.toFloat() / update.apkSize)
                    }
                }
            }
            if (read != update.apkSize) throw IOException("Download unvollständig ($read von ${update.apkSize} Bytes)")
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            conn.disconnect()
        }

        val info = context.packageManager.getPackageArchiveInfo(part.path, 0)
        val code = info?.let(::versionCodeOf)
        if (info == null || info.packageName != context.packageName || code != update.versionCode.toLong()) {
            part.delete()
            throw IOException("Die geladene Datei ist nicht die erwartete App-Version")
        }
        if (!part.renameTo(apk)) throw IOException("Datei konnte nicht gespeichert werden")
        apk
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Darf diese App andere Apps installieren? Muss einmalig in den Einstellungen erlaubt werden. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Wirft ActivityNotFoundException, wenn das Gerät keine passende Einstellungsseite hat. */
    fun openInstallPermissionSettings() {
        val pkg = Uri.parse("package:${context.packageName}")
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            // Manche Geräte haben die Seite nicht: dann wenigstens die App-Infos öffnen.
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Alte Downloads aufräumen, z. B. nach erfolgreichem Update. */
    fun cleanUp() {
        updateDir.listFiles()?.forEach { it.delete() }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Schichtplaner-App/${BuildConfig.VERSION_NAME}")
        }

    private fun get(url: String): String {
        val conn = open(url).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            val status = conn.responseCode
            if (status == 403 || status == 429) throw RateLimitException()
            if (status != HttpURLConnection.HTTP_OK) throw IOException("GitHub: HTTP $status")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** GitHub erlaubt ohne Anmeldung 60 Abfragen pro Stunde und Anschluss. */
    class RateLimitException : IOException("GitHub-Limit erreicht")

    companion object {
        /**
         * GitHub sortiert nach Erstellzeit, neueste zuerst. Weil jede Version höher sein muss als alle
         * bisherigen (prüft der Release-Workflow), ist die neueste immer auf der ersten Seite.
         */
        const val RELEASES_URL = "https://api.github.com/repos/ReichiMD/Schichtplaner/releases?per_page=100"

        /** Liest die Antwort der GitHub-API. Unvollständige Einträge werden übersprungen. */
        fun parse(json: String): List<UpdateCheck.Release> {
            val arr = JSONArray(json)
            return (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val assets = r.optJSONArray("assets") ?: JSONArray()
                UpdateCheck.Release(
                    tag = r.optString("tag_name"),
                    draft = r.optBoolean("draft", true),
                    prerelease = r.optBoolean("prerelease", false),
                    assets = (0 until assets.length()).mapNotNull { j ->
                        val a = assets.optJSONObject(j) ?: return@mapNotNull null
                        if (a.optString("state", "uploaded") != "uploaded") return@mapNotNull null
                        UpdateCheck.Asset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size", 0))
                    },
                )
            }
        }

        /** Ist die APK mit dem Release-Schlüssel signiert? Debug-Builds können nicht auf Releases updaten. */
        fun isReleaseBuild(): Boolean = !BuildConfig.DEBUG
    }
}
