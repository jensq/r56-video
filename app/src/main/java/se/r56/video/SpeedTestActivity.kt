package se.r56.video

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Reads a large file from the server for about 10 seconds in two ways and reports MB/s plus how busy the CPU was.
 * Tells apart "slow link / server" from "the device spends its CPU on the network traffic".
 */
class SpeedTestActivity : Activity() {
    private class Result(val bytes: Long, val ms: Long, val cpuMs: Long) {
        val mbPerSec: Double get() = if (ms > 0) bytes / 1048576.0 / (ms / 1000.0) else 0.0
        val mbitPerSec: Double get() = if (ms > 0) bytes * 8 / 1e6 / (ms / 1000.0) else 0.0
        val cpuPercent: Int get() = if (ms > 0) (cpuMs * 100 / ms).toInt() else 0
    }

    private lateinit var shell: Ui.Shell
    private lateinit var out: TextView
    private val lines = StringBuilder()
    @Volatile private var cancelled = false
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell = Ui.shell(this, L.t("Network speed test"))
        shell.subtitle.text = ""

        out = TextView(this)
        out.setTextColor(Ui.TEXT)
        out.textSize = 16f
        out.typeface = android.graphics.Typeface.MONOSPACE
        val scroll = ScrollView(this)
        scroll.addView(out, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        shell.content.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_refresh, L.t("Run again")) { begin() })
        Ui.addMenu(shell, Ui.menuButton(this, R.drawable.ic_home, L.t("Back")) { finish() })
        setContentView(shell.root)
        begin()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!shell.handleBack()) super.onBackPressed()
    }

    private fun append(s: String) {
        synchronized(lines) { lines.append(s).append('\n') }
        runOnUiThread {
            if (!isDestroyed) out.text = synchronized(lines) { lines.toString() }
        }
    }

    private fun begin() {
        if (running) return
        val sources = Store.sources()
        synchronized(lines) { lines.setLength(0) }
        out.text = ""
        when {
            sources.isEmpty() -> append(L.t("Add a location first (Home → Add location)."))
            sources.size == 1 -> run(sources[0])
            else -> AlertDialog.Builder(this)
                .setTitle(L.t("Choose a location to test"))
                .setItems(sources.map { it.name }.toTypedArray()) { _, i -> run(sources[i]) }
                .show()
        }
    }

    private fun run(src: Source) {
        running = true
        Thread {
            try {
                test(src)
            } catch (e: Throwable) {
                append("")
                append(L.f("Error: %s", e.message ?: e.toString()))
            } finally {
                running = false
            }
        }.start()
    }

    private fun test(src: Source) {
        append(L.t("Looking for a large file…"))
        val big = findBigFile(src)
        if (big == null) {
            append(L.t("No file of at least 30 MB found in this location (searched two levels down). Choose a favorite that points to a folder with videos."))
            return
        }
        append(L.f("Test file: %s (%s)", big.first.substringAfterLast('/'), Ui.fmtSize(big.second)))
        append(connectionLine(src))

        append("")
        append(L.t("Test 1: one read at a time (about 10 s)…"))
        val r1 = measure(SmbFileReader(src, big.first), (big.second * 0.10).toLong(), 1000, 9000)
        append(describe(r1))
        if (cancelled) return

        append("")
        append(L.t("Test 2: as during playback, several reads at once (about 10 s)…"))
        val big2 = Store.bigBuffer
        val base = SmbFileReader(src, big.first)
        val par = ParallelReader(base, if (big2) 5 else 3, if (big2) 64 else 32)
        val r2 = measure(par, (big.second * 0.55).toLong(), 2000, 10000)
        append(describe(r2))
        if (cancelled) return

        append("")
        val best = maxOf(r1.mbitPerSec, r2.mbitPerSec)
        append(
            when {
                best >= 25 -> L.t("Result: fast enough for most 1080p films (over 25 Mbit/s).")
                best >= 10 -> L.t("Result: borderline (10–25 Mbit/s). Films with a high bitrate may stall.")
                else -> L.t("Result: too slow for 1080p films (under 10 Mbit/s).")
            }
        )
        val cpuBound = maxOf(r1.cpuPercent, r2.cpuPercent) >= 85 && best < 25
        append(
            if (cpuBound) L.t("The processor is the bottleneck: the device spends its power on handling the network traffic (often SMB signing or encryption). Check the server's signing/encryption settings.")
            else if (best < 25) L.t("The processor is not the limit, so the cause is the wifi link or the server. Try 5 GHz or a network cable, and check the server's load.")
            else L.t("The network is not the problem on this connection.")
        )
    }

    /** Reads sequentially from startPos; only the part after warmMs is counted. Closes the reader. */
    private fun measure(reader: RandomReader, startPos: Long, warmMs: Long, totalMs: Long): Result {
        val buf = ByteArray(256 * 1024)
        var pos = startPos
        var counted = 0L
        var tStart = -1L
        var cpu0 = 0L
        val t0 = SystemClock.elapsedRealtime()
        try {
            while (!cancelled) {
                val now = SystemClock.elapsedRealtime()
                if (now - t0 >= totalMs) break
                if (tStart < 0 && now - t0 >= warmMs) {
                    tStart = now
                    cpu0 = android.os.Process.getElapsedCpuTime()
                }
                val n = reader.read(pos, buf, 0, buf.size)
                if (n <= 0) break
                pos += n
                if (tStart >= 0) counted += n
            }
            val end = SystemClock.elapsedRealtime()
            val cpu = android.os.Process.getElapsedCpuTime() - cpu0
            return if (tStart < 0) Result(0, 0, 0) else Result(counted, end - tStart, cpu)
        } finally {
            reader.close()
        }
    }

    private fun describe(r: Result): String =
        if (r.ms <= 0) "-" else L.f("%.1f MB/s (%.0f Mbit/s)   CPU %d%% of one core", r.mbPerSec, r.mbitPerSec, r.cpuPercent)

    private fun findBigFile(src: Source): Pair<String, Long>? {
        val minSize = 30L * 1024 * 1024
        var best: Pair<String, Long>? = null
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(Pair(src.path, 0))
        var listings = 0
        while (queue.isNotEmpty() && listings < 40 && !cancelled) {
            val (dir, depth) = queue.removeFirst()
            listings++
            val entries = try {
                Smb.list(src, dir)
            } catch (e: Exception) {
                if (depth == 0) throw e else continue
            }
            for (e in entries) {
                val p = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                if (e.isDir) {
                    if (depth < 2) queue.add(Pair(p, depth + 1))
                } else {
                    val cur = best
                    if (e.size >= minSize && (cur == null || e.size > cur.second)) best = Pair(p, e.size)
                }
            }
        }
        return best
    }

    // ---- connection details via reflection (so a library API difference can never break the build) ----

    private fun call(o: Any?, name: String): Any? = try {
        if (o == null) {
            null
        } else {
            val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            m?.isAccessible = true
            m?.invoke(o)
        }
    } catch (_: Throwable) {
        null
    }

    private fun yesNo(v: Any?): String = when (v) {
        true -> L.t("yes")
        false -> L.t("no")
        else -> L.t("unknown")
    }

    private fun connectionLine(src: Source): String = try {
        Smb.withShare(src) { sh ->
            val session = call(call(sh, "getTreeConnect"), "getSession")
            val conn = call(session, "getConnection")
            val dialect = call(call(conn, "getNegotiatedProtocol"), "getDialect")?.toString()
                ?.removePrefix("SMB_")?.replace('_', '.') ?: "?"
            val ctx = call(session, "getSessionContext")
            val signing = call(ctx, "isSigningRequired")
            val enc = call(ctx, "isEncryptData") ?: call(ctx, "encryptData")
            L.f("Connection: SMB %s, signing required: %s, encryption: %s", dialect, yesNo(signing), yesNo(enc))
        }
    } catch (e: Exception) {
        L.f("Error: %s", e.message ?: e.toString())
    }
}
