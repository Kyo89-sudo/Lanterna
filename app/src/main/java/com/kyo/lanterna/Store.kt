package com.kyo.lanterna

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class LanternaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
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

    // ---- buffer ----
    var bufferPresetIndex: Int
        get() = p.getInt("buffer_preset", 1).coerceIn(0, BUFFER_PRESETS.lastIndex)
        set(v) = p.edit().putInt("buffer_preset", v).apply()

    fun bufferPreset(): BufferPreset = BUFFER_PRESETS[bufferPresetIndex]

    // ---- posizione di ripresa ----
    fun resume(key: String): Long = p.getLong("r:$key", 0L)

    fun setResume(key: String, posMs: Long) {
        if (posMs <= 0) p.edit().remove("r:$key").apply()
        else p.edit().putLong("r:$key", posMs).apply()
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
