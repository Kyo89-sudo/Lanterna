package com.kyo.lanterna

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Aggiornamento dall'app: controlla l'ultima release su GitHub, scarica e installa l'APK. */
object Updater {
    private const val REPO = "Kyo89-sudo/Lanterna"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK = "https://github.com/$REPO/releases/latest/download/lanterna.apk"

    fun currentBuild(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            ?.substringAfterLast('.')?.toIntOrNull() ?: 0
    } catch (e: Exception) {
        0
    }

    private fun connect(url: URL): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Lanterna")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect senza destinazione")
                c.disconnect()
                u = URL(u, loc)
            } else {
                if (code != 200) {
                    c.disconnect()
                    throw IOException("HTTP $code")
                }
                return c
            }
        }
        throw IOException("Troppi redirect")
    }

    /** Numero dell'ultima build pubblicata (tag "build-N"). */
    fun latestBuild(): Int {
        val c = connect(URL(API))
        try {
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(txt).optString("tag_name").substringAfter("build-").toIntOrNull() ?: 0
        } finally {
            c.disconnect()
        }
    }

    fun download(ctx: Context, progress: (Int) -> Unit): java.io.File {
        val dir = java.io.File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = java.io.File(dir, "lanterna.apk")
        val c = connect(URL(APK))
        try {
            val total = c.contentLength.toLong()
            var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                progress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(a: Activity): Boolean = try {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
        true
    } catch (e: Exception) {
        false
    }

    fun install(ctx: Context, apk: java.io.File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
