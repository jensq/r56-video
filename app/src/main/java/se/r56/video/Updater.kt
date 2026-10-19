package se.r56.video

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer build and installs it (the user confirms in Android's installer). */
object Updater {
    private const val REPO = "jensq/r56-video"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    class Release(val build: Int, val name: String, val url: String, val size: Long)

    fun currentBuild(a: Activity): Int {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    fun currentName(a: Activity): String = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?"

    private fun fetchLatest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null // no release published yet
            if (c.responseCode != 200) throw java.io.IOException("GitHub answered ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name") // build-<number>
            val num = tag.substringAfterLast('-').toIntOrNull() ?: return null
            val assets = o.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val s = assets.getJSONObject(i)
                if (s.getString("name").endsWith(".apk")) {
                    return Release(num, "1.0.$num", s.getString("browser_download_url"), s.optLong("size", 0))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** silent = true: only speak up if an update exists (used at app start). */
    fun check(a: Activity, silent: Boolean) {
        Thread {
            try {
                val r = fetchLatest()
                val cur = currentBuild(a)
                a.runOnUiThread {
                    if (a.isDestroyed || a.isFinishing) return@runOnUiThread
                    if (r != null && r.build > cur) {
                        offer(a, r)
                    } else if (!silent) {
                        Toast.makeText(a, "You have the latest version (${currentName(a)})", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) a.runOnUiThread {
                    Toast.makeText(a, "Update check failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun offer(a: Activity, r: Release) {
        val size = if (r.size > 0) " (${Ui.fmtSize(r.size)})" else ""
        AlertDialog.Builder(a)
            .setTitle("Update available")
            .setMessage("Version ${r.name}$size is available. You have ${currentName(a)}.\n\nDownload and install now?")
            .setPositiveButton("Update") { _, _ -> download(a, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(a: Activity, r: Release) {
        val dlg = ProgressDialog(a)
        dlg.setTitle("Downloading update")
        dlg.setMessage("Version ${r.name}")
        dlg.isIndeterminate = false
        dlg.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dlg.max = 100
        dlg.setCancelable(false)
        dlg.show()
        Thread {
            try {
                val dir = File(a.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "R56-video.apk")
                val c = URL(r.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                if (c.responseCode != 200) throw java.io.IOException("Download failed (${c.responseCode})")
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) {
                                    last = pct
                                    a.runOnUiThread { dlg.progress = pct }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                a.runOnUiThread {
                    dlg.dismiss()
                    if (!a.isDestroyed) install(a, out)
                }
            } catch (e: Exception) {
                a.runOnUiThread {
                    dlg.dismiss()
                    if (!a.isDestroyed) {
                        AlertDialog.Builder(a).setTitle("Update failed").setMessage(e.message ?: e.toString())
                            .setPositiveButton("Close", null).show()
                    }
                }
            }
        }.start()
    }

    private fun install(a: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a)
                .setTitle("Allow installing updates")
                .setMessage("Android needs your permission for R56 video to install apps. Enable \"Allow from this source\" on the next screen, then choose Check for updates again.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        a.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
                        )
                    } catch (e: Exception) {
                        Toast.makeText(
                            a,
                            "Open Settings → Apps → Security & restrictions → Unknown sources and enable R56 video",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            a.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(a, "Could not start installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
