package com.kyo.lanterna

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class LanternaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Themes.apply()
    }
}

object Themes {
    const val AUTO = 0
    const val LIGHT = 1
    const val DARK = 2
    val LABELS = listOf("Automatico (chiaro di giorno, scuro di sera)", "Chiaro", "Scuro")

    fun nightMode(): Int = when (Store.theme) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h in 7..18) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    fun apply() {
        val m = nightMode()
        if (AppCompatDelegate.getDefaultNightMode() != m) AppCompatDelegate.setDefaultNightMode(m)
    }
}

data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val share: String,
    val user: String,
    val password: String,
    val domain: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("share", share).put("user", user).put("password", password).put("domain", domain)

    companion object {
        fun fromJson(o: JSONObject) = ServerConfig(
            id = o.getString("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 0),
            share = o.optString("share"),
            user = o.optString("user"),
            password = o.optString("password"),
            domain = o.optString("domain")
        )
    }
}

/** Buffer del player (RAM per ExoPlayer) + read-ahead SMB (blocchi letti in anticipo). */
data class BufferPreset(val label: String, val playerMb: Int, val readAheadMb: Int)

val BUFFER_PRESETS = listOf(
    BufferPreset("Normale  (player 64 MB + read-ahead 32 MB)", 64, 32),
    BufferPreset("Grande   (player 128 MB + read-ahead 64 MB)", 128, 64),
    BufferPreset("Massimo  (player 192 MB + read-ahead 96 MB)", 192, 96)
)

val PARALLEL_OPTIONS = listOf(2, 4, 6, 8)
val SUB_SIZES = listOf("Piccola" to 0.8f, "Media" to 1.0f, "Grande" to 1.25f, "Enorme" to 1.5f)
val SUB_COLORS = listOf("Bianco" to 0xFFFFFFFF.toInt(), "Giallo" to 0xFFFFE14D.toInt())
val SUB_BGS = listOf("Nessuno" to 0x00000000, "Semitrasparente" to 0x99000000.toInt())
val RESIZE_LABELS = listOf("Adatta", "Riempi", "Stira")

/** Preferenze ricordate per cartella (serie): tracce scelte e ritardi. */
data class SeriesPrefs(
    val audioLang: String? = null,
    val audioLabel: String? = null,
    val subOff: Boolean = false,
    val subLang: String? = null,
    val subLabel: String? = null,
    val subForced: Boolean = false,
    val subDelayMs: Int = 0,
    val audioDelayMs: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        putOpt("audioLang", audioLang)
        putOpt("audioLabel", audioLabel)
        put("subOff", subOff)
        putOpt("subLang", subLang)
        putOpt("subLabel", subLabel)
        put("subForced", subForced)
        put("subDelayMs", subDelayMs)
        put("audioDelayMs", audioDelayMs)
    }

    companion object {
        private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

        fun fromJson(o: JSONObject) = SeriesPrefs(
            audioLang = o.str("audioLang"),
            audioLabel = o.str("audioLabel"),
            subOff = o.optBoolean("subOff", false),
            subLang = o.str("subLang"),
            subLabel = o.str("subLabel"),
            subForced = o.optBoolean("subForced", false),
            subDelayMs = o.optInt("subDelayMs", 0),
            audioDelayMs = o.optInt("audioDelayMs", 0)
        )
    }
}

data class HistoryItem(val key: String, val title: String, val posMs: Long, val durMs: Long, val updated: Long)

object Store {
    private lateinit var p: SharedPreferences

    fun init(c: Context) {
        if (!::p.isInitialized) p = c.applicationContext.getSharedPreferences("lanterna", Context.MODE_PRIVATE)
    }

    // ---- server ----
    fun servers(): List<ServerConfig> {
        val arr = JSONArray(p.getString("servers", "[]"))
        return (0 until arr.length()).map { ServerConfig.fromJson(arr.getJSONObject(it)) }
    }

    fun server(id: String): ServerConfig? = servers().firstOrNull { it.id == id }

    fun saveServer(s: ServerConfig) {
        val list = servers().toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        writeServers(list)
        SmbManager.invalidate(s.id)
    }

