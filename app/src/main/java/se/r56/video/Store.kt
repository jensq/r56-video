package se.r56.video

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A saved SMB location (also used as "favorite folder"). */
data class Source(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val path: String,      // folder inside the share, '/' separated, no leading/trailing slash
    val user: String,
    val pass: String,
    val domain: String
) {
    val connKey: String get() = "$host|$share|$user|$domain"
    val address: String get() = "smb://$host/$share" + if (path.isEmpty()) "" else "/$path"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("share", share)
        .put("path", path).put("user", user).put("pass", pass).put("domain", domain)

    companion object {
        fun fromJson(o: JSONObject) = Source(
            o.getString("id"), o.getString("name"), o.getString("host"), o.getString("share"),
            o.optString("path", ""), o.optString("user", ""), o.optString("pass", ""), o.optString("domain", "")
        )

        /** Parses "smb://host/share/folder" (or //host/share, host/share) into host, share, path. */
        fun parseAddress(input: String): Triple<String, String, String>? {
            var s = input.trim().replace('\\', '/')
            if (s.lowercase().startsWith("smb://")) s = s.substring(6)
            val parts = s.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            return Triple(parts[0], parts[1], parts.drop(2).joinToString("/"))
        }

        /** Splits "DOMAIN\user" into (domain, user). */
        fun splitUser(input: String): Pair<String, String> {
            val u = input.trim()
            val i = u.indexOf('\\')
            return if (i > 0) Pair(u.substring(0, i), u.substring(i + 1)) else Pair("", u)
        }
    }
}

data class Progress(val pos: Long, val dur: Long, val watched: Boolean)

object Store {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("r56", Context.MODE_PRIVATE)
    }

    // ---- settings ----------------------------------------------------------------------------

    /** Text size (sp) of file and folder names in lists. */
    var textSizeSp: Int
        get() = prefs.getInt("textSize", 20)
        set(v) = prefs.edit().putInt("textSize", v).apply()

    var seekBackSec: Int
        get() = prefs.getInt("seekBack", 10)
        set(v) = prefs.edit().putInt("seekBack", v).apply()

    var seekForwardSec: Int
        get() = prefs.getInt("seekForward", 30)
        set(v) = prefs.edit().putInt("seekForward", v).apply()

    // ---- sources -----------------------------------------------------------------------------

    @Synchronized
    fun sources(): List<Source> {
        val raw = prefs.getString("sources", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Source.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun source(id: String): Source? = sources().firstOrNull { it.id == id }

    @Synchronized
    private fun saveSources(list: List<Source>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("sources", arr.toString()).apply()
    }

    @Synchronized
    fun upsert(s: Source) {
        val list = sources().toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        saveSources(list)
    }

    @Synchronized
    fun remove(id: String) = saveSources(sources().filter { it.id != id })

    fun newId(): String = System.currentTimeMillis().toString(36) + (Math.random() * 1000).toInt().toString(36)

    // ---- watch state -------------------------------------------------------------------------

    fun fileKey(src: Source, path: String) = "w:${src.host}/${src.share}/$path".lowercase()

    @Synchronized
    fun getProgress(key: String): Progress? {
        val s = prefs.getString(key, null) ?: return null
        val p = s.split("|")
        if (p.size < 3) return null
        return try {
            Progress(p[0].toLong(), p[1].toLong(), p[2] == "1")
        } catch (e: NumberFormatException) {
            null
        }
    }

    @Synchronized
    fun setProgress(key: String, pos: Long, dur: Long, watched: Boolean) {
        prefs.edit().putString(key, "$pos|$dur|${if (watched) 1 else 0}").apply()
    }

    @Synchronized
    fun clearProgress(key: String) {
        prefs.edit().remove(key).apply()
    }
}