    fun deleteServer(id: String) {
        writeServers(servers().filter { it.id != id })
        SmbManager.invalidate(id)
    }

    private fun writeServers(list: List<ServerConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        p.edit().putString("servers", arr.toString()).apply()
    }

    // ---- impostazioni ----
    var bufferPresetIndex: Int
        get() = p.getInt("buffer_preset", 1).coerceIn(0, BUFFER_PRESETS.lastIndex)
        set(v) = p.edit().putInt("buffer_preset", v).apply()

    fun bufferPreset(): BufferPreset = BUFFER_PRESETS[bufferPresetIndex]

    var theme: Int
        get() = p.getInt("theme", Themes.AUTO).coerceIn(0, 2)
        set(v) = p.edit().putInt("theme", v).apply()

    var parallel: Int
        get() = p.getInt("parallel", 4).coerceIn(1, 8)
        set(v) = p.edit().putInt("parallel", v).apply()

    var frameRateMatch: Boolean
        get() = p.getBoolean("fps_match", true)
        set(v) = p.edit().putBoolean("fps_match", v).apply()

    var thumbnails: Boolean
        get() = p.getBoolean("thumbs", true)
        set(v) = p.edit().putBoolean("thumbs", v).apply()

    var subSize: Int
        get() = p.getInt("sub_size", 1).coerceIn(0, SUB_SIZES.lastIndex)
        set(v) = p.edit().putInt("sub_size", v).apply()

    var subColor: Int
        get() = p.getInt("sub_color", 0).coerceIn(0, SUB_COLORS.lastIndex)
        set(v) = p.edit().putInt("sub_color", v).apply()

    var subBg: Int
        get() = p.getInt("sub_bg", 1).coerceIn(0, SUB_BGS.lastIndex)
        set(v) = p.edit().putInt("sub_bg", v).apply()

    var resizeMode: Int
        get() = p.getInt("resize", 0).coerceIn(0, RESIZE_LABELS.lastIndex)
        set(v) = p.edit().putInt("resize", v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong("upd_check", 0L)
        set(v) = p.edit().putLong("upd_check", v).apply()

    var latestBuild: Int
        get() = p.getInt("upd_latest", 0)
        set(v) = p.edit().putInt("upd_latest", v).apply()

    // ---- avanzamento / continua a guardare ----
    fun resume(key: String): Long = p.getLong("r:$key", 0L)

    fun duration(key: String): Long = p.getLong("d:$key", 0L)

    fun saveProgress(key: String, title: String, posMs: Long, durMs: Long) {
        p.edit()
            .putLong("r:$key", posMs)
            .putLong("d:$key", durMs)
            .putString("t:$key", title)
            .putLong("u:$key", System.currentTimeMillis())
            .apply()
    }

    fun clearProgress(key: String) {
        p.edit().remove("r:$key").remove("d:$key").remove("t:$key").remove("u:$key").apply()
    }

    fun history(limit: Int = 12): List<HistoryItem> =
        p.all.keys.filter { it.startsWith("r:") }.map { k ->
            val key = k.substring(2)
            HistoryItem(
                key = key,
                title = p.getString("t:$key", null) ?: Uri.decode(key.substringAfterLast('/')),
                posMs = p.getLong(k, 0L),
                durMs = p.getLong("d:$key", 0L),
                updated = p.getLong("u:$key", 0L)
            )
        }.filter { it.posMs > 0 }.sortedByDescending { it.updated }.take(limit)

    // ---- visto ----
    fun watched(key: String): Boolean = p.getBoolean("w:$key", false)

    fun setWatched(key: String, v: Boolean) {
        if (v) p.edit().putBoolean("w:$key", true).apply() else p.edit().remove("w:$key").apply()
    }

    // ---- preferenze per serie ----
    fun seriesPrefs(folderKey: String): SeriesPrefs =
        p.getString("sp:$folderKey", null)?.let { runCatching { SeriesPrefs.fromJson(JSONObject(it)) }.getOrNull() }
            ?: SeriesPrefs()

    fun saveSeriesPrefs(folderKey: String, s: SeriesPrefs) {
        p.edit().putString("sp:$folderKey", s.toJson().toString()).apply()
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
